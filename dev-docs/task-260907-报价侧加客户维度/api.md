# API 契约 · task-260907 报价侧加客户维度

> 本任务**几乎不改 HTTP 契约** —— 改的是**数据写入的轴模型**与**表结构**。
> 唯一的契约面变化在 B-4 的透传，且已由 `task-260907-产品管理客户过滤` 承接前端侧。

---

## 1. 🚫 本任务**不改**的端点（写明判定依据，非留空）

| 端点 | 为什么不改 |
|---|---|
| `GET /dataset/{dataset}/parts` 及 4 个 `{axisValue}` 端点 | 🚚 **加 `?customerNo=` query 参数已移出本任务**，由 `task-260907-产品管理客户过滤` 承接（用户 2026-09-07 裁决单独立项）。本任务只负责**让服务层能接收并透传**该值到 writer |
| 导入端点 | 客户号由 `报价导入切ds新表` 会话的 `QuotationImportService` 在调用方填好，**契约不变** |
| 配置器全部端点 | 与本任务无关 |

## 2. 唯一的服务层契约变化：`VersionedGroupWriter` 的轴签名

```java
// 改动前
SheetDef.axisColumn : String            // 单列
writeGroups(spec, byAxis, ...)          // byAxis 的 key 是单个轴值

// 改动后（形态待实现期定，本文只钉约束）
轴变复合 (customer_no, <原轴列>)
```

🚨 **判据落在签名层，不落在点名清单上** —— 让**新增调用方编译期就必须提供** `customer_no`。
理由见 `需求文档.md` AC-4 的方法记账（找调用方连错三次，错法各不相同）。

## 3. 🚨 一条不属于 HTTP 契约、但比契约更要紧的约束

`customer_no` **不能声明成 `ColumnDef`**，必须走 `SheetDef` 的静态系统列。
四条源码依据见 `交接说明-customer_no.md` §③。

⇒ **它不出现在任何 Excel 表头校验里，也不出现在 `persistedColumns()` 里**，
但**必须显式出现在 `insertAll` 与 `archive` 的列清单里**，否则列建了、值恒 NULL。

## 4. 回归确认清单（合并前逐条确认未变）

- 导入端点的请求/响应形状
- 维护端 5 个端点的现有行为（**在 `产品管理客户过滤` 加 query 参数之前**）
- 核价两套（`COST_BASIC`/`COST_DETAIL`）的导入与维护端保存 —— **逐行 md5 相同**（AC-6）
- 选配链路（`ConfigureProductService` / `SelDsQuoteWriter`）写入行为（AC-7）

---

## 🚨 契约补漏（2026-09-07 —— 这两处原本没写，测试代理是靠 400 报文反推出来的）

> 📌 **「靠报错反推契约」本身就是契约缺口的证据。** 记在这里，让下一个人不用再反推一次。

### 导入端：`customerNo` 走 **multipart 表单字段**，🚫 不是 query 参数

```
POST /api/cpq/dataset/{dataset}/import
Content-Type: multipart/form-data
  file       : <xlsx>
  customerNo : CUST-0001        ← 表单字段，必填
```
按 query 参数发会得到：
```json
{"code":400,"message":"导入报价数据必须指定客户（customerNo）"}
```

### 维护端：`PUT .../sheets/{sheetKey}/rows` **强制**要 `customerNo`（query 参数）

```
PUT /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows?customerNo=CUST-0001
```
与 `A0-2` 裁决一致（客户走 query 参数），但**这一条是强制的**，与其余端点「不传则不过滤」的向后兼容口径不同。

### ⚠️ 两个已知的契约不一致（本文件记录，修复归属见 `需求文档.md` A0-7）

1. 🔴 **读端点 `GET .../sheets/{sheetKey}/rows` 收下 `customerNo` 却不按它过滤** ——
   传了客户号仍返回全部客户的行；照原样保存会把对方客户的行**复制成自己的**（实测 CUST-0004 由 1 行变 2 行）。
   ⇒ 🚚 已转 `task-260907-产品管理客户过滤`。
   **注意区分两种形态**：「不传 = 不过滤」是有意的向后兼容；「**传了也不过滤**」是缺陷。
2. ⚠️ **缺 `customerNo` 时的 400 响应没有 `data.errors`**，违反本文件 §1「400 必须带 `data.errors`」。
   实测红的用例：`DatasetCustomerPartAcTest.tc01` / `tc02`。
