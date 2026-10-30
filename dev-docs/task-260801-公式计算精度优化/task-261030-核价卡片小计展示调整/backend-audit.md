# 后端只读审核：核价卡片小计展示调整（B-1）

| 项 | 值 |
|---|---|
| 任务 | `task-261030-核价卡片小计展示调整`（BL-0334）· `backtask.md` B-1 |
| 服务的 AC | AC-4、AC-7、AC-9（另为 S-FLOW 全部页面级夹具提供字段依据） |
| 审核时刻 | 2026-10-30 14:02 +0800（worktree HEAD `1ac02a1d`，分支 `feat/task-261030-costing-card-subtotal`） |
| 审核方式 | 只读：读后端源码 / 迁移 / 前端取数代码；本机 docker 库 `cpq_db` 只跑 `SELECT information_schema`。**未启动后端、未发任何 HTTP 请求、未连共享库** |
| 结论 | 后端零改动成立（§4）；字段样本见 §1，页面接口清单与响应样本见 §2；与 `api.md §2` 的出入 6 条见 §5，其中 **§5-1（保存后币种/汇率丢失）建议主线优先过目** |

> 📌 所有 `文件:行` 均为 worktree 内路径。`B/` = `cpq-backend/src/main/java/com/cpq/`，`F/` = `cpq-frontend/src/`。

---

## 0. 三条全局序列化规则（造夹具前必须先知道）

| # | 规则 | 依据 |
|---|---|---|
| G-1 | **所有 `BigDecimal` 一律序列化为 JSON 字符串**，`stripTrailingZeros` 后的 plain 文本；零值固定为 `"0"`。例：`numeric(26,12)` 的 `7.123400000000` → `"7.1234"`；`1.000000000000` → `"1"`；`0.13000000` → `"0.13"`；`13.0000` → `"13"`。**`null` 仍是 JSON `null`**（自定义序列化器不处理 null）。 | `B/common/DecimalJacksonCustomizer.java:30-43`；`B/common/PrecisionPolicy.java:137-141` |
| G-2 | **DTO 里值为 null 的字段照常输出 `"key": null`，不会缺省**。只有响应信封 `ApiResponse` 自身是 `NON_NULL`（所以成功信封里没有 `detail` 键）；该注解是类级，不传染给 `data` 内部对象。全局未配置 `quarkus.jackson.serialization-inclusion`。 | `B/common/dto/ApiResponse.java:5`；`application.properties` 无 `quarkus.jackson.*` 配置（`grep` 为空） |
| G-3 | 所有业务接口都包在信封里：`{"code":200,"message":"success","data":<DTO>}`；前端 axios 拦截器返回 `response.data`（即整个信封），页面再取 `.data`。前端**不看 `code` 字段**，只看 HTTP 状态；HTTP 401 → 跳 `/login`。 | `ApiResponse.java:26-32`；`F/services/api.ts:3-13`、`:54-66` |

⚠️ **G-1 对本任务是硬约束**：编辑页把报价行读进来时只接受字符串形态的十进制数 —— `vatRateRatio: isDecimalString(li.vatRateRatio) ? … : undefined`、`subtotal: isDecimalString(li.subtotal) ? … : '0'`（`F/pages/quotation/QuotationWizard.tsx:683`、`:689`）。**夹具若把 `vatRateRatio` 写成数字 `0.13`，编辑页会把它当成缺失 → `(VAT) -`，测出来是假红/假绿。**

---

## 1. 字段样本表（报价查询响应）

接口：`GET /api/cpq/quotations/{id}` → `QuotationResource#getById`（`B/quotation/resource/QuotationResource.java:147-150`）→ `QuotationService#getById`（`B/quotation/service/QuotationService.java:337-358`）。
同一 DTO 还由 `POST /api/cpq/quotations/{id}/ensure-card-values`（`:295-307`）、`POST /{id}/ensure-excel-values`（`:283-291`）、`POST /{id}/calculate-discount` 原样返回（都在末尾调 `getById`）。核价管理页的 `frozenDto` 也是这份 DTO 的序列化快照（§2.3）。

### 1.1 报价单头

| 字段（JSON 名） | Java 类型 → JSON 类型 | 空值形态 / 取值 | 依据 |
|---|---|---|---|
| `currency` | `String` → string | **实际不会为空**：列 `NOT NULL DEFAULT 'CNY'`（本机库 `information_schema` 实查）；建单取客户 `defaultCurrency` 大写，客户无币种则 `"CNY"`，币种不存在/停用直接 400。`""` / `null` 只可能来自手工改库 | `QuotationDTO.java:17`、`:140`；`entity/Quotation.java:28`；`QuotationService.java:479-486` |
| `baseCurrency` | —— | 🚫 **后端不存在此字段**（`QuotationDTO`、`SaveDraftResponse` 均无；全仓 `grep baseCurrency` 只命中 V6 导入代码）。前端 `currency \|\| baseCurrency \|\| 'CNY'` 里的 `baseCurrency` 恒为 `undefined` | `grep -rn baseCurrency cpq-backend/src/main/java` |
| `exchangeRate` | `BigDecimal` → **string** | 列 `numeric(26,12) NOT NULL DEFAULT 1` ⇒ 实际恒非空；建单 `setScale(12)` 后序列化去尾零：`"7.1234"`、CNY 为 `"1"`。`null` / `"0"` 只可能来自手工改库 | `QuotationDTO.java:18`、`:141`；`entity/Quotation.java:30-31`；`QuotationService.java:486-487` |
| `costingCardTemplateId` | `UUID` → string | 未绑核价模板 → `null` | `QuotationDTO.java:81`、`:175` |
| `costingCardStructure` | `JsonNode` → object | **见 §1.3（三种真实空形态）** | `QuotationDTO.java:55`；`QuotationService.java:391-407` |
| `quoteCardStructure` | `JsonNode` → object | 同上口径（报价侧）；未绑报价模板或未冻结 → `null` | `QuotationDTO.java:53` |
| `quoteExcelStructure` / `costingExcelStructure` | `JsonNode` → object | 同上；无则 `null` | `QuotationDTO.java:54`、`:56` |
| `quoteExcelColumns` / `costingExcelColumns` | `List<Map>` → array | 对应模板 id 为 `null` 或取列异常 → `null` | `QuotationDTO.java:59-60`；`QuotationService.java:366-389` |
| `status` | `String` → string | `DRAFT`（默认）/`SUBMITTED`/`APPROVED`/`REJECTED`/`COSTING_REJECTED`/`SENT`/`ACCEPTED`（按 `q.status = "…"` 赋值处统计，**可能不全**） | `QuotationDTO.java:34`；`entity/Quotation.java:83` |
| `userDataVersion` | `Integer` → number | 新单 `0`。⚠️ **编辑页保存前要它做并发基线，缺失则前端拒绝保存**（弹「页面数据不完整」）——AC-7④ 夹具必须给 | `QuotationDTO.java:120`；`F/pages/quotation/QuotationWizard.tsx:476-486`、`:594` |
| `cardValuesWarming` | `boolean` → boolean | 恒 `false`；仅 `ensure-card-values` 撞单飞锁时返回一个**全字段 null** 的 DTO 且此值为 `true` | `QuotationDTO.java:114`；`QuotationResource.java:298-303` |
| `totalAmount` / `originalAmount` / `defaultVatRate` 等 | `BigDecimal` → string | 规则 G-1 | `QuotationDTO.java:35-44` |
| `lineItems` | `List<LineItemDTO>` → array | 无行 → `[]`（`loadLineItems` 返回 `List.of()`） | `QuotationDTO.java:107`；`QuotationService.java:3824-3826` |

### 1.2 报价行（`lineItems[]`，`LineItemDTO`）

