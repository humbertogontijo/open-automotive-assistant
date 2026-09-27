#!/usr/bin/env bash
# Hub helpers (sourced): adb-free installs through the hub's OTA rollouts.
#
#   OAA_HUB_URL        hub human face, e.g. http://hub.local:8787 (required)
#   OAA_HUB_TOKEN      admin session or system token (skips login)
#   OAA_HUB_USER       admin username for login (password from OAA_HUB_PASSWORD or prompt)
#   OAA_HUB_TOKEN_FILE token cache (default ~/.config/oaa/hub-token, mode 600)

OAA_HUB_TOKEN_FILE="${OAA_HUB_TOKEN_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/oaa/hub-token}"
OAA_HUB_DEPLOY_TIMEOUT="${OAA_HUB_DEPLOY_TIMEOUT:-900}"
HUB_TOKEN=""

hub_base() {
  [[ -n "${OAA_HUB_URL:-}" ]] || die "Set OAA_HUB_URL (e.g. http://hub.local:8787)"
  printf '%s' "${OAA_HUB_URL%/}"
}

# json_get <python-expr> [args…] — evaluates the expression with d = JSON on stdin and
# a = [args…]; prints it ('' for None, JSON for lists/dicts).
json_get() {
  python3 -c '
import json, sys
d = json.load(sys.stdin)
v = eval(sys.argv[1], {"d": d, "a": sys.argv[2:]})
print("" if v is None else (json.dumps(v) if isinstance(v, (dict, list)) else v))
' "$@"
}

hub_curl() {
  curl -sS --fail-with-body -H "Authorization: Bearer $HUB_TOKEN" "$@"
}

hub_token_valid() {
  [[ -n "$1" ]] || return 1
  local me role
  me="$(curl -sS --fail-with-body -H "Authorization: Bearer $1" "$(hub_base)/api/auth/me" 2>/dev/null)" || return 1
  role="$(printf '%s' "$me" | json_get 'd["user"]["role"]')" || return 1
  [[ "$role" == "admin" || "$role" == "system" ]]
}

hub_login() {
  local user="${OAA_HUB_USER:-}" pass="${OAA_HUB_PASSWORD:-}" body res token
  if [[ -z "$user" ]]; then
    [[ -t 0 ]] || die "Set OAA_HUB_TOKEN, or OAA_HUB_USER + OAA_HUB_PASSWORD"
    read -r -p "Hub admin username: " user
  fi
  if [[ -z "$pass" ]]; then
    [[ -t 0 ]] || die "Set OAA_HUB_PASSWORD for $user"
    read -r -s -p "Hub password for $user: " pass
    echo
  fi
  body="$(python3 -c 'import json,sys; print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))' "$user" "$pass")"
  res="$(curl -sS --fail-with-body -H 'Content-Type: application/json' -d "$body" "$(hub_base)/api/auth/login")" \
    || die "Hub login failed: $res"
  token="$(printf '%s' "$res" | json_get 'd.get("token")')"
  [[ -n "$token" ]] || die "Hub login returned no token"
  mkdir -p "$(dirname "$OAA_HUB_TOKEN_FILE")"
  (umask 077 && printf '%s\n' "$token" >"$OAA_HUB_TOKEN_FILE")
  HUB_TOKEN="$token"
}

hub_auth() {
  require_cmd curl
  require_cmd python3
  if [[ -n "${OAA_HUB_TOKEN:-}" ]]; then
    hub_token_valid "$OAA_HUB_TOKEN" || die "OAA_HUB_TOKEN is not an admin token for $(hub_base)"
    HUB_TOKEN="$OAA_HUB_TOKEN"
  elif [[ -f "$OAA_HUB_TOKEN_FILE" ]] && hub_token_valid "$(head -n1 "$OAA_HUB_TOKEN_FILE")"; then
    HUB_TOKEN="$(head -n1 "$OAA_HUB_TOKEN_FILE")"
  else
    hub_login
    hub_token_valid "$HUB_TOKEN" || die "Hub user is not an admin"
  fi
  ok "Hub $(hub_base) authenticated"
}

