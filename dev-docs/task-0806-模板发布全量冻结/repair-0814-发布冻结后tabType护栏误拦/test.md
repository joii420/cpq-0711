# test · repair-0814 发布冻结后 tabType 护栏误拦

> 依据 `问题说明.md` §⑥（AC-1~AC-12）+ `backtask.md` T1~T9 + `api.md` A-1~A-3。
> 环境：分支 `fix/repair-0814-tabtype-guard`；后端单测走 `test` profile → `10.177.152.12:5432/cpq_db`（**与 dev 库 `cpq_db_0724` 不同**，写用例时注意）；
> 真机验收走主工作区 dev server（8081 / 5174，库 `cpq_db_0724`）。
>
> 🩸 **两条铁律**
> 1. **AC-4（核价渲染逐位不变）是一票否决门禁** —— 必须 A/B 背靠背对比，禁止目测。
> 2. **AC-10 是防实现漂回 delta 判据的专项闸** —— TC-10 若通过不了，说明实现用了「与上一版比对」，必须打回。

---

## 1. 基线（改动前已实测）

| 项 | 结果 |
|---|---|
| `ComponentServiceTabTypeGuardTest` | **12 tests / 0 failures / 0 errors**（worktree 内 `./mvnw test -Dtest=ComponentServiceTabTypeGuardTest`，2026-08-14） |
| `cpq_db_0724` COSTING 引用分布 | ARCHIVED 1 张 / PUBLISHED 4 张；61 条引用**全部已冻结**；DRAFT **0 张**；22 组件被锁 |
| `cpq_db_0724` 树页签数 | 5 张 COSTING 模板，活表侧与快照侧**均恰好 1 个**，零违规 |
| 树组件 `$view` 含 `parent_no` | **18/18**，零合法反例 |

---

## 2. 用例清单

### 2.1 D-1 护栏收窄（`ComponentServiceTabTypeGuardTest`，**只增不改**）

| 编号 | AC | 前置数据 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| **TC-01** | AC-1 | 组件 C；COSTING 模板 T `status=PUBLISHED`，`template_component` 绑 C，**且 `template_component_snapshot` 有对应行** | `svc.update(C, {tabType:'BOM'})` | **200**；`C.tabType='BOM'`、`bomRecursiveExpand=true` | | P0 |
| **TC-02** | AC-2 | 同上但 T `status=DRAFT`（**即现有用例 `componentReferencedByCostingTemplate_cannotBecomeBomTab` 的构造**） | 同上 | **400**；`C.tabType` 未被改动 | | P0 |
| **TC-03** | AC-3 | T `status=PUBLISHED` 但**快照零行**（D17 未冻结态） | 同上 | **400** | | P0 |
| **TC-04** | AC-2 | T `status=ARCHIVED` + 有快照 | 同上 | **200**（已冻结，同 TC-01） | | P1 |
| **TC-05** | AC-2 | 组件被**两张**模板引用：T1 已冻结 PUBLISHED、T2 DRAFT | 同上 | **400**，且文案**只点名 T2**（不列已冻结的 T1） | | P0 |
| **TC-06** | AC-6 | 同 TC-05 | 检查 `ex.getMessage()` | 含模板名 + 状态；**不含**「一并改成树渲染」字样；**含 `\n`**（多行 → 前端走常驻 notification，见 `api.md` A-1） | | P1 |
| **TC-07** | AC-5 | —— | 全类回归 | 原 12 个用例**语义未改且全绿** | | P0 |
| **TC-08** | 性能 | 组件被 5 张 COSTING 模板引用 | 开 SQL 计数 | 判定 SQL **恒 2 条**，与模板数无关（模板数 2→5 条数不变） | | P1 |

> ⚠️ TC-02 是**已有用例**，本次不改它一个字符 —— 它构造的恰好是 `status="DRAFT"`，正是「至今仍该拦」那一档。
> 它保持绿色本身就是「阳性能力没丢」的证据。

### 2.2 D-2 publish 树页签不变量

| 编号 | AC | 前置数据 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| **TC-09** | AC-8 | DRAFT COSTING 模板 T 绑两个 `bomRecursiveExpand=true` 的组件（**绕开 `addComponent` 直接 persist `TemplateComponent`**，因为该入口自己会拦） | `publish(T)` | **400**；文案点名两个冲突页签；**模板仍为 DRAFT，`template_component_snapshot` 零行**（事务回滚） | | P0 |
| **TC-10** | **AC-10** | DRAFT COSTING 模板 T：上一版快照里 A 是树、B 非树；本次改成 A 非树、B 是树（**净数量仍为 1**） | `publish(T)` | **200 放行** —— delta 式实现必在此假阳性 | | **P0（防漂回专项闸）** |
| **TC-11** | AC-9 | DRAFT COSTING 模板恰好 1 个树页签 | `publish(T)` | **200**；快照树页签数 = 1 | | P0 |
| **TC-12** | AC-9 | DRAFT **QUOTATION** 模板绑 2 个树页签组件 | `publish(T)` | **200 放行**（约束只对 COSTING） | | P1 |
| **TC-13** | 边界 | DRAFT COSTING 模板 **0 个**树页签 | `publish(T)` | **200**（≤1 含 0） | | P1 |
| **TC-14** | 救援路径 | 违规模板（2 树页签）走 `freeze()` 首次冻结 / `archive()` 补冻 | 调用两条路径 | **不拦，正常完成**，仅记 WARN（理由：不砖化存量，见 `backtask.md` §3 D-2 表） | | P0 |

