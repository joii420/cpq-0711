# 后端任务书 · 客户价格调整策略与价格版本（task-0729）

> **产出日期**：2026-08-01 · 技术总监
> **上位文档**：同目录 `需求说明.md`（**唯一权威口径**）+ `api.md`（接口契约）。本文只做工作拆解，**不产生新口径**。
> **本文与需求说明冲突时，以需求说明为准**；需求说明内部优先级：**§11.17（E14）≈ §11.15 技术方案 > §11 其余裁决 > §12 执行要点**。

---

## 0. 起手（**不要跳过，这一节省掉的时间会在返工时十倍还回来**）

### 0.1 必读顺序

| 顺序 | 文档 | 为什么 |
|------|------|--------|
| 1 | `需求说明.md` **§11.17（E14）** | 出任务书前的最后一轮澄清，11 条裁定，**优先级最高之一** |
| 2 | `需求说明.md` **§11.15 技术方案** | 它修正了 5 条裁决、证伪了 §11.0 中 4 条"已实证事实"。不读直接照 §11.1~§11.9 实现会踩 7 个阻断级坑 |
| 3 | `需求说明.md` §11.11 **70 条验收** | 你的交付标准。**每条都可测**，其中 26 条专防静默失败 |
| 4 | `需求说明.md` §12.2 **硬约束 22 条** | 违反即返工，不接受"我觉得这样更好" |
| 5 | `docs/方案制定前必读.md` + `docs/反模式.md` AP-31/37/44/50/51/53/54 | 本任务要动 `CardSnapshotService`（129 符号共享引擎），这些坑全在那条链路上 |
| 6 | `api.md` | 接口契约，与前端的唯一约定 |

### 0.2 环境（**本机不带 profile**）

```bash
cd cpq-backend && ./mvnw quarkus:dev          # 默认 profile → 10.177.152.12:5432/cpq_db_0724
# 端口探活（本机 http_proxy 会让 curl 走代理返 502，必须 --noproxy）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
# ⚠️ /q/health 返 404 —— 未装 smallrye-health，它不是健康探针
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724
```

- 🔒 **worktree 里不要另起 dev server**（8081/5174 全会话共享），直接复用主工作区实例。
- ⚠️ 本环境 `grep` 是 `ugrep -I`，对中文注释多的大源文件**静默返空** → 用 `/usr/bin/grep -a` 复核。
- ⚠️ 探索代码优先 **codegraph**（`codegraph_context` 起手 / `codegraph_trace` 追链路 / `codegraph_impact` 评估影响面）。

### 0.3 三条最容易翻车的纪律

1. 🔒 **Flyway 版本号是移动靶**：共享库并发迁移。落库前先 `SELECT max(version) FROM flyway_schema_history`，**绝不改已应用的迁移文件**（教训 `cpq-shared-flyway-history-churn`）。
2. 🔒 **改视图 / 任何 DDL 后必须重启 Quarkus** —— `ImplicitJoinRewriter.tableColumnsCache` / `SqlViewExecutor.customerCodeCache` 是进程级缓存（CLAUDE.md「视图 DROP CASCADE / 重建后必须重启」）。
3. 🔒 **只对被升版的料号行执行重算**，哪怕输入没变也**不做整单重算** —— 引擎兜底路径会造成静默漂移（硬约束 1）。

### 0.4 包结构

```
com.cpq.priceadjust
  ├─ entity/     CustomerPriceAdjustStrategy, ElementPriceVersion, MaterialPriceReview, ...
  ├─ dto/
  ├─ service/    StrategyService, VersionGenerationService, BudgetService,
  │              MaterialVersionUpgradeService(通道B), PriceReconciler(归位), ComparisonDiffService
  └─ resource/   PriceAdjustStrategyResource, PriceAdjustReviewResource, PriceAdjustJobResource
```

---

## 1. 工作块

> **依赖顺序**：B1 → B2/B7 → B3 → B8 → B0 → B4/B5 → B9 → B6/B10/B11/B12
> B0（通道 B）是本期**最大的新建工程也最容易漏**，但它依赖 B8（口径补齐）先完成。

---

### B1 · 表结构与迁移（12 张新表 + 3 处加列）

**新增 12 张表**（语义见 §11.10 + §11.15.6，列定义以本节为准）

