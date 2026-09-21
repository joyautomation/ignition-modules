// Package harness is what every module's integration tests share: a client for the Ignition gateway (this
// file) and a Nautilus controller to stand in for the plant (edge.go).
package harness

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// Gateway talks to the test-support WebDev endpoint in
// gateway-project/integration-api. That endpoint is a thin skin over system.tag.*,
// so a test sees the gateway the way a project script or the Designer does.
type Gateway struct {
	URL      string // http://localhost:8088
	User     string
	Password string
	Provider string // the tag provider paths are relative to
	HostID   string // the gateway's Sparkplug host id, for an edge that names it as primary-host
	client   *http.Client
}

// GatewayFromEnv reads IGNITION_IT_* and falls back to the dev stack's values
// (docker-compose.yml and dev/config).
func GatewayFromEnv() *Gateway {
	return &Gateway{
		URL:      env("IGNITION_IT_GATEWAY", "http://localhost:8088"),
		User:     env("IGNITION_IT_USER", "admin"),
		Password: env("IGNITION_IT_PASSWORD", "password"),
		Provider: env("IGNITION_IT_PROVIDER", "Sparkplug"),
		HostID:   env("IGNITION_IT_HOST_ID", "joy-dev"),
		client:   &http.Client{Timeout: 30 * time.Second},
	}
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// Value is one tag read.
type Value struct {
	Value     any    `json:"value"`
	Quality   string `json:"quality"`
	Good      bool   `json:"good"`
	Timestamp int64  `json:"timestamp"` // ms since epoch
}

// Float reads a numeric value; ok is false for null or non-numeric.
func (v Value) Float() (float64, bool) {
	f, ok := v.Value.(float64)
	return f, ok
}

// HistoryRow is one stored sample.
type HistoryRow struct {
	Path      string `json:"path"`
	Value     any    `json:"value"`
	Quality   string `json:"quality"`
	Timestamp int64  `json:"timestamp"`
}

// Path qualifies a provider-relative path: "MantleIT/edge1/LevelFt" ->
// "[Sparkplug]MantleIT/edge1/LevelFt".
func (g *Gateway) Path(rel string) string {
	return "[" + g.Provider + "]" + rel
}

func (g *Gateway) call(req map[string]any, out any) error {
	body, _ := json.Marshal(req)
	r, err := http.NewRequest(http.MethodPost, g.URL+"/system/webdev/integration-api/api", bytes.NewReader(body))
	if err != nil {
		return err
	}
	r.SetBasicAuth(g.User, g.Password)
	r.Header.Set("Content-Type", "application/json")
	resp, err := g.client.Do(r)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("gateway %s: HTTP %d: %s", req["op"], resp.StatusCode, firstLine(raw))
	}
	var probe struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(raw, &probe) == nil && probe.Error != "" {
		return fmt.Errorf("gateway %s: %s", req["op"], probe.Error)
	}
	return json.Unmarshal(raw, out)
}

func firstLine(b []byte) string {
	s := strings.TrimSpace(string(b))
	if i := strings.IndexByte(s, '\n'); i > 0 {
		s = s[:i]
	}
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}

// Ping fails when the gateway, the integration-api project, or the WebDev module
// (trial expired?) is not answering.
func (g *Gateway) Ping() error {
	var out struct {
		OK bool `json:"ok"`
	}
	if err := g.call(map[string]any{"op": "ping"}, &out); err != nil {
		return err
	}
	if !out.OK {
		return fmt.Errorf("gateway ping: not ok")
	}
	return nil
}

// Read reads provider-relative paths.
func (g *Gateway) Read(rel ...string) ([]Value, error) {
	paths := make([]string, len(rel))
	for i, p := range rel {
		paths[i] = g.Path(p)
	}
	var out struct {
		Values []Value `json:"values"`
	}
	err := g.call(map[string]any{"op": "read", "paths": paths}, &out)
	if err == nil && len(out.Values) != len(rel) {
		err = fmt.Errorf("gateway read: asked for %d values, got %d", len(rel), len(out.Values))
	}
	return out.Values, err
}

// Write writes one tag the way a script or an operator would, and returns
// the quality code the tag system reported for the write.
func (g *Gateway) Write(rel string, value any) (string, error) {
	var out struct {
		Results []string `json:"results"`
	}
	err := g.call(map[string]any{"op": "write", "paths": []string{g.Path(rel)}, "values": []any{value}}, &out)
	if err != nil {
		return "", err
	}
	if len(out.Results) != 1 {
		return "", fmt.Errorf("gateway write: %d results", len(out.Results))
	}
	return out.Results[0], nil
}

// Config returns a tag's effective configuration (module layer merged with
// the user's).
func (g *Gateway) Config(rel string) (map[string]any, error) {
	var out struct {
		Configs []map[string]any `json:"configs"`
	}
	if err := g.call(map[string]any{"op": "config", "path": g.Path(rel)}, &out); err != nil {
		return nil, err
	}
	if len(out.Configs) != 1 {
		return nil, fmt.Errorf("gateway config %s: %d results", rel, len(out.Configs))
	}
	return out.Configs[0], nil
}

