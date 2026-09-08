#!/usr/bin/env bash
# task-260907 · B-13 —— V6 八张 pending 表的【内容 md5】基线采集
#
# 🚫 只比行数不够：回填改的是 is_current 与列值，行数可能不变而内容已变（backtask B-13 原话）。
#    故这里对「全表按稳定排序拼成的文本」取 md5，行数与内容任一变化都会变。
# 用法：  ./v6-content-md5.sh before   /  ./v6-content-md5.sh after
#         diff <(...) 两次输出，逐表相同 = 回填确为 no-op。
set -euo pipefail
TAG="${1:-snapshot}"
export PGPASSWORD="${PGPASSWORD:-joii5231}"
PGHOST="${PGHOST:-10.177.152.12}"; PGUSER="${PGUSER:-postgres}"; PGDATABASE="${PGDATABASE:-cpq_db_0724}"

# QuoteBackfillService.PENDING_TABLES 原样照抄（8 张）
TABLES="unit_price material_bom material_bom_item element_bom element_bom_item capacity plating_scheme material_customer_map"

echo "# $TAG  $(date -Iseconds)"
for t in $TABLES; do
  # 按 id 排序、整行转文本 —— 列增删也会反映到 md5 里
  md5=$(psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tA -c \
    "SELECT md5(string_agg(x, E'\n' ORDER BY x)) FROM (SELECT t::text AS x FROM $t t) s")
  n=$(psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tA -c "SELECT count(*) FROM $t")
  printf '%-28s rows=%-8s md5=%s\n' "$t" "$n" "$md5"
done
