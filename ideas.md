# Module ideas

Ignition modules that carry Nautilus (`~/Development/joyautomation/nautilus`) into plants that already run
Ignition. The common thread: the site keeps its gateway, and gets one piece of "SCADA built like software" at a
time, each piece useful by itself.

Status: **building** · **next** · **idea**

---

## 1. Mantle: Sparkplug B host that isn't a chore · **shipped** (`mantle/`)

**1.3.0 released 2026-09-22.** Free, Apache-2.0, signed, at
https://github.com/joyautomation/ignition-modules/releases — product page at
https://joyautomation.com/software/mantle. 95 Sparkplug TCK assertions pass, none fail; three broker
implementations and two transports in CI. Remaining gaps are listed in `mantle/README.md`.

**Next for it, in order:** submit the Ignition Module Showcase application; `wss://`; AWS IoT Core and Azure
(both need accounts).

Tags appear as they are born, are historized unless you say otherwise, and are customized in place. No second
set of tags. See `mantle/README.md`.

Follow-ons, roughly in order of value:

- **Gateway status page.** Connections, nodes online/offline, seq gaps, rebirths requested, decode failures,
  last birth per node, and a "request rebirth" button. 8.3's web UI takes React pages (`webui-webpage` SDK
  example). The counters already exist on `HostState`.
- **Templates as real UDTs.** Today a template instance flattens to a folder (mantle's approach). Nautilus turns
  NBIRTH template definitions into struct types (`sparkplug.StructDefsFromTemplates`); the Ignition analogue is
  creating UDT definitions from template definitions and instances of them, so a site can bind a Perspective view
  to a type. Needs care: a managed provider's UDT support has to be proven first.
- **Offline write queue.** Nautilus parks a command for a dark node and releases it on the next birth unless the
  birth already reports that value (`sparkplug/host/mqtt.go: queueOffline / releaseQueued / markWritten`). Today
  a write to an offline node is refused.
- **Stale sweep.** Nautilus's `StaleAfter`: a node that goes silent without an NDEATH (broker never fired the
  will) should go stale anyway.
- **Primary/standby on redundant gateways.** Nautilus gates STATE on `Primary && isLeader()` so a standby never
  flaps store-and-forward at the edge. Map that to Ignition redundancy state.
- **TCK in CI.** Run `sparkplug-tck-go`'s host-application profile against the module, the way Nautilus does
  (81 pass / 0 fail there). This is the claim Cirrus Link can't casually make.
- **MQTT 5.** The HiveMQ client already supports it; it is a config option away.
- **Transmission side.** A Sparkplug *edge node* module: publish any Ignition tag folder with RBE deadbands and
  store-and-forward marked historical (`sparkplug/rbe.go`, `sparkplug/storeforward.go`), UDTs as templates.
  Same philosophy: point it at a folder, no mirrored tag tree.

## 2. Nautilus HMI for Perspective: components and themes · **next** (designed: `docs/perspective-ui.md`)

The route is decided and the riskiest assumption is checked: Svelte 5 stays the single implementation,
custom elements are the public API, one generic React adapter hosts them. `TankGlyph.svelte` compiles to a
custom element with zero warnings, and the theme is CSS custom properties, which cross the shadow boundary.
Two modules: a theme pack (no JavaScript, useful alone) and the component module. **Next action is the spike
in `docs/perspective-ui.md`, not the 54 components.**

People open Perspective and face a blank canvas and a grey default theme. `@joyautomation/nautilus-hmi` already
has the answer: ~55 Svelte 5 components (Tank, Pump, Valve, Pipe, Gauge, TrendChart, Sparkline, AlarmBanner,
AlarmTable, WriteNumber, ConfirmDialog, StatusPill, AppShell…) and a documented two-layer token theme
(`hmi/src/lib/theme.css`: raw palette → semantic role, dark-first "ops room at 03:00", depth from borders and
`color-mix()` rather than shadows).

Two deliverables, separable:

- **Theme pack.** Perspective themes are CSS variable files. Generate them from Nautilus's tokens so stock
  Perspective components look like Nautilus with no code at all. Smallest possible first release, and it is
  useful to someone who never installs anything else. (An "immutable project" module can ship themes and
  starter views: see the `immutable-project` SDK example.)
- **Component module.** Perspective components are React, but a Svelte 5 component compiles to a custom
  element, and a thin React wrapper per component handles props and events (`perspective-component` SDK
  example is the scaffold). One source of truth for the symbols in both Nautilus HMIs and Perspective. Props
  map to Perspective property trees so bindings work normally; high-performance displays follow ISA-101.

Include a starter project: app shell, nav, alarm banner, a trend page and an overview page wired to the
`Sparkplug` tag provider from idea 1, so a fresh gateway plus a broker URL gets to a working screen in minutes.

## 3. SvelteKit on Ignition · **next**

Let a properly structured SvelteKit app be the HMI, with Ignition as the backend. Two halves:

- **Gateway module** that serves a built SvelteKit app (adapter-static, or adapter-node behind a proxy route)
  from a project resource, and exposes Ignition's services over HTTP: tag read/subscribe/write, tag history,
  alarm status/ack/shelve, alarm journal, named queries, and the session's identity and security levels from the
  gateway IdP. 8.3's route system does the mounting and the OpenAPI annotations.
