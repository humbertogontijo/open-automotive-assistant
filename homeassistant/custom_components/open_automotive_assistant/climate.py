"""Climate platform (cabin HVAC composites)."""

from __future__ import annotations

from homeassistant.components.climate import (
    ClimateEntity,
    ClimateEntityFeature,
    HVACMode,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import ATTR_TEMPERATURE, UnitOfTemperature
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_flag, row_float

_OFF = frozenset({"off", "0", "false"})


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaClimate, "climate")


class OaaClimate(OaaEntity, ClimateEntity):
    _attr_temperature_unit = UnitOfTemperature.CELSIUS
    _attr_supported_features = ClimateEntityFeature.TARGET_TEMPERATURE
    _attr_hvac_modes = [HVACMode.OFF, HVACMode.HEAT_COOL, HVACMode.AUTO]

    @property
    def hvac_mode(self) -> HVACMode | None:
        return HVACMode.OFF if row_flag(self._row(), _OFF) else HVACMode.HEAT_COOL

    @property
    def current_temperature(self) -> float | None:
        return row_float(self._row(), "current_temperature", "currentTemp", "cabin_temp")

    @property
    def target_temperature(self) -> float | None:
        return row_float(self._row(), "temperature", "target_temperature", "targetTemp")

    async def async_set_temperature(self, **kwargs) -> None:
        temp = kwargs.get(ATTR_TEMPERATURE)
        if temp is not None:
            await self._async_write(temp)

    async def async_set_hvac_mode(self, hvac_mode: HVACMode) -> None:
        await self._async_write("off" if hvac_mode == HVACMode.OFF else "on")
