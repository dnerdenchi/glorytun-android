#!/usr/bin/env bash
set -euo pipefail

if [[ "$EUID" -ne 0 ]]; then
  echo "root で実行してください: sudo $0"
  exit 1
fi

source_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
install_dir="/opt/pairbond-relay"

if ! id -u mqvpn >/dev/null 2>&1; then
  echo "mqvpn ユーザーが見つかりません。mqvpn サーバーを先にセットアップしてください。"
  exit 1
fi
if [[ ! -r /etc/mqvpn/server.conf ]]; then
  echo "/etc/mqvpn/server.conf を読み取れません。"
  exit 1
fi

install -d -o root -g root -m 0755 "$install_dir"
install -o root -g root -m 0755 "$source_dir/pairbond_relay.py" "$install_dir/pairbond_relay.py"
install -o root -g root -m 0644 "$source_dir/pairbond-relay.service" /etc/systemd/system/pairbond-relay.service

systemctl daemon-reload
systemctl enable --now pairbond-relay.service

echo "PairBond relay を起動しました。TCP 443 を firewall で許可してください。"
