# test-report · repair-260829 异步物化事务上下文缺失

> 执行结果记录。测试方案见 `test.md`，AC 原文见 `问题说明.md ⑥`。
> 本报告持续更新（后端 B-1/B-2/B-4/B-6/B-7 仍在进行中），当前版本覆盖 **T-05/T-09/T-10/T-11 已执行 + T-04 基线已确认**，
> 其余用例（T-00/T-01~T-03/T-06/T-07/T-08/T-12/T-13/T-14）状态见文末汇总表。

## 1. 测试环境

- 分支：`fix/repair-260829-async-tx-context`（已同步 master 至 `bca9cf23`，含 `f907dfa1` AC-8 三元判据修正 + `bca9cf23` AC-4 基线表述修正）
- 命令：`cd cpq-backend && ./mvnw -Dtest=<Class> test`（**未在 worktree 里跑测试的坑已避开**，全程在 `.claude/worktrees/repair-260829-async-tx-context/cpq-backend/` 执行）
- DB：test profile → `10.177.152.12:5432/cpq_db`（未碰 dev 库 `cpq_db_0724`；未碰任何 pending 数据）
- 本轮执行时点：**B-4/B-5 守卫逻辑尚未落地**（`git status` 显示 backend 只新增了 `DiagTxContextResource.java`，未改 `CreateQuotationMaterializer`/`ConfigureSnapshotService`）。因此以下结果是**基线轮**：新用例应"平凡通过"（守卫不存在，自然不会误报），B-4/B-5 落地后必须**重跑**，见文末「待办」。

---

## 2. T-05 / T-09 / T-10 / T-11（AC-4 反向 / AC-8 三边界）

**文件**：`cpq-backend/src/test/java/com/cpq/basicdata/v6/service/CreateQuotationEmptyGuardBoundaryTest.java`

**⚠️ 层级声明（避免后来人误读）**：本文件验的是 **B-4/B-5 守卫的后置状态判断**（① 步跑完后查一次 `count(*)`，比对"明细行>0 且 driver组件>0 且 comp_data==0"三元判据），**不是①步端到端真实性的验证**。用直接注入 `materializer.materialize(r)` + **预插目标终态**到 `quotation_line_component_data`（不依赖①步真实驱动展开产出内容）。①步端到端真实性（真实 1845 行单、fire-and-forget、真实驱动展开）由 **T-01/T-02/T-03** 覆盖，那三条是主线亲验项。**T-05 绿了不代表①步没问题**，两层各管各的。

### 执行命令与原始输出

```
cd cpq-backend && ./mvnw -Dtest=CreateQuotationEmptyGuardBoundaryTest test -DfailIfNoTests=true
```

