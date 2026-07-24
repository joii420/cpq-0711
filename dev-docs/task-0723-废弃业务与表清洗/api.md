# 接口文档 · 废弃业务与表清洗（task-0723）

> 隶属：`dev-docs/task-0723-废弃业务与表清洗`
> 需求依据：同目录 `需求说明.md`（§4 审计 + §9 施工顺序）
> 本任务性质是**清理**，接口层以「下线端点 / 端点内部改读 V6」为主，**无新增业务端点**。
> 契约有歧义时以本文为准。

---

## 0. 全局约定

### 0.1 本任务分 7 个阶段一次性交付

阶段编号沿用需求说明 §9，全部在**同一 worktree 分支**上完成，最后统一验收。各阶段对接口的影响见 §1~§7。

### 0.2 「删除」全部改为「改名 `_drop`」—— 可逆软删除

**表与视图不执行 `DROP`**，一律 `ALTER TABLE/VIEW ... RENAME TO xxx_drop`。理由：

- PostgreSQL 的 `ALTER ... RENAME` 可逆，改回即回滚
- 漏网的活代码会**大声报 `relation "xxx" does not exist`**，而非静默取空——这正是我们要的验证信号
- ⚠️ 但 `RENAME` **不破坏依赖它的视图**（视图按 OID 绑定，会跟着改名走）。所以视图必须**先于底表改名**（阶段 5 先于阶段 7），否则视图会静默照常返数，改名验证对它失效

**Java 代码**（Resource / Service / Entity）该删的仍是真删——代码不存在"改名软删"一说。

### 0.3 三个必读的排查纪律

1. 判"无引用"必须用 `/usr/bin/grep -a`。本机 `grep` = `ugrep`，会把中文注释多的大文件**静默当二进制返空**（记忆 `cpq-grep-ugrep-binary-pitfall`）。
2. 判 BNF 路径引用必须用**点号语法** `逻辑名.列` 正则，不能裸子串（需求说明 §10 记录过三次误判）。
3. 实体表（有 `@Table`）的消费方要用**实体类名**查（Panache 走类名，grep 表名字符串看不到）。删代码前对关键类跑 `codegraph_impact` 复核。

---

## 1. 阶段 1 · 止血（前端 1 行）

摘除前端「V5 增强导入」按钮入口（`ImportHistoryList.tsx:146`）。**无接口变更**——后端端点在阶段 3 才删，本阶段只切断 UI 触达。

---

## 2. 阶段 2 · 报价热路径（= BL-0069）

### 2.1 漂移检测整体下线

**决策**：漂移检测（基础数据升版→草稿单过期提醒）**0/321 报价单有值，自上线从未生效过一次**（实测 `referenced_versions` 全空 + 现役键组合命中冻结表 0/22）。且 V6 表版本列不统一、业务键无客户维度，无法平价迁移。**整体下线**，`referenced_versions` 列保留（历史兼容，反正全空）。

**下线端点**：

```
POST /api/cpq/quotations/{id}/refresh-versions      → 删（DriftDetectionService 唯一对外入口之一）
```

**改造端点**（不删，删其内部对漂移检测的调用）：

```
GET  /api/cpq/quotations/{id}            getById 内不再调 driftDetectionService.detect()
POST /api/cpq/quotations/{id}/save-draft saveDraft 内不再调 collectReferencedVersions()
POST /api/cpq/quotations/{id}/submit     submit   内不再调 collectReferencedVersions()
```

响应 DTO 变化：`QuotationDTO` 的 `driftDetection` 字段恒返 `{hasDrift:false, driftedRecords:[]}` 或直接移除（前端同步删横幅，见 fronttask F2）。

### 2.2 客户料号三字段改读 V6

**改造**：`QuotationService.loadLineItems`（`:2491`）读客户料号名/图号/产品编号的 SQL 从 V44 `mat_customer_part_mapping` 改为 V6 `material_customer_map`，**全键严格匹配**：

```
customer_no = customer.code  AND  material_no = 料号
```

