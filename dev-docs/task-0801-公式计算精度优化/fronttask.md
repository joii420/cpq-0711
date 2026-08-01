# 前端任务文档 — 公式计算精度优化（task-0801）

> 配套：`需求说明.md`（需求与澄清结论，冲突时以其 §11 为准）、`api.md`（精度契约）、`backtask.md`（后端任务）
> 分支：`feat/task-0801-formula-precision`（worktree 隔离开发，禁止在主工作区/master 上改）
> 定稿：2026-08-01

---

## 0. 开工前必读（30 分钟，不可跳过）

| # | 文档 | 为什么 |
|---|------|--------|
| 1 | 本目录 `需求说明.md` §4.3 + §11 | 精度规则与全部澄清结论 |
| 2 | 本目录 `api.md` §1 / §5 | 精度契约 + 黄金用例（你的单测直接照抄） |
| 3 | `docs/E2E测试方法.md` | 本任务 **E2E 是强制项**，不是可选 |
| 4 | `docs/反模式.md` AP-50 | 详情页 / 编辑页必须同口径，改一端不改另一端 = 经典事故 |
| 5 | `docs/RECORD.md` 2026-06-21 两条小数记录 | 本任务**推翻**的历史口径（4 位 / 2 位），注释里明确写过"易被误当 bug 改回" |
| 6 | `CLAUDE.md`「修改后强制自检」第 5 项 | 你要改的文件全在 E2E 触发清单里 |

**本任务不触发 AP-44**（不改 `field_type`），但改动落在报价渲染核心链路，**E2E 双 spec 强制**。

---

## 1. 核心设计：两条链路的精确边界

| | 链路一（安全区） | 链路二（危险区） |
|---|---|---|
| **范围** | 单元格公式求值 → 列小计 → 页签合计 → **产品小计（单件级）** | **产品小计 → ×年用量 → 行合计 → Σ 整单合计** |
| **金额量级** | ≤ 10⁶ | 10⁸~10⁹（年用量几十万件） |
| **有效数字** | ≤ 12 位（JS number 有 15~17 位，余量充足） | **15 位（已达 number 极限）** |
| **承载类型** | 跨层可继续用 `number` | **必须全程 `Decimal`，禁止中途 `.toNumber()`** |
| **要求** | 单次求值与累加**内部**用 Decimal 精确算，算完可存回 number | 全程 Decimal，只在最终显示时 `toFixed` |

> 典型链路二代码：`QuotationStep3.tsx:207-219` 的三个 `reduce`，其中 `grandOriginal` 直接
> `(lineUnitPrice) * (annualVolume)` 再累加 —— **单价 × 几十万 × 20 行 = 亿级**，
> 这是本任务前端最危险的一处，必须优先改。

---

## 2. 任务清单总览

| Task | 名称 | 规模 | 依赖 |
|------|------|------|------|
| F1 | 精度基础设施（`precision.ts` + Decimal 表达式求值器） | M | 无 |
| F2 | 公式求值十进制化（3 个求值点） | M | F1 |
| F3 | 汇总链路 Decimal 化（含链路二） | M | F1 |
| F4 | 显示口径统一为 6 位 | M | F1 |
| F5 | `normalizeDraftPayloadNumbers` 位数修正（**有坑，细读**） | S | F1 |
| F6 | 单元测试 | M | F1~F5 |
| F7 | E2E（强制） | M | 全部 |
| F8 | 自检与交付证据 | S | 全部 |

**建议顺序**：F1 → F2 → F3 → F5 → F4 → F6 → F7 → F8。

---

## Task F1：精度基础设施

### 目标
把精度决策收敛到一个模块，并提供一个**与后端 `ArithParser` 语义对称**的十进制表达式求值器。

### 新建文件
`cpq-frontend/src/utils/precision.ts`

依赖：`decimal.js`（已在 `package.json`，`^10.6.0`，无需新装）。

