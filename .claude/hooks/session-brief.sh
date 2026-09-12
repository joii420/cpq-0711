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
  # 🚨 只注入「够识别 + 够定向」的条目头，不注入全文。
  #    RECORD 单条中位 1353 字符、最长 2541；原先 `tail -8` 全文 = **20642 字符**，
  #    占整个 session-brief 注入量的 **82%**，而每个会话开局都要付一次。
  #    CLAUDE.md 的口径本就是「hook 已注入摘要 —— 别再整篇重读，要细节时定向读」，
  #    注入全文恰恰让「定向读」失去意义。
  # 🚨 用**定长字符截断**，不解析 `- **标题**` 结构：RECORD 条目至少有三种格式变体
  #    （`[日期] 模块 - **标题**` / `[日期] 长描述（括号说明） - 正文` / `[日期] 同上任务 - …`），
  #    解析式提取在格式变化时会**静默产出垃圾**（实测把正文里第一个粗体当成了标题）。
  #    定长截断格式无关：最坏只是「少看几个字」，不会错。
  # ⚠️ 必须 `/usr/bin/grep -a` —— 某些环境 `grep` 是 ugrep 的别名/函数，其 `-I` 会把中文
  #    密集的 .md **静默判为二进制返空**（见 `docs/决策台账.md` · DEC-0002）。
  # ⚠️ 截断必须在 UTF-8 locale 下做：GNU sed 的 `.` 在 C/POSIX locale 下按**字节**匹配，
  #    实测 30 行里 **27 行**被切出非法 UTF-8 字节序列。🚨 且 `grep -c '�'` **检测不到** ——
  #    切坏的是不完整字节序列，不是 U+FFFD 码点，只有 `iconv -f UTF-8 -t UTF-8` 验得出来。
  #    hook 执行环境不保证设了 LANG，所以这里显式挑一个；一个都挑不到就**退回不截断**
  #    （宁可长，不可乱码）。
  REC_N=25; REC_W=115; REC_LC=""
  for c in C.utf8 C.UTF-8 en_US.utf8 en_US.UTF-8; do
    if locale -a 2>/dev/null | /usr/bin/grep -qxa "$c"; then REC_LC="$c"; break; fi
  done
  if [ -n "$REC_LC" ]; then
    recent=$(/usr/bin/grep -aE '^[[:space:]]*[-*]?[[:space:]]*\[[0-9]' "$REC" 2>/dev/null | tail -n "$REC_N" \
      | LC_ALL="$REC_LC" sed -E "s/^[[:space:]]*[-*]?[[:space:]]*//; s/\*\*//g; s/^(.{${REC_W}}).+$/\1…/" || true)
    rec_hdr="最近 ${REC_N} 条 · 仅条目头（细节定向读原文）"
  else
    recent=$(/usr/bin/grep -aE '^[[:space:]]*[-*]?[[:space:]]*\[[0-9]' "$REC" 2>/dev/null | tail -6 || true)
    rec_hdr="最近 6 条 · 全文（未找到 UTF-8 locale，已退回不截断）"
  fi
  [ -n "$recent" ] && out="${out}
── docs/RECORD.md · ${rec_hdr} ──
${recent}
"
fi

# ---- 新增段：docs/决策台账.md 现行裁决 ----
# 🚨 按**有效性**注入，不按时间截断 —— 上面 RECORD 那段的 `tail -8` 恰恰是要避开的毛病：
#    它挑的是「新」，不是「还有效」。367 条流水账里抽 8 条最新的，抽不中那条还在管事的旧裁决。
# 🚨 **绝不传 --verify** —— 会话开局**不执行**台账里写的任何验法命令（安全护栏①）。
#    传了就等于每个会话开局自动执行任意命令，而那是本次交付唯一的代码执行面。
# ⚠️ **必须忽略 check-decisions.sh 的非零退出码** —— 检出非现行条目是正常业务结果，不是 hook 失败。
# ⚠️ 用**相对自身脚本位置**解析兄弟脚本：本文件装到下游后位于 <项目>/.claude/hooks/，
#    硬编码开发机路径会让它在所有下游项目里静默失效。
CD="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)/check-decisions.sh"
DEC="$ROOT/docs/决策台账.md"
if [ -f "$CD" ] && [ -f "$DEC" ]; then
  dec_out=$(bash "$CD" "$DEC" 2>/dev/null || true)
  # 记录行恰好 3 字段（制表符分隔）；汇总行与「台账不存在」都只有 1 字段，天然被排除。
  dec_rec=$(printf '%s\n' "$dec_out" | awk -F'\t' 'NF>=3{c++} END{print c+0}')
  # 台账不存在 / 无条目 → **整段不输出**，连标题行也不输出。
  if [ "$dec_rec" -gt 0 ]; then
    dec_live=$(printf '%s\n' "$dec_out" | awk -F'\t' '$1=="生效"{print $3}')
    dec_x=$(printf '%s\n' "$dec_out" | awk -F'\t' '$1=="STALE"{c++} END{print c+0}')
    dec_y=$(printf '%s\n' "$dec_out" | awk -F'\t' '$1=="前提失效"{c++} END{print c+0}')
    # 「同对象多条生效」是**组**级记录，第 3 字段列着该组全部编号 —— 这里按编号个数折成**条数**，
    # 🚨 不是组数：末行的 N 必须等于 X+Y+Z，读的人才对得上账。
    dec_z=$(printf '%s\n' "$dec_out" | awk -F'\t' '$1=="同对象多条生效"{n+=gsub(/DEC-[0-9][0-9][0-9][0-9]/,"&",$3)} END{print n+0}')
    dec_n=$(( dec_x + dec_y + dec_z ))
    out="${out}
── docs/决策台账.md · 现行裁决（按有效性注入，非按时间截断）──"
    [ -n "$dec_live" ] && out="${out}
${dec_live}"
    # 🔧 措辞是定死的：「被取代」≠「失效」—— 被顶替的裁决**当初完全正确**，只是不再是现行口径；
    #    「前提失效」那条**当初就是错的**。合并成一个词再合并成一个数字，
    #    会让下一个读的人以为前者当初也错了。⇒ 三类分开计数，🚫 不许写「失效裁决」。
    out="${out}
已略过 ${dec_n} 条非现行裁决（被取代 ${dec_x} / 前提失效 ${dec_y} / 同对象多条 ${dec_z}）
"
  fi
fi

[ -n "$out" ] || exit 0

printf '%s\tsession-brief\tinjected\tINDEX=%s BACKLOG_todo=%s\n' \
  "$(date -Is)" "$([ -f "$IDX" ] && echo yes || echo missing)" "${n:-0}" \
  2>/dev/null >> "$ROOT/.claude/hooks.log" || true

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
