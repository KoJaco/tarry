package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	testKID      = "k1"
	testIssuer   = "https://issuer.test"
	testAudience = "api://app"
)

func genKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

// jwksServer serves a one-key JWKS for pub under testKID.
func jwksServer(t *testing.T, pub *ecdsa.PublicKey) *httptest.Server {
	t.Helper()
	enc := base64.RawURLEncoding.EncodeToString
	raw, err := pub.Bytes() // uncompressed point: 0x04 || X(32) || Y(32)
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(map[string]any{"keys": []map[string]string{{
		"kty": "EC", "crv": "P-256", "kid": testKID, "alg": "ES256", "use": "sig",
		"x": enc(raw[1:33]),
		"y": enc(raw[33:65]),
	}}})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func baseClaims() jwt.MapClaims {
	return jwt.MapClaims{
		"sub":   "user-1",
		"email": "a@example.com",
		"iss":   testIssuer,
		"aud":   testAudience,
		"exp":   time.Now().Add(time.Hour).Unix(),
	}
}

func sign(t *testing.T, method jwt.SigningMethod, key any, claims jwt.MapClaims) string {
	t.Helper()
	tok := jwt.NewWithClaims(method, claims)
	tok.Header["kid"] = testKID
	s, err := tok.SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestJWKSVerifier_Verify(t *testing.T) {
	key, other := genKey(t), genKey(t)
	srv := jwksServer(t, &key.PublicKey)
	v, err := NewJWKSVerifier(context.Background(), srv.URL, testIssuer, testAudience)
	if err != nil {
		t.Fatal(err)
	}

	with := func(mut func(jwt.MapClaims)) jwt.MapClaims {
		c := baseClaims()
		mut(c)
		return c
	}

	tests := []struct {
		name    string
		token   string
		wantErr bool
	}{
		{"valid", sign(t, jwt.SigningMethodES256, key, baseClaims()), false},
		{"expired", sign(t, jwt.SigningMethodES256, key, with(func(c jwt.MapClaims) { c["exp"] = time.Now().Add(-time.Hour).Unix() })), true},
		{"missing exp", sign(t, jwt.SigningMethodES256, key, with(func(c jwt.MapClaims) { delete(c, "exp") })), true},
		{"wrong issuer", sign(t, jwt.SigningMethodES256, key, with(func(c jwt.MapClaims) { c["iss"] = "https://evil.test" })), true},
		{"wrong audience", sign(t, jwt.SigningMethodES256, key, with(func(c jwt.MapClaims) { c["aud"] = "api://other" })), true},
		{"missing subject", sign(t, jwt.SigningMethodES256, key, with(func(c jwt.MapClaims) { delete(c, "sub") })), true},
		{"signed by unknown key", sign(t, jwt.SigningMethodES256, other, baseClaims()), true},
		{"HS256 (algorithm confusion)", sign(t, jwt.SigningMethodHS256, []byte("secret"), baseClaims()), true},
		{"garbage", "not-a-jwt", true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, err := v.Verify(context.Background(), tt.token)
			if (err != nil) != tt.wantErr {
				t.Fatalf("err = %v, wantErr %v", err, tt.wantErr)
			}
			if !tt.wantErr && (c.Subject != "user-1" || c.Email != "a@example.com") {
				t.Fatalf("claims = %+v", c)
			}
		})
	}
}

type stubVerifier struct {
	claims *Claims
	err    error
}

func (s stubVerifier) Verify(context.Context, string) (*Claims, error) { return s.claims, s.err }

func TestMiddleware(t *testing.T) {
	ok := stubVerifier{claims: &Claims{Subject: "u1", Email: "u1@example.com"}}
	bad := stubVerifier{err: context.Canceled}

	tests := []struct {
		name   string
		v      Verifier
		header string
		want   int
	}{
		{"no header", ok, "", http.StatusUnauthorized},
		{"wrong scheme", ok, "Basic abc", http.StatusUnauthorized},
		{"empty bearer", ok, "Bearer ", http.StatusUnauthorized},
		{"verifier rejects", bad, "Bearer abc", http.StatusUnauthorized},
		{"valid", ok, "Bearer abc", http.StatusOK},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var got *Claims
			h := Middleware(tt.v)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				got, _ = FromContext(r.Context())
			}))
			req := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/", nil)
			if tt.header != "" {
				req.Header.Set("Authorization", tt.header)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != tt.want {
				t.Fatalf("status = %d, want %d", rec.Code, tt.want)
			}
			if tt.want == http.StatusOK && (got == nil || got.Subject != "u1") {
				t.Fatalf("claims not in context: %+v", got)
			}
			if tt.want != http.StatusOK && got != nil {
				t.Fatal("handler ran for rejected request")
			}
		})
	}
}

func TestOriginSecret(t *testing.T) {
	tests := []struct {
		name, secret, header string
		want                 int
	}{
		{"no secret configured", "", "", http.StatusOK},
		{"missing header", "s3cret", "", http.StatusForbidden},
		{"wrong header", "s3cret", "nope", http.StatusForbidden},
		{"correct header", "s3cret", "s3cret", http.StatusOK},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			h := OriginSecret(tt.secret)(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
			req := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/", nil)
			if tt.header != "" {
				req.Header.Set("X-Origin-Secret", tt.header)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != tt.want {
				t.Fatalf("status = %d, want %d", rec.Code, tt.want)
			}
		})
	}
}
