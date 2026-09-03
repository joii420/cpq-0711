# 测试执行报告 · task-260901 保存草稿增量协议与并发保护

- **执行日期**：2026-09-01
- **执行人**：测试工程师（子代理）
- **分支**：`feat/task-260901-incremental-draft`
- **状态**：后端 17/17 全绿；E2E 通过 6 条（T-1/T-4/T-12/T-14/T-19/T-21）；**T-18 未达成（差 270 ms）**；T-22 待用户裁决；9 条未执行

> 🚫 本报告只陈述**实测到的**结果。未跑的用例一律标「未执行」，不写「应该通过」。
> 报告中每个数字都可在 `证据/` 或下文引用的日志行中复核。

---

## 0. 执行环境（全部实测确认，非推断）

| 项 | 值 |
|---|---|
| 测试库 | `cpq_db_0724`（用户 2026-09-01 裁决从 `cpq_db` 切换） |
| 后端 | worktree 临时实例 **8099**（`quarkus:dev`，默认 profile） |
| 前端 | worktree 临时实例 **5199**（`VITE_API_TARGET=http://localhost:8099`） |
| 主线环境 | **8081 / 5174 全程未碰** |

**8099 验明正身**（不靠读配置）：`GET /quotations?page=1&size=1` 首条为 `QT-20260901-0218`，
该单只存在于 `cpq_db_0724`；两库 `quotation` 计数 **0724=9 / cpq_db=1277** ⇒ 确认连的是 0724。

**V398 迁移**：`installed_on=2026-09-01 07:06:46`，`success=t`；`quotation.user_data_version` 列已建，9 张单初值全为 `0`。

**基准单完整性**（每轮前校验）：`QT-20260901-0218` = `DRAFT / 1845 行 / 9225 条 componentData`。

---

## 1. 后端契约测试：**17 / 17 全绿** ✅

`cpq-backend/src/test/java/com/cpq/quotation/task260901/`，全部走 **HTTP + 原始 JSON**，不引用任何 DTO 类
（断言口径只能来自 `api.md`，实现改字段名就该红）。

| 用例 | AC | 结果 |
|---|---|---|
| `ac2_addedLineIsInsertedAndNewIdReturned` | AC-2 | ✅ |
| `ac3_removedLineAndAllFourSubTablesGoToZero` | AC-3 | ✅ |
| `ac4_headerOnlySaveDoesNotTouchAnyLineItem` | AC-4 | ✅ |
| `ac17_newRowClaimsDbIdAndSecondSaveDoesNotDuplicate` | AC-17 | ✅ |
| `ac20_processRowsSurviveIncrementalSave` | AC-20 | ✅ |
| `ac20b_omittedProcessFieldsAreTreatedAsCleared` | 语义存档（非 AC） | ✅ |
| `ac24_emptyQuotationSaveSucceeds` | AC-24 | ✅ |
| `ac6_untouchedLineComponentDataUnchanged` | AC-6 | ✅ |
| `ac7_onlyChangedLineCardValuesCleared` | AC-7 | ✅ |
| `ac9_semanticallyEqualRowDataWithDifferentKeyOrder…` | AC-9 | ✅ |
| `ac10_lastDigitDifference_isTreatedAsChanged` | AC-10 | ✅ |
| `ac16_modifiedLineKeysAreExactlyTheSix` | AC-16 | ✅ |
| `ac16b_addedLineCarriesTempIdAsSeventhKey` | api.md §1.3 作用域 | ✅ |
| `ac11_saveIncrementsUserDataVersionByOne` | AC-11 | ✅ |
| `ac12_staleBaseVersionIsRejectedWith409` | AC-12 | ✅ |
| `ac13_derivedWritesDoNotBumpUserDataVersion` | AC-13 | ✅ |
| `ac14_quoteCardEditReturnsNewVersion` | AC-14 | ✅ |

**AC-3 / AC-20 在这里才有分辨力**：dev 库 `quotation_line_process` /
`quotation_line_composite_process` / `quotation_line_item_snapshot` **全库 0 行**，
E2E 层断言恒为 `0==0`。本套夹具主动为行挂上工序 ×2、组合工艺 ×2、行快照 ×1（前置计数均 >0）。

