# API 契约 · 材质与元素导入导出边界优化

## 1. 元素批量接口

### `GET /api/cpq/elements/import/template`

- 响应：xlsx；列为 `元素符号 | 中文名称`，含一行示例。

### `POST /api/cpq/elements/import`

- 请求：`multipart/form-data`，字段 `file`。
- 成功响应：`{ totalRows, createdCount, updatedNameCount, unchangedCount }`。
- 规则：新符号自动发号并启用；已存在符号只改中文名称；文件内同符号异名时返回 400 且整份不落库。

### `GET /api/cpq/elements/export`

- 响应：xlsx；列为 `元素编号 | 元素符号 | 中文名称 | 状态`。

## 2. 材质批量接口（既有路径，契约调整）

### `GET /api/cpq/material-recipes/import/template`

- 响应：xlsx；列严格为 `材质 | 组号 | 元素符号 | 含量`，含一行示例。

### `GET /api/cpq/material-recipes/export`

- 响应：xlsx；列严格为 `材质 | 组号 | 元素符号 | 含量`。
- `组号` 为导出文件内的分组标签，不承诺等于数据库 `group_no`；再次导入仍按内容判断是否新增。

### `POST /api/cpq/material-recipes/import`

- 请求：`multipart/form-data`，字段 `file`。
- 成功响应保留：`totalRows, recipesCreated, configsCreated, configsSkippedAsDuplicate, skippedRowCount, createdConfigs, durationMs`；移除 `createdElements` 的业务含义，兼容字段若暂时保留必须恒为空。
- 400 `MATERIAL_IMPORT_VALIDATION_FAILED`：`detail.missingElements[]`、`detail.inactiveElements[]`、`detail.compositionMismatches[]`、`detail.rowErrors[]`；任一阻断项存在则事务零写入。
- Excel `组号` 只用于把行聚成配置；系统按现存全部状态配置的最大 `group_no + 1` 分配数据库组号，并以 `<材质编号>-<两位组号>` 生成配置编号。

## 3. 兼容与错误响应

- 旧两 sheet 材质模板继续返回 `IMPORT_TEMPLATE_OUTDATED`。
- 鉴权、xlsx MIME 和 20 MB 上限沿用现状。
- 所有校验错误使用既有 `{ code, message, detail }` 包装，不返回未处理异常或数据库约束原文。
