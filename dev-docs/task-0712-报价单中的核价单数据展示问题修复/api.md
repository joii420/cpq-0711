# 接口文档 · 报价单中的核价单数据展示问题修复（task-0712）

> 本文档为前后端契约唯一标准。前端按「请求/响应」对接，后端按「行为约定」实现。
> 复现与根因见同目录 `需求说明.md` 第 11 节「复现与根因归档」。

## 0. 背景（一句话）

导入建单（`create-quotation`）只建**空报价单**、明细行与卡片值全靠前端 `autoPopulate` + `warm` 事后补算；
只读面（报价详情 / 核价管理）不触发 warm，直接读持久化的 `costing_card_values`（NULL）→「无组件数据」；
编辑页 warm 补算前 →「加载中」，warm 不稳定 → 刷新前永久加载中。

**修复核心：把「明细行 + 卡片值（报价 + 核价含核价树）」的物化从「前端事后补算」上移到「后端建单时同步完成并落库」。**

---

## 1. 变更接口：导入建单（同步物化）

### `POST /api/cpq/basic-data-import/v6/quote/create-quotation`

**语义变更**：由「只建空单」升级为「建单 + 建全部明细行 + 同步批量计算并落库全部卡片值」，返回时报价单已**完全物化**（任意面开箱即用）。

#### 请求（不变）
```jsonc
{
  "importRecordId": "uuid",      // Step1 导入产生的记录
  "customerId": "uuid",
  "name": "string",
  "categoryId": "uuid | null",
  "customerTemplateId": "uuid",  // 报价模板
  "costingTemplateId": "uuid"    // 核价模板（含核价树组件）
}
```

#### 响应（扩展字段）
```jsonc
{
  "code": 200,
  "message": "success",
  "data": {
    "quotationId": "uuid",
    "importRecordId": "uuid",
    "hfPairsCount": 12,          // 既有
    // ↓ 本次新增
    "lineItemsCount": 3,         // 服务端已建的明细行数
    "cardValuesReady": true,     // true=报价+核价卡片值已全部落库；false=降级(见§3)
    "costingTreeRows": 34,       // 本单核价树总节点数(便于自检/日志，可选)
    "warnings": ["string"]       // 部分料号无 BOM 树等非致命提示(可选)
  }
}
```

#### 行为约定（后端）
1. 建报价单（复用 `quotationService.create`）。
2. **服务端建明细行**：用 `CustomerPartCandidateService.listCandidates(customerId, importRecordId)` 取候选，按 `customerTemplateId`/`costingTemplateId` 结构建行并落库（替代前端 `autoPopulate` 建行）。
3. **同步批量算卡片值**：整单一次性调 `CardSnapshotService`（核价树走 `CostingTreeRenderService.render(costingTemplateId, allLines)` **多根一次批量**，禁止逐行 N+1），计算并**持久化** `quote_card_values / quote_excel_values / costing_card_values / costing_excel_values`。
4. 全部落库成功后再返回；`cardValuesReady=true`。
5. 幂等：同一 `importRecordId` 重复提交需可安全重入（不重复建行/不重复建单），策略见 backtask §4。

---

## 2. 依赖接口（不变，仅说明服务端复用）

| 接口 | 用途 | 变更 |
|------|------|------|
| `GET /api/cpq/quotations/{id}/candidates?importRecordId=...`（`listCustomerPartCandidates`） | 取导入候选料号 | 前端导入流不再单独调用；服务端 `create-quotation` 内部复用同一 `CustomerPartCandidateService` |
| `POST /api/cpq/quotations/{id}/ensure-card-values` | 计算+落库卡片值（**已确认会持久化**） | 保留作**兜底/存量单**入口；建单路径改为服务端主动算，不再依赖前端触发 |
| `GET /api/cpq/quotations/{id}`（详情/编辑加载） | 读整单（含 4 份卡片值快照） | 不变；物化后开箱返回非空 `costingCardValues` |

---

## 3. 降级与错误约定

- 若某产品行的核价树 SQL 计算失败（如种子料号无 BOM），**不整单失败**：该行 `costingCardValues` 落**显式失败哨兵**（沿用既有 `__cardValueFailed`/BL-0030 机制），`cardValuesReady=false` 且 `warnings` 列出料号；其余行正常。
- 只读面（详情/核价管理）对失败哨兵显示明确占位（非静默「无组件数据」），前端见 fronttask §3。
- `create-quotation` 若在算卡片值阶段抛未预期异常：报价单与明细行**已落库**（保证不丢单），卡片值置 NULL 并 `cardValuesReady=false`；前端进入编辑页时由既有 warm 兜底补算。

---

## 4. 自检契约（联调必过）

导入罗克韦尔.xlsx 建单后，**不打开编辑页、不手刷**，直接：
1. `GET /quotations/{id}` → 每个 lineItem 的 `costingCardValues` 非空，核价树 tab `baseRows` 行数 = 递归 SQL 节点数（如 S-3120014539 → 14）。
2. 报价详情页 → 核价侧各页签渲染树，无「无组件数据」、无「加载中」。
3. 核价管理进入 → 同上。
4. DB：`SELECT costing_card_values IS NOT NULL FROM quotation_line_item WHERE quotation_id=...` 全为 `t`，`card_snapshot_at` 非空。
