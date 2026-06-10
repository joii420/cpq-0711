# Excel 页签连表公式构建器 — 设计文档

- **日期**：2026-06-10
- **状态**：设计已确认，待写实现计划
- **原型**：`docs/html/excel-tab-join-formula-builder.html`（可点击）
- **作用域**：Excel 模板视图列配置，新增一种列来源类型 `TAB_JOIN_FORMULA`

---

## 1. 目标与背景

让客户在 Excel 模板配置页里，用**可视化构建器**为某一列配置一个"跨页签关联 + 条件过滤 + 聚合"的公式，最终在 Excel 单元格里得到**一个单值**。值取自**产品卡片中各页签的内容数据**。

现状利好（已存在，复用）：
- `ExcelViewService.buildRowData` 已能在单卡片内引用页签小计 + 对页签行 `SUM`（见 `ExcelViewCardFormulaIT.card_formula_column_evaluates_subtotal_plus_sum_over`）。
- `CardEffectiveRows.TabRows` 已把每张卡片的页签内容**物化成行**（含明细行 + 小计/总计）。
- `com.cpq.formula.FormulaEngine`（JEXL + `SUM/AVG/MIN/MAX/COUNT/ROUND/...`）现成，`SUM(collection, field)` 已支持对行集合聚合。

本特性 = 在上述地基上叠加：**多页签 JOIN + 组级 WHERE + 计算组 + 可视化构建器 + 试算**。

---

## 2. 关键决策（需求澄清结论）

| # | 决策 | 结论 |
|---|------|------|
| 产物 | 公式的最终产物 | 扩展 Excel 模板的列取值能力：**新增一种列来源**，配出来的是某一列的值 |
| 作用域 | join/聚合发生在哪一层 | **单张产品卡片内**（每张卡片各算各的，一列一卡一值） |
| 结果粒度 | 一列结果 | **永远是单值**；正常用法套聚合函数；裸明细字段不写聚合时退化取**过滤后第一行**（不推荐） |
| JOIN 语义 | 一对多放大 | **INNER JOIN**，笛卡尔放大由用户自负（像 SQL join） |
| 条件作用域 | WHERE | **组内全局 WHERE**：过滤本组 JOIN 宽表的行，过滤后该组所有聚合在同一批剩余行上算 |
| 多页签串联 | 第 3+ 页签关联谁 | **任意 join 图**：加新页签先选"关联到哪个已加入页签"，再选两边列；**默认**新页签与主页签同名列自动设为关联键，用户可改 |
| 存储 | 文本 vs 结构化 | **文本聚合表达式 + 结构化元数据**（tabs/joins/where 分面板） |
| 共存 | 与老 FORMULA 列 | **新增独立列来源类型** `TAB_JOIN_FORMULA`；老视图列 FORMULA 列原样不动（老的是开发测试数据） |
| 试算 | 是否预览 | **要**，选已有报价单/核价单里的一张产品卡片作样本，调后端真实试算 |
| 函数 | 一期范围 | `SUM AVG MIN MAX COUNT` + `+ - * / ( )`；其余函数后续补充 |
| 多连表上下文 | 一个表达式多段独立连表 | **引入"计算组"**：每组 = 页签 JOIN + 组 WHERE + 组内聚合表达式 → 一个标量；最终表达式层用四则+常数组合各组结果 |
| 组内表达式 | 丰富度 | 允许多聚合四则，如 `SUM(x)/SUM(y)` |
| 最终表达式层 | 范围 | 只用 `+ - * / ( )` 和常数组合各组结果，不放函数 |
| 缺值语义 | 取不到值 | 缺值默认 **0** 参与计算；**除数取不到/为 0 默认按 1**（本特性专属，覆盖引擎默认 DIV_ZERO） |
| 插入交互 | 变量/运算符插哪 | **跟随焦点**：插入到当前聚焦的表达式框（组聚合框或最终框） |
| 字段来源 | 页签/字段清单 | 来自**模板的页签定义**（不依赖样本卡片） |
| 边界 | 是否替换页面 | **不替换**：现有 Excel 配置页保留，仅新增一种列来源 + 该列专属构建器抽屉；其它列来源以后可按需移除 |

---

## 3. 架构方案（方案 1：内存连表 + 复用 FormulaEngine）

运行态不下推 SQL，不在前端重写引擎。从已物化的卡片页签行出发，在 Java 内存里做 JOIN+WHERE，再把行集喂给现成 `FormulaEngine`。试算与正式渲染走**同一后端求值入口**，保证口径一致。

被否方案：
- **方案 2（翻译成动态 SQL 下推 PG）**：页签行是卡片运行态数据（含手动/输入行、snapshot_rows+row_data 两路），难干净映射回 SQL 表，且极易再踩 `$view` 串号 / 视图放大坑（AP-22 / AP-53）。否决。
- **方案 3（纯前端 JS 求值）**：Excel 实际在后端渲染，会变成两套引擎双维护、口径漂移。否决。

