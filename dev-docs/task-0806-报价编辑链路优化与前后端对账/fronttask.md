# fronttask.md · 报价编辑链路优化与前后端对账（前端工程师输入）

> 口径以同目录 `需求文档.md` 为准，接口以 `api.md` 为准。本文件只写「前端怎么落地」。
> ⚠️ 主战场 `QuotationStep2.tsx` 是**全仓最危险的文件**（24 个任务在它身上叠协议），属 `CLAUDE.md`「修改后强制自检」第 5 条的 **E2E 强制清单**。动它之前**必读**：`task-0721-报价侧树状结构` + `task-删除行删错架构重构` + `repair-0727-报价单报价侧删除行BUG`（一行有 4 套身份）。

---

## 1. 页面 / 组件清单

| 文件 | 改什么 | 阶段 |
|---|---|---|
| `src/pages/quotation/QuotationStep2.tsx` | 取值分流、对账、⚠ 旁路、异步编辑、防抖合并 | ①②③ |
| `src/pages/quotation/ReadonlyProductCard.tsx` | 取值分流**同步**（AP-50） | ① |
| `src/pages/quotation/ComponentCell.tsx` | ⚠ 角标 + tooltip 渲染 | ① |
| `src/pages/quotation/QuotationWizard.tsx` | 提交前置校验的错误处理（`409 RECONCILE_PENDING` 弹窗） | ① |
| `src/services/quotationService.ts` | 新增 `ensureRowData` / `reconcileReport` 调用 | ①③ |
| `src/pages/quotation/ExcelView.tsx` | 打开 Excel 视图前调 `ensure-row-data` | ③ |
| `src/utils/formulaEngine.ts` | **求值管线载体 number → Decimal**（阶段⑤ 主战场） | ⑤ |
| `src/utils/precision.ts` | 出口转换点调整（`evaluateArithmetic` 的返回契约） | ⑤ |

---

## 2. 阶段① · 分流 + 对账 + 差异可见

### 2.1 FR-2 · `quotationStatus` 透传（🚨 AP-41 高危）

`quotationStatus` 目前只在**父组件**有（`QuotationStep2.tsx:287`，用于控制「刷新基础数据」按钮），**没进 `ProductCardProps`**。

- `ProductCardProps`（`:2184`）新增 `quotationStatus?: string`
- **三处 `<ProductCard>` 调用点全部透传**：报价侧 / 核价侧 / 详情页 `ReadonlyProductCard`

> **AP-41 就是这么踩的**：漏传一处 → 一个视图正常、另一个静默失效。
> **验收方式**：`/usr/bin/grep -an "<ProductCard" src/pages/quotation/*.tsx` 数出全部调用点，逐个确认已传，把命中行号贴进 `test-report.md`。

### 2.2 FR-1 · 取值分流

`QuotationStep2.tsx` 现状：

```ts
const useSnapEdit = cardSide === 'QUOTE' && !!quotationId && !!(item as any).id;
```

🚨 **正确改法：新增独立 `isDraft`，只叠加到 `snapFormula` 那一处读分支，`useSnapEdit` 本身不动。**

```ts
const useSnapEdit = cardSide === 'QUOTE' && !!quotationId && !!(item as any).id;  // ← 不动
const isDraft = !quotationStatus || quotationStatus === 'DRAFT';
// …渲染处：
const snapFormula = (useSnapEdit && !isDraft) ? getByKeyWithLegacyFallback(...) : undefined;
```

