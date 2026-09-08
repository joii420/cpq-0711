# 前端任务分解 · task-260908-取数配置器优化

> 📐 **F-1 / F-2 有原型图，见 `原型图/index.html`（📌 当前基准）。定稿后 1:1 还原**（`frontend.md §1.3`）。
> F-3 是纯逻辑（默认值）、F-4 是纯文案替换，两者不触发原型（同 §1.3「不触发」列）。

---

## F-1 · 组件绑定区按数据源条件显示

**服务的 AC**：AC-10, AC-11, AC-12, AC-13, AC-25
**文件**：`cpq-frontend/src/pages/component/ComponentManagement.tsx`（绑定区在 `:1962~2110`）
**原型**：`原型图/01-组件绑定区.html`

### 现状

绑定区**固定**渲染 5 个下拉：料号列（`:1986`）· 名称列（`:1997`）· 元素列（`:2016`）· 元素单价列（`:2030`）· 货币列（`:2044`），另有一个「按 SQL 推导元素绑定」按钮（`:2051`）。

### 要改成

| 条件 | 显示 |
|---|---|
| `shouldShowElementBinding === true` | 料号列 · 名称列 · **元素列** · **元素单价列** |
| 否则 | 料号列 · 名称列 |
| **任何情况** | 🚫 **不渲染货币列** |

### 🚨 判据必须是两条的「或」，不是只看 semantic

```ts
const shouldShowElementBinding =
  boundSemantic === 'MATERIAL_ELEMENT'
  || !!elementCodeField || !!elementPriceField;   // ← 这一半不能省
```

**为什么第二个条件不能省**（AC-13）：`boundSemantic` 是 `BoundTabSemantic` 四态（见 `utils/tabSemantic.ts` 文件头注释），`undefined` = **未绑定数据源的存量组件**。只按 `semantic === 'MATERIAL_ELEMENT'` 一刀切，会把这类组件上**已生效的元素配置藏起来** —— 变成看不见但仍在参与计算的僵尸值，比显示出来危险得多。

⚠️ **这条在现网数据上验不出来，别拿现网组件自测。** 2026-09-08 实测：全库仅 2 个组件配了元素绑定（`dc3297cc-…`、`196aadee-…`），而**两个都有 `builder_config` 且 `tabType='材质元素'`** ⇒ `boundSemantic` 恒为 `'MATERIAL_ELEMENT'`，两种判据写法都会绿。
⇒ 自测时**必须自己造一个** `builder_config` 为空、但 `element_code_field` 非空的组件（AC-13 已写明造法与阳性对照）。

### 货币列的处置边界（`D-4`，务必按此执行）

- ✅ **只删渲染**：把 `:2041~2050` 的 `Tooltip` + `Select` 整块移除
- 🚫 **不许**改 `CreateComponentRequest.elementCurrencyField` / `ComponentDTO` / 实体 / 任何后端调用
- 🚫 **不许**在提交时把 `elementCurrencyField` 置空或不传导致后端清空 —— `ComponentService:801` 的写法是 `if (request.elementCurrencyField != null) ...`，**传 `null` 才是安全的（不动存量值），传空串会清空**。改完请自查提交 payload
- ✅ 存量那 1 个有值的组件，其值必须原样保留（AC-12 反向断言会核对 `count(*) = 1`）
- 「按 SQL 推导元素绑定」按钮（`:2051`）：跟随 `shouldShowElementBinding` 一起显示/隐藏（它推导的就是这几个字段）

---

## F-2 · 切数据源自动带出行键列 + 不可移除

**服务的 AC**：AC-14, AC-15, AC-16
**文件**：`cpq-frontend/src/pages/component/SqlViewBuilderTab.tsx`
**原型**：`原型图/02-取数配置器-已选输出列.html`

### 落点

| 行为 | 落在哪 |
|---|---|
| 选中/切换数据源后自动填充 | `handleSourceChange`（`:800`）以及**字段树加载完成**之后 —— 注意两者是异步的，`handleSourceChange` 触发时 `fieldTree` 还是旧的/空的 |
| 自动带出哪些列 | 字段树中所有 `roles` 含 `'ROW_KEY'` 的列，走既有 `addColumn`（`:581`）保证 `_uid`/`viewColumn` 等字段构造一致 |
| 禁止移除 | `removeColumn`（`:601`）对行键列直接 return；渲染层 `renderSelRowBody` 的 ✕（`:1136` 附近 `<span className="svb-rm">`）置禁用态 |

### 三条必须守住的细节

1. **`ROW_KEY` 判据用 `s.roles.includes('ROW_KEY')`，不要用字段名或下标。** 角色来自服务端字段树声明（`ROLE_LABEL` 在 `:143`），是唯一权威。
2. **切源要「替换」不是「追加」**（AC-16）：切换数据源本来就会重置已选输出列（列属于源）。请确认自动填充发生在**重置之后**，否则往返切换会堆积成 4 个行键列。
3. **✕ 用禁用态而不是不渲染**（`frontend.md §1.2`：禁用但可见 + 说明原因）。hover 文案要说明**为什么**，例如：「行键列决定行的身份（删行、回填、保存都靠它匹配），不可移除」。
   🚫 不要只置灰不给原因 —— 用户会以为是 bug。

