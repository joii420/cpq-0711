# 接口契约文档 —— repair-0726 BOM 中料件类投入料号没有落库

> 上游：`需求说明.md`｜后端：`backtask.md`｜前端：`fronttask.md`

---

## 0. 结论先行

**本次不新增、不删除、不修改任何接口的请求/响应结构。**

改动全部发生在**落库语义与数据可见性**层面。对前端而言只有一处可观测变化：料件主数据列表不再返回「未核准报价单新建的料号」。前端 TypeScript 类型、请求参数、响应字段**零变化**。

---

## 1. 受影响接口清单

| # | 接口 | 变化类型 | 前端是否需改 |
|---|---|---|---|
| 1 | `GET /api/cpq/material-masters` | **返回集合口径收窄** | 否 |
| 2 | `GET /api/cpq/material-masters/{id}` | 无变化（见 §2.2） | 否 |
| 3 | `POST/PUT/DELETE /api/cpq/material-masters[/{id}]` | 无变化（见 §2.3） | 否 |
| 4 | 报价导入 / 建单 / 核价通过 / 删单 四条链路 | 无接口变化，仅副作用变化（见 §3） | 否 |
| 5 | 报价单渲染相关（页签 / BOM 树 / batch-expand） | 无接口变化，**数据由空变有**（见 §4） | 否 |

---

## 2. 料件主数据接口

### 2.1 `GET /api/cpq/material-masters` —— 列表（口径收窄）

**请求**：`page` / `size` / `keyword` —— **不变**

**响应结构**：`ApiResponse<PageResult<MaterialMasterDTO>>` —— **不变**，`MaterialMasterDTO` **不新增 `pendingQuotationId` 字段**（后端刻意不暴露，保持前端契约稳定）

**行为变化**：

| 变化前 | 变化后 |
|---|---|
| 返回 `material_master` 全部行 | 仅返回 `pending_quotation_id IS NULL` 的行 |

含义：报价单导入产生的新料号，在该报价单**核价通过之前**不出现在此列表；核价通过后自动出现。`total` 分页总数同步按该谓词计算。

> 📌 变化前后对存量数据的实际影响：由于旧机制下这些料号根本没进正表（在暂存表里），此列表**本来就看不到**它们。所以对使用者而言，此接口的可见结果**与改造前完全一致** —— 收窄谓词是为了在"改成直落正表"之后维持既有观感，而不是新增限制。

### 2.2 `GET /api/cpq/material-masters/{id}` —— 详情（不加过滤）

**不加** `pending_quotation_id IS NULL` 谓词。理由：详情页由列表点入，列表已过滤；额外过滤只会在极端并发（查看详情时该单恰好被删）下把 200 变 404，无收益。

### 2.3 手工维护接口（新建 / 编辑 / 删除）

- `POST /api/cpq/material-masters`：手工新建的料号 `pending_quotation_id` 恒为 `null`（即正式料号），请求体不含该字段。
- `PUT /api/cpq/material-masters/{id}`：编辑不改变 pending 归属（后端 `findById` 后改字段，天然保留）。
- `DELETE /api/cpq/material-masters/{id}`：语义不变。

---

## 3. 报价链路的副作用变化（无接口签名变化）

| 链路 | 触发接口 | 副作用变化 |
|---|---|---|
| 基础数据导入 | `POST /api/cpq/v6/quote-import`（现有导入端点） | 零件/外购件料号**即刻**写入 `material_master`（带 `pending_quotation_id = importRecordId`），不再只进暂存表 |
| 创建报价单 | 现有 create-quotation 端点 | 料号的 pending 归属与 8 张 V6 表一起过户为 `quotationId` |
| 核价通过 | `POST /api/cpq/quotations/{id}/costing-approve` | 原 `promoteStaging`（暂存→正表 upsert）改为 `flipPending`（清标记转正）。**响应结构不变**；`previewToken` 校验逻辑不变 |
| 核价预览 | `GET /api/cpq/quotations/{id}/costing-approve/preview` | 响应结构不变。token 计算的数据源由暂存表换成正表 pending 行，**同一业务状态仍产出同一 token**，前端的"预览→提交"两步流程不受影响 |
| 报价单删除 | `DELETE /api/cpq/quotations/{id}` | 除清 8 表 pending 行外，回收本单 pending 料号行（带引用守卫：被其它单引用的不删） |
| 重复导入覆盖 | 同导入端点 | 清理本单旧 pending 料号行（关闭 BACKLOG BL-0072 的孤儿行问题） |

> ⚠️ 对前端的唯一提示：**核价通过接口的 409（previewToken 失效）语义不变**。若集成测试中发现预览后提交必现 409，属后端 `listPending` 未加 `ORDER BY material_no` 的实现缺陷（backtask §7 已列为强制项），应回报后端而非前端重试。

---

## 4. 报价单渲染（数据由空变有）

报价单页签 / BOM 树 / `batch-expand` 等渲染链路的 SQL 视图 join `material_master` 时**不加任何 pending 谓词**（全局可见）。

前端可观测效果：导入后即刻，零件/外购件料号的**品名、单重**等字段从空值变为有值 —— 这正是本需求要修的现象，属**预期改善**，不是回归。

**跨单场景**：同客户第二张报价单复用第一张单铸的 pending 料号时，第二张单同样能取到品名（渲染侧不做单据隔离）。

---

## 5. 错误码

无新增错误码。既有语义保持：

| 场景 | 错误码 | 说明 |
|---|---|---|
| 核价通过时 previewToken 与当前状态不符 | 409 | 不变 |
| 导入 Phase1 校验失败 | 现有导入错误报文 | 不变（本次不改校验器） |

---

## 6. 前端需回归的接口（详见 `fronttask.md`）

1. `GET /api/cpq/material-masters` —— 列表正常渲染、分页 total 正确
2. 报价单页签 / BOM 树 —— 零件/外购件料号品名、单重可见
3. 核价通过「预览 → 提交」两步 —— 不出现异常 409
