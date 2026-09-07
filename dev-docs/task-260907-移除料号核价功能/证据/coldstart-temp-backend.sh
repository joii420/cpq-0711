#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# task-260907 · T-10 / AC-10 冷启动验证（临时端口版）
#
# ⚠️ **与 AC-10 原文的一处偏差，必须在报告里写明**：
#    AC-10 原文写「先停掉已有 8081 进程」再冷启。
#    🚫 测试代理**不停 8081** —— 那是全会话共享的 dev server，主线亲验与用户验收都靠它，
#       停它属于 `CLAUDE.md §3.2`「环境销毁」。⇒ 本脚本改在**临时端口 8099** 冷启。
#    ⇒ 「应用能在它自己声称的端口 8081 上绑起来」这一条**本脚本证明不了**，
#       归主线亲验（`testing.md §5` 冷启动验证：临时端口跑绿 ≠ 声明端口能绑）。
#
# 🚨 陈旧产物级假绿（test.md §0）：删了 java 源文件但 target/classes 留着旧 class ⇒
#    「删了还能跑」或反过来报 CDI 错。⇒ 冷启前必须 `mvnw -q clean`。
#
# 🚫 不含任何 DROP / TRUNCATE / DELETE。库仍是默认 profile 的 cpq_db_0724（只做 Flyway 校验）。
# ═══════════════════════════════════════════════════════════════════
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PORT="${TEMP_BACKEND_PORT:-8099}"
LOG="${1:-/tmp/task260907-coldstart-$PORT.log}"

cd "$ROOT/cpq-backend" || exit 1
echo "[coldstart] worktree = $ROOT"
echo "[coldstart] 目标端口 = $PORT（🚫 不碰共享 8081）"

# 端口占用自检：被占的话会探到**别人的**实例，然后把全部断言打在别人身上（testing.md §4.2）
if curl -s --noproxy '*' -o /dev/null -w '' "http://localhost:$PORT/api/cpq/health" 2>/dev/null; then
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/cpq/health")
  if [ "$code" != "000" ]; then
    echo "🚨 端口 $PORT 已被占用（health 返 $code）—— 换个端口，否则会探到别人的实例"
    exit 2
  fi
fi

echo "[coldstart] ① mvnw -q clean（清 target/classes，防陈旧 class 假绿）"
./mvnw -q clean || { echo "🚨 clean 失败"; exit 1; }

echo "[coldstart] ② 冷启动 quarkus:dev → $LOG"
rm -f "$LOG"
nohup ./mvnw quarkus:dev -Dquarkus.http.port="$PORT" -Ddebug=false > "$LOG" 2>&1 &
BACK_PID=$!
echo "[coldstart] pid=$BACK_PID"

echo "[coldstart] ③ 等待就绪（最多 300s）"
for i in $(seq 1 300); do
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/cpq/components" 2>/dev/null)
  if [ "$code" = "401" ]; then echo "[coldstart] 就绪（第 ${i}s，/api/cpq/components → 401）"; break; fi
  sleep 1
done

echo
echo "═══ AC-10 判据 ═══"
echo "  ── Flyway 校验 ──"
/usr/bin/grep -aiE 'migrat' "$LOG" | tail -6 | sed 's/^/    /'
echo "  ── 禁止出现的三类异常 ──"
for pat in UnsatisfiedResolutionException DeploymentException ClassNotFoundException; do
  n=$(/usr/bin/grep -ac "$pat" "$LOG")
  echo "    $pat = $n （期望 0）"
  [ "$n" -gt 0 ] && /usr/bin/grep -a -m3 "$pat" "$LOG" | sed 's/^/      /'
done
echo "  ── 存活探针（🚫 /q/health 返 404，不是健康探针）──"
echo "    GET /api/cpq/components -> $(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/cpq/components")  （期望 401）"
echo "    GET /api/cpq/health     -> $(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/cpq/health")     （期望 200）"
echo
echo "[coldstart] 后端仍在运行（pid=$BACK_PID），供 E2E 使用。收工时： kill $BACK_PID"
