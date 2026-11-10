# Handoff: phase 8a.1 — About screen

**From:** Claude (planner, `phase-8`) → **To:** implementer · **Date:** 2026-11-09 · **Branch:** `feat/phase-8-launch` (checked out; never main).

Read first: `AGENTS.md`, `docs/design/ui-rules.md` (all; §1 anatomy, §7 checklist), `docs/design/tokens.md`, `docs/decisions/0005-licence.md` (About wording), `docs/decisions/0004-play-billing-network.md`.

## Build
1. **Route:** `AboutRoute` in `ui/navigation/Routes.kt`, wired in `ui/TarryApp.kt` like the other pushed screens (Sessions, Places). A new Settings section header "About" at the very end of Settings (after Data), with one tappable row "About Tarry" (subtitle: the version name) that pushes `AboutRoute`.
2. **Screen** (`ui/about/AboutScreen.kt`, pushed, no app bar, `headlineMedium` title "About Tarry"):
   - **Intro line** (`bodyMedium` `muted`): "Your work day, written down. Private on your phone."
   - **App** group (`SettingsGroup`): Version — read-only row, value `BuildConfig.VERSION_NAME` (+ `(VERSION_CODE)`); Licence — tappable row "Source available under FSL-1.1-ALv2", opens the repo URL; "Source code" — tappable row, opens the repo URL (if this duplicates, merge them into one row "Source code", subtitle "Source available under FSL-1.1-ALv2").
   - **Privacy** group: a short read-only paragraph from the brand privacy promise: "Tarry has no account, no server and no analytics. Your location stays on this phone unless you export it. It fetches only map tiles, street addresses from the phone's geocoder, and your purchase through Google Play." Then a tappable "Privacy policy" row opening the privacy URL.
   - **Help** group: "Support" row, subtitle the support email, opens `mailto:` with subject "Tarry support" (use `Intent.ACTION_SENDTO`; if no app handles it, show a snackbar "No email app found. Write to <email>.").
   - **Credits** group: "Map data" read-only row: "© OpenStreetMap contributors. Tiles by OpenFreeMap." (match the attribution text the map already shows, find it in `ui/map/`); "Open-source licences" tappable row → pushes `LicencesRoute`.
   - Outbound links use `Intent.ACTION_VIEW` (the system browser; not an in-app network call, rule 2 holds). Guard `ActivityNotFoundException` with the same snackbar pattern.
3. **Constants:** `ui/about/AboutLinks.kt` object with `SOURCE_URL = "https://github.com/KoJaco/tarry"`, `PRIVACY_URL` and `SUPPORT_EMAIL` as clear placeholders with `// TODO(phase 8c): owner fills before release` comments. Use `https://tarry.app/privacy` and `support@tarry.app` as placeholder values ONLY if nothing better is found in docs; grep docs first.
4. **Licences screen** (`LicencesRoute`, pushed, title "Open-source licences"): a list of rows, one per notice: name, licence id as subtitle, tap expands or pushes the full text (your choice, pick the simpler that still meets 2× font; a `TarrySheet` with scrollable text is fine). Load texts from `app/src/main/assets/licenses/`. Entries:
   - Nunito, Nunito Sans — OFL-1.1 (files exist);
   - MapLibre Native — BSD-2-Clause (add `BSD-2-MapLibre.txt` with the MapLibre copyright line and the standard BSD-2 text);
   - OpenStreetMap data — ODbL-1.0 (add `ODbL-notice.txt`: a short notice with attribution and a link to opendatacommons.org/licenses/odbl/, not the whole licence);
   - Material Symbols, AndroidX, Kotlin, Hilt/Dagger, Google Play services, kotlinx — Apache-2.0 (add one `Apache-2.0.txt` with the full licence text; one row "Android and Google libraries" listing those names in the subtitle, or one row each — keep it readable).
   Keep the list as a single data list in code (`OssNotices.kt`) so 8b can add Play Billing.
5. **No "Restore purchase"** yet (8b).

## Rules
- Tokens only, strings in `strings.xml`, sentence case, 48 dp targets, tappable rows are whole-row `Role.Button` with chevron, read-only rows muted with no chevron, one TalkBack node per row, decorative icons null.
- Dark: cards get the dark-only edge as other screens do.
- Tests (Robolectric Compose, follow `SettingsDataUiTest`): Settings row pushes About; About shows version and licence line; links fire the right intent (verify via a lambda/`UriHandler`-style seam rather than real activities); licences list shows all entries and opens a text; font scale 2.0 has no clipped text (GraphicsMode.NATIVE where wrapping matters).
- Run `gradlew.bat testDebugUnitTest lintDebug assembleDebug` until green. One commit (include this handoff file), explicit paths, never `.idea/`, end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`.
- Report files changed, tests, decisions, skips.
