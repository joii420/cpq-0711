# 单位换算（Unit Conversion）设计 v5 — 2026-06-15

> 版本演进：
> - v1：卡片单引擎"行视图换算"。
> - v2：采纳一轮评审 + 决定覆盖 Excel/核价（曾设想视图 SQL 归一 / 单位字典）。
> - v3：live DB 实测确认现役模板全卡片取数、BNF 视图直读已死，收敛为卡片层换算。
> - v4：改"生成端换算 + resolvedRows 写 canonical 伴生值 `__uc__<C>`"。
> - **v5（本版）**：第四轮评审证伪伴生值机制——消费端（Excel SUM_OVER 的 `rowsFor`、后端小计、cross_tab）**全按字段名读行，无人读伴生键**，"消费端零换算"是伪命题；且 v4 误判 `parseEffectiveRows` 没有 fields（实则它持有 `componentsSnapshot`）。**砍掉伴生键**，回归"converted row view"：在每个**有 fields 可用**的公式/聚合物化点，把配换算的列 C 覆盖成 canonical 的**克隆**视图；明细输入列存 / 显示原值。物化点经四轮评审已枚举完整（§7）。

## 1. 背景与目标

报价 / 核价的公式计算中，重量与计数列的原始录入单位不统一（g / KG / 吨 / 片 / KPCS / g·PCS⁻¹ 等），导致同一条公式在不同行、不同料件下混入不同量纲，报价结果不可比。

**目标**：在公式计算环节把这些列**逐行归一**到统一单位（重量→KG，计数→PCS，比值→kg·PCS⁻¹），使所有公式计算结果（卡片渲染、快照、Excel/核价卡片取数连表公式、小计）单位一致，达到报价统一。

**非目标**：
- 不改变**明细输入列**的展示值、落库值、快照值——一律保留**原值**（公式结果列与小计列是派生值，存 / 显示 canonical，见 §4）。
- 不做可维护的单位配置表 / 管理页（预设表硬编码）。
- 不支持跨组件取单位、不支持列级固定单位字面量。
- **不支持 BNF 视图直读列（`{path}` / VARIABLE）的换算**——现役模板不用（实测见 §5），列为已知限制。

## 2. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 明细输入列可见性 | 界面、落库、快照都显示 / 存**原值**（如 500g 仍显示 500、存 500） |
| 公式结果列 / 小计列 | 派生值，存 / 显示**换算后 canonical 值**（这是"报价统一"目标本身） |
| 生效范围 | 所有"卡片取数"公式消费链路：卡片行公式、组件小计、跨组件 / 跨 Tab 引用、详情/只读视图公式列、Excel/核价 CARD_FORMULA / TAB_JOIN_FORMULA |
| 换算表维护 | **代码内硬编码**；前后端各一份小 Map，对拍测试守护一致 |
| 异常单位 | D 为空 / 不在预设表内 → **×1 原值透传**，不阻断报价，旁路 ⚠ 提示"未知单位" |
| 单位来源位置 | **同组件、同一行**的另一个字段 D（逐行读取） |
| 被换算列 C 类型 | 任意持有数值的列（INPUT_NUMBER / DATA_SOURCE / FORMULA 结果），但**不得同时是行键 / cross_tab 匹配键**（见 §8 约束） |

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
| g/KPCS（克/千片，斜杠两侧大小写不限） | kg/PCS | 0.000001（分子 g→kg ÷1000，分母 千片→片 ÷1000，整体 ÷1e6） |
| 空 / 不在表内 | —（不换算） | 1（原值透传） |

说明：
- canonical 目标由单位字符串自身决定，列上无需配目标单位——换算 = `rawValue × factor`。
- `kg/PCS` 档：分子 g→kg（÷1000），分母不变，整体系数 0.001。
- D 文本仅用于查表得 factor，**绝不拼进任何被 JEXL/ArithParser 解析的表达式串**。

## 4. 核心机制：物化点克隆换算视图（无伴生键）

**原则**：一列被配 `unit_source_field` 后，它的**公式 / 聚合面取值 = canonical**，**展示 / 落库面取值 = 原值（输入列）/ canonical（派生列）**。

实现：在每个"卡片行被物化去喂公式 / 聚合"的点（这些点都**持有 fields**，能定位哪列是 C、D 是谁），产出一份**克隆**行，把配换算的列 C 覆盖成 `rawC × factorFor(normalize(rawD))`；**绝不原地 mutate 原行**（原行还被明细渲染读，§8 约束）。下游按字段名读列即透明拿到 canonical，无需伴生键、无需逐消费者注入换算感知。

- 输入列 C：克隆视图里覆盖成 canonical 喂公式 / 聚合；原行 / 快照 / 渲染保留原值。
- 公式结果列：因公式输入在 computeRows / computeAllFormulas 已是 canonical，**结果天生 canonical**，存快照、显示、被 Excel 复用均一致。

