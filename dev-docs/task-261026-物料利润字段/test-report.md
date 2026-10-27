# 测试报告

## 测试服务器部署与远端亲验（2026-10-26 PDT / 2026-10-27 CST）

- 本地 5174 / 8081 服务均已关闭；部署前后复核本机无对应监听。
- 从干净 detached worktree 的 `master@b54b62a65ec01291312036959af1bed9af613000` 构建，未带入主工作区现有未提交改动。前端 `npm run build` 通过（3439 modules），后端 `./mvnw -B -ntp -DskipTests clean package` 为 `BUILD SUCCESS`。
- 新部署包：`deploy/2026102601/`；上次 SQL 基线 V452，本次携带 `update-261026-material-profit.sql` 升至 V454。该脚本在 `cpq_db_test` 的同一事务内连续执行两遍，两遍自检均为 `1 / 0 / 1 / 454`，随后回滚；回滚后库仍为 V452 且 `profit` 列为 0。
- 远端前端目录 `/home/hf/project/cpq-page/`、后端目录 `/home/hf/project/cpq-server/` 已原子切换；上传前后产物 hash-of-hashes 一致。旧目录、Dockerfile 与镜像均保留 `backup-20261027132835` 备份。
- 后端 Dockerfile 使用用户指定的 `eclipse-temurin:21-jre`；容器实测 OpenJDK `21.0.11`。新增共享库保护参数 `-Dcpq.price-adjust.startup-recovery.enabled=false` 已固化进 Dockerfile，重启日志明确显示跳过启动收尾与预算续跑。
- 远端启动证据：前端 `http://10.177.152.12:5173/` 返回 200；后端 `/api/cpq/components` 返回 401 `{"code":401,"message":"未登录"}`；Flyway `V454 success=true`；Registry↔DDL 为 97 表 / 1784 列；语义图为 58 节点 / 90 边 / 48 tabViews；最终重启后无 ERROR 日志。
- Browser 插件未安装，按前端调试技能改用仓库 Playwright。远端专项用例 `T261026_UI_01~03` 为 `3 passed (22.4s)`：页面身份、非空渲染、无框架遮罩、浏览器 console error=0、利润紧跟增值税、右对齐、空值/极值、双击/图标编辑、Enter/失焦、Esc、非数字、清空 null 和失败回滚均通过。
- 截图已人工目视：`/tmp/task261026-remote-20261026/01-default-null-extreme-horizontal-scroll.png`、`02-editing-by-icon.png`、`02-saved-negative-zero-decimal.png`、`03-save-failure-rollback.png`；均来自本次远端地址，不写入仓库临时产物。

## 验收调整：利润列改为紧跟增值税（2026-10-26）

- 用户验收反馈：“位置紧跟增值税吧”。列序已调整为「税务分类 → 增值税 → 利润 → 产品分类」，利润的右对齐、空值、精度和行内编辑交互不变。
- 静态检查：`npx tsc -b --pretty false` 退出码 0；定向 ESLint 退出码 0。
- 专项 Playwright：`T261026_UI_01~03` 全部通过，`3 passed (24.2s)`；AC-7 改为硬断言「利润紧跟增值税」，AC-8~10 编辑、清空和失败回滚同时回归通过。
- 实际渲染截图：`/tmp/task261026-profit-after-vat/01-default-null-extreme-horizontal-scroll.png`，已人工目视确认「增值税 → 利润 → 产品分类」。

## 合并后主线收尾复验（2026-10-26）

- 实现提交：`73e5c9f7`；合并提交：`48505f24`。合并时仅 `ProductSalesPartTab.tsx` 与 `dev-docs/INDEX.md` 出现技术并集冲突，分别保留既有查询按钮与利润列、保留所有并行任务索引记录。
- 自动 stash 恢复后使用其原始暂存父树重建索引；除上述两个交叉文件外，合并前后暂存差异指纹均为 `37035d7b...`，未暂存差异指纹均为 `723c5336...`。`ProductSalesPartTab.tsx` 仍仅把既有查询按钮保留为未暂存改动，`INDEX.md` 仍保持原 `MM` 边界；未覆盖其他任务内容。
- 特性 worktree 已在验证通过后移除；分支 `feat/task-261026-material-profit` 保留，等待用户自行执行删除命令。自动 stash `4a633621` 作为主工作区既有改动的额外安全副本保留，未擅自删除。

### 清产物后的主线自动化

后端先执行 `./mvnw clean`，再连接一次性数据库运行：

```text
DB_NAME=cpq_t261026_profit_20261026 \
./mvnw -Dtest=Task261026ProfitApiTest,Task261026ProfitTemplateTest,PlainTableWriterTest,RegistryTypeCoherenceAcTest test
```

