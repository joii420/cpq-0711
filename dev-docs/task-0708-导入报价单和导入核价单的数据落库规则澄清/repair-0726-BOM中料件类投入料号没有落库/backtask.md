# 后端任务文档 —— BOM 中料件类投入料号没有落库（repair-0726）

> 上游：`需求说明.md`（含 §11 澄清纪要 + §12 方案定稿）｜接口契约：`api.md`｜前端：`fronttask.md`
> 分支：`feat/repair-0726-material-master-pending`（worktree 已由技术总监创建）
> **开工前必读**：`docs/方案制定前必读.md`、`docs/RECORD.md`（尤其 2026-07-21 task-0721 B9 与 2026-07-23 update-0723 两条）

---

## 0. 一句话任务

把「报价导入的料号先进 `pending_material_master_staging` 暂存表、核价通过才进 `material_master`」改成「**直接进 `material_master` 正表 + 行级 `pending_quotation_id` 标记**」，暂存表退役；标记的生命周期复用现有 8 张 V6 表的 pending 基建。

**这不是 bug 修复，是机制替换。** 写入链路本身是好的（`MaterialBomMergeHandler:122-133` 对零件/外购件有登记动作），问题在 repo 层被改道去了暂存表。

---

## 1. 改动全景（8 个任务）

| # | 任务 | 主要文件 | 规模 |
|---|---|---|---|
| B1 | Flyway：加列 + 存量迁回 + DROP 暂存表 | `V362__material_master_pending_flag.sql` | S |
| B2 | Repo 写入语义改造 + 四方法退役 | `MaterialMasterRepository.java` | M |
| B3 | 生命周期接线（过户/重导/转正/回收） | 4 个 service | M |
| B4 | 可见性：主数据列表过滤 pending | `MaterialMasterCrudService.java` + 实体 | S |
| B5 | Q02 补 `material_type='零件'` | `Q02CustomerMapHandler.java` | S |
| B6 | 回填预览 token 口径迁移 | `QuoteBackfillPlan/Collector/PreviewService` | S |
| B7 | 单测改造 + 新增 | `MaterialMasterStagingTest` 等 | M |
| B8 | 全链路验收 + 自检证据 | — | M |

> ⚠️ **8 个 handler 的 call site 一行都不用改**（`MaterialBomMergeHandler:169`、`Q02:124`、`Q04`、`Q06`、`Q07`、`Q09`、`Q13`、`Q18:51` 全部走 repo 的三个 pending 感知重载）。B5 是唯一例外，改的是它传的 `material_type` 值，与 pending 机制无关。

---

## 2. B1：Flyway 迁移

**文件**：`cpq-backend/src/main/resources/db/migration/V362__material_master_pending_flag.sql`（当前最大版本 V361）

### 2.1 三段式内容

```sql
-- ① 加列 + 部分索引
ALTER TABLE material_master ADD COLUMN pending_quotation_id UUID NULL;
CREATE INDEX ix_material_master_pending ON material_master(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;
COMMENT ON COLUMN material_master.pending_quotation_id IS
  'repair-0726：该行由哪张未核准报价单新建（导入期=importRecordId，建单后=quotationId）；NULL=已生效的正式料号';

-- ② 存量迁回（暂存表 → 正表）
--    去重：同一 material_no 可能在多张单的暂存表里各有一行（暂存表唯一键是 quotation_id+material_no），
--    PG 不允许一条 INSERT 对同一冲突键命中两次 → 必须 DISTINCT ON，取最早 created_at 的单作归属。
INSERT INTO material_master (material_no, material_name, specification, dimension, old_material_no,
                             material_type, usage_property, unit_weight, standard_unit, production_no,
                             pending_quotation_id, created_at, updated_at, updated_by)
SELECT DISTINCT ON (material_no)
       material_no, material_name, specification, dimension, old_material_no,
       material_type, usage_property, unit_weight, standard_unit, production_no,
       quotation_id, NOW(), NOW(), updated_by
FROM pending_material_master_staging
ORDER BY material_no, created_at
ON CONFLICT (material_no) DO UPDATE SET
  -- 与 promoteStaging 走的 upsertByMaterialNo(preserveDescriptive=true) 口径逐列对齐：
  --   name/type = COALESCE(现值, 新值)；其余列 = COALESCE(新值, 现值)
  material_name   = COALESCE(material_master.material_name, EXCLUDED.material_name),
  material_type   = COALESCE(material_master.material_type, EXCLUDED.material_type),
  specification   = COALESCE(EXCLUDED.specification,   material_master.specification),
  dimension       = COALESCE(EXCLUDED.dimension,       material_master.dimension),
  old_material_no = COALESCE(EXCLUDED.old_material_no, material_master.old_material_no),
  usage_property  = COALESCE(EXCLUDED.usage_property,  material_master.usage_property),
  unit_weight     = COALESCE(EXCLUDED.unit_weight,     material_master.unit_weight),
  standard_unit   = COALESCE(EXCLUDED.standard_unit,   material_master.standard_unit),
  production_no   = COALESCE(EXCLUDED.production_no,   material_master.production_no),
  updated_at      = NOW(),
  updated_by      = EXCLUDED.updated_by;
  -- ⚠️ 故意不写 pending_quotation_id：正表已存在的行（老正式料号）不得被降级为 pending

-- ③ 退役暂存表
DROP TABLE pending_material_master_staging;
```

