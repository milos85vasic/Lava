#!/usr/bin/env bash
# scripts/systemd-status.sh — convenience wrapper reporting the lava-api-go
# systemd --user unit's state plus the underlying container health, so a
# single command answers "is the service actually up" rather than just
# "is the unit active" (a systemd unit can be RemainAfterExit=active while
# the container inside it crash-looped — see CLAUDE.md §6.B).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=== systemd --user unit ==="
systemctl --user status lava-api.service --no-pager 2>&1 || true

echo ""
echo "=== linger (required for boot-time start without login) ==="
loginctl show-user "$(id -un)" --property=Linger 2>&1

echo ""
echo "=== container-level health (the real user-facing signal, §6.B) ==="
"$SCRIPT_DIR/tools/lava-containers/bin/lava-containers" -cmd=status 2>&1 || true
