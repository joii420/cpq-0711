# CPQ 报价单 Excel 导入 + Excel 视图功能设计 v3.0

> **日期**: 2026-04-22
> **基于**: v2.0
> **状态**: 设计完成，待实施
> **核心变更**: 去掉 CustomerExcelTemplate 和 ImportMappingTemplate，Excel 视图配置和导入映射统一合并到 Template.excel_view_config，一个入口完成所有配置

---

## 1. 背景与目标

### 1.1 问题

1. 销售代表创建报价单时，手动填写大量字段效率低下，客户通常发送自己格式的 Excel 报价模板
2. 当前系统只有产品卡片视图，销售代表和客户习惯的 Excel 表格视角缺失
3. 客户 Excel 中的公式列（如 `=64*1%`）在产品卡片视图中被拆分为多个字段，结构差异导致导入和理解困难

### 1.2 目标

- 支持从客户 Excel 文件批量导入产品数据到报价单
- 新增 Excel 视图模式，所有报价单可在产品卡片视图和 Excel 视图间切换
- Excel 视图支持 Excel 原生公式，导出的 .xlsx 文件携带公式而非静态值
- 导入后自动匹配我司生产料号，通过绿色/红色边框标识生产可行性
- 导入数据可编辑，两种视图双向同步

### 1.3 核心设计决策

| 决策项 | 选择 | 理由 |
|-------|------|------|
| 整体方案 | 配置映射 | 数据准确性第一，不引入 AI 不确定性 |
| 导入粒度 | 一个 Excel = 一张报价单 | Excel 每行是一个产品 |
| 配置入口 | 统一在模板配置页的 Excel 视图标签页 | 一个入口完成 Excel 视图定义 + 导入映射 + 公式配置，不再需要独立的导入配置管理页面 |
| Excel视图配置位置 | 挂载在 CPQ 模板上 | 所有报价单（手动创建/导入）都能使用 Excel 视图 |
| 模板与客户 | 一个模板对应一个客户的一种 Excel 格式 | Template.excel_view_config 中包含 customer_id，导入时按客户过滤可选模板 |
| 双向编辑 | 同一份数据，两种呈现 | 产品卡片和 Excel 视图编辑同一份底层数据，实时同步 |
| 公式引擎 | 产品卡片用 JEXL，Excel视图用 Excel 原生公式 | 各自引擎计算，数据源相同，结果一致 |
| 公式结果存储 | 存储 | row_data 扩展存储 FORMULA 结果，excel_view_snapshot 存储 Excel 公式结果 |
| 产品关联 | 不强制关联 Product 表 | Excel 中客户零件号映射为产品属性值，灵活适配新零件 |
| 工序处理 | 导入时跳过 | 模板已确定，工序可导入后补充 |

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

### 2.4 Template 表扩展

```
Template {
  ...existing fields...

  excel_view_config: JSONB (Excel视图配置，含列定义+公式+导入参数，新增字段)
}
```

**excel_view_config 完整结构：**

