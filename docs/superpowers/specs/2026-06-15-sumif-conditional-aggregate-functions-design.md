# SUMIF 条件聚合函数族 — 设计方案

- 日期：2026-06-15
- 状态：设计已确认，待写实现计划
- 涉及核心基线：是（公式渲染，见 `docs/三大核心模块基线.md`）— 改动属增量，落地走隔离 worktree + TDD + 双 E2E spec

## 1. 背景与目标

现有聚合（`SUM/COUNT/AVG/MIN/MAX` 及 EXCEL 线的 `*_OVER` 族）只能对整个行集求值。新需求：聚合时按**条件过滤**取值，即新增一族带条件的聚合函数。

支持语法：

```
SUMIF([页签A.字段a] = '管理费', [页签A.字段b] * [宿主页签.字段B])
SUMIF([页签A.字段a] = [页签B.字段a], [页签A.字段b] * [宿主页签.字段B])
```

- 组件内公式：计算结果按**宿主页签行键分组对齐**到宿主行（分行）。
- EXCEL 公式 / 小计公式：计算结果为**单值**。

## 2. 范围与不变量（已与需求方确认）

| 项 | 决定 |
|---|---|
| 函数族 | 一次做齐 `SUMIF / COUNTIF / AVGIF / MINIF / MAXIF` |
| 配置入口 | 三处全覆盖：**a** 组件字段公式（`FormulaEngine`/`FunctionRegistry`，分行）、**b** 模板 TAB_JOIN_FORMULA/EXCEL 列（`TemplateFormulaService`，单值）、**c** 小计公式（`CardAggregateSource`，单值） |
| 引擎 | **不新建公式引擎**，扩展现有两条线 |
| 嵌套 | **不做聚合套聚合**（嵌套规则以后统一）；但 `XXXIF(...)` 可作为普通项出现在算术表达式里，如 `[B.字段] * SUMIF(...)` |
| 条件运算符 | `=` `!=` `<>` `>` `<` `>=` `<=` + `AND` / `OR` + 括号 |
| 跨页签语义 | 按宿主行键分组；条件作连接谓词；A×B **笛卡尔积、会放大**；对宿主页签**左联**（无命中组→0/计数0） |

**显式非目标（Out of Scope）**：
- 聚合函数互相嵌套（`SUMIF(..., SUMIF(...))`）— 后续独立需求统一规则后再做。
- 把现有 `SUM_OVER ... WHERE` 正则机制迁成新语法 — 老写法原样保留。
- 全量重写公式引擎为 AST。

## 3. 关键语义

### 3.1 行域（iteration domain）
- 行域 = 条件与 valueExpr 中引用的**非宿主页签**（A、B…）在**同一宿主行键组内**的**笛卡尔积**。
- **宿主页签字段**按当前宿主行取标量值，是分组轴，**不进笛卡尔**。
- 语法 1（条件只引用 A，无 B）退化为组内 A 行的过滤。

### 3.2 条件过滤
对行域内每个组合行，套用完整布尔 Predicate（含 AND/OR/括号）过滤；命中行用本线原生逐行求值器计算 valueExpr。

### 3.3 笛卡尔放大（已确认为期望行为）
同一宿主行键组内，若某 A 行按条件匹配到 B 的多行，该 A 行被计算**多次**（标准 JOIN 笛卡尔语义）。
- 不想放大时，用户自行改写：把 B 移出条件，例如 `[B.字段] * SUMIF([A.类型]='管理费', [A.金额])`。
- 放大对 SUM/COUNT/AVG 有影响；MIN/MAX 不受重复影响。

### 3.4 宿主左联与空组
遍历所有宿主行；某宿主行键组无命中组合行时：
- `SUMIF` → 0
- `COUNTIF` → 0
- `AVGIF` / `MINIF` / `MAXIF` 空集 → 0（对齐现有 `*_OVER` 空集语义）

### 3.5 分行 vs 单值
靠求值上下文里**有没有"当前宿主行键"**区分，同一份共享核两种结果：
- 组件线公式本就**逐宿主行**求值 → ctx 带当前宿主行键 → 行域只限该组 → 输出该宿主行的值（分行对齐）。
- EXCEL/小计线无宿主行上下文 → 跨所有组聚合 → 单值（所有命中行加总成一个总数）。

### 3.6 类型与 NULL 语义
- `=` / `!=` / `<>`：两边都可解析为数 → 数值比较；否则按字符串（trim）比较。`'管理费'` 走字符串。
- `>` `<` `>=` `<=`：数值比较；非数值操作数 → 该比较不成立（行不命中）。
- 任一操作数为 NULL → 该比较为假（行被排除）。
- `COUNTIF` 为**单参**：`COUNTIF(condition)`，计命中的笛卡尔组合行数；其余四个为双参 `XXXIF(condition, valueExpr)`。

## 4. 架构：一个共享核 + 各线一个预解析钩子

抽取共享模块 `ConditionalAggregateCore`，被三个入口复用，避免两套实现漂移（防 AP-44 式协议分裂）。**它不是第二个公式引擎**，只是被现有引擎调用的 helper。

