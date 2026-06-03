# CPQ 报价单 Excel 导入 + Excel 视图功能设计 v2.0

> **日期**: 2026-04-21
> **基于**: v1.0（Excel 导入功能设计）
> **状态**: 设计完成，待实施
> **核心变更**: 新增 Excel 视图模式，Excel 视图配置挂载在模板上，导入映射简化为列对列映射

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
| 整体方案 | 配置映射（方案A） | 数据准确性第一，不引入 AI 不确定性 |
| 导入粒度 | 一个 Excel = 一张报价单 | Excel 每行是一个产品 |
| 导入流程 | 先选模板再导入 | 确保所有产品使用同一模板，映射关系明确 |
| 配置架构 | 客户Excel模板 + 映射配置两层分离 | 客户Excel格式独立管理，一种格式可映射到多个CPQ模板 |
| Excel视图配置位置 | 挂载在 CPQ 模板上 | 所有报价单（手动创建/导入）都能使用 Excel 视图 |
| 导入映射方式 | 客户Excel列 → 模板Excel视图列（列对列） | Excel视图已定义列与组件字段的关系，导入映射不再重复定义 |
| 双向编辑 | 同一份数据，两种呈现 | 产品卡片和 Excel 视图编辑同一份底层数据，实时同步 |
| 公式引擎 | 产品卡片用 JEXL，Excel视图用 Excel 原生公式 | 各自引擎计算，数据源相同，结果一致 |
| 公式结果存储 | 存储 | row_data 扩展存储 FORMULA 结果，excel_view_snapshot 存储 Excel 公式结果 |
| 产品关联 | 不强制关联 Product 表 | Excel 中客户零件号映射为产品属性值，灵活适配新零件 |
| FORMULA/DATA_SOURCE | Excel视图中为公式列或数据列 | 产品卡片视图中仍走系统公式计算和数据源查询 |
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

### 2.4 Template 表扩展

```
Template {
  ...existing fields...

  excel_view_config: JSONB (Excel视图列定义+公式，新增字段)
}
```

**excel_view_config 结构：**

```json
{
  "columns": [
    {
      "col_key": "A",
      "title": "Part Number",
      "source_type": "PRODUCT_ATTRIBUTE",
      "source_name": "产品名称"
    },
    {
      "col_key": "B",
      "title": "Ag%",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "含量",
      "row_index": 0
    },
    {
      "col_key": "C",
      "title": "Ag base price",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "单价",
      "row_index": 0
    },
    {
      "col_key": "D",
      "title": "Ag base cost",
      "source_type": "EXCEL_FORMULA",
      "formula": "=B{row}*C{row}"
    },
    {
      "col_key": "E",
      "title": "Copper%",
      "source_type": "COMPONENT_FIELD",
      "component_code": "touLiao",
      "field_name": "含量",
      "row_index": 1
    },
    {
      "col_key": "F",
      "title": "Unit",
      "source_type": "FIXED_VALUE",
      "value": "USD/Kg"
    }
  ]
}
```

**列的 source_type 四种类型：**

| source_type | 说明 | 可编辑 | 数据流向 |
|------------|------|--------|---------|
| PRODUCT_ATTRIBUTE | 映射到产品属性 | 是 | 编辑后同步更新产品卡片视图的产品属性 |
| COMPONENT_FIELD | 映射到组件字段（含 row_index） | 是 | 编辑后同步更新产品卡片视图对应的组件字段 |
| EXCEL_FORMULA | Excel 公式，引用其他列计算 | 否（自动计算） | 公式结果存入 excel_view_snapshot |
| FIXED_VALUE | 固定值 | 否 | 仅展示 |

**公式中的 `{row}` 占位符：**
- 渲染时替换为实际行号（如第 1 个产品 row=2，第 2 个产品 row=3）
- 导出 Excel 时同理，`=B{row}*C{row}` → `=B2*C2`

**发布时 excel_view_config 快照：**
- 模板发布时，excel_view_config 和 components_snapshot 同等对待，冻结到发布版本中
- 已发布模板的 excel_view_config 不可修改
- 报价单使用发布时的快照数据渲染 Excel 视图

### 2.5 QuotationLineItem 扩展

