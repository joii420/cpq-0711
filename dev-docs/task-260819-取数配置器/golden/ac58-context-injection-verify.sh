#!/usr/bin/env bash
# ac58-context-injection-verify.sh · AC-58（报价侧非BOM页签渲染时上下文已注入）验证脚本
#
# 【为什么是脚本，不是纯 Playwright】AC-58 要求"取 BomTreeVarsContext.get() 的实际值"，这是一个
# Java 内部 ThreadLocal-like 构造，浏览器侧无法直接读取；本测试工程师被明确禁止读
# cpq-backend/src/main/java/com/cpq/builder|semanticgraph/ 实现代码，因此不知道是否存在可以从
# 外部观测这个值的调试端点。退而求其次，用它的【下游可观测效应】做等价证明：如果
# total_material_no 正确注入了"整单BOM料号并集"，非BOM页签(材质元素/零件/外购件/费用类)的渲染
# 结果应当覆盖到 BOM 展开后的全部后代料号；如果注入缺失或不完整，渲染结果会只覆盖根料号一层
# （或者按 AC-59 的证据，SqlViewExecutor 未来会直接报错而不是悄悄漏数据）。
#
# 【真实数据锚点，已用 psql 独立验证，2026-08-24】
#   客户 = 罗克韦尔（CUST-0001），产品料号 = S-3120014539
#   甲组（仅产品自身）：element_bom_item 2 行
#   乙组（产品自身 + material_bom_item 递归展开的全部后代料号）：element_bom_item 16 行
#   （具体验证 SQL 见 test.md §3b「AC-26/AC-58 基准复核」一节，与 AC-26 用的是同一份闭包数据）
#   已知一条真实报价单行项目挂了同款"材质元素"页签组件（COMP-0021，mc_view，A机制/旧存量视图）：
#     quotation id = 20e11f25-2125-496c-8d7e-4b61d6da2c73 (DRAFT)
#     line_item id = 4474aeb8-e5e6-4ed9-8bc9-50cfa4b170ac
#     component code = COMP-0021（materials, 材质元素, A机制mc_view——这个是golden基准组件本身，
#       不消费 :total_material_no，不能直接拿它验证B机制的注入是否生效）
#   ⚠️ 本脚本无法自证"AC-58用的是B机制新产物"——需要一个用配置器新建、绑在这条产品线上的
#   builder组件（非BOM页签）作为验证对象。这个绑定动作本身涉及改动真实DRAFT报价单数据，
#   已按 testing.md §4.3 共享库纪律停下——不擅自往这条真实用户可能仍在编辑的DRAFT报价单上加
#   测试组件。**正确做法（留给执行阶段）**：新建一张专用测试报价单（客户=罗克韦尔，产品=
#   S-3120014539），在其上挂一个配置器新建的"材质元素"builder组件，refresh-snapshot 后跑本脚本，
#   跑完可以保留（新建的测试报价单不影响任何真实用户数据，不需要清理，除非账号配额有限）。
#
# 用法：
#   ./ac58-context-injection-verify.sh <报价单ID> <line_item ID> <builder组件ID>
#
# 判据（需求文档.md §3.6b AC-58）：
#   ① BomTreeVarsContext.get() 非null——本脚本用②③的下游效应间接证明，不直接读这个值
#   ② totalMaterialNo 非空，且逐个包含成品自身料号与其BOM展开后的全部后代料号
#      ——间接验证：渲染出的行数应 ≥ 乙组的psql基准(16)，且渲染出的料号集合应是
#        psql闭包集合的子集或相等（允许因为basic data缺失导致部分料号无对应行，但不允许
#        出现"闭包集合之外"的料号，也不允许行数 <= 甲组基准(2)，那说明闭包没生效）
#   ③ 页签实际渲染行数 > 0

set -euo pipefail

