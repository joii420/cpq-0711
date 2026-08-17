# test · repair-0809 精度残留两处

> 基线文档：同目录 `需求文档.md`（AC-1~AC-7）。
> 环境：后端测试库 `cpq_db`（`mvnw test` 走这个，**不是** dev 库 `cpq_db_0724`）；前端 `vitest`；真机验收在合并后主工作区。
> **实际结果**列留空，执行人填；未填 = 未执行。

---

## 1. 静态核查（AC-1）

| 编号 | 对应 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-1.1 | AC-1 | `/usr/bin/grep -rn "setScale(4" cpq-backend/src/main/java` | `CardSnapshotService:4078` 与 `MaterialVersionUpgradeService:388` **不再命中** | | P0 |
| T-1.2 | AC-1 | 同上输出的剩余命中 | 恰为 4 处（`PriceImportRowWriter:117` / `PriceTableService:486` / `StrategyService:391` / `CardSnapshotService:1519`），**逐条在 test-report 写明保留理由** | | P0 |
| T-1.3 | D-1 | 读 `CardSnapshotService` 改动处上下文 | 裸键 `roundedTotal` **未被顺手改动**；`buildTabNode` 排除哨兵键泄漏的 🔒 逻辑仍在 | | P0 |
| T-1.4 | R-3 | 读 `MaterialVersionUpgradeService` 改动处上下文 | `lineSum` 累加口径（跳过 `PART` / 只读既有 `lineTotalAmount` / 只对被升版行重算）**一字未动** | | P0 |

## 2. 后端等价性单测（AC-3 / AC-4）

> 夹具**代码构造**，不依赖库里具体单据。🚫 禁止 `Assumptions.assumeTrue` 兜底（BL-0157）。

| 编号 | 对应 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-2.1 | AC-3 | 构造一个含 2 个金额列（值取 `0.083825536` 这类 6 位以上小数）的组件，分别经三个登记点算 `#__amount_total__` | 三者**逐值相等**，且等于精确和（不被截断到 4 位） | | P0 |
| T-2.2 | AC-3 | 边界：金额列和恰为 4 位（如 `0.1234`） | 三者相等（回归保护：确认改动没把"本来就相等"的场景搞坏） | | P1 |
| T-2.3 | AC-3 | 边界：无金额列（`amountCols` 空集） | 三者均为 `0`，不抛异常 | | P1 |
| T-2.4 | AC-4 | 同一 `lineSum`（取 6 位以上小数）分别喂 `QuotationService` 与 `MaterialVersionUpgradeService` 的落库规整 | 两者**逐值相等**（均为 6 位 HALF_UP） | | P0 |
| T-2.5 | AC-4 | 边界：`lineSum` 为 0 / 负数 / 恰 6 位 | 两者相等，无异常 | | P1 |
| T-2.6 | 反向门禁 | 在测试内把 T-2.1 的输入手工截断到 4 位再比 | 与不截断结果**不等** —— 证明用例真的能分辨 4 位与全精度，不是恒绿 | | P0 |

## 3. 回归（AC-5 / AC-6）

| 编号 | 对应 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-3.1 | AC-5 | **同轮 A/B**：先取改前版本在 worktree 的 `cpq-backend/` 跑 `./mvnw test`，再跑改后 | 两组 `Tests run / Failures / Errors / **Skipped**` 并列；改后**无新增失败**。⚠️ 不得引用历史基线数字（RECORD 教训③）；`Skipped` 异常增长要解释（教训④） | | P0 |
| T-3.2 | AC-6 | `npx vitest run src/pages/quotation src/utils src/pages/component` | 相对基线 `1044 passed / 2 failed` 无新增失败 | | P0 |
| T-3.3 | AC-6 | `crossTabOrderParityQt0146.repair0808.test.ts` | 全绿；**并记录 T-2.4 那条的前后端实测差值**（本单修复后应为 0） | | P0 |
| T-3.4 | R-2 | 检查 `task-0806` 的 golden 基线文件是否含受影响模板 | 列出需重取的 golden 及理由，写进 test-report | | P1 |
| T-3.5 | AC-7 | 价格调整链路：跑更新任务（或等价单测） | `quotation.total_amount` == `PrecisionPolicy.round(Σ lineTotalAmount)` | | P0 |

## 4. 真机验收（合并后，AC-2）

| 编号 | 对应 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-4.1 | AC-2 | 跑 `e2e/repair0808-verify.spec.ts` 看 `QT-20260807-0146` 产品小计 | 与后端重算值**逐值一致**；尾差 `2.5536e-5` 消失 | | P0 |
| T-4.2 | AC-2 | 该单保存一次后查库：`SELECT total_amount FROM quotation WHERE quotation_number='QT-20260807-0146'` | 与前端显示的产品小计一致（6 位口径） | | P0 |
| T-4.3 | R-1 | 抽 2 张其它在途 DRAFT 单打开 | 若数值有变化，逐条能解释为"向 6 位口径对齐"；无量级级别的变化 | | P0 |

## 5. 缺陷登记口径

发现的问题登记到 `test-report.md` 缺陷清单（`D-nn`），注明复现步骤 / 严重级 / **是否本次引入（必须同库 A/B 对照后判定）** / 修复状态。
