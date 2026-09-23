#!/usr/bin/env bash
# =====================================================================
# task-260922 · 分片 S-API · 公共库（AC-2/3/4/5/7/8 的接口层脚本用）
#
# 🚨 纯只读：全库只有 GET + SELECT。无 INSERT / UPDATE / DELETE / DDL。
#    唯一的非 GET 是 POST /api/cpq/auth/login（取会话，接口测试绕不开）。
# 🚫 期望值一律现场用 任务.md §⑦ 基准 SQL 重算，不硬编码立项日采样值。
# =====================================================================

# ⚠️ 不用 set -e：用例失败要继续跑完并汇总。
#    pipefail 必须开 —— 否则 `psql ... | tr` 会把 psql 的失败吞掉。
set -uo pipefail

BACKEND="${S_API_BACKEND:-http://localhost:8322}"
DB_HOST="${S_API_DB_HOST:-10.177.152.12}"
DB_PORT="${S_API_DB_PORT:-5432}"
DB_USER="${S_API_DB_USER:-postgres}"
DB_NAME="${S_API_DB_NAME:-cpq_db_0724}"
export PGPASSWORD="${S_API_DB_PASS:-joii5231}"

ADMIN_USER="${S_API_ADMIN_USER:-admin}"
ADMIN_PASS="${S_API_ADMIN_PASS:-Admin@2026}"

# 证据目录：可由环境变量改写（testing.md §5.7⑤：不许写死，复跑/证伪要能写到独立目录）。
# 默认 = 任务目录 证据/s-api/run-<时间戳>-<后端端口>/，每轮一个新目录，天然不覆盖旧证据。
_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_TASK_DIR="$(cd "$_LIB_DIR/../.." && pwd)"
_PORT_TAG="$(printf '%s' "$BACKEND" | sed -E 's#.*:([0-9]+).*#\1#')"
EVID="${S_API_EVIDENCE_DIR:-$_TASK_DIR/证据/s-api/run-$(date +%Y%m%d-%H%M%S)-$_PORT_TAG}"
mkdir -p "$EVID" || { echo "证据目录建不了: $EVID" >&2; exit 2; }

PASS_N=0; FAIL_N=0; UNVERIFIED_N=0; UNSTABLE_N=0
RESULT_LINES=()

C_OK=$'\033[32m'; C_NG=$'\033[31m'; C_WARN=$'\033[33m'; C_OFF=$'\033[0m'
[ -t 1 ] || { C_OK=""; C_NG=""; C_WARN=""; C_OFF=""; }

hr(){ echo "────────────────────────────────────────────────────────────────"; }
tc_begin(){ echo; hr; echo "▶ $1  【服务 AC】$2"; echo "  $3"; hr; }
ok(){   PASS_N=$((PASS_N+1));             RESULT_LINES+=("PASS|$1|$2");       echo "  ${C_OK}✅ PASS${C_OFF}  $1 — $2"; }
ng(){   FAIL_N=$((FAIL_N+1));             RESULT_LINES+=("FAIL|$1|$2");       echo "  ${C_NG}❌ FAIL${C_OFF}  $1 — $2"; }
unv(){  UNVERIFIED_N=$((UNVERIFIED_N+1)); RESULT_LINES+=("UNVERIFIED|$1|$2"); echo "  ${C_WARN}⚠️  未验证${C_OFF} $1 — $2"; }
unst(){ UNSTABLE_N=$((UNSTABLE_N+1));     RESULT_LINES+=("UNSTABLE|$1|$2");   echo "  ${C_WARN}🌀 不稳定${C_OFF} $1 — $2"; }

