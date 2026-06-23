# P2-C4 实现设计：核价侧首存快照「跨行 partSet 并集 + 每核价 driver 组件一次 expandMulti」

> 立项 2026-06-23（architect）。落地总纲 `docs/superpowers/specs/2026-06-23-import-and-firstsave-perf-optimization-design.md` §3 P2-C4。
> 工作分支 `worktree-p2-costing-partset`（基于 master，含 P1 批量优化 + P1-C3 `resolveGvarsBatched`）。
> 本文是 P2-C4 的精确可实现设计：把核价侧首存的 `expandForPartSet` 远程查询从 **N × M_rec** 压到 **M_rec**（N=核价行数，M_rec=核价模板里 `bom_recursive_expand=true` 的 driver 组件数）。
>
> 🔑 **本次代码核实推翻了总纲对收益面与风险面的两处估计，必须以本文为准**（见 §1.4 / §2.3）：
> 1. **union 的可优化面比总纲设想的小**——`expandForPartSet` 只被「recursive=true」的核价组件调用；当前所有核价模板里**仅 `COMP-0019 $cz_view` 一个组件 recursive=true**。其余 4 个核价 driver 组件（`$ys_view/$zpj_view/$gx_view/$zh_view`）`bom_recursive_expand=false`，走 `expand()` 单值分支，**不在 C4 范围**。
> 2. **唯一用 `:spineKeys` 的组件恰好是 recursive=false 的 `COMP-0020 $ys_view`**——它走 `expand()`、不走 `expandForPartSet`，所以 `:spineKeys` 与本次 union **物理无交集**。这把总纲最担心的「union 把 per-li spine 上下文串掉」风险**降为 0**（前提是严格只动 `expandForPartSet` 链）。

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| 优化对象 | `CardSnapshotService.buildCostingCardValues` → `expandTemplateDriverBaseRows(..,closure)` 里对 **recursive=true** 组件的 `componentDriverService.expandForPartSet(compId, customerId, closure.partSet, li.id, compositeType)` |
| 现状往返 | 每核价行各算闭包（缓存便宜）+ 对每个 recursive 组件 1 次多值 `loadByPath(ANY(partSet))`；N 行 × M_rec 组件 = **N×M_rec 次远程查询**，行间 partSet 高度重叠却零复用（`expandMulti`/多值 `loadByPath` 不进 `resultCache`） |
| 目标往返 | 先并所有行的 partSet → 每个 recursive 组件**一次** `expandMulti(union)` → `Map<partNo,resp>`，按各行 spine 深拷贝分发。**M_rec 次远程查询**（C4 ÷N） |
| 改动面 | 新增「核价整单批量预取」入口（在 `snapshotLineValues` 的 N-loop 之上）；`buildCostingCardValues` / `expandTemplateDriverBaseRows` 增可选「预取 Map」参数；**深拷贝点固定在 `spineRowNode`/`rowToNode`（已是 `MAPPER.valueToTree` 深拷贝）** |
| 唯一真·可优化组件（当前数据） | `COMP-0019 $cz_view`（recursive=true，无 spineKeys / 无 lineItemId / 无 composite / 无 quotation_line_item_id 维度 = AP-53 customer×material 共享）→ union 天然等价 |
| 风险评级 | **低**（不是总纲说的中低）。理由：① 只动 `expandForPartSet` 链；② 该链当前服务的视图无任何行维度谓词；③ spineKeys/composite/lineItemId 组件全在 recursive=false 分支，本次不碰；④ 深拷贝点不变 |
| 最大残余等价风险 | **共享 `Map<partNo,resp>` 的可变别名**——union 后该 Map 被全 N 行共享读，若分发链上任何代码就地 mutate `Row.driverRow`/`Row.basicDataValues` 会跨行串污染。落地必须审计整条分发链只读 + 「连跑两次 md5 一致」专项覆盖 |

---

## 1. 现状往返量化（已读真实代码核实）

### 1.1 N-loop 在哪（外层）

`snapshotLineValues(QuotationLineItem)` 是 **per-line-item、串行**调用，N 个核价行 = N 次：

- `ImportExecutionService.java:632`（**首存主路径**：导入后逐行 `li.persist()` 紧跟 `snapshotLineValues(li)`，**N 个 li 在该循环内逐个创建+快照，并非全部先建好**）
- `ImportExecutionService.java:207`
- `ConfigureProductResource.java:77`（加单个产品）
- `QuotationResource.java:145`（saveDraft 仅对 `cardSnapshotAt==null` 的新行）

每次 `snapshotLineValues` → `buildCostingCardValues(managed, costingTemplateId, customerId, quotationId)`（`CardSnapshotService.java:427`）。

### 1.2 M_rec-loop 在哪（内层，C4 现场）

