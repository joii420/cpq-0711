
### tabjoin-formula-drawer（要求：无新增失败）

| 用例 | 本分支 | master | 结论 |
|---|---|---|---|
| 两栏布局 / 左栏搜索 / chip 插入光标处 / 宽度自适应（AC-1 / AC-3 / AC-16 / AC-18） | failed | failed | 一致 |
| 括号可视化与光标行为（AC-7 / AC-8 / AC-9 / AC-12） | failed | failed | 一致 |

### quotation-flow（要求：仅对照（DEC-0008））

| 用例 | 本分支 | master | 结论 |
|---|---|---|---|
| LEGACY SIMPLE smoke · 报价单流程: 苏州西门子 + 报价模板0608 v1.10 + 10110002(渲染层无回归) | timedOut | timedOut | 一致 |
| TC-075 SIMPLE · Stage H 确定性精度单保存/刷新/重开后所有 Tab 稳定 | failed | failed | 一致 |
| TC-F1: 打开 DRAFT 报价单不自动发 refresh-card-snapshot | failed | failed | 一致 |
| TC-F2: 显式刷新才触发 refresh-card-snapshot | failed | failed | 一致 |
