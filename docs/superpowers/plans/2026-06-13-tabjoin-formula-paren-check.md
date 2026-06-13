# 页签连表公式括号实时校验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「配置页签连表公式」抽屉的公式输入框上增加圆括号 `()` 的数量+顺序校验，实时红字提示并禁用保存按钮，对所有组件类型生效。

**Architecture:** 新增一个纯函数 `checkParenBalance(expr)` 用栈/深度计数法扫描表达式（跳过 `[...]`/`{...}` 块内字符以排除 `(总计)` 误判），返回 `{ok, error}`；`TabJoinFormulaDrawer` 用 `useMemo` 调它，驱动输入框下方红字、保存按钮禁用与 `save()` 兜底拦截。

**Tech Stack:** React + TypeScript + Ant Design（前端）；Vitest（单测）。纯前端改动，无后端 / SQL。

**设计依据:** `docs/superpowers/specs/2026-06-13-tabjoin-formula-paren-check-design.md`

---

## File Structure

- **Create** `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.ts` — 纯函数 `checkParenBalance`，唯一职责：圆括号数量+顺序校验。无 React / api 依赖。
- **Create** `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.test.ts` — vitest 单测。
- **Modify** `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx` — 引入 useMemo、输入框下方红字、保存按钮禁用 + Tooltip、`save()` 兜底。

---

## Task 1: 核心校验函数 `checkParenBalance`

**Files:**
- Create: `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.ts`
- Test: `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.test.ts`

- [ ] **Step 1: 写失败测试**

创建 `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.test.ts`：

```ts
import { describe, it, expect } from 'vitest';
import { checkParenBalance } from './formulaBracketCheck';

describe('checkParenBalance', () => {
  it('平衡表达式 → ok', () => {
    expect(checkParenBalance('([单重]+[A.金额(总计)])*2')).toEqual({ ok: true });
  });

  it('空串 → ok', () => {
    expect(checkParenBalance('')).toEqual({ ok: true });
  });

  it('纯文本无括号 → ok', () => {
    expect(checkParenBalance('abc+1')).toEqual({ ok: true });
  });

  it('嵌套平衡 → ok', () => {
    expect(checkParenBalance('((1+2)*(3+4))')).toEqual({ ok: true });
  });

  it('块内 (总计) 不计入 → ok', () => {
    expect(checkParenBalance('[COMP_RL.金额(总计)]')).toEqual({ ok: true });
  });

  it('裸字段总计 [alias(总计)] 不计入 → ok', () => {
    expect(checkParenBalance('[COMP_RL(总计)] + 1')).toEqual({ ok: true });
  });

  it('缺 1 个右括号 → 报缺少', () => {
    const r = checkParenBalance('([单重]+1');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('缺少 1 个右括号');
  });

  it('缺 2 个右括号 → 报缺少 2 个', () => {
    const r = checkParenBalance('((1+2');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('缺少 2 个右括号');
  });

  it('多了右括号 → 报多余', () => {
    const r = checkParenBalance('[单重])');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('多了');
  });

  it('顺序错 )( → 先遇无匹配右括号 → 报多余', () => {
    const r = checkParenBalance(')(');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('多了');
  });

  it('块内圆括号被排除后仍能抓到块外真错', () => {
    const r = checkParenBalance('([COMP_RL.金额(总计)]');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('缺少 1 个右括号');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/tabjoin/formulaBracketCheck.test.ts`
Expected: FAIL — 模块/导出不存在（`Failed to resolve import './formulaBracketCheck'` 或 `checkParenBalance is not a function`）。

- [ ] **Step 3: 写最小实现**

创建 `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.ts`：

```ts
/**
 * formulaBracketCheck.ts
 *
 * 页签连表公式表达式的圆括号 () 数量+顺序校验（纯函数，无 React / api 依赖）。
 *
 * 关键约束：`(总计)` 后缀用 ASCII 圆括号，且总出现在 [...] 字段块内部
 * （如 [COMP_RL.金额(总计)]）。这些不是分组括号，必须排除。
 * 因此扫描时遇 '[' 跳到配对 ']'、遇 '{' 跳到配对 '}'，整体跳过块内字符，
 * 只对真正的分组圆括号做深度计数。
 *
 * 本函数只负责圆括号；[] / {} 缺配对仍由 formulaSerialize.lex() 在保存时报。
 */

export interface ParenCheckResult {
  ok: boolean;
  error?: string;
}

export function checkParenBalance(expr: string): ParenCheckResult {
  let depth = 0;
  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];
    // 跳过 [...] 块（含块内 (总计)）；未闭合则跳到串尾
    if (ch === '[') {
      const end = expr.indexOf(']', i);
      i = end === -1 ? expr.length : end + 1;
      continue;
    }
    // 跳过 {...} 路径块
    if (ch === '{') {
      const end = expr.indexOf('}', i);
      i = end === -1 ? expr.length : end + 1;
      continue;
    }
    if (ch === '(') {
      depth += 1;
    } else if (ch === ')') {
      depth -= 1;
      if (depth < 0) {
        return { ok: false, error: '括号不匹配：多了 1 个右括号 ")"（出现无匹配的右括号）' };
      }
    }
    i += 1;
  }
  if (depth > 0) {
    return { ok: false, error: `括号不匹配：缺少 ${depth} 个右括号 ")"` };
  }
  return { ok: true };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/template/tabjoin/formulaBracketCheck.test.ts`
Expected: PASS — 11 个用例全绿。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.ts cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.test.ts
git commit -m "feat(tabjoin): 新增公式圆括号数量+顺序校验纯函数 checkParenBalance

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 接入抽屉 UI（实时红字 + 保存禁用 + save 兜底）

