# Handoff: phase 8 — Launch (onboarding, hardening, Play Store, pricing)

**From:** Claude (planner, session `tarry-phase-6`) → **To:** next Claude session (planner first, then subagents) · **Date:** 2026-10-31 · **Branch to create:** `feat/phase-8-launch` from `main`, after phase 7.5 merges. Expect several PRs: 8a app hardening and onboarding, 8b pricing, 8c release and store.

## What changed in the plan (owner, 2026-10-31)
- **The MVP now ships on Google Play** with paid access. The build plan's "signed APK sideloaded" is replaced, and the "Play Store post-MVP" line is replaced. The new definition of done is **a production release on Google Play with working purchase and trial.**
- **Pricing:**
  - the **whole core app** (tracking, timeline, jobs, summary, export, backup) has a **7-day free trial**, then a **one-time lifetime purchase**, about **29 AUD** (price to be confirmed);
  - there is **no subscription for the core, ever**;
  - a future subscription is only for new Pro features (derived-data sync, MCP, Walkflow integration), and is not part of this phase.
- **Payment: Google Play Billing.**
- **Licence:** the repo is public and GPL-3.0. The owner wants to **rethink the licence before launch**, because anyone can build GPL code and skip the paywall.
- **UI:** phase 8 screens follow `docs/design/ui-rules.md` and `docs/design/tokens.md` as left by phase 7.5. Don't invent new patterns: if a rule is missing, add it to `ui-rules.md` with the owner.

## Decisions to record first (ADRs, before code)
1. **ADR 0004, a Play Billing network exception.**
   - **The change:** amend AGENTS.md rule 2 so that, besides map tiles and the Geocoder, the only network use is **Google Play Billing through the Play Billing Library** (Google Play services).
   - **What stays forbidden:** our own server, purchase verification server, analytics, crash SDKs and accounts.
   - **The data:** only Google's billing traffic. No app data goes into it (no location, jobs or anything else).
   - **Verification:** client side only, which is an accepted risk at this price.
   - **The Data safety form** must reflect this.
2. **ADR 0005, licence.**
   - **Options to put to the owner:**
     - keep GPL-3.0, with the paid build on Play for convenience;
     - a source-available licence (for example PolyForm Noncommercial, FSL or BSL), with the repo kept public;
     - proprietary, with the repo made private.
   - **Facts to check:**
     - every commit is the owner's (co-authored with Claude), so the owner can relicense future versions;
     - versions already published under GPL stay GPL for anyone who has them;
     - third-party licences must still be met: MapLibre (BSD), OpenFreeMap and OSM attribution (ODbL), the fonts (OFL), Material Symbols (Apache 2.0) and AndroidX (Apache 2.0);
     - F-Droid (post-MVP) needs a FOSS licence, so the choice decides whether that is possible.
   - **The ADR decides:** whether GitHub Releases APKs continue, and what the About screen links to.

## Scope
### 8a. App hardening and onboarding (MVP spec § Onboarding, § Settings, § Permissions, privacy and Play Store)
1. **Onboarding (first run).** The steps:
   - what Tarry does, and the **privacy promise** (no account, no server, no analytics, location never leaves the phone);
   - location while in use;
   - **Play's prominent disclosure screen**, then "Allow all the time" (background location);
   - notifications (Android 13+);
   - physical activity;
   - battery optimisation exemption with plain guidance;
   - paint the schedule (reuse the phase 6 `ScheduleGrid`, with the Mon–Fri 07:00–17:00 preset offered);
   - done, showing the tracking state and the next window.

   Each permission is explained before its system prompt, and a refusal leaves the app visibly degraded (ATTENTION plus the reason), never silently broken (rule 6). The trial starts here (see 8b).
2. **Tracking help / diagnostics:**
   - permission and battery-optimisation status;
   - an exact-alarm status;
   - a short "why tracking can stop" explainer;
   - a **"test tracking" self-check**: a short live capture that reports fix accuracy and that the service starts, with no timers beyond the check itself (rule 5).
3. **Data:**
   - **raw-ping retention** that actually prunes (30, 90, 365 days or forever), run by WorkManager, never touching frozen-derived data that is needed;
   - **delete all data**, with a typed confirmation;
   - export and import backup links (phase 7).
4. **Review reminder:** an optional "review your day" notification at a set time on scheduled days, with an exact or inexact alarm chosen by the rules in ADR 0003.
5. **About:**
   - version and licence (per ADR 0005), with a source link if public;
   - open-source licences, from `assets/licenses/` plus library notices;
   - map attribution;
   - privacy policy link;
   - support contact;
   - restore purchase.

### 8b. Pricing and entitlement (Play Billing)
- **Play Billing Library:** check the current version and policies, and the minimum Play Billing Library version Play requires at submission time.
- **Product:** one one-time product, `lifetime` (non-consumable).
- **Trial:**
  - **The catch:** Play one-time products have **no built-in free trial** (trials are a subscription feature).
  - **The plan:** a **local trial clock** that starts when onboarding finishes and is stored in DataStore.
  - **The weakness:** it is resettable by clearing data or reinstalling. That also wipes the user's tracking data, so it is an accepted weakness, but confirm with the owner.
  - **The display:** show "Trial · N days left" in Settings, and once (not nagging) near the end.
