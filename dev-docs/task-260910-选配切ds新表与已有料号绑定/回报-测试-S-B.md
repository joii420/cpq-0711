# 子代理回报 · 测试片 S-B（读取侧与判据）

> 📌 **先落盘、后分析**（`subagents.md §1⑥`）。逐字留档。
> **落盘时间**：2026-09-10（agent 用时约 27.2 分钟 / 75 次工具调用）
> **认领**：AC-5、AC-6、AC-7、AC-8、AC-9、AC-21、AC-22　**造数前缀**：`T260910B-`

## 结论（回报原文）

> **AC-5 / AC-6 / AC-7 / AC-8 / AC-9 全绿（9 条用例）；AC-21 / AC-22 红 —— 撞到一个实现缺陷：`ConfigureProductService.buildReusedProductInfo` 仍查 `v_compat_material_master`，在 `cpq_db_test` 里该视图不存在 ⇒ 指纹复用命中路径 HTTP 500。**

⚠️ **代码状态锚定（回报原文）**：
> 我起手时 `git status` 是干净的，但**跑测试时后端已把 S-2/S-3 的实现落进工作区（未提交）**（`ConfigureSearchResource.java` / `ConfigureProductService.java` / `SelDsQuoteWriter.java` / `LookupFingerprintResponse.java` + 新增 `SelQuotationScope.java`）。⇒ **本片全部结论对应的代码状态 = 2026-09-10 05:02 的工作区快照**；此后后端又在改 `ComponentDriverService`（S-4），那部分未覆盖。

---

## 1. 逐条 AC（断言 + 实际值，逐字）

| AC | 用例:行号 | 断言原文（摘） | 实际值 | 判定 |
|---|---|---|---|---|
| **AC-5** | `Ac567ReadSideCustomerScopeTest.java:74-76` | 搜 `T260907M-AC17ANCHOR` 返回 ≥1 条且含该料号 | `customerNo=CUST-0001 q=T260907M-AC17ANCHOR → 1 条：[T260907M-AC17ANCHOR]`；阳性对照 `material_master=0 行`（老表确实搜不到） | ✅ |
| **AC-6①** | `:118-130` | 每客户各 1 条、🚫 不出双份 | `CUST-0001 items=[S0003,S0007,S0011,S0014,T260907-M2,T260910B-OUTC1,T260910B-OUTDUAL]`；`CUST-0004 items=[S0003,S0007,S0011,S0014,T260907-M2,T260910B-OUTDUAL]` ⇒ `OUTDUAL` 各 1 次、本片料号无重复 | ✅ |
| **AC-6②** | `:150-153` | `total` 与本客户可见条数自洽（🚫 不断言现网的 5） | `CUST-0001 api=7 db=7`；`CUST-0004 api=6 db=6` | ✅ |
| **AC-6③** | `:132-137` | `CUST-0004` 不含只属 `CUST-0001` 的料号 | `CUST-0004` 列表**不含** `T260910B-OUTC1`；阳性对照：它在 `CUST-0001` 列表里**在** | ✅ |
| **AC-7** | `:222` | 以 `CUST-0004` 搜 `T260910B-C1ONLY` → **0 条** | `→ 0 条：[]`；**两枪阳性对照都响**（`CUST-0001` 搜到 `C1ONLY`、`CUST-0004` 搜到 `MULTI`） | ✅ |
| **AC-8** | `Ac89MaterialPredicateTest.java:68-86` | 返回全部 N 个材质，🚫 不是 1 个 | 自造 `T260910B-MULTI` → `[00006, 00168]`（N=2）；交叉 `S0013@CUST-0004` 期望 `[992,00017]` 实际 `[992,00017]` | ✅ |
| **AC-9①** | `:137` | `PERF0909-B00001`（`omt='成品'`、input JOIN 落空）材质为**空** | 前提自证 `BOM 行=1 JOIN 命中=0 omt=成品 input=T260907T-RM01`；阳性对照该料号**搜得到**；材质集合 `[]` | ✅ |
| **AC-9②** | `:205` | `omt` 为 NULL/零件 但 JOIN 命中 ⇒ 材质**必须带出**（3 行逐个验） | `S0002@CUST-0001→[992]` · `S0002@CUST-0004→[992]` · `T260907-M1@CUST-0004(omt=零件)→[00006]`（外加自造 1 行）**4/4 全带出** | ✅ |
| **AC-9 加固** | `:236,261` | 声明 `RECIPE` 但 JOIN 落空 ⇒ 🚫 不许带出 | 现网 `S-2120011659@CUST-0001` 声明 RECIPE **4 行**、JOIN 命中 **2 行** ⇒ 实际返回 `[00006, 00168]`（2 个，**不是 4 个僵尸**） | ✅ |
| 契约守卫 | `:175 / :241` | 两端点缺 `customerNo` ⇒ 400 | `400 {"data":{"code":"CUSTOMER_NO_REQUIRED"}}`，文案「已有零件搜索必须携带客户编号(customerNo)」/「外购件候选必须携带客户编号(customerNo)」 | ✅ |
| **AC-21** | `Ac2122FingerprintRegressionTest.java:90` | 同输入再选配 ⇒ `fingerprintMatched=true` + 复用原料号 | 第一次 200、`fingerprintMatched=false`、铸号 `1009-2609000001`、`ds_quote_material=1`/`_customer_part=1`（前提全对）；**第二次提交 HTTP 500** | 🔴 |
| **AC-22** | `:180` | 换序仍命中 + 确认页显示 `（Z100 → Z101）` | 前置已验：`ds_quote_self_process_fee=[Z100, Z101]` / `unit_price=[]`（**写入侧切表生效**）；**换序提交 HTTP 500** | 🔴 |

