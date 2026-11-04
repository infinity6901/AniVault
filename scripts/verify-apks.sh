#!/usr/bin/env bash
# Verifies both APKs exist, are validly signed, and are signed with the Android debug key.
# Usage: scripts/verify-apks.sh <debug.apk> <release.apk>   Writes ci-report/APK-METADATA.md
set -euo pipefail
REPORT_DIR="${REPORT_DIR:-ci-report}"; mkdir -p "$REPORT_DIR"
KS="${DEBUG_KEYSTORE:-${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore}"
BT="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"/build-tools/* | sort -V | tail -1)"
# Normalize fingerprints to bare upper-case hex so keytool (AB:CD:..) and apksigner (abcd..) output compare equal.
norm() { tr -d ': \t\r' | tr 'a-f' 'A-F'; }
want="$(keytool -list -v -keystore "$KS" -storepass android -alias androiddebugkey | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | norm)"
[[ ${#want} -eq 64 ]] || { echo "Could not read debug key SHA-256 from $KS (got '$want')"; exit 1; }
echo "Debug keystore SHA-256: $want"
: > "$REPORT_DIR/APK-METADATA.md"
for apk in "$@"; do
  [[ -s "$apk" ]] || { echo "Missing or empty APK: $apk"; exit 1; }
  "$BT/apksigner" verify --verbose "$apk"
  got="$("$BT/apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | norm)"
  [[ "$got" == "$want" ]] || { echo "Signing mismatch for $apk: got $got, expected debug key $want"; exit 1; }
  badge="$("$BT/aapt2" dump badging "$apk" | head -1)"
  {
    echo "**$(basename "$apk")**"; echo
    echo "- SHA-256: \`$(sha256sum "$apk" | cut -d' ' -f1)\`"
    echo "- Size: $(du -h "$apk" | cut -f1)"
    echo "- Signed with Android debug key: \`${got:0:16}…\`"
    echo "- \`$badge\`"; echo
  } >> "$REPORT_DIR/APK-METADATA.md"
done
echo "APK verification OK"
