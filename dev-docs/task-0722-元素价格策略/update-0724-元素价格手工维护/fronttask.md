# 前端任务文档 · 元素价格手工维护（update-0724）

> 隶属：`dev-docs/task-0722-元素价格策略` · update-0724
> 契约以同目录 `api.md` 为准；需求裁决以 `需求文档.md` §11（U0~U12）为准。
> 开发在独立 worktree 分支进行。技术总监负责验收与合并，不代写代码。

---

## F0 · 开工前必读

### 已核实的现状（技术总监 2026-07-23 亲验，可直接采信）

| # | 事实 | 位置 |
|---|---|---|
| 1 | 价格表抽屉 `ElementPriceTableDrawer.tsx`，`width={1200}`，`Tabs` 现有 2 项：`detail`（明细）/ `matrix`（矩阵） | `:34/:42-45` |
| 2 | 明细 Tab `PriceDetailTab.tsx` 共 **155 行**，用**裸 `<Table>`**，无 `rowSelection`、无工具栏 | 全文 |
| 3 | 明细 Tab 的 `rowKey` 是组合键 `${elementCode}__${sourceId}__${priceDate}` | `:120` |
| 4 | 数据服务是 **`elementPriceStrategyService`**（不是 `elementPriceService`），类型在 `types/element-price-strategy.ts` | `:5-6/:41` |
| 5 | `sources`（价格源列表）由父抽屉以 prop 传入，明细 Tab 不自己请求 | `:18/:95` |
| 6 | `ElementPriceHint` 挂在 `QuotationStep2.tsx`：`import` 在 `:19`，判定 `:2725-2731`，渲染 `:2777-2787` | 源码 |
| 7 | `showElementHint` 依赖 `row.element_name`，而**全库 0 个组件视图输出 `element_name`、0 个组件字段名为 `element_name`** → 该组件**从不渲染** | U0 ⑤ |
| 8 | `SelectableTable` 位于 `components/SelectableTable.tsx`，导出 `SelectableTable` + `runBatch` | 源码 |

### 两条硬规范

1. **列表操作必须走 `SelectableTable` + 顶部工具栏**（`docs/列表操作规范.md`，PR 评审强制项）。
   行内**不放**动作按钮；动作禁用时 hover 显示原因，**禁止 `if (...) return null` 隐藏按钮**。
2. **弹出式交互统一用 `Drawer`，不用 `Modal`**（CLAUDE.md「UI 交互规范」）。
   例外：`SelectableTable` 内建的危险动作二次确认 Modal 属规范指定形态，**照用不改**。

---

## F1 · 类型与服务层

### F1.1 `types/element-price-strategy.ts` —— `ElementPriceRowDTO` 补两个字段

```ts
export interface ElementPriceRowDTO {
  id: string;              // ← 新增：element_daily_price.id，rowKey / PUT / DELETE 都要用
  elementCode: string;
  elementName: string;
  priceDate: string;
  sourceId: string;
  sourceName: string;
  sourceStatus: string;
  price: number;
  currency: string;
  priceUnit: string;
  fetchStatus: 'SUCCESS' | 'FAILED' | 'MANUAL' | 'IMPORT';   // ← 新增
  operatorName: string;
  updatedAt: string;
}
```

> 这两个字段是本期后端 B3 新补的（需求文档原本没覆盖，技术总监审阅时发现）。**后端 B3 未合入前 F2/F3 无法联调**，请与后端确认 B3 已先行落地。

### F1.2 新增类型

