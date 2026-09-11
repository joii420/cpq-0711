# S3 片 · 改动后实测与基线对照（2026-09-11 09:26~09:50 -07:00）

> 认领 **AC-1 / AC-2 / AC-4 / AC-10 / AC-12**。改动前基线见 `README-S3基线索引.md`。
> 🚫 本片未读任何被点名禁止的实现文件；用例仍由 `需求文档.md ③` 与 `repair-260910 问题说明 ⑥` 的 AC 原文派生。
> （`backtask.md` 属任务文档，为判断「B-6 置空语义」读过，未读 Java。）

## 环境验明正身（独立复核，未采信派工 prompt 的转述）

| 项 | 实查值 |
|---|---|
| 代码 | `git rev-parse --abbrev-ref HEAD` = `master`；`git status --porcelain cpq-backend/src cpq-frontend/src` = **空** |
| 8081 热重载 | 日志最后一次 `Live reload total time` = **2026-09-11 09:23:27**；09:24:22 `recompile+realign 已执行：重编译 98 个视图` |
| 迁移 | `flyway_schema_history` 最新 = **V441 `task260911 row scope role` success=t @09:23:30** |
| `roles` | 全库 `ROW_SCOPE` **恰好 1 列** = `CUSTOMER_PART(QUOTE).customer_product_no`；`{}` 由基线 236 → **235**（只搬走 1 列，加法式） |
| 编译产物 | 3 个「产品」视图 `sql_template` 全部含 `= ANY(:customerProductNos)`，**无**标量 `:customerProductNo`；`required_variables={customerCode,customerProductNos,total_material_no}` |
| 谓词位置 | 集合谓词在 `LEFT JOIN … ON`；`WHERE` 只有 `material_no = ANY(:total_material_no) AND customer_no = :customerCode`（见 `18` / `19.diff`） |
| 观测口径 | 同基线：`[SqlViewExecutor] driver path=…` 每执行一次一行；按**日志字节偏移**切单次请求窗口，并核对窗口内 `quotation=` **只出现本片自己的单** |

⚠️ **改动前实例 8097 / 8103 已不可用作对照**：`sql_template` 是**落库的全局产物**，`B-10` 已整体覆盖 ⇒
它们现在跑的是「旧 Java + 新模板」的混合体。**对照只能用已归档的基线数值。**

## 逐条结果

| AC | 基线 | 改动后 | 结论 |
|---|---|---|---|
| **AC-1** | `builder_221dc7668ab6` **5 次** = 1 预取 + **4 逐明细行** | **2 次** = 1 预取 + 1 桶查询，**逐行 0 次**（三轮零漂移）| ✅ |
| **AC-1 正确性** | 4 行各取各的客编 | 4 行各取各的客编（`snapshot_rows` / `row_data` / **真实浏览器输入框** 三路一致）| ✅ |
| **AC-2** | `eligibleComps=1`，221dc766 **不在**列表 | `comp=221dc766… eligible`，`eligibleComps=2`（+材质元素）| ✅ |
| **AC-4** | 1 行，客编 = 本行 | 新建单 `QT-20260911-0844`（CUST-0004 选 S0004/`B18-BIND-0910B`）→ **1 行**，客编 = 本行；UI 同 | ✅ |
| **AC-10** | repair AC-1~7 全成立 | `06`vs`15`、`07`vs`16` **diff 为空（逐字节相同）**；repair AC-6 谓词仍在 `ON`；repair AC-7 preview `rowCount=1`/客编 `null`/无未绑定参数 | ✅ |
| **AC-12** | **1201 次**（1 预取 + 1200 逐行），三轮零漂移；耗时 30.2/28.8/21.0s | **2 次**，三轮零漂移；耗时 **3.80/3.50/3.31s**；1200 行**全部** 1 行且客编 = 本行（1200/1200）| ✅ |

## 🚨 附带发现（不在 AC 字面内，见 `20-…`）

空客编 / 无匹配客编的明细行：`snapshot_rows` 正确置空，但 **`row_data` 与 UI 串入他行客编**。
判别实验已**证伪**「行键撞键」这一已知正交解释；机制线索指向 `B-6` 的分发层构造，但**归因未完成**
（`sql_template` 全局覆盖后改动前一侧不可复跑；且我的基线未采 `row_data`）。

## 文件

| 文件 | 内容 |
|---|---|
| `10` | AC-1/AC-2 改动后三轮窗口日志（含污染检查）|
| `11` / `12` | AC-1 各行渲染（DB 层）/ API 层 `rowData` 对照 |
| `13` / `14` | AC-12 1200 行正确性核对 / 三轮窗口日志与耗时 |
| `15` / `16` | AC-10 改动后 R10 各行渲染 / 各页签行数小计（与 `06`/`07` diff 为空）|
| `17` | AC-10 改动后 repair AC-7 `builder/preview` 响应 |
| `18` / `19` | 改动后 `sql_template` 原文 / 与基线的 diff（仅一行谓词）|
| `20` | 🚨 附带发现：空客编行 `row_data` 串入他行客编（含判别实验 + Bug 报告）|
| `93` | UI 取值 dump 探针（须复制到 `cpq-frontend/` 下跑，依赖那里的 `node_modules`；用系统 `/usr/bin/google-chrome`）|
| `截图/` | `AC1-step2-全页.png`（4 卡片 4 个不同客编）· `AC4-step2-全页.png` · `R10-step2-全页.png`（第 2/4 卡片串号现场）|
