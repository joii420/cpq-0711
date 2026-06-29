# 首存 draft 与 batch-expand 渲染性能优化 Spec — 2026-06-26

> 状态：设计 / 待评审。本 Spec 给出「为什么慢」的根因定位与「怎么优化」的分级方案（P0/P1/P2/P3），
> 每条优化都标注**证据等级**（已实测 / 已查证代码事实 / 待埋点确认的假设）与**回滚开关**。
>
> 关联记忆：`cpq-firstsave-real-perf-measurement`、`cpq-open-autosave-storm-bug`、`cpq-savedraft-incremental-snapshot`；
> 关联 plan：`docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md`（P3 根治路线）。

## 1. 背景与目标

导入报价单后进入报价单 Step2，前端连续打两类慢接口：

- **`POST /api/cpq/components/batch-expand`**（卡片渲染的 driver 展开）：实测 **17.77s**。
- **`PUT /api/cpq/quotations/{id}/draft`**（首存草稿）：实测**连发 3 次，22.43s / 12.91s / 10.98s**。

前序优化（2026-06-26 已合 master）已把两接口的**查询次数**砍到接近 1 次：
- batch-expand：Phase 1 逐 task 读快照 `SELECT snapshot_rows ... LIMIT 1`（616 次）→ 1 次 IN 查（`SnapshotRowsContext` 批量预载，commit `aa46bbd`）。
- draft：getById 4 处 N+1 融合、首存 card values / Excel compData / 核价 union 整单预取等。

**但接口耗时仍 10-22s。** 说明瓶颈已**不在查询次数**，而在别处。本 Spec 目标：定位真实耗时段，给出最小改动、最高收益的优化。

**量化目标**：首存 draft 单次 < 5s（拉伸目标），且**一次用户动作只发 1 次 draft**（消除三连发）；batch-expand 渲染 < 2s。

**非目标**：本 Spec 的 P0/P1/P2 不做集合化大重构（那是 P3，单独 plan）；不改三大核心模块基线的渲染契约。

## 2. 分段埋点（已部署 master，dev server 已热重载）

为把「10-22s 花在哪」量化，已加临时 INFO 埋点（commit 见 master，日志前缀便于过滤；量化完可撤）：

- **draft PUT**（`QuotationResource.saveDraft`）→
  `[draft-profile] id=.. newLines=N total=Tms | S1.saveDraft=Xms S2.snapshotRows=Xms S3.cardValues=Xms S4.getById=Xms`
  - **S1** `quotationService.saveDraft`：全删全建 + 落库。
  - **S2** `snapshotService.snapshotQuotation(id,true)`：重建 `snapshot_rows`（行级 driver 展开快照）。
  - **S3** 整份快照 Phase1：仅对**新行**算 4 份 card values（报价/核价 × 值/结构）。
  - **S4** `getById` 重建 DTO。
- **batch-expand**（`ComponentResource.batchExpand`）→
  `[be-profile] tasks=N prefetched=P snapshotHit=H realExpand=R | prefetch=Xms phases=Xms`
  - **snapshotHit**：命中快照直返的 task 数（快路径）。
  - **realExpand**：未命中、落 Phase 2 实时 expand 的 task 数（远程 SQL 视图，慢路径）。

埋点落 `backend.log`，复现一次即可读到精确分段，用于**事前定位 + 事后量化收益**。

## 3. 根因分析

### 根因 A — batch-expand 17.77s ≈ Phase 2 实时 expand（待埋点实锤，但高置信）

**证据**：
- 快照已批量化为 1 次 IN 查；且快照体量极小：基准单 87af5786（用户实测 616 task 那单）**616 行快照合计 190KB，平均 309 字节、最大 2.5KB**。反序列化 190KB JSON 仅需几十毫秒。
- 因此 **17.77s 在数量级上不可能来自「快照读 + 反序列化」**。

**推断**：渲染（batch-expand）发生在**首存快照落库之前**——这些行此刻还没 `snapshot_rows`，Phase 1 全部未命中 → 落 Phase 2 **实时 expand**（逐桶跑远程 SQL 视图）。即慢的是「没有快照可读、只能现算」。

**待确认**：埋点的 `realExpand` 计数。若 `realExpand` 远大于 0 → 实锤 Phase 2；若 `snapshotHit` 接近 tasks 而仍慢 → 转查反序列化/响应序列化（概率低）。

### 根因 B — draft 三连发 = payload churn 震荡（已查证，前后端双代理交叉验证）

**机制**：
1. `buildDraftPayload` 把后端**派生**的 4 份快照（quoteCardValues / costingCardValues / 两份结构）一并塞进 PUT payload。
2. 每次保存返回后，`syncLineItemsFromResponse` 把后端**新算出的快照**写回 `lineItems`。
3. → `lineItems` 变了 → 下一次 `buildDraftPayload` 产出的 `payloadStr` 与上次不同 → `lastSaveRef` 去重失效。
4. 保存在飞期间这次变更被 `pendingSaveRef` 记下 → 当前保存 `finally` 里补发一次（`autoSaveDraftRef.current?.()`）。
5. 补发又返回新快照 → 再写回 → 再变 → 再补发…… 约 **3 轮**后快照稳定才收敛。

