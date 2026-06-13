# 设计方案：页签连表公式 — 括号实时校验

> 日期：2026-06-13 ｜ 模块：模板管理 / 页签连表公式抽屉
> 状态：已确认，待 writing-plans

## 1. 背景与目标

「配置页签连表公式」抽屉（`TabJoinFormulaDrawer`）的公式表达式输入框（`FormulaRichInput`）目前没有针对圆括号的语法预检：

- NORMAL / SUBTOTAL 组件保存时虽会通过 `expressionToTokens` 抛"括号不匹配"等解析错（`message.error` toast 拦截），但只在点保存后才暴露；
- EXCEL 组件路径完全不查括号；
- 输入过程没有任何实时反馈。

**目标**：在输入框上增加一次基本语法检测 —— 圆括号 `()` 的**数量 + 顺序**校验，实时提示 + 保存硬拦截，对所有组件类型生效。

## 2. 需求边界（已与用户确认）

| 项 | 决策 |
|----|------|
| 触发时机 / 拦截力度 | **实时提示 + 保存硬拦截**（输入即时校验，不匹配则保存按钮置灰禁用） |
| 校验严格程度 | **数量 + 顺序**（栈/深度计数法，能抓 `)(`、`)a+b(` 等顺序错误） |
| 括号种类 | **仅圆括号 `()`**；`[]`/`{}` 配对仍由现有 `lex()` 在保存时报 |
| 生效范围 | **所有组件类型**（EXCEL / NORMAL / SUBTOTAL） |
| 错误文案 | **区分**"缺少右括号"vs"多了右括号 / 顺序错（无匹配的 `)`）"，给具体数量 |
| 错误展示 | 输入框正下方**红色小字** + 保存按钮置灰（hover tooltip 显示原因） |

## 3. 关键技术约束

`(总计)` 后缀使用 **ASCII 圆括号**，且总是出现在 `[...]` 字段块**内部**（如 `[COMP_RL.金额(总计)]`、`[alias(总计)]`，见 `formulaSerialize.ts` 文法注释）。

因此**朴素地对原始字符串数 `(` `)` 在概念上是错的** —— 这些不是分组括号。校验必须先跳过 `[...]` / `{...}` 块内字符，只对真正的分组括号计数。

## 4. 设计

### 4.1 核心校验函数（纯函数，可单测）

新增 `cpq-frontend/src/pages/template/tabjoin/formulaBracketCheck.ts`：

```ts
export interface ParenCheckResult { ok: boolean; error?: string }
export function checkParenBalance(expr: string): ParenCheckResult
```

扫描逻辑（数量 + 顺序，仅 ASCII 圆括号）：

1. 从左到右扫描字符。遇 `[` 跳到配对 `]`、遇 `{` 跳到配对 `}`（**整体跳过块内字符**）——根除 `(总计)` 误判。未找到配对闭合符时跳到串尾（圆括号检查不负责报 `[`/`{` 缺配对）。
2. 深度计数器：`(` 深度 +1，`)` 深度 -1。
3. 扫描中一旦深度 < 0 → 立即返回 `{ ok:false, error:'括号不匹配：多了 1 个右括号 ")"（出现无匹配的右括号）' }`。
4. 扫描结束深度 > 0 → `{ ok:false, error:'括号不匹配：缺少 N 个右括号 ")"' }`（N = 剩余深度）。
5. 否则 `{ ok:true }`。

空串 / 纯文本 → `ok:true`（深度 0）。

### 4.2 UI 接线（`TabJoinFormulaDrawer.tsx`）

```ts
const parenCheck = useMemo(() => checkParenBalance(expression), [expression]);
```

- **实时红字**：在 `<FormulaRichInput .../>` 正下方，仅当 `!parenCheck.ok` 时渲染一行红色小字（`color:#cf1322; fontSize:12`）显示 `parenCheck.error`。
- **保存禁用**：`<Button type="primary" disabled={!parenCheck.ok}>` 外包 `<Tooltip title={parenCheck.ok ? '' : parenCheck.error}>`，hover 显示原因（不用 `if return null` 隐藏按钮，符合项目 `enabledWhen` 风格）。
- **保存防御**：`save()` 开头、组件类型分支**之前**加兜底：
  ```ts
  const pc = checkParenBalance(expr);
  if (!pc.ok) { message.error(pc.error); return; }
  ```
  即使按钮被绕过也拦得住，且天然对全类型生效。
- 空表达式仍由现有"表达式不能为空"提示处理，二者不冲突（`checkParenBalance('')` 返回 ok）。
- 试算按钮 `runDryRun` **不**禁用（YAGNI）；括号不匹配时点试算走现有错误路径。

## 5. 测试与自检

### 5.1 TDD 单测（vitest，新增 `formulaBracketCheck.test.ts`）

| 用例 | 输入 | 期望 |
|------|------|------|
| 平衡 | `([单重]+[A.金额(总计)])*2` | ok |
| 缺右括号 | `([单重]+1` | 缺少 1 个 `)` |
| 多右括号 | `[单重])` | 多了右括号 |
| 顺序错 | `)(` | 多了右括号（先遇无匹配 `)`） |
| 块内括号排除 | `[COMP_RL.金额(总计)]` | ok |
| 嵌套 | `((1+2)*(3+4))` | ok |
| 空串 | `` | ok |
| 纯文本 | `abc+1` | ok |

### 5.2 改动后强制自检（CLAUDE.md）

- `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/TabJoinFormulaDrawer.tsx` → 200
- 同上 curl `formulaBracketCheck.ts` → 200
- vitest 跑新增 spec 全绿
- 本改动不涉及 driver expansion / snapshot / 字段类型协议文件，**不触发强制 E2E**

## 6. 开发流程

按 CLAUDE.md：`superpowers:using-git-worktrees` 建隔离 worktree → TDD 实现 → 自检 → 用户确认 → 自动合并 + 清理 worktree。

## 7. 影响面

- 新增文件：`formulaBracketCheck.ts`、`formulaBracketCheck.test.ts`
- 改动文件：`TabJoinFormulaDrawer.tsx`（新增 useMemo + 红字行 + 按钮禁用 + save 兜底）
- 不改：`FormulaRichInput.tsx`、`formulaSerialize.ts`、任何后端 / SQL
