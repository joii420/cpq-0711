# 前端任务分解 · 报价与核价建表与导入方案新规范

> 前端**只按本文件做**。接口契约见 [`api.md`](./api.md)，视觉基准见 [`原型图/`](./原型图/)，AC 原文见 [`需求文档.md`](./需求文档.md) —— **本文件只标 AC 编号，不复制原文**。
> 必读：`docs/rules/frontend.md`（§1.1 抽屉替代弹窗 · §1.2 工具栏动作 + 禁用但可见 · §1.3 原型 1:1 还原 · §2 强制自检）、`docs/列表操作规范.md`
> 🚨 **闸门 A0 裁决 D-13：前端复用现有组件。复用方式 = 抽公共件 + 新旧各自引用，🚫 不得改变现有「料号核价」页签的任何行为（AC-42）。**

---

## F-1 · 抽公共件（本任务的地基，先做）

| 服务的 AC | AC-24, AC-42 |
|---|---|

现有三件套写死了 `/pricing-basic-data` 基址与固定 16 tab，需参数化：

| 文件 | 怎么改 |
|---|---|
| `part-costing/api.ts` | 抽成 `createSheetApi(basePath)` 工厂；现有 `PartCostingTab` 传 `'/pricing-basic-data'`，新页签传 `'/dataset/cost-basic'` / `'/dataset/cost-detail'` |
| `part-costing/PartCostingTab.tsx` | 抽出通用 `<SheetPartListTab api={} axisLabel={} />`；现有页签改为薄封装（**渲染结果逐屏不变**） |
| `part-costing/PartCostingDrawer.tsx` | 抽出通用 `<SheetPartDrawer api={} />`；tab 数量由 `GET sheets` 决定，不再写死 16 |
| `part-costing/EditableSheetTable.tsx` | **不改逻辑**，只补一个可选 prop：列头按 `compared` 打 🔗 角标 |

- 🚨 **验收方式是「改动前后逐屏比对」**：抽公共件后先跑 AC-42，确认现有页签零变化，**再**开始 F-2。
- ⚠️ 若发现某处必须改动现有页签的行为才能复用，**停下报主线**，不要自行取舍。

## F-2 · 「基础核价」页签

| 服务的 AC | AC-24, AC-25, AC-26 |
|---|---|

- 挂进 `MasterDataHubPage.tsx`，位置在现有 4 个页签**之后**，key `cost-basic`，label `基础核价`。
- 列表按 `原型图/核价数据-列表.html`：搜索框 + 配置状态过滤 + 服务端分页/排序 + 点行开抽屉。
- 列表是**裸 `<Table>` + 可点击行**（Master-Detail 导航，属 `docs/列表操作规范.md` 例外白名单），工具栏要自己套 `TOOLBAR_ROW_STYLE`（见 `listConventions.ts`）。
- 轴列标题为 `生产料号`。

## F-3 · 「详细核价」页签

| 服务的 AC | AC-24, AC-26 |
|---|---|

- 同 F-2，key `cost-detail`，label `详细核价`，紧随「基础核价」。
- **唯一差异是 `basePath`** —— tab 数（17）由 `GET sheets` 返回决定，不写死。

## F-4 · 抽屉：编辑 · 保存 · 版本切换

| 服务的 AC | AC-27, AC-28, AC-29, AC-30 |
|---|---|

- 按 `原型图/核价数据-抽屉.html`：左侧 tab（`tabPosition="left"`）+ 顶部版本下拉 + `EditableSheetTable` + 保存/新增行。
- 保存调 `PUT …/rows`，**必须带 `baseVersion`**；按 `result` 三态给不同 toast（文案见 `api.md §7` 表格，AC-27/28）。
- 版本下拉选历史版本 → 表格切到该版本数据，**保存/新增行禁用**，hover 提示见 AC-29。
  🚫 按 `frontend.md §1.2`：**禁用但可见 + 说明原因**，不许隐藏按钮。
- 新增行用现有 `newBlankRow`；删除行用行尾图标。行身份走现有 `__rid`（防受控输入错位，`AP-54` 教训）。
- 🚫 前端**不回传** `role=NAME` 的列与 `row_fingerprint`。

## F-5 · 空态 · 禁用态 · 权限

| 服务的 AC | AC-31, AC-32 |
|---|---|

- 空态按 `原型图/核价数据-抽屉-空态.html`：`暂无数据，可点「新增行」录入或从 Excel 导入`。
  🚫 **不许**出现红色遮罩、白屏、`加载中…` 永久占位（`AP-31` / `AP-38` 族）。
- 权限：非 `PRICING_MANAGER` / `SYSTEM_ADMIN` 时，保存 / 新增行 / 导入按钮**禁用但可见**，hover 提示 `需要核价管理员权限`。
- 最长文案态按 `原型图/核价数据-抽屉-极值.html`（128 字要素名称 + 12 位小数）不撑破布局。

## F-6 · 核价导入抽屉

| 服务的 AC | AC-11, AC-33, AC-34 |
|---|---|

- 两个新页签**各自**页签内一个「导入核价数据」按钮（按用户第 6 条：两个页签内各含导入按钮），点开抽屉。
- 抽屉按 `原型图/数据导入-抽屉.html`：文件选择 → 上传 → 结果区。
- 成功：展示 `api.md §1` 的 summary 表（sheet / 新建 / 升版 / 无变化），**抽屉自动关闭 + 列表自动刷新**（AC-33）。
- 失败：按 `原型图/数据导入-校验失败.html` 渲染错误表（sheet / 行号 / 列名 / 原因），可滚动，**逐条列全不截断**（AC-10）。
- 🚫 按 `frontend.md §1.1`：用 Drawer，不用 Modal。

