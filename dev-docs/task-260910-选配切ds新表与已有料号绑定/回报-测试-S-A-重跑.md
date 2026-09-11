# 子代理回报 · 测试片 S-A（写入侧落表）**重跑**

> **落盘时间**：2026-09-10 18:0x（本轮 **7 次** maven 执行：2 次基线绿 + 3 次证伪 + 恢复确认 run4 + javadoc 改后终轮 run5，**绿轮共 4 次**）
> **认领**：AC-1 / AC-2 / AC-3 / AC-4　**造数前缀**：`T260910A-`
> **上一轮回报**：`回报-测试-S-A.md`（读数已作废，见 §3 diff）

---

## 0. 结论一句话

**AC-1 / AC-2 / AC-3 / AC-4 全部达成（4/4 绿，共 4 轮全绿：run1 / run2 / run4 恢复轮 / run5 终轮）。**
**AC-4 按 D-15 新口径重写后仍通过** —— 父轴 2 行 / 子轴各 1 行 / 合计 4 行，三个口径逐一给数。
**上轮遗留的「行数口径待裁决」已关闭**：D-15 描述的就是实测形态，无产品缺陷。

---

## 1. 本轮锚定的代码状态（读数归谁）

| 项 | 值 |
|---|---|
| worktree | `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables`（分支 `feat/task-260910-sel-ds-tables`，HEAD `8ef9eba8`） |
| 实现仍是**未提交工作区状态** | `git diff cpq-backend/src/main/java/` 的 sha256 = **`cb1c5e2938b71f6a…`** |
| 与上一轮对比 | 上轮 sha256 = `15a7aa7d65bc36c4…` ⇒ **确属不同代码**，上轮读数已作废这一判断成立 |
| 终轮 run5 跑的**整个 src** = 当前 worktree | `diff -r /tmp/.../cpq-backend/src <worktree>/cpq-backend/src` → **无差异**（逐字节，跑前复核）。run4 时对 `src/main` 做过同样复核 |
| 实现文件 mtime | `SelDsQuoteWriter.java` 07:45 · `ConfigureProductService.java` 09:09 · `ConfigureProductResource.java` 08:35 · `CostingTreeGrouping.java` 17:41 ⇒ **全部早于本轮首跑 17:53**，运行期间实现未被改动（与上一轮「运行期间被改过两次」相反） |

---

## 2. 逐条 AC：实际 SQL 与实际返回行

> **数据源**：`证据/S-A-重跑/run4.log`（恢复干预后的最终确认轮，RUN_ID=`613b4a`）。run1/run2 的读数与它逐值一致。
> 所有查询一律按 `WHERE customer_no='<本用例自建客户>'` 收窄 —— **本片无一条全局计数断言**。

### AC-1（TC-A1）✅

客户 `T260910A-13602fe9` · 新铸料号 `1079-2609000001` · `fingerprintMatched=false`（真铸新号，非复用）

**① 落新表**
```sql
SELECT material_no, input_material_no, operation_no, operation_item_seq, item_seq,
       currency, pricing_unit, value::text, version_no, source
  FROM ds_quote_self_process_fee WHERE customer_no='T260910A-13602fe9';
```
实际返回 **1 行**：
```
[1079-2609000001, 1079-2609000001, Z008, 1, 1, CNY, PCS, null, 1, MANUAL]
```
10 列逐字对 AC-1① 原文：`material_no` = `input_material_no` = 新铸料号（**D-4**，SIMPLE 时零件料号=自己）· `operation_no=Z008` · `operation_item_seq=1` · `item_seq=1` · `currency=CNY` · `pricing_unit=PCS`（**动态取 `process_master['Z008'].standard_unit`，非硬编码**）· `value IS NULL` · `version_no=1` · `source=MANUAL`。

**② `unit_price` 零新增**（三种口径都打）
```sql
SELECT count(*) FROM unit_price WHERE finished_material_no='1079-2609000001';  -- 0
SELECT count(*) FROM unit_price WHERE code='1079-2609000001';                  -- 0
SELECT count(*) FROM unit_price WHERE customer_no='T260910A-13602fe9';         -- 0
```
🔑 **探针阳性对照**（防「零断言恒绿」）：`unit_price WHERE code='S-3120014539'` → **16 行** ⇒ 探针抓得到东西。

