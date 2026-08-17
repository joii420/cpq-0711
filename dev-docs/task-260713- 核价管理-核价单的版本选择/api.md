# 接口文档 — 核价管理·核价单版本选择（task-0713）

> 以 `需求说明.md` §「需求澄清纪要与设计定稿」为准。本文定义前后端契约。
> 标 🔶 的字段/端点其**落点/形态**依赖 cpq-architect 对 D1/D2/D3 的评审结论，评审后回填。
> 统一前缀：`/api/cpq`；鉴权：核价管理相关端点仅 `PRICING_MANAGER` / `SYSTEM_ADMIN`（`RoleFilter`）。

---

## 0. 通用约定

- 返回信封沿用现有 `ApiResponse<T>`：`{ code, message, data }`。
- **销售料号** = V6 语义下的 `material_no`（task-0708 已纠偏），本文 `partNo` 即销售料号。
- **约定版本列 `view_version`**：核价侧任一 $view 的输出行若含 `view_version` 字段，则该页签该行支持版本切换；无则不支持。前端只认 `view_version` 这一个键名（后端各表版本列 `bom_version/version_no/characteristic/...` 已在 $view 里 `AS view_version` 归一）。

---

## 1. 打开核价单详情（扩展现有）

`GET /api/cpq/costing-orders/{coid}`

打开核价管理里的核价单，渲染"报价详情"（报价侧冻结 + 核价侧可重算）。

**响应 `data`（在现有 `CostingOrderDetailDTO` 上扩展）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| costingOrderId / costingOrderNumber / quotationId / quotationNumber / status / customerName / currency / rejectReason / createdAt / updatedAt | — | 现有字段不变 |
| `frozenDto` | string(JSON) | **报价侧**数据源（冻结副本，**写一次永不改**）。前端 `ProductDetailViews` 的报价单视图仍读它 |
| `costingRender` | object | **核价侧**渲染数据（来自 `costing_order.costing_render` 新列缓存）：`{lineItemId: {costingCardValues(行内含 view_version 列), costingExcelValues}}`，**已应用本单 override** |
| `costingTotalAmount` | number | 核价侧单据总价（来自 `costing_order.costing_total_amount` 新列）= Σ 核价成本 subtotal，**不含 Step3 折扣**；供 3a 展示 |
| `versionOverrides` | array | 本单当前所有版本 override，见 §4 结构；供前端标记"已切版本" |
| `editable` | boolean | = `status==='PENDING' && role∈{PRICING_MANAGER,SYSTEM_ADMIN}`；前端据此决定是否显示版本切换控件 |

> **打开永远读缓存、绝不 on-open 重算**（守 BL-0010）。PENDING 打开读缓存、切版本时才 live 重算回写；APPROVED/历史单只读冻结缓存（历史单 line item 已被重提物理删除、无法 live 重算）。
> **空白 bug 修复点**：根因 = 提交冻结时 `costing_card_values` 还是 lazy NULL；修法 = `createForSubmission` 冻结前先 `ensureCardValues` 物化再组装 `costing_render`（见 backtask B5）。

---

## 2. 查询某料号在某页签的可选版本（下拉数据源）

`GET /api/cpq/costing-orders/{coid}/version-options`

**Query 参数**：

| 参数 | 必填 | 说明 |
|---|---|---|
| `lineItemId` | 是 | 当前产品卡片对应的报价行 ID |
| `componentId` | 是 | 当前页签的组件 ID（决定用哪条 $view + 哪个版本列） |
| `partNo` | 是 | 被点击的销售料号 |

**响应 `data`**：
```jsonc
{
  "componentId": "…",
  "partNo": "S-3110520789",
  "currentVersion": "2000",          // 当前生效/已override 的版本（高亮用）
  "options": ["2002", "2001", "2000"] // view_version 列表，倒序
}
```

**实现约定**：后端用该组件 $view 的**列出模式**（`:versionFilter` 展开为 `TRUE`）+ 料号集合限定为 `partNo`，取 `distinct view_version` 倒序。**独立轻查、不走带 30s 缓存的 `expandUncached`**（守 AP-37 串号）。无 `view_version` 列的组件返回空 `options`（前端不显示下拉）。

---

## 3. 切换版本（核心写操作）

`POST /api/cpq/costing-orders/{coid}/version-switch`

**Body**：
```jsonc
{
  "lineItemId": "…",     // 当前产品卡片
  "componentId": "…",    // 触发切换的页签组件
  "partNo": "S-3110520789",
  "viewVersion": "2001"  // 目标版本；传当前 is_current 版本 = 复位该 override
}
```

**行为**（单 `@Transactional` + `SELECT ... FOR UPDATE` 锁 costing_order + upsert 后 `flush` 再重算）：
1. 门禁校验：核价单 `status==='PENDING'` 且角色 ∈ {PRICING_MANAGER, SYSTEM_ADMIN}，否则 `403`。
2. upsert override（§4 表）。
3. **重查 scope（最小远程 SQL）**（`需求说明.md` §E）：
   - `componentId` 是**主树页签**（`bom_recursive_expand=true`）→ 该 line 各 driver 组件跑 $view（整卡重查）。
   - 否则（**非主树页签**）→ **仅该组件 $view 跑一次**（`partNo` 组限定）。
   - 两种情形远程查询次数**与料号数无关**（禁 N+1）。
4. **重装 scope = 整卡**（未重查页签用缓存值），保跨页签公式正确；成本 rollup 到根（后端引擎）→ 更新 `costing_total_amount`（Σ 成本 subtotal，不含 Step3 折扣）；写回 `costing_render` 缓存（仅受影响 line）。
5. 只影响**当前 lineItem 卡片**，不动其他卡片；**不回写** `quotation_line_item.costing_card_values`。

**响应 `data`**：
```jsonc
{
  "lineItemId": "…",
  "costingCardValues": { /* 该卡片重算后的核价卡片值（行内含 view_version） */ },
  "costingExcelColumns": [ /* 若受影响 */ ],
  "costingTotalAmount": 12345.67,     // 更新后的单据总价
  "affectedTabs": ["<componentId>", ...] // 实际触发重查/重算的页签，便于前端定向刷新
}
```

**性能约束**：单次 <3s、**禁 N+1**（守 AP-31/37，勿触发 batch-expand 风暴）。

---

## 4. 版本 override 持久化结构

新表 `costing_order_version_override`（详见 `backtask.md` B4）。API 层 `versionOverrides` 元素结构：

```jsonc
{
  "componentId": "…",
  "partNo": "S-3110520789",
  "viewVersion": "2001"
}
```

- 唯一键：`(costing_order_id, component_id, part_no)`。
- 生命周期 **B1**：override 归属单张核价单；报价单驳回→改→重提生成的新核价单不继承旧 override（新单从 is_current 起）。

---

## 5. 复位（可选，本期可用"切回 is_current 版本"代替）

`DELETE /api/cpq/costing-orders/{coid}/version-override?lineItemId=&componentId=&partNo=`

删除某 override → 该料号该页签回到 is_current，触发与 §3 相同的重查/重算/回写。若前端用"下拉里选当前 is_current 版本"实现复位，则本端点可不实现。

---

## 6. 错误码

| code | 场景 |
|---|---|
| 403 | 非 PENDING 或非财务/管理员切版本 |
| 404 | coid / lineItem / component 不存在 |
| 400 | `viewVersion` 不在该料号该页签可选版本内 / 该组件无 `view_version` 列不支持切换 |
| 500 | 重查/重算 SQL 失败（须带回可读错误原文，参照 BL-0030 不静默吞） |
