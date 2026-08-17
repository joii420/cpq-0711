# backtask · repair-0814 发布冻结后 tabType 护栏误拦

> 依据：本目录 `问题说明.md`（六段齐全，闸门 A 已过，⑤ = 方案 D 修订版 + `BL-0172` 并入本期）
> 分支：`fix/repair-0814-tabtype-guard`（worktree `.claude/worktrees/repair-0814-tabtype-guard`，基于 `44d67f10`）
> **本任务纯后端**：无 Flyway 迁移、无表结构变更、无前端改动。

---

## 1. 数据模型变更

**无。** 不新增/修改任何表、列、视图、索引；**不产出 Flyway 迁移**。

判定依据：三项改动都只是**读现有数据做判断**——
`template.status` + `template_component_snapshot` 行是否存在（D-1）、待冻 `snapshotRows` 的 `bom_recursive_expand`（D-2）、
driver 行是否带 `parent_no` 键（D-3）。没有任何新状态需要持久化。

---

## 2. 服务与端点清单

**端点零新增、零签名变更。** 受影响的是三个既有端点的**校验行为**（返回码可能从 400 变 200，或从 200 变 400）：

| 端点 | 变化 | 对应 |
|---|---|---|
| `PUT /api/cpq/components/{id}` | `tabType=BOM` 的拦截条件收窄 → 原先误拦的现在返 200 | D-1 |
| `POST /api/cpq/templates/{id}/publish` | 新增树页签不变量断言 → 违反时 400 | D-2 |
| 走核价树渲染的路径（`batch-expand` / 核价卡片渲染 / 价格更新 job） | 树页签 `$view` 缺 `parent_no` 从静默 warn 升级为显式失败 | D-3 |

---

## 3. 业务规则与算法

### D-1 · 护栏判定收窄到「未冻结的引用」

**位置**：`ComponentService.java:410-423` `assertNotReferencedByCostingTemplate`

**新判据** —— 一条 COSTING 引用**算数**（即仍需拦截）当且仅当：

```
template.status ∉ {PUBLISHED, ARCHIVED}                      // DRAFT 等，渲染期直读活表
  OR
template.status ∈ {PUBLISHED, ARCHIVED} AND 该模板快照零行      // D17「未冻结」过渡态
```

计数 > 0 才抛 400。计数 = 0（所有引用都来自**已冻结**模板）→ 放行。

**口径同源（强制）**：不在 `ComponentService` 里自己写「什么叫已冻结」。
`PublishedTemplateReader` 已有 `isFrozen(UUID)`（`:80`，内部 `isFrozenStatus(status)` + 快照 `COUNT`），但它是**单张**的；
护栏面对的是「一个组件的 N 张 COSTING 模板」，循环调 = **N+1 违规**。

→ **在 `PublishedTemplateReader` 新增批量方法**（名称与签名实现时定稿，语义固定）：

```
输入：Collection<UUID> templateIds
输出：这批里【未冻结】的那些（status 非 PUBLISHED/ARCHIVED，或 status 是但快照零行）
实现：一次查 template(id, status) + 一次按 template_id 聚合查 snapshot 计数 → 内存判定
```

**SQL 条数硬指标：与模板数 N 无关，恒 2 条。**

**文案**（对应 E-3）：不再写「会把这些核价模板一并改成树渲染」（冻结后已是错误陈述）。改为点名**具体模板 + 状态**：

> 该组件被以下尚未冻结的核价(COSTING)模板引用，不能设为 BOM 树页签：核价模板X v1.0(DRAFT)。
> 这些模板渲染时直接读取组件活配置，改为树页签会立即改变它们的渲染方式。
> （已发布并已冻结的核价模板不受影响，故不在此列。）

### D-2 · `publish()` 树页签不变量断言

**位置**：`TemplateService.publish()`，在 `persistSnapshotRows(id, tcs, compById)`（`:228`）返回**之后**、方法提交之前。

**断言**：

```
if (template.templateKind == "COSTING")
    count(snapshotRows where bomRecursiveExpand == true) <= 1      否则 400
```

