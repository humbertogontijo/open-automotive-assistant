# ADR-0004: Car authentication and hub-initiated pairing

## Status

Accepted

Doc index: [../README.md](../README.md). Builds on [ADR-0003](0003-hub.md).

## Context

The car's HTTP server (`:8787`) served its full control API to anyone on the LAN: any phone on the car's hotspot, any device on the home Wi-Fi when parked, and any other app on the head unit could read telemetry, move windows or unlock doors. The hub link was also car-driven only: the owner typed a hub URL and code on the head unit, even though the hub is the natural place to manage a fleet.

We want the same rule on both sides of the link: a new device (phone, laptop, hub or script) is trusted only after someone types a code that the car shows on its own screen. The head unit UI itself must keep working with no prompt, and other apps on the head unit must not ride on its loopback access.

## Decision

### 1. Who may call the car

Every `/api/…` and `/debug…` call resolves a caller. Without one the answer is `401 {"ok":false,"error":"pairing required"}`.

| Caller | Credential | Notes |
|---|---|---|
| Head unit UI (`hu`) | Per-launch local key, swapped for a session cookie | See §3 |
| Browser (`browser`) | `oaa_car` HttpOnly cookie (car-issued token, ~400 days) | Paired with a code |
| Hub (`hub`) | Car-issued token (`Authorization: Bearer`) | Paired with a code; also listed after a manual join |
| Tool (`tool`) | Car-issued token (`Authorization: Bearer`) | `oaa-setup pair` over adb (§5) |
| Hub RPC replay (`internal`) | `X-Oaa-Internal` per-launch secret, loopback only | The node session replays hub requests in-process |

Open without a caller: `/api/health`, `/api/i18n`, `/api/auth/status`, `/api/auth/pair/request`, `/api/auth/pair/confirm`, `/api/auth/local`, the SPA shell and static assets.

Tokens are 256-bit random values. The car stores only their SHA-256 (`files/oaa/auth-clients.json`); head unit sessions live in memory only.

### 2. Pairing with a code shown on the car

1. The new device calls `POST /api/auth/pair/request {kind: browser | hub | tool, name, hubId?}`.
2. The car creates a 6-digit code (3 minutes, 5 attempts, one open request per source address, at most 5 open) and shows it on the head unit: an in-app dialog (`pair_request` event, head unit sockets only) and a high-priority Android notification (`oaa_pair` channel).
3. The person types the code on the new device: `POST /api/auth/pair/confirm {requestId, code}` from the **same address** that asked. Codes are compared in constant time.
4. The car answers with a token (in the body, or as the `oaa_car` cookie for browsers).

The code is never sent to anyone but the head unit. The head unit can decline a request (`DELETE /api/auth/pair/pending/{id}`) and list or revoke trusted devices (`/api/auth/clients`). Revoking a hub also drops the hub link.

### 3. The head unit UI

`MainActivity` generates nothing itself: `CarAuth` holds a random local key for the process lifetime. Top-level GeckoView loads carry it as `X-Oaa-Local-Key` (`GeckoSession.Loader.additionalHeaders`, unrestricted header filter). The server accepts the key only from loopback and answers with an HttpOnly `oaa_car` session cookie, so page scripts never see the key. If the session is lost (a 401 from the API), the page fires `oaa:reauth`; the `oaa-ext` content script relays it and the activity reloads the page with the key.

Other apps on the head unit reach `127.0.0.1:8787` too, but they have neither the key nor a session, so they see the same pairing gate as a LAN device.

### 4. Hub-initiated pairing

The hub is the primary place to add cars:

1. Unpaired cars announce `_oaa-car._tcp` over mDNS (TXT `id`, `name`, `integration`, `version`) and stop once paired to a hub.
2. The hub browses for them (`GET /api/nodes/discovered`, admin, paired cars hidden). Admins can also add by address.
3. `POST /api/nodes/invite {nodeId | host, port?}`: the hub calls the car's `pair/request` with `kind=hub`, its name and its stable id (`data/hub.json`).
4. The admin types the car's code: `POST /api/nodes/invite/{id}/confirm {code}`. The hub pre-registers a node token and sends it in the car's `pair/confirm` as `hub {hubId, hubName, nodeToken, nodePort, nodeUrls, sessionPath}`. A failed confirm restores the previous registry entry.
5. The car stores the link, with the hub's source address on `nodePort` first and the published node URLs next, and dials the node face as in ADR-0003. The session rotates to the next URL when one never opens. The hub keeps the car's token and LAN URL for later direct calls.

The manual flow (code from the hub typed on the car) stays for cars that are not on the hub's network; that hub is also listed under trusted devices.

### 5. Host tools

`oaa-setup pair` mints a tool token through an exported `AuthProvider` (`content call --method mint`). The provider checks `android.permission.DUMP` in `call()` (the framework does not check provider permissions there); only the adb shell holds it. `tools/lib/permissions.sh` caches the token per host and re-mints it when it was revoked.

### Forbidden

- Showing a pairing code anywhere but the head unit
- Accepting the local key or the internal secret from a non-loopback address
- Storing car tokens in plain text on the car
- Advertising `_oaa-car._tcp` while paired to a hub

## Consequences

- LAN scripts and the Home Assistant component no longer reach the car directly without a token; they go through the hub or `oaa-setup pair`.
- The HAOS app runs with `host_network` for mDNS; compose users choose host networking or add cars by address.
- A reinstall or data wipe forgets every trusted device; they pair again.
- Tokens do not expire. Revocation is manual from the head unit.
