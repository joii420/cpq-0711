# 修复 Spec：`[页签(总计)]` 保存后被改写为 `[页签.列]`（机制 / 显示层修复，不动公式计算）

> 日期：2026-06-30 ｜ 模块：组件管理 · 页签连表公式（formulaSerialize）
> 类型：序列化往返忠实性修复（WYSIWYG）｜ **硬约束：不修改任何公式计算逻辑**
> 范围：仅前端 `formulaSerialize.ts`，**不动求值器 / 不动 Excel 裸键 / 不动配置快照 / 不处理存量数据**

---

## 1. 问题情况（现象）

「组件管理 → 页签连表公式」抽屉中，用户点「页签总计」按钮插入 `[产品(总计)]`，**点保存后该公式被显示成 `[产品.汇率]`**。即：用户所选的内容与保存后看到的内容不一致，系统静默改写了用户的输入。

> 用户诉求（权威）：
> 1. **所见即所存** —— 我选 `[产品(总计)]`，保存后就该还是 `[产品(总计)]`；
> 2. **不许改动公式计算逻辑** —— 求值结果必须与修复前一字不差。
>
> 这是**机制问题**（token 表示塌缩），与具体字段叫什么、是不是金额列无关。

---

## 2. 问题原因（根因，机制层）

`formulaSerialize.ts:818-847`，解析无点、以 `(总计)` 结尾的 `[产品(总计)]` 时，把"首个小计列名"塞进 token 的 `value`/`tab_name`：

```ts
const primarySubtotal = (tabDef.subtotalCols ?? [])[0] ?? '';   // 取首个小计列名
result.push({
  type: 'component_subtotal',
  value: primarySubtotal,        // 与列引用 token 同形
  tab_name: primarySubtotal,
  component_code: tabDef.alias,
  label: ...
});
```

而列引用 `[产品.汇率]` 走 `formulaSerialize.ts:798-815`，产出 `{type:'component_subtotal', value:'汇率', tab_name:'汇率', component_code:alias}`。

**两个不同输入塌缩成同一个 token**：当首个小计列恰为 `汇率` 时，`[产品(总计)]` 与 `[产品.汇率]` 的 token 逐字段完全相同。序列化器 `tokensToDrawerExpression:913-925` 拿到无法区分的 token，只能按"非空 value → 列形式"回显，于是 `[产品(总计)]` 显示成 `[产品.汇率]`。

**根因一句话**：`component_subtotal` token 缺少"整页签总计 vs 具体小计列"的判别维度，导致两种来源塌缩同形、序列化无法还原 `[页签(总计)]`。

> 注：本 token 的 `value` 同时被求值器读取。因此**不能用"清空 value"来修显示**——那会改变求值（违反约束 2）。必须在**不动 value** 的前提下增加判别维度。

---

## 3. 修复方案（加判别标记，value 原样保留）

### 3.1 改动点（仅 `formulaSerialize.ts` 两处，纯显示层）

**① 解析侧**：`[alias(总计)]` 分支（818-847 行）**保留 value 不变**，额外给 token 打标记。新增字段名建议 `is_tab_total: true`（布尔）：

```ts
result.push({
  type: 'component_subtotal',
  value: primarySubtotal,        // ← 原样保留，求值器照旧读它，计算不变
  tab_name: primarySubtotal,     // ← 原样保留
  component_code: tabDef.alias,
  is_tab_total: true,            // ← 新增：标记"这是整页签总计引用"，仅供序列化/显示区分
  label: ...
});
```

列引用分支（798-815 行）**不打标记**，保持原样。

**② 序列化侧**：`tokensToDrawerExpression` 的 `component_subtotal` 分支（913-925 行）**优先看标记**：

```ts
case 'component_subtotal': {
  const code = token.component_code ?? '';
  const label = tabDefs.find((d) => d.alias === code)?.componentName ?? code;
  if (token.is_tab_total) {           // ← 新增：有标记 → 整页签总计形式
    parts.push(`[${label}(总计)]`);
  } else {
    const col = token.value ?? '';
    parts.push(col ? `[${label}.${col}]` : `[${label}(总计)]`);   // 原逻辑兜底
  }
  break;
}
```

**③ 类型**：`FormulaToken`（`component/types.ts`）补可选字段 `is_tab_total?: boolean`。

### 3.2 为什么满足"不改动公式计算逻辑"（核心论证）

- **value / tab_name / component_code 全部原样保留**，新增字段是**纯附加**的布尔标记。
- 三处求值器只读 value / tab_name / component_code，**不读** `is_tab_total`，因此求值表达式逐字节不变：
  - 前端 `formulaEngine.ts:259-277`
  - 后端·报价 `FormulaCalculator.java:130-155`
  - 后端·配置 `FormulaCalculationService.java:187-192`
- **Excel 裸键 / 配置快照 / 折扣 / 产品小计兜底——一处都不碰**。修复前后这些路径读到的 token 数值字段完全一致 → 求值结果完全一致。

### 3.3 行为对照（修复后）

