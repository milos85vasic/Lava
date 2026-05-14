#!/usr/bin/env bash
# scripts/build-stats-report.sh — §11.4.24 report generator.
#
# Reads .lava-ci-evidence/build-stats/registry.tsv and emits a Markdown
# report at docs/build-stats/Stats.md. Per §11.4.24 + Lava §6.AD-debt
# item 5: the Markdown is the canonical view; HTML + PDF derive from it
# via the §11.4.22 lightweight commit path's export pipeline (TODO when
# pandoc + wkhtmltopdf are in scope).
#
# Top of report surfaces ever-values (min/max/mean across all tracked
# builds). Per-build entries sorted most-recent-first.
#
# Usage:
#   bash scripts/build-stats-report.sh
#
# Inheritance: HelixConstitution §11.4.24 + Lava §6.AD-debt item 5.
# Classification: project-specific (the report shape is Lava-side; the
# discipline of "registry → derived view" is universal per §11.4.22).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGISTRY="$REPO_ROOT/.lava-ci-evidence/build-stats/registry.tsv"
REPORT_DIR="$REPO_ROOT/docs/build-stats"
REPORT="$REPORT_DIR/Stats.md"

mkdir -p "$REPORT_DIR"

if [[ ! -f "$REGISTRY" ]]; then
    cat > "$REPORT" <<'EMPTY'
# Build Resource Stats

**Status:** No builds tracked yet. The registry at `.lava-ci-evidence/build-stats/registry.tsv` is empty or missing. Run a build with `scripts/build-stats-sample.sh wrap "<name>" -- <cmd>` to populate.

**Inheritance:** HelixConstitution §11.4.24 (build-resource stats tracking).

**Last regenerated:** never (no data).
EMPTY
    echo "==> wrote empty $REPORT (no data in registry)"
    exit 0
fi

total_builds=$(($(wc -l < "$REGISTRY") - 1))
if [[ "$total_builds" -le 0 ]]; then
    cat > "$REPORT" <<'EMPTY'
# Build Resource Stats

**Status:** Registry exists but contains 0 build rows. Run a build to populate.
EMPTY
    echo "==> wrote $REPORT (0 builds in registry)"
    exit 0
fi

ever_values=$(tail -n +2 "$REGISTRY" | awk -F'\t' '
    BEGIN { mem_min = 1e18; cpu_min = 1e18; load_min = 1e18 }
    {
        if ($6 + 0 < mem_min) mem_min = $6 + 0
        if ($7 + 0 > mem_max) mem_max = $7 + 0
        mem_sum += $8; mem_n++
        if ($10 + 0 < cpu_min) cpu_min = $10 + 0
        if ($11 + 0 > cpu_max) cpu_max = $11 + 0
        cpu_sum += $12; cpu_n++
        if ($14 + 0 < load_min) load_min = $14 + 0
        if ($15 + 0 > load_max) load_max = $15 + 0
        load_sum += $16; load_n++
    }
    END {
        if (mem_n == 0) { print "0|0|0|0|0|0|0|0|0"; exit }
        printf "%d|%d|%d|%.1f|%.1f|%.1f|%.2f|%.2f|%.2f",
            mem_min, mem_max, int(mem_sum/mem_n),
            cpu_min, cpu_max, cpu_sum/cpu_n,
            load_min, load_max, load_sum/load_n
    }
')

IFS='|' read -r ev_mem_min ev_mem_max ev_mem_mean ev_cpu_min ev_cpu_max ev_cpu_mean ev_load_min ev_load_max ev_load_mean <<< "$ever_values"

{
    echo "# Build Resource Stats"
    echo ""
    echo "**Inheritance:** HelixConstitution §11.4.24 (build-resource stats tracking)."
    echo "**Last regenerated:** $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "**Total builds tracked:** $total_builds"
    echo ""
    echo "## Ever-values (across all tracked builds)"
    echo ""
    echo "| Metric | Min | Max | Mean |"
    echo "|---|---:|---:|---:|"
    echo "| Memory used (MB) | $ev_mem_min | $ev_mem_max | $ev_mem_mean |"
    echo "| CPU% | $ev_cpu_min | $ev_cpu_max | $ev_cpu_mean |"
    echo "| Load (1m) | $ev_load_min | $ev_load_max | $ev_load_mean |"
    echo ""
    echo "## Per-build (most recent first)"
    echo ""
    echo "| Timestamp | Build | Duration (s) | RC | Mem max (MB) | Mem p95 (MB) | CPU max | CPU p95 | Load max | Samples |"
    echo "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"
    # tac is GNU; portable reverse via awk indexing.
    tail -n +2 "$REGISTRY" | awk -F'\t' '{lines[NR]=$0} END {for (i=NR;i>=1;i--) print lines[i]}' | awk -F'\t' '
        {
            status_emoji = ($5 == 0) ? "✓" : "✗"
            printf "| %s | %s | %d | %s %s | %d | %d | %.1f | %.1f | %.2f | %d |\n",
                $1, $2, $4, status_emoji, $5, $7, $9, $11, $13, $15, $17
        }
    '
} > "$REPORT"

echo "==> wrote $REPORT ($total_builds build(s) tracked)"
