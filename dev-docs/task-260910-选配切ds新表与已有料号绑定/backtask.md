# task-260910 · 后端任务分解

> 🚫 **只标 AC 编号，不复制 AC 原文**（`task-docs.md §6③`）—— AC 原文的单一事实源在 `需求文档.md §③`，**开工前必读那一节**。
> 🚫 接口契约看 `api.md`，**不要凭本文件猜签名**。
> 🚨 遇 `CLAUDE.md §3.2` 不可逆操作红线（DROP/TRUNCATE/无 WHERE 的 DELETE/清库/改共享环境）**立即停下报主线**，子代理**没有批准权**。
> 📌 本任务**数据层零不可逆操作**：全部改动只产生「新增行」与「S-6 修复后不再产生重复行」。若你发现自己需要 DELETE，先停下。

## 迁移号

从 **V440** 起（2026-09-10 实测：共享库 `flyway_schema_history` max = 439，主仓目录 max = 439）。
🚨 **落库前必须复查共享库的 `flyway_schema_history`**（不能只 `ls` 目录）—— 并发会话可能已占号，教训见 `INDEX.md` §8 的 V401→V405 改名事故。

---

## S-1 写入侧切表

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-4 | `insertProcessSimpleUnitPriceV6` / `insertProcessUnitPriceV6` 改写 **`ds_quote_self_process_fee`**：换用 `VersionedGroupWriter.writeGroup`（轴 = `(customer_no, material_no)`），列映射按 `api.md §4`。**SIMPLE 时 `input_material_no` = 料号自身；COMPOSITE 时 = 子件料号**（D-4）。`item_seq` 按行序 1..N（新表 `required=true`，老表无此列）。`value` 留 NULL。<br>🚫 停写 `unit_price`（AC-1②）。🚫 方法名可保留，但内部不许再引用 `VersionedV6Writer` |
| **B-2** | AC-2 | `insertCompositeProcessCapacityV6` 改写 **`ds_quote_assembly_fee`**：列映射按 `api.md §4`，**`assembly_fee` 写 `0`**（D-5，该列 `required=true`）。`customer_no` 从既有 `customerCode` 透传。<br>🚫 `process_name` / `production_type` 在新表**无落点**，不许硬塞进别的列（前者可由 `assembly_operation` JOIN `process_master` 现算，本期不做）|
| **B-3** | AC-3 | `resolvePart` 外购件分支的工序：从「调 `insertProcessSimpleUnitPriceV6`（自制加工费）」改为**写 `ds_quote_assembly_fee`（组装加工费）**。<br>🚨 **这是业务计算口径变更**（D-6），不是等价搬运 —— 回归时要专门看报价金额，见 `test.md` 片 S-A |
| **B-4** | AC-1, AC-2, AC-3 | `VersionedV6Writer` 在选配链路上的引用清零自检：`/usr/bin/grep -an "versionedWriter\|VersionedGroupSpec" ConfigureProductService.java` 应只剩 `backfillProcessesForNewCustomer` 一处（见 B-5）|
| **B-5** | AC-1 | `backfillProcessesForNewCustomer`：查/写从 `unit_price` 改 **`ds_quote_self_process_fee`**（按 `customer_no` + `material_no`）。<br>📌 **顺手修过时注释**：方法头 Javadoc 写着「hotfix: `mat_process` 按 customer_id 隔离」—— 实测**全工程 `mat_process` 表名位引用 = 0**，方法体查的一直是 `unit_price`。删掉那句误导性注释 |
| **B-6** | AC-22 | `buildSnapshot()` 三段查询改造：**删除** `unitWeightGrams` 段（查 `v_compat_material_master`）与 `compositeProcesses` 段（查 `capacity`）—— 实测**全前端零消费方**；`processes` 段改读 `ds_quote_self_process_fee`（SQL 见 `api.md §2.4`）。同步裁剪 `LookupFingerprintResponse.Snapshot` 的两个字段 |

