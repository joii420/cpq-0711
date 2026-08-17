# 接口契约 — 元素主表管理（task-0709 / BL-0040）

> 前后端共同契约。字段名/类型/语义**以本文为准**。
> Base path：`/api/cpq/elements`（新增 `ElementResource`）
> 鉴权：读=`{SALES_REP,SALES_MANAGER,PRICING_MANAGER,SYSTEM_ADMIN}`；写（create/update/delete）=`SYSTEM_ADMIN`。
> 响应包裹：`api` 拦截器已 unwrap `data`，前端直接拿业务体。
> **路径主键用 `element_no`**（不可改业务主键）。

---

## 一、接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/elements?keyword=` | 元素列表（搜索 + 被引用数 + 排序）|
| POST | `/elements` | 新建元素 |
| PUT | `/elements/{elementNo}` | 编辑（符号引用锁在后端校验）|
| DELETE | `/elements/{elementNo}` | 停用（软删）|

---

## 二、GET /elements（列表）

### 请求
| Query | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `keyword` | string | 否 | 模糊匹配 元素编号 / 符号 / 中文名（任一命中）|

### 响应 `200` — `ElementDTO[]`
排序：**启用优先 → 修改时间倒序 → 创建时间倒序**。返回全状态（含 INACTIVE）。
```json
[
  {
    "id": "uuid",
    "elementNo": "10001",
    "elementCode": "Ag",
    "elementName": "银",
    "status": "ACTIVE",
    "referencedCount": 128,
    "codeLocked": true,
    "createdAt": "2026-07-09T10:00:00+08:00",
    "updatedAt": "2026-07-09T10:00:00+08:00"
  }
]
```

### 字段说明（ElementDTO）
| 字段 | 类型 | 语义 |
|------|------|------|
| `id` | UUID | 物理主键（内部）|
| `elementNo` | string | **元素编号（不可改业务主键）** |
| `elementCode` | string | 符号（可编辑；被引用即锁）|
| `elementName` | string | 中文名（随时可编辑）|
| `status` | `'ACTIVE'\|'INACTIVE'` | 启用/停用 |
| `referencedCount` | long | 被引用材质数 = COUNT(material_recipe_element WHERE element_no=本) |
| `codeLocked` | boolean | `referencedCount>0` → true。前端据此禁用「符号」输入框 |
| `createdAt`/`updatedAt` | ISO8601 | 时间 |

---

## 三、POST /elements（新建）

### 请求 body `ElementUpsertRequest`
```json
{ "elementNo": "10100", "elementCode": "Mo", "elementName": "钼" }
```
| 字段 | 必填 | 校验 |
|------|------|------|
| `elementNo` | ✅ | 非空；**唯一**（撞已有返 409）|
| `elementCode` | ✅ | 非空；**唯一**（撞已有返 409）|
| `elementName` | ✅ | 非空 |

### 响应 `200` — `ElementDTO`（status 默认 ACTIVE，referencedCount=0，codeLocked=false）

### 错误
| HTTP | 场景 |
|------|------|
| 409 | `elementNo` 或 `elementCode` 已存在（body `{"message":"元素编号已存在: 10100"}`）|
| 400 | 必填缺失 |
| 401/403 | 非 SYSTEM_ADMIN |

---

## 四、PUT /elements/{elementNo}（编辑）

### 请求 body `ElementUpsertRequest`
- `elementNo`（路径）定位；**请求体里的 elementNo 不可改**（后端忽略/拒绝）。
- `elementCode`（符号）：
  - 若该元素 **`referencedCount>0`（被引用）且新符号 ≠ 现符号** → **409「符号已被 N 个材质引用，不可修改」**
  - 未引用时可改，但须**唯一**（撞他人返 409）
- `elementName`、`status` 随时可改。

### 响应 `200` — `ElementDTO`（回带最新值）

### 错误
| HTTP | 场景 | body |
|------|------|------|
| 409 | 被引用改符号 | `{"message":"符号已被 128 个材质引用，不可修改"}` |
| 409 | 新符号与他人重复 | `{"message":"符号已存在: Ag"}` |
| 404 | elementNo 不存在 | |

---

## 五、DELETE /elements/{elementNo}（停用）

软删：`status → INACTIVE`，`204 No Content`。幂等（已停用再调仍 204）。
- **不物理删**；被引用的元素也可停用（历史材质靠 element_no join 照常显示）。
- 停用后该元素不应出现在"新材质/新导入可选元素"里（本期无该选择器，属约定）。

---

## 六、内部约定（供联调 / 影响面参考）

- **元素编号(element_no) 是业务主键**：新建时定死、之后不可改；符号(element_code)可改但被引用即锁。
- `material_recipe_element` 存 `element_no` 作权威链 + 保留 `element_code`/`element_name` 快照（符号锁保证快照恒一致）。
- **导入**（task-0708 材质库导入）改为按 `element_no` upsert、不覆盖已有符号/中文（详见 backtask B5）。
- **不改**选配/定价/`element_bom`/`costing_element_price`——它们继续按符号(element_code)读，因符号锁死恒一致。
- 本期**无元素被删/被合并**能力（只停用），故无需处理 material_recipe_element 悬空。
