# 页签连表公式 — 嵌套聚合 `SUM(SUM()+SUM())` 统一求值 设计方案

- 日期：2026-06-16
- 状态：设计待评审（brainstorming 产出，未进入实现计划）
- 作者：Claude（与用户协同 brainstorming）
- 架构走向：**方案甲 · 统一求值器**（Excel 单值路径复用模型 A 的递归 token 求值器，退役扁平的 `TabJoinPlanEvaluator`）

## 0. 背景与前序基线

页签连表公式（cross_tab / TabJoin）当前已具备相当多的"分组 + 条件 + 多源"能力，本方案在其上叠加**任意层嵌套聚合**。相关前序设计（背景，不重复其内容）：

- `docs/superpowers/specs/2026-06-05-跨页签引用公式-design.md` / `2026-06-05-跨页签目标公式-design.md`
- `docs/superpowers/specs/2026-06-11-tabjoin-rowkey-host-grouping-design.md`（KSUM 宿主行键分组 / `projectToHostKey`）
- `docs/superpowers/specs/2026-06-12-multi-source-chain-sum-design.md`（`sources[]` 多源链式）
- `docs/superpowers/specs/2026-06-09-cross_tab_ref-过滤条件-design.md` / `2026-06-15-sumif-conditional-aggregate-functions-design.md`（SUMIF 族 `predicate`）
- `docs/三大核心模块基线.md`（报价单渲染基线，`FormulaCalculator` 属基线锁定模块）
- `docs/反模式.md` AP-44 / AP-50 / AP-51（字段类型联动、渲染 single-source、行数纪律）

### 0.1 现状代码事实（实测，决定可行性）

| 能力 | 模型 A（产品卡片，按宿主行键出向量） | Excel（单值） |
|---|---|---|
| 求值器 | `FormulaCalculator.evalCrossTab` + `targetRowValue` + `evaluateExpression`（**递归**） | `TabJoinPlanEvaluator.replaceAggregatesS`（**单层非递归**） |
| 聚合函数 | `SUM/AVG/MIN/MAX/COUNT` | `SUM/AVG/MIN/MAX/COUNT` |
| 条件聚合 SUMIF 族 | 支持（`predicate`，token 内联） | `AGG_CALL` 正则**不含** SUMIF |
| 宿主行键分组 / 投影 | 支持（`projectToHostKey` = KSUM 降维） | 显式 `throw` 拒绝 |
| 多源链式 | 支持（`sources[]`） | 显式 `throw` 拒绝 |
| 嵌套聚合 `SUM(SUM())` | 经 `targetExpr` 内嵌子 token + 递归，**已基本可表达** | **不支持**（内层 SUM 丢给 JEXL → 归 0） |
| "无嵌套零变化"退化纪律 | 有（N=1 退化路径） | — |

**关键结论**：模型 A 的递归 token 求值器是嵌套聚合的现成底座；Excel 扁平求值器是唯一短板。方案甲 = 让 Excel 复用模型 A 引擎，一举消除双引擎分叉。

### 0.2 序列化 / 作者层现状

- `cpq-frontend/src/pages/component/formulaSerialize.ts`：已有 `K*` 内层函数白名单（`KSUM/KAVG/KMAX/KMIN/KCOUNT`，只能写在外层 `SUM/AVG/MAX/MIN/COUNT` 内）、"顶层裸 K* 拒绝"规则、内联 `SUMIF(...)` 解析（`sumif_call`）。
- `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`：表达式富文本框 + 函数工具条 + **SUMIF 构造器（当前产物拼到公式末尾的 side-token，不能嵌进函数参数）**。

## 1. 目标与非目标

### 1.1 目标
1. 页签连表公式支持**任意聚合函数、任意层数**的嵌套聚合，典型形态：
   ```
   SUM( SUM([来料.来料材料费用] + [来料.外购件材料费用])
        * SUMIF([其他费用.要素]='材料管理费', [其他费用.比例]) )
   ```
2. 有宿主页签（产品卡片）→ 按宿主行键分组聚合，输出**每行键一个值的向量**；无宿主（Excel 公式）→ 输出**单值**。
3. 作者体验（方案 C）：文本表达式为主体，SUMIF 等条件聚合用构造器生成后**内联插入到光标处**，与手敲文本产出同一套可嵌套语法。
4. **不破坏现有公式计算能力**（硬约束）：存量公式逐位等价。

### 1.2 非目标
- 不引入"内层细键 + 外层粗键"的**两级不同分组键**（用户已确认 A：单层，唯一键 = 宿主行键）。
- 不改 `field_type` 枚举（不触发 AP-44 新类型，但触发 cross_tab 求值/序列化协议回归）。
- 不迁移存量数据 / 不改库表结构（除非求值器扩展确需 token schema 增字段，见 §5）。

## 2. 统一语义模型（规范核心 · 唯一权威定义）

