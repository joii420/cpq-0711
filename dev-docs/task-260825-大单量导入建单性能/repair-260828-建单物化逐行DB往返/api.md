# api · repair-260828 建单物化逐行 DB 往返

## 结论：接口契约零改动

本次是**纯内部持久化机制优化**，不新增端点、不删端点、不改任何请求/响应结构。因此 **`dev-docs/main-api.md` 本次无需回写**（`task-docs.md §2.5` 的回写义务仅在契约有增删改时触发）。

## 涉及但不改动的端点（逐个说明为什么不改）

| 端点 | 本次改动是否触及 | 为什么不改契约 |
|---|---|---|
| `POST /api/cpq/basic-data-import/v6/quote/create-quotation` | 触及其**后台任务**（`CreateQuotationMaterializer.materialize` 的下游） | 同步段（建单 + 建行 + 立即返回 `materializing=true`）**一行不改**。`CommitResult` 的字段集不变：`quotationId` / `importRecordId` / `hfPairsCount` / `lineItemsCount` / `cardValuesReady` / `costingTreeRows` / `warnings` / `materializing` |
| `GET /api/cpq/quotations/{id}/materialize-status` | 不触及 | 五字段口径一字不变。`ready` 仍由 `count(*) FILTER (WHERE NOT (quote_card_values IS NULL [OR costing_card_values IS NULL]))` 派生，`done` 仍由 `ready==total` 派生。**本次只让计数推进更快，不改定义** |
| `POST /api/cpq/quotations/{id}/ensure-card-values` | 触及其内部实现（③ 段） | 仍是「触发 + 自愈」入口，仍幂等，仍返回 `QuotationDTO`。`ensureStructure` → `ensureCardValues` 的调用顺序不变 |
| `POST /api/cpq/quotations/{id}/ensure-excel-values` | 触及其内部实现（④ 段） | 仍幂等、已算的零开销，仍返回含 Excel 值的最新 `QuotationDTO`。`computed > 0` 时 `em.clear()` 的既有纪律保留 |
| `POST /api/cpq/quotations/{id}/refresh-card-snapshot` | **不触及** | 它走的是另一条路径（`refreshDraftQuoteCards`：重 expand + 按行键保编辑），本次明确不碰（`问题说明.md` §5.4）|

## warnings 文案（不变，但列出以便测试比对）

B-4 必须保住 B-28/B-29 的既有失败语义，两条文案**逐字不变**：

- `卡片值物化部分未完成：%d 批（共 %d 行）未完成，将在下次打开/轮询时自动补算`
- `Excel 值物化部分未完成：%d 批（共 %d 行）未完成，将在下次打开/导出/提交时自动补算`

## 新增的日志埋点（非接口，但测试要读）

B-6 新增，格式对齐既有埋点风格，**不进 HTTP 响应**：

```
[perf] ensure-cardvalues-write quotation=<uuid> rows=<N> batches=<M> updates=<K>
[perf] ensure-excel-write      quotation=<uuid> rows=<N> batches=<M> updates=<K>
```

`updates` = `executeBatch()` 调用次数，是 AC-5 的唯一可复核依据（`pg_stat_statements` 扩展在 `cpq_db_0724` 上**未安装**，已实测确认）。
