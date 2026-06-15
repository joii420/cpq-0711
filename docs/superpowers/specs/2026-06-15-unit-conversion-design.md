# 单位换算（Unit Conversion）设计 v3 — 2026-06-15

> 版本演进：
> - v1：卡片单引擎"行视图换算"。
> - v2：采纳架构评审 + 决定覆盖 Excel/核价（曾设想 ②a 全栈单位体系 / ②b 视图 SQL 归一）。
> - **v3（本版）**：经 live DB 实测（44 现役报价单 + 现役模板 报价模板0608 / 核价模板0603）确认——**现役 Excel/核价模板 100% 卡片取数（CARD_FORMULA / TAB_JOIN_FORMULA），BNF 视图直读（VARIABLE）已是历史遗留、未绑定任何现役报价单**。因此**砍掉 ②a/②b（视图 SQL 归一 / 单位字典 / 全栈改造）**，范围收敛为**卡片层换算即全覆盖**。保留两轮评审的全部硬修正。

## 1. 背景与目标

报价 / 核价的公式计算中，重量与计数列的原始录入单位不统一（g / KG / 吨 / 片 / KPCS / g·PCS⁻¹ 等），导致同一条公式在不同行、不同料件下混入不同量纲，报价结果不可比。

**目标**：在公式计算环节把这些列**逐行归一**到统一单位（重量→KG，计数→PCS，比值→kg·PCS⁻¹），使所有公式计算结果（卡片、快照、Excel/核价的卡片取数连表公式）单位一致，达到报价统一。

**非目标**：
- 不改变界面展示的**明细列**值、不改变其落库 / 快照存储值——一律保留**原值**（小计列例外，见 §9）。
- 不做可维护的单位配置表 / 管理页（预设表硬编码；前后端共享一份只读 JSON 常量不属于"可维护配置页"，见 §6）。
- 不支持跨组件取单位、不支持列级固定单位字面量。
- **不支持 BNF 视图直读列（`{path}` / VARIABLE）的换算**——现役模板不用此类列（实测见 §5），列为已知限制。

## 2. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 明细列换算后可见性 | 界面、落库 / 快照都显示**原值**；换算只是喂给公式的临时值 |
| 生效范围 | **所有"卡片取数"的公式消费链路**：卡片行公式、组件小计、跨组件 / 跨 Tab 引用、详情/只读视图公式列、Excel/核价 CARD_FORMULA / TAB_JOIN_FORMULA。纯展示明细列 = 原值 |
| 换算表维护 | **代码内硬编码**一套固定预设（实现为前后端各持一份、由对拍测试守护一致） |
| 异常单位 | D 为空 / 不在预设表内 → **×1 原值透传**，不阻断报价，但走旁路 ⚠ 提示"未知单位" |
| 单位来源位置 | **同组件、同一行**的另一个字段 D（逐行读取） |
| 被换算列 C 类型 | **任意持有数值的列**（INPUT_NUMBER / DATA_SOURCE / FORMULA 结果均可） |

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
- canonical 归一目标由单位字符串自身决定，**列上无需配置目标单位**——换算 = `rawValue × factor`，factor 已编码全部信息。
- `kg/PCS` 档：分子 g→kg（÷1000），分母 PCS 不变，整体系数 0.001。
- 归一化把 `g/PCS`、`G/PCS`、`g/pcs` 统一识别为同一档。
- D 文本仅用于查表得 factor，**绝不拼进任何被 JEXL/ArithParser 解析的表达式串**。

## 4. 实现机制（统一在"卡片行物化"边界换算）

凡是"卡片行被物化去喂公式"的地方，产出一份**换算后的行视图**：

```
formulaRow[c] = rawRow[c] × factorFor(normalize(rawRow[unitSourceField(c)]))   // 对每个配了换算的列 c
formulaRow[其它列] = rawRow[其它列]                                              // 原样
```

之后所有公式 token 读 `formulaRow`；展示 / 落库读 `rawRow`。换算逻辑只在共享工具（§6）里实现一次，在每个物化边界调用。

**为什么这样**：每个引擎/物化点只有一个插入点；"展示原值"语义天然成立；卡片、快照、Excel/核价只要都从换算后的卡片行取数即统一覆盖。否决备选：路线 B（token 替换处逐个乘系数，分散易漏）、路线 C（生成隐藏 canonical 列改写公式引用，侵入公式编写）。

## 5. 生效范围实测依据（为什么卡片层即全覆盖）

