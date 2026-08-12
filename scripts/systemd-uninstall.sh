#!/usr/bin/env bash
# scripts/systemd-uninstall.sh — reverses scripts/systemd-install.sh: stops
# and disables the lava-api-go systemd --user unit and removes the rendered
# unit file. Does NOT disable linger (that is a user-account-wide setting
# that may be relied on by other --user units); disable it manually with
# `loginctl disable-linger` if you want it off.
set -euo pipefail

UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_FILE="$UNIT_DIR/lava-api.service"

if systemctl --user is-active --quiet lava-api.service 2>/dev/null; then
    systemctl --user stop lava-api.service
    echo "==> Stopped lava-api.service"
fi

if systemctl --user is-enabled --quiet lava-api.service 2>/dev/null; then
    systemctl --user disable lava-api.service
    echo "==> Disabled lava-api.service"
fi

if [ -f "$UNIT_FILE" ]; then
    rm -f "$UNIT_FILE"
    echo "==> Removed $UNIT_FILE"
fi

systemctl --user daemon-reload
echo "==> systemctl --user daemon-reload done"