---

## 4. 数据模型 — 列配置 JSON

存进现有 Excel 视图列配置 JSON（`TemplateExcelViewResource` 的 `getConfig/saveConfig`），与老列同表，不动老结构。新列来源类型示例：

```json
{
  "key": "col_12",
  "title": "单件总成本",
  "sourceType": "TAB_JOIN_FORMULA",
  "finalExpression": "组1 + 组2 - 100",
  "groups": [
    {
      "ref": "组1",
      "mainTab": "投料",
      "tabs": ["投料", "加工"],
      "joins": [
        { "leftTab": "加工", "leftCols": ["物料编码"],
          "rightTab": "投料", "rightCols": ["物料编码"], "type": "INNER" }
      ],
      "where": [
        { "col": "投料.类型", "op": "=", "value": "主料", "logic": "AND" }
      ],
      "aggExpression": "SUM([投料.金额])"
    },
    {
      "ref": "组2",
      "mainTab": "回料",
      "tabs": ["回料"],
      "joins": [],
      "where": [],
      "aggExpression": "SUM([回料.回料金额])"
    }
  ]
}
```

字段约定：
- `ref`：组引用名（如 `组1`），最终表达式里用它引用本组标量结果。组内唯一。
- `mainTab`：主页签（用户点的第一个页签）。`tabs[0] === mainTab`。
- `joins[]`：关联边，每边 `leftTab/leftCols` 与 `rightTab/rightCols`（复合键，多列 = AND 等值），`type` 一期固定 `INNER`。
- `where[]`：组级条件，`op ∈ {=, >, <, 包含, 不包含}`，`logic ∈ {AND, OR}`（首条 `logic` 为空）。
- `aggExpression`：组内聚合表达式，变量以 `[页签.字段]` 形式引用；允许多聚合四则。
- `finalExpression`：组结果（`组N`）+ 常数 + `+ - * / ( )`。

变量令牌：`[页签名.字段名]`。字段含该页签组件定义的明细字段 + 小计/总计。

---

## 5. 求值流程（后端）

新增 `TabJoinPlanEvaluator`（内存 join 求值器），由 `ExcelViewService` 在渲染该列时调用。渲染 Excel 每行（= 一张产品卡片）对 `TAB_JOIN_FORMULA` 列：

1. 取卡片各页签行 `CardEffectiveRows.TabRows`（已物化，含小计/总计）。
2. 逐 `group`：
   1. **JOIN**：按 `joins` 在内存做 INNER JOIN 成宽表，列命名空间 `页签.字段`；复合键等值匹配；一对多笛卡尔放大保留（用户自负）。单页签组无 join，直接用该页签行。
   2. **WHERE**：按 `where`（含 AND/OR）过滤宽表行。
   3. **缺值归一**：明细字段缺列/缺值 → 按 `0` 参与。
   4. **聚合（两层作用域求值）**：`aggExpression` 不是一次扁平求值，而是分两层（关键，勿实现成"SUM 只能填单列"）：
      - **聚合函数内 = 逐行子表达式**：`SUM/AVG/MIN/MAX/COUNT` 的参数是一个**行级子表达式**（可含 `[页签.字段]`、四则、括号），对本组 JOIN 宽表**每一行**先求值得到一个数，再做聚合 reduce → 标量。等价 SQL `SUM(投料.单价 * 加工.工时)`。
      - **聚合函数外 = 标量上下文**：聚合调用的结果是标量；裸 `[页签.字段]` 不在任何聚合内时按标量取**过滤后第一行**（缺则 0）；标量之间用 `+ - * / ( )` 和常数组合。
      - 实现：`TabJoinPlanEvaluator` 解析 `aggExpression`，识别聚合调用 → 对其行级子表达式逐行用 `FormulaEngine` 求值收集成数组 → reduce 成标量回填；外层标量再用 `FormulaEngine` 算。**不能**直接把整条 `aggExpression` 当 `SUM(collection, field)` 调（现有引擎签名只支持单列，撑不住 `SUM(a*b)`）。
      - 除数取不到/为 0 → 按 `1`。
   5. 把标量绑到变量 `组N`。
3. `FormulaEngine` 算 `finalExpression`（`组N` 当变量 + 四则 + 常数）→ 最终标量。
4. 写入 Excel 单元格。

示例：组内 `SUM([投料.单价]*[加工.工时]) + SUM([投料.单价]*[投料.数量]) + [投料.金额]` →
项1/项2 各逐行算积再求和（标量），项3 取第一行 `投料.金额`（标量），三者相加 = 组标量。

注意：
- 小计/总计是页签级单值，在 JOIN 宽表里广播到每行；对其再 `SUM` 会被行数放大（AP-22 教训，UI 给提示，但语义按用户自负）。
- **同理**：在一个已与从页签 JOIN 的组里，对**主页签明细列**做 `SUM`（如纯 `SUM(投料.单价*投料.数量)`）会因一对多放大被乘以匹配行数。规范建议：**纯单页签的聚合放进一个不连其它页签的独立组**。

