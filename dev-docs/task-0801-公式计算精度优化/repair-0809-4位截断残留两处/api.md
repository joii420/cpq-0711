# api · repair-0809 —— 接口零改动的判定依据

## 1. 结论

**无任何接口契约变更**：不新增/删除端点，不改路径、方法、请求参数、响应结构、错误码。
→ 按 `任务平台规则.md` §2.4 / §4 步 9，**`dev-docs/main-api.md` 不需要回写**；此事实须在 `test-report.md` 写明并跳过该步。

## 2. 判定依据

| # | 判据 | 说明 |
|---|---|---|
| A-1 | 改动是两行**内部数值规整** | `CardSnapshotService` 的 `componentSubtotals` 是**进程内 Map**，不出现在任何响应体；`MaterialVersionUpgradeService` 写的是实体字段 |
| A-2 | 受影响的只是既有字段的**数值末位** | `quotation.total_amount`（`NUMERIC(20,6)`，列定义本就是 6 位）、卡片值快照里参与 `[页签(总计)]` 的派生值。字段名/类型/量纲/必填性全不变 |
| A-3 | 无新增查询、无新增写表 | 不涉及 SQL/Flyway |

## 3. 受影响端点（仅数值口径，非契约）

| 端点 | 影响 | 需回写总账 |
|---|---|---|
| `GET /api/cpq/quotations/{id}` | `totalAmount` 末位（≤1e-5） | ❌ |
| `PUT /api/cpq/quotations/{id}` / `POST .../submit` | 同上 | ❌ |
| 价格调整更新任务相关端点（`MaterialVersionUpgradeService` 调用方） | 写回的 `totalAmount` 末位 | ❌ |

> ⚠️ `total_amount` 列是 `NUMERIC(20,6)` —— 原先写 4 位其实是**主动丢掉了列本来能存的 2 位精度**，改后才真正用满列定义。

## 4. 回归确认清单（主线亲验）

- [ ] 改动前后对同一张单据 `GET` 一次，diff 响应体：**只有 `totalAmount` 末位变化**，无其它字段增删改
- [ ] `dev-docs/main-api.md` 保持不变（`git diff` 为空）

## 5. 二期触发条件

若后续决定回刷存量 `total_amount`，需新增回刷端点或运维脚本 → 那时必须走完整 `api.md` + 总账回写。
