# ⛔ 待落库的迁移（task-260907 第二段 · B-1 / B-3）

本目录里的两个 `.sql` **尚未取迁移号、尚未应用到任何库**。

## 为什么放在这里而不是 `db/migration/`

`application*.properties` 配的是 `quarkus.flyway.locations=db/migration`，Flyway 会**递归**扫描该目录。
占位文件名 `V___xxx.sql` 解析不出版本号 ⇒ 放进去会让**所有人的服务启动失败**。
本目录是 `db/` 下的**兄弟目录**，Flyway 扫不到。

## 为什么还没取号

1. **迁移号是移动靶**：2026-09-07 有至少四条线在抢（本段 / `报价侧加客户维度` / 第一段 B-11 / `移除料号核价功能`）。
   立项文档记录同日 02:40 采到「齐平 V417」，03:30 就变成「齐平 V419」——50 分钟过期一次。
   ⇒ **取号必须在落库那一刻实取** `max(db/migration 目录最大号, 共享库 flyway_schema_history 最大号) + 1`，
   两个都不能单独信（文件先落库后进 master ⇒ 目录偏小；迁移写完没跑 ⇒ 共享库偏小）。
2. ~~**上游的主表 `customer_no` DDL 尚未落库**~~ → ✅ **2026-09-07 17:54 已落库**：
   上游 `V425~V429` 已应用到共享库（逐条 `success=t`），实测 **42 张 `ds_quote_%` 带 `customer_no`**；
   列定义 `varchar(20) NOT NULL` 无默认、取值 = `customer.code`，与本目录 SQL 里写的一致。
   本段的 13 张 `_record` 也已由 **`V420`** 建成（`success=t`）。
   ⇒ **本目录里 `V___task260907_ds_quote_record_tables.sql` 已成历史草稿**（内容 = 已落库的 `V420`）。
   ⛔ **真正还压着的只有本目录另外两条：`V___task260907_ds_quote_source_quotation_id.sql`（B-3）与
   `V___task260907_ds_quote_record_stale.sql`（D-35）** —— 实测 `source_quotation_id` 仍为 **0 张**。
   它们压着的原因是**落库窗口由主线统一开**（见任务目录 `scripts/RUNBOOK-B3-D35-落库.md`），
   🚫 **不是「等上游」**，两个洞不要混成一个。
   <sub>原文（已作废，留痕）：「上游的主表 `customer_no` DDL 尚未落库 …… 2026-09-07 实测 0 张带版本表已有 `customer_no` ⇒ 主表侧 DDL 还没到。」</sub>

## 落库时要做的三步（顺序不能反）

1. 等上游 `报价侧加客户维度` 的「DDL + 轴模型同批合并」落地；
2. 复核 `V___task260907_ds_quote_record_tables.sql` 里 13 处 `customer_no varchar(20) NOT NULL`
   与上游主表落库后的实际列定义**逐字一致**（`D-27`）；不一致就以主表为准改本文件；
3. 实取号 → `git mv` 到 `db/migration/` 并改名 → 启动服务让 Flyway 自动跑
   （🚫 不要手工 `psql -f`，会导致 checksum 对账不符）→ 查 `flyway_schema_history` 确认 `success = t`
   → **落库那一刻就把迁移文件推 master**（`RECORD.md` 记 V416/V417 同型复发两次：
   迁移先落共享库、文件后进 master，Flyway 只在启动时校验 ⇒ 肇事者看不见，下一个重启的人才踩到）。

## 落库前 Java 侧是什么状态

`DatasetSchemaSelfCheck` 已按 `SheetDef.expectedRecordColumns()` 把 `_record` 纳入启动自检。
⇒ **迁移没落库之前，后端起不来**（会报「表不存在: ds_quote_xxx_record」）。这是刻意的：
自检的全部意义就是硬拦「Registry 声明了、DDL 没建」这类双写漂移。
排障期可用 `-Dcpq.dataset.schema-check.enabled=false` 临时关掉（🚫 dev/生产不得关闭）。

---

## 🟢 2026-09-07 状态更新（重要）

- **`V___task260907_ds_quote_record_tables.sql` 已落库** —— 主线取号 **V420**，
  已在 `db/migration/V420__task260907_ds_quote_record_tables.sql`，共享库
  `flyway_schema_history` 记录 `420 | task260907 ds quote record tables | success=t`。
  本目录里那一份**只是历史草稿**，🚫 不要再改。
- **仍待落库的只剩 `V___task260907_ds_quote_source_quotation_id.sql`（B-3）**。
  落库后必须把 `cpq.dataset.record-check.enabled` 保持默认 `true`
  —— 在它落库之前，启动自检会因 13 张主表 + 13 张 `_history` 缺 `source_quotation_id` 而**让服务起不来**，
  这段窗口期用 `-Dcpq.dataset.record-check.enabled=false` 绕过。
- ⚠️ **本分支落后 master 11 个提交，缺 `V421` 文件**（`task260907 quote ds template seed`，已在 master 且已应用到共享库）。
  ⇒ 直接在本 worktree 起服务会被 Flyway 拦：
  `Detected applied migration not resolved locally: 421`。
  正解是**主线把 master 并进本分支**；🚫 不要 `flyway repair`、🚫 不要把别人的迁移文件抄进来。
