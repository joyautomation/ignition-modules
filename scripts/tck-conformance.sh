#!/usr/bin/env bash
# Grades Mantle against the Sparkplug B specification.
#
#   scripts/tck-conformance.sh [duration]
#
# This is the conformance gate, not a smoke test: sparkplug-tck-go runs an in-process MQTT broker, Mantle
# connects to it as a host application, and every normative assertion in the host-application profile is
# graded from the packets that actually crossed the wire. It exits non-zero if any assertion failed.
#
# sparkplug-tck-go is a Go reimplementation of the Eclipse TCK that tracks the upstream spec automatically,
# so this stays honest as the specification moves. It is expected beside this repo, or at $TCK_SRC.
#
# The harness broker runs on the host and the gateway reaches it as host.docker.internal (docker-compose.yml
# gives the gateway that route). A temporary Mantle connection is created against it and removed afterwards,
# so a run leaves the dev stack as it found it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$here"
# shellcheck source=scripts/lib.sh
source scripts/lib.sh

# Long enough to cover a gateway restart (~50 s) and still leave a real capture window.
duration="${1:-110s}"
tck="${TCK_SRC:-$(cd .. && pwd)/sparkplug-tck-go}"
port="${TCK_PORT:-1885}"
connection=tck-conformance
gateway="${GATEWAY_URL:-http://localhost:8088}"
results="$here/build/tck"

if [ ! -d "$tck/cmd/sparkplug-tck" ]; then
    echo "sparkplug-tck-go not found at $tck" >&2
    echo "Clone it beside this repo, or set TCK_SRC." >&2
    exit 1
fi

mkdir -p "$results"

# Built rather than `go run`: go run spawns the real binary as a child, so killing the go process on the way
# out leaves the harness holding the port — which is exactly what happened the first time this was written,
# and the next run failed with "address already in use".
harness_bin="$results/sparkplug-tck"
echo "building the TCK..."
(cd "$tck" && go build -o "$harness_bin" ./cmd/sparkplug-tck)

