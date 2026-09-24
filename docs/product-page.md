# Mantle's product page — draft copy

Inductive Automation **reviews the website** as part of a Showcase application, so this page has to exist and
be complete before applying. Their FAQ requires it to show: module name, description, Ignition version
compatibility, **cost**, a **download link so users can try it**, and a way to purchase if applicable. An IA
reviewer rejecting an application in 2026 put the bar plainly: *"You need to have a link to download the
module and an upfront price, no 'email me' stuff."*

So the two things that must be unmissable and above the fold: **Free**, and a **direct download link with no
email gate**. Everything else is detail.

Drafted here rather than written straight into `joyautomation.com` because it is public marketing copy for the
company and the claims are yours to make. Port it when you are happy with it. Suggested route:
`/software/mantle`, alongside the existing `/software/tentacle`.

---

## Above the fold

> # Mantle
> ### A Sparkplug B host application for Ignition 8.3+
>
> Point it at your broker. Tags create themselves, get historized, and stay yours to customize.
> **There is no second set of tags to maintain.**
>
> **Free and open source** · Apache-2.0 ·
> [Download Mantle 1.3.0 (.modl)](https://github.com/joyautomation/ignition-modules/releases/download/mantle/v1.3.0/Mantle.modl) ·
> [Source on GitHub](https://github.com/joyautomation/ignition-modules)
>
> *Commercial support available from Joy Automation.*

**Required fields, stated explicitly somewhere visible:**

| | |
|---|---|
| **Cost** | Free |
| **Licence** | Apache License 2.0 (open source) |
| **Ignition compatibility** | 8.3.0 and later. The middle version digit must match the platform, so an 8.4 release will be a separate build. |
| **Download** | Direct `.modl` link, no email, no form |
| **Support** | Paid support and integration from Joy Automation — optional, never required |

---

## What it does

Three claims, and every one of them is tested against a live gateway rather than asserted. Keep them in this
order; the third is the one that separates Mantle from what people already have.

**Tags create themselves.** Every metric in an NBIRTH or DBIRTH becomes an Ignition tag, laid out the way the
wire is — `[Sparkplug]Group/Node/Metric` — with data type, engineering units, range and documentation carried
across from the edge. Nothing is declared in advance. Nothing is re-declared when the edge changes.

**Tags are historized by default.** New tags get History Enabled and a history provider when they are created.
Turn history off on a tag and it stays off. Metrics the edge flags `is_transient` start with history off.

**There is one set of tags.** They live in a managed tag provider that persists tags and allows customization,
so alarms, scaling, scripts, security and history settings go on these tags directly. They survive rebirths,
reconnects and gateway restarts. Nothing has to be mirrored into a second tag tree to be "managed".

> **Why customization is safe.** Ignition keeps two layers per tag. What Mantle sets is stored under `prg`;
> what you set in the Designer sits beside it and wins. A rebirth refreshes the module's layer — data type,
> units, range, the history default — and cannot reach yours.

## Configuration is the risk, so there is almost none

A broker URL is the only setting that has to be filled in.

And where configuration *is* genuinely missing, Mantle says so rather than running quietly. A gateway with no
tag historian records nothing; Mantle reports that connection as a **problem** in the Status column of the
page you configured it on — and on the gateway's own home page, as a banner with a button that takes you
straight to the page that fixes it. Silent data loss is the failure mode this module exists to prevent, and
it is not prevented by a warning nobody goes looking for.

## Security

- Passwords are stored as Ignition secrets — encrypted on disk, or referenced from a secret provider. Never
  plaintext.
- `ssl://` for TLS, verified against the gateway's own trust store. A private plant CA goes in
  `data/certificates/supplemental/` once and serves every module; there is no per-connection truststore to get
  wrong.
- Mutual TLS supported: a client certificate and PKCS#8 key, read at connect time so renewals need no edit.

## What has been tested — and what has not

*Publish this section. It is unusual, it is true, and for this audience it is a selling point rather than a
liability. It is also the thing that makes every other claim on the page credible.*

Tested against a live Ignition 8.3.9 gateway and a real Sparkplug edge node on every commit:

- The tag tree, data types, templates as UDTs, and a customized tag surviving a rebirth
- Writes in both directions, including a write to one UDT member leaving the others alone
- Deaths, killed nodes, keep-alive timeouts, broker restarts, and store-and-forward data landing in history
  stamped inside the outage
- TLS, authenticated brokers and mutual TLS, each with its negative case
- **Three broker implementations** — Mosquitto 2, EMQX 5 and HiveMQ CE, the last being the broker the
  Sparkplug TCK itself is built on — and **two transports**, TCP and WebSockets. Each verified by real
  Sparkplug traffic arriving as tags, not merely by a socket opening.
- **Sparkplug conformance**: 49 assertions of the TCK's host-application profile pass, none fail, graded from
  the packets on the wire.
- **The published, signed `.modl` installing on a clean gateway** with no developer flags — the same file
  this page links to, not a local build.

Not yet tested, stated plainly:

- **AWS IoT Core and Azure**, which differ on ALPN and need accounts to test against.
- `wss://` — WebSockets and TLS are each covered, but not the two together.
- An edge with a badly skewed clock; sustained load.

## Support

The software is free and always will be. If you want someone on the end of a phone — commissioning, a
migration off another MQTT module, a broker that does not behave, or a feature you need — that is what Joy
Automation sells.

*[Contact / quote form]*

---

## Before this goes live

- [x] **The download link is live and needs no login** — verified anonymously, with every GitHub credential
      unset: `HTTP 200`, 13,050,401 bytes, checksum matches the published `SHA256SUMS`, and the file is
      signed by `CN = Joy Automation`. That is the requirement IA rejects applications over.
- [ ] Decide whether to name MQTT Engine. **Nothing in this repo has tested it**, so any comparison has to be
      checked first-hand on a current version and dated, or left out. Leaving it out is fine — the page works
      on its own claims.
- [ ] Confirm the version on the page matches the release, and keep them in step; IA's reviewer will look.
- [ ] Screenshots: the configuration page with a healthy connection, the Status column showing the
      no-historian problem, and the Designer tag browser filling in as a node births.
      `~/Development/joyautomation/content/assets/capture/` is where the capture rigs live.
