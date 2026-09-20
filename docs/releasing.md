# Signing, releasing, and getting listed

Where this stands, what is known, and what still has to be asked. Statements are marked **verified** (done here,
or read in Inductive Automation's own docs), **reported** (forum posts, or research not confirmed first-hand), or
**open**. Signing section written 2026-09-19; listing section rewritten 2026-09-20 from IA's own pages.

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
nothing (`mantle/build.gradle.kts`). The properties are the Gradle plugin's:

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

### Before buying a certificate

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

## Getting listed

### There is no module store in the gateway — the Showcase is a web listing

8.3 has no catalogue, no browse-and-install, no registry. A gateway installs a `.modl` you already downloaded
(Platform → System → Modules → Install or Upgrade Module, or the `user-lib/modules` + `modules.json` route this
repo's `dev-up.sh` uses). **verified**
([manual](https://www.docs.inductiveautomation.com/docs/8.3/getting-started/installing-and-upgrading/installing-or-upgrading-a-module))

The [Third-Party Module Showcase](https://inductiveautomation.com/moduleshowcase/) is a **web page that links to
your site**. You host the download, you sell it, you support it. As of 2026-09-19 it lists 111 modules from 52
vendors, 63 marked 8.3-compatible. **reported**

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

- [ ] **Decide the product name** (see above). Conservative: `Mantle`, with "for Ignition" as prose only.
- [ ] **A licence for the repo and the module.** No `LICENSE` yet. The module should carry a `license.html`; the
      gateway shows it at install, and `ACCEPT_MODULE_LICENSES` exists because modules are expected to have one.
- [ ] **Third-party notices.** The `.modl` bundles Eclipse Tahu (**EPL-2.0**), the HiveMQ MQTT client, Netty,
      Jackson and protobuf (Apache-2.0 / BSD). EPL-2.0 is weak copyleft: shipping Tahu unmodified is fine, but its
      licence and a notice of where to get the source have to travel with the module. Generate a `NOTICE` at build.
- [ ] **A real version.** `0.1.0-SNAPSHOT` today, and the middle digit has to match the platform.
- [ ] **A CA code-signing certificate on a hardware token**, bought *before* applying, so the module does not land
      in 8.3's quarantine list.
- [ ] **A product page on joyautomation.com** with everything the FAQ requires — IA reviews it as part of approval.
- [ ] **User documentation in the module** (`documentationFiles` puts it on the gateway's module page).
- [ ] **The status page looked at by a person**, and the gaps in `mantle/README.md` (TLS, authenticated brokers,
      devices from a real edge, a skewed edge clock, load).
- [ ] **Sparkplug conformance**: run `sparkplug-tck-go`'s host profile in CI. Eclipse also runs a "Sparkplug
      Compatible" programme with a public product list. **open: membership and cost.**

### Two questions to put to IA in writing

1. **Is there any fee for a Showcase listing?** No source says either way.
2. **Reconcile their own docs on certificates.** The user manual says *"Authors are required to request
   certificates from Inductive Automation"*, while the SDK docs and IA staff on the forum say you use your own,
   self-signed or CA. Nobody has documented IA actually issuing them. Ask before buying.

Then, whatever the answer: post in the forum's 3rd Party Modules category. It needs nobody's approval and, by
several vendors' accounts, draws more attention than the Showcase does.
