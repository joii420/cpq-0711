CPQ 前后端独立部署包

构建基线：master `34eff3f2`（模板多客户任务已合并并通过用户验收）
构建日期：2026-10-14

目录内容：
- `dist/`：前端 Vite 静态文件
- `quarkus-app/`：后端 Quarkus fast-jar
- `update-261013-template-customer-many.sql`：模板多客户生产数据库增量脚本

后端启动：

```bash
java -jar quarkus-app/quarkus-run.jar
```

启动前请按生产环境配置数据库、Redis、加密密钥和 `QUARKUS_HTTP_PORT` 等环境变量。
数据库增量脚本应在目标库备份并完成预检后执行；脚本支持幂等重复执行。该脚本未在生产库执行。

前端部署：

将 `dist/` 交给 Nginx 等静态服务器，并配置：

1. `/api` 反向代理到后端；
2. 未命中静态文件的路径回退到 `dist/index.html`（SPA fallback）。

构建自检：

- 前端 `npm run build`：通过
- 后端 `./mvnw -B -ntp -DskipTests clean package`：通过
- fast-jar 四件套 `quarkus-run.jar`、`lib/`、`app/`、`quarkus/`：齐全
