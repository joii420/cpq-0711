# backtask.md · 报价编辑链路优化与前后端对账（后端工程师输入）

> 口径以同目录 `需求文档.md` 为准，接口以 `api.md` 为准。本文件只写「后端怎么落地」。
> ⚠️ 主战场 `CardSnapshotService.java`（13 个任务在它身上叠协议）。**动它之前必读**：`需求文档.md §8 已知坑位` 全部 8 条，尤其 **K4（`REQUIRES_NEW` 自锁 → JTA 60s 超时）**。

---

## 1. 阶段责任划分

| 阶段 | 后端要做什么 | 工作量 |
|---|---|---|
| **⓪** | **`ComponentService.VALID_FIELD_TYPES` 由 8 收窄为 6**（剔 `DATA_SOURCE` / `INPUT`），**代码分支一处不删** | **极小（一行）** |
| **①** | 只做 **API-3 提交闸门**（FR-6）+ **API-5 埋点落日志**（FR-5）。**分流/对账全在前端** | 小 |
| **②** | **FR-8 防丢更新**（串行化 / 乐观锁 + `409`） | 中 |
| **③** | **FR-11~13 懒物化**（含 6 处挂 ensure + API-2） | **大（本任务主体）** |
| **④** | **FR-14~17 缓存**（含前置：收敛结构写入口） | 中 |
| **⑤** | **后端零改动** —— Decimal 统一是前端把自己对齐到后端既有的 `BigDecimal` 口径。后端只需**配合验收**（提供对拍基准值） | 无 |

---

## 2. 数据模型变更

### 2.1 阶段③ 脏标记（二选一，实现前定）

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **a** | `quotation_line_component_data` 增列 `row_data_dirty_at timestamptz`（Flyway `V<待分配>`） | 语义清晰、可查可审计 | 要迁移 |
| **b** | 复用 `snapshot_at` / `row_version` 语义（如 `snapshot_at < 某标记`） | 零迁移 | 语义重载，后人难懂 |

**建议 a**，理由：脏标记要被 6 个 ensure 点读，语义必须一眼可懂；重载现有列是「后人难懂」类技术债。

> 🚨 **Flyway 版本号开工时才分配**（K2：共享 `cpq_db` 的迁移号是移动靶，并发会话会插号）。
> 迁移落地后自检：`SELECT version, success FROM flyway_schema_history WHERE version='NNN'` → `success=t`。
> **禁止手工 `psql -f V_xx.sql`**，让 Quarkus dev 的 `migrate-at-start` 自己跑。

### 2.2 其余阶段：无表结构变更

---

## 3. 服务与端点清单

| 端点 | 归属类 | 新增/改造 |
|---|---|---|
| `PUT …/quote-card-edit`（API-1） | `QuotationResource:217` → `CardSnapshotService.editCardValue:3180` | **阶段② 加乐观锁 `409`**；阶段①④ 零改动 |
| `POST …/{id}/ensure-row-data`（API-2） | `QuotationResource` 新增 → `CardSnapshotService` / `ConfigureSnapshotService` | **新增** |
| `POST …/{id}/submit`（API-3） | 提交服务 | **新增前置校验 + `409`** |
| `POST …/admin/cache/evict`（API-4） | `QuotationAdminResource` | **新增**，`SYSTEM_ADMIN` only |
| `POST …/reconcile-report`（API-5） | `QuotationResource` 新增 | **新增**，只落日志 |

---

## 4. 业务规则与算法

### 4.1 阶段① · 提交闸门（FR-6 / D7）

新增 `assertLineSettled(UUID lineItemId)`，提交前对每个 line item 调用，两个条件**都要查**：

1. **在飞写**：阶段② 的串行队列里该 lineItem 仍有待处理项 → `WRITE_IN_FLIGHT`
2. **未落定差异**：前端经 API-5 上报且未消解 → `RECONCILE_PENDING`

> ⚠️ 差异状态存在哪：**阶段① 先用进程内 Map**（key = lineItemId，value = 最近一次上报的差异清单 + 时间戳），D8 单实例下够用。
> 转多实例时需要外置（Redis / 表），届时随 D8 一起重评。**这一点必须写进 `test-report.md` 的已知限制**。

