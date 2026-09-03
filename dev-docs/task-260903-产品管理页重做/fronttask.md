# 前端任务分解 · 产品管理页重做

> 任务：`task-260903-产品管理页重做`　|　日期：2026-09-03
> 阅读者：`cpq-frontend` 子代理。**只按本文件做**，不要读 `backtask.md`。
> AC 原文在 `需求文档.md §③`，**本文只标编号不复制原文**（复制必漂移）。

---

## 0. 开工前必须知道的三条边界

| # | 边界 | 后果 |
|---|---|---|
| 1 | 🚫 **不得修改 `cpq-frontend/src/pages/master-data/part-costing/` 下任何文件** | `task-260902` 的 F-1 正在把它抽成公共件，两边同时改**必冲突** |
| 2 | 🚫 **不得修改 `MasterDataHubPage.tsx`** | 对方在改它（加两个核价页签）。本任务与它无交集 |
| 3 | 🚫 **发现只读渲染缺陷不要自己修** | `EditableSheetTable` 的只读分支目前只在「历史版本」路径用过。发现 `role=NAME` 列或 `DECIMAL` 列只读态异常 → **停下报主线**，由主线转给 `task-260902`。见 F-6 |

---

## 1. 任务清单

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-1, AC-11, AC-16 | 重写 `ProductHubPage.tsx`：两个页签 `[客户产品][销售产品]`，默认选中客户产品；保留右上角「产品分类管理」按钮与其 Drawer；移除对 `InternalMaterialManagement` / `ProductManagement` 的 import 与页签注册（**两个组件文件保留不删**） |
| **F-2** | AC-2, AC-3, AC-13, AC-14, AC-15 | 新建「客户产品」页签组件 `ProductCustomerPartTab.tsx`：裸 `<Table>` + 服务端分页/搜索，6 列（见 AC-2）；**点行无任何反应**（不绑 `onRow.onClick`）；空态、超长文案省略 |
| **F-3** | AC-4, AC-5, AC-11, AC-13, AC-14, AC-15 | 新建「销售产品」页签组件 `ProductSalesPartTab.tsx`：复用 `task-260902` 的 `<SheetPartListTab>` 公共件，传 `basePath='/dataset/quote'`、`axisLabel='销售料号'`；7 列（见 AC-4）；点行开抽屉 |
| **F-4** | AC-5~AC-12 | 销售产品抽屉：复用 `<SheetPartDrawer>`，**传 `editable={false}`**；tab 数与顺序由 `GET sheets` 决定（🚫 **不写死 13**）；版本下拉保留但只读；空 tab 走 `Empty` |
| **F-5** | AC-2, AC-4, AC-14 | API 对接层 `productHubApi.ts`：用 `createSheetApi('/dataset/quote')` 得到销售侧全套；另封装 `listCustomerParts()` 打 `GET /dataset/quote/customer-parts`（契约见 `api.md §2`）。**`page` 传 0-based** |
| **F-6** | AC-8, AC-9 | 只读渲染验证：逐列核对 `role=NAME` / `DECIMAL` / `STRING` 三类列在 `editable={false}` 下的渲染；发现异常**只记录不修改**，产出《只读渲染缺陷清单》交主线 |
| **F-7** | AC-17 | 文档回写：`docs/列表操作规范.md §12` 白名单加两条；`docs/RECORD.md` 追加豁免说明 |
| **F-8** | AC-1, AC-2, AC-4, AC-5, AC-6, AC-12, AC-13, AC-15 | 原型 1:1 还原核对：逐屏比对 `原型图/` 七份，产出对照截图与偏差清单 |

---

## 2. 逐项实现要点

### F-1 · 壳页重写

- 文件：`cpq-frontend/src/pages/product/ProductHubPage.tsx`（**重写，不新建**——路由 `/products-hub` 不变，书签/直链/E2E 不挂）
- 保留：页面标题 `产品管理`、`产品分类管理` 按钮 + `<Drawer width={960}>` + `<ProductCategoryManagement />`
  > 🚫 **不许顺手删产品分类管理** —— `product_category` 被 `customer.product_category_id` 引用，报价/核价/选配三套模板按客户产品分类匹配（`task-0712`）
