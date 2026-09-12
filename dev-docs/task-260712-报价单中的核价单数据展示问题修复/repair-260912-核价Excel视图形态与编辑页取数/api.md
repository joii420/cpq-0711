# api · repair-260912

## 结论：**零接口结构改动，但有一处「响应内容形态」变化，需在此登记**

### 不变

- 无新增/删除端点、无参数增删、无字段改名、零迁移、零 DDL
- `GET /quotations/{id}/excel-view` 的响应仍是 `{columns, rows}`；`costing_excel_values` 仍是 `{rows: [...]}`

### 变（消费方需知悉）

| 项 | 改动前 | 改动后 |
|---|---|---|
| 核价侧 `costing_excel_values` | `{rows: [N 行 BOM 节点], treeMode: true}` | `{rows: [1 行]}`，**不再有 `treeMode` 键** |
| 核价侧 `GET /excel-view` 的 `rows[].col_*` | 恒 `"0"` | 该产品各页签的总计 |

⚠️ **`treeMode` 键的消费方**：实现前请 grep 前后端确认没有别的地方依赖 `treeMode === true` 分支；若有，停下来报主线。

### 报价侧契约不变（硬要求）

`GET /excel-view` 报价/核价共用。核价分支的改动必须做到**报价侧请求与响应逐位不变** —— AC-4 用「键集合一致 + 叶子数值等价」比对器验证，且比对器本身做变异实验。
