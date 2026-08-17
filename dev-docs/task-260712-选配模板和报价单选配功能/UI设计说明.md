# UI 设计说明 — 选配模板和报价单选配功能（task-0712）

> 面向 UI/原型制作。依据 `需求说明.md` 第 9 节澄清结论。产出物 = 4 个**可交互 HTML 原型**（自包含、内联 CSS、无 CDN、vanilla JS，能点能用），风格对齐 `docs/html/*.html`。
>
> 原型文件（存于本任务目录 `prototypes/`）：
> 1. `原型-选配模板管理.html`
> 2. `原型-报价单-从已有产品添加.html`
> 3. `原型-报价单-选配添加.html`
> 4. `原型-配置中心-3D模型配置.html`

---

## 0. 全局风格与外壳（所有原型统一）

### 0.1 设计令牌（Design Tokens）
| 令牌 | 值 |
|---|---|
| 主色 primary | `#1890ff`（hover `#40a9ff`） |
| 成功 / 危险 | `#52c41a` / `#f56c6c` |
| 页面底色 | `#f0f2f5` |
| 卡片 / 抽屉底 | `#fff`，圆角 `8px`（抽屉 `0`） |
| 左侧菜单底 | `#001529`，选中 `#000c17` + 左蓝条 |
| 文本主/次/浅 | `#303133` / `#606266` / `#909399` |
| 边框 | `#e4e7ed` / `#f0f0f0` |
| 字体 | `-apple-system,'PingFang SC','Microsoft YaHei',sans-serif`；基准 `13px` |
| 圆角/间距 | 按钮 `4px`、卡片 `8px`、内边距 `12~20px` |

### 0.2 外壳布局（管理端页面用；报价单抽屉见各页）
```
┌───────┬──────────────────────────────────────────┐
│ Sider │ 面包屑（配置中心 / xxx）                   │
│ 200px │──────────────────────────────────────────│
│ 深色  │ 内容区（.page-card 白卡）                  │
│ 菜单  │   页标题 + 工具栏 + 表格 / 表单            │
└───────┴──────────────────────────────────────────┘
```
- 报价单相关抽屉原型：外壳可用简化的报价单 Step2 背景（灰底 + 顶部"报价单 / Step2 添加产品"面包屑 + 一个「添加产品 ▾」下拉），点下拉项滑出对应右侧抽屉。

### 0.3 共享交互组件（原型需可用）
- **抽屉 Drawer**：右侧滑出，遮罩，右上角关闭；宽度 480/720/960 按内容取。
- **列表工具栏动作**：变更类动作上提到顶部工具栏，行内只留主入口链接；动作按"是否选中行"启用/禁用（禁用 hover 提示原因），危险动作二次确认 Modal 列出所选项。（对齐 `SelectableTable` 规范）
- **3D 预览块**：一个方形/16:9 预览区，默认显示预览图（占位可用纯色块 + "🧊 材质3D/料号3D 预览" 文案 + 模型名）；右上角小按钮「⤢ 交互查看」点开显示"（可旋转 3D，增强项）"提示层。切换选择时预览图与标题实时更新。

---

## 1. 选配模板管理（配置中心）

**入口**：配置中心 → 选配模板管理（现路由 `/config/sel-templates`）。

### 1.1 列表页
- 页标题「选配模板管理」；工具栏右侧「+ 新建模板」。
- 工具栏动作（选择驱动）：**编辑**（选 1 行启用）、**停用/启用**（切状态）、**删除**（危险，二次确认列出所选）。
- 表格列：
  | 列 | 说明 |
  |---|---|
  | 归属行业 | 主入口链接，点击进编辑抽屉；显示行业中文名（含保留：默认模板 `__DEFAULT__` / 通用组合工艺 `__GLOBAL__`） |
  | 模板名 | 文本 |
  | 启用参数数 | 已启用参数个数（如 2/3） |
  | 状态 | 标签：启用(绿) / 停用(灰) |
- 空态：无模板时提示"暂无选配模板，点击右上「+ 新建模板」创建"。
- 演示数据建议 3~4 行：默认模板、汽车、电子、通用组合工艺。

