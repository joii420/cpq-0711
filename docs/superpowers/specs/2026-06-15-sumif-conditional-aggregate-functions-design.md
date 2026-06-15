# SUMIF 条件聚合函数族 — 设计方案（v2，已核实代码现状后重写）

- 日期：2026-06-15
- 状态：方向已确认，待用户复审 → 进 writing-plans
- 涉及核心基线：是（公式渲染，见 `docs/三大核心模块基线.md`）
- 修订说明：v1 误判组件线走 JEXL、且漏看了已存在的 `cross_tab_ref` 特性。经逐行核实（见 §10 证据）重写为"扩展两个现有底座"方案。

## 1. 背景与目标

聚合时需按**条件过滤**取值，新增一族带条件的聚合函数 `SUMIF / COUNTIF / AVGIF / MINIF / MAXIF`。

可读语法：

```
SUMIF([页签A.字段a] = '管理费',      [页签A.字段b] * [宿主页签.字段B])   -- 语法1：字段 vs 字面量
SUMIF([页签A.字段a] = [页签B.字段a], [页签A.字段b] * [宿主页签.字段B])   -- 语法2：字段 vs 字段（跨页签）
```

- 组件内公式：结果按**宿主页签行键分组对齐**到宿主行（分行）。
- EXCEL 公式 / 小计公式：结果为**单值**。

## 2. 现状核实结论（决定本方案的基础事实，证据见 §10）

系统里**没有"三条独立引擎"，只有两个底座**，且两者都已实现条件聚合的大部分：

### 底座①：组件 token 线（前后端各一份镜像）
- 组件字段公式 = `ExpressionToken[]`，**不走 JEXL**：后端 `FormulaCalculator.evaluateExpression`（token → `appendToken` 拼算术串 → `ArithParser.parse`），前端 1:1 镜像 `formulaEngine.ts`。两份必须严格对齐。
- 已有成熟跨页签条件聚合特性 **`cross_tab_ref`**（`evalCrossTab`）：
  - `source` = 兄弟组件已算行（`ctx.crossTabRows`，由 `CardSnapshotService` 按拓扑序、按 **componentId + code 双键**喂入）；
  - 按**当前宿主行** `ctx.currentRowRaw` 用 `match:[{a,b}]` 配对（`a`=source行字段，`b`=宿主行字段）；
  - 五种 reduce `SUM/AVG/MAX/MIN/COUNT`；SUM 对多命中**已累加（即已"放大"）**；
  - 空集 → 0（SUM/COUNT/KSUM）或 null 塌 0（KAVG/KMAX/KMIN）；
  - `targetExpr` 逐命中行求值（即 valueExpr）；支持多 source 广播、KSUM(`projectToHostKey`)；
  - 相等比较 `valEquals` = 两边可解析为数则数值比较，否则 trim 文本比较；
  - 配套校验：`TokenMappabilityValidator`（组件线结构校验）+ `TemplateService` 模板级 source/防环校验 + `ComponentService.validateFormulas`。
- **局限**：`match` 只支持「两个行字段等值的 AND 合取」——**不能比字面量、无 OR、无不等/比较**；且 NONE（标量查找）对多命中返 ERR。

### 底座②：EXCEL/模板线
- `TemplateFormulaService.executeOverFunction` 已实现「逐行 → WHERE 谓词过滤 → 聚合」，谓词走 JEXL（`evalRowExpression`），**已支持 AND/OR/比较**；`SUM_OVER([X] WHERE pred, expr)` 即条件求和、出单值。
- 小计公式骑在这条线上：`CardAggregateSource` 给它注入卡片行 + 动态谓词，**不是第三套引擎**。
- 解析靠 `OVER_FUNC_PATTERN` + `findMatchingParen` + `findTopLevelComma`（`parseOverFuncArgs`）。
- **局限**：WHERE 谓词作用在**单一 source 的行内字段**上，非跨页签 join；结果总是单值（无宿主行分组）。

## 3. 范围与不变量（已确认）

