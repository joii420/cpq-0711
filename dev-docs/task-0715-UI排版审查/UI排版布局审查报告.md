# CPQ 前端 UI 排版布局审查报告

> 审查角色：资深 UI/UX 设计师视角的"挑刺"审查
> 审查日期：2026-07-15
> 审查范围：`cpq-frontend/src` 全部操作页面（176 个页面 `.tsx` / 193 个 `.tsx` 含组件）
> 审查方法：布局外壳 + 全局主题/CSS 主线亲审 + 8 个模块簇并行代码级审查（逐页读 JSX/CSS，落到 `file:line`）
> 约束：**只挑排版/布局/交互/一致性/无障碍问题，不改任何功能逻辑**；所有条目均可在不触碰数据与计算的前提下整改
> 产出：约 180+ 条带行号的可执行挑刺条目 + 11 个系统性主题 + 3 阶段修复路线图

---

## 0. 执行摘要（一句话结论）

**功能骨架扎实、交互逻辑打磨很深，但"视觉体系"是全站最大欠账——问题几乎全部是"规范执行不彻底"与"缺一层 design token 收敛"，而非"不懂规范"。** 一轮定向的、纯样式层的重构即可显著拉齐观感，收益极高、风险极低（不动任何业务逻辑）。

三个最痛的系统性事实：

1. **两条自家写进 `CLAUDE.md` 的强制规范全站大面积失守**：①"表单/详情/向导一律用 Drawer 抽屉，禁止 Modal" —— 全站 **36 个文件仍在用 `Modal` 承载表单/向导**；②"列表统一 SelectableTable + 动作上提工具栏，行内不放动作按钮" —— 大量列表仍是裸 `<Table>` + 行内 `<a>/Switch/删除`。这两项恰恰是 `CLAUDE.md` 里标注的"PR 评审强制项"。
2. **品牌主色 `#667eea` 形同虚设**：主色设为紫色，但全站硬编码了 **1272 处 hex 颜色**，其中 **39 个文件 / 63 处**仍在用 AntD 蓝 `#1677ff/#1890ff`，还混入了 Element-Plus 灰阶（`#909399/#606266/#303133`）、Material（`#1976d2`）、Chakra（`#e53e3e/#48bb78`）三套外来色板。同一个"金额合计"语义在报价链路的 5 个屏出现了 5 种强调色。
3. **首页是调试残留**：`Dashboard.tsx`（工作台，每个用户登录后第一眼）全页只有一张 `maxWidth:400` 的 "System Health" 卡片，还混入英文 `Status/Service` 与硬编码绿 `#48bb78`。

---

## 1. 系统性问题（跨全站主题，按杠杆排序）

> 这一节是本报告的核心。下面 11 个主题是从 180+ 条明细里归纳出的"共因"，修一个主题 = 一次性拉齐几十个页面。建议**优先按主题批量整改**，而不是逐页打补丁。

### 主题①　弹窗规范失守：Modal 承载表单/向导（应为 Drawer）— 🔴 P0

`CLAUDE.md`「UI 交互规范」白纸黑字："所有需要弹出式交互的场景（新建/编辑表单、详情查看、多步骤向导、批量导入…）一律使用抽屉从右侧滑出，不再使用居中 Modal"。实际全站 **36 个文件**含 `Modal`，其中确认承载表单/向导的违规点（非许可的"危险二次确认"）至少包括：

| 违规位置 | 承载内容 | 严重度 |
|---|---|---|
| `component/ComponentManagement.tsx:238` `DataSourceModal` | DATA_SOURCE 字段 config→select→bind **三步向导** | P0 |
| `template/ProductTemplateBinding.tsx:600` | 含 `<Steps>` 的**两步绑定向导** | P0 |
| `configurator/ConfiguratorPage.tsx:447` | 提交配置 review→link→done **三步向导** | P0 |
| `pricing/PricingStrategy.tsx:436` | 新建/编辑定价策略（表单+阶梯规则表） | P0 |
| `product/ProductManagement.tsx:290` | Excel 导入产品向导 | P0 |
| `customer-lead/CustomerLeadList.tsx:144` | 线索审核（详情 Descriptions + 表单） | P0 |
| `customer/CustomerMaterialMappingTab.tsx:154` | 添加映射 + Excel 批量导入 | P0 |
| `system/ApprovalRuleManagement.tsx:208`、`DepartmentManagement.tsx:152`、`RegionManagement.tsx:77` | 新增/编辑表单 | P0 |
| `configurator/ConfiguratorInstanceList.tsx:191` | 绑定到已有报价单表单 | P0 |
| `costing/CostingTemplateList.tsx:280` | 新建 Excel 模板表单 | P0 |
| `component/FieldConfigTable.tsx:701` | HTTP_API 配置（且 `DefaultSourceEditor` 里同款配置是 Drawer → 双实现） | P1 |
| `template/TemplateList.tsx:331`、`ProductAttributesGrid.tsx:222`、`TemplateFormulasPanel.tsx:238/465` | 新建模板/属性/函数选择/试算 | P1 |
| `basicdata/ComparisonTagManagement.tsx:128`、`ProductCategoryManagement.tsx:146`、`global-variable/GlobalVariablePage.tsx:577/644` | 新增/编辑表单 | P1 |
| `quotation/ImportExcelModal.tsx:357` | 5 步导入向导（**已是孤儿死代码，建议直接删**） | P1 |

**统一整改**：抽一个共享的 `FormDrawer` / `ImportDrawer` / `WizardDrawer` 壳组件，把上述表单/向导迁进去；`Modal` 仅保留 `Modal.confirm` 危险二次确认与 `SelectableTable` 的批量确认（这两类是规范明确许可的）。

### 主题②　列表规范失守：裸 Table + 行内动作按钮 — 🔴 P0

`CLAUDE.md`「列表操作规范」强制："行内不放动作按钮，所有变更/危险动作上提顶部工具栏；统一走 `SelectableTable`"。实际大量列表仍是裸 `<Table>` + 行内 `编辑/停用/删除/解绑/重置密码` 链接：

- `customer-lead/CustomerLeadList.tsx:115` 裸 Table + 行内 `🔗绑定/+新建/🚫拒绝`
- `template/ProductTemplateBinding.tsx:232` 行内 `<Switch>` + 删除；`template/TemplateFormulasPanel.tsx:956` 行内编辑/删除/试算
- 系统簇几乎全线：`ConfiguratorInstanceList/SharesPage/TemplateList`、`FeatureLibraryList/FeatureGroupDetail`、`DepartmentManagement/RegionManagement/UserManagement` 全部行内 `<a>` 动作 —— **全站仅 `ApprovalRuleManagement` 一个页面遵守**
- `part-model/PartModelList.tsx:174`、`global-variable/GlobalVariablePage.tsx:382` 行内危险/状态动作

**统一整改**：迁 `SelectableTable`，动作上提工具栏并声明 `enabledWhen(rows)`；危险动作走列出所选项的二次确认。

### 主题③　色彩体系失控：主色形同虚设 + 多套外来色板混入 — 🟠 P1（覆盖面最广）

- 主色 `#667eea`（紫）只活在 Login 渐变背景和少数卡片里；真正的 CTA、选中态、金额强调却散落 **AntD 蓝 `#1677ff`（39 文件 63 处）+ 旧版 `#1890ff`**。
- 混入三套外来调色板：**Element-Plus 灰阶** `#909399/#606266/#303133/#e4e7ed`（`ModelConfigManagement`、`StatCard`、组件簇 `.cm-*` 样式）、**Material** `#1976d2/#4caf50/#1565c0`（报价 `quotation.css`、模板 `styles.css`）、**Chakra** `#e53e3e/#48bb78`（Dashboard、报价必填星号）。
- **"金额合计"这一核心语义在报价链路出现 5 种强调色**：`Step3` 行合计 `#0958d9`、详情页报价总额 `#c00`、向导总览 `#1890ff`、卡片产品小计 `#667eea` 紫渐变、页签小计 `#1677ff` —— 用户无法把颜色当信息锚点。
- 组件簇同页并存**三套主蓝**（Element `#409eff` / AntD `#1677ff` / 渐变 `#667eea`）；模板簇并存**四套蓝**（`#667eea/#1976d2/#1890ff/#1677ff`）；危险色三写法（`'red'`/`#f5222d`/antd `danger`）。

