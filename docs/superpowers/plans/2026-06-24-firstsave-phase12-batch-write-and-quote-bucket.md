# 报价单首存性能优化 — Phase 1（批量写）+ Phase 2（报价侧合桶）实现计划

> 立项 2026-06-24。worktree `perf-firstsave-phase12`（分支 `worktree-perf-firstsave-phase12`，基于 master `d306bbd`）。
> 本计划**只设计 + 拆任务 + 定 A/B 等价验证**，由 subagent-driven 逐 Task 执行。
> 唯一战场：**报价侧首存** `ConfigureSnapshotService.snapshotLines`（saveDraft 经 `QuotationResource.saveDraft → snapshotService.snapshotQuotation(id,true)` 触发）。
> 上游设计：`docs/superpowers/specs/2026-06-23-import-and-firstsave-perf-optimization-design.md` §P3-C1；交接：`docs/handover/2026-06-24-perf-optimization-handover.md`。
> 核价侧 P2-C4 union 已落地（`CardSnapshotService.precomputeCostingDriverUnion`），本计划复刻其套路到报价侧。

---

## 0. TL;DR

| 项 | 现状（实测，罗克韦尔 `8f0c37a4`，170 行/77 料号） | 改造后 | 风险 |
|---|---|---|---|
| 报价侧首存（冷，全量 expand+写） | **69s** | **~2-4s** | — |
| ├ driver 读 1190 次逐个 expand | ~36s | 合桶 8 组件 × expandMulti(77) ≈ **0.6s** | 高（Bug B 雷区） |
| └ 写盘+物化 1190 次 REQUIRES_NEW | ~33s | 单条多值 UPSERT ≈ **~2s** | 低 |

两阶段独立、可分别 kill switch：
- **Phase 1（批量写，低风险）**：`writeSnapshot`/`writeRowData` 每 (行×组件) 一个 `REQUIRES_NEW`（UPDATE 命中 0 再 INSERT）→ **整行一次"批量 UPDATE…FROM VALUES + 未命中多值 INSERT"**（已核实表**无** `UNIQUE(line_item_id,component_id)`，不能用 ON CONFLICT，走两段式等价批量）；`loadSnapshotRowsByComp` 每行一查 → **整单一次查**。
- **Phase 2（报价侧合桶 P3-C1，高杠杆/Bug B 雷区）**：`snapshotLines` 内层逐 (行×组件) `expand` → **每个可合组件一次 `expandMulti(全部不同料号)`**，按 `hf_part_no` 回分；闸门 `viewUsesLineItemId`/`eligibleForBomUnion` 守 Bug B，不可合的组件**逐行回落 `expand`**。

---

## 1. 现状链路图（精确到方法/行）

文件：`cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java`

```
QuotationResource.saveDraft (:113)
  → snapshotService.snapshotQuotation(id, true)             // 增量(skipRowsWithSnapshot=true)
      → loadQuotationLines(:130)  [REQUIRES_NEW, 1 查/单]    // (id, productPartNo, compositeType)×N
      → snapshotLines(quotationId, lines, true) (:158)
          evictAll()  (:164)                                 // 冷跑：清 driver 进程缓存，冻结"当前"基础值（硬约束④保留）
          loadCustomerId(:165) [REQUIRES_NEW]
          loadDriverComponents(:166) [REQUIRES_NEW]          // M 个 driver 组件 (id,name,driverPath)
          loadComponentsSnapshot(:177) [SUPPORTS, 1 查/单]    // 物化用模板 snapshot
          for 每行 li (N):
            ── 增量跳过判定 ──
            if skipRowsWithSnapshot && !lineNeedsExpand(driverCompIds, loadSnapshotRowsByComp(li)) (:184-188)
                  → loadSnapshotRowsByComp(:115) [REQUIRES_NEW, 1 查/行]   ← Phase1-改造点②
                  continue
            ── 读 + 写（逐组件）──
            for 每 driver 组件 comp (M):                       ← Phase2-改造点（核心）
                expand(comp.id, customerId, partNo, null,null,null, li.id, compositeType) (:193)
                                                              ← N×M 次逐(行×组件)远程 expand（36s）
                writeSnapshot(li.id, comp.id, comp.name, rowsJson) (:197) [REQUIRES_NEW] ← Phase1-改造点①a
            ── 物化 row_data（逐组件）──
            materializeRowData(li.id, componentsSnapshot, snapByComp) (:205)
                → materializeLineRowData → computeLineRowData(纯算，拓扑序)
                → for 每组件: writeRowData(li.id, comp.id, json) (:373) [REQUIRES_NEW] ← Phase1-改造点①b
```

