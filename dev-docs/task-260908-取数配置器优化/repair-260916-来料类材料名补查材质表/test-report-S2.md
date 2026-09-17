# test-report · S-2（私有写片，`cpq_db_test`）

> 分片：S-2 · 认领 AC-5 / AC-8 · 造数前缀 `R260916-T-` · 库 `10.177.152.12:5432/cpq_db_test`（`application-test.properties:32` 默认值，未用 `DB_NAME` 覆盖）
> 用例：`cpq-backend/src/test/java/com/cpq/repair260916/{R260916TBase,Ac5MaterialNamePriorityTest,Ac8RecompileComponentsTest}.java`
> 证据归档：`证据/S-2/`（`s2-run1.log` / `s2-diag.log` / `s2-run2.log` / `s2-falsify.log` / `s2-run3.log` + 三轮 surefire 报告）
>
> **最终结论（run2 + run3 合并）：AC-5 ①~④ 与 AC-8 ①~⑥ 全部通过，12/12 个用例为绿。** run3 只重跑了 t24a / t24b，其余 10 个用例以 run2 为准。

## 0. 执行记录与确权（testing.md §4.2.5）

| 轮次 | 命令 | 开始（UTC） | 结果 | 确权 |
|---|---|---|---|---|
| run1 | `./mvnw clean test -Dtest='Ac5MaterialNamePriorityTest,Ac8RecompileComponentsTest' -Dsurefire.failIfNoSpecifiedTests=false` | 2026-09-17T02:46Z 前后 | 12 跑 / 2 失败（t24a/t24b）· `EXIT=1` | 日志末尾 `EXIT=1`；两份 surefire 报告已复制到 `run1-surefire/` |
| diag | `./mvnw test -Dtest='Ac5MaterialNamePriorityTest#t24a_costBasic_threeCases'` | — | 1/1 失败（加了「只打印」的 compile 诊断） | 仅用于归因 |
| **run2（t21~t23、AC-8 全部的判定依据）** | 同 run1（含 `clean`） | **2026-09-17T02:48:32Z** | **12 跑 / 2 失败（t24a/t24b）· `EXIT=1`** | surefire 报告 mtime `19:49:06 / 19:49:24 PDT`（= 02:49Z，晚于开始时刻）；日志末尾 `EXIT=1` |
| falsify（证伪，见 §8） | `./mvnw test -Dtest='Ac5MaterialNamePriorityTest#t24a_costBasic_threeCases'`（列名临时改为 `material_namex`） | — | 1/1 失败（预期内） | `s2-falsify.log`，已还原并复验 |
| **run3（t24a / t24b 的判定依据）** | `./mvnw clean test -Dtest='Ac5MaterialNamePriorityTest#t24a_costBasic_threeCases+t24b_costDetail_threeCases' -Dsurefire.failIfNoSpecifiedTests=false` | **2026-09-17T02:53:27Z** | **2 跑 / 0 失败 · `BUILD SUCCESS` · `EXIT=0`** | surefire 报告 mtime `19:53:54 PDT`（= 02:53:54Z，晚于开始时刻）；日志末尾 `EXIT=0` |

开跑前 `pgrep -af java` 采样：worktree 内无 java 进程（其余进程在主工作区 `cpq-backend` 与 scratch 副本 `accept-backend-cc803299`）。全程只有本片一个 maven。

## 1. 结论总览

| 用例 | AC | run2 | 说明 |
|---|---|---|---|
| `Ac5#t21_bothTables_materialWins` | AC-5 ① | ✅ | |
| `Ac5#t22_neitherTable_rowKeptNameNull` | AC-5 ② | ✅ | |
| `Ac5#t23_otherCustomerMaterialNotLeaked` | AC-5 ③ | ✅ | 含阳性对照 |
| `Ac5#t24a_costBasic_threeCases` | AC-5 ④ | run2 ❌ 量具 → **run3 ✅** | 见 §3、§8 |
| `Ac5#t24b_costDetail_threeCases` | AC-5 ④ | run2 ❌ 量具 → **run3 ✅** | 同上 |
| `Ac8#t25_idsRequired` | AC-8 ① | ✅ | |
| `Ac8#t25b_invalidUuid` | AC-8 ①（api.md §1.7） | ✅ | |
| `Ac8#t26_missingIdWholeRequest404` | AC-8 ② | ✅ | 含阳性对照 |
| `Ac8#t27_componentWithoutBuilderViewSkipped` | AC-8 ③ | ✅ | |
| `Ac8#t28_secondRunIsNoop` | AC-8 ④ | ✅ | |
| `Ac8#t29_rbac` | AC-8 ⑤ | ✅ | |
| `Ac8#t210_corruptConfigRollsBackAll` | AC-8 ⑥ | ✅ | 含阳性对照；回滚被真实考到（见 §4） |

