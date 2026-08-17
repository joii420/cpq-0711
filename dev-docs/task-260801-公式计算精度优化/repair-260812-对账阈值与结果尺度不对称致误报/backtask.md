# backtask.md — repair-0812 对账阈值与结果尺度不对称致误报（后端）

- **归属**：`dev-docs/task-260801-公式计算精度优化/repair-260812-对账阈值与结果尺度不对称致误报/`
- **结论：本次后端零改动。**
- 按 `CLAUDE.md` 要求，零改动的任务同样要写本文档，内容 = **「为什么不改」的判定依据 + 回归确认清单 + 二期触发条件**。宁可写细，不可留空槽。

---

## 1. 为什么后端不改（判定依据）

### 1.1 后端当前行为是**正确且符合既定契约**的，不是缺陷方

| 事实 | 证据 |
|---|---|
| 后端**内部**以 12 位工作值传播（链式公式不损精度） | `FormulaCalculator.java:1110-1114` — `working = roundForCalculation(evaluate(...))`（12 位）→ `ctx.fieldValues.put(name, working)` |
| 后端**落快照**时压到 9 位结果精度 | 同上 `:1113` `results.put(name, roundFormulaResult(working))`；BOM 树路径同口径 `:1244` |
| 9 位是 `task-0810` **自己确立并保留**的结果精度契约 | `PrecisionPolicy.java:25` `FORMULA_RESULT_SCALE = 9` |
| **本次编辑触发的重算路径**产出 9 位，与契约一致 | `问题说明.md` §4.2 证据 1（**注意：样本 n=1**）。⚠️ **2026-08-13 后端复核订正**：把样本扩到 200 个 lineItem 后实测 **`max_dp = 17`，421 个数值里有 50 个超过 9 位**（如 `0.14716242167146046`，带 `double→BigDecimal` 二进制噪声特征）。这些是 `a3285cc0`（2026-08-11 20:36 才引入 `FORMULA_RESULT_SCALE=9`）**之前**写入的存量遗留值，**不是活的第二条写入路径** —— `editCardValue`（`CardSnapshotService.java:3556`）每次编辑都调 `assembleTabsWithFormulaResults` **全量重建该 lineItem 所有页签**的 `formulaResults`，任何一次编辑都会用当前唯一的 9 位口径把遗留值整体覆盖，**靠下次编辑自愈**。<br>📌 原表述「现网数据实测与契约一致，无越界」是 **n=1 外推成全称判断**，已作废。<br>📌 **对本方案无影响**：前端归一不依赖「后端本来就是 9 位」这个假设 —— 无论后端给 9 位还是遗留的 17 位，都先在前端归一，设计上稳健 |

**即："传播 12 位、落快照 9 位" 正是设计意图**，前端显示层同样封顶 9 位。缺陷只在**前端拿 12 位去比 9 位**这一处，属判定层，与后端无关。

### 1.2 唯一"能改后端"的方案已被裁决否掉

`问题说明.md` §5 记录：方案 C（后端另出 12 位 `formulaResultsWorking`，或 `formulaResults` 直接改存 12 位）**未被采纳**。否决理由：

- `formulaResults` 是 **4 条链路的数据权威**（比对视图 `ComparisonViewService:286,292` / 价格升版 `MaterialVersionUpgradeService:263-276` / 复制单 `QuotationService:1548` / 树删除重算 `QuotationTreeService`，见 `task-0806/需求文档.md §1.2 P3`）；
- 改尺度要连带评估存量快照兼容、导出、DB `numeric(26,12)` 与已冻结单据；
- 12 位工作值对业务无解释力（低于显示精度 3 个数量级）。

### 1.3 也**不能**从后端侧"顺手放宽"

后端 `SubmitGateService` / `ReconcileDiffStore` **不做数值判定**——它们只存前端上报的差异清单并在提交时检查非空。数值一致性判定 100% 在前端 `valuesReconcile`。因此后端没有可调的阈值旋钮，改后端等于把判定逻辑复制一份到后端，制造第二个真相源。

```java
// SubmitGateService.java:38-50 —— 只判"pending 是否非空"，不比数值
List<ReconcileDiffEntry> diffs = reconcileDiffStore.getPending(lineItemId);
if (diffs.isEmpty()) return List.of();
```

---

## 2. 明确不动的后端资产（改动禁区）

| 资产 | 位置 | 为什么不能顺手动 |
|---|---|---|
| `PrecisionPolicy.FORMULA_RESULT_SCALE = 9` | `common/PrecisionPolicy.java:25` | 全后端结果精度单一来源；改它 = 改全系统的数（本次要求 E-5 不改数） |
| `roundFormulaResult` 调用点 | `FormulaCalculator.java:1113` / `:1244` | 同上；且两处必须同口径，任何单边修改会让普通页签与 BOM 树页签分叉 |
| `formulaResults` 快照结构 | `CardSnapshotService` | 4 条下游链路的数据权威 + 存量单据 |
| `ReconcileDiffStore` / `SubmitGateService` | `quotation/service/reconcile/` | 语义正确（整份替换 + 非空即拦）；本次是让前端不再产生噪声，而不是让闸门变松 |
| `edit-quote-card-value` / `reconcile-report` 端点 | `QuotationResource` | 契约零变更，见 `api.md` |

