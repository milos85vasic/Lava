# LVA-079 — verification summary (already-fixed, independently confirmed)

"Video #3 — search-input chips vs results-filter chips disagree +
results chip set non-deterministic run-to-run"

A dispatched subagent found this was already fixed prior to this
cycle (fix commit `b06ec32b`, ancestor of this session's HEAD) and
independently re-verified it rather than re-fixing it blind.

## Root cause + fix (confirmed by reading source)

- Search-input chips (`SearchInputViewModel.kt:104-109`): derived
  from `ProviderConfigRepository.observeAll()` filtered by
  `searchEnabled && isEnabled`, `.distinct().sorted()` -- static,
  deterministic.
- Search-results chips fix (`SearchPageState.kt:99-100`):
  `filterProviderChipIds` reads `filter.providerIds.distinct().sorted()`
  -- the REQUESTED/SUBMITTED provider-id list (same list the input
  screen produced), never response/streaming state. This structurally
  guarantees both cross-screen agreement and run-to-run determinism.
- Adjacent raw-id-label issue (finding #4) also already fixed via
  LVA-085's `providerDisplayNames` resolution.

## Independent verification performed

1. Existing real-stack unit test
   `SearchResultFilterChipDeterminismTest` -- GREEN (real
   ViewModel + real SDK/registry, only outermost per-provider
   `search()` faked).
2. Subagent's OWN falsifiability rehearsal: mutated
   `filterProviderChipIds` to drop `.distinct().sorted()` -> RED
   (`AssertionError: results chip set must equal the full requested
   provider set, sorted expected:[archiveorg, rutor, rutracker] but
   was:[rutor, archiveorg, rutracker]`) -- reverted, GREEN again.
3. Full `:feature:search_result:testDebugUnitTest`: 38 tests, 0
   failures.
4. Pre-existing device Challenge Test
   `Challenge60InputResultsChipsAgreeTest` with REAL committed
   device evidence via the §6.X containerized-KVM emulator matrix:
   - `.lava-ci-evidence/lva014-c00-c71-c58-c59-device-gate/Challenge60InputResultsChipsAgreeTest/real-device-verification.json`:
     RED (`test_passed: false`, 2026-08-10, before an unrelated
     shared-test-infra bug was fixed)
   - `.lava-ci-evidence/lva014-c00-c71-c58-c59-device-gate-retest2/Challenge60InputResultsChipsAgreeTest/real-device-verification.json`:
     GREEN (`test_passed: true`, `all_passed: true`, `gating: true`,
     AVD `CZ_API34_Phone`, API 34, `runner: containers-submodule`,
     `runtime: podman`, 2026-08-11)

## Outcome

No production code change was needed this cycle (net diff empty).
Verified already-correct with both fresh unit-level evidence
(this session) and existing real-device evidence (2026-08-11,
one day prior).