| 用户操作 | token | 求值（三处） | 显示回写 |
|---|---|---|---|
| 点 `产品(总计)` | value='汇率' + `is_tab_total:true` | 读 value → **与修复前完全相同** | `[产品(总计)]` ✅ |
| 点 `汇率(小计)` | value='汇率'（无标记） | 不变 | `[产品.汇率]`（不变） |

`[产品(总计)]` 与 `[产品.汇率]` 从此 token 可区分、各自忠实往返；两者求值均不变。

### 3.4 测试

`formulaSerialize.test.ts` 补对称往返用例：
1. `expressionToTokens('[产品(总计)]', ...)` → 断言 token 含 `is_tab_total === true` 且 `value` 仍为首个小计列（**计算不变**的证据）、`label` 为纯 componentName（收口项 1）→ `tokensToDrawerExpression(tokens, tabDefs)`（**2 参，对齐 `ComponentManagement.tsx:384` 真实调用**）→ 必须为 `[产品(总计)]`。
2. `expressionToTokens('[产品.汇率]', ...)` → 断言**无** `is_tab_total`、`value==='汇率'` → 序列化 → 必须为 `[产品.汇率]`（防回归）。
3. （求值不变佐证）对上述两个 token 用同一 `componentSubtotals` 跑 `evaluateExpression`，断言**两者结果与未加标记时一致**（标记不影响求值）。
4. （tabDef 缺失兜底）`tabDefs=[]` 时带标记 token 序列化 → `[<component_code>(总计)]`（与 col 分支兜底同构）。
5. （重映射保真，可选 Java/集成）带 `is_tab_total` 的 token 过 `FormulaRefRemapper` 后 `is_tab_total` 与 `value` 均保留。

### 3.4bis 收口项（第二轮评审补充，纳入本期范围）

1. **`is_tab_total` token 的 `label` 同步设为纯 componentName**（评审 [低]）：解析分支里 tab-total token 的 `label` 不再用 `产品·汇率`，直接置 `tabDef.componentName ?? tabDef.alias`（去掉 `·列名`）。消除 label 语义残留，并让任何"读 label"的显示路径不再回显旧列名。
2. **核实并处理 `FormulaZone.tsx` 备选显示路径**（评审 [中]）：`FormulaZone.tsx:98-104 getTokenLabel` 读 `token.label`（含 `·` 即直接返回）而非走 `tokensToDrawerExpression`。实现时先核实带 `is_tab_total` 的 token 是否可能流入 FormulaZone（其仅被 FormulaBuilder / SubtotalFormulaBar 使用，理论上不接收 `expressionToTokens` 产物）：
   - 若**确认不可达** → 在 spec / 代码注释中记录"不可达"结论，配单测 (b) 兜底；
   - 若**可达** → 让 `getTokenLabel` 对 `is_tab_total` 也回显 `${componentName}(总计)`。
   - 注：收口项 1（label 去 `·`）已使 `getTokenLabel` 的 `includes('·')` 短路失效 → 即便可达也会落到其兜底分支，需确认兜底产出可接受。

### 3.5 自检（CLAUDE.md 前端强制项）

- `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- `npx vitest run src/pages/component/formulaSerialize.test.ts` → 全绿
- `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/formulaSerialize.ts` → 200
- 手动复测：抽屉点 `产品(总计)` → 保存 → 列表仍显示 `[产品(总计)]`（不再翻成 `[产品.汇率]`）；试算/Excel 数值**与修复前一致**。
- **强制 E2E（CLAUDE.md「修改后强制自检」第 5 条触发）**：本 spec 改动 `cpq-frontend/src/pages/component/types.ts`（`FormulaToken` 加 `is_tab_total?`），属点名强制 Playwright E2E 文件。须跑
  `npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`
  并附 `1 passed` + `'加载中' final count = 0` 证据。改动虽为纯可选字段附加、求值零变化，E2E 主要作为"未破坏渲染链路"的回归护栏。

### 3.6 范围与不做项

- **不动公式计算**：求值器、Excel 裸键、配置快照、折扣、产品小计——全部不碰。
- **不处理存量数据**：修复前已存的、由 `[页签(总计)]` 塌缩成的 token 无 `is_tab_total` 标记，重开仍显示为 `[页签.列]`；需用户重存一次才带标记。本次不做迁移。
- **不引入"金额字段求和"等计算口径变更**：那属于"数字对不对"的另一议题，本 spec 只解决"所见即所存"，与计算无关。

---

## 4. 验收标准

1. 抽屉点 `[页签(总计)]` 保存后，列表表达式仍为 `[页签(总计)]`（不再变 `[页签.列]`）。
2. 该公式在报价单卡片 / Excel 视图 / 配置快照的求值结果**与修复前完全一致**（逐值核对，0 差异）。
3. `[页签.某列]` 列引用的解析 / 求值 / 显示与修复前一致（无回归）。
4. `formulaSerialize.test.ts` 新增 3 条用例通过；`tsc` 0 错误。
