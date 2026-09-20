# Signing, releasing, and getting listed

Where this stands, what is known, and what still has to be asked. Written 2026-09-19. Statements are marked
**verified** (done here, or read in Inductive Automation's own docs), **reported** (forum posts, including by IA
staff), or **open**.

## Signing

### How it works

A `.modl` is a zip. Signing adds `certificates.p7b` (your certificate chain) and `signatures.properties` (a signature
per file). On install the gateway shows the certificate and asks the administrator to accept it, then remembers its
fingerprint in `data/modules.json`. **verified**

- **Any certificate works technically.** IA staff, 2020: *"Ignition doesn't look at it or care, only your customers
  will maybe see that your module is signed by a real code signing certificate or not."* A self-signed certificate is
  accepted; the administrator just can't verify who you are without checking the fingerprint some other way.
  **reported** ([forum](https://forum.inductiveautomation.com/t/which-code-signing-certificate-should-i-get-for-the-module/36800))
- **For anything sold or distributed publicly, use a CA-issued code-signing certificate.** That is IA's guidance.
  **verified** ([SDK guide](https://www.sdk-docs.inductiveautomation.com/docs/getting-started/create-a-module/module-signing/))
- **Use one certificate for every module.** Once an administrator accepts it, it is accepted for all of them.
  Another reason for the monorepo. **reported**
- **When a certificate expires**, installed modules are said to keep running and be reclassified as self-signed
  rather than disabled. That comes from a community member, not IA: confirm before relying on it. **reported**
  ([forum](https://forum.inductiveautomation.com/t/third-party-module-signature-validity/19624))

### In this repo

Each module signs itself when a keystore is configured, and builds unsigned otherwise, so development and CI need
nothing (`mantle/build.gradle.kts`). The properties are the Gradle plugin's
([README](https://github.com/inductiveautomation/ignition-module-tools/blob/master/gradle-module-plugin/README.md)):

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

`./gradlew build` then produces `build/Mantle-Ignition.modl` beside the `.unsigned.modl`. The same names work as
flags: `./gradlew signModule --certAlias=...`.

**Verified end to end with a throwaway self-signed certificate**: `signModule` ran, the signed module loaded on a
gateway started **without** `-Dignition.allowunsignedmodules=true`, and the gateway recorded the fingerprint. To
repeat it:

```sh
mkdir -p .signing && cd .signing     # .signing/ is gitignored, as are *.jks *.p12 *.p7b
keytool -genkeypair -alias mantle-dev -keyalg RSA -keysize 3072 -validity 365 \
  -dname "CN=Joy Automation DEV ONLY (self-signed), O=Joy Automation, C=US" \
  -ext KeyUsage=digitalSignature -ext ExtendedKeyUsage=codeSigning \
  -keystore dev.jks -storepass devpassword -keypass devpassword -storetype JKS
keytool -exportcert -alias mantle-dev -keystore dev.jks -storepass devpassword -rfc -file dev.crt
openssl crl2pkcs7 -nocrl -certfile dev.crt -out dev.p7b
```

### The thing to know before buying a certificate

Since June 2023 the CA/Browser Forum requires the private key of a publicly trusted code-signing certificate to live
on certified hardware: a USB token, or a cloud HSM. **A CA will not hand you a `.p12` to put in a GitHub secret.**
That shapes the release pipeline more than anything else here:

- **USB token (YubiKey or the CA's own):** cheapest. Signing happens on the machine the token is plugged into, so a
  release is `./gradlew build` on a workstation (or a self-hosted runner), not a hosted Actions job. The plugin's
  `pkcs11CfgFile` is for exactly this.
- **Cloud HSM / signing service** (SSL.com eSigner, DigiCert KeyLocker, and similar): costs more per year, and signs
  from hosted CI. It has to expose **PKCS#11** (or a JCA provider) to work with the Gradle plugin, which signs with
  Java's own machinery rather than `jarsigner`/`signtool`. **open: confirm with the vendor before paying.** Forum
  users report moving from DigiCert to SSL.com on price.
- OV is enough. EV buys Windows SmartScreen reputation, which means nothing to a gateway.

Start with a token. Releases are rare, and it can move to a cloud HSM later without anyone reinstalling, as long as
the certificate's subject stays the same. **open: does a renewed or reissued certificate with a new fingerprint make
administrators re-accept?** Almost certainly yes; worth knowing before the first renewal.

## Getting listed

Inductive Automation has a [Third-Party Module Showcase](https://inductiveautomation.com/moduleshowcase/), under
their "Technology Ecosystem" alongside Strategic Partners and Technology Providers. **verified** that it exists.
The page says nothing about how a module gets onto it. **open**, and the questions to put to IA
(sales or the partner team):

1. What is the submission process, and is there a review or a fee?
2. Does listing require a partner programme tier? (Cirrus Link, the obvious comparison, is a Strategic Partner whose
   modules IA resells. That is a different relationship from a Showcase listing.)
3. Do they require a CA-issued certificate, or any particular CA? One search summary claimed authors *request
   certificates from IA*; I could not find that in any IA source, so treat it as unconfirmed and ask.
4. How are paid third-party modules licensed? `freeModule` is true here. A paid module needs to know whether IA's
   licensing platform is open to third parties or whether vendors run their own.
5. Is "for Ignition" in a product name acceptable under their trademark guidelines? "Mantle for Ignition" was chosen
   over "Mantle Ignition Edition" to stay on the right side of this, but it is their mark.
6. Do they list modules that overlap a Strategic Partner's product? Mantle for Ignition competes with MQTT Engine.

IA's forum has "3rd Party Modules" and "Module Development" categories, which is where most independent modules
actually get discovered, and posting there needs nobody's permission.

## Before a first public release

Roughly in order. None of it is started.

- [ ] **A licence for the repo and the module.** The repo goes public later and has no `LICENSE`. The module should
      carry a `license.html`: the gateway shows it at install, and `ACCEPT_MODULE_LICENSES` exists because modules
      are expected to have one. Nautilus is Apache-2.0.
- [ ] **Third-party notices.** The `.modl` bundles Eclipse Tahu (**EPL-2.0**), the HiveMQ MQTT client, Netty, Jackson
      and protobuf (Apache-2.0 / BSD). EPL-2.0 is weak copyleft: shipping Tahu unmodified is fine, but its licence
      and a notice of where to get the source have to travel with the module. Generate a `NOTICE` at build time.
- [ ] **A real version.** `0.1.0-SNAPSHOT` today. Tags per module (`mantle/v0.1.0`) driving a release workflow that
      builds, signs (where the key lives decides where this runs, see above), and attaches the `.modl` and its
      SHA-256 to a GitHub release.
- [ ] **A gateway status page**, so an administrator can see connections and nodes without reading logs
      (`ideas.md`, idea 1). A module with no UI reads as unfinished in a store.
- [ ] **User documentation** in the module (`documentationFiles` in the Gradle plugin puts it in the gateway's
      module page).
- [ ] **Sparkplug conformance.** Run `sparkplug-tck-go`'s host profile against the module in CI. The Eclipse
      Sparkplug Working Group runs a "Sparkplug Compatible" programme with a public product list; being on it is a
      claim the incumbent's marketing leans on. **open: membership and cost.**
- [ ] **Compatibility statement.** Tested on 8.3.9 only. Decide the supported range and test its ends in CI (the
      image tag is one line in `docker-compose.yml`).
- [ ] **The gaps in `mantle/README.md`**: TLS and authenticated brokers, devices from a real edge, a skewed edge
      clock, load.
