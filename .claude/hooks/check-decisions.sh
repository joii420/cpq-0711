#!/usr/bin/env bash
# ============================================================
# 工具（🚫 不是 harness hook）· 决策台账判定器  check-decisions.sh
#
#   落地 docs/决策台账.md 的「终态由脚本派生，不设人工状态字段」——
#   人工状态字段必然与事实漂移，而漂移**不报错**。
#
#   调用形态（契约见任务文档 api.md §1）：
#       bash check-decisions.sh [--verify [--run]] [台账路径]
#     · 位置参数可省略，省略时取 $PWD/docs/决策台账.md
#     · stdin 🚫 不读；工作目录由调用方决定，本脚本**不得 cd**
#     · 🚫 一律不写任何文件（--verify --run 也不写）
#
#   退出码：0 无异常（含台账不存在）/ 1 检出异常 / 2 用法错或不可读 / 3 验法含红线词拒绝执行
#
#   终态（本脚本是这些定义的唯一事实源，规则分册指向这里）：
#     STALE          被另一条的「取代:」指到
#     前提失效        自身任一「前提:」行以 [已证伪] 开头（任一，不是全部）
#     同对象多条生效   同一「对象:」下 ≥2 条既非 STALE 也非前提失效，且作用域有重复或含「全局」
#     前提可疑        仅 --verify --run：某条「验法:」命令退出码非 0
#     取代指向未知     「取代:」的值在台账条目与「已归档」指针段里都找不到
#                    🚨 独立告警行，不改变该条目自身终态 —— 它仍判「生效」、仍照常注入
#     生效           以上皆非
#   优先级：STALE > 前提失效 > 同对象多条生效 > 前提可疑 > 生效
#          （「取代指向未知」不在这条链上）
#
#   🚨 「被取代」≠「失效」：被顶替的裁决当初是对的，只是不再是现行口径；
#      「前提失效」那条当初就是错的。三者不许合并成一个词、也不许合并成一个数字。
#
#   stdout：每行一条记录，字段以**单个制表符**分隔 —— <终态>\t<编号或对象>\t<说明>
#           末行固定为汇总行。stderr 在一切正常路径上必须 0 字节。
#
#   🚨 三个最容易写错的地方，改本文件前先读一遍：
#     ① 判定顺序不可换：STALE > 前提失效 > 同对象多条生效 > 前提可疑 > 生效。
#        「同对象多条生效」**只在既非 STALE 也非前提失效的条目里**算 ——
#        不排除，就会让「旧裁决 + 取代它的新裁决」互报，而那恰恰是**正常的取代**。
#     ② 「任一前提失效即判」，不是「全部失效才判」。
#     ③ 「取代指向未知」是**独立告警行，不改变该条目自身的终态** ——
#        当成终态的话，一个笔误就会把一条现行裁决从上下文里静默踢掉，比不报警还糟。
#
#   ⚠️ 解析一律用 /usr/bin/grep -a 与 awk：某些环境 grep 是 ugrep -I 别名，
#      会把含大量中文的 .md **静默判为二进制并返空**（CLAUDE.md §5）。台账全是中文。
# ============================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)"

usage() {
  cat <<'USAGE'
用法: check-decisions.sh [--verify [--run]] [台账路径]

  台账路径      省略时取 $PWD/docs/决策台账.md
  --verify      列出每条前提的「验法」命令，🚫 不执行（默认 dry-run）
  --verify --run 真执行验法命令 —— 唯一的代码执行面，执行前扫红线词

退出码: 0 无异常 / 1 检出异常 / 2 用法错误或台账不可读 / 3 验法含红线词，拒绝执行
USAGE
}

# ---------------- argv ----------------
VERIFY=0; RUN=0; LEDGER=""
while [ $# -gt 0 ]; do
  case "$1" in
    --verify) VERIFY=1 ;;
    --run)    RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    --*) printf '用法错误：未知选项 %s\n' "$1" >&2; usage >&2; exit 2 ;;
    *)   if [ -n "$LEDGER" ]; then
           printf '用法错误：只接受一个台账路径（已有 %s，又给了 %s）\n' "$LEDGER" "$1" >&2; exit 2
         fi
         LEDGER="$1" ;;
  esac
  shift
