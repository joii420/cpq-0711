# S3 片 · 改动前基线（2026-09-11 07:18~07:26 -07:00）

> 认领 AC-1 / AC-2 / AC-4 / AC-10 / AC-12。本文只记**改动前基线**；改动后结果另出。
> 🚫 本片未读任何被点名禁止的实现文件。用例由 `需求文档.md ③` 与 `repair-260910 问题说明 ⑥` 的 AC 原文派生。

## 环境与验明正身

| 项 | 值 |
|---|---|
| 改动前实例 A | 主仓 `/home/joii/project/cpq` 的 **8081**（`git status` 对 `cpq-backend/src` / `cpq-frontend/src` 干净 = master） |
| 改动前实例 B（交叉复核） | `scratchpad/s3/bsrc` 的 **8103**（源码副本，无 `RowScopeProjector.java`） |
| 库 | `10.177.152.12:5432/cpq_db_0724`；8081 / 8103 的 `totalElements` 均 = **170**，与库内 `SELECT count(*) FROM quotation` = **170** 一致；对照 `cpq_db_test` = **140**（区分得开） |
| 改动前产物 | `component_sql_view(221dc766).sql_template` md5 = `7fba05dfd1dbea768952b48abad612f3`，`:customerProductNo` **标量**、无 `customerProductNos` |
| 改动前 roles | `CUSTOMER_PART(QUOTE)` 5 列 `roles` 全为 `{}`；全库分布 `{}`236 / `{ROW_KEY}`50 / `{PART_NO,ROW_KEY}`42 / `{SORT}`28 / `{PART_NAME}`8 / `{PART_NO}`2 —— 与 `api.md §2` 记录逐项一致 |
| 观测口径 | 后端日志两类行：`[SqlViewExecutor] driver path=$builder_xxx rows=N`（**每执行一次一行** ⇒ SQL 次数）与 `[quote-bucket] … eligibleComps=… ` / `comp=<id> eligible …`（⇒ 合桶准入） |
| 计数隔离 | 每次请求前记 log 字节偏移，只统计本次窗口；并核对窗口内 `quotation=<uuid>` **仅出现本片自己的单**（🚫 未用任何全局计数） |

## 夹具（全部 `T0911S3` 前缀，只写自己的行）

| 单 | id | 明细 |
|---|---|---|
| `T0911S3-AC1-同料号多客编` | `e166c7d9-5d81-49f7-a246-5ea9e3475504`（QT-20260911-0841） | 4 行，同料号 `S0004`，客编 `B17-DUP-0910-1` / `B17-DUP-0910-2` / `B18-BIND-0910B` / `RW-A004` |
| `T0911S3-PERF-大单基线` | `5f641c55-8387-4eee-a7ff-a78f312bbf06`（QT-20260911-0842） | **1200 行**，1182 个不同料号，1200 个不同客编 |
| `T0911S3-R10-零回归夹具` | `5f70e531-371a-439e-a34c-7304d6ae9981`（QT-20260911-0843） | 4 行：`S0004/RW-A004`、`S0004/NULL`、`0028-2609000015/B1SELF-050259`、`S0004/T0911S3-NOMATCH`（判别性反例） |

🚫 未碰 S2 的 `T0911S2` 数据；🚫 未删 `ds_quote_customer_part` id=21327/21328/21329 三条 MANUAL 夹具；对 `CUST-0004/S0004` 的 4 条客编只做只读。

## 基线数值

### AC-1 / AC-2（4 明细行的整单物化，`refresh-snapshot`）

```
builder_221dc7668ab6（产品 COMP-2344）  执行 5 次 = 1 次预取(lineItemId=null,rows=4) + 4 次逐明细行(rows=1 each)
builder_98ee51d1832e（材质元素，可合桶）执行 2 次 = 1 次桶查询 + 1 次预取，**0 次逐行**
builder_7f9a5bbf264f（BOM）            执行 1 次
[quote-bucket] quotation=e166c7d9… eligibleComps=1 totalComps=3 distinctParts=1
  ⇒ comp=221dc766 **不在** eligible 列表 ⇒ eligibleForQuoteBucket(221dc766) = false
耗时 time_total=0.551969
```
8103 上交叉复核逐项相同（5 / 2 / 1 次，eligibleComps=1）⇒ 该基线不是 8081 的实例特性。

