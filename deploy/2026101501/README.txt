CPQ 前后端独立部署包

构建基线：master `58745b6a`（已发布模板软删除任务验收通过）
实现合并：master `0c78a2c8`
构建日期：2026-10-15

目录内容：
- `dist/`：前端 Vite 静态文件
- `quarkus-app/`：后端 Quarkus fast-jar
- `update-261013-template-customer-many.sql`：V450 数据库增量脚本（创建 `template_customer`）
- `update-261015-template-soft-delete.sql`：V451 数据库增量脚本

数据库升级：
1. 生产库执行前先备份，并确认目标库为 PostgreSQL 16+。
2. **必须先执行** `update-261013-template-customer-many.sql`（V450），创建 `template_customer` 并完成历史客户回填。
3. 再执行 `update-261015-template-soft-delete.sql`（V451）；两个脚本均可重复执行。
4. 自检：`template_customer` 表存在，且 `chk_template_status` 定义中包含 `DELETED`。

故障修复：如果已经只执行过 V451、启动时报 `relation "template_customer" does not exist`，现在补执行 V450 脚本即可；不要删除数据，也不要回滚应用包。

后端启动：

```bash
java -jar quarkus-app/quarkus-run.jar
```

启动前请按生产环境配置数据库、Redis、加密密钥和 `QUARKUS_HTTP_PORT` 等环境变量。

前端部署：

将 `dist/` 交给 Nginx 等静态服务器，并配置：

1. `/api` 反向代理到后端；
2. 未命中静态文件的路径回退到 `dist/index.html`（SPA fallback）。

构建自检：

- 前端 `npm run build`：通过
- 后端 `./mvnw -B -ntp -DskipTests clean package`：通过
- fast-jar 四件套 `quarkus-run.jar`、`lib/`、`app/`、`quarkus/`：齐全
