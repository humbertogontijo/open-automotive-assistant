"""HA Cloud / Ingress bridge: narrow node-face proxy for away cars.

Reverse-proxies pairing, the node session WebSocket and OTA artifact
downloads to the hub node port (8788). Cars authenticate with node bearer
tokens, so the views use requires_auth=False: cars that cannot log into HA
still dial home over Nabu Casa TLS. Views are registered once per HA run and
resolve the hub from the most recently set-up config entry.
"""

from __future__ import annotations

import asyncio
import logging

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .cloud_paths import (
    HUB_ARTIFACTS_PATH,
    HUB_PAIR_PATH,
    HUB_SESSION_PATH,
    NODE_ARTIFACTS_PATH,
    NODE_PAIR_PATH,
    NODE_SESSION_PATH,
    bearer_token,
    is_sha256,
    node_public_urls,
)
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

DATA_TARGET = "cloud_target"
DATA_URLS = "cloud_bridge"

_ARTIFACT_HEADERS = ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")


def _hub_base(hass: HomeAssistant, scheme: str = "http") -> str | None:
    target = hass.data.get(DOMAIN, {}).get(DATA_TARGET)
    if not target:
        return None
    host, node_port = target
    return f"{scheme}://{host}:{node_port}"


def _no_hub() -> web.Response:
    return web.json_response({"ok": False, "error": "no hub configured"}, status=503)


class OaaNodePairView(HomeAssistantView):
    url = NODE_PAIR_PATH
    name = "api:oaa_node:pair"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass

    async def post(self, request: web.Request) -> web.Response:
        base = _hub_base(self._hass)
        if base is None:
            return _no_hub()
        session = async_get_clientsession(self._hass)
        try:
            async with session.post(
                f"{base}{HUB_PAIR_PATH}",
                data=await request.read(),
                headers={"Content-Type": request.content_type or "application/json"},
                timeout=ClientTimeout(total=30),
            ) as resp:
                return web.Response(
                    body=await resp.read(),
                    status=resp.status,
                    content_type=resp.content_type or "application/json",
                )
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("OAA node pair proxy failed: %s", err)
            return web.json_response({"ok": False, "error": "hub unreachable"}, status=502)


class OaaNodeSessionView(HomeAssistantView):
    url = NODE_SESSION_PATH
    name = "api:oaa_node:session"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass

    async def get(self, request: web.Request) -> web.StreamResponse:
        token = bearer_token(request.query.get("token"), request.headers.get("Authorization"))
        if not token:
            return web.json_response({"ok": False, "error": "missing token"}, status=401)
        base = _hub_base(self._hass, "ws")
        if base is None:
            return _no_hub()

        session: ClientSession = async_get_clientsession(self._hass)
        client_ws = web.WebSocketResponse(heartbeat=30)
        await client_ws.prepare(request)

        async def pump(src, dst) -> None:
            async for msg in src:
                if msg.type == WSMsgType.TEXT:
                    await dst.send_str(msg.data)
                elif msg.type == WSMsgType.BINARY:
                    await dst.send_bytes(msg.data)
                elif msg.type in (WSMsgType.CLOSE, WSMsgType.ERROR):
                    break

        try:
            async with session.ws_connect(
                f"{base}{HUB_SESSION_PATH}",
                headers={"Authorization": f"Bearer {token}"},
                heartbeat=30,
            ) as hub_ws:
                tasks = [
                    asyncio.create_task(pump(client_ws, hub_ws)),
                    asyncio.create_task(pump(hub_ws, client_ws)),
                ]
                _, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("OAA node session proxy failed: %s", err)
            if not client_ws.closed:
                await client_ws.close(code=1011, message=b"hub unreachable")

        if not client_ws.closed:
            await client_ws.close()
        return client_ws


class OaaNodeArtifactView(HomeAssistantView):
    """Streams an OTA APK from the hub, forwarding Range so cars can resume."""

    url = NODE_ARTIFACTS_PATH + "/{sha}"
    name = "api:oaa_node:artifacts"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass

    async def get(self, request: web.Request, sha: str) -> web.StreamResponse:
        if not is_sha256(sha):
            return web.json_response({"ok": False, "error": "invalid artifact id"}, status=400)
        token = bearer_token(request.query.get("token"), request.headers.get("Authorization"))
        if not token:
            return web.json_response({"ok": False, "error": "missing token"}, status=401)
        base = _hub_base(self._hass)
        if base is None:
            return _no_hub()

        headers = {"Authorization": f"Bearer {token}"}
        if "Range" in request.headers:
            headers["Range"] = request.headers["Range"]
        session = async_get_clientsession(self._hass)
        try:
            async with session.get(
                f"{base}{HUB_ARTIFACTS_PATH}/{sha.lower()}",
                headers=headers,
                timeout=ClientTimeout(total=None, sock_connect=15, sock_read=60),
            ) as resp:
                out = web.StreamResponse(status=resp.status)
                for name in _ARTIFACT_HEADERS:
                    if name in resp.headers:
                        out.headers[name] = resp.headers[name]
                await out.prepare(request)
                async for chunk in resp.content.iter_chunked(64 * 1024):
                    await out.write(chunk)
                await out.write_eof()
                return out
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("OAA artifact proxy failed: %s", err)
            return web.json_response({"ok": False, "error": "hub unreachable"}, status=502)


def async_register_cloud_views(hass: HomeAssistant) -> None:
    hass.http.register_view(OaaNodePairView(hass))
    hass.http.register_view(OaaNodeSessionView(hass))
    hass.http.register_view(OaaNodeArtifactView(hass))


async def async_resolve_cloud_url(hass: HomeAssistant) -> str | None:
    """Best-effort Nabu Casa remote UI base URL."""
    try:
        from homeassistant.components.cloud import async_remote_ui_url

        url = async_remote_ui_url(hass)
        if url:
            return url.rstrip("/")
    except Exception:  # noqa: BLE001
        pass
    if hass.config.external_url:
        return str(hass.config.external_url).rstrip("/")
    return None


async def async_set_cloud_target(hass: HomeAssistant, hub_host: str, node_port: int) -> dict[str, str]:
    """Point the bridge views at this hub and return published Cloud URLs."""
    data = hass.data.setdefault(DOMAIN, {})
    data[DATA_TARGET] = (hub_host, node_port)
    cloud_base = await async_resolve_cloud_url(hass)
    urls = node_public_urls(cloud_base) or {}
    data[DATA_URLS] = urls
    _LOGGER.info("OAA Cloud node face → %s:%s (base=%s)", hub_host, node_port, cloud_base or "local-only")
    return urls
