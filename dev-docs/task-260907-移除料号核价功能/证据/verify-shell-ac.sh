#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# task-260907「移除主数据维护『料号核价』」· 非 E2E 类 AC 的可复核脚本
#
# 覆盖：T-6/AC-6（物理删除+反向对照）· T-7/AC-7（零残留引用+还原实验）
#       T-14/AC-14（构建与类型）· T-16/AC-16（公共件 diff）· T-17/AC-17（文档回写+反向对照）
#
# 🚨 本脚本只读 + 只跑构建，**不含任何 DROP / TRUNCATE / DELETE / rm -rf / git reset**。
#    唯一的写动作是 T-7 还原实验：在 `cpq-frontend/e2e/` 下建一个临时哨兵文件后立即删除
#    （🚫 刻意不按 test.md §5 原稿去改 `src/main.tsx` + `git checkout` ——
#      前端代理可能正在改那个文件，`git checkout` 会连人家未提交的改动一起打掉）。
#
# 用法：bash dev-docs/task-260907-移除料号核价功能/证据/verify-shell-ac.sh
# 必须在 worktree 根目录执行。
# ═══════════════════════════════════════════════════════════════════
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT" || exit 1
echo "ROOT = $ROOT"
echo "BRANCH = $(git branch --show-current)"
echo "HEAD = $(git rev-parse --short HEAD)"
echo

FAIL=0
ok()   { echo "  ✅ $*"; }
bad()  { echo "  ❌ $*"; FAIL=1; }

# ───────────────────────── T-6 / AC-6 ─────────────────────────
echo "═══ T-6 / AC-6 · 代码物理删除（含反向对照）═══"
for p in \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance \
  cpq-frontend/src/pages/master-data/part-costing \
  cpq-frontend/src/pages/master-data/PricingBasicDataImportDrawer.tsx
do
  if [ -e "$p" ]; then bad "应已删除但仍存在：$p"; else ok "已删除：$p"; fi
done
echo "  ── 🔁 反向对照（证明删的是目标，不是把整片都删了）──"
for p in \
  cpq-frontend/src/pages/master-data/shared/EditableSheetTable.tsx \
  cpq-frontend/src/pages/master-data/shared/SheetPartListTab.tsx \
  cpq-frontend/src/pages/master-data/shared/types.ts \
  cpq-frontend/src/pages/master-data/shared/sheetApiFactory.ts \
  cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing
do
  if [ -e "$p" ]; then ok "仍存在：$p"; else bad "🚨 反向对照失败，被误删：$p"; fi
done
echo

# ───────────────────────── T-7 / AC-7 ─────────────────────────
echo "═══ T-7 / AC-7 · 源码树零残留引用（先做还原实验）═══"
PAT='PartCostingTab|PartCostingDrawer|PricingBasicDataImportDrawer|PricingBasicDataMaintenanceResource|PricingMaintenanceService|PricingSheetRegistry|PricingSheetDef|pricing-basic-data|part-costing|importPricing|downloadPricingTemplate|料号核价'
SCOPE=(cpq-backend/src cpq-frontend/src cpq-frontend/e2e)

# ① 还原实验：先证明这条扫描真的抓得到东西
#    🚫 跳过这一步直接看 ② 拿到 0 = 白测（本环境 grep 是 ugrep -I 别名，
#       会把中文注释多的源文件静默判为二进制返空 ⇒ 「零残留」是伪造出来的）
SENTINEL=cpq-frontend/e2e/.__ac7_falsify_sentinel.ts
echo '// falsification sentinel: PartCostingTab / 料号核价 / pricing-basic-data' > "$SENTINEL"
N_WITH=$(/usr/bin/grep -raE "$PAT" "${SCOPE[@]}" | wc -l)
rm -f "$SENTINEL"
N_WITHOUT=$(/usr/bin/grep -raE "$PAT" "${SCOPE[@]}" | wc -l)
echo "  还原实验：插哨兵后命中 $N_WITH 行；撤哨兵后命中 $N_WITHOUT 行（差值应 = 1）"
if [ "$((N_WITH - N_WITHOUT))" -eq 1 ]; then
  ok "扫描确实生效（哨兵被抓到）"
else
  bad "🚨 还原实验失败：插哨兵前后命中数差值 = $((N_WITH - N_WITHOUT))，不是 1 ⇒ 本轮扫描结果不可采信（白测）"
fi

# ② 正式扫描
echo "  ── 正式扫描（期望 0 行）──"
/usr/bin/grep -raEn "$PAT" "${SCOPE[@]}" | sed 's/^/    /'
echo "  命中数 = $N_WITHOUT"
if [ "$N_WITHOUT" -eq 0 ]; then ok "零残留引用"; else bad "仍有 $N_WITHOUT 处残留引用（清单见上）"; fi