| 表 | 关键约束 |
|---|---|
| `customer_price_adjust_strategy` | `UNIQUE(customer_no)`；含 `enabled` / `cycle_type` / `cycle_weekday` / `cycle_day_of_month` / `cycle_nth_week` / `execute_time` / `material_scope_mode` / **`cost_diff_threshold NUMERIC(18,4) NOT NULL DEFAULT 0`**（E13：成本差额金额，**不是百分比**） |
| `customer_price_adjust_material` | 策略 × 销售料号，`UNIQUE(strategy_id, material_no)` |
| `customer_price_adjust_element` | 策略 × 元素编码，`UNIQUE(strategy_id, element_code)` |
| `customer_price_adjust_strategy_log` | 变更审计：`change_type` / `before_snapshot jsonb` / `after_snapshot jsonb` / 变更人 / 时间 |
| `element_price_version` | `status` 仅 **`PENDING` / `SUPERSEDED`**；`scheduled_slot TIMESTAMPTZ`；<br>`UNIQUE(customer_no, scheduled_slot)`（幂等 + 天然补跑）<br>`UNIQUE(customer_no, version_no)`<br>🔒 `UNIQUE(customer_no) WHERE status='PENDING'`（部分唯一索引，强约束补1） |
| `element_price_version_item` | 版本 × 元素 **一组价**（E12：报价核价共用，**不是两组**）；含 `current_price` / `previous_price` / `change_rate` / **`currency`** / `price_unit` / `no_price` / `inherited_from_previous`；`UNIQUE(version_id, element_code)` |
| `material_price_version_ref` | **料号版本指针**（客户 × 销售料号 → version_id）。`UNIQUE(customer_no, material_no)` + 覆盖索引 `(customer_no, material_no) INCLUDE (version_id)` —— **取价函数热路径** |
| `material_price_review` | 料号审核记录。`status`(PENDING/APPROVED/REJECTED/VOIDED) + **`budget_status`(QUEUED/COMPUTING/READY/FAILED)** 🆕E14-3；冗余 `customer_no` / `template_series_id` / `basis_quotation_id` / `breached_count` / `amber_count` / `missing_count` / `stale_count` / `column_count`；`INDEX(status, version_id)` / `INDEX(customer_no, status)` |
| `material_price_review_column` | 逐比对列预算明细：`column_id` / `column_label` / `threshold` / `sort_order` / 报价侧(现,调整后) / 核价侧(现,调整后) / 差异(现,调整后) / `status`(**RED/AMBER/NORMAL/MISSING/STALE**) |
| `quotation_price_revision` | 报价单 `R` 版本。`revision_no` / `based_version_id`(初版**留空**) / `upgraded_material_nos` / `first_effective_at` / `last_updated_at` / **`sealed BOOLEAN NOT NULL DEFAULT false`** / 快照 jsonb（**双侧**：`quote_card_values` + `costing_card_values` + `snapshot_rows`）<br>`UNIQUE(quotation_id, based_version_id)`（一期一版物理保证）<br>🔒 **另加"每单只能一条初版"的部分唯一索引**（`UNIQUE(quotation_id) WHERE based_version_id IS NULL`）—— PG 中 NULL 不参与普通唯一约束（D6） |
| `comparison_column_config` | 🔒 维度 = **`UNIQUE(customer_no, template_series_id)`**，不是按客户一份（§11.5.4）。`columns jsonb` 复用 task-0717 `ColumnDef` schema。**不要用 `customer_comparison_config` 这个名字** |
| `material_price_update_job` / `material_price_update_job_item` | 批次 + 明细（报价单 × 料号 × 状态 × 错误 × 重试次数 × line_item_id × **diff_value**(L3 守卫用)）。参照既有 `QuoteImportService` 的 ManagedExecutor + 进度增量写库 + REQUIRES_NEW 独立提交 |

**加列 3 处**

```sql
-- F1 并发控制（🔒 不是 JPA @Version，见 §11.15.5.2）
ALTER TABLE quotation_line_item             ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE quotation_line_component_data   ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
-- 组件三个角色字段（🔒 组件级，不进 fields 的 config JSON，绕开 AP-44）
ALTER TABLE component ADD COLUMN element_code_field     VARCHAR(100);
ALTER TABLE component ADD COLUMN element_price_field    VARCHAR(100);
ALTER TABLE component ADD COLUMN element_currency_field VARCHAR(100);
```

**全局纪律**：客户维度一律 `customer_no VARCHAR`，**禁用 UUID 外键**（对齐 task-0722 §11.11.4）。

**完成判据**：Flyway `success=t`（`SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5`）+ 所有唯一约束用 SQL 反向验证（故意插重复必须被拒）。

---

### B2 · 取价函数 `f_material_element_price` + 视图迁移（通道 A）

**函数签名**（🔄 E12 已去掉 `p_side`）
```sql
f_material_element_price(p_customer_no TEXT, p_base_date DATE)
  RETURNS TABLE(material_no VARCHAR, element_code VARCHAR,
                unit_price NUMERIC, currency VARCHAR, price_unit VARCHAR)
```

🔒 **三条硬要求**
1. **必须有 fallback 分支**：按 `(customer_no, material_no)` 查指针 → 有则取该版明细 → **无则回落 `f_customer_element_price` 的实时算逻辑**。缺了它，视图切换那一刻**所有未升版料号的元素价会全部变空**。
2. **不要改 `f_customer_element_price` 的签名**（硬约束 11）—— 它被 7 个视图在用，改签名 = 强制一次性全量迁移且无法回滚。
3. **必须返回 `currency`**：现行 `mc_view` 同时输出 `cep.unit_price AS 单价` 与 `cep.currency AS 货币`，新函数不返回 currency 会让报价单「货币」列被清空（§11.15.6 缺口 B6）。

**视图迁移范围**（E12 改判：**两侧都迁**）
- **报价侧**：实测 **7 个** `mc_view`（`SELECT count(*) FROM component_sql_view WHERE sql_template ILIKE '%f_customer_element_price%'` = 7，全属「材料成本」组件）
- **核价侧**：`物料与元素BOM` 组件视图（由技术总监提供的 Flyway 脚本落，见 B7 说明）

🔴 **JOIN 键铁律（§11.15.4.2，最容易静默失败的一处）**

> **JOIN 键必须与该视图 `hf_part_no` 的输出表达式逐字一致。**

