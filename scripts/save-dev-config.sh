#!/usr/bin/env bash
# Copies gateway config you do not want to rebuild by hand back into dev/config/, where dev-up.sh seeds it
# on every --fresh.
#
#   scripts/save-dev-config.sh            # save everything listed below
#   scripts/save-dev-config.sh api-token  # just one
#
# This exists because a data volume is the gateway's identity: `dev-up.sh --fresh` throws away API keys,
# security levels and gateway permissions along with everything else, and rebuilding them through the web UI
# is several minutes of clicking that nobody should do twice.
#
# What it does NOT save, deliberately: anything holding a secret encrypted with the gateway's own key. Those
# do not decrypt on a different gateway, so a saved copy would be worse than nothing — it would look like it
# worked. The Mantle TLS connection is the example; scripts/lib.sh builds that one on the fly instead.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
# shellcheck source=scripts/lib.sh
source scripts/lib.sh

# Singletons keep their files directly in the type folder; named types have a folder per resource.
singletons="security-levels security-properties"
named="api-token"

save_singleton() {
    local type="$1" dest="dev/config/$type"
    compose exec -T gateway test -f "$gateway_config_dir/ignition/$type/config.json" 2>/dev/null || {
        echo "  $type: not present on the gateway, skipped"; return 0; }
    mkdir -p "$dest"
    for f in config.json resource.json; do
        compose exec -T gateway cat "$gateway_config_dir/ignition/$type/$f" > "$dest/$f"
    done
    echo "  $type -> $dest"
}

save_named() {
    local type="$1" names
    names="$(compose exec -T gateway sh -c \
        "ls '$gateway_config_dir/ignition/$type' 2>/dev/null" | tr -d '\r')" || true
    if [ -z "$names" ]; then
        echo "  $type: none on the gateway, skipped"
        return 0
    fi
    for name in $names; do
        local dest="dev/config/$type/$name"
        mkdir -p "$dest"
        for f in config.json resource.json; do
            compose exec -T gateway sh -c \
                "cat '$gateway_config_dir/ignition/$type/$name/$f' 2>/dev/null" > "$dest/$f" || true
            [ -s "$dest/$f" ] || rm -f "$dest/$f"
        done
        echo "  $type/$name -> $dest"
    done
}

want="${1:-}"
echo "saving gateway config into dev/config/ ..."
for type in $singletons; do
    [ -z "$want" ] || [ "$want" = "$type" ] || continue
    save_singleton "$type"
done
for type in $named; do
    [ -z "$want" ] || [ "$want" = "$type" ] || continue
    save_named "$type"
done

cat <<'EOF'

Saved. dev-up.sh seeds these on every --fresh, so they survive from here on.

If you saved an api-token, the plaintext of the key is NOT in it and never will be — the gateway only ever
shows that once. Keep it in .env.trial (gitignored):

    echo 'IGNITION_API_TOKEN=...' > .env.trial

Then check the round trip actually works before trusting it:

    scripts/dev-up.sh --fresh && scripts/keep-trial-alive.sh --status
EOF