# ---------------------------------------------------------------------
# 基准 SQL（任务.md §⑦ 原文，:kw 替换为小写关键字）
# 关键字白名单：只许 [a-z0-9-]。本期关键字全是字母数字；'_' '%' 是 LIKE 通配符、
# 单引号会破坏语句 —— 出现就直接拒绝，不做转义（转义写错比拒绝更隐蔽）。
# ---------------------------------------------------------------------
kw_check(){ printf '%s' "$1" | /usr/bin/grep -aqE '^[a-z0-9-]+$' || { echo "非法关键字（只许小写字母数字与-）: [$1]" >&2; return 1; }; }

# Q1 / Q1′ 的 WHERE 谓词（作用于别名 q）
q1_pred(){ printf "EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id = q.id AND (lower(li.product_part_no_snapshot) LIKE '%%%s%%' OR lower(li.customer_part_no) LIKE '%%%s%%'))" "$1" "$1"; }
q1p_pred(){ printf "EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id = q.id AND (lower(li.product_part_no_snapshot) LIKE '%%%s%%' OR lower(li.customer_part_no) LIKE '%%%s%%' OR EXISTS (SELECT 1 FROM customer c JOIN ds_quote_material m ON m.customer_no = c.code WHERE c.id = q.customer_id AND m.material_no = li.product_part_no_snapshot AND lower(m.production_no) LIKE '%%%s%%')))" "$1" "$1" "$1"; }
# 「只经生产料号这一路」的谓词（AC-4 拆解用；是 Q1′ 第三个分支的原样摘出）
qprod_pred(){ printf "EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id = q.id AND EXISTS (SELECT 1 FROM customer c JOIN ds_quote_material m ON m.customer_no = c.code WHERE c.id = q.customer_id AND m.material_no = li.product_part_no_snapshot AND lower(m.production_no) LIKE '%%%s%%'))" "$1"; }

Q0_SQL="SELECT count(*) FROM quotation;"
q1_sql(){  kw_check "$1" || return 1; printf "SELECT count(*) FROM quotation q WHERE %s;" "$(q1_pred "$1")"; }
q1p_sql(){ kw_check "$1" || return 1; printf "SELECT count(*) FROM quotation q WHERE %s;" "$(q1p_pred "$1")"; }
q1_set_sql(){  kw_check "$1" || return 1; printf "SELECT q.quotation_number FROM quotation q WHERE %s ORDER BY 1;" "$(q1_pred "$1")"; }
q1p_set_sql(){ kw_check "$1" || return 1; printf "SELECT q.quotation_number FROM quotation q WHERE %s ORDER BY 1;" "$(q1p_pred "$1")"; }
qprod_sql(){ kw_check "$1" || return 1; printf "SELECT count(*) FROM quotation q WHERE %s;" "$(qprod_pred "$1")"; }
qboth_sql(){ kw_check "$1" || return 1; printf "SELECT count(*) FROM quotation q WHERE %s AND %s;" "$(q1_pred "$1")" "$(qprod_pred "$1")"; }
# Q-漏客户（AC-5 对照值 —— 错误实现会得到的数，任务.md §⑦ 原文）
qleak_sql(){ kw_check "$1" || return 1; printf "SELECT count(DISTINCT li.quotation_id) FROM quotation_line_item li JOIN ds_quote_material m ON m.material_no = li.product_part_no_snapshot WHERE lower(m.production_no) LIKE '%%%s%%';" "$1"; }

# ---------------------------------------------------------------------
# psql 包装
# ---------------------------------------------------------------------
_psql(){ psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "$1" 2>&1; }

# 单值数字查询。失败或非数字一律返回非 0（不许把空串当 0 —— 经典假绿）。
sql1(){
  local out rc
  out=$(_psql "$1"); rc=$?
  if [ $rc -ne 0 ]; then echo "  ${C_NG}psql 执行失败(rc=$rc)${C_OFF}: $out" >&2; return 1; fi
  out=$(printf '%s' "$out" | tr -d '[:space:]')
  if ! printf '%s' "$out" | /usr/bin/grep -aqE '^-?[0-9]+$'; then
    echo "  ${C_NG}psql 返回的不是数字${C_OFF}: [$out]" >&2; return 1
  fi
  printf '%s' "$out"
}
# 多行单列查询，每行一个值。失败返回非 0。
sqlrows(){
  local out rc
  out=$(_psql "$1"); rc=$?
  if [ $rc -ne 0 ]; then echo "  ${C_NG}psql 执行失败(rc=$rc)${C_OFF}: $out" >&2; return 1; fi
  printf '%s\n' "$out" | sed '/^[[:space:]]*$/d'
}

