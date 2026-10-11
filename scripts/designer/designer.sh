#!/usr/bin/env bash
# designer.sh — drive the Ignition Designer in a container and read the
# result, so a module's Perspective side can be checked without a human
# taking a screenshot.
#
#   scripts/designer/designer.sh status
#   scripts/designer/designer.sh up [--project Plant] [--relaunch] [--perspective]
#   scripts/designer/designer.sh shot [--name tag-browser] [--crop 0,0,700,900] [--width 1280]
#   scripts/designer/designer.sh do 'click 500 300' 'sleep 1' 'retype 20' --shot
#
# The rig it drives is NOT ours: it is `im12-designer` from the content repo
# (assets/capture/im12/designer-rig.md), built 2026-10-09 for the Mantle
# launch video -- a `nautilus-vscode-rig` container with Xvfb, openbox,
# xdotool, ffmpeg and the Designer Launcher in it. That rig makes one scripted
# continuous video take; this makes stills on demand against this repo's dev
# stack. Nothing here touches the user's desktop.
#
# READ THE FRAMES. EVERY TIME. The rig's own hardest-won lesson: a tape types
# blind, cannot tell that a menu reordered under it, and will happily record
# the wrong thing at full resolution. `do` prints each verb and `--shot`
# exists so that the frame after an action is the default, not an afterthought.
set -euo pipefail

CONTAINER=${DESIGNER_CONTAINER:-im12-designer}
RIG_DISPLAY=${DESIGNER_DISPLAY:-:99}
SHOT_DIR=${DESIGNER_SHOT_DIR:-/tmp/designer-shots}
PROJECT=${DESIGNER_PROJECT:-Plant}
# The dev gateway as the CONTAINER sees it: this repo's stack listens on the
# host's :8088, which is incusbr0's gateway address from inside.
HOST_IP=$(ip -4 -o addr show "${DESIGNER_BRIDGE:-incusbr0}" 2>/dev/null | awk '{print $4}' | cut -d/ -f1)
GATEWAY=${DESIGNER_GATEWAY:-http://${HOST_IP:-10.154.92.1}:8088}
CREDS=${DESIGNER_CREDS:-admin:password}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

die() { echo "designer.sh: $*" >&2; exit 1; }
dexec() { incus exec "$CONTAINER" -- su - dev -c "$1"; }

# _rig.sh is pushed on every call, so editing it here is all it takes. It is
# ~6 kB; the push costs less than the first ffmpeg frame.
push_rig() {
    incus file push --quiet --mode 0755 "$HERE/_rig.sh" "$CONTAINER/home/dev/_rig.sh"
    incus exec "$CONTAINER" -- chown dev:dev /home/dev/_rig.sh
}

rig() { push_rig; dexec "RIG_DISPLAY=$RIG_DISPLAY /home/dev/_rig.sh $*"; }
# the no-push form, for the second call onward in one subcommand
rig_fast() { dexec "RIG_DISPLAY=$RIG_DISPLAY /home/dev/_rig.sh $*"; }

require_container() {
    local state
    state=$(incus list "$CONTAINER" --format csv -c s 2>/dev/null || true)
    [[ -n $state ]] || die "no container '$CONTAINER'. See the content repo's assets/capture/im12/designer-rig.md for how it was built."
    if [[ $state != RUNNING ]]; then
        echo "starting $CONTAINER..."
        incus start "$CONTAINER"
        sleep 8
    fi
}

# --- status ----------------------------------------------------------------
cmd_status() {
    require_container
    echo "container : $CONTAINER ($(incus list "$CONTAINER" --format csv -c s))"
    echo "display   : $RIG_DISPLAY"
    printf 'gateway   : %s (from container: ' "$GATEWAY"
    dexec "curl -s -o /dev/null -m 5 -w '%{http_code}' $GATEWAY/" || true
    echo ")"
    printf 'designer  : '
    if ! rig geom 2>/dev/null; then
        echo "not running -- 'designer.sh up' to launch it"
        return 0
    fi
}

# --- up --------------------------------------------------------------------
# Idempotent and deliberately conservative: an already-running Designer is
# left exactly as it is unless --relaunch is passed, because the window may be
# holding state (an open view, a half-finished edit) that is the whole point
# of the next shot. --perspective also opens the Perspective workspace.
cmd_up() {
    local relaunch= layout=
    while [[ $# -gt 0 ]]; do
        case $1 in
            --project) PROJECT=$2; shift 2 ;;
            --gateway) GATEWAY=$2; shift 2 ;;
            --relaunch) relaunch=1; shift ;;
            --perspective) layout=1; shift ;;
            *) die "up: unknown option $1" ;;
        esac
    done
    require_container
    push_rig

    if [[ -z $relaunch ]] && rig_fast geom >/dev/null 2>&1; then
        echo "Designer already up: $(rig_fast geom)"
        return 0
    fi

    # The launch blocks inside the container: see cmd_launch in _rig.sh for
    # why a host-side `nohup ... &` through `incus exec` does not survive.
    echo "launching the Designer (this takes ~40 s)..."
    rig_fast launch "'$GATEWAY'" "'$PROJECT'" "'${CREDS%%:*}'" "'${CREDS#*:}'" "'${relaunch:-}'" \
        || die "launch failed -- 'designer.sh shot --root --name whats-on-screen' to see what is there"
    echo "up: $(rig_fast geom)"
    [[ -n $layout ]] && do_perspective
    return 0
}