`buildCostingCardValues`（`:545`）每行：
1. `bomClosureService.compute(li.productPartNoSnapshot, Map.of())`（`:562`）→ `BomClosureResult{partSet, spine, cyclePartNos}`。单条 `WITH RECURSIVE` CTE + Caffeine 缓存，**非 N+1**（C5，不动）。
2. `expandTemplateDriverBaseRows(costingTemplateId, li, customerId, quotationId, closure)`（`:566` → 实现 `:1157`）：
   - 一次 SQL 取该模板 driver 组件清单 `SELECT DISTINCT c.id, c.bom_recursive_expand ...`（`:1165`，**每行重复查一次模板组件清单——本身也是可省往返，但量级 N 次小表查询，次要**）。
   - 逐组件：
     - `recursive==true`（`:1184`）→ `componentDriverService.expandForPartSet(compId, customerId, closure.partSet, li.id, compositeType)`（`:1186`）→ `buildSpineBaseRows(closure, byPart)`（`:1188`）。**这是 C4 唯一现场。**
     - `recursive==false`（`:1189`）→ `componentDriverService.expand(compId, ..., partNo, ..., li.id, compositeType)` 单值（`:1191`）→ `buildBaseRowsFromRows`。**不在 C4 范围**（见 §2.3）。

`expandForPartSet`（`ComponentDriverService.java:625`）：
- 非 composite 视图（`compositeAgg==false`，`:633`）→ `expandMulti(componentId, customerId, partSet, null, null, null)`（`:645`）→ `DataLoader.loadByPath(path, null, partSet, customerId)`（`ComponentDriverService.java:566`）= **一次** `hf_part_no = ANY(:hfPartNos)` SQL（`DataLoader.java:244`，**不进 resultCache**）。
- composite 视图（`:636`）→ 逐料号 `expand()`（保留 lineItemId / 聚合语义）。**当前无核价组件命中此分支**（§2.3 实测）。

### 1.3 量级

```
首存核价侧 = Σ_{li ∈ N}  [ compute(li)  +  Σ_{c ∈ M_rec} expandForPartSet(c, partSet(li)) ]
           = N×(1 闭包，缓存) + N×M_rec 次多值远程 SQL
```

行间 BOM 闭包高度重叠（同客户多料号常共享子树），但 `expandMulti`/多值 `loadByPath` **不进任何缓存** → 跨行**零复用**。这是首存核价段最重的远程往返来源（含 BOM 树展开）。

### 1.4 ⚠️ 当前真实可优化面（核实数据，2026-06-23）

实测当前库（14 个 COSTING 模板，结构完全一致）每个核价模板 5 个 driver 组件：

| 组件 code | driver path | `bom_recursive_expand` | 走哪条 | 用 `:spineKeys` | composite | 在 C4 范围 |
|---|---|---|---|---|---|---|
| COMP-0019 | `$cz_view` | **true** | **expandForPartSet → expandMulti** | 否 | 否 | ✅ **是（唯一）** |
| COMP-0020 | `$ys_view` | false | `expand()` 单值 | **是** | 否 | ❌ 否 |
| COMP-0021 | `$zpj_view` | false | `expand()` 单值 | 否 | 否 | ❌ 否 |
| COMP-0022 | `$gx_view` | false | `expand()` 单值 | 否 | 否 | ❌ 否 |
| COMP-0023 | `$zh_view` | false | `expand()` 单值 | 否 | 否 | ❌ 否 |

全库 26 个 `component_sql_view`：**仅 1 个含 `:spineKeys`（`$ys_view`@`b3359f70`，COMP-0020）、0 个含 `:lineItemId`、0 个被 recursive 组件使用**。

**结论**：本次 C4 当前**只对 `COMP-0019 $cz_view` 一个组件生效**（M_rec=1）。`$cz_view` 模板（核实）：

```sql
SELECT asy.material_no AS hf_part_no, asy.component_no AS child_hf_part_no, ...
FROM material_bom_item asy LEFT JOIN material_master mm ... LEFT JOIN material_recipe mr ...
WHERE asy.system_type = 'QUOTE' AND asy.characteristic IS NULL AND asy.is_current
  AND asy.customer_no = :customerCode
```

无 `quotation_line_item_id`、无 `:lineItemId`、无 `:spineKeys`、无 composite —— **纯 customer×material 维度（AP-53），union 按 `hf_part_no` 分桶与逐行分别查结果逐位一致**。

> 收益虽看似仅 1 组件，但这 1 个就是核价侧最重的 BOM 树展开组件（recursive=true 的本义），且 ÷N（N 可达 73）。设计仍按「通用支持所有 recursive 组件 union」实现（M_rec 可能随未来配置增长），不写死单组件。

---

## 2. 精确改动清单

### 2.1 总体路线：在 N-loop 之上加「核价整单批量预取层」

C4 的瓶颈跨越 N 个 `snapshotLineValues` 调用，而 N-loop 不在 `CardSnapshotService` 内部，故**不能只改 `buildCostingCardValues`**。两种落地形态：

