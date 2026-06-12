#!/usr/bin/env bash
# docker-stop.sh — 停止并移除 CPQ 容器（镜像保留，离线安全）。
#
#   ./docker-stop.sh            # 停止并删除容器
#   ./docker-stop.sh --volumes  # 同时删除匿名卷（本应用无持久卷，一般用不到）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

COMPOSE_FILE="docker-compose.offline.yml"
[ -f "$COMPOSE_FILE" ] || { echo "缺少 $COMPOSE_FILE，请在离线包目录里运行" >&2; exit 1; }

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "未找到 docker compose / docker-compose" >&2; exit 1
fi

EXTRA=()
[ "${1:-}" = "--volumes" ] && EXTRA+=(--volumes)

"${DC[@]}" -f "$COMPOSE_FILE" down "${EXTRA[@]}"
echo "已停止并移除容器（cpq:latest 镜像保留）。"
