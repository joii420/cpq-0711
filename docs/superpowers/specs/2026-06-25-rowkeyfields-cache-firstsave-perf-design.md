# 方案1 设计文档 — saveDraft 首存 rowKeyFields 冗余 DB 往返根治

> 立项日期:2026-06-25(2026-06-25 经独立评审后修订:主方案由"进程缓存 A"改为"per-request 批量预取 B")
> 类型:性能优化(等价优先,不改任何计算结果)
> 影响模块:报价单首存 / 卡片值快照构建(`CardSnapshotService.assembleTabsWithFormulaResults`)
> 前置剖析:`docs/handover/2026-06-25-*`(四桶 + 子步骤深剖)
> 硬约束:不触碰 expand/公式/快照求值层并发([[cpq-expand-layer-not-threadsafe]]);落库/计算结果逐位一致

---

## 0. 修订说明(评审采纳记录)

初版选"进程级缓存 A"。经一名独立评审 agent 对抗性核验,采纳其核心意见 **N1**:本痛点是"**单次保存内的 N+1**",`per-request 批量预取(方案 B)` 即可根治,且 B **无需 evict、无 TTL、无陈旧、无跨请求 `em`/线程问题**——把进程缓存方案的三重风险(失效完整性、TTL 弱一致、loader 事务作用域)一次性消除,改动面更小,且与既有 `CardValuesPrefetch`(B2 优化)**完全同构**。跨请求复用收益经核算仅 ~15 次查/请求(可忽略),不足以支撑进程缓存的复杂度。故本版**改用方案 B**。评审其余意见(N2 只读防御、N3 片段补 `containsKey`、N4 计数手法、import 绕过点登记、Flyway 安全性说明)一并并入。

---

## 1. 一句话

saveDraft 逐行构建卡片值时,`assembleTabsWithFormulaResults` 对**每行 × 每组件 × 每侧**各发一次 `SELECT row_key_fields FROM component WHERE id=?`(实测 2550 次/14.7s,占逐行构建 49.6%)——而读的是**一次保存内永不变化的静态元数据**。本方案在**已有的 `CardValuesPrefetch`(整单一次预取)里加一条 `WHERE id IN(...)` 批量查**,把 2550 次往返压到 **1 次**,消除这 14.7s,且产出逐位等价。

---

## 2. 修改原因(为什么必须改)

### 2.1 剖析实测证据

对基准单 `8f0c37a4`(罗克韦尔,170 行 / 77 个不同料号 / 报价+核价双模板)冷态逐行构建卡片值,按子步骤插探针实测:

| 子步骤 | 耗时 | 占比 | 调用次数 | 性质 |
|---|---|---|---|---|
| **`ASM_a` rowKeyFields 加载** | **14770 ms** | **49.6%** | **2550** | 🔴 冗余 DB 往返 |
| `EXP_b` 核价 driver expand SQL I/O | 10318 ms | 34.6% | 850 | 🟠 真实远程 I/O |
| 模板/清单读(QUOTE_1a+COST_1+EXP_a) | ~3220 ms | 10.9% | 510 | 🔴 170× 冗余 |
| `ASM_d` PASS2 真计算+组装 | 217 ms | 0.7% | — | 🟢 真计算 |
| **`CR_4` 公式求值(算术)** | **101 ms** | **0.3%** | 3570 | 🟢 真计算 |
| 其余(rowkey/collect/unitconv/序列化) | <120 ms | <0.4% | — | 🟢 |

**关键认知**:所谓"公式计算慢"是错觉——真正的算术求值只占 **0.3%(101ms)**,序列化 ≈ 0%。**逐行构建的近一半时间花在重复读一张永不变化的静态表上。** 这是 I/O 浪费,不是 CPU 瓶颈。

### 2.2 浪费的结构性来源

`assembleTabsWithFormulaResults`(`CardSnapshotService.java` 约 :943)对 snapshot 里每个组件调:

```java
for (JsonNode tab : snapshot) {
    String cid = tab.path("componentId").asText("");
    if (!rkfByComp.containsKey(cid)) rkfByComp.put(cid, loadRowKeyFieldsNode(cid)); // ← 局部 Map,每次 assemble 重建
}
```

`rkfByComp` 是 **assemble 调用内的局部 Map**,**生命周期仅一次 assemble**(单次 assemble 内已按 cid 去重,但跨 assemble 不复用)。而 `buildCardValues` / `buildCostingCardValues` **每行各调一次 assemble**,故:

- `loadRowKeyFieldsNode(cid)` → `loadRowKeyFields(cid)`(`CardSnapshotService.java` 约 :1919)执行
  **`SELECT row_key_fields FROM component WHERE id = :cid`** —— 一次远程往返。
- 调用次数 = 行数(170) ×(报价侧 distinct 组件数 + 核价侧 distinct 组件数)≈ **2550 次**。
- 远程 DB RTT ~8–13ms、单查 ~5.8ms → **14.7s**。

### 2.3 为什么是"纯浪费"

`component.row_key_fields` 是组件的**静态配置属性**:
- 它只在**组件编辑**时变化(`ComponentService.create` 约 :146 / `update` 约 :229),一次报价单保存期间恒定不变。
- 同一 componentId 在整单 170 行里反复查到的**值完全相同**(`component.id` 是 PK、无版本/is_current 列,DB 实测,一个 componentId 唯一对一份 row_key_fields)。
- 因此 2550 次查询里,**只有"去重组件数"次是有信息量的**(本单 ~15 个不同组件),其余 ~2535 次是同值重查。

**结论**:这是教科书式的 N+1——把"每存查一次"的静态读放进了"每行 × 每组件"的双重循环。

### 2.4 它在真实生产端点同样存在(非测试假象)

真实 saveDraft 端点经 `snapshotLineValuesWithUnion → buildCardValues/buildCostingCardValues(prefetch)`。**B2 `CardValuesPrefetch` 只缓存了 `template.components_snapshot` 与 `quotation_line_component_data`(`CardSnapshotService.java:590-597`),从未覆盖 rowKeyFields**;`assembleTabsWithFormulaResults` 内部无条件查库。故这 14.7s 在线上原样发生,是用户"首存超时"的最大单一构成项。

---

## 3. 修改原理

### 3.1 为什么不能"直接读内存 snapshot 的 tab 节点"(已证伪)

初判曾设想"rowKeyFields 已冻进 tab,直接 `tab.path("row_key_fields")` 即可,零查库"。**经 DB 实测证伪**:

```
template.components_snapshot 的 tab 顶层键(罗克韦尔模板,实测):
  componentCode / componentId / componentName / componentType /
  data_driver_path / excelColumns / fields / formula_assignments /
  formulas / id / preset_rows / sortOrder / tabName / tree_config
```

**不含 `row_key_fields` / `rowKeyFields`**——这正是 `buildCardValues` 必须另查 `component` 表的原因(亦与 `ExcelViewService.java:1012` 注释"componentsSnapshot 不含此字段"吻合)。
> 注:`CardSnapshotService:182/241` 注释提到的"行键冻进结构"指的是 **`ensureStructure` 构建的卡片结构快照(quote_card_structure)**,与 `buildCardValues` 读的 `template.components_snapshot` 是**两份不同快照**,勿混淆。

### 3.2 候选方案对比

| 方案 | 原理 | 收益 | 风险 | 取舍 |
|---|---|---|---|---|
| **B. per-request 批量预取**(选定) | 在已有 `CardValuesPrefetch` 加一条 `SELECT id,row_key_fields FROM component WHERE id IN(...)`,整单一次,透传进 assemble | 单存内 2550→**1** | **极低**:per-request、无 evict、无 TTL、无陈旧、无跨请求 em | ✅ 选定 |
| A. 进程级缓存 componentId→rkf | 首查后缓存,跨行/跨请求复用,组件改动 evict | 2550→~15(首触)→0 | 中:须穷举失效点(evict 完整性)、TTL 弱一致、loader 不能捕获请求级 `em` | ❌ 弃(收益增量≈0,风险更大) |
| C. 烤进 template.components_snapshot | 刷新 snapshot 时写入,读内存零查 | 彻底零查 | **高**:改 snapshot schema + 须重刷所有 PUBLISHED 模板,踩 AP-39"snapshot 残留老字段"族 | ❌ 暂不(留未来统一快照演进) |

**选 B 弃 A 的关键论证(评审 N1)**:本痛点是"**单次保存内**的 N+1"(2550 次都在一个请求里),per-request 预取已**完全根治**。方案 A 的唯一额外卖点是"跨请求复用",但跨请求只省 ~15 次查/请求(A 自身首触后即 ~0.1s)——**几乎可忽略**,却要背 evict 完整性 / TTL 弱一致 / loader 事务作用域三重风险。**B 用更小改动面拿走 ~99% 收益且免除全部三重风险。**

### 3.3 选定方案 B — 设计细节

#### 3.3.1 扩 `CardValuesPrefetch` 结构

`CardValuesPrefetch`(`CardSnapshotService.java:590`)新增一字段:

```java
public static final class CardValuesPrefetch {
    final Map<UUID, JsonNode> templateSnapshotById;
    final Map<UUID, List<Object[]>> compDataByLine;
    final Map<UUID, JsonNode> rowKeyFieldsByComp;   // ★ 新增:componentId → rowKeyFields(已 readTree)
    ...
}
```

#### 3.3.2 在 `precomputeCardValuesPrefetch` 批量预取(整单一次)

`precomputeCardValuesPrefetch`(:604)**已经解析了报价+核价两个模板 snapshot**(`parseTemplateSnapshotInto`,:610-611)。在此基础上:

1. 从两份已解析的 snapshot 的各 tab 节点收集 **distinct componentId**(`tab.path("componentId")`);
2. 一条批量查:`SELECT id, row_key_fields FROM component WHERE id IN (:compIds)`;
3. 逐行 `MAPPER.readTree(row_key_fields)` 存入 `rowKeyFieldsByComp`(null/空 → 不放,消费方回落)。

→ **整单 1 次 IN 查**(本单 ~15 行结果,~6ms)替代 2550 次单查。

#### 3.3.3 透传进 assemble(零破坏回落)

`assembleTabsWithFormulaResults` 现有 3/4/5 参重载链。在最深重载新增一个 `Map<String,JsonNode> rkfPrefetch` 参(各 delegating 重载传 null),rkf 加载循环改为:

```java
for (JsonNode tab : snapshot) {
    String cid = tab.path("componentId").asText("");
    if (!rkfByComp.containsKey(cid)) {
        JsonNode hit = (rkfPrefetch != null) ? rkfPrefetch.get(cid) : null;
        rkfByComp.put(cid, hit != null ? hit : loadRowKeyFieldsNode(cid)); // ★ 命中用预取,未命中/无预取回落逐行查
    }
}
```

`buildCardValues`/`buildCostingCardValues` 把 `prefetch.rowKeyFieldsByComp` 传下去。

**零破坏纪律**:
- `prefetch == null`(旧调用方 / 剖析纯逐行路径 / B2 预取失败降级)→ `rkfPrefetch == null` → 全程走 `loadRowKeyFieldsNode`,**行为与改造前 1:1 不变**。
- 某 cid 不在预取 map(理论上不会,防御)→ 该 cid 回落单查,**仍正确**。

#### 3.3.4 为什么 B 不需要 evict / TTL / 缓存键安全分析

- `CardValuesPrefetch` 是 **per-request 临时对象**(每次 saveDraft 新建,随请求销毁),**不跨请求存活** → 永不陈旧 → **无需任何失效机制**。
- 每次保存都现查当前 `row_key_fields` 真值 → 组件编辑后下次保存自动读到新值,**无陈旧窗口**。
- 不引入进程级共享态 → 无缓存键串号问题(方案 A 才需论证 componentId 维度;B 无关)。
- 预取在 `precomputeCardValuesPrefetch`(`@Transactional`)内用 `em` 本就合法(请求级事务内),**无方案 A 的 loader 跨请求捕获 `em` 隐患**。

### 3.4 修改后行为(原理验证)

- 单次 saveDraft:`precomputeCardValuesPrefetch` 一次 IN 查得全部组件 rkf;之后 170 行 × 每组件 assemble **0 往返**,读内存 map。整单 2550 次 → **1 次**。
- **值逐位不变**:预取存的是与原 `SELECT row_key_fields` 完全相同的 JSON 经 `MAPPER.readTree` 的结果;原代码本就是 `loadRowKeyFields → readTree`,本方案只是把"每行重查重解析"提前为"整单查一次解析一次"。`computeRowKey`/`uniquifyRowKeys`/`rowFingerprint` 的输入输出**完全不变** → effKey、行键唯一化、墓碑过滤、formulaResults、resolvedRows、小计**全部逐位一致**。

---

## 4. 影响面与不变量保护

| 不变量 | 是否触碰 | 说明 |
|---|---|---|
| 行数 / 列值 / 公式结果 / 料号 | ❌ 不变 | 复用同值 rowKeyFields,计算输入不变 |
| effKey / 行键唯一化(AP-54) | ❌ 不变 | `computeRowKey` 入参 rowKeyFields 同值 |
| 永久删除墓碑过滤(AP-54) | ❌ 不变 | `rowFingerprint` 用的 rowKeyFieldNames 同源 |
| v6-N 草稿行键覆盖 `rkfOverride` | ❌ 不变 | override 是新建 ArrayNode,经 `rkfByComp.putAll` 覆盖**局部 Map**,不写预取(评审核验 :962-963/2077-2087) |
| expand / 公式 / 快照求值层并发 | ❌ 不碰 | 不进 expand 层;预取对象 per-request 不共享 |
| snapshot schema / PUBLISHED 模板 | ❌ 不改 | 方案 B 不动 snapshot(区别于方案 C) |