### 2.2 强制注意

- **不要手工 `psql -f`**（CLAUDE.md 铁律）。放进 `db/migration/` 后 `touch` 一个 java 文件让 Quarkus dev 自动跑 Flyway。
- 迁移后自检：`SELECT version, success FROM flyway_schema_history WHERE version='362'` → `success=t`。
- 开工前先 `SELECT count(*) FROM pending_material_master_staging` 记下存量数，迁移后核对正表里 `pending_quotation_id IS NOT NULL` 的行数（去重后可能少于存量数，属正常，需说明差额来源）。

---

## 3. B2：`MaterialMasterRepository` 改造

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/MaterialMasterRepository.java`

### 3.1 三个 pending 感知重载：从「改道暂存」改为「正表 + 标记」

现状（`:253-292`）是 `pendingQuotationId != null` → 逐行 `stageOne(...)`。改为：**继续走原批量 upsert SQL，只多带一个 `pending_quotation_id` 列**。

```java
public void upsertBatchNameType(List<NameTypeRow> rows, UUID updatedBy,
                                boolean preserveDescriptive, UUID pendingQuotationId) {
    // pendingQuotationId == null 时行为逐字节不变（内部走同一 SQL，:pq 绑 null）
    ...
}
```

SQL 改动仅两处：
1. `INSERT INTO material_master (..., pending_quotation_id) VALUES (..., :pq)`
2. `ON CONFLICT (material_no) DO UPDATE SET ...` —— **不含 `pending_quotation_id`**

这一条同时满足三个裁决：新行打标记 / 已有正式行不降级（DO UPDATE 不碰该列） / 别单 pending 行不被抢占。

> 建议把三个批量方法（NameType / Weight / MaterialNoOnly）的 SQL 收敛为一个私有构造器，避免三处重复维护 `pending_quotation_id` 列。`upsertBatchMaterialNoOnly` 现已委托 `upsertBatchNameType`，保持委托即可。
> `upsertByMaterialNo`（逐行版，`:47-101`）同样加 `:pq` 参数的重载 —— `QuoteBackfillService:69` 的 `newMaterialStubs` 补桩调用它，那条路径发生在核价通过时刻，应传 `null`（直接建正式料号）。

### 3.2 新增三个方法

```java
/** 核价通过：本单 pending 料号转正。返回转正行数。 */
public int flipPending(UUID quotationId)
// UPDATE material_master SET pending_quotation_id = NULL, updated_at = NOW()
// WHERE pending_quotation_id = :qid

/** 重导覆盖 / 删单回收：删除本单 pending 料号行，带引用守卫（见 §3.3）。返回删除行数。 */
public int deletePendingWithGuard(UUID quotationId)

/** 回填预览用：读本单 pending 料号行（替代 listStaging，复用 StagedRow 形状）。 */
public List<StagedRow> listPending(UUID quotationId)
// SELECT material_no, material_name, ... FROM material_master WHERE pending_quotation_id = :qid
// ORDER BY material_no   ← 必须固定排序，预览 token 依赖稳定序列化
```

### 3.3 引用守卫 SQL（`deletePendingWithGuard`）

```sql
DELETE FROM material_master mm
WHERE mm.pending_quotation_id = :qid
  AND NOT EXISTS (SELECT 1 FROM material_bom_item x
                   WHERE x.component_no = mm.material_no
                     AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid))
  AND NOT EXISTS (SELECT 1 FROM material_bom x
                   WHERE x.material_no = mm.material_no
                     AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid))
  AND NOT EXISTS (SELECT 1 FROM material_customer_map x
                   WHERE x.material_no = mm.material_no
                     AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid))
