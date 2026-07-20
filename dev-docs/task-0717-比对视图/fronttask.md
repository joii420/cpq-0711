# 报价单比对视图 · 前端任务文档（fronttask.md）

> 面向：cpq-frontend 工程师
> 关联：`api.md`（接口契约）、`需求说明.md §11`、**`prototype-比对视图.html`（1:1 还原基准，最高优先级）**
> 技术栈：React + TypeScript + Ant Design + Vite（端口 5174）

---

## 0. 硬性要求（先读，违反即打回）

1. **🔴 1:1 还原原型 `prototype-比对视图.html`**：布局、间距、配色、字号、图标、交互动效、文案，全部按原型落地。原型是像素级基准，不是"参考"。任何偏离需先报技术总监。下面每节的"对齐原型"清单是验收点。
2. **改造而非新建**：现有 `比对视图` Tab 已有一套 tag-based 实现（`ComparisonView.tsx` / `comparisonModel.ts` / `comparisonTable.tsx` / `ReadonlyComparison.tsx`）。本任务把「列模型」整体换成本文方案。旧文件按 §6 处理（改造/替换，不留双源）。
3. **协议级改动 → 强制 E2E**：`QuotationStep2.tsx` 在 CLAUDE.md 协议级文件清单内。改动后必须跑 Playwright E2E（见 §8），跳过 = 未自检。
4. **前端不取数**：所有比对值来自后端 `data` 端点，前端**只组装/展示/算差异/着色/排序/过滤/分页**，不新写取值逻辑。
5. **抽屉规范**：连线配置用 Ant Design `Drawer`（`placement="right"`），符合项目"抽屉替代弹窗"规范。

---

## 1. 页面挂载点（三处，共用同一比对视图组件）

新建统一组件 `ComparisonBoard`（暂名，替代旧 `ComparisonView`），props：
```ts
interface ComparisonBoardProps {
  quotationId: string;
  bucket: 'SALES' | 'FINANCE';   // 入口桶（不看登录角色）
  readonly?: boolean;            // 详情页=true：隐藏"新增比对"、列删除✕、阈值⚙、不可打开抽屉、不调 PUT
  frozen?: boolean;             // 详情/已提交核价单=true → 请求 data?frozen=true
}
```

| 入口 | 文件 | props | 说明 |
|------|------|-------|------|
| 报价单编辑页（销售） | `QuotationStep2.tsx`（`mainTab==='comparison'`，约 L3503） | `bucket='SALES'`, `readonly=false`, `frozen=false` | 替换现有 `<ComparisonView>` 挂载 |
| 报价单详情页（销售只读） | `ProductDetailViews.tsx`（现挂 `ReadonlyComparison`，约 L199） | `bucket='SALES'`, `readonly=true`, `frozen=true` | 替换现有 `<ReadonlyComparison>` |
| 核价单页面（财务） | `CostingReviewPage.tsx` | `bucket='FINANCE'`, `readonly = !editable`, `frozen = 已提交` | **新增**比对视图入口；`editable` 沿用该页 `editable`（PENDING+财务/管理员）逻辑 |
| 核价单详情（财务只读） | 对应详情视图 | `bucket='FINANCE'`, `readonly=true`, `frozen=true` | 若有独立核价详情入口则同挂只读 |

> CostingReviewPage 现无比对视图入口，需按其现有 Tab/分区结构加一个"比对视图"入口挂 `ComparisonBoard`。保持该页 报价单/核价单 既有渲染不动。

---

## 2. 数据装配（组件内部）

