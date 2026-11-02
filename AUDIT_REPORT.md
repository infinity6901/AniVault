# AniVault Code Audit and Fix Report

## Fixes applied

1. **Build configuration**
   - Added the missing Navigation Compose dependency used by `MainActivity`.
   - Added an explicit Fragment KTX dependency for `FragmentActivity` / biometric integration.
   - Fixed malformed JVM option quoting in `gradlew`.
   - The supplied `gradle-wrapper.jar` was only a 489-byte placeholder with no `GradleWrapperMain` class. Added a fallback in the Unix and Windows launchers that uses an installed Gradle or downloads the pinned Gradle 8.7 distribution.

2. **Export reliability**
   - Moved PDF, DOCX, JSON export and JSON import work onto `Dispatchers.IO` so file work does not block the UI thread.
   - Added a shared Downloads writer that keeps MediaStore files pending until the output stream is fully closed, publishes only complete files, and removes partial files on failure.
   - Ensured `PdfDocument` is closed even when rendering or writing fails.

3. **Import validation**
   - Empty, malformed, or `null` JSON backups now fail safely.
   - Backups that omit one of the vault lists are treated as having an empty list for that vault.

4. **Vault data integrity**
   - Adding an anime already in the vault no longer overwrites its progress, status, rating, or notes.
   - Detail loading clears the previous entry state immediately, preventing stale data from appearing for a missing/new ID.
   - Episode progress is constrained to the known total episode count.
   - A detail save is only marked successful when the target entry exists and was updated.
   - Removed Room's destructive migration fallback so a future schema mismatch cannot silently wipe the user's vault. Explicit migrations should be added before increasing the database version.

5. **Search and biometric behavior**
   - A failed biometric match no longer clears the pending H-Vault unlock request; only a terminal authentication error does.
   - Search cancellation is rethrown instead of being displayed as a search error.
   - Anime metadata search now uses configurable providers with AniList GraphQL and Jikan-compatible REST adapters. The discontinued api.jikan.moe endpoint is no longer hard-coded.

6. **Release shrinker dependency**
   - Added an explicit JSpecify dependency (`org.jspecify:jspecify:1.0.0`). The supplied CI report showed R8 failing because Jsoup referenced `org.jspecify.annotations.NullMarked`, which was missing from the release classpath.

## Validation performed

- Parsed all project XML resource/manifest files successfully during the earlier static audit.
- Added the JSpecify version and library alias to the Gradle version catalog and wired it into the app module.
- Attempted `./gradlew --no-daemon :app:assembleDebug :app:assembleRelease` after the fix. The build could not start because this environment has no network access and Gradle 8.7 was not cached (`UnknownHostException: services.gradle.org`). This means the R8 fix is based on the reported missing-class diagnostic and has not yet been confirmed by a completed Android build.

## Remaining security note

H-Vault's biometric prompt is an application access gate; the Room database itself is not encrypted at rest. Do not treat the database as cryptographically protected against someone with filesystem/root access. Database encryption would require a separate migration plan.


## API source redesign (2026-11-03)
- Removed the hard-coded Jikan Retrofit service, response models, and `api.jikan.moe` base URL.
- Added local API-source configuration with enable/disable, priority ordering, custom HTTPS endpoints, and MAL Client ID support.
- Added fallback search: enabled sources are tried in priority order when a request fails or returns no results.
- Added AniList GraphQL and MyAnimeList v2 adapters plus a Jikan-compatible REST adapter for compatible endpoints.
- Added Settings → API Sources screen and generated AniVault launcher artwork.
- Tenrai is listed as a configurable source but disabled by default because its response schema has not been verified as Jikan-compatible. Enable it only if it supports the configured adapter or add a dedicated adapter for its documented API.
- Build verification still required in a network-enabled Android/Gradle environment.