**首存语义**：罗克韦尔 170 行全是新行（无 snapshot_rows）→ `lineNeedsExpand` 恒 true → 增量救不了首存 → N×M 全量 expand + N×M×2 个 REQUIRES_NEW 写（snapshot + row_data）。

**关键事实（已实测，采信）**：
- 报价视图（`$ys_view`/`$cp_view`/…）只用 `:customerCode + hf_part_no=ANY()`，**不含 `:lineItemId`** → `viewUsesLineItemId(comp,path)=false` → 可合桶。
- `f≈0`：BASIC_DATA 叶字段全部命中各自 driver mirror 视图 declared_columns → `evaluatePath` 全短路、零跨表往返。瓶颈纯是 **(行×组件)展开次数 × 串行 × 冷跑 + 逐组件 REQUIRES_NEW 写**。

---

## 2. 设计

### 2.1 Phase 1 — 批量写（低风险，不碰 expand/Bug B）

#### 改造点①a — `writeSnapshot` 批量化（新方法 `writeSnapshotBatch`）

现状（:597-619）：每组件一个 `REQUIRES_NEW`，UPDATE 命中 0 → INSERT；语义 = **更新 snapshot_rows，保留编辑层 row_data**（`tab_name = COALESCE(tab_name,:tab)`）。

**🚨 已核实（2026-06-24，blocking 修正）**：`quotation_line_component_data`（V11 :77-88）只有 `id UUID PRIMARY KEY`，**无 `UNIQUE(line_item_id, component_id)`**，且 **`component_id` 可空**（:80），仅有普通索引 `idx_qlcd_line(line_item_id)`。遍历 V11/V262/V269/V301 全部 qlcd 迁移确认**无任何后续唯一约束**。→ **`ON CONFLICT (line_item_id, component_id)` 不可用**（PG 报 "no unique or exclusion constraint matching ON CONFLICT")。现状逐行代码用"UPDATE…WHERE；命中 0 再 INSERT"正是因为没有唯一约束。

**两条可行路线（二选一，Task 1 评审决定）**：

- **路线 A（推荐，零 schema 变更，纯等价批量）**：保留"先批量 UPDATE，再对未命中的批量 INSERT"两段式（与逐行 UPSERT 语义 1:1，无需唯一约束）：
  1. **批量 UPDATE**（一条 `UPDATE … FROM (VALUES …)`，PG 支持，注意 NULL 列 `::jsonb`/`::text` 显式 cast，参照 P2-Q05 VALUES NULL 陷阱）：
     ```sql
     UPDATE quotation_line_component_data d
        SET snapshot_rows = v.rows, snapshot_at = NOW(),
            tab_name = COALESCE(d.tab_name, v.tab)
       FROM (VALUES (:cid0, CAST(:rows0 AS jsonb), :tab0), (:cid1, CAST(:rows1 AS jsonb), :tab1), …)
            AS v(component_id, rows, tab)
      WHERE d.line_item_id = :lid AND d.component_id = v.component_id
     RETURNING d.component_id;   -- 拿回命中的 component_id 集合
     ```
  2. 对 RETURNING **未覆盖**的 component_id，一条**多值 INSERT**（无 ON CONFLICT）：
     ```sql
     INSERT INTO quotation_line_component_data (line_item_id, component_id, tab_name, snapshot_rows, snapshot_at)
     VALUES (:lid, :cidX, :tabX, CAST(:rowsX AS jsonb), NOW()), …
     ```
  - **首存全新行**：UPDATE 命中 0 → 全部走多值 INSERT（1 条/行）。**高频空存复用行**：全命中 UPDATE（1 条/行）。**从 N×M×2 → N×(1~2)**。
  - ⚠️ `component_id IS NULL` 行：driver 组件 component_id 恒非空（loadDriverComponents 过滤了 NULL driver path 组件），本批不涉及 NULL component_id 行，UPDATE 的 `= v.component_id` 安全。

- **路线 B（加唯一约束后用 ON CONFLICT，需 architect + 数据体检）**：Flyway 加 `CREATE UNIQUE INDEX … ON quotation_line_component_data(line_item_id, component_id)`（component_id 非空才行 → 还要先确认无 NULL component_id 行 + 无 (li,cid) 重复行）。**否**——schema 变更 + 既有数据风险 + DDL 后须重启 Quarkus（CLAUDE.md 视图 DDL 纪律），收益不抵；除非路线 A 实测仍慢。

**决策：路线 A**（零 schema 变更、纯等价、无唯一约束依赖）。备选 B 记录在案。

新增方法（保留旧 `writeSnapshot` 不删，渲染/单测仍可用）：

```java
@Transactional(Transactional.TxType.REQUIRES_NEW)
public void writeSnapshotBatch(UUID lineItemId, List<SnapRow> rows)   // rows: {componentId, tabName, rowsJson}；路线 A 两段式
```