**只读纪律(评审 N2 升级为硬规范)**:预取 map 的 `JsonNode` 在单次 saveDraft 内被多行共享。注意 `MAPPER.readTree` 返回的是**可变子类 `ObjectNode`/`ArrayNode`**,"只读"靠纪律非类型保证。所有现有消费方(`computeRowKey`/`rowKeyFieldNamesOf`/`filterEditRowsToNewBaseRows`/`RowKeyUniquenessService`/`materializeComponentRows`)经评审手验 4 处 + 子代理穷举 9 处**均只读**(`.isArray()/.get()/.asText()/迭代`,零 mutation)。**PR 自检硬项**:grep 确认无 `(ObjectNode)`/`(ArrayNode)` cast 后 `.put/.add` 作用于 rkf 节点;若将来有写需求,必须先 deep-copy。

---

## 5. 等价性验证方案(铁律,每项必带)

1. **Golden md5 等价**:复用 `GoldenCardValuesEquivTest`——改动前后,罗克韦尔 `8f0c37a4`(golden `3837c2bd…`)与 `a8f17a74`(77 行,golden `2cc56fea…`)四份输出(Q/C/QE/CE)md5 必须命中,含 determinism 连跑两次。
2. **往返计数断言(评审 N4:用精确计数,非 Hibernate 全局统计)**:在 `loadRowKeyFields` 内置一个**测试可读的 miss 计数器**(或对该方法打 spy),断言整单逐行构建期间 `row_key_fields` 单查次数由 **2550 → 0**(全部命中预取),预取 IN 查 **1 次**。**不要**用 `Statistics.getPrepareStatementCount`——它是全口径(混入 tombstone/compdata/snapshot 等查询),无法隔离 rkf 那 2550 次。
3. **降级正确性测试**:`prefetch=null`(模拟 B2 预取失败)时 build 结果与带预取**逐位一致**(回落路径等价)。
4. **既有套件全绿** + 协议级 E2E(`quotation-flow.spec.ts`):`'加载中' final count = 0`,8 Tab 渲染正常(行键直接影响渲染对齐,必须 E2E 兜底)。
5. 任一不过 = 回滚不合入;派 agent 实现后主线亲跑测试/查 DB([[cpq-deliver-agents-overreport]])。

---

## 6. 风险与回滚

- **kill switch**:`cpq.firstsave-rkf-prefetch`(默认 ON);off 时 `precomputeCardValuesPrefetch` 不建 `rowKeyFieldsByComp`(留空)→ assemble 全程回落 `loadRowKeyFieldsNode`(与改造前 1:1),零等价风险灰度。
- **无陈旧风险**:per-request 预取每存现查,组件编辑后即时反映,**不存在方案 A 的 evict/TTL 弱一致问题**。
- **预取失败降级**:`precomputeCardValuesPrefetch` 整体 try/catch(现有),失败返回空 map → 回落逐行查,**只慢不错**。
- **回滚**:关 kill switch 即恢复;或 revert 单 commit(改动集中:`CardValuesPrefetch` 加 1 字段 + `precomputeCardValuesPrefetch` 加 1 段 IN 查 + assemble 重载加 1 参 + 2 个 build 方法传参)。

### 6.1 已知边界 / 绕过点登记(评审补充)

- **`ComponentImportService.commit()` 直接 `persist()` 绕过 `ComponentService`**:当前 **inert**——导入 bundle 不含 rowKeyFields(`grep` 实证为空),导入组件 rkf 恒 NULL。**方案 B 对此天然免疫**(per-request 每存现查,无缓存可陈旧);仅在方案 A 下才需登记为失效绕过点。此处记录以备未来给 bundle 加 rkf 时心里有数。
- **Flyway 写路径(V278/279/287)**:方案 B 每存现查,与迁移天然无冲突(无论迁移何时跑,下次保存都读最新值)。

---

## 7. 落地步骤(subagent-driven,隔离 worktree)

