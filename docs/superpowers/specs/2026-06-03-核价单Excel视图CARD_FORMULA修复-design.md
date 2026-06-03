# 核价单 Excel 视图 CARD_FORMULA 取值 + 公式校验 修复 — 设计方案

- 日期：2026-06-03
- 状态：已与用户确认，待写实现计划
- 关联：`docs/superpowers/specs/2026-06-02-Excel视图卡片引用公式配置-design.md`（CARD_FORMULA 原始设计）

## 0. 现象
报价单（如 QT-20260603-1528，核价模板「核价模板0603 v1.5」配了 A/B/C 三列 CARD_FORMULA）的**核价单 Excel 视图** A/B/C 全显示「—」。报价单 Excel 视图、核价单产品卡片视图均正常有数据。

## 1. 根因（三层，均已用 DB/code 坐实）
1. **后端无核价计算通路**：`ExcelViewService.getExcelView(quotationId)` 写死 `templateId = lineItems.get(0).templateId`（=报价模板 e88353ec），**从不按核价模板（a4e67fc6）计算**。前端核价单 Excel 视图（`LinkedExcelView linkedTemplateId=核价模板` → 新模型 → `useBackendExcelRows` → `getExcelView`）拿回的是报价模板的列/值，渲染核价模板的 A/B/C → 对不上 → 全「—」。Plan 2 做 `useBackendExcelRows` 时只接了报价侧。
2. **组件实例 id 对不上**：核价模板 CARD_FORMULA 的 `refs.tab = b3359f70:2`（核价模板 template_component「元素」，component code `COMP-0020__imp1__imp1`）；但该报价单卡片数据只存在 `d18ac7e4:2`（报价模板「元素」，code `COMP-0020__imp1`）。全库无任何按核价模板组件 id 持久化的卡片数据（`b3359f70` = 0 行）。`quotation_line_component_data` 无 quote/costing 侧区分列——卡片数据只有一份，按报价/快照组件 id 存；核价单卡片视图是把这份数据**按位置(sort_order)映射**进核价模板结构渲染。`CardDataProvider` 用精确 `componentId:sortOrder` 匹配 → 查不到。
3. **公式可写错且无校验/试算**：B 列 `[SUM_OVER([元素] WHERE c0=='非银点类', c1+c2)]` 聚合外层多包 `[]`（手工误加，选择器本身不会加）。系统保存时未拦截，用户也无法验证自己写的对不对。

## 2. 修复 A — 后端核价侧 Excel 计算通路
- `ExcelViewService.getExcelView(UUID quotationId, UUID templateIdOverride)`：`templateIdOverride` 非空时用它，否则沿用 `lineItems[0].templateId`（向后兼容）。
- 资源端点 `GET /quotations/{id}/excel-view` 增加可选 query 参数 `templateId`：`getExcelView(id, templateId)`。
- 前端 `useBackendExcelRows` 入参增加 `templateId`，调用 `quotationService.getExcelView(quotationId, templateId)`；`LinkedExcelView` 新模型分支把本视图的 `templateId`（报价视图=报价模板、核价视图=核价模板 `costingCardTemplateId`）透传。
- 求值数据域仍是该报价单 line items + 现有卡片数据（同一份，见修复 B 解决 keying）。

## 3. 修复 B — 组件实例 id 映射（CardDataProvider keying 回退）
- `CardDataProvider` 构造时除 `byTab[componentId:sortOrder]` 外，再建 `bySort[sortOrder] = QuotationLineComponentData`（同 line item 内 sort_order 唯一）。
- `rowsOf/subtotalOf(tabKey)`：
  1. 先按完整 `tabKey`(=`componentId:sortOrder`) 精确查；
  2. 查不到 → 从 `tabKey` 末段解析 `sortOrder`，按 `bySort` 回退匹配（核价 `b3359f70:2` → 实际 `:2` 的「元素」数据 `d18ac7e4`）。
- 依据：核价单卡片视图本就按 sort_order/位置映射同一份卡片数据，CARD_FORMULA 按 sort_order 回退与之口径一致。
- 备选/加固：可再用「基础 code（去 `__imp*` 后缀）」做二级匹配，避免 sort_order 万一错位；实现计划里二选一或叠加。

## 4. 修复 C — 公式校验 + 试算预览 + 语法提示
1. **静态校验强化**（前端 `validateCardFormula` + 后端保存校验对齐），中文报错 + 正确写法示例：
   - 聚合被方括号包裹 `[ … (SUM|AVG|COUNT|MIN|MAX)_OVER(…) … ]` → 报「聚合函数不能包在 [] 里，应写 `SUM_OVER([页签] WHERE 条件, 表达式)`」
   - `[]` / `()` 不配平
   - 含 `.` 占位不在 refs；裸占位既不是本表列号也不在 refs；聚合源 ref 缺 `cols`
   - 未知函数名（白名单：`IF/ROUND/ABS/SUM_OVER/AVG_OVER/COUNT_OVER/MIN_OVER/MAX_OVER`）
2. **「试算」按钮（dry-run，最直接验证）**：编辑公式抽屉新增「试算」按钮 → 调后端 dry-run 端点 `POST /quotations/{id}/excel-view/dry-run`（入参 `{templateId, columns:[本列临时配置]}` 或 `{formula, refs, colKey}`）→ 返回该报价单各行该列**计算值**或**精确错误**（哪个占位/聚合解析失败）。用户写完点一下即可验证。
   - dry-run 不落库、不改模板配置（用临时列配置算）。
3. **语法提示**：保留已加的「📖 公式配置说明」面板；校验/试算报错里附正确写法。

## 5. 验收
- 后端单测：
  - `getExcelView(id, costingTemplateId)` 按核价模板算出 A/B/C；
  - `CardDataProvider` sortOrder 回退命中（构造 `b3359f70:2` ref + 数据在 `d18ac7e4:2` → 取到）；
  - `validateCardFormula` 各错误分支（含 `[SUM_OVER…]` 外层括号）；
  - dry-run 端点返回值/错误。
- E2E（非破坏，沿用 QT-1497 / 注入-还原-页签数守卫范式；核价模板临时配 A/B/C 或直接用 QT-1528 只读）：核价单 Excel 视图 A(元素小计)/B(条件聚合)/C(A+B) 出值非「—」；试算按钮返回值；保存非法公式被拦。

## 6. 边界 / 非目标
- 不改卡片数据存储结构（不新增 costing 侧数据表/列）；沿用"同一份卡片数据 + 按 sort_order 映射"。
- 不动报价侧 Excel 视图既有行为（templateId 不传时完全兼容）。
- B 列等历史误配公式：靠修复 C 的校验在再次保存时提示纠正（不自动改用户数据）。

## 7. 风险
- sort_order 回退假设"核价模板与卡片数据同 sort_order 对应同逻辑页签"——与核价卡片视图现有映射一致；若个别模板 sort_order 错位，用基础 code 二级匹配兜底。
- getExcelView 加 templateId 后，前端两个视图（报价/核价）各传各的 templateId，勿混（实现计划核对两处 callsite）。
