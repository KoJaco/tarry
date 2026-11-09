# 0005 — Licence: source-available under FSL-1.1-ALv2

Status: accepted (2026-11-08). Supersedes the GPL-3.0 line in ADR 0001.

## Context
Tarry ships on Google Play as a paid app: a 7-day trial, then a one-time lifetime purchase. The repo is public and was GPL-3.0, which lets anyone build the app, strip the trial and publish a free or competing copy. The owner wants to keep the code public, because showing the code backs the privacy promise, while protecting the paid app and leaving room for a future Pro subscription.

Facts at the decision (2026-11-01, rechecked 2026-11-08):
- Every commit is the owner's (two addresses, handle KoJaco). There are no outside contributors and no forks, so the owner can relicense.
- Versions already published under GPL-3.0 stay GPL-3.0 for anyone who has a copy. The change applies from this commit on.
- Third-party terms still apply and are unaffected: MapLibre Native (BSD-2-Clause), OpenFreeMap / OpenMapTiles / OpenStreetMap data (ODbL, with attribution), the Nunito and Nunito Sans fonts (OFL-1.1), Material Symbols (Apache-2.0), AndroidX and other libraries (Apache-2.0).

## Decision
- **Licence:** Functional Source License 1.1 with the Apache 2.0 future licence (`FSL-1.1-ALv2`), text from fsl.software, in `LICENSE.md`. Licensor: **Kori Jacobsen** (the owner's legal name and business entity, the same as the Google Play organisation account).
- **What it allows:** anyone may read, fork, build, modify and run Tarry for any purpose except a Competing Use: no commercial product or service that substitutes for Tarry or offers substantially the same functionality. Personal use, education, research, and professional services for a licensee are allowed.
- **Future licence:** each version becomes available under Apache-2.0 two years after it is published.
- **Self-builders** can remove the trial check. That is accepted ("run it themselves").
- **Pro code** (sync, MCP, Walkflow integration) stays in a separate, closed module or repo and server, so it never falls under this licence or its two-year conversion.
- **Trademark:** FSL grants no trademark rights. Register "Tarry" so renamed or lookalike clones can be stopped (owner action).
- **Contributions:** if outside contributions are ever accepted, require a CLA so relicensing and dual licensing stay possible.
- **Distribution:** Google Play is the only binary channel. No more APKs on GitHub Releases.
- **About screen (phase 8):** "Source available under FSL-1.1-ALv2" with a link to the repo, plus the third-party licences from `app/src/main/assets/licenses/` and the map attribution.

## Consequences
- FSL is not an OSI open-source licence, so F-Droid's main repository can't ship current versions. Versions older than two years can go there under Apache-2.0 if wanted.
- Copy must say "source available", not "open source" (brand doc, spec, store listing).
- A short review by a lawyer before launch is optional.