**③ 渲染侧读得到**：从库里**现读** `component_sql_view.sql_template`（组件「自制加工费」），代入 `total_material_no=ARRAY['1079-2609000001']` / `customerCode='T260910A-13602fe9'` 执行 → **返回 1 行**，其中 `_自制加工费_工序编号='Z008'` **1 行**。
⚠️ **未验证部分**：这是**接口/SQL 层**证据，不是「浏览器里看见 Z008」。UI 层证据须主线亲验（`testing.md §2`）。

### AC-2（TC-A2）✅

客户 `T260910A-a8c6fb05` · 父料号 `1078-2609000003` · `fingerprintMatched=false`

**①**
```sql
SELECT material_no, assembly_operation, assembly_fee::text, item_seq, currency,
       pricing_unit, defect_rate::text, version_no, source
  FROM ds_quote_assembly_fee WHERE customer_no='T260910A-a8c6fb05';
```
实际返回 **1 行**：
```
[1078-2609000003, Z100, 0.000000000000, 1, CNY, PCS, 0.010000000000, 1, MANUAL]
```
轴=**父料号** ✓ · **`assembly_fee=0`** ✓（D-5）· `pricing_unit`/`defect_rate` 与 `process_master['Z100']` 逐值相符 · `version_no=1` · `source=MANUAL`。

**③** `WHERE assembly_fee=0 AND source='MANUAL'` → **1 行**（「选配占位、待补价」判据成立）。

**②** `capacity` 零新增：`capacity` 无 `customer_no` 列 ⇒ 按本轮料号集合 `[1078-2609000001, 1078-2609000002, 1078-2609000003]` 收窄 → **0 行**（其中 `resource_group_no='QUOTE_ASSEMBLY'` 也 0 行）。
🔑 探针阳性对照：`capacity WHERE material_no='S-3120014539'` → **6 行**。

📌 **AC 原文的 `MRO-AS-0001` 在 `cpq_db_test` 不存在** ⇒ 按 AC 原文给的替代口径动态取 `process_category IN ('ASSEMBLY','组装')` 的工序，实取 **`Z100`**。（与上轮同）

### AC-3（TC-A3）✅

客户 `T260910A-82edbf44` · 自造外购件 `T260910A-OUT1` · 工序 `Z100` · `fingerprintMatched=false`

**①** `ds_quote_assembly_fee WHERE customer_no='T260910A-82edbf44'` → **1 行**
```
[T260910A-OUT1, Z100, 0.000000000000, 1, CNY, PCS, 0.010000000000, 1, MANUAL]
```
**②** `ds_quote_self_process_fee WHERE customer_no='T260910A-82edbf44'` → **`[]`（0 行）**
（比 AC 字面判据更严：本次只提交这一道外购件工序 ⇒ 整表为空才对，堵住「挂到别的轴上绕过字面判据」）
**③** `unit_price` 按客户 0 行、按外购件料号 0 行；探针阳性对照 16 行。

🧩 **料号替换登记（与上轮一致）**：AC 原文的 `S0011` 挂共享客户 `CUST-0001/CUST-0004`，切表后按 `customer_no` 过滤（D-2）在本片自建客户下查不到 ⇒ 按 `test.md §1` 用自造 `T260910A-OUT1`（同形态：`ds_quote_material` 一行、`material_type='外购件'`），**判据逐字不变**。
📌 这条替换顺带规避了派工 prompt §5 点名的「`S0011` 已有别人自测残留 `assembly_fee`」基线污染。

### AC-4（TC-A4）✅ **按 D-15 新口径重写，三个口径分别给数**

客户 `T260910A-33f3d113` · 父 `1080-2609000003` · C1 `1080-2609000001`(Z008) · C2 `1080-2609000002`(Z100) · `fingerprintMatched=false`
（两个子件故意用不同总重 11/12，并硬断言 `c1 != c2` —— 否则指纹复用会把两子件收敛成一个料号，「两个 input_material_no」退化成空验证）

**全量（本片客户收窄）**
```sql
SELECT material_no, input_material_no, operation_no, operation_item_seq, item_seq,
       currency, pricing_unit, value::text, version_no, source
  FROM ds_quote_self_process_fee WHERE customer_no='T260910A-33f3d113'
 ORDER BY item_seq, operation_item_seq;
```
```
[1080-2609000001, 1080-2609000001, Z008, 1, 1, CNY, PCS, null, 1, MANUAL]   ← 子轴 C1
[1080-2609000002, 1080-2609000002, Z100, 1, 1, CNY, PCS, null, 1, MANUAL]   ← 子轴 C2
[1080-2609000003, 1080-2609000001, Z008, 1, 1, CNY, PCS, null, 1, MANUAL]   ← 父轴
[1080-2609000003, 1080-2609000002, Z100, 2, 2, CNY, PCS, null, 1, MANUAL]   ← 父轴
```

