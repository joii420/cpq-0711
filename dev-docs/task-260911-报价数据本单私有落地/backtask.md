# task-260911 · 后端任务分解（第二版 · 已并入独立评审 16 条 findings）

> 主文档：`需求文档.md`。每项标「服务的 AC」，反向可指回。
> 🚫 **遇 `CLAUDE.md` §3.2 不可逆红线（DROP / TRUNCATE / 无 WHERE 的 DELETE / 清库）一律停下报主线，子代理没有批准权。**
> 分两路：**后端 A = 写入侧**，**后端 B = 取数侧**。接触点只有 `SheetDef.recordTable()`（只读）。

---

## 🚨 开工前必读：三条第一版写错、第二版已更正的事实

第一版这三条**都是错的**，照着干会白做或做反：

| 第一版 | 实测更正 |
|---|---|
| 「`VersionedGroupWriter` 被**三条**路径共用」 | **四类调用方 / 7 个调用点**，第一版漏了 `DsQuoteBackfillService:309`（核价通过回填）—— 而那正是本任务 `R-5` 要改的那条 |
| 「删单回收 `_record` 是 no-op，需要**新增**」 | **已实现**：`QuotationService:2199` → `DsQuoteRecordService:522`，按 Registry 派生表名逐表删。本期是**复核 + 补通道**，🚫 不要重写 |
| 「并发任务占着 `ComponentDriverService`，`SqlViewExecutor` 是我们的独占车道」 | **反了**。那个任务**已合并 master**，改的就是 `SqlViewExecutor`（`:367` `:443` `:541` `enrichRowScopeSets`）。见下方红线 1 |

---

## 路 A · 写入侧（`backend-engineer` 实例 1）

