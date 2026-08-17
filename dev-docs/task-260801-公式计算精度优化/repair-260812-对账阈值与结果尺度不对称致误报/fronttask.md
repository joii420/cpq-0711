# fronttask.md — repair-0812 对账阈值与结果尺度不对称致误报（前端）

- **归属**：`dev-docs/task-260801-公式计算精度优化/repair-260812-对账阈值与结果尺度不对称致误报/`
- **上位文档**：`问题说明.md`（§5 已裁决方案 = 比较入口归一到结果精度 9 位）
- **本次性质**：**纯前端判定层修复**，后端零改动、接口零改动、不改数、无 DDL

---

## 1. 改动点清单（就 1 个函数 + 1 处注释 + 1 组单测）

| # | 文件 | 位置 | 改什么 |
|---|---|---|---|
| **F1** | `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | `valuesReconcile`（约 `:2744-2750`） | 比较前把两侧值按 `formatFormulaResult`（`FORMULA_RESULT_SCALE = 9`）归一，再交给 `isWithinTolerance`（容差**仍用默认 1e-12**） |
| **F2** | 同上 | 顶部 `from '../../utils/precision'` 的 import 块（约 `:36-45`） | 追加 `formatFormulaResult`（当前未引入） |
| **F3** | `cpq-frontend/src/utils/formulaEngine.ts` | `isWithinTolerance`（约 `:827-833`） | **只加注释**，不改默认值：写明「默认容差是**同尺度**末位噪声容差；**跨尺度比较必须先归一**」 |
| **F4** | `cpq-frontend/src/utils/formulaEngine.test.ts`（或就近新建 `reconcileScale.test.ts`） | — | 补 `valuesReconcile` 口径的单测（正例 + **负例**，即补齐 task-0806 未执行的 TC-121） |
| **F5** | `task-0810/需求文档.md:82`、`task-0810/fronttask.md:69`、`task-0806/需求文档.md` D4 | — | 文档改写（FR-12 → 按结果精度判定）+ 演进史留痕 |

> **不改**：`isWithinTolerance` 的默认参数值（改它会波及所有调用方）；`computeAllFormulas` / `formulaEngine.evaluateExpression` 的产出尺度（**改它会改数**，见 `问题说明.md` §5「明确排除的错误做法」）；`reconcileTab` 的上报语义（整页签替换 + 全量上报，保持不动）。

---

## 2. 目标代码

### F1 + F2 — `QuotationStep2.tsx`

```ts
// import 块追加（F2）
import {
  isDecimalString,
  formatFormulaResult,        // ← 新增
  formatProductCardSubtotal,
  normalizeDecimalString,
  sumDecimal,
  toCalculationString,
  toDecimal,
  type DecimalString,
  type DecimalValue,
} from '../../utils/precision';
```

```ts
/**
 * D4 判定（repair-0812 改）：**先把两侧归一到结果精度（FORMULA_RESULT_SCALE = 9），再比**。
 *
 * 为什么必须归一：前端 formulaCache 是 12 位工作值（toCalculationString），后端快照
 * formulaResults 是 9 位结果值（PrecisionPolicy.roundFormulaResult）——两侧尺度天然差 3 位，
 * 差值上界 5e-10，而默认容差 1e-12 比它小 500 倍 ⇒ 不归一就恒告警且永不消解。
 * 归一后两侧同尺度，1e-12 只用来吸收同尺度下的末位噪声。
 * 非数字按字符串比、一边缺失算差异 —— 与改动前一致。
 */
const valuesReconcile = (a: any, b: any): boolean => {
  const an = precisionValue(a);
  const bn = precisionValue(b);
  if (an != null && bn != null) {
    return isWithinTolerance(formatFormulaResult(an), formatFormulaResult(bn));
  }
  if ((a == null || a === '') && (b == null || b === '')) return true;
  if ((a == null || a === '') !== (b == null || b === '')) return false; // 一边有值一边缺失
  return String(a) === String(b);
};
```

**要点**：
- `formatFormulaResult` 对后端已是 9 位的值是**幂等**的（`toDecimalPlaces(9)` 再 `toFixed(9)` 去尾零），不会引入新偏差。
- 只改**比较**，不改 `feVal` / `beVal` 本身 —— tooltip 与上报报文里仍是**原始值**（12 位 / 9 位），保留诊断信息，不掩盖现场。

### F3 — `formulaEngine.ts`

```ts
/**
 * 数值一致性判定。
 *
 * 🚨 默认容差 1e-12 是「**同尺度**末位噪声容差」（两侧都在 CALCULATION_SCALE=12 上时）。
 * **跨尺度比较必须先把两侧归一到同一尺度再调本函数**（如前端 12 位工作值 vs 后端 9 位
 * 结果值，须先 formatFormulaResult 归一）——否则结构性尺度差恒 > 容差，判定必然恒 false。
 * 反例见 repair-0812：不归一直接吃默认容差 ⇒ 报价编辑全屏误报 + 提交闸门 409 卡死。
 */
