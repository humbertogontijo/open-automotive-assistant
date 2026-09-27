"""Sensor platform — catalog rows no other platform claims."""

from __future__ import annotations

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_value

# Domains owned by a dedicated platform, or not representable as a sensor.
_NOT_SENSOR = frozenset(
    {
        "binary_sensor",
        "switch",
        "climate",
        "lock",
        "cover",
        "number",
        "select",
        "light",
        "camera",
        "media_player",
    }
)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(
        hass,
        entry,
        async_add_entities,
        OaaSensor,
        lambda row: row.get("domain") not in _NOT_SENSOR,
    )


class OaaSensor(OaaEntity, SensorEntity):
    @property
    def native_value(self):
        return row_value(self._row())

    @property
    def native_unit_of_measurement(self):
        return (self._row() or {}).get("unitOfMeasurement")