### 1.2 新建 / 编辑抽屉（宽 720）
- 标题：新建选配模板 / 编辑选配模板。
- 表单字段：
  - **归属行业**（Select，必填，**一行业一套**；编辑时禁用不可改；含保留行业项）
  - **模板名**（Input，必填）
  - **状态**（启用/停用）
- **选配参数**区（卡片列表，来自参数池 `sel_param_type` 三项）：
  | 参数 | valueMode | 可选值来源 | 交互 |
  |---|---|---|---|
  | 材质 | single | 材质库 `MATERIAL_RECIPE` | 勾选启用 + 多选限定可选值（留空=不限） |
  | 元素含量 | adjust | 无 | 勾选启用 → 显示说明"启用后允许在派生元素含量上微调"（无可选值多选框） |
  | 工序 | multi | 工序库 `V6_PROCESS_MASTER` | 勾选启用 + 多选限定可选值（留空=不限） |
  - 每个参数一张 `.param-card`：Checkbox（参数名）+（非 adjust 时）下方多选下拉；未勾选时下拉禁用。
- 底部「保存」按钮（block）。
- 交互：勾选参数 → 下拉启用；下拉演示可选值（材质：不锈钢304/黄铜H62/铝6061…；工序：车/铣/电镀/热处理…）。

---

## 2. 报价单 · 从已有产品添加（抽屉）

**入口**：报价单 Step2 →「添加产品 ▾」→「从已有产品添加」。宽 960。

### 2.1 布局（左列表 + 右 3D 预览）
```
┌ 抽屉：添加产品 — 从已有产品 ─────────────────────────┐
│ 过滤条：客户产品编号 | 销售料号 | 品名 | 规格  [查询] │
│──────────────────────────────┬──────────────────────│
│ 产品列表（可多选，勾选框）    │  3D 预览（当前选中行）│
│ 列：客户产品编号/销售料号/    │  [预览图]            │
│     品名/规格/客户物料名      │  销售料号: xxx       │
│  ○ ...                        │  「⤢ 交互查看」      │
│  ● 选中行高亮                 │                      │
│──────────────────────────────┴──────────────────────│
│ 已选 N 项            [取消]  [加入报价单]            │
└──────────────────────────────────────────────────────┘
```
- 数据源：当前报价单客户的 `material_customer_map`（原型用演示数据）。
- 过滤字段：客户产品编号 `customer_product_no` / 销售料号 `material_no` / 品名（`customer_material_name`）/ 规格（占位，见备案 9.4）。
- 列表可多选（行 Checkbox + 顶部全选）；单击行 = 选中并在右侧显示该**销售料号 3D 预览**（无 3D 时显示"该料号未配置 3D 模型"占位）。
- 底部显示"已选 N 项"，「加入报价单」按选中项批量加入（原型点按后 message 提示"已加入 N 个产品"并关闭）。

---

## 3. 报价单 · 选配添加（抽屉，**明细表格式**）

**入口**：报价单 Step2 →「添加产品 ▾」→「选配添加」（需已绑报价模板，否则禁用+提示）。宽 960。
**核心模型**：一个选配产品 = **多个材质料号的组合**；抽屉主体是一张**选配明细表**（一行 = 一个材质料号，含各自的元素含量/工序/数量），产品级的**组合工艺**在"数量合计 ≥ 2"时出现。

### 3.1 前置：有效模板解析
- 打开抽屉即按当前客户解析有效模板（`getEffective(customerNo)`）：
  - 有模板 → 进入明细表；【新增】子框内可选的材质/工序/元素依模板 **enabled 参数 + `effectiveValues`** 限定。
  - **无模板**（客户行业 + 默认都没有）→ 抽屉内空态："缺少选配模板 —— 请先在「配置中心 → 选配模板管理」为该客户所属行业或默认模板配置选配参数。" + 「去配置」链接。

### 3.2 主体：选配明细表 + 新增子框 + 组合工艺 + 汇总
**(a) 明细表**（抽屉主体，初始空）
| 列 | 说明 |
|---|---|
| # | 行号 |
| 材质 | 该行选定材质（含 3D 小缩略图） |
| 元素含量 | 微调后的元素摘要（如 Ni 8.1 / Cr 18.2 …） |
| 工序 | 已选工序摘要（车/铣/电镀…） |
| 数量 | 行内可编辑数字（默认 1） |
| 操作 | 编辑 / 删除 |
- 顶部【+ 新增材质料号】按钮；表下方实时显示"**数量合计：N**"。

