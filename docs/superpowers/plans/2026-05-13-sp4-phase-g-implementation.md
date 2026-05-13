# SP-4 Phase G — Removal-syncs-to-backup (implementation plan)

**Plan date:** 2026-05-13
**Design:** `docs/superpowers/specs/2026-05-13-sp4-phase-g-design.md`

5 tasks, ~15 steps. Single-session executable.

## Task 1 — Schema bump v9 → v10

**Files:** entities + AppDatabase.kt.

Steps:
- [ ] CredentialsEntryEntity: add `val deletedAt: Long? = null`.
- [ ] ClonedProviderEntity: add `val deletedAt: Long? = null`.
- [ ] AppDatabase: `version = 10`; add `MIGRATION_9_10` adding the two columns via `ALTER TABLE ... ADD COLUMN deletedAt INTEGER DEFAULT NULL`.
- [ ] Compile + Room schema export.

## Task 2 — DAO read filters + softDelete

- [ ] CredentialsEntryDao: add `softDelete(id: String, deletedAt: Long)`; update `observeAll`, `get` to filter `WHERE deletedAt IS NULL`.
- [ ] ClonedProviderDao: same pattern; update `observeAll`, `getAll` to filter `WHERE deletedAt IS NULL`.
- [ ] Compile.

## Task 3 — Repository routing

- [ ] CredentialsEntryRepository: keep `delete(id: String)` signature; impl now calls `dao.softDelete(id, now)` AND enqueues `SyncOutboxKind.CREDENTIALS` with `deleted = true`.
- [ ] Add `RemoveClonedProviderUseCase` mirroring CloneProviderUseCase shape: soft-deletes the row + enqueues `CLONED_PROVIDER` outbox with `deleted = true`.
- [ ] Compile.

## Task 4 — Unit test + falsifiability rehearsal

- [ ] Create `CredentialsEntryRepositorySoftDeleteTest` with the 4 assertions from the design doc.
- [ ] Rehearsal: revert `delete()` impl to call `dao.delete()` (the hard-delete) — test should fail on the outbox-absence assertion.
- [ ] Run, observe FAIL, revert mutation, re-run GREEN.

## Task 5 — CONTINUATION + commit + push

- [ ] CONTINUATION.md §0 update.
- [ ] Commit with Bluff-Audit stamp.
- [ ] Push to github + gitlab.
