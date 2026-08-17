# 后端任务文档 · 核价单数据展示问题修复（task-0712）

> 交付标准：`docs/PRD-v3.md` + 本文档 + `api.md`。开工前必读 `docs/RECORD.md`、`docs/方案制定前必读.md`、`docs/核价树页签组件配置指南.md`。
> 改动涉及 `CardSnapshotService` / `CostingTreeRenderService` → **协议级**，收尾必跑 E2E（见 §6）。

## 1. 根因（已复现坐实，务必先读）

| 事实 | 证据 |
|------|------|
| `V6QuotationCommitService.createQuotation`（全 103 行）**只建空报价单 + 写 hfPairs 到 import_record**，**不建明细行、不算任何卡片值** | 方法体 36–89，grep 零 `CardSnapshot/cardValues` |
| 明细行由**前端 autoPopulate** 客户端建（`listCustomerPartCandidates` + `buildLineItemFromTemplate` → saveDraft） | `QuotationWizard.tsx:816–855` |
| 卡片值由**前端 warm** 异步补算（`ensureCardValues`） | `QuotationWizard.tsx:709–724` |
| 只读面（详情/核价管理）不触发 warm，直接读持久化 `costingCardValues`，NULL → 「无组件数据」 | `ReadonlyProductCard.tsx:271` `useSnap=!!costingCardValues` |
| **后端已具备计算+落库能力**：`ensureCardValues` 调用后 `costing_card_values` 由 NULL→非空、`card_snapshot_at` 落时间（实测） | curl 实测 |
| 核价树 `render()` **多根一次批量查询**（1 根 14 节点 / 2 根批量正确），**非 N+1** | 直连 DB 实测 |

**结论**：把「明细行 + 4 份卡片值」的物化上移到 `createQuotation` 同步完成并落库。

---

## 2. 方案（推荐 A，含轻量替代 B）

### 方案 A（推荐）：`createQuotation` 服务端整单物化
建单时服务端一次做完：建明细行 → 批量算并落全部卡片值。三面（编辑/详情/核价管理）开箱即用，**彻底不依赖前端时机**。

### 方案 B（轻量替代）：仅服务端补算卡片值，明细行仍由前端 autoPopulate 建
改动小，但「用户建单后不打开编辑页直接进详情/核价管理」时明细行尚不存在 → 仍「无组件数据」。**不满足需求（三面开箱）**，仅作降级备选。

> 本文档按**方案 A** 拆任务。若评审改选 B，Task 2 去除、Task 3 触发点改为 saveDraft 尾部。

---

## 3. 任务拆分（方案 A）

### Task 1｜服务端建明细行能力
- **目标**：后端能从「导入候选 + 模板结构」建出与前端 `buildLineItemFromTemplate` 等价的明细行并落库。
- **做法**：
  - 复用 `CustomerPartCandidateService.listCandidates(customerId, importRecordId)` 取候选（`product_part_no` / `customer_part_no` / `hfPartInfo` 等）。
  - 新增 `QuotationLineItemMaterializeService`（或并入 `V6QuotationCommitService`）：按 `customerTemplateId` + `costingTemplateId` 的组件结构，为每个候选建 `QuotationLineItem`（`product_part_no_snapshot` / `customer_part_no` / `template_id` / `sort_order` / `composite_type` 等），字段对齐前端 `buildLineItemFromTemplate`（`cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx:176`）。
  - `componentData` scaffold 由模板快照结构生成（不写死值，值由 Task 3 算）。
- **注意**：AP-54 过滤下标、SUBTOTAL 组件不建为数据行；`sort_order` 与前端一致（从 0 递增）。
- **产出**：单测覆盖「N 候选 → N 行，字段/顺序正确」。

