# AniVault 1.1 – implementation notes

Implements `AniVault_Feature_UI_Product_Specification` (all 18 sections) and the screens in the supplied mockups.

**Build status: not compiled.** The authoring environment had no Gradle/Kotlin toolchain or network. Code was checked by reading and by a cross-file import audit; JVM unit tests are included (`./gradlew testDebugUnitTest`). Run a build first and expect small fixes.

## Architecture
- `data/provider` – one `ProviderAdapter` per API type (AniList GraphQL, MAL v2, Jikan-compatible, Kitsu JSON:API) → `NormalizedAnime`. `FallbackEngine` implements the spec's request algorithm (priority order, auto-fallback switch, retry only transient errors, 429 cool-down honouring Retry-After, successful empty = final by default). `SourceValidator` enforces HTTPS / no secrets in URL / no private hosts. Health on the API Sources screen comes only from real tests.
- `data/sync` – AniList (implicit grant), MyAnimeList (PKCE), Kitsu (password grant, password never stored). `SyncPlanner` is pure logic with a conflict policy (newest / account / device). Offline edits set `pendingSync` and go out via `SyncWorker`.
- `data/library` – `ProgressRules`, `LibraryFilter`, `StatsCalculator` (pure, tested).
- `data/backup` – `BackupCodec` (PBKDF2-HMAC-SHA256 210k → AES-256-GCM, v1 files still readable), preview, merge/replace inside one Room transaction, in-session undo. H-Vault is opt-in per backup.
- Storage: SQLCipher database (key wrapped by Android Keystore), additive v1→v2 migration, background verified copy of the legacy plaintext DB, user-triggered removal. Tokens and client IDs live in `SecureStore` (Keystore AES-GCM).
- Privacy: no HTTP logging, no `Log` calls with queries/URLs, in-memory sanitized diagnostics only, cloud backup/device transfer excluded, notifications never contain titles, `FLAG_SECURE` in H-Vault.

## You must supply
- AniList / MyAnimeList client IDs (Settings → Account & Sync) and register redirect URIs `anivault://auth/anilist` and `anivault://auth/mal`. Nothing is bundled.
- The "Tenrai API" and "Jikan-Edge" entries from the mockup are listed disabled and unverified; their endpoints must be verified before use.

## Known gaps / decisions
- No video player or streaming (spec non-goal); Reader/Player settings say so.
- H-Vault scrapers and the Cloudflare challenge helper pre-date this work and were left in place; the spec says fallback is resilience, not circumvention, so consider removing the Cloudflare helper.
- Sample counts in the mockups (128, 32) are illustrative; the app shows real data.
