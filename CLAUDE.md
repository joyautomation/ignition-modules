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
- `scripts/designer/designer.sh {status,up,shot,do}`: **the Designer, drivable and readable without asking the
  user for a screenshot.** Opens it in the `im12-designer` container against the dev gateway, clicks in it, and
  brings back PNGs — `shot --crop X,Y,W,H` is legible enough to read tag names. Coordinates are client-relative.
  Read `scripts/designer/README.md` before using it, and read every frame: a blind script reports success.
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
- **A Perspective theme is served FLATTENED, and built once at startup.** The browser never fetches your
  `index.css`; the gateway resolves the whole `@import` chain into one file at
  `/data/perspective/themes/<name>.css` (the per-file form is project-scoped:
  `/data/perspective/client/<project>/themes/<name>/variables.css`). Base values and your overrides both
  appear in it, yours last, so later-wins at equal `:root` specificity is the whole mechanism. Because config
  resources are read at startup, `docker cp`ing a changed theme does nothing until `docker compose restart
  gateway` — the file on disk and the CSS being served disagree silently, with nothing in the log.
- **Most of Perspective's 118 theme variables resolve through ones you already override.** `--checkbox--checked`,
  `--radio--selected` and `--toggleSwitch--selected` are all `var(--callToAction)`; the progress bar is
  `var(--infoSecondary)`; disabled states run through the neutral ramp. Mapping 51 of 118 leaves the controls
  correct for free, so a raw "67 unmapped" count badly overstates the gap. The genuinely uncovered ones are the
  literal-hex chart palettes (`--qual-*`, `--seq-*`, `--div-*`) and `--boxShadow*`.
- **Perspective's ramps invert between light and dark, and `--seq-*` does it too.** `--seq-1` is always the step
  nearest the background: the dark base lightens (L 59 -> 96), the light base darkens (L 67 -> 29). Same trap as
  `--neutral-10`. Check against the shipped base before assuming a direction.
- **A chart axis and an equipment outline are not the same colour, and `--n-axis` was doing both.**
  Measured: `#383835` is **1.65:1** against `--bg` and 1.34:1 against `--surface-2`, where WCAG 1.4.11 asks
  **3:1** for a graphical object — correct for a hairline that should recede behind data, wrong for a pipe
  someone has to find on a plant screen. The generator now derives `--n-stroke` at the house hue, at the
  lightness that first clears 3:1 against *both* the page background and a panel surface (a pipe can sit on
  either, so passing against one is not passing): `#72726d` dark at 4.02/3.26, `#5f5e59` light at 6.22/5.65.
  The wider point: **"does this look too dark" is usually measurable, not a matter of taste** — contrast
  ratio is arithmetic on two hex values and needs no screenshot and no human.
- **A symbol's liquid fill is a component property, not a theme variable.** There is no `--vessel*`/`--tank*`/
  `--liquid*` anywhere in the theme layer, so a Vessel's blue fill survives any theme; `--pipe*` and
  `--symbolFill--*` are themeable and do follow. Don't chase a theme variable for it.
- **The Nautilus CLI is `cmd/naut`, and the integration harness builds it from source.** It used to be
  `cmd/nautilus`; `useNautilusFromSource` probes both (`nautilusEntrypoints` in `mantle_test.go`) and now
  *fails* rather than falling back to PATH, because the silent fallback turned the rename into fifteen
  identical `exec: "nautilus": executable file not found` failures and two red nightlies (2026-10-09, -10)
  that said nothing about Mantle. Add to that list when it moves again.
- **A Nautilus edge is read-only unless `sparkplug.writable` lists the tag** (Nautilus `6d41374`, 2026-10-07).
  CI builds Nautilus from `main`, so that default landing turned the nightly red for twelve days with no change
  here: `TestAWrite*` failed with `edge LevelSP = 10, want 21.25`, and only the edge's own log said why
  (`command write refused — not writable`). When a nightly fails on an unchanged commit, read the nautilus log
  the test prints before suspecting Mantle.
