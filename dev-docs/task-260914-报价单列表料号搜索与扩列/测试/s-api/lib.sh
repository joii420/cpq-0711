#!/usr/bin/env bash
# task-260914 · S-API 分片 · 公共库
# 🚨 纯只读：全脚本只有 GET 与 SELECT。无 INSERT/UPDATE/DELETE/DDL。
#    唯一的非 GET 是 POST /auth/login（取会话，任何接口测试都绕不开）。

# ⚠️ 不用 set -e：用例失败要继续跑完并汇总，不能中途退出。
#    但 pipefail 必须开 —— 否则 `psql ... | tail` 会把 psql 的失败吞掉（本项目实测栽过）。
set -uo pipefail

BACKEND="${S_API_BACKEND:-http://localhost:8195}"
DB_HOST="${S_API_DB_HOST:-10.177.152.12}"
DB_PORT="${S_API_DB_PORT:-5432}"
DB_USER="${S_API_DB_USER:-postgres}"
DB_NAME="${S_API_DB_NAME:-cpq_db_0724}"
export PGPASSWORD="${S_API_DB_PASS:-joii5231}"

ADMIN_USER="${S_API_ADMIN_USER:-admin}"
ADMIN_PASS="${S_API_ADMIN_PASS:-Admin@2026}"
SALES_USER="${S_API_SALES_USER:-alice}"
SALES_PASS="${S_API_SALES_PASS:-Admin@2026}"

PASS_N=0; FAIL_N=0; UNVERIFIED_N=0; UNSTABLE_N=0
RESULT_LINES=()

C_OK=$'\033[32m'; C_NG=$'\033[31m'; C_WARN=$'\033[33m'; C_OFF=$'\033[0m'
[ -t 1 ] || { C_OK=""; C_NG=""; C_WARN=""; C_OFF=""; }

# ---------- 结果登记 ----------
hr(){ echo "────────────────────────────────────────────────────────────────"; }
tc_begin(){ echo; hr; echo "▶ $1  【服务 AC】$2"; echo "  $3"; hr; }
ok(){    PASS_N=$((PASS_N+1));       RESULT_LINES+=("PASS|$1|$2"); echo "  ${C_OK}✅ PASS${C_OFF}  $1 — $2"; }
ng(){    FAIL_N=$((FAIL_N+1));       RESULT_LINES+=("FAIL|$1|$2"); echo "  ${C_NG}❌ FAIL${C_OFF}  $1 — $2"; }
unv(){   UNVERIFIED_N=$((UNVERIFIED_N+1)); RESULT_LINES+=("UNVERIFIED|$1|$2"); echo "  ${C_WARN}⚠️  未验证${C_OFF} $1 — $2"; }
unst(){  UNSTABLE_N=$((UNSTABLE_N+1)); RESULT_LINES+=("UNSTABLE|$1|$2"); echo "  ${C_WARN}🌀 不稳定${C_OFF} $1 — $2"; }

# ---------- SQL ----------
# 单值查询。psql 失败一律硬失败（不许把空串当 0 —— 那是本项目的经典假绿）。
SQL_LAST=""
sql1(){
  local q="$1" out rc
  out=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
        -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "$q" 2>&1)
  rc=$?
  if [ $rc -ne 0 ]; then
    echo "  ${C_NG}psql 执行失败(rc=$rc)${C_OFF}: $out" >&2
    SQL_LAST=""; return 1
  fi
  out=$(printf '%s' "$out" | tr -d '[:space:]')
  if ! printf '%s' "$out" | /usr/bin/grep -aqE '^-?[0-9]+$'; then
    echo "  ${C_NG}psql 返回的不是数字${C_OFF}: [$out]" >&2
    SQL_LAST=""; return 1
  fi
  SQL_LAST="$out"; printf '%s' "$out"
}

# 双采样夹逼：共享库随时可能有别的会话在建单。
# 采样1 → 打接口 → 采样2；两次不等说明库在我们脚下变了 ⇒ 判「不稳定」而不是判 FAIL。
# ⚠️ 不写成 helper 函数是故意的：helper 在 $( ) 里跑是子 shell，回写全局变量不生效（经典坑）。

