# backtask — task-0725 修复报价单页签无法显示数据

> 架构评估（2026-07-25）：采纳 **方案 D'（pre-gated 窄作用域 `QuotePendingScope`）**。
> 否决原提的 A（单独实施是 no-op）与 B（≈20 个签名 + 仍需改 `render` 重载 + 判错成本最高）。
> 完整对比与 8 项需求方决策见 `需求说明.md §4.3 / §6 / §8`。

## 前置纪律（每个 Task 都适用）

- **必须**先用 `superpowers:using-git-worktrees` 建隔离 worktree 特性分支，不在主工作区 / master 直改。
- 默认走 `superpowers:subagent-driven-development`，Task 间两阶段评审（先 spec 合规、再代码质量）。
- 编码前先读 `docs/方案制定前必读.md`；本改动命中三大核心模块「报价单渲染」，同时读 `docs/三大核心模块基线.md`。
- dev server 全会话共享（后端 8081 / 前端 5174 已在跑），**不要在 worktree 里另起**，直接复用做自检。
- curl 本机一律加 `--noproxy '*'`；`/q/health` 返 404 **不是**健康探针，判后端健康看业务端点返 **401**。
- 后端改完 `touch` 一个 java 文件强制 Quarkus 重启 → 等 5-7s → curl 验证。
- 数据库固定 **`cpq_db`**（jh profile，`application-jh.properties:24`；默认 profile 里的 `cpq_db_0724` 与本任务无关）。
- 提交只 `git add` 本次明确改动的文件，**严禁 `git add -A`**。
- 🔴 **多 agent 并发共用本 worktree 时，禁止 `git stash` / `git checkout -- <file>` / `git reset --hard` / `git clean`** —— 会连带抹掉其他 agent 正在进行的工作（2026-07-25 已发生一次险情：T2 用 `git stash -u` 验证预置失败，期间工作树看起来像被清空）。要看"改动前的行为"用 `git show HEAD:<路径>` 读原文或 `git diff` 看差异，**不要动工作区**；确实需要 before/after 跑测试对比时，用独立 clone。
- 本期**无 Flyway 迁移**（`pending_quotation_id` / `pending_supersedes` 列已由 V349 建好）。

## Task 依赖图

```
T0 (E2E 基线实测, 开工前不改代码)
     │
T1 (根因2 · 注释屏蔽 4 站点) ──┬──→ T3 (报价侧 4 个 set 点) ──→ T4 (AC-17 门禁) ──→ T5 (端到端验收)
T2 (QuotePendingScope + 两层缓存) ┘         ↑
F1/F2 (前端 usage 标记, 见 fronttask.md) ───┘
```

- **T0 必须最先做**（开工前，不改任何代码）—— 定 E2E 验收口径。
- **T1 与 T2 可并行**。交叠：都碰 `BomTreeRenderService.java`，但改动区相距约 110 行不重叠（T1 = `:324-325` + `:388-427`；T2 = `:436-441`），git 可自动合并。
- **T1 → T3 有边**（原图漏了）：`cp_view`（产品页签）**只靠根因 2 就会失败**，与 T2 无关。T1 未合入时 T3 的「产品页签有数据」必然 FAIL，实施者会误判 T3 没做对。
- **F1/F2 → T3-P3 → T5 有边**（原图漏了前端节点）：T3-P3 要读 `task.usage`，T5 的入口验收要看 F12 请求体。

---

## T0 — E2E 基线实测（开工前，不改代码）

**依赖**：无。**必须在任何编码之前完成。**

需求方决策（问题 7）：E2E 标准**以基线实测为准**，不预设「全部 passed」。

```powershell
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```

把结果（失败个数 + 失败签名原文 + 是否 skipped）写进 `test.md` §9。然后：

- 基线**全绿** → 验收要求全绿
- 基线**带已知无关失败** → 验收改为「**相对基线无新增失败**」，失败签名须与基线**逐字一致**

> 背景：`docs/RECORD.md` 记载 2026-07-23 时 `quotation-flow.spec.ts` 在本分支与干净基线上**各跑一次均 3 failed**，同一夹具签名（「请先填写产品分类和报价模板」）；`composite-product-flow.spec.ts` 曾是 `1 skipped`（task-0712 遗留 `test.skip(true,...)`）。

