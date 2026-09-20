# Ignition modules

Modules for Inductive Automation's Ignition (8.3+), built to carry
[Nautilus](../nautilus) into plants that already run Ignition.

| | |
|---|---|
| [`mantle/`](mantle/README.md) | **Mantle.** A Sparkplug B host for Ignition: tags create themselves, are historized by default, and are customized in place. |
| [`integration/`](integration/README.md) | Integration tests: a real Nautilus edge node against a live gateway. |
| [`docs/releasing.md`](docs/releasing.md) | Signing, releasing, and getting listed: what is known and what to ask. |
| [`ideas.md`](ideas.md) | What to build next, and why. |

## Working here

```sh
scripts/dev-up.sh --fresh        # gateway (localhost:8088, admin/password) + mosquitto + modules + dev config
scripts/install-module.sh mantle   # rebuild one module and reload it
scripts/demo.sh                    # three Nautilus edge nodes publishing into it, to look at
scripts/keep-trial-alive.sh        # reset the gateway's two-hour trial, for a demo left running
(cd integration && go test ./...)  # ~90 s; -short for ~12 s
```

Each module is its own Gradle build (`cd mantle && ./gradlew build`) producing a `.modl` in `build/`.
`docker-compose.yml`, `scripts/` and `dev/config/` are shared. `dev/config/` holds gateway config resources that
`dev-up.sh` seeds: they are plain files, which is also how you would check a site's configuration into git.
