# 打开报价单性能 — 两轨合并实现计划(请求次数 + 每次业务逻辑)

> 立项 2026-06-24。目标:打开报价单时**请求次数降到最少** + **第一次必须算的那次更快**。
> 全部实测支撑(见 §1),全后端 + 局部前端,不碰精度契约、不做前端权威重写。
> 前置:Phase 1+2(批量写 + 报价侧合桶)已合并 master(`93c7768`)。

---

## 1. 实测依据(本轮全部 profile 结论)

- saveDraft 首存(罗克韦尔 170 行)≈ 145s = snapshotQuotation 12s(已优化)+ 逐行 card values ~132s。
- card values 88s 分项:**assemble 33s**(`computeRows` 跑 2 遍)+ baseRows 25s + **EM 查询 15s** + Excel 11s + 闭包 1s + **公式求值 0.1s** + 序列化 0.02s。
- **结论:慢在"计算周围的管道"(重复 computeRows + EM I/O + Jackson 建节点),不是公式本身。**
- 前端打开:**batch-expand 已按快照 gate**(`useSnapQuote`/`useSnapCosting` → `EMPTY_LINEITEMS`,不发);**batch-evaluate 未 gate**(`usePathFormulaCache` :2731/:3011 + `useLinkedExcelRows`,有快照也照发);**全工程无 AbortController**(enrich 后重发 + 30s 取消)。
- 单卡编辑 ≈ 0.24s/卡(可接受)。

---

## 2. 两轨目标

| 轨 | 改什么 | 目标 |
|---|---|---|
| **A 请求次数(前端)** | gate batch-evaluate + AbortController | 打开**已存单 ≈ 1 次 GET**;没算过的单各接口**各 1 次**(不重发) |
| **B 每次逻辑(后端)** | memoize computeRows + 批量 EM | 第一次必须算的那次:88s → ~50s 级(再叠合桶/Excel 往下压) |

---

## 3. Track A — 前端请求次数

### Task A1 — gate `usePathFormulaCache` 的 batch-evaluate(报价 + 核价)
- 现状:`usePathFormulaCache(lineItems)`(:2731)/`usePathFormulaCache(costingLineItems)`(:3011)**不看快照**,恒发 batch-evaluate。
- 改:有快照时传**空集**(同 batch-expand 的 `useSnapQuote ? EMPTY_LINEITEMS : lineItems` 套路)。
- **🚨 前置核对(必做,防 gate 错)**:`usePathFormulaCache` 注释标明它还**给 LIST_FORMULA 的 BNF fallback 用**(QuotationStep2 :1440)。落地前**逐字段确认**:快照模式(`useSnapQuote=true`)下渲染是否真的不依赖 path cache 的任何输出。
  - 列出所有消费 `quotationPathCache` 的渲染分支(LIST_FORMULA / DATA_SOURCE BNF / default_basic_data_path …);
  - 逐个确认快照模式下这些值已在 `quote_card_values`/`snapshot_rows` 里;
  - 任一字段类型在快照模式仍需 path cache → **该类型不能 gate**(保留按需取),只 gate 确认无依赖的部分。
- 测:E2E `quotation-flow` 打开已存单 → Network 断言 **batch-evaluate 0 次**;全 8 Tab `加载中=0`、值与 gate 前逐格一致(AP-31)。

### Task A2 — gate `useLinkedExcelRows` 的 batch-evaluate
- 同 A1:Excel 行的 batch-evaluate(`useLinkedExcelRows` :263)按快照 gate;前置同款"快照模式不依赖"核对(Excel 值已在 `quote_excel_values`/`costing_excel_values`)。
- 测:打开已存单 Excel 视图 `加载中=0`、值不变、batch-evaluate 0 次。

### Task A3 — batch-expand / batch-evaluate 加 AbortController
- 现状:`useDriverExpansions` :360 `batchExpandDriver(...).then`、`usePathFormulaCache` :234 / `useLinkedExcelRows` :263 `batchEvaluate(...)` 均无中断。
- 改:每个 effect 持 `AbortController`,下一轮 fingerprint 变 / 卸载时 `abort()` 上一轮;fetch 传 signal,catch 里忽略 AbortError。
- 针对"没算过的单"(快照缺失走实时)消掉 enrich 重发 + 30s 取消。
- 测:导入后第一次打开,Network 断言每接口**无"已取消"项**、batch-expand/evaluate 各 ≤1 轮。

### Track A 结果
- 打开**已存单**:无 batch-expand(已 gate)+ 无 batch-evaluate(A1/A2)→ **≈1 次 `draft`/GET 带快照渲染**。
- 打开**没算过的单**:batch-expand + batch-evaluate **各 1 次**(A3 防重发)+ 算完 saveDraft。

---

## 4. Track B — 后端每次业务逻辑

