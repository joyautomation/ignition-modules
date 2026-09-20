# Shared by the dev scripts. Expects to be sourced with the repo root as the working directory.

gateway_modules_dir=/usr/local/bin/ignition/user-lib/modules
gateway_config_dir=/usr/local/bin/ignition/data/config/resources/core

copy_module() {
    local module="$1" modl
    modl="$(find "$module/build" -maxdepth 1 -name '*.modl' | head -1)"
    [ -n "$modl" ] || { echo "no .modl in $module/build; run ./gradlew build there first" >&2; return 1; }
    docker compose cp "$modl" "gateway:$gateway_modules_dir/$(basename "$modl")" >/dev/null
    docker compose exec -T -u root gateway chown ignition:ignition "$gateway_modules_dir/$(basename "$modl")" \
        2>/dev/null || true
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
