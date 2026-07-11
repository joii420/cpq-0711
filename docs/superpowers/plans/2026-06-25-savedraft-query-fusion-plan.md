# saveDraft 首存查询融合方案 — 把几千次往返塌缩到几十次

> ⚠️ **2026-06-25 已并入集合化重构**：F1/F4 静态预取已合并 master（`f79e45e`/`3a97256`）;F7/F9/F10/F11 已整合进权威集合化设计 `2026-06-25-savedraft-setbased-rearchitecture.md` §8（F↔E/Phase 映射 + 当前 master 实测校准）。**后续按集合化重构 Phase 2-2/2-3 推进，本文作往返视角的背景参考。**
>
> 日期:2026-06-25
> 目标:消除逐行逐组件的 N+1 远程查询,改为"每类数据整单查一次 → 内存 Map → 循环只读内存"
> 关联:`2026-06-25-rowkeyfields-cache-firstsave-perf-design.md`(方案 B 是本方案 Family-1 的子集)、`2026-06-25-firstsave-perf-roadmap-*.md`、saveDraft 集合化重构(commit `9ee8df6`)
> 硬约束:不触碰 expand/公式/快照求值层并发([[cpq-expand-layer-not-threadsafe]]);落库/计算结果逐位一致;每阶段 Golden md5 卡口

---

## 0. 核心原则(一句话)

**单条查询都很轻**(主键查 ~5–8ms、SQL 视图查 ~20–30ms,几乎全是远程 RTT;返回数据量极小)。**慢在"逐行(170)× 逐组件(~15)发了几千次"。** 融合原则:

> **每一"类"数据,在循环开始前用一条 `IN(...)`/`JOIN` 整单查一次,装进 per-request 内存 Map;循环体内只读内存,零库往返。**

CPU 计算本就 <1s(实测公式求值 101ms),所以这是纯粹的"往返数"工程。

---

## 1. 往返预算(为什么是"几十次"而非"一次")

- 远程 DB RTT **实测空闲 1.6–4.5ms,负载下取 ~6ms/次**(前几版写 8–13 偏高)→ 5s 预算 ≈ **~800 次**;10s ≈ **~1600 次**。
- 不同"类"是不同 SQL 语句(组件元数据 / 视图 / 写),**各融成一条**,终态 ≈ **一打到几十条批量查**,不是字面"一次"。
- 现状几千次 → 目标 **~30–50 次** → 墙钟 ~3–6s(视图查是大头)。

---

## 2. 重复查询清单与逐类融合方案

> 计数基于基准单 `8f0c37a4`(170 行 / 77 料号 / 报价 9 组件 + 核价 6 组件)。"已覆盖"=现有 B2/Phase1 已部分融合。