1. **唯一分组键 = 宿主页签行键**（规则不变）。
2. **引用分两类**：
   - **行键对齐引用**（明细字段、`KSUM` 等内层聚合）：必须含宿主行键，按宿主行键 LEFT JOIN（全外连·缺补 0）对齐；分组语境内只见"当前行键组"的行。
   - **条件聚合 SUMIF 族**（`SUMIF/COUNTIF/AVGIF/MINIF/MAXIF`）：全局 `predicate` 过滤；**行键对不上 → 退化为全局单值，广播到每个分组**（= 现有行为，天然兼容）。
3. **递归聚合**：`SUM/AVG/MIN/MAX/COUNT` 与 SUMIF 族可**任意层嵌套**；内层归约成标量，外层继续聚合 / 算术。
4. **最外层归约（投影）按上下文分流**：
   - **有宿主（产品卡片 / 模型 A）** → 按宿主行键输出**向量**（`projectToHostKey` 投影）。
   - **无宿主（Excel 公式）** → 全局归约成**单值**。
5. **空集 / 类型语义**沿用现状：`SUM/COUNT/KSUM/KCOUNT` 空集 → 0；`AVG/MAX/MIN`（K 系）空集 → null → 整外层塌 0（I-1/I-2 决策，不改）。
6. **硬约束（退化纪律）**：无嵌套 / 扁平 / N=1 的既有公式，走"零变化"路径，结果与现状逐位一致。

### 2.1 样例公式语义走查

`SUM( SUM([来料.来料材料费用]+[来料.外购件材料费用]) * SUMIF([其他费用.要素]='材料管理费',[其他费用.比例]) )`

- 内层 `SUM([来料.…]+[来料.…])`：`来料` = 宿主，含宿主行键 → 映射为 **KSUM**，对"当前行键组"内的 `(来料材料费用 + 外购件材料费用)` 求和 → 标量 `S_g`。
- `SUMIF([其他费用.要素]='材料管理费', [其他费用.比例])`：`其他费用` 为全局费率表（行键对不上）→ **全局单值** `R`，广播到每组。
- 内层表达式值 = `S_g * R`。
- 外层 `SUM(...)`：
  - 产品卡片 → 按宿主行键输出向量 `{ g → S_g * R }`，拼接回宿主各行。
  - Excel → 全局归约 → `Σ_g(S_g) * R` 单值。

## 3. 架构设计（方案甲：单一 AST + 单一递归求值器）

### 3.1 唯一中间表示 = token AST
- 沿用 `FormulaToken` / `cross_tab_ref`，明确其为**可任意嵌套树**：`cross_tab_ref.targetExpr` 内可挂子聚合 token（已存在），递归无层数上限（由代码栈与校验上限兜底，见 §6）。

### 3.2 唯一求值器 = `FormulaCalculator` 递归 cross_tab 通路
- `evalCrossTab` → `targetRowValue` → `evaluateExpression` 已构成递归骨架。需做：
  1. **验证任意深度对全部聚合函数成立**：补齐 / 验证 `AVG/MIN/MAX/COUNT` 作为内层与外层、3 层以上嵌套的空集与类型分支。
  2. **Excel 全局归约模式**：把 `TabJoinPlanEvaluator.evaluateColumn` 中"拒绝 `projectToHostKey` / 多 source"的两处 `throw` 路径，替换为"经统一求值器、以 `projectToHostKey=false` 顶层全局归约"求单值。

### 3.3 Excel 路径改造
- Excel 列求值：`expression`（字符串）→ `expressionToTokens` → token AST → **统一递归求值器（无宿主全局归约模式）** → 单值。
- `TabJoinPlanEvaluator` 标记 `@Deprecated`，保留为**过渡期黄金对照**（§7 差异回归基准），全绿后退役删除。
- 入口收敛点：`evaluateColumn` 的调用方（Excel 视图列求值链路）改指向统一求值器；`dry-run`（Excel 单值试算）与 `dry-run-token`（卡片逐行试算）最终都落到同一引擎。

### 3.4 模块边界
- **求值核心**：`FormulaCalculator`（基线锁定，改动从严，递归 + 退化纪律）。
- **序列化**：`formulaSerialize.ts`（expression ↔ token 双向，含嵌套与内联 SUMIF）。
- **作者 UI**：`TabJoinFormulaDrawer.tsx` + `FormulaRichInput` + SUMIF 构造器（产物内联插入）。
- **试算端点**：`ComponentTabJoinResource`（`dry-run` / `dry-run-token`）。

## 4. 作者体验 + 序列化（方案 C）

### 4.1 表达式框为主体
- 用户直接敲嵌套 `SUM(...)`；函数工具条插入 `FN()` 行为不变。

### 4.2 SUMIF 构造器：从"末尾 side-token"改为"内联插入光标处"
- 现状：SUMIF 构造器 `handleInsertSumifToken` 把 token 追加到 `sumifTokens`（保存时拼公式末尾）。
- 改造：构造器产出**内联文本** `SUMIF([其他费用.要素]='材料管理费',[其他费用.比例])`，调用 `insertAtCursor` 插入当前光标位置（对所有组件类型统一，含 NORMAL/SUBTOTAL）。
- 收益：SUMIF 可嵌进任意函数参数（满足 `SUM(... * SUMIF(...))`）。
- 兼容：重开旧公式时，原 side-token（带 `predicate` 的 `cross_tab_ref`）仍能反序列化为内联文本展示（`tokensToDrawerExpression` 渲染 SUMIF 文本）。

