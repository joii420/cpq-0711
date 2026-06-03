# CPQ 报价单 Excel 导入功能设计

> **日期**: 2026-04-21
> **状态**: 设计完成，待实施

---

## 1. 背景与目标

### 1.1 问题

销售代表在创建报价单时，添加产品需要手动填写大量字段（单个产品 40+ 列），效率低下。客户通常会发送自己格式的 Excel 报价模板（每个客户格式不同），销售代表需要逐项手动录入到 CPQ 系统中。

### 1.2 目标

- 支持从客户 Excel 文件批量导入产品数据到报价单
- 通过可配置的映射规则适配不同客户的 Excel 格式
- 导入后自动匹配我司生产料号，通过视觉反馈（绿色/红色边框）标识生产可行性
- 导入数据可编辑，允许用户修正不准确的 Excel 内容

### 1.3 核心设计决策

| 决策项 | 选择 | 理由 |
|-------|------|------|
| 整体方案 | 配置映射（方案A） | 数据准确性第一，不引入 AI 不确定性 |
| 导入粒度 | 一个 Excel = 一张报价单 | Excel 每行是一个产品 |
| 导入流程 | 先选模板再导入 | 确保所有产品使用同一模板，映射关系明确 |
| 配置架构 | 客户Excel模板 + 映射配置两层分离 | 客户Excel格式独立管理，一种格式可映射到多个CPQ模板 |
| 产品关联 | 不强制关联 Product 表 | Excel 中客户零件号映射为产品属性值，灵活适配新零件 |
| FORMULA/DATA_SOURCE | 不从 Excel 导入 | 仍走系统公式计算和数据源查询 |
| 工序处理 | 导入时跳过 | 模板已由映射配置锁定，工序可导入后补充 |

---

## 2. 数据模型

### 2.1 Product 表变更

```
Product {
  ...existing fields...
  sku → part_no (字段重命名：客户产品料号，用于导入映射和料号关联匹配)
  ...
}
```

### 2.2 新增 InternalMaterial（我司生产料号）

```
InternalMaterial {
  id: UUID (PK)
  material_no: String (UNIQUE, NOT NULL, 我司料号)
  name: String (NOT NULL, 品名)
  specification: String (规格)
  size: String (尺寸)
  status_code: Enum [Y, N] (Y=可生产, N=停产/不可用)
  created_at: Timestamp
  updated_at: Timestamp
}
```

### 2.3 新增 CustomerMaterialMapping（客户料号关联）

```
CustomerMaterialMapping {
  id: UUID (PK)
  customer_id: UUID (FK → Customer, NOT NULL)
  customer_part_no: String (NOT NULL, 客户产品料号)
  material_id: UUID (FK → InternalMaterial, NOT NULL)
  created_at: Timestamp
}

唯一约束: (customer_id, customer_part_no)
  → 同一客户的同一个客户料号只关联一个我司料号
```

### 2.4 新增 CustomerExcelTemplate（客户Excel模板）

```
CustomerExcelTemplate {
  id: UUID (PK)
  name: String (NOT NULL, 如"施耐德-标准报价格式2026")
  customer_id: UUID (FK → Customer, NOT NULL)
  description: String (描述说明，如"适用于施耐德铜合金产品线的报价模板")
  header_row_index: Integer (表头所在行号, 默认1)
  data_start_row_index: Integer (数据起始行号, 默认2)
  sheet_index: Integer (工作表序号, 默认0)
  part_no_column: String (NOT NULL, 客户料号所在列名, 如"Schneider Part Number")
  excel_columns: JSONB (NOT NULL, 样例Excel的列名有序列表, 如["S/N","Part Number","Drawing No.",...])
  sample_file_name: String (样例文件名，展示用)
  created_by: UUID (FK → User)
  created_at: Timestamp
  updated_at: Timestamp
}
```

### 2.5 新增 ImportMappingTemplate（导入映射配置）

```
ImportMappingTemplate {
  id: UUID (PK)
  name: String (NOT NULL, 如"施耐德标准格式 → 标准件卡片")
  excel_template_id: UUID (FK → CustomerExcelTemplate, NOT NULL)
  template_id: UUID (FK → Template, NOT NULL, CPQ产品卡片模板)
  column_mappings: JSONB (列映射规则)
  created_by: UUID (FK → User)
  created_at: Timestamp
  updated_at: Timestamp
}

唯一约束: (excel_template_id, template_id)
  → 同一个客户Excel模板 + 同一个CPQ模板只能有一个映射配置
```

