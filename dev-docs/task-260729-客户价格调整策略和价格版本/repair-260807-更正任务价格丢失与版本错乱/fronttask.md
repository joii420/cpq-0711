# fronttask · repair-0807 更正任务价格丢失与版本错乱

- 对应需求文档：`./需求文档.md`
- 接口契约：`./api.md`
- 分支：`fix/repair-0807-price-update-loss`
- 角色：`cpq-frontend`

> **本次前端改动很小（2 个文件、3 处）**，但**不是零改动** —— 后端扩了一个 TS 联合类型的取值域，映射表不补会直接编译不过。下面同时写明「哪些看起来该改、实际不该改」，避免顺手扩大改动面。

---

## 1. 改动清单

| # | 文件 | 改动 | 对应 FR/AC |
|---|---|---|---|
| F1 | `src/types/price-adjust.ts` | `JobItemStatus` 加 `'SKIPPED'`；`JobDTO` 加 `skippedCount: number`；`ReviewQuotationDTO` 加 `adjustedComputed: boolean` | FR-4 / FR-5 |
| F2 | `src/pages/pricing/price-adjust-jobs/JobProgressDrawer.tsx` | `ITEM_STATUS_TAG` 补 `SKIPPED`；跳过行不出「重试」按钮；汇总区显示跳过数 | FR-4 / AC-8 |
| F3 | `src/pages/pricing/price-adjust-review/ReviewDetailDrawer.tsx` | 「调整后小计」按 `adjustedComputed` 三态渲染 | FR-5 / AC-9 |

---

## F1 · `types/price-adjust.ts`

```ts
export type JobItemStatus =
  | 'WAITING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CONFLICT' | 'STALE'
  | 'SKIPPED';                     // repair-0807 FR-4：该单未被更新，且重试无意义

export interface JobDTO {
  // …既有字段不动
  skippedCount: number;            // repair-0807 FR-4
}

export interface ReviewQuotationDTO {
  // …既有字段不动
  quoteSubtotalAdjusted: number | null;
  adjustedComputed: boolean;       // repair-0807 FR-5：false = 未试算（≠ 无数据）
}
```

🔒 `JobItemStatus` 是联合类型，`ITEM_STATUS_TAG` 是 `Record<JobItemStatus, …>` —— **加了取值不补映射表会编译不过。这是好事，别用 `Partial<>` 或 `as any` 绕过。**

---

## F2 · `JobProgressDrawer.tsx`（屏 6a 更新执行进度）

### F2-1 状态标签（`:16-23`）

```ts
const ITEM_STATUS_TAG: Record<JobItemStatus, { color: string; label: string }> = {
  WAITING:  { color: 'default',    label: '等待' },
  RUNNING:  { color: 'processing', label: '执行中' },
  SUCCESS:  { color: 'green',      label: '成功' },
  FAILED:   { color: 'red',        label: '失败' },
  CONFLICT: { color: 'orange',     label: '冲突' },
  STALE:    { color: 'default',    label: '已失效' },
  SKIPPED:  { color: 'gold',       label: '已跳过' },   // repair-0807
};
```

**配色理由（别改成 default 或 green）**：`SKIPPED` 语义是"这张单没被更新"，是财务**需要注意**的信息，不是中性的完成态。灰色（`default`）会和「已失效」混，绿色会重演本次缺陷"看起来成功了"的错觉。金色与 `AMBER` 告警色系一致。

### F2-2 跳过行不显示「重试」（`:137`）

现有判断是 `if (r.status !== 'FAILED' && r.status !== 'CONFLICT') return null;` —— **该判断已天然把 `SKIPPED` 排除在外，无需改动**。但要在 `SKIPPED` 行的原因列显示 `errorMessage`：现有渲染逻辑（`:118-136`）若只在 `FAILED/CONFLICT` 分支读 `errorMessage`，需扩到 `SKIPPED`。

跳过行的原因文案直接展示后端 `errorMessage`（如「冻结结构已补建，但仍无接价格策略的组件…」），**不要在前端重写文案** —— 后端区分了"补建成功但无组件"与"补建失败"两态，前端自造文案会把这个区分抹掉。

### F2-3 汇总区

`Progress` 上方的汇总行加一项「已跳过 N」，仅 `skippedCount > 0` 时显示（为 0 时不占版面）。`job.status === 'PARTIAL'` 时 `Progress` 的 `status` 取 `'normal'`（不是 `'exception'` —— 跳过不是失败）。

---

## F3 · `ReviewDetailDrawer.tsx`（屏 4 料号审核抽屉）

### F3-1 「调整后小计」三态渲染（`:124`）

```tsx
{
  title: '调整后小计', dataIndex: 'quoteSubtotalAdjusted', align: 'right' as const,
  // repair-0807 FR-5：三态，不能塌成两态。
  //   adjustedComputed=false → 「未试算」（设计内：只对判断依据单试算，其余仅作参考）
  //   adjustedComputed=true 且有值 → 数值
  //   adjustedComputed=true 但值为 null → 「—」（试算跑了却没拿到值 = 异常态，必须与"未试算"区分开）
  render: (v: number | null, r: ReviewQuotationDTO) => {
    if (!r.adjustedComputed) {
      return <Tooltip title="仅对判断依据单试算，其余单据仅作参考"><span style={{ color: 'rgba(0,0,0,.35)' }}>未试算</span></Tooltip>;
    }
    return fmt(v);
  },
}
```

