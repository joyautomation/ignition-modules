#!/usr/bin/env bash
# _rig.sh — the container half. Runs INSIDE the Designer rig container as
# `dev`; designer.sh on the host pushes it and calls it. Never run by hand.
#
# Every coordinate a verb takes is a CLIENT coordinate: (0,0) is the top-left
# of the Designer's client area, wherever the window manager has put it. The
# offset is resolved per call from xwininfo, which is the one thing im12's
# designer-take.sh could not do (its numbers are root coords measured with
# openbox placing the client at (1,20), so a Designer that opened elsewhere
# needed all of them re-measured).
set -euo pipefail
export DISPLAY=${RIG_DISPLAY:-:99}

# --- where the Designer is -------------------------------------------------
# Sets WID AX AY AW AH. The window size still matters -- the Designer lays its
# panels out to fit, so coordinates measured at 2560x1440 only hold at
# 2560x1440 -- so callers that click assert on it. Finding it does not.
find_designer() {
    WID=$(xdotool search --onlyvisible --name "Ignition Designer" 2>/dev/null | head -1)
    [[ -n ${WID:-} ]] || { echo "no Designer window on $DISPLAY" >&2; return 1; }
    eval "$(xwininfo -id "$WID" | awk '
        /Absolute upper-left X/{print "AX="$NF}
        /Absolute upper-left Y/{print "AY="$NF}
        /^  Width/{print "AW="$NF}
        /^  Height/{print "AH="$NF}')"
}

cmd_geom() {
    find_designer
    echo "wid=$WID x=$AX y=$AY w=$AW h=$AH title=$(xdotool getwindowname "$WID")"
}

