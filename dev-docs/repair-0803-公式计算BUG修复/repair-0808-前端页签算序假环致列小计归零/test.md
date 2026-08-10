# test · repair-0808 前端页签算序假环致列小计归零

> 基线文档：同目录 `需求文档.md`（AC-1~AC-8）。
> 执行环境：分支 `fix/repair-0808-crosstab-order-column-granularity`（worktree）；库 `10.177.152.12:5432/cpq_db_0724`；
> 前端单测 `npx vitest run`；页面验证在**合并后**的主工作区 5174 做（worktree 不起独立 dev server）。
> **实际结果**列留空，由执行人填写；未填 = 未执行。

---

## 0. 前置数据

| 项 | 值 |
|---|---|
| 报障报价单 | `QT-20260807-0146` / `quotation.id = 6d014a9a-fe27-432a-bce9-7f6c86c50775` |
| 行项 | `quotation_line_item.id = 5eb0f2de-dc0e-42bf-a979-9240940507ec`，料号 `3120011203` |
| 模板 | 「测试BUG-2」`7fd1ecd8-ec52-4bc5-9104-98189d1e0761`（PUBLISHED v1.0） |
| 关键页签 | 产品 `8e06c482…`（税率=INPUT_NUMBER）、物料 `7f7b57ac…`（11 个 FORMULA 列）、材料成本 `1054217f…`、来料固定加工费 `d6b5add7…`、来料其他费用 `00783228…` |
| 夹具导出 SQL | 见 §5「夹具制备」 |

---

## 1. 建图规则单测（`crossTabOrder.test.ts` 新增 · 对应 FR-1 / AC-5）

| 编号 | 对应 | 前置 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|---|
| T-1.1 | FR-1 / AC-5 | A.公式 `component_subtotal` 引用 B 的 **FORMULA** 列 | `buildComponentDeps([A,B])` | `deps[A]` 含 `B` | | P0 |
| T-1.2 | FR-1 / AC-5 | A.公式 `component_subtotal` 引用 B 的 **INPUT_NUMBER** 列 | 同上 | `deps[A]` **不含** `B` | | P0 |
| T-1.3 | FR-1 | 引用 B 的 `LIST_FORMULA` 列 | 同上 | `deps[A]` 含 `B`（`*_FORMULA` 后缀判据生效，D-4） | | P0 |
| T-1.4 | FR-1 | 引用整页签合计（`is_tab_total=true`） | 同上 | `deps[A]` 含 `B` | | P0 |
| T-1.5 | FR-1 | 引用 `value='__amount_total__'`（未带 `is_tab_total`） | 同上 | `deps[A]` 含 `B` | | P1 |
| T-1.6 | FR-1 / D-3 | 引用 B 里**不存在**的列名 | 同上 | `deps[A]` 含 `B`（保守建边） | | P0 |
| T-1.7 | FR-1 / D-3 | B 的 `fields` 为 `undefined` / 非数组 | 同上 | `deps[A]` 含 `B`（保守建边） | | P0 |
| T-1.8 | FR-1 / D-3 | token 的 `value` 为空串 | 同上 | `deps[A]` 含 `B`（保守建边） | | P1 |
| T-1.9 | FR-1 | `cross_tab_ref` 指向 B 的任意列（含 INPUT 列） | 同上 | `deps[A]` 含 `B`（cross_tab 恒建边，不做列豁免） | | P0 |
| T-1.10 | FR-1 | `component_subtotal` 指向**卡片外**的 code | 同上 | 不建边、不抛错 | | P1 |
| T-1.11 | FR-1 | A 的公式引用 **A 自己**的列小计（二阶列） | 同上 | `deps[A]` 不含 `A`（自引用不建边） | | P0 |
| T-1.12 | FR-1 | 目标只能用 `tab_name` 解析（`component_code` 缺失） | 同上 | 按 `tabName` 解析成功并按列粒度判定 | | P1 |
| T-1.13 | FR-1 | `fields` 用 camelCase `fieldType` 写法（结构快照形状） | 同上 | 与 snake `field_type` 判定结果一致 | | P0 |
| T-1.14 | FR-1 | 空 `formulas` / 空 `fields` 页签 | 同上 | `deps[cid] = []`，不抛错 | | P1 |