| 字段 | Java → JSON | 空值形态 / 取值 | 依据 |
|---|---|---|---|
| `subtotal` | `BigDecimal` → **string** | **报价侧**产品小计（§3）。实体默认 `BigDecimal.ZERO` ⇒ 一般为 `"0"` 或去尾零的十进制；列可空，存量行可能为 `null` | `QuotationDTO.java:217`、`:278`；`entity/QuotationLineItem.java:95-96` |
| `vatRate` | `BigDecimal` → string | 百分数，列 `numeric(7,4)` ⇒ 最多 4 位小数（`"13"`、`"5"`）；未解析税率的存量行 `null` | `QuotationDTO.java:194`、`:269`；`QuotationLineItem.java:65-66` |
| `vatRateRatio` | `BigDecimal` → string | 比例，列 `numeric(12,8)` ⇒ **最多 8 位小数**（`"0.13"`）；存量行 `null` | `QuotationDTO.java:195`、`:270`；`QuotationLineItem.java:68-69` |
| `quoteCardValues` / `costingCardValues` | `String`（jsonb 列）→ **JSON 字符串（内容是 JSON 文本，需再 `JSON.parse`）** | 未计算 → `null`；**没绑核价模板时 `costingCardValues` 永远是 `null`**（核价值只在 `costingCardTemplateId != null` 时才算/写，见 §1.4） | `QuotationDTO.java:246-249`、`:299-302`；`QuotationLineItem.java:163-175`；`CardSnapshotService.java:739-748`、`:816-819` |
| `quoteExcelValues` / `costingExcelValues` | 同上 | 同上；形如 `"{\"rows\":[…]}"` | 同上 |
| `componentData[]` | `List<ComponentDataDTO>` | `{id, componentId, tabName, rowData(JSON 字符串), deletedRowKeys(JSON 字符串，默认 "[]"), subtotal(string), sortOrder}`；**不含** `fields` / `componentType` / `dataDriverPath` | `QuotationDTO.java:351-371` |
| `productAttributeValues` | `String`（jsonb）→ JSON 字符串 | 如 `"{}"`，可 `null` | `QuotationDTO.java:216` |
| `profit` | `String` → string | `profit_snapshot` 的 plain 文本；无则 `null` | `QuotationDTO.java:200`、`:274` |
| 其余（`id`/`templateId`/`productPartNo`/`compositeType`/`partVersionLocked`…） | 见源码 | `productPartNo` 为空时快照模式不建 driver 展开（前端跳过该行），夹具请给非空 | `QuotationDTO.java:188-260`；`F/pages/quotation/QuotationStep2.tsx:2325-2326` |

### 1.3 `costingCardStructure`「没有核价结构」的真实取值 —— **不会缺省，只有两种：JSON `null` 或 `tabs: []` 的对象**

`getById` 先 `QuotationDTO.from(q)`（结构字段初值 `null`），再从 `quotation_view_structure` 按 `view_kind` 回填（`QuotationService.java:391-407`）。由 G-2，字段**总是出现**在 JSON 里。

| 真实场景 | 线上返回 | 依据 |
|---|---|---|
| A. 未绑核价模板（`costingCardTemplateId: null`） | `"costingCardStructure": null`，`"costingCardTemplateId": null`，各行 `"costingCardValues": null` | `ensureStructure` 只在 `costingCardTemplateId != null` 时冻结 `COSTING_CARD`（`CardSnapshotService.java:240-245`）⇒ 无该行 ⇒ 字段保持 `null` |
| B. 绑了核价模板但结构没冻成（模板 `components_snapshot` 为空/非数组/异常，或非 DRAFT 存量单） | `"costingCardStructure": null`，`costingCardTemplateId` **非空** | `buildCardStructure` 返回 `null`（`:326-332`、`:482-486`）→ `upsertStructure` 遇 `null` 直接 return 不落库（`:272-273`）；`ensureStructure` 非 DRAFT 直接 return（`:229`）；`structure` 列 `NOT NULL`，故库里不会有「行在但为 null」 |
| C. 绑了核价模板且模板快照是空数组 `[]` | 对象：`{"version":2,"templateId":"…","templateKind":"COSTING","productAttributes":[],"tabs":[]}` | `buildCardStructure` 对空数组照常组装根对象（`:343-361`），`tabs` 为空 |

三种形态在详情页核价卡片上都落到「暂无组件数据」（`ReadonlyProductCard.tsx:289-301` → `normalComponents.length === 0` → `:1219`）。
**AC-4① 夹具建议用 A**（最贴近「没有核价结构的私有报价」，也是 `ensure-card-values` 之后不会被改写的稳定形态）。B/C 可作为补充反例。

非空时的结构形状（`CardSnapshotService.java:343-479`，camelCase）：

```json
{
  "version": 2, "templateId": "<uuid>", "templateKind": "COSTING", "productAttributes": [],
  "tabs": [{
    "componentId": "<uuid>", "componentCode": "…", "tabName": "…", "sortOrder": 0,
    "componentType": "NORMAL", "dataDriverPath": "…", "rowKeyFields": [],
    "fields": [{ "name": "…", "fieldType": "BASIC_DATA", "label": "…", "sortOrder": 0,
                 "isAmount": false, "isRequired": false, "isSubtotal": false, "width": 0,
                 "editable": false, "defaultValue": "", "basicDataPath": "…" }],
    "formulas": []
  }]
}
```
（`treeConfig` / `formula_assignments` / `elementCodeField` 等只在模板有时才出现；`defaultValue` / `basicDataPath` 只在模板字段有 `content` / `basic_data_path` 键时出现。）

### 1.4 卡片值 `costingCardValues` / `quoteCardValues`（JSON 字符串的**内容**）

正常形态（`CardSnapshotService.java:3576-3622` `buildTabNode`；`:3125-3133` `rowToNode`）：

```json
{"tabs":[{"componentId":"<uuid>","tabName":"…","componentType":"NORMAL",
  "baseRows":[{"driverRow":{…},"basicDataValues":{"<字段名>":"<值>"}}],
  "editRows":[], "formulaResults":[{"rowKey":"…","values":{…}}],
  "subtotal":"<十进制字符串，可无>", "subtotalByColumn":{"<列名>":"…"}, "resolvedRows":[…]}]}
```
（卡片值用 `DecimalJacksonCustomizer.newMapper()` 序列化，`CardSnapshotService.java:63` ⇒ 内部十进制同样是字符串。）

**生成失败标记**（`CardSnapshotService.java:104-107`、`:138-156`）：

| 形态 | 内容 | 写入处 |
|---|---|---|
| 通用哨兵 | `{"tabs":[],"__cardValueFailed":true}` | `orSentinel`：单行 build 返回 null/空（`:138-139`，用于 `:819`） |
| 带原文哨兵 | `{"tabs":[],"__cardValueFailed":true,"__errorMsg":"核价渲染失败: <异常文本>"}` | 核价树整单渲染抛异常（`:2096-2106` 及 `:816-818`），`__errorMsg` 恒以 `核价渲染失败: ` 开头 |

- `__cardValueFailed` 是 **JSON 布尔 `true`**（`n.put(…, true)`），`__errorMsg` 是字符串。
- 落库后经 jsonb 往返，读回的文本是 PostgreSQL 规范化格式（逗号/冒号后带空格、键按 jsonb 内部顺序重排），**语义等价、前端 `JSON.parse` 后无差别**；夹具用紧凑写法即可。（规范化后的确切字面**未实测**——本机库 0 条报价。）
- ⚠️ 核价侧失败哨兵**只会出现在 `costingCardTemplateId` 非空的单上**（核价值仅在绑了核价模板时才算，`:739`）。AC-4② 夹具应保持 `costingCardTemplateId` 与 `costingCardStructure` 非空、只把目标行 `costingCardValues` 换成哨兵 —— 这才是线上可达的形态。
- 详情页显示文案：`核价数据生成失败` + （有 `__errorMsg` 时）`：<原文>`（`F/pages/quotation/ReadonlyProductCard.tsx:705-708`）。

---

## 2. 页面加载接口清单（给 S-FLOW 造 mock）

前端 axios `baseURL='/api/cpq'`（`F/services/api.ts:3-4`），下表路径均为浏览器实际请求路径。
**建议**：`page.route('**/api/**')` 先按下表精确应答，**兜底分支打印 URL 并让用例失败**（而不是静默回 200）——这样漏 mock 的接口会暴露出来，而不是被兜底值掩盖。

### 2.0 所有页面公共（登录态 / 角色 / 布局）

| # | 方法 + 路径 | 何时调用 | 最小响应 `data` | 依据 |
|---|---|---|---|---|
| P-1 | `GET /api/cpq/auth/me` | `AuthGuard` 每次挂载 | `{"id":"<uuid>","username":"T261030_COST_admin","fullName":"…","role":"SYSTEM_ADMIN","forceChangePassword":false}` | 后端 `B/auth/resource/AuthResource.java:125-143`；前端 `F/router/AuthGuard.tsx:10-12`、`F/stores/authStore.ts:41-53` |
| P-2 | `GET /api/cpq/notifications/unread-count` | 布局挂载 + 每 30s 轮询 | `{"count":0}` | `B/notification/resource/NotificationResource.java:39-45`；`F/layouts/MainLayout.tsx:183-205` |
| P-3 | `GET /api/cpq/notifications?page=0&size=5` | 打开通知下拉时 | `[]` | `NotificationResource.java:30-37`；`MainLayout.tsx:192-197` |