> ⚠️ **本节原先给的是「把 `!isDraft` 直接并入 `useSnapEdit`」的示意码 —— 那是错的，2026-08-07 实现期发现并纠正。**
> `useSnapEdit` **不只**控制显示源，它还同时驱动：
> - **rowKey 计算**（`activeUniqRowKeyTuples` / `activeUniqRowKeys` / `activeLegacyRowKeySets`，以及 `const rowKey = useSnapEdit ? … : String(i)`）
> - **`handleSnapshotCellEdit` 的写调用闸门**（`if (useSnapEdit && isUserInputField …)`）
>
> 若并入 `useSnapEdit`，DRAFT 下会同时发生两件坏事：
> 1. **rowKey 退化成 `String(i)`** —— 把 repair-0805 F5 刚归一到 **497/497** 的权威行键**打回原形**（后端按位置误配对）
> 2. **写闸门关闭** —— `handleSnapshotCellEdit` 不再触发，**FR-4 对账在最该生效的 DRAFT 期反而没有数据源**
>
> 而 DRAFT 恰恰是编辑期最常见的状态。**这是 AP-50 级别的坑，照抄必炸。**

- **DRAFT** → `snapFormula` 恒 `undefined` → 走 `computeAllFormulas` / `computeTabFormulasTree`（现有分支，无需新写）；rowKey 与写路径**保持原样**
- **非 DRAFT** → 行为与今天**逐字节相同**

> 💡 **性能前提已实证**：`buildCrossTabRows`（`:2855`）写在 ProductCard 渲染体里、**不在 `useMemo` 内**，每次渲染本来就把所有行所有公式算了一遍（为了出列小计）。所以 DRAFT 走本地引擎**边际成本为 0**，不需要额外优化。

`ReadonlyProductCard.tsx:689-702` 同款分流，**必须同批改**（AP-50）。

### 2.3 FR-3 · 兜底不得删

`:3329` 的 `(snapFormula && Object.keys(snapFormula).length > 0) ? … : …` 结构保留。
非 DRAFT 下快照缺该 rowKey / 该列时仍要落到本地引擎（新行、LIST_FORMULA 字符串公式依赖它）。

### 2.4 FR-4 · 对账与 ⚠ 旁路

**时机**（D5）：编辑防抖落定后，拿 API-1 的响应对**整卡**比一次，不是每格比。

**数据来源**：
- 前端值：本轮渲染的 `preComputedCaches[rowIdx][fieldName]`
- 后端值：响应 `quoteCardValues.tabs[].formulaResults[].values[fieldName]`，按 **rowKey 对齐**
- 前端输入 / 后端输入：本行 `row` + `driverRow` / 响应 `tabs[].resolvedRows[]`（供 tooltip，D2）

**判定**（D4）：`Math.abs(a - b)` 按 `DISPLAY_SCALE = 6` 判，小于阈值视为一致。
两边都非数字时按字符串比；一边有值一边缺失**算差异**。

**渲染**：差异写进已有的 `preComputedErrors[rowIdx][fieldName]`（`:3295` 的错误旁路），`ComponentCell` 已有 ⚠ + tooltip 通道，**不新造机制**。

tooltip 文案按 `需求文档.md §5.4`：

```
前后端算值不一致
  前端 6.087758    输入：材料占比=0.25  组成数量=2  元素单价=28892.5
  后端 608.775811  输入：材料占比=25    组成数量=2  元素单价=28892.5
```

### 2.5 FR-5 · 埋点

对账有差异 → 调 API-5（fire-and-forget，`catch` 掉不打扰用户）。
**`frontendInputs` / `backendInputs` 只带行键字段 + 该公式引用到的字段，不要整行倾倒。**

### 2.6 FR-6 · 提交闸门

`QuotationWizard` 提交流程捕获 `409`：
- `reason === 'RECONCILE_PENDING'` → **Drawer** 列出 `conflicts`（料号 / 页签 / 行 / 列 / 两边值），提供「定位到该格」跳转
- `reason === 'WRITE_IN_FLIGHT'` → 提示「正在保存，请稍候」并自动重试一次

> ⚠️ `CLAUDE.md` UI 规范：**用 Drawer，不用 Modal**（危险动作二次确认除外）。

---

## 3. 阶段② · 异步 + 防抖合并

### 3.1 FR-7 · 不再 await 回灌

