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
| 不改 `overlayExistingInputKeys` 的签名与实现 | 静态纯函数，`OverlayExistingInputKeysTest` 6 例守着，是 AC-4 的守卫 |
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
  `OverlayExistingInputKeysTest`（**6 例**）/ `GoldenCardValuesEquivTest` / `CardValuesBatchPersistEquivTest` /
  `LoadSnapshotRowsByLinesEquivTest` / **`LazyQuoteBucketEquivTest`**（`:52` 有往返数上限断言 `rt < 30`，
  改完盯着这个数**不要涨**）/ **`ConfigureSnapshotEmptyOverwriteGuardTest`**（`:266` 真调 `snapshotQuotation(id,true)` 打真库）
- 后端存活自检：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → **401**
- ⚠️ 探本机服务一律加 `--noproxy '*'`；`/q/health` 返 404 不是健康探针
- **N+1 硬指标自检**：改完后自述两句 —— 「`quotation_line_component_data` 读取条数 = ⌈N/200⌉，与 N 不成正比」
  「`quotation_view_structure` 读取条数 ≤ 4，与 N 无关」

---

## 🔴 第二次扩范围任务项 · D-4（2026-08-25 亲验后，用户裁决）

**背景**：D-1+D-3 修完后实测 AC-1①②/AC-2 全绿，**但四步埋点显示**：

```
①snapshotQuotation=16758ms ②ensureStructure=290ms ③ensureCardValues=58679ms ④ensureExcelValues=10931ms 总计=86658ms
```

**③ 距 Narayana 60s 硬上限只剩 1.3s（余量 2%）**。评审预估的「阈值 ≈3700 行」被实测证伪 ——
③ 每行 **31.8ms**（58679÷1845），真实阈值 ≈**1887 行**，本单已用掉 **97.7%**。
再多 40 行就可能重新撞穿，失败模式是**静默数据损坏**。

> ⚠️ **这 58 秒不是 N+1**。T-1 的 SQL 条数护栏已证明查询次数与行数无关。
> 它是 `buildCardValues` / `buildCostingCardValues` / `BomTreeRenderService.render`
> 在 1845 行 × 1845 distinct 料号下的**真实计算量**。所以不要再去找 N+1，要去**拆事务**。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-11** | AC-11 | `ensureCardValues` 从「单事务包住全部 N 行」改为**按行分批、每批独立事务**。chunk 默认 **300**（按 31.8ms/行 → 单批 ≈9.5s，余量 84%），**chunk 值须可配**以便调优。每批调 `snapshotNewLinesCardValues` 走 `REQUIRES_NEW`，批内提交、批间不共享事务 |
| **B-12** | AC-11 | 🔒 **`prefetch` 必须留在分批之外**，保持整单一次。⚠️ **拆错位置会把 D-3 刚修好的「整单查一次」打回「每批查一次」** —— 这是本项最大的自伤风险，实现后必须用 T-1 的 `quotation_view_structure` 计数复核（应仍 ≤4，**不是** ≤4×批数） |
| **B-13** | AC-11 | 新增分批埋点：批次序号 / 每批行数 / **每批耗时**。AC-11 的断言全靠它，不打点就没法验收 |
| **B-14** | AC-12 | 部分失败语义：中途失败时已提交批**保留**、未完成行留 NULL；接口仍按既有降级语义返回 `cardValuesReady=false` + `warnings`（🚫 **不许因为「大部分成功」就报 true**）。确认 `ensureCardValues` 的 `IS NULL` 选行谓词使重跑天然只补未完成行（自愈） |

### 🚫 D-4 明确不做

| 不做 | 原因 |
|---|---|
| 不改接口契约、不转异步轮询 | 那是 `BL-0183` 完整方案丙的**后一半**，本次只取「拆批事务」这一半 |
| 不加大 Narayana 事务超时 | 靠**拆小事务**回到预算内，不靠放大预算 |
| 不去 profile / 优化 ③ 的 58s CPU 本身 | 本次目标是**拆掉 60s 悬崖**，不是把计算变快。真要提速另立任务 |

### D-4 自检要求

- 报出**分批后的四步埋点** + **每批耗时**，与改动前的 `③=58679ms` 直接对照
- 用 T-1 复核 `quotation_view_structure` 计数**仍 ≤4**（防 B-12 的自伤）
- ⚠️ **worktree 内不要与其它进程并发跑 mvn** —— 上一轮并发踩 `target/` 造出过 399 个假失败

---

## 🔴 D-4 返修 · B-15（2026-08-25 亲验暴露，用户裁决方案 E）

### 亲验实测：分批本身成功，外层事务失败

```
batch=1/7 rows=300 elapsed=8976ms      batch=5/7 rows=300 elapsed=13181ms
batch=2/7 rows=300 elapsed=9631ms      batch=6/7 rows=300 elapsed=14501ms
batch=3/7 rows=300 elapsed=11300ms     batch=7/7 rows=45  elapsed=7464ms
batch=4/7 rows=300 elapsed=12500ms     补算 1845 行（分 7 批，chunk=300）
```

