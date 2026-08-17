# fronttask · repair-0809 —— 前端零改动的判定依据

> 规则要求：前端零改动的任务也要写本文件（`CLAUDE.md`「宁可写细，不可留空槽」）。

## 1. 结论

**前端零改动。前端本来就是对的，本单是把后端对齐到前端 + 后端自己的另两个实现。**

## 2. 判定依据

| # | 判据 | 证据 |
|---|---|---|
| F-1 | 前端 `__amount_total__` 求和不截断 | `cpq-frontend/src/pages/quotation/tabTotalLines.ts#sumAmountFromByCol` → `sumDecimal(values).toNumber()`，无 `setScale`/`toFixed`/`round` |
| F-2 | 前端产品小计链路已在 task-0801 统一到 6 位**呈现**（计算不规整） | `precision.ts` / `formatNumber(..., {isComputed:true})`；见记忆 `cpq-decimal-display-policy`（现行 6 位，代码里有「勿改回」警告） |
| F-3 | 改后端不改变前端任何输入契约 | 变的只是 `componentSubtotals['<key>#__amount_total__']` 的**数值末位**与 `quotation.total_amount` 的**数值末位**，字段名/类型/结构不变 |
| F-4 | 若改前端反而是错的 | 让前端也截断到 4 位 = 把三个正确实现对齐到唯一错误实现，且违反 `PrecisionPolicy`「计算过程中不规整」 |

## 3. 前端回归确认清单（主线亲验）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误（应与改动前完全一致）
- [ ] `npx vitest run src/pages/quotation src/utils src/pages/component` → 相对基线**无新增失败**（基线：`1044 passed / 2 failed`，那 2 条是 `BL-0160` 存量）
- [ ] `crossTabOrderParityQt0146.repair0808.test.ts` 仍全绿 —— ⚠️ 该文件里 T-2.4 对产品小计用的是「绝对容差 1e-3 + 量级闸」，**本单修复后前后端应当逐值相等**；若你想顺手把它收紧成 1e-12，属加分项，但**必须先确认真的相等**再收紧，不许为了绿而放宽
- [ ] 合并后用 `e2e/repair0808-verify.spec.ts` 真机复看 `QT-20260807-0146`：产品小计应从 `¥ 137.531092` 变为与后端一致的值（AC-2）

## 4. 二期触发条件

1. 若产品裁定价格域（`PriceImportRowWriter` / `PriceTableService` / `StrategyService` 那 3 处 4 位）也要统一 → 会牵动价格表展示与导出，届时前端展示位数需同步评估，本文件作废。
2. 若决定回刷存量 `total_amount` → 列表页/报表的历史数字会变，需前端配合出对比视图。