---

## 3. 回归确认清单（后端不改，但必须确认没被连带影响）

| # | 确认项 | 方法 | 期望 |
|---|---|---|---|
| B-R1 | 编辑端点行为不变 | 真机改一格，看 PUT 响应的 `quoteCardValues` | 结构与值同修复前 |
| B-R0 **（新增前置，必跑）** | 回归目标单据当前是否 DRAFT | `SELECT q.status FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id WHERE li.id='<目标>';` | `DRAFT`。⚠️ 原目标 `f57bfe0c-…` 已 `SUBMITTED`（`editCardValue` 对非 DRAFT `return null`，Resource 再映射成 **400**，前端 `catch{}` 吞掉 = `BL-0152`，会被误判成"改动引入新 bug"）。已改用 DRAFT 主夹具 `b7077e71-…` |
| B-R2 | `formulaResults` 口径**与修复前一致**（非"必须=9"） | 修复前后各跑一次 200 单扩样 SQL（见 `问题说明.md` §4.2 的扩样版） | 两次 `max_dp` / 越界计数**相同**（基线：`max_dp=17`、421 个数值中 50 个 >9 位）。<br>⚠️ 判据是**与修复前一致**而不是"必须等于 9" —— 存量遗留值（§1.1）会让"必须=9"这条恒不成立，用错判据会把历史噪声误判成本次退步 |
| B-R3 | 落库值逐字节不变（E-5） | **幂等回环**（详见 `test.md` TC-10）：① 采 B0′ 指纹 → ② 同一格原值重输（no-op 编辑）→ ③ 立刻再采指纹，三步紧邻 | ① 与 ③ 的 md5 一致。<br>⚠️ 主线原采的基线 `92ff887d…`/29909 bytes **已作废**（该行被第三方改写为 `5eea1ced…`/23532 bytes，单据亦已 SUBMITTED），必须自采 B0′ |
| B-R4 | 提交闸门阳性能力不丢 | 注入真差异（前端 route 篡改第 8 位）后提交 | 仍 409 `RECONCILE_PENDING` |
| B-R5 | 差异消解链路通 | 修复后编辑一格 → 看 `reconcile-report` 请求体 `diffs: []` | 后端 `pending.remove()`，再提交放行 |
| B-R6 | 无 Flyway / DDL | `git diff --stat` 看 `db/migration/` | **0 文件** |
| B-R7 | 后端无需重启即生效 | 本次不改 java/sql | 仅前端 HMR |

> ⚠️ **B-R3 的取证纪律**：`cpq_db_0724` 是**多会话共享**库，本任务期间目标夹具被第三方改写了两次（DRAFT→SUBMITTED、内容 29909→23532 字节）。因此取证必须是**紧邻窗口内的回环**，并在窗口前后各校验一次指纹（`row_version` / `quote_values_at` 有无法解释的跳变 = 第三方写入 → 本次结果作废重跑）。**跨越较长时间的"改动前 vs 改动后"对比在本环境下不可靠。**

---

## 4. N+1 自检

**本次后端改动 0 行**，无新增/改动的 `for` / `forEach` / `stream()` 循环体，无 repository 调用、无 `SqlViewExecutor.execute`、无懒加载 getter 触发。
> N+1 自检：后端零改动，无新增循环，SQL 条数不变 ✅

---

## 5. 二期触发条件（什么情况下后端才需要进场）

| 触发条件 | 后端要做什么 |
|---|---|
| 业务上确认「第 10~12 位的分歧也必须阻断提交」（即恢复 FR-12 原意） | 走方案 C：`FormulaCalculator` 两处同时输出 12 位 working → 快照新增 `formulaResultsWorking` → 存量快照缺字段的兼容分支 → 前端改比这一份 |
| `FORMULA_RESULT_SCALE` 从 9 调成别的值 | **本次修复自适应，后端无需动**；但需重跑 `test.md` 的边界用例确认归一口径仍成立 |
| 对账要支持多实例部署 | `ReconcileDiffStore` 由进程内 `ConcurrentHashMap` 外置（Redis / 表）——这是 `task-0806` D8 已知限制，与本 repair 无关，另立任务 |
| `问题说明.md` §4.5 的「真差异普查」（原 AC-6，已裁决另立任务）跑出**真实**分歧 | 届时按具体分歧定位，可能落到后端公式求值链 |

---

## 6. Task 列表

- [x] **B1** 判定后端是否需改 → **结论：零改动**（依据见 §1）
- [ ] **B2** 执行 §3 回归确认清单 B-R1~B-R7，证据落 `test-report.md`
- [ ] **B3** 在 `test-report.md` 写明「本次无后端改动、无 DDL、无 Flyway、无契约变更」
