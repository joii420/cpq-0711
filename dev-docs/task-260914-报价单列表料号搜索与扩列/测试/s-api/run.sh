#!/usr/bin/env bash
# =====================================================================
# task-260914 · 分片 S-API（后端接口层）· 测试用例
# 覆盖 AC：2 / 3 / 4 / 6 / 7 / 13 / 14 / 15 / 18 / 19 / 21
#
# 🚨 纯只读片：全脚本只有 GET + SELECT，不建单、不改数据、不删数据。
#    唯一非 GET 是 POST /auth/login（取会话，接口测试绕不开）。
#
# 用法：
#   bash run.sh                 # 跑全部
#   bash run.sh TC-API-01 TC-API-07   # 只跑指定用例
#   S_API_BACKEND=http://localhost:8081 bash run.sh   # 打到别的后端（见 README「证伪实验」）
#
# 🚫 期望值一律现场从基准 SQL 重算，**不硬编码立项日采样值**（cpq_db_0724 是共享开发库）。
# =====================================================================
cd "$(dirname "$0")" || exit 2
# shellcheck source=lib.sh
. ./lib.sh

WANT=("$@")
want(){ [ ${#WANT[@]} -eq 0 ] && return 0; for w in "${WANT[@]}"; do [ "$w" = "$1" ] && return 0; done; return 1; }

echo "=== task-260914 · S-API 用例集 ==="
echo "后端: $BACKEND    库: $DB_HOST/$DB_NAME    开跑: $(date -Is)"

# ---------------------------------------------------------------------
# 前置 0：后端存活 + 登录
# 判后端健康看业务端点返 401（/q/health 返 404 是正常的，它不是健康探针）
# ---------------------------------------------------------------------
PROBE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BACKEND/api/cpq/quotations")
echo "存活探针（未登录期望 401）: $PROBE"
if [ "$PROBE" != "401" ] && [ "$PROBE" != "200" ]; then
  echo "${C_NG}后端未就绪（$BACKEND 返回 $PROBE）。先在 worktree 里起 8195，见 README。${C_OFF}"
  exit 2
fi
JAR_ADMIN=$(login "$ADMIN_USER" "$ADMIN_PASS") || { echo "管理员登录失败，中止"; exit 2; }
trap 'rm -f "$JAR_ADMIN" "$JAR_SALES" 2>/dev/null' EXIT

# ---------------------------------------------------------------------
# 前置 1：现场取全量基线 Q0（后续「过滤是否真生效」的上界都用它）
# ---------------------------------------------------------------------
Q0=$(sql1 "SELECT count(*) FROM quotation;") || { echo "Q0 取不到，中止"; exit 2; }
echo "Q0 全量报价单（现场重算）= $Q0"
if [ "$Q0" -lt 10 ]; then
  echo "${C_WARN}⚠️ 全量只有 $Q0 单，样本过小，多数断言会退化。请先确认连的是 cpq_db_0724。${C_OFF}"
fi

# 料号命中的基准 SQL 模板（Q1）
q1(){ printf "SELECT count(*) FROM quotation q WHERE EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id=q.id AND (LOWER(li.product_part_no_snapshot) LIKE '%%%s%%' OR LOWER(li.customer_part_no) LIKE '%%%s%%'));" "$1" "$1"; }

# =====================================================================
# TC-API-01 / AC-2 · 料号搜索 S0004（销售料号路）
# AC 原文：列表只剩含该销售料号的单；「共 N 条」= 基准 SQL Q1 现场重算值；
#          随机抽其中 1 单打开，其产品行里确实存在料号 S0004
# =====================================================================
if want TC-API-01; then
  tc_begin "TC-API-01" "AC-2" "料号框输入 S0004 → 命中数 = Q1('s0004')，且抽样单确实含该料号"
  assert_count "TC-API-01" "AC-2" "partNo=S0004&page=0&size=20" "$(q1 s0004)" "$Q0"

  # 回库核实（对应 AC-2「列表只剩含该销售料号的单」+ 后半句「抽 1 单确认产品行里确有该料号」）
  # 🚨 这里**不能只抽 1 单**：改动前基线（参数被忽略、返回全量）时，首行恰好含 S0004，
  #    只抽 1 单会 PASS —— 证伪实验实测到过这个假绿。改成「返回的每一单都必须含该料号」。
  echo "  —— 回库逐单核实（接口返回的整页单 → 回库查各自行项）——"
  api_get "$JAR_ADMIN" "partNo=S0004&page=0&size=20"
  IDS=$(printf '%s' "$API_BODY" | python3 -c '
import sys,json
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content") or []
print(" ".join("%s|%s"%(r["id"],r["quotationNumber"]) for r in c))')
  if [ -z "${IDS// /}" ]; then
    ng "TC-API-01-逐单" "接口一条都没返回，逐单核实无从谈起（「断言从未执行」的假绿形态）"
  else
    BAD=0; TOTALCHK=0
    for pair in $IDS; do
      SID=${pair%%|*}; SNO=${pair##*|}; TOTALCHK=$((TOTALCHK+1))
      HITROWS=$(sql1 "SELECT count(*) FROM quotation_line_item WHERE quotation_id='$SID' AND (LOWER(product_part_no_snapshot) LIKE '%s0004%' OR LOWER(customer_part_no) LIKE '%s0004%');")
      if [ -z "$HITROWS" ] || [ "$HITROWS" -lt 1 ]; then
        BAD=$((BAD+1)); echo "    ✗ $SNO 行项里没有 S0004"
      fi
    done
    echo "  逐单核实: 共查 $TOTALCHK 单，不含 S0004 的有 $BAD 单"
    if [ "$BAD" -eq 0 ]; then ok "TC-API-01-逐单" "整页 $TOTALCHK 单全部确实含 S0004"
    else ng "TC-API-01-逐单" "$BAD/$TOTALCHK 单根本不含 S0004 —— 接口把不该命中的单返回了"; fi
  fi
fi

# =====================================================================
# TC-API-02 / AC-3 · 料号搜索 A002（客户料号路）
# AC 原文：命中数 = Q1；抽 1 单确认其**客户料号**列为 A002
#          （验证搜的是客户料号那一路，不是销售料号）
# ⭐ 本环境实测 a002 在销售料号里 0 命中、只在客户料号里命中 ⇒ 天然是强判据：
#    若后端只搜了销售料号，这条会返回 0，与基准值 35 明显分叉。
# =====================================================================
if want TC-API-02; then
  tc_begin "TC-API-02" "AC-3" "料号框输入 A002 → 命中数 = Q1('a002')，且命中来自 customer_part_no"
  VIA_SALES=$(sql1 "SELECT count(DISTINCT quotation_id) FROM quotation_line_item WHERE LOWER(product_part_no_snapshot) LIKE '%a002%';")
  VIA_CUST=$(sql1 "SELECT count(DISTINCT quotation_id) FROM quotation_line_item WHERE LOWER(customer_part_no) LIKE '%a002%';")
  echo "  命中路径拆解: 经销售料号 $VIA_SALES 单 / 经客户料号 $VIA_CUST 单"
  if [ "$VIA_CUST" = "0" ]; then
    unv "TC-API-02" "库里已无「只经客户料号命中」的样本，本条失去区分力（不计 PASS）"
  else
    assert_count "TC-API-02" "AC-3" "partNo=A002&page=0&size=20" "$(q1 a002)" "$Q0"
    # 🚨 同 TC-API-01：只抽 1 单会在「参数被忽略」时碰巧 PASS（证伪实验实测到过），改成逐单
    echo "  —— 逐单核实客户料号列 ——"
    api_get "$JAR_ADMIN" "partNo=A002&page=0&size=20"
    IDS=$(printf '%s' "$API_BODY" | python3 -c '
import sys,json
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content") or []
print(" ".join("%s|%s"%(r["id"],r["quotationNumber"]) for r in c))')
    if [ -z "${IDS// /}" ]; then
      ng "TC-API-02-逐单" "接口零返回，逐单核实无从谈起"
    else
      BAD=0; TOTALCHK=0; SAMPLEVAL=""
      for pair in $IDS; do
        SID=${pair%%|*}; SNO=${pair##*|}; TOTALCHK=$((TOTALCHK+1))
        CPN=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 \
              -c "SELECT COALESCE(string_agg(DISTINCT customer_part_no,','),'') FROM quotation_line_item WHERE quotation_id='$SID' AND LOWER(customer_part_no) LIKE '%a002%';")
        CPN=$(printf '%s' "$CPN" | tr -d '[:space:]')
        if [ -z "$CPN" ]; then BAD=$((BAD+1)); echo "    ✗ $SNO 的客户料号里没有 a002";
        else [ -z "$SAMPLEVAL" ] && SAMPLEVAL="$SNO→$CPN"; fi
      done
      echo "  逐单核实: 共查 $TOTALCHK 单，客户料号不含 a002 的有 $BAD 单；样例 $SAMPLEVAL"
      if [ "$BAD" -eq 0 ]; then ok "TC-API-02-逐单" "整页 $TOTALCHK 单的客户料号都含 A002（走的是客户料号那一路）"
      else ng "TC-API-02-逐单" "$BAD/$TOTALCHK 单的客户料号里没有 a002"; fi
    fi
  fi
fi

# =====================================================================
# TC-API-03 / AC-4 · 小写 s000 → 模糊包含 + 大小写不敏感
# AC 原文：命中数 = Q1('s000')，且 > AC-2 的命中数
# =====================================================================
if want TC-API-03; then
  tc_begin "TC-API-03" "AC-4" "小写 s000 → 命中数 = Q1('s000') 且严格大于 S0004 的命中数；再验大小写两向一致"
  N_S0004=$(sql1 "$(q1 s0004)")
  echo "  参照值 Q1('s0004') = $N_S0004"
  assert_count "TC-API-03" "AC-4" "partNo=s000&page=0&size=20" "$(q1 s000)" "$Q0"

  api_get "$JAR_ADMIN" "partNo=s000&page=0&size=1"; T_LOWER=$(api_total)
  api_get "$JAR_ADMIN" "partNo=S000&page=0&size=1"; T_UPPER=$(api_total)
  echo "  小写 s000 = $T_LOWER    大写 S000 = $T_UPPER    S0004 = $N_S0004    全量 = $Q0"
  # 🚨 前置守卫：参数被忽略时两向都返全量 187，「大小写一致」会平凡成立 —— 证伪实验实测到过这个假绿。
  #    必须先确认过滤真的生效（结果 ≠ 全量），这条比较才有意义。
  if ! printf '%s' "$T_LOWER" | /usr/bin/grep -aqE '^[0-9]+$'; then
    ng "TC-API-03-大小写" "接口返回不可解析：$T_LOWER"
  elif [ "$T_LOWER" = "$Q0" ] && [ "$T_UPPER" = "$Q0" ]; then
    ng "TC-API-03-大小写" "两向都等于全量 $Q0 → 料号参数根本没生效，「大小写一致」是平凡成立，不算通过"
  elif [ "$T_LOWER" != "$T_UPPER" ]; then
    ng "TC-API-03-大小写" "大小写不一致（$T_LOWER vs $T_UPPER）→ 大小写敏感了"
  elif [ "$T_LOWER" -le "$N_S0004" ]; then
    ng "TC-API-03-模糊" "s000($T_LOWER) 未严格大于 S0004($N_S0004) → 像是精确匹配而非中间段模糊"
  else
    ok "TC-API-03-大小写/模糊" "s000=$T_LOWER = S000=$T_UPPER 且 > S0004=$N_S0004（模糊 + 大小写不敏感成立）"
  fi
fi

# =====================================================================
# TC-API-04 / AC-6 · 料号 ∧ 关键字（AND，不是 OR）
# AC 原文：结果是两个条件的交集，命中数 ≤ AC-2 的命中数，且 = 基准 SQL Q2 值
# ⭐ 关键字不写死：现场从「S0004 命中单」里挑一个**只覆盖其中一部分**的客户名，
#    这样才能同时证伪 OR（会 > 43）和证伪「关键字被忽略」（会 = 43）。
# =====================================================================
if want TC-API-04; then
  tc_begin "TC-API-04" "AC-6" "partNo=S0004 ∧ keyword=<子集客户名> → 严格交集，且严格小于单料号命中数"
  N_S0004=$(sql1 "$(q1 s0004)")
  KW=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "
    WITH hit AS (SELECT q.* FROM quotation q WHERE EXISTS (
        SELECT 1 FROM quotation_line_item li WHERE li.quotation_id=q.id
          AND (LOWER(li.product_part_no_snapshot) LIKE '%s0004%' OR LOWER(li.customer_part_no) LIKE '%s0004%')))
    SELECT snapshot_customer_name FROM hit
    WHERE snapshot_customer_name IS NOT NULL
    GROUP BY 1 HAVING count(*) < $N_S0004 AND count(*) > 0
    ORDER BY count(*) DESC LIMIT 1;")
  KW=$(printf '%s' "$KW" | sed 's/^ *//;s/ *$//')
  echo "  现场挑中的关键字（S0004 单的真子集）: [$KW]"
  if [ -z "$KW" ]; then
    unv "TC-API-04" "库里挑不到「只覆盖 S0004 单一部分」的客户名，AND 的区分力无法构造（不计 PASS）"
  else
    KWE=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$KW")
    Q2="SELECT count(*) FROM quotation q WHERE EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id=q.id AND (LOWER(li.product_part_no_snapshot) LIKE '%s0004%' OR LOWER(li.customer_part_no) LIKE '%s0004%')) AND (q.snapshot_customer_name ILIKE '%$KW%' OR q.name ILIKE '%$KW%' OR q.quotation_number ILIKE '%$KW%');"
    # 上界用 N_S0004：若后端把 keyword 丢了，结果会等于 43 → 被上界挡下判 FAIL
    assert_count "TC-API-04" "AC-6" "partNo=S0004&keyword=$KWE&page=0&size=20" "$Q2" "$N_S0004"
    # 另一侧证伪：若两个条件被做成 OR，结果会 > 单条件命中数
    api_get "$JAR_ADMIN" "keyword=$KWE&page=0&size=1"; T_KW_ONLY=$(api_total)
    echo "  仅 keyword 命中 = $T_KW_ONLY   仅 partNo 命中 = $N_S0004"
    api_get "$JAR_ADMIN" "partNo=S0004&keyword=$KWE&page=0&size=1"; T_BOTH=$(api_total)
    echo "  两者同时 = $T_BOTH"
    if printf '%s' "$T_BOTH$T_KW_ONLY" | /usr/bin/grep -aqE '^[0-9]+$' && [ "$T_BOTH" -le "$T_KW_ONLY" ] && [ "$T_BOTH" -le "$N_S0004" ]; then
      ok "TC-API-04-AND语义" "组合($T_BOTH) ≤ 各单条件($N_S0004 / $T_KW_ONLY)，是 AND 不是 OR"
    else
      ng "TC-API-04-AND语义" "组合($T_BOTH) 超过了某个单条件 → 疑似 OR 语义"
    fi
  fi
fi

# =====================================================================
# TC-API-05 / AC-7 · 料号 ∧ 状态页签，且切回后料号条件不被清空
# AC 原文：结果 = 料号 ∧ status='DRAFT'，= Q3；再切回「全部」，命中数回到 AC-2 的值
# =====================================================================
if want TC-API-05; then
  tc_begin "TC-API-05" "AC-7" "partNo=S0004 ∧ status=DRAFT → Q3；去掉 status 后回到 Q1（料号条件不被吃掉）"
  N_S0004=$(sql1 "$(q1 s0004)")
  Q3="SELECT count(*) FROM quotation q WHERE q.status='DRAFT' AND EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id=q.id AND (LOWER(li.product_part_no_snapshot) LIKE '%s0004%' OR LOWER(li.customer_part_no) LIKE '%s0004%'));"
  assert_count "TC-API-05" "AC-7" "partNo=S0004&status=DRAFT&page=0&size=20" "$Q3" "$N_S0004"

  echo "  —— 切回「全部」（去掉 status），料号条件必须还在 ——"
  api_get "$JAR_ADMIN" "partNo=S0004&page=0&size=1"; T_BACK=$(api_total)
  echo "  切回后 = $T_BACK    期望 = $N_S0004（Q1 现场值）    全量 = $Q0"
  if [ "$T_BACK" = "$N_S0004" ] && [ "$T_BACK" != "$Q0" ]; then
    ok "TC-API-05-回切" "切回全部后仍为 $T_BACK（料号条件未被清空，也没退化成全量 $Q0）"
  else
    ng "TC-API-05-回切" "切回后 $T_BACK ≠ Q1 $N_S0004（若等于全量 $Q0 说明料号条件被清空了）"
  fi
fi

# =====================================================================
# TC-API-06 / AC-13 · 分类筛选 = 具体分类
# AC 原文：命中数 = Q5；列表每一行的「产品分类」列都显示该分类名
# ⭐ 逐行校验走全量（size=500），不是只看首页 —— 只看首页等于没验服务端过滤
# =====================================================================
if want TC-API-06; then
  tc_begin "TC-API-06" "AC-13" "categoryId=<默认分类> → 命中数 = Q5，且返回的每一行 categoryName 都是该分类名"
  CAT=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "
    SELECT pc.id||'|'||pc.name FROM product_category pc
    WHERE pc.status='ACTIVE'
    ORDER BY (SELECT count(*) FROM quotation q WHERE q.product_category_id=pc.id) DESC, pc.name LIMIT 1;")
  CAT=$(printf '%s' "$CAT" | tr -d '[:space:]')
  CID=${CAT%%|*}; CNAME=${CAT##*|}
  echo "  现场选中的分类: $CNAME ($CID)  —— 取「名下单最多的 ACTIVE 分类」，不写死名字"
  if [ -z "$CID" ]; then
    unv "TC-API-06" "库里没有 ACTIVE 分类，无法构造"
  else
    assert_count "TC-API-06" "AC-13" "categoryId=$CID&page=0&size=20" \
      "SELECT count(*) FROM quotation WHERE product_category_id='$CID';" "$Q0"
    echo "  —— 逐行校验（拉全量，不是只看首页）——"
    fetch_all_rows "$JAR_ADMIN" "categoryId=$CID"
    printf '%s' "$API_BODY" | python3 -c '
import sys,json
cid,cname=sys.argv[1],sys.argv[2]
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content") or []
print("  返回行数: %d"%len(c))
if not c: print("  ROWCHECK=EMPTY"); sys.exit(0)
bad_id=[r["quotationNumber"] for r in c if r.get("categoryId")!=cid]
bad_nm=[(r["quotationNumber"],r.get("categoryName")) for r in c if r.get("categoryName")!=cname]
print("  categoryId 不符的行: %d %s"%(len(bad_id),bad_id[:5]))
print("  categoryName 不符的行: %d %s"%(len(bad_nm),bad_nm[:5]))
print("  样例前3行 categoryName: %s"%[r.get("categoryName") for r in c[:3]])
print("  ROWCHECK=%s"%("OK" if not bad_id and not bad_nm else "BAD"))' "$CID" "$CNAME" > /tmp/s_api_rowchk.$$ 2>&1
    cat /tmp/s_api_rowchk.$$
    RC=$(/usr/bin/grep -a -o 'ROWCHECK=[A-Z]*' /tmp/s_api_rowchk.$$ | tail -1)
    rm -f /tmp/s_api_rowchk.$$
    case "$RC" in
      ROWCHECK=OK)    ok  "TC-API-06-逐行" "全部返回行的 categoryId/categoryName 都是 $CNAME" ;;
      ROWCHECK=EMPTY) ng  "TC-API-06-逐行" "零行返回 —— 逐行断言没跑（假绿形态）" ;;
      ROWCHECK=BAD)   ng  "TC-API-06-逐行" "存在分类不符的行（见上方清单）" ;;
      *)              ng  "TC-API-06-逐行" "逐行校验没产出结论（可能是 categoryName 字段还没实现）" ;;
    esac
  fi
fi

# =====================================================================
# TC-API-07 / AC-14 · 分类筛选 = 「未分类」（字面量 NONE）
# AC 原文：命中数 = Q6；列表每一行的「产品分类」列都显示 —（即 categoryName 为 null）
# =====================================================================
if want TC-API-07; then
  tc_begin "TC-API-07" "AC-14" "categoryId=NONE → 命中数 = Q6(IS NULL)，且每一行 categoryId/categoryName 均为 null"
  assert_count "TC-API-07" "AC-14" "categoryId=NONE&page=0&size=20" \
    "SELECT count(*) FROM quotation WHERE product_category_id IS NULL;" "$Q0"
  echo "  —— 逐行校验（拉全量）——"
  fetch_all_rows "$JAR_ADMIN" "categoryId=NONE"
  printf '%s' "$API_BODY" | python3 -c '
import sys,json
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content") or []
print("  返回行数: %d"%len(c))
if not c: print("  ROWCHECK=EMPTY"); sys.exit(0)
bad=[(r["quotationNumber"],r.get("categoryId"),r.get("categoryName")) for r in c
     if r.get("categoryId") is not None or r.get("categoryName") is not None]
print("  非空分类的行: %d %s"%(len(bad),bad[:5]))
print("  ROWCHECK=%s"%("OK" if not bad else "BAD"))' > /tmp/s_api_rowchk.$$ 2>&1
  cat /tmp/s_api_rowchk.$$
  RC=$(/usr/bin/grep -a -o 'ROWCHECK=[A-Z]*' /tmp/s_api_rowchk.$$ | tail -1); rm -f /tmp/s_api_rowchk.$$
  case "$RC" in
    ROWCHECK=OK)    ok "TC-API-07-逐行" "全部返回行的分类均为空（前端应渲染「—」）" ;;
    ROWCHECK=EMPTY) ng "TC-API-07-逐行" "零行返回 —— 逐行断言没跑" ;;
    *)              ng "TC-API-07-逐行" "存在分类非空的行 → NONE 语义没落实" ;;
  esac
