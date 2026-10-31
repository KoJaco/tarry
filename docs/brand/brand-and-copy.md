# Tarry — brand and copy handoff (for the landing page)

**From:** Claude (planner, session `tarry-phase-6`) → **To:** the owner, building the landing page · **Date:** 2026-10-31
**Source of truth for the app's look:** `docs/design/tokens.md`. This doc derives the brand from it. If phase 7.5 (the UI pass) changes colours, type or voice, update both together so the site and the app match.

## 1. What Tarry is (one breath)
Tarry quietly records where you are during your work hours, splits the day into time on site and time on the road, and lets you drop jobs onto it afterwards, so invoicing becomes a lookup instead of a reconstruction. Everything stays on your phone.

**Who it's for:** tradespeople and other mobile workers who bill by time across several sites a day: marine and boat work, building, cleaning, gardening, mobile mechanics, carers. They are glanced at on site and reviewed at night.

**The name:** *tarry* means to linger, to stay a while. The app notices where you tarried, so you don't have to remember.

## 2. Brand feel
- **Direction: field notebook.** Warm paper, ink-black type, one apricot accent. Calm, flat and honest. It feels like a well-kept logbook, not a dashboard.
- **Three words:** *quiet, trustworthy, practical.*
- **Personality:**
  - **Like:** a tidy tradesperson's notebook, a good tape measure, a paper diary.
  - **Not like:** a surveillance tool, a productivity "hustle" app, a corporate HR system.
- **Visual rules:**
  - flat surfaces separated by colour, never drop shadows;
  - generous spacing and rounded but not bubbly;
  - the accent is rare and means "this is live" or "this needs you", so it is never decoration;
  - no stock photos of smiling people at laptops. Prefer real app screens, a phone in a work context (ute dash, marina, toolbag), or simple calendar-style diagrams of a day.

## 3. Colour
Use the light theme for the site by default; dark is optional (the hex values below come from the app).

| Role | Hex (light) | Hex (dark) | Use on the site |
|---|---|---|---|
| Paper (background) | `#F3EFE8` | `#14120F` | Page background |
| Card | `#FFFFFF` | `#201D18` | Feature cards, pricing card |
| Inset | `#EFE9DF` | `#2D2924` | Subtle bands, FAQ rows, code or quote boxes |
| Ink (text, primary button) | `#1B1813` | `#F2EDE4` | Headings, body, **primary buttons** |
| On ink | `#F3EFE8` | `#14120F` | Text on ink buttons |
| Muted | `#655E54` | `#A9A193` | Secondary text, captions |
| Line | `#DDD5C8` | `#3A352E` | Borders, dividers |
| **Apricot accent** | `#F2B064` | `#F2B064` | Small highlights only: a "tracking" dot, an underline, one badge. **Never a button fill, never under white text.** |
| Accent strong (accent text) | `#8A5310` | `#F2B064` | Accent-coloured words or links |
| Accent soft | `#FBE3C4` | `#3A2A16` | A highlighted band or callout background |
| Alert | `#B3261E` | `#F2B8B5` | Only if needed (errors) |

**Job palette:** the app's job colours (Apricot, Sky, Sage, Lilac, Rose, Teal, Sand, Slate; `tokens.md` § Job colours) can tint illustrations of the timeline. Always pair each tint with its own border and text colour from that table.

**Contrast:** ink on paper is 15.4:1 and muted on paper is 5.6:1. Text on apricot must be ink (`#1B1813`), never white.

## 4. Typography
- **Headings: Nunito** (700, also 600), a rounded sans. Use it for the hero line, section titles, prices and big numbers ("6 h 12 m").
- **Body and UI: Nunito Sans** (400, 600, 700). Use it for paragraphs, buttons, navigation and captions.
- **Both are OFL 1.1.** Self-host the font files rather than loading them from Google Fonts, so the site makes no third-party requests, which keeps the privacy promise.
- **Suggested web scale:**
  - hero 48–56 px Nunito 700, line height 1.1, letter spacing −0.5;
  - H2 32 px Nunito 700;
  - H3 20 px Nunito 700;
  - body 18 px Nunito Sans 400, line height 1.55;
  - small 14 px;
  - button 16 px Nunito Sans 700.
- **Numbers:** both families have tabular figures by default, which suits times and prices.
- **Line length:** about 60–70 characters. Use sentence case for headings and buttons.

## 5. Shape, icons, motion
- **Radii:** cards 20 px; big surfaces and sheets 24 px; small blocks 12 px; buttons and chips fully rounded (pill).
- **Icons:** Material Symbols Rounded, weight 300 (Apache 2.0), the same as the app.
- **Motion:** subtle, 200 ms fades and slides. One signature motion is allowed: the slow apricot **pulse ring** of the tracking button, which scales 1.0 → 1.35 and fades over 1.6 s, for the "it's running quietly" idea. Respect `prefers-reduced-motion`.
- **Logo:** not designed yet. Until then, use the wordmark "Tarry" in Nunito 700 ink, optionally with a small apricot dot (the tracking dot) before or after it. A proper mark is an open task.

