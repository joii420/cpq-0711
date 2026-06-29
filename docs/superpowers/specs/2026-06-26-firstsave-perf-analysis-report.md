# 首存性能深度分析报告 — 2026-06-26

> 供查阅。基于实测埋点(`[draft-profile]`/`[be-profile]`/`[be-bucket]`/`[s3-detail]` 落 `backend.log`)+
> 离线 profiling(`S3SegmentProfileTest`,可 `-Dqid=<单据>` 指定)。所有优化均已合 master + live。

## 0. 当前实测水位(用户实测)
| 接口 | 最初 | 现在 |
|---|---|---|
| **batch-expand**(进报价渲染) | 17-22s ×多次重发 | **20s ×1**(重发已消) |
| **draft**(首存) | 22s ×3 连发 | **9-11s ×1** |

## 1. 已落地优化栈(全部合 master + live + 等价验证)
| # | 优化 | 机理 | 实测效果 | 等价护栏 |
|---|---|---|---|---|
| 1 | **P1 JDBC 批处理** | `statement-batch-size=100`+order_inserts → 落库 770 往返合并成批 | **S1 10s→0.65s** | Golden md5 + BatchStage1 持久化等价 |
| 2 | **P0 砍 payload churn** | `stableDraftDedupKey` 去重只比用户输入(剔除回填 id + 派生快照) | **三连发→1** | draftPayloadDedup 7/7 单测 + E2E |
| 3 | **JEXL `.cache(512)`** | 4 个 JexlEngine 缓存已解析 AST,免逐行重 parse | **S3 公式求值 −25%** | Golden md5 不变 |
| 4 | **P3 lazy-Excel** | 首存只算卡片值,Excel 值(7.5s)懒算(开 Excel 视图/导出/提交前 ensure) | **S3 Excel 段 7.5s→0**(已证 `cexcel=0`) | LazyExcelValuesEquivTest 170 行逐位等价 + Golden |
| 5 | **batch-expand 去 allUniquePartNos** | 同料号多卡也合桶(对 170 行/77 distinct 重复单有效) | 用户单是 77/77 全 distinct,**本单无收益**(已 distinct,本就合桶) | BatchExpandBucketEquivTest 逐位等价 |

## 2. draft 11s 深度拆解
`PUT /draft` 串行 4 段(`[draft-profile]` 实测,order 4ff10e8c/a341844a,77 行):

| 段 | 耗时 | 说明 |
|---|---|---|
| **S1 saveDraft 落库** | **0.65s** | ✅ P1 已修(原 ~10s) |
| **S2 snapshotRows** | ~1s | 报价 driver 合桶,已快 |
| **S3 cardValues** | **6.8-8.1s** | ⚠️ 当前最大段 |
| S4 getById | ~0.45s | 已批化 |

### S3 内部(离线 profiling,用户单 a341844a 77 行)
| S3 子段 | 离线实测 | 生产是否付费 |
|---|---|---|
| setup(precomputeCostingDriverUnion 1.77s + prefetch 0.31s) | **2.1s** | 是 |
| 报价卡 buildCardValues | 0.19s | 是 |
| 核价卡 buildCostingCardValues | 0.037s | 是 |
| 报价 Excel buildExcelValues | 1.68s | **否(懒算 + 前端权威)** |
| 核价 Excel buildExcelValues | 1.61s | **否(懒算,已证 cexcel=0)** |

**离线 cards+setup = ~2.3s,但生产 S3 = 6.8-8.1s → ~5.8s 缺口。**

### 🔴 S3 缺口已实锤(`[s3-detail]` order 9881d2e2,77 行,S3=7724ms)
```
setup(union/prefetch/compData,首行一次) = 2030ms
逐行卡片值(snapshotLineValuesWithUnion ×77) = 4095ms
余(ensureStructure + loadQuotationLines + 循环内 findById) ≈ 1599ms
```
**逐行卡片值 4.1s vs 离线纯算值 0.23s → ~3.9s 是「逐行落库+开销」,不是算值。** 根因:S3 循环从
**非事务**编排器逐行调 `snapshotLineValuesWithUnion`(`@Transactional`),77 次 = **77 个独立事务**,每个:
begin + `findById`×2(跨 tx 不复用 L1,冷查)+ 卡片值 JSONB UPDATE + commit。77 次 begin/commit/冷 findById
= ~3.9s(与历史 F7「非事务编排 170 独立 txn」同源)。
**修法:卡片值集合化落库** —— S3 循环包一个事务(L1 复用 + 一次 flush + P1 的 JDBC 批 UPDATE 合并 77 行),
或两段式 `UPDATE…FROM(VALUES)`。预计 4.1s → ~1s,叠加 setup 优化后 S3 ≈ 3s。