⇒ **AC-8：①~⑥ 全部通过。AC-5：①~④ 全部通过**（④ 在主线批准量具修正后，由 run3 判定）。

## 2. 每条用例的实际值（逐字摘自 `s2-run2.log`）

### AC-5（`material_recipe.00144.symbol=H85`；前置 `ds_cost_basic_material(00144)=0`、`ds_cost_detail_material(00144)=0`）

- **T2.1** `[AC-5 实际值] partCol=_来料固定加工费_投入料号 nameCol=_物料_材料名 我的行数=2 投入料号→材料名={00144=[R260916-T-物料名-00144], R260916-T-M1-389F=[R260916-T-物料名-M1]} diagnostics=[]`
- **T2.2** `[AC-5 实际值] partCol=_来料固定加工费_投入料号 nameCol=_物料_材料名 我的行数=2 投入料号→材料名={00144=[H85], R260916-T-N2-389F=[null]} diagnostics=[]`
- **T2.3 阳性对照（他客户 C4 自己预览）** `… 投入料号→材料名={00144=[R260916-T-他客户名-00144], R260916-T-X3-389F=[R260916-T-他客户名-X3]} diagnostics=[]`
- **T2.3 宿主客户 C3** `… 投入料号→材料名={00144=[H85], R260916-T-X3-389F=[null]} diagnostics=[]`
- **T2.4a 原始预览响应（COST_BASIC，partNo=生产料号 `R260916-T-PC5-389F`）**
  `{"rowCount":3,"columns":["hf_part_no","incoming_material_no","material_name","process_fee","view_version"],"rows":[{"hf_part_no":"R260916-T-PC5-389F","incoming_material_no":"00144","material_name":"H85",…},{…"incoming_material_no":"R260916-T-PMC5-389F","material_name":"R260916-T-核价物料名-C5",…},{…"incoming_material_no":"R260916-T-PNC5-389F","material_name":null,…}],"elapsedMs":8,"diagnostics":[]}`
- **T2.4b 原始预览响应（COST_DETAIL）**
  `{"rowCount":3,"columns":["hf_part_no","incoming_material_no","material_name","fee","view_version"],"rows":[{…"incoming_material_no":"00144","material_name":"H85",…},{…"incoming_material_no":"R260916-T-PMC6-389F","material_name":"R260916-T-核价物料名-C6",…},{…"incoming_material_no":"R260916-T-PNC6-389F","material_name":null,…}],"elapsedMs":10,"diagnostics":[]}`

### AC-8

- **T2.5** 四个请求 `{confirm=true}` / `{componentIds=null, confirm=true}` / `{componentIds=[], confirm=true}` / `{componentIds=[], confirm=false}` 均为 `400 {"code":"COMPONENT_IDS_REQUIRED","message":"componentIds 必填且不能为空数组"}`；过期组件快照 s0 = s1。
- **T2.5b** `400 {"code":"INVALID_COMPONENT_ID","message":"componentIds 含非法 UUID: R260916-T-not-a-uuid","invalidIds":["R260916-T-not-a-uuid"]}`；快照不变。
- **T2.6**
  - 阳性对照·预览：`200 … "preview":true,"componentCount":1,"views":1,"changed":1,"changes":[{"componentId":"7ca46271-…","componentCode":"COMP-3326",…,"sqlViewName":"builder_7ca462711313","oldSqlTemplate":"/* R260916-T-STALE */ SELECT …`
  - 阳性对照·执行：`200 {"preview":false,"componentCount":1,"views":1,"changed":1,"changedViewNames":["builder_7ca462711313"],"unchangedViewNames":[],"skippedComponentIds":[],"operationLogIds":["81d7fc03-de82-4122-bb1e-f3a56cc38153"]}`；快照 sql md5 `962f1e63…` → `d49022ae…`（观察手段能看到写入）
  - 混合请求 confirm=true / false：均为 `404 {"code":"COMPONENT_NOT_FOUND","message":"以下组件不存在: 2332b079-a97c-4db9-9ae3-3855e8cd0bd9","missingIds":["2332b079-a97c-4db9-9ae3-3855e8cd0bd9"]}`；重新标过期后的快照 s0 = s1（md5 `962f1e63…` 未变）
