# task-260902 · 后端任务分解

> 后端**只按本文件做**。接口契约以 `api.md` 为准（前后端唯一协调物）。
> AC 原文在 `需求文档.md`，本文件**只标编号不复制原文**（复制会双写漂移）。
> 起点：`master`（`task-260901` 已于 `f5adfccc` 合入，材质配置层可用）。

---

## A · DTO 与请求解析

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-3, AC-11 | `PartRequest` 三层化：新增 `partType`(PART/OUTSOURCED) / `spec` / `dimension` / `materials[]` / `outsourcedPartNo`；`partMode` 值域加 `"new"`（`"custom"` 继续接受并等价映射）。<br>🚫 **`recipeCode` / `elements` / `configNo` 三个老字段保留并标 `@Deprecated`，不删** —— 解析优先级 `materials` 非空则用之，否则回落单材质（`ratio` 默认 `100`）。⚠️ 该回落**不是为存量数据**（实测 `sel_part_signature` 0 行、`material_recipe_id` 1890 行全 NULL（取数 2026-09-02，仍为 0），custom 路径从未跑通），而是为并发分支与 `task-260901` 刚落地的 `configNo` 单值语义留安全边。 |
| **B-2** | AC-1, AC-2 | `ConfigureProductRequest` 加 `customerProductNo`(必填) / `customerProductName`(选填)；新增端点 `GET /quotations/configure/check-product-no`（见 `api.md` §2.1）。<br>提交时**后端硬拦**：编号已存在于 `material_customer_map.customer_product_no` → 409 `CUSTOMER_PRODUCT_NO_TAKEN`，响应带 `hfPartNo` 与 `createdAt` 供前端展示。**前端拦截是体验，后端拦是正确性**，两处都要有。 |

