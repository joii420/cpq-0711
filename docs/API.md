# CPQ 系统 - 业务场景与接口文档

> **版本**: v4.0
> **更新日期**: 2026-04-23
> **基础路径**: `/api/cpq`
> **认证**: HttpOnly Session Cookie（登录后自动携带）
> **响应格式**: `{ "code": 200, "message": "success", "data": <payload> }`

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 角色与权限](#2-角色与权限)
- [3. 核心业务场景](#3-核心业务场景)
- [4. 状态机](#4-状态机)
- [5. 公共约定](#5-公共约定)
- [6. API 接口](#6-api-接口)
  - [6.1 认证 / 用户](#61-认证--用户)
  - [6.2 客户管理](#62-客户管理)
  - [6.3 产品管理](#63-产品管理)
  - [6.4 基础数据配置 (v4)](#64-基础数据配置-v4)
  - [6.5 业务标签字典 (v4)](#65-业务标签字典-v4)
  - [6.6 核价模板 (v4)](#66-核价模板-v4)
  - [6.7 客户报价模板](#67-客户报价模板)
  - [6.8 报价单核心](#68-报价单核心)
  - [6.9 报价单导入 (v4)](#69-报价单导入-v4)
  - [6.10 核价表 + 比对视图 (v4)](#610-核价表--比对视图-v4)
  - [6.11 审批撤回 (v4)](#611-审批撤回-v4)
  - [6.12 定价 / 数据源 / 组件](#612-定价--数据源--组件)
  - [6.13 系统管理](#613-系统管理)

---

## 1. 项目概述

**CPQ (Configure, Price, Quote)** 是面向制造业/工业组件销售团队的内部报价系统。

**v4 核心特性**：
- **基础数据驱动**：内部统一格式 Excel → 一次导入生成核价表 + 客户报价表
- **双模板体系**：核价模板（按产品分类绑定）+ 客户报价模板（按客户+分类绑定）
- **四视图协同**：产品卡片视图 / Excel 视图 / 核价表视图 / 比对视图
- **衍生字段引擎**：LOOKUP / EXPRESSION / AGGREGATE 三种计算模式
- **业务标签比对**：跨模板按 `comparison_tag` 配对，支持成本/毛利分析
- **审批撤回**：APPROVED → DRAFT 流程

**技术栈**：Java 17 + Quarkus 3.34 + PostgreSQL 16 + Apache POI + Commons JEXL 3.3 + React 18 + Vite + Ant Design 5

---

## 2. 角色与权限

| 角色 Code | 角色名 | 主要职责 |
|---|---|---|
| `SALES_REP` | 销售代表 | 创建报价、上传基础数据、查看本人报价 |
| `SALES_MANAGER` | 销售经理 | 审批报价、配置组件/模板/核价模板/基础数据 |
| `PRICING_MANAGER` | 定价经理 | 维护定价策略 |
| `SYSTEM_ADMIN` | 系统管理员 | 全部权限（用户、字典、规则、兜底审批） |

**权限矩阵摘要**：

| 模块 | SALES_REP | SALES_MANAGER | PRICING_MANAGER | SYSTEM_ADMIN |
|---|---|---|---|---|
| 报价单（本人） | ✓ | ✓（全部） | 查看 | ✓ |
| 从基础数据导入 | ✓ | ✓ | - | ✓ |
| 核价表 / 比对视图 | 本人 | ✓ | ✓ | ✓ |
| 请求撤回 | 本人 | - | - | ✓ |
| 批准撤回 | - | 原审批人 | - | ✓ |
| 客户/产品/料号管理 | 查看 | ✓ | 查看 | ✓ |
| 产品分类管理 | 查看 | ✓ | 查看 | ✓ |
| 基础数据配置 | - | ✓ | - | ✓ |
| 核价/客户报价模板 | 查看 | ✓ | 查看 | ✓ |
| 业务标签字典 | - | ✓ | - | ✓ |
| 定价策略 | 查看 | 查看 | ✓ | ✓ |
| 数据源 / 用户 / 审批规则 | - | - | - | ✓ |

---

## 3. 核心业务场景

### 3.1 配置阶段（一次性 / 增量维护）

```
销售经理 / 系统管理员
  ├─ 维护产品分类、生产料号、客户料号关联
  ├─ 维护基础数据 Excel 配置（Sheet / 列属性 / 衍生字段）
  ├─ 维护业务标签字典（按"材料/加工/汇总"分组）
  ├─ 配置核价模板（按产品分类）
  └─ 配置客户报价模板（按客户 + 产品分类，含 Excel 视图）
```

### 3.2 销售代表导入 + 报价场景

```
1. 选客户 → 上传基础数据 Excel
2. 系统自动:
   - 解析 Excel 构建产品数据池（按 import_batch_id 存储）
   - 计算衍生字段（LOOKUP / EXPRESSION / AGGREGATE）
   - 校验产品分类一致性
   - 匹配核价模板 + 客户报价模板（客户专属优先 → 通用兜底）
   - 渲染核价表 + 产品预览
3. 确认导入 → 事务化创建:
   - Quotation (DRAFT) + QuotationLineItem
   - CostingSheet (LIVE)
   - ImportRecord (含双模板快照)
4. 跳转报价生成器步骤二（四视图）
   - 产品卡片视图：编辑 BASIC_DATA 字段、查看 FORMULA 实时计算
   - Excel 视图：客户原生格式预览/编辑
   - 核价表视图：只读查看成本基线
   - 比对视图：实时显示毛利率 + 修改差异（送审前自检）
5. 商务条款 + 折扣调整 → 提交审批
```

### 3.3 销售经理审批场景

```
1. 收到通知（含毛利率摘要）
2. 进入报价单详情页
3. 查看审批摘要卡片（核价总成本/客户报价总价/毛利/毛利率/修改字段数）
4. 必要时切换到核价表 / 比对差异 tab 查看详情
5. [通过] 或 [退回]（含原因）
```

### 3.4 审批撤回场景（v4 新增）

```
背景: 报价已 APPROVED 但因价格波动需要修改

1. 销售代表在详情页点击 [请求撤回] → 填写原因
2. 系统创建 QuotationWithdrawRequest (PENDING) + 通知原审批人
3. 原审批人:
   - [同意撤回] → Quotation 状态 APPROVED → DRAFT
   - [拒绝撤回] → 保持 APPROVED
4. 原审批人离职/停用 → 自动路由到 SYSTEM_ADMIN 兜底
```

### 3.5 重新导入基础数据场景（DRAFT 阶段）

```
1. DRAFT 状态报价单详情页 [重新导入基础数据]
2. 上传新 Excel → 系统创建新 ProductDataPool 批次
3. 提示用户修改过的字段 vs 新数据的差异 → 选择保留/覆盖
4. 核价表重新计算
5. APPROVED 后不允许重新导入 → 走撤回流程
```

### 3.6 输出场景

```
APPROVED 后:
  - [导出 Excel] 按客户报价模板 customer_template_snapshot 生成多 Sheet xlsx（含 Excel 公式）
  - [发送给客户] 邮件附件
  - [复制报价单] 重置编号/日期/状态
```

---

## 4. 状态机

### 4.1 Quotation 状态

```
DRAFT ──提交审批──→ SUBMITTED
SUBMITTED ──审批通过──→ APPROVED
SUBMITTED ──审批退回──→ DRAFT
SUBMITTED ──销售代表撤回──→ DRAFT

APPROVED + 撤回请求 PENDING
  ├─ 审批人同意撤回 ──→ DRAFT
  └─ 审批人拒绝撤回 ──→ 保持 APPROVED

APPROVED ──发送客户──→ SENT
SENT ──标记接受──→ ACCEPTED
SENT ──标记拒绝──→ REJECTED
SENT / APPROVED ──定时任务──→ EXPIRED
```

### 4.2 模板状态（CostingTemplate / Template）

```
DRAFT ──发布──→ PUBLISHED ──归档──→ ARCHIVED
```

- DRAFT 可编辑/删除
- PUBLISHED 不可编辑（仅可创建新草稿迭代）
- 同 (customer_id, category_id) 同时只能一个 PUBLISHED（部分唯一索引）
- 同 category_id 默认核价模板唯一

### 4.3 撤回请求状态

```
PENDING ──审批人同意──→ APPROVED (Quotation 同步变 DRAFT)
PENDING ──审批人拒绝──→ REJECTED
```

### 4.4 核价表状态

```
LIVE (DRAFT 阶段，跟随基础数据实时更新)
  ──提交审批──→ SNAPSHOT (冻结)
```

---

## 5. 公共约定

### 5.1 响应包装

所有端点返回统一格式：

```json
{
  "code": 200,
  "message": "success",
  "data": <payload>
}
```

错误响应：
```json
{ "code": 400, "message": "<错误描述>" }
```

### 5.2 分页

`PageResult<T>`：
```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 156,
  "totalPages": 8
}
```

请求参数：`?page=0&size=20`

### 5.3 标识符

- 主键统一 UUID
- `import_batch_id`：同一次导入的批次号，关联 ProductDataPool / ImportRecord / Quotation
- `series_id` / `template_series_id`：模板系列 ID，跨版本追踪
- `comparison_tag`：业务标签编码（如 `MATERIAL_COST_AG`），跨模板字段配对

### 5.4 文件上传

`Content-Type: multipart/form-data`，字段名通常为 `file`，部分端点同时接受表单参数（如 `customerId`）。

### 5.5 公式语法

- **变量路径**：`{HF_PART_NO}` `{元素BOM[元素='Ag'].组成含量(%)}` `{元素BOM[*].COMP_PCT}`
- **本表列引用**：`[列Key]` 如 `[C]*[D]`
- **聚合函数**：`SUM` `AVG` `MAX` `MIN` `COUNT`
- **跨表查找**：`LOOKUP(sheet, match, field)`
- **条件**：`IF(条件, 真值, 假值)`
- **Excel 视图占位符**：`{row}` 替换为当前数据行实际行号（从 2 开始）

---

## 6. API 接口

### 6.1 认证 / 用户

#### `POST /api/cpq/auth/login` — 登录
请求：
```json
{ "username": "admin", "password": "******" }
```
响应：用户信息 + 设置 HttpOnly Session Cookie

#### `POST /api/cpq/auth/logout` — 登出
#### `POST /api/cpq/auth/change-password` — 修改密码
```json
{ "oldPassword": "***", "newPassword": "***" }
```
#### `POST /api/cpq/auth/forgot-password` — 找回密码
#### `POST /api/cpq/auth/reset-password` — 重置密码（带 token）
#### `GET /api/cpq/auth/me` — 当前用户信息

---

### 6.2 客户管理

| Method | Path | 说明 | 角色 |
|---|---|---|---|
| GET | `/customers?page&size&keyword&level&status` | 客户列表 | ALL |
| GET | `/customers/{id}` | 客户详情 | ALL |
| POST | `/customers` | 创建客户 | SALES_REP+ |
| PUT | `/customers/{id}` | 更新客户 | SALES_REP+ |
| DELETE | `/customers/{id}` | 删除客户（有进行中报价单时禁止） | SALES_MANAGER+ |
| GET | `/customers/{customerId}/contacts` | 联系人列表 | ALL |
| POST | `/customers/{customerId}/contacts` | 新增联系人 | SALES_REP+ |

**客户料号关联**：
| Method | Path | 说明 |
|---|---|---|
| GET | `/customers/{customerId}/material-mappings` | 列出客户料号映射 |
| POST | `/customers/{customerId}/material-mappings` | 新增映射 |
| DELETE | `/customers/{customerId}/material-mappings/{id}` | 删除 |
| POST | `/customers/{customerId}/material-mappings/import` | Excel 批量导入 |
| GET | `/customers/{customerId}/material-mappings/match?partNo=xxx` | 单一料号匹配查询 |

---

### 6.3 产品管理

#### 产品
| Method | Path | 说明 |
|---|---|---|
| GET | `/products?page&size&category&categoryId&status&keyword` | 产品列表（v4 支持 categoryId 筛选） |
| POST | `/products` | 创建产品（v4：传 `categoryId` 优先于 `category` 字符串） |
| PUT | `/products/{id}` | 更新 |
| DELETE | `/products/{id}` | 软删除（INACTIVE） |
| POST | `/products/import` | Excel 批量导入（multipart） |

`Product` 字段：`name / partNo / category / categoryId / categoryName / specification / drawingNo / dimension / material / status / tags / externalId`

#### 工序
| Method | Path | 说明 |
|---|---|---|
| GET | `/processes` | 工序列表（27 条种子） |
| GET | `/products/{productId}/processes` | 产品工序配置 |
| PUT | `/products/{productId}/processes` | 更新工序配置 |

#### 生产料号（v3）
| Method | Path | 说明 |
|---|---|---|
| GET | `/internal-materials?page&size&keyword&statusCode` | 列表 |
| POST | `/internal-materials` | 创建 |
| PUT | `/internal-materials/{id}` | 更新 |
| DELETE | `/internal-materials/{id}` | 删除（被映射引用时禁止） |
| POST | `/internal-materials/import` | Excel 批量导入 |

#### 产品分类（v4 新增）
| Method | Path | 说明 |
|---|---|---|
| GET | `/product-categories?status` | 列表（树形） |
| GET | `/product-categories/{id}` | 详情 |
| POST | `/product-categories` | 新增（`code` `name` `parentId` `sortOrder` `status`） |
| PUT | `/product-categories/{id}` | 更新（含循环引用检测） |
| DELETE | `/product-categories/{id}` | 删除（有子分类或关联产品时禁止） |

---

### 6.4 基础数据配置 (v4)

#### Sheet 配置
| Method | Path | 说明 |
|---|---|---|
| GET | `/basic-data-config/sheets?status` | Sheet 列表 |
| GET | `/basic-data-config/sheets/{id}` | Sheet 详情 |
| POST | `/basic-data-config/sheets` | 新增 |
| PUT | `/basic-data-config/sheets/{id}` | 更新 |
| DELETE | `/basic-data-config/sheets/{id}` | 删除（有子 Sheet 时禁止） |

`BasicDataConfig` 字段：
```
sheetName, sheetIndex, headerRowIndex, dataStartRowIndex,
description, parentConfigId, joinColumns: ["HF_PART_NO", ...],
sortOrder, status
```

#### 列属性
| Method | Path | 说明 |
|---|---|---|
| GET | `/basic-data-config/attributes?sheetId={uuid}&status` | 列属性列表 |
| POST | `/basic-data-config/attributes` | 新增 |
| PUT | `/basic-data-config/attributes/{id}` | 更新 |
| DELETE | `/basic-data-config/attributes/{id}` | 禁用（不真删） |

`BasicDataAttribute` 字段：
```
configId, columnLetter (Excel 列字母), columnTitle, variableCode (UNIQUE),
variableLabel, dataType: IDENTIFIER | VALUE, status, sortOrder
```

#### 衍生字段
| Method | Path | 说明 |
|---|---|---|
| GET | `/basic-data-config/derived?sheetId={uuid}&status` | 衍生字段列表 |
| POST | `/basic-data-config/derived` | 新增 |
| PUT | `/basic-data-config/derived/{id}` | 更新 |
| DELETE | `/basic-data-config/derived/{id}` | 禁用 |

`DerivedAttribute` 字段：
```
hostSheetId, variableCode, variableLabel, dataType,
computationType: LOOKUP | EXPRESSION | AGGREGATE,
computation (JSON 计算定义), status, sortOrder
```

`computation` 示例：

**EXPRESSION**：
```json
{ "type": "EXPRESSION", "formula": "[组成含量(%)] * [元素单价] / 100" }
```

**LOOKUP**：
```json
{
  "type": "LOOKUP",
  "source_sheet": "元素单价",
  "match_conditions": [
    { "source_field": "单个元素名称", "match_to": "当前行.ELEMENT" }
  ],
  "take_field": "升水价"
}
```

**AGGREGATE**：
```json
{ "type": "AGGREGATE", "function": "SUM", "source_path": "元素BOM[*].单元素材料成本" }
```

#### Excel 解析
| Method | Path | 说明 |
|---|---|---|
| POST | `/basic-data-config/parse-excel` | 上传 Excel，返回所有 Sheet + 列结构（不持久化） |

请求：multipart `file`，返回：
```json
{
  "sheets": [
    {
      "sheetIndex": 0,
      "sheetName": "宏丰料号",
      "headerRowIndex": 1,
      "columns": [
        { "columnLetter": "A", "columnIndex": 0, "columnTitle": "宏丰料号" },
        ...
      ]
    }
  ]
}
```

---

### 6.5 业务标签字典 (v4)

| Method | Path | 说明 |
|---|---|---|
| GET | `/comparison-tags?status` | 标签列表（按 group 排序） |
| GET | `/comparison-tags/{id}` | 详情 |
| POST | `/comparison-tags` | 新增（自定义标签） |
| PUT | `/comparison-tags/{id}` | 更新（内置标签不可改 code） |
| DELETE | `/comparison-tags/{id}` | 删除（内置标签禁止删除，仅可禁用） |

**内置标签（11 个）**：
- 材料成本维度：`MATERIAL_COST_AG` `MATERIAL_COST_CU` `MATERIAL_COST_TOTAL`
- 加工费维度：`PROCESSING_COST` `LABOR_COST` `SETUP_COST`
- 其他费用：`OVERHEAD_COST` `PACKAGING_COST` `CUSTOM_COST`
- 汇总：`UNIT_TOTAL_COST` `TOTAL`

---

### 6.6 核价模板 (v4)

| Method | Path | 说明 |
|---|---|---|
| GET | `/costing-templates?categoryId&status` | 列表 |
| GET | `/costing-templates/{id}` | 详情 |
| POST | `/costing-templates` | 新建（`name` `categoryId` `isDefault` `description`） |
| PUT | `/costing-templates/{id}` | 更新 columns（仅 DRAFT 可编辑） |
| DELETE | `/costing-templates/{id}` | 删除（仅 DRAFT） |
| POST | `/costing-templates/{id}/publish` | 发布（自动递增版本） |
| POST | `/costing-templates/{id}/archive` | 归档 |

`columns` JSONB 结构：
```json
[
  {
    "col_key": "A",
    "title": "宏丰料号",
    "source_type": "VARIABLE",
    "variable_path": "{HF_PART_NO}"
  },
  {
    "col_key": "E",
    "title": "Ag材料成本",
    "source_type": "FORMULA",
    "formula": "[C]*[D]*{UNIT_WEIGHT}/100",
    "comparison_tag": "MATERIAL_COST_AG"
  }
]
```

**约束**：
- 同 `categoryId` 仅一个 `isDefault=true`（部分唯一索引）
- 仅 DRAFT 可编辑/删除

---

### 6.7 客户报价模板

| Method | Path | 说明 |
|---|---|---|
| GET | `/templates?page&size&category&customerId&categoryId&status&keyword` | 列表（v4 支持 customerId/categoryId 筛选） |
| GET | `/templates/{id}` | 详情（含 components 快照） |
| POST | `/templates` | 新建（`name` `customerId` `categoryId` `description`） |
| PUT | `/templates/{id}` | 更新（DRAFT） |
| DELETE | `/templates/{id}` | 删除 |
| POST | `/templates/{id}/publish` | 发布（含组件快照、版本递增） |
| POST | `/templates/{id}/archive?force=true` | 归档（in-progress 报价单时禁止） |
| POST | `/templates/{id}/new-draft` | 基于已发布版本新建 DRAFT |
| GET | `/templates/series/{templateSeriesId}/versions` | 模板系列所有版本 |

#### Template 关联组件
| Method | Path | 说明 |
|---|---|---|
| GET | `/templates/{templateId}/components` | 列出 |
| POST | `/templates/{templateId}/components` | 添加组件到模板 |
| PUT | `/templates/{templateId}/components/{id}` | 更新（preset_rows / formula_assignments） |
| DELETE | `/templates/{templateId}/components/{id}` | 移除 |

#### Excel 视图配置
| Method | Path | 说明 |
|---|---|---|
| GET | `/templates/{id}/excel-view-config` | 取 excelViewConfig |
| PUT | `/templates/{id}/excel-view-config` | 保存 excelViewConfig |
| POST | `/templates/{id}/excel-view-config/parse-header` | 上传客户 Excel 解析表头 |

`excelViewConfig` JSONB 结构（多 Sheet 示例）：
```json
{
  "import_entry_sheet": "报价明细",
  "sheets": [
    {
      "sheet_name": "报价明细",
      "sort_order": 1,
      "header_row_index": 1,
      "columns": [
        { "col_key": "A", "title": "Schneider Part Number", "source_type": "PRODUCT_ATTRIBUTE", "source_name": "产品名称" },
        { "col_key": "D", "title": "Ag%", "source_type": "COMPONENT_FIELD", "component_code": "touLiao", "field_name": "含量", "row_index": 0 },
        { "col_key": "F", "title": "Ag cost", "source_type": "EXCEL_FORMULA", "formula": "=D{row}*E{row}*0.5/100", "comparison_tag": "MATERIAL_COST_AG" },
        { "col_key": "J", "title": "Unit", "source_type": "FIXED_VALUE", "value": "USD/Kg" }
      ]
    }
  ]
}
```

#### 产品 - 模板绑定
| Method | Path | 说明 |
|---|---|---|
| GET | `/products/{productId}/template-bindings` | 列表 |
| POST | `/products/{productId}/template-bindings` | 绑定（含 process_ids） |
| DELETE | `/products/{productId}/template-bindings/{id}` | 解绑 |

---

### 6.8 报价单核心

| Method | Path | 说明 |
|---|---|---|
| GET | `/quotations?page&size&status&keyword&assignedToMe` | 报价单列表 |
| GET | `/quotations/{id}` | 详情（含 lineItems / approvalHistory） |
| POST | `/quotations` | 创建（手动） |
| PUT | `/quotations/{id}/draft` | 保存草稿（line items + components） |
| POST | `/quotations/{id}/calculate-discount` | 计算折扣 |
| POST | `/quotations/{id}/submit` | 提交审批 → SUBMITTED |
| POST | `/quotations/{id}/approve` | 审批通过（仅 assignedApprover/SYSTEM_ADMIN） |
| POST | `/quotations/{id}/reject` | 审批退回（必填 reason） |
| POST | `/quotations/{id}/withdraw` | 销售代表撤回 SUBMITTED → DRAFT |
| POST | `/quotations/{id}/copy` | 复制报价单 |
| DELETE | `/quotations/{id}` | 删除 DRAFT |
| POST | `/quotations/{id}/export/html` | 导出 HTML 预览 |
| POST | `/quotations/{id}/export/pdf` | 导出 PDF（v1 实现：返回浏览器可打印的 HTML，Content-Type 为 `text/html`；客户端通过浏览器打印转 PDF）|
| POST | `/quotations/{id}/export/excel` | 导出 Excel（`.xlsx`）|
| POST | `/quotations/{id}/send` | 发送邮件给客户 → SENT — 请求体 `{"to":"<email>","subject":"...","body":"..."}`（`to` 为单个邮箱字符串）|
| PUT | `/quotations/{id}/extend` | 延期 expiry_date — 请求体 `{"newExpiryDate":"yyyy-MM-dd"}`（也接受 `expiryDate` 别名）|
| POST | `/quotations/{id}/accept` | 标记客户接受 → ACCEPTED（销售代表本人） |
| POST | `/quotations/{id}/reject-by-customer` | 标记客户拒绝 → REJECTED |
| GET | `/quotations/{id}/excel-view` | 取 Excel 视图数据 |
| PUT | `/quotations/{id}/excel-view` | 更新 Excel 视图单元格（双向同步到 lineItem） |
| GET | `/quotations/{id}/export-excel-view` | 导出 Excel 视图（含公式） |

**Quotation 关键字段（v4 扩展）**：
```
quotationNumber, customerId, customerTemplateId, importBatchId,
status, totalAmount, originalAmount, systemDiscountRate, finalDiscountRate,
expiryDate, paymentTerms, deliveryCycle, taxRate, taxAmount,
assignedApproverId, snapshot_customer_*
```

---

### 6.9 报价单导入 (v4)

#### 基础数据驱动导入（v4 主流程）

| Method | Path | 说明 |
|---|---|---|
| POST | `/quotations/import-basic-data` | 上传基础数据 Excel，返回预览 |
| POST | `/quotations/confirm-basic-data-import` | 确认导入，事务化生成报价单 |
| POST | `/quotations/{id}/reimport-basic-data` | DRAFT 重新导入 |

**预览请求**（multipart）：
- `customerId`: UUID
- `file`: Excel 文件

**预览响应**：
```json
{
  "importBatchId": "uuid",
  "customerId": "uuid",
  "costingTemplateId": "uuid",
  "customerTemplateId": "uuid",
  "costingRows": [{ "hf_part_no": "...", "cells": { "A": "..." } }],
  "productPreview": [{ "HF_PART_NO": "...", "UNIT_WEIGHT": 0.4, "childCounts": { "来料BOM": 2 } }],
  "errors": [],
  "warnings": [],
  "totalProducts": 5,
  "matchedCostingTemplateOptions": ["uuid"],
  "matchedCustomerTemplateOptions": ["uuid"],
  "originalFileName": "data.xlsx",
  "tempFilePath": "data/imports/{customerId}/{yyyy-MM}/{batch}-data.xlsx"
}
```

**确认请求**：
```json
{
  "importBatchId": "uuid",
  "customerId": "uuid",
  "costingTemplateId": "uuid",
  "customerTemplateId": "uuid",
  "tempFilePath": "...",
  "originalFileName": "...",
  "quotationId": null
}
```

**确认响应**：
```json
{ "quotationId": "uuid", "quotationNumber": "QT-20260423-0123" }
```

**模板匹配规则**：
- 核价模板：`category_id = ? AND status = 'PUBLISHED'`
- 客户报价模板：先 `customer_id = ? AND category_id = ?`，无则回退 `customer_id IS NULL AND category_id = ?`
- 0 条 → 阻止；1 条 → 自动选中；N 条 → 由用户选择

#### 客户 Excel 导入（v3 保留）
| Method | Path | 说明 |
|---|---|---|
| POST | `/imports/import-excel` | 客户 Excel 预览（按模板 excel_view_config 解析） |
| POST | `/imports/confirm-import` | 确认导入 |
| GET | `/imports/records?page&size&customerId&importStatus` | 导入历史 |
| GET | `/imports/records/{id}` | 单条记录详情 |
| GET | `/imports/records/{id}/download` | 下载原始 Excel |

#### 客户 Excel 模板（v3）
| Method | Path | 说明 |
|---|---|---|
| GET | `/excel-templates?customerId` | 列表 |
| POST | `/excel-templates` | 新建 |
| PUT | `/excel-templates/{id}` | 更新 |
| DELETE | `/excel-templates/{id}` | 删除 |

#### 导入映射模板（v3）
| Method | Path | 说明 |
|---|---|---|
| GET | `/import-mappings?templateId` | 列表 |
| POST | `/import-mappings` | 新建 |

---

### 6.10 核价表 + 比对视图 (v4)

| Method | Path | 说明 |
|---|---|---|
| GET | `/quotations/{id}/costing-sheet` | 取核价表（含模板列定义 + rows） |
| GET | `/quotations/{id}/comparison` | 取比对视图（基础字段 + 业务标签分组 + 毛利） |

**核价表响应**：
```json
{
  "id": "uuid",
  "quotationId": "uuid",
  "costingTemplateId": "uuid",
  "costingTemplateName": "银点类核价模板",
  "columns": [{ "col_key": "A", "title": "宏丰料号", "source_type": "VARIABLE", ... }],
  "rows": [{ "hf_part_no": "...", "cells": { "A": "...", "B": ... } }],
  "totalCost": 238.00,
  "status": "LIVE"
}
```

**比对视图响应**：
```json
{
  "basicFieldDiffs": [
    { "variableCode": "AG_PRICE", "variableLabel": "Ag单价", "costingValue": 400, "quotationValue": 420, "diffStatus": "MODIFIED" }
  ],
  "tagGroups": [
    {
      "groupName": "材料成本维度",
      "tags": [
        { "tag": "MATERIAL_COST_AG", "tagLabel": "Ag材料成本", "costingValue": 150, "quotationValue": 157.5, "delta": 7.5, "deltaPct": "5.00%" }
      ]
    }
  ],
  "summary": {
    "costingTotal": 238.00,
    "quotationTotal": 273.45,
    "profit": 35.45,
    "profitRate": "12.97%",
    "modifiedFieldsCount": 3
  }
}
```

---

### 6.11 审批撤回 (v4)

| Method | Path | 说明 |
|---|---|---|
| GET | `/quotations/{id}/withdraw-requests` | 历史撤回记录 |
| GET | `/quotations/{id}/withdraw-requests/pending` | 当前 PENDING 撤回请求 |
| POST | `/quotations/{id}/withdraw-request` | 销售代表请求撤回（必填 `reason`） |
| POST | `/quotations/{id}/withdraw/approve` | 原审批人/管理员同意撤回（含可选 `note`） |
| POST | `/quotations/{id}/withdraw/reject` | 原审批人/管理员拒绝撤回 |

**约束**：
- 仅 APPROVED 状态可发起撤回
- 同一报价单同时只能有一个 PENDING 撤回请求（部分唯一索引）
- 撤回通过同时写入 QuotationApproval(action=WITHDRAWN) 审批历史
- 原审批人离职/停用 → 走 SYSTEM_ADMIN 兜底

---

### 6.12 定价 / 数据源 / 组件

#### 定价策略
| Method | Path | 说明 |
|---|---|---|
| GET | `/pricing-strategies?customerId` | 列表 |
| POST | `/pricing-strategies` | 新建 |
| PUT | `/pricing-strategies/{id}` | 更新 |
| DELETE | `/pricing-strategies/{id}` | 删除 |
| GET | `/pricing-strategies/{strategyId}/rules` | 规则列表 |
| POST | `/pricing-strategies/{strategyId}/rules` | 新增规则 |

#### 数据源
| Method | Path | 说明 |
|---|---|---|
| GET | `/datasources?type` | SQL / API 数据源 |
| POST | `/datasources` | 新建（API headers AES-256-GCM 加密存储） |
| POST | `/datasources/{id}/test` | 测试查询 |
| POST | `/datasources/{id}/execute` | 执行（带参数） |

#### 组件
| Method | Path | 说明 |
|---|---|---|
| GET | `/components?page&size&componentType&keyword` | 组件列表 |
| GET | `/components/{id}` | 详情 |
| POST | `/components` | 新建（自动 COMP-XXXX 编码） |
| PUT | `/components/{id}` | 更新 fields/formulas（DFS 检测循环引用） |
| DELETE | `/components/{id}` | 删除 |

#### 组件目录
| Method | Path | 说明 |
|---|---|---|
| GET | `/component-directories` | 目录列表 |
| POST | `/component-directories` | 新增 |

---

### 6.13 系统管理

#### 用户
| Method | Path | 说明 |
|---|---|---|
| GET | `/users?role&status&keyword` | 用户列表 |
| POST | `/users` | 创建（首次密码 90 天有效期） |
| PUT | `/users/{id}` | 更新 |
| PATCH | `/users/{id}` | 部分更新（status / role） |
| POST | `/users/{id}/reset-password` | 重置密码（管理员） |

#### 区域 / 部门
| Method | Path | 说明 |
|---|---|---|
| GET | `/regions` | 区域列表 |
| POST | `/regions` | 新增 |
| PATCH | `/regions/{id}` | 部分更新 |
| GET | `/departments` | 部门树 |
| POST | `/departments` | 新增（含 parent_id 树形） |

#### 审批规则
| Method | Path | 说明 |
|---|---|---|
| GET | `/approval-rules` | 规则列表 |
| POST | `/approval-rules` | 新建（FIXED / DYNAMIC） |
| PUT | `/approval-rules/{id}` | 更新 |
| DELETE | `/approval-rules/{id}` | 删除 |

#### 通知
| Method | Path | 说明 |
|---|---|---|
| GET | `/notifications?page&size` | 列表 |
| GET | `/notifications/unread-count` | 未读数 |
| POST | `/notifications/mark-all-read` | 全部已读 |
| POST | `/notifications/{id}/mark-read` | 单条已读 |

#### 操作日志
| Method | Path | 说明 |
|---|---|---|
| GET | `/operation-logs?page&size&module&userId&action` | 操作日志查询 |

#### 健康检查
| Method | Path | 说明 |
|---|---|---|
| GET | `/health` | 服务健康状态 |

---

## 7. 关键数据结构示例

### 7.1 ProductDataPool.dataTree 示例

一份基础数据 Excel 解析后的产品数据树结构：

```json
{
  "HF_PART_NO": "3120012574",
  "UNIT_WEIGHT": 0.4,
  "来料BOM": [
    {
      "INPUT_NAME": "Ag铆钉",
      "SEQ_NO": 1,
      "GROSS_WEIGHT": 0.5,
      "LOSS_PCT": 5,
      "元素BOM": [
        { "ELEMENT": "Ag", "COMP_PCT": 75, "元素单价": 400, "单元素材料成本": 150 },
        { "ELEMENT": "Ni", "COMP_PCT": 25, "元素单价": 180, "单元素材料成本": 22.5 }
      ]
    }
  ],
  "成品固定加工费": [{ "值": 5 }],
  "总材料成本": 172.5
}
```

### 7.2 变量路径解析示例

| 路径 | 含义 | 返回类型 |
|---|---|---|
| `{HF_PART_NO}` | 根字段 | scalar |
| `{UNIT_WEIGHT}` | 根字段 | scalar |
| `{来料BOM[*]}` | 整个来料数组 | List<Map> |
| `{来料BOM[*].INPUT_NAME}` | 所有来料名称 | List |
| `{来料BOM[INPUT_NAME='Ag铆钉']}` | 条件筛选 | Map |
| `{来料BOM[INPUT_NAME='Ag铆钉'].元素BOM[*].COMP_PCT}` | 嵌套+聚合 | List |
| `{元素BOM[元素='Ag'].组成含量(%)}` | 中文路径筛选 | scalar |

### 7.3 QuotationLineComponentData.row_data 示例

```json
[
  {
    "row_index": 0,
    "group_key": "Ag铆钉",
    "物料": "Ag",
    "含量": 75,
    "单价": 400,
    "金额": 150,
    "_modified_fields": ["单价"],
    "_original_values": { "单价": 380 }
  }
]
```

### 7.4 ImportRecord 字段（v4）

```
quotationId, customerId,
costingTemplateId, customerTemplateId,
costingTemplateSnapshot, customerTemplateSnapshot,
importBatchId,
originalFileName, originalFilePath,
totalRows, successRows, matchedRows, unmatchedRows,
importStatus: SUCCESS | PARTIAL | FAILED,
errorDetail, importedBy, createdAt
```

---

## 8. 错误码约定

| Code | 含义 |
|---|---|
| 200 | 成功 |
| 400 | 参数错误 / 业务校验失败 |
| 401 | 未登录 / Session 失效 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

业务错误均通过 `BusinessException(code, message)` 抛出，统一由 `GlobalExceptionMapper` 包装返回。

---

## 9. 性能与约束

| 项 | 上限 |
|---|---|
| 基础数据单次导入产品数 | 500 |
| 导入预览响应时间 | < 3s |
| 料号匹配查询 | < 200ms |
| 生产料号 Excel 导入单次 | 5000 条 |
| Excel 视图渲染 | < 1s（50 产品以内） |
| Excel 导出 | < 3s（50 产品以内） |
| 核价表计算 | < 500ms（10 产品 × 50 列） |
| 双向同步延迟 | < 200ms |
| 单次公式求值 | < 10ms |
| 全表重算 | < 500ms |
| 原始 Excel 服务端保留 | 12 个月 |
| ImportRecord 保留 | 永久 |

---

## 10. 文件路径约定

| 内容 | 路径 |
|---|---|
| 上传的基础数据 Excel | `data/imports/{customerId}/{yyyy-MM}/{uuid}.xlsx` |
| 客户 Excel 模板样例 | `data/template/` |

---

## 附录：术语表

| 术语 | 英文 | 说明 |
|---|---|---|
| 基础数据 | Basic Data | 内部维护的 Excel 数据，作为单一数据源 |
| 产品数据池 | Product Data Pool | 导入解析后的中间数据层，按批次存储 |
| 核价模板 | Costing Template | 内部成本核算模板，按产品分类绑定 |
| 客户报价模板 | Customer Template | 对外报价模板，按客户+分类绑定，含产品卡片+Excel 视图 |
| 核价表 | Costing Sheet | 核价模板 + 产品数据池生成的内部成本表 |
| 衍生字段 | Derived Attribute | 基础数据层的预计算字段（LOOKUP/EXPRESSION/AGGREGATE） |
| 业务标签 | Comparison Tag | 跨模板字段配对用的业务含义标签 |
| 行展开 | Row Expansion | 组件按数据源自动展开多行（FLAT/GROUPED） |
| 批次 | Import Batch | 一次导入的产品数据池记录标识 |
| 宏丰料号 | HF Part No | 公司内部产品料号，作为产品数据池的根键 |
| 四视图 | Four Views | 产品卡片视图 / Excel 视图 / 核价表视图 / 比对视图 |

---

**文档结束** | 详细设计请参考 `docs/superpowers/specs/2026-04-23-excel-import-design-v4.md`