// ConfigTree is Config including a tag's children, for a UDT type or a folder.
func (g *Gateway) ConfigTree(rel string) (map[string]any, error) {
	var out struct {
		Configs []map[string]any `json:"configs"`
	}
	if err := g.call(map[string]any{"op": "config", "path": g.Path(rel), "recursive": true}, &out); err != nil {
		return nil, err
	}
	if len(out.Configs) != 1 {
		return nil, fmt.Errorf("gateway config %s: %d results", rel, len(out.Configs))
	}
	return out.Configs[0], nil
}

// Configure merges properties into existing tags under a folder, through
// system.tag.configure: the same route a person's edit takes, so what it
// sets lands in the tag's user layer.
func (g *Gateway) Configure(relFolder string, tags ...map[string]any) error {
	var out struct {
		Results []string `json:"results"`
		Good    []bool   `json:"good"`
	}
	err := g.call(map[string]any{"op": "configure", "basePath": g.Path(relFolder), "tags": tags, "collisionPolicy": "m"}, &out)
	if err != nil {
		return err
	}
	for i, ok := range out.Good {
		if !ok {
			return fmt.Errorf("gateway configure %s: %s", relFolder, out.Results[i])
		}
	}
	return nil
}

// Browse lists the names directly under a folder.
func (g *Gateway) Browse(relFolder string) ([]string, error) {
	var out struct {
		Results []struct {
			Name string `json:"name"`
		} `json:"results"`
	}
	if err := g.call(map[string]any{"op": "browse", "path": g.Path(relFolder)}, &out); err != nil {
		return nil, err
	}
	names := make([]string, len(out.Results))
	for i, r := range out.Results {
		names[i] = r.Name
	}
	return names, nil
}

// Delete removes tags or folders. Tests use it to clean up their own node.
func (g *Gateway) Delete(rel ...string) error {
	paths := make([]string, len(rel))
	for i, p := range rel {
		paths[i] = g.Path(p)
	}
	var out struct {
		Results []string `json:"results"`
	}
	return g.call(map[string]any{"op": "delete", "paths": paths}, &out)
}

// Module is a module descriptor as the gateway parsed it from module.xml.
type Module struct {
	ID                string `json:"id"`
	Name              string `json:"name"`
	Version           string `json:"version"`
	VendorName        string `json:"vendorName"`
	VendorContactInfo string `json:"vendorContactInfo"`
}

// Module returns one loaded module's descriptor by id.
func (g *Gateway) Module(id string) (Module, error) {
	var out struct {
		Modules []Module `json:"modules"`
	}
	if err := g.call(map[string]any{"op": "modules"}, &out); err != nil {
		return Module{}, err
	}
	for _, m := range out.Modules {
		if m.ID == id {
			return m, nil
		}
	}
	return Module{}, fmt.Errorf("module %s is not loaded", id)
}

// Connection is one Mantle broker connection and the health the gateway reports for it — the same result
// behind the Status column on the module's configuration page.
type Connection struct {
	Name    string `json:"name"`
	Healthy bool   `json:"healthy"`
	Message string `json:"message"`
}

// Connections lists every Mantle connection the gateway knows about, with its health.
func (g *Gateway) Connections() ([]Connection, error) {
	var out struct {
		Connections []Connection `json:"connections"`
	}
	if err := g.call(map[string]any{"op": "connections"}, &out); err != nil {
		return nil, err
	}
	return out.Connections, nil
}

// NavPage is one page in the gateway's own navigation menu.
type NavPage struct {
	Section    string
	Category   string
	Label      string
	URL        string
	Permission string
}

// Nav reads the gateway's navigation model from inside the gateway. A module's page being registered is
// otherwise only visible to a logged-in browser, which a test cannot be.
func (g *Gateway) Nav() ([]NavPage, error) {
	var out struct {
		Sections []struct {
			Label      string `json:"label"`
			Categories []struct {
				Label string `json:"label"`
				Pages []struct {
					Label      string `json:"label"`
					URL        string `json:"url"`
					Permission string `json:"permission"`
				} `json:"pages"`
			} `json:"categories"`
		} `json:"sections"`
	}
	if err := g.call(map[string]any{"op": "nav"}, &out); err != nil {
		return nil, err
	}
	var pages []NavPage
	for _, s := range out.Sections {
		for _, c := range s.Categories {
			for _, p := range c.Pages {
				pages = append(pages, NavPage{s.Label, c.Label, p.Label, p.URL, p.Permission})
			}
		}
	}
	return pages, nil
}

// History returns the raw stored samples for one tag in [start, end].
func (g *Gateway) History(rel string, start, end time.Time) ([]HistoryRow, error) {
	var out struct {
		Rows []HistoryRow `json:"rows"`
	}
	err := g.call(map[string]any{
		"op": "history", "paths": []string{g.Path(rel)},
		"start": start.UnixMilli(), "end": end.UnixMilli(),
	}, &out)
	return out.Rows, err
}
