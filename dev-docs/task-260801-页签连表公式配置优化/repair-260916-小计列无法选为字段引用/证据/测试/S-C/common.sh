#!/usr/bin/env bash
# S-C 片公共环境。所有脚本 source 本文件。只连一次性库 cpq_db_rp0916c。
set -uo pipefail
export WT=/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix
export T="$WT/dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用"
export S="$T/证据/测试/S-C"
export PGPASSWORD=joii5231
export DBHOST=10.177.152.12
export DBNAME=cpq_db_rp0916c
export BE_PORT=8293 FE_PORT=5293
export BACKEND="http://localhost:$BE_PORT"
PSQL=(psql -h "$DBHOST" -U postgres -d "$DBNAME" -v ON_ERROR_STOP=1 -At -F '|')
q() { "${PSQL[@]}" -c "$1"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
fail() { echo "❌ FAIL: $*"; FAILS=$((${FAILS:-0}+1)); }
pass() { echo "✅ PASS: $*"; }
# 身份守卫：库注释必须以约定前缀开头，否则立即退出（不做任何后续动作）
guard_db() {
  local c; c=$(q "SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()")
  case "$c" in
    "repair-260916-subtotal-suffix 一次性库"*) echo "[guard] $(now) 库身份 OK: $DBNAME / $c";;
    *) echo "[guard] 库身份不符，停止: '$c'"; exit 99;;
  esac
}
# 工作区守卫：命令必须在 worktree 内执行
guard_wt() { case "$(pwd)" in "$WT"*) :;; *) cd "$WT" || exit 98;; esac; }
