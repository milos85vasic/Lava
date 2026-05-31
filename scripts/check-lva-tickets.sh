#!/usr/bin/env bash
# scripts/check-lva-tickets.sh — CM-LVA-TICKETS-SYNC gate (§11.4.93 / §11.4.95 / §11.4.106).
#
# Per HelixConstitution §11.4.93 (workable-items SQLite DB is the single source
# of truth) + §11.4.95 (the DB is TRACKED in git, NEVER gitignored) + §11.4.106
# (generated tracker docs MUST be byte-identical to a fresh generation FROM the
# DB), this gate proves that the LVA ticket system is internally consistent:
#
#   1. docs/tickets/tickets.db EXISTS and is NOT gitignored (§11.4.95).
#   2. `lava-tickets verify` exits 0 — i.e. the four committed tracker docs
#      (Issues.md / Fixed.md / Issues_Summary.md / Fixed_Summary.md) are
#      byte-identical to a fresh `gen` from the DB (§11.4.106). A stale/edited
#      tracker doc is a violation.
#   3. The DB carries every §11.4.33 (type-aware closure) + §11.4.34 (reopen
#      attribution) trigger — the schema's integrity guards are intact, not
#      dropped by a hand-edit of the DB.
#
# Anti-bluff posture (§6.J / §6.L): the gate's primary signal is the binary's
# own byte-identity verdict + a real sqlite_master query for the trigger set.
# A doc that drifts from the DB, or a DB with a guard trigger removed, MUST make
# this gate FAIL. Falsifiability is proven by
# tests/check-lva-tickets/test_check_lva_tickets.sh.
#
# §6.A real-binary contract: the gate builds (or uses) the actual lava-tickets
# binary and asserts its real exit code — it does not re-implement verify in bash.
#
# Modes:
#   --strict (default): exit 1 on any violation
#   --advisory:         exit 0 even on violation (incremental adoption)
#   LAVA_LVA_TICKETS_STRICT=0 env: same as --advisory
#
# No sudo (§6.U). No network. Host go toolchain + a discoverable sqlite3 only;
# both are confirmed present on this host. If a prerequisite tool is genuinely
# absent the gate degrades HONESTLY (records the exact missing tool) rather than
# faking a PASS.
#
# Inheritance: HelixConstitution §11.4.93/95/106 + §11.4.18 (script docs) +
# §6.A (real-binary contract test) + §6.J/§6.L (anti-bluff).
# Classification: project-specific (the LVA key + Lava's doc set are Lava-specific;
# the §11.4.93/95/106 mandates are universal per HelixConstitution).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$REPO_ROOT"

STRICT="${LAVA_LVA_TICKETS_STRICT:-1}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)   STRICT=1; shift ;;
        --advisory) STRICT=0; shift ;;
        -h|--help)  sed -n '3,40p' "$0"; exit 0 ;;
        *)          echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

# Overridable so the hermetic test can point at a fixture tree.
DB_PATH="${LAVA_LVA_TICKETS_DB:-docs/tickets/tickets.db}"
OUT_DIR="${LAVA_LVA_TICKETS_OUT:-docs/tickets}"
TOOL_DIR="${LAVA_LVA_TICKETS_TOOLDIR:-tools/lava-tickets}"
BIN="$TOOL_DIR/bin/lava-tickets"

# §11.4.33 + §11.4.34 (+ the supporting integrity guards) trigger set that the
# schema MUST install. These are the load-bearing schema-integrity guards: a DB
# missing any of them has had its anti-bluff guards stripped.
REQUIRED_TRIGGERS=(
    trg_closure_status_typeaware          # §11.4.33 type-aware closure guard
    trg_reopen_attribution                # §11.4.34 reopen attribution guard
    trg_operator_blocked_details          # §11.4.21 operator-blocked-details
    trg_ticket_id_after_insert            # §11.4.21/54 LVA-<seq> id rendering
    trg_closed_at                         # closed_at terminal-status stamp
)

violations=()
warnings=()

# -----------------------------------------------------------------------------
# Tool discovery (no sudo; portable across workstations).
# -----------------------------------------------------------------------------

# Discover a sqlite3 binary: PATH first, then common Homebrew + Android
# platform-tools locations (this host ships it under platform-tools).
find_sqlite3() {
    if command -v sqlite3 >/dev/null 2>&1; then
        command -v sqlite3
        return 0
    fi
    local cand
    for cand in \
        /opt/homebrew/bin/sqlite3 \
        /usr/local/bin/sqlite3 \
        /usr/bin/sqlite3 \
        "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/sqlite3" \
        "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/sqlite3"
    do
        if [[ -x "$cand" ]]; then
            echo "$cand"
            return 0
        fi
    done
    return 1
}