done
if [ "$RUN" = 1 ] && [ "$VERIFY" = 0 ]; then
  printf '用法错误：--run 必须与 --verify 同用\n' >&2; usage >&2; exit 2
fi
[ -n "$LEDGER" ] || LEDGER="$PWD/docs/决策台账.md"

# 台账不存在 → 0 不是 2。下游项目装机后台账为空是合法初态，
# 报 2 会让每个新项目的第一次调用都失败。
if [ ! -e "$LEDGER" ]; then
  printf '台账不存在\n'
  exit 0
fi
if [ ! -f "$LEDGER" ] || [ ! -r "$LEDGER" ]; then
  printf '台账存在但不可读：%s\n' "$LEDGER" >&2
  exit 2
fi

# ---------------- 解析（api.md §1.5 解析纪律）----------------
# 只有匹配 ^## DEC-[0-9]{4} 的标题才开始一条记录；记录终止于下一个任意 '## '。
# 其他 '## ' 段（如「已归档（指针，不参与判定）」）只起终止作用，其下内容不作为条目解析，
# 仅从中提取 ~~DEC-NNNN~~ 供「取代指向未知」查表。'## ' 之前的文件头注释全部忽略。
PARSED=$(awk '
function flush(   i) {
  if (cur != "") {
    printf "E\t%s\t%s\t%s\t%s\t%s\n", cur, obj, scope, concl, sup
    for (i = 1; i <= np; i++) {
      if (pver[i] == "")
        printf "X\t格式错：%s 的前提「%s」缺少紧随的「  验法: 」子行\n", cur, ptext[i]
      else
        printf "P\t%s\t%s\t%s\t%s\n", cur, pflag[i], ptext[i], pver[i]
    }
  }
  cur=""; obj=""; scope=""; concl=""; sup=""; np=0
}
{
  line = $0
  sub(/\r$/, "", line)
  if (line ~ /^## /) {
    flush()
    if (line ~ /^## DEC-[0-9][0-9][0-9][0-9] /) { cur = substr(line, 4, 8); inentry = 1; insec = 0 }
    else                                        { inentry = 0; insec = 1 }
    next
  }
  if (inentry) {
    if (line ~ /^- 对象: /)   { obj   = substr(line, index(line, ": ") + 2); next }
    if (line ~ /^- 作用域: /) { scope = substr(line, index(line, ": ") + 2); next }
    if (line ~ /^- 结论: /)   { concl = substr(line, index(line, ": ") + 2); next }
    if (line ~ /^- 取代: /)   { sup   = substr(line, index(line, ": ") + 2); next }
    if (line ~ /^- 出处: /)   { next }
    if (line ~ /^- 前提: /) {
      np++
      t = substr(line, index(line, ": ") + 2)
      if (index(t, "[已证伪] ") == 1)    { pflag[np] = "已证伪"; t = substr(t, length("[已证伪] ") + 1) }
      else if (index(t, "[有效] ") == 1) { pflag[np] = "有效";   t = substr(t, length("[有效] ") + 1) }
      else                               { pflag[np] = "有效" }
      ptext[np] = t; pver[np] = ""
      next
    }
    if (line ~ /^  验法: /) {
      if (np == 0) { printf "X\t格式错：%s 出现「  验法: 」但其上没有「- 前提: 」\n", cur; next }
      pver[np] = substr(line, index(line, ": ") + 2)
      next
    }
    next
  }
  if (insec && match(line, /~~DEC-[0-9][0-9][0-9][0-9]~~/))
    printf "A\t%s\n", substr(line, RSTART + 2, 8)
}
END { flush() }
' "$LEDGER")

# ⚠️ 必须带 `=()` 初始化：`set -u` 下，只 declare 未赋值的数组会被当作 **unset**，
#    `${#EID[@]}` 直接报 unbound variable —— 而**空台账正是最常见的初态**（下游装机第一天）。
#    实测：不带 `=()` 时空台账下退出码 1、stderr 192 字节，AC-8 全红。
declare -a EID=() EOBJ=() ESCOPE=() ECONC=() ESUP=()
declare -a PID=() PFLAG=() PTEXT=() PVER=()
declare -A IS_ENTRY=() STALE_BY=() BADP_TEXT=() SUSPECT_TXT=() UNKNOWN_SUP=()
ARCHIVED=""
FMT_ERR=""

while IFS=$'\t' read -r kind f2 f3 f4 f5 f6; do
  case "$kind" in
    E) EID+=("$f2"); EOBJ+=("$f3"); ESCOPE+=("$f4"); ECONC+=("$f5"); ESUP+=("$f6"); IS_ENTRY["$f2"]=1 ;;
    P) PID+=("$f2"); PFLAG+=("$f3"); PTEXT+=("$f4"); PVER+=("$f5") ;;
    A) ARCHIVED="$ARCHIVED $f2" ;;
    X) FMT_ERR="${FMT_ERR}${f2}"$'\n' ;;
  esac
