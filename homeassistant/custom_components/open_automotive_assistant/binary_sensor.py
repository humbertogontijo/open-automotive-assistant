"""Binary sensor platform."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import TRUTHY, OaaEntity, async_setup_dynamic, row_flag

_ON = TRUTHY | {"open"}


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaBinarySensor, "binary_sensor")


class OaaBinarySensor(OaaEntity, BinarySensorEntity):
    @property
    def is_on(self) -> bool | None:
        return row_flag(self._row(), _ON)
