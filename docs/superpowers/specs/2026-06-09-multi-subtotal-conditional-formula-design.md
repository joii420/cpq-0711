# 多小计列 + 条件公式绑定 + 多列组合行键唯一 — 设计方案

- 立项日期：2026-06-09
- 状态：设计待评审（brainstorming 产出，未进入 writing-plans）
- 关联基线：`docs/三大核心模块基线.md`、`docs/反模式.md` AP-39 / AP-44 / AP-50 / AP-51 / AP-54
- 关联现有实现：
  - 后端 `cpq-backend/.../quotation/service/FormulaCalculator.java`（`computeRows` / `computeTabSubtotal` / `findSubtotalFieldName` / `collectFormulaFields` / `resolveFormulaExpression` / `computeRowKey`）
  - 前端 `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`computeAllFormulas` / `computeTabSubtotal` / `computeProductSubtotal`）
  - 前端 `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`handleSubtotalChange` / 行键勾选）
  - 前端 `cpq-frontend/src/pages/template/cardFormula.ts` + `CardFormulaDrawer.tsx`（现有扁平条件构建器，将被复用/升级）
  - 前端 `cpq-frontend/src/pages/quotation/useCardSnapshots.ts`（`computeRowKey`）

---

## 1. 目标（需求三点）

1. **多小计列**：一个组件支持多个小计列，每列各自计算总计。
2. **条件公式绑定**：小计/公式列的公式可按"某些列的内容"作为条件触发；每一行该单元格只运行**唯一**命中的公式。这定义了"公式与公式字段的绑定关系"。
3. **多列组合行键唯一**：组件可选多列作为组合行键，组合键不能重复。

三点均为对现有基础设施的**升级**，采用**原地演进 + 保留单一模式兼容**策略，不做破坏性数据迁移。

---

## 2. 需求澄清结论（brainstorming 问答固化）

| 维度 | 结论 |
|---|---|
| 条件判定模型 | 任意布尔表达式（if / elseif / else），但 UI 选择式、用户不手敲表达式（右侧字面值除外可键入） |
| 条件复杂度 | **完整 AND/OR 嵌套 + 分组括号**（`(A AND B) OR C`） |
| 条件右值来源 | 字面值**可键入**；另支持选本组件另一列做列列比较；左值仅本组件本行的列 |
| 条件可引用列范围 | 仅本组件本行的列（含其它公式列，按拓扑序求值） |
| 多小计列 × 条件公式 绑定层 | 全组件一套公式池，每条规则声明"条件 + 目标列"；等价表达为"每个公式字段挂一个有序规则列表" |
| 无条件命中 | 每个使用条件模式的字段**必须有末尾默认公式兜底**（else） |
| 多条命中 | 有序、第一条为真即停 |
| 行键 | 多列组合键 |
| 行键重复处理 | **保存时校验**，列出冲突拦截；driver 展开行也纳入校验 |
| 多小计列向上汇总 | 各小计列**独立向上**；跨组件**不归并**，每 `(组件, 小计列)` 各占一条汇总线 |
| 条件公式机制适用范围 | 任意公式列通用，小计列只是其中会被求和的那种 |
| 条件能否引用公式列 | 可以，引擎按依赖拓扑排序，保存时做环检测 |

### 2.1 待复核假设（user review 时确认/推翻）

- **A1（上层最终总价）**：在多小计维度明细之外，上层（产品卡 / 报价单）仍保留一个**最终汇总总价 = 所有小计线之和**，作为可对外报出的单一数字。若推翻，则上层只有多维明细、无单一总价。

---

## 3. 设计 A — 绑定数据结构（核心）

每个 `FORMULA` 字段的公式绑定升级为二选一模式，二者由字段上是否存在 `conditional_formula` 区分：

```jsonc
// 单一模式（现状，老组件零改动；解析口径 = 现有 resolveFormulaExpression）
{ "field_type": "FORMULA", "name": "加工费", "formula_name": "proc_fee" }

// 条件模式（新增）
{
  "field_type": "FORMULA",
  "name": "加工费",
  "is_subtotal": true,                         // 多小计列：多个字段可同时 true
  "conditional_formula": {
    "rules": [
      { "when": <CondTree>, "formula": "proc_turning" },  // 命中即停
      { "when": <CondTree>, "formula": "proc_milling" }
    ],
    "default": "proc_zero"                      // 末尾默认公式（无条件），必填
  }
}
```