无论哪种，以下**不打折**：`'加载中' final count = 0`、全部 8 Tab `'加载中'=0`、PR 附 qf-19 + qf-21~28 共 9 张截图。
「补 E2E 夹具缺口」登记为独立待办，不在本期范围。

---

## T1 — SQL 文本屏蔽工具抽取 + **4 个站点**加固（根因 2）

**依赖**：无（可最先开工）　**可与 T2 并行**

### 改动清单

1. **新建** `com.cpq.datasource.sqlview.SqlTextMask`
   - `public static String mask(String sql)`
   - 实现整体搬 `QuotePendingRewriter.java:107-136`：字符串字面量 / `--` 行注释 / `/* */` 块注释 → **等长空白**，换行符原样保留（保证行号与偏移量不变，编辑仍作用于原文）
2. `QuotePendingRewriter.java:107` 的 `mask` 改为委派 `SqlTextMask`（**保留现有单测不动**）
3. `SqlViewExecutor.java:602 rewriteNamedParams`：先 `mask` 再用 `NAMED_PARAM`（`:95`）在 masked 文本上定位，**替换作用于原文**
4. `SqlViewExecutor.java:627 extractNamedParams`：同款
5. **`SqlViewValidator.java`：4 处，不是 1 处** ⚠️ 评审补充

   | 位置 | 性质 | 后果 |
   |------|------|------|
   | `:66` | 同款正则 | 注释里写 `:xxx` → dry-run 与执行不一致 |
   | `:122` | **裸 `sqlTemplate` 检查 `:hfPartNo`** | 注释里写它会**硬失败保存** |
   | `:129` | **裸检查 `:__sk`** | 同上 |
   | `:135` | **裸检查 `:__vf`** | 同上 |
   | `:204` | 字面量替换 | 无害，不必改 |
   | `:209-214 stripCommentsAndWhitespace` | **第三套注释处理实现** | 评估是否一并收口到 `SqlTextMask` |

6. `BomTreeRenderService.java:324-325 TREE_PARAM`：同款加固（4 token：`:production_part_nos` / `:__vfPart` / `:__vfVer` / `:pq`）

   ⚠️ **必须保留的顺序不变量**：屏蔽/匹配必须作用在 `withPending`（`:388`，**改写之后**）而不是 `expanded`。因为 T2 生效后改写器会**生成**大量合法的 `:pq` token（`QuotePendingRewriter:229`/`:235`/`:236`），若把匹配挪到 `expanded` 上，`:pq` 就绑不上了。**当前代码本来是对的，别"顺手优化"。**

### 为什么站点 5 不可漏

`docs/方案制定前必读.md` 的既有血泪记录：同类正则误识坑「修复见 V236（执行）+ **同日同源修复 SqlViewValidator（校验/dry-run 通路漏修，双链路必须同步）**」。**这是同一个坑第二次。** 只修执行链路会造成「dry-run 报错但实际能跑」（或反过来「保存被拒但实际能跑」）的新型不一致。

`mask()` 原为 package-private，站点 5 在 `com.cpq.component.service`、站点 6 在 `com.cpq.quotation.service`，跨包不可见 → 这是必须抽 `SqlTextMask` 的原因。

### 验收点

1. `cp_view`（注释含 `:customerCode`）**保持注释原样**可正常执行；日志不再出现 `executeAllRows failed path=$cp_view` / `column index is out of range`
2. `bom_view`（注释含 `:total_material_no` ×1 + 正文 ×2）Java 侧绑定数 == pgjdbc 认到的占位符数
3. 单测：`--` 注释内 / 单引号字面量内 / 块注释内 / `::uuid` cast 四类 token 均不被误识；**正文内同名 token 仍正常替换且绑定顺序正确**
4. 单测：注释里写 `:hfPartNo` / `:__sk` / `:__vf` **不再导致保存被拒**（站点 5 的 `:122`/`:129`/`:135`）
5. dry-run 通路与执行通路对同一 SQL 的占位符清单**完全一致**
6. 屏蔽后行号/偏移量不变（用含多行注释的 SQL 断言替换位置正确）

---

## T2 — `QuotePendingScope` + 传播接缝 + **两层**缓存维度（根因 1 核心）

**依赖**：无　**可与 T1 并行**

