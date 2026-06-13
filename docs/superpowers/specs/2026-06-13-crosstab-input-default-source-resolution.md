# cross_tab_ref 对 INPUT+default_source 字段不解析致全 0 — 问题报告 + 修复方案 Spec

> 状态：草案（待评审）｜日期：2026-06-13｜模块：报价单渲染 / cross_tab_ref 求值（🔒 三大核心基线）
> 触发来源：报价单 `QT-20260613-1705` 来料页签 `材料费` 全部料件行 = 0
> 关联：[[AP-55]]（cross_tab_ref 协议传播）/ [[AP-56]]（多 source + KSUM）/ 基线文档《三大核心模块基线》§报价单渲染

---

## 1. 摘要（一句话）

cross_tab_ref 求值前的"行解析"只把 `field_type ∈ {FORMULA, BASIC_DATA, DATA_SOURCE}` 的字段按**字段名**写回行值；而 **INPUT 型（INPUT_TEXT / INPUT_NUMBER）+ 带 `default_source` 绑定**（值来自驱动 `$view` 列）的字段被漏解析 —— 其值只留在 `basicDataValues` 的驱动 path 键下，从未写进 `行[字段名]`。cross_tab 的 `match` / `targetExpr` 按**字面字段名**查行（`行['料件']` / `行['单价']`），全部取到 `undefined` → 源行匹配不到宿主键、目标列取不到值 → **SUM / KSUM / 所有聚合 = 0**。

**性质**：既有（pre-existing）缺陷，非 2026-06-12 多 source/KSUM 改动引入；影响**任何**引用"INPUT+default_source 列"的 cross_tab 公式。本次用户配置的 `材料费` 公式只是把它暴露出来。

---

## 2. 复现场景（真实数据）

- 报价单：`QT-20260613-1705`（DRAFT，line item `d1f7bc28...`，产品 主料1 / partNo `3120018220`，客户 苏州西门子 `8000137`）
- 模板：`报价模板0608`（`d66197c6...`，PUBLISHED）
- 宿主组件：`COMP-0028 来料`（`3cb220be...`，driver `$ll_view`，row_key_fields `["料件"]`）
- 公式（`材料费计算公式`，配在 来料 的 `材料费` 列）：
  ```
  SUM([元素.重量(g)] / 1000 * [元素.单价] * (1 + [元素.损耗率] / 100) + KSUM([外购件.费用])) * [组成用量]
  ```
- 源页签：`元素`（`ad99c10d`，driver `$ys_view`，rowKey `["料件","元素"]`）、`外购件`（`1f82da1b`，driver `$wgj_view`，rowKey `["料件"]`）

**症状**：来料**所有**料件行（料4/5/6/7/8/9）`材料费` = 0（`加工费` 同因也 = 0）。

---

## 3. 事实核查（逐层，已验证）

| # | 核查项 | 结论 |
|---|---|---|
| 1 | 组件 `COMP-0028.formulas` 的 `材料费` token | ✅ 正确（外层 `SUM(元素)` + `KSUM(外购件)` 折叠 `projectToHostKey:true` + 顶层 `* [组成用量]`） |
| 2 | 发布模板 `d66197c6` 的 `components_snapshot` | ✅ 含 `projectToHostKey`（KSUM token 正确进快照） |
| 3 | 源数据 | ✅ 元素 6 行、外购件 4 行（料9 的 加工费/单价/运费/包装费）、来料 `组成用量`=1/2（**非 0**） |
| 4 | 时间线（UTC） | ✅ 公式更新 `00:16` → 模板发布 `00:23:05` → 报价单 card 快照 `00:23:42`，均在代码合并（`00:04`）之后 → **非陈旧快照，是新代码算出的真 0** |
| 5 | 求值引擎本身 | ✅ **正确**：真实数据按**字段名键**喂 `buildCrossTabRows` → 料8=**94.5**、料4=**23.625**（手算一致） |
| 6 | 驱动路径复现 | ❌ **复现 bug**：真实 driverRow（`_` 键）喂 `buildCrossTabRows` → 料8/料4 材料费=**0**，crossTabRows 源行键 = `[_元素,_单价,_料件,_损耗率,_重量(g)]`（全下划线） |

