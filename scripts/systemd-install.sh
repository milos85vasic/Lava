#!/usr/bin/env bash
# scripts/systemd-install.sh — installs the lava-api-go container service as a
# systemd --user unit, so it starts on login and (via `loginctl enable-linger`)
# on boot even without an active login session. No sudo/su anywhere — every
# step operates in the invoking user's own systemd --user + XDG_CONFIG_HOME
# scope, per this project's §6.U No-sudo/su Mandate.
#
# What this does:
#   1. Renders systemd/user/lava-api.service.template -> a real unit file
#      (substitutes __LAVA_REPO_ROOT__ with this repo's absolute path).
#   2. Copies it to ~/.config/systemd/user/lava-api.service.
#   3. systemctl --user daemon-reload
#   4. systemctl --user enable lava-api.service
#   5. Verifies (and if needed enables) `loginctl enable-linger` for the
#      current user — this is a read-only/config-safe logind operation, not
#      in the Forbidden Command List (suspend/hibernate/poweroff/etc.); it
#      is what makes a --user unit survive to boot time without a live login
#      session, entirely without root.
#
# Usage:
#   ./scripts/systemd-install.sh            # install + enable (does not start)
#   ./scripts/systemd-install.sh --start    # install + enable + start now
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

TEMPLATE="systemd/user/lava-api.service.template"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_FILE="$UNIT_DIR/lava-api.service"

if [ ! -f "$TEMPLATE" ]; then
    echo "ERROR: $TEMPLATE not found (run from repo root)" >&2
    exit 1
fi

mkdir -p "$UNIT_DIR"
sed "s#__LAVA_REPO_ROOT__#$SCRIPT_DIR#g" "$TEMPLATE" > "$UNIT_FILE"
echo "==> Installed unit: $UNIT_FILE"

systemctl --user daemon-reload
echo "==> systemctl --user daemon-reload done"

systemctl --user enable lava-api.service
echo "==> systemctl --user enable lava-api.service done"

LINGER="$(loginctl show-user "$(id -un)" --property=Linger 2>/dev/null | cut -d= -f2)"
if [ "$LINGER" != "yes" ]; then
    echo "==> Enabling linger for $(id -un) (no sudo — this is the calling user's own account)"
    loginctl enable-linger
else
    echo "==> Linger already enabled for $(id -un) — unit will start at boot without a login session"
fi

if [ "${1:-}" = "--start" ]; then
    systemctl --user start lava-api.service
    echo "==> Started. Check status: systemctl --user status lava-api.service"
else
    echo "==> Installed + enabled, not started. Start with:"
    echo "      systemctl --user start lava-api.service"
    echo "    or re-run this script with --start."
fi
