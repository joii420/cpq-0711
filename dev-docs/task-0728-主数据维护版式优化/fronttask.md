# task-0728 主数据维护版式优化 · 前端任务

> 上游：[`需求说明.md`](./需求说明.md) · 契约：[`api.md`](./api.md) · 原型：[`主数据维护优化原型-v1.html`](./主数据维护优化原型-v1.html)
> 本任务是**版式统一**：不改任何列表的取数口径与业务字段，不改导入解析逻辑。

---

## 总览

| 任务 | 内容 | 依赖 | 规模 |
|---|---|---|---|
| F0 | 共享约定常量 + 紧凑上传框组件 | — | S |
| F1 | 壳页页签增删排序 + 导入按钮移位 | F0 | S |
| F2 | 料号核价页签：过滤 / 排序 / 导入按钮入驻 | F0、B1 | M |
| F3 | 材质页签：拆 Card + 前端分页 / 排序 / 过滤 | F0 | M |
| F4 | 元素页签：拆 Card + 前端分页 / 排序 / 过滤 + 按钮收下拉 | F0 | M |
| F5 | 工序页签：对齐规范 + 排序 / 过滤 | F0、B2、B3 | M |
| F6 | 4 个导入抽屉统一 | F0、B4 | M |
| F7 | 「数据模板」路由下线 | — | XS |

**执行顺序**：F0 必须先做（后续都依赖它的常量与组件）。F1 与 F7 可与 F2~F6 并行。F2/F5 需等对应后端参数就绪（可先按契约写、后联调）。

---

## F0 · 共享约定与组件

### F0-1 约定常量

新建 `cpq-frontend/src/pages/master-data/listConventions.ts`：

```ts
/** 主数据维护 4 个页签的统一版式约定（task-0728）。新增页签一律引用本文件，不要各自写死。 */
export const SEARCH_WIDTH = 280;
export const FILTER_MIN_WIDTH = 150;
export const SEARCH_DEBOUNCE_MS = 300;
export const DEFAULT_PAGE_SIZE = 20;
export const PAGE_SIZE_OPTIONS = ['10', '20', '50', '100'];

/** 四个页签共用的分页配置（服务端分页页签把 current/total/onChange 覆盖掉即可） */
export const commonPagination = {
  showSizeChanger: true,
  pageSizeOptions: PAGE_SIZE_OPTIONS,
  defaultPageSize: DEFAULT_PAGE_SIZE,
  showTotal: (t: number) => `共 ${t} 条`,
} as const;
```

> 材质/元素页签在 `pages/config/` 下，跨目录 import 本文件即可（不必移动文件）。

### F0-2 紧凑上传框组件

新建 `cpq-frontend/src/components/CompactUploadDragger.tsx`：4 个导入抽屉共用，**避免 4 处各写一遍样式导致漂移**。

- 内部就是 `Upload.Dragger`，透传 `accept` / `fileList` / `beforeUpload` / `onRemove` / `disabled` / `maxCount` 等 props；
- 视觉：图标（`InboxOutlined`，`fontSize: 22`）与文案**同一行**横排（flex + gap），容器 `padding: 14px 16px`，高度约 64px（改造前约 180px）；
- 两行文案通过 props 传：`text`（主）/ `hint`（次，`fontSize: 12`、次要色）；
- **选中文件后必须仍能看到文件名与移除按钮**（`Upload` 默认的 `itemRender` 在 Dragger 下方，不受影响 —— 改完实测一次）。

### F0-3 统一工具栏结构（约定，不强制抽组件）

四个页签的工具栏一律写成：

```tsx
<div style={{ display:'flex', justifyContent:'space-between', alignItems:'center', gap:8, flexWrap:'wrap', marginBottom:12 }}>
  <Space wrap>{/* 左：搜索 → 过滤下拉 */}</Space>
  <Space wrap>{/* 右：次级下拉 → 刷新 → 导入 → 新建 */}</Space>
</div>
```

带 `SelectableTable` 的页签把这段塞进它的 `toolbar` prop（`SelectableTable` 内部已是 `space-between` 容器，注意**不要套两层 flex 导致右组被挤**——实测确认对齐效果，必要时把上面这段直接作为 `toolbar` 内容的两个子节点）。

---

## F1 · 壳页改造

**文件**：`cpq-frontend/src/pages/master-data/MasterDataHubPage.tsx`（现 47 行，全文重写即可）

1. `items` 改为 4 项，顺序固定：

