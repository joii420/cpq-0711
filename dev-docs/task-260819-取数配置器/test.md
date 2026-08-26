# test.md · 取数配置器（task-260819）· 测试方案与 AC 可追溯矩阵

> 闸门 A 的**前置产物**，开工前写完。执行结果另出 `test-report.md`。
> AC 原文在 `需求文档.md §3`（一期 51 条：AC-1~40、47~50、51~57；二期 6 条 AC-41~46 本期不测）。

## 1. 测试分层

| 编号 | 层次 | 覆盖什么 | 环境 |
|---|---|---|---|
| **T-1** | 后端单测 | 编译器：路径求解 / 按边基数生成 SQL / 铁律 / 方言参数化 / 别名纯函数 | `test` profile（`cpq_db`，**与 dev 库不是同一个库**） |
| **T-2** | 后端单测 | 四道保存期校验 + 体检规则 | 同上 |
| **T-3** | 后端集成 | 端点契约 + 权限 + 一体化保存事务 + 预览 | 同上 |
| **T-4** | ★ **golden 逐行等值 × 5 类** | 配置器产物 vs 现网手写基准 | **dev 库 `cpq_db_0724`** + 罗克韦尔 |
| **T-5** | E2E（Playwright） | 前端交互与连续操作 | dev server 5174 + dev 库 |
| **T-6** | CI 断言 | 边基数 + handler 双向对账 | CI |
| **T-7** | 回归 | 存量手写视图零影响 | dev 库 |

## 2. 🔑 反证型用例（10 条，最容易被做成「跑通即可」）

**只验证正向通过不算达成** —— 测试报告里必须同时附「人为破坏后确实失败」的证据。

| AC | 破坏方式 | 期望 |
|---|---|---|
| AC-9 | 把生成的 SQL 顶层 FROM 改成 CTE | `rewriterCompatible=false`，锚点注入失败可见 |
| AC-10 | 构造两条可达路径 | 编译 400 `COMPILE_PATH_AMBIGUOUS` + 列出两条路径 |
| AC-22 | 绕过前端直接提交缺绑定的保存 | 后端拒绝（证明后端**确实在校验**，不是前端挡的） |
| AC-35 | 把一条真实一对多的边改成 `MANY_TO_ONE` | CI 断言**必须红**，且指出哪条边、右侧哪个键重复 |
| AC-36 | 在某 `Q*Handler` 里加一个 `put(...)` 不改登记 | 对账断言**必须红**，指出哪个 handler 哪一列未登记 |
| AC-52 | 同 AC-35，走保存端点 | 400 + 库中该边**未写入**；改回 `ONE_TO_MANY` 后同一请求成功 |
| AC-53 | `physical_table` / `db_column` 填不存在的名字 | 400，分别报「表不存在」「列不存在，该表实有列为…」 |
| AC-54 | **用 `psql` 直接** `DELETE FROM semantic_node WHERE node_key='ELEMENT_BOM_ITEM'` | **数据库层**报外键违反并回滚（绕过应用仍必须失败） |
| AC-55 | 库中人为构造两条路径的边组合 | 保存 400 + 列出两条路径的节点序列 |
| AC-56 | 三个非超管角色各发 `POST/PUT/DELETE` | 一律 403 且**库中数据逐行未变** |
| AC-59 🆕 | 编译产物含 `= ANY(:total_material_no)`，在不设 `BomTreeVarsContext` 的情况下执行 | 抛可识别错误（点名 `total_material_no`），**不得**返回 0 行；错误信息在用户可见处（响应体，不只是日志）——D-53 明写这是要堵"`ANY(NULL)` 恒 0 行不报错"的坑 |