打开时并发拉三份（详情页 data 带 `frozen=true`）：
```
GET /comparison-view/meta                     → 连线抽屉目录（quoteTabs/costingTabs）
GET /comparison-view/data[?frozen=true]       → 取值矩阵 rows[]
GET /comparison-view/config?bucket=<bucket>   → 列配置 columns（null → 种默认列）
```
- **列 = config.columns**；`columns===null` 时前端种入一条默认列 `{id:'__product_total__', kind:'PRODUCT_TOTAL', threshold:0, sortOrder:0}`（不立即落库，用户首次保存时随 PUT 一起提交）。
- 默认列恒为第一列；若 columns 里无 PRODUCT_TOTAL 项，渲染时在最前补齐。
- **取值映射**（每料号每列）：
  - PRODUCT_TOTAL：`row.quote?.productTotal` / `row.costing?.productTotal`
  - TAB_PAIR：`row.quote?.tabs[col.quoteComponentId]?.[col.quoteMetric==='__TAB_TOTAL__'?'tabTotal':'subtotals'][col.quoteMetric]`；costing 侧同理
  - 取不到 → `undefined` → 显示"—"
- **新增/删除/改阈值/改顺序 → 仅改本地 columns + 调 PUT config，不重拉 data**（data 已含所有页签所有小计）。

---

## 3. 主表渲染（对齐原型「比对主表」）

### 3.1 结构
- 每销售料号 = **3 行块**：`报价(数据)` / `核价(数据)` / `差异`。料号列 `rowSpan=3` 合并。
- 固定左两列：`销售料号`（monospace；单边料号追加橙色 Tag「仅报价」/「仅核价」）、`口径`（报价/核价/差异）。
- 数据列 = 列配置：第 1 列默认列「🔒 产品卡片总计 · 默认」（不可删，`readonly=false` 时列头有 ⚙ 改阈值）；其后为用户列（列头分两行「报价：页签·小计 / 核价：页签·小计」+ ✕ 删除 + 「阈值 N」标签，`readonly` 时隐藏 ✕/⚙）。

### 3.2 比对值显示格式（对齐原型 `formatVal`）
- 字段/合计名在末尾"小计/合计"前插入间隔点：`加工费小计`→`加工费·小计`、`页签合计`→`页签·合计`。列头、（如有）任何展示比对值处统一用此格式。

### 3.3 着色（对齐原型，整格填色，仅差异行差异格）
- `diff = quote − costing`（任一侧 undefined/null → "—"，不着色）。
- `diff < 0` → 🔴 红底白字（`#ff4d4f`/`#fff`）**优先**；否则 `diff < col.threshold` → 🟠 橙底白字（`#fa8c16`/`#fff`）；否则无色。
- 差异值显示带正负号（正数 `+`）；数值精度：产品总计列 2 位、页签列 4 位（对齐 `docs/小数显示口径`）。

### 3.4 单边料号变灰（对齐原型 `cq-row-muted`）
- `presence==='QUOTE_ONLY'`（核价缺）：核价数据行整行变灰（`#fafafa`/`#bbb`），差异行差异格"—"。
- `presence==='COSTING_ONLY'`（报价缺）：报价数据行变灰，差异"—"。

### 3.5 精度 / 千分位
- 数值 `toLocaleString`（千分位）；空值"—"（`#bbb`）。

---

## 4. 工具栏（对齐原型「cq-toolbar」）

- **`+ 新增比对`**（primary）：打开连线抽屉；`readonly` 时**隐藏**。
- **`差异料号` 开关**（Ant `Switch` 或原型自绘样式）：勾选 → 差异料号**排序前置**（非过滤），见 §4.1。
- **过滤框**（右对齐，搜索图标）：输入销售料号**子串模糊匹配**，实时过滤；`onChange` 重置到第 1 页。
- **无导出按钮**（本期不做）。

### 4.1 差异料号排序前置
- 判定：某料号**任一列差异格标橙/标红**，或 `presence≠BOTH`（单边料号，优先级最高）。
- 勾选后**稳定排序**前置（差异料号在前，其余保持原顺序在后，仍可见），非隐藏。

### 4.2 分页（对齐原型「cq-pagination」）
- 按销售料号块分页（一个料号 3 行不切断），默认每页 **10**，页大小可选 **10/20/50**；过滤/排序/切页大小后回第 1 页。

---

## 5. 连线配置抽屉（对齐原型「连线配置」，本任务交互核心）

