#!/usr/bin/env bash
# T-A3 · AC-12 · 计算规则不变
# 断言来源：问题说明.md ⑥ AC-12
#   ① 前后端共享夹具 cross-tab-cases.json 两端测试全部通过（含命中 0 行 / 命中多行 / 非数值三类）
#   ② formulaEngine.ts 与 FormulaCalculator.java 相对 master 0 行改动
#   ③ 对既有测试断言的改动只出现在 test.md §5 清单内
# 用法：bash <T>/证据/测试/S-A/ta3-rules-unchanged.sh [fe|be|git|all]（默认 all）
# 退出码：0 全部通过；1 有 FAIL；2 前置不成立
# 约束：后端只用 DB_NAME=cpq_db_rp0916c、随机测试端口；vitest 不起 server，不占端口。
set -uo pipefail
PART="${1:-all}"
WT=/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix
S="$WT/dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用/证据/测试/S-A"
OUT="$S/输出/ta3-$(date +%y%m%d-%H%M%S)"; mkdir -p "$OUT"
FE_FIX=cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json
BE_FIX=cpq-backend/src/test/resources/cross-tab-cases.json
ENG_FE=cpq-frontend/src/utils/formulaEngine.ts
ENG_BE=cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
FAILS=0
pass(){ echo "PASS [$1] $2"; }
fail(){ echo "FAIL [$1] $2"; FAILS=$((FAILS+1)); }
g(){ git -C "$WT" "$@"; }
echo "# T-A3 part=$PART at=$(date -Is) out=$OUT HEAD=$(g rev-parse --short HEAD) master=$(g rev-parse --short master) merge-base=$(g merge-base master HEAD | cut -c1-8)"
NCASES=$(python3 -c "import json;print(len(json.load(open('$WT/$FE_FIX'))))")
echo "[pre] cross-tab-cases.json 用例数=$NCASES"
[ "$NCASES" -gt 0 ] || { echo "ABORT: 夹具为空"; exit 2; }
for n in 'NONE zero match' 'NONE multi match error path' 'non-numeric target in SUM error path'; do
  python3 -c "import json,sys;sys.exit(0 if any(c.get('name')==sys.argv[1] for c in json.load(open('$WT/$FE_FIX'))) else 1)" "$n" \
    || { echo "ABORT: 夹具缺三类之一：$n"; exit 2; }
done

if [ "$PART" = all ] || [ "$PART" = git ]; then
  echo; echo "== ① 夹具守卫：两份逐字节相同、且相对基线未被改（否则「全部通过」不说明规则未变）"
  sha256sum "$WT/$FE_FIX" "$WT/$BE_FIX" | tee "$OUT/fixture-sha.txt"
  [ "$(sha256sum < "$WT/$FE_FIX")" = "$(sha256sum < "$WT/$BE_FIX")" ] && pass 'AC-12①-夹具一致' '两份 sha256 相同' || fail 'AC-12①-夹具一致' '两份 sha256 不同'
  D=$(g diff --stat "$(g merge-base master HEAD)" -- "$FE_FIX" "$BE_FIX")
  [ -z "$D" ] && pass 'AC-12①-夹具未改' '相对分叉点 0 改动' || fail 'AC-12①-夹具未改' "夹具被改：$D"

  echo; echo "== ② 两引擎文件 0 行改动"
  for f in "$ENG_FE" "$ENG_BE"; do
    [ -f "$WT/$f" ] && g cat-file -e "master:$f" 2>/dev/null || { echo "ABORT: 路径不存在 $f（空 diff 不可信）"; exit 2; }
  done
  # 阳性对照：同一 pathspec 在「该文件最后一次改动的提交」上必须能拿到非空 diff（证明命令能看见改动）
  for f in "$ENG_FE" "$ENG_BE"; do
    C=$(g log -1 --format=%H master -- "$f")
    PC=$(g diff --stat "$C^" "$C" -- "$f")
    [ -n "$PC" ] && echo "[对照] $f 在 ${C:0:8} 的改动可见：$PC" || { echo "ABORT: 阳性对照为空，判据失效"; exit 2; }
  done
  LIT=$(g diff --stat master -- "$ENG_FE" "$ENG_BE"); echo "\$ git diff --stat master -- <两文件>  → '${LIT}'" | tee "$OUT/engine-diff.txt"
  MB=$(g diff --stat "$(g merge-base master HEAD)" -- "$ENG_FE" "$ENG_BE"); echo "\$ git diff --stat \$(merge-base) -- <两文件> → '${MB}'" | tee -a "$OUT/engine-diff.txt"
  [ -z "$LIT" ] && pass 'AC-12②' 'git diff --stat master 为空' || fail 'AC-12②' "非空（若 merge-base 版本为空，说明是 master 前进所致，需主线判定）：$LIT"
  [ -z "$MB" ] || fail 'AC-12②-本分支' "本分支相对分叉点改了引擎：$MB"

  echo; echo "== ③ 既有测试断言改动审阅（判定规则见 ta3_review_test_diff.py 顶部）"
  BASE=$(g merge-base master HEAD)
  SPEC=( '*test*' '*Test*' ':(glob)cpq-frontend/src/**/__fixtures__/**' ':(glob)cpq-frontend/e2e/**' ':(glob)cpq-backend/src/test/**' ':(glob,exclude)dev-docs/**' ':(glob,exclude)docs/**' )
  g diff "$BASE" -- "${SPEC[@]}" > "$OUT/test-diff.patch"
  g diff --name-status "$BASE" -- "${SPEC[@]}" > "$OUT/test-name-status.txt"
  g ls-files --others --exclude-standard -- "${SPEC[@]}" > "$OUT/test-untracked.txt"
  g diff --stat master -- '*test*' > "$OUT/test-diff-stat-literal.txt"   # 派工要求的字面命令，留档
  echo "[info] 改动文件："; cat "$OUT/test-name-status.txt"; echo "[info] 未跟踪："; cat "$OUT/test-untracked.txt"
  python3 "$S/ta3_review_test_diff.py" "$OUT/test-diff.patch" "$OUT/test-name-status.txt" "$OUT/test-untracked.txt" || FAILS=$((FAILS+1))
