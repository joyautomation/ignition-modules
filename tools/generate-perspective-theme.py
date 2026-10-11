#!/usr/bin/env python3
"""Generates Perspective themes from the Nautilus HMI's design tokens.

Perspective themes are config resources — a folder of CSS under
`config/resources/core/com.inductiveautomation.perspective/themes/<name>/` — so a theme is a data artefact,
not code. That is why this is a generator and not a module: the module only has to deliver the output.

The approach is to IMPORT Perspective's own base theme and override, rather than restate it. Its light and
dark bases define 118 variables; Nautilus has opinions about perhaps twenty-five of them. Overriding that
subset keeps every Perspective component working (they reference variables we never think about) while the
colours that carry meaning become Nautilus's. `dark-cool` is built exactly this way and is the model here.

  tools/generate-perspective-theme.py <hmi/src/lib/theme.css> <output-dir>
"""
import math
import re
import sys
from pathlib import Path

# Nautilus token -> the Perspective variables it should drive.
#
# Perspective's names are structural ("the nested container's background"); Nautilus's are semantic ("the
# surface a card sits on"). This table is where that translation is decided, and it is the whole design
# content of the theme pack — everything else is plumbing.
MAPPING = {
    # ── ground, surfaces, edges ──
    "bg":            ["containerRoot"],
    "surface":       ["container", "input", "contextBackground"],
    "surface-2":     ["containerNested"],
    "border":        ["border", "containerBorder"],
    "ink":           ["label", "icon"],
    "muted":         ["label--disabled", "icon--disabled", "border--disabled", "input--disabled"],

    # ── the one brand colour, and its states ──
    "accent":        ["callToAction", "callToActionHighlight", "icon--selected"],
    "primary-hover": ["callToAction--hover"],
    "primary-bg":    ["callToAction--active", "callToAction--activeAlt"],

    # ── status. In Nautilus these are RESERVED for process state; Perspective uses the same four for
    #    feedback, which is a near-enough match that sharing them is right rather than lazy.
    "crit":          ["error", "symbolFill--faulted", "symbolStroke--faulted"],
    "warn":          ["warning", "warningSecondary"],
    "good":          ["success", "symbolFill--running", "symbolStroke--running", "indicator"],
    "s1":            ["info", "infoSecondary"],

    # ── P&ID symbols and pipework. Perspective ships its own tank/pump/valve symbols; these make them obey
    #    the same process-state palette as the Nautilus ones, so a screen mixing both is not two languages.
    "muted:stopped": ["symbolFill--stopped", "symbolStroke--stopped", "indicatorOff"],
    "surface-2:sym": ["symbolFill--default", "pipePrimaryFill"],
    # NOT --axis. Measured 2026-10-10: --n-axis #383835 is 1.65:1 against --bg and 1.34:1 against
    # --surface-2, where WCAG 1.4.11 wants 3:1 for a graphical object. It is a correct CHART AXIS -- a
    # hairline that should recede behind the data -- and a bad EQUIPMENT OUTLINE, which has to be findable
    # on a plant screen at a glance. Same token, two jobs, one of them failing. --n-stroke below is derived
    # for the second job; Nautilus's own components should grow the same token so a pipe reads the same in
    # both products.
    "stroke":        ["symbolStroke--default", "pipeStroke", "pipeSecondaryFill"],
    "accent:pipe":   ["pipeSelectStroke"],

    # ── charts: --qual-1..10, the CATEGORICAL palette (one colour per series, no order implied).
    #    Perspective's own is ten unrelated hues; Nautilus has nine named ones, and they are the nine a
    #    plant already reads as meaning something. Assigned so that ADJACENT slots differ in hue, because
    #    a chart with three series uses 1, 2, 3 and those are the three that must not be confusable.
    #    The tenth is --muted on purpose: a tenth series is an "other", and grey says so.
    "s1:q1":         ["qual-1"],   # blue    — Nautilus's primary series
    "s3:q2":         ["qual-2"],   # aqua
    "s2:q3":         ["qual-3"],   # yellow
    "s5:q4":         ["qual-4"],   # violet
    "good:q5":       ["qual-5"],   # green
    "serious:q6":    ["qual-6"],   # soft orange
    "accent:q7":     ["qual-7"],   # cyan
    "crit:q8":       ["qual-8"],   # red
    "warn:q9":       ["qual-9"],   # amber
    "muted:q10":     ["qual-10"],  # grey — the "other" slot, and the bronze this replaces
}

