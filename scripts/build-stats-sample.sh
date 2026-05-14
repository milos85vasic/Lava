#!/usr/bin/env bash
# scripts/build-stats-sample.sh — §11.4.24 build-resource stats sampler.
#
# Per HelixConstitution §11.4.24 + Lava §6.AD-debt item 5: every build
# exceeding 1 minute MUST be sampled. The sampler captures:
#   memory used, CPU%, load average, disk read/write
# at a fixed interval (default 5s) and writes per-sample lines to a
# TSV file. On stop, computes per-metric min/max/mean/p95 and appends
# one summary row to the build registry at
# .lava-ci-evidence/build-stats/registry.tsv.
#
# The sampler MUST itself stay under 50 MB RSS and 5% CPU
# (Heisenberg-class observer constraint per §11.4.24).
#
# Usage:
#
#   # Start a sampler in the background; capture its PID.
#   PID=$(scripts/build-stats-sample.sh start "<build-name>" "<command>")
#
#   # Run the actual build.
#   <command>
#   BUILD_RC=$?
#
#   # Stop the sampler + emit summary.
#   scripts/build-stats-sample.sh stop "$PID" "$BUILD_RC"
#
# Or invoke as a wrapper that does start + run + stop in one call:
#
#   scripts/build-stats-sample.sh wrap "<build-name>" -- <command...>
#
# Inheritance: HelixConstitution §11.4.24, Lava §6.AD-debt item 5.
# Classification: mixed (sampler discipline universal; per-platform
# implementation paths project-specific — this script targets darwin
# + linux via portable `ps` + `vm_stat` / `free` invocations).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATS_DIR="$REPO_ROOT/.lava-ci-evidence/build-stats"
REGISTRY="$STATS_DIR/registry.tsv"
INTERVAL="${LAVA_STATS_INTERVAL:-5}"

mkdir -p "$STATS_DIR"

# Initialize registry header if missing.
if [[ ! -f "$REGISTRY" ]]; then
    printf 'timestamp\tbuild_name\tcommand\tduration_s\trc\tmem_min_mb\tmem_max_mb\tmem_mean_mb\tmem_p95_mb\tcpu_min_pct\tcpu_max_pct\tcpu_mean_pct\tcpu_p95_pct\tload_min\tload_max\tload_mean\tsamples\n' > "$REGISTRY"
fi

# Cross-platform helpers.
mem_used_mb() {
    case "$(uname -s)" in
        Darwin)
            # vm_stat reports pages of 4096 bytes by default. Active + Wired = used.
            local pages_active pages_wired page_size
            pages_active=$(vm_stat | awk '/Pages active/ {print $3+0}')
            pages_wired=$(vm_stat | awk '/Pages wired down/ {print $4+0}')
            page_size=$(vm_stat | head -1 | awk '{print $8+0}')
            [[ -z "$page_size" || "$page_size" == "0" ]] && page_size=4096
            echo "$(( (pages_active + pages_wired) * page_size / 1024 / 1024 ))"
            ;;
        Linux)
            free -m | awk '/^Mem:/ {print $3}'
            ;;
        *)
            echo "0"
            ;;
    esac
}

cpu_pct() {
    # Aggregate CPU% across all running processes (rough but consistent).
    case "$(uname -s)" in
        Darwin|Linux)
            ps -A -o %cpu= 2>/dev/null | awk '{s+=$1} END {printf "%.1f", s}'
            ;;
        *)
            echo "0.0"
            ;;
    esac
}

load_1m() {
    case "$(uname -s)" in
        Darwin)
            sysctl -n vm.loadavg | awk '{print $2}'
            ;;
        Linux)
            cut -d' ' -f1 /proc/loadavg
            ;;
        *)
            echo "0.0"
            ;;
    esac
}

start_sampler() {
    local build_name=$1
    local command=$2
    local sample_file="$STATS_DIR/.sample-$$.tsv"
    local meta_file="$STATS_DIR/.meta-$$.tsv"
    printf '%s\t%s\t%s\n' "$(date +%s)" "$build_name" "$command" > "$meta_file"
    (
        while true; do
            printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "$(mem_used_mb)" "$(cpu_pct)" "$(load_1m)" >> "$sample_file"
            sleep "$INTERVAL"
        done
    ) &
    local sampler_pid=$!
    # Print PID + the sample file path so caller can pass them to stop.
    printf '%s\t%s\t%s\n' "$sampler_pid" "$sample_file" "$meta_file"
}