**统一整改**：定义一套语义 token（`--cpq-primary:#667eea`、`colorSuccess`、`colorError`、"金额强调色"单一常量），全站 grep 替换硬编码 hex。这是覆盖面最广、投入产出比最高的一项。

### 主题④　emoji 当结构性图标 — 🟠 P1

约 **40 个文件**用 emoji 充当导航/按钮/状态图标，与 Ant Design 线性 SVG 图标体系割裂、跨 OS 字形不稳、读屏无语义：
- 侧栏菜单 `🛒 3D 选配 / 📋 选配实例列表 / 🎯 开始选配 / 🔗 / 📩 / 📚 / 📦`（`MainLayout.tsx:101-112`）
- 主 CTA `💾 保存模板`（`TemplateConfiguration.tsx:444`）、`📥` 与 `ImportOutlined` **双图标叠加**（`CostingPartDataPage.tsx:83`）
- `CustomerLeadList` `📩⏳✅🚫🔗ⓘ⚠`、`PartModelList` `📦📖✏️🗑✓←→🎬🔐`、`ModelConfigManagement` `📦📷🪄`、`ConfiguratorInstanceList` `👁📋`、报价视图切换 Segmented `📝📊📈📋📑`

**整改**：结构性图标全部换 `@ant-design/icons`；emoji 仅可作正文内的装饰性辅助。

### 主题⑤　内联样式 + 魔数泛滥，无 design token 层 — 🟠 P1

全站 **2457 处内联 `style={{}}`**、**1272 处硬编码 hex**、大量非阶梯字号（`fontSize:10.5/11.5/12.5`，低于 12px 可读下限且亚像素渲染模糊）、脆弱的负边距与视口硬算（`margin:-24px`、`height: calc(100vh - 112px)` —— 头部高度一变即错位，出现在组件/模板/定价/工序多簇）。

**整改**：抽公共色/字号/间距 token 与 CSS class，内联仅保留一次性布局；字号归整到 12/13/14 阶梯。

### 主题⑥　缺"我在哪"导航：0 面包屑 / 0 PageHeader / 标题层级混乱 — 🟠 P1

- 全站 `Breadcrumb`=**0**、`PageHeader`=**0**；176 个页面**仅 32 个有页面标题**（其余 144 页无标题）。
- 有标题的也层级不一：主标题散落在 `Title level={4}`（20 处）与 `level={5}`（21 处），还有零星 `<h2>` 裸标签与 `level={2/3}`，无统一规则、无 `h1`。
- Header 顶栏左侧完全空置（只有右侧的主题/铃铛/头像），本可承载当前页标题/面包屑。
- Hub 页双标题冗余：`ProductHubPage` 顶部已有 `<h2>产品管理</h2>`，其 Tab 内 `ProductManagement` 又渲染 `Title level4 "产品管理"`（同屏两个同名标题）；`PartVersionPage` 同类问题。

**整改**：约定统一的页头组件（标题 `level={4}` + 可选副标题 + 面包屑），Hub/Tab 子页不再重复页面级标题与外层 padding。

### 主题⑦　数字/金额列不右对齐、不等宽 + 金额精度口径不一 — 🟠 P1

- 数值列（单价/损耗率/年用量/折扣率/含量%/文件大小/取值…）在核价、报价、主数据、定价多簇**普遍左对齐、非 tabular-nums**，无法按小数点纵向扫读。`ElementPriceCenterPage:112` 是全站唯一做对的样板（右对齐 + strong），反衬其它页欠账。
- 金额精度口径打架：向导 Step5 用 `toLocaleString()` 无小数（`¥1,234`），详情/Step3 用 `formatCurrency` 固定 2 位（`¥1,234.00`）；`QuotationList:98` 金额列亦无小数且左对齐。
- 货币码不统一：`ElementPriceCenterPage` 用 `RMB`，核价其它页用 `CNY`。

**整改**：数值列统一 `align:'right'` + `fontVariantNumeric:'tabular-nums'`；对外金额统一 `formatCurrency`（2 位，符合项目小数显示口径）；货币码统一 `CNY`。

### 主题⑧　抽屉宽度不成体系 — 🟡 P2

规范建议 480/720/960/1200 四档，实际散落 **420/520/560/580/600/640/680/700/760/780/800/840/900/920/1000/1080/1100**。近半数抽屉宽度是随手拍的非标值，同类抽屉宽窄不一。**整改**：归并到四档。

### 主题⑨　无障碍普遍薄弱 — 🟡 P2（合规向可上升为 P1）

- **icon-only 按钮缺 `title`/`aria-label`** 遍布（排序 ↑↓、删除、拖拽柄、编辑）：`SortableTable:22`、`FieldConfigTable:552`、`DataSourceEdit:217`、`ImportConfigManagement:122`、`ProductAttributesGrid:210` 等。
- **原生控件绕过设计系统**：`<input type="checkbox">`（`CustomerManagement:497`、`PartModelList:333`）、原生 `<select>`（`GlobalVariablePage:669`）、原生 `<table>`（`MappingEditor:272`）、自绘勾选框 `div.cmm-chk`（`ComponentManagement:683`）—— 均无焦点环/键盘/读屏语义。
- **纯颜色/纯 emoji 传达状态**：版本 diff 仅靠黄/绿底（`VersionCompareDrawer:68`）、线索状态仅靠 emoji+色（`CustomerLeadList:127`）、删行"锁定/联动"用 `🔒🔗`。
- **低对比灰**：`#bfbfbf/#c0c4cc/#bbb/#ccc`（白底 ≈1.6–2.8:1，低于 WCAG 4.5:1）出现在多簇占位/次要文字/危险图标。
- **拖拽无键盘替代**：模板画布加组件仅指针可用（`ComponentPalette` 的 `onAddComponent` 被解构为 `_onAdd` 从未使用，DndContext 无 `KeyboardSensor`）。

### 主题⑩　加载/空态不统一、Dashboard 调试残留 — 🟡 P2（Dashboard 为 P1）

- **首页 = 调试卡**：`Dashboard.tsx` 只有 "System Health" 英文卡片 + 硬编码绿，未复用已有的 `StatCard`。
- 裸 `<Spin/>`（无居中无文案，缩在左上角）反复出现：`CostingTemplateConfig:271`、`ConfiguratorPage:232`、`FeatureGroupDetail:129` 等；标杆写法是 `CostingSummaryDetailPage:167` 的居中容器。
- **`<Spin tip>` 在 AntD v5 独立使用失效**（tip 仅嵌套/fullscreen 生效）：`MasterDataPage:195`、`RowDetailDrawer:174`、`TableDataDrawer:288`、`VersionCompareDrawer:125`。
- 纯文字"加载中..."（无骨架）：`ConfiguratorStartPage:89`、`ImportHistoryList:204`、`TemplateConfiguration:397`。
- 空态混用：一部分用 `<Empty>` 图形、一部分裸 `<div>文字`；`CostingPartDataPage:117` 甚至出现"顶部大 Empty + 下方空 Tab"双重空态，且在无关的 element-bom Tab 误显"请先输入料号"。
- 工程内部细节泄漏给终端用户：`MasterDataPage:71` Alert 暴露 `VITE_USE_MOCK_MASTER_DATA` 环境变量名；`ConfiguratorInstanceList:69` `message` 里字面显示 `<br/>`；`PricingBasicDataImportDrawer:118` 原样渲染反引号。

### 主题⑪　新老两代代码并存（原型直转 vs 现行规范）— 结构性

