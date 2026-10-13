# 测试与验收记录

## 已执行

### 后端编译

```text
./mvnw -DskipTests compile
BUILD SUCCESS
```

编译通过，包含 1022 个后端源文件。

### 元素服务回归

在 `cpq_task_261012_import_clone` 数据库执行 `ElementServiceTest`：

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 导入/导出 API 验收

在 `cpq_task_261012_import_clone` 数据库执行：

```text
DB_HOST=10.177.152.12 DB_NAME=cpq_task_261012_import_clone \
DB_USERNAME=postgres DB_PASSWORD=*** \
QUARKUS_FLYWAY_VALIDATE_ON_MIGRATE=false \
./mvnw -Dtest=MaterialElementTemplateApiTest \
-Dcpq.dataset.schema-check.enabled=false test

Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：元素模板两列表头及一行示例、材质模板四列表头及一行示例、元素四列只读导出、元素同符号异名冲突整批拒收及零写入、材质缺失元素整批拒收及零写入。测试使用 `/auth/login` 后校验 `/auth/me` 的 `SYSTEM_ADMIN` 会话。

### 前端构建

```text
npm run build
✓ built in 1.16s
```

构建通过。新增加的 `ElementImportDrawer.tsx`、`MaterialImportDrawer.tsx`、`elementService.ts` 定向 ESLint 通过；全仓库 `npm run lint` 仍受既有 3,827 个 lint error 阻塞，未将既有问题归因于本任务。

### 文件检查

```text
git diff --check
```

已在功能工作树执行，无空白错误。

## 测试环境说明

- 共享 `cpq_db_test` 已存在未随当前代码提供的 Flyway V448，因此验收使用从该库克隆的临时数据库并关闭 Flyway 本地未解析迁移校验；V449 已成功应用，当前 schema version 为 449。
- 克隆库存在与本任务无关的历史数据集注册表漂移，API 测试使用 `-Dcpq.dataset.schema-check.enabled=false`，仅绕过该环境自检，不改变生产默认配置。
- 临时数据库 `cpq_task_261012_import`、`cpq_task_261012_import_clone` 尚未删除，避免未经用户确认执行不可逆数据库操作。

## 尚未执行

- 浏览器端 Playwright 交互验收未执行；现有 UI 测试骨架保持跳过，不能作为通过证据。
- 全量后端回归未执行；已完成编译、元素服务定向回归和本任务 API 定向验收。
