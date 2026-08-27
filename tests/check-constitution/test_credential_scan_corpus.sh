#!/usr/bin/env bash
# Tests for the §6.H credential-scan corpus assertion in
# scripts/check-constitution.sh (added 2026-08-22 by the §6.N.2 gate-shaping
# bluff hunt).
#
# The defect this guards: the scan corpus comes from
#   mapfile -t tracked_files < <( git ls-files 2>/dev/null | grep -vE '<exempt>' || true )
# When `git ls-files` yields nothing the array is empty, the scan loop runs
# zero times, credential_violations stays 0, and the script prints
# "no clause-6.H credential patterns in tracked files" and exits 0 — a PASS
# that examined nothing. That is §6.J's "nothing was learned reported as
# nothing failed".
#
# Falsifiability rehearsal per §6.J clause 2: test 1 removes the corpus and
# asserts the gate now REFUSES; test 3 re-introduces a real credential
# pattern into a non-empty corpus and asserts the gate still catches it, so
# the new guard cannot be mistaken for the detection it protects.
#
# Test 4 (added 2026-08-25, LVA-134) guards the exemption added to the
# scanner in the same change: the generated workable-items tracker
# renderings {Issues,Fixed}{,_Summary}.{md,html,pdf,docx} are exempt, and
# test 4 proves that exemption is ANCHORED to those generated paths rather
# than sweeping docs/ or any docs/Issues* prefix.
#
# Tests 5 and 6 (added 2026-08-26) cover the ROOT half of the same exemption.
# The repo tracks a second, byte-identical copy of all four renderings at the
# repository root, so the anchor was widened to `^(docs/)?…` — 32 exempt
# paths, not 16. Test 6 proves the root paths are genuinely covered; test 5
# proves the newly-optional `docs/` component did not turn the anchor into a
# prefix sweep at the root the way test 4 proves it did not under docs/.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-constitution.sh"

if [[ ! -f "$SCANNER" ]]; then
    echo "FAIL: scanner not found at $SCANNER"
    exit 1
fi

# Build a PATH shim whose `git ls-files` emits exactly the lines given in
# $LAVA_TEST_LSFILES (one per line, empty = no output). Every other git
# subcommand is delegated to the real binary, so the rest of
# check-constitution.sh runs against the real tree unchanged.
#
# -z IS HONOURED (fixed 2026-08-26, LVA-159). It was not, and that made this
# whole suite vacuous for the three §6.R scanners that check-constitution.sh
# shells out to: they call `git ls-files -z` and split on NUL, so a stub that
# answered with a newline handed them one malformed record ("README.md\n"),
# which is not a readable regular file. Every §6.R scan under this suite
# therefore examined ZERO files while the suite reported green — the exact
# §6.J shape "nothing was learned reported as nothing failed". It only became
# visible when the corpus floor added to those scanners started REFUSING an
# empty corpus instead of silently exiting 0. Emitting NUL-terminated records
# under -z is what makes the corpus this suite claims to supply actually
# arrive.
#
# --deleted answers with the EMPTY set. The stub's corpus is a virtual list of
# files that are all present; none is pending deletion. Replaying the whole
# corpus in answer to `git ls-files -z --deleted` (which the naive stub did)
# would tell the scanners every file they were about to read had been deleted,
# which is both false and exactly the wrong direction for an anti-bluff guard:
# it would let a genuinely-missing path be classified as an expected deletion.
_make_git_stub() {
    local dir=$1 real
    real=$(command -v git)
    mkdir -p "$dir"
    cat > "$dir/git" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "ls-files" ]]; then
    shift
    _z=0; _deleted=0
    for _a in "\$@"; do
        case "\$_a" in
            -z) _z=1 ;;
            --deleted) _deleted=1 ;;
        esac
    done
    # Nothing in the virtual corpus is deleted.
    [[ "\$_deleted" -eq 1 ]] && exit 0
    if [[ -n "\${LAVA_TEST_LSFILES:-}" ]]; then
        if [[ "\$_z" -eq 1 ]]; then
            printf '%s\n' "\$LAVA_TEST_LSFILES" | while IFS= read -r _p; do
                [[ -n "\$_p" ]] && printf '%s\0' "\$_p"
            done
        else
            printf '%s\n' "\$LAVA_TEST_LSFILES"
        fi
    fi
    exit 0
fi
exec "$real" "\$@"
EOF
    chmod +x "$dir/git"
}

