"""Light platform.

OAA lights already use the HA scales (brightness 0–255, `rgb_color` [r, g, b]); the node maps
native ranges, so values pass through unchanged.
"""

from __future__ import annotations

from typing import Any

from homeassistant.components.light import (
    ATTR_BRIGHTNESS,
    ATTR_EFFECT,
    ATTR_RGB_COLOR,
    ColorMode,
    LightEntity,
    LightEntityFeature,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import OaaEntity, async_setup_dynamic, row_flag, row_float

_MODES = {"onoff": ColorMode.ONOFF, "brightness": ColorMode.BRIGHTNESS, "rgb": ColorMode.RGB}


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_setup_dynamic(hass, entry, async_add_entities, OaaLight, "light")


class OaaLight(OaaEntity, LightEntity):
    def _attrs(self) -> dict[str, Any]:
        return (self._row() or {}).get("attributes") or {}

    def _effects(self) -> dict[str, str]:
        """Effect name → OAA option value."""
        out: dict[str, str] = {}
        for opt in (self._row() or {}).get("options") or []:
            value = str(opt.get("value"))
            out[str(opt.get("label") or opt.get("labelKey") or value)] = value
        return out

    @property
    def supported_color_modes(self) -> set[ColorMode]:
        modes = {_MODES[m] for m in self._attrs().get("supported_color_modes") or [] if m in _MODES}
        return modes or {ColorMode.ONOFF}

    @property
    def color_mode(self) -> ColorMode:
        return _MODES.get(str(self._attrs().get("color_mode")), next(iter(self.supported_color_modes)))

    @property
    def supported_features(self) -> LightEntityFeature:
        return LightEntityFeature.EFFECT if self._effects() else LightEntityFeature(0)

    @property
    def is_on(self) -> bool | None:
        state = row_flag(self._row())
        if state is not None:
            return state
        bri = self.brightness
        return None if bri is None else bri > 0

    @property
    def brightness(self) -> int | None:
        bri = row_float(self._row(), "brightness")
        return None if bri is None else int(bri)

    @property
    def rgb_color(self) -> tuple[int, int, int] | None:
        rgb = self._attrs().get("rgb_color")
        if isinstance(rgb, (list, tuple)) and len(rgb) == 3:
            return int(rgb[0]), int(rgb[1]), int(rgb[2])
        return None

    @property
    def effect_list(self) -> list[str] | None:
        return list(self._effects()) or None

    @property
    def effect(self) -> str | None:
        current = self._attrs().get("effect")
        return next((name for name, value in self._effects().items() if value == str(current)), None)

    async def async_turn_on(self, **kwargs) -> None:
        wrote = False
        if (effect := kwargs.get(ATTR_EFFECT)) is not None and effect in self._effects():
            await self._async_write(f"effect:{self._effects()[effect]}")
            wrote = True
        if (rgb := kwargs.get(ATTR_RGB_COLOR)) is not None:
            await self._async_write("rgb_color:" + ",".join(str(int(c)) for c in rgb))
            wrote = True
        if (bri := kwargs.get(ATTR_BRIGHTNESS)) is not None:
            await self._async_write(f"brightness:{int(bri)}")
        elif not wrote or not self.is_on:
            await self._async_write("on")

    async def async_turn_off(self, **kwargs) -> None:
        await self._async_write("off")
