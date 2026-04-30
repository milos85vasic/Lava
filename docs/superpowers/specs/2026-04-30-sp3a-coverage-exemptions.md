# SP-3a Coverage Exemption Ledger

Per Constitutional clause 6.D (Behavioral Coverage Contract), every uncovered
line in code authored under SP-3a is listed here with reason, reviewer, and
date. Blanket waivers are forbidden.

This file was seeded ahead of Task 0.7 by Task 0.2 (TestBookmarksRepository
stub bluff) because that task needed an entry to land. Task 0.7 will append
the Phase-0 summary section without re-creating the file.

## Format

| File | Lines | Reason | Reviewer | Date | PR |
|---|---|---|---|---|---|

## Entries

| File | Lines | Reason | Reviewer | Date | PR |
|---|---|---|---|---|---|
| `core/testing/src/main/kotlin/lava/testing/repository/TestBookmarksRepository.kt` | all (every method body is `TODO("Not yet implemented")`, except `clear()` which is a no-op) | Complete stub fake. Full behavioral-equivalence implementation against `BookmarksRepositoryImpl` (Room `BookmarkDao`, `@PrimaryKey` on category id, `JSONArray`-encoded `newTopics` column) deferred to the first SP-3a consumer of `BookmarksRepository` — anticipated SP-3a Phase 4 (`tracker_settings` UI) or later. Stub status is locked in place by the inverted test `TestBookmarksRepositoryStubBluffTest`: when the stub is replaced with a real fake, those three assertions will start failing and force the maintainer to (a) remove this row, and (b) replace the inverted test with a true equivalence test patterned after `TestEndpointsRepositoryEquivalenceTest`. The current `MenuViewModelTest` constructs `ClearBookmarksUseCase(TestBookmarksRepository(), ...)` but never triggers a code path that calls a stubbed method (no `MenuAction.ClearBookmarks` test exists), so the stub is latent rather than actively broken. Anti-Bluff Pact Third Law violation acknowledged; falsifiability rehearsal recorded in `.lava-ci-evidence/sp3a-bluff-audit/0.2-test-bookmarks-repository.json`. | implementer (subagent, supervised) | 2026-04-30 | SP-3a-0.2 |
