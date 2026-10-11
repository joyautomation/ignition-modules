# Driving the Designer

`designer.sh` opens the Ignition Designer against this repo's dev gateway in a
container, clicks things in it, and brings back PNGs — so the Perspective side
of a module can be checked without a human taking a screenshot, and so a demo
can be recorded without anything touching the user's desktop.

```sh
scripts/designer/designer.sh status
scripts/designer/designer.sh up --project Plant --perspective
scripts/designer/designer.sh shot --name palette --crop 1700,200,800,900
scripts/designer/designer.sh do 'click 212 779' 'sleep 1' --shot
```

## Read the frames. Every time.

This is the rig's own hardest-won lesson, and it is the whole reason the tool
is shaped this way: **a script types blind.** It cannot tell that a menu
reordered under it, that a dialog opened late, or that the row it counted to
moved — and it will report success either way. `do` echoes every verb and
`--shot` is one word, so that looking at the result is the default.

What the tool is good at is *reading*. Capture a crop and actually look at it;
do not conclude anything from the fact that a click did not error.

## The pieces

| | |
|---|---|
| **container** | `im12-designer`, **not ours** — it belongs to the content repo (`content/assets/capture/im12/designer-rig.md`), built 2026-10-09 for the Mantle launch video: a `nautilus-vscode-rig` container with Xvfb on `:99`, openbox, xdotool, ffmpeg, and the Designer Launcher in `~dev/designerlauncher`. Override with `DESIGNER_CONTAINER`. That rig makes one scripted continuous video take; this makes stills on demand. |
| **gateway** | this repo's dev stack on the host's `:8088`, which the container reaches at incusbr0's gateway address (`10.154.92.1:8088`), derived at runtime. Override with `DESIGNER_GATEWAY`. |
| **`_rig.sh`** | the container half, pushed on every call — edit it here and the next call has it. Implements `geom`, `shot` and the verbs. |
| **shots** | `/tmp/designer-shots`, or `DESIGNER_SHOT_DIR`. |

## Coordinates

Every verb takes a **client** coordinate: `(0,0)` is the top-left of the
Designer's client area, resolved per call from `xwininfo`. im12's
`designer-take.sh` uses *root* coordinates measured with openbox placing the
client at `(1,20)`, and says so — "a Designer that opens elsewhere or at
another scale needs them re-measured". Client coordinates remove that half of
the problem. To port a number from the im12 scripts, subtract `(1, 20)`.

What they do **not** remove: the Designer lays its panels out to fit, so a
coordinate is only meaningful at the size it was measured at. Everything here
assumes **2560x1440 at UI scale 2** (`-Dsun.java2d.uiScale=2`, which is what
makes the Tag Browser legible at that size). `do` asserts the window size
before it clicks anything, because the alternative is clicking confidently in
the wrong place.

## Verbs

`mv x y` · `click x y [button]` · `dclick x y` · `rclick x y` ·
`drag x y to-x [to-y]` · `scroll x y [n]` (negative scrolls up) ·
`type TEXT` · `retype TEXT` (select-all, type, Enter — for a field) ·
`key KEYS` · `sleep N` · `focus`

The pointer travels rather than teleports (~0.4 s), which Swing hover states
and anyone watching a recording both want.

## `up` is conservative on purpose

An already-running Designer is left alone unless `--relaunch` is passed: the
window may be holding the exact state the next shot is for. `--perspective`
additionally opens the Perspective workspace.

Login is typed by keyboard (`admin`, Tab, `password`, Enter) rather than by
clicking two measured pixels, so it survives the dialog landing anywhere.

### There is no `--layout`, and that is the finding

im12's `designer-setup.sh` arranges the Designer for its video: Perspective
workspace, Project Browser closed, Tag Browser widened to ~1300 px, Alarm
Metrics off, `Sparkplug` provider. Ported here verbatim (its root coordinates
minus `(1,20)`) and run on 2026-10-11, it **selected Alarm Notification
Pipelines, left the Project Browser open, and left the Tag Browser on
`default`** — and printed every one of its fourteen verbs as fine. Nothing
errored. The only thing that caught it was looking at the frame.

So only the one step that was re-measured survives, as `--perspective`. The
rest is not reproduced here rather than reproduced wrong. Arrange by hand with
`do ... --shot`, a frame at a time.

One concrete difference found while measuring: im12 opens the Perspective
workspace with a **single** click on the Project Browser node. A single click
only *selects* it; the workspace needs a **double** click.

## Traps

- **A gateway restart or a `--fresh` loses Perspective views and page-config
  entries**, which are project resources. The Designer will happily show you an
  empty project and nothing will say why.
- **The dev gateway's trial is two hours** and takes WebDev and the historian
  with it. The Designer's status bar shows the clock — it is in every frame, so
  read it.
- **`pkill -f` self-matches**; `up` kills the Designer with `pkill -u dev -x java`.
- **Do not open a browser on `:99`** if anything is recording there — it lands
  on top of the Designer. im12 keeps browser takes on `:98` for this reason.
- **A `nohup ... &` spawned through `incus exec` does not survive**: the exec
  session is torn down a moment later and takes the JVM with it — no java, no
  window, an empty `launcher.out`, nothing in any log. The launch therefore
  lives in `_rig.sh` and blocks until the Designer is up, which is also why
  im12's `designer-setup.sh` works where the first host-side version did not.