```
[T10] warnings=[卡片值物化失败: 模板尚未冻结：templateId=513f0d27-a03f-4371-bce7-6fcc82adb728, status=PUBLISHED。该模板的渲染配置冻结快照为空（过渡期正常状态），请联系管理员冻结该模板后再试。] severeCount=1
[T11] compData=3 nonEmpty=0
[T11] warnings=[] severeCount=0
[T09] warnings=[部分行卡片值未就绪或渲染失败，详情/核价管理可能显式提示] severeCount=0
[T05] compData=3 nonEmpty=3
[T05] warnings=[] severeCount=0
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.744 s -- in com.cpq.basicdata.v6.service.CreateQuotationEmptyGuardBoundaryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 逐条结果

| 用例 | 前置数据非空断言 | 终态断言 | 不误报断言 | 结论 |
|---|---|---|---|---|
| **T-05**（AC-4 反向，守卫状态验证非①步端到端） | 预插后立即查库：`comp_data=3, nonEmpty=3`（PASS） | ①步（真实内部尝试，见下节）跑完后再查一次：`compData=3, nonEmpty=3` —— **预插行原样存活**（PASS，见下方"存活性"说明） | `warnings=[]`，`severeCount=0`（PASS） | **PASS**（基线轮，平凡绿——守卫不存在） |
| **T-09**（AC-8-①，明细行 0 条） | `countLineItems=0`（PASS） | `comp_data=0`（PASS，合法态） | `warnings` 不含"组件数据0行"类文案，`severeCount=0`（PASS） | **PASS** |
| **T-10**（AC-8-②，driver 组件 0 个） | `lineItemCount=3, driverComponentCount=0`；DB 层复核 `template_component` 表确为 0 行（PASS） | `comp_data=0`（PASS，且**原因正确**：0 组件×3行=0，不是被异常清空——见下节） | `warnings` 不含目标文案（PASS，但 `severeCount=1` 系其它既有 warning，见下节说明） | **PASS**（但需 B-4 落地后复核，见「待办」） |
| **T-11**（AC-8-③，视图合法返 0 行） | 预插后立即查库：`comp_data=3, nonEmpty=0`（PASS，**行存在**，区别于"无记录"） | ①步跑完后再查一次：`compData=3, nonEmpty=0` —— **预插行原样存活**（PASS） | `warnings=[]`，`severeCount=0`（PASS） | **PASS**（基线轮，平凡绿） |

**"预插行原样存活"是本轮实测验证的，不是假设**：T-05/T-11 都在预插后立即查一次 `count(*)`，再在 `materializer.materialize(r)` 跑完后查第二次，两次比对相等才判 PASS——这是防止"①步内部实际把预插行删了又插失败，最终巧合等于预插值"这种假绿的必要动作。

### T-10 的 `severeCount=1` 说明（不是缺陷，是既有的、不相关的 warning）

T-10 的 `warnings` 里那条"卡片值物化失败: 模板尚未冻结…"和对应的 1 条 ERROR 日志，**来自本轮基线环境的一个既有 gotcha**（见下节"TemplateNotFrozenException 踩坑记录"），跟 B-4/B-5 完全无关——即便 driver 组件数正确地为 0，`ensureCardValuesDetailed` 仍会在 `PublishedTemplateReader.allTabsOf` 那一步因为模板"未冻结"而抛异常，被 `materialize()` 顶层 catch 兜成这条 warning。

**T-10 本身的判断没有被这条 warning 污染**——测试用的是精确关键词匹配（"组件数据.*0行"类正则），这条不相关 warning 不含这些关键词，所以 `assertNoFalsePositive` 依然通过。但这**掩盖了一个后续必须再验的点**：见文末「待办」第 1 条。

---

## 3. 踩坑记录：`TemplateNotFrozenException`（既有测试基础设施的心照不宣规避，本次不修）

**现象**：用纯 `template.components_snapshot`（JSONB）搭建的 fixture 模板（`status='PUBLISHED'`，但没有经过真正的"冻结"动作），只要有 ≥1 行 `quotation_line_item` 且模板挂了 ≥1 个 driver 组件，直接调用 `materializer.materialize(r)` 让①步自己去真实驱动展开产出 `quotation_line_component_data`，会**稳定**（非偶发，连跑两次不同随机 UUID 复现一致）触发：

```
ERROR [CreateQuotationMaterializer] 后置物化失败 quotation=...: com.cpq.template.exception.TemplateNotFrozenException:
模板尚未冻结：templateId=..., status=PUBLISHED。该模板的渲染配置冻结快照为空（过渡期正常状态），请联系管理员冻结该模板后再试。
	at com.cpq.template.service.PublishedTemplateReader.verifyConsistentWithJsonb(...)
	at com.cpq.template.service.PublishedTemplateReader.allTabsOf(...)
	at com.cpq.quotation.service.CardSnapshotService.ensureCardValuesDetailed(...)
