#!/usr/bin/env bash
# B-AC-3: 3 个 PG 复合视图的全量快照(逐字)。用法: pgview_snapshot.sh <outdir>
set -uo pipefail
OUT="${1:?}"; mkdir -p "$OUT"
export PGPASSWORD=joii5231
H="-h 10.177.152.12 -U postgres -d cpq_db_0724"
for v in v_composite_child_materials v_composite_child_processes v_composite_child_elements; do
  # 定义快照(改造后 pg_get_viewdef 必然变;仅作留痕,不作判据)
  psql $H -tAX -c "SELECT pg_get_viewdef('$v'::regclass, true)" > "$OUT/$v.def.sql"
  # 数据快照: 全列排序 => 行序确定, 不依赖视图自身 ORDER BY
  NCOL=$(psql $H -tAX -c "SELECT count(*) FROM information_schema.columns WHERE table_name='$v'")
  ORD=$(seq -s, 1 "$NCOL")
  psql $H -qAX -v ON_ERROR_STOP=1 \
    -c "COPY (SELECT * FROM $v ORDER BY $ORD) TO STDOUT WITH (FORMAT csv, HEADER true, NULL '\\N', FORCE_QUOTE *)" \
    > "$OUT/$v.csv" 2> "$OUT/$v.err"
  echo "$v rows=$(( $(wc -l < "$OUT/$v.csv") - 1 )) sha=$(sha256sum "$OUT/$v.csv" | cut -c1-16) $(head -c 120 "$OUT/$v.err")"
done