**为什么不用伴生键**（v4 教训）：消费端 `CardAggregateSource.rowsFor`（`:39` 按原字段名 `row.get("工序")` 取值）、后端小计、cross_tab 前后端全按**字段名**读行，没有任何一个会读 `__uc__<C>`；伴生键还会作为新派生列键撞 AP-37/44 的"遍历跳过"检查点。覆盖原列值则下游字段名读取天然命中。

## 5. 生效范围实测依据 + 待排除路径

live DB（44 现役报价单）实测：现役模板 `报价模板0608` / `核价模板0603` 列全为 **CARD_FORMULA**（`SUM_OVER([来料#1], c0)` 卡片取数）+ **FORMULA**（`=[A]+[B]+[C]` 纯同行 col_key）；`costing_template`（老 BNF VARIABLE）连上现役报价单 = **0**。

**已知限制 / 实现前 grep 排除（评审已初核，无新增隐患，仍须实现期确认）**：
- BNF 视图直读列（`{path}` / VARIABLE）：现役不用，不换算。
- `SUM_OVER` 在 `CardAggregateSource` ThreadLocal 未 set 时回退 `dataLoader.loadByPath` 视图直读（`TemplateFormulaService` 约 :694-703）——走 BNF 直读、不经卡片行、属已知限制，前后一致。
- `DerivedAttributeCalculatorV5` / driver 展开：初判不在卡片物化前消费待换算列；实现时 `codegraph_callers` 确认一次。

## 6. 配置模型 + 共享换算工具

**字段配置**：字段（component.fields 内列定义）新增属性 `unit_source_field`，存**同组件内单位列 D 的字段 `name`**（钉死 name，与各引擎取值键 `fieldName(f)` / `f.name||f.key` 口径一致）。非空 ⇒ 启用换算。不新增 `field_type` 枚举。

**共享换算工具（轻量双副本 + 对拍）**：
- 前端 `cpq-frontend/src/utils/unitConversion.ts`、后端 `cpq-backend/.../UnitConversion.java` **各硬编码一份小 Map**，导出 `factorFor(unitText)`。沿用项目既有"前后端各一份 + 对拍"先例（`uniquifyRowKeys` / `bnfDriverLookupKey`），不引 JSON 文件 / classpath 读取。
- **跨端对拍测试（强制）**：同组输入断言两端 factor 逐档相等。

## 7. 换算物化点清单（实现检查清单）

**6 个克隆换算点（每个都持有 fields）**
1. 前端 `computeAllFormulas`：函数顶部 **克隆** `const convRow = {...row}` 后覆盖配换算列，再走既有逻辑。**绝不 mutate 入参**（`:2236` 把渲染用同一 `r.row` 传入，`fillFixedDefaults` `:1017` 无默认值时返回原引用 → 原地改会让明细格子显示 canonical，违反不变量）。一处覆盖渲染算值 + 前端小计 `computeTabSubtotalsByColumn` + 跨 Tab `buildCrossTabRows`（都经由它）。
2. 前端 `computeNonSubtotalColumnSums`（`:1080`）：`:1119` 直接 `row[colName]` 读 INPUT_NUMBER 原值、**绕过** computeAllFormulas，须单独在此对配换算列读 canonical（评审 A3 补漏）。
3. 后端 `FormulaCalculator#computeRows`：`collectFieldValues`（约 :608）之前覆盖 `mergedRow` 配换算列（克隆 / 改 JsonNode 值）。用 **BigDecimal** 解析 C（可能是 TextNode）再乘再包回 DecimalNode，**禁 double**。同源派生的 `currentRowRaw`（:619）一并吃到。只改单元格值、不重建行（守 AP-51）。
4. 后端 `ExcelViewService#parseEffectiveRows`（`:271-280`，**持有 `componentsSnapshot`**）：构造 effectiveRows 时把配换算的输入列覆盖成 canonical，Excel `SUM_OVER` / `CardAggregateSource.rowsFor` 透明拿到（评审 A2：此处确有 fields，是 Excel 侧的单点）。
5. 后端小计 `backfillSubtotalsFromResolved`（`:1680`，签名首参即 `JsonNode fields`）：聚合**输入列**时对配换算列读 canonical（公式结果列已 canonical）。注意现状 `resolved` 同时落库 + 喂小计是同一对象——落库存原值、小计读 canonical，**只在求和那一刻按 fields 换算**，不另造数组、不改落库行。
6. cross_tab 源行：前后端同规则——`crossTabRows` 源行的配换算输入列在喂入前覆盖成 canonical（后端 `targetRowValue` `:393` 按字段名注入、前端 `formulaEngine.ts:371` 按字段名读），保证 cross_tab_ref 引用该列拿 canonical，前后端口径一致（守 ≤0.01）。

