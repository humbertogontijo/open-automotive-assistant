"""Shared entity base and platform setup for OAA catalog rows."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import OaaDataUpdateCoordinator

TRUTHY = frozenset({"1", "true", "on", "yes"})


def row_value(row: dict[str, Any] | None) -> Any:
    """Current value of a catalog row: `state`, falling back to `value`."""
    row = row or {}
    return row.get("state") if row.get("state") is not None else row.get("value")


def row_flag(row: dict[str, Any] | None, on: frozenset[str] | set[str] = TRUTHY) -> bool | None:
    """Row value as a boolean; None when unknown."""
    val = row_value(row)
    if val is None or val == "":
        return None
    if isinstance(val, bool):
        return val
    return str(val).lower() in on


def row_float(row: dict[str, Any] | None, *attr_keys: str) -> float | None:
    """First numeric attribute among [attr_keys], or the row value when none given."""
    row = row or {}
    if attr_keys:
        attrs = row.get("attributes") or {}
        candidates = [attrs[k] for k in attr_keys if k in attrs]
    else:
        candidates = [row_value(row)]
    for val in candidates:
        try:
            return float(val)
        except (TypeError, ValueError):
            continue
    return None


def async_setup_dynamic(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
    factory: Callable[[OaaDataUpdateCoordinator, str, dict[str, Any]], OaaEntity],
    match: str | Callable[[dict[str, Any]], bool],
) -> None:
    """Add matching rows now and whenever the catalog grows. [match] is a domain or a predicate."""
    coordinator: OaaDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    matches = (lambda row: row.get("domain") == match) if isinstance(match, str) else match
    known: set[str] = set()

    @callback
    def _discover() -> None:
        fresh = []
        for row in (coordinator.data or {}).get("entities") or []:
            eid = row.get("id")
            if not eid or eid in known or not matches(row):
                continue
            known.add(eid)
            fresh.append(factory(coordinator, eid, row))
        if fresh:
            async_add_entities(fresh)

    _discover()
    entry.async_on_unload(coordinator.async_add_listener(_discover))


class OaaEntity(CoordinatorEntity[OaaDataUpdateCoordinator]):
    _attr_has_entity_name = True

    def __init__(self, coordinator: OaaDataUpdateCoordinator, entity_id: str, row: dict[str, Any]) -> None:
        super().__init__(coordinator)
        self._oaa_id = entity_id
        self._attr_unique_id = f"{coordinator.entry.entry_id}:{entity_id}"
        self._attr_name = str(row.get("friendlyName") or row.get("label") or entity_id)
        host = coordinator.entry.data.get("host", "oaa")
        node = coordinator.node_id or "local"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, f"{host}:{node}")},
            name=f"OAA {node}",
            manufacturer="Open Automotive Assistant",
            model=(coordinator.data or {}).get("status", {}).get("integration") or "vehicle",
        )

    def _row(self) -> dict[str, Any] | None:
        return ((self.coordinator.data or {}).get("by_id") or {}).get(self._oaa_id)

    @property
    def available(self) -> bool:
        row = self._row()
        return bool(row) and row.get("available") is not False

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        row = self._row() or {}
        attrs = dict(row.get("attributes") or {})
        attrs["oaa_id"] = self._oaa_id
        attrs["oaa_status"] = row.get("status")
        return attrs

    async def _async_write(self, value: Any) -> None:
        await self.coordinator.client.set_entity(self._oaa_id, value, self.coordinator.node_id)
        await self.coordinator.async_request_refresh()