---

## 2. E2E 结果

| 用例 | AC | 结果 | 说明 |
|---|---|---|---|
| **T-1** | AC-1 | ✅ **通过 ×2**（28.6s / 28.1s） | `modified` 长度 1、id 命中、`added`/`removed` 空、**请求体 2310 B < 100 KB**、新值落库 |
| **T-4** | AC-4 | ✅ **通过 ×2**（35.0s） | 三数组空、`projectName` 落库、**1845 行 xmin 变化 = 0**、请求体 516 B |
| **T-12** | AC-12 | ✅ **通过**（59.4s） | 见 §2.1 |
| **T-14** | AC-14 | ✅ **通过**（29.3s） | `quote-card-edit → userDataVersion=2`，随后保存 200 |
| **T-18** | AC-18 | ❌ **未达成**（B-6 后复测，见 §10） | 三轮 9381 / 10270 / 8129 ms，取最大 **10270 ms** > 10000 ms（超 270 ms / 2.7%） |
| T-2 | AC-2 | ⚠️ 夹具问题，已修**未复验** | 抽屉需先点「查询」+ antd 勾选框定位 |
| T-5 | AC-5 | ⚠️ 夹具问题，已修**未复验** | 「不发 PUT」✅ 已达成；toast 时序错 |
| T-24 | AC-24 | ⚠️ 夹具问题，已修**未复验** | 「未发 PUT」✅、无错误提示 ✅；仅告警白名单写窄 |
| **T-19** | AC-19 | ✅ **通过**（56.9s） | 第二轮补跑，详见 §8.1 |
| **T-21** | AC-21 | ✅ **通过**（第 7 轮，1.6m） | 第二轮补跑，详见 §8.1 / §8.3 |
| T-22 | AC-22 | ⏸ **待用户裁决** | 提交会在共享库留下撤回也删不掉的核价单，见 §8.5 |
| T-3 / T-6 / T-7 / T-8 / T-15 / T-16 / T-17 / T-20 / T-23 | — | ⛔ **未执行** | 环境调通耗时超预期，未排到；协议形状类已由后端 `ac*` 覆盖 |

### 2.1 T-12 证据（AC-12 逐条达成）

```
单元格写入 → userDataVersion=10
会话 A 保存：版本 10 → 11，projectName=AC12-A-1788274131851
会话 B 保存 → HTTP 409，reason=STALE_VERSION，currentVersion=11，响应体 112 B，861 ms
弹窗：标题「保存失败」/ 正文含「这张报价单已被他人修改」/ 按钮 = ["刷新页面"]（恰好 1 个）
      / 无右上角 × / 无「强制覆盖·忽略·稍后」
点「刷新页面」→ 重载后 userDataVersion=11、projectName=AC12-A-1788274131851（正是 A 的版本）
```
截图：`证据/screenshots/T-12-stale-version-modal.png`，与 `原型图/冲突提示.html` 状态 1 逐项一致。

### 2.2 增量协议的实测收益（T-1 / T-4 归档数字）

| 指标 | 现状基线 | 本期实测 |
|---|---|---|
| `PUT /draft` 请求体（改一个格子） | 9.3 MB | **2310 B** |
| `PUT /draft` 请求体（只改单头） | 9.3 MB | **516 B** |
| `PUT /draft` 响应体 | 24.6 MB | **1561 B** |
| 只改单头时被 UPDATE 的 line item | 1845 行 | **0 行**（xmin 逐行比对） |

---

## 3. T-18 性能：**B-6 前基线**（历史记录，⚠️ 最终结论见 §10）

三轮端到端（点击 → PUT 返回 **且** 目标行 `quote_card_values` 由 NULL 回填为非 NULL）：

| 轮次 | PUT 耗时 | 端到端 | 环境探针（0 行空单 GET） |
|---|---|---|---|
| 1 | 8900 ms | **12312 ms** | — |
| 2 | 8155 ms | **11732 ms** | 132 ms，active 查询 0 |
| 3 | 10444 ms | **15786 ms** | 175 ms，active 查询 0 |