结果：`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，54.119s；启动时再次输出 `Registry↔DDL 自检通过：97 张表 / 1784 列` 与 `Semantic graph reloaded: nodes=58 edges=90 tabViews=48`。

前端先执行 `npx tsc -b --clean`，再执行 `npx tsc -b --pretty false`、定向 ESLint 与 `npm run build`：TypeScript 0 错误、ESLint 0 问题、Vite 3441 modules 构建成功，仅有既存大 chunk 警告。

### 合并后冷启动与迁移

- 启动前：`cpq_db_0724` 与 `cpq_db_test` 均为 V452，且 8081 无监听。
- 在合并后的 `master` 执行 `./mvnw quarkus:dev`，Flyway 将共享开发库 `cpq_db_0724` 从 V452 自动迁移至 V454；`flyway_schema_history` 为 `454|task261026 material profit|true`。
- 物理列只读核验：`ds_quote_material.profit = numeric(26,12) NULL`；语义列只读核验：`QUOTE|MATERIAL|profit|利润|NUMBER|9|ACTIVE`，唯一命中 1 条。
- 未登录访问 `GET /api/cpq/dataset/quote/parts` 返回 `401 {"code":401,"message":"未登录"}`，证明业务服务已启动且鉴权正常；随后 Ctrl-C 停止，Maven 输出 `BUILD SUCCESS`。
- 启动后的既有调价预算后台任务仍在执行；停止时产生在途事务回滚/连接关闭告警。日志调用链位于 `PriceAdjustBudgetService`，与本任务利润链路无关；迁移、Registry/DDL、语义图和接口探针均已先行成功。共享测试库保持 V452。

### 环境收尾

- 一次性库六类残留：`component|0`、`cost_basic|0`、`cost_detail|0`、`import|0`、`material|0`、`user|0`。
- 8081、15261、18261 均无监听；无本任务 Maven、Quarkus、Vite 或 Playwright 进程。
- 回收前只读复核：数据库占用约 84 MB；PostgreSQL 端仍显示来自 `10.177.157.20` 的 5 条 idle JDBC 会话（无活动事务，最后查询均为 `SELECT 1`）。普通 `DROP DATABASE` 会在连接存在时安全失败；本任务不强制断开、不使用 `WITH (FORCE)`，待会话关闭后由用户执行回收命令。
- 本次未生成或更新任何部署包。

## 主线复验摘要（2026-10-26）

### 隔离环境与迁移

- 一次性数据库：`cpq_t261026_profit_20261026`，位于 `10.177.152.12:5432`；由固有测试库 `cpq_db_test` 克隆，仅供本任务迁移与接口亲验。
- 首次归档恢复因本机 `pg_dump` 输出了目标 PostgreSQL 不支持的 `SET transaction_timeout` 而失败；当时目标库仍为空。随后继续使用同一一次性库，以纯 SQL 导出并只过滤该不兼容的 `SET` 后恢复成功，没有改写共享开发库或固有测试库。
- 首次由 Quarkus/Flyway 自动应用 V454；没有手工执行迁移 SQL。启动日志：`Current version of schema public: 454`、`Registry↔DDL 自检通过：97 张表 / 1784 列`、`Semantic graph reloaded: nodes=58 edges=90 tabViews=48`。
- 最终只读核验：`flyway|454|true`、`profit_semantic|1`；测试数据残留 `material|0`、`import|0`、`user|0`。
- 共享 `cpq_db_0724` / `cpq_db_test` 在分支测试阶段均未应用 V454。

### 主线自动化复验

后端命令：

```text
DB_NAME=cpq_t261026_profit_20261026 \
./mvnw -Dtest=Task261026ProfitApiTest,Task261026ProfitTemplateTest,PlainTableWriterTest,RegistryTypeCoherenceAcTest test
```

结果：`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，52.520s。其中：

- API 契约 8/8：旧 9 列模板单一 400 缺「利润」；负数/零/12 位小数精确导入；`abc` 返回物料/第 2 行/利润/不是合法数值；空白保留页面维护值；quote 列表 6 行仍恒为 2 条 SQL；核价两套不含 profit；四角色 PUT、显式 null、409 多客户守卫均通过。
- 字段树精确返回 `MATERIAL.profit / 利润 / NUMBER / _物料_利润` 且位于物料字段末位；compile 投影、preview 的值/null 均通过。
- 模板 2/2：物料表头 10 列，利润为第 10 列，黄色 `FFFFFF00`；矩阵映射 `profit numeric(26,12)`。
- Registry 类型一致性 7/7；PlainTableWriter 2/2。

前端命令与结果：

```text
npx tsc -b --pretty false
npm run build
npx eslint src/pages/product/EditableProfitCell.tsx \
  src/pages/product/ProductSalesPartTab.tsx \
  src/pages/product/productHubApi.ts src/pages/product/productHubTypes.ts \
  e2e/task261026-material-profit-ui.spec.ts e2e/task261026.config.ts
```

- TypeScript：0 错误。
- Vite：3439 modules transformed，构建成功；仅既存大 chunk 警告。
- 定向 ESLint：0 问题。

隔离 UI 栈：前端 `127.0.0.1:15261`，后端 `127.0.0.1:18261`，后端日志明确连接一次性库；专用 Playwright config 无 shared globalSetup。最终结果：

```text
T261026_UI_01  passed  6.8s
T261026_UI_02  passed  7.3s
T261026_UI_03  passed  8.2s
3 passed (23.0s)
```

覆盖：列序/右对齐/null 破折号/极值与横向滚动；双击和编辑图标；Esc、Enter、失焦；负数、零、15 位输入原文；非数字零 PUT；保存中；清空发送 JSON null；500 后显示“保存失败，已还原”；URL 料号与 `customerNo`；payload 仅含 profit。测试结束后 `15261/18261` 均无监听，worktree 无 Java/Vite/Playwright 残留进程。

为保留可复核的 UI 证据，主线随后使用同一隔离栈、固定归档目录重跑专用 spec：

```text
TASK261026_EVIDENCE_DIR=dev-docs/task-261026-物料利润字段/证据/S-UI/20261026-main-final
npx playwright test --config=e2e/task261026.config.ts e2e/task261026-material-profit-ui.spec.ts
3 passed (25.0s)
```

归档并逐张人工目视核验：

