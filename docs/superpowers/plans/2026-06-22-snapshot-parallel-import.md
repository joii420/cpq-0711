# 报价单导入首存快照 · 内部并行化 Implementation Plan

> REQUIRED SUB-SKILL: subagent-driven-development 或 executing-plans。本会话主线亲执行(并发代码需严控)。

**Goal:** 把 saveDraft 首存大单的串行快照(报价 ~13s + 核价 ~34s)改为专用有界线程池按行并行,请求仍**同步**返回,47s→秒级。

**Architecture(来自 cpq-architect 设计,已亲验关键事实):**
- DataLoader 是 `@RequestScoped`(每实例独立 resultCache)→ 每 worker 必须 `@ActivateRequestContext` 起**独立** request context(各拿独立 DataLoader + 活跃 session)。先例:QuoteImportService:83。
- expand/BomClosure 走 raw JDBC(线程安全)+ Caffeine 进程缓存(共享 OK)。
- 专用有界 daemon 池(默认 8 线程),**不借** RESTEasy worker / ManagedExecutor(防 RECORD 502)。
- 行级粒度,行内串行;**两段屏障**:报价侧并行跑完(写完 snapshot_rows)→ 才启核价侧并行(核价 buildCardValues 读 snapshot_rows)。
- 只并行 needExpand 行(复用已合并的增量 `lineNeedsExpand` skip);总超时 < HTTP 超时,未完成行下次 saveDraft 自愈。
- ThreadLocal(QuotationIdContext 等)每 worker 内 set/finally clear(不跨线程)。

**连接预算:** 核价 worker 峰值 ~2 连接(JTA 事务 1 + raw JDBC expand 1);threads=8 → ~16,接近 max-size=20 → **配套 max-size 20→32**。

**Tech Stack:** Quarkus 3.23 / CDI `@ActivateRequestContext` / `ExecutorService` / `CompletableFuture.allOf(timeout)`。

---

## 落地顺序(先核价后报价,先验证并发模式再复制)

### Slice 1: 专用有界池 SnapshotParallelExecutor + 单测
- 新建 `cpq-backend/src/main/java/com/cpq/quotation/service/SnapshotParallelExecutor.java`:`@ApplicationScoped`,`@PostConstruct` 建 `newFixedThreadPool(threads, 命名 daemon 工厂)`,`@PreDestroy shutdownNow`,`@ConfigProperty cpq.snapshot.parallel.threads=8` / `cpq.snapshot.parallel.timeout-seconds=120`。
- 暴露 `runParallel(Collection<UUID> ids, Consumer<UUID> task)`:submit 到池 + `allOf().get(timeout)`,超时只 warn 不抛。
- 单测 `SnapshotParallelExecutorTest`:提交 N 个任务全部执行(AtomicInteger 计数 == N);并发度 ≤ threads(同时运行计数峰值 ≤ threads)。

### Slice 2: 核价侧并行(屏障 2)+ 单测
- 新建 `LineSnapshotWorker.java`:`@ActivateRequestContext public void snapshotOneLineValues(UUID lineItemId)`,构造 stub(`new QuotationLineItem(); stub.id=lineItemId`)→ `cardSnapshotService.snapshotLineValues(stub)`(其内部 @Transactional 重载 findById);try/catch warn 不抛。
- `QuotationResource.saveDraft` 核价循环(L134-149):主线程 findById + hasSnapshot 过滤收集 `toSnapshot` 列表 → `parallelExecutor.runParallel(toSnapshot, worker::snapshotOneLineValues)`。
- 验证:编译 + LIVE A/B 计时(核价段 34s→<8s)+ DB 等价(并行 vs 串行 costing_card_values 一致)。

### Slice 3: 报价侧并行(屏障 1)
- `ConfigureSnapshotService`:抽 `snapshotLines` 循环体为 `public void snapshotSingleLine(quotationId, lineMeta, customerId, componentsSnapshot, comps)`(行为 1:1;含 expand+writeSnapshot+materializeRowData);把循环外 `QuotationIdContext.set` 下沉到 worker。
- 新增 `snapshotQuotationParallel(UUID, boolean skip)`:主线程 load 元数据 + evictAll 一次 + `lineNeedsExpand` 过滤 needExpand 行 + 并行提交 `worker.snapshotOneLineQuote(...)`(`@ActivateRequestContext` + set/clear QuotationIdContext)。
- `QuotationResource` L119:`snapshotQuotation(id,true)` → `snapshotQuotationParallel(id,true)`。保留串行 `snapshotQuotation` 作降级。

### Slice 4: 配置 + 端到端验证
- `application.properties`:`cpq.snapshot.parallel.threads=8`、`cpq.snapshot.parallel.timeout-seconds=120`、`quarkus.datasource.jdbc.max-size=32`。
- LIVE A/B 总计时(47s→~10s)、E2E quotation-flow、DB 全等价、ThreadLocal 隔离(连跑两单)、连接池压测。

## 不变量(实现者)
1. 专用裸 daemon 池,绝不借 worker 池;只首存(needExpand 行)触发。
2. 每 worker `@ActivateRequestContext`(独立 DataLoader+session);经 CDI 代理调用 service(不裸 new)。
3. 每 worker 内 set/finally clear QuotationIdContext(报价侧);核价侧 ThreadLocal 由 service 自管,勿重复。
4. 两屏障:报价并行跑完 allOf.get → 才启核价并行。
5. per-line try/catch 降级、行数权威=snapshot_rows(AP-51)、每 task 自带 lineItemId 写库(AP-37)全保留。
6. 总超时 < HTTP 超时;超时未完成行下次增量自愈。
