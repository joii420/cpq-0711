# test-report · repair-260912 · 分片 S1（AC-1 ~ AC-9）

- 执行人：test-engineer（S1，单片）
- 日期：2026-09-12 ~ 09-13（**第二轮：主线刷完存量后复跑，代码版本 `9ecefbfb`**）
- worktree：`/home/joii/project/cpq/.claude/worktrees/repair-260911-excel-tabkey`，分支 `repair/260912-excel-shape`
- 库：`cpq_db_0910`（共享）　写入面：**空**（纯只读断言；未点保存、未刷任何 `*_excel_values`）
- 证据目录：`证据/S1-测试员/`

---

## 0. 结论速览

| AC | 结论 | 一句话 |
|---|---|---|
| AC-1 编辑页 4 行 × 四列 | ✅ **PASS** | 真实浏览器实测四列逐格等于期望表 |
| AC-2 详情页同形同值 + 无子件行 | ✅ **PASS** | 24 行 / 9 个子件 → **4 行 / 首列仅四个产品**，阴性断言通过 |
| AC-3 落库 `rows`=1 且无 `treeMode` | ✅ **PASS** | 8/8 `rows`=1、无 `treeMode`；行键仅 `col_1..col_4` |
| AC-4 报价侧零回归（形态+数值） | ✅ **PASS** | 比对器 SAME；同一比对器在核价侧给 DIFF(16) |
| AC-5 核价卡片视图零回归 | ✅ **PASS** | 00003=233922.5 / 00081=5204745 / 小计 5438667.5 |
| AC-6 真无数据仍为 0 | ✅ **PASS** | 0010 的三个产品物料小计=0，同表 S0001=5438667.5 |
| AC-7 `buildLineTreeRows` 保留 + 注释 | ✅ **PASS** | 仍在 `ExcelViewService.java:270`，注释齐全 |
| AC-8 双向还原实验 | ✅ **PASS**（两个方向红→绿都实证） | 见 AC-8 节 |
| AC-9 自检证据 | 🟡 **四项齐全；仅「改造后的 TreeTest 不再 skip」待复跑** | 见 AC-9 节 |

🚩 **我报的 2 项已被处置，我已复核**：① `CostingVersionService` 第四调用点 **已切 `false`**（现 `:341`）；② `CostingExcelTreeTest` **已改造**为自建夹具 + 双向对照（**但「不再 skip」尚待复跑证实**，见 §3-B）。

📌 **第二轮复跑（代码 `9ecefbfb`，主线刷完存量后）：AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-6 / AC-7 全部 PASS。**

---

## 1. 逐条 AC

### AC-1 编辑页 Excel 视图 = 4 行 × 四列总计 ✅ PASS

- **验的是什么（AC 原文）**：打开 `QT-20260912-0011` 编辑页 → Step2 → 核价单 → Excel 视图：**4 行**，四列逐格等于实查总计表。
- **手段**：真实浏览器（Playwright + 系统 Chrome），前端 `5096` → 后端 `8095`（本 worktree）。
  用例 `cpq-frontend/e2e/repair260912-ac1-edit.spec.ts`。
- **实际观测值**（页面 DOM 实读，非端点）：

| 料号 | 元素小计 | 物料小计 | 加工费 | 单价 |
|---|---|---|---|---|
| S0001 | 489985 | 5438667.5 | 5.8 | 5438673.3 |
| S0004 | 632046 | 16459679.88 | 3.8 | 16459683.68 |
| S0008 | 677615 | 38485323.75 | 2.05 | 38485325.8 |
| S0012 | 641025 | 34249252.5 | 4.7 | 34249257.2 |

  逐格等于 AC 表。行数 = 4。料号列 = `["S0001","S0004","S0008","S0012"]`，**不含任何 BOM 子件料号**。
