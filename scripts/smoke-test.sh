#!/usr/bin/env bash
# Emulator smoke test for AniVault APKs. Run while an emulator is booted.
# Usage: scripts/smoke-test.sh "<label>=<apk>" ["<label>=<apk>" ...]
# Writes: diagnostics/smoke-results.tsv (label<TAB>PASS|FAIL<TAB>reason), logcat, screenshots, UI dumps.
set -u
APP_ID="${APP_ID:-com.anivault}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-com.anivault/.MainActivity}"
OUT="${DIAG_DIR:-diagnostics}"
mkdir -p "$OUT"
RESULTS="$OUT/smoke-results.tsv"
: > "$RESULTS"
CRASH_RE='FATAL EXCEPTION|Fatal signal [0-9]+|ANR in '"${APP_ID//./\\.}"'|am_crash'

record() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$RESULTS"; echo ">>> $1: $2 - $3"; }

crash_excerpt() { # $1=logcat file
  grep -E -B1 -A14 'FATAL EXCEPTION|Fatal signal|ANR in '"${APP_ID//./\\.}" "$1" | head -n 60
}

smoke_one() {
  local label="$1" apk="$2" safe
  safe="$(echo "$label" | tr -c 'a-zA-Z0-9_\n-' '_')"
  local log="$OUT/smoke-$safe.log" logcat="$OUT/logcat-$safe.txt"
  {
    echo "=== $label ($apk) ==="
    [[ -f "$apk" ]] || { record "$label" FAIL "APK not found: $apk"; return; }

    adb shell getprop sys.boot_completed | tr -d '\r' | grep -q 1 || { record "$label" FAIL "emulator not booted"; return; }
    adb uninstall "$APP_ID" >/dev/null 2>&1 || true
    adb logcat -c

    local out
    if ! out="$(adb install -r -g "$apk" 2>&1)" || ! grep -q Success <<<"$out"; then
      echo "$out"; adb logcat -d -v threadtime > "$logcat" || true
      record "$label" FAIL "install failed: $(tr '\n' ' ' <<<"$out" | cut -c1-200)"; return
    fi
    echo "$out"

    out="$(adb shell am start -W -n "$MAIN_ACTIVITY" 2>&1)"; echo "$out"
    if grep -Eqi 'Error|Exception|does not exist' <<<"$out" || ! grep -q 'Status: ok' <<<"$out"; then
      adb logcat -d -v threadtime > "$logcat" || true
      record "$label" FAIL "launch failed: $(tr '\n' ' ' <<<"$out" | cut -c1-200)"; return
    fi

    sleep 10
    adb exec-out screencap -p > "$OUT/screenshot-$safe.png" 2>/dev/null || true
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui-$safe.xml" >/dev/null 2>&1 || true
    adb shell dumpsys activity activities > "$OUT/activities-$safe.txt" 2>&1 || true
    adb logcat -d -v threadtime > "$logcat" || true

    if grep -Eq "$CRASH_RE" "$logcat" && grep -Eq "Process: ${APP_ID//./\\.}|ANR in ${APP_ID//./\\.}|Fatal signal" "$logcat"; then
      record "$label" FAIL "crash/ANR detected after launch (see logcat-$safe.txt)"; crash_excerpt "$logcat"; return
    fi
    if ! adb shell pidof "$APP_ID" | tr -d '\r' | grep -Eq '[0-9]'; then
      record "$label" FAIL "app process not running 10s after launch"; return
    fi
    if ! grep -q "$APP_ID" "$OUT/activities-$safe.txt"; then
      record "$label" FAIL "app activity not found in activity dump"; return
    fi

    # Light UI stress: pseudo-random input, fixed seed. Fails only on crash/ANR.
    adb logcat -c
    local monkey; monkey="$(adb shell monkey -p "$APP_ID" -s 42 --throttle 250 --ignore-timeouts --ignore-security-exceptions 120 2>&1)"
    echo "$monkey" | tail -n 15
    adb logcat -d -v threadtime > "$OUT/logcat-$safe-monkey.txt" || true
    if grep -Eq '// CRASH|// NOT RESPONDING' <<<"$monkey" || grep -Eq "Process: ${APP_ID//./\\.}|ANR in ${APP_ID//./\\.}" "$OUT/logcat-$safe-monkey.txt"; then
      record "$label" FAIL "crash/ANR under monkey input (see logcat-$safe-monkey.txt)"; crash_excerpt "$OUT/logcat-$safe-monkey.txt"; return
    fi
    adb exec-out screencap -p > "$OUT/screenshot-$safe-after-monkey.png" 2>/dev/null || true
    record "$label" PASS "installed, launched, process alive, no crash/ANR, survived 120 monkey events"
    adb shell am force-stop "$APP_ID" || true
  } 2>&1 | tee "$log"
}

[[ $# -gt 0 ]] || { echo "usage: $0 label=apk ..." >&2; exit 2; }
for spec in "$@"; do smoke_one "${spec%%=*}" "${spec#*=}"; done
echo; echo "=== Summary ==="; cat "$RESULTS"
! grep -q $'\tFAIL\t' "$RESULTS"
