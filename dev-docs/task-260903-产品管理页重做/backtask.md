# 后端任务分解 · 产品管理页重做

> 任务：`task-260903-产品管理页重做`　|　日期：2026-09-03
> 阅读者：`cpq-backend` 子代理。**只按本文件做**，不要读 `fronttask.md`。

---

## 0. 结论先行：本任务后端**默认零改动**

**没有 `B-x` 条目**，这不是漏写。下面写清「为什么不改」与「什么条件下才改」。

| 项 | 结论 |
|---|---|
| 新建表 / 迁移 | **0** —— 16 张 `ds_quote_*` 由 `task-260902` 交付 |
| 新建端点 | **0** —— 复用其 8 个 `{dataset}` 参数化端点 |
| 修改既有端点 | **0** |
| 修改既有实体 / Repository / Service | **0** |

---

## 1. 为什么不改（逐条判定依据）

| 可能想改的地方 | 为什么不改 |
|---|---|
| 建 `ds_quote_*` 表 | 已由 `task-260902` 的迁移交付并在真库事务内验证（84 表 / 1438 列 / 唯一约束生效，验完 ROLLBACK 零残留）。**重复建表 = 迁移撞号 + 双写** |
| 写报价侧读端点 | 8 个端点已 `{dataset}` 参数化，`quote` 是合法值，开箱即用 |
| 写报价侧保存端点 | 🚫 **本页纯只读**。写了就产生第二条升版路径，是 `报价侧交接说明.md` 点名禁止的事 |
| 改 `ProductResource` / `product` 表 | 页签虽摘除，但 `product` 被 `quotation_line_item` / `product_template_binding` / `product_process` **三张表外键引用**。删表即断链 |
| 改 `MaterialMasterResource` / `material_master` 表 | 该表仍被 V6 导入链路写入、被组件 SQL 视图读取。**摘 UI 入口 ≠ 停用后端** |
| 清理 `material_master` 的 1847 行 pending 影子行 | 🚨 **§3.2 不可逆操作红线**。且 `docs/BACKLOG.md` 已有专项条目「长期未提交报价单的 pending 影子行定期回收机制」，归那条,不归本任务 |
| 改 `Q01~Q19` / `P01~P24` / `PricingSheetRegistry` | 主源 AC-43 明令不改；改则 107 个组件 SQL 视图断供 |
| 给 `ds_quote_*` 加索引或改列 | 交接说明第 4 节第 3 条：**有需要找主源走迁移**，不得自行加 |

---

## 2. ⚠️ 条件性任务（仅当退路条款启用时才做）

## ✅ 2026-09-03：退路条款**已启用**，B-1 转为正式任务；B-2 已由对方承接

| 启用条件 | 状态 |
|---|---|
| `task-260902` 明确答复本期不承接 | ✅ **成立** —— 对方将本任务的完整论证呈报用户，用户裁决「客户料号端点仍归 `task-260903` 自建」。这是范围归属裁决，非对方驳回 |
| 用户明确批准本任务自建只读端点 | ✅ **成立** —— 主线于 2026-09-03 直接向用户确认（🚫 未采信对方转述：`peer` 的转述不能当作本会话用户的批准） |

**⇒ B-1 转为正式任务，必须实现。**

**⇒ B-2 取消，不要做。** 对方核实后确认这是**它的实现漏了跟上契约**（`api.md §3` 已有 `productionNo`，但后端 `DsPartsPage.Item` 是改契约之前写的），已派其后端 #3 补。
> ⚠️ **三数据集差异**（对方提醒，写渲染逻辑时注意）：`dataset=quote` 时 `productionNo` 有值；`cost-basic` / `cost-detail` 的物料表**没有这一列**（它们的轴本身就是生产料号），响应里该字段**整个省略** —— 🚫 不要写成「期望 null」的渲染逻辑。

### B-1 的补充设计依据（对方提供，照此实现可日后平滑并入主源）

对方为**同类问题**已实现 `GET /{dataset}/plating-schemes` + `DsPlatingSchemes` DTO，其类注释原文即
「**补的是「免版本表在新体系里没有查看入口」的缺口**……只读，没有配套写端点」。
报价侧 3 张免版本表它覆盖了 2 张（物料走 `GET parts`、电镀方案走新端点），**客户料号这张是剩余的第 3 张**。

⇒ **照 `DsPlatingSchemes` 的形状写 `DsCustomerParts`**，三条设计要点对方已验证、本任务完全认同：
1. `columns` **按数据集下发**，前端不写死
2. 🚫 **不直接下发 `ColumnDef`** —— 那会带上 `editable` / `required` / `compared`，而本页只读，下发 `editable=true` 会误导前端渲染出编辑态。只投影 `{name, label, type}` 三个键（唯一真源仍是 Registry 的 `SheetDef.columns`）
3. 只读，**无配套写端点**