### 必须提供的 API

```ts
/** 呈现精度：显示 / 落库 payload 边界统一 6 位。 */
export const DISPLAY_SCALE = 6;
/** 除法中间精度。 */
export const DIVISION_SCALE = 12;
/** payload 规范化位数 —— 注意不是 6，理由见 Task F5。 */
export const PAYLOAD_NORMALIZE_SCALE = 10;

/** 任意值 → Decimal（null/空/非数字 → 0），唯一转换入口。 */
export function toDecimal(v: unknown): Decimal;
/** 十进制精确表达式求值（替代 new Function），语义与后端 ArithParser 对齐。 */
export function evaluateArithmetic(expr: string): Decimal | null;
/** 精确累加。 */
export function sumDecimal(values: Array<unknown>): Decimal;
/** 规整到呈现精度并转 number（仅在离开计算链路时用）。 */
export function roundToDisplay(v: Decimal | number): number;
```

### `evaluateArithmetic` 实现要求（关键）

用**递归下降解析器**（约 120~150 行），**不要**用 `eval` / `new Function`。必须与后端
`FormulaCalculator.ArithParser`（`:1961-2030`）语义**逐条对齐**：

| 语义 | 要求 | 对应黄金用例 |
|------|------|------------|
| 运算符 | `+ - * /`、括号 | G-13 |
| 一元正负号 | `-(2+3)*2 = -10` | G-12 |
| 运算符优先级 | `2+3*4 = 14` | G-13 |
| 除法 | 走 `DIVISION_SCALE`(12) 精度 | G-3 |
| 除以 0 | **返回 0，不抛异常**（保持现有 `catch → 0` 行为） | G-9 |
| 非法表达式 | 返回 `null`（保持 `formulaEngine:703` 的现有约定） | — |
| 空值参与运算 | 按 `0` 参与（保持 `numericToStr` 现有行为） | G-8 |

> ⚠️ **只换数值类型与求值方式，不改这些语义**。任何语义变化都会引发报价值漂移，属回归缺陷。

### 同步改造
`cpq-frontend/src/utils/formatNumber.ts`：
- `COMPUTED_FALLBACK` 由 `4` 改为**引用 `precision.ts` 的 `DISPLAY_SCALE`**（不再自持字面量）；
- 更新文件头注释：现注释描述的是**已被本任务推翻**的 4 位/2 位策略（`需求说明.md` §9.1），
  必须改写为新口径，否则后人会照着旧注释把它改回去。

### 验收
- [ ] `api.md` §5.2 的 G-1 ~ G-14 **全部**在 `precision.test.ts` 中有断言且全绿
- [ ] `DISPLAY_SCALE === 6` 有显式断言（AC-16）

---

## Task F2：公式求值十进制化（3 个求值点）

### 3 个求值点（一个都不能漏）

| # | 文件:行 | 用途 | 改法 |
|---|---------|------|------|
| 1 | `utils/formulaEngine.ts:587-589` | 主公式求值 `evaluateExpression` | `new Function` → `evaluateArithmetic`；**去掉 `toDecimalPlaces(4)`** |
| 2 | `utils/formulaEngine.ts:703-705` | LIST_FORMULA 字符串公式求值 | 同上；保持"非法表达式返回 `null`"的约定 |
| 3 | `pages/quotation/ExcelView.tsx:65` | Excel 视图公式求值 | 同上 |

### 要点与坑

1. **返回类型保持 `number`**（链路一承载）——
   内部用 Decimal 精确算，`return` 前 `.toNumber()`。**不要**把返回类型改成 `Decimal`，
   否则会波及全部消费方（`computeAllFormulas` / `ComponentCell` / 快照构建等），超出本期范围。
2. **去掉 `toDecimalPlaces(4)` 后**，返回值可能带很长的小数 —— 这是**预期行为**（中间不截断），
   由显示层（F4）和 payload 规范化（F5）在边界处理。不要因为"看着不舒服"又加回截断。