**Files:**
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`

参考现有结构：`save()` 在 `:111`，`<FormulaRichInput>` 在 `:282`，保存 `<Button>` 在 `:210`。当前从 `'antd'` 已导入 `message`、`Button`、`Space`、`Text`（`Typography.Text`）等；需确认是否已导入 `Tooltip` 与 React 的 `useMemo`，缺则补。

- [ ] **Step 1: 确认/补充导入**

打开 `TabJoinFormulaDrawer.tsx`，确认顶部：
- 从 `'antd'` 的导入里包含 `Tooltip`（若缺，加入该具名导入）。
- 从 `'react'` 的导入里包含 `useMemo`（若缺，加入）。
- 确认已从 `'./tabjoin/...'` 同级目录可导入新函数。

在已有 import 区新增一行：

```ts
import { checkParenBalance } from './tabjoin/formulaBracketCheck';
```

- [ ] **Step 2: 计算校验结果（useMemo）**

在组件函数体内、`save` 定义之前（`expression` state 声明之后）插入：

```ts
const parenCheck = useMemo(() => checkParenBalance(expression), [expression]);
```

- [ ] **Step 3: `save()` 兜底拦截**

在 `save` 函数开头、`const expr = expression.trim();` 之后、组件类型分支之前，紧跟现有空表达式检查后插入：

```ts
    const pc = checkParenBalance(expr);
    if (!pc.ok) {
      message.error(pc.error);
      return;
    }
```

放置位置示例（现有代码 + 新增）：

```ts
  const save = () => {
    const expr = expression.trim();
    if (!expr) {
      message.error('表达式不能为空');
      return;
    }

    const pc = checkParenBalance(expr);
    if (!pc.ok) {
      message.error(pc.error);
      return;
    }

    // EXCEL 组件：...（以下保持原样）
```

- [ ] **Step 4: 保存按钮禁用 + Tooltip**

把抽屉 `extra` 里的保存按钮（现 `:210`）：

```tsx
          <Button type="primary" onClick={save}>
            保存
          </Button>
```

改为：

```tsx
          <Tooltip title={parenCheck.ok ? '' : parenCheck.error}>
            <Button type="primary" onClick={save} disabled={!parenCheck.ok}>
              保存
            </Button>
          </Tooltip>
```

- [ ] **Step 5: 输入框下方实时红字**

在 `<FormulaRichInput ... />`（现 `:282`，找到该元素的闭合 `/>`）之后、紧贴其下插入：

```tsx
      {!parenCheck.ok && (
        <Text style={{ color: '#cf1322', fontSize: 12, display: 'block', marginTop: 4 }}>
          {parenCheck.error}
        </Text>
      )}
```

（`Text` 即现有从 `antd` Typography 解构的 `const { Text } = Typography` 别名，沿用即可。）

- [ ] **Step 6: TS 编译自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 7: Vite transform 自检（改动的 .tsx 必须 200）**

Run:
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/TabJoinFormulaDrawer.tsx
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/tabjoin/formulaBracketCheck.ts
```
Expected: 两行都是 `200`。

- [ ] **Step 8: 回归单测**

Run: `cd cpq-frontend && npx vitest run src/pages/template/tabjoin/formulaBracketCheck.test.ts`
Expected: PASS（11 绿）。

- [ ] **Step 9: 提交**

```bash
git add cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx
git commit -m "feat(tabjoin): 公式输入框圆括号实时校验（红字提示+保存禁用+save兜底，全类型生效）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 记录 RECORD.md + 自检声明

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 追加开发记录**

在 `docs/RECORD.md` 末尾追加一行（格式：`[日期] 模块 - 描述 | 文件 | 决策`）：

```
[2026-06-13] 模板管理/页签连表公式 - 公式输入框新增圆括号()数量+顺序实时校验（红字提示+保存禁用+save兜底，全组件类型生效；扫描跳过[...]/{...}块以排除(总计)误判，仅查圆括号，[]/{} 仍由 lex() 保存时报） | cpq-frontend/.../tabjoin/formulaBracketCheck.ts(新) + .test.ts(新) + TabJoinFormulaDrawer.tsx | checkParenBalance 纯函数，深度计数法；不触发 E2E（不涉及 driver/snapshot/字段类型协议文件）
```

- [ ] **Step 2: 提交**

```bash
git add docs/RECORD.md
git commit -m "docs(record): 页签连表公式括号实时校验记录

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 3: 输出"已自检"声明**

向用户报告一行，例如：
> "TS 0 错误 ✅；TabJoinFormulaDrawer.tsx + formulaBracketCheck.ts → Vite 200 ✅；vitest 11 passed ✅；本改动不涉及协议文件，无需 E2E。"

---

## Self-Review

**1. Spec 覆盖：**
- §4.1 核心函数 → Task 1 ✅
- §4.2 UI 接线（红字 / 禁用 / save 兜底 / 全类型）→ Task 2 ✅
- §5.1 单测 8 类用例 → Task 1 Step 1（含 11 个用例，覆盖全部）✅
- §5.2 自检（tsc / curl / vitest / 无 E2E）→ Task 2 Step 6-8 ✅
- §6 流程（worktree → TDD → 自检 → 合并）→ 由 subagent-driven-development + 收尾技能承接 ✅

**2. Placeholder 扫描：** 无 TBD/TODO，所有代码步骤含完整代码 ✅

**3. 类型一致性：** `checkParenBalance` / `ParenCheckResult` / `{ ok, error }` 在函数定义、测试、UseMemo、save 兜底、按钮、红字处命名一致 ✅