| 项 | 决定 |
|---|---|
| 函数族 | 一次做齐 `SUMIF / COUNTIF / AVGIF / MINIF / MAXIF` |
| 落地策略 | **扩展两个现有底座**，不另起公式引擎、不建跨语言"共享核" |
| 入口 | 组件字段公式（底座①，分行）+ 模板 TAB_JOIN_FORMULA/EXCEL 列 + 小计公式（底座②，单值） |
| 组件线录入 | **与现有公式一致：点击生成 token 到表达式框**；`SUMIF(...)` 文本是其可读表示/底层形态。不新造自由文本引擎 |
| 嵌套 | **不做聚合套聚合**（嵌套规则后续统一）；`XXXIF(...)` 可作为普通项出现在算术里（如 `[B.字段]*SUMIF(...)`），与现有 token 混排一致 |
| 条件运算符 | `= != <> > < >= <=` + `AND` / `OR` + 括号 |
| 跨页签语义 | 复用 `cross_tab_ref` 的「按当前宿主行 `currentRowRaw` 配对」模型；条件命中行做 reduce；SUM 对多命中累加（放大，沿用现状） |
| 类型/NULL | 复用现有 `valEquals`（数值优先，否则 trim 文本）；任一操作数 blank → 比较不成立 |

**显式非目标**：聚合互相嵌套；把老 `SUM_OVER ... WHERE` 写法迁成新语法（保留兼容）；全量 AST 重写；给组件线新增自由文本公式引擎。

## 4. 设计：扩展两个底座

### 4.1 组件线（底座①）——扩 `cross_tab_ref` 的条件能力 + 新增 SUMIF token UX
真正的增量是「比 `match` 等值-AND 更强的条件」。**已选定取向 A**：

- **取向 A（已选定）：给 `cross_tab_ref` 增加一个可选 `predicate` 结构**（布尔树：AND/OR/比较，操作数 = source行字段 / 宿主行字段 / 字面量），与现有 `match` 并存：`match` 仍负责"行键对齐"，`predicate` 负责"附加过滤"。`evalCrossTab` 在 hits 过滤处叠加 predicate 求值即可，五 reduce / 空集 / targetExpr 全部复用。`predicate` 缺省为空 → 行为与现状完全一致（纯增量、零回归）。
- ~~取向 B：新增并列 token 类型~~（未采纳）。

**predicate 求值器（小递归下降布尔解析 + 求值）必须前后端各实现一份并对齐**（后端 Java、前端 TS），与现有 `evalCrossTab` ↔ `formulaEngine.ts` 的对齐纪律一致。

- 语法1（`[A.a]='管理费'`，字段 vs 字面量）：现有 `match` 无法表达，由新 predicate 承载。
- 语法2（`[A.a]=[B.b]`，字段 vs 字段）：等值部分可落到 `match`（继续负责行键对齐），其余条件落 predicate。
- 录入 UX：在现有表达式框里，像 `cross_tab_ref` 一样**通过抽屉/按钮点击生成** SUMIF token；表达式框展示其可读文本 `SUMIF(...)`。

### 4.2 EXCEL/小计线（底座②）——SUMIF 文本映射到 `SUM_OVER WHERE`
- 扩展 `OVER_FUNC_PATTERN` / `parseOverFuncArgs` 识别 `XXXIF(cond, expr)`，把 `cond` 规整成现有 WHERE 谓词喂给 `executeOverFunction`（谓词 JEXL 已支持 AND/OR/比较，基本零新增）。
- `COUNTIF(cond)` 单参 → 等价 `COUNT_OVER([src] WHERE cond, 1)`。
- 结果单值，符合该线现状。
- 跨页签 `[A.a]=[B.b]`：该线 WHERE 作用于单 source 行字段，跨页签取数仍按该线既有 source 解析能力；若超出能力，计划阶段明确"EXCEL 线 SUMIF 跨页签的支持边界"（见 §9 P0-5）。