- **`@joyautomation/ignition-kit` npm package**: typed client and Svelte 5 rune stores.

The trick that makes this a segue rather than another API: **mirror Nautilus's own server surface**,
`/api/state`, `/api/stream` (SSE with generation-stamped deltas), `/api/tags`, `/api/alarms*`,
`/api/history*`. Then `nautilus-hmi`'s `realtime.svelte.ts`, `alarms.svelte.ts` and `history.ts` run unchanged
against an Ignition gateway. An HMI written for Ignition today moves to a Nautilus runtime later by changing a
base URL, and vice versa. The delta-stream design matters here: it took a real Nautilus host from 4.3 MB/min to
0.15 MB/min.

Authorization has to be real: every route checks Ignition security levels, writes are audited to the gateway
audit log, and tag write permissions are honoured rather than bypassed.

## 4. Alarm notification that people don't hate · **next**

The Twilio module is one provider, one channel, and no opinion about on-call. Build a notification profile
(`AlarmNotificationProfile` extension point; the SDK's `slack-alarm-notification` example is the skeleton) that
is:

- **Provider-agnostic**: Twilio, Telnyx, SignalWire, plain SMTP, Slack, Teams, ntfy, Pushover, generic webhook.
  One interface, provider is config. Nautilus's `WebhookNotifier` already has the right failure rule: a slow
  endpoint drops with a counter instead of stalling the engine.
- **Two-way**: acknowledge by replying to the SMS, or by a signed link. Ack and shelve from the phone.
- **Storm-aware**: group by site/area, digest instead of 400 texts when a comms link drops, and suppress
  children when the parent "site offline" alarm is active. Mantle's `_meta/Online` tags are the natural parent.
- **On-call built in**: rotations, escalation after N minutes unacked, quiet hours, holiday overrides, without
  building a pipeline blocks maze for the common case.
- **Accountable**: a delivery journal (queued, sent, delivered, failed, acked-by) that can be queried and shown
  in a Perspective/SvelteKit component.

## 5. Alarms as code · **idea**

Nautilus's strongest alarm idea is `Rule`: match a struct type plus member and generate the alarm defs. "14
rules cover ~1 850 fleet alarms." Ignition's equivalent today is hand-editing UDT definitions or tag-by-tag.

A module that reads rules (YAML in a project resource, so it is in git) and applies alarm configuration to
matching tags by path pattern or UDT type, writing into the tags' own user layer. It composes with idea 1:
Sparkplug tags appear by themselves, rules put ISA-18.2 alarms on them by themselves, and nobody builds a
management layer. Include Nautilus's `check` behaviour: a dead rule or an unknown member is reported, a tag that
does not exist yet is "suppressed with a reason" rather than an error.

Generalizes to **tag config as code**: deadbands, history settings, scaling and security by rule.

## 6. Nautilus bridge, without MQTT in the middle · **idea**

A managed tag provider fed straight from a Nautilus runtime's `/api/stream`, with writes to `/api/tags`. For the
site that wants one Nautilus controller visible in Ignition and does not run a broker. Reuses `ManagedTagSink`
from idea 1 nearly whole: the sink interface was written so the source doesn't have to be Sparkplug.

The reverse is a Nautilus `io.Driver` that reads and writes Ignition tags through idea 3's API, so Nautilus logic
can be layered over an existing Ignition plant without touching the PLCs.

## 7. TimescaleDB historian · **idea**

8.3 made historians an extension point (`HistorianExtensionPoint` in `historian-gateway-api`). mantle already has
the production recipe: hypertables, `compress_segmentby` on the series key, compression after an hour, retention
policy, `time_bucket` queries with a left-edge sample so charts don't start blank. Fix the two known mantle
mistakes on the way in: store doubles as `double precision` not `real`, and never build SQL from metric ids.
Pairs with `cnpg-timescaledb`.

## 8. Logic you can test, running beside the gateway · **idea**

A module that supervises a `nautilus` binary as a sidecar (start, stop, health, log capture, hot-swap on project
save) and surfaces its tags through idea 6. Sites get IEC 61131-3 Structured Text with acceptance tests and
virtual time, for the calculations and sequencing that currently live in untestable gateway timer scripts.

## 9. Acceptance tests for Ignition projects · **idea**

Nautilus's `*_test.yaml` format pointed at Ignition tags: set these tags, wait, expect those tags and alarms.
Runs from CI against a throwaway gateway container (this repo's `docker-compose.yml` is most of the harness).
Virtual time is not available, so it suits integration checks, not timing proofs.

---

## Cross-cutting notes

- **Target 8.3+.** File-based config, the secrets API, the REST/OpenAPI layer and historian extension points are
  what make these modules pleasant; 8.1 support would cost more than it returns.
- **One Gradle build per module** in this repo, sharing `docker-compose.yml` and `scripts/`.
- **Signing.** Everything builds unsigned for development. Shipping needs a code-signing cert and
  `skipModlSigning.set(false)`.
- **Content.** Each of these is demo-able; per the Nautilus habit, record episode ideas in
  `~/Development/joyautomation/content` as they land.
