# backtask · 价格调整更新任务性能优化

> 配套 `需求文档.md`（验收唯一标准）与 `api.md`（契约）。本任务**主体在后端**，前端零改动（见 `fronttask.md`）。
> 闸门 A 未过前不得建分支写代码。

---

## 1. 数据模型变更

### 1.1 表 / 字段

| 表 | 变更 | 说明 |
|---|---|---|
| `price_adjust_settings` | 新增列 `subtotal_guard_enabled boolean NOT NULL DEFAULT false` | S0 L3 口径守卫开关。**默认 `false` = 默认关闭**（D-5） |

无其他 DDL：不新建表、不改视图、不动索引。

### 1.2 Flyway

- 文件名：`V<开工时现取的下一个可用号>__price_adjust_settings_subtotal_guard_enabled.sql`
- 🚨 **版本号不得提前占号**。Flyway 历史是**全会话共享的移动靶**（记忆 `cpq-shared-flyway-history-churn`），提前写死会在合并时撞号。开工当天现查 `SELECT max(version) FROM flyway_schema_history` 再定。
- 内容仅一条 `ALTER TABLE ... ADD COLUMN ... DEFAULT false`，无数据回填（默认值即目标状态）。
- 🚨 **禁止手工 `psql -f`**。让 Quarkus dev 的 `migrate-at-start` 自己跑（CLAUDE.md「修改后强制自检」）。
- worktree 内的迁移主工作区 dev server 看不到，验证方式见记忆 `cpq-worktree-flyway-migration-verify`。

---

## 2. 服务与端点清单

### 2.1 新增

| 类 | 职责 |
|---|---|
| **driver 组件维度审计器**（类名与包位置由架构评审定，建议 `com.cpq.quotation.service` 或 `com.cpq.priceadjust.service`） | 输入 driver 组件集合，输出每个组件的批量安全级别；供预渲染分组决策 |

### 2.2 改动

| 类 / 方法 | 改动 | 约束 |
|---|---|---|
| `PriceAdjustJobExecutionService#executeJob` | 逐项循环**前**做分组预渲染，结果按 `lineItemId` 分发 | 见 §3.1 |
| `MaterialVersionUpgradeService#upgrade` | 增可选 `precomputed` 入参 | **默认 `null` = 现状行为**，dryRun / 预算路径逐位不变 |
| `MaterialVersionUpgradeService#upgrade` S0 段 | 按开关短路 | **告警代码路径保留不删** |
| `CardSnapshotService#refreshCostingCardValuesForLine` | **新增**接受 `precomputed` 的重载 | **原方法签名与行为不动**（其他调用方零影响） |
| `BomTreeRenderService#renderInternal` | 仅 FR-8（方案 A）触及：组件展开分三层调度 | **只做分层调度，不改 render 内部计算算法** |
| `PriceAdjustSettingsService` / `PriceAdjustSettingsDTO` / `PriceAdjustSettings` 实体 | 增一个开关字段的读写 | 沿用既有阈值字段的范式（读库不缓存、`PUT` 即时生效） |

### 2.3 端点

见 `api.md`。仅 `GET/PUT /api/cpq/price-adjust/settings` 的**请求/响应体各多一个字段**，无新增端点、无路径变更、无鉴权变更。

---

## 3. 业务规则与算法

### 3.1 分组预渲染（FR-1，方案 B）

```
分组键 = (costingCardTemplateId, quotation.created_at 的 LocalDate)
```

**日期口径必须与 `SqlViewExecutor#enrichPriceBaseDate` 同源**（`quotation.created_at` → `LocalDate`）。🔒 **不得另写一份日期推导** —— 两套口径是本项目反复出事的根因（AP-52）。

为什么必须含日期：`BomTreeRenderService.render()` 用 `lineItems.get(0).quotationId` 设 `QuotationIdContext`，而 `$wl_ys_bom_view` 含 `f_material_element_price(:customerCode, :priceBaseDate)`。跨建单日整批 ⇒ 组长的基准日被套给全批。**实测 job `c2915208` 因此写坏 5/18 张单**（需求文档 D-3）。

### 3.2 维度审计（FR-4，守卫 1）

扫描 `component_sql_view.sql_template` 的占位符：

| 含 | 级别 | 批量行为 |
|---|---|---|
| 无维度占位符 | `GLOBAL` | 跨全批共享 |
| 仅 `:priceBaseDate` | `PER_PRICE_BASE_DATE` | 按建单日分组 |
| `:quotationId` 或 `:lineItemId` | `PER_LINE_ITEM` | 强制逐项 + WARN |
| 解析失败 / 读不到视图 | `PER_LINE_ITEM` | 强制逐项 + WARN |