> 严格按 `prototype-比对视图.html` 的连线抽屉实现。以下为逐条还原点。

### 5.1 容器
- Ant `Drawer` `placement="right"`，**宽 960**（`max-width:96vw`），标题「新增比对列 · 连线配置」。

### 5.2 三区布局
- **左列 报价单页签**：遍历 `meta.quoteTabs`，每页签一个分组头（蓝色带 + 左强调条 `#1677ff`），其下每个 metric 为可连接节点（右缘 port）。
- **右列 核价单页签**：遍历 `meta.costingTabs`，节点 **port 在左缘（内侧）**、标签右对齐（连线只走中间空隙、不穿标签）。
- **中间 SVG 画布**：绝对定位覆盖两列，`pointer-events:none`；连线 `<path>` 贝塞尔曲线、`pointer-events:stroke` 可点。
- **三色层级**：分组头=蓝带；`SUBTOTAL_FIELD` 节点=绿点（`#52c41a`）；`__TAB_TOTAL__`（页签合计）节点=橙点橙字（`#fa8c16`/`#d46b08`）。
- **页签折叠（本期做）**：分组头（页签标题）可点击折叠/展开，箭头 ▾（展开）/▸（折叠），默认全展开。折叠时隐藏该页签下节点；折叠/展开后**重绘连线**。
  - **折叠时已连线的处理**：若某连线端点所在页签被折叠（节点隐藏），连线**锚到该页签标题的内侧边缘**并显示为**虚线**（`stroke-dasharray`）——连线不丢、仍指向该页签，已配对清单不变。展开后恢复实线连到具体节点。

### 5.3 点击式连线状态机（非拖拽）
- 点左侧节点 → 该端 pending 高亮（port 放大蓝实心）；再点右侧节点 → 生成连线 + 「已配对清单」追加一条 `{id,quoteComponentId,quoteMetric,quoteLabel,costingComponentId,costingMetric,costingLabel,threshold:0}`。
- pending 后再点**同侧** → 更新 pending 端（允许改主意）。
- 一个节点**可连多条**（一对多允许）。

### 5.4 已配对清单
- 每行：`报价：页签·比对值 ↔ 核价：页签·比对值` + 阈值数字输入（默认 0）+ 删除✕。
- 悬停行 ↔ 高亮对应连线（双向）；点连线 → 定位/高亮对应行；删除行 → 同步移除连线。
- 清单区 `max-height:160px` 可滚动。

### 5.5 重绘时机
- 新增/删除配对、左右列各自滚动、窗口 resize、抽屉滑入动画结束（`transitionend`）→ 依 port `getBoundingClientRect()` 相对 SVG 重算重绘全部曲线。

### 5.6 确定 / 取消
- **确定**：清单为空 → toast「请先连线配置至少一对」并阻止关闭；非空 → 把每条配对按顺序（`sortOrder` 递增）转成 `ColumnDef(kind:'TAB_PAIR')` **追加到 columns 末尾** → 调 `PUT config` 保存 → 关闭 + 主表重渲染 + toast「已添加 N 个比对列」。
- **取消/关闭/点遮罩**：丢弃当前配对，关闭。
- 每次打开：重置配对清单/待连接态，按最新 meta 重建左右节点。

---

## 6. 旧代码处置（避免双源，AP-50 纪律）

- `ComparisonView.tsx`：改造为新 `ComparisonBoard`（或新建 `ComparisonBoard` 并让三处挂载改指向它，删除旧 `ComparisonView` 引用）。
- `ReadonlyComparison.tsx`：详情页改挂 `ComparisonBoard(readonly, frozen)`；旧只读比对逻辑删除或并入。
- `comparisonModel.ts` / `comparisonTable.tsx`（tag-based 双行模型）：本功能不再使用 → 移除引用；若无其它消费方可删除（先 grep 确认无其它 import）。
- `comparisonModel.test.ts`：随之更新/删除。
- **不改** `useLinkedExcelRows.ts`（仍服务于 LinkedExcelView 等），本功能不依赖它。
- **不动** 旧导出链路（`comparisonExportService` / 后端 export 端点），只是比对视图里不再有导出按钮。