取最大值 **15786 ms**，超 AC-18 的 ≤10000 ms。**环境探针全部通过（干净基线 ~180 ms），数字不作废。**
对比现状基线 97.6 s，改善约 **6~8 倍**，但未达标。

后端 `[draft-profile]` 分段（8 次采样）：

```
S1.saveDraft       81 / 150 / 209 / 259 / 497 / 545 / 1201 / 1210 ms   ← 本期优化对象，已压到几百毫秒
S2.snapshotRows  1859 / 2103 / 2162 / 2235 / 2332 / 2726 / 3059 / 5145 ms
S3.priceReconcile 3302 / 3436 / 3447 / 3703 / 4109 / 4393 / 4711 / 6071 ms   ← 最大头
```

主线据此定位根因：`PriceReconciler.prefetch` 加载 1845 行完整实体，而 B-1c 只失效 1 行后，
其余 1844 行的卡片值 jsonb 仍在，prefetch 真要搬（≈7.8 MB ÷ 1.74 MB/s ≈ 4.5 s）。
**属本期改造的连带成本，已回流后端修（B-6 投影查询）。**

🚫 **复测前提**：必须无他人并发 run（本轮全程有一个他人的 `zz-verify-fieldconfig.spec.ts` 在跑），
且环境探针通过；不通过则数字直接作废。

---

## 4. ✅ 证伪实验：6 / 6 全部具备分辨力

方法：单文件 `cp` 备份 → 破坏 → 跑 → 记录变红项 → `cp` 还原 → **`diff` 逐字节确认**。
🚫 全程未用 `git stash`（会端走前后端在途改动）；🚫 未碰 `PriceReconciler`（后端正在改）。

| # | 守卫 | 破坏方式 | `test.md §4` 预期 | **实测变红项** | 结论 |
|---|---|---|---|---|---|
| 1 | B-1a 语义比对 | `JsonSemanticEquality.equal()` 改回纯字符串比对 | T-9 | **`ac9`**（红在 xmin：`前=7841142 后=7841143`） | ✅ 符合 |
| 2 | B-1c 有条件置 NULL | 逃生阀 `CPQ_SAVEDRAFT_CONDITIONAL_INVALIDATE=false` | T-7 | **`ac9`**（红在「未变的行不得清空 quote_card_values，后=null」）；`ac7` **未红** | ⚠️ **预期需更正**，见 §4.1 |
| 3 | B-3b 版本校验 | 去掉 `StaleVersionException` | T-12 | **`ac12` + `ac14`**（409→200 两处） | ✅ 超出预期（`ac14` 对照组也接住了） |
| 4 | B-3e 派生不递增 | `ensureCardValues` 里加自增 | T-13 | **`ac13`**（`expected: <0> but was: <1>`） | ✅ 符合 |
| 5 | **T-16 专项**（我提议） | 给 `modified` 行也塞 `tempId` | — | **`ac16`**（键集实测 7 键）；**`ac16b` 保持绿** | ✅ 分辨力确认 |
| 6 | B-4b 轻量响应 | 响应退回整单回传 | T-15 | **`ac16` + `ac16b`**（都因「只应回传 1 行」而红） | ✅ 符合（后端代理判据） |

**还原核验**：4 个被动过的文件（`JsonSemanticEquality` / `QuotationService` / `CardSnapshotService` /
`SaveDraftResponse`）全部与备份 `diff` 一致；全工程无 `FALSIFY` 残留；还原后全套 **17/17 绿**。

### 4.1 🚩 `test.md §4` 的 B-1c 期望值已过时，需更正

原表写「B-1c → **T-7** 变红（空值行数变 1845）」。那是**全量协议时代**的预期：
当时 payload 含全部 1845 行，无条件置 NULL 会让空值行数变成 1845。

