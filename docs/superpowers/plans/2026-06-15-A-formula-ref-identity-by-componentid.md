# A. 公式引用按稳定身份(componentId)绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复组件管理公式编辑器里「选 `[来料加工费.费用]` 保存后变成 `[元素…]`」——即含多个 cross_tab 引用的公式，第二个引用被错绑到别的页签。

**Architecture:** 根因 = `formulaSerialize.ts` 正向解析 `expressionToTokens` 用 `findTabByRef`（按页签**中文名 componentName 优先、alias 兜底**）解析 `[别名.列]` 的页签身份；当模板里**页签中文名重复/近似**或解析串用的是另一种标识时命中错页签；N≥2 多 source 路径还会把 `source` 镜像成排序后的 `primaryTab`（非用户所选）。反向回显 `tokensToDrawerExpression` 已按 `componentId` 解析（稳定、正确），所以只需把**正向解析的身份锚点从"名字"换成"componentId"**，并保证编辑器把每个所选引用的 componentId 透传进解析，名字仅用于显示。

**Tech Stack:** React + TS / Vitest（`cpq-frontend/src/pages/component/formulaSerialize.ts` + 其调用方编辑器 `TabJoinFormulaDrawer` 系列）。

**复现实证（库内当前数据）：** 来料组件(COMP-0028) 公式 `纯材料成本(来料)` = `SUM(<元素 expr>) + SUM(<元素 expr>)`，**两个 cross_tab_ref 的 source 都是「元素」的 componentId**；第二个本应是 `来料加工费`(COMP-0036) 的引用。

---

## 根因（已确认，代码级）

- `findTabByRef`（formulaSerialize.ts:171-173）：`tabDefs.find(d => d.componentName === ref) ?? tabDefs.find(d => d.alias === ref)`。**按名字解析页签身份**。被 `expressionToTokens` 在三处调用：单列快捷 :277、KSUM 内 :357、行级 body :448。
- N≥2 多 source 时（:529-549）`source` 取 `ordered[0]`（按 rowKeyFields 长度 + `componentId.localeCompare` 排序）= **primaryTab，非用户所选页签**。`sources[]` 保留全部，但顶层 `source` 字段是镜像值；下游 / 回显以 `source` 为准 → 错绑。
- 反向 `tokensToDrawerExpression`（:744 `tabDefs.find(d => d.componentId === token.source)`）已按 componentId，正确；问题纯在正向 + 编辑器选择是否携带 componentId。

## File Structure

- `cpq-frontend/src/pages/component/formulaSerialize.ts` — `findTabByRef` 改为 **componentId 优先精确匹配**，名字仅兜底显示；`expressionToTokens` 解析 `[别名.列]` 时优先把 `别名` 当 componentId/alias（稳定键）解析。
- 调用方编辑器（`TabJoinFormulaDrawer.tsx` / `TabFieldMatrix` / `tabJoinFormulaService.ts`）— 选择一个跨页签引用时，drawer 串内的"别名"段应使用**稳定 alias（= tabDef.alias / componentCode）**，而非可重复的 componentName；并确认 `tabDefs` 每项都带 `componentId` + `alias`。
- 测试：`cpq-frontend/src/pages/component/formulaSerialize.test.ts`（新增/补充 round-trip + 同名页签 + N≥2 source 用例）。

**不变量：** 对任意 tabDefs，`tokensToDrawerExpression(expressionToTokens(s, tabDefs), tabDefs)` 解析出的每个 cross_tab_ref `source` 必须等于用户所引用页签的 componentId（round-trip 身份稳定），即使存在 componentName 重复。

---

## Task A1：复现失败测试 — 同名/多引用页签身份错绑（RED）

**Files:**
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试**

构造 tabDefs：两个页签 componentName 相同或第二页签解析易被首个吃掉的场景，断言两条独立 SUM 各自 source 正确。

```ts
import { describe, it, expect } from 'vitest';
import { expressionToTokens, type TabDef } from './formulaSerialize';

const tabDefs: TabDef[] = [
  { componentId: 'CID-元素', alias: 'COMP-0029', componentName: '元素', rowKeyFields: ['料件'] },
  { componentId: 'CID-来料加工费', alias: 'COMP-0036', componentName: '来料加工费', rowKeyFields: ['料件'] },
];

describe('A1 公式引用身份锚定', () => {
  it('两个独立 SUM 引用不同页签时, source 各自正确(不被 primaryTab 镜像/同名吃掉)', () => {
    // 用稳定 alias 作引用别名(编辑器应产出 alias 串)
    const expr = 'SUM([COMP-0029.重量(g)]) + SUM([COMP-0036.费用])';
    const tokens = expressionToTokens(expr, tabDefs, ['料件'], 'CID-来料');
    const refs = tokens.filter(t => t.type === 'cross_tab_ref');
    expect(refs).toHaveLength(2);
    expect(refs[0].source).toBe('CID-元素');
    expect(refs[1].source).toBe('CID-来料加工费');   // 修复前: 可能被错绑成 CID-元素
  });

  it('componentName 重复时按稳定键解析不串号', () => {
    const dup: TabDef[] = [
      { componentId: 'CID-A', alias: 'COMP-A', componentName: '加工费', rowKeyFields: ['料件'] },
      { componentId: 'CID-B', alias: 'COMP-B', componentName: '加工费', rowKeyFields: ['料件'] },
    ];
    const tokens = expressionToTokens('SUM([COMP-B.费用])', dup, ['料件'], 'CID-self');
    const ref = tokens.find(t => t.type === 'cross_tab_ref')!;
    expect(ref.source).toBe('CID-B');   // 修复前: findTabByRef 按 componentName='加工费' 命中 CID-A
  });
});
```