### Task B1 — memoize `computeRows`(assemble 33s → ~16s)
- 现状:`assembleTabsWithFormulaResults` PASS1(:856 `computeTabSubtotalsByColumn`→`computeRows`)+ PASS2(`calculate`→`computeRows`)**每 tab 把 computeRows 跑 2 遍**(同输入)。
- 改:每 tab 的 `computeRows` 结果**算一次缓存复用**(键 = tab 的 cid + baseRows/editRows 引用 + componentSubtotals 快照)。PASS1 算小计、PASS2 填结果共用同一份 `List<RowResult>`。
- 注意:PASS1 在 `componentSubtotals` **累加中**调用(后 tab 依赖前 tab 小计),memoize 要保证**依赖顺序不变**(同一 tab 的 computeRows 在其依赖的 componentSubtotals 就绪后只算一次)。
- 测:A/B 等价单测——memoize 前后 `assembleTabsWithFormulaResults` 输出 JSON **逐位相同**(对罗克韦尔多行);`FormulaCalculatorTest` 全绿;实测 assemble 分项耗时下降。

### Task B2 — 批量 EM(EM 15s → ~1s)
- 现状:`buildCardValues`/`buildCostingCardValues` 每行 `SELECT components_snapshot`(同模板重复读+解析 ×170)+ 逐行 compdata 查询。
- 改:
  - 模板 snapshot **按 templateId 在请求内 parse 一次复用**(全单同 2 个模板);
  - compdata **整单一次 IN 查**(复用已落地的 `ConfigureSnapshotService.loadSnapshotRowsByLines`),`buildCardValues` 加接受预加载数据的重载;
  - 入口在 saveDraft 首存循环(`snapshotLines` 已是合桶现场)预取后传入。
- 测:A/B 等价(预加载 vs 逐行查,落库逐位相同)+ 实测 EM 分项耗时降到 ~1s(已验证批量版 0.19s)。

### Task B3 —(可选,后置)批量核价 driver expand
- `baseRows 25s` 里的 driver expand 部分:把报价侧合桶(Phase 2 已做)延伸到核价 spine,减远程往返。视 B1/B2 后实测剩余再定。

### Track B 结果(预估,基于实测)
- 88s − B1(~17s)− B2(~14s)≈ **~57s**;+ B3 + Excel 优化往 ~30s 以下压。首存大单仍配进度条/增量兜底。

---

## 5. 任务清单(subagent-driven,每 Task 独立可测)

| Task | 轨 | 内容 | 风险 |
|---|---|---|---|
| A1 | 前端 | gate usePathFormulaCache(+逐字段核对) | 中(gate 错致缺值) |
| A2 | 前端 | gate useLinkedExcelRows | 中 |
| A3 | 前端 | AbortController(expand+evaluate) | 低 |
| B1 | 后端 | memoize computeRows | 中(顺序依赖) |
| B2 | 后端 | 批量 EM(模板 parse 一次 + compdata IN) | 低-中 |
| B3 | 后端 | 批量核价 driver expand(可选) | 中 |

依赖:A1/A2 须先完成"快照模式不依赖 path cache"核对;B1/B2 互相独立可并行设计、串行落库验证。

---

## 6. 验证铁律(每 Task 必带)

1. **A/B 逐位等价**:后端改动(B1/B2)前后落库 JSON 逐位相同(罗克韦尔 `8f0c37a4` 多行 md5);前端改动(A1/A2)gate 前后渲染值逐格相同。
2. **E2E**:`quotation-flow.spec.ts` 打开已存单 → 全 Tab `加载中=0` + **Network 断言 batch-evaluate/batch-expand 调用次数符合预期**(已存单=0,新单各≤1)。AP-31 协议(无永久"加载中")。
3. **协议自检**:A1/A2 改渲染取数链路 → 走 AP-31 清单;涉及 cache key → AP-37。
4. **实测前后对比**:复用本轮探测手法(分项计时 + Network 计数),给"打开请求数"和"首存耗时"改造前后硬数。
5. 后端既有套件全绿(`FormulaCalculatorTest`/`SnapshotReconcileTest`/`CostingPartSetUnionEquivTest` 等),排除分支既有红。

---

## 7. 落地顺序(建议)

1. **A3(AbortController)**:最低风险,先消重发/取消。
2. **B1(memoize computeRows)**:最清晰的后端去重,A/B 等价护栏强。
3. **B2(批量 EM)**:复用现成 `loadSnapshotRowsByLines`。
4. **A1/A2(gate batch-evaluate)**:先做"逐字段核对",确认安全再 gate。
5. B3 视实测剩余决定。
- 每 Task:起隔离 worktree(或复用)→ 实现 + A/B + E2E → 主线亲验 → 安全合并。

---

## 8. 预期总效果

- **打开已存单**:≈1 次 GET 读快照渲染,秒回(无 batch-expand/evaluate)。
- **打开没算过的单**:各接口各 1 次(无重发)+ 首存计算从 ~145s(其中 card values 88s)经 B1/B2 降到 ~50-57s,配进度条/增量;之后转入"已存单"快路径。
- **编辑**:单卡 ~0.24s(已具备)。
- 全程**后端权威不变**(无双引擎/精度契约),前端仅"按快照 gate + 中断重发"。
</content>