多个模块明显是"两代设计拼贴"：
- **现行规范基线（可作收敛标杆）**：`system/system-monitor/system-config/change-log` 四组、核价 master-data 主流程、组件簇的 `SqlViewConfigDrawer/ListFormulaConfigDrawer`、`ChangeLogCenterPage` 的新旧值对照、`LockMonitor` 心跳高亮。
- **原型直转的重灾区**：`configurator`（3D 选配）+ `feature-library`（emoji 图标 + 魔数 + `#1890ff/#52c41a` + 主 CTA 一律染绿 + mock "后端待实现"控件暴露给用户）、`ModelConfigManagement`（整套 Element-Plus 色板 + 手写星号）、报价 `qt-*` 手写卡片（Material/Chakra 色板）。
- **死代码污染一致性**：`ComponentTree.tsx`/`HeaderPreview.tsx`/`CrossTabRefDrawer.tsx`（0 外部引用，携带上一代 `.cm-*` Modal 反规范实现）、`quotation/ImportExcelModal.tsx`（孤儿 Modal 向导）、`AddProductModal.tsx`（实现已是 Drawer 但文件/类型仍叫 Modal，命名误导）。

**整改**：以 system 簇为基线做"一致性清扫"，并清理上述死代码。

---

## 2. 分模块明细（逐条挑刺，带 file:line）

> 下列为各模块簇的完整明细，按严重度降序。可直接作为整改工单。P0=规范硬伤/阻碍，P1=明显问题，P2=打磨项。

### 2.1 骨架层（MainLayout / App / global.css）— 主线亲审

| 位置 | 类别 | 问题 | 建议 | 严重度 |
|---|---|---|---|---|
| `App.tsx:22` + 全站 | 一致性 | 主色 `#667eea` 与全站 63 处硬编码蓝 `#1677ff` 冲突 | 收敛主色 token，清硬编码蓝 | P1 |
| `MainLayout.tsx:101-112` | 一致性/图标 | 3D 选配菜单用 emoji 图标，与其它菜单的 AntD 图标割裂 | 换 AntD 图标（`ShoppingCartOutlined` 等） | P1 |
| `MainLayout.tsx:256` | 布局/响应式 | Sider 固定 220px、`overflow:auto`、**不可折叠**；菜单组多时窄屏无收起 | 加 `collapsible` + 触发按钮；窄屏自动收起 | P1 |
| `MainLayout.tsx:280-318` | 信息层级 | Header 左侧完全空置，无当前页标题/面包屑 | 左侧放页标题或面包屑 | P1 |
| `MainLayout.tsx:274` | 导航状态 | `selectedKeys=[pathname]`：Hub 页合并了子路由（如 `/products` 并入 `/products-hub`），在子路由下菜单不高亮 | 用 `startsWith` 匹配父级 key | P2 |
| `MainLayout.tsx:319` / `global.css` | 一致性 | Content/Header 背景用 `themeMode==='dark'?'#1f1f1f':'#fff'` 内联三元 + 硬编码，通知红点写死 `#1677ff` | 走 token，不与主色打架 | P2 |
| 全站 | 导航 | `Breadcrumb`=0 / `PageHeader`=0 / 144 页无标题 / 标题层级 4 与 5 混用 | 统一页头组件 + 面包屑 | P1 |

### 2.2 登录 / 工作台 / 共享组件

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议(不改功能) | 严重度 |
|---|---|---|---|---|---|
| Dashboard | Dashboard.tsx:34,39,40 | 一致性/i18n | Card 标题 `System Health`、正文 `Status:`/`Service:` 全英文，混在全中文 UI | 改「系统健康/状态：/服务：」 | P1 |
| Dashboard | Dashboard.tsx:14-45 | 复用 | 工作台手写 `<p>`+图标，未复用已有 `StatCard`；`<p>` 带浏览器默认 margin | 指标改用 `StatCard`，文本用 Typography | P2 |
| Dashboard | Dashboard.tsx:35,39 | 加载/颜色 | 裸 `<Spin/>` 无居中无 tip；成功图标绿 `#48bb78` 硬编码（与 StatCard `#52c41a` 两种绿） | 居中 Spin/Skeleton；绿走 `colorSuccess` | P2 |
| ResetPassword | ResetPassword.tsx:12 | 布局 | 错误态 `Result` 裸渲染贴左上，未套成功态用的居中 flex+Card 外壳 | 用相同居中壳包裹 | P1 |
| StatCard | StatCard.tsx:7 | 颜色 | `primary` 硬编码 `#1890ff`（第三种游离蓝），作为复用组件扩散错误基准色 | 统一 `colorPrimary`/`#667eea` | P1 |
| 登录簇 | Login:32 vs ForgotPassword:18/ResetPassword:23 | 一致性 | Login 用品牌紫渐变，忘记/重置密码却用扁平灰 `#f0f2f5`，同一鉴权流背景突变 | 三页共用背景，抽 `AuthLayout` | P1 |
| StatCard | StatCard.tsx:48,50 | 无障碍 | 字号 `11.5`/`10.5`——小数且低于 12px（sub 10.5px 低于可读下限） | 用 12 / `fontSizeSM`，去小数 | P1 |
| SelectableTable | SelectableTable.tsx:198-209 | 颜色 | 选中态工具栏 `#e6f4ff/#91caff/#0958d9` 硬编码 AntD 蓝阶，非品牌紫 | 用 `colorPrimaryBg/Border/colorPrimary` | P2 |
| Login | Login.tsx:32,44 | 一致性 | 紫底但 primary 按钮/「忘记密码？」链接渲染 AntD 蓝（主色未收敛） | 根 ConfigProvider 设主色即自动继承 | P2 |
| ResetPassword | ResetPassword.tsx:32,33 | 表单 | `{min:8}`/`{required:true}` 无 `message`，触发默认英文文案（与 ChangePassword 中文不一致） | 补中文 message | P2 |
| ResetPassword/ChangePassword | ResetPassword:15 / ChangePassword:13 | 表单 | 两次密码不一致用顶部 `message.error` toast，而非确认框下方内联错误 | 用 `dependencies`+校验器内联到字段 | P2 |
| SortableTable | SortableTable.tsx:22-30 | 无障碍 | 拖拽手柄纯 icon 无 `aria-label`/`title`/`role`；`cursor:'move'` 旧写法、色 `#999` 硬编码；命中区小 | 补 aria/title，`cursor:'grab'`，扩热区 | P2 |
| SelectableTable | SelectableTable.tsx:241-251 | 交互 | 整行点击切换选中仅靠 `cursor:pointer` 暗示，无说明、无键盘等价 | 增 hover 高亮/提示 | P2 |
| 登录簇 | Login:33 等 4 处 | 复用 | `width:400` 固定 px 无 maxWidth；居中壳在 4 处逐字重复；卡片头部模式不一 | 抽 `AuthCard/AuthLayout` | P2 |
| ChangePassword | ChangePassword.tsx:29,30 | 布局 | 页面无标题 heading，`marginBottom:16`/`maxWidth:500` 魔数 | 加页级标题，间距走变量 | P2 |
| StatCard | StatCard.tsx:48-50 | 颜色 | 文本色 `#909399/#303133`（Element-UI 板），非 AntD token | 换 `colorTextSecondary/colorText` | P2 |

> 注：`SelectableTable` 用 Modal 承载的是"危险动作二次确认 + 列出所选项"，属规范许可范畴，不算违规。

