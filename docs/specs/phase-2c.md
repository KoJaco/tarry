# Phase 2c — Design system

Branch: `feat/phase-2c-design-system`, from `main`. Size: M. Parent: `docs/specs/build-plan.md` (2c). Design source: `docs/design/tokens.md`, the single source of truth for every value below.

## Goal
Replace Android Studio's default purple theme with Tarry's own design system before more screens exist:
- warm paper, ink and apricot;
- Instrument Serif and Inter;
- flat cards and pills;
- a full-width bottom bar with equal pill slots;
- a calendar-style day canvas.

Then restyle every existing screen with it. From here on, phases 3–8 build only from these tokens and components.

## Non-goals
- New features. No editing drawer, no week or month views, no map, no schedule grid. Those are phases 3–6.
- Changing behaviour, data or the schema. Visual and semantic changes only, plus the query-only reads the timeline needs (job name and colour).
- Dynamic colour (Material You). This is ruled out: the palette is fixed.

## Decisions (owner, 2026-10-21)
- **Palette and type:** direction A ("field notebook").
- **Timeline styling:** direction C (calendar). The day is drawn to scale on a time column, with job-coloured blocks, hatched gaps and a now-line.
- **Bottom bar:** full width, the screen's background colour, set apart only by a top border, with three equally spaced pill slots: Timeline | tracking toggle | Settings. Not a floating bar.
- The comparison mock-ups are the private artifact "Tarry design directions". They are reference only. Where they differ from `tokens.md`, `tokens.md` wins: it replaces the floating bar and puts A's palette onto C's layout.

## Carry-over
- Every AGENTS.md hard rule still applies. Rule 2 means fonts are **bundled**, never downloadable. Rule 11 is WCAG 2.2 AA with 48 dp targets. Rule 7 means every behaviour change ships with a test.
- **Phase 2b:** the timeline's spoken descriptions (`TimelineItem` content descriptions, plurals, "Unknown place", midnight clamping, loading vs empty, today rollover, Next disabled on today) are **kept** and carried into the new canvas.
- **Settings:** `Settings.theme` (SYSTEM/LIGHT/DARK) already exists in DataStore but is not yet wired to the theme. 2c wires it.

## Tasks (in order)

### T1 — Fonts and licences (implementer, S)
- Add `app/src/main/res/font/`:
  - `instrument_serif_regular.ttf`, `instrument_serif_italic.ttf`;
  - `inter_regular.ttf`, `inter_medium.ttf`, `inter_semibold.ttf` (static instances; take the official releases from the Google Fonts and rsms/inter repos, OFL 1.1);
  - a `FontFamily` for each in `ui/theme/Type.kt`.
- Put the OFL texts in `app/src/main/assets/licenses/` (`OFL-InstrumentSerif.txt`, `OFL-Inter.txt`). Phase 8's About screen will list them.
- Downloading the files once, at development time, is fine. The app makes no font request at runtime.

### T2 — Theme (claude + implementer, M)
- **`ui/theme/`:**
  - `TarryColors` (an immutable data class with every semantic token in `tokens.md`), plus `LightTarryColors` and `DarkTarryColors`;
  - `LocalTarryColors`;
  - `TarrySpace` (xs 4 … xxl 48, plus `gutter` 20);
  - `TarryShapes` (pill, card 24, container 28, block 14, small 10);
  - `JobPalette` (8 slots × light/dark container, border, text, with lookup by the stored `colorArgb`; an unknown value falls back to a slot chosen deterministically from the job id);
  - `TarryTheme { colors, space, shapes }` accessors.
- **M3 schemes:** `lightColorScheme`/`darkColorScheme` mapped exactly as in the `tokens.md` table. Delete `Purple80` and the rest, and the `dynamicColor` path.
- **Typography:** the M3 `Typography` with the `tokens.md` scale, plus a `TabularNumbers` text style helper (`tnum`).
- **Theme setting:**
  - `MainActivity` reads `Settings.theme` and passes `darkTheme` to `TarryTheme` (SYSTEM → `isSystemInDarkTheme()`).
  - Status and navigation bar icon appearance follows the theme (`enableEdgeToEdge` with `SystemBarStyle.auto`/`light`/`dark` per the effective theme).