```

语义：**只要该料号还被本单之外的任何数据（正式行或别单 pending 行）引用，就不删**。

### 3.4 退役

删除 `stageOne` / `listStaging` / `promoteStaging` / `clearStaging` 四个方法。`StagedRow` record **保留**（B6 的 `listPending` 继续用它，避免连锁改 `QuoteBackfillPlan`），但把 javadoc 里的"暂存"措辞改掉。

---

## 4. B3：生命周期接线

| 位置 | 现状 | 改为 |
|---|---|---|
| `V6QuotationCommitService.java:134 repointPendingOwnership` | 循环 8 张 `PENDING_TABLES` UPDATE 过户 | `material_master` **加入同一循环**（列名同为 `pending_quotation_id`，可直接加进 `PENDING_TABLES` 常量） |
| `QuoteImportService.java:269 clearPreviousPending` | 循环 8 表 DELETE | 8 表 DELETE 后 **追加** `materialMasterRepo.deletePendingWithGuard(pq)`（**关闭 BACKLOG BL-0072**） |
| `QuoteBackfillService.java:67` | `promoteStaging(quotationId, currentUserId)` | `materialMasterRepo.flipPending(quotationId)` |
| `QuoteBackfillService.java:146`（`cleanupPending`） | `clearStaging(quotationId)` | **删掉这行** —— 核价通过路径已由 `flipPending` 转正，再删就把刚转正的行删了。⚠️ 这是本任务最容易写错的一处 |
| `QuotationService.java:1638 cleanupPendingV6Data` | 8 表 DELETE + `clearStaging` | 8 表 DELETE **在前**，`deletePendingWithGuard(quotationId)` **在后** |

> 🔴 **顺序铁律**：`cleanupPendingV6Data` 必须先删 8 张 V6 表的 pending 行，再删料号行 —— 否则本单自己的 `material_bom_item` 会把守卫顶住，料号永远回收不掉。B7 要有专门单测锁死这个顺序。

> 📌 `V6QuotationCommitService` / `QuoteImportService` / `QuotationService` / `QuoteBackfillService` 各自持有一份 8 表字面量清单（原注释说明「分属不同包各自 private，重复的耦合成本低于抽共享工具类」）。本次沿用该风格，但**四处都要改到**，不要漏。`material_master` 是否放进字面量清单由你判断：过户可以放（UPDATE 语义相同），删除**不能放**（需要守卫，必须走 repo 方法）。

---

## 5. B4：可见性（主数据列表过滤）

**文件**：`MaterialMaster.java`（实体）+ `MaterialMasterCrudService.java:30 list`

1. 实体加字段：
   ```java
   @Column(name = "pending_quotation_id")
   public UUID pendingQuotationId;
   ```
2. `list()` 的 `where` 起手改为 `pendingQuotationId is null`（`count` 与 `find` 用同一个 where，注意两处都要生效）。
3. **`MaterialMasterDTO` 不暴露该字段** —— 前端契约零变化（见 `api.md`）。
4. 检查 `MaterialMasterCrudService.create/update` 等手工维护路径：新建实体时 `pendingQuotationId` 保持 `null`（手工建的就是正式料号）；`update` 走 `findById` 后改字段，天然保留原值，不要在 DTO→实体映射里覆写它。

**不要动**的三处（需求方已裁决）：
- SQL 视图 / 渲染 join：不加谓词
- `MaterialNoResolver` 发号查重：不加谓词
- `ExistingProductService` / `CustomerPartCandidateService`：闸门已在 `material_customer_map` 上，不叠加

---

## 6. B5：Q02 补 `material_type`

**文件**：`Q02CustomerMapHandler.java:124`

现状 `upsertBatchMaterialNoOnly(mmAcc, ...)` 只写 `material_no`。改为走 `upsertBatchNameType`，每行 `NameTypeRow(materialNo, null, "零件")`。

- `preserveDescriptive` 传 `true`（已有类型不被覆盖）。
- 名称仍传 `null`（Q02 的销售料号名称语义属于客户料号名，不是料件品名，**不要**顺手写进 `material_name`）。
- 口径依据：`PartTypeInferenceService` 注释 —— 客户料号关系 / 成品其他费用 / 组装加工费的销售料号属 ASSEMBLY（零件）权威集。

---

## 7. B6：回填预览 token 口径迁移

| 文件 | 改动 |
|---|---|
| `QuoteBackfillCollector.java:222` | `listStaging(quotationId)` → `listPending(quotationId)` |
| `QuoteBackfillPlan.java:20` | 字段名 `materialMasterStaging` → `materialMasterPending`（类型不变，仍 `List<StagedRow>`） |
| `QuoteBackfillPreviewService.java:153` | 跟随改名，`canonStaged` 的序列化字段顺序**不变** |

**幂等性说明**：token 由 canonical 序列化算出，数据源从暂存表换成正表 pending 行后，同一业务状态仍产出同一 token，幂等语义不破。但 `listPending` **必须固定 `ORDER BY material_no`**（暂存表原查询无 ORDER BY，靠行序偶然稳定），否则预览 token 会随 PG 返回顺序抖动 → 用户预览后提交报 409。

---

## 8. B7：测试

### 8.1 改造既有

`cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/MaterialMasterStagingTest.java`（3 个用例：暂存不写实表 / promote 覆盖式落地 / clearStaging 清理）—— 按新语义整体改写并更名为 `MaterialMasterPendingTest`：
1. `pendingQuotationId != null` → **正表有行且带标记**（原断言"正表 count=0"必须反转）
2. `flipPending` → 标记清空，行仍在
3. `deletePendingWithGuard` → 无引用时删除；有别单引用时**保留**

### 8.2 新增用例（最少 5 条）

| # | 用例 | 断言 |
|---|---|---|
| T1 | 已存在正式行（NULL）被 pending 单再次 upsert | `pending_quotation_id` 仍为 NULL（不降级）；描述列按 preserve 语义只补空 |
| T2 | 别单 pending 行被本单 upsert | 归属不变（不抢占） |
| T3 | 引用守卫：料号被别单 `material_bom_item` 引用 | `deletePendingWithGuard` 不删该行，返回 0 |
| T4 | 删单顺序：先删 8 表 pending 再删料号 | 料号行确实被回收（顺序写反时该用例必须失败） |
| T5 | `listPending` 排序稳定 | 两次调用返回顺序一致，且 = `ORDER BY material_no` |

### 8.3 回归（必须全绿）

```bash
cd cpq-backend
./mvnw test -Dtest='*Quote*,*PartType*,*MaterialBomMerge*,*MaterialMaster*,*Backfill*,*Costing*'
```

已知 pre-existing 坏测试（**不是你引入的，不要花时间修**，但报告里要提）：`Q04ElementBomHandlerTest` / `Q05ElementRecoveryHandlerTest` 系列的 fixture 列名漂移（BACKLOG BL-0069，master 上即已失败）。

---

## 9. B8：全链路验收（对应需求说明 §8 AC-1~AC-9）

测试数据：`docs/table/报价测试数据/v2/报价系统模板0723.xlsx`，客户罗克韦尔 `CUST-1269`。
黄金样例：`991/992`=材质（**不得入表**）、`S-80011`=零件、`W-1001`=外购件。

四节点 SQL 证据（每一步都要贴实际输出，不许只写"已验证"）：

```sql
-- AC-1 导入后
SELECT material_no, material_name, material_type, pending_quotation_id
FROM material_master WHERE material_no IN ('S-80011','W-1001','991','992');
-- 期望：S-80011(零件)/W-1001(外购件) 两行且 pending=importRecordId；991/992 无行