**结论**：**一次用户动作实际发 3 个 draft，每个都干全套重活**。这是纯浪费的 ×3。
（注：编辑失焦 autosave 已于 2026-06-26 关闭，故三连发**不是**编辑触发，而是上述 churn 自激。）

### 根因 C — 单次 draft 耗时结构（结构已查证，精确占比待埋点）

PUT 一次串行干 S1→S2→S3→S4：

- **S1 全删全建 + 落库**（已查证代码事实，最高确定性的「免费修点」）：
  - `clearLineItemChildren` 对每行 `DELETE` 4 张子表，再逐行重建。**快照在删前已备份、建后回填**（`processBatchStage1` lines ~1885/2066），故**复用行快照不丢**，再存不会触发 S2/S3 重算——这点是对的。
  - 但重建是**逐行 `persist`**：基准单 **693 条 `quotation_line_component_data`** + 工序 + 组合工艺 + 行实体，每条一次 `persist`。
  - **关键事实：`application.properties` 没配 `hibernate.jdbc.batch_size`** → 每条 INSERT 在 flush 时各发一次**远程库往返**（DB 在 10.177.152.12）→ **700-1000+ 次往返**。这是 S1 的主成本，且首存/再存都付。
- **S2 snapshotQuotation**：复用行快照已保留 → 多数行跳过；**首存（全新行）才全量行级 expand**。
- **S3 整份快照 Phase1**：只对**新行**算 4 份 card values（expand + 公式 + 序列化）；首存全是新行 → 全量；再存（已有快照）跳过。
- **S4 getById**：已批量化（4 IN 查），轻。

**耗时画像**（与截图三连发曲线吻合）：
- **首存**（导入，全新行）≈ S1 落库 + S2 全量 expand + S3 全量 cardValues ≈ **22s**。
- **再存**（快照保留）≈ 仅 S1 落库（S2/S3 大部分跳过）≈ **10-12s**。

## 4. 优化方案（分级）

### P0 — 砍 payload churn：3 次 draft → 1 次【最高性价比，先做】

**优化点**：让 draft 的发送/去重**不受后端派生快照回写的影响**。

**为什么这么优化**：根因 B 证明三连发是「payload 含派生快照 → 回写 → 去重失效 → 补发」的自激环。**派生快照是后端算出来的、不是用户输入**，本不该参与「内容是否变化」的判定，更不该驱动重发。打破这个环，一次动作就只存一次。

**两种实现（择一或叠加）**：
- **B-1（推荐，治本）**：`buildDraftPayload` **剥离 4 份派生快照**，draft payload 只携带**用户输入**（lineItems 结构、字段值、客户、优惠等）。后端本就会重算快照，不依赖前端回传。
  - 注意：现码注释（`QuotationResource` line 182-185）指出**导入首存新行**时前端 `syncLineItemsFromResponse` 依赖**响应里**的 4 份卡片值翻入「快照模式」（`useSnapQuote=true`），否则报价卡走实时展开、删行墓碑失效。
  - → **响应仍要带 4 份快照**（B-1 只剥离**请求** payload，不动响应）。前端拿响应回写一次即可，回写后**不再**因此重发（因为请求 payload 不含快照、`lastSaveRef` 不再被快照变化打破）。
- **B-2(兜底)**：`lastSaveRef` 去重比较时，对 payload **排除快照字段后再比**；且 `pendingSaveRef` 补发前再做一次「排除快照」的相等判断，相等则不补发。

**收益**：3 → 1，直接省 ~2/3（约 23s 中省约 23s 的重复部分）。

**风险与验证**：
- 风险点：删行墓碑 / 快照模式 / 首存「已自动加入 N 个产品」的回填。
- 必跑 E2E `quotation-flow.spec.ts`（含「空闲 5s PUT/draft ≤1」断言 + 8 Tab 加载中=0），并真实复现「导入→建单→重开」验产品不丢、删行生效。

### P1 — 开 Hibernate JDBC 批处理：S1 落库 700+ 往返 → 个位数【近乎免费，先做】

**优化点**：`application.properties` 启用 JDBC 语句批处理 + 插入/更新排序：
```
quarkus.hibernate-orm.jdbc.statement-batch-size=100
# 让同实体 INSERT/UPDATE 归并成批(否则交错语句打断批)
quarkus.hibernate-orm.unsupported-properties."hibernate.order_inserts"=true
quarkus.hibernate-orm.unsupported-properties."hibernate.order_updates"=true
```

**为什么这么优化**：根因 C 已查证 `batch_size` 未配 → 693 条 component_data 等逐条 INSERT 各发一次远程往返。开批处理后 Hibernate 在 flush 时把同实体多条 INSERT 合并成**一条多行 prepared statement**（`order_inserts` 保证同类相邻、不被异类语句打断批），**落库往返从 700+ 压到个位数**。这是纯 flush 层优化，**不改任何业务逻辑**：实体仍逐个 `cd.snapshotRows=...; cd.persist()`（快照回填顺序不变），只是提交时机批量化。

