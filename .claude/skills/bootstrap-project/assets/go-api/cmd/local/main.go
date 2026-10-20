// Local dev server: same handler, plain net/http.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"time"

	"example.com/app/internal/app"
)

func main() {
	h, err := app.NewHandler(context.Background())
	if err != nil {
		log.Fatal(err)
	}
	addr := ":" + envOr("PORT", "8080")
	log.Printf("listening on %s", addr)
	srv := &http.Server{Addr: addr, Handler: h, ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