> ⚠️ **AC-59【已知信息缺口】**：`Sec36bClosureUnificationTest::ac59_missingContextMustErrorNotSilentlyReturnZeroRows`
> 用"只给 `customerCode`、不给 `partNo`"来黑盒构造"上下文缺失"，因为本测试工程师被禁止读
> `com.cpq.builder`/`com.cpq.semanticgraph` 实现代码，不知道 `BomTreeVarsContext`/`SqlViewExecutor`
> 的公开方法签名，写不出直接调用它们的白盒单测；`backtask.md` 里也没有任何任务条目明确
> "`/builder/preview` 端点如何取得 `:total_material_no`"（B-19 明确只覆盖"报价侧渲染链路"，
> B-11"预览执行"的任务描述未随 D-50~D-53 更新）。这是本测试工程师能想到的最佳黑盒近似，
> **不代表这就是后端最终会走的代码路径**——若后端在更早的校验层拦截（如"partNo 必填"400），
> 同样满足"不静默返回0行"的核心诉求，只是可能不会在错误信息里出现"total_material_no"字样，
> 测试方法里把这一点降级为信息性打印，不作为硬性阻断项，避免因为猜错分层而误判。
> 需要主线/后端确认：预览端点的上下文注入职责归属，以及"缺失"要报什么错。

> ⚠️ **AC-52 的样本不足盲区要单独测**：造一张只有 1 行数据的目标表，断言返回 **`THIN`** 而不是 `PASS`（D-32 实测 RECYCLE 全库仅 1 行，任何基数声明都能过 —— 这是断言的固有假阴性）。

## 3. golden 基准（T-4，★ 总把关）

| 页签类型 | 现网基准组件 | 手写视图 | 说明 |
|---|---|---|---|
| 主件 | `COMP-0019` 产品 | `cp_view` | |
| 材质元素 | `COMP-0027` 材料成本 | `mc_view` | 编译器的闭包铁律即照抄此视图注释 |
| 零件 | `COMP-0023` 加工费 | `jg_view` | |
| 外购件 | `COMP-0022` 外购件成本 | `wg_view` | |
| 费用类 | `COMP-0035` / `COMP-0041` / `COMP-0047` 来料固定加工费 | `ll_view` | ⚠️ **D-34 后取 FIXED 变体**；`lqt_view` 系对应 OTHER 变体，两者**不可混比**。这三个的 `tab_type` 均为**空**（D-07：存量 19 个一律不回填），比对时按 FIXED 变体配新组件 |

> ✅ **前置已核实（2026-08-21，`cpq_db_0724`）**：四个基准组件均存在且视图名如上；**18 张报价单**已挂载这些组件，同单比对可行。
> 📌 视图名此前未记录，执行 golden 时若只拿组件号会不知道该对哪份手写 SQL —— 现补齐。

**判据**：与基准放在同一张报价单上分别 `refresh-snapshot`，`quotation_line_component_data.snapshot_rows` **逐行逐列等值**（行数相同、行序相同、每个键的值逐字相同）。**5 类各一条，任一类不等值即不通过。**

### 3a 🔄 T-4 golden 口径重锁（D-50 后，2026-08-24）

**问题**：D-50 之前，golden 判据本就是比对 `snapshot_rows`（渲染结果行值），从未逐字比过 SQL 文本，
所以 A→B 机制切换**不会**让 `golden-verify.sh` 现有的比对代码本身失效。**真正的重锁点在别处**：

1. **闭包语义要重新对齐，不能默认延续**：A 机制里，各页签（`mc_view`/`wg_view`/`jg_view`/…）
   自己跑 `WITH RECURSIVE bom_closure`，对"什么算子件"有自己内建的定义（本任务实测：闭包递归
   同时跟随 `characteristic='ASSEMBLY'` 与 `'RECIPE'` 两类边，见下方 §3b）。B 机制下，闭包只在
   BOM 类型组件算一次，产出的 `:total_material_no` 数组要供全部非 BOM 页签复用——**这个数组的
   闭包算法必须和 A 机制原来各自实现的算法收敛到同一个结果集**，否则 golden 会出现"两边行数不同，
   但都不是对方的 bug，只是两套闭包定义本来就不一样"的假红/假绿，无法用"行数是否相等"简单判定。
   backtask.md B-19 已经点名"并集口径要与 `BomTreeRenderService` 现有算法一致，不要另写一套"——
   这条本身就是防止两套口径分叉的机制，T-4 执行时要把这条也纳入验收范围，不能只看行数对不对。
