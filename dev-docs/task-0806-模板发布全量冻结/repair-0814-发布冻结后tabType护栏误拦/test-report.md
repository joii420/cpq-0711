# test-report · repair-0814 发布冻结后 tabType 护栏误拦

> **执行环境**：分支 `fix/repair-0814-tabtype-guard`（worktree `.claude/worktrees/repair-0814-tabtype-guard`，基于 `44d67f10`）
> 后端单测库 = `test` profile → `10.177.152.12:5432/cpq_db`；数据勘察库 = `10.177.152.12:5432/cpq_db_0724`（dev）
> 执行人：主线（技术总监）亲跑，未派子代理 —— 任务规模 S，且 `CLAUDE.md` 要求不采信子代理的"已完成"
> 日期：2026-08-14

---

## 1. 执行汇总

| 类别 | 通过 | 失败 | 跳过 | 说明 |
|---|---|---|---|---|
| 新增/扩充单测 | **27** | 0 | 0 | 三个类，见 §2 |
| 既有回归（核价树渲染） | **13** | 0 | 1 | `CostingBomTreeSnapshotTest` 1 skipped = 夹具缺失（`BL-0157` 族，非本次引入） |
| 既有回归（模板发布/公式） | **16** | 0 | 0 | 见 §3 |
| **合计** | **56** | **0** | **1** | |

**还原实验（判别力验证）**：2 次，全部按预测变红 —— 见 §4，这是本报告最关键的一节。

---

## 2. 新增用例结果

```
Tests run: 16, Failures: 0, Errors: 0 -- com.cpq.component.service.ComponentServiceTabTypeGuardTest      （基线 12 → +4）
Tests run:  7, Failures: 0, Errors: 0 -- com.cpq.template.service.TemplateServiceTreeTabInvariantTest    （新建）
Tests run:  4, Failures: 0, Errors: 0 -- com.cpq.quotation.service.BomTreeParentNoGuardTest              （新建）
```

| 用例 | AC | 结果 |
|---|---|---|
| `referencedByFrozenPublishedCosting_canBecomeBomTab`（TC-01） | AC-1 | ✅ |
| `referencedByFrozenArchivedCosting_canBecomeBomTab`（TC-04） | AC-2 | ✅ |
| `referencedByPublishedButUnfrozenCosting_cannotBecomeBomTab`（TC-03） | AC-3 | ✅ |
| `mixedReferences_blocksAndNamesOnlyUnfrozenTemplate`（TC-05/06） | AC-2/AC-6 | ✅ |
| 原有 12 个用例 | AC-5 | ✅ **语义一字未改**（见 §5） |
| `costingTemplateWithTwoTreeTabs_publishRejected`（TC-09） | AC-8 | ✅ |
| `netCountUnchanged_swappingWhichTabIsTree_isAllowed`（TC-10）★ | **AC-10** | ✅ |
| `costingTemplateWithExactlyOneTreeTab_publishes`（TC-11） | AC-9 | ✅ |
| `quotationTemplateWithTwoTreeTabs_publishesFine`（TC-12） | AC-9 | ✅ |
| `costingTemplateWithZeroTreeTabs_publishes`（TC-13） | 边界 | ✅ |
| `archiveAutoFreeze_withTwoTreeTabs_isNotBlocked`（TC-14） | 救援路径 | ✅ |
| `treeTabWithAllRowsMissingParentNo_throws`（TC-15） | AC-11 | ✅ |
| `treeTabWithPartialMissingParentNo_doesNotThrow`（TC-16） | AC-11 边界 | ✅ |
| `treeTabWithZeroKeptRows_doesNotThrow`（TC-17） | AC-11 边界 | ✅ |
| `nonTreeTab_neverThrows`（TC-18） | 回归 | ✅ |

---

## 3. 回归结果

```
核价树渲染：
Tests run: 5, F0 E0 -- BomTreeRenderServiceTest
Tests run: 2, F0 E0 -- BomTreeRenderServiceTreeParamMaskingTest
Tests run: 1, F0 E0, Skipped 1 -- CostingBomTreeSnapshotTest      ← skip = 夹具缺失，BL-0157 族
Tests run: 1, F0 E0 -- RefreshQuoteCardValuesTreeResilienceTest
Tests run: 4, F0 E0 -- CostingTreeGroupingTest

模板发布 / 公式：
Tests run: 1, F0 E0 -- PublishWithoutSubtotalTest
Tests run: 3, F0 E0 -- TemplateCrossTabCycleStructuredTest
Tests run: 4, F0 E0 -- TemplateCrossTabValidateTest
Tests run: 3, F0 E0 -- TemplateFormulaServicePrecisionTest
Tests run: 5, F0 E0 -- TemplateFormulaSumifTest
```