### 新建 `com.cpq.datasource.sqlview.QuotePendingScope`

```java
/**
 * 报价侧 pending 可见域（task-0725 根因 1）。
 *
 * <p>语义：作用域「打开」⟺ 当前线程正在渲染【报价侧】【非冻结】报价单 ⟹ 允许 pending 感知改写。
 *
 * <p><b>AC-17 保障机制 = 核价侧任何入口一律不调用 open()</b>（靠"不调用"而非运行时判断，
 * 可 grep 穷举、并由 T4 的白名单单测机器化保证）。禁止在
 * CardSnapshotService#precomputeCostingDriverUnion(:767) / #buildCostingCardValues(:1152) /
 * #snapshotNewLinesCardValues(:483) / CostingVersionService(:354) /
 * 核价侧 render 调用点（CardSnapshotService:501、:841、CostingVersionService:209）链路上调用 open()。
 *
 * <p><b>AC-10 保障机制 = open() 内建冻结判定</b>：冻结态 ⟹ 存 null ⟹ 下游 quotationId
 * 保持 null，与修复前逐位相同。
 *
 * <p><b>既存的另一条 pending 通路（勿混淆）</b>：FormulaEvaluateResource:119-120 已把真实
 * quotationId + quotationStatus 塞进 SqlViewRuntimeContext（前端 useLinkedExcelRows.ts:275 在发），
 * 即联动 Excel 公式求值路径靠运行时 status 判定、不经本类。本期不统一两者语义（见需求说明 §8-13）。
 */
public final class QuotePendingScope {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private QuotePendingScope() {}

    /** quotationId==null 或 status ∈ {SUBMITTED,APPROVED,PUBLISHED} → 存 null（等价不打开）。返回旧值。 */
    public static UUID open(UUID quotationId, String quotationStatus) { … }

    /** 恢复 open 返回的旧值。调用方 finally 必须调用。 */
    public static void restore(UUID prev) { … }

    /** 当前 pending 归属；null = 不改写。<b>已内建冻结判定，消费方不得再判 frozen。</b> */
    public static UUID pendingOwner() { return CURRENT.get(); }

    /** 缓存维度标签：开 → ":pq<qid>"；关 → ""（保核价 key 逐字不变）。 */
    public static String cacheTag() { … }
}
```

⚠️ **不要提供 public `clear()`** —— 嵌套场景下谁误用 `clear()` 代替 `restore(prev)` 都会静默丢掉外层作用域。只留给测试（或干脆不留）。

### 改动清单

1. **`ComponentDriverService.java:341-342` 与 `:642-643`** 两处同款：

```java
UUID _pq = QuotePendingScope.pendingOwner();
// ⚠️ 第 4 参 quotationStatus 恒传 null，不得传真实 status：
//    pendingOwner() 已保证「非 null ⟹ 非冻结」，故 isQuotationFrozen()=false，
//    ① SqlViewExecutor:555 门槛成立（本次修复目标）
//    ② ComponentSqlViewService:379「冻结读 quotation snapshot」分支在本链路保持休眠
//       （传真实 status 会点亮它 → 已提交单的视图 SQL 来源从 component_sql_view
//        静默切到 quotation_component_sql_snapshot，属超范围的静默行为变更）
Snapshot _prev = SqlViewRuntimeContext.setNested(componentId, null, _pq, null);
```

> 注：`:379` 那条分支并非「全工程恒不可达」—— `FormulaEvaluateResource:119-120` 今天就会进（因快照表无行而 fallthrough，表现良性）。准确说法是「**在 driver 展开链路上**不可达」。

2. **`ComponentDriverService.java:365-366`** cacheKey 追加 `QuotePendingScope.cacheTag()`

   现有 9 个维度（componentId / customerId / partNo / partVersion / tmnHash / overrideTag / lineItemTag / childTag / **qidTag**）缺「pending 是否可见」。
   ⚠️ **不能复用 `qidTag`** —— 报价侧与核价侧 `_qid` 是**同一个值**。必须独立 token 维度。关闭态返 `""` → 核价 key 逐字不变。
   `expandMulti:639` / `expandForPartSet:737` 不进 `expandCache`（`:633` 注释），无需处理。