- `<Tabs>` 保留 `destroyInactiveTabPane`（避免两个列表同时挂载重复请求）
- 页签 key 建议 `customer` / `sales`，**默认 `customer`**

### F-2 · 客户产品页签

- 文件：`cpq-frontend/src/pages/product/ProductCustomerPartTab.tsx`（新建）
- 列（顺序即 AC-2）：`客户编号` `客户名称` `客户料号名称` `客户产品编号` `客户图号` `销售料号`
- **`客户名称` 为空时渲染 `—`**，🚫 不许渲染空白或 `undefined`
- 🚫 **不绑行点击**：`<Table>` 不传 `onRow`，行不加 `cursor:pointer`，视觉上就不该暗示可点（AC-3）
- 工具栏：只有 `搜索框` + `刷新` 按钮。🚫 **无新增/编辑/删除/导入**（②范围明确不做）
- 属**列表操作规范例外白名单**（纯查看无批量动作）⇒ 用裸 `<Table>` 不用 `SelectableTable`；工具栏须自套 `TOOLBAR_ROW_STYLE`（`listConventions.ts`）

### F-3 · 销售产品页签

- 文件：`cpq-frontend/src/pages/product/ProductSalesPartTab.tsx`（新建）
- **优先复用** `task-260902` F-1 抽出的 `<SheetPartListTab>`。若合并时该公共件尚未就绪：**照 `PartCostingTab.tsx` 的结构新写一份，但不得 import 或修改 `part-costing/` 下的文件**，待公共件到位后再切换
- 列（顺序即 AC-4）：`销售料号` `品名` `规格` `尺寸` `旧料号` `单重` `生产料号`
  > ⚠️ `生产料号` 依赖后端补 `productionNo`（`api.md §2 缺口2`）。字段缺失时该列渲染 `—`，**不得因缺字段而崩溃或整列不渲染**
- `单重` 是数值：**后端以字符串回传保留 scale**，🚫 禁止 `Number()` 后再格式化（丢精度）。用 `utils/precision.ts` 的 `formatDisplayDecimal`
- 行可点击（`cursor:pointer`），点击开抽屉

### F-4 · 销售产品抽屉（本任务的核心）

- **结构**：右侧 `<Drawer>`，`<Tabs tabPosition="left">`，每 tab 内 = 版本下拉 + 平铺表格。**是平铺不是树**
- **只读实现**：
  - 传 `editable={false}` 给 `EditableSheetTable`
  - Drawer 内**不渲染** `保存` / `新增行` / `删除` 按钮（AC-8）——不是禁用，是**不渲染**
    > ⚠️ 此处是 `frontend.md §1.2`「禁止 `if(...) return null` 隐藏按钮」的**合理例外**：那条针对的是「本可用但当前不可用」的动作，需要让用户知道能力存在；本页是**整页无编辑能力**，渲染一排永久禁用的保存按钮反而误导。**此例外须在闸门 A 呈报时点名**
  - 🚨 **无论 `rows` 响应里的 `readOnly` 字段为何值，一律只读**（AC-8 备注）
- **tab 数不写死**：来源 `GET /dataset/quote/sheets`。AC-6 断言 13 个是**当前数据下的期望值**，不是硬编码依据
- **列渲染按 `ColumnDef.type`**（`STRING`/`NUMBER`/`DECIMAL`）决定对齐与格式化：
  🚨 **`pricing_unit`（计价单位）已由 `DECIMAL` 修正为 `STRING`，不得当数字格式化、不得右对齐**（`task-260902` 变动 B）
  🚫 **禁止按列名硬编码判断类型** —— 对方刚修了 50 列类型，硬编码必然过时
- `role=AXIS` 的列**隐藏**（轴值已在 Drawer 标题上），与核价侧一致（AC-7）
- 抽屉标题：`销售产品 · {axisValue}`，`axisValue` 必须原样显示（AC-5 断言标题含 `S-3120014539`）
- **每次打开抽屉重置到第一个 tab**（AC-11 步骤⑦），不保留上次停留位置

### F-5 · API 对接层

