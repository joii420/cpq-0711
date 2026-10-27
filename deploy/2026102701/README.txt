CPQ 前后端测试环境部署包
========================

构建日期：2026-10-27
代码分支：master
代码基线：cb361d081b6e25c3c3ce0bc4db99a80c4a8442a9
功能合并基线：cb361d08（报价单产品卡片利润快照）
上次部署包：deploy/2026102601（数据库最新基线 V454）
本次目标数据库基线：V455

本包内容：
- dist/：前端 Vite 生产静态文件
- quarkus-app/：后端 Quarkus fast-jar（Java 21）
- Dockerfile.backend：测试服务器后端镜像定义与全部 JVM / Quarkus 启动参数
- update-261027-quotation-profit-snapshot.sql：V454 → V455 数据库增量

本次功能：
- 新建报价单时把销售产品利润冻结到报价行；保存、刷新和重算不跟随基础数据漂移。
- 报价单编辑态与只读态产品卡片在 VAT 提示框右侧显示“利润: xx”。
- 产品未维护利润或历史报价行无利润快照时显示“利润: -”。
- 利润仅作展示，不参与价格计算或导出。

数据库升级顺序（应用启动前）：
1. 备份目标数据库，并确认上次部署 SQL 基线为 V454。
2. 执行 update-261027-quotation-profit-snapshot.sql，将基线升级到 V455。
3. 脚本末尾四个自检结果必须依次为 1、0、0、455。
4. 本测试服务器 cpq_db_0724 由 Flyway 在后端冷启动时自动应用 V455，不重复手工执行本增量。

前端部署：
1. 目录：/home/hf/project/cpq-page/
2. 将本包 dist/ 上传为临时目录，校验 hash 后原子替换远端 dist/，保留原目录备份。
3. 使用远端 Dockerfile / nginx.conf 构建 cpq-page-image:5173。
4. 启动容器 cpq-page-5173，端口映射 5173:80。

后端部署：
1. 目录：/home/hf/project/cpq-server/
2. 将本包 quarkus-app/ 上传为临时目录，校验 hash 后原子替换远端 quarkus-app/，保留原目录备份。
3. 用本包 Dockerfile.backend 替换远端 Dockerfile（先保留旧文件备份）。
4. 构建 cpq-server-image:1.0，启动容器 cpq-server-1.0，host 网络监听 8201。
5. Dockerfile 使用 eclipse-temurin:21-jre；MaxRAMPercentage=75，匹配容器 4096 MiB 内存上限；profile 保持 webtest。
6. Dockerfile 固定设置 -Dcpq.price-adjust.startup-recovery.enabled=false，避免测试服务器重启时误收尾其他实例任务或自动续跑预算。

验收地址：
- 前端：http://10.177.152.12:5173
- 后端未登录业务探针：http://10.177.152.12:8201/api/cpq/components（期望 HTTP 401）

构建与包自检：
- 前端 npx tsc -b --clean && npx tsc -b --pretty false：通过，0 错误。
- 前端 npm run build：通过；3440 modules transformed；仅有既存的大 chunk 提示。
- 后端 ./mvnw -B -ntp -DskipTests clean package：BUILD SUCCESS。
- fast-jar 四件套 quarkus-run.jar、lib/、app/、quarkus/：齐全。
- 产物来源：干净部署 worktree，精确位于 master 代码基线 cb361d081b6e25c3c3ce0bc4db99a80c4a8442a9。
- V455 脚本从 V454 一次性克隆库连续执行两遍；两遍自检均为 1 / 0 / 0 / 455，3920 条历史报价行保持 profit_snapshot=NULL。
- V455 脚本无 psql 元命令、无美元引用，二次执行仅产生“列已存在，跳过”提示。
- 前端 dist 相对路径 hash-of-hashes：92f5ea34ad3f2d8dc0f2466e284e97f9f0219c6a4c0c9668135f6b638d1acc54。
- 后端 quarkus-app 相对路径 hash-of-hashes：3513465dd00b3922c00ba647fca03a92d62e0066d48ff7ad4c5836bb54691f87。
- Dockerfile.backend SHA-256：e7321dd06311bc09c15f423f6f7b6c320a685f978a1b6299e5b7c06fcb1cc94c。
- 远端上传后复算三项哈希均与本地一致；旧目录、Dockerfile 与镜像保留 backup-20261027154057。
- 远端 Flyway：V454 → V455，V455 success=true；字段 numeric(26,12) NULL、无默认值；部署时 5325 条历史报价行非空快照数为 0。
- 远端容器：前端 HTTP 200；后端未登录业务端点 HTTP 401；Temurin OpenJDK 21.0.11；最终启动日志 ERROR 数为 0。
- 远端 Playwright：T261026_UI_01~03 + T261026_QUI_19，4 passed (36.3s)；测试报价夹具清理后残留 0。

SQL 文件 md5：
- V455：a881fcab2851197941d05c3b80b17337
