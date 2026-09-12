# fronttask · repair-260911 连表公式匹配键宿主侧取不到取数列

> 🚫 **只按本文件做，不要自行扩范围。** AC 原文在 `问题说明.md` ⑥ 节，本文件只标编号不复制原文。
> 🚫 **不许直接扩宽 `currentRowForEval`** —— 它同时供 `b_field` 求值消费，扩宽会外溢。要新增独立的匹配用行。

## 背景一句话

同后端：`cross_tab_ref` 匹配时，源页签行按**字段名**取、宿主行按 **driver 列名**取；`QuotationStep2.tsx:715-716` 的注释原话「仅补 INPUT default_source，**不动 BASIC_DATA/DATA_SOURCE**」正是缺陷所在。前后端严格同构，两端一起改才有意义。

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-1, AC-2, AC-3, **AC-4**, AC-5 | `utils/formulaEngine.ts`：`evaluateExpression` 增加一个**可选**的「匹配用宿主行」入参（建议名 `matchRow`），缺省回落现有 `currentRow`（保证未传的调用方逐位不变）；`aggregateRows`（`:557`）里 `match` 的宿主侧取值与 `predicate` 宿主行改用它。KSUM（`projectToHostKey`，`:681`）与外层（`:703`）两条调用路径都要传到。 |
| **F-2** | AC-1, AC-2, AC-3, **AC-9** | `pages/quotation/QuotationStep2.tsx`：**两处**求值素材构造点各构造一份 `matchRow` 并传入 —— `:716-730`（`computeAllFormulas`）与 `:1051-1064`（`buildRowMaterials` 同构副本）。<br>✅ **复用已有的 `buildResolvedRow`（`:1327`）**，不要新造解析器。<br>⚠️ **口径必须统一到后端**：`buildResolvedRow:1345` 现在是 `out[key] == null \|\| out[key] === ''` 才补（`""` 会被覆盖），而后端 `fillInputDefaultSourceByFieldName:2662` 是 `!= null` 就不补（`""` 尊重置空）。构造 `matchRow` 时**按后端口径**（键存在即权威，`""` 不补）。<br>🚫 漏改任一处 = AP-50 式「一个视图对、另一个错」。 |
| **F-3** | AC-6 | 前端共享夹具 `src/utils/__fixtures__/cross-tab-cases.json` + 其 harness：同 B-3，支持 `fields` / `driverRow` / `basicDataValues`，新增同样三条用例。<br>🚫 **与后端 `src/test/resources/cross-tab-cases.json` 内容必须逐字一致** —— 与后端代理对齐后再各自提交。<br>✅ 先跑红再修。 |
| **F-4** | AC-10 | 匹配键在宿主行取不到值时，把后端同语义的诊断写进 `outDiag.crossTabError`（该通道渲染层已有展示位，`:685-690` 附近有现成写法）。🚫 不新增 UI 组件、不改布局 —— **本任务不是页面设计类改动，不出原型图**。 |
| **F-5** | AC-7, AC-8, AC-11 | 自检：`tsc` 0 错误；`quotation` 目录单测全绿；**报价侧渲染零回归** —— 报价单侧 `COMP-0011 BOM` 的「物料成本」列仍显示 `203420.460972186`。 |

> 📌 **AC-9（序列）挂在 F-2 上**：切页签 / 刷新后取的是重新构造的求值素材，两处构造点只要有一处漏传 `matchRow`，就会出现「首次渲染对、切回来变 0」这类间歇态 —— 它正是 F-2 要防的病。

## 明确不做

| 不做项 | 理由 |
|---|---|
| 扩宽 `currentRowForEval` 让所有类型都进去（方案乙的前端形态） | A0 已否决：会外溢到 `b_field` 求值 |
| 连表公式配置抽屉（`TabJoinFormulaDrawer` / `CrossTabRefDrawer`）的任何改动 | 本次是求值期缺陷，配置期 UI 不动 |
| 新增页面 / 弹层 / 布局调整 | 无 UI 结构改动 ⇒ 不触发 `frontend.md §1.3` 的原型图要求 |

## 硬约束

- 🚫 **`CLAUDE.md` §3.2 不可逆操作红线**：本任务无需任何数据写操作。遇到需要删文件 / 清目录 / 改共享环境配置 —— **停下来报主线**，你没有批准权。
- ⚠️ 前端自检坑（记忆 `cpq-worktree-frontend-selfcheck`）：共享的 `5174` / `5090` 服务的是**主仓**不是 worktree。worktree 内自检要软链 `node_modules` + 起另一个端口的临时 vite，或直接以 `tsc` + 单测为准。
- 工作区：见派工时给出的 worktree 路径。🚫 不要 `cd` 回主仓提交。
