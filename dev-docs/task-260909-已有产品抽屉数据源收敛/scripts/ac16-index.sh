#!/usr/bin/env bash
# AC-16 · 索引已建（D-2 裁决后的可证伪版本）
#
# 断言两条（EXPLAIN 那半条已从 AC 删除 —— 它双向都是假的，见需求文档 AC-16 注）：
#   ① ds_quote_customer_part 上存在名为 idx_ds_quote_customer_part_customer_no 的索引
#   ② 迁移 V436 在 flyway_schema_history 中 success = t
#
# 🚫 只读脚本，不含任何 DDL/DML。
set -euo pipefail
export PGPASSWORD=joii5231
PSQL="psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -P pager=off"

echo "=== 证据 1/3：\\d ds_quote_customer_part ==="
$PSQL -c '\d ds_quote_customer_part'

echo "=== 证据 2/3：索引存在性 ==="
IDX=$($PSQL -t -A -c "SELECT indexname FROM pg_indexes WHERE tablename='ds_quote_customer_part' AND indexname='idx_ds_quote_customer_part_customer_no'")
echo "查得: '${IDX}'"
[ "$IDX" = "idx_ds_quote_customer_part_customer_no" ] || { echo "❌ AC-16 断言① 失败：索引不存在"; exit 1; }

echo "=== 证据 3/3：V436 迁移状态 ==="
$PSQL -c "SELECT version, description, success, installed_on FROM flyway_schema_history WHERE version='436'"
OK=$($PSQL -t -A -c "SELECT success FROM flyway_schema_history WHERE version='436'")
[ "$OK" = "t" ] || { echo "❌ AC-16 断言② 失败：V436 success=${OK}"; exit 1; }

echo "=== 证伪对照：查一个不存在的索引名应返回空 ==="
BOGUS=$($PSQL -t -A -c "SELECT indexname FROM pg_indexes WHERE tablename='ds_quote_customer_part' AND indexname='idx_task260909_definitely_not_exist'")
[ -z "$BOGUS" ] || { echo "❌ 证伪失败：这条判据恒真，不算证据"; exit 1; }
echo "✅ AC-16 两条断言均通过，且判据已证伪（非恒真）"
