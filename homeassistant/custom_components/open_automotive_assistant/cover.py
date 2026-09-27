"""Cover platform."""

from __future__ import annotations

from homeassistant.components.cover import CoverEntity, CoverEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_flag

_CLOSED = frozenset({"closed", "0", "false"})


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaCover, "cover")


class OaaCover(OaaEntity, CoverEntity):
    _attr_supported_features = CoverEntityFeature.OPEN | CoverEntityFeature.CLOSE

    @property
    def is_closed(self) -> bool | None:
        return row_flag(self._row(), _CLOSED)

    async def async_open_cover(self, **kwargs) -> None:
        await self._async_write("open")

    async def async_close_cover(self, **kwargs) -> None:
        await self._async_write("closed")