```ts
export interface CreatePriceRequest {
  elementCode: string;
  sourceId: string;
  priceDate: string;    // yyyy-MM-dd
  price: number;
  currency: string;
  priceUnit: string;
}

/** 注意：故意不含 elementCode / sourceId / priceDate —— 键锁定由后端硬保证 */
export interface UpdatePriceRequest {
  price: number;
  currency: string;
  priceUnit: string;
}

export interface PriceChangeDTO {
  field: string;
  fieldLabel: string;
  oldValue: string;
  newValue: string;
}

export interface PriceHistoryDTO {
  id: string;
  changedAt: string;
  changedByName: string;
  action: 'CREATE' | 'UPDATE' | 'DELETE';
  elementCode: string;
  elementName: string;
  sourceId: string | null;
  sourceName: string | null;
  priceDate: string;
  targetLabel: string;
  changes: PriceChangeDTO[];      // 仅 UPDATE 非空
  snapshot: Record<string, unknown>;
}
```

### F1.3 `services/elementPriceStrategyService.ts` 加 4 个方法

```ts
createPrice: (body: CreatePriceRequest) => api.post('/element-price/prices', body),
updatePrice: (id: string, body: UpdatePriceRequest) => api.put(`/element-price/prices/${id}`, body),
deletePrice: (id: string) => api.delete(`/element-price/prices/${id}`),
listPriceHistory: (params: {...}) => api.get('/element-price/prices/history', { params }),
```

⚠️ **新端点不包 `ApiResponse`**（api.md §0.2）。照抄同文件里 `listPrices` 的解包写法，**不要**再多解一层 `.data`。

---

## F2 · 明细 Tab 改造为 `SelectableTable` + 工具栏

**文件**：`pages/element-price/PriceDetailTab.tsx`

### 改动点

| 项 | 从 | 到 |
|---|---|---|
| 表格组件 | 裸 `<Table>` | `<SelectableTable>` |
| `rowKey` | `${elementCode}__${sourceId}__${priceDate}` | **`'id'`** |
| 工具栏 | 无 | `[＋ 新建][✎ 编辑][🗑 删除(danger)]` |
| 列 | 9 列 | 10 列（加「数据来源」展示 `fetchStatus`） |

> **`rowKey` 必须改成 `id`。** `SelectableTable` 默认开启跨页保留选中，组合键在不同页可能重复，会导致选中串行。

### 工具栏动作

```tsx
actions={[
  {
    key: 'create', label: '＋ 新建', type: 'primary',
    enabledWhen: () => true,
    onClick: () => openEditor('create'),
  },
  {
    key: 'edit', label: '✎ 编辑',
    enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
    onClick: (rows) => openEditor('edit', rows[0]),
  },
  {
    key: 'delete', label: '🗑 删除', danger: true, needsConfirm: true,
    confirmTitle: '确认删除选中的 {N} 条价格？',
    confirmDescription: '删除后该源该日期的价格将从取价窗口中消失，可能影响客户报价成本。',
    enabledWhen: (rows) => rows.length >= 1 ? true : '请先勾选要删除的价格',
    onClick: async (rows) => { /* 见下 */ },
  },
]}
rowLabel={(r) => `${r.elementCode} · ${r.sourceName} · ${r.priceDate}`}
```

> 动作的 `key`/`label`/`enabledWhen`/`needsConfirm`/`confirmTitle`/`confirmDescription`/`danger` 字段名以 `components/SelectableTable.tsx` 的 `ToolbarAction<T>` 接口为准，上面是示意，**实现时以接口签名为准**。

### 删除走 `runBatch`

```tsx
onClick: async (rows) => {
  const { ok, failed } = await runBatch(
    rows,
    (r) => elementPriceStrategyService.deletePrice(r.id),
    { rowLabel: (r) => `${r.elementCode} · ${r.sourceName} · ${r.priceDate}` },
  );
  if (ok > 0) message.success(`已删除 ${ok} 条`);
  query(page);   // 刷新当前页
}
```

`runBatch` 会用 `Promise.allSettled` 收集结果并 `message.error` 列出失败明细（规范要求的"部分失败"处理）。

### 新增列

```tsx
{
  title: '数据来源', dataIndex: 'fetchStatus',
  render: (v: string) => v === 'MANUAL'
    ? <Tag color="blue">手工</Tag>
    : v === 'IMPORT' ? <Tag>导入</Tag> : <Tag>{v}</Tag>,
}
```

