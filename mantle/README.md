# Mantle for Ignition

A Sparkplug B host application for Ignition 8.3+. Point it at a broker and:

- **Tags create themselves.** Every metric in an NBIRTH or DBIRTH becomes a tag, laid out the way the wire is:
  `[Sparkplug]Group/Node/Metric` and `[Sparkplug]Group/Node/Device/Metric`. A `/` in a metric name is a folder.
- **Tags are historized by default.** New tags get History Enabled and a history provider when they are created.
  Turn history off on a tag and it stays off. Metrics the edge flags `is_transient`, and `Node Control/*` /
  `Device Control/*`, start with history off.
- **There is one set of tags.** They live in a managed tag provider that persists tags and allows customization,
  so alarms, scaling, scripts, security and history settings go on these tags directly. They survive rebirths,
  reconnects and gateway restarts. Nothing has to be mirrored into a second tag tree to be "managed".

The product shape is mantle's. The protocol engine is a port of Nautilus's `sparkplug/host`, which passes the
Sparkplug TCK host profile.

## Why customization is safe

Ignition keeps two layers per tag. What the module sets through `configureTag` is stored under `"prg"` in the tag's
JSON; what a person sets in the Designer is stored beside it and wins. A rebirth refreshes the module's layer
(datatype, units, range, documentation, the history default) and cannot reach the user's.

```json
{
  "name": "Level",
  "tagType": "AtomicTag",
  "historyEnabled": false,                       <- yours: stays
  "engUnit": "m",                                <- yours: stays
  "alarms": [{ "name": "High Level", "...": "" }],
  "prg": { "dataType": "Float4", "engUnit": "ft", "historyEnabled": true, "historyProvider": "Core" }
}
```

That was tested against 8.3.9, not assumed: see *What has been verified*.

## Configuration

Connections are 8.3 config resources of type `com.joyautomation.ignition.mantle/broker-connection`, so they are
files under `data/config/resources/core/com.joyautomation.ignition.mantle/broker-connection/<name>/`, they are
git-friendly, and they are reachable through the gateway's config REST API. A change is applied live: the
connection restarts, the tags stay. Only `brokerUrl` has to be set.

| Field | Default | |
|---|---|---|
| `brokerUrl` | `tcp://localhost:1883` | `tcp://`, `ssl://`, `ws://`, `wss://` |
| `username`, `password` | none | `password` is an Ignition secret, never plaintext on disk |
| `clientId` | `joy-<hostId>` | |
| `keepAliveSeconds` | `30` | |
| `hostId` | the connection's name | Sparkplug host application ID, published on `spBv1.0/STATE/<hostId>` |
| `groupIds` | all groups | comma-separated |
| `reorderTimeoutMs` | `5000` | how long a missing sequence number is waited for before a rebirth is requested |
| `tagProvider` | `Sparkplug` | created if missing; several connections may share one |
| `historizeByDefault` | `true` | |
| `historyProvider` | first available | |

`dev/config/mantle/dev-broker/` in the repo root is a working example.

## Behaviour

**Host application.** Connects with a clean session and a retained will of
`{"online":false,"timestamp":t}` on `spBv1.0/STATE/<hostId>`, subscribes, then publishes the matching
`online:true`. A fresh CONNECT, and so a fresh timestamp, is built for every reconnect attempt (exponential
backoff to 30 s). If an offline STATE for its own ID shows up while it is online, it corrects it.

**Sequence numbers** are enforced per edge node. An out-of-order message waits in a bounded reorder buffer; a
duplicate is dropped; a gap that isn't filled within `reorderTimeoutMs` drops the buffered tail and asks the node
for a rebirth.

**Rebirth requests** go out when data arrives from a node with no birth on record, when an alias or metric was
never declared, when a payload can't be decoded, and on connect for every node that already has tags (so a
quiet RBE node doesn't sit stale after a gateway restart). One request per node per birth cycle, re-armed after
30 s because the request itself is QoS 0.

**Deaths.** An NDEATH counts only if its `bdSeq` matches the birth's; a stale will is ignored. On NDEATH, DDEATH
or loss of the broker, tags keep their last value and go `Bad_Stale`. They come back `Good` with the next value.
A death is stamped with the gateway's clock: a will is as old as the connection it ends.

**Timestamps.** Values carry the edge's timestamps. The exception is a live value that would land at or before the
tag's current one (an edge clock running behind, or a birth racing the tag's creation): it is re-stamped just
after, because the provider allows backfill and Ignition would otherwise file it as history and never show it. A
large gap is logged once. Metrics flagged `is_historical` keep their original time and go to history without
moving the live value, which is how a store-and-forward flush fills in an outage.

**Births are shape first, then values.** Every tag in a birth is configured, the module waits until the gateway
has really built them, and only then pushes values. Otherwise a value that reports only on change can be wiped by
the tag's own initialization and sit at null until the next rebirth.