- 文件：`cpq-frontend/src/pages/product/productHubApi.ts`（新建）
- 🚨 **`page` 是 0-based**（`api.md §1` 消费方硬约束 1）。antd `Table` 的 `current` 是 1-based ⇒ **必须 `current - 1`**，否则首页取到第二页
- 🚫 **不实现** `import` / `saveRows` / `lookup` 三个函数——本页不调（`api.md §3`）。写了就是超范围
- 端点 `GET customer-parts` 若后端尚未就绪：**用 mock 数据自测**，mock 形状严格对齐 `api.md §2`，并在回报里注明「F-2 走 mock，未接真实端点」

### F-6 · 只读渲染验证（**只记录，不修改**）

逐项核对并产出清单，每项写明「列名 / `role` / `type` / 期望 / 实际」：

| 核对项 | 期望 |
|---|---|
| `role=NAME` 的 JOIN 展示列 | 正常显示名称文本，不显示 `undefined` / 不显示空白 |
| `type=DECIMAL` 的列 | 保留库中 scale，不做 JS 数值转换 |
| `type=STRING` 但值像数字的列（如 `pricing_unit`） | **左对齐、原样显示**，不被格式化成数字 |
| 值为 `null` 的单元格 | 显示 `—` |
| 空 `rows` 数组 | `Empty` 空态，不是 `加载中…` |

🚫 **发现问题不要动 `part-costing/`**，写进清单交主线（边界 3）。

### F-7 · 文档回写

- `docs/列表操作规范.md` §12 白名单追加两条（文案见 AC-17）
- `docs/RECORD.md` 追加豁免说明一行

### F-8 · 原型 1:1 还原

- 逐屏比对 `原型图/` 七份与实现，**产出对照截图**
- 允许的偏差**只有一类**：组件库能力所限的等价实现。偏差逐条列进回报
- 🚫 原型没画到的地方不许自由发挥 → 停下来问主线（`frontend.md §1.3` 还原纪律）

---

## 3. 双向覆盖检查（闸门 A 自检用）

### 正向：每条 AC 都有人认领

| AC | 认领者 | | AC | 认领者 |
|---|---|---|---|---|
| AC-1 | F-1, F-8 | | AC-10 | F-4 |
| AC-2 | F-2, F-5, F-8 | | AC-11 | F-1, F-3, F-4 |
| AC-3 | F-2 | | AC-12 | F-4, F-8 |
| AC-4 | F-3, F-5, F-8 | | AC-13 | F-2, F-3, F-8 |
| AC-5 | F-3, F-4, F-8 | | AC-14 | F-2, F-3, F-5 |
| AC-6 | F-4, F-8 | | AC-15 | F-2, F-3, F-8 |
| AC-7 | F-4 | | AC-16 | F-1 |
| AC-8 | F-4, F-6 | | AC-17 | F-7 |
| AC-9 | F-4, F-6 | | | |

✅ **17 条 AC 全部被认领，无交付缺口。**

### 反向：每个 F-x 都指得回 AC

`F-1`→AC-1/11/16 · `F-2`→AC-2/3/13/14/15 · `F-3`→AC-4/5/11/13/14/15 · `F-4`→AC-5~12 · `F-5`→AC-2/4/14 · `F-6`→AC-8/9 · `F-7`→AC-17 · `F-8`→AC-1/2/4/5/6/12/13/15

✅ **8 个 F-x 全部指得回 AC，无超范围。**

---

## 4. 前端强制自检（`frontend.md §2`，完成宣告必须附这一行）

```
TS 0 错误 ✅；ProductHubPage / ProductCustomerPartTab / ProductSalesPartTab → dev server 200 ✅；
浏览器 console 0 error ✅；原型 7 屏逐项比对完成、偏差 N 条已列 ✅
```

⚠️ **共享 dev server 是全会话共用的**：先探端口，已在跑就复用，不要重复起。
worktree 内自检需软链 `node_modules` + 另起临时端口（见记忆条目「worktree前端自检坑」）。

🚫 **「已自检」≠「亲验」** —— 自检答「代码能跑吗」，亲验答「功能对吗」。亲验由主线做，不要代劳，也不要声称已完成。