done <<< "$PARSED"

if [ -n "$FMT_ERR" ]; then
  printf '%s' "$FMT_ERR" >&2
  exit 2
fi

N=${#EID[@]}

# ---------------- 第 1 步：STALE（被别人的「取代」指到）----------------
# 取代关系是最明确的事实（有人显式写了「这条被那条取代」），所以优先级最高：
# 一条已被取代的旧裁决，前提塌没塌、和谁重名，都已经不重要了。
for ((i = 0; i < N; i++)); do
  sup="${ESUP[$i]}"
  [ -z "$sup" ] && continue
  case "$sup" in 无*) continue ;; esac
  tgt=$(printf '%s' "$sup" | sed -nE 's/.*(DEC-[0-9]{4}).*/\1/p')
  if [ -n "$tgt" ] && [ -n "${IS_ENTRY[$tgt]:-}" ]; then
    STALE_BY["$tgt"]="${EID[$i]}"
    continue
  fi
  # 第 5 步：取代指向未知 —— 台账条目与「已归档」指针段里**都**找不到才报。
  # 🚨 独立告警，不改变本条目自身的终态。
  found=0
  if [ -n "$tgt" ]; then
    case " $ARCHIVED " in *" $tgt "*) found=1 ;; esac
  fi
  if [ "$found" = 0 ]; then
    UNKNOWN_SUP["${EID[$i]}"]="${tgt:-$sup}"
  fi
done

# ---------------- 第 2 步：前提失效（任一前提被标 [已证伪] 即判）----------------
PN=${#PID[@]}
for ((j = 0; j < PN; j++)); do
  if [ "${PFLAG[$j]}" = "已证伪" ]; then
    id="${PID[$j]}"
    [ -n "${BADP_TEXT[$id]:-}" ] || BADP_TEXT["$id"]="${PTEXT[$j]}"
  fi
done

# ---------------- 第 3 步：同对象多条生效 ----------------
# 🚨 只在既非 STALE 也非前提失效的条目里分组，否则「旧裁决 + 取代它的新裁决」会互报。
declare -A G_IDS=() G_CNT=() G_SCOPES=() G_FLAG=() G_FIRST=()
for ((i = 0; i < N; i++)); do
  id="${EID[$i]}"
  [ -n "${STALE_BY[$id]:-}" ] && continue
  [ -n "${BADP_TEXT[$id]:-}" ] && continue
  o="${EOBJ[$i]}"
  if [ -z "${G_CNT[$o]:-}" ]; then G_CNT["$o"]=0; G_IDS["$o"]=""; G_SCOPES["$o"]=""; G_FIRST["$o"]=$i; fi
  G_CNT["$o"]=$(( G_CNT["$o"] + 1 ))
  G_IDS["$o"]="${G_IDS[$o]}${G_IDS[$o]:+, }$id"
  G_SCOPES["$o"]="${G_SCOPES[$o]}|${ESCOPE[$i]}"
done
# ⚠️ 变量名不叫 GROUPS：GROUPS 是 bash 的**内建数组**（当前用户的组 ID），
#    赋值被静默忽略，$GROUPS 会取到组 ID（实测 1000）—— 汇总行会出现「同对象多条生效 1000 组」
#    且退出码恒为 1，**不报任何错**。本 bug 在开发自检时实际发生过。
N_GROUP=0
for o in "${!G_CNT[@]}"; do
  [ "${G_CNT[$o]}" -ge 2 ] || continue
  hit=0
  case "${G_SCOPES[$o]}" in *全局*) hit=1 ;; esac
  if [ "$hit" = 0 ]; then
    dup=$(printf '%s' "${G_SCOPES[$o]}" | tr '|' '\n' | sed '/^$/d' | sort | uniq -d)
    [ -n "$dup" ] && hit=1
  fi
  if [ "$hit" = 1 ]; then G_FLAG["$o"]=1; N_GROUP=$(( N_GROUP + 1 )); fi