**角色 → 显示位数**（`F/pages/quotation/quotationResultDisplayScale.ts:13-38`）：`SALES_REP` / `SALES_MANAGER` → 4 位；`PRICING_MANAGER` / `SYSTEM_ADMIN` / 其他 → 9 位。只由 P-1 的 `role` 决定。
**角色门禁（夹具角色要配对）**：
- 编辑页 `/quotations/:id/edit`：前端 `RoleGuard` 只放 `SALES_REP`/`SALES_MANAGER`/`SYSTEM_ADMIN`（`F/router/index.tsx:57`、`:132`）——**`PRICING_MANAGER` 进不去**。
- 详情页 `/quotations/:id`：前端不拦；后端 `QuotationResource` 放四种角色（`QuotationResource.java:47`）。
- 核价管理页：后端 `CostingOrderResource` 只放 `PRICING_MANAGER`/`SYSTEM_ADMIN`（`CostingOrderResource.java:34`）；且该页**强制 9 位**（`CostingReviewPage.tsx:266` 传 `9`，`ProductDetailViews.tsx:56-59` 有 `coid` 时走 `INDEPENDENT_COSTING` 恒 9）⇒ **AC-2 的 4 位分支无法在核价管理页出现**，只能在编辑页/详情页用销售角色测。

### 2.1 报价详情页 `/quotations/:id` →「核价单 · 产品卡片」

| # | 方法 + 路径 | 何时 | 最小响应 `data` | 依据 |
|---|---|---|---|---|
| D-1 | `GET /api/cpq/quotations/{id}` | 进页 | §2.4 夹具 C 的 QuotationDTO | `F/pages/quotation/QuotationDetail.tsx:124-133` |
| D-2 | `GET /api/cpq/templates/{customerTemplateId}/global-variable-bindings` | D-1 返回后，`customerTemplateId` 非空 | `[]` | `QuotationDetail.tsx:155-164`；`F/services/boundGlobalVariableService.ts:54-55` |
| D-3 | `GET /api/cpq/global-variables` | 产品明细挂载（非冻结模式） | `[]` | `F/pages/quotation/ProductDetailViews.tsx:86-111`；`F/services/globalVariableService.ts:42` |
| D-4 | `GET /api/cpq/templates/{lineItem.templateId}` | 报价侧：行有 `templateId` 时预热 enrich；结构不可用时报价卡也会走它 | TemplateDTO（§2.5） | `ProductDetailViews.tsx:113-145`；`ReadonlyProductCard.tsx:304-336`；`F/pages/quotation/enrichComponentData.ts:56` |
| D-5 | `POST /api/cpq/components/batch-expand` | **仅当**某侧该行卡片值为空（`costingCardValues`/`quoteCardValues` 为 `null`）且组件有 `fields` | `{"results":[]}` | `ReadonlyProductCard.tsx:383-386`；`F/pages/quotation/useDriverExpansions.ts:283-296`、`:381`；`F/services/componentService.ts:72-76` |
| D-6 | `POST /api/cpq/formulas/batch-evaluate` | 有 path/全局变量类 token 的字段时（详情页对核价卡**不做**快照闸门） | `{"results":[]}` | `ReadonlyProductCard.tsx:425`；`F/services/formulaService.ts:75-78` |
| D-7 | `GET /api/cpq/config-templates/{id}` | 仅 `LIST_FORMULA` 字段 | —（夹具不要用 `LIST_FORMULA`） | `F/pages/quotation/useConfigTemplates.ts:51`；`F/services/configTemplateService.ts:74-75` |
| D-8 | `GET /api/cpq/quotations/{id}/comparison-view/meta`、`/data?frozen=true`、`/config?bucket=SALES` | 切到「比对视图」时（AC-7③） | 后端 DTO：`ComparisonMetaDTO` / `ComparisonDataDTO` / `ComparisonConfigDTO`（本审核**未给样本**，见 §3 未做到） | `F/pages/quotation/ComparisonBoard.tsx:72-74`；`B/costing/resource/ComparisonViewResource.java:33-56` |

- 「Excel 视图」（AC-7③）在详情页**零网络**，只读 D-1 里的 `costingExcelColumns` + 行 `costingExcelValues`（`ProductDetailViews.tsx:287-301`）。
- 详情页**不调** `ensure-card-values`；失败哨兵 / `null` 原样渲染。

### 2.2 报价编辑页 `/quotations/:id/edit` → 第 2 步 →「核价单 · 产品卡片」

进页默认停在第 1 步（`currentStep` 初值 0，`QuotationWizard.tsx:240`），需点「下一步」（`next()`，`:2012-2024`，会先 `handleSaveDraft(true)`，**无改动时不发请求**，`:1680-1683`）。

| # | 方法 + 路径 | 何时 | 最小响应 `data` | 依据 |
|---|---|---|---|---|
| E-1 | `GET /api/cpq/quotations/{id}` | 进页 | §2.4 夹具 C | `QuotationWizard.tsx:832` |
| E-2 | `POST /api/cpq/quotations/{id}/ensure-card-values` | **任一行** `quoteCardValues` 或 `costingCardValues` 为空/失败哨兵时（夹具 A「未绑核价模板」必然触发，因为该单 `costingCardValues` 永远 `null`） | 完整 QuotationDTO（同 E-1，`cardValuesWarming:false`）；若返回 `{cardValuesWarming:true}` 前端会每 800ms 重试最多 20 次 | `QuotationWizard.tsx:841-860`、`:1054-1076`；`F/pages/quotation/cardValuesWarm.ts:20-30`；`QuotationResource.java:295-307` |
| E-3 | `GET /api/cpq/customers/{customerId}` | E-1 后 | CustomerDTO：`{"id","name","code","defaultCurrency":"USD","defaultVatRate":"13","status":"ACTIVE",…}`（十进制为字符串） | `QuotationWizard.tsx:621-623`、`:886-893`；`B/.../CustomerDTO.java:15-36` |
| E-4 | `GET /api/cpq/customers/{customerId}/contacts` | E-1 后 | `[]` | `QuotationWizard.tsx:895-917` |
| E-5 | `GET /api/cpq/customers?…` | 客户下拉组件挂载 | PageResult：`{"content":[{…CustomerDTO}],"page":0,"size":20,"totalElements":1,"totalPages":1}` | `F/components/CustomerSelect.tsx:50-60`；`B/common/dto/PageResult.java:21-25` |
| E-6 | `GET /api/cpq/product-categories?status=ACTIVE` | 第 1 步表单挂载 | `[]`（或 `[{"id","name",…}]`） | `F/pages/quotation/QuotationCreateForm.tsx:133`；`F/services/productCategoryService.ts:16-17` |
| E-7 | `GET /api/cpq/templates/match-customer-quote?customerId=…&categoryId=…` | 第 1 步，选了分类后 | `{"matchType":"CUSTOMER_SPECIFIC","templates":[{TemplateDTO…}]}`（枚举：`CUSTOMER_SPECIFIC`/`GENERAL_FALLBACK`/`MIXED`/`NONE`） | `QuotationCreateForm.tsx:175-180`；`B/template/dto/TemplateMatchResult.java:21-29` |
| E-8 | `GET /api/cpq/global-variables` | 向导与第 2 步各一次 | `[]` | `QuotationWizard.tsx:328-339`；`QuotationStep2.tsx:4254` |
| E-9 | `GET /api/cpq/templates/{customerTemplateId}/excel-view-config/effective-columns` | `customerTemplateId` 非空 | `[]` | `QuotationWizard.tsx:350`；`F/services/templateService.ts:97` |
| E-10 | `GET /api/cpq/templates/{costingCardTemplateId}` | 🔑 **第 2 步只要 `costingCardTemplateId` 非空就必调**——编辑页核价卡片的页签/字段是按**这个模板的 `componentsSnapshot`** 重建的，不是按 `costingCardStructure` | TemplateDTO（§2.5），`componentsSnapshot` 为 JSON 字符串、snake_case；返回失败或空数组 → 显示「核价模板未配置任何组件」 | `QuotationStep2.tsx:4335-4356`、`:4373-4421`、`:4871-4878` |
| E-11 | `GET /api/cpq/templates/{customerTemplateId}` | 报价结构 `tabs` 为空时；第 1 步分类回填 | TemplateDTO | `QuotationStep2.tsx:4282-4310`；`QuotationCreateForm.tsx:150-160` |
| E-12 | `POST /api/cpq/components/batch-expand`、`POST /api/cpq/formulas/batch-evaluate` | 仅卡片值缺失 / 有 path token 时（同 D-5/D-6） | `{"results":[]}` | 同上 |
| E-13 | `PUT /api/cpq/quotations/{id}/draft` | AC-7④「只改关联项目后保存」；或「下一步」时确有改动 | **`SaveDraftResponse`（不是 QuotationDTO）**：单头字段 + `lineItems:[{id,tempId,partVersionLocked,quoteCardValues,costingCardValues,quoteExcelValues,costingExcelValues}]`；**不含 `currency`/`exchangeRate`/结构/`vat*`**（见 §5-1） | `QuotationService.java:1096-1097`；`B/quotation/dto/SaveDraftResponse.java:12-73`；请求体类 `B/quotation/dto/SaveDraftRequest.java` |
| E-14 | `POST /api/cpq/quotations/{id}/ensure-excel-values` | 切到「Excel 视图」且当前侧有行缺 `*ExcelValues` | 完整 QuotationDTO | `QuotationStep2.tsx:4719-4732` |