- **Tests:**
  - **Contrast guard:** a JVM test that computes the WCAG ratio for every text/background pair listed in `tokens.md` (and every job slot's text on its container, in both themes) and asserts the documented minimum.
  - **No-literals guard:** a JVM test that scans `app/src/main/java` for `Color(0x` outside `ui/theme/` and fails, listing the offenders. `TrackingToggle.kt` has some today.
  - `JobPalette` lookup and fallback.
  - The theme setting changes the effective dark flag (Robolectric).

### T3 — Icons (implementer, S)
- Import the used Material Symbols Rounded icons (weight 300, grade 0, 24 px, Apache 2.0) as vector drawables named `ic_<name>.xml`. The set covers: timeline, settings, chevrons, calendar, car, walk, warning, pause, location pin, radius, theme, permissions, sessions, plus anything an existing screen uses. Replace the current `Icons.*` uses.
- Don't add `material-icons-extended`.

### T4 — Shared components (implementer + test-writer, M)
Create `ui/components/`, each component with previews in light, dark and fontScale 2.0, each stateless:
- `TarryCard` (card or container radius) and `SectionHeader`.
- `SettingsRow` in three variants (chevron, value, switch): a 40 dp icon circle, at least 56 dp tall, merged semantics. The switch variant uses `toggleable(role = Switch)` on the whole row.
- `PrimaryButton`, `SecondaryButton`, `TertiaryButton`, `DestructiveButton` (pills, 52 dp).
- `TarryChip` (selectable), `SegmentedControl` (tabs semantics), `IconCircleButton` (48 dp, description required by signature).
- `EmptyState`, `LoadingBlocks` (no shimmer when reduced motion is on), `ErrorCard`.
- `TarryBottomBar`: full width, `paper` background with only a 1 dp `line` top border (no fill, corners or shadow), three `weight(1f)` slots, nav-bar insets inside. Nav items are pills (selected: `ink` fill with icon and label; unselected: `muted`). Keep the existing navigation behaviour.
- `TrackingToggle`, restyled to the four states in `tokens.md`:
  - each state differs by shape, icon and label as well as colour;
  - pulse ring only while TRACKING, static when reduced motion is on;
  - keep the existing content descriptions and the long-press;
  - read reduced motion from `Settings.Global.ANIMATOR_DURATION_SCALE` and hide it behind a small injectable `MotionPreferences` so tests can flip it.
- **Tests:**
  - Compose (Robolectric) semantics: roles, merged descriptions, ≥48 dp, selected and disabled states, and the four toggle states' text and description;
  - the bottom bar's equal slot widths;
  - reduced motion turns off the pulse.

### T5 — Calendar day canvas (claude + implementer, L)
- **Pure layout first:** `ui/timeline/DayLayout.kt` (no Compose), `layoutDay(items, dayBounds, now, fontScale, params): DayLayout`, which gives:
  - canvas start and end (rounded to the hour; it spans the first or last item, the window and now);
  - the dp offset and height of each block at 72 dp/h × max(1, fontScale);
  - a 28 dp minimum height, compact mode under 30 min, and trip rows placed in the gaps (dropped if the gap is under the row height, with the trip folded into the next visit's description);
  - the now-line position, today only.

  `DayLayoutParams` holds the numbers (rule 5). Unit-test it heavily:
  - a normal day and an empty day;
  - an all-day stay;
  - many short visits that hit the minimum heights, including no overlap after enforcing the minimums (push down, and the canvas grows);
  - a midnight-spanning item clamped to the day;
  - a DST day (Sydney 2026-10-04, 23 h);
  - fontScale 2.0;
  - now before the first item or after the last.
- **Data:** `TimelineItem.Visit` gains `jobName: String?` and `jobColorArgb: Int?` from a query-only join or lookup through `JobRepository`/`JobDao`. No schema change; keep `git diff main -- core-data/schemas` empty.
- **UI** (`TimelineContent` is replaced):
  - `DayHeader`: the serif date, weekday, previous/next/calendar `IconCircleButton`s, the M3 `DatePickerDialog` for the calendar, and Next disabled on today.
  - `WeekStrip`: 7 cells; selecting one changes the day; today's accent dot; future days `muted`. Days after today are not selectable, the same rule as Next.
  - `SummaryCard`.
  - `DayCanvas`: the time column, hour rules, blocks (job colours or the dashed no-job style), trips, hatched gaps and the now-line, per `tokens.md`. Each block is one focusable element with the 2b content description extended with the job ("…, job Antifoul"). Reading order is time order.
  - The loading and empty states use the T4 components.
- **Tests:** the layout tests above. Compose tests for the content descriptions of each item type, the job colour applied, the hatch present on gaps (a test tag or semantics property), the now-line only today, week-strip selection and disabled future days, and fontScale 2.0 with no clipped text (assert the text nodes are displayed and not truncated where measurable).

### T6 — Restyle the remaining screens (implementer, M)
- **Settings:** a `headlineMedium` title, sectioned `SettingsRow`s ("Tracking": Stop radius, Permissions, Sessions; "Appearance": Theme; "Billing": the existing read-only rows as value rows). The stop-radius picker becomes a modal bottom sheet (`container` radius) with selectable rows, the same semantics as 2a. A new Theme picker (System, Light, Dark) writes `Settings.theme`; add a setter test if one is missing.
- **Permissions:** title, explanatory copy and one `SettingsRow`-style card per permission with its status and a primary or secondary pill action. The rule-6 wording stays.
- **Sessions (debug):** a restyled list; the export and run-segmentation buttons become secondary pills; the status line uses `muted`, or `alert` on error.
- **Tracking sheet:** a `container`-radius sheet with the four actions as pill buttons, the state title in `titleLarge`, and no behaviour change.
- **Notifications:** `setColor(accent)`, monochrome small icon. Text unchanged.
- Remove every remaining literal colour and default-theme leftover.
- **Tests:** update the existing UI tests, which may assert on text or semantics that moved. Add the theme-picker test.

### T7 — Review (ui-reviewer + reviewer, S)
- **ui-reviewer on the Pixel:**
  - Timeline, Settings, Permissions, Sessions and the tracking sheet, in light and dark at font scale 1.0 and 2.0;
  - TalkBack pass over the day canvas and the bottom bar;
  - touch-target and contrast spot checks.

  Save the screenshots under `tmp/` only: they show real places, so they never go in the repo.
- **reviewer:** the whole branch against this spec and AGENTS.md.

## Acceptance criteria
- `./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes locally and in CI.
- No `Color(0x…)` outside `ui/theme/` (the test enforces it). No dynamic colour, no downloadable fonts, no new network use.
- The contrast-guard test passes for every documented pair in both themes.
- **On the Pixel:**
  - every screen matches `tokens.md` in light and dark;
  - the theme setting switches it live;
  - the day canvas shows a real day to scale, with job colours, trips, hatched gaps and the now-line;
  - font scale 2.0 is usable with no clipped text;
  - TalkBack reads blocks in time order with full descriptions.
- Room schema unchanged.

## Risks and open questions
- **Proportional canvas vs minimum heights:** many short visits push blocks down, so lower blocks drift from their true time. That is accepted: the spoken description and the label always give real times. Revisit in phase 3 if the drift is confusing.
- **Font licences:** OFL allows bundling. Keep the licence files, and credit the fonts in phase 8's About.
- **Phase 3 overlap:** cards and the editing drawer build on T4 and T5. Keep the components stateless and generic enough for that.
