# Fixes after CI run #11

- Replaced the legacy blue Android-grid launcher art with the AniVault anime icon across legacy density assets and adaptive launcher resources.
- Added an explicit search action button inside the search field so searches can be triggered by tapping, in both Anime and H-Vault modes.
- H-Vault search now surfaces source failures when all configured sources fail instead of silently looking like an empty search. The existing source fallback order remains in place.
- Added a confirmation dialog before bulk deletion; the individual entry detail screen already asks for confirmation.
- Updated CI to use one explicit Android user-home directory for debug-key generation, Gradle signing, and APK verification (including the verifier script), addressing CI #11's mismatched certificate fingerprints.

These are code changes; CI must be rerun to confirm build, signing, and runtime behavior. H-Vault sources are third-party sites and can still be unavailable or change their HTML.
