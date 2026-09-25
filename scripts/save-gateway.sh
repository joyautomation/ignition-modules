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
# Remove any leftover first: gwcmd prompts before overwriting an existing file, and with no stdin that
# surfaces as "java.util.NoSuchElementException: No line found" rather than anything about a prompt.
docker compose exec -T gateway rm -f /tmp/save.gwbk
docker compose exec -T gateway sh -lc 'cd /usr/local/bin/ignition && ./gwcmd.sh -b /tmp/save.gwbk' >/dev/null

# Streamed out with `docker exec cat` rather than `docker compose cp`, and written to a temporary file
# first. Both matter: dev/ is bind-mounted into the running gateway so the backup can be restored, and
# writing straight to the destination — or moving the destination aside first — disturbs that mount. The
# first version of this script did exactly that and left Docker refusing every later copy with
# "mkdirat restore.gwbk: file exists".
tmp="$out.new"
docker exec "$(docker compose ps -q gateway)" cat /tmp/save.gwbk > "$tmp"
docker compose exec -T gateway rm -f /tmp/save.gwbk

if ! python3 -c 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).namelist()' "$tmp" 2>/dev/null; then
    echo "the backup did not come out as a readable archive; leaving $out alone" >&2
    rm -f "$tmp"
    exit 1
fi

# Keep the previous one: a backup taken from a broken gateway is worse than the backup it replaced.
[ -f "$out" ] && cp "$out" "$out.prev"
mv "$tmp" "$out"

printf 'saved %s (%s, %s entries)\n' "$out" "$(du -h "$out" | cut -f1)" \
    "$(python3 -c 'import zipfile,sys; print(len(zipfile.ZipFile(sys.argv[1]).namelist()))' "$out")"
if python3 -c 'import zipfile,sys
names = zipfile.ZipFile(sys.argv[1]).namelist()
sys.exit(0 if any("api-token" in n for n in names) else 1)' "$out" 2>/dev/null; then
    echo "  includes an API key — the trial-reset cron will survive a --fresh"
fi
[ -f "$out.prev" ] && printf 'previous backup kept at %s.prev\n' "$out"
cat <<'EOF'

scripts/dev-up.sh --fresh will now restore this gateway rather than building a blank one, so API keys,
security levels and permissions, users and projects all survive.

To deliberately start from nothing again, move dev/gateway.gwbk out of the way first.
EOF