live DB（44 现役报价单）实测：
- 现役模板 `报价模板0608`(2026-06-15) / `核价模板0603`(2026-06)：列全为 **CARD_FORMULA**（`SUM_OVER([来料#1], c0)` 卡片页签取数）+ **FORMULA**（`=[A]+[B]+[C]` 纯同行 col_key 引用，无点号视图读）。
- `costing_template`（老 BNF VARIABLE 121 列）能连上现役报价单模板的 = **0**：核价比对 `CostingSheetService.buildComparison` 的 BNF 路径对所有现役报价单**已死**。
- BNF VARIABLE 直读列仅存在于 5月老模板（西门子 / 报价单模板0601），未绑定现役报价单。

结论：**类别"视图直读"在现役配置里为空**。CARD_FORMULA / TAB_JOIN_FORMULA 经 `CardDataProvider` 取卡片有效行（`driverRow ∪ basicDataValues ∪ formulaResults ∪ editRows`），**C 与同行 D 都在**——卡片行物化处换算即覆盖。BNF 视图直读列（`{path}` / VARIABLE）= **已知限制，不换算**。

## 6. 配置模型 + 共享换算工具

**字段配置**：字段（component.fields 内列定义）新增属性 `unit_source_field`，存**同组件内单位列 D 的字段 `name`**（钉死 name，与各引擎取值键 `fieldName(f)` / `f.name || f.key` 口径一致）。非空 ⇒ 启用换算；空 ⇒ 现状行为。不新增 `field_type` 枚举。

**共享换算工具（双副本 + 对拍守护）**：
- 一份预设数据 `unit_conversion_presets.json`（归一化别名 + canonical + factor），**前后端各持一份副本**（两工程根隔离，无 monorepo 共享包；前端 `tsconfig include` 仅 `src`、读不到后端 resources，故不能单一物理文件共享）。
- 前端 `cpq-frontend/src/utils/unitConversion.ts` import 前端副本，导出 `factorFor(unitText): number`。
- 后端 `cpq-backend/.../UnitConversion.java` 从 classpath 读后端副本，`factorFor(String): BigDecimal`。
- **跨端对拍测试（强制 CI 关卡）**：同一组输入断言两端 factor 逐档相等——这是双副本唯一的漂移护栏。
- 副本同步：构建/CI 步骤校验两份内容一致（脚本对比），不一致则失败。

## 7. 插入点清单（实现检查清单）

**换算行视图插入点**
1. 后端 `FormulaCalculator#computeRows`：在 `collectFieldValues`（约 :608-609）**之前**对 `mergedRow` 整体换算——让下游 `fieldValues`（:609）与 `currentRowRaw`（:619，由同一 `mergedRow` 派生）都吃到换算值。D 文本须从 `mergedRow` 读（`mergedRow` 保 String；`collectFieldValues` 才 `toNumber`，`toNumber("KG")=null` 会丢单位）。只改单元格值、不重建行数组（守 AP-51）。**`FormulaCalculationService` 是死代码（仅测试调用），不动。**
2. 前端 `QuotationStep2.tsx#computeAllFormulas`：在 fieldValues 收集循环（约 :477-556）**之前**换 `row`（D 文本此时还在 row 里）。
3. 前端 `buildCrossTabRows`（约 :859）+ 后端组装传给 `FormulaCalculator` 的 `crossTabRows` 入参：都用换算后的行（跨 Tab 引用前后端一致）。
4. 前端 `ReadonlyProductCard.buildFormulaCache`（约 :74）：详情/只读视图公式列换算（守 AP-50，与 computeAllFormulas 同源，建议抽共享转换函数两处共用）。
5. Excel/核价：`CardFormulaEvaluator`（CARD_FORMULA）/ `TabJoinPlanEvaluator`（TAB_JOIN_FORMULA）消费的 `CardDataProvider` 卡片行换算（构造点约 ExcelViewService.java:338-353）。
   - **A4 补丁**：`CardDataProvider` 是纯数据容器，不含 fields 定义。必须把 per-tab 的 `componentsSnapshot` fields（含 `unit_source_field`）喂进换算器，才能定位哪列是 C、哪字段是 D。这是本插入点的必要新增通道。
   - 核价比对 `CostingSheetService.buildComparison`（BNF VARIABLE 路径）：**现役未绑定，不在本期范围**（§5 已知限制）。