> 这一列是验收 1/3 的**唯一 UI 验证手段**（"列表出现该行 `fetch_status='MANUAL'`"），不能省。

### 保持不变

筛选区（价格源 / 日期区间 / 元素）、查询/重置按钮、导出 Excel 按钮、分页——**全部保持现状**，只是移到 `SelectableTable` 的 `toolbar` slot 里（工具栏动作按钮与筛选控件并存）。

---

## F3 · 价格编辑抽屉（新增）

**新建**：`pages/element-price/PriceEditDrawer.tsx`

### 形态

- 二级抽屉，`width={480}`，`placement="right"`
- 父抽屉 `ElementPriceTableDrawer` 是 `width={1200}`，二级抽屉盖在其上

### 两种态

| 字段 | 新建态 | 编辑态 |
|---|---|---|
| 元素 | `Select`，选项来自 `GET /api/cpq/elements`，只列 `ACTIVE` | **置灰只读** |
| 价格源 | `Select`，来自父组件 `sources` prop，只列 `ACTIVE` | **置灰只读** |
| 价格日期 | `DatePicker`，必填 | **置灰只读** |
| 单价 | `InputNumber`，必填，`min` 校验 > 0，精度 4 位 | 可改 |
| 货币 | 必填 | 可改 |
| 计价单位 | 必填 | 可改 |

> **置灰只是 UX，不是安全边界。** 后端 `UpdatePriceRequest` 根本不含这三个字段（api.md §2），前端传了也会被 Jackson 丢弃。不要因为"后端会拦"就省掉置灰，也不要因为"前端置灰了"就假设后端不用管。

### 元素下拉数据源

```
GET /api/cpq/elements?keyword={kw}
```

⚠️ **不要**用 v1 的 `GET /element-prices/available-elements`——该端点本期随 v1 一并下线（F5）。
task-0722 的策略例外抽屉选元素走的也是 `/api/cpq/elements`，本次与之保持一致（需求 §4.3 规则 8）。

### 错误处理

| 后端码 | 前端表现 |
|---|---|
| `409` | `message.error` 显示后端 message（"该元素在该源该日期已存在价格，请改用编辑"），**抽屉不关闭**，让用户改日期或改走编辑 |
| `400` | 显示后端 message，抽屉不关闭 |
| `404`（编辑/删除时） | 提示"该价格已被删除"，关闭抽屉并刷新列表 |

### 保存成功后

关闭抽屉 → 刷新明细列表当前页 → `message.success`。

---

## F4 · 变更历史 Tab（新增第三个 Tab）

**新建**：`pages/element-price/PriceHistoryTab.tsx`
**改动**：`ElementPriceTableDrawer.tsx` 的 `Tabs.items` 加第三项

```tsx
{ key: 'history', label: '变更历史', children: <PriceHistoryTab active={activeKey === 'history'} sources={sources} /> }
```

### 筛选

与明细 Tab **完全一致**的三个控件（价格源 / 日期区间 / 元素）——直接抄 `PriceDetailTab` 的筛选区。

> ⚠️ 语义差异要在 UI 上说清：明细 Tab 的日期区间过滤的是**价格日期**，历史 Tab 过滤的是**变更时间**。建议历史 Tab 的日期标签写「变更时间」而非「日期区间」。

### 列

| 列 | 数据 | 渲染 |
|---|---|---|
| 变更时间 | `changedAt` | `YYYY-MM-DD HH:mm` |
| 操作人 | `changedByName` | — |
| 动作 | `action` | `CREATE`→`<Tag color="green">新建</Tag>`；`UPDATE`→`<Tag color="blue">修改</Tag>`；`DELETE`→`<Tag color="red">删除</Tag>` |
| 目标 | `targetLabel` | `Ag 银 · 上海有色网 · 2026-07-24` |
| 变更内容 | `changes` / `snapshot` | 见下 |

