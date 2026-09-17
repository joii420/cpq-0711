#!/usr/bin/env bash
# T-C6（AC-17）结案全量。分阶段执行，每阶段独立出结果，最后 report 汇总成 A/B 表。
#   T-C6 static                # tsc -b + 本任务前端测试 + 本任务后端测试(一次性库)
#   T-C6 e2e branch            # 分支栈(5293→8293 分支后端)：S-B spec 复跑、tabjoin-formula-drawer、quotation-flow、本片 spec
#   T-C6 e2e master            # master 栈(5293 master vite → 8293 master 后端，均连一次性库)：tabjoin-formula-drawer、quotation-flow
#   T-C6 report                # 输出 A/B 对照表 out-C6/AB对照.md
# 栈的起停用 stack.sh（branch: start-backend branch true + start-vite branch；master: start-backend master + start-vite master）。
# 需主线提供：SB_CMD（S-B 复跑命令，在 cpq-frontend 下执行）、BE_TEST_CLASS（可选覆盖）。master 树默认 repair-260916-master-ab。
# 分支阶段的本片 spec 含 AC-16⑤，须在 T-C5 run 之后跑。
# 含 Playwright 的阶段开跑前必须 pgrep 采样无其他 playwright 进程，并记录采样时刻。
source "$(dirname "$0")/common.sh"; guard_wt; guard_db
O="$S/out-C6"; mkdir -p "$O"; FAILS=0
pw_guard() {
  local t; t=$(now); local p; p=$(pgrep -af "node.*[p]laywright test" || true)
  echo "[pw-guard] 采样时刻 $t playwright 进程: ${p:-无}" | tee -a "$O/pw-guard.log"
  [ -z "$p" ] || { echo "有其他 playwright 在跑，停止"; exit 10; }
  c=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5293/api/cpq/components); [ "$c" = 401 ] || { echo "5293→8293 栈未就绪($c)"; exit 11; }
}
pw_json() { # tree label spec [extra env...]
  local tree=$1 lab=$2 spec=$3; shift 3
  ( cd "$tree/cpq-frontend" && env "$@" PW_BASE_URL=http://localhost:5293 PW_BACKEND_URL=http://localhost:8293 \
      PLAYWRIGHT_JSON_OUTPUT_NAME="$O/pw-$lab-$(basename "$spec" .spec.ts).json" \
      npx playwright test -c playwright.config.ts "$spec" --reporter=json,list > "$O/pw-$lab-$(basename "$spec" .spec.ts).log" 2>&1 )
  echo "[pw] $lab $spec rc=$? → $O/pw-$lab-$(basename "$spec" .spec.ts).json"
}
case "${1:-}" in
static)
  cd "$WT/cpq-frontend"
  npx tsc -b > "$O/tsc.txt" 2>&1; rc=$?; tail -5 "$O/tsc.txt"
  [ $rc -eq 0 ] && [ "$(grep -c 'error TS' "$O/tsc.txt")" = 0 ] && pass "tsc -b 0 错误" || fail "tsc -b rc=$rc 错误数=$(grep -c 'error TS' "$O/tsc.txt")"
  FE=$(cd "$WT" && { git diff --name-only master -- 'cpq-frontend/src/**/*.test.ts' 'cpq-frontend/src/**/*.test.tsx'; git ls-files --others --exclude-standard -- 'cpq-frontend/src/**/*.test.ts' 'cpq-frontend/src/**/*.test.tsx'; } | sort -u | sed 's#^cpq-frontend/##')
  echo "本任务前端测试文件: $FE" | tee "$O/fe-files.txt"
  [ -n "$FE" ] || fail "未识别到本任务前端测试文件（空跑保护）"
  npx vitest run $FE --reporter=verbose > "$O/vitest.txt" 2>&1; rc=$?; tail -8 "$O/vitest.txt"
  [ $rc -eq 0 ] && pass "vitest 本任务文件通过" || fail "vitest rc=$rc"
  grep -Eq 'Tests +[1-9][0-9]* passed' "$O/vitest.txt" || fail "vitest 无通过用例"
  BE=$(cd "$WT" && { git diff --name-only master -- 'cpq-backend/src/test/java/**'; git ls-files --others --exclude-standard -- 'cpq-backend/src/test/java/**'; } | grep -E 'Test\.java$' | sort -u | xargs -r -n1 basename | sed 's/\.java$//' | paste -sd, -)
  BE=${BE_TEST_CLASS:-$BE}
  echo "本任务后端测试类: $BE" | tee "$O/be-classes.txt"
  [ -n "$BE" ] || fail "未识别到本任务后端测试类"
  if pgrep -af "java.*$WT/cpq-backend" | grep -v pgrep; then fail "worktree 内已有 java 进程（含临时后端 quarkus:dev 会改写 target/），先 stack.sh stop"; else
    START=$(date +%s); cd "$WT/cpq-backend"
    DB_NAME=$DBNAME ./mvnw test -Dtest="$BE" -Dsurefire.failIfNoSpecifiedTests=true -Dquarkus.http.test-port=0 > "$O/mvn.txt" 2>&1; rc=$?
    grep -E 'Tests run:|BUILD|ERROR' "$O/mvn.txt" | tail -15
    [ $rc -eq 0 ] && pass "mvn 本任务测试 rc=0" || fail "mvn rc=$rc"
    for c in ${BE//,/ }; do f=$(ls target/surefire-reports/TEST-*."$c".xml 2>/dev/null | head -1)
      [ -n "$f" ] && [ "$(stat -c %Y "$f")" -ge "$START" ] && echo "  $c: $(grep -o 'tests="[0-9]*"[^>]*failures="[0-9]*"' "$f" | head -1)" || fail "$c 无本轮 surefire 报告"; done
    n=$(psql -h $DBHOST -U postgres -d cpq_db_test -At -c "select count(*) from flyway_schema_history where description ilike '%repair260916%subtotal%'")
    [ "$n" = 0 ] && pass "cpq_db_test 未落本任务迁移" || fail "cpq_db_test 出现本任务迁移（越界）"
  fi
  r=$(q "select success from flyway_schema_history where description ilike '%repair260916%subtotal%'"); [ "$r" = t ] && pass "迁移 success=t（一次性库）" || fail "迁移记录 '$r'"
  echo "RESULT FAILS=$FAILS" | tee "$O/static-result.txt" ;;
e2e)
  who=${2:?branch|master}; pw_guard
  if [ "$who" = branch ]; then
    TREE=$WT
    if [ -n "${SB_CMD:-}" ]; then ( cd "$WT/cpq-frontend" && eval "$SB_CMD" ) > "$O/sb-rerun.log" 2>&1; echo "[S-B 复跑] rc=$? → sb-rerun.log"; else echo "[S-B 复跑] 未提供 SB_CMD，未验证" | tee "$O/sb-rerun.log"; fi
    ( cd "$WT/cpq-frontend" && PW_BASE_URL=http://localhost:5293 PW_BACKEND_URL=http://localhost:8293 npx playwright test -c e2e/repair260916-sc.config.ts ) > "$O/sc-spec.log" 2>&1; echo "[S-C spec] rc=$?"
  else TREE=${MASTER_WT:-/home/joii/project/cpq/.claude/worktrees/repair-260916-master-ab}; fi
  pw_json "$TREE" "$who" e2e/tabjoin-formula-drawer.spec.ts
  pw_json "$TREE" "$who" e2e/quotation-flow.spec.ts ;;
report)
  python3 - "$O" <<'PY' | tee "$O/AB对照.md"
