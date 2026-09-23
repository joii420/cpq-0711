#!/usr/bin/env bash
# =====================================================================
# task-260922 · 分片 S-API（接口层）· 报价单料号搜索加上生产料号
# 覆盖 AC：2 / 3 / 4 / 5 / 7 / 8        （AC-6 在 Task260922ProductionNoSearchTest，@QuarkusTest）
#
# 🚨 纯只读：全脚本只有 GET + SELECT，不建单、不改数据、不删数据。
#    唯一非 GET 是 POST /api/cpq/auth/login（admin / Admin@2026）。
# 🚫 期望值一律现场用 任务.md §⑦ 基准 SQL 重算（「采样 → 打接口 → 复采」同一分钟内），
#    不把立项日数字当断言常量。复采与首采不等 ⇒ 判「不稳定」，不判 PASS 也不判 FAIL。
#
# 用法（详见 README.md）：
#   S_API_BACKEND=http://localhost:8322 S_API_SQLLOG=/path/to/8322.log bash run.sh
#   bash run.sh TC-AC2 TC-AC8          # 只跑指定用例
# 用例 ID：TC-AC2 TC-AC3 TC-AC4 TC-AC5 TC-AC7 TC-AC8
# =====================================================================
cd "$(dirname "$0")" || exit 2
# shellcheck source=lib.sh
. ./lib.sh

