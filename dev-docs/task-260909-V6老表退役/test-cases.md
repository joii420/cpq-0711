# test-cases.md · V6 老表退役 · 全 5 片测试用例

- **任务**：`task-260909-V6老表退役`
- **编写时刻**：2026-09-09 10:38 ~ 10:55 PDT
- **基线 commit**：`14f80f40d5c5e4d7e3bd20e6a83ec81b37647b9c`（`feat/task-260909-v6-retire`）
- **用例来源**：`需求文档.md §⑥` 的 AC-1~AC-15 **原文**。分片依据 `test.md §一`
- **配套基线**：`证据/基线-260909.md`（本轮自采，采样时刻 17:38~17:47 UTC）
- **改动前对照**：`证据/S1-改动前-260909.md`

> ⚖️ **AC 原文才是验收标准**，本文件是分解结果。凡与 AC 原文有出入处，本文件已用 🚩 显式标注并报主线。

---

## 〇、全局执行前提（每片开跑前逐条确认，不确认不许跑）

| # | 前提 | 不满足时的动作 |
|---|---|---|
| **G-1** | **重采基线**：跑任何一条用例前，先原样重跑 `证据/基线-260909.md` 的对应命令取「当次执行前值」 | 🚫 不许引用基线文件里的数字当「执行前值」 |
| **G-2** | **禁用全局计数断言**（`S-1`/`S-3`）：断言写成「存在性」或「相对当次执行前值的差值」 | 见到 `count(*) = <绝对数>` 形式的断言即改写 |
| **G-3** | **造数前缀 `RETIRE0909-`**：`S-3` 造的报价单名、料号、批次名全部带此前缀；跑完在 `finally` 里清掉自造那批 | 无前缀的数据一律不造 |
| **G-4** | **`/usr/bin/grep -a`**：本环境 `grep` 是 `ugrep -I`，中文多的大源文件被静默判二进制返空 | 用了裸 `grep` 的结果一律作废重跑 |
| **G-5** | **0 命中先反向证明**：任何「命中 0」结论前，先用同 pattern 打一个**已知存在**的对象 | 无阳性对照的 0 值不许写进报告（`AC-14`） |
| **G-6** | **红线无批准权**：`DROP VIEW` / `DROP TABLE` / 清库 / `rm -rf` → **停下报主线** | 被 hook 拒了不许换写法重试 |
| **G-7** | **`mvnw test` 仅在 `B-1`（`S-5`）验收通过后才允许跑** | `B-1` 前跑测试 = 直写共享 dev 库 |

---

# S-5 · 测试配置片（最先做，做完广播）

> 写入面：配置文件 + 测试库。会改**所有人**的测试环境。

## T-1.1 · `AC-1` 测试跑完不再动共享 dev 库的 `material_master`

**服务的 AC 原文**：
> 前置：`application-test.properties` 已改。步骤：跑 `Q13ComponentOtherFeeHandlerTest` 一个类。
> 断言：跑前后 `SELECT max(updated_at) FROM material_master` **逐字不变**；且该测试类**仍全绿**（不能靠让它跑不起来来「通过」）。

**前置状态**
1. `B-1` 已交付，`application-test.properties` 已指向独立测试库（库名由 `B-1` 回报给出）。
2. worktree 内、`cpq-backend/` 目录下执行（🚫 不许 `cd` 到主仓 —— 会测错树）。

**操作步骤**
```bash
# ① 跑前立刻采（不许用基线文件的值 —— 中间隔了几十分钟，任何变化都归因不到测试头上）
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -c \
  "SELECT max(updated_at)||'|'||count(*) FROM material_master;" | tee /tmp/mm_before.txt

# ② 跑测试（限定单类）
cd /home/joii/project/cpq/.claude/worktrees/task-260909-v6-retire/cpq-backend
./mvnw -q test -Dtest=Q13ComponentOtherFeeHandlerTest ; echo "EXIT=$?"

# ③ 跑后立刻采
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -c \
  "SELECT max(updated_at)||'|'||count(*) FROM material_master;" | tee /tmp/mm_after.txt

diff /tmp/mm_before.txt /tmp/mm_after.txt && echo "SAME" || echo "CHANGED"
```

**可观测断言**
| # | 断言 | 判定值 |
|---|---|---|
| A1 | `diff` 输出 `SAME` | `max(updated_at)` 与行数**逐字节相同** |
| A2 | 测试进程退出码 | `EXIT=0` |
| A3 | Surefire 结果行 | `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`，且 **`N ≥ 1`** |
| A4 | 断言非空转 | `N ≥ 1` 必须显式打印。🚫 `Tests run: 0` 判 **FAIL**（那正是「靠跑不起来通过」） |
| A5 | 目标库确实被写了 | 在 `B-1` 新指的测试库上执行 `SELECT count(*) FROM material_master;`，**结果 ≥ 1**，证明测试真的建了表并写了数据（而不是连不上库被静默跳过） |

**🔬 证伪实验（必做，否则 A1 的 PASS 不可信）**
把 `application-test.properties` 的库**临时改回 `cpq_db_0724`**，重跑 ①②③。
- 期望：`diff` 输出 **`CHANGED`**（`max(updated_at)` 被推高）。
- 若改回去仍是 `SAME` ⇒ **这个测试类根本不写 `material_master`**，A1 是空验证，
  必须换一个已实证会写该表的测试类（立项文档 §④批次0 实测 09-09 09:43–09:44 有 18 行 `material_master` UPDATE，去那批测试里挑）。
- 🚨 证伪实验跑完**必须把配置改回 `B-1` 的新值**，并在报告里写明已还原。

**🚩 与 AC 原文的出入**：AC 只说「跑前后逐字不变」。但**共享库上任何并发会话都可能在这几分钟内推高 `max(updated_at)`**，
造成假红。⇒ A1 若判 CHANGED，必须先做归因：查 `SELECT material_no, updated_at FROM material_master ORDER BY updated_at DESC LIMIT 5;`，
看变的那几行是否与本测试类的夹具料号相关。**归因不出来就报主线，不许直接结案为 FAIL 或 PASS。**

---

# S-1 · 只读结构片（可与任何片并行；收尾须复跑）

> 写入面：**无**。纯 `SELECT` / `grep`。本轮已跑过一遍「改动前」，结果见 `证据/S1-改动前-260909.md`。

## T-4.1 · `AC-4` 配置层不再引用兼容视图与老表

**服务的 AC 原文**：
> `costing_bom_tree_config` **2 行**的 `sql_template` 中，`v_compat_` 命中 **0**、`FROM material_bom_item` 命中 **0**（当前分别为 1 和 1）。

**前置状态**：`B-3` 已交付。改动前实测：`d6defaa0-…` 含 `v_compat_`（1）、`82612f2b-…` 含 `FROM material_bom_item`（1）。

**操作步骤**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT now() AS sampled_at;
SELECT id,
       (regexp_match(sql_template,'FROM\s+[a-zA-Z_][a-zA-Z0-9_]*'))[1] AS from_frag,
       (sql_template ~* 'v_compat_')::int              AS has_compat,
       (sql_template ~* 'FROM\s+material_bom_item')::int AS has_old_mbi
FROM costing_bom_tree_config
WHERE id IN ('82612f2b-558a-4b13-b052-af08100573ac','d6defaa0-354f-4e92-8e89-4bc8454888c3')
ORDER BY id::text;"
```

**可观测断言**
🔴 **AC-4 已订正，本期只管 QUOTE 那一行**（原文把两行都算进范围，依据「V411 漏改」——**那个定性是错的**）：

| 行 | `usage` | 本期 |
|---|---|---|
| `d6defaa0-354f-4e92-8e89-4bc8454888c3` | **QUOTE** | ✅ **本期改** |
| `82612f2b-558a-4b13-b052-af08100573ac` | **COSTING**（核价侧，用 `system_type` + `:versionFilter`） | 🛑 **挂起等用户**（`ds_quote_material_bom` 无 `system_type`/`bom_version`/`component_no`，照原文改会让 SQL 直接报错打挂核价 BOM 树） |

| # | 对象 | 断言 |
|---|---|---|
| A1 | `d6defaa0-354f-4e92-8e89-4bc8454888c3` | `has_compat = 0` **且** `has_old_mbi = 0` |
| A2 | 同上 | `from_frag = 'FROM ds_quote_material_bom'`（逐字） |
| A3 🛑 | `82612f2b-558a-4b13-b052-af08100573ac` | **本期不断言**（挂起）。但须**反向断言它未被误改**：`from_frag` 仍为 `FROM material_bom_item` |
| A4 | 两个 id 都查得到 | 各返 1 行（少一个即 FAIL） |

🚩 **与 AC 原文的出入（已按 `test.md` G-2 改写）**：AC 写「2 行」，本用例改为**按 `id` 逐行断言**。
理由：`costing_bom_tree_config` 是共享配置表，并发会话新增行会让「2 行」失败，
而那与本任务无关。按 `id` 定位既覆盖 AC 的意图，又不受并发影响。

**`AC-14` 配套阳性对照（同 pattern 探针法）**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT count(*) FILTER (WHERE (sql_template || ' v_compat_probe') ~* 'v_compat_')                        AS probe_compat,
       count(*) FILTER (WHERE (sql_template || ' FROM material_bom_item') ~* 'FROM\s+material_bom_item') AS probe_mbi,
       count(*) AS total
FROM costing_bom_tree_config;"
```
断言：`probe_compat = total` **且** `probe_mbi = total`（改动前实测 `2 | 2 | 2`，见 `S1-改动前` T-14.1）。
🔑 **为什么用探针而非「换个已知对象」**：改动后全库可能没有任何一行 `sql_template` 还含 `v_compat_`
（实测 `component_sql_view` 42 行里 0 行含它），届时无对象可换。探针证明**谓词+列读取这条链路是通的**。

---

## T-9.1 · `AC-9` 3 处用户可见文案改指向 `ds_`

**服务的 AC 原文**：
> `SqlViewValidator.java:106` · `BnfPathLinter.java:107` · `Q05ElementRecoveryHandler.java:90` 三处文案中，
> 10 张老表名命中 **0**，且出现 `ds_quote_` 或 `ds_cost_` 前缀。
> 🚫 5 处**日志型**文案本期不改，不得混入本条断言。