done

in_flagged_group() { [ -n "${G_FLAG[$1]:-}" ]; }

# ---------------- --verify 的安全护栏（api.md §1.6）----------------
# 🚨 词表**照抄 guard-redline.sh 第一档 deny**（2026-09-11），🚫 不要另写一份 —— 两份词表必然漂移。
#    为什么不能指望 guard-redline.sh 自己拦：它是 PreToolUse(Bash) hook，拦的是**模型发出的**
#    Bash 工具调用，**拦不到本脚本内部 fork 出的子进程** ⇒ --verify --run 会完全绕过 §3.2 硬拦。
#    这一层没有别人兜。
# ⚠️ 红线词扫描是**必要不充分**的（拦得住明文，拦不住 eval/base64 等变形）——
#    所以护栏①「session-brief.sh 绝不调用 --verify」才是地基：它把执行面从「每个会话自动」
#    压到「人显式发起一次」。
NOLOG_DIR="/nonexistent-check-decisions-nolog"

# 按 ; && || | 与 $( ) 切段，丢掉只读段（白名单照抄 guard-redline.sh），剩下的才做红线匹配。
# 🔎 比 guard 多切一刀 $( )：验法里 `test "$(...)"` 是常见写法，而 test 在只读白名单里 ——
#    不把命令替换的内容单独切出来，藏在里面的红线会被整段丢掉。**同一份词表，更大的扫描面。**
danger_of() {
  local c="$1" seg verb out="" OLD_IFS
  c=$(printf '%s' "$c" | sed -E 's/\$\(/;/g; s/\)/;/g; s/&&/;/g; s/\|\|/;/g; s/\|/;/g' | tr '\n' ';')
  OLD_IFS=$IFS; IFS=';'
  for seg in $c; do
    seg=$(printf '%s' "$seg" | sed -E 's#^([[:space:]]*)[^[:space:]]*/([^[:space:]/]+)#\1\2#')
    verb=$(printf '%s' "$seg" | sed -E 's/^[[:space:]]*//; s/[[:space:]].*//')
    case "$verb" in
      grep|rg|ag|cat|head|tail|less|more|ls|find|wc|file|stat|diff|echo|printf|jq|which|type|env|date|pwd|cd|true|test|export)
        continue ;;
      git)
        case "$seg" in
          *" log"*|*" diff"*|*" status"*|*" show"*|*" blame"*|*" rev-parse"*|*" branch --contains"*|*" worktree list"*|*" config --get"*)
            continue ;;
        esac ;;
    esac
    out="$out; $seg"
  done
  IFS=$OLD_IFS
  printf '%s' "$out"
}

