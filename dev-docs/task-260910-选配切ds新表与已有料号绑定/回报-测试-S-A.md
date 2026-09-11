# 子代理回报 · 测试片 S-A（写入侧落表）

> 📌 **先落盘、后分析**（`subagents.md §1⑥`）。逐字留档，🚫 主线复验结论不写在本文件里。
> **落盘时间**：2026-09-10（agent 用时约 22.6 分钟 / 67 次工具调用）
> **认领**：AC-1、AC-2、AC-3、AC-4　**造数前缀**：`T260910A-`

## 结论（回报原文）

> **AC-1 / AC-2 / AC-3 全部达成；AC-4 实质达成，但 AC 原文的行数口径有一处需要你裁决**。

---

## 1. 产出文件（绝对路径）

- `.../cpq-backend/src/test/java/com/cpq/task260910a/SelDsWriteAcBase.java`（**638 行**，基座）
- `.../cpq-backend/src/test/java/com/cpq/task260910a/SelWriteToDsTablesAcTest.java`（**409 行**，TC-A1~TC-A4）
- 证据归档（**已复制出 `target/`，下一轮 surefire 清不掉**）：`dev-docs/task-260910-选配切ds新表与已有料号绑定/证据/S-A/run{1,2,3}-*.txt`

**跑法**：
```bash
cd cpq-backend && QUARKUS_REDIS_HOSTS='redis://:joii5231@10.177.152.12:6379/0' ./mvnw -o test -Dtest=SelWriteToDsTablesAcTest
```

---

## 2. 逐条 AC（断言原文 + 实际值，逐字）

### AC-1（TC-A1，`SelWriteToDsTablesAcTest.java:57`）✅
- `:82`「本片客户名下 `ds_quote_self_process_fee` 应恰好 1 行」→ **实际 1 行**，10 列实测值：
  ```
  [1012-2609000001, 1012-2609000001, Z008, 1, 1, CNY, PCS, null, 1, MANUAL]
  ```
  `material_no` = `input_material_no` = 新铸料号 ✓**D-4** · `operation_item_seq=1` · `item_seq=1` · `currency=CNY` · `pricing_unit=PCS` = `process_master['Z008'].standard_unit`（**动态取，非硬编码**）· `value IS NULL` · `version_no=1` · `source=MANUAL`
- `:114`「`unit_price` 零新增」→ `finished_material_no`=0、`code`=0、`customer_no`=0 行（**三种口径都打了**）
- `:148`「渲染侧读得到、工序编号=Z008」→ 用 `component_sql_view.sql_template`（组件「自制加工费」，**运行时从库现读，不是抄代码**）代入 `total_material_no=ARRAY[料号]` / `customerCode=本片客户` 执行 → 返回 **1 行**，其中 `_自制加工费_工序编号='Z008'` **1 行**

### AC-2（TC-A2，`:164`）✅
- `:182`「`ds_quote_assembly_fee` 恰好 1 行」→ `[1011-2609000003, Z100, 0.000000000000, 1, CNY, PCS, 0.010000000000, 1, MANUAL]`
  轴=父料号 ✓ · **`assembly_fee=0`** ✓**D-5** · `pricing_unit`/`defect_rate` 与 `process_master['Z100']` 逐值相符（PCS / 0.010000000000）· `version_no=1` · `source=MANUAL`
- `:216`「`assembly_fee=0` 且 `source='MANUAL'` 可同时查到」→ 1 行
- `:229`「`capacity` 零新增」→ 本轮 3 个料号命中 **0 行**（`capacity` 无 `customer_no` 列 ⇒ 改按本轮料号集合收窄，**不用全局计数**）

### AC-3（TC-A3，`:271`）✅
- `:291` → `ds_quote_assembly_fee` 出现 `[T260910A-OUT1, Z100, 0.000000000000, 1, CNY, PCS, 0.010000000000, 1, MANUAL]`（1 行）
- `:300`「`_self_process_fee` 不出现该外购件」→ 本片客户下该表**整体 `[]`（0 行）**（比 AC 字面判据更严：本次只提交了这一道外购件工序，所以整表为空才对，避免"挂到别的轴上"绕过字面判据）
- `:316`「`unit_price` 零新增」→ 0 行

