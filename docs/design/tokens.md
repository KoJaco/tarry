# Design tokens — Tarry

Status: chosen 2026-10-21 from the direction comparison (artifact "Tarry design directions": the A palette and type, C's calendar timeline, and a full-width bottom bar). Implemented in phase 2c (`docs/specs/phase-2c.md`). This file is the source of truth: Compose code reads these values through `TarryTheme`, never through literals in screens.

## Brief
- **Who and what:** one tradesperson, tracked through the work day; glanced at on site and reviewed at night for invoicing.
- **Density:** comfortable. Big headline dates, generous cards, at least 48 dp touch targets.
- **Direction:** *field notebook*. Warm paper, ink-black type and controls, and one apricot accent. Calm, flat surfaces separated by colour, not shadow. A rounded sans (Nunito) for headings, dates and totals, and Nunito Sans for everything else. The day is drawn to scale like a calendar.
- **Fixed palette:** no Material You dynamic colour. Tracking-state and job colours must stay predictable.

## Colour

### Semantic tokens
Code uses these names through `TarryTheme.colors` (an extended colour object), mapped onto M3 roles (below) so stock components look right.

| Token | Light | Dark | Use |
|---|---|---|---|
| `paper` | `#F3EFE8` | `#14120F` | Screen background |
| `card` | `#FFFFFF` | `#201D18` | Cards, list rows, timeline container |
| `inset` | `#EFE9DF` | `#2D2924` | Chips, icon circles, segmented-control track, paused toggle |
| `ink` | `#1B1813` | `#F2EDE4` | Body text, primary pill buttons, selected states |
| `onInk` | `#F3EFE8` | `#14120F` | Text and icons on `ink` |
| `muted` | `#655E54` | `#A9A193` | Secondary text, time labels, unselected nav |
| `faint` | `#C2BAAE` | `#5E574D` | Disabled control fills and outlines (decorative, never text; disabled labels use `muted`) |
| `line` | `#DDD5C8` | `#3A352E` | Borders outside surfaces (the bottom bar's top border) |
| `divider` | `line` at 80 % | `line` at 80 % | Dividers and hour rules inside surfaces (grouped settings, the timeline canvas card, its rail) |
| `outline` | `#8F877B` | `#7A7266` | Input borders, the dashed outline of a visit that needs review (3:1 UI contrast) |
| `accent` | `#F2B064` | `#F2B064` | Apricot: tracking toggle, now-line, needs-review chip fill |
| `onAccent` | `#1B1813` | `#1B1813` | Text and icons on `accent` (white on apricot fails contrast) |
| `accentStrong` | `#8A5310` | `#F2B064` | Accent-coloured *text* and small marks (4.5:1 on `card`) |
| `accentSoft` | `#FBE3C4` | `#3A2A16` | Needs-review chip background, accent tints |
| `onAccentSoft` | `#4A2C05` | `#F6CDA6` | Text on `accentSoft` |
| `alert` | `#B3261E` | `#F2B8B5` | Gap icon, attention marks, errors |
| `alertSoft` | `#F9DEDC` | `#4A1F1B` | Gap block background, error container |
| `onAlertSoft` | `#8C1D17` | `#F9DEDC` | Text on `alertSoft` |
| `success` | `#1F5E36` | `#B4E0C2` | Granted / OK status icon and text (Sage job text; always with a word, never colour alone) |
| `alertDot` | `#FF6B5E` | `#FF6B5E` | The red badge dot on the ATTENTION toggle (on `ink`) |
| `pulse` | `#F2B064` @ 35 % | `#F2B064` @ 25 % | Tracking pulse ring |

### Map tokens
The map style (`app/src/main/assets/map/style.json`, derived from OpenFreeMap Positron) uses `{{token}}` placeholders, never colour literals. `MapStyle.build` fills them from `TarryColors`. Base map features use `paper` (land), `inset` (residential, buildings, tunnels), `card` (major roads, ice, aeroways), `line` (minor roads, casings, rail, building outlines), `outline` (boundaries), `ink` (place labels), `muted` (other labels) and `paper` (label halos). There are two map-only tokens. They are decorative fills, so no contrast target applies; tune them on device.

| Token | Light | Dark | Use |
|---|---|---|---|
| `mapWater` | `#CAD7DD` | `#1B2830` | Water bodies, waterways |
| `mapGreen` | `#E2E5D4` | `#1C211A` | Parks, woods |

Contrast, computed with WCAG 2.x relative luminance (keep these passing when tuning):

| Pair | Light | Dark |
|---|---|---|
| `ink` on `paper` | 15.4:1 | 16.0:1 |
| `muted` on `paper` / `inset` | 5.6:1 / 5.3:1 | 7.3:1 / 5.6:1 |
| `muted` on `card` | 6.4:1 | 6.5:1 |
| `accentStrong` on `card` | 6.3:1 | 8.9:1 |
| `onAccent` on `accent` | 9.4:1 | 9.4:1 |
| `onAlertSoft` on `alertSoft` | 7.2:1 | 11.0:1 |
| `alert` on `card` | 6.5:1 | 9.8:1 |
| `success` on `card` | 7.7:1 | 11.5:1 |
| `outline` on `paper` (UI, 3:1) | 3.1:1 | 3.9:1 |

Every job-colour text-on-container pair is at least 6.6:1 in both themes.

### M3 role mapping (`lightColorScheme` / `darkColorScheme`)
| M3 role | Token |
|---|---|
| `background`, `surface` | `paper` |
| `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer` | `card` |
| `surfaceContainerHigh`, `surfaceContainerHighest`, `surfaceVariant` | `inset` |
| `onBackground`, `onSurface` | `ink` |
| `onSurfaceVariant` | `muted` |
| `primary` / `onPrimary` | `ink` / `onInk` |
| `secondary` / `onSecondary` | `accent` / `onAccent` |
| `secondaryContainer` / `onSecondaryContainer` | `accentSoft` / `onAccentSoft` |
| `tertiary` | `accentStrong` |
| `error` / `onError` | `alert` / white (light), `#601410` (dark) |
| `errorContainer` / `onErrorContainer` | `alertSoft` / `onAlertSoft` |
| `outline` / `outlineVariant` | `outline` / `line` |
| `primaryContainer` / `onPrimaryContainer` | `inset` / `ink` |
| `tertiaryContainer` / `onTertiaryContainer` | `accentSoft` / `onAccentSoft` |
| `onTertiary` | `card` |
| `inversePrimary` | `accent` |
| `inverseSurface` / `inverseOnSurface` | `ink` / `onInk` (the same tokens in both themes, so the dark scheme inverts automatically) |
| `surfaceTint` | `paper` (flat: no tint on elevated surfaces) |
| `surfaceBright` / `surfaceDim` | `card` / `inset` |
| `primaryFixed`, `primaryFixedDim`, `onPrimaryFixed`, `onPrimaryFixedVariant` | `inset`, `line`, `ink`, `muted` |
| `secondaryFixed`, `secondaryFixedDim` and `tertiaryFixed`, `tertiaryFixedDim` | `accentSoft`, `accent` |
| `onSecondaryFixed`, `onSecondaryFixedVariant`, `onTertiaryFixed`, `onTertiaryFixedVariant` | `onAccentSoft` |
| `scrim` | M3 default (black) |

No role keeps an M3 baseline value (`TarryThemeTest.noRoleKeepsTheM3BaselineValue`). The error roles and the two white-on-white light roles coincide with the baseline by design.

### Job colours (calendar blocks)
`Job.colorArgb` stores one of these slots (by its light `container` value). Blocks are a tinted container, a 1.5 dp border and dark text. Never colour alone: the job name is always written in the block.

| Slot | Light container / border / text | Dark container / border / text |
|---|---|---|
| Apricot | `#FDEBD8` / `#EBA35C` / `#7A3F0B` | `#3A2614` / `#D9894A` / `#F6CDA6` |
| Sky | `#E3EDFB` / `#7FA6E3` / `#1E4A8F` | `#172A45` / `#5F8BD6` / `#B9D0F5` |
| Sage | `#E2F2E6` / `#73BB8C` / `#1F5E36` | `#16301F` / `#5BA676` / `#B4E0C2` |
| Lilac | `#EEE6FA` / `#A98DE0` / `#4E3191` | `#2A1F45` / `#9077D1` / `#D4C6F5` |
| Rose | `#FBE4EA` / `#E28BA2` / `#8A2342` | `#401A25` / `#CF7690` / `#F5C3D1` |
| Teal | `#DDF2F1` / `#5DB5B0` / `#165955` | `#123332` / `#4FA19C` / `#AEE0DD` |
| Sand | `#F3EBDD` / `#C9AE7E` / `#5E4718` | `#33291A` / `#B3996B` / `#E6D6B5` |
| Slate | `#E8EAEE` / `#9AA3B2` / `#353D4A` | `#22262D` / `#848D9C` / `#CDD3DD` |

A visit with no job uses `card` with a 1.5 dp **dashed** `outline` border.

## Type
- **Families (bundled in `res/font`, no downloadable fonts, because rule 2 forbids network):**
  - *Nunito* (600, 700; OFL 1.1), a rounded sans for headings only: major page titles, the timeline date, totals, card headers, sheet titles, empty-state and onboarding titles.
  - *Nunito Sans* (400, 600, 700; OFL 1.1), for everything else: body text, dense UI, buttons, captions and the tab bar.
  - The licence texts go in `assets/licenses/`.
  - Changed 2026-10-21 by the owner. It replaces Instrument Serif and Inter.
- **Scale** (sp, so it follows the user's font size):

| M3 style | Family / weight | Size / line | Use |
|---|---|---|---|
| `displaySmall` | Nunito 700 | 34 / 40, −0.25 tracking | Timeline date ("21 October") |
| `headlineLarge` | Nunito 700 | 32 / 38 | Totals ("6 h 12 m") |
| `headlineMedium` | Nunito 700 | 28 / 34 | Screen titles (Settings, Sessions, Permissions), empty-state titles |
| `titleLarge` | Nunito 700 | 20 / 26 | Sheet and dialog titles |
| `titleMedium` | Nunito 600 | 16 / 22 | Card headers and block titles (place name) |
| `titleSmall` | Nunito Sans 600 | 14 / 20 | Row titles |
| `bodyLarge` | Nunito Sans 400 | 16 / 24 | Body |
| `bodyMedium` | Nunito Sans 400 | 14 / 20 | Secondary lines (times, addresses) |
| `bodySmall` | Nunito Sans 400 | 12 / 16 | Hour labels, captions |
| `labelLarge` | Nunito Sans 700 | 15 / 20 | Buttons, the toggle |
| `labelMedium` | Nunito Sans 600 | 13 / 18 | Chips, nav labels |
| `labelSmall` | Nunito Sans 700 | 11 / 16, +0.6 tracking, upper case | Section headers |

- Nunito's and Nunito Sans's default figures are tabular (all ten digits have the same advance width; the bundled fonts have no `tnum` feature), so totals and times don't jitter. `TabularFiguresTest.digitsAreTabularByDefault` verifies this; if the fonts are ever replaced, that test must still pass or `tnum` must be used.
- Max line length is about 60 characters. Long place names ellipsize on one line in blocks (at font scales above 1.0 a tall enough block lets the title wrap to two lines and the time line to three instead) and wrap in the drawer.

## Space, shape, elevation
- **Spacing:** `TarryTheme.space` has `xs` 4, `s` 8, `m` 12, `l` 16, `xl` 20, `xxl` 24, `xxxl` 32, `huge` 48 dp, plus `gutter` 20 dp. The gap between cards is `s` (8 dp) and between sections `xl` (20 dp).
- **Shape:**
  - `pill` (fully rounded): buttons, chips, the toggle, nav items and their selected icon backing, segmented controls, icon buttons (circles).
  - `card` 20 dp: cards, list rows, the timeline container, grouped settings surfaces.
  - `container` 24 dp: the summary card and sheets.
  - `block` 12 dp: calendar blocks and swatches.
  - `small` 8 dp: compact blocks under 30 min.
  - These radii were reduced slightly on 2026-10-21, at the owner's request (from 24, 28, 14 and 10).
- **Elevation:** flat. Surfaces separate by colour (`card` on `paper`), not shadow. Sheets and dialogs use the M3 scrim only. No drop shadows on cards or the bar.
- **Receded rows:** archived jobs and tags draw at 70 % opacity (`RECEDED_ALPHA`, never below 4.5:1 for `ink` text on `card`) and always carry the word "Archived", never dimming alone. The "Archived" marker is drawn outside the receded content at full opacity (`muted` on `card`, 6.4:1 / 6.5:1), because `muted` at 70 % falls below 4.5:1.

## Motion
- **Standard transitions:** 200 ms, M3 emphasized easing. The day change slides 24 dp and fades.
- **Bottom bar backing glide:** 350 ms, emphasized easing `cubic-bezier(0.2, 0, 0, 1)`; snaps under reduced motion.
- **Tracking pulse:** the `pulse` ring scales 1.0 → 1.35 and fades out over 1.6 s, looping, only while TRACKING.
- **Reduced motion:** if the system animator duration scale is 0 (or the user turns animations off), the ring is static at 1.15 and there are no slides.

## Components

### Bottom bar
- Full width, edge to edge, in `paper` (the screen background), set apart only by a 1 dp `line` top border. It has no fill of its own, no rounded corners and no shadow. It extends under the gesture/navigation bar (insets padding inside).
- **Three equal slots** (`weight(1f)` each), in this order: **Timeline | tracking toggle | Settings**. Each slot is centred and at least 56 dp tall.
- **Nav items** are an icon over a `labelMedium` label, with 6 dp between them. Labels are always shown (no icon-only nav). Only the icon is backed, never the whole item.
  - Selected: a subtle `accentSoft` backing behind the icon only (64 × 32 dp, fully rounded `pill`), with the icon and label in `ink`. `inset` was too faint on `paper` in the light theme (about 1.1:1); `accentSoft` reads as a soft tint in both themes without the weight of an `ink` fill.
  - Unselected: the icon and label in `muted`, with no backing.
  - When the selection changes, the backing glides across to the new item (350 ms, emphasized easing `cubic-bezier(0.2, 0, 0, 1)`). With reduced motion it jumps.
  - Nav items have **no ripple or press background**; feedback is a light haptic (`SegmentTick`) on tap. The touch target (at least 48 dp) and semantics are unchanged.
- **The centre slot is the tracking toggle**, a pill in its state style (below), not a nav destination. Tap opens the tracking sheet; long-press does the same with haptics.
  - The label may wrap between words but never inside one. In a one-third slot at large font scales it shrinks a step at a time until no word is split.

### Tracking toggle (four states, each different in more than colour)
| State | Look | Label | Content description |
|---|---|---|---|
| TRACKING | `accent` fill, `onAccent` dot and text, animated `pulse` ring | "Tracking" | "Tracking: on" |
| IDLE | `ink` 1.5 dp outline, hollow dot | "Off" | "Tracking: off" |
| PAUSED | `inset` fill, a pause glyph in `accentStrong` (light) or `accent` (dark), static | "Paused · 45 m" (time left when known) | "Tracking: paused, 45 minutes left" |
| ATTENTION | `ink` fill, `alertDot` dot and badge, `onInk` text | "Needs attention" | "Tracking: needs attention" |

The PAUSED look deliberately differs from the MVP spec's "amber" because the accent is already apricot. It stays distinct through the fill, the glyph and the absence of a ring.

### Timeline (calendar style)
- **Header:**
  - the date in `displaySmall` ("21 October") with the weekday in `bodyMedium` `muted` above it;
  - on the right, round 48 dp `card` icon buttons: previous, next (disabled on today) and a calendar picker;
  - below, a **week strip**: 7 equal day cells (56 dp, `block` radius). The selected day is an `ink` fill with `onInk` text, future days are `muted`, and today is marked by an `accent` dot.
- **Summary card** (`card`, `container` radius): "Tracked today" (`bodyMedium` `muted`), the total in `headlineLarge`, a needs-review chip (`accentSoft`) and a "3 visits · 1 trip · 1 gap" caption.
- **Day canvas** (a `card` container, `card` radius):
  - A left time column, at least 44 dp wide (the canvas card has 8 dp of side padding), that grows to fit its widest hour label or the now-pill at large font sizes, with hour labels (`bodySmall` `muted`) and `divider` hour rules across the canvas. All times follow the system 24-hour setting: hour labels are "HH" or the locale's "h a", block and now-pill times the locale's "H:mm" or "h:mm a".
  - **The scale** is 72 dp per hour × max(1, fontScale), so text keeps fitting at large font sizes. The canvas starts at the earlier of the first item or the window start, rounded down to the hour, and ends at the later of now or the last item.
  - **Visits** are blocks at their true start and height, inset 4 dp from the rail:
    - job colour container, border and text (above); with no job, `card` with a dashed `outline`;
    - title (`titleMedium`), a time range and the job in `bodyMedium`;
    - a "Needs review" chip (`accentSoft` / `onAccentSoft`) on unlabelled visits.
  - **Compact mode:** under 30 min a block shrinks to `small` radius and one line ("Place · 12 m"). Every block is at least 28 dp × max(1, fontScale) tall even if that overstates a very short visit, and its spoken description gives the real times.
- **Item gaps:** consecutive items (visits, gaps, trip rows) are never closer than `itemGapDp` (4 dp, `DayLayoutParams`). Blocks are drawn at their true top; a block that would touch the next one is trimmed at the bottom, but never below its minimum height. Where it can't be trimmed, the next item is pushed down.  - **No top rule:** the canvas card has no border, and no hour rule is drawn at the very top edge. Hour rules appear only between hours.
  - **Trips** are a slim row (no fill) in the gap between blocks: a car or walk icon plus "Drive · 12.4 km · 25 min" in `bodySmall` `muted`. A trip too short to fit a row shows only in the next block's description.
  - **Gaps:** `alertSoft` fill with diagonal hatch lines (`alert` at 25 %, 1 dp every 8 dp), a 1.5 dp `alert` border, a warning icon and "Not tracked · App was closed" in `onAlertSoft`. They are never mistaken for a visit, because they have different fill, pattern, icon and text.
  - **Gaps inside a visit:** segmentation bridges short outages, so a gap can fall inside a visit. It is drawn inside the visit block, not as a block of its own, and never as a full-width band, so text is never crossed: a small 5 dp `alert` dot sitting on the block's left border at the outage's real time (its proportional midpoint), drawn above the border. When the block has room for a third line (not compact), an "Includes N min not tracked" line follows the time: warning icon (16 dp, `alert`) and `bodySmall` `onAlertSoft` text. A gap belongs to the visit it overlaps most and is inner when at least half of it lies inside that visit; otherwise it stays a block. The dot is decorative: the visit's spoken description ends with ", includes N minutes not tracked".
  - **Now-line:** today only, a 2 dp `accent` line across the canvas with a pill in the time column showing the time (`accent` fill, `onAccent` text). Its position is the proportional time position, moved down if needed so that every placed block or trip that has started sits at or above it (blocks keep a minimum height and are pushed apart, so they can drift below true time); an ongoing visit meets it at its elapsed fraction.
- **Accessibility:**
  - Each block is one focusable element. Its content description is the same phrasing as phase 2b's day list: "Visit at Rose Bay Marina, from 7:02 to 9:40, 2 hours 38 minutes, job Antifoul".
  - Reading order is time order.
  - At font scale 2.0 the canvas still scales, and the header and week strip wrap.

### Lists and settings rows
- **Grouped settings surfaces are the default** for a settings group (Tracking, Appearance, Billing), built with `SettingsGroup { row(...) }`: ONE `card` surface (`card` radius) holding all the rows, separated by 1 dp `divider` rules.
  - A row has a plain 24 dp `ink` leading icon (no icon circle), a `titleSmall` title with an optional `muted` `bodyMedium` subtitle under it, and a chevron on the right when it is tappable (Role.Button, merged semantics). Read-only rows (Billing) show a `muted` value on the right and no chevron.
  - Rows are at least 64 dp tall, with 16 dp side padding and 12 dp vertical padding.
  - Dividers run edge to edge across the surface, with no left (or right) inset. They are not focusable.
- **Standalone rows** (`SettingsRow`, `SettingsValueRow`, `SettingsSwitchRow`): used only where rows aren't a group. A row is a `card` pill (`card` radius), at least 56 dp tall, with a 40 dp `inset` icon circle on the left, the title (`titleSmall`), an optional value (`bodyMedium` `muted`) and a chevron or a switch on the right. Standalone rows are separate cards with 8 dp gaps and no dividers.
- Section headers use `labelSmall` `muted`.
- **Permissions:** one card per permission, with 12 dp between cards:
  - a leading icon circle;
  - the status as an icon plus a word: a check in `success` for granted, a cross in `alert` for not granted;
  - an action. Not granted: request it. Granted: "Change", which opens the system settings page.
- **Tracking sheet actions** are rows in the settings-row style (an icon circle, the title and an optional subtitle), not pill buttons.

### Buttons, chips, inputs
- **Primary:** `ink` pill, `onInk` label, 52 dp. **Secondary:** a 1.5 dp `ink` outline pill. **Tertiary:** a text button in `ink`. **Destructive:** `alert` text, or an outline pill in `alert`.
- **The accent is not a button colour.** It is reserved for tracking state and "needs attention from you" cues, so it keeps its meaning.
- **Chips:** a `card` pill on `paper` (or an `inset` pill on `card`), `labelMedium`. Selected: an `ink` fill.
- **Segmented control:** a `card` track with an `ink` pill for the selected item.
- **Switch:** M3 `Switch` with track `ink` when on, `inset` when off, and a `paper` thumb.
- **Inputs:** an `inset` fill, pill (single line) or `card` radius (multi-line), no underline, a 2 dp `ink` focus ring.

### Empty, loading and error states
- **Empty:** a short Nunito line (`headlineMedium`, "Nothing yet today") plus one `bodyMedium` `muted` sentence on what will fill it. No illustration.
- **Loading:** `card` placeholder blocks at 60 % opacity, with no shimmer when reduced motion is on.
- **Error:** an `alertSoft` card with an icon, a cause and one action.

### Icons
- Material Symbols Rounded, weight 300, grade 0, optical size 24, imported as vector drawables (Apache 2.0) only for the icons used. No icon font and no `material-icons-extended`.
- Decorative icons have `contentDescription = null`. Icon-only buttons always have a description.

### System chrome and notifications
- The app is edge to edge. Status bar icons are dark on `paper` in light theme and light in dark theme.
- Notifications use a monochrome small icon and `setColor(accent)`. Actions keep platform styling.

## Do / Don't
- **Do:** use `TarryTheme.colors`, `TarryTheme.space` and the M3 type scale; put cards on paper; spell out every state in text as well as colour; keep the accent rare.
- **Don't:** use hex literals in screens; add shadows; use dynamic colour; download fonts; use apricot for ordinary buttons; let a gap look like a visit; put white text on apricot.