| # | 服务的 AC | 内容 |
|---|---|---|
| **B-1** | AC-1 | **迁移**：13 张 `ds_quote_*_record` 的 `quotation_id` 放开为**可空**，新增 `import_batch_id uuid`（对应 `import_record.id`，🚫 不建 FK 以免删导入记录级联），加索引 `(import_batch_id)`。<br>📌 **迁移号从 `V442` 起**（`V441` 已被并发任务占用且 `success=t`）。🚫 仍须落库前 `ls .../db/migration/` 实取 —— 它是移动靶。<br>🚫 不动业务列、不动 `base_version_no` / `base_row_fingerprint` |
| **B-2** | AC-1, AC-9, AC-22 | **`VersionedGroupWriter` 加目标表路由 —— 🚨 必须用「重载」，🚫 不许加必填参数。**<br>**为什么**（评审 `F-7`）：实测 7 个调用点 / 4 类调用方 —— `DatasetImportService:260`、`DatasetMaintenanceService:873`、`DsQuoteBackfillService:309`、`SelDsQuoteWriter:243/334/387/435`。加必填参数会让 `SelDsQuoteWriter` **4 个调用点编译不过**，而它是「首批禁止触碰」的文件（`B-5` 延后）⇒ 开工第一天就卡死。<br>**做法**：保留现签名，语义 = `MASTER`；新增重载接 `WriteTarget`（`MASTER` \| `RECORD(quotationId, importBatchId)`）。<br>🚨 **路由参数必须显式传入**，🚫 **严禁读 ThreadLocal / `QuotePendingScope` 做隐式推断**。<br>📌 **真实理由（第一版写反了，评审 `F-5`）**：维护页与导入链路上**都没有** `QuotePendingScope` ⇒ 隐式推断在**两边都落 `MASTER`** ⇒ 维护页行为不变（所以 `AC-9` 不是这条的守卫），而**导入侧会整个不生效、`R-1` 白做**。<br>`RECORD` 分支语义：按 `(quotation_id, 轴值)` 整组**覆盖式**写（先删本单该轴值组再插），🚫 不算指纹、不升版、不写 `_history` |
| **B-3** | AC-1, AC-2 | **导入落地改路由**：报价侧带版本 sheet 传 `WriteTarget.RECORD(null, importRecordId)`；**免版本三表仍传 `MASTER`**（岔路 1 甲）。判据用 `SheetDef.versioned`，🚫 不写死表名 |
| **B-4** | AC-1 | **建单时回填归属**：`DatasetQuotationCommitService.createQuotation` 同事务内把本批次 `_record` 的 `import_batch_id` → `quotation_id`。<br>🚫 **N+1 硬指标**：13 张表各 1 条 `UPDATE ... WHERE import_batch_id = ?`，与料号数无关 |
| **B-5**<br>🚦 **延后批** | AC-8, AC-8b | 🚦 **不随首批开工** —— 等 `task-260911-选配三层模型` 合并（§4.1）。<br>内容：`SelDsQuoteWriter` 4 张带版本表传 `WriteTarget.RECORD(quotationId, null)`；2 张免版本表不动。<br>🚨 **同批必须改读路径，见 `B-20`** —— 只改写不改读会静默覆盖本单 `_record`。<br>🚫 **首批派工时不许把本项塞进任何子代理的 prompt** |
| **B-6** | AC-7, AC-24 | 🔄 **复核 + 补通道（🚫 不是新增）**：<br>① **复核** `DsQuoteRecordService:522 deleteByQuotation` 在 `quotation_id` 可空之后仍完备（它是 `WHERE quotation_id = :qid`，**永远命中不了 NULL 行**）；<br>② **补一条按 `import_batch_id` 回收未建单批次**的路径（评审 `F-12`：导入成功但建单失败/用户放弃时，`_record` 留下 `quotation_id IS NULL` 的行，**没有任何代码会删它们**，按导入次数累积）；<br>③ 实测库内已有 12 个「`_record` 有、`quotation` 表无」的历史孤儿组 —— 那是 `D-48` 修复前的残留，🚫 **不属本期范围，不许顺手清**（`§3.2` 红线） |
| **B-7** | AC-11, AC-13 | **`_record` 编辑同步收敛**：`DsQuoteRecordService` 从「投影整单」改为「保存时按用户编辑事件增量同步」。<br>🚨 **`AC-11⑤` 红线**：删除**只能**由「用户删行」事件写入，🚫 **不许存在「重投影后比对差集」的删除路径**。交付须给出「`_record` 的 DELETE 写点可穷举清单」 |
| **B-8** | AC-6, AC-11, AC-14, AC-22 | **回填改整组对齐 + 继续升级**：<br>① 整组对齐含**减行**（推翻 `D-34`），`resultRowCount < baseRowCount` 成为合法态；<br>② 基底取**回填那刻主表最新版**，直接升下一版，🚫 不因基准过期拦截（岔路 3）；<br>③ 📌 **内容对位锚定只保留给存量单**（评审 `F-15`）：A 之后导入落 `_record` 的行 `origin_id` 必为 NULL，内容对位在 `AC-14` 场景下必然全不命中 —— 结果不会错（整组替换等价），但🚫 **不要为新链路维护这段代码**，且它形态上与 `AP-60` 的「整组权威」相邻，要注明适用边界；<br>④ **确认抽屉必须列出「将删除的行」**（`AC-11③`，`BL-0211` 原文要求）；<br>⑤ 🚨 **`AC-5e` 的红色警示是三处并存的**（单元格小注 + 顶部红条 + 汇总条），改判据要三处一起改，🚫 只改一处不合格；<br>⑥ 🚨 **`DsQuoteBackfillService:309` 自己也调 `VersionedGroupWriter`** —— 必须传 `MASTER`，被误路由到 `_record` 会让回填整个失效且静默（`AC-22` 守这条） |
| **B-9** | AC-15 | **存量单显式提示**：`_record` 为空的单在确认抽屉返回 `noRecordSnapshot`（复用现有机制），🚫 不许静默 no-op |
| **B-10** | AC-11, AC-16 | **`DsRecordProjector` 退役**：内容对位反推整体删除。<br>📌 **删前复核 `DsRecordCardDeduper` 的两层逻辑**（第一版引错，评审 `F-11`）：它实现的是 **`D-44`**（同料号多卡片 → 整组翻倍的跨卡片归一），**不是 `D-43`**（`D-43` 是「改一格值直接提交 ⇒ 回填用旧值」）；且它后来被 `task-260910 B-17` 扩过（`dropAnchorShadows`）⇒ **要复核的是两层不是一层**。<br>🚫 不许假设「`_record` 变第一手就不需要去重了」——造一张同料号双卡片的单实证 |
| 🆕 **B-21**<br>🚦 **延后批** | AC-25 | **零件复用加「数据看得见」前置条件**（`R-12`，用户 2026-09-11 裁决 · `需求文档` §1.7）。<br>**问题**：复用判定查 `sel_part_signature`（`SalesSignatureRepository:29`），该表 **无 `quotation_id`、无审核状态列**（实测 71 行，`UNIQUE(customer_no, structure_version, config_fingerprint)`），选配提交即全局可见；而三层模型 `B-6` 明写复用时 **不补 BOM**。⇒ A 的 `B-5` 落地后，他单复用到的是**有料号、没 BOM 的空壳，且不报错**。<br>**做法**：命中签名表之后追加一道判据 —— 「该料号在**主表**有 BOM」**或**「它的 `_record` 属于**本单**」。两者皆不满足 ⇒ **不复用，新铸**。<br>🔑 **这就是 A 的可见域规则本身**，🚫 不许另造一套「审核状态」概念。<br>⚠️ **本项与三层模型是耦合项，不是任一方的缺陷** —— 单看谁都成立。三层模型那边的 `A0` 岔路 2「零件复用判定放哪」必须知道本项存在 |
| 🆕 **B-20**<br>🚦 **延后批** | AC-8 | **选配读路径改口径**（评审 `F-8`，第一版完全漏了）：`SelDsQuoteWriter:273-278 writeOutsourcedSelfRow` **读主表整组当「现状」再整组重写**。写落 `_record` 而读仍在主表 ⇒ 每次外购件自指行写入都会用主表版本**整组覆盖本单 `_record`**。<br>🚨 **该方法 javadoc 明写「`task-260910 D-14`：现状与写入都是主表口径（曾按方案②改成 `_record` 口径，已被 D-14 推翻回退）」** ⇒ 本任务正在把 D-14 推翻的方向再推一次。**动手前必须读 `task-260910` 的 `D-7`/`D-14` 原文**（`需求文档` §1.3 已摘录），🚫 不许凭本文件的转述 |