1. 起隔离 worktree 分支(`superpowers:using-git-worktrees`)。
2. TDD:先写"往返计数 2550→0 + 预取 1 次"失败测试(用 rkf miss 计数器)+ 复用 Golden md5 护栏。
3. 实现:`CardValuesPrefetch` 加 `rowKeyFieldsByComp` + `precomputeCardValuesPrefetch` 加 IN 查 + assemble 最深重载加 `rkfPrefetch` 参并回落 + 2 个 build 方法传参 + kill switch。
4. 主线亲跑:Golden md5 命中 + 往返计数断言(2550→0,IN=1)+ 降级等价测试 + 既有套件 + E2E。
5. 实测复跑深剖探针确认 `ASM_a` 桶 ≈ 0;报告新四桶占比。
6. 用户确认 → 安全合并 master + 清理 worktree([[cpq-auto-finish-merge-e2e-cleanup]])。

---

## 8. 预期收益与"能否达到目标速度"结论

### 8.1 本方案单项收益

- 逐行构建 `ASM_a` 桶:**14.7s → ~6ms**(整单 1 次 IN 查);单项 **−14.7s**。
- 该 14.7s 是冷态 profiling 实测(`SaveDraftProfileTest` 锁定 `8f0c37a4`),数字内部自洽(2550 × ~5.8ms),量级可信(精确值随远程 DB 负载略动)。

### 8.2 是否达到用户想要的速度?——**必要但不充分,需叠加方案 2**

诚实结论:**方案 B 是消除最大单一浪费的"第一刀",但单靠它不足以让首存稳进客户端 30s 超时内,更达不到 ~10s SLA 目标。** 因为消掉 rkf 后,**核价 driver expand(`EXP_b`,10–24s 真实远程 I/O)** 立即成为新的最大头。

真实端点首存耗时投影(单事务冷态,已含 B2 prefetch):

| 阶段 | 现状 | 仅做方案 B | B + 方案 2(partNo 去重 170→77) | B + 2 + 3 |
|---|---|---|---|---|
| rkf 加载(ASM_a) | 14.7s | **~0** | ~0 | ~0 |
| 核价 expand(EXP_b) | 10–24s | 10–24s | **~5–12s**(料号去重砍半) | ~5–12s |
| driver 清单查(EXP_a) | 0.9s | 0.9s | ~0.4s | **~0**(hoist) |
| 真计算+组装 | ~0.5s | ~0.5s | ~0.3s | ~0.3s |
| 落库 snapshotQuotation | ~9s | ~9s | ~9s | ~9s |
| **逐行构建+落库 合计** | **~35–48s** | **~20–34s** | **~15–22s** | **~14–21s** |
| getById(打开) | 6–9s | 6–9s | 6–9s | 6–9s |

- **仅方案 B**:首存约 **20–34s** —— 顺境下可能不再撞 30s 超时,但**不稳定**(核价 expand 波动大时仍可能超)。
- **B + 方案 2(按 partNo 去重)**:约 **15–22s** —— 较稳进 30s 超时内,但仍达不到 ~10s SLA。
- **B + 2 + 落库优化 / getById 优化**:才有望进 **~10–15s** 量级,稳达目标。
- **并发放大已另行解决**:用户曾观测 ~145s/30s 取消,主因是"打开触发 autosave 风暴占满线程池"——该止血(autosave 默认拒绝门 + 悲观锁串行化)已合 master,与本方案正交。去掉风暴后,单跑首存才是上面这张表的量级。

**一句话**:方案 B 必做(零风险砍掉 14.7s),但要达到用户期望的"首存不超时 / ~10s 级",**必须把方案 B 与方案 2(partNo 去重)配套落地**;核价 driver expand 这块真实远程 I/O 是去掉 rkf 后的下一个、也是最后一个硬骨头。建议:本方案先落,随即接方案 2。

---

## 9. 关键文件索引

- `cpq-backend/.../quotation/service/CardSnapshotService.java`:`CardValuesPrefetch`(:590)、`precomputeCardValuesPrefetch`(:604,IN 查落点)、`parseTemplateSnapshotInto`(:635,收集 componentId 处)、`assembleTabsWithFormulaResults`(约 :943,rkf 加载循环)、`buildCardValues`(:667)/`buildCostingCardValues`(:748,传参)、`loadRowKeyFieldsNode`(约 :1860)、`loadRowKeyFields`(约 :1919,被替代的单查)。
- `cpq-backend/.../quotation/resource/QuotationResource.java`:saveDraft 端点(:115)调 `precomputeCardValuesPrefetch`(:159)+ `snapshotLineValuesWithUnion`(:160)。
- `cpq-backend/.../component/service/ComponentService.java`:`row_key_fields` 写入点(create :146 / update :229)—— 方案 B 无需在此挂钩(留作信息)。
- 测试:`GoldenCardValuesEquivTest`(golden md5 护栏)、`B2BatchEmEquivTest`(prefetch 等价范式)、深剖探针(复测桶占比)。
