"""Lock platform."""

from __future__ import annotations

from homeassistant.components.lock import LockEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import TRUTHY, OaaEntity, async_setup_dynamic, row_flag

_LOCKED = TRUTHY | {"locked"}


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaLock, "lock")


class OaaLock(OaaEntity, LockEntity):
    @property
    def is_locked(self) -> bool | None:
        return row_flag(self._row(), _LOCKED)

    async def async_lock(self, **kwargs) -> None:
        await self._async_write("locked")

    async def async_unlock(self, **kwargs) -> None:
        await self._async_write("unlocked")