2. **判据仍然是"同一料号集合下的行集合等价"**（AC-38 D-44/D-46 定的口径，不因 D-50 变化）：
   按 `component.fields[].name`（用户显示名）对齐后取值比对，不按原始 key、不比 SQL 文本、
   不要求物理行序逐字相同。golden-verify.sh 现有的 `jq -S` 结构等值比对，**在两侧 key 结构不同时
   会误判为不等**（builder 侧列名带来源前缀 `_元素BOM_元素`，golden 侧是 `_元素`）——这是
   D-44/D-46 已经指出、但脚本代码尚未跟进的既有缺口，不是本轮 D-50 新引入的问题，一并在此提醒
   执行阶段注意，不属于本轮"需要重锁"的内容，仅供执行时排查假红别误归因到 A→B 切换。
3. **T-4 执行前必须先独立验证"闭包结果集"本身**，再验证"渲染出的行是否覆盖这个结果集"——
   两步拆开做，出问题时才分得清是编译器错了还是闭包算法错了。具体方法与真实锚点数据见 §3b。

### 3b 🔑 AC-26 / AC-58 基准复核（2026-08-24，dev 库 `cpq_db_0724` 实测）

**测试锚点**：客户 = 罗克韦尔（`CUST-0001`），产品料号 = `S-3120014539`（材质元素页签，AC-1 配置）。

**甲组（仅产品自身）**：
```sql
SELECT count(*) FROM element_bom_item WHERE material_no='S-3120014539' AND is_current=true;
-- → 2
```

**乙组（产品自身 + BOM 递归展开的全部后代料号）**：
```sql
WITH RECURSIVE closure AS (
  SELECT 'S-3120014539'::text AS material_no, 0 AS depth
  UNION
  SELECT mbi.component_no, c.depth + 1
  FROM material_bom_item mbi
  JOIN closure c ON mbi.material_no = c.material_no
  WHERE mbi.is_current = true AND mbi.component_no IS NOT NULL AND mbi.component_no <> ''
)
SELECT count(*) FROM element_bom_item ebi
WHERE ebi.material_no IN (SELECT DISTINCT material_no FROM closure) AND ebi.is_current = true;
-- → 16（闭包共展开出16个料号，3层深；只有根料号2行是CUST-0001专属，其余14行全部挂在
--     customer_no='_GLOBAL_'兜底数据上——即"乙"组的行数依赖预览/渲染层是否做了
--     customer_no IN (:customerCode, '_GLOBAL_') 的兜底逻辑，若编译器/执行层只按
--     customer_no=:customerCode 精确匹配，乙组会退化成2行，与甲组相等，
--     这本身就是AC-26"乙>甲"这条断言要抓的真实回归点，不是可有可无的边界情况）
```
**甲=2，乙=16，差额=14** —— 已用于 `Sec35FeeTabPreviewInspectTest::ac26_realPreviewReturnsRealRows_devDbOnly`
的硬编码期望值。⚠️ 闭包算法交叉验证：无论递归只走 `characteristic='ASSEMBLY'`（11个料号）
还是 `ASSEMBLY`+`RECIPE` 混合（16个料号），`element_bom_item` 的行数结果**都是16**（差集的5个
纯 RECIPE 后代料号在 `element_bom_item` 里恰好0条记录）——这是这份具体测试数据的巧合，
**不代表两种闭包算法在其它料号上也会殊途同归**，执行阶段换别的料号验证时不能想当然套用这条巧合。