**方案 A（推荐）：每报价单一次批量预取，注入 per-component union Map**
- 新增 `precomputeCostingDriverUnion(quotationId)`：一次性对该报价单**全部核价行**算各自闭包 → 求各 recursive 组件的 partSet 并集 → 每 recursive 组件**一次** `expandMulti(union)` → 缓存 `Map<componentId, Map<partNo, ExpandDriverResponse>>`。
- `buildCostingCardValues` / `expandTemplateDriverBaseRows` 增**可选**入参 `Map<componentId, Map<partNo, resp>> unionByComp`；非 null 时 recursive 组件**不再调 expandForPartSet**，直接从 `unionByComp.get(cid)` 取并按本行 spine `buildSpineBaseRows` 深拷贝分发。
- 入参为 null 时**完全走旧逐行路径**（零破坏：`refreshCostingCardValues` 单行刷新、旧测试、加单产品场景都保留旧行为）。

**方案 B（不选）：给 `expandMulti` 结果加按 `(componentId,customerId,partVersion,driverPath)` 维度的跨行缓存**
- 改 `ComponentDriverService` 缓存语义，触及 §1 硬约束「可变对象引用」面更广（缓存返回的 resp 被多行共享，且要管 TTL/evict），且与 P1-C3 刚加的 `resolveGvarsBatched` 在 expandMulti 内的耦合更深。**风险高于 A，弃用。**

→ **采用方案 A**。它把「共享面」收敛在 CardSnapshotService 一个方法的局部变量里（请求内、无 TTL、首存结束即销毁），不污染进程级缓存语义，最贴合 §1 硬约束。

### 2.2 改动点逐条

**(1) 新增批量预取方法 `CardSnapshotService.precomputeCostingDriverUnion(UUID quotationId)`**
返回 `Map<UUID /*componentId*/, Map<String /*partNo*/, ExpandDriverResponse>>`。逻辑：
1. 取核价模板 id（`q.costingCardTemplateId`），取 recursive driver 组件清单（**一次** `SELECT DISTINCT c.id, c.bom_recursive_expand ... WHERE recursive=true AND data_driver_path 非空`——把 §1.2 里「每行重查模板组件清单」也顺带折叠为整单一次）。
2. 取该报价单全部核价行（`QuotationLineItem.list("quotationId", quotationId)`）。
3. 对每行算 `bomClosureService.compute(li.productPartNoSnapshot, Map.of())`（缓存便宜；这步即把 §1.3 的 N 次闭包也集中在此，结果可顺带缓存进一个 `Map<lineItemId, BomClosureResult>` 复用给后续分发，避免分发阶段二次 compute）。
4. 求每 recursive 组件的 **partSet 并集**：`LinkedHashSet<String> union`（保持确定序），遍历所有行 closure.partSet 累加。**对所有 recursive 组件用同一份 union**（它们都按 `hf_part_no=ANY` 取数，union 取全集不影响各组件按各自 spine 左关联的结果）。
5. 每 recursive 组件**一次** `componentDriverService.expandForPartSet(compId, customerId, new ArrayList<>(union), null /*lineItemId*/, null /*compositeType*/)`：
   - 复用现成 `expandForPartSet`（不新增 ComponentDriverService 方法）；它内部对非 composite 视图走 `expandMulti(union)` 一次多值 SQL。
   - **lineItemId 传 null**：核实 `$cz_view`（及所有 recursive 组件）无 lineItemId 维度，`expandForPartSet` 非 composite 分支本就忽略 lineItemId（`:645` 调 expandMulti 不传 lineItemId），传 null 与逐行传 `li.id` **对非 composite 视图结果完全一致**。
   - **若某 recursive 组件命中 composite 分支（`compositeAgg==true`），预取层必须跳过它、让它回落逐行**（见 §2.3 + §4 闸门）。
6. 返回 `unionByComp` + （可选）`closureByLi`。

> compositeType 传 null 安全性：`expandForPartSet` 的 compositeType 仅在 composite 分支透传给 `expand()`；非 composite 分支忽略。预取层只处理非 composite recursive 组件，故 null 无副作用。