> 复现脚本思路：构造 `元素/来料/外购件` 三个 `NORMAL` 组件 + `lookupExpansion` 返回 driverRow（`_` 键），调用导出的 `buildCrossTabRows`，比对 `store[来料].材料费`。两组对照（字段名键 vs `_` 键）分别得 94.5/23.625 与 0/0。

---

## 4. 根因（精确）

### 4.1 字段绑定（设计正确，非误配）

`料件` 字段（`INPUT_TEXT`）配置：
```json
{ "name": "料件", "field_type": "INPUT_TEXT",
  "default_source": { "path": "$ll_view._料件", "type": "BASIC_DATA" } }
```
`$ll_view` SQL：`mm.material_name _料件`。驱动列**有意命名为 `_料件`**（避免"驱动列名 == 字段名 → 不能当行键"的撞名约束）；字段 `料件` 经 `default_source.path` 绑定到该列。`单价`/`重量(g)`/`损耗率`（INPUT_NUMBER）、`外购件.费用`、`来料.组成用量` 同理。

### 4.2 缺陷点（"写死按字段名取" vs "按字段名找绑定栏位"）

cross_tab 引擎的匹配规则 = **字面属性查找**：
- `match:[{a,b}]` → `hits = 源行.filter(ar => keyEq(ar[a], 宿主行[b]))`，即 `源行['料件']` / `宿主行['料件']`；
- `targetExpr` 的 `field` → 从行的 `aFieldValues[字段名]` 取（`aFieldValues` 由该行自身键构建）。

它**假定行对象的键 == 字段名**。缺陷分布是**四象限非对称**的（2026-06-13 逐行追代码核实，下表是本 spec 的事实基准，**修订前的"前后端对称缺陷"说法已作废**）：

| 象限 | 代码点 | 现状 | 是否缺陷 |
|---|---|---|---|
| **前端·源行** | `buildResolvedRow`（`QuotationStep2.tsx:732-758`，喂 `crossTabRows` 源行） | 仅 `field_type ∈ {FORMULA, BASIC_DATA, DATA_SOURCE}` 按字段名写回；INPUT+default_source **不处理**；`out` 初值 `{...driverRow, ...row}` 只含 SQL 别名键 `_料件`/`_单价` | ❌ **缺陷** |
| **前端·宿主行** | `computeAllFormulas`（`QuotationStep2.tsx:649` 把裸 `row` 当 `currentRow` 传 `evaluateExpression`） | 宿主 `currentRow` = 原始 `row`（驱动行无手动编辑时 ≈ `_` 键 / 空），未按 default_source 解析为字段名键 | ❌ **缺陷** |
| **后端·源行** | `CardSnapshotService.buildResolvedRows`（`:860`）→ **`FormulaCalculator.resolveRowByFieldName`（`:736`）** | `:753-779` **已正确处理** `INPUT_NUMBER/INPUT_TEXT/INPUT + default_source(GLOBAL_VARIABLE/BNF_PATH/BASIC_DATA)`，按字段名写回 `out.put(name,…)`，文本保留 | ✅ **不缺陷（现成基准）** |
| **后端·宿主行** | `FormulaCalculator.computeRows`（`:553` `ctx.currentRowRaw = toRawRowMap(mergedRow)`，`toRawRowMap` 见 `:1145`） | 只解包 `mergedRow = driverRow ⊕ editValues`，**不按字段名解析 default_source**；宿主 `料件` 缺失 | ❌ **缺陷** |

