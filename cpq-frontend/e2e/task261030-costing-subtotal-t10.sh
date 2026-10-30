#!/usr/bin/env bash
# task-261030 · T-10 / AC-9 回归与可构建：逐条执行并记录命令、原始输出、退出码。
#
# AC-9 原文：前端类型检查、本任务定向 Vitest、task-261028 与角色精度的既有测试、生产构建、本任务 Playwright
# 退出码均为 0；E2E 中每个目标页面唯一命中 1 张目标卡片，控制台 error/warn 为 0，无框架错误遮罩。
# 已知 quotation-flow.spec.ts 因 DEC-0008 不作为通过证据（本脚本不跑它）。
#
# 用法（WSL，在本 worktree 的 cpq-frontend 下）：
#   bash e2e/task261030-costing-subtotal-t10.sh [证据目录]
# 前置：S-FLOW 的 A/B 基线已由主线在 master 检出上以 T261030_TARGET=baseline 跑过（否则 flow 的 A/B 用例按设计硬失败）。
# ⚠️ testing.md §4.2.5：执行时不得有实现代理在同一 worktree 并发构建。
# 不用 set -e：每条命令都要跑完并记录退出码，最后汇总。
set -u
export PATH="$HOME/.local/node/bin:$PATH"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND="$(cd "$HERE/.." && pwd)"
TASK_DIR="$FRONTEND/../dev-docs/task-260801-公式计算精度优化/task-261030-核价卡片小计展示调整"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${1:-$TASK_DIR/证据/T-10-$STAMP}"
mkdir -p "$OUT"
cd "$FRONTEND" || exit 99
SUMMARY="$OUT/T-10-summary.txt"
: > "$SUMMARY"
echo "worktree=$FRONTEND head=$(git rev-parse HEAD 2>/dev/null) at=$(date -Is)" | tee -a "$SUMMARY"

run() {
  local name="$1"; shift
  local log="$OUT/$name.txt"
  echo "\$ $*" > "$log"
  "$@" >> "$log" 2>&1
  local code=$?
  printf '%-28s exit=%s  %s\n' "$name" "$code" "$*" | tee -a "$SUMMARY"
  return 0
}

run tsc npx tsc -b
run vitest-task261030 npx vitest run \
  src/pages/quotation/task261030-costing-subtotal.test.tsx \
  src/pages/quotation/task261030-costing-subtotal-flow.test.tsx
run vitest-regression npx vitest run \
  src/pages/quotation/task261028-vat-subtotal.test.tsx \
  src/utils/task261027RoleDisplayPrecision.test.ts \
  src/pages/quotation/components/ComponentCell.readonlyPrecision.test.tsx
run build npm run build
run playwright-unit npx playwright test -c e2e/task261030-costing-subtotal-unit.config.ts
run playwright-flow npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts

# 防「命令写错 → 全程空输出 → 看起来像全通过」：每份输出必须非空，且 vitest / playwright 的输出里要有用例计数。
for f in "$OUT"/*.txt; do
  [ "$f" = "$SUMMARY" ] && continue
  lines=$(wc -l < "$f")
  [ "$lines" -le 1 ] && echo "EMPTY-OUTPUT $(basename "$f")" | tee -a "$SUMMARY"
done
grep -hE "Tests +[0-9]+|[0-9]+ passed|[0-9]+ failed" "$OUT"/vitest-*.txt "$OUT"/playwright-*.txt 2>/dev/null | tee -a "$SUMMARY"
echo "evidence=$OUT" | tee -a "$SUMMARY"