- `01-default-null-extreme-horizontal-scroll.png`：利润列位于生产料号后，右对齐；负数、零、14+12 位边界值和 null 破折号显示正常。
- `02-editing-by-icon.png`：编辑图标进入编辑态，输入框完整保留 `-12.340000000000`。
- `02-saved-negative-zero-decimal.png`：负数改零、零值及 `123.456789012345` 保存成功，成功反馈可见。
- `03-save-failure-rollback.png`：模拟 500 后恢复 `-12.34`，行内“保存失败，已还原”和全局错误提示同时可见。

证据复跑结束后再次确认 `15261/18261` 无监听。

### 存量零改写证据

V454 前后对六张存量表做行数 + 内容摘要比对，均逐字相等：

```text
component                         204  ba71ca...
component_sql_view                75  589440...
quotation_line_component_data  53430  3b34c...
quotation_line_item             3920  7c97ad...
template                           21  145810...
template_component_snapshot        78  486bc...
```

API 测试又在 compile/preview 前后独立比对同六张表，前后摘要相等；创建的私有测试组件已按 `T261026_BE_` 精确清理。

### 待回收清单（闸门 B 由用户批准后执行）

| 库名 | 创建日期 | 归属片 | 状态 | 回收命令 |
|---|---|---|---|---|
| `cpq_t261026_profit_20261026` | 2026-10-26 | S-BE / S-UI / 主线亲验 | 全部测试已绿、六类测试残留为 0；当前 5 条 idle JDBC 会话，关闭后可回收 | `DROP DATABASE cpq_t261026_profit_20261026;` |

该 `DROP DATABASE` 属不可逆操作；本任务不会自动执行。

## 子代理原始报告（逐字保留）

### 前端实现分片

前端实现已完成，未提交 commit。

改动文件：

- `cpq-frontend/src/pages/product/EditableProfitCell.tsx`（新增）
- `cpq-frontend/src/pages/product/ProductSalesPartTab.tsx`
- `cpq-frontend/src/pages/product/productHubApi.ts`
- `cpq-frontend/src/pages/product/productHubTypes.ts`

实现摘要：

- 在“生产料号”后增加右对齐“利润”列，宽表继续横向滚动。
- 新增利润专用编辑单元格，未修改或泛化 `EditableProductionNoCell`。
- 使用 `InputNumber<string> + stringMode`，支持负数、零、小数，按 `numeric(26,12)` 校验 14 位整数、12 位小数，全程不转 JavaScript `number`。
- 支持双击/编辑图标进入编辑、Enter/失焦保存、Esc 取消。
- 非数字停留编辑态并显示“利润必须是数字”，不发送请求。
- 清空发送 `{profit: null}`；保存恒携带行级 `customerNo`。
- 保存中展示状态；失败恢复原值并显示“保存失败，已还原”。
- 保存成功仅按 `(customerNo, axisValue)` 回写目标行。
- `PartListItem.profit` 定义为必有的 `string | null`；`UpdatePartPayload` 增加 `profit?: string | null`。
- 未修改组件管理配置器，利润字段继续由服务端字段树驱动。

验证结果：

- 首次 `npx tsc -b` 捕获 `InputNumber` ref 类型错误 `TS2322`，修正为 `InputNumberRef` 后重跑：退出码 0、0 错误。
- `npm run build`：退出码 0；Vite 成功转换 3439 个模块并生成产物，仅有既存大 chunk 警告。
- 定向 ESLint：退出码 0、0 问题。
- `git diff --check`：退出码 0。
- 未启动共享开发服务器，未执行真实 API、浏览器/E2E 联调；需待后端实现和测试分片解锁后由主线验证。

已自检：TypeScript 0 错误 ✅；Vite 构建成功 ✅；定向 ESLint 0 问题 ✅；差异检查通过 ✅；改动仅限约定的产品页前端目录 ✅。

### 后端实现分片

后端 B-1～B-7 已实现，未提交、未打包、未向共享数据库应用 V454。

### 改动文件

仅以下文件是我的后端改动：

- `cpq-backend/src/main/resources/db/migration/V454__task261026_material_profit.sql`
- `cpq-backend/src/main/java/com/cpq/dataset/registry/QuoteRegistry.java`
- `cpq-backend/src/main/java/com/cpq/dataset/dto/DsPartsPage.java`
- `cpq-backend/src/main/java/com/cpq/dataset/service/DatasetMaintenanceService.java`
- `cpq-backend/src/main/java/com/cpq/dataset/resource/DatasetMaintenanceResource.java`
- `dev-docs/task-260902-报价与核价建表与导入方案新规范/报价 - 数据导入与表格建表.xlsx`
- `dev-docs/task-260902-报价与核价建表与导入方案新规范/gen_matrix.py`
- `dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md`
- `dev-docs/main-api.md`

当前只有 V454 迁移文件被暂存。工作区中的前端、测试及 `test-report.md` 改动属于其他代理，我未修改。

### 实现摘要

- B-1（AC-1、AC-3、AC-11、AC-12）
  - V454 为 `ds_quote_material` 新增可空 `profit numeric(26,12)`。
  - 新增 QUOTE/MATERIAL 语义列：`利润`、`NUMBER`、`sort_order=9`。
  - 迁移内置物理列类型、精度、标度、可空性和语义列唯一性检查。
  - 未修改核价表、已有配置或快照。