### 2.3 D-3 `parent_no` 检出（原 `BL-0169`）

| 编号 | AC | 前置数据 | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| **TC-15** | AC-11 | 树页签组件的 `$view` 返回若干行但**全部无 `parent_no`** | 触发 `BomTreeRenderService.render()` | **抛 `BusinessException`**，不再静默返回全空行 | | P0 |
| **TC-16** | AC-11 边界 | 树页签组件 `$view` **部分行**缺 `parent_no` | 同上 | **不拦**（数据问题非配置问题），行为与改动前一致 | | P0 |
| **TC-17** | AC-11 边界 | 树页签组件 `$view` 返回 **0 行**（`kept == 0`） | 同上 | **不拦**（触发条件要求 `kept > 0`） | | P1 |
| **TC-18** | 回归 | **非**树页签组件无 `parent_no` | 同上 | **不拦**（条件要求 `recursive`），行为不变 | | P0 |

### 2.4 回归面

| 编号 | AC | 内容 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|
| **TC-19** | **AC-4 ★一票否决** | 同一核价单在「改动前 / 改动后」各渲染一次，`costing_card_values` 逐字段比对 | **逐位不变** | | **P0** |
| **TC-20** | AC-5 | `assertPartNoFieldRequirement` / `assertTreeTokenGates` / 闸③（BOM 转出 + `tree_ref`/`tree_attr`） | 行为逐条不变（含在 TC-07 全类回归内） | | P0 |
| **TC-21** | AC-12 | E2E `quotation-flow.spec.ts` | **A/B 同型对照**：worktree 与主工作区 master 失败集合**相同**（干净 master 因夹具漂移恒 3 失败，见 `BL-0078`） | | P0 |
| **TC-22** | AC-12 | 后端自检 | `touch` java → Quarkus 重启 → `/api/cpq/components` 返 **401**（非 500） | | P0 |
| **TC-23** | AC-12 | N+1 自检 | 新增/改动的所有循环体内**无查库、无 `SqlViewExecutor.execute`、无懒加载 getter** | | P0 |

### 2.5 真机验收（闸门 B 用，库 `cpq_db_0724`）

| 编号 | AC | 步骤 | 期望 | 实际 | 优先级 |
|---|---|---|---|---|---|
| **TC-24** | AC-1 | 组件管理 → `COMP-0233`「物料与元素BOM」→ 页签类型 `材质元素` → `BOM` → 保存 | **保存成功**；DB 复核 `tab_type='BOM' AND bom_recursive_expand=true` | | P0 |
| **TC-25** | AC-9 | 对 `核价模板-简易` 走一次完整发布 | **发布成功**，快照树页签数仍为 1 | | P0 |
| **TC-26** | AC-6 | 构造一个仍会被拦的场景（DRAFT COSTING 模板引用） | 前端显示**常驻 notification**（非 3s toast）、文案分行可读 | | P1 |

---

## 3. AC → 用例追溯表

| AC | 用例 |
|---|---|
| AC-1 阴性不再误报 | TC-01、TC-04、**TC-24（真机）** |
| AC-2 阳性 DRAFT 仍拦 | TC-02、TC-05 |
| AC-3 阳性未冻结态仍拦 | TC-03 |
| AC-4 核价渲染逐位不变 ★ | **TC-19** |
| AC-5 联动与既有闸不变 | TC-07、TC-20 |
| AC-6 文案 | TC-06、TC-26 |
| AC-7 同类排查 | 见 `test-report.md` 专节（清单式，非用例） |
| AC-8 树页签不变量阳性 | TC-09 |
| AC-9 不误伤存量 | TC-11、TC-12、TC-13、**TC-25（真机）** |
| AC-10 防 delta 回归 ★ | **TC-10** |
| AC-11 `parent_no` 检出 | TC-15、TC-16、TC-17、TC-18 |
| AC-12 强制自检 | TC-21、TC-22、TC-23 |

---

## 4. 执行纪律

1. **不采信子代理的「已完成」** —— 主线亲跑测试、亲查 DB、亲看输出（任务平台规则 §6）。
2. **TC-19 的 A/B 必须真做**：用 `git stash` 或同型对照跑改动前/改动后两次，**不能凭经验目测**。
3. **验证脚本首次 PASS 不等于有效** —— 按记忆 `cpq-agent-tests-stale-server-false-positive` 的教训，
   对 TC-10 / TC-15 这类新增护栏用例做**还原实验**（把修复改回去重跑，**必须变红**）；不变红 = 空验证，作废重写。
4. 用例文档未经主线审核**不得进入执行阶段**（任务平台规则 §4 第 6 步）。
