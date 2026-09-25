#!/usr/bin/env bash
# Takes a full gateway backup, so a `dev-up.sh --fresh` restores this gateway instead of building a blank one.
#
#   scripts/save-gateway.sh
#
# A .gwbk is everything: API keys, security levels and permissions, users, projects, tag providers, module
# settings, connections. Config resources seeded from dev/config/ only cover what can be rebuilt from a file;
# an API key cannot, because the gateway shows its value once and stores something derived from it.
#
# Run this after configuring anything through the web UI that you would be annoyed to lose. Then --fresh is
# safe: the volume is rebuilt, and this backup is restored over it.
#
# The file holds credentials for this gateway, so dev/gateway.gwbk is gitignored and stays on this machine.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
# shellcheck source=scripts/lib.sh
source scripts/lib.sh

out="dev/gateway.gwbk"
mkdir -p dev

if ! docker compose ps --format '{{.Name}}' 2>/dev/null | grep -q gateway; then
    echo "the gateway is not running; start it first (scripts/dev-up.sh)" >&2
    exit 1
fi

echo "taking a gateway backup..."
docker compose exec -T gateway sh -lc 'cd /usr/local/bin/ignition && ./gwcmd.sh -b /tmp/save.gwbk' >/dev/null
# Keep the previous one: a backup taken from a broken gateway is worse than the backup it replaced.
[ -f "$out" ] && mv "$out" "$out.prev"
docker compose cp gateway:/tmp/save.gwbk "$out" >/dev/null
docker compose exec -T gateway rm -f /tmp/save.gwbk

printf 'saved %s (%s)\n' "$out" "$(du -h "$out" | cut -f1)"
[ -f "$out.prev" ] && printf 'previous backup kept at %s.prev\n' "$out"
cat <<'EOF'

scripts/dev-up.sh --fresh will now restore this gateway rather than building a blank one, so API keys,
security levels and permissions, users and projects all survive.

To deliberately start from nothing again, move dev/gateway.gwbk out of the way first.
EOF