**增量协议下 payload 只含变化行**，无条件置 NULL 也只波及那一行 ⇒ T-7 的「恰好 1 行」照样成立，**测不出来**。
实测真正守住 B-1c 的是 **T-9**（它发的是「语义未变」的行，本该保住卡片值）。
同一命令不设逃生阀的对照跑 6/6 全绿，证明红确由破坏引起。

**建议把 `test.md §4` 该行的期望改为 T-9。**

### 4.2 §5 印证了「证伪必须精确对准不变量」

B-4b 的破坏让 `ac16` 红，但那是因为**行数**不对（回传 1845 行而非 1 行），
与 `tempId` 作用域毫无关系。若只做 B-4b，一个「对所有行都回传 7 键」的实现能一路绿到
AC-17 才炸，且症状是「重复插入」，排查时不会有人想到根因在 AC-16 的用例写宽了。
**§5 这条专项证伪是必要的，不是冗余。**

---

## 5. 🚨 发现与登记

### 5.1 脆弱契约（主线要求登记，非缺陷）

`modified` 行的 payload 是**整行权威** —— 缺席字段视为「用户清空了它」。
实测（`ac20b_omittedProcessFieldsAreTreatedAsCleared`）：payload 不带 `processNos` /
`compositeProcesses` ⇒ 该行工序 2→0、组合工艺 2→0；而**未提交的 B 行完全不受影响**。

真实 payload 两者都带（主线核实 `QuotationWizard.tsx:1245/:1247`），故**当前无缺陷**
（`ac20_processRowsSurviveIncrementalSave` 实测 A 行 2→2、组合工艺 2→2，全绿）。

**风险**：将来任一前端路径（新入口、重构、条件分支）忘了带这两个字段，
用户改一个格子就会**静默丢掉整行工序** —— 不报错、不变红、无告警。
已把该语义钉成可执行用例，它变红的那天就是有人动了这个契约的那天。

### 5.2 既有测试基础设施的问题（非本任务引入，登记给主线）

| 项 | 问题 |
|---|---|
| `EnsureCardValuesEndpointTest` | 对 401 采取**「容忍」**策略（`status == 200 \|\| status == 401`）——等于放弃断言，是结构性假绿 |
| `fixtures/precision.ts` / `fixtures/task260825-paging.ts` | `.qt-sku-badge` 的「料号: xxx」正则**已失效**：实测徽标文本是「客户产品编号: A1409」。依赖它定位卡片的 spec 会同样失效 |
| `fixtures/auth.ts` | storageState 分支含**无超时**的 `waitForLoadState('networkidle')`；本项目页面持续有后台请求，可能永不达成 ⇒ 挂死且不报错 |
| `e2e/global-setup.ts` | `unlockAccounts()` 的 psql **硬编码 `-d cpq_db`**（老库），已无意义；且 `alice`/`bob` 在 0724 不存在，每轮固定白等 2×15 s |
| `cpq_db_0724` schema | 后端启动报 `missing table [mat_composite_process]`（既有缺口） |

---

## 6. 我自己犯的错（全部已修，如实登记）

| # | 缺陷 | 影响 |
|---|---|---|
| 1 | `application-test.properties:86` 的 `rbac=true` 压过 test/resources 的 `false` | 后端首跑 16/16 全红 401 |
| 2 | 夹具编造 `process_no` 撞外键 | 后端全包红 |
| 3 | 本机无 Playwright 内置 chromium | E2E 首跑全红 |
| 4 | **误判旧 run 已死，起了第二个 Playwright 打同一张单** | 违反 `workers:1` 契约；已 kill，基准单经核验未被污染 |
| 5 | 后台 `sleep` 轮询不阻塞 | 误判「卡了 11 分钟」，实际只过 2 分钟 |
| 6 | 抄了 `task-260825` 的 `networkidle` 坏模式 | E2E 挂死不报错 |
| 7 | `.qt-sku-badge` 选择器过时 | 卡片定位超时 30 s |
| 8 | `editCell` 不等 `quote-card-edit` 往返就点保存 | **自造 409**，一度误判为前端不同步版本号（已撤回） |
| 9 | 抽屉不点「查询」就等行 | 白等 30 s |
| 10 | toast 瞬时反馈等窗口走完才断言 | 必然扑空，一度误判 AC-5 未实现 |
| 11 | 把库的废弃告警当「页面异常」 | T-24 假红 |
| 12 | `loginAdmin` 未处理「已登录被重定向」态 | `fill` 白等 120 s |
| 13 | **工序指纹按 `id` 排序** | 行被 delete+insert 重建后 id 变、顺序变 ⇒ 内容没变指纹也变，一度误判 AC-20 有缺陷 |

