#!/usr/bin/env bash
#
# scripts/gen-cert.sh — generate a self-signed TLS cert/key pair for the
# lava-api-go listener. No-op when both files already exist.
#
# Usage:
#   scripts/gen-cert.sh             # generate if missing
#   scripts/gen-cert.sh --force     # regenerate even if existing
#
# Output:
#   lava-api-go/docker/tls/server.crt
#   lava-api-go/docker/tls/server.key
#
# The cert is RSA-2048, valid 365 days, with SAN entries for localhost,
# 127.0.0.1, ::1, and the host's primary LAN IP (auto-detected). Suitable
# for LAN deployment per the SP-2 design doc §8.1; rotate manually or
# replace with an ACME-issued cert for non-LAN deployments.

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TLS_DIR="$ROOT/docker/tls"
CRT="$TLS_DIR/server.crt"
KEY="$TLS_DIR/server.key"

FORCE=false
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=true ;;
    -h|--help) sed -n '/^# Usage/,/^# *$/p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;36m[gen-cert]\033[0m %s\n' "$*"; }

if [ -s "$CRT" ] && [ -s "$KEY" ] && [ "$FORCE" = false ]; then
  log "TLS material already present at $TLS_DIR (--force to regenerate)"
  exit 0
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "[gen-cert] openssl not found in PATH; install it or provide certs manually" >&2
  exit 1
fi

mkdir -p "$TLS_DIR"

# Auto-detect the primary LAN IPv4 (ip route is Linux-specific; fall back to
# 127.0.0.1 only on platforms without it).
LAN_IP=""
if command -v ip >/dev/null 2>&1; then
  LAN_IP="$(ip -4 -o route get 1 2>/dev/null | awk '{for (i=1; i<=NF; i++) if ($i=="src") { print $(i+1); exit }}')" || true
fi

SAN_ENTRIES="DNS:localhost,IP:127.0.0.1,IP:::1"
if [ -n "$LAN_IP" ] && [ "$LAN_IP" != "127.0.0.1" ]; then
  SAN_ENTRIES="$SAN_ENTRIES,IP:$LAN_IP"
fi

CONFIG="$(mktemp)"
trap 'rm -f "$CONFIG"' EXIT

cat > "$CONFIG" <<EOF
[req]
distinguished_name = dn
req_extensions     = v3_req
prompt             = no

[dn]
CN = lava-api-go.local
O  = Lava
OU = SP-2 LAN deployment

[v3_req]
basicConstraints = CA:FALSE
keyUsage         = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName   = $SAN_ENTRIES
EOF

log "generating self-signed cert (RSA-2048, 365d, SAN=$SAN_ENTRIES)"
openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout "$KEY" \
  -out "$CRT" \
  -days 365 \
  -config "$CONFIG" \
  -extensions v3_req >/dev/null 2>&1

chmod 600 "$KEY"
chmod 644 "$CRT"

log "wrote $CRT"
log "wrote $KEY"
