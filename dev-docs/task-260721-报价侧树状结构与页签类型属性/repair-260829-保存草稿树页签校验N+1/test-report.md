# test-report.md · repair-260829 执行结果与主线亲验

> 环境：worktree `repair-260829-savedraft-tabmeta-n1` · 分支 `fix/repair-260829-savedraft-tabmeta-n1`
> 临时后端 **8095**（worktree 代码 + dev 库 `cpq_db_0724`）· 测试库 `cpq_db`
> 基线单 `QT-20260828-0198` = `e41a2d24-...`，1845 行，模板 `99ff6aa4`（5 页签）

---

## 一、总体结论

**端到端从「永远失败」变为「稳定 16.9~17.5 秒成功」。**

| 阶段 | 修复前 | 修复后（实测） |
|---|---|---|
| `PUT /draft` 端到端 | **从未完成**（60 s 被 Narayana reaper 强杀，只跑完 200/1845 行） | **16.9 / 17.5 / 23.5 s**（三次，全部 < 30 s） |
| 主循环 1845 行 | ≈400 s（外推） | **21~24 ms** |
| `assert`（两个 N+1 合计） | ≈325 s（模板元数据）+ ≈55 s（树上下文） | **142~143 ms**（9,225 次） |
| 循环内 `em.flush()` | 1,845 次 ≈57 s | **0 次**（提到循环外，1 次 11.4 s） |
| 事务是否被砍 | **必然** | **零命中** |

---

## 二、自动化测试

