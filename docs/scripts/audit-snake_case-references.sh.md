# `scripts/audit-snake_case-references.sh` — User Guide

**Last verified:** 2026-05-16 (constitution-compliance plan Phase 6.0 — research+plan cycle).
**Inheritance:** HelixConstitution §11.4.29 (Lowercase-Snake_Case-Naming Mandate) + §11.4.18 (script documentation mandate).
**Classification:** project-specific (the names list IS Lava's 17 owned-by-us submodules; the audit-for-blast-radius-before-rename discipline is universal per §11.4.29).

## Overview

Read-only audit that counts, for each of the 17 owned-by-us submodules (16 `vasic-digital/*` + 1 `HelixDevelopment/HelixQA`), how many references in tracked files point at the current pre-rename name (e.g. `submodules/containers`). The output is a tab-separated table that the operator and reviewers consult BEFORE approving any rename batch under HelixConstitution §11.4.29.

The script's value comes from making the rename blast radius **operator-visible and reproducible**: a single command produces a deterministic snapshot that can be diffed before/after each rename batch to detect stale references — which §11.4.29 declares severity-equivalent to the rename itself ("reference drift after a rename is a §11.4.29 violation of equal severity to the rename itself").

## Prerequisites

- `git` on `$PATH` and the script invoked from inside a git working tree (or via `LAVA_REPO_ROOT=/path/to/repo`).
- POSIX `awk`, `sort`, `head`, `wc` (standard everywhere).
- No network access needed; no privileged operations.

## Usage examples

```bash
# Default — TSV output to stdout
bash scripts/audit-snake_case-references.sh

# Markdown table (paste into PRs / plan docs)
bash scripts/audit-snake_case-references.sh --format=md

# Names + counts only (no TOTAL_Submodules aggregate)
bash scripts/audit-snake_case-references.sh --names-only

# TSV + top-5 files per submodule (for digging into reference hotspots)
bash scripts/audit-snake_case-references.sh --top-files=5

# Capture before/after baselines around a rename
bash scripts/audit-snake_case-references.sh > /tmp/before.tsv
# ... perform rename batch ...
bash scripts/audit-snake_case-references.sh > /tmp/after.tsv
diff /tmp/before.tsv /tmp/after.tsv
```

## Output format

Default TSV:

```
NAME            REFS    FILES
Auth            16      9
Cache           36      15
...
Tracker-SDK     83      25
TOTAL_Submodules        806     124
```

- `NAME` — the current submodule directory name (CamelCase / hyphenated).
- `REFS` — total occurrences of `submodules/<NAME>` across all tracked files.
- `FILES` — distinct file count containing at least one reference.
- `TOTAL_Submodules` — aggregate for any `submodules/` prefix reference (this is broader than the per-submodule sum because it captures bare `submodules/` paths without a specific submodule name).

The `TOTAL_Submodules` aggregate captures the work of Phase 6a (the top-level `submodules/` → `submodules/` rename). Per-submodule rows capture the work of Phase 6b sub-cycles.

## Modes

| Flag | Behavior |
|---|---:|
| (none) | Default: TSV header + 17 per-submodule rows + TOTAL_Submodules aggregate |
| `--format=tsv` | Same as default |
| `--format=md` | Markdown table (header + rows; no TOTAL line in pure-md aesthetic — though TOTAL is included for consistency) |
| `--names-only` | Skip the TOTAL_Submodules aggregate; print only the 17 per-name rows + header |
| `--top-files=N` | Also list the top-N files (by reference count) for each name |
| `--help`, `-h` | Print embedded documentation, exit 0 |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Audit completed successfully (regardless of reference counts) |
| 2 | Argument / environment error (unknown flag, not inside a git repo, git missing) |

The script does NOT exit nonzero on "too many references found" — it is a read-only measurement, not a gate. The gate role belongs to Phase 6e's `scripts/check-snake-case-naming.sh` (planned, not yet implemented).

## Edge cases

- **Files with `submodules/` in comments only.** Counted — the rename will touch them too.
- **Binary files.** `git grep` does not match against binary files unless `-a` is passed; this script does NOT pass `-a`, so binary files are skipped. Acceptable because binary files don't contain renameable path strings worth tracking.
- **`.gitmodules`.** Counted as a tracked file. Renaming requires updating it explicitly.
- **`.lava-ci-evidence/` historical attestations.** Counted. These are historical records; updates are optional for correctness but recommended for consistency.
- **Submodule internals (inside `submodules/<X>/`).** NOT counted by this script because they are tracked by the submodule's own repo, not the parent. Per-submodule renames update the parent's `.gitmodules` + the parent's reference graph; the submodule's internal contents are out of scope.
- **`submodules/tracker_sdk`.** Hyphenated name — `git grep "submodules/tracker_sdk"` works fine because `-` is a literal character in extended regex.
- **`submodules/helixqa`.** HelixDevelopment-owned; same audit treatment as vasic-digital submodules per §11.4.28 (HelixDevelopment is on the owned-org list).

## Internal behaviour

For each of the 17 hard-coded `NAMES` entries:

1. Build the pattern `submodules/<NAME>`.
2. `git grep -c "$pattern"` — emits `<file>:<count>` for every file with ≥1 match.
3. `awk -F: '{s += $NF} END {print s+0}'` sums the per-file counts.
4. `git grep -l "$pattern" | wc -l` counts distinct files.
5. Emit `<NAME>\t<refs>\t<files>` to stdout.

For the `TOTAL_Submodules` aggregate row: same pattern but `submodules/` (no trailing name).

The hard-coded NAMES list is the §11.4.29 migration scope. New owned-by-us submodules added between this script's creation and §11.4.29 closure MUST be added to the NAMES array (a single-line edit) and the companion plan + hermetic test updated accordingly.

## Related scripts

- `scripts/check-no-nested-own-org-submodules.sh` — §11.4.28 nested-chain audit; runs against the same submodule set.
- `scripts/check-helix-deps-manifest.sh` — §11.4.31 dependency-manifest gate; consumes the same submodule registry.
- `scripts/check-constitution.sh` — pre-push enforcement (the §11.4.29 gate will live here once Phase 6e ships).
- `scripts/verify-all-constitution-rules.sh` — sweep wrapper; this audit script is intentionally NOT wired into the sweep because it is a read-only measurement (no FAIL condition exists for "too many references").

## Companion test

`tests/check-constitution/test_audit_snake_case_references.sh` — 11 hermetic checks:

1. `test_script_executable` — script exists + is executable
2. `test_runs_cleanly_on_real_tree` — exit 0 + TSV header on real Lava tree
3. `test_reports_all_17_submodules` — every name in the NAMES array appears in output
4. `test_reports_total_line` — `TOTAL_Submodules` aggregate present + well-formed
5. `test_format_md` — `--format=md` emits a Markdown table header
6. `test_names_only_mode` — `--names-only` emits exactly 18 lines (header + 17 names)
7. `test_help_exits_zero` — `--help` exits 0 + prints docs
8. `test_unknown_arg_exits_2` — unknown flags exit 2 + error message
9. `test_deterministic_output` — two consecutive runs produce identical output
10. `test_numeric_columns` — REFS and FILES columns are integers
11. `test_blast_radius_ordering_sanity` — Containers (370 refs) > Discovery (5 refs); the anti-bluff guarantee that the script reflects actual repo state, not hardcoded values

## Cross-references

- HelixConstitution `Constitution.md` §11.4.29 (the rename mandate this script supports).
- HelixConstitution `Constitution.md` §11.4.18 (script-doc-sync mandate — companion to this doc).
- `docs/plans/2026-05-16-snake_case-migration.md` — the comprehensive migration plan this script enables.
- `docs/plans/2026-05-15-constitution-compliance.md` Phase 6 — the parent compliance plan referencing this work.
- Lava CLAUDE.md §6.AD-debt (HelixConstitution gate-wiring debt — Phase 6 closes a portion).
- `scripts/check-no-nested-own-org-submodules.sh` — sister audit for §11.4.28.

## Falsifiability rehearsal evidence

Per §6.J clause 2 / §6.N: before declaring this audit script "covers" the §11.4.29 baseline-capture role, the following falsifiability rehearsals were performed against `test_blast_radius_ordering_sanity`:

**Mutation:** in `scripts/audit-snake_case-references.sh`, replace the real `count_refs_for_name` with a stub returning `echo "0\t0"` for every name.

**Observed failure:** `test_blast_radius_ordering_sanity` fails: `"Containers (0) should exceed Discovery (0)"`. The deterministic-output test still passes (a stub IS deterministic — both bluffs return zero), but the ordering invariant catches the bluff. The `test_reports_total_line` test ALSO catches it because the TOTAL_Submodules aggregate would return 0, which is not the actual `806`.

**Reverted:** yes. Production code restored; all 11 tests PASS.

This rehearsal demonstrates the test suite has discrimination — it would not silently report PASS if the audit script were lying about its inputs.
