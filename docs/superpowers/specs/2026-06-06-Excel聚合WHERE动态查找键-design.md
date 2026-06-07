# Excel 卡片公式 聚合 WHERE 动态查找键（第二期）设计方案

- 日期：2026-06-06
- 状态：已评审通过（待落实施计划）
- 关联：第一期 ROW_WHERE 动态查找键（`docs/superpowers/specs/2026-06-06-Excel条件动态查找键-design.md` + 计划 `docs/superpowers/plans/2026-06-06-Excel条件动态查找键-rowwhere实施.md`，已实现 commit `b4ac322`→`998999b`）。本期是其续作，复用同一 `condRows` 数据模型与 `buildDynamicCond`/`resolveRhs`/拓扑 `condRowColumnDeps`。

## 1. 背景与需求

第一期让 **ROW_WHERE**（`[页签.字段(条件)]`，VLOOKUP 式取行）的条件右侧"值"能引用本产品行可见的键（字面量 / 本行产品字段 / 本行其它 CARD_FORMULA 列），实现动态 VLOOKUP。

本期把同样的动态查找键能力扩展到**聚合 `SUM_OVER([页签] WHERE 条件, 表达式)`**（SUMIF 族），实现**动态 SUMIF**——聚合的 WHERE 过滤条件右侧可随本产品行变化（如 `SUM_OVER([投料] WHERE 关联号==本行料号, 单价*数量)`）。

### 与 ROW_WHERE 的关键差异

- ROW_WHERE 的条件存在 `ref.cond`（独立 JEXL 字符串）+ `ref.cols`，求值时 `pickFieldValue` 直接用。
- **聚合 WHERE 的谓词内嵌在公式文本里** —— `SUM_OVER([页签] WHERE c0=='镀铜', c0*c1)`，由 `TemplateFormulaService.parseOverFuncArgs` 从公式串切出 `predicate`，`executeOverFunction` 对页签每行 `evalRowExpression(predicate, row)` 过滤。这是本期机制设计的核心约束。

### 范围裁剪（已与用户确认）

- **本期只做聚合 WHERE 的动态 RHS**。
- RHS 类型复用第一期三种：`字面量 | 本行产品字段(组件字段 + __partNo__) | 本行其它 CARD_FORMULA 列`，**单值引用**，不支持 RHS 写四则/函数。
- **动态 RHS 只作用于 WHERE 过滤条件**；被聚合的行内表达式 `aggExpr`（如 `单价*数量`）保持原样（对页签每行求值，与"本产品行的键"不是一个维度，本期不做）。
- **同一列公式里对同一页签写多个条件不同的聚合**：本期必须支持（用户明确场景）。
- 聚合 ref **暂不做"点标签回填编辑"**（第一期回填只覆盖 ROW_WHERE；聚合既有 UX 一直是删了重配，不回归）。

## 2. 求值上下文（事实基线）

- 现状聚合链路：`evaluateColumns`（每产品行一次）→ `resolveCardScalars` 把聚合源 ref 登记成 `CardAggregateSource.Binding(tab, cols)`（key = 公式源 token，现状 = 页签名）→ `CardFormulaEvaluator.set(Ctx(provider, aggBindings))` → `evaluateExpressionPublic` → `resolveAggregates` → `executeOverFunction`：`parseOverFuncArgs` 从公式文本切 `source/predicate/expression`；`CardAggregateSource.rowsFor(source)` 用 binding 把页签行按 `cols` 别名重映射；`evalRowExpression(predicate, 别名行)` 逐行过滤后聚合。
- 第一期已给 `resolveCardScalars` 加了 `productRow`/`cached`/`partNo` 入参（ROW_WHERE 动态 RHS 用），聚合 binding 的登记也在 `resolveCardScalars` 内 —— **本期算动态谓词所需的上下文已就绪**。
- 既有限制（潜在缺陷）：binding 按页签名 collision，同页签多个聚合若用不同字段 → cols 别名互覆盖 → 算错；本期唯一 keying 顺带修复。

## 3. 数据模型（复用 + 唯一 keying）

