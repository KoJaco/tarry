# Handoff: phase 8b.1 — entitlement core and the tracking lock

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-12 · **Branch:** `feat/phase-8b1-entitlement` (never main).
Read first: `AGENTS.md` (rules 2, 3, 5, 6, 7), `docs/decisions/0004-play-billing-network.md`, `docs/handoffs/phase-8-launch.md`
§8b and owner decisions 2026-11-09, `docs/design/ui-rules.md`, `docs/brand/brand-and-copy.md:111–141`.

## Context
7-day local trial, then a one-time lifetime purchase (8b.2 adds Play Billing). When the trial ends, **new tracking locks**
(schedule and manual start); viewing, editing, summary, export, backup and delete stay open. The lock is always visible
(rule 6). All checks go through **one `Entitlement` interface**. This PR has **no Play dependency**: everything is tested
with fakes. Naming: "Billing" already means billable-time rounding (`BillingRules`, Settings "Billing") — use
`access` / `Entitlement` / `Purchase`, never "billing", in new code and resources.

Owner decisions (11 Nov): debug builds count as **owned** by default, with a debug-only state switch; the locked pill reads
**"Unlock"**; the near-end nudge (8b.2) is one in-app card.

## Build exactly this
### 1. Model (`app/src/main/java/me/tarry/app/access/`)
- `sealed interface AccessState`: `NotStarted` (onboarding open, no anchor), `Trial(endsAtMs)`, `TrialEnded`, `Owned`,
  `Pending(trialEndsAtMs: Long?)`, `Revoked(trialEndsAtMs: Long?)`.
  **`canTrack` = `Owned`, `NotStarted`, or the trial still running.** Pending/Revoked only change wording; a refund inside
  the trial keeps the trial.
- `interface Entitlement { val state: StateFlow<AccessState>; suspend fun current(): AccessState; suspend fun canTrack(): Boolean }`
  — the **only** gate. Hilt: `RealEntitlement` in release builds; in debug builds a `DebugEntitlement` that reads a
  debug override (default **Owned**) and otherwise delegates to the real one. Choose by `BuildConfig.DEBUG` in the module.
- `TrialClock`: anchor = `SettingsRepository.onboardingCompletedAtMs()` (already stamped by `OnboardingGate` for fresh and
  upgrading installs); `endsAt = anchor + 7 × 24 h` exactly (zone-independent; constant in one place). **Rollback guard:**
  persist a high-water `access_last_seen_ms` and evaluate with `max(now, lastSeen)`. Days left (display only) =
  ceil((endsAt − now) / 24 h). Uses the injected `TrackingClock`.