消解条件（任一）：① 下一轮对账上报 `diffs: []`；② 该 lineItem 的 `quote_card_values` 被整份重建（`ensureCardValues` / `refreshQuoteCardValues`）。

### 4.2 阶段② · 防丢更新（FR-8 / D3）

**问题**：`editCardValue` 从 `li.quoteCardValues` 读 baseRows/editRows 再写回。今天前端 `await` 把请求串行化了；**去掉 await 后并发请求会基于旧快照互相覆盖**（lost update）。

**方案（二选一，建议 a）**：

| 方案 | 做法 | 评估 |
|---|---|---|
| **a 乐观锁** | 请求带 `row_version`；服务端比对，不符返 `409` 让前端重放 | ✅ 复用现有列，无新状态；重放逻辑在前端，服务端无状态 |
| b 服务端串行队列 | 按 lineItemId 排队 | 需要队列状态，多实例失效，且连改 10 格 = 排队 9 秒 |

**注意**：`quotation_line_item.row_version` 已存在。要确认 `editCardValue` 路径当前**是否已在推进它**（未推进则需补），并确保 `409` 时**不留半写状态**（整个 `@Transactional` 回滚）。

### 4.3 阶段③ · 懒物化（FR-11~13 / D11）

**现状**（`editCardValue:3235`）：

```
materializeWholeLineRowData → for(每个组件) → writeRowData(REQUIRES_NEW UPSERT)
= 8 个页签 8 次独立事务 = 实测 356ms（占单次编辑 45%）
```

**改造**：

1. **只推迟公式列，INPUT 值即时写**（D11）
   - INPUT 值是用户敲的，不需要算，直写代价极小
   - 公式列标脏，等 ensure 时再算
   - **收益**：`RowKeyUniquenessService`（判重只看 INPUT）天然不用挂 ensure
2. **标脏**：`row_data_dirty_at = now()`（方案 a）
3. **`ensureRowData(quotationId, lineItemId?)`**：查标脏组件 → 按**拓扑序**物化（跨页签依赖：引用方要读到依赖方的最新列小计）→ 清脏标记
   - 复用 `ConfigureSnapshotService.materializeLineRowData` 现成的单趟拓扑序物化，**不要新写第二份**
   - 单飞锁：照抄 `ensureCardValues` 的 `pg_try_advisory_xact_lock`，拿不到返 `-1`

4. **6 处挂 ensure**（`需求文档.md §5.3`）：

| 位置 | 何时调 |
|---|---|
| `ExcelViewService.buildRowData:403` / `:732` / `:742` | 进入 Excel 视图构建前 |
| `LineDiscountService:148` | 解析 `discountBaseAmount` 前 |
| `SnapshotCollectorService:172` | 提交冻结采集前 |
| `QuoteBackfillCollector:138` | 采集前 |
| `PriceReconciler:553` | 归位读取前 |
| `MaterialVersionUpgradeService:459` / `:633` | 升版读取前 |

> ⚠️ **K4 时序约束**：`editCardValue` 现有顺序「赋卡片值 → 跑 `REQUIRES_NEW` 物化 → `flush/clear` → 覆盖派生小计」**不能调换**（提前覆盖会与内层 `REQUIRES_NEW` 自锁，JTA 60s 超时）。懒物化把中间那步挪走之后，**必须重新验证这条约束仍成立**，并在 `test-report.md` 记录验证方式。

### 4.4 阶段④ · 缓存（FR-14~17）

**前置（必须先做）**：`quotation_view_structure` 的**写入口收敛为唯一方法**。
现状可能有多条写路径（`ensureStructure` / `populateViewStructures` / 迁移脚本），**先 grep 穷举并收敛**，否则失效钩子必漏。

**实现**：用已装的 **Quarkus `cache` 扩展**（`@CacheResult` / `@CacheInvalidate`），不要自己造。

| 缓存名 | key | TTL | 容量 |
|---|---|---|---|
| `frozen-structure` | `quotationId + view_kind` | **30 分钟** | LRU 200 |
| `row-key-fields` | `componentId` | **10 分钟** | 全量常驻 |

