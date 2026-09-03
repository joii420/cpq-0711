# task-260902 立项六件套 · 独立评审报告（A 轮）

> 评审人：cpq-architect 子代理 ｜ 日期：2026-09-02 ｜ 评审对象：闸门 A 呈报版
> 评审方式：**不采信文档中任何「实测」字样**，全部自行跑只读 SQL / 读源码复核。
> 越界纪律遵守情况：未改任何代码、未改六件套、未执行任何写库语句、未建分支、未提交。

---

## 0. 结论摘要

**打回（限于 AC 与契约层，架构主干与 A0 两项裁决不必推翻）。**

一句话理由：**三条 AC 在现有架构下逻辑上不可能通过（AC-19③、AC-2 vs AC-7/AC-10、test.md §4 对 `config_fingerprint` 的断言），另有 AC-18/AC-3 全套 fixture 值在现网库里不存在** —— 这些不是打磨问题，是「照着做一定会撞墙」的问题，必须在开工前改掉，否则子代理会花整轮开发时间去实现一个做不出来的断言。

文档的方法论质量很高（AC-15a/15b 证伪对照组、AC-20 防 `distinct()` 守卫经我独立复算**完全正确**，是本次评审里最扎实的部分），问题集中在**「实测」数字的可信度**与**几处没有推到底的契约后果**。

---

## 1. 实测复核（P0）

### 1.1 数据库只读复核

