# Signing, releasing, and getting listed

Where this stands, what is known, and what still has to be asked. Statements are marked **verified** (done here,
or read in Inductive Automation's own docs), **reported** (forum posts, or research not confirmed first-hand), or
**open**. Signing section written 2026-09-19; listing section rewritten 2026-09-20 from IA's own pages;
signing decided and costed 2026-09-21.

## Signing

### How it works

A `.modl` is a zip. Signing adds `certificates.p7b` (your certificate chain) and `signatures.properties` (a signature
per file). On install the gateway shows the certificate and asks the administrator to accept it, then remembers its
fingerprint in `data/modules.json`. **verified**

- **Any certificate works technically.** IA staff, 2020: *"Ignition doesn't look at it or care, only your customers
  will maybe see that your module is signed by a real code signing certificate or not."* **reported**
  ([forum](https://forum.inductiveautomation.com/t/which-code-signing-certificate-should-i-get-for-the-module/36800))
- **For anything sold or distributed publicly, use a CA-issued code-signing certificate.** That is IA's guidance.
  **verified** ([SDK guide](https://www.sdk-docs.inductiveautomation.com/docs/getting-started/create-a-module/module-signing/))
- **8.3 raises the stakes: it quarantines modules** whose certificate is unsigned or "needs review", and the
  administrator has to explicitly trust them. 8.3.2 added the path to do that. A self-signed module is therefore an
  adoption tax, not just a cosmetic difference. **reported**
- **Use one certificate for every module.** Once an administrator accepts it, it is accepted for all of them.
- **When a certificate expires**, installed modules are said to keep running and be reclassified as self-signed
  rather than disabled. Community, not IA — confirm before relying on it. **reported**

### In this repo

Each module signs itself when a keystore is configured, and builds unsigned otherwise, so development and CI need
nothing (`mantle/build.gradle.kts`). `scripts/gen-signing-key.sh` makes a self-signed identity and, with
`--secrets`, prints the four values `.github/workflows/release.yml` needs. The properties are the Gradle
plugin's, and **all five are required** — verified against io.ia.sdk.modl 0.5.0 by signing a real build:

```properties
# ~/.gradle/gradle.properties  (never in the repo)
ignition.signing.keystoreFile=/path/to/keystore.jks     # or .p12
ignition.signing.keystorePassword=...
ignition.signing.certFile=/path/to/chain.p7b
ignition.signing.certAlias=...
ignition.signing.certPassword=...
# a hardware token or cloud HSM instead of a file (mutually exclusive with keystoreFile):
# ignition.signing.pkcs11CfgFile=/path/to/pkcs11.cfg
```

Two traps, both of which fail a release rather than a build:

- **`certFile` is required even when the keystore already holds the chain.** Without it `signModule` stops with
  *"Required certificate file location not found"*, and the only artifact left is `Mantle.unsigned.modl`.
- **The key password property is `certPassword`, not `keyPassword`.** `keyPassword` is not a property at all,
  so passing it looks fine and does nothing.

A successful signing leaves `Mantle.modl` beside `Mantle.unsigned.modl`. The signed one contains
`signatures.properties` and `certificates.p7b`; `./gradlew checkModuleArtifact` reports which one it looked at
and whether it was signed, and the release workflow refuses to publish if the signed file is missing either.

**Verified end to end with a throwaway self-signed certificate**: `signModule` ran, the signed module loaded on a
gateway started **without** `-Dignition.allowunsignedmodules=true`, and the gateway recorded the fingerprint.

```sh
mkdir -p .signing && cd .signing     # .signing/ is gitignored, as are *.jks *.p12 *.p7b
keytool -genkeypair -alias mantle-dev -keyalg RSA -keysize 3072 -validity 365 \
  -dname "CN=Joy Automation DEV ONLY (self-signed), O=Joy Automation, C=US" \
  -ext KeyUsage=digitalSignature -ext ExtendedKeyUsage=codeSigning \
  -keystore dev.jks -storepass devpassword -keypass devpassword -storetype JKS
keytool -exportcert -alias mantle-dev -keystore dev.jks -storepass devpassword -rfc -file dev.crt
openssl crl2pkcs7 -nocrl -certfile dev.crt -out dev.p7b
```

### You may not need to buy a certificate at all

IA staff, repeatedly: *"It almost doesn't matter. Ignition doesn't look at it or care, only your customers will
maybe see that your module is signed by a real code signing certificate or not"* and *"you can simply generate
your own certificate."* **reported**, and corroborated concretely: Musson Industrial's Embr modules are approved
and listed on the Showcase, and their shipped `.modl` carries a bare self-signed certificate
(`subject=CN = Musson Industrial`, same issuer, no CA chain).

So a CA certificate is a trust-and-polish decision, not a gate. A self-signed module installs; the administrator
is asked to accept an unverifiable certificate once. For a free module whose source is public, that is a defensible
trade — the source is the trust story. Revisit it if you start selling.

### Decided 2026-09-21: self-signed for the first release

**verified.** Reasoning, so it can be revisited rather than re-argued:

- A free module with public source has the source as its trust story. A certificate adds nothing a reader
  cannot already check.
- Musson Industrial is listed on the Showcase shipping a bare self-signed certificate. It is not a gate.
- It costs nothing and blocks nothing today.

The argument **against** is real and worth restating: 8.3 quarantines modules with unsigned or
"needs review" certificates, and an administrator has to explicitly trust them. That is an adoption tax. But
it is a hypothesis about our users, not a measurement. **Revisit if more than one person reports trouble
installing**, or when something starts being sold.

Switching later costs four repository secrets and, for a cloud HSM, one property change
(`ignition.signing.pkcs11CfgFile` in place of the keystore properties). Nothing has to be rebuilt.

### What a certificate would cost, if the decision is revisited

Prices are 2026, from reseller listings, and move; treat them as the shape rather than a quote.

| | Cost | Keeps CD on hosted runners? |
|---|---|---|
| Self-signed | $0 | yes |
| SignPath Foundation (free for OSS) | $0 | yes, **but the publisher is "SignPath Foundation"**, not Joy Automation |
| Sectigo OV + USB token | ~$220/yr + $90–250 token | **no** |
| DigiCert OV + token | ~$370–575/yr (+$120 for their token) | **no** |
| Cloud HSM — DigiCert KeyLocker, SSL.com eSigner | eSigner from ~$180/yr; KeyLocker by quote | **yes** |

Three things that matter more than the headline price:

- **A USB token breaks hosted CI.** Since June 2023 the key must be on certified hardware, and a hosted
  GitHub Actions runner cannot see a USB token. Signing would move to a self-hosted runner or a workstation.
  **If a certificate is ever bought, buy cloud HSM, not a token.**
- **Cloud HSM works with our Gradle plugin** — this was the open question, and it is now answered for
  DigiCert KeyLocker, which ships a PKCS#11 library with documented jarsigner and GitHub Actions scripts.
  That is exactly what `ignition.signing.pkcs11CfgFile` takes. **open:** SSL.com eSigner is cheaper and no
  equivalent PKCS#11 documentation was found — ask them before choosing it.
- **From 1 March 2026 maximum validity drops to 460 days** (from 39 months), so any certificate is an annual
  renewal plus annual re-issuance work.

### If you do buy a certificate

Since June 2023 the private key of a publicly trusted code-signing certificate must live on certified hardware: a
USB token, or a cloud HSM. **A CA will not hand you a `.p12` to put in a GitHub secret.** That decides where
releases run:

- **USB token** (the CA's own, or a YubiKey 5 FIPS): cheapest. Releases run on a workstation or a self-hosted
  runner, not a hosted Actions job. The plugin's `pkcs11CfgFile` is for this. A showcase vendor documents exactly
  this path with SSL.com ([Kode Nu](https://kodenu.com/blog/ia-module-signing/)). **reported**
- **Cloud HSM / signing service** (SSL.com eSigner, DigiCert KeyLocker): signs from hosted CI, costs more. It must
  expose **PKCS#11** to work with the Gradle plugin. **open: confirm with the vendor before paying.**
- OV is enough. EV buys Windows SmartScreen reputation, which means nothing to a gateway.
- **Embed the full chain.** An incomplete chain makes Ignition report the module as self-signed anyway. **reported**

### Dependency licences are a real constraint

IA staff: *"Generally speaking, GPL, AGPL, and LGPL are not compatible with our signed module system unless you
release the full source code."* **reported.** The mechanism is that copyleft of that family entitles a user to
swap the component for their own build, which would require re-signing the module, which an end user cannot do.

**Mantle is clean**, audited from each jar's manifest and the published POMs: 11 Apache-2.0, 3 MIT-0, 2 BSD
3-Clause, and Eclipse Tahu under **EPL-2.0**. EPL-2.0 is not in that list and carries no relinking requirement —
it is the licence the Sparkplug reference implementation ships under, and what Cirrus Link's own modules are built
on. Audit this again whenever a dependency is added; it is now the constraint most likely to bite.

### A bug found in our own TCK

Staging out-of-order delivery turned up a real defect in `sparkplug-tck-go`, worth fixing there:
`findSeqGaps` (`internal/harness/scenarios_host_ordering.go`) is forward-only and keeps no memory of
sequence numbers it has already seen. Publishing `…6, 8, 7, 9…` — legal Sparkplug, and exactly what a
reorder buffer exists for — registers as **three** gaps rather than one:

| sees | expects | |
|---|---|---|
| 8 | 7 | gap, later filled by 7 |
| 7 | 9 | gap, later filled by 9 |
| 9 | 8 | gap — but 8 already arrived, *before* 7 |

The third can never be filled, so `tck-id-operational-behavior-host-reordering-rebirth` **fails against a
host that behaved perfectly**. Mantle buffered 8, took 7, applied both in order and correctly did not ask
for a rebirth.

**Fixed** in `sparkplug-tck-go` PR #9 (`d1899fc` + `a9aad42`, merged 2026-09-25): the detector now buffers
sequence numbers that arrive ahead of the one it is waiting for, so a swap is one filled gap rather than
three, and recovery is matched on any of the edge's DBIRTH/NDATA/DDATA/DDEATH topics. It also gained the
table test that was missing — which is how the bug survived in the first place.

Re-run against the fixed kit, all four reordering assertions pass:

```
PASS  tck-id-operational-behavior-host-reordering-param     (gap before seq=7)
PASS  tck-id-operational-behavior-host-reordering-start     (gap before seq=7)
PASS  tck-id-operational-behavior-host-reordering-rebirth   (gap before seq=7)    <- the drop
PASS  tck-id-operational-behavior-host-reordering-success   (gap before seq=15)   <- the swap
```

Our gate now does both provocations — a drop *and* a swap — and the swap, which used to produce that phantom
failure, passes. 94 with the drop alone, **95** with both.

## Getting listed

### There is no module store in the gateway — the Showcase is a web listing

8.3 has no catalogue, no browse-and-install, no registry. A gateway installs a `.modl` you already downloaded
(Platform → System → Modules → Install or Upgrade Module, or the `user-lib/modules` + `modules.json` route this
repo's `dev-up.sh` uses). **verified**
([manual](https://www.docs.inductiveautomation.com/docs/8.3/getting-started/installing-and-upgrading/installing-or-upgrading-a-module))

The [Third-Party Module Showcase](https://inductiveautomation.com/moduleshowcase/) is a **web page that links to
your site**. You host the download, you sell it, you support it. As of 2026-09-19 it lists 111 modules from 52
vendors, 63 marked 8.3-compatible. **reported**

### Free and open source is explicitly allowed

IA's own developer page, **verified** first-hand in their site bundle:

> "if you'd like to list your module on the Inductive Automation Module Showcase **either for free or for sale**,
> you can create a Module Showcase Developer Account. We do also make our licensing system available for you to
> use in your module **if you are selling your module**."

Their code renders a `0` price as the word **"Free"**. About a third of the catalogue is free, and several listed
modules are Apache-2.0 or MIT with public repos — one listing's IA-published description reads *"Free,
open-source"*. **reported**

The boundary, from an IA staff post: *"It's no problem to distribute signed modules. The only problem would be if
they weren't free, and you were somehow bypassing our licensing system to sell them."* **reported.** Free is
unrestricted; selling means using IA's licensing system. A free module needs **no licensing integration at all**.

Opt out of the trial timer with **`isFreeModule()` returning true in the hook** — which Mantle does. The
`<freeModule>` flag in `module.xml` is deprecated, because anyone could edit it. **reported**

**Do not use the Ignition Exchange for this.** Its terms force **MIT, irrevocably**, and it hosts project
resources rather than `.modl` files. The Showcase lets you keep Apache-2.0 and your own terms. **verified**
([Exchange terms](https://inductiveautomation.com/exchange/terms))

### How to apply

A **Module Showcase Developer Account**, requested at
<https://inductiveautomation.com/moduleshowcase/developer/>. There is no published email for it; the form is the
front door, and the documented escalation if it goes quiet is to ping your IA sales contact. **reported** (the page
is JS-gated and could not be read first-hand here).

Review is light and concrete, quoted from the [FAQ](https://inductiveautomation.com/moduleshowcase/faq)
— **verified**:

> "After you submit all required information, we will review your information, visit your website, download your
> module and ensure that it installs properly."

**They review your website**, so the product page has to exist first. The FAQ requires it to show: module name,
description, Ignition version compatibility, cost, a download link so users can try it, and a way to purchase if
applicable. **verified**

An IA staff member rejecting an application in 2026 put the bar plainly: *"You need to have a link to download the
module and an upfront price, no 'email me' stuff"*, and *"SE will do a technical review of the module and a
marketing review of your website."* For a free module that means: state the cost as **Free**, and give a **direct
download link with no email gate**. **reported**

### Naming rules — and a problem with ours

Quoted from the FAQ, **verified**:

> "You may not use the name 'Ignition' or 'Inductive Automation' as part of your module name."
>
> "You may state that your module is 'for Ignition' in written form. … An acceptable example would be: 'This module
> by Acme Company is the Driver Connectivity Module for Ignition.'"
>
> "you may not put the words 'Ignition' or 'Inductive Automation' before your module name"
>
> "Your company name cannot be the same as the module name."
>
> "You may not incorporate the Ignition by Inductive Automation® logo or Inductive Automation logo, in whole or in
> part, into the design of your product logo."

In their example the *product name* is "Driver Connectivity Module" and "for Ignition" is trailing prose. **Our
module declares its name as "Mantle for Ignition", which puts "Ignition" inside the name.** The conservative
reading — and the one that matches their example — is:

- **product name: `Mantle`** in the manifest, the logo and the page title
- "Mantle for Ignition" only as prose, after the name
- never "Ignition Mantle"

"Joy Automation" ≠ "Mantle", so the company-name rule is already satisfied. **Decision needed** — see the checklist.

### Module ID

> "Your Module ID needs to start with com.*yourcompanyname*"

`com.joyautomation.mantle` satisfies this, and matches the prefix we would claim on the form. Treat it as
permanent: once license keys reference a module ID it cannot be withdrawn. **verified** (rule), **reported** (the
permanence).

### Money: IA gives you licensing, not a sales channel

A Showcase developer account includes access to Ignition's **own licensing and activation system** through the
[Module Showcase API](https://inductiveautomation.com/moduleshowcase/api-docs): register module IDs under your
prefix, `generateLicenseKey` per customer, `addModulesToLicenseKey`, `suspendLicenseKey`, and activation through
the gateway's normal `activateKey` path. One activation grant is intended to mean one concurrently activated
gateway, with `backup=1` for a redundant pair. **reported**

You sell from your own site and **keep everything** — no revenue share, commission or listing fee is mentioned in
any IA source. **reported**, and worth getting in writing.

Known weakness before pricing anything: vendors report that suspending a key does not reliably invalidate an
already-activated permanent license; only removing module IDs from the key does. Enforcement leans on honour.
**reported**

### The Technology Ecosystem Program is separate, and optional

A four-level marketing programme (Registered → Verified → Gold → Premier), no fee stated, requiring an agreement
and IA branding on your site.
[Guide](https://assets.inductiveautomation.com/static/Technology-Ecosystem-Program-Guide.9e218f37ff77.pdf).
A listed third-party module already counts as the "validated contribution" Level 2 wants, so it is a cheap glide
path *if* the co-marketing is worth it. **It is a prerequisite for nothing here.** **reported**

Premier Tech Providers include Sepasoft, 4IR Solutions and Opto 22 — all of whom distribute from their own sites
rather than the Showcase.

### The competitive fact worth knowing

**Cirrus Link is IA's only Strategic Partner, and IA sells their MQTT modules directly** — MQTT Engine and MQTT
Transmission are bundled in IA's own Enterprise Integration Solution Suite (~$4,100) on IA's price list. They are
not on the Showcase, because they do not need to be. **reported**

Mantle competes with something Inductive themselves sell. That does not obviously block a Showcase listing — the
Showcase is lightly vetted and full of modules overlapping IA features — but it is the honest context for how much
promotion to expect, and it is worth asking about rather than discovering later.

### Also: the version rule

A module's **middle version digit must match the platform's** or the gateway faults it. An 8.1 module will not load
on 8.3, so supporting both means separate builds. **reported**

## Before a first public release

- [x] **Decide the product name.** `Mantle`, with "for Ignition" as trailing prose only. `checkModuleArtifact`
      fails a build whose `<name>` contains "Ignition", so the Showcase rule is enforced rather than remembered.
- [x] **A licence for the repo and the module.** Apache-2.0 in `LICENSE`; the module carries
      `mantle/license.html`, which the gateway shows at install. `checkModuleArtifact` fails if it is missing.
- [x] **Third-party notices.** `NOTICE` names every bundled library, and Tahu's EPL-2.0 obligation (licence
      plus a pointer to the source) travels with it. Not generated, but *enforced*:
      `checkDependencyLicenses` fails if anything ships that `NOTICE` does not name, which is the property
      that actually matters — a generated file nobody reads can be silently wrong.
- [x] **A real version.** `1.3.0`. The middle digit must match the platform's minor version — Inductive's own
      modules confirm the shape (Historian 1.3.9, OPC-UA 10.3.9, Perspective 3.3.9, all on 8.3.9). It is not
      semver and cannot be. `checkModuleArtifact` fails a build whose version is not `x.3.y`, and the release
      workflow rejects the tag before building.
- [x] **Sign the module** — **decided 2026-09-21: self-signed** (reasoning above). The machinery is built and
      rehearsed end to end. **Remaining action, and it is James's rather than mine, because it involves a
      passphrase:**

      ```sh
      scripts/gen-signing-key.sh            # choose a passphrase; writes .signing/mantle.p12 + .p7b
      scripts/gen-signing-key.sh --upload   # same passphrase; pushes the four secrets with gh
      ```

      Both read the passphrase from stdin when there is no terminal, so a password manager can feed it —
      `bw get password <item> | scripts/gen-signing-key.sh --upload`. (Bash suppresses a `read -p` prompt
      when stdin is not a terminal, so the first version of this did nothing and said nothing when run
      through an editor's shell integration.)

      `--upload` checks the passphrase actually opens the keystore before sending anything (a typo here
      becomes a failed release otherwise), and pipes the files straight into `gh secret set` so the keystore
      is never printed. `--secrets` prints the values instead, for pasting by hand — which puts a copy of the
      signing identity in scrollback, so prefer `--upload`. The release workflow refuses to publish unsigned,
      so this gates the first release.
- [x] **Re-audit dependency licences** — now automatic. `./gradlew checkDependencyLicenses` (part of `build`,
      so CI runs it on every change) reads each bundled jar's licence, following `<parent>` POMs, fails on the
      GPL family, and also fails if anything ships that `NOTICE` does not name. 25 dependencies, all
      permissive except Tahu's EPL-2.0. Proven to bite by adding MySQL's GPL connector.
**Mantle 1.3.0 was released 2026-09-22**: signed with the self-signed Joy Automation certificate, published
at `https://github.com/joyautomation/ignition-modules/releases/tag/mantle/v1.3.0`, and verified downloadable
anonymously (no login, no email gate). The release workflow went green first time, signing included.

**The published artifact was then installed on a clean 8.3.9 gateway started WITHOUT
`-Dignition.allowunsignedmodules=true`** — the thing a self-signed module most needs to prove. **verified**,
with its control:

- With the certificate accepted (`ACCEPT_MODULE_CERTS`, the container equivalent of an administrator
  clicking accept): *"Starting up module 'com.joyautomation.mantle' v1.3.0"*, *"Mantle module started"*, web
  bundle serving 200, status route correctly refusing an anonymous caller with 401.
- Without it: the gateway **stayed in Commissioning and Mantle never started**. So the certificate is
  genuinely being checked, and the first result is not an artefact of the flag being ignored.

That is also what an administrator will experience: a self-signed module is not silently rejected, it asks to
be trusted once. The 8.3 "quarantine" tax is real but is a single deliberate acceptance.

- [x] **A product page on joyautomation.com** — **live at https://joyautomation.com/software/mantle**
      (2026-09-24). Carries everything the FAQ requires, with the price stated upfront as Free and a direct
      download link that needs no login — the two things a reviewer has rejected applications over. Says 94
      assertions; it is now 95, worth correcting next time the page is touched.
- [x] **User documentation in the module.** `mantle/doc/index.html`, shipped through `documentationFiles`, so
      it matches the build that is installed. `checkModuleArtifact` fails if it is missing.
- [x] **TLS, authenticated brokers and mutual TLS.** Done 2026-09-20/21, and covered by CI rather than by
      hand: a private CA, a listener that refuses anonymous clients, a listener that demands a client
      certificate, and an edge publishing over `ssl://`. Server-side TLS needed no Mantle setting at all —
      the gateway's `data/certificates/supplemental/` reaches the JVM trust store. Mutual TLS is three
      settings. **Only Mosquitto 2 has been tested**; say so on the product page rather than let a HiveMQ or
      AWS IoT Core user discover it.
- [ ] **The remaining gaps in `mantle/README.md`**: `wss://`, AWS IoT Core and Azure (both need accounts),
      devices from a real edge, a skewed edge clock, load. Mutual TLS, Sparkplug conformance, WebSockets and
      three broker implementations (Mosquitto, EMQX, HiveMQ) are done and covered by CI.
- [x] **Sparkplug conformance** — done. `scripts/tck-conformance.sh` runs `sparkplug-tck-go`'s
      host-application profile against a live Mantle in CI: **95 assertions pass, none fail**, after the gate
      was taught to provoke the host (drop a sequence number, kill a device, write tags) rather than only
      watch a happy path — which had 49 passing and 36 sitting at "not observed". It grades from the
      packets on the wire and regenerates its catalogue from the Eclipse spec, so it tracks the
      specification. **open:** Eclipse also runs a "Sparkplug
      Compatible" programme with a public product list. **open: membership and cost.**

### One more thing, if Edge matters

Showcase vendors believe open-source modules are not approved for **Ignition Edge**, on the reasoning that an open
module id could be used to sidestep Edge's licence limits. That is vendor opinion with no IA statement behind it.
**reported, and worth asking directly only if Edge is in scope.**

### Two questions to put to IA in writing

1. **Is there any fee for a Showcase listing?** Still no source either way, though no vendor mentions paying.
2. **Reconcile their own docs on certificates.** The user manual says *"Authors are required to request
   certificates from Inductive Automation"*, while the SDK docs and IA staff on the forum say you use your own,
   self-signed or CA — and listed modules demonstrably ship self-signed. The manual looks stale; confirm.

Then, whatever the answer: post in the forum's 3rd Party Modules category. It needs nobody's approval and, by
several vendors' accounts, draws more attention than the Showcase does.
