# Handoff: phase 8b.2 — Play purchase, paywall, Settings row, trial card

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-13 · **Branch:** `feat/phase-8b2-play-purchase`, cut from
`feat/phase-8b1-entitlement` (8b.1 and 8b.2 ship together; never main).
Read first: `AGENTS.md` (rules 2, 6, 7, 10, 11), `docs/decisions/0004-play-billing-network.md`,
`docs/handoffs/phase-8b1-entitlement.md` (what already exists), `docs/design/ui-rules.md`, `docs/brand/brand-and-copy.md` §Pricing.

## Context
8b.1 built the entitlement gate, trial clock, purchase cache (`access/PurchaseStore`, `PurchaseState` NONE/PENDING/OWNED/
REVOKED) and the lock, with a placeholder `PaywallScreen`. This PR adds the real Google Play purchase of one non-consumable
in-app product **`lifetime`** and the screens around it. Naming: never "billing" in our own classes/resources (that word
means billable-time rounding); the library's own types are fine. No app data goes into any Play call (ADR 0004): no
obfuscated account/profile IDs, nothing but the product ID.

## Build exactly this
### 1. Dependency
- `com.android.billingclient:billing-ktx` **9.1.0** in `gradle/libs.versions.toml`, used by `:app` only. The library
  ships its own consumer R8 rules; add none unless `assembleRelease` proves one is needed.
- The library merges `com.android.vending.BILLING` into the manifest; that is expected. Add nothing else to the manifest.

### 2. `PurchaseClient` (`app/.../access/`)
```kotlin
interface PurchaseClient {
    val offer: StateFlow<OfferState>     // Loading, Available(formattedPrice), Unavailable
    suspend fun refresh()                // queryPurchases + product details; never throws
    suspend fun launch(activity: Activity): LaunchResult  // Started, AlreadyOwned, Unavailable, Error
}
```
- `PlayPurchaseClient` is the **only** class that imports `com.android.billingclient`. `@Singleton`, application context.
  `BillingClient.newBuilder(ctx).setListener(..).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).enableAutoServiceReconnection().build()`.
- Connect lazily on first use; a failed connection maps to `Unavailable` and keeps the cache (never throws to callers).
- `queryProductDetails` for `lifetime` / `INAPP` → `oneTimePurchaseOfferDetails.formattedPrice` (9.x: use the first
  offer of `oneTimePurchaseOfferDetailsList` if the singular getter is deprecated).
- `launch`: `launchBillingFlow` with that `ProductDetails`. `ITEM_ALREADY_OWNED` ⇒ run `refresh()` and return `AlreadyOwned`.
- **Owner decision (review):** a successful empty query revokes only on a foreground refresh (`ReconcileSource.UI`: paywall, `onStart`, restore). The process-start (`SYSTEM`) refresh may set OWNED/PENDING but never writes REVOKED (`mapPurchases(foreground = ...)`). `refresh()` returns `RefreshResult` (Ok(found) / Unavailable); a listener failure after a started launch is emitted on `PurchaseClient.failures`; the connect wait times out after 10 s.
- **Purchase handling** (listener and `queryPurchasesAsync(INAPP)` share one function), only for purchases whose
  `products` contains `lifetime`:
  - `PURCHASED` ⇒ `PurchaseStore.record(OWNED)`; if `!isAcknowledged` ⇒ `acknowledgePurchase` (Play refunds after 3 days
    without it). Acknowledge failure keeps OWNED and retries on the next refresh.
  - `PENDING` ⇒ PENDING.
  - A **successful** query (response OK) with no `lifetime` purchase ⇒ if the cache was OWNED or PENDING, record REVOKED
    (refund/cancel); if NONE stay NONE. A failed query (any non-OK, disconnected, offline) **never changes** the cache.
  - `USER_CANCELED` from the flow changes nothing.
- Put the mapping in a pure function (`PurchaseMapping.kt`: inputs = query ok?, list of (products, state, acknowledged),
  current cache → new cache + list of tokens to acknowledge) so it is fully JVM-testable without the library types.
- Refresh triggers: app process start (from `TarryApplication` on the capture scope, best effort) and `MainActivity.onStart`
  (next to `trackingState.refresh()`). The library-bound client is provided in **both** debug and release; the debug
  override in `DebugEntitlement` still wins (its "Real" option lets the owner test the true path).
- Hilt: bind `PurchaseClient` to `PlayPurchaseClient` in `di/AccessModule.kt`. Tests use a `FakePurchaseClient`.
- After the cache changes **to or from OWNED**, reconcile so a scheduled window starts (or stops) straight away:
  `TrackingController.reconcile(ReconcileSource.UI)` when the change came from the purchase flow / paywall / `onStart`
  (app in foreground, as `SessionsViewModel.kt:136` does for the debug switch), `ReconcileSource.SYSTEM` from the
  process-start refresh.

