# UI rules — Tarry

**Status:** the phase 7.5 output (2026-11-08). Phase 8 screens (onboarding, paywall, diagnostics, About) follow this file and `tokens.md`. `tokens.md` holds the values (colour, type, space, shape, motion) and the specs of existing components. This file holds the rules for using them. If a rule is missing, add it here with the owner. Don't invent a pattern in a screen.

## 1. Screen anatomy
- **No app bars anywhere.** The title is the first thing in the scrolling content:
  - on the Timeline, the date in `displaySmall` with the weekday above it;
  - on every other screen, top-level (Summary, Settings) or pushed (Jobs, Places, Place detail, Sessions, Permissions, Schedule, and new phase 8 screens), the title in `headlineMedium`.
- **Actions** sit to the title's right as 48 dp `card` icon circles (`IconCircleButton`), at most three.
- **Back:** pushed screens rely on system Back and gestures. There is no back arrow today; if one is ever added, it goes on every pushed screen at once.
- **Intro line:** one `bodyMedium` `muted` line under the title, only if the screen needs explaining.
- **Body:**
  - content sits in `card`s on `paper`, 20 dp `gutter` side margins, 8 dp between cards and 20 dp between sections;
  - section headers are `SectionHeader` (`labelSmall` `muted`);
  - grouped rows use `SettingsGroup`.
- **The primary action:**
  - one per screen, at most;
  - for a form, it sits at the end of the content as a full-width `PrimaryButton`, not floating;
  - the timeline's actions live in its review row.
- **Sheet or full screen:**
  - **Bottom sheet (`TarrySheet`):** a short task about something on screen, such as editing a visit or job (the drawer), tracking actions, a picker or a gap explanation. It can be dismissed with a swipe or Back without losing data, because edits save as they happen.
  - **Full screen:** a task with its own navigation or a long list, such as Schedule, Jobs, Places, Permissions and, in phase 8, onboarding and the paywall.
  - **Dialog (`AlertDialog`):** only a decision or a single small input, such as rename, confirm delete or a time picker.

## 2. Writing
- **Voice:** see `docs/brand/brand-and-copy.md` § Voice. Plain, warm, direct, Australian English. No exclamation marks, no hype, no surveillance words ("monitor", "spy").
- **Case:** sentence case everywhere, including titles, buttons, chips and settings rows.
  - the one deliberate exception: section headers (`SectionHeader`, and the Summary By day date headers, which use it) are uppercase `labelSmall` in `muted`. The text is uppercased in code (`text.uppercase()`), so write the string in sentence case; TalkBack still gets the original case.
- **Full stops:**
  - titles, buttons, chips, row titles and empty-state titles have none;
  - sentences (intros, hints, empty-state bodies, errors) end with one.
- **Buttons say what happens:** a verb first ("Add job", "Save visit", "Restore backup"), never "OK" for a destructive action. A destructive confirm names the thing ("Delete job").
- **Errors:** what happened, then what to do, in the user's terms. "Couldn't save. This visit overlaps 9:10–9:40." Never codes or exceptions.
- **Empty states:** a title that says what's empty ("Nothing yet today"), then one sentence saying what will fill it.
- **Permissions:**
  - explain before the system prompt: what it enables, in one sentence;
  - say what's lost if declined;
  - never threaten, never nag more than once per screen visit.
- **Confirmations:** prefer undo over confirm (§3). Confirm only what can't be undone, such as delete all data or restore over existing data. For data loss, type to confirm.
- **Numbers and times:**
  - the system 12/24-hour setting;
  - durations as "2 h 35 min", short "45 min", "3 h";
  - ranges with an en dash and no spaces ("8:31 am–11:06 am");
  - spaces inside a time or date are non-breaking, and a range may break only after the dash;
  - distances in km with one decimal place ("5.5 km");
  - dates as "6 November" or "6 Nov 2026" (no ordinals).
- **Status is never colour alone:** every state also has a word or an icon (Tracking/Off/Paused, Done, Needs review, Not tracking, Granted).

## 3. Component use
- **Buttons:**
  - `PrimaryButton` (`ink` fill) is the one main action;
  - `SecondaryButton` (outline) is an alternative;
  - `TertiaryButton` (text) is low weight (Skip, Cancel);
  - `DestructiveButton` is a delete or remove;
  - the accent colour is never a button; it marks tracking state and "needs you" cues only.