```tsx
{ key: 'part-costing', label: '料号核价', children: <PartCostingTab /> },
{ key: 'material',     label: '材质',     children: <MaterialRecipeManagement /> },
{ key: 'element',      label: '元素',     children: <ElementManagement /> },
{ key: 'process',      label: '工序',     children: <V6ProcessCrudTab /> },
```

2. 删除 `bom` / `dataTemplate` 两项，并删掉 `V6BomQueryTab`、`ConfigTemplateManagement` 两个 import。
   ⚠️ **两个组件文件本身保留，不要删文件**（`V6BomQueryTab.tsx` 按 D 决策保留待用；`ConfigTemplateManagement.tsx` 按 D4 保留文件、只下线路由）。

3. 默认页签：`useState<string>('part-costing')`。

4. **移走顶部「导入核价数据」按钮**：删掉 `page-head` 里的 `Space`/`Button`、`pricingImportOpen` state、`<PricingBasicDataImportDrawer>` 挂载与 import —— 这些整体迁到 F2（料号核价页签内）。顶部只留 `<h2>主数据维护</h2>`。

5. `destroyInactiveTabPane` 保持不变。

---

## F2 · 料号核价页签

**文件**：`cpq-frontend/src/pages/master-data/part-costing/PartCostingTab.tsx`（现 152 行）、`part-costing/api.ts`

1. **接管导入抽屉**（需求 6）：把 F1 移走的 `PricingBasicDataImportDrawer` state + 挂载搬到本组件；按钮放工具栏右侧，`type="primary"` + `<ImportOutlined/>`，文案「导入核价数据」。
   - 可见性沿用现状（原按钮无角色判断 → 这里也不加）；
   - 导入完成后**刷新列表**（`onClose` 时 `fetchList(...)`，改造前没有这一步，属顺手补的合理行为）。

2. **工具栏**按 F0-3 结构：左＝搜索（宽度改用 `SEARCH_WIDTH`，占位保持「按料号 / 品名搜索」）+「配置状态」`Select`（选项：全部 / 已配齐 / 未配齐）；右＝刷新 + 导入核价数据。

3. **过滤**：`configured` 状态（`undefined | true | false`）加入 `fetchList` 参数与 `useEffect` 依赖；变化时 `setPage(1)`。

4. **排序**：给 6 个列都加 `sorter: true`（服务端排序），并受控 `sortOrder`：
   - 维护 `sortBy` / `sortOrder` state；
   - `<Table onChange={(pagination, filters, sorter) => ...}>` 里读 `sorter.field` / `sorter.order`（`'ascend' | 'descend' | undefined`），映射为 `api.md` A1 的 `sortBy` / `asc|desc`，`undefined` 时清空两者回默认序；
   - 每列 `sortOrder: sortBy === '<field>' ? sortOrder : null`（保证单列排序、视觉唯一高亮）；
   - 排序变化 `setPage(1)`。
   - **列 `dataIndex` 与 `api.md` A1 白名单 key 必须一一对应**：`materialName / materialNo / specification / dimension / configured→configuredCount / lastUpdatedAt`。
     注意「已配置」列现在 `key: 'configured'` 但没有 `dataIndex`，排序时要传的是 `configuredCount`——显式写 `sorter: true` 并在 onChange 里按 `sorter.columnKey` 映射，别依赖 `field`。

5. **分页**：合并 `commonPagination`（补上 `pageSizeOptions`，现在只有 `showSizeChanger`）。

6. `api.ts#listParts` 参数类型扩为 `{ keyword?, page?, size?, sortBy?, sortOrder?, configured? }`，`api.get` 直接透传（`undefined` 的键 axios 会自动省略，确认一下当前 `params` 用法是否会把 `undefined` 序列化成空串）。

7. **保持不动**：不加多选、不加动作条（Master-Detail 例外白名单），点行进抽屉的行为原样。

---

## F3 · 材质页签

**文件**：`cpq-frontend/src/pages/config/MaterialRecipeManagement.tsx`（现 197 行）

1. **拆 Card**：删掉 `<Card title="材质管理" extra={...}>` 外壳，改为 F0-3 的工具栏 + `SelectableTable`（`toolbar` prop 承载工具栏）。

2. **工具栏**：左＝搜索（`SEARCH_WIDTH`，占位不变）+「类型」`Select`（标准锁定/含量可调/部分可调）+「状态」`Select`（启用/停用）；右＝刷新（新增）+ 导入材质库（图标 `UploadOutlined` → **`ImportOutlined`**）+ 新建材质（primary，保持）。