```sql
ON  cep.element_code = ebi.component_no
AND cep.material_no  = COALESCE(cl.root_no, ebi.material_no)   -- 闭包形态
AND cep.material_no  = ebi.material_no                          -- 平铺形态
```

**实测两种形态确实并存**（`SELECT DISTINCT substring(sql_template from '[^ ,\n]+[ ]*AS[ ]*hf_part_no')` 返回 `ebi.material_no AS hf_part_no` 与 `...ebi.material_no) AS hf_part_no` 两种）→ **迁移脚本必须逐视图按各自表达式改写，禁止统一字符串替换**。写错的后果：闭包展开的子件行 JOIN 不中 → **子件元素价不吃版本**，且**完全静默**（验收 #31 专测）。

**其他**
- 迁移**必须可逆**（保留 `sql_template` 原文备份列或备份表）。
- 别名规范定死 `cep`，但**推导代码仍动态捕获**（D7）。
- 🔒 **改完必须重启 Quarkus**，并用含料号的端点验证返单值（不是数组）。
- ⚠️ **必须同步更新 `dev-docs/rule-0724-组件模板配置/3-SQL视图.md` 与 `报价侧.md` 的配方**（加 JOIN 键铁律 + 别名 `cep` + 三个角色字段必配）—— 那是配置 Agent 的唯一入口，不改配方 = 新配的模板必然走回旧路（风险表「迁移后的漏配」）。

**复核两处缓存**（硬约束 13）
- `DataLoader.java:276-308` 批量合桶入口会把多 partNo 合成 `hf_part_no = ANY(:hfPartNos)` —— 新函数按 `material_no` JOIN 后，**结果分发回各 task 的配对逻辑必须按 task index 而非 backend key**（AP-37 教训）。
- `SqlViewExecutor` 的 `customerCodeCache(:412)` / `priceBaseDateCache(:438)`。

**验收归属**：#31（子件吃版本）

---

### B3 · 定时扫描 + 版本生成

**定时任务**
```java
@Scheduled(every = "1m", concurrentExecution = SKIP)   // 🔒 D4 每分钟；SKIP 必须带
```
- 现有 **7 个** `@Scheduled` **全部未配 `concurrentExecution`**（§11.15.8#6），本期新增任务**必须带 SKIP**，否则验收 #4「同一周期点只生成一次」判不出对错。
- **幂等 + 补跑**：靠 `UNIQUE(customer_no, scheduled_slot)` 插入冲突即跳过 —— 服务重启错过时刻，下次扫描自然补跑。
- 周期边界：每月 31 号小月**顺延至月末**；第 N 周不足按**当月最后一个该星期几**。

**版本生成**
1. 🔒 **前置校验（E14-10）**：参与调价元素为空、或勾选元素**全部**既无本期价也无历史价 → **不生成任何版本**（定时静默跳过 + 屏 1 提示；手动调用返 `400 STRATEGY_NO_ELEMENTS`）。
2. 版本号 `V + YYMMDD + 两位当日流水`，流水按**客户 + 日期**计数。**不用自增整数**（硬约束 8）。
3. 快照该策略约定的**全部元素**当期价（**一套**，E12），逐元素标涨跌。
4. **无价处置（§11.3.2.1）**：本期无价但有上一版价 → **沿用上一版价** + `inherited_from_previous=true`；本期无价且从无历史价 → `no_price=true`。两类都标「无价」不可审，**但都不阻塞料号升版**。
5. 上一版整体转 `SUPERSEDED`；🔒 **其名下未完成的 `job_item` 一律置 `STALE` 终态、不能再重试**（§11.6.3.2）。
6. 🔒 **只写版本 + 明细即返回**，预算投后台队列（E14-3）。

**手动生成**：`POST /price-adjust/versions/generate` **走同一个服务方法**（验收 #5 / #67③）。二次确认只在前端；服务端仅凭 `confirmSupersede` 决定是否放行，**不得分出第二条生成逻辑**。

**验收归属**：#4 #5 #6 #7 #62 #67 #68

---

### B4 · 预算计算（异步）+ 比对算法移植

#### B4.1 待办池成员判定

| 规则 | 口径 |
|------|------|
| 进池前提 | 料号 ∈ 策略范围 ∧ 本期相关元素价与**该料号指针当前指向的那一版**有差异（🔒 裁决 39 的"上一版"锚点已改判，§11.5.5 补丁 1） |
| 判断依据单 | 🔒 **版本生成那一刻**确定并写入 `basis_quotation_id`，之后**不随新建单变化**（E11-6）。取该料号**建单日期倒序首张活单** |
| 🔒 「活单」定义 | **= 5 个可更新状态**（`DRAFT`/`SUBMITTED`/`APPROVED`/`REJECTED`/`COSTING_REJECTED`）。**用一个 `ACTIVE_STATUSES` 常量贯穿全实现**（E14-2） |
| 无活单料号（D5） | **不进待办池，但指针照常推进**到本期版本。🔒 **反例外**：`material_price_review` 里该客户×料号存在 `REJECTED` 记录（不限本期）→ **不适用自动推进**（§11.5.5 补丁 2） |

#### B4.2 预算算法（dryRun）