- Purchase cache in the settings DataStore: `access_purchase_state` (NONE / PENDING / OWNED / REVOKED) and
  `access_purchase_checked_ms`, written only through a small `PurchaseStore` (8b.2's Play client feeds it).
- `RealEntitlement.state` combines trial clock + purchase cache, re-evaluated on changes; expose a flow the UI collects.

### 2. Device-local settings keys (backup hardening)
- One shared set in `core-data/.../settings/Settings.kt` of **device-local keys**: last backup, pruned-before, both
  onboarding stamps, every `access_*` key, the debug override, the (8b.2) trial-card flag.
- Backup **skips** them (replace the hard-coded skip set in `BackupManager.kt:163`). Restore **strips** any of them that
  arrive in the file and re-applies the phone's own values, **removing** a key whose old value was null
  (`BackupManager.kt:246–261`, `BackupPrefs.kt`). Today a hand-edited backup could move the trial anchor.
- Delete-all already leaves settings untouched (`BackupManager.kt:314–330`) — keep it so; add a test.

### 3. The lock — two places, not one
1. **`TrackingController.reconcileLocked`** (`TrackingController.kt` ~369–410; every start path goes through it): when
   `!entitlement.canTrack()`, treat as inactive — no `startService`; stop a running session (end reason `SCHEDULE_END`,
   **no enum change**); keep re-arming. `start()` / `startUntil()` refuse up front (no START override saved) and return a
   result the shell can act on (e.g. `StartResult.Locked`). `onServiceStarted` re-checks before `coordinator.begin`.
   `rearm()` adds the **trial end time** to its `min(...)` while a trial runs unowned, so a running session stops on time.
2. **Health**: `TrackingSnapshotProvider` (`HealthChecker.kt` ~20–41) gets a `locked` flag; locked ⇒ `shouldRun = false`
   and a standing, **attention-only** issue `AttentionCause.TRIAL_ENDED` (no Gap; one alert through the existing
   attention-only path, as `standingIssue` does). Without this a locked window reads as SERVICE_NOT_RUNNING with false
   Gaps, alerts and repeated reconciles.
- `TrackingStateReducer`: locked ⇒ `ATTENTION` with cause `TRIAL_ENDED` (above IDLE).
- **Segmentation:** windows after the lock began are not passed to segmentation. Persist `access_locked_since_ms` when the
  lock starts, clear it on Owned; clip windows in the app glue (`app/.../segmentation/SegmentationInputs.kt` /
  `SegmentationRunner`). **No `:segmentation` module change.** Locked days must not fill with red NO_SESSION gaps.
- Every exhaustive `when` over `AttentionCause` (sheet, alert notifier, health screen, etc.) gets the new branch.

### 4. What the user sees
- **Pill** (`ui/shell/TrackingToggle.kt` ~241–253): cause TRIAL_ENDED ⇒ label **"Unlock"** with a lock mark (an existing
  icon if one fits, else a new vector drawable); content description "Tracking: trial ended, unlock Tarry". Other ATTENTION
  causes unchanged.
- **Sheet** (`ui/shell/TrackingSheet.kt` ~135–144, 207–226): message "Trial ended · Unlock Tarry to keep tracking. Your data
  stays yours." and fix row **"Unlock Tarry"** → new `TrackingSheetActions.onOpenPaywall`. In IDLE, if a start is refused
  as Locked, open the paywall.
- **Alert notification** (`tracking/HealthAlertNotifier.kt` ~51–58): the same message; tapping opens the app on the paywall
  (intent extra handled in `MainActivity` → push `PaywallRoute`).
- **`PaywallRoute`** (`ui/navigation/Routes.kt`, `ui/TarryApp.kt` entry + content parameter): a **minimal placeholder**
  screen in 8b.1 — title "Unlock Tarry", one muted line "Purchase arrives in the next update", and the data promise
  "You can still view, edit, export, back up and delete everything." 8b.2 replaces it. Full screen like onboarding
  (bottom bar hidden).
- **Debug switch:** a debug-only row in Sessions next to "Start test window" (same `BuildConfig.DEBUG` gating) that cycles
  Owned / Trial (6 days left) / Trial (1 day left) / Trial ended / Pending / Revoked by writing the debug override (Trial
  variants set an override end time relative to now). Shows the current value.
- Strings in a new `res/values/strings_access.xml`; Australian English, sentence case, no full stops on buttons/titles.

## Tests (rule 7)
- `TrialClock`: no anchor ⇒ NotStarted; exactly 7 × 24 h edges (end − 1 ms trial, end ⇒ ended); a zone change doesn't move
  the end; clock moved back doesn't extend (high-water); days-left rounding.
- `AccessState.canTrack` table for every state × trial running/ended, including Pending/Revoked inside the trial.
- `DebugEntitlement`: default Owned; each override value.
- Controller with a `FakeEntitlement` (put it with the other tracking fakes): a window while locked starts nothing; manual
  start returns Locked and saves no override; a running session stops at trial end (rearm includes it); owned again ⇒ the
  next reconcile starts.
- Health: locked window ⇒ no Gap, exactly one TRIAL_ENDED alert, no repeated reconcile; reducer ⇒ ATTENTION / TRIAL_ENDED.
- Segmentation inputs: windows clipped at `access_locked_since_ms`; a SegmentationRunner case: a locked day writes no
  NO_SESSION gap.
- Backup/restore: device-local keys never written; a backup carrying an onboarding stamp or `access_*` key can't change
  the phone's values (present and absent cases); delete-all keeps them.
- Compose: pill "Unlock" + description; sheet message and "Unlock Tarry" routes to the paywall; placeholder paywall renders;
  48 dp; light/dark; font 2.0; guard tests (`NoColorLiteralsTest`, `ContrastGuardTest`) green.

## Rules
No Room schema change (all state in DataStore; if one seems needed, stop and report). No `:segmentation` change. No new
network use, no Play dependency in this PR. Small diffs, no drive-by refactors. `./gradlew.bat testDebugUnitTest
:segmentation:test lintDebug assembleDebug` green, and `assembleRelease` once (the release binding must compile).
Commits by item, explicit paths (include this handoff in the first), never `.idea/` or `.claude/`, each ending with exactly
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push. No adb, no devices. Report files, tests, decisions,
skips.
