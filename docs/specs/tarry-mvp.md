# Location Timeline Tracker — MVP Spec

14 Oct 2026 · @Kori

> Canonical source spec, saved as written. Intentional deviations agreed during planning (Job entity, UUID keys, extra tables, CSV ID columns, exact alarms) are in `docs/specs/build-plan.md` under "Spec deltas" and take precedence.

## Overview
An Android app that passively records where you are during scheduled work hours, splits the day into on-site visits and travel, and lets you label it afterwards so invoicing is a lookup, not a reconstruction. All data stays on the device. The code is open source.

## Principles
- **Set and forget.** After the schedule is painted once, nothing is required day to day.
- **Label later, never in the moment.** Annotation happens retroactively, with the map in front of you.
- **Private by default.** No account, no server, no analytics. Raw location never leaves the phone.
- **Never fail silently.** Tracking state and data gaps are always visible.
- **Simple surface.** Two main screens (Timeline, Settings), a bottom bar with a tracking toggle, and one edit drawer.

## MVP scope
- Scheduled ambient tracking with a weekly template and per-week overrides
- Manual start, stop and pause outside or inside the schedule
- Automatic segmentation into visits and trips, and clustering of visits into known places
- Timeline with three views: List, Map (with horizontal scrubber) and Summary
- Retroactive annotation: job label, free notes, multi-select tags, billable flag
- Place management, tag groups, rounding rules, CSV/PDF export, local backup and restore

## Non-goals for the MVP
- Cloud sync, accounts or multi-device
- MCP server or agent access (future Pro tier)
- iOS
- Team or employer features
- Live in-the-moment prompts to label or categorise

## Tech stack and architecture
Native Kotlin with Jetpack Compose, single activity, local Room database. Capture and processing are separate: a foreground service only appends raw pings; segmentation runs in batches through WorkManager and writes derived visits and trips.

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Min / target SDK | minSdk 29 (Android 10, for the background location permission model), target latest |
| Architecture | MVVM, unidirectional state, Kotlin Flows; Hilt for DI |
| Storage | Room (SQLite); DataStore for preferences |
| Location | Google Play Services fused location provider; Activity Recognition Transition API; Geofencing API |
| Background | Foreground service (type location); WorkManager for segmentation; AlarmManager for schedule edges; BOOT_COMPLETED receiver |
| Maps | MapLibre Native with OpenStreetMap-based vector tiles; offline tile cache |
| Reverse geocoding | Android Geocoder (on-device where available); result cached on the place |
| Export | CSV via plain writer; PDF via Android PdfDocument |
| Licence | Open source (choose before first public commit; check compatibility of any borrowed code) |

### Modules
- **core-data:** Room entities, DAOs, repositories
- **tracking:** service, location and activity clients, schedule engine, health monitor
- **segmentation:** pure Kotlin stay-point, trip and place clustering logic (no Android dependencies, fully unit-testable)
- **feature-timeline, feature-settings, feature-onboarding:** Compose UI
- **export:** CSV, PDF, backup/restore

**Play Services dependency.** Fused location and activity recognition need Google Play Services. An F-Droid build without them (raw LocationManager, no activity recognition) is a later option, not MVP.

## Data model
Raw pings are the source of truth; visits and trips are derived from them and can be regenerated, except visits a user has annotated or edited, which are frozen. Defaults live on the place, overrides on the visit. All timestamps are stored as UTC epoch millis plus the device time-zone ID at capture. Pings use Long keys; places, visits, trips, tags and tag groups use UUID keys plus updatedAt and a soft-delete flag, so sync can be added later without a schema rewrite (the Long ids in the tables below apply to pings only).

### LocationPing (raw, append-only)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| timestamp | Long | UTC ms |
| lat, lng | Double | |
| accuracyM | Float | Horizontal accuracy |
| speedMps, bearing | Float? | When available |
| activity | Enum? | STILL, WALKING, IN_VEHICLE, ON_BICYCLE, UNKNOWN |
| source | Enum | FUSED, GEOFENCE_EXIT, MANUAL_START |
| sessionId | Long | FK to TrackingSession |

**TrackingSession:** id, start, end?, trigger (SCHEDULE, MANUAL), endReason (SCHEDULE_END, USER_STOP, USER_PAUSE, KILLED, PERMISSION_LOST). Gaps between sessions inside a scheduled window are shown as gaps.

