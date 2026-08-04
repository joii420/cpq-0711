# 测试用例 —— repair-0803 公式 SUM 内引用宿主页签字段

- 关联：`需求文档.md`（AC-1~AC-17）、`api.md`、`backtask.md`、`fronttask.md`
- 环境：worktree 分支 `fix/repair-0803-sum-host-field`；单测走 test profile（`10.177.152.12/cpq_db`）；联调走临时端口 Quarkus + 主仓 5174
- 实际结果列留空待执行填写

---

## 1. 取值与依赖（后端核心）

| # | 对应 | 前置数据 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| T-01 | AC-1 | 宿主含 FORMULA 字段 `D`=5；关联页签同键 **2 行**（各 5.0） | 求值 `SUM(基数 + [D])` | 结果 = **20.0**（修复前 10.0） | | P0 |
| T-02 | AC-3 | 同上，但 `D` 改为 `INPUT_NUMBER`、driver 行带值 5 | 同上 | 结果 = 20.0，且**与修复前逐位一致** | | P0 |
| T-03 | FR-2 | 宿主 `D` 为 `INPUT_NUMBER` 且用户**显式清空**（`""`） | 求值 | 取空值（0），**不回落** `fieldValues` | | P1 |
| T-04 | FR-2 | 宿主 `D` 键**完全缺失**（driver 无此列、非 FORMULA） | 求值 | 回落 `fieldValues`；仍无 → 0，不抛异常 | | P1 |
| T-05 | AC-4 | `D`(FORMULA) 自身依赖另一 FORMULA 字段 `E`；`SUM` 内引用 `D` | 求值 | 取到 `D` 的**最终值**（非中间态 0） | | P0 |
| T-06 | FR-3 | 公式 X 的 targetExpr 引用 `D`，`D` 定义在 X **之后** | 求值 | 依赖排序生效，`D` 先算；结果与调换定义顺序时一致 | | P0 |
| T-07 | §5.2.4 | targetExpr 内含 `projectToHostKey=true` 子 token，其 inner 有 `field` | 收集依赖 | 递归**在 KSUM 子 token 处停止**，不把其 inner 计入依赖边 | | P1 |
| T-08 | §5.2.2 | 同一 targetExpr 内两处引用同一字段 `D` | 收集依赖 | 依赖边**去重**，Kahn 入度可归零，**不误报环** | | P0 |
| T-09 | AC-2 | 同一份 fields/formulas/baseRows/crossTabRows | 前端 `computeAllFormulas` vs 后端 `FormulaCalculator` | 两端结果**逐位一致**（对拍夹具） | | P0 |

---

## 2. 环检测与链路（后端）

| # | 对应 | 前置数据 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| T-10 | AC-10 | 同组件内 A、B 两个 FORMULA 字段互相引用 | `PUT /api/cpq/components/{id}` | 400；`data.errorType=="FORMULA_CYCLE"`；`cycles[0].scope=="FIELD"`；`nodes` 含 A/B 及所属公式名；`edges` 含 2 条边及 `viaFormulaName` | | P0 |
| T-11 | AC-11 | 同上 | 检查响应体全文 | 正则 `[0-9a-f]{8}-[0-9a-f]{4}-` 匹配数 = **0** | | P0 |
| T-12 | AC-13 | 卡片内 产品↔物料 互相引用**对方的公式列** | `POST /api/cpq/templates/{id}/publish` | 400；`cycles[0].scope=="TAB"`；`nodes[].componentName` 为中文名 | | P0 |
| T-13 | FR-12/AC-14 | 同 T-12 配置已入库，触发渲染 | 打开报价单卡片 | 错误文案形如 `页签公式存在循环引用: 产品 → 物料 → 产品`，**不含 UUID** | | P0 |
| T-14 | — | 产品引物料的**公式列**、物料引产品的**输入列**（QT-20260803-0052 原形态） | 发布 + 渲染 | **不报环**，正常渲染（回归锁：假环不得复活） | | P0 |
| T-15 | AC-16 | 构造原先仅由 `dfsCycleDetect` 拦截的配置 | 保存组件 | 删除该方法后**仍被拦截**且带定位信息 | | P0 |
| T-16 | FR-4 | 自引用（字段的公式引用自己） | 保存组件 | 报环并指出该字段，不静默 | | P1 |
| T-17 | AC-15 | 一份配置含 2 个互不相交的环 | 保存组件 | `cycles.length==2`，两组 nodes 无交集 | | P1 |
| T-18 | 条件公式 | 环经由条件公式的判断条件 / 某规则命中的公式构成 | 保存组件 | `viaDesc` 为 `条件规则N的判断条件` / `条件规则N命中的公式「X」` | | P1 |

---

## 3. 前端交互