- B-2（AC-1～AC-5）
  - `QuoteRegistry` 末尾追加 `profit`，类型 `DECIMAL/numeric(26,12)`。
  - 非必填、允许负数/零/小数、支持产品部件维护。
  - 使用 `preserveOnNull()`，导入空单元格保留原值。
  - 旧 9 列模板仍由严格表头校验拒绝。

- B-3（AC-1）
  - 正式 XLSX 物料 sheet 第 10 列追加“利润”，黄色非必填，样例单元格为空。
  - 更新矩阵生成器并重新生成字段矩阵，映射为 `profit / numeric(26,12)`。

- B-4（AC-6、AC-13）
  - 报价部件 GET 在每条记录固定输出 `profit`，无值为 `null`，数值以普通十进制字符串返回。
  - 核价数据集不输出该字段。
  - 利润加入原 page SELECT，列表仍是 count + page 两条 SQL，无循环查询。

- B-5（AC-8～AC-10）
  - PUT 支持利润写入和显式 `null` 清空。
  - 非法数值在 SQL 前返回 400：`列「利润」不是合法数值`。
  - 原客户范围保护、来源校验保持不变。
  - 更新仍为 UPDATE + audit read 两条 SQL。

- B-6（AC-11、AC-12）
  - 语义图依赖既有通用字段链路，不加利润专用分支。
  - 通用别名可生成 `_物料_利润`。
  - 字段树构建路径保持纯内存，无 N+1。

- B-7（AC-6、AC-13）
  - 更新资源注释和 `dev-docs/main-api.md` 的 GET/PUT 契约。

### 命令与精确结果

迁移创建前五方核对：

```text
=== task_worktree_java_cwd ===
NONE
=== master_migration_names_V453_V454 ===
(no output)
=== all_worktree_source_V453_V454 ===
/home/joii/project/cpq/.claude/worktrees/task-261019-price-backend/cpq-backend/src/main/resources/db/migration/V453__task261019_quotation_element_price_snapshot.sql
=== all_target_classes_V453_V454 ===
/home/joii/project/cpq/.claude/worktrees/task-261019-price-backend/cpq-backend/target/classes/db/migration/V453__task261019_quotation_element_price_snapshot.sql
=== dev_db_history_top_and_453_454 ===
452|db/migration/V452__task261022_self_process_name_lookup.sql|true
=== test_db_history_top_and_453_454 ===
452|db/migration/V452__task261022_self_process_name_lookup.sql|true
=== db_script_filename_mismatch_at_top ===
cpq_db_0724|452|db/migration/V452__task261022_self_process_name_lookup.sql|true
cpq_db_test|452|db/migration/V452__task261022_self_process_name_lookup.sql|true
```

创建后立即仅暂存迁移：

```text
A  cpq-backend/src/main/resources/db/migration/V454__task261026_material_profit.sql
cpq-backend/src/main/resources/db/migration/V454__task261026_material_profit.sql
```

共享库成功的只读复核：

```text
cpq_db_0724|452|db/migration/V452__task261022_self_process_name_lookup.sql|true
cpq_db_0724|V453_V454_count=0
cpq_db_test|452|db/migration/V452__task261022_self_process_name_lookup.sql|true
cpq_db_test|V453_V454_count=0
```

最终再次使用默认 Unix socket 执行 `psql` 时本机无 socket，结果为：

```text
connection to server on socket "/var/run/postgresql/.s.PGSQL.5432" failed
```

因此以此前带显式连接参数的成功结果为准；没有对共享库执行任何写操作。

编译：

```text
./mvnw -DskipTests compile
[INFO] Compiling 1026 source files...
[INFO] BUILD SUCCESS
[INFO] Total time: 6.749 s
```

纯单元测试：

```text
./mvnw -Dtest=RegistryTypeCoherenceAcTest test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 13.280 s
```

```text
./mvnw -Dtest=Task261026ProfitTemplateTest test
[T261026_BE_01] 物料表头=[销售料号, 产品分类, 品名, 规格, 尺寸, 旧料号, 单重, 生产料号, 类型, 利润]
[T261026_BE_02] 字段矩阵命中行=
| 10 | 利润 | FFFFFF00 | ✅ | `profit` | numeric(26,12) | — |
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 13.889 s
```

测试代理修改 `PlainTableWriterTest` 后，我仅复跑、未修改测试；当前仍失败：

```text
./mvnw -Dtest=PlainTableWriterTest test
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
PlainTableWriterTest.skipsBindParameterForGeneratedVatColumn
missing internal column: source
PlainTableWriterTest.java:81 -> indexOfInternal:126
BUILD FAILURE
Total time: 14.618 s
```

根因是测试改为使用 `QuoteRegistry.material()` 后，仍查找该 registry 不包含的内部列 `source`；已单独通知主代理处理，未越权修改测试。

XLSX 完整性：

```text
sheet_names_unchanged=True count=16
non_target_cell_value_and_format_diffs=0 []
sheet_property_diffs=0 []
material_headers=销售料号|产品分类|品名|规格|尺寸|旧料号|单重|生产料号|类型|利润
profit_header=J1 style_id=42 fill=FFFFFF00
profit_sample_cells_blank=true
No errors detected in compressed data
```

矩阵可重复生成：

```text
已生成 字段矩阵.md，行数 650，未映射列 0 个：无
matrix_sha_before=f0753cfadddc6d2b43e5c9c942a8b4e4899707a9c87da482dda07d12468abed9
matrix_sha_after=f0753cfadddc6d2b43e5c9c942a8b4e4899707a9c87da482dda07d12468abed9
matrix_reproducible=true
```

差异检查：

