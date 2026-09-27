#!/usr/bin/env bash
# Permission check / grant helpers (sourced). Compatible with macOS Bash 3.2.

OAA_RUNTIME_PERMS_DEFAULT="android.permission.CAMERA android.car.permission.CAR_SPEED android.car.permission.CAR_ENERGY"
OAA_INSTALL_PERMS_DEFAULT="android.car.permission.CAR_INFO android.car.permission.CAR_POWERTRAIN"

_runtime_perms() {
  if [[ -n "${OAA_RUNTIME_PERMS:-}" ]]; then echo "$OAA_RUNTIME_PERMS"; else echo "$OAA_RUNTIME_PERMS_DEFAULT"; fi
}
_install_perms() {
  if [[ -n "${OAA_INSTALL_PERMS:-}" ]]; then echo "$OAA_INSTALL_PERMS"; else echo "$OAA_INSTALL_PERMS_DEFAULT"; fi
}

oaa_perm_granted() {
  local perm="$1"
  adb_s shell dumpsys package "$OAA_PACKAGE" 2>/dev/null \
    | tr -d '\r' \
    | grep -F "$perm" \
    | grep -q "granted=true"
}

oaa_grant_runtime() {
  log "Granting runtime permissions (user $OAA_ANDROID_USER)"
  local p
  for p in $(_runtime_perms); do
    if adb_s shell pm grant --user "$OAA_ANDROID_USER" "$OAA_PACKAGE" "$p" 2>/dev/null; then
      ok "granted $p"
    else
      warn "could not grant $p (may already be granted or not changeable)"
    fi
  done
  # Float chip (WindowManager TYPE_APPLICATION_OVERLAY)
  if adb_s shell appops set --user "$OAA_ANDROID_USER" "$OAA_PACKAGE" SYSTEM_ALERT_WINDOW allow 2>/dev/null \
    || adb_s shell appops set "$OAA_PACKAGE" SYSTEM_ALERT_WINDOW allow 2>/dev/null; then
    ok "appops SYSTEM_ALERT_WINDOW allow"
  else
    warn "could not set SYSTEM_ALERT_WINDOW (float chip may stay hidden)"
  fi
}

oaa_check() {
  echo
  echo "=== Package ==="
  local path=""
  path="$(adb_s shell pm path --user "$OAA_ANDROID_USER" "$OAA_PACKAGE" 2>/dev/null | tr -d '\r' | head -1)"
  if [[ -z "$path" ]]; then
    # Fall back: some images omit --user on pm path
    path="$(adb_s shell pm path "$OAA_PACKAGE" 2>/dev/null | tr -d '\r' | head -1)"
  fi
  if [[ -z "$path" ]]; then
    warn "Package not installed for user $OAA_ANDROID_USER: $OAA_PACKAGE"
    return 1
  fi
  echo "  $path (user $OAA_ANDROID_USER)"
  adb_s shell dumpsys package "$OAA_PACKAGE" 2>/dev/null | tr -d '\r' \
    | grep -E 'codePath=|pkgFlags=|flags=\[' | head -8 | sed 's/^/  /'
  if echo "$path" | grep -qE '/system|/system_ext|/product|/vendor'; then
    warn "Installed on system partition — reinstall with oaa-setup setup for /data"
  else
    ok "Installed under /data (user-space)"
  fi

  echo
  echo "=== Runtime ==="
  local p
  for p in $(_runtime_perms); do
    if oaa_perm_granted "$p"; then ok "$p"; else warn "MISSING $p"; fi
  done

  echo
  echo "=== Install ==="
  for p in $(_install_perms); do
    if oaa_perm_granted "$p"; then ok "$p"; else warn "MISSING $p"; fi
  done

  echo
  echo "=== Overlay (float chip) ==="
  local ops
  ops="$(adb_s shell appops get "$OAA_PACKAGE" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r' | head -1)"
  if echo "$ops" | grep -qiE 'allow|foreground'; then
    ok "SYSTEM_ALERT_WINDOW $ops"
  else
    warn "SYSTEM_ALERT_WINDOW: ${ops:-unknown} (float chip needs allow)"
  fi

  local setup=""
  if setup="$(oaa_car_curl -f --max-time 2 "http://${OAA_HOST}:8787/api/setup" 2>/dev/null)"; then
    echo
    echo "=== Live /api/setup ==="
    echo "$setup" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  complete=%s runtimeOk=%s hasBasicTelemetry=%s accessMode=%s" % (d.get("complete"), d.get("runtimeOk"), d.get("hasBasicTelemetry"), d.get("accessMode")))' \
      2>/dev/null || true
  fi
  return 0
}

_oaa_token_cache() {
  echo "${XDG_CACHE_HOME:-$HOME/.cache}/oaa/tokens/${OAA_HOST}-${OAA_PACKAGE}"
}

# Mint a car API token over adb (AuthProvider; the shell user holds DUMP) and cache it.
oaa_mint_token() {
  local out tok cache
  out="$(adb_s shell content call --user "$OAA_ANDROID_USER" --uri "content://${OAA_PACKAGE}.auth" \
    --method mint --arg "oaa-setup@$(hostname -s 2>/dev/null || echo host)" 2>/dev/null | tr -d '\r')"
  tok="$(echo "$out" | sed -n 's/.*token=\([0-9a-f]*\).*/\1/p')"
  [[ -n "$tok" ]] || return 1
  cache="$(_oaa_token_cache)"
  mkdir -p "$(dirname "$cache")"
  (umask 077 && printf '%s' "$tok" >"$cache")
  printf '%s' "$tok"
}

oaa_car_token() {
  local cache
  cache="$(_oaa_token_cache)"
  if [[ -s "$cache" ]]; then cat "$cache"; else oaa_mint_token; fi
}

# curl against the car's API with a cached tool token; re-mints once when it was revoked.
oaa_car_curl() {
  local tok st
  tok="$(oaa_car_token)" || return 1
  st="$(curl -sf --max-time 2 -H "Authorization: Bearer $tok" "http://${OAA_HOST}:8787/api/auth/status" 2>/dev/null)" \
    || return 1
  if ! echo "$st" | grep -q '"authenticated":true'; then
    tok="$(oaa_mint_token)" || return 1
  fi
  curl -s -H "Authorization: Bearer $tok" "$@"
}

oaa_pair() {
  local tok
  tok="$(oaa_mint_token)" || die "Could not mint a token (is the app installed and running for user $OAA_ANDROID_USER?)"
  ok "Car API token saved to $(_oaa_token_cache)"
  echo "  curl -H 'Authorization: Bearer $tok' http://${OAA_HOST}:8787/api/status"
}
