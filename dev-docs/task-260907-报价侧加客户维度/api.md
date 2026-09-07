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
