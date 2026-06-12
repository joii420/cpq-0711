# 宿主页签自引用归一(self→field)+ 紫色区分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"宿主引用自己明细字段"在序列化时归一为 `field` token(不成环、读本行),并用紫色在矩阵 chip 与富文本块区分宿主自身字段;附一次性迁移修复库内既有自环数据。

**Architecture:** 纯前端改动——`expressionToTokens` 顶层 `[别名.字段]` 明细分支加自判(`selfComponentId` 命中 → `field`);`classifyRefSegment` 用 tabDefs 上 `self` 标记把宿主明细判紫、宿主自聚合判红;矩阵宿主行插裸字段 + 紫边;富文本加紫块。后端求值/环检测**不动**(自环边因 token 变 field 而消失)。既有坏数据用一次性 SQL 迁移。

**Tech Stack:** React 18 + TypeScript + Ant Design + Vitest(单测) + Playwright(E2E) + PostgreSQL(迁移)。

**Spec:** `docs/superpowers/specs/2026-06-12-host-self-field-normalization-design.md`

**前置(执行时):** 隔离 worktree 分支(`superpowers:using-git-worktrees`)。worktree 内 `cpq-frontend/node_modules` 需 symlink 复用主工作区(`ln -s ../../../../cpq-frontend/node_modules node_modules`),不重装。

**测试命令约定:**
- 单测:`cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
- 类型:`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
- Transform(worktree 内 dev server 不服务 worktree,用 esbuild 验 .tsx 解析):`cd cpq-frontend && npx esbuild <file> --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK`

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-frontend/src/services/tabJoinFormulaService.ts` | `TabDef` 接口加 `self?: boolean` | 修改 |
| `cpq-frontend/src/pages/component/formulaSerialize.ts` | `SegmentColor` 加 `'purple'`;`classifyRefSegment` 宿主紫/自聚合红;`expressionToTokens` 自引用归一 field | 修改 |
| `cpq-frontend/src/pages/component/formulaSerialize.test.ts` | 上述 TDD 用例 + 改既有期望 | 修改 |
| `cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx` | `BLOCK_STYLE` 加 purple | 修改 |
| `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx` | 宿主行明细 chip 插裸 `[字段]` + 紫边 | 修改 |
| `cpq-backend/scripts/2026-06-12-fix-host-self-ref.sql` | 一次性迁移既有自环数据 | 新建 |

---

## Task 1: `TabDef` 接口加 `self?: boolean`(前置)

**Files:** Modify `cpq-frontend/src/services/tabJoinFormulaService.ts`

后端 `ComponentTabDefService:100` 已返 `self:true`,但前端类型未声明 → `tab.self` 永远 undefined、紫色静默失效。此为后续硬前提。

- [ ] **Step 1: 加字段**

把 `TabDef` 接口(第 3-13 行)的 `subtotalCols: string[];` 行下方加一行:

```ts
export interface TabDef {
  alias: string;
  tabKey: string;
  componentId?: string;
  componentName?: string;
  componentType?: string;
  sortOrder?: number;
  rowKeyFields: string[];
  detailFields: string[];
  subtotalCols: string[];
  /** 后端标记:该 tabDef 是否为当前被编辑(宿主)组件(ComponentTabDefService 注入) */
  self?: boolean;
}
```

- [ ] **Step 2: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/services/tabJoinFormulaService.ts
git commit -m "feat(tabjoin): TabDef 增加 self 标记(宿主页签识别前置)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 紫色配色 — `classifyRefSegment` 宿主紫/自聚合红 + 紫色管线(TDD)

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`
- Modify: `cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx`(同 commit 加 purple 块样式,保证 tsc 不破)

`SegmentColor` 加 `'purple'` 会让 `FormulaRichInput` 的 `BLOCK_STYLE`(`Record<NonNullable<SegmentColor>|'neutral'>`)缺键报错,故本任务一并补 purple 块样式。

- [ ] **Step 1: 写失败测试**

在 `formulaSerialize.test.ts` 末尾追加(顶部 import 已含 `classifyRefSegment`):

