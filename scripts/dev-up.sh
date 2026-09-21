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

# The broker's TLS and authenticated listeners need a CA, a server certificate and a password file, and the
# compose file mounts dev/certs, so this has to happen before anything starts.
scripts/gen-dev-certs.sh

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
seed_config com.joyautomation.mantle/connection dev-broker dev/config/mantle/dev-broker
# Mutual TLS, so CI exercises the certificate path rather than skipping it
stage_client_certificate
seed_config com.joyautomation.mantle/connection mtls-broker dev/config/mantle/mtls-broker
# what the integration tests observe the gateway through; it requires an Administrator login
seed_project integration-api integration/gateway-project/integration-api
# Trust the dev CA gateway-wide. This is how a plant trusts its own broker's certificate too: Ignition puts
# everything in data/certificates/supplemental into the JVM's default trust store, which is what Mantle's
# ssl:// connections use — so there is nothing to configure in Mantle itself. Verified 2026-09-20.
trust_dev_ca
# config resources are read at startup
docker compose restart gateway
wait_for_gateway

# Needs the gateway up with the integration-api project loaded, so it happens after that restart and costs
# one more. Without it the TLS tests skip, and a skipped test covers nothing.
seed_tls_connection
docker compose restart gateway
wait_for_gateway

cat <<'EOF'

gateway   http://localhost:8088   (admin / password)
broker    tcp://localhost:1883    anonymous
          tcp://localhost:1884    mantle / mantle-dev-password
          ssl://localhost:8883    mantle / mantle-dev-password, CA at dev/certs/ca.crt

Run the integration tests (a real Nautilus edge node against this gateway):
  (cd integration && go test ./...)
Or feed it by hand, and watch tags appear under the [Sparkplug] provider:
  (cd integration/mantle/edge && nautilus run)
  (cd mantle && ./gradlew :gateway:simulate --args="tcp://localhost:1883 Plant Edge1")
Over TLS, with a username and password:
  (cd mantle && MQTT_CA_FILE=../dev/certs/ca.crt MQTT_USERNAME=mantle MQTT_PASSWORD=mantle-dev-password \
      ./gradlew :gateway:simulate --args="ssl://localhost:8883 TlsPlant TlsEdge1")
EOF
