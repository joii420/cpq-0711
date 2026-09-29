# VAT 实现阶段测试报告

> 任务：`task-260927-增值税税率模块`
> 日期：2026-09-28
> 分支：`feat/task-260927-vat`
> 基线提交：`6a816e86`（实现尚未提交，以下证据对应当前工作树）

## 1. 执行环境

- 后端：Java 21 / Quarkus；`mvnw` 使用专用测试库 `cpq_db_test`，未触碰共享开发库。
- 前端：Vite 8 / Vitest 4.1.4；worktree 的 `node_modules` 使用本地依赖软链，仅用于本地构建，不纳入提交。
- 迁移：Flyway 启动校验通过，版本为 `V446__task260927_vat_schema.sql`；Dataset Registry 自检通过（97 tables / 1779 columns）。

## 2. 已执行证据

| 命令 | 结果 |
|---|---|
| `cd cpq-backend && ./mvnw -q -DskipTests compile` | PASS |
| `cd cpq-backend && ./mvnw -q -DskipTests test-compile` | PASS |
| `cd cpq-backend && ./mvnw -q -Dtest=VatFormulaTokenTest test` | PASS，1/1 |
| `cd cpq-backend && ./mvnw -q -Dtest=VatSchemaContractTest -Dquarkus.http.test-port=18081 test` | PASS，5/5；迁移、自检、Quarkus 启停均通过 |
| `cd cpq-frontend && npm run build` | PASS，`tsc -b` 与 Vite build 均通过 |
| `cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts` | PASS，128/128 |
| `cd cpq-frontend && npx vitest run src --exclude 'src/**/*.spec.ts'` | PASS，110 files / 1439 tests |
| `git diff --check` | PASS |
| `.claude/hooks/check-decisions.sh` | PASS |
| `.claude/hooks/check-selfcheck.sh` | PASS |
| `.claude/hooks/guard-redline.sh` | PASS |

关键后端断言包括：默认分类为 `GENERAL_GOODS` 且当前版本 13%；13% 转比例 0.13；100 × 1.13 = 113；113 ÷ 1.13 = 100；历史 `quotation.tax_rate` 保留；版本边界按半开区间字段存在。

## 3. 当前覆盖状态

- 已覆盖：数据库结构与幂等种子、默认分类兜底、产品分类优先解析、报价头默认 VAT、报价行快照 DTO/持久化、旧行不回填、公式 `[增值税]` 比例值、无报价上下文待报价提示、客户默认 VAT 字段、客户/销售产品同源只读展示、现有报价卡片快照徽标。
- 未形成真实服务证据：销售产品税务分类手工绑定接口/抽屉交互、导入预览中未知税务分类整批拒绝、含税元素单价策略在 QUOTE/COST_BASIC/COST_DETAIL 三套真实模板中的端到端取数、Excel/PDF/HTML 输出回归。
- 这些未覆盖项不是以自动化“通过”代替；需在后端 8081 与前端 5174 可安全启动、且明确测试数据后继续执行。当前端口未运行，因此没有虚报 curl 或 Playwright 结果。

## 4. E2E 状态

本轮未启动默认 profile 后端，也未启动 Vite 服务：默认后端连接共享开发库，禁止为跑 E2E 擅自启动并写入共享库；当前也没有已运行的长期服务可复用。因此 S-UI/E2E 暂记为“待执行”，不作为本轮 AC 已验收证据。

## 5. 结论

实现与静态/单元/专用测试门槛已通过；尚不能把 AC-1~AC-12 全部标记为用户验收通过。下一步应使用专用可回收环境补跑导入、三方言含税策略、服务端 API 权限和真实报价 Step2/详情页回归。