## B · 落库（三层模型的核心）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-3** | AC-3, AC-13 | `insertMaterialBomItemV6` 从**写 1 行**改为**写 N 行**（每材质一行，`seq_no` 递增）：`component_no`=材质码、`component_usage_type`=材质名、**`material_ratio`=占比**。<br>📌 `material_ratio` 是 **V365 专为「材质占比」加的既有列**（`numeric(24,12)`，V386 调过精度，`MaterialBomMergeHandler` 已在用，存量 11131 行仅 3 行非空）—— **复用，不新增列**。<br>⚠️ AC-13 要求单材质（占比 100）时行为与现状等价，**这是不回归的判据**。 |
| **B-4** | AC-3 | `insertElementBomV6` 按材质分组：现状 groupKey `(system_type, customer_no, material_no, material_part_no)` **天然支持多组**（`material_part_no` 已是键的一部分），每个材质一组元素行。自定义含量时用 `materials[i].elements`，否则展开该 `configNo` 的元素。 |
| **B-5** | AC-7, AC-8, AC-9, AC-10, AC-19, AC-20 | `SalesFingerprintCalculator` 升 `STRUCTURE_VERSION` `v1`→`v2`，`buildSalesConfigContext` 按 `api.md` §4 装配新 token：新增 `PART=`(品名/规格/尺寸)、`WEIGHT=`(总重)，`MAT=` 改为「材质码:占比(元素码:含量,…)」按材质码排序。<br>🚫 **`MAT=` 里不放 `configNo`** —— 须把配置展开成元素含量（AC-10 的依据：含量相同即同一种材料）。<br>🚫 **`PRC=` 的 `sorted().join(",")` 一行不改**（A0 裁决：工序顺序不进指纹）。<br>🚨 **绝不可加 `distinct()`** —— `sort` 不去重正是 AC-20 的依据（「焊两次」≠「焊一次」）。改这段时请连带读 AC-19/AC-20。 |
| **B-6** | AC-19 | `insertProcessSimpleUnitPriceV6` 落 `unit_price` 时，`seq_no` **按 `processNos` 数组的原始顺序**赋值，不排序。<br>⚠️ 这与 B-5 的「指纹排序」**不矛盾**：指纹不认顺序（换序复用同一料号），但落库与显示认顺序。AC-19 同时断言了这两侧，改动时不要为了「统一」把其中一边也排序。 |
| **B-7** | AC-5, AC-16 | 外购件：新增端点 `GET /quotations/configure/outsourced-parts`（`WHERE material_master.material_type='外购件'`，见 `api.md` §2.2）；落库时 `material_bom_item.characteristic='OUTSOURCED'`。<br>📌 实测该 `characteristic` 值**当前 0 行**（全表 RECIPE 11095 / ASSEMBLY 49）—— 这是**从未落地过的路径**，不是「已有但没接」，落库后须实查确认。 |
| **B-8** | AC-12, AC-12b | 选配成功后写 **`sel_product_no`**（B-16 新建的表）：`customer_no` / `customer_product_no` / `customer_product_name` / `quote_part_no` = 铸出**或复用**的销售料号 / `quotation_id`。<br>🚫 **不写 `material_customer_map`** —— 方案甲的核心就是不动 mcm，避开 `upsertQuote` 的 ON CONFLICT target 与跨客户串号防线（详见 B-16）。<br>⚠️ 复用场景（AC-7 命中指纹）**同样要写一行** `sel_product_no`，这正是 AC-12b 要的「一料号多编号」。<br>📌 **这修掉一个历史问题**：现状选配料号在 mcm 里 `customer_product_no` 为空，导致「从已有产品添加」找不回它，靠 `ExistingProductService` 一句 `OR EXISTS (SELECT 1 FROM sel_part_signature …)` 兜着 —— 新表落地后该兜底可退役（见 B-16b，**退不退由主线定，不要顺手删**）。 |
| **B-9** | AC-5 | **`material_type` 归位为料号类型**（闸门 A0 裁决）：`insertMaterialMasterV6` 第 2 参从 `recipe.symbol` 改为字面量 `'零件'`。<br>🚨 **改完必须回归验证视图**：`v_composite_child_materials` 的 `material_name` 列是 `COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name)` —— `mm.material_type` 是**第二兜底**。改后若 `component_usage_type` 为空，材质名显示会从 `AgNi10` 变成 `零件`。B-3 已保证 `component_usage_type` 写材质名，但**必须实查一条选配料号确认渲染无变化**，不能只看代码推断。<br>📌 背景：这条路径**至今没污染过数据**，因为 262 条材质全 `locked` ⇒ `validateCustomPart` 必抛，走不到写库行（实测 `material_recipe_id` 1890 行全 NULL（取数 2026-09-02，仍为 0））。`task-260901` 打开 `allow_custom_content` 后它才会第一次真实执行。 |
| **B-10** | AC-4, AC-6, AC-14, AC-15a, AC-15b, AC-17 | 校验规则 8 条 + 错误码（`api.md` §1.2 表）。<br>🚨 **占比合计判等必须用 `BigDecimal.compareTo`**，不得用 `double` / `equals`。<br>🚨 **AC-15b 是证伪对照组**：`0.000000000001 + 99.999999999998 + 0.000000000001` 在浮点下 = `99.99999999999999`，浮点实现会**错误拒绝这个合法输入**。⚠️ 注意 AC-15a 那组（`33.333333333333×2 + 33.333333333334`）在浮点下**恰好等于 100**，**它拦不住浮点实现**，两条都要跑才有分辨力。<br>`MATERIAL_RATIO_SUM_INVALID` 的响应**必须带 `actualSum` 实际值**（AC-4 断言提示写出「90%」而非形容词）。 |
| **B-11** | AC-7 | 响应加 `reusedProductInfo`（命中复用时带出销售产品信息，见 `api.md` §1.3）与 `structureVersion`。 |

## C · 既有测试改造

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-12** | 回归保障 | 4 个单测硬编码了示例工序号 `MRO-AS-0001`，在 `cpq_db_0724` 上必失败（`组合工艺未找到`）：<br>`ConfigureProductServiceTest:426/514` · `ConfigureProductServiceB2LedgerTest:101/356/389` · `ConfigureProductServiceLookupFingerprintTest:217/236` · `CompositeProcessServiceB6CandidatesTest:34/42`<br>✅ **用户已确认工序是业务在「主数据维护→工序」页自维护的开放主数据**，`MRO-*` 那 26 条是 `V4` 带的通用示例（CNC加工/包装入库…），与触点业务不符，**不搬入**。<br>⇒ 改用现存 `Z100`/`Z101`，或让测试自建夹具不依赖库内现存数据。🚫 **不要写迁移去补种子**。<br>⚠️ `CompositeProcessServiceB6CandidatesTest` 还断言 `process_category='ASSEMBLY'`，而库里 `Z100/Z101` 的分类是**中文「组装」** —— 同一处要一并改。 |
| **B-13** | AC-13, AC-19, AC-20 | 回归保障（**不改功能，只加测试**）：① 单材质（占比 100）落库结果与改造前逐字段一致；② 工序换序**复用同一料号且 `unit_price.seq_no` 保持第一次的顺序**（A 轮初稿写「各按自己顺序」，逻辑不可能成立，已按裁决改）；③ 工序重复次数不同必铸新号。 |

