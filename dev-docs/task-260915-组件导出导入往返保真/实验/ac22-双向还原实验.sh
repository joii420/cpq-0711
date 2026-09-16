#!/usr/bin/env bash
# =====================================================================================
# task-260915 · 分片 S-B · AC-22 双向赋值覆盖守门的还原实验
#
# AC-22 要四件：
#   ① 正向：守门跑通，两行输出都是空集合
#   ② 前置（防空验证）：夹具所有持久化字段非 null，且测试自身先核验
#   ③ 阳性对照 a（导出端）：注释掉导出端任一字段赋值 → 变红且点名该字段 → 恢复变绿
#   ④ 阳性对照 b（导入端）：同上，导入端
#
# 本脚本额外做一件（实验 c）：把夹具里某个字段置 null，确认 ② 的「夹具没填满」自检**真的会触发**。
#   —— 防空验证条款本身也需要被证伪，否则「测试自身会核验」只是一句写在代码里的话。
#
# 🚨 纪律
#   - 会临时改 2 个实现文件 + 1 个测试文件；trap 覆盖正常退出 / Ctrl-C / kill，必还原
#   - 🚫 绝不用 git stash（仓库 stash 栈全仓共享）—— 用 mktemp 备份原文件再 cp 回去
#   - 还原核验：git status/diff 为准，另加「去掉注释行后再 grep 实验标记」的双保险
#     （只 grep 原文会把注释里的历史文本当成残留 —— 后端上一轮就这么误判过一次）
# =====================================================================================
set -uo pipefail

WT="/home/joii/project/cpq/.claude/worktrees/task-260915-export-fidelity"
BACKEND="$WT/cpq-backend"
EXP_SVC="$BACKEND/src/main/java/com/cpq/component/service/ComponentExportService.java"
IMP_SVC="$BACKEND/src/main/java/com/cpq/component/service/ComponentImportService.java"
GUARD_SRC="$BACKEND/src/test/java/com/cpq/component/service/Task260915AssignmentCoverageTest.java"
GUARD="com.cpq.component.service.Task260915AssignmentCoverageTest"
EVID="$WT/dev-docs/task-260915-组件导出导入往返保真/证据"
MARK="AC-22 实验临时注释"
LOG="$EVID/AC-22-双向还原实验-$(date +%y%m%d-%H%M%S).log"

mkdir -p "$EVID"
exec > >(tee -a "$LOG") 2>&1
echo "=== AC-22 双向还原实验 $(date '+%F %T') ==="

REL_EXP="cpq-backend/src/main/java/com/cpq/component/service/ComponentExportService.java"
REL_IMP="cpq-backend/src/main/java/com/cpq/component/service/ComponentImportService.java"
REL_GUARD="cpq-backend/src/test/java/com/cpq/component/service/Task260915AssignmentCoverageTest.java"

# ── 前置 0：锚定"实验前的原样" ────────────────────────────────────────────────
# ⚠️ 与 AC-7 那次不同：本任务的两个 service 现在是 `M`（后端本轮的在途改动，尚未 commit），
#    守门测试是 `??`（未跟踪）。所以**不能**要求 git 工作区干净，也不能拿 `git diff --stat 为空`
#    当还原证据 —— 那对这三个文件永远不成立，还会把「后端的在途改动」误当成「我没还原」。
# ✅ 改用更强的判据：实验前算三个文件的 sha256，实验后逐一比对**字节完全一致**。
#    （cp 备份→cp 还原是字节级的，与 git 状态无关。）
echo "--- 前置 0：记录实验前的文件指纹 ---"
( cd "$WT" && git status --porcelain -- "$REL_EXP" "$REL_IMP" "$REL_GUARD" ) || true
SHA_BEFORE=$(sha256sum "$EXP_SVC" "$IMP_SVC" "$GUARD_SRC")
echo "$SHA_BEFORE"

BAK_EXP=$(mktemp); BAK_IMP=$(mktemp); BAK_GUARD=$(mktemp)
cp "$EXP_SVC" "$BAK_EXP"; cp "$IMP_SVC" "$BAK_IMP"; cp "$GUARD_SRC" "$BAK_GUARD"
restore_all() {
  cp "$BAK_EXP" "$EXP_SVC"; cp "$BAK_IMP" "$IMP_SVC"; cp "$BAK_GUARD" "$GUARD_SRC"
  echo "↩️  已还原 3 个文件"
}
trap restore_all EXIT INT TERM