fi

# =====================================================================
# TC-API-08 / AC-15 · 模板筛选 —— **按模板系列聚合**（C-3 裁决，2026-09-14）
# AC 原文：模板筛选选「正泰测试模板1」（该系列有 v1.0/v1.1/v1.2 三个版本，名下 18/9/32 单）
#          → 命中数 = Q7 值（= 三个版本之和，🚫 不是单版本的 32）；每行「报价模板」列显示该名（不带版本号）
#          「这条专验『按系列聚合』真的生效 —— 若实现退回按单个模板 ID 过滤，这里会得到 32 而不是 59」
#
# ⭐ 判别性设计：期望值不但要 == 按系列的 SQL 值，还必须 **严格大于该系列中任一单版本的最大单数**。
#    这一条专门抓「退回按单模板 ID 过滤」—— 那种实现会返回 32，数值上跟 59 分叉，被硬失败挡下。
#    🚨 选系列必须按**判别力**排序（`quotes - max_one` 最大），不是按 quotes 最大 ——
#       证伪实验实测：按 quotes 排会挑中「报价模板·ds原生v1.0」（3 版本但 63 单全在一个版本上，
#       63-63=0），按系列和按单 ID 返回同一个数字，这条断言当场退化成恒真。
#    挑不到有判别力的系列时 ⇒ 判未验证，不计 PASS。
if want TC-API-08; then
  tc_begin "TC-API-08" "AC-15" "templateSeriesId=<多版本系列> → 命中数 = 该系列全部版本之和，且严格大于任一单版本"
  SER=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "
    SELECT s.sid||'|'||s.nm||'|'||s.vers||'|'||s.quotes||'|'||s.max_one
    FROM (
      SELECT t.template_series_id AS sid, min(t.name) AS nm, count(DISTINCT t.id) AS vers,
             (SELECT count(*) FROM quotation q JOIN template t2 ON t2.id=q.customer_template_id
               WHERE t2.template_series_id=t.template_series_id) AS quotes,
             (SELECT COALESCE(max(c),0) FROM (
                SELECT count(*) AS c FROM quotation q3 JOIN template t3 ON t3.id=q3.customer_template_id
                 WHERE t3.template_series_id=t.template_series_id GROUP BY t3.id) x) AS max_one
      FROM template t
      WHERE t.template_kind='QUOTATION' AND t.template_series_id IS NOT NULL
      GROUP BY t.template_series_id
    ) s
    WHERE s.vers > 1 AND s.quotes > s.max_one
    ORDER BY (s.quotes - s.max_one) DESC, s.quotes DESC LIMIT 1;")
  SER=$(printf '%s' "$SER" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  if [ -z "$SER" ]; then
    unv "TC-API-08" "库里已无「多版本」的 QUOTATION 模板系列，「按系列聚合」这条失去判别力（不计 PASS）"
  else
    TSID=$(printf '%s' "$SER" | cut -d'|' -f1)
    TNAME=$(printf '%s' "$SER" | cut -d'|' -f2)
    TVERS=$(printf '%s' "$SER" | cut -d'|' -f3)
    TQ=$(printf '%s' "$SER"  | cut -d'|' -f4)
    TMAX1=$(printf '%s' "$SER" | cut -d'|' -f5)
    echo "  现场选中的系列: $TNAME"
    echo "  series_id     : $TSID"
    echo "  版本数 $TVERS 个 / 按系列合计 $TQ 单 / 单版本最多的那个 $TMAX1 单"

    Q7="SELECT count(*) FROM quotation q JOIN template t ON t.id=q.customer_template_id WHERE t.template_series_id='$TSID';"
    assert_count "TC-API-08" "AC-15" "templateSeriesId=$TSID&page=0&size=20" "$Q7" "$Q0"

    # 🚨 判别性断言：抓「退回按单个模板 ID 过滤」
    echo "  —— 判别性核验：按系列 vs 按单版本 ——"
    api_get "$JAR_ADMIN" "templateSeriesId=$TSID&page=0&size=1"; T_SER=$(api_total)
    echo "  接口值 $T_SER   按系列应为 $TQ   退回按单 ID 会是 $TMAX1"
    if ! printf '%s' "$T_SER" | /usr/bin/grep -aqE '^[0-9]+$'; then
      ng "TC-API-08-系列聚合" "接口返回不可解析：$T_SER"
    elif [ "$TQ" -le "$TMAX1" ]; then
      unv "TC-API-08-系列聚合" "该系列各版本单数分布使 合计($TQ) 未超过单版本最大值($TMAX1)，判别力不足（不计 PASS）"
    elif [ "$T_SER" = "$TMAX1" ]; then
      ng "TC-API-08-系列聚合" "接口返回 $T_SER = 单版本最大值 → 实现退回了按单个模板 ID 过滤，另外 $((TQ-TMAX1)) 单被静默丢掉"
    elif [ "$T_SER" != "$TQ" ]; then
      ng "TC-API-08-系列聚合" "接口 $T_SER ≠ 按系列合计 $TQ（也不等于单版本 $TMAX1，口径不明）"
    else
      ok "TC-API-08-系列聚合" "接口 $T_SER == 按系列合计 $TQ，严格大于单版本最大值 $TMAX1（聚合真的生效）"
    fi

    echo "  —— 逐行校验（拉全量）——"
    VERIDS=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 \
             -c "SELECT string_agg(id::text,',') FROM template WHERE template_series_id='$TSID';")
    VERIDS=$(printf '%s' "$VERIDS" | tr -d '[:space:]')
    echo "  该系列的版本 id 集合: $VERIDS"
    fetch_all_rows "$JAR_ADMIN" "templateSeriesId=$TSID"
    printf '%s' "$API_BODY" | python3 -c '
