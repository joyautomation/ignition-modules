# Ignition modules

Ignition 8.3+ modules in one repo (`joyautomation/ignition-modules`): one Gradle build per module directory
(`mantle/`), sharing the dev stack, `scripts/`, and the integration harness. Each module versions and releases on
its own (tags like `mantle/v0.1.0`); CI is path-filtered per module. See `README.md`, `mantle/README.md`,
`ideas.md`.

## Commands

- `scripts/dev-up.sh --fresh`: dev gateway + broker + modules + seeded config, from nothing.
- `scripts/install-module.sh mantle`: rebuild and reload after a change.
- `cd integration && go test ./...` (`-short` skips the container-restarting ones). Needs the dev stack up.
  Run it after any change to `HostState`, `ManagedTagSink` or `BrokerConnection`: it has caught bugs the unit
  tests structurally cannot.
- `cd mantle && ./gradlew test`; `./gradlew :gateway:simulate --args="tcp://localhost:1883 Plant Edge1 [seconds]"`.
- Gateway logs: `docker compose logs gateway | grep -E 'Mantle|M\.Host|M\.Tags|M\.C\.'`.

## Things that cost time to learn

- **Read the SDK, don't guess it.** The jars are at
  `https://nexus.inductiveautomation.com/repository/inductiveautomation-releases/com/inductiveautomation/ignition/{gateway-api,common}/<ver>/`
  (the `ignitionsdk` group is poms only). System Java is a JRE; `javap` is at
  `~/.gradle/jdks/eclipse_adoptium-11-amd64-linux/jdk-*/bin/javap` and reads the Java 17 classes fine.
  Examples: `github.com/inductiveautomation/ignition-sdk-examples`, branch `ignition-8.3`.
- **8.3 does not scan `user-lib/modules` on every boot.** Modules are registered in `data/modules.json`. A
  third-party `.modl` is only auto-registered on the first boot of a fresh data volume, and only with
  `ACCEPT_MODULE_LICENSES` / `ACCEPT_MODULE_CERTS` set to its id. Hand-editing `modules.json` drops the gateway
  into commissioning. After first registration, replacing the file and restarting is enough.
- **Config is files**: `data/config/resources/core/<module-id>/<resource-type>/<name>/{config.json,resource.json}`,
  read at startup. Extension-point resources wrap settings as `{"profile":{"type":...},"settings":{...}}`.
  Tags persist as JSON under `.../core/ignition/tag-definition/<provider>/`, which makes them easy to assert on.
- **Managed tag providers have two layers.** `configureTag` writes `"prg"`; user edits sit beside it and win.
  Reading config with `localPropsOnly=true` returns only the user layer (so `dataType` comes back null).
- **Tahu's `MetricDataType` / `DataSetDataType` are classes, not enums**: no `switch`, compare with `equals`,
  use `toIntValue()` (the spec's type codes).
- **Keep slf4j, logback and Paho out of the `.modl`**, but exclude them per dependency: a configuration-level
  exclude on `modlImplementation` also strips them from the compile classpath. `./gradlew clean` after changing
  excludes, since the plugin's staging directory keeps old jars.
- `freeModule.set(true)` or the module runs on the two hour trial timer.
- QuestDB (Core Historian) partition directories get version suffixes (`2026-09.109`); don't hardcode the path
  when inspecting stored rows.
- To read, write, configure or query history from outside, use the `integration-api` WebDev endpoint
  (`integration/harness/gateway.go`, admin/password on the dev stack). A WebDev python resource on disk is
  `doPost.py` + `config.json`, and `config.json` needs a top-level `"resource-type": "python-resource"` or the
  handler is a 405. Never make that endpoint unauthenticated: the dev gateway is served on the tailnet.
- **`allowBackfill(true)` means any value older than a tag's current one goes to history and the live value doesn't
  move.** Deaths (wills are stamped at connect time, or not at all), clock-skewed edges and births racing tag
  creation all hit it. See `ManagedTagSink.liveTime`.
- **`configureTag` is asynchronous.** A value pushed right after it can be wiped when the tag initializes. See
  `ManagedTagSink.awaitDefinitions`.
- Ignition compresses analog history: a linear ramp is stored as two points. Test history with a zigzag.
- A test that passes suspiciously fast deserves the same look as one that fails; everything local really is
  that fast (a Nautilus edge births in ~100 ms, a SIGKILL's will arrives in ~3 ms).

## Conventions

- Protocol logic stays free of Ignition and MQTT types (`sparkplug/HostState` behind `TagSink` and `Outbound`)
  so it is unit-testable. Nautilus's `sparkplug/host` (Go) is the reference for protocol behaviour; the original
  mantle (`../kraken/mantle`, Deno) is the reference for product shape.
- Verify behaviour in the live gateway before writing it into a README, and say which claims are only unit-tested.