- 匹配键 = `(customer.code, material_no)`，**不兜底**（不做仅料号降级——会重现森萨塔跨客户串号）
- 匹配不到 → 三字段留空（与现状一致，但至少能匹配到的 21/22 有值了）
- 无接口签名变化，只是同一端点返回的字段值来源变了

> ⚠️ 验收标准修正：需求说明 §8 第 3 条「三字段**非空**」**不可达**（`material_customer_map` 127 行里只有 1 行有客户品名）。改为「**取数正确**：能匹配到映射行时值与表一致，表中为空则显示空」。

### 2.3 料号版本族整族下线

**下线端点**（`PartVersionService` 读写 `mat_customer_part_mapping` + `mat_part_version_log`，对 V6 料号 `part_version_locked` 恒 2000，从未生效）：

```
GET  /api/cpq/part-version/{cpn}/{hf}         → 删
POST /api/cpq/part-version/**（切版本/升版）  → 删（PartVersionService 全部端点）
```

`part_version_locked` 列保留（138/138=2000，历史兼容）。前端删版本抽屉 + 孤儿页（fronttask F3）。

### 2.4 提交快照客户料号块

`SnapshotCollectorService`（`:315`）读 `mat_customer_part_mapping` 的块，与 2.2 同源同键 → 改读 `material_customer_map` 全键匹配。无接口变化。

---

## 3. 阶段 3 · V5 / import-session 死链路退役

**下线端点**：

```
POST /api/cpq/import/basic-data/v5/preview       → 删（BasicDataImportV5Resource）
POST /api/cpq/import/basic-data/v5/confirm        → 删
POST /api/cpq/quotations/{id}/reimport-basic-data → 删（QuotationService.reimportBasicData）
ALL  /api/cpq/import-session/**                    → 删（ImportSessionResource 全部端点）
```

**退役前置核查（阻塞项）**：`basicdata.v6` 正式导入路径（`QuoteBasicDataImportV6Drawer` → V6 handler + `VersionedV6Writer`）**不得经过** `ImportSessionService` / `StagingWriter` / `BasicDataImportServiceV5`。
- `StagingWriter:63` 复用了 `BasicDataImportServiceV5.parseExcel`，但那是 **import-session 自己的 staging flow**，随 import-session 一起退役
- 用 `codegraph_trace` 从 V6 导入 Drawer 追到后端，确认落点是 `VersionedV6Writer` 而非 `StagingWriter`，**通过后才能删**

**保留**：`basicdata.v6` 全部端点、`VersionedV6Writer`。

---

## 4. 阶段 4 · 旧核价引擎退役

**下线端点**：

```
ALL /api/cpq/costing-part/**      → 删（CostingPartDataResource，孤儿页宿主）
ALL /api/cpq/costing-basic/**     → 删（CostingBasicDataResource）
ALL /api/cpq/costing-summary/**   → 删（CostingSummaryResource）
ALL /api/cpq/costing-templates/** → 删（CostingTemplateResource，17/17 零绑定）
GET /api/cpq/quotations/{id}/costing-sheet → 删（CostingSheetResource 内此方法）
```

⚠️ **方法级精确摘除，勿删整个 Resource**：

| Resource 文件 | 处置 |
|---|---|
| `CostingSheetResource`（`@Path("/api/cpq/quotations")`） | **只删 `getCostingSheet` 方法**；`getComparison` / `exportComparison`（比对视图，task-0717 活功能）**保留** |
| `costing` 模块的 `ComparisonViewResource` / `ComparisonViewService` / `ComparisonExportService` | **全保留**（活功能） |

**保留的活端点（勿误伤）**：

```
GET  /api/cpq/quotations/{id}/comparison         ← 比对视图（task-0717）
POST /api/cpq/quotations/{id}/comparison/export
GET  /costing-summary（前端菜单）→ CostingOrderListPage ← 财务核价工作台（286 单活跃）
```

> ⚠️ **命名撞名陷阱**：菜单「核价管理」路由 `/costing-summary` 走的是 `CostingOrderListPage`（**新引擎**财务工作台），与要下线的**旧引擎** `costing_summary` 表 / `CostingSummaryResource` 只是名字撞了。前端菜单项、`costing_order` 表、`CostingOrder*` 全部**保留**。