- 聚合 ref 复用第一期 `CardRef.condRows`（`[{left, op, logic, rhs:{type:literal|product|column, value}}]`），与 ROW_WHERE 同构。聚合 ref 现状 `{tab, cols}` → 新增可选 `condRows`。`CardRef.fromMap` 已解析 `condRows`（第一期完成），聚合 ref 复用同一解析，无需改 `CardRef`。
- **唯一 keying**：所有**新建**聚合 ref 的 refKey 改为 `页签名#N`（N = 该页签在本列已有聚合 refKey 中的下一个序号，从 1 起），公式源 token 同步为 `[页签名#N]`。
- 旧聚合 ref（`[页签名]` 无后缀）：binding 仍按 token 原样查找（token=页签名），照常工作。**不迁移、不破坏**。

## 4. 后端求值（方案①·Binding 注入动态谓词）

### 4.1 `CardAggregateSource`（`com.cpq.template.service`）
- `Binding` 增字段 `public final String dynamicPredicate;`（可空）+ 对应构造重载（保留旧 2 参构造，新增 3 参；旧 2 参委托 3 参传 null）。
- 新增静态方法 `public static String predicateFor(String sourceToken)`：返回该 token 对应 binding 的 `dynamicPredicate`（无 binding 或无动态谓词 → null）。

### 4.2 `CardFormulaEvaluator.resolveCardScalars`
- 登记聚合 binding 时：若该聚合 ref `hasCondRows()`，**复用 `buildDynamicCond(ref, productRow, cached, partNo)`**（第一期方法，私有同类可直接调）算出本产品行的动态谓词字符串，存进 `Binding.dynamicPredicate`；否则 dynamicPredicate=null（走旧路径）。
- 别名一致性：`buildDynamicCond` 左值用 `c0/c1` 别名（从 `ref.cols` 反查 字段→别名），与 binding 的 `aliasToField`(=ref.cols) 同源，与 `rowsFor` 行别名重映射对齐。

### 4.3 `TemplateFormulaService.executeOverFunction`
- 取谓词处改为：`String dyn = com.cpq.template.service.CardAggregateSource.predicateFor(parsed.source); String predicate = (dyn != null) ? dyn : parsed.predicate;`，后续 `if (predicate != null && !predicate.isBlank()) evalRowExpression(predicate, row)` 不变。
- 即：binding 有动态谓词 → 用它；否则用公式文本切出的 `parsed.predicate`（静态/旧 ref）。

### 4.4 关键不变量（沿用第一期）
- RHS 仅能引用 productRow / partNo + 已算 CARD_FORMULA 列（cached）。VARIABLE / 普通 FORMULA 列不可作 RHS（求值时未就绪）。`resolveRhs` 三分支穷举保证无越权读列。
- RHS 解析为空 → 该条件 `1==2` 永假 → 不匹配（聚合空集 → 0，由 `aggregate()` 既有规则处理）。不抛异常。
- 中文标识符：condRows 的 `left`/`product` 字段是中文，走 `cols` 别名 + `productRow` Map 直取，不当 JEXL 标识符（[[cpq-chinese-identifiers-need-ascii-alias]]）。

## 5. 列依赖拓扑（零新增）

第一期 `CardFormulaEvaluator.condRowColumnDeps` 已扫**所有 refs** 的 condRows 收集 `rhs.type=column` 依赖喂拓扑 + 环检测。聚合 ref 一旦带 condRows，其 WHERE 里 `rhs=column` 的列依赖**自动纳入**，无需改动拓扑代码。

## 6. 前端（CardFormulaDrawer 聚合分支）

- 条件构建器的 RHS 来源选择器（字面量/产品字段/本行列）第一期 Task6 已对 row_where + aggregate **都渲染**（`needCondBuilder = row_where || aggregate`），UI 基本就位。本期只改 `buildInsertResult` 的 `aggregate` 分支：
  - `hasDynamic`（任一条件 `rhsType !== 'literal'`）：`condRows = buildCondRows(conds)`；公式占位**省略 WHERE** → `SUM_OVER([页签#N], aliasExpr)`；ref = `{tab, cols, condRows}`。
  - 全字面量：保持现状（`WHERE condJexl` 烤进公式、不生成 condRows）；ref = `{tab, cols}`。
  - 两种都用唯一 refKey `页签名#N`：`handleInsertRef` 按现有 `refs` 里匹配 `^页签名(#\d+)?$` 的项算下一个序号，传入 `buildInsertResult`（或在 `buildInsertResult` 增一个 `existingAggKeys` 入参）。
