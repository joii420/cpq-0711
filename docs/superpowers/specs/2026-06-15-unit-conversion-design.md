# 单位换算（Unit Conversion）设计 v4 — 2026-06-15

> 版本演进：
> - v1：卡片单引擎"行视图换算"。
> - v2：采纳一轮评审 + 决定覆盖 Excel/核价（曾设想视图 SQL 归一 / 单位字典）。
> - v3：live DB 实测确认现役 Excel/核价模板 100% 卡片取数、BNF 视图直读已死，砍掉视图层大改，收敛为卡片层换算。
> - **v4（本版）**：第三轮评审发现 v3 的"卡片层 + Excel 构造点两处独立插入"是错的——快照/Excel/小计共享同一份 `resolvedRows`，且 Excel 读的是**写快照时就物化好的** resolvedRows，消费端二次换算救不了 `C=FORMULA` 场景。改为**换算统一在生成端做一次 + resolvedRows 写 canonical 伴生值，消费端零换算**。吸收评审 A1~A5 / B1~B4 全部修正。

## 1. 背景与目标

报价 / 核价的公式计算中，重量与计数列的原始录入单位不统一（g / KG / 吨 / 片 / KPCS / g·PCS⁻¹ 等），导致同一条公式在不同行、不同料件下混入不同量纲，报价结果不可比。

**目标**：在公式计算环节把这些列**逐行归一**到统一单位（重量→KG，计数→PCS，比值→kg·PCS⁻¹），使所有公式计算结果（卡片渲染、快照、Excel/核价卡片取数连表公式、小计）单位一致，达到报价统一。

**非目标**：
- 不改变**明细输入列**的展示值、落库值、快照值——一律保留**原值**（公式结果列与小计列是派生值，见 §4/§9）。
- 不做可维护的单位配置表 / 管理页（预设表硬编码）。
- 不支持跨组件取单位、不支持列级固定单位字面量。
- **不支持 BNF 视图直读列（`{path}` / VARIABLE）的换算**——现役模板不用此类列（实测见 §5），列为已知限制。

## 2. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 明细输入列可见性 | 界面、落库、快照都显示**原值**（如 500g 仍显示 500） |
| 公式结果列 / 小计列 | 派生值，存 / 显示**换算后 canonical 值**（这是"报价统一"的目标本身） |
| 生效范围 | 所有"卡片取数"的公式消费链路：卡片行公式、组件小计、跨组件 / 跨 Tab 引用、详情/只读视图公式列、Excel/核价 CARD_FORMULA / TAB_JOIN_FORMULA |
| 换算表维护 | **代码内硬编码**；前后端各一份小 Map，由对拍测试守护一致 |
| 异常单位 | D 为空 / 不在预设表内 → **×1 原值透传**，不阻断报价，旁路 ⚠ 提示"未知单位" |
| 单位来源位置 | **同组件、同一行**的另一个字段 D（逐行读取） |
| 被换算列 C 类型 | 任意持有数值的列（INPUT_NUMBER / DATA_SOURCE / FORMULA 结果均可），但**不得同时是行键 / cross_tab 匹配键**（见 §8 约束） |

## 3. 预设换算表（已锁定，硬编码）

归一化规则：取 D 的单元格文本 → `trim` → 去内部空格 → 转大写（中文别名单独映射）→ 查表。

| 录入单位（别名，不区分大小写） | 归一目标 | 对 C 原值的系数 |
|---|---|---|
| 克 / G | KG | 0.001（÷1000） |
| 千克 / KG | KG | 1 |
| 吨 / T | KG | 1000（×1000） |
| 片 / PCS | PCS | 1 |
| KPCS / 千片 | PCS | 1000（×1000） |
| g/PCS（克/片，斜杠两侧大小写不限） | kg/PCS | 0.001（÷1000） |
| 空 / 不在表内 | —（不换算） | 1（原值透传） |

说明：
- canonical 目标由单位字符串自身决定，列上无需配目标单位——换算 = `rawValue × factor`。
- `kg/PCS` 档：分子 g→kg（÷1000），分母不变，整体系数 0.001。
- D 文本仅用于查表得 factor，**绝不拼进任何被 JEXL/ArithParser 解析的表达式串**。

## 4. 核心机制：生成端换算一次 + canonical 伴生值

两条公式计算路径，各只有一个换算锚点，**消费端零换算**：

**路径 A — 前端实时重算（卡片渲染 / 前端小计 / 跨 Tab）**
- 唯一锚点：`computeAllFormulas` **函数顶部**，对传入的 `row` 先构造换算视图（配了 `unit_source_field` 的列 C → `rawC × factorFor(D)`），再走既有 fieldValues 收集与求值。
- 因为前端小计 `computeTabSubtotalsByColumn`、跨 Tab `buildCrossTabRows` 都**经由** `computeAllFormulas`，在其顶部换算即一处覆盖三处，公式结果与小计自然 canonical。