3. 🔴 **`DataLoader` 是第二层缓存，必须一并补**（评审 BLOCKER）

   `com.cpq.formula.dataloader.DataLoader` 是 `@RequestScoped`（`:45-46`），实例级 `resultCache`（`:56`），**只在 `@PreDestroy` 清**（`:379-382`，`clearCache()` 注释明写「仅供测试」，无生产调用方），而它就在 driver 路径上（`ComponentDriverService:498`/`:581`/`:678`）。其 key（`:189-191`）=

   ```
   normalizedPath :: partNo :: customerId :: viewLineItemId :: ownerTag :: quotationId
   ```

   **无 pending 维度**。报价侧与核价侧这 6 维在同一报价单下**逐字相同**（templateId 两侧皆 null，因 `setNested` 第 2 参恒 null；quotationId 同一张单），而 `CreateQuotationMaterializer:41/43` 在**同一请求线程**先跑报价再跑核价 → 同一个 DataLoader 实例 → **核价侧直接拿到报价侧改写后、带 `__v6_id`、含 pending 行的结果**。

   → **`cacheTag()` 必须同时进三个进缓存的 `loadByPath` 重载的 key：`:90`（⚠️ 该重载 key **只有 `normalizedPath` 一项**，粒度最粗，重点评估）/ `:104` / `:189`。**

4. **`BomTreeRenderService.java:436-441 resolvePendingOwner()`**：改读 `QuotePendingScope.pendingOwner()`（约 3 行）

   原读 `SqlViewRuntimeContext.get()`，而 `ConfigureSnapshotService:350` 调 `render()` 那一刻**从未有任何 `setNested` 发生过**（driver 的 setNested 在更内层 `:216 expandUncached`）→ 恒返 null。这是报价树侧的独立断点。

5. **不动**：`SqlViewExecutor.java:553 applyPendingRewrite` / `:567 injectPendingParam` / 整个 `SqlViewRuntimeContext`

### 验收点

1. ~~`SqlViewExecutorPendingHookTest` 三个既有用例不改一行仍全绿~~ ❌ **该验收点作废**（2026-07-25 T1 实施期发现并实测确认）

   该测试 `:34` 硬编码 `COMPONENT_ID = 4d8874c8-5022-4ba0-ba08-17009f46ecae`，而**当前 `cpq_db` 里不存在该组件**（库中只有 6 个组件：BOM / 产品 / 加工费 / 外购件成本 / 材料成本 / 罗克韦尔报价小计1，ID 全不匹配）→ 3 个用例**直接 error**，`git stash` 验证改动前同样 error，属**预置失败**。
   要求它「全绿」不可达。**改为**：确认这 3 个 error 的签名与改动前**逐字一致**（即未新增回归），并在 PR 里如实标注为预置失败。

   > 🔴 **这是本项目同一个病的第三例**：测试硬编码库内数据 → 换库即静默失效。前两例 = 4 个等价性测试的锚单（`8f0c37a4`/`a8f17a74` 不在库）、BL-0021 的 golden 常量过期。**真正的护栏只能是 T4 的 SQL 文本断言**（不依赖库里有什么数据）。建议登记 BACKLOG（P1/M）：审计全部测试对库内固定数据的硬编码依赖，改为夹具自建或环境无关断言。