**(2) 在 N-loop 之上调用预取（两处批量入口）**
- `ImportExecutionService`：当前在逐行创建循环内 `:632` 调 `snapshotLineValues`。**问题**：该处 N 个 li 尚未全部建好（边建边快照），无法在循环前对「全部行」预取。**修法**：把核价快照从「逐行循环内」抽到「全部 li 建好之后的二次循环」——
  - 第一遍循环：只 `li.persist()` + 报价侧轻量结构（保持 `ensureStructure` 幂等一次）。
  - 全部建完后：`precomputeCostingDriverUnion(quotationId)` 一次 → 第二遍循环 `snapshotLineValues(li, unionByComp, closureByLi)`。
  - ⚠️ 此重排是**结构性改动**，必须确认报价侧 `snapshot_rows`（ConfigureSnapshotService 写）在二次循环时已就绪（snapshotLineValues 的报价侧 `buildCardValues` 读 `quotation_line_component_data.snapshot_rows`）。**核价侧 union 不依赖报价侧 snapshot_rows**，但 `snapshotLineValues` 同时算报价侧；若报价侧 component_data 仍在后续 `:644` 才写，则 snapshotLineValues 的报价侧会读空。**落地前必须用 codegraph_trace 确认 ImportExecutionService 里 `quotation_line_component_data` 的写入时机 vs snapshotLineValues 的相对顺序**，并保证重排后报价侧逐位不变（否则单独保留报价侧逐行旧位、只把核价侧 union 化）。
  - **保守降级**：若重排报价侧风险大，可只对**核价侧**做整单预取——保留 `snapshotLineValues` 原位逐行算报价侧 + 报价 Excel，但核价侧 `buildCostingCardValues` 改读「请求级 union 缓存」。union 缓存用一个 `@RequestScoped`/ThreadLocal 持有，首个核价行触发整单预取（lazy init，对该 quotationId 只算一次），后续行命中。**这是最小破坏形态，推荐先实现这个。**
- `ConfigureProductResource.java:77`（加单产品）：N=1，union 退化为逐行，**保持不变/自然命中**（预取对单行无收益也无害）。
- `QuotationResource.java:145`（saveDraft 新行）：可能多行，复用同一预取入口。

> **强烈建议落地形态 = §2.2(2) 的「保守降级」lazy 请求级缓存**：不改 N-loop 结构、不动报价侧顺序，仅在 `buildCostingCardValues` 第一次被调用时对整单 recursive 组件预取一次 union，存入请求级 Map，本请求内后续核价行直接命中。等价面最小、回滚最简单。

**(3) `buildCostingCardValues` 增可选 union 入参**
签名增 `Map<UUID, Map<String,ExpandDriverResponse>> unionByComp`（null=旧路径）。内部把它透传给 `expandTemplateDriverBaseRows`。

**(4) `expandTemplateDriverBaseRows(.., closure, unionByComp)` 重载**
recursive 分支（`:1184`）改为：
```
Map<String,ExpandDriverResponse> byPart =
    (unionByComp != null && unionByComp.containsKey(compId))
        ? unionByComp.get(compId)                       // union 命中：复用整单一次查的结果
        : componentDriverService.expandForPartSet(...); // 未命中/旧路径：逐行查（兜底）
baseRowsByComp.put(cidStr, buildSpineBaseRows(closure, byPart));
```
- `buildSpineBaseRows(closure, byPart)` **不变**：它以**本行 closure.spine** 为行主轴，按 `node.hfPartNo` 从 `byPart` 取业务行（`:1218`）。union 的 byPart 是全集 superset，本行只取本行 spine 命中的料号 → **行数、行序与逐行查完全一致**（AP-51，见 §3.3）。
- composite recursive 组件（compositeAgg==true）**不进 unionByComp**（预取层已跳过）→ 此处 `containsKey` 为 false → 回落 `expandForPartSet` 逐行（保留聚合/lineItemId 语义）。

**(5) `SpineKeysContext` / `QuotationIdContext` 处理（关键）**
现状 `expandTemplateDriverBaseRows`（`:1174-1176`）在 per-li 循环内 `set` 了 `QuotationIdContext`(quotationId) 与 `SpineKeysContext`(本行 closure 三元组)，组件 expand 时由 SQL 视图宏读取。
- **union 预取阶段（§2.2(1) step5）调 expandForPartSet 时，绝不能设 SpineKeysContext**——因为 union 跨全行，没有「单行 spine」可设；而当前唯一会读 spineKeys 的视图（`$ys_view`）是 **recursive=false 组件**，根本不进 union 预取。→ **预取阶段对 recursive 组件不设 SpineKeysContext 是安全的**（recursive 组件视图实测均不含 `:spineKeys`）。
- **不变量守护（防未来）**：预取层必须在调 `expandForPartSet` **前**断言「该 recursive 组件的 driver 视图 sql_template 不含 `:spineKeys`」，含则**拒绝 union、回落逐行**（带 SpineKeysContext）。这条断言把「未来有人给 recursive 组件配 spineKeys 视图」的隐患挡在门外。判定复用 `SpineKeysMacro.containsMacro(sqlTemplate)`（已有纯函数）。
- `QuotationIdContext`：预取阶段仍 `set(quotationId)`（整单同一 quotationId，无歧义）。

### 2.3 哪些路径**不走 union、必须保留逐行**（精确判定）