- 编辑页「报价单/核价单」切换、Excel/比对切换本身不另发请求（比对视图同 D-8，桶 `SALES`）。
- 「未绑核价模板」时第 2 步整页显示「该报价单未绑定核价模板」，不渲染核价卡片（`QuotationStep2.tsx:4862-4869`）——与需求文档 ② 一致。

### 2.3 核价管理页 `/costing-orders/:coid/review`（`CostingReviewPage`）

| # | 方法 + 路径 | 何时 | 最小响应 `data` | 依据 |
|---|---|---|---|---|
| C-1 | `GET /api/cpq/costing-orders/{coid}` | 进页（**唯一**的加载请求；冻结模式不发 `/global-variables`、`/templates`） | `CostingOrderDetailDTO`，见下 | `F/pages/quotation/CostingReviewPage.tsx:97-110`；`B/quotation/resource/CostingOrderResource.java:64-68`；`QuotationService.java:4352-4393` |
| C-2 | `GET /api/cpq/costing-orders/{coid}/version-options?...` | 仅用户展开版本下拉时（懒加载） | 不需要 | `F/pages/quotation/VersionSelectDropdown.tsx:54-62` |
| C-3 | 比对视图 | 同 D-8，`bucket=FINANCE` | 同 D-8 | `ProductDetailViews.tsx:70`、`:270-276` |

`CostingOrderDetailDTO`（`B/quotation/dto/CostingOrderDetailDTO.java:16-70`）：

```json
{
  "costingOrderId": "<uuid>", "quotationId": "<uuid>", "costingOrderNumber": "T261030_COST_HJ-0001",
  "status": "PENDING", "rejectReason": null, "totalAmount": "888.123456789",
  "frozenDto": "<字符串：整份 QuotationDTO 的 JSON 文本 + 顶层 gvDefs 数组>",
  "createdAt": "2026-10-30T10:00:00+08:00", "reviewedAt": null,
  "costingRender": { "<lineItemId>": { "costingCardValues": "<JSON 字符串>", "costingExcelValues": "<JSON 字符串或 null>" } },
  "costingTotalAmount": "1717.135540717",
  "versionOverrides": [], "editable": true
}
```
- `frozenDto` 是**字符串**，内容 = `mapper.valueToTree(QuotationDTO)` 再加 `gvDefs`（`CostingFreezeService.java:187-196`；`mapper` 是注入的 Quarkus 全局 ObjectMapper，`:52-54`，已带 G-1 ⇒ 内部十进制也是字符串）。前端 `JSON.parse` 后当作 QuotationDTO 用，并用 `costingRender[lineId]` 覆写各行 `costingCardValues`/`costingExcelValues`、用 `costingTotalAmount` 覆写顶层（`CostingReviewPage.tsx:62-83`）。
- `costingRender` 是对象（`JsonNode`），值里的 `costingCardValues` 仍是 **JSON 字符串**（`CostingFreezeService.java:144-160`）；缺失时前端退回 `frozenDto` 里的行值。
- `status`：`PENDING`/`APPROVED`/`REJECTED`/`WITHDRAWN`；`editable = status === 'PENDING'`。
- 「核价单据总价」读 `costingTotalAmount`，显示为 `¥` + 数字（`ProductDetailViews.tsx:381-395`），本任务不动（AC-7②）。
- ⚠️ 存量核价单若在十进制字符串化之前冻结，`frozenDto` 里的十进制可能是 JSON 数字（**推断，未实查**——本机库 0 条核价单）。夹具按字符串造即可。

### 2.4 夹具 C 最小 QuotationDTO 样本（字段名 = 后端真实序列化名）

> ID 用合法 UUID 形态；名称类加 `T261030_COST_` 前缀。省略的 DTO 字段线上为 `null`（G-2），前端对本任务路径不依赖它们。

```json
{
  "code": 200, "message": "success",
  "data": {
    "id": "26103000-0000-4000-8000-0000000000a1",
    "quotationNumber": "T261030_COST_QT-C",
    "customerId": "26103000-0000-4000-8000-0000000000c1",
    "currency": "USD",
    "exchangeRate": "7.1234",
    "exchangeRateDate": "2026-10-30", "exchangeRateSource": null,
    "exchangeRateCapturedAt": "2026-10-30T10:00:00+08:00",
    "name": "T261030_COST_夹具C", "projectName": "T261030_COST_项目",
    "status": "DRAFT",
    "totalAmount": "888.123456789", "originalAmount": "888.123456789",
    "finalDiscountRate": null, "defaultVatRate": "13", "defaultVatSource": "CUSTOMER",
    "customerTemplateId": "26103000-0000-4000-8000-0000000000b1",
    "categoryId": null,
    "costingCardTemplateId": "26103000-0000-4000-8000-0000000000b2",
    "quoteCardStructure": { "…": "同 §1.3 形状，templateKind=QUOTATION" },
    "quoteExcelStructure": null,
    "costingCardStructure": {
      "version": 2, "templateId": "26103000-0000-4000-8000-0000000000b2", "templateKind": "COSTING",
      "productAttributes": [],
      "tabs": [{
        "componentId": "26103000-0000-4000-8000-0000000000d1", "componentCode": "T261030_COST_C1",
        "tabName": "T261030_COST_核价明细", "sortOrder": 0, "componentType": "NORMAL",
        "dataDriverPath": "$T261030_COST_view", "rowKeyFields": [],
        "fields": [{
          "name": "核价金额", "fieldType": "BASIC_DATA", "label": "核价金额", "sortOrder": 0,
          "isAmount": true, "isRequired": false, "isSubtotal": true, "width": 0, "editable": false,
          "basicDataPath": "$T261030_COST_view.amount"
        }],
        "formulas": []
      }]
    },
    "costingExcelStructure": null,
    "quoteExcelColumns": [], "costingExcelColumns": [],
    "userDataVersion": 0,
    "cardValuesWarming": false,
    "approvalHistory": [],
    "lineItems": [{
      "id": "26103000-0000-4000-8000-0000000000e1",
      "productId": null, "templateId": "26103000-0000-4000-8000-0000000000b1",
      "vatRate": "13", "vatRateRatio": "0.13", "vatSource": "CUSTOMER",
      "productPartNo": "T261030_COST_P1", "productName": "T261030_COST_产品1",
      "productAttributeValues": "{}",
      "subtotal": "888.123456789",
      "sortOrder": 0, "partVersionLocked": 1, "compositeType": "SIMPLE", "parentLineItemId": null,
      "processes": [], "compositeProcesses": [], "componentData": [],
      "quoteCardValues": "<报价侧 JSON 字符串>",
      "costingCardValues": "{\"tabs\":[{\"componentId\":\"26103000-0000-4000-8000-0000000000d1\",\"tabName\":\"T261030_COST_核价明细\",\"componentType\":\"NORMAL\",\"baseRows\":[{\"driverRow\":{\"amount\":\"1717.135540717\"},\"basicDataValues\":{\"核价金额\":\"1717.135540717\"}}],\"editRows\":[],\"formulaResults\":[],\"subtotal\":\"1717.135540717\"}]}",
      "quoteExcelValues": "{\"rows\":[]}",
      "costingExcelValues": "{\"rows\":[]}"
    }]
  }
}
```