> ⚠️ **一次差点踩中的坑**（记录以备后来人）：中途用 `-Dtest='A+B+C'`（加号）分隔跑三个类，
> Maven **不识别**该语法 → 构建失败、**surefire 报告没有重新生成**，而我先读到的是上一轮
> 「还原实验」留下的**红色旧报告**，差点误判成"修复把测试搞红了"。
> 处置：删掉目标报告文件 → 改用逗号分隔重跑 → 确认报告时间戳与内容都是本轮的。
> 对应记忆 `cpq-agent-tests-stale-server-false-positive`（"agent 报 P0 可能测了旧代码"）的同型陷阱，**自己也会踩**。

---

## 4. 还原实验（判别力验证）★ 本报告最关键的一节

`test.md` §4.3 纪律：新增护栏用例**首次 PASS 不等于有效** —— 必须把修复改回去重跑，**不变红 = 空验证**。

### 实验一：撤掉 D-1（护栏收窄）

```bash
git stash push -- .../ComponentService.java .../PublishedTemplateReader.java
./mvnw -o -q test -Dtest=ComponentServiceTabTypeGuardTest
```

**结果**：`Tests run: 16, Failures: 1, Errors: 2` —— 恰好是新增的那 3 条，且报错原文正是**旧文案**：

```
com.cpq.common.exception.BusinessException: 该组件已被 1 处核价(COSTING)模板引用，不能设为 BOM
树页签——会把这些核价模板一并改成树渲染，破坏核价侧零回归。报价侧树页签请新建专用组件。
	at ComponentService.assertNotReferencedByCostingTemplate(ComponentService.java:419)
	at ComponentService.applyTabType(ComponentService.java:106)
```

预测与实际一致：TC-01/TC-04 抛旧异常（Errors=2），TC-05/06 的文案断言失败（Failures=1），
TC-03（阳性用例）在旧代码下**照常通过**——因为旧代码把所有情况都拦，本就包含这一档。

### 实验二：撤掉 D-2（publish 不变量断言）

```bash
git stash push -- .../TemplateService.java
./mvnw -o -q test -Dtest=TemplateServiceTreeTabInvariantTest
```

**结果**：`Tests run: 7, Failures: 1` —— **只有 TC-09 变红**，TC-10（"净数量不变应放行"）**保持绿色**。
这正是期望形态：TC-10 本就是「不该拦」的用例，撤掉断言后当然仍绿；它的价值在**另一个方向**——
若有人把实现改成 delta 判据，TC-10 会变红。

两次实验后均 `git stash pop` 恢复，恢复后复跑全绿（§2）。

---

## 5. AC 逐条达成对照

| AC | 内容 | 状态 | 证据 |
|---|---|---|---|
| **AC-1** | 阴性：已冻结引用放行 | ✅ | TC-01/TC-04 绿 + 实验一证明有判别力。**TC-24 真机待闸门 B**（见 §7） |
| **AC-2** | 阳性：DRAFT 引用仍拦 | ✅ | TC-02（**原有用例，一字未改**）、TC-05 |
| **AC-3** | 阳性：PUBLISHED 但未冻结仍拦 | ✅ | TC-03 |
| **AC-4** | 核价渲染逐位不变 ★一票否决 | 🟡 **结构性 + 单测级达成，逐位 A/B 待合并后完成** | 见 §6，**已抓改动前指纹基线** |
| **AC-5** | 联动与既有闸不变 | ✅ | 原 12 个用例全绿且未改；`git diff` 显示测试文件只有**追加**没有修改 |
| **AC-6** | 文案 | ✅ | TC-06 断言：含模板名 + 状态、**不含**「一并改成树渲染」、**含 `\n`** |
| **AC-7** | 同类排查 | ✅ **查完，有 1 个真发现** | 见 §8 |
| **AC-8** | 树页签不变量阳性 | ✅ | TC-09 + 实验二 |
| **AC-9** | 不误伤存量 | ✅ | TC-11/12/13 绿；实测存量 5/5 模板均恰好 1 个树页签，零违规 |
| **AC-10** | 防 delta 回归 ★ | ✅ | TC-10 绿；代码审查确认 `assertAtMostOneTreeTab` **只读 `snapshotRows`**，不查上一版 |
| **AC-11** | `parent_no` 检出 | ✅ | TC-15~18；强度依据 = 全库 18/18 树组件 `$view` 均含 `parent_no`，零合法反例 |
| **AC-12** | 强制自检 | ✅ | 见 §6 |

