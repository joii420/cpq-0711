# 测试报告

状态：实现完成，已通过自动化自检，待用户验收。

## 已执行

| 检查 | 结果 | 证据 |
|---|---|---|
| 后端编译/打包 | PASS | `./mvnw -q -DskipTests package` |
| 后端单位换算/导入版本单测 | PASS | `UnitConversionTest`、`PlainTableWriterTest` |
| 单位管理 API 契约测试 | PASS | `UnitManagementApiContractTest`，测试库 Flyway 已到 448，HTTP/权限/预置关系断言通过 |
| 前端全量单测 | PASS | 112 个测试文件、1444 项测试全部通过（新增 KPCS→重量跨类型小计回归） |
| 前端类型检查/生产构建 | PASS | `npm run build`（`tsc -b && vite build`） |
| Java/文本格式差异检查 | PASS | `git diff --check` |
| 服务启动自检 | PASS | Registry↔DDL：97 张表 / 1783 列；Flyway `448|true` |
| 本地端口探活 | PASS | 前端 `200`（5174）；后端未登录 `401`（8081，符合鉴权预期） |
| 用户导入报价单回归修复 | PASS | 产品卡片显示客户产品结算单位；页签费用列小计/合计按原始计算值显示，不做汇率换算且不显示币种 |
| 报价单列表金额/货币口径 | PASS | 总金额按报价单冻结汇率换算为报价币种，货币单独列展示；`1717.135540718 CNY ÷ 7.88 = 217.910601614 USD` 与产品卡片一致 |
| 只读报价卡片 KPCS→g 回归 | PASS | `QT-20261012-0950` 详情页切到“组装加工费”后，行值/小计/合计均为 `39.54034733`，不再出现 `0.039540347` |

## 尚未自动化覆盖

浏览器级 Playwright 的导入 Excel、单位 Drawer 和报价/核价冻结序列仍未完整自动化；本轮已用 Chrome 实测详情页 `QT-20261012-0950` 的真实页面与目标页签。

## 覆盖说明

- 预置七单位及 18 条显式有向关系写入 `V448__task261004_unit_management.sql`。
- 单位关系运行时缓存由数据库启动加载，并在管理员保存、启用、停用时同步；大小写、跨类型、未知/复合单位按原值透传。
- 报价目标为客户产品 `settlement_unit`；核价列表和生产料号信息分别携带基础/详细 `pricing_unit`，根生产料号作为核价目标，子件不切换目标。
- 已提交卡片在值快照中保存目标单位，草稿仍读取当前有效配置。
- `ds_quote_customer_part.version` 为服务端乐观锁内部列；客户料号 Excel 只声明可选“结算单位”，不声明“版本”。导入新行自动从 1 开始，覆盖已有行由 SQL 自动递增。
- 用户回归单 `QT-20261012-0950`：快照与客户料号数据均已保存结算单位 `g`，此前仅因产品卡片头部漏渲染而不可见；费用页签 `1 + 0.1` 被错误按 `7.88` 汇率除算为 `0.139593909`，现已恢复为原始小计/合计数值且不显示货币单位。产品卡底部“产品小计”仍沿用既有报价币种展示规则。
- 本次截图中的第二个问题根因：编辑卡片已有显式 `targetUnit`，但 `ReadonlyProductCard` 的 PASS1/`buildCrossTabRows` 仍直接使用未注入目标单位的组件；单位换算兼容分支遂将 `KPCS` 按历史 `0.001` 处理。现将报价结算单位、核价基础/详细计价单位显式注入只读与编辑链路，并在报价行回读/导入时保留这些字段。该修复不是改 `unit_source_field` 规则；`unit_source_field` 仍是同组件单位字段名。

## 2026-10-13 动态下拉补充复验

| 检查 | 结果 | 证据 |
|---|---|---|
| 动态选择器统一组件 | PASS | 新增 `RemoteInfiniteSelect`，后端关键词查询、页码请求、滚动加载、请求竞态丢弃、已选项回显 |
| 模板管理客户模糊搜索 | PASS | Chrome 实测 `GET /api/cpq/customers?page=0&size=50&keyword=Cust`，返回匹配项 |
| 模板管理客户滚动分页 | PASS | Chrome 实测下拉滚动触发 `GET /api/cpq/customers?page=1&size=50` |
| 客户搜索大小写兼容 | PASS | `CustomerService` 查询统一使用 `LOWER(...) LIKE LOWER(:kw)`，覆盖名称、编码、联系人/电话 |
| 前端回归 | PASS | `npm run build`；Vitest 112 个文件、1444 项测试 |
| 后端回归 | PARTIAL | `./mvnw -q -DskipTests compile` 通过；全量 `mvn test` 3520 条中 273 failures、614 errors，主要为既有测试库连接回滚/历史契约失败，未作为本次改动通过依据 |
| 前端 ESLint | BASELINE BLOCKED | 全工程 `npm run lint` 仍有项目既有 3847 errors/126 warnings（以 `any`、未使用变量等为主）；本次不将其作为门禁，类型检查、构建和 Vitest 结果有效 |
