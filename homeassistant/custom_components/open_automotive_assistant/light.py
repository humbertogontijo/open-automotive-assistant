"""Light platform."""

from __future__ import annotations

from homeassistant.components.light import ColorMode, LightEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_flag


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaLight, "light")


class OaaLight(OaaEntity, LightEntity):
    _attr_color_mode = ColorMode.ONOFF
    _attr_supported_color_modes = {ColorMode.ONOFF}

    @property
    def is_on(self) -> bool | None:
        return row_flag(self._row())

    async def async_turn_on(self, **kwargs) -> None:
        await self._async_write("on")

    async def async_turn_off(self, **kwargs) -> None:
        await self._async_write("off")