🚨 **不要写成 `v == null ? '未试算' : fmt(v)`** —— 那会把"试算失败"也说成"未试算"，正是本次缺陷家族（用一个占位符掩盖两种完全不同的状态）的老毛病。判据必须是 `adjustedComputed` 这个显式布尔，不是值是否为空。

### F3-2 财务自检行（`:137-142`，无需改代码）

`impactCheck` 的现有逻辑在 `quoteSubtotalAdjusted` 有值后**自动生效**，不用改。但要**验证**它显示出来了（AC-10）—— 这一段至今从未在真实数据下渲染过。

### F3-3 元素明细两列（`:82-83`，无需改代码）

「该料号用量」「对单价影响」的渲染逻辑已存在（`v == null ? '—' : v`），后端填上值即自然显示。但要检查 `usageQty` 的显示精度：后端返 6 位小数（如 `0.001159`），现有 render 是 `v` 裸输出，会显示成 `0.001159`，可接受；**不要套 `fmt(v)`**（它固定 2 位，0.001159 会显示成 `0.00`，等于把数据显示丢了）。

---

## 2. 明确不改的地方（防止改动面扩大）

| 位置 | 为什么不改 |
|---|---|
| `ComponentCell.tsx` 的 `priceLocked` / `priceVersionNo` 渲染分支（`:626-639`） | 徽标错乱的根因在后端写入侧（`__priceVersion` 没被刷新），渲染层逐字正确。**动它只会把后端的错掩盖掉** |
| `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` 的 `__priceLocked ?? rawRow.__priceLocked` 透传（`:3706` / `:909`） | 同上。两处透传逻辑是对的，后端修好后自然显示正确 |
| `ComponentCell.tsx:458` 的 `pathCache` key 缺行维度 | 真缺陷，但属另一族（编辑页首屏取到别行值）。已登记 `BL-0149`，本次不动 —— 修完根因 B 后该格不再为空、触发条件消失，改它会与本次修复的验证互相干扰 |
| `ReviewDetailDrawer` 的 `MISSING_SIDE_LABEL` / `cellStyle` | 与本次无关 |

---

## 3. 边界与空态

| 场景 | 期望 |
|---|---|
| 后端返回旧结构（`adjustedComputed` 缺失） | `!r.adjustedComputed` 为 `true` → 显示「未试算」。**降级安全**，不崩 |
| `skippedCount` 缺失 | 汇总区不显示该项，不显示 `undefined` |
| 抽屉打开变慢（后端含 dryRun，单元素约 2s、三元素约 8s） | 沿用既有 `<Spin tip="加载中…">`，**不加超时、不加取消按钮**。超时会让财务以为功能坏了 |
| 判断依据单已被删除 | 后端返全部 `adjustedComputed=false` + `elementImpactTotal=0`；页面不报错，自检行不显示 |

---

## 4. 自检项

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**（重点看 `Record<JobItemStatus, …>` 是否已补全 `SKIPPED`）
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/pricing/price-adjust-jobs/JobProgressDrawer.tsx` → 200
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/pricing/price-adjust-review/ReviewDetailDrawer.tsx` → 200
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/types/price-adjust.ts` → 200
- [ ] **E2E 触发判定**：本次未改 `QuotationStep2.tsx` / `useDriverExpansions.ts` / `ReadonlyProductCard.tsx` / `ComponentCell.tsx`，按 `CLAUDE.md`「修改后强制自检」第 5 条**不强制**跑 E2E。但**后端改了 `CardSnapshotService` 的调用时机（FR-3 的 `ensureStructure`）与卡片值内容**，故 `quotation-flow.spec.ts` 仍须跑一次作回归（AC-16），由主线在亲验环节执行
- [ ] 截图证据：屏 6a 含 `SKIPPED` 行、屏 4 的「未试算」列 + 财务自检行 + 元素明细两列有值

---

## 5. Task 列表

- [ ] **F1** `types/price-adjust.ts` 三处类型扩展
- [ ] **F2-1** `ITEM_STATUS_TAG` 补 `SKIPPED`（金色/已跳过）
- [ ] **F2-2** `SKIPPED` 行显示后端 `errorMessage` 作为原因，不出重试按钮
- [ ] **F2-3** 汇总区「已跳过 N」+ `PARTIAL` 时 Progress 不用 exception 态
- [ ] **F3-1** 「调整后小计」三态渲染（`adjustedComputed` 为判据，非空值判据）
- [ ] **F3-2** 验证财务自检行在真实数据下渲染（无需改码，需出证据）
- [ ] **F3-3** 验证 `usageQty` 精度不被 `fmt` 吃掉
