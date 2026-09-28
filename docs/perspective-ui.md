# Nautilus components in Perspective

How the Nautilus HMI's 54 Svelte components reach an Ignition Perspective screen, why that route was chosen
over the alternatives, and the spike that has to pass before any of it is committed to.

Marked the way `releasing.md` marks things: **verified** (checked here), **reported** (read, not confirmed
first-hand), **open** (nobody knows yet). Written 2026-09-25.

## The decision

**Svelte 5 stays the single implementation. Custom elements are the public API. One generic React adapter
hosts them in Perspective.**

Two modules, shipped separately:

| | |
|---|---|
| **Theme pack** | Perspective themes generated from Nautilus's design tokens. No components, no adapter, no JavaScript. Useful to somebody who installs nothing else. |
| **Component module** | The 54 components as custom elements, plus the adapter that registers them with Perspective. |

Separate `.modl` files, same monorepo. That is what this repo is for: one Gradle build per module directory,
path-filtered CI, per-module release tags. Signing, CD, `checkModuleArtifact` and `checkDependencyLicenses`
are per-module already, so a second module inherits the whole pipeline.

Names are still open. The Showcase forbids "Ignition" inside a module name and allows "for Ignition" only as
trailing prose — see `releasing.md`.

## What is actually known

**verified — Perspective runs React 18.2.** Its client ships `react-18.2.0.js` and `react-dom-18.2.0.js` as
named files inside `app-3.3.9.jar`; the gateway's own web UI reports 18.2.0 as well. This is the single fact
that shapes everything below.

**verified — the components are Svelte 5 with runes.** `@joyautomation/nautilus-hmi` 0.6.0, Svelte 5.56.4,
54 `.svelte` components, 62 files using `$state`/`$derived`/`$props`.

**verified — a real runes component compiles to a custom element.** `TankGlyph.svelte`, unmodified apart from
adding `<svelte:options customElement>`, compiled with `customElement: true`: 8,183 bytes, registers a custom
element, **zero warnings**. This was the riskiest assumption and it holds.

**verified — the theme is CSS custom properties** (`--font`, `--font-xs`, …, in `hmi/src/lib/theme.css`).
Custom properties are inherited, so they **cross the shadow DOM boundary**. A Perspective theme can therefore
style these components from the outside with no bridging code. This is what makes the theme pack useful on
its own *and* makes the two modules compose instead of collide.

**verified — a custom element renders, and takes props as properties.** `Tank.svelte` compiled with
`customElement: true` and no source changes, loaded in a plain page: shadow root, `<svg>` inside it, paints
at 220px, and `Object.assign(el, {levelPct, tempC, heaterPct, label, width})` drives it. Confirmed
2026-09-27 in Chrome.

**verified — page-level custom properties DO cross into the shadow root.** Read from inside
`nautilus-tank`'s shadow root, `--s1` resolves to `#3987e5`, the value `theme.css` sets on the page. This
was a kill criterion and it is cleared: the theme pack and the component module compose.

**verified — 5 of the 54 components cannot be custom elements at all.** `TankGlyph`, `PumpGlyph`,
`ValveGlyph`, `Pipe` and `FlowLink` render an SVG *fragment* (`<g>`), designed to sit inside an `<svg>` the
caller owns. A custom element is an HTML-namespace host, so its shadow root has no `<svg>` to render into
and the element paints nothing — measured host box `0x0`. It still *compiles* with zero warnings, which is
why the earlier "compiles to a custom element" check did not catch it; compiling is not rendering.

The control that makes this a real finding rather than a broken component: the same `TankGlyph`, mounted as
a plain Svelte component into an `<svg>` we own, paints correctly and picks its colour up from `--s1`. Same
component, same props, same page — only the boundary differs.

This is a scoping fact, not a blocker. Each glyph has a standalone sibling that is an `<svg>` root and works
(`TankGlyph`/`Tank`, `PumpGlyph`/`Pump`, `ValveGlyph`/`Valve`), and `Pipe`/`FlowLink` are connectors that
only mean anything inside a diagram. The glyphs are a *composition* API: the way they reach Perspective is
inside a container component that owns one `<svg>` — `Mimic` or `CoordinateCanvas` — not individually. That
container, not the glyph, is the thing to wrap.

