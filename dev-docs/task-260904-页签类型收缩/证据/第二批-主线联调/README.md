# 第二批 · 主线联调证据（API 层）

> 采集人：主线　采集时间：2026-09-06　采集方式：亲手 curl，非子代理转述
> 服务端：`8089` = 本任务 worktree 代码（后端 B-1/B-2/B-3/B-22 + 上游闸）；`8081` = 主仓 master（对照组）
> 库：共享 dev 库 `10.177.152.12:5432/cpq_db_0724`

⚠️ **本文件是 API 层证据，不构成完整亲验。** 亲验还需从 UI 进走用户视角完整路径（见 `第二批-UI亲验/`）。

---

## 0. 采集前提：一个会让结论全错的坑

`GET /config/semantic-graph/field-tree` 的方言入参名是 **`dialect`**，不是 `dataset`。
**JAX-RS 静默忽略未知查询参数** —— 传错不报错，三个方言会全部返回缺省 QUOTE 的同一份结果。

> 🔴 主线第一轮正是按 `dataset=` 打的，得到「三方言结果完全相同」，**差点当成后端缺陷报出去**。
> 教训：**下游结论异常时，先证明请求本身发对了**，再怀疑被测对象。

---

## 1. `availableSources` 契约（F-1 / B-1）—— 七项逐条核对

```
dialect=QUOTE        11 条   semantic: TREE×1  MATERIAL_ELEMENT×1  null×9
dialect=COST_BASIC   10 条   semantic: TREE×1  MATERIAL_ELEMENT×1  null×8
dialect=COST_DETAIL  18 条   semantic: TREE×1  MATERIAL_ELEMENT×1  null×16
```

| # | 检查项 | 结果 |
|---|---|---|
| 1 | 前端 `FieldTreeSource` 与后端 `Source` 的键集合 | ✅ **互为子集，零多余** |
| 2 | `sourceKey` 唯一（前端当 Select value + React key） | ✅ 11/11 · 10/10 · 18/18 |
| 3 | `dialect` 恒等于入参 | ✅ 三方言各自正确 |
| 4 | 退役「零件 / 外购件」已剔除（**AC-1 / AC-3**） | ✅ 0 残留 |
| 5 | `semantic` 三态分布（**AC-10**） | ✅ 每方言恰好 1 TREE + 1 MATERIAL_ELEMENT |
| 6 | 普通源 `semantic` 是**显式 null** 而非缺键 | ✅ 见 §2 |
| 7 | `availableTabTypes`（已退役字段）已过滤 | ✅ `['主件','材质元素','费用类','BOM']` |

---

## 2. 🔑 前端三态设计的地基：Jackson 输出 null 键

前端用 `semantic === undefined`（不知道）与 `=== null`（已知不是材质元素）区分两种状态。
**这套设计成立与否，全看后端 JSON 会不会把 null 值的键输出出来** —— 若配了 `NON_NULL`，普通源的
`semantic` 会整个键缺失，读出来是 `undefined`，那行 PRICE 隐藏判断将**永不生效**。

实测（真实响应，非推断 Quarkus 默认值）：
```
conflictReason: null      ← 显式键
lookupLib:      null      ← 显式键
availableSources[].semantic: null（普通源，键在、值为 null）
```
⇒ ✅ 本项目未开 `NON_NULL`，**前端三态成立**。

---

## 3. `tab_type` 收缩后仍可解析存量坐标（**AC-25①**）

```
tabType=零件   & dialect=QUOTE  → 200，1 组 [MATERIAL_BOM]
tabType=外购件 & dialect=QUOTE  → 200，1 组 [MATERIAL_BOM]
```
⇒ 收缩发生在**入口**（`availableSources` 不列出，用户选不到），**不在校验** ——
否则 142 个存量组件会当场打不开。`api.md §1.4` 原写「传入停用值 → 404」已据此更正。

---

## 4. 各方言组数矩阵（**AC-20**，取代文档中两条过期结论）

| tabType | QUOTE | COST_BASIC | COST_DETAIL |
|---|---|---|---|
| `主件` | 1 组 `MATERIAL` | 1 组 | 1 组 |
| `BOM` / `零件` / `外购件` | 1 组 `MATERIAL_BOM` | 1 组 | 1 组 |
| `材质元素` | **3 组** 🔴 | 1 组 `ELEMENT_BOM` | 1 组 `ELEMENT_BOM` |

- ✅ **AC-20② 的适用面从 1 个方言扩到 3 个**：`BOM`/`零件`/`外购件` 在三方言下声明逐字相同。
  文档原写「⚠️ 仅 QUOTE 成立，核价两套各多挂 `QUOTE_MATERIAL_BRIDGE(AUX)`」——
  写下时正确，已被 `task-260819` B-50 作废。已更正。
