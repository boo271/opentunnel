#!/usr/bin/env bash
# Installs sing-box with a VLESS + REALITY inbound on TCP 443.
# Tested on Ubuntu 22.04/24.04 and Debian 12, as root. Re-running rotates credentials.
#
# Usage:
#   SNI=m.facebook.com bash setup.sh
#   SNI=www.microsoft.com PORT=443 bash setup.sh
#   SING_BOX_VERSION=1.14.4 SNI=m.facebook.com bash setup.sh

set -euo pipefail

SNI="${SNI:-m.facebook.com}"
PORT="${PORT:-443}"
NODE_NAME="${NODE_NAME:-OpenTunnel}"
SING_BOX_VERSION="${SING_BOX_VERSION:-}"
CONFIG_DIR="/etc/sing-box"
CONFIG_FILE="${CONFIG_DIR}/config.json"
BIN="/usr/local/bin/sing-box"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()   { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[[ "${EUID}" -eq 0 ]] || die "Run this script as root (sudo -i, then bash setup.sh)."

for tool in curl tar openssl; do
    command -v "${tool}" >/dev/null 2>&1 || die "Missing '${tool}'. Install it first (apt install ${tool})."
done

if [[ ! "${PORT}" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
    die "PORT must be a number between 1 and 65535."
fi

case "$(uname -m)" in
    x86_64|amd64)  ARCH="amd64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    armv7l|armv7)  ARCH="armv7" ;;
    *) die "Unsupported architecture: $(uname -m)" ;;
esac

# sing-box binary
if [[ -z "${SING_BOX_VERSION}" ]]; then
    info "Looking up the latest sing-box release…"
    SING_BOX_VERSION="$(curl -fsSL --max-time 15 \
        -H 'Accept: application/vnd.github+json' \
        https://api.github.com/repos/SagerNet/sing-box/releases/latest \
        | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' \
        | head -n1 || true)"
fi
# GitHub's API is rate-limited; fall back to the version the Android app links.
SING_BOX_VERSION="${SING_BOX_VERSION:-1.14.4}"

info "Installing sing-box ${SING_BOX_VERSION} (${ARCH})…"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
URL="https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-linux-${ARCH}.tar.gz"
if ! curl -fsSL --max-time 120 "${URL}" -o "${TMP}/sing-box.tar.gz"; then
    die "Could not download ${URL}. Check the version and the server's internet access."
fi
tar -xzf "${TMP}/sing-box.tar.gz" -C "${TMP}"
install -m 0755 "${TMP}/sing-box-${SING_BOX_VERSION}-linux-${ARCH}/sing-box" "${BIN}"

"${BIN}" version

# credentials and configuration
info "Generating VLESS UUID, REALITY keypair and short id…"
UUID="$("${BIN}" generate uuid)"
KEYPAIR="$("${BIN}" generate reality-keypair)"
PRIVATE_KEY="$(printf '%s\n' "${KEYPAIR}" | awk '/PrivateKey/ {print $2; exit}')"
PUBLIC_KEY="$(printf '%s\n' "${KEYPAIR}" | awk '/PublicKey/ {print $2; exit}')"
SHORT_ID="$(openssl rand -hex 8)"
[[ -n "${UUID}" && -n "${PRIVATE_KEY}" && -n "${PUBLIC_KEY}" ]] \
    || die "Could not generate credentials."

mkdir -p "${CONFIG_DIR}"
chmod 700 "${CONFIG_DIR}"

info "Writing ${CONFIG_FILE} (SNI ${SNI}, TCP ${PORT})…"
cat > "${CONFIG_FILE}" <<EOF
{
  "log": {
    "level": "info",
    "timestamp": true
  },
  "inbounds": [
    {
      "type": "vless",
      "tag": "vless-in",
      "listen": "::",
      "listen_port": ${PORT},
      "users": [
        {
          "name": "opentunnel",
          "uuid": "${UUID}",
          "flow": ""
        }
      ],
      "tls": {
        "enabled": true,
        "server_name": "${SNI}",
        "reality": {
          "enabled": true,
          "handshake": {
            "server": "${SNI}",
            "server_port": 443
          },
          "private_key": "${PRIVATE_KEY}",
          "short_id": [
            "${SHORT_ID}"
          ]
        }
      }
    }
  ],
  "outbounds": [
    {
      "type": "direct",
      "tag": "direct"
    }
  ]
}
EOF
chmod 600 "${CONFIG_FILE}"

info "Validating the configuration…"
"${BIN}" check -c "${CONFIG_FILE}"

# systemd service
info "Installing the systemd unit…"
cat > /etc/systemd/system/sing-box.service <<'EOF'
[Unit]
Description=sing-box service
Documentation=https://sing-box.sagernet.org
After=network.target nss-lookup.target

[Service]
Type=simple
ExecStart=/usr/local/bin/sing-box run -c /etc/sing-box/config.json
Restart=on-failure
RestartSec=5
LimitNOFILE=infinity

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable sing-box >/dev/null 2>&1 || true
systemctl restart sing-box
sleep 1
systemctl --no-pager --lines=15 status sing-box || true

# firewall (only the port we need)
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    info "Opening TCP ${PORT} in ufw…"
    ufw allow "${PORT}/tcp" >/dev/null
fi
if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
    info "Opening TCP ${PORT} in firewalld…"
    firewall-cmd --permanent --add-port="${PORT}/tcp" >/dev/null
    firewall-cmd --reload >/dev/null
fi
warn "Also allow TCP ${PORT} in your cloud provider's security group / VCN list."

# client details
SERVER_IP="$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)"
if [[ -z "${SERVER_IP}" ]]; then
    SERVER_IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
fi
[[ -n "${SERVER_IP}" ]] || SERVER_IP="YOUR_SERVER_IP"

LINK="vless://${UUID}@${SERVER_IP}:${PORT}?encryption=none&flow=&security=reality&sni=${SNI}&fp=chrome&pbk=${PUBLIC_KEY}&sid=${SHORT_ID}&type=tcp#${NODE_NAME}"

cat <<EOF

$(printf '\033[1;32mDone.\033[0m')  sing-box is running and starts on boot.

Client fields for OpenTunnel -> VLESS + REALITY:
  Server host / IP : ${SERVER_IP}
  Port             : ${PORT}
  UUID             : ${UUID}
  SNI / server name: ${SNI}
  REALITY pub key  : ${PUBLIC_KEY}
  REALITY short id : ${SHORT_ID}
  uTLS fingerprint : chrome
  Flow             : (leave empty)

Or paste this link into the app's "vless:// link" field:

  ${LINK}

Checks on the server:
  systemctl status sing-box
  journalctl -u sing-box -f

Keep ${CONFIG_FILE} private: it contains the REALITY private key.
Re-running this script rotates all credentials.
EOF
