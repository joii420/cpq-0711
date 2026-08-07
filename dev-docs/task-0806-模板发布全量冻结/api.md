# task-0806 模板发布全量冻结 —— API 契约

> **前后端交接的唯一依据**。任何一方要改契约，必须**先改本文件并通知另一方**。
> 测试完成后，本文件的**最终**契约（非设计初稿）须按端点回写 `dev-docs/main-api.md`（`任务平台规则.md` §2.4）。
> 关联：`需求文档.md`（FR / AC）｜`backtask.md`｜`fronttask.md`

---

## 0. 变更总览

| # | 方法 + 路径 | 变更类型 | FR |
|---|---|---|---|
| A1 | `POST /api/cpq/templates/{id}/publish` | **行为变更，契约不变** | FR-1 / FR-2 |
| A2 | `GET /api/cpq/templates/{id}/frozen-drift` | **新增** | FR-9 |
| A3 | `GET /api/cpq/templates/admin/frozen-drift` | **新增**（批量版，兼体检 A） | FR-8 |
| A4 | `GET /api/cpq/templates/admin/sqlview-closure-check` | **新增**（体检 B） | FR-6 / FR-8 |
| A5 | `POST /api/cpq/config-center/refresh-all-snapshots` | **改造 + 实现重写** | FR-3 / FR-7 |
| A6 | `POST /api/cpq/templates/admin/{templateId}/delete-tcs` | **改造**（加预览 + 审计） | FR-7 |
| A7 | `POST /api/cpq/templates/admin/promote-override-to-component` | **改造**（加预览 + 审计） | FR-7 |
| A8 | `POST /api/cpq/components/{id}/refresh-template-snapshots` | **删除** | FR-3 |
| A9 | `POST /api/cpq/templates/admin/migrate-to-unified-view` | **删除** | FR-7 |

**前端受影响面：零。** A1 契约不变；A2~A7 全部 `SYSTEM_ADMIN` 运维端点，本期不做 UI（D10）；A8/A9 前端从未调用（已 grep 确认）。前端本期唯一改动是 `CopyQuotationDrawer.tsx` 文案（`fronttask.md`），不涉及接口。

---

## 1. 通用约定

- **响应包装**：沿用 `ApiResponse<T>` → `{ "code": 200, "message": "success", "data": ... }`
- **鉴权**：`@RoleAllowed`，下文每节标注
- **错误码**：沿用 `BusinessException(status, message)`；本任务新增的错误码见 §10
- **时间**：ISO-8601 带时区（`OffsetDateTime` 默认序列化）

---

## 2. A1 · `POST /api/cpq/templates/{id}/publish`

**契约完全不变**，仅内部行为变更。此处记录以明确「前端无需改动」。

