#!/usr/bin/env bash
# ac61-legacy-baseline.sh · AC-61（存量 A 机制视图不被动到）基线捕获 + 收尾复核脚本
#
# 用法：
#   ./ac61-legacy-baseline.sh capture   # 开工前跑一次，把当前基线写入 ac61-baseline-captured.txt
#   ./ac61-legacy-baseline.sh verify    # 收尾时再跑一次，与 capture 时的文件逐行 diff，不等值即失败
#
# 【为什么是脚本不是 JUnit】AC-61③ 的实测基线是 dev 库 cpq_db_0724 特有的真实业务数据
# （26个存量视图/1183个字段这组数字，在 test 库 cpq_db 上完全测不出来——本测试工程师已实测验证：
#  test 库上同样口径的查询给出的是 40个视图/2个含bom_closure的组件，与 dev 库的 26/66 对不上，
#  两个库的业务数据集合本就不同）。test.md §4 矩阵把 AC-61 标为 T-3（test profile）是不准确的，
#  已在回报中向主线标出这处矩阵-AC 原文不一致，本脚本按 AC 原文的实际要求走 dev 库。
#
# 判据（需求文档.md §3.6b AC-61）：
#   ① 现网66个仍用 WITH RECURSIVE bom_closure 的存量组件，sql_template 逐字节不变
#   ② 存量组件 fields 中 default_source.path / basic_data_path 的取值逐字节不变
#   ③ 收尾时按同一口径重数，26视图/1183字段两组数字必须与开工前一致

set -euo pipefail

MODE="${1:?用法: ac61-legacy-baseline.sh <capture|verify>}"

DB_HOST="10.177.152.12"
DB_NAME="cpq_db_0724"           # ⚠️ dev 库，不是 test 库 cpq_db（见上方说明）
DB_USER="${DB_USERNAME:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-joii5231}"
export PGPASSWORD="$DB_PASSWORD"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAPTURE_FILE="${SCRIPT_DIR}/ac61-baseline-captured.txt"

# 0. 探活验明正身
CONN_INFO=$(psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT current_database();")
if [ "$CONN_INFO" != "$DB_NAME" ]; then
  echo "❌ 连接验明正身失败：期望 ${DB_NAME}，实际 ${CONN_INFO}——停止执行"
  exit 1
fi

run_queries() {
  # ① 66个A机制存量组件：数量 + sql_template 的聚合md5（任一字节变化，md5必变）
  psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
    SELECT 'AC61_A_MECHANISM_COUNT|' || count(*)
    FROM component_sql_view
    WHERE builder_config IS NULL AND sql_template ILIKE '%bom_closure%';
  "
  psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
    SELECT 'AC61_A_MECHANISM_SQL_MD5|' || md5(string_agg(id::text||':'||md5(sql_template), ',' ORDER BY id))
    FROM component_sql_view
    WHERE builder_config IS NULL AND sql_template ILIKE '%bom_closure%';
  "
  # ② 26个存量视图（去重 sql_view_name）+ 143个存量组件的 fields 聚合md5
  psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
    SELECT 'AC61_LEGACY_VIEW_COUNT|' || count(DISTINCT sql_view_name)
    FROM component_sql_view WHERE builder_config IS NULL;
  "
  psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
    WITH legacy_components AS (
      SELECT DISTINCT c.id, c.fields
      FROM component c
      JOIN component_sql_view v ON v.component_id = c.id
      WHERE v.builder_config IS NULL
    )
    SELECT 'AC61_LEGACY_FIELDS_MD5|' || md5(string_agg(fields::text, '|' ORDER BY id))
    FROM legacy_components;
  "
  # ③ 1183个字段总数（跨全部存量组件，报价侧+核价侧）——鉴别粒度到"总数"，不到"报价/核价拆分"，
  #    因为拆分口径（791+30 vs 267）与本测试工程师独立复核的结果对不上，已在 test.md §3b 与
  #    回报中单独登记为待主线/PM核实的差异，不写进本脚本的硬性判据，避免用一个存疑的数字
  #    去锁定基线。
  psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
    WITH legacy_components AS (
      SELECT DISTINCT c.id, c.fields
      FROM component c
      JOIN component_sql_view v ON v.component_id = c.id
      WHERE v.builder_config IS NULL
    )
    SELECT 'AC61_LEGACY_FIELDS_TOTAL|' || sum(jsonb_array_length(fields))
    FROM legacy_components;
  "
}

if [ "$MODE" = "capture" ]; then
  run_queries > "$CAPTURE_FILE"
  echo "✅ 基线已捕获到 ${CAPTURE_FILE}："
  cat "$CAPTURE_FILE"
elif [ "$MODE" = "verify" ]; then
  if [ ! -f "$CAPTURE_FILE" ]; then
    echo "❌ 找不到基线文件 ${CAPTURE_FILE}，须先跑 capture"
    exit 1
  fi
  CURRENT=$(mktemp)
  run_queries > "$CURRENT"
  if diff -q "$CAPTURE_FILE" "$CURRENT" > /dev/null; then
    echo "✅ PASS：AC-61 存量基线逐项一致（未被本任务动到）"
    cat "$CURRENT"
    rm -f "$CURRENT"
    exit 0
  else
    echo "❌ FAIL：AC-61 存量基线发生变化，diff："
    diff "$CAPTURE_FILE" "$CURRENT"
    rm -f "$CURRENT"
    exit 1
  fi
else
  echo "未知模式: $MODE（应为 capture 或 verify）"
  exit 1
fi
