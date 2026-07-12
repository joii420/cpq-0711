# 前端任务文档 · 主数据维护-核价基础数据维护（task-0712）

> 配套 `需求说明.md`（C1–C13）、`api.md`。执行前**必读** `docs/列表操作规范.md`、`docs/UI 交互规范`（CLAUDE.md）、`docs/RECORD.md`。
> 角色：`cpq-frontend`。技术栈：React + Ant Design + Vite（端口 5174）。
> **UI 规范硬约束**：抽屉（Drawer）不用 Modal；列表走 `SelectableTable`（本页以"点行进抽屉"为主入口，符合规范"纯查看/Master-Detail"例外）。

---

## 0. 现状与复用点

| 复用件 | 位置 | 用途 |
|--------|------|------|
| 宿主页 | `src/pages/master-data/MasterDataHubPage.tsx` | 现有 4 Tab（工序/料号/材质/数据模板），**加第 5 Tab「料号核价」** |
| 菜单角色 | `src/layouts/MainLayout.tsx`（`/master-data-hub` 项） | 现 `['SALES_MANAGER','SYSTEM_ADMIN']`，**补 `PRICING_MANAGER`**（C10） |
| 版本 UI 参照 | `VersionHistoryPage.tsx` / `VersionCompareDrawer.tsx` / `RowDetailDrawer.tsx` / `PricingBasicDataImportDrawer.tsx` | 版本切换、抽屉、行详情样式参照 |
| 表格组件 | `src/components/SelectableTable.tsx` | 列表页 |
| 权限 | 现有 `useAuth`/role guard（参照他页） | 编辑权判定 |

---

## F1 · 宿主接入 + 菜单角色

1. `MasterDataHubPage.tsx` 增加第 5 个 Tab：`key="part-costing"`、`label="料号核价"` → 渲染 `<PartCostingTab/>`。
2. `MainLayout.tsx` 把 `/master-data-hub` 菜单项 `roles` 改为 `['SALES_MANAGER','PRICING_MANAGER','SYSTEM_ADMIN']`。
3. 目录建议：`src/pages/master-data/part-costing/`（`PartCostingTab.tsx` / `PartCostingDrawer.tsx` / `EditableSheetTable.tsx` / `api.ts` / `types.ts`）。

---

## F2 · `PartCostingTab`（料号列表，C3/C4）

- 顶部搜索框（按料号/品名，防抖）→ `GET /parts?keyword=&page=&size=`。
- `SelectableTable` 列：品名 `materialName`、料号 `materialNo`、规格 `specification`、尺寸 `dimension`、**已配置** `configuredCount/totalSheets`（如 `12/16`，可用 Tag/Progress）、最近更新 `lastUpdatedAt`。
- **主入口**：点行 → 打开 `PartCostingDrawer`（传 `materialNo`）。行内不放动作按钮（符合列表规范）。
- 分页；空态友好提示"暂无有核价数据的料号"。

---

## F3 · `PartCostingDrawer`（抽屉，16 tab + 版本切换，C2/C7/C9）

- Ant `Drawer` `placement="right"` 宽 `1200`；标题区显示料号 + 品名/规格/尺寸。
- 打开时：`GET /sheets`（元数据，可全局缓存一次）+ `GET /parts/{materialNo}/overview`。
- **固定 16 个 tab**（顺序按 `sheets[].order`；tab 标题用 `tabName`）：
  - tab 徽标：有数据显示当前版本号（`overview.sheets[].currentVersion`），无数据显示"未配置"。
- **每个 tab 内容**（切到才懒加载该组 rows）：
  1. 顶部版本切换 `Select`：`GET /versions`，选项显示 `版本号 · 来源 · 操作人 · 时间`（C11）；默认选当前版。
  2. 表体 `EditableSheetTable`（见 F4），传该 sheet 的列元数据 + rows + `editable`。
  3. `editable = isCurrent && 有编辑权`（C7：历史版恒只读；无编辑权角色恒只读）。
  4. 底部：`保存` 按钮（仅 editable 显示）；`新增行`（editable）。
  5. 空 tab（`hasData=false`）：渲染空 `EditableSheetTable`，允许新增行从零录入（C9），保存走 CREATED。

---

## F4 · `EditableSheetTable`（元数据驱动通用表格，核心）

