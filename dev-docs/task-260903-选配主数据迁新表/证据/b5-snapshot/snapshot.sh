#!/usr/bin/env bash
# B-5 组件 SQL 快照工具 —— task-260903
# 用法: snapshot.sh <输出目录>
#   对 component_sql_view 全表逐段执行，落盘列名+行序+每单元格值，并出 manifest(sha256)。
# 设计要点:
#   1. 参数替换是【确定性】的，改造前后用同一套替换 => diff 有效性不依赖替换与生产语义一致。
#   2. 两档: t1(faithful, versionFilter->(is_current)) / t2(high-coverage, versionFilter+is_current->TRUE)
#      t2 只为提高 diff 灵敏度(行数越多越容易抓到差异)。
#   3. 0 行的段会显式记为 EMPTY —— 它对 diff 无鉴别力, 不许算作"已验证"。
set -uo pipefail

OUT="${1:?usage: snapshot.sh <outdir>}"
PGHOST=10.177.152.12; PGUSER=postgres; PGDB=cpq_db_0724
export PGPASSWORD=joii5231
PSQL="psql -h $PGHOST -U $PGUSER -d $PGDB -tAX -v ON_ERROR_STOP=1"

# 固定参数(写死 => 跨轮次可复现)
CUSTOMERS=("CUST-0004" "CUST-0001" "CUST-0002" "_GLOBAL_")
PRICE_DATE="2026-09-03"
MATNOS="ARRAY['00005','00006','00168','00256','1630010773','2101110225','2111410069','2120011658','2120011659','3110520789','3110520790','3111320634','3111320635','3111320636','3111320637','0028-2609000012','3120011203','3110520422','00144']::varchar[]"

mkdir -p "$OUT/data"
MANIFEST="$OUT/manifest.tsv"
: > "$MANIFEST"
printf 'view_name\tview_id\ttier\tcustomer\tstatus\tncols\tnrows\tsha256\n' >> "$MANIFEST"

# 并发写入守卫 —— 前置水位
$PSQL -c "SELECT 'GUARD_PRE', now()::text,
  (SELECT max(created_at)::text FROM material_master),
  (SELECT max(created_at)::text FROM material_bom),
  (SELECT max(created_at)::text FROM material_bom_item),
  (SELECT max(created_at)::text FROM element_bom),
  (SELECT max(created_at)::text FROM element_bom_item),
  (SELECT count(*)::text FROM material_master),
  (SELECT count(*)::text FROM material_bom),
  (SELECT count(*)::text FROM material_bom_item),
  (SELECT count(*)::text FROM element_bom),
  (SELECT count(*)::text FROM element_bom_item),
  (SELECT count(*)::text FROM pg_stat_activity WHERE datname='$PGDB' AND state<>'idle');" > "$OUT/guard_pre.txt" 2>&1

# 拉取全部视图定义
$PSQL -c "COPY (SELECT id::text || ',' || sql_view_name || ',' || replace(encode(convert_to(sql_template,'UTF8'),'base64'), chr(10), '') FROM component_sql_view ORDER BY sql_view_name, id) TO STDOUT" > "$OUT/views.csv"

TOTAL=$(wc -l < "$OUT/views.csv")
echo "views: $TOTAL"

i=0
while IFS=, read -r VID VNAME VB64; do
  i=$((i+1))
  VID="${VID//\"/}"; VNAME="${VNAME//\"/}"; VB64="${VB64//\"/}"
  RAW=$(printf '%s' "$VB64" | base64 -d)
  for TIER in t1 t2; do
    # t2(全量行)只跑数据最富的客户, 防 1200 条重查询拖爆; t1 跑全部客户
    if [ "$TIER" = t2 ]; then CUSLIST=("CUST-0004"); else CUSLIST=("${CUSTOMERS[@]}"); fi
    for CUS in "${CUSLIST[@]}"; do
      SQL=$(TIER="$TIER" CUS="$CUS" PD="$PRICE_DATE" MN="$MATNOS" perl -0777 -pe '
        my $tier=$ENV{TIER}; my $cus=$ENV{CUS}; my $pd=$ENV{PD}; my $mn=$ENV{MN};
        my $vf = ($tier eq "t2") ? "(TRUE)" : "(\$1)";
        if ($tier eq "t2") { s/:versionFilter\(\s*([^,\)]+?)\s*,[^\)]*\)/(TRUE)/g; }
        else               { s/:versionFilter\(\s*([^,\)]+?)\s*,[^\)]*\)/($1)/g; }
        s/(?<!:):customerCode\b/'"'"'$cus'"'"'/g;
        s/(?<!:):priceBaseDate\b/DATE '"'"'$pd'"'"'/g;
        s/(?<!:):total_material_no\b/$mn/g;
        if ($tier eq "t2") { s/\b([A-Za-z_][A-Za-z0-9_]*\.)?is_current\b/TRUE/g; }
        s/;\s*\z//;
      ' <<< "$RAW")

      KEY="${VNAME}__${VID:0:8}__${TIER}__${CUS}"
      F="$OUT/data/$KEY.csv"
      ERRF="$OUT/data/$KEY.err"
      if timeout 45 psql -h $PGHOST -U $PGUSER -d $PGDB -qAX -v ON_ERROR_STOP=1 \
           -c "SET statement_timeout='30s'" \
           -c "COPY ($SQL) TO STDOUT WITH (FORMAT csv, HEADER true, NULL '\\N', FORCE_QUOTE *)" \
           > "$F" 2>"$ERRF"; then
        NR=$(( $(wc -l < "$F") - 1 )); [ "$NR" -lt 0 ] && NR=0
        NC=$(head -1 "$F" | awk -F',' '{print NF}')
        SHA=$(sha256sum "$F" | cut -c1-64)
        ST=OK; [ "$NR" -eq 0 ] && ST=EMPTY
        rm -f "$ERRF"
      else
        ST=ERROR; NR=-1; NC=-1
        SHA=$(sha256sum "$ERRF" | cut -c1-64)
        head -c 400 "$ERRF" > "$ERRF.short"; mv "$ERRF.short" "$ERRF"
        rm -f "$F"
      fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$VNAME" "$VID" "$TIER" "$CUS" "$ST" "$NC" "$NR" "$SHA" >> "$MANIFEST"
    done
  done
  [ $((i % 20)) -eq 0 ] && echo "  ... $i/$TOTAL"
done < "$OUT/views.csv"

# 并发写入守卫 —— 后置水位
$PSQL -c "SELECT 'GUARD_POST', now()::text,
  (SELECT max(created_at)::text FROM material_master),
  (SELECT max(created_at)::text FROM material_bom),
  (SELECT max(created_at)::text FROM material_bom_item),
  (SELECT max(created_at)::text FROM element_bom),
  (SELECT max(created_at)::text FROM element_bom_item),
  (SELECT count(*)::text FROM material_master),
  (SELECT count(*)::text FROM material_bom),
  (SELECT count(*)::text FROM material_bom_item),
  (SELECT count(*)::text FROM element_bom),
  (SELECT count(*)::text FROM element_bom_item),
  (SELECT count(*)::text FROM pg_stat_activity WHERE datname='$PGDB' AND state<>'idle');" > "$OUT/guard_post.txt" 2>&1

echo "=== SUMMARY ==="
awk -F'\t' 'NR>1{c[$5]++} END{for(k in c) print k, c[k]}' "$MANIFEST"