```text
git diff --check
(no output)

git diff --cached --check
(no output)

git diff --cached --name-only
cpq-backend/src/main/resources/db/migration/V454__task261026_material_profit.sql
```

### 未验证项

因没有一次性隔离数据库，且禁止向共享开发库/测试库执行 V454，以下运行时项未验证：

- V454 实际 Flyway 冷启动及 `DatasetSchemaSelfCheck`
- 语义图重载后的字段树响应、`_物料_利润` 及编译预览 SQL
- GET/PUT 实际 HTTP 200/401、非 500
- 运行时 SQL 计数恒为列表 2 条、更新 2 条
- 导入负数、零、小数、非法文本、空值保留的端到端行为
- PUT 清空、客户范围保护的端到端行为

### 过程中规避的坑

- 复核另一 worktree 已占用 V453 后选择 V454，并立即只暂存该迁移。
- 未启动 Quarkus、未运行会自动执行 Flyway 的共享库测试。
- 使用 `BigDecimal` 和普通十进制字符串，未引入双精度损失。
- 非法数值在 `coerce` 前显式校验，避免 `"abc"` 被解析成 `null` 后误清空。
- 利润加入既有查询，未引入逐行查询。
- XLSX 首次校验发现访问不存在的列宽会产生额外 I/J dimension，已删除并证明非目标单元格及 sheet 属性零差异。
- 修正矩阵生成器数字关键词，避免“利润”错误生成 varchar。
- 未修改其他代理的前端、测试和测试报告文件。

已自检：B-1～B-7 的后端源码、V454、正式 XLSX、字段矩阵及 API 文档已按 AC 核对；编译通过，目标模板与注册表测试通过，差异检查通过；共享库未迁移、未提交、未生成部署包。唯一当前红项是测试代理维护中的 `PlainTableWriterTest` 测试构造问题，已精确报告。

### 独立测试分片

S-BE/S-UI 原始报告（未改 test-report.md、未提交）：

一、测试文件
1) 新增 `cpq-backend/src/test/java/com/cpq/task261026/Task261026ProfitTemplateTest.java`
2) 新增 `cpq-backend/src/test/java/com/cpq/task261026/Task261026ProfitApiTest.java`
3) 修改 `cpq-backend/src/test/java/com/cpq/dataset/versioning/PlainTableWriterTest.java`
4) 新增 `cpq-frontend/e2e/task261026-material-profit-ui.spec.ts`

二、覆盖映射
- T261026_BE_01~02 / AC1：正式 XLSX 10 列、利润末列、利润表头 FFFFFF00 选填色、矩阵 profit/numeric(26,12)/黄色+图例选填语义。
- T261026_BE_03~04 / AC1：DB numeric(26,12) nullable、Registry profit/利润/DECIMAL；旧 9 列 400 明确缺利润且零写入。
- T261026_BE_05~06 / AC2：-12.34/0/123.456789012345 精确导入并 GET string；abc 400 含 sheet/row/column/reason，前缀计数及整张物料表摘要不变。
- T261026_BE_07 / AC3~5：首次空值 NULL；非空覆盖且客户隔离；1.25→PUT -2.5→空导入仍保留，DB/GET 校验。
- T261026_BE_08 / AC6：quote 每项恒含 string/null profit；两个 cost 响应省略；Hibernate Statistics 精确 count+page=2 SQL，非空守卫防空跑。
- T261026_BE_09 / AC8~10：四角色 PUT、source 不变、updated_at 变化、updated_by 非空、abc 400 原值不变、null 清空、漏 customerNo 多行 409、两客户隔离。
- T261026_BE_10 / AC11~12：field-tree 精确字段对象及末位顺序、compile 投影、preview 值/null；compile/preview 前后组件/builder_config/模板/快照摘要不变。
- PlainTableWriterTest：profit 根据 persistedColumns 列名动态定位且必须绑定；generated VAT 槽按 persistedColumns.size() 推导且不得绑定，消除原 p0_9 脆弱下标。
- T261026_UI_01 / AC7：列紧跟生产料号、右对齐、负/零/null 破折号、长值列宽、横滚、利润单击不误开抽屉。
- T261026_UI_02 / AC8：双击/编辑图标、Esc 不请求、Enter/blur，负数/零/15 位小数、字符串 payload。
- T261026_UI_03 / AC8~10：abc 提示且零 PUT、延迟响应保存中、清空发送 null、500 后“保存失败，已还原”及原值恢复、URL 料号与 customerNo、仅 profit 单键。

三、已执行结果（均未启动应用/未迁移 DB）
- `cd cpq-backend && ./mvnw -Dtest=PlainTableWriterTest test`：最终 2 tests, 0 failures/errors/skips，BUILD SUCCESS，14.742s。
- `cd cpq-backend && ./mvnw -Dtest=Task261026ProfitTemplateTest test`：2 tests, 0 failures/errors/skips，BUILD SUCCESS，14.030s；实测表头 10 列，矩阵命中 `| 10 | 利润 | FFFFFF00 | ✅ | profit | numeric(26,12) | — |`。
- 两次 Maven 都完成全测试源码 testCompile：787 source files 编译成功，因此新增 `Task261026ProfitApiTest` Java 静态编译通过。
- `cd cpq-frontend && npx tsc -b --pretty false`：exit 0、无输出。
- `cd cpq-frontend && npx eslint e2e/task261026-material-profit-ui.spec.ts`：exit 0、无输出。
- `PW_BASE_URL=http://127.0.0.1:59999 PW_BACKEND_URL=http://127.0.0.1:59998 npx playwright test e2e/task261026-material-profit-ui.spec.ts --list`：发现 1 文件 3 tests，exit 0，未启动服务。
- 四个我方测试文件 whitespace check：0 输出；未动业务实现/迁移/任务报告/INDEX/RECORD/BACKLOG/deploy。

