#!/usr/bin/env bash
# ============================================================
# Hook 5/5 · PreCompact —— 给压缩摘要点菜
#
#   为什么必须有这一条：
#     压缩清掉的是 transcript，**`CLAUDE.md` 在系统提示区不会被清**。
#     于是压缩后会进入最危险的状态 —— 它还记得有规则、还记得任务在做什么，
#     但 AC 原文、分册全文、派工 prompt、子代理回报正文**都已经没了**，
#     摘要里只剩转述。转述足以让它「觉得自己知道」，不足以让它验对，
#     而 AC 的可观测断言（**具体数值、具体文案、具体行数**）恰恰是最先被摘要抹平的东西。
#
#   本 hook 的 stdout 会**整段成为本次压缩摘要请求的 customInstructions**，
#   `trigger:"auto"`（阈值自动压缩）同样生效 —— 也就是说「摘要保留什么」是可控的。
#   这是唯一能在压缩发生**之前**干预它的杠杆；压缩之后再提醒已经晚了。
#
#   🚨 四条硬约束，改脚本前先读一遍：
#     ① 输出**裸文本**，不是 JSON。PreCompact 不在合法 hookSpecificOutput.hookEventName
#        枚举里，输出 JSON 会被整段当成字面文本喂进摘要指令。
#     ② **任何分支都必须 exit 0**。非 0 会走 harness 的
#        `Compaction blocked by PreCompact hook` 分支 —— 后果不是「没注入」，
#        是**压缩不发生**，会话直接撞满窗。所以用 `set -uo pipefail`，**绝不加 `-e`**。
#     ③ **不解析 stdin、不依赖任何外部 JSON 解析器**。缺解析器时 hook 会静默失效，
#        本 hook 必须免疫。输出内容与 trigger 无关，manual / auto 完全一致。
#     ④ 空 stdout 会被 harness 过滤掉（等同没注入），所以五条清单是**固定输出**，
#        只有第 6 行「当前在途任务」是条件输出。
#
#   永不阻断，只点菜。
# ============================================================
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
# ⚠️ `2>/dev/null` 必须写在 `>>` **之前**：重定向按从左到右生效，写反了的话
#    「.claude/hooks.log 所在目录不存在」这条**由 shell 自己发出的**重定向失败信息
#    会在 2>/dev/null 生效前就漏到 stderr 上（实测 163 字节）。写日志失败必须完全静默。
log(){ printf '%s\tpre-compact\t%s\t%s\n' "$(date -Is)" "$1" "${2:-}" 2>/dev/null >> "$ROOT/.claude/hooks.log" || true; }

# ---- 条件项：从态势表抠在途任务目录名（抠不到就不输出第 6 行）----
# 写法沿用已退役的那个压缩后重锚脚本：只看「进行中的任务」那一行，不全文扫。
IDX="$ROOT/dev-docs/INDEX.md"
task=""
if [ -f "$IDX" ]; then
  task=$(awk '/进行中的任务/{print; exit}' "$IDX" 2>/dev/null \
    | grep -oE '(task|repair)-[0-9]{6}-[^`（(]*' 2>/dev/null \
    | head -1 | sed 's/[[:space:]]*$//' 2>/dev/null) || task=""
fi

# ---- 固定输出：五条保留清单（裸文本，不是 JSON）----
printf '%s\n' '生成摘要时，以下内容必须逐字保留，不许转述、不许归纳：'
printf '%s\n' '1. AC 编号及其可观测断言的具体数值 / 具体文案 / 具体行数'
printf '%s\n' '2. 工具调用返回的具体数字（行数、条数、返回码、端口、迁移版本号）与具体文件路径'
printf '%s\n' '3. 任何被标注「未验证」「没做到」「待确认」的项 —— 连同标注词一起保留'
printf '%s\n' '4. 尚未闭合的闸门（路径确认 / A0 / A / B）及其在等什么'
printf '%s\n' '5. 用户明确否决过的方案，以及否决理由'

# ---- 条件输出：第 6 行 ----
if [ -n "$task" ]; then
  printf '6. 当前在途任务目录：%s —— 其 AC 原文在 dev-docs/%s/ 下，摘要里保留该路径\n' "$task" "$task"
fi

printf '%s\n' '不要把「某代理报告了 X 相关内容」当作对 X 的保留 —— 那等于丢了。'

log injected "${task:-no-task}"
exit 0
