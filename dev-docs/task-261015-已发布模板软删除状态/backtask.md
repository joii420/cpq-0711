# 后端任务

## B-1 状态迁移与删除服务（服务 AC-1、AC-8、AC-9、AC-11）

- 新增 Flyway 迁移，将 `template.status` CHECK 扩展为 `DRAFT/PUBLISHED/ARCHIVED/DELETED`；不改写存量行、不清理引用。
- 修改 `TemplateService.delete`：DRAFT 保持物理删除；PUBLISHED 仅状态更新为 DELETED；ARCHIVED/DELETED 拒绝。
- 删除不复用归档的进行中报价/产品绑定阻断，写清审计日志并保证事务原子性。
- `DELETED` 禁止 publish/archive/update/component/formula/sql-view 等编辑入口。

## B-2 列表、权限和管理员新草稿（服务 AC-3、AC-7、AC-9、AC-12）

- 默认列表排除 DELETED；显式 `status=DELETED` 和按 ID 查看仅 SYSTEM_ADMIN。
- `createNewDraft` 对 DELETED 源模板仅 SYSTEM_ADMIN 放行，复制结果为新 DRAFT，源模板不变。
- 管理端点不新增恢复能力；拒绝任何 DELETED→其他状态的写入路径。
- 批量冻结/漂移检查等管理查询按“可审计但不可恢复”的口径处理。

## B-3 新引用守卫（服务 AC-2、AC-5、AC-11）

- 复核并补齐 `QuotationService.validateTemplateBinding`、复制报价、产品模板绑定、客户匹配、自动默认模板和核价模板过滤。
- 保留当前值不变的历史 DRAFT 保存豁免；切换到 DELETED 的新绑定必须拒绝。
- 已存在的外键、产品绑定、报价模板关系不删除、不迁移、不置空。

## B-4 历史快照读取（服务 AC-4、AC-5、AC-10）

- 将 DELETED 纳入 `PublishedTemplateReader` 及其调用方的冻结状态判断。
- 同步 `ComponentSqlViewService`、`ConfigureSnapshotService`、`BomTreeRenderService`、`ExcelViewService` 等状态分支，禁止回落活组件表。
- 验证报价卡片、BOM、Excel/PDF 读取均继续使用模板/报价快照。

## B-5 后端测试与自检（服务 AC-1~AC-11）

- 增加状态迁移、权限、列表默认隐藏、显式管理员查询、历史 DRAFT 保存、新草稿复制和快照渲染契约测试。
- 对新增/修改循环执行 N+1 自检；查询客户/模板摘要必须批量处理。
- 迁移只通过 Flyway 启动验证，不手工执行 SQL；保留迁移历史和启动日志证据。

## 后端不做

- 不修改 `quotation.customer_template_id`、`quotation_line_item.template_id`、`quotation_view_structure` 或价格计算。
- 不修改独立 `config_template` 模块。