### 2.6 column_mappings JSONB 结构

映射规则支持两种 target_type 和三种数据来源：

**target_type:**
- `PRODUCT_ATTRIBUTE` — 映射到产品属性（无行概念）
- `COMPONENT_FIELD` — 映射到组件字段（有行概念，需指定 row_index）

**数据来源:**
- `excel_column` 指定列名 — 从 Excel 读取值
- `fixed_value` 指定固定值 — 不从 Excel 读取，导入时自动填入固定值
- 两者都为 null — 未映射，导入时跳过

**FORMULA / DATA_SOURCE 类型字段不参与映射配置，导入后由系统自动计算/查询。**

```json
[
  // === 产品属性映射 ===
  {
    "excel_column": "Schneider Part Number",
    "target_type": "PRODUCT_ATTRIBUTE",
    "target_name": "产品名称"
  },
  {
    "excel_column": "Drawing No.",
    "target_type": "PRODUCT_ATTRIBUTE",
    "target_name": "规格型号"
  },
  {
    "excel_column": "Annual Usage",
    "target_type": "PRODUCT_ATTRIBUTE",
    "target_name": "数量"
  },

  // === 投料成本组件 - 第1行（Ag）===
  {
    "excel_column": null,
    "fixed_value": "Ag",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "物料",
    "row_index": 0
  },
  {
    "excel_column": "Ag%",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "含量",
    "row_index": 0
  },
  {
    "excel_column": "Ag loss%",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "材料损耗",
    "row_index": 0
  },
  {
    "excel_column": "Ag base cost USD/Kg",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "单价(USD/Kg)",
    "row_index": 0
  },

  // === 投料成本组件 - 第2行（Cu）===
  {
    "excel_column": null,
    "fixed_value": "Cu",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "物料",
    "row_index": 1
  },
  {
    "excel_column": "Copper%",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "含量",
    "row_index": 1
  },
  {
    "excel_column": "Cu base cost USD/Kg",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "单价(USD/Kg)",
    "row_index": 1
  },

  // === 投料成本组件 - 第3行（Fe）===
  {
    "excel_column": null,
    "fixed_value": "Fe",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "物料",
    "row_index": 2
  },
  {
    "excel_column": "Fe%",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "含量",
    "row_index": 2
  },
  {
    "excel_column": "Fe/ Zn7 or H62 base cost RMB/Kg",
    "target_type": "COMPONENT_FIELD",
    "target_component_code": "touLiao",
    "target_field_name": "单价(USD/Kg)",
    "row_index": 2
  }

  // === 其他组件同理 ===
]
```

### 2.7 新增 ImportRecord（导入记录）

```
ImportRecord {
  id: UUID (PK)
  quotation_id: UUID (FK → Quotation, nullable, 生成的报价单；FAILED时为NULL)
  customer_id: UUID (FK → Customer, NOT NULL)
  excel_template_id: UUID (FK → CustomerExcelTemplate, NOT NULL)
  mapping_template_id: UUID (FK → ImportMappingTemplate, NOT NULL)
  mapping_snapshot: JSONB (NOT NULL, 导入时映射配置的完整快照，防止配置后续修改影响追溯)
  original_file_name: String (NOT NULL, 原始文件名)
  original_file_path: String (NOT NULL, 服务端存储路径)
  total_rows: Integer (Excel 数据总行数)
  success_rows: Integer (成功导入行数)
  matched_rows: Integer (料号匹配成功行数)
  unmatched_rows: Integer (料号未匹配行数)
  import_status: Enum [SUCCESS, PARTIAL, FAILED]
  error_detail: JSONB (逐行错误明细，可为空)
  imported_by: UUID (FK → User, NOT NULL, 操作人)
  created_at: Timestamp
}
```

**import_status 规则：**
- `SUCCESS`：所有行全部导入成功
- `PARTIAL`：部分行有数据问题（空值/格式错误），跳过问题行，其余导入成功
- `FAILED`：表头校验失败或全部行失败，未生成报价单（quotation_id 为 NULL）

