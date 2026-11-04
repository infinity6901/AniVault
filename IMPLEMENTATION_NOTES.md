# AniVault UI and feature implementation notes

This update adds the purple/pink AniVault visual palette, the custom illustrated launcher icon, a Settings home with categorized sections, persistent preference controls for selected settings, dashboard hero/quick actions, and an Auto Fallback control plus provider connection testing in API Sources.

## API source behavior
- AniList GraphQL has a dedicated adapter.
- MyAnimeList v2 requires a user-supplied Client ID.
- Jikan-compatible REST sources are treated as compatible REST endpoints only; the official Jikan API is not configured as a default provider.
- Tenrai and Jikan-Edge are disabled by default and must be tested by the user before enabling.
- Auto Fallback is stored locally and controls whether search tries the full enabled source list or only the first enabled source.

## Existing features retained
- Local anime and H-Vault libraries, status filtering/sorting, entry detail editing, bulk selection and deletion confirmation.
- JSON/PDF/DOCX export and JSON import.
- Biometric H-Vault gate and screen-capture protection while H-Vault is open.

## Known implementation gaps
- Account authentication/cloud sync for MAL, AniList, and Kitsu is not implemented by these screens.
- Reader/player is currently a preference category, not a built-in streaming player.
- Backup & Restore currently points users to existing local import/export rather than a complete scheduled/encrypted backup system.
- Theme choice is persisted, but live light/system theme switching needs further integration into the root theme.
- H-Vault's biometric gate and screen protection do not by themselves guarantee database encryption at rest. Do not store sensitive content until encrypted-at-rest storage is implemented and verified.
- API connection tests validate a basic response, not full provider compatibility. Custom providers need a supported adapter/response schema.

## Validation
A local Gradle build was attempted but could not run because the environment cannot resolve/download `services.gradle.org`. Run the repository's GitHub Actions workflow to verify compilation, release shrinking, APK signatures, and emulator smoke tests.

## Follow-up implementation pass

- Added password-protected backup/restore for both anime and H-Vault entries. The `.avb` format uses PBKDF2-HMAC-SHA256 (210,000 iterations) to derive a 256-bit key and AES-256-GCM with a random salt and nonce. The password is not persisted. Restore merges by primary key and does not delete entries missing from the backup.
- Added a Settings > Backup & Restore flow using Android's Storage Access Framework to select a save or restore location.
- Added an application-wide Material theme preference that reacts to changes in Dark / Light / System. Custom branded surfaces still retain some fixed dark palette colors, so this is a partial light-theme treatment rather than a complete light-theme redesign.
- Advanced > Clear cached data now clears the app cache directory without deleting Room library entries or settings.

## Still requires external integration / device validation

- AniList/MAL/Kitsu account OAuth and cloud list synchronization are not implemented; they require registered provider credentials, redirect URIs, and provider-specific synchronization conflict rules.
- The local Room database (including H-Vault) is not encrypted at rest. The encrypted backup protects exported backup files only; it does not encrypt the live database. Do not treat biometric gating as database encryption.
- The app cannot validate live API endpoint availability in this offline environment. Tenrai and Jikan-Edge endpoints must be tested before enabling them by default.
- Gradle distribution download was attempted but failed due DNS/network unavailability, so a full Android compile and device tests could not be run here.
