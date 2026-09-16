#!/usr/bin/env bash
# =====================================================================================
# task-260915 · 分片 S-B · AC-19 基线回归
#
# AC-19 原文（基线回归那条）：
#   Task0805ExportBindingReportTest 回到 `Tests run: 23, Failures: 2`（**一条不多**；
#   那 2 条是改动前就红的既有失败 —— oldBundle_checksumStillValid[1][2]）
#
# 为什么要写死 23/2 而不是"≤18"：
#   - 放宽成"比 18 少就算好转"，等于把「修好了」和「修了一半」判成同一个结果；
#   - 23/2 是**改动前的实测基线**，不是理想值。多一条失败 = 本次新引入，必须归因，
#     🚫 不许算进"既有失败"里糊过去。
#
# 🚫 本脚本不改任何文件、不碰数据库、不 commit。只跑一个既有测试类并核对计数。
# 🚫 不许裸跑 ./mvnw test（test.md §4.5：一律 -Dtest= 限定）。
#
# 用法：bash dev-docs/task-260915-组件导出导入往返保真/实验/ac19-基线回归.sh
# =====================================================================================
set -uo pipefail

WT="/home/joii/project/cpq/.claude/worktrees/task-260915-export-fidelity"
BACKEND="$WT/cpq-backend"
EVID="$WT/dev-docs/task-260915-组件导出导入往返保真/证据"
OUT="$EVID/AC-19-基线回归-$(date +%y%m%d-%H%M%S).log"

mkdir -p "$EVID"
echo "=== AC-19 基线回归 $(date '+%F %T') ==="
echo "日志: $OUT"

( cd "$BACKEND" && ./mvnw test -Dtest=Task0805ExportBindingReportTest -DfailIfNoTests=false ) > "$OUT" 2>&1
RC=$?
echo "maven 退出码=$RC"

echo "--- Tests run 汇总行 ---"
/usr/bin/grep -a -E "Tests run:.*Task0805ExportBindingReportTest|^\[INFO\] Tests run:|^Tests run:" "$OUT" | tail -5

# 期望：该类 Tests run: 23, Failures: 2, Errors: 0, Skipped: 0
if /usr/bin/grep -aq "Tests run: 23, Failures: 2, Errors: 0, Skipped: 0" "$OUT"; then
  echo "✅ 基线回归通过：Tests run: 23, Failures: 2, Errors: 0, Skipped: 0（与改动前基线一致）"
  echo "--- 确认这 2 条就是既有的 oldBundle_checksumStillValid[1][2] ---"
  /usr/bin/grep -a -n "oldBundle_checksumStillValid" "$OUT" | head -10
  if /usr/bin/grep -aq "oldBundle_checksumStillValid" "$OUT"; then
    echo "✅ 失败项确实是既有的 oldBundle_checksumStillValid（不是本次新引入的别的用例）"
    exit 0
  fi
  echo "⚠️ 计数对上了，但失败项里没看到 oldBundle_checksumStillValid ——"
  echo "   数量对≠同一批，可能是「修好 2 条、新坏 2 条」。人工看 $OUT 再下结论。"
  exit 1
fi

echo "❌ 基线回归未通过。实际汇总见上；常见两种情况："
echo "   · Failures 明显多于 2（如 18）⇒ 老包 checksum 仍在误报，AC-19 的修法没生效"
echo "   · Failures 少于 2 ⇒ 好事，但说明基线本身变了，要重新确认基线数字再改本脚本"
exit 1
