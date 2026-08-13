# 接口契约 — task-0812 材质元素改下拉选择

> **结论先行：本任务无新增、无修改、无删除任何接口契约。**
> 前端只是**新增一次对既有端点 `GET /api/cpq/elements` 的调用**，请求参数与响应结构完全沿用现状；
> 材质保存端点 `POST/PUT /api/cpq/material-recipes` 的**请求体字段集合一字不改**。
> 因此按 `任务平台规则.md` §2.4，本任务**无需回写 `dev-docs/main-api.md`**，该结论须在 `test-report.md` 中写明。

---

## API-1（复用）GET /api/cpq/elements — 元素字典列表

- **来源**：`task-0709-元素主表管理`（BL-0040），已在 master，本次**不改**
- **实现**：`ElementResource.list()` → `ElementService.list(keyword)`
- **鉴权**：`SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
  （与 `MaterialRecipeResource` 读权限完全一致 → 能打开材质抽屉者必然有权读本接口，不会 401）
- **本次调用方式**：抽屉 `open` 变 true 时调用一次，**不传 `keyword`**（全量拉取，前端本地过滤，见需求文档 D11）

### 请求

| 参数 | 位置 | 类型 | 必填 | 本次取值 | 说明 |
|---|---|---|---|---|---|
| `keyword` | query | string | 否 | **不传** | 后端按 `element_no / element_code / element_name` 做 ILIKE 模糊匹配；本次改由前端本地过滤 |

```
GET /api/cpq/elements
```

### 响应 200

数组，元素为 `ElementDTO`。前端类型见 `cpq-frontend/src/services/elementService.ts` 的 `ElementItem`。

| 字段 | 类型 | 本次是否使用 | 说明 |
|---|---|---|---|
| `id` | UUID | 否 | 主键 |
| `elementNo` | string | **是** | 元素编号，不可改业务主键 → 用作 Select 的 `value`（FR-2 显示第 1 段） |
| `elementCode` | string | **是** | 元素符号 → 显示第 2 段；提交时写入 `elements[].elementCode` |
| `elementName` | string | **是** | 中文名 → 显示第 3 段；提交时写入 `elements[].elementName` |
| `status` | `'ACTIVE' \| 'INACTIVE'` | **是** | 候选只保留 `ACTIVE`（FR-4）；老数据回显命中 `INACTIVE` 时打「已停用」标记（FR-7） |
| `referencedCount` | number | 否 | 被引用材质元素行数 |
| `codeLocked` | boolean | 否 | `referencedCount > 0` 时为 true |
| `createdAt` / `updatedAt` / `lastModifiedAt` | ISO8601 | 否 | 时间戳 |

```json
[
  { "id": "…", "elementNo": "10001", "elementCode": "Ag", "elementName": "银",
    "status": "ACTIVE", "referencedCount": 128, "codeLocked": true,
    "createdAt": "2026-07-08T10:00:00+08:00", "updatedAt": "2026-07-09T12:00:00+08:00",
    "lastModifiedAt": "2026-08-01T09:30:00+08:00" },
  { "id": "…", "elementNo": "10004", "elementCode": "Sn", "elementName": "锡",
    "status": "ACTIVE", "referencedCount": 31, "codeLocked": true }
]
```

- **排序**：后端已按「启用优先 → `lastModifiedAt` 倒序」返回。前端**不重排**，直接沿用（常用元素靠前）。
- **数据规模**：现网 37 条，全部 `ACTIVE`。

### 错误码

| 码 | 含义 | 前端处理 |
|---|---|---|
| 401 | 未登录 / token 失效 | 交由全局拦截器处理（跳登录） |
| 403 | 角色不足 | 理论不会发生（见鉴权说明）；若发生按 500 同路径提示 |
| 5xx | 服务端异常 | `message.error('元素字典加载失败，请刷新重试')` + 下拉显示加载失败空态，**不得静默显示为「无可选元素」**（FR-10） |

### 对应功能需求

FR-2 / FR-3 / FR-4 / FR-7 / FR-10

---

## API-2（复用，契约不变）POST /api/cpq/material-recipes ｜ PUT /api/cpq/material-recipes/{id}

- **实现**：`MaterialRecipeResource.create/update` → `MaterialRecipeService`
- **鉴权**：`SYSTEM_ADMIN`（写操作，现状不变）
- **本次变化**：**无**。请求体 `elements[]` 的字段集合与取值语义**完全不变**，仅「`elementCode` / `elementName` 的来源」从用户手输改为下拉选中项的字段值。

```jsonc
// 请求体片段（字段集合与改动前完全一致）
{
  "code": "00300",
  "symbol": "Ag",
  "name": null,
  "specLabel": null,
  "recipeType": "locked",
  "sortOrder": 100,
  "status": "ACTIVE",
  "elements": [
    {
      "elementCode": "Ag",     // ← 来自下拉选中项的 elementCode（原为手输）
      "elementName": "银",      // ← 来自下拉选中项的 elementName（原为手输）
      "defaultPct": "100",
      "minPct": undefined,
      "maxPct": undefined,
      "isLocked": true,
      "sortOrder": 1
    }
  ]
}
```

> ⚠️ **明确不加 `elementNo` 字段**（需求文档 D9）。后端 `MaterialRecipeUpsertRequest.ElementUpsert` 不动，
> `material_recipe_element.element_no` 继续保持 NULL，遗留登记 `BL-0163`。

### 后端既有校验（保留，前端新增校验是其前置防线，不是替代）

| 校验 | 报错文案（后端） |
|---|---|
| `elementCode` 必填 | `element.elementCode 必填` |
| 同材质内 `elementCode` 重复 | `element.elementCode 重复: <code>` |
| `defaultPct` 之和 = 100（±0.01） | `元素 default_pct 之和必须 = 100, 当前: <sum>` |
| `recipeType` 与 `isLocked` / `min` / `max` 组合合法性 | 见 `MaterialRecipeService.validateUpsert()` |

### 对应功能需求

FR-8 / FR-9

---

## 契约变更登记

| 项 | 结论 |
|---|---|
| 新增端点 | 无 |
| 修改端点（方法/路径/参数/响应/错误码） | 无 |
| 删除端点 | 无 |
| 需回写 `dev-docs/main-api.md` | **否**（在 `test-report.md` 写明「本次无契约变更，无需回写 main-api.md」） |
