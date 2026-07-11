# 首存提速合并规划 — 方案 B(rkf 预取)+ 方案 2(partNo 去重)

> 日期:2026-06-25
> 目标:让"导入 → 首存报价单"在客户端超时内完成,逼近 ~10s SLA
> 关联:`docs/superpowers/specs/2026-06-25-rowkeyfields-cache-firstsave-perf-design.md`(方案 B 详设)
> 硬约束:不触碰 expand/公式/快照求值层并发([[cpq-expand-layer-not-threadsafe]]);落库/计算结果逐位一致;不改 `pg_advisory_xact_lock` 粒度

---

## 1. 总览:两刀 + 一个测量闸门

| 阶段 | 内容 | 性质 | 预期 | 风险 |
|---|---|---|---|---|
| **P1 方案 B** | rkf 纳入 `CardValuesPrefetch` 整单一次 IN 查 | 消冗余静态读 | −14.7s | 极低 |
| **P2 重测闸门** | B 落地后对真实端点(union/bucket ON)重跑深剖 | 校准 | —— | —— |
| **P3 方案 2** | 逐行 build 按 partNo 去重(闸门化) | 消重复料号 expand+assemble | EXP_b 砍半 | **中(Bug B 闸门)** |

**为什么要 P2 闸门**:我此前的深剖跑的是 `prefetch=null/union=null` 纯逐行路径;真实端点已有 C4 union + 报价合桶(部分 partNo 去重已发生)。**方案 2 的真实增量收益必须在 B 落地后、用真实端点路径重测才能确定**,不能凭纯逐行数字承诺。

---

## 2. P1 — 方案 B(rkf 预取)

详见独立 spec。一句话:`assembleTabsWithFormulaResults` 对每行×每组件×每侧各发一次 `SELECT row_key_fields`(2550 次/14.7s),改为在已有 `CardValuesPrefetch` 里加一条 `WHERE id IN(...)` 整单一次查 → 2550→1。**无 evict/无 TTL/无陈旧/逐位等价**。这是零风险、无条件先做的第一刀。

---

## 3. P3 — 方案 2(逐行 build 按 partNo 去重)设计

### 3.1 核心思想

逐行构建循环对 170 行各跑一遍 `buildCardValues + buildCostingCardValues`,但本单只有 **77 个不同料号**。若两行的卡片值输出**逐位相同**,第二行可直接复用第一行结果,**跳过 read + expand + assemble + serialize**。

### 3.2 ⚠️ 安全性分析(为什么不能裸按 partNo)

卡片值输出**并非只依赖 partNo**。经核实(`ComponentDriverService.expand` :204-227 / :159-166),`buildCostingCardValues` 的输出还取决于:

1. **Bug B 专属工序行(lineItemId 维度)**:`expand`/`expandForPartSet` 传 `li.id`;`lineItemId != null` 时**先查该行专属 driver 行**(`quotation_line_item_id = lineItemId` 谓词),无专属行才回落 partNo 行。→ **同料号、不同行可能有不同专属行 → 输出不同**。这正是 C4 union "多不命中"的根因。
2. **editRows / row_data(用户编辑)**:报价侧 `delByComp`(deleted_row_keys)+ 编辑值按行存。→ 同料号、不同编辑 → 输出不同。
3. **compositeType(SIMPLE/COMPOSITE)**:影响 lineItemId 注入与子件聚合语义。

`closure = bomClosureService.compute(partNo)` 仅依赖 partNo(安全);输出 JSON 结构(`buildTabNode`:componentId/tabName/baseRows/editRows/formulaResults/resolvedRows/subtotal)**不含 lineItemId**(同输入即同输出,安全)。

### 3.3 安全去重键 + 适用闸门

**去重键** = `(partNo, compositeType, editSig, delSig, hasSpecializedRows)`,其中:
- `editSig` / `delSig` = 该行 editRows / deleted_row_keys 的指纹(空 → 恒等);
- `hasSpecializedRows` = 该行是否存在 Bug B 专属工序行(有 → 不可与他行去重)。