| # | 查询族 | 现状(代码位置) | 现状次数 | 融合后 | 内存结构 | 难度 | 等价风险 |
|---|---|---|---|---|---|---|---|
| **F1** | 组件行键 `row_key_fields` | `loadRowKeyFields`(CardSnapshotService:1919),assemble 内每行每组件 | **2550** | **1** | `Map<UUID,JsonNode> rkfByComp` | 🟢 秒级 | 无(静态)= **方案 B** |
| **F2** | 模板 `components_snapshot` | `buildCard*`(:674/758)已 B2 预取;但 `loadComponentsSnapshot`(:1533)+ Excel `Template.findById`(ExcelViewService:282/484)**逐行重读** | 报价 build 已 1;Excel/materialize **~170×** | **2**(报价+核价各一次) | `Map<UUID,JsonNode> tmplSnap` | 🟢 秒级 | 无(静态) |
| **F3a** | Excel 模板配置 `excel_view_config` + 模板公式 | `buildLineRowData`:209/215/282,逐行读模板配置/公式 | **~170×** | **1** | `excelCfgByTmpl` | 🟢 秒级 | 无(真静态) |
| **F3b** | ⚠️ Excel **per-line 输入**:`partVersionLocked` / `productAttributeValues` / lineItem compData | `buildRowData`:326/328/330(**行级,不可按模板缓存**) | per-line | 随行走 | **进 `ctx.lines`,严禁进静态 Map** | 🟡 | **误融=AP-52 语义错配** |
| **F4** | driver 组件清单 `SELECT DISTINCT template_component` | `expandTemplateDriverBaseRows`(:1392)+ :505/1321 | **~170×**(EXP_a) | **2** | `List<DriverComp>` per template | 🟢 秒级 | 无(静态) |
| **F5** | 组件实体 `Component.findById`(TAB_JOIN/解析) | ExcelViewService:451/496,逐行逐组件 | 视模板而定 | **1**(整单 IN) | `Map<UUID,Component> compById` | 🟢 秒级 | 无(静态) |
| **F6** | line 级 compData(snapshot_rows + deleted_row_keys) | `buildCardValues`(:689)已 B2 预取;`expandWithSnapshot`(ComponentDriverService:210)读 snapshot_rows **逐行逐组件** | build 已 1;expand 读 **170×N** | **1**(整单 IN by line) | `Map<UUID,Map<UUID,String>> snapByLineComp` | 🟡 中 | 低 |
| **F7** | line 实体 `QuotationLineItem.findById` / `Quotation.findById` | 循环内反复 findById(:423/562/1582/1689…);`Quotation` 同一对象重查 | **170+ 次** | **1 list + 1** | `lines: List<>`(一次 `.list`)+ `q` 提到循环外 | 🟢 秒级 | 无 |
| **F8** | BOM 闭包 `bomClosureService.compute(partNo)` | `buildCostingCardValues`(:769)逐行 | 170(冷),缓存 by partNo | **77**(distinct partNo,已有 Caffeine) | 现有 closureCache | 🟢 已具备 | 无 |
| **F9** | **核价/报价 driver expand(SQL 视图)** | `expandForPartSet`/`expand`(:1417/1422),逐行逐组件;`precomputeQuoteDriverBuckets`/C4 已部分合桶但闸门窄 | **~850** | **~一打**(每组件一次 `IN`) | `Map<UUID,Map<partKey,Rows>> expandByCompPart` | 🔴 难 | **中(Bug B / 行序 / 可变共享)** |
| **F10** | 落库写 snapshot_rows / 物化 row_data | `writeSnapshotBatch`(per line,Phase1 已批)/`materializeRowData`(per line) | 170 批 + 170 | **几条**(整单批量 INSERT) | 收集后一次写 | 🟡 中 | 低(写) |
| **F11** | 删建行(全删子表 + 重建) | `quotationService.saveDraft` 逐行 `clearLineItemChildren` + persist | per line × 子表 | **批量 DELETE…IN + 批量 INSERT** | — | 🟡 中 | 中(并发已有悲观锁) |

**合计:现状 ~数千次 → 融合后 ~30–50 次。**

**待核实/登记的次要查询族(评审补充,落地前确认)**:
- `GlobalVariableService.resolveValues`(gx_view 等若走独立全局变量解析而非视图内 join,可能有 per-row 往返;本单线上 0 gvar 组件,大概率 no-op)。
- `SqlViewExecutor.getColumns`(`ImplicitJoinRewriter.tableColumnsCache` 进程缓存)——稳态已缓存非 per-line;但**冷启动首存有 N 次 getColumns**,登记为"依赖缓存命中,冷态略慢"(见 CLAUDE.md schema DDL 重启教训)。
- `deleted_row_keys`(墓碑):并入 F6 同一条 IN,勿单独查。
- discount/approval/derived-attribute:saveDraft 首存当前不算(Step3 优惠策略半成品),确认不在热路径即可,不必融合。

---

## 3. 内存处理架构:`SaveDraftContext`(一次预取,全程只读)

新增一个 **per-request 上下文对象**,在 saveDraft 循环**开始前**一次性装满,循环体内所有取数改为读它:

