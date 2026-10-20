---
name: add-migration
description: Create a safe Postgres schema migration (tables, columns, indexes, constraints, backfills) with rollback, tested on a disposable Neon branch. Use for any database schema or data change, including "add a column", "new table", or "fix this data".
---
# add-migration

Flow:
1. Create a throwaway Neon branch from the **staging** project's `main` (name: `mig-<task>`). Use its connection string only for this task; never touch production.
2. Write the migration (rules below).
3. Apply up, run the test suite against the branch, apply down, apply up again (rollback/idempotency check).
4. Report lock risk, expected duration on large tables, rollback plan, and data-loss risk.
5. Delete the throwaway branch when done.

Rules:
- Plain SQL in `db/migrations/NNNN_<slug>.sql` (goose: `-- +goose Up` / `-- +goose Down`). **Portable Postgres only** — no provider-specific features (so we can move Neon ⇄ Supabase).
- **Expand/contract:** add nullable → backfill in batches → enforce → drop old in a later release. Never rename/drop in the same release code still reads.
- Indexes on big tables: `CREATE INDEX CONCURRENTLY` (needs `-- +goose NO TRANSACTION`).
- Add `NOT NULL`/FKs with `NOT VALID` then `VALIDATE` for large tables.
- Test on a throwaway branch of the **staging** Neon project: up → tests → down → up.
- Report lock risk, duration estimate, rollback plan, and data-loss risk. Production application happens in CI after approval.
- Don't bake auth-provider coupling (e.g. RLS policies using a provider's `auth.uid()`) into schema until an ADR approves it.