# --div-1..16 (diverging) and --boxShadow1..5 are deliberately NOT mapped. A diverging scale needs two
# poles and a neutral midpoint, and Nautilus defines no such pair — inventing one here would be this
# generator having a design opinion the palette does not. The shadows are neutral black rgba, which is
# hue-agnostic and correct over any background, so retinting them to the house hue would only make them
# worse. Both keep Perspective's values.

# Nautilus has no 10–100 neutral ramp and Perspective leans on one hard (--neutral-90 is referenced 60 times
# in the component CSS, --neutral-10 48 times). Inventing ten hex values by hand would drift from the palette
# the moment it changed, and mixing --bg toward --ink — the first attempt here — gives even numeric steps
# that are not even *perceptual* steps, so the middle of the ramp collapses.
#
# So: Tailwind's ramp structure, Nautilus's hue. Tailwind v4 publishes its greys in OKLCH, which is a
# perceptual space, and its lightness/chroma curve is well-tuned and widely sighted. Nautilus's own greys
# measure at hue ~105 (olive) — not stone (34–74), not zinc (286), not neutral (achromatic) — and its
# lightness steps already land close to stone's, `--surface` at 21.7% against stone-900's 21.6%. Borrowing
# the curve and retinting it to the house hue gives a ramp that is perceptually even AND in the family.
#
# TAILWIND_GREY is (lightness %, chroma) per step, from Tailwind v4's stone ramp, darkest first.
TAILWIND_GREY = [
    (14.7, 0.004), (21.6, 0.006), (26.8, 0.007), (37.4, 0.010), (44.4, 0.011),
    (55.3, 0.013), (70.9, 0.010), (86.9, 0.005), (92.3, 0.003), (98.5, 0.001),
]


def read_tokens(css: str, selector: str) -> dict[str, str]:
    """The custom properties declared in the first block matching `selector`."""
    start = css.find(selector)
    if start < 0:
        raise SystemExit(f"no {selector} block in the theme")
    block = css[css.index("{", start) + 1:css.index("}", start)]
    return {m.group(1): m.group(2).strip()
            for m in re.finditer(r"--([a-zA-Z0-9-]+)\s*:\s*([^;]+);", block)}