事务粒度 = 整行（M 个组件 = 1 个事务的 1 UPDATE + 至多 1 INSERT），N 行 = N 个事务。

**NULL 处理**：`rowsJson==null` → VALUES 该元素列用 `NULL::jsonb`（显式 cast，否则 PG "could not determine data type"，同 P2-Q05 陷阱）。逐元素拼 `CAST(:rowsK AS jsonb)` 或 `NULL::jsonb`。

**整单 vs 整行权衡**：选**整行**（N 事务，每事务 M 行 VALUES）——隔离粒度 = 行，某行失败不连坐其它行，保留"逐组件隔离"精神（降级到行级）。备选整单（1 事务 N×M 行 VALUES）最少往返但失败连坐整单 + 单语句参数过多（170×8×3 参数）→ **否**。

#### 改造点①b — `writeRowData` 批量化（新方法 `writeRowDataBatch`）

现状（:572-590）：每组件一个 `REQUIRES_NEW`，UPDATE row_data 命中 0 → INSERT。语义 = **更新 row_data，保留 snapshot_rows**（互不清零）。同样**无唯一约束**，用路线 A 两段式：

```java
@Transactional(Transactional.TxType.REQUIRES_NEW)
public void writeRowDataBatch(UUID lineItemId, Map<UUID, ArrayNode> byComp)  // 整行物化结果
```

```sql
-- ① 批量 UPDATE（只更 row_data，保留 snapshot_rows）
UPDATE quotation_line_component_data d SET row_data = v.rd
  FROM (VALUES (:cid0, CAST(:rd0 AS jsonb)), …) AS v(component_id, rd)
 WHERE d.line_item_id = :lid AND d.component_id = v.component_id
RETURNING d.component_id;
-- ② 未命中的多值 INSERT
INSERT INTO quotation_line_component_data (line_item_id, component_id, row_data, created_at)
VALUES (:lid, :cidX, CAST(:rdX AS jsonb), NOW()), …
```

> 实务上 `writeSnapshotBatch` 已先建行 → `writeRowDataBatch` 的 UPDATE 几乎全命中，INSERT 段通常空。

**改造点①b 调用方**：`materializeLineRowData`（:363）末尾 `for...writeRowData` 循环（:371-378）→ 改为一次 `self.writeRowDataBatch(lineItemId, byComp)`。`computeLineRowData`（纯算，拓扑序累积）**不动**——它已经先算齐整行各组件再落库，天然适配批量。

> ⚠️ 顺序不变性：`writeSnapshotBatch` 必须在 `writeRowDataBatch` 之前（先建行写 snapshot_rows，再 UPDATE row_data）。但因都是 `ON CONFLICT DO UPDATE`，先后皆能自建行；保持现状顺序（snapshot → materialize）即可。

#### 改造点② — `loadSnapshotRowsByComp` 整单一次查（新方法 `loadSnapshotRowsByLines`）

现状（:115）：每行一次 `WHERE line_item_id = :li`，saveDraft 增量路径 N 次。

新增：

```java
@Transactional(Transactional.TxType.REQUIRES_NEW)
public Map<UUID, Map<UUID, String>> loadSnapshotRowsByLines(Collection<UUID> lineItemIds)
// SELECT line_item_id, component_id, snapshot_rows
//   FROM quotation_line_component_data WHERE line_item_id IN (:lis)
// → 外层 lineItemId → (内层 componentId → snapshot_rows)
```

`snapshotLines`（:158）在 N-行循环**之前**一次调用，循环内 `lineNeedsExpand(driverCompIds, byLine.getOrDefault(li.id, Map.of()))` 查内存 map，删掉循环内的 `loadSnapshotRowsByComp(li)`。

> 收益主要在**高频防抖空存**（170 行全已快照时 N 次跳过查 → 1 次）；首存全新行此查命中空、仍走 expand，但首存也省了 N 次往返。

#### Phase 1 kill switch

`cpq.firstsave-batch-write`（默认 `true`，kill `-Dcpq.firstsave-batch-write=false` / `CPQ_FIRSTSAVE_BATCH_WRITE=false`），照 `ComponentResource` 的 `cpq.batch-expand-bucket`（:200-202）读法。Off → 走旧逐行 `writeSnapshot`/`writeRowData`/`loadSnapshotRowsByComp`。

### 2.2 Phase 2 — 报价侧合桶（P3-C1，高杠杆，Bug B 雷区）

#### 核心改造：`snapshotLines` 内层逐 (行×组件) expand → 整单一次 expandMulti（按组件）

新增整单预取（仿 `precomputeCostingDriverUnion`，但报价侧分桶维度是**全部不同料号**，非 BOM 闭包并集）：

