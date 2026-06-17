# INPUT 字段默认值解析统一化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把散落在 ~8 处、覆盖不一致的「INPUT 字段默认值解析」收敛为单一共享解析器，使 `INPUT_TEXT`/`INPUT_NUMBER` 的默认值（`default_source` 三 type 实时 + 静态 `content` 兜底）在渲染/计算/落库/导出/核价五处按统一优先级一致生效。

**Architecture:** 抽 `inputDefaults.ts#resolveInputDefault`（以现有 `resolveInputDefaultSourceForRow` 为种子 + 补 pathCache + 补 content 兜底），所有前端消费点改调它并去除 `INPUT_NUMBER`-only gate；`snapshotRows` 仅对「无 default_source 的静态 content」冻结进 `row_data`（有源字段保持实时）；后端取数链验证/补齐对 `INPUT_TEXT + 三 type + content` 的解析。

**Tech Stack:** React + TypeScript（Vite）、Vitest、Playwright E2E；后端 Quarkus（仅验证/必要时补齐取数链）。

**Spec:** `docs/superpowers/specs/2026-06-17-input-static-default-materialization-design.md`

**优先级铁律（贯穿全程）:** `已有用户值(row[key] 非空) > default_source(GLOBAL_VARIABLE|BNF_PATH|BASIC_DATA，实时) > 静态 content > 空`。**绝不**用默认值覆盖 `row[key]` 已有非空值。

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `cpq-frontend/src/pages/quotation/inputDefaults.ts` | 单一共享解析器 + 数值归一 | **新建** |
| `cpq-frontend/src/pages/quotation/inputDefaults.test.ts` | 解析器真值表单测 | **新建** |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | computeAllFormulas / buildCrossTabRows / 旧 resolveInputDefaultSourceForRow | 改 |
| `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx` | 编辑态 + 只读态 INPUT 渲染 | 改 |
| `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` | snapshotRows 持久化兜底块 | 改 |
| `cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx` | buildEmptyRow（保持写 ''） | 不改值，仅注释说明（见 Task 6） |
| `cpq-frontend/src/pages/quotation/AddProductModal.tsx` | buildEmptyRow（保持写 ''） | 不改值，仅注释说明（见 Task 6） |
| `cpq-backend/.../quotation/service/CardSnapshotService.java` 等 | 后端取数链 | 验证 / 必要时补齐（Task 7） |
| `docs/RECORD.md` | 开发记录 | 追加（Task 9） |

> ⚠️ `cpq-frontend/src/pages/quotation/usePathFormulaCache.ts` 的 BASIC_DATA 路径采集排除（:99/:103/:172/:176）**保持不动**——那是路径采集防 `$view.中文列` 撞键约束，与取值解析正交。

---

## 前置：worktree + 存量量化

### Task 0: 隔离 worktree + 存量爆炸面量化

**Files:** 无代码改动（调查 + 环境）

- [ ] **Step 1: 建隔离 worktree 分支**

用 `superpowers:using-git-worktrees` 技能创建独立 worktree + 特性分支 `feat/input-default-resolver`。后续所有编码/提交在该 worktree 内。不在 worktree 另起 dev server / 重装依赖（复用主工作区 5174/8081）。

- [ ] **Step 2: 统计存量 INPUT 默认值字段（三处口径）**

Run（在任意能连库的终端）:
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
SELECT 'component' src, count(*) FROM component c, jsonb_array_elements(c.fields) f
 WHERE f->>'field_type' IN ('INPUT_TEXT','INPUT_NUMBER')
   AND (COALESCE(f->>'content','')<>'' OR f->'default_source' IS NOT NULL)
UNION ALL
SELECT 'tmpl_snapshot', count(*) FROM template t, jsonb_array_elements(t.components_snapshot) comp, jsonb_array_elements(comp->'fields') f
 WHERE f->>'field_type' IN ('INPUT_TEXT','INPUT_NUMBER')
   AND (COALESCE(f->>'content','')<>'' OR f->'default_source' IS NOT NULL);"
