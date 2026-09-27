# Remote access for docker-compose hubs

Supported installs are **HAOS addon** or **docker compose** only (see [hub.md](../../docs/hub.md)).

## Security model

- **Human face** (`:8787`): SPA + control APIs. Always requires **hub login** (local admin, HA OAuth, or Ingress when addon). Never rely on tunnel auth alone.
- **Node face** (`:8788`): `/api/nodes/pair`, `/api/nodes/session` and `/api/nodes/artifacts/{sha256}` (OTA downloads) only. Auth is pairing code → node bearer token.

- **Media plane** (remote Cameras): WebRTC between car and browser. Signaling goes over the human face with a hub session; video and recordings never pass through the hub. Add TURN (below) for cars on cellular.

See [ADR-0003](../../docs/adr/0003-hub.md).

## Cloudflare Tunnel (`--profile tunnel`)

1. Create a Cloudflare Tunnel and copy the token.
2. In Zero Trust, add public hostnames:
   - `oaa-nodes.example.com` → `http://localhost:8788` (node face)
   - `oaa.example.com` → `http://localhost:8787` (human face)
3. Export `CLOUDFLARE_TUNNEL_TOKEN=...` and set on the hub:

```bash
export CLOUDFLARE_TUNNEL_TOKEN=...
export OAA_PUBLIC_NODE_URL=https://oaa-nodes.example.com
export OAA_PUBLIC_HUMAN_URL=https://oaa.example.com
docker compose --profile tunnel up -d
```

Optional: Cloudflare Access on the **human** hostname is an extra layer; cars must still use the **node** hostname with node tokens (do not put Access in front of the node face).

## Caddy / Traefik (`--profile proxy`)

```bash
docker compose --profile proxy up -d
```

Edit [`Caddyfile`](./Caddyfile) for your domains. Path ACL on the node hostname limits exposure to pair/session/artifacts/health.

## Tailscale

Run the hub on a Tailscale node (userspace or sidecar). Point cars at `http://100.x.y.z:8788` (node) and browsers at `:8787` (human). Still complete hub `/setup` before using the human UI. No public internet required.

## TURN (`--profile turn`)

Remote Cameras connect car and browser directly using STUN. When both sides sit behind strict NAT (cellular CGNAT, hotel Wi-Fi), they need a TURN relay. The `turn` profile runs coturn with the shared-secret scheme: the hub hands each viewer credentials that expire after `OAA_TURN_TTL` seconds, and the secret itself never leaves the hub or coturn.

```bash
export OAA_TURN_SECRET=$(openssl rand -hex 32)
export OAA_TURN_EXTERNAL_IP=203.0.113.10   # public IP of this host
export OAA_TURN_URLS="turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp"
docker compose --profile turn up -d
```

- Forward `3478/udp`, `3478/tcp` and `49160-49200/udp` to the host. TURN is UDP/TCP, not HTTP, so it cannot go through Cloudflare Tunnel or Caddy.
- `turn.example.com` must resolve to `OAA_TURN_EXTERNAL_IP`, not to a Cloudflare-proxied hostname.
- Check `GET /api/status`: `webrtc.turn` is `true` once the hub has both URLs and the secret.
- Tailscale-only setups usually do not need TURN.

Details: [webrtc.md](../../docs/webrtc.md).

## Dev pushes and remote debug

Hub admins can install builds and debug cars without adb, from anywhere the human face is reachable:

```bash
OAA_HUB_URL=https://oaa.example.com OAA_HUB_USER=admin ./tools/oaa-setup hub-deploy
```

The car downloads the APK from the node hostname, so OTA works through the tunnel and proxy profiles as long as `/api/nodes/artifacts/*` reaches `:8788`. The Lab page (probe, export, live logs) is proxied over the car's node session. See [hub.md](../../docs/hub.md#app-updates-ota).

## HA OAuth on compose

To offer “Sign in with Home Assistant” on a non-addon hub:

```env
OAA_HA_URL=https://homeassistant.local:8123
OAA_HA_CLIENT_ID=https://oaa.example.com
```

Home Assistant uses IndieAuth: the client id is the hub's public URL and no secret is needed. The redirect URI `https://oaa.example.com/api/auth/callback` must be on the same host as the client id. The hub exchanges the code at `/auth/token` and reads the user from `auth/current_user`; HA admins become hub admins, other HA users get the `user` role.
