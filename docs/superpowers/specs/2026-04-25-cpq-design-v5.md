# CPQ 系统设计 v5.0 — 主数据驱动 + 版本迭代 + 物理表架构

> **日期**: 2026-04-25
> **基于**: v4.0 重构（保留四视图体系、模板架构、审批流程）
> **核心变更**: 取消 ProductDataPool/衍生字段 → 物理表为唯一数据源；引入主数据 + 版本迭代；元素价格独立子系统
> **状态**: 设计完成（部分 UI 细节标注 TBD）

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [核心设计决策](#2-核心设计决策)
3. [整体架构](#3-整体架构)
4. [数据分类](#4-数据分类)
5. [数据模型](#5-数据模型)
6. [版本管理机制](#6-版本管理机制)
7. [元素价格子系统](#7-元素价格子系统)
8. [导入流程](#8-导入流程)
9. [变更日志架构](#9-变更日志架构)
10. [报价单快照机制](#10-报价单快照机制)
11. [四视图体系（沿用 v4.0）](#11-四视图体系)
12. [模板体系（沿用 v4.0）](#12-模板体系)
13. [审批流程（沿用 v4.0 + 撤回）](#13-审批流程)
14. [数据校验规则](#14-数据校验规则)
15. [菜单与权限](#15-菜单与权限)
16. [API 设计](#16-api-设计)
17. [非功能需求](#17-非功能需求)
18. [TBD 清单](#18-tbd-清单)
19. [版本演进摘要](#19-版本演进摘要)

---

## 1. 背景与目标

### 1.1 问题

1. 销售代表创建报价单需手动录入大量字段，效率低
2. 缺少成本核算视角，销售决策依据不足
3. 客户 Excel 格式多样，但内部基础数据需要规范化、版本化管理
4. 数据需要可追溯（变更历史、版本演进）

### 1.2 目标

- **统一基础数据 Excel** 作为业务源头，分类管理（基础资料 vs 客户资料）
- **版本化管理客户资料**：按料号迭代版本，报价单锁定特定版本
- **成本透明**：核价表 + 客户报价表协同，比对差异
- **多视图协同**：产品卡片 / Excel视图 / 核价表 / 比对差异
- **完整可追溯**：变更日志 + 历史版本 + 数据快照
- **客户不登录系统**，仅看到导出 Excel

---

## 2. 核心设计决策

### 2.1 架构层

| 决策项 | 选择 | 理由 |
|-------|------|------|
| 数据存储 | 10 张合并物理表（含类型字段） | 减少表数量，扩展便利 |
| 中间数据池 | 取消 ProductDataPool | 物理表为唯一数据源，避免缓存一致性问题 |
| 衍生字段 | 完全取消 | 所有计算上浮到模板公式（含 LOOKUP/SUM 等） |
| 物理表扩列 | Flyway ALTER TABLE + 管理界面 | 统一规范 |
| 变量路径解析 | 解析缓存 + 批量查询 | 平衡性能和复杂度 |

### 2.2 业务层

| 决策项 | 选择 |
|-------|------|
| 数据分类 | 基础资料（无版本） + 客户资料（料号级版本） + 元素价格（独立子系统） |
| 版本号格式 | 顺序整数 v1, v2, v3 |
| 报价单引用 | DRAFT 跟随最新 + SUBMITTED 锁定特定版本 |
| 历史版本 | 永不删除、完全只读、强保护 + 软删除 |
| 冲突处理 | 字段级（客户资料）/ 整体确认（基础资料） |
| 跨客户料号 | 全局唯一（差异化需建独立料号） |
| 待分类产品 | 自动创建 + DRAFT 可建，提交前必须分类 |

---

## 3. 整体架构

```
┌────────────────────────────────────────────────────────────────┐
│ 用户层（销售代表/销售经理/定价经理/系统管理员）                │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│ 业务功能层                                                     │
│   报价生成器 / 主数据维护 / 模板配置 / 审批管理                │
│   元素价格中心 / 变更日志中心 / 历史版本管理                   │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│ 元数据 + 解析层                                                │
│   BasicDataConfig / BasicDataAttribute (Sheet ↔ 物理表映射)    │
│   VariablePathResolver (路径 → SQL，含解析缓存)                 │
│   FormulaEngine (JEXL 扩展, 含 LOOKUP / SUM / AVG)              │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│ 物理存储层（PostgreSQL）                                       │
│  基础资料表（不版本化）:                                       │
│    mat_part / mat_bom / plating_plan /                          │
│    mat_customer_part_mapping                                    │
│                                                                  │
│  客户资料表（版本化）:                                          │
│    mat_process / mat_fee / plating_fee                          │
│    element_price                                                │
│                                                                  │
│  元素价格子系统:                                                │
│    element_price_source / element_price_fetch_rule /            │
│    element_daily_price                                          │
│                                                                  │
│  变更与审计:                                                    │
│    basic_data_change_log / import_record                        │
│                                                                  │
│  报价业务（沿用 v4.0）:                                          │
│    Quotation / QuotationLineItem /                              │
│    QuotationLineComponentData / CostingSheet /                  │
│    QuotationApproval / QuotationWithdrawRequest                 │
│                                                                  │
│  模板:                                                          │
│    CostingTemplate / CustomerTemplate                            │
│                                                                  │
│  其他: Customer / Product / ProductCategory /                   │
│    ComparisonTag / exchange_rate / tax_rate / ...               │
└────────────────────────────────────────────────────────────────┘
```

---

## 4. 数据分类

### 4.1 基础资料（不分客户、不版本化、变更日志）

| Excel Sheet | 物理表 | 说明 |
|------------|-------|-----|
| 单重 | `mat_part.unit_weight` | 嵌入 mat_part |
| 来料BOM | `mat_bom (bom_type=INCOMING)` | |
| 元素BOM | `mat_bom (bom_type=ELEMENT)` | |
| 电镀方案 | `plating_plan` | |
| 客户料号与宏丰料号关系 | `mat_customer_part_mapping` | 含 customer_id 但属基础资料 |

**更新策略：** 导入时展示差异 → 用户整体确认 → UPSERT 覆盖 + 写变更日志

### 4.2 客户资料（带 customer_id + 版本号、料号级迭代）

| Excel Sheet | 物理表 | 业务键（含版本） |
|------------|-------|----------------|
| 组成件BOM及单价 | `mat_process` | (customer_id, hf_part_no, version, ...) |
| 来料固定加工费 | `mat_fee (INCOMING_FIXED)` | (customer_id, hf_part_no, version, seq_no, ...) |
| 来料其他费用 | `mat_fee (INCOMING_OTHER)` | 同上 |
| 成品固定加工费 | `mat_fee (FINISHED_FIXED)` | 同上 |
| 成品其他费用 | `mat_fee (FINISHED_OTHER)` | 同上 |
| 组装加工费 | `mat_fee (ASSEMBLY_PROCESS)` | 同上 |
| 电镀费用 | `plating_fee` | (customer_id, hf_part_no, version, ...) |

**更新策略：** 导入时字段级冲突处理 → 用户选择 → 创建新版本 + 写变更日志

### 4.3 元素价格（独立子系统）

| 表 | 来源 | 版本粒度 |
|----|-----|---------|
| `element_price` | excel(元素单价) | 元素级（customer_id + element_name） |
| `element_price_source` | 网址表（独立维护） | 不版本化 |
| `element_price_fetch_rule` | 规则表（独立维护） | 不版本化 |
| `element_daily_price` | 每日抓取 | 不版本化（按日期累积） |

---

## 5. 数据模型

### 5.1 基础资料表

#### 5.1.1 `mat_part` — 生产料号（含单重）

```
mat_part {
  part_no              VARCHAR(64)  PK
  part_name            VARCHAR(128)
  specification        VARCHAR(128)
  size_info            VARCHAR(128)
  category_id          UUID         FK → ProductCategory (nullable)
  unit_weight          DECIMAL(18,4)
  weight_unit          VARCHAR(16)
  status_code          ENUM [Y, N]
  is_pending_category  BOOLEAN  DEFAULT false  (待分类标记)
  created_at / updated_at
}

索引: (category_id, status_code), (is_pending_category)
```

#### 5.1.2 `mat_customer_part_mapping` — 客户料号对照

```
mat_customer_part_mapping {
  id                    UUID PK
  customer_id           UUID FK → Customer
  customer_part_name    VARCHAR(128)
  customer_product_no   VARCHAR(64)
  customer_drawing_no   VARCHAR(64)
  hf_part_no            VARCHAR(64) FK → mat_part.part_no
  payment_method        VARCHAR(64)
  base_currency         VARCHAR(8)
  quote_currency        VARCHAR(8)
  created_at / updated_at
}

唯一约束: (customer_id, customer_product_no)
```

#### 5.1.3 `mat_bom` — 统一 BOM 表（合并来料 + 元素）

```
mat_bom {
  id                    UUID PK
  bom_type              ENUM [INCOMING, ELEMENT]
  hf_part_no            VARCHAR(64) FK → mat_part.part_no
  seq_no                INT
  input_material_no     VARCHAR(64)
  input_material_name   VARCHAR(128)
  loss_rate             DECIMAL(10,4)
  
  -- 公共数量字段（合并后）--
  gross_qty             DECIMAL(18,4)  -- 毛用量/材料毛重
  net_qty               DECIMAL(18,4)  -- 净用量/材料净重
  gross_unit            VARCHAR(16)
  net_unit              VARCHAR(16)
  
  -- INCOMING 专用 --
  output_material_type  VARCHAR(64)
  defect_rate           DECIMAL(10,4)
  
  -- ELEMENT 专用 --
  element_name          VARCHAR(64)
  composition_pct       DECIMAL(10,4)
  
  created_at / updated_at
}

索引: (bom_type, hf_part_no, seq_no)
唯一约束: (bom_type, hf_part_no, seq_no, COALESCE(input_material_no,''), COALESCE(element_name,''))
```

#### 5.1.4 `plating_plan` — 电镀方案

```
plating_plan {
  id                   UUID PK
  plan_code            VARCHAR(32)
  version              VARCHAR(16)
  seq_no               INT
  plating_element      VARCHAR(64)
  plating_area         DECIMAL(18,4)
  coating_thickness    DECIMAL(10,4)
  plating_requirement  VARCHAR(256)
  created_at / updated_at
}

唯一约束: (plan_code, version, seq_no)
```

### 5.2 客户资料表（带版本）

#### 5.2.1 `mat_process` — 组成件BOM及单价

```
mat_process {
  id                    UUID PK
  customer_id           UUID FK → Customer
  hf_part_no            VARCHAR(64) FK → mat_part.part_no
  version               INT
  is_current            BOOLEAN
  
  seq_no                INT
  process_code          VARCHAR(32)
  assembly_process      VARCHAR(64)
  sub_seq_no            INT
  component_part_no     VARCHAR(64)
  component_name        VARCHAR(128)
  supplier_code         VARCHAR(32)
  supplier_name         VARCHAR(128)
  quantity              DECIMAL(18,4)
  quantity_unit         VARCHAR(16)
  unit_price            DECIMAL(18,4)
  freight               DECIMAL(18,4)
  currency              VARCHAR(8)
  price_unit            VARCHAR(16)
  
  status                ENUM [ACTIVE, DELETED]
  imported_by           UUID FK → User
  import_record_id      UUID FK
  created_at / updated_at
}

索引: (customer_id, hf_part_no, version), (is_current)
唯一约束: (customer_id, hf_part_no, version, seq_no, sub_seq_no)
部分唯一索引: (customer_id, hf_part_no, seq_no, sub_seq_no) WHERE is_current=true
```

#### 5.2.2 `mat_fee` — 统一费用表

```
mat_fee {
  id                       UUID PK
  customer_id              UUID FK → Customer
  hf_part_no               VARCHAR(64) FK → mat_part.part_no
  version                  INT
  is_current               BOOLEAN
  fee_type                 ENUM [INCOMING_FIXED, INCOMING_OTHER, FINISHED_FIXED, FINISHED_OTHER, ASSEMBLY_PROCESS]
  
  seq_no                   INT
  fee_value                DECIMAL(18,4)
  fee_ratio                DECIMAL(10,4)
  currency                 VARCHAR(8)
  price_unit               VARCHAR(16)
  
  -- 维度字段 --
  dim_input_material_no    VARCHAR(64)
  dim_input_material_name  VARCHAR(128)
  dim_element_name         VARCHAR(128)
  dim_assembly_process     VARCHAR(64)
  dim_sub_seq_no           INT
  
  -- INCOMING_FIXED 专用 --
  price_floating           BOOLEAN
  settlement_rise_ratio    DECIMAL(10,4)
  fixed_rise_value         DECIMAL(18,4)
  rise_currency            VARCHAR(8)
  rise_unit                VARCHAR(16)
  
  -- ASSEMBLY_PROCESS 专用 --
  reject_rate              DECIMAL(10,4)
  
  status                   ENUM [ACTIVE, DELETED]
  imported_by              UUID
  import_record_id         UUID
  created_at / updated_at
}

索引: (customer_id, fee_type, hf_part_no, version), (is_current)
```

#### 5.2.3 `plating_fee` — 电镀费用

```
plating_fee {
  id                    UUID PK
  customer_id           UUID FK → Customer
  hf_part_no            VARCHAR(64) FK → mat_part.part_no
  version               INT
  is_current            BOOLEAN
  
  plating_plan_code     VARCHAR(32)
  plan_version          VARCHAR(16)
  plating_process_fee   DECIMAL(18,4)
  plating_material_fee  DECIMAL(18,4)
  currency              VARCHAR(8)
  price_unit            VARCHAR(16)
  defect_rate           DECIMAL(10,4)
  
  status                ENUM [ACTIVE, DELETED]
  imported_by           UUID
  import_record_id      UUID
  created_at / updated_at
}

索引: (customer_id, hf_part_no, version), (is_current)
```

#### 5.2.4 `element_price` — 客户元素价格配置（元素级版本）

```
element_price {
  id                    UUID PK
  customer_id           UUID FK → Customer
  element_name          VARCHAR(64)        -- Ag / Cu / ALL 等
  version               INT
  is_current            BOOLEAN
  
  source_id             UUID FK → element_price_source
  fetch_rule_id         UUID FK → element_price_fetch_rule
  premium_price         DECIMAL(18,4)       -- 升水价/手续费
  currency              VARCHAR(8)
  price_unit            VARCHAR(16)
  
  status                ENUM [ACTIVE, DELETED]
  imported_by           UUID
  import_record_id      UUID
  created_at / updated_at
}

索引: (customer_id, element_name, version), (is_current)
唯一约束: (customer_id, element_name, version)
部分唯一索引: (customer_id, element_name) WHERE is_current=true
```

### 5.3 元素价格子系统

#### 5.3.1 `element_price_source`

```
element_price_source {
  id              UUID PK
  source_name     VARCHAR(128)
  source_url      VARCHAR(256)
  source_type     ENUM [HTML_SCRAPE, API, MANUAL]
  description     TEXT
  status          ENUM [ACTIVE, DISABLED]
  created_at / updated_at
}

唯一约束: (source_name, source_url)
```

#### 5.3.2 `element_price_fetch_rule`

```
element_price_fetch_rule {
  id                  UUID PK
  rule_name           VARCHAR(128)
  rule_code           VARCHAR(64) UNIQUE
  rule_definition     JSONB
  description         TEXT
  status              ENUM [ACTIVE, DISABLED]
  created_at / updated_at
}

rule_definition 示例:
  {"type":"AVERAGE", "period_type":"PREVIOUS_MONTH"}      -- 上月平均
  {"type":"AVERAGE_HIGH", "period_type":"PREVIOUS_DAY"}    -- 前一天峰值平均
  {"type":"REAL_TIME", "use":"close_price"}                -- 实时价
```

#### 5.3.3 `element_daily_price`

```
element_daily_price {
  id              UUID PK
  element_name    VARCHAR(64)
  source_id       UUID FK → element_price_source
  price_date      DATE
  raw_price       DECIMAL(18,4)
  raw_high        DECIMAL(18,4)
  raw_low         DECIMAL(18,4)
  raw_open        DECIMAL(18,4)
  raw_close       DECIMAL(18,4)
  currency        VARCHAR(8)
  price_unit      VARCHAR(16)
  fetch_status    ENUM [SUCCESS, FAILED, MANUAL]
  fetch_error     TEXT
  fetched_at      TIMESTAMP
  manually_filled_by UUID (nullable)  -- 人工补录人
}

唯一约束: (element_name, source_id, price_date)
索引: (element_name, price_date DESC)
```

### 5.4 变更日志

```
basic_data_change_log {
  id                    UUID PK
  table_name            VARCHAR(64)
  record_id             UUID
  business_key          JSONB           -- 业务键值快照
  change_type           ENUM [CREATE, UPDATE, NEW_VERSION, SOFT_DELETE]
  field_changes         JSONB           -- [{"field":"...", "old":..., "new":...}]
  version_before        INT (nullable)
  version_after         INT (nullable)
  import_record_id      UUID FK
  changed_by            UUID FK → User
  changed_at            TIMESTAMP
  remarks               TEXT (nullable)
}

索引: (table_name, record_id, changed_at DESC), (import_record_id), (changed_by, changed_at DESC)
```

### 5.5 导入记录

```
import_record {
  id                       UUID PK
  customer_id              UUID FK → Customer
  imported_by              UUID FK → User
  import_type              ENUM [BASIC_DATA_ONLY, FOR_QUOTATION]
  related_quotation_id     UUID (nullable)  -- FOR_QUOTATION 时关联
  
  original_file_name       VARCHAR(256)
  original_file_path       VARCHAR(512)
  
  total_sheets             INT
  basic_sheets_count       INT
  customer_sheets_count    INT
  
  -- 处理统计 --
  rows_created             INT
  rows_updated             INT
  versions_iterated        INT
  rows_unchanged           INT
  
  remarks                  TEXT (nullable)  -- 导入级备注
  status                   ENUM [SUCCESS, PARTIAL, FAILED, IN_PROGRESS]
  error_detail             JSONB
  
  conflict_resolution_state JSONB           -- 中间状态保存（用户离开后恢复）
  
  created_at / updated_at
}

索引: (customer_id, created_at DESC), (imported_by, created_at DESC)
```

### 5.6 报价单相关（沿用 v4.0 + 新增字段）

#### 5.6.1 Quotation 扩展

```
Quotation {
  ...existing v4.0 fields...
  
  -- v5.0 新增 --
  referenced_versions       JSONB    -- SUBMITTED 时锁定版本，结构见下文
  last_basic_data_sync_at   TIMESTAMP -- 上次同步基础数据时间（DRAFT 漂移检测）
}

referenced_versions 结构:
{
  "mat_process":   {"3120012574": 5, "3120012575": 3},
  "mat_fee":       {"3120012574": 3},
  "plating_fee":   {"3120012574": 2},
  "element_price": {"Ag": 12, "Cu": 8, "Fe": 5},
  "element_actual_prices": {
    "Ag": 405.5,
    "Cu": 42.3
  },
  "basic_data_snapshot_at": "2026-04-25T10:30:00Z"
}
```

#### 5.6.2 QuotationLineItem 扩展

```
QuotationLineItem {
  ...existing fields...
  customer_part_no       String
  excel_view_snapshot    JSONB
  
  -- v5.0 新增 --
  basic_snapshot         JSONB    -- 基础资料快照
}

basic_snapshot 结构:
{
  "unit_weight": 0.5,
  "part_name": "...",
  "specification": "...",
  "bom_summary": [
    {"input_material_no": "Ag铆钉", "element": "Ag", "composition_pct": 75},
    ...
  ]
}
```

### 5.7 沿用 v4.0 的实体（不再展开）

- `Customer` / `ProductCategory`
- `CostingTemplate` / `CustomerTemplate` (含 components_snapshot, excel_view_config)
- `CostingSheet` / `QuotationLineComponentData`
- `QuotationApproval` / `QuotationWithdrawRequest`
- `ComparisonTag`
- `ImportMappingTemplate`（已在 v4.0 中存在）
- `Customer / Product / InternalMaterial / CustomerMaterialMapping` (注：原 InternalMaterial 演进为 mat_part)
- `User / Region / Department / OperationLog / Notification`
- `BasicDataConfig / BasicDataAttribute`
- `exchange_rate / tax_rate`

### 5.8 实体关系总览

```
Customer
  ├── mat_customer_part_mapping (基础资料)
  ├── element_price (客户资料·元素级版本)
  ├── 客户资料: mat_process / mat_fee / plating_fee
  ├── tax_rate
  └── Quotation
       ├── QuotationLineItem
       ├── CostingSheet
       ├── QuotationApproval
       └── QuotationWithdrawRequest

mat_part (PK=part_no, 全局唯一)
  ├── mat_customer_part_mapping.hf_part_no
  ├── mat_bom.hf_part_no
  ├── mat_process.hf_part_no
  ├── mat_fee.hf_part_no
  └── plating_fee.hf_part_no

ProductCategory ←── mat_part.category_id

ComparisonTag ←── 模板列引用

element_price → element_price_source (FK)
              → element_price_fetch_rule (FK)
element_daily_price → element_price_source (FK)

basic_data_change_log ──→ 关联所有数据表的变更
import_record ──→ 关联变更日志
```

---

## 6. 版本管理机制

### 6.1 版本号规则

- 整数顺序递增：v1, v2, v3, ...
- 首次创建：v1
- 每次"创建新版本"：当前版本 + 1
- 每条记录的"业务键 + version"全局唯一

### 6.2 当前版本（is_current）

- 每个业务键最多一个 is_current=true 版本（部分唯一索引保证）
- 创建新版本：旧版本 is_current=false，新版本 is_current=true
- 报价 DRAFT 阶段查询使用 is_current=true 数据

### 6.3 触发条件

```
默认: 检测到任何字段差异 → 创建新版本
用户可选: 修正模式（覆盖当前版本，不迭代）
  - 适用于错别字、单位修正等场景
  - 选此模式时记录 change_type=UPDATE 到日志（不是 NEW_VERSION）
```

### 6.4 报价单引用版本

```
DRAFT 阶段:
  - Quotation.referenced_versions 为空
  - 数据查询使用 is_current=true 版本
  - 主数据变更时，DRAFT 报价单"漂移"
  - 打开 DRAFT 时检测漂移 → 红色横幅强制确认

SUBMITTED 时:
  - 计算并锁定所有引用的版本号到 referenced_versions
  - 快照核价表/Excel视图/组件数据/基础资料到报价单内嵌字段
  - 之后查询根据 referenced_versions 取特定版本

引用元素的版本由 mat_bom (ELEMENT, hf_part_no=报价单产品) 推断
```

### 6.5 版本生命周期

```
创建（导入）→ is_current=true
迭代（新版本）→ 旧版本 is_current=false（物理保留）
软删除 → status=DELETED（被报价单引用时不可硬删）

历史版本完全只读：所有字段不可修改
版本永不物理删除（暂不归档）
```

### 6.6 漂移检测机制

```
DRAFT 报价单打开时:
  查 basic_data_change_log:
    WHERE 涉及表的 record 业务键匹配本报价单产品
      AND changed_at > Quotation.last_basic_data_sync_at
  
  发现变更 → 显示红色横幅 + 强制选择处理方式:
    [采用最新值] - 用最新版本数据更新报价单显示
    [保留我的编辑] - 维持当前数据，更新 last_basic_data_sync_at
    [逐字段选择] - 进入字段级冲突处理界面
```

---

## 7. 元素价格子系统

### 7.1 数据流

```
每日 08:00 定时任务 → 抓取 element_price_source 网址 → 写入 element_daily_price
                                                       ↓
                                       报价计算时按 element_price 配置查询
                                                       ↓
                                       按 fetch_rule 计算 base_price
                                                       ↓
                                       + element_price.premium_price
                                                       ↓
                                       = 实际单价（quotation_actual_price）
```

### 7.2 取价计算

```
报价时计算 Ag 实际单价:
1. 查 element_price (customer_id=施耐德, element_name='Ag', is_current=true)
   - 找不到 → 查 element_name='ALL'（兜底）
   - 仍找不到 → 标记"价格获取失败，请手动输入"
2. 得到 source_id, fetch_rule_id, premium_price
3. 按 fetch_rule 计算时间窗口
4. 查 element_daily_price (source_id, element='Ag', price_date 在窗口内)
5. 按 rule.type 计算 base_price (AVERAGE/MAX/MIN/...)
6. 校验数据完整度（默认 80%）：
   - 满足 → 继续
   - 不满足 → 标记"数据不完整，请手动确认"
7. final_price = base_price + premium_price
```

### 7.3 抓取定时任务

```
@Scheduled(cron = "0 0 8 * * ?")
@Blocking
public void dailyFetchElementPrices() {
    for (source in element_price_source WHERE status=ACTIVE) {
        try {
            prices = scrapeOrFetchApi(source);
            for (each element price) {
                upsert into element_daily_price;
            }
        } catch (Exception e) {
            // 重试 3 次
            // 失败后告警系统管理员
            log error
        }
    }
}
```

按需抓取（实时价规则）：报价单计算时，若使用 REAL_TIME 规则且缺少当日数据，触发该 source 的实时抓取。

### 7.4 手动输入兜底

```
报价单中元素价格无法自动计算时:
  显示红色单元格"⚠ 价格获取失败"
  销售代表手动输入价格
  该价格仅本报价单使用（写入 QuotationLineComponentData.row_data）
  不写回 element_daily_price
```

### 7.5 元素价格中心页面（TBD UI）

提供页面管理：
- 各元素当前价格快览
- 历史价格趋势
- 抓取状态监控（成功/失败统计）
- 手动补录入口（管理员可填补缺失日期）

---

## 8. 导入流程

### 8.1 入口

```
报价中心 → [+ 从基础数据创建报价单]   (一体化导入 + 创建报价单)
基础数据管理 → [上传基础数据]         (仅维护基础数据，独立入口)
```

### 8.2 统一导入流程（混合 UI）

```
步骤 1: 选客户
  ↓
步骤 2: 上传 Excel（获取产品级悲观锁）
  ↓
步骤 3: 解析 + 校验
  - 格式校验失败 → 阻止整 Excel
  - 类型校验失败 → 阻止整 Excel
  - 业务校验失败 → 警告但允许
  - 引用完整性失败 → 阻止整 Excel
  ↓
步骤 4: 检测变更 + 展示导入预览（混合 UI）
  ┌────────────────────────────────────┐
  │ 导入预览 - 客户 施耐德             │
  │                                     │
  │ 【基础资料】                        │
  │   单重: 5 新增, 2 修改             │
  │   来料BOM: 8 新增, 3 修改          │
  │   元素BOM: 4 修改                  │
  │   电镀方案: 无变化                 │
  │   客户料号映射: 1 新增             │
  │   [展开差异详情]                    │
  │                                     │
  │ 【客户资料】                        │
  │   mat_process: 5 料号待版本迭代    │
  │   mat_fee: 8 料号待版本迭代        │
  │   plating_fee: 2 料号待版本迭代    │
  │   element_price: 3 元素待版本迭代  │
  │   [处理冲突]                        │
  │                                     │
  │ 【新产品】                          │
  │   3 个待分类产品（提交报价前需分类）│
  │                                     │
  │ 备注: [本次更新原因_______]         │
  │                                     │
  │ [取消]   [确认导入]                 │
  └────────────────────────────────────┘
  ↓
步骤 5: 用户处理冲突
  - 基础资料: 整体确认
  - 客户资料: 字段级冲突处理（详细 UI 见 TBD）
  - 中间状态自动保存，可中断恢复
  ↓
步骤 6: 事务执行
  - 基础资料 UPSERT + 写日志
  - 客户资料创建新版本（旧 is_current=false，新 is_current=true）+ 写日志
  - 待分类产品创建（is_pending_category=true）
  - 写 import_record（含统计 + 备注）
  - 释放产品悲观锁
  ↓
步骤 7（仅 FOR_QUOTATION）: 匹配核价模板 + 客户报价模板
  ↓
步骤 8: 生成 DRAFT 报价单 + 跳转报价生成器
```

### 8.3 字段级冲突处理 UI（TBD 详细）

详细 UI 设计见 TBD 18.x，核心规则：

```
对每个有冲突的料号:
  显示当前版本 vs 导入版本的字段差异
  逐字段选择"保留当前 / 采用导入"
  支持批量快捷:
    - 全部保留当前 / 全部采用导入
    - 按产品批量
    - 按字段重要性批量
  中间状态保存（conflict_resolution_state in import_record）
```

### 8.4 重新导入（仅 DRAFT 报价单）

```
DRAFT 状态 → [重新导入基础数据]
  上传新 Excel
  系统对比 → 仍按版本机制处理
  涉及该报价单产品的版本更新 → 重新计算
  Quotation.last_basic_data_sync_at 更新

APPROVED 后不可重新导入，需先撤回审批
```

---

## 9. 变更日志架构

### 9.1 日志写入时机

| 数据类型 | 触发条件 | change_type |
|---------|---------|------------|
| 基础资料 | UPSERT 时新增 | CREATE |
| 基础资料 | UPSERT 时更新 | UPDATE |
| 客户资料 | 创建新版本 | NEW_VERSION |
| 客户资料 | 软删除 | SOFT_DELETE |
| 客户资料 | 修正模式覆盖（不迭代） | UPDATE |

### 9.2 查询入口

```
A) 业务上下文入口:
   - 客户详情页 → 该客户的变更
   - 导入历史页 → 某次导入产生的变更
   - 历史版本管理 → 某料号的版本演进
   - 元素价格中心 → 某元素的版本演进

B) 全局入口:
   - 系统管理 → 变更日志中心
   - 多维度筛选: 表 / 记录 / 用户 / 时间 / 导入 / 客户
```

### 9.3 清理策略

```
保留 5 年（可在系统配置中调整）
定时任务每月清理超期日志
被 SUBMITTED 报价单引用的版本对应日志强制保留
```

### 9.4 导出

```
日志页面 → [导出 Excel/CSV]
按筛选条件导出，含变更前后值
用于审计场景
```

---

## 10. 报价单快照机制

### 10.1 快照内容（SUBMITTED 时）

```
事务内执行:

1. 锁定版本号:
   Quotation.referenced_versions = {
     "mat_process": {hf_part_no: version, ...},
     "mat_fee": {...},
     "plating_fee": {...},
     "element_price": {element_name: version, ...},
     "element_actual_prices": {element_name: 实际单价, ...},
     "basic_data_snapshot_at": NOW()
   }

2. 快照核价表:
   CostingSheet.rows = JSONB[每行产品的核价数据]
   CostingSheet.status = SNAPSHOT

3. 快照客户报价 Excel 视图:
   QuotationLineItem.excel_view_snapshot = {sheets: {...}}

4. 快照组件行数据:
   QuotationLineComponentData.row_data = [...]

5. 快照基础资料关键字段:
   QuotationLineItem.basic_snapshot = {
     unit_weight, part_name, specification,
     bom_summary: [...]
   }

6. 状态变 SUBMITTED
```

### 10.2 历史报价单数据访问

```
打开历史报价单:
  数据来源（按重要性）:
    1. CostingSheet.rows (snapshot) → 核价表展示
    2. QuotationLineItem.excel_view_snapshot → Excel 视图
    3. QuotationLineComponentData.row_data → 产品卡片
    4. QuotationLineItem.basic_snapshot → 基础资料显示
    5. Quotation.referenced_versions → 链接历史版本

通过 referenced_versions 可访问历史版本数据（只读）
```

### 10.3 数据来源追溯（报价单详情）

```
报价单详情页:
  Tab: [基本信息] [产品明细] [核价表] [比对差异] [数据来源] [审批历史] [操作日志]
                                              ↑ 新增

数据来源 Tab:
  - 客户资料引用版本（按表分组）
  - 元素单价引用版本 + 实际单价
  - 基础资料快照时间
  - [导出快照 Excel] / [导出快照 PDF]

字段级追溯:
  核价表/Excel视图 中每个值带 ⓘ 图标
  点击 → Popover 显示:
    - 数据值
    - 来源（哪个版本/哪个字段）
    - 计算方式（如适用）
```

---

## 11. 四视图体系

沿用 v4.0 设计：

```
报价生成器步骤二顶部:
  [产品卡片视图] [Excel视图] [核价表视图] [比对视图 ⚠N]

四视图共享底层数据，切换无需保存
```

详见 v4.0 章节 10。

---

## 12. 模板体系

### 12.1 两种模板

- **核价模板（CostingTemplate）**：内部成本核算，按产品分类绑定
- **客户报价模板（CustomerTemplate）**：对外报价，按客户+分类绑定，含产品卡片+Excel视图

### 12.2 公式引擎（v5 调整）

由于取消衍生字段，模板公式必须支持完整的跨表 LOOKUP：

```
公式语法:
  {variable_path}             变量路径
  [col_key]                   本表列引用
  LOOKUP(table, conditions, field)   跨表查询
  SUM / AVG / MAX / MIN / COUNT       聚合
  IF(condition, t, f)         条件
  + - * / ( )                 运算

变量路径示例:
  {元素BOM[元素='Ag'].组成含量(%)}
  解析为:
    SELECT composition_pct FROM mat_bom
    WHERE bom_type='ELEMENT' AND element_name='Ag' AND hf_part_no=:p
```

### 12.3 模板版本（沿用 v4.0）

- series_id + version + status
- 发布时快照 components_snapshot, excel_view_config 等
- 归档保护：被进行中报价单使用的不可归档

详见 v4.0 章节 7、12、15。

---

## 13. 审批流程

沿用 v4.0 章节 12 所有内容：

- 审批摘要卡片（毛利指标）
- 通过/退回 + 审批意见
- 审批撤回（APPROVED → DRAFT 经原审批人确认）
- "待我审批"列表增强（含毛利率、调整项数）
- 审批通知含毛利摘要
- 审批历史快照

---

## 14. 数据校验规则

### 14.1 校验级别

| 级别 | 失败处理 |
|------|---------|
| 格式校验（文件格式、Sheet 完整性、表头匹配） | 阻止整 Excel |
| 类型校验（数值/日期/必填） | 阻止整 Excel |
| 业务校验（合计 100%、范围、合理性） | 警告但允许，需用户明确确认 |
| 引用完整性（FK 存在性、字典值合法） | 阻止整 Excel |

### 14.2 配置性

```
核心校验规则: 硬编码（代码控制）
阈值类参数: 可配置
  - 数据完整度阈值（默认 80%）
  - 含量合计容差（默认 ±1%）
  - 损耗率上限（默认 100%）
```

### 14.3 校验报告 UI

导入预览页面顶部展示校验结果：
- ✓ 通过项
- ⚠ 警告项（业务校验）
- ✗ 阻塞项

---

## 15. 菜单与权限

### 15.1 菜单结构

```
仪表板
├── 快捷入口

客户管理
├── 客户列表
└── 定价策略

产品管理
├── 产品列表
├── 待分类产品                 (新增)
├── 生产料号管理
└── 产品分类管理

报价中心
├── 报价单管理
├── 报价生成器
└── 导入历史

主数据中心                     (新增一级菜单)
├── 主数据维护                 (独立入口)
├── 历史版本管理               (独立菜单)
├── 变更日志中心               (独立菜单)
└── 元素价格中心               (新增)

配置中心
├── 组件管理
├── 模板配置（客户报价模板）
├── 核价模板
├── 产品绑定
├── 版本比对
├── 基础数据配置
├── 数据源管理
├── 业务标签字典
└── 元素价格规则与来源        (新增)

系统管理
├── 用户管理
├── 区域/部门字典
├── 审批规则
└── 操作日志
```

### 15.2 权限矩阵（增量）

| 功能 | 销售代表 | 销售经理 | 定价经理 | 系统管理员 |
|------|---------|---------|---------|----------|
| 主数据维护（导入） | 可使用 | 可使用 | - | 可使用 |
| 待分类产品（分类指派） | - | ✓ | - | ✓ |
| 历史版本管理 | 仅查看 | 仅查看 | 仅查看 | 完全访问（含软删除）|
| 变更日志中心 | 仅本人 | 全部 | 全部 | 全部 |
| 元素价格中心 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 元素价格规则配置 | - | ✓ | - | ✓ |
| 数据来源追溯 | 仅本人报价单 | 全部 | 全部 | 全部 |
| 历史快照导出 | 仅本人 | 全部 | 全部 | 全部 |

详细权限沿用 v4.0 章节 17.2。

---

## 16. API 设计

### 16.1 主数据维护

```
POST   /api/cpq/master-data/import-preview         上传预览（含差异检测）
POST   /api/cpq/master-data/import-confirm         确认导入（带冲突解决）
PUT    /api/cpq/master-data/import-resolution     保存中间状态（冲突处理过程中）
```

### 16.2 历史版本

```
GET    /api/cpq/customer-data-versions
       筛选: customer_id, table_name, hf_part_no
GET    /api/cpq/customer-data-versions/{id}        版本详情
POST   /api/cpq/customer-data-versions/{id}/soft-delete  软删除
GET    /api/cpq/customer-data-versions/compare    版本对比（v1 vs v2）
```

### 16.3 变更日志

```
GET    /api/cpq/change-logs
       筛选: table, record_id, business_key, user, time_range, import_record
GET    /api/cpq/change-logs/export                 导出
GET    /api/cpq/import-records/{id}/changes        某次导入的所有变更
```

### 16.4 元素价格

```
GET    /api/cpq/element-price/sources              来源管理
POST   /api/cpq/element-price/sources
GET    /api/cpq/element-price/rules                规则管理
POST   /api/cpq/element-price/rules
GET    /api/cpq/element-price/daily               每日价格查询
POST   /api/cpq/element-price/daily/manual-fill   人工补录
GET    /api/cpq/element-price/center               价格中心数据（汇总+趋势+状态）
POST   /api/cpq/element-price/fetch-now            手动触发抓取
```

### 16.5 待分类产品

```
GET    /api/cpq/pending-products                   待分类产品列表
POST   /api/cpq/pending-products/batch-categorize  批量分类
PUT    /api/cpq/pending-products/{partNo}/category 单个分类
```

### 16.6 报价单数据来源

```
GET    /api/cpq/quotations/{id}/data-sources       数据来源详情
GET    /api/cpq/quotations/{id}/snapshot/export-excel  导出快照 Excel
GET    /api/cpq/quotations/{id}/snapshot/export-pdf    导出快照 PDF
```

### 16.7 沿用 v4.0 的 API

参见 v4.0 章节 18。

---

## 17. 非功能需求

- Excel 解析支持 .xlsx 格式（Apache POI）
- 单次导入支持最大 500 个产品行
- 导入预览响应时间 < 3s（500 行以内）
- 元素价格抓取超时 30s，重试 3 次
- 元素每日价格保留：永久（按需归档老数据）
- 变更日志保留 5 年
- 历史版本永不物理删除
- 原始 Excel 服务端保留 12 个月
- 中间状态自动保存间隔 500ms
- 产品级悲观锁超时 5 分钟（避免长时间占用）
- 沿用 v4.0 的其他非功能需求

---

## 18. TBD 清单

以下细节在本次设计中标注为 TBD，待后续讨论补完：

### 18.1 UI 设计细节

- **TBD-UI-1**: 字段级冲突处理 UI 详细布局
  - 表格设计、批量快捷按钮组织、按字段重要性的实现方式
- **TBD-UI-2**: 基础资料差异确认 UI
  - 整体预览页面的设计
- **TBD-UI-3**: 元素价格中心页面
  - 价格快览、趋势图、抓取状态、手动补录入口
- **TBD-UI-4**: 主数据维护页面（独立入口）
  - 与"创建报价单"流程的差异化设计
- **TBD-UI-5**: 版本对比工具
  - 左右对比 / 差异高亮的具体设计
- **TBD-UI-6**: 历史版本查询页面（独立菜单）
- **TBD-UI-7**: 变更日志中心页面（统一查询）
- **TBD-UI-8**: 报价单详情"数据来源"标签页
- **TBD-UI-9**: 字段级追溯 Popover

### 18.2 业务流程细节

- **TBD-BIZ-1**: 报价单复制的版本引用策略
- **TBD-BIZ-2**: 跨客户报价的料号差异化处理
- **TBD-BIZ-3**: 导入失败的回滚事务边界
- **TBD-BIZ-4**: 大批量导入（千行级）的性能策略
- **TBD-BIZ-5**: 完整的业务校验规则清单（含合理性检查）

### 18.3 技术实施细节

- **TBD-TECH-1**: 变量路径解析器的 BNF 语法定义
- **TBD-TECH-2**: 公式引擎扩展函数清单的精确语义
- **TBD-TECH-3**: HTML 抓取的实现方式（爬虫 vs 第三方 API）
- **TBD-TECH-4**: Flyway 扩列的事务边界与回滚策略
- **TBD-TECH-5**: 产品级悲观锁的具体实现（`SELECT FOR UPDATE` 粒度）
- **TBD-TECH-6**: 解析缓存与批量查询的实现方案
- **TBD-TECH-7**: 多级别的事务嵌套（导入大事务 + 子操作）

### 18.4 字典与配置

- **TBD-CONF-1**: 校验阈值的配置项清单（默认值）
- **TBD-CONF-2**: 字段重要性的标记机制（is_critical 或类似）
- **TBD-CONF-3**: fetch_rule_definition 的完整定义模式

---

## 19. 版本演进摘要

### v4.0 → v5.0 主要变更

| 变更项 | v4.0 | v5.0 |
|-------|------|------|
| 数据存储层 | ProductDataPool (JSONB) | 10 张物理合并表 |
| 衍生字段 | DerivedAttribute（LOOKUP/EXPRESSION/AGGREGATE） | 取消，全部上浮到模板公式 |
| 物理表扩列 | 隐式 | Flyway ALTER TABLE + 管理界面 |
| 数据分类 | 统一 | 基础资料 vs 客户资料 |
| 客户资料管理 | 整体导入 | 料号级版本迭代 |
| 元素价格 | 嵌入 mat_price | 独立子系统（source/rule/daily_price + element_price） |
| 报价单引用 | 引用 import_batch_id | DRAFT 跟随 + SUBMITTED 锁定 referenced_versions |
| 变更追踪 | 无 | basic_data_change_log（统一日志中心） |
| 待分类产品 | 无 | 自动创建队列 + 销售经理分类 |
| 漂移提示 | 无 | DRAFT 红色横幅 + 强制确认 |
| 数据来源追溯 | 无 | 报价单详情新增 Tab + 字段级追溯 |
| 元素价格抓取 | 无 | 每日定时 + 按需 + 手动兜底 |

### v5.0 设计原则

```
1. 物理表为唯一数据源（取消缓存层）
2. 主数据 vs 业务数据严格分离
3. 客户资料按料号版本化，可追溯可审计
4. 报价单 SUBMITTED 后数据完全自洽（快照机制）
5. 元素价格独立子系统，多源 + 多规则 + 每日抓取
6. 变更日志统一架构，全链路可追溯
7. UI 灵活度：自助配置（基础数据）+ 严格审计（变更日志）
```

---

## 附录：术语表

| 术语 | 说明 |
|------|------|
| 基础资料 | 不分客户、不版本化的主数据（mat_part / mat_bom / plating_plan / mat_customer_part_mapping） |
| 客户资料 | 带 customer_id 和版本号的数据（mat_process / mat_fee / plating_fee / element_price） |
| 料号级版本 | 客户资料的版本粒度，每个料号独立版本号 |
| 元素级版本 | element_price 的版本粒度，每个 (customer_id, element_name) 独立版本号 |
| 漂移检测 | DRAFT 报价单打开时检查基础数据是否更新 |
| 引用版本 | 报价单 SUBMITTED 时锁定的 referenced_versions |
| 实际单价 | 元素价格按 fetch_rule 计算 + premium_price 的最终值 |
| 待分类产品 | 自动创建但未指定 category_id 的产品 |
| 兜底配置 | element_price 中 element_name='ALL' 的配置（其他元素未配置时使用） |
| 修正模式 | 客户资料导入时不创建新版本，直接覆盖当前版本（用于错误修正） |