**适用闸门(只对"干净行"去重)**:仅当 `editSig` 与 `delSig` 为空、且 `hasSpecializedRows=false` 时,该行进 partNo 去重池;否则**逐行回落**(走现状路径,零影响)。

> **导入→首存场景天然全部命中**:导入新建行 editRows/deleted 全空(实测),且 driver 展开日志全为 `no specialized rows -> EMPTY`(无专属行)。故用户的核心场景下 170 行几乎全部 eligible → 去重到 ~77,`EXP_b` 与 assemble 双双砍半。再保存(带编辑)的行自动回落,正确性不破。

### 3.4 与现有 C4 union / 报价合桶的关系(避免重复造轮子)

- 现有 `precomputeCostingDriverUnion`(C4)/ `precomputeQuoteDriverBuckets` 已在 **expand 层**按 partNo 合桶,但:① 受同一 Bug B 闸门限制(C4 常不命中);② 只优化 expand,**不优化 assemble / 不跳过整次 build**。
- 方案 2 是**更高层**的去重:命中即跳过整行 `read+expand+assemble+serialize`,**收益是 expand 合桶的超集**。
- **实现取舍(P2 后定)**:若 P2 重测显示 C4 union 在真实端点已吃掉大部分 expand 去重,则方案 2 退化为"只对干净行做 assemble 层去重"(增量小);若 C4 仍多不命中(预期),方案 2 在干净行上**整体 build 去重**收益最大。**故必须先过 P2 闸门再定方案 2 的精确落点。**

### 3.5 实现骨架(P3,待 P2 校准后细化)

逐行循环外层加 per-request 去重池:

```java
Map<String, BuiltCardValues> dedupPool = new HashMap<>();   // key = (partNo|compositeType|editSig|delSig), 仅 eligible 行进
for (line) {
    if (eligible(line)) {                                    // editSig/delSig 空 + 无专属行
        String key = dedupKey(line);
        BuiltCardValues hit = dedupPool.get(key);
        if (hit != null) { reuse(hit); continue; }           // 跳过 read+expand+assemble+serialize
        BuiltCardValues built = buildBoth(line);             // 正常构建(含方案 B 的 rkf 预取)
        dedupPool.put(key, built);
        apply(built);
    } else {
        apply(buildBoth(line));                              // 不 eligible → 逐行回落(现状)
    }
}
```

**输出复用纪律(AP-37)**:复用的是**序列化后的 JSON 字符串**(`quoteCardValues`/`costingCardValues` 已是 String),逐行 `li.persist` 各自写库;若复用对象引用须深拷贝。不得复用可变 `JsonNode`。

### 3.6 开放问题(P2/architect 解决)

1. `hasSpecializedRows` 如何低成本判定?(整单一次查 `quotation_line_item` 专属行存在性 → Set<lineItemId>;或依赖"首存新行恒无专属行"的更强前提并 assert)
2. compositeType=COMPOSITE 行是否纳入去重?(子件聚合语义,建议先排除,逐行回落)
3. 去重池命中率实测(P2 重测顺带统计 distinct eligible partNo / 总行)。

---

## 4. 编排顺序与测量闸门

```
P1 方案 B(隔离 worktree, TDD, Golden md5)
   └─合并 master
P2 重测闸门(真实端点 union/bucket ON):
   ① 复跑深剖 → 确认 ASM_a≈0、量出真实 EXP_b
   ② 统计 eligible 行数 / distinct eligible partNo(定方案 2 命中率)
   ③ 决策:EXP_b 是否仍是大头?方案 2 增量是否值得?
   └─走 architect 评审方案 2 精确落点(Bug B 闸门是雷区)
P3 方案 2(隔离 worktree, TDD, Golden md5 + 双 spec E2E SIMPLE/COMPOSITE)
   └─合并 master
```