| D-15 口径 | 断言 | **实际** | 判定 |
|---|---|---|---|
| **①父轴** | `material_no=P` 恰好 2 行，`input_material_no`∈{C1,C2}，`operation_no` 分别 Z008/Z100 | **2 行**；inputs = {`1080-2609000001`,`1080-2609000002`}；C1→**Z008**、C2→**Z100** | ✅ |
| **②子轴** | `(C1,C1,Z008)` 与 `(C2,C2,Z100)` **各 1 行**（自指） | C1 轴 **1 行** `[1080-2609000001, 1080-2609000001, Z008, …]`；C2 轴 **1 行** `[1080-2609000002, 1080-2609000002, Z100, …]` | ✅ |
| **③合计** | 4 行 | **4 行**（父轴 2 + 子轴 2） | ✅ |

🚫 **断言按轴分别收窄写的**（`spf.stream().filter(material_no=…)`），**没有写「整表 N 行」**；合计那条也限死在 `WHERE customer_no='<本用例自建客户>'`，共库并行打不红。

**附带**：`unit_price WHERE customer_no='T260910A-33f3d113'` → **0 行**（阳性对照：新表同一提交已有 4 行 ⇒ 写入路径确实执行过）。

---

## 3. 与上一轮读数的 diff

| # | 项 | 上一轮 | **本轮** | 归因 |
|---|---|---|---|---|
| 1 | **AC-4 判定** | 「实质达成，行数口径待裁决」——只硬断言父轴 2 行，子轴只打印不断言 | **完全达成**，父/子/合计三个口径全部**硬断言**并通过 | **D-15**：AC 原文补全了子轴描述。**行为本身没变**（上轮实测也是 4 行、同样的 4 条）⇒ 上轮那个 A/B 归因（老表 `unit_price` 同为双轴、等价搬运）**被 D-15 采纳，判断是对的** |
| 2 | 🔴 **带版本表落点** | 上轮运行中途从「主表 6 行 / `_record` 0 行」变成「**主表 0 行 / `_record` 6 行**」（S-4 方案②中途落地） | **主表 6 行 / `_record` 0 行**（COMPOSITE 用例的 `[还原]` 行：`mat_bom=6 el_bom=4 mb_rec=0 eb_rec=0`） | **D-14**：方案② 整体作废，带版本表退回**直写主表**。⇒ 这是本轮**最该看见变化的地方，确实变了** |
| 3 | `_record` 投影 | — | 日志出现 `[ds-record] quotation=… 命中 N 个轴值但无组件数据，跳过` | **D-21** 把 `syncRecordsForFlow` 挂回两段物化之后；本片夹具是裸 API 建单、`quotation_line_component_data` 无数据 ⇒ 投影早退属**预期**。`_record` 的正向验证归 **S-C（AC-13/AC-14）**，不在本片 |
| 4 | 实现代码指纹 | `15a7aa7d65bc36c4…` | `cb1c5e2938b71f6a…` | 5 轮裁决改动后的当前状态 |
| 5 | 运行期稳定性 | 「运行期间实现被改过至少两次」，读数不可锚定 | 实现文件 mtime **全部早于首跑**，run4 与当前 worktree **逐字节一致** | 本轮读数可锚定 |
| 6 | 证伪实验 | **未执行**（当时实现未提交，回改会毁在途代码） | **已执行 3 个**（见 §4） | 本轮改的是**测试侧新加的守卫**，不动实现 ⇒ 无此顾虑 |
| 7 | AC-1/AC-2/AC-3 读数 | 1/1/1 行，各列值 | **逐值一致**（只有料号/客户号随机部分不同） | B-1/B-2/B-3/B-5 逐字未动，符合预期 |

---

## 4. 证伪实验（本轮新加的守卫，逐个做）

> `testing.md`：**新加的守卫必须做证伪实验 —— 故意破坏它保护的条件，确认它硬失败**。首次 PASS 证明不了它接上了。
> 干预**只改 `/tmp` 隔离副本**，worktree 源文件全程未动；做完 rsync 回滚 + 跑 run4 确认复绿。

