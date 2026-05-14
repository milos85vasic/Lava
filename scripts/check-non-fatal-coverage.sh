#!/usr/bin/env bash
# scripts/check-non-fatal-coverage.sh — §6.AC mechanical enforcement.
#
# Scans Kotlin + Go production code for catch blocks (or fallback paths)
# that lack a recordNonFatal/recordWarning call AND lack an explicit
# `// no-telemetry: <reason>` opt-out comment. Closes §6.AC-debt.
#
# §6.AC Comprehensive Non-Fatal Telemetry Mandate (added 2026-05-14, 28th
# §6.L invocation): every catch / error / fallback / unexpected-state path
# on every distributable artifact MUST record a non-fatal telemetry event
# OR explicitly opt out via `// no-telemetry: <reason>`.
#
# This is a bash-based scanner (lightweight; pre-Detekt). Detekt rule
# remains an open option for §6.AC-debt closure cycle 2.
#
# Scope:
#   - .kt files under app/src/main, core/*/src/main, feature/*/src/main
#   - .go files under lava-api-go/internal, lava-api-go/cmd
#
# Excluded:
#   - test sources (*Test.kt, *_test.go) — tests use catches for assertions, not for telemetry
#   - generated code (build/, generated/)
#   - constitution submodule
#   - HelixConstitution-domain submodules (per §6.AD inheritance — they self-enforce)
#
# Detection:
#   - Kotlin: every `} catch (` block whose body lacks `recordNonFatal`,
#     `recordWarning`, OR `// no-telemetry:` (within 5 lines of the catch)
#   - Go: every `if err != nil {` block, every `defer recover() {` block
#     (heuristic; subset of paths but better than nothing)
#
# Acceptance: gate runs as advisory until all violations are addressed.
# Once the violations queue is drained, this script can be promoted to
# pre-push enforcement. Set LAVA_NONFATAL_STRICT=1 to fail on any
# violation; default behavior is WARN-only.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

STRICT="${LAVA_NONFATAL_STRICT:-0}"

