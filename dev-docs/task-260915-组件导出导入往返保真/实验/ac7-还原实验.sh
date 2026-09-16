#!/usr/bin/env bash
# =====================================================================================
# task-260915 · 分片 S-B · AC-7 还原实验（falsification experiment）
#
# AC-7 原文：
#   操作：在 Component 实体上临时加一个持久化字段 foo，不改 ComponentExportBundle.Item，跑测试
#   断言：测试失败，失败信息点名 foo 未在导出 DTO 中登记，且提示"带上或加入白名单并注明理由"
#   反向断言：把 foo 删掉后测试恢复绿（确认不是恒红）
#
# 为什么必须做这件事：AC-7 验的是一个**防御机制**，而防御机制最典型的失效形态是
# 「它其实从来不会触发」。只跑一次看到绿，等于什么都没验证。
#
# 🚨 安全纪律
#   - 本脚本会**临时改一个实现文件**（Component.java），这是 S-B 片唯一允许的实现改动。
#   - 退出前一定还原：trap 覆盖正常退出 / Ctrl-C / kill；还原后再校验 git 工作区是否干净。
#   - 🚫 不碰数据库、不删任何东西、不 commit。
#
# 用法（在 worktree 内任意目录执行均可，脚本自己定位路径）：
#   bash dev-docs/task-260915-组件导出导入往返保真/实验/ac7-还原实验.sh
# =====================================================================================
set -uo pipefail

WT="/home/joii/project/cpq/.claude/worktrees/task-260915-export-fidelity"
BACKEND="$WT/cpq-backend"
ENTITY="$BACKEND/src/main/java/com/cpq/component/entity/Component.java"
EVID="$WT/dev-docs/task-260915-组件导出导入往返保真/证据"
LOG="$EVID/AC-7-还原实验-$(date +%y%m%d-%H%M%S).log"

mkdir -p "$EVID"
exec > >(tee -a "$LOG") 2>&1

echo "=== AC-7 还原实验 $(date '+%F %T') ==="
echo "worktree : $WT"
echo "entity   : $ENTITY"

[ -f "$ENTITY" ] || { echo "❌ 找不到实体文件，停"; exit 2; }

# ── 前置 0：工作区必须是干净的，否则还原校验无从判断 ─────────────────────────────
DIRTY=$(cd "$WT" && git status --porcelain -- "cpq-backend/src/main/java/com/cpq/component/entity/Component.java")
if [ -n "$DIRTY" ]; then
  echo "❌ Component.java 当前已有未提交改动：$DIRTY"
  echo "   实验会改这个文件，带着别人的在途改动做实验，还原时分不清该还原到哪一版 —— 停下来报主线。"
  exit 2
fi

# ── 前置 1：定位 B-7 的反射契约测试（不写死类名，按内容发现）───────────────────
mapfile -t GUARDS < <(cd "$BACKEND/src/test/java" && /usr/bin/grep -arl "ComponentExportBundle" . \
  | grep -v "/com/cpq/task260915/" \
  | xargs -r /usr/bin/grep -al "getDeclaredFields" \
  | sed 's|^\./||; s|\.java$||; s|/|.|g')

if [ "${#GUARDS[@]}" -eq 0 ]; then
  echo "❌ 找不到 B-7 的反射契约测试（同时含 ComponentExportBundle + getDeclaredFields 的测试类）"
  echo "   没有靶子就做不了还原实验 —— 停下来报主线，不要自己写一个来顶替。"
  exit 2
fi
GUARD_LIST=$(IFS=,; echo "${GUARDS[*]}")
echo "靶子测试类：$GUARD_LIST"

BAK="$(mktemp)"
cp "$ENTITY" "$BAK"
restore() {
  cp "$BAK" "$ENTITY"
  echo "↩️  已还原 $ENTITY"
}
trap restore EXIT INT TERM

run_guard() {   # $1 = 阶段名
  echo "--- 跑靶子测试（$1）---"
  ( cd "$BACKEND" && ./mvnw test -Dtest="$GUARD_LIST" -DfailIfNoTests=false ) > "$EVID/.ac7-$1.out" 2>&1
  echo "退出码=$?" | tee /dev/stderr
  return 0
}

# ══ 步骤 1：基线 —— 未注入时必须绿（证明靶子本身可跑）═══════════════════════════
( cd "$BACKEND" && ./mvnw test -Dtest="$GUARD_LIST" -DfailIfNoTests=false ) > "$EVID/.ac7-step1-baseline.out" 2>&1
RC1=$?
echo "[步骤1 基线] 退出码=$RC1"
if [ $RC1 -ne 0 ]; then
  echo "❌ 基线就不是绿的 —— 先让 B-7 的测试在未注入状态下通过，再做还原实验"
  tail -40 "$EVID/.ac7-step1-baseline.out"
  exit 1
