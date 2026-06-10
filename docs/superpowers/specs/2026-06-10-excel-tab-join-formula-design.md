# Excel 页签连表公式构建器 — 设计文档（v2 终版）

- **日期**：2026-06-10
- **状态**：设计已确认（v2），待按 v2 重写实现计划继续
- **原型**：`docs/html/excel-tab-join-formula-builder-v2.html`（可点击，点击事件全功能，即抽屉内容版）
- **作用域**：Excel 模板视图列配置，新增一种列来源类型 `TAB_JOIN_FORMULA`

> **修订记录**：v1（计算组 + 显式 JOIN 关联键 + 组级 WHERE）已于 2026-06-10 被 **v2** 取代。v2 核心简化：**取消计算组、取消 WHERE、用行键(rowKeyFields)自动对齐、单表达式 + 加减项分段自动求和**。旧原型 `excel-tab-join-formula-builder.html` 仅留作历史对照。

---

## 1. 目标与背景

让客户在 Excel 模板配置页里，用**可视化构建器**为某一列配置一个"跨页签按行键自动对齐 + 聚合"的公式，最终在 Excel 单元格里得到**一个单值**。值取自**产品卡片中各页签的内容数据**。

现状利好（已存在，复用）：
- `ExcelViewService.buildRowData` 已能在单卡片内引用页签小计 + 对页签行 `SUM`（见 `ExcelViewCardFormulaIT`）。
- `CardEffectiveRows.TabRows` 已把每张卡片的页签内容**物化成行**（含明细行 + `subtotal` 页签小计 + `subtotalByColumn` 列小计）。
- `com.cpq.quotation.service.card.CardDataProvider`：`rowsOf(tabKey)` / `subtotalOf(tabKey)` / `subtotalOfColumn(tabKey,col)`，tabKey=`componentId:sortOrder`。
- 已完成的 `com.cpq.quotation.service.tabjoin.SafeArithmetic`（缺值=0/除数0或缺=1 的 JEXL 算术）。

---

## 2. 关键决策（v2 需求澄清结论）

| # | 主题 | 结论 |
|---|------|------|
| 产物 | 公式产物 | 新增列来源 `TAB_JOIN_FORMULA`，配出来是某一列的值 |
| 作用域 | 计算范围 | **单张产品卡片内**，结果**永远是单值**写入单元格 |
| 计算组 | 是否分组 | **取消**。一个列 = **一个表达式**，无组、无 final/子表达式两层 |
| 连表方式 | 页签如何关联 | **行键自动对齐**：所有页签全展示，不选主/副；按各页签 `rowKeyFields`（组件管理里配的行键字段）**完全相等**判定同一"行键类"，同类页签自动对齐。行键唯一→无笛卡尔积 |
| 对齐语义 | 行全集 | 同类页签按行键**全外连**（行键并集，缺失行的字段→0）；对齐行集 = 表达式**实际引用到的明细页签**的行键并集 |
| WHERE | 条件过滤 | **取消**。不再有行过滤，直接点选字段运算 |
| 可选字段 | 每页签带出 | ① 明细字段（逐行）② 各"小计列的总计"（is_subtotal 列的列合计）③ 页签总计（整页签小计），三类按令牌名区分 |
| 置灰 | 字段可选性 | 只有点"明细字段"**锁定行键类**；锁定后行键不同页签的**明细置灰**、其**总计字段仍可点**；表达式不再含该类明细时**自动解锁**；总计字段全程可点、不锁定 |
| 坍缩 | 多行→单值 | 明细字段**默认按对齐行自动求和**；可显式套 `AVG/MIN/MAX/COUNT` 改聚合方式（C 选项） |
| 求值规则 | 自动求和边界 | 表达式按顶层 `+ -` 拆"加减项"，逐项判定（见 §5 规则 + 示例表） |
| 缺值/除零 | 兜底 | 缺值→0；除数 0 或缺→按 1 |
| 函数集 | 一期 | `SUM/AVG/MIN/MAX/COUNT` + `+ - * / ( )`（SUM 因默认自动求和一般不用写） |
| 试算 | 预览 | 保留：从已有报价单/核价单选一张产品卡片样本，调后端真实算出**单值** |
| 共存/边界 | 与老列 | 新增独立列来源，老 FORMULA/CARD_FORMULA 列不动；不替换 Excel 配置页 |
| 插入交互 | 变量/运算符 | 插入到光标处；页签/字段清单来自**模板页签定义**（非样本卡片） |

---

