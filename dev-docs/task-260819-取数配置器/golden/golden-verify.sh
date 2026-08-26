#!/usr/bin/env bash
# golden-verify.sh · T-4 golden 逐行等值验证（AC-38，一期总把关）
#
# 【为什么不是 JUnit】AC-38 要求"与现网手写基准组件在同一张报价单上分别 refresh-snapshot，
# quotation_line_component_data.snapshot_rows 逐行逐列等值"——这必须跑在 dev server(8081) +
# dev 库 cpq_db_0724 上（罗克韦尔数据与 COMP-0019/0027/0023/0022 基准组件只在那儿）。
# @QuarkusTest 绑定的是 test profile（cpq_db），两个库不是同一个库（CLAUDE.md 环境事实），
# 所以 T-4 不能用 mvn test 跑，只能是一段对 dev server 的黑盒验证脚本。
#
# 用法：
#   ./golden-verify.sh <builder组件ID> <基准组件code> <报价单ID>
# 例：
#   ./golden-verify.sh 8f3a... COMP-0027 3c9e...
#
# 前置：① 8081/dev 库已在跑（CLAUDE.md 探活口径：curl 业务端点返401）
#       ② <报价单ID> 上已同时挂了 builder 组件与基准组件两个产品卡/页签
#       ③ 两者已各自完成一次 refresh-snapshot（手工在UI点，或调 POST /api/cpq/quotations/{id}/refresh-snapshot）
#
# 判据（需求文档.md §3.6 AC-38）：行数相同、行序相同、每个键的值逐字相同。5类各一条，任一类不等值即不通过。

set -euo pipefail

BUILDER_COMPONENT_ID="${1:?用法: golden-verify.sh <builder组件ID> <基准组件code> <报价单ID>}"
GOLDEN_COMPONENT_CODE="${2:?缺基准组件code，如 COMP-0027}"
QUOTATION_ID="${3:?缺报价单ID}"

DB_HOST="10.177.152.12"
DB_NAME="cpq_db_0724"           # ⚠️ dev 库，不是 test 库 cpq_db
DB_USER="${DB_USERNAME:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-joii5231}"

export PGPASSWORD="$DB_PASSWORD"

echo "=== T-4 golden 验证：builder组件=${BUILDER_COMPONENT_ID} vs 基准=${GOLDEN_COMPONENT_CODE} ==="
echo "=== 库: ${DB_HOST}/${DB_NAME}（dev 库，非 test 库）==="

# 0. 探活验明正身：确认连的是 cpq_db_0724 而非误连 cpq_db（testing.md §4.2 探活必须验明正身）
CONN_INFO=$(psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT current_database();")
if [ "$CONN_INFO" != "$DB_NAME" ]; then
  echo "❌ 连接验明正身失败：期望 ${DB_NAME}，实际 ${CONN_INFO}——停止执行，不产出误判结果"
  exit 1
fi
echo "✅ 探活验明正身通过：current_database()=${CONN_INFO}"

# 1. 取两侧 snapshot_rows（要求非空——空结果不能当"逐行等值"的证据，是断言从未执行的假绿陷阱）
BUILDER_ROWS_FILE=$(mktemp)
GOLDEN_ROWS_FILE=$(mktemp)

psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
  SELECT qlcd.snapshot_rows::text
  FROM quotation_line_component_data qlcd
  JOIN quotation_line_item qli ON qli.id = qlcd.line_item_id
  WHERE qli.quotation_id = '${QUOTATION_ID}'
    AND qlcd.component_id = '${BUILDER_COMPONENT_ID}'
  ORDER BY qlcd.updated_at DESC LIMIT 1;
" > "$BUILDER_ROWS_FILE"

psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
  SELECT qlcd.snapshot_rows::text
  FROM quotation_line_component_data qlcd
  JOIN quotation_line_item qli ON qli.id = qlcd.line_item_id
  JOIN component c ON c.id = qlcd.component_id
  WHERE qli.quotation_id = '${QUOTATION_ID}'
    AND c.code = '${GOLDEN_COMPONENT_CODE}'
  ORDER BY qlcd.updated_at DESC LIMIT 1;
" > "$GOLDEN_ROWS_FILE"

BUILDER_SIZE=$(wc -c < "$BUILDER_ROWS_FILE")
GOLDEN_SIZE=$(wc -c < "$GOLDEN_ROWS_FILE")

if [ "$BUILDER_SIZE" -le 1 ]; then
  echo "❌ builder组件 snapshot_rows 为空——未执行过 refresh-snapshot，或前置②未满足。不构成'逐行等值'的有效对照，判定不通过"
  rm -f "$BUILDER_ROWS_FILE" "$GOLDEN_ROWS_FILE"
  exit 1
fi
if [ "$GOLDEN_SIZE" -le 1 ]; then
  echo "❌ 基准组件(${GOLDEN_COMPONENT_CODE}) snapshot_rows 为空——同上，判定不通过"
  rm -f "$BUILDER_ROWS_FILE" "$GOLDEN_ROWS_FILE"
  exit 1
fi
echo "✅ 两侧 snapshot_rows 均非空（builder=${BUILDER_SIZE}字节, golden=${GOLDEN_SIZE}字节）"

# 2. 用 jq 做结构化逐行逐列比对（行数/行序/每个键值），而非裸文本diff（避免JSON key顺序等噪音误判不等值）
if command -v jq >/dev/null 2>&1; then
  BUILDER_NORM=$(jq -S . "$BUILDER_ROWS_FILE" 2>/dev/null || cat "$BUILDER_ROWS_FILE")
  GOLDEN_NORM=$(jq -S . "$GOLDEN_ROWS_FILE" 2>/dev/null || cat "$GOLDEN_ROWS_FILE")
  BUILDER_ROW_COUNT=$(jq 'length' "$BUILDER_ROWS_FILE" 2>/dev/null || echo "?")
  GOLDEN_ROW_COUNT=$(jq 'length' "$GOLDEN_ROWS_FILE" 2>/dev/null || echo "?")
  echo "行数：builder=${BUILDER_ROW_COUNT} golden=${GOLDEN_ROW_COUNT}"
  if [ "$BUILDER_NORM" == "$GOLDEN_NORM" ]; then
    echo "✅ PASS：${GOLDEN_COMPONENT_CODE} golden 逐行逐列等值"
    rm -f "$BUILDER_ROWS_FILE" "$GOLDEN_ROWS_FILE"
    exit 0
  else
    echo "❌ FAIL：与基准 ${GOLDEN_COMPONENT_CODE} 不等值，差异如下（截断前80行）："
    diff <(echo "$BUILDER_NORM") <(echo "$GOLDEN_NORM") | head -80
    rm -f "$BUILDER_ROWS_FILE" "$GOLDEN_ROWS_FILE"
    exit 1
  fi
else
  echo "⚠️ 未装 jq，退化为裸文本比对（可能因JSON key顺序产生假阴性，建议装jq后重跑）"
  if diff -q "$BUILDER_ROWS_FILE" "$GOLDEN_ROWS_FILE" >/dev/null; then
    echo "✅ PASS（裸文本比对）"
    exit 0
  else
    echo "❌ FAIL（裸文本比对），diff："
    diff "$BUILDER_ROWS_FILE" "$GOLDEN_ROWS_FILE" | head -80
    exit 1
  fi
fi
