# 接口文档 — 报价侧树状结构与页签类型属性

> 需求：`需求说明.md`｜技术设计：`docs/superpowers/specs/2026-07-21-报价单BOM树状渲染-design.md`
> 日期：2026-07-21

---

## 0. 契约总则

### 0.1 职责划分（重要）

| 能力 | 实现方 | 理由 |
|---|---|---|
| 类型判定（§4.3 规则二） | **后端** | 业务规则单一实现，避免前后端漂移；结构推导需遍历树 |
| 级联影响面计算 | **后端** | 删除是危险操作，前端算的结果不可信 |
| `+` 按钮是否置灰 | **前端**（用后端预标注的 `__nodeType`） | 即时 UI 反馈，不能每次都发请求 |
| 加叶子候选料号列表 | **前端**（本地过滤 `componentData`） | 数据已在前端，无需新端点 |

> **前端不得自行实现类型判定与级联判定逻辑**。前端只做 UI 反馈，所有写操作以后端校验为准。

### 0.2 新增系统列

树页签的每个 baseRow 在原有系统列基础上**新增一列**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `__nodeType` | string | 节点类型：`材质` / `零件` / `外购件` / `主件` / `null`(未判定) |

前端据此决定 `+` 是否置灰（材质 / 外购件 → 置灰）。

### 0.3 统一响应包装

沿用项目现有 `ApiResponse<T>`：

```jsonc
{ "success": true, "data": { ... }, "message": null }
```

### 0.4 鉴权

**本需求不新增任何鉴权代码**，全部沿用现有端点的类级鉴权：

| 端点组 | 沿用角色 |
|---|---|
| 报价单树操作 | `SALES_REP` `SALES_MANAGER` `PRICING_MANAGER` `SYSTEM_ADMIN`（`QuotationResource` 类级） |
| 组件管理（页签类型属性） | `PRICING_MANAGER` `SYSTEM_ADMIN` |
| 递归 SQL 配置 | `SALES_MANAGER` `SYSTEM_ADMIN` |

---

## 1. 页签类型属性

### 1.1 扩展现有组件保存接口

**不新增端点**。在组件创建 / 更新的请求体中增加字段：

```jsonc
{
  "code": "COMP-0071",
  "name": "投料",
  // ... 现有字段
  "tabType": "材质元素",       // 新增，可空
  "partNoField": "料号",       // 新增：哪个字段是料号列（非树页签必填）
  "partNameField": "料号名称"  // 新增：哪个字段是料号名称列（可空）
}
```

> **2026-07-21 新增（料号列标识）**：`partNoField` / `partNameField` 指向该组件字段的 name。
> 类型判定与候选料号采集**依据 `partNoField` 显式取值，不靠字段名启发式猜测**。
> 树页签（`tabType=BOM`）料号取系统列 `__hfPartNo`，可不配；非树页签必须配 `partNoField`，
> 否则该页签不参与类型判定匹配（后端保存期校验：`tabType ∈ {材质元素,零件,外购件,主件}` 但缺 `partNoField` → 400）。

**值域**（5 类，后端强校验，非法值返回 400）：

```
BOM | 材质元素 | 零件 | 外购件 | 主件
```

| 值 | 语义 |
|---|---|
| `BOM` | 树状页签（结构角色） |
| `材质元素` | 该页签料号为材质 |
| `零件` | 该页签料号为零件 |
| `外购件` | 该页签料号为外购件 |
| `主件` | 成品 = 树根 |

> **2026-07-21 裁决（Q1）**：`tabType='BOM'` 是「该页签是否按树渲染」的**唯一判据**。
> 保存时后端**自动同步** `bomRecursiveExpand`（`BOM` → true；改为其他值 → false）。
> 前端**不需要**单独暴露 `bomRecursiveExpand` 开关给报价侧用户。
>
> ⚠️ 若目标组件**已被 COSTING 模板引用**，改为 `tabType='BOM'` 须**阻断并返回 400** ——
> 该开关是组件级全局开关，会把核价模板一并改成树渲染（违反 AC-10）。