3. **前端过滤**：后端 `list` 仍返全量，`recipeType` / `status` 两个过滤在内存里 filter（与关键字搜索叠加：关键字仍走后端，两个过滤走前端）。

4. **前端排序**：7 个列各加 `sorter: (a,b)=>...` 比较函数（不是 `sorter: true`）：
   - 文本列 `String(a.x ?? '').localeCompare(String(b.x ?? ''))`；
   - 数字列（`sortOrder`）数值比较；
   - 时间列（`createdAt`/`updatedAt`）按原始 ISO 串比较即可（勿用格式化后的字符串）；
   - 空值统一排在后面（比较函数里把 null/undefined 视为最大）。
   - **不设 `defaultSortOrder`** —— 未点击时保持后端返回的默认序（启用优先→改时倒序→建时倒序），这就是「三态里的取消态」。

5. **前端分页**：`SelectableTable` 的 `pagination` 从 `false` 改为 `{...commonPagination}`（Table 自带前端分页，数据源是过滤后的数组）。
   ⚠️ `SelectableTable` 开了 `preserveSelectedRowKeys`，跨页选中会保留 —— 这是既有设计（《列表操作规范》要求跨页保留选中），**不要改**。但要实测：分页后批量停用仍作用于全部选中项。

6. 过滤/搜索变化后回到第 1 页（前端分页需手动 `setCurrentPage(1)` 或给 Table 受控 `pagination.current`）。

---

## F4 · 元素页签

**文件**：`cpq-frontend/src/pages/config/ElementManagement.tsx`（现 205 行）

1. **拆 Card**（同 F3）。

2. **按钮收下拉**（D3）：三个价格入口收进 `Dropdown`：

```tsx
<Dropdown menu={{ items: [
  { key:'source', label:'价格源管理', icon:<LinkOutlined/> },
  { key:'import', label:'价格导入',   icon:<ImportOutlined/> },
  { key:'table',  label:'元素价格表', icon:<TableOutlined/> },
], onClick: ({key}) => openByKey(key) }}>
  <Button>元素价格 <DownOutlined /></Button>
</Dropdown>
```
   三个 Drawer 组件的挂载与 props **保持不动**，只换触发入口。

3. **工具栏**：左＝搜索 + 「状态」`Select`；右＝元素价格▾ + 刷新（新增） + 新建元素（primary）。

4. **前端排序 / 分页 / 过滤**：同 F3 口径。列：元素编号 / 符号 / 中文名 / 被引用材质数（数值排序）/ 状态 / 最后修改时间。
   ⚠️ 「符号」列的 render 里带锁图标 `<Space>`，排序比较函数要取 `r.elementCode` 原值，不要受 render 影响。

---

## F5 · 工序页签

**文件**：`cpq-frontend/src/pages/master-data/V6ProcessCrudTab.tsx`（现 274 行）

1. **搜索框** 240 → `SEARCH_WIDTH`；现在用的是 `Input` + `prefix={<SearchOutlined/>}`，为与其余三页签一致改成 `Input.Search`（保留现有防抖与 `onClear` 逻辑）。

2. **过滤**：新增「是否外协」（外协/自制）+「工序分类」两个 `Select`，走后端参数（`api.md` A2）。
   工序分类选项调 **A3** `GET /v6/process-master/categories`，组件挂载时拉一次；返空数组时该下拉禁用 + tooltip「暂无分类数据」。

3. **排序**：8 个数据列 `sorter: true` + 受控 `sortOrder`，映射 `api.md` A2 白名单；实现方式同 F2。

4. **分页**：删掉写死的 `const PAGE_SIZE = 20`，改为 `size` state + `commonPagination`（`showSizeChanger` 由 `false` 改 `true`），`onChange: (p, s) => { setPage(p); setSize(s); }`。
   ⚠️ 该页签的 `listProcesses` 传的是 `page: pg - 1`（**后端 0-based**），改动时别把这个 -1 弄丢。

5. **按钮顺序**：刷新 → 导入工序（图标已是 `ImportOutlined`✓）→ 新增工序（primary），移到工具栏**右组**（现在四个控件都在左组）。

6. 现有动作条（编辑 / 删除）与两个抽屉**不动**。

---

## F6 · 4 个导入抽屉统一