**前置状态**：`B-9` 已交付。改动前实测（commit `14f80f40`）：
- `SqlViewValidator` 106–107 含 `material_master`/`material_bom_item`/`element_bom_item`/`unit_price`/`plating_scheme`
- `BnfPathLinter` 107–108 含 `material_bom_item`/`element_bom_item`/`unit_price`/`plating_scheme`
- `Q05ElementRecoveryHandler` :90 含 `element_bom_item` —— 🔴 **本期不改**（`Q-1` 挂起）
- 三处 `ds_quote_|ds_cost_` 命中均为 **0**

### 🔴 主线裁决后的范围与作用域（`Q-1` / `Q-2`）

| 裁决 | 内容 |
|---|---|
| **`Q-1` 🛑 挂起等用户** | 排除 `Q05ElementRecoveryHandler:90` 属**范围缩减**（`D-4` 裁的是「3 处」），按 `§4.3` 归用户裁决。<br>**本期 `B-9` 只做 2 处祈使型**（`SqlViewValidator` / `BnfPathLinter`）。<br>理由（主线实测确认）：该 handler 真在 UPDATE `element_bom_item`（`:20` Javadoc + `:94` `recordWrite`），改指向 `ds_` 会指向它**从未查过的表**。 |
| **`Q-2` ✅ 采纳** | `AC-9` 作用域**写死到「该文案字符串所在行」**，不含 Javadoc、行内注释、错误码常量、V44 黑名单常量数组、`recordWrite(...)` 的机器标识符。<br>📌 `Q05:94` 的 `recordWrite("element_bom_item", …)` 是 **T-5d 记账，指向真实写入表，必须保留** —— 🚫 不许当成「漏改的文案」去改。 |

⇒ **本用例断言 2 处，不是 3 处。** 第 3 处（`Q05`）改为「**逐字未变**」的反向断言（A7/A8），
等用户对 `Q-1` 裁决后再决定是否新增用例。

**操作步骤**
```bash
cd /home/joii/project/cpq/.claude/worktrees/task-260909-v6-retire
PATB='\b(material_master|material_bom_item|element_bom_item|material_bom|element_bom|unit_price|capacity|plating_scheme|annual_discount|material_customer_map)\b'

# ① 先定位每处文案的实际跨行范围（改动后行号会变，必须重新定位，不许沿用 106/107/90）
/usr/bin/grep -a -n 'SQL_VIEW_DEPRECATED_TABLE' cpq-backend/src/main/java/com/cpq/component/service/SqlViewValidator.java
/usr/bin/grep -a -n 'String suggestion ='        cpq-backend/src/main/java/com/cpq/template/util/BnfPathLinter.java
# Q05 本期不改（Q-1 挂起），下面这条是「逐字未变」的反向核对，不是改动目标
/usr/bin/grep -a -n -E '未匹配|recordWrite' cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q05ElementRecoveryHandler.java

# ② 对定位到的行区间做断言（示例：SqlViewValidator 的 L..R 由 ① 得出）
sed -n "${L},${R}p" cpq-backend/src/main/java/com/cpq/component/service/SqlViewValidator.java \
  | /usr/bin/grep -a -c -E "$PATB"          # 期望 0
sed -n "${L},${R}p" cpq-backend/src/main/java/com/cpq/component/service/SqlViewValidator.java \
  | /usr/bin/grep -a -c -E 'ds_quote_|ds_cost_'   # 期望 >= 1
```

**可观测断言**
| # | 对象 | 断言 |
|---|---|---|
| A1 | `SqlViewValidator` 文案区间 | 老表名（带 `\b`）命中 **= 0** |
| A2 | `SqlViewValidator` 文案区间 | `ds_quote_\|ds_cost_` 命中 **≥ 1** |
| A3 | `BnfPathLinter` 文案区间 | 同 A1 / A2 |
| A4 | 错误码不变 | `/usr/bin/grep -a -c 'SQL_VIEW_DEPRECATED_TABLE' SqlViewValidator.java` **≥ 1**（`api.md` 明确「错误码不改」） |
| A5 | `BnfPathLinter` 的严重度分级不变 | `PUBLISHED`=ERROR / `DRAFT`=WARN 的分支**逐字不变**（`B-9` 只改 `suggestion` 文本） |
| A6 | 日志型 5 处未被顺手改 | `QuotationService` / `PendingHygieneService` / `QuoteImportService` / `V6QuotationCommitService` / `ConfigureProductService` 五处日志文案 **逐字不变**（`git diff --stat` 里这 5 个文件若出现，逐处核对是不是只改了别的） |
| **A7** 🔴 | **`Q05ElementRecoveryHandler:90` 文案逐字未变**（`Q-1` 挂起，本期不改） | `git diff -- …/Q05ElementRecoveryHandler.java` **无输出**。🚫 若该行被改成 `ds_quote_element_bom` 判 **FAIL**（那是未经用户裁决的范围变更） |
| **A8** 🔴 | **`Q05:94` 的 `recordWrite("element_bom_item", …)` 保留** | `/usr/bin/grep -a -c 'recordWrite("element_bom_item"' …/Q05ElementRecoveryHandler.java` **≥ 1**。它是 T-5d 记账，指向真实写入表 |

**🚨 量具必须带 `\b`（实证）**
不带词边界时 `material_bom` 会命中 V44 黑名单常量 `"costing_part_material_bom"`：
```bash
/usr/bin/grep -a -n -E 'material_bom' cpq-backend/src/main/java/com/cpq/component/service/SqlViewValidator.java
# 49:            "costing_part_material_bom", "costing_part_element_bom",
```
⇒ 不带 `\b` 会把一个**已经改对**的实现判成失败。

**`AC-14` 配套阳性对照**
```bash
# 老表名 pattern 够得着（打在已知含老表名的迁移文件上）
/usr/bin/grep -a -c -E "$PATB" cpq-backend/src/main/resources/db/migration/V255__v12_create_component_sql_views.sql   # 期望 >= 1
# ds_ pattern 够得着
/usr/bin/grep -a -c -E 'ds_quote_|ds_cost_' cpq-backend/src/main/resources/db/migration/V410__task260903_compat_views.sql  # 实测 19
```
🔑 **A2 本身就是 A1 的阳性对照**：同一个 `sed` 区间、同一个 `grep -a`，
一个 pattern 返 0、另一个返 ≥1 ⇒ 证明「量具读到了这段文本」，那个 0 是真的 0。

**🔬 证伪实验（必做）**
把 `SqlViewValidator` 文案里的 `ds_quote_xxx` 临时改回 `material_master`，重跑 A1。
- 期望：A1 变成 **≥ 1**（FAIL）。
- 若仍是 0 ⇒ `sed` 的行区间取错了（量具没打在文案上），断言全程空转。
- 跑完**立即改回**并在报告写明已还原。

---

## T-10.1 · `AC-10` 全程 10 张表行数不变

**服务的 AC 原文**：
> `material_master 48 / material_bom_item 85 / element_bom_item 64 / material_bom 429 / element_bom 667 / unit_price 185 / capacity 22 / plating_scheme 12 / annual_discount 13 / material_customer_map 61`（执行前重采）——**本任务不删任何数据行**。

**前置状态**：无（任何时点可跑）。

**操作步骤**：原样重跑 `证据/基线-260909.md §1` 的命令，在**每个批次前后各跑一次**，落盘成对文件。

```bash
snap() { PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -c "
SELECT 'material_master'||'='||(SELECT count(*) FROM material_master)
UNION ALL SELECT 'material_bom_item='||(SELECT count(*) FROM material_bom_item)
UNION ALL SELECT 'element_bom_item='||(SELECT count(*) FROM element_bom_item)
UNION ALL SELECT 'material_bom='||(SELECT count(*) FROM material_bom)
UNION ALL SELECT 'element_bom='||(SELECT count(*) FROM element_bom)
UNION ALL SELECT 'unit_price='||(SELECT count(*) FROM unit_price)
UNION ALL SELECT 'capacity='||(SELECT count(*) FROM capacity)
UNION ALL SELECT 'plating_scheme='||(SELECT count(*) FROM plating_scheme)
UNION ALL SELECT 'annual_discount='||(SELECT count(*) FROM annual_discount)
UNION ALL SELECT 'material_customer_map='||(SELECT count(*) FROM material_customer_map)
ORDER BY 1;"; }
snap > /tmp/v6_before.txt   # 批次前
# … 执行批次 …
snap > /tmp/v6_after.txt    # 批次后
diff /tmp/v6_before.txt /tmp/v6_after.txt && echo "NO_ROW_CHANGE" || echo "ROW_CHANGED"
```

**可观测断言**
| # | 断言 |
|---|---|
| A1 | 每个批次的 `diff` 输出 **`NO_ROW_CHANGE`**（10 行逐字相同） |
| A2 | 首次快照的 10 个数值须**显式打印**并记入报告（防止 `snap` 因连库失败返空、两次都空 ⇒ `diff` 也 SAME 的假绿） |
| A3 | `wc -l /tmp/v6_before.txt` **= 10**（不是 0，也不是 9） |

🚩 **与 AC 原文的出入**：AC 写死了绝对值 `48/85/…`。但 §⑥ 抬头明确「执行时须先重采基线，
断言的是**相对基线的变化**」。⇒ 本用例按**差值 = 0** 断言，不按绝对值。
本轮实采恰好与文档快照逐字相同（见基线 §0），但**下次执行前必须重采**。

⚠️ **A2 是防假绿的关键**：`diff` 两个空文件也输出「相同」。这正是 `testing.md` 的第四类假绿
（自己写的验证脚本命令写错导致全程空输出，看起来和「全部通过」一模一样）。

---

## T-14.1 · `AC-14` 每个「命中 0」都有同 pattern 的阳性命中记录

**服务的 AC 原文**：
> `AC-2`/`AC-4`/`AC-9` 中每个「命中 0」的断言，都附一条同 pattern 对**已知存在**对象的命中记录（证明量具够得着）。

