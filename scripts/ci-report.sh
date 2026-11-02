#!/usr/bin/env bash
# Builds ci-report/REPORT.md, the GitHub step summary and error annotations,
# including the likely cause of any failure. Reads outcomes from env vars.
set -u
REPORT_DIR="${REPORT_DIR:-ci-report}"; DIAG="${DIAG_DIR:-diagnostics}"
mkdir -p "$REPORT_DIR"
R="$REPORT_DIR/REPORT.md"
icon() { case "$1" in success|PASS) echo "✅ PASS";; failure|FAIL) echo "❌ FAIL";; skipped|"") echo "⏭️ SKIPPED";; *) echo "⚠️ $1";; esac; }
annotate() { # title, message (multi-line ok)
  local m="${2//'%'/'%25'}"; m="${m//$'\r'/}"; m="${m//$'\n'/'%0A'}"
  echo "::error title=$1::${m:0:1500}"
}
gradle_cause() { # $1=log file -> prints the most relevant failure lines
  local f="$1"; [[ -s "$f" ]] || { echo "(no log captured)"; return; }
  { grep -E -A8 '^\* What went wrong:' "$f" | head -n 30
    grep -E '^e: |error: |Execution failed for task|Could not resolve|Could not find|Manifest merger failed|SDK location not found|R8: |Missing class|AAPT: error|Lint found [0-9]+ errors|tests completed, [0-9]+ failed|FAILED$' "$f" | sort -u | head -n 25
  } | awk 'NF && !seen[$0]++'
}

{
  echo "# AniVault CI Report"; echo
  echo "- **Commit:** \`${GITHUB_SHA:-local}\`"
  echo "- **Ref:** \`${GITHUB_REF_NAME:-local}\`"
  echo "- **Run:** [#${GITHUB_RUN_NUMBER:-0}](${GITHUB_SERVER_URL:-}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-0})"
  echo "- **Finished (UTC):** $(date -u '+%Y-%m-%d %H:%M:%S')"
  echo "- **Signing:** Android debug keystore, for both the debug and FOSS release APKs"
  echo; echo "## Results"; echo
  echo "| Stage | Result |"; echo "|---|---|"
  echo "| Build debug + FOSS release APKs | $(icon "${BUILD_OUTCOME:-}") |"
  echo "| Verify APK signatures | $(icon "${VERIFY_OUTCOME:-}") |"
  echo "| Unit tests + lint | $(icon "${CHECKS_OUTCOME:-}") |"
  echo "| Emulator smoke tests | $(icon "${SMOKE_OUTCOME:-}") |"
} > "$R"

if [[ -s "$DIAG/smoke-results.tsv" ]]; then
  { echo; echo "## Emulator smoke test (API ${EMULATOR_API:-34})"; echo
    echo "| APK | Result | Details |"; echo "|---|---|---|"
    while IFS=$'\t' read -r l s d; do echo "| $l | $(icon "$s") | $d |"; done < "$DIAG/smoke-results.tsv"
  } >> "$R"
fi
if [[ -s "$REPORT_DIR/APK-METADATA.md" ]]; then
  { echo; echo "## APKs"; echo; cat "$REPORT_DIR/APK-METADATA.md"; } >> "$R"
fi

failed=0
for kv in ${SETUP_OUTCOMES:-}; do
  if [[ "${kv#*=}" == failure ]]; then
    failed=1
    { echo; echo "## ❌ Failure cause: setup step \`${kv%%=*}\` failed"
      echo "The job stopped before building anything. Open the failed step in the run log for the error (usually a network/download problem fetching tools)."; } >> "$R"
    annotate "Setup failed: ${kv%%=*}" "A setup step failed before the build started. Open that step in the run log."
  fi
done
if [[ $failed -eq 0 && "${JOB_STATUS:-}" == failure && "${BUILD_OUTCOME:-}" != failure && "${VERIFY_OUTCOME:-}" != failure && "${CHECKS_OUTCOME:-}" != failure && "${SMOKE_OUTCOME:-}" != failure ]]; then
  failed=1
  { echo; echo "## ❌ Failure cause: an earlier step failed"; echo "The job failed outside the build/verify/test/smoke stages. See the failed step in the run log."; } >> "$R"
  annotate "Job failed" "A step failed outside the build/verify/test/smoke stages. See the run log."
fi
if [[ "${BUILD_OUTCOME:-}" == failure ]]; then
  failed=1; cause="$(gradle_cause "$DIAG/gradle-build.log")"
  { echo; echo "## ❌ Failure cause: Gradle build"; echo '```text'; echo "$cause"; echo '```'
    echo "<details><summary>Last 80 lines of the Gradle log</summary>"; echo; echo '```text'; tail -n 80 "$DIAG/gradle-build.log" 2>/dev/null; echo '```'; echo "</details>"; } >> "$R"
  annotate "Gradle build failed" "$cause"
fi
if [[ "${VERIFY_OUTCOME:-}" == failure ]]; then
  failed=1; cause="$(tail -n 30 "$DIAG/verify-apks.log" 2>/dev/null)"
  { echo; echo "## ❌ Failure cause: APK verification"; echo '```text'; echo "$cause"; echo '```'; } >> "$R"
  annotate "APK verification failed" "$cause"
fi
if [[ "${CHECKS_OUTCOME:-}" == failure ]]; then
  failed=1; cause="$(gradle_cause "$DIAG/gradle-checks.log")"
  { echo; echo "## ❌ Failure cause: unit tests / lint"; echo '```text'; echo "$cause"; echo '```'
    echo "Full reports are in the artifact: \`app/build/reports/\`."; } >> "$R"
  annotate "Unit tests or lint failed" "$cause"
fi
if [[ "${SMOKE_OUTCOME:-}" == failure ]]; then
  failed=1
  if [[ -s "$DIAG/smoke-results.tsv" ]]; then
    cause="$(grep -P '\tFAIL\t' "$DIAG/smoke-results.tsv" | tr '\t' ' ')"
    { echo; echo "## ❌ Failure cause: emulator smoke test"; echo '```text'; echo "$cause"; echo '```'
      for f in "$DIAG"/logcat-*.txt; do [[ -f "$f" ]] || continue
        ex="$(grep -E -B1 -A14 'FATAL EXCEPTION|Fatal signal|ANR in com\.anivault' "$f" | head -n 40)"
        [[ -n "$ex" ]] && { echo "### Crash excerpt: \`$(basename "$f")\`"; echo '```text'; echo "$ex"; echo '```'; }
      done; } >> "$R"
  else
    cause="Emulator never produced results: it failed to boot/start or the smoke script did not run. Check the 'Emulator smoke test' step output."
    { echo; echo "## ❌ Failure cause: emulator"; echo "$cause"; } >> "$R"
  fi
  annotate "Emulator smoke test failed" "$cause"
fi
[[ $failed -eq 0 ]] && { echo; echo "All stages passed."; } >> "$R"

[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && cat "$R" >> "$GITHUB_STEP_SUMMARY"
exit 0
