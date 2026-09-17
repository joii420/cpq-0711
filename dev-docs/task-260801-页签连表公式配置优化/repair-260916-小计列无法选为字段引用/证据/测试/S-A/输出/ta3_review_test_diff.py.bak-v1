"""T-A3 ③ 判定：本分支对「既有」测试文件的改动是否只落在 test.md §5 清单内、且只属两类。

判定规则（写死，不做语义猜测）：
  R0 只审「已存在于基线的测试文件」的修改（git name-status = M / D / R）；新增文件（A / 未跟踪）是新用例，不属于「改既有断言」，只列出。
  R1 被修改的既有文件必须在白名单内：
       cpq-frontend/src/pages/component/formulaSerialize.test.ts
       cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts
     （§5 后端 TabJoin*Test 为「预计无」→ 任何后端既有测试被改都判 FLAG）
     删除（D）/ 重命名（R）既有测试文件 → FLAG。
  R2 白名单文件内，每一条删除行（-）都必须能配到同一 hunk 内的一条新增行（+），且满足
         新增行.replace('(小计)]', ']') == 删除行
     即两行的唯一差别是在若干个 `]` 前插入了 `(小计)`——这恰是 §5 的两类：
       ① 输入文字 [页签.列] → [页签.列(小计)]；② 期望回显给 component_subtotal 加 (小计)。
     配不上的删除行 → FLAG（可能改了 token 结构 / 数值 / 用例名，需主线人工裁决）。
     纯新增行（没有对应删除行）视为新增断言，放行但列出条数。
  R3 R2 配对还要求该删除行本身含 `[` —— 不含引用文字的行被改，一律 FLAG。

用法：python3 ta3_review_test_diff.py <diff 文件> <name-status 文件> <untracked 列表文件>
退出码：0 全部符合；1 有 FLAG（需主线人工判定）
"""
import re, sys

ALLOW = {
    'cpq-frontend/src/pages/component/formulaSerialize.test.ts',
    'cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts',
}
diff_path, ns_path, untracked_path = sys.argv[1:4]
flags, info = [], []

for line in open(ns_path, encoding='utf8'):
    line = line.rstrip('\n')
    if not line:
        continue
    parts = line.split('\t')
    st, path = parts[0], parts[-1]
    if st.startswith('A'):
        info.append(f'新增测试文件（不属既有断言）: {path}')
    elif st.startswith('M'):
        if path not in ALLOW:
            flags.append(f'R1 清单外既有测试文件被修改: {path}')
    else:
        flags.append(f'R1 既有测试文件 {st}: {" -> ".join(parts[1:])}')
for line in open(untracked_path, encoding='utf8'):
    if line.strip():
        info.append(f'未跟踪新测试文件（不属既有断言）: {line.strip()}')

cur, hunk_minus, hunk_plus, hunk_head = None, [], [], ''
stats = {}

def close_hunk():
    if cur is None or cur not in ALLOW:
        return
    plus_pool = list(hunk_plus)
    ok_pairs = 0
    for m in hunk_minus:
        hit = None
        for i, p in enumerate(plus_pool):
            if '[' in m and '(小计)]' in p and p.replace('(小计)]', ']') == m:
                hit = i
                break
        if hit is None:
            flags.append(f'R2/R3 {cur} {hunk_head}\n      - {m.strip()}\n      （同 hunk 无「仅插入 (小计)」的对应新增行）')
        else:
            plus_pool.pop(hit)
            ok_pairs += 1
    s = stats.setdefault(cur, {'合规改写对': 0, '纯新增行': 0})
    s['合规改写对'] += ok_pairs
    s['纯新增行'] += len(plus_pool)

for raw in open(diff_path, encoding='utf8', errors='replace'):
    line = raw.rstrip('\n')
    if line.startswith('diff --git '):
        close_hunk(); hunk_minus, hunk_plus = [], []
        cur = re.sub(r'^diff --git a/(.*) b/.*$', r'\1', line)
    elif line.startswith('@@'):
        close_hunk(); hunk_minus, hunk_plus, hunk_head = [], [], line
    elif line.startswith('---') or line.startswith('+++'):
        continue
    elif line.startswith('-'):
        hunk_minus.append(line[1:])
    elif line.startswith('+'):
        hunk_plus.append(line[1:])
close_hunk()

for i in info:
    print('INFO', i)
for k, v in stats.items():
    print('INFO', k, v)
for f in flags:
    print('FLAG', f)
print(('PASS' if not flags else 'FAIL'), f'[AC-12③] 既有测试断言改动审阅：FLAG {len(flags)} 条')
sys.exit(1 if flags else 0)