```java
/** 报价侧整单合桶预取：对每个 eligibleForQuoteBucket 组件一次 expandMulti(全部不同 partNo)，
 *  → Map<componentId, Map<partNo, ExpandDriverResponse>>。不 eligible 的组件不进 Map → 调用方逐行回落 expand。*/
private Map<UUID, Map<String, ExpandDriverResponse>> precomputeQuoteDriverBuckets(
        UUID quotationId, UUID customerId, List<DriverComp> comps, List<Map<String,Object>> lines)
```

实现：
1. 收集**全部不同料号** `distinctPartNos`（按 `li.productPartNo`，去重、确定序——用 `LinkedHashSet` 保插入序；DataLoader.stableSort 已统一视图行序，无需额外排序料号但去重确定）。
2. 对每个 `comp`：闸门判定（见下）→ eligible 才 `expandMulti(comp.id, customerId, distinctPartNos, null, null, null)` 一次 → 存 `Map<partNo, resp>`；不 eligible 不进 map。
3. 报价侧**无 BOM 闭包**（与核价 P2-C4 区别）：分桶键就是产品料号本身，`expandMulti` 已按 `hf_part_no` 回分（:578-598）。

`snapshotLines` 内层循环改为：

```java
Map<UUID, Map<String, ExpandDriverResponse>> buckets =
    bucketEnabled ? precomputeQuoteDriverBuckets(quotationId, customerId, comps, lineItems) : Map.of();
for (Map<String,Object> li : lineItems) {
    ... 增量跳过判定 ...
    for (DriverComp comp : comps) {
        ExpandDriverResponse exp;
        Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
        if (bucket != null) {
            // 合桶命中：按本行 partNo 取（expandMulti 已保证每个 partNo 都有 entry，0 行也返空 resp）
            exp = bucket.get(partNo);
            if (exp == null) exp = emptyResp();   // 防御：理论上 expandMulti 已预置全 partNo
        } else {
            // 不 eligible → 逐行回落（保 Bug B + lineItemId 隔离 + composite 语义）
            exp = componentDriverService.expand(comp.id, customerId, partNo, null,null,null, li.id, compositeType);
        }
        // ⚠️ AP-37 配对：bucket.get(partNo) 是按 hf_part_no 取（合桶天然按 partNo 分），
        //    报价侧"重复料号共享行"是期望语义（同料号产品卡行内容相同）。但写入各行必须
        //    各自深拷贝 rowsJson（见下"可变共享面"）。
        String rowsJson = MAPPER.writeValueAsString(exp != null && exp.rows != null ? exp.rows : List.of());
        snapByComp.put(comp.id, rowsJson);  // 收集整行，配合 Phase 1 writeSnapshotBatch 一次写
    }
    writeSnapshotBatch(li.id, snapRowsOf(snapByComp));   // Phase 1
    materializeRowData(li.id, componentsSnapshot, snapByComp);
}
```

#### 闸门 — `eligibleForQuoteBucket(componentId)`（守 Bug B，精确）

报价侧合桶充要条件（仿 `eligibleForBomUnion` 四闸门，但报价无 BOM 递归维度）：

| 闸门 | 条件 | 不满足 → |
|---|---|---|
| ① 非 EXCEL | `componentType != "EXCEL"`（EXCEL 不走 driver expand，:269） | 回落（实际它本就返 0 行，统一逐行更安全） |
| ② 有 $view 路径 | `extractSqlViewName(dataDriverPath) != null`（非 $view 路径保守逐行） | 回落 |
| ③ 非 composite 聚合视图 | path 不含 `v_composite_child_` / `composite_child_`（COMPOSITE 父级聚合 + lineItemId 注入语义，不能跨行合，见 expand :371-411） | 回落逐行（带 li.id + compositeType + childLineItemIds） |
| ④ 视图不含 `:lineItemId` / `quotation_line_item_id` / `:spineKeys` | sql_template 按 componentId 精确取（同名视图跨组件串号，记忆 `cpq-sqlview-cache-key-needs-component-dim`），不含行维度宏 | 回落逐行（保 Bug B EMPTY 不 fallback + lineItem 专属行隔离） |

**复用现成判定**：闸门④可直接复用 `eligibleForBomUnion` 的判定体（它已查 sql_template 含 `:lineItemId`/`quotation_line_item_id`/SpineKeysMacro，:719-735），但 `eligibleForBomUnion` 额外要求 `bomRecursiveExpand==true`（闸门 0）——报价侧 driver 组件**多数 `bomRecursiveExpand=false`**，故**不能直接调** `eligibleForBomUnion`。

