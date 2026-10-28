CPQ 前后端测试环境部署包
========================

构建日期：2026-10-27
代码分支：master
代码基线：115cf40c464457f72fade17b908b92a999a527e9
功能合并基线：7d9f0a11（报价计算结果按角色显示 4/9 位）
上次部署包：deploy/2026102701（数据库最新基线 V455）
本次目标数据库基线：V455（无新增迁移）

本包内容：
- dist/：前端 Vite 生产静态文件
- quarkus-app/：后端 Quarkus fast-jar（Java 21）
- Dockerfile.backend：测试服务器后端镜像定义与 JVM / Quarkus 启动参数
- README.txt：本次基线、数据库顺序、部署和自检记录

本次功能：
- 报价计算过程、保存值、快照和 API decimal string 继续保持原 12 位精度契约。
- 销售代表（SALES_REP）、销售经理（SALES_MANAGER）的报价计算结果最多显示 4 位小数。
- 定价经理（PRICING_MANAGER）、系统管理员（SYSTEM_ADMIN）继续最多显示 9 位小数。
- 报价列表、编辑、详情、产品卡、Excel/比对视图及 HTML/PDF/Excel/邮件输出统一执行当前角色显示上限。
- 原始输入、基础属性、汇率、税率和利润快照不套用角色 4 位上限。

数据库升级顺序（应用启动前）：
1. 确认上次部署 `2026102701` 已将数据库基线升级至 V455。
2. 本次从 V455 到 V455，没有新增 Flyway 迁移或内网增量 SQL，不执行任何数据库脚本。
3. 启动后复核 `flyway_schema_history` 最新成功版本仍为 V455。

前端部署：
1. 目录：/home/hf/project/cpq-page/
2. 将本包 dist/ 上传为临时目录，校验 hash 后原子替换远端 dist/，保留旧目录备份。
3. 使用远端 Dockerfile / nginx.conf 构建 cpq-page-image:5173。
4. 启动容器 cpq-page-5173，端口映射 5173:80。

后端部署：
1. 目录：/home/hf/project/cpq-server/
2. 将本包 quarkus-app/ 上传为临时目录，校验 hash 后原子替换远端 quarkus-app/，保留旧目录备份。
3. 用本包 Dockerfile.backend 替换远端 Dockerfile（先保留旧文件备份）。
4. 构建 cpq-server-image:1.0，启动容器 cpq-server-1.0，host 网络监听 8201。
5. Dockerfile 使用 eclipse-temurin:21-jre；MaxRAMPercentage=75，容器内存上限 4096 MiB；profile 保持 webtest。
6. 继续固定 -Dcpq.price-adjust.startup-recovery.enabled=false，本次没有新增启动参数。

验收地址：
- 前端：http://10.177.152.12:5173
- 后端未登录业务探针：http://10.177.152.12:8201/api/cpq/components（期望 HTTP 401）

构建与包自检：
- 前端 `npx tsc -b --clean && npx tsc -b --pretty false && npm run build`：通过；3444 modules transformed；仅有既存的大 chunk 提示。
- 后端 `./mvnw -B -ntp -DskipTests clean package`：BUILD SUCCESS。
- 合并后回归：前端 116 个文件 / 1473 项通过；后端 clean 定向 39/39 通过。
- fast-jar 四件套 quarkus-run.jar、lib/、app/、quarkus/：齐全。
- 产物来源：干净 master `115cf40c464457f72fade17b908b92a999a527e9`。
- 前端 dist 相对路径 hash-of-hashes：d85df3c9d4354ee2930a2c6178a4b9190af6d30d1ecb094874bc9f9416b8eea8。
- 后端 quarkus-app 相对路径 hash-of-hashes：0596e4952216f52d16001e9a5ca2d2f63a5113537793a0e3d0f0df4675ebd7e7。
- Dockerfile.backend SHA-256：e7321dd06311bc09c15f423f6f7b6c320a685f978a1b6299e5b7c06fcb1cc94c。

注意：
- 本包未包含新 SQL，不覆盖历史部署包。
- 一次性测试库 `cpq_t261027_rdp_1027a` 仍保留；DROP DATABASE 需用户单独授权，本次部署不处理。

测试服务器部署记录：
- 部署时间：2026-10-28 14:49（Asia/Shanghai）。
- 远端备份标识：backup-20261027234508；dist、quarkus-app、Dockerfile 和前后端镜像均已保留。
- 上传后按目录内 `./`相对路径复算：dist = f7267e1e9819c742b36173daabfb844993badc2d5cd66dca333ee6da04e0a59d；quarkus-app = 5c724966b3084e20135b88641ea799af098ae5c11ae211c20a5685082630cd89；Dockerfile = e7321dd06311bc09c15f423f6f7b6c320a685f978a1b6299e5b7c06fcb1cc94c，与本地同口径一致。
- 前端镜像：sha256:4179b4a76fd03efb1673564597e2d114efd9d106f4c38b8c67013749b8a44ee9。
- 后端镜像：sha256:96b51cd01c5cacd2ef547f18e0de52d67c368856e3e3e29f205b4ca96dedf247。
- 容器：cpq-page-5173 使用 5173:80；cpq-server-1.0 使用 host 网络、4096 MiB、`/u2/logs/cpq-server-1.0:/logs`；两者 restart=always。
- 后端 Java：Eclipse Temurin OpenJDK 21.0.11 LTS。
- Flyway：`455|t`；启动日志确认 V455 up to date，未执行新迁移。
- Quarkus：webtest profile，6.845s 启动，监听 0.0.0.0:8201；严格 `ERROR` 级别日志 0。
- HTTP 探针：外部前端 200；外部后端未登录业务端点 401。
- 实际角色输出：SYSTEM_ADMIN 与 SALES_MANAGER 登录均 200，同一报价 HTML 导出均 200；管理员为 `1717.135540718`，销售经理为 `1717.1355`，销售结果不含 9 位文本。预检首次误用 GET 产生两条可解释 405 WARN，改为契约 POST 后均为 200。
- 真实浏览器（Browser plugin 不可用，使用 Playwright Chrome 1440×900）：管理员报价列表显示 `1717.135540718 USD`，销售经理显示 `1717.1355 USD`；页面非空、无框架错误覆盖层，console error/warn 均为 0。