- **端点侧同证**：`GET /excel-view?templateId=ffae0668-…` 返回 4 行，`col_1..col_4` 与上表一致。
- **鉴别力**：改动前同一端点（master 代码实例 `8091`）返回 **四列全 `"0"` × 4 行** —— 见 AC-8②。
- **证据**：`证据/S1-测试员/AC-1-编辑页核价Excel视图.png`、`ac1-edit-dump.json`、`q1.cost.after.json`

### AC-2 详情页同形同值 + 不得出现子件料号行 ✅ PASS

- **验的是什么（AC 原文）**：同一张单详情页 → 产品明细 → 核价单 → Excel 视图：**同样 4 行、同样的值**，**不再出现 BOM 节点行**（页面上不得出现 `300012` / `00081` 这类子件料号作为独立行）。
- **手段**：真实浏览器。同一支用例 `repair260912-ac2-detail.spec.ts` 由 `R260912_STAGE` 切两种期望 —— **同一段观测代码跑 before/after，所以 after 的绿不可能是观测手段失灵造成的假绿。**

| | 首列（产品/节点） | 行数 | 子件行数 |
|---|---|---|---|
| **刷前** | `300001,300012,300013,300014,300015,00003,00081,300021,…,00017` | **24** | **9** |
| **刷后** | `["S0001","S0004","S0008","S0012"]` | **4** | **0** |

- **四列实测**（详情页 DOM）：S0001 `489985 / 5438667.5 / 5.8 / 5438673.3`；S0004 `632046 / 16459679.88 / 3.8 / 16459683.68`；S0008 `677615 / 38485323.75 / 2.05 / 38485325.8`；S0012 `641025 / 34249252.5 / 4.7 / 34249257.2` —— **逐格等于期望表，且与 AC-1 编辑页逐格相同**（E-1/E-2「两个页面一致」达成）。
- **阴性断言（用户明确要的「不要树状」）**：① 首列集合恰为四个产品；② **整张表的文本里不含 `300012` / `00081` / `300015`** —— 两条都过。
- **证据**：`AC-2-详情页核价Excel视图-post-refresh.png`（刷后）、`AC-2-详情页核价Excel视图-pre-refresh.png`（刷前对照）、`ac2-detail-{pre,post}-refresh.json`
- ⚠️ 记录一处两页面差异（**不是缺陷**）：首列表头编辑页为「料号」、详情页为「产品/节点」。

### AC-3 落库 `rows` 长度 = 1 且无 `treeMode` ✅ PASS

- **手段**：只读 SQL，脚本 `证据/S1-测试员/ac3.sh`。
- **刷后实测**：

```
QT-20260911-0010/S0001|1|no_treeMode      QT-20260912-0011/S0001|1|no_treeMode
QT-20260911-0010/S0004|1|no_treeMode      QT-20260912-0011/S0004|1|no_treeMode
QT-20260911-0010/S0008|1|no_treeMode      QT-20260912-0011/S0008|1|no_treeMode
QT-20260911-0010/S0012|1|no_treeMode      QT-20260912-0011/S0012|1|no_treeMode
✅ AC-3 PASS：8/8 个 line item 的 rows 长度 = 1 且无 treeMode 键   (exit=0)
```

- **加验（我自己的 SQL，不复用主线的）**：顶层键**只有** `rows`；行键**只有** `col_1,col_2,col_3,col_4`
  —— 连 `__nodeId` / `__hfPartNo` 等树形系统列都不残留。落库四列值与期望表逐格一致。
- **刷前对照（鉴别力）**：8/8 曾是 `7/6/6/5 行 + HAS_treeMode`；同一脚本以 `post-refresh` 口径在刷前跑 **exit=1（红）**。
  ⇒ **红→绿的转变由「刷存量」这一个动作驱动，绿是真的。**

### AC-4 报价侧零回归（形态 + 数值都不变）✅ PASS

- **手段**：端点 A/B + 自研比对器（**键集合一致 + 叶子数值等价**，`str`/`number` 归一，数值须逐位相等）。
  - **before** = master 代码实例（`8091`，`/proc/<pid>/cwd` 核对为主仓 `cpq-backend`，本次两文件 `git status` 干净）
  - **after** = 本 worktree 实例（`8095`，cwd 已核对）
