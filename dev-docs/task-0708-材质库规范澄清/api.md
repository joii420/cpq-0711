# 接口契约 — 材质库规范化与导入（task-0708）

> 前后端共同契约。字段名、类型、语义**以本文为准**；改动需双方同步。
> Base path：`/api/cpq/material-recipes`（沿用现有 `MaterialRecipeResource`）
> 鉴权：类级 `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`（读）；**写操作（import/create/update/delete）需 `SYSTEM_ADMIN`**。
> 响应包裹：项目 `api` 拦截器已 unwrap `data`，前端 service 直接拿业务体（下文 JSON 均指 unwrap 后的业务体）。

---

## 一、本次新增/改造接口一览

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| GET | `/material-recipes?keyword=&withCount=` | 列表（**新增 keyword、全状态、排序**） | 改造 |
| POST | `/material-recipes/import` | 上传 xlsx 导入材质库（Upsert + 报告） | **新增** |
| GET | `/material-recipes/import/template` | 下载干净导入模板（xlsx） | **新增** |
| GET | `/material-recipes/{id}` | 详情（含 elements） | 复用 |
| POST | `/material-recipes` | 新建材质 | 复用 |
| PUT | `/material-recipes/{id}` | 编辑材质 | 复用 |
| DELETE | `/material-recipes/{id}` | 停用（软删 status=INACTIVE） | 复用 |

> **本期隐藏但不删除**（料号功能后期再议）：`/{id}/parts`、`/{id}/bind-parts`、`/{id}/unbind-parts`、`/search-parts`、`/suggest-bindings`、`/confirm-bindings` 继续存在，前端不再调用。

---

## 二、GET /material-recipes（列表，改造）

### 请求
| Query | 类型 | 必填 | 说明 |
|-------|------|------|------|
| `keyword` | string | 否 | 模糊匹配：材质编号(code) / 材质名称(symbol) / 元素符号 / 元素中文名（任一命中即返回） |
| `withCount` | boolean | 否 | 默认 false；本期前端不用（保留兼容） |

### 响应 `200` — `MaterialRecipeDTO[]`
排序固定：**启用优先 → 修改时间倒序 → 创建时间倒序**（`ORDER BY (status='ACTIVE') DESC, updated_at DESC, created_at DESC`）。返回**全状态**（含 INACTIVE）。

```json
[
  {
    "id": "0f9c…-uuid",
    "code": "00001",
    "symbol": "Ag",
    "name": null,
    "specLabel": null,
    "recipeType": "locked",
    "status": "ACTIVE",
    "sortOrder": 1,
    "createdAt": "2026-07-09T10:00:00+08:00",
    "updatedAt": "2026-07-09T10:00:00+08:00",
    "elements": null,
    "boundPartsCount": null
  }
]
```

### 字段说明（MaterialRecipeDTO）
| 字段 | 类型 | 语义 | 本次变化 |
|------|------|------|----------|
| `id` | UUID | 主键 | — |
| `code` | string | **材质编号**（不可编辑主键 + 搜索键） | 语义锁定 |
| `symbol` | string | **材质名称**（UI 标签由「化学式」改为「材质名称」；DB 列仍叫 symbol） | 标签改名 |
| `name` | string\|null | 名称（导入为 null，UI 隐藏，下游 COALESCE 引用保留） | UI 隐藏 |
| `specLabel` | string\|null | 配比（导入为 null，UI 隐藏） | UI 隐藏 |
| `recipeType` | `'locked'\|'editable'\|'partial'` | 类型；导入/新建默认 locked | 默认值改 locked |
| `status` | `'ACTIVE'\|'INACTIVE'` | 启用/停用 | — |
| `sortOrder` | int | 排序序号 | — |
| `createdAt` | ISO8601 | 创建时间 | **新增字段** |
| `updatedAt` | ISO8601 | 修改时间 | **新增字段** |
| `elements` | 数组\|null | 仅详情端点填充 | — |
| `boundPartsCount` | long\|null | 绑定料号数（本期前端不展示） | — |

---

## 三、POST /material-recipes/import（导入，新增）

### 请求
- `Content-Type: multipart/form-data`
- part：`file` = `.xlsx`（用户上传的材质库文件）
- 鉴权：`SYSTEM_ADMIN`

### 处理语义（后端保证）
1. **只读** `材质编号` + `材质对应元素` 两 sheet，其余忽略。
2. 含量 **×100** 存储；行/材质级校验，脏行跳过不中断。
3. **按材质编号 Upsert**：文件内材质覆盖、文件外材质不动。
4. 同步元素主表 `element`。
5. 全程单事务、批量落库。