| 抽屉 | 文件 | 现状 |
|---|---|---|
| 核价数据 | `master-data/PricingBasicDataImportDrawer.tsx` | 840 宽；**无模板按钮**；「开始导入」在 Drawer `extra` |
| 工序 | `master-data/ProcessMasterImportDrawer.tsx` | 720；模板按钮 `default` 埋在 Alert 正文；「开始导入」在正文 |
| 材质库 | `config/MaterialImportDrawer.tsx` | 720；同工序 |
| 元素价格 | `element-price/PriceImportDrawer.tsx` | 720；模板按钮 `size="small"` 独立一行；已有 footer |

**统一四段式**（每个抽屉都改成这个骨架）：

1. **宽度 840**（D6）——四个都设 `width={840}`。
2. **说明 Alert**：`description` 内用 `display:flex; justify-content:space-between; gap:12` 两栏，左＝说明文字（**原文保留，不要改文案**），右＝「下载模板」按钮：
   ```tsx
   <Button icon={<DownloadOutlined />} loading={downloading} onClick={handleDownloadTemplate} style={{ flex:'none' }}>
     下载模板
   </Button>
   ```
   —— 统一 `default` 尺寸（元素价格抽屉的 `size="small"` 去掉），统一文案「下载模板」。
3. **紧凑上传区**：换成 F0-2 的 `<CompactUploadDragger>`，`text`/`hint` 沿用各抽屉原文案。
4. **footer**：`[重置] [开始导入]` 右下角。
   - 核价数据抽屉把 `extra` 里的两个按钮搬到 `footer`；
   - 工序 / 材质抽屉把正文里的「开始导入」搬到 `footer`，并补一个「重置」；
   - 已有报告时的「完成」按钮保留在 footer（与「开始导入」并存或替换，按各抽屉现有逻辑，**行为不变**）。
5. **结果报告区一字不动。**

**新增（B4 联调）**：核价数据抽屉接 `GET /basic-data-import/v6/pricing/template`
- 在 `services/basicDataImportV6Service.ts` 加 `downloadPricingTemplate(): Promise<Blob>`，抄 `v6MasterDataService#downloadProcessTemplate` 的 `responseType:'blob'` + Blob 兜底写法；
- 下载文件名 `pricing_basic_data_template.xlsx`。

---

## F7 · 「数据模板」路由下线

**文件**：`cpq-frontend/src/router/index.tsx`

1. 删除 `{ path: 'config/config-templates', element: <ConfigTemplateManagement /> }`（约 `:127`）；
2. 删除对应 import（约 `:49`）；
3. **`pages/configtemplate/ConfigTemplateManagement.tsx` 文件保留**（D4）；
4. 全局搜一遍是否还有别处 `navigate('/config/config-templates')` 之类的跳转（有就一并清理，别留死链）。

---

## 交付自检（CLAUDE.md 前端口径，逐条附输出）

1. [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
2. [ ] 每个改动的 `.tsx` 都 `curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<相对路径>` → **200**
   （至少覆盖：`MasterDataHubPage.tsx`、`PartCostingTab.tsx`、`MaterialRecipeManagement.tsx`、`ElementManagement.tsx`、`V6ProcessCrudTab.tsx`、4 个导入抽屉、`CompactUploadDragger.tsx`、`listConventions.ts`、`router/index.tsx`）
3. [ ] `curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" http://localhost:5174/` → 200
4. [ ] 浏览器实测（`/master-data-hub`）：
   - 4 个页签、顺序正确、默认停在料号核价；
   - 每个页签点两个不同列排序，**翻到第 2 页确认跨页排序正确**（料号核价/工序，见验收标准 18）；
   - 每个过滤下拉选一次 + 清空一次，「共 N 条」随之变化；
   - 页大小切到 50 再切回 20；
   - 4 个导入抽屉各开一次，看宽度、模板按钮位置、上传框高度、footer 按钮；
   - **核价数据抽屉下载模板 → 原样上传回去，确认不报「缺少 Sheet」**（验收标准 24，需 B4 就绪）；
   - `/config/config-templates` 手输 URL 进不去。
5. [ ] 抽查 1 张报价单打开正常（本任务不触碰报价链路，仅确认无意外波及）

> **不需要跑 `quotation-flow.spec.ts` E2E**：本任务不改 `useDriverExpansions.ts` / `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `component/types.ts` 等协议级文件，也不动 `field_type`（AP-44 不触发）。若实现过程中发现必须改上述任一文件，**立即停下来上报**，那意味着方案跑偏了。