**决策：新增 `eligibleForQuoteBucket(UUID componentId)`** 于 `ComponentDriverService`，逻辑 = `eligibleForBomUnion` 去掉 `bomRecursiveExpand` 闸门、保留 ②③④。两方法可共享私有 helper `viewHasNoRowDimension(componentId, path)` 抽出闸门 ②③④（避免重复、避免 drift）。

> 备选方案：①直接在 `snapshotLines` 内用 `viewUsesLineItemId(comp.id, dp)` 单闸门（batchExpand 渲染侧就这么做的，:270/282）。**否**——`viewUsesLineItemId` 只查 `:lineItemId` 占位符，**漏** composite 聚合视图（含 lineItemId 注入但不一定有 `:lineItemId` 占位符）和 `:spineKeys`，不够严。②照搬 `eligibleForBomUnion`。**否**——多带 bomRecursiveExpand 闸门把报价组件全挡掉，合桶失效。**选新增 `eligibleForQuoteBucket` + 共享 helper**。

#### 各分支回落判定与等价性

| driver 类型 | 闸门 | 路径 | 等价性论证 |
|---|---|---|---|
| 普通 `$ys_view`/`$cp_view`（报价主路径，`hf_part_no=ANY`） | eligible | `expandMulti(distinctPartNos)` 一次，按 hf_part_no 回分 | 视图不含 lineItemId → `expand(li.id)` 注入 lineItemHint 但视图无该列 → 与 `expandMulti(null lineItemId)` 结果同（实测 `viewUsesLineItemId=false`）；DataLoader.stableSort 保同 partNo 同序 → 逐位等价 |
| 含 `:lineItemId` 的 per-quote 工序/工艺 mirror | 闸门④挡 | 逐行 `expand(comp.id,…,li.id,compositeType)` | 保 Bug B（专属行不存在返 EMPTY 不 fallback，:393-401）+ lineItem 隔离 |
| COMPOSITE 父级 `v_composite_child_*` 聚合视图 | 闸门③挡 | 逐行 `expand(comp.id,…,li.id,"COMPOSITE",childLineItemIds)` | 保聚合 + IN 谓词 + 主数据合并语义（:402-459）。⚠️ 报价侧 `snapshotLines` 当前调 expand **不传 childLineItemIds**（:193-194 8-arg）→ 现状 COMPOSITE 父级走 full aggregate 分支（:461-470）。**回落必须 1:1 复刻现状调用签名**（8-arg，childLineItemIds=null），不引入新参数 |
| 含 `:spineKeys` 视图 | 闸门④挡 | 逐行 expand | 单行 spine 上下文不可批量设 |
| 无 driver path（虚拟单行） | 闸门②挡（path 为空 → extractSqlViewName=null） | 逐行 expand（虚拟单行语义，:292-352） | 1:1 现状 |

> **关键不变量（硬约束⑤）**：闸门必须**精确**，宁可错挡（回落逐行，慢但正确）不可错放（合桶绕过 Bug B → 串号）。闸门④用 `eligibleForBomUnion` 同款 sql_template 检测（已生产验证）。

#### 可变共享面（硬约束③ + AP-37，必审计）

合桶后 `Map<partNo, resp>` 是**全行共享的同一份**：重复料号的多个报价行从同一个 `resp.rows` 取数。两处保护：
1. **写入前深拷贝**：每行 `MAPPER.writeValueAsString(exp.rows)` 序列化即天然深拷贝（snapshot_rows 落库是 JSON 字符串，各行独立）→ snapshot 落库侧安全。
2. **物化侧**：`materializeRowData` 把 rowsJson `parseRows` 重新反序列化为独立 JsonNode（:334）→ 各行独立 JsonNode 树，`computeLineRowData` 只读不就地 mutate 共享 resp.rows。
3. **审计**：确认 `expandMulti` 返回的 `Row.driverRow`（持 loadByPath 原始 Map 引用，:589）在 snapshotLines 分发链全程**只读**（仅经 `writeValueAsString` 序列化）。**§3.4 连跑两次 md5 专项覆盖**。

#### Phase 2 kill switch

`cpq.firstsave-quote-bucket`（默认 **建议先 `false`，灰度后改 `true`**——Bug B 雷区，先让 Phase 1 单独上）。Off → 内层逐行 `expand`（现状）。

> 决策：Phase 2 上线初期默认 `false`，A/B 全绿 + 真实对拍 + 灰度后单独 PR 改默认 `true`。Phase 1 默认 `true`（低风险）。

---

## 3. 任务拆分（subagent-driven，每 Task 独立可测）

> 每 Task = 实现 + A/B 等价单测 + 自检（在 worktree 的 cpq-backend 跑 mvnw -o test）。Task 间两阶段评审（先 spec 合规、再代码质量）。后端改动后 `touch` java 触发热重载，`curl /q/health` 期望 200/401。