- **鉴权**：`SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求**：`{ "majorVersion": 2 }`（可选，不传则次版本号 +1）
- **响应**：`ApiResponse<TemplateDTO>`，字段与现状一致（含 `componentsSnapshot`）

**内部行为变更（FR-1 / FR-2）**：同一事务内额外写 `template_component_snapshot` N 行；`components_snapshot` 改为**由该表派生**而非各自拼装。

> ⚠️ **验收要点（AC-2）**：同一模板在改造前后调用本端点，返回的 `componentsSnapshot` 必须**逐字段一致**（键集合、键顺序、值）。前端 `BulkImportPartsDrawer.tsx:123`、`QuotationStep2.tsx:3844`、`ExcelViewConfigTab`、`enrichComponentData` 依赖此形状。

---

## 3. A2 · `GET /api/cpq/templates/{id}/frozen-drift`

单模板的「快照 vs 当前活组件配置」逐字段差异。**FR-9，本期仅 API 无 UI。**

- **鉴权**：`SYSTEM_ADMIN`
- **路径参数**：`id`（UUID，必填）—— 模板 ID
- **查询参数**：无

### 响应

```json
{
  "code": 200, "message": "success",
  "data": {
    "templateId": "88d5d815-...",
    "templateName": "报价模板V2",
    "templateVersion": "v1.3",
    "templateStatus": "PUBLISHED",
    "frozenAt": "2026-08-06T10:00:00+08:00",
    "hasDrift": true,
    "driftCount": 2,
    "tabs": [
      {
        "sortOrder": 3,
        "tabName": "材料成本",
        "componentId": "e42185ec-...",
        "componentCode": "COMP-0045",
        "componentExists": true,
        "fieldDrifts": [
          {
            "field": "formulas",
            "frozenValue": "[{\"name\":\"金额\",\"expression\":\"[单价]*[数量]\"}]",
            "liveValue":   "[{\"name\":\"金额\",\"expression\":\"[单价]*[数量]*[系数]\"}]"
          },
          { "field": "sortField", "frozenValue": null, "liveValue": "项次" }
        ]
      }
    ]
  }
}
```

### 语义（重要，别当告警器用）

- 迁移刚完成时应**全部 `hasDrift: false`**
- 之后随组件迭代，差异**自然增长 —— 这是严格版本化下的正常状态，不是故障**
- 它回答的是「这个已发布模板比当前组件配置落后多少，值不值得发新版」
- 同时兼任 admin 后门的安全网：谁偷改了快照，这里可见

### 边界

| 情况 | 行为 |
|---|---|
| 模板不存在 | `404` `Template not found: {id}` |
| 模板为 DRAFT | `400` `DRAFT 模板无快照，无差异可比` |
| 快照行存在但组件已被删除 | 该 tab 返回 `componentExists: false`，`fieldDrifts` 为空（不视为 drift） |

> 📌 **`componentExists: false` 是刻意的防御性分支，不是可从 UI 触达的常规场景**（2026-08-07 测试提问后裁定）。
> 正常 API 路径到不了这个状态：`ComponentService.delete:862` 的 `checkNotReferencedByTemplate` 会拦下任何仍被 `template_component`（含 DRAFT）引用的组件删除。
> **但这个状态在结构上是可能的** —— 新表刻意不建 FK 到 `component`（§5.1.1），所以裸 SQL、未来的目录级联删除、或 `task-0723 废弃业务与表清洗` 这类批量清理都可能造出它。
> **保留该分支**：快照的意义就是脱钩，读快照时绝不能因为组件没了就崩。测试用裸 SQL 构造覆盖（`test.md` TC-10-5）。
> **不要**因为「当前打不到」就删掉它 —— 那会让将来某条清理路径直接把渲染打挂。
| 组件被 `DISABLED` | **不视为 drift**（D5：status 不进快照也不进渲染路径） |

---

## 4. A3 · `GET /api/cpq/templates/admin/frozen-drift`

A2 的批量版，兼**迁移前体检 A**。

- **鉴权**：`SYSTEM_ADMIN`
- **查询参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 否 | 默认 `PUBLISHED,ARCHIVED`；逗号分隔 |
| `onlyDrift` | boolean | 否 | 默认 `true`，只返回有差异的模板 |

### 响应

```json
{
  "code": 200, "message": "success",
  "data": {
    "scanned": 17,
    "withDrift": 0,
    "totalFieldDrifts": 0,
    "templates": []
  }
}
```

`templates[]` 元素结构同 A2 的 `data`。

> **迁移前用法**：`onlyDrift=true` 跑一次，输出**存档**。这就是 D12 说的「差异清单人工过目」。理论上应接近空——非空处即历史上某次自动传导失败留下的疤，**冻死前必须人工看一眼**。

---

## 5. A4 · `GET /api/cpq/templates/admin/sqlview-closure-check`

**迁移前体检 B**：验证每个已发布模板引用到的 SQL 视图都在 `sql_views_snapshot` 闭包内。是 FR-6 中「SQL 视图 fallback 切报错」的**前置门槛**（D13）。

- **鉴权**：`SYSTEM_ADMIN`
- **查询参数**：`status`（同 A3）

### 响应

```json
{
  "code": 200, "message": "success",
  "data": {
    "scanned": 17,
    "totalRefs": 214,
    "missCount": 0,
    "misses": [
      {
        "templateId": "88d5d815-...",
        "templateName": "报价模板V2",
        "componentId": "e42185ec-...",
        "componentCode": "COMP-0045",
        "sqlViewName": "v_material_cost",
        "crossComponent": false,
        "reason": "NOT_IN_SNAPSHOT"
      }
    ]
  }
}
```

`reason` 值域：`NOT_IN_SNAPSHOT`（快照里没有）/ `SNAPSHOT_EMPTY`（该模板整个 `sql_views_snapshot` 为空）/ `VIEW_DELETED`（快照有但活表已删，仅提示）

### D13 三档判定（**结果必须交用户拍板，实现者不得自决**）

| `missCount` | 处置 |
|---|---|
| `0` | 直接切报错，FR-6 本期完成 |
| 少量、可定点补 | 补闭包算法 + 回填存量 → 再切报错，仍在本期 |
| 大量（翻出闭包算法历史欠账） | FR-6 的 SQL 视图部分**整块拆出单独立项**，主线先上；此时保留 fallback 并在 `test-report.md` + BACKLOG **显式记为已知缺口** |

---

## 6. A5 · `POST /api/cpq/config-center/refresh-all-snapshots`

**改造 + 实现重写。** 原实现是循环调 `refreshSnapshotsByComponent`（`O(N_template × N_component)`，本身违反 N+1 铁律），该方法退役后失去实现基础。

**新语义**：把已发布模板的冻结快照**强制重新对齐**到当前活组件配置 —— 即 D3 一次性迁移的可重复版本。**这是明确破坏不可变性的后门操作。**

- **鉴权**：`SYSTEM_ADMIN`

### 请求

```json
{ "templateIds": ["uuid1", "uuid2"], "confirm": false }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `templateIds` | UUID[] | 否 | 不传或空 → 全部 PUBLISHED + ARCHIVED 模板 |
| `confirm` | boolean | 否 | 默认 `false` = **仅预览不写入**；`true` 才真正执行 |