# ③ 📌 已知的**不可清理项**（报主线裁决，不要自行改）：
#    cpq-backend/src/main/resources/db/migration/V327__pricing_maintenance_source_column.sql
#    该文件是**已发布的迁移**，改它会变更 checksum ⇒ 任何已应用它的环境 Flyway validate 会挂
#    （CLAUDE.md §3.2「契约销毁」）。AC-7 的扫描范围里含 db/migration ⇒ 字面判据无法为 0。
echo "  ── 📌 迁移目录里的命中（不可清理，见脚本注释）──"
/usr/bin/grep -raEn "$PAT" cpq-backend/src/main/resources/db/migration | sed 's/^/    /' || true
echo

# ───────────────────────── T-16 / AC-16 ─────────────────────────
echo "═══ T-16 / AC-16 · 公共件迁移后内容零变化（人工审阅，脚本只出材料）═══"
git diff master --stat -- \
  cpq-frontend/src/pages/master-data/shared/EditableSheetTable.tsx \
  cpq-frontend/src/pages/master-data/shared/SheetPartListTab.tsx \
  cpq-frontend/src/pages/master-data/shared/types.ts \
  cpq-frontend/src/pages/master-data/shared/sheetApiFactory.ts | sed 's/^/  /'
echo "  ── 完整 diff 见：git diff master -M -- cpq-frontend/src/pages/master-data/shared/ ──"
echo "  ⚠️ 判据是人工逐 hunk 判定「只属于 import 路径 / 文件头注释 / legacy 段删除」三类，"
echo "     出现第四类即不通过。🚫 脚本不能替你判这一条。"
echo

# ───────────────────────── T-17 / AC-17 ─────────────────────────
echo "═══ T-17 / AC-17 · 文档回写完整性（含反向对照）═══"
N_PRICING=$(/usr/bin/grep -ac 'basic-data-import/v6/pricing' dev-docs/main-api.md 2>/dev/null || echo 0)
N_QUOTE=$(/usr/bin/grep -ac 'basic-data-import/v6/quote' dev-docs/main-api.md 2>/dev/null || echo 0)
N_SECTION=$(/usr/bin/grep -ac '核价基础数据导入（同步）' dev-docs/main-api.md 2>/dev/null || echo 0)
echo "  main-api.md: 'basic-data-import/v6/pricing' 命中 $N_PRICING（期望 0）"
echo "  main-api.md: '核价基础数据导入（同步）' 小节 命中 $N_SECTION（期望 0）"
echo "  🔁 反向对照 'basic-data-import/v6/quote' 命中 $N_QUOTE（期望 ≥1，证明删的是一节不是整章）"
[ "$N_PRICING" -eq 0 ] && ok "pricing 端点已从总账删除" || bad "main-api.md 仍有 $N_PRICING 处 pricing 端点"
[ "$N_SECTION" -eq 0 ] && ok "「核价基础数据导入（同步）」小节已删" || bad "该小节仍在"
[ "$N_QUOTE" -ge 1 ] && ok "反向对照成立（quote 端点仍在总账里）" || bad "🚨 反向对照失败：quote 端点也没了 ⇒ 可能删过头了"

echo "  ── RECORD.md / INDEX.md / BACKLOG.md ──"
/usr/bin/grep -acm1 '\[2026-09-07\]' docs/RECORD.md >/dev/null 2>&1 \
  && ok "RECORD.md 含 [2026-09-07] 条目" || bad "RECORD.md 缺 [2026-09-07] 条目"
/usr/bin/grep -ac 'task-260907-移除料号核价功能' dev-docs/INDEX.md | { read n; \
  [ "$n" -ge 1 ] && ok "INDEX.md 已登记本任务（$n 处）" || bad "INDEX.md 未登记本任务"; }
/usr/bin/grep -an 'MasterDataHubPage.tsx' dev-docs/INDEX.md | sed 's/^/    INDEX 按文件反查: /'
/usr/bin/grep -ac 'BL-0214' docs/BACKLOG.md | { read n; \
  [ "$n" -ge 1 ] && ok "BACKLOG.md 含 BL-0214（$n 处）" || bad "BACKLOG.md 缺 BL-0214"; }
echo

# ───────────────────────── T-14 / AC-14 ─────────────────────────
echo "═══ T-14 / AC-14 · 构建与类型 ═══"
echo "  ── npx tsc -b（前端全项目类型检查）──"
( cd cpq-frontend && npx tsc -b 2>&1 | tail -25 | sed 's/^/    /'; exit "${PIPESTATUS[0]}" )
[ $? -eq 0 ] && ok "tsc -b 0 错误" || bad "tsc -b 有错误"

echo "  ── npm run build ──"
( cd cpq-frontend && npm run build 2>&1 | tail -12 | sed 's/^/    /'; exit "${PIPESTATUS[0]}" )
[ $? -eq 0 ] && ok "npm run build 成功" || bad "npm run build 失败"

echo "  ── mvnw -q compile ──"
( cd cpq-backend && ./mvnw -q compile 2>&1 | tail -25 | sed 's/^/    /'; exit "${PIPESTATUS[0]}" )
[ $? -eq 0 ] && ok "mvnw -q compile 成功" || bad "mvnw -q compile 失败"
echo

echo "═══════════════════════════════════════════"
[ "$FAIL" -eq 0 ] && echo "全部 shell 类 AC 通过" || echo "存在未通过项（见上面的 ❌）"
exit "$FAIL"