---

## 7. 组件拆分建议（可调整，但保持单一职责）
- `ComparisonBoard.tsx`：容器（拉 meta/data/config、状态、组装 columns×rows）。
- `ComparisonTable.tsx`（重写或替换旧同名）：纯展示 3 行块表 + 着色 + 变灰 + rowSpan。
- `ComparisonToolbar.tsx`：新增比对 / 差异料号 / 过滤。
- `LinkConfigDrawer.tsx`：连线抽屉（两列节点树 + SVG + 已配对清单）。
- `comparisonMapping.ts`：纯函数——从 columns + data 映射出每料号每列取值、算 diff、判色、判差异料号、排序（**加单测**，对齐原型逻辑）。

---

## 8. 强制自检（完成前必跑，写"已自检"声明）

**基础（每个改动 tsx 必过）**：
1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
2. 对每个改动 tsx：`curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:5174/src/pages/quotation/<file>.tsx` → 200。
3. `curl ... http://localhost:5174/` → 200。

**协议级 E2E（强制，因改 `QuotationStep2.tsx`）**：
4. 跑 `e2e/quotation-flow.spec.ts`（见 `docs/E2E测试方法.md`），确认 `1 passed` + `'加载中' final count = 0`。
   > 注意历史涟漪：干净 master 上 `quotation-flow` 可能因共享 DB 夹具缺产品分类恒失败（见记忆 `task0712-update071501-category-axis`）；判回归须 **A/B 同型对比**（改动前后同环境跑），勿误归因。
5. 比对视图专项 E2E（建议新增或手工复测）：打开报价单编辑页 → 比对视图 Tab → 新增比对（连线一对）→ 列出现、取值/差异/着色正确 → 删列 → 改阈值 → 差异料号前置 → 分页；再切详情页确认只读（无新增/删除/⚙）。截图留证。

**三视图一致复测**：报价单编辑页（SALES 可配）/ 核价单页面（FINANCE 可配）/ 详情页（只读）三处，配置互不影响（AC 桶隔离）、取值一致。

**1:1 还原核验**：与 `prototype-比对视图.html` 逐屏对照（主表 3 行块、着色、变灰、连线抽屉三色/内侧 port、工具栏、分页）。

---

## 9. 验收标准（AC，技术总监据此核验）

- **AC-F1｜1:1 还原**：主表/工具栏/连线抽屉与原型逐项一致（3 行块、整格红橙、单边变灰、连线三色、核价 port 内侧、比对值"字段·小计"格式）。
- **AC-F2｜默认列**：产品卡片总计恒第一列、不可删、阈值可改；取值=后端 productTotal。
- **AC-F3｜连线增列**：连一对 → 追加末尾一列，取值/差异/着色正确；一对多可连；确定后持久化（刷新仍在）。
- **AC-F4｜删列/改阈值**：用户列可删；阈值改后着色实时变；均持久化（PUT）。
- **AC-F5｜着色规则**：diff<0 红优先、否则<阈值橙、否则无色；仅差异格；单边料号差异"—"。
- **AC-F6｜差异料号前置**：勾选后差异料号（含单边）稳定排序前置、非隐藏。
- **AC-F7｜过滤/分页**：料号子串过滤；按块分页默认 10、可调 10/20/50、不切断。
- **AC-F8｜桶隔离**：SALES 与 FINANCE 入口各自配置互不影响；详情页只读（无配置控件、不调 PUT）。
- **AC-F9｜无回归**：报价单/核价单既有 Tab、E2E `quotation-flow` A/B 对比无新增失败。

> ⚠️ 并发纪律：只 `git add` 本次改动文件，严禁 `git add -A`；worktree 内复用主仓 dev server(5174)，勿另起（见 `cpq-worktree-frontend-selfcheck`）。
