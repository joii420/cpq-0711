# 测试报告：自制加工费工序名称字段

> 执行日期：2026-10-23<br>
> 代码基线：`master` / `221f6e17`<br>
> 结论：✅ 用户验收通过（2026-10-23），任务已交付；已生成独立部署包 `deploy/2026102301/`。

## 1. 验收结果

| AC | 结果 | 证据 |
|---|---|---|
| AC-1 | ✅ | 字段树顺序为「工序编号、工序名称、值」；双击「工序名称」后已选字段数由 9 增至 10。 |
| AC-2 | ✅ | 编译 SQL 含 `LEFT JOIN process_master`，连接 `operation_no = process_no`，并投影 `process_name AS "_工序_工序名称"`。 |
| AC-3 | ⚠️ 结构验证通过 | 编译器测试确认使用 LEFT JOIN 且 `fallback_to_join_key=false`；测试库 79 条现有数据均能匹配工序主数据，未向共享测试库写入不匹配业务数据。 |
| AC-4 | ✅ | 既有字段顺序回归测试通过；新字段为可选，未修改任何存量组件配置。 |
| AC-5 | ✅ | Flyway V452、后端定向测试、前端 TypeScript 与浏览器联调均通过。 |

## 2. 自动化验证

在 `cpq-backend` 执行：

```text
./mvnw -q -Dtest=SelfProcessNameLookupTest,FieldTreeAndDialectParseSelfCheckTest test
SelfProcessNameLookupTest: 2 tests, 0 failures, 0 errors
FieldTreeAndDialectParseSelfCheckTest: 9 tests, 0 failures, 0 errors

./mvnw -q -Dtest=SelfProcessNameMigrationTest test
SelfProcessNameMigrationTest: 1 test, 0 failures, 0 errors
Flyway: validated 435 migrations; V452 applied successfully

./mvnw -q -DskipTests test
exit 0
```

在 `cpq-frontend` 执行：

```text
./node_modules/.bin/tsc -b --pretty false
exit 0
```

数据库只读核对：

```text
452|true|task261022 self process name lookup
PROCESS_NAME_LK|工序名称|process_name
```

## 3. 浏览器亲验

- Browser 插件不可用，按前端调试规范使用系统 Chrome + Playwright。
- 独立环境：前端 `127.0.0.1:15174`，后端 `127.0.0.1:18082`，数据库 `cpq_db_test`；验证后已停止临时服务。
- 路径：组件管理 → 搜索并打开 `COMP-2414`（自制加工费）→ 取数配置 → 展开字段组 → 双击工序名称 → 编译。
- 页面标题 `cpq-frontend`，无空白页、无框架错误遮罩；相关 console error 为 0，仅有既有 Ant Design deprecated warning。
- 全程只读验证，没有点击保存。

截图：

- `/tmp/task261022-process-name.png`
- `/tmp/task261022-process-name-selected.png`

## 4. 环境边界与风险

- 2026-10-23 经用户明确授权重启共享前后端；旧 8081 进程两轮 TERM 未退出后，仅对已核实的 Maven/Java PID 强制终止并按原配置恢复。Flyway 已在 `cpq_db_0724` 成功应用 V452，5174 返回 200，8081 未登录业务接口返回 401。
- `cpq_db_test` 没有未匹配工序编号的现成记录，因此 AC-3 的真实空值行预览未执行；LEFT JOIN 与无 fallback 已由编译结构测试覆盖。
- 用户明确要求打包后，已生成新的 `deploy/2026102301/`，未覆盖任何历史部署包。

## 5. 自检声明

已自检：后端 12 项定向测试全部通过；Flyway V452 在 `cpq_db_test` 与 `cpq_db_0724` 的迁移历史均为 `success=true`；前端 TypeScript 检查通过；共享页面字段顺序、选择交互和编译 SQL 已由 Playwright 亲验；无新增 N+1 查询；未保存组件配置；部署包构建、SQL 全链与成品启动冒烟均通过。

## 6. 用户验收

- 2026-10-23，用户在重启后的共享前后端环境完成验收并明确回复“验收通过”。
- 任务进入已交付状态；分支与 worktree 按项目规则保留，待用户另行授权回收。

## 7. 部署打包

- 交付目录：`deploy/2026102301/`；代码基线：`cb90d949893859ba44def7d364598df1df92e0a9`。
- 前端 `npm run build` 通过；后端 `./mvnw -B -ntp -DskipTests clean package` 通过。
- 上次部署数据库基线为 V451；本包携带且仅需新增执行 `update-261023-self-process-name-lookup.sql`，目标基线 V452，MD5 为 `b2c8b118f6eedea6b12db3484a41651e`。
- 一次性空库按 V439 全量初始化及 V440～V452 全部增量顺序执行通过；V452 连续执行两次均成功，节点、字段、边、连接键均保持唯一。
- 成品在隔离临时库上以 prod profile 启动成功；前端首页 HTTP 200，后端未登录业务接口 HTTP 401；临时前后端与临时数据库均在验证后清理。
- 风险记录：首次成品启动探针遗漏覆盖 `DB_NAME`，短时连接到开发共享库并触发默认开启的价格调整启动恢复；停止日志记录本实例处理 `READY=60`、`FAILED=1`，另有 69 条被并发实例处理。该流程是预算试算（日志 `dryRun=true`），但会更新审核行状态/试算列；未擅自回滚。随后已改用隔离库并显式关闭启动恢复及调度重验，包内 README 已补充安全启动命令。
