# fronttask · repair-260828 建单物化逐行 DB 往返

## 结论：前端零改动

**判定依据（逐条核对，不是"看起来不用改"）**：

| 判据 | 核对结果 |
|---|---|
| **接口契约是否变** | 否。`POST /basic-data-import/v6/quote/create-quotation`、`GET /quotations/{id}/materialize-status`、`POST /quotations/{id}/ensure-card-values`、`POST /quotations/{id}/ensure-excel-values` 的**请求体、响应字段、状态码全部不变**（详见 `api.md`）|
| **响应语义是否变** | 否。`materialize-status` 的 `total`/`ready`/`pending`/`done`/`inFlight` 五个字段口径一字不变——`ready` 仍是 `count(*) FILTER (WHERE NOT (quote_card_values IS NULL [OR costing_card_values IS NULL]))`。本次只让这个计数**推进得更快**，不改它的定义 |
| **是否新增/删除字段** | 否 |
| **是否改变前端可观测的时序** | 是，但**只是变快**。轮询主循环、1.5s 间隔、20 分钟兜底超时、30s 自愈节流、`pollAbortRef` 关闭抽屉不跳转——全部无需调整 |
| **是否影响渲染层** | 否。本次不碰 `field_type`、不碰 driver expansion、不碰 cache key、不碰模板快照结构 → **AP-31 / AP-37 / AP-44 / AP-50 均不触发** |

🚫 **明确不做**（防止顺手扩范围）：不调进度条文案、不调轮询间隔、不加"预计剩余时间"、不改抽屉交互。这些都不在 `问题说明.md` 的 E-1~E-7 里，属于超范围。

---

## 回归确认清单（不写代码，但必须验，由主线亲验时执行）

对应 `问题说明.md` ⑥ 的 AC-1 / AC-3 / AC-10：

- [ ] 建单抽屉进度条**仍能正常推进**（分子从 0 涨到 1845、百分比正确），完成后**自动跳转编辑页**
- [ ] 中途**关闭抽屉**→ 后台继续算完 → 报价单列表能看到该单，**不发生"算完把用户跳走"**（`pollAbortRef` 仍生效）
- [ ] 编辑页打开该单：各页签卡片值、行小计、页签小计、单头总价**均正确渲染**，无「加载中…」残留、无「—」占位
- [ ] 打开 **Excel 视图**：`quote_excel_values` / `costing_excel_values` 正常渲染
- [ ] 编辑页某格改值失焦 → 该行小计与页签小计更新 → 切走再切回 → **刷新页面**，值仍是新值（AC-10 的前端侧观测）
- [ ] F12 Network：打开该单期间**无 `batch-expand` / `batch-evaluate` 风暴**（守 AP-31；本次不应引入，若出现说明改动越界）

## 二期触发条件（本次不做，出现下列情况再立项）

- 若 B-4/B-5 落地后总耗时仍 > 20s，进度条需要"预计剩余时间"或分段提示 → 那时才动前端
- `BL-0184`（大单量报价单打开后 `batch-evaluate` 风暴 517 次 / 29 分钟）是**另一条独立故障线**，与本次建单物化不同源，不在本次范围