**两条方法论教训**：
- 「日志停住 + 进程还在」≠ 产品卡死。**先用不含测试框架的最小探针打同一条路径**，
  才能把 harness 问题和产品问题切开。我在拿到探针证据前提了三个基于错误前提的方案。
- 判内存看 `available` 不是 `used`。我据 `used 9.8Gi` 断言「内存吃紧」，被 `available 13Gi` 推翻。

---

## 7. 未完成事项

1. ~~T-18 复测~~ **已完成**（见 §10）—— 结论：未达成，差 270 ms
2. **T-2 / T-5 / T-24 复验**（夹具已修，未跑）
3. **9 条 E2E 未执行**：T-3 / T-6 / T-7 / T-8 / T-15 / T-16 / T-17 / T-20 / T-23
   （T-19 / T-21 已于第二轮补跑通过，见 §8；T-22 待用户裁决，见 §8.5）
4. **回归基线未跑**：`quotation-flow.spec.ts`（既有恒 4 条失败，判回归须 A/B 同型对比）、
   `vitest run src`（基线 98 files / 1180 tests 全绿）
5. `test.md §4` 的 B-1c 期望值更正（见 §4.1）

---

# 8. 第二轮执行补充（2026-09-01 08:00–08:35）

## 8.1 新增结果

| 用例 | AC | 结果 | 证据 |
|---|---|---|---|
| **T-19** | AC-19 | ✅ **通过**（56.9s） | 保存前 costing NULL = 0/1845 → 保存后 = 1（恰为「≤ 前+1」）；详情页核价视图渲染出产品卡、无「暂无组件数据」、无「加载中…」 |
| **T-21** | AC-21 | ✅ **通过**（第 7 轮，1.6m） | `料号 202601010002 的 annual_volume：1 → 100`、`PUT /draft → 200`；判据为**归属性**（挑一行当前值≠100 的、断言它由已知旧值变为 100），非终态存在性 |

## 8.2 T-21 —— 功能达成的产品侧证据（独立于 harness 成立）

第二轮执行时用例的真实 UI 操作已经生效，库与后端日志双向确认：

```
料号 202601010001（Step3 第 1 行）annual_volume：NULL → 100
后端 08:20:41  Saved draft for quotation id=77ffe749-...
```

即 **AC-21「Step3 改年用量 → 点下一步 → 落库」在功能上已被实证达成**，
`repair-260830` 记录的「Step3 程序化通道漏进 diff ⇒ 改动永不落库」这个陷阱**未复现**。

后续 5 轮重跑不是因为功能可疑，而是为了拿一个**方法论上干净**的绿（详见 §8.3），
**第 7 轮取得**（见 §8.1 表）。前 6 轮的红/绿一律不作数，理由逐条列在 §8.3。

⚠️ 附带观察（非 AC 违规）：该次 Step3 保存把其余 1844 行的 `annual_volume` 由 NULL 写成 `1`，
`[draft-profile] total=26914ms`。这是 Step3 初始物化的既有行为，方向上与「保存做轻」相反，
是否跟进由主线判断。

## 8.3 T-21 六轮重跑各自暴露的 harness 缺陷（#14~#18）