### Place (a clustered location you return to)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| centroidLat, centroidLng | Double | Recomputed as visits are added |
| radiusM | Int | Default 75, user-adjustable |
| address | String? | Reverse-geocoded, cached |
| name | String? | User label for the place |
| defaultJobLabel | String? | Applied to new visits here |
| defaultBillable | Boolean? | Applied to new visits here |
| defaultTagIds | List<Long> | Applied to new visits here |
| ignored | Boolean | Visits here hidden from totals (fuel, lunch) |
| mergedIntoId | Long? | Set when merged into another place |

### Visit (time spent at one place)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| placeId | Long | FK |
| start, end | Long | UTC ms |
| jobLabel | String? | Null = inherit from place |
| freeNotes | String | |
| billable | Boolean? | Null = inherit from place |
| frozen | Boolean | True once annotated or manually edited |
| origin | Enum | DETECTED, MANUAL, SPLIT, MERGED |
| confidence | Float | Low when built across gaps or poor accuracy |

**Trip** (travel between visits): id, start, end, fromVisitId?, toVisitId?, distanceM, mode (VEHICLE, WALK, UNKNOWN), billable?, attributedJobLabel? (see travel attribution), frozen.

**TagGroup:** id, name, colour, sortOrder. **Tag:** id, groupId, name, sortOrder, archived. **VisitTag** and **TripTag:** join tables (many-to-many).

### Annotation shape (as exposed to UI and export)
```
{ jobLabel: String, freeNotes: String, tags: Tag[], billable: Boolean }
```
Resolved value = visit field if set, else place default.

### Schedule
- **ScheduleTemplate:** a weekly bitmap of 336 half-hour slots (7 days x 48), local time.
- **ScheduleOverride:** weekStart (ISO date of Monday) plus its own 336-slot bitmap. Replaces the template for that week only.
- **ManualOverride:** type (START, PAUSE), start, end? (until stopped, until end of day, fixed duration, until next window).

**Settings (DataStore):** rounding increment (none, 6, 15, 30 min), rounding mode (nearest, up), minimum billable visit, travel attribution rule, raw-ping retention days, review reminder on/off and time, theme.

## Tracking engine
The OS wakes the app; the app never polls on its own timer. A foreground service runs only inside active windows, and motion state decides how often location is requested. Starting values below are tuning parameters, set in one config object and adjusted during dogfooding.

### Capture modes
| Mode | Entered when | Location request | Other |
|---|---|---|---|
| STATIONARY | Activity STILL, or no movement beyond 50 m for 3 min | Low power, one fix per 3 min; a keepalive ping is saved every 10 min, and the last still fix is saved on leaving | Geofence around current position (user setting, default 100 m: 100/150/200/300); exit wakes MOVING |
| MOVING | Activity IN_VEHICLE / WALKING / ON_BICYCLE, or geofence exit | Balanced power, interval 20 s, min displacement 25 m, maxUpdateDelay 2 min (batched) | Remove stay geofence |
| SUSPENDED | Outside schedule, paused or stopped | None | Service stopped; alarm armed for next window |

### Ping filtering (at capture, before insert)
- Drop fixes with accuracy worse than 100 m.
- Drop fixes implying speed above 70 m/s from the previous accepted fix.
- In STATIONARY, drop fixes within the current accuracy circle (jitter).

### Schedule engine
- Effective schedule for a week = override if present, else template; manual overrides layered on top.
- On any change (edit, boot, time-zone change, window edge), compute the next transition and arm one inexact AlarmManager alarm. A few minutes of slack is acceptable.
- At a window start: start the foreground service. At a window end: close the session, stop the service, enqueue segmentation.
- Re-arm on BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED and app update.
- Android 12+ limits starting foreground services from the background. Validate early that the alarm-triggered start is permitted (exact-alarm exemption or user-visible notification path); if not, fall back to a high-priority notification the user taps, and flag it in health status.

### Manual controls
- **Start** (outside schedule): options are until stopped, until end of day, or for a chosen duration.
- **Pause** (inside schedule): options are 30 min, 1 hour, or until the next window.
- **Stop:** ends the current session; tracking resumes at the next scheduled window.

### Tracking states (shown in the nav indicator)
| State | Meaning |
|---|---|
| TRACKING | Service running, recent fix received |
| IDLE | Outside scheduled hours, nothing wrong |
| PAUSED | User paused; shows resume time |
| ATTENTION | Inside a window but not tracking: permission revoked, service killed, location off, or no fix for 20+ min while moving |

### Health monitor
- Heartbeat: the service writes a timestamp each time it receives a fix or batch.
- A periodic WorkManager check (every 15 min) compares the effective schedule against the heartbeat; if inside a window with no heartbeat for 20 min, set ATTENTION, try to restart, and post a notification explaining the cause.
- Record each detected outage as a gap so the timeline can show it.