# Prints the JSON array of target node ids.
hub_resolve_targets() {
  local node="$1" all="$2" fleet
  fleet="$(hub_curl "$(hub_base)/api/nodes")" || die "Cannot list hub nodes: $fleet"
  if [[ "$all" == "1" ]]; then
    printf '%s' "$fleet" | json_get '[n["id"] for n in d["nodes"]]'
  elif [[ -n "$node" ]]; then
    printf '%s' "$fleet" | json_get '[n["id"] for n in d["nodes"] if n["id"] == a[0]] or None' "$node" | grep . \
      || die "Unknown node '$node'. Paired: $(printf '%s' "$fleet" | json_get '", ".join(n["id"] for n in d["nodes"])')"
  else
    local count
    count="$(printf '%s' "$fleet" | json_get 'len(d["nodes"])')"
    [[ "$count" == "1" ]] \
      || die "Pass --node ID or --all (paired: $(printf '%s' "$fleet" | json_get '", ".join(n["id"] for n in d["nodes"]) or "none"'))"
    printf '%s' "$fleet" | json_get '[n["id"] for n in d["nodes"]]'
  fi
}

apk_meta() {
  local meta
  meta="$(dirname "$1")/output-metadata.json"
  [[ -f "$meta" ]] || die "Missing $meta (build with gradle first)"
  json_get "$2" <"$meta"
}

hub_upload() {
  local apk="$1" pkg vname vcode res
  pkg="$(apk_meta "$apk" 'd["applicationId"]')"
  vname="$(apk_meta "$apk" 'd["elements"][0].get("versionName")')"
  vcode="$(apk_meta "$apk" 'd["elements"][0].get("versionCode")')"
  log "Uploading $(basename "$apk") ($pkg $vname/$vcode)" >&2
  # No `Expect: 100-continue`: Ktor CIO garbles the final response after it on large bodies.
  res="$(hub_curl -X POST \
    -H 'Expect:' \
    -H 'Content-Type: application/vnd.android.package-archive' \
    -H "X-Oaa-Package: $pkg" \
    -H "X-Oaa-Version-Name: $vname" \
    -H "X-Oaa-Version-Code: $vcode" \
    --data-binary "@$apk" \
    "$(hub_base)/api/ota/artifacts")" || die "Upload failed: $res"
  printf '%s' "$res" | json_get 'd["artifact"]["sha256"]'
}

hub_rollout() {
  local sha="$1" targets="$2" body res
  body="$(python3 -c 'import json,sys; print(json.dumps({"artifact": sys.argv[1], "nodes": json.loads(sys.argv[2])}))' "$sha" "$targets")"
  res="$(hub_curl -X POST -H 'Content-Type: application/json' -d "$body" "$(hub_base)/api/ota/rollouts")" \
    || die "Rollout failed: $res"
  printf '%s' "$res" | json_get 'd["rollout"]["id"]'
}

hub_wait_rollout() {
  local id="$1" deadline=$((SECONDS + OAA_HUB_DEPLOY_TIMEOUT)) last="" now res
  while ((SECONDS < deadline)); do
    res="$(hub_curl "$(hub_base)/api/ota/rollouts/$id")" || die "Rollout status failed: $res"
    now="$(printf '%s' "$res" | json_get '"  ".join(k + "=" + v["state"] + (" " + str(v["progress"]) + "%" if v.get("progress") is not None and v["state"] == "downloading" else "") + (" (" + v["error"] + ")" if v.get("error") else "") for k, v in sorted(d["targets"].items()))')"
    [[ "$now" != "$last" ]] && log "$now"
    last="$now"
    if [[ "$(printf '%s' "$res" | json_get 'd["done"]')" == "True" ]]; then
      if [[ "$(printf '%s' "$res" | json_get 'any(v["state"] == "failed" for v in d["targets"].values())')" == "True" ]]; then
        die "Rollout $id finished with failures"
      fi
      ok "Rollout $id installed"
      return 0
    fi
    sleep 3
  done
  die "Rollout $id still running after ${OAA_HUB_DEPLOY_TIMEOUT}s (offline cars get it when they reconnect)"
}

# hub_deploy [--node ID|--all] [--no-build] [--no-wait]
hub_deploy() {
  local node="" all=0 build=1 wait=1
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --node|-n) node="$2"; shift 2 ;;
      --all) all=1; shift ;;
      --no-build) build=0; shift ;;
      --no-wait) wait=0; shift ;;
      *) die "hub-deploy: unknown option $1" ;;
    esac
  done
  hub_auth
  local targets sha id
  targets="$(hub_resolve_targets "$node" "$all")"
  if [[ "$build" == "1" ]]; then
    oaa_build
    oaa_sign
  else
    oaa_ensure_apk
  fi
  sha="$(hub_upload "$OAA_APK_SIGNED")"
  ok "Artifact $sha"
  id="$(hub_rollout "$sha" "$targets")"
  ok "Rollout $id → $targets"
  [[ "$wait" == "1" ]] && hub_wait_rollout "$id"
  return 0
}