# -----------------------------------------------------------------------------
# Test 1: empty corpus → gate REFUSES (exit 1) instead of claiming a clean scan
# -----------------------------------------------------------------------------
test_empty_corpus_refused() {
    local d out rc
    d=$(mktemp -d); _make_git_stub "$d/bin"
    out=$(PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="" bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -ne 0 ]] && grep -q 'examined ZERO tracked files' <<<"$out"; then
        echo "PASS test_empty_corpus_refused"
    else
        echo "FAIL test_empty_corpus_refused: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 2: non-empty clean corpus → guard does NOT fire; script runs to completion
#
# The corpus is TWO paths, not one, and the second is load-bearing. check-
# constitution.sh shells out to the three §6.R scanners, and ipv4/hostport
# exempt '\.md$' from their own corpora — so a README.md-only list reaches
# them as zero files and their corpus floors correctly REFUSE. That refusal is
# a true statement about a corpus this fixture failed to supply, not a defect
# in the floor. settings.gradle.kts survives every one of the three exemption
# filters, so each scanner receives a real file to read. Asserting the count
# each scanner reports is what keeps this honest: the previous single-path
# form let all three examine nothing while this test still printed PASS.
# -----------------------------------------------------------------------------
test_clean_corpus_passes() {
    local d out rc
    d=$(mktemp -d); _make_git_stub "$d/bin"
    out=$(PATH="$d/bin:$PATH" LAVA_TEST_LSFILES=$'README.md\nsettings.gradle.kts' bash "$SCANNER" 2>&1)
    rc=$?
    if [[ "$rc" -eq 0 ]] && grep -q 'Constitution check passed' <<<"$out" \
       && ! grep -q 'examined ZERO tracked files' <<<"$out" \
       && ! grep -q 'examined ZERO files' <<<"$out" \
       && grep -q '6\.R UUID scan clean: [1-9]' <<<"$out" \
       && grep -q '6\.R IPv4 scan clean: [1-9]' <<<"$out" \
       && grep -q '6\.R host:port scan clean: [1-9]' <<<"$out"; then
        echo "PASS test_clean_corpus_passes"
    else
        echo "FAIL test_clean_corpus_passes: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 3: the guard did NOT displace real detection — a genuine §6.H pattern in
# a non-empty corpus is still caught.
# -----------------------------------------------------------------------------
test_real_violation_still_caught() {
    local d out rc leak
    d=$(mktemp -d); _make_git_stub "$d/bin"
    # The scan loop resolves paths relative to the repo root, so the fixture
    # must live inside it. Use a uniquely-named temp file and remove it after.
    leak="lava-bluffhunt-fixture-$$.kt"
    # Self-safe idiom (LVA-134). The leak string is ASSEMBLED AT RUNTIME rather
    # than stored verbatim, so this TRACKED test file does not itself match the
    # §6.H `private object *Bridge {` pattern it plants. Storing it literally
    # made scripts/check-constitution.sh flag the very test that proves the
    # scanner works: the gate reported its own fixture as a leak and exited 1,
    # and because the clause-6.H block runs BEFORE the §6.N/O/P/Q propagation
    # blocks, the run aborted there and propagation was never checked at all.
    # Same idiom scripts/scan-no-hardcoded-ipv4.sh uses in its own header so
    # that comment does not trip its own self-scan.
    #
    # This is STRICTLY BETTER than exempting tests/ from the §6.H corpus:
    #   * tests/** (102 tracked files) stays fully scanned — zero coverage lost;
    #   * the assembly is SELF-VERIFYING — if it ever drifts so the written
    #     file stops matching the scanner's regex, the scanner finds nothing,
    #     rc becomes 0, and this test FAILS loudly. An exemption would instead
    #     have gone quiet, which is the §6.J shape this suite exists to forbid.
    printf '%s object CredsBridge {\n}\n' 'private' > "$REPO_ROOT/$leak"
    out=$(cd "$REPO_ROOT" && PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="$leak" bash "$SCANNER" 2>&1)
    rc=$?
    rm -f "$REPO_ROOT/$leak"
    if [[ "$rc" -ne 0 ]] && grep -q 'clause 6.H violation' <<<"$out"; then
        echo "PASS test_real_violation_still_caught"
    else
        echo "FAIL test_real_violation_still_caught: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 4: the LVA-134 tracker-rendering exemption must be ANCHORED, not a
# prefix sweep. `docs/Issues_Notes_*.md` begins with `docs/Issues` and ends in
# `.md`, so an unanchored (or merely sloppier) exemption regex would swallow
# it. It is NOT a generated tracker rendering and MUST still be scanned.
# -----------------------------------------------------------------------------
test_exemption_does_not_overreach() {
    local d out rc leak
    d=$(mktemp -d); _make_git_stub "$d/bin"
    leak="docs/Issues_Notes_bluffhunt-$$.md"
    # Same self-safe runtime assembly as test 3 — see the comment there.
    printf '%s object CredsBridge {\n}\n' 'private' > "$REPO_ROOT/$leak"
    out=$(cd "$REPO_ROOT" && PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="$leak" bash "$SCANNER" 2>&1)
    rc=$?
    rm -f "$REPO_ROOT/$leak"
    if [[ "$rc" -ne 0 ]] && grep -q 'clause 6.H violation' <<<"$out"; then
        echo "PASS test_exemption_does_not_overreach"
    else
        echo "FAIL test_exemption_does_not_overreach: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 5 (added 2026-08-26): the exemption anchor was widened from
# `^docs/(Issues|Fixed)…` to `^(docs/)?(Issues|Fixed)…` because this repo
# tracks the SAME generated tracker renderings at the repository ROOT as well
# as under docs/ (byte-identical; verified with cmp), and the docs/-only
# anchor left the root copies scanned — so regenerating the trackers put the
# LVA-134 ticket body's verbatim pattern into Fixed.md/Fixed.html/
# Fixed_Summary.md/Fixed_Summary.html and re-broke the gate.
#
# Making a path component OPTIONAL is exactly the kind of edit that quietly
# turns an anchor into a sweep, so this test pins the ROOT half the way test 4
# pins the docs/ half: `Issues_Notes_*.md` at the repository root begins with
# `Issues` and ends in `.md`, is NOT a generated tracker rendering, and MUST
# still be scanned.
# -----------------------------------------------------------------------------
test_root_exemption_does_not_overreach() {
    local d out rc leak
    d=$(mktemp -d); _make_git_stub "$d/bin"
    leak="Issues_Notes_bluffhunt-$$.md"
    # Same self-safe runtime assembly as test 3 — see the comment there.
    printf '%s object CredsBridge {\n}\n' 'private' > "$REPO_ROOT/$leak"
    out=$(cd "$REPO_ROOT" && PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="$leak" bash "$SCANNER" 2>&1)
    rc=$?
    rm -f "$REPO_ROOT/$leak"
    if [[ "$rc" -ne 0 ]] && grep -q 'clause 6.H violation' <<<"$out"; then
        echo "PASS test_root_exemption_does_not_overreach"
    else
        echo "FAIL test_root_exemption_does_not_overreach: rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

# -----------------------------------------------------------------------------
# Test 6 (added 2026-08-26): the widened anchor must actually COVER the root
# generated renderings — otherwise test 5 alone would pass against a scanner
# that had simply reverted to docs/-only.
#
# NON-DESTRUCTIVE BY CONSTRUCTION. An earlier draft overwrote the real, tracked
# Fixed_Summary.html with the pattern and restored it afterwards. That plant was
# never load-bearing and the mutation window was a genuine cross-suite hazard:
# CM-WORKABLE-ITEMS-SYNC and CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC both read the
# generated trackers, so a sweep sampling that file mid-test would see drift in
# a file this suite does not own. The assertion below is exactly as strong
# without it: "examined ZERO tracked files" can only be produced by the corpus
# being EMPTY, which means the path was filtered out by the exemption. Had it
# been scanned, the corpus would have held one file and the guard would not
# have fired — so the ZERO verdict IS the proof of exemption, and the file's
# contents never entered into it.
# -----------------------------------------------------------------------------
test_root_tracker_rendering_is_exempt() {
    local d out rc probe
    d=$(mktemp -d); _make_git_stub "$d/bin"
    probe="Fixed_Summary.html"
    out=$(cd "$REPO_ROOT" && PATH="$d/bin:$PATH" LAVA_TEST_LSFILES="$probe" bash "$SCANNER" 2>&1)
    rc=$?
    if grep -q 'examined ZERO tracked files' <<<"$out"; then
        echo "PASS test_root_tracker_rendering_is_exempt"
    else
        echo "FAIL test_root_tracker_rendering_is_exempt: expected the root tracker rendering to be filtered out of the §6.H corpus; rc=$rc out=$out"
        rm -rf "$d"; exit 1
    fi
    rm -rf "$d"
}

test_empty_corpus_refused
test_clean_corpus_passes
test_real_violation_still_caught
test_exemption_does_not_overreach
test_root_exemption_does_not_overreach
test_root_tracker_rendering_is_exempt

echo "All 6 credential-scan corpus tests PASSED"