🔒 **保守兜底原则：判不出来就逐项，绝不猜。** 未来有人给某视图加维度占位符时，行为必须**自动降级到安全**，而不是继续共享。

### 3.3 失败隔离（FR-5，守卫 2）

预渲染抛异常 ⇒ **回退逐项渲染**，让 FAILED 精确落到出问题的 item。

⚠️ 现状是 task-0729 debug（2026-08-03）**刻意加的行为**：单组件 expand 异常必须让该 item 可见地 FAILED，而不是"0 行 + 报 SUCCESS"（真实事故：272 次 `ContextNotActiveException` 被吞，核价卡片 17 个 tab 清零却全报成功）。批量化**不得**把它退化成"一个组件挂 → 全批挂"。

### 3.4 客户唯一性断言（FR-6）

分组内 `customerId` 必须唯一，否则**抛错**。`render()` 的 `ctxCustomerId` 只取 `lineItems.get(0)`，跨客户会静默串号。现状「一个 job 恒等于一个客户」已核实（`material_price_update_job.customer_no`），但**代码里没有任何东西保证它**。

### 3.5 分层批量（FR-8，方案 A）

按 §3.2 的级别把 `renderInternal` 的组件展开拆三层：`GLOBAL` 整批一次 / `PER_PRICE_BASE_DATE` 按日期分组 / `PER_LINE_ITEM` 逐项；三层结果在装配阶段按 lineItem 合并。

收益依据：唯一带日期维度的 `$wl_ys_bom_view` 只花 **54ms**，而贵的 `$cpgd_view`(272ms) / `$cpbl_view`(140ms) / `$cn_view`(138ms) 全部与报价单无关。每 job SQL 时间 7.6s（B，6 组）→ ~1.5s（A）。

### 3.6 S0 开关（FR-9）

`upgrade()` 的 S0 段按 `price_adjust_settings.subtotal_guard_enabled` 短路。**每次读库取值、不缓存**（沿用阈值字段的既有范式，保证 `PUT` 后即时生效不重启）。

---

## 4. 事务边界

- `executeItem` 保持 `@ActivateRequestContext + @Transactional(REQUIRES_NEW)` **不变**。
  > ⚠️ 这个注解位置是 2026-08-03 的真根因修复结果（挂在 `executeJob` 上会让 request-scoped bean 在嵌套 `REQUIRES_NEW` 后无法解析，实测 100% 抛 `ContextNotActiveException`）。**不得上移、不得删除。**
- 预渲染发生在**循环之外、任何 item 事务之前**，不参与 item 事务。
- 预渲染结果类型 `Map<UUID, Map<String, ArrayNode>>`，**纯 JsonNode、无 Hibernate 托管实体**，跨 `REQUIRES_NEW` 传递安全（已确认）。
- 🔒 **只读传递，不得跨 item 复用可变对象**（2026-06-22 教训：不要把缓存对象按引用交出去 —— 那次 73 行里 9 行算错就是这么来的）。

## 5. 幂等与并发

- 预渲染是纯读操作，可重复执行，无副作用。
- 批次中途被 supersede（item 转 `STALE`）→ 丢弃对应预渲染结果，**不写入**（FR-7）。
- `row_version` 乐观锁语义不变；CONFLICT 判定路径不动。
- **不引入任何并发/线程池**（需求文档 D-1，永久排除）。

## 6. 性能要求

| 指标 | 现状 | 目标 |
|---|---|---|
| 18 项 job 端到端 | 58s | ~25s |
| 每 job 树渲染 SQL 条数 | 306（18×17） | 方案 B ≤ 分组数×17；方案 A ≈ 17 + 分组数 |
| 单项耗时 | 3.22s | ~1.35s |

⚠️ **目标不是验收门槛**（AC-10）：达不到就如实报数并说明天花板，不追加未验证的优化去凑。

---

## 7. 自检项（每次改动结束前必跑）