---

## 6. 强制自检

### 编译

```
[INFO] BUILD SUCCESS   （./mvnw -o compile，worktree 内）
```

### N+1 自检（`CLAUDE.md` 强制项）

> **N+1 自检：本次改动共 5 处循环，逐个复核如下 —— 循环体内无 repository 调用、无
> `SqlViewExecutor.execute`、无触发懒加载的关联 getter。单个业务操作 SQL 条数恒定，与 N 无关 ✅**

| 位置 | 循环 | 判定 |
|---|---|---|
| `PublishedTemplateReader.unfrozenAmong` | `for (TemplateComponentSnapshot s : snapshotRows)` | 纯内存，建 `Set` |
| 同上 | `for (UUID id : ids)` | 纯内存，`Map.get` + 简单字段读 |
| `ComponentService.assertNotReferencedByCostingTemplate` | `for (Template t : blocking)` 拼文案 | 只读 `name`/`version`/`status` 基础列，非懒加载关联 |
| `TemplateService.assertAtMostOneTreeTab` | `for (TemplateComponentSnapshot s : snapshotRows)` | 纯内存计数 |
| `TemplateService.warnIfMultipleTreeTabs` | 同上 | 纯内存计数 |

**SQL 条数**：护栏路径 = 3 条恒定（1 查 COSTING 引用 + `unfrozenAmong` 的 2 条）；
publish 断言 = **0 条新增**；`assertParentNoPresent` = **0 条新增**（无循环、无查询）。

### 其余自检项

| 项 | 结果 |
|---|---|
| Flyway 迁移 | **N/A —— 本次零迁移** |
| 视图 DROP CASCADE / schema DDL | **N/A —— 零 DDL**，故无需为缓存问题重启 |
| 前端 `tsc --noEmit` / Vite 200 | **N/A —— 零前端改动**（判定依据见 `fronttask.md` §1-2） |
| 后端端点存活（`/api/cpq/components` 期望 401） | ⏳ **合并后在主工作区 dev server 验**（worktree 不另起 server，`CLAUDE.md` worktree 共享约束） |
| E2E `quotation-flow.spec.ts` | ⏳ **合并后跑**，原因见 §7 |

---

## 7. 两项延后到合并后执行的验证（诚实说明，不是"已完成"）

`CLAUDE.md` 的工作流是**亲验 → 自动合并 → 用户闸门 B 真机验收**，且 dev server（8081/5174）
服务的是**主工作区已合并代码**，worktree 里的代码它看不到。因此下面两项在合并前**无法产生有效证据**，
强行在 worktree 里跑只会得到"测的是 master"的假绿：

| 项 | 为什么必须在合并后 | 合并后怎么做 |
|---|---|---|
| **AC-4 逐位 A/B** | 需要用**新代码**重渲一次核价单再与旧值比对；worktree 代码进不了 dev server | 已抓改动前基线（下方），合并后触发重渲 → 比对指纹 |
| **E2E `quotation-flow.spec.ts`** | 同上；且判据是「与 master 相同的失败集合」（干净 master 因夹具漂移恒 3 失败，`BL-0078`） | 合并后跑一次，与 master 已知失败集合对照 |

**AC-4 基线（改动前，`cpq_db_0724`，2026-08-14 抓取）**：

```sql
SELECT count(*) AS 行项数,
       count(*) FILTER (WHERE costing_card_values IS NOT NULL) AS 有核价值,
       md5(string_agg(md5(coalesce(costing_card_values::text,'')), '#' ORDER BY id::text)) AS 核价指纹,
       md5(string_agg(md5(coalesce(quote_card_values::text,'')),   '#' ORDER BY id::text)) AS 报价指纹
FROM quotation_line_item;
```

```
行项数 | 有核价值 | 核价指纹                          | 报价指纹
   77  |    62    | 587f0a619d356bf1da2b468aeb56d3a8 | 45e1b8647eea09bba1e75d7c2804b0d3
```

