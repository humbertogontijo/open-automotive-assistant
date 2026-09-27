# Home Assistant custom component

Copy `open_automotive_assistant/` into your HA `config/custom_components/` (or add this repo path as a HACS custom repository of type Integration).

1. Restart Home Assistant
2. Settings → Devices & services → Add Integration → **Open Automotive Assistant**
3. Enter hub host (and optional `node_id` when using a multi-car hub)

Requires a running OAA hub (`deploy/docker` or HAOS app) or a car on the LAN. See [docs/hub.md](../../docs/hub.md).
