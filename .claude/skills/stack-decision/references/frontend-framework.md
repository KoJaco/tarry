# Frontend framework: Next.js vs React Router v7

Both run on Cloudflare Workers. Choose from the spec.

| Prefer **Next.js** when | Prefer **React Router v7** when |
|---|---|
| SEO/content-heavy pages, ISR/static generation, rich metadata | App-like dashboard/tool behind login, SEO irrelevant |
| You want React Server Components and the Next ecosystem (docs, templates, libraries) | You want simple loaders/actions, web-standard Request/Response, fewer moving parts |
| Team is already fluent in Next (you are) and speed matters | You want the most direct Workers fit and the smallest runtime/bundle |
| Image optimisation, middleware, route handlers matter | You want fewer platform-specific features to maintain |

**Tie-breaker:** your familiarity favours Next.js. Pick RR7 only if the spec is a pure app-like product and Workers bundle size/compat becomes a concern.

## Hosting target
- **Default: Cloudflare Workers.** Next.js via OpenNext (`@opennextjs/cloudflare`); RR7 via its Cloudflare/Workers template. *Verify current OpenNext docs and Node-API compatibility (`nodejs_compat`) before committing — some Next features and Node APIs behave differently on Workers; check worker bundle size limits.*
- **Use Vercel instead only if** the app needs Vercel-specific Next features that don't work well on Workers AND revenue justifies the per-seat Pro plan (Hobby is non-commercial only). Record the reason in an ADR.

## Skeleton `wrangler.jsonc` (Next via OpenNext — verify against current docs)
```jsonc
{
  "name": "myapp-web",
  "main": ".open-next/worker.js",
  "compatibility_date": "2026-10-01",
  "compatibility_flags": ["nodejs_compat"],
  "assets": { "directory": ".open-next/assets", "binding": "ASSETS" },
  "env": {
    "staging":    { "name": "myapp-web-staging", "vars": { "API_BASE_URL": "https://api-staging.example.com" } },
    "production": { "name": "myapp-web",         "vars": { "API_BASE_URL": "https://api.example.com" } }
  }
}
```
Secrets via `wrangler secret put` (staging only for agents).