**路径 B — 后端生成端（写快照 → Excel/核价/后端小计复用）**
- 锚点 1（公式输入换算）：`FormulaCalculator#computeRows` 在 `collectFieldValues`（约 :608）**之前**对 `mergedRow` 整体换算 → `fieldValues` 与 `currentRowRaw`（:619，同源派生）都吃到换算值 → **公式结果列天生 canonical**。
- 锚点 2（伴生值）：生成 `resolvedRows` 时（`CardSnapshotService#buildResolvedRows`），对每个配了换算的**输入列 C**，在该行额外写一份 **canonical 伴生值**（键如 `__uc__<C>`）；C 原值列保持原值。
- **消费端零换算**：
  - 公式结果列：已 canonical，直接用。
  - Excel `SUM_OVER([页签], C)` / 后端小计 `backfillSubtotalsFromResolved` 聚合**输入列 C**时：读伴生值 `__uc__<C>`（配了换算的列），否则读原值。
  - 详情 / 只读 / 卡片明细渲染：读 C 原值。

**为什么用伴生值而非"消费端读 D 现换"**：Excel 读的是写快照时物化好的 `resolvedRows`，其构造点（`ExcelViewService.java:279` 走 `CardEffectiveRows.parse(..., (cid)->null)` 3 参重载）**手头没有 fields 定义**、拿不到 `unit_source_field`，无法知道哪列该换、D 是谁；且 `C=FORMULA` 时其结果在写快照时已定。把换算前移到生成端、并把 canonical 预烘进 resolvedRows，消费端就无需 fields、无需 D、无需区分 C 是否公式列。

## 5. 生效范围实测依据 + 待排除路径

live DB（44 现役报价单）实测：现役模板 `报价模板0608` / `核价模板0603` 列全为 **CARD_FORMULA**（`SUM_OVER([来料#1], c0)` 卡片取数）+ **FORMULA**（`=[A]+[B]+[C]` 纯同行 col_key）；`costing_template`（老 BNF VARIABLE）连上现役报价单 = **0**。CARD_FORMULA / TAB_JOIN_FORMULA 经 `CardDataProvider` 卡片有效行，BNF VARIABLE 经 `DataLoader` 视图直读。

**已知限制 / 实现前必须 grep 排除的非卡片路径**（评审 B1）：
- BNF 视图直读列（`{path}` / VARIABLE）：现役不用，不换算。
- `SUM_OVER` 在 `CardAggregateSource` ThreadLocal **未 set** 时会回退 `dataLoader.loadByPath` 视图直读（`TemplateFormulaService` 约 :643/:664）——这条不经卡片行、不换算。实现前 grep 现役模板小计 token 是否有走此回退的 SUM_OVER。
- `DerivedAttributeCalculatorV5` / `ComponentDriverService` driver 展开：核实有无在卡片物化前就消费待换算列（driver 行是 C 的来源，换算应在其下游 computeRows，理论安全，需确认）。

## 6. 配置模型 + 共享换算工具

**字段配置**：字段（component.fields 内列定义）新增属性 `unit_source_field`，存**同组件内单位列 D 的字段 `name`**（钉死 name，与各引擎取值键 `fieldName(f)` / `f.name||f.key` 口径一致）。非空 ⇒ 启用换算。不新增 `field_type` 枚举。

**共享换算工具（轻量双副本 + 对拍）**：
- 前端 `cpq-frontend/src/utils/unitConversion.ts`、后端 `cpq-backend/.../UnitConversion.java` **各硬编码一份小 Map**（归一化别名 → factor），导出 `factorFor(unitText)`。
- 沿用项目既有"前后端各一份实现 + 对拍测试"先例（如 `uniquifyRowKeys` / `bnfDriverLookupKey`），**不引入 JSON 文件 / classpath 读取**这个新失败面（评审 B2）。
- **跨端对拍测试（强制）**：同一组输入断言两端 factor 逐档相等——这是双副本唯一漂移护栏。

## 7. 插入点清单（实现检查清单）

**换算锚点（仅 4 处真正做换算）**
1. 前端 `computeAllFormulas` 顶部：换 `row`（覆盖渲染 + 前端小计 `computeTabSubtotalsByColumn` + 跨 Tab `buildCrossTabRows`，因后两者都经由它）。
2. 后端 `FormulaCalculator#computeRows`：`collectFieldValues`（约 :608）之前换 `mergedRow`。用 **BigDecimal** 解析 C（可能是 TextNode "12.5"）再乘再包回 DecimalNode，**禁用 double**（精度，评审 A4）。只改单元格值、不重建行（守 AP-51）。
3. 后端 `CardSnapshotService#buildResolvedRows`：对配了换算的输入列写 canonical 伴生值 `__uc__<C>` 进 resolvedRows。
4. 后端 / 前端跨 Tab 入参：`crossTabRows` 的源行须含伴生值或换算后值，cross_tab_ref 解析配了换算的列时读 canonical（与小计同口径）。

