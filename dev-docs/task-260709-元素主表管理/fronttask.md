# 前端任务文档 — 元素主表管理（task-0709 / BL-0040）

> 技术总监下发 · 2026-07-09 · 优先级 P2
> 配套：`backtask.md`（后端）｜`api.md`（接口契约）
> **开工前必读**：`backtask.md`「零、澄清结论」6 条锁定项。

---

## 零、涉及文件

| 文件 | 改动 |
|------|------|
| `src/services/elementService.ts` | **新建**：元素 CRUD service + 类型 |
| `src/pages/config/ElementManagement.tsx` | **新建**：元素管理页（SelectableTable + 工具栏 + 搜索）|
| `src/pages/config/ElementEditDrawer.tsx` | **新建**：新建/编辑抽屉（符号锁 + 唯一校验提示）|
| `src/pages/master-data/MasterDataHubPage.tsx` | 加「元素」页签，挂 `ElementManagement` |

> 交互规范：**统一 Drawer（禁 Modal 表单）** + **SelectableTable + 顶部工具栏动作**（行内不放按钮）。参照现有材质页 `MaterialRecipeManagement`/`MaterialRecipeEditDrawer` 的写法。

---

## 一、任务拆分

| 任务 | 标题 | 规模 |
|------|------|------|
| F1 | `elementService.ts` 类型 + CRUD | S |
| F2 | `ElementManagement` 列表页（列/搜索/工具栏动作）| M |
| F3 | `ElementEditDrawer` 新建/编辑抽屉（符号锁）| M |
| F4 | `MasterDataHubPage` 挂「元素」页签 | XS |
| F5 | 自检 | S |

---

## 二、F1 — `elementService.ts`

```ts
import api from './api';

export interface ElementItem {
  id: string;
  elementNo: string;        // 业务主键(不可改)
  elementCode: string;      // 符号(被引用即锁)
  elementName: string;      // 中文
  status: 'ACTIVE' | 'INACTIVE';
  referencedCount: number;  // 被引用材质数
  codeLocked: boolean;      // true → 禁用符号输入
  createdAt?: string;
  updatedAt?: string;
}
export interface ElementUpsertRequest {
  elementNo: string;
  elementCode: string;
  elementName: string;
}

export const elementService = {
  async list(keyword?: string): Promise<ElementItem[]> {
    const res = await api.get('/elements', { params: keyword ? { keyword } : undefined });
    return (res as unknown as ElementItem[]) ?? [];
  },
  async create(req: ElementUpsertRequest): Promise<ElementItem> {
    return (await api.post('/elements', req)) as unknown as ElementItem;
  },
  async update(elementNo: string, req: ElementUpsertRequest): Promise<ElementItem> {
    return (await api.put(`/elements/${encodeURIComponent(elementNo)}`, req)) as unknown as ElementItem;
  },
  async deleteSoft(elementNo: string): Promise<void> {
    await api.delete(`/elements/${encodeURIComponent(elementNo)}`);
  },
};
```

---

## 三、F2 — `ElementManagement`（列表页）

- `Card` 标题「元素管理」，`extra` 放**「新建元素」**按钮（`PlusOutlined`）。
- 卡片工具栏区放 `Input.Search`：placeholder「搜索 元素编号 / 符号 / 中文名」；防抖 ~300ms → `refresh(keyword)`。
- **列**：
  | 列 | dataIndex | 说明 |
  |----|-----------|------|
  | 元素编号 | `elementNo` | 主入口链接，点击进编辑 |
  | 符号 | `elementCode` | 被引用的加个锁标记/tooltip（可选）|
  | 中文名 | `elementName` | |
  | 被引用材质数 | `referencedCount` | `<Tag color={n>0?'blue':'default'}>{n}</Tag>` |
  | 状态 | `status` | 启用(绿)/停用(默认) Tag |
  | 创建时间 | `createdAt` | `YYYY-MM-DD HH:mm` |
  | 修改时间 | `updatedAt` | 同上 |
- 排序由后端定，**前端不本地 sort**。
- **`SelectableTable` 工具栏动作**（行内不放按钮）：
  - **编辑**：`enabledWhen: rows.length===1`，`onClick → openEdit(rows[0])`
  - **停用**：`danger`，`enabledWhen`：0 选禁用；含已停用项 → 提示「仅启用状态可停用」；`needsConfirm` Modal 列出所选项二次确认；`runBatch` 聚合失败明细
- 参照 `MaterialRecipeManagement.tsx` 结构落地。

---

## 四、F3 — `ElementEditDrawer`（新建/编辑）

Drawer（`placement="right"` width 480/560），`Form`：

| 字段 | 新建 | 编辑 |
|------|------|------|
| 元素编号 `elementNo` | 必填输入 | **只读**（不可改主键）|
| 符号 `elementCode` | 必填输入 | **`codeLocked` 为 true 时禁用 + tooltip「已被 {referencedCount} 个材质引用，符号不可修改」**；false 时可改 |
| 中文名 `elementName` | 必填输入 | 可改 |
| 状态 `status` | 默认 ACTIVE | 启用/停用 Select |

- 提交：新建 `create` / 编辑 `update(elementNo, req)`。
- **错误提示**：捕获后端 409 → `message.error(后端 message)`（如「符号已被 128 个材质引用，不可修改」「元素编号已存在」「符号已存在」）。
- 保存成功 → 关抽屉 + 回调父页 `refresh()`。

Props：`{ open, editing: ElementItem | null, onClose, onSaved }`。

---

## 五、F4 — 挂「元素」页签

`MasterDataHubPage.tsx` 的 Tab 列表里，在「材质」页签旁**新增**：
```tsx
{ key: 'element', label: '元素', children: <ElementManagement /> },
```
（参照现有 `{ key: 'material', label: '材质', children: <MaterialRecipeManagement /> }`）

---

## 六、F5 — 自检

```bash
cd cpq-frontend
npx tsc --noEmit -p tsconfig.json          # 0 错误
for f in \
  src/services/elementService.ts \
  src/pages/config/ElementManagement.tsx \
  src/pages/config/ElementEditDrawer.tsx \
  src/pages/master-data/MasterDataHubPage.tsx ; do
  curl -s --noproxy '*' -o /dev/null -w "$f %{http_code}\n" "http://localhost:5174/$f"
done
```
- tsc 0 错误；各 tsx Vite 200。
- 手动走查：主数据维护→元素 → 列表有编号/符号/中文/被引用数/状态/时间；搜索可用；新建可建；编辑时**被引用元素符号锁定**、中文可改；停用走二次确认。
- 本任务**非协议级前端改动**（不碰 QuotationStep2/渲染链），无需 E2E。

---

## 七、验收标准

- [ ] 主数据维护出现「元素」页签，列表列齐全、排序=启用优先→改时倒序→建时倒序
- [ ] 搜索 元素编号 / 符号 / 中文名 均生效
- [ ] 新建元素（编号+符号唯一校验，撞号提示）
- [ ] 编辑：元素编号只读；**被引用元素符号禁用 + tooltip**；未引用可改符号；中文名可改
- [ ] 停用（软删）走 SelectableTable 二次确认
- [ ] 一行「已自检」声明（tsc 0 错 + 各 tsx Vite 200）
