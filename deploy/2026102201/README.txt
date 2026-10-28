CPQ 前后端独立部署包
====================

构建日期：2026-10-22
代码分支：master
代码基线：HEAD 4224d41881aee542956472e7a385be35d4a92da4
说明：产物取当前 master 工作区源码构建，包含本次列表查询按钮旧状态修复及用户已验收的现有工作区改动。

目录内容：
- dist/：前端 Vite 静态文件
- quarkus-app/：后端 Quarkus fast-jar
- 本包不含数据库增量 SQL：本次改动未新增或修改数据库迁移。

数据库基线与执行顺序：
1. 上次部署对应的最新 SQL 基线为 V451。
2. 本次部署没有 V452 或更高版本增量，已达到 V451 的目标库无需执行 SQL。
3. 若目标库来自全量初始化脚本，按 `deploy/db/README.md` 顺序执行：
   `cpq-init-empty.sql`（V439）→ V440~V443（`update-260913-v440-v443-record-batch-and-price.sql`）
   → V444（`update-260916-lookup-name-recipe-fallback.sql`）
   → V445（`update-260916-element-price-scale9.sql`）
   → V446/V447/V449（`update-261013-v446-v449-material-config.sql`）
   → V448（`update-261013-v448-unit-management.sql`）
   → V450（`update-261013-template-customer-many.sql`）
   → V451（`update-261015-template-soft-delete.sql`）。
4. 执行前请备份数据库；执行后确认 Flyway 基线为 V451。

后端部署：
```bash
java -jar quarkus-app/quarkus-run.jar
```

启动前配置数据库、Redis、加密密钥和 `QUARKUS_HTTP_PORT` 等环境变量。不要依赖应用配置中的开发库默认值。

前端部署：
将 `dist/` 交给 Nginx 等静态服务器，并配置：
1. `/api` 反向代理到后端；
2. 未命中静态文件的路径回退到 `dist/index.html`（SPA fallback）。

构建与包自检：
- 前端 `npm run build`：通过（`tsc -b` + Vite build）
- 后端 `./mvnw -B -ntp -DskipTests clean package`：通过
- fast-jar 四件套 `quarkus-run.jar`、`lib/`、`app/`、`quarkus/`：齐全
- 前端静态资源目录约 25M，后端 fast-jar 目录约 82M
- 未生成新的部署 SQL；数据库基线仍为 V451
