# Android CI: debug + FOSS release

Workflow: `.github/workflows/android-build-smoke-test.yml`

## What it does

1. Uses JDK 17, Android SDK, and Gradle 8.7.
2. Builds `:app:assembleDebug` and `:app:assembleRelease`.
3. Runs `:app:testDebugUnitTest` and `:app:lintDebug`.
4. Signs both APKs using the Android-generated debug signing key. The FOSS release is minified but is **not** a Play Store release and should not be used for a production update channel.
5. Boots an API 34 Android emulator, installs each APK separately, starts `com.anivault/.MainActivity`, and checks that the app process remains alive and the activity appears in the activity dump.
6. Uploads APKs, checksums, Gradle logs, emulator logs, Logcat, lint results, unit-test reports, and a Markdown report as one Actions artifact.
7. On build failures, includes common Gradle error signatures and the final 100 lines of the build log in the report. On smoke-test failures, captures Logcat and activity diagnostics.

## Where to get outputs

Open the GitHub Actions run and download the `anivault-ci-<run number>` artifact. The report is `ci-report/REPORT.md`; build and emulator details are in `diagnostics/`.

## What the smoke test means

This is a startup smoke test, not a full UI/integration test. It confirms that each APK installs, launches the declared launcher activity, and keeps its process alive for a short interval. It does not verify every screen, network source, biometric flow, or export operation.

## Signing note

Android's debug signing key is generated per CI runner, so different workflow runs may use different debug keys. That's acceptable for a downloadable FOSS test build, but an APK installed over a build signed with a different key cannot update it in place. Uninstall the old build first, or configure a stable release keystore if persistent in-place updates are required. Never use the debug key for Google Play publishing.
