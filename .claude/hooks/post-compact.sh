#!/usr/bin/env bash
# ============================================================
# Hook 5/5 · PostCompact —— 上下文压缩后的重锚
#
#   为什么必须有这一条：
#     压缩会把 **transcript 里的东西** 清掉，而 `CLAUDE.md` 在系统提示区**不会**被清。
#     于是压缩后出现一个最危险的状态 —— 它**还记得有规则、还记得任务在做什么**，
#     但 AC 原文、分册全文、派工 prompt、原型图内容**都已经没了**，
#     而压缩摘要里只剩转述。转述足以让它「感觉自己知道」，不足以让它验对。
#
#   🚨 没有任何机制会自动触发重读 —— 这就是那个机制。
#   永不阻断，只注入。
# ============================================================
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
log(){ printf '%s\tpost-compact\t%s\t%s\n' "$(date -Is)" "$1" "${2:-}" >> "$ROOT/.claude/hooks.log" 2>/dev/null || true; }

IDX="$ROOT/dev-docs/INDEX.md"
task=""
if [ -f "$IDX" ]; then
  # 从态势表「进行中的任务」里抠出任务目录名
  task=$(awk '/进行中的任务/{print; exit}' "$IDX" 2>/dev/null \
    | grep -oE '(task|repair)-[0-9]{6}-[^`（(]*' | head -1 | sed 's/[[:space:]]*$//')
fi

out="
🔄 **刚刚发生了上下文压缩。以下内容已经从你的上下文里蒸发了 —— 它们在 transcript 里，不在系统提示里：**

| 蒸发了 | 还在 |
|---|---|
| 规则分册全文（\`docs/rules/*.md\`） | \`CLAUDE.md\`（常驻，没丢） |
| **AC 原文**、任务文档正文 | 任务目录里的**文件本身**（重读即可） |
| 你发出去的派工 prompt、子代理的回报正文 | 已落盘的 \`test-report.md\` / 回报摘要 |
| 原型图的具体内容 | \`原型图/*.html\` 文件 |

🚫 **压缩摘要里的转述不能替代原文。** 摘要足以让你「觉得自己知道」，不足以让你验对 ——
AC 的可观测断言（具体数值、具体文案、具体行数）恰恰是最先被摘要抹平的部分。

**动手前按需重读（读文件，不要凭记忆）：**
"

if [ -n "$task" ]; then
  out="${out}
- 📌 当前在途任务：\`$task\`
  - **AC 原文** → \`dev-docs/$task/需求文档.md\`（返修任务是 \`问题说明.md\`）的验收标准一节
  - 任务分解与 AC 映射 → \`fronttask.md\` / \`backtask.md\`
  - 接口契约 → \`api.md\`　·　追溯矩阵 → \`test.md\`
"
else
  out="${out}
- 📌 态势表里没有在途任务；若你正在做的事有任务目录，重读它的 AC 章节。
"
fi

out="${out}
- 📖 **本阶段对应的规则分册** → 按 \`CLAUDE.md §2.2\` 触发矩阵重新调取一次

⚠️ **自检一句话**：如果你现在**说不出当前任务的 AC 编号和它们的可观测断言**，那就是已经蒸发了，
先读回来再动手 —— 不要凭摘要继续写代码、写测试或做亲验。
"

log injected "${task:-no-task}"
jq -n --arg ctx "$out" '{
  hookSpecificOutput: { hookEventName: "PostCompact", additionalContext: $ctx },
  suppressOutput: true
}'
exit 0
