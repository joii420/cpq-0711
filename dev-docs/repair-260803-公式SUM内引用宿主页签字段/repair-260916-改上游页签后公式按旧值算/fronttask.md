# fronttask · repair-260916 前端任务分解

> 只按本文件做。**验收标准以 `问题说明.md` ⑥ 的 AC 原文为准**，本文件与 AC 有出入时以 AC 为准并报告主线。
> 背景与根因见 `问题说明.md` ④；修复方案（闸门 A0 裁决：方案甲）见 ⑤。

## 改动范围

- **只改** `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（逻辑）与 `cpq-frontend/src/utils/formulaEngine.ts`（**仅注释**，F-4）。
- 🚫 不改：两个计算入口的**函数签名**、14 个调用点、`buildExcelSnapshot.ts`、`ReadonlyProductCard.tsx`、`QuotationWizard.tsx`、`formulaEngine.ts` 的任何逻辑、任何后端文件。
- 🚫 不写测试用例（由测试工程师按 `test.md` 写）；只做自检。
- 📌 **不出原型图**：纯计算逻辑改动，界面布局、控件、文案零变化，不命中 `frontend.md` §1.3 触发条件。

## 任务

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| F-1 | AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-12 | `computeAllFormulas`：构造 `currentRowForEval` 时，以「本行原始数据**去掉本组件全部 `FORMULA` 字段键**」的**新对象**为起点（字段名取法与本函数其他处一致：`f.name \|\| f.key`）；其后的 INPUT 默认值补齐、单位换算、`buildHostMatchRow(...)` 全部基于这份新对象。🚫 不得修改传入的 `row` 对象（它是渲染层同一引用） |
| F-2 | AC-1, AC-2, AC-4, AC-5, AC-7, AC-12 | `resolveRowForTree`（`computeTabFormulasTree` 的行素材构造）：同 F-1 —— `currentRowForEval` 与 `matchRowForEval` 基于去掉公式列键的新对象；`treeCtx.rowBundles[i].currentRow` 因此随之一致。同样不得修改入参 `row` |
| F-3 | AC-11 | 两个入口里**条件公式**的取值函数 `lookup`：列名是本组件 `FORMULA` 字段时，**不读**原始行，直接取本轮已算出的值（`fieldValues` / `bundle.fieldValues`）；树属性保留字（层级 / 是否叶子 / 是否根）仍最优先；其余列名的取值顺序不变 |
| F-4 | AC-1, AC-11 | 注释同步（`change-protocol.md` §5.1「警告放在会破坏它的人的必经之路上」）：① `formulaEngine.ts` 的 `case 'b_field'` 注释补一句「调用方传入的 `currentRow` 不得含本页签公式列的值——前端行数据（`row_data`）存着上次保存的公式结果，会被当成原始值优先使用；两个入口已在构造处剔除（repair-260916）」；② F-1/F-2 改动处写明原因与本任务编号。**不改任何逻辑** |
| F-5 | AC-8, AC-9, AC-10, AC-13, AC-14 | 自检（见下）+ 回报「改动前后行为不变」的依据：非公式列取值、匹配判定、错误标记收集路径逐一说明未受影响 |

## 实现约束（逐条对应已知坑）

1. **不改签名**：`computeAllFormulas` / `computeTabFormulasTree` 已被 14 处调用（编辑页 8 / 详情页 4 / 向导 2）并被既有单测直接 import；签名一变就是跨文件改动。
2. **只剔除 `FORMULA` 类型**：`INPUT_*` / `BASIC_DATA` / `DATA_SOURCE` / `FIXED_VALUE` / `LIST_FORMULA` 的键原样保留。「用户填的值优先、显式清空 `''` 按 0」（`repair-260803` FR-2）必须逐位不变（AC-11b）。
3. **不要**把本轮算出的公式值写回原始行或 `currentRowForEval`（`repair-260803` 决策 D-2 已否决：会污染内层 KSUM 的匹配键）。`b_field` 取不到键时自然回落 `hostFieldValues`，那正是想要的行为。
4. **性能**：每行多一次浅拷贝 + 一次按字段表的删键，字段数是常数级；🚫 不要在循环内反复 `comp.fields.filter(...)`，公式列名集合每次调用算一次即可。
5. **错误标记不许动**：`computeAllFormulas` 的 `out.errors`、`diag.crossTabError` 收集逻辑保持原样（AC-9 反向：真正的「细项引用命中多行」仍须显示 ⚠）。

## 自检（按 `frontend.md` §2，worktree 内执行）

- [ ] `cd <worktree>/cpq-frontend && npx tsc -b` → 0 错误（🚫 不是 `tsc -p tsconfig.json`）
- [ ] `npx vitest run src/pages/quotation src/utils` → 失败集合与改动前（`git stash` 前先跑一次记下）相同；**不许为了变绿改既有测试**，有新增失败先报主线
- [ ] 用临时端口起 worktree 前端（`VITE_API_TARGET=http://localhost:8081 npx vite --port 5198 --strictPort`，依赖按 `git-worktree.md` §1.4 软链主仓 `node_modules`），`curl --noproxy '*' -s -o /dev/null -w '%{http_code}' http://localhost:5198/src/pages/quotation/QuotationStep2.tsx` → 200；**用完即停**
- [ ] `grep` 确证改动特征串确实在 worktree 文件里（不是只看接口响应）
- [ ] 🚫 不占 `5174` / `8081` / `5197`（测试 S-E 片专用）

## 回报格式

做了什么（逐项，注明 F-x 与 AC）/ 自检命令原始输出 / 未验证的项 / 过程中规避掉的坑。
