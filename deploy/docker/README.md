# Open Automotive Assistant — Docker hub

```bash
cd deploy/docker
docker compose up -d --build
```

UI: `http://HOST:8787`

Pair cars from the Cars page (generate code) then Settings → Hub on each car.

Profiles:

| Profile | Adds |
|---|---|
| `tunnel` | Cloudflare Tunnel for off-LAN cars and browsers |
| `proxy` | Caddy with TLS and node-face path ACLs |
| `turn` | coturn relay for remote Cameras (WebRTC) behind strict NAT |

See [REMOTE.md](./REMOTE.md), [docs/hub.md](../../docs/hub.md) and [docs/webrtc.md](../../docs/webrtc.md).
