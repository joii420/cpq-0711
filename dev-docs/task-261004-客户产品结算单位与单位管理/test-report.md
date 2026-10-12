# 测试报告

状态：实现完成，已通过自动化自检，待用户验收。

## 已执行

| 检查 | 结果 | 证据 |
|---|---|---|
| 后端编译/打包 | PASS | `./mvnw -q -DskipTests package` |
| 后端单位换算/导入版本单测 | PASS | `UnitConversionTest`、`PlainTableWriterTest` |
| 单位管理 API 契约测试 | PASS | `UnitManagementApiContractTest`，测试库 Flyway 已到 448，HTTP/权限/预置关系断言通过 |
| 前端全量单测 | PASS | 111 个测试文件、1442 项测试全部通过 |
| 前端类型检查/生产构建 | PASS | `npm run build`（`tsc -b && vite build`） |
| Java/文本格式差异检查 | PASS | `git diff --check` |
| 服务启动自检 | PASS | Registry↔DDL：97 张表 / 1783 列；Flyway `448|true` |
| 本地端口探活 | PASS | 前端 `200`（5174）；后端未登录 `401`（8081，符合鉴权预期） |

## 尚未自动化覆盖

浏览器级 Playwright 业务流程（登录后实际上传 Excel、Drawer 操作和报价/核价冻结序列）未在本轮自动执行，建议由用户通过已启动的前后端服务验收。

## 覆盖说明

- 预置七单位及 18 条显式有向关系写入 `V448__task261004_unit_management.sql`。
- 单位关系运行时缓存由数据库启动加载，并在管理员保存、启用、停用时同步；大小写、跨类型、未知/复合单位按原值透传。
- 报价目标为客户产品 `settlement_unit`；核价列表和生产料号信息分别携带基础/详细 `pricing_unit`，根生产料号作为核价目标，子件不切换目标。
- 已提交卡片在值快照中保存目标单位，草稿仍读取当前有效配置。
- `ds_quote_customer_part.version` 为服务端乐观锁内部列；客户料号 Excel 只声明可选“结算单位”，不声明“版本”。导入新行自动从 1 开始，覆盖已有行由 SQL 自动递增。