- **This machine's dev stack has no seeded `dev-broker` connection.** The im12 shoot unseeded the dev
  connections so the video could create one on camera, and the on-camera `plant` connection has
  `hostId: ""`. Mantle publishes no STATE without a host id, so every `mantle_test.go` edge — they all set
  `primary-host` to `gw.HostID` — waits the full 30 s and fails with `has not birthed into Ignition yet`,
  while `clockskew_test.go` and `load_test.go` (no primary host) pass. It looks exactly like a protocol
  regression and is local drift: CI runs `dev-up.sh --fresh`, which seeds `dev-broker` with
  `hostId: joy-dev` (`scripts/dev-up.sh:93`). Check the connection's `hostId` before believing a birth
  failure.
- **A backgrounded process spawned through `incus exec` dies with the exec session.** `incus exec C -- su - dev
  -c "nohup ./thing &"` returns, the session is torn down, and the child goes with it — no process, no window,
  an empty redirect target, nothing in any log. It looks exactly like the program failing to start. Anything
  long-lived has to be launched by a script *inside* the container that then blocks until it is up.
- **A UI script that reports every step as fine has told you nothing.** The Designer layout sequence ported from
  the content repo's im12 rig printed all fourteen of its verbs, errored on none, and had selected the wrong
  workspace, left a panel open and left the tag provider unchanged. xdotool clicks empty space as happily as a
  button. The only check that works is capturing the frame and looking at it — which is cheap now
  (`scripts/designer/designer.sh do ... --shot`), so there is no excuse for inferring a UI state from an exit
  code. Corollary: a coordinate measured in one Designer is a measurement, not a constant.
- **`pkill -f` / `pgrep -f` match the shell running them.** The pattern appears in that shell's own command
  line, so `pkill -f "http.server 8901"` kills the shell mid-script and the rest of the command — the edit,
  the commit — silently never runs, surfacing only as exit code 144. Bitten twice here. Match on something
  the kill command itself cannot contain (a pidfile, `lsof -ti:PORT | xargs -r kill`), or just let a
  backgrounded test server expire with the session.
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
- **Module trust survives replacing the file.** Once a UI-installed module's certificate has been accepted,
  the `.modl` can be overwritten in place and the gateway restarted without going back to quarantine
  (verified 2026-09-28: 0 quarantine lines, resources served immediately). The UI puts it in
  `data/var/ignition/modl/<Name>.modl` — inside the data volume, not `user-lib/modules` — so it survives a
  container recreate too. That makes the iterate loop `docker compose cp` + `restart`, with no browser.
- **`./gradlew build` will happily ship a stale resource; use `clean build`.** After replacing a file under
  `gateway/src/main/resources/mounted/`, the module task reported `UP-TO-DATE` and rebuilt a `.modl` whose
  jar still held the *previous* file — same byte count, old timestamp, no warning. Same staging-directory
  cause as the excludes trap below. Verify by unzipping the `.modl` and listing the jar; do not trust the
  `.modl`'s own timestamp, which updates while its contents do not.
- **A module installed through the web UI is QUARANTINED pending certificate acceptance**, and
  `-Dignition.allowunsignedmodules=true` does not cover it. The log line is
  *"Moving module <name> to quarantine because certificate not yet accepted"*, and the only symptom
  otherwise is that every one of its resources 404s — `/res/<alias>/js/<file>.js` included — while the
  install itself reported success. Mantle never hits this because `ACCEPT_MODULE_CERTS` auto-accepts it on
  the first boot of a fresh volume; a module added by hand to an existing volume gets no such help, and has
  to be trusted in Config → Modules. There is a modules REST API (the install logs
  `route-group=api, route-path=/v1/modules/install`) but `/data/api/v1/modules{,/list,/quarantine}` all
  answer *"No route"* under both basic auth and an API token, so the acceptance is a browser action.
- **A Perspective view and its page mapping are project resources, read at startup.** A view is
  `data/projects/<project>/com.inductiveautomation.perspective/views/<Name>/{view.json,resource.json}`, and a
  URL only reaches it through `page-config/config.json` (`{"pages": {"/spike": {"viewPath": "NautilusSpike"}}}`)
  — a view with no page entry renders *"View Not Found"*, which reads like a missing view and is not. Both
  need a `docker compose restart gateway` to be seen, exactly like a theme. A hand-written `resource.json` can
  leave `attributes` empty; the `lastModificationSignature` the Designer writes is not required.