import sys,json
ids=set(x for x in sys.argv[1].split(",") if x); tname=sys.argv[2]
d=json.load(sys.stdin); c=(d.get("data") or {}).get("content") or []
print("  返回行数: %d"%len(c))
if not c: print("  ROWCHECK=EMPTY"); sys.exit(0)
bad_id=[(r["quotationNumber"],r.get("customerTemplateId")) for r in c if r.get("customerTemplateId") not in ids]
bad_nm=[(r["quotationNumber"],r.get("templateName")) for r in c if r.get("templateName")!=tname]
used=sorted(set(str(r.get("customerTemplateId")) for r in c))
print("  模板 id 不在本系列内的行: %d %s"%(len(bad_id),bad_id[:5]))
print("  templateName 不符的行: %d %s"%(len(bad_nm),bad_nm[:5]))
print("  实际用到的版本 id 个数: %d（多于 1 个才说明真的跨版本聚合了）"%len(used))
verlike=[x for x in set(r.get("templateName") for r in c) if x and ("v1." in str(x).lower() or "版本" in str(x))]
print("  疑似带版本号的模板名（D-8 禁止）: %s"%verlike)
print("  DISTINCT_VER=%d"%len(used))
print("  ROWCHECK=%s"%("OK" if not bad_id and not bad_nm and not verlike else "BAD"))' "$VERIDS" "$TNAME" > /tmp/s_api_rowchk.$$ 2>&1
    cat /tmp/s_api_rowchk.$$
    RC=$(/usr/bin/grep -a -o 'ROWCHECK=[A-Z]*' /tmp/s_api_rowchk.$$ | tail -1)
    DV=$(/usr/bin/grep -a -o 'DISTINCT_VER=[0-9]*' /tmp/s_api_rowchk.$$ | tail -1 | cut -d= -f2)
    rm -f /tmp/s_api_rowchk.$$
    case "$RC" in
      ROWCHECK=OK)    ok "TC-API-08-逐行" "全部返回行的模板都属于本系列，templateName 均为 $TNAME 且不带版本号" ;;
      ROWCHECK=EMPTY) ng "TC-API-08-逐行" "零行返回 —— 逐行断言没跑" ;;
      *)              ng "TC-API-08-逐行" "存在模板不符 / 名称带版本号的行（见上方清单）" ;;
    esac
    # 🚨 「跨了 N 个版本」必须**先确认返回行都属于本系列**才有意义：
    #    过滤没生效返全量时，21 个模板全在里面，DV 当然 >1 —— 证伪实验实测到过这个假绿。
    #    所以本条挂在 ROWCHECK=OK 之下，且 DV 必须 ≤ 本系列版本数（否则说明混进了别的系列）。
    if [ "$RC" != "ROWCHECK=OK" ]; then
      unv "TC-API-08-跨版本" "逐行校验未通过，返回集不纯，「跨了几个版本」此时无意义（不计 PASS）"
    elif [ -z "$DV" ]; then
      unv "TC-API-08-跨版本" "没数出版本 id 个数（逐行校验没产出结论）"
    elif [ "$DV" -gt "$TVERS" ]; then
      ng "TC-API-08-跨版本" "返回行用到 $DV 个版本 id，超过本系列的 $TVERS 个 —— 混进了别的系列"
    elif [ "$DV" -le 1 ]; then
      ng "TC-API-08-跨版本" "返回行只用到 $DV 个版本 id —— 没有跨版本聚合，与「按系列」口径不符"
    else
      ok "TC-API-08-跨版本" "返回行跨了 $DV 个版本 id（≤ 本系列 $TVERS 个，且逐行均属本系列）—— 按系列聚合的正向证据"
    fi
  fi