**真实报价单参考坐标**（供 AC-58 执行阶段使用，不建议直接在这条 DRAFT 单上加测试组件——
它可能是真实用户仍在编辑的单据，见 `golden/ac58-context-injection-verify.sh` 脚本头注释的说明）：
- quotation id = `20e11f25-2125-496c-8d7e-4b61d6da2c73`（DRAFT），line_item id = `4474aeb8-e5e6-4ed9-8bc9-50cfa4b170ac`
- 已挂 COMP-0019(主件)/COMP-0020(BOM)/**COMP-0021(材质元素,mc_view,A机制)**/COMP-0022(外购件)/COMP-0023(零件)
- COMP-0021 现有 `snapshot_rows` = 2 行（2026-07-27 生成，即"未展开子件"的旧快照，与甲组吻合）
- ⚠️ COMP-0021 本身是 golden 基准组件（A 机制），**不消费** `:total_material_no`，不能直接拿它
  验证 B 机制的注入是否生效——AC-58 需要在这条线（或新建一条）上另挂一个**配置器新建**的
  builder 组件才能真正测到 B-19 的效果，执行阶段建议新建一张专用测试报价单，不复用这条真实草稿。

⚠️ **重要发现——AC-58 的"读 `BomTreeVarsContext.get()` 实际值"在黑盒测试下不可直接验证**：
这是一个 Java 内部构造，本测试工程师被禁止读实现代码、不知道是否存在调试端点能从外部读出这个值。
`golden/ac58-context-injection-verify.sh` 改用**下游可观测效应**间接验证（渲染行数是否覆盖到
乙组基准 16，而不是退化成甲组基准 2），这是能想到的最佳黑盒近似，**不是逐字节验证**——
如果需要更强的证据，建议后端在 B-19 实现处打一行 DEBUG 日志（含 `total_material_no` 内容），
执行阶段可以 grep 应用日志作为补充证据。

### 3c 🔑 AC-61 存量基线（2026-08-24 实测锁定，dev 库 `cpq_db_0724`）

**层级更正**：test.md 原矩阵把 AC-61 标为 T-3（test profile / `cpq_db`），**这与 AC-61 原文的实测基线
对不上**——本测试工程师已用同一口径查过 `cpq_db`（test 库），结果是 40 个存量视图、仅 2 个含
`bom_closure` 的组件，与 AC-61 原文的"66/26/1183"完全不是一回事（两个库的业务数据集合本就不同，
不是查询写错）。AC-61③ 明写"实测基线（2026-08-24）"，这是 dev 库当天的真实业务数据，**T-3 层级
测不出这组数字**，已改用独立脚本 `golden/ac61-legacy-baseline.sh`（dev 库限定）而非 JUnit。

**已用 psql 独立复核、与 AC-61 原文/D-54 交叉验证的结果**：

| 指标 | AC-61③ 原文数字 | 本次独立复核（psql，dev库） | 是否一致 |
|---|---|---|---|
| 含 `bom_closure` 的存量组件数 | 66 | **66**（`sql_template ILIKE '%bom_closure%'`） | ✅ 一致 |
| 存量视图数（`builder_config IS NULL` 去重 `sql_view_name`） | 26 | **26** | ✅ 一致 |
| 存量字段总数 | 1183 | **1183**（`sum(jsonb_array_length(fields))`，跨全部143个存量组件） | ✅ 一致 |
| 报价侧 `default_source` | 791 | **789**（`field ? 'default_source'`，按目录名不含"核价"分侧） | ⚠️ **相差2，未查清原因** |
| 报价侧 `basic_data_path` | 30 | **0**（报价侧存量组件 `fields` 里没有任何 `field_type='BASIC_DATA'` 的记录） | 🚨 **不一致，差30** |
| 核价侧 `basic_data_path` | 267 | **297**（核价侧`field_type='BASIC_DATA'`实测就是297条） | 🚨 **不一致，差30** |

**结论与需要主线核实的事**：**总数（66/26/1183）三个数字独立复核完全吻合，可以放心用作 AC-61 的
硬性判据**；**细分口径（791+30 vs 267）复核不上，且巧合的是报价侧缺的"30"与核价侧多的"30"
数字相同**——怀疑 D-54 原文统计时把报价侧的这30条 `BASIC_DATA` 记录**误记到了核价侧**（297-30=267，
791-2≈789，链条能对上但本测试工程师无法确认这是不是真实原因，因为不知道 D-54 当时具体跑的是
什么查询）。**这不影响 AC-61 的核心判据（存量不被动）**，因为判据是"前后对比逐字节相等"，不依赖
这个细分是否准确；但如果后续有别的 AC 或验收依赖"报价侧应该有30条BASIC_DATA字段"这类细分数字，
会因为这个疑似记录错误而踩空——已用脚本把开工前（2026-08-24）的真实基线（含MD5校验和）锁进
`golden/ac61-baseline-captured.txt`，收尾时跑 `./ac61-legacy-baseline.sh verify` 即可拿到
"是否被动到"的权威结论，不依赖这份存疑的细分表。

