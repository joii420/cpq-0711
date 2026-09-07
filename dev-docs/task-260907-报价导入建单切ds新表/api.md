# task-260907 · 接口契约

> **基线**：`dev-docs/main-api.md`（全项目 87 Resource / ~413 端点）。本文只写**本任务新增 / 变更 / 停用**的端点。
> **回写义务**：测试完成后、合并 master 之前，按 `task-docs.md §2.5` 把本文覆盖回 `main-api.md`。
> **口径来源**：D-15（业务流程与 V6 逐行对齐）+ D-19（导入段异步 + `import_record` 轮询）。

---

## 0. 设计原则：镜像 V6，不发明新形状

三个新端点是 `BasicDataImportV6Resource` 三个端点的**逐字段镜像**，只换服务实现。这样做的理由：

1. 前端可以直接复刻 `QuoteBasicDataImportV6Drawer` 的状态机（两步 + 轮询 + 重试），不必重新设计交互
2. `import_record` 表已被两条导入链路共用（`system_type` 无 CHECK 约束，`task-260902` 实测确认可直接追加新值）
3. 出错形态、进度形态、幂等形态都有已验证的先例，不引入新的失败模式

🚫 **`POST /api/cpq/dataset/{dataset}/import` 一个字节不改。** 它被【基础资料维护】的 `DatasetPartListTab.tsx:185` 与本任务共用同一个 `DatasetImportDrawer` 组件；给它加必选 `customerId` 会直接打断维护页签（N-10）。

---

## 1. 🆕 `POST /api/cpq/dataset/quote/quotation-import`

**报价数据导入（建单流程专用，异步）。**

| 项 | 值 |
|---|---|
| Content-Type | `multipart/form-data` |
| 权限 | `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`（**对齐 V6**，🚫 不是 `dataset/import` 那套 `PRICING_MANAGER`） |
| 事务 | 同步段只建 `import_record` + 读文件入内存；解析与写库在后台线程 |

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `customerId` | UUID | ✅ | **必选**（D-15）。空 → 400「customerId 不能为空」；查不到 → 404「客户不存在」；`customer.code` 为空 → 400 |
| `file` | 文件 | ✅ | 16 sheet 报价 Excel |

**响应 200**

```json
{ "code": 200, "data": {
    "importRecordId": "…uuid…",
    "systemType": "DATASET_QUOTE",
    "status": "PROCESSING"
} }
```

> 立即返回，**不等解析**。前端轮询 §2。
> `import_record.system_type` 沿用 `DatasetImportService.systemTypeOf()` 产出的 `DATASET_QUOTE`（varchar(20)，19 字符放得下，无 CHECK 约束）。
> `import_record.customer_id` 落本次所选客户 —— 建单时据此校验一致性（§3）。

**错误**

| 码 | 场景 |
|---|---|
| 400 | `customerId` / `file` 缺失；客户无 `code` |
| 401 | 未登录 |
| 403 | 角色不在白名单 |
| 404 | 客户不存在 |
| 500 | 读取上传文件失败 |

⚠️ **Excel 内容错误不在这里返回** —— 那时请求已经返回了。校验结果经 §2 轮询取回。

---

## 2. 🆕 `GET /api/cpq/dataset/quote/quotation-import/{recordId}`

**轮询导入进度与结果。** 形状对齐 `GET /basic-data-import/v6/{recordId}`，追加 `errors`。

| 项 | 值 |
|---|---|
| 权限 | 同 §1 |

**响应 200**

```json
{ "code": 200, "data": {
    "importRecordId": "…uuid…",
    "systemType": "DATASET_QUOTE",
    "status": "PROCESSING | SUCCESS | FAILED",
    "originalFileName": "报价数据-正泰.xlsx",
    "totalRows": 1845, "successRows": 1845, "failedRows": 0,
    "createdAt": "2026-09-07T10:00:00+08:00",
    "progress": { "done": 6, "total": 18, "current": "物料与元素BOM" },
    "summary": [
      { "sheetName": "物料",   "kind": "PLAIN",     "inserted": 12, "updated": 3 },
      { "sheetName": "物料BOM","kind": "VERSIONED", "axisCount": 47, "created": 5, "upgraded": 2, "unchanged": 40 }
    ],
    "errors": [
      { "sheetName": "客户料号", "rowNum": 8, "columnLabel": "客户编号",
        "value": "8000142", "reason": "客户编号不存在于 customer.code" }
    ]
} }
```

**字段约定**

| 字段 | 何时有值 | 说明 |
|---|---|---|
| `progress` | `status=PROCESSING` | 沿用 V6 的节流写法（固定检查点 + 静默超时兜底），🚫 不要每 sheet 写一次 |
| `summary` | `status=SUCCESS` | 逐 sheet 结果。`kind=PLAIN` 走 `PlainTableWriter`（免版本 3 张）；`kind=VERSIONED` 走 `VersionedGroupWriter`，三态计数对应 `CREATED/UPGRADED/UNCHANGED` |
| `errors` | `status=FAILED` | **全部**错误，不是第一条（沿用 `task-260902` AC-10 语义）。整份拒收，一行未写 |

**错误**：404（记录不存在）· 401 · 403

---

## 3. 🆕 `POST /api/cpq/dataset/quote/create-quotation`

**由已成功的导入批次建报价单。** 形状对齐 `POST /basic-data-import/v6/quote/create-quotation`。