**消费端（读伴生值，不自己换算）**
5. Excel `SUM_OVER` / `CardAggregateSource` 聚合输入列：配了换算的列读 `__uc__<C>`。
6. 后端小计 `backfillSubtotalsFromResolved`：聚合输入列时读 `__uc__<C>`；公式结果列已 canonical。
   - 注意（评审 A2）：现状 `resolved` 数组同时落库 + 喂小计，是同一对象。伴生值方案下二者共用同一 resolvedRows 即可——原值列存原值、伴生键存 canonical，小计读伴生键，不必再造并行数组。

**元数据传播（grep + `codegraph_impact unit_source_field` 按真实调用边列全，别只信本清单 —— 评审 A5）**
7. 组件 fields(JSONB) 持久化新属性。
8. 管理端 `FieldConfigTable`：新增"单位换算来源"Select，选项 = 同组件内可作单位的字段，写入 `unit_source_field`（value = 字段 name）。
9. 快照**写入**侧 `TemplateService#refreshSnapshotsByComponent` 整体搬 fields 数组，新属性**自动随带、无需改**（评审 B3）。要改的是**消费/生成侧**：`FormulaCalculator`（锚点 2）、`buildResolvedRows`（锚点 3）、`assembleTabsWithFormulaResults`、前端 `ComponentField` 类型 + `enrichComponentData` 透传、详情页 `useCardSnapshots.resolveDataSource` + `ReadonlyProductCard.buildFormulaCache`（AP-50）。
10. 渲染层（明细输入列展示）不动——读原值。

## 8. 不变量与约束

**不变量**
- 明细输入列展示 / 落库 / 快照 = 原值；公式结果列 / 小计列 = 换算后 canonical 值。
- 前后端换算逻辑等价（双副本 + 对拍）⇒ 前后端一致性校验（差值 ≤0.01）仍成立。
- 报价单 + 核价单 + 详情视图 + Excel/核价卡片取数公式统一覆盖（同口径）。
- 换算只改单元格数值 / 新增伴生键，绝不增删行 / 改 `rowCount`（守 AP-51）。
- BNF 视图直读列不换算（已知限制）。

**约束**
- 被配 `unit_source_field` 的列**不得同时是行键 / cross_tab 匹配键**（评审 A4）：`currentRowRaw` 同时是 cross_tab_ref 匹配键 b 的取值源，换算会改掉匹配键导致失配。一般计量数值列不当键，但需在 `FieldConfigTable` 校验或文档显式禁止。
- 后端换算一律 BigDecimal，禁 double。

## 9. 小计与公式结果列口径

- 公式结果列、小计列是**派生值**，存 / 显示换算后 canonical，卡片小计行**标注 canonical 单位**（如"小计 1.5 KG"），避免与同卡明细原值口径混淆。
- 输入列 C 的小计（直接对输入列求和的场景）：读伴生值 `__uc__<C>` 求和 → canonical（评审 A2 的"原值之和"陷阱由此消解：不再 `sum(原值)`，而是 `sum(伴生 canonical)`）。
- 明细输入列本身仍存原值（守 §8 不变量）。

## 10. 异常单位处理

- D 为空 / 不在预设表 → factor=1 原值透传，不阻断报价。
- 走现有 `out.errors` / `outDiag` 旁路透出"未知单位"⚠。**需确认渲染层能按"第 i 行第 C 列"粒度定位**（评审 B3）；若只能整组件级，先整组件级提示，后续打磨。

## 11. 验收用例（最小集）

- 同列三行单位不同（g / KG / 吨）→ 各行公式按各自系数换算；明细输入列展示仍各自原值。
- D 空 / D="mm" → 该行 ×1 透传 + ⚠ 提示，公式用原值。
- KPCS → ×1000 进 PCS 量纲；g/PCS → ÷1000 进 kg/PCS 量纲。
- 组件小计 / 跨 Tab 引用该列 → canonical 汇总；小计标注 canonical 单位。
- **C 是 FORMULA 且其公式内部消费另一待换算列**（评审 B4）：如 C 公式 = `[重量列] × [单价]`、重量列配 g→KG → 验证卡片端结果 = Excel `SUM_OVER` 端结果（口径一致 ≤0.01）。
- Excel CARD_FORMULA `SUM_OVER([页签], C)` 聚合输入列 → 读伴生值，与卡片端一致。
- 前后端一致性校验通过；E2E 刷新 3 次行数稳定（未累加）。

**自检 / 测试**
- 后端单测：换算表全档 + `FormulaCalculator` 逐行换算 + `buildResolvedRows` 伴生值 + 跨端对拍。
- 前端单测：换算表全档 + computeAllFormulas（含小计 / 跨 Tab caller）行级换算。
- 协议级改动 ⇒ Playwright `quotation-flow.spec.ts`：换算行公式值正确、`'加载中' final count = 0`、8 Tab 正常；复测报价单 + 核价单 + 详情页 + Excel 视图四处。
- TS `tsc --noEmit` 0 错；改动 `.tsx` 走 Vite 200；后端 touch 重启 + health 200；Flyway（若有）success=t。

## 12. 开发流程

- 隔离 worktree 分支（`superpowers:using-git-worktrees`）。
- 默认 `superpowers:subagent-driven-development` 推进。
- 完成后用户确认 → 自动合并 master + E2E + 清理 worktree。