**为什么断言在 `snapshotRows` 上而不是 `tcs` / `compById` 上**：
`snapshotRows` 就是**本次要冻的那批行**，正是 AC-10 要求的判据对象。`buildSnapshotEntity:967` 里
`s.bomRecursiveExpand = comp.bomRecursiveExpand`（无 tc 级 override），两者当前等价 —— 但等价是实现细节，
写在 `snapshotRows` 上才**语义正确**，且不会因将来给该字段加 override 而失效。
抛异常回滚同事务的 persist，无残留。

🚫 **明令禁止**：与上一版快照做 diff、比对 `tab_type` 变化量、任何形式的「非 BOM → BOM」检测。
理由见 `问题说明.md` §③ 说明框（delta 判据假阳性 + 假阴性双错）。**AC-10 是专门为此设的闸。**

**三个 `persistSnapshotRows` 调用点的处理不对称（重要设计决策）**：

| 调用点 | 场景 | 是否硬拦 | 理由 |
|---|---|---|---|
| `:228` `publish()` | 用户主动发新版 | ✅ **硬拦 400** | 模板此刻是 DRAFT，用户**能回去改**（解绑一个树页签再发）→ 拦得起 |
| `:347` `archive()` 补冻（D18） | 归档前自动补冻 | ❌ **不拦，记 WARN** | 归档是终态、没法重新发布；硬拦会把存量模板卡在**既不能归档也不能修**的状态 |
| `:576` `freeze()` 首次冻结 | 存量模板人工补冻 | ❌ **不拦，记 WARN** | 同上，这是**救援路径**。违规模板若在此被拦，将既无法冻结（不能渲染）又无法编辑（非 DRAFT 不允许改 tc）= 彻底砖化 |

> 存量安全性已实测：`cpq_db_0724` 的 5 张 COSTING 模板（活表侧 + 快照侧）**均恰好 1 个树页签，零违规**，
> 故上述不对称当前不产生实际差异；它是为**未来出现违规存量时不砖化**而设的防线。

### D-3 · 树页签 `$view` 缺 `parent_no` 的显式检出（原 `BL-0172`）

**位置**：`BomTreeRenderService.java:349-354`，现状：

```java
if (recursive && kept > 0 && missingParent == kept) {
    LOG.warnf("[costing-tree] 树页签组件 %s 的 $view 未输出 parent_no（%d 行全无父件列）,…");
}
```

**改为硬失败**（抛 `BusinessException`），与本方法**已有的既定方向一致** —— 同文件 `:365-378` 的
`failedComponents` 块明写「不能把『组件 expand 抛异常』悄悄降级成『该组件 0 行』…带着残缺数据静默"成功"」。
本条是同类问题（带着**全空行**静默成功），处置口径应当统一。

**强度选择依据（全库扫描，`cpq_db_0724`，2026-08-14）**：18 个 `bom_recursive_expand=true` 的组件，
其 `component_sql_view.sql_template` **全部含 `parent_no`（18/18）**，零合法反例 → 硬拦不误伤存量。

**触发条件保持不变，不得放宽**：`recursive && kept > 0 && missingParent == kept`
—— 「有行、且**全部**行都没有父件列」才判定为配置错误。**部分行缺 `parent_no` 不拦**（那是数据问题，不是配置问题）。

⚠️ 实现时注意：`ILIKE '%parent_no%'` 只能证明模板文本含该串，不能证明它是**输出列**（可能出现在 WHERE 里）。
该扫描用于「是否存在合法反例」的**否定性判断**足够；若实现中发现某组件文本含 `parent_no` 但运行期仍
`missingParent == kept`，按硬拦处理并在 `test-report.md` 记录该反例。

---

## 4. 事务边界

- **D-1**：`ComponentService.update` 既有事务内，纯读判定，不改事务边界
- **D-2**：`publish()` 既有 `@Transactional` 内。断言在 `persistSnapshotRows` 之后抛出 → **同事务回滚**，
  快照行与 `components_snapshot` jsonb 均不落库，模板停留在 DRAFT。**不需要**手工清理
- **D-3**：`render()` 既有路径，抛出后由上层（`upgrade()` / 渲染调用方）按既有 `FAILED` 通道处理，与 `:365-378` 同款

**幂等与并发**：三处均为**读判定**，不引入新写操作，无幂等/并发新问题。
D-2 的断言与 `persistSnapshotRows` 的「先清后插」在同一事务内，回滚后旧快照（若有）原样保留。