**(b) 新增子框**（点【新增】弹出，子步骤：材质 → 元素含量 → 工序）
- **材质**：候选列表 + **过滤/搜索框**（D14）；单选；选中即右侧 3D 预览实时切为该材质 3D。
- **元素含量**：按所选材质派生元素列表，可微调数值（2~3 个元素 + 数字输入）。
- **工序**：**顶部过滤/搜索框**（D14）+ 从工序可选值多选（可调序）。
- 底部【确认】→ 回填明细表一行（数量默认 1，可在表内改）。

**(c) 组合工艺**（条件出现）
- 当明细表**数量合计 ≥ 2**（D12）→ 表格下方出现【组合工艺】区（否则隐藏/灰置提示"数量合计≥2 时需选择组合工艺"）。
- 组合工艺候选 = 工序库中 `process_category='ASSEMBLY'` 的工序（D13，标识用 `process_no`）；**顶部过滤/搜索框**（D14）+ 多选。

**(d) 汇总确认**
- 展示明细表 + 组合工艺（如有）摘要 + **指纹匹配区**：整份配置命中已有销售料号时显示"✅ 匹配到已有销售料号 SP-xxxx，将带出其内容与 3D"并把右侧预览切成**料号 3D**；未命中显示"🆕 将新建选配产品"。
- 右侧 3D 预览常驻：默认跟随最近操作的材质；命中料号后切料号 3D。
- 底部【取消】【确认加入】→ toast "已加入选配产品" 并关闭。

### 3.3 布局
```
┌ 抽屉：添加产品 — 选配 ───────────────────────────────┐
│ [演示切换] 有模板 / 缺少模板                          │
│──────────────────────────────┬──────────────────────│
│ 选配明细表                    │  3D 预览（随选择变化）│
│  [+ 新增材质料号]             │  [预览图]            │
│  # 材质 元素 工序 数量 操作   │  当前: 材质 不锈钢304 │
│  1 ...                    [1] │  「⤢ 交互查看」      │
│  数量合计: 3                  │                      │
│  ── 组合工艺(合计≥2出现) ──   │                      │
│  ☑ 组装 ☑ 焊接                │                      │
│──────────────────────────────┴──────────────────────│
│ [指纹: 🆕 将新建 / ✅ 命中 SP-2048]  [取消][确认加入] │
└──────────────────────────────────────────────────────┘
```
> 新增子框（材质→元素→工序）可用**内层抽屉/弹层**呈现，确认后回填明细表。

---

## 4. 配置中心 · 3D 模型配置（新页）

**入口**：配置中心 → 3D 模型配置（新增菜单）。管理端外壳。

### 4.1 顶部分区 Tab
- Tab：**销售料号模型** | **材质模型**（对应 `model_config.subject_type` = SALES_PART / MATERIAL）。

### 4.2 列表（每个 Tab 一张表）
- 工具栏动作：**上传模型**（打开上传抽屉）、**设为当前版本**、**查看历史版本**、**删除**。
- 列（销售料号 Tab）：销售料号 / 模型名 label / 当前版本 / 预览缩略图 / 大小 / 上传时间 / 状态(当前/历史)。
- 列（材质 Tab）：材质配方码 / 材质名 / 模型名 / 当前版本 / 缩略图 / 大小 / 上传时间。
- 缩略图列显示小预览图（占位色块）；点击行主入口 = 打开详情/预览。

### 4.3 上传抽屉（宽 720）
- 字段：
  - **绑定对象**（按 Tab 预设类型）：销售料号 Tab → 选/输销售料号；材质 Tab → 选/输材质配方。**两者交互一致，均为可输入文本过滤的选择框（datalist/可搜索下拉，D14）**，不用纯 select。
  - **模型文件 .glb**（上传区，拖拽/点选；原型模拟选文件后显示文件名 + 大小 + 解析出的 mesh/顶点占位）。
  - **预览图**（可选上传 png/jpg；或"从模型自动截图"按钮，原型模拟生成占位图）。
  - **模型名 label**（Input）。
  - 「上传并设为当前」/「仅上传为历史版本」两按钮。
