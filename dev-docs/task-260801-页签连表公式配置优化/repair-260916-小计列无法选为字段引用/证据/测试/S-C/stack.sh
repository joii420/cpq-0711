#!/usr/bin/env bash
# 临时前后端（只用 8293 / 5293，只连 cpq_db_rp0916d）。
# 用法：
#   stack.sh start-backend <branch|master>        # 本分支已无迁移（D-15），两棵树都以 migrate-at-start=false 启动
#   stack.sh start-vite    <branch|master>
#   stack.sh stop                                                 # 按记录的 PID 停掉本脚本起的进程
#   stack.sh status
# master 树：默认 /home/joii/project/cpq/.claude/worktrees/repair-260916-master-ab（主线提供，detached@d04ff40f；只起服务跑测试，不改文件）
source "$(dirname "$0")/common.sh"
RUN=/tmp/rp0916d-sc; mkdir -p "$RUN"; LOGD="$S/out-stack"; mkdir -p "$LOGD"
root_of() { case "$1" in branch) echo "$WT";; master) echo "${MASTER_WT:-/home/joii/project/cpq/.claude/worktrees/repair-260916-master-ab}";; *) exit 2;; esac; }
port_busy() { ss -ltn "sport = :$1" | grep -q LISTEN; }
listener_pid() { ss -ltnp "sport = :$1" | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2; }
wait_code() { # url expected timeout
  for i in $(seq 1 "$3"); do c=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$1"); [ "$c" = "$2" ] && return 0; sleep 2; done; return 1; }
case "${1:-}" in
start-backend)
  who=$2
  guard_db
  port_busy $BE_PORT && { echo "8293 已被占用，停止（pid=$(listener_pid $BE_PORT)）"; exit 3; }
  R=$(root_of "$who"); APP="rp0916d-SC-$who-$(date +%s)"
  URL="jdbc:postgresql://$DBHOST:5432/$DBNAME?ApplicationName=$APP"
  # 同一 URL 以无 profile 与 %dev 两种键同时给出，防止 application.properties 里的 %dev 值优先生效连到共享库
  ARGS=(-Dquarkus.http.port=$BE_PORT -Ddebug=false
        "-Dquarkus.datasource.jdbc.url=$URL" "-D%dev.quarkus.datasource.jdbc.url=$URL"
        "-Dquarkus.flyway.migrate-at-start=false" "-D%dev.quarkus.flyway.migrate-at-start=false")
  boot() {
    ( cd "$R/cpq-backend" && DB_NAME=$DBNAME QUARKUS_DATASOURCE_JDBC_URL="$URL" setsid nohup ./mvnw -q quarkus:dev "${ARGS[@]}" "$@" > "$LOGD/backend-$who.log" 2>&1 & echo $! > "$RUN/backend.pid" )
    sleep 3; echo "[stack] $(now) backend($who) pgid=$(cat $RUN/backend.pid) root=$R"
    wait_code "$BACKEND/api/cpq/components" 401 150 || { echo "后端 300s 内未返回 401"; tail -40 "$LOGD/backend-$who.log"; exit 4; }
    lp=$(listener_pid $BE_PORT); cwd=$(readlink /proc/$lp/cwd)
    echo "[stack] 8293 listener pid=$lp cwd=$cwd"
    [ "$cwd" = "$R/cpq-backend" ] || { echo "8293 进程 cwd 不是 $R/cpq-backend，停止"; "$0" stop; exit 5; }
    # 验明正身：本实例（ApplicationName）的连接必须全部落在一次性库
    q "select datname,count(*) from pg_stat_activity where application_name='$APP' group by 1" | tee "$LOGD/identity-$who.txt"
    bad=$(q "select count(*) from pg_stat_activity where application_name='$APP' and datname<>'$DBNAME'")
    good=$(q "select count(*) from pg_stat_activity where application_name='$APP' and datname='$DBNAME'")
    [ "$bad" = 0 ] && [ "$good" -ge 1 ] || { echo "连库身份不符 good=$good bad=$bad，停止"; "$0" stop; exit 6; }
    echo "[stack] 连库身份 OK（$good 条连接，全部在 $DBNAME）"
  }
  boot
  q "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+$'" | sed 's/^/[stack] 库内 flyway 最高版本=/' | tee -a "$LOGD/identity-$who.txt"
  # 一次性库内解锁 admin（与 global-setup 对 0724 的动作同构，只作用于本库）
  q "UPDATE \"user\" SET locked_until=NULL, failed_login_attempts=0, is_first_login=false WHERE username='admin'" ;;
start-vite)
  who=$2; R=$(root_of "$who")
  port_busy $FE_PORT && { echo "5293 已被占用"; exit 3; }
  ( cd "$R/cpq-frontend" && VITE_PORT=$FE_PORT VITE_API_TARGET=$BACKEND setsid nohup npx vite --port $FE_PORT --strictPort --host 127.0.0.1 > "$LOGD/vite-$who.log" 2>&1 & echo $! > "$RUN/vite.pid" )
  wait_code "http://localhost:$FE_PORT/" 200 60 || { echo "vite 未起"; tail -20 "$LOGD/vite-$who.log"; exit 4; }
  lp=$(listener_pid $FE_PORT); cwd=$(readlink /proc/$lp/cwd); echo "[stack] 5293 pid=$lp cwd=$cwd"
  [ "$cwd" = "$R/cpq-frontend" ] || { echo "5293 cwd 不符，停止"; "$0" stop; exit 5; }
  # 代理确认：经 5293 访问业务端点应得 401（打到 8293）
  c=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:$FE_PORT/api/cpq/components"); echo "[stack] 5293/api → $c（应 401）"
  [ "$c" = 401 ] || { echo "vite 代理未指向后端"; exit 7; } ;;
stop)
  for f in vite backend; do
    [ -f "$RUN/$f.pid" ] || continue; p=$(cat "$RUN/$f.pid")
    kill -- -"$p" 2>/dev/null && echo "[stack] $(now) 已停 $f pgid=$p"; rm -f "$RUN/$f.pid"
  done
  sleep 3; for pt in $BE_PORT $FE_PORT; do port_busy $pt && echo "⚠️ 端口 $pt 仍被占用 pid=$(listener_pid $pt)（不是本脚本记录的进程，不处理，报主线）"; done; true ;;
status) for pt in $BE_PORT $FE_PORT; do echo "$pt: $(listener_pid $pt) $(readlink /proc/$(listener_pid $pt)/cwd 2>/dev/null)"; done ;;
*) sed -n 2,9p "$0";;
esac