⚠️ **两张名字极像的表，别搞混**（对方实测）：
- `customer_material_mapping` —— **1 行**，几乎空，挂着一套**零引用的死代码**端点（`CustomerMaterialMappingResource` + `CustomerMaterialMappingTab.tsx`）
- `material_customer_map` —— **1877 行**，真表，被 20 个 `component_sql_view` 引用

🚫 **B-1 读的是新体系的 `ds_quote_customer_part`，与上面两张旧表都无关。**

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| B-1 | AC-2, AC-3, AC-14 | 新建 `GET /dataset/{dataset}/customer-parts` 只读列表端点：分页（`page` **0-based**）/ `keyword` / 排序；响应 `{ total, items[] }`，字段见 `api.md §2 缺口1`。**`customerName` 必须 LEFT JOIN `customer` 表**，JOIN 不到回 `null`（前端渲染 `—`） |
| B-2 | AC-4 | `GET /dataset/{dataset}/parts` 响应 `items` 补 `productionNo` 字段（取 `ds_quote_material.production_no`） |

### B-1 / B-2 的硬约束

1. 🚫 **只读**：不得包含 INSERT / UPDATE / DELETE，不得引用 `VersionedGroupWriter`
2. 🚫 **不得改动主源已有的任何端点实现**；B-2 若无法在不动主源代码的前提下完成，**停下来报主线**，不要绕道
3. ✅ **N+1 硬指标**（`backend.md`）：列表端点的 SQL 条数必须与返回行数无关。`customerName` 用 **JOIN 一次取回**，🚫 禁止逐行查 `customer`
4. ✅ 分页必须服务端做，不得全量捞出内存分页（`ds_quote_customer_part` 未来量级与 `mcm` 同级，现网 mcm 已 1862 行）
5. ✅ 路径与响应形状**严格对齐** `api.md §2` 给出的形状，便于日后并入主源

---

## 3. 回归确认清单（后端零改动也必须跑）

本任务虽不改后端，但**摘除了两个页签的 UI 入口**，需确认后端未受牵连：

| # | 确认项 | 方法 | 期望 |
|---|---|---|---|
| G-1 | `product` 表端点仍可用 | `GET /api/cpq/products?page=1&size=1` | 200，`total` = 3 |
| G-2 | `material_master` 端点仍可用 | `GET /api/cpq/material-masters?page=0&size=1` | 200，`total` = **42** |

> 🚩 **2026-09-03 更正**：本行原写 `total = 1889`，**是主线写错的**。测试代理实测该端点返回 **42** —— 它已过滤 `pending_quotation_id`，1889 是表的物理行数不是端点返回值。照原值比对会报出一个**不存在的回归**。
| G-3 | 三张外键引用表未受影响 | `SELECT count(*) FROM quotation_line_item WHERE product_id IS NOT NULL` | 与改动前一致 |
| G-4 | 核价侧端点零变化 | `GET /pricing-basic-data/...` 若干 | 与改动前逐字节一致 |
| G-5 | 后端存活 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` | **401**（不是 404、不是 000） |

> ⚠️ `/q/health` 返 404 是**正常的**（未装 smallrye-health），它不是健康探针。判后端健康看业务端点返 401。

---

## 4. 二期触发条件（什么情况下后端才需要真正动工）

| 触发条件 | 届时要做什么 | 去向 |
|---|---|---|
| 用户要求「客户产品」页签可编辑 | 需要 `PUT customer-parts`；免版本表按主键 UPSERT，无升版保护，需补并发与审计设计 | 新任务，不在本期 |
| 用户要求本页支持导入 | 复用 `POST /dataset/quote/import` 即可，**仍无需新端点**，只是前端加按钮 | 新任务 |
| `ds_quote_customer_part` 数据量级超过 10 万行 | `keyword` 模糊匹配需加 GIN / trigram 索引 | 找主源走迁移，不自行加 |
| 用户要求在列表上看「该料号配了几个 sheet」徽标 | `GET parts` 已回 `configuredCount` / `totalSheetCount`，**后端无需改动**，前端渲染即可 | 可随时加 |

---

## 5. 交付要求

- 若走默认路径（零改动）：本文件即交付物，**不产生代码提交**。在 `test-report.md` 写明「本次后端零改动，回归清单 G-1~G-5 全绿」
- 若走退路条款：B-1 / B-2 完成后必须跑 `backend.md` 的强制自检，并回写 `dev-docs/main-api.md`（见 `api.md §4`）
