# api.md —— 接口契约

## 结论：本任务接口零变更

🔴 **2026-08-25 扩范围（D-1 → D-1+D-3）后复核：结论不变，仍是零变更。**

改动全部位于 `ConfigureSnapshotService` 与 `CardSnapshotService` 的**私有方法**内部，
不涉及任何 Resource 层签名、DTO 字段、HTTP 状态码或错误码。

---

## 涉及但不变更的端点

### `POST /api/cpq/basic-data-import/v6/quote/create-quotation`

`BasicDataImportV6Resource.java:139`

| 项 | 内容 | 本次是否变更 |
|---|---|---|
| 权限 | `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN` | 否 |
| 请求体 | `CreateQuotationFromImportRequest{ importRecordId, customerId, name }`，三者非空否则 400 | 否 |
| 响应体 | `ApiResponse<V6QuotationCommitService.CommitResult>` | 否 |
| `CommitResult` 字段 | `quotationId` / `importRecordId` / `hfPairsCount` / `lineItemsCount` / `cardValuesReady` / `costingTreeRows` / `warnings` | 否 |
| 幂等性 | 同 `importRecordId` 重入返回既有 quotation（日志「V6 commit: 幂等重入」） | 否 |
| 降级语义 | 物化失败不回滚整单；置 `cardValuesReady=false` + `warnings`，前端 warm 兜底 | **否 —— 且 AC-5 专门守它不被吞掉** |

**唯一可观测差异（行为不变，取值变化）**：修复后 1845 行场景下
`cardValuesReady` 由 `false` 变为 `true`、`warnings` 由非空变为空数组 —— 这是**缺陷修复的结果**，
不是契约变更。字段语义与类型均未改动。

⚠️ **响应耗时会变化但无阈值承诺**：AC-1 的秒数判据按用户裁决**先测后定**，
本文件**不写死任何响应时间 SLA**，待实测后再定。

---

## 超时相关（本次明确不动，记录以免后续会话重提）

| 位置 | 值 | 本次 |
|---|---|---|
| `cpq-frontend/src/services/api.ts:5` | `timeout: 30000`（axios 全局） | **不动** |
| `cpq-frontend/vite.config.ts` `/api` proxy | 未设超时 | 不动 |
| Narayana 事务超时 | 未显式配置 → 默认 60s | **不动** |

理由见 `问题说明.md §④ 证据 5` 与 §⑤「已否决备选」。

---

# 🔴 契约变更（D-5，2026-08-26 第三次扩范围）

> ⚠️ **本文件此前写的「零变更」结论到此为止。** D-5 改变 `create-quotation` 的响应时机与语义。

## 变更起因

后端已修好，但**用户实测体感毫无改善**：前端 axios 在 **30.01s** cancel 请求，而整单需 **132s**
（实测 ①15,398 ②305 ③76,154 ④40,460 ms）。数据是好的、界面报失败。

## `POST /api/cpq/basic-data-import/v6/quote/create-quotation`

| 项 | 变更前 | **变更后** |
|---|---|---|
| 返回时机 | **建单 + 建行 + 四步物化全做完**才返回（132s） | **只做建单 + 建行**即返回（目标 <5s） |
| `cardValuesReady` | 物化的真实结果 | **恒 `false`**（物化尚未开始/进行中）—— 语义由「算完了吗」变为「本次响应里算完了吗」 |
| `costingTreeRows` | 物化后的真实行数 | **恒 0**（同上） |
| 新增字段 | —— | 建议加 `materializing: true`，让前端**显式**知道要去轮询，而不是靠 `cardValuesReady=false` 猜 |
| 物化 | 请求线程内同步 | **后台执行**（受单飞锁保护） |

🚫 **不改**：请求体、权限、幂等语义、`warnings` 字段、失败降级语义。

## 轮询：复用既有端点，不新增

`POST /api/cpq/quotations/{id}/ensure-card-values`（`QuotationResource:217`，**已存在**）

| 响应 | 含义 | 前端动作 |
|---|---|---|
| **409** | `WARMING_IN_PROGRESS` —— 单飞锁被占，物化在飞 | 继续轮询 |
| **200** | 补算完成（返回补算行数；0 = 本来就齐） | 停止轮询，进编辑页 |
| 其它错误 | 物化失败 | **显式提示，不许无限转圈**（AC-14①） |

✅ **为什么这样最省**：① 端点、前端封装（`quotationService.ensureCardValues`）都已存在；
② 单飞锁天然保证并发轮询安全；③ **后台任务若丢失（如服务重启），轮询会自动重新触发补算** ——
`ensureCardValues` 按 `IS NULL` 选行，只补没算的，白送一个自愈（AC-14②）。

⚠️ **注意它不是纯只读探针**：它会**触发**计算。这正是自愈的来源，但实现方需知悉这一点，
不要误以为在做无副作用的状态查询。
