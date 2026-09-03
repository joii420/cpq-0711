# 接口契约 · 产品管理页重做

> 任务：`task-260903-产品管理页重做`　|　日期：2026-09-03
> **契约主源**：`../task-260902-报价与核价建表与导入方案新规范/api.md`（已冻结）。
> 本文只写**本任务消费什么、以及需要主源补什么**，🚫 不复制主源契约原文（复制必漂移）。

---

## 0. 本任务的接口立场

| 项 | 结论 |
|---|---|
| 新建业务端点 | **0 个**（前提：§2 的两处扩展由 `task-260902` 承接） |
| 修改既有端点 | **0 个** |
| 调用写端点 | **0 个** —— 本页纯只读，**不调** `POST import`、**不调** `PUT rows` |
| 契约所有权 | 全部归 `task-260902`。本任务**无权改** `api.md`，有疑问走跨会话沟通 |

> 🚫 **为什么不自建后端**：`报价侧交接说明.md` 明写「不要为报价侧另写一套后端，那会产生第二条升版路径」。
> 本任务连写端点都不调，更不应自建。**唯一例外见 §2 退路条款。**

---

## 1. 本任务消费的端点（全部来自主源，`{dataset}` = `quote`）

| # | 端点 | 用途 | 服务的 AC |
|---|---|---|---|
| C-1 | `GET /dataset/quote/parts` | 「销售产品」页签列表数据源 | AC-4, AC-14, AC-15 |
| C-2 | `GET /dataset/quote/sheets` | 抽屉 tab 清单与顺序（**tab 数由本接口决定，前端不写死**） | AC-6 |
| C-3 | `GET /dataset/quote/parts/{axisValue}/overview` | 抽屉各 tab 的行数徽标 / 是否从未有数据 | AC-6, AC-12 |
| C-4 | `GET /dataset/quote/parts/{axisValue}/sheets/{sheetKey}/rows` | 抽屉某 tab 的行数据与列元数据 | AC-7, AC-8, AC-10 |
| C-5 | `GET /dataset/quote/parts/{axisValue}/sheets/{sheetKey}/versions` | 版本下拉 | AC-10 |
| C-6 | **`GET /dataset/quote/customer-parts`** ⚠️ **待主源新增** | 「客户产品」页签列表数据源 | AC-2, AC-3, AC-14 |

### 消费方硬约束（写进 `fronttask.md` 逐条落实）

1. **分页 `page` 是 0-based** —— 主源 §3 明确。antd `Table` 的 `current` 是 1-based，**必须减 1**，否则首页取到第二页。
2. **数值以字符串回传**，保留库中 scale。🚫 **禁止 `Number()` 转换后再格式化**，会丢精度（与既有 `part-costing` 一致）。
3. **列渲染按 `ColumnDef.type`（`STRING`/`NUMBER`/`DECIMAL`），🚫 不得按列名硬编码。**
   `task-260902` 已修正 50 列类型（31 列方向错），典型如 `pricing_unit` 由 `DECIMAL` 改为 `STRING`。
   🚨 **`pricing_unit` 不得当数字格式化 / 不得右对齐。**
4. **`role=AXIS` 的列在抽屉内隐藏**（轴值已在抽屉标题上），与核价侧一致。
5. **`role=NAME` 的列只读且不回传** —— 本任务全只读，天然满足；但渲染时要能正确显示 JOIN 出来的名称列。
6. **`versionNo` 为 `null`** = 该 sheet 该轴值从未有过数据 → tab 不打徽标，进去是空态（AC-12）。
7. **`readOnly` 字段**：主源在请求历史版本时回 `readOnly=true`。本任务**无论该字段为何值，一律按只读渲染**，不得据其推导出可编辑分支。

---

## 2. ⚠️ 需要主源补齐的两处（已于 2026-09-03 提出，待对方确认）

### 缺口 1（阻塞级）：`ds_quote_customer_part` 无任何读端点

**事实**：8 个端点中，`GET parts` 读物料表、`GET lookup/{masterType}` 读的是现有 `customer` / `process` / `element` / `material_recipe` **主数据表**，均非 `ds_quote_customer_part`。免版本表按主源 R-2「不进抽屉 tab」⇒ 客户料号表在新体系里**没有查看入口**。

**建议形状**（对齐主源 `GET parts`，仍 `{dataset}` 参数化）：

```
GET /dataset/{dataset}/customer-parts
Query : page(0-based) / size / keyword / sortBy / sortDir
200   : { code:200, message:"success", data:{
            total: 17,
            columns:[ {name,label,type} ],          // 只投影三键，不下发 ColumnDef
            items:[ { customerNo, customerName, customerPartName,
                      customerProductNo, customerDrawingNo, materialNo } ] } }
```

