# 接口契约

> 角色口径：本任务中的“管理员”= `SYSTEM_ADMIN`；`SALES_MANAGER` 不获得 `DELETED` 历史查看或从已删除模板创建新模板的额外权限。

## 兼容原则

- 保留现有模板路径和响应结构；新增状态值 `DELETED`。
- 不新增报价单模板绑定字段，不修改报价单查询响应结构。
- 所有已有模板引用保持原值；删除接口不清理外键、绑定或快照。

## 模板接口

### `DELETE /api/cpq/templates/{id}`

- `DRAFT`：保持现有物理删除行为。
- `PUBLISHED`：不删除行，只把 `template.status` 更新为 `DELETED`，返回更新后的模板 DTO。
- `ARCHIVED` / `DELETED`：HTTP 400，返回明确的状态错误。
- 删除不检查进行中报价或产品绑定，不修改任何引用表。
- 角色：`SALES_MANAGER`、`SYSTEM_ADMIN`，沿用现有权限。

### `GET /api/cpq/templates`

- 不传 `status`：默认追加 `status <> 'DELETED'`。
- 传 `status=DELETED`：仅 `SYSTEM_ADMIN` 允许；其他角色返回 403。
- 传其他状态：保持现有筛选行为。

### `GET /api/cpq/templates/{id}`

- `DELETED` 模板仅 `SYSTEM_ADMIN` 可直接打开；历史报价内部服务不通过该 HTTP 列表入口读取模板。
- 返回 DTO 结构不变，`status` 为 `DELETED`。

### `POST /api/cpq/templates/{id}/new-draft`

- `DRAFT/PUBLISHED/ARCHIVED`：保持现有行为和角色限制。
- 源模板为 `DELETED`：仅 `SYSTEM_ADMIN` 可调用；创建新的 `DRAFT` 模板，不改变源模板状态、版本和引用。
- 不提供 `DELETED -> PUBLISHED/ARCHIVED` 恢复接口。

## 报价与绑定接口行为

- 新建报价、保存时切换模板、复制报价、产品模板绑定、客户模板匹配均不得接受 `DELETED` 作为新模板。
- 已有 DRAFT 报价若保持原 `DELETED` 模板 ID 不变，保存继续成功；不得因为模板状态变化而清空或改写该引用。
- 报价详情/列表继续按原模板 ID 查询并带出模板信息；渲染使用已有报价/模板快照。

## 错误约定

- 状态不允许的删除：HTTP 400。
- 非管理员查询/打开/复制 `DELETED` 模板：HTTP 403。
- 模板不存在：HTTP 404。
- 新引用 `DELETED` 模板：HTTP 400，错误信息包含模板当前状态。