fi

# =====================================================================
# TC-API-09 / AC-18 · N+1 红线
# AC 原文：本次请求发出的 SQL 条数是常数，与返回行数无关：
#          1 count + 1 主查询 + ≤2 条字典批量查询；🚫 不得出现「每行一条」的分类/模板查询
#
# 手法：后端必须以 -Dquarkus.hibernate-orm.log.sql=true 启动且日志 tee 到文件；
#       静默窗口 → 打一次 20 行请求 → 数 SQL；再打一次 1 行请求 → 数 SQL；两者必须相等。
# 🚨 反假绿：日志里一条 SQL 都没有时，判「未验证」而不是「0 条 → 完美」。
# =====================================================================
if want TC-API-09; then
  tc_begin "TC-API-09" "AC-18" "同一查询返回 20 行 vs 1 行，SQL 条数必须不变（N+1 红线）"
  LOG="${S_API_SQLLOG:-}"
  if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
    unv "TC-API-09" "未提供后端 SQL 日志（S_API_SQLLOG 未设或文件不存在）—— 见 README 的启动命令"
  else
    SQLRE='^\[Hibernate\] |^Hibernate: '
    count_sql(){ # count_sql <起始行号> ; 回显「总条数 分类查询数 模板查询数」
      local from="$1" tail_txt
      tail_txt=$(tail -n "+$from" "$LOG")
      local tot cat tpl
      # ⚠️ Quarkus 的 quarkus.hibernate-orm.log.sql=true 打出来的前缀是 `[Hibernate] `，
      #    不是裸 Hibernate 的 `Hibernate: `。首轮实跑按后者数得 0 条，被守卫判「未验证」拦下（没当成假绿）。
      tot=$(printf '%s\n' "$tail_txt" | /usr/bin/grep -acE "$SQLRE")
      cat=$(printf '%s\n' "$tail_txt" | /usr/bin/grep -aE "$SQLRE" | /usr/bin/grep -aci 'from product_category')
      tpl=$(printf '%s\n' "$tail_txt" | /usr/bin/grep -aE "$SQLRE" | /usr/bin/grep -aci 'from template ')
      echo "$tot $cat $tpl"
    }
    quiesce(){ local a b; a=$(wc -l < "$LOG"); sleep 2; b=$(wc -l < "$LOG"); [ "$a" = "$b" ]; }

    if ! quiesce; then sleep 3; fi
    if ! quiesce; then
      unv "TC-API-09" "日志一直在增长（后端非空闲，可能有别人在打这台实例），SQL 计数不可信"
    else
      # A：返回 20 行
      MARK=$(( $(wc -l < "$LOG") + 1 ))
      api_get "$JAR_ADMIN" "page=0&size=20"
      ROWS_A=$(printf '%s' "$API_BODY" | python3 -c 'import sys,json;print(len((json.load(sys.stdin).get("data") or {}).get("content") or []))')
      sleep 2
      read -r TOT_A CAT_A TPL_A <<<"$(count_sql "$MARK")"
      echo "  [A] 返回 $ROWS_A 行 → SQL 共 $TOT_A 条（其中 product_category $CAT_A 条 / template $TPL_A 条）"

      # B：窄条件，只返回 1 行（用某张单的单号做 keyword，与新参数无关，避免互相掩盖）
      ONE=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 \
            -c "SELECT quotation_number FROM quotation WHERE product_category_id IS NOT NULL AND customer_template_id IS NOT NULL ORDER BY updated_at DESC LIMIT 1;")
      ONE=$(printf '%s' "$ONE" | tr -d '[:space:]')
      echo "  窄条件单号: $ONE"
      MARK=$(( $(wc -l < "$LOG") + 1 ))
      api_get "$JAR_ADMIN" "keyword=$ONE&page=0&size=20"
      ROWS_B=$(printf '%s' "$API_BODY" | python3 -c 'import sys,json;print(len((json.load(sys.stdin).get("data") or {}).get("content") or []))')
      sleep 2
      read -r TOT_B CAT_B TPL_B <<<"$(count_sql "$MARK")"
      echo "  [B] 返回 $ROWS_B 行 → SQL 共 $TOT_B 条（其中 product_category $CAT_B 条 / template $TPL_B 条）"

      if [ "$TOT_A" -eq 0 ] || [ "$TOT_B" -eq 0 ]; then
        unv "TC-API-09" "日志里一条 Hibernate: 都没数到 —— SQL 日志多半没开，0 条不等于没 N+1"
      elif [ "$ROWS_A" -le "$ROWS_B" ]; then
        unv "TC-API-09" "两次返回行数没拉开（$ROWS_A vs $ROWS_B），比不出「与行数无关」"
      elif [ "$TOT_A" -ne "$TOT_B" ]; then
        ng "TC-API-09" "SQL 条数随行数变化：$ROWS_A 行→$TOT_A 条，$ROWS_B 行→$TOT_B 条（N+1 红线）"
      elif [ "$CAT_A" -gt 2 ] || [ "$TPL_A" -gt 2 ]; then
        ng "TC-API-09" "字典查询超过 2 条（分类 $CAT_A / 模板 $TPL_A）→ 疑似逐行查字典"
      elif [ "$TOT_A" -gt 4 ]; then
        # 🚨 AC-18 的上限是绝对值：1 count + 1 主查询 + ≤2 字典 = **最多 4 条**。
        #    只断言「两次相等」是不够的 —— 一次实跑数到 19 条、两边相等，照样报绿（已修）。
        echo "  ⚠️ 超出 AC-18 上限，转储本次窗口的 SQL 原文："
        tail -n "+$MARK" "$LOG" | /usr/bin/grep -aE "$SQLRE" | cut -c1-140 | sed 's/^/      /'
        ng "TC-API-09" "SQL 共 $TOT_A 条，超出 AC-18 的上限 4 条（1 count + 1 主查询 + ≤2 字典），原文见上"
      else
        ok "TC-API-09" "SQL $TOT_A 条 ≤ 上限 4，且与行数无关（$ROWS_A 行与 $ROWS_B 行同为 $TOT_A 条；字典 分类$CAT_A/模板$TPL_A）"
      fi
      echo "  （完整 SQL 原文请从 $LOG 归档到任务目录，作为 AC-18 的证据）"
    fi
  fi
