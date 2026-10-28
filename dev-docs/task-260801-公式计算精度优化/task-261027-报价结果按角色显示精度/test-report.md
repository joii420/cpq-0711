# 测试报告：报价结果按角色显示精度

| 项 | 值 |
|---|---|
| 执行日期 | 2026-10-27 |
| 分支 / 合并 | `feat/task-261027-role-display-precision` → `master@7d9f0a11`；worktree 已移除，分支保留待用户删除 |
| 数据库 | `10.177.152.12:5432/cpq_t261027_rdp_1027a`（一次性隔离库，V455） |
| 界面栈 | 后端 `127.0.0.1:18082` + 前端生产预览 `127.0.0.1:15176` |
| 浏览器 | Playwright Chrome Desktop 1440×900；Browser plugin 不可用，按前端测试规范使用仓库 Playwright |
| 结论 | 实现、保存、导出、权限与四角色界面验证全绿；已合并 `master`、部署测试服务器，并于 2026-10-28 通过用户真机验收，任务已交付 |

## 1. 结论摘要

- 销售代表、销售经理：同一计算值 `1.2345678915` 在报价列表、编辑、产品卡、详情、Excel 视图和比对视图显示 `1.2346`。
- 定价经理、系统管理员：同一值显示 `1.234567892`。
- GET JSON、报价头金额、行金额、卡片/Excel JSON 快照一直保留原值；真实保存无关文本字段后仍逐字为 `1.234567891500`。
- 六类对外输出（HTML、PDF、报价 Excel、Excel 视图导出、比对 Excel、邮件附件）按发起人服务端会话生成 4/9 位文本。
- 匿名查询与六输出共七个请求均为 401；query/body/header 伪造管理员角色或 9 位不能覆盖销售经理会话。
- 计算过程 12 位、数据库 numeric scale、DTO/JSON 合同、快照、公式算序和除法精度未改。

## 2. AC 结果

| AC | 结果 | 可复核证据 |
|---|---|---|
| AC-1 | 通过 | S-FLOW T-04；`SALES_REP` / `SALES_MANAGER` 列表、编辑、详情均显示 `1.2346`，GET 仍含 `1.2345678915`。 |
| AC-2 | 通过 | S-FLOW T-05；`PRICING_MANAGER` 在既有允许的详情入口、`SYSTEM_ADMIN` 在列表/编辑/详情显示 `1.234567892`。 |
| AC-3 | 通过 | 前后端策略单测：显式 2 位四角色均 `1.23`；显式 6 位为销售 `1.2346`、其他 `1.234568`。 |
| AC-4 | 通过 | T-04/T-05/T-06；列表、编辑、卡片、详情、Excel、比对口径一致，loading / 红色 Alert / 路由错误遮罩均为 0。 |
| AC-5 | 通过 | 后端 `Task261027RoleDisplayPrecisionOutputFlowTest` 4/4；两角色真实会话解析六输出及邮件附件，文本分别为 `1.2346` / `1.234567892`，落库值不变。 |
| AC-6 | 通过 | `NumberFormatUtilTest` 与前端 `formatNumber` 全边界用例：去尾零、HALF_UP 正/负半界、零不出现 `-0`。 |
| AC-7 | 通过 | T-08 真实两次 PUT=200；保存/还原 `projectName` 前后 GET、关系列和两类快照逐字相等。后端批量/逐行两路 header-only、line-change、discount-change 集成用例通过。 |
| AC-8 | 通过 | T-09 同一浏览器 `SALES_REP → SYSTEM_ADMIN → SALES_REP`，显示 `4 → 9 → 4`，每步 DB 完整快照含 `updated_at` 不变。 |
| AC-9 | 通过 | 独立前后端单测覆盖原始输入、汇率、税率、利润快照、基础资料取数；元素实际价快照在销售角色下仍走原 9 位上限。 |
| AC-10 | 通过 | T-10 实测七个匿名请求 `[401,401,401,401,401,401,401]`；伪造角色后 HTML/比对仍只含 `1.2346`。未扩大定价经理列表/编辑权限。 |
| AC-11 | 通过 | 后端 clean 39/39；前端 116 个测试文件、1473/1473；TypeScript 和生产构建成功；Playwright 6/6。 |

## 3. 自动化结果

### 3.1 后端主线 clean 回归

命令（通过 `DB_NAME=cpq_t261027_rdp_1027a` 显式覆盖）：

```text
./mvnw clean -Dtest=SaveDraftIncrementalProtocolTest,NumberFormatUtilTest,
QuotationDisplayPrecisionPolicyTest,ExcelViewServicePrecisionTest,
ComparisonExportServiceTest,QuotationExportRolePrecisionTest,
Task261027RoleDisplayPrecisionUnitTest,
Task261027RoleDisplayPrecisionOutputFlowTest test
```

```text
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 43.366 s
```

其中：

- `SaveDraftIncrementalProtocolTest` 14/14；批量/逐行两路纯单头保存保留 `1.234567891500`，真实行变化仍重算，折扣变化仅重算折后额。
- 六输出真实会话 4/4；销售经理附件正文 `display=1.2346`，系统管理员 `display=1.234567892`。
- 格式化、Excel 视图、报价 Excel、比对 Excel 与角色策略单测 21/21。