- `customerName` **必须由后端 JOIN `customer` 表**得出：`ds_quote_customer_part` 只有 `customer_no`，且主源第一轮已提醒「客户名现网大量为空，要走 customer 表 JOIN 或兜底」。仅显示 `CUST-0004` 这类编号对业务不可用。

  🚨 **JOIN 列名实测确认（写错直接报错）**：`customer` 表**没有 `customer_no` 列**，客户编号列名是 **`code`**。
  ```sql
  LEFT JOIN customer c ON c.code = t.customer_no
  ```
  ⚠️ 必须是 **LEFT JOIN**：实测 17 行里 **3 行 JOIN 不到**（`Q13CUST0617` 2 行、`C1` 1 行未在客户档案登记），
  用 INNER JOIN 会**静默丢掉这 3 行**，列表总数从 17 变 14。命中情况实测：

  | 客户编号 | 客户名称 | 行数 |
  |---|---|---|
  | `CUST-0004` | 正泰 | 11 |
  | `CUST-0001` | 罗克韦尔 | 2 |
  | `CUST-0002` | 测试客户 | 1 |
  | `Q13CUST0617` | **JOIN 不到** | 2 |
  | `C1` | **JOIN 不到** | 1 |

  > JOIN 不到时回 `null`，前端渲染 `—`（AC-2）。这不是缺陷，是现网真实状态。
- `keyword` **严格匹配** `customer_no` / `customer_product_no` / `material_no` **三列**（AC-14 要按 `CUST-0004` 搜出 **11** 行）。
  ⚠️ 日后若有人「顺手」把 `customer_part_name` 或 `c.name` 加进匹配范围，行数会变而**测试不一定挂** —— 后端代理留记，此处固化为契约。

> 🚩 **2026-09-03 更正（后端代理实测抛回）**：本节示例原写 `{ success:true, ... }`，**与项目真实响应信封不符**。
> `ApiResponse.java` **没有 `success` 字段**，真实信封是 `{code, message, data}`。前端 `productHubApi.ts` 已按「只看有无 `data` 键」解包，绝不读 `success`。
> ⚠️ 主源 `task-260902` 的 `api.md` 同样写着 `success:true` —— **那是文档笔误，不是契约**。下一个照抄的人还会撞，已向对方提出。

### 缺口 2（次要）：`GET parts` 响应缺 `productionNo`

`ds_quote_material` 建了 7 列，主源 §3 响应体只有 `axisValue / materialName / specification / dimension / oldMaterialNo / unitWeight`，**少 `production_no`**。AC-4 要求 7 列全展示 ⇒ 请在 `items` 补 `productionNo`。

### 退路条款（未获对方承接时启用）

若 `task-260902` 明确本期不接，则**由本任务新建两个只读查询端点**，并满足全部三条：

1. 🚫 只读，不含任何写操作，不触碰 `VersionedGroupWriter` 与升版路径
2. 路径与主源风格一致，`{dataset}` 参数化，便于日后并入主源
3. **需用户在闸门 A 明确批准**（这构成对交接说明「不要另写后端」边界的一次例外）

> ⚠️ 未获确认前，`fronttask.md` 的 F-2 按 C-6 契约写对接层并用 mock 自测，**不会被这一决策阻塞**。

---

## 3. 不涉及的端点（写明「为什么不调」，防后续会话误加）

| 端点 | 为什么不调 |
|---|---|
| `POST /dataset/quote/import` | 本页无导入入口（②范围明确不做）。导入留在报价单管理的「导入报价数据」按钮 |
| `PUT /dataset/quote/parts/.../rows` | 本页纯只读。**调它就产生了第二条升版路径**，是交接说明点名禁止的事 |
| `GET /dataset/quote/lookup/{masterType}` | 该端点服务于**可编辑表格的下拉候选**。本页无编辑态 ⇒ 无下拉 ⇒ 不需要 |
| `/api/cpq/products*`（现 `ProductResource`） | `product` 表页签被本任务摘除 UI 入口，但**端点与表保留**（三张表外键引用）。本任务不调、不改、不删 |
| `/api/cpq/material-masters*` | 同上，`material_master` 的 CRUD 端点保留不动 |
| `/pricing-basic-data/*` | 核价侧既有端点，本任务一个字节都不碰（主源 AC-43） |

---

## 4. `main-api.md` 回写

