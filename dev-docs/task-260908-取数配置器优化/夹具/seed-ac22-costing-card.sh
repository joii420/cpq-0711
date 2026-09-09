#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# AC-22 核价卡片夹具 · 可重复种子
#
# 【它解决什么】
#   AC-22 要验「核价单视图下含 BOM 树的卡片，表头 = BOM，右侧「版本」列仍在」。
#   立项时 D-19 裁决「不造夹具、记未验证」，理由是「要绑 COSTING 模板 + 建单 + 物化」。
#   ⚠️ 那个理由**基于一个错误前提**：以为核价卡片需要独立的 COSTING 模板实体。
#      实测 `quotation.costing_card_template_id` 的外键是
#      `quotation_costing_card_template_fk → template(id)`，指向**普通 template 表**，
#      而库里 14 张 PUBLISHED 模板全部 kind=QUOTATION。
#   ⇒ 造这个夹具 = **一条 UPDATE**。
#
# 【为什么这样就够】
#   QuotationStep2.tsx:4620 起，核价卡片编辑页的渲染只依赖两样：
#     ① quotation.costing_card_template_id 非空
#     ② 该模板 componentsSnapshot 非空 + 该单有明细行
#   **不经过 quotation_view_structure**（那张表只喂详情页 dto.costingCardStructure）。
#   产品自己的空态文案就写着「或在数据库中通过 quotation.costing_card_template_id 字段配置」
#   ⇒ 直接写这一列是**产品声明的配置方式**，不是绕过导入器。
#
# 【这个夹具证明什么 / 不证明什么】
#   ✅ 证明：cardSide='COSTING' 分支的表头渲染（AC-22 的全部内容）
#   🚫 不证明：核价方言组件能取到数、核价价格策略能接价（那是 P-2 的事，需要第三层夹具）
#
# 【AC-22 的实测结果 · 2026-09-09】用 PW_T260908_COSTING_QID=03e79d02-... 跑：
#     表头 = ["BOM","版本","销售料号","项次","投入料号",...]  ⇒ 两条正题都通过
#   spec 的共用断言 assertTreeHeader② 「展开箭头 ≥ 1」会红 —— 该单 BOM 只有根节点。
#   🔑 那条断言想防的是「表头文案对了但它已不是树列」，而这里有**更强的直接证据**：
#      `<th>BOM</th>` 与 `{cardSide==='COSTING' && <th>版本</th>}` 写在**同一个
#      `activeComponentBomTree && (...)` 块内**（QuotationStep2.tsx:3281-3292）⇒
#      版本列出现本身就证明 activeComponentBomTree===true。箭头只是它的代理指标。
#   —— 因为本种子把**同一张报价模板**同时挂在两个字段上，两个视图的组件完全相同，
#      唯一差异就是 cardSide。这恰恰是 AC-22 的理想对照组：**只有一个变量在动**。
#
# 【用法】
#   bash dev-docs/task-260908-取数配置器优化/夹具/seed-ac22-costing-card.sh          # 建
#   bash dev-docs/task-260908-取数配置器优化/夹具/seed-ac22-costing-card.sh --rollback # 撤
#   bash dev-docs/task-260908-取数配置器优化/夹具/seed-ac22-costing-card.sh --check     # 只看现状
#
# 【幂等】重复跑不会重复改：UPDATE 带 `costing_card_template_id IS NULL` 守卫。
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

PGHOST="${DB_HOST:-10.177.152.12}"
PGUSER="${DB_USERNAME:-postgres}"
PGDB="${DB_NAME:-cpq_db_0724}"
export PGPASSWORD="${DB_PASSWORD:-joii5231}"
BACKEND="${PW_BACKEND_URL:-http://localhost:8081}"

psql_() { psql -h "$PGHOST" -U "$PGUSER" -d "$PGDB" -Atc "$1"; }

# ── 夹具单的选取判据（🚫 不写死单号：单会被别的会话删/改，写死等于下次必挂）──────────
#   ① DRAFT —— 核价卡片是编辑页视图；且 ensureStructure 只对 DRAFT 生效
#   ② 有明细行 —— lineItems.length===0 会走「暂无产品」空态，看不到表头
#   ③ 模板含 tabType='BOM' 的组件 —— 没有树就没有树结构列，断言「表头=BOM」会验成另一个东西
#   ④ 明细的 quote_card_values 里真有 nodeId —— 有树组件 ≠ 这张单真展开出了树节点
#   ⑤ 🚨 树**真有子节点**（parentId 非空）—— 这条是 2026-09-09 补的，漏掉它的后果很具体：
#      spec 的 assertTreeHeader 第②条断言「展开/折叠箭头数 ≥ 1」。只有根节点的单
#      箭头数恒为 0 ⇒ 用例必红，而红的原因是**夹具选得不对**，不是产品坏了。
#      （亲验记录里 AC-20/21 那条「诚实标注」正是这个成因。）
#   ⑥ 节点数 DESC —— 🚫 不再用 created_at ASC：最老的单往往只有根节点，
#      「稳定」但验不动东西。选树最深的那张，它同时满足④⑤。
PICK_SQL="
select q.id::text
from quotation q
join template_component tc on tc.template_id = q.customer_template_id
join component_sql_view v on v.component_id = tc.component_id
                         and v.builder_config->>'tabType' = 'BOM'
join quotation_line_item li on li.quotation_id = q.id
where q.status = 'DRAFT'
  and li.quote_card_values::text like '%nodeId%'
  and li.quote_card_values::text ~ 'parentId\"?\s*:\s*\"'
