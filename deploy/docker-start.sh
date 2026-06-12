#!/usr/bin/env bash
# docker-start.sh — CPQ 内网离线一键启动脚本（air-gapped，无需网络）。
#
# 在内网主机上、解压后的离线包目录里运行即可：
#   chmod +x docker-start.sh
#   ./docker-start.sh
#
# 它做的事（全程不联网、不构建、不拉取 registry）：
#   1) 若本地无 cpq:latest 镜像 → 从同目录的 cpq-*.tar.gz 执行 docker load
#   2) 若无 .env → 从 .env.example 生成并提示填写后退出（不带占位配置启动）
#   3) 校验必填环境变量 + CPQ_ENCRYPTION_KEY 长度
#   4) docker compose -f docker-compose.offline.yml up -d（pull_policy: never）
#   5) 轮询 /api/cpq/health 直到就绪（Flyway 首次迁移约 30~90s）
#
# 可选覆盖：
#   IMAGE_TAG=cpq:1.0.0 ./docker-start.sh    # 自定义镜像 tag（需与离线包一致）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

IMAGE_TAG="${IMAGE_TAG:-cpq:latest}"
COMPOSE_FILE="docker-compose.offline.yml"
ENV_FILE=".env"
ENV_TEMPLATE=".env.example"
SAMPLE_KEY="Br70gK6vvP5bvCwQNPm4PU55uI3esEW4"   # .env.example 内置的示例密钥

# ---------- pretty output --------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { printf "${BLUE}[%s]${NC} %s\n" "$(date +%H:%M:%S)" "$*"; }
ok()   { printf "${GREEN}OK${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}!${NC} %s\n" "$*"; }
die()  { printf "${RED}X %s${NC}\n" "$*" >&2; exit 1; }

# ---------- preflight: docker + compose flavor -----------------------------
command -v docker >/dev/null 2>&1 || die "docker 未安装或不在 PATH"
if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)            # v2 插件
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)            # v1 独立命令（CentOS 7 常见）
else
  die "未找到 docker compose (v2) 或 docker-compose (v1)"
fi
command -v gunzip >/dev/null 2>&1 || die "gunzip 未安装"
[ -f "$COMPOSE_FILE" ] || die "缺少 $COMPOSE_FILE —— 请在解压后的离线包目录里运行本脚本"

# ---------- step 1: 载入镜像（缺则 load）------------------------------------
if docker image inspect "$IMAGE_TAG" >/dev/null 2>&1; then
  ok "镜像已存在: $IMAGE_TAG（跳过 docker load）"
else
  TAR=""
  for t in cpq-latest-amd64.tar.gz cpq-latest.tar.gz; do
    [ -f "$t" ] && { TAR="$t"; break; }
  done
  if [ -z "$TAR" ]; then
    for t in cpq-*.tar.gz; do [ -f "$t" ] && { TAR="$t"; break; }; done
  fi
  [ -n "$TAR" ] || die "本地无 $IMAGE_TAG 镜像，且目录下找不到镜像包 cpq-*.tar.gz"
  log "加载镜像: $TAR -> docker load ..."
  gunzip -c "$TAR" | docker load
  docker image inspect "$IMAGE_TAG" >/dev/null 2>&1 \
    || die "docker load 完成但找不到 tag $IMAGE_TAG（用 'docker images' 核对真实 tag，或设 IMAGE_TAG=...）"
  ok "镜像已加载: $IMAGE_TAG"
fi

# ---------- step 2: 确保 .env ----------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
  [ -f "$ENV_TEMPLATE" ] || die "缺少 $ENV_FILE 且无 $ENV_TEMPLATE 可复制"
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  warn "$ENV_FILE 不存在，已从模板生成。请先填写真实连接信息："
  echo "      vim $SCRIPT_DIR/$ENV_FILE"
  echo "      # 必填: DB_HOST DB_USERNAME DB_PASSWORD REDIS_HOST CPQ_ENCRYPTION_KEY(32字符)"
  die "填好 $ENV_FILE 后重新运行 ./docker-start.sh"
fi

# ---------- step 3: 校验必填 env -------------------------------------------
set -a; . "./$ENV_FILE"; set +a
missing=()
for v in DB_HOST DB_USERNAME DB_PASSWORD REDIS_HOST CPQ_ENCRYPTION_KEY; do
  [ -n "${!v:-}" ] || missing+=("$v")
done
[ ${#missing[@]} -eq 0 ] || die ".env 缺少必填项: ${missing[*]}"
keylen=${#CPQ_ENCRYPTION_KEY}
[ "$keylen" -eq 32 ] || die "CPQ_ENCRYPTION_KEY 必须正好 32 个字符（当前 $keylen）"
[ "$CPQ_ENCRYPTION_KEY" = "$SAMPLE_KEY" ] \
  && warn "CPQ_ENCRYPTION_KEY 仍是示例值，上线前务必更换（数据加密后该密钥不可改）"

# ---------- step 4: 启动 ----------------------------------------------------
log "启动容器（离线 compose，pull_policy=never，不构建不拉取）..."
"${DC[@]}" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d
ok "容器已拉起"

# ---------- step 5: 等待健康检查 -------------------------------------------
HTTP_PORT="${HTTP_PORT:-8200}"
url="http://localhost:${HTTP_PORT}/api/cpq/health"
log "等待应用就绪（首次 Flyway 迁移约 30~90s）..."
deadline=$(( $(date +%s) + 180 ))
while :; do
  if curl -fsS "$url" >/dev/null 2>&1; then
    ok "健康检查通过: $url"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    warn "180s 内健康检查未通过 —— 多半还在迁移或配置有误，看日志排查："
    echo "      ${DC[*]} -f $COMPOSE_FILE logs -f app"
    break
  fi
  sleep 5
done

echo
ok "完成。前端入口:  http://<本机IP>:${HTTP_PORT}/"
echo "      查看日志:  ${DC[*]} -f $COMPOSE_FILE logs -f app"
echo "      停止服务:  ./docker-stop.sh"