**后端链路实证**（`CardSnapshotService.java:760-780`，决定性证据）：
```
crossTabRows = {}                                   // 空起步
for 组件 in 拓扑序:
    formulaResults = calculate(..., crossTabRows)   // :770  求值,用已存兄弟行；宿主行=currentRowRaw(缺陷)
    resolved       = buildResolvedRows(...)         // :775 → resolveRowByFieldName(已正确)
    crossTabRows.put(cid, resolved)                 // :778  存回供下游引用
```
⟹ 后端存进 `crossTabRows` 的**源行已齐**（`元素` 行含 `料件/单价/重量(g)/损耗率`）；落库 `材料费`=0 的**唯一原因是宿主行**（`currentRowRaw['料件']`=undefined → `match b` 取空）。**故后端只需修宿主行 `currentRowRaw`，`resolveRowByFieldName` 不动。**

**关于"`computeAllFormulas:565-611` default_source 解析仅 INPUT_NUMBER"**：此现象属实，但那段写的是 **numeric `fieldValues`**，而 cross_tab 的 match 键 b 与目标列**都从行对象取**（`formulaEngine.ts:292` `hostRow[p.b]`、`:333/336` `Number(ar[k])`），**不读 `fieldValues`**；`料件` 是文本，`parseFloat` 必 NaN 进不了 `fieldValues`。⟹ **扩这段到 INPUT_TEXT 对修复零贡献，是死代码，本 spec 不做**（宿主自有的 `组成用量` 等 INPUT_NUMBER 走该段已正常，无需改）。

⟹ 前端 `源行['料件']`/`宿主行['料件']`/`源行['单价']` + 后端 `宿主行['料件']` 取 `undefined` → 源行匹配不到宿主键、目标列取不到值 → 所有聚合（SUM/KSUM/NONE）归 0。

### 4.3 为何"既有缺陷直到现在才暴露"

- 这些代码点（`buildResolvedRow` 的 field_type 分支、cross_tab 字面匹配、driver 列命名、computeAllFormulas 的 INPUT_NUMBER-only default_source 解析）在 2026-06-12 KSUM 改动**前后逐字未变**。
- KSUM 求值器 / 序列化 / validator 均正确（单测 263+98 全绿、前后端对拍 18 类 diff 空、隔离算出 94.5）。
- 用户首次把 cross_tab 公式配在"引用 INPUT+default_source 驱动列"的页签上，触发了这条既有路径。

---

## 5. 修复方案

### 5.1 原则

把"喂给 cross_tab 的行解析"从**"假定行键==字段名"**改为**"按字段名解析其绑定栏位的值，写回字段名键"** —— 即对 **INPUT_TEXT / INPUT_NUMBER + `default_source`(BASIC_DATA / BNF_PATH / GLOBAL_VARIABLE)** 字段，也按字段名把绑定值（文本保留文本、数值保留数值）解析进行对象的字段名键。复用既有解析逻辑，不新造解析规则。

> 不改 cross_tab 的字面匹配算法本身（它对"键==字段名"的行是对的）；而是**保证喂进去的行已按字段名解析齐全**。
>
> **以后端 `FormulaCalculator.resolveRowByFieldName`（`:736`）为前后端共同基准**——它的字段优先级是既定正确实现：`editValues/手填 > driverRow[name] > default_source(三子类型) > content`。前端 `buildResolvedRow` 逐条对齐它；后端宿主行最小侵入补齐。靠对拍夹具锁一致。

### 5.2 改动点（按四象限收窄，**不再"全量对称"**）

