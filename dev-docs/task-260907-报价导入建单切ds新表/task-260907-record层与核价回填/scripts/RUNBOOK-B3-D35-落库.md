# RUNBOOK · B-3 / D-35 落库（合并 + 迁移）

> **执行者**：主线。**产出者**：后端子代理。
> ⛔ 本文只是操作手册，**后端子代理未执行其中任何一步**，也未对共享库做过任何 DDL/DML。
> 🚦 落库时机由主线裁定。

---

## 0. 先纠正一个前提：窗口不在「合并 vs 落库」之间

主线派工时的假设是「合并与落库之间存在窗口，要压到秒级」。**实测结论不同**：

`quarkus.flyway.migrate-at-start=true`（`application.properties:67`）⇒ **迁移是被应用自己在启动时执行的**。
所以只要「Registry 代码 + B-3 迁移文件」在**同一次合并**里进 master，对**任何一个实例**而言，代码与 DDL 就是**原子到达**的：它启动 → Flyway 先跑 → 自检后跑 → 两侧必然一致。

⇒ **真正的窗口不是「合并到落库」，而是「master 已含新代码+迁移」到「每个并发会话把 master 合进自己的 worktree」之间。**
在这个窗口里：

| 会话状态 | 后果 |
|---|---|
| 后端**正在运行**、不重启 | ✅ 无影响。自检只在启动时跑一次 |
| 用**陈旧 worktree**（无我的 Registry 改动）重启 | ❌ `多出未声明的列: source_quotation_id` ×26，起不来 |
| 已 merge master 后重启 | ✅ 正常 |

⇒ 需要压缩的不是「合并→落库」的秒数，而是**广播覆盖率**。窗口本身可以长达数小时而无害，只要没人用陈旧代码重启。

---

## 1. 顺序（含理由）

**推 master 在前，落库在后 —— 且落库不是一条独立动作，而是「主线自己重启 8081」的副作用。**

理由：
1. 反过来（先手工 `psql` 落库、后推代码）会同时踩两条纪律 —— `backend.md §4`「🚫 不要手工执行迁移 SQL」，以及 `RECORD.md` 记过两次的「迁移先落共享库、文件后进 master ⇒ Flyway 只在启动时校验，肇事者看不见，下一个重启的人才踩到」（V416/V417 同型复发）。
2. 让 Flyway 自己跑，checksum 天然对账；手工跑之后再合并，下次重启就是 `Migration checksum mismatch`。
3. 由主线在主工作区重启 = **主线是第一个开门的人**，出问题时现场就在自己手上，而不是让某个并发会话先撞上。

---

## 2. 步骤（每步带判据）

### S0 · 取号（落库那一刻实取，🚫 不许预取）

```bash
# 目录侧
ls cpq-backend/src/main/resources/db/migration | grep -oP '^V\d+' | sed 's/V//' | sort -n | tail -1
# 共享库侧
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
  -c "SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';"
```
**判据**：取 `max(两者) + 1`。两个都不能单独信（文件先落库后进 master ⇒ 目录偏小；迁移写完没跑 ⇒ 共享库偏小）。
⚠️ 上游「报价侧加客户维度」今天要落 `V425~V429` —— **必须在它落完之后再取号**，否则撞号。

### S1 · 广播「冻结重启」（落库前）

见 §4 广播文案 A。**判据**：等各会话回执，或主线判定当前无人处于重启边缘。

### S2 · 改名 + 移动两个迁移文件（在分支上做）

```bash
cd <worktree>
git mv cpq-backend/src/main/resources/db/migration-pending-260907/V___task260907_ds_quote_source_quotation_id.sql \
       cpq-backend/src/main/resources/db/migration/V<N>__task260907_ds_quote_source_quotation_id.sql
git mv cpq-backend/src/main/resources/db/migration-pending-260907/V___task260907_ds_quote_record_stale.sql \
       cpq-backend/src/main/resources/db/migration/V<N+1>__task260907_ds_quote_record_stale.sql
```
**判据**：`ls cpq-backend/src/main/resources/db/migration/ | grep task260907` 出现两个新号，且 `migration-pending-260907/` 只剩 `README.md` 与 V420 的历史草稿。

### S3 · 落库前最后一次成对性验证（只读）

```bash
cd cpq-backend && javac -nowarn -cp "target/classes:$(find ~/.m2 -name 'jackson-annotations-*.jar'|head -1)" \
  -d /tmp/p02 ../dev-docs/.../scripts/DumpExpectedTyped.java
java -cp "target/classes:/tmp/p02:$(find ~/.m2 -name 'jackson-annotations-*.jar'|head -1)" DumpExpectedTyped > /tmp/p02/expected.tsv
cd .. && python3 dev-docs/.../scripts/prove-b3-pairing.py /tmp/p02/expected.tsv
```
⚠️ 脚本里的 `MIG` 常量指向 `migration-pending-260907/`，S2 之后要改成 `migration/`（或在 S2 **之前**跑）。
**判据**：`结论：✅ 双向零差`，退出码 0。**不是零差就停下**，别硬着头皮合。

