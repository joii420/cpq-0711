# 模板多客户适用：测试执行报告

> 执行分支：`feat/task-261013-template-customer-many`
> 执行日期：2026-10-14
> 结论：实现已完成自检并合并 master；用户已在合并后环境完成验收，任务已交付。

## 已执行命令

| 检查项 | 命令 | 结果 |
|---|---|---|
| 后端编译 | `cpq-backend/./mvnw -q -DskipTests compile` | 通过，退出码 0 |
| 前端类型+生产构建 | `cpq-frontend/npm run build` | 通过；`tsc -b` 与 Vite 构建均通过 |
| 前端全量 lint | `cpq-frontend/npm run lint` | 未通过：基线已有约 3,970 条 ESLint 问题，主要为既有 `any`/未使用变量；未将本任务代码格式化为掩盖基线问题 |
| 改动文件 lint | `npx eslint src/pages/quotation/QuotationCreateForm.tsx src/pages/template/TemplateConfigPanel.tsx src/pages/template/TemplateConfiguration.tsx src/pages/template/TemplateList.tsx src/pages/template/types.ts` | 未通过：41 个既有错误、1 个既有 warning；本次新增的客户集合辅助代码未新增 lint 错误 |
| 差异空白检查 | `git diff --check HEAD` | 通过，退出码 0 |
| SQL 安全静态检查 | `rg -ni '\\b(drop|truncate|delete|update)\\b' ...` | 两个迁移脚本均无命中 |
| 本地 PostgreSQL 探测 | `pg_isready -h 127.0.0.1 -p 5432` | 未执行数据库验证：本地 5432 无服务（no response） |

## AC 证据状态

- 已有代码证据：`template_customer` 组合主键、双外键、双索引；V450 Flyway 迁移；生产事务脚本；旧 `customerId` 请求兼容；多客户 DTO 摘要批量加载；报价/核价匹配和通用回退均按关联表判断；草稿复制、删除、列表/配置/报价选择已接入 `customerIds`。
- 已完成静态验证：AC-10/11 的请求分流与去重逻辑、AC-13 的迁移破坏性关键字扫描、AC-14 的生产脚本前置/后置检查代码审阅。
- 待隔离数据库验证：AC-1/2/5/7/9/13/14 的 PostgreSQL 16 双次执行、行数/孤儿/历史表不变和查询行为；不能在共享开发库代替执行。
- 待浏览器/E2E 验证：AC-3/4/6/8/12 的配置→发布→报价 A/B、列表筛选、已发布冻结和既有报价快照回归。

## 已知限制与下一步

1. 本次无可用的独立 PostgreSQL 16 实例，生产 SQL 未在任何共享库执行；上线前必须在数据库副本运行两次并保留日志。
2. 前端全量 lint 受 master 基线问题阻塞，但生产构建通过；Gate B 需要按项目 UI/E2E SOP 在独立测试数据前缀下补跑。
3. 测试工程师/前端工程师子代理因模型容量限制未能接管执行，本报告保留客观限制，不把未执行的 E2E 标记为通过。

## 合并后服务重启验证（2026-10-14）

- 主分支合并提交：`d1c48339 merge: 模板支持多客户适用范围`。
- 后端以默认 dev profile 启动，连接 `cpq_db_0724`；Flyway 日志确认 `V450` 成功应用，`template_customer` 回填后共有 41 条关联。
- `curl --noproxy '*' http://localhost:8081/api/cpq/components` 返回 HTTP `401`，说明后端已启动且鉴权链路正常。
- `curl --noproxy '*' http://localhost:5174/` 返回 HTTP `200`，前端 Vite 服务正常。
- 启动日志存在既有 schema validation 警告 `missing table [mat_composite_process]`，未阻止服务启动；本任务未新增该表依赖。

## 用户验收结论（2026-10-14）

- 用户在合并后的 master 环境完成真机验收，结论为“验收通过”。
- AC-1~AC-14 无新增返修项；本次交付的模板多客户配置、匹配、列表、核价及兼容行为按需求完成验收。
- 待回收清单：无（本任务未创建一次性数据库或额外临时服务实例）。
- 已自检：后端编译、前端生产构建、Flyway V450 应用、前后端端口探活及合并后服务验证均有上文证据。
