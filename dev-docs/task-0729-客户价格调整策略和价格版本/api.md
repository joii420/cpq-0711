# 接口文档 · 客户价格调整策略与价格版本（task-0729）

> **产出日期**：2026-08-01 · 技术总监
> **上位文档**：同目录 `需求说明.md`（**唯一权威口径**）。本文只做契约拆解，**不产生新口径**；与需求说明冲突时以需求说明为准。
> **配套**：`backtask.md`（后端任务）/ `fronttask.md`（前端任务）/ `价格调整策略-原型图.html`（8 屏）
>
> **命名与包**：
> - 路径前缀统一 `**/api/cpq/price-adjust**`（与 task-0722 的 `/api/cpq/element-price` 平级但独立）
> - 后端包 `**com.cpq.priceadjust**`（参照 `com.cpq.elementprice` 的分层：`entity` / `dto` / `service` / `resource`）
>
> **本文档的三条铁律**：
> 1. 🔒 **判定逻辑全在后端，前端纯读标记** —— 前端不需要知道"调价清单"是什么，不新增拉取清单的接口（§11.15.2.8）
> 2. 🔒 **不新增报价单编辑相关端点** —— 单价列联动复用既有 `PUT /api/cpq/quotations/line-items/{id}/quote-card-edit`（§11.15.2.8「零新增 API」）
> 3. 🔒 **元素价格策略 Tab（task-0722）本期零改动** —— 不为它增删任何端点（§11.4.2）

---

## 0. 通用约定

### 0.1 权限

| 角色 | 权限范围 |
|------|---------|
| `PRICING_MANAGER` / `SYSTEM_ADMIN` | 本文**全部**端点（策略配置 / 审核 / 触发更新 / 更新任务与重试） |
| `SALES_REP` / `SALES_MANAGER` | **仅** §4 报价单侧的 3 个只读端点（版本轨迹 / 料号版本表 / 切版预览），且**限本人可见的报价单范围**（复用既有报价单可见性规则） |

- 实现方式：`@RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`。**该角色已存在**（实测 `user` 表有 `SYSTEM_ADMIN` / `PRICING_MANAGER`），无需新建。
- 销售访问 §4 端点时，服务端必须校验"该单是否对当前用户可见"，**不得**因为端点只读就跳过。

### 0.2 统一响应包装

沿用项目既有风格（与 `/api/cpq/element-price/*` 一致），不另造包装层。分页响应统一：

```jsonc
{ "content": [ ... ], "page": 1, "size": 20, "totalElements": 137, "totalPages": 7 }
```

### 0.3 错误码（本任务新增语义）

| HTTP | `code` | 含义 | 前端处置 |
|------|--------|------|---------|
| 400 | `STRATEGY_NO_ELEMENTS` | 参与调价元素为空，不能生成版本（E14-10） | 屏 1 就地提示，不弹全局 error |
| 409 | `PENDING_VERSION_EXISTS` | 已有待处理版本，需 `confirmSupersede=true`（E14-9） | 弹二次确认后重试 |
| 409 | `REVIEW_BUDGET_NOT_READY` | 该料号预算未算完，不可审核（E14-3） | 按钮本就该置灰，此为兜底 |
| 409 | `REVIEW_STATUS_CHANGED` | 料号已被他人处理 / 版本已被取代 | 刷新列表并提示 |
| 400 | `COMPONENT_ELEMENT_BINDING_REQUIRED` | 组件视图接了取价函数但三项角色字段未配全（§11.15.3.2） | 屏 8 字段级 `status='error'` |
| 422 | `SUBTOTAL_MISMATCH` | 升版口径守卫拦截（L3，§11.17.2） | 仅出现在 job_item 明细，不是同步响应 |

### 0.4 幂等与并发

- 所有 `POST` 动作端点（生成版本 / 通过 / 驳回 / 重试）**必须幂等**：重复提交同一批次不产生第二份效果。
- 版本生成幂等键 = `UNIQUE(customer_no, scheduled_slot)`；手动生成 `scheduled_slot=NULL`（PG 中 NULL 不参与唯一约束）。
- 升版写回并发保护 = **原生 SQL 自带 `row_version` + WHERE 条件**（F1 终版），受影响行数 0 → 该单标「冲突」。**不是 JPA `@Version`**。

---

## 1. 调价策略（屏 1）

### 1.1 `GET /api/cpq/price-adjust/strategies/{customerNo}`

取该客户调价策略主体。**策略不存在时返回 200 + `exists:false` 的空壳**（前端据此渲染"未配置"态），不返回 404。