2. `SqlViewIsolationBoundaryTest` 全绿（`:144-152` / `:176-180` 的语义断言未受影响）
3. 新单测：scope 开 + `DRAFT` → `pendingOwner()` 非 null；开 + `SUBMITTED`/`APPROVED`/`PUBLISHED` → **null**；未开 → null；嵌套 open/restore 正确还原；**异常路径下 finally 仍还原**（无 ThreadLocal 泄漏）
4. 新单测：`ComponentDriverService.cacheKey` 在 scope 开/关下**必须不同**；关闭态与改动前**逐字相同**
5. 新单测：`DataLoader` 三个重载的 key 在 scope 开/关下**必须不同**；关闭态逐字不变
6. `ComponentDriverServiceCacheKeyTest` 同步更新
8. **预置失败基线（T1/T2 实测 + 技术总监独立核实，共 3 组 12 项，不得当本任务回归）**

   | 组 | 测试类 | 结果 | 根因（均已核实为预置） |
   |---|--------|------|---------------------|
   | 1 | `DataSourceResourceTest` | **5 failure** | `Expected status code <200> but was <401>`，认证 fixture 问题 |
   | 2 | `SqlViewExecutorPendingHookTest` | **3 error** | `本组件 SQL 视图未找到：$zh_view（componentId=4d8874c8-5022-4ba0-ba08-17009f46ecae）` —— 该组件**不在当前 `cpq_db`**（库中只有 6 个组件） |
   | 3 | `com.cpq.formula.DataLoaderTest` | **4 error** | `NPE: ...SqlViewExecutor.isSqlViewPath(...) because "this.sqlViewExecutor" is null` @ `DataLoader.java:89`。**技术总监已独立核实**：HEAD 版 `DataLoader:89` 本就有该调用，而 HEAD 版 `DataLoaderTest:49-52` 只注入 `pathParser`/`sqlCompiler`/`dataSource`、**从不注入 `sqlViewExecutor`** → 确属预置 |

   合计 **5 failure + 7 error = 12 项**。后续每个 Task 完成后这 3 组签名须**逐字不变**；出现第 4 组、或某组数量/签名变化，才判本任务回归。

   ⚠️ 注意区分 `com.cpq.formula.DataLoaderTest`（预置失败 4 error）与 T2 新建的 `com.cpq.formula.dataloader.DataLoaderScopedCacheKeyTest`（4 passed）—— **不同包不同类**，别混淆。

   > 🔴 **这 3 组 + E2E 的 3 failed + 4 个等价性测试的 skip + BL-0021 的 golden 过期，是同一个系统性问题的 6 例**：测试硬编码库内数据 / 依赖特定环境 → 换库即失效或静默跳过。**这也正是本任务 AC-17 门禁必须改成「核对实际发出的 SQL」（环境无关）的根本原因。** 已列入建议登记 BACKLOG（P1/M）做全量审计。

7. `SUBMITTED` 专项单测（AC-10 关键边界）：此刻 B5 尚未升版，pending 行仍带 `pending_quotation_id=本单`。**不可用「反正 B5 已升版所以改写等价于不改写」的推理省掉冻结判定。**

### 并发前提（已核实，可采信）

`ConfigureSnapshotService` / `CardSnapshotService` / `BomTreeRenderService` / `ComponentResource` / `ComponentDriverService` 五个文件里 `parallelStream|supplyAsync|ManagedExecutor|ExecutorService|Uni<` **零命中**；`ComponentResource` 的 batch-expand 是纯顺序 for 循环；`DataLoader` 签名虽是 `CompletableFuture` 但实现全是 `completedFuture(...)`（`:93`/`:214`/`:283`/`:355`），无线程切换；物化在请求线程内同步执行（`BasicDataImportV6Resource:129`）。→ ThreadLocal + try/finally 在这些路径上是正确的。

### 供实现者确认「同组件挂两模板」是否现实可触发（可选，结论不依赖它）

```sql
SELECT c.code, c.name, c.tab_type
FROM quotation q
JOIN template t ON t.id IN (q.customer_template_id, q.costing_card_template_id)
JOIN template_component tc ON tc.template_id = t.id
JOIN component c ON c.id = tc.component_id
WHERE c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''
GROUP BY c.code, c.name, c.tab_type
HAVING bool_or(t.id = q.customer_template_id) AND bool_or(t.id = q.costing_card_template_id);
```

返非空 = 现实可触发；返空 = 仍须补维度（防御未来配置）。

---

## T3 — 报价侧 **4 个**权威 set 点接线

**依赖**：T1（产品页签）、T2（作用域类）、F1/F2（前端 usage）

### 先厘清：不是所有入口都要开

`CardSnapshotService.java:43` 类注释原文：**报价侧 `buildCardValues` 复用 `ConfigureSnapshotService` 已写入的 `snapshot_rows`（不双写 expand）**。所以只有真正做 driver 展开的地方才开。

### P1 — `ConfigureSnapshotService.snapshotLines`（主战场）

在 `:272 QuotationIdContext.set` 的**同一 try/finally**（`:273-536`）内 `open` / `restore`（status 从 quotation 取）。