`handleSnapshotCellEdit`（`:2704-2724`）现状是 `await` 后 `onUpdate` 回灌 `quoteCardValues`。改为：

- **不回灌渲染**（DRAFT 下渲染已由前端引擎负责）
- 响应只交给对账（§2.4）
- 保留 `catch` 静默，但**改为记账**：失败计数 → FR-10 可见失败态

### 3.2 FR-9 · 防抖合并

现状每格失焦发一次。改为**按 lineItem 攒批**：失焦入队 → 防抖窗口（建议 800ms，实现期可调）→ 批量发送。

> AC-6：连改 10 格 ≤ 2 次请求。

### 3.3 FR-8 · 丢更新防护（前端侧）

- 每次请求带当前 `row_version`
- 收到 `409` → 用最新版本**重放**该格编辑（不是丢弃），重试上限 3 次
- 响应乱序：**只接受序号 ≥ 已处理最大序号的响应**（D3），过期响应直接丢

### 3.4 FR-10 · 失败可见

重试耗尽 → 在卡片头部显示可见失败态（如「N 处修改未保存」+ 重试按钮）。**禁止静默吞掉。**

---

## 4. 阶段③ · 前端侧改动（很小）

打开 **Excel 视图** / 点**导出**前，先调 API-2 `ensure-row-data`（与现有 `ensure-excel-values` 并列，可合并成一次串行调用）。

---

## 4.5 阶段⑤ · 求值管线 Decimal 统一（FR-18~21）

### 病灶在哪

```ts
// formulaEngine.ts:792 与 :905 —— 两处出口
const result = evaluateArithmetic(expr);        // 内部全程 Decimal，精确
return result === null ? 0 : result.toNumber(); // ← 精度在这里丢
```

`ArithDecimalParser` 内部是精确的，但**每条表达式一出门就转回 number**。所以下游的聚合：

```ts
// :189-197
function aggregateTreeNums(agg: string, nums: number[]): number {
  case 'SUM': return nums.reduce((s, x) => s + x, 0);   // ← 拿到的已经是 number，无原料
```

后端同位置是 `PrecisionPolicy.sum(nums)`（BigDecimal）→ **SUM/AVG 是确定的分歧源**。

### 改法

**把 Decimal 的生存期从「一条表达式内」延长到「整条求值管线」**，只在最终出口转 number：

| 边界 | 改前 | 改后 |
|---|---|---|
| `evaluateArithmetic` 返回 | `Decimal \| null` → 调用方立刻 `.toNumber()` | 保持 `Decimal`，**调用方不再立即转** |
| `evaluateExpression` | 返回 `number` | 返回 `Decimal` |
| `evalTreeRefToken` / `cross_tab_ref` 聚合 | `number[]` → `number` | `Decimal[]` → `Decimal` |
| `aggregateTreeNums` | `nums.reduce((s,x)=>s+x,0)` | `Decimal.plus` 精确累加；`AVG` 用 `div` + `DIVISION_SCALE` |
| **最终出口**（写 `formulaCache` / 落 payload / 交给显示层） | — | **在这里且只在这里 `.toNumber()`** |

- `MAX` / `MIN` / `COUNT` 无精度问题，但**不得再经 number 中转**（用 `Decimal.cmp` 比较）
- 除法 scale 沿用 `precision.ts` 现有的 `DIVISION_SCALE = 12`，**不要另立**

### ⚠️ 这一阶段会改变现有的值 —— 验收判据跟别的阶段不一样

| 阶段 | 判据 |
|---|---|
| ⓪①②③④ | **AC-13 值中性**：逐值不变 |
| **⑤** | **AC-16**：每处变化必须**可解释为精度提升**，且幅度 **< 6 位显示精度**。超出即缺陷 |