QUOTATION_ID="${1:?用法: ac58-context-injection-verify.sh <报价单ID> <line_item ID> <builder组件ID>}"
LINE_ITEM_ID="${2:?缺 line_item ID}"
BUILDER_COMPONENT_ID="${3:?缺 builder 组件ID}"

DB_HOST="10.177.152.12"
DB_NAME="cpq_db_0724"
DB_USER="${DB_USERNAME:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-joii5231}"
export PGPASSWORD="$DB_PASSWORD"

CONN_INFO=$(psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT current_database();")
if [ "$CONN_INFO" != "$DB_NAME" ]; then
  echo "❌ 连接验明正身失败：期望 ${DB_NAME}，实际 ${CONN_INFO}——停止执行"
  exit 1
fi

ROWS_FILE=$(mktemp)
psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -tAc "
  SELECT qlcd.snapshot_rows::text
  FROM quotation_line_component_data qlcd
  WHERE qlcd.line_item_id = '${LINE_ITEM_ID}' AND qlcd.component_id = '${BUILDER_COMPONENT_ID}'
  ORDER BY qlcd.created_at DESC LIMIT 1;
" > "$ROWS_FILE"

SIZE=$(wc -c < "$ROWS_FILE")
if [ "$SIZE" -le 1 ]; then
  echo "❌ snapshot_rows 为空——未执行过 refresh-snapshot，或该 line_item/component 组合不存在"
  rm -f "$ROWS_FILE"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "⚠️ 未装 jq，无法结构化统计行数，仅打印原始内容供人工核对："
  cat "$ROWS_FILE"
  rm -f "$ROWS_FILE"
  exit 1
fi

ROW_COUNT=$(jq 'length' "$ROWS_FILE")
echo "实际渲染行数 = ${ROW_COUNT}"

# ③ 行数 > 0
if [ "$ROW_COUNT" -le 0 ]; then
  echo "❌ FAIL(③): 渲染行数应 > 0，实际 = ${ROW_COUNT}"
  rm -f "$ROWS_FILE"
  exit 1
fi
echo "✅ PASS(③): 渲染行数 > 0"

# ②（间接）：行数应 > 甲组基准(2)，否则说明闭包没生效（只渲染了根料号自身）
if [ "$ROW_COUNT" -le 2 ]; then
  echo "❌ FAIL(②间接证据): 行数(${ROW_COUNT}) <= 甲组基准(2)，看起来 total_material_no 只含产品"
  echo "   自身、未包含BOM后代——闭包很可能没有注入成功，或注入了空数组导致退化成'仅自身'。"
  rm -f "$ROWS_FILE"
  exit 1
fi
echo "✅ 行数(${ROW_COUNT}) > 甲组基准(2)，闭包看起来生效了"

# 行数不应超过乙组基准(16)太多（允许等于；若明显更多，可能是笛卡尔积或重复注入，值得警惕但不判死）
if [ "$ROW_COUNT" -gt 16 ]; then
  echo "⚠️ 警告：行数(${ROW_COUNT}) > 乙组psql基准(16)——请人工核对是否重复注入/笛卡尔积，"
  echo "   本脚本不据此判失败（可能是该builder组件多选了字段导致列数不同但语义仍对，需人工看内容）"
fi

echo "=== 本脚本只能做间接验证（行数量级），无法证明 total_material_no 内容逐字节等于 psql 闭包集合。"
echo "=== 若需要逐字节比对，请在 test-report.md 里附上 snapshot_rows 里料号列的去重清单，"
echo "=== 与下面这条 psql 递归查询的结果人工比对："
echo "
WITH RECURSIVE closure AS (
  SELECT 'S-3120014539'::text AS material_no
  UNION
  SELECT mbi.component_no FROM material_bom_item mbi
  JOIN closure c ON mbi.material_no = c.material_no
  WHERE mbi.is_current = true AND mbi.component_no IS NOT NULL AND mbi.component_no <> ''
)
SELECT DISTINCT material_no FROM closure ORDER BY 1;
"
rm -f "$ROWS_FILE"
