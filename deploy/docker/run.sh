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
export_opt OAA_PUBLIC_NODE_URL public_node_url
# The app's public URL is Home Assistant's; cars reach the node face through the HACS bridge.
if [ -n "$(opt public_node_url)" ] && [ -z "${OAA_SESSION_PATH:-}" ]; then
  export OAA_SESSION_PATH=/api/oaa_node/session
fi
export_opt OAA_STUN_URLS stun_urls
export_opt OAA_TURN_URLS turn_urls
export_opt OAA_TURN_SECRET turn_secret
export_opt OAA_TURN_TTL turn_ttl

exec java -jar /app/oaa-hub.jar --data "$DATA" "$@"