覆盖：**建单**（`CreateQuotationMaterializer:41`）、**加产品**（`ConfigureProductResource:63`/`:95`）、**saveDraft**（`QuotationResource:132`）、**从基础刷新**（`ConfigureProductResource:95`）、**报价树**（`:350-351 render(…,"QUOTE")`）。
`:403 expand` 与 `:592 expandMulti`（经 `:316 precomputeQuoteDriverBuckets`）均在此作用域内。**报价树同时修好。**

### P2 — `CardSnapshotService` 报价卡刷新 / dry-run

- `refreshQuoteCardValues:2106`（包住 `:2124 expandFlatDriverBaseRows`）
- `dryRunTokenRows`（包住 `:2671`）

覆盖：**「刷新基础数据」按钮**（`QuotationResource:154` → `refreshDraftQuoteCards:2172`）、**公式 dry-run 预览**。

⚠️ **禁止下沉到 `expandFlatDriverBaseRows:1911` 内部** —— 该方法被核价侧 `:1228 buildCostingCardValues` 共用，下沉即污染核价。

### P3 — `ComponentResource` 三处 + **bucketKey 补侧别维度**

`:261`（phase1）/ `:367`（bucket-merge）/ `:407`（runSingleTask），按 `task.usage` 决定是否 open。

🔴 **同时必须改 `:318-331` 的 bucketKey，把侧别纳入**（评审 HIGH）。现有 bucketKey = `componentId | customerId | partVersion | dp | fieldsTag | q=quotationId [| li=lineItemId]`，无侧别维度；`canMerge`（`:342-343`）成立时整桶只跑一次、作用域只能按 `pivot`（`:336`）开一次 → 桶内混着两侧时必有一侧错。

**需求方决策（问题 4）：报价/核价永不合并**，混合批次因此可正确工作，`api.md §2.3` 的混合示例保持有效。

协议：`BatchExpandDriverRequest.Task`（`:18-78`）新增可选 `usage`（`"QUOTE"` / `"COSTING"`），**缺省与非法值一律按 `COSTING`（不开）**，老前端零行为变化。端点是 `POST /api/cpq/components/batch-expand`（`:191`）。

### P4 — 报价侧 Excel 值 ⭐ 新增（需求方决策，问题 5）

`CardSnapshotService.ensureExcelValues:649`（其 `:666` 已 set `QuotationIdContext`）**须纳入作用域**。

理由：报价单有两处显示数据（产品卡页签 / Excel 视图值与导出）。若只开页签，会出现**同一张 DRAFT 单页面有数据、导出空白** —— 销售在草稿阶段导出自查是自然动作，拿到空表会直接当 bug 报。需求方已决策：**页面与导出口径必须一致**。

⚠️ 与 P2 同款注意：只在报价侧 Excel 路径开，核价 Excel（`:1228` 下游）不得开。开工前先确认 `ensureExcelValues` 的报价/核价分支是否共用同一方法体；若共用，须在方法内按侧别分支而**不是**在方法外整体包裹。

### 验收点（9 条路径，漏一条即该场景仍失效）

| # | 入口 | 覆盖 | 证据要求 |
|---|------|------|---------|
| 1 | 建单（Excel 导入） | P1 | 页签有数据 |
| 2 | 加产品（选配） | P1 | 新增行页签有数据 |
| 3 | saveDraft | P1 | ⚠️ saveDraft 走**增量**，对已有非 null 空数组是 no-op（见需求说明 §6）；此项验的是「新行能出数」 |
| 4 | **从基础刷新** | P1 | **存量空快照的重算入口**，必须验 |
| 5 | 报价树渲染 | P1 | 6 节点树 |
| 6 | 「刷新基础数据」按钮 | P2 | 刷新后仍有数据 |
| 7 | 公式 dry-run | P2 | token 行非空 |
| 8 | 前端实时 batch-expand | P3 | F12 请求体报价侧带 `"usage":"QUOTE"` |
| 9 | **报价 Excel 值 / 导出** | P4 | 与页签数据一致 |

### 明确不开作用域（反向清单）

`ensureCardValues`（报价侧只读 `snapshot_rows`）、`ConfigureProductResource:77 snapshotLineValues`（同理）、`ComponentResource:156 POST /components/{id}/expand-driver`（配置期单 task 预览，无 quotationId）、详情页读取（读已持久化卡片值；其 `ReadonlyProductCard:283-285` 的 live 兜底走 P3 协议）、`precomputeCostingDriverUnion:767`、`buildCostingCardValues:1152`、核价树调用点（`CardSnapshotService:501`/`:841`、`CostingVersionService:209`）、`CostingVersionService:354`、`FormulaEvaluateResource:119`（Excel 公式路径，本期不碰）。

