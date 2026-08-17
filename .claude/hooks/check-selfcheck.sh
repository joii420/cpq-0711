#!/usr/bin/env bash
# ============================================================
# Hook 4/4 · Stop —— 「完成」宣告缺自检声明行时打回
#
#   落地 CLAUDE.md §6.1：「没有『已自检』声明行的『完成』= 未完成」。
#   这是最容易做到形式合规、也最容易被省掉的一条 —— 交给 harness 兜底。
#
#   判定逻辑（宁可漏判，不可误拦）：
#     最后一条助手消息里 **同时** 满足「出现完成类措辞」+「没有自检证据」→ block
#     只描述过程、只提问、只汇报失败 → 不拦
#
#   ⚠️ 本 hook 只能验「有没有写那一行」，验不了「那一行是不是真跑出来的」。
#      内容真实性仍靠 §6.1 的口径（自检 = 命令与其输出；亲验 = 实际业务输出值）。
#   ⚠️ stop_hook_active 为 true 时直接放行，避免自我循环。
# ============================================================
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
log(){ printf '%s\tcheck-selfcheck\t%s\t%s\n' "$(date -Is)" "$1" "${2:-}${3:+ $3}" >> "$ROOT/.claude/hooks.log" 2>/dev/null || true; }

payload=$(cat)

# 防循环：已经因本 hook 被打回过一次就不再拦
active=$(printf '%s' "$payload" | jq -r '.stop_hook_active // false' 2>/dev/null)
[ "$active" = "true" ] && { log skip hook-active; exit 0; }

# 🚨 每个提前退出点都要留日志。
#    实测教训：51 条日志里本 hook 一条都没有，因为它在写日志之前就静默 exit 了 ——
#    「没数据」和「有数据但全通过」在报表上长得一模一样，而这两件事含义相反。
tp=$(printf '%s' "$payload" | jq -r '.transcript_path // empty' 2>/dev/null)
if [ -z "$tp" ]; then
  log diag no-transcript-path "$(printf '%s' "$payload" | jq -rc 'keys' 2>/dev/null)"
  exit 0
fi
[ -f "$tp" ] || { log diag transcript-not-found; exit 0; }

