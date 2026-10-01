# WebRTC media plane

All camera video (live view, DVR playback, download and cut) runs over WebRTC, both when the SPA is served by the car (`role=local`) and when it is served by a hub (`role=hub`). The design rationale and limits are in [ADR-0003](adr/0003-hub.md); the wire contract is in [OpenAPI](openapi/open-automotive-assistant-v1.yaml) (`/api/webrtc/ice`, `/api/webrtc/signal`, `WebRtcSignal`, `DataChannelHello`, `MediaChunk`).

The head unit never composites or re-encodes video to stream or record it. Each camera renders straight into its own hardware H.264 encoder; the same encoded frames go to that camera's MP4 and to its WebRTC track. The browser lays the cameras out in a grid and draws each frame's capture time over the video. Only an exported clip is re-encoded, to burn the time into the picture.

## How a session works

1. The SPA calls `GET /api/webrtc/ice` for STUN and, if configured, short-lived TURN credentials. On the car this returns no servers: LAN viewers need none.
2. It opens the signaling WebSocket on the human face (`:8787`): `WSS /api/webrtc/signal?node=<id>` on the hub, `/api/webrtc/signal` on the car. Both reply `{type:"hello", v, role, online}`.
3. The SPA creates one `RTCPeerConnection` with four recvonly H.264 video transceivers and the `oaa-media` data channel, then sends `webrtc_offer`.
4. On the hub, the offer gets the hub's ICE servers and is relayed over the car's node WebSocket; on the car it goes straight to the WebRTC stack. The car answers with one track per camera (up to four, capped by the offer's video m-lines) and lists them in the answer's `tracks`.
5. Media flows car ↔ browser directly, or through TURN. The hub only relays signaling.

Each camera's track belongs to the MediaStream `oaa-cam-<role>` (`front`, `right`, `rear`, `left`); the SPA maps tracks to tiles by that id and ignores m-lines without one.

| Feature | Transport | Car side |
|---|---|---|
| Live | One H.264 RTP track per camera | That camera's encoder output, passed through without re-encoding. RTP timestamp = capture UTC ms × 90 (mod 2³²) |
| Playback / scrub | The same RTP tracks | Recorded H.264 samples sent on each visible camera's track instead of live, paced by one clock (`replay_*` on the data channel); no remux or re-encode |
| Download | Data channel → Blob | `download_open {name}`, basename only |
| Cut | Data channel → Blob | `cut_request {role, fromMs, toMs}`, one camera, up to 10 minutes, time burned in; `media_progress` while the car encodes |

The car's WebRTC stack is [Pion](https://github.com/pion/webrtc) (pure Go), bound to Kotlin with gomobile in `libs/oaartc`. It shares no `org.webrtc` classes with the GeckoView engine that hosts the in-car UI, and it has no native dependencies beyond the Go runtime (arm64, armv7 and x86_64, API 21+). `./gradlew :oaartc:assemble` downloads the pinned Go toolchain (`oaa.goVersion` in `gradle.properties`) into `~/.gradle/oaa-toolchains`. It needs the NDK named by `oaa.ndkVersion`. `cd libs/oaartc && go test ./...` runs a loopback session (several H.264 tracks plus the data channel) without a device.

The in-car UI connects to the car's own Pion stack over loopback. The app starts GeckoView with `media.peerconnection.ice.loopback` on and host-address obfuscation off so those candidates pair.

## Data channel

The car's first data-channel message is `dc_hello {features, cameras, tracks, carUtcMs, maxCutMs, maxTransfers, chunkMaxBytes}`; the SPA only uses what is advertised.

- **Camera selection:** `live_select {roles}` sends only the visible cameras (the whole grid, or the one camera tapped to view alone), live or replayed; omitting `roles` selects all. The car answers with `live_tracks {roles, streaming}` whenever the set of live cameras changes. A newly selected camera starts with a key frame; a viewer's PLI/FIR asks only that camera's encoder for one.
- **Car clock:** `clock_sync {t0}` → `clock {t0, carUtcMs}`. The SPA keeps the lowest round-trip sample of five at connect and again every minute, and uses it to unwrap RTP timestamps.
- **Replay:** see [Recorded playback](#recorded-playback).
- **Pause:** hiding the tab sends `live_pause` (the car stops sending live frames); showing it sends `live_resume`, and the car starts again with a key frame. Leaving Cameras hangs up the session, which also ends a replay.

## Timestamps

RTP time on every camera track follows the car clock, live or replayed, so the browser's jitter buffer never sees a jump.

- **Live:** each frame's RTP timestamp (`requestVideoFrameCallback` metadata, else the receiver's synchronization source), unwrapped against the car clock, is its capture time.
- **Replayed:** the same unwrapped RTP time, mapped through the anchor of its replay epoch: `wallMs + (rtp − rtpMs) × speed`.
- **Exported:** burned into the picture by the car (`yyyy-MM-dd HH:mm:ss`, car time zone) while it re-encodes the clip.

## Recorded playback

Recordings are groups: one `oaa_dvr_<stamp>_<role>.mp4` per camera covering the same wall-clock window. The car replays them over the camera tracks the session already has, so the browser needs nothing beyond WebRTC (no Media Source Extensions, which iPhones lack) and the hub relays nothing extra.

- `replay_start {atMs, speed?, paused?}` switches the selected cameras from live to their recordings at wall time `atMs` (the active group is sealed first; a time in a gap snaps to the next recording). `replay_seek {atMs}`, `replay_pause`, `replay_resume` and `replay_speed {speed}` (0.25–4) control it; `replay_stop` goes back to live.
- The car reads each file's H.264 samples and sends them on that camera's track when due: every camera follows one clock, so the tiles stay in step without the browser syncing anything. A seek sends each camera's frames from the preceding key frame up to the target at once, so the picture appears straight away. Playback continues into the next group and skips gaps longer than 3 s.
- `replay_state {state, atMs, speed, group?, groupStartUtcMs?, groupEndUtcMs?, roles, anchor, error?}` reports `playing`, `paused`, `ended` (no more recordings; the SPA goes live if the car is still recording), `live` (nothing sealed at that time, or the answer to `replay_stop`) or `error`. Each seek, resume, speed change or skipped gap starts an epoch: frames with RTP time ≥ `anchor.fromRtpMs` were captured at `anchor.wallMs + (rtp − anchor.rtpMs) × anchor.speed`; `anchor.live` means RTP time is the capture time again. The SPA keeps the last few anchors, since frames from an earlier epoch can still be on screen.
- Back to live, the car drops live frames captured before the replay's last RTP time, so RTP time never goes backwards.

## Error reasons

`webrtc_hangup.reason` ends the session; `media_error.error` fails one data-channel request.

| Value | Meaning |
|---|---|
| `offline` | Node is not connected to the hub |
| `unsupported` | Car app is too old or has no DVR |
| `replaced` | Another viewer opened this car's cameras (one hub viewer per car; up to three on the car itself) |
| `version` | Signaling or data-channel version mismatch; update the car app or hub |
| `busy` | `media_error` only: transfer limit reached (2 per session: a download and a cut) |
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

The hub never carries, logs or stores media. TURN relays DTLS ciphertext only. The car's signaling WebSocket sits behind the same car login as the rest of `/api/*`. In v1 any authenticated hub user can open any paired car's cameras; per-node ACLs are future work.

## Troubleshooting

- **"Car is offline"**: the car's hub connection is down; check the hub's Cars page.
- **Stuck on connecting, then ICE failure**: no usable path; configure TURN and make sure UDP 3478 and the relay port range are reachable.
- **One tile stays on "Waiting for camera…"**: that camera did not open or its encoder did not start; `GET /api/status` → `dvr.cameras[]` (`running`, `encoder`, `fps`) and `dvr.stream.camera2Probe` show why.
- **Black tiles, live and recordings**: the browser rejected the H.264 profile (the car sends Constrained Baseline 3.1).
- **On the head unit only, ICE fails**: GeckoView did not load `geckoview-config.yaml` (loopback candidates disabled).

## Not covered

Hub SFU or recording, hub-to-hub federation, cabin audio, head-unit screen share, and WebRTC for the Home Assistant `camera` entity are out of scope and may reuse this plane later.