### 3. Paywall (replace `ui/access/PaywallScreen.kt`; add `PaywallViewModel`)
Full screen, bottom bar hidden (already wired), `ui-rules.md`: `headlineMedium` title, **one** ink `PrimaryButton`, no app bar,
a close affordance (`SecondaryButton` "Not now" at the bottom or an icon button top-start with content description "Close").
- Title "Unlock Tarry"; heading line "One price. No subscription."
- Status line by `AccessState`: Trial ⇒ "Free trial · N days left" (N from `TrialClock` days-left, "1 day left" singular via
  plurals); TrialEnded ⇒ "Your free trial has ended"; Revoked ⇒ "Your purchase was refunded or cancelled".
- Ticks (from brand doc, exact): Unlimited tracking and history · Jobs, summary and exports · Backups and restore · All future
  updates to the core app. Use an existing check icon; ticks are decorative (content description null, text carries it).
- Price line: "{price} once. Yours for life, on your Google account."
- Primary button "Unlock for {price}" → `launch(activity)` (get the Activity via `LocalActivity` or a small helper).
- Text button "Restore purchase" → `refresh()` then a one-line result: "Purchase restored" / "No purchase found on this
  Google account".
- Privacy line (muted): "Google Play handles your purchase. Your data stays on this phone."
- When not allowed to track (TrialEnded/Revoked) add the data promise "You can still view, edit, export, back up and delete
  everything." (existing string).
- States: **Loading** (price placeholder, button disabled); **Unavailable** ("Can't reach Google Play right now" + "Try
  again" secondary button calling `refresh()`; primary disabled); **Pending** ("Payment pending. Tracking unlocks when
  Google Play confirms it."; primary hidden); **Owned** ("Tarry is unlocked. Thank you." + "Done" primary that closes);
  **Error** after a failed launch ("Something went wrong. Try again."). No literal colours; tokens only.
- Remove the placeholder string `paywall_placeholder`.

### 4. Settings row
- New first section in `SettingsScreen.kt`, header "Tarry" (new string `settings_section_access`), one row opening
  `PaywallRoute`: "Free trial · N days left" / "Trial ended · Unlock Tarry" / "Payment pending" / "Unlocked" /
  "Purchase refunded · Unlock Tarry". Hidden while `NotStarted`. Same row component as the other Settings rows, ≥ 48 dp.

### 5. Trial card (Timeline)
- One dismissible card at the top of the Timeline when the state is `Trial` with **≤ 24 h left**, not yet dismissed:
  "Trial ends tomorrow" title, body "Unlock Tarry to keep tracking after your free trial.", action "Unlock" → paywall,
  close icon (content description "Dismiss"). Dismiss or Unlock sets the device-local flag `access_trial_card_shown`
  (new key; add it to the device-local key set in `core-data/.../settings/Settings.kt` with the other `access_*` keys so
  backup skips it and restore strips it, and extend the existing device-local backup test). Never shown again after that, never shown when owned. Use an existing card style from
  the Timeline (e.g. the empty-day / attention card), don't invent a new one.

### 6. About → open-source notices
- Add `OssNotice(R.string.licences_play_billing, R.string.licences_android_sdk_terms, "Play-Billing-notice.txt")` to
  `ui/about/OssNotices.kt` and the asset `app/src/main/assets/licenses/Play-Billing-notice.txt`:
  "Google Play Billing Library. Copyright Google LLC. Used under the Android Software Development Kit License Agreement:
  https://developer.android.com/studio/terms". Remove the "Phase 8b adds Play Billing here" comment.

### 7. Strings
All in `res/values/strings_access.xml` (plurals for days left). Australian English, sentence case, no full stops on
buttons/titles.

## Tests (rule 7)
- `PurchaseMappingTest`: purchased+unacked ⇒ OWNED + ack token; purchased+acked ⇒ OWNED, no ack; pending ⇒ PENDING;
  pending→purchased; successful empty after OWNED ⇒ REVOKED; after PENDING ⇒ REVOKED; after NONE ⇒ NONE; failed query
  keeps every state; other product IDs ignored; REVOKED then purchased again ⇒ OWNED.
- `PaywallViewModelTest` with `FakePurchaseClient` + fake `Entitlement`: each state's UI model; restore found / not found;
  launch AlreadyOwned ⇒ refresh; Unavailable ⇒ retry calls refresh.
- Compose (`app/src/test` Robolectric like the existing screen tests): paywall in Trial / TrialEnded / Pending / Owned /
  Unavailable; price text; buttons ≥ 48 dp; font scale 2.0 no clipping of the primary button; light and dark.
  Settings row text per state and hidden when NotStarted. Trial card shows at ≤ 24 h, not at 25 h, not when owned, never
  after dismiss. Existing guards (`NoColorLiteralsTest`, `ContrastGuardTest`, `TabularFiguresTest`) stay green.
- `OssNotices` asset exists for every entry (existing test, if any, covers the new row).

## Verify
`gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug assembleRelease` all green (release confirms R8
with the library). Do not run `connectedDebugAndroidTest` with the Pixel attached. Do not install on the Pixel.

## Out of scope
Real purchase test (needs the Play internal track: 8c). Price changes, subscriptions, promo codes, server verification.
