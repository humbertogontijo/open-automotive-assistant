# WebRTC media plane

Remote Cameras (live preview, DVR playback, download and cut) run over WebRTC when the SPA is served by a hub (`role=hub`). The design rationale and limits are in [ADR-0003](adr/0003-hub.md); the wire contract is in [OpenAPI](openapi/open-automotive-assistant-v1.yaml) (`/api/webrtc/ice`, `/api/webrtc/signal`, `WebRtcSignal`, `DataChannelHello`, `MediaChunk`).

On the car's own LAN (`role=local`) nothing changes: live preview is HLS and recordings are plain `/api/dvr/*` HTTP.

## How a session works

1. The SPA calls `GET /api/webrtc/ice` (hub session required) for STUN and, if configured, short-lived TURN credentials.
2. It opens `WSS /api/webrtc/signal?node=<id>` on the human face (`:8787`). The hub replies `{type:"hello", v, role:"hub", nodeId, online}`.
3. The SPA creates one `RTCPeerConnection` with a recvonly H.264 video transceiver and the `oaa-media` data channel, then sends `webrtc_offer`.
4. The hub adds its ICE servers to the offer and relays it over the car's existing node WebSocket. The car answers; ICE candidates flow both ways through the hub.
5. Media flows car ↔ browser directly, or through TURN. The hub only relays signaling.

| Feature | Transport | Car side |
|---|---|---|
| Live | H.264 RTP track | AccessUnits from the mosaic DVR encoder, passed through without re-encoding |
| Playback / scrub | Data channel → MSE | Recording remuxed to fragmented MP4 (`playback_open` / `playback_seek` / `playback_close`) |
| Download | Data channel → Blob | `download_open {name}`, basename only |
| Cut | Data channel → Blob | `cut_request {fromMs, toMs}`, up to 30 minutes |

The car's WebRTC stack is [Pion](https://github.com/pion/webrtc) (pure Go), bound to Kotlin with gomobile in `libs/oaartc`. It shares no `org.webrtc` classes with the GeckoView engine that hosts the in-car UI, and it has no native dependencies beyond the Go runtime (arm64, armv7 and x86_64, API 21+). `./gradlew :oaartc:assemble` downloads the pinned Go toolchain (`oaa.goVersion` in `gradle.properties`) into `~/.gradle/oaa-toolchains`. It needs the NDK named by `oaa.ndkVersion`. `cd libs/oaartc && go test ./...` runs a loopback session (H.264 track plus data channel) without a device.

The car's first data-channel message is `dc_hello` with `features`; the SPA only uses what is advertised. Hiding the tab sends `live_pause` (the car stops sending video frames); showing it sends `live_resume`, and the car starts again with a key frame. Leaving Cameras hangs up the session.

## Error reasons

`webrtc_hangup.reason` ends the session; `media_error.error` fails one data-channel request.

| Value | Meaning |
|---|---|
| `offline` | Node is not connected to the hub |
| `unsupported` | Car app is too old or has no DVR |
| `replaced` | Another viewer opened this car's cameras |
| `version` | Signaling or data-channel version mismatch; update the car app or hub |
| `busy` | `media_error` only: transfer limit reached (2 per session) |
| `invalid` | Malformed frame, bad `sessionId`, or a name that failed the basename check |
| `error`, `bye` | Car-side failure or normal close |
| `timeout` | SPA only: no `dc_hello` or request answer in time |

The SPA shows these through the `media.reason.<value>` i18n keys. After a failure it waits 5 s before reconnecting (60 s after `replaced`), so two viewers do not keep stealing the session from each other.

## NAT traversal and TURN

STUN is enough when either side has a reachable NAT. Cars on cellular (CGNAT) and many corporate networks need TURN.

| Env on hub | Default | Purpose |
|---|---|---|
| `OAA_STUN_URLS` | `stun:stun.cloudflare.com:3478` | Comma-separated; `none` disables STUN |
| `OAA_TURN_URLS` | unset | e.g. `turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp` |
| `OAA_TURN_SECRET` | unset | Shared secret with coturn `static-auth-secret` |
| `OAA_TURN_TTL` | `3600` | Credential lifetime in seconds (60–86400) |

Credentials follow the coturn REST scheme: username `<expiry>:<userId>`, password `base64(HMAC-SHA1(secret, username))`. The secret stays on the hub, and credentials expire after `OAA_TURN_TTL`. `GET /api/status` reports `webrtc.turn` so you can confirm it is active.

Docker compose ships coturn under the `turn` profile; see [REMOTE.md](../deploy/docker/REMOTE.md#turn---profile-turn).

## Privacy

The hub never carries, logs or stores media. TURN relays DTLS ciphertext only. In v1 any authenticated hub user can open any paired car's cameras; per-node ACLs are future work.

## Troubleshooting

- **"Car is offline"**: the car's hub connection is down; check the hub's Cars page.
- **Stuck on connecting, then ICE failure**: no usable path; configure TURN and make sure UDP 3478 and the relay port range are reachable.
- **Live black but playback works**: the head unit rejected pass-through H.264. The fallback second encode is documented in ADR-0003 but not built yet.

## Not covered

Hub SFU or recording, hub-to-hub federation, cabin audio, head-unit screen share, and WebRTC for the Home Assistant `camera` entity are out of scope and may reuse this plane later.
