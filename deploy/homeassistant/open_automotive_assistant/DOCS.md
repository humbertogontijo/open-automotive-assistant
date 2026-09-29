# Open Automotive Assistant (HAOS app)

Self-hosted **OAA hub** as a Home Assistant OS app (add-on). Same Music Assistant pattern: server beside HA, Ingress to the web UI, cars pair over the LAN.

## Install

1. Supervisor → Add-on store → ⋮ → Repositories
2. Add `https://github.com/humbertogontijo/open-automotive-assistant`
3. Install **Open Automotive Assistant**
4. Start the app; open via **Ingress** or `http://HOME_ASSISTANT_IP:8787`

## Pair cars

Cars → **Nearby cars** → Add. The car's screen shows a 6-digit code; type it on the hub. Cars that are not on the same network can use **Add by address** (the car's IP) or the manual flow: Cars → Generate pairing code → on the car: Settings → Hub → hub URL + code.

The app runs with host networking so it can see the cars' mDNS announcements.

## Cars away from home (Nabu Casa)

Cars keep their link to the hub over Home Assistant's public URL. Nabu Casa only forwards Home Assistant itself, so the **Open Automotive Assistant integration** relays the cars to this app.

1. Enable Nabu Casa **Remote access** (Settings → Home Assistant Cloud), then restart this app. At start it reads Home Assistant's Nabu Casa URL (else its external URL) and publishes it to cars. To use another URL (e.g. your own domain in front of Home Assistant), set the `public_node_url` option; it wins over the detected one.
2. Restart Home Assistant (Settings → System → ⋮ → Restart Home Assistant). This app copies the integration into `/config/custom_components/open_automotive_assistant` when it starts (and updates it with the app), and posts a notification; Home Assistant only lists the integration after a restart.
3. Add the hub: Settings → Devices & services shows **Open Automotive Assistant** as discovered → **Add**. When it asks for the token, open this app's UI → Settings → **Integration token** → **Show and copy**, and paste it. With several cars it asks which one; add the hub again for each other car. (If it is not discovered: Add integration → Open Automotive Assistant, host `127.0.0.1`.)

Cars get the public URL at pairing and each time they connect, so cars paired before still pick it up. Pair at home as usual.

To check it: Cars → **Away from home** shows the URL away cars dial and whether it reaches the hub. "HTTP 404" there means Home Assistant was not restarted yet or the integration is not added (steps 2 and 3). Each online car says whether it is connected over the local network or the public URL.

## Car app updates

Each release of this app comes with the matching car app on GitHub Releases. When the app starts it downloads that APK, and each paired car running an older build hears about it when it connects. The `car_updates` option picks what happens next:

| `car_updates` | Behaviour |
|---|---|
| `ask` (default) | The car's Settings → **App update** shows the new version and an **Install** button |
| `auto` | The hub starts the install as soon as the car connects |
| `off` | Cars are not told about updates |

Cars download only a patch from the build they run when the hub can get that build (it fetches the car's own release too), else the full APK. On Android 11 head units, the car still asks you to confirm the install on its screen. Cars → **Car app updates** shows which build the hub holds.

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
