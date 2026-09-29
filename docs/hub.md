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

Open `http://HOST:8787`, complete **first-run admin setup**, then use the Cars page. Data persists in `oaa-data` (`/data`).

Internet without HAOS: see [deploy/docker/REMOTE.md](../deploy/docker/REMOTE.md) (`tunnel` / `proxy` profiles, Tailscale).

Multi-arch images: `ghcr.io/<owner>/open-automotive-assistant` (CI workflow `publish-hub.yml`).

### Home Assistant OS app

The app uses the published `image:` in [`deploy/homeassistant/open_automotive_assistant/config.yaml`](../deploy/homeassistant/open_automotive_assistant/config.yaml), the same image docker compose builds from [`deploy/docker/Dockerfile`](../deploy/docker/Dockerfile). Open via **Ingress** (auto-login via `X-Remote-User-*`) or `http://HOME_ASSISTANT_IP:8787`.

### Away cars (public node URL)

The hub has one setting for it, `OAA_PUBLIC_NODE_URL`: the URL away cars dial, the same one typed on a car for manual pairing.

- A plain origin (`https://oaa-nodes.example.com`, a tunnel or proxy in front of port 8788) uses the node face's own paths.
- A URL with a path is a bridge prefix: the session is `<prefix>/session` and OTA downloads `<prefix>/artifacts`.

Nabu Casa exposes Home Assistant only, so there away cars go through the integration's HA Cloud **node** views (`/api/oaa_node/pair|session|artifacts`, token-gated by the car's node token, not an HA login): `OAA_PUBLIC_NODE_URL=https://xxxx.ui.nabu.casa/api/oaa_node`. The HA app sets this itself at start: its `public_node_url` option, else Home Assistant's Nabu Casa URL (else its external URL), read through the Supervisor, plus `/api/oaa_node`. The hub itself knows nothing about Home Assistant. Install the integration and add the hub with its token (Settings → **Integration token**); see [the app docs](../deploy/homeassistant/open_automotive_assistant/DOCS.md).

Cars get the endpoint when they pair and in a `public_node` frame on every connect, so cars paired earlier pick it up the next time they are online. A new URL replaces the previously published one; the car never drops it on "none", since that could strand a car that is away. The node face serves the bridge's artifacts path too, so cars on the LAN download the same OTA offer.

To check it: Cars → **Away from home** shows the URL away cars dial (or a warning when there is none) and whether it works: the hub sends an empty pair request through it and expects its own node face to answer with its `hubId` (a 404 behind Home Assistant means the integration is not installed or not added). Each online car says whether it is connected over the local network or the public URL. The hub logs `public node URL: …` on start, and pings cars every 20 s, so a car that drops off the network shows offline within about 30 s.

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

1. Hub UI → Cars → **Nearby cars** lists unpaired cars announcing `_oaa-car._tcp` on the LAN (needs host networking for the hub). Or use **Add by address** with the car's IP.
2. **Add** → the car shows a 6-digit code (dialog and notification) → type it on the hub.
3. The hub registers the car and hands it a node token; the car keeps the hub's LAN address (port 8788) as its local URL and the public node URL ([above](#away-cars-public-node-url)), picks between them as in [Local and public URL on the car](#local-and-public-url-on-the-car), and stops announcing itself.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/nodes/discovered` | Admin. Unpaired cars seen by mDNS |
| POST | `/api/nodes/invite` | Admin. `{"nodeId": …}` or `{"host": "192.168.1.50", "port": 8787}` → `inviteId` |
| POST | `/api/nodes/invite/{id}/confirm` | Admin. `{"code": "123456"}` → paired node |
| DELETE | `/api/nodes/invite/{id}` | Admin. Forget an open invite |

**Manual pairing** (car not on the hub's network):

1. Hub UI → Cars → Manual pairing → **Generate pairing code** (note Cloud/public node URL when shown).
2. Car UI → Settings → Hub → *Pair manually with a hub code* → enter the hub's **node** URL shown on the Cars page (`http://HUB_IP:8788`, or the tunnel / HA Cloud node URL such as `https://….ui.nabu.casa/api/oaa_node`) and the code.
3. Car stores the node token. A typed LAN URL becomes its local URL; a typed tunnel or HA Cloud URL stands in as the public URL until the hub publishes its own. The hub also appears under the car's trusted devices.

Removing the hub from the car's trusted devices, or Settings → Hub → Leave, drops the link; the car announces itself again.

## Talking to the car directly

The car's own `:8787` API answers only paired callers. Open `http://CAR_IP:8787` in a browser to pair it (the car shows the code). Scripts use `./tools/oaa-setup -H CAR_IP pair`, which mints a token over adb, then `Authorization: Bearer <token>`.

## Remote control and debug

The hub forwards `/api/*` (any signed-in user) and `/debug/*` (admins only) to the selected car as `rpc` frames over the node session. The car is chosen by the `X-Oaa-Node` header, then `?node=`, then the `oaa_node` cookie the SPA sets for the open car — so plain links such as **Export zip** work.

In the hub UI the fleet is `/` and hub settings `/settings`; a car's pages live under its node id (`/<node>/`, `/<node>/cameras`), so a reload or bookmark reopens the same car. When that car is offline, unknown or not answering, the UI returns to the fleet with a notice. Bodies are binary-safe and capped at 2.5 MiB each way (413 beyond that; use the car's LAN URL for larger downloads).