```json
{
  "customer_id": "uuid-customer-schneider",
  "import_settings": {
    "header_row_index": 1,
    "data_start_row_index": 2,
    "sheet_index": 0,
    "part_no_column_key": "B",
    "sample_file_name": "schneider_quote_template.xlsx"
  },
  "columns": [
    {
      "col_key": "A",
      "title": "S/N",
      "source_type": "PRODUCT_ATTRIBUTE",
      "source_name": "序号"
    },
    {
      "col_key": "B",
      "title": "Schneider Part Number",
      "source_type": "PRODUCT_ATTRIBUTE",
      "source_name": "产品名称"
    },
    {
      "col_key": "C",
      "title": "Drawing No.",
      "source_type": "PRODUCT_ATTRIBUTE",
      "source_name": "规格型号"
    },
    {
      "col_key": "D",
      "title": "Annual Usage",
      "source_type": "PRODUCT_ATTRIBUTE",
      "source_name": "数量"
    },
    {
      "col_key": "E",
      "title": "Ag%",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "含量",
      "row_index": 0
    },
    {
      "col_key": "F",
      "title": "Ag base cost USD/Kg",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "单价",
      "row_index": 0
    },
    {
      "col_key": "G",
      "title": "Ag total cost",
      "source_type": "EXCEL_FORMULA",
      "formula": "=E{row}*F{row}"
    },
    {
      "col_key": "H",
      "title": "Copper%",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "含量",
      "row_index": 1
    },
    {
      "col_key": "I",
      "title": "Cu base cost USD/Kg",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "单价",
      "row_index": 1
    },
    {
      "col_key": "J",
      "title": "Unit",
      "source_type": "FIXED_VALUE",
      "value": "USD/Kg"
    }
  ]
}
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| customer_id | 该模板关联的客户ID，导入时按此过滤可选模板 |
| import_settings.header_row_index | Excel 表头所在行号，默认1 |
| import_settings.data_start_row_index | 数据起始行号，默认2 |
| import_settings.sheet_index | 工作表序号，默认0 |
| import_settings.part_no_column_key | 客户料号所在列的 col_key，用于导入时匹配料号 |
| import_settings.sample_file_name | 上传的样例文件名，展示用 |
| columns | 列定义数组，有序 |

**列的 source_type 四种类型：**

| source_type | 说明 | 可编辑 | 导入时行为 |
|------------|------|--------|-----------|
| PRODUCT_ATTRIBUTE | 映射到产品属性 | 是 | 从 Excel 读取值写入产品属性 |
| COMPONENT_FIELD | 映射到组件字段（含 row_index） | 是 | 从 Excel 读取值写入组件行数据 |
| EXCEL_FORMULA | Excel 公式，引用其他列计算 | 否（自动计算） | 不从 Excel 读取，由公式自动计算 |
| FIXED_VALUE | 固定值 | 否 | 不从 Excel 读取，使用配置的固定值 |

**公式中的 `{row}` 占位符：**
- 渲染时替换为实际行号（如第 1 个产品 row=2，第 2 个产品 row=3）
- 导出 Excel 时同理，`=E{row}*F{row}` → `=E2*F2`

**发布时快照：**
- 模板发布时，excel_view_config 和 components_snapshot 同等对待，冻结到发布版本中
- 已发布模板的 excel_view_config 不可修改
- 报价单使用发布时的快照数据渲染 Excel 视图

**发布时校验：**
- excel_view_config 可以为空（不配置 Excel 视图，模板正常使用产品卡片视图）
- 若已配置：
  - part_no_column_key 引用的 col_key 必须存在且 source_type 为 PRODUCT_ATTRIBUTE
  - EXCEL_FORMULA 的 formula 中引用的列 col_key 必须存在
  - COMPONENT_FIELD 引用的 component_code 和 field_name 必须在模板的 components_snapshot 中存在
  - PRODUCT_ATTRIBUTE 引用的 source_name 必须在模板的 product_attributes 中存在

### 2.5 QuotationLineItem 扩展

```
QuotationLineItem {
  ...existing fields...

  customer_part_no: String (nullable, 客户料号值；
    Excel导入时从 part_no_column_key 对应列的值写入；
    手动添加产品时从 Product.part_no 写入；
    用于料号匹配查询和编辑时重新匹配的依据)

  excel_view_snapshot: JSONB (nullable, Excel视图所有列的值快照，含公式计算结果；
    每次保存/提交时更新)
}
```

**excel_view_snapshot 结构：**

```json
{
  "A": "001",
  "B": "AU2",
  "C": "DWG-001",
  "D": 50000,
  "E": 0.05,
  "F": 64,
  "G": 3.2,
  "H": 0.60,
  "I": 38.5,
  "J": "USD/Kg"
}
```

### 2.6 QuotationLineComponentData.row_data 扩展

```
原 row_data（只存 INPUT/FIXED_VALUE）：
  [{"row_index": 0, "材料编码": "M001", "使用数量": 5}]

扩展后 row_data（也存 FORMULA 计算结果）：
  [{"row_index": 0, "材料编码": "M001", "使用数量": 5, "小计": 250.00}]