group by q.id
order by max((select count(*) from regexp_matches(li.quote_card_values::text,'nodeId','g'))) desc,
         q.id asc
limit 1"

show_state() {
  echo "── 现状 ──"
  psql -h "$PGHOST" -U "$PGUSER" -d "$PGDB" -c "
    select q.quotation_number, q.status,
           (q.costing_card_template_id is not null) as has_costing_tpl,
           (select count(*) from quotation_line_item li where li.quotation_id=q.id) as lines,
           (select count(*) from quotation_view_structure s
             where s.quotation_id=q.id and s.view_kind like 'COSTING%') as costing_structs
    from quotation q where q.id = '$1';"
}

# 允许显式指定（🚨 必要，不是便利）——2026-09-09 实测：**没有一张单同时满足两侧**
#   · 03e79d02 QT-20260907-0557（报价模板·ds 原生）→ 核价视图渲染成功，但 BOM 只有根节点
#   · 438f10f4 QT-20260908-0627（正泰测试模板1）  → 树有 24 节点带子节点，但核价侧 expand 抛异常：
#       「树页签组件 7f9a5bbf 的 $view 未输出 parent_no 列（8 行全无父件列）」
#       ⇒ 核价侧树页签按 (parent_no, material_no) 边键匹配，**与报价侧树的展开链路不是同一套**；
#         同一个组件报价侧渲染正常、核价侧硬失败。这是该组件的 $view 配置缺口，不是渲染代码缺陷。
#   ⇒ 自动选取按「树最深」给 0627；要跑 AC-22 请显式指定 0557。
QID="${PW_T260908_COSTING_QID:-$(psql_ "$PICK_SQL")}"
if [ -z "$QID" ]; then
  echo "🚨 找不到满足四条判据的报价单 —— 这是环境缺口，不是产品缺陷。停手报主线。" >&2
  exit 1
fi

case "${1:-seed}" in
  --check)
    show_state "$QID"; echo "候选单 id = $QID"; exit 0 ;;

  --rollback)
    echo "回滚夹具 quotation=$QID"
    # 命中面：1 行 quotation + 该单的 COSTING_* 结构行（下面先数给你看）
    psql_ "select count(*) from quotation_view_structure where quotation_id='$QID' and view_kind like 'COSTING%'" \
      | xargs -I{} echo "  将删除 quotation_view_structure COSTING_* 行数 = {}"
    psql_ "update quotation set costing_card_template_id = null where id = '$QID'"
    psql_ "delete from quotation_view_structure where quotation_id='$QID' and view_kind like 'COSTING%'"
    show_state "$QID"; exit 0 ;;
esac

# ── 建夹具 ────────────────────────────────────────────────────────────────
echo "夹具单 quotation=$QID"
show_state "$QID"

# 🔒 IS NULL 守卫 = 幂等 + 防覆盖：若这张单已被别人绑了别的核价模板，本脚本不动它
# 🚨 判「改了几行」必须读**命令标签**，不能读 RETURNING 的空非空：
#    `psql -Atc "... returning 1"` 在 0 行时输出的是 `UPDATE 0`（8 字节，**非空**）
#    ⇒ `[ -z "$AFFECTED" ]` 永远不成立，脚本会永远宣称「命中 1 行」。
#    数据层是对的（IS NULL 守卫真的挡住了），错的是量具 —— 2026-09-09 实证。
TAG="$(psql_ "
  update quotation
     set costing_card_template_id = customer_template_id
   where id = '$QID' and costing_card_template_id is null")"
case "$TAG" in
  "UPDATE 1") echo "  ✅ UPDATE 命中 1 行：costing_card_template_id := customer_template_id" ;;
  "UPDATE 0") echo "  ⏭  已绑定（或已被他人绑定），本次改 0 行 —— 幂等生效" ;;
  *)          echo "  🚨 非预期的命令标签：[$TAG] —— 停手查清楚，别当成成功"; exit 1 ;;
esac

# 详情页（AC-21 那侧的只读渲染）需要 quotation_view_structure 的 COSTING_CARD 行。
# 它由 Java 的 buildCardStructure 产出，SQL 造不出来 ⇒ 借端点触发（内部调 ensureStructure）。
# 编辑页不需要这一步，失败也不影响 AC-22 主体。
echo "── 触发 ensureStructure（POST /quotations/{id}/ensure-card-values）──"
# ⚠️ 本项目鉴权走 **session cookie**，不是 Bearer token —— 必须用 cookie jar。
#    首版写成 `Authorization: Bearer $TOKEN` 拿不到 token 直接静默跳过，
#    表现是「夹具建好了但详情页结构缺失」，看起来像另一个 bug。
JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
LOGIN_CODE="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -c "$JAR" \
  -X POST "$BACKEND/api/cpq/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@2026"}')"
if [ "$LOGIN_CODE" != "200" ]; then
  echo "  ⚠️ 登录 HTTP $LOGIN_CODE，跳过结构补建（编辑页不受影响，只有详情页会缺 COSTING_CARD 结构）"
else
  CODE="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -b "$JAR" -X POST \
    "$BACKEND/api/cpq/quotations/$QID/ensure-card-values")"
  echo "  ensure-card-values → HTTP $CODE"
fi

show_state "$QID"
echo
echo "════════════════════════════════════════════════════════════"
echo "跑 AC-22 用这个环境变量："
echo "  PW_T260908_COSTING_QID=$QID \\"
echo "  npx playwright test --config=e2e/playwright.config.ts \\"
echo "    e2e/task260908-s3-tree-header.spec.ts -g 'AC-22'"
echo "════════════════════════════════════════════════════════════"