---

## T4 — AC-17 门禁（**已改为核对实际发出的 SQL**）

**依赖**：T2、T3　**只加测试与审查，不改生产代码**

需求方决策（问题 2）：原「4 个等价性测试全绿」**作废**。它们把锚单硬编码为 `8f0c37a4-8186-4f5e-a9ca-358bd2d9662d` / `a8f17a74-5a32-40fc-9e3d-bd5e81181248` 并用 `Assumptions.assumeTrue` 保护，而**已实测这两张单不在当前 `cpq_db`**（库中只有 `QT-20260725-0001`，因该库是重建过的空 baseline 库）→ 全部 skip、Maven 报绿。**用「什么都不检查的检查」当唯一门禁 = 自欺。**

### 新门禁（环境无关、0 行也能失败）

1. **负向断言（核价）** —— 用 `SqlDebugContext`（`datasource/sqlview/SqlDebugContext.java`，`begin()` / `record()` / `drainJoined()`；`SqlViewExecutor` 在 `isActive()` 时无条件记录最终 SQL + 参数）：
   DRAFT 单 → `begin()` → 跑核价侧构建（`precomputeCostingDriverUnion` + `buildCostingCardValues`）→ drain → 断言**每一条** SQL 都不含 `pending_quotation_id`、不含 `AS __v6_id`，且参数表里没有本单 quotationId 被当 `:pq` 绑入。
2. **正向对照（报价）** —— 同一单跑报价侧，断言捕获的 SQL **确实**含 `(t.is_current OR t.pending_quotation_id = ?)` 且含 `AS __v6_id`。
   **没有正向对照，负向断言就是「因为什么都没跑所以通过」。**
3. **前置非空断言** —— 任何 `assertFalse(contains(...))` 之前先 `assertFalse(capturedSql.isEmpty())`；涉及 rows 的断言先断言 rows 非空。否则会复制 `SqlViewExecutorPendingHookTest` 的空转缺陷。
4. **`open()` 白名单单测** —— 遍历 `src/main/java`，断言含 `QuotePendingScope.open(` 的文件集合 **== 白名单集合**（T3 的 P1/P2/P3/P4）。这是 D' 全部安全性的支点；靠人肉 grep 会腐化，写成单测后任何人在核价链上加一次 `open()` 立即红。
5. **skip 计数校验** —— PR 附 surefire 报告中相关测试类的 `tests run / skipped`，要求 **`skipped == 0`**。**只贴「BUILD SUCCESS」不算证据。**
6. **AP-37 缓存交叉测试** —— 同一报价单、同一 lineItem、同一组件：先跑报价侧（开域）再跑核价侧（关域），**30s TTL 内 + 同一请求内**（覆盖 `expandCache` 与 `DataLoader` **两层**）。断言核价侧结果不含 pending 行、不含 `__v6_id`。反向顺序同测。
7. **核价树基线（降级为烟雾测试）** —— 种子 `S-3120014539` 稳定 15 节点 / 4 层。⚠️ 数据依赖，前置 P-4 不满足时本项**作废而非 FAIL**。

> 为什么必须靠断言而非人眼：误改写**不会崩、只会静默漂移**。官方数据不会丢（不变式 `is_current=true ⟹ pending_quotation_id IS NULL` 由 `QuoteBackfillService:106-107` 与 V349 默认 NULL 共同保证），症状只是「多出 `__v6_id` 列 + 多出 pending 行」—— 而 `__v6_id` 一旦进 `costing_card_values`，等价性断言就全破。

### BL-0021 本期不做

把 4 个等价性测试的锚单迁到当前库 + 重校准 golden（S 规模）**继续挂 P2**。新门禁已绕开对它的依赖。

---

## T5 — 端到端验收

**依赖**：T0~T4　详细用例见 `test.md`

