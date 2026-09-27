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

mDNS: `_oaa-hub._tcp` advertises both ports and the hub id; the hub browses `_oaa-car._tcp` for unpaired cars.

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

### Away cars (public node URL)

Nabu Casa exposes Home Assistant only, so away cars reach the node face through the HACS component's HA Cloud **node** views (`/api/oaa_node/pair|session|artifacts`, token-gated by the car's node token, not an HA login). Install the component and add the hub with its system token.

The hub publishes one public node endpoint to cars:

1. `OAA_PUBLIC_NODE_URL` (+ `OAA_SESSION_PATH`) when set. In the HA app this is the `public_node_url` option (e.g. `https://xxxx.ui.nabu.casa`); the session path defaults to `/api/oaa_node/session`.
2. Otherwise the URL the component reports: its Nabu Casa remote URL (else HA's external URL), sent with `POST /api/nodes/public-url` (admin) on setup and each time HA Cloud connects, and kept in `data/public-node.json`. Nothing to configure.

Cars get it when they pair and in a `public_node` frame on every connect and whenever it changes, so cars paired earlier pick it up the next time they are online. A new URL replaces the previously published one; the car never drops it on "none", since that could strand a car that is away. OTA offers use the endpoint's artifacts path (`/api/oaa_node/artifacts`); the node face serves that path too, so cars on the LAN download the same offer. Override with `OAA_ARTIFACTS_PATH` only for custom proxies.

To check it: Fleet → **Away from home** shows the public URL and whether it came from the app option or Home Assistant (or a warning when there is none), and each online car says whether it is connected over the local network or the public URL. The hub logs `public node URL: …` on start and `public node URL -> …, sent to N connected car(s)` on each change.

### Local and public URL on the car

Like the Home Assistant app's internal and external URLs, each car keeps two node-face URLs, shown under Settings → Hub:

- **Local**: the hub's LAN address, learned at pairing (the address the hub called from, or the plain `http://HUB_IP:8788` typed for manual pairing).
- **Public**: the endpoint above; the car follows every `public_node` update.

Before each connect the car probes `GET <local>/api/health` (2 s connect timeout); when the node face answers with this hub's `hubId`, the session uses the local URL, otherwise the public one. While on the public URL the car re-probes the local one every 60 s and on each network change, and moves back as soon as the hub answers there. While on the local URL, a network change that makes the hub unreachable moves the session to the public URL right away instead of waiting for pings to time out. A URL whose socket fails to open is tried last on the next attempt. Cars with only one of the two URLs always dial that one.

## First-run auth

1. Open the human UI → create admin username/password (`/setup`).
2. Optional: configure `OAA_HA_URL` + OAuth client for **Sign in with Home Assistant**.
3. Admins can fetch a machine token for the HA integration: `GET /api/auth/system-token`.

There is no password recovery — wipe `/data/auth.json` (or the volume) to re-run setup.

## Pair a car

The hub adds cars; the car confirms with a code on its own screen ([adr/0004-car-auth.md](adr/0004-car-auth.md)).

1. Hub UI → Fleet → **Nearby cars** lists unpaired cars announcing `_oaa-car._tcp` on the LAN (needs host networking for the hub). Or use **Add by address** with the car's IP.
2. **Add** → the car shows a 6-digit code (dialog and notification) → type it on the hub.
3. The hub registers the car and hands it a node token; the car keeps the hub's LAN address (port 8788) as its local URL and the public node URL ([above](#away-cars-public-node-url)), picks between them as in [Local and public URL on the car](#local-and-public-url-on-the-car), and stops announcing itself.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/nodes/discovered` | Admin. Unpaired cars seen by mDNS |
| POST | `/api/nodes/invite` | Admin. `{"nodeId": …}` or `{"host": "192.168.1.50", "port": 8787}` → `inviteId` |
| POST | `/api/nodes/invite/{id}/confirm` | Admin. `{"code": "123456"}` → paired node |
| DELETE | `/api/nodes/invite/{id}` | Admin. Forget an open invite |

**Manual pairing** (car not on the hub's network):

1. Hub UI → Fleet → Manual pairing → **Generate pairing code** (note Cloud/public node URL when shown).
2. Car UI → Settings → Hub → *Pair manually with a hub code* → enter the hub's **node** URL shown on Fleet (`http://HUB_IP:8788`, or the tunnel / HA Cloud node URL such as `https://….ui.nabu.casa/api/oaa_node`) and the code.
3. Car stores the node token. A typed LAN URL becomes its local URL; a typed tunnel or HA Cloud URL stands in as the public URL until the hub publishes its own. The hub also appears under the car's trusted devices.

Removing the hub from the car's trusted devices, or Settings → Hub → Leave, drops the link; the car announces itself again.

## Talking to the car directly

The car's own `:8787` API answers only paired callers. Open `http://CAR_IP:8787` in a browser to pair it (the car shows the code). Scripts use `./tools/oaa-setup -H CAR_IP pair`, which mints a token over adb, then `Authorization: Bearer <token>`.

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

**Delta updates.** When a car's `hello` hash matches a stored artifact, the offer also carries `delta` (`from`, `sha256`, `size`, `path`): an OADP patch built by [`libs/apk-delta`](../libs/apk-delta/) that copies unchanged zip entries from the installed APK. The car downloads the patch, rebuilds the APK, checks it against the artifact hash and installs it. On any failure it downloads the full APK instead. Patches are built on first offer, cached under `artifacts/deltas/`, served from the same artifacts path by their own hash (so the HA Cloud proxy needs no changes), and skipped when larger than 70% of the APK. Keep the car's current build among the last 5 artifacts to get deltas.

Fleet shows each car's app version and latest OTA state. From a dev checkout, `./tools/oaa-setup hub-deploy` does build → sign → upload → rollout → wait (see [contributor-debug.md](contributor-debug.md)).

## Home Assistant entities

HACS custom component: [`homeassistant/custom_components/open_automotive_assistant`](../homeassistant/custom_components/open_automotive_assistant/). Point it at the hub with a Bearer token. Zeroconf discovery uses `_oaa-hub._tcp`. Devices are created per node; entities refresh from the catalog.

The on-car Home Assistant plugin remains for inbound shortcuts (car → HA).

## Demo node

```bash
OAA_DEMO_NODE=1 java -jar oaa-hub.jar --data /tmp/oaa-data
```

Registers an in-process demo car for CI / UI smoke tests.