```java
final class SaveDraftContext {
    Quotation q;                                  // F7:一次
    List<QuotationLineItem> lines;                // F7:一次 .list
    Map<UUID,JsonNode> tmplSnapById;              // F2:报价+核价
    Map<UUID,Object>   excelCfgByTmpl;            // F3
    List<DriverComp>   quoteDrivers, costingDrivers; // F4
    Map<UUID,Component> compById;                 // F5
    Map<UUID,JsonNode> rkfByComp;                 // F1(= 方案 B)
    Map<UUID,Map<UUID,String>> snapByLineComp;    // F6
    Map<String,BomClosureResult> closureByPart;   // F8(distinct partNo)
    Map<UUID,Map<String,ExpandDriverResponse>> expandByCompPart; // F9(内存回配产物)
}
```

- **预取阶段**(`precompute...`,扩展现有 `CardValuesPrefetch`):每个 Map 一条 `IN`/`JOIN` 查填满。
- **循环阶段**:`buildCardValues`/`buildCostingCardValues`/`buildExcelValues`/落库 全部签名接受 `ctx`,内部 `ctx.rkfByComp.get(cid)` 等**只读内存**,删掉所有 `loadXxx`/`findById` 的库调用(保留 `ctx==null` 回落分支 → 零破坏 + kill switch)。

> 这正是把现有 `CardValuesPrefetch`(已含 `templateSnapshotById`+`compDataByLine`)**长成完整上下文**,与集合化重构同向。

---

## 4. 分阶段落地(按风险/收益排序,对齐集合化重构)

| 阶段 | 融合族 | 收益 | 风险 | 说明 |
|---|---|---|---|---|
| **A. 静态元数据融合** | F1 F2 F3 F4 F5 F7 | 消 ~3000+ 次纯浪费往返(rkf 2550 + 模板/清单/实体逐行) | 🟢 极低 | 全是静态/同值重查,整单一次 IN;**今天就能做**,Golden md5 必等价 |
| **B. 读类融合** | F6 F8 | 消 snapshot_rows/闭包逐行读 | 🟡 低 | closure 已有缓存;snapByLineComp 一次 IN |
| **C. expand 融合(硬骨头)** | F9 | 消 ~850 次视图查 → 一打 | 🔴 中 | `WHERE hf_part_no IN(...)`/`lineItemId IN(...)` 一次查,**内存按 partNo/lineItemId 回配**;守 Bug B + 行序(`DataLoader.stableSort` 已有)+ 可变共享深拷贝(AP-37);**走 architect** |
| **D. 写类融合** | F10 F11 | 批量 INSERT/DELETE | 🟡 中 | Phase1 已部分;扩到整单批量 |

**A 阶段单独就能消掉几千次往返里的绝大多数"纯浪费"**(它们读的是静态元数据,零计算意义)。**C 阶段是 expand 真实 I/O,是 5–10s 地板的决定项。**

---

## 5. 关键:F9 expand 内存回配怎么做(雷区拆解)

现状:`for line: for comp: expandForPartSet(partSet, lineItemId)` → 850 次。

融合:
1. **预取**:每个 driver 组件**一次** `expandMulti`,`WHERE hf_part_no IN (全部 distinct 料号/spine 节点)` + 需要 lineItemId 维度的组件追加 `quotation_line_item_id IN (全部行)`。
2. **内存回配**:把一次查回的大结果集,按 `(partNo)` 或 `(lineItemId, partNo)` 在内存分桶 → `expandByCompPart`。
3. **循环**:`buildCostingCardValues` 从 `ctx.expandByCompPart.get(comp).get(partKey)` 取,**不再 expand**。

