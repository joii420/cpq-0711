# 单位换算（Unit Conversion）设计 v2 — 2026-06-15

> v2 在 v1 基础上：采纳架构评审（修正插入点、补全传播链），并按"覆盖 Excel/核价"决策（选项 B）扩大生效范围。v1 的"路线 A 行视图换算"思路保留并推广到所有"卡片行物化"边界。

## 1. 背景与目标

报价 / 核价的公式计算中，重量与计数列的原始录入单位不统一（g / KG / 吨 / 片 / KPCS / g·PCS⁻¹ 等），导致同一条公式在不同行、不同料件下混入不同量纲，报价结果不可比。

**目标**：在公式计算环节把这些列**逐行归一**到统一单位（重量→KG，计数→PCS，比值→kg·PCS⁻¹），使所有公式计算结果（含卡片、快照、Excel/核价连表公式）单位一致，达到报价统一。

**非目标**：
- 不改变界面展示值、不改变落库 / 快照存储的**明细列**值——这些一律保留**原值**（小计列例外，见 §9）。
- 不做可维护的单位配置表 / 管理页（预设表硬编码；"内部共享一份只读 JSON 常量"不属于"可维护配置页"，见 §6）。
- 不支持跨组件取单位、不支持列级固定单位字面量（本期只做"同组件同行的单位来源字段"一种模式）。
- **不支持裸 `{path}` 视图直读列的换算**（见 §5 类别 2，列为已知限制）。

## 2. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 明细列换算后可见性 | 界面、落库 / 快照都显示**原值**；换算只是喂给公式的临时值 |
| 生效范围 | **覆盖所有公式消费 C 的链路**：卡片行公式、组件小计、跨组件 / 跨 Tab 引用、详情/只读视图公式列、**Excel/核价 `[页签.字段]` 连表公式**。纯展示明细列与裸 `{path}` 视图直读 = 原值/不换算 |
| 换算表维护 | **代码内硬编码**一套固定预设（实现为前后端共享的单一 JSON 常量） |
| 异常单位 | D 为空 / 不在预设表内 → **×1 原值透传**，不阻断报价，但走旁路 ⚠ 提示"未知单位" |
| 单位来源位置 | **同组件、同一行**的另一个字段 D（逐行读取） |
| 被换算列 C 类型 | **任意持有数值的列**（INPUT_NUMBER / DATA_SOURCE / FORMULA 结果均可）——在公式消费的瞬间换算 |

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
- canonical 归一目标由单位字符串自身决定，**列上无需配置目标单位**——换算就是 `rawValue × factor`，factor 已编码全部信息。
- `kg/PCS` 这一档：分子 g→kg（÷1000），分母 PCS 不变，整体系数 0.001。
- 归一化需把 `g/PCS`、`G/PCS`、`g/pcs` 等统一识别为同一档。
- D 文本仅用于查表得 factor，**绝不拼进任何被 JEXL/ArithParser 解析的表达式串**（避免 `/`、`·`、`⁻¹` 触发解析错误）。

## 4. 实现机制（统一在"卡片行物化"边界换算）

核心：凡是"卡片行被物化去喂公式"的地方，产出一份**换算后的行视图**：

```
formulaRow[c] = rawRow[c] × factorFor(normalize(rawRow[unitSourceField(c)]))   // 对每个带换算配置的列 c
formulaRow[其它列] = rawRow[其它列]                                              // 原样
```

之后**所有**公式 token 读取走 `formulaRow`；展示 / 落库读 `rawRow`。换算逻辑只在共享工具（§6）里实现一次，在每个物化边界调用。

**为什么这样**：每个引擎/物化点只有一个插入点；"展示原值"语义天然成立（只换公式视图）；卡片、快照、Excel/核价只要都从换算后的卡片行取数即统一覆盖。否决的备选：
- 路线 B（token 替换处逐个乘系数）——跨组件 / Excel 引用拿不到对应列元数据与同行 D，分散易漏。
- 路线 C（生成隐藏 canonical 列 + 改写公式引用）——侵入公式编写，否决。

