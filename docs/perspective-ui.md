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

## The part that needs design, not just work

React 18 means the adapter has to set properties and attach listeners through a `ref`. The thing that makes
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

- How does Perspective's property tree (JSON) reach a component, and what happens to a nested object?
- Does a Perspective **binding** update the element without a remount?
- Does the Designer palette need anything beyond registration — icon, category, schema?
- Does Perspective's own CSS reset reach inside the shadow root, or do the components need their own?
- Do focus, keyboard and screen readers behave across the shadow boundary? **open**, and the most likely
  source of unpleasant surprises for `AckButton`, `ConfirmDialog` and `AlarmTable`.
- Bundle shape: one bundle of 54 components, or lazy per component?

## Sequencing

1. **Theme pack.** Small, standalone, proves the packaging path end to end with no adapter risk.
2. **Spike one component**, below.
3. Only then the remaining 53.

Step 3 is mechanical and parallelisable once 2 is proven; step 2 is not, and should not be rushed on the
grounds that step 3 looks big.

---

# The spike

**One component, all the way onto a Perspective screen, bound to a Mantle tag.** `TankGlyph` — it takes a
numeric level, has visual state, and is the component whose value is most obvious when it is wrong.

The point is not to produce reusable code. It is to find out which of the open questions above have ugly
answers, while the cost of changing direction is one component rather than fifty-four.

## Done means

A Perspective view, in a browser, showing a tank whose level follows `[Sparkplug]Plant/Line1/…` as Mantle
writes it — and the same view still correct after the Designer changes a property and after the page is
reloaded.

Not "it renders". Renders is the easy half.

## Steps

1. **Build one custom element.** `TankGlyph.svelte` + `<svelte:options customElement="nautilus-tank" />`,
   compiled to a UMD bundle the way `mantle/web-ui` already is. Confirm it works in a plain HTML page first,
   with no Ignition anywhere — a bug found here is worth ten found later.

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