**History** is enabled with no minimum time between samples (Ignition's default is one a second): the edge's
report-by-exception has already decided what is worth storing. Analog compression still applies, so a steady ramp
is stored as its endpoints.

**Status tags** sit in `_meta` beside each node's and device's tags: `Online` (historized, the natural parent
for "site offline" alarms) and `Last Birth`.

**Aliases and missing datatypes.** DATA messages that carry only an alias and omit the datatype decode against
the types the birth declared, tracked per device so two devices may both have a `Temp` of different types.

**Types.** Unsigned integers widen to the next signed Ignition type instead of wrapping (UInt32 → Int8/long);
UInt64 saturates at `Long.MAX_VALUE`. DataSets become Ignition datasets. `engUnit`, `engLow`, `engHigh` and
`documentation` metric properties become the tag's.

**Templates become real UDTs.** A template definition in an NBIRTH becomes an Ignition UDT type in the provider's
`_types_` folder, and each instance becomes an instance of it, so a Perspective view can bind to the type rather
than to one site's tags. Members keep their datatypes, units and the history default, and a write to a member
still goes back as a partial template.

An edge that sends instances without definitions gets the old behaviour: the instance flattens into a folder of
tags (`Motor1/Speed`), which always works. So does a path that is **already** a folder — from an older build of
this module, or from a person — because converting it would mean deleting their tags, and their alarms and
history settings with them. The log says so once, and names the tag to delete if you want it rebuilt as a UDT.

**Writes.** Writing a tag sends an NCMD or DCMD typed as the *birth* declared, not as the written value happens
to be. A template member goes out as a partial instance containing only that member. The tag's value doesn't
change on write; it changes when the edge reports it. Writes to an offline node are refused.

## Status

The gateway's web UI gets a **Mantle** page at Diagnostics → Mantle → Sparkplug: every connection, whether it is connected, the
nodes and devices under it with their last birth, and the four numbers worth watching — messages, sequence gaps,
rebirths requested, and decode failures. Gaps and decode failures both mean data was lost between the edge and
the gateway, and both should sit at zero on a healthy link. Each node has a **Request rebirth** button.

The same data is JSON at `GET /data/mantle/status`, and a rebirth is `POST /data/mantle/rebirth/<group>/<edge>`.
Both take any authenticated gateway identity — the web UI's own session, or an
[API token](https://www.docs.inductiveautomation.com/docs/8.3/platform/security/api-keys) for a monitoring
system (`X-Ignition-API-Token`). Read needs READ, a rebirth needs WRITE, because it is a command to the field.

## Build and run

```sh
scripts/dev-up.sh --fresh                     # from the repo root: gateway + broker + module + config
(cd mantle && ./gradlew :gateway:simulate --args="tcp://localhost:1883 Plant Edge1")
scripts/install-module.sh mantle                # after a code change: rebuild and reload
(cd mantle && ./gradlew test)                   # unit tests
(cd integration && go test ./mantle/...)        # integration tests: a Nautilus edge against the live gateway
```

Needs Docker and a JRE to launch Gradle; the JDK 17 toolchain downloads itself. The module is built unsigned, so
a gateway has to run with `-Dignition.allowunsignedmodules=true` until signing is set up.

Code map: `sparkplug/HostState` is the protocol state machine with no MQTT and no Ignition in it, which is what
makes it unit-testable; `mqtt/BrokerConnection` is the session; `tags/ManagedTagSink` is the Ignition side;
`config/BrokerConnectionConfig` is the resource; `MantleGatewayHook` wires them to config changes.

## What has been verified

**By the integration suite** (`../integration`, a real Nautilus edge node against a live 8.3.9 gateway; 16 tests,
~90 s): the tag tree and datatypes, templates as folders, history on by default and off for controls, values in
both directions, a tag write becoming an NCMD the controller acts on, a template member write merging as a partial
template, **a template definition becoming a UDT type and its instance a UDT instance** (and an existing folder
being left alone instead), **a tag customized through `system.tag.configure` (history off, an alarm, documentation) keeping all of it
across a rebirth**, every reported change reaching the historian, `Bad_Stale` with the last value kept on an
orderly death, a killed node, a keepalive timeout (45 s), **store-and-forward data landing in the historian stamped
inside a gateway outage**, and a broker restart. The Nautilus edge's `primary-host` handshake also proves the STATE
certificate against a TCK-passing edge.

**By hand against the Java `EdgeSimulator`**: `engUnit`, `engLow`, `engHigh` and `documentation` properties
becoming tag properties; alias-only DDATA with datatypes stripped; `is_transient` metrics starting with history
off. (The Nautilus edge sends none of these, so the suite can't cover them yet.)

**By unit test only** (`HostStateTest` and `StatusRoutesTest`, 25 cases): reordering, duplicate drop, gap expiry, seq wrap at 256, rebirth
debounce, stale-NDEATH rejection, host-stamped deaths, two-phase births, group filtering, unsigned widening.

**Not yet exercised**: the status page rendering in a browser (its registration, its bundle being served and
both routes refusing anonymous callers are tested, but nobody has looked at the page itself — that needs a
logged-in gateway session); TLS, WebSocket and authenticated brokers; devices (DBIRTH/DDEATH) from a real edge; an
edge with a badly skewed clock; a real Designer session editing a tag (the suite uses `system.tag.configure`, the
scripted equivalent); Ignition Transmission or tentacle as the edge; load.

Known: each gateway boot logs one `Failed to store N points ... historian-name=Core`. Row counts show those points
are stored anyway, and it happens with no Sparkplug traffic at all, so it comes from Ignition restoring persisted
values.

Next steps are in `../ideas.md` under idea 1.