预取层对某 driver 组件**纳入 union** 的充要条件（全部成立）：
1. `bom_recursive_expand == true`（否则走 `expand()` 单值，不是 C4 现场）；
2. driver 视图**非 composite 聚合视图**：`!(path.contains("v_composite_child_") || path.contains("composite_child_"))`（复用 `expandForPartSet:633` 的同款判定，保证与回落逻辑一致）；
3. driver 视图 sql_template **不含 `:spineKeys`**（`!SpineKeysMacro.containsMacro(sqlTemplate)`，§2.2(5) 不变量）；
4. （冗余但显式）driver 视图 sql_template **不含 `:lineItemId`**（核价侧实测恒不含；显式挡门，含则回落逐行）。

不满足任一条 → **该组件不进 unionByComp**，`expandTemplateDriverBaseRows` recursive 分支对它 `containsKey==false` → 回落 `expandForPartSet`（逐行、带 lineItemId/SpineKeysContext），**与改动前逐位一致**。

> 当前数据：唯一 recursive 组件 `COMP-0019 $cz_view` 四条全满足 → 纳入 union。其余 4 个 recursive=false 组件天然不进（条件1 不满足），其中 `COMP-0020 $ys_view` 的 spineKeys 语义完全不受影响（它走 `expand()` 单值 + per-li SpineKeysContext，本次零改动）。

---

## 3. 逐条等价/风险点处理设计 + 为何等价

### 3.1 AP-37 串卡：分发必须按 (lineItemId, partNo) / spine 节点严格配对

- **机制**：union 后 `Map<partNo, resp>` 是**全行共享的同一份 superset**。各行的 `buildSpineBaseRows(closure_li, byPart)` 以**本行 closure_li.spine** 为行主轴（`:1217`），逐 spine 节点用 `node.hfPartNo` 去 `byPart.get(node.hfPartNo)` 取业务行（`:1218`）。
- **配对键 = (本行 spine 节点) → partNo**：行的「哪些料号、各几行、什么顺序」**完全由本行自己的 spine 决定**，byPart 只是按 partNo 提供候选业务行。两行即便共享同一子料号，也各自按各自 spine 取、各自深拷贝，**不会把行 A 的 spine 结构带给行 B**。
- **为何不会串卡**：`byPart` 的 key 是 `hf_part_no`（视图返回行的料号），value 是该料号的业务行集合。这正是 `cz_view` 的 customer×material 共享语义——**同一 (customer, material) 的业务行本就该对所有引用它的行相同**（AP-53）。逐行查时每行也是查 `ANY(partSet_li)` 再按 hf_part_no 配，union 只是把候选集扩大为并集，**本行实际取用的仍是 `node.hfPartNo` 命中的子集**，与逐行查同 partNo 得到的行**逐位相同**（同一视图、同一 customerCode、无行维度谓词）。
- **绝不按 backend `hf_part_no` 跨行直配整份 resp**：分发不是「把 resp 整体挂给某行」，而是「按本行 spine 节点逐个 `get(partNo)`」——配对粒度是 spine 节点，不是行。这与 `expandMulti` 内部 `:579-598` 按 `hf_part_no` 分桶是同一套语义，无新增串号面。

### 3.2 composite 聚合视图逐料号回退不得被吞

- §2.3 判定条件 2 显式排除 composite 视图进 union。composite recursive 组件（若存在）→ `containsKey==false` → `expandTemplateDriverBaseRows` 回落 `expandForPartSet(.., li.id, compositeType)`（`:1186` 原调用），其内部 `compositeAgg` 分支（`:636`）逐料号 `expand()` 带 lineItemId/compositeType，**与改动前 100% 一致**。
- 当前无核价组件命中此分支（实测 0 composite recursive），故是「防御性保留」而非「当前必经」。

### 3.3 AP-51 行数：union 的 ANY/IN 谓词不得改变任何行的行数

- 行数权威 = `buildSpineBaseRows` 里 `Σ_{node ∈ closure_li.spine} max(1, byPart.get(node.hfPartNo).rows.size())`（`:1217-1226`，节点无业务行补 1 空行）。
- union 只改变 `byPart` 的**键集大小**（从单行 partSet 扩到并集），**不改变任一 `byPart.get(partNo).rows` 的内容/条数**——因为 `cz_view` 无行维度谓词，`ANY(union)` 返回的某料号的行集 = `ANY(partSet_li)` 返回的同料号行集（同 customer、同 is_current、同 system_type）。
- 逐位一致论证：对任意 spine 节点 `node`，逐行查得 `respA.get(node.hfPartNo).rows`，union 查得 `respU.get(node.hfPartNo).rows`。二者 SQL 仅 `ANY(...)` 列表不同，而 `hf_part_no = ANY(L)` 对固定 `node.hfPartNo ∈ L` 的命中行**与 L 的其余元素无关**（PG ANY 是 OR 等价，无 JOIN/聚合放大）→ 同 partNo 行集逐位相同 → `max(1, size)` 相同 → 行数、行序逐位一致。
- **验收**：§5 的「连跑两次 + A/B md5」对 `costing_card_values` 各 Tab `baseRows` 长度逐位比对。