**Foreground notification:** low-importance, ongoing, shows state and quick actions (Pause 1 h, Stop). Doubles as the transparency signal.

## Segmentation
Segmentation is a pure function from pings (plus frozen records) to visits, trips and place assignments. It runs as a WorkManager job every 15 minutes while tracking, at each window end, on app open and when charging. It never touches frozen time ranges.

### Parameters (initial values)
| Parameter | Value | Purpose |
|---|---|---|
| stayRadius | 60 m | Max distance from the stay's running centroid |
| minStayDuration | 5 min | Shorter stops are part of the trip |
| maxGapToBridge | 45 min | Gap inside one stay is bridged if position on both sides is within stayRadius |
| placeMatchRadius | 75 m (or place's own radius) | Assign a stay to an existing place |
| minTripDistance | 150 m | A move shorter than this between two stays merges them |
| shortTripMerge | 3 min | Stays at the same place separated by a shorter blip are merged |
| signalGap | 15 min | A silence inside a stay longer than this marks it bridged (lower confidence) |

### Algorithm
1. Load pings for the range since the last processed checkpoint, minus a 30 min overlap, excluding frozen ranges.
2. Stay-point detection: walk pings in order; grow a candidate stay while each ping is within stayRadius of the running accuracy-weighted centroid. Close it when a ping falls outside; keep it if its duration is at least minStayDuration.
3. Bridge gaps: if no pings for up to maxGapToBridge and the next ping is within stayRadius, extend the stay. Mark confidence lower when bridged.
4. Everything between two stays becomes a trip; compute distance from pings and mode from dominant activity.
5. Merge stays at the same place separated by trips shorter than minTripDistance or shortTripMerge.
6. Place assignment: match each stay's centroid to the nearest non-merged place within its radius; otherwise create a new place and queue reverse geocoding.
7. Apply place defaults (job label, billable, tags) to new visits.
8. Upsert results, advance the checkpoint.

### Frozen records
- A visit or trip becomes frozen on any annotation (job, note, tag, billable) or manual edit (trim, split, merge, create, delete).
- Reprocessing treats frozen ranges as fixed boundaries and only regenerates unannotated time between them.
- Deleting a visit is a frozen edit: its range is marked excluded so it is not re-detected.
- Unfreeze is available only through an explicit "reset to detected" action, which discards the annotation after confirmation.

### Same-site disambiguation (e.g. several boats at one marina)
- Each place has an adjustable radius; shrinking it causes new detections to split into separate places.
- A visit can be split at a chosen time into two visits, each labelled separately.
- Reassigning a visit to a different place is a manual edit.

### Travel attribution (affects summary only; trips are stored unattributed unless frozen)
- Default: a trip is attributed to the job at its destination.
- Setting options: destination, origin, split evenly, or unattributed.
- A trip to or from an ignored place is unattributed unless the user changes it.
- Per-trip override in the drawer.

**Gaps:** time inside a scheduled window with no session or no pings beyond maxGapToBridge is emitted as a Gap segment for display, never as a visit.

## UI
Two main screens and one drawer. A bottom bar holds Timeline (left), the tracking toggle (centre, larger) and Settings (right). The app opens on Timeline at today.

### Bottom bar and tracking toggle
- Centre button shows state: TRACKING (accent colour, slow pulse ring), IDLE (neutral outline), PAUSED (amber, static), ATTENTION (red badge).
- Tap: Start if idle or stopped; Pause/Stop sheet if tracking; Resume if paused; diagnostic sheet if ATTENTION.
- Long-press: quick sheet with duration options.
- Timeline icon shows a badge with the count of visits needing review.

### Timeline
Top bar: date-range picker (Today, Yesterday, This week, Last week, Custom) with previous/next arrows; segmented control List | Map | Summary.

**List view**
- Vertical timeline: time column left, segment cards right.
- Visit card: place name or address, job label, duration, tag chips, billable icon, small static map thumbnail. Unlabelled visits get a "needs review" dot.
- Trip row (compact): from/to, duration, distance, mode icon.
- Gap row: hatched, with cause where known ("tracking stopped 2:10 to 3:40 pm").
- Day headers with daily on-site, travel and billable totals.
- Tap a card: opens the edit drawer. Long-press: multi-select mode with a bulk-action bar (set job, add tags, billable on/off, ignore place).
- Filter chip: "Needs review only".

**Map view**
- Full-width MapLibre map with the route polyline for the selected range and pins for visits (numbered in order).
- Horizontal scrubber under the map: a strip of coloured blocks (visits by job colour, trips grey, gaps hatched) proportional to time. Dragging moves a position marker along the route and centres the map; a time label floats above the thumb.
- Snaps to visit starts on release; tapping a visit block or pin opens the drawer.
- Date-time jump: tap the time label to pick an exact time.

**Summary view**
- Table grouped by job (toggle: by job, by place, by day): visit count, on-site time, attributed travel, billable total, after rounding.
- Unlabelled time shown as its own row so nothing is lost.
- Tap a row to see its visits; export button for CSV or PDF of the current range and grouping.

### Edit drawer
Bottom sheet, half height expandable to full. Same component for visits and trips.
- Header: place name/address, time range, duration, mini map with the place radius.
- Job label: text field with autocomplete from previous labels; "set as default for this place" checkbox.
- Tags: grouped chips, multi-select, with inline "new tag".
- Billable: toggle, inheriting the place default.
- Notes: multi-line free text; keyboard dictation works by default.
- Time edits: trim start/end with time pickers; split at a time; merge with previous or next; reassign to another place; delete.
- Trips additionally: travel attribution override.
- Changes save immediately and freeze the record; undo snackbar on destructive edits.
- Manual visit: add from the List view ("+" between segments) with place search or map pin.

### Settings (hub)
- **Schedule:** weekly grid, 7 columns x 48 half-hour rows, 6:00 to 20:00 visible by default and scrollable. Drag to paint or erase; tap a day header to copy that day to others; presets (Mon to Fri 7:00 to 17:00, clear). Toggle at top: Every week (template) or This week only (override), with week navigation for overrides.
- **Tags:** tag groups as sections, tags as chips; add, rename, recolour, reorder by drag, archive. Archived tags stay on past visits.
- **Places:** list sorted by last visit; edit name, default job, default billable and tags, radius (with map preview), ignore toggle; merge two places; delete (visits become unassigned and re-detected unless frozen).
- **Billing:** rounding increment and mode, minimum billable visit, travel attribution rule.
- **Reminders:** optional "review your day" notification at a set time on scheduled days.
- **Maps:** offline area download, cache size, clear cache.
- **Data:** export backup, import backup, raw-ping retention (30, 90, 365 days, forever), delete all data.
- **Tracking help:** permission status, battery optimisation status, device-specific guidance for aggressive OEMs, "test tracking" self-check.
- **About:** version, licence, source link.

### Onboarding (first run)
1. What the app does and the privacy promise.
2. Location "while in use", then "allow all the time" with an explanation screen before the system prompt.
3. Notifications (Android 13+) and physical activity permissions.
4. Battery optimisation exemption, with OEM-specific instructions when detected.
5. Paint the schedule (preset offered).
6. Done: shows the tracking state and when the next window starts.

## Summary, rounding and export
Totals are computed from resolved annotations at read time, so changing a place default or a rounding rule updates every summary without migrating data.

### Calculation rules
- On-site time = sum of visit durations, excluding ignored places and deleted ranges.
- Billable = visits whose resolved billable is true, plus trips attributed to a billable job (unless the trip is overridden).
- Rounding applies per visit, then sums (matches how tradespeople usually invoice). Option for rounding the daily total per job instead.
- Visits shorter than the minimum billable visit are shown but excluded from billable totals.
- Visits crossing midnight or a range boundary are clipped to the range.

### CSV export (one row per segment)
```
date,start,end,duration_min,rounded_min,type,job,place,address,tags,billable,notes,distance_km
```
Tags are joined with `;` and prefixed by group (`Work:Install`). Times in local time with an ISO offset.

**PDF export:** a one-page-per-job report for the selected range with header (job, range, totals), a table of visits and attributed travel, and notes. Plain and printable, no logo by default.

**Backup:** a single versioned JSON (or zipped SQLite) file containing all entities and settings, saved through the Storage Access Framework so the user picks the location (device, Drive, etc.). Import validates the schema version and offers replace or merge.

## Permissions, privacy and Play Store
The app requests only what tracking needs, explains each permission before the system prompt, and keeps working visibly degraded rather than silently broken when one is missing.

| Permission | Why | If denied |
|---|---|---|
| ACCESS_FINE_LOCATION | Position fixes | App cannot track; onboarding blocks |
| ACCESS_BACKGROUND_LOCATION | Tracking while screen off | ATTENTION state; explain and link to settings |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION | Reliable capture | Required |
| ACTIVITY_RECOGNITION | Motion gating to save battery | Falls back to distance-based gating; higher battery use |
| POST_NOTIFICATIONS (13+) | Foreground notification, alerts, review reminder | Tracking runs but alerts are lost; warn |
| RECEIVE_BOOT_COMPLETED | Re-arm schedule after reboot | Required |
| SCHEDULE_EXACT_ALARM | Only if inexact alarms prove unreliable in testing | Use inexact |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Reduce OEM killing | Guidance screen instead |

Maps tiles need INTERNET; no other network use.

### Privacy commitments
- No account, analytics, crash reporting SDKs or ads in the MVP.
- The only network traffic is map tiles and the platform geocoder; both documented in the privacy policy.
- Raw pings deleted after the retention period; delete-all wipes the database and caches.
- Android auto-backup disabled for the database (allowBackup=false or explicit exclusion), so location history never leaves the device without a user-initiated export.
- Prompt users to exempt the app from Android's unused-app permission reset.

### Play Store requirements
- Background location declaration in Play Console with core-feature justification and a short demo video showing the prominent disclosure and the feature.
- In-app prominent disclosure before requesting background location, stating what is collected, why, and that it stays on device.
- Data safety form: location collected, not shared, stored on device only.
- Foreground service type declaration for location.
- Privacy policy URL (can live in the repo / project site).
- Sideloaded APK for personal use until review passes.

## Testing
Segmentation is tested offline against synthetic and recorded traces; tracking is proven only by carrying real devices on real days.

**Unit tests (segmentation module, no Android)**
- Synthetic trace generator: stays with configurable jitter, drives, walks, signal gaps, multi-day ranges.
- Golden cases: single site all day; two sites with a drive; marina with two adjacent boats; indoor drift with 100 m accuracy spikes; 40 min signal gap inside one stay; fuel stop under 5 min; quick return to the same site; trip crossing midnight.
- Frozen-range invariants: reprocessing never alters a frozen record and never creates overlapping segments.
- Summary math: rounding modes, minimum billable, travel attribution, range clipping.

**Recorded traces**
- Debug build exports raw pings as GPX/CSV; real days become regression fixtures.
- Debug screen overlays raw pings, accuracy circles and detected segments on the map for tuning.

**Device and lifecycle tests**
- Schedule edges, reboot mid-window, time-zone change, DST change, app update, permission revoked mid-window, location toggled off, Doze (`adb shell dumpsys deviceidle force-idle`).
- At least one Samsung and one other OEM device during dogfooding.
- Battery: log daily drain with tracking on versus a baseline day; review Android battery usage stats.

**UI tests:** Compose tests for drawer save/freeze, multi-select bulk edit, schedule grid painting, and export output.

## Build phases and future Pro tier
Build the riskiest part first and carry it on real jobs while the UI is built around real data. Each phase ends with something usable on the phone.

1. **Foundation:** project scaffold, modules, Room schema and migrations, DataStore, navigation shell with bottom bar.
2. **Tracking core:** foreground service, fused location, activity recognition, geofence, capture filtering, schedule engine with a hard-coded schedule, boot and time-change receivers, health monitor, debug ping export. Start dogfooding here.
3. **Segmentation:** stay detection, trips, place clustering, gaps, frozen-range handling, WorkManager jobs, synthetic-trace test suite, debug map overlay.
4. **Timeline list and drawer:** list view, visit/trip/gap cards, edit drawer with job, notes, tags, billable, time edits, manual visits, multi-select bulk edit, needs-review badge.
5. **Tags and places:** tag groups CRUD, places management (rename, defaults, radius, ignore, merge).
6. **Map view:** MapLibre, route and pins, horizontal scrubber, date-time jump, offline tile caching.
7. **Schedule grid:** weekly template, per-week override, painting, presets, manual start/pause options.
8. **Summary and export:** grouping, rounding, travel attribution, CSV, PDF, backup and restore.
9. **Onboarding and hardening:** first-run flow, permission and battery guidance, ATTENTION diagnostics, review reminder, privacy policy, Play Console declarations, release build.

### Future Pro tier (not MVP)
- Optional sync of derived data only (places, visits, trips, annotations, daily summaries). Raw pings never leave the device.
- Self-hostable sync server, open source; hosted option as the paid convenience.
- MCP server on top of the synced store, read-mostly, scoped revocable tokens:
  - `get_visits(from, to)`
  - `get_summary(range, group_by: job | place | day)`
  - `list_places`, `label_place(place_id, job_label)`
  - `add_note(visit_id, text, tag_ids?)`
  - No remote start/stop of tracking.
- First consumer: Walkflow, pulling time per job into project finalisation, plus agent queries such as a weekly time-per-site rundown.
- To keep this cheap later: stable UUIDs on all derived entities from day one, updatedAt on every row, and soft deletes, so sync can be added without a schema rewrite.
