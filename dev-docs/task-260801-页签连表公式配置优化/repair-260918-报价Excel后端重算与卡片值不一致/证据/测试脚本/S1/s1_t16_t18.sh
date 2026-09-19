#!/usr/bin/env bash
# repair-260918 · 测试片 S1 · T1.6（AC-10 核价侧不变）与 T1.8（AC-13 自检声明）。
# 只读：git diff / grep / curl 探活；T1.6 的 mvnw test 需显式 --run-mvn 才执行（仅在主线放行后）。
#
# 用法：
#   bash s1_t16_t18.sh --base http://localhost:8318 --startlog /path/to/backend-8318.log [--run-mvn] [--only T1.6|T1.8]
# 输出：证据/测试/S1/<yymmdd-HHMM>[-n]-sh/（新目录，绝不覆盖）。
# 退出码：0 = 全部断言 PASS；1 = 有 FAIL；2 = 参数/环境错误。
set -u
WT=/home/joii/project/cpq/.claude/worktrees/repair-260918-quote-excel-card-source
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TASK="$(cd "$HERE/../../.." && pwd)"
BASE=""; STARTLOG=""; RUN_MVN=0; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2;;
    --startlog) STARTLOG="$2"; shift 2;;
    --run-mvn) RUN_MVN=1; shift;;
    --only) ONLY="$2"; shift 2;;
    *) echo "未知参数 $1"; exit 2;;
  esac
done
[ -n "$BASE" ] || { echo "缺 --base"; exit 2; }

STAMP=$(date +%y%m%d-%H%M); OUT="$TASK/证据/测试/S1/${STAMP}-sh"; n=2
while [ -e "$OUT" ]; do OUT="$TASK/证据/测试/S1/${STAMP}-sh-$n"; n=$((n+1)); done
mkdir -p "$OUT" || exit 2
LOG="$OUT/T1.6-T1.8.txt"; FAILS=0
p()    { echo "$*" | tee -a "$LOG"; }
pass() { p "  [PASS] $*"; }
fail() { p "  [FAIL] $*"; FAILS=$((FAILS+1)); }
p "运行目录 $OUT；时刻 $(date -Is)；BASE=$BASE；worktree HEAD=$(git -C "$WT" rev-parse --short HEAD)；master=$(git -C "$WT" rev-parse --short master)"
p "分支 $(git -C "$WT" rev-parse --abbrev-ref HEAD)"
# 子代理不提交 ⇒ 改动可能只在工作区：一律比 merge-base 与「工作区」（含未提交），而不是 master...HEAD（只看已提交）
MB=$(git -C "$WT" merge-base master HEAD); p "merge-base=$MB（以下 diff 均为 merge-base ↔ 工作区，含未提交改动）"

