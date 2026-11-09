# Changelog

## 1.3.0
- **Customizable True Liquid Glass Theme & Studio**: Added `Palette.LIQUID_GLASS` (`LiquidGlass.kt`) featuring an animated multi-orb refractive backdrop (`LiquidGlassBackdrop`), directional 3D specular rim borders, diagonal glass sheen, inner top-edge caustic highlights (`Modifier.liquidGlassPanel`), and a floating Liquid Glass capsule navigation dock. Fully customizable in **Settings → Appearance → Liquid Glass Studio** (6 tint presets: Aurora Prism, Crystal Ice, Rose Quartz, Emerald Lagoon, Solar Amber, Smoked Obsidian; sliders for Frosted Opacity, 3D Specular Rim Intensity, and Backdrop Blur Radius; toggles for Animated Liquid Orbs and Floating Capsule Dock).
- **Liquid Glass Homescreen Widgets with Chameleon Cover Glow**: Added `LiquidGlassWidgetRenderer` to dynamically render high-resolution 3D Liquid Glass surfaces (radial light orbs, diagonal reflection sheen, top caustic highlight, and directional specular rim) across the Progress, Airing Next, and Library Stats widgets, with customizable widget glass opacity and automatic cover-art chameleon colour extraction.
- **Weekly Airing Calendar Screen**: Dedicated 7-day (Mon–Sun) schedule screen accessible from Home, switching between "My Library" and "All Seasonal" airing shows with countdown badges and episodes-behind indicators.
- **Season Archive Browser**: Browse any historical or upcoming anime season (Winter / Spring / Summer / Fall + Year selector) directly in Discover across AniList, MyAnimeList, Kitsu, and Jikan.
- **"Spin the Wheel" Random Picker**: Filter by status, genre, and episode length (including a "≤ 13 eps" toggle) to randomly pick what to watch next from Library or Home.
- **Custom Smart Lists**: Save any combination of status, media type, genre, tag, and sort order as a one-tap pinned Smart List chip at the top of the Library.
- **CSV & MyAnimeList XML Export + CSV Import**: Export your library to standard `.csv` or MyAnimeList-compatible `.xml` (`myanimelist` schema) in addition to `.json`, and import `.csv` or `.json` lists directly from Settings → Data.
- **Rich Entry Detail Sheet**: Added Studios chips, Characters & Voice Actors (Seiyuu) portrait carousel, interactive Franchise Watch-Order checklist with live completion badges, and official Streaming Service links.
- **Rewatch Counter & Episode Watch History Log**: Track `rewatchCount` with a one-tap "Start Rewatch" action, log per-episode watch timestamps in Room (`watch_history`, DB v4), and view recent watch activity in Entry Detail and Stats.
- **Quick-Action Swipe Gestures**: Swipe right on any list row in Library or H-Vault for `+1 Episode`, or swipe left to cycle watch status.
- **Customizable Grid Density & Poster Badges**: Choose Compact (4-col), Comfortable (3-col), or Large (2-col) grids and toggle Rating, Airing Countdown, or Unwatched Episode count corner badges in Settings → Appearance.
- **3 Additional Built-In Themes & Dynamic Cover-Art Tinting**: Added **Cyberpunk Neon**, **Matcha Paper**, and **Crimson Eclipse** themes, plus automatic vibrant cover-art accent extraction on Entry Detail and Catalog sheets.
- **Multi-Show Progress Widget, Deep-Link Airing Widget & Notification Actions**: Secondary quick `+1` rows on the Progress Widget, tap-to-open `anivault://entry/<id>` deep-links and "X ep behind" indicators on the Airing Widget, and an inline **"+1 Watched"** action button on episode notifications.
- **Security & Stability Hardening**: Fixed OAuth deep-link scheme mismatch, PKCE state verification, Cleartext traffic disablement, PBKDF2 salt migration, Room v4 migration & singleton safety, sync snapshot deletion reconciliation, and full input/URI sanitization.

## 1.2.0
- Account deletes now work (they were being sent as reads; MyAnimeList answered 405).
- Slimmer search header on Discover, Library and H-Vault that scrolls away, with a jump-to-top button.
- Widgets: progress (with cover art), airing next, library stats.
- "Group seasons" in the library; cover disk cache, preloading and a "Refresh library covers" setting.
- Baseline profile and trimmed resources for faster startup and a smaller APK.
- Sync preview ("Preview changes") and a readable sync log, so you can see what a sync will do before it touches your accounts.
- New-episode alerts and a "This week" section on Home, using AniList airing times.
- Your own tags on every title, with a tag filter in the library.
- Related titles (prequel, sequel, side story…) on title pages; tap one to open it and add it.
- Import a MyAnimeList export (.xml / .xml.gz); adult titles go to H-Vault.
- Home-screen widget with a +1 episode button.

## 1.1.0
- Stable signing key, Discover genre paging and sorting, backup toggles, H-Vault library-style UI and library⇄H-Vault moves, more themes, H-Vault sync and delete-on-account, AniList rate-limit handling, README with screenshots.
