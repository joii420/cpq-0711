#!/usr/bin/env bash
# V411 回滚：把 component_sql_view.sql_template 还原到应用前
# 用法：./rollback.sh <csv文件>
set -euo pipefail
CSV="${1:-$(dirname "$0")/component_sql_view-$(cat "$(dirname "$0")/LATEST").csv}"
[ -f "$CSV" ] || { echo "❌ 找不到备份 $CSV"; exit 1; }
echo "将从 $CSV 还原 $(( $(wc -l < "$CSV") - 1 )) 行"
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 <<SQL
BEGIN;
CREATE TEMP TABLE _restore(id uuid, sql_template text);
\copy _restore FROM '$CSV' WITH (FORMAT csv, HEADER true)
UPDATE component_sql_view c SET sql_template = r.sql_template
  FROM _restore r WHERE c.id = r.id AND c.sql_template IS DISTINCT FROM r.sql_template;
SELECT '还原后仍含 v_compat_ 的行数 = '||count(*) FROM component_sql_view WHERE sql_template ~* 'v_compat_';
COMMIT;
SQL