变体：
- **AC-3**：`currency:"CNY"`、`exchangeRate:"1"`。「币种为空」变体（`currency:""` 或 `null`）**线上不可达**（列 `NOT NULL`、建单必有值），只作前端兜底用例。
- **AC-4①（无核价结构）**：`costingCardTemplateId:null`、`costingCardStructure:null`、`costingExcelStructure:null`、`costingExcelColumns:null`、各行 `costingCardValues:null`、`costingExcelValues:null`；报价侧照旧。
- **AC-4②（生成失败）**：保持夹具 C，只改目标行 `"costingCardValues":"{\"tabs\":[],\"__cardValueFailed\":true,\"__errorMsg\":\"核价渲染失败: T261030_COST_mock\"}"`。
- **AC-7②（核价管理页）**：把上面 `data` 整体 `JSON.stringify` 后（顶层加 `"gvDefs":[]`）放进 C-1 的 `frozenDto`，`costingRender` 用同一份 `costingCardValues`，`costingTotalAmount:"1717.135540717"`。

🚫 **未验证**：上面这份核价结构 + 卡片值能否让**前端计算链**在编辑页/详情页算出 `1717.135540717`。字段名与形态是后端真实的，但「BASIC_DATA 列 + `isSubtotal:true` + 一行 `basicDataValues`」→ 页签小计 → 产品小计这条推导属于前端计算（`QuotationStep2.tsx:2038-2236`、`:2311-2390`），本审核未运行验证。另外编辑页核价卡片按 **E-10 模板快照**重建页签（snake_case：`componentId`、`tabName`、`componentType`、`data_driver_path`、`fields[].field_type/is_subtotal/is_amount/basic_data_path`），E-10 的 `componentsSnapshot` 必须与 `costingCardStructure.tabs` 用**同一个 `componentId`**，否则快照值对不上组件（`QuotationStep2.tsx:2331-2332`）。若测试员按此造出的金额不对，按 `test.md §3` 停下报主线，不要翻实现。

### 2.5 TemplateDTO（`GET /api/cpq/templates/{id}`）最小样本

`B/template/resource/TemplateResource.java:60-63`；`B/template/dto/TemplateDTO.java`：`componentsSnapshot` 是 **JSON 字符串**（`:93`），`productAttributes` 是**数组**（`:91`）。

```json
{"id":"26103000-0000-4000-8000-0000000000b2","name":"T261030_COST_核价模板","templateKind":"COSTING",
 "status":"PUBLISHED","productAttributes":[],"subtotalFormula":[],"components":[],"warnings":[],
 "componentsSnapshot":"[{\"componentId\":\"26103000-0000-4000-8000-0000000000d1\",\"componentCode\":\"T261030_COST_C1\",\"tabName\":\"T261030_COST_核价明细\",\"sortOrder\":0,\"componentType\":\"NORMAL\",\"data_driver_path\":\"$T261030_COST_view\",\"fields\":[{\"name\":\"核价金额\",\"field_type\":\"BASIC_DATA\",\"sort_order\":0,\"is_amount\":true,\"is_subtotal\":true,\"basic_data_path\":\"$T261030_COST_view.amount\"}],\"formulas\":[]}]"}
```

---

## 3. 单列确认：`quotation_line_item` 只有一个小计列，且是报价侧

本机 docker 库只读查询（2026-10-30 14:02 采样）：

```
$ docker exec cpq-jh-postgres psql -U postgres -d cpq_db -X -A -c "SELECT column_name, data_type, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_schema='public' AND table_name='quotation_line_item' AND (column_name ILIKE '%subtotal%' OR column_name ILIKE '%costing%' OR column_name ILIKE '%vat%' OR column_name ILIKE '%card_values%') ORDER BY column_name;"
column_name|data_type|numeric_precision|numeric_scale
costing_card_values|jsonb||
costing_excel_values|jsonb||
costing_summary_id|uuid||
quote_card_values|jsonb||
subtotal|numeric|26|12
vat_effective_from|date||
vat_rate|numeric|7|4
vat_rate_ratio|numeric|12|8
vat_resolved_at|timestamp with time zone||
vat_source|character varying||
(10 rows)
```
同库 `flyway_schema_history` 最新 `455|t`；`quotation` / `quotation_line_item` / `quotation_view_structure` 行数 `0|0|0`。

- 名字含 `subtotal` 的列只有 `subtotal` 一个（`numeric(26,12)`）；核价相关只有两个 jsonb 值列与 `costing_summary_id`，**没有核价小计列**。
- 实体同样只有一个 `subtotal`（`entity/QuotationLineItem.java:95-96`），DTO 原样透传（`QuotationDTO.java:217`、`:278`）。
- 它是**报价侧**小计：报价卡片值落库时由 `assignQuoteCardValues` 覆盖 `li.subtotal`（`CardSnapshotService.java:787` 注释「赋卡片值的同一个动作里覆盖 li.subtotal」，调用在 `:809-813`），核价值写入只动 `costingCardValues`（`:816-819`）；Flyway 历史最早 `V11__create_quotation_tables.sql:58` 建 `subtotal`，此后没有给本表新增第二个小计列（按 `grep -l quotation_line_item | xargs grep -i subtotal` 检出的迁移逐条看过，均为注释或别表）。
- 与立项文档 ④ 的实查结论一致。
- ⚠️ 本机库是 `jh` 备用库，不是开发默认库 `cpq_db_0724`（本机不可达）；列结构结论以实体 + 迁移为准，本机库只作旁证。

---

## 4. 零改动确认（原始输出）

```
### git diff --stat -- cpq-backend Dockerfile
[exit=0]
### git diff --stat --cached -- cpq-backend Dockerfile
[exit=0]
### git status --short -- cpq-backend
[exit=0]
### git diff master...HEAD --stat -- cpq-backend Dockerfile
[exit=0]
### diff --stat (tracked Dockerfiles + backend, worktree vs HEAD)   # 全部 12 个受控 Dockerfile 一并纳入
[exit=0]
### untracked under cpq-backend
[exit=0]
```
（输出为空即无差异。仓库根并没有 `Dockerfile`，受控的 Dockerfile 在 `cpq-backend/src/main/docker/` 与 `deploy/` 下，已一并按路径列入比较，结果仍为空。）

同时刻 worktree 全量 `git status --short`（仅供参照，均不属后端）：
```
 M cpq-frontend/src/pages/quotation/QuotationStep2.tsx
 M cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
 M cpq-frontend/src/pages/quotation/quotation.css
 M dev-docs/…/task-261030-核价卡片小计展示调整/api.md
 M dev-docs/…/task-261030-核价卡片小计展示调整/fronttask.md
?? cpq-frontend/src/pages/quotation/CostingSubtotalSummary.tsx
```
⇒ 无新增 SQL / Flyway；部署 SQL 基线不变。

---

## 5. 与 `api.md §2` 的出入（只报告，未改 `api.md`）