# 命中时把**红线词原文**打到 stdout（给人看的判据），未命中返回 1
redline_hit() {
  local cmd="$1" d u
  d=$(danger_of "$cmd")
  if [ -n "$d" ]; then
    u=$(printf '%s' "$d" | tr '[:lower:]' '[:upper:]')
    if printf '%s' "$d" | /usr/bin/grep -qaE '(^|[;&|[:space:]])rm[[:space:]]+(-[a-zA-Z]*[rR][a-zA-Z]*[fF]|-[a-zA-Z]*[fF][a-zA-Z]*[rR])'; then printf 'rm -rf'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE 'git[[:space:]]+reset[[:space:]]+.*--hard'; then printf 'git reset --hard'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE 'git[[:space:]]+push' \
       && printf '%s' "$d" | /usr/bin/grep -qaE '(--force|--force-with-lease|(^|[[:space:]])-f([[:space:]]|$))'; then printf 'git push --force'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE 'git[[:space:]]+clean[[:space:]]+-[a-zA-Z]*[fd]'; then printf 'git clean -fd'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE 'git[[:space:]]+branch[[:space:]]+.*(-[dD]([[:space:]]|$)|--delete)'; then printf 'git branch -d'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE 'git[[:space:]]+rebase' \
       && ! printf '%s' "$d" | /usr/bin/grep -qaE 'rebase[[:space:]]+--(abort|continue|skip)'; then printf 'git rebase'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE '(^|[;&|[:space:]])(rm|mv)[[:space:]]' \
       && printf '%s' "$d" | /usr/bin/grep -qaiE 'migration|migrations|db/migrate|flyway|liquibase'; then printf '改名/移动/删除迁移文件'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE '(node|ts-node|tsx|bun|deno)[[:space:]]+(-e|--eval|-p)' \
       && printf '%s' "$d" | /usr/bin/grep -qaiE '(require|import)[^)]*(seed|migrat|reset|teardown|setup|bootstrap|fixture)'; then printf 'eval 引入 seed/migrate 类脚本'; return 0; fi
    if printf '%s' "$d" | /usr/bin/grep -qaE '\.deleteMany\(\s*(\)|\{\s*\})|\.destroy\(\s*\{\s*(where\s*:\s*\{\s*\})?\s*\}|\.truncate\('; then printf 'deleteMany()/truncate()'; return 0; fi
    if printf '%s' "$u" | /usr/bin/grep -qaE 'DROP[[:space:]]+(TABLE|VIEW|SCHEMA|DATABASE|INDEX|TYPE|SEQUENCE)'; then printf 'DROP'; return 0; fi
    if printf '%s' "$u" | /usr/bin/grep -qaE '(^|[^A-Z_])TRUNCATE([^A-Z_]|$)'; then printf 'TRUNCATE'; return 0; fi
    if printf '%s' "$u" | /usr/bin/grep -qaE 'DELETE[[:space:]]+FROM' \
       && ! printf '%s' "$u" | /usr/bin/grep -qaE 'DELETE[[:space:]]+FROM.*WHERE'; then printf '无 WHERE 的 DELETE'; return 0; fi
    if printf '%s' "$u" | /usr/bin/grep -qaE 'UPDATE[[:space:]]+[A-Z_."]+[[:space:]]+SET' \
       && ! printf '%s' "$u" | /usr/bin/grep -qaE 'WHERE'; then printf '无 WHERE 的 UPDATE'; return 0; fi
    if printf '%s' "$u" | /usr/bin/grep -qaE '(DROPDB|DROP[[:space:]]+DATABASE|FLYWAY[[:space:]]+CLEAN|PRISMA[[:space:]]+MIGRATE[[:space:]]+RESET|DB:RESET|SCHEMA:DROP)'; then printf '清库 / 重建库'; return 0; fi
  fi
  # 兜底：把同一条命令交给 guard-redline.sh 的 deny 档再判一次（词表的唯一事实源）。
  # CLAUDE_PROJECT_DIR 指向不存在的目录 ⇒ 它的审计行写不进任何文件（本脚本一律不写文件）。
  if [ -f "$SELF_DIR/guard-redline.sh" ] && command -v jq >/dev/null 2>&1; then
    local payload
    payload=$(jq -cn --arg c "$cmd" '{tool_input:{command:$c}}' 2>/dev/null)
    if [ -n "$payload" ] && printf '%s' "$payload" \
        | CLAUDE_PROJECT_DIR="$NOLOG_DIR" bash "$SELF_DIR/guard-redline.sh" 2>/dev/null \
        | /usr/bin/grep -qa '"permissionDecision"[[:space:]]*:[[:space:]]*"deny"'; then
      printf 'guard-redline deny 档'
      return 0
    fi
  fi
  return 1
}

# 验法是否参与 --verify：逐字以「无」开头的不参与（业务裁决类前提）
verifiable() { case "$1" in ""|无*) return 1 ;; *) return 0 ;; esac; }

if [ "$VERIFY" = 1 ]; then
  # 🚨 护栏③：执行**前**扫红线词，命中即拒绝**整次** --verify（不是跳过那一条继续跑）
  refused=""
  for ((j = 0; j < PN; j++)); do
    verifiable "${PVER[$j]}" || continue
    if word=$(redline_hit "${PVER[$j]}"); then
      refused="${refused}$(printf '红线拒绝\t%s\t验法命中红线词【%s】，按 CLAUDE.md §3.2 拒绝执行整次 --verify —— 验法: %s' "${PID[$j]}" "$word" "${PVER[$j]}")"$'\n'
    fi
  done
  if [ -n "$refused" ]; then
    printf '%s' "$refused"
    exit 3
  fi
