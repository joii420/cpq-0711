# backtask · repair-260912 核价 Excel 视图形态与编辑页取数

> 🚫 只按本文件做。AC 原文在 `问题说明.md` ⑥。A0 已裁决**方案甲**，不许改成乙/丙。

## 背景一句话

核价 Excel 有三条路径，目标形态（**每产品一行 + 取卡片值**）**仓库里已经存在且已修好**（`buildExcelValues` 4 参 → `buildLineRowData(…, cardValuesJson)`）。本次是把另外两条切到它上面，**不是新写渲染逻辑**。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-2, AC-3, AC-8① | `CardSnapshotService` **三处**调用点（`:1225` / `:1406` / `:2099`）的 `buildExcelValues(..., costingTree)` 由 `true` 改 **`false`** ⇒ 走 4 参重载 ⇒ 每产品一行 + 取卡片值、不再写 `treeMode`。<br>⚠️ **三处都要改**，漏一处就是「有的单树形有的单不是」。请自己 grep 确认没有第四处。 |
| **B-2** | AC-1, AC-8② | `ExcelViewService.getExcelView:160`：**核价侧**把该行的 `costingCardValues` 作为 `effectiveRows` 来源传进去（走 6 参 `buildRowData` 或与 `buildLineRowData` 统一，你选更干净的那个）。<br>🚨 **报价侧必须逐位不变** —— 该端点报价/核价共用，`templateIdOverride` 是核价模板时才走新分支，判据请用**显式的模板归属判断**，不要用「有没有 cardValues」这类隐式条件。 |
| **B-3** | AC-7 | `buildLineTreeRows` **保留不删**，加注释说明「暂不使用（2026-09-12 用户裁决核价 Excel 不走树形），保留以便日后切回」。🚫 不许顺手删除或标 `@Deprecated` 之外的破坏性改动。 |
| **B-4** | AC-4, AC-5, AC-6, AC-8, AC-9 | 回归与还原实验：① 报价侧 Excel **形态与数值**双零回归（落库值 + `GET /excel-view` 返回，用「键集合一致 + 叶子数值等价」比对，比对器做变异实验）；② 核价**产品卡片**视图不受影响（`S0001` BOM 仍 233922.5 / 5204745 / 小计 5438667.5）；③ **双向还原实验**（见 AC-8，两个方向各自跑红）；④ 相关测试类全绿。 |

## 明确不做

| 不做项 | 理由 |
|---|---|
| 改 `CardEffectiveRows` | `repair-260911` 刚交付，本次不动 |
| 改列表达式语义 / 让 `(总计)` 按节点取值 | 用户已裁决「明细看卡片视图」，Excel 只给总计 |
| 删 `buildLineTreeRows` | 见 B-3，用户可能改主意 |
| 刷存量 `costing_excel_values` | **由主线执行**（§3.2 写操作）。🚫 你不许碰任何 `*_excel_values` 数据 |
| 前端任何改动 | 本次预期零前端改动；若你认为必须改，停下来报主线 |

## 硬约束

- 🚨 §3.2 红线：零迁移、零 DDL、零批量写。遇 `DROP`/`TRUNCATE`/无 WHERE 的写/`rm -rf`/`git reset --hard` → **停下报告**。
- 🚫 不 `git commit`、不 `git stash`、不占 `8091`/`5090`。
- worktree 起 Quarkus 需加 `-Dquarkus.flyway.migrate-at-start=false -Dcpq.dataset.schema-check.enabled=false`；**跑测试这两个参数必须走 `_JAVA_OPTIONS` 环境变量**（`mvn -D` 传不进 surefire fork JVM）。
- ⚠️ 全量 `mvnw test` 恒有 `mat_*` 相关红（本库从未建过这些表），不是验收门槛。