### 2.1 主线清产物后亲跑（`./mvnw clean test`，worktree 内）

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 2  -- BatchStage1PersistEquivTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftCardValuesInvalidationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftComponentDataUpsertTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftCustomerTemplateIdOverrideValidationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftDuplicateComponentDataMigrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftExcelSnapshotTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftRestrictedTabValidationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftSerializeLockTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftTabStructureFallbackTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftUpsertTombstonePreservedTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  -- SaveDraftZeroLineItemsTest
总计: Tests run: 21, Failures: 0, Errors: 0, Skipped: 2  — BUILD SUCCESS
```

> `BatchStage1PersistEquivTest` 的 2 skipped：golden 单在测试库 `cpq_db` 不存在，**历来如此**，非本次引入。

### 2.2 零回归 A/B（主线执行，补齐子代理未做完的部分）

子代理只对 4 个失败做了 A/B，**约 20 个未逐条对照**。主线对 9 个可疑测试类做了完整 A/B：

- **手法**：`git stash push -- QuotationService.java QuotationTreeService.java`（**只 stash 两个主代码文件，刻意保留 V396 迁移文件** —— 迁移被 stash 掉会让 A/B 两侧的 schema 不一致，是已知陷阱）
- **A 集**（修复版）：45 测试 / 21 失败 / 5 错误
- **B 集**（基线）：45 测试 / 21 失败 / 5 错误
- **`diff` 结果**：**逐字相同**，唯一差异是 `QuoteBomTreeEndToEndTest` 三个用例里**每次运行随机生成的 componentId UUID**（同一失败、同一消息、同一行号）

✅ **结论：21 失败 + 5 错误全部 pre-existing，本次改动零回归。**

---

## 三、主线亲验（AC 逐条，不采信子代理回报）

| AC | 判据 | 亲验证据 | 结论 |
|---|---|---|---|
| **AC-3 / AC-24** | 续存态保存返回 200、无 reaper、< 30 s | 真实 3.49 MB payload（Playwright 从主仓前端拦截捕获，**abort 未发送**，主仓零污染）→ 8095：`http=200 time=17.79s`；`[draft-profile] total=17494ms \| S1=15932 S2=1387 S3=175`；日志区间内 `ARJUNA0121` **零命中** | ✅ |
| **AC-4** | componentData 恰好 9,225 行 | 保存后查库 = **9225**（不多不少） | ✅ |
| **AC-19** | `snapshot_rows` 逐字不变 | 保存前后 `md5(string_agg(snapshot_rows::text ORDER BY id))` = `bff08e02fbf75b51305526ef66faca96`，**两次完全相同**，行数 9225→9225 | ✅ |
| **B-6 核心** | 真走 UPSERT 而非 delete+insert | 保存前后 `md5(string_agg(id::text ORDER BY id))` **逐字相同** ⇒ 9,225 条记录 id 全部复用 | ✅ |
| **AC-25** | 采样中不再出现 `buildHitContext` 栈 | jstack **五次**采样，`buildHitContext` / `loadComponentDataByLineItem` 相关帧 **全部为 0**；埋点 `assert=143ms(x9225)`（修复前该链路 ≈55 s） | ✅ |
| **AC-22** | 重复组 0、约束存在、目标 lineItem 剩 8 条 | dev 库实测：重复组 **0**；`uq_qlcd_line_component` **存在**；`76c33527-...` 剩 **8 条**（7 条 tab_name 非空 + 1 条原生空值行未被误删） | ✅ |
| **AC-15** | 前端 timeout=60000、loading 态、tsc 0 错 | `api.ts` `timeout: 60000`（主仓对照仍 30000 ⇒ 确认改的是 worktree）；`loading={saving \|\| cardValuesWarming}` + `disabled` 已覆盖 `handleSaveDraft`；`message.loading(...)` + finally `hideSavingHint?.()`；**主线亲跑 `tsc --noEmit` exit=0** | ✅ |
| **AC-17** | 🔬 **还原实验**：改回原样必须变红 | 把 B-8 的 6 参调用临时改回 4 参 → **`http=500 time=60.2s` 被 reaper 砍、`★总结` 根本没打印**；恢复后 → `200 / 17.5s`。**证明亲验不是空验证** | ✅ |

---

## 四、契约变更

**无。** `PUT /quotations/{id}/draft` 与 `POST /v6/quote/create-quotation` 的方法、路径、请求体、响应体、错误码全部未变（详见 `api.md`）。
⇒ 按 `task-docs.md` §2.5，**本次无需回写 `dev-docs/main-api.md`**。

---

## 五、未验证 / 未执行项（如实列出）

| 项 | 状态与理由 |
|---|---|
| **AC-8 完整逐位等价** | ⚠️ **未做修复前后的双跑对比**。理由：**修复前该单根本存不进去**（60 s 被砍、事务回滚），**没有可比的基线数据**。替代覆盖：`SaveDraftComponentDataUpsertTest` 在单测层验证了 id 复用 / `snapshot_rows` / `deleted_row_keys` 逐字不变 + `row_data` 反映新值；主线在真实大单上验证了 AC-19 与 id 不变性 |
| **AC-6**（模板口径变化） | 由 `SaveDraftCustomerTemplateIdOverrideValidationTest` 覆盖并通过；**未在 dev 库真实双模板场景手工复验** |
| **AC-10**（序列 AC）/ **AC-11b**（并发） | **未执行** —— 需浏览器交互与双会话并发 |
| **AC-12 / 13 / 14** | 🚫 **已随 B-3 撤出本期**（与并发会话 `repair-260829-异步物化事务上下文缺失` 撞车，对方根因更准且已合并 `c1daeef7`） |
| **AC-26**（其他三个调用点不受影响） | 部分覆盖：`QuoteBomTreeEndToEndTest` 走 `addLeaf`/`previewDelete`，A/B 显示失败模式逐字相同（且为 pre-existing）；**未针对性设计新用例** |
| **T-01**（首存场景） | **未执行** —— 需先清空该单 componentData 造首存态，会破坏当前已验证的数据状态 |

---

## 六、过程中的重要教训

1. 🔬 **`curl` 的 `time_total` 会把 Quarkus dev 的热重载算进去**：一次测到 70.8 s 险些误判「性能不稳」，查日志才发现 = **53 s 热重载 + 16.9 s 真实处理**（`Restarting quarkus` → `Live reload total time` → `saveDraft-diag` 三个时间戳可对齐）。**dev 模式下判性能必须先排除重载。**
2. 🔬 **第一个 N+1 修好会暴露第二个**：B-1/B-2 让主循环从 ≈400 s 降到 21 ms 后，`buildHitContext` 的 55 s 才浮出水面。它一直存在，只是此前被「`snapshot_rows` 全空 ⇒ `treeRowsByComp.isEmpty()` 短路放行」掩盖。**立项文档 4.8 节已预言「复测基线会变」，却没据此扩范围 —— 预见到症状不等于推导出结论。**
3. ⚠️ **两处「注释拦不住调用方」**：`PublishedTemplateReader` 类注释写着「🚫 禁止新增单条查询方法被调用方放进循环」、`QuotationTreeService:80` 写着「`buildHitContext` 每次外部请求只调一次，SQL 条数 O(1)」—— **两条不变量都被 `task-0721` B8 的同一次接线破坏，注释都没跟着改**。
4. ⚠️ **AC 判据本身会写错**：AC-22 写「应剩 7 条」，实际是 8 条（14 − 6 = 8，漏算了一条从未重复的原生空 `tab_name` 行）。子代理在数字对不上时**停下核对而不是凑数删够 7 条**，处置正确。
