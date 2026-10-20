---
name: security-pass
description: Security review of auth, API, data access, secrets, dependencies, and cloud config. Use before launch, after changing auth/permissions/infra, when handling user data or uploads, or when the user says security, hardening, or "is this safe".
---
# security-pass

Review and report (blocker / should-fix / nit) with file:line:
- **Auth:** JWT verified via JWKS (alg allowlist, issuer, audience, expiry); no trust in client-provided user ids; session/cookie flags; account-linking and email-verification rules (especially if adding social providers).
- **Authorization:** ownership/tenant checks in queries; no IDOR; admin routes separated.
- **Input:** validation, size limits, content-type checks, SQL parameterisation, SSRF, open redirects, file upload type/size and signed-URL scopes/expiry (R2/S3).
- **Edge:** CORS allowlist, rate limiting (Cloudflare rules), origin-secret header so Lambda isn't directly callable, security headers/CSP.
- **Secrets:** none in repo/logs; per-env scoped tokens; rotation plan; no `.env` in images.
- **Cloud:** least-privilege IAM (no `*`), per-env accounts, GitHub OIDC (no long-lived keys), Budgets alerts, log retention, public Function URL exposure.
- **Supply chain:** pinned dependency versions and GitHub Action SHAs; review third-party skills/MCP servers before enabling (they can run code); Dependabot/Renovate.
- **Agents:** Claude hooks/permission denies and Cursor command approval/sandbox settings still in place; agent credentials scoped to dev/staging.
