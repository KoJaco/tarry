# 0004 — Network exception for Google Play Billing

Status: accepted (2026-11-09). Amends AGENTS.md rule 2.

## Context
Tarry ships on Google Play with a 7-day trial and then a one-time lifetime purchase (phase 8). Rule 2 allows only map tiles and the platform Geocoder on the network. Taking payment through Play needs the Play Billing Library, which talks to Google Play services.

## Decision
- **Allowed:** besides map tiles and the Geocoder, the only network use is **Google Play Billing through the Play Billing Library** (via Google Play services on the device).
- **The data:** only Google's own billing traffic (product details, purchase, acknowledgement, purchase queries). No app data goes into it: no location, pings, places, jobs, notes or settings.
- **The library's own diagnostics:** Play Billing Library 9.x bundles Google's logging component (`com.google.android.datatransport`, found 2026-11-13 as a `com.google.android.datatransport.events` database on the phone). It may send Google technical diagnostics about the purchase flow. We don't configure it or feed it anything, and it carries none of the user's Tarry data. We disclose it rather than strip it out, because removing it is unsupported and could break purchases. The privacy policy and store listing say so.
- **Verification:** purchases are verified on the client only. A self-builder or a patched APK can skip it, which is accepted at this price (see ADR 0005).
- **Still forbidden:** our own server, a purchase-verification server, analytics, crash SDKs, ads and accounts. Play's own vitals are fine; we add nothing.

## Consequences
- AGENTS.md rule 2 names Play Billing as the third exception.
- The Play **Data safety** form says: location is collected and processed on the device only, not shared and not sent off the device; purchase handling goes through Google Play.
- The privacy promise copy (`docs/brand/brand-and-copy.md`) already says "Google Play handles your purchase". Keep the two in line.
- All entitlement checks go through one `Entitlement` interface (phase 8b), so the billing code stays in one place and tests use a fake.