- **T2.7** `200 {"preview":false,"componentCount":3,"views":1,"changed":1,"changedViewNames":["builder_4da4499c42f0"],"unchangedViewNames":[],"skippedComponentIds":["1b29a5f5-…","ebdd9129-…"],"operationLogIds":["94775e9b-20f9-45fd-a80c-50e0274d2f4d"]}`（`1b29a5f5` = 空白组件，`ebdd9129` = 仅手写视图组件，两者快照不变）
- **T2.8**
  - 第一次：`200 {"componentCount":2,"views":2,"changed":2,"changedViewNames":["builder_0c5295bd35ed","builder_870a2cb204dc"],"operationLogIds":["aa601c25-…","ef24a350-…"]}`
  - 审计行：`[COMPONENT_VIEW_RECOMPILE, COMPONENT, 0c5295bd-35ed-4b9f-af40-ade01502bab8, 按组件重编译取数视图 builder_0c5295bd35ed, builder_0c5295bd35ed, recompile-components, d1e1147c-a639-4156-aeac-9f938a65ad05]`；另一行同形（`870a2cb2…` / `builder_870a2cb204dc`）。`operator_id` = admin 的 id。
  - 第二次：`200 {"changed":0,"changedViewNames":[],"unchangedViewNames":["builder_0c5295bd35ed","builder_870a2cb204dc"],"skippedComponentIds":[],"operationLogIds":[]}`；两个组件的审计行数不再增加
  - 旁证（不判定）：`component fields/formulas md5 before=eb47f5c0… after=eb47f5c0…`
- **T2.9** `/auth/me` 返回 `200 … "username":"t260903_sales" … "role":"SALES_REP"`；SALES_REP 调用 confirm=true 与 confirm=false 均为 `403 {"code":403,"message":"无权限访问"}`；未登录两种都为 `401 {"code":401,"message":"未登录"}`；admin 对照预览 `200`；快照不变。
- **T2.10**
  - 组件编号 `a1=COMP-3333 bad=COMP-3334 a2=COMP-3335`（视图名 `a1=builder_163c98736519 bad=builder_8ebc75073690 a2=builder_d306dc667c4f`）
  - 阳性对照：a1、a2 单独执行各返回 `changed=1`，sql md5 `962f1e63…` → `d49022ae…`
  - 混合请求 confirm=true / false：均为 `500 {"code":"RECOMPILE_CONFIG_CORRUPT","message":"视图「builder_8ebc75073690」的 builder_config 无法反序列化，重编译已整体中止（…）: Cannot deserialize value of type \`java.util.ArrayList<…BuilderConfig$ColumnConfig>\` from String value …","componentId":"8ebc7507-3690-43b1-92bb-5cce53a3c35c","sqlViewName":"builder_8ebc75073690"}`；a1、a2、bad 三者快照不变，a1、a2 的过期标记仍在

## 3. 失败项：原始输出 + 归因

### F-1（run1）t24a / t24b：预览 0 行 —— 归因 **量具前提**，已修夹具输入（断言未改）

原始输出（run1）：
```
Ac5MaterialNamePriorityTest.t24a_costBasic_threeCases:375->runCostCase:413->parse:199 🔴 预览 0 行 …
diagnostics=[{level=WARN, code=PREVIEW_ZERO_ROWS, … message=没有取到数据。核价取数是「销售料号 → 料号桥 → 生产料号」三步，请按顺序排查：① 销售料号「R260916-T-SC5-7217」在报价物料（ds_quote_material）里是否存在；② 该行的生产料号是否已填…；③ 该生产料号在本页签对应的核价表里是否已有数据。🚫 与客户无关——核价数据集没有客户维度}]
```
- 诊断轮（`s2-diag.log`）调 `/builder/compile` 得到的编译产物：`… COALESCE(dcbm.material_name, mr.symbol) AS "material_name" … LEFT JOIN ds_cost_basic_material dcbm ON dcbm.production_no = vdcbipfa.incoming_material_no  LEFT JOIN material_recipe mr ON mr.code = vdcbipfa.incoming_material_no WHERE :versionFilter(…) AND vdcbipfa.production_no = ANY(:total_material_no)`
- 按 `CLAUDE.md` 的例外条款（定位失败原因时可读实现，不据此改断言），读了 `BuilderService#bindTotalMaterialNo` 的核价分支：**核价方言的 `/preview` 把 `partNo` 原样当作生产料号代入 `production_no = ANY(...)`，不走销售料号桥**。本片的前提（partNo = 销售料号）是错的。
- 修正：`runCostCase` 的预览 `partNo` 改传生产料号，并加注释留碑。**取值断言一条未改。**已按类注释交主线确认。
- 🔎 **旁报（非本片 AC）**：0 行诊断文案说的是「销售料号 → 料号桥 → 生产料号」三步、要求排查销售料号，但预览的实际行为是把输入当作生产料号直接收窄。**文案与行为不一致**，会把用户引向错误的排查方向。建议主线另行登记，本片不作判定。

