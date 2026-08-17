# 前端任务文档 — 材质库规范化与导入（task-0708）

> 技术总监下发 · 2026-07-09 · 优先级 P0
> 配套文档：`backtask.md`（后端）｜`api.md`（接口契约）
> **开工前必读**：`backtask.md` 的「零、澄清结论」8 条锁定项。前端一切改动以此为准。

---

## 零、涉及文件

| 文件 | 改动 |
|------|------|
| `src/pages/config/MaterialRecipeManagement.tsx` | 列表列/排序/搜索/导入按钮 |
| `src/pages/config/MaterialRecipeEditDrawer.tsx` | 化学式→材质名称、隐藏 name/配比、隐藏关联料号 tab、材质编号只读、默认 locked |
| `src/pages/config/MaterialImportDrawer.tsx` | **新建**：导入抽屉（上传 + 结果报告 + 模板下载） |
| `src/services/materialRecipeService.ts` | list 加 keyword、新增 import/downloadTemplate、类型补 createdAt/updatedAt |
| （挂载处）`src/pages/master-data/MasterDataHubPage.tsx` | 无需改（材质 Tab 已挂 MaterialRecipeManagement） |

> 交互规范：**统一抽屉（Drawer），禁止 Modal 表单**；列表**统一 SelectableTable + 顶部工具栏动作**（现有已符合，延续）。

---

## 一、任务拆分总览

| 任务 | 标题 | 规模 |
|------|------|------|
| F1 | service 层：list 加 keyword + import/template + 类型补时间字段 | S |
| F2 | 列表页改造：时间列 + 排序 + 移除绑定料号数列 + 搜索框 + 导入按钮 | M |
| F3 | 导入抽屉 `MaterialImportDrawer`（上传/进度/结果报告/模板下载） | M |
| F4 | 编辑抽屉改造：材质名称/隐藏字段/隐藏 tab/只读编号/默认 locked | M |
| F5 | 自检（tsc + Vite 200 + 关键页面 curl） | S |

---

## 二、F1 — service 层（`materialRecipeService.ts`）

### 2.1 类型补充
`MaterialRecipeLite` / `MaterialRecipeDetail` 增加：
```ts
createdAt?: string;
updatedAt?: string;
```

### 2.2 list 加 keyword
```ts
async list(opts?: { withCount?: boolean; keyword?: string }): Promise<MaterialRecipeLite[]> {
  const res = await api.get('/material-recipes', {
    params: {
      ...(opts?.withCount ? { withCount: true } : {}),
      ...(opts?.keyword ? { keyword: opts.keyword } : {}),
    },
  });
  return (res as unknown as MaterialRecipeLite[]) ?? [];
}
```

### 2.3 新增导入 + 模板下载
```ts
export interface MaterialImportReport {
  totalRows: number;
  materialsUpserted: number;
  elementRowsInserted: number;
  elementMasterUpserted: number;
  skippedRowCount: number;
  skipped: Array<{ sheet: string; row: number; reason: string; raw?: string }>;
  durationMs: number;
}

async importLibrary(file: File): Promise<MaterialImportReport> {
  const fd = new FormData();
  fd.append('file', file);
  const res = await api.post('/material-recipes/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return res as unknown as MaterialImportReport;
},

async downloadTemplate(): Promise<Blob> {
  const res = await api.get('/material-recipes/import/template', { responseType: 'blob' });
  return res as unknown as Blob;
},
```
> 注意 api 拦截器 unwrap 行为：blob 下载确认 `responseType:'blob'` 不被 unwrap 破坏；若拦截器对 blob 有特殊处理，按项目既有下载封装（参考其它 Excel 导出页）实现。

---

## 三、F2 — 列表页改造（`MaterialRecipeManagement.tsx`）

### 3.1 列改动
- **移除**「化学式」列标题歧义：`symbol` 列**标题改为「材质名称」**。
- 「代号」列（`code`）标题改为「**材质编号**」。
- **移除「绑定料号数」列**（`boundPartsCount`）——料号功能本期隐藏。
- **新增两列**：
  - 「创建时间」`createdAt`（格式化 `YYYY-MM-DD HH:mm`）
  - 「修改时间」`updatedAt`（同格式）
- 保留：类型（Tag）、状态（启用/停用 Tag）、排序。
- 列表 `dataSource` 顺序由后端定（启用优先→改时倒序→建时倒序），**前端不再本地 sort**。

### 3.2 搜索框
- 卡片工具栏区加 `Input.Search`（或受控 Input + 防抖），placeholder：「搜索 材质编号 / 材质名称 / 元素」。
- 输入变化（防抖 ~300ms）→ `refresh(keyword)` → `materialRecipeService.list({ keyword })`。
- 清空 → 拉全量。

### 3.3 顶部动作按钮
在 Card `extra` 区，`新建材质` 旁**新增**「**导入材质库**」按钮（`UploadOutlined`），点击开 `MaterialImportDrawer`。
> 移除或保留「未绑料号-智能建议」按钮？——**本期移除**（属料号功能）。