### 2.3 报价单模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| ImportExcelModal | ImportExcelModal.tsx:357-373 | 弹窗规范 | Modal 承载 5 步导入向导（**已无引用，孤儿死代码**） | 删除；或迁 Drawer 对齐 V6 导入抽屉 | P1 |
| 全簇金额强调色 | Step3:160,242 / ProductDetailViews:258,280 / Wizard:1674 / css:218,264 | 一致性 | "金额合计"同语义 5 个屏 5 种色（`#0958d9`/`#c00`/`#1890ff`/`#667eea`/`#1677ff`） | 定义单一"金额强调"token 全簇引用 | P1 |
| quotation.css | css:137-529 多处 | 一致性 | `qt-*` 卡片整套 Material/Chakra 色板（tab `#1976d2`、加行 `#4caf50`、徽章 `#1565c0`、步骤 `#48bb78`、公式 `#2e7d32`、删除 `#c62828`） | 收敛到品牌/AntD 变量 | P1 |
| Step2 vs ProductDetailViews | Step2:3371 vs ProductDetailViews:172 | 一致性 | 同一视图切换：编辑页 Segmented 带 emoji，详情页纯文字 | 二者统一 + 抽共享常量 | P1 |
| Step2 数据表 | css:156-161 vs css:192 | 数据密度 | 普通数值/输入单元格左对齐，仅公式格右对齐，同表参差 | 数值/金额列统一右对齐 | P1 |
| Wizard | Wizard:1658-1674 `toLocaleString` | 一致性 | Step5 金额无固定小数，与详情/Step3 的 2 位不一致 | 统一 `formatCurrency` | P1 |
| ExcelView | ExcelView.tsx:384,237,302 | 颜色 | 固定值单元格用 AntD **v4** 蓝 `#1890ff/#e6f7ff/#91d5ff`，与 Step2 料号徽章 v5 蓝不一 | 换 v5 蓝阶 | P2 |
| Step2 必填标识 | Step2:2236 | 表单 | 手写星号 + Chakra 红 `#e53e3e`，无内联校验；与 CreateForm 的 Ant `Form.Item required` 两套 | 星号红统一 `#ff4d4f` 或纳入 Form.Item | P2 |
| Wizard 步骤导航 | Wizard:1735-1772,1687 | 向导 | `Steps` 未开 `onChange` 不能点已完成步跳转；末步底部"下一步"被 disabled 成死角，真正"提交审批"在正文另一颗按钮 | 允许点已达步回退；末步底部换主 CTA 或隐藏导航行 | P2 |
| Step2 头部工具条 | Step2:3396 | 响应式 | 右簇容器无 `flexWrap`（左簇有），窄屏多按钮挤出 | 右簇加 `flexWrap:wrap` | P2 |
| QuotationDetail | QuotationDetail.tsx:437-527 | 信息层级 | 顶部 `Space wrap` 最多并列 ~12 颗按钮（危险/状态流转/工具混排），仅靠 primary 区分 | 按语义分组，危险降权，工具收进"更多▾" | P2 |
| Step3 | Step3.tsx:93-135,221 | 数据密度 | "年用量/折扣率"InputNumber 列未右对齐；表头"原小计 (单价)"叠词 | 数字输入列右对齐；表头精简 | P2 |
| AddProductModal | 文件名/类型/state | 命名 | 实现已是 Drawer（960，正确）但文件/props/state 仍叫 Modal，命名误导 | 重命名 `AddProductDrawer` | P2 |
| OrphanRowsDrawer | OrphanRowsDrawer.tsx:162 | 无障碍 | `closeIcon` 换成裸 `<span>×</span>`，无 aria-label、热区小 | 用 `CloseOutlined` 或补 aria | P2 |
| Step2 删行 | Step2:2717 | 无障碍 | 删行是 14px `✕` 文本、仅靠红色传达；`🔒/🔗` emoji 表不可删 | 换 `DeleteOutlined`+aria；状态用带 tooltip 的图标 | P2 |
| 全簇空态/图标 | Step2:3504 / AddProductModal:219 等 | 一致性 | 空态、视图切换、3D 预览大量 emoji（📦⏳🧊🚫） | 空态用 `Empty`/AntD 图标 | P2 |
| Step2/ReadonlyProductCard | 内联色/字号散布 | 布局 | 大量内联 hex + magic fontSize(11/12/13/16/18) 跨文件重复 | 抽公共色/字号常量或 class | P2 |
| QuotationList | QuotationList.tsx:96-101 | 数据密度 | "总金额"列未右对齐且无小数；文本列与金额列同左对齐 | 金额列右对齐 + 2 位 | P2 |

### 2.4 核价模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| CostingTemplateList | :280-308 | 弹窗规范 | 新建 Excel 模板表单用 Modal（同簇其它新建都是 Drawer） | 换 `Drawer width=520` | P0 |
| CostingPartDataPage | :77-84 | 一致性 | 主按钮 `ImportOutlined` 图标 + label 里又塞 `📥` 双图标 | 去 emoji，保留图标 | P1 |
| CostingPartDataPage | :117-134 | 空态 | 顶部大 Empty + 下方空 Tab 双重空态；element-bom Tab 误显"请先输入料号" | 空态收进各 Tab；element-bom/plating 排除 hfPartNo 判断 | P1 |
| CostingPartDataPage | 数值列 :183,276,356,606 | 数据密度 | 单价/单套成本/损耗率/加工费全左对齐、精度不一 | 统一右对齐 + tabular-nums + 精度口径 | P1 |
| ManualPriceEntryDrawer | :81-88 | 一致性 | 取消/提交放 Drawer 顶部 `extra`，本簇其它抽屉都在底部 footer | 改底部 footer 右对齐 | P1 |
| CostingTemplateConfig | :313-356,538 | 响应式 | 变量路径列 `Space.Compact` 塞 4 按钮，Table 无 `scroll{x}`，窄屏溢出 | Table 加 `scroll{x:1200}`；次要按钮收"更多" | P1 |
| CostingTemplateConfig | :271 | 加载 | 裸 `<Spin/>` 挂左上，标杆是 SummaryDetail:167 居中 | 复用居中 Spin | P1 |
| CostingOrderListPage | :61-231 | 数据密度 | 9 列约 1390px，SelectableTable 未传 `scroll` | 传 `scroll{x:1400}` | P1 |
| Config/PartData | Config:336,344,353 / PartData:83 | 图标 | `📚字段库/🌐全局变量/▶试算/📥` emoji 与 AntD 图标混用 | 换 `BookOutlined/GlobalOutlined/CaretRightOutlined` | P2 |
| 全簇 | Config:429/List:232 `<h2>`；`#8c8c8c/#bbb/#999` | 一致性 | 标题一处 `<h2>` 一处 `Title level4`；副标题灰硬编码 | 统一 `Title level4`+`Text secondary` | P2 |
| CostingTemplateConfig | :437-444 | 信息层级 | "调试样本零件号"输入常驻页头，与"保存"同级 | 折进"试算"区/次级工具条 | P2 |
| CostingTemplateConfig | :226,278 | 表单 | 只读态（非 DRAFT）逐个 disabled 无任何说明 | 顶部加 `Alert` 说明"已发布不可编辑" | P2 |
| CostingTemplateList | :282 | 表单 | 名称 `required` 无 `message` | 补中文 message | P2 |
| CostingOrderListPage | :256-300 | 弹窗规范 | 驳回 Modal 里嵌原因 TextArea（列表+确认合规，但夹表单属灰区） | 现状可接受；原因项变多则升 Drawer | P2 |
| CostingPartDataPage | 分页/抽屉宽 | 一致性 | 分页策略（50/false）与抽屉宽（480/520/560）随手拍 | 统一分页与宽度档位 | P2 |
| CostingSummaryListPage | :178-184 | 信息层级 | 说明 Alert 夹在两个表单项中间打断填写 | 上移表单首或下移末尾 | P2 |
| CostingSummaryDetailPage | :231-240 | 数据密度 | 结果 Statistic 无千分位；`formulaUsed` 硬编码 `#999` | 加千分位；公式行 `Text secondary` | P2 |
| ElementPriceCenterPage | :23-27 | 一致性 | 货币色表键用 `RMB`，其它页用 `CNY` | 统一 `CNY` | P2 |
| ElementPriceCenterPage | :164-172 | 无障碍 | 非管理员"录入新参考价"直接不渲染 | 可保留；贴规范则 disabled+tooltip | P2 |
| ManualPriceEntryDrawer | :74,84 | 布局 | `width=720` 承载仅 5 短字段被拉长；label"提交"其它抽屉"保存" | 宽度收 520；动词统一"保存" | P2 |
| CostingTemplateConfig | :272 | 空态 | 模板不存在裸 `<div>模板不存在</div>` | 换 `<Empty>` | P2 |