**入口**：`MaterialVersionUpgradeService.execute(..., dryRun=true)` —— 与执行**同一段代码**，只差最后落不落库（§11.15.1）。这使裁决 41「预算与实际逐位一致」**从靠纪律变成结构上不可能违反**。

执行 S1~S6 全在内存 → 得到调整后的报价侧 SUBTOTAL 与各比对列值 → 逐列落 `material_price_review_column` + 冗余汇总列。

🔒 **禁止在预算阶段触发整单 `ensureCardValues`**（硬约束 4）—— `ComparisonViewService.getData` 的 live 分支会调它（`:285-296`），有污染未升版料号的风险。取不到就如实标 `MISSING`。

#### B4.3 比对差异/着色算法（**后端新建**）

⚠️ **§11.0#7 的"直接复用"只对取值成立** —— 差异/阈值/着色 **100% 在前端**（`comparisonMapping.ts:84-115`），后端**连 `ColumnDef` 类型都没有**（`ComparisonConfigDTO.java:20` 注释"后端只存不解释内容"；`com.cpq.basicdata.v6.maintenance.dto.ColumnDef` 是**同名不同物，别误用**）。

**任务**：移植 `comparisonMapping.ts:84-115` 的 `getColumnValue` / `computeDiff` / `classifyDiff` 到后端，**并补后端单测对齐 `comparisonMapping.test.ts` 的黄金用例**。

**着色判据（E13 终态，单一干净）**
```
RED     ⟸ 差异（成本差额）< 0                    计入 breached_count
MISSING ⟸ 任一侧取不到值                        🔒 计入 breached_count（E4）
AMBER   ⟸ 0 ≤ 差异 < threshold                  计入 amber_count
STALE   ⟸ componentId/metric 因模板改版失效      🔒 不计入 breached/amber（否则模板改版整池飘红）
NORMAL  ⟸ 其余
```
- **「成本差额预警线」= 该客户 `cost_diff_threshold`（金额）**，作用于产品总价列。**不算毛利率**（E13）。
- 🔒 **整行标红 ⟺ `breached_count > 0`**，**不是**「产品总价差异 < 0」（硬约束 19 / 验收 #25）。
- 比对列定位：料号 → 依据单 → `quotation.customer_template_id` → `template_series_id` → 查 `comparison_column_config`；未配则用默认「产品总价」列（`kind=PRODUCT_TOTAL` **不依赖 componentId**，跨模板通用）。

#### B4.4 异步管道（E14-3）

- 版本生成 / 四项配置变更（比对列 / 预警线 / 元素清单 / 料号范围）**共用同一条队列**。
- `material_price_review.budget_status`：`QUEUED → COMPUTING → READY | FAILED`。
- 🔒 **`budget_status != READY` 的料号不可审核**（服务端兜底 `409 REVIEW_BUDGET_NOT_READY`，前端按钮置灰）。
- 🔒 **单料号预算失败不连累同版其他料号**；该料号标 `FAILED` 可单独重算。
- 🔒 **`已通过` / `已驳回` 料号不重算**（审核依据必须可追溯）。

**验收归属**：#9 #10 #23 #24 #26 #27 #38 #39 #49 #50 #57 #65

---

### B8 · 后端 SUBTOTAL 口径补齐 + 对拍清单（**S6 的前置，必须先做**）

#### B8.1 补两处

| # | 口径差 | 补法 | 实测影响面 |
|---|--------|------|-----------|
| 1 | `productAttributes` 传空 map → `product_attribute` token 恒取 0（`CardSnapshotService` 调 `FormulaCalculator.calculate` 时第 9 参为 `new HashMap<>()`） | 从 line item / 模板 snapshot 的 `productAttributes` + `productAttributeValues` 构造 NUMBER 型 map 注入（对齐前端 `QuotationStep2.tsx:1302-1307` / `evalProductSubtotalFromSubtotals`） | **当前库 0 使用**（0 组件引用、0 模板配 schema）→ 纯防御 |
| 2 | `${key}#__amount_total__` 哨兵键未登记 → `[页签(总计)]` 公式两端不等值 | 🔒 **搬既有做法，不新写**：`ComponentDataEffectiveRows.java:198-199` 已实现（`code#__amount_total__` / `name#__amount_total__` = Σ金额列(`is_amount`)），把同一逻辑接进 `CardSnapshotService.backfillSubtotalsFromResolved` 那条路径 | **5 个报价模板 + 核价模板1 全在用**，涉 26 张单 → 真实影响 |

⚠️ 注意 `CardSnapshotService:1806` 已有「不得让 `__amount_total__` 泄漏进 `subtotalByColumn`」的排除逻辑 —— **补登记后这行必须保留**，否则污染快照 + golden 漂移。

#### B8.2 🔒 交付物：《SUBTOTAL 双端对拍清单》

**合并前必交，技术总监确认后才允许合并、才允许 S6 真正写回。**

内容：逐 line item 列出

| quotationNo | materialNo | 后端新算 SUBTOTAL | 前端已落库 `li.subtotal` | 差异 | 定性 |
|---|---|---|---|---|---|

**差异非 0 的每一行必须给定性**：`口径差已修复` / `历史时点漂移`（`li.subtotal` 是某次前端保存写的，卡片值是后来重算的）/ `其他（说明）`。

> 📌 技术总监已做的小样本（11 行）：9 行一致，**2 行对不上**（`li=26` vs 卡片 `14`；`li=755.93` vs 卡片 `214`）。**未定性前不得当作"后端算错"的结论。**