3. `formulaEngine.ts:697` 的白名单正则 `/^[\d+\-*/().\s]*$/` **保持不变**（安全防线）。
4. `isWithinTolerance`（`:595`）的容差 `0.01` 是**前后端一致性校验**用的，
   精度提高后可考虑收紧，但**本期不改**（改了会让存量数据大面积报不一致）。

### 验收
- [ ] 3 个求值点各有一条 `0.1+0.2 = 0.3` 断言
- [ ] `formulaEngine.test.ts` / `formulaSerialize.test.ts` / `computeFormula.test.ts` 原有用例全绿

---

## Task F3：汇总链路 Decimal 化

### F3-1 链路二（**最高优先级，亿级金额**）

| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `QuotationStep3.tsx:207-210` `grandOriginal` | `sum + (lineUnitPrice ?? subtotal ?? 0) * (annualVolume ?? 0)` —— **number 乘几十万再累加** | 全程 Decimal：`toDecimal(unitPrice).times(toDecimal(qty))` 累加，最后 `roundToDisplay` |
| `QuotationStep3.tsx:212-215` `grandDiscount` | number reduce | 同上 |
| `QuotationStep3.tsx:216-219` `grandTotal` | number reduce | 同上 |
| `QuotationWizard.tsx:1210` `originalAmount` | `reduce((sum, li) => sum + computeProductSubtotal(...), 0)` | 同上（Step4 汇总页） |
| `QuotationWizard.tsx:1629` `originalAmount` | 同上 | 同上 |

### F3-2 链路一（求值/累加内部精确，承载仍是 number）

| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `QuotationStep2.tsx:880/899` `sumColumnsCanonical` 的 `round4` + `sum += n` | number 累加 + 4 位截断 | 用 `sumDecimal` 精确累加；**去掉 round4**，返回 `number` |
| `QuotationStep2.tsx:1085/1095/1102` `round4` | 同上 | 同上 |
| `QuotationStep2.tsx:1196` `for (const v of ...) sum += v` | number 累加 | `sumDecimal` |
| `QuotationStep2.tsx:1240` `subtotal += colVal` | 同上 | 同上 |
| `tabTotalLines.ts:36/55` `total += val` | 页签合计 number 累加 | 同上 |

### 要点
- 链路一改的是**累加方式**（number `+=` → Decimal 累加），**不改函数签名与返回类型**；
- 每个改动点都要问一句"这个值会不会乘年用量" —— 会 → 链路二，全程 Decimal；不会 → 链路一。

### 验收
- [ ] 链路二用例：单价 `123.456789` × 年用量 `800000` × 20 行，整单合计小数第 6 位正确（AC-14）
- [ ] 链路一用例：列小计 = 各行真值精确和，与后端 `computeTabSubtotalsByColumn` 结果一致

---

## Task F4：显示口径统一为 6 位

### 统一原则
**所有计算类数值走 `formatNumber(v, { isComputed: true })`**（兜底已由 F1 改成 6 位，至多 6 位去尾零）。
**禁止**再出现散落的 `toFixed(2)` / `toFixed(4)` / `toLocaleString(...)` 硬编码位数。

### 改动点清单（逐个勾掉）

#### 报价单编辑页
| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `QuotationStep2.tsx:2113-2114` `formatCurrency` | `formatNumber(val, { isComputed: true, decimals: 2 })` | **去掉 `decimals: 2`**，走 6 位兜底 |
| `QuotationStep2.tsx:2936` | `formatNumber(v, { isComputed: true })` | 已正确，兜底改后自动生效 ✅ |
| `QuotationStep2.tsx:2958` 页签金额合计 | 同上 | ✅ 无需改 |