# ---------- HTTP ----------
JAR_ADMIN=""; JAR_SALES=""
login(){ # login <user> <pass> <jarvar>
  local u="$1" p="$2" jar code
  jar=$(mktemp)
  code=$(curl -s --noproxy '*' -c "$jar" -o /dev/null -w '%{http_code}' \
        -X POST "$BACKEND/api/cpq/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$u\",\"password\":\"$p\"}")
  if [ "$code" != "200" ]; then echo "  ${C_NG}登录失败${C_OFF} $u -> HTTP $code" >&2; rm -f "$jar"; return 1; fi
  printf '%s' "$jar"
}

HTTP_CODE=""; API_BODY=""
api_get(){ # api_get <jar> <querystring> ; 结果放 HTTP_CODE / API_BODY
  local jar="$1" qs="$2" tmp
  tmp=$(mktemp)
  HTTP_CODE=$(curl -s --noproxy '*' -b "$jar" -o "$tmp" -w '%{http_code}' "$BACKEND/api/cpq/quotations?$qs")
  API_BODY=$(cat "$tmp"); rm -f "$tmp"
  echo "  URL     : $BACKEND/api/cpq/quotations?$qs"
  echo "  HTTP    : $HTTP_CODE"
}

# 从 API_BODY 取 totalElements；取不到返回非 0（绝不静默当 0）
api_total(){
  printf '%s' "$API_BODY" | python3 -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print("PARSE_ERROR:%s"%e); sys.exit(2)
if d.get("code")!=200: print("BIZ_CODE_%s:%s"%(d.get("code"),d.get("message"))); sys.exit(3)
t=(d.get("data") or {}).get("totalElements")
if t is None: print("NO_TOTAL"); sys.exit(4)
print(t)'
}

# 断言：接口 totalElements == 基准 SQL 现场值（同分钟双采样）
# 用法：assert_count <TCID> <AC> <querystring> <基准SQL> [<必须严格小于的上界，用于证明过滤真的生效>]
assert_count(){
  local tc="$1" ac="$2" qs="$3" q="$4" ceil="${5:-}"
  local before after total
  before=$(sql1 "$q") || { ng "$tc" "基准 SQL 跑不通，无法产生期望值"; return 1; }
  echo "  基准SQL : $q"
  echo "  SQL值(前): $before"
  api_get "$JAR_ADMIN" "$qs"
  total=$(api_total); local prc=$?
  after=$(sql1 "$q") || { ng "$tc" "基准 SQL 复采失败"; return 1; }
  echo "  SQL值(后): $after"
  echo "  接口值  : $total"
  if [ $prc -ne 0 ]; then ng "$tc" "接口返回不可解析/非 200 业务码：$total"; return 1; fi
  if [ "$before" != "$after" ]; then
    unst "$tc" "采样窗口内库发生变化（$before → $after），本次不作判据，请重跑"; return 1
  fi
  # 🚨 反假绿：期望值为 0 时，「接口也返 0」证明不了过滤生效（全查不到也是 0）
  if [ "$before" = "0" ]; then
    unv "$tc" "基准值为 0，断言退化（0==0 无区分力），不计 PASS"; return 1
  fi
  if [ "$total" != "$before" ]; then ng "$tc" "接口 $total ≠ 基准 SQL $before"; return 1; fi
  if [ -n "$ceil" ]; then
    if [ "$total" -ge "$ceil" ]; then
      ng "$tc" "接口 $total 未低于上界 $ceil —— 过滤条件很可能被后端忽略了（改动前基线就是这个数）"; return 1
    fi
    echo "  收窄证据: $total < $ceil ✔（证明过滤条件真的进了 where，不是被忽略）"
  fi
  ok "$tc" "接口 $total == 基准 SQL $before"
}

# 拉全量行（size 无上限，已实测 size=500 返回全部 187 行）
fetch_all_rows(){ # fetch_all_rows <jar> <querystring 不含 page/size>
  api_get "$1" "$2&page=0&size=500"
}

summary(){
  echo; hr
  echo "S-API 汇总：PASS=$PASS_N  FAIL=$FAIL_N  未验证=$UNVERIFIED_N  不稳定=$UNSTABLE_N"
  hr
  for l in "${RESULT_LINES[@]:-}"; do [ -n "$l" ] && echo "  $l"; done
  hr
  echo "库: $DB_HOST/$DB_NAME   后端: $BACKEND   时刻: $(date -Is)"
  [ "$FAIL_N" -gt 0 ] && return 1
  return 0
}