| # | `api.md §2` 的说法 | 后端真实情况 | 影响 |
|---|---|---|---|
| **5-1** | 汇率：「编辑：`quoteExchangeRate` prop」；币种同理 | 🔴 **编辑页保存后，单头的 `currency` 与 `exchangeRate` 会从前端状态里消失**。保存接口 `PUT /quotations/{id}/draft` 返回的是瘦身版 `SaveDraftResponse`，**没有 `currency` / `exchangeRate` 字段**（`SaveDraftResponse.java:12-60` 字段清单）；前端 `setQuotationPreservingStructures` 用 `{...resData}` 整体替换 `quotation`，只为 4 份结构和 `status/quotationNumber/baseCurrency/snapshotCustomerName/customer` 兜回旧值（`QuotationWizard.tsx:956-977`，`:1717` 在显式保存后调用）——兜底名单里写的是**后端并不存在的 `baseCurrency`**，真正的 `currency` / `exchangeRate` 不在名单里 ⇒ 保存成功后 `quoteCurrency` 退回 `'CNY'`、`quoteExchangeRate` 退回 `'1'`（`QuotationWizard.tsx:2241-2242`）。**推断（代码阅读），未运行验证。** 若成立：AC-7④「只改关联项目后保存」之后，核价卡片会从两列（CNY+USD）塌成一列 CNY；报价侧 `task-261028` 的 `888.123456789 USD` 也会变成 `… CNY`。刷新页面后恢复（重新走 `getById`）。这不是本任务引入的（该合并逻辑与 `SaveDraftResponse` 都早于本任务），但会直接撞上 AC-6/AC-7 的观测。修法属前端：把 `currency`、`exchangeRate`（以及需要的话 `exchangeRateDate`）加进 `:973` 的兜底名单；或后端在 `SaveDraftResponse` 补这两个只读字段（改契约，需主线裁决）。 |
| 5-2 | 只读币种：`quotation.currency \|\| quotation.baseCurrency \|\| 'CNY'` | `baseCurrency` **后端不存在**，该段兜底恒不生效；`currency` 实际恒非空（`NOT NULL DEFAULT 'CNY'`） | 无功能影响；「币种为空」只是前端防御分支，AC-3 的空币种夹具线上不可达 |
| 5-3 | 汇率：`quotation.exchangeRate \|\| '1'`；「缺失或 0 沿用按 1」 | `exchangeRate` 是**字符串**（如 `"7.1234"`、`"1"`），列 `NOT NULL DEFAULT 1`，`getById` 永远有值；`"0"` / 缺失只能来自手工改库 | 无影响；`"0"` 是字符串，`\|\| '1'` 不会把它换掉，「为 0 按 1」要靠组件内部判断（`api.md §3.1` 已写由组件处理） |
| 5-4 | 核价卡片结构「为空时核价卡片组件为空」 | 「为空」有**两种真实形态**：JSON `null`（未绑核价模板，或绑了但结构没冻成），以及 `{"…","tabs":[]}` 对象（模板快照为空数组）；**不会缺省**。详见 §1.3 | 前端判「无核价数据」时两种都要覆盖（当前前端以 `normalComponents.length === 0` 判，两种都能命中） |
| 5-5 | 核价卡片值 `lineItem.costingCardValues`；失败标记 `__cardValueFailed` / `__errorMsg` | `costingCardValues` 是 **JSON 字符串**（需二次解析），不是对象；未计算时 `null`，**未绑核价模板的单永远 `null`**。失败标记 `__cardValueFailed` 是布尔 `true`，`__errorMsg` 只在核价树整单渲染失败时出现、以 `核价渲染失败: ` 开头。详见 §1.4 | 夹具按字符串造；附带现象：未绑核价模板的单**每次打开编辑页都会调一次 `ensure-card-values`**（`shouldWarmCardValues` 把核价值 `null` 视为缺失，`cardValuesWarm.ts:24-29`，而后端对这类单永不写核价值）——既有行为，非本任务范围 |
| 5-6 | `vatRateRatio` / `vatRate`「报价行字段」 | 均为**字符串**；`vatRateRatio` 列 `numeric(12,8)` 最多 8 位小数，`vatRate` `numeric(7,4)` 最多 4 位；存量行可为 `null` | AC-8 的 `"0.99999999999"`（11 位）、AC-5 的「非数字 / 负数 / >1」都是**线上不可达**的组件级夹具，只覆盖前端防御；不影响验收，但亲验时不要期待真实数据能复现这些值 |

另：`api.md` 在本 worktree 中处于**已修改未提交**状态（`git status` 显示 ` M`，§3.1 写明「开工时补定」），本审核读的是该修改后版本。

---

## 6. 发现但没动的问题（超出 B-1 范围）

1. **§5-1 保存后币种/汇率丢失**（前端 `QuotationWizard.tsx:973`）——见上，建议主线让前端或测试员先用 mock 复现一次再裁决。
2. **既有 N+1（后端，与本任务无关）**：`QuotationDTO.LineItemDTO.from` 在每行内调 `Product.findById(li.productId)`（`QuotationDTO.java:285-291`），`loadLineItems` 对每行调用它（`QuotationService.java:3947-3948`）⇒ `productId` 非空的行数 = 额外 SQL 条数（V5 链路行 `productId` 多为 `null` 时不触发）。子表查询默认已整单批量（`cpq.getbyid-batch` 默认 ON，`:3904-3908`），逐行分支（`:3995-3996`）只在关闭该开关时走。只报告，未改。
3. 未绑核价模板的单每次打开编辑页都触发一次 `ensure-card-values`（§5-5 附带现象），对大单是一次多余的整单计算请求。只报告。

---

## 6+. 补充夹具依据（B-1 补充，2026-10-30；服务 AC-7③、AC-10）

> 编号说明：主线指令要求追加「§6 补充夹具依据」。本文件第一轮已有 §6「发现但没动的问题」，按「只追加、不改已有内容」的纪律不重排编号，本节记作 **§6+**，子节为 §6+.1 ~ §6+.3。
> 全部依据为只读源码（同第一轮 HEAD `1ac02a1d`），未启动后端、未发请求；§0 的 G-1/G-2/G-3 三条序列化规则在本节同样适用。

### 6+.1 `PUT /api/cpq/quotations/{id}/draft` → `SaveDraftResponse`（AC-10）

链路：`QuotationResource#saveDraft`（`B/quotation/resource/QuotationResource.java:174-176`，`:187` 取 `quotationService.saveDraft` 的返回，`:259` `ApiResponse.success(dto)` 原样包信封，中间只读 `touchedLineItemIds`，不改响应）→ `QuotationService#saveDraft` 末尾 `SaveDraftResponse.fromHeader(q)` + `resp.userDataVersion = newVersion` + 变化行（`B/quotation/service/QuotationService.java:1096-1118`）。

**完整字段清单**（`B/quotation/dto/SaveDraftResponse.java`；类上**无** `@JsonInclude`，⇒ 除注明外 null 照常输出 `"key": null`）：

| 行 | 字段 | Java → JSON | 空值形态 / 取值 |
|---|---|---|---|
| `:52-53` | `touchedLineItemIds` | `List<UUID>`，`@JsonIgnore` + `transient` | 🚫 **不出现在 JSON 里**（进程内传给 Resource 用） |
| `:56` | `id` | UUID → string | 非空 |
| `:57` | `quotationNumber` | String | 非空 |
| `:58` | `customerId` | UUID → string | 非空 |
| `:59` | `name` | String | 可 null |
| `:60-63` | `contactId` / `contactName` / `contactPhone` / `contactEmail` | UUID / String ×3 | 可 null |
| `:64` | `projectName` | String | 可 null；AC-10 改的就是它（「关联项目」） |
| `:65` | `opportunityId` | String | 可 null |
| `:66` | `salesRepId` | UUID → string | 可 null |
| `:67-69` | `quoteType` / `priority` / `stage` | String | 实体有默认值时非空，可 null |
| `:70` | `expectedCloseDate` | `LocalDate` → `"yyyy-MM-dd"` | 可 null |
| `:71` | `status` | String | 非空（DRAFT） |
| `:72` | `totalAmount` | BigDecimal → string | 可 null |
| `:73` | `expiryDate` | LocalDate → string | 可 null |
| `:74` | `paymentTerms` | String | 可 null |
| `:75` | `deliveryCycle` | Integer → number | 可 null |
| `:76-80` | `originalAmount` / `systemDiscountRate` / `finalDiscountRate` / `taxRate` / `taxAmount` | BigDecimal → string | 可 null |
| `:81` | `discountAdjustmentReason` | String | 可 null |
| `:82` | `isManuallyAdjusted` | Boolean → boolean | 可 null |
| `:83-84` | `sourceQuotationId` / `assignedApproverId` | UUID → string | 可 null |
| `:85-87` | `customerTemplateId` / `categoryId` / `costingCardTemplateId` | UUID → string | 可 null；`categoryId` 取自 `q.productCategoryId`（`:166`） |
| `:88` | `remarks` | String | 可 null |
| `:89-93` | `snapshotCustomerName` / `…Level` / `…Region` / `…Industry` / `…Address` | String | 可 null |
| `:94-95` | `createdAt` / `updatedAt` | `OffsetDateTime` → ISO-8601 字符串 | 一般非空 |
| `:98` | `userDataVersion` | Integer → number | **非空**：本次保存后的新版本号（有写入时 = 原值 + 1，`QuotationService.java:1086-1089`，回填用自增返回值 `:1099`） |
| `:101` | `lineItems` | `List<Line>` → array | **默认 `[]`**（字段初值 `new ArrayList<>()`）；只装本次 added + modified 的行，**只改单头时为 `[]`**（`delta.writtenIds` 为 null 或空 ⇒ `loadChangedLinesLight` 返回空表，`:1100-1118`、`:3739-3740`） |

`Line`（`SaveDraftResponse.java:116-132`）：

| 行 | 字段 | Java → JSON | 空值形态 |
|---|---|---|---|
| `:118` | `id` | UUID → string | 非空（DB id；新行在此拿到后端生成的 id） |
| `:125-126` | `tempId` | String，**字段级 `@JsonInclude(NON_NULL)`** | **只在 added 行出现**（原样回传请求里的 tempId）；modified 行该键**不出现** ⇒ modified 行恰好 6 个键、added 行 7 个键 |
| `:127` | `partVersionLocked` | Integer → number | 可 null |
| `:128-131` | `quoteCardValues` / `costingCardValues` / `quoteExcelValues` / `costingExcelValues` | String → **JSON 字符串** | 可 null（被失效时）；取自 `…::text`（`QuotationService.java:3743-3757`），即 PostgreSQL jsonb 规范化文本 |