## 2. 前后端对拍（新增 `crossTabOrderParityQt0146.repair0808.test.ts` · 对应 AC-3/AC-4）

> 夹具 = 线上真实三件套（结构快照 / 值快照 / `row_data`），装配器复刻 `formulaParityQt0068.repair0805.test.ts` 的形状
> （`buildComponentDataFromStructure → buildSnapshotExpansions → PASS1 → buildCrossTabRows`）。
> ⚠️ **PASS1 不能省**：`computeTabSubtotalsByColumn` 逐组件登记 `产品#税率=1.13` 是 `银点材料成本公式` 的输入，
> 省了会得到假阴性（诊断阶段实测：省 PASS1 → 材料成本仍为 0，会误判修复无效）。

| 编号 | 对应 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-2.1 | AC-4 | 跑管线，捕获 `topoOrderComponents` 是否抛错 | **不抛环**；`order` 中 `材料成本 / 来料固定加工费 / 来料其他费用` 三者下标 **< `物料` 下标** | | P0 |
| T-2.2 | AC-3 | 取 `columnSumsByComp['7f7b57ac…']` | 与后端 `subtotalByColumn` 6 键逐值一致（相对误差 ≤1e-12）：材料成本 `131.9016582451963`、材料损耗成本 `5.525608362054`、原材料成本 `847.90558141593`、材料价格 `150.45243204895976`、铆钉额外费用 `111.5175`、回收成本 `0` | | P0 |
| T-2.3 | AC-3 | 取物料 11 个 FORMULA 列的列和 | 除 `回收成本=0` 外**均非 0**（`来料回收费 0.27` / `来料财务费 0.79` / `来料损耗率 6.75` / `来料加工费 784.566` / `公式10 3595.251036946903`） | | P0 |
| T-2.4 | AC-2 | 按 `报价` SUBTOTAL 页签公式求产品小计 | `137.5310666072503`（= `quotation.total_amount`） | | P0 |
| T-2.5 | 反向门禁 | 把 `buildComponentDeps` 换回**页签粒度**（测试内构造等价 deps）后重跑 | T-2.1 抛环 + T-2.2 六键全为 0 —— **证明用例真的守着本缺陷、不是恒绿** | | P0 |

## 3. 回归（对应 AC-5 / AC-6 / AC-7）

| 编号 | 对应 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-3.1 | AC-7 | `npx vitest run src/pages/quotation` | 全绿，**无新增失败**（先在同 worktree 记录改动前基线，做 A/B） | | P0 |
| T-3.2 | AC-7 | `npx vitest run src/utils/formulaEngine.test.ts src/pages/component` | 全绿 | | P1 |
| T-3.3 | AC-5（QT-1743 不回归） | 跑 `subtotalColRefEndToEnd.test.ts` + `computeMultiSubtotal.test.ts` | 全绿 —— 引用别页签**公式列**小计的场景仍先算被引用页签 | | P0 |
| T-3.4 | AC-6 | 跑 `buildExcelSnapshot.test.ts`、`columnSumsByComp.test.ts` | 全绿 | | P0 |
| T-3.5 | AC-7 | `npx tsc --noEmit -p tsconfig.json` | 0 错误 | | P0 |
| T-3.6 | AC-6 | 用 T-2 的同一份夹具，走 `ReadonlyProductCard` 的 `buildCrossTabRows` 调用形状复算 | 列小计与 T-2.2 同值（详情页/只读卡同步修复） | | P1 |

## 4. 页面验证（合并后在主工作区做 · 对应 AC-1/AC-2/AC-8）

