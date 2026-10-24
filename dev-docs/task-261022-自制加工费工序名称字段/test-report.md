# 测试报告：自制加工费工序名称字段

> 执行日期：2026-10-23<br>
> 代码基线：`master` / `221f6e17`<br>
> 结论：开发自检通过，待用户验收；未生成部署包。

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

- 共享开发服务 `8081` 为其他会话持有的既有进程且请求超时，本次未擅自停止或重启；共享开发库 `cpq_db_0724` 仍停在 V451。正常重启应用后 Flyway 会应用 V452。
- `cpq_db_test` 没有未匹配工序编号的现成记录，因此 AC-3 的真实空值行预览未执行；LEFT JOIN 与无 fallback 已由编译结构测试覆盖。
- 本次没有生成或更新 `deploy/` 交付目录。

## 5. 自检声明

已自检：后端 12 项定向测试全部通过；Flyway V452 在 `cpq_db_test` 的迁移历史为 `success=true`；前端 TypeScript 检查通过；真实页面字段顺序、选择交互和编译 SQL 已由 Playwright 亲验；无新增 N+1 查询；未修改共享服务、未生成部署包。
