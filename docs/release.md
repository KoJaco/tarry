# Releasing Tarry to Google Play

Owner runbook for the first Play release (phase 8c) and every update after it. The session prepares content; only the owner
touches keys, Play Console and payments (AGENTS rule 10).

## 1. One-time: the upload key and GitHub secrets
Play App Signing holds the real app signing key. You create only an **upload key**, keep it out of the repo, and give it
to CI as secrets.

1. Create the key (keep the `.jks` and both passwords in your password manager, never in the repo):
   ```
   keytool -genkeypair -v -keystore tarry-upload.jks -alias tarry-upload -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Encode it for GitHub (Git Bash): `base64 -w0 tarry-upload.jks > tarry-upload.b64`
3. GitHub → repo → Settings → Secrets and variables → Actions → New repository secret, four times:
   `TARRY_UPLOAD_KEYSTORE_BASE64` (contents of the .b64 file), `TARRY_UPLOAD_STORE_PASSWORD`, `TARRY_UPLOAD_KEY_ALIAS`
   (`tarry-upload`), `TARRY_UPLOAD_KEY_PASSWORD`. Delete the .b64 file afterwards.

## 2. Each release: build the signed bundle
1. Bump `versionCode` (+1, every upload) and `versionName` in `app/build.gradle.kts` on a branch; add a `CHANGELOG.md`
   entry; merge.
2. Tag main: `git tag v1.0.0 && git push origin v1.0.0` (the tag must equal `v` + versionName, or the workflow fails).
3. Actions → **Release** → the run for the tag → download the artifact `tarry-release-v1.0.0`
   (`app-release.aab` + `mapping.txt`). The workflow also runs the unit tests and lint, and checks the bundle is signed.

## 3. Play Console, first release (in this order)
1. **Create app:** name "Tarry: Work Hours Timeline", default language English (Australia) or English (United States),
   App, Paid? → **Free** (the purchase is in-app), accept declarations.
2. **App signing:** accept Play App Signing (default) when you upload the first bundle.
3. **Internal testing** track → create release → upload the AAB → add `mapping.txt` under the bundle's App bundle
   explorer → Downloads → "ReTrace mapping file" (deobfuscates crash reports in Play vitals) → roll out to testers.
   Add yourself under Testers (email list).
4. **In-app product** (Monetize → Products → In-app products; becomes available after a bundle with the Billing library is
   uploaded): product ID **`lifetime`**, name "Tarry lifetime", description "Unlock tracking for life. One payment, no
   subscription.", price **19.99 AUD**, let Play convert other countries → **Activate**.
5. **Licence testing** (Settings → License testing): add your Google account so test purchases cost nothing.
6. Install from the internal-testing opt-in link on the Pixel **after backing up your data** (see §5) and run the
   purchase checks: buy, restore after reinstall, refund in Play Console ⇒ Settings row says refunded, airplane mode ⇒
   stays unlocked.
7. **App content** (Policy → App content), everything below in §4.
8. **Store listing**: `docs/store-listing.md`; screenshots from `tmp/marketing-2026-11-11/stills/`; feature graphic
   1024×500 (to make); icon 512×512 from `docs/brand/logo/`.
9. **Production** → countries: all → create release → promote the tested internal build → send for review.
   (Organisation accounts are exempt from the 12-tester / 14-day closed test; if Play Console asks for it anyway, it is
   on the Dashboard under "Production access".) First review usually takes from a few days up to about a week for apps
   with background location.

## 4. App content answers
- **Privacy policy URL:** the hosted `docs/privacy-policy.md` (fill `AboutLinks.PRIVACY_URL` with the same URL first).
- **Ads:** No ads.
- **App access:** All functionality is available without special access (no login). Note for reviewers: "Tracking runs
  during the work hours you set in Settings → Schedule; use Start in the top bar to try it at any time."
- **Content rating:** questionnaire → Utility/Productivity; no violence, no user-generated content shared, no
  location **sharing** with other users ⇒ expect Everyone / PEGI 3.
- **Target audience:** 18 and over. Not designed for children.
- **News app:** No. **Government app:** No. **Financial features:** none. **Health:** none.
- **Data safety** (our recommendation; read Play's definitions before submitting):
  - Data collected? **Yes**, only because two features send something off the phone:
    - **Approximate location** — collected, **not shared**, **processed ephemerally**, *required*, purpose **App
      functionality**. Why: map tiles are downloaded for the area you view (OpenFreeMap), and street addresses come from
      the phone's built-in geocoder. Neither is stored by us; we have no server.
    - **Precise location: not collected** — raw positions never leave the phone except in a file the user exports.
    - **Purchase history:** handled by Google Play; Play's own billing data does not need declaring by the developer
      (check the current Play help article "Data safety: purchase history" before submitting; if unsure, declare
      Purchase history — collected, not shared, App functionality).
  - Encrypted in transit: **Yes** (HTTPS). Users can request deletion: **Yes** — Settings → Data → Delete all data
    (everything is on the device; uninstalling also removes it).
- **Location permissions declaration** (background location):
  - Feature: "Tarry records where you work during the hours you set, so it can write your day as site visits and travel
    for invoicing. Recording must continue with the screen off and the app closed, because work happens with the phone in
    a pocket."
  - Why foreground-only is not enough: "The user is not looking at the app while working; a timeline with gaps would be
    useless for invoicing."
  - Video: `tmp/marketing-2026-11-11/play/background-location-declaration.mp4` (upload unlisted to YouTube; paste link).
- **Foreground service declaration** (type `location`): "Records the user's location during their scheduled work hours
  for the work timeline. Starts at the start of the user's hours, stops at the end; a persistent notification is shown
  with Pause and Stop." Same video link.
- **Exact alarm** (`SCHEDULE_EXACT_ALARM`): user-granted; used to start and stop tracking at the user's work-hour
  boundaries. No declaration needed (only `USE_EXACT_ALARM` is restricted).
- **Battery optimisation exemption** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`): Play restricts this permission to apps whose
  core function breaks without it. Justification if asked: "Tarry's core function is continuous location recording during
  work hours; Doze and app standby stop the foreground service's alarms and location updates on some devices, which
  leaves gaps in the user's work record." **Rejection risk:** if review objects, the fallback is to remove the permission
  and send users to the system battery settings screen instead (one small code change).

## 5. Moving the owner's data to the Play build
The Play build is signed with a different key than the debug build, so it cannot install over it, and uninstalling wipes
the database.
1. On the debug build: Settings → Data → Export backup → save it somewhere off the phone too (and copy to `tmp/`).
2. Uninstall the debug build. Install from the Play internal link. Finish onboarding.
3. Settings → Data → Restore backup → check a few recent days and the Settings values.
4. The trial clock starts fresh from this onboarding (trial keys are device-local and never restored). Unlock with the
   licence-tester purchase so the test costs nothing.

## 6. After launch
- Watch Play Console → Android vitals (crashes/ANRs; we add no crash SDK).
- Updates: §2 then Production → new release → upload → staged rollout 20% → 100%.