**元数据传播（实现前 `codegraph_impact unit_source_field` 按真实调用边列全 —— 评审 A6）**
7. 组件 fields(JSONB) 持久化新属性。
8. 管理端 `FieldConfigTable`：新增"单位换算来源"Select，选项 = 同组件内可作单位的字段，写入 `unit_source_field`（value = 字段 name）。
9. 快照**写入**侧 `TemplateService#refreshSnapshotsByComponent` 整体搬 fields 数组，新属性自动随带、无需改（评审 B3）。要改的是上述物化点 + 前端 `ComponentField` 类型 + `enrichComponentData` 透传 + 详情页 `useCardSnapshots.resolveDataSource` + `ReadonlyProductCard.buildFormulaCache`（AP-50）。
10. **缓存失效**：`unit_source_field` 必须纳入 `driverExpansionKey` / `fieldsOverrideHash`（约 `QuotationStep2.tsx:1160`）的 fields hash 维度——否则改配置后旧缓存不失效（AP-37 经典坑，评审 A6）。
11. 渲染层（明细输入列展示）不动——读原值。

## 8. 不变量与约束

**不变量**
- 明细输入列展示 / 落库 / 快照 = 原值；公式结果列 / 小计列 = 换算后 canonical。
- 换算只在**克隆视图**上改单元格数值，**绝不 mutate 原行**、绝不增删行 / 改 `rowCount`（守 AP-51 + 防明细显示污染）。
- 前后端换算逻辑等价（双副本 + 对拍）⇒ 前后端一致性校验（差值 ≤0.01）仍成立。
- 报价单 + 核价单 + 详情视图 + Excel/核价卡片取数公式统一覆盖（同口径）。
- BNF 视图直读列不换算（已知限制）。

**约束**
- 被配 `unit_source_field` 的列**不得同时是行键 / cross_tab 匹配键**（评审 A4-r3）：`currentRowRaw` 同时是 cross_tab_ref 匹配键取值源，换算会改掉匹配键导致失配。一般计量数值列不当键，需在 `FieldConfigTable` 校验或文档显式禁止。
- 后端换算一律 BigDecimal，禁 double。

## 9. 小计与公式结果列口径

- 公式结果列、小计列是派生值，存 / 显示换算后 canonical；卡片小计行**标注 canonical 单位**（如"小计 1.5 KG"），避免与同卡明细原值口径混淆。
- 输入列 C 的直接求和（小计 / `computeNonSubtotalColumnSums`）：在求和点对配换算列按 fields 读 canonical → `sum(canonical)`，不再 `sum(原值)`。
- 明细输入列本身仍存 / 显示原值（守 §8 不变量）。

## 10. 异常单位处理

- D 为空 / 不在预设表 → factor=1 原值透传，不阻断报价。
- 走现有 `out.errors` / `outDiag` 旁路透出"未知单位"⚠。现状只能到**列级**粒度（按字段名键，到不了"第 i 行"，评审 B3）——先列级提示，行级另起。

## 11. 验收用例（最小集）

- 同列三行单位不同（g / KG / 吨）→ 各行公式按各自系数换算；明细输入列展示仍各自原值（验证未被 mutate 污染）。
- D 空 / D="mm" → 该行 ×1 透传 + ⚠ 提示，公式用原值。
- KPCS → ×1000 进 PCS 量纲；g/PCS → ÷1000 进 kg/PCS 量纲。
- 组件小计 / `computeNonSubtotalColumnSums` 直接求和该输入列 → canonical 汇总；小计标注 canonical 单位。
- **C 是 FORMULA 且公式内部消费另一待换算列**（如 C = `[重量列] × [单价]`、重量列配 g→KG）→ 卡片端结果 = Excel `SUM_OVER` 端结果（口径一致 ≤0.01）。
- 跨 Tab cross_tab_ref 引用该列 → 拿 canonical，前后端一致。
- 前后端一致性校验通过；E2E 刷新 3 次行数稳定（未累加）；明细输入列三次刷新仍显示原值。

**自检 / 测试**
- 后端单测：换算表全档 + `FormulaCalculator` / `parseEffectiveRows` / `backfillSubtotalsFromResolved` 逐行换算 + 跨端对拍。
- 前端单测：换算表全档 + computeAllFormulas（克隆不 mutate）+ computeNonSubtotalColumnSums 行级换算。
- 协议级改动 ⇒ Playwright `quotation-flow.spec.ts`：换算行公式值正确、明细原值未污染、`'加载中' final count = 0`、8 Tab 正常；复测报价单 + 核价单 + 详情页 + Excel 视图四处。
- TS `tsc --noEmit` 0 错；改动 `.tsx` 走 Vite 200；后端 touch 重启 + health 200；Flyway（若有）success=t。

## 12. 开发流程

- 隔离 worktree 分支（`superpowers:using-git-worktrees`）。
- 默认 `superpowers:subagent-driven-development` 推进。
- 完成后用户确认 → 自动合并 master + E2E + 清理 worktree。