OK=1

run_guard() {   # $1 = 阶段标签 ; 输出文件 $EVID/.ac22-$1.out ; 返回 maven 退出码
  ( cd "$BACKEND" && ./mvnw -o test -Dtest="$GUARD" -DfailIfNoTests=false ) > "$EVID/.ac22-$1.out" 2>&1
  return $?
}

comment_out() {  # $1=文件 $2=原样一行（去首尾空白后的内容）
  python3 - "$1" "$2" "$MARK" <<'PY'
import sys
path, needle, mark = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path, encoding='utf-8').read().split('\n')
hits = [i for i, l in enumerate(lines) if l.strip() == needle]
assert len(hits) == 1, f"锚点行命中 {len(hits)} 次（需恰好 1 次）: {needle}"
i = hits[0]
indent = lines[i][:len(lines[i]) - len(lines[i].lstrip())]
lines[i] = f"{indent}// [{mark}] {lines[i].strip()}"
open(path, 'w', encoding='utf-8').write('\n'.join(lines))
print(f"已注释掉 {path}:{i+1} -> {lines[i].strip()}")
PY
}

# ══ ① 正向基线 ═══════════════════════════════════════════════════════════════
echo "--- ① 正向基线 ---"
run_guard step1; RC=$?
echo "[① 基线] maven 退出码=$RC"
/usr/bin/grep -a -E "\[B-12 (导出|导入)\]" "$EVID/.ac22-step1.out"
if [ $RC -ne 0 ]; then echo "❌ 正向基线就不是绿的，后面的对照没有意义"; OK=0; fi
/usr/bin/grep -aq "\[B-12 导出\] item 为 null 的字段=\[\] ; sqlView 为 null 的字段=\[\]" "$EVID/.ac22-step1.out" \
  && echo "✅ 导出侧输出为空集合" || { echo "❌ 导出侧输出不是空集合"; OK=0; }
/usr/bin/grep -aq "\[B-12 导入\] component 丢失字段=\[\] ; component_sql_view 丢失字段=\[\]" "$EVID/.ac22-step1.out" \
  && echo "✅ 导入侧输出为空集合" || { echo "❌ 导入侧输出不是空集合"; OK=0; }

# ══ ③ 阳性对照 a：导出端漏赋值 ════════════════════════════════════════════════
echo "--- ③ 阳性对照 a：注释掉导出端 item.elementCodeField 赋值 ---"
comment_out "$EXP_SVC" "item.elementCodeField = c.elementCodeField;" || { echo "❌ 注入失败"; exit 2; }
run_guard step3a; RC=$?
echo "[③ 导出端注释后] maven 退出码=$RC"
if [ $RC -eq 0 ]; then
  echo "❌ 导出端漏了 elementCodeField 的赋值，守门仍然是绿的 ⇒ 这条防线不会触发"
  OK=0
else
  echo "✅ 变红"
  /usr/bin/grep -a -m3 "elementCodeField" "$EVID/.ac22-step3a.out"
  if /usr/bin/grep -aq "导出端漏了 1 个字段的赋值：\[elementCodeField\]" "$EVID/.ac22-step3a.out"; then
    echo "✅ 失败信息点名了 elementCodeField（导出端）"
  else
    echo "❌ 变红但没点名 elementCodeField —— 可能红在别的原因，人工看 .ac22-step3a.out"
    OK=0
  fi
fi
cp "$BAK_EXP" "$EXP_SVC"; echo "↩️ 已恢复导出端"

# ══ ④ 阳性对照 b：导入端漏赋值 ════════════════════════════════════════════════
echo "--- ④ 阳性对照 b：注释掉导入端 c.elementCodeField 赋值 ---"
comment_out "$IMP_SVC" "c.elementCodeField = it.elementCodeField;" || { echo "❌ 注入失败"; exit 2; }
run_guard step4b; RC=$?
echo "[④ 导入端注释后] maven 退出码=$RC"
if [ $RC -eq 0 ]; then
  echo "❌ 导入端漏了 elementCodeField 的赋值，守门仍然是绿的 ⇒ 只堵住了导出端那一半"
  OK=0
else
  echo "✅ 变红"
  /usr/bin/grep -a -m3 "elementCodeField" "$EVID/.ac22-step4b.out"
  if /usr/bin/grep -aq "导入端漏了 1 个字段的赋值：\[elementCodeField\]" "$EVID/.ac22-step4b.out"; then
    echo "✅ 失败信息点名了 elementCodeField（导入端）"
  else
    echo "❌ 变红但没点名 elementCodeField —— 人工看 .ac22-step4b.out"
    OK=0
  fi
