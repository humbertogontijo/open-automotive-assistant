# Open Automotive Assistant (HAOS app)

Self-hosted **OAA hub** as a Home Assistant OS app (add-on). Same Music Assistant pattern: server beside HA, Ingress to the web UI, cars pair over the LAN.

## Install

1. Supervisor → Add-on store → ⋮ → Repositories
2. Add `https://github.com/humbertogontijo/open-automotive-assistant`
3. Install **Open Automotive Assistant**
4. Start the app; open via **Ingress** or `http://HOME_ASSISTANT_IP:8787`

## Pair cars

Fleet → **Nearby cars** → Add. The car's screen shows a 6-digit code; type it on the hub. Cars that are not on the same network can use **Add by address** (the car's IP) or the manual flow: Fleet → Generate pairing code → on the car: Settings → Hub → hub URL + code.

The app runs with host networking so it can see the cars' mDNS announcements.

## Cars away from home (Nabu Casa)

Cars keep their link to the hub over Home Assistant Cloud:

1. Enable Nabu Casa **Remote access**.
2. Install the HACS integration (`homeassistant/custom_components/open_automotive_assistant`) and add the hub with its system token (hub UI as admin → `GET /api/auth/system-token`).

The integration reports your Nabu Casa URL to the hub, and the hub hands it to every car: at pairing, and each time a car connects, so cars paired before still pick it up. Pair at home as usual; nothing else to configure.

To pin a URL instead (e.g. your own domain in front of Home Assistant), set the `public_node_url` option. It wins over the reported one.

## Remote Cameras (WebRTC)

Live preview, playback and downloads from the hub UI use WebRTC directly between the car and your browser; video never passes through this app. Public STUN works for most networks. For cars on cellular, point these options at your own TURN server (coturn with `use-auth-secret`):

| Option | Example |
|---|---|
| `turn_urls` | `turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp` |
| `turn_secret` | coturn `static-auth-secret` |
| `turn_ttl` | Credential lifetime in seconds (default 3600) |
| `stun_urls` | Override STUN, or `none` |

See [docs/webrtc.md](../../../docs/webrtc.md).

## Notes

- The app runs the same image as docker compose (`deploy/docker/Dockerfile`), published to GHCR by `publish-hub.yml`.
- Persist pairing state under `/data`
- Do not expose port 8787 to the public internet (see [docs/safety.md](../../../docs/safety.md))
- For entity export into HA automations, install the HACS custom component under `homeassistant/custom_components/open_automotive_assistant`

Full docs: [docs/hub.md](../../../docs/hub.md)
