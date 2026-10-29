# 后端 / API 零改动核查

> 核查时间：2026-10-28T17:38:08-07:00
> 基线：`cb7f449b34942356203b33d38a62ad4f25105a21`
> 分支：`feat/task-261028-quote-card-vat-total`

## 1. 结论

- 本需求不存在必须扩充后端、API 或数据库的契约缺口。现有报价行查询已同时返回 `subtotal`、`vatRateRatio`、`vatRate`、卡片组件数据和快照，前端可在最终展示边界用 Decimal 派生含税文本。
- `vatRateRatio` / `vatRate` 是报价行的创建时冻结快照。现有保存链只为新行捕获 VAT，不会因后续主数据修改回填存量行。
- 保存请求没有 `vatInclusiveTotal`、`taxInclusiveSubtotal`、`taxInclusiveTotal` 或同义派生金额字段；本任务不应新增这些字段，也不应把 `(VAT)` 展示文本写入快照。
- 核查时限定后端、Flyway、Dockerfile、`deploy/` 和主 API 文档的 diff 为空；本任务无 SQL 增量、无部署参数变更。

## 2. 静态契约证据

### 2.1 报价行实体已保存冻结 VAT 与小计

- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineItem.java`
  - `QuotationLineItem#vatRate` 对应 `vat_rate`。
  - `QuotationLineItem#vatRateRatio` 对应 `vat_rate_ratio`。
  - `QuotationLineItem#subtotal` 是 `NUMERIC(26,12)` 口径的现有产品行小计。
  - 实体注释明确 VAT 为创建时快照，不由重算刷新。
- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/resources/db/migration/V446__task260927_vat_schema.sql`
  - 已有 `vat_rate_ratio NUMERIC(12,8)`；本需求无需新迁移。

### 2.2 GET 响应已提供前端全部输入

- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java`
  - `QuotationDTO.LineItemDTO` 已声明 `vatRate`、`vatRateRatio`、`subtotal`、`componentData`、`snapshot` 和四份卡片/视图值快照。
  - `QuotationDTO.LineItemDTO#from` 从实体逐字段复制 `vatRate`、`vatRateRatio`、`subtotal` 与快照输入。
- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`
  - `QuotationResource#getById` 继续直接返回 `quotationService.getById(id)`，没有角色化改写这些原始 decimal 值。
- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
  - `QuotationService#getById` 通过 `loadLineItems` 装配报价行。
  - `QuotationService#loadLineItems` 以 `QuotationDTO.LineItemDTO#from` 为起点，再装配既有组件数据与快照；因此编辑态/详情态都可以消费同一组现有字段。

### 2.3 税率冻结语义未被保存/重算覆盖

- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
  - `QuotationService#applyVatSnapshot` 把 `VatSnapshotService.Snapshot` 的 `vatRate` / `vatRateRatio` 写入报价行。
  - 旧全量保存与新增量保存两条路径都以 `isNewLine && li.vatRate == null` 作为捕获条件，旁边注释明确“Existing rows (including legacy NULL snapshots) are intentionally not backfilled”。
  - `QuotationService#copy` 复制报价时直接复制源行的 `vatRate` / `vatRateRatio`，不回查当前税务主数据。
- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`
  - `CardSnapshotService#buildProductAttributesContext` 将行级 `vatRateRatio` 作为冻结公式上下文，不查税率主数据。

### 2.4 保存契约不需新字段

- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java`
  - `SaveDraftRequest.LineItemDraft` 已有 `subtotal`、`productAttributeValues`、组件数据与现有快照值。
  - 请求 DTO 不包含 VAT 快照可写字段，也不包含任何税后派生金额字段；这与“显示值不回写”的范围一致。
- 在 `SaveDraftRequest.java` 和 `QuotationDTO.java` 限定搜索 `vatInclusiveTotal|taxInclusiveSubtotal|taxInclusiveTotal` 为 0 命中。

### 2.5 核价、导出与快照链无变更

- 本分支对 `cpq-backend/` 的跟踪与未跟踪改动都为 0，因此核价响应形状、快照写入、报价单级总计和导出路径均未被本任务改写。
- `/home/joii/project/cpq/.claude/worktrees/task-261028-quote-card-vat-total/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java` 已有自身独立的导出 VAT 处理；本需求明确不改导出，本分支也没有触及它。

## 3. 命令与原始结果

```text
$ git rev-parse HEAD
cb7f449b34942356203b33d38a62ad4f25105a21

$ git diff --exit-code HEAD -- cpq-backend dev-docs/main-api.md deploy/Dockerfile deploy/Dockerfile.runtime cpq-backend/src/main/docker
<empty>
exit=0; BACKEND_CONTRACT_DIFF_EMPTY

$ git diff --exit-code -- cpq-backend/src/main/resources/db/migration
<empty>
exit=0; FLYWAY_DIFF_EMPTY

$ git ls-files --others --exclude-standard cpq-backend
<empty>
BACKEND_UNTRACKED_EMPTY

$ rg -n "vatInclusiveTotal|taxInclusiveSubtotal|taxInclusiveTotal" \
    cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java \
    cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java
<0 matches>
```

## 4. 尚需流程测试闭合的证据

本代理按派工禁止启动服务和修改业务数据，因此没有把以下动态事项冒充为已通过：

- AC-5 的保存前/PUT/GET 真实 JSON 字段集合与目标 decimal string 逐字 diff。
- AC-6 的核价编辑态/只读态真实响应和界面反向回归。
- 运行时请求体中无派生字段、保存后快照哈希不变的浏览器/API 证据。

上述三项应由 `test.md` 的 S-FLOW / T-05 / T-06 在隔离环境执行后写入 `test-report.md`。

## 5. 残余风险与自检

- 静态契约无缺口；主要残余风险只是前端是否仅在 `QUOTE` 分支派生文本、是否错将展示值混入保存 state，需以前端 diff 和 T-05/T-06 闭合。
- N+1 自检：本核查未修改任何后端循环、Repository 调用或懒加载路径，新增 SQL 条数为 0。
- 迁移自检：本核查未新增/修改 Flyway，未启动后端，未手工执行 SQL，未写数据库。
- 部署自检：未修改 Dockerfile 或 `deploy/`，未生成部署包。

> 已自检：后端/迁移/API 文档/Dockerfile 限定 diff 为空 ✅；后端未跟踪文件为空 ✅；新增 SQL/N+1 风险为 0 ✅；动态 GET/PUT 契约已明确留给 S-FLOW，未假称完成 ✅。