| 项 | 值 |
|---|---|
| Content-Type | `application/json` |
| 权限 | 同 §1 |
| 事务 | **同步段** = 建单 + 建明细行（同一 `@Transactional`，建单建行强一致）；**异步段** = 卡片值物化 |

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `importRecordId` | UUID | ✅ | 必须 `status=SUCCESS` 且 `system_type=DATASET_QUOTE`，否则 400 |
| `customerId` | UUID | ✅ | **必须与 `import_record.customer_id` 一致**，否则 400（防止前端串号） |
| `name` | string | ✅ | 报价单名称 |
| `categoryId` | UUID | ❌ | 产品分类。现网 `product_category` 只有一条 `000000 默认分类` |
| `customerTemplateId` | UUID | ✅ | 报价模板。由 `GET /templates/auto-defaults` 带出 |
| `costingTemplateId` | UUID | ❌ | 核价模板 |

**响应 200**

```json
{ "code": 200, "data": {
    "quotationId": "…uuid…",
    "importRecordId": "…uuid…",
    "lineItemsCount": 12,
    "materializing": true
} }
```

| 字段 | 说明 |
|---|---|
| `lineItemsCount` | 建出的明细行数 = 该客户在 `ds_quote_customer_part` 中的行数（见下） |
| `materializing` | **恒 `true`**。物化转后台，前端据此去轮询既有 `POST /quotations/{id}/ensure-card-values`。🚫 不要靠 `cardValuesReady==false` 猜（V6 侧 D-5 的教训：区分不了「真失败」和「还没开始算」） |

**明细行候选来源（本任务的核心改动点）**

```sql
SELECT cp.customer_product_no, cp.customer_part_name, cp.material_no, m.material_name
  FROM ds_quote_customer_part cp
  LEFT JOIN ds_quote_material m ON m.material_no = cp.material_no
 WHERE cp.customer_no = :customerCode
 ORDER BY cp.customer_product_no
```

🚫 **不再走 V6 那套** `material_customer_map` + `created_at ±时间窗` + `hfPairs` 写 `metadata`。
理由：`ds_quote_customer_part` 自带 `customer_no`，一条 JOIN 就能精确框定，不需要靠时间窗近似。
⚠️ **必须 `LEFT JOIN`** —— `INNER` 会在物料表缺行时静默丢明细行（`task-260903` 立项期实测过同型问题：`customer` 表 17 行有 3 行 JOIN 不到）。

**幂等**：同 `importRecordId` 已建过单且单仍在 → 返回既有 `quotationId`，不重复建单建行（对齐 V6 `V6QuotationCommitService` 的幂等重入）。

**错误**

| 码 | 场景 |
|---|---|
| 400 | 必填缺失；`importRecordId` 状态非 `SUCCESS`；`system_type` 非 `DATASET_QUOTE`；`customerId` 与导入记录不一致 |
| 401 / 403 | 同 §1 |
| 404 | `importRecordId` / 客户 / 模板不存在 |
| 500 | 建单失败（事务已整体回滚） |

---

## 4. 🚫 停用（S-6 / D-13）

| 端点 | 处理 |
|---|---|
| `POST /api/cpq/basic-data-import/v6/quote` | 返回 **410 Gone**，body 提示「报价基础数据导入已迁移至『导入报价数据』」 |
| `POST /api/cpq/basic-data-import/v6/quote/create-quotation` | 同上 |

- **本任务只停报价侧两个。** `GET /basic-data-import/v6/{recordId}` **必须保留** —— 它是报价与核价共用的轮询端点，且【导入历史】页要读历史记录

- 后端 `QuoteImportService` / 17 个 `Q*Handler` / `QuoteImportValidator` **代码保留**（N-7），只是没有 HTTP 入口

> 🚨 **2026-09-07 更正（原文已过期）**：本节原写「`POST /pricing` / `/pricing/template` 不动 —— 核价侧仍在用」。
> 实际上并发任务 **`task-260907-移除料号核价功能`** 正要**删掉这两个端点方法**（其范围表第 7 项）。
> ⇒ 两个任务在 **`BasicDataImportV6Resource` 同一个类**上并发编辑，合起来该类只剩 `GET /{recordId}`。
>
> **同时，对方「不做」清单第 6 条写着「不动报价侧 `POST /quote` —— 报价侧 V6 导入仍在用」，这条前提被本任务推翻**（本任务正是要摘掉 `QuoteBasicDataImportV6Drawer` 并停用该端点）。
>
> 🚦 **须由主线在开工前与对方协调合并顺序**，并明确 `GET /{recordId}` 由谁负责保留 —— 两边都以为对方会留着它，是最容易两边都删掉的形态。

---

## 5. ✅ 零改动（须回归验证）

| 端点 | 为什么必须零改动 |
|---|---|
| `POST /api/cpq/dataset/{dataset}/import` | 【基础资料维护】共用（AC-15）。N-10 明确不加客户参数 |
| `GET /api/cpq/dataset/{dataset}/sheets` / `parts` / `parts/{axis}/overview` / `.../rows` / `.../versions` / `PUT .../rows` / `lookup/{masterType}` | 维护页签的 7 个读写端点，本任务不碰 |
| `GET /api/cpq/templates/auto-defaults` | 建单第 2 步复用，契约不变 |
| `POST /api/cpq/quotations/{id}/ensure-card-values` | 物化轮询复用，契约不变 |
| `POST /api/cpq/quotations/{id}/costing-approve` | 契约不变。**行为变化在实现侧**：新单走到这里时 `QuoteBackfillService` 静默降级为 no-op（AC-16），摘要恒 `0/0/0/0` |

---

## 6. 契约变更纪律

本文冻结后即为前后端唯一协调物。**开工后要改，走 `task-docs.md §4` 四步**：
回用户确认 → 同步四处（需求文档 AC / 本文 / `fronttask.md`+`backtask.md` 的 AC 映射 / `test.md` 追溯矩阵）→ **主动通知在跑的子代理**（它们读过的是派出时那一版，不会自己重读）→ 重跑闸门 A 自检三项。