---

## 2. 🔴 发现的实现缺陷（根因 + 证据，🚫 子代理未动实现）

```
【现象】选配提交第 2 次（同指纹 ⇒ 命中复用）返回 500 {"code":500,"message":"Internal server error"}
        第 1 次（不命中）正常 200。
【预期】AC-21①「完全相同的输入应命中复用，fingerprintMatched=true」
        AC-22①「换序仍命中复用」
【复现】① 建客户+报价单 → ② 提交零件（材质 00006/00006-01 占比100，工序 Z100,Z101）→ 200
        ③ 换一个 customerProductNo、零件输入逐字相同再提交 → 500
        环境：worktree 内 mvnw test（test profile → 10.177.152.12:5432/cpq_db_test），必现 2/2
【影响】阻塞（AC-21 / AC-22 两条回归 AC 全部无法达成；复用是选配主路径）
【证据】ERROR: relation "v_compat_material_master" does not exist  (SQLState 42P01)
        SQL: SELECT mm.material_name, mm.specification, mm.dimension, mm.unit_weight,
                    (SELECT min(sps.created_at) FROM sel_part_signature sps
                      WHERE sps.quote_part_no = mm.material_no)
               FROM v_compat_material_master mm WHERE mm.material_no = ?
        栈顶：ConfigureProductService.buildReusedProductInfo(ConfigureProductService.java:1802)
              ← ConfigureProductService.configure(:1993)
        库侧实查：v_compat_material_master 在 cpq_db_test 【不存在】（V439 "task260909 drop
                 compat views" success=t 已真正执行）；在 cpq_db_0724 【仍存在】（3 个 v_compat_*
                 都在）—— 与需求文档 §2.2「迁移历史与库状态已不一致」吻合。
        ⇒ 该缺陷在 dev 库上被视图的意外存活掩盖，在 test 库上必现。
        证据归档：dev-docs/.../证据/S-B/com.cpq.task260910b.Ac2122FingerprintRegressionTest.stdout.txt
【建议】方向而非结论：需求文档 §2.2 明确「本任务只**停止引用** v_compat_*，S-2 改读
        ds_quote_material 后选配侧 4 个引用点归零」，而 §4.2 列出的剩余消费方是
        ExistingProductService / ExistingProductDTO / QuotePendingRewriter / SemanticCompiler
        —— **buildReusedProductInfo 不在这两份清单里任何一处 ⇒ 引用面审计漏了它**。
        看起来是 S-2 的第 5 个引用点（品名/规格/尺寸/单重四列 + sel_part_signature 首次时间），
        应与其余 4 点一并换到 ds_quote_material（+ customer_no 维度，D-2）。
        请主线裁决：是纳入 S-2 一并改，还是单开返修。
```

---

## 3. FT-2 证伪实验