**为什么串行而非并行**:方案 2 的去重池命中"正常构建"那一支会复用方案 B 的 rkf 预取;且方案 2 的收益估算依赖 B 落地后的真实基线。先 B 后 2,中间用 P2 校准,避免在错误基线上设计。

---

## 5. 综合速度结论(诚实版)

真实端点首存投影(单事务冷态,已含现有 B2 prefetch + C4/bucket):

| 阶段 | 逐行构建+落库 | 能否进 30s 客户端超时 | 能否达 ~10s SLA |
|---|---|---|---|
| 现状 | ~35–48s | ❌ 常超时取消 | ❌ |
| +方案 B | ~20–34s | ⚠️ 顺境可能,不稳定 | ❌ |
| +方案 B + 方案 2 | ~15–22s | ✅ 较稳 | ⚠️ 接近未达 |
| +B+2+落库/getById | ~10–15s | ✅ | ✅ 量级达成 |

**结论三句话**:
1. **方案 B 必做、零风险,但单独不够**——消掉 rkf 后核价 driver expand(真实远程 I/O)立刻成新大头。
2. **方案 B + 方案 2 配套,才能把导入→首存稳进 30s 超时内(~15–22s)**;方案 2 在用户核心场景(干净导入行)几乎全命中,把 expand+assemble 砍半。
3. **要稳达 ~10s SLA,还需第三步**:核价 expand 剩余的 ~5–12s 是远程 RTT × 往返数的硬地板(靠扩大 union 闸门 / 减往返),叠加落库 9s 与 getById 6–9s 的优化。这部分与正在进行的 **saveDraft 集合化重构** 同源,建议并轨。
4. **并发放大已解决**:用户曾见 ~145s/30s 取消,主因 autosave 风暴占满线程池——止血已合 master,去掉后单跑才是上表量级。

> 一句话给决策者:**B 立即做(稳赚 14.7s),2 紧随其后(配套才稳进超时);~10s SLA 要等核价 expand + 落库这两块硬 I/O 在集合化重构里一并收口。**

---

## 6. 共用:不变量与等价验证(B 与 2 均适用)

- **Golden md5 等价**:`GoldenCardValuesEquivTest` 钉死 `8f0c37a4`/`a8f17a74` 四份输出 md5,改动前后命中 + determinism 连跑两次。方案 2 额外:**去重命中行 vs 逐行构建行 md5 必须逐位相同**(证明去重无副作用)。
- **闸门等价**:方案 2 对 non-eligible 行走回落,与现状逐位一致;对 eligible 行去重结果与"不去重逐行算"逐位一致。
- **E2E**:协议级改动跑 `quotation-flow.spec.ts`(SIMPLE)+ 方案 2 必加 `composite-product-flow.spec.ts`(COMPOSITE),`'加载中'=0`、8 Tab 渲染正常。
- **往返/桶复测**:每阶段后复跑深剖探针,报告四桶占比变化(证明靶子被打掉)。
- **主线亲验**:派 agent 实现后主线亲跑测试/查 DB([[cpq-deliver-agents-overreport]]);worktree 子代理可能提交到 master,验收查 `git branch --contains`([[cpq-worktree-subagent-commits-to-master]])。

---

## 7. 关键文件索引

- 方案 B:`CardSnapshotService`(`CardValuesPrefetch`:590 / `precomputeCardValuesPrefetch`:604 / `assembleTabsWithFormulaResults`:943 / build*:667,748)。
- 方案 2:同上 build 循环 + `QuotationResource.saveDraft` 逐行循环(:150-164)+ `ComponentDriverService.expand`(:204-227,Bug B 专属行)+ `eligibleForBomUnion` / `precomputeCostingDriverUnion`(C4 闸门参照)+ `precomputeQuoteDriverBuckets`(报价合桶参照)。
- 测试:`GoldenCardValuesEquivTest` / `B2BatchEmEquivTest` / `CostingPartSetUnionEquivTest`(闸门等价范式)/ 深剖探针。
