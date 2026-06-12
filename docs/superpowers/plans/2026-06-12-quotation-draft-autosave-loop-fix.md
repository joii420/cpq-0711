# 报价单草稿自动保存死循环修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除进入报价单草稿后 `PUT /quotations/{id}/draft` 自动保存无限重复(空闲也连续 PUT)的反馈环,做到"空闲 0 次保存 / 真实编辑恰好 1 次保存"。

**Architecture:** 纯前端修 `QuotationWizard.tsx` 的自动保存调度。三层组合:① `syncingRef` guard 切断"saveDraft 响应回填 → setLineItems → 再次调度保存"这条边;② `autoSaveDraft` 改用 ref 读 `lineItems`/`driverExpansions`,精简 useCallback 依赖消除函数引用 churn;③ payload 去重前对数值规范化(`toFixed(4)`)断 live↔snap 浮点尾差导致的去重失效。求值/渲染逻辑不动。

**Tech Stack:** React 18 + TypeScript + Ant Design + Playwright(E2E)。

**Spec 来源:** cpq-architect 设计(本会话);根因见 RECORD 反馈环分析。

**前置(执行时):** 隔离 worktree 分支(`superpowers:using-git-worktrees`,原生 EnterWorktree)。worktree 内 `cpq-frontend/node_modules` symlink 复用主工作区,不重装。复用主工作区 dev server(5174/8081)做 E2E,不在 worktree 另起 server。

**协议警示(AP-31 强制 E2E 区):** `QuotationWizard.tsx` 是 AP-31 协议级文件。改完**必须**跑 `quotation-flow.spec.ts`,且本计划新增"PUT /draft 次数"断言。

**测试命令约定:**
- 单测:`cd cpq-frontend && npx vitest run <test 文件>`
- 类型:`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
- E2E:`cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` | 自动保存调度:syncingRef guard + ref 化依赖 + payload 数值规范化 | 修改 |
| `cpq-frontend/src/pages/quotation/buildDraftPayload.ts` *(若 buildDraftPayload 为内联函数则留在 Wizard 内)* | payload 数值规范化纯函数(供单测) | 视现状新建或内联 |
| `cpq-frontend/src/pages/quotation/__tests__/draftPayloadNormalize.test.ts` | payload 规范化单测 | 新建 |
| `cpq-frontend/e2e/quotation-flow.spec.ts` | 加 PUT /draft 次数断言 | 修改 |

> **执行前先读真实代码确认结构**:`buildDraftPayload` / `autoSaveDraft` / `scheduleAutoSave` / `syncLineItemsFromResponse` / `driverExpansionsSnap` / `driverExpansions` / 自动保存 effect 当前是内联还是独立导出。架构给出的行号(autoSaveDraft~581、syncLineItemsFromResponse~593、setLineItems~551、useSnapAll~109、driverExpansions~116、lineItems effect~190、useCallback deps~611、buildDraftPayload~697、lastSaveRef 去重~586/587、subtotal~742、rowData snapshotRows~774、import-auto-save effect~625)为参考,以实际为准。

---

## Task 1: 抽 payload 数值规范化纯函数 + 单测(TDD)

**Files:**
- Modify/Create: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`(或新建 `buildDraftPayload.ts` 容纳可测纯函数)
- Test: `cpq-frontend/src/pages/quotation/__tests__/draftPayloadNormalize.test.ts`

抽一个 `normalizeDraftPayloadNumbers(payload)` 纯函数:把 payload 内所有数值字段(尤其 `subtotal` / `rowData` 内数值)统一 `Number(x).toFixed(4)` 后回填(或递归规范化),使 live 与 snap 两种 driverExpansions 入参产出的 payload **去重字符串一致**。这是断环的关键(③)。

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { normalizeDraftPayloadNumbers } from '../QuotationWizard'; // 或 '../buildDraftPayload'

