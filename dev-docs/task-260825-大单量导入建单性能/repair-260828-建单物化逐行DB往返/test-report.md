# test-report · repair-260828 建单物化逐行 DB 往返

> 执行日期：2026-08-29　执行者：测试子代理（用例编写 + 首轮执行）→ **主线亲验复跑**
> 分支 `fix/repair-260828-materialize-row-roundtrip`　实现 `a8a4ec85`　用例 `3be0a9d0`

---

## 0. 一句话结论

**1845 行整单物化 105906ms → 5900ms，降 94.4%**，14 条 AC 中 **12 条达成、2 条部分达成**，值逐位不变，`@DynamicUpdate` 并发防线实测未受损。

---

## 1. 🚨 首个必须说清的方法学问题：冷启动 vs 热态

主线第一轮实测曾得出「AC-1 / AC-3 超标」的结论，**该结论已作废**——它拿的是**冷启动**数字去比**热态**基线。

同规格两单（均 1845 行、均含核价模板）实测：

| 单 | 状态 | ④ ensureExcelValues | 每批 |
|---|---|---|---|
| `7cb46cfc` | 冷（JVM 刚起、首次执行该路径） | **11798ms** | 1653~2313ms |
| `f3d98e06` | 热 | **2454ms** | 123~431ms |

**差 4.8 倍。** 而基线两次（18:59 的 100481ms、22:19 的 105906ms）彼此仅差 5%，说明**基线本身就是热态数字**——因此唯一公平的对照是热态对热态。

🔒 **本报告的所有性能数字均取热态**，并全部来自走完整链路（真实 xlsx 导入 → 新 `importRecordId` → 建单 → 物化）的那一次运行，而非端点单独触发。

---

## 2. 性能实测（AC-1 / AC-2 / AC-3）

**方法**：8095 = worktree 修复后代码，**验明正身连 `cpq_db_0724`**（`totalElements=141` 对齐 `SELECT count(*)`，测试库 `cpq_db` 为 1123，可区分）。同一份 `1800笔产品订单.xlsx`、同客户（正泰 `1f5818d8`）、同模板（含核价 `cb492889`）。

**埋点原文**（`quotation=6d457323-dfdd-4285-90bb-ec81aad8c3b4`）：

```
[create-quotation-timing] ①snapshotQuotation=476ms ②ensureStructure=240ms
                          ③ensureCardValues=3151ms ④ensureExcelValues=2033ms 总计=5900ms
[ensure-cardvalues] 补算 1845 行（分 7 批，chunk=300，总耗时=2237ms，失败 0 批/0 行）
[lazy-excel]        补算 1845 行（分 7 批，chunk=300，总耗时=1951ms，失败 0 批/0 行）
端到端墙钟 = 5s（含轮询间隔）
```

| AC | 判据 | 基线 | 实测 | 结果 |
|---|---|---|---|---|
| **AC-1** | 总计 ≤ 20000ms | 105906ms | **5900ms** | ✅ **达成**（−94.4%） |
| **AC-2** | ③ 每批 ≤ 2000ms | 10435~12004ms | **137 / 278 / 307 / 346 / 348 / 399 / 403 ms** | ✅ **达成**（7/7 批全达标） |
| **AC-3** | ④ ≤ 8000ms | 33637ms | **1951ms**（埋点内部值；四步口径 2033ms） | ✅ **达成**（−94.2%） |

**分段降幅**：③ 71362 → 3151ms（**−95.6%**）；④ 33800 → 2033ms（**−94.0%**）；①② 基本不变（本就只占 0.7%）。

**落库核对（非空正向结果，`CLAUDE.md` §4.5 步骤 4d）**：
```
total=1845  pend_qc=0  pend_qe=0  pend_cc=0
```

---

## 3. 自动化用例（主线在 worktree 内清产物后亲跑）

