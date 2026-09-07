#!/usr/bin/env bash
# ⛔ 已作废（2026-09-03）—— 🚫 不要执行，也不要再为它向共享库报批。
#
# 本脚本原本是 AC-125「启动期自检」反证的安全替代方案（建克隆库跑）。
# 但 cpq-backend #2 已在克隆库 cpq_b42_flyway 上把 A/B 两轮都跑完了：
#   A 轮（无漂移）：正常启动，日志 "[builder] 全版本视图自检通过：26 张 v_<主表>_all，逐列与主表双向一致(+is_current)"
#   B 轮（ALTER TABLE ds_cost_basic_material_bom ADD COLUMN drift_probe）：启动失败，
#        IllegalStateException: [builder] 核价全版本视图 ... 缺列 drift_probe（该列在取数配置器里永远查不到）
# ⇒ AC-125 的反证证据已存在，本脚本的唯一价值降为「方法留痕」。
#
# 保留而不删除，遵循 CLAUDE.md 的归档纪律（Agent 会引用旧路径，要让它撞到「已作废，见 X」而不是撞到空）。
# 静态不变量那一半仍在 V9VersionAndDriftTest.ac125_versionViewsHaveNoColumnDrift 里，随 mvnw test 跑。
# ─────────────────────────────────────────────────────────────────────────────
# AC-125 反证探针 —— 「视图列漂移 ⇒ 后端启动失败」
#
# 🚦 本脚本【未执行】。它需要主线取得用户批准后才可以跑，原因见下。
#
# AC-125 原文要求：26 张全版本视图的列 = 主表列 + is_current，无差集；
#                   且【必须做成启动期自检】—— 不一致直接启动失败。
#
# 「不一致时真的起不来」这一半，只能靠**制造一次不一致**来证伪。
# 而制造不一致 = 给 ds_cost_* 主表加一列，那是：
#   · task-260902 的表（迁移 V405~V408 已应用、checksum 锁死）
#   · backtask.md 全局硬约束③「ds_* 45 张表的表结构不许改」
#   · CLAUDE.md §3.2「契约销毁」类
# ⇒ 测试工程师没有批准权，停下报告（这就是本脚本存在而不被执行的原因）。
#
# 本脚本给出**不碰共享库**的安全做法：在克隆库上做，全程不写 cpq_db_0724。
#
# ⚠️ 仍需批准的两处（都属 §3.2，请主线逐项向用户报批）：
#   ① CREATE DATABASE cpq_v9_drift125 TEMPLATE cpq_db_0724
#      —— TEMPLATE 复制期间源库**不能有活连接**，PG 会报
#         "source database is being accessed by other users"。
#         这意味着要先停掉 8081 dev server / 让别的会话断开 ⇒ 影响他人，必须报批。
#   ② DROP DATABASE cpq_v9_drift125（收尾）—— 数据销毁类，即便是自建库也要报批。
#
# 期望结果（AC-125 通过的判据）：
#   A 轮：克隆库未改列 → 后端在临时端口 8093 正常启动（业务端点返 401）
#   B 轮：克隆库给某张 ds_cost_* 主表加一列 → 后端**启动失败**，日志点名该表/该视图
#   两轮都符合才算 AC-125 的「启动期自检」这一半通过；B 轮若照常启动 ⇒ 自检没接上，判红。
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

CLONE_DB="cpq_v9_drift125"
SRC_DB="cpq_db_0724"
PGHOST="10.177.152.12"
PGUSER="postgres"
export PGPASSWORD="${DB_PASSWORD:-joii5231}"
PORT=8093          # 🚫 不用 8081 / 5174 —— 那两个留给主线亲验
PROBE_TABLE="ds_cost_basic_material_bom"
PROBE_COLUMN="v9t_drift_probe_col"

WORKTREE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKEND="$WORKTREE/cpq-backend"

say() { printf '\n=== %s ===\n' "$*"; }

say "0. 前置确认（脚本不会替你批准）"
cat <<'EOF'
本脚本将要执行的、属于 CLAUDE.md §3.2 的操作：
  ① CREATE DATABASE cpq_v9_drift125 TEMPLATE cpq_db_0724   （需源库无活连接）
  ② ALTER TABLE <克隆库>.ds_cost_basic_material_bom ADD COLUMN v9t_drift_probe_col text
  ③ DROP DATABASE cpq_v9_drift125