---

## 5. 阶段 5 · 视图 / 全局变量 / 配置清理（DDL，无 REST 端点变化）

### 5.1 视图改名（分两层）

```sql
-- 第 1 层（4 视图，零运行时引用）
ALTER VIEW v_costing_summary_full RENAME TO v_costing_summary_full_drop;
ALTER VIEW v_c_summary_agg        RENAME TO v_c_summary_agg_drop;
ALTER VIEW v_q_part_info_merged   RENAME TO v_q_part_info_merged_drop;
ALTER VIEW v_part_material_recipe RENAME TO v_part_material_recipe_drop;

-- 第 2 层（3 价格视图，被 254 张核价单 frozen_dto 快照但仅元数据引用）
ALTER VIEW v_costing_element_price  RENAME TO v_costing_element_price_drop;
ALTER VIEW v_costing_material_price RENAME TO v_costing_material_price_drop;
ALTER VIEW v_costing_exchange_rate  RENAME TO v_costing_exchange_rate_drop;
```

### 5.2 死全局变量置停用（先停用观察，不删行）

```sql
UPDATE global_variable_definition SET is_active = false
WHERE code IN ('ELEM_PRICE','MAT_PRICE','EXCHANGE_RATE');
```

实测：这 3 个变量在所有 live 配置载体（组件公式/字段/Excel列、模板快照、核价模板）**0 引用**；254 张核价单的 `frozen_dto` 引用是 `serializeWithGvDefs=listAll()` 的元数据副作用，非真查询。

### 5.3 废弃配置置 INACTIVE（不删行，保留追溯）

```sql
UPDATE basic_data_config SET status = 'INACTIVE'
WHERE target_table LIKE 'mat\_%' OR target_table LIKE 'costing\_%';   -- 实测 58 条 ACTIVE
```

**无 REST 接口变化**——这些是配置数据，不是端点。

---

## 6. 阶段 6 · SchemaContext BNF 映射（无 REST 端点变化）

`SchemaContext.defaultContext()`（`:143-151`）删除 8 条指向废弃表的中文逻辑名映射（元素BOM/来料BOM/组成件BOM/生产料号/工序资料/料号费用/客户料号对应/电镀方案）。

**保留**：电镀费用（→`plating_fee` V6 活表）、汇率、客户税率。

影响：删除后若有人配 `元素BOM.xxx` 路径会立即报「未知逻辑名」（而非静默读冻结表）——潜伏陷阱变显式报错。配置层实测 0 命中，无现存破坏。

---

## 7. 阶段 7 · 表改名 + 三重验证（DDL）

37 张表改名 `_drop`（清单见 backtask B7）。**无 REST 端点变化**——端点在阶段 2~4 已删，本阶段是底表清理。

**改名后**：任何遗漏的活代码触发 `relation does not exist`，Quarkus 日志可见——这是验证信号，不是故障。

---

## 8. 下线端点汇总（前端 service 同步清理，验收用）

下线后请求应返回 `404`：

```
POST /api/cpq/quotations/{id}/refresh-versions
GET  /api/cpq/part-version/**
POST /api/cpq/import/basic-data/v5/{preview,confirm}
POST /api/cpq/quotations/{id}/reimport-basic-data
ALL  /api/cpq/import-session/**
ALL  /api/cpq/costing-part/**
ALL  /api/cpq/costing-basic/**
ALL  /api/cpq/costing-summary/**
ALL  /api/cpq/costing-templates/**
GET  /api/cpq/quotations/{id}/costing-sheet
GET  /api/cpq/element-prices/available-elements   （若 task-0722 update-0724 未先下线，则本任务顺带）
```

**保留清单（联调时逐个 curl 确认仍 200/401，勿误伤）**：

```
GET  /api/cpq/quotations/{id}                    报价单打开
GET  /api/cpq/quotations/{id}/comparison         比对视图
GET  /costing-summary → CostingOrderListPage      财务工作台
POST /api/cpq/element-price/import                价格导入
基础资料 basicdata.v6 全部端点
```