**失效点**：
- 结构写入口**事务提交后** `evict(quotationId)`（不是提交前 —— 回滚了缓存已清虽只多查一次，但语义要对）
- `ComponentService` 保存组件**提交后** `evict(componentId)`

**🚨 硬规则 FR-17（negative caching 禁令）**：
`loadRowKeyFieldsNode` 返回 `null` 时**不得缓存**。
教训出处：`ImplicitJoinRewriter.tableColumnsCache` 曾在视图 `DROP CASCADE` 瞬间缓存了**空集**并永久残留 → BNF 路径不再注入谓词 → 视图返全表 → UI 出现「首值（共N项）」。`CLAUDE.md`「任何 schema DDL 后必须重启 Quarkus」那条纪律即由此而来。

---

## 5. 事务边界

| 操作 | 边界 |
|---|---|
| `editCardValue` | 单 `@Transactional`；内层 `writeRowData` 是 `REQUIRES_NEW`（**K4 自锁风险**） |
| `ensureRowData` | 单 `@Transactional` + `pg_try_advisory_xact_lock` 单飞（照抄 `ensureCardValues:753`） |
| 缓存 evict | **必须在事务提交后**（用 `@Transactional(TxType.REQUIRED)` + 提交后回调，或事件监听） |
| 提交闸门校验 | 只读，不开写事务 |

---

## 6. 幂等与并发

| 项 | 要求 |
|---|---|
| `ensureRowData` | **幂等**：已算的零开销（靠脏标记判定），重复调用不重复算 |
| `editCardValue` | 同 `(lineItemId, componentId, rowKey, fieldName, value)` 重复提交结果一致；乐观锁保证并发不丢更新 |
| 单飞 | `ensureRowData` 拿不到锁返 `-1`（语义同 `ensureCardValues` 的 `WARMING_IN_PROGRESS`），**不排队不阻塞** |
| 并发写 `row_data` | 阶段③ 后 `row_data` 有两个写入源（`saveDraft` 前端权威 / 懒物化后端算）→ **必须明确谁赢并写进 `test-report.md`**（建议：`saveDraft` 的 INPUT 值赢，公式列以后端物化为准） |

---

## 7. 性能要求

| 指标 | 现状 | 目标 | 验证 |
|---|---|---|---|
| 单次编辑端到端 | ~900ms | 阶段③后 **~550ms**、阶段④后 **~450ms** | AC-14 |
| 整行物化 | 356ms（45%） | 编辑期 **≈0**（挪到 ensure） | 打点实测 |
| 计算段（`buildCardValues`） | 229ms | **~60ms** | AC-14 |

🚨 **AC-14 必须在生产态复测**：`java -jar target/quarkus-app/quarkus-run.jar`。
本文所有基线均为 **dev 模式 + 远程共享库**，生产（同机房 DB）SQL 往返快很多时**收益会大幅缩水**。本项目在性能上栽过这个跟头（优化「首存慢」时实测发现真正卡点不在预想处）。

---

## 8. 自检项

- [ ] `touch` 一个 java → 等 5-7s 触发 Quarkus 重启
- [ ] 目标端点 `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' …` → **200/401**（不是 500）
- [ ] Flyway（若走方案 a）：`SELECT version, success FROM flyway_schema_history WHERE version='NNN'` → `success=t`
- [ ] **在 worktree 的 `cpq-backend/`** 跑 `./mvnw test`（K3：跑主仓 = 测错树 = 假绿）
- [ ] **A/B 判回归**（K1：master 存量 159F/393E）：改动前后失败数**逐个相同**才算无回归，**禁止看绝对值**
- [ ] **AC-13 值中性**：对无编辑单据，改动前后 `quote_card_values` **逐值不变**（背靠背 stash 对比）
- [ ] 数据落库用 `psql` 直接断言，**不听子代理转述**

---

## 9. Task 列表（逐项可勾选）

