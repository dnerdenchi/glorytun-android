#!/usr/bin/env bash
set -Eeuo pipefail

CONFIG_PATH="/etc/mqvpn/server.conf"
SERVICE_NAME="mqvpn-server.service"
LOCK_PATH="/run/lock/mqvpn-feature-enable.lock"

if [[ "${EUID}" -ne 0 ]]; then
  echo "このスクリプトは sudo で実行してください。" >&2
  exit 1
fi

for command_name in python3 systemctl ss sha256sum flock; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "必要なコマンドがありません: ${command_name}" >&2
    exit 1
  fi
done

if [[ ! -f "${CONFIG_PATH}" ]]; then
  echo "設定ファイルが見つかりません: ${CONFIG_PATH}" >&2
  exit 1
fi

exec 9>"${LOCK_PATH}"
if ! flock -n 9; then
  echo "別の設定更新処理が実行中です。" >&2
  exit 1
fi

umask 077
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_path="${CONFIG_PATH}.bak.${timestamp}"
temp_path="$(mktemp "${CONFIG_PATH}.new.XXXXXX")"
applied=0

cleanup() {
  rm -f -- "${temp_path}"
}
trap cleanup EXIT

restore_backup() {
  if [[ "${applied}" -eq 1 ]]; then
    echo "mqvpn の起動確認に失敗したため、元の設定へ戻します。" >&2
    cp --preserve=mode,ownership,timestamps -- "${backup_path}" "${CONFIG_PATH}"
    systemctl reset-failed "${SERVICE_NAME}" || true
    systemctl restart "${SERVICE_NAME}" || true
  fi
}

python3 - "${CONFIG_PATH}" "${temp_path}" <<'PY'
from __future__ import annotations

import re
import sys
from pathlib import Path

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
header_pattern = re.compile(r"^\s*\[([^\]]+)\]")

lines = source.read_text(encoding="utf-8").splitlines(keepends=True)
blocks: list[tuple[str | None, list[str]]] = []
current_name: str | None = None
current_lines: list[str] = []

for line in lines:
    match = header_pattern.match(line)
    if match:
        blocks.append((current_name, current_lines))
        current_name = match.group(1).strip()
        current_lines = [line]
    else:
        current_lines.append(line)
blocks.append((current_name, current_lines))


def set_key(block_lines: list[str], key: str, value: str) -> list[str]:
    key_pattern = re.compile(rf"^(\s*){re.escape(key)}\s*=.*$", re.IGNORECASE)
    output: list[str] = []
    replaced = False
    for line in block_lines:
        if key_pattern.match(line.rstrip("\r\n")):
            if not replaced:
                indent = key_pattern.match(line.rstrip("\r\n")).group(1)
                output.append(f"{indent}{key} = {value}\n")
                replaced = True
        else:
            output.append(line)
    if not replaced:
        if output and not output[-1].endswith(("\n", "\r")):
            output[-1] += "\n"
        output.append(f"{key} = {value}\n")
    return output


def get_key(block_lines: list[str], key: str) -> str | None:
    key_pattern = re.compile(
        rf"^\s*{re.escape(key)}\s*=\s*([^#;\r\n]+)", re.IGNORECASE
    )
    for line in block_lines[1:]:
        match = key_pattern.match(line)
        if match:
            return match.group(1).strip()
    return None


found_multipath = False
found_reorder = False
found_hybrid = False
found_udp_443_rule = False
updated: list[tuple[str | None, list[str]]] = []