| 情况 | 回写动作 |
|---|---|
| §2 两处由主源承接 | **本任务无契约变更，不回写**；由 `task-260902` 随其自身端点一并回写 |
| §2 走退路条款（本任务自建） | **必须回写** `dev-docs/main-api.md`，两个新端点各起一节 + 来源标记 `> 来源任务：task-260903-产品管理页重做｜回写日期：<实取日期>` |

> 按 `task-docs.md §2.5`：回写时机 = 测试完成后、合并 master 之前。未回写不得进入合并环节。

---

## 5. 开工后的契约更正与情报（2026-09-03，来自 `task-260902` 主动通知 + 主线实测）

> 按 `task-docs.md §4`「开工后 AC/契约变更」记录。**本节全部是情报与更正，未改动任何 AC。**

### 5.1 ⚠️ 契约措辞不准 —— `sheets` 返回体并不对齐旧 `SheetMeta`

主源 `api.md §2` 原写「结构完全对齐现有 `SheetMeta`」，**实测不成立**，三个字段名都不同：

| 新接口 | 旧 `part-costing/types.ts` 的 `SheetMeta` |
|---|---|
| `sheetName` | `tabName` |
| `sortOrder` | `order` |
| `masterType` | `master` |

🚫 **不要把新接口返回直接喂给旧类型**，也不要 import `part-costing/types.ts` 的类型定义。
✅ 在 `productHubApi.ts` 里自建类型 + 一层收敛映射（对方在 `dataset/types.ts` 的 `toColumnDefs()` 里做了同样的事）。
> 对方已表示会更正主源措辞；在其更正落地前，**以本节为准**。

### 5.2 ✅ 建表迁移已合 master，共享库表已就绪

`cfa5e5bc` 已将 `V405`~`V408` 合入 master，本分支已 `git merge master` 同步。**主线实测确认**：

```
ds_ 表数 = 84
flyway_schema_history: 405 / 406 / 407 / 408 全部 success = t
ds_quote_material 当前行数 = 0
```

🚫 **这 4 个迁移文件的 checksum 已锁死，任何人不得改动一个字节。** 只读，不要动。

> 背景：这 4 个文件曾被对方测试代理的 `@QuarkusTest` 意外应用进共享库（`test` profile 实连 `cpq_db_0724` 且 `migrate-at-start`），造成「迁移进库、文件没进 master」的失配，用户裁决单独合并修复。**这也正好满足了本任务「提前拿到建表」的请求。**

### 5.3 ⚠️ 后端 8 个端点**仍未合并** —— 前端继续 mock

表建好了 ≠ 端点能调。`com.cpq.dataset` 包仍在对方 worktree 未提交状态。
⇒ F-2/F-3/F-5 继续用 mock 自测；接真实端点做亲验**仍须等对方合并**。

### 5.4 🚨 共享库并发风险（新增，写入 `test.md` 同步执行）

`ds_quote_*` 现在是**两个任务共用**：对方五路子代理正在同一批表上跑 `@QuarkusTest`。

- 🚫 **绝对不许跑清表 / 清库型测试**（`CLAUDE.md §3.2` 环境销毁红线）
- 🚫 **不许写死绝对行数断言**（「表里就该是 42 行」）⇒ 改为**相对不变量**（「导入后 = 导入前 + 42」或按料号过滤后计数）
- ⚠️ 灌样例数据前**必须由主线与对方协调窗口**，子代理不得自行往共享库写

> 同源教训：对方立项时实测共享库数据在漂移（同一条 `count(*)` 几分钟内材质 263→259），最后把 AC 全改写成「与同一时刻基准查询相等」的不变量。

### 5.5 §2 两个缺口的最新状态：**仍未闭合**

| 缺口 | 状态 |
|---|---|
| `ds_quote_customer_part` 无读端点 | ⏸ 待对方答复。**新论据**：对方为电镀方案加的 `GET /{dataset}/plating-schemes` + `DsPlatingSchemes` DTO，其类注释原文就是「补的是**免版本表在新体系里没有查看入口**的缺口」——报价侧 3 张免版本表它补了 2 张（物料走 `GET parts`、电镀方案走新端点），**客户料号这张漏了**。已把 `DsPlatingSchemes` 作为现成模板递过去 |
| `GET parts` 缺 `productionNo` | ⏸ 待对方答复。主线 grep 复核：`production_no` 只在 `CostBasicRegistry`（核价轴）命中，**报价侧响应体确实没有** |

⇒ 两条不补，**AC-2 / AC-4 达不成**（断言的是完整列）。前端已做「缺字段渲染 `—` 不崩」兜底，不阻塞开发，但阻塞验收。