| 轮次 | 现象 | 根因 | 处置 |
|---|---|---|---|
| 2 | 红 | `await resp.text()` 抛 `Protocol error (Network.getResponseBody): Request content was evicted from inspector cache` —— **响应体只用于丰富失败信息，却成了失败源** | 改 `.catch()` 兜底 |
| 3 | **假绿** | 断言写成「库中存在 `annual_volume=100` 的行」= **终态存在性**；上一轮已写过 100 ⇒ 前置恒真 ⇒ 保存什么都不做也绿。而 AC-21 恰恰是防「改动永不落库」的，对此**零分辨力** | 改为**归属性判据**：每轮挑一行当前值≠100 的，断言它由「已知旧值」→100 |
| 4 | **静默 skip** | 8099 热重载 9.5s 期间 `/api/cpq/health` 不可达；既有 `isBackendUp()` 只探一次、超时 3s ⇒ `test.skip` 把用例跳过。**skip 在报告里长得像「不适用」，但它既不是通过也不是失败** | 夹具新增 `waitBackendUp()`（90s/3s 重试），6 个 spec 全部换用 |
| 5 | 红（守卫拦下） | `row_number() OVER (ORDER BY sort_order)` 与 `WHERE annual_volume IS DISTINCT FROM 100` 同层 ⇒ 窗口函数在 WHERE 后求值 ⇒ 算的是**过滤后集合**序号，而 Step3 渲染整表 ⇒ 下标错位 | `row_number()` 移进子查询在未过滤集合上求值 |
| 5b | 红（守卫拦下） | antd `InputNumber` 用 `fill()` 无效：React `onChange` 不触发，失焦后值回退 | 改 `click → Ctrl+A → Delete → pressSequentially` 真实键入 |
| 6 | 红 | `编辑向导应就绪（「下一步」可见）/ element(s) not found` —— 页面未渲染出向导，时间点与 8099 热重载重合 | 重跑 |
| 7 | ✅ **通过**（1.6m） | — | `料号 202601010002 的 annual_volume：1 → 100`、`PUT /draft → 200` |

**两道守卫救了两次**：
- 「Step3 第 N 行文本必须含该料号」拦下了第 5 轮的下标错位 ——
  没有它，用例会去改**第 1 行**（已是 100），再拿第 2 行断言，要么给出莫名其妙的红，**要么改错行却仍然绿**。
- 「输入未生效则本用例无分辨力」拦下了第 6 轮的 antd 输入回退。

## 8.4 🔑 B-6 时间线：T-18 旧三轮确为「B-6 前」，基线有效

```
PriceReconciler.java 修改时间  = 08:06:28（投影查询 + record LineRef 均在位）
T-18 三轮 draft-profile        = 07:49 / 07:50 / 07:51 / 07:51   ← 全部早于 08:06:28
8099 (quarkus:dev) 重启        = 08:03、08:09、08:15、08:28、08:31 ← 08:09 起晚于 08:06:28
```

两条结论：
1. **T-18 的三轮未被 B-6 污染**，可作对比基线。
2. **8099 当前跑的已是 B-6**，届时复测拿到的是真正的 after。

初步信号（**不作判据**）：B-6 后基准单一次保存 `[draft-profile] total=4016ms`，
B-6 前同一张单区间为 `6313~11366ms`。

### 🚨 T-18 复测的新增前提

后端工程师在本 worktree 跑 `mvnw test` 会重新编译 `target/classes`，
而我的 `quarkus:dev` 监听该目录 ⇒ **被动热重载 9.5 秒**。实测 08:28、08:31 两次重启即由此产生
（近 15 分钟内无任何后端源码改动）。

复测期间必须确认**无人在本 worktree 跑 `mvnw test`**：重编译 + 重载 + 其测试负载会污染性能数字，
而重载若恰好落在三轮之间，环境探针可能测不到、数字已经偏了。

## 8.5 T-22 立场更新：我撤回 A，同意主线的 C

我原先算的是「哪张单被弄脏」（沙箱单是测试专用单）；主线算的是「哪个界面被弄脏」——
`costing_order` 现全库 0 条，核价工作台是空的；跑完永久多一条测试数据，
且 `CostingOrderResource` **无任何 `@DELETE` 端点**，产品功能删不掉。
代价不是「一张脏单」，是「用户天天打开的业务界面里多一条清不掉的假数据」，量纲更大。
且 AC-22 替代覆盖不弱：提交端点本任务零改动（`api.md §5` 明列为不变更端点）。
**最终由用户裁决。**

