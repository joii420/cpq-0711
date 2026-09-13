# api · repair-260911 核价 Excel 视图取数源对不上

## 结论：本次**零接口改动**

### 为什么不改

- 缺陷在 `CardEffectiveRows.parse` 的**进程内 map 键登记**，不在任何请求/响应边界上
- `GET /quotations/{id}` 与 `POST /quotations/{id}/ensure-excel-values` 的**响应结构不变**；变的只是 `costing_excel_values.rows[].col_*` 的**值**由 `"0"` 变成真实值 —— 数据内容变化，不是契约变化
- 无新增端点、无参数增删、无字段改名、零迁移、零 DDL

### 真正的「契约」在哪

不是 HTTP 接口，是**两条 effective-rows 路径之间的键口径约定**：

| 路径 | 约定 |
|---|---|
| `ComponentDataEffectiveRows`（报价侧） | 裸 `componentId`（Excel 列 tabKey 约定）+ `cid:sortOrder`（CardRef 约定），**双键** |
| `CardEffectiveRows.parse`（核价 Excel 树） | **本次修复前只有 `cid:sortOrder`** |

🚨 这个约定此前**只存在于注释里**（`ComponentDataEffectiveRows:235-236` 的行末注释），没有任何测试守着 —— 这正是它能在一条路径上漏掉三个月的原因。`B-2` 的契约测试就是把它固化下来。

### 回归确认清单

| 项 | 确认方式 |
|---|---|
| `GET /quotations/{id}` 响应键集合 | 改动前后 `jq 'paths'` 对比一致 |
| `quote_excel_values` 数值 | AC-3 数值等价比对（🚫 不用逐字节 diff） |
| `POST /ensure-excel-values` 幂等语义 | 仍只补 `IS NULL` 行，本次不改其谓词 |
