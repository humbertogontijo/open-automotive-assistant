#!/usr/bin/env bash
# Install helpers: user-space /data install (sourced).

# Wireless `adb install` (streamed) often hangs on Antora and then reports
# "device offline". Pushing to /data/local/tmp + `pm install` is reliable.
# Also force-stop first: a running camera FGS (concurrent Camera1 opens) can
# block package replace for a long time.
oaa_install_data() {
  [[ -f "$OAA_APK_SIGNED" ]] || die "Missing signed APK: $OAA_APK_SIGNED"
  local remote="/data/local/tmp/oaa-install.apk"
  local serial="${OAA_HOST}:${OAA_ADB_PORT}"

  log "Preparing install (force-stop $OAA_PACKAGE user $OAA_ANDROID_USER)"
  adb_s shell am force-stop --user "$OAA_ANDROID_USER" "$OAA_PACKAGE" 2>/dev/null || true
  # Best-effort: abandon any half-open PackageInstaller sessions from a prior hang.
  # (pm list sessions is missing on some HUs — must not trip `set -o pipefail`.)
  local sessions
  sessions="$(adb_s shell pm list sessions 2>/dev/null || true)"
  while IFS= read -r line; do
    local sid
    sid="$(printf '%s\n' "$line" | awk -F'[\[\\] ]+' '/Session/{print $2; exit}')"
    [[ -n "${sid:-}" ]] && adb_s shell pm abandon-session "$sid" 2>/dev/null || true
  done <<<"$sessions"

  log "Pushing APK → $remote"
  if ! adb_s push "$OAA_APK_SIGNED" "$remote"; then
    warn "adb push failed — reconnecting and retrying once"
    adb disconnect "$serial" >/dev/null 2>&1 || true
    sleep 1
    adb connect "$serial" >/dev/null
    sleep 2
    adb devices | grep -q "^${serial}[[:space:]]device" || die "Device not connected after reconnect: $serial"
    adb_s push "$OAA_APK_SIGNED" "$remote" || die "adb push failed"
  fi

  log "Installing to /data (user $OAA_ANDROID_USER) via pm"
  local out=""
  if ! out="$(adb_s shell pm install -r --user "$OAA_ANDROID_USER" "$remote" 2>&1)"; then
    # Some HUs reject --user on install; fall back
    warn "pm install --user failed (${out%%$'\n'*}); retrying without --user"
    out="$(adb_s shell pm install -r "$remote" 2>&1)" || die "pm install failed: $out"
  fi
  printf '%s\n' "$out" | grep -qi 'success' || warn "pm install output: $out"
  adb_s shell rm -f "$remote" 2>/dev/null || true
  ok "Data install done"
  oaa_start
}

oaa_uninstall() {
  log "Uninstall $OAA_PACKAGE"
  adb_s uninstall "$OAA_PACKAGE" 2>/dev/null || true
  # Best-effort cleanup if an older priv-app copy remains from prior tooling.
  if adb_s root >/dev/null 2>&1; then
    sleep 1
    adb_s remount >/dev/null 2>&1 || true
    adb_s shell "rm -rf '/system/priv-app/OpenCarAssistant' '/system/priv-app/OpenAutomotiveAssistant' '/system/etc/permissions/privapp-permissions-opencar.xml'" 2>/dev/null || true
  fi
}