| # | 守卫 | 干预 | **结果** | 日志 |
|---|---|---|---|---|
| **FT-A** | AC-4② 子轴 `operation_no` 断言 | 期望值对调（C1 期望 Z100 / C2 期望 Z008） | ✅ **硬失败**：`AC-4②（D-15）：子件 1070-2609000001（…配的是 Z100）自轴行的 operation_no 应为 Z100，实际=Z008 ==> expected: <Z100> but was: <Z008>`。且**只有 TC-A4 红，TC-A1/A2/A3 仍绿** ⇒ 用例间无串扰 | `run3a.log:150` |
| **FT-B** | AC-4③ 合计断言 | 期望 4 → 5 | ✅ **硬失败**：`…应合计 4 行（父轴 2 + 子轴 2），实际 4 行 = […4 条…] ==> expected: <5> but was: <4>` | `run3b.log:152` |
| **FT-C** | 🆕 指纹复用假绿守卫 `assertFreshCast` | `assertNotEquals(TRUE, fpm)` → `assertEquals(TRUE, fpm)` | ✅ **4 个用例全红**，报文里逐条打出真实值 `==> expected: <true> but was: <false>` ⇒ 证明该字段**真的被读到了、值确为 false**，不是 null 恒绿 | `run3c.log:130/141/152/163` |
| **恢复确认** | — | rsync 回滚 + `diff` 逐字节比对 | ✅ **4/4 复绿**，`BUILD SUCCESS` | `run4.log:157` |
| **终轮** | 只改了 TC-A4 的 javadoc 一行（注释，无行为影响） | ✅ **4/4 绿**，`BUILD SUCCESS`；副本与 worktree `diff -r` 逐字节一致 | `run5.log:157` |

🆕 **本轮新增的守卫（针对派工 prompt §⑦ 点名的「指纹复用假绿」）**：
`SelDsWriteAcBase.assertFreshCast()` —— 每次提交后硬断言 `fingerprintMatched != true` 且 `reusedHfPartNos` 为空，并把两者打印出来。
**实际值**：4 个用例全部 `fingerprintMatched=false reusedHfPartNos=[]` ⇒ **本轮每条用例都真铸了新料号，没有一条被存量行骗过**。

---

## 5. 隔离方式与 target 假故障

- **隔离方式**：`-Dmaven.build.dir` **在本项目不可用** —— `cpq-backend/pom.xml` 没有 `<directory>${maven.build.dir}</directory>`（`grep` 实查 0 命中），传了也不生效。
  ⇒ 改用**源码隔离副本**：`rsync -a --delete --exclude='target/' <worktree>/cpq-backend/ /tmp/claude-1000/s-a-build/cpq-backend/`，**全部 6 次 maven 都在 `/tmp` 副本里跑**。
  `pom.xml` 无 `<parent>`、无 `relativePath` ⇒ 单模块可独立构建，副本自带 `.mvn/` wrapper。
- **撞到 target 假故障了吗**：**没有**。7 次执行**零次** `Tests run: 0` / `bad class file` / `cannot find symbol` / `NoClassDefFoundError`。
  对照上一轮：那轮在共享 `target/` 里跑，出现过一次 `Tests run: 0` + `BUILD FAILURE` 紧接着同命令 4 绿。
- **进程纪律**：全程**未执行任何 `pkill`**；测试用 `quarkus.http.test-port=0`（随机口），**未占 8081 / 5174**。
  🚫 主线 8081（PID 19938/20503）与另一路 uat 8099（PID 12201/12202）全程未碰。
### 5.1 🔍 「worktree 里一次 maven 都没跑」的硬证据（主线 18:1x 更正后补做）

主线的更正与本片**实际做法一致**（我在动手前就 `grep -n '<directory>' pom.xml` 得 0 命中，所以从一开始就没用 `-Dmaven.build.dir`）。补三条可复核证据：

| 证据 | 读数 | 说明 |
|---|---|---|
| **本轮编译产物落在哪** | 副本 `/tmp/claude-1000/s-a-build/cpq-backend/target/test-classes/com/cpq/task260910a/*.class` → **18:04:24**；`surefire-reports/TEST-com.cpq.task260910a.*.xml` → **18:04:44** | 本轮产物**全在副本** |
| **worktree `target` 里我的类** | `.class` → **17:41:59**（**早于本片首跑 17:53**，是别片跑全量 test 时顺带编译的）；`surefire-reports/TEST-com.cpq.task260910a.*.xml` → **08:34:26**（**上一轮早上留下的**） | worktree 里**没有本轮的任何产物** |
| 🔑 **字节数反证** | `SelDsWriteAcBase.class`：副本 **33067 B** / worktree **32074 B** | 副本那份含本轮新加的 `assertFreshCast()`；worktree 那份是**旧版**。⇒ **本轮改动从未编进 worktree 的 `target/`**，构造性证明「worktree 里一次 maven 都没跑」 |