fi

# ══ 步骤 2：注入 foo（只加实体字段，绝不碰 ComponentExportBundle.Item）══════════
python3 - "$ENTITY" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
anchor = "    @PrePersist"
assert anchor in s, "锚点 @PrePersist 不见了，脚本需要更新"
inject = (
    "    // ── AC-7 还原实验临时字段：由 ac7-还原实验.sh 注入，脚本退出时自动删除 ──\n"
    "    @Column(name = \"foo\", length = 50)\n"
    "    public String foo;\n\n"
)
s = s.replace(anchor, inject + anchor, 1)
open(p, 'w', encoding='utf-8').write(s)
print("已注入 foo 字段")
PY
[ $? -eq 0 ] || { echo "❌ 注入失败"; exit 2; }
/usr/bin/grep -n "public String foo;" "$ENTITY" || { echo "❌ 注入后没找到 foo，实验无效"; exit 2; }

# ══ 步骤 3：必须变红，且失败信息点名 foo ═══════════════════════════════════════
( cd "$BACKEND" && ./mvnw test -Dtest="$GUARD_LIST" -DfailIfNoTests=false ) > "$EVID/.ac7-step3-injected.out" 2>&1
RC3=$?
echo "[步骤3 注入后] 退出码=$RC3"

FAIL_OK=1
if [ $RC3 -eq 0 ]; then
  echo "❌ AC-7 不成立：加了未登记的持久化字段 foo，反射契约测试仍然是绿的 —— 这条防线不会触发"
  FAIL_OK=0
else
  echo "✅ 变红（退出码 $RC3）"
  echo "--- 失败信息里与 foo 相关的行 ---"
  /usr/bin/grep -a -n "foo" "$EVID/.ac7-step3-injected.out" | head -20
  if /usr/bin/grep -aq "foo" "$EVID/.ac7-step3-injected.out"; then
    echo "✅ 失败信息点名了 foo"
  else
    echo "❌ 变红了但失败信息没点名 foo —— 可能是别的原因红的（比如 SQL 里没有 foo 列导致启动/查询报错），"
    echo "   这不满足 AC-7「失败信息点名 foo 未在导出 DTO 中登记」，需要人工看 .ac7-step3-injected.out"
    FAIL_OK=0
  fi
  if /usr/bin/grep -aq -e "白名单" -e "登记" "$EVID/.ac7-step3-injected.out"; then
    echo "✅ 失败信息含「登记/白名单」提示（AC-7 要求提示带上或加入白名单并注明理由）"
  else
    echo "⚠️  失败信息没看到「登记/白名单」字样，AC-7 的文案要求可能没满足 —— 人工确认"
    FAIL_OK=0
  fi
fi

# ══ 步骤 4：删掉 foo 必须恢复绿（确认不是恒红）═════════════════════════════════
restore
trap - EXIT INT TERM
( cd "$BACKEND" && ./mvnw test -Dtest="$GUARD_LIST" -DfailIfNoTests=false ) > "$EVID/.ac7-step4-restored.out" 2>&1
RC4=$?
echo "[步骤4 还原后] 退出码=$RC4"
[ $RC4 -eq 0 ] && echo "✅ 恢复绿（不是恒红）" || { echo "❌ 还原后仍是红的 —— 说明步骤3 的红不是 foo 引起的"; FAIL_OK=0; }

# ══ 步骤 5：还原证据 —— git 工作区必须干净 ════════════════════════════════════
echo "--- git status（Component.java）---"
( cd "$WT" && git status --porcelain -- "cpq-backend/src/main/java/com/cpq/component/entity/Component.java" )
echo "--- git diff --stat（Component.java）---"
( cd "$WT" && git diff --stat -- "cpq-backend/src/main/java/com/cpq/component/entity/Component.java" )
LEFT=$(cd "$WT" && git status --porcelain -- "cpq-backend/src/main/java/com/cpq/component/entity/Component.java")
if [ -n "$LEFT" ]; then
  echo "❌❌ 实体文件没还原干净：$LEFT —— 立即人工处理，别让它污染后端工程师的工作区"
  exit 3
fi
echo "✅ 实体文件已还原（git 工作区对该文件干净）"

if [ $FAIL_OK -eq 1 ]; then
  echo "=== AC-7 还原实验：通过（注入变红且点名 foo，删掉恢复绿，文件已还原）==="
  exit 0
fi
echo "=== AC-7 还原实验：未通过，看上面的 ❌ 行 ==="
exit 1