## D · A 轮评审新增项（`评审报告-A轮.md`）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-14** | AC-3 | 🔴 **P1-10 交付缺口**：`insertMaterialMasterV6` 的 INSERT 列只有 5 个（`material_no / material_type / unit_weight / material_recipe_id / config_fingerprint`），**没有 `material_name` / `specification` / `dimension`** ⇒ AC-3 断言① 在 A 轮**无人认领**。<br>本项扩列写入这三列。⚠️ 现有 `ON CONFLICT DO NOTHING` 在复用场景不会更新，需确认是否改 `DO UPDATE`（改则要评估对既有导入料号的影响）。 |
| **B-15** | AC-3, 回归 | 🔴 **P0-5 读出侧静默吞组**：`v_composite_child_elements` 的相关子查询<br>`AND ebi.characteristic = (SELECT max(ebi2.characteristic) … WHERE system_type/customer_no/material_no 相同)` **缺 `material_part_no` 维度**。<br>而 `characteristic` 在该表是 `VersionedV6Writer` 的**版本列**（实测取值 2000~2018），**按 groupKey 独立递增** ⇒ 材质 A 升到 2001、材质 B 还在 2000 时（只改一个材质重新提交，常见操作），`max()` 取 2001 ⇒ **材质 B 的元素整组从页签消失，无任何报错**。<br>⇒ 本项：迁移改视图，子查询补 `material_part_no` 维度，并把 `material_part_no` **暴露为视图列**供前端分组（现状两组元素的 `seq_no` 都从 1 开始，前端拿不到归属标签）。<br>📌 存量扫描 0 组（`HAVING count(DISTINCT material_part_no)>1 AND count(DISTINCT characteristic)>1`）⇒ **潜伏缺陷，本任务第一次给它通电**。 |
| **B-16** | AC-12, AC-12b | 🔵 **新建 `sel_product_no` 映射表**（用户裁决方案甲，2026-09-02）—— **不动 `material_customer_map`，零红线**。<br><br>**为什么不放宽 `uq_mcm_quote_no`**（影响面调查结论，三条依赖各自独立成立）：<br>① `upsertQuote` 的 SQL 是 `ON CONFLICT (material_no) WHERE system_type='QUOTE' DO UPDATE … WHERE mcm.customer_no = EXCLUDED.customer_no` —— **该索引就是 ON CONFLICT target**，PG 要求 target 必须有对应唯一索引，DROP 掉**语句直接报错**，报价导入 Q02 全线挂；<br>② 末行那个客户守卫是**跨客户串号检测**的实现：跨客户命中时影响 0 行，调用方按返回值 0 转 `recordError("报价料号跨客户串号")`（`Q07:87` / `Q09:112`）。`SemanticCompiler:451` 注释：「是真实的跨客户串号风险，不是理论问题，RECORD.md 明确记录过历史教训」（森萨塔事故）；<br>③ **4 个组件 SQL 视图 / 19 条记录**的 JOIN 是 `ON mcm.material_no=mm.material_no AND mcm.customer_no=:customerCode`，**不含 `customer_product_no`** ⇒ 一料号两编号会返 2 行 → **重复渲染**（AP-22 族），其中 `builder_ac14e1fd3f10` 还是内连接。<br><br>**本项实现**：<br>```sql<br>CREATE TABLE sel_product_no (<br>  id uuid PRIMARY KEY,<br>  customer_no varchar(20) NOT NULL,<br>  customer_product_no varchar(100) NOT NULL,<br>  customer_product_name varchar(200),<br>  quote_part_no varchar(50) NOT NULL,   -- 销售料号（多对一的「一」侧）<br>  quotation_id uuid,                    -- 来源报价单，可空<br>  created_at/updated_at/created_by/updated_by<br>);<br>CREATE UNIQUE INDEX uq_spn_cust_prod ON sel_product_no(customer_no, customer_product_no);<br>CREATE INDEX idx_spn_part_no ON sel_product_no(quote_part_no);<br>```<br>🔑 **`(customer_no, customer_product_no)` 唯一** ⇒ 满足 AC-2 编号不重；**`quote_part_no` 不唯一** ⇒ 满足 AC-12b 一料号多编号。<br>⚠️ **纯新增表 + 新增索引，不 DROP 任何东西** ⇒ 不属 §3.2 红线，无需单独批准。 |
| **B-16b** | AC-12 | 「从产品库添加」列表**并两处数据源**：既有 `material_customer_map`（导入来的产品）+ 新增 `sel_product_no`（选配来的产品）。<br>⚠️ **`ExistingProductService` 的 `source` 语义随之简化**：现状 `CASE WHEN mcm.customer_product_no IS NULL THEN 'CONFIGURED' ELSE 'EXISTING' END`（评审 P2-17 指出 B-8 会让它静默翻转）—— 方案甲下**改为按来源表判定**：来自 `sel_product_no` → `CONFIGURED`，来自 `mcm` → `EXISTING`，语义更准且不再依赖「编号是否为空」这个易变判据。<br>📌 **连带好处**：`ExistingProductService` 里那句 `OR EXISTS (SELECT 1 FROM sel_part_signature …)` 兜底可以退役（它本就是为「选配料号在 mcm 里编号为空」打的补丁）。**退役与否由主线在实现后裁定**，不要顺手删。 |
| **B-17** | AC-3, AC-7 | 🔴 **P0-4 漏写入点（两处）**：<br>**① `material_bom_item` 的第二个写入点** `backfillV6MaterialsForCustomer`（`:867-880`）在 `existing` 分支无条件调用，而**指纹命中复用时前端正是把 `partMode` 切成 `existing`** ⇒ AC-7 必然走到它。该 SQL 按 `material_recipe_id` 重建且**硬编码 `seq_no=1` 只产出 1 行** ⇒ 多材质料号跨客户复用时**材质从 N 个塌回 1 个**。<br>⇒ 改为**按 `material_bom_item` 整组复制**，不再按 `material_recipe_id` 重建。<br>**② `material_type` 的第二个写入点** `:1276 insertMaterialMasterV6(parentHfPartNo, "COMPOSITE", …)` —— B-9 只改了 `:388`。⇒ §4.3 的「一列两义」其实是**一列三义**（料号类型 / 材质符号 / 产品结构类型）。<br>本项须为 COMPOSITE 父料号定一个合法的 `material_type` 值并同步 `v_composite_child_materials` 第二 UNION 分支的 `COALESCE(mm.material_type, mm.material_name)` 回归。 |
| **B-18** | AC-3, AC-13 | 🔵 **用户裁决落地**：`material_master.material_recipe_id` **不再作为料件材质的判据**。<br>多材质时该列写 NULL，材质权威改为 `material_bom_item` 的 N 行。<br>⇒ 连带：① B-17① 的 backfill 改法即由此而来；② `test.md` 对该列的非空断言**删除**；③ 现有 `idx_material_master_recipe` 索引保留不动（不影响正确性）。 |
| **B-19** | 回归 | 🟠 **P1-9 N+1 硬指标**（`backend.md`：循环体里出现查询 = 违规）。本任务把工序变成**允许重复的无界有序列表**、材质变成 1..N，**放大了 N**。三处必须批量化：<br>① `insertProcessSimpleUnitPriceV6`（`:1041-1048`）循环内 `SELECT … FROM process_master WHERE process_no=:c` → 改一次 `IN (…)` + Map；<br>② `resolveProcessCodes` 循环内 `SELECT 1 FROM process_master WHERE process_no=:pn` → 同上；<br>③ `insertQuotationLineProcesses`（`:1159-1172`）逐条 INSERT → 改批量。<br>④ **B-4 的新增风险**：🚫 **不许 for 循环调单组 `writeVersionedGroup`**（每组 ≈5 次 DB 往返），必须走已存在的多组批量重载 `VersionedV6Writer.writeVersionedGroups(…)`（`VersionedV6Writer.java:213/230`）。 |
| **B-20** | AC-21, AC-22 | 🟠 **P1-11 S-5 正向路径**：自定义含量的**正向**落库与指纹（A 轮只有 AC-6 验了反向禁用）。<br>① 自定义含量落 `element_bom_item`，**不回流** `material_recipe_config` / `material_recipe_element`；<br>② 自定义含量参与指纹时**与标准配方走同一套展开逻辑** ⇒ 内容逐字相同则复用同一料号（AC-22③ 是 D-5「按含量内容判同」的完整验证）。 |
| **B-21** | §4.3 | 🟠 **P1-7 指纹分隔符**：`PART=` 改**长度前缀编码**（`api.md §4.3`），🚫 不得用 `/` 分隔（实查 74 条材质符号含 `/`，品名含 `/` 的料号也真实存在）；并捕获 `assertNoDelimiter` 抛出的 `IllegalArgumentException`，转成 400 `PART_TEXT_INVALID_CHAR`，不要漏成 500。 |
| **B-22** | AC-11, AC-19 | 🟡 **P2-14 工序顺序无持久化依据**：实查 `quotation_line_process` 只有 4 列 —— `id \| line_item_id \| process_id \| process_no`，**没有任何顺序列**。而 AC-11 断言「工序顺序回填」、AC-19④ 要显示第一次的顺序，`insertQuotationLineProcesses` 是逐条 INSERT，读回不带 `ORDER BY` 时顺序**不保证**（堆表，UPDATE 后会乱）。<br>⇒ 迁移加 `seq_no` 列 + 写入时按 `processNos` 数组下标赋值 + 所有读出点补 `ORDER BY seq_no`。<br>🚨 **不加这一列，AC-11 会「今天绿、下周红」** —— 这类偶发失败最难归因。 |
| **B-23** | AC-2 | 🟡 **P2-16 并发下 409 退化成 500**：B-2 是 SELECT-then-INSERT，挡不住竞态。现网存在 `uq_mcm_quote_cust_prod ON (system_type, customer_no, customer_product_no) WHERE system_type='QUOTE' AND customer_product_no IS NOT NULL` ⇒ 两会话同时提交同一编号，后者撞索引抛 `ConstraintViolationException` → **500，而不是约定的 409**。<br>⇒ 捕获该唯一约束异常并映射成**同一个** 409 `CUSTOMER_PRODUCT_NO_TAKEN`。<br>📌 **检查防不住竞态，索引才能** —— 前置 SELECT 保留作快速反馈，但正确性由索引 + 异常映射保证。 |
| **B-24** | AC-23 | 🟡 **P2-18 超长输入**：`material_master` 的 `material_name`/`specification`/`dimension` **实查均为 `varchar(100)`**。品名等超 100 字符时**必须 400 `PART_TEXT_TOO_LONG`**，🚫 **不得落库截断**（截断会让指纹与实际内容不一致 ⇒ 两个不同产品指纹相同 ⇒ 静默错价，与 P1-7 同型）。 |

