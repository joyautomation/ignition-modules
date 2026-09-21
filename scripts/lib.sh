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
#   e.g. seed_config com.joyautomation.mantle/connection dev-broker dev/config/mantle/dev-broker
seed_config() {
    local type="$1" name="$2" src="$3"
    docker compose exec -T gateway mkdir -p "$gateway_config_dir/$type"
    docker compose cp "$src" "gateway:$gateway_config_dir/$type/$name" >/dev/null
    docker compose exec -T -u root gateway chown -R ignition:ignition "$gateway_config_dir/${type%%/*}"
}

# trust_dev_ca: teaches the gateway to trust the dev broker's private CA.
#
# This is the same mechanism a plant uses for its own broker. Ignition loads every certificate in
# data/certificates/supplemental into the JVM's default trust store at startup, and that is what Mantle's
# ssl:// connections use — so trusting a private CA is a gateway-level thing an Ignition administrator
# already knows how to do, and Mantle needs no trust-store setting of its own.
trust_dev_ca() {
    local dir=/usr/local/bin/ignition/data/certificates/supplemental
    docker compose exec -T gateway mkdir -p "$dir"
    docker compose cp dev/certs/ca.crt "gateway:$dir/mantle-dev-ca.crt" >/dev/null
    docker compose exec -T -u root gateway chown -R ignition:ignition "$dir"
}

# stage_client_certificate: the PEM pair the mtls-broker connection points at, inside the gateway.
#
# This is the one dev connection that can be seeded from a committed file, because mutual TLS needs no
# secret — the certificate is the identity. The password-carrying ones cannot: an embedded secret is
# encrypted with the gateway's own key, so a committed one would not decrypt anywhere else.
stage_client_certificate() {
    local dir=/usr/local/bin/ignition/data/mantle-certs
    docker compose exec -T gateway mkdir -p "$dir"
    docker compose cp dev/certs/client.crt "gateway:$dir/client.crt" >/dev/null
    docker compose cp dev/certs/client.key "gateway:$dir/client.key" >/dev/null
    docker compose exec -T -u root gateway chown -R ignition:ignition "$dir"
}

# seed_tls_connection: the TLS + username/password connection, built here rather than committed.
#
# An embedded secret is a JWE encrypted with the gateway's own key, so a committed config.json would not
# decrypt on anybody else's gateway. The integration-api project (already seeded, and the only thing here
# that accepts a plain gateway login) encrypts it, and the result is written as an ordinary config resource.
#
# Requires the gateway to be up with that project loaded, so call it after the first restart.
seed_tls_connection() {
    local name=tls-broker dir
    dir="$(mktemp -d)"
    local secret
    secret=$(curl -sf -m 30 -u admin:password -H 'Content-Type: application/json' \
        -d '{"op":"encrypt","plaintext":"mantle-dev-password"}' \
        http://localhost:8088/system/webdev/integration-api/api \
        | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["secret"]))')
    if [ -z "$secret" ]; then
        echo "could not encrypt the broker password; is the integration-api project loaded?" >&2
        rm -rf "$dir"
        return 1
    fi

    cat > "$dir/config.json" <<JSON
{
  "profile": { "type": "MQTT" },
  "settings": {
    "brokerUrl": "ssl://broker:8883",
    "username": "mantle",
    "password": { "type": "Embedded", "data": $secret },
    "keepAliveSeconds": 30,
    "hostId": "joy-dev-tls",
    "reorderTimeoutMs": 5000,
    "tagProvider": "SparkplugTls",
    "historizeByDefault": true
  }
}
JSON
    cat > "$dir/resource.json" <<'JSON'
{
  "scope": "A",
  "description": "TLS and a username/password against the dev broker's 8883 listener",
  "version": 1,
  "restricted": false,
  "overridable": true,
  "files": ["config.json"],
  "attributes": {
    "uuid": "5d1c1f0e-7a54-4a4e-9a57-6b7f5a1d0003",
    "enabled": true
  }
}
JSON
    seed_config com.joyautomation.mantle/connection "$name" "$dir"
    rm -rf "$dir"
}

# seed_connection <name> <broker-url> <host-id> <tag-provider>: a Mantle connection as a config resource.
#
# Files rather than the configuration REST API, because that API accepts only a session or an API token and
# an API token does not survive a fresh data volume — so CI can never have one. docker cp needs no
# credentials at all. The cost is that config resources are read at startup, so the caller has to restart
# the gateway afterwards.
seed_connection() {
    local name="$1" url="$2" host_id="$3" provider="$4" dir
    dir="$(mktemp -d)"
    cat > "$dir/config.json" <<JSON
{
  "profile": { "type": "MQTT" },
  "settings": {
    "brokerUrl": "$url",
    "keepAliveSeconds": 30,
    "hostId": "$host_id",
    "reorderTimeoutMs": 5000,
    "tagProvider": "$provider",
    "historizeByDefault": false
  }
}
JSON
    cat > "$dir/resource.json" <<JSON
{
  "scope": "A",
  "description": "created by scripts/, removed again by it",
  "version": 1,
  "restricted": false,
  "overridable": true,
  "files": ["config.json"],
  "attributes": { "uuid": "$(uuidgen 2>/dev/null || echo "5d1c1f0e-7a54-4a4e-9a57-6b7f5a1d09$RANDOM")", "enabled": true }
}
JSON
    seed_config com.joyautomation.mantle/connection "$name" "$dir"
    rm -rf "$dir"
}

# unseed_connection <name>: removes the resource directory again. Also needs a restart to take effect.
unseed_connection() {
    docker compose exec -T gateway rm -rf \
        "$gateway_config_dir/com.joyautomation.mantle/connection/$1" 2>/dev/null || true
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