-- AC-2 create-quotation 后：pending_quotation_id 已 = quotationId
-- AC-3 核价通过后：pending_quotation_id IS NULL
-- AC-4 删单后：本单 pending 行消失；被别单引用的行仍在；NULL 行不受影响
-- AC-8 迁移
SELECT version, success FROM flyway_schema_history WHERE version='362';
SELECT to_regclass('pending_material_master_staging');  -- 期望 NULL（已 DROP）
```

**AC-5 渲染**须人工在报价单页面确认 `S-80011` / `W-1001` 的品名、单重能显示（导入后即可，不必等核价通过），截图入交付。

---

## 10. 强制自检（CLAUDE.md「修改后强制自检」）

后端改动完成后逐条执行并在交付报告中原样贴出：

1. `touch` 一个 java 文件 → 等 5-7 秒 Quarkus 重启
2. `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 **401**（不是 200，不是 500；`/q/health` 是 404，不是探针）
3. `SELECT version, success FROM flyway_schema_history WHERE version='362'` → `success=t`
4. `./mvnw test -Dtest='...'`（§8.3）全绿输出
5. 四节点 SQL 实测输出

**本次不触发的自检**：无 `field_type` 改动 → 不触发 AP-44 十七点协议；无前端协议级改动 → 不强制 Playwright E2E（AC-5 用人工渲染验证替代）。

**交付宣告必须含一行「已自检」声明**，例如：
> V362 success=t ✅；`./mvnw test -Dtest='*Quote*,*MaterialMaster*,*Backfill*'` 全绿 ✅；后端 /api/cpq/components → 401 ✅；AC-1~AC-9 SQL 证据见 §9 ✅

---

## 11. 交付物清单

- [ ] `V362__material_master_pending_flag.sql`
- [ ] `MaterialMasterRepository.java`（3 重载改造 + 3 新方法 + 4 方法退役）
- [ ] `V6QuotationCommitService` / `QuoteImportService` / `QuotationService` / `QuoteBackfillService` 四处生命周期接线
- [ ] `MaterialMaster.java` + `MaterialMasterCrudService.java`
- [ ] `Q02CustomerMapHandler.java`
- [ ] `QuoteBackfillPlan/Collector/PreviewService` 三处改名与口径迁移
- [ ] `MaterialMasterPendingTest`（改写）+ 5 条新用例
- [ ] 交付报告：AC-1~AC-9 证据 + 自检声明 + 已知遗留
- [ ] `docs/RECORD.md` 追加一条开发记录
- [ ] `BACKLOG.md`：BL-0072 标记为已关闭（随暂存表退役天然解决）
