# AniVault implementation status (next pass)

## Implemented in this pass

- Material 3 color-scheme tokens are now used by screens instead of hard-coded dark colors for common backgrounds, surfaces, text, outlines, and primary accents. This allows the existing dark/light/system preference to affect the wider UI consistently.
- H-Vault and anime Room data now use a SQLCipher-encrypted database. The SQLCipher passphrase is randomly generated and wrapped by an AES-GCM key held in Android Keystore.
- First-run migration copies entries from the existing plaintext `anivault.db` into `anivault-secure.db` in a transaction. The legacy file is deliberately preserved as a recovery copy; it is not automatically deleted. Users should keep the app's private storage protected and use the app's encrypted backup before upgrading.
- API connection testing validates HTTPS URLs and checks a real endpoint for the configured adapter.
- Search fallback now returns a successful empty result when at least one provider responded successfully with no matches, instead of misreporting a total outage because another provider failed.

## Still requiring provider registration / device verification

- Account sync is not falsely marked as complete. Real AniList/MAL/Kitsu account sync requires provider-specific OAuth app registration, redirect URI configuration, token storage/refresh, API adapters, and conflict-resolution tests. No client secrets or redirect URI were supplied with this source tree, so these cannot be safely fabricated.
- SQLCipher migration and biometric unlock must be exercised on a device with a copy of a real legacy database before release. The legacy database is retained intentionally to avoid data loss; remove it only after validating a successful migration and backup.
- Tenrai and Jikan-compatible endpoints are community endpoints and must be verified against their actual current schemas before being enabled by default. A successful connection test does not guarantee complete metadata compatibility.
- A full Gradle build and emulator test were not run in this environment. Do not treat this archive as release-verified until CI builds debug and release APKs, runs tests, and checks signatures.
