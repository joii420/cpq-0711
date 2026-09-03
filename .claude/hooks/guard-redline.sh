#!/usr/bin/env bash
# ============================================================
# Hook 2/4 · PreToolUse(Bash) —— 不可逆操作红线的硬拦截
#
#   落地 CLAUDE.md §3.2：这些操作的前提是「做错了可以改回来」不成立，
#   所以不能靠模型自觉，必须由 harness 拦。
#
#   两档：
#     deny —— §3.2 五类销毁操作。直接拒，逼模型回去走「三步前置」
#             （量化影响面 → 说清可恢复路径 → 用户明确批准本次）
#             + git-worktree.md §1.0 的 worktree 落点纪律（可逆，但必然重犯，见该段注释）
#     ask  —— git-worktree.md §3 并发纪律里「会夹带 / 会丢别人改动」的操作，
#             交用户当场判断
#
#   ⚠️ 子代理同样受这个 hook 约束，且它没有批准权 —— 这正是 §3.2 要的效果。
#   ⚠️ 只读命令（grep/cat/git log…）整条跳过，避免 `grep "DROP TABLE"` 这类误伤。
#
#   豁免开关（只对 ask 档生效，deny 档无开关、不可豁免）：
#     CLAUDE_SKIP_ADDALL_ASK=1   `git add -A` / `git add .` 降级为「只记日志不拦」
#   在 .claude/settings.json 的 env 段设置，或临时 export。
#   ⚠️ 降级不是关闭：每次命中仍写一行 skip-ask 进 .claude/hooks.log，
#      hooks-report.sh 能统计出你到底豁免了多少次 —— 别让它变成静默失效。
#
#   审计行格式（.claude/hooks.log，TSV）：
#     时间 \t guard-redline \t 裁决 \t 命令前 120 字节 \t **规则名**
#   🚨 规则名是**第 5 列、追加在命令之后**，不能插进中间：hooks.log 是四个 hook
#      共用的表，hooks-report.sh 按 $3/$4 取裁决与命令，插进中间会让另外三个 hook
#      的行和本 hook 的历史行整体错位，而且**不报错**。旧行没有第 5 列，读作空即可。
#   ⚠️ 每个 emit 都必须报出自己的规则名，否则事后无法回答「是哪条拦的」——
#      实测：64 条 ask 里有 50 条因为只记了裁决 + 截断命令，事后归因不到规则。
# ============================================================
set -uo pipefail

cmd=$(jq -r '.tool_input.command // empty' 2>/dev/null)
[ -n "$cmd" ] || exit 0

ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"

logline() { # $1=decision  $2=规则名 —— 只写审计行，不作裁决、不退出
  printf '%s\tguard-redline\t%s\t%s\t%s\n' "$(date -Is)" "$1" \
    "$(printf '%s' "$cmd" | head -c 120 | tr '\t\n' '  ')" "${2:-未命名}" \
    >> "$ROOT/.claude/hooks.log" 2>/dev/null || true
}

emit() { # $1=allow|deny|ask  $2=规则名  $3=reason
  logline "$1" "$2"
  jq -n --arg d "$1" --arg r "$3" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: $d,
      permissionDecisionReason: $r
    }
  }'
  exit 0
}