**reported — React 19 added first-class custom element support; React 18 did not.** On 18, a non-primitive
prop set as a JSX attribute is stringified, and a `CustomEvent` does not arrive as an `onFoo` prop. Both need
a `ref`. Not confirmed against Perspective here; the spike settles it.

## Why not the alternatives

**Rewrite the components in React.** Two implementations of 54 components drift within a quarter, and the
Nautilus HMI would stop being the source of truth for its own symbols. The honest counterfactual: *if you
were starting today and Perspective were the only target, write React* — no adapter, no shadow DOM. That is
not the situation.

**Move Nautilus to Solid.** Solid's advantage is fine-grained reactivity inside a framework. The boundary
here is the DOM, where that buys nothing, and it costs a rewrite of all 54 components.

**Make Nautilus support React and Svelte both.** A permanent tax on every component written from then on.
The shape that avoids it: Svelte is the implementation, **the custom element is the contract**, and each host
framework gets a thin adapter. A React consumer takes an adapter, not a second implementation.

## Step 2 findings — how a Perspective component is actually registered

Read out of `perspective-component-minimal` and `perspective-component` in `ignition-sdk-examples`
(branch `ignition-8.3`), 2026-09-27. **reported** — read from IA's own examples, not yet run here.

A component is four things, and only the last is ours to invent:

- **A Java descriptor** in `common` scope — `ComponentDescriptorImpl.ComponentBuilder` with an id, a palette
  category, a palette entry and a `JsonSchema`. Registered by both the gateway hook and the designer hook.
- **A JSON Schema for the props**, as a resource file. This *is* the Designer's property editor: `type`,
  `enum`, `format: "color"`, `visibleWhen`, `dynamicSuggestions` all render as editor affordances. So the
  per-component cost really is a manifest entry, which is what the generic-adapter argument needed.
- **A JS meta object** registered with `ComponentRegistry.register(...)`: `getComponentType()`,
  `getViewComponent()`, `getDefaultSize()`, and `getPropsReducer(tree)` — which is the explicit answer to
  "how does the property tree reach a component". You map it yourself, with `tree.readString(...)` and
  friends.
- **A React component** extending `Component` from `window.PerspectiveClient`. Both `PerspectiveClient` and
  `React` are globals supplied by the gateway, so they are webpack `externals` — the same arrangement
  `mantle/web-ui` already uses for `@inductiveautomation/*`.

**The correction that matters.** This note previously said the adapter "has to set properties and attach
listeners through a `ref`". That is wrong for Perspective, and would have produced a component that
misbehaves in ways hard to attribute. IA's own comment is unambiguous: the topmost element must spread
`this.props.emit({...})`, which supplies the element ref, event listeners, styles and classes — and

> declaring a `ref` on the root element … will override the emitted ref and will not allow your component to
> properly display any changes to the state of its qualities and may cause the component to throw an error.

So the adapter reaches its DOM node through **`this.props.store.element`** after mount, never a `ref`. Two
further constraints from the same source: DOM events go through `this.props.domEvents.addListener` rather
than JSX handlers, and *"it is highly recommended that the root element does not change throughout the
lifecycle of the component"* — which a fixed custom-element tag satisfies for free.

That makes the generic adapter *more* plausible than when this was written, not less, because the awkward
part is now a documented API rather than a fight with React 18's attribute stringifying:

```js
class NautilusElement extends Component {          // from window.PerspectiveClient
    componentDidMount()  { this.sync(); }
    componentDidUpdate() { this.sync(); }
    sync() {
        const el = this.props.store.element;        // NOT a ref
        Object.assign(el, this.props.props);        // properties, not attributes
    }
    render() {
        return React.createElement(TAG, this.props.emit());
    }
}
```

`Object.assign` onto the element is exactly what already drove `nautilus-tank` in step 1, so the two halves
are known to fit. **open** — whether `emit()`'s styles and the custom element's own sizing agree, and
whether a binding update re-renders without remounting.

## Step 3-4 results — measured in a running gateway, 2026-09-28

**verified — bindings do NOT remount the element. The kill criterion is cleared.** A Perspective expression
binding at 2 Hz (`50 + 40 * sin(getMillis(now(500)) / 500.0)`) driving `levelPct`, measured off the adapter's
own counters after ~65 seconds:

```
mounts: 1    updates: 129    distinctNodes: 1    mountSeq: "1"    stillSameNode: true
```

129 binding updates, one mount, one DOM node, and the node the adapter holds at the end is the node it was
given at the start. React reconciles the constant element type in place. This was the criterion most likely
to force a rethink and it is comfortably clear.

**verified — the generic adapter stayed generic.** It is written as a factory over
`(tag, componentType, propSpec)` with the component list as a manifest, never a `TankComponent`. That matters
because a bespoke wrapper would have worked here and told us nothing.

**verified — the theme pack composes with the component module, inside real Perspective.** Read from inside
the custom element's shadow root while the session ran under our generated `nautilus-dark.css`:
`--n-accent` resolves to `#06b6d4`. Step 1 measured this in a plain page; this is the same result in the
product, which is what it needed.

**verified — the custom element upgrade race did not bite.** `BROWSER_RESOURCES` is a Java `Set.of(...)`
with unspecified iteration order, so the adapter can load before the element bundle defines the tag, and
properties set on a un-upgraded element would be shadowed by the class accessors. Measured `upgraded: true`
with the live value flowing, so Svelte 5's custom elements survive it. Worth a `customElements.whenDefined`
guard anyway, because "did not bite today" and "cannot bite" are different claims and the ordering is
genuinely unspecified.

### Two real problems, neither fatal

**The component library's token names and the theme pack's do not match.** Inside the shadow root, `--s1`
resolves to **nothing**. The Nautilus HMI's components default to `fill='var(--s1)'`, but the generated
Perspective theme publishes the tokens as `--n-s1`, `--n-accent` and so on — the `--n-` prefix exists so the
mapping onto Perspective's own variable names reads as a mapping. So a component's *internal defaults* go
unstyled under the Perspective theme even though the theme is present and reaching the shadow root.

Three ways out, and it is a decision, not a bug fix:
1. the theme publishes both spellings (cheap, and a component needs no knowledge of Perspective);
2. the theme publishes the bare names Nautilus already uses, and drops the prefix;
3. the components move to `--n-` names, which changes the HMI.

(1) is the obvious first move, and the generator can emit it for free. It is recorded here rather than done
because it touches what the theme pack publishes, which is a product surface.

**The host element stretches.** Measured `hostBox: 220x1468` against `svgBox: 220x260` — the custom element
host is eleven hundred pixels taller than its own content, because `emit()` sets no height and the flex
container stretches it. The component draws correctly inside that box, so it looks like a layout bug rather
than a rendering one, but it makes the component unusable in a flex container without intervention. **open**
— whether the adapter should constrain the host, the component should declare its own intrinsic size, or the
props should carry a height. This is the next thing to settle.

## The part that needs design, not just work

React 18 means the adapter has to set properties through the element rather than as JSX attributes. The thing that makes
that tractable is that **it can be one adapter rather than 54**, provided the elements keep to a convention:

- every input is a **property** on the element, named as in the Svelte component
- every output is a **`CustomEvent`** whose `detail` carries the payload
- nothing is passed as a JSX attribute except strings

Then a single `<NautilusElement tag="nautilus-tank" props={…} on={…} />` serves every component, and adding a
component is a manifest entry rather than a new wrapper. If that convention cannot hold — if some component
needs slots, or a render prop, or two-way binding that does not reduce to property-plus-event — the generic
adapter breaks down and the cost goes up sharply. **The spike exists to find that out on one component
before it is discovered on the fortieth.**

## Open questions the spike must answer

- ~~How does Perspective's property tree (JSON) reach a component?~~ **Answered:** `getPropsReducer(tree)`
  on the meta object, mapped explicitly. What happens to a nested object is still **open**.
- Does the Designer palette need anything beyond registration? **Answered:** a Java `ComponentDescriptor`
  plus a JSON Schema resource; the schema is the property editor.
- ~~Does a Perspective **binding** update the element without a remount?~~ **Answered: yes.** 129 updates, 1 mount.
- Does Perspective's own CSS reset reach inside the shadow root, or do the components need their own?
  (Partly answered: custom *properties* cross the boundary — **verified**. A CSS *reset* is ordinary
  selector-based CSS and does not, which is the shadow DOM working as intended.)
