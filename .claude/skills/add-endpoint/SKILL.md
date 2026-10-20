---
name: add-endpoint
description: Add or change a Go API endpoint on the Lambda API — route, handler, validation, auth, DB access, tests, and client types. Use whenever the user asks for a new API route, handler, or backend capability, even if they only describe the behaviour.
---
# add-endpoint (Go on Lambda)

Pattern (match existing code first):
1. **Contract first:** method, path (`/v1/...`), request/response JSON, error codes. Update the OpenAPI/types file if the repo has one.
2. **Handler** in `api/internal/http/`: decode + validate input (reject unknown fields, enforce limits), get the user via `auth.FromContext`, call a service/repo, map errors to status codes. No provider SDKs.
3. **Authz:** every non-public route is behind the auth middleware; check ownership/tenancy in the query itself (`WHERE user_id = $1`).
4. **DB:** `pgx` with the pooled `DATABASE_URL`; small pool (Lambda), context timeouts, no long transactions. If you see "prepared statement already exists" with the pooler, use simple protocol exec mode.
5. **Lambda fit:** stateless; init heavy clients in `init()`/package vars; keep cold start small; respect 6 MB sync payload limits; no VPC unless an ADR says so.
6. **Tests:** table-driven handler tests with a fake `auth.Verifier`; one integration test against a Neon branch DB for queries.
7. **Frontend types:** regenerate or hand-update shared types; update the client call site.
8. Run `go vet`, `go test -race`, then `review-diff`.