# ---- 命令解析：按段剥离只读子命令，剩下的才做红线匹配 ----
#
# 🚨 不能拿「整条命令的第一个词」判断。实测教训：Claude Code 发的几乎都是
#    `cd <路径>` 开头的多行命令块，而 `sed 's/[[:space:]].*//'` 是**逐行**处理的，
#    first 会变成 "cd\necho\ngit" 这种多行值，白名单一条都匹配不上 —— 等于白名单失效。
#
# 现在按 `;` `&&` `||` `|` 和换行切段，逐段看首个动词：
#   · 只读段（grep/cat/echo/ls/git log…）整段丢弃 → `grep "DROP TABLE"` 不再误伤
#   · 其余段拼起来做红线匹配   → `cd x && rm -rf y` 里的 rm 仍然抓得到
# 🚨 先剥掉 heredoc 正文 —— 那是**被写入的数据**，不是要执行的命令。
#    实测教训：往 INDEX.md 里写「待执行的清理命令」这段文本时，
#    `python3 - <<'PY' ... git worktree remove ... PY` 被当成真的在删 worktree，连拦 4 次。
#    hook 分不清「写进文档的命令字符串」和「真的执行」，这个区分只能靠 heredoc 边界。
#    例外：正文交给 shell 解释器（bash/sh/zsh）时它就是命令，必须留下继续扫。
cmd_nohd=$(printf '%s' "$cmd" | awk '
  BEGIN{ind=0}
  {
    if (ind) { if ($0 ~ ("^[[:space:]]*" delim "[[:space:]]*$")) ind=0; next }
    line=$0
    if (match(line, /<<-?[[:space:]]*['"'"'"]?[A-Za-z_][A-Za-z0-9_]*['"'"'"]?/)) {
      d=substr(line, RSTART, RLENGTH)
      gsub(/^<<-?[[:space:]]*|['"'"'"]/, "", d)
      # 交给 shell 解释器的 heredoc 是命令，保留
      if (line ~ /(^|[[:space:]|;&(])(ba|z|da)?sh[[:space:]]+.*<</) { print line; next }
      delim=d; ind=1; print line; next
    }
    print line
  }')
# 🚨 先把**反斜杠续行**折回一行 —— `\`+换行是 shell 的行接续，本就属于同一条命令。
#    实测教训：`git commit -q -F "$MSGF" \` 换行 `-- <paths>` 会被判成「没限定路径」而弹 ask。
#    原因在下面那步 `tr '\n' ';'`：换行变成了 `;`，而豁免正则的 `[^|;&]*` 恰好排除 `;`，
#    于是**触发式匹配得到、豁免式匹配不到**，两边不对称 —— 写法越规范越挨拦。
#    ⚠️ 只折反斜杠续行，**绝不折裸换行**：裸换行在 shell 里本来就终止命令，
#       折了会把两条命令粘成一条，让 `git commit -m x` 换行 `git add -- y` 里
#       属于 add 的 `--` 被误算给 commit，等于把夹带风险悄悄放行。
# 🚨 然后剥掉 `git commit/tag -m` 的**消息体** —— 同样是数据不是命令。
#    实测教训：多行 commit message 里写了「后续需 rm -rf dist / git branch -d」之类的说明，
#    整条提交被 deny。heredoc 在上一步已剥，但引号里的多行字符串是另一条路径。
#    ⚠️ 只剥 -m 的参数，**不能通杀所有引号** —— `psql -c "DROP TABLE t"` 里的引号内容是要执行的，必须留着扫。
cmd_nomsg=$(printf '%s' "$cmd_nohd" | perl -0777 -pe '
  s/\\\n[ \t]*/ /g;
  s/(git\s+(?:commit|tag)\b[^\n]*?\s-{1,2}m(?:essage)?[=\s]\s*)("(?:[^"\\]|\\.)*"|'\''[^'\'']*'\'')/$1"<msg>"/gs
' 2>/dev/null || printf '%s' "$cmd_nohd")

scan=$(printf '%s' "$cmd_nomsg" | tr '\n' ';' | sed -E 's/&&/;/g; s/\|\|/;/g; s/\|/;/g')
danger=""
OLD_IFS=$IFS; IFS=';'
for seg in $scan; do
  verb=$(printf '%s' "$seg" | sed -E 's/^[[:space:]]*//; s/[[:space:]].*//')
  case "$verb" in
    # 纯读取类：整段丢弃（注意 sed/awk 不在此列 —— sed -i 会写文件）
    grep|rg|ag|cat|head|tail|less|more|ls|find|wc|file|stat|diff|echo|printf|jq|which|type|env|date|pwd|cd|true|test|export)
      continue ;;
    git)
      case "$seg" in
        *" log"*|*" diff"*|*" status"*|*" show"*|*" blame"*|*" rev-parse"*|*" branch --contains"*|*" worktree list"*|*" config --get"*)
          continue ;;
      esac ;;
  esac
  danger="$danger; $seg"