stop_sampler() {
    local sampler_pid=$1
    local sample_file=$2
    local meta_file=$3
    local build_rc=${4:-0}
    if kill -0 "$sampler_pid" 2>/dev/null; then
        kill "$sampler_pid" 2>/dev/null || true
        wait "$sampler_pid" 2>/dev/null || true
    fi
    if [[ ! -f "$sample_file" || ! -s "$sample_file" ]]; then
        echo "  ⚠ no samples captured (build too fast or sampler crashed)" >&2
        rm -f "$sample_file" "$meta_file"
        return 0
    fi
    local start_ts build_name command
    IFS=$'\t' read -r start_ts build_name command < "$meta_file"
    local stop_ts duration_s
    stop_ts=$(date +%s)
    duration_s=$(( stop_ts - start_ts ))
    local samples
    samples=$(wc -l < "$sample_file" | tr -d ' ')

    # Compute aggregates per column. BSD awk lacks asort, so p95 is
    # computed externally via `sort -n` + `awk NR==target`.
    n=$samples
    p95i=$(( n * 95 / 100 ))
    [[ "$p95i" -lt 1 ]] && p95i=1
    [[ "$p95i" -gt "$n" ]] && p95i=$n

    mem_min=$(awk -F'\t' 'BEGIN{m=1e18}{if($2+0<m)m=$2+0}END{print int(m)}' "$sample_file")
    mem_max=$(awk -F'\t' '{if($2+0>m)m=$2+0}END{print int(m)}' "$sample_file")
    mem_mean=$(awk -F'\t' '{s+=$2;n++}END{if(n>0)printf "%d", s/n; else print 0}' "$sample_file")
    mem_p95=$(awk -F'\t' '{print $2}' "$sample_file" | sort -n | awk -v t=$p95i 'NR==t{print int($1)}')

    cpu_min=$(awk -F'\t' 'BEGIN{m=1e18}{if($3+0<m)m=$3+0}END{printf "%.1f", m}' "$sample_file")
    cpu_max=$(awk -F'\t' '{if($3+0>m)m=$3+0}END{printf "%.1f", m}' "$sample_file")
    cpu_mean=$(awk -F'\t' '{s+=$3;n++}END{if(n>0)printf "%.1f", s/n; else print "0.0"}' "$sample_file")
    cpu_p95=$(awk -F'\t' '{print $3}' "$sample_file" | sort -n | awk -v t=$p95i 'NR==t{printf "%.1f", $1}')

    load_min=$(awk -F'\t' 'BEGIN{m=1e18}{if($4+0<m)m=$4+0}END{printf "%.2f", m}' "$sample_file")
    load_max=$(awk -F'\t' '{if($4+0>m)m=$4+0}END{printf "%.2f", m}' "$sample_file")
    load_mean=$(awk -F'\t' '{s+=$4;n++}END{if(n>0)printf "%.2f", s/n; else print "0.00"}' "$sample_file")

    aggregates="${mem_min}	${mem_max}	${mem_mean}	${mem_p95}	${cpu_min}	${cpu_max}	${cpu_mean}	${cpu_p95}	${load_min}	${load_max}	${load_mean}"

    # Append to registry.
    printf '%s\t%s\t%s\t%d\t%d\t%s\t%d\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "$build_name" \
        "$command" \
        "$duration_s" \
        "$build_rc" \
        "$aggregates" \
        "$samples" >> "$REGISTRY"

    # Clean up per-sample files.
    rm -f "$sample_file" "$meta_file"
    echo "  ✓ build-stats: ${build_name} samples=$samples duration=${duration_s}s rc=$build_rc"
}

case "${1:-help}" in
    start)
        start_sampler "$2" "$3"
        ;;
    stop)
        stop_sampler "$2" "$3" "$4" "${5:-0}"
        ;;
    wrap)
        # wrap "<name>" -- <cmd...>
        local_name=$2
        shift 2
        if [[ "${1:-}" == "--" ]]; then shift; fi
        IFS=$'\t' read -r sampler_pid sample_file meta_file < <(start_sampler "$local_name" "$*")
        # Run the build; capture rc.
        BUILD_RC=0
        "$@" || BUILD_RC=$?
        stop_sampler "$sampler_pid" "$sample_file" "$meta_file" "$BUILD_RC"
        exit "$BUILD_RC"
        ;;
    *)
        sed -n '3,33p' "$0"
        exit 1
        ;;
esac
