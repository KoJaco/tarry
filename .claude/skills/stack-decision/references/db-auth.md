# DB + auth: Neon (default) vs Supabase

## Default: Neon Postgres + Neon Auth
- Scale-to-zero; instant branching (per-PR/per-agent DBs); plain Postgres; low lock-in.
- Neon Auth is managed Better Auth. **Known gaps (verify current status):** social OAuth limited to Google/GitHub/Vercel; MFA "coming soon"; organisations partial; docs conflict on Beta vs GA.
- Cost: usage-based (Launch ≈ $0.106/CU-hour, ~$0.35/GB-month at last check, no monthly minimum). Always-on production is not free — a full CU around the clock is roughly $77/month; Scale tier is more than double per CU-hour. Re-check pricing.

## Switch to Supabase (DB + auth) when ANY is true
- Need **Apple, Microsoft/Azure, or other social providers** Neon Auth lacks (e.g. iOS app with social login).
- Need **MFA**, **SAML/SSO**, or mature organisation/role features now.
- Want a **flat predictable bill** (Pro ≈ $25/org + usage; 100K MAU included) and the app runs always-on anyway.
- Neon Auth status/limits block launch.
Not a reason to switch: storage or realtime (files stay on R2/S3).

## Keeping the switch cheap (enforced by AGENTS.md rules 2–3)
- App uses `DATABASE_URL` only; SQL migrations (goose); no provider SDKs in domain code; no RLS tied to a provider's auth function until an ADR approves.
- Go verifies JWTs via JWKS behind `auth.Verifier`. Switching = change JWKS URL, issuer, audience, and the web login client.
- `pg_dump`/restore (or logical replication) for data; plan the **user migration** early:
  - Test exporting users and password hashes between providers; if hashes can't be carried over, use forced reset / magic-link on first login.
  - Keep a stable internal `users.id` and map provider subject → internal id in your own table so a provider change doesn't rewrite foreign keys.
- Escape hatch from Neon Auth: self-host Better Auth (open source) against the same Neon database.

## Environments
- Separate Neon **projects** for production and for staging/dev (a project-scoped API key can reach all branches in it). Per-PR branches live in the staging project.
- Agents get staging-scoped keys only.
