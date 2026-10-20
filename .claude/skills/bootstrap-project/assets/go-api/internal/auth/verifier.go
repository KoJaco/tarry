// Package auth is the ONLY place that knows how tokens are verified.
// Handlers depend on Verifier + Claims, never on a provider SDK (AGENTS.md rule 3).
// Switching Neon Auth <-> Supabase = change JWKS URL, issuer, audience (and the web login client).
package auth

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/MicahParks/keyfunc/v3"
	"github.com/golang-jwt/jwt/v5"
)

// Claims is the provider-neutral view of the caller.
type Claims struct {
	Subject string // provider subject; map to your internal users.id in the DB
	Email   string
}

type Verifier interface {
	Verify(ctx context.Context, bearer string) (*Claims, error)
}

type JWKSVerifier struct {
	kf       keyfunc.Keyfunc
	issuer   string
	audience string
}

// NewJWKSVerifier fetches/refreshes keys from jwksURL. issuer/audience are optional but strongly recommended.
func NewJWKSVerifier(ctx context.Context, jwksURL, issuer, audience string) (*JWKSVerifier, error) {
	kf, err := keyfunc.NewDefaultCtx(ctx, []string{jwksURL})
	if err != nil {
		return nil, fmt.Errorf("load jwks: %w", err)
	}
	return &JWKSVerifier{kf: kf, issuer: issuer, audience: audience}, nil
}

func (v *JWKSVerifier) Verify(_ context.Context, token string) (*Claims, error) {
	opts := []jwt.ParserOption{
		jwt.WithValidMethods([]string{"RS256", "ES256", "EdDSA"}), // allowlist; never "none"/HS*
		jwt.WithExpirationRequired(),
	}
	if v.issuer != "" {
		opts = append(opts, jwt.WithIssuer(v.issuer))
	}
	if v.audience != "" {
		opts = append(opts, jwt.WithAudience(v.audience))
	}
	parsed, err := jwt.Parse(token, v.kf.Keyfunc, opts...)
	if err != nil || !parsed.Valid {
		return nil, errors.New("invalid token")
	}
	mc, ok := parsed.Claims.(jwt.MapClaims)
	if !ok {
		return nil, errors.New("invalid claims")
	}
	sub, _ := mc.GetSubject()
	if sub == "" {
		return nil, errors.New("missing subject")
	}
	email, _ := mc["email"].(string)
	return &Claims{Subject: sub, Email: email}, nil
}

type ctxKey struct{}

func FromContext(ctx context.Context) (*Claims, bool) {
	c, ok := ctx.Value(ctxKey{}).(*Claims)
	return c, ok
}

// Middleware requires a valid Bearer token and stores Claims in the request context.
func Middleware(v Verifier) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			if !ok || tok == "" {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			c, err := v.Verify(r.Context(), tok)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, c)))
		})
	}
}

// OriginSecret rejects requests that didn't come through your Cloudflare proxy (keeps the Function URL private-ish).
func OriginSecret(secret string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if secret != "" && r.Header.Get("X-Origin-Secret") != secret {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
