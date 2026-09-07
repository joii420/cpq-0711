# task-260907 · 后端任务分解

> **后端只按本文做。** AC 原文在 `需求文档.md` §3，接口契约在 `api.md` —— 本文**只标 AC 编号不复制原文**（复制会双写漂移）。
> **开工前必读**：`docs/rules/backend.md`（N+1 硬指标 / DDL 后重启 / 迁移纪律）· `docs/方案制定前必读.md`（7 类改动决策树）。

---

## 0. 三条不可越界

1. 🚫 **`POST /api/cpq/dataset/{dataset}/import` 及其 7 个维护端点一个字节不改**（`api.md` §5）。它们被【基础资料维护】共用，本任务另开端点。
2. 🚫 **`QuoteImportService` / 17 个 `Q*Handler` / `QuoteBackfillService` / `QuoteBackfillCollector` 全部保留不动**（N-7）。只摘 HTTP 入口。
3. 🚫 **严禁自己算指纹 / 自己归档 / 自己定版本号** —— 一律走 `VersionedGroupWriter`。它的类注释原文：「两套实现必然漂移（`PricingSheetRegistry` 自陈的『双写漂移』就是前车之鉴）」。
4. ⏸ **两项标 P2 的必须排在 S-5（11 页签模板）落地之后**：**B-10**（旧端点返 410）与前端 **F-1**（摘「从基础数据导入」按钮）。<br>**理由**：新模板未到位前，新链路建出的单渲染不出费用类页签；此时若把旧入口也摘了，用户就只剩一条还没通的路。<br>📌 D-24 之后 S-7 不在本期 ⇒ 旧模板与存量单仍在，**这两项晚做的代价接近零，早做的代价是真的**。<br>🚫 其余 12 个后端项与 9 个前端项**不受此约束，正常并行**。

---

## 1. 导入段（异步，D-19）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-19 | 新建 `QuotationImportResource`（**独立文件**，🚫 不要往 `DatasetImportResource` 里塞——那是维护页签的入口）。实现 `POST /api/cpq/dataset/quote/quotation-import`：<br>① 校验 `customerId` 非空 → `Customer.findById` → `customer.code` 非空，三步分别返 400/404/400；<br>② `sessionHelper.getCurrentUserId` 取不到返 401；<br>③ 同步段只做「建 `import_record`（`status=PROCESSING`，`system_type=DATASET_QUOTE`，`customer_id` 落所选客户）+ 在**请求线程内**把上传文件读进 `byte[]`」；<br>④ `managedExecutor.runAsync(...)` 后台执行，立即返回 `{importRecordId, status:"PROCESSING"}`。<br>⚠️ ③ 的读文件必须在请求线程完成 —— 上传临时文件请求结束后可能被回收（V6 侧同一约定，`BasicDataImportV6Resource:88` 有注释） |
| **B-2** | AC-1, AC-4, AC-5, AC-12, AC-17 | 新建 `QuotationImportService`（或在 `DatasetImportService` 上加异步入口，二选一，**在 `test.md` 里说明选了哪个**）。后台方法带 `@ActivateRequestContext`（否则后台线程拿不到 request-scoped `EntityManager`）：<br>① Phase 1 调既有 `DatasetImportService.parseAndValidate`（**事务外、零写库**，这是 AC-4「16 张表 count 逐表相等」唯一能成立的实现方式）；<br>② 有错 → 落 `import_record.status=FAILED` + 把 `DsValidationError` 全量写进 `metadata.errors`（**全部错误不是第一条**）；<br>③ 无错 → Phase 2 调既有 `DatasetImportService.writeAll`（单事务，异常整体回滚）；<br>④ 成功 → `status=SUCCESS` + `metadata.summary` 写逐 sheet 三态计数。<br>📌 **AC-12 的 `UNCHANGED` 语义直接继承自既有 `writeGroups`**（轴值整组指纹多重集相同 ⇒ 一行不写），🚫 不要另写一套判定 —— 这与 B-9 的「同 `importRecordId` 幂等」是**两件事**：前者是同内容再导一次，后者是同一次导入重复提交。<br>🚫 **顶层必须 try/catch**：后台线程的任何失败都要 finalize 成 `FAILED`，不许静默吞掉（V6 侧同型约定） |
| **B-3** | AC-4 | Excel「客户料号」sheet 的 `customer_no` **严格校验**：不在 `customer.code` 中 → 整份拒收，逐行报 `{sheetName,rowNum,columnLabel,value,reason}`。<br>📌 这条 `QuoteRegistry` 已声明 `.master("customer", null)`，确认既有校验器已覆盖即可，**不要重复实现**。<br>⚠️ 实测现网 19 个客户编号只有 3 个能命中（`CUST-0001` / `CUST-0002` / `CUST-0004`），做夹具时别用另外 16 个，否则是假红 |
| **B-4** | AC-5, AC-20 | 进度写入：`metadata.progress = {done,total,current}`，独立 `REQUIRES_NEW` 事务立即提交（整单回滚时进度不能跟着消失）。<br>🚫 **不许每 sheet 写一次** —— V6 侧实测 17 次 × 三次网络往返 ≈ 800ms，是端到端超 2s 的主因。照抄它的双重判据：固定检查点（均匀分桶）+ 静默超时兜底 |
| **B-5** | AC-4, AC-5 | `GET /api/cpq/dataset/quote/quotation-import/{recordId}`：按 `api.md §2` 返回 `status/progress/summary/errors`。记录不存在返 404 |