---

## 双向覆盖检查

**正向 —— 每条 AC 至少被一个 B-x 或 F-x 覆盖**：

| AC | 后端 | 前端 |
|---|---|---|
| AC-1 / AC-2 | B-2 | F-1 |
| AC-3 | B-1, B-3, B-4 | F-3, F-4 |
| AC-4 | B-10 | F-5 |
| AC-5 | B-7, B-10 | F-7 |
| AC-6 | B-10 | F-6 |
| AC-7~AC-10 | B-5, B-11 | F-9 |
| AC-11 | B-1 | F-8, F-10 |
| AC-12 | B-8 | — |
| AC-13 | B-3, B-13 | F-4 |
| AC-14 | B-10 | F-4 |
| AC-15a / AC-15b | B-10 | F-5 |
| AC-16 | B-7 | F-7 |
| AC-17 | B-10 | F-6 |
| AC-18 / AC-18b | —（复用既有端点） | F-6 |
| AC-19 / AC-20 | B-5, B-6, B-13 | F-11 |
| **AC-12b** 🆕 | **B-16**（放宽索引）, B-8 | — |
| **AC-21 / AC-22** 🆕 | **B-20**, B-5 | F-6 |
| **AC-3 断言①** 🆕 | **B-14**（`material_master` 扩列） | F-4 |
| **AC-5b** 🆕 | B-10 | —（后端正确性，前端对应 AC-18b 灰显） |
| **AC-23** 🆕 超长输入 | **B-24** | F-4（maxLength） |
| **AC-24** 🆕 并发 | **B-23** | — |
| AC-11 补漏 | B-1, **B-22**（工序顺序列） | F-8, F-10, **F-2** |
| AC-15a 补漏 | B-10 | F-5, **F-12** |