**响应**
```jsonc
{
  "exists": true,
  "customerNo": "C001",
  "enabled": true,
  "cycleType": "MONTHLY_NTH_WEEK",     // DAILY | WEEKLY | MONTHLY_DAY | MONTHLY_NTH_WEEK
  "cycleWeekday": 3,                   // 1-7（WEEKLY / MONTHLY_NTH_WEEK 用）
  "cycleDayOfMonth": null,             // 1-31（MONTHLY_DAY 用）
  "cycleNthWeek": 2,                   // 1-5（MONTHLY_NTH_WEEK 用）
  "executeTime": "09:30",              // HH:mm，策略配置，不写死
  "materialScopeMode": "SPECIFIED",    // ALL | SPECIFIED
  "costDiffThreshold": 0.00,           // 成本差额预警线（金额，E13；默认 0）
  "latestVersionNo": "V26080501",      // 「最新已生成版本」——不是「当前生效」（§11.3.3(3)）
  "pendingVersionNo": "V26080501",     // 当前 PENDING 版本号，无则 null
  "materialCount": 3,
  "elementCount": 7,
  "hasComparisonConfig": true,
  "updatedAt": "2026-08-01T09:30:00+08:00",
  "updatedBy": "李财务"
}
```

### 1.2 `PUT /api/cpq/price-adjust/strategies/{customerNo}`

保存策略主体（不含料号/元素清单，各自独立端点）。

**请求**：同上响应的可写字段（`enabled` / `cycleType` / `cycleWeekday` / `cycleDayOfMonth` / `cycleNthWeek` / `executeTime` / `materialScopeMode` / `costDiffThreshold`）。

**服务端行为**
1. 一客户一条（`UNIQUE(customer_no)`），不存在则创建。
2. 写 `customer_price_adjust_strategy_log` 审计（变更前/后快照 jsonb）。
3. 🔒 **`costDiffThreshold` 或 `enabled` 变化 → 触发该客户全部 `待处理` 料号预算重算**（E11-1 四项之二），**走异步管道**（E14-3），响应立即返回。
4. 周期边界规则由服务端负责（每月 31 号小月顺延月末；第 5 周不足取当月最后一个该星期几）。

**响应**：`200` + 更新后的策略 + `{ "budgetRecomputeTriggered": true, "affectedReviewCount": 12 }`

### 1.3 `GET /api/cpq/price-adjust/strategies/{customerNo}/materials`

指定料号矩阵（屏 1），**分页 + 四筛选框模糊匹配可组合**。

**Query**：`page` / `size` / `customerPartNo` / `customerMaterialName` / `materialNo` / `materialName` / `selectedOnly`（"只看已选"）

**数据源**：`material_customer_map`（`system_type='QUOTE'` + 本客户）LEFT JOIN `material_master`

**响应**
```jsonc
{ "content": [
    { "materialNo": "10110002",           // 销售料号 —— 策略实际记录的就是它
      "materialName": "触头组件",
      "customerPartNo": null,             // 现网大量为空 → 前端显示「—」但照常可勾
      "customerMaterialName": null,
      "selected": true }
  ], "page":1, "size":20, "totalElements":128, "totalPages":7 }
```

### 1.4 `PUT /api/cpq/price-adjust/strategies/{customerNo}/materials`

**请求**
```jsonc
{ "materialNos": ["10110002","10110037"],
  "confirmRemoval": false }        // 有料号被移出范围时必须为 true，否则 409
```

**服务端行为**
1. 全量覆盖式保存（前端传最终全集，跨页保留由前端维护）。
2. 🔒 **移出料号需二次确认**（§11.2.3 / E11 断链 4）：若本次有料号被移出且 `confirmRemoval != true` → 返回 `409` + 影响面预览：
   ```jsonc
   { "code":"REMOVAL_NEEDS_CONFIRM",
     "removedMaterialNos":["10110037"],
     "pendingReviewCount": 2,           // 将作废退出待办池的待处理审核记录数
     "unlockedQuotationCount": 5 }      // 单价列将解锁为可编辑的存量单数
   ```
3. 确认后：移出料号的 `待处理` 审核记录置「已作废（移出范围）」；**`已通过` 的既成事实不回滚**。
4. 新增料号：本期有价格变动则纳入待办池并算预算，无变动不进（同裁决 39）。
5. 写审计日志 + **触发异步预算重算**。

### 1.5 `GET /api/cpq/price-adjust/strategies/{customerNo}/elements`

参与调价元素矩阵（屏 1），右侧 pivot **最近 10 个版本**的单价 + 涨跌幅。

**Query**：`page` / `size` / `keyword` / `includeDisabled`（默认 `true`，见下）