```

**根因定位**：①步自身（`ConfigureSnapshotService` 的 `add-snapshot`）遇到这个异常时**有自己的内部 catch**，会 warn 一次"快照整体失败(已降级)"并继续（不影响②）；但③步 `CardSnapshotService.ensureCardValuesDetailed` 调用 `PublishedTemplateReader.allTabsOf` 时**同一个异常没有被内部吞掉**，直接向上传播，被 `materializer.materialize()` 的顶层 catch 接住，产生一条 ERROR 日志 + 一条 warning。

**既有测试是怎么规避的**：`CreateQuotationMaterializeWarningsPropagationTest.java` / `EnsureCardValuesPartialBatchRecoveryTest.java` 的 fixture 从不依赖①步真实驱动展开去产出 `quotation_line_component_data`——它们的 `buildFixture()` 直接手工 `INSERT INTO quotation_line_component_data (...) VALUES (..., '[]', '[]')` 把终态数据预先插好，注释只写"经其验证过对 materialize() 的①②③步均安全"，**没写为什么**。本次踩坑后确认：这是因为一旦让①步自己产出数据，就会撞上未冻结模板这个坑；预插终态从根本上绕开了①步的真实驱动展开路径。

**本次的应对**：`CreateQuotationEmptyGuardBoundaryTest.java` 沿用同一个"预插终态"规避手法（详见第 2 节）。**不修这个坑**——不在本次返修范围内，按主线要求只记录，供后来写这一带测试的人参考，避免重复踩坑排查一次。

---

## 4. T-04 基线确认（AC-4）—— 不新造基线，理由如下

**背景**：`test.md` 原设计要求"零改动基线上 warnings 为空、无 ERROR，本条在基线上必然失败"作为 T-04 的证伪基础。执行过程中发现这条判据本身站不住脚，已推动主线修正 AC-4（`commit bca9cf23`）。以下是完整推理过程（应主线要求原样留痕）：

### 4.1 两条尝试路径都走不通

**路径一：用现有守卫方法做基线对照。** 不可行——B-4 把守卫改成"独立可测方法"之后，**这个方法在基线上根本不存在**。调用不存在的方法不叫"确认它保持沉默"，是无意义的空对照。

**路径二：用真实 fixture（未冻结模板）在基线上跑一次 `materializer.materialize(r)`，构造"明细>0 且 driver>0 且 comp_data==0"的终态，检查 `warnings`/ERROR。** 这正是第 3 节踩到的 `TemplateNotFrozenException`——实测发现这个场景在基线上**本来就不是沉默的**：③步会抛异常，被顶层 catch 兜成一条**已存在、与 B-4 无关**的 warning（"卡片值物化失败: 模板尚未冻结..."）+ 1 条 ERROR 日志。

拿路径二的结果去证明"基线沉默"，得到的是一个**看似成立、实际文不对题**的绿——这个场景在基线上从来就不沉默，只是不沉默的原因跟 B-4 要报的东西（"组件数据0行"）不是一回事。**两条路都走不通，说明这条判据从"守卫改成独立方法"那一刻起就已经不成立了，不是 fixture 没选对。**

### 4.2 正确的区分度：四条用例互为对照

AC-4 的有效性不需要另造基线对照，它由 **T-04（报警）vs T-09/T-10/T-11（不报警）互为对照**保证：
- 守卫若写成**恒报警** → T-09/T-10/T-11 三条全红（因为它们的终态也是 `comp_data==0`，只是原因合法）
- 守卫若写成**恒不报警** → T-04 红（真正该报的场景被漏掉）

这本身就是最强的证伪结构，不需要额外基线。而**"修复是否真的生效"这个基线证伪职责，本就属于 AC-3（还原实验，主线亲验）**，从设计上就不该塞进 AC-4。

### 4.3 采纳方案 A：引用勘察期已坐实的真实生产证据

真实缺陷的"基线沉默"症状形状——**四步埋点全绿、零 ERROR、零相关 warning**——已经由勘察期的 `证据/E4-生产日志与还原实验.txt` 逐字坐实，且是**三张真实生产报价单**（非合成 fixture），比我能在测试里合成的任何场景都更有说服力：

```
2026-08-29 00:58:28.895 INFO  [CreateQuotationMaterializer] (executor-thread-2)
  [create-quotation-timing] quotation=cc7fa07d-db15-47ae-941c-5aa73fc730d2
  ①snapshotQuotation=447ms ②ensureStructure=282ms ③ensureCardValues=2767ms ④ensureExcelValues=3193ms 总计=6689ms