**error_detail 结构：**
```json
[
  {"row": 5, "excel_part_no": "AU7", "error": "Excel列 'Ag base cost' 值为空"},
  {"row": 12, "excel_part_no": "AU15", "error": "数值格式错误: 'N/A'"}
]
```

**原始文件存储规则：**
```
存储路径: data/imports/{customer_id}/{yyyy-MM}/{uuid}.xlsx
示例:    data/imports/c001-xxx/2026-04/a1b2c3d4.xlsx
```
- 原始 Excel 文件完整保留，不做修改
- 按客户 + 月份分目录
- 文件保留期限：12 个月（定时任务清理过期文件，ImportRecord 记录永久保留）

### 2.8 实体关系总览

```
Customer ──1:N──→ CustomerMaterialMapping ──N:1──→ InternalMaterial
Customer ──1:N──→ CustomerExcelTemplate ──1:N──→ ImportMappingTemplate ──N:1──→ Template
ImportMappingTemplate ──1:N──→ ImportRecord ──N:1──→ Quotation

示例:
施耐德客户
  ├── 料号关联
  │     ├── AU2 → M-10001 (status: Y)
  │     ├── AU3 → M-10002 (status: N)
  │     └── AU5 → (无匹配)
  │
  ├── 客户Excel模板
  │     ├── "施耐德-标准报价格式2026"（46列）
  │     │     ├── → 标准件卡片 v1.2（映射配置A）
  │     │     └── → 定制件卡片 v2.0（映射配置B）
  │     │
  │     └── "施耐德-原材料询价格式"（22列）
  │           └── → 原材料卡片 v1.0（映射配置C）
  │
  └── 导入记录
        ├── 2026-04-21 14:30 quote_q2.xlsx → QT-20260421-0015 (SUCCESS)
        └── 2026-04-19 16:00 test.xlsx → (无报价单) (FAILED)
```

---

## 3. 导入流程

### 3.1 入口

报价单管理页新增"从 Excel 新建报价单"按钮。

### 3.2 导入弹窗（步骤式）

```
步骤1：选择客户（搜索下拉）
  │    └→ 加载该客户下已注册的客户Excel模板列表
  │
步骤2：选择客户Excel模板（下拉）
  │    └→ 展示模板描述 + 列数 + 样例文件名
  │    └→ 用户根据手上的 Excel 文件判断选哪个
  │    └→ 无模板时提示"该客户暂无Excel模板，请联系销售经理注册"
  │    └→ 选择后加载该Excel模板下的映射配置列表
  │
步骤3：选择映射配置（下拉）
  │    └→ 展示关联的 CPQ 产品卡片模板名称 + 版本号
  │    └→ 若该Excel模板只有一个映射配置，自动选中跳过此步
  │    └→ 无映射配置时提示"该Excel模板暂未配置映射规则，请联系销售经理创建"
  │
步骤4：上传 Excel 文件
  │    └→ 后端按客户Excel模板配置的 sheet_index、header_row_index 解析表头
  │    └→ 校验表头与客户Excel模板的 excel_columns 是否匹配
  │    └→ 不匹配时提示具体差异列
  │
步骤5：预览解析结果
  │    ┌──────────────────────────────────────────────────┐
  │    │ 客户料号 │ 产品名称 │ 投料·Ag单价 │ ... │ 料号状态 │
  │    │ AU2     │ 触点座   │ 45.00     │     │ ✓ 匹配   │
  │    │ AU3     │ 弹簧片   │ 38.50     │     │ ✗ 停产   │
  │    │ AU5     │ 接线端子 │ 22.00     │     │ ✗ 未匹配 │
  │    └──────────────────────────────────────────────────┘
  │    └→ 底部统计：共X条，匹配成功X条，未匹配X条，停产X条
  │
步骤6：确认导入
        └→ 保存原始 Excel 文件到服务端
        └→ 生成 DRAFT 报价单
        └→ 创建 ImportRecord（记录映射快照、统计数据、问题明细）
        └→ 跳转报价生成器步骤二
```

### 3.3 导入后数据生成规则

