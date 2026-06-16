# 字段属性「小计 / 金额 / 行键」渲染修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正报价单/核价单/详情三视图中「小计行」「本页签金额合计行」的渲染判定，使其严格按 `is_subtotal` / `is_amount` 两个字段属性工作；并在组件管理强制「金额 ⊆ 小计」勾选约束。

**Architecture:** 纯前端渲染层 + 一个纯函数改动。不碰公式计算引擎、不碰数据层求和（`buildColumnSumsByComp` 维持原谓词，仅在渲染层 gate）、不迁移存量。两处渲染（`QuotationStep2.tsx` 编辑视图 = 报价/核价共用，`ReadonlyProductCard.tsx` 详情只读）做对称改动。

**Tech Stack:** React + TypeScript + Ant Design；Vitest（单测）；Playwright（E2E）。

**Spec:** `docs/superpowers/specs/2026-06-16-subtotal-amount-rowkey-field-attributes-design.md`

**关键纪律:**
- ❌ 禁止改公式引擎（`FormulaCalculator.java` / `computeAllFormulas` / cross_tab / `component_subtotal` token / 行键分组）。
- ❌ 禁止改 `buildColumnSumsByComp` 数据层谓词（保持 numeric，渲染层 gate）。
- ✅ 行键（row_key_fields）逻辑本次零改动。
- ✅ `is_amount && is_subtotal` 是金额合计的过滤条件（M1 保险）。
- ⚠️ 改 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` 属协议级 → 强制跑 Playwright E2E。

---

## 文件结构

| 文件 | 责任 | 本计划改动 |
|---|---|---|
| `cpq-frontend/src/pages/quotation/tabTotalLines.ts` | 「本页签金额合计」纯函数 | 过滤 `is_amount && is_subtotal` + 类型补 `is_amount` + O1 注释（Task 1） |
| `cpq-frontend/src/pages/quotation/tabTotalLines.test.ts` | 上者单测 | 更新断言 + 新增金额语义用例（Task 1） |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | 报价/核价编辑视图 ProductCard 渲染 | 小计行 gate / 金额格式 / 合计行 gate+改名（Task 2） |
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | 详情只读视图渲染 | 与 Task 2 对称（Task 3） |
| `cpq-frontend/src/pages/component/FieldConfigTable.tsx` | 组件管理字段配置勾选框 | 金额置灰 + 取消小计联动清金额（Task 4） |

---

## Task 1: `sumTabColumns` 改为只汇总金额列（TDD）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/tabTotalLines.ts`
- Test: `cpq-frontend/src/pages/quotation/tabTotalLines.test.ts`

- [ ] **Step 1: 改测试为新语义（先让它失败）**

把 `tabTotalLines.test.ts` 整体替换为下面内容（给原 is_subtotal 列补 `is_amount`，并加金额语义 + M1 保险用例）：

```typescript
import { describe, it, expect } from 'vitest';
import { sumTabColumns } from './tabTotalLines';

const sub = {
  '投料#材料费': 40, '投料#加工费': 22, 'TOULIAO#材料费': 40, 'TOULIAO#加工费': 22,
  '元素#小计': 0, 'ELEM#小计': 0,
};

describe('sumTabColumns（仅汇总 is_amount && is_subtotal 列）', () => {
  it('多金额列之和', () => {
    const comp = {
      tabName: '投料', componentCode: 'TOULIAO',
      fields: [
        { name: '材料费', is_subtotal: true, is_amount: true },
        { name: '加工费', is_subtotal: true, is_amount: true },
        { name: '数量' },
      ],
    };
    expect(sumTabColumns(comp as any, sub)).toBe(62);
  });

  it('小计但非金额的列不计入', () => {
    const comp = {
      tabName: '投料', componentCode: 'TOULIAO',
      fields: [
        { name: '材料费', is_subtotal: true, is_amount: true },   // 计入
        { name: '加工费', is_subtotal: true },                     // 仅小计、非金额 → 不计入
      ],
    };
    expect(sumTabColumns(comp as any, sub)).toBe(40);
  });

  it('M1 保险：is_amount=true 但 is_subtotal=false 不计入', () => {
    const comp = {
      tabName: '投料', componentCode: 'TOULIAO',
      fields: [{ name: '材料费', is_amount: true }],  // 无 is_subtotal → 不计入
    };
    expect(sumTabColumns(comp as any, sub)).toBe(0);
  });

  it('componentCode 缺失时回退 tabName 键', () => {
    const comp = { tabName: '投料', fields: [{ name: '材料费', is_subtotal: true, is_amount: true }] };
    expect(sumTabColumns(comp as any, sub)).toBe(40);
  });

  it('无金额列 → 0', () => {
    const comp = { tabName: '来料', componentCode: 'LL', fields: [{ name: '品名' }, { name: '规格' }] };
    expect(sumTabColumns(comp as any, sub)).toBe(0);
  });

  it('键缺失时计 0', () => {
    const comp = { tabName: '未知', componentCode: 'X', fields: [{ name: '小计', is_subtotal: true, is_amount: true }] };
    expect(sumTabColumns(comp as any, sub)).toBe(0);
  });

  it('undefined 组件 → 0', () => {
    expect(sumTabColumns(undefined, sub)).toBe(0);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/tabTotalLines.test.ts`