**AC-4 当前已有的（较弱但真实的）证据**：
1. D-1 只作用于**组件保存校验**、D-2 只作用于 **publish**，两者均不在渲染路径上（代码审查）；
2. D-3 是渲染路径上**唯一**的改动，且行为差异**只存在于** `recursive && kept>0 && missingParent==kept` 这一种情况；
3. 全库 18 个树组件的 `$view` **全部**输出 `parent_no`（18/18），即**现网无任何模板会命中该分支**；
4. 核价树渲染既有回归 13 项全绿。

---

## 8. AC-7 同类排查结果（"还有没有别的旧校验以『活表配置外溢到已发布模板』为前提"）

扫描方式：`grep -rn "FROM template_component tc" --include=*.java src/main/java` 全命中逐个判读。

| 命中位置 | 判定 | 说明 |
|---|---|---|
| `ComponentService.java:430` | ✅ **本次已修** | 即本任务 D-1 |
| `BomTreeRenderService.java:239` | ✅ 已正确 | task-0806 B18 已加 PUBLISHED/ARCHIVED → `PublishedTemplateReader` 分支，DRAFT 才读活表 |
| `ConfigureSnapshotService.java:903` | ✅ 已正确 | 同款分支（frozen 走快照、DRAFT 走活表），注释齐全 |
| `TemplateService.java:1131` / `:1362` | ✅ 设计如此 | `forceRealignSnapshots` admin 后门 —— **刻意**用活配置重写快照，带 confirm 预览 + `operation_log` 审计 + `LOG.warn`（task-0806 FR-7）。不是缺陷 |
| **`DriverBatchSafetyAuditor.java:100`** | 🔴 **真发现，本期不改** | 见下 |

### 🔴 发现：`DriverBatchSafetyAuditor.classifyTemplateDriverComponents` 读活表，与渲染器已分叉

该方法的 javadoc 自称：

> 查询与 `BomTreeRenderService#renderInternal` §②-pre 的 driver 组件清单**同款**
> （`template_component JOIN component`），**保证审计对象与实际渲染对象一致**。

但 `renderInternal` 在 task-0806 B18 之后**已经改成按状态分支**（PUBLISHED/ARCHIVED 读冻结快照），
而本方法仍是**无条件读活表**。因此对**已冻结模板**，「审计对象与实际渲染对象一致」这句已经**不成立**：
渲染器看的是快照里的 `data_driver_path`，审计器看的是活表的。若某组件冻结后改过 driver 路径，
批量安全级别就会按**错误的对象**分类。

- **性质**：与本次修复**同源**（task-0806 冻结改造的收尾遗漏），但**不同链路**（批量安全审计，非 tabType 护栏）
- **本期不改的理由**：`问题说明.md` §⑤「已知风险 3」定的口径 —— AC-7 只列清单不顺手改，避免范围失控
- **去向**：登记 `BL-0171`（P1）

---

## 9. 缺陷清单

**本次开发过程中未发现产品缺陷。** 一个曾被误认为缺陷、经查实为测试假象的项，记录如下：

| # | 现象 | 结论 |
|---|---|---|
| 1 | TC-09 断言「publish 被拦后快照回滚为 0 行」失败，实际为 2 行 | **测试假象，非产品缺陷**。`@TestTransaction` 让**测试方法**拥有事务，`publish()` 的 `@Transactional(REQUIRED)` 只是加入它，`assertThrows` 又把异常吞在测试内，外层事务此刻未结束 → 刚插的行对同一事务仍可见。生产环境：`TemplateResource.publish` **不带** `@Transactional`，`TemplateService.publish` 自己是事务边界，`BusinessException extends RuntimeException`（unchecked）→ JTA 自动回滚。**处置**：改断言为 `txManager.getStatus() == STATUS_MARKED_ROLLBACK`（这正是最终回滚的同一机制），并把上述推理写进测试注释，避免后来人再被误导 |

---

## 10. 回归结论

- 新增 27 项、既有回归 29 项，**全部通过**（1 项 skip 为既有夹具缺失，非本次引入）
- 两次还原实验均按预测变红，新用例**具备真实判别力**，不是空验证
- 前端零改动、零迁移、零 DDL、零接口结构变更
- **AC-4 逐位 A/B 与 E2E 延后到合并后执行**（§7 已说明为何合并前做不出有效证据，并已抓好基线）
- AC-7 查出 1 个同源遗漏（`DriverBatchSafetyAuditor`），按既定口径**只登记不顺手改** → `BL-0171`