#### B8.3 影响面提醒

后端算的 SUBTOTAL 还被 **task-0717 比对视图报价侧「产品总价」与核价侧**消费 → 补齐后这些地方显示的数会变（朝正确方向）。对拍清单是"到底动了哪几张单"的唯一答案。

**验收归属**：#69（与 B9 共同）

---

### B0 · 通道 B · 升版重算通道（**本期最大的新建工程**）

单一入口 `MaterialVersionUpgradeService`，执行单位 =「**报价单 × line item**」。

| 步 | 内容 | 关键约束 |
|---|------|---------|
| **S0** 🆕 | **L3 口径守卫**（E14-11） | 用**旧价**跑一遍后端引擎，`\|结果 − li.subtotal\| > 阈值(默认 0.01,可配)` → 该单标 `FAILED/SUBTOTAL_MISMATCH`，**不写回**、记 `diff_value`、可重试。**同批其他单照常执行** |
| **S1** | 读版本价 | 从 `element_price_version_item` 读**一套**元素价 → `Map<elementCode,{price,currency}>`。🔒 **不走视图、不走函数** —— 版本明细就是权威快照 |
| **S2** | 定位价格字段 | 🔒 **直接读组件三个角色字段**（`element_code_field` / `element_price_field` / `element_currency_field`）。**运行期禁止正则解析 SQL**（§11.15.3 改判） |
| **S3a** | 字段级写回 `snapshot_rows`（driver 行） | 按 rowKey 对齐，**只改价格/货币两个键**；行集合不动（不增不删不换序）。🚨 **同一元素必然多行**（行键 =「料号+材质+元素」三元组，BOM 闭包展开的不同子件/材质都可能含 Ag）—— **实现成一对一会静默漏改**（验收 #33） |
| **S3b** | 字段级写回 `row_data`（**手动行**） | 按 `_origin === 'manual'` **显式判定**（🚨 **禁止靠下标区分**，"手动行恒在尾部"只是前端纪律，AP-54 同族）；按**元素列的值**匹配 `element_code`；对不上就**不动**（不做名称→编码兜底） |
| **S4a/S4b** | 清手工值 | 删除 `row_data` / `editRows` 里**价格字段这一个键**；🔒 **其余手改字段（毛重/损耗率…）原样保留**；🔒 **不得清元素字段**（否则两次升版结果漂移，坑3/验收 #34） |
| **S5** | 重算卡片 | 走既有 `buildCardValues`，**报价侧 + 核价侧都算** |
| **S6** | 写回行金额 | 从新 `quoteCardValues` 提取报价侧 SUBTOTAL → 写 `li.subtotal` → `LineDiscountService.recompute(li)`。🔒 **用 `CostingSubtotalUtil.extractUnitSubtotal(String)`，只提可见性/改中性命名，不要新写一份** |
| **S7** | 失效导出快照 | `li.quoteExcelValues = null`（`ExcelViewService.exportExcelView:782-786` 已有 fallback 重算，**无需新增重算代码**；实现前确认 fallback 取数源是 `quote_card_values`，即 P4） |
| **S8** | 聚合单据 | `quotation.total_amount` + 税额 |

**`dryRun=true`** → S1~S6 全在内存、不写库 = **审核页的预算**。

#### 为什么 S3 是关键（缺口 A1）

- 元素单价是 `INPUT_NUMBER` + `basic_data_path` → 冻结在 `quotation_line_component_data.snapshot_rows`
- `CardSnapshotService.buildCardValues` 的 baseRows **来自 `snapshot_rows`，不重跑 driver expand**
- **`CardSnapshotService` 全类只读 `snapshot_rows`，从不写**

> 🔒 **升版必须写 `snapshot_rows`**。只重算 `quote_card_values` 不写 `snapshot_rows` = 升版当时看着对，**销售存一次草稿就静默退回旧价**（`saveDraft` 置 `quoteCardValues=null` → `ensureCardValues` 从旧 `snapshot_rows` 重建）。这是本期最隐蔽的失败模式，验收 #28 专测。

#### 并发保护（F1 终版）

```sql
UPDATE quotation_line_component_data
   SET snapshot_rows = ..., row_version = row_version + 1
 WHERE line_item_id = :lid AND component_id = :cid AND row_version = :seen
```
受影响行数 = 0 → 标「冲突」态，不写回、可重试。
🔒 **不是 JPA `@Version`** —— 这两张表的 `snapshot_rows` / `row_data` 现存写入口 **100% 是原生 SQL**（`ConfigureSnapshotService:1028/:1056/:1345/:1352/:1372/:1381`、`QuotationTreeService:300`、`CardSnapshotService:2306` REQUIRES_NEW），原生 UPDATE 既不 bump 也不校验 `@Version`（§11.15.5.2）。

#### 活单白名单（E14-2）

```java
// 🔒 只有这 5 个。禁止写成"排除 EXPIRED/CANCELLED，其余全更新"——那会把 SENT/ACCEPTED 卷进来
Set<String> ACTIVE_STATUSES = Set.of("DRAFT","SUBMITTED","APPROVED","REJECTED","COSTING_REJECTED");
```
**同一常量同时服务**：升版更新范围 / 裁决 33 判断依据单选取 / D5「不在任何活单」判定。

