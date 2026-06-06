# Excel 卡片公式 WHERE 动态查找键（第一期）设计方案

- 日期：2026-06-06
- 状态：已评审通过（待落实施计划）
- 关联：Excel 卡片公式系统（`CardFormulaEvaluator` / `CardRef` / `CardFormulaDrawer`），独立于组件列公式的 `cross_tab_ref`

## 1. 背景与需求

Excel 视图列公式（`CARD_FORMULA` 类型）已支持跨页签取值/聚合：
- **「字段·按条件取行」(ROW_WHERE)** = VLOOKUP 式：取某页签中满足条件的行的某字段。
- **聚合 SUM_OVER([页签] WHERE 条件, 表达式)** = SUMIF 族。

但条件构建器的右侧"值"**只能是固定字面量**（`buildCondJexl`：`字段 == '字面量'`）。因此只能"按固定值取行"，**做不了动态 VLOOKUP**（查找键随本产品行变化，如 `A.关联号 == 本行料号`）。

本需求：让 WHERE 条件右侧"值"能引用**本产品行可见的键**，实现动态 VLOOKUP / 动态 SUMIF。

### 范围裁剪（已与用户确认）

- 查找键来源（用户选 D=全都要，按风险分两期）：
  - **第一期（本 spec）**：右侧 = `字面量 | 本行产品字段 | 本行其它卡片公式列`。覆盖"按本产品键查找"绝大多数场景。
  - **第二期（另立项）**：右侧 = **嵌套另一页签 card-ref**（`A.关联号 == [投料.子件号(条件)]`），需递归 ref 构建器 UI，是最重、风险最高的一块。
- 右侧表达力（用户选 A=单值引用）：右侧是**单个值引用**，不是完整表达式（不支持 RHS 写四则/函数/SUM_OVER）。
- 表示与求值（用户选甲）：结构化条件行 + 服务端在产品行上下文解析 RHS，复用现有卡片公式求值器，不造新引擎。

## 2. 求值上下文（事实基线）

- Excel 每行 = 一个产品（lineItem）。`ExcelViewService` 对每个 lineItem 提取 `partNo` + `componentRowData`（各组件首行字段拍平的 flat map），调 `cardFormulaEvaluator.evaluateColumns(cardCols, provider/componentDataList, customerId, partNo, null)`。
- `evaluateColumns` 内按列拓扑序求值，`cached` 持有**已算的 CARD_FORMULA 列**值；`provider` 提供各页签行（`rowsOf` / `subtotalOf`）。
- 现状 ROW_WHERE：`pickFieldValue` 用 `templateFormulaService.firstMatchIndex(aliasedARows, ref.cond)` 扫 A 行，`cond` 是只含 A 字段别名 + 字面量的 JEXL。

## 3. 数据模型（结构化条件）

`CardRef`（`com.cpq.quotation.service.card.CardRef` + 前端对应结构）增加结构化条件：

```jsonc
condRows: [
  { "left": "<A页签字段名>", "op": "eq|ne|gt|gte|lt|lte|in",
    "logic": "and|or",                  // 与下一条件的连接（末条不用）
    "rhs": { "type": "literal|product|column", "value": "<字面量/产品字段名/列号>" } }
]
```

- 后端**优先用 `condRows`**；旧 ref 仅有 `cond`(JEXL 字符串) 时走兼容老路径。
- `cols`（别名→A 中文字段）保留，供左侧别名映射。

## 4. 后端求值

`evaluateColumns` 新增入参 `Map<String,Object> productRow`（= `componentRowData`），`ExcelViewService` 调用处传入。

求 ROW_WHERE / 聚合 WHERE 时（每产品行一次）：
1. 逐 `condRow` 解析 RHS 为标量：
   - `literal` → 原值（数字/字符串字面量）
   - `product` → `productRow.get(value)`；特殊 `__partNo__` → `partNo`
   - `column` → `cached.get(value)`（仅已算 CARD_FORMULA 列）
