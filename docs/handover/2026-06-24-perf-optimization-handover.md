# 交接文档 — 导入 & 进入报价单 性能优化(2026-06-24,接 06-23)

> 用途:新会话据此**接续**性能优化。上一份交接 `docs/handover/2026-06-23-perf-optimization-handover.md`(P1/P2 实现起点)已**全部落地并合并 master**。本份记录:已完成项、**一处关键再定向(真痛点)**、剩余唯一大项(首存 draft 30s)、硬约束与方法论。

---

## 一、一句话现状

- 设计方案 §7 的 **P1(A/Q19/C3)+ P2(D/Q05/C4)全部完成并合并 master**;另**额外发现并修复了用户真痛点的一半**(batch-expand 合桶 + DataLoader 行序根治)。
- **剩余唯一大项**:用户 F12 实测的 `draft`(saveDraft 首存)**30s 超时取消** —— 即**报价侧 snapshot_rows 全量 expand(P3-C1)**,本会话**未碰**(最高风险)。
- master 当前 `d306bbd`(本会话所有 perf 工作已并入;dev server 已热重载验证健康 401)。

---

## 二、🎯 下一步要做的事(剩余核心)

**攻 `draft`(saveDraft 首存)30s 超时**,让首存在客户端超时内完成 → 快照及时落库 → 渲染脱钩生效(后续加载 batch-expand=0)。

用户 2026-06-23 F12 实测(导入后首次加载报价单)三类慢:
- `batch-expand` 6-21s × 多次 → **本会话已修**(合桶启用,见 §四)。
- `batch-evaluate` 7s → 未单独处理(公式批量求值;若仍慢可查 FormulaEvaluateResource/batchEvaluate)。
- `draft`(saveDraft)**30s 取消** → **未修,这是下一步主目标**。

`draft` 30s 构成(`CardSnapshotService.snapshotLineValues` → 两侧):
- **报价侧** `buildCardValues` 依赖 `ConfigureSnapshotService` 写的 `snapshot_rows`;首存对**新行**全量 expand 写 snapshot_rows(**P3-C1,未做,~13s/73行**)。
- **核价侧** `buildCostingCardValues` —— 本会话 **P2-C4 已 ÷N 优化**(union)。

> ⚠️ **方向决策(必须先和用户确认)**:P3-C1(报价侧合桶)是设计方案里**风险最高**项(Bug B 竞态保护雷区:`ConfigureSnapshotService` 查不到返 EMPTY 不 fallback,是修 configure 串号的保护,合桶易绕过)。用户 2026-06-22 曾**否掉异步首存**,但 06-23 实测痛点仍在 → **异步首存可重新提上桌**(和用户确认)。三条路线:① 报价侧 C1 批量(高风险,走 architect);② 重新考虑异步首存(用户曾否,需再确认);③ 提高客户端/网关超时让首存能落(治标,但首存落一次后续加载即快)。

---

## 三、✅ 本会话已完成(全部已合并 master,勿重复)

| 项 | 内容 | 收益(实测) | commit/merge |
|---|---|---|---|
| **P1-A** | material_master 9 handler 逐行 upsert→批量(name/type 首值 / Q18 末值 / Q02 单列三类) | 导入往返 353→232 | master `5b43e32` |
| **P1-Q19** | 年降系数逐行 INSERT→批量(AnnualDiscountRepository) | 273→233 | `5b43e32` |
| **P1-C3** | gvar `GlobalVariableService.resolveValues` 批量(KvTable IN)+ expand 跨行批量 | **当前 no-op**(线上 0 gvar 组件),收益潜伏 | `5b43e32` |
| **P2-D** | MaterialNoResolver 9 字头 MAX 锁+读一次/批(BatchState.dbMax 缓存) | 每生成名省 2 往返 | `7f95f11` |
| **P2-Q05** | 元素回收折扣逐行 UPDATE→批量(countCurrentMatches + UPDATE…FROM VALUES,保 per-row 报告) | N→1 | `7f95f11` |
| **P2-C4** | 核价侧跨行 partSet union(÷N 首存核价 driver);**再定向**到 refresh+saveDraft | 往返 OLD136→NEW100/12行 | `210d4e9` |
| **batch-expand 合桶** | 启用已建未用的 bucket-merge flag(默认 true)+ **DataLoader 行序根治** | 首次加载 N×M→每桶1次 | `d306bbd` |

