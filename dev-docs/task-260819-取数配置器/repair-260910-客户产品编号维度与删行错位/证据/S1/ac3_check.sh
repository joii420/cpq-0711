#!/bin/bash
# AC-3: 以指定客户取数 -> 返回非空，且不含他客户独有的客户料号
CUST="${1:-CUST-0004}"; PART="${2:-S0004}"; CPN="${3:-RW-A004}"
Q(){ PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 "$@"; }
TPL=$(Q -Atc "SELECT sql_template FROM component_sql_view WHERE sql_view_name='builder_221dc7668ab6';")
echo "########## AC-3 · 客户=$CUST 料号=$PART 客编=$CPN ##########"
echo "----- 库中 sql_template 原文（live，非手抄） -----"; echo "$TPL"
SQL=$(CUST="$CUST" PART="$PART" CPN="$CPN" python3 -c "
import os,sys
t=sys.stdin.read()
t=t.replace(':total_material_no', \"ARRAY['%s']\"%os.environ['PART'])
t=t.replace(':customerProductNo', \"'%s'\"%os.environ['CPN'])
t=t.replace(':customerCode', \"'%s'\"%os.environ['CUST'])
sys.stdout.write(t)" <<<"$TPL")
echo; echo "----- 实际执行的 SQL（参数已字面量化） -----"; echo "$SQL"
echo; echo "----- 残留未绑定占位符检查（应为空） -----"
echo "$SQL" | /usr/bin/grep -ao ':[a-zA-Z_][a-zA-Z_0-9]*' || echo "(无残留占位符 ✓)"
echo; echo "----- 执行结果 -----"; Q -c "$SQL"
echo "----- 断言 ① 结果非空（阳性 AC，0 行即不通过） -----"
N=$(Q -Atc "SELECT count(*) FROM ($SQL) t;"); echo "行数 = $N"
echo "----- 断言 ②③ 跨客户泄漏（他客户独有客编一条都不许出现，期望 0） -----"
Q -c "
WITH got AS ($SQL),
     mine AS (SELECT customer_product_no FROM ds_quote_customer_part WHERE material_no='$PART' AND customer_no='$CUST'),
     others AS (SELECT customer_product_no FROM ds_quote_customer_part WHERE material_no='$PART' AND customer_no<>'$CUST'
                  AND customer_product_no NOT IN (SELECT customer_product_no FROM mine))
SELECT (SELECT string_agg(customer_product_no,', ') FROM others) AS 他客户独有客编_判据行,
       (SELECT count(*) FROM got g JOIN others o ON g.\"_客户料号_客户产品编号\"=o.customer_product_no) AS 泄漏条数_期望0,
       (SELECT count(*) FROM got) AS 返回行数;"