**必须守的四条不变量**(否则静默错值,踩 AP-37/Bug B):
- **① 按视图维度分流(评审新增,最关键)**:回配前用现有 `viewHasNoRowDimension` 探针判定——视图**不含** `quotation_line_item_id`/`:lineItemId`/`:spineKeys` → 安全按 partNo 回配;视图**含** line 维 → **保持逐行 / 新写 `(lineItemId,partNo)` 多元组 IN**,**不得**用 partNo 单键(否则拆 Bug B 护栏 → 跨行串数据)。⚠️ `expandForPartSet` 当前只支持 `hf_part_no = ANY`,**lineItemId 只能单值**;多元组 IN 批量**当前不存在,需新写 SQL**。
  > 实测利好:当前 13 个视图全部 `has_line_dim=f`(仅 `ys_view` 带 `:spineKeys`),故对现有数据 partNo 回配大概率等价——但这是**数据巧合非结构保证**,闸门必须留。
- **② Bug B 隔离**:含 lineItemId 维度的组件回配键含 lineItemId(同料号不同行专属工序行不串)。
- **③ 行序确定性**:视图无 ORDER BY → 用已有 `DataLoader.stableSort`(:286)保证 union 与逐行同序、跨运行确定。
- **④ 可变共享深拷贝(落点明确)**:`expand` 主路径 `row.driverRow=driverRow`(:489)是裸引用、缓存命中(:256)直返缓存对象 → 回配分发**必须**在该点 `MAPPER.writeValueAsString` 深拷贝(参照 snapshot 分支 :219 范式),禁裸共享、禁并行。

> 这四条是 C4 union 护栏的推广;F9 = 把 C4 从"少数 eligible 组件"推广到"全部组件 + 按维度分流回配"——即集合化重构 E7/E8/E9 阶段。**不在本方案外单独造,直接并入集合化重构。**

---

## 6. 等价性验证铁律(每阶段必带)

1. **Golden md5**:`GoldenCardValuesEquivTest` 钉死 `8f0c37a4`/`a8f17a74` 四份输出(Q/C/QE/CE),每阶段改完 md5 必命中 + determinism 连跑两次。
2. **往返计数**:每族用精确计数器断言 N→目标(如 F1 rkf 2550→1;F9 expand 850→一打),用缓存/预取 miss 计数,**非** Hibernate 全局统计。
3. **回落等价**:`ctx==null`/kill switch off 时走旧逐行路径,与现状逐位一致。
4. **F9 专项**:必须额外造 **same-partNo 但 lineItemId 有专属行 / partVersion / productAttributeValues 不同** 的单,验证回配不串(现有 golden 这些维度 distinct=1,**对该缺口失明**——评审已警示)。
5. **E2E**:`quotation-flow.spec.ts`(SIMPLE)+ `composite-product-flow.spec.ts`(COMPOSITE),`'加载中'=0`。
6. 主线亲跑测试/查 DB([[cpq-deliver-agents-overreport]]);worktree 子代理可能提交 master,验收查 `git branch --contains`([[cpq-worktree-subagent-commits-to-master]])。

---

## 7. 预期往返与速度(全部基于当前 master 实测,非估算)

> 测量口径:基准单 `8f0c37a4`(170 行),当前 master `4d67620`(**含已合并的 B1 memoize computeRows + B2 批量 EM**),单事务冷态(清 expand/closure 缓存)。深剖探针实测,跑完即删。**纠正前几版"现状 67–76s/视图 JOIN 物理地板"的错误叙事。**

### 7.1 现状逐块实测(按"可融合 I/O" vs "不可约 CPU"分类)

| 块 | 实测耗时 | 性质 | 融合后 |
|---|---|---|---|
| rkf 加载(F1) | **14.7s** | 🟢 可融合 I/O(2550 次单查) | ~0 |
| 核价 driver expand(F9) | **10.3s** | 🔴 可融合 I/O(850 次,RTT 主导;视图执行本身仅 ~10ms/850 条) | ~0.1–2s |
| 模板/清单读(F2/F4) | ~2s | 🟢 可融合 I/O(170× 重读) | ~0 |
| Excel compData 逐行查(F6) | **1.0s** | 🟢 可融合 I/O(`buildExcelValues` 总 1.4s,其中 73.7% 是这条逐行查) | ~0 |
| 落库写(F10:snapshot_rows 4.2s + row_data 4.4s) | **9.1s** | 🟡 可批量 I/O(逐行写,RTT 主导) | ~1–2s |
| **真 CPU**(computeRows + Excel 列求值 0.3s + 序列化) | **~0.8s** | 🟢 不可约,但极小 | ~0.8s |