---

## 6. 前端 — `TabJoinFormulaBuilderDrawer`

- 入口：`cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx` 列来源下拉新增「页签连表公式」；选它打开本抽屉（右侧 `Drawer`，宽 1200，符合 UI 交互规范）。
- 组件拆分（每个单一职责、可独立测试）：
  - `FinalExpressionPanel`：最终表达式框 + 运算符工具条 + 整列试算回显。
  - `GroupCard`：单个计算组容器，含：
    - `TabPills`：参与页签（主页签紫色标记）+「+添加页签」。
    - `JoinKeyModal`：添加页签时弹出；先选关联到哪个已加入页签 → 选两边列对应（复合键多对，默认 INNER）；**默认按同名列预填关联键**。
    - `VariableMatrix`：每行一个页签 + 其全部字段 chip（明细/小计-总计）；点字段 → 菜单「插入到表达式 / 设置条件」。
    - `WherePanel`：组级条件行（字段 / op / 值 / AND·OR）。
    - `GroupExprPanel`：组内聚合表达式框 + 运算符 + 函数（SUM/AVG/MIN/MAX/COUNT）工具条 + 本组试算回显。
  - `SampleCardPicker`：从已有报价单/核价单选一张产品卡片作样本。
- 状态：`activeExprTarget`（跟随焦点决定插入到哪个框）。
- 数据来源：页签/字段清单读**模板页签定义**接口（非样本卡片）。

---

## 7. 试算接口

- `POST /api/cpq/templates/{templateId}/excel-view/dry-run-tab-formula`
- body：`{ groups, finalExpression, sampleQuotationId | sampleCostingId, lineItemId }`
- resp：`{ groupValues: { "组1": 1820.00, "组2": 96.00 }, finalValue: 1816.00, errors: [] }`
- 复用 `ExcelViewService` / `TabJoinPlanEvaluator` 同一求值路径（与正式渲染口径一致）。
- 样本卡片 = 已有报价单/核价单里的一个 `lineItem`（产品卡片）。

---

## 8. 错误处理

- 缺值 → `0`；除数缺/0 → `1`（本特性专属，覆盖默认 `DIV_ZERO`）。
- 配置期 `validate` 拦截：JOIN 关联列不存在 / 未知页签字段 / 未知组引用（`finalExpression` 引用了不存在的 `ref`）/ 表达式语法错。
- 运行态兜底：连不通 / 求值异常 → 返回 `0` 并记 trace，不抛断渲染。
- 错误以 `FormulaError` 风格回传，UI 标红显示。

---

## 9. 测试

- **后端单测** `TabJoinPlanEvaluatorTest`：单表 / 双表 INNER / 复合键 / 一对多放大 / where 各算子(`= > < 包含 不包含`) + AND·OR / 缺值=0 / 除数=1 / 多组 + 最终四则 / 未知 ref 校验。
- **集成测试**（仿 `ExcelViewCardFormulaIT`）：样本卡片端到端试算 = 期望值。
- **前端**：Drawer 渲染 + token 插入跟随焦点 + 添加页签默认 join 预填 + 试算回显。
- **E2E**：本特性属 Excel 视图渲染链路改动，按 `docs/E2E测试方法.md` 跑（改 `ExcelViewConfigTab.tsx` / `ExcelViewService.java` 触发强制 E2E）。

---

## 10. 影响面与风险

- 改动文件（预估）：
  - 后端：`ExcelViewService.java`（列求值分支接入）、新增 `TabJoinPlanEvaluator.java`、`TemplateExcelViewResource.java`（试算端点 + validate）。
  - 前端：`ExcelViewConfigTab.tsx`（新列来源 + 抽屉入口）、新增 `TabJoinFormulaBuilderDrawer` 及子组件、模板页签定义/样本卡片取数 service。
- 风险点：
  - JOIN 放大（AP-22）：一期按用户自负 + UI 提示，不自动去重。
  - 列来源协议传播：`TAB_JOIN_FORMULA` 是新的列**来源**类型（非组件 `field_type`），不触发 AP-44 的 17 检查点矩阵，但需确认 Excel 列来源在前后端的枚举/渲染/校验各处同步。
  - 缺值/除数兜底语义与引擎默认不同，须在求值器内显式实现，勿依赖 `FormulaEngine` 默认行为。

---

## 11. 一期不做（YAGNI）

- LEFT/OUTER JOIN（一期只 INNER）。
- 最终表达式层的函数（只四则+常数）。
- `SUM/AVG/MIN/MAX/COUNT` 以外的函数（后续按需补充）。
- 跨产品卡片 / 整单作用域（一期只单卡片）。
- 页签数据与视图列变量（汇率等）混引（一期纯页签）。
- JOIN 放大自动去重/防呆（一期用户自负 + 提示）。