import json, os, sys
O = sys.argv[1]
def res(lab, spec):
    p = os.path.join(O, f'pw-{lab}-{spec}.json')
    if not os.path.exists(p): return None
    out = {}
    def walk(s, pre=''):
        for sp in s.get('specs', []):
            for t in sp.get('tests', []):
                st = [r.get('status') for r in t.get('results', [])]
                out[pre + sp['title']] = st[-1] if st else 'notrun'
        for c in s.get('suites', []): walk(c, pre + (c.get('title', '') + ' › ' if c.get('title') and not c['title'].endswith('.ts') else ''))
    for s in json.load(open(p)).get('suites', []): walk(s)
    return out
for spec, req in (('tabjoin-formula-drawer', '无新增失败'), ('quotation-flow', '仅对照（DEC-0008）')):
    b, m = res('branch', spec), res('master', spec)
    print(f'\n### {spec}（要求：{req}）\n\n| 用例 | 本分支 | master | 结论 |\n|---|---|---|---|')
    if b is None or m is None: print(f'| — | {"缺" if b is None else "有"} | {"缺" if m is None else "有"} | 未验证 |'); continue
    if not b and not m: print('| — | 0 条 | 0 条 | ⚠️ 空跑 |')
    for k in sorted(set(b) | set(m)):
        bb, mm = b.get(k, '缺'), m.get(k, '缺')
        verdict = '一致' if bb == mm else ('🔴 新增失败' if mm == 'passed' and bb != 'passed' else '不一致（逐条归因）')
        print(f'| {k} | {bb} | {mm} | {verdict} |')
PY
  ;;
*) sed -n 2,10p "$0";;
esac
[ "${1:-}" = report ] || echo "RESULT FAILS=$FAILS"; [ $FAILS -eq 0 ]