- 🔴 QUOTE 材质元素 3 组 = **AC-30 缺陷**（`FUNC_ELEMENT_PRICE` 重复），详见 §5。

---

## 5. AC-30 缺陷取证：非本任务引入

**master(8081) 与 worktree(8089) 逐字相同** ⇒ 与本任务无因果：

| 方言 | master(8081) | worktree(8089) |
|---|---|---|
| QUOTE | 3 组，PRICE×2 🔴 | 3 组，PRICE×2 🔴 |
| COST_BASIC | 1 组，PRICE×0 | 1 组，PRICE×0 |
| COST_DETAIL | 1 组，PRICE×0 | 1 组，PRICE×0 |

两块字段坐标**完全相同**，只有派生属性不同：
```
#1 元素单价 sourceNodeKey=FUNC_ELEMENT_PRICE sourceColumn=unit_price isCore=False viewColumn='_价格策略_元素单价'
#2 元素单价 sourceNodeKey=FUNC_ELEMENT_PRICE sourceColumn=unit_price isCore=True  viewColumn='元素单价'
```

### 🔄 严重性判断的更正过程（留档，因为错法可复用）

- ❌ **初判**：「用户从 #1 拖列 → `basic_data_path` 指向视图未声明的列 → 报价单静默空白（数据损坏）」
- ✅ **证伪**：前端 `SqlViewBuilderTab.configPayloadFor:451-463` 只发
  `sourceNodeKey / sourceColumn / fieldName / 角色布尔位 / userAdded`，**不发 `viewColumn`**
  ⇒ 两块保存请求逐字相同，编译器按坐标重算别名，产出 SQL 都对。
- ✅ **实际危害**：两块 `isCore` 不同，而它驱动前端价格策略**原子组**语义
  ⇒ 从 #1 拖出的单价列删它不整组删除、原子组认不到它。**行为缺陷，非数据损坏。**
- 📌 **错在哪**：从「两块 `viewColumn` 不同」直接推到「写进 `basic_data_path` 的就是这个值」，
  **中间那步『前端会不会把它发出去』没查就当成了真**。
  ⇒ 判渲染/存储后果前，必须先确认该字段是否真的进了请求体。

---

## 6. AC-30 修复后主线亲验（B-23 落地后重采，2026-09-06）

**同轮次 A/B 对照**（8089 = 含 B-23；8081 = master 无 B-23）：

| 方言 | worktree(8089) | master(8081) | 期望 | 判定 |
|---|---|---|---|---|
| QUOTE | **2 组（PRICE×1）** | 3 组（PRICE×2） | 2 | ✅ |
| COST_BASIC | 1 组（PRICE×0） | 1 组（PRICE×0） | 1 | ✅ 阴性对照未受影响 |
| COST_DETAIL | 1 组（PRICE×0） | 1 组（PRICE×0） | 1 | ✅ 阴性对照未受影响 |

- ✅ **保留的是正确那块**：`groupName='价格策略'` / `isCore=True` / `viewColumn='元素单价'`（与 `SemanticCompiler:196` 的 `bareColumn` 同源）
- ✅ **未被"换个形式再重复一次"**：MAIN 组 `物料与元素BOM` 字段数仍为 **12**（未变），且组内 **无** `sourceNodeKey=FUNC_ELEMENT_PRICE` 的列混入

> 🔑 最后一项是专门验一个隐蔽失败形态：`FieldTreeBuilder` 的 `nodesWithOwnGroup` 语义是「本响应里已自成一组的节点」，
> `syntheticLookupFields` 靠它决定要不要把某节点的列**内联进 MAIN**。价格节点被过滤出 `tvns` 后就不在该集合里了 ——
> 它确实有组（末尾专用块），**不补回去就会被当成「没组」而内联进 MAIN，等于换个形式再重复一次**。
> 实现里补了 `nodesWithOwnGroup.add(priceGroupNodeId)`，本项实测确认补回有效（MAIN 字段数没有从 12 变成 14）。

---

## 7. 环境事故记录（非缺陷，但会造成假红，须留档）

本 worktree 的 `quarkus:dev`（主线起的 8089）与在此跑的任何 `mvnw` **共用 `cpq-backend/target/`**：

- `mvnw clean` → 铲掉 8089 的 `target/classes` → 它 `Error restarting Quarkus`
- 8089 的 live reload → 刷写 `target/test-classes` → 并发测试读到半截 class

