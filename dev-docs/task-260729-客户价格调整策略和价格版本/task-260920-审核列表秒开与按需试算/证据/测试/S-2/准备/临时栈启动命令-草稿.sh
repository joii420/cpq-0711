#!/usr/bin/env bash
# task-260920 S-2 · 临时栈启动命令【草稿 · 本阶段未执行】
# 前提：S-1 全绿 + 主线通知 S-2 开跑 + R 轮报批已通过；S-1 已不再占用 cpq-backend/target/（本 worktree 同一时刻只许一个 maven）。
# 端口：后端 8130 / 前端 5230。🚫 8081/5174（用户与主仓）、8295/5295（主线亲验）。
set -euo pipefail
WT=/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute
EV="$WT/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2"
RUN="${RUN:-R1-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$EV/$RUN"
LOG="$EV/$RUN/backend-8130.log"          # 日志直接落任务目录（AC-14/21 取 [perf] 与 dryRun 行）；*.log 被 gitignore，交主线时 git add -f

# 0. 探端口（必须都空闲；占用 ⇒ 停下报主线，🚫 杀别人的进程）
for p in 8130 5230; do ss -ltnH "sport = :$p" | grep -q . && { echo "端口 $p 已占用，停"; exit 1; }; done
pgrep -af "node.*[p]laywright test" && { echo "E-4：有别的 playwright 在跑，停"; exit 1; } || true

# 1. 构建（仅在 S-1 让出 target/ 之后；只构建 jar，不跑测试）
( cd "$WT/cpq-backend" && ./mvnw -o -q package -DskipTests ) 2>&1 | tail -20
git -C "$WT" rev-parse HEAD | tee "$EV/$RUN/commit.txt"

# 2. 后端（jar ⇒ prod profile：flyway migrate-at-start=false，不向共享库打迁移；数据源默认值 = 10.177.152.12/cpq_db_0724）
( cd "$WT/cpq-backend" && nohup java \
    -Dquarkus.http.port=8130 \
    -Dquarkus.scheduler.enabled=false \
    -Dcpq.price-adjust.startup-recovery.enabled=false \
    -Dcpq.price-adjust.budget.concurrency=3 \
    -jar target/quarkus-app/quarkus-run.jar > "$LOG" 2>&1 & echo $! > "$EV/$RUN/backend.pid" )
# 点击即算名额 cpq.price-adjust.budget.interactive-concurrency 用默认值 2（不传）

# 3. 探活 + 验明正身（不看 200：业务端点应 401；再比 totalElements 与 cpq_db_0724 的行数）
for i in $(seq 1 60); do c=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8130/api/cpq/components || true); [ "$c" = 401 ] && break; sleep 1; done
echo "8130 components → $c（期望 401）"
readlink /proc/$(cat "$EV/$RUN/backend.pid")/cwd          # 必须是 $WT/cpq-backend
grep -m3 -E 'Profile prod activated|Listening on|cpq_db_0724' "$LOG" || true
grep -m5 -E 'scheduler|startup-recovery|concurrency' "$LOG" || true   # E-1 三个参数生效的旁证（看得到就贴，看不到写「日志无此行」）

# 4. 前端临时 vite（代理 → 8130）；node_modules 已在 worktree（ls cpq-frontend/node_modules 实查存在）
( cd "$WT/cpq-frontend" && VITE_PORT=5230 VITE_API_TARGET=http://localhost:8130 nohup npx vite --port 5230 --strictPort > "$EV/$RUN/vite-5230.log" 2>&1 & echo $! > "$EV/$RUN/vite.pid" )
for i in $(seq 1 60); do c=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5230/ || true); [ "$c" = 200 ] && break; sleep 1; done
echo "5230 / → $c（期望 200）；经代理：$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5230/api/cpq/components)（期望 401）"

# 5. 冷启动（E-8）：R1 = 本进程启动后的第一轮全量；R2 / R3 前各「停 → 按第 2 步重起」，🚫 复用进程
# 停：kill $(cat "$EV/$RUN/backend.pid")   —— 只杀自己记下的 pid，🚫 pkill -f（会匹配到别人/自己）