🔒 **性能硬约束（§11.2.4）**：**必须一次 pivot 查完**（元素 × 版本），**禁止逐元素查 10 次**。分页只分元素行，版本列固定。验收 #3 用 F12 Network 确认无 N+1。

**响应**
```jsonc
{
  "versionColumns": [                       // 按版本号倒序，最多 10；不足按实有
    { "versionId":"...", "versionNo":"V26080501", "status":"PENDING",      // 只有 PENDING | SUPERSEDED 两态
      "baseDate":"2026-08-05" },
    { "versionId":"...", "versionNo":"V26070501", "status":"SUPERSEDED", "baseDate":"2026-07-05" }
  ],
  "content": [
    { "elementCode":"Ag", "elementName":"银", "elementNo":"10001",
      "elementEnabled": false,              // 元素主表已停用 → 前端标「已停用」，仍照常参与调价
      "selected": true,
      "prices": [                           // 与 versionColumns 逐位对齐，长度相同
        { "unitPrice": 5820.00, "changeRate": 0.0679, "priceState":"NORMAL" },
        { "unitPrice": 5450.00, "changeRate": null,   "priceState":"NORMAL" }
      ] }
  ],
  "page":1, "size":20, "totalElements":37, "totalPages":2
}
```

🔒 **两种空值语义必须分开**（§11.2.4）：
- `priceState="NOT_IN_LIST"` → 前端渲染 `—`（该元素当时不在调价清单里，未参与该版）
- `priceState="NO_PRICE"` → 前端渲染 `无价`（在清单里但取价策略算不出值）

### 1.6 `PUT /api/cpq/price-adjust/strategies/{customerNo}/elements`

**请求**
```jsonc
{ "elementCodes": ["Ag","Cu","Ni"],
  "confirmUnselect": false }      // 有元素被取消勾选时必须 true，否则 409
```

**服务端行为**
1. 🔒 **取消勾选需二次确认**（§11.2.1 / E11 断链 1 之(2)）：`409` + `{ "code":"UNSELECT_NEEDS_CONFIRM", "removedElementCodes":["Ni"], "unlockedQuotationCount": 8 }` → 前端弹「该元素将退出调价机制，N 张存量单上它的单价列将解锁为可编辑，销售可能改动」。
2. 🔒 **禁止服务端自动移出已停用元素**（§11.2.1）—— 元素主表停用**不影响**它留在清单里、照常参与调价。
3. 写审计 + **触发异步预算重算**。

### 1.7 `GET /api/cpq/price-adjust/strategies/{customerNo}/logs`

策略变更历史（屏 1「🕘 变更历史」按钮）。分页。

**响应**
```jsonc
{ "content": [
   { "id":"...", "changedAt":"2026-08-01T09:30:00+08:00", "changedBy":"李财务",
     "changeType":"MATERIAL_SCOPE",   // STRATEGY | MATERIAL_SCOPE | ELEMENT_LIST | COMPARISON_COLUMN
     "summary":"指定料号 3 → 5（新增 10110088、10110091）",
     "beforeSnapshot": {...}, "afterSnapshot": {...} }
  ], ... }
```

### 1.8 `GET /api/cpq/price-adjust/strategies/{customerNo}/template-series`

屏 1 比对列配置区的**模板系列选择器**数据源（§11.5.4）。

**响应**
```jsonc
[ { "templateSeriesId":"a91209e6-...", "seriesName":"罗克韦尔模板1",
    "latestVersion":"v1.0", "isDefault": true, "templateCount": 1,
    "hasComparisonConfig": true, "columnCount": 3 } ]
```

### 1.9 `GET /api/cpq/price-adjust/comparison-columns`

**Query**：`customerNo`（必填）+ `templateSeriesId`（必填）

🔒 **配置维度 = 「客户 × 模板系列」**（§11.5.4 改判裁决 43），表 `comparison_column_config`。**未配置过时返回默认列**（`kind=PRODUCT_TOTAL`，不依赖 componentId，跨模板通用），`configured:false`。

**响应**
```jsonc
{ "configured": false,
  "customerNo":"C001", "templateSeriesId":"a91209e6-...",
  "columns": [
    { "id":"col-default", "kind":"PRODUCT_TOTAL", "sortOrder":0, "threshold":0.00,
      "quoteLabel":"产品总价", "costingLabel":"产品总价", "removable": false },
    { "id":"col-2", "kind":"TAB_PAIR", "sortOrder":1, "threshold":2.00,
      "quoteComponentId":"...", "quoteMetric":"材料小计", "quoteLabel":"投料·材料小计",
      "costingComponentId":"...", "costingMetric":"__TAB_TOTAL__", "costingLabel":"材料成本·页签合计",
      "removable": true }
  ] }
```