---

## 路 B · 取数侧（`backend-engineer` 实例 2）

| # | 服务的 AC | 内容 |
|---|---|---|
| **B-11** | AC-3, AC-4, AC-17, AC-23 | **新建 ds 改写器**（建议 `DsRecordRewriter`，🚫 不塞进 `QuotePendingRewriter` —— 那是 V6 通道，两套语义混一个类必然漂移）。<br>规则：`FROM ds_quote_x` → `(SELECT <主表列集> FROM ds_quote_x_record WHERE quotation_id=:pq UNION ALL SELECT <主表列集> FROM ds_quote_x WHERE (轴) NOT IN (SELECT 轴 FROM ds_quote_x_record WHERE quotation_id=:pq)) <原别名>`。<br>🚨 **别名必须保留原别名**，🚫 不许照抄文档里的 `x`（实测 12 段视图把 ds 表放在 `JOIN` 位并带别名）。<br>🚨 **原 SQL 的过滤条件必须复制到 UNION 两侧**，漏一侧的症状是**筛选漏一半且不报错**（`AC-23`）。<br>🚨 **不注入锚点**（`__v6_id`）—— `_record` 是第一手数据，`origin_id` 不再靠 SQL 注入反推；且注了会让启动校验全崩（见 `B-14`）。<br>**列集只能取主表列集**：`_record` 独有的 `element_price` 在 UNION 里取不到（`需求文档` §1.4）。<br>**两条实测依据（自己复核一遍，🚫 别信本文档）**：① ds 主表**无 `is_current` 列** ⇒ 不需要遮蔽；② 引 ds 的视图**零段**引用 `version_no`/`row_fingerprint`/`source`/`is_current` ⇒ 换表零破坏。<br>**已知 SQL 形状（评审实测，风险比预期低）**：`SELECT *` / `alias.*` / `LATERAL` / `GROUP BY` / `DISTINCT` / CTE / `RECURSIVE` **全部 0 段**；含 `UNION` 4 段、ds 表在 `JOIN` 位 12 段、含 `:param` 50 段 |
| **B-12** | AC-3, AC-12 | **挂接缝**：`SqlViewExecutor.applyPendingRewrite` 按表名分流 —— V6 白名单走老 `QuotePendingRewriter`，13 张 ds 带版本表走 `DsRecordRewriter`。<br>⚠️ **`SqlViewExecutor` 是共享文件**，见红线 1 |
| 🆕 **B-19** | AC-21, AC-12, AC-10 | 🚨 **冻结/隔离判定统一到后端自查**（用户裁决甲）。<br>**问题**（评审 `F-1`）：`applyPendingRewrite` 的门槛（`:735-737`）读 `SqlViewRuntimeContext.get().quotationId`，而该 ThreadLocal 有**第二个写入点** `FormulaEvaluateResource:122` `setNestedTemplate(templateId, req.quotationId, req.quotationStatus)` —— **状态来自请求体**。实测前端两处 `LinkedExcelView`（`QuotationStep2.tsx:4569` COSTING / `:4648` QUOTE）**都不传 `quotationStatus`**，该 prop 可选，一路 `\|\| null` 到后端 ⇒ `isQuotationFrozen()` 恒 false。<br>**做法**：`DsRecordRewriter` 的启用判定**不信** `SqlViewRuntimeContext` 里的 `quotationStatus`，一律按 `quotationId` **自查单据状态**（可进程级缓存，但 🚨 **状态会变，缓存必须可失效** —— 🚫 不许照抄 `priceBaseDateCache` 那种「`created_at` 不可变所以永久缓存」的模式）。<br>🚫 **前端零改动**（乙案已被否决：信任模型没变，下一个调用方忘传洞就回来） |
| **B-13** | AC-12 | 🚨 **先证实再动手**：确证「冻结单渲染 100% 读 `card_values` 快照、零回落取数」。<br>做法：拿一张 SUBMITTED 单，**断掉 `_record` 与主表该组数据**（临时改名/加谓词，🚫 不许 DROP），渲染结果必须逐字节不变。<br>⛔ **证不出来就停下报主线** —— 前提不成立时本任务会打穿现网 40+ 张 SUBMITTED 与 APPROVED 单。<br>📌 评审代理把这条列为「只读约束下验不了」，所以它**必须由本路实跑**，🚫 不许引用评审结论代替 |
| **B-14** | AC-18 | 🔄 **启动期硬校验整条重做**（🚫 不是「加白名单」）。<br>**第一版错在哪（评审 `F-4`）**：<br>① `QuoteViewValidationService:152-153` 在 `bindProbe` + `LIMIT 0` **之前**早退 `if (!r.anchorInjected) return 不适用`；而实测**零段**活跃视图命中 V6 白名单 ⇒ **当前校验器分母恒为 0，整个是空跑的**；<br>② 若给 ds 改写器注锚点：产物是 `(… UNION ALL …) alias` 子查询，pgjdbc 拿不到 `getBaseTableName`（同文件 `:173-176` 已有实证注释）⇒ `verifyAnchorMetadata` 全判失败 ⇒ **后端起不来**。<br>**做法**：① ds 侧**单开常量**，🚫 **不许扩 `QuotePendingRewriter.WHITELIST_TABLES`** —— 它还被 `SemanticCompiler:1767`（编译期兼容自检，要求生成 SQL 必须含 `FROM <anchorTable>`）、`QuoteViewValidationService:210`、`QuoteBackfillColumnMapper:105` 消费；② ds 适用性门槛改为「**命中 ds 表即纳入校验**」，校验内容 = 改写后 `LIMIT 0` 能跑通，🚫 不做锚点元数据校验；③ **断言 `total > 0`**（专治空跑）。<br>**还原实验必做**：一段视图引用不存在的 `_record` 表 → 启动失败并指名该视图 |
| 🆕 **B-18** | AC-20 | 🚨 **取价函数补 `_record` 分支**（用户裁决补进本期）。<br>**实查的函数现状**：`f_material_element_price(text,date,uuid)` 的 `candidate_materials` CTE —— V6 两行带 `OR pending_quotation_id = p_pending_quotation_id`（`repair-260830` 修的，注释自陈「两个分支各管一半、缺一不可」），**ds 两行只有 `WHERE customer_no = p_customer_no`**，注释自陈「只对应 `is_current` 那半边」。<br>**不改的后果**：A 之后新料号 BOM 只在 `_record` ⇒ 候选集缺席 ⇒ `realtime` 分支产不出行 ⇒ **元素单价整列 NULL**，往下所有用单价的公式全塌。实测 **11 段视图**引用该函数，且这 11 段**全部**同时引用 ds 表。<br>**做法**：迁移里 `CREATE OR REPLACE` 该函数，`candidate_materials` 补两行读 `_record`（按 `quotation_id = p_pending_quotation_id` 收窄）。<br>🚨 **两参重载 `f_material_element_price(text,date)` 也要评估** —— 确认它有没有被 ds 视图调用；结论写进回报，🚫 不许省略。<br>🚨 **`RE-6` 还原实验必做**：去掉新分支 → `AC-20` 的元素单价列必须变成整列空 |
| **B-15** | AC-5 | **已有产品「待核价」判据**：`ExistingProductService` 增 `pendingApproval`，判据 = 该 `(customer_no, material_no)` 在**任一带版本主表**无行。<br>🚫 **N+1 硬指标**：单次请求 SQL 条数保持常数（现为 3 条），走 `EXISTS` 聚合子查询一次 `LEFT JOIN` 带出。<br>🚫 **不过滤、只标注**。<br>🚨 `LEFT JOIN v_compat_material_master` **刻意保留、不许直连 `ds_quote_material`**（`D-3`：实测直连会让 42 个料号改走新表值、6 个老表独有料号整个消失） |
| **B-16** | AC-10, AC-21 | 🔄 **核价侧隔离断言改口径**。<br>🚫 **不许再断言「核价侧零处调 `QuotePendingScope.open()`」** —— `F-1` 已证第二条通路绕过它，那条断言**永远绿但测不到出问题的地方**。<br>改为**运行时断言**：「核价上下文下 `DsRecordRewriter` **零次触发**」（打点计数 / 测试桩）。原白名单单测保留为补充，不作为唯一保障 |
| **B-17** | AC-20 | 🔄 **结论已定，本项从「先 grep 确认」降级为记录项**：`f_material_element_price` **读的就是 ds 表**（实查函数体，见 `B-18`）。本项只需在回报里写明「已确认，处置见 `B-18`」，🚫 不要重复调查 |

