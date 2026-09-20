#!/usr/bin/env bash
# A demo you can look at: a few Nautilus edge nodes publishing Sparkplug B into the dev gateway, so the
# Mantle status page and the tag tree have something real in them.
#
#   scripts/demo.sh           # start it (brings the stack up if it isn't)
#   scripts/demo.sh --stop    # stop the edge nodes, leave the gateway up
#   scripts/demo.sh --fresh   # throw the gateway's data away and start over
#
# The nodes keep running until stopped. Only the processes this script started are stopped, by recorded PID —
# never by name, because other nautilus processes on this machine are not ours.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
# shellcheck source=scripts/lib.sh
source scripts/lib.sh

run_dir="integration/.run/demo"
pid_file="$run_dir/pids"

# group:node:api-port — one running controller each. Two groups, so the page shows more than one of everything.
sites=(
    "Plant:Line1:18101"
    "Plant:Line2:18102"
    "Remote:PumpStation:18103"
)

# Stops what this script started, and nothing else. Recorded PIDs first, then a sweep for any controller whose
# working directory is inside our own demo directory — which catches one orphaned by an earlier failure while
# being incapable of touching another nautilus on this machine, because it matches on path, never on name.
stop_demo() {
    local stopped=0 pid cwd
    if [ -f "$pid_file" ]; then
        while read -r pid; do
            if [ -n "$pid" ] && kill "$pid" 2>/dev/null; then
                stopped=$((stopped + 1))
            fi
        done < "$pid_file"
        rm -f "$pid_file"
    fi
    for pid in $(pgrep -x nautilus 2>/dev/null); do
        cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null || true)"
        case "$cwd" in
            "$root/$run_dir"/*)
                kill "$pid" 2>/dev/null && stopped=$((stopped + 1))
                ;;
        esac
    done
    [ "$stopped" -gt 0 ] && echo "stopped $stopped demo edge node(s)" || echo "no demo edge nodes were running"
    return 0
}

case "${1:-}" in
    --stop) stop_demo; exit 0 ;;
    --fresh) stop_demo; scripts/dev-up.sh --fresh ;;
    "") ;;
    *) echo "usage: demo.sh [--stop|--fresh]" >&2; exit 2 ;;
esac

# The gateway's trial stops WebDev and the historian after two hours, which makes a demo look broken for a
# reason that has nothing to do with the module. Start over rather than show that.
if ! curl -s -m 5 http://localhost:8088/StatusPing | grep -q RUNNING \
    || docker compose logs --since 3h gateway 2>/dev/null | grep -qi 'trial expired'; then
    echo "the gateway is down or its trial has run out — rebuilding the stack"
    scripts/dev-up.sh --fresh
fi

nautilus="$(build_nautilus)"
stop_demo
mkdir -p "$run_dir"
: > "$pid_file"

for site in "${sites[@]}"; do
    IFS=: read -r group node port <<< "$site"
    dir="$run_dir/$node"
    rm -rf "$dir"; mkdir -p "$dir"
    cp integration/mantle/edge/*.st "$dir/"
    sed -e "s|addr: \"localhost:[0-9]*\"|addr: \"localhost:$port\"|" \
        -e "s|group-id: .*|group-id: $group|" \
        -e "s|edge-node: .*|edge-node: $node|" \
        -e "s|bdseq-file: .*|bdseq-file: bdseq|" \
        integration/mantle/edge/nautilus.yaml > "$dir/nautilus.yaml"
    # nohup rather than a subshell: it execs in place, so $! is the controller's own PID and --stop can
    # actually stop it. Every descriptor is redirected, so nothing holds this script's output open.
    cd "$dir"
    nohup "$nautilus" run < /dev/null > run.log 2>&1 &
    echo $! >> "$root/$pid_file"
    cd "$root"
    echo "  $group/$node   tag API on localhost:$port"
done

# Give them something to do, so the page has motion: a counter running, a motor turning.
sleep 6
for site in "${sites[@]}"; do
    IFS=: read -r group node port <<< "$site"
    curl -s -m 5 -X POST "localhost:$port/api/tags" -d '{"name":"CountEnable","value":true}' >/dev/null || true
    curl -s -m 5 -X POST "localhost:$port/api/tags" -d '{"name":"P101_Enable","value":true}' >/dev/null || true
    curl -s -m 5 -X POST "localhost:$port/api/tags" -d '{"name":"P101.SpeedSP","value":1450}' >/dev/null || true
done

online=$(curl -s -m 10 -u admin:password -X POST -H 'Content-Type: application/json' \
    -d '{"op":"browse","path":"[Sparkplug]"}' \
    http://localhost:8088/system/webdev/integration-api/api 2>/dev/null | grep -o '"name"' | wc -l)

cat <<EOF

The demo is running: ${#sites[@]} edge nodes publishing into the gateway ($online top-level tag folders so far).

  Gateway    http://localhost:8088          (admin / password)
  Status     Diagnostics -> Mantle -> Sparkplug
  Tags       the [Sparkplug] provider, in the Designer

Things worth doing on the status page:
  - watch Messages climb, and Sequence gaps and Decode failures stay at 0
  - stop one node and watch it go offline, its tags going stale but keeping their last values:
      kill \$(sed -n 1p $pid_file)
  - press Request rebirth on a node and watch its Last birth reset
  - in the Designer, look at [Sparkplug]_types_/Motor — the UDT built from the edge's template definition —
    and at Plant/Line1/P101, an instance of it

Move a value by hand:
  curl -X POST localhost:18101/api/tags -d '{"name":"LevelSP","value":42}'

  scripts/demo.sh --stop     when you are done

The gateway's trial runs out in two hours and takes WebDev and the historian with it. To keep a demo up,
make an API key once (Platform -> Security -> API Keys) and run scripts/keep-trial-alive.sh from cron —
its header has the details. scripts/keep-trial-alive.sh --status needs no key.
EOF