- `ColumnDef` schema **复用 task-0717**（§11.0#7），字段名保持一致以便前端复用连线配置抽屉。
- 默认「产品总价」列 **不可删除**（验收 #24③）。

### 1.10 `PUT /api/cpq/price-adjust/comparison-columns`

**请求**：`{ customerNo, templateSeriesId, columns:[...] }`

**服务端行为**
1. `UNIQUE(customer_no, template_series_id)` upsert。
2. 🔒 **唯一写入口**（§11.5.3(4)）—— 屏 4 审核抽屉**只读**，不提供任何修改端点。
3. 🔒 **保存后触发重算范围 = 该「客户 × 模板系列」下的 `待处理` 料号**（§11.5.4 收窄，非"该客户全部"），走异步管道；`已通过`/`已驳回` **不重算**。
4. 写审计日志。

**响应**：`200` + `{ "budgetRecomputeTriggered": true, "affectedReviewCount": 7 }`

### 1.11 `POST /api/cpq/price-adjust/versions/generate`

「立即生成一次」（屏 1 按钮）。

**请求**：`{ "customerNo":"C001", "confirmSupersede": false }`

**服务端行为**
1. 🔒 **与定时任务走完全相同的代码路径**（验收 #5 / #67③）—— 二次确认只在前端，服务端**不得**分出第二条生成逻辑。
2. **前置校验**：参与调价元素为空（或全部既无本期价也无历史价）→ `400 STRATEGY_NO_ELEMENTS`，**不生成任何版本**（E14-10）。
3. 已有 `PENDING` 版本且 `confirmSupersede != true` → `409 PENDING_VERSION_EXISTS` + 影响面：
   ```jsonc
   { "code":"PENDING_VERSION_EXISTS", "pendingVersionNo":"V26080501",
     "pendingReviewCount": 12, "approvedReviewCount": 8 }
   ```
   前端据此弹确认：「V26080501 将被作废，其中 12 个待处理料号退出待办池（已通过的 8 个不回滚）」。
4. 生成：版本号 `V + YYMMDD + 两位当日流水`（按**客户+日期**独立计数）；`trigger_type='MANUAL'`、`scheduled_slot=NULL`。
5. 上一版整体转 `SUPERSEDED`；🔒 **其名下未完成的 `job_item` 一律置「已失效」不能再重试**（§11.6.3.2）。
6. 🔒 **只写版本 + 明细即返回（毫秒级），预算投后台队列**（E14-3）。

**响应**：`201`
```jsonc
{ "versionId":"...", "versionNo":"V26080502", "baseDate":"2026-08-05",
  "itemCount": 7, "budgetJobId":"...", "budgetStatus":"QUEUED" }
```

### 1.12 `GET /api/cpq/price-adjust/versions`

版本轨迹（屏 1 底部）。**Query**：`customerNo` / `page` / `size`

**响应**
```jsonc
{ "content":[
   { "versionId":"...", "versionNo":"V26080501", "baseDate":"2026-08-05",
     "status":"PENDING",                       // 只有两态
     "triggerType":"SCHEDULED", "createdAt":"...", "createdBy":"system",
     "progress": { "total":12, "approved":8, "rejected":2, "pending":2, "budgeting":0 },
     // ↑ 版本进度摘要「不落库、实时派生」（§11.3.3(2)）
     "itemCount": 7 }
  ], ... }
```

### 1.13 `GET /api/cpq/price-adjust/versions/{versionId}/items`

版本明细（元素级）。用于屏 1 展开查看。

```jsonc
{ "content":[
   { "elementCode":"Ag", "elementName":"银",
     "currentPrice": 5820.00, "previousPrice": 5450.00, "changeRate": 0.0679,
     "currency":"CNY", "priceUnit":"kg",
     "noPrice": false,                  // 「无价」标记：不可审、不占审核动作
     "inheritedFromPrevious": false }   // E2/§11.3.2.1：本期无价但沿用了上一版价
  ], ... }
```

---

## 2. 审核（屏 3 / 屏 4 / 屏 5）

### 2.1 `GET /api/cpq/price-adjust/reviews`

**跨客户的料号待办池**（屏 3 主列表）。

**Query**：`page` / `size` / `customerNo`（可选筛选）/ `status`（默认 `PENDING`）/ `breachedOnly`（只看标红）/ `keyword`（料号/名称）/ `sort`

🔒 **纯读库，不实时算**（§11.5.3(5)）—— 标色来自 `material_price_review` 的冗余汇总列。