### 2.5 组件管理模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| ComponentManagement | :238-308 | 弹窗规范 | `DataSourceModal` 三步向导用 Modal（DATA_SOURCE 活跃入口） | 迁 `Drawer width=720`，顶部 Steps+底部 footer | P0 |
| FieldConfigTable | :701-744 | 弹窗规范 | HTTP_API 配置用 Modal，而 DefaultSourceEditor 同款是 Drawer（双实现） | 改 Drawer 或复用 DefaultSourceEditor | P1 |
| ComponentManagement | :1647,881 | 弹窗规范 | "新建组件""新建/重命名目录"表单用 Modal | 迁 Drawer（480） | P1 |
| FieldConfigTable | :95-586 | 数据密度/响应式 | 字段配置表 13 列，SortableTable 无 `scroll{x}`，嵌 flex:1 面板，窄屏挤压/换行 | 加 `scroll{x:1200}`；低频列折"更多" | P1 |
| DefaultSourceEditor | :112-148,215 | 表单反馈 | 必填项空时 `submit()` 直接 return，无 message、无字段报错 | 每个 return 前补 `message.warning` 或用 Form 校验 | P1 |
| 全簇样式 | styles.css 多处 + 内联 | 一致性 | 三套主蓝并存（Element `#409eff`/AntD `#1677ff`/渐变 `#667eea`）+ 内联 `#08979c/#d46b08/#722ed1` | 抽 `--cpq-primary`，语义色收敛一套 | P1 |
| ComponentTree（遗留） | :167,236,431 | 死代码 | 目录/组件 CRUD 全 Modal，且 0 外部引用（已被 MasterList 取代） | 删 `ComponentTree.tsx`+`HeaderPreview.tsx` | P1 |
| 抽屉宽度 | ImportDrawer:154(760)/DefaultSourceEditor:206(520)/PathPicker:258(560)/DataSourceModal:244(600) | 一致性 | 760/520/560/600 非标准档 | 归 480/720/960/1200 | P2 |
| MasterList | :683-688 | 无障碍 | 卡片勾选是自绘 `div.cmm-chk`（16px 方块+✓文本），不可键盘聚焦、渐变底辨识低 | 换 AntD `Checkbox` 或加 role/aria | P2 |
| FieldConfigTable | :552-584 | 无障碍 | 删除/排序 ↑↓ icon 无 title/aria；金额·小计 Checkbox 仅靠列头传达 | 加 Tooltip/aria-label | P2 |
| 保存全部草稿 | :1696,1024 | 可读性 | 勾选项/失败明细显示裸 `componentId`(UUID) | 改显示组件 `name（code）` | P2 |
| CrossTabRefDrawer（遗留） | :270-383 | 信息层级 | 分区编号跳号缺 3（1/2/无/4），且 0 引用 | 补编号或随死代码删 | P2 |
| ConfigGuideDrawer | :66 | helper | 标题"字段类型—5 种"，实际 >5 种（漏 LIST_FORMULA 等） | 去硬数字，补条目 | P2 |
| 布局根容器 | styles.css:6,11 | 响应式 | `.cm-layout height:calc(100vh-112px)` 写死头高，`margin:-24px` 负边距 hack | flex 撑满替代魔数 | P2 |
| FieldConfigTable | :452-570 | 触控 | 宽度预设/排序箭头 `height:16-18 fontSize:10-11`，热区<24px | 提 class，扩热区 ≥24px | P2 |
| 内容/配置列 | :183-389 | 一致性 | 字段类型内联 hex 语义色（GV橙/BASIC青/HTTP紫/LIST蓝），无图例 | 抽"字段类型色"token + 帮助里加色图例 | P2 |
| 空态/占位对比度 | FormulaListPanel:386/HeaderPreview:20 | 无障碍 | 占位用 `#bfbfbf/#c0c4cc`（≈1.6-2:1） | 下沉到 `#8c8c8c` 一档以上 | P2 |
| 详情头动作区 | :1551-1564 | 信息层级 | "核价树"配置 Checkbox 混入操作按钮区 | 移入组件级配置行，动作区只留操作 | P2 |

### 2.6 模板管理模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| ProductTemplateBinding | :600-632 | 弹窗规范 | 含 `<Steps>` 两步向导用 `Modal width=800` | 迁 `Drawer width=800`，导航放 footer | P0 |
| ProductTemplateBinding | :232-287,544 | 列表规范 | 裸 Table + 行内 `<Switch>默认` + 行内删除（无二次确认） | 换 SelectableTable，动作上提+删除确认 | P0 |
| TemplateConfiguration | :426-441 | 一致性 | "Excel视图"是裸 `<button>` 全内联（`#1890ff` 实心），紧挨药丸式 ViewToggle | 并入 ViewToggle 三段式 | P1 |
| 全簇主色 | styles.css:37-462 + 内联 | 一致性 | 四套蓝并存（`#667eea/#1976d2/#1890ff/#1677ff`） | 定义 `--tm-primary` 收敛 | P1 |
| TemplateList | :331-389 | 弹窗规范 | 新建模板表单用 Modal | 迁 `Drawer width=480` | P1 |
| ProductAttributesGrid | :222-252 | 弹窗规范 | 添加/编辑属性表单用 `Modal width=400` | 迁 `Drawer width=480` | P1 |
| TemplateFormulasPanel | :238-383 | 弹窗规范 | 选择函数 master-detail 选择器用 `Modal width=900` | 迁 `Drawer width=960` | P1 |
| TemplateFormulasPanel | :465-534 | 弹窗规范 | 试算表单+结果用 `Modal width=600` | 迁 `Drawer width=720` | P1 |
| TemplateFormulasPanel | :956-988 | 列表规范 | 公式表末列行内 编辑/删除/试算 三 icon 按钮，裸 Table | 上提工具栏+SelectableTable；icon 补 aria | P1 |
| TemplateConfiguration | :444-447 | 一致性 | 主 CTA `💾 保存模板` emoji + 裸 button，与"发布模板"(Ant Button+图标) 不一；两个渐变主按钮争焦 | 统一 `Button icon=SaveOutlined`，只留一个渐变主色 | P1 |
| ComponentPalette | :58 + Config:62 | 无障碍 | `onAddComponent` 解构为 `_onAdd` 从未用 → 组件只能拖拽加；DndContext 无 KeyboardSensor | 加 onClick 兜底或补 KeyboardSensor | P2 |
| ProductAttributesGrid | :163-167 | 拖拽 | 属性重排用原生 HTML5 draggable，其余用 dnd-kit（两套范式，无落点线/键盘） | 统一 dnd-kit SortableContext | P2 |
| ProductAttributesGrid | :184,210-213 | 无障碍 | 拖拽柄/删除 icon `#ccc`（≈1.6:1），删除无 title | 提 `#8c8c8c`(hover 红)+Tooltip | P2 |
| TabJoinFormulaDrawer | :496 | 一致性 | `width=1100` 非标准档 | 归 960/1200 | P2 |
| TemplateConfigPanel | :155-165 | 表单 | 发布 Modal 内 `Form.Item` 未包 `<Form>`，游离 label | 包 Form 或改 Space；顺带迁 Drawer | P2 |
| TemplateConfigPanel | :82-117 | 表单 | 编辑态 name 无 required 校验，与新建不一致 | 加 rules+必填标记 | P2 |
| TemplateConfiguration | :397 + Palette:134 | 加载 | 三栏编辑器/调色板 loading 用裸文字"加载中..."，GvBinding 用 Skeleton | 统一 Skeleton/Spin | P2 |
| styles.css | :13-448 | 响应式 | `.tm-layout calc(100vh-112px)`+`margin:-24px`，左右栏固定 280/350px 无断点 | flex min-width+@media 折叠右栏 | P2 |
| TemplateList | :252-317 | 布局 | 筛选行 Col 合计 6+4+4+5+4+4=27 超 24，末列换行 | 收敛 span≤24 或 `Space wrap` | P2 |
| SubtotalFormulaBar/TabFieldMatrix/CardFormulaDrawer | 多处 | 无障碍 | 可点 `<span>`/`<Tag onClick>` 充当按钮，无 role/tabIndex/键盘 | 加 role=button+tabIndex+onKeyDown 或换 Button | P2 |
| GvBindingPanel | :77,272 | 无障碍 | 子表拖拽柄无 title/aria；末列行内"移除"（子表白名单可豁免） | 拖拽柄补 aria | P2 |
| CardFormulaDrawer | :654-703 | 可读性 | "配置说明"7 段 12px 密排文字，与右侧面板重复引导 | 折叠 Collapse 分类，示例用代码块 | P2 |
| TemplateConfiguration | :425 + ViewToggle:12 | 交互状态 | `excel` 态下 ViewToggle 两键都非激活（呈空选中） | 三态并轨后消除 | P2 |