## 3. batch-expand 20s 深度拆解
**关键发现:用户实测单 a341844a = 77 行 / 77 distinct 料号(全不重复)。** 故 #5(去 allUniquePartNos)
对本单无效(本就全 distinct、eligible 组件本就合桶)。`[be-profile]`:tasks=616 realExpand=616 phases≈20s。

616 task = 77 行 × ~8 组件。两类组件:
- **eligible(非 lineItemId 视图)**:合桶 → 每组件一次 `expandMulti(77 distinct partNo)`(`[be-bucket] merged=true partNos=77`,日志已见 16 条「省 76 次」)。`expandMulti` 是**一次** SQL 视图查(`DataLoader.loadByPath` 全 partNo)+ 内存逐行 `evaluatePath`(每行每 BASIC_DATA 字段)。
- **lineItemId 视图组件(Bug-B 隔离)**:`viewUsesLineItemId=true` → **不能合桶**,逐 task(77 次)冷 expand。

### 🔴🔴 batch-expand 18.9s 已实锤 —— Phase 1 在做「整批白干的逐 task 冷展开」
`[be-bucket]`(order 9881d2e2):**8 个组件全 merged=true,合计仅 245ms**(`$cp/$ll/$ys/$jg/$wgj/$qt/$dd/$zz_view`,
各 77 partNo「省 76 次」20-53ms)。**但 `[be-profile]` phases=18922ms!** 8 桶只占 245ms → 另外 **18.6s 不在 Phase 2**。

定位:`ComponentResource.doBatchExpandPhases` 的 **Phase 1**(`:271`)对每个 hasContext task 调
`expandWithSnapshot(...)`。而 `expandWithSnapshot`(`ComponentDriverService:202`)= 先读快照,**读不到就
`return expand(...)` 做一次真·逐 task 冷展开**(`:230`)。导入时 616 task 全无快照(snapHit=0)→ **Phase 1
对 616 个 task 各做一次冷远程展开 ≈ 18.6s,拿到结果后又因 `driverPath!="snapshot"` 丢弃、塞进 phase2**
(`:276→282`)→ Phase 2 再合桶展开一遍(245ms,且 expandMulti 不吃 expandCache、与 Phase 1 无关)。

**即 Phase 1 的 616 次冷展开是 100% 白干**(结果被丢、Phase 2 重算)。这是 batch-expand 18.9s 的唯一大头。

**修法(简单且大):Phase 1 只「窥探快照」不展开** —— 把 `:272` 的 `expandWithSnapshot` 换成只读快照、
miss 返 null 的 `tryReadSnapshot(componentId, lineItemId)`(把 `expandWithSnapshot` 的快照读段抽出、不 fallthrough
到 expand);miss → 直接 `phase2.add(i)`,**不做真展开**。Phase 2 合桶照旧。**预计 batch-expand 18.9s → ~0.3s**。
等价:Phase 2 产出不变(BatchExpandBucketEquivTest 守);Phase 1 仅停止「算了又丢」。

### 旁证:服务端 S2 只要 ~1s 做等价展开
`snapshotQuotation` 的 S2 用 `precomputeQuoteDriverBuckets`(对 distinct partNo 一次 expandMulti)**1s** 出
snapshot_rows;客户端 batch-expand 做"同一份展开"却 20s。差异 = 客户端含 lineItemId 视图逐 task + 冷态。
**根治方向**:客户端渲染改"读服务端快照"而非自行冷展开(首存后快照已落)——即把展开收敛到服务端一次。

## 4. 结论与下一步(两个根因已实锤,各对应一个简单大修)
| 项 | 现状 | 根因(已实锤) | 修法 | 预计 |
|---|---|---|---|---|
| **batch-expand** | 18.9s | **Phase 1 对 616 task 各做一次冷展开后全丢**,Phase 2 再合桶算一遍 | Phase 1 改「只窥探快照、miss 不展开直接进 Phase 2」 | **18.9s → ~0.3s** |
| **draft S3** | 7.7s | 逐行卡片值 4.1s = **77 个独立事务**的 begin/冷 findById/JSONB UPDATE/commit | 卡片值**集合化落库**(一个事务 + 批 UPDATE) | S3 7.7s → ~3s |

两者都是**结构性白干/逐行事务**,非算力问题;均有等价护栏(BatchExpandBucketEquivTest / GoldenCardValuesEquivTest +
BatchStage1 持久化等价)。落地后预计:**batch-expand ~0.3s + draft ~4s**,首存体验从 ~30s(20+11)→ ~5s 量级。

**优先级:先打 batch-expand Phase 1(最大、最简、风险最低),再打 draft S3 集合化落库。**