**可执行的那半：已执行且通过**（回报原文）
> `SbBase.assertDualCustomerPremise()`（`SbBase.java:290-310`）用**同一份数据源、不带客户谓词**做反事实对照，输出：
> `[S-B·FT-2 反事实] T260910B-OUTDUAL 不带客户谓词=2 行；带 CUST-0001=1 行；带 CUST-0004=1 行`
> ⇒ 证明「每客户各 1 条」**不是恒真**。若现网某天变成每料号只挂 1 客户，这枪会当场硬失败，而不是让 AC-6 恒绿。

**改实现的那半：未执行（无权改实现）**，步骤：
> 1. 在 `ConfigureSearchResource` 的外购件候选 SQL 里**注掉 `AND customer_no = :customerNo`**（只这一处）；
> 2. 确认干预真的生效（编译/重启日志里必须看到重编译，🚫 不看日志不读数）；
> 3. `DB_NAME=cpq_db_test QUARKUS_REDIS_HOSTS='redis://:joii5231@10.177.152.12:6379/0' ./mvnw -o test -Dtest=Ac567ReadSideCustomerScopeTest -Dquarkus.http.test-port=<空闲端口>`；
> 4. **`tcB2_ac6_outsourcedCustomerIsolation` 必须变红**（`OUT_DUAL` 出 2 行）；`tcB2b` 也应变红（不再 400）。不变红 = 断言没接上。

---

## 4. 「未验证」标注（逐字）

1. **AC-21 / AC-22 在缺陷之后是否还有别的问题** —— **未验证**。500 抛在第一条业务断言之前，后面的复用/顺序/契约裁剪断言（`:96,103,113,124,186,207` + `api.md §2.4` 两个删字段）**一次都没执行**。
2. **`cpq_db_0724` 上的 A/B（视图存在时 AC-21/22 是否全绿）—— 未验证（受阻）**。原因是环境而非产品：本 worktree 内**同时**有 S-A 的 `mvnw -o test` 和后来拉起的 `quarkus:dev -Dquarkus.http.port=8094`，与我共用**同一个 `target/`** ⇒ 实测两次编译产物互相破坏（`bad class file … NoSuchFileException` 数十处、`Could not load class Ac2122…`），且 8097 被别的 worktree 的 dev server 占用导致一次 `Failed to start quarkus`。命令留给主线：
   ```bash
   cd cpq-backend && DB_NAME=cpq_db_0724 QUARKUS_REDIS_HOSTS='redis://:joii5231@10.177.152.12:6379/0' \
     ./mvnw -o test -Dtest=Ac2122FingerprintRegressionTest -Dquarkus.http.test-port=8102
   ```
   （先确认本 worktree 内无并发 maven / dev server）
3. **前端侧未验证** —— 本片是接口层与 DB 层。`ExistingPartPanel.tsx` 的多材质展示形态（`api.md` R-2：`materials[]` vs `materialsLabel`）只在解析层做了双形态兼容 + 对旧单值形态点名报错，**没有走 UI**。
4. **S-4（`ComponentDriverService`）之后的改动未覆盖** —— 见开头的代码状态说明。

---

## 5. 子代理规避掉的坑（每个都实际踩到或差点踩到，逐字要点）