Expected: FAIL —「小计但非金额的列不计入」「M1 保险」用例红（现实现仍按 is_subtotal 累加，会把非金额列也加进来 / 把无小计的金额列也加）。

- [ ] **Step 3: 改实现**

把 `tabTotalLines.ts` 整体替换为：

```typescript
/**
 * 「本页签金额合计」（页签底部那行）= 该页签所有「金额列」(is_amount && is_subtotal) 的列小计之和。
 *
 * ⚠️ 此行仅用于显示，**不参与任何公式计算**，没有任何 token 读它。
 * 切勿与公式引擎的「组件小计 component_subtotal」混淆 —— 后者 = 该页签所有 is_subtotal 列之和，
 * 存于 allComponentSubtotals[tabName]（裸键），喂产品小计 / 跨页签公式。两者改本设计后会分叉：
 * 本行显示 = 金额列之和；引擎值仍 = 小计列之和。改本函数不触公式引擎。
 */
interface CompLike {
  componentType?: string;
  tabName: string;
  componentCode?: string;
  fields?: Array<{ name: string; is_subtotal?: boolean; is_amount?: boolean }>;
}

/**
 * @param comp        当前页签组件
 * @param subtotalMap per-column 列小计字典，键 `${componentCode}#${列名}` / `${tabName}#${列名}`
 * @returns 该组件所有「金额列」(is_amount && is_subtotal) 的列小计之和（无金额列 / 空组件 → 0）
 */