fi

# =====================================================================
# TC-API-10 / AC-19 · 🔻 **已降级为代码级验证**（C-6 第二轮裁决：不造数）
# AC 原文（改后）：① 读前端代码确认 SALES_REP 时确实传 salesRepId；
#                 ② 接口侧：某销售员全量 N 单、叠加 partNo=S0004 后命中数**严格小于** N
#                 （证明料号条件与 salesRepId 是 AND，没把角色隔离冲掉）
#   🚫 AC 原文明令：不得声称「已验证真实 SALES_REP 登录后的可见范围」——
#      现网 187 单里 184 单归 SYSTEM_ADMIN，可登录的两个 SALES_REP 名下均 0 单，该链路本次未验证。
#
# ⚠️ 本片只做 ②。① 要读 `cpq-frontend/src/.../QuotationList.tsx`，**超出 S-API 的可读范围**
#    （测试不得从实现代码派生），本脚本显式登记为「未验证·需主线或 S-UI 承担」，🚫 不冒充已验。
if want TC-API-10; then
  tc_begin "TC-API-10" "AC-19(降级)" "salesRepId=<某销售> 全量 N → 叠加 partNo=S0004 必须严格小于 N，且 = 基准 SQL"
  # 现场挑「判别力最强」的 uid：名下单数多、且其中含 S0004 的是真子集（0 < n_part < n）
  PICK=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "
    SELECT x.uid||'|'||x.n||'|'||x.np FROM (
      SELECT q.sales_rep_id AS uid, count(*) AS n,
             count(*) FILTER (WHERE EXISTS (SELECT 1 FROM quotation_line_item li
               WHERE li.quotation_id=q.id AND (LOWER(li.product_part_no_snapshot) LIKE '%s0004%'
                 OR LOWER(li.customer_part_no) LIKE '%s0004%'))) AS np
      FROM quotation q WHERE q.sales_rep_id IS NOT NULL GROUP BY q.sales_rep_id) x
    WHERE x.np > 0 AND x.np < x.n ORDER BY (x.n - x.np) DESC LIMIT 1;")
  PICK=$(printf '%s' "$PICK" | tr -d '[:space:]')
  if [ -z "$PICK" ]; then
    unv "TC-API-10" "库里找不到「名下有单且只有部分含 S0004」的销售员 —— 断言无区分力（不计 PASS）"
  else
    UID1=$(printf '%s' "$PICK" | cut -d'|' -f1)
    N1=$(printf '%s' "$PICK" | cut -d'|' -f2)
    NP1=$(printf '%s' "$PICK" | cut -d'|' -f3)
    UNAME1=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 \
             -c "SELECT username||'/'||role FROM \"user\" WHERE id='$UID1';")
    UNAME1=$(printf '%s' "$UNAME1" | tr -d '[:space:]')
    echo "  现场选中的销售员: $UNAME1 ($UID1)"
    echo "  名下总单 = $N1    其中含 S0004 = $NP1    （$NP1 < $N1 ⇒ 有区分力）"

    # 断言①：salesRepId 全量 == SQL
    assert_count "TC-API-10-角色基线" "AC-19" "salesRepId=$UID1&page=0&size=1" \
      "SELECT count(*) FROM quotation WHERE sales_rep_id='$UID1';" "$Q0"

    # 断言②：叠加 partNo → == Q10 且严格小于①
    Q10="SELECT count(*) FROM quotation q WHERE q.sales_rep_id='$UID1' AND EXISTS (SELECT 1 FROM quotation_line_item li WHERE li.quotation_id=q.id AND (LOWER(li.product_part_no_snapshot) LIKE '%s0004%' OR LOWER(li.customer_part_no) LIKE '%s0004%'));"
    # 上界用 N1：料号条件被忽略时结果会 == N1，被挡下
    assert_count "TC-API-10" "AC-19" "salesRepId=$UID1&partNo=S0004&page=0&size=20" "$Q10" "$N1"

    # 判别性反例：同一料号换一个 uid，结果必须**互异** ⇒ 证明 salesRepId 真的进了 where，
    # 而不是「partNo 一个人在干活、salesRepId 被忽略」。
    # ⚠️ 全都一样往往是没真跑（本项目实测栽过）。
    PICK2=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -X -P pager=off -A -t -v ON_ERROR_STOP=1 -c "
      SELECT x.uid||'|'||x.n||'|'||x.np FROM (
        SELECT q.sales_rep_id AS uid, count(*) AS n,
               count(*) FILTER (WHERE EXISTS (SELECT 1 FROM quotation_line_item li
                 WHERE li.quotation_id=q.id AND (LOWER(li.product_part_no_snapshot) LIKE '%s0004%'
                   OR LOWER(li.customer_part_no) LIKE '%s0004%'))) AS np
        FROM quotation q WHERE q.sales_rep_id IS NOT NULL AND q.sales_rep_id <> '$UID1' GROUP BY q.sales_rep_id) x
      ORDER BY x.n DESC LIMIT 1;")
    PICK2=$(printf '%s' "$PICK2" | tr -d '[:space:]')
    if [ -z "$PICK2" ]; then
      unv "TC-API-10-判别性反例" "库里只有一个销售员有单，构造不出「换个 uid 结果应不同」的对照"
    else
      UID2=$(printf '%s' "$PICK2" | cut -d'|' -f1)
      NP2=$(printf '%s' "$PICK2" | cut -d'|' -f3)
      echo "  —— 判别性反例：同一料号换一个 uid ——"
      echo "  对照 uid: $UID2   其名下含 S0004 的 SQL 值 = $NP2   （与 $NP1 应当互异）"
      api_get "$JAR_ADMIN" "salesRepId=$UID1&partNo=S0004&page=0&size=1"; A1=$(api_total)
      api_get "$JAR_ADMIN" "salesRepId=$UID2&partNo=S0004&page=0&size=1"; A2=$(api_total)
      echo "  uid1 → $A1 （SQL $NP1）    uid2 → $A2 （SQL $NP2）"
      if [ "$NP1" = "$NP2" ]; then
        unv "TC-API-10-判别性反例" "两个 uid 的 SQL 期望值恰好相同（$NP1），这组对照没有区分力（不计 PASS）"
      elif [ "$A1" = "$A2" ]; then
        ng "TC-API-10-判别性反例" "换了 uid 结果仍是 $A1 —— salesRepId 没进 where，或两个条件不是 AND"
      elif [ "$A1" = "$NP1" ] && [ "$A2" = "$NP2" ]; then
        ok "TC-API-10-判别性反例" "uid1=$A1 / uid2=$A2 互异且各自等于其 SQL 值（salesRepId 与 partNo 确是 AND）"
      else
        ng "TC-API-10-判别性反例" "uid1=$A1(应 $NP1) / uid2=$A2(应 $NP2) 对不上"
      fi
    fi

    # 🚫 必须留痕的两条「未验证」，闸门 B 汇报要原样写出，不许标绿
    unv "TC-API-10-真实角色链路" "真实 SALES_REP 登录后的可见范围：**本次未验证**（C-6 裁决不造数；现网 187 单里 184 单归 SYSTEM_ADMIN，可登录的两个 SALES_REP 名下均 0 单）"
    unv "TC-API-10-前端传参" "AC-19 ①「前端 SALES_REP 时确实传 salesRepId」需读 QuotationList.tsx，**超出 S-API 可读范围**，本片未验证 —— 需主线或 S-UI 承担"
  fi
