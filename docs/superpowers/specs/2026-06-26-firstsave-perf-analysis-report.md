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

### 🔴 S3 ~5.8s 缺口定位(核心待确认项)
离线 profiling 直接调 `buildCardValues`/`buildCostingCardValues`(返字符串、**不落库**);生产 S3 走
`snapshotLineValuesWithUnion`,除算值外还**逐行 persist 卡片值 JSONB**(报价 334KB + 核价 193KB = 527KB/77 行)
+ 逐行 `findById`×2 + 事务脏检查。缺口最可能在**这批 JSONB 落库 + 逐行开销**(离线测不覆盖)。
已部署 `[s3-detail]` 埋点(setup vs 逐行卡片值 ms);下次复现即可实锤:
- 若「逐行卡片值」≈6s ≫ 离线 0.23s → 缺口 = persist + 逐行开销 → 优化方向:卡片值**批量落库**(类似 P1 的批 UPDATE,或两段式 UPDATE…FROM VALUES),把 77 行 JSONB 写合并。
- 若「setup」≈6s → 缺口 = precomputeCostingDriverUnion 生产态更慢(冷/争用)→ 查 union。

## 3. batch-expand 20s 深度拆解
**关键发现:用户实测单 a341844a = 77 行 / 77 distinct 料号(全不重复)。** 故 #5(去 allUniquePartNos)
对本单无效(本就全 distinct、eligible 组件本就合桶)。`[be-profile]`:tasks=616 realExpand=616 phases≈20s。

616 task = 77 行 × ~8 组件。两类组件:
- **eligible(非 lineItemId 视图)**:合桶 → 每组件一次 `expandMulti(77 distinct partNo)`(`[be-bucket] merged=true partNos=77`,日志已见 16 条「省 76 次」)。`expandMulti` 是**一次** SQL 视图查(`DataLoader.loadByPath` 全 partNo)+ 内存逐行 `evaluatePath`(每行每 BASIC_DATA 字段)。
- **lineItemId 视图组件(Bug-B 隔离)**:`viewUsesLineItemId=true` → **不能合桶**,逐 task(77 次)冷 expand。

### 🔴 batch-expand 20s 归因(待 `[be-bucket]` 实锤)
20s 落在二者之一(或都有):
- **(a) 合桶组件的 expandMulti(77) 本身慢**:一次大 IN 查 + 77×N 行逐行 evaluatePath(CPU/或逐字段 BNF 远程)。
- **(b) lineItemId 视图组件逐 task(77 次冷 expand)**:每 task 一次远程视图查,RTT 主导。
`[be-bucket]` 已部署(每桶 comp/merged/tasks/ms/lineItemIdView)→ 下次复现即知哪类、哪些组件吃掉 20s。
- 若 (b) 主导 → 需 **lineItemId 维度合桶**(`(lineItemId,partNo) IN` 多键一次查;P3 Phase 2-3),或服务端权威展开(S2 已 1s 算出 snapshot_rows,客户端可改读快照不自展)。
- 若 (a) 主导 → 优化 expandMulti 的逐行 evaluatePath(批量 BNF / 减字段)。

### 旁证:服务端 S2 只要 ~1s 做等价展开
`snapshotQuotation` 的 S2 用 `precomputeQuoteDriverBuckets`(对 distinct partNo 一次 expandMulti)**1s** 出
snapshot_rows;客户端 batch-expand 做"同一份展开"却 20s。差异 = 客户端含 lineItemId 视图逐 task + 冷态。
**根治方向**:客户端渲染改"读服务端快照"而非自行冷展开(首存后快照已落)——即把展开收敛到服务端一次。

## 4. 结论与下一步
1. **已把首存从 22s×3 ≈ 60s+ 压到 ~9-11s×1**;Excel 7.5s 已移出关键路径(懒算,已证生效)。
2. **draft 残留主项 = S3 的 ~5.8s 缺口**,强疑卡片值 JSONB 逐行落库 → 方向 = **批量落库**。待 `[s3-detail]` 实锤。
3. **batch-expand 20s = 客户端冷展开**(eligible 合桶 expandMulti + lineItemId 逐 task)。待 `[be-bucket]` 实锤是 (a) 还是 (b);根治为"服务端权威展开/客户端读快照"或"lineItemId 维度合桶"。
4. **请复现一次**(导入 77 行 → 保存):读 `[s3-detail]` + `[be-bucket]` 即可把上面两个 🔴 缺口换成精确数字,据此各打一个针对性优化。