## 5. 生效范围的两类列（决定可行性）

Excel/核价模板的公式是**页签连表公式**，列引用形如 `[页签.字段]`，分两类：

**类别 1 — 引用卡片字段（本期覆盖）✅**
`TAB_JOIN_FORMULA` / `CARD_FORMULA` 经 `CardDataProvider`（卡片有效行 / 持久化 componentData）取数，C 与同行 D 都在。`COMPONENT_FIELD` 纯展示列读 `componentRowData`（摊平行）= 原值，**不换**（属展示）。
- 注意：`ExcelViewService.buildRowData` 摊平时每组件只取第 0 行（`rowDataList.get(0)`，ExcelViewService.java:319-325）；多行 driver 组件在 Excel 这层取首行的 D。

**类别 2 — 裸 `{path}` / VARIABLE 视图直读（不支持）❌**
经 `DataLoader.loadByPath` 直接查 V6 视图（FormulaEngine.java:150-170 / TemplateFormulaService.resolveColKeyFallback:942），无卡片上下文、无同行 D 字段，不是"配了 `unit_source_field` 的卡片字段"。**列为已知限制**；现有模板用类别 1，不受影响。

## 6. 配置模型 + 共享换算工具

**字段配置**：字段（component.fields 内列定义）新增属性 `unit_source_field`，存**同组件内单位列 D 的字段 `name`**（钉死用 name，与各引擎取值键 `fieldName(f)` / `f.name || f.key` 口径一致）。非空 ⇒ 启用换算；空 ⇒ 现状行为。不新增 `field_type` 枚举。

**共享换算工具（单一事实源，根除前后端漂移）**：
- 单一只读资源 `unit_conversion_presets.json`（归一化别名 + canonical + factor）。
- 前端 `cpq-frontend/src/utils/unitConversion.ts` import 该 JSON，导出 `factorFor(unitText): number`。
- 后端 `cpq-backend/.../UnitConversion.java` 从 classpath 读同一 JSON，`factorFor(String): BigDecimal`。
- **跨端对拍测试**：同一组输入断言两端 factor 相等（不仅各自单测）。

## 7. 插入点清单（实现检查清单）

**换算行视图插入点**
1. 后端 `FormulaCalculator#computeRows`：在 `collectFieldValues` **之前**对 `mergedRow` 整体换算（FormulaCalculator.java:608-619）——让下游 `fieldValues`（:609）与 `currentRowRaw`（:619）都吃到换算值。D 文本须从 `mergedRow` 读（`toNumber("KG")=null`，抽数阶段会丢）。**`FormulaCalculationService` 是死代码（仅测试调用），不动。**
2. 前端 `QuotationStep2.tsx#computeAllFormulas`：在 fieldValues 收集循环（:477-556）**之前**换 `row`（D 文本此时还在 row 里）。
3. 前端 `buildCrossTabRows`（QuotationStep2.tsx:859）+ 后端组装传给 `FormulaCalculator` 的 `crossTabRows` 入参：都用换算后的行（跨 Tab 引用前后端一致）。
4. 前端 `ReadonlyProductCard.buildFormulaCache`（ReadonlyProductCard.tsx:74）：详情/只读视图公式列换算（守 AP-50，与 computeAllFormulas 同源，建议抽共享转换函数两处共用）。
5. Excel/核价：`TabJoinPlanEvaluator` / `CardFormulaEvaluator` 消费的 `CardDataProvider` 卡片行换算（ExcelViewService.java:338-353 处构造点）；核价比对 `CostingSheetService.buildComparison`（CostingSheetService.java:68）按同款卡片行换算。