统一命令前缀：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tAc "…"`

| # | 文档声称 | 我的验证命令（SQL 主体） | 实际结果 | 是否相符 |
|---|---|---|---|---|
| 1 | `sel_part_signature` **0 行** | `SELECT count(*) FROM sel_part_signature;` | `0` | ✅ **相符** |
| 2 | `material_master.material_recipe_id` / `config_fingerprint` **1890 行全 NULL** | `SELECT count(*), count(material_recipe_id), count(config_fingerprint) FROM material_master;` | `1889 \| 0 \| 0` | ⚠️ **结论相符**（两列确为全 NULL），**总行数 1889 非 1890**（数据在动，可接受） |
| 3 | `material_type` = 零件 1851 / 外购件 1 / **成品 1** / NULL 37 | `SELECT COALESCE(material_type,'<NULL>'), count(*) FROM material_master GROUP BY 1;` | `零件 1848 \| 外购件 1 \| <NULL> 40`；另 `SELECT count(*) … WHERE material_type='成品'` → **0** | ❌ **不符**：**「成品」这个取值在现网库里根本不存在**，其余三个数字也全不对 |
| 4 | `material_bom_item.characteristic`：`OUTSOURCED` **0 行**，只有 **ASSEMBLY 29 / RECIPE 15** | `SELECT COALESCE(characteristic,'<NULL>'), count(*) FROM material_bom_item GROUP BY 1;` | `RECIPE 11095 \| ASSEMBLY 49 \| <NULL> 1` | ⚠️ **`OUTSOURCED=0` 这个结论对**；**但 29/15 这两个数字差了三个数量级**，且**漏报了 1 行 `characteristic IS NULL`**。我又按 `system_type` / `is_current` / `count(DISTINCT material_no)` 四种切法各试一遍，**没有任何一种切法能得出 29/15** |
| 5 | `material_ratio`：11131 行中仅 3 行非空 | `SELECT count(*), count(material_ratio) FROM material_bom_item;` | `11145 \| 3` | ✅ **相符**（总行数微增，结论一致） |
| 6 | `process_master` **4 条**（`TP10`/`TP20` **遗留**测试数据 + `Z100 焊接`/`Z101 铆接`） | `SELECT process_no, process_name, process_category, created_at FROM process_master ORDER BY created_at;` | 4 条 ✅；但 **`TP10`/`TP20` 的 `created_at` = `2026-09-02 11:54:22`** | ⚠️ **条数相符，性质定错**：TP10/TP20 **不是「遗留」数据，是今天 11:54 刚被写进去的**（见 §2 P0-6） |
| 补 | 需求文档 / api.md：`process` 表 0 行、V267 是空搬运 | `SELECT count(*) FROM process;` | `0` | ✅ **相符** |
| 补 | AC-18：材质库 **258 条 ACTIVE** | `SELECT status,count(*) FROM material_recipe GROUP BY 1;` | `ACTIVE 262 \| INACTIVE 1` | ❌ **不符** |
| 补 | AC-18：`00263 / AC测新材`，ACTIVE 含量配置 **0 组** | `SELECT code,symbol,name,status FROM material_recipe WHERE code='00263';` + config 计数 | `00263 \| SnO2-del \| SnO2-del \| **INACTIVE**`，且**有 1 组 ACTIVE 配置** | ❌ **三处全错**（名字、状态、组数） |
| 补 | AC-3/7/8/9/10/11：材质 **紫铜**（占比 30） | `SELECT code,symbol,name FROM material_recipe WHERE name LIKE '%紫铜%' OR symbol LIKE '%紫铜%';` | **0 行 —— 全库没有「紫铜」** | ❌ **不符**（且 `00123` 实为 `AgZnO12/Cu`，不是紫铜） |
| 补 | AC-18：输入 `AgNi` 筛出 5 条（AgNi10/15/20/30 + AgNi10/Ag15CuP） | `SELECT code,symbol,name FROM material_recipe WHERE status='ACTIVE' AND (symbol ILIKE '%AgNi%' OR name ILIKE '%AgNi%');` | **42 条** | ❌ **不符** |
| 补 | AC-6：`00006` 的 `allow_custom_content=false` | 同上 | `00006 \| AgNi10 \| ACTIVE \| f` | ✅ **相符** |
| 补 | 需求文档 §4.3「258 条材质全 `locked`」 | `SELECT allow_custom_content,count(*) FROM material_recipe GROUP BY 1;` | `f 259 \| t 4`（4 条为 `AgCu85/AgCu90/AgNi90/AgNi95` demo 数据） | ⚠️ **已被 `task-260901` 的 demo 夹具打破** |
| 补 | AC-18：输入 `法兰` 空态 | `SELECT count(*) … WHERE name LIKE '%法兰%' OR symbol LIKE '%法兰%';` | `0` | ✅ **相符** |
| 补 | api.md §2.2：外购件示例 `WG-0001 / 绝缘垫片` | `SELECT material_no,material_name,specification FROM material_master WHERE material_type='外购件';` | `TEST-Q13-CODE \| 组成件1 \| (空规格)` | ❌ **示例值是编造的**（条数 1 对，内容全不对） |
| 补 | 需求文档 §4.1：`material_master` 的 `material_name/specification/dimension/unit_weight` 落点均已存在 | `information_schema.columns` | 四列**全部存在**（`unit_weight numeric(24,12)`、其余 `varchar(100)`） | ✅ **相符** |
| 补 | 需求文档 §4.1：`material_ratio` 为 `numeric(24,12)` | 同上 | `numeric(24,12)` | ✅ **相符** |

### 1.2 源码复核

| # | 文档声称 | 我读的位置 | 实际结果 | 是否相符 |
|---|---|---|---|---|
| 7 | `SalesFingerprintCalculator` 的 `PRC=` 是 `sorted().join(",")`，`MAT=` 单值，`ELE=` 按元素码排序，`COMBO=` 按「子料号:qty」排序，`CPROC=` 排序，`STRUCTURE_VERSION="v1"` | `cpq-backend/src/main/java/com/cpq/configure/SalesFingerprintCalculator.java:39,147,154-161,116-131,169-170` | 逐条对上：`:39 STRUCTURE_VERSION="v1"`；`:147 return "MAT=" + materialCode`（单值）；`:154 sorted(Comparator.comparing(ElementPct::elementCode))`；`:169 processCodes.stream().sorted().collect(joining(","))`；`:123/:131 sorted()` | ✅ **完全相符**（本条是文档里质量最高的「实测」） |
| 8 | `v_composite_child_materials` 的 `material_name` = `COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name)`，`mm.material_type` 是第二兜底 | `pg_get_viewdef('v_composite_child_materials'::regclass, true)` | 逐字一致 | ✅ **完全相符** |
| 9 | `ConfigureProductService` 的 `insertMaterialMasterV6` 第 2 实参传的是 `recipe.symbol` | `ConfigureProductService.java:388` | `insertMaterialMasterV6(hfPartNo, recipe.symbol, pr.unitWeightGrams, recipe.id, null);` | ✅ **相符**，但**不完整** —— 见 §2 P0-4（还有第二个写入点 `:1276` 传字面量 `"COMPOSITE"`，文档全篇未提） |
| 补 | 需求文档 §4.3 引用位置为 `ConfigureProductService:378` | 同上 | 实际在 **`:388`** | ⚠️ 行号偏 10 行（`INDEX.md:171` 也已抄成 `:378`） |
| 补 | fronttask.md：`ConfigureProductDrawer.tsx`（349 行）+ `AddPartSubDrawer.tsx`（391 行） | `wc -l` | `349` ✅ / **`615`** ❌ | ⚠️ 后者**低估 57%**（`task-260901` 合入后已从 391 涨到 615），直接影响「整体重做」的工作量估计 |
| 补 | AC-15a：`33.333333333333*2+33.333333333334 === 100` 为 `true` | `node -e` + `java T.java` 双侧实跑 | JS `100 / true`；Java `100.0 / true` | ✅ **完全相符** |
| 补 | AC-15b：`0.000000000001+99.999999999998+0.000000000001` 浮点下 `= 99.99999999999999` | 同上 | JS `99.99999999999999 / false`；Java `99.99999999999999 / false`；`BigDecimal.compareTo(100) = 0` | ✅ **完全相符**，证伪对照组设计成立 |
| 补 | AC-20：`["Z100","Z101","Z100"].sort()` → `Z100,Z100,Z101 ≠ Z100,Z101` | 直接推演 + `:169` 无 `distinct()` | 成立 | ✅ **相符** |
| 补 | backtask B-4：`element_bom_item` 的 groupKey 含 `material_part_no`，天然支持多组 | `ConfigureProductService.java:756-765`（`masterGk.put("material_part_no", …)`）+ 实查 `SELECT … HAVING count(DISTINCT material_part_no)>1` 得 5 组存量 | **写入侧成立** | ⚠️ **写入侧对，读出侧不对** —— 见 §2 P0-5 |

---

## 2. 发现的问题

### 🔴 P0-1 · AC-19 断言③ 在现有架构下**逻辑上不可能成立**

**问题**：AC-19③ 要求「两次的 `unit_price.seq_no` 各按自己的列表顺序落（1=Z100/2=Z101 **与** 1=Z101/2=Z100）」，同时①要求两次**复用同一料号 X**。这两件事互斥。

**证据**（三条，独立成立）：

1. `unit_price` 的写入分组键（`ConfigureProductService.java:1060-1066`）：
   ```
   gk = {system_type=QUOTE, price_type=PROCESS, cost_type=自制加工费, customer_no, code=hfPartNo, finished_material_no=hfPartNo}
   ```
   **无 quotation / lineItem 维度**。同一料号 X + 同一客户 = **同一个组，只能有一套 `seq_no`**。
2. 唯一索引 `uq_unit_price` 含 `code, customer_no, finished_material_no, operation_no, seq_no, version_no` —— 两种顺序若同版本共存必撞键。
3. 更根本：指纹命中时代码在 `:350-355` **早退**：
   ```java
   if (hit != null) { reused.add(hit); return hit; }   // ← 在 insertProcessSimpleUnitPriceV6(:381) 之前
   ```
   ⇒ 第二次提交**根本不写 `unit_price`**，`seq_no` 保持第一次的值。
   而若前端按现有逻辑把 `partMode` 切成 `existing`（`:845` 注释点名 `ConfigureProductDrawer.reuseExistingPart`），则走 `:324` 的 `insertProcessSimpleUnitPriceV6` → **后写覆盖先写**，第一张报价单的工序顺序被静默改写。

**影响**：这**正是** `computeComposite` 的 javadoc 里点名的历史事故形态（「命中复用会在父级落库前短路跳过，**静默丢弃新 qty/工序** —— T5 code review Important #1：错价风险」）。需求文档 §4.4 引用了这起事故作为「我们想过了」的证据，然后写下了一条**与该事故同型**的 AC。

**建议**：AC-19③ 改写为断言真实后果，二选一由用户裁决：
- **方案甲（不改架构）**：`AC-19③ 改为` —— 第二次提交后 SQL 查 `unit_price` 仍为 `1=Z100/2=Z101`（**第一次的顺序**），且前端在命中复用时给出明示提示「该配置已存在，工序顺序沿用已有产品」。同时 AC-11 的「工序顺序回填」必须限定为**当前报价行**，不是料号主数据。
- **方案乙（改架构）**：给 `unit_price`（或改走 `quotation_line_process`）加 lineItem 维度 —— 但那是新增 B-x + 迁移，属扩范围，须回闸门 A0。

---

### 🔴 P0-2 · AC-2 与 AC-7/AC-10 互斥：`uq_mcm_quote_no` 把「客户产品编号」和「销售料号」锁成 1:1

**问题**：
- AC-2 要求客户产品编号**全局不可重复**（已存在则挡住）⇒ 第二次选配必须用**新编号** `CP-NEW-002`。
- AC-7/AC-10 要求配置相同时**复用同一销售料号 X**。
- B-8 要求把 `customer_product_no` 落进 `material_customer_map`，`material_no` = 铸出/**复用**的销售料号。

三者叠加 ⇒ 需要 `(material_no=X, customer_product_no=CP-NEW-001)` 与 `(material_no=X, customer_product_no=CP-NEW-002)` 两行共存。

**证据**（现网唯一索引，实查）：
```
uq_mcm_quote_no | CREATE UNIQUE INDEX … ON material_customer_map (material_no) WHERE system_type = 'QUOTE'
```
**QUOTE 域每个 `material_no` 只允许一行 mcm。** 第二次落库必然：撞唯一索引 → 500，或改成 UPSERT → **把第一个产品的客户产品编号覆盖掉**（第一张报价单的产品从产品库列表里消失）。

**影响**：这是本任务两条核心业务规则（编号唯一 + 指纹复用）的正面冲突，落在最后一步落库上，**六件套完全没提**。B-8 只有一句「写 `material_customer_map`」。

**建议**：闸门 A0 补一次裁决（这是新岔路，不是打磨）：
- (a) 客户产品编号不进 mcm，另建 `sel_product_no` 映射表（多对一）；
- (b) 指纹加入客户产品编号维度 ⇒ 但这直接推翻 D-5 与 AC-7/AC-10 的全部复用语义；
- (c) 放弃 AC-2 的全局唯一，改为「同编号允许指向同料号」。
无论选哪个都要改 AC，因此**不能带着现状开工**。

---

### 🔴 P0-3 · `test.md §4` 的一条假绿守卫本身会引入 500

**问题**：test.md §4 最后一行写「`material_recipe_id` / `config_fingerprint` 恒 NULL …… AC-3 须**显式断言这两列在新料号上非 NULL**（否则等于路径仍没跑通）」。

**证据**：`ConfigureProductService.java:386-388` 的注释与实参：
```java
// R1: config_fingerprint 传 null — 客户维度发号后同一 material_master 可能被多个客户各自的
// 报价料号复用，若沿用生产侧全局指纹会撞 uq_material_master_fingerprint 全局唯一索引 → 500。
insertMaterialMasterV6(hfPartNo, recipe.symbol, pr.unitWeightGrams, recipe.id, null);
                                                                              // ↑ 有意传 null