### 3.4 🔑 可变共享面（最关键风险）：整条分发链只读 + 深拷贝点

**共享面**：union 后 `unionByComp.get(cid)` = 一份 `Map<partNo, ExpandDriverResponse>`，被全 N 行的 `buildSpineBaseRows` 反复读。`expandMulti:589` 把 `row.driverRow = driverRow`（`loadByPath` 返回的原始 Map 引用），`row.basicDataValues` 是 expandMulti 新建的 LinkedHashMap（`:590`）。这些 `Row` 对象现在被 N 行共享。

**审计要求（落地必做，逐点列）**——分发链 `unionByComp → buildSpineBaseRows → spineRowNode → rowToNode` 全程必须只读 `Row.{driverRow,basicDataValues}`：
1. `buildSpineBaseRows`（`:1214`）：只读 `resp.rows`、`r`，不 mutate。✅ 现状只读。
2. `spineRowNode(node, bizRow)`（`:1231`）：`bizRow!=null` 时 `rowToNode(bizRow)`；空行时新建 ObjectNode。不 mutate bizRow。✅。
3. **`rowToNode`（`:683`）= 唯一深拷贝点**：`MAPPER.valueToTree(row.driverRow)` / `valueToTree(row.basicDataValues)` —— **valueToTree 每次新建独立 JsonNode 树（深拷贝）**，后续对返回 ObjectNode 的 `put("__nodeId"...)`（`:1241`）只改**拷贝**，不碰原 `Row`。✅ **这是等价性的物理保证**：每行各持独立 JsonNode 副本，绝无把同一可变 `Row` 挂到多行。
4. 下游 `assembleTabsWithFormulaResults` 系列只读 `baseRows`（ArrayNode，已是拷贝），公式求值不回写 driverRow。✅（与现状逐行路径同一套，无新增写点）。

**结论**：深拷贝点 = `rowToNode`（`MAPPER.valueToTree`），**已存在、无需新增**。union 不引入新的 mutate 点，只是放大了「若有人未来在分发链上就地改 Row 会串污染」的影响面。落地 PR 必须：
- grep 分发链确认无 `Row.driverRow.put(...)` / `Row.basicDataValues.put(...)` 等就地写（除 expandMulti 自身构建期）；
- 在 `rowToNode` 加注释钉死「分发链对 Row 只读，深拷贝由本方法保证，勿在上游 mutate 共享 Row」；
- §5「连跑两次 md5 一致」专项验证此面（若有就地 mutate，连跑两次结果会因执行序漂移）。

### 3.5 AP-53：核价 V6 视图无 quotation_line_item_id 维度（union 安全前提）的验证

- **前提**：union 把「每 li 各查」变「全 li 共享一份按 partNo 分桶」，等价的充要条件 = 视图结果只依赖 (customerCode, hf_part_no)，**不依赖 lineItem**。
- **如何验证对本次所有涉及视图成立**（落地前 + CI 守门）：
  1. 一次性 SQL 普查（已在本设计执行）：`SELECT sql_view_name FROM component_sql_view WHERE sql_template LIKE '%quotation_line_item_id%' OR LIKE '%:lineItemId%'` → 当前 **0 命中**（核价侧）。
  2. 对**每个被纳入 union 的 recursive 组件**，落地代码在预取时**断言** sql_template 不含 `:lineItemId` / `:spineKeys` / `quotation_line_item_id` / composite 标记（§2.3 四条闸门），任一不满足 → 该组件回落逐行（不进 union）。这把「前提是否成立」从「人工核一次」升级为「每次运行按视图实际内容自动判定」，杜绝未来视图改写后静默串号。
  3. `$cz_view` 模板已核（§1.4）：仅 `customer_no = :customerCode` + `system_type/characteristic/is_current` 常量谓词，无行维度 → 前提成立。

### 3.6 不并行（硬约束①）

- 本方案是**串行重排查询次序**：把「N 次同视图多值查」合并为「1 次更大 ANY 多值查」，分发仍在原单线程内逐行串行做。**不引入任何并行**（无 ManagedExecutor、无并行 stream、无后台线程）。预取层 step5 的 M_rec 次 expandForPartSet 也是串行 for 循环。
- 与 `cpq-expand-layer-not-threadsafe` 教训一致：不碰 expand 层并发；共享的 `unionByComp` 只在单线程内被串行读 + 深拷贝，无竞态。

---

## 4. union 闸门判定（实现伪码，精确）

