# childtask-1 前端任务（fronttask）

> 前端工程师进场基准。开工前先读同目录 `需求说明.md` + `api.md`。
> 守则：抽屉(Drawer)代替弹窗；列表走 SelectableTable 工具栏动作；遵 CLAUDE.md「修改后强制自检」。
> 探代码优先用 codegraph。

---

## 任务总览

| 编号 | 任务 | 类型 | 规模 |
|---|------|------|------|
| **F1** | 工序主数据导入 Drawer + 工序管理页入口 | 新增 | M |
| **F2** | 核价维护页「材质名」列渲染 + 未绑定/未维护灰字 | 改动 | S |

---

## F1 · 工序主数据导入 Drawer + 入口

**目标**：「主数据维护→工序管理」Tab 提供 xlsx 批量导入入口（上传 + 模板下载 + 导入报告），把工序码+名灌进 `process_master`。

样板（**照抄结构**）：`pages/config/MaterialImportDrawer.tsx` + `services/materialRecipeService.ts` 的 `importLibrary`/`downloadTemplate`。

### F1.1 service 加两个方法
文件：`cpq-frontend/src/services/v6MasterDataService.ts`（已有 `listProcesses/createProcess/...`，同文件加）。
```ts
// ── 工序主数据导入 / 模板下载 (childtask-1) ──
export interface ProcessMasterImportReport {
  totalRows: number;
  insertedCount: number;
  updatedCount: number;
  skippedRowCount: number;
  skipped: { row: number; reason: string; raw?: string }[];
  durationMs: number;
}
/** POST /api/cpq/v6/process-master/import — 上传 xlsx 导入工序主数据 */
export async function importProcesses(file: File): Promise<ProcessMasterImportReport> {
  const fd = new FormData();
  fd.append('file', file);
  return api.post('/v6/process-master/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }); // 拦截器已 return response.data；若后端包 ApiResponse 则取 .data，按本仓约定对齐
}
/** GET /api/cpq/v6/process-master/import/template — 下载导入模板(xlsx blob) */
export async function downloadProcessTemplate(): Promise<Blob> {
  return api.get('/v6/process-master/import/template', { responseType: 'blob' });
}
```
> ⚠️ 对齐本仓 `api` 响应拦截器约定：`materialRecipeService.ts:148-165` 已示范 `importLibrary`/`downloadTemplate` 的返回解包与 blob 处理，**照它写**（含 `responseType:'blob'` 时返回值本身即 Blob）。

### F1.2 新增 `ProcessMasterImportDrawer.tsx`
路径：`cpq-frontend/src/pages/master-data/ProcessMasterImportDrawer.tsx`
照抄 `MaterialImportDrawer.tsx`，替换 service 调用与文案：
- Props：`{ open: boolean; onClose: () => void; onImported?: () => void }`。
- 内容：`Upload.Dragger`（选 .xlsx，`beforeUpload` 拦截不自动上传）+「下载模板」按钮（调 `downloadProcessTemplate` → blob → `a.download='process_master_template.xlsx'`）+「开始导入」按钮（调 `importProcesses` → 展示报告）。
- 导入报告展示：总行 / 新增 / 更新 / 跳过数 + 跳过明细 `Table`（行号/原因/原始值）+ 耗时；有跳过用 `Alert warning`。
- 导入成功后 `onImported?.()` 让工序 Tab 刷新列表。

### F1.3 工序管理 Tab 挂入口
文件：`cpq-frontend/src/pages/master-data/V6ProcessCrudTab.tsx`
- 顶部工具栏（现有 `Space` 区，与「新建工序」按钮同排）加 `<Button icon={<ImportOutlined/>}>导入工序</Button>` → 打开 `ProcessMasterImportDrawer`。
- `onImported` 回调里触发现有列表刷新（复用 `loadData`/`reload`）。
- 保持 SelectableTable 工具栏动作范式，别在行内加动作。

---

## F2 · 核价维护页「材质名」列渲染 + 灰字提示

**目标**：核价基础数据维护页 `ELEMENT_BOM`（物料与元素BOM）sheet，在「材质料号」旁显示「材质名」（后端 readRows 新返 `material_recipe_name` 字段）；未绑定/未维护显示灰字。

### 定位
- 前端维护页在 `cpq-frontend/src/pages/master-data/part-costing/`（`api.ts` + `types.ts` + 表格渲染组件）。用 codegraph 定位渲染 sheet rows 的表格组件（消费 `/parts/{materialNo}/sheets/{sheetKey}/rows` 的地方）。

### F2.1 类型 + 列渲染
- `part-costing/types.ts`：行类型补 `material_recipe_name?: string | null`（后端 readRows 新增列，随 sheet 的 columns 元数据自动带出——**确认**后端把该 nameCol 放进 columns 定义，前端按 columns 动态渲染则可能无需硬编码；若前端是动态列渲染，F2 可能仅需下一条灰字兜底）。
- 若为动态列渲染（按后端 `columns` 元数据出列）：材质名列会**自动出现**，F2 主要工作转为 F2.2 的灰字兜底 + 校验它确实渲染。
- 若为静态列：在 ELEMENT_BOM 的列配置里，`material_part_no` 后插入「材质名」只读列，取 `row.material_recipe_name`。

### F2.2 未绑定/未维护灰字
- 名称列值为 `null/空` 时，渲染灰字占位而非空白：
  - 材质名列：`<Text type="secondary">未绑定</Text>`（未绑 material_recipe_id）。
  - 工序名/元素名/料号名列：值为空时 `<Text type="secondary">未维护</Text>`。
- 抽一个小工具 `renderNameOrHint(value, hint)` 复用四列，避免散落。
- **不阻断**：这是纯展示，保存/浏览照常。

---

## 强制自检（完成前必跑，写进交付说明）

1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**。
2. 对每个改动 `.tsx` 跑：
   ```bash
   curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/master-data/ProcessMasterImportDrawer.tsx   # 期望 200
   ```
   （V6ProcessCrudTab / part-costing 表格组件同理）
3. 主入口 `curl --noproxy '*' http://localhost:5174/` → 200。
4. 真实走查：打开「主数据维护→工序」→ 导入 Drawer 能开、模板能下、导入返报告；打开核价维护页某销售料号的「物料与元素BOM」→ 材质名列出现，未绑定显灰字。
5. 交付说明附一行「已自检」声明（tsc 0 错 + 各改动 tsx Vite 200 + 走查结果）。

## 影响文件清单（预估）

- 新增：`pages/master-data/ProcessMasterImportDrawer.tsx`
- 改：`services/v6MasterDataService.ts`（+import/+template + Report 类型）
- 改：`pages/master-data/V6ProcessCrudTab.tsx`（工具栏加导入入口）
- 改：`pages/master-data/part-costing/`（材质名列渲染 + 灰字兜底；types.ts 补字段）

## 依赖后端（阻塞点）
- F1 依赖后端 B1 的 `/v6/process-master/import` + `/import/template`（见 `api.md`）。
- F2 依赖后端 B2 的 readRows 返回 `material_recipe_name`（见 `api.md`）。
- 后端未就绪前，前端可先按 `api.md` 契约 mock 联调。