#### 详情页（AP-50：必须与编辑页同口径）
| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `ReadonlyProductCard.tsx:135-136` `formatCurrency` | `toLocaleString(min:2, max:2)` | 改走 `formatNumber(v, { isComputed: true })` |
| `ReadonlyProductCard.tsx:893` | `parseFloat(v.toFixed(4)).toString()` | 改走 `formatNumber(v, { isComputed: true })` |
| `ReadonlyProductCard.tsx:915/934` | 已走 formatNumber / formatCurrency | 随上面两处一并生效 |

#### Step3 优惠策略页
| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `QuotationStep3.tsx:285-288` `formatCurrency` | `toLocaleString(min:2, max:2)` | 改走 `formatNumber(v, { isComputed: true })`，保留货币符号逻辑 |

#### 报价单列表（需求方明确：**只改精度，不改列、不改名**）
| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `QuotationList.tsx:98` | `Number(v).toLocaleString()` —— **默认最多 3 位小数**，这就是"列表与详情对不上"里属于精度的那部分 | 改走 `formatNumber(v, { isComputed: true })`，保留 `¥` 前缀与 `-` 空值兜底 |

#### 详情视图 / 向导汇总 / 快照
| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `ProductDetailViews.tsx:262` 原价合计 | `toLocaleString(min:2)` | `formatNumber(..., { isComputed: true })` |
| `ProductDetailViews.tsx:271` 总额 | 同上 | 同上 |
| `ProductDetailViews.tsx:293` 核价总额 | `toLocaleString(min:2, max:2)` | 同上 |
| `QuotationWizard.tsx:1664` 行小计 | `¥${(v||0).toLocaleString()}` | 同上 |
| `QuotationWizard.tsx:1671/1681/1684` 原始/最终总额 | `toLocaleString()` | 同上 |
| `components/SnapshotTab.tsx:149` | `Number(v).toFixed(2)` | 同上 |

#### 核价侧
| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `pages/costingsummary/CostingSummaryDetailPage.tsx:234` | `Number(r.value).toFixed(4)` | `formatNumber(..., { isComputed: true })` |
| 核价单渲染共用 `ReadonlyProductCard` / `ComponentCell` | — | 随上面改动自动同步 ✅ |

### **明确不改**的点（避免过度改造）

| 文件:行 | 理由 |
|---------|------|
| `comparisonMapping.ts:64/73` | 比对视图**显式传 decimals 参数**，是配置驱动的展示口径，非兜底 |
| `excelCellFormat.tsx:31` | PERCENT 列 `decimals ?? 2` 是百分比显示约定（类别 C） |
| `ComponentCell.tsx:286` | 已走 `formatNumber(..., { decimals: field.decimals ?? null, isComputed: true })`，兜底改后自动生效 ✅ |
| `configure/AddPartSubDrawer.tsx:185/304`、`SelDetailTable.tsx:37` | 元素含量百分比校验提示（类别 C） |
| `pages/element-price/**`、`pages/pricing/**` | 元素价格 = 取数值（类别 B），保持原精度 |
| `pages/config/ElementEditDrawer.tsx:19`、`MaterialRecipeEditDrawer` | 基础资料录入，非公式结果 |
| 所有 `new Date(...).toLocaleString()` | 日期格式化，与数字无关 |
| 折扣率 / 税率相关展示 | 输入值（类别 C），保持 2 位 |

### 布局自检（风险 R-7）
位数变长后表格列可能挤压。改完必须目视检查：报价单 Step2 各页签表格、Step3 表格、报价单列表、详情页。
如有挤压，调列宽即可，**不要退回减少位数**。

---

## Task F5：`normalizeDraftPayloadNumbers` 位数修正（**有坑，务必细读**）

### 现状
`QuotationWizard.tsx:42-52`：递归把 payload 里**所有** `number` 压成 `Number(v.toFixed(4))`，
注释目的是"消除 live↔snap 求值浮点尾差，保证 payload 去重稳定"。

