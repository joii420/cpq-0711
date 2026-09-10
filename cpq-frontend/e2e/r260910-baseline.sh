#!/usr/bin/env bash
# repair-260910 · S1 片「改动前基线」采集器（**纯只读**）
#
# 用途：AC-12 / AC-13 / AC-14 是 before/after 型断言，其 before 侧一旦被
#       主线的骨架配置改动覆盖就再也拿不回来 ⇒ 必须在改配置前跑一次。
#
# 🚨 全脚本只有 SELECT，无任何写入；不 DROP、不 DELETE、不 UPDATE。
# 用法：bash e2e/r260910-baseline.sh [输出目录]
set -euo pipefail

OUT="${1:-$(cd "$(dirname "$0")/../.." && pwd)/dev-docs/task-260909-核价树骨架分档与轴口径统一/repair-260910-核价树版本切换查V6老表/证据/e2e-s1}"
mkdir -p "$OUT"
# 🚨 默认文件名固定为「基线-改动前.txt」，且**只应在主线改骨架配置之前跑一次**。
#    配置改动落地后重跑会把 before 侧覆盖成 after 态，AC-12/13/14 的对照当场退化成自证。
#    改动后想留快照请显式换名：R260910_SNAPSHOT_NAME=现状-改动后 bash e2e/r260910-baseline.sh
F="$OUT/${R260910_SNAPSHOT_NAME:-基线-改动前}.txt"
if [ -z "${R260910_SNAPSHOT_NAME:-}" ] && [ -s "$OUT/基线-改动前.txt" ]; then
  echo "🚨 拒绝覆盖已存在的 $OUT/基线-改动前.txt"
  echo "   它是 AC-12/13/14 的 before 侧，覆盖后取不回来。"
  echo "   要留改动后快照请用：R260910_SNAPSHOT_NAME=现状-改动后 bash $0"
  exit 3
fi

export PGPASSWORD="${PW_DB_PASS:-joii5231}"
PSQL=(psql -h "${PW_DB_HOST:-10.177.152.12}" -p "${PW_DB_PORT:-5432}" -U "${PW_DB_USER:-postgres}" -d "${PW_DB:-cpq_db_0724}" -X -P pager=off)
BACKEND="${PW_BACKEND_URL:-http://localhost:8081}"

COID="${R260910_COID:-bea4c465-d15a-411e-991d-43f9e108c087}"   # HJ-20260910-0975 (PENDING, 正泰)
LIID="${R260910_LIID:-3c582035-d35a-4f99-9372-cb4b908a29bb}"   # S0001 铆钉 → BOM 树根 300001
CID_TREE="${R260910_CID_TREE:-32ab8212-df6c-4844-9721-ac7dc41d6cf2}"   # COMP-2299 BOM（树）
CID_FLAT="${R260910_CID_FLAT:-9291b050-b6a9-42c7-8c56-d3066c3c2369}"   # COMP-2300 材质元素（非树）