```ts
// ─────────────────────────────────────────────
// 宿主自身字段 → 紫(spec §5)
// ─────────────────────────────────────────────
describe('classifyRefSegment — 宿主自身字段(紫)', () => {
  const self = ['料号'];
  const tabSelf: TabDef = {
    alias: 'COMP_SELF', tabKey: 'tab-self', componentId: 'uuid-self',
    componentName: '宿主组件', componentType: 'NORMAL', self: true,
    rowKeyFields: ['料号'], detailFields: ['组成用量'], subtotalCols: ['金额小计'],
  };
  const tabs = [...allTabs, tabSelf];

  it('宿主明细字段 [宿主组件.组成用量] → purple', () => {
    expect(classifyRefSegment('宿主组件.组成用量', tabs, self, true))
      .toEqual({ kind: 'self-field', color: 'purple' });
  });
  it('宿主小计列 [宿主组件.金额小计] → yellow(小计优先于 self)', () => {
    expect(classifyRefSegment('宿主组件.金额小计', tabs, self, true))
      .toEqual({ kind: 'subtotal', color: 'yellow' });
  });
  it('宿主自聚合 [宿主组件.组成用量(总计)] → red(本期不支持)', () => {
    expect(classifyRefSegment('宿主组件.组成用量(总计)', tabs, self, true).color).toBe('red');
  });
  it('宿主未知字段 [宿主组件.不存在] → red', () => {
    expect(classifyRefSegment('宿主组件.不存在', tabs, self, true).color).toBe('red');
  });
  it('兄弟明细仍蓝 [回料.用量] → blue', () => {
    expect(classifyRefSegment('回料.用量', tabs, self, true).color).toBe('blue');
  });
});
```

并**修改既有用例期望**:把测试里 `classifyRefSegment('单重', allTabs, self, true)` 期望从 `{ kind: 'self-field', color: 'blue' }` 改为 `{ kind: 'self-field', color: 'purple' }`(裸字段=宿主自身字段=紫)。用 grep 定位:`grep -n "self-field', color: 'blue'\|color).toBe('blue')" src/pages/component/formulaSerialize.test.ts`,凡断言**裸 `[字段]`/无点 self-field 块为 blue** 的,改 purple;兄弟跨页签蓝的不动。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: FAIL(purple 用例未实现 + 单重 期望不符)。

- [ ] **Step 3: 实现 — SegmentColor 加 purple**

`formulaSerialize.ts` 第 670 行:

```ts
export type SegmentColor = 'blue' | 'yellow' | 'green' | 'red' | 'purple' | null;
```

- [ ] **Step 4: 实现 — classifyRefSegment 宿主紫/自聚合红**

在 `classifyRefSegment` 含点分支里,**紧接** subtotalCols 判定(`if (!isAgg && (tab.subtotalCols ?? []).includes(field)) { return yellow }` 之后、`const known = new Set(...)` 之前)插入:

```ts
    // 宿主自身字段(spec §5):tabDef.self → 紫;自聚合(isAgg)本期不支持 → 红
    if (tab.self) {
      if (isAgg) return { kind: 'invalid', color: 'red' };
      if (!(tab.detailFields ?? []).includes(field)) return { kind: 'invalid', color: 'red' };
      return { kind: 'self-field', color: 'purple' };
    }
```

并把末尾"无点无总计"分支(原 `return { kind: 'self-field', color: 'blue' };`)改为:

```ts
  // 无点无总计 → 宿主自身列(裸字段)→ 紫
  return { kind: 'self-field', color: 'purple' };
```

- [ ] **Step 5: 实现 — FormulaRichInput 加 purple 块样式**

`FormulaRichInput.tsx` 的 `BLOCK_STYLE`(`blue/yellow/green/red/neutral` 那个 Record)新增 purple 项:

```tsx
  purple:  { background: '#f9f0ff', border: '1px solid #d3adf7', color: '#722ed1' },
```

(放在 `red` 与 `neutral` 之间即可;键名 `purple` 对齐 `SegmentColor`。)

- [ ] **Step 6: 跑测试 + 类型 + transform**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts
npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/template/tabjoin/FormulaRichInput.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: vitest 全绿(含新增 5 例 + 改后 单重);tsc 0;TRANSFORM_OK。

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx
git commit -m "feat(formula): 宿主自身字段判色紫 + 自聚合红 + 紫块样式

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 序列化器自引用归一 field(TDD)

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

`expressionToTokens` 顶层 `[别名.字段]` 明细分支:`selfComponentId` 命中宿主 + 非聚合 → `{type:'field'}`,否则才 makeCrossTabRef。

- [ ] **Step 1: 写失败测试**

在 `formulaSerialize.test.ts` 末尾追加(fixtures:tabRL 回料 componentId='uuid-rl' detailFields=['金额','用量'] subtotalCols=['金额'];tabInv 投料 componentId='uuid-inv'):

