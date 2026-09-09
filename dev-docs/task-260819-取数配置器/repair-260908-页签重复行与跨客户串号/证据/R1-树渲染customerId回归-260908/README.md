# R-1 回归修复 · 报价树渲染拿不到 customerId · 2026-09-08

## 根因（一行代码）

`ConfigureSnapshotService` 给 `BomTreeRenderService.render(..., "QUOTE")` 传的是**就地 new 的轻量壳**：

```java
QuotationLineItem lite = new QuotationLineItem();
lite.id = lid;
lite.productPartNoSnapshot = pn;     // ← quotationId 从来没赋值
liteLines.add(lite);
```

而 `BomTreeRenderService` **两处**客户解析都以 `li.quotationId` 为唯一入口：

| 位置 | 用途 | 壳里没 quotationId 的后果 |
|---|---|---|
| `renderInternal` §④ | `Quotation.customerId` → `expandUncached(compId, ctxCustomerId)` → `:customerCode` | 恒 null ⇒ 4 个组件 expand 全抛 400 |
| `collectTotalMaterialNoUnion` → `resolveCustomerCodeFromLines` | 递归闭包 SQL 的客户谓词（task-260907 B-7a） | 闭包按"无客户"算，多带回别家料号 |

**task-0729 那次只修了核价侧** —— 核价侧传的是**真实 `QuotationLineItem` 实体**，天然带 `quotationId`；
报价侧这条从来就是 null，**只是以前不会响亮失败**（`:customerCode` 降级成字面量 NULL，
客户料号那条 LEFT JOIN 恒不命中，列空着没人当回事）。B-2 把它变成 400 之后才照出来。
⇒ **不是闸太严，是调用方从来没给过参数。**

## 修法

1. `ConfigureSnapshotService`：`lite.quotationId = quotationId;`（**一行**，复用既有解析路径，两处同时修好）
2. `BomTreeRenderService`：解析不到客户时 `LOG.warn` 点名调用方 —— 🚫 刻意不抛，
   响亮失败那一档由 B-2 在执行层承担；这里只负责留线索。

## 回归验证（8098，真实报价单 QT-20260908-0624）

| 页签 | 修复前 baseRows | 修复后 | 目标 | |
|---|---|---|---|---|
| BOM | 8（全是 `__renderError` 占位） | **42** | 42 | ✅ |
| 产品 | 8 | **8** | 不变 | ✅ |
| 加工费 | 10 | **10** | 不变 | ✅ |
| 材质元素 | 26 | **26** | 不变 | ✅ |

`__renderError` 全部消失（`含错误占位 = 0`）。

## ⚠️ 副作用：`row_data` 被这次成功渲染改写了

```
row_data 合计   修复前 84 / 56 / 20 / 52  →  修复后 42 / 8 / 10 / 26
editRows        修复前 196（100% 带 #N）  →  修复后 78（仅 4 条仍带后缀）
```

**为什么之前"完好无损"**：上一次刷新时**树渲染是失败的**，失败路径不重写 `row_data`，所以 84 原样留着。
本次渲染成功 ⇒ `row_data` 跟着收敛到新行集（去掉跨客户孪生行）。这是修复的**预期终态**，
但它意味着**落在那些行上的用户输入随行消失**，且 `证据/AC18-刷新前备份-260908/` **只备份了
`quote_card_values`，没有备份 `row_data`**。

🔑 **对 AC-18 的直接含义**：B-8 收集器本次报 **0 失配**，而且它**是对的** ——
损失发生在**上游的 `row_data` 重写**，不在 `rowKey` 匹配那一步。
⇒ 现在这版 AC-18 量的是**另一条路**。

📌 顺带印证了上一轮的预测：修复前 `#2/#3` 各 2 条（撞键度 4，「恰好两个客户」解释不了），
修复后**恰好剩 4 条仍带后缀** —— 它们本来就不是跨客户造成的撞键。
