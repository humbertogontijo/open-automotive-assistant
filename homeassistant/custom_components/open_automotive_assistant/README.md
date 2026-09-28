# Home Assistant integration

Exports car entities into Home Assistant and relays away cars to the hub over Home Assistant's public URL (Nabu Casa).

1. The HAOS app copies it into `config/custom_components/` when it starts. With a docker compose hub, copy `open_automotive_assistant/` there yourself (or add this repo path as a HACS custom repository of type Integration).
2. Restart Home Assistant.
3. Settings → Devices & services: a hub on the LAN shows up as discovered → **Add**. Otherwise Add Integration → **Open Automotive Assistant** and enter the hub host (`127.0.0.1` for the HAOS app).
4. Paste the hub token from the hub UI → Settings → **Integration token** (admin). Each entry is one car: a hub with one car uses it, a hub with several asks which one (add the hub again for each other car).

Requires a running OAA hub (`deploy/docker` or HAOS app) or a car on the LAN. See [docs/hub.md](../../../docs/hub.md).