典型假红症状（后端工程师本轮遇到两次）：
```
NoClassDefFoundError: com/cpq/configure/service/ConfigureSnapshotService$DriverComp
PreconditionViolationException: Could not load class with name: …Sec34PriceStrategyTest
```
🚨 **这类红不是代码问题。** 据此改实现 = 把环境问题修成代码改动。

**规避**：跑测试时用隔离副本（拷 `src/ pom.xml mvnw .mvn` 到 scratch 目录），
🔑 **拷完必须 `diff -rq` 逐文件确认与 worktree 一致** —— 否则测的是一份漂移的源码，那是更隐蔽的假绿。

另记：本轮有一次 `pkill -f "quarkus:dev"` 范围过宽，同时匹配到主工作区 8081 与 worktree 8089。
**事后主线亲自复核：8081 返 401 且 `/quotations` 查询返回真实数据、5174 返 200、8089 返 401 —— 无损失。**
教训：`pkill` 必须带端口等限定（`-f "port=8088"`），不要用会匹配到他人进程的宽模式。

---

## 8. UI 亲验（主线亲跑，非子代理转述）

环境：临时 vite `5179` →（proxy）→ worktree 后端 `8089`；系统 Chrome（`channel:'chrome'`）。

### 8.1 AC-1 —— ✅ 通过

```
✓ AC-1(task-260904)①③④: 面板无「页签类型」；「数据源」下拉选项全部来自服务端 availableSources，
                          不含已退役的零件/外购件，且无重复 label   (24.1s)
```
用例运行期打印的**真实下拉选项**（服务端现给，非写死清单）：
```
成品其他费用 · 来料固定加工费 · 来料其他费用 · 来料回收折扣 · 电镀费用 · 自制加工费 · 组成件其他费用 · 物料BOM
```
- ✅ 无「零件」「外购件」
- ✅ 「物料BOM」**只出现一次** —— 这条判据比「恰好 N 项」更有分辨力：
  `BOM` / `零件` / `外购件` 三个坐标共用同一锚点 `MATERIAL_BOM`、label 都叫「物料BOM」，
  **退役过滤一旦失效，下拉里就会出现三个「物料BOM」**。

### 8.2 🚨 A/B 归因：`sql-view-builder.spec.ts` 在 master 上本来就是整份全红

改动波及 `task-260819` 的共享 helper `createComponentAndOpenBuilderTab`（9 个用例**全部**调用它），
故必须做 A/B，不许直接说「非本次引入」。**主线亲跑两侧全量**：

| | master（干净基线） | 本分支 |
|---|---|---|
| failed | **8** | **6** |
| passed | **0** | **2** |
| skipped | 1 | 1 |

**master 上 8 条的死法是同一个**：helper 第 41 行 `input[placeholder*="名称"]` **timeout**
—— 连用例自己的断言都没跑到。

⇒ **结论：不是回归，是净改善（0→2 通过）。** 本分支剩下的 6 条在 master 上同样红，只是死得更早。

> 🔑 **顺带暴露 `task-260819` 的一个交付事实**：该 spec 的 9 个用例**从未在真机上执行过自己的断言**
> （helper 从来就走不通）。它们不是"通过"，是"没跑到"。已同步该会话。

### 8.3 后端回归 A/B

```
本分支 -Dtest='com.cpq.task260904.**,com.cpq.builder.**,com.cpq.semanticgraph.**,ComponentService*Test'
   → Tests run: 202, Failures: 4
```
逐条归因：

| 失败用例 | 归因 | 依据 |
|---|---|---|
| `Sec35FeeTabPreviewInspectTest.ac25_expenseTabAsSixthType` | **非本次** | master 同类同方法**同样失败**（主线亲跑对照） |
| `Sec36aSemanticGraphDbTest.tombstone_retiredSemanticGraphAcs_premiseStillHolds` | **非本次** | 同上 |
| `LegacyZeroChangeAcTest.ac13_inflightQuotationsUnchanged` | 已知静态基线失效 | 见 `证据/baseline/README.md` 失效声明 |
| `LegacyZeroChangeAcTest.ac25_legacyTreeComponentsUnchanged` | 已知静态基线失效 | 同上；差异明细显示是**其他会话**对 `QT-20260830-0210` / `QT-20260901-0233` 的并发写入 |

> 🚫 **AC-13 / AC-25② 的基线不重采** —— 重采会把它退化成恒真断言（拿当前值当基线，永远相等）。

### 8.4 工作区卫生

E2E 自归档动作会覆写 `dev-docs/task-260819-取数配置器/证据/e2e/` 下 3 个 PNG（那是**他任务的证据存档**）。
主线已 `git checkout` 还原，**未提交对他人证据的改动**。