```ts
describe('expressionToTokens — 宿主自引用归一 field(spec §4)', () => {
  const rkf = ['料号'];

  it('[回料.用量] + selfComponentId=回料 → field token', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, rkf, 'uuid-rl');
    expect(t).toHaveLength(1);
    expect(t[0]).toEqual({ type: 'field', value: '用量' });
  });
  it('[回料.用量] + selfComponentId=别的(uuid-inv) → cross_tab_ref(回归)', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, rkf, 'uuid-inv');
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'uuid-rl' });
  });
  it('[回料.金额](金额∈subtotalCols) + self=回料 → 仍 component_subtotal', () => {
    const t = expressionToTokens('[回料.金额]', allTabs, rkf, 'uuid-rl');
    expect(t[0]).toMatchObject({ type: 'component_subtotal' });
  });
  it('[回料.用量(总计)](自聚合) + self=回料 → 仍 cross_tab_ref(不归一)', () => {
    const t = expressionToTokens('[回料.用量(总计)]', allTabs, rkf, 'uuid-rl');
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', agg: 'SUM' });
  });
  it('不传 selfComponentId → cross_tab_ref(旧行为保留)', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, rkf);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref' });
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: FAIL(第 1 例期望 field,实际仍 cross_tab_ref)。

- [ ] **Step 3: 实现 — 插入自判分支**

`formulaSerialize.ts` 顶层 `[别名.字段]` 处理(第 401-413 行),把现有 `if (subtotal) {...} else { makeCrossTabRef }` 改成三分支:

```ts
          if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
            result.push({
              type: 'component_subtotal',
              value: fieldPart,
              tab_name: fieldPart,
              component_code: tabDef.alias,
              label: `${tabDef.componentName ?? tabDef.alias}·${fieldPart}`,
            });
          } else if (selfComponentId && tabDef.componentId === selfComponentId && !isAgg) {
            // 宿主自身明细字段 → 同行裸字段 token(不成环、读本行)。
            // 小计列已被上面的 if 截走;自聚合(isAgg)不在此归一,仍走下面 cross_tab_ref。
            result.push({ type: 'field', value: fieldPart });
          } else {
            result.push(
              makeCrossTabRef(alias, fieldPart, isAgg ? 'SUM' : 'NONE', tabDefs, selfRowKeyFields),
            );
          }
