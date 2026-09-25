# Ignition modules

Ignition 8.3+ modules in one repo (`joyautomation/ignition-modules`): one Gradle build per module directory
(`mantle/`), sharing the dev stack, `scripts/`, and the integration harness. Each module versions and releases on
its own (tags like `mantle/v1.3.0` — **the middle digit must match the platform's minor**, so 8.3 means x.3.y;
Inductive's own modules do the same, e.g. Historian 1.3.9 on 8.3.9). Pushing that tag runs
`.github/workflows/release.yml`, which builds, runs both suites, signs from repository secrets and drafts a
GitHub release. CI is path-filtered per module. See `README.md`, `mantle/README.md`,
`ideas.md`.

## Commands

- `scripts/dev-up.sh --fresh`: dev gateway + broker + modules + seeded config, from nothing.
- `scripts/install-module.sh mantle`: rebuild and reload after a change.
- `scripts/demo.sh` / `--stop`: edge nodes publishing into the dev gateway, for looking at the status page.
  It stops only what it started (recorded PIDs, plus a sweep scoped to its own directory): **other `nautilus`
  processes on this machine belong to the user — never kill by name.**
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
- **The gateway serves source maps for its own web UI, and they carry `sourcesContent`.** This is the only
  real documentation of the gateway's React API. `/res/sys/js/IgnitionGatewayLib.js.map` unpacks to the full
  TypeScript of `@inductiveautomation/ignition-gateway-lib` (`src/index.ts` is its export list); every module
  bundle has one too (`/res/<module>/js/web-ui/<name>.js.map`), so IA's own pages read as worked examples —
  `historian.js.map` gives a ~40-line `ExtensionPointDataGridPage` usage. The bare specifiers resolve through
  the SystemJS import map printed inline in `GET /app`.
- **A config page is a React page, but almost none of it is yours.** `ExtensionPointDataGridPage` from
  `@inductiveautomation/ignition-gateway-lib` *is* the gateway's config page — table, create wizard, edit,
  delete, enable/disable, config-mode banner — and the add/edit form inside it is generated from the JSON
  Schema the gateway derives from the annotations on the settings record. Declare the two
  `@inductiveautomation/*` packages as webpack `externals` and never install them; they exist only on IA's
  private registry, and the gateway supplies them at runtime. What a module must still write is the *list*:
  which columns, the page title, the blank state (`mantle/web-ui/src/ConnectionsPage.tsx`).
- **A resource type with no route delegate has no REST routes at all**, so no UI — IA's or yours — can list or
  create one. `ResourceTypeMeta.newExtensionPointBuilder(...)` needs *both* `.withActionSet(...)` (it defaults
  to `EMPTY`) and `.buildRouteDelegate(routes -> routes.profileSchema(...))`. The extension-point builder has
  `profileSchema`, not `configSchema`. Symptom: `/data/api/v1/resources/list/<module>/<type>` 404s while an IA
  module's own type 200s.
- **TLS needs no module configuration: `data/certificates/supplemental/` reaches the JVM default trust
  store.** Drop a PEM there, restart, and HiveMQ's `sslWithDefaultConfig()` trusts it — so a plant's private
  CA is a gateway-level act an Ignition administrator already knows, and Mantle needs no truststore field.
  Verified 2026-09-20 against a private CA (`SunCertPathBuilderException` before, connected after);
  `scripts/lib.sh trust_dev_ca` is the dev stack doing it. There is no `SslManager` in the SDK, only
  `gateway.ssl.SslManagerChangeEvent`, so this is the whole story. Mutual TLS is a separate question.
- **`allow_anonymous` and `password_file` are GLOBAL in mosquitto 2** — without `per_listener_settings true`
  the last one written wins and silently turns an anonymous listener into an authenticated one. That is what
  broke port 1883 for the demo and the whole suite the first time `dev/mosquitto.conf` was written.
- **An embedded secret is a JWE encrypted with the gateway's own key**, so a config resource carrying one
  cannot be committed — it will not decrypt anywhere else. Encrypt through `/data/api/v1/encryption/encrypt`
  (session or API token only; **basic auth is refused**) or, from a script, through the integration-api
  WebDev endpoint's `encrypt` op, which does accept a gateway login. `scripts/lib.sh seed_tls_connection` is
  the working example. Deleting a resource needs its `signature` as well as its name.
- **A resource's Status column comes from a Dropwizard health check, not from your own API.** Register the
  check in `SharedHealthCheckRegistries.getDefault()` under a name containing the resource's name, and point
  the resource type at it with `.buildStatusDelegate(s -> s.instanceHealthCheck("status", "mantle.%s.status"))`
  — the `%s` is filled in with the resource name. It then appears in the resource listing under
  `healthchecks.status.result.{healthy,message}`, which is what the config page reads (`ConnectionHealth`).
  `metrics-healthchecks` comes in transitively, no dependency needed. `HealthCheckRegistry` has `unregister`,
  not `remove`. **`SharedHealthCheckRegistries.getDefault()` and `GatewayContext.getHealthCheckRegistry()`
  are the same object** (checked: `sameObject: True`), so one registration serves everything — our checks sit
  beside `host.disk.fullDisk` and `jvm.threads.deadlock`. A health check that also implements
  `CriticalHealthCheck` (title, resolution text, resolution URL, action label) is what
  `OverviewRoutes.getCriticalProblems` reads for the gateway's home-page problem list. `ConnectionHealth`
  implements it and **it works** — verified in a browser 2026-09-23. Where each method surfaces on the home
  page banner: `getTitle()` is the tooltip on the "warnings" link (so it names *which* connection),
  `getActionLabel()` is the button, `getResolutionUrl()` is where the button goes. `getResolutionText()` was
  not seen on the banner itself, but it and `getTitle()` are the two *abstract* methods on the interface —
  they have to be implemented regardless, and the platform shows them where it chooses. Note the platform
  files this under "Performance Warning", which is its framing, not ours.
  Note that `/data/api/v1/overview/banners` does **not** show it over basic auth and `/overview/problems`
  refuses basic auth outright, so a script cannot see this — it needs a logged-in browser session. Do not
  read a quiet API as "not implemented".
- **`grep -r` skips binary files; `grep -ra` does not.** Searching extracted `.class` trees for a symbol
  without `-a` returns a confident, wrong "nothing references this". Always run a control search for a symbol
  known to be used before trusting a negative.
- **Wicket is gone in 8.3** — zero classes in `gateway-api-8.3.9.jar`. The 8.1 config-page mechanism does not
  exist, and no amount of searching for it will help.
- **An extension point's name and description are bundle KEYS, not text.** `AbstractExtensionPoint`'s
  constructor takes `nameKey`/`descriptionKey`; an unresolved one renders as `¿Mantle.Connection.MQTT.name?`
  on the page. Register the bundle in `setup()` — `BundleUtil.get().addBundle("Mantle", Hook.class, "Mantle")`
  — and put the strings in `Mantle.properties` beside the hook class.
- **`user-lib/modules` is NOT in the `gateway-data` volume.** Any `docker compose up -d gateway` that
  recreates the container (an edited `docker-compose.yml` will) deletes every installed third-party module
  while `data/modules.json` — which *is* in the volume — still points at it, and the gateway logs
  *"The file for module 'com.joyautomation.mantle' is missing and will not be loaded"*. Re-run
  `scripts/install-module.sh mantle`. Symptom: every module route 404s.
- **A fresh data volume invalidates the API token in `.env.trial`.** `dev-up.sh --fresh` therefore breaks the
  trial-reset cron until a new token is made by hand. Anything scripted should go through the integration-api
  WebDev endpoint (basic auth) or `docker cp`, never an API token — CI can never have one.
- **Renaming a module's `.modl` strands the gateway on the old file.** `data/modules.json` records each module's
  file *path*, so a changed `ignitionModule.fileName` leaves the gateway loading the previous build while the
  install reports success. `scripts/lib.sh` `repoint_module` fixes the registry and deletes the stale copy.
- **The module name is `Mantle`, never "Mantle for Ignition".** Inductive's Showcase rules forbid "Ignition"
  inside a module name and allow "for Ignition" only as trailing prose (`docs/releasing.md`).
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

## Content ideas are part of the work

Anything demo-able or surprising that happens here gets a post idea in
`~/Development/joyautomation/content/ideas.md`, under **"Ignition modules"**, IDs `IM-##` (continue the numbering).
Do it at the end of a piece of work without being asked. The same goes for anything else content-shaped that
comes up here (how a shot could be captured, a rig, a series angle): that repo is what it is for, so write it
there rather than leaving it in a chat reply. Capture tooling and its lessons live in its
`assets/capture/README.md`; read how an existing rig works (`ls assets/capture`) before proposing a new one. Follow that file's format exactly: `### IM-NN — Title ·
channels · effort`, a paragraph that leads with the finding, then `*Source: path*`. Read that repo's `README.md`
and `sourcing.md` first. Rules specific to this project:

- Bugs found, wrong guesses, and measurements (6 of 16 vs 15 of 16) make better ideas than features. Record the
  number and the test that produced it.
- Cross-reference instead of duplicating: Nautilus-side stories are `N-##` and belong to that section.
- **Nothing here has tested Cirrus Link's MQTT Engine.** Don't write a comparison claim into an idea as fact.
- That repo usually has another session's uncommitted work in it. Commit only your own hunk (build the index
  entry from `HEAD` plus your text; see the first `IM` commit), never `git add -A` there, and don't push it.

