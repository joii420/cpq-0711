# backtask.md —— 后端任务分解

> 唯一立项文档：`问题说明.md`（AC 原文在 §⑥，本文件只标编号不复制原文）。
> 方案见 `问题说明.md §⑤`，是唯一实现依据，**不要另选修法**。
>
> 🔴 **本文件 2026-08-25 因独立评审结论重写**。初稿只认定 D-1 一处缺陷，且引用了**指错文件**的
> flush 纪律。若你手上是旧版，丢掉重读。

## 改动范围

| 文件 | 治什么 |
|---|---|
| `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java` | **D-3** —— 真凶，让 AC-1/AC-2 变红的那个 |
| `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java` | **D-1** —— 同族浪费，不致命但违反 N+1 硬指标 |

无 API 变更、无 DB schema 变更、无 Flyway 迁移、无前端改动。

> 🚦 **先做 D-3 再做 D-1。** D-3 是唯一能让 AC-1/AC-2 变绿的；D-1 只影响墙钟。
> 若时间受限必须二选一，**保 D-3**。

---

## 任务项 · D-3（`CardSnapshotService`）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-7** | AC-1, AC-2, AC-3 | 把 `loadFrozenQuoteTabs(li.quotationId)`（`:1634` 处的逐行调用）**提到 `snapshotNewLinesCardValues` 的 Pass1 循环之前**（`:603` 之前）查一次，经现有 `prefetch` 通道或新增参数传入 `buildCardValues`。该查询入参是 `quotationId`、**整单恒定**，逐行查纯属浪费 |
| **B-8** | AC-10 | **三级降级链原样保留**：冻结结构 → `prefetch.templateSnapshotById` → 模板表查询（`:1635-1643`）。只是「取一次」而非「取 N 次」，**优先级与语义一个字不许改**。⚠️ 这是 `b6e86a18`「消除同卡双值」那次修复的守卫 |
| **B-9** | AC-3 | **核价侧同型排查**：确认 `buildCostingCardValues` 有无同型的「整单恒定值被逐行查」（如 `COSTING_CARD` 对应的冻结结构读）。⚠️ **本次评审未覆盖核价侧，不要假定它干净**。若存在，一并提出循环（属同一 AC-3 覆盖，不算扩范围）；若确认没有，**在汇报里明说「已查、没有」** |
| **B-10** | AC-9 | 给 `CreateQuotationMaterializer` 的四步各加一条**带耗时的日志埋点**（①snapshotQuotation ②ensureStructure ③ensureCardValues ④ensureExcelValues）。这是 AC-9「下一堵墙在哪」的唯一依据，也是万一 AC-1 仍红时的定位手段 |

🔒 **位置纪律（这条是真的，别再指错）**：`CardSnapshotService.java:625-631` 的「Pass1.5 位置纪律」注释写明
——Pass1 阶段托管实体必须保持**干净**，否则 native query 触发 Hibernate `flush-before-query`，破坏批处理。
**提到 Pass1 之前天然满足**（此刻尚未给任何实体赋值）。

---

## 任务项 · D-1（`ConfigureSnapshotService`）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-3 | 新增批量加载方法（建议名 `loadRowDataByLineComp(Collection<UUID>)`）。SQL 见 `问题说明.md §⑤ 修复二`，**必须带 `ORDER BY line_item_id, component_id, id`**，**分块 200** |
| **B-2** | AC-1, AC-3 | 在 `snapshotLines` **进入逐行循环之前**调用一次，结果按行传给 `computeRowDataFromSnap` |
| **B-3** | AC-3, AC-4 | `computeRowDataFromSnap` 加入参接收预载结果，内部不再调 `loadRowDataByComp`；随后**删除** `loadRowDataByComp`（唯一调用点 `:1181`，已 `codegraph_impact` + `/usr/bin/grep -a` 双向确认无残留引用） |
| **B-4** | AC-5 | **降级语义保持不变**：原 `try { overlay... } catch { LOG.warnf(...) }` 记 warn 不中止整份快照的行为必须保留 |
| **B-5** | AC-8 | 边界：`lineItemIds` 空 / 单元素 / 查询返回空 → 返回空 `Map` 而非 `null`；下游收到空 Map 等同「该行无既有 row_data」，不 NPE |
| **B-6** | AC-6 | **取数口径逐位一致**：SQL 谓词、JSON 解析、Map 语义必须与被替换的 `loadRowDataByComp` 完全一致 —— 同样的 `row_data IS NOT NULL` 过滤、同样的「解析失败跳过该组件」降级、同样的「缺失即 key 不存在」而非补空对象。🚫 **不得顺手**改过滤条件、补默认值、把 `null` 归一成 `{}`。⚠️ 这是「**键存在即权威**」语义的直接守卫（`RECORD.md` 2026-08-03 记载过一次因混淆「`''` vs 键缺失」引入的真实回归） |

### 🔒 D-1 的六条强制落地约束（评审实测发现，缺一条即视为未完成）