**等价证据**:每项都有 A/B 等价单测(逐行 vs 批量逐位)+ 真实数据集成测试 + 广回归全绿。测试类:`MaterialMasterBatchUpsertEquivTest`/`MaterialMasterWeightBatchUpsertEquivTest`/`AnnualDiscountBatchUpsertEquivTest`/`ElementRecoveryBatchUpdateEquivTest`/`MaterialNoResolverBatchGenTest`/`GlobalVariableResolveValuesBatchEquivTest`/`ComponentDriverGvarBatchEquivTest`/`CostingPartSetUnionEquivTest`(A/B+连跑两次+往返)/`BatchExpandBucketEquivTest`/`MaterialMasterBatchImportIntegrationTest`。详见 `docs/RECORD.md` 末尾 2026-06-23 各条。

---

## 四、🔑 本会话三处关键发现(新会话必须知道,否则会重蹈)

1. **C4 真热点再定向**:`ImportExecutionService.executeImport` 创建的行 **`productPartNoSnapshot=null`** → BOM 闭包空 → 核价本就轻。架构师两轮误把它当首存热点。`productPartNoSnapshot` 仅 `QuotationService` 设(saveDraft `:354`/configureProduct `:416`/copy `:1323`)。**真热点 = saveDraft 首存 + refreshCostingCardValues**(都整单 loop)。→ C4 落在这两处,`executeImport` 不动。**下一步攻 draft 也是这条**(saveDraft)。

2. **视图无 ORDER BY 的行序非确定性**(咬了 3 次:C4 buildSpineBaseRows、batch-expand 合桶)→ **已根治**:`DataLoader.stableSort`(取回行按"列名升序拼 col=value"稳定键排序,在 executeAllRows/execute 两返回点)→ expand/expandMulti/expandForPartSet **全确定且同 partNo 同序**。**新加任何"合桶/union/跨行复用 expand"的优化,等价性天然成立**(行序已统一);但 A/B 仍要做。**接受了核价/渲染行序一次性规整(值/行数不变)**——用户已拍板。

3. **batch-expand 合桶 flag 默认改 true**(`cpq.batch-expand-bucket`,kill switch `-Dcpq.batch-expand-bucket=false`)。`ComponentResource.batchExpand` Phase1/2:有 snapshot 命中直返,未命中按 driver 桶合并 expandMulti。

**既有红测试(非本会话引入,勿当回归)**:`DataLoaderTest`(4 err,sqlViewExecutor null=单测未注入依赖)+ `FormulaEvaluateResourceTest`(3 fail,parse 期望)+ `VersionedV6MasterDetailTest`(2 err,childVersionColumn=null CHILD_UQ)。均经 `git stash` 验证为分支既有红。

---

## 五、🚫 硬约束(违反即作废,先读对应记忆)

1. **禁止并行化** expand / 公式 / 快照求值层(2026-06-22 已实证竞态回滚)。见 `cpq-expand-layer-not-threadsafe`。
2. **不改变任何业务落库/计算结果**(行数/列值/版本/is_current/公式/料号生成逐位一致;**行序例外**——已统一规整,值不变)。
3. **不改 `pg_advisory_xact_lock` 粒度**;首存异步**需先与用户确认**(曾否,但痛点仍在可重议)。
4. 触碰 expand/snapshot 保留**防御性深拷贝** + AP-37/45/50/51/52/53 既有保护。

**开工前必读记忆**:`cpq-import-firstsave-perf-bottlenecks`、`cpq-expand-layer-not-threadsafe`、`cpq-savedraft-incremental-snapshot`(首存增量机制+禁异步现状)、`cpq-sqlview-cache-key-needs-component-dim`(同名视图按 componentId 消歧)、`cpq-deliver-agents-overreport`(派 agent 必主线亲验)、`cpq-worktree-maven-test-tree`/`cpq-concurrent-sessions-and-worktree`/`cpq-auto-finish-merge-e2e-cleanup`。

---

## 六、⚙️ 环境与工作流

