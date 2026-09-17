# fronttask · repair-260916 小计列无法选为字段引用

> **只按本文件做前端**。验收标准以 `问题说明.md` ⑥ 的 **AC 原文**为准，本文件与 AC 有出入时以 AC 为准并报告主线。
> 写法规则、报错文案、Excel 求值口径的**唯一出处**是 `问题说明.md` ⑤ 5.1 / 5.3 与 `api.md`，本文件不复述细节。
> 视觉基准：`原型图/公式抽屉.html`（状态 A~G）。

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-1, AC-2, AC-3, AC-5, AC-6, AC-8, AC-11, AC-13 | `src/pages/component/formulaSerialize.ts` `expressionToTokens`：① 顶层 `[别名.列]`（非本页签）不再查 `subtotalCols`，一律按本行取值（`cross_tab_ref` `agg=NONE`，`match` 规则不变）；② 识别 `(小计)` 后缀 → `component_subtotal`（字段结构与现行小计引用一致，不带 `is_tab_total`）；③ 行级函数表达式内（现 `:817` 附近）同样规则：`(小计)` → `component_subtotal`，无后缀 → 来源页签列；④ 单列函数快捷路径、K 系列内层、`SUMIF` 条件与取值中出现 `(小计)` → 抛错；⑤ `(小计)` 用在未勾小计的列 / 本页签 / 无列名 → 抛错。**报错文案与判定顺序逐字按 `问题说明.md` 5.1** |
| **F-2** | AC-3, AC-4, AC-11, AC-13 | 同文件 `tokensToDrawerExpression`：`component_subtotal`（非整页签合计、列名非空）回显为 `[{页签名}.{列}(小计)]`，顶层（现 `:1119-1131`）与行级表达式内（现 `:1222-1231`）两处都改；其余 token 回显不变。回显 → 解析必须往返稳定（AC-3③、AC-13） |
| **F-3** | AC-1, AC-2, AC-3, AC-5, AC-6, AC-8, AC-10 | 同文件 `classifyRefSegment`：黄色只给「带 `(小计)` 且列确为小计列且非本页签」的块；5.1 所列非法情形标红；无后缀引用一律按明细规则判色（含函数内）。`parseFormulaSegments` 如需调整随之改 |
| **F-4** | AC-1, AC-2, AC-7, AC-9, AC-10 | `src/pages/template/tabjoin/TabFieldMatrix.tsx`：「小计列」组芯片插入 `[{ref}.{列}(小计)]`（显示文字 `{列}(小计)` 不变）；**本页签卡片（`def.self`）不渲染「小计列」组**；「明细」组、「页签总计」组、置灰与悬停提示一律不变。`TabFieldPanel.tsx` 搜索逻辑不变（AC-9 要求搜索前后插入逐字一致） |
| **F-5** | AC-10 | `src/pages/template/tabjoin/FormulaEditorPanel.tsx`：图例「小计」文字改为 `小计 · [页签.列(小计)]`；公式框占位改为 `例:[投料.金额] * [加工.工时] + [回料.金额(小计)] + [回料(总计)]`（与原型状态 A / F 逐字一致） |
| **F-6** | AC-18 | `src/pages/template/TabJoinFormulaDrawer.tsx` `buildColumn`（现 `:368-388`）：提取页签引用串时同时去掉 `(小计)` 后缀（与现有去 `(总计)` 同处理） |
| **F-7** | AC-14, AC-15 | `src/pages/quotation/buildExcelSnapshot.ts` 的 `TAB_JOIN_FORMULA` 分支：求值口径改为与后端 `TabJoinPlanEvaluator.evaluateColumn` 一致（`问题说明.md` 5.3 五条规则）。实现方式自定（可移植为纯函数，放在 `src/pages/quotation/` 下新文件）。🚫 `CARD_FORMULA` 分支不改。🚫 不改 `src/utils/formulaEngine.ts`（AC-12②）。现网 8 列在现有报价单上的值必须不变（AC-15⑤） |
| **F-8** | AC-14 | 共享对拍夹具前端副本 `src/pages/quotation/__fixtures__/tabjoin-excel-cases.json` + 读取它的测试。**夹具内容由后端工程师 B-5 定稿后逐字节拷贝**，前端不单独编写；两份 `sha256sum` 必须相同 |
| **F-9** | AC-12 | 自测：改动涉及的既有单测按 `test.md` §5「允许调整的既有断言清单」调整（只许改那两类），新增覆盖 F-1~F-4、F-7 的单测；`formulaSerialize.ts` 文件头语法说明（`:11-21`）按 5.1 更新 |

## 协议检查点（来自 `问题说明.md` 5.2，前端部分）

写代码前逐项确认位置、写完逐项勾掉，回报里附 `grep`/`codegraph` 命中输出：

- [ ] P1 顶层 `[别名.列]` 分支 → F-1
- [ ] P2 行级函数表达式分支 → F-1
- [ ] P3 单列函数快捷路径 → F-1
- [ ] P4 K 系列内层 / `SUMIF` 条件与取值 → F-1
- [ ] P5 回显两处 → F-2
- [ ] P6 着色 → F-3
- [ ] P7 文件头说明 → F-9
- [ ] P8 左栏芯片 / 本页签卡片 → F-4
- [ ] P9 图例 / 占位 → F-5
- [ ] P10 `buildColumn` → F-6
- [ ] P11 Excel 快照求值 → F-7
- [ ] 全仓再扫一遍：`/usr/bin/grep -arn "(总计)\|subtotalCols" cpq-frontend/src --include=*.ts --include=*.tsx`，确认没有第 12 处按文字判小计的地方（有则停下报告）

## 不改的文件（及原因）

| 文件 | 为什么不改 |
|---|---|
| `src/utils/formulaEngine.ts` | 计算规则不变（D-2、AC-12②）；token 结构不变 |
| `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `QuotationWizard.tsx` 等渲染主链路 | 页签公式存的是 token，不经过文字解析；本返修不改 token 结构 |
| `src/components/formula/FormulaZone.tsx` | 按 token 显示，不经过文字写法 |
| `src/pages/component/ComponentManagement.tsx` | 公式列表调用 `tokensToDrawerExpression`，随 F-2 自动生效（AC-4），本文件无需改 |

## 自检口径

- `npx tsc -b` 0 错误；改动/新增的测试文件全部通过；
- 共享 dev server（5174）服务的是主工作区代码 —— **用临时端口起 worktree 的 vite**（`/api` 代理到共享后端 8081 即可，前端改动不依赖后端改动），把改动的 `.tsx` 文件地址取回 200；
- 用完即停临时端口；声明端口 5174 留给主线。