### Task 1 — Phase 1：批量写 `writeSnapshotBatch` + `writeRowDataBatch`
- **前置（已核实）**：`quotation_line_component_data` **无 `UNIQUE(line_item_id, component_id)`**（V11 只有 id PK + idx_qlcd_line），故**走路线 A 两段式批量**（批量 UPDATE…FROM VALUES + RETURNING + 未命中多值 INSERT），不依赖 ON CONFLICT、不改 schema。
- 新增 `writeSnapshotBatch(lineItemId, List<SnapRow>)` + `writeRowDataBatch(lineItemId, Map<UUID,ArrayNode>)`，**路线 A 两段式**（批量 `UPDATE…FROM (VALUES…) RETURNING component_id` + 对未命中 component_id 的多值 `INSERT`，**不用 ON CONFLICT**——表无唯一约束），**只更各自列**（snapshot_rows / row_data 互不清零，tab_name COALESCE）。
- ⚠️ **存量数据**：已核实表内有 **2 个重复 `(line_item_id, component_id)` 对**（component_id 当前无 NULL）。路线 A 的批量 UPDATE `WHERE d.component_id=v.component_id` 会更新**所有**匹配行 = 与现状逐行 `UPDATE…WHERE`（executeUpdate>0 → 不 INSERT）**行为一致**，等价性成立；等价测试需容忍重复行（按 (li,cid) 聚合对账时注意重复对）。
- 加 kill switch `cpq.firstsave-batch-write`（默认 true）。`snapshotLines` 内层 `writeSnapshot` 循环 + `materializeLineRowData` 内 `writeRowData` 循环按 flag 切批量/逐行。
- **测**：`FirstSaveBatchWriteEquivTest`（见 §4 Task 1）。

### Task 2 — Phase 1：`loadSnapshotRowsByLines` 整单一次查
- 新增整单 `WHERE line_item_id IN (:lis)` 重载，返 `Map<UUID,Map<UUID,String>>`。
- `snapshotLines` 循环前一次查，循环内 `lineNeedsExpand` 读内存 map（flag off 回退逐行 `loadSnapshotRowsByComp`）。
- **测**：`LoadSnapshotRowsByLinesEquivTest`（按行聚合 == 逐行查并集）。

### Task 3 — Phase 2：`eligibleForQuoteBucket` 闸门 + 共享 helper
- `ComponentDriverService` 抽 `viewHasNoRowDimension(componentId, path)`（闸门②③④），`eligibleForBomUnion` 改为 `bomRecursiveExpand && viewHasNoRowDimension(...)`（**重构等价，原行为不变**），新增 `eligibleForQuoteBucket = !EXCEL && viewHasNoRowDimension(...)`。
- **测**：`EligibleForQuoteBucketTest`（对库里真实报价模板各 driver 组件，断言闸门分类与"逐行 vs 合桶产出一致"挂钩；含 `eligibleForBomUnion` 重构前后对同组件返回值不变）。

### Task 4 — Phase 2：`precomputeQuoteDriverBuckets` + `snapshotLines` 合桶分发
- 新增 `precomputeQuoteDriverBuckets`（全部不同料号 → 每 eligible 组件一次 expandMulti）。
- `snapshotLines` 内层改合桶命中/回落（§2.2），kill switch `cpq.firstsave-quote-bucket`（默认 **false**）。
- 保留 evictAll 冷跑（:164，硬约束④）+ 防御性深拷贝（写入序列化）。
- **测**：`FirstSaveQuoteBucketEquivTest`（§4 Task 4，含连跑两次 md5 + 往返）。

### Task 5 — 真实数据全链路对拍 + 往返度量 + 既有套件/E2E
- 对 `8f0c37a4`（罗克韦尔）改造前后逐表 md5 全等（§4.5）。
- Statistics 往返 OLD vs NEW（flag 切换同进程）。
- 既有套件全绿 + E2E `quotation-flow.spec.ts`（+ COMPOSITE 单 `composite-product-flow.spec.ts`）。
- 灰度评估后单独决定是否把 `cpq.firstsave-quote-bucket` 默认改 true（独立小 commit）。

---

## 4. A/B 等价验证方案（每 Task 必带）

> 铁律（交接 §七）：A/B 逐位 + 真实对拍 + 连跑两次 md5 + Statistics 往返 + 既有套件全绿。范式 = `CostingPartSetUnionEquivTest` / `BatchExpandBucketEquivTest`。**主线亲跑**（agent 会虚报，记忆 `cpq-deliver-agents-overreport`）；测试在 **worktree 的 cpq-backend** 里跑（`cpq-worktree-maven-test-tree`）。