### 坑
它是**一刀切**的 —— 不区分计算列与取数列。
如果简单改成 `toFixed(6)`，**8 位小数的取数列（如工装单价 `P12ToolingCostHandler` 写 8 位）
在保存草稿时会被压到 6 位**，直接违反 AC-8（取数列必须保持原精度）。

### 正确改法
把位数改为 `PAYLOAD_NORMALIZE_SCALE = 10`（**不是 6**）：

- **10 位足以消除浮点尾差**（尾差在 1e-15 量级），保住原函数的去重稳定性目的；
- **10 位不伤取数列**（现网最高精度是 8 位）；
- **计算值不会因此变成 10 位落库** —— 后端在落库边界统一 `PrecisionPolicy.round()` 到 6 位
  （`backtask.md` Task B5），前端发 10 位、后端落 6 位，结果一致；
- 取数列后端**不规整**（类别 B），因此保持 8 位原值，AC-8 达成。

### 必须做
- 常量放 `precision.ts` 的 `PAYLOAD_NORMALIZE_SCALE`，**不要**在 `QuotationWizard.tsx` 里写字面量；
- 更新该函数的注释，写清"为什么是 10 而不是 6"（否则下一个人会"顺手统一成 6"把取数列压坏）；
- 加一条单测：payload 中 8 位小数的取数值经过规范化后**仍是 8 位**。

---

## Task F6：单元测试

| 组 | 内容 | 对应验收 |
|----|------|---------|
| **T1 黄金用例** | `api.md` §5.2 G-1 ~ G-14 逐条断言（与后端**共用同一份期望值**） | AC-11/12 |
| **T2 求值点覆盖** | 3 个求值点各一条 `0.1+0.2=0.3` | R-1 |
| **T3 链路二基线** | 单价 6 位 × 年用量 50 万 × 20 行 → 整单合计第 6 位正确 | AC-14 |
| **T4 链路一基线** | 6 层嵌套（元素行→列小计→页签合计→产品小计）结果 = 一次性精确计算 | AC-13 |
| **T5 常量锁定** | `DISPLAY_SCALE === 6`；`formatNumber` 兜底 = 6 | AC-16 |
| **T6 类别隔离** | 8 位取数值经 `normalizeDraftPayloadNumbers` 后仍 8 位；费率仍 2 位 | AC-8/AC-9 |
| **T7 语义不变** | 除零 → 0；null 按 0；非法表达式 → null；全角运算符；一元负号；优先级 | R-5 |
| **T8 显示格式** | 至多 6 位去尾零：`0.0774→"0.0774"`、`5→"5"`、`0.0000005→"0.000001"` | AC-1/AC-2 |

### 回归
原有测试**全部必须绿**，特别是：
`formulaEngine.test.ts` / `formatNumber.test.ts` / `formulaSerialize.test.ts` / `computeFormula.test.ts` /
`buildExcelSnapshot.test.ts` / `columnSumsByComp.test.ts` / `lineDiscount.test.ts` / `unitConversion.*.test.ts`。

> 若某条老用例因**期望值位数**而失败（如期望 `0.0774` 现在得 `0.07740012`），
> 说明该处**中间截断被正确移除**，应更新期望值；
> 若因**语义**而失败（除零、null、优先级），说明**改错了**，必须回退重做。
> 交付时对每条修改过的老用例给出属于哪一类的说明。

---

## Task F7：E2E（强制，不可跳过）

### 为什么强制
你改了 `QuotationStep2.tsx` / `formulaEngine.ts` / `ReadonlyProductCard.tsx` / `QuotationWizard.tsx` ——
全在 CLAUDE.md「修改后强制自检」第 5 项的 E2E 触发文件清单里。
**AP-37 / AP-38 / AP-40~43 族的协议 bug 只在 E2E 暴露，TS check 和 API 探活看不到。**

