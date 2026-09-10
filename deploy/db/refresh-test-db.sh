#!/usr/bin/env bash
# =============================================================================
# 把 cpq_db_test 从 cpq_db_0724 重新克隆一份（自动化测试库定期同步）
# -----------------------------------------------------------------------------
# 为什么需要它：
#   cpq_db_test 是 2026-09-09 从 cpq_db_0724 克隆的快照。schema 由 Flyway
#   migrate-at-start 自动跟进，但**数据不会同步** —— 时间一长，测试跑在越来越
#   陈旧的数据上，结果与开发库越差越远。
#
# 🚨 本脚本会销毁 cpq_db_test 的全部内容（CLAUDE.md §3.2【数据销毁】）
#    执行前请确认：
#      1) 没有人正在用 cpq_db_test 跑测试（脚本会自检并拒绝）
#      2) cpq_db_test 里没有你想保留的东西（它按定义是一次性的）
#
# 用法：
#   bash deploy/db/refresh-test-db.sh            # 交互确认后执行
#   bash deploy/db/refresh-test-db.sh --yes      # 跳过确认（CI / 明确授权时）
# =============================================================================
set -euo pipefail

PGHOST="${PGHOST:-10.177.152.12}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-joii5231}"

SRC="${SRC_DB:-cpq_db_0724}"     # 源：开发共享库
DST="${DST_DB:-cpq_db_test}"     # 目标：自动化测试库
PSQL="psql -h $PGHOST -p $PGPORT -U $PGUSER -v ON_ERROR_STOP=1"

# 🚫 硬护栏：目标库名白名单。防手滑把 SRC/DST 写反或指到别的库。
case "$DST" in
  cpq_db_test) ;;
  *) echo "🛑 拒绝执行：目标库只允许 cpq_db_test，当前是「$DST」" >&2; exit 1 ;;
esac
if [ "$SRC" = "$DST" ]; then echo "🛑 拒绝执行：源库与目标库相同" >&2; exit 1; fi
case "$SRC" in
  cpq_db_0910) echo "🛑 拒绝执行：cpq_db_0910 是用户的真机验证库，不作为克隆源" >&2; exit 1 ;;
esac

echo "═══ 1/5 前置自检 ═══"
CONN=$($PSQL -d postgres -tAc "SELECT count(*) FROM pg_stat_activity WHERE datname='$DST';")
echo "  $DST 当前连接数：$CONN"
if [ "$CONN" != "0" ]; then
  echo "🛑 拒绝执行：$DST 上还有 $CONN 个连接（可能有人正在跑测试）。" >&2
  $PSQL -d postgres -c "SELECT pid, application_name, state, state_change FROM pg_stat_activity WHERE datname='$DST';" >&2
  exit 1
fi
SRC_TABLES=$($PSQL -d "$SRC" -tAc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r';")
echo "  源库 $SRC 表数：$SRC_TABLES"
[ "$SRC_TABLES" -gt 100 ] || { echo "🛑 源库表数异常（$SRC_TABLES），疑似连错库，中止" >&2; exit 1; }

echo "═══ 2/5 影响面（§3.2 要求：说不出数字就不许执行）═══"
$PSQL -d "$DST" -tAc "
SELECT '  将销毁 '||count(*)||' 张表 / '||pg_size_pretty(pg_database_size('$DST'))||' 数据'
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r';"
echo "  可恢复性：本脚本本身即恢复手段（从 $SRC 重新克隆）。$DST 按定义是一次性测试库，无独有数据。"

if [ "${1:-}" != "--yes" ]; then
  read -r -p "  确认执行？(输入 yes 继续) " ans
  [ "$ans" = "yes" ] || { echo "已取消"; exit 0; }
fi

echo "═══ 3/5 清空目标库 ═══"
$PSQL -d "$DST" -c "DROP SCHEMA IF EXISTS public CASCADE;" -c "CREATE SCHEMA public;" \
  -c "GRANT ALL ON SCHEMA public TO public;"

echo "═══ 4/5 从 $SRC 全量克隆 ═══"
pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$SRC" --no-owner --no-privileges \
  | $PSQL -d "$DST" >/dev/null
echo "  完成"

echo "═══ 5/5 验证（六项，任一不过即视为失败）═══"
$PSQL -d postgres -tAc "SELECT 1" >/dev/null
for q in \
  "表数|SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r'" \
  "视图数|SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='v'" \
  "序列数|SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='S'" \
  "函数数|SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'" \
  "flyway最高|SELECT COALESCE(max(version::int),0) FROM flyway_schema_history" \
  "用户数|SELECT count(*) FROM \"user\"" ; do
  k="${q%%|*}"; s="${q#*|}"
  a=$($PSQL -d "$SRC" -tAc "$s"); b=$($PSQL -d "$DST" -tAc "$s")
  if [ "$a" = "$b" ]; then printf "  ✅ %-10s 源=%s 目标=%s\n" "$k" "$a" "$b"
  else printf "  🔴 %-10s 源=%s 目标=%s  ← 不一致\n" "$k" "$a" "$b"; FAIL=1; fi
done
[ "${FAIL:-0}" = "1" ] && { echo "🔴 验证未通过，请人工检查" >&2; exit 1; }

echo
echo "✅ $DST 已从 $SRC 重新克隆完成（$(date '+%Y-%m-%d %H:%M:%S')）"
echo "   ⚠️ 提醒：mvnw test 默认打 $DST（application-test.properties）。"
echo "      临时要打回共享库用：DB_NAME=$SRC ./mvnw test"