```
// 预取层：决定某 recursive driver 组件是否纳入 union
boolean eligibleForUnion(UUID compId) {
    Component c = Component.findById(compId);
    if (c == null) return false;
    if (!Boolean.TRUE.equals(c.bomRecursiveExpand)) return false;          // 条件1：必须 recursive（否则走 expand 单值，非 C4）
    String path = c.dataDriverPath;
    if (path == null || path.isBlank()) return false;
    if (path.contains("v_composite_child_") || path.contains("composite_child_")) return false; // 条件2：composite 回落逐行
    String viewName = extractSqlViewName(path);                            // 复用 ComponentDriverService 同款解析
    ComponentSqlView v = ComponentSqlView.find("sqlViewName", viewName...).firstResult();
    String tpl = (v == null) ? "" : nz(v.sqlTemplate);
    if (SpineKeysMacro.containsMacro(tpl)) return false;                   // 条件3：spineKeys 视图回落逐行（带 per-li context）
    if (tpl.contains(":lineItemId") || tpl.contains("quotation_line_item_id")) return false; // 条件4：行维度回落逐行（AP-53 守门）
    return true;
}
// ⚠ viewName 解析 + ComponentSqlView 查询必须含 componentId 维度消歧
//   （同名视图跨组件，见 cpq-sqlview-cache-key-needs-component-dim）：
//   按 component_id 绑定的那条 sql_view 取 template，不能只按 sql_view_name firstResult（会取到导入副本的错条）。
```

> **componentId 消歧硬要求**：`$cz_view`/`$ys_view` 等视图名在多组件间不唯一（导入副本带 `__imp` 后缀的组件各带同名视图）。闸门取 sql_template **必须按 `component_id = compId`** 精确取（`ComponentSqlView.find("componentId", compId)` 或 join），否则可能读到别的组件的模板做判定 → 误纳/误拒。`expandForPartSet` 自身按 componentId 取 `component.dataDriverPath`，已天然正确；闸门读 sql_template 是新增读点，必须显式带 componentId。

不进 union 的 recursive 组件 → `expandTemplateDriverBaseRows` recursive 分支 `containsKey==false` → 原 `expandForPartSet(compId, customerId, closure.partSet, li.id, compositeType)` 逐行（带 SpineKeysContext，因为外层 `:1175` 仍 set 了本行 spineKeys）→ **逐位等价改动前**。

---

## 5. 可执行的等价验证测试设计

> 总则（并行回滚教训）：正确性必须「连跑两次比对 md5」+ 「A/B 逐行 vs union」双证。本方案不并行（理论确定），但共享 Map 的可变别名面要靠连跑两次兜底。

### 5.1 A/B 等价单测 `CostingPartSetUnionEquivTest`（新增）

- **构造场景**：复用 E2E 测试数据锚点（`cpq-e2e-quotation-flow-test-data`：苏州西门子 + 报价模板0608 + 多核价行）或核价 BOM 锚点（`costing-bom-tree-full-spine-render`：PRICING 多层 BOM 根 `3120018220`，4 层 + DAG 重复子件 `3110520789`）。建一个含 **N≥3 核价行、行间 partSet 有重叠**的报价单（重叠才暴露 union 分发是否串行）。
- **断言**：对同一组 li，
  - 路径 A（旧）：逐行 `buildCostingCardValues(li, ..., null /*无union*/)`；
  - 路径 B（新）：`precomputeCostingDriverUnion(quotationId)` → 逐行 `buildCostingCardValues(li, ..., unionByComp)`；
  - 断言两路产出的 `costing_card_values` JSON **逐位相同**（规范化后 `assertEquals` 字符串，或逐 Tab 逐 baseRow 逐 cell 比对）。重点比 `COMP-0019 $cz_view` Tab 的 baseRows（行数 + driverRow 各列 + basicDataValues + __* 系统列）。
- 同步覆盖：含 spineKeys 的 `COMP-0020 $ys_view` Tab 两路必须也逐位相同（证明 union 没误伤 recursive=false 组件）。

### 5.2 前后 DB md5 diff（改动前后同一份首存）

- 同一份导入 Excel（或同一份 `PUT /draft` 触发 snapshotLineValues），在**改动前（git stash）**与**改动后**各跑一次首存，逐表稳定 md5 全等：
  - `costing_card_values`（核心）：`md5(string_agg(... ORDER BY line_item_id))`；
  - `costing_excel_values`、`quote_card_values`、`quote_excel_values`（守报价侧隔离未被重排破坏）；
  - `quotation_line_item`（snapshot_rows + cardSnapshotAt 除外的值列）、`quotation_view_structure`。
- 任一表 md5 不等 = 回滚不合入。

### 5.3 连跑两次 md5 一致（专项覆盖 §3.4 可变共享面）

- 同一份首存场景，**连续跑两遍**（各自清空相关 li 的 4 个快照列 → 重新 snapshotLineValues），两遍 `costing_card_values` md5 必须一致。
- 这是检测「共享 Map 被就地 mutate 导致执行序敏感」的唯一手段——若有跨行串污染，两遍因行处理序/缓存命中序不同会 md5 漂移。

### 5.4 往返度量（仿 P1 集成测试手法）