WANT=("$@")
want(){ [ ${#WANT[@]} -eq 0 ] && return 0; for w in "${WANT[@]}"; do [ "$w" = "$1" ] && return 0; done; return 1; }

# 全部控制台输出同时落盘到证据目录（testing.md §2：证据必须留得下来）
exec > >(tee -a "$EVID/console.txt") 2>&1

echo "=== task-260922 · S-API 用例集 ==="
echo "后端: $BACKEND    库: $DB_HOST/$DB_NAME    开跑: $(date -Is)"
echo "证据目录: $EVID"
echo "SQL 日志: ${S_API_SQLLOG:-<未设置>}"

# 小工具：集合校验。输入两个文件（接口返回的单号清单 / 基准 SQL 的单号集合），
# 输出一行 "LEN=<返回条数> DUP=<重复单号个数> OUT=<不在集合里的个数>"，其后逐行列出越界与重复的单号。
set_check(){ # set_check <返回单号文件> <集合文件>
  python3 - "$1" "$2" <<'PY'
import sys, collections
got=[l.strip() for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
ref=set(l.strip() for l in open(sys.argv[2], encoding='utf-8') if l.strip())
dup=[k for k,v in collections.Counter(got).items() if v>1]
out=[g for g in got if g not in ref]
print("LEN=%d DUP=%d OUT=%d" % (len(got), len(dup), len(out)))
for d in dup: print("  DUP  %s" % d)
for o in out: print("  OUT  %s" % o)
PY
}

# ---------------------------------------------------------------------
# 前置 0：探活 + 登录
# 判后端健康看业务端点返 401（/q/health 返 404 是正常的，它不是健康探针）
# ---------------------------------------------------------------------
PROBE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BACKEND/api/cpq/quotations")
echo "存活探针（未登录期望 401）: $PROBE"
if [ "$PROBE" != "401" ]; then
  echo "${C_NG}后端未就绪或不是预期实例（$BACKEND 返回 $PROBE，期望 401）。${C_OFF}"; exit 2
fi
JAR_ADMIN=$(login "$ADMIN_USER" "$ADMIN_PASS") || { echo "管理员登录失败，中止"; exit 2; }
trap 'rm -f "$JAR_ADMIN" 2>/dev/null' EXIT

# ---------------------------------------------------------------------
# 前置 1：验明正身 —— 这台后端连的是不是 cpq_db_0724（只看 200 会把断言打到别人的库上）
# 判据：接口全量 == 本库 count(*)，且 ≠ cpq_db_test 的 count(*)（两值必须互异，判据才有区分力）
# ---------------------------------------------------------------------
Q0=$(sql1 "$Q0_SQL") || { echo "Q0 取不到，中止"; exit 2; }
Q0_TEST=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d cpq_db_test -X -A -t -v ON_ERROR_STOP=1 -c "$Q0_SQL" 2>&1 | tr -d '[:space:]')
api_get "page=0&size=1" "00-identity-all"
T_ALL=$(api_total) || { echo "全量接口不可解析：$T_ALL，中止"; exit 2; }
Q0B=$(sql1 "$Q0_SQL") || exit 2
echo "  验明正身: 接口全量=$T_ALL  $DB_NAME.count=$Q0→$Q0B  cpq_db_test.count=$Q0_TEST"
evid_sql "00-identity.sql.txt" "Q0 ($DB_NAME) 首采/复采" "$Q0_SQL" "$Q0 / $Q0B"
evid_sql "00-identity.sql.txt" "Q0 (cpq_db_test)" "$Q0_SQL" "$Q0_TEST"
if [ "$Q0" != "$Q0B" ]; then
  echo "${C_WARN}验明正身窗口内库在变（$Q0→$Q0B），请重跑${C_OFF}"; exit 2
fi
if [ "$T_ALL" != "$Q0" ]; then
  echo "${C_NG}接口全量 $T_ALL ≠ $DB_NAME 的 $Q0 —— 这台后端连的不是 $DB_NAME，中止（不许把断言打在别的库上）${C_OFF}"; exit 2
fi
if [ "$Q0_TEST" = "$Q0" ]; then
  echo "${C_WARN}⚠️ cpq_db_test 与 $DB_NAME 报价单数相同（$Q0），「连哪个库」判据失去区分力 —— 请从后端启动日志的 JDBC URL 另行确认${C_OFF}"
fi
if [ -n "${S_API_SQLLOG:-}" ] && [ -f "${S_API_SQLLOG:-}" ]; then
  echo "  启动日志中的库与端口（辅证）:"
  /usr/bin/grep -aoE 'jdbc:postgresql://[^ ?]+|Listening on: http://[^ ]+' "$S_API_SQLLOG" | sort -u | sed 's/^/    /'
fi

# =====================================================================
# TC-AC2 / AC-2 · 纯靠生产料号命中（300001）
# AC 原文：调 GET /api/cpq/quotations?partNo=300001&page=0&size=20 →
#   data.totalElements = Q1′(300001)；且同一分钟的 Q1(300001)（旧口径）= 0 ——
#   证明这批命中全部来自生产料号这一路；data.content 非空，逐条核对其单号都在 Q1′ 的结果集合里
# =====================================================================
if want TC-AC2; then
  KW=300001
  tc_begin "TC-AC2" "AC-2" "partNo=$KW → total = Q1′($KW)；Q1($KW)=0；content 非空且逐条 ∈ Q1′ 集合"
  F=TC-AC2.sql.txt
  P1=$(sql1 "$(q1p_sql $KW)") || { ng TC-AC2 "Q1′ 跑不通"; P1=""; }
  O1=$(sql1 "$(q1_sql $KW)")  || { ng TC-AC2 "Q1 跑不通"; O1=""; }
  sqlrows "$(q1p_set_sql $KW)" > "$EVID/TC-AC2.q1p-set.txt" || ng TC-AC2 "Q1′ 集合跑不通"
  api_get "partNo=$KW&page=0&size=20" "TC-AC2"
  TOTAL=$(api_total); PRC=$?
  P2=$(sql1 "$(q1p_sql $KW)") || P2=""; O2=$(sql1 "$(q1_sql $KW)") || O2=""
  evid_sql $F "Q1′($KW) 首采/复采" "$(q1p_sql $KW)" "$P1 / $P2"
  evid_sql $F "Q1($KW) 旧口径 首采/复采" "$(q1_sql $KW)" "$O1 / $O2"
  echo "  Q1′($KW) = $P1 → $P2    Q1($KW) = $O1 → $O2    接口 totalElements = $TOTAL"
  if [ -z "$P1" ] || [ -z "$O1" ]; then :
  elif [ "$HTTP_CODE" != "200" ] || [ $PRC -ne 0 ]; then ng TC-AC2 "HTTP $HTTP_CODE / 解析结果 $TOTAL"
  elif [ "$P1" != "$P2" ] || [ "$O1" != "$O2" ]; then unst TC-AC2 "采样窗口内库在变（Q1′ $P1→$P2 / Q1 $O1→$O2），请重跑"
  elif [ "$P1" = "0" ]; then unv TC-AC2 "Q1′($KW)=0：样本已空，0==0 无区分力，不计 PASS"
  elif [ "$O1" != "0" ]; then unv TC-AC2 "样本失效：旧口径 Q1($KW)=$O1 ≠ 0，已证不了「命中全部来自生产料号」—— 须换样本"
  else
    if [ "$TOTAL" = "$P1" ]; then ok TC-AC2 "totalElements $TOTAL == Q1′ $P1，且同分钟旧口径 Q1 = 0（命中全部来自生产料号）"
    else ng TC-AC2 "totalElements $TOTAL ≠ Q1′ $P1（旧口径 Q1=$O1）"; fi
    # 逐条核对 content（先断言非空，防「循环 0 次」假绿）
    api_numbers > "$EVID/TC-AC2.content-numbers.txt" || true
    EXPECT_LEN=$(( P1 < 20 ? P1 : 20 ))
    CHK=$(set_check "$EVID/TC-AC2.content-numbers.txt" "$EVID/TC-AC2.q1p-set.txt"); echo "$CHK" | sed 's/^/  /'
    LEN=$(echo "$CHK" | head -1 | sed -E 's/.*LEN=([0-9]+).*/\1/'); OUT=$(echo "$CHK" | head -1 | sed -E 's/.*OUT=([0-9]+).*/\1/')
    if [ "$LEN" -eq 0 ]; then ng TC-AC2-逐条 "data.content 为空 —— AC 要求非空，逐条核对无从谈起"
    elif [ "$LEN" -ne "$EXPECT_LEN" ]; then ng TC-AC2-逐条 "content 返回 $LEN 条，应为 min(20, Q1′)=$EXPECT_LEN"
    elif [ "$OUT" -ne 0 ]; then ng TC-AC2-逐条 "$OUT/$LEN 个单号不在 Q1′ 集合里（清单见上）"
    else ok TC-AC2-逐条 "content $LEN 条全部 ∈ Q1′ 集合（$(head -3 "$EVID/TC-AC2.content-numbers.txt" | paste -sd, -)…）"; fi
  fi
fi

# =====================================================================
# TC-AC3 / AC-3 · 中间段模糊匹配（3000）
# AC 原文：调 …?partNo=3000 → totalElements = Q1′(3000)，且严格大于 AC-2 同时刻的值 ——
#   证明是中间段模糊匹配，不是精确匹配
# 「AC-2 同时刻的值」= 本用例内紧挨着再打一次 partNo=300001 取到的 totalElements
# =====================================================================
if want TC-AC3; then
  KW=3000; KW2=300001
  tc_begin "TC-AC3" "AC-3" "partNo=$KW → total = Q1′($KW)，且 > 同时刻 partNo=$KW2 的 total"
  F=TC-AC3.sql.txt
  A1=$(sql1 "$(q1p_sql $KW)") || A1=""; B1=$(sql1 "$(q1p_sql $KW2)") || B1=""
  sqlrows "$(q1p_set_sql $KW)" > "$EVID/TC-AC3.q1p-set.txt" || ng TC-AC3 "Q1′ 集合跑不通"
  api_get "partNo=$KW&page=0&size=20" "TC-AC3-$KW"
  T3=$(api_total); R3=$?; H3=$HTTP_CODE
  api_numbers > "$EVID/TC-AC3.content-numbers.txt" || true
  api_get "partNo=$KW2&page=0&size=20" "TC-AC3-$KW2"
  T2=$(api_total); R2=$?; H2=$HTTP_CODE
  A2=$(sql1 "$(q1p_sql $KW)") || A2=""; B2=$(sql1 "$(q1p_sql $KW2)") || B2=""
  evid_sql $F "Q1′($KW) 首采/复采" "$(q1p_sql $KW)" "$A1 / $A2"
  evid_sql $F "Q1′($KW2) 首采/复采" "$(q1p_sql $KW2)" "$B1 / $B2"
  echo "  Q1′($KW) = $A1 → $A2   Q1′($KW2) = $B1 → $B2   接口: $KW→$T3  $KW2→$T2"
  if [ -z "$A1" ] || [ -z "$B1" ] || [ -z "$A2" ] || [ -z "$B2" ]; then ng TC-AC3 "基准 SQL 跑不通"
  elif [ "$H3" != "200" ] || [ "$H2" != "200" ] || [ $R3 -ne 0 ] || [ $R2 -ne 0 ]; then ng TC-AC3 "HTTP $H3/$H2，解析 $T3/$T2"
  elif [ "$A1" != "$A2" ] || [ "$B1" != "$B2" ]; then unst TC-AC3 "采样窗口内库在变，请重跑"
  elif [ "$A1" = "0" ]; then unv TC-AC3 "Q1′($KW)=0，无区分力"
  elif [ "$A1" -le "$B1" ]; then unv TC-AC3 "样本失效：基准 SQL 本身 Q1′($KW)=$A1 不大于 Q1′($KW2)=$B1，证不了「中间段模糊」"
  else
    if [ "$T3" = "$A1" ]; then ok TC-AC3 "totalElements $T3 == Q1′($KW) $A1"; else ng TC-AC3 "totalElements $T3 ≠ Q1′($KW) $A1"; fi
    if [ "$T3" -gt "$T2" ]; then ok TC-AC3-严格大于 "$KW→$T3 > $KW2→$T2（同时刻）"
    else ng TC-AC3-严格大于 "$KW→$T3 不大于 $KW2→$T2 —— 像是精确匹配或只匹配了前缀"; fi
    CHK=$(set_check "$EVID/TC-AC3.content-numbers.txt" "$EVID/TC-AC3.q1p-set.txt"); echo "$CHK" | sed 's/^/  /'
    LEN=$(echo "$CHK" | head -1 | sed -E 's/.*LEN=([0-9]+).*/\1/'); OUT=$(echo "$CHK" | head -1 | sed -E 's/.*OUT=([0-9]+).*/\1/')
    if [ "$LEN" -eq 0 ]; then ng TC-AC3-逐条 "content 为空"
    elif [ "$OUT" -ne 0 ]; then ng TC-AC3-逐条 "$OUT/$LEN 个单号不在 Q1′($KW) 集合里"
    else ok TC-AC3-逐条 "content $LEN 条全部 ∈ Q1′($KW) 集合"; fi
  fi
fi

# =====================================================================
# TC-AC4 / AC-4 · 两路同时命中只算一次（3120011203）
# AC 原文：调 …?partNo=3120011203 → totalElements = Q1′(3120011203)，不是 旧口径命中数 与
#   生产料号那一路命中数之和；data.content 内单号无重复 —— 同一单被销售料号和生产料号同时命中只出现一次
# 区分力前提：两路都命中的单（交集 I）必须 > 0，否则「去重」根本没被触发，本条恒真
# =====================================================================
if want TC-AC4; then
  KW=3120011203
  tc_begin "TC-AC4" "AC-4" "partNo=$KW → total = Q1′，≠ S+P；content 无重复"
  F=TC-AC4.sql.txt
  sample4(){ PP=$(sql1 "$(q1p_sql $KW)") || PP=""; SS=$(sql1 "$(q1_sql $KW)") || SS=""; PR=$(sql1 "$(qprod_sql $KW)") || PR=""; II=$(sql1 "$(qboth_sql $KW)") || II=""; }
  sample4; P1=$PP; S1=$SS; R1=$PR; I1=$II
  sqlrows "$(q1p_set_sql $KW)" > "$EVID/TC-AC4.q1p-set.txt" || ng TC-AC4 "Q1′ 集合跑不通"
  api_get "partNo=$KW&page=0&size=20" "TC-AC4-page0-size20"
  T20=$(api_total); RC20=$?; H20=$HTTP_CODE
  api_numbers > "$EVID/TC-AC4.page0-size20-numbers.txt" || true
  # 追加：一次拉全量（size=500，上一期实测无上限），查跨页重复与「条数 == totalElements」
  api_get "partNo=$KW&page=0&size=500" "TC-AC4-all"
  TALL=$(api_total); RCALL=$?; HALL=$HTTP_CODE
  api_numbers > "$EVID/TC-AC4.all-numbers.txt" || true
  sample4
  evid_sql $F "Q1′ 首采/复采" "$(q1p_sql $KW)" "$P1 / $PP"
  evid_sql $F "Q1 旧口径 S 首采/复采" "$(q1_sql $KW)" "$S1 / $SS"
  evid_sql $F "生产料号那一路 P 首采/复采" "$(qprod_sql $KW)" "$R1 / $PR"
  evid_sql $F "两路交集 I 首采/复采" "$(qboth_sql $KW)" "$I1 / $II"
  echo "  Q1′=$P1→$PP  S(旧口径)=$S1→$SS  P(生产料号路)=$R1→$PR  I(交集)=$I1→$II  S+P=$(( ${S1:-0} + ${R1:-0} ))"
  echo "  接口: size=20 → $T20   size=500 → $TALL"
  if [ -z "$P1" ] || [ -z "$S1" ] || [ -z "$R1" ] || [ -z "$I1" ]; then ng TC-AC4 "基准 SQL 跑不通"
  elif [ "$H20" != "200" ] || [ "$HALL" != "200" ] || [ $RC20 -ne 0 ] || [ $RCALL -ne 0 ]; then ng TC-AC4 "HTTP $H20/$HALL，解析 $T20/$TALL"
  elif [ "$P1" != "$PP" ] || [ "$S1" != "$SS" ] || [ "$R1" != "$PR" ] || [ "$I1" != "$II" ]; then unst TC-AC4 "采样窗口内库在变，请重跑"
  elif [ "$I1" = "0" ]; then unv TC-AC4 "样本失效：两路交集 I=0，「去重」不会被触发，本条恒真 —— 须换样本"
  else
    SUM=$(( S1 + R1 ))
    if [ "$T20" = "$P1" ] && [ "$TALL" = "$P1" ]; then ok TC-AC4 "totalElements $T20 == Q1′ $P1（size=20 与 size=500 一致）"
    else ng TC-AC4 "totalElements size20=$T20 / size500=$TALL，应 == Q1′ $P1"; fi
    if [ "$T20" != "$SUM" ]; then ok TC-AC4-非求和 "totalElements $T20 ≠ S+P $SUM（交集 $I1 单只计一次）"
    else ng TC-AC4-非求和 "totalElements $T20 == S+P $SUM —— 两路命中被重复计数"; fi
    for pair in "page0-size20:$(( P1 < 20 ? P1 : 20 ))" "all:$P1"; do
      NAME=${pair%%:*}; WANTLEN=${pair##*:}
      CHK=$(set_check "$EVID/TC-AC4.$NAME-numbers.txt" "$EVID/TC-AC4.q1p-set.txt"); echo "  [$NAME] $CHK" | sed 's/^/  /'
      LEN=$(echo "$CHK" | head -1 | sed -E 's/.*LEN=([0-9]+).*/\1/'); DUP=$(echo "$CHK" | head -1 | sed -E 's/.*DUP=([0-9]+).*/\1/'); OUT=$(echo "$CHK" | head -1 | sed -E 's/.*OUT=([0-9]+).*/\1/')
      if [ "$LEN" -eq 0 ]; then ng "TC-AC4-无重复[$NAME]" "content 为空，查重无从谈起"
      elif [ "$DUP" -ne 0 ]; then ng "TC-AC4-无重复[$NAME]" "content 里有 $DUP 个单号重复出现"
      elif [ "$LEN" -ne "$WANTLEN" ]; then ng "TC-AC4-无重复[$NAME]" "content 返回 $LEN 条，应为 $WANTLEN"
      elif [ "$OUT" -ne 0 ]; then ng "TC-AC4-无重复[$NAME]" "$OUT 个单号不在 Q1′ 集合里"
      else ok "TC-AC4-无重复[$NAME]" "content $LEN 条、单号无重复、全部 ∈ Q1′"; fi
    done
  fi
fi

# =====================================================================
# TC-AC5 / AC-5 · 必须按本单客户取生产料号（300021）
# AC 原文：调 …?partNo=300021&size=100 →
#   ① totalElements = Q1′(300021)；
#   ② 返回的单号里不含 QT-20260907-0564、QT-20260908-0620、QT-20260910-0806
#      （客户 CUST-0001 的单，含销售料号 S0004，但「S0004 → 300021」只绑在正泰名下）；
#   ③ 对照值：同一分钟的 Q-漏客户(300021) 必须 ≠ ① 的值 —— 若两者相等，说明样本失效，
#      须换样本重跑，🚫 不许当通过
# =====================================================================
if want TC-AC5; then
  KW=300021
  NAMED=(QT-20260907-0564 QT-20260908-0620 QT-20260910-0806)
  tc_begin "TC-AC5" "AC-5" "partNo=$KW&size=100 → ① total = Q1′；② 不含 3 张 CUST-0001 单；③ Q-漏客户 ≠ ①"
  F=TC-AC5.sql.txt
  P1=$(sql1 "$(q1p_sql $KW)") || P1=""; L1=$(sql1 "$(qleak_sql $KW)") || L1=""
  sqlrows "$(q1p_set_sql $KW)" > "$EVID/TC-AC5.q1p-set.txt" || ng TC-AC5 "Q1′ 集合跑不通"
  # 点名样本的有效性：存在 + 客户不是 S0004 的绑定方 + 含销售料号 S0004
  NAMED_SQL="SELECT q.quotation_number||'|'||c.code||'|'||
      (SELECT count(*) FROM quotation_line_item li WHERE li.quotation_id=q.id AND lower(li.product_part_no_snapshot)='s0004')||'|'||
      (SELECT count(*) FROM quotation_line_item li JOIN ds_quote_material m ON m.customer_no=c.code AND m.material_no=li.product_part_no_snapshot
         WHERE li.quotation_id=q.id AND lower(m.production_no) LIKE '%${KW}%')
    FROM quotation q JOIN customer c ON c.id=q.customer_id
   WHERE q.quotation_number IN ('${NAMED[0]}','${NAMED[1]}','${NAMED[2]}') ORDER BY 1;"
  NAMED_ROWS=$(sqlrows "$NAMED_SQL") || NAMED_ROWS=""
  api_get "partNo=$KW&page=0&size=100" "TC-AC5"
  TOTAL=$(api_total); PRC=$?
  api_numbers > "$EVID/TC-AC5.content-numbers.txt" || true
  CLEN=$(api_content_len) || CLEN=""
  P2=$(sql1 "$(q1p_sql $KW)") || P2=""; L2=$(sql1 "$(qleak_sql $KW)") || L2=""
  evid_sql $F "Q1′($KW) 首采/复采" "$(q1p_sql $KW)" "$P1 / $P2"
  evid_sql $F "Q-漏客户($KW) 首采/复采" "$(qleak_sql $KW)" "$L1 / $L2"
  evid_sql $F "点名样本 单号|客户|含S0004行数|本单客户名下该行生产料号含$KW的行数" "$NAMED_SQL" "$(printf '%s' "$NAMED_ROWS" | paste -sd';' -)"
  echo "  ① Q1′=$P1→$P2  接口 totalElements=$TOTAL  content=$CLEN 条"
  echo "  ③ Q-漏客户=$L1→$L2"
  echo "  点名样本（单号|客户|含S0004行数|本客户名下生产料号含$KW行数）:"; printf '%s\n' "$NAMED_ROWS" | sed 's/^/    /'
  if [ -z "$P1" ] || [ -z "$L1" ] || [ -z "$P2" ] || [ -z "$L2" ]; then ng TC-AC5 "基准 SQL 跑不通"
  elif [ "$HTTP_CODE" != "200" ] || [ $PRC -ne 0 ]; then ng TC-AC5 "HTTP $HTTP_CODE，解析 $TOTAL"
  elif [ "$P1" != "$P2" ] || [ "$L1" != "$L2" ]; then unst TC-AC5 "采样窗口内库在变，请重跑"
  elif [ "$L1" = "$P1" ]; then
    # AC 原文：样本失效，须换样本重跑，🚫 不许当通过 —— ①② 一并不计 PASS
    unv TC-AC5 "样本失效：Q-漏客户 $L1 == Q1′ $P1（跨客户单已被删或改了客户），须换样本重跑；①② 本轮一律不计 PASS（接口实测 $TOTAL）"
  elif [ "$P1" = "0" ]; then unv TC-AC5 "Q1′=0，无区分力"
  else
    ok TC-AC5-③对照 "Q-漏客户 $L1 ≠ Q1′ $P1（样本有效：漏客户过滤的错误实现会得到 $L1）"
    if [ "$TOTAL" = "$P1" ]; then ok TC-AC5-① "totalElements $TOTAL == Q1′ $P1"
    elif [ "$TOTAL" = "$L1" ]; then ng TC-AC5-① "totalElements $TOTAL == Q-漏客户 $L1 ≠ Q1′ $P1 —— 生产料号没按本单客户过滤（跨客户串号）"
    else ng TC-AC5-① "totalElements $TOTAL ≠ Q1′ $P1（Q-漏客户=$L1）"; fi
    # ② 前提：size=100 必须装得下全部结果，否则「不含」只是没翻到那一页
    if [ -z "$CLEN" ] || [ "$CLEN" -eq 0 ]; then ng TC-AC5-② "content 为空，「不含」的判断无从谈起"
    elif [ "$TOTAL" -gt 100 ] || [ "$CLEN" != "$TOTAL" ]; then unv TC-AC5-② "content $CLEN 条 ≠ totalElements $TOTAL（或 >100），一页装不下全部结果，「不含」不完整"
    else
      HIT=""; VALID=0
      for n in "${NAMED[@]}"; do
        ROW=$(printf '%s\n' "$NAMED_ROWS" | /usr/bin/grep -a "^$n|" || true)
        S0004=$(printf '%s' "$ROW" | cut -d'|' -f3); OWN=$(printf '%s' "$ROW" | cut -d'|' -f4)
        if [ -n "$ROW" ] && [ "${S0004:-0}" -gt 0 ] && [ "${OWN:-1}" -eq 0 ]; then VALID=$((VALID+1)); else echo "    ⚠ 点名样本 $n 已不满足「存在 + 含 S0004 + 本客户无该绑定」：[$ROW]"; fi
        /usr/bin/grep -aqx "$n" "$EVID/TC-AC5.content-numbers.txt" && HIT="$HIT $n"
      done
      if [ -n "$HIT" ]; then ng TC-AC5-② "返回结果含跨客户单:$HIT —— 生产料号没按本单客户过滤"
      elif [ "$VALID" -eq 0 ]; then unv TC-AC5-② "三张点名单都已不是有效样本，「不含」为空跑"
      else ok TC-AC5-② "全部 $CLEN 条里不含三张点名单（其中有效样本 $VALID/3 张）"; fi
      CHK=$(set_check "$EVID/TC-AC5.content-numbers.txt" "$EVID/TC-AC5.q1p-set.txt"); echo "$CHK" | sed 's/^/  /'
      OUT=$(echo "$CHK" | head -1 | sed -E 's/.*OUT=([0-9]+).*/\1/')
      if [ "$OUT" -ne 0 ]; then ng TC-AC5-集合 "$OUT 个单号不在 Q1′ 集合里（清单见上）"
      else ok TC-AC5-集合 "全部 $CLEN 条 ∈ Q1′ 集合"; fi
    fi
  fi
fi

# =====================================================================
# TC-AC7 / AC-7 · 生产料号那一路没命中时，另两路结果不变（回归）
# AC 原文：依次调 …?partNo=s0004、a002、s000、zzz9999 → 每次 totalElements = 同一分钟的
#   Q1（旧口径）值 = Q1′（新口径）值 —— 不得把另两路的结果清零或报错；
#   zzz9999 返回 HTTP 200、totalElements = 0，🚫 不是 500
# =====================================================================
if want TC-AC7; then
  tc_begin "TC-AC7" "AC-7" "s0004 / a002 / s000 / zzz9999 → total = Q1 = Q1′；zzz9999 → 200 + 0"
  F=TC-AC7.sql.txt
  for KW in s0004 a002 s000 zzz9999; do
    echo "  —— partNo=$KW ——"
    O1=$(sql1 "$(q1_sql $KW)") || O1=""; P1=$(sql1 "$(q1p_sql $KW)") || P1=""
    sqlrows "$(q1_set_sql $KW)" > "$EVID/TC-AC7-$KW.q1-set.txt" || true
    api_get "partNo=$KW&page=0&size=20" "TC-AC7-$KW"
    TOTAL=$(api_total); PRC=$?
    api_numbers > "$EVID/TC-AC7-$KW.content-numbers.txt" || true
    O2=$(sql1 "$(q1_sql $KW)") || O2=""; P2=$(sql1 "$(q1p_sql $KW)") || P2=""
    evid_sql $F "Q1($KW) 首采/复采" "$(q1_sql $KW)" "$O1 / $O2"
    evid_sql $F "Q1′($KW) 首采/复采" "$(q1p_sql $KW)" "$P1 / $P2"
    echo "  Q1=$O1→$O2  Q1′=$P1→$P2  接口 totalElements=$TOTAL"
    TC="TC-AC7[$KW]"
    if [ -z "$O1" ] || [ -z "$P1" ] || [ -z "$O2" ] || [ -z "$P2" ]; then ng "$TC" "基准 SQL 跑不通"
    elif [ "$HTTP_CODE" != "200" ]; then ng "$TC" "HTTP $HTTP_CODE（AC 要求 200，🚫 不是 500）"
    elif [ $PRC -ne 0 ]; then ng "$TC" "HTTP 200 但响应不可解析 / 业务码非 200：$TOTAL"
    elif [ "$O1" != "$O2" ] || [ "$P1" != "$P2" ]; then unst "$TC" "采样窗口内库在变，请重跑"
    elif [ "$O1" != "$P1" ]; then unv "$TC" "样本失效：该关键字现已出现在某个生产料号里（Q1=$O1 ≠ Q1′=$P1），不再是回归样本"
    elif [ "$KW" = "zzz9999" ]; then
      if [ "$O1" != "0" ]; then unv "$TC" "样本失效：zzz9999 现在有 $O1 单命中，不再是「查无结果」样本"
      elif [ "$TOTAL" = "0" ]; then ok "$TC" "HTTP 200，totalElements 0 == Q1 == Q1′ 0"
      else ng "$TC" "totalElements $TOTAL ≠ 0"; fi
    elif [ "$O1" = "0" ]; then unv "$TC" "Q1=0，样本已空，无区分力"
    else
      if [ "$TOTAL" = "$O1" ]; then ok "$TC" "totalElements $TOTAL == Q1 $O1 == Q1′ $P1"
      else ng "$TC" "totalElements $TOTAL ≠ Q1 $O1（Q1′=$P1）—— 生产料号那一路未命中时，另两路结果被改变"; fi
      CHK=$(set_check "$EVID/TC-AC7-$KW.content-numbers.txt" "$EVID/TC-AC7-$KW.q1-set.txt"); echo "$CHK" | sed 's/^/  /'
      LEN=$(echo "$CHK" | head -1 | sed -E 's/.*LEN=([0-9]+).*/\1/'); OUT=$(echo "$CHK" | head -1 | sed -E 's/.*OUT=([0-9]+).*/\1/')
      if [ "$LEN" -eq 0 ]; then ng "$TC-逐条" "content 为空"
      elif [ "$OUT" -ne 0 ]; then ng "$TC-逐条" "$OUT/$LEN 个单号不在 Q1 集合里"
      else ok "$TC-逐条" "content $LEN 条全部 ∈ Q1 集合"; fi
    fi
  done
fi

# =====================================================================
# TC-AC8 / AC-8 · SQL 条数与命中单数、页大小无关（N+1 红线）
# AC 原文：临时后端开 SQL 日志（quarkus.hibernate-orm.log.sql=true）；依次发
#   ① ?partNo=3000&size=20  ② ?partNo=3000&size=1  ③ 不带 partNo 的 ?size=20 →
#   ① 与 ② 的 SQL 条数相等（返回行数不同、SQL 条数不变）；① 比 ③ 至多多 1 条；
#   日志里不得出现按单 / 按行 / 按料号逐条查 ds_quote_material 的语句。三次请求各自的 SQL 原文落盘为证据
#
# 手法：先把三种请求各预热 2 次（避开 HQL 计划首次编译的额外 SQL）→ 每次请求前确认日志静默 →
#       记日志行号 → 发请求 → 等日志再次静默 → 截取该窗口的 SQL。
# 🚨 反假绿守卫：
#   - 任一窗口 0 条 SQL / 窗口里没有查 quotation 的语句 ⇒ 判「未验证」（日志没开或没截到，0 条 ≠ 没 N+1）
#   - ① 窗口里一条 ds_quote_material 都没有 ⇒ 判「未验证」（生产料号那一路的查询没进日志，
#     例如走了 Hibernate 之外的连接，此时「至多多 1 条」数不到它，会假绿）
#   - ① 与 ② 返回行数没拉开 ⇒ 判「未验证」（比不出「与行数无关」）
# 「逐条查」的操作化判据：同一请求窗口内，引用 ds_quote_material 的**同一条语句文本**出现 ≥2 次
#   （绑定参数以 ? 显示，循环逐条查必然是同文本重复）；且 ① 与 ② 的该类语句条数必须相等
# =====================================================================
if want TC-AC8; then
  tc_begin "TC-AC8" "AC-8" "① partNo=3000&size=20 与 ② size=1 SQL 条数相等；① − ③ ≤ 1；无逐条查 ds_quote_material"
  LOG="${S_API_SQLLOG:-}"
  if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
    unv TC-AC8 "未提供后端 SQL 日志（S_API_SQLLOG 未设或文件不存在）—— 见 README 启动命令"
  else
    SQLRE='^\[Hibernate\] |^Hibernate: '
    quiesce(){ local a b i; for i in 1 2 3 4 5; do a=$(wc -l < "$LOG"); sleep 1.5; b=$(wc -l < "$LOG"); [ "$a" = "$b" ] && return 0; done; return 1; }
    # 截取窗口：发请求 → 等日志静默 → 输出 SQL 行到证据文件
    WIN_TOT=0; WIN_DQM=0; WIN_Q=0; WIN_ROWS=0; WIN_REPEAT=0; WIN_OK=1
    measure(){ # measure <标签> <querystring>
      local tag="$1" qs="$2" mark f
      WIN_OK=1
      if ! quiesce; then WIN_OK=0; echo "  [$tag] 请求前日志不静默"; return; fi
      mark=$(( $(wc -l < "$LOG") + 1 ))
      api_get "$qs" "TC-AC8-$tag"
      WIN_ROWS=$(api_content_len) || WIN_ROWS=-1
      if ! quiesce; then WIN_OK=0; echo "  [$tag] 请求后日志不静默"; fi
      f="$EVID/AC-8-$tag.sql.txt"
      { echo "# AC-8 证据 · $tag · GET $API_URL · HTTP $HTTP_CODE · 返回 $WIN_ROWS 行 · $(date -Is)"
        echo "# 日志 $LOG 自第 $mark 行起的窗口"; } > "$f"
      tail -n "+$mark" "$LOG" | /usr/bin/grep -aE "$SQLRE" | nl -ba >> "$f"
      WIN_TOT=$(tail -n "+$mark" "$LOG" | /usr/bin/grep -acE "$SQLRE")
      WIN_DQM=$(tail -n "+$mark" "$LOG" | /usr/bin/grep -aE "$SQLRE" | /usr/bin/grep -aci 'ds_quote_material')
      WIN_Q=$(tail -n "+$mark" "$LOG" | /usr/bin/grep -aE "$SQLRE" | /usr/bin/grep -aciE 'from quotation( |$)')
      WIN_REPEAT=$(tail -n "+$mark" "$LOG" | /usr/bin/grep -aE "$SQLRE" | /usr/bin/grep -ai 'ds_quote_material' | sort | uniq -c | awk '$1>=2' | wc -l)
      { echo; echo "## 条数统计"; echo "总条数: $WIN_TOT"; echo "引用 ds_quote_material: $WIN_DQM"
        echo "查 quotation 主表: $WIN_Q"; echo "ds_quote_material 同文本重复(≥2 次)的语句种数: $WIN_REPEAT"; } >> "$f"
      echo "  [$tag] 返回 $WIN_ROWS 行 → SQL $WIN_TOT 条（ds_quote_material $WIN_DQM / 查 quotation $WIN_Q / 同文本重复 $WIN_REPEAT）  原文: $f"
    }
    echo "  预热：三种请求各 2 次"
    for i in 1 2; do
      for qs in "partNo=3000&page=0&size=20" "partNo=3000&page=0&size=1" "page=0&size=20"; do
        curl -s --noproxy '*' -b "$JAR_ADMIN" -o /dev/null "$BACKEND/api/cpq/quotations?$qs"
      done
    done
    measure "1-partNo3000-size20" "partNo=3000&page=0&size=20"; A_OK=$WIN_OK A_TOT=$WIN_TOT A_DQM=$WIN_DQM A_Q=$WIN_Q A_ROWS=$WIN_ROWS A_REP=$WIN_REPEAT
    measure "2-partNo3000-size1"  "partNo=3000&page=0&size=1";  B_OK=$WIN_OK B_TOT=$WIN_TOT B_DQM=$WIN_DQM B_Q=$WIN_Q B_ROWS=$WIN_ROWS B_REP=$WIN_REPEAT
    measure "3-noPartNo-size20"   "page=0&size=20";             Z_OK=$WIN_OK Z_TOT=$WIN_TOT Z_DQM=$WIN_DQM Z_Q=$WIN_Q Z_ROWS=$WIN_ROWS Z_REP=$WIN_REPEAT  # 前缀用 Z_ 不用 C_：C_OK 是 lib.sh 的颜色变量（run1 实测被覆盖成 "1"，仅影响显示）
    if [ "$A_OK$B_OK$Z_OK" != "111" ]; then
      unv TC-AC8 "有窗口日志不静默（后端非空闲，可能有别的请求打到这台实例），SQL 计数不可信"
    elif [ "$A_TOT" -eq 0 ] || [ "$B_TOT" -eq 0 ] || [ "$Z_TOT" -eq 0 ] || [ "$A_Q" -eq 0 ] || [ "$B_Q" -eq 0 ] || [ "$Z_Q" -eq 0 ]; then
      unv TC-AC8 "有窗口数到 0 条 SQL 或没有查 quotation 的语句（$A_TOT/$B_TOT/$Z_TOT，quotation $A_Q/$B_Q/$Z_Q）—— SQL 日志多半没开或没截到"
    elif [ "$A_DQM" -eq 0 ]; then
      unv TC-AC8 "① 窗口里没有任何引用 ds_quote_material 的 SQL —— 生产料号那一路的查询没进日志，条数比较会漏数它"
    elif [ "$A_ROWS" -le "$B_ROWS" ]; then
      unv TC-AC8 "① ② 返回行数没拉开（$A_ROWS vs $B_ROWS），比不出「与行数无关」"
    else
      if [ "$A_TOT" -eq "$B_TOT" ]; then ok TC-AC8-①=② "① $A_ROWS 行 / ② $B_ROWS 行，SQL 同为 $A_TOT 条"
      else ng TC-AC8-①=② "SQL 条数随返回行数变化：① $A_ROWS 行→$A_TOT 条，② $B_ROWS 行→$B_TOT 条"; fi
      DIFF=$(( A_TOT - Z_TOT ))
      if [ "$DIFF" -le 1 ]; then ok TC-AC8-①−③≤1 "① $A_TOT 条 − ③ $Z_TOT 条 = $DIFF ≤ 1"
      else ng TC-AC8-①−③≤1 "① $A_TOT 条比 ③ $Z_TOT 条多 $DIFF 条（AC 上限 1）"; fi
      if [ "$A_REP" -eq 0 ] && [ "$B_REP" -eq 0 ] && [ "$A_DQM" -eq "$B_DQM" ]; then
        ok TC-AC8-无逐条 "引用 ds_quote_material 的语句 ① $A_DQM 条 = ② $B_DQM 条，且无同文本重复"
      else
        ng TC-AC8-无逐条 "疑似逐条查 ds_quote_material：同文本重复 ①$A_REP/②$B_REP 种，条数 ①$A_DQM vs ②$B_DQM（原文见证据）"
      fi
    fi
  fi
fi

summary
