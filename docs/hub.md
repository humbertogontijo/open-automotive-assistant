# Self-hosted hub

Open Automotive Assistant can run as an always-on **hub** beside Home Assistant (Music Assistant–shaped): one server aggregates many cars; each car still runs the HU app and can control itself.

Design (topology, faces and auth, control and media planes, OTA): [adr/0003-hub.md](adr/0003-hub.md). Remote Cameras over WebRTC: [webrtc.md](webrtc.md). Wire contract: [openapi/open-automotive-assistant-v1.yaml](openapi/open-automotive-assistant-v1.yaml).

## Support boundary

**Supported** deployments:

1. **Home Assistant OS addon** (Ingress + optional HA Cloud node face for away cars)
2. **Docker Compose** ([`deploy/docker`](../deploy/docker/)), including tunnel/proxy profiles for internet reachability

Bare JVM jars and other packagers are fine for development; they are not a supported production path.

## Faces and ports

| Face | Port | Purpose | Auth |
|------|------|---------|------|
| Human | `8787` (`OAA_PORT`) | SPA, fleet, control APIs | Hub session (local / HA OAuth / Ingress) |
| Node | `8788` (`OAA_NODE_PORT`) | Car pair, WebSocket session, OTA downloads | Pairing code → node bearer token |

mDNS: `_oaa-hub._tcp` advertises both ports.

## Install

### Docker Compose

```bash
cd deploy/docker
docker compose up -d
```

Open `http://HOST:8787`, complete **first-run admin setup**, then use Fleet. Data persists in `oaa-data` (`/data`).

Internet without HAOS: see [deploy/docker/REMOTE.md](../deploy/docker/REMOTE.md) (`tunnel` / `proxy` profiles, Tailscale).

Multi-arch images: `ghcr.io/<owner>/open-automotive-assistant` (CI workflow `publish-hub.yml`).

### Home Assistant OS app

The app uses the published `image:` in [`deploy/homeassistant/open_automotive_assistant/config.yaml`](../deploy/homeassistant/open_automotive_assistant/config.yaml), the same image docker compose builds from [`deploy/docker/Dockerfile`](../deploy/docker/Dockerfile). Open via **Ingress** (auto-login via `X-Remote-User-*`) or `http://HOME_ASSISTANT_IP:8787`.

Away cars: install the HACS component so it registers HA Cloud **node** views (`/api/oaa_node/pair|session|artifacts`). Set `OAA_PUBLIC_NODE_URL` to your Nabu Casa base and `OAA_SESSION_PATH=/api/oaa_node/session`. OTA downloads follow the session path (`/api/oaa_node/artifacts`); override with `OAA_ARTIFACTS_PATH` only for custom proxies.

## First-run auth

1. Open the human UI → create admin username/password (`/setup`).
2. Optional: configure `OAA_HA_URL` + OAuth client for **Sign in with Home Assistant**.
3. Admins can fetch a machine token for the HA integration: `GET /api/auth/system-token`.

There is no password recovery — wipe `/data/auth.json` (or the volume) to re-run setup.

## Pair a car

1. Hub UI → Fleet → **Generate pairing code** (note Cloud/public node URL when shown).
2. Car UI → Settings → Hub → enter the hub's **node** URL shown on Fleet (`http://HUB_IP:8788`, or the tunnel / HA Cloud node URL such as `https://….ui.nabu.casa/api/oaa_node`) and the code.
3. Car stores the node token and keeps its session on that node URL (or the `OAA_PUBLIC_NODE_URL` the hub publishes).

## Remote control and debug

The hub forwards `/api/*` (any signed-in user) and `/debug/*` (admins only) to the selected car as `rpc` frames over the node session. The car is chosen by the `X-Oaa-Node` header, then `?node=`, then the `oaa_node` cookie the SPA sets when you pick a car in Fleet — so plain links such as **Export zip** work. Bodies are binary-safe and capped at 2.5 MiB each way (413 beyond that; use the car's LAN URL for larger downloads).

The Lab page works through the hub: re-probe, OBD2, export, the ADB hint and **Live logs** (`WS /debug/logs/stream?token=…&node=…`, relayed as `log_subscribe` / `log` frames). The contributor token is still checked by the car.

## App updates (OTA)

Admins upload a signed APK and roll it out; cars download it from the node face with their node token (resumable with `Range`), verify hash, package and signing certificate, then self-install. A rollout is marked `installed` only when the car reconnects and its `hello` reports the new APK hash. Offline cars get the offer when they reconnect.

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/ota/artifacts` | Raw APK body; `X-Oaa-Package`, optional `X-Oaa-Version-Name` / `X-Oaa-Version-Code` |
| GET | `/api/ota/artifacts` | Stored artifacts (newest first, last 5 kept) |
| POST | `/api/ota/rollouts` | `{"artifact": sha256, "nodes": [...]}` or `{"artifact": sha256, "all": true}` |
| GET | `/api/ota/rollouts[/{id}]` | Per-car state: pending → offered → downloading → verifying → installing (→ pending_user) → installed / failed |

Fleet shows each car's app version and latest OTA state. From a dev checkout, `./tools/oaa-setup hub-deploy` does build → sign → upload → rollout → wait (see [contributor-debug.md](contributor-debug.md)).

## Home Assistant entities

HACS custom component: [`homeassistant/custom_components/open_automotive_assistant`](../homeassistant/custom_components/open_automotive_assistant/). Point it at the hub with a Bearer token. Zeroconf discovery uses `_oaa-hub._tcp`. Devices are created per node; entities refresh from the catalog.

The on-car Home Assistant plugin remains for inbound shortcuts (car → HA).

## Demo node

```bash
OAA_DEMO_NODE=1 java -jar oaa-hub.jar --data /tmp/oaa-data
```

Registers an in-process demo car for CI / UI smoke tests.
