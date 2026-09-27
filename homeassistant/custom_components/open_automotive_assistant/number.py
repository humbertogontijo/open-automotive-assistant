"""Number platform."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_float


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaNumber, "number")


class OaaNumber(OaaEntity, NumberEntity):
    @property
    def native_value(self) -> float | None:
        return row_float(self._row())

    async def async_set_native_value(self, value: float) -> None:
        await self._async_write(value)