**关键事实(实测推翻旧叙事)**:
- **DB 往返是唯一大头**;单条查询都很轻(EXPLAIN:cz_view 视图执行 0.17ms,850 条服务端循环仅 10ms)。**所谓"视图 JOIN 物理地板"不存在——地板是 RTT × 往返数,融合后亚秒级。**
- **真 CPU 仅 ~0.8s**:`computeRows` 因 B1(已合并)已 memoize,实测公式求值 101ms、PASS2 组装 217ms;Excel 列求值 309ms;序列化 ≈0。**前几版/外部评审引用的"assemble 33s / Excel 11s"是 B1/B2 前的陈旧数,当前 master 不成立。**

### 7.2 分阶段墙钟投影(实测基线)

| 阶段 | 往返次数 | 墙钟 |
|---|---|---|
| 现状(实测,含 Excel+落库) | ~数千 | **~40s**(30 build + 1.4 Excel + 9 落库;另有未测的删建行) |
| +A(F1–F5,F7 静态融合) | 消 ~3000 | ~24s(消 rkf 14.7 + 模板/清单/Excel静态) |
| +A+B(F6,F8 读类) | — | ~22s(消 Excel/snapshot 逐行读) |
| +A+B+C(F9 expand 回配) | ~30–50 | **~11s**(消 expand 10.3s) |
| +A+B+C+D(F10/F11 写批量) | ~30–50 | **~2–5s** |

### 7.3 结论

- **真 CPU 地板仅 ~0.8s** → 把所有逐行/逐组件往返融成 ~30–50 条批量查 + 批量写后,**墙钟地板 ~2–5s**。
- **5s 靠融合方案(A+B+C+D)单独可达**——**无需再叠加 B1**(已合并),也**不卡在 Excel/CPU**(实测 Excel 1.4s、CPU 0.8s)。卡点纯粹是"把几千次往返融掉",尤其 **F9 expand 回配 + F10 落库批量**这两块 I/O。
- **务实建议**:A 阶段(含方案 B)**立即独立落地**(零风险,−~16s);B/C/D 并入集合化重构(C=F9 是 Bug B 雷区,architect + 全套 md5 等价 + 专项造 same-partNo 属性/版本不同的单)。
- **诚实保留**:以上为单事务冷态实测;真实端点的删建行(F11)未单独测,且 F9 若有 line 维视图无法全融则 expand 残留逐行——这两点可能把地板从 2–5s 抬高,需 P0 落地后复测确认 5s。

---

## 8. 关键文件索引

- 预取/上下文:`CardSnapshotService`(`CardValuesPrefetch`:590 / `precomputeCardValuesPrefetch`:604 / `parseTemplateSnapshotInto`:635)。
- F1:`loadRowKeyFields`:1919 / assemble 加载点:960。
- F2/F3/F5:`loadComponentsSnapshot`:1533 / `ExcelViewService.buildRowData`:317-330(`partVersionLocked`:326 / `productAttributeValues`:328)/ `Template.findById`:282 / `Component.findById`:451,496。
- F4:`expandTemplateDriverBaseRows`:1392(driver 清单查)。
- F6:`ComponentDriverService.expandWithSnapshot`:210(snapshot_rows 逐行读)。
- F9:`expandForPartSet`/`expand`:1417,1422 / `precomputeQuoteDriverBuckets`(报价合桶参照)/ `precomputeCostingDriverUnion`(C4)/ `DataLoader.stableSort`(行序)。
- F10/F11:`ConfigureSnapshotService.writeSnapshotBatch`/`materializeRowData` / `QuotationService.saveDraft` 删建行。
- 测试:`GoldenCardValuesEquivTest` / `CostingPartSetUnionEquivTest`(回配等价范式)/ 深剖探针(复测往返)。
