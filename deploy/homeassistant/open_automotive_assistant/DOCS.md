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

Cars keep their link to the hub over Home Assistant's public URL. Nabu Casa only forwards Home Assistant itself, so the **Open Automotive Assistant integration** relays the cars to this app.

1. Enable Nabu Casa **Remote access** (Settings → Home Assistant Cloud), then restart this app. At start it reads Home Assistant's Nabu Casa URL (else its external URL) and publishes it to cars. To use another URL (e.g. your own domain in front of Home Assistant), set the `public_node_url` option; it wins over the detected one.
2. Install the integration:
   1. Copy the `open_automotive_assistant` folder from [`homeassistant/custom_components`](https://github.com/humbertogontijo/open-automotive-assistant/tree/main/homeassistant/custom_components) in the repository into `/config/custom_components/` (with the Samba share, File editor or SSH app).
   2. Restart Home Assistant.
3. Add the hub: Settings → Devices & services shows **Open Automotive Assistant** as discovered → **Add**. When it asks for the token, open this app's UI → Settings → **Integration token** → **Show and copy**, and paste it. (If it is not discovered: Add integration → Open Automotive Assistant, host `127.0.0.1`.)

Cars get the public URL at pairing and each time they connect, so cars paired before still pick it up. Pair at home as usual.

To check it: Fleet → **Away from home** shows the URL away cars dial (or a warning when there is none), and each online car says whether it is connected over the local network or the public URL.

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
- The same integration (see "Cars away from home") exports car entities into HA automations

Full docs: [docs/hub.md](../../../docs/hub.md)