🧩 **料号替换已登记（回报原文）**：
> AC 原文的 `S0011` 挂在共享客户 `CUST-0001/CUST-0004` 下，切表后按 `customer_no` 过滤（D-2）在本片自建客户下查不到 ⇒ 按 `test.md §1` 用自造 `T260910A-OUT1`（同形态：`ds_quote_material` 一行 + `material_type='外购件'`），判据逐字不变。

### AC-4（TC-A4，`:332`）⚠️ **实质达成，行数口径待主线裁决**

- `:372` 父轴（`material_no=父料号`）**恰好 2 行** ✓
- `:377` 两行 `input_material_no` = {C1, C2} ✓
- `:385/:387` C1→Z008、C2→Z100 ✓　⇒ **D-4 映射完全正确**

**但按「整表口径」读，本片客户下共 4 行**：
```
[C1, C1, Z008, 1, 1, ...]   ← 子轴（与 AC-1 的 SIMPLE 形态同构）
[C2, C2, Z100, 1, 1, ...]   ← 子轴
[P,  C1, Z008, 1, 1, ...]   ← AC-4 描述的那 2 行
[P,  C2, Z100, 2, 2, ...]
```

🔑 **A/B 归因证据（子代理按 `testing.md §4.1.5` 三问查的，逐字）**：
> 老表 `unit_price` 的存量选配数据**也是双轴**，`cpq_db_0724` 实查 `CUST-0001`：
> ```
> finished_material_no=0526-2609000004  code=0526-2609000004  Z002/Z008   ← 子轴
> finished_material_no=0526-2609000005  code=0526-2609000004  Z002/Z008   ← 父轴
> ```
> ⇒ 4 行是**与老表同形态的等价搬运，不是本次改动引入的新行为**。我判断这是 **AC-4 原文的行数表述不完整**（只描述了父轴），**不是产品缺陷** —— 但改 AC 是你的决定，我没有单方面放宽断言，也没有报缺陷。**请裁决：AC-4 的「2 行」是否应改写为「父轴 2 行」？**

📌 **连带风险（子代理只报观察、不下结论）**：
> 渲染 SQL 是 `WHERE material_no = ANY(:total_material_no)`，若 COMPOSITE 卡片的 `total_material_no` 同时含父与子，「自制加工费」页签会显示 4 行（**视觉重复**）。这属 AC-11 的面，不在我这片。

---

## 3. FT-4 证伪实验：**未执行**（无权改实现），设计步骤如下

> 严格版 FT-4（把 B-1 的切表改回去）我做不了 —— 而且现在**实现是未提交状态**，`git checkout --` 会直接毁掉后端工程师的在途代码。步骤：
> 1. 先由后端工程师落一个 WIP commit（或 `git diff <file> > /tmp/b1.patch` 备份）；
> 2. 只把 `SelDsQuoteWriter.java` 回到 master 版本；
> 3. **先确认干预生效**：日志里必须出现重编译（`Restarting quarkus` / surefire 重新编译该文件），并 `/usr/bin/grep -c ds_quote_self_process_fee SelDsQuoteWriter.java` 前后对比；
> 4. 跑 `-Dtest=SelWriteToDsTablesAcTest`，**期望 TC-A1 两条同时变红**：`:82` spf 0 行 + `:114` unit_price ≥1 行。**只红一条不算证伪成功**；
> 5. `git apply /tmp/b1.patch` 恢复。

**已做的三重替代证据（不动实现，已固化在用例里）**：

| 证据 | 实测值 |
|---|---|
| **探针阳性对照** `assertUnitPriceProbeCanSeeRows()` / `assertCapacityProbeCanSeeRows()` —— 用库里存量行打同形状查询 | `unit_price WHERE code='S-3120014539'` → **16 行**；`capacity WHERE material_no='S-3120014539'` → **6 行** ⇒ 排除「探针是瞎的、零断言恒绿」 |
| **老库实证** | `cpq_db_0724` 有 **31 条** `cost_type='自制加工费'` 且 `customer_no` 非空的行（含 `0526-*`/`0028-*` 选配料号）⇒ 老路径确实写这张表，判据形状能命中 |
| **同用例内阳性对照** | 新表非空（1/1/4 行）⇒ 排除「压根没执行到写入」 |