The Lab page works through the hub: re-probe, OBD2, export, the ADB hint and **Live logs** (`WS /debug/logs/stream?token=…&node=…`, relayed as `log_subscribe` / `log` frames). The contributor token is still checked by the car.

## App updates (OTA)

Admins upload a signed APK and roll it out; cars download it from the node face with their node token (resumable with `Range`), verify hash, package and signing certificate, then self-install. A rollout is marked `installed` only when the car reconnects and its `hello` reports the new APK hash. Offline cars get the offer when they reconnect.

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/ota/artifacts` | Raw APK body; `X-Oaa-Package`, optional `X-Oaa-Version-Name` / `X-Oaa-Version-Code` |
| GET | `/api/ota/artifacts` | Stored artifacts (newest first, last 5 kept) |
| POST | `/api/ota/rollouts` | `{"artifact": sha256, "nodes": [...]}` or `{"artifact": sha256, "all": true}` |
| GET | `/api/ota/rollouts[/{id}]` | Per-car state: pending → offered → downloading → verifying → installing (→ pending_user) → installed / failed |

**Delta updates.** When a car's `hello` hash matches a stored artifact, the offer also carries `delta` (`from`, `sha256`, `size`, `path`): an OADP patch built by [`libs/apk-delta`](../libs/apk-delta/) that copies unchanged zip entries from the installed APK. The car downloads the patch, rebuilds the APK, checks it against the artifact hash and installs it. On any failure it downloads the full APK instead. Patches are built on first offer, cached under `artifacts/deltas/`, served from the same artifacts path by their own hash (so the HA Cloud proxy needs no changes), and skipped when larger than 70% of the APK. For builds that are not on GitHub Releases, keep the car's current build among the last 5 artifacts to get deltas.

The Cars page shows each car's app version and latest OTA state. From a dev checkout, `./tools/oaa-setup hub-deploy` does build → sign → upload → rollout → wait (see [contributor-debug.md](contributor-debug.md)).

### Release updates on the car

Every `v*` tag attaches the car APK and `car-apk.json` (`package`, `versionName`, `versionCode`, `sha256`, `size`, `file`) to its GitHub Release (`release-car.yml`). A hub fetches the release for its own version at start (`OAA_CAR_RELEASE_URL`, default `https://github.com/humbertogontijo/open-automotive-assistant/releases/download/v{version}`), checks the APK against the manifest hash, and keeps it as an artifact. It retries every few minutes, then less often, until the release exists.

After each `hello`, the hub sends `ota_available`: its policy (`OAA_CAR_UPDATES`, the Home Assistant app's `car_updates`: `ask` by default, `auto` or `off`) and, when the car runs the same package with a lower version code, the build's `sha256`, `size`, `versionName` and `versionCode`.

- **ask:** the car's Settings → **App update** shows the version and an **Install** button (head unit only). It sends `ota_request {sha256}`, and the hub starts a one-car rollout.
- **auto:** the hub starts that rollout itself, once per build and car.

Cars download as little as possible. When the store lacks the car's installed APK, the hub fetches the release matching the car's `versionName` and uses it only if its hash equals the car's `hello` hash. It then builds the delta and announces again with `downloadSize` (the patch size). A car on a build that was never published (a dev APK) gets the full APK unless that APK was uploaded to the hub earlier. The car reads the announcement and progress from `GET /api/update`.

### Hub and car versions

The hub serves its own copy of the web UI for every car it opens, so the hub should run the same app version as its cars or a newer one. Update the hub first, then roll the cars out from it.

- A car newer than the hub may have pages and cards the hub's UI does not know. The car's pages say so, and so does its entry on the Cars page. `hub-deploy` refuses such an APK unless given `--allow-newer`.
- A car older than the hub reports the pages it serves (`pages` in `/api/status`), and the UI hides the rest. Cars from before that field are assumed to serve the pages every car had then. Its pages show a note to update it.
- A card the UI cannot render falls back to a read-only value card instead of leaving a gap.

## Home Assistant entities

Integration: [`homeassistant/custom_components/open_automotive_assistant`](../homeassistant/custom_components/open_automotive_assistant/), copied into `config/custom_components/` by the HAOS app at start (by hand for compose hubs; [steps](../homeassistant/custom_components/open_automotive_assistant/README.md)). Point it at the hub with the token from Settings → **Integration token**. Zeroconf discovery uses `_oaa-hub._tcp`. Devices are created per node; entities refresh from the catalog.

The on-car Home Assistant plugin remains for inbound shortcuts (car → HA).

## Demo node

```bash
OAA_DEMO_NODE=1 java -jar oaa-hub.jar --data /tmp/oaa-data
```

Registers an in-process demo car for CI / UI smoke tests.
