# test · repair-0814 详情页报价树多出「版本」列

## T0 测试环境

| 项 | 值 |
|---|---|
| 前端 | worktree 内临时 Vite `http://127.0.0.1:5199`（`node_modules` 软链主仓；**不能用 5174**，那是主仓 master 代码） |
| 后端 | 主仓共享 dev server `http://localhost:8081`（本次零后端改动，用 master 后端即可） |
| DB | 默认 profile → `10.177.152.12:5432/cpq_db_0724` |
| E2E 夹具 | `QUOTATION_ID = 1f8c146d-20cd-438b-9b4a-53a98f3cbdb9`（QT-20260721-2067，报价侧 BOM 树 17 行 spine） |
| 命令 | `PW_BASE_URL=http://127.0.0.1:5199 npx playwright test --config=e2e/playwright.config.ts e2e/quotation-bom-tree.spec.ts --reporter=list` |

## T1 用例矩阵（对齐 `问题说明.md` ⑥ AC）

| # | 用例 | 步骤 | 期望 | 覆盖 AC | 方式 |
|---|---|---|---|---|---|
| T1.1 | 报价侧**详情页**树页签无版本列 | `/quotations/{id}` → BOM树页签 | 表头 = `料号 | <业务列…>`，不含「版本」 | AC-1 | E2E（新增 test）|
| T1.2 | 报价侧**编辑页**树页签无版本列（不回归） | `/quotations/{id}/edit` → BOM树页签 | 同上；无 `select[disabled]` 版本占位 | AC-1 | E2E（既有 test）|
| T1.3 | 详情页表体不错位 | 同 T1.1 | 每行 `td` 数 == 表头列数 | AC-3① | E2E（新增）|
| T1.4 | 详情页表尾不错位 | 同 T1.1 | 每个 tfoot 行 `Σ colSpan` == 表头列数 | AC-3③ | E2E（新增）|
| T1.5 | 「暂无数据」占位行不溢出 | 找一个报价侧树页签且当前料号无数据 | 占位行横跨整表，不多出一列 | AC-3② | 人工（无数据夹具时由 T1.3/T1.4 的列数不变量间接保证）|
| T1.6 | **核价侧详情页仍有版本列** | 核价工作台 / 核价详情 → 树页签 | 表头 = `料号 | 版本 | …`；版本单元格内容与改动前一致 | AC-2 | 人工 A/B（stash 背靠背）|
| T1.7 | 核价侧版本切换仍可用 | 核价 PENDING 单 + 财务/管理员 → 树页签版本下拉 | 下拉可展开、切换后触发 `onVersionSwitched` | AC-2 | 人工 |
| T1.8 | 树能力不退化 | 详情页报价树 | 缩进随层级递增、折叠箭头可展开/收起、环行红字、料号文本正确 | AC-4 | E2E（既有断言）+ 截图 |
| T1.9 | 值不变 | 同一单改动前/后 | 页签小计 / 合计 / 产品小计逐字节一致 | AC-5 | 人工 A/B（`git stash` 背靠背纯读对比）|
| T1.10 | 非树页签版本列不受影响 | 报价侧非树页签（本就无）、核价侧非树页签（按 `view_version` 出） | 与改动前一致 | AC-6 | 人工 |
| T1.11 | 编译/transform 自检 | — | `tsc` 0 错误；两个 `.tsx` Vite 200 | AC-7 | 命令 |
| T1.12 | 防回归断言落地 | — | `quotation-bom-tree.spec.ts` 含详情页表头断言 | AC-8 | 代码评审 |

## T2 A/B 对比方法（T1.6 / T1.9 用）

按记忆 `cpq-golden-cardvalues-preexisting-drift` 的「值中性验证法」：

```bash
# B 侧（改动后）：记录目标页签的小计/合计/产品小计
# 然后 git stash → 刷新 → 记录 A 侧（改动前）→ git stash pop
```
两侧数值必须逐字节一致；若不一致，说明本次改动越界碰到了取值链路（本次预期恒一致，因为只改了 JSX 是否渲染）。

## T3 已知限制 / 不做的事

- **不跑 `quotation-flow.spec.ts`**：该 spec 的夹具在干净 master 上已恒 3 失败（记忆 `task0712-update071501-category-axis`：夹具单缺产品分类 → Step1「下一步」禁用），跑它无法提供有效信号。本次改动面被 `quotation-bom-tree.spec.ts` 完整覆盖（它正是当年落「报价侧不出版本列」裁决时同步更新的 spec）。若仍要跑，必须做 A/B 同型对比再归因。
- **无后端测试**：本次零后端改动（见 `backtask.md`）。
- **T1.5 无专用夹具**：现有报价树夹具恒有数据；该场景由 T1.3/T1.4 的「列数不变量」间接覆盖 + 代码走查（`colSpan` 表达式与表头列数同源）。
