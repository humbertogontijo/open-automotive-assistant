"""Switch platform."""

from __future__ import annotations

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_flag


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaSwitch, "switch")


class OaaSwitch(OaaEntity, SwitchEntity):
    @property
    def is_on(self) -> bool | None:
        return row_flag(self._row())

    async def async_turn_on(self, **kwargs) -> None:
        await self._async_write("on")

    async def async_turn_off(self, **kwargs) -> None:
        await self._async_write("off")