**响应**
```jsonc
{ "content": [
  { "reviewId":"...", "customerNo":"C001", "customerName":"罗克韦尔",
    "materialNo":"10120240", "materialName":"镀银铜带",
    "currentVersionNo":"V26070501",       // 「当前版本 → 目标版本」核心列
    "targetVersionNo":"V26080501",
    "budgetStatus":"READY",               // QUEUED | COMPUTING | READY | FAILED  ← E14-3 新增
    "reviewStatus":"PENDING",             // PENDING | APPROVED | REJECTED | VOIDED
    "basisQuotationNo":"QT-20260801-0012",   // 判断依据单（版本生成时刻锁定，E11-6）
    "basisQuotationDate":"2026-07-28",
    // ↓ 固定列：只出产品总价口径（跨客户列名不统一，§11.5.3(1)）
    "quoteCostCurrent": 218.05, "quoteCostAdjusted": 222.10,
    "costingCost": 218.05,
    "diffCurrent": 0.00, "diffAdjusted": 4.05,
    // ↓ 汇总标记（红橙分开计数，禁止合并成「M/N 列跌破」）
    "columnCount": 3, "breachedCount": 1, "amberCount": 1, "missingCount": 0, "staleCount": 0,
    "rowRed": true                        // 🔒 = breachedCount > 0，不是「产品总价差异<0」
  } ], ... }
```

🚨 **`rowRed` 由服务端给出，前端不得自行按产品总价重算**（硬约束 19 / 验收 #25）。
🚨 `breachedCount` 计入口径：`RED` + `MISSING` **都计**；`AMBER` 计 `amberCount`；`STALE` 计 `staleCount` **不计红**（§11.5.1.1(2)）。

### 2.2 `GET /api/cpq/price-adjust/reviews/{reviewId}`

料号审核抽屉（屏 4）**三段结构**（§11.5.2），一次返回全部三段，不分多次请求。

```jsonc
{
  "reviewId":"...", "customerNo":"C001", "materialNo":"10120240", "materialName":"镀银铜带",
  "currentVersionNo":"V26070501", "targetVersionNo":"V26080501",
  "budgetStatus":"READY", "reviewStatus":"PENDING",

  // 一、为什么变
  "elementChanges": [
    { "elementCode":"Ag", "elementName":"银", "matchedRule":"客户级默认策略 · LATEST × 1.02",
      "previousPrice":5450.00, "currentPrice":5820.00, "changeRate":0.0679,
      "usageQty": 0.0032, "unitPriceImpact": 1.184,
      "noPrice": false, "inheritedFromPrevious": false } ],
  "elementImpactTotal": 4.05,      // 须与「调整后报价 − 现报价」对得上（财务自检位）

  // 二、比对结果（按该料号所属模板系列的配置逐列展开）
  "templateSeriesId":"a91209e6-...", "templateSeriesName":"罗克韦尔模板1",
  "comparisonColumns": [
    { "columnId":"col-default", "label":"产品总价", "threshold":0.00, "sortOrder":0,
      "quoteCurrent":218.05, "quoteAdjusted":222.10,
      "costingCurrent":218.05, "costingAdjusted":218.05,
      "diffCurrent":0.00, "diffAdjusted":4.05, "status":"NORMAL" },
    { "columnId":"col-2", "label":"投料·材料小计 ↔ 材料成本·页签合计", "threshold":2.00, "sortOrder":1,
      "quoteAdjusted":88.10, "costingAdjusted":90.00, "diffAdjusted":-1.90, "status":"RED" },
    { "columnId":"col-3", "label":"加工费对照", "status":"MISSING",
      "diffAdjusted": null, "missingSide":"COSTING" }   // 前端显示「—（缺核价数据）」
  ],

  // 三、逐单明细（下钻）
  "quotations": [
    { "quotationId":"...", "quotationNo":"QT-20260801-0012", "createdAt":"2026-07-28",
      "status":"DRAFT", "isBasis": true,              // 判断依据（唯一一张）
      "quoteSubtotalCurrent":218.05, "quoteSubtotalAdjusted":222.10,
      "comparisonViewUrl":"/quotations/{id}/comparison" },   // 直达 task-0717 比对视图
    { "...": "其余单 isBasis=false，仅作参考、不参与判断" }
  ]
}
```

🔒 **抽屉内所有比对数据与屏 3 同读 `material_price_review_column` 落库值**，两处逐位一致（验收 #27）。**抽屉不提供任何修改比对列的控件**（§11.5.3(4)）。

### 2.3 `POST /api/cpq/price-adjust/reviews/impact`

