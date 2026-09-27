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
if [ -n "${SUPERVISOR_TOKEN:-}" ]; then
  ha_url="$(opt public_node_url)"
  if [ -z "$ha_url" ]; then ha_url="$(java -cp /app/oaa-hub.jar:/app/addon HaPublicUrl 60 || true)"; fi
  if [ -n "$ha_url" ]; then export OAA_PUBLIC_NODE_URL="${ha_url%/}/api/oaa_node"; fi
fi
export_opt OAA_STUN_URLS stun_urls
export_opt OAA_TURN_URLS turn_urls
export_opt OAA_TURN_SECRET turn_secret
export_opt OAA_TURN_TTL turn_ttl

exec java -jar /app/oaa-hub.jar --data "$DATA" "$@"