```
mvnw -o clean test → Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

| AC | 用例 | 主线亲跑证据 | 结果 |
|---|---|---|---|
| **AC-4** | `CardSnapshotComponentDataPreloadNPlusOneTest` | `N=300 fallbackExec=0 \| N=1845 fallbackExec=0` | ✅ |
| **AC-5** | `CardSnapshotBatchWriteRoundTripTest` | `rows 之和=1845, updates 之和=7`（card 与 excel 各一条） | ✅ |
| **AC-6** | `EnsureCardValuesSaveDraftDynamicUpdateGuardTest` | `annualVolume: 初始=1 saveDraft后=42 warm提交后=42` ×3 | ✅ |
| **AC-8/9** | `Repair260828ValueEquivalenceSnapshotTest` | 见 §4 背靠背 | ✅ |
| **AC-12** | `CardSnapshotEmptyAndSingleLineBoundaryTest` | 0 行 / 1 行不抛异常，`ready==total` 恒成立 | ✅ |

### 还原实验（`test.md` §0 强制要求）

🔒 判据：把修复改回原样，**重跑必须变红**；仍绿 = 空用例。

| 用例 | 还原到 `a8a4ec85^` | 恢复 `a8a4ec85` | 有效性 |
|---|---|---|---|
| **T-1（AC-4）** | `N=300 fallbackExec=300` / `N=1845 fallbackExec=1845` → **FAIL** | `fallbackExec=0` → PASS | ✅ **强**。`1845` 精确等于行数，是根因 A「1845 条必定查空的 SQL」的直接数值证据 |
| **T-2（AC-5）** | 「捕获不到 `[perf]` 日志」→ FAIL | `updates=7` → PASS | ⚠️ **弱**（见 §6） |
| **T-4（AC-6）** | 证伪实验：期望值故意改成旧值 `1` → **3/3 正确变红** | 还原后 3/3 PASS | ✅ 断言机制自证有效 |

---

## 4. 值等价背靠背（AC-8 / AC-9）—— 主线独立复验

不采信子代理的比对结果，主线用带 `trap` 保证恢复的脚本重做了一次：

```
git checkout a8a4ec85^ -- CardSnapshotService.java   → 采 BEFORE 快照（10 行）
git checkout a8a4ec85  -- CardSnapshotService.java   → RESTORED diff=0
diff BEFORE AFTER  →  ✅ IDENTICAL
```

**非空验证**：`distinct qcv_md5 = 10`（10 行 md5 互不相同）——排除了「输入全一样导致巧合相等」这类空验证。

⚠️ **夹具局限（如实标注）**：该夹具 `ccv_md5` / `cev_md5` 全为 NULL、`qev_md5` 10 行相同、`subtotal` 全 0，即**核价侧与 Excel 侧在本用例中缺乏区分度**，等价性只在 `quote_card_values` 这一维度被强证明。核价/Excel 侧的等价性由 §2 的整单实测（`pend_cc=0`、`pend_qe=0` 且渲染无失败哨兵）间接支撑，但**未做 md5 级背靠背**。

---

## 5. 回归（AC-7 / AC-10）

**主线亲跑**（不采信子代理运行结果）：`Tests run: 4, Failures: 0, Errors: 0`

| AC | 用例 | 主线亲跑 | 结果 |
|---|---|---|---|
| **AC-7** | `EnsureCardValuesPartialBatchRecoveryTest`（1）+ `CreateQuotationMaterializeWarningsPropagationTest`（2） | ✅ 3/3 | ✅ |
| **AC-10** | `EnsureCardValuesEditConcurrencyTest`（1） | ✅ 1/1 | ✅（后端并发路径；UI 序列见 §6-2） |

批失败语义实测（人为 `lock_timeout` 制造失败批）——**这是 C-丙 换掉落库机制后最关键的一条回归**：

```
[ensure-cardvalues] 补算 9 行（分 3 批，chunk=3，总耗时=10422ms，失败 1 批/3 行）
[gap2] cardValuesReady=false
       warnings=[卡片值物化部分未完成：1 批（共 3 行）未完成，将在下次打开/轮询时自动补算,
                 Excel 值物化部分未完成：1 批（共 9 行）未完成，将在下次打开/导出/提交时自动补算,
                 部分行卡片值未就绪或渲染失败，详情/核价管理可能显式提示]
       missingAfterCall=3
```

🔑 **`missingAfterCall=3`** —— 失败批的行**仍为 NULL**，证明原生批量写没有把「失败批也算半拉子落库」，`IS NULL` 自愈谓词的前提完好。

历史运行（子代理，同一语义、不同夹具）：
```
[lazy-excel] 补算 9 行（分 1 批，chunk=300，总耗时=10058ms，失败 1 批/9 行）
[create-quotation] ④ensureExcelValues 部分批次失败：1 批/9 行，其余批次已正常提交，未完成行靠 IS NULL 谓词自愈
warnings=[卡片值物化部分未完成：1 批（共 3 行）未完成，将在下次打开/轮询时自动补算,
          Excel 值物化部分未完成：1 批（共 9 行）未完成，将在下次打开/导出/提交时自动补算]