---

## 2. 建单段（同步建单建行 + 异步物化）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-6** | AC-1, AC-3, AC-21 | `POST /api/cpq/dataset/quote/create-quotation`。同步段一个 `@Transactional` 内完成：<br>① 校验 `import_record` 存在 / `status=SUCCESS` / `system_type=DATASET_QUOTE` / **`customer_id` 与请求 `customerId` 一致**（不一致 400，防串号）；<br>② 复用既有 `QuotationService.create(CreateQuotationRequest)` 建单，透传 `categoryId` / `customerTemplateId` / `costingTemplateId`；<br>③ 建明细行（见 B-7）；<br>④ `import_record.quotation_id` 回写。<br>🚫 **不要复刻 V6 的 `hfPairs` 写 `metadata` 那一段** —— 那是为 `listCandidatesV6` 的时间窗近似服务的，新链路用 B-7 的精确查询，不需要它 |
| **B-7** | AC-1, AC-3, AC-18, AC-21 | 明细行候选改查 `ds_quote_customer_part`（`api.md §3` 有完整 SQL）：<br>`WHERE cp.customer_no = :customerCode`，`LEFT JOIN ds_quote_material` 取品名。<br>🚨 **必须 LEFT JOIN** —— INNER 会在物料表缺行时静默丢明细行（`task-260903` 立项期实测过同型：`customer` 表 17 行有 3 行 JOIN 不到）。<br>建行复用既有 `QuotationLineItemMaterializeService.materializeLinesFromCandidates`（它已做过 N+1 治理：分块 200 的多行 `VALUES` 批量 INSERT）。<br>⚠️ **同一 `material_no` 对应两条客户料号时要建 2 行**（AC-21），`sort_order` 从 0 严格递增 |
| **B-8** | AC-20 | 物化转后台：`materializeExecutor.runAsync(() -> materializer.materialize(bg))`，响应 `materializing=true`。<br>🚨 **必须用 `@MaterializeExecutor` 那个 cleared CDI executor，不是全局 `managedExecutor`** —— 后者在 fire-and-forget 下会把即将销毁的 request context 传播进后台线程，致 `@ActivateRequestContext` 误判已激活而不新建，下游 `EntityManager` 不可用（`repair-260829 B-2` 的实证）。<br>🚨 **别把响应对象本身交给后台任务持有**（并发读写 → 序列化期 `ConcurrentModificationException`），另建一份局部对象（V6 侧 B-18 的做法） |
| **B-9** | AC-12 | 幂等重入：同 `importRecordId` 已建过单且该单仍在 → 直接返回既有 `quotationId` + 现有行数，不重复建单建行 |

