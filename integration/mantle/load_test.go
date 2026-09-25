// Throughput. Every other test here checks that Mantle is correct; this one checks it keeps up.
//
// The measurement is deliberately taken from tags rather than from the module's own counters. A counter can
// say "1192 messages" while the tag tree quietly falls behind; what matters to somebody watching a screen is
// that values arrive, so the assertion is that a metric the edge increments once per publish advances in
// Ignition at the rate the edge is publishing it.
package mantle

import (
	"fmt"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestMantleKeepsUpWithAFastEdge(t *testing.T) {
	if testing.Short() {
		t.Skip("starts a Gradle JVM and runs for ~40s; -short skips it")
	}
	const (
		rateHz  = 20 // publishes per second
		metrics = 50 // extra analog metrics in each publish, on top of the usual three
		window  = 10 // seconds over which the rate is measured
		runFor  = "40"
	)

	edge := fmt.Sprintf("LoadEdge%d", time.Now().UnixMilli())
	counter := "LoadPlant/" + edge + "/PLC1/Counter"

	sim := exec.Command("./gradlew", "-q", "--console=plain", ":gateway:simulate",
		"--args=tcp://localhost:1883 LoadPlant "+edge+" "+runFor)
	sim.Dir = "../../mantle"
	sim.Env = append(os.Environ(),
		fmt.Sprintf("SIM_RATE_HZ=%d", rateHz),
		fmt.Sprintf("SIM_EXTRA_METRICS=%d", metrics))
	if err := sim.Start(); err != nil {
		t.Fatalf("could not start the fast edge: %v", err)
	}
	t.Cleanup(func() {
		_ = sim.Process.Kill()
		_ = sim.Wait()
		_ = gw.Delete("LoadPlant/" + edge)
	})

	// Wait for the node to be producing. A tag that does not exist yet reads back Good with a nil value, so
	// wait for a number.
	var start float64
	eventually(t, 90*time.Second, func() error {
		v, err := gw.Read(counter)
		if err != nil {
			return err
		}
		n, ok := v[0].Float()
		if !ok || !v[0].Good {
			return fmt.Errorf("counter is %v (%s)", v[0].Value, v[0].Quality)
		}
		start = n
		return nil
	})

	time.Sleep(window * time.Second)

	v, err := gw.Read(counter)
	must(t, err)
	end, ok := v[0].Float()
	if !ok {
		t.Fatalf("counter stopped being a number: %v", v[0].Value)
	}

	observed := (end - start) / float64(window)
	t.Logf("edge published %d metrics at %d Hz; Ignition saw %.1f updates/sec (%.0f values/sec)",
		metrics+3, rateHz, observed, observed*float64(metrics+3))

	// Generous: the point is to catch a collapse — a host that reorders itself into a stall, or drops to a
	// trickle under load — not to police a few percent of jitter on a laptop running six brokers.
	if observed < rateHz/2 {
		t.Errorf("Ignition saw %.1f updates/sec from an edge publishing at %d Hz: Mantle is not keeping up",
			observed, rateHz)
	}
	if !v[0].Good {
		t.Errorf("quality degraded to %s under load", v[0].Quality)
	}
}