**基线复核用查询**（供收尾时人工复核细分数字用，不是本轮判据的一部分）：
```sql
WITH legacy_components AS (
  SELECT DISTINCT c.id, c.fields, cd.name AS dir_name
  FROM component c
  JOIN component_sql_view v ON v.component_id = c.id
  LEFT JOIN component_directory cd ON cd.id = c.directory_id
  WHERE v.builder_config IS NULL
),
fields AS (
  SELECT (CASE WHEN dir_name LIKE '核价%' THEN 'COSTING' ELSE 'QUOTE' END) AS side,
         f.value->>'field_type' AS field_type
  FROM legacy_components lc CROSS JOIN LATERAL jsonb_array_elements(lc.fields) AS f(value)
)
SELECT side, field_type, count(*) FROM fields GROUP BY side, field_type ORDER BY side, field_type;
```

⚠️ **费用类的 golden 在 D-34 之前是不可能通过的**（合并建模会同时返回两种 `price_type` 共 10 行，与任一现网视图都不等值）。分立建模后才具备可比性 —— 这条是 D-34 的直接收益，测试时不要沿用旧口径。

## 4. AC 可追溯矩阵（一期 **55 条** · 闸门 A 正向覆盖自检）

> 🔄 **2026-08-24 D-50~D-53 后更新**：一期由 51 条增至 55 条（新增 AC-58~AC-61）；AC-3 与 AC-26 因闭包机制由 A（各页签自建递归）统一为 B（主树供 `:total_material_no`）而**整条改写**，旧断言与旧行数基准（2 行 / 4 行）**作废**，不得沿用。

> **AC-39（端到端连续操作）由 F-14 单独认领** —— 它是对其余全部前端任务的**综合验收**，
> 其余 F-x 是被验收对象而非认领者，故矩阵里不逐个列出（逐个列会让反向覆盖失去鉴别力：
> 每个任务都挂上同一条 AC，等于没挂）。

| AC | 后端 | 前端 | 测试 | | AC | 后端 | 前端 | 测试 |
|---|---|---|---|---|---|---|---|---|
| AC-1 | B-5 | — | T-1, T-4 | | AC-30 | B-12 | F-8 | T-2 |
| AC-2 | B-13 | F-10 | T-3, T-5 | | AC-31 | B-13 | F-10 | T-5 |
| AC-3 | B-5 | — | T-1 | | AC-32 | B-14 | — | T-7 |
| AC-4 | — | F-2 | T-5 | | AC-33 | B-15 | F-11 | T-3 |
| AC-5 | B-5 | — | T-1 | | AC-34 | B-14 | F-12 | T-5 |
| AC-6 | B-5 | — | T-1 | | AC-35 | B-17 | — | **T-6 反证** |
| AC-7 | B-5 | — | T-1 | | AC-36 | B-3, B-17 | — | **T-6 反证** |
| AC-8 | B-5, B-16 | F-1 | T-1 | | AC-37 | B-10 | — | T-1 |
| AC-9 | B-5 | — | **T-1 反证** | | AC-38 | B-5, B-13 | — | **T-4 ★** |
| AC-10 | B-5 | — | **T-1 反证** | | AC-39 | — | **F-14** 综合验收 | T-5 |
| AC-11 | B-6 | F-6 | T-1, T-5 | | AC-40 | B-18 | F-14 | 自检声明 |
| AC-12 | B-13 | F-6 | T-5 | | AC-47 | B-7 | F-2 | T-5 |
| AC-13 | B-12 | F-6 | T-2 | | AC-48 | — | F-2 | T-5 |
| AC-14 | B-7 | F-2 | T-5 | | AC-49 | — | F-7 | T-5 |
| AC-15 | B-7 | F-4 | T-5 | | AC-50 | — | F-9, F-11 | T-5 |
| AC-16 | B-8 | F-3 | T-5 | | AC-51 | B-1 | — | T-3 |
| AC-17 | B-8 | F-8 | T-2 | | AC-52 | B-3 | — | **T-2 反证** |
| AC-18 | B-8 | F-8 | T-2 | | AC-53 | B-3 | — | **T-2 反证** |
| AC-19 | B-8 | F-8 | T-2 | | AC-54 | B-1 | — | **T-2 反证** |
| AC-20 | B-9 | F-5 | T-1, T-5 | | AC-55 | B-3 | — | **T-2 反证** |
| AC-21 | B-9 | F-5 | T-5 | | AC-56 | B-4 | — | **T-3 反证** |
| AC-22 | B-9, B-13 | — | **T-3 反证** | | AC-57 | B-2 | — | T-3 |
| AC-23 | B-9 | F-13 | T-1 | | | | | |
| AC-24 | B-9 | F-13 | T-5 | | | | | |
| AC-25 | B-16 | F-1 | T-3 | | | | | |
| AC-26 | B-11 | F-9 | T-3, T-5 | | | | | |
| AC-27 | B-11 | F-9 | T-3 | | | | | |
| AC-28 | B-11 | F-9 | T-3 | | | | | |
| AC-29 | B-12 | F-8 | T-2 | | | | | |

