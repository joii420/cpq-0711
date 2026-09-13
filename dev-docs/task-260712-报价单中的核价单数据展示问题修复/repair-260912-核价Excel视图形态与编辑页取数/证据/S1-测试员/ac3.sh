#!/bin/bash
# AC-3（问题说明 ⑥ 原文）：刷新后 costing_excel_values 每个 line item rows 长度 = 1，且不再含 treeMode 键。
# STAGE=pre-refresh  → 必须红（存量仍是 costingTree=true 的产物）——这是 AC-8① 的还原实验对照
# STAGE=post-refresh → 必须绿
# 纯只读 SELECT，不写任何数据。
STAGE=${STAGE:-post-refresh}
export PGPASSWORD=joii5231
SQL="select q.quotation_number||'/'||li.product_part_no_snapshot,
  jsonb_array_length(li.costing_excel_values::jsonb->'rows'),
  case when li.costing_excel_values::jsonb ? 'treeMode' then 'HAS_treeMode' else 'no_treeMode' end
from quotation_line_item li join quotation q on q.id=li.quotation_id
where q.quotation_number in ('QT-20260912-0011','QT-20260911-0010')
order by 1;"
OUT=$(psql -h 10.177.152.12 -U postgres -d cpq_db_0910 -At -F'|' -c "$SQL")
echo "$OUT"
N=$(echo "$OUT" | grep -c .)
[ "$N" -eq 8 ] || { echo "❌ 期望 8 个 line item，实得 $N（数据前提不成立，断言不可信）"; exit 2; }
BAD_ROWS=$(echo "$OUT" | awk -F'|' '$2!=1')
BAD_TREE=$(echo "$OUT" | awk -F'|' '$3!="no_treeMode"')
if [ "$STAGE" = "pre-refresh" ]; then
  [ -n "$BAD_ROWS" ] && [ -n "$BAD_TREE" ] \
    && { echo "✅ 刷前对照成立：rows≠1 的有 $(echo "$BAD_ROWS"|grep -c .) 个、含 treeMode 的有 $(echo "$BAD_TREE"|grep -c .) 个 ⇒ AC-3 断言有鉴别力"; exit 0; } \
    || { echo "❌ 刷前对照不成立（数据已经是目标态？）—— AC-3 的绿将无意义"; exit 1; }
else
  [ -z "$BAD_ROWS" ] && [ -z "$BAD_TREE" ] \
    && { echo "✅ AC-3 PASS：8/8 个 line item 的 rows 长度 = 1 且无 treeMode 键"; exit 0; } \
    || { echo "❌ AC-3 FAIL"; echo "rows≠1:"; echo "$BAD_ROWS"; echo "含 treeMode:"; echo "$BAD_TREE"; exit 1; }
fi