| 象限 | 文件 | 改动 | 与初稿差异 |
|---|---|---|---|
| **前端·源行** | `QuotationStep2.tsx` `buildResolvedRow` | 抽共享 helper `resolveRowFieldNames(fields, row, driverRow, basicDataValues)`（= 现 `buildResolvedRow` 的 BASIC_DATA/DATA_SOURCE 逻辑 + **新增 INPUT_TEXT/INPUT_NUMBER+default_source 分支**：行内该字段空时按 `default_source` 解析——BASIC_DATA/BNF_PATH→`basicDataValues[bnfDriverLookupKey(path)]`；GLOBAL_VARIABLE→`@gvar:CODE`，文本保留）。`buildResolvedRow = resolveRowFieldNames + FORMULA 用 formulaCache 叠加`。优先级对齐后端 `resolveRowByFieldName`。 | 同初稿，新增 INPUT 分支 ✅ |
| **前端·宿主行** | `QuotationStep2.tsx` `computeAllFormulas` | **删初稿 item ①（扩 fieldValues 到 INPUT_TEXT，死代码）**。只做：用上面的 `resolveRowFieldNames` 生成"按字段名解析后的 currentRow"，作为 `evaluateExpression`（`:649`）的 `currentRow` 参传入；**裸 `row` 的其余读取通路（`:559`/`:629` 等）保持不动**，不污染既有逻辑。 | ⚠️ **删 item ①**；item ② 改为复用共享 helper |
| **前端·渲染/详情** | `ReadonlyProductCard.tsx` / `ComponentCell.tsx`（若共用解析） | 因复用 `buildResolvedRow`/`computeAllFormulas` 自动同源（AP-50：三视图一致），核查无独立解析分叉即可 | 同初稿 |
| **后端·宿主行** | `FormulaCalculator.java` `computeRows`（`:553`） | **方案 B（最小侵入）**：保留 `toRawRowMap(mergedRow)`，其后**增量补** INPUT 型 default_source 三子类型（GLOBAL_VARIABLE/BNF_PATH/BASIC_DATA）按字段名解析进 `currentRowRaw`（仅当 `currentRowRaw[name]` 空时写入，不覆盖手填/driver 值）。复用 `defaultSource(f)`/`lookupBdv`/`bnfDriverLookupKey`，文本保留。 | ⚠️ 范围从"行上下文+源行"收窄为**仅宿主行** |
| **后端·源行** | `FormulaCalculator.resolveRowByFieldName`（`:736`） | **不动**（`:753-779` 已正确处理 INPUT+default_source） | ⚠️ **初稿误列为需改，删除** |
| 对拍 | `cross-tab-cases.json`（前后端各一份） | 新增"源行/宿主行仅有 `_驱动键` + 字段带 default_source → 解析后聚合正确"用例（覆盖 INPUT_TEXT 行键 + INPUT_NUMBER 目标列两类），diff 逐字一致。 | 同初稿 |

### 5.3 不变量（务必保持）

- N=1 单 source / 既有 cross_tab（键本就==字段名，如手填/BASIC_DATA 字段）**逐字不变**（既有 CrossTab 测试零回归）。
- 文本行键解析**保留字符串**（不 parseFloat，cross_tab 匹配键需原始文本，参见 `resolveBasicDataForRow` 注释）。
- 用户**手动编辑值**优先级高于 default_source 解析值（仅当行内该字段空时才用绑定值）。
- 不破坏 AP-51 driver 行数权威 / AP-54 写路径下标。

### 5.4 验收（测试落点在**行解析层**，不只在求值器层）

> 关键纠偏：本 bug 在**行解析层**（`buildResolvedRow` / `currentRow` / 后端 `currentRowRaw`），**求值器本身正确**（真实数据按字段名键喂 → 料8=94.5 已证）。只在求值器层加对拍夹具（喂"已解析行"）**抓不住这类回归**——必须先在解析层断言。

- **TDD（解析层先红后绿）**：
  - 前端：`resolveRowFieldNames`/`buildResolvedRow` 单测——喂 driverRow 含 `_料件`/`_单价` + basicDataValues + `料件`(INPUT_TEXT,default_source.BASIC_DATA→`$ll_view._料件`)/`单价`(INPUT_NUMBER) 字段定义 → 断言输出含 `料件`(文本)/`单价`(数值)。
  - 后端：补 `currentRowRaw` 构建单测（driverRow 含 `_料件` + bdv → 期望宿主行含 `料件`）；既有 `ResolveRowByFieldNameTest`（源行）保持绿。
