package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"example.com/app/internal/auth"
)

type fakeVerifier struct {
	claims *auth.Claims
	err    error
}

func (f fakeVerifier) Verify(context.Context, string) (*auth.Claims, error) { return f.claims, f.err }

func do(h http.Handler, path string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequestWithContext(context.Background(), http.MethodGet, path, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestHealthz(t *testing.T) {
	v := fakeVerifier{err: errors.New("unused")}

	if rec := do(NewRouter(v, ""), "/healthz", nil); rec.Code != http.StatusOK || rec.Body.String() != "ok" {
		t.Fatalf("no origin secret: %d %q", rec.Code, rec.Body.String())
	}
	// healthz sits behind the origin-secret check: probes must go through the proxy or send the header.
	h := NewRouter(v, "s3cret")
	if rec := do(h, "/healthz", nil); rec.Code != http.StatusForbidden {
		t.Fatalf("missing origin secret: got %d, want 403", rec.Code)
	}
	if rec := do(h, "/healthz", map[string]string{"X-Origin-Secret": "s3cret"}); rec.Code != http.StatusOK {
		t.Fatalf("with origin secret: got %d, want 200", rec.Code)
	}
}

func TestMe(t *testing.T) {
	t.Run("requires auth", func(t *testing.T) {
		h := NewRouter(fakeVerifier{claims: &auth.Claims{Subject: "u1"}}, "")
		if rec := do(h, "/v1/me", nil); rec.Code != http.StatusUnauthorized {
			t.Fatalf("got %d, want 401", rec.Code)
		}
	})
	t.Run("rejected token", func(t *testing.T) {
		h := NewRouter(fakeVerifier{err: errors.New("bad")}, "")
		if rec := do(h, "/v1/me", map[string]string{"Authorization": "Bearer x"}); rec.Code != http.StatusUnauthorized {
			t.Fatalf("got %d, want 401", rec.Code)
		}
	})
	t.Run("returns caller", func(t *testing.T) {
		h := NewRouter(fakeVerifier{claims: &auth.Claims{Subject: "u1", Email: "u1@example.com"}}, "")
		rec := do(h, "/v1/me", map[string]string{"Authorization": "Bearer x"})
		if rec.Code != http.StatusOK {
			t.Fatalf("got %d, want 200", rec.Code)
		}
		var got map[string]string
		if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		if got["sub"] != "u1" || got["email"] != "u1@example.com" {
			t.Fatalf("body = %v", got)
		}
	})
}
