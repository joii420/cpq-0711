#!/usr/bin/env bash
# T-C0（AC-15 前置）：迁移前只读采样。只 SELECT。输出 out-C0/
source "$(dirname "$0")/common.sh"; guard_db
# 已于 2026-09-17T07:00:02Z 在 cpq_db_rp0916c（当时的 S-C 一次性库，V445、未迁移）上执行过一次，产物即 out-C0/。
# D-15 后库改为 cpq_db_rp0916d，本脚本不再需要重跑；为防覆盖历史基线，产物已存在时拒绝执行。
O="$S/out-C0"; [ -f "$O/基线.txt" ] && { echo "out-C0 基线已存在，不覆盖"; exit 0; }; mkdir -p "$O"
{
echo "采样时刻(UTC)=$(now)"
echo "flyway 最新 3 条:"; q "select version,description,success,installed_on from flyway_schema_history order by installed_rank desc limit 3"
echo "8 列 TAB_JOIN_FORMULA 原文 (code|name|col_key|expression):"
q "select c.code,c.name,e->>'col_key',e->>'expression' from component c, jsonb_array_elements(c.excel_columns) e where jsonb_typeof(c.excel_columns)='array' and e->>'source_type'='TAB_JOIN_FORMULA' order by c.code,e->>'col_key'"
echo "QT-20260916-0881 quote_excel_values:"
q "select q.id,q.status,li.id,li.quote_excel_values from quotation q join quotation_line_item li on li.quotation_id=q.id where q.quotation_number='QT-20260916-0881'"
} | tee "$O/基线.txt"
# 全列 JSON（供迁移后逐列比对「其余 5 列不变」）
q "select json_build_object('code',c.code,'col',e) from component c, jsonb_array_elements(c.excel_columns) e where jsonb_typeof(c.excel_columns)='array' and e->>'source_type'='TAB_JOIN_FORMULA' order by c.code,e->>'col_key'" > "$O/tabjoin-cols-before.jsonl"
# 扫描口径用的组件全量（与 scan_subtotal_refs.py 用法一致）
q "select json_build_object('id',id,'code',code,'name',name,'type',component_type,'fields',fields,'formulas',formulas,'excel',excel_columns,'dir',directory_id) from component" > "$O/comps-before.jsonl"
# 其余三处存储的 TAB_JOIN 列数（预期 0）
q "select 'template',count(*) from template where excel_view_config::text like '%TAB_JOIN_FORMULA%' union all select 'tcs',count(*) from template_component_snapshot where excel_columns::text like '%TAB_JOIN_FORMULA%' union all select 'cet',count(*) from customer_excel_template where excel_columns::text like '%TAB_JOIN_FORMULA%'" | tee "$O/其余三处TAB_JOIN计数.txt"
wc -l "$O"/*.jsonl