```

FORMULA 字段的计算结果也写入 row_data，作为数据快照。前端加载时仍实时重算，但保存时将最新计算结果写入。

### 2.7 新增 ImportRecord（导入记录）

```
ImportRecord {
  id: UUID (PK)
  quotation_id: UUID (FK → Quotation, nullable, FAILED时为NULL)
  customer_id: UUID (FK → Customer, NOT NULL)
  template_id: UUID (FK → Template, NOT NULL, 使用的CPQ模板)
  config_snapshot: JSONB (NOT NULL, 导入时 excel_view_config 的完整快照)
  original_file_name: String (NOT NULL, 原始文件名)
  original_file_path: String (NOT NULL, 服务端存储路径)
  total_rows: Integer (Excel 数据总行数)
  success_rows: Integer (成功导入行数)
  matched_rows: Integer (料号匹配成功行数)
  unmatched_rows: Integer (料号未匹配行数)
  import_status: Enum [SUCCESS, PARTIAL, FAILED]
  error_detail: JSONB (逐行错误明细，可为空)
  imported_by: UUID (FK → User, NOT NULL)
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
Template.excel_view_config.customer_id ──→ Customer（过滤关联）
Template.excel_view_config ──定义──→ Excel视图列结构 + 公式 + 导入参数
ImportRecord ──N:1──→ Template
ImportRecord ──N:1──→ Quotation

数据流:
  配置: 上传样例Excel → 解析列名 → 逐列配置数据来源 → 保存为 excel_view_config
  导入: 客户Excel → excel_view_config.columns 解析 → CPQ 组件字段/产品属性
  渲染: CPQ 组件字段/产品属性 → excel_view_config.columns → Excel 视图
  导出: CPQ 组件字段/产品属性 → excel_view_config.columns → .xlsx 文件（含公式）
```

---

## 3. Excel 视图

### 3.1 视图切换

报价生成器步骤二顶部新增视图切换按钮：

```
[产品卡片视图] | [Excel视图]
```

- 两种视图切换时无需保存，共享同一份内存数据
- 默认显示产品卡片视图
- 模板未配置 excel_view_config 时，Excel视图按钮置灰，提示"该模板未配置Excel视图"

### 3.2 Excel 视图渲染

使用前端电子表格组件（如 Luckysheet / Handsontable）渲染：

```
┌──────────────────────────────────────────────────────────────────────┐
│ [产品卡片视图] | [Excel视图(当前)]                      [导出Excel]   │
│──────────────────────────────────────────────────────────────────────│
│     │ A    │ B              │ C          │ D     │ E    │ F        │ G          │
│     │ S/N  │ Part Number    │ Drawing No.│ Usage │ Ag%  │ Ag price │ Ag cost    │
│─────┼──────┼────────────────┼────────────┼───────┼──────┼──────────┼────────────│
│  1  │ 001  │ AU2            │ DWG-001    │ 50000 │ 5%   │ 64       │ =E1*F1     │
│  2  │ 002  │ AU3            │ DWG-002    │ 30000 │ 3%   │ 45       │ =E2*F2     │
│  3  │ 003  │ AU5            │ DWG-003    │ 10000 │ 8%   │ 72       │ =E3*F3     │
└──────────────────────────────────────────────────────────────────────┘

列背景色区分：
  白色：可编辑列（PRODUCT_ATTRIBUTE / COMPONENT_FIELD）
  浅灰：公式列（EXCEL_FORMULA，只读，显示计算结果）
  浅蓝：固定值列（FIXED_VALUE，只读）
```

每行 = 一个产品（QuotationLineItem）
列结构来自模板的 excel_view_config.columns

### 3.3 双向同步机制

**Excel 视图 → 产品卡片视图：**
1. 用户在 Excel 视图编辑一个单元格（如修改 E1 = "8%"）
2. 系统通过 excel_view_config 查找该列的映射关系（E列 → 投料成本.含量.row_index=0）
3. 更新底层数据（QuotationLineComponentData.row_data 对应字段）
4. 产品卡片视图的对应字段同步更新
5. 产品卡片的 FORMULA 字段自动重算（JEXL）
6. Excel 视图的 EXCEL_FORMULA 列自动重算（表格组件）

**产品卡片视图 → Excel 视图：**
1. 用户在产品卡片编辑一个字段（如修改投料成本·第1行·含量 = "8%"）
2. 更新底层数据
3. 系统通过 excel_view_config 反向查找该字段对应的 Excel 视图列（含量.row_index=0 → E列）
4. Excel 视图对应单元格更新
5. 两边公式各自重算

**同步规则：**
- 只有 PRODUCT_ATTRIBUTE 和 COMPONENT_FIELD 类型的列/字段参与双向同步
- EXCEL_FORMULA 列只在 Excel 视图中存在和计算，不影响产品卡片
- 产品卡片的 FORMULA 字段只在卡片视图中计算，不影响 Excel 视图
- 两边公式各自独立计算，数据源相同，结果应一致

### 3.4 Excel 导出

导出 .xlsx 时（Apache POI），按 excel_view_config 生成 Excel 文件：

- PRODUCT_ATTRIBUTE / COMPONENT_FIELD 列：写入数据值
- EXCEL_FORMULA 列：写入公式（`cell.setCellFormula("E2*F2")`），不是数值
- FIXED_VALUE 列：写入固定值
- 客户打开导出的 Excel 能看到公式并支持重算

---

## 4. 导入流程

### 4.1 入口

报价单管理页新增"从 Excel 新建报价单"按钮。

### 4.2 导入弹窗（步骤式）

```
步骤1：选择客户（搜索下拉）
  │    └→ 按 excel_view_config.customer_id 过滤，加载该客户关联的模板列表
  │
步骤2：选择CPQ模板（下拉，仅已配置 excel_view_config 且关联该客户的模板）
  │    └→ 展示模板名称 + 版本号 + 样例文件名
  │    └→ 若只有一个模板，自动选中
  │    └→ 无可用模板时提示"该客户暂无可导入的模板配置，请联系销售经理配置"
  │
步骤3：上传 Excel 文件
  │    └→ 后端按 import_settings 的 sheet_index、header_row_index 解析表头
  │    └→ 校验表头列名与 excel_view_config.columns 的 title 是否匹配
  │    └→ 仅校验 PRODUCT_ATTRIBUTE 和 COMPONENT_FIELD 类型列（可导入列）
  │    └→ 不匹配时提示具体差异列
  │
步骤4：预览解析结果
  │    ┌───────────────────────────────────────────────────────────┐
  │    │ 客户料号 │ 产品名称 │ Ag%  │ Ag price │ Ag cost │ 料号状态 │
  │    │ AU2     │ 触点座   │ 5%   │ 64      │ 3.2    │ ✓ 匹配   │
  │    │ AU3     │ 弹簧片   │ 3%   │ 45      │ 1.35   │ ✗ 停产   │
  │    │ AU5     │ 接线端子 │ 8%   │ 72      │ 5.76   │ ✗ 未匹配 │
  │    └───────────────────────────────────────────────────────────┘
  │    └→ EXCEL_FORMULA 列在预览中展示计算结果
  │    └→ 底部统计：共X条，匹配成功X条，未匹配X条，停产X条
  │
步骤5：确认导入
        └→ 保存原始 Excel 文件到服务端
        └→ 生成 DRAFT 报价单
        └→ 创建 ImportRecord（快照 excel_view_config、统计数据、问题明细）
        └→ 跳转报价生成器步骤二
```

### 4.3 导入数据流转

```
客户 Excel 文件
  │
  ├─ 按 excel_view_config.columns 解析每列
  │   列 title 匹配 Excel 表头 → 读取该列数据
  │   PRODUCT_ATTRIBUTE → 写入 product_attribute_values
  │   COMPONENT_FIELD → 按 component_code + field_name + row_index 写入 row_data
  │   EXCEL_FORMULA → 不从 Excel 读取，由公式自动计算后写入 excel_view_snapshot
  │   FIXED_VALUE → 不从 Excel 读取，使用配置值写入 excel_view_snapshot
  │
  ├─ 写入 CPQ 数据:
  │   ├─ QuotationLineItem.product_attribute_values（产品属性）
  │   ├─ QuotationLineItem.customer_part_no（从 part_no_column_key 对应列读取）
  │   ├─ QuotationLineItem.excel_view_snapshot（Excel视图全列快照，含公式结果）
  │   └─ QuotationLineComponentData.row_data（组件行数据，含 FORMULA 结果）
  │
  └─ 前端加载后:
      ├─ 产品卡片视图：FORMULA 字段实时重算（JEXL）
      ├─ Excel 视图：EXCEL_FORMULA 列实时重算（表格组件）
      └─ DATA_SOURCE 字段触发查询
```

### 4.4 表头校验规则

导入步骤3解析 Excel 表头后，校验 excel_view_config.columns 中所有 PRODUCT_ATTRIBUTE 和 COMPONENT_FIELD 类型列的 title 是否在 Excel 表头中存在：
- 全部存在 → 校验通过，进入预览
- 部分缺失 → 提示"以下列在 Excel 中未找到：{列名列表}，请检查 Excel 文件是否匹配所选模板"，阻止继续

---

## 5. 料号匹配与着色

### 5.1 匹配逻辑

```sql
SELECT im.*
FROM customer_material_mapping cmm
JOIN internal_material im ON cmm.material_id = im.id
WHERE cmm.customer_id = :customerId
  AND cmm.customer_part_no = :partNo
```

### 5.2 着色规则

| 匹配结果 | 产品卡片边框 | 说明 |
|---------|------------|------|
| 匹配成功 且 status_code = Y | 绿色边框 | 可生产 |
| 匹配成功 且 status_code = N | 红色边框 | 已停产 |
| 匹配失败（关联表中无记录） | 红色边框 | 未找到对应料号 |

### 5.3 触发时机

料号匹配统一使用 `QuotationLineItem.customer_part_no` 字段查询：

- **Excel 导入时**：预览阶段批量查询，导入后 customer_part_no 已写入，卡片直接着色
- **手动添加产品时**：从 Product.part_no 写入 customer_part_no，自动查询匹配着色
- **编辑时**：修改 customer_part_no 对应的产品属性字段并失焦后，同步更新 customer_part_no，重新查询匹配，实时更新边框颜色

### 5.4 料号信息弹出卡片

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

## 6. 管理界面

### 6.1 生产料号管理

**菜单位置**：产品管理 → 生产料号管理

**权限**：销售经理 + 系统管理员完全访问，销售代表/定价经理仅查看

**功能：**
- 料号 CRUD（抽屉弹出编辑）
- 关键词搜索（料号 + 品名模糊匹配）
- 状态筛选（全部 / Y / N）
- Excel 批量导入料号
- 分页展示

### 6.2 客户料号关联管理

**入口位置**：客户详情抽屉 → 新增"料号关联"标签页

**功能：**
- 新增关联：输入客户料号 + 搜索选择我司料号
- 删除关联
- Excel 批量导入关联关系（两列：客户料号、我司料号）
- 关键词搜索

### 6.3 模板 Excel 视图配置（统一配置入口）

**入口位置**：模板配置页面 → 新增"Excel视图配置"标签页

```
模板配置页面
├── 产品属性（已有）
├── 组件画布（已有）
├── 产品小计公式（已有）
└── Excel视图配置（新增）
```

#### 6.3.1 配置流程

```
步骤1：选择关联客户（下拉选择，用于导入时过滤模板）
  │
步骤2：填写导入参数
  │  表头行号（默认1）、数据起始行（默认2）、工作表序号（默认1）
  │
步骤3：上传客户的样例 Excel
  │  └→ 后端按导入参数解析表头
  │  └→ 返回 Excel 列名列表（有序），填充到下方列配置表格的"列标题"列
  │  └→ 解析失败时提示"无法解析表头，请检查导入参数设置"
  │
步骤4：逐列配置数据来源
  │  └→ 系统自动用解析出的列名生成配置表格
  │  └→ 用户逐列选择数据来源和映射目标
  │
步骤5：指定客户料号列
  │  └→ 从列 col_key 中选择（仅 PRODUCT_ATTRIBUTE 类型的列可选）
  │
步骤6：保存
```

#### 6.3.2 列配置表格

```
┌──────────────────────────────────────────────────────────────────┐
│ Excel视图配置                                    [预览] [+ 添加列]│
│──────────────────────────────────────────────────────────────────│
│ 关联客户: [施耐德 ▼]                                              │
│ 表头行号: [1]  数据起始行: [2]  工作表序号: [1]                     │
│ 样例文件: schneider_template.xlsx  [重新上传]                      │
│ 客户料号列: [B - Schneider Part Number ▼]                         │
│──────────────────────────────────────────────────────────────────│
│ 列Key │ 列标题(来自Excel)      │ 数据来源         │ 映射目标/值           │
│ A    │ S/N                   │ [产品属性 ▼]     │ [序号 ▼]             │
│ B    │ Schneider Part Number │ [产品属性 ▼]     │ [产品名称 ▼]          │
│ C    │ Drawing No.           │ [产品属性 ▼]     │ [规格型号 ▼]          │
│ D    │ Annual Usage          │ [产品属性 ▼]     │ [数量 ▼]             │
│ E    │ Ag%                   │ [组件字段 ▼]     │ [投料成本·含量·行1 ▼]  │
│ F    │ Ag base cost USD/Kg   │ [组件字段 ▼]     │ [投料成本·单价·行1 ▼]  │
│ G    │ Ag total cost         │ [Excel公式 ▼]    │ [=E{row}*F{row}     ]│
│ H    │ Copper%               │ [组件字段 ▼]     │ [投料成本·含量·行2 ▼]  │
│ I    │ Cu base cost USD/Kg   │ [组件字段 ▼]     │ [投料成本·单价·行2 ▼]  │
│ J    │ Unit                  │ [固定值 ▼]       │ [USD/Kg             ]│
│ K    │ Subtotal              │ [Excel公式 ▼]    │ [=G{row}+...        ]│
│──────────────────────────────────────────────────────────────────│
│ 每行操作: [↑][↓] 排序  [× 删除]                                    │
│──────────────────────────────────────────────────────────────────│
│ [+ 添加列] — 手动添加不在Excel中的列（如额外的公式列或固定值列）        │
└──────────────────────────────────────────────────────────────────┘
```

**交互规则：**
- 上传 Excel 后，系统自动生成列配置行（一列一行），列标题预填 Excel 表头值
- "数据来源"下拉四选一：产品属性 / 组件字段 / Excel公式 / 固定值
- 选择"产品属性" → 右侧下拉列出模板的 product_attributes
- 选择"组件字段" → 右侧下拉展示"组件名·字段名·行N"（仅 INPUT/FIXED_VALUE 类型字段）
- 选择"Excel公式" → 右侧文本输入框，使用 `{row}` 占位符（如 `=E{row}*F{row}`）
- 选择"固定值" → 右侧文本输入框
- 列可添加/删除/排序
- col_key 自动按 A、B、C... 分配
- 不需要映射的 Excel 列可以删除该行（导入时跳过该列）

**预览功能：**
点击"预览"展示模拟 Excel 表格，填入示例数据，验证公式和映射是否正确。

#### 6.3.3 保存校验规则

| 校验项 | 规则 | 失败提示 |
|-------|------|---------|
| 关联客户 | 已选择 | "请选择关联客户" |
| 样例Excel | 已上传并解析成功 | "请上传客户的样例Excel文件" |
| 客户料号列 | 已选择且对应列 source_type 为 PRODUCT_ATTRIBUTE | "请指定客户料号列，且该列必须映射到产品属性" |
| 至少一列映射 | 至少 1 个 PRODUCT_ATTRIBUTE 或 COMPONENT_FIELD 列 | "请至少配置一列数据映射" |
| 公式引用校验 | EXCEL_FORMULA 中引用的 col_key 必须存在 | "公式引用了不存在的列'{col_key}'" |
| 组件字段校验 | COMPONENT_FIELD 引用的组件和字段必须在模板中存在 | "组件字段'{组件名·字段名}'在模板中不存在" |
| 产品属性校验 | PRODUCT_ATTRIBUTE 引用的属性名必须在模板中存在 | "产品属性'{属性名}'在模板中不存在" |

#### 6.3.4 编辑已有配置

- 关联客户不可修改（防止影响已有导入记录和报价单）
- 可重新上传 Excel 更新列结构（列名变更时自动更新列标题，映射关系保留）
- 导入参数（行号/工作表）可修改

---

## 7. 导入记录与历史查询

### 7.1 入口

报价单管理页顶部操作栏新增"导入历史"按钮。

### 7.2 导入历史列表页

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 导入历史                                                                    │
│──────────────────────────────────────────────────────────────────────────────│
│ 客户: [全部 ▼]  状态: [全部 ▼]  日期: [____] ~ [____]                       │
│──────────────────────────────────────────────────────────────────────────────│
│ 导入时间     │ 操作人 │ 客户   │ 模板           │ 文件名        │ 总行│成功│匹配│ 状态    │ 操作       │
│ 04-22 14:30 │ 张三  │ 施耐德 │ 施耐德标准件v1.2│ quote_q2.xlsx │ 50 │ 48 │ 45 │ SUCCESS │ 详情 下载  │
│ 04-21 09:15 │ 李四  │ ABB   │ ABB定制件v2.0  │ rfq_001.xlsx  │ 30 │ 30 │ 28 │ SUCCESS │ 详情 下载  │
│ 04-20 16:00 │ 张三  │ 施耐德 │ 施耐德标准件v1.2│ test.xlsx     │ 10 │ 0  │ 0  │ FAILED  │ 详情 下载  │
└──────────────────────────────────────────────────────────────────────────────┘
```

**功能：**
- 按客户、状态、日期范围筛选
- 分页展示
- 每行操作：查看详情、下载原始 Excel
- 销售代表仅查看本人导入记录，销售经理/系统管理员查看全部

### 7.3 导入详情页（抽屉弹出）

**基本信息区：**

| 字段 | 值 |
|------|-----|
| 导入时间 | 2026-04-22 14:30:25 |
| 操作人 | 张三 |
| 客户 | 施耐德 |
| CPQ模板 | 施耐德标准件卡片 v1.2 |
| 关联报价单 | QT-20260422-0015（可点击跳转） |
| 原始文件 | quote_q2.xlsx（可下载） |

**导入统计区：**
- 总行数: 50 | 成功: 48 | 料号匹配: 45 | 未匹配: 3 | 失败: 2

**配置快照区（可展开/折叠）：**
- 展示导入时 excel_view_config 的完整内容
- 即使模板后来被修改/新版本发布，此处仍保留导入时的原始配置

**问题明细区（有问题行时展示）：**

```
│ 行号 │ 客户料号 │ 问题描述                           │
│ 5   │ AU7     │ Excel列 'Ag base cost' 值为空       │
│ 12  │ AU15    │ 数值格式错误: 'N/A'                  │
```

### 7.4 追溯场景

1. **数据不对 → 是 Excel 原始问题还是配置问题？**
   - 下载原始 Excel 对照检查原始数据
   - 查看配置快照确认列映射和公式是否正确

2. **模板修改后 → 历史导入用的是哪个版本配置？**
   - config_snapshot 保留导入时的完整 excel_view_config

3. **报价单数据溯源 → 这张报价单是怎么来的？**
   - 通过 quotation_id 关联，报价单详情页展示"来源：Excel 导入"标签

---

## 8. 菜单与权限变更

### 8.1 菜单结构

```
产品管理（已有）
├── 产品列表（已有）
└── 生产料号管理（新增）

配置中心（已有）
├── 组件管理（已有）
├── 模板配置（已有，扩展 Excel 视图配置标签页）
└── 产品绑定（已有）

报价中心（已有）
├── 报价单管理（已有）
│     ├── [从 Excel 新建报价单] 按钮（新增）
│     └── [导入历史] 按钮（新增）
└── 报价生成器步骤二（已有，新增 Excel 视图切换）
```

### 8.2 权限矩阵补充

| 模块 | 销售代表 | 销售经理 | 定价经理 | 系统管理员 |
|------|---------|---------|---------|-----------|
| 生产料号管理 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 客户料号关联 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 模板Excel视图配置 | 无权访问 | 完全访问 | 无权访问 | 完全访问 |
| Excel 导入报价单 | 可使用 | 可使用 | 无权访问 | 可使用 |
| Excel 视图查看/编辑 | 可使用 | 可使用 | 仅查看 | 可使用 |
| 导入历史 | 仅本人记录 | 完全访问 | 无权访问 | 完全访问 |

---

## 9. API 设计概要

### 9.1 生产料号

```
GET    /api/cpq/internal-materials          列表（分页、搜索、状态筛选）
POST   /api/cpq/internal-materials          新增
PUT    /api/cpq/internal-materials/{id}     编辑
DELETE /api/cpq/internal-materials/{id}     删除
POST   /api/cpq/internal-materials/import   Excel批量导入
```

### 9.2 客户料号关联

```
GET    /api/cpq/customers/{customerId}/material-mappings          列表
POST   /api/cpq/customers/{customerId}/material-mappings          新增关联
DELETE /api/cpq/customers/{customerId}/material-mappings/{id}     删除关联
POST   /api/cpq/customers/{customerId}/material-mappings/import   Excel批量导入
GET    /api/cpq/customers/{customerId}/material-mappings/match    按客户料号匹配查询
```

### 9.3 模板 Excel 视图配置

```
POST   /api/cpq/templates/{id}/excel-view-config/parse-header    上传样例Excel解析表头
PUT    /api/cpq/templates/{id}/excel-view-config                 保存Excel视图配置
GET    /api/cpq/templates/{id}/excel-view-config                 获取Excel视图配置
```

### 9.4 报价单 Excel 导入

```
POST   /api/cpq/quotations/import-excel          上传Excel + 模板ID，返回解析预览数据
POST   /api/cpq/quotations/confirm-import        确认导入，生成DRAFT报价单 + 创建导入记录
```

### 9.5 导入记录

```
GET    /api/cpq/import-records                   列表（分页、客户筛选、状态筛选、日期范围）
GET    /api/cpq/import-records/{id}              详情（含配置快照和问题明细）
GET    /api/cpq/import-records/{id}/download     下载原始Excel文件
```

### 9.6 Excel 视图数据

```
GET    /api/cpq/quotations/{id}/excel-view       获取Excel视图数据（按模板excel_view_config渲染）
PUT    /api/cpq/quotations/{id}/excel-view       Excel视图编辑保存（同步更新底层CPQ数据+快照）
GET    /api/cpq/quotations/{id}/export-excel     导出.xlsx文件（含公式）
```

---

## 10. 非功能需求

- Excel 解析支持 .xlsx 格式（Apache POI）
- 单次导入支持最大 500 个产品行
- 导入预览响应时间 < 3s（500 行以内）
- 料号匹配查询响应时间 < 200ms
- 生产料号 Excel 导入支持最大 5000 条/次
- 原始 Excel 文件服务端保留 12 个月，定时任务每月清理过期文件（ImportRecord 记录永久保留）
- 导入记录列表加载时间 < 500ms
- Excel 视图渲染时间 < 1s（50 个产品行以内）
- Excel 导出时间 < 3s（50 个产品行以内）
- 前端电子表格组件需支持：公式计算、单元格编辑、列冻结、行列选择
- 双向同步延迟 < 200ms（编辑一个字段后另一视图更新）

---

## 11. 版本演进摘要

### v1.0 → v2.0 变更

| 变更项 | v1.0 | v2.0 |
|-------|------|------|
| Excel视图 | 无 | 新增，挂载在模板上 |
| Template表 | 无变更 | 新增 excel_view_config |
| column_mappings | 复杂（含 target_type/component_code/row_index） | 简化为列对列映射 |
| Excel导出 | 无公式 | 携带公式 |

### v2.0 → v3.0 变更

| 变更项 | v2.0 | v3.0 |
|-------|------|------|
| CustomerExcelTemplate | 独立实体 | 去掉，合并到 excel_view_config |
| ImportMappingTemplate | 独立实体 | 去掉，映射关系内置于 excel_view_config |
| 导入配置管理页面 | 独立页面（左右分栏） | 去掉，统一在模板配置页的 Excel 视图标签页 |
| excel_view_config | 仅列定义+公式 | 扩展含 customer_id + import_settings + 列定义+公式 |
| 导入流程 | 6步（选客户→选Excel模板→选映射→上传→预览→确认） | 5步（选客户→选模板→上传→预览→确认） |
| ImportRecord | 引用 excel_template_id + mapping_template_id | 引用 template_id + config_snapshot |
| 配置入口 | 两个地方（模板配置+导入配置管理） | 一个地方（模板配置页） |