- **实测**：

```
SAME  QT-20260912-0011 报价侧 rows      ← 4 行 × col_1..col_3 逐格一致
SAME  QT-20260911-0010 报价侧 rows      ← 同上
DIFF(16) QT-20260912-0011 核价侧 rows   ← 0 → 489985 / 5438667.5 / 5.8 / 5438673.3 等 16 处
```

- 🔑 **鉴别力是双重的**：
  1. **变异实验 10/10 通过** —— M0 恒等=SAME、M1 数值末位+1=DIFF、M2 少一行=DIFF、M3 多一行=DIFF、M4 多一个键=DIFF、M5 `str→num` 归一=SAME、M6 列表达式变=DIFF、M7 少一个键=DIFF、M8 微小数值差(1e-8 相对)=DIFF、M9 列数变=DIFF。
  2. **同一次运行里，同一个比对器对报价侧给 SAME、对核价侧给 DIFF(16)** —— 这比单跑变异实验更强：它排除了「比对器整体失灵」。
- **形态**：报价侧行数 before/after 均为 4，列 `col_1..col_3` 不变（比对器的 `LEN`/`KEYS` 节点覆盖）。
- ✅ **落库侧已复比（U-3 闭合）**：主线刷完存量后重新导出 `quote_excel_values.POST-REFRESH.json`，与 `PRE-REFRESH` 比对器 `SAME`，且 **`diff -q` 逐字节完全一致** —— 刷存量未波及报价侧。
- ✅ **第二轮端点复采（代码 `9ecefbfb`）**：`SAME` × 2 单，与 master 基线仍逐格一致。
- **证据**：`cmp.py`、`q*.quote.MASTER8091.rows.json`、`q*.quote.after.json`

### AC-5 核价产品卡片视图零回归 ✅ PASS

- **手段**：**直接复用 repair-260911 验收用的那支 spec**（`e2e/repair260911-ac1-ac2-ac4.spec.ts`，`R260911_MODE=after`）跑在本次改动之上 —— 同一支用例前后都绿，比另写一支更能说明「没被动到」。
- **实际观测值**：

```
料号列     = ["—","300012","300015","00081","300013","00003","300014"]   ← 仍是 7 行树形明细
物料成本列 = ["0","0","0","5204745","0","233922.5","0"]
tfoot      = 小计 ¥ 5438667.5  合计 ¥ 5438667.5
```

- 00003 = **233922.5** ✅　00081 = **5204745** ✅　小计 = **5438667.5** ✅ —— 与 AC-5 基线逐值一致。
- 📌 顺带确认了产品决策落地正确：**卡片视图保留 7 行树形明细，Excel 视图收敛成每产品一行** —— 正是 E-3「明细去卡片视图看」。
- **证据**：`证据/S1-测试员/ac5-260911rerun/`

### AC-6 页签总计确为 0 → 对应列仍为 0，不编造回退值 ✅ PASS

- **正向数据来源**：实查 `costing_card_values` 发现 `QT-20260911-0010` 的 S0004 / S0008 / S0012 **BOM 页签 `subtotal` 确为 `0`** —— 天然正向数据，无需构造。
- **实际观测值**（编辑页真实浏览器）：

```
S0001  元素=489985  物料=5438667.5  加工费=5.8   单价=5438673.3
S0004  元素=632046  物料=0          加工费=3.8   单价=3.8
S0008  元素=677615  物料=0          加工费=2.05  单价=2.05
S0012  元素=641025  物料=0          加工费=4.7   单价=4.7
```

- **阴性配套断言**（防止「整行塌成 0」也能骗过 `=0`）：同三行的元素小计/加工费必须 **> 0**（实测 632046/677615/641025 与 3.8/2.05/4.7）；且**同一张表**里 S0001 的物料小计必须是 5438667.5 —— 证明「0」不是恒真。
- 单价列 = 0 + 加工费，说明 0 **参与了运算**而不是被回退值替换。
- **证据**：`AC-6-编辑页0010.png`、`ac6-edit-dump-0010.json`