- **Chips (`TarryChip`):** filters and quick choices. A chip row that can overflow is a `FadingScrollRow`; it scrolls and never wraps. An action can be a chip only inside a chip group ("+ Add to job").
- **Toggles:** `SegmentedControl` for 2–3 views of the same content (By job | By day); the Timeline | Map toggle; `Switch` for a setting that takes effect immediately.
- **Snackbar:**
  - confirms an action that just happened, with Undo when it can be undone (edits, merges, splits, deletes of user data);
  - one at a time, collected at the app root so it survives tab changes;
  - never for errors that need action (use an inline `ErrorCard` or the field's error).
- **Undo:**
  - every edit to visits, trips and jobs is undoable from the snackbar;
  - deletes of jobs or places are undoable too, or confirmed when undo isn't possible;
  - autosaved fields (notes) save silently, with no Saved indicator (owner, 2026-11-08).
- **Lists:**
  - a tappable row is the whole row (`Role.Button`, merged semantics, chevron);
  - read-only rows show a `muted` value and no chevron.

## 4. Motion and haptics
- **Values:** in `tokens.md` § Motion. Standard transitions are 200 ms with M3 emphasized easing; the nav backing glides over 350 ms; the tracking pulse is the only looping animation.
- **Motion explains a change of place or state.** Use it for a screen change, a day change, a sheet opening or a selection moving. It is never decoration.
- **Reduced motion:** check `rememberReducedMotion()`. Animations become instant, the pulse becomes static, and zoom snaps without animating.
- **Map views:** MapLibre maps use texture mode (`tarryMapOptions`) so they take part in screen transitions. Don't use a `SurfaceView` for anything that moves with the UI.
- **Haptics:**
  - a light tick (`SegmentTick`) for nav selection and timeline block selection;
  - `LongPress` when a long-press starts something (tracking toggle, job block draw/move, schedule painting);
  - never on scroll, never on errors alone;
  - don't add new haptic moments without an entry here.

## 5. Accessibility floor (WCAG 2.2 AA)
- **Touch targets:**
  - 48 × 48 dp minimum; the visual can be smaller if the touch area isn't (see the review chip);
  - **one documented exception:** the Schedule grid cells, about 42–44 dp wide (owner, 2026-11-08). They still exceed WCAG's 24 dp minimum, and each day's sheet offers full-size time fields for the same edits.
- **Contrast:**
  - text 4.5:1;
  - large text and UI parts (borders that carry meaning, icons, focus rings, hour rules) 3:1;
  - `ContrastGuardTest` checks the token pairs, so add a row to it for every new pair.
- **Font scale 2.0:**
  - every screen works with no clipped or overlapping text;
  - text wraps between words, never inside one;
  - sideways rows scroll with a fade;
  - **capped:** only the bottom-nav labels (1.3×) and the map credit line (1.3×), because both have fixed slots and their meaning is duplicated by an icon or a dialog;
  - from 1.5× the timeline moves hour labels above their lines.
- **TalkBack:**
  - each block, row and card is one node with a full sentence ("Visit at Rose Bay Marina, from 7:02 to 9:40, 2 hours 38 minutes, job Antifoul");
  - reading order is time order on the timeline, and top to bottom elsewhere;
  - toggles expose state (`Role.Checkbox` or `Switch`, `stateDescription`);
  - a gesture (pinch, drag, long-press) always has a single-tap or accessibility-action alternative (WCAG 2.5.1, 2.5.7);
  - label-in-name: the spoken name starts with the visible text.
- **Decorative icons** have `contentDescription = null`. Icon-only buttons always have one.
- **Never fail silently** (AGENTS.md rule 6). A locked, missing-permission or not-tracking state is always visible as text, not only as a colour or an absence.

## 6. Icons and illustration
- **Icons:** Material Symbols Rounded, weight 300, grade 0, 24 px, imported as vector drawables from `google/material-design-icons` (Apache-2.0). Name the source in a comment at the top of the file.
- **No illustrations.** Empty states are text only. The brand uses real app screens instead (landing page). An onboarding illustration needs an owner decision and an entry here first.
- **No emoji** in UI text.

## 7. Building a new screen (checklist)
1. **Anatomy:** decide top-level, pushed, sheet or dialog using §1.
2. **Tokens only:** `TarryTheme.colors`, `TarryTheme.space`, `TarryTheme.shapes`, the M3 type scale. No hex literals or ad-hoc dp in screens (put constants in a `…Defaults` object).
3. **Shared components:** buttons, chips, rows, `SettingsGroup`, `TarryCard`, `TarrySheet`, `EmptyState`, `LoadingBlocks`, `ErrorCard`, `FadingScrollRow`.
4. **States:** write loading, empty and error states, plus offline where Play Billing is involved.
5. **Copy:** follow §2, with strings in `strings.xml` and plurals for counts.
6. **Accessibility:** 48 dp targets, one node per item, state descriptions, and a gesture alternative.
7. **Dark mode:** cards and sheets get the dark-only edge (`surfaceEdge`, `sheetEdge`). Never lighten the dark card.
8. **Tests:** Robolectric Compose tests for semantics and behaviour, at font 1.0 and 2.0. Use `GraphicsMode.NATIVE` when wrapping matters.
9. **Device check:** screenshots on the Pixel in light and dark at font 1.0 and 2.0 (`ui-reviewer`) before the PR.
10. **Docs:** a new pattern or value goes into `tokens.md` or this file in the same PR.