```
QuotationLineItem {
  ...existing fields...

  customer_part_no: String (nullable, 客户料号值；
    Excel导入时从 part_no_column 映射值写入；
    手动添加产品时从 Product.part_no 写入；
    用于料号匹配查询和编辑时重新匹配的依据)

  excel_view_snapshot: JSONB (nullable, Excel视图所有列的值快照，含公式计算结果；
    每次保存/提交时更新)
}
```

**excel_view_snapshot 结构：**

```json
{
  "A": "AU2",
  "B": 0.05,
  "C": 64,
  "D": 0.64,
  "E": 0.60,
  "F": "USD/Kg"
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

### 2.7 新增 CustomerExcelTemplate（客户Excel模板）

```
CustomerExcelTemplate {
  id: UUID (PK)
  name: String (NOT NULL, 如"施耐德-标准报价格式2026")
  customer_id: UUID (FK → Customer, NOT NULL)
  description: String (描述说明)
  header_row_index: Integer (表头所在行号, 默认1)
  data_start_row_index: Integer (数据起始行号, 默认2)
  sheet_index: Integer (工作表序号, 默认0)
  part_no_column: String (NOT NULL, 客户料号所在列名)
  excel_columns: JSONB (NOT NULL, 样例Excel的列名有序列表)
  sample_file_name: String (样例文件名，展示用)
  created_by: UUID (FK → User)
  created_at: Timestamp
  updated_at: Timestamp
}
```

### 2.8 新增 ImportMappingTemplate（导入映射配置）

```
ImportMappingTemplate {
  id: UUID (PK)
  name: String (NOT NULL)
  excel_template_id: UUID (FK → CustomerExcelTemplate, NOT NULL)
  template_id: UUID (FK → Template, NOT NULL, CPQ产品卡片模板)
  column_mappings: JSONB (列对列映射规则)
  created_by: UUID (FK → User)
  created_at: Timestamp
  updated_at: Timestamp
}