### AC-7 `buildLineTreeRows` 保留且带「暂不使用」注释 ✅ PASS

- `ExcelViewService.java:270`（第二轮复核后行号；第一轮为 `:267`）—— 方法仍在。其 Javadoc 含：

  > ⚠️ **暂不使用（2026-09-12 用户裁决：核价 Excel 视图不走树形，改为「每产品一行 + 取卡片值」，行级明细去产品卡片视图看）**……本方法与 `costingTree=true` 分支**刻意保留**，以便日后要切回树形时一行开关即可复原……🚫 不要因为「没有调用方」而删除。

- `CardSnapshotService.java:2854-2858` 的 `costingTree=true` 重载也有对应说明。
- 未见 `@Deprecated` 之外的破坏性改动。

### AC-8 双向还原实验 ✅ PASS（两个方向都完成「红 → 绿」闭环）

| 方向 | 红（改动前 / 刷前） | 绿（改动后 / 刷后） |
|---|---|---|
| ② 编辑页不传卡片值 → AC-1 必红 | **未改动的 master 代码实例**（`8091`，pid cwd 核对为主仓）同端点同单据返回 **四列全 `"0"` × 4 行** | 改动后四列 = 各页签总计，AC-1 PASS |
| ① `costingTree=true` → AC-2/AC-3 必红 | 库中存量是 `costingTree=true` 的**真实产物**：详情页 **24 行 / 9 个子件料号**、落库 `7/6/6/5 行 + treeMode:true`；AC-3 脚本 **exit=1**、AC-2 spec 以 post 口径必红 | 刷后详情页 **4 行 / 0 子件**、落库 **8/8 rows=1 无 treeMode**；两者 PASS |

- 🔑 **两个方向的「红」用的都是真实的、未经人为篡改的代码/数据状态**，不是模拟 —— 比临时改代码再改回来更硬。
- 🚫 **我全程未改任何实现文件**（原因见 §4 坑 ⑤）。
- **后端开发自测 `CostingExcelFlatShapeSelfCheckIT`**（覆盖 AC-8①），**我已独立跑过**（不采信其汇报）：
  `restoreExperiment_treeSwitchDrivesShape` / `flatShape_oneRowPerProduct_withRealSubtotals` / `zeroSubtotalTab_stillRendersZero` **三条全 PASS**。

### AC-9 自检证据 🟡 四项齐全，仅一条待复跑

- **后端测试**（第一轮我亲跑，`_JAVA_OPTIONS` 传参，profile=test → `cpq_db_test`）：

```
CostingExcelFlatShapeSelfCheckIT, CostingExcelTreeTest, CostingExcelTreeTabKeyIT, GetExcelViewCostingIT,
ExcelViewCardFormulaIT, ExcelViewTabJoinFormulaIT, ExcelDryRunIT, ExcelViewServicePrecisionTest,
ExcelViewRawSourcePrecisionIT, SaveDraftExcelSnapshotTest
⇒ Tests run: 16, Failures: 0, Errors: 0, Skipped: 1   BUILD SUCCESS
```

  ⏸ **待复跑**：`CostingExcelTreeTest` 此后被改造（见 §3-B），**它是否真的不再 skip 必须再跑一次才算数**。
  未跑的原因：跑 `@QuarkusTest` 会抢 `target/`，导致主线正在用的 `8097` 短暂不可用 —— 需要主线给一个窗口。
- **dev server 探活**：前端 `5096` → **200**（pid 41497，cwd = 本 worktree/cpq-frontend）；后端 `8097` → **401**（pid 45091，cwd = 本 worktree/cpq-backend，uat → `cpq_db_0910`）
- **截图**：AC-1 ✅ / AC-2 刷前 ✅ / **AC-2 刷后 ✅** / AC-6 ✅ / AC-5 ✅
- **报价侧比对**：端点 `SAME` × 2（两个代码版本各一轮）+ 落库 **逐字节一致**