**🔑 与 `QuotationDTO.from` 的差集**（AC-10 要证明「响应不含币种/汇率」就看这张表）：`SaveDraftResponse` **没有**以下 7 个 `QuotationDTO.from` 会填的字段 —— `currency`、`exchangeRate`、`exchangeRateDate`、`exchangeRateSource`、`exchangeRateCapturedAt`（`QuotationDTO.java:17-21`、`:140-144`），以及 `defaultVatRate`、`defaultVatSource`（`:44-45`、`:167-168`）。另外 `QuotationDTO` 中不经 `from()` 填的 `assignedApproverName` / 4 份结构 / 两份 Excel 列 / `categoryName` / `templateName` / `approvalHistory` / `cardValuesWarming` / `backfill` / `dsBackfill` 也都没有。行级没有 `vatRate` / `vatRateRatio` / `subtotal` / `componentData` 等任何 `LineItemDTO` 字段。
- ⚠️ 该类头注 `SaveDraftResponse.java:24-26` 写「单头字段与 `QuotationDTO.from` 一一对应…少一个字段就会把本地的那个字段抹成 undefined」——**与代码不符**（少了上述 7 个），正是 §5-1 的成因。只报告，未改。
- `defaultVatRate` / `defaultVatSource` 的丢失对本任务无影响：前端只有客户管理页读同名字段（`F/pages/customer/CustomerManagement.tsx:197`、`:388`、`:554`），报价页面不读（`grep` 实查）。
- 请求体类 `B/quotation/dto/SaveDraftRequest.java` 同样无 `currency` / `exchange*`（主线已核实），保存改不了它们。

**最小可用响应样本**（AC-10「只改关联项目」= 只改单头 ⇒ `lineItems: []`）：

```json
{
  "code": 200, "message": "success",
  "data": {
    "id": "26103000-0000-4000-8000-0000000000a1",
    "quotationNumber": "T261030_COST_QT-C",
    "customerId": "26103000-0000-4000-8000-0000000000c1",
    "name": "T261030_COST_夹具C",
    "contactId": null, "contactName": null, "contactPhone": null, "contactEmail": null,
    "projectName": "T261030_COST_项目-改",
    "opportunityId": null, "salesRepId": null,
    "quoteType": null, "priority": null, "stage": null, "expectedCloseDate": null,
    "status": "DRAFT",
    "totalAmount": "888.123456789", "expiryDate": "2026-11-29",
    "paymentTerms": null, "deliveryCycle": null,
    "originalAmount": "888.123456789", "systemDiscountRate": null, "finalDiscountRate": null,
    "taxRate": null, "taxAmount": null,
    "discountAdjustmentReason": null, "isManuallyAdjusted": null,
    "sourceQuotationId": null, "assignedApproverId": null,
    "customerTemplateId": "26103000-0000-4000-8000-0000000000b1",
    "categoryId": null,
    "costingCardTemplateId": "26103000-0000-4000-8000-0000000000b2",
    "remarks": null,
    "snapshotCustomerName": "T261030_COST_客户", "snapshotCustomerLevel": null,
    "snapshotCustomerRegion": null, "snapshotCustomerIndustry": null, "snapshotCustomerAddress": null,
    "createdAt": "2026-10-30T10:00:00+08:00", "updatedAt": "2026-10-30T10:05:00+08:00",
    "userDataVersion": 1,
    "lineItems": []
  }
}
```

- `userDataVersion` 应为请求里 `baseVersion` + 1（夹具 C 的 `getById` 给 `0` ⇒ 这里回 `1`）。前端用它推进本地基线，回得比本地小时 `noteUserDataVersion` 单调不回退（`F/pages/quotation/QuotationWizard.tsx:956-961`），故不会报错，但为贴近真实请回 +1。
- 若测试改了明细（本 AC 不需要），modified 行样本：`{"id":"<行 id>","partVersionLocked":1,"quoteCardValues":"<JSON 字符串或 null>","costingCardValues":"…","quoteExcelValues":"…","costingExcelValues":"…"}`（6 键，无 `tempId`）；added 行再加 `"tempId":"<请求里的 tempId>"`。前端按 `id` 认领、`id` 缺失按 `tempId` 认领，非 null 的值才回填（`QuotationWizard.tsx:987` 起 `syncLineItemsFromResponse`）。
- 版本冲突时后端返回 409（`baseVersion` 过期），本 AC 不涉及；mock 不要返回 409。
- 请求体格式：本次只改单头 ⇒ 前端发 `toIncrementalPayload(payload, EMPTY_LINE_DIFF, baseVersion)`（`QuotationWizard.tsx:1700-1701`），明细三数组为空。键集合以测试员的 master / 分支 A/B 为准，本审核不预测。

### 6+.2 详情页「核价单 · Excel 视图」夹具（AC-7③a）

渲染链（零网络，只读 `getById` 已带的数据）：`ProductDetailViews.tsx:287-301` → `ReadonlyExcelView`（`F/pages/quotation/ReadonlyExcelView.tsx:21-61`）列 = `quotation.costingExcelColumns` 去掉 `hidden:true` 的列；行 = `useExcelSnapshotRows` 解析各行 `costingExcelValues`（`F/pages/quotation/useExcelSnapshotRows.ts:24-53`）。
- 出空态「暂无数据」的条件：列为空，**或**所有行的 `costingExcelValues` 都为 null/空串（`__noData = !json`，`ReadonlyExcelView.tsx:25-28`、`useExcelSnapshotRows.ts:48`）。
- 非树：每个报价行只取 `rows[0]` 一行，按 `col_key` 取值；值为 `null` / `''` / `'—'` 显示为空（`useExcelSnapshotRows.ts:19-22`、`:33-37`）。
- 树形分支（`rows[]` 里有 `__nodeId`）**线上不会出现**：后端核价 Excel 值的四处生产调用全部传 `costingTree=false`（`B/quotation/service/CardSnapshotService.java:3010-3019` 注释，`:3015`「四处生产调用点全部传 false」，2026-09-12 用户裁决），夹具不要造 `__nodeId`。

**`costingExcelColumns`（`getById` 顶层，`QuotationDTO.java:60`）**：由 `ExcelColumnResolver#getEffectiveColumns` 产出（`B/quotation/service/ExcelColumnResolver.java:47-93`），是模板/EXCEL 组件 `excel_columns` JSON 的**原样透传**（`List<Map>`，plain ObjectMapper 解析，键名 **snake_case**，`column_overrides` 只覆盖已有键）。没有 `display_format` 合成逻辑——有就透传，没有就没有。前端类型 `CostingTemplateColumn`（`F/services/costingTemplateService.ts:7-25`）：`col_key`、`title`、`source_type`（`VARIABLE`/`FORMULA`/`CARD_FORMULA`/`TAB_JOIN_FORMULA`/`PRODUCT_ATTRIBUTE`/`COMPONENT_FIELD`/`EXCEL_FORMULA`/`FIXED_VALUE`）、`variable_path?`、`formula?`、`field_key?`、`fixed_value?`、`comparison_tag?`、`hidden?`、`display_format?: {type?, decimals?}`。只读视图渲染只用 `col_key` / `title` / `hidden` / `display_format`（`ReadonlyExcelView.tsx:43-48`，格式化走 `renderCellValue`）。

**`costingExcelValues`（行级，JSON 字符串）**：形状 `{"rows":[{"<col_key>": <值>, …}]}`（`CardSnapshotService.java:2951-2981` `buildExcelValues`，`rowData` 键为 `col_key`，见 `B/quotation/service/ExcelViewService.java:698`、`:740`）。经 `DecimalJacksonCustomizer` mapper 序列化 ⇒ 十进制为字符串；`buildLineRowData` 抛错时退化为 `{"rows":[]}`（该行显示为空单元格，不是空态）。

最小样本（放进 §2.4 夹具 C 的 `data` 与目标行）：

```json
"costingExcelColumns": [
  { "col_key": "T261030_COST_part",   "title": "料号",     "source_type": "PRODUCT_ATTRIBUTE", "field_key": "料号" },
  { "col_key": "T261030_COST_amount", "title": "核价金额", "source_type": "CARD_FORMULA",      "field_key": "核价金额",
    "comparison_tag": null, "hidden": false, "display_format": { "type": "NUMBER" } }
]
```
```json
"costingExcelValues": "{\"rows\":[{\"T261030_COST_part\":\"T261030_COST_P1\",\"T261030_COST_amount\":\"1717.135540717\"}]}"
```