> ⇒ 残余风险已很小，但**严格 FT-4 仍待执行，我不把它记成已完成**。

---

## 4. 「未验证」标注（逐字）

- **AC-1③ 只到接口/SQL 层，没到 UI**：验的是「渲染侧真正会查的那段 SQL + 那组过滤条件返回该行」。**「在浏览器里看见 Z008」必须由主线亲验**（`testing.md §2`：只有接口层覆盖的 AC 不算已验收）。
- **AC-2 用的组合工序是 `Z100`（`process_category='组装'`）** —— AC 原文举的 `MRO-AS-0001` 在 `cpq_db_test` 里**不存在**（`process_master` 只有 4 类：加工 4 / 组装 2 / 包装 1 / 空 2）。AC 原文给了「或任一 `ASSEMBLY`/`组装`」的替代口径，按它动态取。
- 🚨 **读数锚定在一个未提交的实现状态**：`git diff cpq-backend/src/main/java/` 的 sha256 = `15a7aa7d65bc36c4…`（05:03 采样）。**运行期间实现被改过至少两次**（04:57 那轮 `ds_quote_material_bom` 主表还有 6 行，05:02 起变成主表 0 行 / `_record` 6 行 —— **S-4 中途落地**）。⇒ **后端提交后必须再跑一轮**，本轮不能替代「对最终提交代码的验证」。

---

## 5. 子代理规避掉的坑（逐字要点）

- **不带 session 一律 401 假红** → 基座统一带 admin session，并用 `/auth/me` **验明正身**（cookie 拿到 ≠ cookie 生效）+ 断言 `role=SYSTEM_ADMIN`；登录 5xx 明确标成「Redis 基础设施故障，不是业务结论」
- **共库并行的全局计数假红** → 全部断言按 `WHERE customer_no='<本用例自建客户>'` 收窄，**一条全局计数都没有**；`capacity` 无客户列改按本轮料号集合收窄
- **`test` profile 连哪个库不凭配置记载** → 用例内 `current_database()` 每轮打印（**实测 `cpq_db_test`**），并用 `psql \conninfo` 双证
- **端口** → `src/test/resources/application.properties` 是 `quarkus.http.test-port=0`（随机口，实测 58102），**没占 8081/5174**
- **指纹复用会把两个子件收敛成一个料号** → TC-A4 两个子件故意用不同总重（11/12），并断言 `c1 != c2`，否则「2 个 input_material_no」会退化成空验证
- **首轮 TC-A2 自己写出了 harness 缺陷**（误留双次 `configure` 调用）→ 已修，以 05:02 那轮为权威；首轮日志一并归档留痕
- **红线**：全程无 DROP / TRUNCATE / 无 WHERE 的 DELETE；`@AfterEach` 每条 DELETE 都限死在「本用例自建 `customer_no` / `quotation_id` / 本轮料号 + `source='MANUAL'`（**白名单，不是 `<> 'IMPORT'`**）」，删除行数逐项打印
- **残留自检**：两个库都查过，`T260910A-%` 在 customer / quotation / `ds_quote_material|_spf|_asm|_customer_part|_mb_record|_eb_record` / `material_master` / `unit_price` / `quote_customer_code` **全部 0 行**。**待回收清单：无**（没建一次性库）

---

## 6. 子代理发现的问题（只报，未动实现）

| # | 问题 | 子代理的判断 |
|---|---|---|
| **1** | ⚠️ **AC-4 行数口径**（见 §2） | 倾向「改 AC」，证据是老表同形态。**需主线裁决** |
| **2** | 🚨 **三片测试员共享同一个 worktree 的 `cpq-backend/target/`** —— 期间出现一轮 `Tests run: 0` + `BUILD FAILURE`（类根本没被执行），紧接着同命令又 4 绿；同时 `git status` 显示 `task260910b/` `task260910c/` 也在同目录跑 maven。**这类症状伪装成「测试挂了」** | 建议主线要么**按片串行跑 maven**，要么各片加 `-Dmaven.build.dir` 隔离。**未改任何共享配置** |
| **3** | 📌 **后端在途编译窗口**：05:00 那阵 `SelDsQuoteWriter.java:247/:340` 报 `cannot find symbol LOG`（工程师正在编辑） | **没有碰那个文件**，只是轮询等它恢复 |
