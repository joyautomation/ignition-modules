#!/usr/bin/env python3
"""Compile a Nautilus MimicDoc into a Perspective view.json.

The point of the Nautilus HMI module is that a screen authored once reaches Ignition as something a plant
engineer can still edit. That works because the two formats are the same kind of document — a tree of typed
components with positions and bindings — so this is a data-to-data compiler, not a transpiler:

    MimicDoc                              view.json
    canvas {width,height}             ->  ia.container.coord root
    equipment[].component "Tank"      ->  type com.joyautomation.nautilus.tank
    equipment[].x/y/width             ->  position (height derived, see below)
    equipment[].props                 ->  props
    equipment[].bind {prop: tag}      ->  propConfig["props.<prop>"].binding, type tag
    labels[]                          ->  ia.display.label + tag binding

Deliberately ONE WAY. It generates the starting view; Perspective owns it afterwards. Round-tripping would
mean two sources of truth, and the whole value is that the output stops being generated the moment it lands.

    tools/mimic-to-view.py <doc.mimic.json> <out/view.json> [--provider Sparkplug] [--prefix Plant/Line1]
"""
import argparse, json, re, sys
from pathlib import Path

MODULE = "com.joyautomation.nautilus"
# Registry name -> Perspective component id. Only components the module actually registers belong here; a
# MimicDoc may name anything its Svelte `registry` prop supplied, and those cannot cross over.
COMPONENTS = {"Tank": f"{MODULE}.tank"}

# A MimicDoc gives a component a WIDTH and lets it choose its own height. A Perspective coordinate container
# needs both, so the height comes from the component's own viewBox ratio -- read from the source rather than
# tabulated here, so it cannot drift.
HMI = Path.home() / "Development/joyautomation/nautilus/hmi/src/lib/components"


def aspect(name: str) -> float | None:
    """height/width from a component's viewBox, or None if it has no single one."""
    f = HMI / f"{name}.svelte"
    if not f.exists():
        return None
    m = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', f.read_text())
    return float(m.group(2)) / float(m.group(1)) if m else None


def tag_binding(path: str) -> dict:
    return {"type": "tag", "config": {"tagPath": path}}


def convert(doc: dict, provider: str, prefix: str, tagmap: dict[str, str]) -> tuple[dict, list[str]]:
    notes: list[str] = []

    def tagpath(name: str) -> str:
        # A MimicDoc names tags the way the screen's author thinks of them ("LevelPct"); a plant names them
        # the way its edge published them ("HeavyPlant/HeavyEdge/PLC1/Tank/Level"). Those are never going to
        # be the same string, so a mapping is not a convenience -- it is the difference between a converter
        # that works on one gateway and one that works anywhere.
        mapped = tagmap.get(name)
        if mapped is not None:
            return mapped if mapped.startswith("[") else f"[{provider}]{mapped}"
        if tagmap:
            notes.append(f"tag {name!r} has no mapping — falling back to the doc's own name")
        inner = f"{prefix}/{name}" if prefix else name
        return f"[{provider}]{inner}"

    children = []
    for eq in doc.get("equipment", []):
        comp = eq.get("component", "")
        type_id = COMPONENTS.get(comp)
        if not type_id:
            notes.append(f"equipment {eq.get('id', '?')!r}: no Perspective component for {comp!r} — skipped")
            continue
        width = eq.get("width", 100)
        ratio = aspect(comp)
        if ratio is None:
            notes.append(f"{comp}: no viewBox found, height guessed as width")
            ratio = 1.0
        props = dict(eq.get("props") or {})
        if eq.get("label") and "label" not in props:
            props["label"] = eq["label"]
        props.setdefault("style", {"classes": ""})
        node = {
            "meta": {"name": eq.get("id") or comp},
            "position": {"x": eq.get("x", 0), "y": eq.get("y", 0),
                         "width": width, "height": round(width * ratio)},
            "props": props,
            "type": type_id,
        }
        binds = eq.get("bind") or {}
        if binds:
            node["propConfig"] = {f"props.{p}": {"binding": tag_binding(tagpath(t))}
                                  for p, t in binds.items()}
        children.append(node)

    for i, lab in enumerate(doc.get("labels", [])):
        node = {
            "meta": {"name": f"label{i}"},
            "position": {"x": lab.get("x", 0), "y": lab.get("y", 0), "width": 160, "height": 28},
            "props": {"text": lab.get("text", ""), "style": {"classes": ""}},
            "type": "ia.display.label",
        }
        if lab.get("bind"):
            # A bound label shows the live value, not its caption -- numberFormat keeps the decimals the
            # doc asked for, and the unit is appended the way <Mimic> renders it.
            fmt = "0." + "0" * int(lab.get("decimals", 1)) if int(lab.get("decimals", 1)) else "0"
            expr = f'numberFormat({{{tagpath(lab["bind"])}}}, "{fmt}")'
            if lab.get("unit"):
                expr += f' + " {lab["unit"]}"'
            node["propConfig"] = {"props.text": {"binding": {"type": "expr", "config": {"expression": expr}}}}
        children.append(node)

    if doc.get("pipes"):
        notes.append(f"{len(doc['pipes'])} pipe(s) not converted — Pipe renders an SVG <g> and cannot be a "
                     f"custom element; these need one 'nautilus-pipes' element owning the whole underlay")

    canvas = doc.get("canvas", {})
    view = {"custom": {}, "params": {}, "props": {},
            "root": {"children": children, "meta": {"name": "root"},
                     "props": {"style": {"width": f"{canvas.get('width', 800)}px",
                                         "height": f"{canvas.get('height', 600)}px"}},
                     "type": "ia.container.coord"}}
    return view, notes


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("doc"); ap.add_argument("out")
    ap.add_argument("--provider", default="Sparkplug"); ap.add_argument("--prefix", default="")
    ap.add_argument("--map", action="append", default=[], metavar="DOCNAME=TAGPATH",
                    help="map a MimicDoc tag name onto a real tag path; repeatable")
    ap.add_argument("--map-file", help="JSON object of the same mappings")
    a = ap.parse_args()
    doc = json.loads(Path(a.doc).read_text())
    tagmap = json.loads(Path(a.map_file).read_text()) if a.map_file else {}
    tagmap.update(dict(m.split("=", 1) for m in a.map))
    view, notes = convert(doc, a.provider, a.prefix, tagmap)
    out = Path(a.out); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(view, indent=2))
    n = len(view["root"]["children"])
    print(f"{doc.get('name', a.doc)}: {n} component(s) -> {out}")
    for note in notes:
        print(f"  note: {note}", file=sys.stderr)


if __name__ == "__main__":
    main()