{
  echo "# repair-260910 · S1 改动前基线"
  echo "采集时刻: $(date -Is)"
  echo "库: ${PW_DB_HOST:-10.177.152.12}/${PW_DB:-cpq_db_0724}   后端: $BACKEND"
  echo "载体: costing_order=$COID  lineItem=$LIID  树组件=$CID_TREE  非树组件=$CID_FLAT"
  echo

  echo "## [B1] costing_bom_tree_config（AC-14 报价侧字节未动 / AC-15 md5 还原点）"
  "${PSQL[@]}" -A -F'|' -c "SELECT usage, id, name, is_active, md5(sql_template), length(sql_template), updated_at FROM costing_bom_tree_config ORDER BY usage, created_at;"
  echo

  echo "## [B2] 目标核价单 BOM 树（AC-13 逐字对照基线；键=node_path）"
  "${PSQL[@]}" -A -F'|' -c "
    SELECT r->>'__nodeId' AS node_path, r->>'__lvl' AS lvl,
           COALESCE(r->>'__parentNo','(root)') AS parent_no,
           COALESCE(r->>'__bomVersion','(null)') AS bom_version,
           r->>'__hfPartNo' AS part_no
    FROM costing_order co, jsonb_each(co.costing_render) li,
         jsonb_array_elements((li.value->>'costingCardValues')::jsonb->'tabs') tab,
         jsonb_array_elements(COALESCE(tab->'baseRows','[]'::jsonb)) WITH ORDINALITY t(r,ord)
    WHERE co.id='$COID' AND li.key='$LIID' AND tab->>'tabName'='BOM'
    ORDER BY ord;"
  echo

  echo "## [B3] 目标核价单整体（AC-6 金额同步的 before 侧）"
  "${PSQL[@]}" -A -F'|' -c "SELECT costing_order_number, status, md5(costing_render::text), costing_total_amount, total_amount, updated_at FROM costing_order WHERE id='$COID';"
  echo

  echo "## [B4] 全部冻结单 costing_render md5（AC-12；APPROVED/REJECTED/WITHDRAWN）"
  "${PSQL[@]}" -A -F'|' -c "
    SELECT id, costing_order_number, status, md5(COALESCE(costing_render::text,'(null)')) AS render_md5,
           (SELECT count(*) FROM jsonb_each(COALESCE(co.costing_render,'{}'::jsonb)) li2,
                   jsonb_array_elements((li2.value->>'costingCardValues')::jsonb->'tabs') tb,
                   jsonb_array_elements(COALESCE(tb->'baseRows','[]'::jsonb)) rr
             WHERE tb->>'tabName'='BOM') AS bom_rows
    FROM costing_order co WHERE status IN ('APPROVED','REJECTED','WITHDRAWN') ORDER BY id;"
  echo

  echo "## [B5] costing_order_version_override 全表（AC-8「不新增行」/ AC-11 还原点）"
  "${PSQL[@]}" -A -F'|' -c "SELECT id, costing_order_id, component_id, part_no, view_version, created_at, updated_at FROM costing_order_version_override ORDER BY created_at;"
  "${PSQL[@]}" -A -t -c "SELECT 'override_total_rows=' || count(*) FROM costing_order_version_override;"
  echo

  echo "## [B6] 报价侧同一行项的 quote_card_values md5（AC-14 报价侧渲染不变）"
  "${PSQL[@]}" -A -F'|' -c "SELECT id, product_part_no_snapshot, md5(COALESCE(quote_card_values::text,'(null)')), md5(COALESCE(costing_card_values::text,'(null)')) FROM quotation_line_item WHERE quotation_id=(SELECT quotation_id FROM costing_order WHERE id='$COID') ORDER BY sort_order;"
  echo

  echo "## [B7] 各料号自有 BOM 版本（AC-3/4/5 的期望值来源，直接查视图）"
  "${PSQL[@]}" -A -F'|' -c "
    SELECT production_no, string_agg(DISTINCT version_no::text, ',' ORDER BY version_no::text DESC) AS versions,
           max(version_no) FILTER (WHERE is_current) AS current_version
    FROM v_ds_cost_basic_material_bom_all
    WHERE production_no IN ('300001','300012','300013','300014','300015','991','992')
    GROUP BY production_no ORDER BY production_no;"
  echo "### 主表 / 历史表分布（AC-5：1、2 只在 _history，3 在主表）"
  "${PSQL[@]}" -A -F'|' -c "
    SELECT 'main' AS src, version_no, count(*) FROM ds_cost_basic_material_bom WHERE production_no='300001' GROUP BY 2
    UNION ALL
    SELECT 'history', version_no, count(*) FROM ds_cost_basic_material_bom_history WHERE production_no='300001' GROUP BY 2
    ORDER BY 1,2;"
  echo "### V6 老表对照（根因证据 E-1：应为 0 行）"
  "${PSQL[@]}" -A -t -c "SELECT 'legacy_material_bom_item_rows=' || count(*) FROM material_bom_item WHERE system_type='PRICING' AND customer_no='_GLOBAL_' AND material_no IN ('300001','300012','300013','300014','300015','991','992') AND bom_version IS NOT NULL;"
  echo

  echo "## [B8] version-options 接口现状（改动前；树分支应恒空 = 待修 bug）"
  JAR="$(mktemp)"
  code=$(curl -s --noproxy '*' -c "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BACKEND/api/cpq/auth/login" \
        -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@2026"}')
  echo "login -> $code"
  for P in 300001 300012 300013 300014 300015 991 992; do
    printf '  tree  %-7s -> ' "$P"
    curl -s --noproxy '*' -b "$JAR" "$BACKEND/api/cpq/costing-orders/$COID/version-options?lineItemId=$LIID&componentId=$CID_TREE&partNo=$P"
    echo
  done
  printf '  flat  %-7s -> ' 300013
  curl -s --noproxy '*' -b "$JAR" "$BACKEND/api/cpq/costing-orders/$COID/version-options?lineItemId=$LIID&componentId=$CID_FLAT&partNo=300013"
  echo
  rm -f "$JAR"
} 2>&1 | tee "$F"

echo
echo "✅ 基线已落盘: $F"