### 4.3 序列化升级
- `expressionToTokens`：支持**任意深度嵌套** + 内联 SUMIF → 嵌套 token 树；外层 `SUM/AVG/MAX/MIN/COUNT` 包裹行键对齐引用时映射为 `K*`（沿用现有 OUTER/INNER 规则并放开层数）。
- `tokensToDrawerExpression`：嵌套 token 树 → 文本（含内联 SUMIF 还原）。
- `checkMappable`：放开"可映射"判定以容纳嵌套（仍拦截真正无法表达的形态）。
- `FormulaRichInput` / `formulaBracketCheck`：括号配平与高亮适配多层嵌套。

### 4.4 试算
- `dry-run-token`（逐行向量）与 Excel `dry-run`（单值）均走统一求值器，"配置即所见"。

## 5. 数据 / 持久化影响
- **优先不改 token schema**：嵌套通过现有 `targetExpr` 子树表达。
- 若 §3.2 验证发现需新增 token 字段（如显式嵌套深度标记），须：(a) 向后兼容缺省值；(b) 旧 token 不含该字段时走退化路径；(c) 不做存量数据迁移。
- 存量 Excel 列 `expression`（字符串）原样读取，由统一求值器解析。

## 6. 边界与防御
- **递归深度上限**：设保护性上限（如 8 层）防栈溢出 / 配置笔误，超限在序列化与求值两侧给明确错误（非静默归 0）。
- **顶层裸 K* 拒绝**：保留现有规则。
- **SUMIF 行键对不上**：按 §2.2 退化为全局单值（非报错）。
- **类型 / 多命中错误**：沿用 `ERR` 哨兵 → 整外层塌 0 + 诊断，不静默。
- **中文标识符**：`[页签.字段]` 内中文经 token 化处理，不直接进 JEXL（沿用现有 token 替换为字面量再求值的纪律）。

## 7. 测试与验收（基线锁定模块，从严）

### 7.1 差异回归（兼容达标的硬指标）
- 选取一批既有 **Excel + 卡片**公式，新引擎 vs 旧引擎（`TabJoinPlanEvaluator` 作 Excel 黄金对照）结果**逐位比对，0 差异**才算兼容达标。

### 7.2 新能力单测（`TabJoinPlanEvaluator` 同级纯逻辑单测 + `FormulaCalculator` 单测）
- `SUM(SUM())` 两层；3 层嵌套；
- `AVG/MIN/MAX/COUNT` 各自作内层与外层；
- SUMIF 全局广播；行键对不上退化为单值；
- 有宿主出向量 vs Excel 出单值；
- 空集分流（SUM→0；AVG/MAX/MIN→塌 0）。

### 7.3 强制 E2E（协议级改动）
- `cpq-frontend/e2e/quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）。
- 必须：所有 test `passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`；附 qf-19 + qf-21~28 截图。

### 7.4 自检
- 前端：`npx tsc --noEmit` 0 错误；改动 `.tsx`/`.ts` 逐个 Vite 200。
- 后端：`FormulaCalculator` 改动后强制 Quarkus 重启 + `/q/health` 200；纯逻辑单测全绿（在 worktree 的 `cpq-backend` 内亲跑 `mvnw`）。

### 7.5 文档
- 同步 `docs/PRD-v3.md`（公式能力章节 + 演进史）、`docs/RECORD.md`、`docs/配置方法论-合并版.md`（§11 / 附录）。
- 若 cross_tab 求值出现新协议点，更新 `docs/反模式.md`。

## 8. 实施阶段（供后续 writing-plans 细化）
1. **P0 语义与回归基线**：固化 §2 语义；搭差异回归框架（旧引擎黄金对照）。
2. **P1 求值器统一**：`FormulaCalculator` 验证 / 补全任意深度递归；Excel 路径改走统一引擎，退役 `TabJoinPlanEvaluator`（先并存）。
3. **P2 序列化嵌套**：`expressionToTokens` / `tokensToDrawerExpression` / `checkMappable` 支持任意深度 + 内联 SUMIF。
4. **P3 作者 UI**：SUMIF 构造器改内联插入；富文本 / 括号校验适配嵌套。
5. **P4 回归与验收**：差异回归 0 差异 + 新能力单测 + 双 E2E + 文档。

## 9. 风险
- **基线模块改动**（`FormulaCalculator`）：以"差异回归 0 差异 + 退化纪律"双保险控风险。
- **双引擎过渡期分叉**：以黄金对照测试钉死，退役前不删旧引擎。
- **序列化 round-trip 不对称**：嵌套 + 内联 SUMIF 的 expression↔token 必须可逆，加 round-trip 单测。

## 10. 待开放问题（实现期确认）
- 递归深度上限取值（暂定 8）。
- `expressionToTokens` 对"外层聚合包裹宿主行键引用 → 自动 `K*` 映射"在多层嵌套下的具体规则细化。
- Excel 路径退役 `TabJoinPlanEvaluator` 的入口收敛点是否还有其它调用方（实现期 `codegraph_callers` 复核）。
