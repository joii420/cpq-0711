CPQ 前后端独立部署包
====================

构建日期：2026-10-23
代码分支：master
代码基线：cb90d949893859ba44def7d364598df1df92e0a9
上次部署包：deploy/2026102201（数据库最新基线 V451）
本次目标数据库基线：V452

本包内容：
- dist/：前端 Vite 生产静态文件
- quarkus-app/：后端 Quarkus fast-jar
- update-261023-self-process-name-lookup.sql：V451 → V452 数据库增量

本次功能：
- 组件管理 → 取数配置 → 自制加工费，新增可选字段“工序名称”。
- 字段位于“工序编号”之后，通过 LEFT JOIN process_master 按 operation_no = process_no 查询 process_name。

数据库升级顺序（必须先执行）：
1. 执行前备份目标数据库，并确认当前部署 SQL 基线为 V451。
2. 执行 update-261023-self-process-name-lookup.sql，将基线升级到 V452。
3. 脚本末尾三个自检结果应分别为：
   - process_name_lookup_check_expected_1 = 1
   - self_process_lookup_edge_check_expected_1 = 1
   - flyway_baseline_expected_452 = 452
4. 脚本可重复执行；若目标库低于 V451，须先按 deploy/db/README.md 的顺序补齐缺失增量至 V451，再执行本包 V452，禁止跳版本。

后端部署：
```bash
java -jar quarkus-app/quarkus-run.jar
```

启动前配置数据库、Redis、加密密钥和 QUARKUS_HTTP_PORT 等环境变量。不要依赖应用配置中的开发库默认值。
启动恢复默认开启，生产环境须确保同一数据库同一时刻仅有一个后端实例执行启动恢复。仅做部署包冒烟检查时，
必须显式指定隔离数据库，并增加以下 JVM 参数，避免恢复任务或定时任务处理业务数据：
```bash
DB_HOST=<数据库地址> DB_PORT=<端口> DB_NAME=<隔离库名> \
DB_USERNAME=<用户名> DB_PASSWORD=<密码> \
java -Dcpq.price-adjust.startup-recovery.enabled=false \
     -Dquarkus.scheduler.enabled=false \
     -Dquarkus.http.port=18123 \
     -jar quarkus-app/quarkus-run.jar
```

前端部署：
将 dist/ 交给 Nginx 等静态服务器，并配置：
1. /api 反向代理到后端；
2. 未命中静态文件的路径回退到 dist/index.html（SPA fallback）。

构建与包自检：
- 前端 npm run build：通过（tsc -b + Vite build）
- 后端 ./mvnw -B -ntp -DskipTests clean package：BUILD SUCCESS
- fast-jar 四件套 quarkus-run.jar、lib/、app/、quarkus/：齐全
- V452 增量：从 V439 全量脚本按顺序升至 V452成功；连续执行两遍均 exit 0、stderr 0 字节
- V452 种子与 cpq_db_0724 对比：节点、字段、边、连接键逐项一致
- Navicat 静态检查：10 个 SQL 片段、非法开头 0、单引号闭合、无 $$、无 psql 元命令
- 成品冒烟：隔离临时库（V452）启动 prod profile 成功；前端首页 HTTP 200，后端未登录业务接口 HTTP 401；临时实例均已停止

SQL 文件 md5：
- V452：b2c8b118f6eedea6b12db3484a41651e
