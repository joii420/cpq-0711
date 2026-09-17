#!/usr/bin/env bash
# T-C3（AC-15 ①~④）。前提：本任务迁移已由临时后端（stack.sh start-backend branch true）或后端测试落到 cpq_db_rp0916c。
# ④ 需要主线先审过迁移文件、再以 CONFIRM_MIGRATION_SQL_OK=1 放行（本脚本不读迁移逻辑，只按文件名定位并在事务内执行）。
source "$(dirname "$0")/common.sh"; guard_wt; guard_db
O="$S/out-C3"; mkdir -p "$O"; FAILS=0; exec > >(tee "$O/log.txt") 2>&1
echo "采样时刻 $(now)"
echo "== ① flyway success =="
row=$(q "select version,description,success from flyway_schema_history where description ilike '%repair260916%subtotal%' order by installed_rank")
echo "$row"
[ "$(echo "$row" | grep -c .)" = 1 ] && [ "${row##*|}" = t ] && pass "① 迁移恰 1 条且 success=t" || fail "① 迁移记录不符: '$row'"
echo "== ② 8 列文字 =="
q "select json_build_object('code',c.code,'col',e) from component c, jsonb_array_elements(c.excel_columns) e where jsonb_typeof(c.excel_columns)='array' and e->>'source_type'='TAB_JOIN_FORMULA' order by c.code,e->>'col_key'" > "$O/tabjoin-cols-after.jsonl"
python3 - "$S/out-C0/tabjoin-cols-before.jsonl" "$O/tabjoin-cols-after.jsonl" <<'PY'
import json, sys
load = lambda p: {(o['code'], o['col']['col_key']): o['col'] for o in map(json.loads, open(p, encoding='utf-8'))}
b, a = load(sys.argv[1]), load(sys.argv[2])
# 迁移前原文（问题说明 4.3 表）与迁移后期望（5.4）
EXP_BEFORE = {('COMP-0011','col_1'):'[物料.材料成本]',('COMP-0011','col_2'):'[物料.回收成本]',('COMP-0011','col_3'):'[产品小计(总计)]',
  ('COMP-2269','col_1'):'[材质元素.元素小计]',('COMP-2269','col_2'):'[小计(总计)]',
  ('COMP-2509','col_1'):'[材质元素(总计)]',('COMP-2509','col_2'):'[BOM(总计)]',('COMP-2509','col_3'):'[XJ(总计)]'}
CHANGED = {('COMP-0011','col_1'):'[物料.材料成本(小计)]',('COMP-0011','col_2'):'[物料.回收成本(小计)]',('COMP-2269','col_1'):'[材质元素.元素小计(小计)]'}
bad = 0
def ck(ok, m):
    global bad; print(('✅ ' if ok else '❌ ') + m); bad += (not ok)