另两条硬要求：
- **AC-17 存量不回溯**：已提交单据的 `quote_card_values` / `submissionSnapshot` **逐字节不变**（D12）
- **AC-19**：① 对账里 `SUM`/`AVG` 列的告警**归零**（以 ① 上线后累积的告警为基准）
- **FR-21**：`GoldenCardValuesEquiv` 的 `amt-002/003` **期望值重新标定**，标定依据写进 `test-report.md`

> 💡 **为什么 ⑤ 必须排在 ① 之后**：对账会把 SUM/AVG 的实际差异**量化出来**，那就是这一阶段的验收基准。没有基准就改，改完无法判定对没对。

---

## 5. 状态管理与缓存 key

**本任务不新增任何前端缓存，也不改动任何现有 cache key。**

| 现有 key | 是否改动 | 说明 |
|---|---|---|
| `driverExpansionKey`（含 lineItemId/partNo/componentId/customerId/dataDriverPath/fieldsHash） | ❌ 不动 | AP-31 / AP-37 族约束，与本任务无交集 |
| `usePathFormulaCache` 的 path key | ❌ 不动 | 同上 |
| `bakedRef`（默认值烘焙守卫） | ❌ 不动 | 「键存在即已定值」口径不变 |

新增的**对账状态**（差异清单）建议放组件内 `useState`，**不要**塞进 `driverExpansions` 之类的共享缓存 —— 它是瞬态的、随下一轮对账整体替换。

---

## 6. 边界与空态

| 场景 | 期望 |
|---|---|
| 非 DRAFT（已提交/已审批）打开 | 行内读快照，**逐字节**与提交时一致；对账仍跑，但差异只埋点**不弹提交闸门**（不可编辑，无提交动作） |
| 快照缺该 rowKey（新增行） | 走本地引擎（FR-3），**不算差异**（后端还没算过它） |
| 后端返回 `__cardValueFailed` 哨兵 | 保持现有「该料号卡片数据待重算」提示；**跳过对账**（没有可比对象） |
| driver 返 0 行 | 沿用 AP-38 口径：BASIC_DATA 显示 `—`，不降级 globalPathCache |
| 编辑请求全部失败（离线） | FR-10 可见失败态；**行内值仍显示前端算的**（不回退），提交被闸门拦住 |
| LIST_FORMULA 字符串公式 | 不进 `formulaResults`，**不参与对账**（后端本来就不算它） |

---

