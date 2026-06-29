# V6 基础数据导入「逐 sheet 落库」性能分析报告 — 2026-06-27

> 基于在 `VersionedV6Writer`(写入器分段计时 ThreadLocal profiler)+ `QuoteImportService`/`PricingImportService`
> 编排器层(parse vs handle 拆分)加的 `[v6import]` 埋点,实测一次 QUOTE 导入(executor-thread-2,
> backend.log 2026-06-27 14:07:47~14:08:13)。埋点纯计数/计时,不改写入行为。

## 0. 实测水位
| 指标 | 值 |
|---|---|
| **QUOTE 导入总耗时** | **29918ms(~30s)** |
| 有数据 sheet 数 | 8 / 18(其余 10 个本单 0 行) |
| **writer 总 DB 往返** | **1437 次** |
| **writer 累计耗时** | **26798ms(占总 90%)** |
| **平均每次往返** | **18.6ms**(远程 PG 网络延迟,非 SQL 计算) |
| parse(POI)最大单 sheet | 37ms(可忽略) |

## 1. 按 sheet 实测(handle 降序)
| sheet | rows | parse | handle | groups | dbCalls | 每组往返 |
|---|---|---|---|---|---|---|
| 成品其他费用 | 99 | 3ms | **8974ms** | 99 | 467 | 4.7 |
| 组装加工费 | 111 | 3ms | **5978ms** | 84 | 362 | 4.3 |
| 物料与元素BOM | 160 | 11ms | **5604ms** | 30 | 202 | 6.7（主从） |
| 物料BOM+组成件BOM(合并) | 206/2 | 37ms | **3865ms** | 79 | 241 | 3.0 |
| 来料固定加工费 | 30 | 3ms | 2302ms | 30 | 126 | 4.2 |
| 客户料号与宏丰料号关系 | 77 | 7ms | **1507ms** | 0 | 0 | ⚠️ 非 writer 路径 |
| 元素单价 | 9 | 26ms | 612ms | 9 | 29 | 3.2 |
| 组成件其他费用 | 2 | 0ms | 161ms | 2 | 10 | 5.0 |

## 2. 按 SQL 类型汇总(全部 writer sheet)
| 操作 | 代码位置 | SQL | 次数 | 累计 ms | 占比 |
|---|---|---|---|---|---|
| advisoryLock | `VersionedV6Writer#advisoryLock` | `SELECT pg_advisory_xact_lock(hashtext)` | 333 | 5783 | 21% |
| loadCurrentGroup | `#loadCurrentGroup` | `SELECT <content> WHERE <gk> AND is_current` | 333 | 6274 | 23% |
| nextVersionOf/currentVersionOf | `#nextVersionOf` `#currentVersionOf` | `SELECT MAX(ver::int)` / `SELECT ver LIMIT 1` | 333 | 5784 | 22% |
| flip(+deleteNonCurrent/deleteCurrent) | `#flip` | `UPDATE/DELETE is_current` | 219 | 4340 | 16% |
| insertRowsBatched(+insertRowGeneric) | `#insertRowsBatched` | `INSERT`（行已批合，组间不合） | 219 | 4617 | 17% |
| **合计** | | | **1437** | **26798** | **90%** |

## 3. 根因(已实锤)
**延迟瓶颈,非吞吐瓶颈。** 每个 group 在 `writeVersionedGroup`/`writeVersionedMasterDetail` 内串行发
**3 个 SELECT(lock+load+ver)+ 升版时 2 个写(flip+ins)**;各 handler `for (group) writer.write...()`
把它放大到 groups×(3~7) 次。远程 PG 单次往返 ~18.6ms,1437 次串行 = ~27s。

- **决策类 3 个 per-group SELECT(lock+load+ver)= 999 次往返 / 17.8s(占 60%)** —— 全部可合并为每 sheet 常数次批查。
- `advisoryLock` 每组独立往返(333 次 / 5.8s)纯串行化开销;可改每 sheet 一把锁。
- 数据量无关:99 行的「成品其他费用」9s,因 99 组 × 4.7 往返串行,非行多。
- parse(POI)可忽略(≤37ms)。

**非 writer 旁路:** 「客户料号与宏丰料号关系」handle=1507ms 但 writer dbCalls=0 → 其 DB 写在
`Q02CustomerMapHandler` 自己的逐行 upsert(77 行 ×~20ms),是独立的第二条 N+1,量级小但同病。

## 4. 优化方向(按收益排序,待决策)
| # | 优化 | 机理 | 预计 | 风险 |
|---|---|---|---|---|
| **A** | **写入器集合化**：handler 把整 sheet 所有 group 一次交给 writer;writer 用 `IN`/`VALUES` 批量 `loadCurrentGroup`+批量取版本号→内存比对→批量 `flip`→跨组合并 `INSERT` | groups×5 往返 → 每 sheet ~5 次常数 | **27s → <2s** | 高（改 golden 关键版本化写入,需等价护栏） |
| **B** | **advisory 锁降频**：每 sheet/每客户取 1 把锁,不再每组一把 | 333 次 → ~8 次 | −5.8s | 低 |
| **C** | **load+ver 合并**：`loadCurrentGroup` 同查询带回 version,省一个 SELECT/组 | 333 次往返消除 | −5.8s | 低 |
| **D** | 「客户料号关系」等非 writer handler 逐行 upsert → 批量 upsert | 77 行 1 次 | −1.4s | 低 |

**建议:** 先做 B+C（低风险、约 −11s、不动版本化语义）验证收益;A（集合化重构,约 −25s）单独立项 +
golden 等价护栏(与首存 S3 `snapshotNewLinesCardValues` 集合化同源思路,参考
`docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md`)。

## 5. 埋点位置（投产前按需降级 INFO→DEBUG，同首存 `[*-profile]` 惯例）
- `VersionedV6Writer`:`Profile`(ThreadLocal)+ 各 DB 私有方法 nanoTime 包裹 + 两入口 `groups++`
- `QuoteImportService#processImport`:每 sheet `[v6import] QUOTE ...` + `QUOTE TOTAL`
- `PricingImportService#importExcel`:每 sheet `[v6import] PRICING ...` + `PRICING TOTAL`
