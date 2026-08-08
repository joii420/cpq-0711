# task-0806 模板发布全量冻结 —— API 契约

> **前后端交接的唯一依据**。任何一方要改契约，必须**先改本文件并通知另一方**。
> 测试完成后，本文件的**最终**契约（非设计初稿）须按端点回写 `dev-docs/main-api.md`（`任务平台规则.md` §2.4）。
> 关联：`需求文档.md`（FR / AC）｜`backtask.md`｜`fronttask.md`
>
> **🔄 2026-08-07 B20 更新**（D16~D19 推翻原 D3）：不做存量迁移；`frozen-drift` 响应新增
> `frozen` 字段；渲染路径新增 `409 TEMPLATE_NOT_FROZEN`；`archive()` 自动补冻。见文末
> §12「B20 契约变更」。

---

## 0. 变更总览

| # | 方法 + 路径 | 变更类型 | FR |
|---|---|---|---|
| A1 | `POST /api/cpq/templates/{id}/publish` | **行为变更，契约不变** | FR-1 / FR-2 |
| A2 | `GET /api/cpq/templates/{id}/frozen-drift` | **新增**（B20：响应新增 `frozen` 字段） | FR-9 |
| A3 | `GET /api/cpq/templates/admin/frozen-drift` | **新增**（批量版，兼体检 A；B20：新增 `unfrozen` 字段） | FR-8 |
| A4 | `GET /api/cpq/templates/admin/sqlview-closure-check` | **新增**（体检 B；B20：新增 `checked`/`unfrozenTemplateIds` 字段） | FR-6 / FR-8 |
| A5 | `POST /api/cpq/config-center/refresh-all-snapshots` | **改造 + 实现重写** | FR-3 / FR-7 |
| A6 | `POST /api/cpq/templates/admin/{templateId}/delete-tcs` | **改造**（加预览 + 审计） | FR-7 |
| A7 | `POST /api/cpq/templates/admin/promote-override-to-component` | **改造**（加预览 + 审计） | FR-7 |
| A8 | `POST /api/cpq/components/{id}/refresh-template-snapshots` | **删除** | FR-3 |
| A9 | `POST /api/cpq/templates/admin/migrate-to-unified-view` | **删除** | FR-7 |
| A10 | `POST /api/cpq/templates/{id}/archive` | **B20 新增行为**（契约/响应形状不变，内部若零快照自动补冻，见 §12.3） | FR-8 |

**前端受影响面（B20 起不再为零）**：A1/A10 契约形状不变；A2~A7 全部 `SYSTEM_ADMIN` 运维端点，本期不做 UI（D10）；A8/A9 前端从未调用（已 grep 确认）。
**但 B20 新增了一条真实前端影响面**：报价/核价渲染相关端点（`ensure-card-values` / `ensure-excel-values` / BOM 树 / Excel 视图等一切经 `PublishedTemplateReader` 的读取路径）在模板"尚未按新语义重新发布"时会返回 **`HTTP 409, data.code = "TEMPLATE_NOT_FROZEN"`**，前端需要能识别这个 code 并提示用户"请联系管理员重新发布该模板"，而不是把它当通用错误弹一个不明所以的 toast。原有的 `CopyQuotationDrawer.tsx` 文案改动仍保留，不涉及接口。

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
    "frozen": true,
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

- **`frozen`（B20 新增字段，先看这个再看 `hasDrift`）**：该模板是否已按新语义完成过至少一次
  "重新发布"（`template_component_snapshot` 非空）。`false` = 过渡期"未冻结"，**不是故障**，
  是正常状态——原 D3「存量一次性对齐」已被 D16 推翻，本任务上线后所有既有 PUBLISHED/ARCHIVED
  模板都会先经历这个状态，直到有人手动重新发布。
- **`frozen:false` 时 `hasDrift` 恒为 `false`，但这不代表"没有差异"，只代表"没东西可比"**——
  绝不能把 `hasDrift:false` 单独当"一切正常"解读，必须先看 `frozen`。
- `frozen:true` 时才回到原语义：迁移/重新发布刚完成时应**全部 `hasDrift: false`**，之后随组件
  迭代，差异**自然增长 —— 这是严格版本化下的正常状态，不是故障**
