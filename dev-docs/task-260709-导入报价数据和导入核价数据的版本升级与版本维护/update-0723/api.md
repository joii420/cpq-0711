# 接口文档 · update-0723 报价导入模板 0723 适配

> 关联需求：`update-0723/需求说明.md`
> 后端：`update-0723/backtask.md` · 前端：`update-0723/fronttask.md`

---

## 0. 总则

本次**不新增端点、不改路由、不改请求契约**。仅：
1. 导入语义改为「两阶段全量校验 + 整单回滚」；
2. 返回体 `status` 不再出现 `PARTIAL`；
3. 错误清单结构保持兼容（一次性返回全部错误行）。

现役端点全部位于 `BasicDataImportV6Resource`（`/api/cpq/basic-data-import/v6`）。

---

## 1. 发起导入（异步）

```
POST /api/cpq/basic-data-import/v6/quote
Content-Type: multipart/form-data
角色：SALES_REP | SALES_MANAGER | SYSTEM_ADMIN
```

**请求（form-data）**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户主键（后端取 `customer.code` 作 `customer_no`） |
| file | file | 是 | `报价系统模板0723.xlsx` |

**响应（立即返回，不等落库）**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "importRecordId": "b3f1...uuid",
    "systemType": "QUOTE",
    "status": "PROCESSING"
  }
}
```

**错误**：`400` customerId/file 为空 / 客户无 code；`404` 客户不存在；`401` 未登录。

> 行为不变：请求线程建 `import_record(PROCESSING)` + 读文件入内存 → `managedExecutor` 后台跑 `processImport` → 前端轮询。

---

## 2. 轮询导入结果

```
GET /api/cpq/basic-data-import/v6/{recordId}
角色：SALES_REP | SALES_MANAGER | SYSTEM_ADMIN
```

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "importRecordId": "b3f1...",
    "systemType": "QUOTE",
    "status": "PROCESSING | SUCCESS | FAILED",
    "totalRows": 42,
    "successRows": 42,
    "failedRows": 0,
    "originalFileName": "报价系统模板0723.xlsx",
    "createdAt": "2026-07-23T...",
    "metadata": "<jsonb 字符串，见 §3>"
  }
}
```

### status 取值（本次变化点）

| 状态 | 含义 |
|------|------|
| `PROCESSING` | 处理中（Phase 1 校验或 Phase 2 写入进行中） |
| `SUCCESS` | 全量校验通过 + 全部写入成功 |
| `FAILED` | 校验未通过（零写库）**或** 写入阶段异常（已整单回滚） |
| ~~`PARTIAL`~~ | **本次移除**，不再产生（U7 整单回滚） |

---

## 3. metadata 结构

`metadata` 为 jsonb 字符串，两种形态：

### 3.1 处理中（进度）

```json
{ "progress": { "done": 3, "total": 14, "current": "自制加工费" } }
```
- Phase 1 校验期可上报 `current: "校验中"`（可选）；Phase 2 写入期逐 handler 上报。

### 3.2 结束（结果）

```json
{
  "sheetResults": [
    {
      "sheetName": "物料BOM",
      "totalRows": 12, "successRows": 12, "failedRows": 0,
      "writes": { "material_bom": 3, "material_bom_item": 12 },
      "errors": []
    },
    {
      "sheetName": "组成件其他费用",
      "totalRows": 2, "successRows": 0, "failedRows": 1,
      "errors": [
        { "rowNo": 5, "column": "组成件料号", "message": "料号「992」类型冲突：同时命中 材质(物料与元素BOM) 与 外购件" }
      ]
    }
  ]
}
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| sheetName | sheet 名 |
| totalRows/successRows/failedRows | 行数统计 |
| writes | 各表写入行数（成功时） |
| errors[] | 错误行：`{rowNo, column, message}` |

> **兼容性**：结构与现状一致，前端现有错误清单渲染直接复用。变化仅在于：`FAILED` 时 `errors` 汇集**全量**校验错误（Phase 1 一次性收集所有 sheet 的错误，不再「遇错即停」），前端应能展示多条。

---

## 4. 后续步骤（不变）

导入 `SUCCESS` 后创建报价单（契约不变）：

```
POST /api/cpq/basic-data-import/v6/quote/create-quotation
Content-Type: application/json
Body: { importRecordId, customerId, name, ... }
```

pending 归属「过户」为真实 quotationId（task-0721 机制），本次不改。

---

## 5. 可选项（默认不实现，需双方确认才加）

若后续希望前端区分「校验失败」与「写入失败」，可在 §3.2 顶层增字段：

```json
{ "phase": "VALIDATE | WRITE", "sheetResults": [...] }
```
- `phase=VALIDATE` → Phase 1 校验未过（零写库）；
- `phase=WRITE` → Phase 2 写入异常（已回滚）。

**默认不加**（U14 前端无改动）。如需，前端按 fronttask §2 增一行提示即可。

---

## 6. 契约变更摘要（供前后端对齐）

| 项 | 变化 |
|----|------|
| 路由 / 请求字段 | **无变化** |
| `status` 枚举 | 移除 `PARTIAL` |
| `metadata.progress` | 无变化 |
| `metadata.sheetResults[].errors` | 结构无变化；FAILED 时为全量错误集 |
| 新增字段 | 无（`phase` 为可选、默认不加） |