- [ ] `touch` 一个 java 文件强制 Quarkus 重启 → 等 5-7 秒
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 **401**（业务端点 401 = 应用在跑、鉴权正常；`/q/health` 返 404，**它不是健康探针**）
- [ ] Flyway：`SELECT version, success FROM flyway_schema_history WHERE version='<NN>'` → `success=t`
- [ ] **禁止**手工 `psql -f V_xx.sql`
- [ ] 后端测试在 **worktree 的 `cpq-backend/`** 下跑（`./mvnw test`），不要 cd 主仓
- [ ] 无 schema DDL CASCADE 操作，故不涉及"视图重建后必须重启"那条；若临时加了探针端点，**收尾必须确认 `/api/cpq/tmp-*` 全部 404**

---

## 8. Task 列表（逐项可勾选）

### T1 · 测量基线固化
- [ ] 产出 `test.md` 的探针手法（临时只读端点 + MD5 比对 + 分相 SQL 计数）
- [ ] 产出改造前基线：4 个 job 的每项耗时 / SQL 条数 / 每个 line item 的 `costing_card_values` MD5
- [ ] 基准 job 集合（缺一不可）：

  | job | 项数 | 建单日分组 | 用途 |
  |---|---|---|---|
  | `c2915208-4327-4818-a9bf-05fdca905c6a` | 24 | **6** | **已知反例**，AC-2 强制 |
  | `6c0aebc8-3a89-4b79-aae0-ff8a768a696b` | 29 | 6 | 日期分散 + 最大批量 |
  | `06b54e9a-305a-4401-95f9-2910b4599026` | 18 | 3 | 日期集中 |
  | `1b7208ab-0168-41c6-8fe6-a833de985de2` | 17 | 3 | 日期集中 |
- [ ] 探针端点必须 `@RoleAllowed({"SYSTEM_ADMIN"})` + 只读；触发升版一律 `dryRun=true`（事务 rollback-only，DB 无痕迹）

### T2 · 守卫 1：维度审计（**先于批量化落地**）
- [ ] 实现审计器（§3.2 判定表）
- [ ] 单测覆盖 4 个分支（含"解析失败 → 逐项"）
- [ ] 对当前库真实数据跑一次，输出 = 1 个 `PER_PRICE_BASE_DATE`(COMP-0049) + 16 个 `GLOBAL` + 0 个 `PER_LINE_ITEM` → **AC-8**
- [ ] 夹具里塞 `:quotationId` → 该组件落 `PER_LINE_ITEM` → **AC-5**

### T3 · 方案 B：分组预渲染
- [ ] `refreshCostingCardValuesForLine` 增重载（原签名不动）
- [ ] `upgrade()` 增可选 `precomputed`（默认 null = 现状）
- [ ] `executeJob` 循环前分组预渲染 + 分发
- [ ] 日期口径与 `enrichPriceBaseDate` 同源（**不另写一份**）
- [ ] 分组内 customerId 唯一断言 → **FR-6**
- [ ] STALE 丢弃 → **FR-7**
- [ ] **AC-1 / AC-2 / AC-3 / AC-6** 全过

### T4 · 守卫 2：失败回退逐项
- [ ] 预渲染异常 → 回退逐项
- [ ] 注入必然失败的组件，断言只有相关 item FAILED → **AC-4**

### T5 · 方案 A：分层批量
- [ ] 按 §3.5 三层调度；装配阶段按 lineItem 合并
- [ ] 记录 A vs B 在 `c2915208`（6 组）上的耗时对比
- [ ] **AC-1 / AC-2 / AC-3 / AC-6** 重跑全过
- [ ] ⚠️ 若评审认为改动面过大 → 只交付 T3，本 Task 转二期（D-4 已授权）

### T6 · S0 开关（⚠️ **实施前需用户就 D-5 拍板**）
- [ ] Flyway 加列（开工现取版本号）
- [ ] 实体 / DTO / Service 加字段
- [ ] `upgrade()` S0 段按开关短路，告警路径保留
- [ ] **AC-7** 双向验证

### T7 · 收尾
- [ ] 删除全部临时探针（`git status` 干净，`/api/cpq/tmp-*` 全 404）
- [ ] 跑完整 AC 门禁 → `test-report.md`（**AC-10 达不到就如实报**）
- [ ] `api.md` 最终契约回写 `dev-docs/main-api.md`
- [ ] 回写 `docs/RECORD.md`（含"抽样 0 不一致不是安全证明"这条方法论）
- [ ] 回写 `dev-docs/INDEX.md` §5 状态列 + 附 merge hash
- [ ] `BACKLOG.md` BL-0140 状态；§2.2 二期两项登记为新条目
- [ ] 合并 master → 删 worktree + 分支 → 更新本任务 `需求文档.md` 状态字段
