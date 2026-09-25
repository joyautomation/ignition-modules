// An edge whose clock is behind the gateway's.
//
// This is the quietest data-loss failure Mantle has. The tag provider allows backfill, so Ignition routes
// any value older than a tag's current one into history and leaves the live value untouched. An edge an hour
// behind would therefore publish perfectly good data, the wire would look healthy, the historian would fill
// up — and the screen would never move. ManagedTagSink.liveTime exists to stop that, and until now nothing
// had ever pointed a genuinely skewed clock at it.
package mantle

import (
	"fmt"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestAnEdgeWithABackwardClockStillUpdatesLiveValues(t *testing.T) {
	if testing.Short() {
		t.Skip("starts a Gradle JVM; -short skips it")
	}
	const skewSeconds = "-3600" // an hour behind, far past any plausible network delay

	// A name unique to this run. The first version of this test reused a fixed one, found a Good value left
	// over from an earlier run, and passed in 1.8 seconds without the skewed edge having published anything
	// at all. A fresh node per run makes that impossible rather than unlikely.
	edge := fmt.Sprintf("SkewEdge%d", time.Now().UnixMilli())
	tag := "SkewPlant/" + edge + "/PLC1/Tank/Level" // relative: gw.Read qualifies it with the provider

	// The Java EdgeSimulator rather than the Nautilus edge: Nautilus has a correct clock, and the point is
	// to misbehave deliberately.
	sim := exec.Command("./gradlew", "-q", "--console=plain", ":gateway:simulate",
		"--args=tcp://localhost:1883 SkewPlant "+edge+" 70")
	sim.Dir = "../../mantle"
	sim.Env = append(os.Environ(), "SIM_CLOCK_SKEW_SECONDS="+skewSeconds)
	if err := sim.Start(); err != nil {
		t.Fatalf("could not start the skewed edge: %v", err)
	}
	t.Cleanup(func() {
		_ = sim.Process.Kill()
		_ = sim.Wait()
		_ = gw.Delete("SkewPlant/" + edge)
	})

	// Gradle takes a while to get a JVM up before the edge even connects. Nothing under this node existed
	// before this moment, so any Good value here was published by the skewed edge.
	var first struct {
		value     any
		timestamp int64
	}
	eventually(t, 90*time.Second, func() error {
		v, err := gw.Read(tag)
		if err != nil {
			return err
		}
		if !v[0].Good {
			return fmt.Errorf("quality is %s", v[0].Quality)
		}
		// A tag that does not exist yet reads back Good with a nil value and the current time — which is
		// how the first version of this test passed in under two seconds without the edge having published
		// anything, and passed again with liveTime deliberately broken. Insist on a real number.
		if _, ok := v[0].Float(); !ok {
			return fmt.Errorf("value is %v, not a number yet", v[0].Value)
		}
		first.value, first.timestamp = v[0].Value, v[0].Timestamp
		return nil
	})

	// The value is stamped with the GATEWAY's clock, not the edge's. Without liveTime it would carry the
	// edge's hour-old timestamp, Ignition would treat it as backfill, and the live value would never move.
	t.Logf("first Good value: %v stamped %s (%s ago)", first.value,
		time.UnixMilli(first.timestamp).Format("15:04:05"),
		time.Since(time.UnixMilli(first.timestamp)).Round(time.Second))
	age := time.Since(time.UnixMilli(first.timestamp))
	if age > 5*time.Minute {
		t.Fatalf("live value is stamped %s in the past — the edge's clock is reaching the live value, so "+
			"backfill will swallow it (liveTime is not doing its job)", age.Round(time.Second))
	}

	// And it keeps moving. One good value could be the first one ever written; this proves the second is
	// not being discarded as older than the first.
	eventually(t, 30*time.Second, func() error {
		v, err := gw.Read(tag)
		if err != nil {
			return err
		}
		if v[0].Timestamp <= first.timestamp {
			return fmt.Errorf("timestamp has not advanced past %d", first.timestamp)
		}
		if !v[0].Good {
			return fmt.Errorf("quality fell back to %s", v[0].Quality)
		}
		if _, ok := v[0].Float(); !ok {
			return fmt.Errorf("value went back to %v", v[0].Value)
		}
		return nil
	})
}