**注意/边界**：
- `processBatchStage1` 循环内若有 INSERT 间穿插 SELECT（读）会触发提前 flush、打断批。需确认热循环（cd/工序/组合工艺 persist）内无穿插读；已知后置批量查询（版本查询 E2 / seed E3 / 父子链 E5）在循环**之后**，不影响循环内批处理。
- 批处理是**全局**配置，影响所有写路径 → 必须跑等价回归确认行为不变。

**收益**：S1 落库段大幅下降（首存/再存都受益）；与 P0 叠加后再存有望进入秒级。

**验证**：
- golden 等价：`GoldenCardValuesEquivTest`(8f0c37a4 / a8f17a74 四份 md5)、`BatchStage1PersistEquivTest`、`PersistWholeBatchEquivTest`、`RowDataWholeBatchEquivTest` 全绿 → 证明落库内容逐位不变。
- 埋点 `S1.saveDraft` 前后对比量化。

### ~~P2 — batch-expand 渲染时序~~ → 【已证伪并入 P3，2026-06-26】

> **结论变更（实证后）**：P2 原设想「渲染等快照落库 → batch-expand 走 Phase 1」**不成立**，已并入 P3。

**为什么证伪**：追导入数据流（`QuotationWizard.tsx:725-750` 导入首存 effect + `useDriverExpansions.ts:307`）发现——
导入首存 effect **显式等 `allReady`(所有 driverExpansions 到位)才发**（line 743-745，注释 721-724：「必须等
driverExpansions 全部到位再保存，否则 snapshotRows 看不到 expansion 只落 1 行」），而 `buildDraftPayload`
的 rowData / quoteExcelValues **正是消费 driverExpansions** 生成、落库成快照。

即数据流是：**batch-expand(17s Phase2 实时 expand) → driverExpansions → allReady 闸门 → 首存消费 → 落库成快照**。
- 17s 的 batch-expand **不是重复浪费**，而是首存 rowData **必须消费的实时展开**；导入→落库是**串行**(~17s 展开 *再* ~22s 存)。
- 「渲染前先读快照」是**鸡生蛋**：快照由首存产生，而首存又依赖这次展开。**渲染时序重排消不掉这 17s**。
- 真正消掉它的唯一办法 = **把展开搬到服务端**（前端只发原始 lineItems，后端一次集合化 expand + 落快照，前端从响应快照渲染、不再 client batch-expand）——**这就是 P3**。

**净影响**：batch-expand 的 17s 与首存 S2/S3 的 ~11s 是**同一份 expand 的两次体现**（客户端一次、服务端 S2/S3 一次），
P3 集合化在服务端**一次算清并同时供渲染与落库**，同时消掉两者。故 P2 不再单列，目标并入 P3。

### P3 — 集合化首存（根治，单独大改，本 Spec 仅引用）

S1/S2/S3 三段本质都在「逐行/逐段重算 expand + 落库」。根治方向是**一次集合化算好全单 + 批量落库、逐位等价（golden md5）**，对应
`docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md`。P0/P1/P2 是其前的低风险增量，不与之冲突。

## 5. 实施顺序与开关

1. **先复现一次**，读 `[draft-profile]`/`[be-profile]` 精确分段 → 确认 S1/S2/S3 占比 + `realExpand` 计数，校准本 Spec 假设（根因 A、C 的精确占比）。
2. **P1（开 JDBC 批处理）**：改 1 个配置文件 + 跑等价回归（golden md5 + BatchStage1 等价）。最低风险先落。
3. **P0（砍 churn）**：前端 `buildDraftPayload` / 去重逻辑，跑 E2E + 真实重开验证。
4. **P2（渲染时序）**：依赖 P0，最后做。
5. 撤埋点（或降为 DEBUG），量化收益写回 `RECORD.md` + 记忆。

**开关纪律**：性能类改动默认 kill switch（先灰度 OFF 验等价、再默认 ON）；P1 的 `batch_size` 通过配置即可回退；P0 前端加 `EDIT_AUTOSAVE_ENABLED` 同款常量开关，便于一键回滚比对。

## 6. 验证清单（DoD）

- [ ] 埋点确认：首存 `S1/S2/S3` 三段精确占比；batch-expand `realExpand` 计数。
- [ ] P1：`batch_size` 开后所有 golden md5 / BatchStage1 / Persist 等价测试绿；`S1.saveDraft` 埋点下降可量化。
- [ ] P0：E2E `quotation-flow.spec.ts` 1 passed + 「空闲 5s PUT/draft ≤1」（实测应 = 1 次首存）；真实「导入→建单→重开」产品不丢、删行生效。
- [ ] P2：`[be-profile]` `snapshotHit≈tasks` / `realExpand≈0`；8 Tab 加载中=0。
- [ ] 收益量化：首存 draft 单次耗时、draft 触发次数、batch-expand 耗时 前后对比写回 `RECORD.md`。