### 3.2 前端主线 clean 回归

```text
npx tsc -b --clean                         exit 0
npm test                                   116 files / 1473 tests passed
npx tsc -b                                 exit 0
npm run build                              3442 modules transformed / built in 1.09s
```

生产构建只有既有大 chunk 警告，无新构建失败。

### 3.3 S-FLOW 生产预览

```text
npx playwright test -c e2e/task261027-role-display-precision.config.ts
6 passed (50.0s)
```

| 用例 | 结果 | 时长 | 关键结果 |
|---|---:|---:|---|
| T-04 | PASS | 13.8s | 销售代表/经理列表+编辑+详情全为 `1.2346` |
| T-05 | PASS | 8.7s | 定价经理允许详情、管理员列表+编辑+详情全为 `1.234567892` |
| T-06 | PASS | 11.3s | 四角色 Excel/比对唯一命中真实产品行，加载/错误遮罩为 0 |
| T-08 | PASS | 7.7s | 无关项目文本保存和还原均 200，计算状态逐字不变 |
| T-09 | PASS | 6.1s | 同浏览器角色切换 `4 → 9 → 4`，DB 不变 |
| T-10 | PASS | 1.2s | 七个 401，伪造角色无效 |

本轮开始前、执行中、结束后均未观测到其他 Playwright 进程。

## 4. 主线亲验

主线在同一生产预览栈上另起独立 Playwright Chrome 会话，不复用测试代理断言：

- 销售经理报价列表真实行：`T261027_RDP_FLOW_Q001 ... 1.2346 CNY`。
- 销售经理产品卡实际文本：`产品小计 1.2346 CNY`。
- 系统管理员同产品卡：`产品小计 1.234567892 CNY`。
- 系统管理员比对表真实产品行：`报价 1.234567892`。
- 页面身份分别为 `SALES_MANAGER` / `SYSTEM_ADMIN`；非空渲染、交互切换成功，可见错误 Alert=0，console error=0。
- 人工检查的主线截图显示布局未因 4/9 位文本发生溢出或错位。截图属临时验证产物，未纳入仓库。

## 5. 测试期间发现与修正

| 最早发现环节 | 问题 | 处置与最终结果 |
|---|---|---|
| 实现期子代理回报 | 独立前端测试把角色上限当作位置参数传入，与已裁决的 `DecimalSpec.maxDisplayDecimals` 契约不一致，会造成 3 个假红 | 测试代理按公开契约改为在 `DecimalSpec` 中传上限；实现契约未被错误测试反向修改。 |
| 实现期子代理回报 | 独立前端测试把 AC-9 的“保持原显示规则”误写成“保留输入尾零”，会把既有去尾零行为误判为 5 个回归 | 断言改为比较应用角色上限前后的非计算值不变量，不改变既有格式化契约。 |
| 测试执行 | 首轮后端独立清理顺序先删父行，触发 FK 失败 | 仅修测试清理顺序，重跑 4/4。 |
| 主线审用例 | 前端用 `body` 宽泛断言“不含 raw”，会把网络原值或非计算值误判为泄漏 | 收窄到真实计算容器和数字 token。 |
| 主线审用例 | 用例尝试让定价经理进入既有禁止的列表/编辑路由，与 AC-10 冲突 | 定价经理只走已允许详情；管理员覆盖列表/编辑/详情，不扩权。 |
| 测试执行 | Vite dev HMR 多次出现纯白偶发，不能作为稳定验收环境 | 使用当前干净生产 build 的 `vite preview` 重跑，纯白不再出现。 |
| 测试执行 | Ant Design 隐藏的 `.ant-table-measure-row` 被当成业务行 | 选择器改为“可见且非 measure”的 PART_NO 行。 |
| 测试执行 | 修改报价名称不会让外层表单真正 dirty，T-08 未触发保存 | 改用外层 `projectName`（“关联项目”）触发真实 PUT，finally 严格还原。 |
| 测试执行 | T-08 首次真实保存暴露：纯单头保存也会查行合计，将报价头 `1.234567891500` 回写成 `1.234567892000` | 后端最小修复：增量三空数组视为纯单头 patch；两条 saveDraft 路径只在行变化时聚合金额，折扣独立变化仅重算折后额。服务层 14/14 + 真实 HTTP T-08 通过。 |
| 测试执行 | 某轮途中其他 worktree Playwright 启动，造成并发污染 | 该轮不作最终证据；等待全局无 Playwright 后单 worker 重跑，6/6 通过。 |
| 主线审交付 | 共享的独立核价页可能误继承销售 4 位，元素实际价快照可能被误当计算结果 | 核价/FINANCE 表面强制既有 9 位；元素实际价使用角色无关格式化。前端全量 1473 项通过。 |

## 6. 环境与数据纪律