- 渲染结果预期：1 行，首列「产品/节点」= 行的 `productPartNo`（`useExcelSnapshotRows.ts:43`），「核价金额」列为 `1717.135540717` 经 `renderCellValue` 按角色位数格式化后的文本。**格式化后的确切文本未验证**（前端格式化逻辑，未运行）；AC-7③a 是 master / 分支同夹具 A/B，比的是两边文本一致，不依赖预测值。
- `display_format.type` 取值 `PERCENT` / `NUMBER` / `TEXT`；不给 `display_format` 也合法（线上多数列无此键）。🔶 **未确认**：`source_type` 在只读视图里不参与渲染（只看源码判断），给哪个值都不影响显示；建议沿用上面写法，与真实配置形态一致即可。
- 报价侧 Excel（「报价单 · Excel 视图」）同构：`quoteExcelColumns` + 行 `quoteExcelValues`。
- 🚫 **未覆盖**：编辑页的 Excel 视图走 `LinkedExcelView`（`QuotationStep2.tsx:4843`、`:4931`），取列/取行路径不同（有 `useBackendExcelRows` / `useLinkedExcelRows` 分支，`F/pages/quotation/LinkedExcelView.tsx:5-8`、`:110-140`），本节**未审**；主线指令只要求详情页。编辑页切 Excel 时若当前侧有行缺 `*ExcelValues` 会触发 `POST /quotations/{id}/ensure-excel-values`（§2.2 E-14），夹具 C 已给两侧值可避开。

### 6+.3 比对视图 D-8 三个接口（AC-7③b）

前端：`ComparisonBoard` 挂载即 `Promise.all` 三个 GET（`F/pages/quotation/ComparisonBoard.tsx:67-84`），任一失败 → 整块显示错误；`config.columns` 为 `null` 时前端补一列默认 **`PRODUCT_TOTAL`**（产品合计，`F/pages/quotation/comparisonMapping.ts:26-43`），该列取 `row.quote.productTotal` 与 `row.costing.productTotal`（`comparisonMapping.ts:95-100`）。⇒ **最小非空金额只需 data 里两侧 `productTotal` 非空 + config `columns: null`**，meta 可给空页签。

| 接口 | 前端调用 | 后端 | 参数 |
|---|---|---|---|
| `GET /api/cpq/quotations/{id}/comparison-view/meta` | `F/services/comparisonViewService.ts:102-103` | `B/costing/resource/ComparisonViewResource.java:33-38` → `ComparisonViewService#getMeta` | 无 |
| `GET /api/cpq/quotations/{id}/comparison-view/data?frozen=true` | `comparisonViewService.ts:105-108`（详情页与核价管理页都传 `frozen`，`ProductDetailViews.tsx:270-276`） | `ComparisonViewResource.java:41-48` → `getData(quotationId, frozen)`（`ComparisonViewService.java:273`） | `frozen`（无核价单时后端降级为 live，`:340-341`） |
| `GET /api/cpq/quotations/{id}/comparison-view/config?bucket=SALES` | `comparisonViewService.ts:110-112` | `ComparisonViewResource.java:51-56` → `getConfig`（`ComparisonViewService.java:161-176`） | `bucket`：详情页 `SALES`，核价管理页 `FINANCE`（`ProductDetailViews.tsx:69`） |

**meta → `ComparisonMetaDTO`**（`B/costing/dto/ComparisonMetaDTO.java`）：`quoteTabs` / `costingTabs` 为 `TabMeta[]`：`{componentId, tabName, sortOrder(number), metrics:[{key, label, type}]}`。每个页签的 `metrics` = 结构里 `isSubtotal:true` 的字段（`key`=字段名，`type:"SUBTOTAL_FIELD"`）+ 末尾固定一项 `{"key":"__TAB_TOTAL__","label":"页签合计","type":"TAB_TOTAL"}`（`ComparisonViewService.java:59`、`:125-150`）。`costingSource` 字段级 `NON_NULL`，本接口不赋值 ⇒ **不出现**（`ComparisonMetaDTO.java:31-32`；`ComparisonViewService` 内无赋值，`grep` 实查）。页签来源是 4 份结构里的 `quoteCardStructure` / `costingCardStructure`（`ComparisonViewService.java:74-75`）。

**data → `ComparisonDataDTO`**（`B/costing/dto/ComparisonDataDTO.java`）：`rows[]` = `{partNo, productName, presence, quote, costing}`，按**料号**聚合（`ComparisonViewService.java:299-313`）：
- `presence`：`"BOTH"` / `"QUOTE_ONLY"` / `"COSTING_ONLY"`；没有某侧时该侧为 `null`。
- `quote` / `costing`（`SideDTO`）：`{productTotal: string|null, tabs: {"<componentId>": {tabTotal: string|null, subtotals: {"<字段名>": string}|null}}}`。十进制为字符串（G-1）。
- 取值来源（后端真实语义，**造夹具时为了自洽可参考，mock 并不强制**）：`tabTotal` = 卡片值 tab 的 `subtotal`；`subtotals` = tab 的 `subtotalByColumn`；**`productTotal` 只取 `componentType == "SUBTOTAL"` 的那个 tab 的 `subtotal`**，没有 SUBTOTAL 页签时为 `null`（`ComparisonViewService.java:405-430`）。⚠️ §2.4 夹具 C 的卡片值里没有 SUBTOTAL 页签——若测试员想让 mock 与卡片值「同源可推」，线上同形态数据算出的 `productTotal` 会是 `null`（产品合计列显示为空）；mock 直接给字符串即可显示金额，两者不矛盾，但请知悉这一差别。

**config → `ComparisonConfigDTO`**（`B/costing/dto/ComparisonConfigDTO.java`）：`{quotationId, bucket, columns, updatedAt}`；从未保存过配置时 `columns: null`、`updatedAt: null`（`ComparisonViewService.java:165-171`）。

最小样本（各为信封里的 `data`）：

```json
// meta
{ "quoteTabs":   [{ "componentId": "26103000-0000-4000-8000-0000000000d0", "tabName": "T261030_COST_报价明细", "sortOrder": 0,
                    "metrics": [{ "key": "__TAB_TOTAL__", "label": "页签合计", "type": "TAB_TOTAL" }] }],
  "costingTabs": [{ "componentId": "26103000-0000-4000-8000-0000000000d1", "tabName": "T261030_COST_核价明细", "sortOrder": 0,
                    "metrics": [{ "key": "核价金额", "label": "核价金额", "type": "SUBTOTAL_FIELD" },
                                { "key": "__TAB_TOTAL__", "label": "页签合计", "type": "TAB_TOTAL" }] }] }
```
```json
// data
{ "rows": [{
    "partNo": "T261030_COST_P1", "productName": "T261030_COST_产品1", "presence": "BOTH",
    "quote":   { "productTotal": "888.123456789",
                 "tabs": { "26103000-0000-4000-8000-0000000000d0": { "tabTotal": "888.123456789", "subtotals": null } } },
    "costing": { "productTotal": "1717.135540717",
                 "tabs": { "26103000-0000-4000-8000-0000000000d1": { "tabTotal": "1717.135540717",
                                                                       "subtotals": { "核价金额": "1717.135540717" } } } }
}] }
```
```json
// config（SALES；核价管理页把 bucket 换成 "FINANCE"）
{ "quotationId": "26103000-0000-4000-8000-0000000000a1", "bucket": "SALES", "columns": null, "updatedAt": null }
```

- 预期：默认「产品合计」一列，报价侧 `888.123456789`、核价侧 `1717.135540717`（按角色位数格式化）及差额。**比对板的格式化文本与差额文本未验证**（前端逻辑，未运行）；AC-7③b 同样走 master / 分支同夹具 A/B。
- 🔶 **未确认**：页签对比列（`kind:"TAB_PAIR"`）的 `columns` 元素形态只看了前端类型（`comparisonViewService.ts` `ColumnDef`：`id, kind, sortOrder, threshold, quoteComponentId?, quoteMetric?, quoteLabel?, costingComponentId?, costingMetric?, costingLabel?`），后端 `validateColumns` 的校验规则未细读；最小样本用 `columns:null` 规避，不需要它。
- 只读：详情页 `readonly` 恒真（`ProductDetailViews.tsx:70`），不会发 `PUT …/comparison-view/config`；核价管理页 `editable` 时用户主动删列/改阈值/连线才会发 PUT，本 AC 不涉及。