### 「变更内容」渲染规则

- `action === 'UPDATE'` → 渲染 `changes` 数组，逐行 `{fieldLabel}: {oldValue} → {newValue}`
- `action === 'CREATE'` / `'DELETE'` → `changes` 为空，改渲染 `snapshot` 全量（`单价 5860.0000 CNY/kg`）

> 与 task-0722 策略历史 Tab 的渲染逻辑同构（后端 DTO 形状是逐字比照的），**可以直接复用/抽取该 Tab 的渲染函数**，不要重写一套。

### 只读

历史 Tab **纯只读**，不提供回滚/还原（U11 第 2 条，比照 task-0722 §11.14F.2）。不加任何工具栏动作。

---

## F5 · v1 元素价格中心整体下线

### 删除清单（U8「清到底，不留休眠代码」）

| 层 | 对象 |
|---|---|
| 页面 | `pages/element-price/ElementPriceCenterPage.tsx` |
| 组件 | `pages/element-price/ManualPriceEntryDrawer.tsx` |
| 组件 | `pages/quotation/components/ElementPriceHint.tsx` |
| 路由 | `router/index.tsx` 的 `element-price-center` 路由项 + 对应 `import` |
| service | `elementPriceService.ts` 的 `getReference` / `listHistory` / `upsertManual` / `listAvailableElements`（删完若整个文件为空则删文件） |

### `QuotationStep2.tsx` 挂载点摘除 ⚠️ 协议级文件，务必精确

| 位置 | 内容 |
|---|---|
| `:19` | `import ElementPriceHint from './components/ElementPriceHint';` |
| `:2725-2731` | `elementName` 取值 + `showElementHint` 判定分支 |
| `:2777-2787` | `cellInner = showElementHint ? (...<ElementPriceHint .../>) : (...)` 渲染分支 |

**摘除方式**：`cellInner` 直接取原本的 `else` 分支值，把三元判断整个去掉。

> **为什么删而不是留着**：`showElementHint` 恒为 `false`（U0 ⑤ 实证——全库 0 个组件输出 `element_name` 列）。本期上线后 `MANUAL` 行会大量增加，一旦将来有人给组件加了名为 `element_name` 的字段，这段休眠代码会**突然生效并显示跨源混取的错误价**。这是 AP-53 同族的"断链后休眠复活"风险。
>
> **代价**：`QuotationStep2.tsx` 在 CLAUDE.md「协议级改动必跑 E2E」清单内 → **本期强制跑 E2E**（F7）。

### 删除前核查

```bash
/usr/bin/grep -a -rn "ElementPriceCenterPage\|ManualPriceEntryDrawer\|ElementPriceHint\|element-price-center" cpq-frontend/src
```
> 本机 `grep` 是 `ugrep`，会把中文注释多的大文件**静默当二进制返空** —— 必须用 `/usr/bin/grep -a`（记忆 `cpq-grep-ugrep-binary-pitfall`）。

### 与后端配对

前端删入口 → 后端删端点（B7）。**先前端后后端**，避免出现"按钮还在但端点已 404"的中间态。

---

## F6 · 元素抽屉「各源最新价格」区块 —— 只改空态文案

**保持只读不变**（U9）。该区块是跨源汇总视图、**没有日期维度**，天然不适合承载编辑（"改哪一天的价？"）。

唯一改动：空态文案

| 从 | 到 |
|---|---|
| 「请通过『价格导入』录入」 | 指向元素价格表可手工维护，例如「暂无价格数据，可在「📊 元素价格表」中导入或手工新建」 |

`GET /element-price/latest-by-source` 端点**保留不动**（api.md §6），它是活的消费方。

---

## F7 · 强制自检（缺任一项 = 未完成）