- ⚠️ **重新物化走「从基础刷新」** `POST /api/cpq/configure-product/quotations/{id}/refresh-snapshot`（`ConfigureProductResource:95`，1-arg `snapshotQuotation(qid)`，`:125-127` 确认 `skip=false` 全量重 expand）。
  **不是**「保存草稿」—— 后者 `snapshotQuotation(id,true)` 走增量，`lineNeedsExpand:148-156` 只判 `sr == null`，而空数组是非 null → 整行跳过 → 页签仍空、树也不渲染。**这不是修复无效，是入口选错。**
- 逐页签行数：产品 **1** / BOM **6 节点** / 材料成本 **2** / 外购件 **1** / 加工费 **1** / 小计**非 0**
- **页面与 Excel 一致**（P4 验收）
- BOM 树：`node_path` 前缀性；`00006` 在根下与 `S-80011` 下各一次且 `node_path` 不同
- 数据隔离：不得出现 `pending_quotation_id='978479fd-fbad-4426-bcf0-d39603a67f3c'` 那批行
- 幂等：连续重新物化 3 次行数**稳定不累加**（AP-51；禁 `Math.max(expansion.rowCount, baseRows.length)`）
- 冻结态：本库**零冻结单**（已实测），故 AC-10 以单测覆盖，不造真实冻结单
- 三视图一致：报价单编辑 / 核价单 / 详情页 `ReadonlyProductCard`（AP-50）
- E2E：按 **T0 定下的口径**判定
- 「已自检」声明一行：TS 0 错误 / 改动 tsx 的 Vite 200 / 后端业务端点 401 / 本期无 Flyway
- 回写 `docs/RECORD.md`

---

## 收尾：BACKLOG 登记（⚠️ 追加，勿覆盖）

仓库根目录**已有 `BACKLOG.md`**（937 行 / BL-0001~BL-0073 / 65 条 TODO / 2 BLOCKED / 1 进行中）。格式为 `### [BL-NNNN] 标题` + `- **优先级** / **来源** / **状态** / **登记日期** / **背景** / **范围** / **依赖** / **预估规模** / **验收要点**`，章节分 `## P0` / `## P1` / `## P2` / `## 已完成`。

🔴 **严禁用 Write 整体覆盖该文件**（会冲掉 73 条历史记录）。用 Edit 在对应优先级章节末尾追加，BL 编号从 **BL-0074** 起。

### 需更新状态的既有条目

| 条目 | 动作 |
|------|------|
| **BL-0073** | 状态补注「**2026-07-25 已与 task-0725 划清边界**：工序反填保持读正式数据，不纳入本单 pending 可见域；本期有意保留同单双来源现象」 |
| **BL-0021** | 状态补注「task-0725 的 AC-17 已改用 SQL 文本门禁绕开本条依赖；本条仍待 golden owner 重校准」 |

### 建议新登记（BL-0074 起）

| 标题 | 优先级 / 规模 | 说明 |
|------|-------------|------|
| 树递归 SQL 注入 `:customerCode`，解决跨客户同成品的根客户推断 | P1 / S | 现按 `customer_no` 字母序取根客户；`TREE_PARAM` 只注入 4 个 token |
| 补 E2E 夹具缺口（产品分类 / 报价模板），让双 spec 真正全绿 | P1 / S | T0 若实测基线仍 3 failed 则登记 |
| `mat_composite_process` 实体与表不一致清理 | P2 / S | V361 已重命名该表，实体 + `TableRegistry:40` 未同步（task-0723 退役收尾遗漏） |
| 统一 driver 路径与 Excel 公式路径的 pending gating 语义 | P2 / M | 修完后系统有两套 gating（`QuotePendingScope` 预判定 vs `FormulaEvaluateResource:119` 运行时 status 判定），将来改 `isQuotationFrozen()` 只会想到一条 |
| 评估是否启用 `ComponentSqlViewService:379`「冻结单读 quotation SQL snapshot」分支 | P2 / M | 本期刻意让它在 driver 链路保持休眠；该设计意图是否要真正生效需单独决策 |
| 存量空 `quote_card_values` 的运维重算端点 | P2 / S | 本期采纳「用户点从基础刷新」，若体验不可接受再做 |
| 核实报价侧 `quote_card_values` 是否有对应的前端失败提示 | P2 / S | BL-0030 修的是核价侧；报价侧 BOM 页签落了 `__renderError` 但用户看到的是「空」而非红字，可能是个 gap |
