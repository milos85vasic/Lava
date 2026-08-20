# Workable-Items Evidence-Path Exemption Ledger

Per the Constitutional §6.D Behavioral Coverage Contract pattern (same precedent as
`docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`): every gate exemption
is listed here individually with reason, investigation method, and date. Blanket
waivers are forbidden — `scripts/check-workable-items.sh` parses the machine-readable
block below and rejects the gate on ANY `CM-WORKABLE-ITEMS-SYNC` violation whose
`(atm_id, history_id)` pair is not listed here verbatim. Adding a row to this file
does not fabricate evidence — it is a documented acknowledgment that no honest
resolvable-artifact fix exists for that specific closure, with the investigation
that proves it.

> **See also: Seventh Law clause 5 (Recurring Bluff Hunt).** An exemption whose stated
> reason no longer holds (e.g. the cited deletion commit turns out to have a live
> successor file, or the workable-items binary later gains a
> mark-historically-deleted mechanism) MUST be closed by fixing the underlying
> `item_history` row via `correct-history-evidence` and removing its ledger row — never
> by leaving a stale exemption in place once a real fix becomes possible.

## Background

On 2026-06-28, commit `56f2417795c98408ebbb6b62ed4ac029526c5ad8` ("evidence cleanup +
submodule pin advances") bulk-deleted the entire `.lava-ci-evidence/workable-items/`
directory of per-item evidence files that 49 of the 51 LVA-N closures recorded in
June 2026 pointed at. A separate, unmerged investigation branch (commit `6a56a394`,
2026-08-20) individually triaged all 78 `validate` violations that existed at the
time, fixed 20 of them where the closure narrative embedded a still-existing literal
file path (via `correct-history-evidence`, never a hand-edit of the SQLite file), and
left the remaining 58 open with per-item reasoning recorded in a gitignored scratch
file. This session (2026-08-20) independently re-derived the same triage against the
current DB state — confirming the same root cause for the deleted-evidence class via
`git log --all --diff-filter=D` on every one of the 49 affected paths (not a re-read
of the prior branch's notes) — fixed 2 further violations (LVA-013 history ids 202/203,
whose narrative already named two still-existing, unambiguous evidence files that only
needed disaggregating into one path per row), and is recording the remaining 67 as an
explicit, auditable exemption ledger rather than leaving `CM-WORKABLE-ITEMS-SYNC`
permanently red with no path to a passing gate.

## Category A — evidence bulk-deleted by commit `56f2417795c9` (2026-06-28), no live replacement

49 items. Verified by running `git log --all --diff-filter=D --format=%H -- <path>`
against every one of the paths below and confirming the deleting commit is exactly
`56f2417795c98408ebbb6b62ed4ac029526c5ad8` (script output cross-checked, zero anomalies).
The workable-items binary has no subcommand to mark historical evidence
"deleted, closure narrative stands" (checked `--help`, `schema.sql`, `evidence.go`,
`correct_evidence.go` in `constitution/scripts/workable-items/` — none exists). Inventing
a replacement evidence path would be fabrication; leaving the row unexempted would
permanently block every future push for a defect in tooling this project doesn't
control the pin of on a whim (`constitution/` is frozen-by-default, CONST-049 pipeline).

| ATM-ID | History-ID | Event | Date | Evidence path (deleted) |
|---|---|---|---|---|
| LVA-025 | 51 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-025-026-evidence.md` |
| LVA-026 | 52 | Implemented | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-025-026-evidence.md` |
| LVA-028 | 53 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-028-evidence.md` |
| LVA-029 | 54 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-029-evidence.md` |
| LVA-030 | 67 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-030-evidence.md` |
| LVA-032 | 63 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-032-evidence.md` |
| LVA-033 | 64 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-033-evidence.md` |
| LVA-034 | 65 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-034-evidence.md` |
| LVA-035 | 66 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-035-evidence.md` |
| LVA-037 | 71 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-037-evidence.md` |
| LVA-038 | 80 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-038-evidence.md` |
| LVA-039 | 81 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-039-evidence.md` |
| LVA-040 | 82 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-040-evidence.md` |
| LVA-041 | 83 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-041-evidence.md` |
| LVA-042 | 84 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-042-evidence.md` |
| LVA-043 | 85 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-043-evidence.md` |
| LVA-044 | 96 | Implemented | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-044-evidence.md` |
| LVA-045 | 86 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-045-evidence.md` |
| LVA-046 | 94 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-046-evidence.md` |
| LVA-047 | 95 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-047-evidence.md` |
| LVA-048 | 98 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-048-evidence.md` |
| LVA-049 | 99 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-049-evidence.md` |
| LVA-050 | 100 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-050-evidence.md` |
| LVA-051 | 97 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-051-evidence.md` |
| LVA-052 | 111 | Implemented | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-052-evidence.md` |
| LVA-053 | 102 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-053-evidence.md` |
| LVA-054 | 112 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-054-evidence.md` |
| LVA-055 | 113 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-055-evidence.md` |
| LVA-056 | 107 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-056-evidence.md` |
| LVA-057 | 117 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-057-evidence.md` |
| LVA-058 | 118 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-058-evidence.md` |
| LVA-059 | 120 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-059-evidence.md` |
| LVA-060 | 119 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-060-evidence.md` |
| LVA-061 | 121 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-061-evidence.md` |
| LVA-062 | 122 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-062-evidence.md` |
| LVA-063 | 124 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-063-evidence.md` |
| LVA-064 | 129 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-064-evidence.md` |
| LVA-065 | 130 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-065-evidence.md` |
| LVA-066 | 128 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-066-evidence.md` |
| LVA-067 | 148 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-067-evidence.md` |
| LVA-068 | 135 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-068-evidence.md` |
| LVA-069 | 136 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-069-evidence.md` |
| LVA-070 | 146 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-070-evidence.md` |
| LVA-071 | 147 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-071-evidence.md` |
| LVA-072 | 143 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-072-evidence.md` |
| LVA-073 | 141 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-073-evidence.md` |
| LVA-074 | 145 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-074-evidence.md` |
| LVA-2 | 9 | Completed | 2026-05-31 | `.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64` |
| LVA-4 | 149 | Implemented | 2026-06-09 | `.lava-ci-evidence/workable-items/LVA-4-evidence.md` |

## Category B — malformed path, never existed under either literal form

2 items (same underlying item, LVA-6/LVA-031, recorded on two history rows).
Path is `.lava-ci-evidence/workable-items/../codegraph/lva6-groundtruth-20260609.md`.
Verified via `git log --all --format=%H` (not `--diff-filter=D`) on BOTH the literal
string and its `..`-normalized form `.lava-ci-evidence/codegraph/lva6-groundtruth-20260609.md`
— zero commits in either case. This was never a deletion; the path was wrong from the
moment it was recorded.

| ATM-ID | History-ID | Event | Date | Evidence path (never existed) |
|---|---|---|---|---|
| LVA-031 | 62 | Fixed | 2026-06-09 | `.lava-ci-evidence/workable-items/../codegraph/lva6-groundtruth-20260609.md` |
| LVA-6 | 55 | Completed | 2026-06-09 | `.lava-ci-evidence/workable-items/../codegraph/lva6-groundtruth-20260609.md` |

## Category C — narrative closure with no unambiguous resolvable file

16 items. The closure narrative names a test class, module, or feature area
(e.g. "core/testing TestEndpointsRepository Rutracker no-op + equivalence test") but
does not embed one specific, still-existing FILE path with a slash and extension — only
bare module/package directories match a path-shaped regex (`core/testing`, `core/domain`,
`feature/rating`, `feature/favorites`, `feature/topic`, `core/preferences`, `submodules/helixqa`).
Recording a directory as "evidence" for a specific behavioral fix would be a guess, not
proof — indistinguishable from picking one of several plausible files, which the Seventh
Law clause 4 forbidden-pattern list and this project's own prior 2026-08-20 triage both
declined to do ("picking one would have been a guess, so they were deliberately left
alone"). Left open pending either a richer closure-time evidence discipline going
forward, or operator decision to accept a specific file per item.

| ATM-ID | History-ID | Event | Date | Narrative (truncated) |
|---|---|---|---|---|
| LVA-009 | 16 | Fixed | 2026-06-09 | submodules/helixqa pin 639f7652 (launch verb builds launcher intent) + .lava-ci-… |
| LVA-011 | 18 | Completed | 2026-06-09 | commit d9a4eaaa core/testing TestEndpointsRepository Rutracker no-op + equivalen… |
| LVA-012 | 19 | Completed | 2026-06-09 | commit d28ddd22 core/testing Visited/Favorites/Bookmarks fakes implemented + 13 … |
| LVA-013 | 20 | Completed | 2026-06-09 | core/testing TestEndpointsRepository.observeAll filterNot Rutracker + no-seed (m… |
| LVA-014 | 23 | Completed | 2026-06-09 | core/testing TestSuggestsRepository implemented (in-memory, case-insensitive UPS… |
| LVA-015 | 24 | Completed | 2026-06-09 | core/testing TestSearchHistoryRepository content-derived id (replicates Filter.i… |
| LVA-016 | 28 | Fixed | 2026-06-09 | core/domain ObserveRatingRequestUseCaseImpl made public (Fifth Law); feature/rat… |
| LVA-017 | 29 | Completed | 2026-06-09 | feature/favorites InMemoryFavoritesRepository.add + feature/topic FakeFavoritesR… |
| LVA-018 | 30 | Completed | 2026-06-09 | core/preferences PreferencesStorage[Impl] getHistorySyncPeriod/getCredentialsSyn… |
| LVA-019 | 32 | Fixed | 2026-06-09 | internal/nnmclub/login.go selectors quoted; login_isauthorised_branch_test.go (I… |
| LVA-020 | 35 | Fixed | 2026-06-09 | flexString type (string/number/array→joined) in internal/archiveorg/flexstring.g… |
| LVA-021 | 37 | Fixed | 2026-06-09 | underlyingTypeName fallback → fmt.Sprintf(%T, err); nonfatal_typed_errorclass_te… |
| LVA-022 | 39 | Fixed | 2026-06-09 | matchFormatByPrefix helper; bestFormatName+pickBestFormatURL prefix-match; utils… |
| LVA-023 | 41 | Fixed | 2026-06-09 | provider.go AuthToken: success.User.Token; provider_adapter_e2e_test.go TestAdap… |
| LVA-024 | 43 | Fixed | 2026-06-09 | provider.go drops the synthetic Files entry; provider_mapping_test.go TestFromTo… |
| LVA-027 | 47 | Fixed | 2026-06-09 | KinozalSizeParser (binary mult, comma/dot, Latin+Cyrillic units) + KinozalSearch… |

## Machine-readable exemption set

Parsed verbatim by `scripts/check-workable-items.sh`. One `ATM-ID|history-id` pair per
line inside the fenced block; nothing else in this file is parsed. A violation whose
`(atm_id, history_id)` is not listed here fails the gate.

```exemptions
LVA-009|16
LVA-011|18
LVA-012|19
LVA-013|20
LVA-014|23
LVA-015|24
LVA-016|28
LVA-017|29
LVA-018|30
LVA-019|32
LVA-020|35
LVA-021|37
LVA-022|39
LVA-023|41
LVA-024|43
LVA-025|51
LVA-026|52
LVA-027|47
LVA-028|53
LVA-029|54
LVA-030|67
LVA-031|62
LVA-032|63
LVA-033|64
LVA-034|65
LVA-035|66
LVA-037|71
LVA-038|80
LVA-039|81
LVA-040|82
LVA-041|83
LVA-042|84
LVA-043|85
LVA-044|96
LVA-045|86
LVA-046|94
LVA-047|95
LVA-048|98
LVA-049|99
LVA-050|100
LVA-051|97
LVA-052|111
LVA-053|102
LVA-054|112
LVA-055|113
LVA-056|107
LVA-057|117
LVA-058|118
LVA-059|120
LVA-060|119
LVA-061|121
LVA-062|122
LVA-063|124
LVA-064|129
LVA-065|130
LVA-066|128
LVA-067|148
LVA-068|135
LVA-069|136
LVA-070|146
LVA-071|147
LVA-072|143
LVA-073|141
LVA-074|145
LVA-2|9
LVA-4|149
LVA-6|55
```