export function sumTabColumns(
  comp: CompLike | undefined,
  subtotalMap: Record<string, number>,
): number {
  if (!comp?.fields) return 0;
  let total = 0;
  for (const f of comp.fields) {
    // M1 保险：金额合计只认「既是金额、又有真实小计值」的列（per-column 小计仅为 is_subtotal 列写入）
    if (!(f.is_amount && f.is_subtotal)) continue;
    total += subtotalMap[`${comp.componentCode}#${f.name}`]
          ?? subtotalMap[`${comp.tabName}#${f.name}`] ?? 0;
  }
  return total;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/tabTotalLines.test.ts`
Expected: PASS（7 passed）。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/tabTotalLines.ts cpq-frontend/src/pages/quotation/tabTotalLines.test.ts
git commit -m "feat(quotation): 本页签金额合计仅汇总金额列(is_amount&&is_subtotal)+O1改名注释"
```

---

## Task 2: `QuotationStep2.tsx`（报价/核价编辑视图）渲染修正

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（小计行 footer，约 2533-2589）

> 说明：本任务为 JSX 渲染层改动，无法用 Vitest 单测覆盖（行级渲染依赖运行时 driver 展开），统一由 Task 5 的 `tsc + Vite 200 + Playwright E2E + 三视图人工验收` 验证。每步只做一处精确替换。

- [ ] **Step 1: C1 — 小计行单元格判定改为「仅 is_subtotal」**

把约 2554-2558 的：

```tsx
                        const isNumericCol =
                          field.is_subtotal ||
                          field.field_type === 'INPUT_NUMBER' ||
                          field.field_type === 'FORMULA' ||
                          field.field_type === 'DATA_SOURCE';
```

替换为：

```tsx
                        // C1：小计行只对勾选了 is_subtotal 的列求和；非小计列一律留空。
                        const isNumericCol = !!field.is_subtotal;
```

- [ ] **Step 2: C1 — 小计行整体显示门槛改为「有 is_subtotal 列」**

把约 2539-2544 的：

```tsx
                {activeComponent.fields.some(f =>
                  f.is_subtotal ||
                  f.field_type === 'INPUT_NUMBER' ||
                  f.field_type === 'FORMULA' ||
                  f.field_type === 'DATA_SOURCE'
                ) && (
```

替换为：

```tsx
                {activeComponent.fields.some(f => f.is_subtotal) && (
```

- [ ] **Step 3: C2 — 金额列小计单元格格式改为「¥ + 通用精度」**

把约 2562-2564 的：

```tsx
                          const text = field.is_amount === true
                            ? formatCurrency(v)
                            : (v === 0 ? '0' : parseFloat(v.toFixed(4)).toString());
```

替换为：

```tsx
                          // C2：金额列 = ¥ + 通用精度（与其它小计列同款 4 位去末尾 0，仅多 ¥ 前缀）
                          const plain = v === 0 ? '0' : parseFloat(v.toFixed(4)).toString();
                          const text = field.is_amount === true ? `¥ ${plain}` : plain;
```

- [ ] **Step 4: C3 — 本页签金额合计行 gate 改 is_amount + label 改名**

把约 2578-2587 的：

```tsx
                    {/* 本页签总计 = 该页签多个 is_subtotal 列之和（成本列汇总；输入量列不并入） */}
                    {activeComponent.fields.some(f => f.is_subtotal) && (
                      <tr className="qt-subtotal-row qt-tab-total-row">
                        {activeComponentBomTree && (<><td /><td /><td /></>)}
                        <td className="qt-subtotal-label-cell">本页签总计</td>
                        <td colSpan={activeComponent.fields.length} className="qt-subtotal-cell" style={{ textAlign: 'right' }}>
                          {formatCurrency(sumTabColumns(activeComponent as any, allComponentSubtotals))}
                        </td>
                      </tr>
                    )}
```

替换为：

```tsx
                    {/* 本页签金额合计 = 该页签所有金额列(is_amount&&is_subtotal)之和；无金额列整行隐藏 */}
                    {activeComponent.fields.some(f => f.is_amount) && (
                      <tr className="qt-subtotal-row qt-tab-total-row">
                        {activeComponentBomTree && (<><td /><td /><td /></>)}
                        <td className="qt-subtotal-label-cell">本页签金额合计</td>
                        <td colSpan={activeComponent.fields.length} className="qt-subtotal-cell" style={{ textAlign: 'right' }}>
                          {formatCurrency(sumTabColumns(activeComponent as any, allComponentSubtotals))}
                        </td>
                      </tr>
                    )}
```

- [ ] **Step 5: tsc 校验 + Vite transform 校验**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: tsc 0 错误；curl 返回 `200`。

- [ ] **Step 6: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quotation): 编辑视图小计行仅is_subtotal+金额列¥精度+金额合计行(QuotationStep2)"
```

---

## Task 3: `ReadonlyProductCard.tsx`（详情只读视图）对称修正

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（footer，约 638-690）

> 与 Task 2 对称（AP-50：三视图一致，防详情页僵尸数据）。

- [ ] **Step 1: C1 — 小计行单元格判定改为「仅 is_subtotal」**

把约 657-661 的：

```tsx
                        const isNumericCol =
                          field.is_subtotal ||
                          field.field_type === 'INPUT_NUMBER' ||
                          field.field_type === 'FORMULA' ||
                          field.field_type === 'DATA_SOURCE';
```

替换为：

```tsx
                        // C1：小计行只对勾选了 is_subtotal 的列求和；非小计列一律留空。
                        const isNumericCol = !!field.is_subtotal;
