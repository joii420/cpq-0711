#!/usr/bin/env bash
# ============================================================
# Hook 1/4 · PreToolUse(Edit|Write) —— 按文件后缀注入对应分册的自检清单
#
#   解决的问题：CLAUDE.md §2.2 触发矩阵「不读就动手 = 违规」全靠模型自觉，
#   同一会话里改第二个文件时基本不会再读一次；上下文压缩后分册内容更是直接蒸发。
#
#   设计要点：
#     · 清单**从分册原文实时提取**（`- [ ]` 行），不复制规则正文 —— 分册改了自动同步，
#       符合 README「不要把规则正文复制进别处，重复即漂移源」的原则
#     · 每个会话对每一类文件**首次命中**时额外附「必读全文」指令，之后只附清单
#     · 永不阻断（exit 0），只注入上下文
# ============================================================
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
RULES="$ROOT/docs/rules"
log(){ printf '%s\tinject-rules\t%s\t%s\n' "$(date -Is)" "$1" "$2" >> "$ROOT/.claude/hooks.log" 2>/dev/null || true; }

payload=$(cat)
fp=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
sid=$(printf '%s' "$payload" | jq -r '.session_id // "nosession"' 2>/dev/null)
[ -n "$fp" ] || exit 0

base=$(basename "$fp")
books=""

# 🚨 目录类判据一律写成**斜杠包裹**（`*/test/*` 而不是 `*test*`）。
#    实测教训：项目目录叫 `dev-task-test2`，裸 `*test*` 命中了每一个文件，
#    整个项目被误判成测试代码 —— 50 次注入里 42 次走错分册，
#    frontend/backend 两册全程零注入。斜杠包裹后 `dev-task-test2` 不再误命中。

# ① 测试类优先判定（否则会被前后端后缀先吃掉）
case "$fp" in
  */test/*|*/tests/*|*/__tests__/*|*/e2e/*|*/spec/*|*/specs/*|*/cypress/*|*/playwright/*) books="testing" ;;
esac
case "$base" in
  *.test.*|*.spec.*|*_test.*|*_spec.*|test_*|*.cy.*) books="testing" ;;
esac

# ② 前后端：**先看目录归属，再退回后缀**。
#    全栈 TS 项目里 `.ts` 前后端都有，只看后缀会把 backend/src/server.ts 判成前端。
#    前端目录先判，因为 frontend/src/api/ 这种路径两边都像。
if [ -z "$books" ]; then
  case "$fp" in
    */frontend/*|*/client/*|*/web/*|*/webapp/*|*/ui/*) books="frontend" ;;
    */backend/*|*/server/*|*/service/*|*/src/main/*|*/prisma/*|*/dao/*|*/repository/*) books="backend" ;;
  esac
fi
if [ -z "$books" ]; then
  case "$base" in
    *.tsx|*.jsx|*.vue|*.svelte|*.css|*.scss|*.less) books="frontend" ;;
    *.ts|*.js|*.mjs) books="frontend" ;;
    *.java|*.kt|*.py|*.go|*.rb|*.php|*.cs|*.rs) books="backend" ;;
  esac
fi

# ③ 无条件覆盖项：这些文件不论在哪个目录都属于协议级
case "$base" in
  *.sql|*.prisma) books="backend change-protocol" ;;
esac
case "$fp" in
  */migration/*|*/migrations/*|*/migrate/*|*/flyway/*|*/liquibase/*) books="backend change-protocol" ;;
esac
# 契约高发文件名：DTO / 类型定义 / 枚举 / 接口契约 —— 追加强联动分册
case "$base" in
  *dto*|*DTO*|types.ts|*enum*|*Enum*|*schema*|*Schema*|api.ts|*Api.ts)
    case " $books " in *" change-protocol "*) ;; *) books="${books:+$books }change-protocol" ;; esac ;;
esac
# 任务文档 / 结案时要动的两个索引文件
# （BACKLOG.md 在 docs/ 下，不在 dev-docs/ 下 —— 漏掉它，结案清单就调不出来）
case "$fp" in
  */dev-docs/*) case "$base" in *.md) books="task-docs" ;; esac ;;
esac
case "$base" in BACKLOG.md) books="task-docs" ;; esac
# 🤖 fronttask/backtask 是**给子代理用的任务书**，写它们的时刻正是该决定派不派的时刻。
#    subagents.md 的触发条件绝不能是「你打算派子代理时」—— 那是死循环
#    （不打算派 → 不读 → 读不到「默认必须派」→ 更不会打算派）。实测连栽两轮。
case "$base" in
  fronttask.md|backtask.md) books="task-docs subagents" ;;
esac
# HTML 原型图：视觉基准，改它等于改验收标准
case "$fp" in
  */原型图/*|*/prototype/*|*/prototypes/*) books="frontend task-docs" ;;
esac

[ -n "$books" ] || exit 0

mark_dir="${TMPDIR:-/tmp}/claude-rules-$sid"
mkdir -p "$mark_dir" 2>/dev/null

out=""
for b in $books; do
  f="$RULES/$b.md"
  [ -f "$f" ] || continue
  list=$(grep -E '^- \[ \]' "$f" || true)
  [ -n "$list" ] || continue

  if [ ! -e "$mark_dir/$b" ]; then
    : > "$mark_dir/$b"
    out="${out}
🎯 本会话首次改动「$b」类文件（$(basename "$fp")）。
按 CLAUDE.md §2.2 触发矩阵，动手前**必读 docs/rules/$b.md 全文**（本次注入的只是它的自检清单，不能替代原文）。

docs/rules/$b.md · 收工前逐条勾：
$list
"
  else
    out="${out}
docs/rules/$b.md · 收工前逐条勾（改动 $(basename "$fp")）：
$list
"
  fi
done

[ -n "$out" ] || exit 0

log "$(printf '%s' "$books" | tr ' ' ',')" "$(basename "$fp")"

jq -n --arg ctx "$out" '{
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: $ctx
  },
  suppressOutput: true
}'
exit 0