---

## 5. 性能要求

**硬指标：SQL 条数与 N 无关。**

| 改动 | N 是什么 | 要求 |
|---|---|---|
| D-1 | 该组件的 COSTING 引用模板数 | **恒 2 条**（1 查 template、1 查 snapshot 聚合计数），禁止 per-template 调 `isFrozen` |
| D-2 | 待冻页签数 | **0 条新增**（断言只遍历已在内存的 `snapshotRows`） |
| D-3 | driver 行数 | **0 条新增**（`missingParent` / `kept` 已在既有循环里算好） |

N+1 自检按 `CLAUDE.md` 执行：逐个检查新增/改动的 `for` / `forEach` / `stream()` 循环体，
确认无 repository 调用、无 `SqlViewExecutor.execute`、无触发懒加载的 getter。

---

## 6. 自检项

| # | 项 | 期望 |
|---|---|---|
| 1 | `touch` 一个 java 文件强制 Quarkus 重启（主工作区 8081，**不在 worktree 起新 server**） | 等 5-7s |
| 2 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` | **401**（非 500） |
| 3 | Flyway | **N/A —— 本次无迁移** |
| 4 | 视图 DROP CASCADE / schema DDL | **N/A —— 无 DDL** |
| 5 | N+1 自检声明 | 写进 `test-report.md`，格式见 `CLAUDE.md` |
| 6 | 后端测试 | `ComponentServiceTabTypeGuardTest` 全绿（**基线已实测：12 tests / 0 failures**，worktree 内 `./mvnw test -Dtest=...`）+ 新增用例全绿 |
| 7 | E2E | **必跑**，判据见下 |

> ⚠️ **E2E 判定**：`CLAUDE.md`「修改后强制自检」第 5 条把 `ComponentService.java` 列入协议级改动清单，
> 本次确实改了它（D-1），故**必须跑** `quotation-flow.spec.ts`。
> 已知环境限制：该 spec 在干净 master 上因夹具漂移**恒 3 失败**（记忆 `task0712-update071501-category-axis`、`BL-0078`），
> 因此判据不是「全绿」而是**同型 A/B 对照** —— worktree 与主工作区 master 跑出**相同的失败集合**才算无回归。
> 结论与证据入 `test-report.md`。

---

## 7. Task 列表（逐项可勾选）

- [x] **T1** `PublishedTemplateReader.unfrozenAmong(Collection<UUID>)` —— 2 条 SQL，javadoc 写明与 `isFrozen` 的口径关系
- [x] **T2** `ComponentService.assertNotReferencedByCostingTemplate` 改用 T1，判定收窄 + 文案重写（点名模板 + 状态 + 多行）
- [x] **T3** `TemplateService.publish()` 在 `persistSnapshotRows` 后加 `assertAtMostOneTreeTab`；`archive` 补冻 / `rebuildSnapshotForTemplate` 两条救援路径改调 `warnIfMultipleTreeTabs`
- [x] **T4** `BomTreeRenderService` `LOG.warnf` → `assertParentNoPresent` 抛 `BusinessException`；触发条件未放宽（顺带抽成可单测的静态方法）
- [x] **T5** `ComponentServiceTabTypeGuardTest` +4 用例，**实测 `git diff --numstat` = 130 增 / 0 删**（「只增不改」字面成立）
- [x] **T6** 新建 `TemplateServiceTreeTabInvariantTest`（7 用例，含 AC-10 防 delta 专项闸）
- [x] **T7** 新建 `BomTreeParentNoGuardTest`（4 用例，覆盖 1 个"该拦"+ 3 个"不该拦"边界）
- [x] **T8** AC-7 同类排查完成 —— 5 处命中逐个判读，**查出 1 个真发现**（`DriverBatchSafetyAuditor:100`），按既定口径只登记不改 → `BL-0171`。明细见 `test-report.md` §8
- [x] **T9** 自检 + `test-report.md`（含 N+1 逐循环声明、两次还原实验、AC 逐条对照）
      ⏳ 其中 **AC-4 逐位 A/B 与 E2E 延后到合并后执行**，原因与基线见 `test-report.md` §7
