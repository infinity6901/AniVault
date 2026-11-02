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
   - The Jikan anime search defaults to `sfw=true`, keeping general anime search separate from the H-Vault.

## Validation performed

- Parsed all project XML resource/manifest files successfully.
- Parsed the Gradle version catalog as TOML successfully.
- Checked the Unix Gradle launcher syntax.
- Inspected the Gradle dependency aliases used by the app module.

## Validation limitation / remaining security note

A full Android Gradle compile/test could not be completed in this environment. The uploaded archive's wrapper JAR was a placeholder rather than the real Gradle wrapper, and the environment could not download a replacement distribution. The launcher now bootstraps Gradle 8.7 when run in an environment with network access, but the Android build still needs to be run in Android Studio or with Gradle available.

H-Vault's biometric prompt is an application access gate; the Room database itself is not encrypted at rest. Do not treat the database as cryptographically protected against someone with filesystem/root access. Database encryption would require a separate migration plan.