### 2.7 主数据 / 基础数据 / 导入模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| ComparisonTagManagement | :128-158 | 弹窗规范 | 新增/编辑标签表单用 Modal | 迁 `Drawer width=480` | P1 |
| ProductCategoryManagement | :146-177 | 弹窗规范 | 新增/编辑分类表单用 Modal | 迁 Drawer | P1 |
| GlobalVariablePage | :577-694 | 弹窗规范 | "编辑/新增明细""新建全局变量"表单用 Modal | 表单迁 Drawer（确认保留） | P1 |
| GlobalVariablePage | :669-678 | 一致性/表单 | 类型用原生 `<select>`+手写 border 冒充 AntD，外套废弃 `Input.Group compact` | 换 AntD `Select` | P1 |
| ProductCategoryManagement | :174-176 | 表单 | "状态"用自由文本 Input（placeholder `ACTIVE/DISABLED`），同概念 ComparisonTag 用 Select | 改 Select | P1 |
| PartModelList | :333-335 | 无障碍 | 特征审核用原生 `<input type=checkbox>` | 换 AntD `Checkbox` | P1 |
| PartModelList | :146-383 多处 | 图标 | 大量 emoji（📦📖✏️🗑✓←→🎬🔐）替代 AntD 图标 | 换 AntD 图标 | P1 |
| 多抽屉 | InternalMaterial:157(560)/ExcelTemplate:144(600)/PartModel:380(640)/GlobalVar:538(780)/923(920)/PricingImport:96(840) | 一致性 | 宽度散落 560/600/640/780/840/920 | 归 480/720/960/1200 | P1 |
| V6BomQueryTab | :206-275 | 表单 | 过滤区手写 `<div>` label+`<span>*</span>` 拼必填，不走 Form.Item | 用 `Form layout=inline`+`required` | P2 |
| MasterDataPage/VersionHistory/MappingEditor | :148 / :175 / :295 | 一致性 | 图标/箭头硬编码 `#1677ff`/`#1890ff` | 用 token | P2 |
| MappingEditor | :272-373 | 一致性/表格 | 列映射用原生 `<table>`+`thStyle/tdStyle` 常量；"未配置视图"用自定义黄框 div | 换 AntD Table / Alert | P2 |
| Spin 加载态 | MasterDataPage:195/RowDetail:174/TableData:288/VersionCompare:125 | 加载 | 独立 `<Spin tip=...>` 在 v5 tip 不显示且告警 | Spin 包裹内容或 Skeleton | P2 |
| ImportHistoryList | :204 | 加载 | 详情抽屉 loading 用纯文本"加载中..." | 换 Skeleton/Spin | P2 |
| 数值列右对齐缺失 | V6BomQuery/InternalMaterial/V6ProcessCrud/GlobalVar/MasterDataViewer | 数据密度 | 数量/损耗率/单重/取值列左对齐、非等宽 | `align:right`+tabular-nums | P2 |
| InternalMaterialManagement | :186 | 表单 | 保存 `<Button block>` 在表单体内且无取消 | 动作上提 footer+补取消 | P2 |
| PartVersionPage | :23-33 | 信息层级 | Tab 子内容带 `padding:24`+`Title level3`+说明 Alert，兄弟 Tab 无 padding | 去页面级标题与外层 padding | P2 |
| MasterDataHubPage | :20,22-25 | 一致性/层级 | 页头用裸 `<h2>`；"导入核价数据"按钮常驻但仅部分 Tab 有意义 | 换 Title；按 activeTab 条件显示 | P2 |
| 主数据浏览三页 | MasterDataPage/TableViewer/VersionHistory | IA | 三个高度重叠的浏览入口，用户不知进哪个 | 合并多视图切换或明确分工 | P2 |
| VersionCompareDrawer | :149-156,68-96 | 一致性/无障碍 | 组件内注入全局 `<style>` 类；diff 仅靠黄绿底传达 | rowClassName+CSS Module；加图标/加粗非色标识 | P2 |
| ImportConfigManagement | :164,122-131 | 响应式/无障碍 | 左栏固定 380px 无 min，右侧挤压；行内编辑 icon 无 title | flex+min 或栅格；加 Tooltip | P2 |
| DataSourceEdit | :217-315 | 无障碍 | 参数表上移/下移/删除 icon 无 title/aria | 加 title/aria，边界 disabled | P2 |
| DataSourceList | :129-148 | 信息层级 | 无标题/说明，直接工具栏+表 | 补 Title+副标题 | P2 |
| PricingBasicDataImportDrawer | :118 | 反馈 | Alert 文案含反引号原样渲染 | 去反引号，用 `Text code` | P2 |
| 两导入抽屉不对齐 | PricingBasic(840) vs ProcessMaster(720) | 一致性 | 同为导入抽屉，宽度/结果布局/模板下载位各异 | 抽统一"导入抽屉"模板 | P2 |
| ExcelTemplateDrawer | :180-243 | 一致性 | 向导"下一步"按钮放每步 body 内，PartModel 放 Drawer extra | 统一到 footer/extra | P2 |
| PartModelList | :257-370 | 布局 | `fontSize:11.5/10.5` 非整数 + 满屏 inline 色块 | 归 12/13 字号 token | P2 |
| PartModelList/GlobalVariablePage | :174 / :382-409 | 列表规范 | 行内放危险/状态动作（设为当前/删除/变更历史） | 危险/状态动作移工具栏 | P2 |
| TableOverviewCard | :33-38 | 无障碍 | 禁用卡用 `opacity:0.5`，文字对比跌破可读 | 置灰底+"未启用"标签替代降透明 | P2 |
| V6BomItemDetailDrawer | :55-147 | 布局 | 同抽屉 Descriptions `labelStyle.width` 在 120/130 间摇摆 | 统一 label 宽 | P2 |
| MasterDataPage | :71-89 | 反馈 | Alert 暴露 `VITE_USE_MOCK_MASTER_DATA` 环境变量给用户 | 改业务语言，技术细节仅日志 | P2 |
| GlobalVariablePage | :382 | 数据密度 | 操作列固定 320px 含 3 按钮，大屏浪费窄屏溢出 | 次要动作入 dropdown 或工具栏 | P2 |

