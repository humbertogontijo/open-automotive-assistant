#!/bin/sh
# Hub entrypoint. Under Home Assistant, app options arrive in /data/options.json
# and are mapped to OAA_* env vars; elsewhere the file is absent and env wins.
set -eu
DATA="${OAA_DATA:-/data}"
mkdir -p "$DATA"

OPTIONS="$DATA/options.json"
opt() {
  [ -f "$OPTIONS" ] || return 0
  sed -n \
    -e "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" \
    -e "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" \
    "$OPTIONS" | head -n 1
}
export_opt() {
  value="$(opt "$2")"
  if [ -n "$value" ]; then export "$1=$value"; fi
}
# Home Assistant app: away cars reach the hub through Home Assistant's public URL and the
# integration's /api/oaa_node bridge. The public_node_url option wins over the URL Home Assistant
# reports (Nabu Casa remote access, else its external URL).
HA_CONFIG="${OAA_HA_CONFIG:-/homeassistant}"
INTEGRATION=/app/integration/open_automotive_assistant

# Home Assistant app: keep the bundled integration (same version as this hub) in Home Assistant's
# custom_components. Home Assistant loads it after a restart; the user then adds it.
install_integration() {
  dst="$HA_CONFIG/custom_components/open_automotive_assistant"
  [ -d "$INTEGRATION" ] && [ -d "$HA_CONFIG" ] || return 0
  if [ -d "$dst" ] && diff -rq -x __pycache__ "$INTEGRATION" "$dst" >/dev/null 2>&1; then return 0; fi
  if [ -d "$dst" ]; then what=updated; else what=installed; fi
  mkdir -p "$HA_CONFIG/custom_components" && rm -rf "$dst" && cp -r "$INTEGRATION" "$dst" || {
    echo "could not copy the integration to $dst" >&2
    return 0
  }
  echo "Open Automotive Assistant integration $what in $dst" >&2
  if [ "$what" = installed ]; then
    msg="The Open Automotive Assistant integration was copied to custom_components. Restart Home Assistant, then add it under Settings → Devices & services and paste the token from the app's Settings → Integration token. Cars away from home connect through it."
  else
    msg="The Open Automotive Assistant integration was updated to match the app. Restart Home Assistant to load it."
  fi
  java -cp /app/oaa-hub.jar:/app/addon HaNotify oaa_integration "Open Automotive Assistant" "$msg" || true
}

if [ -n "${SUPERVISOR_TOKEN:-}" ]; then
  install_integration
  ha_url="$(opt public_node_url)"
  if [ -z "$ha_url" ]; then ha_url="$(java -cp /app/oaa-hub.jar:/app/addon HaPublicUrl 60 || true)"; fi
  if [ -n "$ha_url" ]; then export OAA_PUBLIC_NODE_URL="${ha_url%/}/api/oaa_node"; fi
fi
export_opt OAA_STUN_URLS stun_urls
export_opt OAA_TURN_URLS turn_urls
export_opt OAA_TURN_SECRET turn_secret
export_opt OAA_TURN_TTL turn_ttl

exec java -jar /app/oaa-hub.jar --data "$DATA" "$@"