# 取最后一条 assistant 消息的纯文本。
# 🚨 必须逐行 fromjson 容错，不能直接 `tail | jq -s`：
#    transcript 里单行可能极长（大段工具输出），`tail -n 400` 会从**中间截断**首行，
#    jq -s 解析整块时直接失败 → 什么都取不到 → 静默放行。
#    实测就是栽在这：日志里只有 `diag empty-text`，看着像"没有完成宣告"，
#    实际是解析炸了。逐行解析时坏行被丢弃，其余照常。
# content 可能是数组（[{type:text,text}]）也可能直接是字符串，两种都认。
# 🚨 取「最后一条**带文本的** assistant」，不是「最后一条 assistant」——
#    Stop 触发时最末条目常常是 tool_use（content 数组里只有工具调用没有 text），
#    取 last 会拿到空串，看着像"本轮没有完成宣告"，实际是取错了条目。实测栽在这。
# 🚨 取**本轮全部**助手文本拼起来，不是「最后一个文本块」。
#    实测教训：它的报告开头写了「已自检：tsc --noEmit 0 错误 ✅…」，
#    但报告**结尾**另起一段讲原型图待裁决 —— 最后那块没有自检词，于是被误拦。
#    判据是「这一轮有没有声明自检」，不是「最后一段有没有」。
last=$(tail -n 2000 "$tp" 2>/dev/null \
  | jq -R 'fromjson? // empty' 2>/dev/null \
  | jq -rs '
      reverse
      | (map(.type=="user" and ((.message.content // []) | if type=="string" then (.|length>0)
             else (any(.[]?; .type=="text")) end)) | index(true)) as $i
      | (if $i == null then . else .[0:$i] end)
      | [ .[] | select(.type=="assistant") | .message.content
          | if type=="string" then .
            elif type=="array" then ([.[]? | select(.type=="text") | .text] | join("\n"))
            else "" end
          | select(. != "" and . != null) ]
      | join("\n") ' 2>/dev/null)
if [ -z "$last" ]; then
  log diag empty-text "$(tail -n 1 "$tp" 2>/dev/null | jq -rc '{type, ct:(.message.content|type)}' 2>/dev/null)"
  exit 0
fi

# —— 完成类措辞 ——
# 🚨 判据不是「它说了哪个词」，是「这一轮动没动实现代码」。
#    实测教训：追了三次措辞都被绕过 —— 先是「已结案」不在表里，扩容后又变成「改完了」。
#    措辞是无穷的，**改动是客观的**。分母换成「触碰实现代码的轮次」，与说法无关。
CODE_TOUCHED=$(tail -n 2000 "$tp" 2>/dev/null | jq -R 'fromjson? // empty' 2>/dev/null \
  | jq -rs '
      reverse
      | (map(.type=="user" and ((.message.content // []) | if type=="string" then (.|length>0)
             else (any(.[]?; .type=="text")) end)) | index(true)) as $i
      | (if $i == null then . else .[0:$i] end)
      | [ .[] | select(.type=="assistant") | (.message.content // [])[]?
          | select(.type=="tool_use" and (.name=="Edit" or .name=="Write" or .name=="NotebookEdit"))
          | .input.file_path // empty ]
      | .[]' 2>/dev/null \
  | grep -E '/(src|lib|app|prisma|migrations?)/|\.(tsx?|jsx?|vue|svelte|java|kt|py|go|rb|php|cs|rs|sql|prisma)$' \
  | grep -vE '/(dev-docs|docs)/' | head -5)

CODE_FLAG=n; [ -n "$CODE_TOUCHED" ] && CODE_FLAG=y
HAS_CLAIM=no
printf '%s' "$last" | grep -qE '已完成|完成了|改完了|全部完成|已修复|修复完成|已实现|实现完毕|已交付|已结案|结案完毕|交付完成|验收通过|搞定|可以用了|没问题了|全部通过|全绿' && HAS_CLAIM=yes

# 既没动代码、也没宣告完成 → 与本 hook 无关
if [ -z "$CODE_TOUCHED" ] && [ "$HAS_CLAIM" = no ]; then log no-code; exit 0; fi

# —— 自检证据（任一命中即放行）——
if printf '%s' "$last" | grep -qE '已自检|自检[：:]|自检声明|tsc --noEmit|--noEmit|N\+1 自检|http_code|返回 ?(200|401)|success ?= ?t|迁移历史表'; then
  log pass "code=$CODE_FLAG claim=$HAS_CLAIM"
  exit 0
fi

# 动了代码但这一轮是**回来问你**（不是交付），不该按「完成宣告」要求自检行。
# 只记不拦 —— 拦了会把正常的中途请示也堵死。
if [ "$HAS_CLAIM" = no ] && printf '%s' "$last" | grep -qE '[？?]|要不要|你想|哪个|请裁决|等你|你确认|怎么办'; then
  log ask-back "code-touched-but-asking"
  exit 0
fi

log BLOCK "code=$CODE_FLAG claim=$HAS_CLAIM"
jq -n '{
  decision: "block",
  reason: "🚨 CLAUDE.md §6.1：本次回复里出现了「完成」类宣告，但没有『已自检』声明行 —— **没有这行声明的「完成」= 未完成**。\n\n补齐后重报，格式形如：\n> TS 0 错误 ✅；XxxPage.tsx → dev server 200 ✅；后端 /api/xxx → 401（auth 正常）✅；迁移 V77 success=t ✅\n\n各端具体自检项：前端见 docs/rules/frontend.md §2.1（tsc --noEmit 0 错误 + 每个改动文件的模块地址 200 + 用户点名页面 URL 200），后端见 docs/rules/backend.md §2（强制重启 + endpoint 返 200/401 不要 500 + 迁移 success=true + N+1 自检）。\n\n⚠️ 两点别混：\n· 自检回答「代码能跑吗」——形式是**命令与其原始输出**，不是转述；\n· 亲验回答「功能对吗」——形式是实际业务输出值/截图/接口原始响应。闸门 B 汇报里两者都要有。\n· 若确实验证不了，不要写「应该没问题」——明写「未验证：<哪一项>、<为什么验不了>」，这同样是合法的收尾。"
}'
exit 0