| # | 对应 | 前置 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| T-19 | AC-12 | T-10 的配置 | 组件管理页保存 | **弹出 Drawer**（非 notification/Modal），宽 720，右侧滑出 | | P0 |
| T-20 | AC-12 | 同上 | 查看抽屉内容 | 链路 `「A」→「B」→「A」`（末节点虚线闭合）+ 2 条边各自来源公式名 | | P0 |
| T-21 | AC-15 | T-17 的配置 | 查看抽屉 | 2 个环分组展示，可折叠；≥3 个时默认只展开第 1 个 | | P1 |
| T-22 | FR-11 | T-12 的配置 | 模板发布失败 | 同一个抽屉，`scope=TAB`，节点显示为 `页签「产品」` | | P0 |
| T-23 | api.md §4 | 后端返回非 FORMULA_CYCLE 的多行错误 | 保存失败 | 走**既有** notification 路径，不误弹抽屉 | | P0 |
| T-24 | api.md §4 | 人为把 message 改成含"循环引用"但 errorType 缺失 | 保存失败 | **不弹抽屉**（证明未用文本匹配判定） | | P1 |
| T-25 | FR-6 | 组件公式编辑器 | targetExpr 编辑区选一个 FORMULA 类型的本组件字段 | 出现提示"该值将对每个匹配行各计入一次"；该选项**不被禁用** | | P1 |

---

## 4. 回归面（最重）

| # | 对应 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-26 | **AC-6** | 改前/改后两版引擎对全库 87 个组件同批输入求值，输出差异清单 | 差异**恰好等于**需求文档 §5.4 那 8 条公式；多一条即判失败 | | **P0** |
| T-27 | AC-7 | 8 条公式逐条确认意图后改写 | `test-report.md` 列出每条的：意图 / 是否改写 / 改前值 / 改后值 | | P0 |
| T-28 | AC-8 | 5 张受影响 DRAFT 单重算 | 无 `__cardValueFailed` 哨兵、无 `NaN`/`Infinity`；金额变化逐张记录 | | P0 |
| T-29 | — | 全量 `./mvnw -o test` | 失败集与干净基线 **A/B 逐条一致**（已知 6 项：`QuotePendingScopeOpenWhitelistTest`×1 / `SessionLifecycleTest`×1 / `DataLoaderTest`×4） | | P0 |
| T-30 | AC-17 | `npx playwright test e2e/quotation-flow.spec.ts` | 通过；`'加载中' final count = 0` | | P0 |
| T-31 | — | `npx tsc --noEmit` + 每个改动 tsx 走 Vite | 0 错误；全部 HTTP 200 | | P0 |
| T-32 | — | 核价侧渲染（`buildCostingCardValues` 路径） | 不受影响，与改前一致（本次未动核价隔离） | | P1 |
| T-33 | — | 选配侧 `ConfigureSnapshotService` 物化 | 页签级环仍走降级原序（不中止），文案含组件名 | | P1 |

---

## 5. 边界与异常

| # | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|
| T-34 | 关联页签匹配 **0 行** | `SUM` 返 0；宿主字段不参与（不出现 `0×D` 之类误算） | | P1 |
| T-35 | 关联页签匹配 **1 行** | `Σaᵢ + D`，与"写在 SUM 外"结果相同（N=1 时两种写法等价） | | P1 |
| T-36 | 被引用的宿主 FORMULA 字段求值抛异常 | 该项按现有 try/catch 塌 0，不传播致整卡失败 | | P1 |
| T-37 | targetExpr 引用**不存在**的字段名 | 取 0，不抛异常（与现状一致） | | P2 |
| T-38 | 未登录调用受影响端点 | 401，不泄漏任何 cycles 信息 | | P2 |
| T-39 | 同一组件并发两次保存（均含环） | 两次均 400，无脏写 | | P2 |

---

## 5.5 AC 覆盖矩阵（技术总监审核结论，2026-08-04）

> 审核方式：逐条比对 AC 所述行为与用例实际断言，**不看用例是否绿，看它验的是不是那件事**。
> 所有「已覆盖」项均由技术总监亲自重跑确认，非采信工程师自述。