## F-7 · 报价单管理「导入报价数据」按钮

| 服务的 AC | AC-35, AC-36, AC-37, AC-38 |
|---|---|

- `QuotationList.tsx` 工具栏新增按钮，位置在「从基础数据导入」**之后**、「新建报价单」**之前**（顺序见 `原型图/报价单管理-工具栏.html`）。
- 🚫 **现有「从基础数据导入」按钮及其 `QuoteBasicDataImportV6Drawer` 一个字节都不改**（AC-35/AC-43）。
- 新按钮复用 F-6 的导入抽屉组件，`dataset='quote'`。
- 🚫 **不新增任何报价数据的维护页签**（AC-38，本期明确不做）。

## F-8 · 保存冲突提示

| 服务的 AC | AC-41 |
|---|---|

- `PUT …/rows` 返回 409 时，弹出提示 `数据已被他人更新至 v{n}，请刷新后重试` + 一个「刷新」动作按钮。
- 点刷新 → 重拉 `versions` + `rows`，**丢弃本地未保存改动前必须二次确认**。

## F-9 · 校验错误的统一呈现

| 服务的 AC | AC-6, AC-7, AC-8, AC-9, AC-10 |
|---|---|

- 导入（F-6）与保存（F-4）复用同一个 `<ValidationErrorTable errors={} />`，字段 `sheet / row / column / reason`。
- 错误条数 > 20 时表格内部滚动，**不做截断、不做「仅显示前 N 条」**。

## F-10 · 「电镀方案」页签（S-9，2026-09-03 追加）

| 服务的 AC | AC-48, AC-49, AC-50, AC-51 |
|---|---|

- 挂进 `MasterDataHubPage.tsx`，作为**第 7 个**页签，位置在「详细核价」**之后**，key `plating-scheme`，label `电镀方案`。
- 视觉基准 `原型图/电镀方案-页签.html`。
- 页内一个数据集下拉（`报价` / `详细核价`），默认 `报价`；切换即重新拉 `GET /dataset/{dataset}/plating-schemes`。
- 🚨 **列定义完全由接口的 `columns` 驱动，前端不得写死任何一列**（两个数据集列数不同：10 vs 8，AC-49）。
- 🚫 **只读**：页面**不得**出现「新增」「编辑」「删除」「保存」按钮；单元格不可进入编辑态（AC-51）。
- 页顶固定一行说明：`电镀方案为导入维护，如需修改请通过「导入报价数据」/「导入核价数据」重新导入`。
- 空态文案：`暂无电镀方案数据，请先导入`。

## F-11 · 前导零编码显示修复（D-25，2026-09-03 追加）

| 服务的 AC | AC-53, AC-54, AC-55 |
|---|---|

**问题**：`part-costing/EditableSheetTable.tsx` 的 `displayText(v)` **按值的形状猜类型**
（`if (typeof v === 'string' && isDecimalString(v)) return normalizeDecimalString(v)`），
**完全不读 `ColumnDef.type`**。后果：`type=STRING` 的列，值 `00168` 被渲染成 `168`。

**实测影响面**：`material_recipe` 260 个 code 中 **258 个带前导零**，且这些值已落在
`ds_cost_basic_element_bom` / `ds_cost_detail_element_bom` 的 `material_part_no` 里
（实测值：`00006, 00168, 991`）⇒ 新页签的「物料与元素BOM」tab 必然显示错。

**改法**：`displayText` 增加列类型入参，**仅当 `col.type` 为 `DECIMAL` / `NUMBER` 时**才走
`normalizeDecimalString`；`STRING` 及其余类型一律 `String(v)` 原样透传。

🚨 **三条纪律**：
1. **不要改 `precision.ts`** —— `isDecimalString` / `normalizeDecimalString` 本身没错，错的是调用处的判据。
2. **数值列的去尾零行为必须原样保留**（AC-54 专门验这一条，防止修过头）。
3. 🚨 **改完必须重跑 AC-42 的 A/B 比对**（AC-55）—— 你上次拿到的「抽屉截图 MD5 逐字节相同」这次**会变**，
   因为材质料号列的显示从 `168` 变成 `00168`。**这是预期的改善**，请在回报里逐项说明变化点，
   证明除此之外其余渲染逐项不变。

📌 **P-2（只读态数值列右对齐）/ P-3（12 位精度例外溢出到只读态）本期不做**，已进 BACKLOG。

---

## 前端强制自检（`frontend.md §2`，交付前逐条跑，缺一不算完成）

- [ ] `npx tsc --noEmit` **0 错误**
- [ ] 改动涉及的每个页面在 dev server 上 HTTP 200，浏览器控制台**无红色报错**
- [ ] 逐屏比对实现与 `原型图/*.html`，偏差逐条列出（只允许「组件库能力所限的等价实现」这一类）
- [ ] **AC-42 专项**：现有「料号核价」页签逐屏比对改动前后，确认零变化
- [ ] 空态 / 禁用态 / 极值态三种状态实机各走一遍

## ⚠️ worktree 前端自检的两个坑（`RECORD.md` 教训）

- 共享的 5174 dev server 服务的是**主仓**，不是 worktree ⇒ 必须软链 `node_modules` + 另起临时端口的 vite，或 `grep` 文件内容确证改动生效。
- 本机 shell 常设 `http_proxy`，`curl` 探本机服务一律加 `--noproxy '*'`。