每个 Excel 数据行生成：
1. 一个 `QuotationLineItem`（产品行，product_id 为空，template_id 为映射配置指定的模板）
2. `customer_part_no` 写入该行 part_no_column 列的值（用于料号匹配查询）
3. `product_attribute_values` 由 PRODUCT_ATTRIBUTE 映射填充（含客户料号对应的产品属性）
4. 多个 `QuotationLineComponentData`（每个组件一条），`row_data` 由 COMPONENT_FIELD 映射按 row_index 分组填充
5. FORMULA 字段不写入 row_data，前端加载后实时计算
6. DATA_SOURCE 字段写入 null，前端加载后触发查询

> **QuotationLineItem 新增字段**：`customer_part_no: String (nullable)`。Excel 导入时从 part_no_column 映射值写入；手动添加产品时从 Product.part_no 写入。此字段统一作为料号匹配查询的依据，编辑时修改此字段触发重新匹配和边框着色更新。

### 3.4 表头校验规则

导入步骤4解析 Excel 表头后，与客户Excel模板的 `excel_columns` 比对：
- 全部存在 → 校验通过，进入预览
- 部分缺失 → 提示"以下列在 Excel 中未找到：{列名列表}，请检查 Excel 文件是否匹配所选模板"，阻止继续

---

## 4. 料号匹配与着色

### 4.1 匹配逻辑

```sql
SELECT im.*
FROM customer_material_mapping cmm
JOIN internal_material im ON cmm.material_id = im.id
WHERE cmm.customer_id = :customerId
  AND cmm.customer_part_no = :partNo
```

### 4.2 着色规则

| 匹配结果 | 产品卡片边框 | 说明 |
|---------|------------|------|
| 匹配成功 且 status_code = Y | 绿色边框 | 可生产 |
| 匹配成功 且 status_code = N | 红色边框 | 已停产 |
| 匹配失败（关联表中无记录） | 红色边框 | 未找到对应料号 |

### 4.3 触发时机

料号匹配统一使用 `QuotationLineItem.customer_part_no` 字段查询：

- **Excel 导入时**：预览阶段批量查询，导入后 customer_part_no 已写入，卡片直接着色
- **手动添加产品时**：从 Product.part_no 写入 customer_part_no，自动查询匹配着色
- **编辑时**：修改 customer_part_no 对应的产品属性字段并失焦后，同步更新 customer_part_no，重新查询匹配，实时更新边框颜色

### 4.4 料号信息弹出卡片

产品卡片右上角新增"料号信息"按钮，点击弹出 Popover 小卡片。

**匹配成功时（status_code = Y）：**

| 字段 | 值 |
|------|-----|
| 客户料号 | AU2 |
| 我司料号 | M-10001 |
| 品名 | 触点座-铜合金 |
| 规格 | CuSn6-R |
| 尺寸 | 12×8×3mm |
| 状态 | ● 可生产(Y)（绿色） |

**匹配成功但 status_code = N：**

| 字段 | 值 |
|------|-----|
| 客户料号 | AU3 |
| 我司料号 | M-10002 |
| 品名 | 弹簧片-碳钢 |
| 规格 | 65Mn |
| 尺寸 | 20×5×0.8mm |
| 状态 | ● 已停产(N)（红色） |

**匹配失败：**

| 字段 | 值 |
|------|-----|
| 客户料号 | AU5 |
| 匹配状态 | 未找到关联料号 |
| 提示 | 请联系生产部门确认 |

---

## 5. 管理界面

### 5.1 生产料号管理

**菜单位置**：产品管理 → 生产料号管理

**权限**：销售经理 + 系统管理员完全访问，销售代表/定价经理仅查看

**功能：**
- 料号 CRUD（抽屉弹出编辑）
- 关键词搜索（料号 + 品名模糊匹配）
- 状态筛选（全部 / Y / N）
- Excel 批量导入料号
- 分页展示

### 5.2 客户料号关联管理

**入口位置**：客户详情抽屉 → 新增"料号关联"标签页

**功能：**
- 新增关联：输入客户料号 + 搜索选择我司料号
- 删除关联
- Excel 批量导入关联关系（两列：客户料号、我司料号）
- 关键词搜索

### 5.3 客户Excel模板管理 + 导入映射配置管理

**菜单位置**：配置中心 → 导入配置管理

**权限**：销售经理 + 系统管理员

**页面布局（左右分栏）：**