# 把一段 SQL + 其结果追加到本用例的证据文件
evid_sql(){ # evid_sql <file> <label> <sql> <value>
  { echo "-- [$(date -Is)] $2"; echo "$3"; echo "=> $4"; echo; } >> "$EVID/$1"
}

# ---------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------
JAR_ADMIN=""
login(){ # login <user> <pass> → 打印 cookie jar 路径
  local u="$1" p="$2" jar code
  jar=$(mktemp)
  code=$(curl -s --noproxy '*' -c "$jar" -o /dev/null -w '%{http_code}' \
        -X POST "$BACKEND/api/cpq/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$u\",\"password\":\"$p\"}")
  if [ "$code" != "200" ]; then echo "  ${C_NG}登录失败${C_OFF} $u -> HTTP $code" >&2; rm -f "$jar"; return 1; fi
  printf '%s' "$jar"
}

HTTP_CODE=""; API_BODY=""; API_URL=""
# api_get <querystring> [<证据文件名，不含扩展名>]
# ⚠️ 不能在 $( ) 里调用 —— 子 shell 回写不了全局变量（经典坑）
api_get(){
  local qs="$1" tag="${2:-}" tmp
  tmp=$(mktemp)
  API_URL="$BACKEND/api/cpq/quotations?$qs"
  HTTP_CODE=$(curl -s --noproxy '*' -b "$JAR_ADMIN" -o "$tmp" -w '%{http_code}' "$API_URL")
  API_BODY=$(cat "$tmp"); rm -f "$tmp"
  echo "  URL     : $API_URL"
  echo "  HTTP    : $HTTP_CODE"
  if [ -n "$tag" ]; then
    { echo "# [$(date -Is)] GET $API_URL"; echo "# HTTP $HTTP_CODE"; printf '%s\n' "$API_BODY"; } > "$EVID/$tag.response.txt"
  fi
}

# 从 API_BODY 取字段。取不到返回非 0（绝不静默当 0）。
#   api_total           → totalElements
#   api_numbers         → content 里的 quotationNumber，每行一个（保持返回顺序，不去重）
#   api_content_len     → content 数组长度
_api_py(){
  printf '%s' "$API_BODY" | python3 -c "$1"
}
api_total(){
  _api_py '
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print("PARSE_ERROR:%s"%e); sys.exit(2)
if d.get("code")!=200: print("BIZ_CODE_%s:%s"%(d.get("code"),d.get("message"))); sys.exit(3)
t=(d.get("data") or {}).get("totalElements")
if t is None: print("NO_TOTAL"); sys.exit(4)
print(t)'
}
api_numbers(){
  _api_py '
import sys,json
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content")
if c is None: sys.exit(4)
for r in c: print(r.get("quotationNumber"))'
}
api_content_len(){
  _api_py '
import sys,json
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content")
if c is None: print("NO_CONTENT"); sys.exit(4)
print(len(c))'
}

summary(){
  echo; hr
  echo "S-API(task-260922) 汇总：PASS=$PASS_N  FAIL=$FAIL_N  未验证=$UNVERIFIED_N  不稳定=$UNSTABLE_N"
  hr
  for l in "${RESULT_LINES[@]:-}"; do [ -n "$l" ] && echo "  $l"; done
  hr
  echo "库: $DB_HOST/$DB_NAME   后端: $BACKEND   时刻: $(date -Is)"
  echo "证据目录: $EVID"
  [ "$FAIL_N" -gt 0 ] && return 1
  return 0
}