### F-2（run2）t24a / t24b：`E-5：「材料名」列应恰好 1 个，实际 columns=[hf_part_no, incoming_material_no, material_name, process_fee, view_version] ==> expected: <1> but was: <0>` —— 归因 **量具（本片的启发式）**

原始输出：
```
org.opentest4j.AssertionFailedError: E-5：「材料名」列应恰好 1 个，实际 columns=[hf_part_no, incoming_material_no, material_name, process_fee, view_version] ==> expected: <1> but was: <0>
	at …Ac5MaterialNamePriorityTest.parse(Ac5MaterialNamePriorityTest.java:210)
	at …runCostCase(Ac5MaterialNamePriorityTest.java:423)
（t24b 同形，columns=[hf_part_no, incoming_material_no, material_name, fee, view_version]）
```
- 本片在核价侧用「列名含『材料名』」来定位材料名列。这是**我自己的推断**，把 E-5「视图列名仍是 `_物料_材料名`」外推到了核价侧。AC-3 核价部分只要求 SQL 含 `COALESCE(<别名>.material_name, <别名>.symbol)`，**没有规定核价侧的输出列名**。实测核价方言输出的是裸物理列名 `material_name`（与 task-260819 v9-6「首次出现保持裸名」一致）。
- ⇒ 不是产品缺陷。**按主线要求没有为变绿改这条断言。**
- 原始响应（§2 T2.4a/b）显示的取值 `00144→H85`、`PM→核价物料名`、`PN→null`、3 行都在，**与 AC-5 ④ 完全一致**。但这属于人工读日志，不是断言结论。
- **建议修法（待主线批准）**：核价侧材料名列取 `material_name`（即配置里 `MAT_NAME_LK.material_name` 对应的输出列），并把「恰好 1 个」改成「该列存在且只有 1 列」。批准后只重跑 t24a/t24b。

## 4. 证伪实验结果

| 实验 | 结果 |
|---|---|
| AC-8 ② 阳性对照：同一个 id 单独执行 | ✅ 观察到写入：sql md5 `962f1e63…→d49022ae…`、过期标记消失、审计 +1；随后混合请求下快照完全不变 ⇒ 零写入判据有效 |
| AC-8 ⑥ 阳性对照：a1、a2 单独执行 | ✅ 两者都观察到写入；混合请求下两者快照不变 |
| AC-8 ⑥ 回滚是否被真实考到 | a1 的组件编号与视图名**都排在**损坏视图之前（`COMP-3333 < COMP-3334`；`builder_163c… < builder_8ebc…`）⇒ 无论实现按编号还是按视图名处理，a1 都先于损坏视图被处理；a1 未落库 ⇒ **整体回滚成立**（实现内部顺序本片看不到，此处为推断） |
| AC-5 ③ E-3 判别 | ✅ 他客户物料名 ≠ H85；阳性对照证明他客户那行对查名可见；宿主侧拿到的是 H85 / null，不是他客户名 |
| AC-5 ①「材质优先」错误实现会红 | ✅ 物料名 ≠ symbol，实得物料名 |
| AC-5「只在材质表」修前修后可区分 | ⚠️ **未做实跑对照**：测试库已含 V444，无法回到改动前（问题说明 ② 复现 3 为修前恒 null 的依据） |
| AC-8 ⑤ 403 来源 | ✅ sales 会话 `/auth/me` 为 200 且角色 SALES_REP；同一请求 admin 返回 200 ⇒ 403 来自角色 |

## 5. 未验证项