---

## 3. 旧入口停用（S-6）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-10** ⏸**P2** | AC-14 | ⏸ **排在 S-5 模板落地之后**（见 §0 第 4 条）。`BasicDataImportV6Resource` 的 `POST /quote` 与 `POST /quote/create-quotation` 改为返回 **410**，body 提示「报价基础数据导入已迁移至『导入报价数据』」。<br>🚫 **只停这两个**。`POST /pricing`、`POST /pricing/template`、`GET /{recordId}` **保留** —— 核价侧在用，且【导入历史】页要读 `GET /{recordId}`（`api.md §4`）。<br>🚫 **不删任何 Service / Handler 类**（N-7） |

---

## 4. 模板可重放（S-5 / D-20）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-11** | AC-6, AC-7, AC-8 | 把主线在配置器 UI 里配好的 11 个组件 + 1 张报价模板**导出成 Flyway 迁移**：`component` / `component_sql_view`（含 `builder_config` + `builder_version`）/ `template` / `template_component` 四张表的 INSERT。<br>🚨 **迁移号必须查共享库 `flyway_schema_history` 的当前最大值，不能 `ls` 目录**（`task-260902` 实证：目录最大 V400 而共享库已到 V404，差 4 个号）。<br>⚠️ 迁移要**幂等**（`ON CONFLICT DO NOTHING` 或先判存在），否则重放会撞唯一键。<br>📌 目的是避开 `deploy/cpq-init.sql` 那条老路 ——「`costing_bom_tree_config` 等 UI 建的配置从未进迁移」正是当初不能靠 Flyway 重放的原因。<br>📌 **AC-8 的守卫**：「物料与元素BOM」页签的组件必须绑上元素单价列（走 `f_material_element_price`，语义模型里 `ELEMENT_BOM → FUNC_ELEMENT_PRICE` 是 QUOTE 方言**唯一一条边**）。漏绑的症状是「元素单价整列空」，而那与 `repair-260830` 的历史故障长得一模一样，会浪费一轮误诊 |
| **B-12** | AC-7 前置 | 存量清空脚本落 `deploy/`，**脚本本身不自动执行、不进 Flyway**。<br>🚨 **§3.2 红线**：脚本交付后由主线单独向用户呈报「操作 + 逐表影响行数 + 可恢复性」并取得当次批准才执行。子代理**只写脚本、绝不执行**，遇到任何 `DROP`/`TRUNCATE`/无 WHERE 的 `DELETE` 需求一律停下报主线 |

---

## 4.4 `customer_no` 注入（D-22 的本任务侧，⛔ 阻塞于上游 DDL）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-14** | AC-3, AC-22 | ⛔ **开工条件：`task-260907-报价侧加客户维度`（2026-09-07 从 `取数配置器补齐` 拆出）的 28 张表 DDL + 轴模型改造已合 master。未落地前本项不启动，其余 B-x 不受阻。**<br>① **拒收跨客户文件**（AC-3）：Phase 1 校验时比对「客户料号」sheet 的 `customer_no` 与本次导入选定客户，出现其它编号 → 整份拒收，逐行报出；<br>② **注入**（AC-22）：其余 15 个无客户列的 sheet，写入时把选定客户的 `customer_no` 填进每一行。<br>🚨 **注入点必须在本任务新建的 `QuotationImportService`，🚫 不许改共用的 `DatasetImportService`** —— 那条路被【基础资料维护】用着，**没有「选客户」这个输入**，在那里注入会拿不到值或注入错值（N-10 同源理由）。<br>⚠️ **本项护栏只覆盖导入这一条路。** 实测 `VersionedGroupWriter` 有 **6 个调用方**（导入 / 维护端保存 / 分组锁 / 客户产品 / 选配两个），其中 `DatasetMaintenanceService`、`DatasetGroupLock` 的 `customerNo` 引用数**为 0** —— 那两条路上本护栏不会触发，归上游负责。🚫 别以为加了这条就全覆盖了。<br>🚨 **不要自己扩 `VersionedGroupWriter` 的轴** —— 轴模型归上游。若上游落地后发现轴仍是单列 `material_no`，**停下报主线**：那意味着「客户 A 导入料号 X 会静默删掉客户 B 的料号 X」（§4.55a 第 2 条），此时**绝不能开始导入**，否则是不可逆的数据丢失 |