fi

# ---------------- 第 4 步：前提可疑（仅 --verify --run）----------------
SUSPECTS=0
if [ "$VERIFY" = 1 ] && [ "$RUN" = 1 ]; then
  for ((i = 0; i < N; i++)); do
    id="${EID[$i]}"
    [ -n "${STALE_BY[$id]:-}" ] && continue
    [ -n "${BADP_TEXT[$id]:-}" ] && continue
    in_flagged_group "${EOBJ[$i]}" && continue
    for ((j = 0; j < PN; j++)); do
      [ "${PID[$j]}" = "$id" ] || continue
      verifiable "${PVER[$j]}" || continue
      bash -c "${PVER[$j]}" >/dev/null 2>&1
      rc=$?
      if [ "$rc" -ne 0 ]; then
        SUSPECT_TXT["$id"]="${PTEXT[$j]} · 验法: ${PVER[$j]} · 验法退出码=$rc"
        SUSPECTS=$(( SUSPECTS + 1 ))
        break
      fi
    done
  done
fi

# ---------------- 输出 ----------------
LIVE=0; STALES=0; BADS=0; UNKNOWNS=0
OUT=""
add() { OUT="${OUT}$1"$'\n'; }

for ((i = 0; i < N; i++)); do
  id="${EID[$i]}"; obj="${EOBJ[$i]}"; scope="${ESCOPE[$i]}"; concl="${ECONC[$i]}"
  if [ -n "${STALE_BY[$id]:-}" ]; then
    STALES=$(( STALES + 1 ))
    add "$(printf 'STALE\t%s\t被 %s 取代 · 对象: %s' "$id" "${STALE_BY[$id]}" "$obj")"
  elif [ -n "${BADP_TEXT[$id]:-}" ]; then
    BADS=$(( BADS + 1 ))
    add "$(printf '前提失效\t%s\t前提已证伪：%s' "$id" "${BADP_TEXT[$id]}")"
  elif in_flagged_group "$obj"; then
    if [ "${G_FIRST[$obj]}" = "$i" ]; then
      add "$(printf '同对象多条生效\t%s\t%s 同为「%s」的现行裁决，作用域有重复或含全局，请确认是否互相矛盾' "$obj" "${G_IDS[$obj]}" "$obj")"
    fi
  elif [ -n "${SUSPECT_TXT[$id]:-}" ]; then
    add "$(printf '前提可疑\t%s\t%s' "$id" "${SUSPECT_TXT[$id]}")"
  else
    LIVE=$(( LIVE + 1 ))
    add "$(printf '生效\t%s\t%s | %s | %s' "$id" "$obj" "$scope" "$concl")"
    if [ "$VERIFY" = 1 ] && [ "$RUN" = 0 ]; then
      for ((j = 0; j < PN; j++)); do
        [ "${PID[$j]}" = "$id" ] || continue
        verifiable "${PVER[$j]}" || continue
        add "$(printf '待验\t%s\t%s' "$id" "${PVER[$j]}")"
      done
    fi
  fi
  # 🚨 独立告警行：与该条目自己的终态**并存**输出，不改变它
  if [ -n "${UNKNOWN_SUP[$id]:-}" ]; then
    UNKNOWNS=$(( UNKNOWNS + 1 ))
    add "$(printf '取代指向未知\t%s\t取代字段指向 %s，既不在台账条目中也不在已归档指针中' "$id" "${UNKNOWN_SUP[$id]}")"
  fi
done

printf '%s' "$OUT"
summary=$(printf '台账：生效 %d 条 / STALE %d 条 / 前提失效 %d 条 / 同对象多条生效 %d 组 / 取代指向未知 %d 条' \
  "$LIVE" "$STALES" "$BADS" "$N_GROUP" "$UNKNOWNS")
if [ "$VERIFY" = 1 ] && [ "$RUN" = 1 ]; then
  summary="${summary} / 前提可疑 ${SUSPECTS} 条"
fi
printf '%s\n' "$summary"

if [ $(( STALES + BADS + N_GROUP + UNKNOWNS + SUSPECTS )) -gt 0 ]; then
  exit 1
fi
exit 0