**前置状态**：`AC-2`/`AC-4`/`AC-9` 已各自执行完。

**操作步骤 + 可观测断言**（逐条）
| 待验的 0 值断言 | 同 pattern 阳性对照命令 | 阳性判定值 |
|---|---|---|
| `AC-2` `relname LIKE 'v_compat_%'` = 0 | `SELECT count(*) FROM pg_class WHERE relname LIKE 'v_composite_child_%'` | **= 3** |
| 同上（第二个对照） | `SELECT count(*) FROM pg_class WHERE relname LIKE 'v_ds_cost_%'` | **≥ 26** |
| `AC-4` `~* 'v_compat_'` = 0 | 探针：`count(*) FILTER (WHERE (sql_template\|\|' v_compat_probe') ~* 'v_compat_')` | **= `count(*)`** |
| `AC-4` `~* 'FROM\s+material_bom_item'` = 0 | 探针同上 | **= `count(*)`** |
| `AC-9` 老表名 = 0 | `grep -a -c -E "$PATB" V255__v12_create_component_sql_views.sql` | **≥ 1** |
| `AC-9`（文案自证） | 同区间 `grep -a -c -E 'ds_quote_\|ds_cost_'` | **≥ 1** |

**A-total**：报告里每一个写着 `0` 的格子，**旁边必须有一个非 0 的阳性格子**。缺任一即 `AC-14` FAIL。

**🔬 本轮已实证的一次「0 命中不是结论」**（写进用例作为量具纪律的实例）：
本轮用 `^\s*(###|\|)\s*(POST|GET)\s+/api/cpq/quotations` 搜 `main-api.md` 得 **0 命中**；
反查 `/api/cpq/quotations` 得 **63 命中** ⇒ 0 命中是**pattern 格式假设错了**（该文档用 `- **路径**: ` 而非表格/标题形式），
不是「文档里没有这些端点」。**同一个 0，两种截然不同的结论。**

---

# S-2 · 视图 DDL 片（🌍 全局；串行殿后，不与任何片并行）

> 写入面：`CREATE OR REPLACE VIEW` / `DROP VIEW` / `costing_bom_tree_config` 配置行。**独占 DB 对象层。**
> 🚨 本片含**唯一一处 `§3.2` 红线**（`DROP VIEW` ×3）—— 见 T-15.1，**测试员没有批准权**。

## T-12.1 · `AC-12` 批次 1 四步顺序不可颠倒（本片的主控用例，其余用例挂在它的步骤上）

**服务的 AC 原文**：
> 步骤：① 改写 3 个 composite 视图 + `costing_bom_tree_config` → ② 重启验 `AC-3` → ③ `DROP VIEW v_compat_*` ×3 → ④ 再重启验 `AC-3`。
> 断言：②④ 两次 `AC-3` 都通过。🚫 **不许先 DROP 再改写**。

**前置状态**
1. `B-2`（3 个 composite 视图改写）+ `B-3`（`costing_bom_tree_config` 2 行）已交付但**尚未 DROP**。
2. 已重采基线（G-1）。
3. `B-4` 的对照清单已交主线 → 主线已呈报用户 → **用户已确认**（`A0-4`）。未确认 ⇒ 🚫 不许进入步骤 ③。

**操作步骤 + 每步的可观测断言**

| 步 | 动作 | 断言（当步立即验，不通过即停） |
|---|---|---|
| **①** | 应用 `B-2`+`B-3` 的迁移 | `v_compat_*` 仍存在 **= 3**（证明还没 DROP）：`SELECT count(*) FROM pg_class WHERE relname LIKE 'v_compat_%'` **= 3** |
| **①-b** | 依赖面已切断 | `pg_depend` 查 `v_compat_*` 的下游 **= 0 条**（见 T-15.1 的命令）。**这一条是 ③ 能安全 DROP 的唯一前提** |
| **②** | **真重启**应用 | 跑 T-3.1（`AC-3`）→ 必须 PASS |
| **③** | `DROP VIEW` ×3 | 🚦 **停下报主线，逐个取用户批准**（T-15.1） |
| **④** | **再真重启** | 再跑一次 T-3.1（`AC-3`）→ 必须 PASS |

**顺序违规的可观测判据（用来证明「没有先 DROP 再改写」）**
```bash
# 在步骤 ① 完成、③ 未做时执行，三项必须同时成立：
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT (SELECT count(*) FROM pg_class WHERE relname LIKE 'v_compat_%')                        AS compat_still_alive,
       (SELECT count(*) FROM v_composite_child_materials)                                     AS ccm,
       (SELECT count(*) FROM pg_depend d JOIN pg_rewrite r ON r.oid=d.objid
          JOIN pg_class dep ON dep.oid=r.ev_class JOIN pg_class src ON src.oid=d.refobjid
        WHERE src.relname LIKE 'v_compat_%' AND dep.relname <> src.relname)                   AS remaining_deps;"
```
断言：`compat_still_alive = 3` **且** `ccm ≥ 当次执行前值` **且** `remaining_deps = 0`。

🔑 **`remaining_deps = 0` 是本片最有判别力的一条**：它同时证明了
①composite 视图真的改写完了（不再依赖 compat）②DROP 不会级联失败。
改动前实测 `remaining_deps = 6`（见基线 §8），是天然的阳性对照。

---

## T-3.1 · `AC-3` 3 个 composite 视图仍出行且形状不变（②④ 各跑一次）

🔴 **AC-3 已二次订正**：原文「行数 ≥ 2733」与 §④「6 个料号整个消失」**自相矛盾** ——
`materials` 在设计上就不可能 ≥ 基线（实测 **2730 < 2733**）。新判据 = **A1 逐行 diff 归因 + A2 非空 + A3 列集指纹**。

**前置状态**：`B-2` 已交付（V437/V438 已应用），应用已真重启（②/④ 步）。

### A1 · 变化面逐行归因（取代原「下界」）

🔑 **手法：用归档的旧 DDL 当子查询做 A/B，零 DDL、零写入**，且**两侧在同一快照内计数**（排除并发漂移）。
🚨 **必须用 `EXCEPT ALL` 不是 `EXCEPT`** —— `EXCEPT` 去重，行数不闭合（本轮实证：`elements` 用 `EXCEPT` 得 66，用 `EXCEPT ALL` 得 81，而只有 81 能让 `2693 − 9 + 81 = 2765` 闭合）。

```bash
cd <任务目录>/证据
for V in materials elements processes; do
  { echo "\\pset footer off"; echo "WITH old_v AS ("
    sed 's/;[[:space:]]*$//' "ddl-基线-v_composite_child_${V}.sql"; echo ")"
    echo "SELECT (SELECT count(*) FROM old_v) old_rows,"
    echo "       (SELECT count(*) FROM v_composite_child_${V}) new_rows,"
    echo "       (SELECT count(*) FROM (SELECT * FROM old_v EXCEPT ALL SELECT * FROM v_composite_child_${V}) d) only_old,"
    echo "       (SELECT count(*) FROM (SELECT * FROM v_composite_child_${V} EXCEPT ALL SELECT * FROM old_v) d) only_new;"
  } > /tmp/ab/${V}.sql
  PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -f /tmp/ab/${V}.sql
done
```

| # | 断言 | 判定值（本轮实测 2026-09-09 11:18~11:20 PDT） |
|---|---|---|
| A1-a | 行数闭合：`old_rows − only_old + only_new = new_rows` | materials `2733−24+21=2730` ✅ · elements `2693−9+81=2765` ✅ · processes `28−2+2=28` ✅ |
| A1-b | 减少行归因 | 落在「6 个老表独有料号」 |
| A1-c | 增加行归因 | 落在「`cust_scope` 白名单解禁」 |
| A1-d | 🚫 不许只报数字不报归因 | 每行增/减都要指到原因 |

### A2 · 非空

| # | 断言 | 本轮实测 |
|---|---|---|
| A2 | 三视图行数**均 > 0** | **2730 / 28 / 2765** ✅ |

🚫 不允许「没报错」当通过。

### A3 · 列集指纹逐字不变

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT c.relname, count(*) n_cols,
 md5(string_agg(a.attname||':'||format_type(a.atttypid,a.atttypmod), ',' ORDER BY a.attnum)) colset_md5
FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
WHERE c.relname IN ('v_composite_child_materials','v_composite_child_processes','v_composite_child_elements')
GROUP BY c.relname ORDER BY c.relname;"
```

| 视图 | 基线（V437 前） | 改动后实测 | 判定 |
|---|---|---|---|
| `v_composite_child_elements` | `10 / 5b68343b3dee8a01c856dd2b6a9515ba` | 逐字相同 | ✅ |
| `v_composite_child_materials` | `12 / 8c8fdb9b32bd63828d2d92ceed15537c` | 逐字相同 | ✅ |
| `v_composite_child_processes` | `9 / 217ab2621ce3302c1a881c422f973bf6` | 逐字相同 | ✅ |

🚨 **A3 不可省**：列被改名/改类型/换顺序而行数不变时，A1/A2 全 PASS，
但前端与 `ComponentDriverService` 按**列名**取值 ⇒ 线上渲染变「—」。

### 🚩 A1 顺带暴露的一个渲染可见问题（不阻塞 AC-3，须主线裁决）

`v_composite_child_elements` 的**完全重复行**由 **2 行（旧）→ 17 行（新）**。

```bash
# 新视图里全列相同的重复行
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT ROW(n.*)::text, count(*) n FROM v_composite_child_elements n GROUP BY 1 HAVING count(*)>1 ORDER BY n DESC LIMIT 5;"
# → (VS-RM03,VS-RM03,漆包线,0,1,Cu,99.950000000000,f6d10ef0-…,,00256) | 4
```

**已定性：不是 JOIN 扇出，是源数据**（`ds_quote_element_bom` 里 `VS-RM03` 本就有 4 行，
只差 `loss_rate` 0.50/0.55/0.60/0.65，`version_no` 全为 1）：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT count(*) dup_groups, sum(n)-count(*) extra_rows FROM (
  SELECT material_no, material_part_no, item_seq, element_code, content_pct, customer_no, count(*) n
  FROM ds_quote_element_bom GROUP BY 1,2,3,4,5,6 HAVING count(*)>1) g;"
# → dup_groups 8 | extra_rows 17   ← 与视图的 17 行重复逐字吻合
```