通过前的影响面确认（屏 5 Modal 数据源）。**只读预览，不产生任何副作用。**

**请求**：`{ "reviewIds": ["...","..."] }`

**响应**
```jsonc
{ "materialCount": 2,
  "versionPaths": [ { "materialNo":"10110002", "from":"V26070501", "to":"V26080501" } ],
  "quotationCount": 8,
  "byStatus": { "DRAFT":5, "SUBMITTED":2, "APPROVED":1 },   // 🔒 只统计 5 个可更新状态（E14-2）
  "breachedMaterials": [ { "materialNo":"10120240", "breachedCount":1 } ],  // 跌破预警线提示
  "excludedQuotationCount": 3,        // SENT/ACCEPTED/EXPIRED/CANCELLED 的单，明示不会被更新
  "excludedByStatus": { "SENT":2, "ACCEPTED":1 } }
```

### 2.4 `POST /api/cpq/price-adjust/reviews/approve`

**通过并升版**（屏 5 确认后）。

**请求**：`{ "reviewIds":["..."], "comment":"" }`

**服务端行为**
1. 校验每条 review：`reviewStatus=PENDING` ∧ `budgetStatus=READY` ∧ 所属版本 `status=PENDING`；否则整批拒绝 `409`（`REVIEW_BUDGET_NOT_READY` / `REVIEW_STATUS_CHANGED`）并列出不合格项。
2. **同步**：料号的客户级版本指针推进（`material_price_version_ref`）+ review 置 `APPROVED`。
3. **异步**：创建 `material_price_update_job` + 逐「单 × 料号」的 `job_item`，投递执行（通道 B 八步 + L3 守卫）。
4. 🔒 **指针推进是同步的、改单是异步的**（§11.6.3.2 指针纪律）—— 个别单失败**不回退**料号指针，靠屏 7 标「尚未更新」+ 更新任务页重试兜住。

**响应**：`202 Accepted`
```jsonc
{ "jobId":"...", "materialCount":2, "quotationCount":8, "itemCount":11 }
```

### 2.5 `POST /api/cpq/price-adjust/reviews/reject`

**请求**：`{ "reviewIds":["..."], "reason":"行情尚未确认" }` —— 🔒 `reason` **必填**（裁决 8），空则 `400`。

**服务端行为**：review 置 `REJECTED` + 记录原因/审核人/时间；**指针不动，两侧均保持原样**；**不产生任何 job**。
被驳回的料号后续走 §11.5.5 两条补丁（下期与**指针指向版本**比必有差异 → 一定重新进池；且不适用 D5 自动推进）。

### 2.6 `POST /api/cpq/price-adjust/reviews/{reviewId}/recompute-budget`

预算重算（`budgetStatus=FAILED` 时的重试入口，E14-3③）。投递异步管道，`202` 返回。

---

## 3. 更新任务（屏 6 + 常驻页 §11.6.3.1）

### 3.1 `GET /api/cpq/price-adjust/jobs`

批次列表（「更新任务」菜单页）。**Query**：`page` / `size` / `status` / `customerNo`

```jsonc
{ "content":[
  { "jobId":"...", "customerNo":"C001", "versionNo":"V26080501",
    "triggeredBy":"李财务", "triggeredAt":"...",
    "status":"PARTIAL",            // RUNNING | SUCCESS | PARTIAL | FAILED | STALE
    "total":11, "success":9, "failed":1, "conflict":1, "stale":0 } ], ... }
```

### 3.2 `GET /api/cpq/price-adjust/jobs/{jobId}`

批次进度（屏 6 **轮询**用，建议 2s 一次）。返回同上单条 + `finishedAt` + `notified`。

### 3.3 `GET /api/cpq/price-adjust/jobs/{jobId}/items`

明细。**Query**：`page` / `size` / `status`

```jsonc
{ "content":[
  { "itemId":"...", "quotationId":"...", "quotationNo":"QT-20260801-0012",
    "materialNo":"10110002", "lineItemId":"...",
    "status":"FAILED",            // WAITING | RUNNING | SUCCESS | FAILED | CONFLICT | STALE
    "errorCode":"SUBTOTAL_MISMATCH",
    "errorMessage":"升版口径守卫：后端旧价重算 218.07 vs li.subtotal 218.05，差异 0.02 > 阈值 0.01",
    "diffValue": 0.02,            // L3 守卫专用
    "retryCount": 1, "updatedAt":"..." } ], ... }
```