ck(len(b) == 8 and len(a) == 8, f'迁移前后均 8 列（前 {len(b)} 后 {len(a)}）')
ck({k: v['expression'] for k, v in b.items()} == EXP_BEFORE, '迁移前基线（out-C0，采样 2026-09-17T07:00:02Z）与问题说明 4.3 原文逐列一致')
for k, want_before in EXP_BEFORE.items():
    ca, cb = a.get(k), b.get(k)
    if ca is None: ck(False, f'{k} 迁移后缺失'); continue
    want = CHANGED.get(k, want_before)
    ck(ca['expression'] == want, f'{k} expression={ca["expression"]!r} 期望 {want!r}')
    rest_a = {x: y for x, y in ca.items() if x != 'expression'}; rest_b = {x: y for x, y in cb.items() if x != 'expression'}
    ck(rest_a == rest_b, f'{k} 除 expression 外其余键不变')
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] && pass "② 三列改写、五列不变" || fail "② 见上"
echo "== ③ 四处存储扫描 =="
D="$O/scan"; mkdir -p "$D"
q "select json_build_object('id',id,'code',code,'name',name,'type',component_type,'fields',fields,'formulas',formulas,'excel',excel_columns,'dir',directory_id) from component" > "$D/comps.jsonl"
q "select json_build_object('id',id,'cfg',excel_view_config) from template where excel_view_config is not null" > "$D/templates.jsonl"
q "select json_build_object('id',id,'excel',excel_columns) from template_component_snapshot where excel_columns is not null" > "$D/tcs.jsonl"
q "select json_build_object('id',id,'excel',excel_columns) from customer_excel_template where excel_columns is not null" > "$D/cet.jsonl"
wc -l "$D"/*.jsonl
python3 "$S/scan_s_c.py" "$D" | tee "$O/scan-result.txt"
R=$(tail -1 "$O/scan-result.txt"); echo "$R"
[[ "$R" == *"bare_subtotal_refs=0 "* ]] && pass "③ 不带后缀引用小计列 = 0" || fail "③ $R"
[[ "$R" == *"suffix_on_non_subtotal=0 "* && "$R" == *"unresolved=0 "* ]] && pass "③ 无 (小计) 误用、无无法解析的引用" || fail "③ $R"
[[ "$R" == *"suffix_ok=3 "* && "$R" == *"tabjoin_cols=8" ]] && pass "③ 非空保护：8 列、3 处 (小计)" || fail "③ 非空保护不符 $R"
echo "== ④ 幂等：迁移 SQL 在一次性库再执行 =="
MIG=${MIG_FILE:-$(ls "$WT"/cpq-backend/src/main/resources/db/migration/V*__repair260916_tabjoin_subtotal_suffix.sql 2>/dev/null | head -1)}
echo "迁移文件: $MIG"
if [ -z "$MIG" ] || [ ! -f "$MIG" ]; then fail "④ 找不到迁移文件（可用 MIG_FILE 指定）"
else
  echo "关键字清单（供主线审阅，不代表判定）:"; /usr/bin/grep -n -a -i -E '\b(drop|truncate|delete|commit|begin|rollback|flyway_schema_history)\b' "$MIG" || echo "  (无)"
  if /usr/bin/grep -q -a -i 'flyway_schema_history' "$MIG"; then fail "④ 迁移文件引用 flyway_schema_history，不执行，报主线"
  elif [ "${CONFIRM_MIGRATION_SQL_OK:-}" != 1 ]; then fail "④ 未获主线放行（CONFIRM_MIGRATION_SQL_OK=1），未执行"
  else
    HAS_TX=$(/usr/bin/grep -c -a -i -E '^\s*(commit|begin|rollback)\s*;' "$MIG")
    XSNAP="create temp table x0 as select 'component' t, id::text id, xmin::text x from component union all select 'template', id::text, xmin::text from template union all select 'tcs', id::text, xmin::text from template_component_snapshot union all select 'cet', id::text, xmin::text from customer_excel_template;"
    XCNT="select t, count(*) from (select 'component' t, id::text id, xmin::text x from component union all select 'template', id::text, xmin::text from template union all select 'tcs', id::text, xmin::text from template_component_snapshot union all select 'cet', id::text, xmin::text from customer_excel_template) n join x0 using (t,id) where n.x<>x0.x group by t"
    if [ "$HAS_TX" != 0 ]; then echo "⚠️ 迁移文件自带事务语句，阳性对照（事务内回滚）不可做，跳过并报主线"
    else
      echo "-- 阳性对照：事务内把 COMP-0011 col_1 还原为旧文字 → 执行迁移 → 应改回 (小计) 且计到 ≥1 行；随后 ROLLBACK"
      "${PSQL[@]}" <<SQL | tee "$O/idem-control.txt"
BEGIN;
UPDATE component SET excel_columns = (select jsonb_agg(case when e->>'col_key'='col_1' then jsonb_set(e,'{expression}','"[物料.材料成本]"') else e end order by o) from jsonb_array_elements(excel_columns) with ordinality as x(e,o)) WHERE code='COMP-0011';
select 'reverted', e->>'expression' from component, jsonb_array_elements(excel_columns) e where code='COMP-0011' and e->>'col_key'='col_1';
$XSNAP
\i $MIG
select 'CTRL_AFTER', e->>'expression' from component, jsonb_array_elements(excel_columns) e where code='COMP-0011' and e->>'col_key'='col_1';
select 'CTRL_CHANGED', * from ($XCNT) z;
ROLLBACK;
SQL
      grep -q '^CTRL_AFTER|\[物料.材料成本(小计)\]$' "$O/idem-control.txt" && grep -q '^CTRL_CHANGED|component|[1-9]' "$O/idem-control.txt" \
        && pass "④ 阳性对照：迁移 SQL 能改写旧文字，且测量手段能计到改写行" || fail "④ 阳性对照不成立（见 idem-control.txt）⇒ 下面的 0 行无判别力"
    fi
    MD5="select md5(string_agg(coalesce(excel_columns::text,''),'|' order by id)) from component"
    before=$(q "$MD5")
    echo "-- 第二次执行（事务内测量后 ROLLBACK，不留副作用）"
    "${PSQL[@]}" <<SQL | tee "$O/idem-run2.txt"
BEGIN;
$XSNAP
\i $MIG
select 'RUN2_CHANGED', * from ($XCNT) z;
select 'RUN2_MD5', ($MD5);
ROLLBACK;
SQL
    grep -q '^RUN2_CHANGED' "$O/idem-run2.txt" && fail "④ 第二次执行物理改写了行（见上 RUN2_CHANGED）" || pass "④ 第二次执行物理改写 0 行（xmin 未变）"
    grep -q "^RUN2_MD5|$before\$" "$O/idem-run2.txt" && pass "④ 第二次执行后 component.excel_columns 内容 md5 不变" || fail "④ 内容 md5 变化"
  fi
fi
echo "RESULT FAILS=$FAILS"; [ $FAILS -eq 0 ]
