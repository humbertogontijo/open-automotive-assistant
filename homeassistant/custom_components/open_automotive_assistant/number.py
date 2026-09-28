"""Number platform."""

from __future__ import annotations

from homeassistant.components.number import (
    DEFAULT_MAX_VALUE,
    DEFAULT_MIN_VALUE,
    DEFAULT_STEP,
    NumberEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_float


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaNumber, "number")


class OaaNumber(OaaEntity, NumberEntity):
    def _bound(self, key: str, default: float) -> float:
        try:
            return float((self._row() or {}).get(key))
        except (TypeError, ValueError):
            return default

    @property
    def native_value(self) -> float | None:
        return row_float(self._row())

    @property
    def native_min_value(self) -> float:
        return self._bound("min", DEFAULT_MIN_VALUE)

    @property
    def native_max_value(self) -> float:
        return self._bound("max", DEFAULT_MAX_VALUE)

    @property
    def native_step(self) -> float:
        return self._bound("step", DEFAULT_STEP)

    async def async_set_native_value(self, value: float) -> None:
        await self._async_write(value)