### Task 1 — `FirstSaveBatchWriteEquivTest`（只读真实数据 + 临时表/事务回滚写）
- **A/B 逐位**：挑库里真实有 snapshot_rows + row_data 的若干 (lineItemId,componentId) 行，在**单事务内**：① 逐行 `writeSnapshot`+`writeRowData` 写到 scratch line（或同事务末 `setRollbackOnly`），dump 落库 JSON；② 批量 `writeSnapshotBatch`+`writeRowDataBatch` 同输入写，dump；断言两路 `(snapshot_rows, row_data, tab_name)` 逐位相等。
- **UPSERT 保留语义专项**：预置一行 `(snapshot_rows=A, row_data=B)`；只调 `writeSnapshotBatch`（新 snapshot_rows=A') → 断言 `row_data` 仍 == B（未清零）；反之只调 `writeRowDataBatch` → snapshot_rows 仍 == A。
- **tab_name COALESCE 专项**：预置 tab_name=T，批量传 tab='X' → 断言仍 T。
- 写测试用 `@Transactional` + rollback 不污染库（或写到一个不存在的 partNo scratch 行后删）。

### Task 2 — `LoadSnapshotRowsByLinesEquivTest`
- 挑真实报价单全行：`loadSnapshotRowsByLines(allLineIds)` 的 `(li → comp → snapshot_rows)` == 对每行 `loadSnapshotRowsByComp(li)` 的并集，逐键逐值。

### Task 3 — `EligibleForQuoteBucketTest`
- 对库里真实报价模板每个 driver 组件：`eligibleForQuoteBucket(comp)` 分类。对**判 eligible 的组件**，断言 `expandMulti(distinctPartNos)` 按 partNo 回分 == 逐 partNo `expand(...,li.id,...)`（取一个真实 li 的 partNo 子集），逐位等价；对**判不 eligible**的组件，确认其 sql_template 确含 `:lineItemId`/`quotation_line_item_id`/`:spineKeys` 或是 composite/EXCEL（白盒断言闸门命中原因）。
- **重构护栏**：`eligibleForBomUnion(comp)` 重构（抽 helper）前后对同一组件返回值不变（用 git stash 旧实现 or 内联对照）。

### Task 4 — `FirstSaveQuoteBucketEquivTest`（核心，仿 `CostingPartSetUnionEquivTest`）
- **A/B 逐位**：选库里含报价模板、≥2 行、含重复料号的报价单。对前若干行：
  - 路径 A（逐行）：`expand(comp.id, customerId, partNo, null,null,null, li.id, compositeType)`，序列化 rows。
  - 路径 B（合桶）：`precomputeQuoteDriverBuckets(...)` 后 `bucket.get(partNo)`，序列化 rows。
  - 断言每 (li, comp) 两路 rows JSON 逐位相等。
- **连跑两次 md5（可变共享专项，硬约束③）**：复用同一份 `buckets`，对同 (li,comp) 再序列化一次 → 断言两次一致（证明分发链不就地 mutate 共享 `resp.rows`/`Row.driverRow`）。
- **回落分支专项**：若该单含 composite/lineItemId 组件，断言它**不进 buckets**（走逐行），且逐行产出 == 现状逐行。
- **重复料号专项**：两行同 partNo → 合桶各取同一 bucket entry，序列化后两行 snapshot_rows 逐位相同（期望语义）。
- **往返度量**（仿 `roundTripReduction_unionVsPerRow`）：Statistics `getPrepareStatementCount`，OLD（逐行 N×M）vs NEW（合桶 M），断言 NEW < OLD。预热一次暖闭包/视图元数据后测。

### Task 5 — 真实数据全链路对拍（`8f0c37a4`）
- **逐表 md5 全等**：对 `8f0c37a4`，flag 切换跑两遍首存（`snapshotQuotation(q,false)` 强制全量），逐表
  `md5(array_agg(... ORDER BY 稳定键))` 全等：
  - `quotation_line_component_data`（snapshot_rows + row_data + tab_name，按 (line_item_id, component_id) 排序）
  - `quotation_line_item`（quoteCardValues / quoteExcelValues / costingCardValues 不在本改造范围，但应不受影响 → 也对账）
- **连跑两次自比对**：同 flag 跑两遍 md5 一致（兜底非确定性；DataLoader.stableSort 已统一行序，理论确定，仍验）。
- **往返**：Statistics OLD（两 flag 全 off）vs NEW（两 flag 全 on）整单首存往返对比。
- **既有套件**：`SnapshotReconcileTest` / `FormulaCalculatorTest` / Versioned 套件 / `CostingPartSetUnionEquivTest` / `BatchExpandBucketEquivTest` 全绿。
- **E2E**：`quotation-flow.spec.ts` `1 passed` + `'加载中' final count = 0`（dev server 跑合并后代码）。

