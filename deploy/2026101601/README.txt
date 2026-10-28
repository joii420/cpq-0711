CPQ 前后端独立部署包
====================

构建日期：2026-10-16
代码基线：master 7c1cc5ee4329579e4f8d7fe8d8d8337c0d06decd
说明：本包按用户明确的打包请求生成，构建内容取当前工作区源码（包含当时已有的工作区改动）。

目录内容：
- dist/：前端 Vite 静态文件
- quarkus-app/：后端 Quarkus fast-jar
- update-261013-template-customer-many.sql：V450，模板客户多关系表
- update-261015-template-soft-delete.sql：V451，模板 DELETED 状态

数据库升级顺序（必须按顺序）：
1. 生产库执行前先备份，并确认目标库为 PostgreSQL 16+。
2. 先执行 `update-261013-template-customer-many.sql`（V450），创建 `template_customer` 并回填历史客户关系。
3. 再执行 `update-261015-template-soft-delete.sql`（V451），将模板状态约束扩展为 `DELETED`。
4. 执行完成后确认 `template_customer` 存在，并确认 `chk_template_status` 定义包含 `DELETED`。
5. 两个脚本均设计为可重复执行；不要跳过 V450 直接执行 V451。

后端部署：
```bash
java -jar quarkus-app/quarkus-run.jar
```

启动前配置数据库、Redis、加密密钥和 `QUARKUS_HTTP_PORT` 等环境变量。

前端部署：
将 `dist/` 交给 Nginx 等静态服务器，并配置：
1. `/api` 反向代理到后端；
2. 未命中静态文件的路径回退到 `dist/index.html`（SPA fallback）。

构建自检：
- 前端 `npm run build`：通过
- 后端 `./mvnw -B -ntp -DskipTests clean package`：通过
- fast-jar 四件套 `quarkus-run.jar`、`lib/`、`app/`、`quarkus/`：齐全

SQL 文件 md5：
- V450：`3fcbdbf75248c1b5065941521e6df963`
- V451：`f058c72190efd31906d183497daccec6`
