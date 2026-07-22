# 报价数据版本升级 · 接口契约（api.md）

> 前后端共同契约。基地址 `/api/cpq`。信封统一 `ApiResponse<T>`：`{ code, message, data }`，成功 `code=0`。
> 鉴权沿用现有类级注解，本任务不新增鉴权端点。**所有列表/回填/预览一律无 N+1**。

---

## 0. 术语

| 术语 | 含义 |
|---|---|
| pending 行 | `pending_quotation_id` 非 NULL 的 V6 行，某报价单私有草稿，`is_current=false` |
| 有效行集 | 报价单某（页签→V6 组）此刻的实际行 = pending ⊕ 改值 ⊕ 手工叶子 ⊖ 墓碑 ⊖ spine 空行 |
| `__v6_id` | 物化期视图注入的行锚点（= V6 主键 uuid），写进 `snapshot_rows.driverRow`，回填按它定位 |
| previewToken | 回填影响清单的内容 hash，防 dry-run 与提交间数据漂移（TOCTOU） |

---

## 1. 核价通过 —— 改为两段式（预览 → 带 token 提交）

### 1.1 回填影响预览（新增）

财务点「核价通过」时**先**调此端点，拿到影响清单 + `previewToken`，前端弹预览抽屉。**只读、无副作用、幂等。**

```
GET /quotations/{id}/costing-approve/preview
```

**响应 `data`：**
```jsonc
{
  "quotationId": "uuid",
  "previewToken": "sha256-hex",          // 影响清单内容 hash
  "summary": {
    "versionedGroups": 12,                // 将升版的组数
    "addedRows": 3,                       // 新增行(手工叶子)
    "deletedRows": 2,                     // 删除行(墓碑/剪枝)
    "changedRows": 18                     // 改值行
  },
  "groups": [
    {
      "table": "unit_price",             // V6 目标表
      "tabName": "电镀费",                // 报价单页签(展示用)
      "groupKey": { "code": "S-31200...", "price_type": "PLATING" },  // 轴摘要
      "versionFrom": "2003",             // 旧版本号(无则 null=首版)
      "versionTo": "2004",
      "isGlobalShared": false,           // plating_scheme 等全局共享表标 true(前端重点标注,见 R-4)
      "rows": [
        { "op": "CHANGE", "__v6_id": "uuid", "changes": { "费用": ["1.20", "1.35"] } },
        { "op": "ADD",    "__v6_id": null,   "values": { "料号": "X", "费用": "0.80" } },
        { "op": "DELETE", "__v6_id": "uuid", "values": { "料号": "Y" } }
      ]
    }
  ]
}
```

- `op`：`CHANGE` / `ADD` / `DELETE`（`ADD` 无 `__v6_id`；`changes` 为 `{列: [旧, 新]}`）。
- 空影响（无任何增删改）：`summary` 全 0、`groups: []`，前端可直接放行确认（仍走 1.2 提交以完成 flip/闸门）。

### 1.2 核价通过并回填（改造现有端点）

```
POST /quotations/{id}/costing-approve
Body: { "comment": "string?", "previewToken": "sha256-hex" }
```

**行为**（同一事务）：
1. 校验 `status=SUBMITTED` + `isFinanceOrAdmin`（沿用现状）。
2. 重算影响清单 hash 与 `previewToken` 比对，不一致 → **409**（`code` 非 0，`message=报价数据在预览后发生变化，请重新预览`）。
3. 回填 7 张表（`VersionedV6Writer` 升版）→ upsert 暂存主档 → 占号 pending→approved → `quotation.status=APPROVED` + `costing_order.status=APPROVED` + 写审批流水。
4. 清理本单残留 pending 行。

**响应 `data`：** `QuotationDTO`（含 `lineItems`），另加：
```jsonc
{ "backfill": { "versionedGroups": 12, "addedRows": 3, "deletedRows": 2, "changedRows": 18 } }
```

**错误码：**
| HTTP/code | 场景 |
|---|---|
| 400 | 非 SUBMITTED / previewToken 缺失 |
| 403 | 非财务/管理员 |
| 409 | previewToken 与当前数据不一致（预览后漂移） |
| 500 | 回填失败（整事务回滚，报价单不变 SUBMITTED，pending 保留） |

> **兼容性**：`previewToken` 必填。老调用方（不带 token）直接 400，强制走预览。前端 `costingOrderService.approve` 签名相应加 `previewToken`。

---

## 2. 从已有产品添加 —— 闸门（改造现有端点，契约不变）

```
GET /quotations/customer-part-candidates?customerId={uuid}&importRecordId={uuid?}
GET /existing-products?quotationId={uuid}&...        // ExistingProductService.list
```

- **响应结构不变**；后端 where 增 `mcm.pending_quotation_id IS NULL`。
- 效果：未审核报价单的料号、选配发号未审核料号一律不出现在候选/已有产品列表。
- 前端无需改动。

---

## 3. 撤回 —— 语义调整（现有端点，契约不变）

```
POST /quotations/{id}/withdraw
```

- 已 `APPROVED` 撤回：报价单回到可编辑态，**但已回填的 V6 数据不回滚**（规则七）。
- 响应结构不变。前端如有「撤回」提示文案，需说明「基础数据不回退」（见 fronttask）。

---

## 4. 启动期视图校验结果（新增，诊断用）

```
GET /admin/quote-backfill/view-validation
```

**响应 `data`：**
```jsonc
{
  "checkedAt": "2026-07-21T10:00:00Z",
  "total": 20, "ok": 19, "failed": 1,
  "failures": [
    { "component": "组合工艺", "view": "zh_view",
      "reason": "改写后追不到 __v6_id 锚点 / 基表 capacity 不在白名单 / LIMIT 0 执行失败: <msg>" }
  ]
}
```

- 启动时若有 `failed>0`，应用**启动失败**（fail-fast，见 backtask）；本端点供运维复查已启动实例的最近一次校验快照。
- 鉴权：`SYSTEM_ADMIN`。

---

## 5. 数据结构约定（前后端共享）

### 5.1 `snapshot_rows.driverRow` 注入锚点

物化期视图输出每行注入系统列 `__v6_id`（string，V6 主键；不可回写行为 `null`）。前端渲染**不显示** `__v6_id`；回填后端按它定位行。

### 5.2 pending 列（DB，前端不可见）

`pending_quotation_id uuid NULL` —— 加于 `unit_price` / `material_bom` / `material_bom_item` / `element_bom` / `element_bom_item` / `capacity` / `plating_scheme` / `material_customer_map`。前端无感知。

---

## 6. 非目标（本任务不提供的接口）

- 无「部分勾选回填」端点（规则六：不可挑选）。
- 无手工叶子/剪枝的独立回写端点（随核价通过一并回填）。
- 候选料号**不新增端点**（数据已在前端 `componentData`，本地过滤）。
