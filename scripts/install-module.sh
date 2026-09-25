#!/usr/bin/env bash
# Rebuild a module and reload it in the dev gateway. This is the inner loop.
#
#   scripts/install-module.sh mantle
#
# The gateway has to have seen the module once already (scripts/dev-up.sh does that): 8.3 registers modules in
# data/modules.json and no longer scans user-lib/modules on every boot, so a module that is new to an existing
# data volume is not picked up by copying the file in.
set -euo pipefail

module="${1:?usage: install-module.sh <module-dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/lib.sh
source "$root/scripts/lib.sh"

(cd "$root/$module" && ./gradlew build)
cd "$root"
copy_module "$module"
compose restart gateway
wait_for_gateway
echo "reloaded $module at http://localhost:8088"
