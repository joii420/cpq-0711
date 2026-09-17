# api · 元素价格支持 9 位小数

> **结论：接口形状零改动** —— 不新增 / 不删除端点，不改方法、路径、参数名、字段名、字段类型、错误码与错误文案。
> **改的只是数值口径**：下列端点的单价 / 系数 / 加价在**写入前**按 `HALF_UP` 舍入到 9 位；相关文本里的数字改为最多 9 位、去尾零。
> 精度字段沿用 PRD 精度契约：**请求**用十进制字符串（JSON number → 400，现状不变）；**响应**为规范十进制字符串（去尾零）。

---

## 1. 为什么不改形状

用户诉求是「元素价格保留 9 位」。库列已是 `numeric(26,12)` / `numeric(18,12)`，接口字段已是十进制字符串 —— 形状上本来就容得下 9 位，瓶颈全在**舍入位数**与**显示位数**，与接口形状无关。

## 2. 口径变化的端点

| # | 端点 | 字段 | 改前 | 改后 | 后端任务 |
|---|---|---|---|---|---|
| 1 | `POST /api/cpq/element-price/import`（multipart：`file` / `sourceId` / `priceDate`） | 结果行 `price` | 原样（未舍入） | 舍入到 9 位后的值（即落库值） | B-3 |
| 1 | 同上 | 结果行 `message`（`result=UPDATED` 时） | `原值 X → 新值 Y`，X/Y 为 4 位 | X/Y 最多 9 位、去尾零 | B-3 |
| 1 | 同上 | 结果行 `result` / `message`（舍入后为 0） | 原值大于 0 时判成功 | `FAILED` / `单价必须大于 0`（文案不变） | B-3 |
| 2 | `POST /api/cpq/element-price/prices`（body：`elementCode` / `sourceId` / `priceDate` / `price` / `currency` / `priceUnit`） | `price` 落库与响应 | 原样 | 舍入到 9 位；舍入后为 0 → 400，文案与现有「大于 0」校验一致 | B-4 |
| 3 | `PUT /api/cpq/element-price/prices/{id}`（body：`price` / `currency` / `priceUnit`） | 同上 | 原样 | 同上 | B-4 |
| 4 | `GET /api/cpq/element-price/prices/history` | 变更条目里单价的「A → B」文本 | 4 位（且 4 位相同时**不产生**变更条目） | 最多 9 位、去尾零（9 位内不同即产生条目） | B-5 |
| 5 | `PUT /api/cpq/element-price/strategies/default`、`POST /api/cpq/element-price/strategies/exceptions`、`PUT /api/cpq/element-price/strategies/exceptions/{id}`（body：`customerNo` / `elementCode` / `sourceId` / `method` / `windowNum` / `windowUnit` / `factor` / `premium`） | `factor` / `premium` 落库与响应 | 原样 | 舍入到 9 位 | B-6 |
| 6 | `GET /api/cpq/element-price/strategies/history` | 变更条目里系数、加价的「A → B」文本 | 2 位 | 最多 9 位、去尾零 | B-7 |
| 7 | `POST /api/cpq/element-price/strategies/simulate` | 结果行 `finalPrice` | 4 位 | 舍入到 9 位 | B-8 |

## 3. 取值口径变化、但不经本任务接口改动的读端点

以下端点代码不改，因取价函数（B-1）结果变为 9 位，**返回的数值随之变细**（形状不变）：

| 端点 | 受影响字段 | 说明 |
|---|---|---|
| `GET /api/cpq/price-adjust/strategies/{customerNo}/elements` | 格子 `unitPrice` | 取自版本明细；**修复后新生成的版本**为 9 位，存量版本不变 |
| `GET /api/cpq/price-adjust/versions/{versionId}/items`、`GET /api/cpq/price-adjust/reviews/{reviewId}` | `currentPrice` / `previousPrice` | 同上 |
| `POST /api/cpq/price-adjust/versions/generate` | 新生成版本的冻结价 | 生成时调用取价函数 ⇒ 新版本为 9 位 |
| 报价单渲染与取数相关端点 | 元素单价取值 | 实时取价的料号变 9 位；有冻结版本的料号不变 |

## 4. `main-api.md` 回写判定

按 `task-docs.md §2.5`：本任务**方法 / 路径 / 参数 / 响应字段 / 错误码全未变**，可跳过回写；须在 `test-report.md` 写明「本次无契约形状变更，无需回写 main-api.md（仅数值舍入口径变化，见本文件 §2）」。