## 6. Voice and tone
- **Plain, warm and direct,** the way a good mate who's also an accountant would explain it. Short sentences. Use Australian English (colour, labelled, organise).
- **Talk about the user's day and their invoice,** not the technology. Say "Your day, already written down", not "AI-powered geolocation".
- **Be honest about limits.** It needs permissions, it uses some battery, and Android can stop apps, in which case Tarry shows you the gap rather than hiding it.
- **Avoid:** hype ("revolutionary", "supercharge"), fear ("never lose money again!!"), surveillance words ("monitor", "spy", "track your workers") and exclamation marks.
- **Privacy is said plainly and often,** never as small print.

## 7. Landing page copy (draft, ready to edit)

### Hero
- **Headline options** (pick one):
  - *Your work day, already written down.*
  - *Invoice from where you actually were.*
  - *Stop reconstructing your week.*
- **Subhead:** Tarry quietly notes where you are during work hours, splits your day into on-site time and travel, and lets you drop jobs onto it later. No account. No cloud. It all stays on your phone.
- **Primary button:** Get it on Google Play
- **Secondary link:** See how it works
- **Small line under the buttons:** 7-day free trial · then one payment, yours for life

### How it works (3 steps)
1. **Paint your hours once.** Tell Tarry when you work. It starts and stops on its own from then on.
2. **Work like normal.** Tarry stays in the background, noting each site you stop at and each drive between.
3. **Drop jobs on at the end of the day.** See your day drawn to scale, add a job across the visits and trips it covers, and your hours are ready to invoice.

### Features
- **A day you can read at a glance:** visits and trips drawn to scale like a calendar, with jobs alongside.
- **Jobs that span the real work:** one job can cover a morning of site visits and the drive between. Two jobs can share a supply run.
- **Suggests, never assumes:** set a usual job for a place and Tarry suggests it. You accept or decline.
- **Never fails silently:** if Android stops tracking, or a permission is missing, Tarry tells you and marks the gap. It never makes up the missing time.
- **Map and scrubber:** replay the day on a map to remember where you were at 2:40.
- **Summary and export:** totals by job with your rounding and minimums, plus CSV and PDF for your invoices. (Phase 7; confirm the final feature names before publishing.)
- **Your backup, your file:** export a full backup to wherever you choose.

### Privacy promise (its own section, prominent)
- **Heading:** *Private by default. Not by setting.*
- **Body:** Tarry has no account, no server and no analytics. Your location is processed on your phone and never leaves it, unless you export a file yourself. The only things it fetches from the internet are map tiles and street addresses (the phone's built-in geocoder), and Google Play handles your purchase.
- **Bullets:**
  - No sign-up, no email, no tracking pixels.
  - Location stays on your device.
  - You choose where backups and exports go.
  - Delete everything in one tap.

Keep these claims exactly in line with AGENTS.md rule 2 and ADR 0004. If the app changes, change this.

### Pricing
- **Heading:** *One price. No subscription.*
- **Card:**
  - **Free for 7 days**, with everything included.
  - **Then 29 AUD once.** Yours for life, on your Google account. (The price is to be confirmed; show the localised Play price if possible.)
  - The ticks:
    - Unlimited tracking and history
    - Jobs, summary and exports
    - Backups and restore
    - All future updates to the core app
- **Note:** A Pro subscription may come later for optional extras like sync between devices. The core app stays one payment, forever.

### FAQ
- **Does it drain my battery?** It uses GPS during your work hours, so expect some battery use on working days. Outside your hours it sleeps. Measured figures from the trust pass (phase 6.5) go here before launch.
- **Does my boss or anyone else see my location?** No. Tarry has no server and no account. It's your notebook, on your phone.
- **What if I forget to turn it on?** You don't need to. It follows the hours you painted. You can also start, pause or stop it by hand.
- **What happens if tracking gets interrupted?** Tarry marks the gap on your timeline and tells you why, so you're never invoicing from silently missing time.
- **Can I move to a new phone?** Export a backup and restore it on the new phone. Your purchase restores through Google Play.
- **iPhone?** Not yet. Tarry is Android only for now.
- **What happens after the trial?** Decide in phase 8, then write it here, e.g. "Your data stays yours: you can still view, edit and export everything. Unlock Tarry to keep tracking."

### Footer
Privacy policy · Support (email) · Made in Australia (if true) · © Tarry. The licence or source line follows ADR 0005; if the source stays public, say so, because it builds trust.

## 8. Store listing snippets (Play, for phase 8)
- **Title (30 characters max):** Tarry: Work Hours Timeline
- **Short description (80 characters max):** Your work day, written down. Sites, travel and jobs, private on your phone.
- **Full description:** reuse the hero subhead, How it works, Features and Privacy promise above, as plain text with no claims beyond what ships.

## 9. Open items
- A logo or mark, and an app icon refresh to match.
- The final price, and what the trial end locks (phase 8).
- The licence line (ADR 0005).
- Real screenshots after phase 7.5. The calendar-style day view is the hero image.
- Battery numbers from phase 6.5 for the FAQ.