- 它回答的是「这个已发布模板比当前组件配置落后多少，值不值得发新版」
- 同时兼任 admin 后门的安全网：谁偷改了快照，这里可见

### 边界

| 情况 | 行为 |
|---|---|
| 模板不存在 | `404` `Template not found: {id}` |
| 模板为 DRAFT | `400` `DRAFT 模板无快照，无差异可比` |
| **模板 PUBLISHED/ARCHIVED 但快照零行（B20 新增）** | **`200`**，返回 `frozen: false, hasDrift: false, driftCount: 0, tabs: []`——**不报错**，这是本端点的诊断职责所在，不是异常 |
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
    "unfrozen": 12,
    "totalFieldDrifts": 0,
    "templates": []
  }
}
```

`templates[]` 元素结构同 A2 的 `data`（每项都带 `frozen`）。

**B20 新增 `unfrozen` 字段**：本轮扫描到的模板里，有多少个 `frozen:false`（尚未按新语义重新发布）。
**`onlyDrift=true`（默认）时，`frozen:false` 的模板依然会被列进 `templates[]`**——即使它们
`hasDrift` 恒为 `false`：未冻结比"有 drift"更值得运维关注（对应的渲染路径会直接 409），
不能被默认参数悄悄过滤掉。

> **迁移前用法**：`onlyDrift=true` 跑一次，输出**存档**。这就是 D12 说的「差异清单人工过目」。理论上应接近空——非空处即历史上某次自动传导失败留下的疤，**冻死前必须人工看一眼**。B20 后上线首日预期 `unfrozen` 会很高（等于全部既有 PUBLISHED/ARCHIVED 模板数），随人工逐个重新发布而下降，这是预期曲线，不是回归。

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
    "checked": 5,
    "unfrozenTemplateIds": ["88d5d815-...", "..."],
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

**B20**：`scanned` = 命中 `status` 过滤的模板总数；`checked` = 其中`template_component_snapshot`
非空、真正参与了本轮体检的模板数；`unfrozenTemplateIds` = 零快照行、**被跳过**的模板 id
列表——这些模板<b>不会</b>贡献 `totalRefs`/`misses`，不能把它们的"零引用"误读成"没有 SQL
视图缺口"（同 A3 的假阴性教训，`checked + unfrozenTemplateIds.length == scanned`）。

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

| HTTP | 错误信息（含变量） / `data.code` | 触发 | AC |
|---|---|---|---|
| **`409`** | **`模板尚未重新发布：templateId={id}, status={status}。该模板的渲染配置冻结快照为空（过渡期正常状态），请前往模板管理重新发布该模板后再试。` / `data.code="TEMPLATE_NOT_FROZEN"`** | **（B20，取代下面旧的"零行"500）** 渲染 PUBLISHED/ARCHIVED 模板时快照**零行**——正常过渡态，不是系统故障。响应体额外带 `data.templateId` / `data.templateStatus`，**前端按 `data.code` 判定，禁止按 message 文本匹配** | AC-9②③ |
| `500` | `模板快照缺失：templateId={id}, sortOrder={n}。已发布模板存在部分页签快照但该页签缺失，快照可能被破坏，请检查 template_component_snapshot` | 渲染 PUBLISHED/ARCHIVED 模板时**有快照但缺某个 sortOrder**（B20 起与上面 409 严格区分：这才是真损坏）。**绝不回落活表** | AC-9④ |
| `500` | `SQL 视图快照缺失：templateId={id}, componentId={cid}, view={name}。已发布模板不允许回落实时读取` | 已发布上下文 `sql_views_snapshot` miss | AC-7 |
| `400` | `DRAFT 模板无快照，无差异可比` | 对 DRAFT 模板调 A2 | AC-10 |
| `404` | `Template not found: {id}` | A2 / A3 / A6 模板不存在 | — |

> 部分缺行（真损坏）刻意用 `500` 而非 `400`：这是**系统不变量被破坏**，不是用户输入错误。必须显眼、必须进日志、必须让人来查。
> 零行未冻结改用 `409`（而非旧版的 `500`）：这是**可恢复的正常状态**（重新发布模板即可恢复），语义上更接近"资源状态冲突"而非"服务器内部错误"，也便于前端用 HTTP 状态码本身做粗粒度分流。

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

---

## 12. B20 契约变更（2026-08-07，D16~D19 推翻原 D3）

用户拍板：**不迁移存量**。原 D3「一次性对齐存量后冻死」作废，改为「不迁移存量，业务逻辑修正
后由用户手工重新发布模板即可」，配套「过渡期显式提示」+「归档自动补冻」。三处契约变化：

### 12.1 渲染路径新增 `409 TEMPLATE_NOT_FROZEN`（前端**必须**处理的新状态）

任何经 `PublishedTemplateReader` 读取快照的端点——`ensure-card-values` / `ensure-excel-values` /
`refresh-card-snapshot` / BOM 树渲染 / Excel 视图 / 核价版本渲染等——若目标模板 `status ∈
(PUBLISHED, ARCHIVED)` 但 `template_component_snapshot` 零行（该模板还没按新语义重新发布过），
不再报 `500`，改报：

```json
{
  "code": 409,
  "message": "模板尚未重新发布：templateId=xxx, status=PUBLISHED。该模板的渲染配置冻结快照为空（过渡期正常状态），请前往模板管理重新发布该模板后再试。",
  "data": {
    "code": "TEMPLATE_NOT_FROZEN",
    "templateId": "xxx",
    "templateStatus": "PUBLISHED"
  }
}
```

**前端接入点**：与 `FormulaCycleException` 的 `errorType` 同款纪律——统一错误拦截器/各请求的
catch 分支按 `data.code === "TEMPLATE_NOT_FROZEN"` 识别，提示语建议「该产品所用的模板尚未完成
最新配置的重新发布，请联系模板管理员重新发布后再试」，**不要**当成通用失败弹 toast，也**不要**
静默吞掉后继续用旧数据渲染。

**受影响面评估**：B20 上线当天，dev 库里所有既有 PUBLISHED/ARCHIVED 模板的
`template_component_snapshot` 都会是空的（V382 不再回填），直到人工逐个重新发布——也就是说
上线当天几乎所有已发布模板绑定的报价单，首次触发懒计算（`ensure-card-values` 等）都会先吃到
这个 409，这是**预期行为**，不是回归。建议前端上线说明里带一句「首次打开某些历史报价单会提示
"请联系管理员重新发布模板"，属已知过渡期现象，管理员重新发布对应模板后即恢复正常」。

### 12.2 `frozen-drift`（A2/A3）与 `sqlview-closure-check`（A4）新增字段

见 §3 / §4 / §5 各自的更新——核心是新增 `frozen` / `unfrozen` / `checked` /
`unfrozenTemplateIds` 字段，用于把"未冻结"与"已冻结但无差异/无缺口"区分开，本期同样
**仅 API，无 UI**（沿用 D10），但契约已定，UI 化时直接接。

### 12.3 `POST /api/cpq/templates/{id}/archive`（A10）：自动补冻

请求/响应形状**完全不变**（仍是 `TemplateDTO`）。内部行为变更：归档前若发现该模板
`template_component_snapshot` 零行，会先按当时的活组件配置补一份完整快照（与 `publish()`
落库逻辑完全一致），再置 `ARCHIVED`。**唯一可观测的差异**是极少数情况下响应耗时略增
（多了一次快照写入，量级与该模板页签数一致，不随其他数据量增长）。前端**不需要任何改动**。

### 12.4 前端需要配合的改动清单（交给 cpq-frontend 的具体交接点）

1. 全局或按模块的请求错误处理里加一个 `data.code === "TEMPLATE_NOT_FROZEN"` 的分支（409），
   文案见 §12.1；不要复用"通用 500 报错"的展示组件（那个通常带"联系技术支持"字样，会误导
   用户去找错人——这是配置状态，不是系统故障）。
2. 若产品详情页/报价单编辑页在渲染前有"能否编辑/能否查看"的预判逻辑，可选（非必须）调用
   `GET /templates/{id}/frozen-drift` 的 `frozen` 字段做提前判断，避免用户先看到空白再看到报错
   ——但这是体验优化，**不是本期强制项**（D10：本任务不做冻结相关 UI，只保证契约到位）。
3. 无需改动：A1 `publish`、A10 `archive` 的请求/响应形状。