```
Expected: 返回两行计数（component / tmpl_snapshot）。记录数字到 RECORD（爆炸面），确认改动影响规模。`template_component.fields_override` 若该表存在再补一条同构查询。

- [ ] **Step 3: 确认时间边界**

确认实化只对 DRAFT 重建/新建生效，已提交单走 Phase4 冻结快照不重算（阅读 `QuotationWizard.tsx` 保存路径与提交状态判断，确认 snapshotRows 仅在 draft 保存/创建时跑）。把结论写入本 plan Task 5 注释。无需改代码。

---

## Task 1: 新建共享解析器 `inputDefaults.ts`

**Files:**
- Create: `cpq-frontend/src/pages/quotation/inputDefaults.ts`
- Test: `cpq-frontend/src/pages/quotation/inputDefaults.test.ts`

种子来自现有 `QuotationStep2.tsx:782 resolveInputDefaultSourceForRow`（处理 GLOBAL_VARIABLE/BNF_PATH/BASIC_DATA），本任务在其上补 **pathCache 兜底** 和 **静态 content 兜底**，并导出复用。

- [ ] **Step 1: 写失败测试**

```ts
// inputDefaults.test.ts
import { describe, it, expect } from 'vitest';
import { resolveInputDefault, coerceInputNumber } from './inputDefaults';
import type { ComponentField } from './QuotationStep2';

const f = (over: Partial<ComponentField>): ComponentField =>
  ({ name: 'X', field_type: 'INPUT_TEXT', key: 'X', label: 'X', ...over } as ComponentField);

