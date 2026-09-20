package harness

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Edge is one running Nautilus controller publishing Sparkplug B: a private
// copy of a project directory (./edge beside the test by default) with its own node id and API port, so tests
// don't share state and can run while a hand-started edge is up.
type Edge struct {
	Group string
	Node  string
	Addr  string // host:port of the tag API
	Dir   string

	cmd    *exec.Cmd
	log    *syncBuffer
	client *http.Client
	done   chan struct{}
}

// EdgeOptions are the parts of the manifest a test may want to change.
type EdgeOptions struct {
	Node        string // Sparkplug edge node id; required
	ProjectDir  string // default ./edge, relative to the test's working directory
	Broker      string // default IGNITION_IT_BROKER or tcp://localhost:1883
	PrimaryHost string // the host application's id; the edge holds data while it is offline
}

// StartEdge copies the project, rewrites its manifest and runs it. The
// caller owns Stop or Kill.
func StartEdge(dir string, opt EdgeOptions) (*Edge, error) {
	if opt.ProjectDir == "" {
		opt.ProjectDir = filepath.Join("edge")
	}
	if opt.Broker == "" {
		opt.Broker = env("IGNITION_IT_BROKER", "tcp://localhost:1883")
	}
	port, err := freePort()
	if err != nil {
		return nil, err
	}
	e := &Edge{
		Node:   opt.Node,
		Addr:   fmt.Sprintf("localhost:%d", port),
		Dir:    dir,
		log:    &syncBuffer{},
		client: &http.Client{Timeout: 5 * time.Second},
	}
	if err := e.stage(opt); err != nil {
		return nil, err
	}
	return e, e.start()
}

// stage copies the project into e.Dir with this edge's identity written in.
func (e *Edge) stage(opt EdgeOptions) error {
	entries, err := os.ReadDir(opt.ProjectDir)
	if err != nil {
		return fmt.Errorf("edge project: %w", err)
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		raw, err := os.ReadFile(filepath.Join(opt.ProjectDir, entry.Name()))
		if err != nil {
			return err
		}
		if entry.Name() == "nautilus.yaml" {
			if m := groupLine.FindSubmatch(raw); m != nil {
				e.Group = string(m[1])
			}
			raw = []byte(rewrite(string(raw), map[string]string{
				`addr: "localhost:18080"`:      fmt.Sprintf(`addr: "%s"`, e.Addr),
				"edge-node: edge1":             "edge-node: " + e.Node,
				"broker: tcp://localhost:1883": "broker: " + opt.Broker,
				"primary-host: joy-dev":        "primary-host: " + opt.PrimaryHost,
				"bdseq-file: .run/edge1.bdseq": "bdseq-file: bdseq",
			}))
		}
		if err := os.WriteFile(filepath.Join(e.Dir, entry.Name()), raw, 0o644); err != nil {
			return err
		}
	}
	return nil
}

var groupLine = regexp.MustCompile(`(?m)^\s*group-id:\s*(\S+)`)

// rewrite replaces manifest lines, and refuses to continue when one is not
// there: a manifest edit that silently stopped matching would leave every
// test publishing as the same node.
func rewrite(manifest string, replacements map[string]string) string {
	for old, repl := range replacements {
		if !strings.Contains(manifest, old) {
			panic("edge/nautilus.yaml no longer contains " + old + "; update harness/edge.go")
		}
		manifest = strings.Replace(manifest, old, repl, 1)
	}
	return manifest
}

func (e *Edge) start() error {
	bin := env("NAUTILUS_BIN", "nautilus")
	e.cmd = exec.Command(bin, "run")
	e.cmd.Dir = e.Dir
	e.cmd.Stdout = e.log
	e.cmd.Stderr = e.log
	if err := e.cmd.Start(); err != nil {
		return fmt.Errorf("start %s: %w (set NAUTILUS_BIN?)", bin, err)
	}
	e.done = make(chan struct{})
	go func() { _ = e.cmd.Wait(); close(e.done) }()

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-e.done:
			return fmt.Errorf("nautilus exited during startup:\n%s", e.Log())
		default:
		}
		if _, err := e.State(); err == nil {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	e.Kill()
	return fmt.Errorf("nautilus tag API never came up on %s:\n%s", e.Addr, e.Log())
}

// Restart brings the same node (same directory, so the same bdSeq file and
// identity) back after a Stop or Kill.
func (e *Edge) Restart() error { return e.start() }

// Stop asks Nautilus to shut down, which publishes an NDEATH before it
// disconnects. For the unannounced kind, use Kill.
func (e *Edge) Stop() {
	if e.cmd == nil || e.cmd.Process == nil {
		return
	}
	_ = e.cmd.Process.Signal(syscall.SIGCONT) // a frozen process can't hear the SIGINT
	_ = e.cmd.Process.Signal(syscall.SIGINT)
	select {
	case <-e.done:
	case <-time.After(10 * time.Second):
		_ = e.cmd.Process.Kill()
		<-e.done
	}
}

// Kill takes the process away with no goodbye. The host learns of it only
// when the broker gives up on the keepalive and publishes the node's will.
func (e *Edge) Kill() {
	if e.cmd == nil || e.cmd.Process == nil {
		return
	}
	_ = e.cmd.Process.Kill()
	<-e.done
}

// Freeze stops the process without closing its socket (SIGSTOP), which is what a hung controller or a dead
// radio link looks like from the broker: no goodbye, no FIN, just silence until the keepalive runs out.
func (e *Edge) Freeze() error { return e.cmd.Process.Signal(syscall.SIGSTOP) }

// Thaw lets a frozen process carry on, unaware that it was declared dead.
func (e *Edge) Thaw() error { return e.cmd.Process.Signal(syscall.SIGCONT) }

// Log is everything Nautilus has printed; tests attach it to a failure.
func (e *Edge) Log() string { return e.log.String() }

// TagPath is where a Sparkplug host that mirrors the wire (group/node/metric) puts one of this node's metrics,
// provider-relative.
func (e *Edge) TagPath(metric string) string {
	return e.Group + "/" + e.Node + "/" + metric
}

// State is the controller's tag snapshot.
func (e *Edge) State() (map[string]any, error) {
	resp, err := e.client.Get("http://" + e.Addr + "/api/state")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var out struct {
		Tags map[string]any `json:"tags"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	return out.Tags, nil
}

// Get reads a tag, or a struct member by dotted path ("P101.Speed").
func (e *Edge) Get(name string) (any, error) {
	tags, err := e.State()
	if err != nil {
		return nil, err
	}
	var cur any = map[string]any(tags)
	for _, part := range strings.Split(name, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("edge tag %s: %s is not a struct", name, part)
		}
		if cur, ok = m[part]; !ok {
			return nil, fmt.Errorf("edge tag %s: no %s", name, part)
		}
	}
	return cur, nil
}

// Set writes a tag or a struct member through the controller's tag API, the
// way its own HMI would.
func (e *Edge) Set(name string, value any) error {
	body, _ := json.Marshal(map[string]any{"name": name, "value": value})
	resp, err := e.client.Post("http://"+e.Addr+"/api/tags", "application/json", bytes.NewReader(body))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("edge set %s: HTTP %d: %s", name, resp.StatusCode, firstLine(msg))
	}
	return nil
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}