🔒 **三种非成功态语义**（§11.6.3）：
- `FAILED` 数据问题（含 `SUBTOTAL_MISMATCH`）→ 需人工处理后重试
- `CONFLICT` 重算期间该行被改动（`row_version` 不匹配、受影响行数 0）→ 直接重试即可
- `STALE` 所属版本已被新版取代（§11.6.3.2）→ **终态，重试按钮禁用**，hover 说明「所属版本 V1 已被 V2 取代，请在新版待办池重新处理」

### 3.4 `POST /api/cpq/price-adjust/jobs/{jobId}/retry`

批量重试该批次全部 `FAILED` + `CONFLICT` 项（**不含 `STALE`**）。`202` 返回。

### 3.5 `POST /api/cpq/price-adjust/job-items/{itemId}/retry`

单条重试。`STALE` 项调用返回 `409`。

---

## 4. 报价单侧（屏 7）· 销售只读可见

### 4.1 `GET /api/cpq/quotations/{quotationId}/price-revisions`

版本轨迹表 + 料号级价格版本表（一次返回，屏 7 两张表同源）。

```jsonc
{
  // 整单版本轨迹（一期一行，裁决 30 合并）
  "revisions": [
    { "revisionId":"...", "revisionNo":"R26072001", "basedVersionNo": null,
      "isInitial": true, "sealed": true,               // 初版：based_version_id 留空（D6）
      "firstEffectiveAt":"2026-07-20T10:00:00+08:00", "lastUpdatedAt":"2026-08-05T19:20:00+08:00",
      "upgradedMaterialNos": [], "quoteTotalAmount": 132000.00 },
    { "revisionId":"...", "revisionNo":"R26080501", "basedVersionNo":"V26080501",
      "isInitial": false, "sealed": true,
      "firstEffectiveAt":"2026-08-05T19:20:00+08:00", "lastUpdatedAt":"2026-08-05T20:10:00+08:00",
      "upgradedMaterialNos": ["10110002","10110037"],   // 同 V 版内多次升版 → 累积
      "quoteTotalAmount": 136340.00 }
  ],

  // 料号级价格版本表（单内每个料号各处在哪一版）——§11.1.1「混合价的可查证据」
  "materialVersions": [
    { "materialNo":"10110002", "materialName":"触头组件",
      "currentVersionNo":"V26080501", "state":"UPGRADED" },
    { "materialNo":"10110037", "currentVersionNo":"V26070501", "state":"REJECTED" },
    { "materialNo":"10110088", "currentVersionNo":"V26080501", "state":"NOT_UPDATED",
      "pendingJobItemId":"..." },      // 🔒 指针已推进但该单未更新成功 → 前端必须标「尚未更新」
    { "materialNo":"10110099", "currentVersionNo": null, "state":"NOT_PARTICIPATING" }
  ]
}
```

🔒 **`state=NOT_UPDATED` 的判定必须读 `material_price_update_job_item`**（§11.6.3.1），**不能只读指针** —— 否则这张"可查证据"表会说谎。
🔒 判定锚点 = **该料号指针当前指向版本**的 job_item（不是任意历史批次，§11.6.3.2）。

**涨跌对比按料号级对齐**（§11.7.0 边界 2）：两版都有 → 算涨跌；只在新版有 → `本期新增`；只在旧版有 → `已移除`。**不逐行对齐**（结构可能不同）。

### 4.2 `GET /api/cpq/quotations/{quotationId}/price-revisions/{revisionId}/preview`

切版**只读预览**（裁决 14：不落库）。

**响应**：与报价单详情页渲染同构的整单数据，**双侧都从快照渲染**：
```jsonc
{ "revisionNo":"R26080501", "readonly": true,
  "lineItems": [
    { "lineItemId":"...", "materialNo":"10110002",
      "quoteCardValues": {...},      // 来自快照
      "costingCardValues": {...},    // 🔒 同样来自快照，不得读当前值（验收 #55）
      "snapshotRows": {...} } ],
  "quoteTotalAmount": 136340.00 }
```

🚨 **禁止报价侧读快照、核价侧读当前值** —— 那是 F3 要避免的混合语义，且**报价侧看着完全正常，极难发现**（验收 #55 专防）。
🔒 未定型初版（`sealed=false`）的 `snapshot` 为 NULL → **服务端直接返回该单当前值**（§11.10.6 性能纪律），前端无感。

### 4.3 报价单渲染接口的**扩展字段**（不新增端点）

既有报价单获取/编辑接口（`GET /api/cpq/quotations/{id}`、`PUT .../quote-card-edit`、`saveDraft` 响应）返回的 `quoteCardValues` 行上，**后端追加两个标记**（§11.15.2.8）：

```jsonc
{ "__priceLocked": true, "__priceVersion": "V26080501" }
```

