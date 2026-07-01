#!/usr/bin/env bash
# scripts/autonomous-qa/lib-remote.sh
# ---------------------------------------------------------------------------
# Approach B (run-on-thinker): co-locate the ENTIRE autonomous-QA heavy stack
# on thinker.local so build (gradle) + containerized KVM emulator (podman) +
# adb + connectedAndroidTest + backend all share thinker's loopback. This is
# the only topology where gradle's adb reaches the emulator and the on-emulator
# client reaches the backend without WAN/adb tunnels (the Containers submodule's
# pkg/emulator/containerized.go drives LOCAL podman only — it has no SSH path —
# so the emulator MUST be local to whichever host runs the matrix; that host is
# thinker, with /dev/kvm + 16 cores).
#
# Two functions only — thin glue over ssh + rsync, both keyed on the SAME
# identity that authenticates today (~/.ssh/id_ed25519, confirmed via ssh -G):
#   remote_sync_repo   rsync the repo -> thinker (excludes build/.git/recordings,
#                      INCLUDES the gitignored .env so the Go backend's
#                      LAVA_AUTH_* + tracker creds reach thinker).
#   remote_run "<cmd>" run a command on thinker, cwd = the synced repo.
#
# §6.H: no secret is ever echoed. The SSH key path is config; its contents are
#       never read or printed. .env is transferred over the encrypted SSH
#       channel, never logged.
# §6.U: no sudo/su. §6.J: set -euo pipefail; failures surface, never swallowed.
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"

# Load thinker coordinates from the operator .env (gitignored) with safe
# fallbacks. These are config, not secrets (§6.R).
if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source <(grep -E '^(LAVA_API_GO_REMOTE_HOST|LAVA_REMOTE_HOST_USER|LAVA_THINKER_QA_DIR)=' "$REPO_ROOT/.env" || true)
  set +a
fi

REMOTE_HOST="${LAVA_API_GO_REMOTE_HOST:-thinker.local}"
REMOTE_USER="${LAVA_REMOTE_HOST_USER:-milosvasic}"
REMOTE_KEY="${LAVA_REMOTE_HOST_KEY:-$HOME/.ssh/id_ed25519}"
REMOTE_REPO_DIR="${LAVA_THINKER_QA_DIR:-/home/${REMOTE_USER}/lava-qa}"
REMOTE="${REMOTE_USER}@${REMOTE_HOST}"

# Shared SSH options: BatchMode (key-only, never prompt), bounded connect,
# accept-new host key on first contact, persistent ControlMaster so the many
# remote_run calls in a matrix reuse one TCP/SSH session.
_REMOTE_CTRL_DIR="${TMPDIR:-/tmp}/lava-qa-ssh-ctrl"
_remote_ssh_opts() {
  mkdir -p "$_REMOTE_CTRL_DIR"
  printf '%s\0' \
    -o BatchMode=yes \
    -o ConnectTimeout=10 \
    -o StrictHostKeyChecking=accept-new \
    -o ServerAliveInterval=30 -o ServerAliveCountMax=10 \
    -o ControlMaster=auto \
    -o "ControlPath=${_REMOTE_CTRL_DIR}/%r@%h:%p" \
    -o ControlPersist=300 \
    -i "$REMOTE_KEY"
}

# remote_preflight: prove reachability + key auth + podman + /dev/kvm before any
# heavy work is dispatched. Returns non-zero (and explains) if thinker is unfit.
remote_preflight() {
  local -a opts; mapfile -d '' -t opts < <(_remote_ssh_opts)
  echo "[remote] preflight $REMOTE (key=$REMOTE_KEY)" >&2
  if ! ssh "${opts[@]}" "$REMOTE" 'true' 2>/dev/null; then
    echo "[remote] ERROR cannot SSH to $REMOTE with key auth ($REMOTE_KEY)" >&2
    return 1
  fi
  ssh "${opts[@]}" "$REMOTE" '
    set -e
    command -v podman >/dev/null || { echo "[remote] ERROR podman missing on host" >&2; exit 1; }
    command -v rsync  >/dev/null || { echo "[remote] ERROR rsync missing on host"  >&2; exit 1; }
    [ -e /dev/kvm ] || { echo "[remote] ERROR /dev/kvm absent on host" >&2; exit 1; }
    echo "[remote] OK podman=$(podman --version | awk "{print \$3}") kvm=present cores=$(nproc)"
  ' >&2
}

# remote_sync_repo: mirror the repo onto thinker. Excludes build artefacts,
# git metadata, and recorded media (regenerated on thinker); INCLUDES .env
# (gitignored locally but required on thinker for backend auth + tracker creds)
# because it is NOT in the exclude set. --delete keeps the remote tree a faithful
# mirror so stale files never poison a build.
remote_sync_repo() {
  local -a opts; mapfile -d '' -t opts < <(_remote_ssh_opts)
  echo "[remote] sync $REPO_ROOT -> $REMOTE:$REMOTE_REPO_DIR" >&2
  ssh "${opts[@]}" "$REMOTE" "mkdir -p '$REMOTE_REPO_DIR'"
  # Build the ssh transport string for rsync -e (same key + control socket).
  local ssh_e="ssh"
  local o
  for o in "${opts[@]}"; do ssh_e+=" $(printf '%q' "$o")"; done
  rsync -az --delete \
    -e "$ssh_e" \
    --exclude '.git/' \
    --exclude '**/.git' \
    --exclude '**/build/' \
    --exclude '**/.gradle/' \
    --exclude '.gradle/' \
    --exclude 'releases/' \
    --exclude '**/raw/' \
    --exclude 'recordings/' \
    --exclude '**/*.image.tar' \
    --exclude '.lava-ci-evidence/autonomous-qa/**/raw/' \
    "$REPO_ROOT/" "$REMOTE:$REMOTE_REPO_DIR/"
  echo "[remote] sync complete" >&2
}

# remote_run "<cmd>": execute <cmd> on thinker with cwd = the synced repo.
# Stdout/stderr stream straight back. Quote the whole command as one arg.
remote_run() {
  local cmd="$1"
  local -a opts; mapfile -d '' -t opts < <(_remote_ssh_opts)
  ssh "${opts[@]}" "$REMOTE" "cd '$REMOTE_REPO_DIR' && ${cmd}"
}

# remote_fetch <remote-rel-path> <local-dest>: pull curated evidence back.
remote_fetch() {
  local rel="$1" dest="$2"
  local -a opts; mapfile -d '' -t opts < <(_remote_ssh_opts)
  local ssh_e="ssh"
  local o
  for o in "${opts[@]}"; do ssh_e+=" $(printf '%q' "$o")"; done
  mkdir -p "$(dirname "$dest")"
  rsync -az -e "$ssh_e" "$REMOTE:$REMOTE_REPO_DIR/$rel" "$dest"
}
