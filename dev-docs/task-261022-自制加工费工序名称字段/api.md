# 接口契约

本次不新增或修改 HTTP endpoint、请求/响应 JSON 结构。复用：

- `GET /api/cpq/semantic-graph/field-tree`：后端返回新增 `SELF_PROCESS_FEE` 字段 `工序名称`。
- `POST /api/cpq/components/{id}/builder/compile`（实际资源路径以现有 `sqlViewBuilderService` 为准）：编译 SQL 自动包含 process_master 查名。
- `POST /api/cpq/components/{id}/builder/preview`：预览返回工序名称值。

本任务只扩展语义图数据声明，不改变端点契约。
