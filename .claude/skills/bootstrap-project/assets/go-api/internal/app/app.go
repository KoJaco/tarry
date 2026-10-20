// Package app wires config, DB, auth and routes into one http.Handler.
package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"

	"example.com/app/internal/auth"
)

func NewHandler(ctx context.Context) (http.Handler, error) {
	v, err := auth.NewJWKSVerifier(ctx, mustEnv("JWKS_URL"), os.Getenv("JWT_ISSUER"), os.Getenv("JWT_AUDIENCE"))
	if err != nil {
		return nil, err
	}

	// Lambda: keep the pool tiny and reuse it across invocations. Use the POOLED connection string (Neon pgbouncer).
	cfg, err := pgxpool.ParseConfig(mustEnv("DATABASE_URL"))
	if err != nil {
		return nil, fmt.Errorf("db config: %w", err)
	}
	cfg.MaxConns = 4
	// If you see "prepared statement already exists" behind a pooler, set:
	// cfg.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("db connect: %w", err)
	}
	_ = pool // pass into services/repos

	return NewRouter(v, os.Getenv("ORIGIN_SECRET")), nil
}

// NewRouter is the pure wiring (no env, no network), so routes and middleware are testable with a fake Verifier.
func NewRouter(v auth.Verifier, originSecret string) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID, middleware.Recoverer, middleware.Timeout(25*time.Second))
	r.Use(auth.OriginSecret(originSecret))

	r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })

	r.Route("/v1", func(r chi.Router) {
		r.Use(auth.Middleware(v))
		r.Get("/me", func(w http.ResponseWriter, req *http.Request) {
			c, _ := auth.FromContext(req.Context())
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]string{"sub": c.Subject, "email": c.Email})
		})
	})
	return r
}

func mustEnv(k string) string {
	v := os.Getenv(k)
	if v == "" {
		panic("missing env " + k)
	}
	return v
}
