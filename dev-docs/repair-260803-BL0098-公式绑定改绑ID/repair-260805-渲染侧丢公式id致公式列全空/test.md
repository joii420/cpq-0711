# repair-0805 · 测试用例

> 配套 `需求文档.md`。夹具一律取**线上真实单据** `QT-20260804-0068`，不手搓玩具数据
> —— 本缺陷的两个根因都是「真实配置形状 vs 类型声明形状」的错配，玩具夹具会一起写错、测不出来。

---

## 0. 夹具准备（一次性，落进仓库）

| 夹具 | 来源 | 用途 |
|---|---|---|
| `structure-COMP0185.json` | `GET /api/cpq/quotations/be95ded9-…` → `quoteCardStructure`，取 `tabs[tabName='物料']` | 结构快照路径（含 16 条公式的 `id`、11 个字段的 `formulaId`） |
| `cardvalues-COMP0185.json` | `quotation_line_item.quote_card_values` → `tabs[tabName='物料']` | `baseRows`（含 `__nodeId` / `driverRow._料件`）+ `formulaResults`（后端权威值） |

导出命令见 `问题分析报告.md §8`。

---

## 1. 阶段一：根因 A（必修）

### T1 · enrich 两条路径都必须搬 `id`（AC-3）

| 用例 | 输入 | 期望 |
|---|---|---|
| `T1.1` | `buildComponentDataFromStructure(structure, [])` | 返回的 `comp.formulas` **每一条** `id` 非空，且与结构快照逐条相等 |
| `T1.2` | `enrichComponentData(templateId, <b>真实 savedComponentData</b>)`（模板快照路径，mock `/templates`） | 同 T1.1 |

> 🔧 **2026-08-05 订正（测试工程师实测顶回，技术总监核实采纳）**：本行原写 `enrichComponentData(templateId, [])`，**是错的**。
> `savedCompData.length === 0` 时函数在 `enrichComponentData.ts:74-82` **分流到 `buildComponentDataFromTemplate`**，根本走不到 `:166` 那段 `.map()`。
> 照原文写会「修复前后都红」，看着像没修好，实际是测错了函数。必须传真实 `savedComponentData`。
| `T1.3` | 上述两条结果 | 每个 `field.formula_id` 都能在 `comp.formulas` 里 `find` 到 —— **这条是防回归的核心断言** |

> ⚠️ **T1.2 不可省**。只测结构路径 = 只测了报价编辑页那半条链路，
> 详情页 live 模式走的是模板快照路径（AP-41 不对称故障的经典成因）。

### T2 · `resolveFormula` 按 id 解析（AC-3）

| 用例 | 输入 | 期望 |
|---|---|---|
| `T2.1` | 字段绑 `formula_id`，公式列表里有该 id | 返回**那一条**公式 |
| `T2.2` | 字段绑 `formula_id`，公式列表里**没有**该 id（公式真被删了） | 返回 `undefined`，**且不回落名字/位置** —— BL-0098 的设计必须保住，不能借修复之名开倒车 |
| `T2.3` | 字段无 `formula_id`、有 `formula_name` | 按名字命中（存量路径不变） |

### T3 · 条件公式按 id 解析（AC-4）

用真实的「材料成本」字段配置（`rules[0].formula_id = cb3ea05c-…`、`default_formula_id = c9213b82-…`）：

| 用例 | 期望 |
|---|---|
| `T3.1` | `rules` 长度 = 1（不被 `.filter` 清空），`default` 非 `undefined` |
| `T3.2` | `产出类型 = '非银点类'` → 命中 `非银点类材料成本公式` |
| `T3.3` | `产出类型 = 其它` → 落 `default` = `银点材料成本公式` |
| `T3.4` | `collectFormulaDefs`（BOM 树入口 `:799`）跑同一份配置，结论与 `computeAllFormulas` 一致 |

### T4 · 前后端对拍（AC-1，最强门禁）

```
用 structure-COMP0185.json + cardvalues-COMP0185.json：
  computeAllFormulas(第 i 行)  ≡  formulaResults[i].values   （i = 0..5，11 列逐值）
```

| 用例 | 期望 |
|---|---|
| `T4.1` | 6 行 × 11 列全部有值（**没有 `undefined`、没有 `null`**） |
| `T4.2` | 与后端 `formulaResults` 逐值相等（**相对误差 ≤ 1e-12**） |

> 🔧 **2026-08-05 订正**：原文「浮点按现有精度口径」没定义，无法执行。实测 66 格里 65 格逐位相同，
> 只有 `row2.材料成本` 差在第 17 位有效数字（同串乘除结合序差异）。固化 1e-12：容得下这一个 ULP、拦得住任何真实口径分歧。

> 🚨 **夹具铁律（2026-08-05 补，本轮最有价值的发现）**：`componentData[].rowData` 里混着后端上次算好的公式列值。
> `buildResolvedRow`（`QuotationStep2.tsx:1315`）在 `formulaCache` 缺 key 时**保留 row 原值** —— 即问题分析报告 §4.4 的「静默伪装」。
> 实测量化（回滚修复后跑）：**未剔除 → 60/66 格仍「等于后端」，门禁塌成 6 格；已剔除 → 0/66**。
> **夹具必须剔除公式输出列、只保留输入列**，否则 90% 的格子在测「有没有把后端旧值抄回来」。
| `T4.3` | **反向门禁**：把夹具里 `formulas[].id` 人为抹成 `undefined` → T4.1 必须**失败**（证明这条测试真的守着本次缺陷，而不是恒绿） |