🔑 **判定逻辑全在后端**（元素∈清单 ∧ 料号∈范围 ∧ 策略启用 三条件，§11.15.2.6(1)），**前端纯读标记**，不新增任何拉取调价清单的接口。
🔒 **详情页（`ReadonlyProductCard`）同样消费这两个标记**（AP-50 / 验收 #42③）。

---

## 5. 组件元素列绑定（屏 8）

### 5.1 既有端点扩展（**不新增端点**）

`POST /api/cpq/components` / `PUT /api/cpq/components/{id}` 的请求/响应**增加三个组件级字段**（与 `partNoField` / `partNameField` / `sortField` / `rowKeyFields` **平级**）：

```jsonc
{ "elementCodeField": "元素",
  "elementPriceField": "元素单价",
  "elementCurrencyField": null }
```

🔒 **必须组件级，不得放进 fields 的 config JSON** —— 后者按 AP-44 属"加新 config 键"，触发 17 点联动排查；组件级前端渲染层不消费，**不触发 AP-44**（§11.15.3.1）。

**保存期校验**（§11.15.3.2）：该组件任一 `sqlTemplate` 检测到取价函数（`f_customer_element_price` 或 `f_material_element_price`）→ `elementCodeField` + `elementPriceField` **必填**，否则 `400 COMPONENT_ELEMENT_BINDING_REQUIRED`：

```jsonc
{ "code":"COMPONENT_ELEMENT_BINDING_REQUIRED",
  "message":"该组件视图接入了 f_material_element_price，必须指定元素列与元素单价列",
  "missingFields":["elementCodeField"] }
```

🔒 **拦下不让保存**（不是警告、不是静默保存，验收 #32②）。未接取价函数的组件三项留空**可正常保存**（验收 #32③）。

### 5.2 `GET /api/cpq/components/{id}/element-binding-suggest`

迁移期/新建期的**推导预填**（§11.15.3.4 五步算法），供屏 8 下拉给出推荐值。**推导失败返回空**，不报错。

```jsonc
{ "suggested": { "elementCodeField":"元素", "elementPriceField":"元素单价", "elementCurrencyField": null },
  "alias":"cep",                 // 动态捕获，不硬编码
  "confidence":"HIGH",           // HIGH | LOW（LOW 时前端提示需人工确认）
  "warnings": ["元素列样本值 301 不在元素主表 element_code 中，可能是材质编码"] }  // §11.15.3.3 可选强化
```

---

## 6. 系统参数

### 6.1 `GET /api/cpq/price-adjust/settings` · `PUT /api/cpq/price-adjust/settings`

```jsonc
{ "subtotalGuardThreshold": 0.01 }   // L3 升版口径守卫阈值（E14-11），金额，可配、即时生效
```

仅 `SYSTEM_ADMIN` 可写。

---

## 7. 契约自检清单（前后端联调前逐条对）

| # | 检查项 | 依据 |
|---|--------|------|
| 1 | 待办池的 `rowRed` 由**服务端**给出，前端未自行按产品总价重算 | 硬约束 19 / 验收 #25 |
| 2 | `breachedCount` 含 `MISSING`、不含 `STALE` | §11.5.1.1(2) / 验收 #49 |
| 3 | 元素矩阵一次 pivot 返回，F12 Network 无 N+1 | §11.2.4 / 验收 #3 |
| 4 | 两种空值 `NOT_IN_LIST` / `NO_PRICE` 前端渲染确实不同（`—` vs `无价`） | §11.2.4 |
| 5 | 版本状态只有 `PENDING` / `SUPERSEDED`，响应里**不出现**"生效/驳回/失效" | §11.3.3 / 验收 #62 |
| 6 | 屏 1 表头文案是「最新已生成版本」而非「当前生效」 | §11.3.3(3) |
| 7 | 影响面预览的 `byStatus` 只含 5 个可更新状态，`excludedByStatus` 显式列出被排除的 | E14-2 / 验收 #46 |
| 8 | 切版预览的 `costingCardValues` 来自快照（构造一张 R1 后改过数量的单验证） | 验收 #55 |
| 9 | `__priceLocked` / `__priceVersion` 在**编辑页与详情页**两处都被消费 | AP-50 / 验收 #42③ |
| 10 | 组件三字段未配全时保存**被拒**（400），不是警告 | 验收 #32② |
| 11 | 手动生成与定时生成进入**同一个服务方法**（可加日志断言） | 验收 #5 / #67③ |
| 12 | `job_item` 的 `STALE` 项重试按钮禁用且 hover 有原因 | §11.6.3.2 / 验收 #61 |