## S-2 读取侧切表 + 客户维度

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-7** | AC-5, AC-7, AC-8, AC-9 | `ConfigureSearchResource.searchParts`：① 端点**加 `customerNo` 必填参数**（缺失 400）；② 数据源 `material_master` → **`ds_quote_material`** + `WHERE customer_no = :customerNo`；③ 材质来源改 `ds_quote_material_bom.input_material_no` **JOIN `material_recipe.code`**（SQL 见 `api.md §2.1`）。<br>🚫 **判据不许用 `output_material_type`**（D-1）。<br>🚫 **删掉 `:66` 那句注释**「跨客户搜索（与 V44 行为一致，不限定当前报价单客户）」—— 留着下一个人会照它把过滤去掉 |
| **B-8** | AC-8 | `SearchPartResult` 材质字段单值 → **`materials[]` 多值**（契约见 `api.md §2.1`）。⚠️ 跨端契约变更，改动前确认 `api.md` 的 R-2 已定稿 |
| **B-9** | AC-6 | `ConfigureProductService.listOutsourcedParts`：① 端点**加 `customerNo` 必填参数**；② 数据源 `v_compat_material_master` → **`ds_quote_material`** + `WHERE customer_no = :customerNo AND material_type='外购件'`。<br>🚫 **不许加 `DISTINCT`** —— 客户过滤已经解决重号（实测 5 料号 × 2 客户 = 10 行），加 `DISTINCT` 是给已解决的问题打第二个补丁 |
| **B-10** | AC-5, AC-7 | 另两处读点同样切表 + 加客户：① `loadCatalog` ⑥（外购件料号存在性，`WHERE material_no IN (:nos)` → 加 `customer_no`）；② `resolvePart` existing 分支的存在性校验（`SELECT … FROM v_compat_material_master WHERE material_no = :p`）。<br>📌 服务内部已有 `customerCode`，**不用改方法签名** |
| **B-11** | AC-5 | 选配侧对 `v_compat_material_master` 的引用清零自检：`ConfigureProductService` + `ConfigureSearchResource` 两个类里应为 **0 处**。<br>🚫 **不许 DROP 那 3 个 `v_compat_*` 视图** —— 本期明确不做（其余消费方 `ExistingProductService` / `ExistingProductDTO` / `QuotePendingRewriter` / `SemanticCompiler` 仍在用）|

## S-4 方案 ②（带版本表只写 `_record`）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-12** | AC-10 | `SelDsQuoteWriter`：`writeMaterialBomGroup` / `writeElementBomGroup` **停止直写主表**，改写对应 `_record`（带 `quotation_id`）。<br>🔒 **`upsertMaterial` 与 `insertCustomerPart` 不动** —— 免版本表**没有 `_record` 对应物**（全库 13 张 `_record` 清单里无 `material` / `customer_part`），且 `DsQuoteBackfillService` 的 B-13「免版本表排除」会拒收（D-7）。<br>⚠️ `writeOutsourcedSelfRow` 的「先读现状再合并」逻辑要同步迁到 `_record` 口径 |
| **B-13** | AC-13 | `ConfigureProductResource.configureProduct`：**调换 `snapshotLines`(`:72`) 与 `syncRecordsForFlow`(`:103`) 的顺序** —— 必须先写 `_record` 再冻结，否则冻结时 `_record` 还没写、卡片冻成空。<br>🚨 **保留原有的显式 `flush` 前置**（`D-40` 教训：挂点的 `try/catch` 会把调用方自己的失败吞掉 ⇒ 接口返 200 而整单静默回滚）|
| **B-14** | AC-11 | `ComponentDriverService`：冻结期取数加 **patch 合并层** —— 主表结果为基底，用 `_record`（`WHERE quotation_id = :quotationId`）按 `origin_id` 做**按列 COALESCE 覆盖** + `origin_id IS NULL` 追加（D-8/D-9）。<br>🚫 **组件 SQL 一段不改**（D-8 选方案甲）。<br>🚫 **不许自己写第二套 patch 逻辑** —— 复用 `DsQuoteBackfillService` / `DsBackfillCollector` 的实现（`VersionedGroupWriter` 类注释原话：「两套实现必然漂移」）。<br>⚠️ **`quotationId` 为 null 的老调用者**（详情页走老协议）必须**跳过 patch**、原样返回主表结果，否则详情页查空 |
| **B-15** | AC-12 | 全链路自验：建单（主表 0 行 / `_record` 有行）→ 提交 → 核价通过 → 主表 1 行 `version_no=1` `source='QUOTE_BACKFILL'` `material_ratio=100`。<br>📌 **回填链路本身不用改** —— `QuotationService.costingApprove` → `dsQuoteBackfillService.execute` 已在 master |