export function isWithinTolerance(...)   // 实现不变
```

---

## 3. 交互流程（不变，仅结果变）

```
单元格 onBlur
  → handleSnapshotCellEdit
  → PUT .../line-items/{id}/quote-card-edit（后端重算 + 回灌 quoteCardValues）
  → reconcileTab(qcvJson, lineItemId)
       ├ 逐行逐 FORMULA 字段：valuesReconcile(前端 formulaCache 值, 后端 formulaResults 值)
       │    └ ⬅ 本次唯一改动点：比较前归一到 9 位
       ├ 不一致 → 写 reconcileDiffs（ComponentCell 渲染 ⚠ + tooltip）
       └ 全量上报 POST reconcile-report（跨页签累积，整页签替换语义）
  → 提交时后端 SubmitGateService 查 pending Map，非空 → 409
```

**预期变化**：口径差引起的差异不再产生 → `reconcileDiffs` 为空 → 上报空数组 → `ReconcileDiffStore.report()` 走 `pending.remove()` → **提交闸门自然放行，无需重启后端**。

---

## 4. 状态管理与缓存

- 不新增 state、不新增缓存、**不动任何 cache key**（`driverExpansionKey` / `fieldsOverrideHash` / `globalPathCache` 全不涉及）。
- `reconcileDiffs` state 的结构与生命周期不变。

---

## 5. 调用接口

**无接口新增/变更**，仍用既有两个（契约见本目录 `api.md`）：

| 编号 | 方法 + 路径 | 本次是否改动 |
|---|---|---|
| A-1 | `PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` | ❌ 不改 |
| A-2 | `POST /api/cpq/quotations/line-items/{lineItemId}/reconcile-report` | ❌ 不改（请求体字段、语义全不变） |

---

## 6. 边界与空态（全部保持改动前语义，需回归验证）

| 场景 | 期望 | 说明 |
|---|---|---|
| 后端快照缺该 `rowKey`（新行） | **不算差异** | `reconcileTab` 里 `if (!beValues) continue`，本次不动 |
| 一边有值、一边 null/`''` | **算差异** | 归一分支不接管，走原有 null 判定 |
| 两边都 null/`''` | 判一致 | 同上 |
| 非数字（文本字段误入） | 走 `String(a) === String(b)` | `precisionValue` 返回 null → 不进归一分支 |
| 手动行（`expIndex < 0`） | `backendInputs` 留空、比较逻辑不变 | 不动 |
| 跨页签累积差异 | 仍全量上报 | `merged` 逻辑不动 |
| 非 DRAFT | 前端读快照值，两侧同源 → 恒一致 | 不受影响 |
| 核价侧 | `useSnapEdit` 恒 false，不走对账 | 不受影响 |

---

## 7. 自检项（**完成前必须逐条跑，写进 `test-report.md`**）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.app.json` → **0 错误**
      （⚠️ 用 `tsconfig.app.json`，根 `tsconfig.json` 是空转的，见 `BL-0085`）
- [ ] `curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:<port>/src/pages/quotation/QuotationStep2.tsx` → **200**
      （⚠️ worktree 里**不要**用共享的 5174——它服务主工作区代码，SPA fallback 对任何路径都返 200 = 假阳性；起临时 Vite 另开端口，并**校验返回内容确实含本次改动**）
- [ ] `npm test`（`vitest run src`）→ 精度 / 公式 / 快照 / 折扣 / **对账**用例全绿，无新增失败
- [ ] **E2E 强制**：`QuotationStep2.tsx` 在 `CLAUDE.md`「协议级改动」清单内 → 跑 `e2e/quotation-flow.spec.ts`，须见 `'加载中' final count = 0`
      （⚠️ 已知夹具风险：`BL-0078` / `BL-0158` 族——该 spec 硬编码的客户/模板在现库可能 0 命中。**若卡在选客户步骤，必须在干净 master 上同环境跑一次做 A/B 对照**，确认是夹具枯竭而非本次回归，并如实写进报告，不得含糊标"通过"）
- [ ] **真机亲验**（不采信子代理结论）：`QT-20260811-0170` → 物料页签 → 改「材料净重」失焦 → ⚠ 数 = 0 → 点提交不再 409（**且全程未重启后端**）

---

## 8. Task 列表（逐项勾选）

- [ ] **F1** 改 `valuesReconcile`：两侧 `formatFormulaResult` 归一后再比，容差仍用默认
- [ ] **F2** `precision` import 追加 `formatFormulaResult`
- [ ] **F3** `isWithinTolerance` 补「同尺度容差 / 跨尺度须先归一」注释（**不改默认值**）
- [ ] **F4** 补单测：正例（第 8 位差 → 不一致）+ **负例**（仅第 10~12 位差 → 一致，补齐 task-0806 TC-121）+ 边界（第 10 位进位）+ 回归（空值/非数字分支）
- [ ] **F5** 文档改写：`task-0810/需求文档.md:82` FR-12 → 「按结果精度 `FORMULA_RESULT_SCALE` 判定」；`task-0810/fronttask.md:69` 同步；`task-0806/需求文档.md` D4 对齐；三处均留演进史说明推翻原因
- [ ] **F6** 跑完 §7 全部自检，证据落 `test-report.md`