**7 批全部成功提交，单批最长 14.5s（AC-11 的「单批 ≤20s」已达标）。**
**库内实测卡片值 `报价空 0 / 核价空 0 / 总 1845` —— 数据完全正确。**

但接口返回 `cardValuesReady:false` + `warnings:["卡片值物化失败…"]` + `costingTreeRows:0`，
日志 `RollbackException: ARJUNA016102`。

**根因**：`ensureCardValues` 自身仍带 `@Transactional`，**外层事务横跨整个 77.5s 批循环** →
它自己超 60s 被 reaper 杀 → commit 抛 `RollbackException` → `materialize` 捕获置 `cardValuesReady=false`。

> 🚨 **这个状态比修复前更危险**：修复前是「报失败 + 数据真丢」，现在是「**报失败 + 数据其实是好的**」。
> 一个说谎的状态位会让用户以为需要重试，实际不用；也会让后续排查从错误的前提出发。

### 为什么选方案 E

外层事务**不持有那 1845 行的任何写锁**（写全在内层 `REQUIRES_NEW` 里），它只护两样：
① `tryQuotationCalculationLock` 的 `pg_try_advisory_xact_lock`（单飞锁，注释明写「加锁必须早于缺失行
SELECT，否则两事务都读 NULL → 双重补算」）；② 缺失行 SELECT + `publishedTemplateReader` 门禁校验。

**它是个锁架子，不是干活的事务。** 故给它单独放宽超时，语义完全不变。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-15** | AC-1, AC-2, AC-11, AC-12 | 给 `ensureCardValues(UUID, boolean)` 加 **`@io.quarkus.narayana.jta.runtime.TransactionConfiguration(timeout = 600)`**（与既有 `@Transactional` 并存）。⚠️ `TransactionConfiguration` 只在**由该方法开启事务**时生效，须确认它确实是事务起点（`materialize` 不带事务、`BasicDataImportV6Resource` 也不带 → 是起点）。在注释里写明：**放宽的是「锁架子」事务，不是干活的事务；干活的内层批事务仍受默认 60s 约束，单批实测 ≤14.5s** |

### ⚠️ 这是对「明确不做」清单的一次**例外**，已获用户批准

本文件 D-4 小节的「🚫 不加大 Narayana 事务超时」原文仍然有效 —— 它针对的是**干活的事务**
（③ 那 58.7s 的计算）。B-15 放宽的是**不干活的外层锁架子**，两者性质不同。
**该例外由用户于 2026-08-25 明确裁决，不得据此推广到其它事务。**

### B-15 已知代价（如实记录，不粉饰）

- 外层事务会占住一个池连接约 **77s**（`quarkus.datasource.jdbc.max-size=20`）
- **行数再涨会线性变长**（5000 行 ≈ 200s）—— 这不是悬崖但也不优雅。
  彻底解法是方案 D（`ensureCardValues` 每次只算一批、调用方循环），已记入 `BL-0183` 复评项

### ⚠️ B-15 规格更正（2026-08-25，实现期被测试实证推翻）

🚨 **上面 B-15 的原字面做法（给 `ensureCardValues` 加 `@TransactionConfiguration`）是错的，会造成 P0 功能阻断。**

**实证**：`SqlCountNPlusOneGuardTest` 当场炸出

```
java.lang.RuntimeException: Changing timeout via @TransactionConfiguration
                            can only be done at the entry level of a transaction
  at io.quarkus.narayana.jta.runtime.interceptor.TransactionalInterceptorBase.checkConfiguration(:352)
  → TransactionalInterceptorBase.invokeInCallerTx(:334)
```

**根因**：Quarkus 在「方法已处于外层活跃事务中」且「该方法自己声明了 `@TransactionConfiguration`」时
**直接抛异常中止调用**，而**不是**主线原先以为的「静默不生效」。

**影响面（已核实）**：`QuotationService.submit(UUID)` 于 `:725` 带 `@Transactional`，
`:881` 的 `cardValuesReady = ensureCardValues(id, true)` 在其方法体内 →
**任何需要补算卡片值的报价单提交都会直接抛 `RuntimeException`，提交功能被打断**。
这比 D-4 返修前的「报 false 但数据是好的」严重得多 —— 那是状态位说谎，这是**功能性阻断**。

**6 个生产调用点的事务上下文（实现方已逐一核实）**：

| 调用点 | 调用时已在事务中？ |
|---|---|
| `CreateQuotationMaterializer.materialize`（本次目标路径） | 否 —— 事务根 |
| `QuotationResource#ensureCardValues` | 否 —— 事务根 |
| `QuotationResource#awaitWarmBeforeSubmit` | 否 —— 事务根 |
| `ComparisonViewService#getData` | 否 —— 事务根 |
| **`QuotationService#submit` :881 直接调用** | **是** ← 会炸 |
| **`QuotationService#submit` :897 经 `CostingFreezeService#createForSubmission` 间接调用** | **是** ← 会炸 |

### ✅ B-15 更正后的做法（主线裁决，仍在用户批准的方案 E 之内）

