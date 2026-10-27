CPQ 前后端测试环境部署包
========================

构建日期：2026-10-26
代码分支：master
代码基线：b54b62a65ec01291312036959af1bed9af613000
功能合并基线：58fded62（利润列紧跟增值税）
上次部署包：deploy/2026102301（数据库最新基线 V452）
本次目标数据库基线：V454

本包内容：
- dist/：前端 Vite 生产静态文件
- quarkus-app/：后端 Quarkus fast-jar（Java 21）
- Dockerfile.backend：测试服务器后端镜像定义与全部 JVM / Quarkus 启动参数
- update-261026-material-profit.sql：V452 → V454 数据库增量

本次功能：
- 报价基础数据导入模板“物料”Sheet 增加可空“利润”，非空时做数字校验。
- 导入后作为销售产品基础属性，可在产品列表展示和维护。
- 产品列表“利润”列紧跟“增值税”。
- 组件管理的物料引用字段列表增加“利润”。

数据库升级顺序（应用启动前）：
1. 备份目标数据库，并确认上次部署 SQL 基线为 V452。
2. 执行 update-261026-material-profit.sql，将基线升级到 V454。
3. 脚本末尾四个自检结果必须依次为 1、0、1、454。
4. V453 不属于本代码基线，不执行任何未合并的 V453 脚本；后续相关迁移合并前必须改号到 V454 以上。
5. 本测试服务器当前使用的 cpq_db_0724 已由 Flyway应用 V454，因此本次远端部署只核验 V454 success=true，不重复执行手工增量。

前端部署：
1. 目录：/home/hf/project/cpq-page/
2. 将本包 dist/ 原子替换远端 dist/，保留原目录备份。
3. 使用远端既有 Dockerfile / nginx.conf 构建 cpq-page-image:5173。
4. 启动容器 cpq-page-5173，端口映射 5173:80。

后端部署：
1. 目录：/home/hf/project/cpq-server/
2. 将本包 quarkus-app/ 原子替换远端 quarkus-app/，保留原目录备份。
3. 用本包 Dockerfile.backend 替换远端 Dockerfile（先保留旧文件备份）。
4. 构建 cpq-server-image:1.0，启动容器 cpq-server-1.0，host 网络监听 8201。
5. Dockerfile 使用 Java 21；MaxRAMPercentage=75，匹配容器 4096 MiB 内存上限；profile 保持远端既有 webtest。
6. 测试服务器与其他开发实例共用数据库，Dockerfile 固定设置 `-Dcpq.price-adjust.startup-recovery.enabled=false`，避免启动时误收尾其他实例的任务或自动续跑预算。

验收地址：
- 前端：http://10.177.152.12:5173
- 后端未登录业务探针：http://10.177.152.12:8201/api/cpq/components（期望 HTTP 401）

构建与包自检：
- 前端 npm run build：通过；3439 modules transformed
- 后端 ./mvnw -B -ntp -DskipTests clean package：BUILD SUCCESS
- fast-jar 四件套 quarkus-run.jar、lib/、app/、quarkus/：齐全
- 产物来源：干净 detached worktree，精确位于代码基线 b54b62a6
- update-261026-material-profit.sql 在 cpq_db_test 的同一事务内连续执行两遍，两遍自检均为 1 / 0 / 1 / 454，随后回滚并确认测试库仍为 V452、profit 列为 0；无 psql 元命令、无美元引用
- 远端启动：前端 HTTP 200；后端未登录业务端点 HTTP 401；OpenJDK 21.0.11；V454 success=true；最终重启后无 ERROR 日志
- 远端 Playwright：T261026_UI_01~03，3 passed (22.4s)

SQL 文件 md5：
- V454：d7a2ab369cdb73485e36ca1882bb4b5f
