"""HTTP client for the OAA hub / local protocol."""

from __future__ import annotations

from typing import Any

from aiohttp import ClientSession


class OaaHubClient:
    def __init__(
        self,
        session: ClientSession,
        host: str,
        port: int,
        token: str | None = None,
    ) -> None:
        self._session = session
        self._base = f"http://{host}:{port}"
        self._token = token

    def _headers(self, node_id: str | None) -> dict[str, str]:
        headers: dict[str, str] = {}
        if node_id:
            headers["X-Oaa-Node"] = node_id
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        return headers

    async def get_status(self, node_id: str | None = None) -> dict[str, Any]:
        async with self._session.get(
            f"{self._base}/api/status",
            headers=self._headers(node_id),
            timeout=15,
        ) as resp:
            resp.raise_for_status()
            return await resp.json()

    async def list_entities(self, node_id: str | None = None) -> list[dict[str, Any]]:
        async with self._session.get(
            f"{self._base}/api/entities",
            headers=self._headers(node_id),
            timeout=20,
        ) as resp:
            resp.raise_for_status()
            data = await resp.json()
            return data if isinstance(data, list) else []

    async def list_nodes(self) -> list[dict[str, Any]]:
        async with self._session.get(
            f"{self._base}/api/nodes",
            headers=self._headers(None),
            timeout=15,
        ) as resp:
            resp.raise_for_status()
            data = await resp.json()
            return (data or {}).get("nodes") or []

    async def report_public_node(self, url: str, session_path: str) -> dict[str, Any]:
        """Hub only (admin token): publish where away cars dial the node face."""
        async with self._session.post(
            f"{self._base}/api/nodes/public-url",
            headers=self._headers(None),
            json={"url": url, "sessionPath": session_path},
            timeout=15,
        ) as resp:
            resp.raise_for_status()
            return await resp.json()

    async def set_entity(
        self,
        entity_id: str,
        value: Any,
        node_id: str | None = None,
    ) -> dict[str, Any]:
        async with self._session.post(
            f"{self._base}/api/controls/{entity_id}",
            headers=self._headers(node_id),
            data={"value": str(value)},
            timeout=20,
        ) as resp:
            resp.raise_for_status()
            try:
                return await resp.json(content_type=None)
            except Exception:
                return {"ok": True}
