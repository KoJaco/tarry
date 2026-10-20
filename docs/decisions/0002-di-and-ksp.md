# 0002 — DI, annotation processing and navigation

Status: accepted (2026-10-20). Outcome of phase 1 task T1 (toolchain spike).

## Context
ADR 0001 chose Hilt, with a manual container as fallback in case Hilt or KSP did not yet support AGP 9 with built-in Kotlin. Room also needs KSP. Phase 1 also has to pick between Navigation Compose and Navigation 3.

## Spike
Throwaway commit `015e5ff` (reverted in `e07986c`). It added KSP, the Hilt Gradle plugin, the Room Gradle plugin, a `@HiltAndroidApp` application, an `@AndroidEntryPoint` activity, and a one-entity Room database to `:app`.

- Toolchain: AGP 9.4.1 with built-in Kotlin (no `kotlin-android` plugin), KGP resolved to 2.2.10, Gradle 9.6.0, JDK 25 (Android Studio JBR).
- Versions: KSP 2.3.12, Hilt 2.60.1, Room 2.8.5.
- Result: `assembleDebug` passed with no warnings under `--warning-mode=all`. `kspDebugKotlin` generated `SpikeDb_Impl`/`SpikeDao_Impl` and `Hilt_MainActivity`. `copyRoomSchemas` exported the schema JSON. The APK cold-started on the Pixel 10 (API 37) with no crash.

## Decision
- **DI:** Hilt 2.60.1 via KSP. We don't need the manual `AppContainer` fallback.
- **Annotation processing:** KSP 2.3.12 only (no kapt). KSP 2.3.x is versioned separately from Kotlin, so it doesn't have to be bumped with each KGP release.
- **Room:** 2.8.5 with the `androidx.room` Gradle plugin (`room { schemaDirectory(...) }`).
- **Navigation:** Navigation 3 (`androidx.navigation3` 1.2.0 stable). It is Google's current recommendation for new Compose apps. The app owns the back stack as plain state (`rememberNavBackStack`), which suits a two-destination bottom bar and makes tests simple. Routes are `@Serializable` `NavKey` objects (`TimelineRoute`, `SettingsRoute`), which gives the type safety the phase 1 spec asks for. ViewModels come from `hilt-lifecycle-viewmodel-compose` (`hiltViewModel()`), with entry-scoped ViewModels from `lifecycle-viewmodel-navigation3`. We use Navigation 3 instead of `hilt-navigation-compose`.

## Consequences
- Library upgrades must stay compatible with the Kotlin 2.2.x compiler that AGP 9.4.1 bundles. A library built with a newer Kotlin metadata version has to wait, or the KGP version has to be overridden on purpose in a separate ADR.
- If a future AGP breaks Hilt, the fallback is still a manual container, and that switch needs the owner's sign-off.