- ~~AC-5 ④~~：已由 run3 验证通过（§8）。
- AC-5 修前 / 修后的实跑对照：未做（见 §4）。
- AC-8 ⑥「实现内部处理顺序」：本片不可见，结论基于编号与视图名双序推断。

## 6. 本片实际写入与清理核对

写入面：`ds_quote_material`、`ds_quote_incoming_fixed_fee`（customer_no 带前缀）；`ds_cost_basic_material`、`ds_cost_basic_incoming_process_fee`、`ds_cost_detail_material`、`ds_cost_detail_incoming_other_fixed_fee`（production_no 带前缀）；`component` 与级联的 `component_sql_view`（name 带前缀）；`operation_log`（target_id 为自建组件）。`material_recipe` 与 `user` 只读。

每个用例 `@AfterEach` 的残留自检均为 `{…=0}`（见日志 `[S-2 residue]`）。run2 结束后主动核对（2026-09-17T02:50:14Z）：

```
    ?column?    | count
----------------+-------
 qm             |     0
 qfee           |     0
 cbm            |     0
 cbf            |     0
 cdm            |     0
 cdf            |     0
 comp           |     0
 csv            |     0
 tcs            |     0
 oplog-run2-ids |     0
(10 rows)
```
（`oplog-run2-ids` = run2 返回的 6 个 `operationLogIds` 在库里的剩余行数。）

待回收清单：**无**（本片未建库）。

## 7. 本片对用例文件的执行期改动（均在 `Ac5MaterialNamePriorityTest.java`，断言期望值一律未改）

1. `runCostCase`：预览 `partNo` 由销售料号改为生产料号（F-1，夹具输入，带留碑注释）
2. `runCostCase`：0 行时追加一次 `/builder/compile` 打印（只打印，不判定）
3. `parse` 的 0 行提示文案同步更新
4. （主线裁决 F-1）`runCostCase` 注释改为：依据是父任务 D-12「核价侧主轴 = 生产料号」；同时如实留痕，写明归因时曾按例外条款核对过实现
5. （主线裁决 F-2）`parse` 找列逻辑：去掉「列名含『材料名』」的推断，一律显式传列名。报价侧 `_物料_材料名`，核价侧 `material_name`；两侧都要求该列名**恰好 1 个**（0 个或多个都判失败）

AC-5 ④ 的三个期望值（核价物料表有 → 物料名；只在材质表 → symbol；都没有 → null）和「3 行都在」一个字未动。

## 8. run3（主线裁决后只重跑 t24a / t24b）

### 8.1 新防护的证伪实验（testing.md §4.4 / §5.7）
- 干预：临时把核价侧列名改成 `material_namex`。改完先确认 `干预命中=1（应 1）`，再跑 t24a。
- 结果：硬失败，原文如下：
  `材料名列「material_namex」应恰好 1 个，实际 columns=[hf_part_no, incoming_material_no, material_name, process_fee, view_version] ==> expected: <1> but was: <0>`（`EXIT=1`）
- 还原：用备份以绝对路径写回，还原后复验：`material_namex 命中=0（应 0）；material_name 命中=1（应 1）`
- 局限：「多个同名列」这个分支没有单独证伪，因为输出列名由后端生成，测试侧造不出重名。判定逻辑与 0 个的情形共用同一个计数 `== 1`。

### 8.2 实际值（逐字摘自 `s2-run3.log`）
- **T2.4a（基础核价 / 来料加工费）** `[AC-5 实际值] partCol=incoming_material_no nameCol=material_name 我的行数=3 投入料号→材料名={00144=[H85], R260916-T-PMC5-7C68=[R260916-T-核价物料名-C5], R260916-T-PNC5-7C68=[null]} diagnostics=[]`
- **T2.4b（明细核价 / 来料其他固定费用）** `[AC-5 实际值] partCol=incoming_material_no nameCol=material_name 我的行数=3 投入料号→材料名={00144=[H85], R260916-T-PMC6-7C68=[R260916-T-核价物料名-C6], R260916-T-PNC6-7C68=[null]} diagnostics=[]`
- surefire：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · `EXIT=0`
- 每个用例 `[S-2 residue]` 全为 0

### 8.3 run3 后的清理核对（2026-09-17T02:54:11Z）
```
 ?column? | count
----------+-------
 qm       |     0
 qfee     |     0
 cbm      |     0
 cbf      |     0
 cdm      |     0
 cdf      |     0
 comp     |     0
 csv      |     0
 tcs      |     0
(9 rows)
```