- 上传成功后回列表，新版本置顶、is_current 标记。

---

## 5. 附：共享 HTML 外壳与 CSS（原型内联，供 4 页复用）

> 每个原型 `<style>` 内至少包含以下基础类；页面差异在内容区实现。菜单高亮当前页。

```css
* { margin:0; padding:0; box-sizing:border-box; }
body { font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif; background:#f0f2f5; font-size:13px; color:#303133; }
.layout { display:flex; min-height:100vh; }
.sider { width:200px; background:#001529; color:#fff; flex-shrink:0; }
.sider .logo { padding:16px; font-weight:600; border-bottom:1px solid #1f2d3d; }
.m-item { padding:9px 16px; color:#a6adb4; cursor:pointer; }
.m-item:hover,.m-item.active { color:#fff; background:#1f2d3d; }
.m-sub { padding:6px 16px 6px 32px; color:#a6adb4; cursor:pointer; font-size:12.5px; }
.m-sub.active { color:#1890ff; background:#000c17; border-left:3px solid #1890ff; padding-left:29px; }
.main { flex:1; display:flex; flex-direction:column; }
.crumb { padding:10px 20px; background:#fff; border-bottom:1px solid #e4e7ed; color:#909399; }
.crumb b { color:#303133; font-weight:500; }
.content { flex:1; padding:16px 20px; }
.page-card { background:#fff; border-radius:8px; padding:18px 20px; margin-bottom:12px; }
.page-title { font-size:18px; font-weight:600; margin-bottom:14px; }
.toolbar { display:flex; gap:8px; align-items:center; margin-bottom:12px; flex-wrap:wrap; }
.btn { padding:5px 12px; border:1px solid #dcdfe6; background:#fff; border-radius:4px; cursor:pointer; color:#606266; display:inline-flex; align-items:center; gap:4px; }
.btn:hover { color:#409eff; border-color:#c6e2ff; background:#ecf5ff; }
.btn-primary { background:#1890ff; color:#fff; border-color:#1890ff; }
.btn-primary:hover { background:#40a9ff; }
.btn-danger { color:#f56c6c; border-color:#fbc4c4; }
.btn[disabled]{ opacity:.45; cursor:not-allowed; }
table { width:100%; border-collapse:collapse; }
th,td { padding:9px 10px; border-bottom:1px solid #f0f0f0; text-align:left; }
th { background:#fafafa; color:#606266; font-weight:500; }
tr.sel { background:#e6f7ff; }
.tag { padding:1px 8px; border-radius:10px; font-size:12px; }
.tag-green{ background:#f6ffed; color:#52c41a; border:1px solid #b7eb8f; }
.tag-gray{ background:#f5f5f5; color:#909399; border:1px solid #e4e7ed; }
/* 抽屉 */
.mask { position:fixed; inset:0; background:rgba(0,0,0,.45); display:none; }
.mask.open{ display:block; }
.drawer { position:fixed; top:0; right:0; height:100vh; background:#fff; box-shadow:-2px 0 8px rgba(0,0,0,.15);
  transform:translateX(100%); transition:transform .25s; display:flex; flex-direction:column; }
.drawer.open{ transform:translateX(0); }
.drawer .dh { padding:14px 18px; border-bottom:1px solid #e4e7ed; font-weight:600; display:flex; justify-content:space-between; }
.drawer .db { flex:1; overflow:auto; padding:16px 18px; }
.drawer .df { padding:12px 18px; border-top:1px solid #e4e7ed; display:flex; gap:8px; justify-content:flex-end; }
/* 3D 预览 */
.preview3d { border:1px solid #e4e7ed; border-radius:8px; overflow:hidden; }
.preview3d .box { aspect-ratio:1/1; background:linear-gradient(135deg,#e6f0ff,#dfe7f5); display:flex; align-items:center; justify-content:center; color:#7a8aa8; font-size:34px; position:relative; }
.preview3d .cap { padding:8px 10px; font-size:12.5px; color:#606266; border-top:1px solid #f0f0f0; }
.preview3d .zoom { position:absolute; top:8px; right:8px; }
```
