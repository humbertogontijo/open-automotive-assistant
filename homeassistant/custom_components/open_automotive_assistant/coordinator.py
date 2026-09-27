"""Data update coordinator."""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .const import CONF_NODE_ID, CONF_TOKEN, DEFAULT_PORT, DEFAULT_SCAN_INTERVAL, DOMAIN
from .hub import OaaHubClient

_LOGGER = logging.getLogger(__name__)


class OaaDataUpdateCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        super().__init__(
            hass,
            logger=_LOGGER,
            name=DOMAIN,
            update_interval=timedelta(seconds=DEFAULT_SCAN_INTERVAL),
        )
        self.entry = entry
        self.client = OaaHubClient(
            async_get_clientsession(hass),
            entry.data[CONF_HOST],
            int(entry.data.get(CONF_PORT) or DEFAULT_PORT),
            token=entry.data.get(CONF_TOKEN) or None,
        )
        self.node_id: str | None = entry.data.get(CONF_NODE_ID) or None

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            status = await self.client.get_status(self.node_id)
            entities = await self.client.list_entities(self.node_id)
        except Exception as err:
            raise UpdateFailed(str(err)) from err
        by_id = {e.get("id"): e for e in entities if e.get("id")}
        return {"status": status, "entities": entities, "by_id": by_id}