- **Entitlement states:** TRIAL, TRIAL_ENDED, OWNED, PENDING (Play pending purchases) and REVOKED (refund).
  - Cache the last known state.
  - Re-query with `queryPurchasesAsync` on launch and on resume.
  - Acknowledge purchases within 3 days, or Play refunds them.
  - Keep working offline from the cache.
- **What TRIAL_ENDED locks: ask the owner** (see questions). The recommendation is **never to hold data hostage**:
  - viewing, editing, summary, export, backup and delete stay available;
  - new tracking, both the schedule and manual start, is what locks;
  - the toggle and notification say "Trial ended · Unlock Tarry", which is a visible state, not a silent stop (rule 6).
- **One gate:** all checks go through one `Entitlement` interface, with no scattered checks, so phase 7 code stays clean, and a fake is used in tests.
- **Paywall screen:** price from Play (`ProductDetails`, localised), what you get, "one payment, no subscription", restore purchase, and the privacy line. Follow `ui-rules.md`.
- **Tests:** an entitlement state machine JVM test, a fake billing client, Compose tests for the paywall and the locked states, and the trial clock with an injected clock, including time-zone and clock-change edges.

### 8c. Release and store
- **Builds:**
  - a release AAB (`bundleRelease`) with R8 enabled;
  - check the keep rules for Room, Hilt, MapLibre, DataStore and Billing;
  - run the full test pass on a release build on the Pixel.
- **Signing:**
  - Play App Signing;
  - the upload key comes **only from CI secrets** (rule 10: never read or write keystores or `keystore.properties`; the owner creates and stores the key);
  - a CI release workflow that produces the signed AAB on a tag.
- **Versioning:** `versionCode` and `versionName` scheme, plus a changelog.
- **Play Console (owner-only actions, the session prepares the content):**
  - store listing: title, short and full description, screenshots, feature graphic. The copy comes from `docs/brand/brand-and-copy.md`;
  - **background location declaration** with core-feature justification and the **demo video** (prominent disclosure, then permission, then feature);
  - **Data safety form:** location is collected, processed on device only, not shared, and not sent off the device; purchase handling goes through Google Play;
  - privacy policy URL, hosted on the landing page;
  - content rating, target audience, ads declaration (none) and the foreground service type declaration (location);
  - **testing track:** a new personal developer account must run a **closed test with a minimum number of testers for 14 days** before production (12 testers at last check; **verify the current rule**). Plan the owner's tester list early, since this is the critical path to launch.
- **Landing page:** separate from the app, built by the owner from the brand doc. It hosts the privacy policy, support and the Play badge. The site should follow the same privacy promise (no third-party trackers), so the brand and behaviour match.

## Questions for the owner (ask while planning, rule 12)
1. **When the trial ends,** what locks? The recommendation is tracking only, never data access.
2. **The price:** final AUD price, and whether to let Play convert it per country or set a few by hand.
3. **The licence choice** (ADR 0005), and whether GitHub Releases APKs continue.
4. **The developer account:** personal or organisation (this affects the testing-track rule and the seller name shown on Play), plus the support email and the seller country (tax).
5. **Closed-test testers:** who, and when to start, given the 14-day minimum.
6. **Trial-reset weakness** (clearing data restarts the trial): accept it?
7. **Launch countries:** Australia only, or worldwide?
8. **Refunds:** rely on Play's standard policy?

## Hard constraints (AGENTS.md)
- **Rule 2:** network only as amended by ADR 0004. No analytics and no crash SDK. Play's own vitals are fine; we add nothing.
- **Rule 6:** a locked, revoked or permission-missing state is always visible.
- **Rule 5:** retention and reminders run through WorkManager or alarms, never our own timers.
- **Rule 10:** no keystores, tokens or `.env` files. Signing comes from CI secrets.
- **Rules 7 and 8:** plan first; every behaviour change gets a test; billing is tested against a fake.
- **Rule 4:** a schema change goes through `migration-guard` (none is expected; entitlement and trial live in DataStore).
- **The owner's data:** back up the phone DB to `tmp/` before installing a release build. Both builds use the id `me.tarry.app` but are signed with different keys (debug key versus Play App Signing). Android therefore refuses to install the Play build over the debug build, and uninstalling wipes the DB. Plan the data move: a phase 7 backup export, then uninstall, install from Play, and import the backup. Check the import on a copy first.

## Definition of done (MVP)
- [ ] ADR 0004 and ADR 0005 are accepted, and AGENTS.md is amended.
- [ ] Onboarding, diagnostics, retention, delete-all, reminder and About are built, follow `ui-rules.md`, and pass `ui-reviewer`.
- [ ] Trial and lifetime purchase work end to end on the Pixel (Play test track with licence testers): purchase, restore after reinstall, refund leading to REVOKED, and offline from the cache.
- [ ] The signed release AAB comes from CI, and the release build is tested on the Pixel.
- [ ] Play Console is complete, the closed test has run, and **the production release is live.**
- [ ] The owner's real data has moved to the release build through backup and restore.