#### `R` 版本（裁决 30 + F4 + E11-5）

- **初版**：建单（首次保存且已有产品行）即创建记录标 `sealed=false`，🔒 **snapshot 留 NULL 不物化**（性能纪律，屏 7 渲染时 NULL ⟹ 取当前值）；**首次升版时**把升版**前**的当前值物化进去 + `sealed=true`，从此冻结。`based_version_id` 留空。
- **本期 R**：存升版**后**状态（F4）。同一 `V` 版内多次料号升版 → **合并进同一 `R`**（更新时间 + 追加已升版料号），🔒 **每次并入都必须用当前(升版后)状态覆写双侧整单快照**（E11-5，否则切版预览"先通过的新价、后通过的旧价"说谎，验收 #63）。
- 快照内容 = **整单双侧**：`quote_card_values` + `costing_card_values` + `snapshot_rows`（§11.7.0）。

#### 核价侧（裁决 41/42）

- 只动 `quotation_line_item.costing_card_values`。
- 🔒 **核价单 `costing_order` 的 frozen 快照逐字节不动**（验收 #21 逐字段断言）。
- 🔒 **不碰 `:versionFilter` 宏 / `VersionFilterMacro`**（不做清单 #16，那是另一条轴）。

**验收归属**：#12 #14 #15 #16 #17 #21 #22 #28 #29 #30 #31 #33 #34 #35 #46 #55 #56 #63 #64 #70

---

### B5 · 审核 API + 异步更新任务

- 审核端点见 `api.md` §2；`reject` 的 `reason` **必填**。
- 通过 = **同步推进指针** + **异步建 job**（`202`）。
- 异步执行参照既有 `QuoteImportService`：`ManagedExecutor` + 进度增量写库 + `REQUIRES_NEW` 独立提交（逐单独立事务，一单失败不回滚全批）。
- 三种非成功态：`FAILED`（数据问题，含 L3 `SUBTOTAL_MISMATCH`）/ `CONFLICT`（`row_version` 不匹配）/ `STALE`（所属版本被取代，**终态不可重试**）。
- 🔒 指针推进纪律：**不因个别单失败而回退整个料号的指针**（§11.6.3.2）。

**验收归属**：#8 #11 #12 #13 #19 #52 #61

---

### B6 · 比对列配置 CRUD

- 维度 `UNIQUE(customer_no, template_series_id)`；未配返默认「产品总价」列（`removable:false`）。
- 🔒 **唯一写入口**，屏 4 只读（不提供修改端点）。
- 保存后重算范围 = **该「客户 × 模板系列」下的 `待处理` 料号**（不是该客户全部），走异步管道。

**验收归属**：#23 #24 #38 #39

---

### B7 · 组件三项显式绑定

- 实体 + DTO + `api.md` §5.1 的请求/响应扩展。
- 🔒 **保存期校验**：`sqlTemplate` 检测到取价函数 → 前两项必填，否则 **`400` 拒绝保存**（不是警告、不是静默保存）。
- **迁移期自动推导预填**：按 §11.15.3.4 五步算法（动态捕获别名 → `<别名>.unit_price AS <列名>` → `fields.default_source.path == "$<view>.<列名>"` → `ON <别名>.element_code = <表达式>` → `<表达式> AS <列名>`），推导结果**预填后需人工确认**，不自动固化。
- 可选强化：元素列一致性体检（取真实数据逐个到 `element.element_code` 找，找不到警告）。

> 📌 **本块只做代码。核价 `物料与元素BOM` 组件的实际配置由技术总监提供 Flyway 迁移脚本**（E14-6），后端工程师**不碰配置数据**。

**验收归属**：#32

---

### B9 · 双端公式一致性三层机制（🔴 **业务方特别强调的重点检查项**）

#### L1 · 共享黄金用例集

- 位置：仓库根 `formula-golden/*.json`，**前端 vitest 与后端 JUnit 读同一份文件**（非各自维护副本）。
- 🔒 **权威方向：`expected` 由前端引擎产出，后端必须命中。** 前端是当前真正在给客户报价的那套。
- **必须覆盖 10 类**：四则/优先级/括号 · `component_subtotal` 一阶 · `component_subtotal` 二阶 · **`__amount_total__` 页签总计** · **`product_attribute`** · `cross_tab_ref` · `global_variable` · 单位换算 · 空值/NULL/除零 · 小数精度 4 位。
- **现状实测**：前端 `formulaEngine.test.ts`(756 行) 与后端 `FormulaCalculatorTest.java`(465 行) **零共享用例**（前端测 `cross_tab_ref`×9 后端 0 条；后端测 `global_variable`×3 前端 0 条）。
- 🔒 **反向验证**：故意把后端某处口径改错 → 对应黄金用例**必须变红**（证明这套测试有拦截力，不是摆设）。

#### L2 · 全库真实数据对拍 → 见 B8.2

#### L3 · 升版运行时守卫 → 见 B0 的 S0

**验收归属**：#69 #70

---

### B10 · 价格列归位机制（缺口 A8）

**归位 = 把 §11.4.1 取价优先级表幂等地应用到每一行的价格列。**