## S-5 / S-6 `_record` 层

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-16** | AC-14, AC-15 | `DsQuoteRecordService` / `DsRecordProjector` 投影**补 `material_ratio`**（D-10）。实测该列在 `_record` 真实边行里填充率仅 **12.2%**（49/401），而主表 96.9%。<br>⚠️ **范围只限选配新铸料号**，🚫 不动导入侧投影（AC-15 是这条的反向断言）。<br>📌 `output_material_type` **不补**（系统常量、且 D-1 已废除其判据地位）|
| **B-17** | AC-16, AC-17 | `DsRecordCardDeduper` 修复「同单同料号多 line item ⇒ 同内容行出现两份（一份认领 `origin_id`、一份 NULL）」。<br>**实测复现**：单 `08c99680-1359-4b1b-be98-7b93aabc71e4` 的 `S0001` 有 2 个 line item ⇒ `(S0001, item_seq=1, input=S0002)` 出现 2 行，`created_at` 逐字相同（`2026-09-09 08:18:45.557113`），**晚于 D-43 修复 `f52b8050`（UTC 09-08 08:56）1 天** ⇒ 不是存量。<br>**规模**：修复后创建的行中 element 侧 **58 组** / material 真实边行 **71 组**。<br>⚠️ **3694 组「同一(单,料号)多个树根行」未判定** —— 树根行可能本就每卡片一个（合法），🚫 不要顺手一起去重，先报主线 |

## S-7 直接绑定已有销售料号

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-18** | AC-18, AC-20 | `ConfigureProductRequest` 加 `bindExistingMaterialNo`（契约见 `api.md §2.3`）；`ConfigureProductService.configure` 加**绑定分支**：跳过 `prepareParts` / 指纹 / 发号 / BOM 与元素写入，只写 `ds_quote_customer_part` + `quotation_line_item`。<br>🚫 `parts` 与 `bindExistingMaterialNo` 互斥（400 `BIND_AND_PARTS_EXCLUSIVE`）。<br>⚠️ `validateRequest` 现在硬要求 `parts` 非空，绑定路径要放行 |
| **B-19** | AC-18 | 绑定路径的 `_record`：走既有 `syncRecordsForFlow`（不用特殊处理）—— 该料号已有主表行 ⇒ 投影的 `origin_id` 指向已有行，回填时判 `UNCHANGED` 不升版 |
| **B-20** | AC-18, AC-19 | 绑定路径的编号占用校验：**复用** `assertCustomerProductNoAvailable` + `insertSelProductNo` 的 23505→409 映射（🚫 不写第二套）。`BIND_MATERIAL_NOT_FOUND`：料号不在该客户的 `ds_quote_material` 中 → 400 |

## 通用

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-21** | AC-1~AC-23 | **N+1 硬指标**（`backend.md §1`）：本次所有改动，单个业务操作的 SQL 条数必须与料号数/材质数/工序数/配件数**无关**。<br>🚫 `writeGroup` 不许放进 for 循环（用 `writeGroups` 批量入口）；🚫 patch 合并的 `_record` 查询必须**整单一次取回**，不许逐行查 |
| **B-22** | AC-11 | 顺手修第二处过时注释：`insertQuotationLineProcesses` 的 Javadoc 写「本表当前无任何 SELECT/视图读取」—— 实测 `QuotationService` 在读、表内 24 行 |

---

## 双向覆盖自检

**正向**（每条 AC 至少一个 `B-x` 认领）：

| AC | 认领 | | AC | 认领 |
|---|---|---|---|---|
| AC-1 | B-1, B-4, B-5 | | AC-13 | B-13 |
| AC-2 | B-2, B-4 | | AC-14 | B-16 |
| AC-3 | B-3, B-4 | | AC-15 | B-16 |
| AC-4 | B-1 | | AC-16 | B-17 |
| AC-5 | B-7, B-10, B-11 | | AC-17 | B-17 |
| AC-6 | B-9 | | AC-18 | B-18, B-19, B-20 |
| AC-7 | B-7, B-10 | | AC-19 | B-20 |
| AC-8 | B-7, B-8 | | AC-20 | B-18 |
| AC-9 | B-7 | | AC-21 | （零改动，测试侧回归）|
| AC-10 | B-12 | | AC-22 | B-6 |
| AC-11 | B-14, B-22 | | AC-23 | （零改动，测试侧回归）|
| AC-12 | B-15 | | 全部 | B-21 |

⚠️ **AC-21 / AC-23 是回归 AC，无对应实现任务** —— 它们验的是「**不该变的没变**」，由 `test.md` 片 S-E 覆盖。这不是覆盖缺口。

**反向**（每个 `B-x` 都指回 AC）：B-1~B-22 全部已标，无孤立项。
