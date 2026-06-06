#!/usr/bin/env bash
# scripts/hooks/guard-forbidden-commands.sh
#
# Claude Code PreToolUse guard hook (mechanical block) — the "anti-forgetting"
# enforcement that makes mandatory constraints independent of agent recall.
#
# CONTRACT (Claude Code PreToolUse hook, https://code.claude.com/docs/en/hooks):
#   - Receives the tool invocation as JSON on stdin. For tool_name == "Bash"
#     the command string lives at .tool_input.command.
#   - Exit 0  → allow (Claude proceeds normally).
#   - Exit 2  → BLOCK; the text we print to stderr is fed back to Claude as the
#               reason the tool call was refused.
#   - Any other exit code → non-blocking error (we never use those).
#
# WHY THIS EXISTS (forensic anchor): during the on-device-API build, emulator
# subagents ran raw host-direct `emulator`/`adb` instead of going through the
# Containers submodule (§6.X). The orchestrator forgot to inject that rule into
# their prompts. A prompt the orchestrator forgets to paste is not enforcement.
# This hook makes the FORBIDDEN/GATED command classes mechanical: they are
# blocked at the tool-call boundary no matter what any agent remembers.
#
# CLASSES BLOCKED (each maps to a constitutional clause):
#   - Raw host-direct emulator / adb-install / am-instrument  → §6.X / §6.V / §6.AG
#   - git push --force / -f / --force-with-lease / --no-verify / --no-gpg-sign → §6.T.3
#   - sudo / su                                               → §6.U
#   - host power management (suspend/hibernate/poweroff/...)  → Host Stability Directive
#
# ESCAPE HATCH (documented exceptions only): a command containing the literal
# marker `# guardrails:allow <reason>` is WARNED (printed to stderr) but NOT
# blocked. The reason text is mandatory so the exception is self-documenting and
# auditable in the transcript. The escape hatch never applies to host-power
# commands — those are categorically forbidden by the Host Stability Directive
# and there is no in-band reason that overrides "do not power off the operator's
# machine".
#
# This script is generic + portable on purpose: it has no Lava-specific paths and
# reads its input via a tiny embedded JSON-field extractor (no jq dependency), so
# it ports cleanly up into the shared constitution submodule later.
#
# Classification: universal

set -euo pipefail

# --------------------------------------------------------------------------
# Read the entire PreToolUse JSON payload from stdin.
# --------------------------------------------------------------------------
PAYLOAD="$(cat || true)"

# --------------------------------------------------------------------------
# Extract a top-level or nested string field WITHOUT requiring jq.
# We only need two fields: .tool_name and .tool_input.command. Both are plain
# JSON strings. The extractor below is deliberately conservative: it finds the
# key, then reads the following JSON string literal, unescaping \" \\ \n \t.
# If jq IS present we prefer it (more robust against odd payloads).
# --------------------------------------------------------------------------
json_field() {
  # $1 = jq path (e.g. .tool_name or .tool_input.command)
  local path="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$PAYLOAD" | jq -r "$path // empty" 2>/dev/null || true
    return 0
  fi
  # Fallback: pure-bash/python-free extraction via a small awk state machine.
  # Map the jq path to the leaf key we scan for.
  local key
  case "$path" in
    .tool_name) key="tool_name" ;;
    .tool_input.command) key="command" ;;
    *) key="${path##*.}" ;;
  esac
  printf '%s' "$PAYLOAD" | awk -v key="$key" '
    BEGIN { RS="\0" }
    {
      s = $0
      # locate "key" followed by optional ws, colon, optional ws, quote
      idx = index(s, "\"" key "\"")
      if (idx == 0) { exit }
      rest = substr(s, idx + length(key) + 2)   # skip "key"
      # skip whitespace + colon + whitespace
      sub(/^[ \t\r\n]*:[ \t\r\n]*/, "", rest)
      if (substr(rest, 1, 1) != "\"") { exit }   # not a string value
      rest = substr(rest, 2)                       # drop opening quote
      out = ""
      i = 1
      n = length(rest)
      while (i <= n) {
        c = substr(rest, i, 1)
        if (c == "\\") {
          nx = substr(rest, i+1, 1)
          if (nx == "n") out = out "\n"
          else if (nx == "t") out = out "\t"
          else if (nx == "r") out = out "\r"
          else if (nx == "\"") out = out "\""
          else if (nx == "\\") out = out "\\"
          else if (nx == "/") out = out "/"
          else out = out nx
          i += 2
          continue
        }
        if (c == "\"") break       # closing quote → end of value
        out = out c
        i += 1
      }
      printf "%s", out
    }
  '
}

TOOL_NAME="$(json_field .tool_name)"
COMMAND="$(json_field .tool_input.command)"

