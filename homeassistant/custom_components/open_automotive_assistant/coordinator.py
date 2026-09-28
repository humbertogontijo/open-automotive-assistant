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
from .hub import OaaHubClient, fleet_cars

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
            if status.get("role") == "hub" and not self.node_id:
                self._adopt_only_car(status)
                status = await self.client.get_status(self.node_id)
            entities = await self.client.list_entities(self.node_id)
        except UpdateFailed:
            raise
        except Exception as err:
            raise UpdateFailed(str(err)) from err
        by_id = {e.get("id"): e for e in entities if e.get("id")}
        return {"status": status, "entities": entities, "by_id": by_id}

    def _adopt_only_car(self, status: dict[str, Any]) -> None:
        """Entries added without a car: use the hub's only car, else ask to re-add the hub."""
        cars = fleet_cars(status)
        if len(cars) != 1:
            raise UpdateFailed(
                "the hub has no paired car" if not cars else "the hub has several cars; remove this entry and add the hub again to pick one"
            )
        self.node_id = cars[0]["id"]
        self.hass.config_entries.async_update_entry(self.entry, data={**self.entry.data, CONF_NODE_ID: self.node_id})