```
┌────────────────────────────────────────────────────────────┐
│ 导入配置管理                                                │
│────────────────────────────────────────────────────────────│
│ 左侧：客户Excel模板列表        │ 右侧：选中模板的映射配置列表 │
│                               │                            │
│ 客户: [全部 ▼]  [+ 新建模板]   │ [+ 新建映射]                │
│ ┌─────────────────────┐      │ ┌────────────────────────┐ │
│ │ 施耐德-标准报价格式   │ ←选中 │ │ → 标准件卡片 v1.2     │ │
│ │ 46列 | 2026-04-21   │      │ │   18列映射 | 3组件      │ │
│ ├─────────────────────┤      │ ├────────────────────────┤ │
│ │ 施耐德-原材料询价格式 │      │ │ → 定制件卡片 v2.0     │ │
│ │ 22列 | 2026-04-20   │      │ │   12列映射 | 2组件      │ │
│ ├─────────────────────┤      │ └────────────────────────┘ │
│ │ ABB-标准报价格式     │      │                            │
│ │ 30列 | 2026-04-19   │      │                            │
│ └─────────────────────┘      │                            │
└────────────────────────────────────────────────────────────┘
```

#### 5.3.1 注册客户Excel模板

点击"新建模板"弹出抽屉：

```
步骤1：填写基本信息
  │  模板名称（必填，如"施耐德-标准报价格式2026"）
  │  客户（下拉选择）
  │  描述（选填）
  │  表头行号（默认1）、数据起始行（默认2）、工作表序号（默认1）
  │
步骤2：上传样例 Excel
  │  └→ 后端按 sheet_index + header_row_index 解析表头
  │  └→ 返回 Excel 列名列表（有序），展示在页面上
  │  └→ 解析失败时提示"无法解析表头，请检查行号和工作表序号设置"
  │
步骤3：指定客户料号列
  │  └→ 下拉选择（选项来自步骤2解析出的 Excel 列名列表）
  │  └→ 此列用于导入时匹配客户料号关联表
  │
步骤4：确认保存
       └→ 保存模板名称、客户、解析参数、excel_columns、part_no_column
```

**解析结果预览：**

保存前展示解析出的列名列表，供用户确认：

```
┌──────────────────────────────────────────────────┐
│ 解析到 46 列:                                     │
│ 1. S/N+A2                                        │
│ 2. Schneider Part Number  ← [✓ 客户料号列]        │
│ 3. Drawing No.                                    │
│ 4. Part Description                               │
│ 5. Annual Usage                                   │
│ 6. Tooling                                        │
│ ...                                               │
└──────────────────────────────────────────────────┘
```

**编辑已有模板：**
- 模板名称、描述、解析参数可修改
- **客户不可修改**（防止与已有映射配置和导入记录的关联关系断裂）
- 可重新上传样例 Excel 更新列名列表（客户模板格式变更时使用）
- 更新列名列表时，系统检查已有映射配置是否受影响：若映射配置中引用的 excel_column 在新列名列表中不存在，提示"以下映射列在新模板中已不存在：{列名列表}，请更新对应映射配置"

**保存校验规则：**

| 校验项 | 规则 | 失败提示 |
|-------|------|---------|
| 模板名称 | 非空 | "请填写模板名称" |
| 客户 | 已选择 | "请选择客户" |
| 样例Excel | 已上传并解析成功 | "请上传样例Excel文件" |
| 客户料号列 | 已选择 | "请指定客户料号所在的Excel列" |
| 列名列表 | 至少1列 | "Excel解析结果为空，请检查文件" |

#### 5.3.2 创建/编辑映射配置

在右侧选中一个客户Excel模板后，点击"新建映射"进入映射配置编辑页（独立页面）。

**基本信息区：**
- 映射名称（必填，如"施耐德标准格式 → 标准件卡片"）
- 客户Excel模板：已锁定为左侧选中的模板（只读展示，显示名称+列数）
- CPQ产品卡片模板（下拉选择，仅 PUBLISHED 状态）
  - 选择后系统自动加载模板结构：
    - ① 产品属性列表（来自 template.product_attributes）
    - ② 所有组件及其字段（来自 template.components_snapshot）
  - 在下方映射配置区渲染出完整的映射表单

**映射配置区：**

选择CPQ模板后，映射配置区自动渲染为两大块：**产品属性映射** 和 **组件字段映射**。

**产品属性映射：**

系统列出模板定义的所有产品属性（如产品名称、规格型号、数量等），每个属性一行：