### 4.3 为什么不做 v1 的"共享核"
- 组件线前端是浏览器内 TS 实时求值（`formulaEngine.ts`），Java 共享核够不到 → 跨语言必然两份，"单一共享核消灭漂移"在现实里破产。
- 两个底座的逐行求值器异构（`ArithParser` token 串 vs JEXL `MapContext`），强行套一个核反而增加耦合与回归面。
- 各自扩展是纯增量，且能直接复用现成的宿主行配对、reduce、空集、校验、缓存喂入机制。

## 5. 条件 predicate 解析器（唯一新增 parser，前后端各一份）

只解析布尔条件这一小段文法：

```
orExpr   := andExpr ('OR' andExpr)*
andExpr  := cmpExpr ('AND' cmpExpr)*
cmpExpr  := '(' orExpr ')' | operand op operand
operand  := fieldRef | '字符串字面量' | 数字
fieldRef := [页签/source.字段]            -- 区分 source行字段 与 宿主行字段
op       := = | != | <> | > | < | >= | <=
```

- 递归下降，约 150 行 × 2（Java + TS），完全可单测，**前后端结果必须逐用例对齐**。
- 比较语义复用现有 `valEquals` 口径（数值优先，否则 trim 文本；blank → 不成立），保证与现存 `cross_tab_ref` / `SUM_OVER WHERE` 行为一致、不引入回归。
- **中文标识符**：`[页签A.字段a]` 内中文字段名不能直接当标识符，按 `cpq-chinese-identifiers-need-ascii-alias` 教训——predicate 里字段引用按"页签/source + 字段名"结构化保存，求值时从行 Map 取值（不进字符串表达式拼接），规避中文标识符坑。

## 6. 关键语义真值表（钉死边界）

| 场景 | 条件引用 | B 命中行数 | SUMIF | COUNTIF | AVGIF | MIN/MAXIF |
|---|---|---|---|---|---|---|
| 语法1 | 仅 A（字面量） | — | 组内/source内 A 命中行 valueExpr 求和 | 命中行数 | 命中行均值 | 命中行极值 |
| 语法2 | A vs B | 0（无匹配） | 0 | 0 | 0 | 0（沿用现状空集→0，§见注） |
| 语法2 | A vs B | 1 | A行值×1 | +1 | 计入 | 计入 |
| 语法2 | A vs B | 多 | A行值被累加 N 次（**放大**） | +N | N 行计入 | 极值 |

- 注：MIN/MAX 空集→0 是**继承自现有 `aggregate()` 的已知坏味道**（负值场景会污染），本方案选择"对齐现状"，并显式标注为已知缺陷，不在本次修正。
- 分行 vs 单值：组件线在 `computeRows` 逐宿主行求值、predicate 的宿主侧从 `currentRowRaw` 取值 → 天然分行；EXCEL 线无宿主行上下文 → 单值。

## 7. 协议传播（AP-44 / token 类型新增，必须设计阶段列全）

若采取 §4.1 取向 B（新 token 类型）或取向 A 给 `cross_tab_ref` 加 `predicate` 字段，按 AP-44 需穿透约 17 个检查点。设计阶段须逐条落实，至少包含：
- 前端 `formulaEngine.ts`：`ExpressionToken` 类型/字段、`evaluateExpression` 求值分支、predicate 求值器。
- 后端 `FormulaCalculator`：`appendToken`/`evalCrossTab` 分支、predicate 求值器。
- 校验三处：`TokenMappabilityValidator`（组件结构）、`ComponentService.validateFormulas`、`TemplateService` 模板级 + `TemplateFormulaService.validateFormula`（EXCEL 线）。
- snapshot 链路：`CardSnapshotService` 拓扑序 / `refreshSnapshotsByComponent`（AP-40）。
- 渲染链路：`QuotationStep2`/`ReadonlyProductCard`（AP-50）/ `useDriverExpansions`（AP-37/AP-38）。
- 自动补全 `getFormulaCompletions`（EXCEL 线）+ 组件线公式编辑器候选。
- 缓存 key：复用 `crossTabRows` 的 **componentId+code 双键**（已满足 `cpq-sqlview-cache-key-needs-component-dim`）；新增任何按 source 的索引同样含 componentId 维度。

## 8. 性能（按现状如实评估，不虚承诺）