**反向 —— 每个 F-x 也要指回 AC**（`task-docs.md §7` 自检③ 要求双向，A 轮只做了 B-x 侧，评审 P2-19-3）：
F-1→AC-1/2 · F-2→AC-11 · F-3→AC-5 · F-4→AC-3/13/14/23 · F-5→AC-4/15a/15b · F-6→AC-6/17/18/18b/21 · F-7→AC-5/16 · F-8→AC-11 · F-9→AC-7~10 · F-10→AC-11 · F-11→AC-19/20 · F-12→AC-15a/3。**无指不回 AC 的条目。**

**反向 —— 每个 B-x 都指回 AC**：B-1→AC-3/11 · B-2→AC-1/2 · B-3→AC-3/13 · B-4→AC-3 · B-5→AC-7~10/19/20 · B-6→AC-19 · B-7→AC-5/16 · B-8→AC-12/12b · B-9→AC-5 · B-10→AC-4/6/14/15/17 · B-11→AC-7 · B-12→回归 · B-13→AC-13/19/20 · **B-14→AC-3** · **B-15→AC-3+回归** · **B-16→AC-12b** · **B-17→AC-3/7** · **B-18→AC-3/13** · **B-19→回归(N+1)** · **B-20→AC-21/22** · **B-21→§4.3**。**无指不回 AC 的条目。**

