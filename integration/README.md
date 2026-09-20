# Integration tests

A real Nautilus controller publishing real Sparkplug B, a real broker, and a real Ignition gateway with Mantle
loaded. Nothing is mocked: the tests drive the two ends and check that they agree.

```
 Go test ──/api/tags──▶ Nautilus edge ──Sparkplug B──▶ mosquitto ──▶ Mantle ──▶ Ignition tags + historian
    └────────────────── WebDev test API (system.tag.*, admin login) ◀──────────────────┘
```

```sh
scripts/dev-up.sh                          # once, from the repo root
cd integration
go test ./...                                     # every module's tests; Mantle's take ~90 s
go test -short ./mantle/...                       # skip the three that restart a container or wait out a keepalive, ~12 s
go test -run TestACustomizedTag -v ./mantle/...   # one
```

Shared by every module in the repo: `harness/` (the Go side) and `gateway-project/` (the gateway side). Each
module's tests live in a directory of their own beside them; today that is `mantle/`.

No dependencies beyond the Go standard library. The Nautilus CLI is built from a `nautilus` checkout beside this repo on every run
(set `NAUTILUS_SRC`, or `NAUTILUS_BIN` to use a specific binary): an installed `nautilus` can be months behind the
edge-node code, and a stale edge produces failures that are nobody's bug.

## The pieces

- **`mantle/edge/`**: the test device, a manifest-only Nautilus project. It is built to be asserted against, not to look
  like a plant: every output is a pure function of something a test sets (`LevelSP → LevelFt`,
  `CountEnable → Counter`, `P101.SpeedSP → P101.Speed`), so a test never guesses what a value should be by now.
  `P101` is a UDT, which crosses the wire as a Sparkplug template. It names Mantle as its `primary-host` with
  store-and-forward on. It also runs by hand: `cd mantle/edge && nautilus run`.
- **`harness/edge.go`**: runs a private copy of that project per test, with its own node id and port, and can
  `Stop` it (orderly NDEATH), `Kill` it (the broker publishes its will), or `Freeze`/`Thaw` it (SIGSTOP: a hung
  controller or a dead radio link, where nothing closes the socket).
- **`harness/gateway.go`** and **`gateway-project/integration-api/`**: how tests see Ignition. `integration-api` is an Ignition
  project with one WebDev endpoint over `system.tag.*` (read, write, configure, getConfiguration, browse,
  queryTagHistory, and the gateway's navigation model). It is deliberately not a side door into the module: tests observe the gateway the way a
  script or the Designer does. **It requires an Administrator login**, so it grants nothing a gateway login
  doesn't, but it has no business on a production gateway.

Each test gets its own edge node and deletes that node's tags afterwards, so tests don't share state and can run
while you have an edge of your own up.

## What is covered

| Test | Claim |
|---|---|
| `BirthCreatesTheTagTree` | wire hierarchy, datatypes, a template as a folder of members, `_meta/Online` |
| `HistoryIsOnByDefaultExceptForControls` | history enabled with a provider, including template members; not on `Node Control/*` |
| `AValueChangedAtTheEdgeReachesIgnition` | edge → Ignition |
| `AWriteInIgnitionReachesTheEdgeAndComesBack` | a tag write becomes an NCMD, the controller acts on it, the result returns |
| `AWriteToATemplateMemberLeavesTheOtherMembersAlone` | a member write is a partial template that the edge merges |
| `IntegersAndStringsSurviveTheTrip` | Int64 and String |
| `ACustomizedTagSurvivesARebirth` | history off + an alarm + documentation set through `system.tag.configure` survive a rebirth requested by writing `Node Control/Rebirth` |
| `EveryReportedChangeIsStoredInTheHistorian` | 16 changes 300 ms apart are all stored (Ignition's default keeps about 6) |
| `AnOrderlyShutdownMakesTagsStaleButKeepsTheirValues` | NDEATH → `Bad_Stale` with the last value, `Online` false, writes refused, recovery on return |
| `AKilledNodeIsNoticedThroughItsWill` | death by the broker's will |
| `ASilentNode…KeepaliveRunsOutAndRecovers` | death by keepalive timeout (45 s), then recovery. *Last step skipped: see below.* |
| `DataHeldWhileTheGatewayIsDown…` | the gateway restarts; the edge buffers; its samples land in the historian **stamped inside the outage** |
| `ABrokerRestartIsSurvived` | both ends lose their session and live data resumes |
| `ATemplateBecomesARealUdtType` | a template definition becomes an Ignition UDT type, its instance a `UdtInstance`, members carry values and history |
| `AnExistingFolderIsLeftAloneRatherThanConvertedToAUdt` | the upgrade path: a folder from an older build keeps its shape and its values |
| `TheStatusPageIsRegisteredAndItsRouteIsProtected` | the page is in the gateway's navigation, its bundle is served, and both data routes refuse an anonymous caller |

## What these tests have found

In Mantle, all fixed and all invisible to the unit tests and the Java simulator:

- **Deaths were silently ignored on the tags.** The provider allows backfill, so Ignition files any value older
  than a tag's current one as history and leaves the live value alone. An NDEATH is usually the will, composed at
  connect time (Nautilus's carries no timestamp at all, which Tahu reports as 1970), so `Online = false` never
  showed. Deaths are now stamped with the host's clock, and live values can never be mistaken for backfill; only
  `is_historical` metrics keep a timestamp from the past. An edge whose clock runs behind the gateway's would have
  hit the same thing on every value.
- **Birth values could vanish.** `configureTag` returns before the gateway has built the tag; a value pushed in that
  window is wiped when the tag initializes. Anything that reports only on change (a setpoint, `Online`) then sat at
  null until the next rebirth. Births are now shape first, then values, with a wait in between.
- **History was being thinned to one sample a second** by Ignition's default. New tags now store every reported
  change; the edge's RBE has already decided what matters.

In Nautilus (as of `fffc3a5`), reported rather than fixed, since it is not this repo's code:

- After a keepalive timeout the edge reconnects and rebirths, then publishes **no NDATA at all**, while its own tag
  store keeps changing. Root cause, confirmed with a goroutine dump: the node's one publish goroutine is parked
  forever in an unbounded `Publish(...).Wait()` at `sparkplug/data.go:59`, on a token issued just as the connection
  died. (It is not the primary-host gate, which was the first guess: it reproduces with no primary host.) Closing a
  socket doesn't trigger it, so a broker restart recovers fine. Written up with a standalone repro in
  `../nautilus/docs/handover/2026-09-19-sparkplug-edge-findings.md`. The test skips its last step with this
  explanation; delete the guard in `mantle/mantle_test.go` when it is fixed.
- A manifest seeds every scalar number as a REAL, so a tag the ST declares `DINT` births as a Double.
- The edge does not publish `unit`/`desc` as Sparkplug metric properties, so a host can't pick them up.

A lesson about the tests themselves: a steady ramp is a bad history signal. Ignition compresses analog history, and
a perfectly linear ramp is legitimately stored as its two endpoints, which looks exactly like data loss.
