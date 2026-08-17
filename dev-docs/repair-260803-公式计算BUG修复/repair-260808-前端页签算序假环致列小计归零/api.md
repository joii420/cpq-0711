# api · repair-0808 —— 接口零改动的判定依据

> 规则要求：接口零改动的任务也要写本文件，内容是「为什么不改」+ 回归确认清单 + 二期触发条件（`CLAUDE.md`）。

---

## 1. 结论

**本次无任何接口契约变更**：不新增端点、不改路径/方法、不改请求参数、不改响应结构、不改错误码。
→ 按 `任务平台规则.md` §2.4，**`dev-docs/main-api.md` 不需要回写**；此事实须在 `test-report.md` 中写明并跳过步 9。

---

## 2. 判定依据

| # | 判据 | 说明 |
|---|---|---|
| A-1 | 改动全在**前端纯计算层** | `crossTabOrder.ts`（新增纯函数）+ `QuotationStep2.tsx` 的 `buildCrossTabRows` 内部建图。两者都不发请求 |
| A-2 | 无新增 `fetch` / service 调用 | 建图的输入 `formulas` / `fields` 来自**已经拿到的**卡片结构（`quotation_view_structure` → `buildComponentDataFromStructure`），不需要任何新数据 |
| A-3 | 消费的响应字段无变化 | 仍读 `structure.tabs[].{componentId, componentCode, tabName, fields, formulas}` —— 全是现有字段，无新增依赖 |
| A-4 | 上行 payload **值**会变、**结构**不变 | `PUT/POST` 保存草稿时 `lineItems[].subtotal`、`quotation.total_amount` 的**数值**会由错误的 `0.103826` 变为正确的 `137.531067`。这是修复的目的，不是契约变更：字段名、类型、必填性、量纲全部不变 |

---

## 3. 受影响端点（仅数值口径，非契约）

| 端点 | 影响 | 是否需回写 main-api.md |
|---|---|---|
| `PUT /api/cpq/quotations/{id}`（saveDraft） | 请求体 `lineItems[].subtotal` 数值修正 | ❌ 否（结构不变） |
| `POST /api/cpq/quotations/{id}/submit` | 同上，提交期整单金额随之修正 | ❌ 否 |
| `POST /api/cpq/quotations/{id}/line-items/{liId}/card-value`（editQuoteCardValue） | 不受影响（后端自算，前端算序与它无关） | ❌ 否 |

> 说明：`subtotal` 修正后与后端 `quote_card_values.subtotalByColumn` 收敛为同一口径，
> 这正是 `task-260806-报价编辑链路优化与前后端对账` 想要的"前后端对账一致"状态，**不会**触发它的不一致标记。

---

## 4. 回归确认清单（主线亲验）

- [ ] 报障单据打开 → 不改任何输入 → 触发一次 `saveDraft`，`quotation.total_amount` 从 `137.531067` **保持 137.531067**（不因前端 payload 改变而被写坏）
- [ ] 浏览器 F12 Network：打开卡片时**没有**新增的请求（本次改动零网络行为）
- [ ] `dev-docs/main-api.md` 保持不变（`git diff` 为空）

---

## 5. 二期触发条件

1. 若采纳 `backtask.md` §4.2「后端下发算好的页签序」的架构方案 → 会新增响应字段（如 `structure.tabOrder[]`），届时必须走完整 `api.md` + `main-api.md` 回写流程。
2. 若为诊断需要新增「前后端算序对拍」的调试端点 → 新增端点必须登记本文件并回写总账。