⇒ 视图**忠实透传**了源数据；它不投影 `loss_rate`，所以 4 行塌成 4 条一模一样的输出。
**渲染影响真实存在**：子件页签会出现 4 条完全相同的「Cu 99.95%」。
📌 旧路径看不到这个问题，只是因为这些料号（`VS-RM*`）**在旧视图里根本 0 行**（属新增的 +81）。

🚦 **不下结论，交主线裁决**：① 视图加去重/择一规则（如 materials 侧那样的 `DISTINCT ON`）；
② 源数据 `ds_quote_element_bom` 的 8 组重复该不该存在（`version_no` 全 1，无版本维度可区分）；
③ 认定为本期范围外，登记 BACKLOG。

---

## T-2.1 · `AC-2` 3 个兼容视图已不存在

**服务的 AC 原文**：
> `SELECT count(*) FROM pg_class WHERE relname LIKE 'v_compat_%'` = **0**（当前 3）。

**前置状态**：T-12.1 步骤 ③ 已完成（用户已逐个批准并执行 `DROP VIEW`）。

**操作步骤**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT now() AS sampled_at,
 (SELECT count(*) FROM pg_class WHERE relname LIKE 'v_compat_%')          AS compat,
 (SELECT count(*) FROM pg_class WHERE relname LIKE 'v_composite_child_%') AS positive_ctrl_1,
 (SELECT count(*) FROM pg_class WHERE relname LIKE 'v_ds_cost_%')         AS positive_ctrl_2;"
```

**可观测断言**
| # | 断言 |
|---|---|
| A1 | `compat = 0` |
| A2 | `positive_ctrl_1 = 3` ← `AC-14` 要求的同 pattern 阳性命中 |
| A3 | `positive_ctrl_2 ≥ 26` ← 第二个阳性对照 |
| A4 | 三个视图名逐个不存在：`SELECT to_regclass('public.v_compat_material_master')` → **空**（`v_compat_material_bom_item` / `v_compat_element_bom_item` 同） |

🔑 **A2/A3 与 A1 用的是同一张 `pg_class`、同一个 `LIKE` 形式** ⇒ 若 A1 得 0 而 A2 得 3，
证明「查得着表、pattern 语法没写错」，那个 0 是真的 0，不是空表假阴性。

---

## T-5.1 · `AC-5` 数据变化与对照清单逐条相符

**服务的 AC 原文**：
> - 42 个重叠料号里，**恰好 3 个单元格**取值改变（品名 1：`S-2120011658`；类型 2：`S-3120014539`、`TEST-Q13-CODE`）
> - **6 个老表独有料号**从渲染中消失：`0526-2609000001/2/3`、`0028-2609000012`、`3110520422`、`3120011203`
> - 其余 **39 个料号取值逐字不变**（阳性对照：证明变化面被限定，不是全表漂移）

**前置状态**
1. **改动前**的逐料号快照已固化 —— 本轮已抢采，见基线 §5 与下方命令（改动后就采不到了）。
2. `B-4` 对照清单已交用户确认。

**操作步骤**
```bash
# ① 改动前（本轮已跑）：固化 42 个重叠料号的 品名/类型 全量快照
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -F '|' -c "
SELECT material_no, coalesce(material_name,'<NULL>'), coalesce(material_type,'<NULL>')
FROM v_compat_material_master
WHERE material_no IN (SELECT material_no FROM material_master)
ORDER BY material_no;" > /tmp/ac5_before.txt
wc -l /tmp/ac5_before.txt      # 必须 = 48（42 重叠 + 6 老表独有）

# ② 改动后：同样口径打在新的渲染源上（v_compat_* 已 DROP，改查 ds_quote_material）
#    🔴 收敛规则必须与 v_compat_material_master 视图自己用的一致：ORDER BY material_no, customer_no
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -F '|' -c "
WITH d AS (SELECT DISTINCT ON (material_no) material_no, material_name, material_type
           FROM ds_quote_material ORDER BY material_no, customer_no)
SELECT material_no, coalesce(material_name,'<NULL>'), coalesce(material_type,'<NULL>')
FROM d WHERE material_no IN (SELECT material_no FROM material_master)
ORDER BY material_no;" > /tmp/ac5_after.txt
wc -l /tmp/ac5_after.txt       # 必须 = 42（6 个已消失）

diff /tmp/ac5_before.txt /tmp/ac5_after.txt
```

**可观测断言**
| # | 断言 | 判定值 |
|---|---|---|
| A1 | `wc -l ac5_before` | **= 48** |
| A2 | `wc -l ac5_after` | **= 42** |
| A3 | `diff` 里**仅出现左侧（`<`）**的行 | 恰好 **6** 行，且 `material_no` 恰为 `0028-2609000012` `0526-2609000001` `0526-2609000002` `0526-2609000003` `3110520422` `3120011203` |
| A4 | `diff` 里**成对出现（`<` + `>`）**的 `material_no` | 恰好 **3** 个：`S-2120011658`（品名变）、`S-3120014539`（类型变）、`TEST-Q13-CODE`（类型变） |
| A5 | **阳性对照** —— 其余 39 个料号 | 在 `diff` 中**完全不出现**。用 `comm -12 <(sort ac5_before) <(sort ac5_after) \| wc -l` **= 39** |
| A6 | 变化的 3 个单元格取值逐字 | `S-2120011658` 品名 `AgNi10/Cu-Cu/301/Cu接触桥` → `AgNi10/Cu-Cu/301/Cu接触桥（含银镍复合层与铜基体的多层复合结构，适用于高分断能力断路器）（工程验证批·极值样本）`<br>`S-3120014539` 类型 `零件` → `<NULL>`<br>`TEST-Q13-CODE` 类型 `外购件` → `<NULL>` |

🔑 **A5 是 AC 明写的阳性对照，也是本用例最关键的一条**：
「3 个变了」与「全表漂移但恰好那 3 个被看到了」在证据上长得一样，A5 是唯一能区分二者的断言。
`comm -12` 精确给出「逐字未变」的行数，比「diff 里没看到别的」强。

**本轮改动前实采（已固化，可直接当 ① 的结果）**
```
6 个将消失料号：0028-2609000012(234/零件) 0526-2609000001(触点/零件) 0526-2609000002(触点/零件)
                0526-2609000003(<空>/成品) 3110520422(AgNi10/Cu触点/零件) 3120011203(触头支架总成/零件)
3 个差异单元格：S-2120011658 品名差 · S-3120014539 类型差 · TEST-Q13-CODE 类型差
```
⇒ **与 AC 原文点名的料号逐字吻合**（改动前已验证 AC-5 的预期是准确的）。

### 🔴 `Q-8` 采纳：`B-4` 对照清单的引用面口径必须写进表头

`A0-4` 交用户确认的清单里，引用面计数**必须在表头注明口径**：

> `mbi_ref` = `material_bom_item` 中 **`material_no = X` 或 `component_no = X`** 的行数（父件 + 子件两个角色求并）
> `mb_ref`  = `material_bom` 中 `material_no = X` 的行数
> `mcm_ref` = `material_customer_map` 中 `material_no = X` 的行数

🚫 不写口径的后果（本轮实证）：审计清单写 `3110520422 mbi_ref=4`，
用户或下一个人只按 `material_no` 单列自查会得 **3**，`3120011203` 会得 **13** 而非 15，
⇒ 看起来像「数据被人改过」，实际只是**量具口径不同**。

### 🔴 收敛规则（`Q-7` 主线纠正，我原提的规则是错的）

`ds_quote_material` 有 2725 行 / 2709 个 distinct `material_no`（同料号多行），必须收敛。

**规则不是自选的，必须用 `v_compat_material_master` 视图自己的那条**
（`证据/ddl-基线-v_compat_material_master.sql:39,51` 原文）：

```sql
SELECT DISTINCT ON (ds_quote_material.material_no) …
  FROM ds_quote_material
 ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no   -- ← customer_no 升序
```

🚫 **我最初提的 `ORDER BY material_no, updated_at DESC NULLS LAST, id` 是错的。**
📌 **判据（主线给的，写下来）**：验一个视图的行为，就必须用**那个视图自己的规则**，不能另立一套。
用另一套规则算出的「改动后的值」，根本不是退役真正会产生的值 —— **对照表整体失真，且不会报错。**

**这个错今天恰好没造成失真，但那是运气**（实测量化，采样 `2026-09-09 18:04 UTC`）：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
WITH a AS (SELECT DISTINCT ON (material_no) material_no, id id_a, material_name n_a, material_type t_a
           FROM ds_quote_material ORDER BY material_no, customer_no),
     b AS (SELECT DISTINCT ON (material_no) material_no, id id_b, material_name n_b, material_type t_b
           FROM ds_quote_material ORDER BY material_no, updated_at DESC NULLS LAST, id)
SELECT count(*) total_material_no,
       count(*) FILTER (WHERE a.id_a <> b.id_b)             picked_different_row,
       count(*) FILTER (WHERE a.n_a IS DISTINCT FROM b.n_b) name_differs,
       count(*) FILTER (WHERE a.t_a IS DISTINCT FROM b.t_b) type_differs
FROM a JOIN b USING (material_no);"
#  total_material_no | picked_different_row | name_differs | type_differs
#               2709 |                   16 |            0 |            0
```

⇒ 两套规则在 **16 个料号上确实挑到不同的行**；只是那 16 行的 `material_name`/`material_type` 恰好相同，
且**没有一个落在 42 个重叠料号里**（同口径实测 `overlap_42 = 42, picked_different_row = 0`）。
**「碰巧一致」不是「等价」** —— 数据一变就会失真。⇒ 规则必须写死为视图自己的那条。

✅ **已用正确规则重算，`AC-5` 的差异面结论不变**（采样 `2026-09-09 18:04:05 UTC`）：
仍是 `S-2120011658` 品名差、`S-3120014539` 类型差、`TEST-Q13-CODE` 类型差，**恰好 3 个单元格**。