**是否撞到过 target 冲突症状**：**零次**。7 次执行全程无 `Tests run: 0`、无 `bad class file` / `NoSuchFileException`、无成片 `cannot find symbol`、无 `NoClassDefFoundError`。
⇒ **本轮没有任何读数因 target 冲突作废，§2 的全部结论都是隔离副本上的首取读数**（不是重取）。
📌 对照：上一轮（在共享 `target/` 里跑）确实撞到过一次 `Tests run: 0` + `BUILD FAILURE`，紧接着同命令 4 绿 —— 那正是主线描述的假故障形态。

- **未跑全量 `mvnw test`**（按派工 prompt §⑥）：`mat_*` 表在本库从未创建，全量永远不可能全绿；`BL-0261` 两个恒红存量坏测试本轮**未触及**，无归因风险。

---

## 6. 实确认的连库结果

```
$ psql -h 10.177.152.12 -U postgres -d cpq_db_test -c '\conninfo'
 Database | cpq_db_test    Host | 10.177.152.12    Server Port | 5432
```
- **用例内每条第一枪**打印 `SELECT current_database()` → **`cpq_db_test`**（run4.log 4 次，逐条）。🚫 不凭配置文件记载。
- 配置侧交叉印证：`application-test.properties:32` → `${DB_NAME:cpq_db_test}`（与 `CLAUDE.md` 第 3 版一致）。
**🔬 两库交叉对比（主线 18:1x 提到的 S-D 手法，本片补做）**

| 指标 | `cpq_db_test`（本片用的） | `cpq_db_0724` |
|---|---|---|
| `SELECT count(*) FROM quotation` | **134** | **161** |
| 🔑 **本片 AC-1③ 依赖的渲染视图模板**（组件「自制加工费」且 `sql_template` 引用 `ds_quote_self_process_fee`） | **1 条** | **1 条** |
| `pg_views WHERE viewname LIKE 'v_compat_%'` | **0** | **3** |
| `config_template WHERE id::text LIKE '45cc0267%'`（正泰测试模板1） | 0 | 0 |

⇒ **S-D 报的「`cpq_db_test` 没有正泰模板 `45cc0267…`」这条环境事实不影响本片**：AC-1③ 走的是 `component_sql_view`（「自制加工费」组件），**两库各 1 条、`cpq_db_test` 里存在**，且本片**从未撞到「查不到行」**（AC-1③ 实测返回 1 行非空）。
📌 顺带更正一个数：S-D 当时读到 0724 的 `quotation` 是 **156**，我 18:1x 实读 **161** —— 那是个**随建单在涨的瞬时量**，🚫 不宜当固定指纹用；本片的库辨识用的是用例内每条打印的 `current_database()`，不受此影响。

- **跑前基线**：`ds_quote_self_process_fee` **47 行** / `ds_quote_assembly_fee` **14 行**。
- **跑后**：`spf` **48 行** / `asm` **14 行**。
  ⚠️ **多出的那 1 行不是我的**：`id=358 customer_no='T260958328be7' material_no='1077-2609000001'` —— 客户号形如 `T2609`+8 位 hex（13 字符），**不是本片的 `T260910A-` 前缀**，属另一片（S-C / D-22 重写）的造数。我的 `T260910A-%` 在所有表均为 **0 行**（见 §7）。
  📌 这正是为什么本片**一条全局计数断言都不敢写** —— 若断言「spf 共 47 行」，此刻就会红，且红得像业务回归。

---

## 7. 待回收清单

**无。**

- 本片**没建一次性库**，全程用共享 `cpq_db_test`。
- **残留自检（跑完 psql 实查）**：
  ```
  ds_quote_self_process_fee   WHERE customer_no LIKE 'T260910A-%'  → 0
  ds_quote_assembly_fee       WHERE customer_no LIKE 'T260910A-%'  → 0
  ds_quote_material           WHERE customer_no LIKE 'T260910A-%'  → 0
  ds_quote_material           WHERE material_no LIKE 'T260910A-%'  → 0   （自造外购件 T260910A-OUT1 已清）
  ds_quote_customer_part      WHERE customer_no LIKE 'T260910A-%'  → 0
  customer                    WHERE code        LIKE 'T260910A-%'  → 0
  unit_price                  WHERE customer_no LIKE 'T260910A-%'  → 0
  ```