| 编号 | 对应 | 步骤 | 期望结果 | 实际 | 优先级 |
|---|---|---|---|---|---|
| T-4.1 | AC-1 | 浏览器打开 `QT-20260807-0146` 编辑页 →「物料」页签 | 小计行 `材料成本 = ¥ 131.901658`、`材料损耗成本 = ¥ 5.525608`（现状均 `¥ 0`）；行内值不变 | | P0 |
| T-4.2 | AC-2 | 同页底部 | `产品小计 = ¥ 137.531067`（现状 `¥ 0.103826`） | | P0 |
| T-4.3 | AC-1 | F12 Console | **没有** `[crossTabOrder] 组件依赖成环` 报错 | | P0 |
| T-4.4 | — | 详情页打开同一单据 | 「物料」小计与 T-4.1 同值 | | P1 |
| T-4.5 | — | 该单据触发一次保存（不改任何输入） | `quotation.total_amount` 仍为 `137.531067`；`quote_card_values.subtotalByColumn` 逐值不变（SQL 见 `backtask.md` §3） | | P0 |
| T-4.6 | AC-8 | E2E `npx playwright test e2e/quotation-flow.spec.ts` | 相对**同库 master 基线**无新增失败（⚠️ 该 spec 在干净 master 上已有既存失败，必须 A/B 同型对比，不得直接判回归） | | P1 |
| T-4.7 | 抽样 | 另取 2 张**不同模板**的在途 DRAFT 报价单打开 | 列小计/产品小计与各自 `quote_card_values.subtotalByColumn` 一致；若有数值变化，逐条能解释为"向后端对齐"（风险 R-1） | | P0 |

## 5. 夹具制备（T-2 用）

```bash
D=<夹具目录>
Q=6d014a9a-fe27-432a-bce9-7f6c86c50775
L=5eb0f2de-dc0e-42bf-a979-9240940507ec
PG="PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A"
# ① 冻结结构（卡片渲染的权威结构）
$PG -o $D/structure.json -c "SELECT structure FROM quotation_view_structure WHERE quotation_id='$Q' AND view_kind='QUOTE_CARD';"
# ② 值快照（buildSnapshotExpansions 的输入 + 对拍基准 subtotalByColumn）
$PG -o $D/cardvalues.json -c "SELECT quote_card_values FROM quotation_line_item WHERE id='$L';"
# ③ saved componentData（comp.rows 来源）
$PG -o $D/saved.json -c "SELECT jsonb_agg(jsonb_build_object('componentId',component_id,'tabName',(SELECT t->>'tabName' FROM jsonb_array_elements((SELECT structure->'tabs' FROM quotation_view_structure WHERE quotation_id='$Q' AND view_kind='QUOTE_CARD')) t WHERE (t->>'componentId')::uuid=d.component_id LIMIT 1),'rows',d.row_data,'deletedRowKeys',d.deleted_row_keys)) FROM quotation_line_component_data d WHERE line_item_id='$L';"
```

> 三份 JSON 必须**落进仓库的 `__fixtures__/qt20260807-0146/`**（照 `qt20260804-0068` 的目录形状，含文件头注明导出日期与裁剪口径），
> **不得**从 `/tmp` 读 —— 否则用例在别的机器上必挂。
> 诊断阶段用过的一次性复现脚本在
> `/tmp/claude-1000/-home-joii-project-cpq/b04ad164-0e32-4414-a078-9cec40b70038/scratchpad/repro_bug0146.test.ts`，可作为装配器蓝本。

---

## 6. 缺陷登记口径

执行中发现的问题一律登记到 `test-report.md` 的缺陷清单（编号 `D-nn`），注明：复现步骤 / 严重级 / 是否本次引入 / 修复状态。
**判"是否本次引入"必须做同库 A/B 对照**（记忆 `cpq-agent-tests-stale-server-false-positive`：报"非本次引入"也要自己在 master 跑一遍确认）。