🚦 **`B-2`/`B-4` 必须用同一条规则**（`ORDER BY material_no, customer_no`），不许两边各写各的。

---

## T-6.1 · `AC-6` 桥换表后 `sales_material_no` 逐值不变

**服务的 AC 原文**：
> `v_ds_cost_basic_element_bom_all` / `v_ds_cost_detail_element_bom_all` 的 `(production_no, sales_material_no)` 对，
> 改动前后 **md5 指纹逐字相同**。当前基线：basic **15 行 / 7 行有值**，detail **7 行 / 7 行有值**。

**前置状态**：`B-7` 已交付（视图换桥 + 守卫同批）。改动前指纹已固化（基线 §4）。

**操作步骤**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT now() AS sampled_at;
SELECT 'basic' v, count(*) rows_total, count(*) FILTER (WHERE sales_material_no IS NOT NULL) with_sales,
 md5(coalesce(string_agg(coalesce(production_no,'<NULL>')||'|'||coalesce(sales_material_no,'<NULL>'), E'\n'
  ORDER BY coalesce(production_no,'<NULL>'), coalesce(sales_material_no,'<NULL>')),'')) fp
FROM v_ds_cost_basic_element_bom_all
UNION ALL SELECT 'detail', count(*), count(*) FILTER (WHERE sales_material_no IS NOT NULL),
 md5(coalesce(string_agg(coalesce(production_no,'<NULL>')||'|'||coalesce(sales_material_no,'<NULL>'), E'\n'
  ORDER BY coalesce(production_no,'<NULL>'), coalesce(sales_material_no,'<NULL>')),''))
FROM v_ds_cost_detail_element_bom_all;"
```

**可观测断言**
| # | 断言 | 判定值（本轮基线；执行前须重采） |
|---|---|---|
| A1 | basic `fp` 逐字相同 | `869527eb4be855032f2fe35932da4fc9` |
| A2 | detail `fp` 逐字相同 | `a26e0b755ce8149fcdbd7f7eea421485` |
| A3 | basic `rows_total / with_sales` | `15 / 7` |
| A4 | detail `rows_total / with_sales` | `7 / 7` |
| A5 | 逐值明细逐行相同 | basic: `300013\|<空>\|6`、`300015\|<空>\|2`、`3120014539\|S-3120014539\|7`；detail: `3120014539\|S-3120014539\|7` |
| A6 | 列集指纹不变 | 两视图均 `16 列 / 2914935efcb8508bfae448fe97a33568` |

**🚨 `AC-6` 判别力仅为 1 个样本 —— 必须显式说明（这是我认为最可能验不出问题的一条，详见文末）**
全表只有 **1 个 `production_no` 能桥到销售料号**（`3120014539 → S-3120014539`），
另两个（`300013`/`300015`）**两侧都桥不到**（`NULL`）。
⇒ 桥逻辑写错时（JOIN 条件反了、`LIMIT 1` 的 `ORDER BY` 丢了、`LEFT` 写成 `INNER`），
**只有那 1 个值有机会把它抓出来**；而如果错法恰好是「全都桥不到」，指纹里 2/3 的行本来就是 `<NULL>`，
只有 1 行会变 —— 指纹会变，但**变化幅度极小，且换个数据环境就完全测不出来**。

### 🔴 补强用例 T-6.2 · 全域桥等价对照（`Q-5` 采纳，已实测基线）

**服务的 AC**：`AC-6`（补强 T-6.1 的样本量问题）

**为什么需要它**：T-6.1 的有效样本量 = 1（见上）。T-6.2 把对照面从「视图里出现的 3 个 `production_no`」
扩到**全域 2682 个**，让「换桥会不会改值」这个问题真正可被证伪。

**操作步骤（只读，改动前后各跑一次）**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT now() AS sampled_at;
WITH pn AS (
  SELECT DISTINCT production_no FROM material_master     WHERE production_no IS NOT NULL
  UNION SELECT DISTINCT production_no FROM ds_quote_material WHERE production_no IS NOT NULL
), b AS (
  SELECT pn.production_no,
    (SELECT m.material_no FROM material_master m
       WHERE m.production_no = pn.production_no ORDER BY m.material_no LIMIT 1) AS o,
    (SELECT m.material_no FROM ds_quote_material m
       WHERE m.production_no = pn.production_no ORDER BY m.material_no LIMIT 1) AS n
  FROM pn)
SELECT count(*)                                                          AS total_production_no,
       count(*) FILTER (WHERE o IS NOT NULL AND n IS NOT NULL AND o = n) AS b1_same,
       count(*) FILTER (WHERE o IS NULL     AND n IS NULL)               AS b2_both_null,
       count(*) FILTER (WHERE o IS NOT NULL AND n IS NULL)               AS b3a_bridge_lost,
       count(*) FILTER (WHERE o IS NOT NULL AND n IS NOT NULL AND o<>n)  AS b3b_bridge_changed,
       count(*) FILTER (WHERE o IS NULL     AND n IS NOT NULL)           AS b3c_bridge_gained
FROM b;"
```

**改动前实测基线**（采样 `2026-09-09 18:07:12.651407+00`）：

```
 total_production_no | b1_same | b2_both_null | b3a_bridge_lost | b3b_bridge_changed | b3c_bridge_gained
---------------------+---------+--------------+-----------------+--------------------+-------------------
                2682 |      21 |            0 |               1 |                  0 |              2660
```

**🔴 对主线「三档计数，不一致必须为 0」的一处更正**

主线裁决写的是「三档计数，**不一致必须为 0**」。**按字面执行会大面积假红**：
实测「不一致」（`o IS DISTINCT FROM n`）= **2661**，占全域 99.2%。

原因是**两张表的规模本就不对等**：`material_master` 只有 48 行，`ds_quote_material` 有 2725 行。
⇒ 绝大多数 `production_no` 是「老桥桥不到、新桥桥得到」（`b3c = 2660`），
这是**新表覆盖面更广**，不是回归。把它们算进「不一致」会淹没真正的问题。

⇒ **「不一致」必须拆成三档**，判据只压在前两档上：

| 档 | 含义 | 是否回归 | 断言 |
|---|---|---|---|
| **b3a** `o≠NULL, n=NULL` | **桥失效** —— 原来桥得到，换表后桥不到 | ✅ **是回归** | 落在视图 scope 内的 **必须 = 0** |
| **b3b** `o≠n` 且两者非空 | **桥改值** —— 桥到了不同的销售料号 | ✅ **是回归** | **全域必须 = 0** |
| **b3c** `o=NULL, n≠NULL` | **新桥扩面** —— 新表能桥更多 | ❌ 不是回归 | 不设上限；但落在视图 scope 内的须**逐个归因**（会让 `sales_material_no` 由 NULL 变有值 ⇒ `AC-6` 指纹必变） |

**可观测断言**
| # | 断言 | 判定值（改动前实测） |
|---|---|---|
| B1 | `b3b_bridge_changed`（全域桥改值） | **= 0** ✅ 改动前已满足 |
| B2 | `b3a_bridge_lost` **落在视图 scope 内**的条数 | **= 0** ✅ 改动前已满足（唯一的 1 条不在 scope 内，见 B4） |
| B3 | `b3c_bridge_gained` **落在视图 scope 内**的条数 | **= 0** ✅ 改动前已满足（否则 `AC-6` 的指纹必变，T-6.1 会红） |
| B4 | 唯一的 `b3a` 逐字点名 | `SC3120011203`：`old_bridge = 3120011203` → `new_bridge = <NULL>`，`in_view_scope = f`，`ebi_rows = 0` |
| B5 | B4 与 `AC-5` 自洽 | `3120011203` **正是 `AC-5` 那 6 个「将消失料号」之一** ⇒ 桥失效是 `AC-5` 已覆盖、且已走 `A0-4` 用户确认的**预期后果**，不是新增风险 |

**scope 内逐条核对命令**
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
WITH pn AS (
  SELECT DISTINCT production_no FROM material_master WHERE production_no IS NOT NULL
  UNION SELECT DISTINCT production_no FROM ds_quote_material WHERE production_no IS NOT NULL
), b AS (
  SELECT pn.production_no,
    (SELECT m.material_no FROM material_master m WHERE m.production_no=pn.production_no ORDER BY m.material_no LIMIT 1) o,
    (SELECT m.material_no FROM ds_quote_material m WHERE m.production_no=pn.production_no ORDER BY m.material_no LIMIT 1) n
  FROM pn)
SELECT production_no, o AS old_bridge, n AS new_bridge
FROM b
WHERE o IS DISTINCT FROM n
  AND production_no IN (SELECT production_no FROM v_ds_cost_basic_element_bom_all
                        UNION SELECT production_no FROM v_ds_cost_detail_element_bom_all);"
# 改动前实测：0 行 ⇒ 视图 scope 内两套桥完全等价 ⇒ AC-6 的指纹今天必然守得住
```

🔑 **这条 0 行结果是 `AC-6` 今天能通过的真正原因**，也说明 T-6.1 的「指纹不变」
**不是因为桥逻辑对，而是因为 scope 太小恰好碰不到差异**。
⇒ **数据一多（`b3c` 那 2660 个里任何一个进入 `element_bom_item`），`AC-6` 就会红。**
🚦 **这是须登记的将来风险**：`AC-6` 红时的第一判据是「是不是 `b3c` 扩面导致」，
若是，那是**新表覆盖更广的正常表现，不是回归** —— 届时须更新 `AC-6` 的基线指纹而非回滚代码。

---

## T-13.1 · `AC-13` 回滚演练

**服务的 AC 原文**：
> 批次 1 执行后，用 `V410`/`V415`/`V428`/`V429` 的视图 DDL **原样重建** 3 个 `v_compat_*`，
> 重建后 `AC-3` 仍通过、`v_compat_material_master` 行数回到基线 **2715**。
> 🚫 **不许只在文档里写「可回滚」** —— 必须真跑一次。

**前置状态**：T-2.1 已 PASS（3 个视图已 DROP）。

**操作步骤**
```bash
# ① 用 V410/V415/V428/V429 的 DDL 原样重建（不许自己另写一版）
# ② 验行数
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT now() AS sampled_at,
 (SELECT count(*) FROM v_compat_material_master)   cmm,
 (SELECT count(*) FROM v_compat_material_bom_item) cmbi,
 (SELECT count(*) FROM v_compat_element_bom_item)  cebi;"