```
XXXIF(condition, valueExpr)
        │
   [各线预解析钩子] ← 括号感知切出 XXXIF 调用并拆参数（复用 findMatchingParen，非正则）
        │
ConditionalAggregateCore.evaluate(funcName, condition, valueExpr, ctx)
   ├─ 1) parseCondition(condition) → Predicate 树（小型递归下降，缓存）
   ├─ 2) 构建行域：referenced tabs 在宿主行键组内做笛卡尔积
   ├─ 3) 逐行：Predicate 过滤；命中行用【本线的逐行求值器】算 valueExpr
   └─ 4) reduce（SUM/COUNT/AVG/MIN/MAX）
        │
   把结果替换回标量字面量 → 交给本线原有求值流程
```

设计要点：
- **valueExpr 的逐行求值仍用各线原生求值器**：组件线走 `FormulaEngine`/JEXL，EXCEL/小计线走现有 `evalRowExpression`/`rewriteRowFunctions`。共享核只管"条件解析 + 行域构建 + reduce"。
- **三线都新增一个"预解析 XXXIF → 标量"的前置 pass**，与 EXCEL 线现有 `resolveAggregates` 同形：找到顶层 `XXXIF(...)`，求值成标量字面量再替换回去。因不做嵌套，单趟顶层解析即可；`[B.x]*SUMIF(...)` 因 SUMIF 先被解析为标量而正常工作。
  - 组件线（JEXL）必须走预解析：JEXL 对函数参数是**急求值**，无法把 `[A.a]='管理费'` 当未求值子表达式传入，故不能直接注册为普通 JEXL 函数。

## 5. 条件解析器（唯一新增 parser，刻意做小）

只解析布尔条件这一小段文法，不碰整个公式语言：

```
orExpr   := andExpr ('OR' andExpr)*
andExpr  := cmpExpr ('AND' cmpExpr)*
cmpExpr  := '(' orExpr ')' | operand op operand
operand  := [页签.字段] | '字符串字面量' | 数字
op       := = | != | <> | > | < | >= | <=
```

- 递归下降，约 150 行，完全可单测。条件这段**彻底告别正则**。
- XXXIF 调用边界/参数切分用现成的 `findMatchingParen`（括号匹配，非正则）。
- 产出 `Predicate` 树：`And` / `Or` / `Comparison(lhs, op, rhs)`，操作数为 `FieldRef`（页签.字段）或 `Literal`（字符串/数字）。

## 6. 性能

- 页签行**每次求值只加载一次**，复用现有 driver expansion / RowSet 缓存；按宿主行键建索引 → 组查 O(1)。
- 笛卡尔积**只在组内**（受组大小约束，非全表）。
- 条件 Predicate **解析一次**，随 snapshot 缓存失效（遵循现有 `CachedPathParser` 纪律 + AP「DDL 后空集缓存」教训，失效要干净）。
- valueExpr 中不依赖组合行的子表达式（宿主字段、`@变量`）按宿主行外提一次，不进逐行循环。
- **快速路径**：公式不含 `XXXIF` token 时预解析钩子直接跳过 → 现有公式零额外开销、零回归。

## 7. 风险与回归面

- 纯**增量**：新函数族 + 仅在出现 `XXXIF` 时触发的预解析；老 `SUM_OVER ... WHERE` 与现有 JEXL 路径不动。
- 属核心基线（`docs/三大核心模块基线.md`）：落地走隔离 worktree + TDD；协议级改动按 CLAUDE.md 跑**双 E2E spec**（`quotation-flow.spec.ts` SIMPLE + `composite-product-flow.spec.ts` COMPOSITE）。
- 触及 CLAUDE.md「修改后强制自检」清单中的协议级文件（`FormulaCalculationService.java` / `TemplateFormulaService.java` / 组件渲染链路），须按清单跑 E2E 并出渲染证据。

## 8. 测试

**单元测试**
- 条件解析器：各运算符 / `AND`-`OR` / 括号嵌套 / 类型强转（数 vs 字符串）/ NULL 操作数 / 非法语法报错。
- 共享核：
  - 笛卡尔放大（B 命中多行 → A 行被计多次）。
  - 宿主左联补 0（空组）。
  - 分组（带宿主行键）vs 总计（无宿主行键）两种输出。
  - 五个 reduce 各自正确（SUM/COUNT/AVG/MIN/MAX）。
  - 语法 1（字面量）与语法 2（跨页签）两路。

**E2E（Playwright）**
- 三入口各一条：
  - a 组件字段公式：验证结果**按宿主行键分行对齐**。
  - b EXCEL/TAB_JOIN_FORMULA 列：验证**单值**。
  - c 小计公式：验证**单值**。
- 通过判据：所有 test `passed`，`'加载中' final count = 0`。

## 9. 待实现计划阶段细化的点

- `ConditionalAggregateCore` 的 ctx 接口定义（行集解析器、宿主行键、本线逐行求值器回调）。
- 三个预解析钩子在各自调用栈的精确插入点（组件线公式求值入口 / `resolveAggregates` / `CardAggregateSource`）。
- 函数注册与 token 白名单：自动补全（`getFormulaCompletions`）、校验（`validateFormula`）、前端公式编辑器是否需识别新 token。
- `referenced tabs` 的提取方式（从条件 + valueExpr 解析页签引用）。
- 缓存 key 设计（条件 Predicate 缓存、行域索引缓存的维度，参考 `cpq-sqlview-cache-key-needs-component-dim` 教训）。