for name, block_lines in blocks:
    normalized = name.lower() if name else ""
    if normalized == "multipath":
        found_multipath = True
        block_lines = set_key(block_lines, "Scheduler", "wlb")
    elif normalized == "reorder":
        found_reorder = True
        block_lines = set_key(block_lines, "Enabled", "on")
    elif normalized == "hybrid":
        found_hybrid = True
        block_lines = set_key(block_lines, "Enabled", "true")
        block_lines = set_key(block_lines, "TcpMaxFlows", "256")
        block_lines = set_key(block_lines, "TcpIdleTimeoutSec", "300")
        block_lines = set_key(block_lines, "TcpConnectTimeoutSec", "10")
        block_lines = set_key(block_lines, "TcpMaxGlobalFlows", "4096")
    elif normalized == "reorderrule":
        proto = (get_key(block_lines, "Proto") or "udp").lower()
        port = get_key(block_lines, "Port")
        if proto == "udp" and port == "443":
            found_udp_443_rule = True
            block_lines = set_key(block_lines, "Proto", "udp")
            block_lines = set_key(block_lines, "Port", "443")
            block_lines = set_key(block_lines, "Profile", "cellular_bond")
    updated.append((name, block_lines))


def append_section(name: str, values: list[tuple[str, str]]) -> None:
    section_lines = [f"\n[{name}]\n"]
    section_lines.extend(f"{key} = {value}\n" for key, value in values)
    updated.append((name, section_lines))


if not found_multipath:
    append_section("Multipath", [("Scheduler", "wlb")])
if not found_reorder:
    append_section("Reorder", [("Enabled", "on")])
if not found_udp_443_rule:
    append_section(
        "ReorderRule",
        [("Proto", "udp"), ("Port", "443"), ("Profile", "cellular_bond")],
    )
if not found_hybrid:
    append_section(
        "Hybrid",
        [
            ("Enabled", "true"),
            ("TcpMaxFlows", "256"),
            ("TcpIdleTimeoutSec", "300"),
            ("TcpConnectTimeoutSec", "10"),
            ("TcpMaxGlobalFlows", "4096"),
        ],
    )

rendered = "".join(line for _, block_lines in updated for line in block_lines)
destination.write_text(rendered, encoding="utf-8")
PY

if [[ ! -s "${temp_path}" ]]; then
  echo "生成された設定が空です。変更は適用していません。" >&2
  exit 1
fi

cp --preserve=mode,ownership,timestamps -- "${CONFIG_PATH}" "${backup_path}"
applied=1
if ! cat -- "${temp_path}" >"${CONFIG_PATH}"; then
  restore_backup
  exit 1
fi

systemctl reset-failed "${SERVICE_NAME}"
if ! systemctl restart "${SERVICE_NAME}"; then
  restore_backup
  exit 1
fi

healthy=0
for _ in {1..15}; do
  if systemctl is-active --quiet "${SERVICE_NAME}" \
    && ss -H -lun 'sport = :443' | grep -q .; then
    healthy=1
    break
  fi
  sleep 1
done

if [[ "${healthy}" -ne 1 ]]; then
  restore_backup
  exit 1
fi

config_sha256="$(sha256sum "${CONFIG_PATH}" | awk '{print $1}')"
report_user="${SUDO_USER:-ubuntu}"
report_home="$(getent passwd "${report_user}" | awk -F: '{print $6}')"
if [[ -n "${report_home}" && -d "${report_home}" ]]; then
  report_path="${report_home}/mqvpn-feature-enable-result.txt"
  report_group="$(id -gn "${report_user}")"
  if cat >"${report_path}" <<EOF
status=success
applied_at_utc=${timestamp}
mqvpn_version=$(mqvpn --version 2>&1 | head -n 1)
service=active
udp_443=listening
scheduler=wlb
hybrid=enabled
reorder_udp_443=cellular_bond
config_sha256=${config_sha256}
backup=${backup_path}
EOF
  then
    chown "${report_user}":"${report_group}" "${report_path}" || true
    chmod 600 "${report_path}" || true
  else
    echo "結果ファイルの作成に失敗しましたが、mqvpn の設定と起動は正常です。" >&2
  fi
fi

echo "mqvpn の設定を更新しました。"
echo "  Scheduler: wlb"
echo "  Hybrid: enabled"
echo "  Reorder: UDP 443 / cellular_bond"
echo "  Service: active"
echo "  UDP 443: listening"
echo "  Backup: ${backup_path}"
