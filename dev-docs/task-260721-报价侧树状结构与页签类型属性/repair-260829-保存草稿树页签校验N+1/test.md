# test.md · repair-260829 测试方案与 AC 追溯矩阵

> 闸门 A 的**前置产物**，开工前定稿。执行结果另落 `test-report.md`。

## 一、环境与基线

| 项 | 值 |
|---|---|
| 后端 | `localhost:8081`（默认 profile）→ `10.177.152.12:5432/cpq_db_0724` |
| 前端 | `localhost:5174` |
| 基线单 | `QT-20260828-0198` = `e41a2d24-cadc-4668-bfd0-ba5a1dfb2337`，1845 行，模板 `99ff6aa4`（5 页签：主件/BOM/材质元素/主件/空） |
| 小单 | 从现网 119 张 `<50 行` 的单里任取一张（记录到 `test-report.md`） |
| 单测库 | `mvnw test` 走 `test` profile → `10.177.152.12/cpq_db`（**与 dev 库不同**，写集成测试注意） |

🚨 **测试红线**（`CLAUDE.md` §3.2）：**禁止在共享库上跑任何清库/重置全局状态的测试**，`@TestTransaction` 回滚是唯一允许的清理方式。

⚠️ **基线单的状态会被测试改变**：它目前 `componentData` 有 9,225 行、卡片值 1845/1845。**每轮性能测试前后都要记录这三个数字**；若测试把卡片值置 NULL，收尾必须调 `POST /quotations/{id}/ensure-card-values` 复原（实测 19.8 s）。

## 二、AC 追溯矩阵（双向覆盖）

### 正向：每条 AC 都有人认领

| AC | 类型 | 认领任务 | 验证用例 |
|---|---|---|---|
| AC-1 | 阴性·首存 | B-1,B-2 | T-01 |
| AC-2 | 阴性·埋点 | B-1,B-2 | T-01 |
| AC-3 | 阴性·续存 | B-1,B-2 | T-02 |
| AC-4 | 阴性·行数 | B-1,B-2 | T-02 |
| AC-5 | 阳性·校验仍拦 | B-1 | T-03 |
| AC-6 | 阳性·模板口径 | B-1,B-2 | T-04 |
| AC-7 | 阳性·逃生路径 | B-1 | T-05 |
| AC-8 | 无副作用·逐位等价 | B-1,B-2 | T-06 |
| AC-9 | 无副作用·小单 | B-1,B-2 | T-07 |
| AC-10 | 序列 | B-1,B-2 | T-08 |
| AC-11 | 边界·0 行 | B-1,B-2 | T-09 |
| AC-11b | 边界·并发 | B-1,B-2 | T-09b |
| AC-18 | B-6·性能 | B-6 | T-15 |
| AC-19 | B-6·snapshot 不变 | B-6 | T-15 |
| AC-20 | B-6·回落路径 | B-6 | T-16 |
| AC-21 | B-6·墓碑保留 | B-6 | T-17 |
| AC-22 | B-7·迁移 | B-7 | T-18 |
| AC-23 | B-7·约束不误伤 | B-6,B-7 | T-18 |
| AC-24 | B-8·端到端 | B-8 | T-19 |
| AC-25 | B-8·栈不再出现 | B-8 | T-19 |
| AC-26 | B-8·反向不影响他处 | B-8 | T-20 |
| AC-34 | B-11·排队后成功 | B-11 | T-24 |
| AC-35 | B-11·零延迟 | B-11 | T-24 |
| AC-36 | B-11·超时可理解 | B-11 | T-24 |
| AC-37 | B-11·等待在事务外 | B-11 | T-24 |
| AC-15 | 前端 | F-1,F-2,F-3 | T-12 |
| AC-16 | 自检 | B-4,B-5 | T-13 |
| AC-17 | 还原实验 | B-5 | T-14 |

### 反向：每个用例都指得回 AC