### S4 · 合并到 master（代码 + 两个迁移，一次推完）

```bash
git checkout master && git merge --no-ff feat/task-260907-record-backfill
```
**判据**：`git log --oneline -1 master` 是这次 merge；`git show --stat HEAD | grep -c 'V<N>__\|V<N+1>__'` = 2。
🔑 **代码与迁移必须在同一次合并里** —— 分开推就人为制造出「代码到了迁移没到」或反之的窗口。

### S5 · 🚨 不可逆点 —— 主线在主工作区重启 8081

```bash
cd /home/joii/project/cpq && git pull   # 或 merge master
cd cpq-backend && ./mvnw quarkus:dev    # Flyway 自动跑 migrate-at-start
```
**判据（三条都要）**：
```bash
# ① 迁移成功
PGPASSWORD=joii5231 psql ... -c "SELECT version,description,success FROM flyway_schema_history WHERE version IN ('<N>','<N+1>');"   # success = t
# ② 自检通过（起得来就是通过）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
# ③ 日志里那一行
grep "Registry↔DDL 自检通过" <日志>    # 期望「84 张表 → 97 张表」量级变化
```

### S6 · 落库后复验（只读）

```bash
# 列数从 0 变 26
PGPASSWORD=joii5231 psql ... -c "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='source_quotation_id' AND table_name LIKE 'ds\_quote\_%';"   # 期望 26
# D-35 标记表存在
PGPASSWORD=joii5231 psql ... -c "SELECT count(*) FROM information_schema.tables WHERE table_name='ds_quote_record_stale';"   # 期望 1
```
另：`RecordSchemaAcTest` 的 t08 空验证守卫应由红转绿（它检测的就是 `record-check.enabled=false`）。

### S7 · 广播「已落库」

见 §4 广播文案 B。

---

## 3. 失败点与回滚

| 步骤 | 可回滚吗 | 动作 |
|---|---|---|
| S0~S3 | ✅ 完全可逆 | 什么都没动，直接停 |
| S4（已合 master，未重启） | ✅ 可逆 | `git revert` 那个 merge。**此时 DDL 还没落** |
| **S5（Flyway 已跑）** | ❌ **不可逆点** | 回滚 = `ALTER TABLE ... DROP COLUMN source_quotation_id` ×26 + `DROP TABLE ds_quote_record_stale` —— 属 `CLAUDE.md` §3.2 **数据销毁红线**，必须重新走「量化影响面 + 可恢复性 + 用户当次批准」三步 |
| S5 失败（自检报错起不来） | ⚠️ 半落 | Flyway 已提交、自检才炸 ⇒ **DDL 在、服务不在**。此时**不要 DROP**，先看差异清单：多半是上游 `customer_no` 与本批撞在一起 ⇒ 正解是把上游那批也合进来，而不是回滚 |

🔑 **S5 之前的每一步都能反悔，S5 之后只能向前修。** 所以 S3 的双向零差验证是最后一道闸。

---

## 4. 广播文案（可直接发）

**A · 落库前（冻结）**
> 【task-260907 第二段 · 落库前冻结】接下来约 5 分钟我要把 B-3（`ds_quote_*` 加 `source_quotation_id`，26 处 ALTER）与 D-35（新建 `ds_quote_record_stale`）合进 master 并落库。
> 🚫 **这期间请不要重启后端**（正在跑的不受影响）。落库完成我会再发一条。

**B · 落库后（必须先 merge 再重启）**
> 【task-260907 第二段 · 已落库】B-3 / D-35 已进 master 并已应用到 `cpq_db_0724`（`V<N>` / `V<N+1>`，`success=t`）。
> 🚨 **从现在起，重启后端之前必须先 `git merge master`**（worktree 会话尤其注意）。
> 用陈旧代码重启会报 `[dataset] Registry 与数据库 schema 不一致 … 多出未声明的列: source_quotation_id`（26 处）**直接起不来** —— 那不是你的改动坏了，是没合 master。
> 🆘 应急阀（只在排障时用，用完必须去掉）：`-Dcpq.dataset.record-check.enabled=false`。
> 📌 正在运行、不重启的实例不受影响。

---

## 5. 预计耗时

| 段 | 耗时 |
|---|---|
| S0 取号 + S1 广播 | ~1 min |
| S2 改名 + S3 验证 | ~1 min |
| S4 合并推送 | ~30 s |
| S5 重启 + Flyway + 自检 | ~50 s（首启 6~7 s + 编译，26 条 ALTER 在空列上是元数据操作，秒级） |
| S6 复验 + S7 广播 | ~1 min |
| **合计** | **~4.5 min**，其中「不可逆之后到广播完成」约 **2 min** |

⚠️ 但如 §0 所述，**真正需要在意的不是这 4.5 分钟**，而是广播 B 之后每个会话把 master 合进来之前的那段时间 —— 那段时间长短无害，只要没人用陈旧代码重启。