### 3.4 refresh 签名
```ts
const refresh = async (keyword?: string) => {
  setLoading(true);
  try { setList(await materialRecipeService.list({ keyword })); }
  finally { setLoading(false); }
};
```
> 去掉 `withCount:true`（不再显示绑定数）。

---

## 四、F3 — 导入抽屉（新建 `MaterialImportDrawer.tsx`）

Drawer（`placement="right"` width 720），内容：

1. **模板下载区**：一行说明 + 「下载导入模板」按钮 → `downloadTemplate()` → blob 存为 `material_library_template.xlsx`。文案提示："仅读取【材质编号】【材质对应元素】两个 sheet；含量填 0–1 小数，同材质相加=1。"
2. **上传区**：Ant `Upload`（`accept=".xlsx"`，`beforeUpload` 拦截手动触发、`maxCount=1`），选中后显示文件名 + 「开始导入」按钮。
3. **导入中**：按钮 loading + 禁用，防重复提交。
4. **结果报告区**（导入成功后展示）：
   - 概要行：`成功 {materialsUpserted} 种材质 / {elementRowsInserted} 条元素 / 跳过 {skippedRowCount} 行 / 耗时 {durationMs}ms`。
   - 跳过明细：`Table`（列：sheet、行号、原因、原值 raw），滚动区，让维护者据此回去修 Excel。
   - 底部「完成」按钮 → 关闭 Drawer 并回调父页 `refresh()` 刷新列表。
5. **错误处理**：400（缺 sheet/非法文件）→ `message.error(后端 message)`；网络/500 → `message.error('导入失败，请检查文件')`。**脏数据不是错误**（走 200 报告区）。

Props：
```ts
interface Props { open: boolean; onClose: () => void; onImported: () => void; }
```

---

## 五、F4 — 编辑抽屉改造（`MaterialRecipeEditDrawer.tsx`）

对照 `backtask.md` 锁定项逐条落实：

1. **「化学式」标签 → 「材质名称」**：`Form.Item name="symbol"` 的 `label` 从「化学式」改「材质名称」，placeholder 相应调整（如「Ag / AgC3」）。
2. **「代号」标签 → 「材质编号」**：`name="code"` 的 label 改「材质编号」；`disabled={!!editingDetail}` 保留（编辑只读，已实现）。
3. **隐藏 `name`（名称）和 `specLabel`（配比）两个 Form.Item**：从 detailTab 表单移除展示；提交时 `name`/`specLabel` 传 `null`（或不传）。
   > 不要删 DTO 字段，仅前端不收集。
4. **新建默认类型 = `locked`**：`else` 分支的 `form.setFieldsValue({ recipeType:'editable', … })` 改为 `recipeType:'locked'`；`setRecipeType('locked')`；初始元素 `isLocked:true`、无 min/max。
5. **隐藏「关联料号」tab**：`tabs` 数组中移除 `key:'parts'` 项（`MaterialRecipePartsTab` 组件文件保留不删，仅不挂载）。「变更日志」tab 可保留或一并隐藏（建议保留占位）。
6. 元素表格：类型仍可切换（保留 `onRecipeTypeChange` 三类型逻辑），编辑时用户可改为可调并填 min/max——**不动这套能力**（Q7）。
7. 保存校验沿用现有「默认含量之和=100」逻辑不变。

---

## 六、F5 — 自检（提交前必跑，缺则视为未完成）

```bash
cd cpq-frontend
npx tsc --noEmit -p tsconfig.json            # 0 错误
# 每个改动 tsx 走 Vite transform 200
for f in \
  src/pages/config/MaterialRecipeManagement.tsx \
  src/pages/config/MaterialRecipeEditDrawer.tsx \
  src/pages/config/MaterialImportDrawer.tsx \
  src/services/materialRecipeService.ts ; do
  curl -s --noproxy '*' -o /dev/null -w "$f %{http_code}\n" "http://localhost:5174/$f"
done
curl -s --noproxy '*' -o /dev/null -w "hub %{http_code}\n" http://localhost:5174/   # 主入口 200
```
- 全部 200；`tsc` 0 错误。
- 手动走查：主数据维护→材质 → 列表有创建/修改时间列、搜索可用、导入按钮在；编辑抽屉标签为「材质编号」「材质名称」、无 name/配比、无关联料号 tab、新建默认标准锁定。
- **本任务前端非协议级改动**（不碰 `QuotationStep2/useDriverExpansions/ReadonlyProductCard` 等），**无需**跑 quotation-flow E2E；如联调中动到渲染链再评估。

---

## 七、验收标准（对齐需求第 8 节）

- [ ] 主数据维护→材质：能上传 `材质库.xlsx` 导入，结果报告展示成功数/跳过明细
- [ ] 能新建 / 编辑 / 停用材质；编辑时材质编号只读
- [ ] 搜索框按 材质编号 / 材质名称 / 元素 过滤生效
- [ ] 列表有创建时间、修改时间列，顺序=启用优先→改时倒序→建时倒序
- [ ] 编辑抽屉：化学式已改「材质名称」；关联料号 tab 已隐藏；无 name/配比字段
- [ ] 可下载干净导入模板
- [ ] 一行「已自检」声明（tsc 0 错误 + 各 tsx Vite 200）