```

(`selfComponentId` 是 `expressionToTokens` 第 5 个参数,已存在;`tabDef` 在该分支上文已解析。)

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: 全绿。

- [ ] **Step 5: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(formula): expressionToTokens 宿主自引用归一为 field token

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 矩阵宿主行 — 插裸 `[字段]` + 紫边

**Files:** Modify `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx`

宿主那行(`def.self === true`)的明细 chip:点击插裸 `[字段]`、紫边;须在 `detailFields.map` 内**优先**判 `def.self`,先于 `!isComparable`/`sourceFiner`。

- [ ] **Step 1: 加宿主行明细分支**

在 `def.detailFields.map((f) => {` 内、现有 `if (!isComparable) {` 之**前**,插入宿主分支:

```tsx
                  {def.detailFields.map((f) => {
                    if (def.self) {
                      // 宿主自身明细字段:插裸 [字段](序列化归一为 field,不成环)+ 紫边
                      return (
                        <Tag key={f} onClick={() => onInsert(`[${f}]`)}
                          style={{ cursor: 'pointer', background: '#fff', borderColor: '#d3adf7',
                            color: '#722ed1', margin: 0, fontSize: 12, padding: '3px 9px',
                            borderStyle: 'solid', userSelect: 'none' }}>{f}</Tag>
                      );
                    }
                    if (!isComparable) {
                      // …现有不可比分支不变…
```

(只新增这一个 `if (def.self)` 早返分支;`!isComparable`/`sourceFiner`/普通 三个现有分支原样不动。)

- [ ] **Step 2: 宿主行明细组标题加提示(可选但本任务做)**

定位宿主明细组的小标题 `<span style={{ fontSize: 11, color: '#8a909a' }}>明细</span>`(在 `def.detailFields.map` 上方的 `<Space>` 内),改为按 self 显示提示:

```tsx
                  <span style={{ fontSize: 11, color: def.self ? '#722ed1' : '#8a909a' }}>
                    {def.self ? '明细(本页签·同行)' : '明细'}
                  </span>
```

- [ ] **Step 3: 类型 + transform**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/template/tabjoin/TabFieldMatrix.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: tsc 0;TRANSFORM_OK。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx
git commit -m "feat(tabjoin): 矩阵宿主行明细 chip 插裸字段 + 紫边

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 一次性迁移 SQL 脚本(新建文件)

**Files:** Create `cpq-backend/scripts/2026-06-12-fix-host-self-ref.sql`

把库内既有"宿主明细自引用 cross_tab_ref(source=自身、agg=NONE/null)"原地改写为 field token。**仅创建脚本文件**;执行在合并后手动进行(Task 6)。

- [ ] **Step 1: 写脚本**

创建 `cpq-backend/scripts/2026-06-12-fix-host-self-ref.sql`,内容:

```sql
-- 一次性迁移:宿主明细自引用 cross_tab_ref → field token
-- 背景:[宿主.字段] 被存成 source=自身 的 cross_tab_ref → 组件级自环 + 求值取空归0。
-- 规则:type=cross_tab_ref 且 source=本组件 id 且 (agg='NONE' OR agg IS NULL) → {type:field,value:target,label:target}
-- 注:self cross_tab_ref 的 target 天然是明细(小计/总计走 component_subtotal),无需再过滤 detailFields。
-- 自聚合(agg∈SUM/AVG/MAX/MIN/COUNT)不迁移(本期非目标)。

-- 1) 备份(执行前)
CREATE TABLE IF NOT EXISTS _bak_component_formulas_20260612 AS
  SELECT id, formulas, now() AS backed_up_at
  FROM component
  WHERE formulas::text LIKE '%cross_tab_ref%';

-- 2) 迁移
UPDATE component c SET formulas = (
  SELECT jsonb_agg(
    CASE WHEN f ? 'expression' THEN jsonb_set(f, '{expression}', (
      SELECT jsonb_agg(
        CASE WHEN tk->>'type' = 'cross_tab_ref'
              AND tk->>'source' = c.id::text
              AND (tk->>'agg' = 'NONE' OR tk->>'agg' IS NULL)
             THEN jsonb_build_object('type','field','value',tk->>'target','label',tk->>'target')
             ELSE tk END
        ORDER BY tk_ord)
      FROM jsonb_array_elements(f->'expression') WITH ORDINALITY e(tk, tk_ord)))
    ELSE f END
    ORDER BY f_ord)
  FROM jsonb_array_elements(c.formulas) WITH ORDINALITY ff(f, f_ord))
WHERE c.formulas::text LIKE '%cross_tab_ref%';

-- 3) 复查:全库应 0 条"宿主自引用 cross_tab_ref(agg NONE/null)"
SELECT c.id, c.name, count(*) AS remaining_self_loops
FROM component c, jsonb_array_elements(c.formulas) f, jsonb_array_elements(f->'expression') tk
WHERE tk->>'type'='cross_tab_ref'
  AND tk->>'source'=c.id::text
  AND (tk->>'agg'='NONE' OR tk->>'agg' IS NULL)
GROUP BY c.id, c.name;
-- 期望:0 行返回。
```

- [ ] **Step 2: Commit**

```bash
git add cpq-backend/scripts/2026-06-12-fix-host-self-ref.sql
git commit -m "chore(migration): 宿主明细自引用 cross_tab_ref→field 一次性迁移脚本

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 自检 + 合并后迁移与发布验证

**Files:** 无(仅运行验证)

- [ ] **Step 1: 全量单测 + 类型**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts
npx tsc --noEmit -p tsconfig.json
```
Expected: vitest 全绿;tsc 0。

- [ ] **Step 2: 改动 .tsx transform**

Run(逐个):
```bash
cd cpq-frontend
for f in pages/component/formulaSerialize.ts pages/template/tabjoin/FormulaRichInput.tsx pages/template/tabjoin/TabFieldMatrix.tsx; do
  npx esbuild src/$f --loader:.tsx=tsx --bundle=false > /dev/null && echo "$f TRANSFORM_OK"
done
```
Expected: 三个 TRANSFORM_OK。

- [ ] **Step 3:(合并到 master 后,需用户确认)跑迁移 + 复查**

合并后,在主工作区对线上库执行(用 `application.properties` 的 DB):
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -f cpq-backend/scripts/2026-06-12-fix-host-self-ref.sql
```
Expected: 末尾复查查询返回 **0 行**(无残留自引用 cross_tab_ref)。来料 两条公式的 `[来料.组成用量]` token 应变为 `{type:field,value:组成用量}`。

- [ ] **Step 4:(合并后)E2E + 发布验证**

```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: passed;`'加载中' final count = 0`。
发布验证:由用户在 UI 重新发布「报价模板0608 草稿」→ 应不再报"页签公式存在循环引用"。

- [ ] **Step 5: 完成宣告(附"已自检"声明行)**

例:
> vitest 全绿 ✅;tsc 0 ✅;三 .tsx TRANSFORM_OK ✅;迁移复查 0 自环 ✅;E2E quotation-flow passed ✅;草稿发布通过(用户验收)。

---

## 真机验收(用户)
- 矩阵宿主行明细 chip 紫边、点击插裸 `[字段]`;
- 手敲 `[宿主名.列]` → 保存后重开显示裸 `[列]`(归一生效);
- 公式块紫色显示;
- 「报价模板0608 草稿」发布成功。

## 收尾(用户确认达标后)
走 `superpowers:finishing-a-development-branch` 合并清理。