```
文案与 `api.md` 要求**逐字一致**。二次触发 `computed=3 failedBatches=0`，`IS NULL` 自愈生效、不重算已完成行。

🔎 **真实世界旁证**：本次用于实测的 `7cb46cfc` 之所以 1845 行全 pending，正是 2026-08-27 那次 `ArcUndeclaredThrowableException` 后置物化失败留下的——数据一直等着被补算、没有丢单。这是 `IS NULL` 自愈机制在生产数据上的自然验证。

---

## 6. 未达成 / 未验证 / 已知局限（不含糊）

| # | 项 | 状态 |
|---|---|---|
| **1** | **T-2 的还原实验证明力弱** | 埋点与修复在同一个 commit 引入，还原后表现为「日志不存在」而非「updates=1845」，**没能证明改动前是 1845 次往返**。<br>🔑 该事实由**另一条独立证据链**支撑：主线 jstack 采样在 ④ 的 71/93 个采样里抓到 `UpdateCoordinatorStandard.doDynamicUpdate` → `MutationExecutorSingleNonBatched.performNonBatchedOperations` 帧，这是「逐行非批量 UPDATE」的直接证据。**结论不靠这一条用例撑。** |
| **2** | **AC-10 的 UI 序列部分未验证** | AC-10 要求「编辑失焦 → 切走切回 → 刷新后值持久」，属前端/E2E 范畴。本次前端零改动（见 `fronttask.md` 判定依据），只验证了后端并发路径。**UI 持久化序列留给闸门 B 用户验收。** |
| **3** | **AC-11 的 `[subtotal-single-source]` 计数日志比对未做** | 测试夹具无公式驱动的 subtotal，该日志未触发。测试代理按「禁止读实现代码」纪律未去猜公式 JSON schema，**如实标为缺口**。AC-11 的另一半（有 componentData 的行不回归）已由 §2 整单实测覆盖（该单 cd 非空，`updates=2` 含 cd 表写入）。 |
| **4** | **冷启动首次执行仍需约 22s** | 热态 5.9s、冷态约 22s（③9616 + ④11798 + ①②）。仍远优于基线的 105.9s，但 **JVM 重启后的第一张大单会明显慢于后续**。这是 JIT 特性、非本次改动引入，**如实记录以免后续误判为回归**。 |
| **5** | 既有失败测试（**非本次引入**） | `QuotePendingScopeOpenWhitelistTest`、`NonDraftPrecisionReadOnlyTest.tc057_*` 在 `a8a4ec85^` baseline 上同样失败（后端代理做过背靠背对照）。主线核实：主工作区 `QuotationService.java` 正被**并发会话**改动（未提交），正是白名单告警的来源。**不在本次范围。** |
| **6** | `CardValuesBatchPersistEquivTest` / `LazyExcelValuesEquivTest` 跳过 | 测试库 `cpq_db` 缺基准单夹具，**pre-existing 限制**，非本次引入。 |

---

## 7. 过程事故记录（供后续会话避坑）

**并发 `git stash` 事故**：后端与测试两个子代理被派进**同一个 worktree**（主线编排失误）。`git stash` 作用于整个工作区、不限于调用者关心的文件，测试代理做还原实验时的裸 `git stash push` 把后端**尚未提交**的全部实现改动一并扫走。

- 后端代理未盲目重写，而是 `git stash show -p` 核对内容一致后用 `apply`（非 `pop`，保留安全网）恢复；
- 主线止血：① 立即提交实现 `a8a4ec85`；② 向测试代理下达新指令，禁用一切 `git stash`/`reset`/`clean`，改用 `git checkout <commit> -- <单个文件路径>` 做还原实验。

🔑 **教训**：**多个子代理不应共用一个 worktree**，或必须在派工 prompt 里显式禁止 `git stash`。本次派工六段写了「不许 `git commit`」，但没写「不许 `git stash`」——而后者同样能破坏他人未提交的产出。