- 组件线 source 行由 `CardSnapshotService` 预先按拓扑序算好喂入 `crossTabRows`，**本就只算一次**；predicate 叠加是 hits 过滤处的 O(rows) 线性判定（与现有 `evalCrossTab` 同量级，现状规模可接受）。
- 是否为 source 建按宿主键的 hash 索引（O(1)）= **新增优化项**，现有 `evalCrossTab` 是线性扫，未做；计划阶段按实际数据规模决定要不要做，**不做就不在 spec 承诺**。
- EXCEL 线 `executeOverFunction` 每次 `loadByPath` 重查（现状），SUMIF 沿用，不额外恶化。
- 快速路径：公式不含 SUMIF token / `XXXIF` 文本时，两线均直接跳过（token switch 不命中 / `OVER_FUNC_PATTERN` 不匹配），现有公式零额外开销。

## 9. 进入计划阶段前必须定清的 P0 项

1. ~~组件线取向 A vs B~~ **已定：取向 A**（给 `cross_tab_ref` 加可选 `predicate` 字段，缺省空 = 现状）。
2. **predicate 数据结构**：前后端统一的布尔树 JSON 形态（节点类型、操作数区分 source字段/宿主字段/字面量）。
3. **前后端求值器对齐策略**：predicate 解析+求值的 Java/TS 双份如何保证一致（共享用例集 + 双 E2E）。
4. **AP-44 检查点逐文件清单**（把 §7 展开成可勾选矩阵）。
5. **EXCEL 线 SUMIF 跨页签支持边界**：WHERE 作用于单 source，`[A.a]=[B.b]` 跨页签在该线是否支持/如何降级；与组件线语义差异如何向用户说明。
6. **SUMIF 与现有 `cross_tab_ref` 的共存与防混用**：同卡片同时存在两者时校验规则。
7. **录入 UX**：SUMIF 抽屉的条件构造器形态（沿用 `TabJoinFormulaDrawer` 还是新抽屉）。

## 10. 核实证据（一手，file:line）

- 组件线非 JEXL：`FormulaCalculator.java:73-87`（`evaluateExpression`→`ArithParser`）、`:89-218`（`appendToken`）；前端镜像 `formulaEngine.ts:5-48`（`ExpressionToken`）。
- `cross_tab_ref` / `evalCrossTab`：`FormulaCalculator.java:264-333`（hits 过滤 `:283-296`、reduce `:298-332`）、`targetExpr` 求值 `:347-444`、`valEquals` `:454-457`、`RowContext.currentRowRaw/crossTabRows` `:63-66`。
- crossTabRows 双键喂入：`CardSnapshotService.java:759-808`。
- 校验：`TokenMappabilityValidator.java`、`ComponentService.java:464-525`、`TemplateService.java:248-249/1010-1016`。
- EXCEL 线条件聚合：`TemplateFormulaService.java:722-745`（WHERE+聚合）、`parseOverFuncArgs:755-791`、`findMatchingParen:806`、`findTopLevelComma:794-803`、`aggregate:1476`、`validateFormula:211`、`getFormulaCompletions:1330`。
- 小计骑 EXCEL 线：`executeOverFunction:693/719`（`CardAggregateSource.rowsFor/predicateFor`）。
- BNF 路径 JEXL（与组件字段公式无关）：`FormulaEngine.java:63`。

## 11. 测试

**单元（前后端对齐）**
- predicate 解析器：各运算符 / AND-OR / 括号 / 类型强转（数 vs 文本，对齐 `valEquals`）/ blank 操作数 / 非法语法报错；**Java 与 TS 跑同一组用例、结果一致**。
- 求值：语法1（字面量）、语法2（跨页签 0/1/多命中放大）、五 reduce、空集补 0、分行 vs 单值。

**E2E（Playwright，协议级改动强制）**
- 双 spec：`quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）。
- 三入口渲染证据：组件字段公式**按宿主行键分行对齐**、EXCEL 列**单值**、小计**单值**。
- 判据：所有 test `passed`，`'加载中' final count = 0`。