```
┌─────────────────────────────────────────────────────────────┐
│ ▸ 产品属性映射                                               │
│─────────────────────────────────────────────────────────────│
│ 产品属性名称  │ 数据来源         │ Excel列 / 固定值           │
│ 产品名称     │ [Excel列 ▼]     │ [Schneider Part Number ▼] │
│ 规格型号     │ [Excel列 ▼]     │ [Drawing No. ▼]           │
│ 数量        │ [Excel列 ▼]     │ [Annual Usage ▼]          │
│ 单位        │ [固定值 ▼]      │ [pcs          ]           │
│ 交货天数     │ [未映射 ▼]      │                           │
└─────────────────────────────────────────────────────────────┘
```

每行操作：
- "数据来源"下拉有三个选项：`Excel列` / `固定值` / `未映射`
- 选择 `Excel列` → 右侧出现下拉框，选项来自客户Excel模板的 excel_columns 列表
- 选择 `固定值` → 右侧出现文本输入框，手动输入值
- 选择 `未映射` → 右侧不显示任何输入（导入时该属性留空）

**组件字段映射：**

系统列出模板中所有组件（页签），每个组件可折叠/展开。每个组件下默认展示 1 行映射区块，可点击"添加行映射"新增行区块。

```
┌─────────────────────────────────────────────────────────────┐
│ ▸ 投料成本（组件编码: touLiao）                     [展开/折叠]│
│─────────────────────────────────────────────────────────────│
│                                                             │
│  ┌─ 第1行 ────────────────────────────────────────────────┐ │
│  │ 字段名称  │ 字段类型      │ 数据来源      │ 值           │ │
│  │ 物料     │ FIXED_VALUE  │ [固定值 ▼]   │ [Ag       ]  │ │
│  │ 含量     │ INPUT        │ [Excel列 ▼]  │ [Ag% ▼]      │ │
│  │ 材料损耗  │ INPUT        │ [Excel列 ▼]  │ [Ag loss% ▼] │ │
│  │ 单价     │ INPUT        │ [Excel列 ▼]  │ [Ag base.. ▼]│ │
│  │ 金额     │ FORMULA      │ (公式自动计算)                │ │
│  │                                          [× 删除此行]   │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ 第2行 ────────────────────────────────────────────────┐ │
│  │ 物料     │ FIXED_VALUE  │ [固定值 ▼]   │ [Cu       ]  │ │
│  │ 含量     │ INPUT        │ [Excel列 ▼]  │ [Copper% ▼]  │ │
│  │ 材料损耗  │ INPUT        │ [未映射 ▼]   │              │ │
│  │ 单价     │ INPUT        │ [Excel列 ▼]  │ [Cu base.. ▼]│ │
│  │ 金额     │ FORMULA      │ (公式自动计算)                │ │
│  │                                          [× 删除此行]   │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  [+ 添加行映射]                                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ ▸ 生产费用（组件编码: shengChan）                   [展开/折叠]│
│─────────────────────────────────────────────────────────────│
│  ┌─ 第1行 ────────────────────────────────────────────────┐ │
│  │ ...                                                    │ │
│  └────────────────────────────────────────────────────────┘ │
│  [+ 添加行映射]                                              │
└─────────────────────────────────────────────────────────────┘
```

每个组件行区块中的交互规则：

| 字段类型 | 数据来源可选项 | 说明 |
|---------|-------------|------|
| INPUT | Excel列 / 固定值 / 未映射 | 用户可自由选择 |
| FIXED_VALUE | Excel列 / 固定值 / 未映射 | 通常选"固定值"（如物料名），也可从Excel读取 |
| FORMULA | — | 自动显示"公式自动计算"，灰色不可操作 |
| DATA_SOURCE | — | 自动显示"数据源查询"，灰色不可操作 |

**"添加行映射"操作：**
- 点击后在该组件下新增一个行区块（行号自动递增）
- 新行区块包含该组件的所有字段，数据来源默认为"未映射"
- 用户逐字段配置数据来源
- 行区块可通过"删除此行"移除，至少保留 1 行

**保存校验规则：**