- 动态聚合的条件可读性交给 Task6 的"动态条件预览"结构化中文 + ref 标签。`aggExpr` 不变。
- 聚合 ref 不做点标签回填编辑（YAGNI，留后续）。

## 7. 公式校验（一处必改）

`cpq-frontend/src/pages/template/cardFormula.ts` 的 `ALLOWED` 正则当前为 `/^[\sA-Za-z0-9_+\-*/().,%<>=!&|'一-龥\[\]]*$/`，**不含 `#`**，`[页签#N]` 会被 `validateCardFormula` 判"公式含非法字符"。需把 `#` 加进 `ALLOWED` 字符集。后端 `parseOverFuncArgs` 把 `[...]` 内容整体当 source token（Map key，非 JEXL 标识符），`#` 不参与解析，安全。

## 8. 向后兼容

- 旧聚合 ref（`[页签]`、仅文本 WHERE、无 condRows）：binding 按 token 查找照常、`executeOverFunction` 走 `parsed.predicate`（`predicateFor` 返 null），**完全不变**，无数据迁移。
- 静态新聚合：行为等价于旧（WHERE 仍烤进公式、走 parsed.predicate），只是 refKey/token 带 `#N`，且各聚合各自 binding（修复同页签多静态聚合的 cols collision 潜在缺陷）。

## 9. 测试

- **后端**（`TemplateFormulaService` + `CardFormulaEvaluator` + `CardAggregateSource`）：
  - SUM_OVER 动态 WHERE：`product`（按 productRow 字段 / `__partNo__`）、`column`（按已算 CARD_FORMULA 列）、多条件 AND/OR、RHS 取空 → 空集 → 0。
  - **同一列公式里同页签多个条件不同的动态聚合**互不串值（唯一 keying 核心回归）。
  - 同页签多个静态聚合不同字段不再 cols collision（潜在缺陷修复回归）。
  - 旧 `[页签]` token + 文本 WHERE 静态聚合兼容回归。
- **前端**（`cardFormula.ts` / `CardFormulaDrawer`）：
  - `buildInsertResult` 聚合分支：hasDynamic → 省 WHERE + 生成 condRows + 唯一 refKey；全字面量 → 保持 WHERE 烤入 + 无 condRows。
  - 唯一序号 `#N` 生成逻辑（同页签递增、跨页签独立）。
  - `validateCardFormula` 接受含 `#` 的 `[页签#N]` 公式（不再报非法字符）。
  - tsc 0 + Vite 200。

## 10. 第三期（明确不做）

- ROW_WHERE / 聚合 RHS = 嵌套另一页签 card-ref（`A.关联号 == [投料.子件号(条件)]`），递归 ref 构建器 UI + 后端递归解析（第一期 spec §9 已记，仍属最重一块）。
- aggExpr（被聚合的行内表达式）引用本产品行键。
- 聚合 ref 点标签回填编辑。

## 11. 已知风险 / 注意点

- **唯一 keying 与公式手编**：用户手改公式文本时若改动 `[页签#N]` 的 `#N` 部分需与 refs 的 key 对齐，否则 binding 查不到 → 该聚合走不到卡片源（rowsFor 返 null → resolveDriverPath 兜底 → 多半 0）。属现有"公式 token 须与 refs 对齐"的既有约束延续，非新风险。
- **同页签多聚合 binding 隔离**靠唯一 token；若用户公式里两段都写成同一个 `[页签#1]`（手误），两段共用一个 binding/谓词——这是 token 唯一性的人为破坏，UI 自动生成不会触发。
- **求值顺序**：聚合 WHERE 的 `rhs=column` 与 ROW_WHERE 同样受"RHS 不可引用 VARIABLE/FORMULA 列"约束；UI"本行列"下拉只列 CARD_FORMULA 列，拓扑 `condRowColumnDeps` 自动纳入聚合 ref 的 column 依赖。
- **双轨**：本能力属 Excel 卡片公式系统，与组件列 `cross_tab_ref` 仍是两套，不在本期合并。
