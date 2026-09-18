#!/usr/bin/env bash
# T-C1（AC-14 a~e、g + 夹具 sha256 相同）
# BE_TEST_CLASS 默认 com.cpq.quotation.service.tabjoin.TabJoinExcelSharedFixtureTest（主线 2026-09-17 告知）；前端测试文件自动按夹具名检索，也可用 FE_TEST_FILES 覆盖。
# 前置：同一 worktree 内没有其他 maven / quarkus:dev 在写 target/（testing.md §4.2.5）。
source "$(dirname "$0")/common.sh"; guard_wt; guard_db
O="$S/out-C1"; mkdir -p "$O"; FAILS=0; START=$(date +%s)
BEF="$WT/cpq-backend/src/test/resources/tabjoin-excel-cases.json"
FEF="$WT/cpq-frontend/src/pages/quotation/__fixtures__/tabjoin-excel-cases.json"
echo "== 1. sha256 ==" | tee "$O/log.txt"
[ -f "$BEF" ] && [ -f "$FEF" ] || { fail "夹具文件缺失 BE=$BEF FE=$FEF"; exit 1; }
sha256sum "$BEF" "$FEF" | tee -a "$O/log.txt"
[ "$(sha256sum < "$BEF")" = "$(sha256sum < "$FEF")" ] && pass "两份夹具 sha256 相同" || fail "两份夹具 sha256 不同"
EXP_SHA=${FIXTURE_SHA:-4fc65244d161e499f6be1398229be8960661141eb84f23dec7d81ceff57a946d}
[ "$(sha256sum < "$BEF" | cut -d" " -f1)" = "$EXP_SHA" ] && pass "夹具即主线审过的版本（sha256=$EXP_SHA）" || fail "夹具 sha256 与主线告知的 $EXP_SHA 不同（夹具又被改过？报主线）"
cp "$BEF" "$O/tabjoin-excel-cases.json"
echo "== 2. 夹具内容 vs AC-14 前置（独立重算）==" | tee -a "$O/log.txt"
python3 "$S/check_fixture.py" "$BEF" | tee "$O/fixture-check.txt"
grep -q '^RESULT fails=0$' "$O/fixture-check.txt" && pass "夹具内容核对" || fail "夹具内容核对（见 fixture-check.txt）"
echo "== 3. 前端测试（vitest）==" | tee -a "$O/log.txt"
cd "$WT/cpq-frontend"
FE_TEST_FILES=${FE_TEST_FILES:-$(/usr/bin/grep -rla --include='*.test.ts' --include='*.test.tsx' 'tabjoin-excel-cases' src | tr '\n' ' ')}
[ -n "${FE_TEST_FILES// /}" ] || fail "找不到读取夹具的前端测试文件"
echo "FE_TEST_FILES=$FE_TEST_FILES" | tee -a "$O/log.txt"
npx vitest run $FE_TEST_FILES --reporter=verbose > "$O/vitest.txt" 2>&1; rc=$?
tail -30 "$O/vitest.txt"
[ $rc -eq 0 ] && pass "vitest 退出码 0" || fail "vitest 退出码 $rc"
grep -Eq 'Tests +[1-9][0-9]* passed' "$O/vitest.txt" && pass "vitest 通过用例数 >0" || fail "vitest 未见通过用例（空跑？）"
grep -Eq 'Tests .*(failed|skipped)' "$O/vitest.txt" && fail "vitest 有 failed/skipped" || true
echo "== 4. 后端测试（DB_NAME=$DBNAME）==" | tee -a "$O/log.txt"
BE_TEST_CLASS=${BE_TEST_CLASS:-com.cpq.quotation.service.tabjoin.TabJoinExcelSharedFixtureTest}
if [ -n "${BE_TEST_CLASS:-}" ]; then
  if pgrep -af "java.*$WT/cpq-backend" | grep -v pgrep; then fail "worktree 内已有 java/maven 进程在跑，停止以免互删 target/"; else
  cd "$WT/cpq-backend"
  ( for i in $(seq 1 90); do psql -h $DBHOST -U postgres -d postgres -At -c "select datname, application_name, count(*) from pg_stat_activity where datname in ('$DBNAME','cpq_db_test','cpq_db_0724') and backend_start > to_timestamp($START) group by 1,2" >> "$O/be-conn.txt"; sleep 2; done ) & SAMPLER=$!
  DB_NAME=$DBNAME ./mvnw -q test -Dtest="$BE_TEST_CLASS" -Dsurefire.failIfNoSpecifiedTests=true -Dquarkus.http.test-port=0 > "$O/mvn.txt" 2>&1; rc=$?
  kill $SAMPLER 2>/dev/null; sort -u "$O/be-conn.txt" -o "$O/be-conn.txt"; echo "[连接采样]"; cat "$O/be-conn.txt"
  echo "mvn rc=$rc"; tail -40 "$O/mvn.txt"
  [ $rc -eq 0 ] && pass "mvn 退出码 0" || fail "mvn 退出码 $rc"
  IFS=',' read -ra CLS <<< "$BE_TEST_CLASS"
  for c in "${CLS[@]}"; do
    f=$(ls target/surefire-reports/TEST-*"${c##*.}".xml 2>/dev/null | head -1)
    if [ -z "$f" ] || [ "$(stat -c %Y "$f")" -lt "$START" ]; then fail "$c 无本轮 surefire 报告（缺失或 mtime 早于本轮开始）"; continue; fi
    cp "$f" "$O/"
    python3 - "$f" <<'PY'
import sys, xml.etree.ElementTree as E
r = E.parse(sys.argv[1]).getroot()
t, f, e, s = (int(r.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped'))
print(f'[surefire] {r.get("name")} tests={t} failures={f} errors={e} skipped={s}')
for tc in r.iter('testcase'): print('   ', tc.get('name'))
sys.exit(0 if t > 0 and f == 0 and e == 0 and s == 0 else 1)
PY
    [ $? -eq 0 ] && pass "$c surefire: tests>0 且 0 失败 0 跳过" || fail "$c surefire 不达标"
  done
  # 连库确权：本轮测试期间 cpq_db_rp0916d 上出现过测试进程的连接（pg_stat_activity 采样见 be-conn.txt）
  grep -q "$DBNAME" "$O/be-conn.txt" && pass "测试运行期间观测到连向 $DBNAME 的连接" || fail "未观测到连向 $DBNAME 的连接（测试可能连了别的库，报主线）"
  fi
fi
echo "== 5. 期望值逐条（从两端测试输出中摘录，供人工复核）==" | tee -a "$O/log.txt"
grep -a -E 'AC-14|1\.978941064|1\.13' "$O/vitest.txt" "$O/mvn.txt" 2>/dev/null | head -40 | tee -a "$O/log.txt"
echo "RESULT FAILS=$FAILS" | tee -a "$O/log.txt"; [ $FAILS -eq 0 ]