# --- stills ----------------------------------------------------------------
# x11grab rather than `import`/`xwd` for the same reason im12 uses it: it is
# already in the image, and it takes the pointer with -draw_mouse.
cmd_shot() {
    local out=$1 crop=${2:-} width=${3:-} root=${4:-}
    local size pos
    if [[ -n $root ]]; then
        size=$(xdotool getdisplaygeometry | tr ' ' x); pos="+0,0"
    else
        find_designer; size="${AW}x${AH}"; pos="+$AX,$AY"
    fi
    local vf=()
    if [[ -n $crop ]]; then
        IFS=, read -r cx cy cw ch <<<"$crop"
        vf+=("crop=$cw:$ch:$cx:$cy")
    fi
    [[ -n $width ]] && vf+=("scale=$width:-2:flags=lanczos")
    local args=(-hide_banner -loglevel error -f x11grab -draw_mouse 1
                -video_size "$size" -i "$DISPLAY$pos" -frames:v 1 -y)
    [[ ${#vf[@]} -gt 0 ]] && args+=(-vf "$(IFS=,; echo "${vf[*]}")")
    ffmpeg "${args[@]}" "$out"
    echo "$out"
}

# --- verbs -----------------------------------------------------------------
# A pointer that travels rather than teleports: Swing hover states (and a
# viewer watching a recording) both want the move to be real.
v_mv() {
    find_designer
    local x=$(( $1 + AX )) y=$(( $2 + AY )) cx cy i steps=${MV_STEPS:-16}
    eval "$(xdotool getmouselocation --shell | sed 's/^/c/')"
    cx=$cX; cy=$cY
    for ((i=1; i<=steps; i++)); do
        xdotool mousemove $((cx + (x-cx)*i/steps)) $((cy + (y-cy)*i/steps))
        sleep 0.022
    done
}
v_click()  { v_mv "$1" "$2"; sleep 0.25; xdotool click "${3:-1}"; }
v_dclick() { v_mv "$1" "$2"; sleep 0.25; xdotool click --repeat 2 --delay 110 1; }
v_rclick() { v_mv "$1" "$2"; sleep 0.25; xdotool click 3; }
v_drag()   { # x y to-x [to-y]
    v_mv "$1" "$2"; sleep 0.3; xdotool mousedown 1; sleep 0.3
    local tx=$(( $3 + AX )) ty=$(( ${4:-$2} + AY )) i steps=20 fx fy
    eval "$(xdotool getmouselocation --shell | sed 's/^/c/')"; fx=$cX; fy=$cY
    for ((i=1; i<=steps; i++)); do
        xdotool mousemove $((fx + (tx-fx)*i/steps)) $((fy + (ty-fy)*i/steps)); sleep 0.03
    done
    sleep 0.2; xdotool mouseup 1; }
v_type()   { xdotool type --delay "${TYPE_DELAY:-70}" -- "$1"; }
v_retype() { xdotool key ctrl+a; sleep 0.15; v_type "$1"; sleep 0.3; xdotool key Return; }
v_key()    { xdotool key -- "$@"; }
v_sleep()  { sleep "$1"; }
v_focus()  { find_designer; xdotool windowactivate "$WID"; sleep 0.4; }
v_scroll() { v_mv "$1" "$2"; local n=${3:-3} b=4; (( n < 0 )) && { b=5; n=$(( -n )); }
             xdotool click --repeat "$n" --delay 80 "$b"; }

# `do` takes one verb per argument, each a whole line -- `designer.sh do
# 'click 500 300' 'sleep 1' 'type hello'`. Quoting survives, and a verb that
# is not in the table is an error rather than a silent no-op.
cmd_do() {
    local line verb
    for line in "$@"; do
        # shellcheck disable=SC2086
        set -- $line
        verb=$1; shift
        declare -F "v_$verb" >/dev/null || { echo "unknown verb: $verb" >&2; return 2; }
        echo "  $verb $*" >&2
        # text verbs take the rest of the line as ONE argument: splitting
        # `type High Level` on whitespace would type only "High".
        case $verb in
            type|retype) "v_$verb" "$*" ;;
            *)           "v_$verb" "$@" ;;
        esac
    done
}

# --- launch ----------------------------------------------------------------
# The launch has to happen HERE, not on the host, and has to block: a
# `nohup ... &` spawned through `incus exec` dies with the exec session a
# moment later (no java, no window, an empty launcher.out -- paid for
# 2026-10-11). Staying parented to a script that waits lets the JVM get far
# enough to reparent to init, which is also why im12's designer-setup.sh
# works where the host-side version did not.
cmd_launch() {
    local gw=$1 project=$2 user=$3 pass=$4 relayout=${5:-}
    pkill -u dev -x java 2>/dev/null || true; sleep 3
    [[ -n $relayout ]] && find ~/.ignition -maxdepth 1 -name '*.layout' -delete
    local link="designer://${gw#http*://}/${project}?insecure=true"
    echo "  link: $link"
    ( cd ~/designerlauncher && JAVA_TOOL_OPTIONS="-Dsun.java2d.uiScale=2" \
        setsid nohup ./designerlauncher.sh "$link" >/tmp/launcher.out 2>&1 & )

    local i
    for i in $(seq 180); do
        xdotool search --onlyvisible --name "^Login - Ignition" >/dev/null 2>&1 && break
        sleep 1
    done
    if ! xdotool search --onlyvisible --name "^Login - Ignition" >/dev/null 2>&1; then
        # Already logged in (a saved session) is a success, not a failure.
        find_designer 2>/dev/null && { echo "  no login dialog; the Designer is already up"; }
        find_designer 2>/dev/null || { echo "no login dialog after 180 s" >&2
                                       tail -20 /tmp/launcher.out >&2 || true; return 1; }
    else
        echo "  login dialog up"
        sleep 2
        xdotool search --onlyvisible --name "^Login - Ignition" | head -1 | xargs -r xdotool windowactivate --sync
        sleep 1
        # By keyboard: im12 clicks two measured pixels, which only hold while
        # the dialog lands in the same place at the same scale.
        xdotool type --delay 60 -- "$user"; sleep 0.4
        xdotool key Tab; sleep 0.4
        xdotool type --delay 60 -- "$pass"; sleep 0.4
        xdotool key Return
    fi

    for i in $(seq 180); do find_designer 2>/dev/null && break; sleep 1; done
    find_designer || { echo "the Designer never opened" >&2; return 1; }
    sleep 8
    # Size it to the geometry every measured coordinate assumes.
    wmctrl -i -r "$WID" -b remove,maximized_vert,maximized_horz 2>/dev/null || true
    xdotool windowsize "$WID" 2560 1440; xdotool windowmove "$WID" 0 0
    sleep 2
    cmd_geom
}

case ${1:-} in
    geom)   shift; cmd_geom   "$@" ;;
    launch) shift; cmd_launch "$@" ;;
    shot) shift; cmd_shot "$@" ;;
    do)   shift; cmd_do   "$@" ;;
    *)    echo "usage: _rig.sh {geom|shot|do} ..." >&2; exit 2 ;;
esac
