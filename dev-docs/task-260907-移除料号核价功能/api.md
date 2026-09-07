# api · 移除主数据维护「料号核价」

> 本任务的契约变更**只有删除，没有新增、没有修改**。
> 下表是前后端唯一的协调物：前端按「已删除」一栏停止调用，后端按同一栏下线端点。

---

## 1. 本次删除的端点（9 个）

### 1.1 核价基础数据维护（7 个，整个 Resource 删除）

| 方法 | 路径 | 原鉴权 | 删除后 |
|---|---|---|---|
| GET | `/api/cpq/pricing-basic-data/parts` | SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN | **404** |
| GET | `/api/cpq/pricing-basic-data/sheets` | 同上 | **404** |
| GET | `/api/cpq/pricing-basic-data/parts/{materialNo}/overview` | 同上 | **404** |
| GET | `/api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows` | 同上 | **404** |
| GET | `/api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/versions` | 同上 | **404** |
| PUT | `/api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows` | PRICING_MANAGER / SYSTEM_ADMIN | **404** |
| GET | `/api/cpq/pricing-basic-data/lookup/{masterType}` | SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN | **404** |

承载类：`com.cpq.basicdata.v6.maintenance.PricingBasicDataMaintenanceResource`（整类删除，见 `backtask.md` B-1）。

### 1.2 V6 核价数据导入（2 个，从共用 Resource 里摘方法）

| 方法 | 路径 | 原鉴权 | 删除后 |
|---|---|---|---|
| POST | `/api/cpq/basic-data-import/v6/pricing` | SALES_MANAGER / SYSTEM_ADMIN | **404** |
| GET | `/api/cpq/basic-data-import/v6/pricing/template` | 四角色 | **404** |

承载类：`com.cpq.basicdata.v6.resource.BasicDataImportV6Resource`（**类保留**，只删这两个方法，见 `backtask.md` B-2）。

---

## 2. 🚫 必须保留的端点（反向对照用）

**这一节和上一节同等重要。** 只验证「删掉的返 404」会被「服务根本没起来」骗过去（`task-260819 · D-123` 进程级假绿）—— 必须在**同一次会话、同一轮请求**里同时证明「该在的还在」。

| 方法 | 路径 | 为什么容易被误删 | 验证方式（AC-4 / AC-5） |
|---|---|---|---|
| GET | `/api/cpq/basic-data-import/v6/{recordId}` | **与被删的 `/pricing` 在同一个 Resource 类里**，且报价/核价共用 | 带**实查**的现存 `recordId` → **200** 且 `data` 非 null（AC-5 的反向对照） |
| GET | `/api/cpq/dataset/{dataset}/parts` | **路径形状与被删的 `/pricing-basic-data/parts` 同构**，前端还共用 `createSheetApi` 工厂 | `?dataset=cost-basic&page=0&size=1` → **200** 且 `data` 非 null（AC-4 的反向对照） |
| GET/PUT | `/api/cpq/dataset/{dataset}/**` 其余全部 | 同上 | AC-8 的三页签编辑序列走通即证明 |

### ⚠️ `POST /api/cpq/basic-data-import/v6/quote` —— 本任务不碰，但**不再拿它当反向对照**

它同样与被删的两个端点在一个类里，本任务**一个字节都不改它**。
但它**不适合做本任务的判据**：并发任务 `task-260907-报价导入建单切ds新表`（其 B-10）要把 `/quote` 与 `/quote/create-quotation` 改为返 **410**。若本任务的 AC-5 沿用「不传 `customerId` → 400」这条对照，对方合并后它会变成必然失败的假红 —— **而失败原因与本任务毫无关系**。

📌 **由此得到的一条通则**：反向对照要挑**并发任务线不会碰的端点**。挑之前先过一遍 `dev-docs/INDEX.md §0.5 按代码文件反查`，看该文件是不是撞车热点（`BasicDataImportV6Resource.java` 实测命中 **6 个任务**）。

---

## 3. 前端调用方下线清单

| 前端符号 | 位置 | 处置 | 对应端点 |
|---|---|---|---|
| `listParts` / `getSheets` / `getOverview` / `getRows` / `getVersions` / `saveRows` / `lookup` | `part-costing/api.ts` | **删** | §1.1 七个 |
| `legacy` 实例 / `BASE` 常量 / `PartSortBy` 类型 | 同上 | **删** | 同上 |
| `basicDataImportV6Service.importPricing` | `services/basicDataImportV6Service.ts` | **删** | `POST /v6/pricing` |
| `basicDataImportV6Service.downloadPricingTemplate` | 同上 | **删** | `GET /v6/pricing/template` |
| `createSheetApi` 工厂 | `part-costing/api.ts` → `shared/sheetApiFactory.ts` | **保留 + 迁移** | 服务于 §2 的 dataset 端点 |
| `basicDataImportV6Service.importQuote` / `pollImportResult` / `parseProgress` | `services/basicDataImportV6Service.ts` | **保留** | `POST /v6/quote`、`GET /v6/{recordId}` |

---

## 4. 为什么没有新增或修改任何契约

本任务是**纯移除**：

- 剩余 6 个页签**继续调用它们原本的端点**，请求 URL、query、body、响应体**逐字不变**；
- `createSheetApi` 工厂只换了文件位置与文件名，**函数签名与它生成的 URL 形状一个字节不改**（AC-16 的硬约束）；
- 因此 **dataset 三个数据集页签的契约为零变更**，AC-8 的回归判据才能是「行为与改动前完全相同」。

> 🚫 若开发过程中发现「必须改某个保留端点才能删掉料号核价」，那是**范围外的耦合**，停下来报主线 —— 不要顺手改，改了 AC-8 就失去了「行为不变」这个可比对基准。

---

## 5. `main-api.md` 回写（`task-docs.md §2.5` 强制项）

实查结果：

| 端点 | 总账中的状态 | 本次动作 |
|---|---|---|
| `POST /api/cpq/basic-data-import/v6/pricing` | **已登记**（「#### 核价基础数据导入（同步）」节，约 `:4947~4960`） | **删除该节** |
| `GET /api/cpq/basic-data-import/v6/pricing/template` | **从未登记** | 无 |
| `/api/cpq/pricing-basic-data/*` 七个 | **从未登记**（既有缺口） | 无 |

⇒ 回写 = **删一节，无新增、无覆盖**。执行人 B-7，主线复核。
🚫 **不要"先补齐再删除"** —— 补了立刻删是无谓动作，且会在总账里留下一次没有意义的来源标记。
