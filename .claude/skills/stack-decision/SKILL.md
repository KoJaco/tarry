---
name: stack-decision
description: Decide the frontend framework (Next.js vs React Router v7), hosting target, DB+auth provider (Neon vs Supabase), and file storage (R2 vs S3) for an app from its abstract spec. Use at the start of any new app or major feature, when requirements change (e.g. "we need Apple sign-in", "needs SEO", "heavy uploads"), or when asked which stack to use.
---
# stack-decision

Defaults (confirmed): **Cloudflare (frontend) + Neon (Postgres + Neon Auth) + Go on AWS Lambda (always) + R2 for files.**
The framework, DB/auth provider, and storage are decided **per app from its requirements**, not by habit.

## Step 1 — capture the abstract spec (ask only what's missing)
- Product type: app-like dashboard/tool · content/marketing/SEO-heavy · mixed
- Auth needs: email/OTP · Google/GitHub · **Apple / Microsoft / other social** · MFA · SAML/SSO · organisations/teams
- Clients: web only · web + iOS/Android app (if iOS offers social login, plan for Sign in with Apple or an equivalent)
- Realtime/websockets? Long-running jobs? Heavy uploads/media? Expected traffic shape (spiky vs steady)? Team size/seats? Revenue (commercial) from day one?
- Compliance/data residency needs?

## Step 2 — decide each axis (see references)
1. **Framework** → `references/frontend-framework.md`
2. **DB + auth** → `references/db-auth.md` (includes the switch-to-Supabase playbook)
3. **Files** → `references/file-storage.md`
4. **Go API:** Lambda always (arm64, `provided.al2023`, Function URL by default, outside any VPC).

## Step 3 — record it
Write `docs/decisions/NNNN-<app>-stack.md` (ADR): spec summary, choices, reasons, what would make us switch, cost estimate. Update AGENTS.md "Default stack" only if the app deviates.

Always keep portability intact (DATABASE_URL, SQL migrations, JWKS verifier interface) so switching providers later is a contained change.