- **集成（贯穿）**：前端把事实核查 item 6 的手工复现固化——driver `_` 键 → `buildCrossTabRows`→`computeAllFormulas` → 料8=94.5 / 料4=23.625，先红后绿。
- **前后端对拍**：`cross-tab-cases.json` 新增用例两端 diff 空、结果一致（覆盖 INPUT_TEXT 行键 + INPUT_NUMBER 目标列）。
- **真机**：`QT-20260613-1705` 来料 `材料费` 各料件行算出正确值（料8=94.5 等，外购件无该料件时 KSUM=0 不影响元素项）；三视图（编辑/详情/核价单不渲染该 token）一致。
- **E2E**：`quotation-flow.spec.ts` 加载中=0 零回归；协议级改动强制跑。

---

## 6. 影响面 / 待确认

1. **范围**：影响**所有**引用 INPUT+default_source 驱动列的 cross_tab 公式（同模板 `加工费` 也属此类，但 `加工费` 用 `agg=NONE` 且 `自制/组装加工费` 每料件多工序 → 即便解析后仍可能触 NONE 多命中 ERR，**这是语义问题，需产品单独确认，不在本次行解析修复范围**）。
2. **触 🔒 cross_tab 基线**：改 `buildResolvedRow` / `computeAllFormulas`(currentRow) / 后端 `computeRows`(currentRowRaw) → 走 architect 评估 + 全套对拍/E2E 回归。**后端 `resolveRowByFieldName` 不动**（已正确），收窄 diff 即收窄回归面。
3. **回归面提示（方案 B 取舍）**：后端选 B（增量补 INPUT default_source，不复用 `resolveRowByFieldName`）→ 宿主解析与源行解析仍是两份逻辑，**靠对拍夹具锁两者对 INPUT+default_source 的行为一致**，防后续分叉。
4. **待排查**：是否还有其它 `field_type` / default_source 子类型存在同款"未按字段名解析进 cross_tab 行"的漏洞（INPUT default_source 的 DATABASE_QUERY/HTTP_API 子类型当前前后端均不解析为字段名键，需确认是否有此配法）。
5. **数据迁移**：无（不改 schema、不改 token 模型；纯求值前行解析修正）。

---

## 7. 结论

- 这是 cross_tab 求值前**行解析的既有缺陷**（INPUT+default_source 字段未按字段名解析进 cross_tab 用的行），不是 KSUM 改动引入，也不是用户配置错误（`_驱动列` 命名 + default_source 绑定是正确用法）。
- 缺陷分布是**四象限非对称**的（见 §4.2）：前端源行+宿主行、后端宿主行 = 缺陷；**后端源行 `resolveRowByFieldName` 已正确，是前后端共同基准，不动**。
- 修法 = 让行解析"按字段名找其绑定栏位的值并写回字段名键"——前端抽共享 `resolveRowFieldNames` 同喂源行与宿主 currentRow（删初稿 fieldValues 死代码）；后端走方案 B 最小侵入补 `currentRowRaw`；测试落点在**行解析层**；对拍 + E2E 锁。

---

## 8. 评审修订记录（2026-06-13）

逐行追代码后对初稿的三处校正（评审实证：`CardSnapshotService.java:760-780` 后端链路 + `FormulaCalculator.java:736/553` + `formulaEngine.ts:292/333`）：
1. **后端源行非缺陷**：`resolveRowByFieldName`(`:753-779`) 已处理 INPUT+default_source → 初稿"前后端对称缺陷"作废，改四象限表（§4.2）；后端只改宿主行 `currentRowRaw`。
2. **删前端 fieldValues 死代码**：初稿 §5.2 item ①（扩 fieldValues 到 INPUT_TEXT）对 cross_tab 无效（match/target 读行对象不读 fieldValues）→ 删除，宿主侧只做"解析后的 currentRow"。
3. **测试落点纠偏**：求值器层对拍抓不住此回归 → 验收前置到行解析层单测（§5.4）。
4. **后端取舍定档**：宿主行 `currentRowRaw` 选**方案 B**（增量补 INPUT default_source，不复用 `resolveRowByFieldName`，最小侵入）。