### T5 · 列小计（AC-2）

| 用例 | 期望 |
|---|---|
| `T5.1` | `buildCrossTabRows(...).columnSumsByComp[COMP-0185]` 的 `材料成本 = 623.5975043517194` |
| `T5.2` | 该 map 与后端持久化 `subtotalByColumn` 的 6 个键逐值一致 |

### T6 · 类型门禁（AC-6）

| 用例 | 期望 |
|---|---|
| `T6.1` | `npx tsc --noEmit -p tsconfig.json` 0 错误（三处 `as any` 移除后仍然过） |

---

## 2. 阶段二：根因 B（做了才测）

### T7 · 前后端 rowKey 对拍（AC-7）

| 用例 | 期望 |
|---|---|
| `T7.1` | `buildUniqueRowKeys(comp.fields, ['料件'], baseRows, true)` 逐字等于 `formulaResults[].rowKey`（6/6） |
| `T7.2` | 字段用 **camelCase** `defaultSource` 形状（`CardStructureTab.fields`）时结果不变 —— 兼容不能踩坏原调用方 |
| `T7.3` | **反向门禁**：把字段的 `default_source` / `defaultSource` 删掉 → 退化成行号。**必须同时跑在 camel / snake 两种形状上并加非空性守卫** —— snake 形状在 F5 之前本来就退化成行号，只测它等于空转 |

### T8 · 存量 editRows 兼容（AC-8，**最容易漏、后果最直接**）

| 用例 | 场景 | 期望 |
|---|---|---|
| `T8.1` | `editRows` 用**旧行号键**写入（模拟 F5 之前的存量单据），渲染用**新内容键**查 | 经 legacy 回退**仍能读出**用户编辑值。⚠️ 实测：`snapByComp.edit` 全文件无人读取、`useCardSnapshots` hook 本体无页面消费 —— **这条链路当前是死代码**，用例照写但须标注；真正活的是 `formulaResults` 那条回退 |
| `T8.2` | `editRows` 用新内容键写入 | 直接命中，不走回退 |
| `T8.3` | 同一单据新旧键混存（改造窗口期真实形态） | 两种都读得出，且**不互相覆盖** |

> 这三条不过，F5 就是拿「用户填的数消失」换「快照命中率」，**不许上线**。

> 🔧 **2026-08-05 订正（实测推翻原风险判断）**：
> ① **全库 `editRows` = 0 条**（37 行项 / 32 个有卡片值，报价侧核价侧皆 0）—— 本库无任何存量要迁。
> ② **后端读 `editRows` 一直用它自己算的内容键**，存量行号键在后端侧**本来就已是孤儿** —— F5 不是制造这个问题，而是终结它。
> ③ **墓碑不受影响**：`rowFingerprint` 只读 `driverRow[name]` 原始值不经字段解析；`keepRow` 只用 `fp + nodeId`，`effKey` 形参自 2026-07-14 起不消费。
> 故 **T8 只做 `editRows` 一档**，不扩到 `deletedRowKeys`。F6 的定位是「给生产库的廉价保险」，不是拦路虎。

---

## 3. 人工验收（AC-5）

| # | 步骤 | 期望 |
|---|---|---|
| `M1` | 打开 `QT-20260804-0068` 编辑页 →「物料」 | 6 行 11 列全部出数，无 `—` |
| `M2` | 同单据**详情页** | 与 M1 逐值一致 |
| `M3` | 同单据**核价侧**卡片 | 公式列正常出数 |
| `M4` | 随便改一个 INPUT 列的值 → 失焦 | 依赖它的公式列即时联动，小计跟着变 |
| `M5` | 组件管理里**只点保存不改任何东西**（重新触发 `FormulaIdBinder`），回报价单 | 公式列**仍然正常** —— 这是本缺陷的原始触发路径，必须专门验一遍 |

---

## 4. 回归范围

- 前端全量 `vitest`（重点：`formulaSerialize` / `conditionalFormula` / `componentDraft` / `treeFormulaParityFixture` / `buildExcelSnapshot`）
- `npx tsc --noEmit` 0 错误
- 改动的 `.tsx` / `.ts` 经 Vite transform 200
- **E2E**：`QuotationStep2.tsx` 在强制触发清单内。当前 `quotation-flow` / `composite-product-flow`
  因夹具漂移跑不通（[[BL-0078]]）→ 以 T4/T7 的真实单据对拍 + M1~M5 人工三视图替代，
  **验收记录中如实标注「E2E 因 BL-0078 未跑」，不得含糊带过**。

---

## 5. 覆盖矩阵

| 验收标准 | 覆盖用例 |
|---|---|
| AC-1 | T4.1 / T4.2 / M1 |
| AC-2 | T5.1 / T5.2 |
| AC-3 | T1.1 / T1.2 / T1.3 / T2.1~T2.3 |
| AC-4 | T3.1~T3.4 |
| AC-5 | M1 / M2 / M3 |
| AC-6 | T6.1 |
| AC-7 | T7.1~T7.3 |
| AC-8 | T8.1~T8.3 |
| 防恒绿 | T4.3 / T7.3 |
| 原始触发路径 | M5 |