影响面：② 只作用于克隆库，不碰共享库；① 需要源库暂时无连接（会打扰别的会话）。
可恢复性：克隆库可随时重建；共享库全程只读，不受影响。

未取得用户明确批准前，请勿继续。取得批准后：REALLY=1 bash ac125-drift-probe.sh
EOF
if [ "${REALLY:-0}" != "1" ]; then
  echo ">> 未设置 REALLY=1，按设计退出（这是安全默认，不是失败）。"
  exit 0
fi

say "1. 建克隆库"
psql -h "$PGHOST" -U "$PGUSER" -d postgres -c \
  "SELECT count(*) AS active_conns FROM pg_stat_activity WHERE datname='$SRC_DB'"
psql -h "$PGHOST" -U "$PGUSER" -d postgres -c \
  "CREATE DATABASE $CLONE_DB TEMPLATE $SRC_DB" || {
    echo "!! 建克隆库失败。最常见原因：源库有活连接（先停 8081 dev server 并让别的会话断开）。"; exit 1; }

start_backend() {
  local db="$1" logfile="$2"
  ( cd "$BACKEND" && DB_NAME="$db" ./mvnw -q quarkus:dev \
      -Dquarkus.http.port="$PORT" -Dquarkus.profile=test >"$logfile" 2>&1 & echo $! >/tmp/v9probe.pid )
  # 等最多 90s：要么业务端点返 401（起来了），要么进程退出（起不来）
  for _ in $(seq 1 90); do
    sleep 1
    code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
            "http://localhost:$PORT/api/cpq/components" || true)
    [ "$code" = "401" ] && { echo "UP"; return 0; }
    kill -0 "$(cat /tmp/v9probe.pid)" 2>/dev/null || { echo "DOWN"; return 1; }
  done
  echo "TIMEOUT"; return 1
}
stop_backend() { kill "$(cat /tmp/v9probe.pid)" 2>/dev/null || true; sleep 3; }

say "2. A 轮 —— 未制造漂移，后端应正常启动"
A=$(start_backend "$CLONE_DB" /tmp/v9probe-A.log); stop_backend
echo "A 轮结果 = $A"
[ "$A" = "UP" ] || { echo "!! A 轮就起不来 ⇒ 后面的 B 轮没有对照价值（可能是别的原因导致起不来）。日志 /tmp/v9probe-A.log"; }

say "3. 制造漂移（只作用于克隆库）"
psql -h "$PGHOST" -U "$PGUSER" -d "$CLONE_DB" -c \
  "ALTER TABLE $PROBE_TABLE ADD COLUMN $PROBE_COLUMN text"
echo ">> 已给 $CLONE_DB.$PROBE_TABLE 加列 $PROBE_COLUMN"
echo ">> 注意：v_${PROBE_TABLE}_all 视图是 UNION 显式列举列，**不会报错、只会静默丢掉这一列**"
psql -h "$PGHOST" -U "$PGUSER" -d "$CLONE_DB" -Atc \
  "SELECT count(*) FROM information_schema.columns WHERE table_name='v_${PROBE_TABLE}_all' AND column_name='$PROBE_COLUMN'" \
  | sed 's/^/   视图里该列的存在数（期望 0，正是静默丢列的证据）= /'

say "4. B 轮 —— 有漂移，后端【必须】启动失败"
B=$(start_backend "$CLONE_DB" /tmp/v9probe-B.log); stop_backend
echo "B 轮结果 = $B"

say "5. 判定"
if [ "$A" = "UP" ] && [ "$B" != "UP" ]; then
  echo "✅ AC-125【启动期自检】反证成立：无漂移能起、有漂移起不来。"
  echo "   请到 /tmp/v9probe-B.log 里核对报错是否**点名**了 $PROBE_TABLE / v_${PROBE_TABLE}_all / $PROBE_COLUMN。"
  grep -n -i "$PROBE_TABLE\|$PROBE_COLUMN\|drift\|漂移" /tmp/v9probe-B.log | head -20
else
  echo "❌ AC-125【启动期自检】反证不成立（A=$A B=$B）。"
  echo "   B 轮照常启动 ⇒ 漂移自检没接上，或没做成启动期。这正是 D-84′ 说的『新列永远查不到且零信号』。"
fi

say "6. 收尾 —— DROP 克隆库（§3.2，需已批准）"
echo ">> 手工执行（脚本不自动删，避免误删）："
echo "   psql -h $PGHOST -U $PGUSER -d postgres -c 'DROP DATABASE $CLONE_DB'"