fi

if [ "$PART" = all ] || [ "$PART" = fe ]; then
  echo; echo "== ① 前端夹具测试（vitest）"
  ( cd "$WT/cpq-frontend" && npx vitest run src/utils/formulaEngine.test.ts src/pages/quotation/crossTabOrder.test.ts \
      --reporter=verbose --reporter=json --outputFile.json="$OUT/vitest.json" ) > "$OUT/vitest.log" 2>&1
  RC=$?; tail -n 15 "$OUT/vitest.log"
  python3 - "$OUT/vitest.json" "$RC" "$NCASES" <<'PY' || FAILS=$((FAILS+1))
import json, sys
p, rc, n = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
try: d = json.load(open(p))
except Exception as e: print('FAIL [AC-12①-前端] 无 json 报告：', e); sys.exit(1)
fx = [a for f in d['testResults'] for a in f['assertionResults'] if 'cross-tab fixture' in a.get('ancestorTitles', [])]
bad = [a['title'] for a in fx if a['status'] != 'passed']
three = {t: next((a['status'] for a in fx if a['title'] == t), '缺失') for t in ('NONE zero match', 'NONE multi match error path', 'non-numeric target in SUM error path')}
print(f"[vitest] exit={rc} 总={d.get('numTotalTests')} 失败={d.get('numFailedTests')} 跳过={d.get('numPendingTests')} 夹具用例执行={len(fx)}/{n} 三类={three}")
ok = rc == 0 and d.get('numFailedTests') == 0 and len(fx) == n and not bad and all(v == 'passed' for v in three.values())
print('PASS' if ok else 'FAIL', '[AC-12①-前端]', '非通过：' + str(bad) if bad else '')
sys.exit(0 if ok else 1)
PY
fi

if [ "$PART" = all ] || [ "$PART" = be ]; then
  echo; echo "== ① 后端夹具测试（AP-55 写法）"
  BUSY=$(pgrep -af 'maven|mvnw|quarkus' | grep -F "$WT" || true)
  echo "[pre] $(date -Is) 采样本 worktree 的 maven/quarkus 进程：${BUSY:-无}"
  [ -z "$BUSY" ] || { echo "ABORT: 有其他构建在写 target/"; exit 2; }
  START=$(date +%s)
  ( cd "$WT/cpq-backend" && DB_NAME=cpq_db_rp0916c ./mvnw -B test \
      -Dtest='FormulaCalculator*Test,CrossTabComponentOrderTest,CardSnapshot*Test' \
      -Dquarkus.http.test-port=0 -Dquarkus.http.test-ssl-port=0 ) > "$OUT/mvn.log" 2>&1
  RC=$?; tail -n 25 "$OUT/mvn.log"
  python3 - "$WT/cpq-backend/target/surefire-reports" "$START" "$RC" "$NCASES" "$OUT" <<'PY' || FAILS=$((FAILS+1))
import glob, os, shutil, sys, xml.etree.ElementTree as ET
d, start, rc, n, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
fresh = [p for p in glob.glob(d + '/TEST-*.xml') if os.path.getmtime(p) >= start]
rows = {}
for p in fresh:
    r = ET.parse(p).getroot(); shutil.copy(p, out)
    rows[r.get('name')] = {k: int(r.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}
for k, v in sorted(rows.items()): print('  ', k, v)
gate = ['com.cpq.quotation.service.FormulaCalculatorCrossTabFixtureTest', 'com.cpq.quotation.service.FormulaCalculatorCrossTabHostDiagTest']
ok = True
for g in gate:
    v = rows.get(g)
    if v is None: print('FAIL [AC-12①-后端]', g, '本次未产出报告（未执行/编译失败）'); ok = False; continue
    good = v['failures'] == 0 and v['errors'] == 0 and v['skipped'] == 0 and v['tests'] > 0
    if g.endswith('FixtureTest') and v['tests'] < n: good = False; print(f'  夹具测试数 {v["tests"]} < 夹具用例 {n}')
    print('PASS' if good else 'FAIL', '[AC-12①-后端]', g, v); ok &= good
others = {k: v for k, v in rows.items() if k not in gate and (v['failures'] or v['errors'])}
if others: print('INFO 非夹具类有失败（不属 AC-12①，需主线与 master A/B 归因）：', others)
print(f'[mvn] exit={rc}（非 0 可能来自非夹具类，见上）')
sys.exit(0 if ok else 1)
PY
fi

echo; echo "==== T-A3 SUMMARY: FAIL 组数=$FAILS ===="
[ "$FAILS" -eq 0 ]