| AC | 验收内容 | 覆盖用例 | 状态 |
|---|---|---|---|
| AC-1 | 宿主 FORMULA 字段被 SUM 内引用 → 20.0（修复前 10.0） | `FormulaCalculatorSumHostFieldTest#hostFormulaField_isResolvedAndBroadcastPerRow` | ✅ |
| AC-2 | 前后端结果逐位一致 | 共享夹具 `cross-tab-cases.json` 新增 3 例，后端 `FormulaCalculatorCrossTabFixtureTest`(49) + 前端 `formulaEngine.test.ts`(109) 各自消费 | ✅ **审核时补** |
| AC-3 | b_field 指向 INPUT_NUMBER 行为逐位不变 | `#hostInputField_broadcastsPerRow` + 夹具「prefers currentRow over host fallback」 | ✅ |
| AC-4 | 被引用宿主公式字段自身还依赖别的公式 → 取最终值 | `#transitiveFormulaDependency_resolvesToFinalValue` | ✅ |
| AC-5 | 互相引用报环、可定位、不静默 | `#mutualRefThroughTargetExpr_isDetectedAsCycle`、`ComponentServiceFormulaCycleStructuredTest#ac16_simpleMutualReference_*` | ✅ |
| AC-6 | 全库跑批差异恰好等于 §5.4 那 4 条公式 | `SumHostFieldAffectedFormulasLiveScanTest#ac6_affectedFormulaSet_matchesDocumentedFourFormulas`（连真库扫 87 组件，另断言 b_field token 总数 19 防漂移，并逐条核对引用字段名防同名巧合） | ✅ 需 `RUN_LIVE_DB_SCAN=1` |
| AC-7 | 8 条公式意图确认并落地 + 改前改后值 | — | ⛔ **阻塞：待业务确认「整单一次 / 每行一次」** |
| AC-8 | 5 张 DRAFT 单重算无哨兵无 NaN | — | ⏳ 需合并后在主仓运行时验证 |
| AC-9 | 编辑器选 FORMULA 字段出现「每行各计入一次」提示 | F6 已实现（`CrossTabRefDrawer.tsx`） | ⏳ 无自动化，需人工/E2E |
| AC-10 | 结构化 FIELD 环载荷 | `ComponentServiceFormulaCycleStructuredTest#ac10_*` | ✅ |
| AC-11 | 响应体全文零 UUID | `#ac11_responseBody_containsNoUuid`（经**真实** GlobalExceptionMapper + 生产同款 Jackson 序列化）、`TemplateCrossTabCycleStructuredTest#ac13_messageAndCycles_containNoUuid` | ✅ |
| AC-12 | 前端弹 Drawer 且可见链路 | F3/F4 已实现 | ⏳ 无自动化，需人工/E2E |
| AC-13 | 模板发布 scope=TAB + 中文页签名 | `TemplateCrossTabCycleStructuredTest#ac13_mutualCrossTabRef_*` | ✅ |
| AC-14 | 渲染期文案含名称、不含 UUID | `CrossTabComponentOrderTest#cycleMessage_rendersComponentNames_withoutIds` | ✅ |
| AC-15 | 2 个独立环分组、节点无交集 | 后端 `#ac15_twoDisjointCycles_*` ✅；前端分组展示 ⏳ 无自动化 | 🟡 部分 |
| AC-16 | 删 `dfsCycleDetect` 后仍被拦截 | `#ac16_selfReference_stillCaught_withStructuredLocation`、`#ac16_simpleMutualReference_*` | ✅ |
| AC-17 | E2E `quotation-flow` 通过 | — | ⏳ worktree 无独立前后端 dev server，需合并后在主仓跑 |

### 关于 AC-6 用「静态影响面分析」替代「两版引擎跑批比对」的裁决

AC-6 原文要求改前/改后两版引擎对同一批输入跑批比对。测试工程师改用**静态影响面扫描**，
技术总监**认可该替代**，论证如下：

本次改动只在 `b_field` 取值链上加了一个回落分支，触发条件是 `currentRowRaw` **键缺失**。而
- 指向 FORMULA 字段 → 键必缺失（公式结果只回填 fieldValues）→ 值 0 变真值 → **结果变**
- 指向 INPUT_* 字段 → 键存在 → 不回落 → **结果不变**
- 指向不存在的字段 → 回落后仍取不到 → 仍 0 → **结果不变**

即「结果会变」的**充要条件**就是「targetExpr 内 b_field 指向本组件 FORMULA 字段」，静态扫描
恰好识别的就是这个集合。依赖收集的新增边同理收敛（算序改变仅在原本取不到值时才影响结果）。
静态分析在此比动态比对更可靠——后者需要为全库 87 个组件构造可求值输入，不现实。

### 无法自动化项与人工验证步骤

- **T-39 并发保存**：`ComponentCycleConcurrentSaveTest` 已 `@Disabled`。该环境 `@QuarkusTest`
  无可用登录态（干净基线 `TemplateResourceTest` 同样整片 401）。人工验证：登录后用两个浏览器
  标签同时保存同一含环组件，均应弹环链路抽屉，且库中该组件 `updated_at` 不变。
- **AC-9 / AC-12 / AC-15 前端部分**：需人工在组件管理页保存含环组件，确认弹出的是 Drawer
  （非 notification/Modal）、链路与原型一致、多环分组。

## 6. 执行顺序建议

1. **先跑 T-01~T-09**（取值 + 依赖）——不过不进入后续
2. **再跑 T-26**（AC-6 全量比对）——这是判断"有没有改坏"的总闸
3. 然后 T-10~T-18（环检测）→ T-19~T-25（前端）
4. 最后 T-27/T-28（存量改写 + 单据重算）与 T-29~T-31（回归 + 自检）

> T-26 未通过时**禁止**继续往下推进：说明改动影响面超出预期，须先定位多出来的差异。
