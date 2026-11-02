# Android CI: debug + FOSS release

Workflow: `.github/workflows/android-build-smoke-test.yml`

## What it does

1. Sets up JDK 17, the Android SDK and the Gradle wrapper (8.7), and creates/prints the Android **debug signing key**.
2. Builds `:app:assembleDebug` and `:app:assembleRelease` (the FOSS release: minified, signed with the debug key).
3. `scripts/verify-apks.sh` checks both APKs are validly signed **with the debug key** and records SHA-256, size and package info.
4. Runs `:app:testDebugUnitTest` and `:app:lintDebug` separately, so a lint/test failure does not hide runtime problems.
5. Boots an API 34 emulator (KVM-accelerated) and runs `scripts/smoke-test.sh` on each APK: install, launch `MainActivity`, process alive after 10s, no crash/ANR in Logcat, screenshot + UI dump, then 120 seeded monkey inputs with crash/ANR detection.
6. `scripts/ci-report.sh` writes `ci-report/REPORT.md` and the run's Summary page, and emits error annotations.
7. Uploads two artifacts: `anivault-apks-<run>` (the APKs) and `anivault-reports-<run>` (report, logs, Logcat, screenshots, R8 mapping, unit-test and lint reports). The run fails if any stage failed.

## Failure causes

When a stage fails, the Summary page and `REPORT.md` contain a "Failure cause" section: Gradle's `What went wrong` block and compiler/resolution/R8 errors, the failing verification line, lint/test failures, or the smoke-test reason plus the crash stack from Logcat. The same text is shown as an error annotation on the run. Full logs are in `diagnostics/`.

## Where to get outputs

Open the GitHub Actions run and download `anivault-apks-<run number>` for the APKs or `anivault-reports-<run number>` for the reports. The report is `ci-report/REPORT.md`; build and emulator details are in `diagnostics/`.

## What the smoke test means

This is a startup smoke test, not a full UI/integration test. It confirms that each APK installs, launches the declared launcher activity, and keeps its process alive for a short interval. It does not verify every screen, network source, biometric flow, or export operation.

## Signing note

Android's debug signing key is generated per CI runner, so different workflow runs may use different debug keys. That's acceptable for a downloadable FOSS test build, but an APK installed over a build signed with a different key cannot update it in place. Uninstall the old build first, or configure a stable release keystore if persistent in-place updates are required. Never use the debug key for Google Play publishing.