### AC-1 的正确性对照（同一次物化，「快」与「对」成对）

| sort_order | li.customer_part_no | 渲染行数 | 渲染客编 | 品名 | 单重 |
|---|---|---|---|---|---|
| 0 | B17-DUP-0910-1 | 1 | B17-DUP-0910-1 | 触桥组件A | 16.7 |
| 1 | B17-DUP-0910-2 | 1 | B17-DUP-0910-2 | 触桥组件A | 16.7 |
| 2 | B18-BIND-0910B | 1 | B18-BIND-0910B | 触桥组件A | 16.7 |
| 3 | RW-A004 | 1 | RW-A004 | 触桥组件A | 16.7 |

### AC-12（1200 明细行整单物化）

```
run1 http=200 time_total=30.226700
run2 http=200 time_total=28.758146   ← 窗口混入他人 7 个视图各 4 次，含共享机噪声
run3 http=200 time_total=21.049276
builder_221dc7668ab6 执行 1201 次（= 1 预取 + 1200 逐行），三轮均为 1201（零漂移）
[quote-bucket] eligibleComps=1 totalComps=3 distinctParts=1182
```
⚠️ 耗时在共享 8081 上抖动 21~30s；**SQL 次数 1201 三轮零漂移，是本条更可信的判据**。

### AC-4 / AC-10（repair-260910 AC-1~AC-7 逐条基线，夹具 R10）

| repair AC | 基线实测 | 结论 |
|---|---|---|
| AC-1 `S0004`+`RW-A004` | 1 行，客编 `RW-A004` | 成立 |
| AC-2 客编为空行 | 1 行，客编 `(EMPTY)`，品名 `触桥组件A` / 单重 `16.7` 有值 | 成立 |
| AC-3 跨客户隔离 | 渲染客编中**不出现** `B5SELF-050536`（`CUST-0001/S0004` 专属） | 成立 |
| AC-4 `0028-2609000015` | 1 行（不是 7 行），客编 `B1SELF-050259` | 成立 |
| AC-5 其余页签 | BOM 6/6/2/6 行、材质元素 4/4/2/4 行，subtotal 全 `0.000000000000` | 见 `07-…各页签行数小计.txt` |
| AC-6 谓词位置 | `LEFT JOIN ds_quote_customer_part dqcp ON … AND dqcp.customer_product_no = :customerProductNo`，**`WHERE` 子句内无客编谓词** | 成立 |
| AC-7 无 lineItem 预览 | `POST /components/221dc766…/builder/preview` → `rowCount=1`、客编 `null`、无「未绑定参数」 | 成立 |

📌 **判别性反例（第 4 行 `T0911S3-NOMATCH`，表中不存在的客编）**：基线 **1 行 + 客编空** ——
不是 0 行（谓词误入 `WHERE`），也不是 4 行（谓词未生效）⇒ 证明该夹具**有判别力**，可作改动后的三态检查器。

⚠️ **`POST /components/{id}/expand-driver` 在本组件上基线即返 400**
（`SQL 视图引用了 :total_material_no 但当前渲染上下文未提供该参数`）——
这是 `:total_material_no` 的硬阻断，**与本任务无关、改动前就如此**。repair AC-7 走的是 `builder/preview`，不是 `expand-driver`。

## 文件

| 文件 | 内容 |
|---|---|
| `01-AC1AC2-基线-8081窗口日志.log` | AC-1/AC-2 基线的完整请求窗口日志（含污染检查） |
| `02-AC1-基线-各行渲染客编.txt` | AC-1 正确性对照 |
| `03-AC12-基线-三轮耗时.txt` / `04-AC12-基线-run1汇总.txt` / `04-…run1窗口日志.log.gz` | AC-12 基线 |
| `05`~`09` | AC-10（repair AC-1~7）基线 |
| `10-…8103交叉复核窗口日志.log` | 第二个改动前实例的交叉复核 |
| `90`~`92` | 造数脚本与可复用探针（改动后原样复跑） |