---

## 🚦 开工前置

| 前置 | 状态 |
|---|---|
| **B-16 新建 `sel_product_no` 表** | ✅ **无需批准** —— 方案甲是**纯新增表 + 新增索引，不 DROP 任何东西**，不属 §3.2 红线。<br>📌 原方案（放宽 `uq_mcm_quote_no`）已因影响面调查**否决**：该索引同时是 `upsertQuote` 的 ON CONFLICT target 与跨客户串号防线，且 4 个组件视图会产生重复行 |
| B-15 视图迁移 | ✅ 无需单独批准（`CREATE OR REPLACE VIEW`，非破坏性） |
| B-22 `quotation_line_process` 加 `seq_no` | ✅ 无需单独批准（`ALTER TABLE ADD COLUMN`，可空，非破坏性） |
| `TP10`/`TP20` 两行污染数据如何处理 | ⏸️ **待用户裁决**（评审 P0-6：它们是立项当天 11:54 写入的，非遗留数据） |

> ✅ **迁移全部为「新增」类**（建表 / 加列 / `CREATE OR REPLACE VIEW`），**本任务无 §3.2 红线操作**，可直接开工。

---

## 🚫 明确不做（防超范围）

| 不做 | 理由 |
|---|---|
| 删 `PartRequest.recipeCode` / `elements` / `configNo` | 加法式扩展，老字段留着标废弃。删除属破坏性变更，不在本期 |
| 改 `PRC=` 的排序逻辑 | A0 裁决保持现状。**也不许加 `distinct()`** |
| 补工序主数据种子迁移 | 用户确认工序由业务自维护（见 B-12） |
| 治理 `material_type` 的导入侧与存量 | 本期只收敛**选配写入侧**（B-9），导入侧 1848 条「零件」不动（取数 2026-09-02） |
| 删 `ExistingProductService` 的 `OR EXISTS` 兜底 | B-8 落地后只做**评估**，删不删由主线定 |
| `composite_process_def` 表 0 行、`mat_composite_process` 缺表 ERROR | 既有问题，与本任务无关，不顺手修 |
