# Phase 1 — Foundation

Branch: `feat/phase-1-foundation`. Size: M. Parent: `docs/specs/build-plan.md`. Source spec: `docs/specs/tarry-mvp.md`.

## Goal
A three-module project that builds in CI. It contains the complete Room v1 schema (all MVP entities plus the plan's deltas), DataStore settings, Hilt wiring, and the real navigation shell (Timeline | tracking toggle | Settings). It ships no tracking yet; phase 2a builds on it.

## Non-goals
Tracking, permissions requests at runtime, segmentation logic, real screens, theme tokens (phase 3).

## Tasks (in order)

### T1 — Toolchain spike (claude, S) — do first, stop if it fails
- Add KSP and Hilt to a throwaway commit on this branch. Confirm `assembleDebug` works with **AGP 9.4.1 built-in Kotlin (no `kotlin-android` plugin), Kotlin 2.2.x, Gradle 9.6, JDK 25**.
- Check that Room's KSP processor runs under the same setup.
- If Hilt's Gradle plugin is incompatible, stop and report. The fallback is a manual DI container (`AppContainer` on the `Application`), which needs the owner's sign-off. Record the outcome in `docs/decisions/0002-di-and-ksp.md`.

### T2 — Version catalog (implementer, S)
- Update the stale entries (core-ktx, lifecycle, activity, junit ext, espresso) to the latest stable versions.
- Add: KSP, Hilt (+ hilt-navigation-compose, hilt-work), Room (runtime, ktx, compiler, testing), DataStore preferences, WorkManager, Navigation Compose (type-safe routes with kotlinx-serialization), kotlinx-coroutines (core, android, test), kotlinx-serialization-json, play-services-location, Robolectric, Truth (or kotlin.test).
- Pin exact versions; no `+` or dynamic versions.

### T3 — Modules (implementer, M)
- `settings.gradle.kts`: include `:segmentation` and `:core-data`.
- `:segmentation`: `kotlin("jvm")` with JVM toolchain 17 or later, **no Android dependencies**. For now it holds only `geo/Haversine.kt` (distance in metres between two lat/lng) with a unit test, so CI exercises the module.
- `:core-data`: Android library (namespace `me.tarry.core.data`), KSP + Room + Hilt, `room { schemaDirectory("$projectDir/schemas") }`, exported schema committed.
- `:app` depends on both. Dependency direction: app → core-data and app → segmentation. core-data must not depend on segmentation (the WorkManager glue in `:app` maps between them).

### T4 — Room v1 schema (migration-guard, M)
Conventions: UUIDs stored as `String` (generated with `UUID.randomUUID()`), timestamps `Long` UTC ms, enums stored as `String` via TypeConverters, colours as ARGB `Int`. Every UUID entity has `updatedAt: Long` and `deleted: Boolean = false`. **No cascading FKs on soft-deleted entities.** Use indices instead. FKs only on ping → session and on the join tables.

| Entity | Fields | Indices / notes |
|---|---|---|
| `TrackingSession` | id Long auto, start, end?, zoneId, trigger (SCHEDULE, MANUAL), endReason? (SCHEDULE_END, USER_STOP, USER_PAUSE, KILLED, PERMISSION_LOST) | start |
| `LocationPing` | id Long auto, timestamp, zoneId, lat, lng, accuracyM Float, speedMps Float?, bearing Float?, activity? (STILL, WALKING, IN_VEHICLE, ON_BICYCLE, UNKNOWN), source (FUSED, GEOFENCE_EXIT, MANUAL_START), sessionId FK | timestamp, sessionId |
| `Job` | id, name, colorArgb, archived, updatedAt, deleted | name |
| `Place` | id, centroidLat, centroidLng, radiusM=75, address?, name?, defaultJobId?, defaultBillable?, ignored=false, mergedIntoId?, lastVisitAt?, updatedAt, deleted | lastVisitAt |
| `PlaceDefaultTag` | placeId, tagId (composite PK) | tagId |
| `Visit` | id, placeId? (null = unassigned after place delete), start, end, zoneId, jobId?, freeNotes="", billable?, frozen=false, origin (DETECTED, MANUAL, SPLIT, MERGED), confidence Float, updatedAt, deleted | start, placeId, jobId |
| `Trip` | id, start, end, zoneId, fromVisitId?, toVisitId?, distanceM Float, mode (VEHICLE, WALK, UNKNOWN), billable?, attributedJobId?, freeNotes="", frozen=false, updatedAt, deleted | start |
| `TagGroup` | id, name, colorArgb, sortOrder, updatedAt, deleted | |
| `Tag` | id, groupId, name, sortOrder, archived=false, updatedAt, deleted | groupId |
| `VisitTag`, `TripTag` | (visitId/tripId, tagId) composite PK | tagId |
| `ExcludedRange` | id, start, end, updatedAt, deleted | start |
| `Gap` | id, start, end, cause (TRACKING_STOPPED, SERVICE_KILLED, PERMISSION_LOST, LOCATION_OFF, NO_FIX, NO_SESSION), sessionId?, updatedAt, deleted | start |
| `ScheduleTemplate` | id Int = 0 (single row), slots ByteArray (336 bits = 42 bytes, Monday 00:00 first), updatedAt | |
| `ScheduleOverride` | weekStart String (ISO Monday, PK), slots ByteArray, updatedAt | |
| `ManualOverride` | id Long auto, type (START, PAUSE), start, end?, endMode (UNTIL_STOPPED, END_OF_DAY, DURATION, NEXT_WINDOW), active Boolean | active |
| `SegmentationCheckpoint` | id Int = 0, processedUntil Long | |

- `TarryDatabase` at version 1, `exportSchema = true`, no destructive fallback.
- DAOs: basic insert/upsert/query-by-range per entity, plus `Flow` queries for visits/trips/gaps in a time range (excluding `deleted`).
- Repositories: thin wrappers exposed through Hilt (`@Singleton`). Keep them minimal; phase 2 adds behaviour.
- A `Schedule` value type (in core-data) wrapping the 336-bit bitmap: `isActive(dayOfWeek, slot)`, `set`, and a `MON_FRI_7_TO_17` preset.

### T5 — DataStore settings (implementer, S)
`SettingsRepository` (Preferences DataStore) exposing a `Flow<Settings>`: roundingIncrementMin (0/6/15/30, default 0), roundingMode (NEAREST/UP, default NEAREST), minBillableMinutes (default 0), roundDailyTotalPerJob (default false), travelAttribution (DESTINATION/ORIGIN/SPLIT/UNATTRIBUTED, default DESTINATION), rawPingRetentionDays (30/90/365/null=forever, default 90), reviewReminderEnabled (false), reviewReminderMinuteOfDay (17:30), theme (SYSTEM/LIGHT/DARK). Add typed setters for each.

### T6 — App shell (implementer, M)
- `TarryApplication` with `@HiltAndroidApp`; `MainActivity` `@AndroidEntryPoint`.
- Replace the template's `NavigationSuiteScaffold`/`AppDestinations`/`Greeting` and the `ic_home`/`ic_favorite`/`ic_account_box` drawables with a custom bottom bar: Timeline (left), a larger centre tracking toggle, and Settings (right). Navigation Compose with type-safe routes `TimelineRoute` and `SettingsRoute`. The app opens on Timeline.
- The toggle renders a `TrackingState` (TRACKING, IDLE, PAUSED, ATTENTION) from a `TrackingStateRepository` interface. In phase 1 its only implementation emits IDLE (phase 2a replaces it). Each state has a distinct look per the spec (accent with pulse ring, neutral outline, amber, red badge) using the existing M3 theme colours. Content description: "Tracking: <state>". Touch target ≥ 48dp.
- Placeholder Timeline and Settings screens. Settings shows the current `Settings` values read-only, which proves DataStore wiring.
- Drop the `material3-adaptive-navigation-suite` dependency if it's unused.

### T7 — Manifest and privacy (implementer, S)
- `android:allowBackup="false"`. Rewrite `data_extraction_rules.xml` to exclude everything from both `cloud-backup` and `device-transfer`. Update `backup_rules.xml` to match (for API 29–30).
- Declare permissions (requested at runtime in phase 2a): `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `INTERNET`, `WAKE_LOCK` (WorkManager).
- `android:name=".TarryApplication"`.

### T8 — Tests (test-writer, M)
- `:segmentation`: Haversine against known distances (for example 1° of latitude ≈ 111.19 km, and a short ~60 m pair).
- `:core-data` (Robolectric, JVM, in-memory Room, so CI needs no emulator):
  - insert/read round-trip for every entity
  - range Flow queries exclude `deleted` rows
  - `Schedule` bit operations and the preset (Mon 07:00 active, Mon 17:00 inactive, Sat inactive)
  - a `SettingsRepository` defaults and update test
- `:app`: a Compose test (Robolectric or `androidTest`) checking the bottom bar shows three items, the toggle's content description reads "Tracking: Idle", and tapping Settings navigates.
- Remove the template `ExampleUnitTest`/`ExampleInstrumentedTest`.

### T9 — CI and docs (implementer, S)
- CI already runs `assembleDebug testDebugUnitTest lintDebug`. Make sure `:segmentation:test` runs: either add it explicitly or confirm `testDebugUnitTest` doesn't cover JVM modules (it doesn't, so add `test` for `:segmentation`).
- Update the AGENTS.md commands if anything changed.

## Acceptance criteria
- `./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes locally and in CI.
- `core-data/schemas/me.tarry.core.data.TarryDatabase/1.json` is committed and contains all 18 tables.
- The app installs on the Pixel 10 (`installDebug`), opens on Timeline, shows the three-part bottom bar with an IDLE toggle, and Settings shows the default settings.
- `adb shell dumpsys package me.tarry.app | grep -i allowBackup` reports backup disabled, and the declared permissions are listed.
- `:segmentation` has no Android imports (`grep -r "import android" segmentation/` is empty).

## Risks and open questions
- Hilt/KSP with AGP 9 built-in Kotlin (T1 decides).
- Navigation Compose vs Navigation 3: use whichever is the current stable recommendation, and note the choice in ADR 0002.