# ③ 验 DDL 逐字相同（比行数强得多）
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT viewname, md5(pg_get_viewdef(('public.'||viewname)::regclass,true)) ddl_md5
FROM pg_views WHERE viewname LIKE 'v_compat_%' ORDER BY viewname;"
# ④ 复跑 T-3.1（AC-3）
```

**可观测断言**
| # | 断言 | 判定值（本轮基线） |
|---|---|---|
| A1 | `cmm` | **= 2715**（AC 明写） |
| A2 | `cmbi` | **= 2774** |
| A3 | `cebi` | **= 2748** |
| **A4** ✅ | **DDL md5 逐字相同**（`Q-6` 采纳） | `v_compat_element_bom_item` `64116a232a98e34f4dbe7c4a5804aef8`<br>`v_compat_material_bom_item` `40f7b710b79e09f9c062b720d112a3a2`<br>`v_compat_material_master` `aaf2464d9df839c8cf6aabac88a071fc` |
| A5 | 复跑 T-3.1 | 全部 PASS |
| A6 | 演练后状态复原 | 演练结束须再次 DROP 回到「已退役」状态；**再次 DROP 同样是红线，须再次取用户批准**（`AC-15` 批准不跨对象、不跨次） |

🔴 **A4 已按 `Q-6` 采纳并回写 `需求文档.md`**：AC 原文只要求「行数回到 2715」。但**行数相同而定义不同是完全可能的**
（例如 `cust_scope` 白名单条件写漏，恰好当前数据下行数一样）。
`pg_get_viewdef(..., true)` 的 md5 才是「原样重建」的判据。
8 份基线 DDL 全文已归档在 `证据/ddl-基线-<viewname>.sql`。

---

## T-15.1 · `AC-15` `§3.2` 红线留痕

**服务的 AC 原文**：
> `DROP VIEW` ×3 执行前，逐个给出「影响面数字 + 可恢复路径 + 用户批准记录」三项；缺任一项即不许执行。
> 🚫 **批准不跨对象**：批了删 A 视图不等于批了删 B 视图。

**前置状态**：T-12.1 步骤 ①/①-b 已 PASS（依赖已切断）。

**操作步骤（三项材料的采集命令）**
```bash
# 材料 1：影响面数字 —— 依赖对象数（必须为 0 才允许 DROP）
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT src.relname compat_view, count(DISTINCT dep.relname) AS depended_by_cnt,
       coalesce(string_agg(DISTINCT dep.relname, ', '),'(none)') AS depended_by
FROM pg_class src
LEFT JOIN pg_depend d ON d.refobjid=src.oid
LEFT JOIN pg_rewrite r ON r.oid=d.objid
LEFT JOIN pg_class dep ON dep.oid=r.ev_class AND dep.relname <> src.relname
WHERE src.relname LIKE 'v_compat_%' GROUP BY 1 ORDER BY 1;"

# 材料 1-b：配置层文本引用（pg_depend 看不到，DROP 不会失败但运行期会坏）
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT 'costing_bom_tree_config' src, count(*) FILTER (WHERE sql_template ~* 'v_compat_') hits FROM costing_bom_tree_config
UNION ALL SELECT 'component_sql_view', count(*) FILTER (WHERE sql_template ~* 'v_compat_') FROM component_sql_view;"

# 材料 1-c：各视图当前行数（影响面的量化）
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT (SELECT count(*) FROM v_compat_material_master) cmm,
       (SELECT count(*) FROM v_compat_material_bom_item) cmbi,
       (SELECT count(*) FROM v_compat_element_bom_item) cebi;"
```

**可观测断言（这是一条「流程留痕」用例，断言的是材料齐全度）**
| # | 断言 |
|---|---|
| A1 | **每个视图**各有一份独立留痕记录，共 **3 份**（不是 1 份合并的） |
| A2 | 每份含【影响面】：`depended_by_cnt` **= 0** + 该视图行数（基线 2715 / 2774 / 2748） |
| A3 | 每份含【可恢复性】：指明重建 DDL 的来源迁移号（`V410`/`V415`/`V428`/`V429`）+ 归档路径 `证据/ddl-基线-<viewname>.sql` + 明写「视图无数据，DDL 可原样重建」 |
| A4 | 每份含【用户批准】：用户**针对该视图名**的明确回话原文 + 时刻 |
| A5 | 材料 1-b 的 `costing_bom_tree_config` hits **= 0**（`B-3` 已改完），`component_sql_view` hits **= 0** |
| A6 | 三份留痕**都在 `DROP` 执行之前**产生（时刻可核） |

🚫 **测试员没有批准权。** 本用例的执行动作是「**采集材料 + 停下报主线**」，
🚫 **不许由测试员发起 `DROP VIEW`**，`DROP` 由主线在取得用户逐个批准后执行。
被 hook 拒了**不要换写法重试** —— 停下报主线。

---

# S-4 · 启动期片（🌍 全局；独占进程，紧跟 S-2）

## T-7.1 · `AC-7` 应用能重启，`CostAllVersionViewSelfCheck` 不抛

**服务的 AC 原文**：
> 改视图 + 守卫同批提交后，`./mvnw quarkus:dev` **能起来**，`CostAllVersionViewSelfCheck` 不抛 `IllegalStateException`；
> 业务端点返 401（应用在跑、鉴权正常）。🚨 该守卫在 `@Observes StartupEvent` 双向比对视图与主表列集，**多一列即起不来**。

**前置状态**
1. `B-7` 已交付（视图换桥 + 守卫**同一批**提交）。
2. 🚨 **独占进程**：本片跑之前确认没有别的会话在用同一端口。**不占 8081**（共享 dev server 跑主仓代码），
   用**临时端口**起 worktree 的实例。

**操作步骤**
```bash
cd /home/joii/project/cpq/.claude/worktrees/task-260909-v6-retire/cpq-backend
PORT=8099
./mvnw quarkus:dev -Dquarkus.http.port=$PORT > /tmp/quarkus-$PORT.log 2>&1 &
# 轮询直到端口有响应或超时 180s
```

**可观测断言**
| # | 断言 | 判定值 |
|---|---|---|
| A1 | 启动日志无守卫异常 | `/usr/bin/grep -a -c 'IllegalStateException' /tmp/quarkus-8099.log` **= 0** |
| A2 | 守卫**确实跑过了** | `/usr/bin/grep -a -c 'CostAllVersionViewSelfCheck' /tmp/quarkus-8099.log` **≥ 1** |
| A3 | 应用起来了 | `/usr/bin/grep -a -c 'Listening on: http://0.0.0.0:8099' /tmp/quarkus-8099.log` **≥ 1** |
| A4 | 业务端点鉴权正常 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8099/api/cpq/components` → **`401`** |
| A5 | 起的是 worktree 的代码 | 日志里的项目路径含 `worktrees/task-260909-v6-retire` |
| A6 | 连的是预期的库 | `GET /api/cpq/quotations?page=1&size=1` 的 `totalElements` 与 `SELECT count(*) FROM quotation` 对得上（`CLAUDE.md` 的认库手法） |

🚨 **A2 是本用例的命门（`test.md` 纪律 3）**：
`CostAllVersionViewSelfCheck` 只活在 `@Observes StartupEvent` 上。
若它因为开关关闭/条件不满足**根本没跑**，A1 的「0 个 IllegalStateException」照样成立 —— **纯假绿**。
⇒ **必须先证明守卫跑过了（A2），A1 才有意义。**
若 A2 得 0：先反向证明日志量具够得着（`grep -a -c 'Listening on' log` ≥ 1），
再报主线「守卫未执行，`AC-7` 无法验证」，🚫 不许写「没报错所以通过」。

**🔬 证伪实验（必做）**
在事务外给 `v_ds_cost_basic_element_bom_all` **加一列**（`CREATE OR REPLACE VIEW … , 1 AS probe_col`），重启。
- 期望：启动 **失败**，日志出现 `IllegalStateException`。
- 若照常起来 ⇒ 守卫没接上，`AC-7` 全程是空验证。
- 🚨 跑完**必须把视图改回**（用 `证据/ddl-基线-v_ds_cost_basic_element_bom_all.sql` 原样重建）并复验 md5。
- 🚦 这个实验会改共享库的视图定义 —— **执行前报主线，取得同意再做**。

🚫 **不许用事务内 `CREATE OR REPLACE` + `ROLLBACK` 代替真重启**（`test.md` 纪律 3：
量具所在的层，和失败发生的层，不是同一层）。

---

# S-3 · 业务序列片（造数前缀 `RETIRE0909-`；可与 S-1 并行，🚫 不可与 S-2 并行）

> 🚨 **解锁条件**：`B-8` 已交付。**且视图层未在同时变动**（S-2 不在跑）。

## T-11.1 · `AC-11` 完整业务序列后 pending 不增长

**服务的 AC 原文**：
> 步骤：造一个**带 `batchParts` 的真导入批次** → 建单 → 编辑保存 → 提交 → 核价通过 → 刷新页面。
> 断言：全程结束后 10 张表 pending 计数 **= 执行前基线**（当前 14），且 `_record` 侧回填正常（`ds_quote_*_record` 有对应行）。
> 🚨 **若 pending 计数变化，`P-2` 的作废裁决立即失效**。

**前置状态**
1. 后端实例可用（可复用 S-4 起的 8099，或共享 8081 —— 但 8081 跑的是**主仓代码**，🚫 不能用来验 `B-8`）。
2. 🚨 **必须先闭合 P-2 的空验证坑**：本单**必须有明细行**。
   立项文档记录的失败正是「所选批次 `batchParts = 0` ⇒ 建出 0 明细行 ⇒ 什么都没写，pending 没变当然成立」。