---

## 4.5 核价通过路径（零改动，但必须实证）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-13** | AC-11, AC-16 | **本项不写业务代码，产出的是实证。** 新单核价通过时 `QuoteBackfillService` 会被调用但应静默降级为 no-op —— 需要**证明它确实是「跑了但降级」而不是「压根没跑」**。<br>**依据链**：新组件 SQL 读 `ds_quote_*` → 不在 `QuotePendingRewriter.WHITELIST_TABLES` → `rewrite()` 的 `anchorInjected=false` → `QuoteBackfillColumnMapper` 返 `NOT_BACKFILLABLE` → `QuoteBackfillCollector:181` 的 `!resolved.backfillable → continue`。<br>**要交的证据**：① 核价通过后端日志里 `QuoteBackfillService` 摘要为 `groups=0,added=0,deleted=0,changed=0`；② V6 八张表跑前跑后**内容 md5 逐表相同**（🚫 只比行数不够，回填改的是 `is_current` 与列值，行数可能不变而内容已变）；③ 无 ERROR 日志。<br>🚫 **不要为了让它「更干净」而去改 `QuoteBackfillService` 加白名单判断** —— 那是 N-7 明确不做的，且现有降级路径已经正确 |

---

## 5. 横切纪律（每项都要遵守，不单独编号）

| 项 | 要求 |
|---|---|
| **N+1** | AC-20 硬指标：建单链路 SQL 条数与料号数**无关**。候选查询 1 条、建行分块批量、物化走既有批量路径。🚫 循环体里出现查询 = 违规 |
| **迁移** | 号从共享库 `flyway_schema_history` 实取；🚫 不要手工 `psql -f`（dev server 启动时 `migrate-at-start` 会自动跑）；🚫 不要改名/改号已应用的迁移 |
| **DDL 后** | 本任务**无 DDL**（不建表、不改列）。若发现必须建表，**停下报主线** —— 那意味着范围跑到第二段去了 |
| **测试库** | ⚠️ `mvnw test` 的默认库就是 `cpq_db_0724`（**共享开发库本身**）。🚫 **不许跑任何清库型测试**（§3.2「测试也算」）。夹具用前缀化自造数据，用完清理自己那份 |
| **grep** | 本环境 `grep` 是 ugrep，中文多的大源文件会被**静默判为二进制返空**。据 grep 空结果下「无引用」结论前，必须用 `/usr/bin/grep -a` 复核 |
| **提交** | 🚫 **不要执行 `git commit`**，提交由主线统一做 |

---

## 6. 反向覆盖自查（每个 B-x 都指回了 AC）

| B-x | 指回 |
|---|---|
| B-1 | AC-1, AC-2, AC-19 |
| B-2 | AC-1, AC-4, AC-5, AC-12, AC-17 |
| B-3 | AC-4 |
| B-4 | AC-5, AC-20 |
| B-5 | AC-4, AC-5 |
| B-6 | AC-1, AC-3, AC-21 |
| B-7 | AC-1, AC-3, AC-18, AC-21 |
| B-8 | AC-20 |
| B-9 | AC-12 |
| B-10 | AC-14 |
| B-11 | AC-6, AC-7, AC-8 |
| B-12 | AC-7（前置） |
| B-13 | AC-11, AC-16 |
| B-14 | AC-3, AC-22（⛔ 阻塞于上游） |

✅ 14 项全部有指向，无超范围项。
