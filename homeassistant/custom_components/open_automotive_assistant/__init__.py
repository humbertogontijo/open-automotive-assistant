"""Open Automotive Assistant — Home Assistant custom component.

Discovers or connects to an OAA hub and exposes paired car entities.
Install via HACS (custom repository) or copy this folder to
config/custom_components/open_automotive_assistant.
"""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.typing import ConfigType

from .cloud_bridge import (
    async_listen_cloud_connected,
    async_register_cloud_views,
    async_report_public_node,
    async_set_cloud_target,
)
from .const import CONF_NODE_PORT, DEFAULT_NODE_PORT, DOMAIN
from .coordinator import OaaDataUpdateCoordinator

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

PLATFORMS: list[Platform] = [
    Platform.SENSOR,
    Platform.BINARY_SENSOR,
    Platform.SWITCH,
    Platform.CLIMATE,
    Platform.LOCK,
    Platform.COVER,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.LIGHT,
]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    hass.data.setdefault(DOMAIN, {})
    async_register_cloud_views(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    coordinator = OaaDataUpdateCoordinator(hass, entry)
    await coordinator.async_config_entry_first_refresh()
    hass.data[DOMAIN][entry.entry_id] = coordinator

    node_port = int(entry.data.get(CONF_NODE_PORT) or DEFAULT_NODE_PORT)
    await async_set_cloud_target(hass, entry.data[CONF_HOST], node_port)
    if await coordinator.is_hub():

        async def _report() -> None:
            await async_report_public_node(hass, coordinator.client)

        await _report()
        entry.async_on_unload(async_listen_cloud_connected(hass, _report))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_async_reload))
    return True


async def _async_reload(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return unload_ok
