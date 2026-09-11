# S1（只读片）证据索引 · task-260911

> 认领 **AC-7 / AC-11 / AC-13**。**全程只读**，未写任何库、未 commit、未改主仓文件。
> 采集时点：2026-09-11 · 库 `10.177.152.12:5432/cpq_db_0724` · 主仓 8081（pid 20503，cwd=`/home/joii/project/cpq/cpq-backend`）

## 环境正身（改动前 = master）

| 项 | 实测 |
|---|---|
| 8081 库正身 | API `/quotations?page=1&size=1` → `totalElements=169` **==** `cpq_db_0724` `count(quotation)=169`；`cpq_db_test`=140（排除） |
| 8081 代码正身 | 进程 cwd = 主仓 `cpq-backend`；`git status --porcelain -- cpq-backend/src/**` **空**；HEAD=`master dab8bc9a` |
| 语义图 | `ROW_SCOPE` 全库出现 **0** 次；`CUSTOMER_PART(QUOTE)` 5 列 roles 全 `{}` |
| flyway | 最新 `V440 repair260910 costing element price customer level` success=`t` |
| 谓词形态 | 3 个「产品」视图**都**带 `repair-260910` 的**标量** `= :customerProductNo`，且都在 `LEFT JOIN … ON` |

## 文件清单

### 基线（改动前，一次性，改完不可再采）
- `BEFORE-component_sql_view-md5.tsv` — 99 行（98 builder + 1 非 builder）视图的 len/md5/builder_config md5
- `BEFORE-component_sql_view-full.tsv` — 同上的 **sql_template 全文**（逐字节基线，61 524 B）
- `BEFORE-sqltemplate-builder_{221dc7668ab6,7277969cc41c,a71947b68d50}.txt` — 3 个「产品」视图全文
- `BEFORE-template_sql_view-md5.tsv` / `-full.tsv` — **0 行**（该表全库为空，见下方「重要发现」）
- `BEFORE-expand-raw.json` / `BEFORE-expand-summary.tsv` — **57 条** (夹具×明细行×组件) 的 `batch-expand` 原始响应与 canonical md5
- `BEFORE-lineitem-cardvalues-md5.tsv` / `-full.tsv` — 15 个明细行的 `quote_card_values` 指纹与全文（小计/单元格）
- `BEFORE-view-structure-md5.tsv` — 3 张单的 `quotation_view_structure`（页签清单）指纹
- `BEFORE-roles-distribution.txt` / `BEFORE-CUSTOMER_PART-roles.txt` — 语义图 roles 基线
- `BEFORE-flyway-最新8条.txt`
- `BEFORE-夹具判别力-ds_quote_customer_part.txt` / `BEFORE-判别性夹具-客编取值.txt` — 夹具判别力自证

### 工具（均已自证）
- `capture.py` — 只读采集器（`batch-expand`），`python3 capture.py <cookiejar> <证据目录> AFTER`
- `compare.py` — BEFORE/AFTER 逐字节对照器
- `ac13_check.sh` — AC-13 三态证伪检查器（泛化到集合谓词），`./ac13_check.sh <sql文件> [占位符名]`
- `三态自证/ac13三态自证-原始输出.txt` — A=FAIL(MISSING,exit 2) / B=FAIL(IN_WHERE,exit 3) / C=PASS(exit 0) + 2 条旁证
- `证伪-compare态1完全相同.txt`(exit 0) / `证伪-compare态2人为篡改.txt`(exit 1，精确点名 2 条 FAIL)

## 夹具（全部为他人既有数据，S1 零造数）

| 夹具 | 单号 | 状态 | 模板 | 明细行 | 用途 |
|---|---|---|---|---|---|
| F1 | `QT-20260907-0564` | DRAFT | 取值测试模板1 `4a2ce521` | 4（S0001/S0004/S0008/S0012，客编各不同） | AC-11(视图 `7277969cc41c`) + AC-7(BOM/材质元素) |
| F2 | `QT-20260908-0624` | DRAFT | 取值测试模板2 `a11b0a33` | 8（4 料号各 2 行，**同料号同客编**） | AC-11(视图 `a71947b68d50`) + AC-7(BOM/材质元素/加工费) |
| F3 | `QT-20260910-0816` | DRAFT | 正泰测试模板1 `45cc0267` | 3（**客编全空**） | AC-7(BOM/材质元素/加工费/自制加工费) + 本任务改动目标视图 `221dc7668ab6` 的空客编形态 |

**判别力自证**：`CUST-0001/S0004` 有 **2** 条客编、`CUST-0004/S0004` 有 **4** 条客编，而各明细行的「产品」页签改动前**均返 1 行且客编 = 本行 `customer_part_no`** ⇒ 谓词确实在生效，夹具不是「怎么改都对」的钝夹具。

## 🚨 重要发现（写给主线，影响 AC-11 的判据本身）

**`template_sql_view` 表全库 0 行** —— 不存在「模板级 sql 视图快照」这一层。真实的快照层是
`quotation_component_sql_snapshot`（608 行），且**只对已提交单冻结**（实查：F1/F2/F3 三张 DRAFT 单**一条快照都没有**；
`QT-20260910-0812`(SUBMITTED) 才有 5 条，`frozen_at=2026-09-10 17:12`）。

⇒ **AC-11 原文「快照未推 ⇒ 渲染层应不变」这个因果链对 DRAFT 单不成立**：
DRAFT 单读的是 **live** `component_sql_view.sql_template`，编译器一改，它们**立刻**吃到新 SQL。
所以 AC-11 实际要验的是**更强**的一条：**新集合谓词 + 分发层挑行，产出与旧标量谓词逐字节相同**。
本片的 BEFORE 基线正是按这条强判据采的（12 条「产品」页签结果，含同料号同客编重复行与空客编行）。

## 待做（解锁条件）
- AC-7 / AC-11 的 AFTER 侧：等后端报完成 → 在 **worktree 临时端口**（🚫 不用 8081）重跑 `capture.py … AFTER` → `compare.py`
- AC-13：等迁移落库 → `ac13_check.sh` 跑真实新产物 + 查 flyway `success=t` + 查 `ROW_SCOPE` 可见