### 阶段⓪（一行改动，但要验清楚）
- [ ] B0-1 `ComponentService.java:39` `VALID_FIELD_TYPES` 由 8 → 6：保留 `BASIC_DATA` / `INPUT_TEXT` / `INPUT_NUMBER` / `FORMULA` / `FIXED_VALUE` / `LIST_FORMULA`，剔 `DATA_SOURCE` / `INPUT`
- [ ] B0-2 **确认代码分支一处未删**：`git diff` 只应有那一行（`DATA_SOURCE` / `INPUT` 的解析、渲染、序列化分支全部原样保留）
- [ ] B0-3 **AC-18 验证**：新建组件选 `DATA_SOURCE` → `400` 且错误信息列出 6 种合法值
- [ ] B0-4 **存量回归**：全库三载体（组件表含 DISABLED / 冻结结构 / 模板快照）复查 `DATA_SOURCE`+`INPUT` 仍为 **0**；任一非 0 则**立即回滚本项**并重新评估
  ```sql
  SELECT COALESCE(f->>'field_type', f->>'fieldType') t, count(*)
  FROM component c, jsonb_array_elements(c.fields) f GROUP BY 1;
  -- 冻结结构 / 模板快照同款查询见 需求文档 §2.2 实测记录
  ```
- [ ] B0-5 ✅ **同文件第二个白名单已查清，本次不动**：`ComponentService.java:36` 的 `EDITABLE_FIELD_TYPES = {INPUT_NUMBER, INPUT_TEXT, LIST_FORMULA}` 是「**用户可录入**的字段类型」（含可编辑字段的多行 driver 组件须声明 `rowKeyFields`）。
  - 它**本来就不含** `DATA_SOURCE` / `INPUT`（裸）→ 本次收窄与它**零交集**
  - 它**含 `LIST_FORMULA`** → 又一条「`LIST_FORMULA` 是活的能力、不能删」的佐证（D13）
  - **禁止顺手改它**：它管的是「能不能编辑」，`VALID_FIELD_TYPES` 管的是「能不能配」，两个语义不同

### 阶段①
- [ ] B1-1 `assertLineSettled` + 提交前置校验，`409` 返回 `conflicts` 清单
- [ ] B1-2 API-5 `reconcile-report` 端点，落 `WARN [reconcile-diff]` 日志（字段齐全便于 grep）
- [ ] B1-3 差异状态进程内 Map（**已知限制：多实例失效**，写进 test-report）
- [ ] B1-4 自检 §8

### 阶段②
- [ ] B2-1 确认 `editCardValue` 是否推进 `row_version`，未推进则补
- [ ] B2-2 乐观锁校验 + `409`，冲突时整事务回滚不留半写
- [ ] B2-3 自检 §8（含并发用例：并行 10 次编辑不丢更新）

### 阶段③
- [ ] B3-1 定脏标记方案（建议 a）+ Flyway（**开工时才分配版本号**）
- [ ] B3-2 `materializeWholeLineRowData` 拆分：INPUT 即时写 / 公式列标脏
- [ ] B3-3 `ensureRowData` 实现（复用 `materializeLineRowData` 拓扑序 + 单飞锁）
- [ ] B3-4 API-2 端点
- [ ] B3-5 **6 处挂 ensure**（逐个贴文件:行号进 test-report）
- [ ] B3-6 **重新验证 K4 时序约束仍成立**（改了物化时机）
- [ ] B3-7 裁决并记录「`row_data` 双写入源谁赢」
- [ ] B3-8 自检 §8 + AC-8/AC-9

### 阶段④
- [ ] B4-1 **前置**：grep 穷举 `quotation_view_structure` 写入口并收敛为唯一方法
- [ ] B4-2 `frozen-structure` 缓存（Quarkus cache，30min TTL，LRU 200）
- [ ] B4-3 `row-key-fields` 缓存（10min TTL，全量常驻）
- [ ] B4-4 两处 evict 钩子（**事务提交后**）
- [ ] B4-5 **FR-17 negative caching 禁令**：`null` 不入缓存（AC-12 连查两次验证）
- [ ] B4-6 API-4 诊断端点（`SYSTEM_ADMIN` only）
- [ ] B4-7 自检 §8 + AC-10/AC-11/AC-12/AC-14（**生产态复测**）