### TypeScript
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json     # 必须 0 错误
```

### Vite transform（每个改动的 `.tsx` 都要 200）
```bash
for f in \
  src/pages/element-price/PriceDetailTab.tsx \
  src/pages/element-price/PriceEditDrawer.tsx \
  src/pages/element-price/PriceHistoryTab.tsx \
  src/pages/element-price/ElementPriceTableDrawer.tsx \
  src/pages/quotation/QuotationStep2.tsx \
  src/router/index.tsx ; do
  printf '%s -> ' "$f"
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' "http://localhost:5174/$f"
done
```
> ⚠️ **必须加 `--noproxy '*'`**（本机 `http_proxy=127.0.0.1:7890` 会让 curl 走代理返 502）。
> ⚠️ `tsc` 不覆盖 Vite/Rollup 的解析阶段错误（字符串嵌套引号、JSX 解析等），两步都要跑。

### E2E —— **强制项，因为动了 `QuotationStep2.tsx`**

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```

**判定方式：A/B 同型对比，看「新增失败数 = 0」，不是「全绿」。**

> `quotation-flow.spec.ts` 在**干净 master 上已知恒 3 失败**（夹具单缺产品分类，见记忆 `task0712-update071501-category-axis`）。
> 必须先在干净 master 上跑一次记录基线，再在本分支跑，**逐条比对失败用例名**。
> **不得**把基线失败误归因为本次改动，也**不得**用"反正本来就红"掩盖新增失败。

同时确认：`'加载中' final count = 0`。

### 手工验收路径

1. 主数据维护 → 元素 → 「📊 元素价格表」→ 明细 Tab → 顶部有 `[＋新建][✎编辑][🗑删除]`，行内无动作按钮
2. 不勾选时「编辑」「删除」禁用，hover 显示原因文案（**按钮存在但禁用，不是消失**）
3. 新建一条 → 列表出现，「数据来源」列显示「手工」
4. 同键再建 → 提示 409 文案，抽屉不关，原值未变
5. 编辑一条 `IMPORT` 行 → 三个键字段置灰 → 改单价保存 → 「数据来源」列翻为「手工」
6. 多选两条 → 删除 → Modal 列出所选项二次确认 → 删除成功
7. 切到「变更历史」Tab → 能看到刚才三次操作，`UPDATE` 那条只列出真正变化的字段
8. 浏览器直接访问 `/element-price-center` → 不可达

### 交付说明必须包含这一行
> "TS 0 错误 ✅；6 个改动 `.tsx` → Vite 200 ✅；E2E A/B 同型对比新增失败 = 0（基线 3 失败，本分支 3 失败，用例名逐条一致）✅；`'加载中' final count = 0` ✅；`/element-price-center` 不可达 ✅；`ElementPriceCenterPage`/`ManualPriceEntryDrawer`/`ElementPriceHint` 全工程 0 命中（`/usr/bin/grep -a`）✅"

**没有这一行的"完成"= 未完成。**

---

## 任务清单与依赖

| 任务 | 依赖 | 规模 | 说明 |
|---|---|:--:|---|
| F1 类型与服务层 | 后端 **B3**（DTO 补 `id`/`fetchStatus`） | S | B3 未合入前无法联调 |
| F2 明细 Tab 改 `SelectableTable` | F1 | M | 规范强制项，PR 评审必查 |
| F3 价格编辑抽屉 | F1、后端 B4/B6 | M | — |
| F4 变更历史 Tab | F1、后端 B5/B6 | M | 渲染逻辑可复用策略历史 Tab |
| F5 v1 下线 | — | S | 需与后端 B7 配对，**先前端后后端** |
| F6 空态文案 | — | XS | — |
| F7 自检 + E2E | 全部 | M | E2E 是硬门槛 |

**建议顺序**：F1 → F2 → F3 → F4 → F6 → F5 → F7。
F5 放靠后是因为它会触发 E2E 门槛，先把主功能做稳再动协议级文件。
