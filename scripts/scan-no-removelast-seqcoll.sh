#!/usr/bin/env bash
# scripts/scan-no-removelast-seqcoll.sh — LVA-054 JDK21 SequencedCollection guard.
#
# Purpose: enforce that no production Kotlin uses the SequencedCollection-shaped
# List/MutableList methods that crash on Android runtimes below API 35.
#
# THE CRASH CLASS (LVA-053 / LVA-054):
#   Kotlin `MutableList.removeLast()` / `removeFirst()` and `List.getFirst()` /
#   `getLast()` compile, under JDK21 + AGP core-library-desugaring, to the JDK21
#   `java.util.SequencedCollection` methods (`java.util.List.removeLast`, etc.).
#   Those methods do NOT exist on the Android platform's `java.util.ArrayList`
#   below API 35, so a real user on Android 14 / 13 / 12 / ... hits a runtime
#   `NoSuchMethodError`. JVM unit tests pass (desktop ArrayList HAS the methods),
#   which is exactly the bluff this gate evicts: the test is green, the feature
#   crashes on the user's device.
#
# THE SAFE REPLACEMENTS (no opt-out needed — just use these):
#   list.removeLast()  -> list.removeAt(list.lastIndex)
#   list.removeFirst() -> list.removeAt(0)
#   list.getFirst()    -> list.first()   (or list[0])
#   list.getLast()     -> list.last()    (or list[list.lastIndex])
#
# OPT-OUT (rare): a custom non-List class that legitimately defines its own
#   `getFirst()` / `removeLast()` (NOT a java.util collection) may add the
#   trailing comment `// seqcoll-safe: <reason>` on the SAME line. The opt-out
#   is deliberately auditable — a reviewer sees every one of them.
#
# Scope: production Kotlin only — `core/`, `feature/`, `app/`, `proxy/`,
#   excluding `src/test/` and `src/androidTest/` (test fixtures run on the JVM
#   where the methods exist and the crash cannot manifest).
#
# Exit codes:
#   0 — no SequencedCollection-shaped method calls in production Kotlin
#   1 — violation(s) found (paths + lines printed to stderr)

set -euo pipefail

cd "$(dirname "$0")/.."

# NUL-delimited so whitespace paths survive; explicit -f guard drops gitlinks.
violations=$(
  git ls-files -z -- 'core/**/*.kt' 'feature/**/*.kt' 'app/**/*.kt' 'proxy/**/*.kt' \
    | while IFS= read -r -d '' p; do
        [[ -f "$p" ]] || continue
        case "$p" in
          */src/test/*|*/src/androidTest/*) continue ;;
        esac
        # Match the four risky method-call forms with explicit parens. The
        # `getFirst()`/`getLast()` forms also catch the JDK21 read accessors.
        # `// seqcoll-safe:` on the same line is the auditable opt-out.
        grep -nE '\.(removeLast|removeFirst|getFirst|getLast)\(\)' "$p" 2>/dev/null \
          | while IFS= read -r line; do
              lineno="${line%%:*}"
              body="${line#*:}"
              # Auditable opt-out for legitimate custom non-List types.
              printf '%s' "$body" | grep -q '// seqcoll-safe:' && continue
              # Strip comment content (line `//`, block `/* */`, KDoc `* `) so
              # the guard never flags an occurrence that lives ONLY in a comment
              # (e.g. the LVA-053 fix's own explanatory `// ...removeLast()...`
              # docstring). A REAL call with a trailing `// note` keeps the call.
              stripped="$(printf '%s' "$body" | sed -E -e 's@/\*.*\*/@@g' -e 's@//.*@@' -e 's@^[[:space:]]*\*.*@@')"
              if printf '%s' "$stripped" \
                   | grep -qE '\.(removeLast|removeFirst|getFirst|getLast)\(\)'; then
                printf '%s:%s:%s\n' "$p" "$lineno" "$body"
              fi
            done
      done \
    || true
)

if [[ -n "$violations" ]]; then
  echo "LVA-054 VIOLATION: SequencedCollection-shaped List method(s) in production Kotlin:" >&2
  echo "$violations" >&2
  echo "  These desugar to java.util.List.removeLast/getFirst/... which are ABSENT" >&2
  echo "  on Android < API 35 -> NoSuchMethodError crash at runtime." >&2
  echo "  Fix: removeLast()->removeAt(lastIndex); removeFirst()->removeAt(0);" >&2
  echo "       getFirst()->first(); getLast()->last(). Custom non-List types may" >&2
  echo "       append '// seqcoll-safe: <reason>' on the same line." >&2
  exit 1
fi

exit 0
