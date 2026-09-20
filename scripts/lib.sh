# Shared by the dev scripts. Expects to be sourced with the repo root as the working directory.

gateway_modules_dir=/usr/local/bin/ignition/user-lib/modules
gateway_config_dir=/usr/local/bin/ignition/data/config/resources/core

copy_module() {
    local module="$1" modl name
    modl="$(find "$module/build" -maxdepth 1 -name '*.modl' | head -1)"
    [ -n "$modl" ] || { echo "no .modl in $module/build; run ./gradlew build there first" >&2; return 1; }
    name="$(basename "$modl")"
    docker compose cp "$modl" "gateway:$gateway_modules_dir/$name" >/dev/null
    docker compose exec -T -u root gateway chown ignition:ignition "$gateway_modules_dir/$name" 2>/dev/null || true
    repoint_module "$module" "$name"
}

# The gateway records each module's FILE PATH in data/modules.json, so renaming the .modl (a changed
# ignitionModule.fileName) leaves it loading the old file for ever while the build cheerfully reports success.
# Repoint the registry at the new name and drop the stale copy.
repoint_module() {
    local module="$1" name="$2" id json
    id="$(grep -oE 'id\.set\("[^"]+"\)' "$module/build.gradle.kts" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
    [ -n "$id" ] || return 0
    json="$(mktemp)"
    docker compose cp "gateway:/usr/local/bin/ignition/data/modules.json" "$json" >/dev/null 2>&1 || {
        rm -f "$json"; return 0; }
    MODULE_ID="$id" MODL_NAME="$name" python3 - "$json" <<'PYEOF' || { rm -f "$json"; return 0; }
import json, os, sys
path, mid, name = sys.argv[1], os.environ["MODULE_ID"], os.environ["MODL_NAME"]
registry = json.load(open(path))
entry = registry.get(mid)
want = "/usr/local/bin/ignition/user-lib/modules/" + name
if not entry or entry.get("filename") == want:
    sys.exit(1)               # nothing to do
old = entry["filename"]
entry["filename"] = want
json.dump(registry, open(path, "w"), indent=2)
print("  registry repointed: %s -> %s" % (os.path.basename(old), name))
open(path + ".stale", "w").write(os.path.basename(old))
PYEOF
    docker compose cp "$json" "gateway:/usr/local/bin/ignition/data/modules.json" >/dev/null
    docker compose exec -T -u root gateway chown ignition:ignition /usr/local/bin/ignition/data/modules.json
    if [ -f "$json.stale" ]; then
        docker compose exec -T -u root gateway rm -f "$gateway_modules_dir/$(cat "$json.stale")" 2>/dev/null || true
    fi
    rm -f "$json" "$json.stale"
}

# A first boot on a fresh volume takes ~40 s on a workstation and a few minutes on a two-core CI runner.
wait_for_gateway() {
    local state
    for _ in $(seq 1 120); do
        state="$(curl -s -m 3 http://localhost:8088/StatusPing || true)"
        [ "$state" = '{"state":"RUNNING"}' ] && return 0
        sleep 3
    done
    echo "gateway did not reach RUNNING (last state: ${state:-none})" >&2
    return 1
}

# seed_config <resource-type-path> <name> <source-dir>
#   e.g. seed_config com.joyautomation.mantle/broker-connection dev-broker dev/config/mantle/dev-broker
seed_config() {
    local type="$1" name="$2" src="$3"
    docker compose exec -T gateway mkdir -p "$gateway_config_dir/$type"
    docker compose cp "$src" "gateway:$gateway_config_dir/$type/$name" >/dev/null
    docker compose exec -T -u root gateway chown -R ignition:ignition "$gateway_config_dir/${type%%/*}"
}

# seed_project <name> <source-dir>: an Ignition project, as files
seed_project() {
    local name="$1" src="$2" dir=/usr/local/bin/ignition/data/projects
    docker compose exec -T gateway rm -rf "$dir/$name"
    docker compose cp "$src" "gateway:$dir/$name" >/dev/null
    docker compose exec -T -u root gateway chown -R ignition:ignition "$dir/$name"
}

# build_nautilus prints the path to a nautilus binary built from the checkout beside this repo, building it
# only when the source is newer. Falls back to whatever is on PATH.
build_nautilus() {
    local src="${NAUTILUS_SRC:-$(cd .. && pwd)/nautilus}" bin="$PWD/integration/.run/nautilus"
    if [ -d "$src/cmd/nautilus" ]; then
        mkdir -p "$(dirname "$bin")"
        (cd "$src" && go build -o "$bin" ./cmd/nautilus) >&2 || { echo "could not build nautilus" >&2; return 1; }
        echo "$bin"
    elif command -v nautilus >/dev/null; then
        command -v nautilus
    else
        echo "no nautilus source at $src and none on PATH" >&2
        return 1
    fi
}