## 3. 架构方案（不变：内存对齐 + 复用 FormulaEngine/JEXL）

运行态从已物化的卡片页签行（`CardDataProvider`）出发，在 Java 内存里按行键对齐，再用 JEXL（+`SafeArithmetic`）求值。试算与正式渲染走**同一后端求值入口**，口径一致。不下推 SQL、不在前端重写引擎（理由同 v1：避免视图放大/串号坑、避免双引擎漂移）。

---

## 4. 数据模型 — 列配置 JSON（v2，大幅简化）

```json
{
  "col_key": "L",
  "title": "单件总成本",
  "source_type": "TAB_JOIN_FORMULA",
  "expression": "[投料.金额] * [加工.工时] + [回料(总计)]",
  "tabs": [
    { "alias": "投料", "tabKey": "<cid>:0", "rowKeyFields": ["物料编码"] },
    { "alias": "加工", "tabKey": "<cid>:1", "rowKeyFields": ["物料编码"] },
    { "alias": "回料", "tabKey": "<cid>:2", "rowKeyFields": ["物料编码","工序"] }
  ]
}
```

- **没有** groups / main_tab / joins / where。只剩 `expression` + 引用到的页签元数据（`alias`→`tabKey` + `rowKeyFields`）。
- 令牌三类：
  - 明细：`[投料.金额]`
  - 小计列总计：`[投料.金额(总计)]`（列名后 `(总计)` 后缀）
  - 页签总计：`[投料(总计)]`（页签名后 `(总计)`，无列名）

---

## 5. 求值流程（后端，`TabJoinPlanEvaluator` v2 重写）

渲染 Excel 每行（= 一张产品卡片）对 `TAB_JOIN_FORMULA` 列：

1. 解析 `expression` 里的 `[别名....]` 令牌，得引用到的页签集合。
2. **行键对齐**：取引用到的**明细页签**（被裸明细字段或聚合函数内明细引用的页签），按其 `rowKeyFields`（同类应完全相等）做**全外连**——行键并集，每个行键组合一行，缺失页签的字段在该行视为缺失（取值→0）。宽行键 `别名.字段`。
3. **拆加减项**：把 `expression` 按顶层 `+ -`（尊重括号）拆成带符号的"加减项"。
4. **逐项求值**（两条规则）：
   - **项内有"裸明细字段"**（明细字段不在任何聚合函数内）→ 整项**按对齐行逐行算、再求和**；项内若有聚合函数 `FN(...)`，其值先在对齐行上算成标量、在每行当常量代入；`[别名.列(总计)]`/`[别名(总计)]` 取标量代入；缺明细→0。
   - **明细全在聚合函数内 / 整项纯标量（总计/常数）** → 该项**只算一次**，不外层求和。
5. 各项按符号累加 → 单值。缺值→0、除数 0/缺→1（由 `SafeArithmetic` 保证）。

### 求值规则示例（写入文档，作为口径基准）

| 表达式 | 结果语义 |
|---|---|
| `[投料.金额] * [加工.工时]` | `Σ(每行 金额×工时)`（裸明细→整项逐行求和） |
| `AVG([投料.工时])` | 工时均值（明细全在 AVG 内→算一次） |
| `AVG([投料.工时]) * [投料.数量]` | `Σ(每行 均值×数量)` = 均值 × Σ数量（仍有裸明细"数量"） |
| `MAX([投料.金额]) + [回料(总计)]` | 最大金额 + 回料总计（全聚合/标量→算一次） |
| `[投料.金额] * [加工.工时] + [回料(总计)]` | `Σ(金额×工时) + 回料总计`（项1逐行求和 + 项2标量一次） |

> 复用 v1 已实现的聚合归约机制：`FN(inner)` 括号配平 + 逐行求值内层子表达式 + reduce（SUM/AVG/MIN/MAX/COUNT）。新增的是"行键对齐"与"加减项分段 + 裸明细默认求和"。
> 令牌 `[别名.小计]` 旧写法废弃，改 `[别名(总计)]`(页签总计) / `[别名.列(总计)]`(列总计)。

---

## 6. 前端 — `TabJoinFormulaDrawer`（v2，对照 v2 原型）