绑定关系 = **公式字段 1 : N 条 `(条件树 → 具名公式)` + 1 条默认**，有序，首条命中即停。

约束：
- `formula` / `default` 仍引用 `component.formulas[]` 里的**具名公式**（不内联表达式），与现有 `formula_name` 同口径解析（`findFormulaByName`）。
- `is_subtotal` 与公式模式正交：任意公式字段都能配条件模式；被标记 `is_subtotal` 的字段额外把每行结果求和成"列总计"。
- 条件模式存在时，`formula_name` / `formula_assignments` 对该字段失效（优先级：`conditional_formula` > `formula_name` > `formula_assignments` > 名称匹配 > 位置兜底）。

---

## 4. 设计 B — 条件树（AND/OR 嵌套）

现有 `CondRowSpec`（`cardFormula.ts`）是**扁平**结构（每行带 `logic: and/or` 顺次连接，无分组），无法表达 `(A AND B) OR C`。升级为递归节点：

```ts
type CondOp = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';

type CondTree =
  | { kind: 'group'; logic: 'and' | 'or'; children: CondTree[] }   // 可嵌套分组
  | { kind: 'leaf'; left: string;        // 本组件本行的列名
                    op: CondOp;
                    rhs: { type: 'literal' | 'column'; value: string } };
```

- **左操作数**：仅本组件本行的列（含其它公式列），下拉选择。
- **运算符**：`== != > >= < <= in` 下拉。
- **右值**：`literal` 可键入；`column` 选本组件另一列做列列比较。
- **UI**：复用 `CardFormulaDrawer` 的列/运算符/值选择器，容器换成"可加分组、可嵌套"的条件树编辑器（相对现有最大的前端新增）。配置位置在「组件管理」字段配置 → 公式配置抽屉（Drawer，遵守项目 Drawer 规范）。
- **编译目标**：前后端各把 `CondTree` 编译为布尔判断（后端 JEXL，前端 `computeAllFormulas` 同款求值器）。两端共用一份对照的编译规则（TS / Java 镜像实现），并以单测锁定同一组样例的真值表一致。

---

## 5. 设计 C — 求值引擎（双引擎一致）

每行、每个公式字段：

1. **拓扑排序扩展**：收集所有公式字段，依赖边除"公式表达式引用的列"外，**追加"条件树各 leaf 的 left + column 型 rhs 引用的列"**。现有 `topoOrder` / `detectCycle` 据此扩展。
2. **环检测在组件保存时拦截**（前端 + 后端各一道）。
3. 按拓扑序逐字段求值：条件模式从上到下求 `when`，**第一条为真用其公式**，全不中用 `default`。
4. **落点（双引擎同步改、逐行口径一致）**：
   - 前端 `QuotationStep2.tsx#computeAllFormulas`（:372）
   - 后端 `FormulaCalculator#computeRows`（:355） + `collectFormulaFields` / `resolveFormulaExpression`
5. 单测：构造覆盖"命中首条 / 命中中间 / 全不中走 default / 条件引用公式列 / 列列比较 / IN"等样例，前后端结果逐行比对。

---

## 6. 设计 D — 多小计列与向上汇总

- `FieldConfigTable.handleSubtotalChange`：从"单选（设一个清其余）"改为**多选**。
- 后端 `findSubtotalFieldName` → `findSubtotalFieldNames`（复数）；`computeTabSubtotal` 对**每个**小计列各算 `sum(每行该列结果)`。
- 前端 `computeTabSubtotal` 同步：返回从单值改为 `Record<小计列字段名, number>`。
- **向上汇总**（结论 C / B）：每 `(组件, 小计列)` 各占一条独立汇总线，跨组件不归并。产品卡 / 报价单为每条线展示一个总计数。
- **最终总价**（假设 A1）：上层保留 `最终总价 = Σ 所有小计线`。
- **影响面**（需排查并改）：`computeProductSubtotal`、产品总额、报价单总额、Excel/PDF 模板取数（原取单一小计处改为可指定 `(组件, 小计列)`）。