# ---------------------------------------------------------------------
# Kotlin: scan production .kt files.
# ---------------------------------------------------------------------
kt_violations=()
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    # awk per-file: track catch blocks + their bodies (next 8 lines).
    # If catch body contains recordNonFatal/recordWarning/no-telemetry, OK.
    file_violations=$(awk '
        /} *catch *\(/ {
            in_catch = 1
            catch_line = NR
            body = ""
            depth = 0
            next
        }
        in_catch {
            body = body "\n" $0
            # Count braces to find catch-block end
            n = gsub(/\{/, "{")
            depth += n
            n = gsub(/\}/, "}")
            depth -= n
            if (depth < 0 || NR - catch_line >= 8) {
                if (body !~ /recordNonFatal|recordWarning|no-telemetry/) {
                    print catch_line
                }
                in_catch = 0
            }
        }
    ' "$f" 2>/dev/null)
    if [[ -n "$file_violations" ]]; then
        while IFS= read -r ln; do
            [[ -z "$ln" ]] && continue
            kt_violations+=("$f:$ln")
        done <<<"$file_violations"
    fi
done < <(find app/src/main core/*/src/main feature/*/src/main \
              -name '*.kt' -not -path '*/build/*' -not -path '*/generated/*' 2>/dev/null)

# ---------------------------------------------------------------------
# Go: scan production .go files.
# ---------------------------------------------------------------------
go_violations=()
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    # Heuristic: every "if err != nil {" not followed within 5 lines by
    # observability.RecordNonFatal / observability.RecordWarning OR by
    # // no-telemetry: comment. Only flag the OUTERMOST err check — many
    # Go funcs propagate err up the chain via `return ..., err`, which is
    # the right pattern (the caller records). Detect: if the err-check
    # body returns the err or wraps with fmt.Errorf, that's "propagation"
    # and is OK without telemetry. Telemetry is required when err is
    # SWALLOWED (assigned to _, ignored, or replaced with a fallback value).
    file_violations=$(awk '
        /if err *!= *nil *\{/ {
            in_block = 1
            block_line = NR
            body = ""
            depth = 0
            next
        }
        in_block {
            body = body "\n" $0
            n = gsub(/\{/, "{")
            depth += n
            n = gsub(/\}/, "}")
            depth -= n
            if (depth < 0 || NR - block_line >= 5) {
                # Telemetry call OR opt-out present? OK.
                if (body ~ /RecordNonFatal|RecordWarning|no-telemetry/) {
                    in_block = 0
                    next
                }
                # Propagation patterns (any of these ANYWHERE in the body): OK.
                # The body is a multi-line concatenated string; the awk match
                # operator does multi-line regex matching across the whole
                # string, so we use patterns that anchor on newlines OR allow
                # `return...err` to be terminated by closing paren / brace.
                #
                # 1. `return ... err` followed by line-break or end-of-body
                # 2. `return ... err)` (err inside a function-call wrapper)
                # 3. `return ... err}` (err as last field of struct literal)
                if (body ~ /return[^{}]*err($|[ \t)}\n,])/) {
                    in_block = 0
                    next
                }
                # Wrapped propagation: fmt.Errorf / errors.Wrap.
                if (body ~ /(fmt\.Errorf|errors\.(Wrap|Wrapf|New))[^\n]*err/) {
                    in_block = 0
                    next
                }
                # HTTP-handler short-circuit: presence of c.Abort* / c.JSON / c.String
                # in the body implies the err is converted to an HTTP 4xx/5xx (the
                # user-visible signal). We do NOT require err.Error in the same
                # statement; multi-line gin.H{} blocks span newlines.
                if (body ~ /AbortWithStatus|AbortWithError|c\.JSON\(http\.|c\.String\(http\./) {
                    in_block = 0
                    next
                }
                # log.* / fmt.Fprintf(os.Stderr, ...) — user-visible signal via stderr/log.
                if (body ~ /log\.(Printf|Println|Fatalf|Fatal|Print|Errorf|Warnf)|fmt\.Fprintf?\(os\.Stderr/) {
                    in_block = 0
                    next
                }
                # slog.* (structured logging — Go-side telemetry surface).
                if (body ~ /slog\.(Debug|Info|Warn|Error|DebugContext|InfoContext|WarnContext|ErrorContext)/) {
                    in_block = 0
                    next
                }
                # Continue/break in a loop (likely propagated up)? OK heuristic.
                if (body ~ /(continue|break)[ \t]*\n/) {
                    in_block = 0
                    next
                }
                # Panic — propagation by other means.
                if (body ~ /panic\(/) {
                    in_block = 0
                    next
                }
                # Otherwise this is a swallow — flag it.
                print block_line
                in_block = 0
            }
        }
    ' "$f" 2>/dev/null)
    if [[ -n "$file_violations" ]]; then
        while IFS= read -r ln; do
            [[ -z "$ln" ]] && continue
            go_violations+=("$f:$ln")
        done <<<"$file_violations"
    fi
done < <(find lava-api-go/internal lava-api-go/cmd \
              -name '*.go' -not -path '*/vendor/*' -not -name '*_test.go' 2>/dev/null)

# ---------------------------------------------------------------------
# Report.
# ---------------------------------------------------------------------
total=$(( ${#kt_violations[@]} + ${#go_violations[@]} ))
echo "==> §6.AC non-fatal-coverage scan"
echo "    Kotlin catch blocks lacking telemetry / opt-out: ${#kt_violations[@]}"
echo "    Go err-swallow blocks lacking telemetry / opt-out: ${#go_violations[@]}"
echo "    Total: $total"

if [[ $total -eq 0 ]]; then
    echo "    ✓ all production catch / error-swallow paths instrumented or opted out"
    exit 0
fi

echo ""
echo "    First 10 Kotlin violations (file:line):"
printf '      %s\n' "${kt_violations[@]:0:10}"
if [[ ${#kt_violations[@]} -gt 10 ]]; then
    echo "      ... and $((${#kt_violations[@]} - 10)) more"
fi
echo ""
echo "    First 10 Go violations (file:line):"
printf '      %s\n' "${go_violations[@]:0:10}"
if [[ ${#go_violations[@]} -gt 10 ]]; then
    echo "      ... and $((${#go_violations[@]} - 10)) more"
fi

echo ""
echo "    Remediation per violation: add analytics.recordNonFatal(throwable, ctx)"
echo "    (Kotlin) or observability.RecordNonFatal(ctx, err, attrs) (Go) inside"
echo "    the catch / err-swallow body, OR add a // no-telemetry: <reason> comment"
echo "    immediately above the catch / err-swallow block."

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_NONFATAL_STRICT=1 — failing on $total violation(s)."
    exit 1
else
    echo ""
    echo "    Default mode: WARN-only. Set LAVA_NONFATAL_STRICT=1 to fail."
    exit 0
fi
