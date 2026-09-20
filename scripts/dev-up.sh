#!/usr/bin/env bash
# Bring up a dev gateway from nothing, with the modules in this repo installed and a working config:
# a Core Historian, a Mantle connection to the mosquitto container, and the integration tests' project.
#
#   scripts/dev-up.sh            # keeps an existing data volume
#   scripts/dev-up.sh --fresh    # throws the data volume away first
#
# Third-party modules are only auto-registered on the first boot of a fresh data volume (and only when accepted
# through ACCEPT_MODULE_LICENSES / ACCEPT_MODULE_CERTS in docker-compose.yml), so the .modl has to be inside the
# container before it starts for the first time. That is why this creates, copies, then starts.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
# shellcheck source=scripts/lib.sh
source scripts/lib.sh

modules=(mantle)

if [ "${1:-}" = "--fresh" ]; then
    docker compose down -v
fi

for module in "${modules[@]}"; do
    (cd "$module" && ./gradlew build)
done

docker compose create
for module in "${modules[@]}"; do
    copy_module "$module"
done
docker compose start
wait_for_gateway

seed_config com.inductiveautomation.historian/historian-provider Core dev/config/core-historian
seed_config com.joyautomation.mantle/broker-connection dev-broker dev/config/mantle/dev-broker
# what the integration tests observe the gateway through; it requires an Administrator login
seed_project integration-api integration/gateway-project/integration-api
# config resources are read at startup
docker compose restart gateway
wait_for_gateway

cat <<'EOF'

gateway   http://localhost:8088   (admin / password)
broker    tcp://localhost:1883

Run the integration tests (a real Nautilus edge node against this gateway):
  (cd integration && go test ./...)
Or feed it by hand, and watch tags appear under the [Sparkplug] provider:
  (cd integration/mantle/edge && nautilus run)
  (cd mantle && ./gradlew :gateway:simulate --args="tcp://localhost:1883 Plant Edge1")
EOF