## 7. 自检项（每阶段收工前必跑）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
- [ ] 每个改动的 `.tsx`：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/<相对路径>` → **200**
  > ⚠️ 必须加 `--noproxy '*'`：本机 `http_proxy=127.0.0.1:7890`，不加会走代理返 502
- [ ] `npx vitest run src` → 与基线失败数一致（master 存量 `formulaGolden` amt-002/003 常年红）
- [ ] **协议级改动 → 跑 E2E**：`QuotationStep2.tsx` 在强制清单内
  ```bash
  npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
  ```
  ⚠️ E2E 现状：夹具漂移导致基线本身可能不通（`BL-0078`）。**跑不通要做 A/B**（在干净 master 上跑同一条），证明不是本次引入，并在 `test-report.md` 如实标注
- [ ] **三视图人工核对**（AP-50）：编辑页 / 详情页 / 核价侧同一料号同一页签，值一致

---

## 8. Task 列表（逐项可勾选）

### 阶段①
- [ ] F1-1 `ProductCardProps` 加 `quotationStatus`
- [ ] F1-2 三处 `<ProductCard>` 调用点透传（贴 grep 命中行号）
- [ ] F1-3 `QuotationStep2.tsx` `useSnapEdit` 加 `!isDraft`
- [ ] F1-4 `ReadonlyProductCard.tsx` 同款分流（AP-50）
- [ ] F1-5 对账函数：整卡逐格比对，按 rowKey 对齐，D4 阈值
- [ ] F1-6 差异写入 `preComputedErrors` → `ComponentCell` ⚠ + tooltip（D2 两边输入）
- [ ] F1-7 API-5 埋点上报（fire-and-forget）
- [ ] F1-8 `QuotationWizard` 捕获 `409`，Drawer 列差异清单 + 定位跳转
- [ ] F1-9 自检 §7 全项

### 阶段②
- [ ] F2-1 `handleSnapshotCellEdit` 去掉 await 回灌，响应转对账
- [ ] F2-2 编辑请求防抖攒批（AC-6：10 格 ≤ 2 次）
- [ ] F2-3 请求带 `row_version`；`409` 重放，上限 3 次
- [ ] F2-4 响应序号守卫（D3 过期直接丢）
- [ ] F2-5 可见失败态 + 重试入口
- [ ] F2-6 自检 §7 全项

### 阶段③a（前端侧）—— ✅ **零改动**

> 🚨 **2026-08-07 D17 撤销原 F3-1**：阶段③ 经生产态实测重新裁定为 **③a 批量写**（纯后端写法变更）+ **③b 懒物化不做**（转 [[BL-0154]]）。
> API-2 `ensure-row-data` **不实现**，故前端**没有**需要调用的新端点。

- [x] ~~F3-1 打开 Excel 视图 / 导出前调 API-2~~ → **撤销**（API-2 不存在）
- [ ] F3a-1 **判定依据（为什么不改）**：③a 只把后端整行物化的**写法**从「8 次 `REQUIRES_NEW`」换成「1 次批量」，**物化时机、落库内容、接口契约三者全不变**（落库逐位一致已实证，见 `需求文档.md` AC-8b / 附录 A.6）。前端读 `row_data` 的路径（Excel 视图 / 导出）拿到的数据与改动前**字节相同**。
- [ ] F3a-2 **回归确认清单**（不改代码，但要确认没被波及）：
  - 编辑一格 → 卡片行内值与列小计仍同源一致（阶段① 已上线的分流行为不受影响）
  - 编辑一格 → 立刻开 **Excel 视图**，值为最新（AC-8）
  - **树删除 / 恢复行** → 行数与卡片一致（`materializeAndProject` 是第二条受益路径，AP-51 行数权威）
  - `tsc --noEmit` 0 错误 + `vitest run src` 与基线一致（本期前端零改动，两项应与 master 完全相同）
- [ ] F3a-3 **二期触发条件**：若 [[BL-0154]] 将来重启懒物化，F3-1 原样复活（打开 Excel 视图 / 点导出前调 API-2），届时 `api.md` API-2 的契约草案可直接用。

### 阶段⑤（Decimal 统一，前端主战场）
- [ ] F5-1 `evaluateExpression` 返回类型 `number` → `Decimal`
- [ ] F5-2 `evalTreeRefToken` / `cross_tab_ref` 聚合链路全程 Decimal
- [ ] F5-3 `aggregateTreeNums`：SUM/AVG 用 Decimal 精确算；MAX/MIN/COUNT 用 `Decimal.cmp`，不经 number 中转
- [ ] F5-4 收口出口转换：**只在写 `formulaCache` / 落 payload / 显示层** `.toNumber()`，全文件 grep 确认无第二处
- [ ] F5-5 除法 scale 沿用 `DIVISION_SCALE = 12`，不另立常量
- [ ] F5-6 `GoldenCardValuesEquiv` `amt-002/003` 期望值重新标定 + 依据写入 `test-report.md`
- [ ] F5-7 **AC-16 差异审计**：逐格列出改造前后变化，每处标注「可解释为精度提升」，超 6 位的按缺陷处理
- [ ] F5-8 **AC-17 存量不回溯**：抽 5 张已提交单据验 `quote_card_values` / `submissionSnapshot` 逐字节不变
- [ ] F5-9 **AC-19**：① 对账的 SUM/AVG 告警归零
- [ ] F5-10 自检 §7 全项（**必跑 E2E**，`formulaEngine.ts` 是 6 任务叠协议文件）