fi

# =====================================================================
# TC-API-11 / AC-21 · 非法 categoryId → 400，不是 500、更不是静默返全量
# AC 原文：返回 400 且响应体带可读消息，口径与既有 status 非法值一致
# =====================================================================
if want TC-API-11; then
  tc_begin "TC-API-11" "AC-21" "categoryId=not-a-uuid → HTTP 400 + 可读消息；对照既有 status 非法值口径"
  api_get "$JAR_ADMIN" "categoryId=not-a-uuid&page=0&size=1"
  CODE_BAD="$HTTP_CODE"; BODY_BAD="$API_BODY"
  echo "  响应体  : $(printf '%s' "$BODY_BAD" | head -c 400)"
  MSG=$(printf '%s' "$BODY_BAD" | python3 -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception: print(""); sys.exit(0)
print((d.get("message") or "") if isinstance(d,dict) else "")' 2>/dev/null)
  TOTAL_BAD=$(printf '%s' "$BODY_BAD" | python3 -c '
import sys,json
try: d=json.load(sys.stdin); print((d.get("data") or {}).get("totalElements"))
except Exception: print("N/A")' 2>/dev/null)
  echo "  message : [$MSG]"
  echo "  该响应里的 totalElements: $TOTAL_BAD （改动前基线是静默返全量 $Q0，那是本条要挡掉的行为）"

  # 既有口径对照：status 非法值（改动前实测就是 400）
  api_get "$JAR_ADMIN" "status=NOT_A_STATUS&page=0&size=1"
  echo "  对照（既有 status 非法值）HTTP = $HTTP_CODE"
  REF_CODE="$HTTP_CODE"

  if [ "$CODE_BAD" = "400" ] && [ -n "${MSG// /}" ]; then
    if [ "$REF_CODE" = "400" ]; then
      ok "TC-API-11" "HTTP 400 + 消息「$MSG」，与既有 status 非法值口径($REF_CODE)一致"
    else
      ok "TC-API-11" "HTTP 400 + 消息「$MSG」（对照口径为 $REF_CODE，差异已记录）"
    fi
  elif [ "$CODE_BAD" = "500" ]; then
    ng "TC-API-11" "返回 500（AC-21 明确禁止），应为 400"
  elif [ "$CODE_BAD" = "200" ]; then
    ng "TC-API-11" "返回 200 且 totalElements=$TOTAL_BAD —— 非法值被静默当作「不过滤」，AC-21 明确禁止"
  else
    ng "TC-API-11" "HTTP $CODE_BAD，消息[$MSG]，不满足「400 + 可读消息」"
  fi

  # 附带记录（不判 PASS/FAIL）：templateId 非法值的行为，AC 未覆盖，报主线
  api_get "$JAR_ADMIN" "templateId=not-a-uuid&page=0&size=1"
  echo "  【AC 未覆盖·仅记录】templateId=not-a-uuid → HTTP $HTTP_CODE ；AC-21 只写了 categoryId"
fi

summary
