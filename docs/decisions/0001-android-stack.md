# 0001 — Android stack

Status: accepted (2026-10-20). Supersedes `0001-default-stack.md` (web template) for this repo.

## Context
Tarry is a personal, on-device Android app that records location during scheduled work hours and turns it into labelled visits and trips for invoicing. No server, no account. Full spec: `docs/specs/tarry-mvp.md`.

## Decision
- **Language/UI:** Kotlin, Jetpack Compose, Material 3 (custom theme from `docs/design/tokens.md`). Single activity.
- **SDK:** minSdk 29, target/compile 37. AGP 9 with built-in Kotlin.
- **Architecture:** MVVM, unidirectional state, Flows; Hilt for DI (fallback: manual container if Hilt/KSP lag AGP 9).
- **Modules:** `:segmentation` (pure Kotlin/JVM), `:core-data` (Room + DataStore), `:app` (tracking, UI, export as packages).
- **Storage:** Room with exported schemas and tested migrations; DataStore for settings.
- **Location:** Play Services fused location, Activity Recognition transitions, Geofencing. Foreground service (type location) only inside windows; WorkManager for segmentation; exact AlarmManager alarm for window edges.
- **Maps:** MapLibre Native + OpenFreeMap tiles (no key, no account).
- **IDs:** UUID + `updatedAt` + soft delete on all derived entities; Long keys on pings and sessions only.
- **Licence:** GPL-3.0.
- **Distribution:** sideloaded signed APK via GitHub Releases for the MVP; Play Store post-MVP.

## Consequences
- Requires Google Play Services; an F-Droid flavour is a later option.
- The only network traffic is map tiles and the platform geocoder.
- The web template's skills (Neon, Go Lambda, React) do not apply to this repo.