- Do focus, keyboard and screen readers behave across the shadow boundary? **open**, and the most likely
  source of unpleasant surprises for `AckButton`, `ConfirmDialog` and `AlarmTable`.
- Bundle shape: one bundle of 54 components, or lazy per component? **open.** Two components came to 59 kB.
- Does the host element size itself sensibly in a container? **open, and currently no** — see above.

## Sequencing

1. **Theme pack.** Small, standalone, proves the packaging path end to end with no adapter risk.
2. **Spike one component**, below.
3. Only then the remaining 53.

Step 3 is mechanical and parallelisable once 2 is proven; step 2 is not, and should not be rushed on the
grounds that step 3 looks big.

---

# The spike

**One component, all the way onto a Perspective screen, bound to a Mantle tag.** `Tank` — not `TankGlyph`,
which step 1 showed cannot be a custom element at all (see *What is actually known*). `Tank` takes a numeric
`levelPct`, has visual state, owns its own `<svg>`, and is the direct analogue of Perspective's stock Vessel,
so it is the component whose value is most obvious when it is wrong.

The point is not to produce reusable code. It is to find out which of the open questions above have ugly
answers, while the cost of changing direction is one component rather than fifty-four.

## Done means

A Perspective view, in a browser, showing a tank whose level follows `[Sparkplug]Plant/Line1/…` as Mantle
writes it — and the same view still correct after the Designer changes a property and after the page is
reloaded.

Not "it renders". Renders is the easy half.

## Steps

1. ~~**Build one custom element.**~~ **DONE 2026-09-27.** `Tank.svelte` + `<svelte:options
   customElement="nautilus-tank" />`, Vite IIFE bundle, 59 kB for two components, zero warnings. Works in a
   plain HTML page with no Ignition anywhere, and doing it there first is what turned up the `<g>` problem
   while it cost one afternoon instead of forty. The wrappers were generated *around* the originals rather
   than edited into them, so nothing in `nautilus/hmi` changed.

2. **Register it as a Perspective component.** This is the unknown with the least documentation. The
   `perspective-component` SDK example is the scaffold; expect a Java side (component descriptor, property
   schema, palette entry) and a JS side (a React component registered under a type id).

3. **Write the adapter, generically from the start.** One `NautilusElement` that takes a tag name, a props
   object and an event map, sets properties through a `ref`, and attaches listeners with
   `addEventListener`. Resist writing `TankWrapper`; if the generic form cannot work, that is a finding, not
   an inconvenience.

4. **Bind it.** Point the level property at a Mantle tag and watch it move. Then change a property in the
   Designer and confirm the element updates rather than remounting.

5. **Theme it.** Load the theme pack's CSS variables at the page level and confirm they reach inside the
   shadow root. They should — custom properties inherit — but *should* is what this whole exercise is about.

6. **Poke the awkward corners.** Tab to it. Resize it. Put two on one view. Reload. Open it on a phone.

## What would kill the approach

Any of these means stop and reconsider rather than push on:

- **The generic adapter cannot stay generic.** If `TankGlyph` needs a bespoke wrapper, 54 components need 54,
  and the maintenance argument for Svelte-as-source collapses.
- **Bindings remount the element on every update.** A tank that rebuilds itself ten times a second is not
  usable, and working around it means fighting Perspective's reconciliation.
- **Theme variables do not reach the shadow root.** Then the two modules stop composing and every component
  needs its own theming channel.
- **Focus or screen readers break across the boundary** in a way that cannot be fixed inside the component.
  `AckButton` and `ConfirmDialog` are safety-adjacent; an acknowledge button that cannot be reached by
  keyboard is not shippable.

## What to write down either way

Whatever happens, the findings belong in this file under *Open questions*, replaced by answers. If the spike
kills the approach, that write-up is worth more than the code was — it is the argument for whichever route is
taken instead.

And per this repo's habit: the surprises go to `~/Development/joyautomation/content/ideas.md` as well.
Almost nobody has written about putting Svelte components inside Perspective, and the interesting post is the
same either way it turns out.