# Only Bash commands carry a shell-command string we can vet. Other tools pass
# through untouched.
if [[ "$TOOL_NAME" != "Bash" ]]; then
  exit 0
fi

# Nothing to inspect → allow.
if [[ -z "$COMMAND" ]]; then
  exit 0
fi

# --------------------------------------------------------------------------
# Escape-hatch detection. A documented exception marker downgrades a block to a
# warning — EXCEPT for host-power commands, which are never overridable.
# --------------------------------------------------------------------------
ALLOW_MARKER_PRESENT=0
ALLOW_REASON=""
if [[ "$COMMAND" =~ \#[[:space:]]*guardrails:allow[[:space:]]+(.+) ]]; then
  ALLOW_MARKER_PRESENT=1
  ALLOW_REASON="${BASH_REMATCH[1]}"
fi

# block <clause> <message> — refuse the tool call (exit 2) unless an allow-marker
# downgrades it. $3 (optional) = "no-override" to forbid the escape hatch.
block() {
  local clause="$1" message="$2" override="${3:-overridable}"
  if [[ "$ALLOW_MARKER_PRESENT" -eq 1 && "$override" != "no-override" ]]; then
    echo "guardrails: WARNING — ${clause}: ${message}" >&2
    echo "guardrails: allowed by documented exception: ${ALLOW_REASON}" >&2
    return 0   # warned, not blocked; caller continues scanning
  fi
  echo "guardrails: BLOCKED — ${clause}" >&2
  echo "${message}" >&2
  if [[ "$ALLOW_MARKER_PRESENT" -eq 1 && "$override" == "no-override" ]]; then
    echo "guardrails: this class is NOT overridable by '# guardrails:allow'." >&2
  fi
  exit 2
}

# --------------------------------------------------------------------------
# 1. Emulator / device gate (§6.X / §6.V / §6.AG).
#    Raw host-direct emulator launches and APK installs / instrumentation are
#    dev-iteration ONLY and MUST NOT produce gate evidence. Gate runs go through
#    scripts/run-challenge-matrix.sh → the Containers submodule.
# --------------------------------------------------------------------------
EMULATOR_MSG="Gate emulator runs MUST go via scripts/run-challenge-matrix.sh → Containers submodule (§6.X). Raw host-direct adb/emulator is dev-iteration only, never gate evidence."

# Scoped REAL-PHYSICAL-DEVICE / VM exception (operator-authorized 2026-06-03,
# extended 2026-06-06 for Genymotion).
# §6.AH permits a real PHYSICAL device OR a VM-backed virtual device (Genymotion
# runs Android inside a VM — NOT a host-direct `emulator -avd` AVD) as a §6.Z
# surface. adb commands EXPLICITLY targeting an authorized serial (`adb -s
# <serial> …`) are therefore permitted. Emulator serials (`emulator-*`) and
# un-serialed adb stay BLOCKED — an ambiguous/absent target could be a
# host-direct AVD, which §6.X/§6.AH still forbid.
# Env-overridable allowlist; default = the operator-connected Samsung S23 Ultra.
# scripts/run-genymotion-challenges.sh exports the running Genymotion adb serial
# (e.g. 127.0.0.1:6555) into LAVA_REAL_DEVICE_SERIALS so its connected-test path
# is permitted — a §6.AH-compliant VM surface driven by the Containers submodule.
LAVA_REAL_DEVICE_SERIALS="${LAVA_REAL_DEVICE_SERIALS:-R5CW33CBVQV}"
REAL_DEVICE_ADB=""
for _serial in $LAVA_REAL_DEVICE_SERIALS; do
  if [[ "$_serial" != emulator-* ]] && \
     [[ "$COMMAND" =~ (^|[^[:alnum:]_/.-])adb[[:space:]]+-s[[:space:]]+"$_serial"([[:space:]]|$) ]]; then
    REAL_DEVICE_ADB=1
  fi
done

# raw `emulator -avd ...` or a path ending in .../emulator referencing an SDK env
if [[ "$COMMAND" =~ (^|[^[:alnum:]_/.-])emulator[[:space:]]+-avd([[:space:]]|$) ]]; then
  block "§6.X emulator gate" "$EMULATOR_MSG"
fi
if [[ "$COMMAND" =~ \$(ANDROID_[A-Z_]+|\{ANDROID_[A-Z_]+\})[^[:space:]]*/emulator([[:space:]]|$) ]]; then
  block "§6.X emulator gate" "$EMULATOR_MSG"
fi

# top-level `adb install` / `adb -s <serial> install`
# Permitted only when the command explicitly targets an authorized real
# physical serial (REAL_DEVICE_ADB); emulator/un-serialed installs stay blocked.
if [[ -z "$REAL_DEVICE_ADB" ]] && \
   [[ "$COMMAND" =~ (^|[^[:alnum:]_/.-])adb([[:space:]]+-s[[:space:]]+[^[:space:]]+)?[[:space:]]+install([[:space:]]|$) ]]; then
  block "§6.X emulator gate" "$EMULATOR_MSG"
fi

# `am instrument` (instrumentation runner invoked host-direct)
# Permitted only when wrapped in `adb -s <authorized-real-serial> shell am instrument`.
if [[ -z "$REAL_DEVICE_ADB" ]] && \
   [[ "$COMMAND" =~ (^|[^[:alnum:]_/.-])am[[:space:]]+instrument([[:space:]]|$) ]]; then
  block "§6.X emulator gate" "$EMULATOR_MSG"
fi

# --------------------------------------------------------------------------
# 2. Force-push / verification-bypass gate (§6.T.3).
# --------------------------------------------------------------------------
FORCE_MSG="§6.T.3 requires explicit, in-conversation operator approval for THIS specific operation: force push, history rewrite, --no-verify, or --no-gpg-sign. One approval does not cover the next. Ask the operator, then add a documented '# guardrails:allow <reason>' marker if approved."

if [[ "$COMMAND" =~ git([[:space:]]+[^[:space:]]+)*[[:space:]]+push ]]; then
  if [[ "$COMMAND" =~ (^|[[:space:]])--force([[:space:]]|=|$) ]] ||
     [[ "$COMMAND" =~ (^|[[:space:]])-f([[:space:]]|$) ]] ||
     [[ "$COMMAND" =~ (^|[[:space:]])--force-with-lease ]]; then
    block "§6.T.3 force-push" "$FORCE_MSG"
  fi
fi
if [[ "$COMMAND" =~ (^|[[:space:]])--no-verify([[:space:]]|$) ]]; then
  block "§6.T.3 --no-verify" "$FORCE_MSG"
fi
if [[ "$COMMAND" =~ (^|[[:space:]])--no-gpg-sign([[:space:]]|$) ]]; then
  block "§6.T.3 --no-gpg-sign" "$FORCE_MSG"
fi

# --------------------------------------------------------------------------
# 3. sudo / su gate (§6.U).
# --------------------------------------------------------------------------
SUDO_MSG="§6.U forbids sudo / su in any committed artifact or agent tool call. Use a container-based / user-level alternative (rootless Podman, user namespaces, local-only ports)."

if [[ "$COMMAND" =~ (^|[[:space:]]|;|\||&)sudo([[:space:]]|$) ]]; then
  block "§6.U no-sudo" "$SUDO_MSG"
fi
# `su`, `su -`, `su -l`, `su <user>` (but NOT words merely containing 'su' such
# as `subl`, `sudo` already handled above, or `--summary`).
if [[ "$COMMAND" =~ (^|[[:space:]]|;|\||&)su([[:space:]]+-?l?([[:space:]]|$)|[[:space:]]*$) ]]; then
  block "§6.U no-su" "$SUDO_MSG"
fi

# --------------------------------------------------------------------------
# 4. Host-power gate (Host Machine Stability Directive). NOT overridable.
# --------------------------------------------------------------------------
POWER_MSG="Host Machine Stability Directive: commands that suspend / hibernate / power-off / reboot / halt / sign-out the host are categorically forbidden. They destroy in-progress builds and the development session."

if [[ "$COMMAND" =~ (^|[[:space:]]|;|\||&)systemctl[[:space:]]+(suspend|hibernate|hybrid-sleep|suspend-then-hibernate|poweroff|halt|reboot|kexec|kill-user|kill-session) ]]; then
  block "Host Stability (systemctl)" "$POWER_MSG" no-override
fi
if [[ "$COMMAND" =~ (^|[[:space:]]|;|\||&)loginctl[[:space:]]+(suspend|hibernate|hybrid-sleep|suspend-then-hibernate|poweroff|halt|reboot|kill-user|kill-session|terminate-user|terminate-session) ]]; then
  block "Host Stability (loginctl)" "$POWER_MSG" no-override
fi
if [[ "$COMMAND" =~ (^|[[:space:]]|;|\||&)pm-(suspend|hibernate|suspend-hybrid)([[:space:]]|$) ]]; then
  block "Host Stability (pm-*)" "$POWER_MSG" no-override
fi
if [[ "$COMMAND" =~ (^|[[:space:]]|;|\||&)shutdown([[:space:]]|$) ]]; then
  block "Host Stability (shutdown)" "$POWER_MSG" no-override
fi

# --------------------------------------------------------------------------
# Nothing matched (or only allow-marked warnings fired) → allow the command.
# --------------------------------------------------------------------------
exit 0
