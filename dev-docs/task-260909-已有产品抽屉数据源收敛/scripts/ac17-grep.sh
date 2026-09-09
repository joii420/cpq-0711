#!/usr/bin/env bash
# AC-17 · 注释与实际数据源一致：两个前端文件不得再声称数据来自 material_customer_map
#
# 🚫 用 grep -c（只出计数），不用 grep -n —— existingProduct.ts 在测试代理的禁读清单上，
#    本脚本只取"命中几次"这个可观测量，不打印文件内容。
# ⚠️ 本环境 grep 是 ugrep 别名，会把中文多的源文件静默判为二进制返空 ⇒ 必须用 /usr/bin/grep -a
set -euo pipefail
cd "$(dirname "$0")/../../.."   # → worktree 根

FILES=(
  "cpq-frontend/src/services/quotationService.ts"
  "cpq-frontend/src/types/existingProduct.ts"
)
FAIL=0
for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "❌ 文件不存在：$f"; FAIL=1; continue; }
  N=$(/usr/bin/grep -ac "material_customer_map" "$f" || true)
  echo "$f → material_customer_map 命中 ${N} 次（期望 0）"
  [ "$N" = "0" ] || FAIL=1
done

echo "=== 证伪对照：同一手法查一个必然存在的串，应 > 0 ==="
POS=$(/usr/bin/grep -ac "export" "cpq-frontend/src/services/quotationService.ts" || true)
echo "quotationService.ts → 'export' 命中 ${POS} 次"
[ "$POS" != "0" ] || { echo "❌ 证伪失败：grep 手法本身抓不到东西（可能被 ugrep 判为二进制），本条是空验证"; exit 1; }

[ "$FAIL" = "0" ] && echo "✅ AC-17 通过" || { echo "❌ AC-17 失败"; exit 1; }