**不碰 `ensureCardValues` 自身的任何注解。** 改为在**唯一目标调用点**包一层显式事务：

在 `CreateQuotationMaterializer.materialize` 里，把 `cardSnapshotService.ensureCardValues(qid)`
这一句改为用 `io.quarkus.narayana.jta.QuarkusTransaction` 显式开一个带扩展超时的事务包住它，
例如 `QuarkusTransaction.run(QuarkusTransaction.RunOptions.options().timeout(600), () -> ...)`
（具体 API 形态以实现方核实为准）。

**为什么选它而不是「拆成两个方法」**：
- 改动面 **1 个文件**（vs 拆方法要改 `CardSnapshotService` + 4 个调用方 = 5 个文件，且每个调用点的事务上下文都要重新核实一遍）
- **从机制上不可能再影响 `submit()` 或任何其它调用点** —— 超时设置压根不在 `ensureCardValues` 里，
  而在建单物化这一条路径的显式事务块里
- 把「本次例外只用于建单物化路径」这句话，**从注释里的承诺变成了结构上的事实**

🔑 **教训（值得进 `docs/反模式.md`）**：`@TransactionConfiguration` 在已有事务中**抛异常而非静默降级**。
给一个**被多处调用**的方法加事务配置注解，等于给它的**所有**调用点加了「必须是事务根」的隐式前置条件 ——
而这个条件的违反是**运行时爆炸**，编译期毫无提示。**要改事务配置，改调用点、别改被调方。**

---

## 🔴 B-16：把 `BomTreeRenderService.render` 提出批循环（2026-08-25 A/B 实测驱动，用户裁决）

### 实测：分批带来了 +86% 的开销，元凶是整单级工作被重复做

**同一个 build、同一份数据、背靠背两轮 A/B**（chunk 可配，故无需改代码即可对照）：

| 组 | chunk | ③ `ensureCardValues` | 批处理耗时 | 总墙钟 | 单批最长 |
|---|---|---|---|---|---|
| **A** | 2000（退化为单批） | **51,250ms** | 47,179ms（1 批 × 1845 行） | 80.5s | 47,179ms（余量仅 **21%**） |
| **C** | 300（7 批） | **92,376ms** | 87,716ms（7 批） | 123.1s | 15,691ms（余量 **74%**） |
| 差 | | **+80%** | **+86%** | +42.6s | |

**+86% 不可能只是 7 次 begin/commit 的成本** → 必有整单级工作被重复。

### 元凶

`bomTreeRenderService.render(q.costingCardTemplateId, lines)` 位于
**`CardSnapshotService:597`，在 `snapshotNewLinesCardValues` 方法体内部**。
而 D-4 的分批是**在外面循环调用 `snapshotNewLinesCardValues`** →
**chunk=2000 时 render 跑 1 次；chunk=300 时 render 跑 7 次。**

> 🔑 **这与 B-12 是同一个模式**：整单级的东西必须留在循环外。
> 上一轮把 `prefetch` / `union` 提出去了，**漏了 `render`** —— 因为它藏在**被调方内部**，
> 不像 prefetch/union 那样摆在调用方眼前。
> **教训：拆循环时，"整单级工作"的排查必须往被调方内部再挖一层，不能只看调用方的局部变量。**

### 两头不理想，且同源

- 不分批：快，但**内层单事务 47,179ms / 60,000ms，余量仅 21%** —— 悬崖没真正拆掉
- 分 7 批：余量 74%，但**慢 86%**

**把 render 提出去，两个问题一起解决。**

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-16** | AC-1, AC-9, AC-11 | 把 `bomTreeRenderService.render` 从 `snapshotNewLinesCardValues`（`:595-597` 那段 `templateHasTreeTab` 判断 + render 调用）**提到 `ensureCardValues` 的批循环之外**：对**全部** `missing` 行整单渲染一次，得到 `Map<UUID, Map<String, ArrayNode>>`；批循环内按本批行 id **切片**后传入 `snapshotNewLinesCardValues`（新增入参）。<br>✅ **下游管道已存在**：`buildCostingCardValues` 已有 `precomputedBaseRows` 参数（注释原文「按 `precomputedBaseRows!=null` 跳过旧引擎 closure+expand」），直接复用即可。<br>🔒 **必须保留** `costingRenderError` 的失败语义：render 抛错时**不上抛**，逐 li 落带原文的失败哨兵（现注释：「不上抛(否则整单快照 500 + 全 NULL → 前端无限「加载中…」)」）。提出循环后，该错误应对**所有批次**一致生效 |

### B-16 验收（必须做 A/B，不许只跑一次）

改完后用同样手法再跑一次 A/B（`-Dcpq.ensure-card-values-chunk-size=`）：
- **判据**：`chunk=300` 的 ③ 应显著向 `chunk=2000` 的 51,250ms 靠拢（消除大部分 +80%）
- **同时**单批耗时仍须 ≤20,000ms（AC-11 不许因为提速而回退）
- 用 T-1 复核 `quotation_view_structure` 计数仍 ≤4（防再次自伤）