---

## 7. 设计 E — 多列组合行键唯一性

- 数据模型已支持组合键（`rowKeyFields[]`，`computeRowKey` 以 `||` 拼接）。
- **保存时唯一性校验**（结论 B）：提交整单时，对每个组件把 **driver 展开行 + 手动行**全算 `computeRowKey`，找重复组合键，**列出冲突行并拦截保存**。
- 前端（提交前提示，定位到冲突 Tab/行）+ 后端（保存接口兜底，防绕过）双校验。
- driver 自身带重复组合键 → 同样报冲突，提示用户调整行键列选择或修基础资料。
- 与 AP-54 协同：`editRows` 按 rowKey 对齐，组合键唯一是该对齐正确的前提；本校验同时消除 `indexEditRows` 键碰撞风险。

---

## 8. 设计 F — 协议合规与测试（AP-44 雷区）

字段配置 schema 变动 = AP-44「17 检查点」级联动 + AP-39（PUBLISHED 模板 snapshot 同步）。落地清单：

**前端**：`component/types.ts`（`conditional_formula` / 多 `is_subtotal` 类型）、`FieldConfigTable.tsx`、`normalizeFieldType` 相关、enrich mapper、cache key、`computeAllFormulas`、`ReadonlyProductCard`（详情页同步，AP-50）、snapshot 传播路径。

**后端**：`ComponentService` 校验（默认公式必填 / 环检测 / 行键列合法）、`FormulaCalculator`、`TemplateService.refreshSnapshotsByComponent`（AP-40 同 cid 多实例精确匹配）、Flyway（若 snapshot 结构需迁移）。

**强制 E2E**（`docs/E2E测试方法.md`）：
- `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）
- `'加载中' final count = 0`，8 Tab 全绿
- 报价单 / 核价单 / 详情页三视图截图（改前 vs 改后）
- `POST /api/cpq/components/{id}/refresh-template-snapshots` 后打印各 Tab snapshot fields，确认 `conditional_formula` / 多 `is_subtotal` 正确入 snapshot

---

## 9. 兼容与迁移

- **老组件零迁移**：无 `conditional_formula` 的字段走单一模式（现有 `resolveFormulaExpression` 链路不变）。
- **老单一 `is_subtotal`**：天然是"多小计列集合"的 1 元素特例，无需迁移。
- **`rowKeyFields`**：已是数组，组合键无需迁移；仅新增校验。
- 无破坏性 Flyway 数据迁移；若 snapshot 仅是新增可选键，PUBLISHED 模板按 AP-39 评估是否需回填（默认不回填，读取侧对缺失键做单一模式兜底）。

---

## 10. 风险登记

| 风险 | 说明 | 缓解 |
|---|---|---|
| 双引擎口径漂移 | 前端 `computeAllFormulas` 与后端 `FormulaCalculator` 条件求值不一致 | 共用编译规则镜像实现 + 真值表对照单测 + E2E |
| 拓扑环 | 条件引用公式列引入新依赖边可能成环 | 保存时 `detectCycle`（含条件引用边）前后端各拦截 |
| AP-44 漏点 | 字段配置 schema 变动漏改某协议点 → 静默渲染错误 | 按 §8 清单逐项勾、grep 全工程、双 spec E2E |
| 上层总价语义 | 多小计向上后总价定义变化（假设 A1 待确认） | user review 拍板后再动 `computeProductSubtotal` / 模板取数 |
| 条件树 UI 复杂度 | 嵌套编辑器是最大前端新增 | 复用 `CardFormulaDrawer` 选择器；分组限制合理深度 |

---

## 11. 交付边界（YAGNI）

- 本期**不做**跨组件小计归并（结论 B：每列各占一条线）。
- 本期**不做**条件引用跨组件列 / 全局变量（结论 A：仅本组件本行）。
- 本期**不做**条件右值从全局变量/字典选（仅字面值 + 本组件列）。
- 条件构建器只做"选择式"，不提供原始表达式文本输入。