- **后端 dev server**:`http://localhost:8081`(Quarkus dev,改 .java 自动热重载;健康 `curl .../api/cpq/components` 期望 **401**=正常;首次请求触发重编译可能先返 000,重试)。
- **前端 dev server**:`http://localhost:5174`。
- **远程 DB**:`PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`。**`pg_stat_statements` 未启用且不可重启** → 往返度量改用 **Hibernate `Statistics.getPrepareStatementCount()`**(本会话所有往返实测都用它;A/B 同进程切 flag 或 git stash 新旧对比)。
- **dev server / DB / node_modules 共享**(勿在 worktree 另起 server/装依赖)。
- **工作流(CLAUDE.md 强制)**:新功能用隔离 worktree 分支;主线亲实现亲验(派 agent 会虚报);完成走安全合并(查 master 是否被并发推进 + 主树 WIP 重叠 → `--no-ff` merge → 热重载健康)。
- **后端测试**:`cd <worktree>/cpq-backend && ./mvnw -o test -Dtest=Xxx`(mvnw 在 cpq-backend/,**必须在 worktree 里跑**)。`-q` 会吞 surefire 摘要,看结果用 `grep 'Tests run'` 或读 `target/surefire-reports/*.txt`。
- **并发纪律**:多会话同分支可能交错;只 `git add` 本次明确文件,**严禁 `git add -A`**;主树有大量其它会话未提交 WIP,合并前必查与本次改动文件零重叠。

---

## 七、🔒 等价性验证铁律(每项必带)

1. **A/B 等价单测**:逐行/旧路径 vs 批量/新路径,落库或产出**逐位相同**(行序已由 DataLoader 统一,值逐位比)。
2. **真实数据集成测试**:挑库里真实报价单/料号只读对拍(`CostingPartSetUnionEquivTest`/`BatchExpandBucketEquivTest` 是范式;只读不写、不污染)。
3. **连跑两次**(防可变共享面非确定性)。
4. **往返度量**:Hibernate `Statistics.getPrepareStatementCount()` 新旧对比(同进程切 flag 或 git stash)。
5. **既有套件全绿** + 协议级改动跑 E2E 双 spec(`quotation-flow` + `composite-product-flow`,需 dev server 跑合并后代码)。
6. 任一不过 = 回滚不合入。**派 agent 后主线亲跑测试/查 DB(agent 会虚报)。**

---

## 八、worktree 状态 / 清理

- 本会话 worktree:`.claude/worktrees/p2-costing-partset`(分支 `worktree-p2-costing-partset`)——**已全部并入 master,可清理**(`git worktree remove` + 删分支)。上一会话 `perf-batch-insert-v6writer` 也已合并、可清理。
- 新会话做 draft/C1 时按 CLAUDE.md **另起新隔离 worktree**(off 最新 master `d306bbd`+,含全部 perf 工作)。

---

## 九、关键文件索引(下一步会碰)

- `cpq-backend/.../quotation/resource/QuotationResource.java`:saveDraft 首存循环(`:134` 附近,新行 `snapshotLineValuesWithUnion`;本会话已加核价 union 懒触发)。
- `cpq-backend/.../quotation/service/CardSnapshotService.java`:`snapshotQuoteSideOnly`(报价侧)/`snapshotCostingSideOnly`(核价侧,已 union)/`precomputeCostingDriverUnion`/`buildCardValues`(报价侧读 snapshot_rows)。
- `cpq-backend/.../configure/service/ConfigureSnapshotService.java`:**报价侧 snapshot_rows 全量 expand 在此(P3-C1 主战场)**;已有 `skipRowsWithSnapshot` 增量机制(见 `cpq-savedraft-incremental-snapshot`)。
- `cpq-backend/.../component/service/ComponentDriverService.java`:`expand`/`expandMulti`/`expandForPartSet`/`eligibleForBomUnion`(C4 闸门)/`resolveGvarsBatched`(C3)。
- `cpq-backend/.../formula/dataloader/DataLoader.java`:`stableSort`(行序根治)。
- 设计方案:`docs/superpowers/specs/2026-06-23-import-and-firstsave-perf-optimization-design.md`(§3 P3-C1)+ `2026-06-23-P2-C4-*.md`(C4 两份)。
