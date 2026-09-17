"""T-A3 ③ 判定：本分支对「既有」测试文件的改动是否只落在 test.md §5 清单内、且只属两类。

判定规则（写死，不做语义猜测）：
  R0 只审「已存在于基线的测试文件」的修改（git name-status = M / D / R）；新增文件（A / 未跟踪）是新用例，不属于「改既有断言」，只列出。
  R1 被修改的既有文件必须在白名单内：
       cpq-frontend/src/pages/component/formulaSerialize.test.ts
       cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts
     （§5 后端 TabJoin*Test 为「预计无」→ 任何后端既有测试被改都判 FLAG）
     删除（D）/ 重命名（R）既有测试文件 → FLAG。
  R2 白名单文件内，每一条删除行（-）都必须能配到同一 hunk 内的一条新增行（+），且满足
         去掉新增行里「紧跟列名的 (小计)」后 == 删除行，且删除行本身不含 (小计)
     （2026-09-17 修订：原写法要求 `(小计)]` 相连，漏掉了 `[ 页签.列(小计) ]` 与无括号引用体两种形态）
     即两行的唯一差别是在若干个 `]` 前插入了 `(小计)`——这恰是 §5 的两类：
       ① 输入文字 [页签.列] → [页签.列(小计)]；② 期望回显给 component_subtotal 加 (小计)。
     配不上的删除行 → FLAG（可能改了 token 结构 / 数值 / 用例名，需主线人工裁决）。
     纯新增行（没有对应删除行）视为新增断言，放行但列出条数。
  R3 （已并入 R2：插入位置必须紧跟列名字符，因此不含引用的行无法配对，一律 FLAG。）
  R4（§5 第③类，2026-09-17 补）删除行与新增行的差异仅为 'yellow'→'purple' 和/或 'subtotal'→'self-field'
     （即 新增行.replace('purple','yellow').replace('self-field','subtotal') == 删除行），
     且该删除行所在用例标题含「宿主小计列」→ 放行；其他用例里出现同样改动 → FLAG。
     用例标题本身被改写（如 yellow→purple 以外的措辞变化）不自动放行，FLAG 交主线判定。

用法：python3 ta3_review_test_diff.py <diff 文件> <name-status 文件> <untracked 列表文件>
退出码：0 全部符合；1 有 FLAG（需主线人工判定）
"""
import re, sys

ALLOW = {
    'cpq-frontend/src/pages/component/formulaSerialize.test.ts',
    'cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts',
    'cpq-frontend/src/pages/quotation/subtotalColRefEndToEnd.test.ts',  # test.md §5 2026-09-17 补全
}
# §5 第③类（本页签列着色 黄→紫）只允许出现在这条用例里（按用例标题子串识别）
RULE3_TITLE = '宿主小计列'
# R2 修订（2026-09-17 首跑后）：「(小计)」紧跟在列名字符之后（前一字符非空白、非括号、非引号），
# 覆盖 '[页签.列(小计)]'、'[ 页签.列(小计) ]'、classifyRefSegment 的无括号引用体 '页签.列(小计)' 三种写法
SUFFIX_RE = re.compile(r'(?<=[^\s\[\]()（）\'"`])\(小计\)')
TITLE_RE = re.compile(r'\b(?:it|test)\(\s*[\'"`](.+?)[\'"`]')
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

cur, hunk_minus, hunk_plus, hunk_head, title = None, [], [], '', ''
stats = {}

def close_hunk():
    if cur is None or cur not in ALLOW:
        return
    plus_pool = list(hunk_plus)
    ok_pairs = 0
    for m, mtitle in hunk_minus:
        hit = None
        for i, p in enumerate(plus_pool):
            if p != m and SUFFIX_RE.sub('', p) == m and '(小计)' not in m:
                hit = i
                break
            if RULE3_TITLE in mtitle and p != m and p.replace('purple', 'yellow').replace('self-field', 'subtotal') == m:
                hit = i
                s3 = stats.setdefault(cur, {'合规改写对': 0, '纯新增行': 0}); s3['其中第③类'] = s3.get('其中第③类', 0) + 1
                break
        if hit is None:
            flags.append(f'R2/R3/R4 {cur} 用例「{mtitle}」 {hunk_head}\n      - {m.strip()}\n      （同 hunk 无「仅插入 (小计)」或「仅 黄→紫（限宿主小计列用例）」的对应新增行）')
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
        mt = TITLE_RE.search(line)
        title = mt.group(1) if mt else ''  # 新 hunk 清空标题，防止上一 hunk 尾部上下文的 it( 串到本 hunk
    elif line.startswith('---') or line.startswith('+++'):
        continue
    elif line.startswith('-'):
        mt = TITLE_RE.search(line)
        if mt: title = mt.group(1)
        hunk_minus.append((line[1:], title))
    elif line.startswith('+'):
        hunk_plus.append(line[1:])
    elif line.startswith(' '):
        mt = TITLE_RE.search(line)
        if mt: title = mt.group(1)
close_hunk()

for i in info:
    print('INFO', i)
for k, v in stats.items():
    print('INFO', k, v)
for f in flags:
    print('FLAG', f)
print(('PASS' if not flags else 'FAIL'), f'[AC-12③] 既有测试断言改动审阅：FLAG {len(flags)} 条')
sys.exit(1 if flags else 0)