唯一约束: (excel_template_id, template_id)
```

**column_mappings 结构（v2 简化版）：**

v1 中映射直接到组件字段（复杂），v2 简化为客户 Excel 列名 → 模板 Excel 视图列 key 的映射：

```json
[
  { "excel_column": "Schneider Part Number", "target_view_column": "A" },
  { "excel_column": "Ag%", "target_view_column": "B" },
  { "excel_column": "Ag base cost USD/Kg", "target_view_column": "C" },
  { "excel_column": "Copper%", "target_view_column": "E" }
]
```

不再需要 target_type / target_component_code / row_index 等字段——"Excel 视图列 → CPQ 组件字段"的关系已在模板的 excel_view_config 中定义。

> **EXCEL_FORMULA 和 FIXED_VALUE 类型的列不需要导入映射**——公式列由系统自动计算，固定值列由模板配置决定。只有 PRODUCT_ATTRIBUTE 和 COMPONENT_FIELD 类型的列才需要从客户 Excel 导入数据。

### 2.9 新增 ImportRecord（导入记录）

```
ImportRecord {
  id: UUID (PK)
  quotation_id: UUID (FK → Quotation, nullable, FAILED时为NULL)
  customer_id: UUID (FK → Customer, NOT NULL)
  excel_template_id: UUID (FK → CustomerExcelTemplate, NOT NULL)
  mapping_template_id: UUID (FK → ImportMappingTemplate, NOT NULL)
  mapping_snapshot: JSONB (NOT NULL, 导入时映射配置的完整快照)
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

### 2.10 实体关系总览

```
Customer ──1:N──→ CustomerMaterialMapping ──N:1──→ InternalMaterial
Customer ──1:N──→ CustomerExcelTemplate ──1:N──→ ImportMappingTemplate ──N:1──→ Template
ImportMappingTemplate ──1:N──→ ImportRecord ──N:1──→ Quotation
Template.excel_view_config ──定义──→ Excel视图列结构+公式

数据流:
  导入: 客户Excel列 ──映射配置──→ 模板Excel视图列 ──excel_view_config──→ CPQ组件字段
  渲染: CPQ组件字段 ──excel_view_config──→ 模板Excel视图列 → Excel视图
  导出: CPQ组件字段 ──excel_view_config──→ .xlsx文件（含公式）
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
┌──────────────────────────────────────────────────────────────┐
│ [产品卡片视图] | [Excel视图(当前)]               [导出Excel]   │
│──────────────────────────────────────────────────────────────│
│     │ A            │ B     │ C         │ D          │ E     │
│     │ Part Number  │ Ag%   │ Ag price  │ Ag cost    │ Cu%   │
│─────┼──────────────┼───────┼───────────┼────────────┼───────│
│  1  │ AU2          │ 5%    │ 64        │ =B1*C1     │ 60%   │
│  2  │ AU3          │ 3%    │ 45        │ =B2*C2     │ 55%   │
│  3  │ AU5          │ 8%    │ 72        │ =B3*C3     │ 40%   │
└──────────────────────────────────────────────────────────────┘

列背景色区分：
  白色：可编辑列（PRODUCT_ATTRIBUTE / COMPONENT_FIELD）
  浅灰：公式列（EXCEL_FORMULA，只读，显示计算结果）
  浅蓝：固定值列（FIXED_VALUE，只读）
```

每行 = 一个产品（QuotationLineItem）
列结构来自模板的 excel_view_config

### 3.3 双向同步机制

**Excel 视图 → 产品卡片视图：**
1. 用户在 Excel 视图编辑一个单元格（如修改 B1 = "8%"）
2. 系统通过 excel_view_config 查找该列的映射关系（B列 → 投料成本.含量.row_index=0）
3. 更新底层数据（QuotationLineComponentData.row_data 对应字段）
4. 产品卡片视图的对应字段同步更新
5. 产品卡片的 FORMULA 字段自动重算（JEXL）
6. Excel 视图的 EXCEL_FORMULA 列自动重算（表格组件）

**产品卡片视图 → Excel 视图：**
1. 用户在产品卡片编辑一个字段（如修改投料成本·第1行·含量 = "8%"）
2. 更新底层数据
3. 系统通过 excel_view_config 反向查找该字段对应的 Excel 视图列（含量.row_index=0 → B列）
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
- EXCEL_FORMULA 列：写入公式（`cell.setCellFormula("B2*C2")`），不是数值
- FIXED_VALUE 列：写入固定值
- 客户打开导出的 Excel 能看到公式并支持重算

---

## 4. 导入流程

### 4.1 入口

报价单管理页新增"从 Excel 新建报价单"按钮。

### 4.2 导入弹窗（步骤式）

```
步骤1：选择客户（搜索下拉）
  │    └→ 加载该客户下已注册的客户Excel模板列表
  │
步骤2：选择客户Excel模板（下拉）
  │    └→ 展示模板描述 + 列数 + 样例文件名
  │    └→ 无模板时提示"该客户暂无Excel模板，请联系销售经理注册"
  │    └→ 选择后加载该Excel模板下的映射配置列表
  │
步骤3：选择映射配置（下拉）
  │    └→ 展示关联的 CPQ 模板名称 + 版本号
  │    └→ 若只有一个映射配置，自动选中跳过此步
  │    └→ 无映射配置时提示"该Excel模板暂未配置映射规则，请联系销售经理创建"
  │
步骤4：上传 Excel 文件
  │    └→ 后端按客户Excel模板配置的 sheet_index、header_row_index 解析表头
  │    └→ 校验表头与客户Excel模板的 excel_columns 是否匹配
  │    └→ 不匹配时提示具体差异列
  │
步骤5：预览解析结果
  │    ┌──────────────────────────────────────────────────┐
  │    │ 客户料号 │ 产品名称 │ Ag%  │ Ag cost │ ... │ 料号状态 │
  │    │ AU2     │ 触点座   │ 5%   │ 0.64   │     │ ✓ 匹配   │
  │    │ AU3     │ 弹簧片   │ 3%   │ 0.45   │     │ ✗ 停产   │
  │    │ AU5     │ 接线端子 │ 8%   │ 0.72   │     │ ✗ 未匹配 │
  │    └──────────────────────────────────────────────────┘
  │    └→ 底部统计：共X条，匹配成功X条，未匹配X条，停产X条
  │
步骤6：确认导入
        └→ 保存原始 Excel 文件到服务端
        └→ 生成 DRAFT 报价单
        └→ 创建 ImportRecord
        └→ 跳转报价生成器步骤二
```

### 4.3 导入数据流转

```
客户Excel文件
  │
  ├─ 按 ImportMappingTemplate.column_mappings 映射
  │   客户Excel列名 → 模板Excel视图列 col_key
  │
  ├─ 按 Template.excel_view_config 转换
  │   Excel视图列 col_key → CPQ 组件字段 / 产品属性
  │
  ├─ 写入 CPQ 数据:
  │   ├─ QuotationLineItem.product_attribute_values（产品属性）
  │   ├─ QuotationLineItem.customer_part_no（客户料号）
  │   ├─ QuotationLineItem.excel_view_snapshot（Excel视图快照，含公式结果）
  │   └─ QuotationLineComponentData.row_data（组件行数据，含FORMULA结果）
  │
  └─ 前端加载后:
      ├─ 产品卡片视图：FORMULA 字段实时重算（JEXL）
      ├─ Excel视图：EXCEL_FORMULA 列实时重算（表格组件）
      └─ DATA_SOURCE 字段触发查询
```

### 4.4 表头校验规则

导入步骤4解析 Excel 表头后，校验映射配置中所有 `excel_column` 是否在表头中存在：
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

### 6.3 模板 Excel 视图配置

**入口位置**：模板配置页面 → 新增"Excel视图配置"标签页

```
模板配置页面
├── 产品属性（已有）
├── 组件画布（已有）
├── 产品小计公式（已有）
└── Excel视图配置（新增）
```

**配置界面：**

```
┌──────────────────────────────────────────────────────────────────┐
│ Excel视图配置                                    [预览] [+ 添加列]│
│──────────────────────────────────────────────────────────────────│
│ 列Key │ 列标题        │ 数据来源           │ 值/公式/映射目标      │
│ A    │ [Part Number] │ [产品属性 ▼]       │ [产品名称 ▼]         │
│ B    │ [Ag%        ] │ [组件字段 ▼]       │ [投料成本·含量·行1 ▼] │
│ C    │ [Ag price   ] │ [组件字段 ▼]       │ [投料成本·单价·行1 ▼] │
│ D    │ [Ag cost    ] │ [Excel公式 ▼]      │ [=B{row}*C{row}    ] │
│ E    │ [Copper%    ] │ [组件字段 ▼]       │ [投料成本·含量·行2 ▼] │
│ F    │ [Unit       ] │ [固定值 ▼]         │ [USD/Kg            ] │
│ ...  │               │                    │                      │
│──────────────────────────────────────────────────────────────────│
│ 每行操作: [↑][↓] 排序  [× 删除]                                  │
└──────────────────────────────────────────────────────────────────┘
```

**配置规则：**
- "数据来源"下拉四选一：产品属性 / 组件字段 / Excel公式 / 固定值
- 选择"组件字段"时，右侧下拉展示格式为"组件名·字段名·行N"（只列出 INPUT/FIXED_VALUE 类型字段）
- 选择"Excel公式"时，右侧为文本输入框，使用 `{row}` 占位符引用当前行号，列引用使用 col_key（如 =B{row}*C{row}）
- 选择"产品属性"时，右侧下拉列出模板的 product_attributes
- 列可添加/删除/拖拽排序
- col_key 自动分配（A、B、C...），用户可修改

**预览功能：**
点击"预览"展示一个模拟的 Excel 表格，填入示例数据，验证公式是否正确计算。

**发布时校验：**
- excel_view_config 可以为空（不配置 Excel 视图）
- 若已配置，发布时校验公式中引用的列 key 必须存在

### 6.4 客户Excel模板管理 + 导入映射配置管理

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
│ │ 46列 | 2026-04-21   │      │ │   18列映射             │ │
│ ├─────────────────────┤      │ ├────────────────────────┤ │
│ │ 施耐德-原材料询价格式 │      │ │ → 定制件卡片 v2.0     │ │
│ │ 22列 | 2026-04-20   │      │ │   12列映射             │ │
│ └─────────────────────┘      │ └────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

#### 6.4.1 注册客户Excel模板

点击"新建模板"弹出抽屉：

```
步骤1：填写基本信息
  │  模板名称（必填）
  │  客户（下拉选择）
  │  描述（选填）
  │  表头行号（默认1）、数据起始行（默认2）、工作表序号（默认1）
  │
步骤2：上传样例 Excel
  │  └→ 后端解析表头，返回列名列表
  │  └→ 解析失败时提示"无法解析表头，请检查设置"
  │
步骤3：指定客户料号列
  │  └→ 从解析出的列名中选择
  │
步骤4：保存
```

**编辑已有模板：**
- 客户不可修改
- 可重新上传样例 Excel 更新列名列表
- 更新时检查已有映射配置是否受影响

**保存校验：**

| 校验项 | 规则 | 失败提示 |
|-------|------|---------|
| 模板名称 | 非空 | "请填写模板名称" |
| 客户 | 已选择 | "请选择客户" |
| 样例Excel | 已上传并解析成功 | "请上传样例Excel文件" |
| 客户料号列 | 已选择 | "请指定客户料号所在的Excel列" |

#### 6.4.2 创建/编辑映射配置（v2 简化版）

选中客户Excel模板后，点击"新建映射"进入映射配置编辑页。

**基本信息区：**
- 映射名称（必填）
- 客户Excel模板（只读，已锁定）
- CPQ产品卡片模板（下拉选择，仅 PUBLISHED 且已配置 excel_view_config 的模板）

**映射配置区：**

选择 CPQ 模板后，系统自动列出模板 excel_view_config 中所有**可编辑列**（PRODUCT_ATTRIBUTE 和 COMPONENT_FIELD 类型），以及客户 Excel 模板的列名列表。用户逐列配置对应关系：

```
┌──────────────────────────────────────────────────────────────┐
│ 列映射（仅展示可导入的列，公式列和固定值列自动跳过）            │
│──────────────────────────────────────────────────────────────│
│ CPQ模板Excel视图列       │ ←映射← │ 客户Excel列               │
│ A - Part Number (产品属性)│        │ [Schneider Part Number ▼] │
│ B - Ag% (组件字段)       │        │ [Ag% ▼]                  │
│ C - Ag price (组件字段)   │        │ [Ag base cost USD/Kg ▼]  │
│ E - Copper% (组件字段)   │        │ [Copper% ▼]              │
│ ...                      │        │                          │
│──────────────────────────────────────────────────────────────│
│ D - Ag cost (Excel公式)  │  自动   │ (公式自动计算，无需映射)    │
│ F - Unit (固定值)         │  自动   │ (固定值，无需映射)         │
└──────────────────────────────────────────────────────────────┘
```

**保存校验：**

| 校验项 | 规则 | 失败提示 |
|-------|------|---------|
| 映射名称 | 非空 | "请填写映射名称" |
| CPQ模板 | 已选择 | "请选择产品卡片模板" |
| 客户料号列映射 | 客户Excel模板的 part_no_column 必须被映射 | "客户料号列未配置映射" |
| 至少一列映射 | 至少 1 个可编辑列配置了映射 | "请至少配置一列映射" |
| 唯一约束 | (excel_template_id, template_id) 不重复 | "该Excel模板已有到此CPQ模板的映射配置" |

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
│ 导入时间     │ 操作人 │ 客户   │ Excel模板      │ CPQ模板       │ 文件名        │ 总行│成功│匹配│ 状态    │ 操作       │
│ 04-21 14:30 │ 张三  │ 施耐德 │ 标准报价格式    │ 标准件卡片    │ quote_q2.xlsx │ 50 │ 48 │ 45 │ SUCCESS │ 详情 下载  │
│ 04-20 09:15 │ 李四  │ ABB   │ 标准报价格式    │ 定制件卡片    │ rfq_001.xlsx  │ 30 │ 30 │ 28 │ SUCCESS │ 详情 下载  │
│ 04-19 16:00 │ 张三  │ 施耐德 │ 标准报价格式    │ 标准件卡片    │ test.xlsx     │ 10 │ 0  │ 0  │ FAILED  │ 详情 下载  │
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
- 即使配置后来被修改/删除，此处仍保留导入时的原始配置

**问题明细区（有问题行时展示）：**

```
│ 行号 │ 客户料号 │ 问题描述                           │
│ 5   │ AU7     │ Excel列 'Ag base cost' 值为空       │
│ 12  │ AU15    │ 数值格式错误: 'N/A'                  │
```

### 7.4 追溯场景

1. **数据不对 → 是 Excel 原始问题还是映射问题？**
   - 下载原始 Excel 对照检查原始数据
   - 查看映射快照确认映射规则是否正确

2. **映射配置修改后 → 历史导入用的是哪个版本？**
   - mapping_snapshot 保留导入时的完整映射配置

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
├── 模板配置（已有，扩展Excel视图配置标签页）
├── 产品绑定（已有）
└── 导入配置管理（新增，含客户Excel模板 + 映射配置）

报价中心（已有）
├── 报价单管理（已有）
│     ├── [从 Excel 新建报价单] 按钮（新增）
│     └── [导入历史] 按钮（新增）
└── 报价生成器步骤二（已有，新增 Excel视图切换）
```

### 8.2 权限矩阵补充

| 模块 | 销售代表 | 销售经理 | 定价经理 | 系统管理员 |
|------|---------|---------|---------|-----------|
| 生产料号管理 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 客户料号关联 | 仅查看 | 完全访问 | 仅查看 | 完全访问 |
| 模板Excel视图配置 | 无权访问 | 完全访问 | 无权访问 | 完全访问 |
| 客户Excel模板管理 | 无权访问 | 完全访问 | 无权访问 | 完全访问 |
| 导入映射配置 | 无权访问 | 完全访问 | 无权访问 | 完全访问 |
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

### 9.3 客户Excel模板

```
GET    /api/cpq/customer-excel-templates                         列表（按客户筛选）
GET    /api/cpq/customer-excel-templates/{id}                    详情
POST   /api/cpq/customer-excel-templates                         新增
PUT    /api/cpq/customer-excel-templates/{id}                    编辑
DELETE /api/cpq/customer-excel-templates/{id}                    删除
POST   /api/cpq/customer-excel-templates/parse-header            上传Excel解析表头
```

### 9.4 导入映射配置

```
GET    /api/cpq/import-mapping-templates                         列表（按excel_template_id筛选）
GET    /api/cpq/import-mapping-templates/{id}                    详情
POST   /api/cpq/import-mapping-templates                         新增
PUT    /api/cpq/import-mapping-templates/{id}                    编辑
DELETE /api/cpq/import-mapping-templates/{id}                    删除
```

### 9.5 报价单 Excel 导入

```
POST   /api/cpq/quotations/import-excel     上传Excel+映射配置ID，返回解析预览数据
POST   /api/cpq/quotations/confirm-import   确认导入，生成DRAFT报价单+创建导入记录+保存原始文件
```

### 9.6 导入记录

```
GET    /api/cpq/import-records                   列表（分页、客户筛选、状态筛选、日期范围）
GET    /api/cpq/import-records/{id}              详情（含映射快照和问题明细）
GET    /api/cpq/import-records/{id}/download     下载原始Excel文件
```

### 9.7 Excel 视图数据

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

## 11. v1.0 → v2.0 变更摘要

| 变更项 | v1.0 | v2.0 |
|-------|------|------|
| Excel视图 | 无 | 新增，挂载在模板配置上，支持 Excel 原生公式 |
| 视图切换 | 无 | 报价生成器步骤二支持产品卡片/Excel视图切换 |
| Template表 | 无变更 | 新增 excel_view_config JSONB 字段 |
| QuotationLineItem | 新增 customer_part_no | 新增 customer_part_no + excel_view_snapshot |
| row_data | 只存 INPUT/FIXED_VALUE | 扩展存储 FORMULA 计算结果 |
| column_mappings | 复杂（含 target_type/component_code/row_index） | 简化为列对列映射（excel_column → target_view_column） |
| 映射配置交互 | 按组件分组+按行分区块配置 | 简化为表格式列对列选择 |
| Excel导出 | 无公式 | 导出 .xlsx 携带公式 |
| 公式列处理 | 不处理（读结果值或不导入） | Excel视图原生支持公式计算 |