**响应**：组件对象中回带 `tabType`。

---

## 2. 报价侧递归 SQL 配置

### 2.1 列表（扩展现有端点）

```
GET /api/cpq/costing-bom-tree-configs?usage=QUOTE
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `usage` | string | 否 | `QUOTE` / `COSTING`；**不传 = 返回全部**（向后兼容） |

**响应**：

```jsonc
{
  "success": true,
  "data": [
    { "id": "...", "name": "BOMV2", "usage": "COSTING", "isActive": true, "sqlTemplate": "WITH RECURSIVE ..." }
  ]
}
```

### 2.2 创建 / 更新（扩展请求体）

```
POST /api/cpq/costing-bom-tree-configs
PUT  /api/cpq/costing-bom-tree-configs/{id}
```

请求体增加 `usage` 字段（必填，值域 `QUOTE` / `COSTING`）。

### 2.3 设为生效

```
PUT /api/cpq/costing-bom-tree-configs/{id}/activate
```

**语义变更**：原为「全局唯一 active」，现改为「**每个 usage 至多一条 active**」。
激活某配置时，只下线**同 usage** 的其他配置，不影响另一侧。

> ⚠️ 后端务必保证：激活 `QUOTE` 配置**不得**下线核价侧现役的 `COSTING` active 配置（AC-10 零回归）。

---

## 3. 树上加叶子

```
POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/add-leaf
```

**请求体**：

```jsonc
{
  "componentId": "uuid",        // 触发操作的树页签组件 id
  "hostNodeId": "3120018220/2120011658",   // 宿主节点的 __nodeId
  "partNo": "S-1630010773"      // 用户选中的料号
}
```

**后端处理**：

1. 校验 `hostNodeId` 存在于该 line item 的树中
2. 判定 `hostNodeId` 的节点类型 → 若为 `材质` / `外购件` → **400**（终端物料不可加子）
3. 按 §4.3 规则二判定 `partNo` 的类型：
   - 命中「主件」页签 → **400**（成品不能作为他人叶子挂入）
   - 零命中 → **400**（该料号不是有效的报价产品）
   - 命中 ≥2 个**不同类型**页签 → **409**，返回冲突页签列表供用户裁决
     （命中多个**同类型**页签**不算冲突**，类型无歧义，正常判定 —— 2026-07-21 裁决 Q3）
4. 生成系统列并插入 `snapshot_rows`（插入位置：宿主节点行组的最后一行之后）

**成功响应**：

```jsonc
{
  "success": true,
  "data": {
    "nodeId": "3120018220/2120011658/__manual_a1b2c3d4",
    "nodeType": "材质",
    "quoteCardValues": "..."     // 整单卡片值，前端直接回灌
  }
}
```

**错误响应**：

| HTTP | 场景 | message 示例 |
|---|---|---|
| 400 | 宿主为材质/外购件 | `材质节点不可再添加下级` |
| 400 | 料号命中主件页签 | `S-xxx 是成品料号，不能作为子件挂入` |
| 400 | 零命中 | `S-xxx 不在任何页签中，不是有效的报价产品` |
| 409 | 多页签冲突 | `S-xxx 同时出现在「材质元素」「零件」页签，请先修正基础数据` + `data.conflictTabs` |

---

## 4. 删除影响面预览

> **所有删除操作（剪枝 / 行删除）前端都必须先调此接口**，用返回结果渲染确认弹窗。
> 不允许静默级联。

```
POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete-preview
```

**请求体**：

```jsonc
{
  "componentId": "uuid",
  "mode": "PRUNE",              // PRUNE=剪枝(整枝) | ROW=行删除
  "nodeId": "3120018220/2120011658",
  "rowKey": "..."               // mode=ROW 时必填
}
```

**响应**：

```jsonc
{
  "success": true,
  "data": {
    "treeNodes": [                          // 将从树上移除的节点
      { "nodeId": "...", "partNo": "2120011658", "lvl": 1 },
      { "nodeId": "...", "partNo": "3110520789", "lvl": 2 }
    ],
    "cascadeTabs": [                        // 将被级联删除的其余页签数据
      {
        "componentId": "uuid",
        "tabName": "材质元素",
        "rows": [ { "rowKey": "...", "partNo": "3110520789", "summary": "AgNi11# / 含量 0.138" } ]
      }
    ],
    "retainedParts": [                      // 因仍有剩余 occurrence 而【不删】的料号
      { "partNo": "3110520789", "remainingOccurrences": 1, "reason": "该料号在树上还有 1 处引用" }
    ]
  }
}
```

> `retainedParts` 必须返回并在弹窗中展示 —— 让用户明确看到"哪些没被删、为什么"，
> 这是 DAG 重复子件场景下避免误解的关键。

---

## 5. 执行删除

```
POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete
```

**请求体**：与 §4 预览接口**完全一致**，外加一个确认令牌：

```jsonc
{
  "componentId": "uuid",
  "mode": "PRUNE",
  "nodeId": "...",
  "rowKey": null,
  "previewToken": "..."         // 预览接口返回的令牌，防止预览与执行之间数据漂移
}
```

> **`previewToken` 机制**（2026-07-21 裁决 Q6）：token = 「该 line item 当前树结构 + 墓碑状态」的**内容 hash**。
> 预览接口生成并返回，执行接口重算比对，不一致 → 409 要求重新预览。

**后端处理**：

1. 校验 `previewToken` 有效（若树在预览后发生变化 → **409**，要求前端重新预览）
2. 重新计算影响面（**不信任前端传来的影响面**）
3. 写墓碑：
   - 树节点 → `quotation_line_item.deleted_tree_nodes`
   - 级联行 → 各组件 `deleted_row_keys`
4. 重算小计与卡片值

**成功响应**：

```jsonc
{
  "success": true,
  "data": {
    "deletedNodeIds": ["..."],
    "cascadeDeletedRowKeys": { "<componentId>": ["rowKey1", "rowKey2"] },
    "quoteCardValues": "..."     // 整单卡片值，前端直接回灌
  }
}
```

| HTTP | 场景 |
|---|---|
| 409 | `previewToken` 失效（树已变化），要求重新预览 |

---

## 6. 反向校验（页签数据维护侧）

已拥有子节点的料号，**禁止**被添加到「材质元素」/「外购件」类型页签。

该校验挂在**页签行新增 / 保存**的既有链路上（`saveDraft` / 组件行编辑），不新增端点。

**触发时返回**：

```
400  该料号在 BOM 树上已有下级，不能添加到「材质元素」页签
```

---

## 7. 不新增端点的部分（明确说明，避免误做）

| 能力 | 为什么不需要端点 |
|---|---|
| 加叶子的候选料号列表 | 数据已在前端 `componentData` 中，本地过滤即可。**不要**调用 `pricing-basic-data/lookup` |
| 节点类型查询 | 已随 `__nodeType` 系统列下发到前端 |
| `+` 是否置灰 | 前端据 `__nodeType` 本地判断 |

---

## 8. 联调检查清单

- [ ] `tabType` 五个值域全部能存能读，非法值返回 400
- [ ] `usage=QUOTE` 激活配置后，核价侧 `COSTING` active 配置**未被下线**
- [ ] 加叶子的四种错误（材质宿主 / 主件料号 / 零命中 / 多页签冲突）均返回正确 HTTP 码与可读文案
- [ ] 删除预览的 `retainedParts` 在 DAG 场景下非空且理由正确
- [ ] 预览后修改树再执行删除 → 返回 409 而非静默执行
- [ ] 所有写操作返回的 `quoteCardValues` 可被前端直接回灌，无需二次拉取
