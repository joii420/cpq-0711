# 前端任务 — task-0812 材质元素改下拉选择

> 输入：`需求文档.md`（FR/AC 唯一标准）+ `api.md`（接口契约）+ `原型-元素列.html`（版式）
> 分支：`feat/task-260812-材质元素改下拉选择`
> **红线**：不得改后端契约（要改先回 `api.md` 并知会主线）；不得 `git add -A`，只提交本任务改动的文件。

## 1. 页面 / 组件清单

| 文件 | 改动性质 | 说明 |
|---|---|---|
| `cpq-frontend/src/pages/config/MaterialRecipeEditDrawer.tsx` | **主改动（唯一）** | 新建材质 + 编辑材质共用组件，改「元素组成」表的元素列 |
| `cpq-frontend/src/services/elementService.ts` | **只读复用，不改** | 用 `elementService.list()` 拉字典；类型 `ElementItem` 直接用 |
| `cpq-frontend/src/pages/config/MaterialRecipeManagement.tsx` | 不改 | 父页，仅负责打开抽屉 |
| `cpq-frontend/src/pages/master-data/MasterDataHubPage.tsx` | 不改 | 「材质 / 元素」两个 Tab 的宿主页；本期不做跨 Tab 跳转（D6） |

**入口路径**：主数据维护（`MasterDataHubPage`）→「材质」Tab →「新建材质」/ 点行进入「编辑材质」→ 抽屉「材质详情」→「元素组成」表。

## 2. 改动点详解

### 2.1 状态与数据加载

```ts
// 新增状态
const [elementDict, setElementDict] = useState<ElementItem[]>([]);
const [dictLoading, setDictLoading] = useState(false);
const [dictError, setDictError] = useState(false);

// 抽屉打开时加载一次（AC-10：恰好 1 次请求）
useEffect(() => {
  if (!open) return;
  setDictLoading(true); setDictError(false);
  elementService.list()
    .then(setElementDict)
    .catch(() => { setDictError(true); message.error('元素字典加载失败，请刷新重试'); })
    .finally(() => setDictLoading(false));
}, [open]);
```

- `destroyOnClose` 已开启，抽屉关闭会卸载内容；**不要**把加载放在模块顶层或 `[]` 依赖里。
- 派生索引（`useMemo`）：`byNo: Map<elementNo, ElementItem>`、`byCode: Map<elementCode, ElementItem>`（回显映射用，FR-7）。

### 2.2 `ElementRow` 结构调整

现有：
```ts
interface ElementRow { elementCode: string; elementName: string; defaultPct; minPct; maxPct; isLocked; sortOrder; }
```
改为（新增一个前端态字段，**不进提交体**）：
```ts
interface ElementRow {
  elementNo: string | null;      // ★ 新增：Select 的 value（稳定标识）
  elementCode: string;           // 保留：提交体字段 + 脏值原文承载
  elementName: string;           // 保留：提交体字段
  unmatched?: boolean;           // ★ 新增：原值不在字典中（FR-7 第三种情况）
  defaultPct; minPct; maxPct; isLocked; sortOrder;
}
```

⚠️ **Select 的 `value` 必须用 `elementNo`**，不要用 `elementCode`（符号可被改）也不要用数组下标（AP-54：下标错配会写到别的行）。

### 2.3 回显映射（FR-7，编辑入口）

在 `useEffect([open, editingDetail, form])` 里，把 `editingDetail.elements` 映射成 `ElementRow` 时，**必须等字典就绪**（把 `elementDict` 加进依赖，或在字典 ready 后再做一次 reconcile）：

| 老数据情况 | `elementNo` | `unmatched` | 界面表现 |
|---|---|---|---|
| `byCode` 命中且 `status='ACTIVE'` | 命中项的 `elementNo` | false | 正常选中态 |
| `byCode` 命中但 `status='INACTIVE'` | 命中项的 `elementNo` | false | 正常选中态 + 右侧灰色 `已停用` Tag，可保存（D3） |
| `byCode` 未命中 | `null` | **true** | 元素框为空 + 红色文案 `原值「<原 elementCode>」不在元素字典中，请重新选择`，阻断保存（D4） |

> 现网命中此第三行的仅 2 条：材质 `992`（`element_code='10001'`）与 `00262`（`'10004'`）。

新建入口：初始行为 `{ elementNo: null, elementCode: '', elementName: '', defaultPct: '100', … }`，`addElement()` 同理给空元素。

### 2.4 元素列渲染

```tsx
{
  title: '元素',
  key: 'element',
  width: 260,
  render: (_: unknown, r: ElementRow, i: number) => ( /* Select + 提示 */ ),
}
```

Select 关键配置：

| 配置 | 值 | 对应 |
|---|---|---|
| `showSearch` | true | FR-3 |
| `value` | `r.elementNo ?? undefined` | 2.2 |
| `placeholder` | `请选择元素` | FR-9 |
| `loading` | `dictLoading` | —— |
| `style` | `{ width: '100%' }` | —— |
| `options` | 见下 | FR-4 / FR-5 |
| `filterOption` | **自写**：对 `elementNo` / `elementCode` / `elementName` 三字段做 `toLowerCase().includes(input.trim().toLowerCase())`，命中任一即 true | FR-3、AC-2 |
| `notFoundContent` | `dictError ? '元素字典加载失败' : '未找到该元素，请先到「主数据维护 → 元素」维护后再选择'` | FR-6 / FR-10、AC-6 |
| `onChange` | 按 `elementNo` 查 `byNo`，写回 `{ elementNo, elementCode: item.elementCode, elementName: item.elementName, unmatched: false }` | FR-8 |

`options` 构造：