## 8.6 harness 缺陷累计更新（#14~#18）

| # | 缺陷 |
|---|---|
| 14 | 用 `resp.text()` 拼失败信息，它会因 inspector 缓存淘汰而抛错 → 把功能已通过的用例判红 |
| 15 | **断言写成终态存在性 → 第二轮起恒真（假绿）**。可重复执行的用例里，终态存在性断言会随轮次退化成恒真；必须用「能证明这一轮的动作造成了它」的归属性判据 |
| 16 | 单次就绪探测遇热重载 → 用例**静默 skip**；skip 既不是通过也不是失败 |
| 17 | 窗口函数与 WHERE 同层 → 下标按过滤后集合算，与渲染的整表错位（AP-54 同型） |
| 18 | antd `InputNumber` 用 `fill()` 无效 —— `repair-260830` 在单头字段上记过同一个坑，我隔几小时在 Step3 又踩一次 |

**贯穿性教训**：本轮 18 个 harness 缺陷里，有 **3 个是从既有夹具抄来的过时 DOM 约定**
（抽屉 `tbody tr`、卡片 `.qt-sku-badge`、Step3 antd measure row）。
**抄既有 spec 的选择器等于继承它的技术债**，而代价是每次都要用一整轮失败来发现。

---

# 9. 方法论沉淀（本轮最值得带走的四条）

## 9.1 🚩 可重复执行的用例里，**终态存在性断言会随轮次退化成恒真**

本轮实证（harness 缺陷 #15）：T-21 的判据原本写成

```
断言「库中存在 annual_volume = 100 的行」          ← 终态存在性
```

第一轮它是有效的（改前全表都是 NULL）。**但第二轮起它恒真** —— 上一轮已经把 100 写进去了，
于是「保存什么都不做」也能让它绿。而 AC-21 恰恰是防「Step3 的改动永不落库」的，
对这件事它已经**零分辨力**。我因此否掉了一个已经到手的 `✓ 1 passed`。

改法是把判据从「存在性」换成**归属性** —— 能证明「**这一轮的动作**造成了它」：

```
每轮用 SQL 挑一行当前值 ≠ 100 的（1844 行可选）
断言：该行的 annual_volume 由「已知旧值」变为 100
外加：Step3 第 N 行的文本必须含该料号（下标 ↔ DB 行的对应关系必须验证）
```

> ⚠️ 这与「断言状态而非变化量」不矛盾。那条讲的是**校验逻辑**该用状态不变量；
> 这里的问题不是 state vs delta，而是**存在性 vs 归属性**：
> 断言必须能把结果**归因到本次操作**，而不只是描述「现在它是这样」。
> 判据自检：**把被测动作整个删掉，这条断言还会绿吗？** 会 → 它已经退化了。

## 9.2 `skip` 既不是通过，也不是失败

harness 缺陷 #16：`quarkus:dev` 热重载 9.5 s 期间健康探测失败 ⇒ `test.skip(!backendUp)`
把用例跳过，全部输出只有一行 `1 skipped`。

**`skip` 在报告里长得像「不适用」**，一次环境抖动就能让一条用例凭空消失且无人察觉。
修法：`waitBackendUp()`（90 s / 3 s 重试）把「正在重载」与「真的没起来」区分开，
真失败时打 error 日志。实测接住了「第 4 次探测才就绪」。

## 9.3 「日志停住 + 进程还在」≠ 产品卡死

E2E 首次调通阶段，我据「日志 11 分钟没动」判定「1845 行大单在本机浏览器里活不下来」，
并据此提了三个基于错误前提的方案（停服务、换 preview、放弃大单）。

一个**不含测试框架的最小探针**打同一条路径，**11.3 秒全部就绪**：

```
1.4s navigation committed → 2.2s 下一步/保存草稿可见
9.2s getById 200（24.9 MB）→ 11.3s Segmented + 产品卡 10 张 → DONE-OK
```