- 🚨 **红线纪律**：全程无 `DROP` / `TRUNCATE` / 清库 / 无 `WHERE` 的 `DELETE`。`@AfterEach` 的每条 `DELETE` 都限死在「本用例自建 `customer_no` / `quotation_id` / 本轮料号 + `source='MANUAL'` 白名单」，删除行数逐项打印（见 `[还原]` 行）。**未动任何全局状态**（未改用户启停用 / 角色 / 模板发布态 / 公共基础数据）。

---

## 8. 「AC 过不了」的情况

**无。4 条 AC 全部达成，未放宽任何断言 —— 反而按 D-15 把 AC-4 从 1 条硬断言加到 5 条。**

### 「未验证」标注（逐字，不许读成「应该没问题」）

| # | 项 | 状态 |
|---|---|---|
| 1 | **AC-1③ 只到接口/SQL 层** | 验的是「渲染侧真正会查的那段 SQL（库里现读的 `component_sql_view.sql_template`）+ 那组过滤条件返回该行」。**「在浏览器里看见 Z008」未验证**，须主线亲验（`testing.md §2`：只有接口层覆盖的 AC 不算已验收） |
| 2 | **AC-2 的组合工序** | AC 原文举的 `MRO-AS-0001` 在 `cpq_db_test` **不存在**；按 AC 给的替代口径取 `Z100`。「`MRO-AS-0001` 这个具体工序能不能走通」**未验证** |
| 3 | **AC-3 的 `S0011`** | 本片用自造 `T260910A-OUT1` 同形态替代（原因见 §2）。**「现网 `S0011` 在真实客户下的行为」未验证** |
| 4 | **`cpq_db_0724` 上的行为** | 本片只在 `cpq_db_test` 验过。两库基线不同（0724 有 3 张 `v_compat_*` / 99 段视图；test 是 0 张 / 42 段）⇒ **亲验若在 0724 上出现分歧，先查这个，🚫 不许归因成「测试环境问题」** |

### 只报观察、不下结论的两条

| # | 观察 | 我的判断 |
|---|---|---|
| **O-1** | COMPOSITE 卡片若 `total_material_no` 同时含父与子，「自制加工费」页签渲染 SQL（`WHERE material_no = ANY(:total_material_no)`）会同时取到父轴 2 行 + 子轴 2 行 = **视觉上 4 行**。D-15 已确认落库 4 行是正确行为，但**渲染侧要不要都显示**属 **AC-11 的面，不在本片** | 只报观察，**不下结论**。建议主线亲验 COMPOSITE 卡片时顺手看一眼 |
| **O-2** | 本片夹具建单时 `_record` 投影被跳过（`命中 N 个轴值但无组件数据，跳过`），因为裸 API 夹具没有 `quotation_line_component_data`。这与 **D-21** 的挂点（物化之后）一致，**属预期而非缺陷** | `_record` 的正向验证归 **S-C（AC-13/AC-14）**，本片不认领 |

---

## 9. 产出文件（绝对路径）

- 测试代码（**本轮有改动**）：
  - `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables/cpq-backend/src/test/java/com/cpq/task260910a/SelWriteToDsTablesAcTest.java` —— TC-A4 按 D-15 重写（新增子轴 ×2 + 合计断言，删掉原「待裁决」注释块）
  - `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables/cpq-backend/src/test/java/com/cpq/task260910a/SelDsWriteAcBase.java` —— 新增 `assertFreshCast()` 指纹守卫（挂进 `assertSubmitOk`）+ `assertNotEquals` 导入
- 证据归档（**已复制出 `target/`，下一轮 surefire 清不掉**）：
  `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables/dev-docs/task-260910-选配切ds新表与已有料号绑定/证据/S-A-重跑/run{1,2,3a,3b,3c,4,5}.log`
- **跑法**（隔离副本；🚫 直接在 worktree 跑会与别片抢 `target/`）：
  ```bash
  rsync -a --delete --exclude='target/' \
    /home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables/cpq-backend/ \
    /tmp/claude-1000/s-a-build/cpq-backend/
  cd /tmp/claude-1000/s-a-build/cpq-backend && \
    QUARKUS_REDIS_HOSTS='redis://:joii5231@10.177.152.12:6379/0' ./mvnw -o test -Dtest=SelWriteToDsTablesAcTest
  ```

🚫 **本片未执行 `git commit`**（提交由主线统一做）。