### ⚠️ 与「价格策略原子组」的交互

已选输出列里有一块 `svb-pgrp` 原子组（`:1150~1180`），整组拖动、整组移除。若某个行键列恰好落进该组（`s.raw || s.autoElem`），移除整组的 ✕（`:1177`）也必须挡住。**请实测一次材质元素数据源**，那是唯一会出现价格策略组的源。

---

## F-3 · 料号列拖入后的默认字段名

**服务的 AC**：AC-17, AC-18, AC-19
**文件**：`cpq-frontend/src/pages/component/SqlViewBuilderTab.tsx`
**落点**：`:191` 的 `fieldName: col.displayName`（`addColumn` 构造 `SelColumn` 时的默认字段名来源）

### 规则

```
若 该列 roles 含 'PART_NO'：
    报价方言(QUOTE)：displayName === '销售料号' → 保持原名；否则 → '料号'
    核价方言(COST_*)：displayName === '生产料号' → 保持原名；否则 → '料号'
否则：保持 col.displayName（现状不变）
```

实测各方言下会被改名的料号列（**这是全集，照此自测**）：

| 方言 | 保持原名 | 改成「料号」 |
|---|---|---|
| QUOTE | 销售料号（6 个源） | 投入料号（6 个源）· 材质料号（1）· 组成件料号（1） |
| COST_BASIC | 生产料号（5 个源） | 组成料号（1）· 材质料号（1）· 来料料号（3） |
| COST_DETAIL | 生产料号（13 个源） | 组成料号（1）· 材质料号（1）· 来料料号（3） |

### 两条反向断言

1. 🚫 **左侧「可用字段」面板的显示名不变**（`D-8`）—— 仍显示「投入料号」「组成料号」。只有拖入右侧后的**字段名输入框**变成「料号」。
2. ✅ **改的是默认值，不是锁定** —— 用户仍可在字段名输入框里手动改成别的（`renameColumn` `:616` 行为不变）。

### ⚠️ 方言判据

用当前 `dataset` state（`DATASETS` 的 key）判方言，不要用 `displayName` 反推。「销售料号 / 生产料号」这两个字面量来自语义图 `display_name`，是 DB 数据 —— 若担心它漂移，可改判 `sourceColumn`（`material_no` / `production_no`），但**两种判法只能选一种并在代码注释里写明选了哪种、为什么**。

---

## F-4 · BOM 树列表头「料号」→「BOM」

**服务的 AC**：AC-20, AC-21, AC-22

**两个文件都要改，缺一不可**：

| 文件 | 行 | 现状 |
|---|---|---|
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | `:3285` | `<th style={{ width: 1, whiteSpace: 'nowrap' }}>料号</th>` |
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | `:626` | `<th style={{ width: 130, minWidth: 120 }}>料号</th>` |

**只改文案，🚫 不动样式、不动条件、不动紧随其后的 `cardSide === 'COSTING'` / `isCosting` 版本列分支。**

### 🚨 为什么这条单独标红

这两处是**编辑页与详情页的同一个表头**，且**报价侧与核价侧共用**（`D-6` 用户明确要求两侧都改）。`ReadonlyProductCard.tsx:617-620` 的既有注释记录了一次同型事故：

> repair-0814：报价树无版本切换语义，不出版本列（业务裁决 2026-07-22，提交 7fadf5e8 **当时只改了编辑页，本只读页漏改 → 详情页多出一列，AP-50 同族**）

改完请**两个页面各截一张图**放进 `证据/`，不要只验编辑页。

---

## 前端自检（交付前必跑，输出贴进回报）

按 `frontend.md` 的强制自检 + `CLAUDE.md §6.1`：

1. `npx tsc --noEmit` → **0 错误**（在 worktree 内跑，不要跑主仓）
2. 四个被改文件各自能在 dev server 渲染，**无红色遮罩、控制台无未捕获异常**
3. AC-13 / AC-25 必须用**存量组件**实测（`builder_config` 为空的那种），不能只测新建的
4. F-4 的两个页面各截图一张
5. 「完成」宣告带上自检声明行

## 🚫 越界纪律

- 🚫 不许改 `cpq-backend/` 下任何文件。发现后端缺东西 → 报主线，不要自己加接口
- 🚫 不许改 `utils/tabSemantic.ts` 的判据逻辑（它是 5 处语义闸门的唯一实现，`task-260904 F-8` 刚收敛过）。只**调用**它
- 🚫 不许顺手「优化」绑定区的其他部分（料号列/名称列的校验、`identityRequired` 逻辑）—— 那是 `task-260904` 的交付物，本次不在范围内
- 🚫 不许改 `component` 相关的任何请求体字段名或提交时机