- 入口：`ExcelViewConfigTab.tsx` 列来源下拉新增「页签连表公式」；选它打开本抽屉（右侧 `Drawer`，宽 1100/1200，符合 UI 规范）。
- 布局（自上而下，照 `excel-tab-join-formula-builder-v2.html`）：
  1. **试算条**：样本卡片选择 + 试算按钮 + 结果回显（单值 + 拆项明细）。
  2. **单表达式框** + **运算符工具条** + **函数工具条**（SUM/AVG/MIN/MAX/COUNT）。
  3. **求值规则提示**（加减项分段 + 示例）。
  4. **锁定状态条** + 清空表达式。
  5. **页签字段矩阵**：每行一个页签（左侧页签名 + 行键徽标），右侧三组 chip：明细 / 小计列总计 / 页签总计。
- 组件拆分：`FinalExpressionPanel`(表达式框+工具条) / `TabFieldMatrix`(矩阵+置灰) / `SampleCardPicker` / 顶层 Drawer 编排状态。
- 关键交互：
  - 点字段 chip → 按三类插入对应令牌到光标处；点函数 → 插 `FN()` 光标落括号内。
  - **置灰**：解析表达式中"明细令牌"得当前锁定行键类（取第一个明细的 `rowKeyFields` 签名）；其它行键类页签的明细 chip 置灰（hover tooltip 说明），其总计 chip 仍可点；无明细令牌→全解锁。`onChange`/插入后实时重算。
  - 页签/字段清单 + 各页签 `rowKeyFields` 读**模板页签定义**接口。

---

## 7. 试算接口

- `POST /api/cpq/templates/{templateId}/excel-view-config/dry-run-tab-formula`
- body：`{ lineItemId, column: {expression, tabs}, cardValuesJson?(可选) }`
- resp：`{ value: <单值>, terms: [{kind:'sum'|'scalar', value}], errors: [] }`（terms 供 UI 显示拆项，非必须）
- 复用 `ExcelViewService` / `TabJoinPlanEvaluator` 同一求值路径；样本卡片 = 已有报价/核价单的一个 `lineItem`。

---

## 8. 错误处理

- 缺值→0；除数 0/缺→1（`SafeArithmetic`）。
- 配置期 `validate`：表达式非空；引用的页签 `alias` 必须在 `tabs` 声明；裸明细字段引用的页签必须同一行键类（前端置灰已保证，后端兜底校验/告警）。
- 运行态：求值异常 → 返回 0 + `LOG.warn` 记 trace，不抛断渲染。

---

## 9. 测试

- **后端单测** `TabJoinPlanEvaluator*Test`：
  - 行键对齐（同类全外连/并集补0/缺行=0/不同类不对齐）。
  - 加减项拆分（顶层 +/- 尊重括号）。
  - 裸明细自动求和 / 聚合函数内不再外层求和 / 标量项算一次 / 混合（§5 示例表逐条）。
  - 缺值=0、除数=1（复用 SafeArithmetic 测试）。
  - `evaluateColumn` 整列：expression + tabs(含 rowKeyFields) → 单值（用 `CardDataProvider.fromEffectiveRows` 造数）。
- **集成测试** `ExcelViewTabJoinFormulaIT`：样本卡片端到端 → 单值。
- **E2E**：属 Excel 视图渲染链路改动，按 `docs/E2E测试方法.md` 跑。

---

## 10. 影响面 / 复用

- 改动文件：后端 `TabJoinPlanEvaluator.java`(重写求值)、`ExcelViewService.java`(列求值分支+试算)、`TemplateExcelViewResource.java`(试算端点+tab-defs/sample-cards 只读端点)；前端 `ExcelViewConfigTab.tsx`、新 `TabJoinFormulaDrawer` 及子组件、service。
- **复用既有**：`SafeArithmetic`(T1 不动)、聚合归约机制(reduceAgg/matchParen/findAggCall/substituteRowTokens/numLit/toBig)、`CardDataProvider`/`CardEffectiveRows`、`ExcelViewService.buildRowData` switch 框架。
- **废弃既有**（v1 残留）：`buildWideRows`/`Join`(INNER 笛卡尔) → 换行键对齐；`applyWhere`/`Cond` → 删除；`evaluateColumn` 的 groups/main_tab/joins/where/final/`组N` → 重写。

---

## 11. 一期不做（YAGNI）

- 跨行键类的明细直接混算（只能经总计/聚合）。
- 计算组 / 显式 JOIN 关联键 / WHERE（v1 概念，已删）。
- `SUM/AVG/MIN/MAX/COUNT` 以外的函数。
- 跨产品卡片 / 整单作用域（仅单卡片）。
- 页签数据与视图列变量（汇率等）混引。
- `[别名(总计)]` 的口径细分（一期：页签总计取 `CardDataProvider.subtotalOf`；小计列总计取 `subtotalOfColumn`）。