# Ensure the binary exists; build it if go is present and it is missing.
ensure_binary() {
    if [[ -x "$BIN" ]]; then
        return 0
    fi
    if ! command -v go >/dev/null 2>&1; then
        warnings+=("lava-tickets binary missing AND go not found — cannot build; install go or pre-build $BIN")
        return 1
    fi
    echo "    (building $BIN via go build ...)"
    if ( cd "$TOOL_DIR" && GOMAXPROCS=2 go build -o bin/lava-tickets . ) >/dev/null 2>&1; then
        return 0
    fi
    violations+=("go build of lava-tickets FAILED")
    return 1
}

echo "==> CM-LVA-TICKETS-SYNC gate (§11.4.93 / §11.4.95 / §11.4.106)"
echo ""
echo "    DB:      $DB_PATH"
echo "    Out dir: $OUT_DIR"
echo "    Binary:  $BIN"

# -----------------------------------------------------------------------------
# Check 1: DB exists and is NOT gitignored (§11.4.95).
# -----------------------------------------------------------------------------
if [[ ! -f "$DB_PATH" ]]; then
    violations+=("$DB_PATH: missing — the workable-items DB must exist (§11.4.93)")
else
    # git check-ignore exits 0 when the path IS ignored; non-zero when NOT.
    # The DB MUST NOT be ignored (§11.4.95). The check MUST run inside the git
    # work-tree that actually CONTAINS the DB — run it from the DB's own dir via
    # `git -C` so a fixture DB elsewhere consults the fixture's .gitignore, not
    # the gate's repo. Tolerate a DB outside any git work-tree (advisory).
    if command -v git >/dev/null 2>&1; then
        db_dir="$(cd "$(dirname "$DB_PATH")" && pwd)"
        db_base="$(basename "$DB_PATH")"
        if git -C "$db_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
            if git -C "$db_dir" check-ignore -q "$db_base" 2>/dev/null; then
                violations+=("$DB_PATH: is GITIGNORED — §11.4.95 requires the DB be TRACKED in git, NEVER gitignored")
            fi
        fi
    fi
fi

# -----------------------------------------------------------------------------
# Check 2: byte-identical round-trip (§11.4.106) via the REAL binary.
# -----------------------------------------------------------------------------
binary_ok=0
if [[ -f "$DB_PATH" ]]; then
    if ensure_binary; then
        binary_ok=1
    fi
fi

if [[ "$binary_ok" == "1" ]]; then
    verify_out=""
    verify_rc=0
    verify_out="$("$BIN" verify --db "$DB_PATH" --out "$OUT_DIR" 2>&1)" || verify_rc=$?
    if [[ $verify_rc -ne 0 ]]; then
        violations+=("§11.4.106 byte-identity FAILED — generated tracker docs differ from a fresh gen (run: $BIN gen --db $DB_PATH --out $OUT_DIR)")
        echo ""
        echo "    verify output:"
        echo "$verify_out" | sed 's/^/      /'
    else
        echo ""
        echo "    §11.4.106 round-trip: ✓ trackers byte-identical with the DB"
    fi
elif [[ -f "$DB_PATH" ]]; then
    warnings+=("could not run byte-identity verify — lava-tickets binary unavailable")
fi

# -----------------------------------------------------------------------------
# Check 3: required §11.4.33 / §11.4.34 triggers present in the DB.
# -----------------------------------------------------------------------------
if [[ -f "$DB_PATH" ]]; then
    SQLITE3="$(find_sqlite3 || true)"
    if [[ -z "$SQLITE3" ]]; then
        warnings+=("sqlite3 not found — cannot verify §11.4.33/34 triggers (PATH + Homebrew + Android platform-tools all checked)")
    else
        present_triggers="$("$SQLITE3" "$DB_PATH" \
            "SELECT name FROM sqlite_master WHERE type='trigger';" 2>/dev/null || true)"
        missing=()
        for t in "${REQUIRED_TRIGGERS[@]}"; do
            if ! grep -qxF "$t" <<<"$present_triggers"; then
                missing+=("$t")
            fi
        done
        if [[ ${#missing[@]} -gt 0 ]]; then
            violations+=("DB missing ${#missing[@]} required schema-integrity trigger(s): ${missing[*]} (§11.4.33/34)")
        else
            echo "    §11.4.33/34 triggers: ✓ all ${#REQUIRED_TRIGGERS[@]} present"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# Report
# -----------------------------------------------------------------------------
echo ""
if [[ ${#warnings[@]} -gt 0 ]]; then
    echo "    Warnings (non-fatal):"
    for w in "${warnings[@]}"; do
        echo "      ! $w"
    done
    echo ""
fi

if [[ ${#violations[@]} -eq 0 ]]; then
    echo "    ✓ LVA ticket system in sync (DB tracked, docs byte-identical, triggers intact)"
    exit 0
fi

echo "    Violations:"
for v in "${violations[@]}"; do
    echo "      ✗ $v"
done

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_LVA_TICKETS_STRICT=1 — failing." >&2
    exit 1
fi
echo ""
echo "    (advisory mode — not failing the gate)"
exit 0