- **Perspective's own components crash on a missing `style` prop.** `ia.symbol.vessel` in a hand-written
  view.json throws `Cannot read properties of undefined (reading 'classes')` from `parseStyle` inside its
  `getPropsReducer`, and Perspective's error boundary then replaces the *whole view* with "Component Error" —
  so one under-specified stock component hides every other component on the page. Adding
  `"style": {"classes": ""}` was not enough for the Vessel specifically; `ia.display.label` is a safe control
  to reach for instead. Read the console: the stack names the component, which is the only thing that
  distinguishes "my component is broken" from "the thing next to it is".
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
- **A host takes a metric's own timestamp over the payload's** (`HostState`: `m.getTimestamp() != null ?
  m.getTimestamp() : payloadTs`), and `MetricBuilder` stamps every metric with *now*. So skewing only the
  payload timestamp in `EdgeSimulator` changes nothing that reaches a value, and a clock-skew test built on
  it passes against a deliberately broken guard.
- **A tag that does not exist yet reads back `Good` with a nil value and the current time.** An integration
  test that waits for "Good" therefore passes instantly, before anything has published. Wait for a value of
  the right *type* (`v.Float()`), not for quality.
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
- **Comparison claims about Cirrus Link's MQTT Engine come from a measurement, not memory.** Engine 5.0.2 on 8.3.9
  was tested on 2026-10-26: content repo, `research/mqtt-engine-5.0.2-behaviour.md` (what survives a rebirth,
  a gateway restart, a tag delete, a UDT change; with Cirrus Link's own doc links). Anything not in that note
  is still untested; don't write it into an idea as fact.
- That repo usually has another session's uncommitted work in it. Commit only your own hunk (build the index
  entry from `HEAD` plus your text; see the first `IM` commit), never `git add -A` there, and don't push it.

- **`--fresh` no longer destroys the gateway: `scripts/save-gateway.sh` takes a `.gwbk` and `dev-up.sh`
  restores it into the new volume.** That is the only way to keep an API key, because the gateway shows its
  value once and stores something derived from it — it cannot be rebuilt from a file the way a connection
  can. The image takes `-r <file>` and restores on a clean volume only; it **aborts startup if that file is
  missing**, so dev-up writes `dev/compose.restore.yml` only when `dev/gateway.gwbk` exists rather than
  always declaring the mount. The backup holds credentials, so it is gitignored. **Run save-gateway.sh after
  configuring anything through the web UI.**
- **Gateway config you do not want to rebuild by hand lives in `dev/config/` and is seeded by `dev-up.sh`.**
  `scripts/save-dev-config.sh` snapshots it back out of a running gateway. Security levels and gateway
  permissions are **singleton** resources — their files sit directly in the type folder, not under a
  `<name>/` — hence `seed_singleton` beside `seed_config`.
- **An API key defaults to `secureChannelRequired: true`, so it 401s over plain HTTP** — and the dev
  gateway only exposes 8088. That looks exactly like a permissions problem and is not one. It is a field in
  the key's own config resource (`ignition/api-token/<name>/config.json`); the dev key has it false.
- **Never bind-mount a file that a script replaces.** `dev/gateway.gwbk` was mounted directly at
  `/restore.gwbk`; taking a new backup moved it aside, Docker recreated the source as a root-owned
  directory, and from then on **every `docker compose cp` to that container failed** with
  `mkdirat restore.gwbk: file exists`. Mount the directory (`./dev:/restore:ro`) instead — it survives the
  file inside it being replaced. Streaming with `docker exec cat` also works when `cp` is wedged.
- **`gwcmd.sh -b <file>` prompts before overwriting**, and with no stdin that surfaces as
  `java.util.NoSuchElementException: No line found` rather than anything about a prompt. Delete the target
  first.
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