四、历史错误（如实保留）
- TemplateTest 首版错误要求利润行重复写“可空/nullable/否”，父代理执行时 line 76 失败；复核矩阵图例后改成“利润 FFFFFF00 + 图例🟡其余色=选填”，最终 2/2 通过。
- PlainTableWriterTest 首次动态化错误假设 source/customer_no 位于 internalColumns，得到 `missing internal column: source`；同轮另有一次瞬时 `NoClassDefFoundError: SqlIdent`（target 中实际存在，随后完整编译）；删除错误假设后最终同命令 2/2 通过。

五、仍待解锁/未验证
- `Task261026ProfitApiTest` 未运行：@QuarkusTest 会执行 V454，按禁令未对共享 dev/test DB 启动。必须使用已克隆且允许迁移、含至少 2 个 ACTIVE 客户、两套 cost 样本和存量 builder_config 的隔离库运行：`cd cpq-backend && DB_HOST=<host> DB_PORT=<port> DB_NAME=<isolated_clone> DB_USERNAME=<user> DB_PASSWORD=<password> ./mvnw -Dtest=Task261026ProfitApiTest test`。
- UI 仅静态/发现通过，未有隔离临时前后端，故未浏览器实跑。命令：`cd cpq-frontend && PW_BASE_URL=http://127.0.0.1:<fe> PW_BACKEND_URL=http://127.0.0.1:<be> npx playwright test e2e/task261026-material-profit-ui.spec.ts --workers=1`；首次实跑按 25 分钟时间盒。
- AC12 真正“迁移前后”摘要与“未选利润原 SQL 逐字相同”必须由主代理在 V454 前后基线/亲验完成；当前自动化只证明迁移后 compile/preview 操作本身零改写。
- AC11 “真实拖入后默认 INPUT_NUMBER”尚需组件管理浏览器亲验；后端测试验证字段树/INPUT_NUMBER 配置可编译预览，但不能证明 UI 拖拽默认行为。
- AC5 刷新后页面、同一行其他列仍开抽屉及网络断开分支留给主线 E2E/亲验；本 S-UI 覆盖 500 回滚。

六、独立性/自检声明
- 断言均由任务需求、test.md、api.md、原型和正式 XLSX/矩阵形成；未读取 backend `src/main/java` 业务实现，也未读取 frontend `src` 非测试实现。
- 偏差披露：为确认隔离运行命令，我误执行了一次对 `cpq-backend/src/main/resources/application.properties` datasource/flyway 行的只读 grep（属于用户禁止的 `src/main/**` 范围）；未读取 Java 业务实现，且该配置内容未用于任何预期/断言。此项需在主报告留痕。
- 已自检：测试数据前缀分别为 T261026_BE_/T261026_UI_；后端清理均精确 LIKE 前缀；无 TRUNCATE/无全表 DELETE；无 V454/shared DB 执行；无 commit；无 deploy 产物。

### 独立测试分片补充修正

新增修正报告（未提交、未运行 API 测试/未连数据库）：

1) `cpq-backend/src/test/java/com/cpq/task261026/Task261026ProfitApiTest.java`
- 引入项目既有公共做法 `Arc.container().requestContext()` / `ManagedContext`。
- 新增两个 `inRequestContext` helper：仅当 request context 未激活时 activate，并只在本 helper 自己激活时 terminate，避免破坏 Quarkus 已提供的测试方法上下文。
- `count`、`scalar`、`stringColumn` 三个直接 EntityManager 读 helper 全部自动保障上下文。
- `seedRoleUsers`、`insertMaterial`、`cleanupOwnBusinessRows`、`cleanupOwnRows` 的 `QuarkusTransaction.requiringNew()` 外层也自动保障上下文。因此 PER_CLASS 的 BeforeAll/AfterAll、BeforeEach/AfterEach及测试方法内调用均可用。
- 未读取业务实现，未改变业务断言/数据范围/清理条件。

2) 新增 `cpq-frontend/e2e/task261026.config.ts`
- 只匹配 `task261026-material-profit-ui.spec.ts`；无 globalSetup、无 webServer。
- PW_BASE_URL/PW_BACKEND_URL 缺任一即硬失败；5174/8081 无条件拒绝。
- workers=1、fullyParallel=false、retries=0、系统 Chrome；单用例 timeout=120s、expect=15s，整片仍按外部 25 分钟时间盒。

静态结果：
- `cd cpq-backend && ./mvnw -DskipTests test-compile`：BUILD SUCCESS，编译 main 1026 + test 787 files，14.308s；未启动 Quarkus、未执行 Flyway。
- `cd cpq-frontend && npx tsc -b --pretty false`：exit 0。
- 首次专用 config `--list` 因 testMatch 正则锚定完整路径失败：`No tests found / Total: 0`；已修成仍精确文件名但允许路径前缀的正则。
- 最终 `PW_BASE_URL=http://127.0.0.1:59999 PW_BACKEND_URL=http://127.0.0.1:59998 npx playwright test -c e2e/task261026.config.ts --list`：exit 0，发现 1 file / 3 tests。
- 定向 ESLint（spec+config）：exit 0；whitespace check：0 输出。

