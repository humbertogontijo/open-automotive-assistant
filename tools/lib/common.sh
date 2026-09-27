#!/usr/bin/env bash
# Shared helpers for oaa-setup (sourced).

log()  { printf '➜ %s\n' "$*"; }
ok()   { printf '✓ %s\n' "$*"; }
warn() { printf '⚠ %s\n' "$*" >&2; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

gradlew() {
  if [[ -x "$ROOT/gradlew" ]]; then
    "$ROOT/gradlew" "$@"
  else
    require_cmd gradle
    gradle "$@"
  fi
}

load_integration() {
  local id="$1"
  local file=""
  if [[ -f "$ROOT/integrations/${id}/host.sh" ]]; then
    file="$ROOT/integrations/${id}/host.sh"
  else
    die "Unknown integration '$id' (expected integrations/${id}/host.sh)"
  fi
  # shellcheck source=/dev/null
  source "$file"
  : "${OAA_ADB_PORT:?integration must set OAA_ADB_PORT}"
  : "${OAA_ANDROID_USER:?}"
  : "${OAA_PACKAGE:?}"
  : "${OAA_ACTIVITY:?}"
  local host_disp="${OAA_HOST:-<set -H or OAA_HOST>}"
  ok "Integration: $id  host=$host_disp:$OAA_ADB_PORT  user=$OAA_ANDROID_USER  pkg=$OAA_PACKAGE"
}

require_host() {
  [[ -n "${OAA_HOST:-}" ]] || die "Set the HU address: --host / -H CAR_IP  (or export OAA_HOST)"
}

adb_s() {
  require_host
  adb -s "${OAA_HOST}:${OAA_ADB_PORT}" "$@"
}

oaa_connect() {
  require_cmd adb
  require_host
  local serial="${OAA_HOST}:${OAA_ADB_PORT}"
  if ! adb devices | grep -q "^${serial}[[:space:]]"; then
    log "adb connect $serial"
    adb connect "$serial" >/dev/null
    sleep 1
  fi
  adb devices | grep -q "^${serial}[[:space:]]device" || die "Device not connected: $serial"
  ok "Connected $serial"
}

oaa_build() {
  log "Building :app:assembleDebug"
  (
    cd "$ROOT"
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
    gradlew :app:assembleDebug -Poaa.arm64Only --quiet
  )
  ok "Built $OAA_APK_DEBUG"
}

oaa_sign() {
  local debug_apk="${OAA_APK_DEBUG:?}"
  local signed_apk="${OAA_APK_SIGNED:?}"
  [[ -f "$debug_apk" ]] || die "APK missing — run build first: $debug_apk"
  log "Signing with :signing:signApk (community testkey)"
  (
    cd "$ROOT"
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
    gradlew -q :signing:signApk \
      "-Pin=$debug_apk" \
      "-Pout=$signed_apk" \
      "-Pkey=$ROOT/libs/signing/community.pk8" \
      "-Pcert=$ROOT/libs/signing/community.pem"
  ) || die "Gradle signApk failed"
  [[ -f "$signed_apk" ]] || die "Signed APK not produced: $signed_apk"
  ok "Signed $signed_apk"
}

oaa_ensure_apk() {
  if [[ -f "$OAA_APK_DEBUG" ]]; then
    [[ -f "$OAA_APK_SIGNED" && ! "$OAA_APK_DEBUG" -nt "$OAA_APK_SIGNED" ]] || oaa_sign
  elif [[ ! -f "$OAA_APK_SIGNED" ]]; then
    oaa_build
    oaa_sign
  fi
}

oaa_start() {
  log "Starting $OAA_PACKAGE/$OAA_ACTIVITY (user $OAA_ANDROID_USER)"
  adb_s shell am force-stop --user "$OAA_ANDROID_USER" "$OAA_PACKAGE" 2>/dev/null || true
  adb_s shell am start --user "$OAA_ANDROID_USER" -n "${OAA_PACKAGE}/${OAA_ACTIVITY}"
}