---

## 3. 🚩 我报出的 2 项 —— 均已被处置，复核结果如下

### 3-A 缺陷：B-1 漏改第四个调用点 → ✅ **已修复并复核**

```
【现象】核价单「切换料号版本」这条路径仍会把核价 Excel 值写成 BOM 树形。
        CostingVersionService.java:336-337 调用
          cardSnapshotService.buildExcelValues(li, templateId, q.customerId, newCostingCardValues, hasTreeTab)
        其中 hasTreeTab = cardSnapshotService.templateHasTreeTab(templateId)。
        核价通用1 模板确有树页签（其 costing_card_values 的 BOM 页签 baseRows 带 __nodeId/__lvl/__parentId，
        7 个 spine 节点），⇒ 该实参预期为 true ⇒ 仍走 costingTree=true 的树形分支。
        该文件本次未被改动（git status 为空）。
【预期】E-2 / AC-2「详情页不再按 BOM 节点树状展开」；backtask B-1 原文：
        「三处都要改，漏一处就是『有的单树形有的单不是』。请自己 grep 确认没有第四处。」
        —— 这就是那个第四处。
【复现】1. 对已交付代码全工程 grep `buildExcelValues\s*\(`
        2. 命中 4 个生产调用点：CardSnapshotService:1226 / :1408 / :2102（均已改 false）
           + CostingVersionService:336（未改，传 hasTreeTab）
        3. 走「核价单 → 切换料号版本」路径后读该 line 的核价 Excel 值
        频率：静态必现（代码事实）；运行时未构造验证（切版本是写操作，超出 S1 空写入面）
【影响】一般~严重（取决于消费方）。它写的是 costing_order.costing_render 而非
        quotation_line_item.costing_excel_values，所以**不违反 AC-3 的字面**；
        但「切一次版本，树形就回来了」与用户裁决直接冲突，且会造成同一批单据形态不一致。
【证据】/usr/bin/grep -arnE 'buildExcelValues\s*\(' 输出（见上）；
        CostingVersionService.java:333-337 源码片段；
        costing_card_values 中 S0001 BOM 页签 7 个 __nodeId 节点的 SQL 结果。
【建议】方向（不下结论）：① 若 costing_render 的核价 Excel 也要跟随新形态，此处同样传 false；
        ② 若该路径另有树形需求，需在文档里显式登记「切版本后形态与详情页不同」并让用户裁决。
        请开发确认 templateHasTreeTab(ffae0668-…) 的实际返回值 —— 我未读实现，无法自行断定。
```

✅ **复核（2026-09-13，代码 `9ecefbfb`）**：已按方向 ① 修复 —— `CostingVersionService.java:340-341` 现为
`cardSnapshotService.buildExcelValues(li, templateId, q.customerId, newCostingCardValues, false)`。
**全工程 `buildExcelValues(` 生产调用点复查 = 4 个核价点全部传 `false`**
（`CostingVersionService:340` / `CardSnapshotService:1226` / `:1408` / `:2102`），
报价侧两处（`:1171` / `:1399`）走 4 参重载不带 `costingTree` ⇒ **无第五处遗漏**。
⚠️ 「切版本」这条路径的**运行时**行为我仍未验证（写操作，超出 S1 空写入面）。

### 3-B 假绿风险：`CostingExcelTreeTest` 恒跳过，且其语义已被本次改动推翻 → 🟡 **已改造，待复跑证实**

```
【现象】com.cpq.quotation.service.CostingExcelTreeTest.costingExcelSnapshotIsBomTree
        在本 worktree 与后端 agent 的运行里都是 SKIPPED，原因：
          org.opentest4j.TestAbortedException: Assumption failed: 无 3120018220 核价 line item，跳过
        该测试的名字与断言方向是「核价 Excel 落库快照**是 BOM 树**」——
        正是本次改动**故意推翻**的语义。它本应变红，却因夹具缺失而静默跳过。
【预期】一个断言「形态 = 树」的测试，在「形态改为非树」的改动之后，
        要么被改语义、要么被显式标注废弃 —— 不该以 SKIPPED 混在 BUILD SUCCESS 里。
【复现】1. 在 worktree/cpq-backend 跑
          _JAVA_OPTIONS="-Dquarkus.flyway.migrate-at-start=false -Dcpq.dataset.schema-check.enabled=false" \
          ./mvnw -o test -Dtest=CostingExcelTreeTest
        2. 结果：Tests run: 1, Skipped: 1, BUILD SUCCESS
        3. target/surefire-reports/TEST-...CostingExcelTreeTest.xml 里可见 assumption 文本
        频率：必现（夹具料号 3120018220 在 cpq_db_test 不存在）
【影响】一般。它不会造成线上问题，但会让「全绿」失去意义：
        本次这类形态变更**正是它该拦的**，而它拦不住任何东西。
        与 MEMORY/DEC-0007「恒跳过 = 假绿」、BL-0276「恒跳过故障族」同型。
【证据】surefire XML 的 <skipped type="org.opentest4j.TestAbortedException"> 节点原文（见上）；
        本次独立跑的汇总 Tests run: 16, Skipped: 1。
【建议】方向（不下结论，需主线裁决）：
        ① 改其语义为「落库为每产品一行、无 treeMode」，并补一个 cpq_db_test 里存在的夹具；
        ② 或显式标 @Disabled + 注明「语义已被 repair-260912 推翻」，并登记 BACKLOG；
        🚫 不建议原样留着 —— 它现在是一个「永远不会红」的空位。
```

🟡 **复核（2026-09-13）**：该文件已被改造（`git status` 显示 `M`），做法比我建议的更进一步 ——
改为**自建夹具 + `@TestTransaction` 回滚**，不再依赖 `3120018220`；并新增
`treeSwitchIsWiredAndDiscriminating`（`true → spine 3 行` / `false → 每产品一行` 双向对照）。
Javadoc 里写明了「2026-09-12 repair-260912 改造（用户裁决），改造前后的差别」。
🚨 **但「改造后它真的不再 SKIP」这件事我还没跑过** —— 恒跳过族的教训恰恰是「看起来改好了」不等于「真的会跑」。
**请给我一个占用 `target/` 的窗口，我跑一次确认 `Skipped: 0`。**

---

## 4. 我规避掉的坑

| # | 坑 | 规避方式 |
|---|---|---|
| ① | **探到 401 却是别的实例** | 每次采样前 `ss -ltnp` 取 pid → `ls -l /proc/<pid>/cwd` 核对在本 worktree；前端另核「经 5096 代理打 `/api` 得 401」证明代理指向 8095 |
| ② | **`table.first()` 抓到「基本信息」表** | `excelTable()` 遍历页面全部 `<table>`，按 **thead 同时含「元素小计」+「单价」** 判定 |
| ③ | **表头行被 tbody 重复渲染** → 首轮误判「5 行」 | 只丢弃「第一行且与 thead 逐格相同」的那一行；之后的重复行一律保留，避免掩盖真实重复渲染缺陷 |
| ④ | **表头带 `[col_N]` 前缀 + 按行序定位会静默错位** | 表头按后缀匹配；数据行**按「料号」列定位**并强制「恰好命中 1 行」 |
| ⑤ | **改实现做还原实验会踩后端 agent** | 实测后端 agent 在同一 worktree 跑 `8095` + 正在改同两个文件（md5 `f63f3d8d`→`b9d7f76c`）。改用「真实 master 实例」与「库中存量」作为还原对照，**全程未改任何实现文件**；另把两文件做了双份备份（scratchpad + `.tmp-s1-bak/`）以备误伤。等它退出后我另起 `8097` 作为自己的可控实例 |
| ⑥ | **落库值与端点值精度不同**（`203420.599086532` vs `203420.599086531655`） | AC-4 分两条线各自比 —— 落库比落库、端点比端点，不交叉，避免把既有精度差异误报成回归 |
| ⑦ | **截图可能验了恒真的东西** | 截图前先把所有横向滚动容器 `scrollLeft = scrollWidth`，确保目标列进入像素 |
| ⑧ | **Playwright `test-results/` 每轮被清空** | 证据统一落 `e2e/repair260912-out/` 并复制到任务目录 `证据/S1-测试员/` |
| ⑨ | **共享库全局计数断言** | 全部断言限定到具体单据 + 具体料号 + 具体列；零全局计数 |
| ⑩ | **默认 `global-setup.ts` 会 UPDATE `user` 表** | 自建 `repair260912.config.ts`，刻意不挂 globalSetup |
| ⑪ | **不稳定失败误判成产品缺陷** | 三次随机失败（login timeout / 切视图 timeout）先查基础设施 → 实证是 8095 被后端 agent 重启，**未记为缺陷** |

---

## 5. 未验证 / 阻塞项（原样列出，不含推测）

| # | 项 | 状态 |
|---|---|---|
| ~~U-1 AC-2~~ | 详情页含阴性断言 | ✅ **已闭合**（刷后 4 行 / 0 子件） |
| ~~U-2 AC-3~~ | 落库形态终判 | ✅ **已闭合**（8/8 rows=1 无 treeMode） |
| ~~U-3~~ | 刷后报价侧落库复比 | ✅ **已闭合**（`diff -q` 逐字节一致） |
| ~~U-4~~ | 后端测试类结果 | ✅ 第一轮已亲跑 16/0/0/1skip |
| ~~U-6~~ | 最终代码版本复验 | ✅ **已闭合**（`f63f3d8d` / `b9d7f76c` / `9ecefbfb` 三个版本均复跑，结论一致） |
| **U-8** | **改造后的 `CostingExcelTreeTest` 是否真的不再 SKIP** | ⏸ **待跑** —— 需占用 `target/`（会让主线在用的 `8097` 短暂不可用），**等主线给窗口**。恒跳过族的教训就是「看起来改好了 ≠ 真会跑」 |
| **U-5** | `CostingVersionService`「切料号版本」路径的**运行时**行为 | ⏸ 未验证 —— 切版本是写操作，超出 S1 的空写入面。静态已确认传 `false` |
| U-7 | 核价 Excel **导出** | test.md §4 已声明本次不覆盖 |

📌 **代码版本纪律**：本报告结论覆盖三个代码版本 —— `f63f3d8d`（首轮）、`b9d7f76c`（B-2 调整后）、
**`9ecefbfb`（当前，含第四调用点修复 + TreeTest 改造）**。
判据：`git diff -- cpq-backend/src/main/java/com/cpq/quotation/service/ | md5sum`。
AC-1/2/3/4/5/6/7 在 `9ecefbfb` 上**全部实跑过**，不是沿用旧轮结论。

## 6. 已自检（一行）

前端 `5096` → **200**（pid 41497，cwd = 本 worktree/cpq-frontend）✅ ；后端 `8097` → **401**（pid 45091，cwd = 本 worktree/cpq-backend，uat → `cpq_db_0910`）✅ ；
后端测试 **Tests run: 16, Failures: 0, Errors: 0, Skipped: 1 — BUILD SUCCESS**（亲跑；改造后的 `CostingExcelTreeTest` 待复跑，U-8）✅ ；
AC-4 比对器变异实验 **10/10** + 同轮报价侧 `SAME` / 核价侧 `DIFF(16)` + 落库 `diff -q` 逐字节一致 ✅ ；
AC-3 脚本刷前 **exit=1（红）** → 刷后 **exit=0（绿）** ✅ ；
Playwright S1：AC-1 ✅ / AC-2（刷前红对照 + 刷后绿）✅ / AC-5 ✅ / AC-6 ✅ ；
代码版本 `9ecefbfb`；`mvnw test` 全量恒有 `mat_*` 红，非验收门槛。
