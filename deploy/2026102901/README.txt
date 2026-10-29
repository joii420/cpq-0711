CPQ 前后端测试环境部署包
========================

构建日期：2026-10-29
代码分支：master
代码基线：b760ec10150497e893f7ab4575c13e5839febcf2
上次部署包：deploy/2026102702（数据库最新基线 V455）
本次目标数据库基线：V455（无新增迁移）

本包内容：
- dist/：前端 Vite 生产静态文件
- quarkus-app/：后端 Quarkus fast-jar（Java 21）
- Dockerfile.backend：测试服务器后端镜像定义与 JVM / Quarkus 启动参数
- README.txt：本次代码基线、数据库顺序、部署步骤和构建自检

本次功能：
- 报价产品卡片底部移除“产品小计”字样，第一行只显示现有税前金额与币种。
- 在第一行下方增加 `(VAT)` 含税金额，计算口径为 `产品小计 × (1 + 报价行冻结 VAT)`。
- VAT 缺失或非法时第二行显示 `(VAT) -`，不回查当前产品主数据伪造历史税率。
- 编辑态与只读详情态复用同一 Decimal 组件；计算过程继续保持 12 位，最终展示继续按角色限制为最多 4/9 位。
- 核价卡片、保存值、报价总额、导出、后端 API 和数据库结构均未改变。

数据库升级顺序（应用启动前）：
1. 确认上次部署 `2026102702` 对应数据库基线已经是 V455。
2. 本次从 V455 到 V455，没有新增 Flyway 迁移或内网增量 SQL，**不执行任何数据库脚本**。
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
5. Dockerfile 使用 eclipse-temurin:21-jre；MaxRAMPercentage=75，容器内存上限沿用 4096 MiB；profile 保持 webtest。
6. 继续固定 `-Dcpq.price-adjust.startup-recovery.enabled=false`，本次没有新增启动参数。

验收地址：
- 前端：http://10.177.152.12:5173
- 后端未登录业务探针：http://10.177.152.12:8201/api/cpq/components（期望 HTTP 401）

构建与包自检：
- 前端 `npx tsc -b --clean && npm run build`：通过；3445 modules transformed；仅有既存的大 chunk 提示。
- 后端 `./mvnw -B -ntp -DskipTests clean package`：BUILD SUCCESS；Quarkus augmentation completed；测试按打包参数跳过。
- fast-jar 四件套 `quarkus-run.jar`、`lib/`、`app/`、`quarkus/`：齐全。
- 产物来源：打包前干净 master `b760ec10150497e893f7ab4575c13e5839febcf2`。
- 前端 dist 文件数：24；相对路径 hash-of-hashes：`bba90f5f1eb38a11a0e47eb7c828a3c03d3815f849e879e0c7bad85c34c0c3a8`。
- 后端 quarkus-app 文件数：213；相对路径 hash-of-hashes：`7b307595fcf3035398e68510f39d550d6d77c2ba5ba90cfef1faed7b6e73680f`。
- Dockerfile.backend SHA-256：`e7321dd06311bc09c15f423f6f7b6c320a685f978a1b6299e5b7c06fcb1cc94c`。
- hash-of-hashes 口径：在对应目录内执行 `find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum`。

注意：
- 本包未包含新 SQL，不覆盖历史部署包。
- 本次仅生成部署包，尚未上传或启动测试服务器。
- 后端构建保留既有依赖迁移、deprecated API 和三项未识别配置 warning；均未阻止 BUILD SUCCESS，本次未修改相关配置。