# ------------------------------------------------------------------ T1.6 · AC-10
if [ -z "$ONLY" ] || [ "$ONLY" = "T1.6" ]; then
  p "===== T1.6 开始 ====="
  EVS=cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java
  git -C "$WT" diff "$MB" -- "$EVS" > "$OUT/T1.6-diff-ExcelViewService.patch"; rc=$?
  [ $rc -eq 0 ] && pass "git diff ExcelViewService 执行成功（rc=$rc）" || fail "git diff ExcelViewService rc=$rc"
  p "  diff 行数 $(wc -l < "$OUT/T1.6-diff-ExcelViewService.patch")（0 行 = 该文件无改动；AC-1 等要求它有改动，0 行本身要报主线）"
  # CardSnapshotService 路径不猜：用 git 列出真实路径
  CSS=$(git -C "$WT" ls-files 'cpq-backend/src/main/java/*CardSnapshotService.java')
  p "  CardSnapshotService 真实路径：${CSS:-<未找到>}"
  if [ -n "$CSS" ]; then git -C "$WT" diff "$MB" -- "$CSS" > "$OUT/T1.6-diff-CardSnapshotService.patch"; fi
  # 机械判据（弱）：+/- 行里出现 costingSide / costingExcelValues 的条数。
  # ⚠️ 该判据只能抓到「直接改了含这两个词的行」，抓不到「分支体内部改动」—— 完整判定须人读 diff（见回报：读实现 diff 的授权问题）。
  c1=$( { /usr/bin/grep -a -E '^[+-][^+-]' "$OUT/T1.6-diff-ExcelViewService.patch" | /usr/bin/grep -a -c 'costingSide'; true; } )
  # 注：grep -c 计数为 0 时退出码 1，旧写法 `|| echo NA` 会把「0」变成「0\nNA」造成假红（260918-2230-sh 实例），故用 { …; true; }
  if [ -n "$CSS" ]; then c2=$( { /usr/bin/grep -a -E '^[+-][^+-]' "$OUT/T1.6-diff-CardSnapshotService.patch" | /usr/bin/grep -a -c 'costingExcelValues'; true; } ); else c2=NA; fi
  p "  ExcelViewService 改动行含 costingSide：$c1 条；CardSnapshotService 改动行含 costingExcelValues：$c2 条"
  [ "$c1" = "0" ] && pass "ExcelViewService 无含 costingSide 的改动行" || fail "ExcelViewService 有 $c1 条含 costingSide 的改动行（须逐行核是否触及核价侧分支）"
  [ "$c2" = "0" ] && pass "CardSnapshotService 无含 costingExcelValues 的改动行" || fail "CardSnapshotService costingExcelValues 改动行=$c2"
  if [ $RUN_MVN -eq 1 ]; then
    p "  mvnw test（测试库 cpq_db_test；只跑点名三类）开始 $(date -Is)"
    ( cd "$WT/cpq-backend" && /usr/bin/grep -a -n 'DB_NAME' src/main/resources/application-test.properties ) 2>&1 | tee -a "$LOG"
    ( cd "$WT/cpq-backend" && ./mvnw test -Dtest=GetExcelViewCostingIT,CostingExcelTreeTabKeyIT,EffectiveRowsKeyContractTest ) \
        > "$OUT/T1.6-mvn.log" 2>&1; mrc=$?
    p "  mvnw rc=$mrc 结束 $(date -Is)"
    /usr/bin/grep -a -E 'Tests run:|BUILD|FAIL' "$OUT/T1.6-mvn.log" | tee -a "$LOG"
    for f in "$WT"/cpq-backend/target/surefire-reports/*GetExcelViewCostingIT.txt "$WT"/cpq-backend/target/surefire-reports/*CostingExcelTreeTabKeyIT.txt "$WT"/cpq-backend/target/surefire-reports/*EffectiveRowsKeyContractTest.txt; do
      [ -f "$f" ] && { cp "$f" "$OUT/"; p "  $(basename "$f"): $(/usr/bin/grep -a 'Tests run' "$f")"; } || fail "缺 surefire 报告 $f"
    done
    [ $mrc -eq 0 ] && pass "三个核价测试类 mvnw rc=0" || fail "三个核价测试类 mvnw rc=$mrc"
    p "  ⚠ Tests run 与 master 基线（后端工程师 B-7① 回报）逐类对照须人工填入回报"
  else
    p "  mvnw test 未执行（未带 --run-mvn）⇒ AC-10 ② 未验证"
  fi
fi

# ------------------------------------------------------------------ T1.8 · AC-13
if [ -z "$ONLY" ] || [ "$ONLY" = "T1.8" ]; then
  p "===== T1.8 开始 ====="
  mig=$(git -C "$WT" diff --name-only "$MB" -- cpq-backend/src/main/resources/db/migration)
  p "  迁移目录 diff：[${mig}]"
  [ -z "$mig" ] && pass "无迁移（diff 为空）" || fail "存在迁移改动：$mig"
  fe=$(git -C "$WT" diff --name-only "$MB" -- cpq-frontend/src)
  p "  前端 src diff：[${fe}]"
  [ -z "$fe" ] && pass "前端零改动（diff 为空）" || fail "前端有改动：$fe"
  # 未提交改动也要看：分支若尚未提交，三点 diff 看不到工作区改动
  st=$(git -C "$WT" status --porcelain --untracked-files=all -- cpq-backend/src/main/resources/db/migration cpq-frontend/src)
  p "  工作区未提交（迁移目录+前端 src）：[${st}]"
  [ -z "$st" ] && pass "工作区无未提交的迁移/前端改动" || fail "工作区有未提交改动：$st"
  p "  （参考）本分支相对 master 全部改动文件："; git -C "$WT" diff --name-only "$MB" | tee -a "$LOG"
  if [ -n "$STARTLOG" ] && [ -f "$STARTLOG" ]; then
    ne=$(/usr/bin/grep -a -c -E '\bERROR\b' "$STARTLOG")
    p "  启动日志 $STARTLOG 含 ERROR 行：$ne"; /usr/bin/grep -a -n -E '\bERROR\b' "$STARTLOG" | head -20 | tee -a "$LOG"
    /usr/bin/grep -a -m3 -E 'started in|Listening on' "$STARTLOG" | tee -a "$LOG"
    [ "$ne" = "0" ] && pass "启动无 ERROR" || fail "启动日志有 $ne 行 ERROR"
    cp "$STARTLOG" "$OUT/T1.8-startlog-copy.log"
  else
    fail "未提供 --startlog（启动无 ERROR 未验证）"
  fi
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/cpq/components")
  p "  GET $BASE/api/cpq/components（无 cookie）→ $code"
  [ "$code" = "401" ] && pass "401 探活" || fail "探活期望 401 实际 $code"
fi
p "===== 结束：FAIL 项 $FAILS ====="
[ $FAILS -eq 0 ] && exit 0 || exit 1