| 条件 | 写什么 | 清手工值 |
|---|---|---|
| 元素 **∉** 调价清单 | **不动**（整行一个字节都不碰） | 否 |
| 元素 ∈ 清单 且 指针**有值** | 该版本价 | ✅ |
| 元素 ∈ 清单 且 指针**为空** | task-0722 **实时算**。🔒 **基准日 = 该报价单创建日期**（`created_at::date`，与通道 A 逐字同源，`SqlViewExecutor:443`）。**禁取"执行当天"** | ✅ |

🔑 **「清手工值」的条件是"元素 ∈ 清单"，与指针有无值无关** —— 与 S3 的写入条件不同，**不要合并成一个 if**。

**作用域三条件（E11-4，全部成立才归位/才只读）**
```
① 元素 ∈ 该客户「参与调价元素」清单
② 料号 ∈ 该客户调价策略料号范围（"所有料号"模式恒真）
③ 该客户调价策略处于启用状态
```
🔒 范围外 / 停用客户的行**一个字节都不碰**。
⚠️ **「取价」与「可编辑性」是两套口径**：裁决 5「料号范围不影响新单取价」**不变**；本条改的是可编辑性与归位作用域。

**三个时机，同一段代码**（`PriceReconciler`，幂等单元）

| 时机 | 入口 | 备注 |
|---|---|---|
| 升版 | `MaterialVersionUpgradeService`（S3/S4 本体） | — |
| **保存** | `saveDraft` | 纠正陈旧页面提交的旧价 |
| 单元格失焦 | `quote-card-edit` | ⚠️ `editCardValue` **仅草稿态生效**（`CardSnapshotService:2262`）；且它**不写 `snapshot_rows`**，归位要落 driver 行须在此加 S3a 写点，会碰到既有 flush/clear/重读托管实体逻辑（`:2308-2315`）—— **本块唯一需要小心的地方** |

🔒 **保存流程中的插入位置（严格不可变通）**
```
row_data 落库 / snapshot_rows 重建  →  ★归位★  →  quoteCardValues=null → ensureCardValues 重算
```

🔒 **性能红线（E14-7）**：`saveDraft` 增量 **≤ 300ms**。归位必须**整单一次预取**（策略 / 元素清单 / 料号范围 / 指针 / 版本明细 **各查一次**），**禁止逐行查库**。
**交付必附**：同一张单「开归位 vs 关归位」各 3 次 saveDraft 耗时（取中位数）对比。

**顺手加固**（建议，零成本）：`mergeRowDataInputsIntoEdits` 把隐式的 `i < min(baseRows.size, rowData.size)` 改为显式 `skip if rdRow._origin === 'manual'`。

**输出给前端的标记**：在返回的 cardValues 行上打 `__priceLocked` / `__priceVersion`（判定全在后端，前端纯读）。

**验收归属**：#40 #41 #42 #43 #44 #45 #47 #51 #59 #60 #66

---

### B11 · 通知（E14-8）

复用 `NotificationService`：
- **每张受影响报价单的创建人**：内容含「此前手工调整可能已被覆盖，请复核」（兑现裁决 37 补偿措施）。
- **触发本次升版的财务**：整批汇总（成功 N / 失败 M / 冲突 K）。
- 🔒 **失败项两边都必须可见**（"以下 2 张单未能自动更新，请联系财务重试"）。销售只收自己那几张单的，不收全局。

**验收归属**：#52④

---

### B12 · E12 清理 task-0722 `_GLOBAL_` 残留

| # | 位置 | 处置 |
|---|------|------|
| 1 | 前端 `PricingStrategy.tsx:21/278-291` 固定「全局（核价成本口径）」项 | **删除**（归前端，见 fronttask） |
| 2 | 前端 `ElementPriceStrategyTab.tsx:171` 提示 | **删除**（归前端） |
| 3 | 核价侧元素组件视图取价 | 实测**无一处传 `_GLOBAL_`**（核价元素组件是空壳）→ 实际是 §11.8.3 的**从无到有接客户取价**。若他库确有传 `_GLOBAL_` 的，一并改 `:customerCode` |
| 4 | `element_price_strategy` 的 `_GLOBAL_` 行 | 实测 **0 条**，无需迁移 |

🔒 这几项与 E12 其他实现（指针两侧共用 / 一组价 / 核价写回 / 核价组件补字段）**是一体的**，须一并做 + 测试。

**验收归属**：#64

---

## 2. 验收归属（后端）

### 2.1 后端自测（交付前必须全绿）

`#3 #4 #5 #6 #7 #8 #9 #10 #11 #12 #13 #15 #17 #19 #21 #22 #23 #24 #26 #27 #29 #30 #31 #32 #33 #34 #35 #36 #37 #38 #39 #44 #45 #46 #47 #49 #50 #51 #56 #57 #59 #60 #61 #62 #63 #64 #65 #66 #67 #68 #69 #70`

### 2.2 跨端条目（**后端主导 · 前端配合**，任务书里附复现步骤）