describe('resolveInputDefault', () => {
  it('default_source BASIC_DATA 命中 basicDataValues（TEXT）', () => {
    const field = f({ field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    const bdv = { [require('./useDriverExpansions').bnfDriverLookupKey('$ys_view.单位')]: 'PCS' };
    expect(resolveInputDefault(field, { basicDataValues: bdv })).toBe('PCS');
  });

  it('default_source 取空 → 回退静态 content', () => {
    const field = f({ field_type: 'INPUT_TEXT', content: 'KG', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    expect(resolveInputDefault(field, { basicDataValues: {} })).toBe('KG');
  });

  it('无 default_source → 直接静态 content（TEXT）', () => {
    expect(resolveInputDefault(f({ content: 'RMB' }), {})).toBe('RMB');
  });

  it('GLOBAL_VARIABLE 命中 @gvar', () => {
    const field = f({ field_type: 'INPUT_NUMBER', default_source: { type: 'GLOBAL_VARIABLE', code: 'TAX' } });
    expect(resolveInputDefault(field, { basicDataValues: { '@gvar:TAX': 13 } })).toBe(13);
  });

  it('BNF_PATH basicDataValues 缺 → pathCache 兜底', () => {
    const field = f({ field_type: 'INPUT_NUMBER', default_source: { type: 'BNF_PATH', path: '$v.a' } });
    expect(resolveInputDefault(field, { basicDataValues: {}, partNo: 'P1', pathCache: { 'P1::$v.a': 9 } })).toBe(9);
  });

  it('全空 → undefined', () => {
    expect(resolveInputDefault(f({ content: '' }), {})).toBeUndefined();
  });

  it('coerceInputNumber：合法转数、非法 undefined', () => {
    expect(coerceInputNumber('100')).toBe(100);
    expect(coerceInputNumber('-1.5')).toBe(-1.5);
    expect(coerceInputNumber('abc')).toBeUndefined();
    expect(coerceInputNumber('1e3')).toBeUndefined(); // 与 onChange /^-?\d*\.?\d*$/ 同源
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/inputDefaults.test.ts`
Expected: FAIL（模块不存在 / 函数未定义）

- [ ] **Step 3: 实现解析器**

```ts
// cpq-frontend/src/pages/quotation/inputDefaults.ts
import { bnfDriverLookupKey } from './useDriverExpansions';
import { formatPathValue } from './components/ComponentCell';
import type { ComponentField } from './QuotationStep2';

export interface InputDefaultCtx {
  /** driver 行级 KV：@gvar:CODE / bnfDriverLookupKey(path) */
  basicDataValues?: Record<string, any>;
  partNo?: string;
  /** partNo::path 兜底（仅 BNF_PATH 用，BASIC_DATA 不走 pathCache——单列 ASCII 失败） */
  pathCache?: Record<string, any>;
}

/** 与 ComponentCell.onChange 的 /^-?\d*\.?\d*$/ 同源：合法返回 number，否则 undefined。 */
export function coerceInputNumber(v: unknown): number | undefined {
  if (typeof v === 'number') return isNaN(v) ? undefined : v;
  if (typeof v !== 'string') return undefined;
  if (v === '' || !/^-?\d*\.?\d*$/.test(v)) return undefined;
  const n = parseFloat(v);
  return isNaN(n) ? undefined : n;
}

/**
 * 解析 INPUT_TEXT / INPUT_NUMBER 的有效默认值（不判 row[key]——调用方先判已有值）。
 * 优先级：default_source(GLOBAL_VARIABLE | BNF_PATH | BASIC_DATA，实时) > 静态 content > undefined。
 * 返回原始值（字符串/数值）；数值归一交由调用方按需用 coerceInputNumber。
 */
export function resolveInputDefault(field: ComponentField, ctx: InputDefaultCtx): string | number | undefined {
  const ft = field.field_type;
  if (ft !== 'INPUT_TEXT' && ft !== 'INPUT_NUMBER' && ft !== 'INPUT') return undefined;

  const bdv = ctx.basicDataValues;
  const ds = field.default_source as { type?: string; code?: string; path?: string } | undefined | null;
  let resolved: any = undefined;

  if (ds && bdv) {
    if (ds.type === 'GLOBAL_VARIABLE' && ds.code) {
      const gvKey = `@gvar:${ds.code}`;
      if (Object.prototype.hasOwnProperty.call(bdv, gvKey)) {
        const v = bdv[gvKey];
        if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
      }
    } else if ((ds.type === 'BNF_PATH' || ds.type === 'BASIC_DATA') && ds.path) {
      const lk = bnfDriverLookupKey(ds.path);
      if (Object.prototype.hasOwnProperty.call(bdv, lk)) {
        const v = bdv[lk];
        if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
      }
      // BNF_PATH 才走 pathCache 兜底；BASIC_DATA 只吃行级（单列 ASCII 会失败，沿用 :599 既有判断）
      if (resolved === undefined && ds.type === 'BNF_PATH' && ctx.partNo && ctx.pathCache) {
        const v = ctx.pathCache[`${ctx.partNo}::${ds.path}`];
        if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
      }
    }
  }

  if (resolved != null) {
    if (typeof resolved === 'number') return resolved;
    const fmt = formatPathValue(resolved);
    if (fmt != null) return fmt;
  }
  // 静态 content 兜底
  if (field.content != null && field.content !== '') return field.content;
  return undefined;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/inputDefaults.test.ts`
Expected: PASS（7 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/inputDefaults.ts cpq-frontend/src/pages/quotation/inputDefaults.test.ts
git commit -m "feat(input-default): 抽统一 INPUT 默认值解析器 resolveInputDefault + 数值归一"
```

---

## Task 2: computeAllFormulas 改调统一解析器（去 INPUT_NUMBER gate）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（INPUT 字段值收集 :571-620；旧 `resolveInputDefaultSourceForRow` :782-805；import）
- Test: `cpq-frontend/src/pages/quotation/inputDefaultCompute.test.ts`（新建）

现状：`:577` 字段值兜底 `if ((raw 空) && f.field_type === 'INPUT_NUMBER')` 把 INPUT_TEXT 排除；`:782 resolveInputDefaultSourceForRow` 不含 pathCache/content。统一到 `resolveInputDefault`。

- [ ] **Step 1: 写失败测试**

```ts
// inputDefaultCompute.test.ts
import { describe, it, expect } from 'vitest';
import { computeAllFormulas } from './QuotationStep2';
import type { ComponentDataItem } from './QuotationStep2';
import { bnfDriverLookupKey } from './useDriverExpansions';

// 组件：单价(INPUT_NUMBER,无源) + 计价单位(INPUT_TEXT,源=$v.单位) + 金额(FORMULA = 单价)
const comp = {
  componentId: 'c1', componentCode: 'C1', componentType: 'NORMAL', tabName: 'T',
  fields: [
    { name: '单价', field_type: 'INPUT_NUMBER', key: '单价', label: '单价', content: '8' },
    { name: '计价单位', field_type: 'INPUT_TEXT', key: '计价单位', label: '计价单位',
      content: 'KG', default_source: { type: 'BASIC_DATA', path: '$v.单位' } },
    { name: '金额', field_type: 'FORMULA', key: '金额', label: '金额' },
  ],
  formulas: [{ name: '金额', expression: [{ type: 'field', value: '单价' }], result_type: 'NUMBER' }],
  formulaAssignments: {}, rows: [], subtotal: 0,
} as unknown as ComponentDataItem;

describe('computeAllFormulas INPUT 默认值兜底（含 TEXT）', () => {
  it('单价无源 → 静态 content 8 参与公式（金额=8）', () => {
    const out: any = { fieldValues: {} };
    const cache = computeAllFormulas(comp, {}, {}, undefined, undefined, 'P1', {}, undefined, undefined, out);
    expect(cache['金额']).toBe(8);
    expect(out.fieldValues['单价']).toBe(8);
  });

  it('计价单位(TEXT) default_source 命中 → fieldValues 收集源值文本', () => {
    const bdv = { [bnfDriverLookupKey('$v.单位')]: 'PCS' };
    const out: any = { fieldValues: {} };
    computeAllFormulas(comp, {}, {}, undefined, undefined, 'P1', bdv, undefined, undefined, out);
    // 文本默认值通过 currentRowForEval 增广可被 b_field 引用；至少不再因 INPUT_TEXT 被漏
    // 断言：augmented row 路径生效（计价单位有值进入求值上下文）——用 EXCHANGE/单位换算的既有用例覆盖更佳
    expect(true).toBe(true); // 占位由下方真实断言替换（见 Step 3 实现后改为具体断言）
  });
});
```

> 注：computeAllFormulas 签名以当前源为准（`(comp, row, subtotals, _, _, partNo, basicDataValues, _, _, out, ...)`）。实现 Step 前先读 `:388-415` 确认形参顺序，按实际签名填实参。第二个用例的占位断言在 Step 3 后改为对 `out.fieldValues['计价单位']`（若数值）或单位换算结果的真实断言。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/inputDefaultCompute.test.ts`
Expected: 用例 1 FAIL（当前 INPUT_TEXT/无源 content 在收集循环外，金额=0 而非 8）

- [ ] **Step 3: 改 computeAllFormulas 用统一解析器**

在 `QuotationStep2.tsx` 顶部 import：
```ts
import { resolveInputDefault, coerceInputNumber } from './inputDefaults';
```

把 `:571-617` 的「INPUT_NUMBER 默认值兜底链」整段替换为类型无关版（覆盖 TEXT+NUMBER），即把：
```ts
      // V190: INPUT_NUMBER 行值为空 → 默认值兜底链 ...
      if ((raw === undefined || raw === null || raw === '')
          && f.field_type === 'INPUT_NUMBER') {
        // ...原 580-616 整段 default_source 解析 + content 兜底...
      }
      const val = parseFloat(raw);
      if (!isNaN(val)) fieldValues[key] = val;
```
替换为：
```ts
      // 统一解析器：INPUT_TEXT/INPUT_NUMBER 默认值兜底（default_source 实时 > content）
      if ((raw === undefined || raw === null || raw === '')
          && (f.field_type === 'INPUT_NUMBER' || f.field_type === 'INPUT_TEXT' || f.field_type === 'INPUT')) {
        const def = resolveInputDefault(f, { basicDataValues, partNo, pathCache: pathCache ?? (getGlobalPathCache() as Record<string, any>) });
        if (def !== undefined) raw = def;
      }
      const val = typeof raw === 'number' ? raw : parseFloat(raw);
      if (!isNaN(val)) fieldValues[key] = val;
```
> 删除被替换段内对 `f.default_source` 的本地分支（已收敛进解析器）。保留上方 BASIC_DATA(:490)、DATA_SOURCE(:521)、FIXED_VALUE(:563) 分支不动。

把 `:782 resolveInputDefaultSourceForRow` 函数体改为委托解析器（保持其两处调用 :633/:841 行为不变，但补 content/pathCache 能力）：
```ts
function resolveInputDefaultSourceForRow(
  f: ComponentField,
  basicDataValues: Record<string, any> | undefined,
): any {
  // 收敛到统一解析器（仅行级 basicDataValues；pathCache/content 由解析器内部按需）
  const v = resolveInputDefault(f, { basicDataValues });
  return v === undefined ? undefined : v;
}
```

- [ ] **Step 4: 跑测试 + 类型检查**

把 Step 1 第二个用例的占位断言改为真实断言（基于实现后行为）。
Run:
```bash
cd cpq-frontend && npx vitest run src/pages/quotation/inputDefaultCompute.test.ts && npx tsc --noEmit -p tsconfig.json
```
Expected: PASS + tsc 0 错误

- [ ] **Step 5: 回归既有 default_source / 单位换算测试**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/quotation/unitConversion.computeAllFormulas.test.ts src/pages/quotation/unitConversion.dataSource.test.ts src/pages/quotation/crossTabInputDefaultSource.test.ts src/pages/quotation/unitConversion.crossTabE2E.test.ts
```
Expected: 全 PASS（`含量` 等 INPUT_NUMBER+default_source 行为零回归）

- [ ] **Step 6: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/inputDefaultCompute.test.ts
git commit -m "refactor(input-default): computeAllFormulas 改调统一解析器, INPUT_TEXT 纳入默认值兜底"
```

---

## Task 3: ComponentCell 编辑态 + 只读态改调统一解析器

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx`（编辑态 :640-696；只读态 :599-635；import）

现状：编辑态 `:644` 仅 `(isNumber || INPUT_NUMBER)` 解析默认值且缺 BASIC_DATA 分支；只读态 `:600` 含 TEXT 但缺 BASIC_DATA。统一到解析器。

- [ ] **Step 1: import 解析器**

```ts
import { resolveInputDefault } from '../inputDefaults';
```

- [ ] **Step 2: 改编辑态默认值（:640-696）**

把 `:642-680` 计算 `defaultLabel`/`placeholder` 并仅用作 placeholder 的逻辑，改为：**`row[key]` 空时用解析器结果作为输入框初值**（非仅 placeholder），覆盖 TEXT+NUMBER：
```ts
  // readonly=false: 渲染 <input>
  // row[key] 空 → 用统一解析器给默认初值（default_source 实时 > content）；非空不动（铁律）
  const ctx2 = context;
  let effectiveValue: any = rawCell;
  if (isEmpty) {
    const def = resolveInputDefault(field, {
      basicDataValues: ctx2.basicDataValues,
      partNo: ctx2.partNo,
      pathCache: ctx2.pathCacheState as Record<string, any>,
    });
    if (def !== undefined) effectiveValue = isNumber ? (def as any) : String(def);
  }

  return (
    <input
      type={isNumber ? 'number' : 'text'}
      step={isNumber ? 'any' : undefined}
      value={effectiveValue ?? ''}
      onChange={e => {
        const val = e.target.value;
        if (isNumber && val !== '' && !/^-?\d*\.?\d*$/.test(val)) return;
        onCellChange?.(rowIndex, key, val);
      }}
      onBlur={() => onCellBlur?.(rowIndex, key)}
    />
  );
```
> 删除 `:642-680` 旧 `defaultLabel`/`defaultVarCode`/`placeholder` 整段（被解析器取代）。若需保留"默认值"视觉提示，可在 `value` 非用户输入时加 `title="默认值"`，非必须。

- [ ] **Step 3: 改只读态默认值（:599-635）**

把 `:599-635`（GLOBAL_VARIABLE+BNF_PATH 两分支 + content 兜底）整段替换为解析器调用：
```ts
    // default_source 解析（统一解析器；含 BASIC_DATA + content 兜底）
    if (isNumber || field.field_type === 'INPUT_TEXT' || field.field_type === 'INPUT') {
      const def = resolveInputDefault(field, {
        basicDataValues,
        partNo,
        pathCache: pathCacheState as Record<string, any>,
      });
      if (def !== undefined) {
        const formatted = formatPathValue(def) ?? String(def);
        return <span title="默认值">{formatted}</span>;
      }
    }
    return <span className="qt-ds-placeholder">—</span>;
```

- [ ] **Step 4: 类型检查 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/components/ComponentCell.tsx
```
Expected: tsc 0 错误；HTTP 200

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/components/ComponentCell.tsx
git commit -m "refactor(input-default): ComponentCell 编辑/只读态改调统一解析器, INPUT_TEXT 默认值作初值带出"
```

---

## Task 4: snapshotRows 持久化兜底（无源静态 content 冻结）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（snapshotRows :810-902；import）

策略（§3.3）：**只对「无 default_source 的 INPUT 字段」冻结静态 content 进 row_data**；有源字段不冻结（后端/解析器实时给）。放在 BASIC_DATA 回填(:863)后、FORMULA(:878)前，使公式读得到。

- [ ] **Step 1: import + 数值归一**

```ts
import { resolveInputDefault, coerceInputNumber } from './inputDefaults';
```

- [ ] **Step 2: 在 :876 之后（FIXED_VALUE 块之后、FORMULA 之前）插入 INPUT 兜底块**

```ts
      // 1.6 snapshot INPUT 静态默认值 → row[key]
      //   仅"无 default_source"的静态 content 冻结落库（常量，冻结安全，后端核价/Excel 才读得到）；
      //   有 default_source 的字段不冻结——其值由各消费点解析器/后端实时给出（§3.3）。
      for (const f of fields) {
        if (f.field_type !== 'INPUT_TEXT' && f.field_type !== 'INPUT_NUMBER') continue;
        if (f.default_source) continue;                 // 有源 → 不冻结
        if (f.content == null || f.content === '') continue;
        const fieldKey = f.name || f.key || '';
        if (!fieldKey) continue;
        if (enriched[fieldKey] === undefined || enriched[fieldKey] === null || enriched[fieldKey] === '') {
          enriched[fieldKey] = f.field_type === 'INPUT_NUMBER'
            ? (coerceInputNumber(f.content) ?? f.content)  // 数值列归一，非法保留原值
            : f.content;
        }
      }
```

- [ ] **Step 3: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(input-default): snapshotRows 冻结无源 INPUT 静态 content 进 row_data"
```

---

## Task 5: buildCrossTabRows 收敛到统一解析器

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（buildCrossTabRows 内 INPUT default_source 解析处；及任何渲染期 INPUT 默认值回填）

现状：buildCrossTabRows 经 `resolveInputDefaultSourceForRow`（Task 2 已委托给解析器）取 INPUT 源值用于跨 Tab 匹配。本任务确认其覆盖 TEXT + content 兜底，并补任何遗漏回填点。

- [ ] **Step 1: 定位 buildCrossTabRows 内 INPUT 取值点**

Run: `cd cpq-frontend && grep -n "resolveInputDefaultSourceForRow\|default_source\|INPUT_TEXT\|INPUT_NUMBER" src/pages/quotation/QuotationStep2.tsx | sed -n '1,40p'`
确认 buildCrossTabRows / 渲染回填段是否仍有未走解析器的 INPUT 默认值分支。

- [ ] **Step 2: 把残留分支改调解析器**

对发现的每处「`row[k]` 空 → 取 INPUT 默认值」分支，统一改为：
```ts
const def = resolveInputDefault(f, { basicDataValues, partNo, pathCache });
if (def != null && (row[k] == null || row[k] === '')) { /* 写入增广行/匹配键 */ }
```
（`resolveInputDefaultSourceForRow` 已委托解析器，调用点保持即可；仅当存在**未走该函数**的独立 INPUT 默认值分支时才改。）

- [ ] **Step 3: 回归跨 Tab 测试**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/crossTabInputDefaultSource.test.ts src/pages/quotation/rowDedup.test.ts src/pages/quotation/useCardSnapshots.test.ts`
Expected: 全 PASS

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "refactor(input-default): buildCrossTabRows/渲染回填 INPUT 默认值统一走解析器"
```

---

## Task 6: buildEmptyRow 注释对齐（不改值）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx:94`
- Modify: `cpq-frontend/src/pages/quotation/AddProductModal.tsx:36`

决策：buildEmptyRow 对 INPUT **保持写 `''`**（默认值由解析器在渲染/计算/保存动态给出，不在建行写死，避免与 driver 行 baseRow 不一致、避免把"默认值"误冻结成用户值）。本任务仅加注释固化该约定，防后人"顺手补"。

- [ ] **Step 1: 两处 INPUT 分支加注释**

`BulkImportPartsDrawer.tsx` 与 `AddProductModal.tsx` 的 `else { row[f.name] = ''; }` 上方各加：
```ts
      // INPUT_TEXT/INPUT_NUMBER 故意写 ''：默认值(default_source 实时 / 静态 content)
      // 由 inputDefaults.resolveInputDefault 在渲染/计算/snapshotRows 动态给出，不在建行写死。
```

- [ ] **Step 2: 类型检查 + 提交**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误
```bash
git add cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx cpq-frontend/src/pages/quotation/AddProductModal.tsx
git commit -m "docs(input-default): buildEmptyRow INPUT 写'' 约定注释（默认值由解析器动态给出）"
```

---

## Task 7: 后端取数链验证 / 必要时补齐

**Files:**
- Read/verify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（`buildCardValues` / `buildCostingCardValues`）+ Excel 导出 + 提交快照 `SnapshotCollectorService`

目标：报价侧 `row_data` 冻结的无源 content（货币=RMB）后端读得到；有源 INPUT（计价单位）后端能实时解析（INPUT_TEXT + 三 type + content 兜底）。

- [ ] **Step 1: 定位后端 INPUT 取值逻辑**

Run:
```bash
cd cpq-backend && grep -rn "INPUT_TEXT\|INPUT_NUMBER\|default_source\|defaultSource\|row_data\|rowData" src/main/java/com/cpq/quotation/service/CardSnapshotService.java | head -40
```
判定后端按行取 INPUT 值时：是否读 `row_data`（货币能带出）？是否解析 default_source（计价单位能带出）？是否含 INPUT_TEXT + content 兜底？

- [ ] **Step 2: 端到端验证（造一单 → 查核价值）**

用现有 E2E 测试数据（记忆 `cpq-e2e-quotation-flow-test-data`）建一张含 `元素` 页签的报价单并保存，然后：
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
SELECT component_data FROM quotation_line_item WHERE quotation_id='<新建单id>' LIMIT 1;" | grep -o '货币[^,]*'
```
确认 `row_data` 内 `货币=RMB` 已落库。再调核价卡片值 endpoint（或核价视图），确认货币/计价单位带出。

- [ ] **Step 3: 若后端缺解析 → 补齐**

仅当 Step 1/2 发现后端某链 INPUT_TEXT/三 type/content 兜底缺失时：在对应 Java 取值处补齐解析（与前端解析器同优先级 `default_source(三 type) > content`）。改 Java 后 `touch` 一个 java 文件触发 Quarkus 重启，`curl` health 200/401。
> 若后端已正确（仅缺前端实化的 row_data 输入），则无需改 Java，本步标注"验证通过，无需补齐"。

- [ ] **Step 4: 提交（如有 Java 改动）**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java
git commit -m "fix(input-default): 后端核价取数链补齐 INPUT_TEXT/默认值解析（与前端同优先级）"
```

---

## Task 8: E2E + 五处一致性复测

**Files:**
- Modify/verify: `cpq-frontend/e2e/quotation-flow.spec.ts`（断言增强）

- [ ] **Step 1: 增强 SIMPLE spec 断言**

在 `quotation-flow.spec.ts` 的 `元素` 页签校验里加：
- 货币（无源）输入框 value === `RMB`（读 `input` 的 value，非 placeholder）。
- 不改任何格 → 保存 → 重新进入 → 货币仍 `RMB`（断言来自持久化：可在重开前对该模板 default_source 不可达环境下验证货币仍在）。
- 改货币为 `USD` → 保存 → 重开 === `USD`（不覆盖用户值）。
- 计价单位（有源）带出 `$ys_view.单位` 实时值（若测试数据该列有值则断言为该值；无值则断言兜底 `KG`）。

- [ ] **Step 2: 跑双 spec E2E**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: `quotation-flow` 1 passed + `'加载中' final count = 0` + 全 Tab `加载中=0`；`composite-product-flow` 按 RECORD 既有状态（若环境性失败与本改动无关需说明）。

- [ ] **Step 3: 五处一致性人工复测**

报价填数 / 详情只读 / 核价单 / 核价 Excel / 提交快照 —— 货币=RMB、计价单位=源值/KG 五处一致。截图存档（qf-* + 核价/详情）。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts
git commit -m "test(input-default): E2E 货币硬断言+未改动也保存+计价单位源优先"
```

---

## Task 9: 记录 RECORD.md + 收尾

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 追加开发记录**

格式 `[2026-06-17] 报价渲染 - INPUT 默认值解析统一化 | 涉及文件 | 关键决策`，写明：抽 `resolveInputDefault` 统一 ~8 处消费点 + 后端验证；优先级 `已有值>default_source(三 type 实时)>静态 content`；无源 content 在 snapshotRows 冻结、有源实时；`usePathFormulaCache` BASIC_DATA 排除保持不动；存量爆炸面数字（Task 0 Step 2）；AP-44 漂移教训。

- [ ] **Step 2: 全量自检三连**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && npx vitest run src/pages/quotation/
```
Expected: tsc 0 错误 + quotation 单测全绿。

- [ ] **Step 3: 提交 + 收尾**

```bash
git add docs/RECORD.md
git commit -m "docs(record): INPUT 默认值解析统一化"
```
用户确认功能达标后，走 `superpowers:finishing-a-development-branch` 合并 master + 清理 worktree。

---

## Self-Review（plan 对照 spec）

- §3.1 统一解析器 → Task 1 ✅
- §3.2 #1~#6 全链路由 → Task 2(computeAllFormulas)/Task 3(ComponentCell)/Task 4(snapshotRows)/Task 5(buildCrossTabRows)/Task 6(buildEmptyRow 约定) ✅
- §3.2 #7 后端 → Task 7 ✅
- §3.3 持久化"有源实时/无源冻结" → Task 4 ✅
- §3.4 编辑态可改且未改也算数 → Task 3 Step2 + Task 4 ✅
- §4 usePathFormulaCache 不动 → 文件结构注 + Task 列表未含该文件 ✅
- §5 风险/回归 → Task 2 Step5 / Task 5 Step3（既有 default_source 零回归） ✅
- §5.6 存量 + 边界 → Task 0 ✅
- §7 测试 → Task 1/2/8 ✅

类型一致性：`resolveInputDefault(field, ctx)` / `coerceInputNumber(v)` 全任务签名一致；`InputDefaultCtx { basicDataValues, partNo, pathCache }` 统一。
```