def oklch_to_hex(lightness: float, chroma: float, hue: float) -> str:
    """OKLCH -> sRGB hex.

    Emitted as hex rather than as an oklch() literal on purpose: the Designer renders Perspective in an
    embedded browser whose version is not ours to choose, and oklch() needs Chromium 111. Hex costs nothing
    and works everywhere.
    """
    L, a = lightness / 100, chroma * math.cos(math.radians(hue))
    b = chroma * math.sin(math.radians(hue))
    l_, m_, s_ = (L + 0.3963377774 * a + 0.2158037573 * b,
                  L - 0.1055613458 * a - 0.0638541728 * b,
                  L - 0.0894841775 * a - 1.2914855480 * b)
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    rgb = (+4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
           -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
           -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    out = []
    for c in rgb:
        c = 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
        out.append(max(0, min(255, round(c * 255))))
    return "#%02x%02x%02x" % tuple(out)


def house_hue(tokens: dict[str, str]) -> float:
    """The hue Nautilus's own greys sit at, measured rather than assumed.

    Taken from the mid-tones: --bg and --ink are pure enough that their hue is meaningless noise.
    """
    hues = []
    for name in ("surface-2", "grid", "axis", "muted", "ink-2"):
        value = tokens.get(name, "")
        if not re.fullmatch(r"#[0-9a-fA-F]{6}", value.strip()):
            continue
        _, chroma, h = hex_to_oklch(value)
        if chroma > 0.002:
            hues.append(h)
    return sum(hues) / len(hues) if hues else 0.0


def hex_to_oklch(value: str) -> tuple[float, float, float]:
    """#rrggbb to (lightness %, chroma, hue degrees). The inverse of oklch_to_hex."""
    h = value.strip().lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    r, g, b = ((c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4) for c in (r, g, b))
    l_ = (0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b) ** (1 / 3)
    m_ = (0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b) ** (1 / 3)
    s_ = (0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b) ** (1 / 3)
    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    return L * 100, math.hypot(A, B), math.degrees(math.atan2(B, A)) % 360


def neutrals(dark: bool, hue: float) -> list[str]:
    """Perspective's --neutral-10..100, as hex.

    The numbering runs the OPPOSITE way in each, which is verified against the shipped bases rather than
    assumed — the first version of this got it backwards both ways round:

        light/variables.css   --neutral-10: #FAFAFA (lightest) ... --neutral-100: #161616 (darkest)
        dark/variables.css    --neutral-10: #161616 (darkest)  ... --neutral-100: #FAFAFA (lightest)

    TAILWIND_GREY is darkest-first, so a dark theme takes it as it comes and a light theme reverses it.
    """
    ramp = [oklch_to_hex(L, C, hue) for L, C in TAILWIND_GREY]
    return ramp if dark else list(reversed(ramp))


# --seq-1..6 is Perspective's SEQUENTIAL palette: one hue, stepping away from the background, for a scale
# where the order means something (a heatmap, a gradient fill). Perspective ships purple. The same trick
# the neutrals use applies — borrow the lightness/chroma curve, which is well-tuned, and retint it to
# Nautilus's accent — and the same inversion trap applies too, verified against the shipped bases:
#
#     dark/variables.css    --seq-1 #9656D6 (L 59) ... --seq-6 #F5F0FA (L 96)   lightens
#     light/variables.css   --seq-1 #AE74E8 (L 67) ... --seq-6 #371C52 (L 29)   darkens
#
# Measured from those two ramps as (lightness %, chroma), in slot order 1..6.
SEQ_CURVE = {
    True:  [(58.6, 0.191), (66.7, 0.173), (76.2, 0.129), (84.0, 0.087), (90.9, 0.048), (96.2, 0.014)],
    False: [(66.7, 0.173), (59.1, 0.187), (50.6, 0.194), (43.2, 0.176), (35.5, 0.137), (29.3, 0.096)],
}


def sequential(dark: bool, accent: str) -> list[str]:
    """Perspective's --seq-1..6 at the accent's hue.

    Chroma is scaled so the ramp's most saturated step lands on the accent's OWN chroma rather than the
    purple's. Purple reaches 0.194 at L50; cyan cannot, and asking for it just clips to the edge of sRGB
    and flattens the top of the ramp into one colour.
    """
    _, accent_chroma, hue = hex_to_oklch(accent)
    curve = SEQ_CURVE[dark]
    scale = accent_chroma / max(C for _, C in curve)
    return [oklch_to_hex(L, C * scale, hue) for L, C in curve]


def build(tokens: dict[str, str], dark: bool) -> str:
    lines = [
        "/* Generated by tools/generate-perspective-theme.py from the Nautilus HMI's tokens.",
        " * Do not edit: change hmi/src/lib/theme.css and regenerate.",
        " *",
        " * Perspective's own base is imported first, so every variable this file does not mention keeps",
        " * working. Only the ones Nautilus has an opinion about are overridden.",
        " */",
        f'@import "../{"dark" if dark else "light"}/variables.css";',
        "",
        ":root {",
        "    /* the Nautilus tokens themselves, so the mapping below reads as the mapping it is */",
    ]
    for name, value in sorted(tokens.items()):
        lines.append(f"    --n-{name}: {value};")

    # The SAME tokens under their bare Nautilus names. The --n- prefix keeps the mapping below readable,
    # but the HMI's own components ask for var(--s1), var(--accent) and so on — so a component dropped into
    # Perspective found those undefined and fell back to its hard-coded defaults, with nothing logged and
    # nothing on screen to say why (measured 2026-09-28: --n-accent resolved inside the shadow root, --s1
    # did not). Publishing both spellings costs nothing and means a component needs no knowledge of
    # Perspective at all.
    #
    # The only name that collides with Perspective's own vocabulary is --border, and that one is already
    # deliberately overridden in the mapping below, to the same value.
    # A stroke colour Nautilus does not yet define. Derived at the house hue so it belongs to the palette,
    # at the lightness that first clears 3:1 against BOTH the page background and a panel surface -- a pipe
    # can sit on either, so passing against only one is not passing.
    lines += ["", f"    /* equipment outlines: {'lighter' if dark else 'darker'} than --axis, to clear "
                  f"WCAG 1.4.11's 3:1 for graphical objects */",
              f"    --n-stroke: {oklch_to_hex(55.0 if dark else 48.0, 0.008, house_hue(tokens))};"]

    lines += ["", "    /* the same tokens under the bare names the Nautilus components actually ask for */"]
    for name, value in sorted(tokens.items()):
        lines.append(f"    --{name}: var(--n-{name});")
    lines.append("    --stroke: var(--n-stroke);")

    hue = house_hue(tokens)
    lines += ["", f"    /* neutral ramp: Tailwind v4's stone curve, retinted to the house hue ({hue:.0f}deg) */"]
    for i, value in enumerate(neutrals(dark, hue)):
        lines.append(f"    --neutral-{(i + 1) * 10}: {value};")

    accent = tokens.get("accent", "")
    if re.fullmatch(r"#[0-9a-fA-F]{6}", accent.strip()):
        _, _, accent_hue = hex_to_oklch(accent)
        lines += ["", f"    /* sequential ramp: Perspective's own curve at the accent hue ({accent_hue:.0f}deg) */"]
        for i, value in enumerate(sequential(dark, accent)):
            lines.append(f"    --seq-{i + 1}: {value};")

    lines += ["", "    /* Nautilus tokens mapped onto the names Perspective's components actually read */"]
    # Tokens this generator derives rather than reads from theme.css. They are emitted above and are
    # legitimate mapping sources, so the "no such token" guard must not reject them.
    derived = {"stroke"}
    for source, targets in MAPPING.items():
        token = source.split(":")[0]
        if token not in tokens and token not in derived:
            print(f"  warning: no --{token} in the theme; skipping {targets}", file=sys.stderr)
            continue
        for target in targets:
            lines.append(f"    --{target}: var(--n-{token});")

    lines += ["", "    /* shape and type */",
              "    --borderRadius: var(--n-radius, 4px);",
              "    --borderRadiusInput: var(--n-radius, 4px);",
              "}", ""]
    return "\n".join(lines)


def write_theme(out: Path, name: str, dark: bool, tokens: dict[str, str]) -> None:
    d = out / name
    d.mkdir(parents=True, exist_ok=True)
    base = "dark" if dark else "light"

    (d / "variables.css").write_text(build(tokens, dark))
    # Mirrors the built-in dark-cool: our variables first, then Perspective's own layers on top.
    (d / "index.css").write_text(
        '@import "./variables.css";\n'
        '@import "../light/fonts.css";\n'
        f'@import "../{base}/globals.css";\n'
        '@import "../light/app/index.css";\n'
        '@import "../light/common/index.css";\n'
        '@import "../light/designer/index.css";\n'
        '@import "../light/palette/index.css";\n'
        + (f'@import "../{base}/palette/index.css";\n' if dark else "")
    )
    (d / "config.json").write_text('{\n  "entrypoint": "index.css",\n  "isPrivate": false\n}\n')
    (d / "resource.json").write_text(
        '{\n'
        '  "scope": "G",\n'
        f'  "description": "Nautilus {"dark" if dark else "light"} theme for Perspective.",\n'
        '  "version": 1,\n'
        '  "restricted": false,\n'
        '  "overridable": true,\n'
        '  "files": [\n    "config.json",\n    "index.css",\n    "variables.css"\n  ],\n'
        '  "attributes": {}\n'
        '}\n'
    )
    print(f"  {name}: {len((d / 'variables.css').read_text().splitlines())} lines")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    css = Path(sys.argv[1]).read_text()
    out = Path(sys.argv[2])

    dark = read_tokens(css, ":root[data-theme='dark']")
    light = read_tokens(css, ":root[data-theme='light']")
    shared = read_tokens(css, ":root {")
    print(f"read {len(dark)} dark, {len(light)} light, {len(shared)} shared tokens")

    write_theme(out, "nautilus-dark", True, {**shared, **dark})
    write_theme(out, "nautilus-light", False, {**shared, **light})


if __name__ == "__main__":
    main()
