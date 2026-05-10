# CPQ 核价 + 客户报价 + 基础数据驱动设计 v4.0

> **日期**: 2026-04-23
> **基于**: v3.0 全面重构
> **状态**: 设计完成，待实施
> **核心变更**: 引入基础数据驱动 + 核价模板 + 四视图体系。一次导入基础数据生成核价表 + 客户报价表，支持成本分析与对比审批

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [核心设计决策](#2-核心设计决策)
3. [业务流程总览](#3-业务流程总览)
4. [数据模型](#4-数据模型)
5. [基础数据配置](#5-基础数据配置)
6. [产品分类与料号管理](#6-产品分类与料号管理)
7. [模板体系](#7-模板体系)
8. [产品数据池](#8-产品数据池)
9. [导入流程](#9-导入流程)
10. [四视图体系](#10-四视图体系)
11. [比对环节](#11-比对环节)
12. [审批流程](#12-审批流程)
13. [Excel 输出](#13-excel-输出)
14. [公式执行引擎](#14-公式执行引擎)
15. [版本管理](#15-版本管理)
16. [管理界面](#16-管理界面)
17. [菜单与权限](#17-菜单与权限)
18. [API 设计](#18-api-设计)
19. [非功能需求](#19-非功能需求)
20. [版本演进摘要](#20-版本演进摘要)

---

## 1. 背景与目标

### 1.1 问题

1. 销售代表创建报价单时手动录入大量字段（单个产品 40+ 列），效率低下
2. 系统缺乏成本核算维度，销售缺乏报价决策依据，导致多次送审和修改
3. 客户 Excel 格式多样，系统与客户 Excel 之间的映射困难
4. 没有统一的基础数据层，同一份数据需要在多处重复配置

### 1.2 目标

- 以**基础数据 Excel** 作为单一数据源，一次导入驱动核价表和客户报价表
- 销售代表在报价阶段能看到**完整的成本构成**，做到"送审前心中有数"
- 支持**客户 Excel 原生格式**的导出（含 Excel 公式）
- 数据配置统一到一个地方，降低维护成本
- 产品卡片视图 / Excel 视图 / 核价表视图 / 比对视图 **四视图协同**

### 1.3 定位

- 系统仅供**内部销售团队**使用，客户不登录
- 客户只看到最终导出的 Excel 报价单
- 核价表、比对差异等内部数据对客户不可见

---

## 2. 核心设计决策

| 决策项 | 选择 | 理由 |
|-------|------|------|
| 整体方案 | 基础数据驱动 + 模板映射 | 一次导入，多视图输出，数据一致性由中间层保证 |
| 基础数据格式 | 内部统一格式 Excel，多 Sheet | 数据稳定，按 Sheet 分组管理属性 |
| 数据层级 | Sheet 间通过关联列形成层级关系 | 支持一对多数据（如产品→投料→元素） |
| 衍生字段 | 基础数据配置阶段定义（LOOKUP/EXPRESSION/AGGREGATE） | 复杂逻辑下沉到基础数据层 |
| 核价模板 | 独立模板体系 | 按产品分类绑定，字段直接引用产品数据池 |
| 客户报价模板 | 合并产品卡片 + Excel 视图 | 两种视图共享配置，数据一致性天然保证 |
| 产品卡片模板 | 合并到客户报价模板 | 产品卡片是客户报价的一种视图形式 |
| 行展开 | 支持一维（FLAT）和二维分组（GROUPED） | 按投料分组展示元素等场景 |
| 公式引擎 | 后端 JEXL 扩展 + 前端 Excel 原生 | 各视图各自合适的引擎 |
| 业务标签 | 跨模板字段配对 | 用于比对视图识别对应字段 |
| 比对视图 | 基础数据字段 + 公式计算双 Tab | 审批前自检 + 审批参考 |
| 模板匹配 | 按"客户 + 产品分类"组合 | 自动匹配，多个时让用户选 |
| 版本管理 | 沿用现有快照机制 | 一致性与追溯性 |
| 审批撤回 | APPROVED → DRAFT（需原审批人确认） | 应对执行前价格波动 |

---

## 3. 业务流程总览

### 3.1 全景流程

```
【配置阶段】（一次性，销售经理/系统管理员）
  ├─ 维护产品分类、生产料号、客户料号关联
  ├─ 维护基础数据配置（Sheet / 属性 / 衍生字段 / 关联关系）
  ├─ 维护业务标签字典
  ├─ 配置核价模板（按产品分类）
  └─ 配置客户报价模板（按客户 + 产品分类）

【导入阶段】（销售代表）
  选客户 → 上传基础数据 Excel → 系统自动:
    ├─ 解析构建产品数据池（含衍生字段计算）
    ├─ 匹配核价模板 + 客户报价模板
    ├─ 生成 DRAFT 报价单 + 核价表 + 导入记录
    └─ 跳转报价生成器

【调整阶段】（销售代表）
  四视图切换调整数据:
    ├─ 产品卡片视图
    ├─ Excel 视图（客户格式）
    ├─ 核价表视图（成本基线，只读）
    └─ 比对视图（报价 vs 核价）
  料号匹配（绿/红边框）+ 折扣调整 + 商务条款

【审批阶段】（销售经理）
  审批摘要卡片展示毛利指标
  通过 / 退回 / 撤回（APPROVED 后特殊流程）

【输出阶段】（销售代表）
  按客户模板导出多 Sheet Excel（含公式）→ 邮件发送给客户
```

### 3.2 数据流向

```
基础数据 Excel
      │
      ▼ 按 BasicDataConfig 解析
      │
ProductDataPool（按批次存储）
      │
      ├─ 衍生字段计算 → 追加到 data_tree
      │
      ▼ 两个模板引用变量路径
      │
  ┌───┴────────────────────────────────────┐
  │                                        │
  ▼                                        ▼
核价模板 → CostingSheet              客户报价模板
                                    ├── 产品卡片视图
                                    └── Excel 视图（多 Sheet）
                                          │
                                          ▼
                                    导出 .xlsx（含公式）
```

---

## 4. 数据模型

### 4.1 实体分层

```
层1：基础字典
  Customer / ProductCategory / Product / InternalMaterial / CustomerMaterialMapping

层2：基础数据配置
  BasicDataConfig / BasicDataAttribute / DerivedAttribute / ComparisonTag

层3：模板
  CostingTemplate / CustomerTemplate

层4：运行时数据
  ProductDataPool / ImportRecord / Quotation / QuotationLineItem /
  QuotationLineComponentData / CostingSheet / QuotationApproval /
  QuotationWithdrawRequest

层5：沿用现有
  PricingStrategy / PricingRule / ApprovalRule / User / Region / Department /
  OperationLog / Notification
```

### 4.2 Product 表变更

```
Product {
  ...existing fields...
  sku → part_no (重命名，客户产品料号)
  category (枚举) → category_id (FK → ProductCategory)
}
```

### 4.3 新增 InternalMaterial（我司生产料号）

```
InternalMaterial {
  id: UUID (PK)
  material_no: String (UNIQUE, NOT NULL, 我司料号)
  name: String (NOT NULL, 品名)
  specification: String (规格)
  size: String (尺寸)
  status_code: Enum [Y, N] (Y=可生产, N=停产/不可用)
  created_at / updated_at: Timestamp
}
```

### 4.4 新增 CustomerMaterialMapping（客户料号关联）

```
CustomerMaterialMapping {
  id: UUID (PK)
  customer_id: UUID (FK → Customer, NOT NULL)
  customer_part_no: String (NOT NULL, 客户产品料号)
  material_id: UUID (FK → InternalMaterial, NOT NULL)
  created_at: Timestamp
}

唯一约束: (customer_id, customer_part_no)
```

### 4.5 新增 ProductCategory（产品分类字典）

```
ProductCategory {
  id: UUID (PK)
  code: String (UNIQUE, NOT NULL, 如"SILVER_POINT")
  name: String (NOT NULL, 如"银点类")
  description: String
  parent_id: UUID (FK → self, nullable, 支持层级)
  status: Enum [ACTIVE, DISABLED]
  sort_order: Integer
  created_at / updated_at: Timestamp
}
```

### 4.6 新增 BasicDataConfig（基础数据 Sheet 配置）

```
BasicDataConfig {
  id: UUID (PK)
  sheet_name: String (NOT NULL, Sheet 名称，如"元素BOM")
  sheet_index: Integer (Sheet 序号)
  header_row_index: Integer (表头行号，默认1)
  data_start_row_index: Integer (数据起始行号，默认2)
  description: String
  parent_config_id: UUID (FK → self, nullable, 父级 Sheet)
  join_columns: JSONB (关联列变量编码列表, 如 ["HF_PART_NO", "SEQ_NO"])
  sort_order: Integer
  created_at / updated_at: Timestamp
}
```

### 4.7 新增 BasicDataAttribute（基础数据列属性）

```
BasicDataAttribute {
  id: UUID (PK)
  config_id: UUID (FK → BasicDataConfig, NOT NULL)
  column_letter: String (NOT NULL, Excel 列字母)
  column_title: String (NOT NULL, Excel 表头原始名称)
  variable_code: String (UNIQUE, NOT NULL, 系统变量编码)
  variable_label: String (NOT NULL, 变量中文标签)
  data_type: Enum [IDENTIFIER, VALUE] (标识字段 / 数值字段)
  status: Enum [ACTIVE, DISABLED] (禁用替代删除)
  sort_order: Integer
  created_at / updated_at: Timestamp
}
```

### 4.8 新增 DerivedAttribute（衍生字段）

```
DerivedAttribute {
  id: UUID (PK)
  host_sheet_id: UUID (FK → BasicDataConfig, 衍生字段归属的 Sheet)
  variable_code: String (UNIQUE, NOT NULL)
  variable_label: String (NOT NULL)
  data_type: Enum [IDENTIFIER, VALUE]
  computation_type: Enum [LOOKUP, EXPRESSION, AGGREGATE]
  computation: JSONB (计算定义，根据 computation_type 不同)
  status: Enum [ACTIVE, DISABLED]
  sort_order: Integer
  created_at / updated_at: Timestamp
}
```

**computation 结构示例：**

LOOKUP 模式：
```json
{
  "type": "LOOKUP",
  "source_sheet": "元素单价",
  "match_conditions": [
    {"source_field": "单个元素名称", "match_to": "当前行.ELEMENT"},
    {"source_field": "客户编号", "match_to": "上下文.客户编号"}
  ],
  "take_field": "升水价"
}
```

EXPRESSION 模式：
```json
{
  "type": "EXPRESSION",
  "formula": "[组成含量(%)] * [元素单价] * {UNIT_WEIGHT} / 100"
}
```

AGGREGATE 模式：
```json
{
  "type": "AGGREGATE",
  "function": "SUM",
  "source_path": "元素BOM[*].材料成本"
}
```

### 4.9 新增 ComparisonTag（业务标签字典）

```
ComparisonTag {
  id: UUID (PK)
  code: String (UNIQUE, NOT NULL, 如"MATERIAL_COST_AG")
  label: String (NOT NULL, 如"Ag材料成本")
  group_name: String (NOT NULL, 分组名称，如"材料成本维度")
  group_sort_order: Integer
  tag_sort_order: Integer
  is_builtin: Boolean (是否内置，内置不可删除)
  status: Enum [ACTIVE, DISABLED]
  description: Text
  created_at / updated_at: Timestamp
}
```

### 4.10 新增 CostingTemplate（核价模板）

```
CostingTemplate {
  id: UUID (PK)
  series_id: UUID (模板系列ID)
  name: String (NOT NULL)
  category_id: UUID (FK → ProductCategory, NOT NULL, 绑定产品分类)
  is_default: Boolean (该分类下是否默认模板)
  version: String (v1.0, v1.1...)
  status: Enum [DRAFT, PUBLISHED, ARCHIVED]
  description: Text
  columns: JSONB (列定义，含公式、业务标签 comparison_tag)
  referenced_variables: JSONB (引用的变量编码列表，冗余)
  created_by: UUID (FK → User)
  published_at: Timestamp
  created_at / updated_at: Timestamp
}

部分唯一索引: UNIQUE ON (category_id) WHERE is_default = true
```

**columns JSONB 结构：**
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

**列的 source_type：**
- `VARIABLE` — 引用产品数据池变量路径
- `FORMULA` — 公式计算（支持 PATH / COL / SUM / AVG / MAX / MIN / COUNT / IF / LOOKUP）

### 4.11 Template 表演进为 CustomerTemplate（客户报价模板）

```
CustomerTemplate {
  id: UUID (PK)
  series_id: UUID (系列ID)
  name: String (NOT NULL)
  customer_id: UUID (FK → Customer, nullable, NULL = 通用模板)
  category_id: UUID (FK → ProductCategory, NOT NULL)
  version: String
  status: Enum [DRAFT, PUBLISHED, ARCHIVED]
  description: Text
  usage_note: Text
  
  -- 产品卡片视图配置
  product_attributes: JSONB (产品属性定义)
  components_snapshot: JSONB (组件结构快照，含 BASIC_DATA 字段、row_expansion)
  subtotal_formula: JSONB (产品小计公式)
  
  -- Excel 视图配置
  excel_view_config: JSONB (多 Sheet 配置)
  
  referenced_variables: JSONB (冗余，便于影响分析)
  
  created_by: UUID (FK → User)
  published_at: Timestamp
  created_at / updated_at: Timestamp
}

唯一约束（部分唯一索引）:
  - 客户专属模板 (customer_id NOT NULL): 
    UNIQUE ON (customer_id, category_id) WHERE status='PUBLISHED'
    → 同客户同分类仅一个 PUBLISHED 版本
  - 通用模板 (customer_id IS NULL):
    UNIQUE ON (category_id) WHERE customer_id IS NULL AND status='PUBLISHED'
    → 同分类的通用模板仅一个 PUBLISHED 版本
```

**excel_view_config JSONB 结构：**
```json
{
  "import_entry_sheet": "报价明细",
  "sheets": [
    {
      "sheet_name": "报价明细",
      "sort_order": 1,
      "header_row_index": 1,
      "columns": [
        {
          "col_key": "A",
          "title": "Schneider Part Number",
          "source_type": "PRODUCT_ATTRIBUTE",
          "source_name": "产品名称"
        },
        {
          "col_key": "D",
          "title": "Ag%",
          "source_type": "COMPONENT_FIELD",
          "component_code": "touLiao",
          "field_name": "含量",
          "row_index": 0
        },
        {
          "col_key": "F",
          "title": "Ag cost",
          "source_type": "EXCEL_FORMULA",
          "formula": "=D{row}*E{row}*0.5/100",
          "comparison_tag": "MATERIAL_COST_AG"
        },
        {
          "col_key": "J",
          "title": "Unit",
          "source_type": "FIXED_VALUE",
          "value": "USD/Kg"
        }
      ]
    },
    {
      "sheet_name": "产品规格",
      "sort_order": 2,
      "header_row_index": 1,
      "columns": [...]
    }
  ]
}
```

**组件的 row_expansion 配置（在 components_snapshot 中）：**

一维模式：
```json
{
  "mode": "FLAT",
  "source_path": "元素BOM[*]"
}
```

二维分组模式：
```json
{
  "mode": "GROUPED",
  "source_path": "来料BOM[*].元素BOM[*]",
  "group_by": "来料BOM",
  "group_display_field": "投入料号名称"
}
```

**BASIC_DATA 字段绑定（相对路径）：**
```json
{
  "name": "含量",
  "field_type": "BASIC_DATA",
  "basic_data_binding": {
    "path_mode": "RELATIVE",
    "relative_path": "$.组成含量(%)",
    "level_count": 0
  }
}
```

### 4.12 新增 ProductDataPool（产品数据池）

```
ProductDataPool {
  id: UUID (PK)
  import_batch_id: UUID (NOT NULL, 同一次导入的批次号)
  hf_part_no: String (NOT NULL, 宏丰料号)
  data_tree: JSONB (NOT NULL, 完整的树形结构数据)
  created_at: Timestamp
}

索引: (import_batch_id), (hf_part_no)
```

**data_tree 结构示例：**
```json
{
  "HF_PART_NO": "3120012574",
  "UNIT_WEIGHT": 0.4,
  "来料BOM": [
    {
      "INPUT_MAT": "...",
      "INPUT_NAME": "Ag铆钉",
      "SEQ_NO": 1,
      "GROSS_WEIGHT": 0.5,
      "LOSS_PCT": 5,
      "元素BOM": [
        {"ELEMENT": "Ag", "COMP_PCT": 75, "元素单价": 400, "单元素材料成本": 150},
        {"ELEMENT": "Ni", "COMP_PCT": 25, "元素单价": 180, "单元素材料成本": 22.5}
      ],
      "来料固定加工费": [...]
    },
    {
      "INPUT_NAME": "H85",
      "SEQ_NO": 2,
      "元素BOM": [...]
    }
  ],
  "成品固定加工费": [{"值": 5}]
}
```

### 4.13 新增 ImportRecord（导入记录）

```
ImportRecord {
  id: UUID (PK)
  quotation_id: UUID (FK → Quotation, nullable)
  customer_id: UUID (FK → Customer, NOT NULL)
  costing_template_id: UUID (FK → CostingTemplate, NOT NULL)
  customer_template_id: UUID (FK → CustomerTemplate, NOT NULL)
  costing_template_snapshot: JSONB (模板快照)
  customer_template_snapshot: JSONB (模板快照)
  import_batch_id: UUID (对应 ProductDataPool 批次)
  original_file_name: String (NOT NULL)
  original_file_path: String (NOT NULL)
  total_products: Integer
  success_rows / matched_rows / unmatched_rows: Integer
  import_status: Enum [SUCCESS, PARTIAL, FAILED]
  error_detail: JSONB
  warning_detail: JSONB
  imported_by: UUID (FK → User)
  created_at: Timestamp
}
```

### 4.14 Quotation 表扩展

```
Quotation {
  ...existing fields...
  customer_template_id: UUID (FK → CustomerTemplate, 新增)
  import_batch_id: UUID (对应 ProductDataPool 批次, 新增, nullable)
}
```

### 4.15 QuotationLineItem 表扩展

```
QuotationLineItem {
  ...existing fields...
  customer_part_no: String (nullable, 客户料号值)
  excel_view_snapshot: JSONB (nullable, 多 Sheet 快照)
}

excel_view_snapshot 结构:
{
  "sheets": {
    "报价明细": {"A": "...", "B": "...", ...},
    "产品规格": {"A": "...", ...}
  }
}
```

### 4.16 QuotationLineComponentData.row_data 扩展

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
    "_original_values": {"单价": 380}
  }
]
```

- `group_key`: 分组模式下记录所属分组（可选）
- `_modified_fields` / `_original_values`: 用户修改追踪

### 4.17 新增 CostingSheet（核价表）

```
CostingSheet {
  id: UUID (PK)
  quotation_id: UUID (FK → Quotation, UNIQUE, 1:1)
  costing_template_id: UUID (FK → CostingTemplate)
  import_batch_id: UUID
  rows: JSONB (每行对应一个产品的核价数据)
  total_cost: Decimal (汇总总成本)
  status: Enum [LIVE, SNAPSHOT] (SUBMITTED 后变 SNAPSHOT)
  created_at / updated_at: Timestamp
}

rows 结构:
[
  {
    "hf_part_no": "3120012574",
    "cells": {"A": "3120012574", "B": "Ag铆钉", "C": 75, "D": 400, "E": 150, ...}
  }
]
```

### 4.18 新增 QuotationWithdrawRequest（撤回请求）

```
QuotationWithdrawRequest {
  id: UUID (PK)
  quotation_id: UUID (FK → Quotation)
  requested_by: UUID (FK → User)
  reason: Text (NOT NULL, 撤回原因)
  status: Enum [PENDING, APPROVED, REJECTED]
  decided_by: UUID (FK → User, nullable)
  decided_at: Timestamp
  created_at: Timestamp
}
```

### 4.19 实体关系总览

```
Customer
  ├── CustomerMaterialMapping → InternalMaterial
  └── Quotation
       ├── QuotationLineItem → QuotationLineComponentData
       ├── CostingSheet → CostingTemplate
       ├── ImportRecord → ProductDataPool (批次)
       ├── QuotationApproval
       └── QuotationWithdrawRequest

CustomerTemplate
  ├── customer_id → Customer (nullable，NULL=通用模板)
  └── category_id → ProductCategory

CostingTemplate
  └── category_id → ProductCategory

Product → ProductCategory

ProductCategory
  ├── CostingTemplate (1:N)
  └── CustomerTemplate (1:N)

BasicDataConfig
  ├── 自关联 (parent_config_id 形成 Sheet 层级)
  ├── BasicDataAttribute (1:N)
  └── DerivedAttribute (1:N)

Quotation
  ├── customer_template_id → CustomerTemplate (特定版本)
  └── import_batch_id → ProductDataPool 批次

ComparisonTag 独立，通过 code 被核价模板和客户报价模板的列配置引用
```

---

## 5. 基础数据配置

### 5.1 基础数据 Excel 特性

- 内部维护，格式统一稳定
- 多个 Sheet，每个 Sheet 代表一类数据（如元素BOM、来料BOM）
- Sheet 间通过关联列形成层级关系
- 无公式，纯数据

### 5.2 Sheet 关联层级示例

```
宏丰料号 (根)
  ├── 来料BOM（通过 HF_PART_NO 关联）
  │     ├── 元素BOM（通过 HF_PART_NO + INPUT_NAME 关联）
  │     ├── 来料固定加工费
  │     └── 来料年降
  ├── 成品固定加工费
  ├── 组成件BOM
  │     └── 组装加工费
  └── ...
```

### 5.3 配置流程

```
首次配置:
  1. 上传基础数据 Excel 模板
  2. 系统解析所有 Sheet 和列
  3. 用户对每个 Sheet 配置:
     - 描述、父级 Sheet、关联列
     - 每列: 变量编码、变量标签、字段类型（IDENTIFIER/VALUE）
  4. 定义衍生字段（可选）
  5. 保存

增量更新:
  1. 上传新版 Excel
  2. 系统对比识别:
     - 已有 Sheet + 新增列 → 标记"新增"
     - 新 Sheet → 标记"新增"
     - 移除的 Sheet/列 → 标记"已移除"（不自动删除）
  3. 用户配置新增部分
```

### 5.4 衍生字段三种模式

**LOOKUP**（跨 Sheet 取值）：
```
场景: 元素BOM 中每个元素的"元素单价"
  从 元素单价 Sheet 按元素名称查找升水价
```

**EXPRESSION**（本行字段运算）：
```
场景: "单元素材料成本"
  = 组成含量(%) × 元素单价 × 单重 / 100
```

**AGGREGATE**（跨行聚合）：
```
场景: "总材料成本"
  = SUM(元素BOM[*].单元素材料成本)
```

### 5.5 衍生字段依赖处理

- 保存时 DFS 检测循环依赖
- 导入计算时拓扑排序执行
- 支持衍生字段引用其他衍生字段

### 5.6 字段类型

| 类型 | 说明 | 用途 |
|------|------|------|
| IDENTIFIER | 标识字段（字符串类） | 用于筛选条件（如元素='Ag'） |
| VALUE | 数值/业务数据字段 | 参与公式计算 |

---

## 6. 产品分类与料号管理

### 6.1 产品分类管理

- 独立字典，支持层级（parent_id）
- 替代原 Product.category 枚举
- Product 必须关联产品分类
- 删除产品分类前检查是否有关联产品，有则阻止

### 6.2 生产料号管理

- InternalMaterial 实体
- 料号 / 品名 / 规格 / 尺寸 / 状态码（Y=可生产 / N=停产）
- 支持 Excel 批量导入

### 6.3 客户料号关联管理

- 入口：客户详情抽屉的"料号关联" tab
- 建立客户料号 ↔ 我司料号的映射
- 支持 Excel 批量导入

### 6.4 料号匹配与着色

**匹配逻辑：**
```sql
SELECT im.* FROM customer_material_mapping cmm
JOIN internal_material im ON cmm.material_id = im.id
WHERE cmm.customer_id = :customerId AND cmm.customer_part_no = :partNo
```

**着色规则：**

| 匹配结果 | 产品卡片边框 |
|---------|------------|
| 匹配成功 且 status_code = Y | 绿色边框 |
| 匹配成功 且 status_code = N | 红色边框 |
| 匹配失败 | 红色边框 |

**触发时机：**

料号匹配统一使用 `QuotationLineItem.customer_part_no` 字段查询。该字段在以下场景被写入或更新：

- **Excel 导入时**：从 `part_no_column` 对应列的值写入 `customer_part_no`，预览阶段批量查询着色
- **手动添加产品时**：从 `Product.part_no` 写入 `customer_part_no`，自动查询着色
- **编辑产品属性时**：修改对应客户料号的产品属性字段并失焦后，同步更新 `customer_part_no`，重新查询着色并更新边框颜色

**料号信息弹出卡片：**
产品卡片右上角"料号信息"按钮 → Popover 展示匹配结果的完整信息。

---

## 7. 模板体系

### 7.1 两种模板并存

```
核价模板（CostingTemplate）— 内部核算
  绑定: 产品分类
  视图: 表格形式（一产品一行）
  数据源: 产品数据池变量
  公式: JEXL 扩展（PATH / COL / SUM / AVG 等）

客户报价模板（CustomerTemplate）— 对外报价（合并产品卡片 + Excel视图）
  绑定: 客户 + 产品分类
  视图: 产品卡片 / Excel 视图 双视图
  数据源: 产品数据池变量 + 用户输入
  公式: 
    - 产品卡片 FORMULA 字段: JEXL
    - Excel 视图 EXCEL_FORMULA 列: Excel 原生语法
```

### 7.2 核价模板配置

```
配置项:
  - 基本信息: 名称、产品分类、是否默认、版本
  - 列定义表格:
    col_key / 列标题 / 数据来源（变量/公式）/ 值 / 业务标签
```

**支持的公式函数：**

| 函数 | 说明 |
|------|------|
| PATH(路径) | 取产品数据池变量值 |
| COL(列Key) | 取本行其他列值 |
| SUM / AVG / MAX / MIN / COUNT | 聚合函数（作用于数组路径） |
| LOOKUP | 从 Sheet 按条件取单值 |
| IF(条件, 真值, 假值) | 条件判断 |

### 7.3 客户报价模板配置

保留现有组件/模板配置体系，三项扩展：

**扩展 1：组件字段新增 BASIC_DATA 类型**

```
字段类型:
  FIXED_VALUE / INPUT / FORMULA / DATA_SOURCE / BASIC_DATA (新)

BASIC_DATA 字段绑定产品数据池变量，支持:
  - 绝对路径: {HF_PART_NO}
  - 相对路径（配合 row_expansion）: $.元素、$..投入料号名称
```

**扩展 2：组件支持行展开（row_expansion）**

```
FLAT 模式（一维）:
  source_path: "元素BOM[*]"
  → 按元素生成多行

GROUPED 模式（二维分组）:
  source_path: "来料BOM[*].元素BOM[*]"
  group_by: "来料BOM"
  group_display_field: "投入料号名称"
  → 按投料分组展示元素
```

**组件 FORMULA 字段的跨组件引用（沿用现有能力）：**

- 组件内 FORMULA 字段可以引用本组件的其他字段（如 `[含量] * [单价]`）
- 组件内 FORMULA 字段支持引用**其他组件的小计值**（橙色芯片形式，如 `[投料·小计] + [加工费·小计]`）
- 跨组件引用逻辑保留现有 PRD 规范（组件管理模块）

**扩展 3：Excel 视图配置（新 Tab）**

```
多 Sheet 支持:
  - sheets: 每个 Sheet 独立配置
  - import_entry_sheet: 指定导入入口 Sheet
  - 跨 Sheet 公式: 支持 Excel 原生 SheetName!Cell 语法

每个 Sheet 的列定义:
  col_key / title / source_type / 绑定信息 / 公式 / 业务标签

source_type:
  - PRODUCT_ATTRIBUTE: 映射到产品属性
  - COMPONENT_FIELD: 映射到组件字段（含 row_index）
  - EXCEL_FORMULA: Excel 公式（用 {row} 占位符）
  - FIXED_VALUE: 固定值
```

### 7.4 变量选择器 UI

两种模板的字段配置中引用变量时，使用统一的变量选择器：

```
左侧: Sheet 树（来自 BasicDataConfig）
右侧: 选中 Sheet 的变量列表 + 筛选条件配置

筛选条件:
  - 一对多 Sheet 需配置筛选条件
  - 筛选字段下拉仅显示 IDENTIFIER 类型字段
  - 支持按祖先 Sheet 字段筛选

三种路径模式:
  - DIRECT: 直接变量（如 HF_PART_NO）
  - 按条件筛选: 元素BOM[元素='Ag'].组成含量(%)
  - 全部聚合: 元素BOM[*].组成含量(%) + 配合聚合函数
```

### 7.5 业务标签字典

```
预定义常用标签（分组）:
  - 材料成本维度: MATERIAL_COST_AG / MATERIAL_COST_CU / MATERIAL_COST_TOTAL
  - 加工费维度: PROCESSING_COST / LABOR_COST / SETUP_COST
  - 其他费用: OVERHEAD_COST / PACKAGING_COST / CUSTOM_COST
  - 汇总: UNIT_TOTAL_COST / TOTAL

自定义: 用户可新增

用途: 核价模板和客户报价模板的字段通过相同标签配对，用于比对
```

### 7.6 模板匹配规则

```
导入时按客户 + 产品分类匹配:

核价模板匹配:
  WHERE category_id = ? AND status='PUBLISHED'
  0 条 → 阻止导入
  1 条 → 自动选中
  N 条 → 弹窗选择（默认模板预选）

客户报价模板匹配:
  WHERE customer_id = ? AND category_id = ? AND status='PUBLISHED'
  0 条 → 阻止导入
  1 条 → 自动选中
  N 条 → 弹窗选择
```

---

## 8. 产品数据池

### 8.1 定位

产品数据池是**导入解耦层**，将基础数据 Excel 整合为**以宏丰料号为根节点的树形结构**，供两种模板（核价模板、客户报价模板）引用。

### 8.2 存储策略

- 按批次存储（import_batch_id 标识）
- 保留历史批次，便于追溯
- 同一产品的多次导入产生多条 ProductDataPool 记录

### 8.3 数据结构（data_tree）

```json
{
  "HF_PART_NO": "3120012574",
  "UNIT_WEIGHT": 0.4,
  "来料BOM": [
    {
      "INPUT_NAME": "Ag铆钉",
      "元素BOM": [
        {"ELEMENT": "Ag", "COMP_PCT": 75, "元素单价": 400},
        {"ELEMENT": "Ni", "COMP_PCT": 25, "元素单价": 180}
      ]
    }
  ]
}
```

### 8.4 变量路径寻址

```
简单路径: {HF_PART_NO}
筛选路径: {元素BOM[元素='Ag'].组成含量(%)}
聚合路径: {元素BOM[*].组成含量(%)}
嵌套路径: {来料BOM[投入料号名称='Ag铆钉'].元素BOM[元素='Ag'].组成含量(%)}
```

### 8.5 衍生字段计算

导入时按拓扑排序依次计算：
1. 基础字段（从 Excel 直接读取）
2. 衍生字段（按依赖顺序计算）

衍生字段结果追加到 data_tree 对应节点。

---

## 9. 导入流程

### 9.1 入口

报价单管理页顶部：
```
[+ 手动创建报价单]       (沿用现有)
[+ 从基础数据导入]      (新增，主流程)
[导入历史]              (新增)
```

### 9.2 导入向导（5 步）

```
步骤 1: 选择客户
步骤 2: 上传基础数据 Excel
步骤 3: 系统自动解析 + 模板匹配
  ├─ 解析 Excel 构建产品数据池
  ├─ 计算衍生字段
  ├─ 识别所有产品（多产品支持）
  ├─ 校验产品分类一致性（不一致则阻止）
  ├─ 匹配核价模板
  └─ 匹配客户报价模板
步骤 4: 预览（核价表 + 客户报价表 + 料号匹配状态）
步骤 5: 确认生成
  事务:
    1. 保存原始 Excel 到服务端
    2. 创建 ProductDataPool（按批次）
    3. 计算衍生字段写入 data_tree
    4. 创建 Quotation (DRAFT)
    5. 生成 QuotationLineItem + QuotationLineComponentData + CostingSheet
    6. 创建 ImportRecord
  跳转到报价生成器步骤二
```

### 9.3 多产品导入规则

- 一次导入支持多个产品（宏丰料号列多行）
- 所有产品必须属于同一产品分类
- 分类不一致时阻止导入并提示

### 9.4 模板匹配规则

详见 [7.6](#76-模板匹配规则)。

### 9.5 重新导入机制

```
DRAFT 状态支持重新导入基础数据:
  - 新的导入创建新的 ProductDataPool 批次
  - Quotation.import_batch_id 更新为最新批次
  - ImportRecord 新增一条（关联新批次）
  - 核价表重新计算
  - BASIC_DATA 字段冲突处理:
    系统列出用户修改过的字段 vs 新基础数据的差异
    用户勾选保留或覆盖

APPROVED 及之后状态不允许重新导入
  如需修改: 走"撤回审批"流程
```

### 9.6 文件保留

- 原始 Excel 路径: `data/imports/{customer_id}/{yyyy-MM}/{uuid}.xlsx`
- 文件保留 12 个月
- ImportRecord 永久保留

---

## 10. 四视图体系

### 10.1 视图切换

报价生成器步骤二顶部：
```
[产品卡片视图] [Excel视图] [核价表视图] [比对视图 ⚠N]
```

四视图共享底层数据（QuotationLineItem + QuotationLineComponentData），切换无需保存。

### 10.2 产品卡片视图

- 按组件/页签/行展示
- BASIC_DATA 字段自动填充（可编辑）
- FORMULA 字段实时计算（前端 JS）
- row_expansion 模式自动展开多行（FLAT 或 GROUPED）

### 10.3 Excel 视图

- 使用 Luckysheet / Handsontable 渲染
- 按 excel_view_config.sheets 显示多 Sheet 标签
- 每行对应一个产品
- 列背景色区分可编辑列 / 公式列 / 固定值列
- EXCEL_FORMULA 列用 Excel 原生公式

### 10.4 核价表视图

- 只读展示核价表内容
- 按核价模板的 columns 渲染
- DRAFT 阶段跟随基础数据实时更新
- SUBMITTED 及之后显示快照数据

### 10.5 比对视图

详见 [11. 比对环节](#11-比对环节)。

### 10.6 双向同步机制

```
同一份底层数据 → 四视图独立渲染
  ├── 产品卡片视图编辑 BASIC_DATA → 更新 row_data → 其他视图刷新
  ├── Excel 视图编辑单元格 → 通过 excel_view_config 反查 → 更新 row_data
  ├── 核价表视图只读（不编辑）
  └── 比对视图只读（不编辑）

同步规则:
  - PRODUCT_ATTRIBUTE / COMPONENT_FIELD / BASIC_DATA 类型可编辑
  - EXCEL_FORMULA / FORMULA 自动计算，不可手工编辑
  - FIXED_VALUE 不可编辑
  - 编辑后自动触发相关公式重算
```

---

## 11. 比对环节

### 11.1 目的

让销售代表在送审前自检：哪些数据被修改、毛利是否合理。避免多次送审返工。

### 11.2 两个 Tab

**Tab 1: 基础数据字段对比**

通过相同 `variable_code` 配对核价表和客户报价表中的共享变量字段，展示：
- 一致 / 用户修改 / 缺失 / 新增

**Tab 2: 公式计算对比**

通过业务标签（`comparison_tag`）配对核价表和客户报价表中的公式字段，按业务分组展示：
- 材料成本维度
- 加工费维度
- 其他费用
- 汇总

**毛利分析（自动计算）：**
```
毛利 = 客户报价（UNIT_TOTAL_COST标签）- 核价（UNIT_TOTAL_COST标签）
毛利率 = 毛利 / 客户报价总价 × 100%
```

### 11.3 不配置阈值

系统只标注差异，不主动警告。由销售人员自行判断是否提交。

### 11.4 数据获取

```
GET /api/cpq/quotations/{id}/comparison

后端逻辑:
  1. 读取核价模板 + 客户报价模板的列定义
  2. 按 variable_code 配对基础数据字段
  3. 按 comparison_tag 配对公式字段
  4. 从产品数据池 + 报价表 + 核价表取数
  5. 计算差异
  6. 按分组返回
```

### 11.5 展示位置

- 报价生成器步骤二：一个视图切换 tab
- 报价单详情页：一个独立标签页（审批时使用）

---

## 12. 审批流程

### 12.1 审批视角增强

报价单详情页新增：
- 核价表标签页（只读）
- 比对差异标签页（角标显示修改项数量）
- 审批操作区上方的"审批摘要卡片"（默认展开）

### 12.2 审批摘要卡片

```
核价对比摘要:
  核价总成本: 238 元
  客户报价总价: 273.45 元
  毛利: 35.45 元
  毛利率: 12.97%
  
  用户修改字段: 3 处
    - Ag单价: 400 → 420 (+5.0%)
    - Cu单价: 38.5 → 40 (+4.0%)
    - 加工费: 5 → 5.5 (+10.0%)
  
  [查看完整比对 →]
```

### 12.3 审批动作

- [通过] + 审批意见（可选填）
- [退回] + 退回原因（必填）

### 12.4 审批历史增强

每次审批动作记录时写入快照：
```
approval_snapshot: {
  "costing_total": 238.00,
  "quotation_total": 273.45,
  "profit": 35.45,
  "profit_rate": 12.97,
  "modified_fields_count": 3
}
```

### 12.5 审批撤回机制（新增）

```
场景: APPROVED 状态后因价格波动等原因需要修改

流程:
  销售代表在详情页点击 [请求撤回]
    → 填写撤回原因（必填）
    → 创建 QuotationWithdrawRequest (PENDING)
    → 通知原审批人
  
  原审批人:
    [同意撤回] → Quotation 状态 APPROVED → DRAFT
    [拒绝撤回] → 保持 APPROVED, 通知销售代表

限制:
  - 只有 APPROVED 状态可请求撤回
  - SENT / ACCEPTED / REJECTED / EXPIRED 不可撤回
  - 撤回后审批历史保留

原审批人离职/停用的兜底处理:
  - 撤回请求路由目标为 Quotation.assigned_approver_id 指向的审批人
  - 若该用户 status=INACTIVE 或已删除:
    → 走审批规则重新路由（按当前 ApprovalRule 匹配新审批人）
    → 若路由失败，由系统管理员兜底接收撤回请求
  - 新审批人可以处理撤回请求（同意/拒绝）

状态机更新:
  新增 APPROVED → DRAFT (撤回审批)
```

### 12.6 "待我审批"列表增强

新增列：毛利率、调整项数

```
报价单号 | 客户 | 金额 | 毛利率 | 调整 | 提交时间
```

### 12.7 通知内容增强

通知邮件/站内消息包含毛利率摘要：
```
新报价单待审批: QT-20260423-0015
客户: 施耐德
报价金额: ¥ 273.45（含 3 项数据调整）
毛利率: 12.97%
```

---

## 13. Excel 输出

### 13.1 触发时机

```
APPROVED 及之后状态可导出

入口:
  - [导出 Excel] 按钮（主动下载）
  - [发送给客户] 按钮（邮件附件）
```

### 13.2 导出内容

按客户报价模板的 `customer_template_snapshot` 生成：
- 多 Sheet 支持
- 列标题来自 excel_view_config
- 数据值来自 QuotationLineItem 快照
- EXCEL_FORMULA 列写入**公式本身**（非结果值）
- 跨 Sheet 公式支持 Excel 原生语法

### 13.3 多 Sheet 处理

```
按 excel_view_config.sheets 顺序生成多个 Sheet
每个 Sheet:
  - 第 1 行表头
  - 第 2 行起数据行
  - EXCEL_FORMULA 列替换 {row} 占位符为实际行号
  - 跨 Sheet 公式支持 SheetName!Cell 语法
```

**`{row}` 占位符定义：**

Excel 视图配置中的 EXCEL_FORMULA 列使用 `{row}` 作为行号占位符。
- 渲染和导出时系统将其替换为**当前数据行的实际 Excel 行号**（从 2 开始，因为第 1 行是表头）
- 示例：`=B{row}*C{row}` → 第 1 个产品渲染为 `=B2*C2`，第 2 个产品为 `=B3*C3`
- 跨 Sheet 公式也可使用占位符：`=报价明细!E{row}*1.15` → `=报价明细!E2*1.15`
- 该占位符在前端 Excel 视图渲染和后端 Apache POI 导出时均生效，逻辑统一

### 13.4 导出选项

```
[导出 Excel] 弹出面板:
  ☐ 显示折扣信息（默认不勾选）
  ☑ 包含商务条款
  
  文件名: {报价单号}_{客户名}_{日期}.xlsx
```

### 13.5 版本一致性

导出时使用 `customer_template_snapshot`（模板快照），保证导出结果与报价单内容一致，不受模板后续变更影响。

### 13.6 无水印

导出 Excel 保持纯净，不添加水印或系统标识。

### 13.7 不存储导出文件

每次导出实时生成，不在服务器存储。重新导出结果一致。

---

## 14. 公式执行引擎

### 14.1 引擎分工

| 场景 | 引擎 | 执行位置 |
|------|------|---------|
| 衍生字段计算 | JEXL 扩展 | 后端（导入时） |
| 核价模板公式 | JEXL 扩展 | 后端（预计算 + 只读展示） |
| 产品卡片 FORMULA 字段 | 前端 JS + 后端 JEXL | 前端实时 + 后端校验 |
| Excel 视图 EXCEL_FORMULA | Luckysheet / 原生公式 | 前端 |
| Excel 导出公式 | Apache POI | 后端（写入公式字符串） |

### 14.2 JEXL 扩展函数

```
路径取值: PATH("{变量路径}")
列引用:   COL("列Key")
聚合:     SUM / AVG / MAX / MIN / COUNT
跨Sheet:  LOOKUP(sheet, match, field)
条件:     IF(条件, 真值, 假值)
```

### 14.3 统一语法

用户表达层面的语法：
```
变量路径:    {变量编码} 或 {Sheet[筛选].字段}
本表列引用:  [列Key]
聚合函数:    SUM(...) / AVG(...) / ...
四则运算:    + - * / ( )
```

### 14.4 性能

- 单次公式求值 < 10ms
- 全表重算（10 产品 × 50 列）< 500ms
- JexlExpression 编译结果缓存

### 14.5 循环依赖检测

- 保存模板时 DFS 检测
- 保存衍生字段时同样检测

### 14.6 前后端一致性

沿用 PRD v1.8-patch6 规范：
- 精度容差 ±0.01 元
- 超出阻止提交

### 14.7 @Blocking 约束

所有 JEXL 调用标注 `@Blocking`，在 Worker 线程执行。

### 14.8 Apache POI 导出公式

```java
// EXCEL_FORMULA 列
String formula = col.getFormula().replace("{row}", String.valueOf(excelRowNum));
if (formula.startsWith("=")) formula = formula.substring(1);
cell.setCellFormula(formula);

// 跨 Sheet 引用（Excel 原生语法，POI 直接支持）
// 例: =报价明细!E2*1.15

// 强制重算
workbook.setForceFormulaRecalculation(true);
```

---

## 15. 版本管理

### 15.1 两种模板共用版本机制

```
CostingTemplate / CustomerTemplate {
  series_id: UUID
  version: String (v1.0, v1.1...)
  status: DRAFT / PUBLISHED / ARCHIVED
  published_at: Timestamp
}

规则:
  - 每次发布新建记录
  - 小版本自动递增
  - 大版本手动指定
  - template_id 代表特定版本
```

### 15.2 快照内容

**CostingTemplate 快照：**
- columns（列定义）
- referenced_variables（引用的变量编码列表）

**CustomerTemplate 快照：**
- product_attributes
- components_snapshot（组件结构，含 BASIC_DATA、row_expansion）
- subtotal_formula
- excel_view_config（多 Sheet）
- referenced_variables

### 15.3 版本依赖处理

**基础数据变量禁用：**
- 变量只能"禁用"不能删除（新增 status 字段）
- 禁用后新模板不可引用，已发布模板继续使用
- 新导入数据该字段为空

**产品分类停用：**
- 关联的模板仍能使用
- 新建模板时停用的分类不出现
- 停用前检查关联模板

**组件变更：**
- 已发布模板快照了组件结构
- DRAFT 状态模板打开时提示"组件已更新"

### 15.4 归档保护

```
归档前检查:
  - 该版本是否被 DRAFT/SUBMITTED/APPROVED 报价单使用 → 阻止
  - 该分类/客户是否仅绑定这个版本 → 警告
  - 可强制归档（记录操作日志）
```

### 15.5 版本比对

保留现有功能（模块六），扩展支持核价模板和 Excel 视图的比对。

---

## 16. 管理界面

### 16.1 基础数据配置页

```
左右分栏:
  左侧: Sheet 树（按关联层级展示）
  右侧: Sheet 详情
    - 基本信息: 说明、父级、关联列
    - 属性配置 Tab: 列表 + 变量编码 + 标签 + 字段类型
    - 衍生字段 Tab: LOOKUP/EXPRESSION/AGGREGATE 配置

顶部按钮: [导入新版 Excel 模板]（增量更新）
```

### 16.2 核价模板配置页

```
基本信息:
  - 模板名称、产品分类、是否默认、版本、描述

列配置表格:
  列Key / 列标题 / 数据来源 / 变量或公式 / 业务标签 / 操作（排序/删除）

按钮:
  [+ 添加列] [公式编辑器] [预览] [保存草稿] [发布]
```

### 16.3 客户报价模板配置页

保留现有模板配置 UI，新增 Excel 视图标签页：

```
Tabs:
  - 产品属性
  - 组件画布（拖拽组件，含 row_expansion 开关）
  - 产品小计公式
  - Excel 视图配置（新增）
    - Sheet 标签 + 多 Sheet 管理
    - 每 Sheet 的列定义表格
    - 跨 Sheet 公式支持
```

### 16.4 产品分类管理页

```
列表 / 新增 / 编辑 / 停用
字段: 编码 / 名称 / 描述 / 父级（层级）/ 状态 / 关联核价模板
```

### 16.5 生产料号管理页

```
列表 / CRUD / Excel 批量导入
字段: 料号 / 品名 / 规格 / 尺寸 / 状态码
搜索 + 状态筛选 + 分页
```

### 16.6 客户料号关联管理

```
入口: 客户详情抽屉的"料号关联"标签页
操作: 新增关联（输入客户料号 + 选择我司料号）/ 删除 / Excel 批量导入
```

### 16.7 业务标签字典页

```
列表: 分组 / 编码 / 标签 / 类型（内置/自定义）/ 操作
内置标签不可删除，可停用
```

### 16.8 导入向导弹窗

详见 [9.2](#92-导入向导5-步)。

### 16.9 导入历史页

```
筛选: 客户 / 状态 / 日期
列表: 导入时间 / 操作人 / 客户 / 核价模板 / 客户模板 / 文件 / 产品数 / 状态 / 操作
详情抽屉: 基本信息 / 统计 / 配置快照 / 问题明细 / 下载原件
```

### 16.10 报价生成器步骤二（四视图切换）

顶部视图切换 tab：
```
[产品卡片视图] [Excel视图] [核价表视图] [比对视图 ⚠N]
```

### 16.11 报价单详情页

```
Tabs:
  - 基本信息
  - 产品明细（含视图切换）
  - 核价表（新增）
  - 比对差异（新增，角标）
  - 审批历史
  - 操作日志

审批操作区:
  - 审批摘要卡片（默认展开）
  - 通过/退回/撤回/发送客户/复制/延期
```

---

## 17. 菜单与权限

### 17.1 菜单结构

```
仪表板
├── 快捷入口: [从基础数据创建报价单] [待审批]

客户管理
├── 客户列表 (含料号关联 tab)
└── 定价策略

产品管理
├── 产品列表
├── 生产料号管理
└── 产品分类管理

报价中心
├── 报价单管理
├── 报价生成器
└── 导入历史

配置中心
├── 组件管理
├── 模板配置（客户报价模板）
├── 核价模板
├── 产品绑定
├── 版本比对
├── 基础数据配置
├── 数据源管理
└── 业务标签字典

系统管理
├── 用户管理
├── 区域/部门字典
├── 审批规则
└── 操作日志

通知中心 (右上角铃铛)
```

### 17.2 权限矩阵

| 模块/页面 | 销售代表 | 销售经理 | 定价经理 | 系统管理员 |
|----------|---------|---------|---------|-----------|
| 客户管理 | ✓ | ✓ | 查看 | ✓ |
| 料号关联 | 查看 | ✓ | 查看 | ✓ |
| 产品列表 | 查看 | ✓ | 查看 | ✓ |
| 生产料号管理 | 查看 | ✓ | 查看 | ✓ |
| 产品分类管理 | 查看 | ✓ | 查看 | ✓ |
| 定价策略 | 查看 | 查看 | ✓ | ✓ |
| 报价单管理 | 本人 | ✓ | 查看 | ✓ |
| 从基础数据导入 | ✓ | ✓ | - | ✓ |
| 手动创建报价单 | ✓ | ✓ | - | ✓ |
| 导入历史 | 本人 | ✓ | - | ✓ |
| 核价表查看 | 本人 | ✓ | ✓ | ✓ |
| 比对差异查看 | 本人 | ✓ | ✓ | ✓ |
| 导出 Excel | 本人 | ✓ | - | ✓ |
| 请求撤回审批 | 本人 | - | - | ✓ |
| 批准撤回审批 | - | 原审批人 | - | ✓ |
| 组件管理 | - | ✓ | - | ✓ |
| 客户报价模板 | - | ✓ | 查看 | ✓ |
| 核价模板 | - | ✓ | 查看 | ✓ |
| 产品绑定 | - | ✓ | - | ✓ |
| 基础数据配置 | - | ✓ | - | ✓ |
| 业务标签字典 | - | ✓ | - | ✓ |
| 数据源管理 | - | - | - | ✓ |
| 系统管理 | - | - | - | ✓ |

---

## 18. API 设计

### 18.1 产品分类

```
GET    /api/cpq/product-categories
POST   /api/cpq/product-categories
PUT    /api/cpq/product-categories/{id}
DELETE /api/cpq/product-categories/{id}
```

### 18.2 生产料号

```
GET    /api/cpq/internal-materials
POST   /api/cpq/internal-materials
PUT    /api/cpq/internal-materials/{id}
DELETE /api/cpq/internal-materials/{id}
POST   /api/cpq/internal-materials/import
```

### 18.3 客户料号关联

```
GET    /api/cpq/customers/{customerId}/material-mappings
POST   /api/cpq/customers/{customerId}/material-mappings
DELETE /api/cpq/customers/{customerId}/material-mappings/{id}
POST   /api/cpq/customers/{customerId}/material-mappings/import
GET    /api/cpq/customers/{customerId}/material-mappings/match?partNo=xxx
```

### 18.4 基础数据配置

```
POST   /api/cpq/basic-data-config/parse-excel      上传 Excel 解析结构
GET    /api/cpq/basic-data-config/sheets            Sheet 列表（含层级）
POST   /api/cpq/basic-data-config/sheets            新增 Sheet
PUT    /api/cpq/basic-data-config/sheets/{id}
DELETE /api/cpq/basic-data-config/sheets/{id}

GET    /api/cpq/basic-data-config/attributes?sheetId=xxx
POST   /api/cpq/basic-data-config/attributes
PUT    /api/cpq/basic-data-config/attributes/{id}
DELETE /api/cpq/basic-data-config/attributes/{id}  (禁用)

GET    /api/cpq/basic-data-config/derived?sheetId=xxx
POST   /api/cpq/basic-data-config/derived
PUT    /api/cpq/basic-data-config/derived/{id}
DELETE /api/cpq/basic-data-config/derived/{id}
```

### 18.5 业务标签字典

```
GET    /api/cpq/comparison-tags
POST   /api/cpq/comparison-tags
PUT    /api/cpq/comparison-tags/{id}
DELETE /api/cpq/comparison-tags/{id}
```

### 18.6 核价模板

```
GET    /api/cpq/costing-templates
GET    /api/cpq/costing-templates/{id}
POST   /api/cpq/costing-templates
PUT    /api/cpq/costing-templates/{id}
DELETE /api/cpq/costing-templates/{id}
POST   /api/cpq/costing-templates/{id}/publish
POST   /api/cpq/costing-templates/{id}/archive
```

### 18.7 客户报价模板

（沿用现有 /api/cpq/templates，扩展字段和 Excel 视图配置）

### 18.8 报价单 Excel 导入

```
POST   /api/cpq/quotations/import-basic-data      上传基础数据 Excel，返回预览
POST   /api/cpq/quotations/confirm-import          确认导入，生成报价单
POST   /api/cpq/quotations/{id}/reimport-basic-data  重新导入基础数据（DRAFT）
```

### 18.9 导入记录

```
GET    /api/cpq/import-records
GET    /api/cpq/import-records/{id}
GET    /api/cpq/import-records/{id}/download
```

### 18.10 报价单视图

```
GET    /api/cpq/quotations/{id}/costing-sheet      核价表数据
GET    /api/cpq/quotations/{id}/excel-view         Excel 视图数据
PUT    /api/cpq/quotations/{id}/excel-view         Excel 视图编辑保存
GET    /api/cpq/quotations/{id}/comparison         比对差异数据
GET    /api/cpq/quotations/{id}/export-excel       导出 .xlsx（含公式）
```

### 18.11 审批撤回

```
POST   /api/cpq/quotations/{id}/withdraw-request   请求撤回
POST   /api/cpq/quotations/{id}/withdraw/approve   批准撤回
POST   /api/cpq/quotations/{id}/withdraw/reject    拒绝撤回
```

---

## 19. 非功能需求

- Excel 解析支持 .xlsx 格式（Apache POI）
- 基础数据单次导入支持最大 500 个产品
- 导入预览响应时间 < 3s（500 行以内）
- 料号匹配查询 < 200ms
- 生产料号 Excel 导入最大 5000 条/次
- 原始 Excel 服务端保留 12 个月
- 导入记录永久保留
- Excel 视图渲染 < 1s（50 产品以内）
- Excel 导出 < 3s（50 产品以内）
- 核价表计算 < 500ms（10 产品 × 50 列）
- 双向同步延迟 < 200ms
- 前端电子表格组件需支持：公式计算、单元格编辑、列冻结、行列选择、多 Sheet
- JEXL 调用标注 @Blocking，在 Worker 线程执行

---

## 20. 版本演进摘要

### v1.0 → v2.0
- 新增 Excel 视图（挂载在模板上）
- Excel 导出携带公式
- 导入映射简化为列对列

### v2.0 → v3.0
- 去掉 CustomerExcelTemplate 和 ImportMappingTemplate
- 统一在模板配置页的 Excel 视图标签页
- 导入流程 6 步 → 5 步

### v3.0 → v4.0
- **引入基础数据驱动架构**（ProductDataPool）
- **新增核价模板体系**（CostingTemplate）
- **客户报价模板合并产品卡片 + Excel 视图**
- **新增产品分类字典**（替代枚举）
- **新增业务标签字典**（用于跨模板比对）
- **新增衍生字段机制**（LOOKUP/EXPRESSION/AGGREGATE）
- **新增比对视图**（报价 vs 核价）
- **新增审批撤回机制**（APPROVED → DRAFT）
- **组件扩展 BASIC_DATA 字段类型 + row_expansion**（FLAT / GROUPED）
- **Excel 视图支持多 Sheet + 跨 Sheet 公式**
- **审批流程增强**（审批摘要卡片 + 毛利分析）

### v4.0 的设计原则

```
1. 单一数据源：基础数据 Excel → 产品数据池
2. 职责分离：核价模板（内部）/ 客户报价模板（对外）
3. 视图派生：四视图共享底层数据，切换无需保存
4. 配置前置：衍生字段、业务标签在基础数据阶段定义
5. 版本快照：模板发布时快照配置，报价单关联特定版本
6. 追溯完整：原始文件保留 + 导入记录 + 批次数据保留
```

---

## 附录

### A.1 术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| 基础数据 | Basic Data | 内部维护的 Excel 数据，作为单一数据源 |
| 产品数据池 | Product Data Pool | 导入解析后的中间数据层，按批次存储 |
| 核价模板 | Costing Template | 内部成本核算模板，按产品分类绑定 |
| 客户报价模板 | Customer Template | 对外报价模板，按客户+分类绑定，含产品卡片+Excel视图 |
| 核价表 | Costing Sheet | 核价模板 + 产品数据池生成的内部成本表 |
| 衍生字段 | Derived Attribute | 基础数据层的预计算字段（LOOKUP/EXPRESSION/AGGREGATE） |
| 业务标签 | Comparison Tag | 跨模板字段配对用的业务含义标签 |
| 行展开 | Row Expansion | 组件按数据源自动展开多行（FLAT/GROUPED） |
| 批次 | Import Batch | 一次导入的产品数据池记录标识 |
| 宏丰料号 | HF Part No | 公司内部产品料号，作为产品数据池的根键 |
| 导入记录 / 导入历史 | Import Record | 同一概念。数据层称"导入记录"（ImportRecord 实体），UI/菜单层称"导入历史" |
| 四视图 | Four Views | 产品卡片视图 / Excel 视图 / 核价表视图 / 比对视图 |
| 双向同步 | Bidirectional Sync | 产品卡片视图与 Excel 视图编辑同一份底层数据，切换时自动刷新 |

### A.2 完整状态机（Quotation）

Quotation.status 的枚举值: DRAFT / SUBMITTED / APPROVED / SENT / ACCEPTED / REJECTED / EXPIRED

状态流转:

```
DRAFT ──提交审批──→ SUBMITTED
SUBMITTED ──审批通过──→ APPROVED
SUBMITTED ──审批退回──→ DRAFT
SUBMITTED ──销售代表撤回──→ DRAFT

APPROVED (存在 PENDING 撤回请求)
  ├─ 审批人同意撤回 ──→ DRAFT
  └─ 审批人拒绝撤回 ──→ 保持 APPROVED

APPROVED ──发送客户──→ SENT
SENT ──标记接受──→ ACCEPTED
SENT ──标记拒绝──→ REJECTED
SENT / APPROVED ──定时任务──→ EXPIRED
```

说明:
- "请求撤回"期间 Quotation 本身仍为 APPROVED 状态
- 撤回请求的状态记录在独立的 QuotationWithdrawRequest 表（PENDING/APPROVED/REJECTED）
- 审批人同意后才触发 Quotation 状态变更为 DRAFT
