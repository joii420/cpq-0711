# 前端任务文档 · 核价单数据展示问题修复（task-0712）

> 交付标准：`docs/PRD-v3.md` + 本文档 + `api.md`。开工前必读 `docs/RECORD.md`、`docs/E2E测试方法.md`、`docs/反模式.md`（AP-31/38/50/51）。
> 本次前端为**配合后端物化的收尾**：后端建单时已把明细行 + 卡片值算好落库（见 `backtask.md`），前端主要工作是**去掉客户端物化的时机依赖、保留 warm 兜底、修复只读面占位**。

## 1. 根因与前端定位（先读）

后端建单后三面数据已开箱落库（`api.md` 修复后）。前端遗留三个与「时机/回灌/占位」相关的点需收口：

| 现象 | 前端根因 | 文件 |
|------|---------|------|
| 编辑页核价树刷新前「加载中」 | 依赖 warm 异步补算；后端物化后应直接读到值 | `QuotationWizard.tsx` autoPopulate/warm |
| 导入流客户端重复建行/竞态 | autoPopulate 客户端建行 与后端服务端建行**重复** | `QuotationWizard.tsx:816–855` |
| warm 回灌整块放弃 | `syncLineItemsFromResponse` 行数不一致即 `return prev`（退化手刷） | `QuotationWizard.tsx:669–702` |
| 只读面「无组件数据」 | `useSnap=!!costingCardValues`，NULL 时无占位区分 | `ReadonlyProductCard.tsx:271/742` |

## 2. 任务拆分

### Task 1｜导入流关闭客户端 autoPopulate 建行（避免与后端重复）
- **背景**：后端 `create-quotation` 已服务端建明细行（backtask Task 1/2）。前端 autoPopulate 再客户端建行 → 重复行 / length 竞态。
- **改动**：`QuotationWizard.tsx` 导入流（`?autoPopulate=1`）：
  - 若 `GET /quotations/{id}` 返回的 `lineItems` **已非空**（后端已建行）→ **跳过** autoPopulate 建行，直接用服务端行渲染。
  - 仅当服务端行为空（后端降级/存量单）时，保留旧 autoPopulate 作**兜底**。
- **验收**：导入建单后编辑页明细行数 = 后端 `lineItemsCount`，无重复行。

### Task 2｜编辑页首屏直接读持久化卡片值，warm 降为兜底
- **改动**：编辑页 `loadQuotation` 后，若各行 `costingCardValues/quoteCardValues` 已非空 → **直接渲染，不再触发 warm**（`shouldWarmCardValues` 返回 false 即不发）。
  - 保留 warm 逻辑仅用于「存量单/后端降级 `cardValuesReady=false`」。
- **验收**：导入建单进编辑页核价单，**首屏即树、加载中=0、无手刷**。

### Task 3｜只读面占位区分（详情 / 核价管理）
- **文件**：`ReadonlyProductCard.tsx`（详情页 + 核价管理只读视图共用）
- **改动**：
  - 当 `costingCardValues` 非空 → 正常渲染核价树（现已支持，确认 DATA_SOURCE/LIST_FORMULA/树分支齐全，AP-50）。
  - 当为**失败哨兵**（`__cardValueFailed`，后端 §3 降级）→ 显示明确错误占位（「核价数据生成失败：<原因>」），**不显示笼统「无组件数据」**。
  - 当确为空模板（无组件）→ 保留「无组件数据」。
- **验收**：后端物化成功时详情/核价管理开箱显示树；降级时显式报错占位，不误导。

### Task 4｜`syncLineItemsFromResponse` 回灌健壮化（兜底路径）
- **文件**：`QuotationWizard.tsx:669–702`
- **改动**：行数不一致时不再整块 `return prev`——改为**按 `product_part_no_snapshot` + `sort_order`（或稳定 id）对齐回灌**能匹配的行，未匹配行保持原样（参考 AP-54 稳定下标映射纪律）。
- **验收**：warm 兜底场景下卡片值能就地回灌，不因个别行数差异退化到手刷。

## 3. 强制自检（完成前必跑，报告须含「已自检」行）

1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
2. 每个改动 `.tsx`：`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/<file>.tsx` → 200。
3. **协议级 E2E**（改了 `QuotationWizard.tsx` / `ReadonlyProductCard.tsx`）：
   ```
   npx playwright test --config=e2e/playwright.config.ts e2e/costing-bom-tree.spec.ts --reporter=list
   ```
   全 pass，各核价 tab「加载中=0」。
4. **导入端到端复现自检**（本任务核心，须真跑）：
   - 复用本目录 `../../cpq-frontend/e2e/repro-costing-tree-import.spec.ts`（技术总监已建，驱动真实导入）。
   - 断言：导入建单 → 进核价单 → **首屏（不手刷）加载中=0、配件树≥14 行**。
   - 另需人工/脚本验证：**建单后不打开编辑页，直接进报价详情页 + 核价管理 → 核价树正常显示，无「无组件数据」**（这是本次用户报障的三面之二）。
5. 附截图证据：编辑页核价单首屏、详情页核价侧、核价管理核价侧（修复前 vs 后）。

## 4. 关键文件坐标

| 用途 | 位置 |
|------|------|
| 导入建单抽屉 | `pages/quotation/QuoteBasicDataImportV6Drawer.tsx` |
| 向导/autoPopulate/warm/回灌 | `pages/quotation/QuotationWizard.tsx` |
| 编辑页核价卡片渲染 | `pages/quotation/QuotationStep2.tsx` |
| 只读面（详情+核价管理） | `pages/quotation/ReadonlyProductCard.tsx` |
| warm 门控 | `pages/quotation/cardValuesWarm.ts` `shouldWarmCardValues` |
| 客户端建行 | `pages/quotation/BulkImportPartsDrawer.tsx:176` `buildLineItemFromTemplate` |

## 5. 边界（不做）
- 不改报价侧展示逻辑、不改公式（需求边界）。
- 不改核价树渲染核心（`treeTable.ts` 树重排、DATA_SOURCE 解析）——后端物化后应直接可用；如发现渲染分支缺失再评估。
- 不改字段类型 / 组件配置协议（非本次范围）。