# Open the Perspective workspace. This is ALL that is left of the arrangement
# ported from im12's designer-setup.sh, because the rest of it did not
# reproduce: run verbatim on 2026-10-11 it selected Alarm Notification
# Pipelines, left the Project Browser open and left the Tag Browser on
# `default` -- and reported every verb as fine. The rest of that sequence
# (close the Project Browser, widen the Tag Browser, Alarm Metrics off,
# Sparkplug provider) is for the im12 video, not for checking a module, so it
# is not reproduced here rather than reproduced wrong. Arrange by hand with
# `do ... --shot`, a frame at a time, which is the only way that is honest.
#
# im12 uses a single click on this node; a single click only SELECTS it. The
# workspace needs a double click (measured 2026-10-11).
do_perspective() {
    echo "opening the Perspective workspace..."
    rig_fast do "'focus'" "'dclick 164 397'" "'sleep 3'"
    cmd_shot --name perspective --crop 0,80,700,420
    echo "^ read that frame: the Perspective node expanded, its workspace in the right pane."
}

# --- shot ------------------------------------------------------------------
cmd_shot() {
    local name crop= width= root= out=$SHOT_DIR
    name="shot-$(date +%H%M%S)"
    while [[ $# -gt 0 ]]; do
        case $1 in
            --name)  name=$2; shift 2 ;;
            --crop)  crop=$2; shift 2 ;;
            --width) width=$2; shift 2 ;;
            --out)   out=$2; shift 2 ;;
            --root)  root=1; shift ;;
            *) die "shot: unknown option $1" ;;
        esac
    done
    require_container
    mkdir -p "$out"
    local cpath=/tmp/rig-shot.png
    rig shot "$cpath" "${crop:-''}" "${width:-''}" "${root:-''}" >/dev/null
    local dest="$out/$name.png"
    incus file pull --quiet "$CONTAINER$cpath" "$dest"
    echo "$dest"
}

# --- do --------------------------------------------------------------------
cmd_do() {
    local verbs=() shot= name= crop= width=
    while [[ $# -gt 0 ]]; do
        case $1 in
            --shot)  shot=1; shift ;;
            --name)  name=$2; shift 2 ;;
            --crop)  crop=$2; shot=1; shift 2 ;;
            --width) width=$2; shot=1; shift 2 ;;
            *) verbs+=("$1"); shift ;;
        esac
    done
    [[ ${#verbs[@]} -gt 0 ]] || die "do: give at least one verb, e.g. 'click 500 300'"
    require_container
    push_rig
    # The Designer must be the geometry the caller's coordinates were measured
    # at, or every one of them is wrong by an unknown amount.
    local g; g=$(rig_fast geom) || die "no Designer -- run 'designer.sh up'"
    [[ $g == *"w=2560 h=1440"* ]] || die "Designer is not 2560x1440 ($g); coordinates would be wrong. 'designer.sh up --relaunch'"
    local q=() v
    for v in "${verbs[@]}"; do q+=("'$v'"); done
    rig_fast do "${q[@]}"
    [[ -n $shot ]] && cmd_shot ${name:+--name "$name"} ${crop:+--crop "$crop"} ${width:+--width "$width"}
    return 0
}

case ${1:-} in
    status) shift; cmd_status "$@" ;;
    up)     shift; cmd_up     "$@" ;;
    shot)   shift; cmd_shot   "$@" ;;
    do)     shift; cmd_do     "$@" ;;
    ''|-h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) die "unknown subcommand '$1' (status|up|shot|do)" ;;
esac
