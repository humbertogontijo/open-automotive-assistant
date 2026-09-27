# Open Automotive Assistant (HAOS app)

Self-hosted **OAA hub** as a Home Assistant OS app (add-on). Same Music Assistant pattern: server beside HA, Ingress to the web UI, cars pair over the LAN.

## Install

1. Supervisor → Add-on store → ⋮ → Repositories
2. Add this repository URL (monorepo root or a published addon repo that tracks `deploy/homeassistant`)
3. Install **Open Automotive Assistant**
4. Start the app; open via **Ingress** or `http://HOME_ASSISTANT_IP:8787`

## Pair cars

Fleet → Generate pairing code → on the car: Settings → Hub → hub URL + code.

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