### 响应（`confirm=false`，预览）

```json
{
  "code": 200, "message": "success",
  "data": {
    "preview": true,
    "affectedTemplates": [
      { "templateId": "…", "name": "报价模板V2", "version": "v1.3",
        "status": "PUBLISHED", "tabCount": 12, "fieldDriftCount": 3 }
    ],
    "affectedTemplateCount": 6,
    "affectedQuotationCount": 23,
    "warning": "此操作将改写已发布模板的冻结快照，破坏版本不可变性。受影响的在途报价单渲染结果可能变化。"
  }
}
```

### 响应（`confirm=true`，执行）

```json
{
  "code": 200, "message": "success",
  "data": {
    "preview": false,
    "refreshedTemplates": 6,
    "refreshedRows": 74,
    "operationLogId": "…"
  }
}
```

### 强制行为

1. `confirm=false`（含缺省）→ **只读，零写入**
2. `confirm=true` → 写 `operation_log` 一行（见 §9）
3. 两种情况都 `LOG.warn` 且含「不可变性」字样
4. **批量化**：SQL 条数与模板数、组件数**无关**（AC-11 同款铁律）

---

## 7. A6 / A7 · 另两个 admin 后门

改造口径与 A5 完全一致：`confirm` 参数 + 预览 + 审计 + 告警。契约仅**新增** `confirm` 字段，原有字段与响应不变（向后兼容，但缺省语义从"直接执行"变为"仅预览"——**这是刻意的破坏性变更**，防止误操作）。

### A6 · `POST /api/cpq/templates/admin/{templateId}/delete-tcs`

```json
{ "sortOrders": [0, 1, 2, 4], "confirm": false }
```

预览响应增加 `tabsToDelete: [{ sortOrder, tabName, componentCode }]`。执行响应保留原有 `deletedTcs` / `snapshotBefore` / `snapshotAfter`，**新增** `operationLogId`。

> ⚠️ 删 tc 后必须**同步删对应的 `template_component_snapshot` 行并重生成 jsonb**，否则快照与 tc 不一致。

### A7 · `POST /api/cpq/templates/admin/promote-override-to-component`

```json
{ "componentIds": ["uuid1"], "confirm": false }
```

预览响应增加 `affectedTemplates` / `affectedQuotationCount`。执行响应保留原有 `targetComponents` / `componentsUpdated` / `tcCleared` / `snapshotTouched` / `details[]`，**新增** `operationLogId`。

---

## 8. A8 / A9 · 删除的端点

