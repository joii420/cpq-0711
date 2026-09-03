# 前端任务分解 · 报价单大单量前端分页与料号查询

> **范围**：本任务 **100% 是前端改动**，服务端零改动（见 `api.md`）。
> **视觉基准**：`原型图/`（7 份 + `index.html` 导航），定稿后 1:1 还原。
> **每项都标「服务的 AC」**，无 AC 的工作项一律不做。

---

## F-1 分页状态与切片（地基，其余全部依赖它）

**服务的 AC**：AC-1 / AC-2 / AC-3 / AC-4 / AC-21 / AC-22 / AC-25
**原型**：`原型图/01-编辑页-卡片视图-默认.html`

| 项 | 要求 |
|---|---|
| 状态位置 | 页码 `page` 与页大小 `pageSize` 提到 **`QuotationStep2` 组件内**（`mainTab`/`viewType` 同级，`:3960-3961`），使三视图天然共享 |
| 默认值 | `pageSize = 100`；可选 `[100, 200, 500]` |
| 🚨 切片口径 | **只切渲染，绝不切数据**。`lineItems` 这个 prop **原样保持全量**；新增一个 `pagedItems = visibleItems.slice(...)` 仅供渲染 |
| 🚨 PART 处理 | 分页游标以**可渲染卡片**为单位。现状 `:4065` 过滤掉 `compositeType === 'PART'`，切片必须作用在**过滤之后**的集合上，PART 随父卡片渲染、不单独占页 |
| 🚨 下标纪律 | 所有写路径（`handleRowChange` / `handleInputBlur` / `handleDeleteRow` / `handleAddRow` / `dsStateKey`）的下标**必须按对象引用映射回原 `lineItems` 数组**，不得使用页内下标（**AP-54 原样复发风险**，`:2962` 已有 `activeComponentDataIndex` 的同型修复可参照） |
| 重置规则 | 切页大小 → 回第 1 页；查询词变化 → 回第 1 页；`lineItems` 长度变化（加/删产品）→ 钳制页码到合法区间 |
| 翻页前 blur | 翻页前先 `(document.activeElement as HTMLElement)?.blur()`，把正在编辑、尚未 `onBlur` 回写的输入值落回 `lineItems`，否则该次输入随卡片卸载丢失 |

🚧 **还原边界**：原型图只定义分页相关元素（分页栏 / 查询框 / 空态 / 禁用态 / 全局序号）。
**产品卡片本身是占位示意，不是视觉基准** —— 卡片结构、页签集合与顺序、表格列定义、footer 列小计、公式列、BOM 树、
单元格编辑交互、卡片头部信息，**全部现状 1:1 保留，一个像素都不改**。逐项对照见 `原型图/*.html` 顶部的「🚧 还原边界」表。

🚫 **不许动的东西**（改了就会踩评审查出的 P0）：
- `QuotationWizard.tsx:1881` 的 `lineItems.map(...)` 求和 —— 总额必须继续在**全量数组**上算（AC-21）
- `buildDraftPayload` 的行来源 —— payload 必须继续是**全量 1845 行**（AC-22）
- `useDriverExpansions` 的入参 —— 必须继续收到**全量数组**，否则 COMPOSITE 父子映射断链（AC-25）
- `QuotationStep2.tsx:4020/4299`、`QuotationWizard.tsx:201` 三个 `lineItems.every(...)` 门禁 —— 必须继续读全量，否则渲染模式会按页翻转

---

## F-2 分页栏 UI

**服务的 AC**：AC-1 / AC-2 / **AC-2b**
**原型**：`01-...默认.html`（顶+底双分页器）、`03-...空态与禁用态.html`（禁用与不渲染态）

| 项 | 要求 |
|---|---|
| 位置 | **两级 `Segmented` 之下、独立一行**（原型 `.pgbar`）。🚫 不许并进任一 Segmented 同行——那会暗示「只管这个视图」，与需求 2 相悖 |
| 数量 | **列表顶部与底部各一个**，两者状态同步 |
| 组件 | AntD `<Pagination showSizeChanger showQuickJumper showTotal />` |
| `showTotal` 文案 | 无查询：`共 1845 条`；有查询：`匹配 37 条 / 共 1845 条` |
| 页大小选项 | `[100, 200, 500]`，后缀 `条/页` |
| 禁用态 | 第 1 页「‹」禁用、末页「›」禁用，**置灰但保留可见**（`frontend.md §1.2`） |
| 不渲染态 | ① 总行数 < 最小页大小（100）→ 整个分页栏不渲染；② `mainTab === 'comparison'` → 不渲染（比对视图有自己的分页器） |

---

## F-3 详情页分页

**服务的 AC**：AC-17 / AC-19 / AC-20
**原型**：`06-详情页.html`

- `QuotationDetail.tsx` + `ProductDetailViews.tsx`：同款分页栏 + 同款切片
- 🚨 **翻页不得发起任何写请求**。`ProductDetailViews` 吃的是整个 `quotation` 对象（`:33`），切片只在渲染层做
- 分页状态**不与编辑页共享**（两个页面各自独立）

## F-4 核价工作台分页

**服务的 AC**：AC-18 / AC-19 / AC-20
**原型**：`07-核价工作台.html`

- `CostingReviewPage.tsx`：同款分页栏 + 同款切片，页大小选项与报价侧一致
- 数据来自 `costingOrderService.getById(coid)`（另一个读入口），**该接口不改**

---

## F-5 料号模糊查询