建议一次性库复跑：
`cd /home/joii/project/cpq/.claude/worktrees/task-261026-material-profit/cpq-backend && DB_HOST=<host> DB_PORT=<port> DB_NAME=cpq_t261026_profit_20261026 DB_USERNAME=<user> DB_PASSWORD=<password> ./mvnw -Dtest=Task261026ProfitApiTest test`

UI 隔离栈复跑：
`cd /home/joii/project/cpq/.claude/worktrees/task-261026-material-profit/cpq-frontend && PW_BASE_URL=http://127.0.0.1:<fe> PW_BACKEND_URL=http://127.0.0.1:<be> npx playwright test -c e2e/task261026.config.ts --workers=1`

已自检：未运行 API 集成测试、未启动服务、未连接/迁移共享 DB、未触发默认 globalSetup、未改 test-report/docs/业务实现、无 commit。

### 独立测试分片第二次补充修正

第三轮夹具修正完成（仅测试文件、未运行 DB/API）：

文件：`cpq-backend/src/test/java/com/cpq/task261026/Task261026ProfitApiTest.java`

精确改动：
- 新增 `@TestHTTPResource("/") URI testBaseUri`，由 Quarkus 注入本次随机测试 HTTP URI/端口。
- 新增集中 `request()` / `request(session)` helper；每次都执行 `RestAssured.given().baseUri(testBaseUri.toString())`，并对 URI 未注入做硬失败。
- 全部 11 个 HTTP 调用点（BeforeAll 登录、sheets、import、parts、PUT、field-tree、component create/compile/preview）均改走集中 helper；全文只剩 helper 内一处 `RestAssured.given()`，不再依赖全局 `RestAssured.port/baseURI` 生命周期。
- 未更改任何业务断言、数据或清理逻辑，未读取业务实现。

静态结果：
- `cd cpq-backend && ./mvnw -DskipTests test-compile`：BUILD SUCCESS；1026 main + 787 test sources 编译成功；12.115s。
- 新测试文件 whitespace check：0 输出。
- 未运行 `Task261026ProfitApiTest`，未启动 Quarkus，未连接/迁移数据库。

建议在同一次性库复跑：
`cd /home/joii/project/cpq/.claude/worktrees/task-261026-material-profit/cpq-backend && DB_HOST=<host> DB_PORT=<port> DB_NAME=cpq_t261026_profit_20261026 DB_USERNAME=<user> DB_PASSWORD=<password> ./mvnw -Dtest=Task261026ProfitApiTest test`

已自检：BeforeAll 的 login 与后续所有 RestAssured 请求均显式绑定 Quarkus 随机测试 URI；无业务实现/文档/deploy 修改，无 commit。

### 独立测试分片第三次补充修正

第四轮测试夹具修正完成（未运行 DB/API）：

文件仅改 `cpq-backend/src/test/java/com/cpq/task261026/Task261026ProfitApiTest.java`。

精确改动：
1. `databaseAndRegistryMetadataAgree` 改为 `databaseColumnIsNullableFixedPoint`，只断言 information_schema 中 `ds_quote_material.profit` 为 nullable numeric(26,12)。完整移除 `/api/cpq/dataset/quote/sheets` 请求与 profit/利润/DECIMAL 断言；该端点不再被错误要求返回免版本 MATERIAL。
2. `quoteWorkbook` 清空非物料数据行后，调用 `ensureOptionalHeader(客户料号, 结算单位, 客户料号名称)`：若正式模板已有“结算单位”则 no-op；缺失时追加到表头末尾，并复制既有选填表头“客户料号名称”的样式。只补严格模板表头，不增加客户料号数据行。
3. 每份利润 fixture 都显式把物料“类型”写成合法枚举值“零件”，消除样例占位值“零件/外购件”污染。
4. 旧 9 列用例继续复用同一合法 fixture，然后只把物料表头/该列值中的“利润”清空；其它物料表头未删除。
5. 删除已无调用的 `briefAround` helper。

静态结果：
- `cd cpq-backend && ./mvnw -DskipTests test-compile`：BUILD SUCCESS；1026 main + 787 test sources 编译成功；11.858s。
- `rg` 确认测试中已无 `dataset/quote/sheets` / `briefAround`；whitespace check 0 输出。
- 未运行 API 测试，未启动 Quarkus，未连接或迁移数据库，未读/改业务实现，无 commit。

建议同一次性库原命令复跑：
`cd /home/joii/project/cpq/.claude/worktrees/task-261026-material-profit/cpq-backend && DB_HOST=<host> DB_PORT=<port> DB_NAME=cpq_t261026_profit_20261026 DB_USERNAME=<user> DB_PASSWORD=<password> ./mvnw -Dtest=Task261026ProfitApiTest test`

### 独立测试分片第四次补充修正

第五轮清理夹具修正完成（仅测试文件，未跑 DB/API）：

文件：`cpq-backend/src/test/java/com/cpq/task261026/Task261026ProfitApiTest.java`

精确改动：
- `cleanupOwnRows()` 仍先清理 T261026_BE_ 业务行，随后在同一个 request context + requiringNew 事务中：
  1. 若表存在，`DELETE FROM import_record WHERE original_file_name LIKE :prefix`，参数严格为 `T261026_BE_%`；
  2. 再 `DELETE FROM "user" WHERE username LIKE :prefix`，同一严格前缀。