**前置断言（不满足即停，不许继续跑本用例）**
```bash
# 候选料号面：CUST-0004（正泰）在 ds_quote_customer_part 有 2663 行（本轮实测）
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT customer_no, count(*) FROM ds_quote_customer_part GROUP BY 1 ORDER BY 2 DESC LIMIT 5;"
# 用 API 确认候选非空（customerId = 1f5818d8-b934-44be-b1c3-47df7053cff4 = CUST-0004 正泰）
curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8099/api/cpq/quotations/customer-part-candidates?customerId=1f5818d8-b934-44be-b1c3-47df7053cff4" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("CANDIDATES=",len(d.get("data") or []))'
```
| # | 前置断言 | 判定值 |
|---|---|---|
| P1 | `CANDIDATES` | **≥ 1**。**= 0 ⇒ 立即停止，报主线「本用例会退化成 P-2 那种空验证」** |

**操作步骤**
```bash
BASE=http://localhost:8099/api/cpq
PRE=RETIRE0909-

# ① 执行前采（10 表 pending 计数 + 指纹 + 行数）—— 原样重跑 基线-260909.md §1 §2
# ② 建单
curl -s --noproxy '*' -X POST "$BASE/quotations" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"customerId":"1f5818d8-b934-44be-b1c3-47df7053cff4","name":"RETIRE0909-AC11-序列验证单"}'
# ③ 加明细（Step2 从候选批量导入产品）→ ④ PUT /quotations/{id}/draft 编辑保存
# ⑤ POST /quotations/{id}/submit 提交
# ⑥ POST /quotations/{id}/costing-approve 核价通过
# ⑦ GET  /quotations/{id} 刷新（模拟前端刷新页面）
# ⑧ 执行后采（同 ①）
```

**可观测断言**
| # | 断言 | 判定值 |
|---|---|---|
| A1 | **本单有明细行** | `SELECT count(*) FROM quotation_line_item WHERE quotation_id='<新单id>'` **≥ 1**。**= 0 ⇒ 本用例作废重做**（这是 P-2 的原样教训，`AC` 的「刷新页面」之前必须真有数据） |
| A2 | 10 张表 pending 计数 | 逐表**与执行前值差值 = 0**；总计仍 **14**（本轮基线） |
| A3 | `annual_discount` / `material_master` 的 `pending_fp` | 逐字相同（`18e7ef86…` / `e6a48fb2…`）。**其余 8 张表指纹恒为空集 md5，无判别力**（见下） |
| A4 | 10 张表行数 | 逐表差值 = 0（`AC-10` 同款 `snap()` 前后 `diff`） |
| A5 | `_record` 侧回填 | `SELECT count(*) FROM ds_quote_material_bom_record WHERE quotation_id='<新单id>'` **≥ 1**（**存在性断言**，🚫 不许写 `= 9560 + N` 那种全局计数） |
| A6 | 单据状态推进到位 | `SELECT status FROM quotation WHERE id='<新单id>'` = 核价通过后的目标态（逐字记录实际值） |
| A7 | 每步之间各采一次 pending | 定位到**是哪一步**动了 pending（若真动了）；只在头尾采会说不出归因 |

**⚠️ A3 的判别力说明（基线 §2 已实证）**
10 张表里 **8 张的 pending 集合为空**，指纹恒为 `d41d8cd98f00b204e9800998ecf8427e`（空串 md5）。
「空集指纹不变」几乎不可能失败 ⇒ 对这 8 张表，真实量具是 **`pending_cnt` 仍为 0** 与 **行数不变**，
不是指纹。**报告里不许把这 8 个「指纹相同」当成 8 条独立证据。**

**`finally` 清理（G-3）**
```bash
# 只清本片自造的那批（前缀限定），逐条打印将删的 id 后再删；由主线批准后执行
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "
SELECT id, quotation_no, name FROM quotation WHERE name LIKE 'RETIRE0909-%';"
```
🚦 **删除报价单属于数据销毁**（`§3.2`）—— 测试员**只列清单，不执行删除**，交主线取用户批准。
🚫 不许在 `beforeAll`/`finally` 里写无 `WHERE` 或命中面不明的 `DELETE`。

**🚨 兜底闸（`test.md` 纪律 4）**
若 A2/A3 发现 pending 计数变化 ⇒ **`P-2` 的作废裁决立即失效**，
**停下报主线**，回头重做建单臂并重新评估 `D-1`。🚫 不许自行判定「大概是并发写的」。

---

## T-8.1 · `AC-8` pending 写点全部 no-op

**服务的 AC 原文**：
> 以下写点方法签名**仍在**（可回滚），但内部对 10 张表**零写入**，且标 `@Deprecated`：
> `V6QuotationCommitService:139` `repointPendingOwnership` · `QuoteBackfillService:77` `flipPending` ·
> `QuotationService:2332` / `PendingHygieneService:123` / `QuoteImportService:285` `deletePendingWithGuard`

🔴 **AC 原文的「验证方式」（改动前后各跑一次完整建单流程，行数与指纹不变）已被主线裁定作废**
（`Q-3` 采纳乙 + 追加 A3）。作废理由见本用例文末「为什么原验证方式是空验证」。
**本用例按裁决后的三段式执行：A1 结构面 → A2 行为面 → A3 还原实验。**

**前置状态**
1. `B-8` 已交付。
2. 🚨 **`B-1` 已交付并通过 `AC-1`（T-1.1）** —— A2 必须在**独立测试库**上造 pending 行，
   🚫 在共享 dev 库上造会与 `AC-10`「10 张表行数不变」直接冲突。
   📌 **`B-1` 不只是卫生问题：它解锁了本任务唯一有判别力的行为面验证。**

**⚠️ 本用例的结构面步骤会 grep `cpq-backend/src/main`，A2 需写 Java 测试。**
按本轮派工的可读范围限制（用例必须独立于实现），**我本轮未执行任何一段**，
只把它们写成可执行的验收动作。🚫 无论谁跑，**都不得据此反过来修改断言** —— 断言来自 AC 原文。

---

### A1 · 结构面（签名保留 + `@Deprecated` + 三份清单不等长）

```bash
cd /home/joii/project/cpq/.claude/worktrees/task-260909-v6-retire
for m in repointPendingOwnership flipPending deletePendingWithGuard; do
  echo "== $m"; /usr/bin/grep -a -rn "$m" cpq-backend/src/main/java/ | head -20
done
/usr/bin/grep -a -rn -B3 -E '(repointPendingOwnership|flipPending|deletePendingWithGuard)\s*\(' \
  cpq-backend/src/main/java/ | /usr/bin/grep -a -c '@Deprecated'
```

| # | 断言 | 判定值 |
|---|---|---|
| A1-a | **5 个写点**的方法签名仍在（`A0-1` 乙，可回滚） | 每处 `grep` 命中 **≥ 1** |
| A1-b | 均标 `@Deprecated` | 每个方法声明前 3 行内出现 `@Deprecated` |
| A1-c | Javadoc 写明摘除来源 | 每处 Javadoc 含 `task-260909` 字样 **≥ 1** |
| A1-d | 三份 pending 清单**未被顺手对齐成一样长** | 清单长度仍为 **9 / 8 / 10**（`material_master` 因须走引用守卫，仍不在删除清单里）。🚫 变成 10/10/10 判 **FAIL** |
| A1-e | `pending_quotation_id` **列仍在**（`A0-2` 甲） | 10 张表逐表 `SELECT count(*) FROM pg_attribute WHERE attrelid='<t>'::regclass AND attname='pending_quotation_id'` **= 1** |
| A1-f | `MaterialMasterCrudService` 读过滤**仍在**（`A0-3` 乙） | `/usr/bin/grep -a -c 'pendingQuotationId' …/MaterialMasterCrudService.java` **≥ 1** |

---

### A2 · 行为面（🔑 本任务唯一有判别力的正向量具）

**前置状态**：`B-1` 已落地，测试跑在**独立测试库**上（T-1.1 的 A5 已证明该库真的被写）。

**操作步骤**（后端测试，在独立测试库上跑）
1. 造一条**真实的 `import_record`**，记下其 `id`（记作 `IR`）。
2. 在 `material_master`（或任一 pending 清单内的表）造一行，`pending_quotation_id = IR`，
   料号带 `RETIRE0909-` 前缀。**记下该行 `id` 与 `pending_quotation_id` 原值。**
3. 调用 `repointPendingOwnership(IR, <新 quotationId>)`。
4. 重读该行。

| # | 断言 | 判定值 |
|---|---|---|
| A2-a | 该行的 `pending_quotation_id` | **仍为 `IR`，逐字未被改写**（no-op 生效） |
| A2-b | 造的 pending 行**确实存在**（防空转） | 步骤 2 之后 `SELECT count(*) … WHERE pending_quotation_id = IR` **= 1**，且断言前显式打印 |
| A2-c | `IR` 是真实存在的 `import_record` | `SELECT count(*) FROM import_record WHERE id = IR` **= 1** |
| A2-d | 同法覆盖 `flipPending` | 调用后该行 `pending_quotation_id` **仍非 NULL** |
| A2-e | 同法覆盖 `deletePendingWithGuard` | 调用后该行**仍在**（`count(*) = 1`） |

🚨 **A2-b/A2-c 是防空转的命门**：若 pending 行根本没造出来，或 `IR` 不是真实 `import_record`，
那么「`pending_quotation_id` 没被改写」会**因为没有对象可改**而恒成立 —— 又掉回同一个坑。
**必须在断言前打印实际值，不许只看最终布尔。**

---

### A3 · 还原实验（🔑 主线追加，不可省）

**没有 A3，A2 只是「跑了一次没事」，无法证明量具有效。**

**操作步骤**
1. 把 `B-8` 的改动撤回（用 WIP 提交回退或 `git stash push -u -m 'ac8-falsify-<ts>'` 后 `git stash apply <sha>`；
   🚫 **不许用裸 `git stash` / `git stash pop`** —— stash 栈与主仓及其它 worktree 共享）。
2. 原样重跑 A2。
3. 恢复 `B-8` 的改动，再跑一次 A2 确认恢复。