- 首次后端定向测试连接规范允许的 `cpq_db_test`，Quarkus 发现其为 V454，自动应用源码已有的 `V455__task261026_quotation_line_profit_snapshot.sql`。该迁移不属于本任务，后续显示 `Schema public is up to date`，未回退。
- 最终主线后端测试显式改连一次性库 `cpq_t261027_rdp_1027a`，验明 Flyway 当前版本 V455。
- 前端测试账号、报价、产品、模板一律使用 `T261027_RDP_` / `T261027_RDP_FLOW_` 私有前缀与固定 UUID。
- 清理前读取：4 用户、1 报价、1 产品行、2 视图快照、1 模板、4 模板组件快照；关联 operation log / notification / reset token / approval 均为 0。
- 精确事务清理结果：`DELETE quotation=1`、`DELETE template=1`、`DELETE users=4`；级联后报价行/视图/模板快照/前缀用户均为 0。未触碰其他数据。
- 一次性数据库实体仍保留；`DROP DATABASE cpq_t261027_rdp_1027a` 属不可逆红线，只列入待回收清单，未执行。

## 7. 收尾状态

- `git diff --check`：exit 0。
- N+1 自检：后端生产差异未新增 `for` / `forEach` / `stream` 循环；角色会话每个请求只解析一次，格式化循环内无 repository/原生查询。`saveDraft` 仅在真实行变化时执行 1 条整单 `sum(subtotal)`，SQL 条数与行数 N 无关。
- 本任务未新增依赖、未改 schema/迁移/配置；已实际 clean 编译并启动 Quarkus、生成前端生产 build，无额外冷启动触发项。
- 临时 Vite dev / preview / Quarkus / Playwright 进程均已停止；15175 / 15176 / 18082 无监听。
- 临时视觉验证脚本与 preview config 已删除。
- 用户后续显式要求部署，已新建 `deploy/2026102702/`；上次部署 SQL 基线为 V455，本任务无新迁移，包内 README 明确本次 V455 → V455、不执行 SQL。
- 用户将主工作区改动提交为 `dc823d40`后，以非破坏方式合并功能分支。共解析 6 个前端内容冲突，保留主线币种展示/查询改动并叠加角色 4/9 位上限，合并提交为 `7d9f0a11`。
- 合并后主线复验：前端 116 个文件 / 1473 项全绿，`tsc -b --clean`、`tsc -b`、Vite 生产构建成功；后端 clean 39/39 全绿，`BUILD SUCCESS`。

## 8. 已自检

已自检：AC-1~AC-11 均有非空正向证据；后端 clean 39/39、前端 1473/1473、TypeScript、生产构建、Playwright 6/6、主线视觉亲验、真实保存、冲突解析后主线复验与数据清理均已复核。新增失败 0，未验项 0；已合并并部署 `2026102702`，未做超出授权的破坏性操作。

## 9. 测试服务器部署验证（2026-10-28）

- 交付包：`deploy/2026102702/`，代码产物基线 `master@115cf40c`；前端 `dist/`、后端 `quarkus-app/`、`eclipse-temurin:21-jre` Dockerfile 和 README 齐全。
- SQL 顺序：上次包 `2026102701` 已到 V455；本任务无新增迁移，本次不执行 SQL。远端 `flyway_schema_history` 最新值为 `455|t`，Quarkus 日志为 `Schema public is up to date`。
- 上传后远端产物哈希与本地同口径一致；旧 dist/quarkus-app/Dockerfile 及前后端镜像保留 `backup-20261027234508`。
- 远端启动：Java 21.0.11 LTS，Quarkus `webtest` 6.845s 启动，严格 ERROR 级别 0；外部前端 200，后端未登录业务端点 401。
- 真实输出：SYSTEM_ADMIN / SALES_MANAGER 登录与 HTML 导出均为 200；同一报价分别输出 `1717.135540718` / `1717.1355`，销售结果不含 9 位文本。
- 真实浏览器：Browser plugin 不可用，按规范使用 Playwright Chrome 1440×900。管理员报价列表显示 `1717.135540718 USD`，销售经理显示 `1717.1355 USD`；页面非空，无框架错误覆盖层，console error/warn 均为 0。
- 验收入口：`http://10.177.152.12:5173`。

## 10. 闸门 B 与结案（2026-10-28）

- 用户在固定测试环境 `http://10.177.152.12:5173` 完成真机验收并明确回复“验收通过”；闸门 B 通过，任务状态更新为“已交付”。
- 用户验收未发现新增缺陷；无需返修，也没有新增规则或跨任务决策需要登记。
- 待回收对象仍为 1 个一次性数据库 `cpq_t261027_rdp_1027a`；未取得本次 `DROP DATABASE` 明确授权，故继续保留。功能分支 `feat/task-261027-role-display-precision` 已完全合并、worktree 已移除，分支实体仍由用户执行删除命令。
- 拦截点共 11 个：实现期子代理回报 2、测试执行 6、主线审交付 1、主线审用例 2；主线亲验、冷启动、用户验收、上线后均为 0。

已自检：AC-1~AC-11、主线测试、测试服务器角色专项和用户真机验收证据均已落盘；`INDEX.md`、`BACKLOG.md`、`RECORD.md` 与任务状态已按闸门 B 结案规则同步，未执行分支删除或数据库删除等不可逆操作。