- [ ] **Step 2: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "A1"`，Expected: FAIL（第二条 source 错 / 同名命中首个）。
- [ ] **Step 3: 提交** `git commit -m "test(formula): 复现 cross_tab 引用身份按名字解析错绑 (RED)"`

---

## Task A2：findTabByRef 改 componentId/alias 稳定键优先（GREEN）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:171-173`

- [ ] **Step 1: 读 :160-200 + :438-550 确认所有解析入口**（findTabByRef 调用点 :277/:357/:448；N≥2 镜像 :529-549）。

- [ ] **Step 2: 改 findTabByRef — 稳定键(componentId / alias)优先，componentName 仅最后兜底**

```ts
/**
 * 按引用串定位页签：**稳定键(componentId 全等 → alias 全等)优先，中文名 componentName 仅兜底**。
 * 名字可重复/可改，绝不作主键；编辑器产出的 [别名.列] 中"别名"应为 alias(componentCode)。
 */
function findTabByRef(tabDefs: TabDef[], ref: string): TabDef | undefined {
  return tabDefs.find((d) => d.componentId === ref)
      ?? tabDefs.find((d) => d.alias === ref)
      ?? tabDefs.find((d) => d.componentName === ref);
}
```

- [ ] **Step 3: 跑 A1 测试** → `npx vitest run src/pages/component/formulaSerialize.test.ts -t "A1"`，Expected: 第二个用例(同名)PASS；第一个用例若仍失败说明 N≥2 镜像影响，进 Task A3。
- [ ] **Step 4: 提交** `git commit -m "fix(formula): findTabByRef 改 componentId/alias 稳定键优先 (GREEN)"`

---

## Task A3：确认编辑器选择产出稳定 alias 串（非 componentName）

**Files:**
- Modify/确认: `cpq-frontend/src/services/tabJoinFormulaService.ts`、`cpq-frontend/src/pages/component/TabJoinFormulaDrawer.tsx`、`TabFieldMatrix*`（grep `buildColumn`/插入引用串处）。

- [ ] **Step 1: 定位选择→串构建** → `grep -rn "buildColumn\|componentName\|\.alias\|insert.*\[" cpq-frontend/src/pages/component/TabJoinFormulaDrawer.tsx cpq-frontend/src/services/tabJoinFormulaService.ts`。确认用户点选一个跨页签字段时，拼进 drawer 串的"别名"用的是 **`tabDef.alias`（稳定）** 还是 `componentName`（可重复）。
- [ ] **Step 2: 若用 componentName → 改为 alias**；显示给用户的标签仍可用 componentName，但**写入表达式串/序列化的标识必须是 alias**。保持与反向回显（:732/:744 按 alias/componentId）一致。
- [ ] **Step 3: 确认 tabDefs 每项含 componentId + alias**（tabJoinFormulaService 组装 tabDefs 处）；缺则补齐——`makeCrossTabRef` 在缺 componentId 时已抛错(:188)，避免静默。
- [ ] **Step 4: tsc + Vite 200** → `npx tsc --noEmit -p tsconfig.json`；`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/TabJoinFormulaDrawer.tsx`（200）。
- [ ] **Step 5: 提交** `git commit -m "fix(formula): 编辑器引用串用稳定 alias, 不再用可重复 componentName"`

---

## Task A4：round-trip 身份稳定护栏 + 全量回归

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 加 round-trip 断言** — 对 A1 的两 tabDefs，`tokensToDrawerExpression(expressionToTokens(expr, tabDefs, ...), tabDefs)` 再 `expressionToTokens` 一遍，两次得到的 cross_tab_ref `source[]` 完全一致（身份不漂移）。
- [ ] **Step 2: 跑 formulaSerialize 全量** → `npx vitest run src/pages/component/formulaSerialize.test.ts`，Expected: 全 PASS（含既有用例不回归）。
- [ ] **Step 3: 真机复测** — 组件管理打开 来料 → `纯材料成本(来料)` 公式，重新选 `[来料加工费.费用]` 保存 → 刷新仍显示 `来料加工费`（不变 元素）。库内 `SELECT formulas FROM component WHERE code='COMP-0028'` 第二个 cross_tab_ref `source` = 来料加工费 componentId。
- [ ] **Step 4: 提交** `git commit -m "test(formula): cross_tab 引用 round-trip 身份稳定护栏"`

---

## Self-Review

- **Spec 覆盖：** 正向身份锚点(A2) + 编辑器产出稳定 alias(A3) + N≥2 镜像不影响所选 source(A1/A4) + round-trip 护栏(A4)。✓
- **不破坏反向：** 反向已按 componentId(:744)，本计划不动。✓
- **风险：** AP-37「同 componentId 多实例」邻域；A3 须确认导入副本场景 alias 唯一性。改 formulaSerialize 属协议级 → 合并后连同 B 计划跑 E2E。
- **RECORD/纪律：** 子代理只 `git add` 本任务文件，禁 `git add -A`；不提交 docs/RECORD.md（主线合并后统一追加）。