| # | 断言 | 判定值 |
|---|---|---|
| A3-a | 撤回 `B-8` 后重跑 A2 | **必须变红** —— A2-a 应观测到 `pending_quotation_id` **被改写成新的 quotationId**（老代码的过户行为） |
| A3-b | 恢复后重跑 A2 | 回到 **PASS** |
| A3-c | 报告须记录 | A3-a 观测到的**实际改写后的值**（不是只写「变红了」） |

🚫 **A3-a 若不变红 ⇒ A2 是空验证**，`AC-8` 判为**未验证**，停下报主线。
🚫 **不许以「撤回麻烦/风险大」为由跳过 A3** —— `testing.md`：首次 PASS 证明不了守卫接上了。

---

### 📌 为什么 AC 原文的验证方式是空验证（留档，避免下一个人重走）

AC 原文写的是「改动前后各跑一次完整建单流程，10 张表行数与 pending 指纹均不变」。两条依据（主线已复核属实）：

1. **`repointPendingOwnership` 当前命中面为 0**（立项文档 §③ P-2 实测：挂在「存在的 `import_record`」上的
   pending 行数全部为 0）⇒ 老代码的 `UPDATE … WHERE pending_quotation_id = :importRecordId` **命中 0 行**。
   ⇒ 改动前跑不变，改动后跑也不变 —— **B-8 做了和没做给出完全相同的绿。**
2. **10 张表里 8 张 pending 集合为空**，指纹恒为 `d41d8cd98f00b204e9800998ecf8427e`（= 空串 md5，
   `printf '' | md5sum` 可验）⇒「空集指纹不变」不可能失败，**这 8 个「相同」不是 8 条独立证据。**

⇒ 一个声称验「写点已改 no-op」这个**正向事实**的 AC，用了一个无论如何都会通过的量具。

📌 **`AC-11` 有同样的性质但可以接受**：它本来就是「无回归 / 兜底闸」，
空验证是其设计的一部分；`AC-8` 不是。

---

# 附录 A · AC 覆盖矩阵（正向 + 反向）

> 🔴 已按主线 2026-09-09 审用例裁决更新（`Q-1`~`Q-8`，7 条采纳 / `Q-1` 挂起等用户 / `Q-7` 是对我的纠正）。

| AC | 用例 | 分片 | 可观测断言核心 | 判别力评估 |
|---|---|---|---|---|
| AC-1 | T-1.1 | S-5 | `max(updated_at)` 逐字不变 + `Tests run ≥1, Failures 0` | 中（有证伪实验；受并发干扰）。🔑 **它同时解锁 AC-8 的 A2** |
| AC-2 | T-2.1 | S-2 | `pg_class LIKE 'v_compat_%'` = 0 + 阳性对照 3/26 | 强 |
| AC-3 | T-3.1 | S-2 | 下界 + **上界 ≤×1.05** + **列集指纹**（`Q-4` 采纳） | 补强后强 |
| AC-4 | T-4.1 | S-1 | 按 `id` 逐行 `has_compat/has_old_mbi` = 0 + 探针对照 | 强 |
| AC-5 | T-5.1 | S-2 | 6 消失 / 3 变 / 39 逐字不变（`comm -12` = 39）<br>🔴 收敛规则 = 视图自己的 `ORDER BY material_no, customer_no`（`Q-7`） | 强 |
| AC-6 | T-6.1 + **T-6.2** | S-2 | 指纹逐字相同 + **全域四档对照**（`Q-5` 采纳） | T-6.1 单独弱（样本=1）；加 T-6.2 后强 |
| AC-7 | T-7.1 | S-4 | 真重启 + **守卫确实执行过 A2** + 401 | 补 A2 后强 |
| AC-8 | T-8.1 | S-3 | **A1 结构面 + A2 行为面（造 pending 行调 no-op）+ A3 还原实验**（`Q-3` 采纳乙 + 主线追加 A3） | 原 AC 空验证 → 补强后强。**依赖 `B-1` 先落地** |
| AC-9 | T-9.1 | S-1 | **2 处**祈使型：文案行老表名 0 + `ds_` ≥1 + `\b` 词边界<br>🔴 第 3 处（`Q05`）改为「逐字未变」反向断言 A7/A8 | 中（`Q-1` 待用户裁决） |
| AC-10 | T-10.1 | S-1 | `diff` 前后快照 + A2/A3 防空文件假绿 | 强 |
| AC-11 | T-11.1 | S-3 | pending 差值 0 + **P1 前置断言防空验证** + `_record` 存在性 | 中（本就是兜底闸；P1 闭合了 P-2 的坑） |
| AC-12 | T-12.1 | S-2 | `compat_still_alive=3` + **`remaining_deps` 6→0** | 强 |
| AC-13 | T-13.1 | S-2 | 行数 2715/2774/2748 + **DDL md5 逐字相同**（`Q-6` 采纳） | 补强后强 |
| AC-14 | T-14.1 | S-1 | 每个 0 旁有非 0 阳性格 | 强 |
| AC-15 | T-15.1 | S-2 | 3 份独立留痕 × 3 项材料 | 强（流程类） |

**反向覆盖**：本文件 16 条用例（T-1.1 / 2.1 / 3.1 / 4.1 / 5.1 / 6.1 / 6.2 / 7.1 / 8.1 / 9.1 / 10.1 / 11.1 / 12.1 / 13.1 / 14.1 / 15.1）
**每条都指回一条 AC**，无孤儿用例。T-6.2 是 T-6.1 的补强，同指 AC-6。

# 附录 B · 证伪实验清单（`testing.md`：首次 PASS 证明不了守卫接上了）

| 用例 | 证伪动作 | 期望结果 | 是否需主线批准 |
|---|---|---|---|
| T-1.1 | 配置临时改回 `cpq_db_0724` 重跑 | `diff` 输出 `CHANGED` | 否（可逆，自还原） |
| T-3.1 | 事务内 `CREATE OR REPLACE … WHERE false` + `ROLLBACK` | A1/A4 FAIL | 否（事务内） |
| T-6.2 | 无需额外证伪 —— **改动前实测 `b3b=0`、scope 内不一致 0 行**，本身即阳性/阴性双向可读 | — | 否 |
| T-7.1 | 给 `v_ds_cost_basic_element_bom_all` 加一列后重启 | 启动抛 `IllegalStateException` | 🚦 **是**（改共享库视图定义） |
| **T-8.1 A3** 🔴 | **撤回 `B-8` 重跑 A2**（主线追加，**不可省**） | **A2 必须变红**：观测到 `pending_quotation_id` 被改写成新 quotationId | 否（但须用 WIP 提交或带 tag 的 `git stash push -u`，🚫 不许裸 `stash`/`pop`） |
| T-9.1 | 文案临时改回 `material_master` | A1 由 0 变 ≥1 | 否（自还原） |
| T-10.1 | 故意让 `snap()` 连库失败 | `wc -l = 0` ⇒ A3 FAIL | 否 |

# 附录 C · 主线审用例裁决结果（2026-09-09）

> 我提的 8 条：**7 条采纳，`Q-1` 挂起等用户，`Q-7` 是主线对我的纠正（我错了）**。

| # | 裁决 | 落到本文件哪里 |
|---|---|---|
| **Q-1** | 🛑 **挂起，等用户** —— 排除 `Q05:90` 属**范围缩减**（`D-4` 裁的是「3 处」），按 `§4.3` 归用户。**本期 `B-9` 只做 2 处祈使型** | T-9.1 范围表 + A7/A8 反向断言 |
| **Q-2** | ✅ 采纳 —— `AC-9` 作用域写死到「该文案字符串所在行」；`:94` `recordWrite` 是 **T-5d 记账，指向真实写入表，必须保留** | T-9.1 作用域表 + A8 |
| **Q-3** | ✅ 采纳**乙** + 主线追加 **A3 还原实验** | T-8.1 全部重写为 A1/A2/A3 三段式 |
| **Q-4** | ✅ 采纳 —— `AC-3` 三条：下界 + **上界 ≤ 基线×1.05** + 列集指纹 | T-3.1 A5 / A6 |
| **Q-5** | ✅ 采纳 —— 有效样本量 1，说服力接近 0；加全域对照 | T-6.2（含**一处对裁决措辞的更正**，见下） |
| **Q-6** | ✅ 采纳 —— `AC-13` 加 `md5(pg_get_viewdef(...))` | T-13.1 A4 |
| **Q-7** | 🔴 **主线纠正我** —— 收敛规则须用视图自己的 `ORDER BY material_no, customer_no` | T-5.1 收敛规则一节（含影响面量化） |
| **Q-8** | ✅ 采纳 —— `mbi_ref` 口径写进表头 | T-5.1 末尾 |

## 我在执行裁决时发现并更正的一处（`Q-5`）

主线裁决写「三档计数，**不一致必须为 0**」。**照字面执行会大面积假红**：
实测「不一致」= **2661 / 2682**（99.2%），因为 `material_master` 48 行 vs `ds_quote_material` 2725 行，
**绝大多数是「新桥覆盖更广」而非回归**。

⇒ 已拆成四档，判据只压在真回归的两档上：
`b3b`（桥改值，全域必须 0，**实测 0**）+ `b3a`（桥失效，scope 内必须 0，**实测 scope 内 0**）。
唯一的 `b3a` 是 `SC3120011203 → 3120011203`，而 `3120011203` **正是 `AC-5` 那 6 个「将消失料号」之一**
⇒ 与 `AC-5` 完全自洽，是已走 `A0-4` 用户确认的预期后果，不是新增风险。

## 遗留待办

| # | 事项 |
|---|---|
| 1 | `Q-1` 待用户裁决 —— 用户裁定后，`AC-9` 的断言处数（2 或 3）与 T-9.1 的 A7/A8 需相应调整 |
| 2 | **`AC-6` 的将来风险**：今天指纹守得住是因为 scope 只有 3 个 `production_no` 且两套桥在其上完全等价；`b3c` 那 2660 个里任何一个进入 `element_bom_item`，`AC-6` 就会红，**届时是正常扩面不是回归**，须更新基线指纹而非回滚（T-6.2 末尾） |
| 3 | `AC-11` 的「带 `batchParts` 的批次」缺口 —— 已按主线确认保留「候选料号非空」等价前置（`CUST-0004` 正泰，`ds_quote_customer_part` 2663 行） |