| 校验项 | 规则 | 失败提示 |
|-------|------|---------|
| 映射名称 | 非空 | "请填写映射名称" |
| CPQ模板 | 已选择 | "请选择产品卡片模板" |
| 产品属性映射 | 至少 1 个属性映射到 Excel列或固定值 | "请至少配置一个产品属性映射" |
| 组件字段映射 | 至少 1 个组件有至少 1 行有效映射（行中至少 1 个 INPUT/FIXED_VALUE 字段配置了 Excel列或固定值） | "请至少为一个组件配置字段映射" |
| 客户料号列映射 | 客户Excel模板的 part_no_column 必须被映射到某个产品属性 | "客户料号列'{列名}'未配置映射，请将其映射到一个产品属性" |
| Excel列唯一性 | 同一个 Excel列不可被映射到两个不同的目标（产品属性或组件字段） | "Excel列'{列名}'被重复映射，请检查" |
| 唯一约束 | (excel_template_id, template_id) 不重复 | "该Excel模板已有到此CPQ模板的映射配置" |

**编辑已有映射配置：**
- 映射规则按保存的数据回显
- 客户Excel模板（只读，不可修改）
- CPQ模板可修改（修改后映射配置区重新渲染，已有映射规则清空）

---

## 6. 导入记录与历史查询

### 6.1 入口

报价单管理页顶部操作栏新增"导入历史"按钮。

### 6.2 导入历史列表页

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 导入历史                                                                    │
│──────────────────────────────────────────────────────────────────────────────│
│ 客户: [全部 ▼]  状态: [全部 ▼]  日期: [____] ~ [____]                       │
│──────────────────────────────────────────────────────────────────────────────│
│ 导入时间     │ 操作人 │ 客户   │ Excel模板      │ 映射配置       │ 文件名        │ 总行│成功│匹配│ 状态    │ 操作       │
│ 04-21 14:30 │ 张三  │ 施耐德 │ 标准报价格式    │ →标准件卡片    │ quote_q2.xlsx │ 50 │ 48 │ 45 │ SUCCESS │ 详情 下载  │
│ 04-20 09:15 │ 李四  │ ABB   │ 标准报价格式    │ →定制件卡片    │ rfq_001.xlsx  │ 30 │ 30 │ 28 │ SUCCESS │ 详情 下载  │
│ 04-19 16:00 │ 张三  │ 施耐德 │ 标准报价格式    │ →标准件卡片    │ test.xlsx     │ 10 │ 0  │ 0  │ FAILED  │ 详情 下载  │
└──────────────────────────────────────────────────────────────────────────────┘
```

**功能：**
- 按客户、状态、日期范围筛选
- 分页展示
- 每行操作：查看详情、下载原始 Excel
- 销售代表仅查看本人导入记录，销售经理/系统管理员查看全部

### 6.3 导入详情页（抽屉弹出）

点击"详情"弹出抽屉，展示四个区域：

**基本信息区：**

| 字段 | 值 |
|------|-----|
| 导入时间 | 2026-04-21 14:30:25 |
| 操作人 | 张三 |
| 客户 | 施耐德 |
| Excel模板 | 施耐德-标准报价格式2026 |
| 映射配置 | 施耐德标准格式 → 标准件卡片 |
| 关联CPQ模板 | 标准件卡片 v1.2 |
| 关联报价单 | QT-20260421-0015（可点击跳转） |
| 原始文件 | quote_q2.xlsx（可下载） |

**导入统计区：**
- 总行数: 50 | 成功: 48 | 料号匹配: 45 | 未匹配: 3 | 失败: 2

**映射配置快照区（可展开/折叠）：**
- 展示导入时使用的映射配置完整内容
- 即使映射配置后来被修改/删除，此处仍保留导入时的原始配置
- 展示格式：产品属性映射 X 列 + 各组件行映射概要

**问题明细区（有问题行时展示）：**

```
│ 行号 │ 客户料号 │ 问题描述                           │
│ 5   │ AU7     │ Excel列 'Ag base cost' 值为空       │
│ 12  │ AU15    │ 数值格式错误: 'N/A'                  │
```

### 6.4 追溯场景

导入记录支持以下追溯场景：

1. **数据不对 → 是 Excel 原始问题还是映射问题？**
   - 下载原始 Excel 对照检查原始数据
   - 查看映射快照确认映射规则是否正确
   - 查看问题明细定位具体出错行和原因

2. **映射配置修改后 → 历史导入用的是哪个版本？**
   - mapping_snapshot 保留了导入时的完整映射配置
   - 不受后续配置修改影响

3. **报价单数据溯源 → 这张报价单是怎么来的？**
   - 通过 quotation_id 关联，从报价单可反查导入记录
   - 报价单详情页可展示"来源：Excel 导入"标签（若存在关联 ImportRecord）

---

## 7. 菜单与权限变更

### 7.1 菜单结构

```
产品管理（已有）
├── 产品列表（已有）
└── 生产料号管理（新增）