2. 拼成 JEXL：`alias(left) <opJexl> <标量字面量>`，按 `logic` 用 `&&`/`||` 连接。
3. 复用 `firstMatchIndex(aliasedARows, jexl)`（ROW_WHERE）/ 聚合 WHERE 扫描，逻辑不变。

**关键限制（不变量）**：RHS 仅能引用"求值时已就绪"的来源——**产品字段 (`productRow`/`partNo`) + 已算 CARD_FORMULA 列 (`cached`)**。**VARIABLE / 普通 FORMULA 列不可作 RHS**（它们在 CARD_FORMULA 批量之后才算，引用会取空）。产品键本就在 `componentRowData` 内，无需引用 VARIABLE 列。

RHS 解析为空 / 字段不存在 → 该条件按"取不到键"处理（不匹配 → ROW_WHERE 返回 DASH/0 的既有空值规则；不抛异常）。

## 5. 配置 UI（CardFormulaDrawer 条件构建器）

每个条件行的"值"列加**来源选择器**（小 Select / 分段控件）：
- `字面量`（默认）：原文本输入（行为不变）。
- `产品字段`：下拉选本行产品字段。列表来源 = 报价单产品属性 + `料号(__partNo__)`（+ 可得的 `componentRowData` 字段）；若无现成列表 → 结构化"字段名"输入 + 「试算」兜底校验。
- `本行卡片公式列`：下拉选本模板内**其它 CARD_FORMULA 列**（`[列号]`）。

确认后生成 `condRows`（含 `rhs.type/value`）写入该列 ref。ROW_WHERE 与 聚合 两种引用类型共用此条件构建器（两者都有 WHERE）。

## 6. 校验 / 试算

- 保存校验 `condRows`：`left` 是该源页签字段；`rhs.type='column'` 时列存在且为 CARD_FORMULA 列；`rhs.type='product'` 时字段名非空；`op='in'` 时值非空。
- 复用现有「试算」按钮在样例报价单上验证结果。

## 7. 向后兼容

- 旧 ROW_WHERE / 聚合 refs（仅 `cond` 字符串、字面量条件）照常工作，无需数据迁移。
- 新建/编辑产生 `condRows`，后端优先用之；二者并存。
- 前端编辑旧 ref 时：若只有 `cond` 无 `condRows`，可解析回填或保持只读兼容（实现阶段取简单稳妥者，至少不破坏旧 ref 求值）。

## 8. 测试

- **后端**（`CardFormulaEvaluator` + `TemplateFormulaService`）：
  - ROW_WHERE 动态 RHS：`product`（按 productRow 字段）/ `column`（按已算列）/ `literal`（回归）。
  - 聚合 WHERE 动态 RHS（SUM_OVER WHERE product 字段）。
  - 多条件 AND/OR + 混合 RHS 类型；RHS 取空 → 空值规则；旧 `cond` 兼容。
- **前端**：`CardFormulaDrawer` tsc 0 + Vite 200；E2E 配置路径（值选"产品字段"→ 生成 condRows → 试算/断言）。

## 9. 第二期（明确不做）

- RHS = 嵌套另一页签 card-ref（`A.关联号 == [投料.子件号(条件)]`）。
- 需在条件值处再嵌一个完整 ref 构建器（递归 UI）+ 后端递归 ref 解析。

## 10. 已知风险 / 注意点

- **求值顺序**：RHS 不可引用 VARIABLE/FORMULA 列（§4 不变量），UI 的"本行列"下拉只列 CARD_FORMULA 列，避免用户配出取空的引用。
- **中文标识符**：condRows 的 `left`/`product` 字段是中文，后端解析走别名/Map 取值（沿用 `cols` 别名 + `productRow` Map 直取），不直接当 JEXL 标识符（参考既有 [[cpq-chinese-identifiers-need-ascii-alias]] 教训）。
- **双轨**：本能力属 Excel 卡片公式系统，与组件列 `cross_tab_ref` 仍是两套；不在本期合并。