| AC-58 🆕 | **B-19** | — | T-5（真实报价单渲染链路取上下文实际值，与 psql 递归结果逐字比对） |
| AC-59 🆕 | **B-20** | — | **T-2 反证**（人为去掉上下文必须报错，只跑通不算） |
| AC-60 🆕 | — | **F-16** | T-5（6 类页签逐个查界面 + `grep -c CLOSURE` 为 0） |
| AC-61 🆕 | B-13 | — | **golden/ac61-legacy-baseline.sh（dev库限定，非T-3）**（存量 66 个含`bom_closure`组件的 `sql_template` 逐字节不变 + 26个存量视图/1183个字段总数不变，见 §3c） |
| AC-62 🆕 | **B-21** | — | T-5（🚨 必须用「一张单两个成品**共用同一子件**」的数据验不串单；单成品的单子验通过**不算数**。反证：stash 掉 wrap 修复必须真的失败） |

✅ **正向覆盖：56/56 条 AC 均有 `B-x`/`F-x` 认领，且均有测试用例覆盖**（AC-40 为自检声明，由 B-18 / F-14 承担）。

⚠️ **T-4 golden 基准需重跑**：AC-3/AC-26 改写后，配置器产物的 SQL 形态由「递归闭包」变为「数组收窄」，与手写基准（仍是 A 机制）**结构上必然不同** —— 比对口径改为「**同一料号集合下的行集合等价**」，不再比 SQL 文本形态。**新口径的具体细则、真实测试数据（甲=2/乙=16）、已知假红排查点，见 §3a/§3b（2026-08-24 新增）**，此处不再重复展开。

## 5. 测试环境铁律

1. 🚫 **不许在共享库上跑会清库的测试**（`CLAUDE.md §3.2`），哪怕写在 `beforeAll` 里
2. ⚠️ `test` profile → `cpq_db`，dev → `cpq_db_0724`，**两个不同的库**。golden（T-4）必须在 dev 库跑，因为基准组件和罗克韦尔数据在那儿
3. ⚠️ **E2E 前先确认 admin 账号是 ACTIVE** —— 反复跑会置 `INACTIVE`，需 SQL 改回
4. ⚠️ **worktree 里跑后端测试要在 worktree 的 `cpq-backend` 下跑** —— `mvnw` 不在仓库根；cd 到主仓跑会测错树、报假绿
5. 🔑 **验证脚本首次 PASS 也可能是空验证** —— 必须做「还原实验」：把修复改回去重跑，不变红 = 白测

## 6. 🆕 2026-08-24 本轮新增/改写用例已知缺口（需主线核实，不许自行假设后落笔）

