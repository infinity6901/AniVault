# AniVault API sources

AniVault no longer hard-codes `api.jikan.moe`. Open **Settings → API Sources** to manage providers.

## Built-in adapters

- **AniList GraphQL**: `https://graphql.anilist.co`; no API key required for public search.
- **Jikan-compatible REST**: expects an endpoint that implements `/anime?q=<query>&page=<n>&limit=20` and returns a Jikan-shaped JSON `data` array. Use only with endpoints whose documentation confirms this schema.
- **MyAnimeList v2**: `https://api.myanimelist.net/v2`; requires a MAL Client ID. Create/configure it in the MAL developer portal and enter it in the source settings.

## Fallback behavior

Enabled providers are tried in the order shown. A failed HTTP request, network timeout, malformed response, or empty result moves the search to the next enabled provider. The first provider that returns results wins. If all providers fail, the UI receives a combined error summary.

## Tenrai and custom providers

Tenrai is listed as a configurable source but disabled by default. The generic REST adapter is **not** a universal API translator: custom endpoints must match the Jikan-compatible response schema. A dedicated Tenrai adapter should only be added after confirming its current public API documentation and response format. Never enter API keys into a source URL; use the dedicated Client ID field where supported.

## Caveats

- Source configuration is stored locally in app SharedPreferences.
- MAL credentials are stored locally, but SharedPreferences is not a hardware-backed secret vault. Avoid using a sensitive long-lived secret here.
- Search fallback handles metadata search only; it does not imply that all providers support account sync, watchlist sync, or every metadata field.
- Validate all providers and build the APK in a network-enabled Android/Gradle environment before distributing.