- **Gateway config you do not want to rebuild by hand lives in `dev/config/` and is seeded by `dev-up.sh`.**
  `scripts/save-dev-config.sh` snapshots it back out of a running gateway. Security levels and gateway
  permissions are **singleton** resources — their files sit directly in the type folder, not under a
  `<name>/` — hence `seed_singleton` beside `seed_config`.
- **An API key needs `PermissionType.WRITE`, which resolves to the gateway's own `writePermissions`.**
  Traced through `LicensingRoutes` (`POST /trial` requires WRITE) →`ApiTokenManager.TOKEN_WRITE` →
  `AbstractGatewayAccessControlStrategy` → `GatewaySystemProperties.WritePermissions`. That defaults to
  `AnyOf [Authenticated/Roles/Administrator]`, and **Administrator is system-generated so it cannot be
  granted to a key** — which is why the level field is greyed out when you make one. The fix is a custom
  level added to `writePermissions` as well as to the key; `dev/config/security-levels` and
  `dev/config/security-properties` carry one called `Automation`. Note this is gateway-wide write, not
  scoped to one route.
- **The trial-reset cron needs a token that `--fresh` destroys.** An API token belongs to a data volume, so
  `dev-up.sh --fresh` invalidates the one in `.env.trial` and `keep-trial-alive.sh` then 401s every five
  minutes for ever (379 times in one day, silently, into `/tmp/ignition-trial.log`). There is no script-only
  way round it: the reset endpoint takes a token or a browser session, **`/data/app/login` is not a JSON
  endpoint in 8.3** (404 — logins go through an identity-provider flow), and `gwcmd.sh` has no trial
  command. Make a new token after a `--fresh`. `ignition/api-token` *is* a config resource type, so seeding
  one from a file the way connections are seeded is the likely permanent fix — not tried yet.
- **An expired trial makes the dev stack lie, not fail.** WebDev answers **402**, so anything going through
  the integration-api endpoint silently does nothing — `scripts/tck-conformance.sh` scored 84 instead of 94
  and the difference looked like a real regression from a code change. **Check `{"op":"ping"}` returns 200
  before trusting any comparison against this stack.** It is the same root cause as the suite hanging.
- **The dev gateway's trial expires after two hours** and takes WebDev, the historian and the test API with it,
  so the integration suite hangs in its startup retry. `scripts/keep-trial-alive.sh --install-cron` handles it;
  the gateway only permits a reset once the trial has actually lapsed, so the job is a no-op until then.

## Conventions

- Protocol logic stays free of Ignition and MQTT types (`sparkplug/HostState` behind `TagSink` and `Outbound`)
  so it is unit-testable. Nautilus's `sparkplug/host` (Go) is the reference for protocol behaviour; the original
  mantle (`../kraken/mantle`, Deno) is the reference for product shape.
- Verify behaviour in the live gateway before writing it into a README, and say which claims are only unit-tested.
