#!/usr/bin/env bash
# Install helpers: user-space /data install (sourced).
#
#   OAA_FULL=1          always push the whole APK (same as --full)
#   OAA_APK_CACHE       installed APKs kept as delta bases (default ~/.cache/oaa/apks)
#   OAA_DELTA_MAX_PCT   push the full APK when the delta exceeds this share of it (default 70)

OAA_APK_CACHE="${OAA_APK_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/oaa/apks}"
OAA_DELTA_MAX_PCT="${OAA_DELTA_MAX_PCT:-70}"

host_sha256() { shasum -a 256 "$1" | awk '{print $1}'; }

human_bytes() { awk -v b="$1" 'BEGIN { printf "%.1f MB", b / 1048576 }'; }

# Installed base.apk for the target user ('' when not installed).
oaa_installed_apk() {
  { adb_s shell pm path --user "$OAA_ANDROID_USER" "$OAA_PACKAGE" 2>/dev/null || true; } \
    | tr -d '\r' | sed -n 's/^package://p' | grep '/base\.apk$' | head -n1 || true
}

oaa_device_sha256() {
  { adb_s shell sha256sum "$1" 2>/dev/null || true; } | tr -d '\r' | awk '{print $1}'
}

# Keep the just-installed APK as the base for the next delta (last 3).
oaa_cache_apk() {
  local sha
  sha="$(host_sha256 "$OAA_APK_SIGNED")"
  mkdir -p "$OAA_APK_CACHE"
  cp -f "$OAA_APK_SIGNED" "$OAA_APK_CACHE/$sha.apk"
  # shellcheck disable=SC2012
  ls -t "$OAA_APK_CACHE"/*.apk 2>/dev/null | tail -n +4 | while IFS= read -r f; do rm -f "$f"; done
}

# Rebuild the signed APK at <remote> on the HU from the installed build plus a delta.
# Returns non-zero (caller pushes the full APK) whenever that is not possible.
oaa_stage_delta() {
  local remote="$1" base base_sha target_sha old dir patch_size full_size got
  [[ "${OAA_FULL:-0}" == "1" ]] && return 1
  base="$(oaa_installed_apk)"
  [[ -n "$base" ]] || { log "No installed build for user $OAA_ANDROID_USER — full push"; return 1; }
  base_sha="$(oaa_device_sha256 "$base")"
  target_sha="$(host_sha256 "$OAA_APK_SIGNED")"
  [[ -n "$base_sha" ]] || { warn "Could not hash $base on the HU — full push"; return 1; }
  if [[ "$base_sha" == "$target_sha" ]]; then
    log "HU already has this build — reinstalling it from $base"
    adb_s shell cp "$base" "$remote" || return 1
    return 0
  fi
  old="$OAA_APK_CACHE/$base_sha.apk"
  [[ -f "$old" ]] || { log "Installed build ${base_sha:0:12} is not in $OAA_APK_CACHE — full push"; return 1; }

  dir="$(mktemp -d)"
  log "Computing delta from installed build ${base_sha:0:12}"
  if ! (
    cd "$ROOT"
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
    gradlew -q :apk-delta:diff "-Pold=$old" "-Pnew=$OAA_APK_SIGNED" "-Pout=$dir" >/dev/null
  ); then
    warn "Delta generation failed — full push"
    rm -rf "$dir"
    return 1
  fi
  patch_size="$(wc -c <"$dir/patch.oadp" | tr -d ' ')"
  full_size="$(wc -c <"$OAA_APK_SIGNED" | tr -d ' ')"
  if ((patch_size * 100 > full_size * OAA_DELTA_MAX_PCT)); then
    log "Delta is $(human_bytes "$patch_size") of $(human_bytes "$full_size") — full push"
    rm -rf "$dir"
    return 1
  fi

  log "Pushing delta ($(human_bytes "$patch_size") instead of $(human_bytes "$full_size"))"
  local rpatch="/data/local/tmp/oaa-install.oadp" rscript="/data/local/tmp/oaa-apply.sh"
  if ! adb_s push "$dir/patch.oadp" "$rpatch" >/dev/null || ! adb_s push "$dir/apply.sh" "$rscript" >/dev/null; then
    warn "Delta push failed — full push"
    rm -rf "$dir"
    return 1
  fi
  rm -rf "$dir"
  log "Rebuilding APK on the HU"
  adb_s shell sh "$rscript" "$base" "$rpatch" "$remote" || true
  adb_s shell rm -f "$rpatch" "$rscript" 2>/dev/null || true
  got="$(oaa_device_sha256 "$remote")"
  if [[ "$got" != "$target_sha" ]]; then
    warn "Rebuilt APK hash mismatch (${got:0:12} != ${target_sha:0:12}) — full push"
    adb_s shell rm -f "$remote" 2>/dev/null || true
    return 1
  fi
  ok "Delta applied (${target_sha:0:12})"
}

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

  if ! oaa_stage_delta "$remote"; then
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
  fi

  log "Installing to /data (user $OAA_ANDROID_USER) via pm"
  local out=""
  if ! out="$(adb_s shell pm install -r --user "$OAA_ANDROID_USER" "$remote" 2>&1)"; then
    # Some HUs reject --user on install; fall back
    warn "pm install --user failed (${out%%$'\n'*}); retrying without --user"
    out="$(adb_s shell pm install -r "$remote" 2>&1)" || die "pm install failed: $out"
  fi
  if printf '%s\n' "$out" | grep -qi 'success'; then
    oaa_cache_apk
  else
    warn "pm install output: $out"
  fi
  adb_s shell rm -f "$remote" 2>/dev/null || true
  ok "Data install done"
  oaa_start
}

oaa_uninstall() {
  log "Uninstall $OAA_PACKAGE"
  adb_s uninstall "$OAA_PACKAGE" 2>/dev/null || true
}
