# Design tokens — Tarry

Status: chosen 2026-10-21 from the direction comparison (artifact "Tarry design directions": the A palette and type, C's calendar timeline, and a full-width bottom bar). Implemented in phase 2c (`docs/specs/phase-2c.md`). This file is the source of truth: Compose code reads these values through `TarryTheme`, never through literals in screens.

## Brief
- **Who and what:** one tradesperson, tracked through the work day; glanced at on site and reviewed at night for invoicing.
- **Density:** comfortable. Big headline dates, generous cards, at least 48 dp touch targets.
- **Direction:** *field notebook*. Warm paper, ink-black type and controls, and one apricot accent. Calm, flat surfaces separated by colour, not shadow. A serif for dates and totals, a clean sans for everything else. The day is drawn to scale like a calendar.
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
| `faint` | `#C2BAAE` | `#5E574D` | Disabled controls (decorative, never text) |
| `line` | `#DDD5C8` | `#3A352E` | Hour lines, dividers, timeline rail |
| `outline` | `#8F877B` | `#7A7266` | Input borders, the dashed outline of a visit that needs review (3:1 UI contrast) |
| `accent` | `#F2B064` | `#F2B064` | Apricot: tracking toggle, now-line, needs-review chip fill |
| `onAccent` | `#1B1813` | `#1B1813` | Text and icons on `accent` (white on apricot fails contrast) |
| `accentStrong` | `#8A5310` | `#F2B064` | Accent-coloured *text* and small marks (4.5:1 on `card`) |
| `accentSoft` | `#FBE3C4` | `#3A2A16` | Needs-review chip background, accent tints |
| `onAccentSoft` | `#4A2C05` | `#F6CDA6` | Text on `accentSoft` |
| `alert` | `#B3261E` | `#F2B8B5` | Gap icon, attention marks, errors |
| `alertSoft` | `#F9DEDC` | `#4A1F1B` | Gap block background, error container |
| `onAlertSoft` | `#8C1D17` | `#F9DEDC` | Text on `alertSoft` |
| `alertDot` | `#FF6B5E` | `#FF6B5E` | The red badge dot on the ATTENTION toggle (on `ink`) |
| `pulse` | `#F2B064` @ 35 % | `#F2B064` @ 25 % | Tracking pulse ring |

Contrast, computed with WCAG 2.x relative luminance (keep these passing when tuning):

| Pair | Light | Dark |
|---|---|---|
| `ink` on `paper` | 15.4:1 | 16.0:1 |
| `muted` on `paper` / `inset` | 5.6:1 / 5.3:1 | 7.3:1 / 5.6:1 |
| `accentStrong` on `card` | 6.3:1 | 8.9:1 |
| `onAccent` on `accent` | 9.4:1 | 9.4:1 |
| `onAlertSoft` on `alertSoft` | 7.2:1 | 11.0:1 |
| `alert` on `card` | 6.5:1 | 9.8:1 |
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
  - *Instrument Serif* (Regular, Italic; OFL 1.1), for dates, totals and screen titles only.
  - *Inter* (400, 500, 600; OFL 1.1), for everything else.
  - The licence texts go in `assets/licenses/`.
- **Scale** (sp, so it follows the user's font size):

| M3 style | Family / weight | Size / line | Use |
|---|---|---|---|
| `displaySmall` | Instrument Serif | 40 / 44, −0.5 tracking | Timeline date ("21 October") |
| `headlineLarge` | Instrument Serif | 36 / 40 | Totals ("6 h 12 m") |
| `headlineMedium` | Instrument Serif | 30 / 34 | Screen titles (Settings, Sessions, Permissions) |
| `titleLarge` | Inter 600 | 20 / 26 | Sheet and dialog titles |
| `titleMedium` | Inter 600 | 16 / 22 | Card and block titles (place name) |
| `titleSmall` | Inter 600 | 14 / 20 | Row titles |
| `bodyLarge` | Inter 400 | 16 / 24 | Body |
| `bodyMedium` | Inter 400 | 14 / 20 | Secondary lines (times, addresses) |
| `bodySmall` | Inter 400 | 12 / 16 | Hour labels, captions |
| `labelLarge` | Inter 600 | 15 / 20 | Buttons, the toggle |
| `labelMedium` | Inter 500 | 13 / 18 | Chips |
| `labelSmall` | Inter 600 | 11 / 16, +0.6 tracking, upper case | Section headers |

- Numbers in totals and times use Inter's tabular figures (`fontFeatureSettings = "tnum"`), so columns don't jitter.
- Max line length is about 60 characters. Long place names ellipsize on one line in blocks and wrap in the drawer.

## Space, shape, elevation
- **Spacing:** 4, 8, 12, 16, 20, 24, 32, 48 dp (`TarryTheme.space.xs`…`xxl`). The screen gutter is 20 dp, the gap between cards 8 dp and between sections 20 dp.
- **Shape:**
  - `pill` (fully rounded): buttons, chips, the toggle, nav items, segmented controls, icon buttons (circles).
  - `card` 24 dp: cards, list rows, the timeline container.
  - `container` 28 dp: the summary card and sheets.
  - `block` 14 dp: calendar blocks and swatches.
  - `small` 10 dp: compact blocks under 30 min.
- **Elevation:** flat. Surfaces separate by colour (`card` on `paper`), not shadow. Sheets and dialogs use the M3 scrim only. No drop shadows on cards or the bar.

## Motion
- **Standard transitions:** 200 ms, M3 emphasized easing. The day change slides 24 dp and fades.
- **Tracking pulse:** the `pulse` ring scales 1.0 → 1.35 and fades out over 1.6 s, looping, only while TRACKING.
- **Reduced motion:** if the system animator duration scale is 0 (or the user turns animations off), the ring is static at 1.15 and there are no slides.

## Components

### Bottom bar
- Full width, edge to edge, in `paper` (the screen background), set apart only by a 1 dp `line` top border. It has no fill of its own, no rounded corners and no shadow. It extends under the gesture/navigation bar (insets padding inside).
- **Three equal slots** (`weight(1f)` each), in this order: **Timeline | tracking toggle | Settings**. Each slot is centred and at least 56 dp tall.
- **Nav items are pills.** When selected: an `ink` pill with icon and label in `onInk`. Unselected: icon and label in `muted` on no fill. Labels are always shown (no icon-only nav).
- **The centre slot is the tracking toggle**, a pill in its state style (below), not a nav destination. Tap opens the tracking sheet; long-press does the same with haptics.

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
  - A left time column 52 dp wide with hour labels (`bodySmall` `muted`, `HH` or localised short time) and `line` hour rules across the canvas.
  - **The scale** is 72 dp per hour × max(1, fontScale), so text keeps fitting at large font sizes. The canvas starts at the earlier of the first item or the window start, rounded down to the hour, and ends at the later of now or the last item.
  - **Visits** are blocks at their true start and height, inset 8 dp from the rail:
    - job colour container, border and text (above); with no job, `card` with a dashed `outline`;
    - title (`titleMedium`), a time range and the job in `bodyMedium`;
    - a "Needs review" chip (`accentSoft` / `onAccentSoft`) on unlabelled visits.
  - **Compact mode:** under 30 min a block shrinks to `small` radius and one line ("Place · 12 m"). Every block is at least 28 dp tall even if that overstates a very short visit, and its spoken description gives the real times.
  - **Trips** are a slim row (no fill) in the gap between blocks: a car or walk icon plus "Drive · 12.4 km · 25 min" in `bodySmall` `muted`. A trip too short to fit a row shows only in the next block's description.
  - **Gaps:** `alertSoft` fill with diagonal hatch lines (`alert` at 25 %, 1 dp every 8 dp), a 1.5 dp `alert` border, a warning icon and "Not tracked · App was closed" in `onAlertSoft`. They are never mistaken for a visit, because they have different fill, pattern, icon and text.
  - **Now-line:** today only, a 2 dp `accent` line across the canvas with a pill in the time column showing the time (`accent` fill, `onAccent` text). An ongoing visit block extends to it.
- **Accessibility:**
  - Each block is one focusable element. Its content description is the same phrasing as phase 2b's day list: "Visit at Rose Bay Marina, from 7:02 to 9:40, 2 hours 38 minutes, job Antifoul".
  - Reading order is time order.
  - At font scale 2.0 the canvas still scales, and the header and week strip wrap.

### Lists and settings rows
- A row is a `card` pill (`card` radius), at least 56 dp tall, with a 40 dp `inset` icon circle on the left, the title (`titleSmall`), an optional value (`bodyMedium` `muted`) and a chevron or a switch on the right.
- Section headers use `labelSmall` `muted`.
- Rows in one group are separate cards with 8 dp gaps; there are no dividers.

### Buttons, chips, inputs
- **Primary:** `ink` pill, `onInk` label, 52 dp. **Secondary:** a 1.5 dp `ink` outline pill. **Tertiary:** a text button in `ink`. **Destructive:** `alert` text, or an outline pill in `alert`.
- **The accent is not a button colour.** It is reserved for tracking state and "needs attention from you" cues, so it keeps its meaning.
- **Chips:** a `card` pill on `paper` (or an `inset` pill on `card`), `labelMedium`. Selected: an `ink` fill.
- **Segmented control:** a `card` track with an `ink` pill for the selected item.
- **Switch:** M3 `Switch` with track `ink` when on, `inset` when off, and a `paper` thumb.
- **Inputs:** an `inset` fill, pill (single line) or `card` radius (multi-line), no underline, a 2 dp `ink` focus ring.

### Empty, loading and error states
- **Empty:** a short Instrument Serif line (`headlineMedium`, "Nothing yet today") plus one `bodyMedium` `muted` sentence on what will fill it. No illustration.
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