| # | 约束 | 为什么 |
|---|---|---|
| **C-1** | **预载集合收窄到 `lineNeedsExpand` 命中的行**（`anyNeedsExpand` 段 `:319-332` 已逐行判过，收集命中的 id 即可） | `QuotationResource:154` 的 **saveDraft 增量热路径**下，绝大多数行会在 `:447-455` 被 `continue`、**根本走不到** `computeRowDataFromSnap`。逐行懒查时它们成本为 0，改批量后反而变贵 —— **这是本次改动唯一可能的性能倒退面** |
| **C-2** | IN 列表**过滤 `null` 元素** | 现状靠 `:1145` 的 `null` 提前返回兜住；批量版没这层保护 |
| **C-3** | SQL 必须带 **`ORDER BY line_item_id, component_id, id`** | 表上**无** `(line_item_id, component_id)` 唯一约束（只有 `pkey(id)` + `idx_qlcd_line`），评审实测**全库有 6 组重复键**。两侧都是 `Map.put` 覆盖 =「最后一行赢」，无 `ORDER BY` 时物理序不定。成本为零，钉死它 |
| **C-4** | 预载**只存原始 `String`**，循环内对当前行 `readTree` 一次即弃 | 存 `JsonNode` 全量驻留约 **15~40 MB**；存 String 约 **3.8 MB**。🚩 初稿说「分块 200 后峰值更低」是**错的** —— 分块只限单次结果集，Map 要活到循环结束 |
| **C-5** | 预载结果**视为只读**，禁止喂给任何会原地改的下游 | 批量预载会让同一 `JsonNode` 被两处同时引用（逐行查询天然每次新对象）。当前无害，但这是逐行→批量最经典的 bug 面。**采纳 C-4 后自动消失** |
| **C-6** | 事务注解必须是**有意识的选择**，并在注释写明理由 | `loadSnapshotRowsByLines` 带 `@Transactional(REQUIRES_NEW)` + `self.` 调用；**照抄会把一个原本无事务的读变成开真事务的读**（`snapshotLines` 无事务）。行为无害但多借连接、多一次 begin/commit，不能是复制粘贴的副产品 |

### 实现者可选（不需重新审批）

`snapshotLines` 进循环前**已经**对同一张表、同一批 line id 打过整单 IN 查询（`:308` 增量 / `:344` 非增量，建单走 `:344`）。
可给 `loadSnapshotRowsByLines` 加**姊妹方法**（多带 `row_data` 列）复用这一次查询。
收益仅 ≈0.17 s，**真价值是结构性的**（少一处会与 `:308/:344` 漂移的 id 收集）。
🚫 **不得修改 `loadSnapshotRowsByLines` 自身签名** —— `LoadSnapshotRowsByLinesEquivTest` 守着它。

---

## ⚠️ `snapshotQuotation` / `snapshotLines` 的 5 个生产入口（改 D-1 必须全部想过）

| 入口 | 位置 | `skipRowsWithSnapshot` |
|---|---|---|
| 建单物化（本次目标） | `CreateQuotationMaterializer.java:41` | `false` |
| **saveDraft 热路径** | `QuotationResource.java:154` | **`true`** ← C-1 就是为它 |
| 加产品 | `ConfigureProductResource.java:63`（直调 `snapshotLines` 2 参重载） | `false` |
| 从基础刷新 | `ConfigureProductResource.java:95` | `false` |
| 管理端强制刷新 | `QuotationAdminResource.java:205` | `false` |

无定时任务调用。

---

## 🚫 明确不做（越界即超范围）

| 不做 | 原因 |
|---|---|
| 不改 `overlayExistingInputKeys` 的签名与实现 | 静态纯函数，`OverlayExistingInputKeysTest` 7 例守着，是 AC-4 的守卫 |
| 不改 `loadSnapshotRowsByLines` 签名 | `LoadSnapshotRowsByLinesEquivTest` 守着 |
| 不改 `materializeRowData` 非批量分支（`snapshotLines:613`） | 该分支不调 `overlayExistingInputKeys`，与本缺陷无关 |
| 不改 `cpq-frontend/src/services/api.ts` 的 `timeout: 30000` | 见 `问题说明.md §④ 证据 7`：只放宽前端超时是无效修法 |
| 不改 Narayana 事务超时配置 | 靠**消除无谓耗时**回到预算内，不靠放大预算 |
| 不做物化拆批 / 异步化（备选丙） | 已转 `BL-0183` |
| 不碰导入侧 `QuoteImportService`（D-2 的 27.2s 热点） | 根因未定位，已转 `BL-0182` |
| **④ `ensureExcelValues` 若成为新瓶颈，不许自行继续改** | AC-9 只要求**测量并记录**。真成了新墙 → 按 §4.3 回来问用户 |

---

## 自检要求（`backend.md`）

- `./mvnw test` 全绿，**必须在汇报中点名**以下的通过数：
  `OverlayExistingInputKeysTest`（7 例）/ `GoldenCardValuesEquivTest` / `CardValuesBatchPersistEquivTest` /
  `LoadSnapshotRowsByLinesEquivTest` / **`LazyQuoteBucketEquivTest`**（`:52` 有往返数上限断言 `rt < 30`，
  改完盯着这个数**不要涨**）/ **`ConfigureSnapshotEmptyOverwriteGuardTest`**（`:266` 真调 `snapshotQuotation(id,true)` 打真库）
- 后端存活自检：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → **401**
- ⚠️ 探本机服务一律加 `--noproxy '*'`；`/q/health` 返 404 不是健康探针
- **N+1 硬指标自检**：改完后自述两句 —— 「`quotation_line_component_data` 读取条数 = ⌈N/200⌉，与 N 不成正比」
  「`quotation_view_structure` 读取条数 ≤ 4，与 N 无关」