```ts
const selectedNos = new Set(elements.map(e => e.elementNo).filter(Boolean));  // 全表已选
// 每行独立构造：候选 = ACTIVE 元素 ∪ {本行当前选中项(即使 INACTIVE)}
// disabled = selectedNos.has(opt.elementNo) && opt.elementNo !== r.elementNo   ← FR-5 / AC-5
label = `${elementNo} / ${elementCode} / ${elementName}`                        // FR-2 / AC-2 / AC-3
```

行内附加显示：
- `unmatched === true` → Select 下方一行红字（`#ff4d4f`，12px）：`原值「{r.elementCode}」不在元素字典中，请重新选择`
- 选中项 `status === 'INACTIVE'` → Select 右侧 `<Tag>已停用</Tag>`（灰色）

**删除的列**：原「元素 code」列（`MaterialRecipeEditDrawer.tsx:175-186`）与「元素名」列（同文件 `:187-198`）整块删除，不保留任何 `Input`（AC-9）。

### 2.5 保存前校验（`handleSubmit`，在现有 `sumOk` 校验之前）

```ts
// FR-9：未选元素
const emptyIdx = elements.findIndex(e => !e.elementNo);
if (emptyIdx >= 0) { message.error(`请为第 ${emptyIdx + 1} 行选择元素`); return; }
// FR-7 / D4：字典外脏值
const badIdx = elements.findIndex(e => e.unmatched);
if (badIdx >= 0) { message.error(`第 ${badIdx + 1} 行的元素不在元素字典中，请重新选择`); return; }
```

提交体构造（`req.elements`）**保持现状字段集合**，不加 `elementNo`（api.md API-2）：

```ts
elements: elements.map(e => ({
  elementCode: e.elementCode, elementName: e.elementName,
  defaultPct: e.defaultPct,
  minPct: e.isLocked ? undefined : (e.minPct ?? undefined),
  maxPct: e.isLocked ? undefined : (e.maxPct ?? undefined),
  isLocked: e.isLocked, sortOrder: e.sortOrder,
}))
```

### 2.6 与 `recipeType` 切换的兼容（已知坑）

`onRecipeTypeChange()` 会 `setElements(prev => prev.map(...))` 整表重建行对象。改造后必须确认它**透传** `elementNo` / `elementCode` / `elementName` / `unmatched`（用 `{ ...e, ... }` 展开即可，不要重新构造对象）。切换 `locked → editable → partial` 后元素选中态不得丢失。

## 3. 状态管理与缓存 key

- 无全局缓存、无 `driverExpansionKey` 之类的协议缓存参与（本改动不碰报价渲染链路）。
- 字典生命周期 = 抽屉一次打开；关闭即随 `destroyOnClose` 卸载。不做跨抽屉缓存（避免用户在元素 Tab 新增后拿到陈旧列表）。

## 4. 调用的接口

| 编号 | 接口 | 时机 |
|---|---|---|
| API-1 | `GET /api/cpq/elements`（不传 keyword） | 抽屉打开时 1 次 |
| API-2 | `POST /api/cpq/material-recipes` / `PUT /api/cpq/material-recipes/{id}` | 点保存，契约不变 |

## 5. 边界与空态

| 场景 | 表现 |
|---|---|
| 字典加载中 | Select `loading`，可展开但候选为空 |
| 字典加载失败 | `message.error` + `notFoundContent='元素字典加载失败'`（FR-10）；**不得**表现为「无可选元素」 |
| 字典为空（0 条） | 空态文案同 FR-6 |
| 全部元素都已被其他行选完 | 剩余行下拉全部置灰（合法表现，用户需先删行或改选） |
| 输入含前后空格 | `filterOption` 内 `trim()` 后匹配 |
| 老数据 `element_code` 为空串 | 归入 `unmatched=false` + `elementNo=null` 的「未选择」态，走 FR-9 拦截 |

## 6. 自检项（提交前必须逐条跑，附输出）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/config/MaterialRecipeEditDrawer.tsx` → **200**
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/` → **200**
- [ ] **E2E：不触发**（改动文件不在 `CLAUDE.md` 强制 E2E 清单内，`需求文档.md` §7 已声明）
- [ ] 浏览器 F12 Network：打开抽屉 `GET /elements` 恰 1 次；下拉输入过滤 5 次无新请求（AC-10 证据截图）
- [ ] worktree 纪律：`git add` 只加本任务文件，提交后 `git show --stat` 自查

> ⚠️ worktree 里**不要另起 dev server**，复用主工作区 5174；如需在 worktree 内验证，按记忆 `cpq-worktree-frontend-selfcheck` 软链 `node_modules` + 临时端口，或直接以 `tsc` + 代码走查为准。

## 7. Task 列表（逐项勾选）

- [ ] T1 引入 `elementService` + 字典加载 `useEffect` + `byNo`/`byCode` 索引（2.1）
- [ ] T2 `ElementRow` 增加 `elementNo` / `unmatched` 字段，新建与 `addElement` 初值同步调整（2.2）
- [ ] T3 编辑回显映射三分支（命中 ACTIVE / 命中 INACTIVE / 未命中）（2.3）
- [ ] T4 删除「元素 code」「元素名」两列，新增「元素」Select 列（2.4）
- [ ] T5 `filterOption` 三字段匹配 + `notFoundContent` 空态两种文案（2.4）
- [ ] T6 跨行去重置灰 + 本行自身不置灰（2.4）
- [ ] T7 `已停用` Tag 与 `unmatched` 红字提示（2.4）
- [ ] T8 `handleSubmit` 两条前置校验 + 提交体字段集合保持不变（2.5）
- [ ] T9 `onRecipeTypeChange` 透传新增字段，切换类型不丢选中（2.6）
- [ ] T10 自检项全过，输出证据
