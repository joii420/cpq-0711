#!/usr/bin/env bash
# T-A2 · AC-11（后端部分）· 执行后端 B-6 测试类并摘录 H85 两个值
# 断言来源：问题说明.md ⑥ AC-11 ③④ 的后端侧 —— FormulaCalculator 算 H85：改写后 0.212585104，不改写 0.463735546
# 用法（在 worktree 内执行；类名解锁时由主线告知）：
#   bash <T>/证据/测试/S-A/ta2-backend-h85.sh <B-6 测试类简单名或全名>
# 退出码：0 通过；1 断言失败；2 前置/环境不成立（不构成证据）
# 约束：只用 DB_NAME=cpq_db_rp0916c；随机测试端口（不占 8081/8293~8295）；
#       不用 -Dsurefire.reportsDirectory（testing.md §4.2.5：surefire 3.5.4 静默忽略），改按 mtime 确权。
set -uo pipefail
CLASS="${1:?用法: ta2-backend-h85.sh <B-6 测试类名>}"
SIMPLE="${CLASS##*.}"
WT=/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix
BE="$WT/cpq-backend"
S="$WT/dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用/证据/测试/S-A"
TS=$(date +%y%m%d-%H%M%S)
OUT="$S/输出/ta2-$TS"
mkdir -p "$OUT"
LOG="$OUT/mvn.log"
echo "# T-A2 class=$CLASS at=$(date -Is) out=$OUT"

# 前置 1：没有其他 maven / quarkus:dev 在写本 worktree 的 target/（§4.2.5）
BUSY=$(pgrep -af 'maven|mvnw|quarkus' | grep -F "$WT" | grep -v -F "ta2-backend-h85.sh" || true)
echo "[pre] $(date -Is) 采样本 worktree 的 maven/quarkus 进程：${BUSY:-无}"
if [ -n "$BUSY" ]; then echo "ABORT: 有其他构建在写 $BE/target"; exit 2; fi

# 前置 2：测试源文件存在，且其中确有两个期望值（证明测试断言的是 AC 数值）
SRC=$(find "$BE/src/test" -name "$SIMPLE.java" | head -n 5)
echo "[pre] 测试源：${SRC:-未找到}"
if [ -z "$SRC" ] || [ "$(printf '%s\n' "$SRC" | wc -l)" -ne 1 ]; then echo "ABORT: $SIMPLE.java 未找到或不唯一"; exit 2; fi
/usr/bin/grep -an '0\.212585104\|0\.463735546\|0\.21258510[0-9]*\|0\.46373554[0-9]*' "$SRC" | tee "$OUT/src-literals.txt"
if ! /usr/bin/grep -aq '0\.21258510' "$SRC" || ! /usr/bin/grep -aq '0\.46373554' "$SRC"; then
  echo "FAIL [AC-11③④-后端] 测试源里找不到两个期望值字面量（无法证明它断言的是 AC 数值）"; SRCOK=0; else SRCOK=1; fi

START=$(date +%s)
( cd "$BE" && DB_NAME=cpq_db_rp0916c ./mvnw -B test -Dtest="$CLASS" \
    -Dquarkus.http.test-port=0 -Dquarkus.http.test-ssl-port=0 ) >"$LOG" 2>&1
RC=$?
echo "[run] mvn exit=$RC（日志 $LOG，尾部如下）"; tail -n 25 "$LOG"

REP=$(find "$BE/target/surefire-reports" -maxdepth 1 -name "TEST-*$SIMPLE.xml" -newermt "@$START" 2>/dev/null | head -n 1)
if [ -z "$REP" ]; then echo "FAIL [AC-11-后端] 本次运行没有产出 TEST-*$SIMPLE.xml（类名错 / 编译失败 / 报告是旧残留）"; exit 1; fi
cp "$REP" "$OUT/"
OUTTXT="${REP%.xml}-output.txt"; OUTTXT="${OUTTXT/TEST-/}"
[ -f "$OUTTXT" ] && cp "$OUTTXT" "$OUT/"

python3 - "$REP" "$OUTTXT" "$RC" "$SRCOK" <<'PY'
import re, sys, xml.etree.ElementTree as ET, os
rep, outtxt, rc, srcok = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4] == '1'
r = ET.parse(rep).getroot()
n = {k: int(r.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}
print('[surefire]', rep, n)
text = open(rep, encoding='utf8', errors='replace').read()
if os.path.exists(outtxt): text += open(outtxt, encoding='utf8', errors='replace').read()
for tc in r.iter('testcase'):
    st = 'FAILED' if tc.find('failure') is not None or tc.find('error') is not None else ('SKIPPED' if tc.find('skipped') is not None else 'ok')
    print(f"   {tc.get('name')}: {st}")
vals = sorted(set(re.findall(r'0\.(?:21258510|46373554)\d*', text)))
print('[摘录] 报告/stdout 中出现的 H85 数值：', vals or '无（测试未打印实际值 → 实际值「未验证」，仅能凭断言通过推定）')
ok = rc == 0 and n['tests'] > 0 and n['failures'] == 0 and n['errors'] == 0 and n['skipped'] == 0 and srcok
print(('PASS' if ok else 'FAIL'), f"[AC-11③④-后端] mvn exit={rc} tests={n['tests']} failures={n['failures']} errors={n['errors']} skipped={n['skipped']}（skipped>0 视为未执行）")
printed = any(v.startswith('0.21258510') for v in vals) and any(v.startswith('0.46373554') for v in vals)
print('INFO 两个实际值均已打印' if printed else 'WARN 实际值未同时出现在输出中，需 B-6 测试打印实际值后才算留证')
sys.exit(0 if ok else 1)
PY
