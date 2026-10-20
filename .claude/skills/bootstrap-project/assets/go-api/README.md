# Go Lambda API skeleton

- `cmd/lambda`: Lambda entry (arm64, `provided.al2023`, build tag `lambda.norpc`).
- `cmd/local`: local dev server.
- `internal/auth`: provider-neutral JWKS verifier, bearer middleware, origin-secret check.
- `internal/app`: `NewRouter` (pure wiring, testable with a fake `auth.Verifier`) and `NewHandler` (env, JWKS, pgx pool).

Env: `DATABASE_URL` (pooled), `JWKS_URL`, `JWT_ISSUER`, `JWT_AUDIENCE`, `ORIGIN_SECRET`. Set issuer and audience in every real environment; the verifier skips those checks when they're empty.

## Use it
```bash
cp -r .claude/skills/bootstrap-project/assets/go-api/. api/
cd api
M=github.com/<org>/<app>
go mod edit -module "$M"
grep -rl 'example.com/app' --include='*.go' . | xargs sed -i "s#example.com/app#$M#g"
go mod tidy && go vet ./... && go test -race ./... && golangci-lint run
```
Dependencies are pinned in `go.mod`/`go.sum`; keep them current with Dependabot.

## Tests
- `internal/auth`: valid token, expired, missing exp/sub, wrong issuer/audience, unknown signer, HS256 confusion, garbage; middleware and origin-secret cases. Uses an in-process JWKS server and a generated ES256 key.
- `internal/app`: `/healthz` (with and without origin secret), `/v1/me` (no token, rejected token, success).

`/healthz` sits behind the origin-secret check, so probes must go through the proxy or send `X-Origin-Secret`.
CI runs these tests on the skeleton itself (`skeleton` job in `.github/workflows/ci.yml`).