### 执行
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```

### 必须看到
- 所有 test `passed`
- `'加载中' final count = 0`
- 全部 8 个 Tab `'加载中' = 0`

### 必须附
qf-19（确认添加后）+ qf-21~28（8 个 Tab）共 **9 张截图**作为渲染证据。

### ⚠️ 已知环境陷阱（别误判）
- **E2E 夹具漂移**：`quotation-flow` 在干净 master 上也可能有固定失败（夹具单缺产品分类导致 Step1「下一步」禁用，
  见记忆 `task0712-update071501-category-axis`）。**判断是否回归必须做 A/B 对比**：
  在 master 同环境跑一次作为对照，不要把存量夹具问题算到本次改动头上；
- 中文 UTF-8 编码坑、选择器约定见 `docs/E2E测试方法.md`；
- E2E 反复跑可能把 admin 置为 INACTIVE，需 SQL 改回 ACTIVE。

---

## Task F8：自检与交付证据

### 强制自检命令（逐条跑，输出贴进交付说明）

```bash
# 1. TS 类型检查 —— 必须 0 错误
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json

# 2. 单测
npm test

# 3. 对每个改动的 .tsx 跑 Vite transform —— 必须 200
#    ⚠️ 本机 shell 常设 http_proxy，探本机服务一律加 --noproxy '*'
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/QuotationStep3.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/QuotationList.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/QuotationWizard.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/ProductDetailViews.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/utils/precision.ts
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/utils/formulaEngine.ts
# …改到哪个补哪个

# 4. 主入口
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/     # 期望 200

# 5. 残留硬编码位数审计 —— 输出逐条给结论
/usr/bin/grep -rn -a -E "toFixed\([0-9]\)|toLocaleString\(.*[Ff]raction" cpq-frontend/src/pages/quotation cpq-frontend/src/pages/costingsummary | /usr/bin/grep -v "\.test\."
```

### 交付说明必须包含

1. **3 个求值点 + F4 全部展示点的逐点勾选表**；
2. 残留硬编码位数审计输出 + 每条命中的"改了 / 不属本次范围（理由）"结论；
3. TS 0 错误 + 单测全绿输出；
4. 每个改动 `.tsx` 的 Vite 200 输出；
5. **E2E 双 spec 通过输出 + 9 张截图**；
6. **三视图截图**（报价单编辑页 / 核价单 / 详情页）修复前 vs 后对比；
7. 一行「已自检」声明（CLAUDE.md 硬性要求），例如：
   > "TS 0 错误 ✅；前端单测 96 passed ✅；QuotationStep2/Step3/ReadonlyProductCard/List → Vite 200 ✅；
   > E2E quotation-flow 1 passed、'加载中' final count = 0 ✅；composite-product-flow passed ✅"

### ⚠️ 禁止事项
- 禁止 `git add -A`，只 add 本次明确改动的文件；
- 禁止在 worktree 内另起 dev server 或重装依赖 ——
  共享主工作区的 5174 / 8081；worktree 前端自检需软链 `node_modules` + 另起临时端口 vite
  （见记忆 `cpq-worktree-frontend-selfcheck`），**不要动共享实例**；
- 禁止跳过 E2E —— 跳过 E2E 等于跳过自检；
- 禁止宣布"完成"时缺少上述证据。

---

## 3. 与后端的协作点

| 事项 | 约定 |
|------|------|
| 精度常量 | 前后端各持一份，值都是 6；以 `api.md` §5.1 为准 |
| 黄金用例 | 前后端**各自实现**、**共用同一份期望值**（`api.md` §5.2）；任一端不符即缺陷 |
| 接口 | **结构不变**，你不需要改任何请求体；若后端提出要把数值改成字符串，**先找技术总监**，不要私下答应 |
| 联调 | 同一张单：前端显示值 / API 响应值 / DB 落库值 / 导出文件值**四处逐字节相同**（AC-15） |
| payload 位数 | 前端发 10 位（Task F5），后端落库规整 6 位 —— 这是**约定好的不对称**，不是 bug |