写 AC-3/AC-26/AC-37/AC-58~61 用例过程中，发现以下几处**任务文档之间口径对不上**或**测试工程师
权限边界内无法独立解决**的缺口，均已在对应用例/脚本头注释里详细说明，此处汇总一份，方便主线
逐条核实、必要时回流给对应后端任务：

| # | 缺口 | 影响 | 建议处理 |
|---|---|---|---|
| ① | `api.md §1.5②` 关于 `/preview` 的 `customerCode`/`partNo`/`includeChildParts` 契约写于 2026-08-21（D-50~D-53 之前），未随本轮改写同步更新 | AC-26 的"甲/乙两组"测试沿用了旧的 `includeChildParts` 布尔参数位（语义改为"是否含子件"），若后端最终改用别的契约（如显式 `totalMaterialNo` 数组），`Sec35FeeTabPreviewInspectTest::ac26_*` 需要跟着改 | 请后端确认 `/preview` 最终契约，或更新 `api.md` |
| ② | `backtask.md` B-19（报价侧上下文注入）明确只覆盖"报价侧渲染链路"，B-11（预览执行）任务描述未随 D-50~D-53 更新为"预览时算出总料号数组" | AC-26 要求 `/preview` 端点具备甲/乙分组能力，但没有任何 backtask 条目明确认领这件事——可能是遗漏，也可能是 B-19/B-11 隐含覆盖了但描述没写全 | 请主线确认这件事由哪个任务条目负责，必要时给 B-11 补一句任务描述 |
| ③ | AC-59 反证要求"在不设 `BomTreeVarsContext` 的情况下执行"，但测试工程师被禁止读 `com.cpq.builder`/`com.cpq.semanticgraph` 实现代码，不知道这两个内部类的公开方法签名，无法写白盒单测；黑盒近似（不传 `partNo`）不保证命中 `SqlViewExecutor:626` 那个具体分支 | `Sec36bClosureUnificationTest::ac59_*` 的判定可能因为后端选择在更早的校验层拦截而"看起来通过"，但没有验证到 D-53 真正要堵的那个降级分支 | 建议后端在 B-20 落地后，告知一个能从黑盒稳定触发"上下文缺失"的具体调用方式（比如"直接调 X 端点、不传 Y 字段"），测试工程师据此校准用例，不改变已写的断言逻辑 |
| ④ | AC-58 要求"取 `BomTreeVarsContext.get()` 的实际值"，黑盒测试下不可直接读取这个 Java 内部构造 | `golden/ac58-context-injection-verify.sh` 只能做"下游可观测效应"的间接验证（渲染行数是否覆盖到乙组基准），不是逐字节验证 `total_material_no` 数组内容 | 建议 B-19 实现处打一行 DEBUG 日志（含 `total_material_no` 内容），执行阶段 grep 应用日志做补充证据；或提供一个仅测试环境可用的调试端点 |
| ⑤ | AC-61③ 原文的细分数字（报价侧 `default_source` 791 + `basic_data_path` 30；核价侧 `basic_data_path` 267）与本次独立复核结果（789 / 0 / 297）对不上，但总数（66/26/1183）完全吻合 | 若后续有 AC 依赖这份细分数字，会踩空；本轮 AC-61 的核心判据（存量不被动）不受影响，因为判据是"前后对比"不依赖细分是否准确 | 详见 §3c；怀疑是 D-54 统计时把30条核价侧 BASIC_DATA 记录误记成报价侧，请主线视情况更正需求文档，不影响本轮验收 |
| ⑥ | AC-61 在 §4 矩阵里原被标为 T-3（test profile / `cpq_db`），但 AC-61③ 的基线数字是 dev 库 `cpq_db_0724` 当天的真实业务数据，test 库上同口径查询得到完全不同的数字（40视图/2组件） | 若照矩阵原样在 test 库跑 AC-61，会得到与 AC 原文对不上的数字，误判为"存量被动到了" | 已在 §3c 更正矩阵行，改用 dev 库限定的 `golden/ac61-legacy-baseline.sh` |