**元数据传播（让 `unit_source_field` 一路到达引擎；grep 全工程 `unit_source_field` 对账）**
6. 组件 fields(JSONB) 持久化新属性。
7. 管理端 `FieldConfigTable`：新增"单位换算来源"Select，选项 = 同组件内可作单位的字段，写入 `unit_source_field`（value = 字段 name）。
8. 两条快照写入路径都带上该属性：`TemplateService#refreshSnapshotsByComponent` **和** `CardSnapshotService#assembleTabsWithFormulaResults`（注意 AP-40：同 cid 多 tc 实例按 sortOrder 精确匹配，勿 firstResult 污染）。
9. 前端 `ComponentField` 类型定义（component/types.ts）加字段；`enrichComponentData` / `buildComponentDataFromStructure` 透传到 `comp.fields`。
10. 后端 `FormulaCalculator` 字段属性 helper 旁加 `unitSourceField(f)`，`collectFieldValues` 据此触发换算。
11. 渲染层（明细列展示）**不动**——展示原值。

## 8. 不变量与验收

**不变量**
- 明细列展示 / 落库 / 快照 / Excel 展示列 = 原值；公式消费（含 Excel/核价连表公式）= 换算值。
- 前后端换算逻辑等价（共享 JSON）⇒ 前后端一致性校验（差值 ≤0.01）仍成立。
- 报价单 + 核价单 + 详情视图 + Excel/核价 连表公式统一覆盖。
- 换算只改单元格**数值**，绝不增删行 / 改 `rowCount`（守 AP-51；务必在 `mergedRow` 上做值替换，不重建行数组）。
- 类别 2 裸 `{path}` 列不换算（已知限制）。

**验收用例（最小集）**
- 同列三行单位不同（g / KG / 吨）→ 各行公式按各自系数换算；明细列展示仍各自原值。
- D 空 / D="mm" → 该行 ×1 透传 + ⚠"未知单位"提示，公式用原值。
- KPCS 行 → ×1000 进 PCS 量纲公式。
- g/PCS 行 → ÷1000 进 kg/PCS 量纲公式。
- 组件小计 / 跨 Tab 引用该列 → 用换算值汇总；小计展示为 canonical 单位（带标注）。
- Excel/核价 `[页签.C]` 连表公式 → 取换算值，与卡片公式口径一致；核价比对不再因单位口径标红。
- 前后端一致性校验通过；E2E 刷新 3 次行数稳定（未累加）。

**自检 / 测试**
- 后端单测：换算表全档 + `FormulaCalculator` 逐行换算 + 跨端对拍测试。
- 前端单测：换算表全档 + computeAllFormulas / ReadonlyProductCard 行级换算。
- 协议级改动 ⇒ 跑 Playwright `quotation-flow.spec.ts`，确认换算行公式值正确、`'加载中' final count = 0`、8 Tab 正常；并复测报价单 + 核价单 + 详情页 + Excel 视图四处。
- TS `tsc --noEmit` 0 错；改动 `.tsx` 走 Vite 200；后端 touch 重启 + health 200；Flyway（若有）success=t。

## 9. 小计列口径（A6 决策）

- 小计属公式域 ⇒ 显示**换算后 canonical 之和**。
- 卡片小计行**标注 canonical 单位**（如"小计 1.5 KG"），避免与同卡明细原值口径混淆。
- "落库 = 原值"不变量**豁免小计列**：小计列落库 / 落快照存的是**换算值**（`backfillSubtotalsFromResolved` 等）。其余明细列仍存原值。

## 10. 异常单位处理（B3 决策）

- D 为空 / 不在预设表 → factor=1 原值透传，**不阻断报价**。
- 同时走现有 `out.errors` / `outDiag` 旁路（computeAllFormulas 已有 `out?.errors`，QuotationStep2.tsx:404）透出"未知单位"⚠ 标记到渲染层，便于销售/核价发现配错。

## 11. 开发流程

- 隔离 worktree 分支（`superpowers:using-git-worktrees`）。
- 默认 `superpowers:subagent-driven-development` 推进。
- 完成后用户确认 → 自动合并 master + E2E + 清理 worktree。