> **既有红测试（非本改动引入，勿当回归，交接 §四-3）**：`DataLoaderTest`(4 err) / `FormulaEvaluateResourceTest`(3 fail) / `VersionedV6MasterDetailTest`(2 err)。跑前 git stash 验证为分支既有红。

---

## 5. 风险登记 + 回滚

| 风险 | 等级 | 缓解 | 回滚 |
|---|---|---|---|
| Bug B 被合桶绕过（闸门错放含 lineItemId 组件 → 串号/EMPTY 错缓存） | **高** | `eligibleForQuoteBucket` 复用 `eligibleForBomUnion` 同款 sql_template 检测（生产验证）；Task 3 白盒断言闸门命中原因；默认 flag false 灰度 | `cpq.firstsave-quote-bucket=false` |
| 合桶共享 `resp.rows` 被就地 mutate → 跨行串污染 | 中 | 写入序列化深拷贝 + materialize 重 parse；连跑两次 md5 专项 | 同上 flag |
| COMPOSITE 父级/`:spineKeys`/虚拟单行回落漏判 | 中 | 闸门②③ 精确挡；回落 1:1 复刻现状 8-arg expand 签名（childLineItemIds=null） | 同上 flag |
| 无 `UNIQUE(line_item_id,component_id)` → ON CONFLICT 不可用（已核实无约束 + component_id 可空） | 已解 | **路线 A**：批量 UPDATE…FROM VALUES + RETURNING + 未命中多值 INSERT，不依赖唯一约束、不改 schema、与逐行 1:1 等价 | `cpq.firstsave-batch-write=false`（走逐行 UPDATE/INSERT） |
| VALUES 列表 NULL 列推不出类型（PG "could not determine data type"） | 低 | NULL 列显式 `NULL::jsonb`/`::text`（同 P2-Q05 陷阱）；首行各列给 cast | flag off |
| 批量语句参数过多（整行 M×3 参数，约 24，安全） | 低 | 整行粒度（非整单）；PG 参数上限 65535 远未触及 | flag off |
| evictAll 冷跑语义被合桶破坏（缓存旧基础值） | 中 | `precomputeQuoteDriverBuckets` 在 `evictAll()` 之后调；`expandMulti` 不进 expandCache（:521 注释）→ 天然冷跑 | flag off |
| 行序非确定性 | 低 | DataLoader.stableSort 已根治（交接 §四-2）；连跑两次 md5 兜底 | — |

**两个 kill switch 默认值**：`cpq.firstsave-batch-write=true`（Phase 1 低风险），`cpq.firstsave-quote-bucket=false`（Phase 2 灰度后改 true）。读法照 `ComponentResource` :200-202。

---

## 6. 预期收益（基于实测）

| | 现状 | Phase 1 后 | +Phase 2 后 |
|---|---|---|---|
| 报价侧首存（罗克韦尔 170 行） | 69s | ~36s（读不变，写 33s→~2s） | **~2-4s**（读 36s→0.6s + 写 ~2s） |
| driver 读 | 1190 次 expand ≈ 36s | 同 | 8 组件 × expandMulti(77) ≈ **0.6s** |
| 写盘+物化 | 1190×2 REQUIRES_NEW ≈ 33s | N 行 × 2 批量事务 ≈ **~2s** | 同 |

效果：首存落客户端超时内 → 快照及时落盘 → 后续 autosave 增量跳过 + 渲染脱钩（batch-expand=0）。

---

## 7. 硬约束自检清单（合入前逐条）

- [ ] 不改任何业务落库/计算结果——行数/列值/版本/is_current/公式逐位一致（§4 md5 全等）
- [ ] 全程单线程，无并行化（硬约束②）
- [ ] 保留 expand/snapshot 防御性深拷贝（写入序列化 + materialize 重 parse）
- [ ] AP-37（按 partNo/task 配对，重复料号共享为期望语义）/ AP-51（行数权威 snapshot_rows，禁 Math.max）/ AP-53（V6 视图无 lineItemId 维度）既有保护未破
- [ ] evictAll 冷跑保留（precompute 在 evictAll 后）
- [ ] Bug B 保护未被合桶绕过（`eligibleForQuoteBucket` 闸门精确，含 lineItemId/composite/spineKeys 全回落逐行）
- [ ] 两 kill switch 可独立关，关后逐位回到现状
- [ ] 既有套件全绿（排除分支既有红）+ E2E `1 passed` + `'加载中'=0`
- [ ] 连跑两次 md5 一致（可变共享面 + 行序兜底）
- [ ] Statistics 往返 NEW < OLD
```
