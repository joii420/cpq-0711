#!/usr/bin/env bash
# T-C1（AC-14 a~e + 夹具 sha256 相同）
# 需主线告知：BE_TEST_CLASS（后端读夹具的测试类名，可逗号分隔）；前端测试文件自动按夹具名检索，也可用 FE_TEST_FILES 覆盖。
# 前置：同一 worktree 内没有其他 maven / quarkus:dev 在写 target/（testing.md §4.2.5）。
source "$(dirname "$0")/common.sh"; guard_wt; guard_db
O="$S/out-C1"; mkdir -p "$O"; FAILS=0; START=$(date +%s)
BEF="$WT/cpq-backend/src/test/resources/tabjoin-excel-cases.json"
FEF="$WT/cpq-frontend/src/pages/quotation/__fixtures__/tabjoin-excel-cases.json"
echo "== 1. sha256 ==" | tee "$O/log.txt"
[ -f "$BEF" ] && [ -f "$FEF" ] || { fail "夹具文件缺失 BE=$BEF FE=$FEF"; exit 1; }
sha256sum "$BEF" "$FEF" | tee -a "$O/log.txt"
[ "$(sha256sum < "$BEF")" = "$(sha256sum < "$FEF")" ] && pass "两份夹具 sha256 相同" || fail "两份夹具 sha256 不同"
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
[ -n "${BE_TEST_CLASS:-}" ] || { fail "未提供 BE_TEST_CLASS（等主线告知）"; }
if [ -n "${BE_TEST_CLASS:-}" ]; then
  if pgrep -af "java.*$WT/cpq-backend" | grep -v pgrep; then fail "worktree 内已有 java/maven 进程在跑，停止以免互删 target/"; else
  cd "$WT/cpq-backend"
  DB_NAME=$DBNAME ./mvnw -q test -Dtest="$BE_TEST_CLASS" -Dsurefire.failIfNoSpecifiedTests=true -Dquarkus.http.test-port=0 > "$O/mvn.txt" 2>&1; rc=$?
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
  # 连库身份确权：测试跑的是一次性库 ⇒ 本任务迁移出现在 cpq_db_rp0916c，且未出现在 cpq_db_test
  q "select version,description,success from flyway_schema_history where description ilike '%repair260916%subtotal%'" | tee -a "$O/log.txt"
  n=$(psql -h $DBHOST -U postgres -d cpq_db_test -At -c "select count(*) from flyway_schema_history where description ilike '%repair260916%subtotal%'")
  [ "$n" = "0" ] && pass "cpq_db_test 未落本任务迁移" || fail "cpq_db_test 出现本任务迁移 $n 条（越界！立即报主线）"
  fi
fi
echo "== 5. 期望值逐条（从两端测试输出中摘录，供人工复核）==" | tee -a "$O/log.txt"
grep -a -E 'AC-14|1\.978941064' "$O/vitest.txt" "$O/mvn.txt" 2>/dev/null | head -40 | tee -a "$O/log.txt"
echo "RESULT FAILS=$FAILS" | tee -a "$O/log.txt"; [ $FAILS -eq 0 ]
