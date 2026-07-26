# fronttask — task-0725 修复报价单页签无法显示数据

> 需求 §4.2 原判断「无前端改动」**不成立**（2026-07-25 架构评估修正）。
> 前端有**一处必做改动**：给实时 batch-expand 传业务侧别。
> 除此之外无 UI / 布局 / 交互改动 —— 页签结构、列、编辑协议全不变。

---

## 1. 为什么前端必须改

后端要决定「是否为这次 driver 展开打开报价侧 pending 可见域」，但当前**拿不到任何可区分侧别的信号**：

```
QuotationStep2.tsx:3362  useDriverExpansions(… lineItems …,        customerId, quotationId)  // 报价侧
QuotationStep2.tsx:3364  useDriverExpansions(… costingLineItems …, customerId, quotationId)  // 核价侧
                                                                               ↑ 同一个 quotationId
```

两侧共用同一个 hook、同一个 `quotationId`，请求体 `tasks[]` 里也没有侧别字段。若后端按 quotationId 判断，核价侧也会被开启 pending 改写 → **破 AC-17 核价零回归**（症状是静默数据漂移，不报错，只能靠等价性测试发现）。

所以侧别信息**只能由前端显式传下来**。

---

## 2. 改动清单

### F1 — `useDriverExpansions` 增加 `usage` 入参

**文件**：`cpq-frontend/src/pages/quotation/useDriverExpansions.ts`（⚠️ 协议级文件，改动强制跑 E2E）

1. hook 入参增加 `usage: 'QUOTE' | 'COSTING'`
2. 组装 `batch-expand` 请求体时，把 `usage` **写进每个 task**（`tasks[].usage`，不是顶层）
3. ❌ **不要动 `driverExpansionKey` / fingerprint**（2026-07-25 评审修正，原第 3 步作废）

   原来写的「必须补 `usage` 维度」是**错的，且做了会引入它声称要防的 AP-31**：

   - `useDriverExpansions.ts:166` 的 `const [cache, setCache] = useState<DriverExpansionMap>({})` —— 缓存是**每个 hook 实例私有的 useState**，不是模块级。报价侧与核价侧是**两个 hook 实例、两个独立 cache 对象**，结构上**不存在跨侧命中**。
   - `driverExpansionKey`（`:130-137`，6 个位置参数）是**导出**的，键在多处**独立重建**用于查找：`QuotationStep2.tsx:8`、`ReadonlyProductCard.tsx:5`、`buildExcelSnapshot.ts:44`、`buildSnapshotExpansions`，另加 4 个 test 文件。
   - 加一个维度而不把所有生产方/消费方同步改到位 → **键错位 → 正是「加载中…」永久占位**。

   → **`usage` 只进请求体，不进任何前端缓存键。**

### F2 — callsite 按所在视图传值

| 文件 | 位置 | 传值 | 说明 |
|------|------|------|------|
| `QuotationStep2.tsx` | `:3362` | `'QUOTE'` | 报价侧产品卡 |
| `QuotationStep2.tsx` | `:3364` | `'COSTING'` | 核价侧产品卡 |
| `QuotationWizard.tsx` | `:135` | 按所在视图判定 | 若同时承载两侧则需分别传 |
| `ReadonlyProductCard.tsx` | `:283-285` | **由已有的 `side` prop 派生** | ✅ 该组件**已经知道自己是哪一侧**：`:194 const isCosting = side === 'COSTING'`。直接 `usage = isCosting ? 'COSTING' : 'QUOTE'`，**不需要新加 prop、不需要改任何调用方** |

> 2026-07-25 评审更正：原文说「必须由调用方把 `usage` 作为 prop 传进来，不要在组件内部猜」—— 对 `ReadonlyProductCard` 而言这是多余的，它本来就有权威的 `side` prop，从中派生**不是猜**。这让前端改动比原计划小很多。
>
> 另需知悉（决定了这项工作的真实价值）：`QuotationStep2.tsx:3353` 的 `useSnapCosting = costingLineItems.length > 0` 意味着**核价侧自 2026-07-03 起就不再发实时 batch-expand**（`:3347-3352` 有决策注释）。今天唯一还会走核价侧实时展开的是 `ReadonlyProductCard:283-285`（`isCosting && !costingCardValues`，即存量单详情页）。需求方已决策**仍然加**（纵深防御 + 前端闸门随时可能改回），但实施者应知道这条路径今天很窄，别为它过度设计。

### F3 — 不需要改的地方（反向清单，避免过度改动）