T-01→AC-1,2 ｜ T-02→AC-3,4 ｜ T-03→AC-5 ｜ T-04→AC-6 ｜ T-05→AC-7 ｜ T-06→AC-8 ｜ T-07→AC-9 ｜ T-08→AC-10 ｜ T-09→AC-11 ｜ T-09b→AC-11b ｜ T-12→AC-15 ｜ T-13→AC-16 ｜ T-14→AC-17 ｜ T-15→AC-18,19 ｜ T-16→AC-20 ｜ T-17→AC-21 ｜ T-18→AC-22,23 ｜ T-19→AC-24,25 ｜ T-20→AC-26。**无孤儿用例。**

## 三、测试用例

| 编号 | 层级 | 做什么 | 通过判据 |
|---|---|---|---|
| **T-01** | 手工+日志 | 清空基线单 componentData 造首存态 → 点保存 | 200；无 `ARJUNA012108/012121`；`S1.saveDraft < 30s`；`assert < 500ms`；flush 次数 ≤ 2 |
| **T-02** | 手工+SQL | 在 T-01 结果上（componentData 已 9225）再保存一次 | 200；`[draft-profile] total < 60s`；componentData **恰好 9225** |
| **T-03** | 集成测试 | 扩充 `SaveDraftRestrictedTabValidationTest`：材质元素页签放入树上有下级的料号 | 抛 `BusinessException(400)`，消息逐字匹配；`@TestTransaction` 回滚 |
| **T-04** | 集成测试 | 🆕 新建用例：单绑模板 A，保存请求携带 `customerTemplateId=B` | 校验按 **B** 的配置执行（构造一条只有 B 会拦的数据 → 400） |
| **T-05** | 集成测试 | `-Dcpq.savedraft-batch-stage1=false` 重跑 T-03 | 结果与 T-03 **逐字相同** |
| **T-06** | 手工+SQL | 修复前后同 payload 各保存一次，导出四张子表 + 总额比对 | **逐位相同**（用 `md5(string_agg(...))` 或导 CSV diff） |
| **T-07** | 手工 | 小单保存 | 200；耗时不劣化超 20%；子表逐位等价 |
| **T-08** | 手工·序列 | 保存 → 改「材料成本」一行 → 再保存 → 切页签再切回 → 刷新整页 | 三个时点断言均成立（含中间态） |
| **T-09** | 集成测试 | 0 产品行的空 DRAFT 单保存 | 200，不抛异常，`lines=0`，子表无残留 |
| **T-09b** | 手工·并发 | 两个浏览器会话对同一张 1845 行单几乎同时点保存 | componentData 恰好 9225（不翻倍/不缺失）；行为与修复前同型；异常情况如实记录不掩盖 |
| **T-10** | 手工·端到端 | 走一次完整 V6 导入 + 建单，等后台物化完成 | componentData = 行数 × 页签数（非 0）；无「快照整体失败(已降级)」；`①snapshotQuotation > 1000ms` |
| **T-11** | 集成测试 | mock `snapshotQuotation` 抛异常 | 建单本身仍提交成功；`warnings` 有记录；日志级别为 **ERROR** 而非静默 WARN |
| **T-12** | 手工·浏览器 | 1845 行单点保存，全程 F12 观察 | 无 `net::ERR_ABORTED`；按钮 loading 且不可重复点击；有等待提示 |
| **T-13** | 命令行 | `./mvnw test` + `tsc` + 端点探活 + `grep` 埋点残留 | 全绿；0 错误；401；`stage1-profile`/`meta-profile` **0 命中** |
| **T-14** | 🔬 还原实验 | 把 B-1 改回「循环内现查」重跑 T-01 | `assert` 必须**变红**回到 ×9225 量级 —— 不变红说明 T-01 是空验证 |

