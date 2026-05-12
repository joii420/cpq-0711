# CPQ Docker 构建与部署指南(方案 B:单镜像)

> 目标环境:CentOS Linux 7 + Docker;PostgreSQL 16 与 Redis 使用**外部**已有实例。
>
> 所有部署文件都在 `dev/deploy/` 目录:
> - `Dockerfile` — 三阶段构建定义
> - `Dockerfile.dockerignore` — 构建上下文过滤(**需 BuildKit**)
> - `docker-compose.yml` — Compose 编排(build context = `..` = `dev/`)
> - `.env.example` — 环境变量模板(运维 cp 为 `.env` 后编辑)
> - `BUILD-DEPLOY.md` — 本文档

## 1. 一次性准备(仅 CentOS 7 宿主)

### 1.1 安装 Docker(若未安装)

```bash
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
docker version            # 验证
docker compose version    # 验证 v2 插件
```

> 若公司只能用 docker-compose v1(独立命令 `docker-compose`),把后文 `docker compose` 全部替换即可,YAML 完全兼容。

### 1.3 启用 BuildKit(Dockerfile.dockerignore 依赖)

Docker 23+ 默认启用 BuildKit;Docker 19~22 需手动启用。检查:

```bash
docker buildx version || echo "BuildKit 未启用"
```

若未启用,二选一:

```bash
# 方式 A: 临时启用(每次 build 都加)
export DOCKER_BUILDKIT=1

# 方式 B: 永久启用(daemon 全局)
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{ "features": { "buildkit": true } }
EOF
sudo systemctl restart docker
```

> 若坚持不开 BuildKit:把 `Dockerfile.dockerignore` 重命名回 `.dockerignore` 并移到 `dev/`(构建上下文根目录),即可走兼容模式。

### 1.2 验证外部依赖可达

在宿主机执行:

```bash
# Postgres
nc -zv <DB_HOST> <DB_PORT>
# Redis
nc -zv <REDIS_HOST> <REDIS_PORT>
```

不通则先打通防火墙/安全组,再继续。同时确保宿主 `8200` 端口未被占用(对外的前端访问端口):

```bash
ss -lntp | grep ':8200 ' || echo 'port 8200 free'
```

## 2. 准备配置

```bash
cd dev/deploy                  # 所有部署命令都在此目录执行
cp .env.example .env
vim .env                       # 填入真实 DB / Redis / 加密密钥
chmod 600 .env
```

### 必填项

| 变量 | 说明 |
|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | 外部 PostgreSQL 16 |
| `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` `REDIS_DB` | 外部 Redis |
| `CPQ_ENCRYPTION_KEY` | **必须 32 字符**;生成:`openssl rand -base64 24 \| head -c 32` |

### 数据库初始化

Quarkus 启动时通过 Flyway 自动跑 143 个迁移(`db/migration/V*.sql`),要求 `DB_USERNAME` 拥有目标库的 CREATE / ALTER 权限。首次启动会耗时 30~90 秒,这是正常的。

## 3. 构建镜像

推荐通过 compose 一步构建+启动(见第 4 节)。如需单独构建镜像:

```bash
# 在 dev/deploy/ 目录下执行
DOCKER_BUILDKIT=1 docker build -t cpq:latest -f Dockerfile ..
```

注意末尾的 `..` —— 构建上下文是父目录 `dev/`,因为 Dockerfile 需要 `cpq-frontend/` 和 `cpq-backend/` 两个源码目录。

- 第一阶段(node:24-alpine)装前端依赖 + `vite build`
- 第二阶段(maven:3.9-eclipse-temurin-17)装 Maven 依赖 + 把前端 dist 嵌入 `META-INF/resources/` + `mvn package`
- 第三阶段(eclipse-temurin:17-jre-jammy)最终运行镜像,非 root,UTC+8 时区

第一次构建 5~15 分钟(主要在 Maven 拉依赖)。之后修改源码再构建,缓存层会复用,通常 < 2 分钟。

## 4. 启动 & 验证

```bash
# 在 dev/deploy/ 目录下执行
docker compose --env-file .env up -d --build
docker compose logs -f app
```

启动成功标志(从日志找):

```
Listening on: http://0.0.0.0:8201
Profile prod activated.
Installed features: [...]
```

健康检查:

```bash
curl -s http://localhost:8200/api/cpq/health
# 期望 200 + JSON
```

浏览器访问 `http://<server-ip>:8200/` → 应看到前端登录页。直接访问任意子路径(如 `/quotations`)刷新也应正常显示(SPA fallback 已就位)。

**端口说明**:容器内 Quarkus 监听 `8201`(由 Dockerfile 中 `QUARKUS_HTTP_PORT=8201` 覆盖,不影响本地 `mvn quarkus:dev` 的 8081);宿主对外发布 `8200`(由 `.env` 中 `HTTP_PORT` 控制),作为前端 + API 的统一入口。

## 5. 常用运维命令

```bash
docker compose ps                 # 状态
docker compose logs -f --tail=200 app
docker compose restart app
docker compose down               # 停止并删除容器(镜像保留)

# 升级:重新构建后滚动更新
docker compose up -d --build

# 进容器排查
docker exec -it cpq-app sh
```

## 6. 离线/内网部署(可选)

外网受限环境下,在能联网的机器构建好镜像,导出再上传:

```bash
# 联网机器(在 dev/deploy/ 目录)
DOCKER_BUILDKIT=1 docker build -t cpq:latest -f Dockerfile ..
docker save cpq:latest | gzip > cpq-latest.tar.gz

# 上传到 CentOS 7 服务器
scp cpq-latest.tar.gz user@server:/opt/cpq/

# 服务器加载
gunzip -c /opt/cpq/cpq-latest.tar.gz | docker load
# 然后正常 docker compose up -d
```

## 7. 故障排查

| 现象 | 排查 |
|---|---|
| 容器反复重启 | `docker compose logs app` 看 stack trace。常见:DB 不通、Redis 密码错、`CPQ_ENCRYPTION_KEY` 长度 ≠ 32 |
| `Flyway migration checksum mismatch` | 数据库已被旧版应用初始化过,且本地 V_xx.sql 有改动。需要 DBA 协助校准 `flyway_schema_history` 表 |
| 健康检查一直 `unhealthy` | start_period 90s 内 Flyway 还在跑,属正常;若超过 3 分钟仍未恢复,看日志找根因 |
| 浏览器刷新子路径 404 | 确认 `SpaFallbackRoute.java` 已编译进镜像(`docker exec cpq-app sh -c 'ls /app/app/'` 应能看到类相关 jar) |
| 端口 8200 已被占用 | 在 `.env` 改 `HTTP_PORT=18200`,重新 `up -d` |

## 8. 安全提示

- `.env` 文件含明文密码/密钥,务必 `chmod 600` 并加入 git 忽略;生产建议用 Docker secrets 或 K8s Secret
- `CPQ_ENCRYPTION_KEY` 一旦上线**不可更换**(会导致已加密数据无法解密)
- 容器以非 root 用户 `app` 运行,无需额外加固
- 默认 `quarkus.mailer.mock=true`(application.properties)—— 上线前若需真实邮件,需追加邮件服务相关 env 并改配置
