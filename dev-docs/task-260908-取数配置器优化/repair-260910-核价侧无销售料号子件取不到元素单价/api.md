# api · repair-260910

## 结论：HTTP 契约零改动，`main-api.md` **无需回写**

（按 `task-docs.md §2.5` 末段，本次在 `test-report.md` 里同样要写明「本次无契约变更，无需回写 main-api.md」。）

## 为什么不改

本次改的全部是**数据**（`semantic_node` / `semantic_edge_key` 两张表的行）与**编译产物**（`component_sql_view.sql_template`），
没有新增/删除/修改任何端点的方法、路径、入参、响应结构或错误码。

## 本次会**被调用**的既有端点（只用，不改）

| 端点 | 用途 | 归属 |
|---|---|---|
| `POST /api/cpq/components/{componentId}/builder/compile` | 重新编译组件视图 | B-2 |
| `PUT /api/cpq/components/{componentId}/builder`（`save`）| 写回 `component_sql_view.sql_template` | B-2 |
| 模板 `new-draft` / `publish` | 升版并重写 `sql_views_snapshot` | B-3 |
| 「刷新基础数据」refresh 路径（`skipRowsWithSnapshot=false`）| 重算 `costing_card_values` | B-4 |

## 🚫 明确不调用的端点

| 端点 / 方法 | 为什么不调 |
|---|---|
| `BuilderRecompileService.recompileAndRealign` 管理端后门 | 它内部调 `forceRealignSnapshots`，而后者**不刷 `sql_views_snapshot`**（`BL-0223`，`TemplateService:262` 只在 `publish` 写）⇒ **会报成功但渲染不变**。`A0-2` 裁决走模板升版 |
| `TemplateService.forceRealignSnapshots` | 同上 |

## 响应契约的一处**不变量**（回归时要看，不是要改）

编译产物里价格列的输出别名恒为 `元素单价` / `货币`，JOIN 别名恒为 `cep`（`repair-260909` `AC-1` 铁律）。
本次换函数后**这三个名字一个都不许变** —— 变了就是前端契约破坏，属 `AC-2` 的隐含断言。