真凶是我自己的 harness（`networkidle` 永不达成 + `auth.ts` 里无超时的 `waitForLoadState`
+ trace 录制）。**harness 被证明可信之前，测试报告不作为验收证据** —— 这轮把这句话撞了一遍。

## 9.4 抄既有 spec 的选择器 = 继承它的技术债

本轮 18 个 harness 缺陷里，**3 个是从既有夹具抄来的过时 DOM 约定**：

| 抄自 | 失效的约定 | 实际 |
|---|---|---|
| `fixtures/precision.ts` / `task260825-paging.ts` | `.qt-sku-badge` 内「料号: xxx」 | 徽标实为「客户产品编号: A1409」 |
| `task260825-paging.ts` | `waitNetworkIdleTolerant` | 该页持续有后台请求，`networkidle` 永不达成 |
| 通用写法 | `tbody tr` 取 `.first()` | antd 第一个 `tbody tr` 是 **measure row**，无内容无 input |

代价是每次都要**用一整轮失败来发现**。抄之前应先用只读探针把真实 DOM 打出来 ——
本轮 Step3 的两个探针共约 2 分钟，换掉了至少两轮失败重试。

---

# 10. T-18 复测（B-6 后）—— ❌ 未达成，差 270 ms

## 10.1 执行条件

- 后端工程师已停止一切会触发重编译的命令（主线 2026-09-01 08:38 通知）
- 发车前确认 8099 **连续 3 次探测 200** 才开始计时（最近一次热重载 08:38:41，计时始于 08:38:46 之后）
- 无 playwright / surefire / mvnw 并发；库内 `active = 0`
- 基准单 `DRAFT / user_data_version=19 / 1845 行 / NULL 卡片值 0`

## 10.2 三轮结果

| 轮次 | 环境探针（0 行空单 GET） | PUT | 端到端 |
|---|---|---|---|
| 1 | 112 ms | 5858 ms | 9381 ms ✅ |
| 2 | 124 ms | 5593 ms | **10270 ms** ❌ |
| 3 | 263 ms | 5617 ms | 8129 ms ✅ |

**取最大值 = 10270 ms**，AC-18 预算 10000 ms ⇒ **未达成，超出 270 ms（2.7%）**。
三轮探针均远低于 3000 ms 作废阈值（干净基线约 180 ms）⇒ **数字有效，不作废**。

请求体恒为 2311 B、响应体恒为 1562 B，与 T-1 一致。

## 10.3 B-6 的效果（后端分段计时对比）

```
B-6 前 S3.priceReconcile = 3302 / 3436 / 3447 / 3703 / 4109 / 4393 / 4711 / 6071 ms
B-6 后 S3.priceReconcile =  260 /  172 /  191 ms                     ← 约 20 倍
B-6 后 S1.saveDraft      =  670 / 2252 / 2172 ms
B-6 后 S2.snapshotRows   = 1996 / 2045 / 2055 ms
```

| 指标 | 现状基线 | B-6 前 | B-6 后 |
|---|---|---|---|
| 端到端（3 轮取最大） | 97600 ms | 15786 ms | **10270 ms** |
| 相对现状的改善 | — | 6.2× | **9.5×** |

## 10.4 剩余耗时的构成（供后续决策，不下结论）

PUT 约 5.5 s，其中 `S2.snapshotRows` 约 2.0 s 已是最大单项；
端到端减去 PUT 还剩 **约 2.5~4.7 s**，即**保存后触发的卡片值重算**。

⇒ `priceReconcile` 经 B-6 后已不再是瓶颈；当前两个大头是
**`snapshotRows`** 与 **PUT 之后的卡片值重算**。是否继续优化由主线/用户判断。

## 10.5 结论口径

**AC-18 未达成。** 差距虽只有 2.7%，但 AC 原文是「测 3 次取**最大值** ≤ 10 秒」，
最大值 10270 ms 超出预算 ⇒ 判未达成，**不四舍五入成通过**。
供决策参考：3 轮中 2 轮（9381 / 8129 ms）在预算内，结果处于边界。
