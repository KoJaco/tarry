# Plan: marketing and store screenshots (after 8a.2, before 8c)

**From:** Claude (planner) · **Date:** 2026-11-11 · **Status:** planned, not started. Run it once the branches that change what ships (8a.2, 8b paywall) have merged, so the images match the released app.

## Goal
Clean, impersonal screenshots and short clips of Tarry for the marketing site (https://www.tarry.me/) and the Play Store listing, made from **invented demo data on the emulator**. The owner's real data, real places and real routes never appear.

## Principles
- **Never the Pixel, never real data.** Capture only on the emulator (`Medium_Phone_API_35`, or a Pixel-sized AVD at 1080×2400). The owner's backups stay in `tmp/` and are never used for captures.
- **Demo data is synthetic and believable.** About two weeks of a contractor's working weeks, with these places and jobs:
  - invented site names, e.g. "Harbourside Apartments", "Lane Cove reno", "Northside depot", "Café on Miller";
  - invented jobs and clients;
  - trips between the sites, notes and a few labelled visits;
  - two or three "Needs review" items;
  - one honest gap.

  Map tiles show real streets (OpenStreetMap), so the sites sit in a generic area **away from anywhere the owner lives or works**. No real addresses in names or notes.
- **Release-like build:** no debug-only items (e.g. Sessions "Start test window"). Use a release-config build signed with the debug key for local install, or a debug build with those items confirmed hidden.
- **Clean status bar:** Android demo mode, with clock 9:41, full battery, full signal and no notification icons:
  - `adb shell settings put global sysui_demo_allowed 1`
  - `am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941`, and the same for battery, network and notifications
  - Exit demo mode afterwards.
- **Locale and format:** en-AU, 12-hour time (matches the brand voice). Font scale 1.0.
- **Nothing in the repo that identifies the owner.** Generated images are committed only if the site repo needs them here; otherwise they live in the website repo.

## Step 1: demo dataset as a Tarry backup file
- Generate a valid backup with the app's own format (`app/.../export/backup/BackupCodec.kt`, `FORMAT = "tarry-backup"`, `CURRENT_VERSION`), so a normal Settings → Restore loads it. This needs **no app feature code**.
- Write the generator as a JVM test-source utility (e.g. `app/src/test/.../demo/DemoBackupGenerator.kt`, run by a dedicated, normally skipped test or a small Gradle task). It builds entities with the real model classes and writes `build/demo/tarry-demo.json`, so the format can't drift.
- Contents:
  - places with radii;
  - jobs with colours and default billable flags;
  - job blocks;
  - visits and trips: some labelled, some needing review, plus notes;
  - pings dense enough for map route lines;
  - tracking sessions, including one interrupted session;
  - a schedule template, Mon–Fri 7:00–17:00 with one shorter Friday.
- Reuse the segmentation test helpers for synthetic traces where that is simpler than hand-writing pings. Check whether restore re-runs segmentation; if it does, generate pings and let segmentation produce the visits, rather than writing visits that would be overwritten.
- Dates are relative to the capture day, so "today" is a full, tidy day.
- Push to the emulator (`adb -s emulator-5554 push`) and restore it through the app.

## Step 2: shot list

### Hero (about 5 stills, light; dark variants if the site switches theme)
1. **Timeline, a full tidy day:** visits, trips and a job block; the summary card shows tracked time.
2. **Map view** of the same day with route lines.
3. **Edit drawer:** a visit being labelled with a job and a note.
4. **Summary:** the week's totals by job.
5. **Onboarding Welcome** (logo plus privacy promise) or the **Schedule grid**.

### Feature sections (a still each, plus an optional 3–6 s loop)
| Section | Still | Clip |
|---|---|---|
| A day you can read at a glance | Timeline day | scroll and zoom through the day |
| Site time and travel time | visit and trip blocks, then the map with routes | Timeline → Map toggle |
| Jobs and notes after the fact | drawer with job and note | tap a visit, pick a job, add a note |
| Schedules that fit your week | Schedule grid | paint Friday's hours |
| Never fails silently | "Attention" pill, the Tracking health screen, a visible gap | open health and tap Test tracking |
| Summary and export | Summary card and export sheet (CSV/PDF) | pick a range and export |

### Play Store (for 8c, same setup)
- 2–8 phone screenshots: reuse the hero set, portrait, 1080×2400 or 1080×1920.
- Feature graphic 1024×500: the logo and tagline, made separately, not a screenshot.
- 512×512 icon, from `docs/brand/logo/tarry-mark.svg`.
- **Background-location video** for the Play permission declaration: a short screen recording of the prominent disclosure (`BackgroundDisclosureDialog`), granting "Allow all the time", and tracking at work. Record it in the same session.

## Step 3: capture
- Stills: `adb -s emulator-5554 exec-out screencap -p > out/<name>.png`.
- Clips: `adb -s emulator-5554 shell screenrecord --size 1080x2400 --bit-rate 8M /sdcard/<name>.mp4`, then `adb pull`. Drive the taps with `adb shell input`. **Never swipe across Timeline blocks**; use the zoom buttons and scroll gestures on empty space only.
- Convert with ffmpeg (installed via winget): trim, then encode
  - **MP4 (H.264)** for compatibility;
  - **WebM (VP9 or AV1)** for size;
  - a **poster PNG** from the first frame.

  Loop-friendly: start and end on the same frame. Make GIFs only if a specific spot needs one (`ffmpeg … -vf "fps=15,scale=540:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse"`).
- Stills to WebP/AVIF, with a PNG fallback; keep the 1080-wide originals.

## Step 4: deliverables
- An `out/` folder with stills (light and dark), clips (mp4, webm and poster), Play assets and the declaration video.
- Alt text for every image in a small `captions.md` (what the screen shows, written for people who can't see it).
- Device frames are applied on the website with CSS, not baked into the images.
- A quick check that every image is free of real data: no owner place names, notes, addresses or familiar streets.

## Out of scope
Website code (separate Next.js project, see the brand guideline artifact), copywriting beyond alt text, and the feature graphic design. These can be follow-ups.