- 复用 `MaterialMasterBatchImportIntegrationTest` 的 `Statistics.getPrepareStatementCount()` 模式：在 `@QuarkusTest` 里包住首存（`snapshotLineValues` 全 N 行 / 整单预取路径），`stats.clear()` → before → 跑 → after，打印 prepared 差。
- **新旧对比**：同测试在新代码 + `git stash` 旧代码各跑一次（命令行两遍），核价 driver 往返应从 ~N×M_rec 降到 ~M_rec。
- 补充（可选，更准）：用 PG `pg_stat_statements.calls` 前后增量按 `$cz_view` 语句维度计真实服务端往返（应用层 Statistics 含 evictAll/子事务噪声）。
- **门槛**：往返数确实下降 **且** §5.2 md5 全等，二者同时满足才算 C4 达标。

### 5.5 既有套件全绿（门禁）

`SnapshotReconcileTest`（值对账）/ `CardValuesSnapshotTest` / `CardSnapshotFreezeTest` / `SubmitFreezeSnapshotTest` / `ConfigureProductServiceTest` 全绿；E2E `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE，验 composite 回落分支未被 union 误吞）双 spec `passed` + `'加载中' final=0`。

### 5.6 在 worktree 里如何跑（共享约束）

- 后端测试在 **worktree 的 `cpq-backend`** 里跑（`cpq-worktree-maven-test-tree`：`./mvnw` 在 cpq-backend/，子代理 cd 主仓会测错树报假绿）：
  `cd <worktree>/cpq-backend && ./mvnw test -Dtest=CostingPartSetUnionEquivTest,SnapshotReconcileTest,...`
- A/B 往返对比用 `git stash` 在 worktree 内切新旧（worktree 隔离 git 工作区，dev server/DB/node_modules 共享，不另起 server）。
- 主线必须**亲验**测试/DB md5/E2E（`cpq-deliver-agents-overreport`：实现 agent 会虚报完成）。

---

## 6. 风险评级 + 回滚点

### 6.1 风险评级：**低**（修正总纲的「中低」）

| 维度 | 评估 |
|---|---|
| 触碰核心基线 | 仅核价侧 `CardSnapshotService`（editRows 恒空、与报价侧物理隔离），不碰报价渲染/AP-41 双视图对齐 |
| union 服务的视图行维度 | 当前唯一组件 `$cz_view` 无任何行维度谓词（AP-53 customer×material 共享），union 天然等价 |
| spineKeys/composite/lineItemId | 全在 recursive=false 分支，本次零改动；四条闸门 + componentId 消歧自动守门 |
| 深拷贝 | 复用现成 `rowToNode`（valueToTree），无新增 mutate 点 |
| 并发 | 串行重排，无并行 |
| 最大残余风险 | ① 共享 Map 可变别名（§3.4，靠连跑两次 md5 兜底）；② 若选「ImportExecutionService 重排报价侧顺序」形态，报价侧 snapshot_rows 时序需重验（§2.2(2)）——**故推荐「lazy 请求级核价 union 缓存」最小破坏形态，规避此项** |

### 6.2 回滚点（分层，越往后越彻底）

1. **运行时闸门回滚**：四条 `eligibleForUnion` 闸门任一收紧即让对应组件回落逐行；极端可让 `eligibleForUnion` 恒返 false → 全组件逐行 = 改动前行为（保留新代码但行为等价旧路径）。
2. **入参回滚**：`buildCostingCardValues`/`expandTemplateDriverBaseRows` 的 `unionByComp` 传 null → 走旧逐行分支（签名零破坏 delegate，与 `assembleTabsWithFormulaResults` 多重载同款纪律）。
3. **整段回滚**：`git revert` 本特性分支 commit（独立 worktree 分支，未合 master 前零影响）；合 master 后单 commit revert。
4. **守门失败即不合入**：§5 任一对账（A/B 逐位 / 前后 md5 / 连跑两次 / 既有套件 / 往返下降）不过 → 不合入。

---

## 7. 落地顺序建议

1. 先实现「lazy 请求级核价 union 缓存」最小破坏形态（§2.2(2) 保守降级）：不改 N-loop 结构、不动报价侧顺序，仅核价侧 `buildCostingCardValues` 首次触发整单 union 预取。
2. 写 `CostingPartSetUnionEquivTest`（§5.1）+ 往返集成测试（§5.4），TDD 红→绿。
3. 跑 §5 全套对账（A/B + 前后 md5 + 连跑两次 + 既有套件 + E2E 双 spec）。
4. 全绿 + 用户确认 → `superpowers:finishing-a-development-branch` 合 master + 清 worktree。

> 若后续实测首存仍不达标且报价侧顺序重排经验证安全，再升级到 §2.2(2) 方案 A 的「全行预取 + 二次循环」形态进一步省「每行重查模板组件清单」的 N 次小查。