| # | 条目 | 后端职责 | 前端职责 |
|---|------|---------|---------|
| #14 | 隔离性双向断言 | 提供 A 变 B 不变的 SQL 断言脚本 | — |
| #16 | 冲突保护 | `row_version` 判据 | 制造不走 saveDraft 的编辑路径 |
| #25 | 行标红判定 | `rowRed` / `breachedCount` 落库正确 | 渲染确实按 `rowRed` 而非产品总价 |
| #28 | 升版不被 saveDraft 回滚 | S3 写 `snapshot_rows` | 执行一次真实保存 |
| #40 #41 | 陈旧页面保存不退价 | 归位 | 🚨 **测试顺序不可颠倒**：必须"先打开页面 → 再升版 → 用旧页面保存" |
| #42 #43 | 单价列只读态 / 元素↔单价联动 | 打标记 | 渲染只读文本 + 徽标 |
| #48 | 成本差额预警线 | 阈值判定 | 着色 |
| #52 | 更新失败可找回 | job/job_item | 更新任务页 + 屏 7「尚未更新」 |
| #53 | 初版定型 | sealed 逻辑 | 轨迹表渲染 |
| #55 | 切版预览双侧都是历史值 | 快照双侧返回 | 预览态渲染 |
| #58 | 取消勾选元素弹确认 | 409 + 影响面 | 二次确认 UI |

### 2.3 技术总监亲验（不接受口头"已通过"）

`#14`（隔离双向）· `#21`（核价联动 + `costing_order` 冻结）· `#28`（saveDraft 不回滚）· `#40 #41`（陈旧页面）· `#64`（两侧同一套价）

---

## 3. 强制自检（每次改动结束前，缺一不可）

```bash
# 1. 后端编译 + 重启
touch cpq-backend/src/main/java/com/cpq/<任一>.java   # 等 5-7s
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components  # 期望 401，不要 500
# 2. Flyway
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 \
  -c "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5"   # success=t
# 3. 单元测试（🔒 worktree 里必须在 worktree 的 cpq-backend 跑，不要 cd 主仓）
cd cpq-backend && ./mvnw test
# 4. 协议级改动必跑 E2E（本任务动了 CardSnapshotService，属协议级）
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```

- 🔒 **改视图/DDL 后必须重启 Quarkus**（进程级缓存）。
- 🔒 **不要手工 `psql -f V_xx.sql`** —— 让 Quarkus dev 自己跑 Flyway。
- ⚠️ E2E 夹具可能失效（BL-0078 硬编码 quotationId）→ **判回归必须 A/B 同型对比**（在 master 上跑同一 spec 看是否同样失败），否则会把环境噪声当真回归。
- 🔒 **任何"完成"宣告必须带一行"已自检"声明**，没有 = 未完成。

---

## 4. 交付物清单（缺一不可）

| # | 交付物 | 依据 |
|---|--------|------|
| 1 | 代码 + Flyway 迁移（12 表 + 3 处加列 + 函数 + 视图迁移） | B1/B2 |
| 2 | 🔒 **《SUBTOTAL 双端对拍清单》** —— 差异非 0 逐行定性 | B8.2 / E14-4 |
| 3 | 🔒 **`formula-golden/*.json` 共享黄金用例（10 类）+ 后端 JUnit 读同一文件全绿 + 反向验证证据** | B9 / E14-5 |
| 4 | 🔒 **归位性能实测**：同一单「开/关归位」各 3 次 saveDraft 耗时对比，增量 ≤ 300ms | B10 / E14-7 |
| 5 | 验收 §2.1 全绿的执行记录（SQL 断言输出 / 测试日志） | — |
| 6 | E2E `quotation-flow` + `composite-product-flow` 双 spec 结果（含 A/B 对照说明） | AP-44 / §12.3.3 |
| 7 | `dev-docs/rule-0724-组件模板配置/3-SQL视图.md` + `报价侧.md` 配方更新（JOIN 键铁律 + 别名 `cep` + 三角色字段） | B2 风险表 |
| 8 | `docs/RECORD.md` 追加开发记录 | CLAUDE.md 质量保证规范 4 |

---

## 5. 明确不做（一条都不要"顺手"加回）

§11.12 **17 条不做清单**全部适用。**特别提醒后端**：

- ❌ 不做抓价爬虫 / 不做报价单编辑锁（用 `row_version` 不是新锁机制）
- ❌ 不做手工值保护 / 已改标记 / 恢复策略价
- ❌ **不做「是否优先使用客户价格调整策略」勾选框**，数据模型**不得**为它预留列（如 `use_price_version`）
- ❌ **不碰 `element_price_strategy` / `element_daily_price` / `element_price_source`**（task-0722 既有，不动）
- ❌ **不碰核价单 `costing_order` frozen 快照 / `VersionFilterMacro` / `:versionFilter` 宏**（BL-0006 另立项）
- ❌ 不做待办池导出功能
- ❌ **不改 `f_customer_element_price` 签名**

---

## 6. 有疑问时的决策顺序

1. 查 **§11.17（E14）** → 有则照做
2. 查 **§11.15 技术方案** → 有则照做
3. 查 **§11 裁决** → 有则照做，**但先看 §11.15.7 有没有被修正**
4. 查 **§11.12 不做清单** → 在清单里则**不做**
5. 查 **§11.0 现状盘点** → **必须连 §11.15.8 一起读**（7 条里 4 条已被证伪）
6. 仍不明确 → **问技术总监，不要自行假设**

> 本任务经历三次结构性修正 + 两起"改判后旧裁决未清理" + 两次"新方案地基不成立"。**自行假设的代价很高。**
> 📌 **对 §11.15 / §11.17 里任何一条判断存疑，请自己去读一遍代码验证** —— 本文档里被证伪的那些"已实证事实"，当初也是这么写上去的。