| 端点 | 删除理由 | 删除后 |
|---|---|---|
| `POST /api/cpq/components/{id}/refresh-template-snapshots` | 其唯一实现 `refreshSnapshotsByComponent` 已退役；语义（把组件配置推给已发布模板）与严格版本化直接冲突 | **路由整个移除** → `404`（D11 用户裁定，不做 410 过渡） |
| `POST /api/cpq/templates/admin/migrate-to-unified-view` | 一次性历史迁移，基线 §3.5 标注「已跑过」，留着只是风险 | **路由整个移除** → `404` |

**调用方核查**：前端全仓 grep 零命中，两者均为运维端点。若有外部脚本调用，`404` 后需人工改走 `createNewDraft` → `publish`。

---

## 9. 审计记录：`operation_log`（D9）

A5 / A6 / A7 在 `confirm=true` 时各写一行。

**表结构变更**：加法式新增一列（不影响 `CustomerService` 等现有写入方）

```sql
ALTER TABLE operation_log ADD COLUMN details jsonb;
```

### 写入约定

| 列 | 值 |
|---|---|
| `operator_id` | 当前登录用户 |
| `operation_type` | `TEMPLATE_SNAPSHOT_FORCE_REFRESH`（A5）/ `TEMPLATE_TC_DELETE`（A6）/ `TEMPLATE_OVERRIDE_PROMOTE`（A7） |
| `target_type` | `TEMPLATE` |
| `target_id` | 模板 ID（A5/A7 批量时每个模板各写一行）。**批量 17 个模板就写 17 行，不额外写聚合摘要行**（2026-08-07 裁定）—— 审计要能按 `target_id` 精确回答「这个模板被谁在什么时候动过」，加一条聚合行等于制造第二个真相源，查起来反而要对账 |
| `summary` | 人话摘要，例：`强制重新对齐已发布模板快照：报价模板V2 v1.3，12 个页签，3 处字段差异` |
| `details` | `{ "endpoint": "...", "before": {...}, "after": {...}, "fieldDrifts": [...] }` |

> **不用 `basic_data_change_log`**：该表语义是「基础资料变更」，模板快照不是基础资料。为复用而扭曲语义会污染 `BL-0100` 的设计意图。

---

## 10. 新增错误码

| HTTP | 错误信息（含变量） | 触发 | AC |
|---|---|---|---|
| `500` | `模板快照缺失：templateId={id}, sortOrder={n}。已发布模板必须有完整冻结快照，请检查 template_component_snapshot` | 渲染 PUBLISHED / ARCHIVED 模板时快照行缺失。**绝不回落活表** | AC-6 |
| `500` | `SQL 视图快照缺失：templateId={id}, componentId={cid}, view={name}。已发布模板不允许回落实时读取` | 已发布上下文 `sql_views_snapshot` miss | AC-7 |
| `400` | `DRAFT 模板无快照，无差异可比` | 对 DRAFT 模板调 A2 | AC-10 |
| `404` | `Template not found: {id}` | A2 / A3 / A6 模板不存在 | — |

> 快照缺失刻意用 `500` 而非 `400`：这是**系统不变量被破坏**，不是用户输入错误。必须显眼、必须进日志、必须让人来查。

---

## 11. 回写 `main-api.md` 清单（测试通过后、合并前）

| 动作 | 端点 |
|---|---|
| 覆盖 | A1 `POST /templates/{id}/publish`（行为说明更新）、A5 `POST /config-center/refresh-all-snapshots`、A6 `POST /templates/admin/{templateId}/delete-tcs`、A7 `POST /templates/admin/promote-override-to-component` |
| 新增 | A2 `GET /templates/{id}/frozen-drift`、A3 `GET /templates/admin/frozen-drift`、A4 `GET /templates/admin/sqlview-closure-check` |
| 移除 | A8 `POST /components/{id}/refresh-template-snapshots`、A9 `POST /templates/admin/migrate-to-unified-view` |

每个被覆盖 / 新增的端点小节末尾加一行（只记最后一次，不累积）：

```
> 来源任务：`task-0806-模板发布全量冻结`｜回写日期：YYYY-MM-DD
```

同步更新 `main-api.md` 头部日期说明。
