"""Config flow for Open Automotive Assistant hub."""

from __future__ import annotations

from typing import Any

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.components import zeroconf
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import CONF_NODE_ID, CONF_NODE_PORT, CONF_TOKEN, DEFAULT_NODE_PORT, DEFAULT_PORT, DOMAIN
from .hub import OaaHubClient

_PASSWORD = selector.TextSelector(selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD))


def _unique_id(host: str, port: int, node_id: str | None) -> str:
    return f"{host}:{port}:{node_id or 'default'}"


def _user_schema(defaults: dict[str, Any] | None = None) -> vol.Schema:
    d = defaults or {}
    return vol.Schema(
        {
            vol.Required(CONF_HOST, default=d.get(CONF_HOST, vol.UNDEFINED)): str,
            vol.Optional(CONF_PORT, default=d.get(CONF_PORT, DEFAULT_PORT)): int,
            vol.Optional(CONF_NODE_PORT, default=d.get(CONF_NODE_PORT, DEFAULT_NODE_PORT)): int,
            vol.Optional(CONF_NODE_ID, default=d.get(CONF_NODE_ID) or ""): selector.TextSelector(),
            vol.Optional(CONF_TOKEN): _PASSWORD,
        }
    )


_CONFIRM_SCHEMA = vol.Schema(
    {
        vol.Optional(CONF_NODE_ID): selector.TextSelector(),
        vol.Optional(CONF_TOKEN): _PASSWORD,
    }
)


class OaaConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._discovered: dict[str, Any] = {}

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        if user_input is None:
            return self.async_show_form(step_id="user", data_schema=_user_schema())
        return await self._async_try_create(user_input)

    async def async_step_zeroconf(self, discovery_info: zeroconf.ZeroconfServiceInfo) -> FlowResult:
        host = discovery_info.host
        port = discovery_info.port or DEFAULT_PORT
        props = discovery_info.properties or {}
        await self.async_set_unique_id(_unique_id(host, port, None))
        self._abort_if_unique_id_configured(updates={CONF_HOST: host, CONF_PORT: port})
        self._discovered = {
            CONF_HOST: host,
            CONF_PORT: port,
            CONF_NODE_PORT: int(props.get("node_port") or DEFAULT_NODE_PORT),
        }
        self.context["title_placeholders"] = {"name": f"OAA @ {host}"}
        return await self.async_step_zeroconf_confirm()

    async def async_step_zeroconf_confirm(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        if user_input is not None:
            return await self._async_try_create({**self._discovered, **user_input})
        return self.async_show_form(
            step_id="zeroconf_confirm",
            description_placeholders={"host": self._discovered.get(CONF_HOST, "")},
            data_schema=_CONFIRM_SCHEMA,
        )

    async def _async_try_create(self, user_input: dict[str, Any]) -> FlowResult:
        data = {
            CONF_HOST: user_input[CONF_HOST].strip(),
            CONF_PORT: int(user_input.get(CONF_PORT) or DEFAULT_PORT),
            CONF_NODE_PORT: int(user_input.get(CONF_NODE_PORT) or DEFAULT_NODE_PORT),
            CONF_NODE_ID: (user_input.get(CONF_NODE_ID) or "").strip() or None,
            CONF_TOKEN: (user_input.get(CONF_TOKEN) or "").strip() or None,
        }
        client = OaaHubClient(async_get_clientsession(self.hass), data[CONF_HOST], data[CONF_PORT], token=data[CONF_TOKEN])
        error: str | None = None
        try:
            status = await client.get_status(data[CONF_NODE_ID])
            if status.get("role") not in ("hub", "local"):
                error = "invalid_role"
        except Exception:  # noqa: BLE001
            error = "cannot_connect"
        if error:
            return self.async_show_form(step_id="user", data_schema=_user_schema(data), errors={"base": error})

        await self.async_set_unique_id(
            _unique_id(data[CONF_HOST], data[CONF_PORT], data[CONF_NODE_ID]),
            raise_on_progress=False,
        )
        self._abort_if_unique_id_configured()
        node_id = data[CONF_NODE_ID]
        title = f"OAA {node_id}@{data[CONF_HOST]}" if node_id else f"OAA {data[CONF_HOST]}"
        return self.async_create_entry(title=title, data=data)