done
IFS=$OLD_IFS
[ -n "$danger" ] || exit 0        # 全是只读段
cmd_scan="$danger"

# ---- 定位命令实际在哪个仓库里跑（可能 cd 进了 worktree）----
wd="$ROOT"
cdp=$(printf '%s' "$cmd" | tr '\n' ' ' \
  | sed -nE 's/.*(^|[[:space:]])cd[[:space:]]+"?([^"[:space:];&|]+)"?.*/\2/p' | head -1)
[ -n "$cdp" ] && [ -d "$cdp" ] && wd="$cdp"

C=$(printf '%s' "$cmd_scan" | tr '[:lower:]' '[:upper:]')

# ============ 第一档：deny（§3.2 不可逆操作红线）============

# —— 文件销毁 ——
if printf '%s' "$cmd_scan" | grep -qE '(^|[;&|[:space:]])rm[[:space:]]+(-[a-zA-Z]*[rR][a-zA-Z]*[fF]|-[a-zA-Z]*[fF][a-zA-Z]*[rR])'; then
  emit deny rm-rf "🚨 CLAUDE.md §3.2 红线【文件销毁】：rm -rf 被 hook 拦截。
必须先走三步前置：① 用 \`ls\` 列一遍要删的路径、说清「将删除多少个文件」② 说清可恢复路径（有备份？在 git 里？三个都没有就直说不可恢复）③ 把「操作 + 影响面数字 + 可恢复性」报给用户，等明确批准本次。
子代理没有批准权，遇到本条一律停下来报告。"
fi

# —— 历史销毁 ——
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+reset[[:space:]]+.*--hard'; then
  emit deny reset-hard "🚨 CLAUDE.md §3.2 红线【历史销毁】：git reset --hard 被 hook 拦截。
它会连未提交的工作一起丢，而且工作区里可能有**别人**的改动（多会话并发）。
先 \`git status\` / \`git diff --stat\` 量化会丢什么，报给用户等批准。要撤自己的改动见 git-worktree.md §3.2：用编辑器精准删自己加的那几行，不要整文件回退。"
fi
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+push' \
   && printf '%s' "$cmd_scan" | grep -qE '(--force|--force-with-lease|(^|[[:space:]])-f([[:space:]]|$))'; then
  emit deny push-force "🚨 CLAUDE.md §3.2 红线【历史销毁】：git push -f / --force-with-lease 被 hook 拦截。
远端历史是其他会话和他人的基线。必须报给用户并获得**本次**明确批准。"
fi
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+clean[[:space:]]+-[a-zA-Z]*[fd]'; then
  emit deny clean-fd "🚨 CLAUDE.md §3.2 红线【历史销毁】：git clean -fd 被 hook 拦截。
先 \`git clean -nd\` 干跑一遍列出会删什么（这也满足§3.2第①步的量化要求），报给用户等批准。"
fi
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+branch[[:space:]]+.*(-[dD]([[:space:]]|$)|--delete)'; then
  emit deny branch-delete "🚨 CLAUDE.md §3.2 红线【历史销毁】：删分支被 hook 拦截。
删分支前必须确认它已合并且 dev-docs/INDEX.md 态势表的「未合并分支」行已处理（git-worktree.md §2⑥）。报给用户等批准。"
fi
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+rebase' && printf '%s' "$cmd_scan" | grep -qvE 'rebase[[:space:]]+--(abort|continue|skip)'; then
  emit deny rebase "🚨 CLAUDE.md §3.2 红线【历史销毁】：rebase 被 hook 拦截。
若该分支**已推送**，rebase 会重写他人已拉取的历史。先确认分支是否已推送并报给用户。"
fi

# —— 数据销毁 ——
if printf '%s' "$C" | grep -qE 'DROP[[:space:]]+(TABLE|VIEW|SCHEMA|DATABASE|INDEX|TYPE|SEQUENCE)'; then
  emit deny sql-drop "🚨 CLAUDE.md §3.2 红线【数据销毁】：DROP 被 hook 拦截。
三步前置缺一不可：① 先用只读手段量化影响面（\`SELECT count(*)\`、依赖对象清单）② 说清可恢复路径 ③ 用户明确批准**本次**（批了删 A 表不等于批了删 B 表）。
⚠️ 带 CASCADE 的还要额外注意 backend.md §3：DDL 之后**必须强制重启服务**，否则进程级缓存会缓存空集并永久残留。"
fi
if printf '%s' "$C" | grep -qE '(^|[^A-Z_])TRUNCATE([^A-Z_]|$)'; then
  emit deny sql-truncate "🚨 CLAUDE.md §3.2 红线【数据销毁】：TRUNCATE 被 hook 拦截。先 \`SELECT count(*)\` 说清将清掉多少行，报给用户等批准。"
fi
if printf '%s' "$C" | grep -qE 'DELETE[[:space:]]+FROM' && ! printf '%s' "$C" | grep -qE 'DELETE[[:space:]]+FROM.*WHERE'; then
  emit deny sql-delete-nowhere "🚨 CLAUDE.md §3.2 红线【数据销毁】：无 WHERE 的 DELETE 被 hook 拦截。
同样的 WHERE 先跑 \`SELECT count(*)\` 说出数字；**说不出数字就不许执行**。"
fi
if printf '%s' "$C" | grep -qE 'UPDATE[[:space:]]+[A-Z_."]+[[:space:]]+SET' && ! printf '%s' "$C" | grep -qE 'WHERE'; then
  emit deny sql-update-nowhere "🚨 CLAUDE.md §3.2 红线【数据销毁】：无 WHERE 的 UPDATE 被 hook 拦截。先用同样的 WHERE 跑 \`SELECT count(*)\` 量化命中面。"
fi

# —— 契约销毁：已应用到共享库的迁移文件 ——
if printf '%s' "$cmd_scan" | grep -qE '(^|[;&|[:space:]])(rm|mv)[[:space:]]' && printf '%s' "$cmd_scan" | grep -qiE 'migration|migrations|db/migrate|flyway|liquibase'; then
  emit deny migration-file "🚨 CLAUDE.md §3.2 红线【契约销毁】：改名/移动/删除迁移文件被 hook 拦截。
迁移工具按 checksum 对账，动了**已应用到共享库**的迁移会让所有人的服务启动失败（backend.md §4）。
schema 变更一律**新建**迁移脚本，不改历史脚本。确需处理请报给用户。"
fi

# —— 环境销毁：脚本顶层副作用（实测踩过）——
# `node -e "require('./prisma/seed.ts')"` 本意是读常量，实际执行了顶层的 deleteMany + 重建。
# 这类破坏不出现任何危险关键字，靠形状匹配抓不到，只能按「eval + 引入副作用脚本」拦。
if printf '%s' "$cmd_scan" | grep -qE '(node|ts-node|tsx|bun|deno)[[:space:]]+(-e|--eval|-p)' \
   && printf '%s' "$cmd_scan" | grep -qiE '(require|import)[^)]*(seed|migrat|reset|teardown|setup|bootstrap|fixture)'; then
  emit deny eval-side-effect "🚨 CLAUDE.md §3.2 红线【环境销毁】：用 \`-e\` 引入 seed/migrate/setup 类脚本被 hook 拦截。
**这类脚本有顶层副作用** —— 你以为只是读几个常量，实际会把 \`deleteMany\` / 建表 / 重置整套跑一遍，而且**命令里看不出任何危险关键字**。
✅ 想读常量就直接读文件（\`sed -n\` / \`grep\`），不要 require/import 它。
✅ 确实要跑，就显式跑（\`npm run seed\`），并先说清目标库是哪个、影响多少行、报给用户批准。"
fi
if printf '%s' "$cmd_scan" | grep -qE '\.deleteMany\(\s*(\)|\{\s*\})|\.destroy\(\s*\{\s*(where\s*:\s*\{\s*\})?\s*\}|\.truncate\('; then
  emit deny orm-delete-all "🚨 CLAUDE.md §3.2 红线【数据销毁】：无条件 \`deleteMany()\` / \`truncate()\` 被 hook 拦截。
这是「删全表」。先用同样条件跑一次 count 说出数字，说清可恢复路径，报给用户批准本次。"
fi

# —— 环境销毁 ——
if printf '%s' "$C" | grep -qE '(DROPDB|DROP[[:space:]]+DATABASE|FLYWAY[[:space:]]+CLEAN|PRISMA[[:space:]]+MIGRATE[[:space:]]+RESET|DB:RESET|SCHEMA:DROP)'; then
  emit deny db-reset "🚨 CLAUDE.md §3.2 红线【环境销毁】：清库 / 重建库被 hook 拦截。
⚠️ 测试里的清库也算 —— **共享库上不许跑会清库的测试**，哪怕它写在 beforeAll 里（testing.md §4.3）。先确认目标库是不是共享库，报给用户等批准。"
fi

# —— 工作区纪律：worktree 建错位置 ——
# 🚨 这条不是 §3.2 红线（建错了能 git worktree move 迁回来），但仍放 deny 档：
#    它是**必然会重犯**的一类偏差 —— git-worktree.md §1.0 的触发条件是"该建 worktree 时"，
#    而不打算读该分册的人恰恰不会去读它，ask 档在这里等于没拦。
#    实测：某项目规则装了 6 天、.gitignore 也配好了，worktree 仍被建到项目同级目录
#    ../wt-<任务名> —— 既不是本册的 .claude/worktrees/，也不是任何技能的默认值，是现拍的。
#    deny 的代价只是换个路径重跑一次，成本远低于事后迁移 + 清理孤儿目录。
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+(-C[[:space:]]+[^[:space:]]+[[:space:]]+)?worktree[[:space:]]+add'; then
  # 取 `worktree add` 之后第一个非选项参数 = 目标路径。
  # 带值的选项（-b / -B / --reason）要连它的值一起跳过，否则会把分支名当成路径。
  wt_path=$(printf '%s' "$cmd_scan" | tr ';' '\n' | grep -E 'worktree[[:space:]]+add' | head -1 \
    | sed -E 's/.*worktree[[:space:]]+add[[:space:]]+//' | tr -d '"'"'"'' \
    | awk '{ skip=0
             for(i=1;i<=NF;i++){
               if(skip){skip=0;continue}
               if($i=="-b"||$i=="-B"||$i=="--reason"){skip=1;continue}
               if($i ~ /^-/){continue}
               print $i; exit
             } }')
  case "$wt_path" in
    # 合规：项目内 .claude/worktrees/ 下（相对或绝对路径均可）
    .claude/worktrees/*|*/.claude/worktrees/*) : ;;
    # 解析不出路径就不拦 —— 宁可漏拦，不可误伤（本 hook 的一贯口径）
    "") : ;;
    *)
      emit deny worktree-path "🚨 git-worktree.md §1.0：worktree 必须建在项目内的 \`.claude/worktrees/\` 下。
本次目标路径是 \`$wt_path\` —— 不在该目录下，已拦截。

正确写法（两步，顺序不能反）：
  grep -qx '.claude/worktrees/' .gitignore || echo '.claude/worktrees/' >> .gitignore
  git worktree add .claude/worktrees/<任务名或分支名> -b <分支名>

为什么不能建在同级目录（\`../<项目名>-<名>\`、\`../wt-<名>\`）：N 个 worktree 就是 N 个同级目录、归属只能靠前缀猜、清理要散着找、删项目时残留成孤儿。
📌 已经建错的用 \`git worktree move <旧路径> .claude/worktrees/<名>\` 迁移，分支和提交不受影响。
📌 有原生 \`EnterWorktree\` 工具时优先用它（它本来就建在 \`.claude/worktrees/\`）。"
      ;;
  esac
fi

# ============ 第二档：ask（并发纪律，交用户当场判断）============

# 🚨 这两条按**仓库实际状态**判断，不是看命令长什么样。
#    实测教训：光看命令形状，每次提交都弹 ask，噪音大到用户会直接加 allow 规则
#    把整个 hook 关掉 —— 那连带的纪律也就一起没了。宁可少拦，不可被关掉。

# 空仓库的首次提交没有「别人的改动」可夹带，-A 是安全的
REPO_HAS_COMMITS=yes
git -C "$wd" rev-parse --verify HEAD >/dev/null 2>&1 || REPO_HAS_COMMITS=no

# 豁免开关：CLAUDE_SKIP_ADDALL_ASK=1/true/yes → `git add -A` 不再拦，只留审计行。
# ⚠️ 只豁免这一条。commit 夹带、checkout -- 两条 ask 与整个 deny 档不受影响 ——
#    它们各自的成本量级不同，不能共用一个开关。
case "${CLAUDE_SKIP_ADDALL_ASK:-}" in
  1|true|TRUE|yes|YES|on|ON) SKIP_ADDALL=yes ;;
  *)                         SKIP_ADDALL=no  ;;
esac

if [ "$REPO_HAS_COMMITS" = yes ] \
   && printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+add[[:space:]]+(-A([[:space:]]|$)|--all|\.([[:space:]]|$))'; then
  if [ "$SKIP_ADDALL" = yes ]; then
    logline "skip-ask" "add-all"
  else
    emit ask add-all "⚠️ git-worktree.md §3 并发纪律：**严禁 git add -A / git add .**
同分支并发提交会交错，会把别人未提交的改动夹带进你的提交。
正确做法：\`git add <本次明确改动的文件>\`，提交用 \`git commit -- <paths>\` 限定路径，提交后 \`git show --stat\` 自查有无夹带。
确认要继续吗？"
  fi
fi

# git commit 无 `--` 限定：只有**暂存区已有本次改动之外的东西**时才值得拦。
# 暂存区是空的 → 这条命令只会提交它自己 add 的内容 → 没有夹带风险 → 放行。
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+commit' \
   && ! printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+commit[^|;&]*[[:space:]]--[[:space:]]'; then
  PRESTAGED=$(git -C "$wd" diff --cached --name-only 2>/dev/null | head -8)
  if [ -n "$PRESTAGED" ]; then
    emit ask commit-unscoped "⚠️ git-worktree.md §3：\`git commit\` 提交的是**整个暂存区**，不是你刚 add 的那几个路径。
🚨 **动手前暂存区里已经有这些文件**（可能是他人或前序遗留，会被一并带走）：
$PRESTAGED

建议改成 \`git commit -m \"...\" -- <paths>\` 限定路径；提交后 \`git show --stat\` 自查有无夹带。
确认要按当前写法提交吗？"
  fi
fi
if printf '%s' "$cmd_scan" | grep -qE 'git[[:space:]]+checkout[[:space:]]+--[[:space:]]'; then
  emit ask checkout-discard "⚠️ git-worktree.md §3.2：\`git checkout -- <file>\` 会连**别人未提交的工作**一起丢掉。
动手前先 \`git diff <file>\` 逐处确认哪些改动是你自己的；若该文件在你动手前就已是 modified 状态，应改用编辑器精准删掉自己加的那几行。
确认要继续吗？"
fi

exit 0