### 2.8 客户 / 定价 / 产品 / 配置模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| CustomerMaterialMappingTab | :154-201 | 弹窗规范 | 添加映射 + Excel 批量导入两处用 Modal（同簇 MaterialImportDrawer 已 Drawer） | 迁 Drawer | P0 |
| PricingStrategy | :436-503 | 弹窗规范 | 新建/编辑策略用 `Modal width=700`（表单+阶梯表） | 迁 `Drawer width=720` | P0 |
| ProductManagement | :290-336 | 弹窗规范 | 导入产品用 Modal（与 MaterialImportDrawer 冲突） | 复用 Drawer 形态 | P0 |
| CustomerLeadList | :144-201 | 弹窗规范 | 线索审核（Descriptions 详情+表单）用 Modal | 迁 `Drawer width=520` | P0 |
| CustomerLeadList | :115-139 | 列表规范 | 裸 Table + 行内 `🔗绑定/+新建/🚫拒绝` | 换 SelectableTable，动作上提 | P0 |
| CustomerLeadList | :100-130 | 数据密度/无障碍 | 状态直接显示后端英文枚举 `PENDING_REVIEW/CONVERTED/REJECTED`，仅靠 emoji+色传达 | statusMap 输出中文，Tag 文字化 | P1 |
| ModelConfigManagement | :386-470 | 表单 | 上传抽屉不用 Form，必填手写 `<span style=color:#f56c6c>*</span>`，校验靠 message.error toast | 用 Form+Form.Item rules | P1 |
| ModelConfigManagement | :333-580 多处 | 一致性 | 大面积 Element-Plus 灰阶 `#909399/#606266/#303133/#e4e7ed/#f56c6c/#dcdfe6` | 换 AntD token | P1 |
| PricingStrategy | :280-281 | 一致性 | 客户选中态 `#e6f4ff`+`borderLeft:3px #1677ff`（非品牌紫） | 选中色走 token | P1 |
| ProcessSelection | :236-324 | 一致性 | 分类选中/卡边框/Badge 硬编码 `#1677ff` 4 处 | 走主色 token | P1 |
| CustomerManagement | :497-499 | 表单 | "设为主要联系人"用原生 `<input type=checkbox>` | 换 AntD Checkbox/Switch | P1 |
| CustomerManagement | :415-430 | 信息层级 | "统计信息（只读）"分区里塞可编辑"信用额度" | 移出只读区或分区改名 | P1 |
| 列表页(全簇) | Element/MaterialRecipe/SelTemplate(Card extra) vs Customer/Product/Mapping(toolbar) | 一致性 | 搜索+主按钮位置两套流派（表头右 vs 表格上方） | 统一走 SelectableTable toolbar | P1 |
| ProductManagement/HubPage | Product:193+Hub:15 | 信息层级 | 同屏两个"产品管理"标题（Hub `<h2>` + Tab 内 `Title level4`） | Tab 内去重复 Title | P1 |
| MaterialRecipeBindPartsDrawer | :66-72 | 弹窗规范 | 料号转移二次确认用原生 `window.confirm` | 换 `Modal.confirm` 列明细 | P1 |
| MaterialRecipeEditDrawer | :263-296 | 布局/响应式 | 6 个 Form.Item `Space wrap` 横排，写死 width 160/180/140/100 | 改 Row/Col 栅格自适应 | P1 |
| Drawer 宽度(全簇) | ElementEdit:520/BindParts:900/RecipeEdit:1080/Suggest:1200/Industry:480/SelTemplate:720/ModelConfig:480/720 | 一致性 | 520/900/1080 非标准档 | 归四档 | P1 |
| CustomerLeadList | :96-153 | 图标/无障碍 | emoji 泛滥（📩⏳✅🚫🔗ⓘ⚠），删除/拒绝靠 emoji 传达 | 换 AntD 图标+文本 | P1 |
| PricingStrategy | :355-376 | 列表规范 | 策略卡 extra 行内 编辑/禁用/删除，删除用零散 Popconfirm | 删除改 `Modal.confirm` 列策略名 | P1 |
| SelTemplateManagement | :358-361 | 布局 | Drawer footer 取消:保存 = `flex:1:flex:2`（保存宽两倍） | 右对齐 Space 或等宽 | P2 |
| IndustryManagement | :113-118 | 布局 | toolbar 用空 `<div/>` 占位撑 justify-between，且无搜索框 | 去空 div，补搜索框 | P2 |
| PricingStrategy/ProcessSelection | :238 / :212 | 响应式 | `calc(100vh-112px)`/`calc(100vh-200px)` 写死视口偏移 | flex 撑满替代硬算 | P2 |
| ProcessSelection | :354-355 | 交互 | 行 hover 用内联 `onMouseOver/Out` 改 style；拖拽无落点指示线 | hover 交给 CSS，拖拽加指示线 | P2 |
| 数字列对齐(多处) | PricingStrategy:403 / MaterialRecipeEdit:191 / ModelConfig:308 | 数据密度 | 起订金额/折扣率/含量%/文件大小未右对齐 | `align:right`+等宽 | P2 |
| 低对比文字 | MaterialRecipeSuggest:143 / ProcessSelection:294 | 无障碍 | 次要文字用 `#bbb`(≈2.8:1)/`#999` | 提到 `colorTextTertiary` 或 ≥#8c8c8c | P2 |
| icon-only 缺 title | PricingStrategy:227 / MaterialRecipeEdit:255 / ProcessSelection:377 | 无障碍 | 纯图标删除按钮无 title/aria | 包 Tooltip | P2 |
| 危险 `<a>` 硬编码红 | CustomerManagement:231,239 / CustomerLeadList:137 | 一致性 | 删除/拒绝链接内联 `color:red`/`#f5222d` | 用 `colorError` 或 `Button danger` | P2 |
| 空态不一致 | PricingStrategy:308 / SelTemplate:343 vs ProcessSelection/ModelConfig 用 Empty | 空态 | 部分纯文字 div，部分 `<Empty>` | 统一 `<Empty>` | P2 |
| ModelConfigManagement | :333 | 布局 | Card 说明用 `marginTop:-8` 负边距贴标题 | 用正常间距或 Card styles | P2 |
| ProductManagement | :216 | 布局 | 根节点 `padding:24 bg#fff` 但已在 HubPage 白底容器内（白叠白） | Tab 子页去重复容器样式 | P2 |
| ModelConfigManagement | :421-468 | 图标 | 按钮文案内嵌 emoji（📦📷🪄） | 换 AntD 图标 | P2 |
| CustomerManagement | :87-100 | 交互 | 编辑抽屉靠 `setTimeout(50)` 回填，先空后填一帧闪烁 | 用 initialValues/useEffect 同步 | P2 |

### 2.9 3D 选配 / 特征库 / 系统管理模块