### Task 2｜`createQuotation` 编排：建行 + 同步物化
- **文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java`
- **改动**：`quotationService.create(...)` 后新增：
  1. 调 Task 1 建明细行并落库（`em.flush()`）。
  2. 调 Task 3 整单批量算并落卡片值。
  3. 回写 `import_record`（hfPairs 逻辑不变）。
  4. `CommitResult` 扩展 `lineItemsCount / cardValuesReady / costingTreeRows / warnings`（见 api.md §1）。
- **事务**：明细行落库与卡片值落库需保证「不丢单」——建单 + 建行为强一致；卡片值算失败降级为 NULL（不回滚整单），见 §5。

### Task 3｜整单批量计算并持久化卡片值
- **目标**：一次算完整单 4 份卡片值（`quote_card_values / quote_excel_values / costing_card_values / costing_excel_values`）并落 `quotation_line_item`，`card_snapshot_at` 落时间。
- **做法**：**复用 `ensureCardValues` 背后的 `CardSnapshotService` 落库路径**（已确认持久化），把「按 quotationId 计算+落库」抽成可服务端直接调用的方法（若现仅经 Resource 暴露，则抽 service 方法）。
- **核价树硬约束**：
  - 走 `CostingTreeRenderService.render(costingTemplateId, allLines)` **整单多根一次批量**（见 `CardSnapshotService:494/838`），**禁止**逐行 `render(List.of(li))`（`CardSnapshotService:618/1212` 是既有单行兜底，本路径不得退化到它）。
  - 树页签契约：驱动视图（如 `$pj_view`）须输出 `parent_no + material_no`；生效递归 SQL 取 `costing_bom_tree_config WHERE is_active`（当前「核价树V1」）。
- **产出**：单测「多产品单 → 每行 costingCardValues 非空、核价树行数=递归节点数」。

### Task 4｜幂等与重入
- 同 `importRecordId` 重复 `create-quotation`：若 `import_record.quotationId` 已存在且单未删，返回既有 `quotationId`，不重复建单/建行。
- 若前端仍会跑 autoPopulate（过渡期），Task 1 建行需与前端 saveDraft **按 product_part_no + sort_order 去重合并**，防重复行（配合 fronttask Task 1 关闭导入流 autoPopulate 建行）。

---

## 4. 关键既有代码坐标

| 用途 | 位置 |
|------|------|
| 导入建单入口 | `basicdata/v6/resource/BasicDataImportV6Resource.java:114` `createQuotation` |
| 建单编排（本次主改） | `basicdata/v6/service/V6QuotationCommitService.java:36` |
| 候选查询（复用） | `quotation/service/CustomerPartCandidateService.java:42` `listCandidates` |
| 卡片值计算+落库引擎 | `quotation/service/CardSnapshotService.java`（含核价树分支 494/615/838/1114） |
| 核价树批量渲染 | `quotation/service/CostingTreeRenderService.java:79` `render(templateId, lineItems)` |
| ensure 端点（保留兜底） | `quotation/resource/QuotationResource.java`（`ensure-card-values`） |
| 生效递归 SQL 配置表 | `costing_bom_tree_config`（`is_active=true` → 「核价树V1」） |

## 5. 事务/降级纪律
- **不丢单**：报价单 + 明细行落库成功即视为建单成功；卡片值计算失败**不回滚**整单。
- 单行核价树失败 → 该行 costingCardValues 落失败哨兵（沿用 BL-0030/`__cardValueFailed`），`cardValuesReady=false` + `warnings`，**不拖垮其他行**（`CostingTreeRenderService` 批量层 try/catch 逐行落哨兵）。
- 严禁「视图 DROP CASCADE 后不重启」类缓存坑（见 CLAUDE.md）；本次不动视图 DDL。

## 6. 强制自检（完成前必跑，报告须含「已自检」行）
1. `cd cpq-backend && ./mvnw -o test`（新增单测 + `CostingTreeRenderServiceTest` / `CardSnapshotService` 相关全绿）。
2. `touch` 一个 java 触发 Quarkus 重启 → `curl .../api/cpq/health` 200。
3. **协议级 E2E**（改了 `CardSnapshotService`/`CostingTreeRenderService`）：
   `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/costing-bom-tree.spec.ts --reporter=list` → 全 pass，各核价 tab「加载中=0」。
4. **端到端契约自检**（api.md §4）：导入建单后**不打开编辑页、不手刷** → `GET /quotations/{id}` 每行 `costingCardValues` 非空、树行数正确；DB `costing_card_values IS NOT NULL` 全 `t`。
5. 多产品单验证批量（无 N+1）：日志确认核价树只跑**一次**递归查询覆盖所有根。

## 7. 边界（不做）
- 不改公式计算逻辑、不改报价侧展示逻辑（需求边界）。
- 不改核价树递归 SQL 语义 / 不改 `bom_recursive_expand` 标记机制。
- 不引入异步补算+轮询（首版走同步；大单性能问题另立 backlog）。