describe('normalizeDraftPayloadNumbers — 浮点尾差规范化', () => {
  it('subtotal 浮点尾差规范化后相等', () => {
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 25.40999999998, rowData: [] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 25.41000000001, rowData: [] }] });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });
  it('rowData 内数值字段同样规范化', () => {
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0, rowData: [{ 金额: 1.1500000001 }] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0, rowData: [{ 金额: 1.1499999999 }] }] });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });
  it('非数值字段原样保留', () => {
    const r = normalizeDraftPayloadNumbers({ name: '料8', lineItems: [{ subtotal: 1, rowData: [{ 料件: '料8' }] }] });
    expect(r.name).toBe('料8');
    expect(r.lineItems[0].rowData[0]['料件']).toBe('料8');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/__tests__/draftPayloadNormalize.test.ts`
Expected: FAIL(函数未导出)。

- [ ] **Step 3: 实现 normalizeDraftPayloadNumbers**

在 `QuotationWizard.tsx`(或 `buildDraftPayload.ts`)实现并 `export`:

```ts
/** 递归把所有数值规范化为 4 位定点,消除 live↔snap 求值浮点尾差,保证 payload 去重稳定。 */
export function normalizeDraftPayloadNumbers<T>(payload: T): T {
  const norm = (v: any): any => {
    if (typeof v === 'number') return Number.isFinite(v) ? Number(v.toFixed(4)) : v;
    if (Array.isArray(v)) return v.map(norm);
    if (v && typeof v === 'object') {
      const o: any = {};
      for (const k of Object.keys(v)) o[k] = norm(v[k]);
      return o;
    }
    return v;
  };
  return norm(payload);
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/__tests__/draftPayloadNormalize.test.ts`
Expected: 3 passed。

- [ ] **Step 5: 接入去重比较**

在 `autoSaveDraft` 里,`lastSaveRef` 去重比较前对 payload 应用规范化(只改用于**比较去重的字符串**,不改真正 PUT 的 body — 或两者都用规范化值,二选一保持一致;推荐两者都用规范化后的对象,后端接受 4 位定点):

```ts
const payloadNorm = normalizeDraftPayloadNumbers(payload);
const payloadStr = JSON.stringify(payloadNorm);
if (payloadStr === lastSaveRef.current) return; // 去重命中,不 PUT
lastSaveRef.current = payloadStr;
// PUT 用 payloadNorm(或原 payload,但比较与发送口径一致)
```

- [ ] **Step 6: 类型 + transform**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/quotation/QuotationWizard.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: tsc 0;TRANSFORM_OK。

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx cpq-frontend/src/pages/quotation/__tests__/draftPayloadNormalize.test.ts
git commit -m "fix(quotation): draft payload 数值规范化断浮点尾差(自动保存去重稳定)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: syncingRef guard 切断"回填→再调度保存"边

**Files:** Modify `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`

`syncLineItemsFromResponse` 回填 `quoteCardValues` 是必要的(详情/Excel 首屏一致),但它触发的 `setLineItems` 不应再调度一次自动保存。用 `syncingRef` 标记,让监听 `lineItems` 的调度 effect 跳过这一次。

- [ ] **Step 1: 加 syncingRef + 标记**

在组件内加:
```ts
const syncingRef = useRef(false);
```

在 `syncLineItemsFromResponse` 内、调用 `setLineItems` **之前**置位:
```ts
const syncLineItemsFromResponse = (resData: any) => {
  const respLines = resData?.lineItems;
  if (!Array.isArray(respLines)) return;
  syncingRef.current = true;        // 标记:本次 lineItems 变化来自 saveDraft 回填
  setLineItems(prev => { /* …原 patch 逻辑不变… */ });
};
```

- [ ] **Step 2: 调度 effect 消费并复位**

定位监听 lineItems 调度自动保存的 effect(架构参考 ~line 190 `useEffect([lineItems, quotationId])` → `scheduleAutoSave()`),开头加守卫(读到 true 即消费复位并 return,**不用定时复位**,避免吞掉真实编辑):

```ts
useEffect(() => {
  if (!quotationId) return;
  if (lineItems.length === 0) return;
  if (syncingRef.current) {          // 回填触发的变化:消费一次,不调度
    syncingRef.current = false;
    return;
  }
  scheduleAutoSave();
}, [lineItems, quotationId]);
```

> **保真纪律**:用户真实编辑(handleRowChange / onValuesChange)走的是另一条 setLineItems,`syncingRef=false`,正常调度。务必确认回填路径与编辑路径是**不同的 setLineItems 调用**,guard 不会误吞真实编辑。

- [ ] **Step 3: 类型 + transform**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/quotation/QuotationWizard.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: tsc 0;TRANSFORM_OK。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "fix(quotation): syncingRef 切断 saveDraft 回填触发再次自动保存的反馈环

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: autoSaveDraft 用 ref 读 lineItems/driverExpansions,精简 useCallback 依赖

**Files:** Modify `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`

消除"lineItems/driverExpansions 变 → autoSaveDraft 重建 → autoSaveDraftRef effect 重跑"的引用 churn 放大边。

- [ ] **Step 1: 加 ref 镜像**

```ts
const lineItemsRef = useRef(lineItems);
lineItemsRef.current = lineItems;
const driverExpansionsRef = useRef(driverExpansions);
driverExpansionsRef.current = driverExpansions;
```

- [ ] **Step 2: autoSaveDraft 内部改读 ref + 收敛 deps**

把 `autoSaveDraft`(及其调用的 `buildDraftPayload`)内部对 `lineItems` / `driverExpansions` 的读取改为 `lineItemsRef.current` / `driverExpansionsRef.current`;useCallback deps 从 `[quotationId, form, lineItems, driverExpansions, customerIdValue]` 收敛为 `[quotationId, form, customerIdValue]`(只保留真正稳定/必要项;`form` 来自 antd 稳定;`customerIdValue` 若每次新建需一并 ref 化或确认稳定)。

> **不破坏 import-auto-save effect**:架构指出导入自动保存 effect(~line 625)单独 watch `driverExpansions` state(不是 autoSaveDraft 内部 ref),收敛 autoSaveDraft deps 不影响它。改完确认该 effect 仍能在 driverExpansions ready 时触发一次导入保存。

- [ ] **Step 3: 类型 + transform**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/quotation/QuotationWizard.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: tsc 0;TRANSFORM_OK。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "fix(quotation): autoSaveDraft 用 ref 读 lineItems/driverExpansions 收敛 useCallback 依赖

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: E2E 加 PUT /draft 次数断言 + 全量回归

**Files:** Modify `cpq-frontend/e2e/quotation-flow.spec.ts`

- [ ] **Step 1: 加请求计数断言**

在现有 `quotation-flow.spec.ts` 测试里(进入报价单草稿后),挂请求监听计数 PUT /draft:

```ts
let draftPutCount = 0;
page.on('request', (req) => {
  if (req.method() === 'PUT' && /\/quotations\/[^/]+\/draft/.test(req.url())) draftPutCount += 1;
});
// …进入草稿、等待初始加载稳定(现有等待逻辑之后)…
const before = draftPutCount;
await page.waitForTimeout(5000); // 空闲 5s,不做任何操作
const idle = draftPutCount - before;
console.log(`[draft] idle PUT count over 5s = ${idle}`);
expect(idle).toBeLessThanOrEqual(1); // 空闲应 0(放宽到 ≤1 容忍初始一次)
```

(可选增强:真实改一个输入框 → 等 1.8s → 断言新增恰好 1 次 PUT。若现有 spec 已有编辑步骤可复用。)

- [ ] **Step 2: 跑 E2E(必须复用主工作区 dev server)**

> 注意:E2E 跑的是主工作区 5174 服务的代码。本修复在 worktree 内,**E2E 实证留到合并回 master 后跑**(worktree 改动对共享 5174 不可见)。本 Task 在合并后执行;合并前先靠 tsc+transform+单测自检。

合并后 Run:
```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`;`idle PUT count over 5s = 0`(或 ≤1);`'加载中' final count = 0`;全 8 Tab `加载中=0`(无渲染回归)。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts
git commit -m "test(e2e): 断言报价单草稿空闲不重复 PUT /draft

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 自检 + 真机验收点

- [ ] **Step 1: 合并前自检**

Run:
```bash
cd cpq-frontend
npx vitest run src/pages/quotation/__tests__/draftPayloadNormalize.test.ts
npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/quotation/QuotationWizard.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: 单测 passed;tsc 0;TRANSFORM_OK。

- [ ] **Step 2:(合并后)E2E + 真机**

E2E 见 Task 4 Step 2。真机:用 `3e959537-247d-4438-b642-e12f1d8940ca`(QT-20260612-1697)打开草稿,F12 Network 过滤 `/draft`——空闲应 **0 次 PUT**;手改一个输入框 → 1.5s 后恰 **1 次 PUT**,之后不再重复。

- [ ] **Step 3: 完成宣告(附"已自检"声明行)**

例:
> 单测 3 passed ✅;tsc 0 ✅;QuotationWizard TRANSFORM_OK ✅;E2E quotation-flow 1 passed + idle PUT=0 + 加载中=0 ✅;真机 F12 空闲 0 PUT、编辑 1 PUT(用户验收)。

---

## 真机验收(用户)
- 进报价单草稿空闲时,F12 Network 不再连续 PUT `/draft`(0 次);
- 手改一个单元格 → 恰好 1 次 PUT,不连环;
- 报价单渲染、小计、各 Tab 无回归。

## 收尾
按 `cpq-auto-finish-merge-e2e-cleanup` 习惯:评审通过即自动合并 master + 跑 E2E + 清理 worktree。
