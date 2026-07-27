# repair-0727 · 前端任务拆分（F1~F3）

> 契约：`api.md`；主文档：`需求说明.md`
> 目标：把「核价通过预览」从 V6 物理字段串改成**财务读得懂的产品级变更说明**（决策 3）

---

## F1 —— service 层类型对齐

**文件**：`cpq-frontend/src/services/costingOrderService.ts`

- `CostingApprovePreviewResult` 增 `products` / `globalShared`，`summary` 增 `affectedProducts`
- `CostingApprovePreviewGroup` 增 `productNo` / `productName` / `categoryLabel` / `route` / `baseSource` / `baseRowCount` / `resultRowCount` / `axisLabels`
- `CostingApprovePreviewRow`：`changes` / `values` 由对象改为数组（`{column,label,oldValue,newValue}` / `{column,label,value}`），增 `rowLabel` / `conflict`
- 同步 `quotationService.ts` 里若有重复定义则收敛到一处，禁两份类型漂移

---

## F2 —— 预览抽屉重做 ★核心

**文件**：`cpq-frontend/src/pages/quotation/CostingApprovePreviewDrawer.tsx`（重写渲染层，交互契约不变）

### 结构

```
┌ 核价通过预览 ────────────────────────── [取消] [确认通过] ┐
│ 摘要条：影响 2 个产品 · 新增 1 行 · 删除 2 行 · 改值 4 行   │
│ （无变更时保留现有 Alert「仅完成审核状态流转」）             │
│                                                          │
│ ▣ 产品卡片：S-3120014539 接触片组件 · 客户 苏州西门子       │
│   ├ BOM 组成        v2009 → v2010   4 行 → 4 行            │
│   │   · 改值 组成件 W-1001（外购件）：组成数量 1 → 2         │
│   │   · 删除 组成件 992（材质）                              │
│   ├ 材质元素构成    v2009 → v2010   2 行 → 2 行            │
│   │   · 改值 元素 Cu：损耗率 1.05 → 1.20                    │
│   └ 单价            v2009 → v2010   …                      │
│                                                          │
│ ▣ 全局共享变更（影响所有客户）  ← 红色警示区                │
│   └ 电镀方案 A0001  v2000 → v2001                          │
└──────────────────────────────────────────────────────────┘
```

### 要求

1. **按 `products` 渲染卡片**，卡内按 `categoryLabel` 分节；`globalShared` 单列一块，沿用红色 `Tag`「全局共享，影响所有客户」。
2. **行级展示**用 `rowLabel` + 中文 `label`：
   - CHANGE：`{label}：{oldValue} → {newValue}`（旧值删除线、新值加粗），多列纵向排列
   - ADD：绿色，列出 `values` 的中文名: 值
   - DELETE：红色删除线，同上
   - `conflict=true` 的行加橙色 `Tag`「多页签冲突，取先到值」
3. **不再展示** `system_type=QUOTE, price_type=…` 这类原始轴串；轴一律走 `axisLabels[].display`。
4. **默认折叠策略**：有变更的分节默认展开，`route=FLIP` 且 0 变更的分节默认折叠并标灰「仅版本转正，内容无变化」。
5. **保留**：加载态 Spin、加载失败 Alert+重试、409 自动重拉、500 关抽屉——逻辑与文案不变。
6. UI 规范：继续用 Drawer（宽度可提到 1000），组件用 antd；遵守 `docs/列表操作规范.md` 不适用（本页非列表）。

---

## F3 —— 自检

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- [ ] `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/CostingApprovePreviewDrawer.tsx` → 200
- [ ] `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/services/costingOrderService.ts` → 200
- [ ] 真实报价单走一遍：提交核价 → 财务点通过 → 截图抽屉（含产品卡片 + 全局共享区），与本文结构图比对
- [ ] 空变更场景截图（Alert 提示正确）

> 本次不改 `useDriverExpansions.ts` / `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` 等渲染主链路 → **E2E 非强制**；若实现中确实动了上述文件，按 CLAUDE.md §5 补跑 `quotation-flow.spec.ts`。