### 响应 `200` — `MaterialImportReportDTO`
```json
{
  "totalRows": 654,
  "materialsUpserted": 254,
  "elementRowsInserted": 620,
  "elementMasterUpserted": 5,
  "skippedRowCount": 11,
  "skipped": [
    { "sheet": "材质对应元素", "row": 42, "reason": "元素名称为纯数字(疑料号误填)", "raw": "191" },
    { "sheet": "材质对应元素", "row": 88, "reason": "含量合计≠1(实际0.87)", "raw": "code=00120" }
  ],
  "durationMs": 1180
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalRows` | int | 材质对应元素数据行数 |
| `materialsUpserted` | int | 落库材质数（新增+覆盖） |
| `elementRowsInserted` | int | 落库元素明细行数 |
| `elementMasterUpserted` | int | 元素主表新增/更新数 |
| `skippedRowCount` | int | 跳过行/材质数 |
| `skipped[]` | 数组 | `{sheet,row,reason,raw}` 逐条原因，前端表格展示 |
| `durationMs` | long | 耗时（性能自检可见） |

### 错误
| HTTP | 场景 | body |
|------|------|------|
| 400 | 非 xlsx / 缺必需 sheet / 空文件 | `{"message":"模板缺少必需 sheet: 材质对应元素"}` |
| 401/403 | 未登录 / 非 SYSTEM_ADMIN | 标准鉴权错误 |
| 500 | 解析异常 | `{"message":"..."}`（前端提示"导入失败，请检查文件"） |

> **约定**：脏数据**不**报 400，走 200 + `skipped[]`。400 仅用于"文件本身不可用"。

---

## 四、GET /material-recipes/import/template（模板下载，新增）

### 请求：无参。返回 `.xlsx` 二进制流。
- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment; filename="material_library_template.xlsx"`
- 内容：两 sheet 空模板
  - `材质编号`：表头 `材质 | 材质编号`
  - `材质对应元素`：表头 `材质 | 材质编号 | 元素名称 | 含量 | 元素编号`（1 行示例 + 批注"含量填 0–1 小数，同材质相加=1"）

前端以 blob 下载。

---

## 五、复用接口（契约不变，仅提示）

### GET /material-recipes/{id} — 详情
返回 `MaterialRecipeDTO` + `elements[]`：
```json
{
  "id":"…","code":"00002","symbol":"AgC3","recipeType":"locked","status":"ACTIVE",
  "createdAt":"…","updatedAt":"…",
  "elements":[
    {"elementCode":"Ag","elementName":"银","defaultPct":97.0,"minPct":null,"maxPct":null,"isLocked":true,"sortOrder":1},
    {"elementCode":"C","elementName":"碳","defaultPct":3.0,"minPct":null,"maxPct":null,"isLocked":true,"sortOrder":2}
  ]
}
```

### POST /material-recipes（新建）/ PUT /material-recipes/{id}（编辑）
Body = `MaterialRecipeUpsertRequest`（**契约不变**）：
```json
{
  "code":"00300","symbol":"AgNi10","name":null,"specLabel":null,
  "recipeType":"locked","sortOrder":300,"status":"ACTIVE",
  "elements":[
    {"elementCode":"Ag","elementName":"银","defaultPct":90,"isLocked":true,"sortOrder":1},
    {"elementCode":"Ni","elementName":"镍","defaultPct":10,"isLocked":true,"sortOrder":2}
  ]
}
```
前端约束（见 fronttask）：
- **新建**：`recipeType` 默认 `locked`；`code`（材质编号）可填。
- **编辑**：`code` 只读（后端 update 亦不应改 code）。
- `name`/`specLabel` 前端不再收集，置 null 传或省略。
- 元素 `defaultPct` 之和须 = 100（后端已有校验，沿用）。

### DELETE /material-recipes/{id} — 停用
软删（status→INACTIVE），`204 No Content`。停用后仍出现在列表（排在启用项之后）。

---

## 六、内部数据（非对外接口，供联调参考）

### element 元素主表（新增，无独立管理接口）
| 列 | 类型 | 说明 |
|----|------|------|
| `element_code` | varchar UNIQUE | 符号（=定价 join 键，Excel「元素名称」） |
| `element_name` | varchar | 中文名（字典命中中文，否则=符号） |
| `element_no` | varchar\|null | Excel 元素编号（留存备用，当前无消费方） |
| `status` | varchar | ACTIVE/INACTIVE |

> 本期不提供元素 CRUD 接口；仅由导入同步维护 + seed。列表搜索的"按元素"过滤走 `material_recipe_element` 子查询，不直接查本表。
