#!/usr/bin/env bash
# ============================================================
# 规则遵守率测量报告 —— 汇总 .claude/hooks.log
#
#   用法：bash .claude/hooks/hooks-report.sh [项目根]
#
#   这份报告回答四个问题（都是客观计数，不靠回忆）：
#     1. 触发矩阵实际被调用了多少次、覆盖了哪些分册
#     2. §3.2 红线实际拦下了什么、有没有「被拒后绕路重试」的痕迹
#     3. 「完成」宣告里缺自检声明的比例 —— §6.1 的真实遗漏率
#     4. 会话开始注入是否每次都成功
# ============================================================
set -uo pipefail

ROOT="${1:-${CLAUDE_PROJECT_DIR:-$PWD}}"
LOG="$ROOT/.claude/hooks.log"

if [ ! -f "$LOG" ]; then
  echo "✗ 没有找到 $LOG"
  echo "  可能原因：hook 没生效（打开一次 /hooks 或重启）／未装 jq／还没跑过任务"
  exit 1
fi

total=$(wc -l < "$LOG")
first=$(head -1 "$LOG" | cut -f1)
last=$(tail -1 "$LOG" | cut -f1)

echo "════════════════════════════════════════════════"
echo " 规则遵守率测量报告"
echo " 项目：$ROOT"
echo " 区间：$first  →  $last"
echo " 日志条数：$total"
echo "════════════════════════════════════════════════"
echo

echo "── 1. 触发矩阵（inject-rules）──"
inj=$(awk -F'\t' '$2=="inject-rules"' "$LOG" | wc -l)
echo "总注入次数：$inj"
if [ "$inj" -gt 0 ]; then
  echo "按分册："
  awk -F'\t' '$2=="inject-rules"{print $3}' "$LOG" | tr ',' '\n' | sort | uniq -c | sort -rn | sed 's/^/  /'
  echo "触及文件数（去重）：$(awk -F'\t' '$2=="inject-rules"{print $4}' "$LOG" | sort -u | wc -l)"
fi
echo

echo "── 2. §3.2 红线（guard-redline）──"
d=$(awk -F'\t' '$2=="guard-redline" && $3=="deny"' "$LOG" | wc -l)
a=$(awk -F'\t' '$2=="guard-redline" && $3=="ask"'  "$LOG" | wc -l)
echo "deny $d 次 / ask $a 次"
if [ $((d+a)) -gt 0 ]; then
  echo "被拦的命令："
  awk -F'\t' '$2=="guard-redline"{printf "  [%s] %s\n",$3,$4}' "$LOG" | sort | uniq -c | sort -rn | head -20
  echo
  echo "⚠️ 重点看这里：同一意图连续多次被拦 = 模型在**绕路重试**，"
  echo "   说明它把 hook 当障碍物而不是当规则。这是最有价值的一个信号。"
fi
echo

echo "── 3. §6.1 自检声明自发率（check-selfcheck）──"
p=$(awk -F'\t' '$2=="check-selfcheck" && $3=="pass"'  "$LOG" | wc -l)
b=$(awk -F'\t' '$2=="check-selfcheck" && $3=="BLOCK"' "$LOG" | wc -l)
a=$(awk -F'\t' '$2=="check-selfcheck" && $3=="ask-back"' "$LOG" | wc -l)
n=$(awk -F'\t' '$2=="check-selfcheck" && ($3=="no-code" || $3=="no-claim")' "$LOG" | wc -l)
d=$(awk -F'\t' '$2=="check-selfcheck" && $3=="diag"' "$LOG" | wc -l)
tot=$((p+b))
echo "交付型轮次 $tot 次（动了实现代码或宣告了完成，且不是回来提问的）"
echo "  另有：中途请示 $a 次 / 与本 hook 无关 $n 次"
[ "$d" -gt 0 ] && echo "  ⚠️ 诊断记录 $d 次 —— hook 取不到助手文本，这部分数据缺失："
[ "$d" -gt 0 ] && awk -F'\t' '$2=="check-selfcheck" && $3=="diag"{print "      "$4}' "$LOG" | sort | uniq -c
if [ "$tot" -gt 0 ]; then
  echo "  自带自检声明：$p 次"
  echo "  缺声明被打回：$b 次"
  echo "  → 自检声明自发率：$(( p * 100 / tot ))%   ← **这是 §6.1 的真实遵守率**"
  echo
  echo "  📌 分母是「触碰实现代码的轮次」，不是「说了『完成』二字的轮次」——"
  echo "     措辞可以无穷变化（已完成→已结案→改完了），改动是客观的。"
else
  echo "  （尚无交付型轮次，数据不足）"
fi
echo

echo "── 4. 会话开始注入（session-brief）──"
s=$(awk -F'\t' '$2=="session-brief"' "$LOG" | wc -l)
echo "成功注入 $s 次（≈ 会话数）"
awk -F'\t' '$2=="session-brief" && $4 ~ /INDEX=missing/' "$LOG" | wc -l | sed 's/^/  其中 INDEX.md 缺失：/'
echo

echo "════════════════════════════════════════════════"
echo " 把上面这份输出，连同以下内容一起发出来："
echo "   · dev-docs/INDEX.md 和 docs/RECORD.md 全文"
echo "   · git log --oneline（看提交纪律和分支）"
echo "   · 任意一个任务目录的 ls -R（看六件套齐不齐）"
echo "════════════════════════════════════════════════"
