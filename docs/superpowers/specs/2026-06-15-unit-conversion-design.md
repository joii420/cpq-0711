# 单位换算（Unit Conversion）设计 — 2026-06-15

## 1. 背景与目标

报价 / 核价的公式计算中，重量与计数列的原始录入单位不统一（g / KG / 吨 / 片 / KPCS / g·PCS⁻¹ 等），导致同一条公式在不同行、不同料件下混入不同量纲，报价结果不可比。

**目标**：在公式计算环节把这些列**逐行归一**到统一单位（重量→KG，计数→PCS，比值→kg·PCS⁻¹），使所有公式计算结果保持单位一致，达到报价统一。

**非目标**：
- 不改变界面展示值、不改变落库 / 快照存储值、不改变 Excel 模板对原始列的直接引用值——这些一律保留**原值**。
- 不做可维护的单位配置表 / 管理页（预设表硬编码，新增单位需改代码）。
- 不支持跨组件取单位、不支持列级固定单位字面量（本期只做"同组件同行的单位来源字段"一种模式）。

## 2. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 换算后可见性 | 界面、落库 / 快照都显示**原值**；换算只是喂给公式的临时值 |
| 生效范围 | **只要是公式消费 C 的地方**（同组件行公式、组件小计、跨组件 / 跨 Tab 引用）都用换算值；纯展示与 Excel 直接引用原列 = 原值 |
| 换算表维护 | **代码内硬编码**一套固定预设 |
| 异常单位 | D 为空 / 不在预设表内 → **×1 原值透传**，不报错、不阻断报价 |
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
- 大小写处理需在归一化时把 `g/PCS`、`G/PCS`、`g/pcs` 等统一识别为同一档。

## 4. 实现路线（路线 A：公式入口处构造换算后行视图）

在每个公式引擎进入逐行求值前，对**原始行 Map** 做一次转换：

```
convertedRow[c] = raw[c] × factorFor(normalize(raw[unitSourceField(c)]))   // 对每个带换算配置的列 c
convertedRow[其它列] = raw[其它列]                                          // 原样
```

之后**所有**公式 token 读取都走 `convertedRow`；展示 / 落库读原始行。

**为什么是路线 A**：每个引擎只有一个插入点；"展示原值"语义天然成立（只换公式视图）；跨组件 / 小计只要都消费 convertedRow 即统一覆盖。否决的备选：
- 路线 B（token 替换处逐个乘系数）——跨组件引用读别组件原始行时拿不到该组件列元数据，小计 / 跨组件要分散改多处，易漏。
- 路线 C（生成隐藏 canonical 列 + 改写公式引用）——侵入公式编写，否决。

## 5. 配置模型

字段（component.fields 内的列定义）新增一个属性：

- `unit_source_field`：字符串，存**同组件内单位列 D** 的 `name`/`key`。
- 非空 ⇒ 该列启用换算；空 ⇒ 不换算（现状行为）。

不新增 `field_type` 枚举——这是既有字段上的一个新 config 属性，因此**不触发 AP-44 全部 17 检查点**，只触发其中的"config 属性传播"子集（见 §7）。

## 6. 共享换算工具（单一事实源，两端逐字对齐）

- 前端 `cpq-frontend/src/utils/unitConversion.ts`：`factorFor(unitText: string): number`，含归一化 + 硬编码预设表 + 未知/空 → 1.0。
- 后端 `cpq-backend/.../UnitConversion.java`：等价 `factorFor(String unitText): BigDecimal`。
- 两端各配单测，逐档断言系数相等，防止前后端漂移导致一致性校验（≤0.01）失败。

## 7. 元数据传播链 + 插入点（实现检查清单）

**插入点（路线 A 的"行视图转换器"）**
1. 前端 `QuotationStep2.tsx#computeAllFormulas` 入口：按 `comp.fields` 中带 `unit_source_field` 的列构造 `convertedRow` 再求值。
2. 前端 `buildCrossTabRows` 产出的跨 Tab 行：同样过转换（保证跨组件引用消费换算值）。
3. 后端引擎一 `FormulaCalculationService#calculateRowFormulas`：对 `rowData` 先转换再 `buildJexlExpression`。
4. 后端引擎二 `FormulaCalculator#computeRows` / `targetRowValue`：对 `currentRowRaw` / `arow` 先转换。
   - ⚠ **必须在 `toNumber` 抽数之前转换**——D 是文本，抽数阶段会被丢弃；转换器要读原始 Object 行里的文本单位。

**元数据传播（让 `unit_source_field` 一路到达引擎）**
5. 组件 fields(JSONB) 持久化新属性。
6. 管理端 `FieldConfigTable`：新增"单位换算来源"Select，选项 = 同组件内可作单位的字段；写入 `unit_source_field`。
7. snapshot columns JSON：`TemplateService#refreshSnapshotsByComponent` / snapshot 构建要带上该属性。
8. 前端 enrich / normalize：把 `unit_source_field` 带到 `comp.fields`（与现有字段属性同路径）。
9. 渲染层**不动**（展示原值）。

## 8. 不变量与验收

**不变量**
- 展示 / 落库 / 快照 / Excel 直接引用原列 = 原值；仅公式消费 = 换算值。
- 前后端换算逻辑等价 ⇒ 一致性校验（前后端差值 ≤0.01）仍成立。
- 报价单 + 核价单共用这些引擎，自动同时覆盖；详情 / 只读视图展示原值，不受影响。

**验收用例（最小集）**
- 同列三行单位不同（g / KG / 吨）→ 各行公式按各自系数换算；展示仍是各自原值。
- D 空 / D="mm" → 该行 ×1 透传，公式用原值。
- KPCS 行 → ×1000 进 PCS 量纲公式。
- g/PCS 行 → ÷1000 进 kg/PCS 量纲公式。
- 组件小计 / 跨 Tab 引用该列 → 用换算值汇总；小计展示为 canonical 单位。
- 前后端一致性校验通过。

**自检 / 测试**
- 后端单测：换算表全档 + 两个引擎逐行换算。
- 前端单测：换算表全档 + computeAllFormulas 行级换算。
- 协议级改动 ⇒ 跑 Playwright `quotation-flow.spec.ts`，确认换算行公式值正确、`'加载中' final count = 0`、8 Tab 正常。
- TS `tsc --noEmit` 0 错；改动 `.tsx` 走 Vite 200；后端 touch 重启 + health 200。

## 9. 开发流程

- 隔离 worktree 分支（`superpowers:using-git-worktrees`）。
- 默认 `superpowers:subagent-driven-development` 推进。
- 完成后用户确认 → 自动合并 master + E2E + 清理 worktree。
