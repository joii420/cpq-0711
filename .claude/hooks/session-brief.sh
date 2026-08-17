#!/usr/bin/env bash
# ============================================================
# Hook 3/4 · SessionStart —— 注入「当前项目态势」，让会话开始动作 2~4 步不再靠自觉
#
#   落地 CLAUDE.md「会话开始时的动作顺序」第 2~4 步：
#     读 RECORD.md / 读 INDEX.md 当前项目态势 / 读 BACKLOG.md 待开发条目
#   这三步是只读的、每会话一次，正好适合 hook 直接喂进去 —— 省掉 3 轮工具调用，
#   而且**不会因为模型「觉得这次不用看」而被跳过**。
#
#   同时兜住两个门：§1 仍是「待探测」→ 提示先走 bootstrap；
#                   INDEX 缺失 → 提示按 bootstrap.md §2 建骨架。
#   永不阻断。
# ============================================================
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
out=""

# ---- 门 0：项目是否还没初始化 ----
# 🚨 只在 §1 段内找「待探测」，不能全文 grep ——
#    「待探测」这三个字在规则正文里本来就出现好几次（§0 的触发条件、§2.2 触发矩阵那一行），
#    全文 grep 会让**已经初始化完的项目每次开会话都被提示去初始化**。实测被误报。
SEC1=$(awk '/^# 1\. 项目速览/{f=1;next} f&&/^# /{exit} f' "$ROOT/CLAUDE.md" 2>/dev/null)
if [ -f "$ROOT/CLAUDE.md" ] && printf '%s' "$SEC1" | grep -q '待探测'; then
  out="${out}
🚨 CLAUDE.md §1 仍带「待探测」字样 → 按「会话开始时的动作顺序」第 1 步，
**先执行 docs/rules/bootstrap.md 的初始化（只探测和建骨架，不动代码），再执行用户的任务。**
"
fi

# ---- 第 3 步：INDEX.md「当前项目态势」----
IDX="$ROOT/dev-docs/INDEX.md"
if [ -f "$IDX" ]; then
  situ=$(awk '/^## 0\. 当前项目态势/{f=1} f&&/^## /&&!/^## 0\. 当前项目态势/{exit} f' "$IDX")
  [ -n "$situ" ] && out="${out}
── dev-docs/INDEX.md · 当前项目态势（避免撞车；过期的态势表比没有更危险）──
${situ}
"
else
  out="${out}
⚠️ 未找到 dev-docs/INDEX.md —— 按 docs/rules/bootstrap.md §2 建骨架，不要另起炉灶新建同类文件。
"
fi

# ---- 第 4 步：BACKLOG 待开发条目（§7 规则二）----
BL="$ROOT/docs/BACKLOG.md"
if [ -f "$BL" ]; then
  todo=$(grep -nE '^\s*- \[ \]' "$BL" | head -20 || true)
  n=$(grep -cE '^\s*- \[ \]' "$BL" || echo 0)
  done_n=$(grep -cE '^\s*- \[x\]' "$BL" || echo 0)
  if [ "$n" -gt 0 ]; then
    out="${out}
── docs/BACKLOG.md · 待开发 ${n} 条 / 已完成 ${done_n} 条（§7 规则二：判断本次任务是否与之相关并告知用户）──
${todo}
"
  fi
fi

# ---- 第 2 步：RECORD.md 最近记录（历史上下文与已知问题）----
REC="$ROOT/docs/RECORD.md"
if [ -f "$REC" ]; then
  recent=$(grep -E '^\s*[-*]?\s*\[[0-9]' "$REC" | tail -8 || true)
  [ -n "$recent" ] && out="${out}
── docs/RECORD.md · 最近 8 条（完整历史仍需按需自行读取）──
${recent}
"
fi

[ -n "$out" ] || exit 0

printf '%s\tsession-brief\tinjected\tINDEX=%s BACKLOG_todo=%s\n' \
  "$(date -Is)" "$([ -f "$IDX" ] && echo yes || echo missing)" "${n:-0}" \
  >> "$ROOT/.claude/hooks.log" 2>/dev/null || true

out="${out}
⚠️ 以上是 hook 注入的**摘要**，不替代原文：排查 bug 时仍须按 §6 走「INDEX 按症状反查 → docs/反模式.md → 才动手复现」。
🚦 收到代码改动请求时，先走 §4.3 路径确认，等用户拍板。"

jq -n --arg ctx "$out" '{
  hookSpecificOutput: {
    hookEventName: "SessionStart",
    additionalContext: $ctx
  },
  suppressOutput: true
}'
exit 0