**元数据传播（让 `unit_source_field` 一路到达引擎；grep 全工程 `unit_source_field` 对账）**
6. 组件 fields(JSONB) 持久化新属性。
7. 管理端 `FieldConfigTable`：新增"单位换算来源"Select，选项 = 同组件内可作单位的字段，写入 `unit_source_field`（value = 字段 name）。
8. 快照**写入**侧：`TemplateService#refreshSnapshotsByComponent` 整体搬运 field 对象数组（`entry.put("fields", effectiveFields)`），新属性**随之自动携带、无需改动**（B3 修正：不必特意改写入侧，也不触发 AP-40 firstResult 污染）。真正要改的是**消费/读取**侧：
   - 后端 `FormulaCalculator` 加 `unitSourceField(f)` helper，`collectFieldValues` 据此触发换算（插入点 1）。
   - 后端 `CardSnapshotService#assembleTabsWithFormulaResults` / `ExcelViewService` 把 fields 传到 CardDataProvider 换算器（插入点 5 / A4）。
9. 前端 `ComponentField` 类型定义（component/types.ts）加字段；`enrichComponentData` / `buildComponentDataFromStructure` 透传到 `comp.fields`。
10. 渲染层（明细列展示）**不动**——展示原值。

## 8. 不变量与验收

**不变量**
- 明细列展示 / 落库 / 快照 = 原值；公式消费（含 Excel/核价卡片取数连表公式）= 换算值。
- 前后端换算逻辑等价（双副本 + 对拍测试）⇒ 前后端一致性校验（差值 ≤0.01）仍成立。
- 报价单 + 核价单 + 详情视图 + Excel/核价卡片取数公式统一覆盖。
- 换算只改单元格**数值**，绝不增删行 / 改 `rowCount`（守 AP-51；在 `mergedRow` 上做值替换，不重建行数组）。
- BNF 视图直读列（`{path}` / VARIABLE）不换算（已知限制）。

**验收用例（最小集）**
- 同列三行单位不同（g / KG / 吨）→ 各行公式按各自系数换算；明细列展示仍各自原值。
- D 空 / D="mm" → 该行 ×1 透传 + ⚠"未知单位"提示，公式用原值。
- KPCS 行 → ×1000 进 PCS 量纲公式；g/PCS 行 → ÷1000 进 kg/PCS 量纲公式。
- 组件小计 / 跨 Tab 引用该列 → 用换算值汇总；小计展示为 canonical 单位（带标注）。
- Excel/核价 CARD_FORMULA `SUM_OVER([页签], c)` 聚合该列 → 对换算值求和，与卡片公式口径一致。
- 前后端一致性校验通过；E2E 刷新 3 次行数稳定（未累加）。

**自检 / 测试**
- 后端单测：换算表全档 + `FormulaCalculator` 逐行换算 + 跨端对拍测试（强制）。
- 前端单测：换算表全档 + computeAllFormulas / ReadonlyProductCard 行级换算。
- 协议级改动 ⇒ 跑 Playwright `quotation-flow.spec.ts`，确认换算行公式值正确、`'加载中' final count = 0`、8 Tab 正常；复测报价单 + 核价单 + 详情页 + Excel 视图四处。
- TS `tsc --noEmit` 0 错；改动 `.tsx` 走 Vite 200；后端 touch 重启 + health 200；Flyway（若有）success=t。

## 9. 小计列口径

- 小计属公式域 ⇒ 显示**换算后 canonical 之和**，卡片小计行**标注 canonical 单位**（如"小计 1.5 KG"），避免与同卡明细原值口径混淆。
- "落库 = 原值"不变量**豁免小计列**：小计列落库 / 落快照存**换算值**；其余明细列存原值。
- **B4 修正（关键，防静默偏差）**：`snapshot_rows`（明细持久化行）存 C **原值**，小计**不能** `sum(snapshot_rows 的 C)`（那是原值之和）。小计求和的输入必须是**换算后行视图**——它与 snapshot_rows 原值行是**两个不同中间产物**，实现时勿混用同一 `resolvedRows` 数组（参见 `backfillSubtotalsFromResolved` / `computeTabSubtotalsByColumn`）。

## 10. 异常单位处理

- D 为空 / 不在预设表 → factor=1 原值透传，**不阻断报价**。
- 同时走现有 `out.errors` / `outDiag` 旁路（computeAllFormulas 已有 `out?.errors`，约 QuotationStep2.tsx:404）透出"未知单位"⚠ 标记到渲染层（需确认渲染层能按行/列定位展示），便于销售/核价发现配错。

## 11. 开发流程

- 隔离 worktree 分支（`superpowers:using-git-worktrees`）。
- 默认 `superpowers:subagent-driven-development` 推进。
- 完成后用户确认 → 自动合并 master + E2E + 清理 worktree。