1. 🚨 **AC-5 与 AC-7/D-2 前置互斥（需求文档缺陷，请裁决）**：AC-5 前置写「`CUST-0004` 的报价单」，但 `T260907M-AC17ANCHOR` 在 `ds_quote_material` 里**只挂 `CUST-0001`**（两库各 1 行，实查）。在 D-2 之下，以 `CUST-0004` 搜它**必须返 0 条** —— 那正好是 AC-7 的语义。⇒ 按字面执行两条 AC 直接互斥。取唯一自洽读法（AC-5 验「切表」、客户维度交给 AC-7），用该料号**所属客户**发起搜索，并在用例 javadoc 里写明矛盾。**建议把 AC-5 前置改成 `CUST-0001`，或换一个 `CUST-0004` 名下的料号。**
2. 🚨 **AC-8 前置数字口径不一致**：AC-8 写「2 个材质的 7 个料号、4 个材质的 2 个料号」。实查 `cpq_db_0724`：按 `material_no` 分组（**忽略客户**）是 2→8 / 4→1；按 `output_material_type='RECIPE'` 分组是 2→10 / 4→3；而按 **(customer_no, material_no)** 分组 —— 也就是 D-2 之后查询真正的粒度 —— 是 **2→5、4→0 个**。⇒ 「4 个材质的料号」在客户维度下**根本不存在**（`S-2120011659` 的 4 行 BOM 只有 2 行 JOIN 命中）。因此**不依赖这些数字**，自造 `T260910B-MULTI`（N=2）当被测对象，现网料号只做交叉验证。
3. **不断言现网的「5 条」**：自造的 2 个外购件对照就把 `total` 顶到了 7 / 6 —— 实测证明 `test.md §2` 那条纪律是对的，直接断言 5 会被**自己**打红。
4. **阴性断言全部配阳性对照**：AC-7 的「0 条」配两枪；AC-6③ 的「不含」配「它在自己客户列表里在」；AC-9① 的「材质为空」配「该料号本身搜得到」。没有这些，端点坏掉 / 关键词失效都会让这些断言**恒绿**。
5. **LEFT JOIN 要求顺带被验到**：`T260910B-C1ONLY` 完全没有 BOM 行，仍被 AC-7 阳性对照①搜到 ⇒ `api.md §2.1`「料号本身用 LEFT JOIN」成立。
6. **自己的 harness bug（已修）**：清理清单里写了 `DELETE FROM capacity WHERE customer_no=?`，而 **`capacity` 没有 `customer_no` 列** ⇒ 抛 42703 把整个 `requiringNew()` 事务连带回滚 ⇒ 本轮自建 customer 一行都没删掉，然后在**下一个测试类**的残留自检里报「清理未净」，失败信息指向的是后面那个类。已加 `columnExists()` 逐列守卫 + 把残留自检从 `PREFIX` 全局计数收窄到**本轮 `RUN_ID`**。
7. `/q/health` 404 不是探针 / `--noproxy '*'` / `test` profile 实连 `cpq_db_test`（`\conninfo` 实测确认）/ `grep` 一律 `/usr/bin/grep -a` —— 全部按派工单执行。
8. **红线**：全程无 DROP / TRUNCATE / 清库 / 无 WHERE 删除。中途清一次自己上一轮的 14 行残留时**先跑 `SELECT` 量化影响面**（2 customer + 12 依赖行，全部自造、几分钟前建），命中面被自建 customer code 与 `T260910B-` 前缀限死。

**残留核验**：`cpq_db_test` 与 `cpq_db_0724` 两库的 `customer` / `ds_quote_material` / `ds_quote_material_bom` / `quotation` 中 `T260910B-` 前缀均为 **0 行**。**一次性库：无**（无待回收清单）。

---

## 6. 🚨 子代理给主线的编排级建议（与 S-A §6-2 同一件事，实测更严重）

> **本 worktree 的 `cpq-backend/target/` 是 S-A、S-B 与后端实现三方共享的可写对象**（`testing.md §4.2`「并行测试的临时资源必须唯一化」被违反）。实测后果：编译产物互相破坏，症状是几十条 `cannot access … bad class file / NoSuchFileException` 与 `Could not load class`，**长得完全像代码坏了**。建议二选一：① 各测试片用独立 `-Dmaven.build.dir` 或独立拷贝；② 测试片与实现改动串行。否则后续任何一轮红都需要先排除这一层。

---

## 7. 产出文件（绝对路径）

- `.../cpq-backend/src/test/java/com/cpq/task260910b/SbBase.java`（**538 行** · 基座：造数/前缀/证伪前提/登录/清理）
- `.../cpq-backend/src/test/java/com/cpq/task260910b/Ac567ReadSideCustomerScopeTest.java`（AC-5/6/7 + 2 条契约守卫）
- `.../cpq-backend/src/test/java/com/cpq/task260910b/Ac89MaterialPredicateTest.java`（AC-8/9 + AC-9 反向加固）
- `.../cpq-backend/src/test/java/com/cpq/task260910b/Ac2122FingerprintRegressionTest.java`（AC-21/22）
- 证据归档：`dev-docs/task-260910-选配切ds新表与已有料号绑定/证据/S-B/`（3 份 surefire XML + 3 份抽出的 stdout 文本）

**子代理自检声明（原文）**：
> `./mvnw -o test-compile` 0 错误 ✅；`Ac567ReadSideCustomerScopeTest` 5/5 ✅、`Ac89MaterialPredicateTest` 4/4 ✅、`Ac2122FingerprintRegressionTest` 0/2 🔴（根因见 §2）；连库 `\conninfo → cpq_db_test` ✅；临时端口 8096（未占 8081/5174）✅；两库残留 0 行 ✅。🚫 未执行 `git commit`。