配置中心（已有）
├── 组件管理（已有）
├── 模板配置（已有）
├── 产品绑定（已有）
└── 导入配置管理（新增，含客户Excel模板 + 映射配置）

报价中心（已有）
├── 报价单管理（已有）
│     ├── [从 Excel 新建报价单] 按钮（新增）
│     └── [导入历史] 按钮（新增）
└── ...
```

### 7.2 权限矩阵补充

| 模块 | 销售代表 | 销售经理 | 定价经理 | 系统管理员 |
|------|---------|---------|---------|-----------|
| 生产料号管理 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 客户料号关联 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 客户Excel模板管理 | 无权访问 | 完全访问 | 无权访问 | 完全访问 |
| 导入映射配置 | 无权访问 | 完全访问 | 无权访问 | 完全访问 |
| Excel 导入报价单 | 可使用 | 可使用 | 无权访问 | 可使用 |
| 导入历史 | 仅本人记录 | 完全访问 | 无权访问 | 完全访问 |

---

## 8. API 设计概要

### 8.1 生产料号

```
GET    /api/cpq/internal-materials          列表（分页、搜索、状态筛选）
POST   /api/cpq/internal-materials          新增
PUT    /api/cpq/internal-materials/{id}     编辑
DELETE /api/cpq/internal-materials/{id}     删除
POST   /api/cpq/internal-materials/import   Excel批量导入
```

### 8.2 客户料号关联

```
GET    /api/cpq/customers/{customerId}/material-mappings          列表
POST   /api/cpq/customers/{customerId}/material-mappings          新增关联
DELETE /api/cpq/customers/{customerId}/material-mappings/{id}     删除关联
POST   /api/cpq/customers/{customerId}/material-mappings/import   Excel批量导入
GET    /api/cpq/customers/{customerId}/material-mappings/match    按客户料号匹配查询
```

### 8.3 客户Excel模板

```
GET    /api/cpq/customer-excel-templates                         列表（按客户筛选）
GET    /api/cpq/customer-excel-templates/{id}                    详情
POST   /api/cpq/customer-excel-templates                         新增
PUT    /api/cpq/customer-excel-templates/{id}                    编辑
DELETE /api/cpq/customer-excel-templates/{id}                    删除
POST   /api/cpq/customer-excel-templates/parse-header            上传Excel解析表头
```

### 8.4 导入映射配置

```
GET    /api/cpq/import-mapping-templates                         列表（按excel_template_id筛选）
GET    /api/cpq/import-mapping-templates/{id}                    详情
POST   /api/cpq/import-mapping-templates                         新增
PUT    /api/cpq/import-mapping-templates/{id}                    编辑
DELETE /api/cpq/import-mapping-templates/{id}                    删除
```

### 8.5 报价单 Excel 导入

```
POST   /api/cpq/quotations/import-excel     上传Excel+映射配置ID，返回解析预览数据
POST   /api/cpq/quotations/confirm-import   确认导入，生成DRAFT报价单+创建导入记录+保存原始文件
```

### 8.6 导入记录

```
GET    /api/cpq/import-records                   列表（分页、客户筛选、状态筛选、日期范围）
GET    /api/cpq/import-records/{id}              详情（含映射快照和问题明细）
GET    /api/cpq/import-records/{id}/download     下载原始Excel文件
```

---

## 9. 非功能需求

- Excel 解析支持 .xlsx 格式（Apache POI）
- 单次导入支持最大 500 个产品行
- 导入预览响应时间 < 3s（500 行以内）
- 料号匹配查询响应时间 < 200ms
- 生产料号 Excel 导入支持最大 5000 条/次
- 映射配置保存前校验：至少一个产品属性映射 + 至少一个组件字段映射
- 原始 Excel 文件服务端保留 12 个月，定时任务每月清理过期文件（ImportRecord 记录永久保留）
- 导入记录列表加载时间 < 500ms