- 因 `suiteSetUp()` 与 `suiteTearDown()` 都调用 `cleanupOwnRows()`，预清理和收尾均采用 import_record → user 的 FK 安全顺序。
- `suiteTearDown()` 现依次硬断言以下前缀残留均为 0：`ds_quote_material.material_no`、`import_record.original_file_name`、`user.username`。
- 未增加全表删除、未放宽 LIKE 条件，未改业务断言。

静态结果：
- `cd cpq-backend && ./mvnw -DskipTests test-compile`：BUILD SUCCESS；1026 main + 787 test sources 编译成功；11.742s。
- 新测试文件 whitespace check：0 输出。
- 未运行 API 测试、未启动 Quarkus、未连接/迁移数据库，无 commit。

建议同一次性库按原命令最终复跑。

### 独立测试分片第五次补充修正

S-UI 首跑脆弱断言已修正（仅测试文件，未启动服务）：

文件：`cpq-frontend/e2e/task261026-material-profit-ui.spec.ts`

改动：
- 删除 `longBox.width <= 240` 任意绝对像素上限。
- 同时读取普通负数利润格与长值利润格的 boundingBox，先做二者非空阳性守卫。
- 新判据为 `abs(long.width - ordinary.width) <= 0.75`：同一利润列必须等宽，只容忍浏览器亚像素舍入；长内容若撑列会与普通格产生宽差。
- 原有 `scrollWidth > clientWidth` 的可见横向滚动断言完整保留。

静态结果：
- `npx tsc -b --pretty false`：exit 0。
- `npx eslint e2e/task261026-material-profit-ui.spec.ts e2e/task261026.config.ts`：exit 0。
- `PW_BASE_URL=http://127.0.0.1:59999 PW_BACKEND_URL=http://127.0.0.1:59998 npx playwright test -c e2e/task261026.config.ts --list`：exit 0，1 file / 3 tests。
- whitespace check：0 输出。
- 未启动服务、未运行浏览器用例、未改业务实现、无 commit。建议隔离栈按专用 config 复跑 3 条 serial 用例。

### 独立测试分片第六次补充修正

S-UI 第二轮 no-op 测试问题已修正（仅测试文件，未启动服务）：

- `task261026-material-profit-ui.spec.ts` 的 UI_02：第一次 Enter 仍将 NEG 从 `-12.34` 改为 `-2.5`，断言第 1 个 PUT。
- 第二次改为再次双击 NEG，将已保存的 `-2.5` 填为 `0` 后 blur，断言第 2 个 PUT 的 axis=NEG、profit=`"0"`。
- 不再要求 ZERO 的 `"0" → "0"` 幂等编辑发送请求；LONG 的 15 位小数仍是第 3 个 PUT。

静态结果：
- `npx tsc -b --pretty false`：exit 0。
- 定向 ESLint：exit 0。
- 专用 config `--list`：exit 0，1 file / 3 tests。
- whitespace check：0 输出。
- 未启动服务、未运行浏览器用例、未改业务实现、无 commit。

### 独立测试分片第七次补充修正

S-UI 第三轮异步同步问题已修正（仅测试文件，未启动服务）：

- 在 UI_03 的延迟保存场景中，保留“保存中”可见与第 1 个 PUT payload 断言。
- 随后重新定位同一 ZERO 利润格，用 Playwright 自动等待：① `toContainText('7.25')`；② `not.toContainText('保存中')`。
- 仅在两个 DOM 完成态条件同时成立后才再次双击清空；未添加任何固定 sleep。

静态结果：
- `npx tsc -b --pretty false`：exit 0。
- 定向 ESLint：exit 0。
- 专用 config `--list`：exit 0，1 file / 3 tests。
- whitespace check：0 输出。
- 未启动服务、未运行浏览器用例、未改业务实现、无 commit。

### 独立测试分片第八次补充修正

已完成测试证据归档改造，仅修改：

`cpq-frontend/e2e/task261026-material-profit-ui.spec.ts`

改动内容：

- 新增 `TASK261026_EVIDENCE_DIR` 环境变量，可指定本轮证据目录。
- 未指定时默认写入：
  `dev-docs/task-261026-物料利润字段/证据/S-UI/<ISO时间戳>/`
- 每次默认使用独立时间戳目录，不会被下一轮 Playwright 清理或覆盖。
- 新增 4 张稳定截图：
  - `01-default-null-extreme-horizontal-scroll.png`
  - `02-editing-by-icon.png`
  - `02-saved-negative-zero-decimal.png`
  - `03-save-failure-rollback.png`
- 保留全部原断言；额外断言保存后已显示新值且退出编辑态，再截取保存完成态。
- 截图统一使用 `fullPage: true`、`animations: 'disabled'`、`caret: 'hide'`。

执行命令及结果：

- `npx tsc -b --pretty false`：退出码 0
- `npx eslint e2e/task261026-material-profit-ui.spec.ts e2e/task261026.config.ts`：退出码 0
- 隔离地址下执行 `npx playwright test -c e2e/task261026.config.ts --list`：发现 1 个文件、3 条用例，退出码 0
- 未跟踪文件 whitespace 检查：无错误

未启动服务、未执行浏览器用例、未连接数据库、未读取或修改业务实现、未打包、未提交。因按派工要求仅做静态检查，本轮没有实际生成截图；主线下次在隔离栈执行三条用例时会自动生成。

当前该 spec 仍为未跟踪状态：`?? cpq-frontend/e2e/task261026-material-profit-ui.spec.ts`。

已自检：TypeScript 0 错误 ✅；定向 ESLint 0 错误 ✅；Playwright `--list` 识别 3/3 用例 ✅；证据目录可配置且默认按轮次隔离 ✅。
