#!/usr/bin/env bash
# B-6a: 135 段受影响组件 SQL 的执行耗时基线 (B-AC-7 的 SQL 层判据)
# 用法: perf_sql.sh <outdir> <b5快照目录(取每视图行数最多的参数组合)>
# 指标: EXPLAIN(ANALYZE) 的 Execution Time —— 排除客户端传输噪声, 且附执行计划供回归定位
set -uo pipefail
OUT="${1:?}"; B5="${2:?}"; mkdir -p "$OUT/plans"
PGHOST=10.177.152.12; PGUSER=postgres; PGDB=cpq_db_0724
export PGPASSWORD=joii5231
PSQL="psql -h $PGHOST -U $PGUSER -d $PGDB -tAX"
REPS=5
PRICE_DATE="2026-09-03"
MATNOS="ARRAY['00005','00006','00168','00256','1630010773','2101110225','2111410069','2120011658','2120011659','3110520789','3110520790','3111320634','3111320635','3111320636','3111320637','0028-2609000012','3120011203','3110520422','00144']::varchar[]"

# 并发守卫
$PSQL -c "SELECT 'PERF_GUARD_PRE', now()::text, (SELECT count(*) FROM pg_stat_activity WHERE datname='$PGDB' AND state<>'idle')" > "$OUT/guard_pre.txt"

# 每个受影响视图挑「行数最多」的参数组合
awk -F'\t' 'NR==FNR{aff[$1]=1;next} FNR>1 && ($2 in aff) && $5=="OK" {
  if($7 > best[$2]){best[$2]=$7; sel[$2]=$1"\t"$2"\t"$3"\t"$4"\t"$7}
} END{for(k in sel) print sel[k]}' "$B5/../affected_ids.txt" "$B5/manifest.tsv" | sort > "$OUT/targets.tsv"
echo "targets: $(wc -l < "$OUT/targets.tsv")"

printf 'view_name\tview_id\ttier\tcustomer\trows\tmedian_ms\tmin_ms\tmax_ms\tsamples\n' > "$OUT/perf.tsv"

while IFS=$'\t' read -r VNAME VID TIER CUS NR; do
  RAW=$($PSQL -c "SELECT replace(encode(convert_to(sql_template,'UTF8'),'base64'), chr(10),'') FROM component_sql_view WHERE id='$VID'" | base64 -d)
  SQL=$(TIER="$TIER" CUS="$CUS" PD="$PRICE_DATE" MN="$MATNOS" perl -0777 -pe '
    my $tier=$ENV{TIER}; my $cus=$ENV{CUS}; my $pd=$ENV{PD}; my $mn=$ENV{MN};
    if ($tier eq "t2") { s/:versionFilter\(\s*([^,\)]+?)\s*,[^\)]*\)/(TRUE)/g; }
    else               { s/:versionFilter\(\s*([^,\)]+?)\s*,[^\)]*\)/($1)/g; }
    s/(?<!:):customerCode\b/'"'"'$cus'"'"'/g;
    s/(?<!:):priceBaseDate\b/DATE '"'"'$pd'"'"'/g;
    s/(?<!:):total_material_no\b/$mn/g;
    if ($tier eq "t2") { s/\b([A-Za-z_][A-Za-z0-9_]*\.)?is_current\b/TRUE/g; }
    s/;\s*\z//;
  ' <<< "$RAW")

  TIMES=()
  for r in $(seq 1 $REPS); do
    T=$(timeout 90 psql -h $PGHOST -U $PGUSER -d $PGDB -tAX -c "SET statement_timeout='60s'" \
          -c "EXPLAIN (ANALYZE, TIMING ON, FORMAT JSON) $SQL" 2>/dev/null \
        | grep -o '"Execution Time": *[0-9.]*' | head -1 | grep -o '[0-9.]*$')
    [ -n "$T" ] && TIMES+=("$T")
  done
  if [ ${#TIMES[@]} -eq 0 ]; then
    printf '%s\t%s\t%s\t%s\t%s\tERR\tERR\tERR\t0\n' "$VNAME" "$VID" "$TIER" "$CUS" "$NR" >> "$OUT/perf.tsv"; continue
  fi
  STATS=$(printf '%s\n' "${TIMES[@]}" | sort -g | awk '{a[NR]=$1} END{printf "%.3f\t%.3f\t%.3f\t%d", a[int((NR+1)/2)], a[1], a[NR], NR}')
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$VNAME" "$VID" "$TIER" "$CUS" "$NR" "$STATS" >> "$OUT/perf.tsv"
  # 留一份执行计划(最后一次), 供 AFTER 劣化时定位
  timeout 90 psql -h $PGHOST -U $PGUSER -d $PGDB -tAX -c "SET statement_timeout='60s'" \
    -c "EXPLAIN (ANALYZE, BUFFERS) $SQL" > "$OUT/plans/${VNAME}__${VID:0:8}.txt" 2>&1
done < "$OUT/targets.tsv"

$PSQL -c "SELECT 'PERF_GUARD_POST', now()::text, (SELECT count(*) FROM pg_stat_activity WHERE datname='$PGDB' AND state<>'idle')" > "$OUT/guard_post.txt"
echo "=== 汇总 ==="
awk -F'\t' 'NR>1 && $6!="ERR"{n++; s+=$6; if($6+0>mx){mx=$6+0;mn=$1}} END{printf "段数=%d 中位耗时合计=%.1fms 最慢段=%s(%.1fms)\n", n, s, mn, mx}' "$OUT/perf.tsv"
