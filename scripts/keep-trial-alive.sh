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
# To run it from cron — and then CHECK IT FIRED, because an entry nobody installed looks exactly like one that
# is working until the gateway expires:
#
#   scripts/keep-trial-alive.sh --install-cron
#   crontab -l                      # the entry is there
#   tail -f /tmp/ignition-trial.log # it logs every five minutes, even when there is nothing to do
#
# Every five minutes, because the gateway REFUSES to reset a trial that still has time on it (the route answers
# 403 unless getDemoTimeRemaining() is 0). So this is a no-op almost every run, and resets promptly on the one
# run after the trial lapses — which keeps the outage to minutes rather than however long until the next hourly
# tick. There is no way to reset early and stay ahead of it.
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

if [ "${1:-}" = "--install-cron" ]; then
    line="*/5 * * * * cd $root && scripts/keep-trial-alive.sh >> /tmp/ignition-trial.log 2>&1"
    if crontab -l 2>/dev/null | grep -qF "keep-trial-alive.sh"; then
        echo "already in the crontab:"
        crontab -l | grep -F "keep-trial-alive.sh"
    else
        { crontab -l 2>/dev/null
          echo "# Mantle dev gateway: reset Ignition's trial as soon as it lapses (a no-op while time remains)"
          echo "$line"; } | crontab -
        echo "installed: $line"
    fi
    command -v systemctl >/dev/null && ! systemctl is-active --quiet cron 2>/dev/null \
        && ! systemctl is-active --quiet crond 2>/dev/null \
        && echo "warning: no cron daemon appears to be running, so it will never fire" >&2
    echo "it logs to /tmp/ignition-trial.log every five minutes; check there in a few minutes"
    exit 0
fi

if [ "${1:-}" = "--status" ]; then
    echo "$(date -Is) trial has $(pretty "$before") left"
    exit 0
fi

# The gateway only allows a reset once the trial has actually run out, so having time left is the normal case
# and not a problem to report.
if [ "$before" -gt 0 ]; then
    echo "$(date -Is) trial has $(pretty "$before") left; nothing to do"
    exit 0
fi

if [ -z "${IGNITION_API_TOKEN:-}" ]; then
    echo "$(date -Is) the trial has expired, but IGNITION_API_TOKEN is not set — see the header of this script" >&2
    exit 2
fi

code="$(curl -s -m 15 -o /dev/null -w '%{http_code}' -X POST \
    -H "X-Ignition-API-Token: $IGNITION_API_TOKEN" "$gateway/data/api/v1/trial")"
after="$(left)"

if [ "$code" != "200" ] || [ "$after" -le 0 ]; then
    echo "$(date -Is) reset FAILED (HTTP $code); $(pretty "$after") left" >&2
    exit 1
fi
echo "$(date -Is) trial reset: it had expired, now $(pretty "$after")"