- 页签结构 / 列定义 / 单元格渲染分支：**不变**
- 编辑协议（`handleRowChange` / `handleInputBlur` / `handleAddRow` / `handleDeleteRow` / `dsStateKey`）：**不变**
- 树页签的 `__nodeId` 行键逻辑：**不变**
- 小计计算：**不变**（值会从 0 变成真实汇总，属数据变化不是逻辑变化）
- 不新增页面、不新增 Drawer、不改列表页

---

## 3. 验收点

### 功能

1. 报价单 Step2 报价侧产品卡各页签显示数据（逐页签期望行数见 `test.md`）
2. 核价侧产品卡渲染**与修复前逐位相同**（这是 AC-17 的前端观测面）
3. 报价单详情页（`ReadonlyProductCard`）与编辑页显示一致（AP-50）
4. F12 Network 观察 `batch-expand` 请求体：报价侧 task 带 `"usage":"QUOTE"`，核价侧带 `"usage":"COSTING"`

### 必须为 0 / 必须不出现

5. **不得出现 "加载中…" 永久占位**（AP-31 族）—— 全部 8 Tab `'加载中'` 计数 = **0**
6. **不得出现「首值（共 N 项）」错乱**（AP-22 族）
7. **不得出现空 driver 的鬼魂行**（AP-38）：driver 返 0 行时 BASIC_DATA cell 应显示 `—` 而非降级到 globalPathCache 后卡在「加载中」
8. 切换 Tab / 切换报价↔核价视图后**不串数据**（两侧 hook 实例各自持有独立 cache，本项验的是这个结构假设仍成立）
9. **页面与 Excel 一致**：同一张 DRAFT 单，产品卡页签显示的数据与 Excel 视图值 / 导出内容一致，不得出现「页面有数据、导出空白」（对应后端 T3-P4）

### 强制自检（CLAUDE.md「修改后强制自检」）

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json          # 必须 0 错误

# 每个改动的 tsx/ts 都要拿到 200
for f in src/pages/quotation/useDriverExpansions.ts \
         src/pages/quotation/QuotationStep2.tsx \
         src/pages/quotation/QuotationWizard.tsx \
         src/pages/quotation/ReadonlyProductCard.tsx ; do
  printf "%-52s " "$f"
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' "http://localhost:5174/$f"
done
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/     # 主入口 200
```

> ⚠️ 路径以实际仓库结构为准（上面是示意）；`--noproxy '*'` 不可省（本机 `http_proxy=127.0.0.1:7890` 会让 localhost 走代理返 502）。
> dev server 全会话共享，**不要在 worktree 里另起 5174**，直接复用。

### E2E（强制，不是可选）

本次改动命中 `useDriverExpansions.ts` / `QuotationStep2.tsx` / `QuotationWizard.tsx` / `ReadonlyProductCard.tsx` —— 全部在 CLAUDE.md 列的「协议级改动必须跑 E2E」清单里。

```powershell
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```

**通过标准以 T0 基线实测为准**（需求方决策，问题 7）：开工前先在不改代码的情况下各跑一遍，若基线本来全绿则要求全绿；若基线带着已知无关失败（`docs/RECORD.md` 记载 07-23 时 `quotation-flow.spec.ts` 稳定 3 failed，夹具缺产品分类），则标准为「**相对基线无新增失败**」且失败签名逐字一致。

**无论哪种口径，以下不打折**：`'加载中' final count = 0`、全部 8 Tab `'加载中'=0`。
**PR 必附**：qf-19（确认添加后）+ qf-21~28（8 Tab）共 **9 张截图**作为渲染证据。

> 跳过 E2E 等于跳过自检 —— AP-31 / AP-37 / AP-38 / AP-40~43 这类协议 bug **只在 E2E 暴露**，TS 检查和 API 探活看不到。本次根因 1 本身就是这一族（不报错、只是静默空表）。

---

## 4. 排查辅助（若页签仍空）

按 `docs/E2E测试方法.md §4.6` 的 console.warn 三段式（LF-FIND / DEBUG / EVAL）定位。优先核对顺序：

1. F12 看请求体 `tasks[].usage` **是否真的传了**（最常见的漏 prop）
2. 看响应里 `usage=QUOTE` 的 task 结果**有没有 `__v6_id` 列** —— 有 = 后端改写生效了，问题在前端；无 = 后端作用域没开，回 `backtask.md` T3 查 set 点
3. 后端日志搜 `driver path=$xx rows=` —— `rows=0` 且无报错 = 后端作用域未开；`rows>0` 但 UI 空 = 前端问题
4. ⚠️ **不要去给 `driverExpansionKey` 加维度**（见 F1 第 3 步）—— 若怀疑缓存串数据，先确认两侧确实是两个 hook 实例；键错位造成的「加载中」比缓存串号更常见