---

## 两路共同的红线

1. 🚨 **`SqlViewExecutor` 是共享文件，不是本任务的独占车道**（第一版写反了）。
   `feat/task-260911-row-scope` **已合并 master**，master 已含 `enrichRowScopeSets`（`:367` `:443` `:541`）。
   ⇒ 本任务**只动 `applyPendingRewrite` 及其新增分流**，🚫 **不动 `enrichRowScopeSets` / `:367` / `:443` 两个调用点**。
   叠加正确性由 `AC-23` 守：A 换表在前（`buildWrappedSql` 期）、B 绑参在后（已展开 SQL 上扫 `:param`）。
2. 🚫 **不改 `ComponentDriverService`**（它的合桶改动已在 master，本任务无需碰）。
3. 🚫 **不改渲染层**（`CardSnapshotService.buildCardValues` / 前端）—— 取数改写只在物化期。
4. 🚫 **不动 `ds_quote_*` 业务列与 `SheetDef` 轴模型**。
5. **提交一律 `git commit -- <本次改的文件>`**，🚫 禁 `git add -A`（本项目有夹带他人改动的实证）。
6. **N+1 硬指标**：单个业务操作的 SQL 条数必须是常数，与料号数 / 行数 / sheet 数无关。
7. 🚫 **`grep` 空结果不许直接下「不存在」的结论** —— 本环境 `grep` 可能是 `ugrep -I`，中文注释多的文件会**静默返空**。一律 `/usr/bin/grep -a` 复核；表名拼字符串进 SQL 时，按「表名位」正则会**漏**（`T-5` 陷阱族）。
