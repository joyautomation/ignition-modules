#!/usr/bin/env bash
# Presses the dev gateway's "reset trial" button, over its own REST API, so a demo can be left running.
#
#   scripts/keep-trial-alive.sh            # reset now
#   scripts/keep-trial-alive.sh --status   # just report how long is left
#
# Needs an API token, because the reset route requires an authenticated identity (the GET that reports the
# time left does not). Make one once, in the gateway: Platform -> Security -> API Keys -> Create API Key,
# with write permission. The key is shown only at creation. Then either:
#
#   export IGNITION_API_TOKEN=...                       # for one shell
#   echo 'IGNITION_API_TOKEN=...' > .env.trial          # for cron; gitignored
#
# To run it every 100 minutes, inside the two-hour window:
#
#   crontab -e
#   */100 * * * * cd /path/to/ignition && scripts/keep-trial-alive.sh >> /tmp/ignition-trial.log 2>&1
#
# This automates Inductive Automation's own reset button on a development gateway, which is what the button is
# there for. It is not a way to run anything real: for a demo that has to stay up unattended, or anything a
# customer sees, get a licence. Maker Edition is free for non-commercial use, though a third-party module has
# to declare isMakerEditionCompatible() before Maker will load it, and Mantle does not yet.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
gateway="${IGNITION_GATEWAY:-http://localhost:8088}"
[ -f .env.trial ] && . ./.env.trial

left() {
    curl -s -m 10 "$gateway/data/api/v1/trial" \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("trialSecondsLeft", -1))' 2>/dev/null \
        || echo -1
}

pretty() { printf '%dh %02dm' $(( $1 / 3600 )) $(( ($1 % 3600) / 60 )); }

before="$(left)"
if [ "$before" -lt 0 ]; then
    echo "$(date -Is) the gateway at $gateway is not answering"
    exit 1
fi

if [ "${1:-}" = "--status" ]; then
    echo "$(date -Is) trial has $(pretty "$before") left"
    exit 0
fi

if [ -z "${IGNITION_API_TOKEN:-}" ]; then
    echo "$(date -Is) trial has $(pretty "$before") left, but IGNITION_API_TOKEN is not set — see the header of" \
        "this script for how to make one" >&2
    exit 2
fi

code="$(curl -s -m 15 -o /dev/null -w '%{http_code}' -X POST \
    -H "X-Ignition-API-Token: $IGNITION_API_TOKEN" "$gateway/data/api/v1/trial")"
after="$(left)"

if [ "$code" != "200" ] || [ "$after" -le "$before" ]; then
    echo "$(date -Is) reset FAILED (HTTP $code); $(pretty "$after") left" >&2
    exit 1
fi
echo "$(date -Is) trial reset: $(pretty "$before") -> $(pretty "$after")"