| 页面/文件 | 位置(file:line) | 类别 | 问题描述 | 优化建议 | 严重度 |
|---|---|---|---|---|---|
| ConfiguratorPage | :447,510 | 弹窗规范 | "提交配置"三步向导 + "分享给客户"表单用 Modal | 迁 Drawer | P0 |
| ConfiguratorInstanceList | :191 | 弹窗规范 | "绑定到已有报价单"表单用 Modal | 迁 Drawer | P0 |
| ApprovalRule/Department/Region | :208 / :152 / :77 | 弹窗规范 | 三个新增/编辑表单全用 Modal（UserManagement 已 Drawer，自相矛盾） | 统一迁 Drawer | P0 |
| 全列表簇 | ConfiguratorInstanceList/Shares/TemplateList、FeatureLibrary/GroupDetail、Department/Region/User | 列表规范 | 行内 `<a>` 动作（编辑/停用/删除/解绑/重置密码），仅 ApprovalRule 合规 | 迁 SelectableTable，动作上提+enabledWhen | P0 |
| 无障碍/图标 | ConfiguratorInstanceList:173(`<a>👁</a><a>📋</a>`)、TemplateEditor:483(✏️🗑)、GroupDetail:201 | 无障碍 | emoji 图标动作零 title/aria，纯符号传达 | 每个 icon 动作加 Tooltip/title 或补文字 | P0 |
| 一致性/主色 | ConfiguratorPage:353,360,413 多处 | 一致性 | configurator/feature 硬编码 `#1890ff`(v4 蓝)+`#52c41a`；system 用 `#1677ff`；主色 `#667eea` 全簇零使用 | 抽 token(primary=#667eea) 替换 | P1 |
| 图标体系混用 | configurator/feature(全 emoji) vs system/monitor/change-log(全 AntD SVG) | 一致性 | 跨簇图标语言分裂，emoji 当结构性图标混入 SVG 体系 | 结构性图标统一 AntD icon | P1 |
| 主 CTA 语义色 | ConfiguratorPage:425,461 / Start:138 / TemplateEditor:436,566 | 信息层级 | 所有主操作强改绿 `#52c41a` 覆盖 primary（绿=成功语义被挪用），与蓝 primary 并存主次混乱 | primary 用主色，绿仅留成功反馈 | P1 |
| 假控件 | ConfiguratorInstanceList:103-104 | 表单反馈 | 日期 `RangePicker` onChange 只 `message.info('后端待实现')`；"复制模板/导入 JSON"同样只弹 info | 未实现控件 disabled+tooltip 或隐藏 | P1 |
| 响应式 | ConfiguratorPage:283-316 | 响应式 | 全屏配置器主区 `Col span=12` 无断点，预览/选项固定 `height 460`；右侧再 `Col span=12` 切半致卡片过窄 | 加 xs/md/lg 断点，高度 min-height 自适应 | P1 |
| 响应式/溢出 | ConfiguratorTemplateEditor:443,604 | 表格/响应式 | 选项表 9 列无 `scroll{x}` + 并排 280px 定宽侧栏 | Table 加 scroll，侧栏可折叠 | P1 |
| 加载占位 | ConfiguratorStartPage:89 | 加载 | 纯文本 `<div>加载中...</div>` | 换 Spin/Skeleton | P1 |
| 加载占位 | ConfiguratorPage:232 / InstanceDetail:56 / TemplateEditor:378 / GroupDetail:129 | 加载 | 页面级 `if(loading) return <Spin/>` 裸 spinner 缩左上 | 居中容器或整页 Skeleton | P1 |
| 表单校验文案 | TemplateEditor:641,657 / TemplateList:123 | 表单 | 大量 `required` 无 `message`，中文界面报英文默认文案 | 补中文 message | P1 |
| 页面外边距 | configurator(padding:16)/Department(无)/DdlExtension(padding:24) | 布局 | 页面外边距 16/24/0 三种混用，切换时内容位置跳动 | 由布局层统一给内边距 | P1 |
| Drawer 宽度 | TemplateEditor:625(560/720/680/960)/ValueEdit:221(780)/Shares:150(580) | 一致性 | 560/580/680/780 非标准档 | 归四档 | P2 |
| 危险色三写法 | Region:61,123(`color:red`)/多处 `#f5222d`/AntD danger | 一致性 | 同一危险语义三种表达 | 统一 danger token | P2 |
| 字号魔数 | InstanceDetail:486 / TemplateEditor:784 / ConfiguratorPage:308 | 布局 | 遍布 `fontSize:10.5/11.5/12.5`（<11px 可读差） | 用字号阶梯 token | P2 |
| 反馈 | ConfiguratorInstanceList:69 | 反馈 | `message.success('...<br/>...')` 里 `<br/>` 字面显示 | 用 notification 或去 HTML | P2 |
| 信息层级过载 | ConfiguratorPage:260-430 | 信息层级 | 演示 banner+客户 banner+预设 banner+摘要+价格栏多彩色 callout 竖叠争焦 | 收敛为 1 个主强调 | P2 |
| 定宽长文本 | ValueEditDrawer:389-391 | 表格 | `Radio.Button` 定宽 33.3% 承载长标签易换行 | 标签缩短或宽度自适应 | P2 |
| 明文密码 | UserManagement:64,95 | 反馈 | 初始/重置密码 `Modal.success` 明文展示无一键复制 | 带复制按钮的 Result | P2 |
| Descriptions 密度 | InstanceDetail:109 / TemplateEditor:392 | 表格 | `Descriptions column=3` 无最小宽约束，窄屏挤 | column 响应式 `{xs:1,md:3}` | P2 |
| StatCard 可点性 | ConfiguratorSharesPage:100-103 | 信息层级 | 4 统计卡 3 个可点过滤，"👁已被访问"独不可点，无视觉区分 | 统一可点态样式/cursor | P2 |

---

## 3. 修复路线图（3 阶段，均不改功能）

> 建议按"先补强制规范硬伤 → 再做 token 化收敛 → 最后打磨"的顺序推进；每阶段独立可交付、独立可回归。

### 阶段一：清偿强制规范硬伤（P0，PR 评审强制项）
1. **Modal→Drawer**：抽 `FormDrawer/WizardDrawer/ImportDrawer` 三个壳，迁移约 15 处表单/向导 Modal（组件/模板/定价/产品/客户线索/系统/核价/配置器）。
2. **裸 Table→SelectableTable**：迁移约 8+ 处列表（客户线索、模板绑定/公式、系统各管理页、配置器列表），动作上提工具栏 + `enabledWhen`，危险动作走列出所选项二次确认。
3. **删死代码**：`ComponentTree.tsx`、`HeaderPreview.tsx`、`CrossTabRefDrawer.tsx`、`quotation/ImportExcelModal.tsx`；`AddProductModal.*` 重命名为 `Drawer`。
4. **首页**：`Dashboard.tsx` 换成真正的工作台（复用 `StatCard`，中文文案）。

> ⚠️ 迁移涉及 `QuotationStep2.tsx / FieldConfigTable.tsx / ProductTemplateBinding.tsx` 等协议级文件时，按 `CLAUDE.md` 跑 Playwright E2E（`quotation-flow.spec.ts` + `composite-product-flow.spec.ts`）复测，勿因样式迁移引入渲染回归。

### 阶段二：design token 化收敛（P1，覆盖面最广、投入产出比最高）
5. **主色 token**：定义 `--cpq-primary:#667eea` + AntD `colorPrimary` 收敛；grep 替换全站 63 处 `#1677ff/#1890ff` 及 Element/Material/Chakra 外来色板。
6. **金额强调色单一常量** + **数值列统一右对齐 tabular-nums** + **金额精度统一 `formatCurrency`**（对外 2 位）。
7. **emoji→AntD 图标**（约 40 文件，含侧栏菜单、主 CTA、状态图标）。
8. **抽屉宽度归四档**（480/720/960/1200）。
9. **统一页头组件 + 面包屑 + 标题层级**（主标题统一 `level={4}`）；Hub/Tab 子页去重复标题与外层 padding。
10. **加载/空态统一**（居中 Spin / Skeleton / `<Empty>`；修 v5 `Spin tip` 失效）。

### 阶段三：无障碍与响应式打磨（P2）
11. icon-only 按钮补 `aria-label`/`Tooltip`；原生 `<input>/<select>/<table>`、自绘勾选框换 AntD 组件。
12. 低对比灰（`#bfbfbf/#ccc/#bbb`）提到 ≥`#8c8c8c`；纯颜色/纯 emoji 传达状态补图标/文字。
13. 字号魔数（10.5/11.5/12.5）归整到 12/13/14 阶梯；`calc(100vh-112px)`/负边距等脆弱布局改 flex。
14. 全屏配置器补 `xs/md/lg` 断点；宽表补 `scroll={{x}}`（当前 70 个含 Table 文件仅 19 处配置）。
15. Sider 加 `collapsible`；Hub 子路由菜单高亮修正。

---

## 附录 A：量化度量（全库扫描）

| 度量 | 数值 | 说明 |
|---|---|---|
| 页面 `.tsx`（非 test） | 176 | `src/pages` 下 |
| 内联 `style={{}}` | 2457 处 | 无 design token 层 |
| 硬编码 hex 颜色 | 1272 处 | 分布在 tsx |
| 硬编码 AntD 蓝 `#1677ff/#1890ff` | 63 处 / 39 文件 | 与主色 `#667eea` 冲突 |
| 用 `Modal` 的文件 | 36 | 含约 15 处承载表单/向导（违规） |
| emoji 当图标的文件 | ~40 | 含侧栏菜单、主 CTA |
| `Breadcrumb` / `PageHeader` | 0 / 0 | 无"我在哪"导航 |
| 有页面标题的页面 | 32 / 176 | 144 页无标题 |
| 主标题层级分布 | level4:20 / level5:21 / 零星 2,3 | 无统一规则、无 h1 |
| 含 `<Table>` 文件 / 配 `scroll={{x}}` | 70 / 19 | 宽表横向溢出兜底不足 |
| 抽屉宽度取值种类 | 17+ 种 | 规范仅 4 档（480/720/960/1200） |

## 附录 B：可作"收敛标杆"的现行规范页面
- **系统簇**：`system/`、`system-monitor/`、`system-config/`、`change-log/`（AntD 图标 + `Alert` + `scroll{x}` + 中文校验 + 空态齐备；`ChangeLogCenterPage` 新旧值对照、`LockMonitor` 心跳高亮）
- **核价 master-data 主流程**、`ElementPriceCenterPage`（数值右对齐 + strong 的唯一样板）
- **组件簇**：`SqlViewConfigDrawer`（dry-run 门控保存）、`ListFormulaConfigDrawer`、`ConfigGuideDrawer`（渐进式+空态）
- **报价簇**：详情页已全迁 Drawer；懒算/定位/冲突抽屉交互打磨深

---

*本报告仅覆盖排版/布局/交互/一致性/无障碍层面，不含功能性 bug 与业务逻辑评估；所有条目均可在不改动数据与计算逻辑的前提下整改。*