cleanup() {
    if [ -n "${poke_pid:-}" ]; then
        kill "$poke_pid" 2>/dev/null || true
        wait "$poke_pid" 2>/dev/null || true
    fi
    if [ -n "${edge_pid:-}" ]; then
        kill "$edge_pid" 2>/dev/null || true
        wait "$edge_pid" 2>/dev/null || true
    fi
    if [ -n "${harness_pid:-}" ]; then
        kill "$harness_pid" 2>/dev/null || true
        wait "$harness_pid" 2>/dev/null || true
    fi
    # Leave the dev stack as it was found: a connection pointing at a harness that is no longer listening
    # would fail every later run of TestEveryConnectionIsHealthy. The gateway may be stopped at this point
    # (see stop_the_host_near_the_end), and unseeding needs it running.
    compose up -d gateway >/dev/null 2>&1 || true
    wait_for_gateway >/dev/null 2>&1 || true
    unseed_connection "$connection"
    compose restart gateway >/dev/null 2>&1 || true
    wait_for_gateway >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "starting the TCK host-application harness on port $port for $duration..."
"$harness_bin" -harness -profile host-application -listen "0.0.0.0:$port" -duration "$duration" -json \
    > "$results/results.json" 2> "$results/harness.log" &
harness_pid=$!

# The harness has to be accepting connections before Mantle is told to connect, or the first attempt fails
# and the reconnect backoff eats most of the capture window.
for _ in $(seq 1 40); do
    if (exec 3<>/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then exec 3>&- 3<&-; break; fi
    sleep 0.5
done

# The connection is a file and config resources are read at startup, so Mantle reaches the harness on the way
# back up. That is also why the harness window has to be long enough to contain a whole gateway restart.
echo "pointing Mantle at it, and restarting the gateway..."
seed_connection "$connection" "tcp://host.docker.internal:$port" tck-host SparkplugTCK
compose restart gateway >/dev/null 2>&1
wait_for_gateway

# An edge node on the same broker, so the host has something to react to. Without it about half the profile
# grades N/A for want of any NBIRTH to answer, a rebirth to request, or a command to send — a conformance
# claim that rests on assertions nobody exercised is not worth much.
echo "publishing from an edge node so the host has something to answer..."
(cd mantle && SIM_CONFORMANCE=1 ./gradlew -q --console=plain :gateway:simulate \
    --args="tcp://localhost:$port TCKGroup TCKEdge 60" >"$results/edge.log" 2>&1) &
edge_pid=$!

# Make the host actually do the things the profile grades. Without this, 26 of the profile's assertions sit
# at "no NCMD observed" / "no DCMD observed" — not because Mantle cannot send them, but because nothing asked
# it to. An untested assertion is not a passed one, and a conformance number built out of them is worth very
# little.
#
# Both writes go through the integration-api WebDev endpoint, which takes a plain gateway login, so this
# works in CI where an API token cannot exist.
poke() {
    local op="$1" body="$2"
    curl -sf -m 30 -u admin:password -H 'Content-Type: application/json' -d "$body" \
        "$gateway/system/webdev/integration-api/api" 2>/dev/null
}

exercise_commands() {
    local base="[SparkplugTCK]TCKGroup/TCKEdge"
    # Wait for the node to have birthed and its tags to exist, or the writes go nowhere.
    for _ in $(seq 1 30); do
        if poke browse "{\"op\":\"browse\",\"path\":\"$base\"}" | grep -q 'Node Control'; then break; fi
        sleep 2
    done

    # A write to a node-level tag makes Mantle publish NCMD; a write to a device metric makes it publish
    # DCMD. One of each is enough — the assertions are about the shape of the message, not how many.
    echo "  writing to a node tag (expect NCMD)..."
    poke write "{\"op\":\"write\",\"paths\":[\"$base/Node Control/Rebirth\"],\"values\":[true]}" >/dev/null
    sleep 3
    echo "  writing to a device metric (expect DCMD)..."
    poke write "{\"op\":\"write\",\"paths\":[\"$base/PLC1/Tank/Setpoint\"],\"values\":[42.0]}" >/dev/null
}

# Three assertions about the host disconnecting (its STATE death certificate, and whether the disconnect was
# intentional) are deliberately NOT staged here. Stopping the gateway inside the capture window was tried:
# Ignition's graceful shutdown takes longer than the window has left, so the DISCONNECT lands after the
# harness has stopped listening, and pulling the stop earlier costs the 26 NCMD/DCMD assertions that need a
# live host. Those three are covered by the integration suite instead — see the orderly-shutdown and
# gateway-outage tests in integration/mantle.
exercise_commands &
poke_pid=$!

echo "capturing..."
wait "$harness_pid" && status=0 || status=$?
kill "$poke_pid" 2>/dev/null || true
wait "$poke_pid" 2>/dev/null || true
harness_pid=
kill "$edge_pid" 2>/dev/null || true
wait "$edge_pid" 2>/dev/null || true

python3 - "$results/results.json" <<'PY' || status=1
import json, sys
try:
    with open(sys.argv[1]) as f:
        results = json.load(f)
except Exception as e:                                   # the harness died before writing anything
    print("no TCK results: %s" % e)
    raise SystemExit(1)

items = results if isinstance(results, list) else results.get("results", results.get("assertions", []))
counts = {}
failures = []
for item in items:
    verdict = str(item.get("verdict") or item.get("status") or "").upper()
    counts[verdict] = counts.get(verdict, 0) + 1
    if verdict in ("FAIL", "FAILED"):
        failures.append("%s — %s" % (item.get("id", "?"), item.get("message", "")))

print(" ".join("%s=%d" % kv for kv in sorted(counts.items())) or "no assertions graded")
for failure in failures:
    print("  FAIL %s" % failure)

passed = counts.get("PASS", 0) + counts.get("PASSED", 0)
if failures:
    raise SystemExit(1)
if passed == 0:
    # Everything n/a means Mantle never reached the harness: a green run that proves nothing is the one
    # outcome worse than a red one.
    print("no assertion was exercised — Mantle did not connect to the harness. See build/tck/harness.log")
    raise SystemExit(1)
print("%d assertions passed, none failed" % passed)
PY

exit "$status"
