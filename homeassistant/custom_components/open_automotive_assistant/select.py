"""Select platform."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_value


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaSelect, "select")


class OaaSelect(OaaEntity, SelectEntity):
    @property
    def current_option(self) -> str | None:
        val = row_value(self._row())
        return None if val is None else str(val)

    @property
    def options(self) -> list[str]:
        attrs = (self._row() or {}).get("attributes") or {}
        return [str(o) for o in attrs.get("options") or attrs.get("choices") or []]

    async def async_select_option(self, option: str) -> None:
        await self._async_write(option)