fi
cp "$BAK_IMP" "$IMP_SVC"; echo "↩️ 已恢复导入端"

# ══ ② 防空验证自证：夹具留一个 null，必须先报「夹具没填满」═══════════════════════
echo "--- ② 防空验证自证：把夹具的 elementCurrencyField 置 null ---"
python3 - "$GUARD_SRC" "$MARK" <<'PY'
import sys
path, mark = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
old = 'c.elementCurrencyField = "币种";'
assert s.count(old) == 1, f"锚点命中 {s.count(old)} 次"
s = s.replace(old, f'c.elementCurrencyField = null;  // [{mark}]')
open(path, 'w', encoding='utf-8').write(s)
print("已把夹具的 elementCurrencyField 置 null")
PY
run_guard step2; RC=$?
echo "[② 夹具留 null 后] maven 退出码=$RC"
if [ $RC -eq 0 ]; then
  echo "❌ 夹具有 null 字段，测试却是绿的 ⇒ 「夹具自检」根本没跑，正向那条绿是空验证"
  OK=0
else
  if /usr/bin/grep -aq "夹具没填满 Component 的字段" "$EVID/.ac22-step2.out"; then
    echo "✅ 先报「夹具没填满，本测试会变成空验证」而不是直接绿"
    /usr/bin/grep -a -m2 "夹具没填满" "$EVID/.ac22-step2.out"
  else
    echo "❌ 变红了但不是因为夹具自检 —— 人工看 .ac22-step2.out"
    OK=0
  fi
fi
cp "$BAK_GUARD" "$GUARD_SRC"; echo "↩️ 已恢复守门测试文件"

# ══ 还原后复跑：必须恢复绿（确认不是恒红）═════════════════════════════════════
echo "--- 还原后复跑 ---"
run_guard step5restored; RC=$?
echo "[还原后] maven 退出码=$RC"
[ $RC -eq 0 ] && echo "✅ 恢复绿（不是恒红）" || { echo "❌ 全部还原后仍是红的"; OK=0; }

trap - EXIT INT TERM

# ══ 还原证据 ════════════════════════════════════════════════════════════════
echo "--- 还原判据①：三个文件的 sha256 必须与实验前逐字节相同 ---"
SHA_AFTER=$(sha256sum "$EXP_SVC" "$IMP_SVC" "$GUARD_SRC")
echo "$SHA_AFTER"
if [ "$SHA_BEFORE" = "$SHA_AFTER" ]; then
  LEFT=""
  echo "✅ 三个文件与实验前逐字节相同"
else
  LEFT="sha256 不一致"
  echo "❌❌ 文件指纹与实验前不同！"
  diff <(echo "$SHA_BEFORE") <(echo "$SHA_AFTER")
fi
echo "--- 还原判据②（参考）：git status / git diff --stat ---"
echo "  ⚠️ 这两个文件本来就是 M（后端本轮在途改动，尚未 commit），守门测试本来就是 ?? —— "
echo "     所以这里**不为空是正常的**，判据以上面的 sha256 为准。"
( cd "$WT" && git status --porcelain -- "$REL_EXP" "$REL_IMP" "$REL_GUARD" )
( cd "$WT" && git diff --stat -- "$REL_EXP" "$REL_IMP" )

echo "--- 双保险：去掉注释行后再 grep 实验标记（避免把注释里的历史文本当残留）---"
for f in "$EXP_SVC" "$IMP_SVC" "$GUARD_SRC"; do
  n=$(sed 's://.*::' "$f" | /usr/bin/grep -ac "$MARK")
  echo "  $(basename "$f") 去注释后残留标记数=$n"
  [ "$n" != "0" ] && { echo "  ❌ 代码里还有实验标记"; OK=0; }
done

if [ -n "$LEFT" ]; then
  echo "❌❌ 文件没还原干净（$LEFT）—— 立即人工处理"
  exit 3
fi
echo "✅ 三个文件均已还原（字节级比对通过）"

[ $OK -eq 1 ] && { echo "=== AC-22：四件全部满足（正向 + 防空自证 + 导出端对照 + 导入端对照），文件已还原 ==="; exit 0; }
echo "=== AC-22：未全部满足，看上面的 ❌ 行 ==="
exit 1