| **T-15** | 手工+SQL | 续存态保存，测 `prep`/端到端；保存前后取 `snapshot_rows` 的 md5 | `prep < 2s`；端到端 `< 20s`；`snapshot_rows` md5 **前后完全相同**；componentData 恰好 9225 |
| **T-16** | 集成测试 | 构造页签结构变化（换页签数不同的模板，或删掉某行一个 componentData）后保存 | 自动回落全删全建；结果数量与新结构一致；不抛错、不写坏 |
| **T-17** | 集成测试 | 某页签有非空 `deleted_row_keys` → 走 UPSERT 保存 | 墓碑逐字保留（与全删全建路径结果一致） |
| **T-18** | 迁移+SQL | 应用 B-7 迁移 → 查重复组 → 连续保存 3 次 | 重复组 = 0；`76c33527-...` 剩 7 条且 `tab_name` 均非空；`uq_qlcd_line_component` 存在；3 次保存全成功无约束冲突 |

| **T-19** | 手工·端到端+jstack | 续存态真实 payload 打 `PUT /draft`，期间 jstack 采样 ≥4 次 | 200；无 `ARJUNA012108/012121`；`[draft-profile] total < 30s`；采样中**不再**出现 `loadComponentDataByLineItem ← buildHitContext` 栈 |
| **T-20** | 集成测试 | 走 `buildHitContext` 另外三个调用点（`:234`/`:356`/`:461`）的既有功能 | 行为与修复前完全一致 |

| **T-24** | 集成测试 | `SaveDraftMaterializeWaitTest`：`@Inject MaterializeRegistry` 手动 `begin(qid)` → 另起线程延时 `end(qid)` → 主线程调 saveDraft | ① 等待后成功、总耗时含等待；② 不 `begin` 时零延迟；③ 超时抛 409 + 含秒数文案；④ 等待窗口内另一连接 `SELECT ... FOR UPDATE NOWAIT` 不被阻塞、`[draft-profile]` 的 `S1` 不含等待 |

## 四、回归范围

| 必跑 | 理由 |
|---|---|
| `BatchStage1PersistEquivTest` | 本次直接改 `processBatchStage1`，它是该方法的等价性护栏。⚠️ **B-6 会让它按现判据假红** —— UPSERT 路径下 `id` 与 `created_at` 必然不同，须按 AC-8 修正后的判据（排除这两列、`snapshot_rows` 留在对比内）同步调整该测试，**调整必须在 `test-report.md` 里逐条说明改了什么、为什么不是放水** |
| `SaveDraftRestrictedTabValidationTest` | 本次改的校验就是它守的 |
| `SaveDraftCardValuesInvalidationTest` | D-1 失效逻辑相邻（本次未改，但要证明没碰坏） |
| `TemplateServiceTreeTabInvariantTest` | `PublishedTemplateReader.allTabsOf` 调用方变化 |
| E2E `quotation-flow.spec.ts` | ⚠️ **已知干净 master 恒 3 失败**（夹具单缺产品分类，见 `task0712-update071501`）—— 判回归必须 A/B 同型对比，不许直接归因本次 |

## 五、不测什么（明确排除）

- INSERT 落库 29.8 s / S2 14.1 s 的性能 —— 本期不改，不设判据
- 子表增量保存 —— 本期不做
- 多线程/并发保存 —— 本期不引入，且既有纪律禁止

## 六、执行纪律

1. 🚫 **任何打开报价单页面的诊断脚本必须在网络层 abort 非 GET 请求** —— `task-260825` 因此清空过 1845 行卡片值（`BL-0188`）。本次测试脚本若需触发保存，必须**显式**说明并在 `test-report.md` 记录对数据的影响。
2. ⚠️ **worktree 里跑后端测试要在 worktree 的 `cpq-backend` 下跑**，不许 `cd` 回主仓（会测错树报假绿）。
3. ⚠️ **性能数字必须取两个以上数据点**才能下「线性/非线性」结论（本任务立项期已因此推翻过一个模型）。