**服务的 AC**：AC-9 / AC-10 / AC-11 / AC-12 / AC-13 / AC-14
**原型**：`02-...料号查询命中.html`（命中态）、`03-...空态与禁用态.html`（空态）

| 项 | 要求 |
|---|---|
| 🚨 作用域 | **对全量 `lineItems` 过滤，命中集合再分页**。🚫 不是在当前页里找 |
| 匹配字段 | `productPartNo`、`customerProductNo`、`customerPartName`（三者任一命中即算） |
| 匹配方式 | 大小写不敏感的子串包含；输入 trim 后为空 → 视为无查询 |
| 防抖 | 200 ms（1845 条纯内存过滤很快，防抖只为避免逐字符重渲染 100 张卡片） |
| 命中高亮 | 卡片头部与 Excel 行内，把命中的字符片段高亮（原型 `.mark`） |
| 空态 | 文案 **逐字**照 `03-...空态与禁用态.html`：<br>标题「未找到匹配的料号」<br>副文案「「XYZ999」在本报价单的 1845 个料号中无匹配。请换一个料号片段，或清空查询查看全部。」<br>按钮「清空查询」 |
| 🚨 零行防护 | `QuotationWizard.tsx:505-508` 的 QT-1554 守卫判据是**后端响应的 `basicItems.length === 0`**，与"查询命中 0 条"是两回事。必须确认查询空态**不会**误触发它导致保留上一页内容（AC-11） |

> 📌 **现网数据事实**（写在这里避免验收时误判）：`productPartNo` 1845/1845 有值、`customerProductNo` 1845/1845 有值（样例 `A1409`）、
> `customerPartName` **0/1845 全空**、`customerPartNo` **0/1845 全空**。`customerPartName` 纳入匹配逻辑但**不作验收判据**。

---

## F-6 Excel 视图切片与配对（🚨 本任务最高风险项）

**服务的 AC**：AC-5 / AC-6 / AC-7 / AC-8 / AC-14
**原型**：`04-编辑页-Excel视图.html`

| 项 | 要求 |
|---|---|
| 背景 | 后端 `GET /quotations/{id}/excel-view`（`ExcelViewService.java:133`）**仍全量返回 1845 行**，本次不改后端 |
| 🚨 必做 | Excel 视图**必须在前端自己切片**，否则「卡片 100 行 / Excel 1845 行」，直接违反需求 2（AC-7） |
| 🚨 配对 | `useBackendExcelRows.ts:117-121` 现用 `lineItems[i]` **按下标兜底配对**料号（AP-54 家族）。切片后下标基准必须与 `rawRows` 对齐，**推荐直接改为按 `_lineItemId` 键匹配**（AC-8） |
| 五条链路 | `frontendRows`（`LinkedExcelView.tsx:117`）/ `useExcelSnapshotRows`（`:96`）/ `useLinkedExcelRows`（`:78`）/ **`useBackendExcelRows`（`:100`，唯一走后端全量的）** / `ReadonlyExcelView`（`ProductDetailViews.tsx:222`）—— **逐条确认切片口径**，不许只改主路径 |
| 行号 | 用**全局序号**（第 2 页从 101 起），不用页内序号 |
| `refreshSignal` | `useBackendExcelRows.ts:69` 的 `refreshSignal` 由 `lineItems` 拼成。切片后**不得让翻页触发重新拉取**（AC-3 要求翻页零请求） |

---

## F-7 Step5 明细表分页 + 冲突定位跨页

**服务的 AC**：AC-15 / AC-16 / AC-21
**原型**：`05-编辑页-Step5明细表.html`

- `QuotationWizard.tsx:1919-1920` 的 `dataSource={lineItems} pagination={false}` → 改为分页（AC-16）
- 🚨 **总览区金额不动**：`:1881` 的求和继续在全量数组上跑（AC-21）
- **冲突定位跨页**（AC-15）：`QuotationStep2.tsx:3974` 的 `lineItems.find(...)` 在全量数组上**能找到对象**，但它可能不在当前渲染页 → 先由该对象在**过滤后集合**中的下标算出页码、`setPage()`，再走既有的 `cardRefs` 滚动定位。同一改法覆盖 `handleLocateConflict`（`:1448`）与 `reconcileConflicts`（`:1439`）两个入口

---

## F-8 性能与泄漏验证（不是"测试的事"，是前端的交付项）

**服务的 AC**：AC-19 / AC-20

- 交付时必须自带**实测数据**，口径与基线一致（headless Chrome + `--enable-precise-memory-info` + 强制 GC + 每页 100 + 同机）
- 五项指标：JS 堆 used / DOM 节点 / LayoutObjects / JS 事件监听器 / Step2 渲染耗时
- 🚨 **AC-20 泄漏护栏必须自测**：连翻 20 页回到第 1 页，四项指标不得单调上升。
  实测事件监听器 **13.2 个/行**，若卡片卸载未解绑，翻页会持续累积 —— **翻到第 18 页可能比不分页更糟**。
  不达标 → **停下来报告**，不许靠分页掩盖

---

## 不做（点名，防"顺手也做了"）

| 不做 | 原因 |
|---|---|
| 任何服务端 / 接口改动 | 用户 2026-08-26 裁决：服务端零改动 |
| 虚拟滚动 | 已裁决否决（D-9） |
| 比对视图分页 | 它已有（`ComparisonBoard.tsx:57/84/93/179`） |
| 修「打开编辑页自发 `PUT /draft`」 | 已实证的独立缺陷，另立 `repair-` |
| 总额口径下沉 / 增量合成 | 前端持全量，总额天然正确，整套机制不需要 |