入参：`columns`（api.md §2 的列定义）、`rows`、`editable`、`onChange`、`sheetKey`。

**按 `role` 渲染列**：
- `AXIS`：不渲染为列（或只读展示），**不可编辑**。
- `NAME`：只读文本，随对应编码列联动刷新（编码变→重取名称；可前端用 lookup 结果缓存或保存后由后端回带）。
- `SUBDIM` / `VALUE`：editable 时按 `dropdown.kind` 渲染编辑控件：
  - `MASTER`（工序/元素/来料料号）：Ant `Select` 远程搜索 → `GET /lookup/{master}?keyword=`；`labelInValue` 选中同时得 code+name，回填对应 NAME 列。
  - `ENUM`（币种/单位/计算类型/是否有效/P22电镀费类型）：`Select` 固定 `options`（含 CHECK 约束值），允许 `未知可输入`（`showSearch` + 自定义）。
  - `FREE`（要素名称/模具编号/物料BOM组成件，C13）：普通 `Input`/`InputNumber`。
  - 数值列（DECIMAL/NUMBER）：`InputNumber`，保留后端精度（字符串传值，避免浮点误差 — 参照核价精度既往教训）。
- editable=false：全列纯文本展示。

**行操作**（editable）：`新增行`（按 columns 生成空行）、`删除行`（至少留一行，删到 1 行禁用删除并提示）。
**AXIS 锁定**：料号/price_type 由抽屉上下文固定，表格内不出现可编辑轴列。

---

## F5 · 保存交互（C5/C6）

- `保存`：收集当前 tab 全部行（仅需 AXIS 外列）→ `PUT /parts/{materialNo}/sheets/{sheetKey}/rows`，body 带 `expectedCurrentVersion`（= 进入编辑时的当前版本号）+ `rows`。
- 结果处理：
  - `UNCHANGED`：`message.info("内容未变化，未产生新版本")`。
  - `UPGRADED` / `CREATED`：`message.success("已保存，版本 " + version)` → 刷新该 tab 的 versions + overview 徽标 + rows（切到新当前版）。
  - `409`（乐观锁）：`Modal` 提示"该数据已被他人升级，请刷新后重试"，提供刷新按钮重载。
  - `422`（整组清空）：提示"至少保留一行"。
  - `400`（校验）：提示后端返回的具体列错误。
- 保存中禁用按钮 + loading。

---

## F6 · 权限门控

- 进入抽屉前/后按当前用户角色计算 `canEdit = role ∈ {PRICING_MANAGER, SYSTEM_ADMIN}`。
- `canEdit=false`：所有 tab 只读（不显示保存/新增/删除，编码列以文本展示）。
- 列表/抽屉入口按菜单角色已限制（F1）。

---

## F7 · 自检清单（修改后强制自检，CLAUDE.md）

```
1) cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   → 0 错误
2) 对每个改动 .tsx：
   curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/master-data/part-costing/<file>.tsx  → 200
3) curl 主入口 http://localhost:5174/ → 200
4) 手动走查（带 PRICING_MANAGER / 只读角色 各一遍）：
   - 主数据维护出现「料号核价」tab，PRICING_MANAGER 可见
   - 列表搜索 + N/16 + 点行开抽屉
   - 16 tab 固定；有数据 tab 显当前版数据 + 名称列
   - 版本切换（历史只读）；编辑改值/增行/删行 → 保存 → 版本 +1；内容未变 → info
   - 空 tab 从零新建 → CREATED 2000
   - 编码列主表下拉带名称；只读角色全只读
```

> 本任务属**主数据维护**页面，不触及报价/核价渲染协议（AP-37/44 等），**不强制** `quotation-flow` E2E；但编辑表格建议补一个轻量 Playwright 冒烟（列表→抽屉→改值保存→版本+1）作为交付证据。

**完成宣告须含一行"已自检"声明**（TS 0 错、改动 tsx Vite 200、主入口 200、两角色走查通过）。

---

## F8 · 与后端接口对齐

严格按 `api.md` 的 7 组接口；`types.ts` 定义与响应体一一对应的 TS 类型（`PartRow` / `SheetMeta` / `ColumnDef` / `PartOverview` / `VersionInfo` / `SaveResult` 等）。列元数据的 `role`/`dropdown` 驱动渲染，**不在前端硬编码 16 组列**（保持与后端 registry 单一真源）。