```

- [ ] **Step 2: C1 — 小计行整体显示门槛改为「有 is_subtotal 列」**

把约 644-649 的：

```tsx
                {activeComp.fields.some(f =>
                  f.is_subtotal ||
                  f.field_type === 'INPUT_NUMBER' ||
                  f.field_type === 'FORMULA' ||
                  f.field_type === 'DATA_SOURCE'
                ) && (
```

替换为：

```tsx
                {activeComp.fields.some(f => f.is_subtotal) && (
```

- [ ] **Step 3: C2 — 金额列小计单元格格式改为「¥ + 通用精度」**

把约 665-667 的：

```tsx
                          const text = field.is_amount === true
                            ? formatCurrency(v)
                            : (v === 0 ? '0' : parseFloat(v.toFixed(4)).toString());
```

替换为：

```tsx
                          // C2：金额列 = ¥ + 通用精度（与其它小计列同款 4 位去末尾 0，仅多 ¥ 前缀）
                          const plain = v === 0 ? '0' : parseFloat(v.toFixed(4)).toString();
                          const text = field.is_amount === true ? `¥ ${plain}` : plain;
```

- [ ] **Step 4: C3 — 本页签金额合计行 gate 改 is_amount + label 改名**

把约 680-688 的：

```tsx
                    {/* 本页签总计 = 该页签多个 is_subtotal 列之和（成本列汇总；输入量列不并入） */}
                    {activeComp.fields.some(f => f.is_subtotal) && (
                      <tr className="qt-subtotal-row qt-tab-total-row">
                        <td className="qt-subtotal-label-cell">本页签总计</td>
                        <td colSpan={Math.max(1, activeComp.fields.length - 1)} className="qt-subtotal-cell" style={{ textAlign: 'right' }}>
                          {formatCurrency(sumTabColumns(activeComp as any, compSubtotals))}
                        </td>
                      </tr>
                    )}
```

替换为：

```tsx
                    {/* 本页签金额合计 = 该页签所有金额列(is_amount&&is_subtotal)之和；无金额列整行隐藏 */}
                    {activeComp.fields.some(f => f.is_amount) && (
                      <tr className="qt-subtotal-row qt-tab-total-row">
                        <td className="qt-subtotal-label-cell">本页签金额合计</td>
                        <td colSpan={Math.max(1, activeComp.fields.length - 1)} className="qt-subtotal-cell" style={{ textAlign: 'right' }}>
                          {formatCurrency(sumTabColumns(activeComp as any, compSubtotals))}
                        </td>
                      </tr>
                    )}
```

- [ ] **Step 5: tsc 校验 + Vite transform 校验**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx
```
Expected: tsc 0 错误；curl 返回 `200`。

- [ ] **Step 6: 提交**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "feat(quotation): 详情视图对称修正小计行/金额列/金额合计行(ReadonlyProductCard)"
```

---

## Task 4: `FieldConfigTable.tsx` 金额⊆小计 勾选约束（C4 / M2）

**Files:**
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`handleSubtotalChange` 约 77-80；金额 Checkbox 约 415-419）

> M2：两处必须一起改，缺一即留下「is_amount=true 但 is_subtotal=false」脏数据入口。

- [ ] **Step 1: 取消小计时联动清金额**

把约 77-80 的：

```tsx
  const handleSubtotalChange = (key: string, checked: boolean) => {
    // 多小计列（Plan 2-核心）：每个字段独立勾选，不再互斥。
    updateField(key, { is_subtotal: checked });
  };
```

替换为：

```tsx
  const handleSubtotalChange = (key: string, checked: boolean) => {
    // 多小计列（Plan 2-核心）：每个字段独立勾选，不再互斥。
    // C4：金额 ⊆ 小计 —— 取消小计时联动清掉金额，杜绝「金额脱离小计」脏数据。
    updateField(key, { is_subtotal: checked, ...(checked ? {} : { is_amount: false }) });
  };
```

- [ ] **Step 2: 金额勾选框在未勾小计时置灰**

把约 415-419 的：

```tsx
        <Checkbox
          checked={!!record.is_amount}
          onChange={(e) => updateField(record.key, { is_amount: e.target.checked })}
        />
```

替换为：

```tsx
        <Checkbox
          checked={!!record.is_amount}
          disabled={!record.is_subtotal}
          onChange={(e) => updateField(record.key, { is_amount: e.target.checked })}
        />
```

- [ ] **Step 3: tsc 校验 + Vite transform 校验**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/FieldConfigTable.tsx
```
Expected: tsc 0 错误；curl 返回 `200`。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(component): 金额⊆小计勾选约束(置灰+取消小计联动清金额)(FieldConfigTable)"
```

---

## Task 5: 全量自检 + E2E + 三视图验收 + 记录

**Files:**
- 验证：上述全部
- Modify: `docs/RECORD.md`（追加开发记录）

- [ ] **Step 1: 全量单测 + tsc**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/quotation/tabTotalLines.test.ts && npx tsc --noEmit -p tsconfig.json
```
Expected: vitest 7 passed；tsc 0 错误。

- [ ] **Step 2: 三个改动 .tsx 的 Vite transform 200**

Run:
```bash
cd cpq-frontend
for f in pages/quotation/QuotationStep2.tsx pages/quotation/ReadonlyProductCard.tsx pages/component/FieldConfigTable.tsx; do
  echo -n "$f -> "; curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:5174/src/$f"
done
```
Expected: 三行均 `200`。

- [ ] **Step 3: Playwright E2E（协议级强制）**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png 2>/dev/null
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`；日志出现 `'加载中' final count = 0`；全部 8 Tab `'加载中'=0`。若 E2E 失败，回到对应 Task 修复后重跑，不得跳过。

- [ ] **Step 4: 三视图人工验收（对照 spec §4 边界）**

逐项确认（报价单 / 核价单 / 详情）：
1. 未勾小计的列（项次/毛重/净重/组成用量/损耗率）在小计行**留空**。
2. 勾了金额的列小计前有 **¥** 且按通用精度（非强制 2 位）。
3. 「本页签金额合计」label 已改名，数值 = 仅金额列之和、¥+2 位；某页签无金额列时该行**隐藏**。
4. 组件管理：未勾小计时金额框**置灰**；取消小计后原金额勾选**自动清除**。

- [ ] **Step 5: 追加开发记录**

在 `docs/RECORD.md` 末尾追加（格式 `[日期] 模块 - 描述 | 文件 | 决策`）：

```
[2026-06-16] 字段属性 - 小计/金额/行键渲染修正：小计行仅汇总 is_subtotal 列(非小计列留空)；本页签总计改名「本页签金额合计」=仅 is_amount&&is_subtotal 列之和(无金额列隐藏)；金额列小计 ¥+通用精度；组件管理强制金额⊆小计(置灰+取消小计联动清金额)。行键/公式引擎/数据层 buildColumnSumsByComp 零改动；不迁移存量。 | tabTotalLines.ts(+test) / QuotationStep2.tsx / ReadonlyProductCard.tsx / FieldConfigTable.tsx | 评审采纳 M1(is_amount&&is_subtotal 保险)/M2(C4两项同PR)/O1(改名+注释)/O3(类型补 is_amount)
```

- [ ] **Step 6: 提交记录 + 自检声明**

```bash
git add docs/RECORD.md
git commit -m "docs(record): 记录小计/金额/行键字段属性渲染修正"
```

报告须含一行自检声明，例如：
> vitest 7 passed ✅；tsc 0 错误 ✅；QuotationStep2/ReadonlyProductCard/FieldConfigTable → Vite 200 ✅；Playwright quotation-flow `1 passed` + `加载中=0` ✅；三视图人工验收四项通过 ✅

---

## 自检（计划对照 spec）

- **C1 非小计列留空** → Task 2 Step1-2 + Task 3 Step1-2 ✅
- **C2 金额列 ¥+通用精度** → Task 2 Step3 + Task 3 Step3 ✅
- **C3 金额合计仅金额列 + 无金额列隐藏 + 改名** → Task 1 + Task 2 Step4 + Task 3 Step4 ✅
- **C4 金额⊆小计（置灰+联动）** → Task 4 ✅
- **M1 `is_amount && is_subtotal` 保险** → Task 1 Step3 ✅
- **O1 改名 + 注释** → Task 1 Step3（注释）+ Task 2/3 Step4（label）✅
- **O3 类型补 is_amount** → Task 1 Step3（`CompLike.fields`）✅
- **不改公式引擎 / buildColumnSumsByComp / 行键 / 存量** → 各 Task 均未触及 ✅
- **三视图覆盖** → 报价/核价（Task 2）+ 详情（Task 3）；Excel 视图本次不动 ✅
- **协议级 E2E** → Task 5 Step3 ✅
