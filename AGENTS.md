# AGENTS.md — shared project conventions (Claude Code + Cursor)

> Single source of truth. `CLAUDE.md` imports this file; `.cursor/rules/project.mdc` points here.
> Keep it short. Every line should prevent a mistake the agent would otherwise make.

## What this is

**Tarry** — a personal Android app that records location during scheduled work hours, segments it into visits and trips, and lets the user label them later for invoicing. On-device only. Spec: `docs/specs/tarry-mvp.md`. Build plan and phase specs: `docs/specs/`.

## Stack (details: docs/decisions/0001-android-stack.md)

- Kotlin, Jetpack Compose, Material 3 (custom theme from `docs/design/tokens.md`), single activity.
- minSdk 29, target/compile 37, AGP 9 built-in Kotlin, version catalog in `gradle/libs.versions.toml`.
- MVVM + unidirectional state + Flows; Hilt; Room (exported schemas); DataStore; WorkManager.
- Play Services fused location, Activity Recognition, Geofencing. MapLibre Native + OpenFreeMap.

## Commands (Windows: `gradlew.bat`; CI/Unix: `./gradlew`)

- build: `gradlew.bat assembleDebug`
- unit tests: `gradlew.bat testDebugUnitTest` · segmentation only: `gradlew.bat :segmentation:test`
- lint: `gradlew.bat lintDebug`
- instrumented/Compose tests (device or emulator attached): `gradlew.bat connectedDebugAndroidTest`
- install on device: `gradlew.bat installDebug` (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`)

## Architecture map

- `:segmentation` — pure Kotlin/JVM. Stay-point, trip, gap and place clustering. **No Android imports.** Fully unit-tested with synthetic and recorded traces (`src/test/resources/traces/`).
- `:core-data` — Room entities, DAOs, repositories, migrations, DataStore settings. Schemas in `core-data/schemas/`.
- `:app` — packages: `tracking/` (service, location/activity clients, schedule engine, health monitor), `segmentation/` (WorkManager glue), `ui/` (timeline, drawer, settings, onboarding, theme), `export/` (CSV, PDF, backup).
- `docs/` — spec, phase specs, ADRs, handoffs, design tokens.

## Hard rules

1. Never commit to `main`. One branch (ideally one worktree) per phase or task.
2. **Privacy:** no network calls except map tiles and the platform Geocoder. No analytics, crash SDKs, ads or accounts. Raw pings never leave the device except via a user-initiated export. Auto-backup stays disabled for the DB.
3. **Data model:** UUID + `updatedAt` + `deleted` on Place, Visit, Trip, Job, Tag, TagGroup; Long keys on pings and sessions only. Timestamps are UTC epoch ms plus capture time-zone ID. Frozen (annotated/edited) records are never touched by segmentation.
4. **Room:** every schema change bumps the version, commits the exported schema JSON, and ships a migration with a `MigrationTestHelper` test. No `fallbackToDestructiveMigration`.
5. **Tracking:** the app never polls on its own timer — the OS wakes it (alarms, geofences, activity transitions, WorkManager). All tunables live in `TrackingConfig` / segmentation params, not scattered literals.
6. **Never fail silently:** any state where tracking should run but isn't must surface as ATTENTION and/or a Gap.
7. **Tests:** every behaviour change ships with a test. Segmentation changes need a golden-case test. CI must be green before merge.
8. **Plan first** for anything touching more than ~3 files, the schema, the tracking service, or permissions (use the `plan-feature` skill).
9. **Small diffs.** No drive-by refactors. List unrelated problems; don't fix them.
10. **Secrets:** never read or write `.env*`, keystores, `keystore.properties` or tokens. Release signing comes from CI secrets.
11. **UI:** follow `docs/design/tokens.md` via the M3 theme; meet WCAG 2.2 AA (touch targets ≥ 48dp, content descriptions, contrast).
12. **Don't guess** — if the spec or handoff is ambiguous, stop and ask.

## Handoffs (Claude ⇄ Cursor)

- Claude plans and writes `docs/handoffs/<task>.md` (template: `docs/handoffs/_TEMPLATE.md`).
- Cursor implements from that file. If stuck after two honest attempts, write `docs/handoffs/<task>-blocked.md` and stop.
- If Cursor is not available, implement using the Claude subagents in `.claude/agents/`.