00:58:21.559 WARN [ConfigureSnapshotService] (executor-thread-2) [add-snapshot] quotation=cc7fa07d…
  取 components_snapshot 失败: Cannot use the EntityManager/Session because neither a transaction
  nor a CDI request context is active. …
00:58:21.647 WARN [ConfigureSnapshotService] (executor-thread-2) [add-snapshot] quotation=cc7fa07d…
  快照整体失败(已降级): <同上>
```

同一批次另两张单（`d50a65a6` / `10ca17fb`）逐字同构，见 `证据/E4` 全文。三张单埋点四步均"全绿"（无异常抛出到顶层），日志里**只有 WARN，零 ERROR**，且没有任何一条 warning 提到"组件数据0行"（因为 B-4 当时还不存在）——这正是 T-04 要证的"基线上是沉默的，真正该报的场景在当时溜过去了"。

**结论**：T-04 不需要另建基线用例，`证据/E4` 已经是比任何合成 fixture 都更强的第一手证据。方案 B（现在再用真实小规模 fire-and-forget 独立复现一次）不做——那和 T-03（主线亲验，真实 1845 行单）技术手法重叠，属于重复劳动，且不该由 test-engineer 越界代做主线的还原实验。

---

## 5. 待办（本报告后续版本要补齐的项）

1. 🔴 **B-4/B-5 落地后，`CreateQuotationEmptyGuardBoundaryTest` 必须重跑**，重点复核 T-10：`severeCount` 应变为 **0**（当前基线轮的 `severeCount=1` 来自不相关的 `TemplateNotFrozenException`）。如果 B-4 落地后 T-10 的 `severeCount` 仍为 1，需要先分清是"新守卫误报"还是"那条既有不相关 warning 还在"，不能直接归因于 B-4 有 bug。
2. T-04 的正式用例（调用 B-4 抽出的独立守卫方法）待方法签名落地后补齐——本报告第 4 节只解决了"基线怎么定"，正向断言（B-4 落地后守卫确实报警）还没写。
3. T-00：backend B-1 第二轮（真实 materialize 写数据，`0203`/`0199` 各配一个 executor）结果未出，暂缓。
4. T-06：待 B-6 提供请求线程基线耗时 `BASE`。
5. T-07/T-08：待 B-2/B-4 落地后，用干净新建的小规模测试锚点单执行（不碰现网单、不碰 24 张 pending 批次单）。
6. T-13：待 T-01~T-03 确认修复生效后，执行前先报 19 张单的影响面数字给主线，分批执行、每批核对行数再继续。

## 6. AC 覆盖状态汇总（本报告当前版本）

| AC | 用例 | 状态 |
|---|---|---|
| AC-1/AC-2/AC-3 | T-01/T-02/T-03 | 未执行（主线亲验项） |
| **AC-4** | **T-04**（基线小节已完成，正向用例待 B-4 方法签名） | **部分完成** |
| AC-5 | T-06 | 阻塞（待 B-6 基线值） |
| AC-6 | T-07 | 未执行（待 B-2/B-4 落地） |
| AC-7 | T-08 | 未执行（待 B-2/B-4 落地） |
| **AC-8** | **T-09/T-10/T-11** | **完成**（基线轮 4/4 PASS，B-4/B-5 落地后需重跑，见待办①） |
| AC-9 | T-12 | 未执行（主线亲验项） |
| AC-10 | T-13 | 未执行（待修复确认生效） |
| AC-11 | T-14 | 主线职责 |
| —（B-1 前置） | T-00 | 阻塞（backend B-1 第二轮进行中） |