```
现网索引印证：`uq_material_master_fingerprint … ON material_master (config_fingerprint) WHERE config_fingerprint IS NOT NULL`。

⇒ **`config_fingerprint` 为 NULL 是有意为之的正确行为，不是「路径没跑通」的证据。** 一个照 test.md 做的测试工程师会写出一条逼实现者把指纹写回去的断言，而满足它就会撞全局唯一索引 500。

**影响**：test.md 的这一格从「假绿守卫」变成了「制造真红的陷阱」，且它带着「🚨 本次是它第一次真跑」的强语气，很容易被照单执行。

**建议**：`config_fingerprint` 一列从断言中删除，并在同格注明「**该列有意恒 NULL，见 `:386` R1 注释，不得断言非空**」。`material_recipe_id` 的非空断言保留 —— 但见 P0-4。

---

### 🔴 P0-4 · 多材质后 `material_master.material_recipe_id` 指向谁，无人定义；且 B-9 漏了第二个 `material_type` 写入点

**问题 A（单值外键无归属）**：`material_master.material_recipe_id` 是**单值 uuid 外键**（实查 `data_type=uuid`，现有索引 `idx_material_master_recipe`）。三层模型下一个零件挂 N 个材质，这一列该写谁？六件套（含 §4.1 依赖表）**只字未提**。

这不是纯理论问题 —— `backfillV6MaterialsForCustomer`（`:867-880`）**依赖它非空**来跨客户重建材质行：
```sql
FROM material_master mm LEFT JOIN material_recipe mr ON mr.id = mm.material_recipe_id
WHERE mm.material_no = :p AND mm.material_recipe_id IS NOT NULL
```
且该 SQL **硬编码 `seq_no=1`、只产出 1 行**。⇒ **多材质料号被跨客户复用时，材质会从 N 个塌回 1 个**（或 0 个）。

**问题 B（B-3 漏写入点）**：backtask B-3 只点名 `insertMaterialBomItemV6`。`backfillV6MaterialsForCustomer` 是 `material_bom_item` 的**第二个写入点**，在 `existing` 分支（`:307`）无条件调用 —— 而指纹命中复用时前端正是把 `partMode` 切成 `existing`。**这条路径必然被本任务的 AC-7 触发。**

**问题 C（B-9 漏写入点）**：B-9 只改 `:388`。`material_type` 在选配侧有**两个**写入点：
```
:388  insertMaterialMasterV6(hfPartNo, recipe.symbol, …)     ← B-9 覆盖
:1276 insertMaterialMasterV6(parentHfPartNo, "COMPOSITE", …) ← B-9 未覆盖
```
⇒ 需求文档 §4.3 的「一列两义」其实是**一列三义**（料号类型 / 材质符号 / 产品结构类型）。「归位为料号类型」后 `"COMPOSITE"` 该写什么，没有答案。而 `v_composite_child_materials` 第二个 UNION 分支 `COALESCE(mm.material_type, mm.material_name) AS material_name` 会把它直接渲染成材质名。

**建议**：B-3 补 `backfillV6MaterialsForCustomer`（改成按 `material_bom_item` 复制而非按 `material_recipe_id` 重建）；B-9 补 `:1276`；§4.1 补 `material_recipe_id` 的多材质归属裁决（建议：置 NULL，材质权威改为 `material_bom_item`，但这会连带影响上面那条 backfill SQL 与 `test.md` 的非空断言，须一并改）。

---

### 🔴 P0-5 · 多材质在**读出侧**会被 `v_composite_child_elements` 静默吞组（B-4「天然支持」只对了一半）

**问题**：B-4 称 `element_bom_item` 的 groupKey 含 `material_part_no`「天然支持多组」。写入侧我复核**属实**。但读出侧的视图不认这个维度：

```sql
-- pg_get_viewdef('v_composite_child_elements')
AND ebi.characteristic = ( SELECT max(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no )   -- ← 没有 material_part_no
```
`characteristic` 在这张表上是 **VersionedV6Writer 的版本列**（实查取值 `2000/2001/…/2018`），而 `VersionedV6Writer.nextVersionOf(table, versionColumn, groupKeyColumns)` 是**按 groupKey 独立递增**的。

⇒ 材质 A 的组升到 `2001`、材质 B 的组还停在 `2000` 时（只改了一个材质就重新提交，正是常见操作），`max()` 取 `2001` ⇒ **材质 B 的元素行整组从元素页签消失，无任何报错**。

**现状核查**：我查了存量，目前尚无「同 material_no 多 material_part_no 且版本不齐」的行（`HAVING count(DISTINCT material_part_no)>1 AND count(DISTINCT characteristic)>1` → **0 组**）。所以这是**潜伏缺陷，本任务第一次给它通电**。

**附带问题**：该视图不 SELECT `material_part_no`，且两组元素的 `seq_no` 都从 1 开始 —— 前端拿到的两组元素**没有归属标签、`seq_no` 重复**。test.md 的回归项写「元素页签显示两组元素（Ag/Ni 与 Cu/P），合计正确」，但视图**没有任何列能把元素分到组**。

**建议**：本任务必须新增一条 B-x（视图改造 + 迁移）：`max(characteristic)` 的相关子查询补 `material_part_no` 维度，并把 `material_part_no` 暴露到视图列供渲染分组。同时在 test.md 加一条**版本错位**用例（先提交双材质 → 只改其中一个材质的含量再提交 → 断言元素页签**仍是两组**），否则首次写入必然假绿。

---

### 🔴 P0-6 · `TP10`/`TP20` 不是「遗留测试数据」，是**今天 11:54 刚被写进 dev 库的污染**

**证据**：
```
Z100 | 焊接 | 组装 | 2026-07-28 04:03:47
Z101 | 铆接 | 组装 | 2026-07-28 04:03:47
TP10 | 测试工序10 | (空) | 2026-09-02 11:54:22   ← 今天
TP20 | 测试工序20 | (空) | 2026-09-02 11:54:22   ← 今天
```
需求文档 §4.5 与 api.md §3 都把它们描述成既有遗留数据。实际是**本次立项调查当天**由提交式夹具写入的 —— 与 test.md §0 自己列的两起事故（材质会话种 4 条 demo 材质、V399 撞号）**同型的第三起**，而且就发生在写这份 test.md 的同一天。

**影响**：① `process_master 4 条` 这个「现网基线」不是稳定基线，随时会被下一次 `./mvnw test` 改变；② B-12 让 4 个单测改用 `Z100/Z101`，但没有任何机制阻止下一轮测试继续往 `process_master` 里塞行；③ 更要紧的是它证明 **test.md §0 的纪律目前只是文字，没有执行保障**。

**建议**：test.md §0 增加一条硬项 ——「本任务所有夹具工序码统一用 `T260902-` 前缀，且 `test-report.md` 必须附一条 `SELECT process_no, created_at FROM process_master WHERE created_at > <开工时刻>` 的收尾核对输出，非空即视为污染未清理」。顺带请主线决定 `TP10/TP20` 这两行怎么处理（**属清理动作，我无批准权，未执行**）。

---

### 🟠 P1-7 · `PART=<品名>/<规格>/<尺寸>` 用 `/` 当分隔符 → 指纹碰撞 + 未定义的 500

**证据链**：
1. `api.md §4` 与原型 `6-组合工序与指纹结果.html` 的样例串：
   ```
   v2|CUST=SZXM|PART=动触头/φ12×3/12×8×3|WEIGHT=10|MAT=…
   ```
2. `SalesFingerprintCalculator.assertNoDelimiter`（`:180-190`）守卫的字符集是 **`| = , : ∅` 五个，不含 `/`**。
3. 该类的类注释白纸黑字写着这类事故的形态：
   > 「例如工序码 `["a","b,c"]` 与 `["a,b","c"]` 都会渲染成 `PRC=a,b,c`，造成两个不同选配复用同一报价料号的**静默错价**」

⇒ 品名 `A/B` + 规格 `C` 与 品名 `A` + 规格 `B/C` 渲染出**同一个 `PART=A/B/C`** → 同一销售料号 → 静默错价。
**这不是理论风险**：我实查 `material_recipe.symbol` 含 `/` 的有 **74 条**（`AgNi10/Cu/1008`、`AgNi30C3/AgNi20`…），说明本业务的规格/名称文本带 `/` 是**常态**。

**同时**：品名/规格/尺寸是**自由文本用户输入**。用户打一个中文全角冒号或逗号 → `assertNoDelimiter` 抛 `IllegalArgumentException` → api.md 的错误码表**没有对应条目**，用户拿到 500。

**建议**：`PART=` 改用**长度前缀**（`PART=3:动触头|4:φ12×3|…`）或对三段各做转义；并在 api.md §1.2 错误码表补 `PART_TEXT_INVALID_CHAR`（400）。这条必须在 B-5 开工前定，事后改指纹结构 = 全部已发号料号失配。

---

### 🟠 P1-8 · v2 指纹结构与 `renderToken` / `sel_param_type` 封闭枚举的对接方式未定义（AP-44 型漏点）

**证据**：
- `renderToken` 是 `switch(paramTypeCode)`，`default: throw new IllegalArgumentException("未知 paramTypeCode")`。
- token 集合来自 `projectEnabledParams(pr, enabledTypes)`，槽位由 `sel_param_type` + 客户模板 enabled 驱动。实查 `sel_param_type` **恰好 3 行**（`MATERIAL / ELEMENT / PROCESS`），且 `SelParamCandidateService` 用 `switch("MATERIAL_RECIPE"/"V6_PROCESS_MASTER")` 硬匹配 —— 需求文档 §4.5 自己认定它是**封闭枚举**。

⇒ v2 新增的 `PART=` / `WEIGHT=` **既不是 `sel_param_type` 的成员，也不在 `renderToken` 的 case 里**。实现者只有两条路，api.md 与 B-5 **都没说走哪条**：
- 绕过槽位机制、直接拼进 `computeSimple` 的串（改方法签名，破坏「槽位由模板驱动」这一设计）；
- 新增两个 `sel_param_type` 行（要迁移种子 + `SelParamCandidateService` switch + `sel_template.allowed_value_key` + 选配模板管理页）—— 而 fronttask.md 的「不做」清单**明确排除了选配模板管理页**。

**另外三处 v2 结构的空槽**：
1. **`ELE=` token 在 v2 里消失了**（元素被折进 `MAT=`），api.md §4 的表里没有 `ELE=` 行，也没写「删除」。这是一次**未声明的契约删除**。
2. `EnabledParam` 的 javadoc 不变量：「每 paramTypeCode 至多一项……否则会产生多个同名 token，破坏规范串结构」。移除 ELEMENT 槽位后，`projectEnabledParams` 的**防坍缩底线**（注释里写明 MATERIAL/ELEMENT **恒为槽位**）要重新论证。
3. **`STRUCTURE_VERSION` 是 `computeSimple` 与 `computeComposite` 共用的常量**。api.md §4 只定义了 SIMPLE 的 v2 串，却把 `CPROC=` 也画进去了 —— 而 `CPROC=` 只存在于 `computeComposite`，`computeSimple` 里没有。**COMPOSITE 的 v2 串结构未定义**，B-5 照 api.md §4 实现会做出一个错的 COMPOSITE 结构。

**建议**：api.md §4 拆成 §4.1 SIMPLE-v2 / §4.2 COMPOSITE-v2 两张表，显式写明 `ELE=` 的去向、`PART=`/`WEIGHT=` 的注入方式（建议走「非槽位的固定前缀 token」并在 `computeSimple` 签名里显式接参，避免动 `sel_param_type`），B-5 同步补一条「不改 `sel_param_type`」的显式约束。

---

### 🟠 P1-9 · 违反 `backend.md` N+1 硬指标：本任务动到的路径上有 4 处「循环体里查库」

`backend.md` 的口径是「单个业务操作的 SQL 条数必须是常数，与 N 无关；**循环体里出现查询 = 违规**」。本任务扩大了 N（F-11 让工序变成**允许重复的无界有序列表**，材质变成 1..N）。

| 位置 | 循环体内的语句 | N 是什么 | 本任务是否触及 |
|---|---|---|---|
| `ConfigureProductService.java:1041-1048` `insertProcessSimpleUnitPriceV6` | `SELECT standard_currency, standard_unit FROM process_master WHERE process_no = :c` | 工序数 | ✅ **B-6 直接改这个方法** |
| `resolveProcessCodes`（`projectEnabledParams` 下游） | `SELECT 1 FROM process_master WHERE process_no = :pn` | 工序数 × 配件数 | ✅ **B-5 改指纹装配必经此处** |
| `:1159-1172` `insertQuotationLineProcesses` | 逐条 `INSERT INTO quotation_line_process` | 工序数 | ✅ **AC-11/AC-19 的显示顺序由它承载** |
| `insertCompositeProcessCapacityV6` | `SELECT … FROM process_master WHERE process_no=:c AND process_category='ASSEMBLY'` | 组合工序数 | ⚪ 间接 |
| B-3/B-4 的**新增**风险 | 若按材质逐个调 `VersionedV6Writer.writeVersionedGroup`，每组 ≈ 5 次 DB 往返（lock/load/ver/flip/ins） | 材质数 | ✅ **新引入** |

**建议**：① B-5/B-6 顺手把 `process_master` 的两处循环查询改成一次 `WHERE process_no IN (…)` + Map 查表；② B-4 明确要求走 `VersionedV6Writer.writeVersionedGroups(…, Map<groupKey, rows>)` **多组批量重载**（该 API 已存在，`VersionedV6Writer.java:213/230`），不许 for 循环调单组版本；③ `insertQuotationLineProcesses` 改批量 INSERT。这三条应写进 backtask，否则子代理不会主动做。

---

### 🟠 P1-10 · AC-3 断言① 无人认领（正向覆盖缺口，闸门 A 自检②不过）

**证据**：AC-3 断言① 是「`material_master` 该料号：`material_name='触点'`、`specification='φ5'`、`dimension='5×3×2'`、`unit_weight=10`」。

现状 `insertMaterialMasterV6` 的 INSERT 列只有 5 个：
```java
"INSERT INTO material_master (material_no, material_type, unit_weight, material_recipe_id, config_fingerprint) …"
```
**没有 `material_name` / `specification` / `dimension`。**

backtask 的正向覆盖表把 AC-3 挂给 `B-1`（DTO）、`B-3`（`material_bom_item`）、`B-4`（`element_bom_item`）—— **三条都不写 `material_master`**。唯一触碰该方法的 B-9 只改 `material_type` 字面量、且只挂 AC-5。

⇒ 按现状开工，AC-3 的断言① **没有任何 B-x 会去实现它**。这正是 `task-docs.md §7` 自检项②要拦的「交付缺口」。

**建议**：新增 `B-14`：`insertMaterialMasterV6` 扩列写入 `material_name/specification/dimension`（注意现有 `ON CONFLICT DO NOTHING` 在复用场景下不会更新，需确认是否要改 `DO UPDATE`）。

---

### 🟠 P1-11 · `S-5`（自定义含量）的**正向路径一条 AC 都没有**

AC-6 只验「`allow_custom_content=false` 时入口禁用」（反向）。**没有任何 AC 验「支持自定义时，改了含量 → 落 `element_bom_item` → 进指纹 → 与标准配方铸不同料号」**，而这正是 S-5 的业务价值本身，也是 `task-260901` 打开开关后**第一次真跑**的路径（需求文档 §4.3 自己说「`task-260901` 打开 `allow_custom_content` 会给这条路径通电」）。

**可用 fixture**（我实查）：`allow_custom_content=true` 的恰好 4 条 —— `AgCu85 / AgCu90 / AgNi90 / AgNi95`（均 ACTIVE）。

**建议**：补 `AC-21`（单点）：用 `AgNi90` 自定义含量 → 断言 `element_bom_item` 落自定义值、`material_recipe_config` 无新增（不回流，对齐 D-5）；补 `AC-22`（序列）：同材质、标准配方 vs 自定义含量（内容不同）→ **铸不同料号**；同材质、自定义含量**逐字等于**标准配方 → **复用同一料号**（这才是 D-5「按含量内容判同」的完整验证，AC-10 只验了配方↔配方）。

---

### 🟠 P1-12 · AC-18 / AC-3 / AC-7~AC-11 的 fixture 值大面积不存在，且原型违反 `frontend.md §1.3` 硬指标 #2

**汇总**（详见 §1.1）：

| 文档里的 fixture | 现网实况 | 后果 |
|---|---|---|
| 材质「**紫铜**」（AC-3/7/8/9/10/11 的第二材质，原型 2/3/4/6 与交互原型都在用） | **全库不存在** | 这 6 条 AC 的场景**跑不起来**；测试工程师要么改数据（污染共享库，违反 test.md §0），要么改 AC |
| `00263 / AC测新材`，**0 组 ACTIVE 配置**（AC-18 的灰显守卫） | `00263 = SnO2-del`，**INACTIVE**，且**有 1 组 ACTIVE 配置** | AC-18 的「`00263` 在①③④中出现但灰显」**必然失败** —— 选择器按 `status=ACTIVE` 取数，它压根不会出现 |
| 「258 条 ACTIVE 材质」 | **262 条** | AC-18 的虚拟滚动前提值错 |
| 输入 `AgNi` 筛出 5 条 | **42 条** | AC-18 断言③④「结果相同」仍成立，但列出的 5 条清单是错的 |
| `WG-0001 / 绝缘垫片`（api.md §2.2 示例 + 原型 5） | `TEST-Q13-CODE / 组成件1`，规格为空 | 前端照原型做的列头/列宽/空值处理会与真实数据对不上 |
| 「含量配置 0 组」的材质（AC-5 的 `RECIPE_HAS_NO_CONFIG` 触发条件 + AC-18 的红色 `0 组`） | **262 条 ACTIVE 材质，每条恰好 1 组 ACTIVE 配置，0 组的一条都没有** | 该错误码在 dev 库**无法自然构造** |

**规则依据**：`frontend.md §1.3` 硬指标 #2 —— 「**真实文案 + 真实示例数据** …… 数据取自 seed 或真实业务样例」。原型用了编造材质，AC 又把编造值抄成断言值，于是**错误从原型固化进了验收基准**。

**建议**：用现网真实数据重铸一套 fixture，建议：
- 双材质场景改用 `00006 / AgNi10`（70%）+ `00123 / AgZnO12/Cu`（30%）；
- 「0 组配置」场景无真实样本 ⇒ AC-18 的该断言改为**事务内构造 + 回滚**（与 test.md §4 对 AC-16 的处理同法），或直接删掉这半条，把 `RECIPE_HAS_NO_CONFIG` 降级为纯后端单测；
- 「258 条」改成「≥260 条（实测 262，取数当日为准）」，避免把一个会漂移的计数写死进 AC。

---

### 🟡 P2-13 · `characteristic='OUTSOURCED'` 会流进选配-材质页签，回归清单未覆盖

`v_composite_child_materials` 的过滤条件是 `asy.characteristic IS DISTINCT FROM 'ASSEMBLY'`（**排除法**，不是白名单）。B-7 要写的 `OUTSOURCED` 行 **`IS DISTINCT FROM 'ASSEMBLY'` 为真** ⇒ 外购件会作为一行「材质」出现在选配-材质页签，`material_name` 取 `COALESCE(component_usage_type, mm.material_type, …)`。test.md §3 回归清单只写了「多材质后 1 行变 N 行」，没有这一条。**建议**补一条回归：加外购件后，材质页签**不应**多出外购件行（或明确它应该出现、以什么名字出现）。

### 🟡 P2-14 · `quotation_line_process` 没有顺序列，AC-11 的「工序顺序回填」无持久化依据

实查列：`id | line_item_id | process_id | process_no` —— **无 `seq_no`/`sort_order`**。`insertQuotationLineProcesses` 逐条 INSERT，读回若不带 `ORDER BY` 则顺序不保证（堆表，更新后会乱）。AC-11 断言「工序**顺序**回填」、F-10 同样断言，但**没有任何 B-x 给这张表加顺序列**。**建议**补 `B-15`（迁移加 `seq_no` + 写入赋值 + 读出 `ORDER BY`），否则 AC-11 会「今天绿、下周红」。

### 🟡 P2-15 · `MATERIAL_SOURCE_AMBIGUOUS` 与 `materialResolved` 未随 `materials[]` 下沉

`PartRequest.configNo` 的 javadoc 明写：「与 `elements` **必须恰好给一个**，两个都给或都不给 → 400 `MATERIAL_SOURCE_AMBIGUOUS`」，且有一个 `@JsonIgnore boolean materialResolved` 幂等标志（注释：「解析必须发生在指纹计算之前，且要幂等 —— 物化之后 configNo 与 elements 会同时非空，再跑一次互斥校验就会误报」）。

`materials[]` 把 `configNo`/`elements` 下沉一层后，**互斥规则与幂等标志都得跟着下沉到每个 material**。api.md §1.2 的错误码表**没有 `MATERIAL_SOURCE_AMBIGUOUS`**，B-1 也没提 `materialResolved`。照现状实现会在第二次调用 `prepareMaterialSelection`（`lookupFingerprint` 与 `configure` 各调一次）时误报 400。**建议**：api.md 错误码表补该条并注明作用域为 `materials[i]`；B-1 明确要求 `materialResolved` 下沉到 material 级。

### 🟡 P2-16 · 并发下 `CUSTOMER_PRODUCT_NO_TAKEN` 会退化成 500

B-2 是 SELECT-then-INSERT。现网存在唯一索引 `uq_mcm_quote_cust_prod ON (system_type, customer_no, customer_product_no) WHERE system_type='QUOTE' AND customer_product_no IS NOT NULL`。两个会话同时提交同一编号 ⇒ 后者撞索引抛 `ConstraintViolationException` → 500，而不是约定的 409。**建议**：B-2 明确要求捕获该唯一约束异常并映射成同一个 409（这也是唯一正确的做法 —— 检查不能防竞态，索引才能）。

### 🟡 P2-17 · AC-12 会改变 `ExistingProductService` 的 `source` 语义，无回归

`ExistingProductService:86`：
```sql
CASE WHEN mcm.customer_product_no IS NULL THEN 'CONFIGURED' ELSE 'EXISTING' END AS source
```
B-8 把选配料号的 `customer_product_no` 填上以后，**所有选配产品的 `source` 会从 `CONFIGURED` 翻成 `EXISTING`**。前端若按这个字段打标/分流，行为会静默改变。test.md 回归清单未覆盖。**建议**补一条回归断言。

### 🟡 P2-18 · AC 三类覆盖：AC-5 一条 AC 塞了两件无关的事；边界类有名不副实

- **AC-5** 前置是「选外购件类型」、断言是「列表只列 `material_type='外购件'`」，但 `test.md T-05` 的断言里又塞进了「无 ACTIVE 配置的材质提交 → 409 `RECIPE_HAS_NO_CONFIG`」—— 这与外购件毫无关系，且（见 P1-12）在 dev 库无法构造。**建议**拆成 AC-5（外购件列表）与 AC-5b（材质无配置 409）。
- **AC-13**（单材质占比 100）归在「边界」，实质是**单点回归**；**AC-17**（重复添加灰显）也是单点交互。真正的边界（超长输入、并发编辑、权限不足）里，**超长**只在原型状态 D/C 画了、AC 没断言；**并发**全篇无 AC（而 P0-2/P2-16 表明并发是真实风险）；**权限**全篇未提。`task-docs.md §4` 明列「空数据 / 超长输入 / **并发编辑** / **权限不足**」。**建议**补 1 条并发 AC（两会话同编号提交，一个 409 一个成功）+ 1 条超长输入 AC（品名 100 字符 → `material_master.material_name` 是 `varchar(100)`，**超过必须前端拦或后端 400，不能落库截断**）。

### 🟡 P2-19 · 文档自身的一致性问题（不影响正确性，但影响可引用性）

1. **需求文档 §4 的小节顺序是 4.1 → 4.2 → 4.5 → 4.6 → 4.3 → 4.4 → 4.4**，且**有两个 §4.4**（「工序进指纹」与「架构基线」）。api.md §2.2 与 backtask B-9 都引用「§4.3」、需求文档自己引用「§④」，读者需要跳着找。
2. **需求文档 §③ 开头的免责声明已过期**：「⚠️ 本节为需求侧初稿。部分 AC 的具体断言值依赖闸门 A0 的架构裁决，**裁决后补全**」—— A0 已在 §4.3/§4.4 裁决。带着这句话进闸门 A，等于告诉用户「AC 还没定稿」，与闸门 A 的「照这份已定案的文档干，对吗」相冲突。
3. **`fronttask.md` 与 `backtask.md` 的覆盖表不一致**：backtask 的正向表里 AC-11 只挂 `F-8, F-10`（漏 `F-2`）、AC-15a 只挂 `F-5`（漏 `F-12`）；且**只做了 B-x 的反向检查，没有 F-x 的反向检查**（`task-docs.md §7` 自检项③要求「每个 `F-x`/`B-x` 都能指回 AC」）。
4. **`INDEX.md` 未同步**：`:36` 与 `:303` 仍写「需求文档已成稿（**17 条 AC**）· 闸门 A0 **呈报中** · **原型图待出**」，实际是 21 条 AC、A0 已裁决、原型已出 6+1 份。而且 `:36` 已经把 §1.1 里那批**错误的实测数字**（零件 1851/成品 1、ASSEMBLY 29/RECIPE 15、1890 行）原样抄了进去 —— 错误正在向索引层扩散。
5. **`ConfigureProductService:378` 这个行号**在需求文档 §4.3、api.md §2.2、`INDEX.md:171` 三处出现，实际是 **`:388`**。

---

## 3. 通过项（明确审过、无问题）

| 项 | 结论与依据 |
|---|---|
| **AC-15a / AC-15b 的证伪对照组设计** | ✅ **本次评审里质量最高的一处**。我在 JS 与 Java 两侧各实跑一遍：`33.333333333333*2+33.333333333334` 在**双侧都恰好 === 100**（所以 15a 确实拦不住浮点实现），`0.000000000001+99.999999999998+0.000000000001` 在**双侧都 = 99.99999999999999**（浮点实现会错误拒绝），`BigDecimal` 侧 `compareTo(100)=0`。**结论与文档逐字一致，配对逻辑成立，分辨力真实存在。** |
| **AC-20 防 `distinct()` 守卫** | ✅ 成立。`SalesFingerprintCalculator:169` 确为 `sorted().collect(joining(","))` 无 `distinct()`；`Z100,Z101,Z100` 排序后为 `Z100,Z100,Z101 ≠ Z100,Z101`。这条 AC 的「只验指纹字符串不同拦不住」的分析也是对的。 |
| **指纹现状描述（`MAT`/`ELE`/`PRC`/`COMBO`/`CPROC` 全类顺序无关）** | ✅ 逐行核对源码，**五个 token 的排序口径与文档描述完全一致**，`STRUCTURE_VERSION="v1"` 也对。「不存在内部不对称，本裁决是要不要为工艺路线破例」这个判断是准确的。 |
| **`v_composite_child_materials` 的 COALESCE 顺序** | ✅ 现网 viewdef 与文档引用**逐字一致**，`mm.material_type` 确为第二兜底，B-9 要求「必须实查一条真数据确认渲染无变化、不能只看代码推断」是正确的谨慎。 |
| **`sel_part_signature` 0 行 ⇒ 指纹升版无存量失配** | ✅ 实查 0 行，推论成立。 |
| **`material_ratio` / `material_master` 四列均已存在、零新增列** | ✅ 实查 `information_schema.columns` 全部命中，类型 `numeric(24,12)` 也对。「复用不新增列」的判断正确。 |
| **`element_bom_item` 写入侧 groupKey 含 `material_part_no`** | ✅ 代码核实（`:756-765`），且实查到 5 组存量多 `material_part_no` 数据。**写入侧的判断是对的**（读出侧的问题见 P0-5，不否定这半条结论）。 |
| **`process` 表 0 行 / V267 是空搬运** | ✅ 实查 `process` = 0 行。「只补跑 V267 是空搬运，修不好」的归因正确。§4.5 那段「归因过程本身值得留痕 —— 四轮才定准」的自省，我认为是文档里最有价值的方法论沉淀。 |
| **B-12 的判断（不写迁移补种子，改测试）** | ✅ 方向正确。工序确有维护界面、属开放主数据；`Z100/Z101` 的 `process_category` 实查确为**中文「组装」**，与 `CompositeProcessServiceB6CandidatesTest` 断言的英文 `'ASSEMBLY'` 不符，文档点出这一点是对的。 |
| **`frontend.md §1.1/§1.2` 遵从性** | ✅ 6 份原型**无一处 `ant-modal` / `<dialog>`**（grep 零命中），全用抽屉；fronttask 对「抽屉内部子表行内操作属 §1.2 例外白名单」的援引正确；「不可选项灰显+tooltip、禁止 `if(...) return null` 过滤」的口径贯彻到位（AC-17 明确写了这一条）。 |
| **原型覆盖面与身份标记** | ✅ `F-x` 涉及的每个页面/弹层都有对应 `.html`（6 份），`index.html` 有 📌当前基准 / 🕰️历史快照 标记，符合 `frontend.md §1.3`。`3-新建零件与多材质.html` 的状态 A/A2/B/C/D/E/F/G/H **全部实际存在**（含 fronttask 引用的 `A2-b`/`A2-c`/`F-b` 子态，我逐一 grep 确认过，**没有悬空引用**）。空态/禁用态/极值态**都画了**。 |
| **api.md §5「本次不改的契约」写明理由而非留空槽** | ✅ 符合 `task-docs.md §2`「宁可写细，不可留空槽」。 |
| **test.md §0 的环境前提** | ✅ `application-test.properties` 确实指向 `cpq_db_0724`（与 `CLAUDE.md` profile 表不符这一点，文档发现得对）。禁止提交式夹具、禁止清库、任务专属前缀三条纪律都对 —— 只是（见 P0-6）目前没有执行保障。 |
| **加法式扩展（老字段保留标 `@Deprecated`）** | ✅ 符合本项目一贯做法，理由（为并发分支与 `task-260901` 刚落地的 `configNo` 留安全边）站得住。 |

---

## 4. 我未能验证的部分（诚实列出）

| 项 | 为什么没验 |
|---|---|
| **`task-260901` 的 `material_recipe_config` / `allow_custom_content` 端点行为** | 只验了表结构与数据（262 条 ACTIVE 材质各 1 组配置、4 条 `allow_custom_content=true`），**没有起服务实调** `GET /material-recipes` / `/{id}/configs`，所以 api.md §3 说「响应含 `configCount` 与 `allowCustomContent`」这一点**未经验证**，只是文档声称。若前端要靠 `configCount` 做灰显（F-6），建议开工前实调一次确认字段名。 |
| **4 个选配单测在 `cpq_db_0724` 上「必失败」** | 未跑 `./mvnw test` —— 跑测试会往共享 dev 库写数据并触发 Flyway，正是 test.md §0 与 P0-6 所禁止的。我只做了静态核对（`MRO-*` 在 `process_master` 中**确实不存在**，`process` 表 0 行），所以「会失败」是**高置信推断，不是实测**。 |
| **`v_composite_child_materials` 多材质后真返 N 行** | 需要真正提交一次多材质选配才能观测，而那是写库操作。P0-5 关于 `v_composite_child_elements` 被 `max()` 吞组的推演，**依据是 viewdef + `VersionedV6Writer` 的按组版本逻辑，属代码推演而非运行时实测**（我用 SQL 确认了「存量中尚无版本错位的多组数据」，即缺陷未被触发过）。 |
| **前端 `AddPartSubDrawer.tsx` 的现有实现细节** | 只统计了行数（615 行）与 `task-260901` 改动这一事实，**未逐段读**。所以「整体重做的工作量」我只能说文档低估了行数，不能量化低估了多少。 |
| **交互原型 `选配流程-交互原型.html`（87 KB）** | 按 fronttask 声明它「不是验收基准」，我只做了数据一致性 grep（同样用了「紫铜」「AC测新材」），**未逐屏审交互**。 |
| **`docs/PRD-v3.md` 的一致性** | 六件套均未引用 PRD，我也**未核对**本任务是否与 PRD 的选配章节冲突。`task-docs.md §2` 要求「任务文档不得与 PRD 冲突」—— **这条自检六件套里看不到痕迹，建议主线补一次**。 |
| **`docs/三大核心模块基线.md` 的破坏性评估** | 需求文档 §4.4（第二个）说「本任务会改变 `v_composite_child_materials` 的返回行数（1→N），属基线影响，需在 A0 一并说明」。我**未打开基线文档核对**该处原文，无法判断这个「说明」是否已被用户在 A0 接受，也无法判断是否还有其他基线条款被触及。 |
| **`ExistingProductService` 的 `OR EXISTS` 兜底能否退役** | B-8 说「落地后评估」。这需要跑通完整链路后看数据，**本轮无法判断**。 |
| **性能** | 六件套无任何性能 AC 或预算。我只做了 N+1 的**静态**识别（P1-9），**未做任何耗时测量**。 |

---

**已自检：读了 12 份文档（六件套 5 份 + 6 份独立原型 + `index.html` + `CLAUDE.md` + `docs/rules/task-docs.md` + `docs/rules/frontend.md §1.3` + `dev-docs/INDEX.md` 定向段） / 跑了 33 条只读查询（psql 只读 + 2 次本地 JS/Java 数值实验，无任何 INSERT/UPDATE/DELETE/DDL） / 查证了 14 处代码（`SalesFingerprintCalculator` 全文 · `ConfigureProductService` 的 `:300-420`/`:720-840`/`:840-885`/`:1035-1110`/`:1148-1200`/`:1265-1295`/`effectiveEnabledTypes`/`projectEnabledParams`/`resolveProcessCodes` · `PartRequest.java` 全文 · `ExistingProductService` 关键段 · `VersionedV6Writer` 版本分配段 · `SelParamCandidateService` switch · 前端三个文件行数）。未修改任何代码或文档，未执行任何写库操作，未建分支、未提交。**
