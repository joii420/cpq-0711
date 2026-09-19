#!/usr/bin/env python3
"""
repair-260918 · 测试片 S1（只读片）用例 T1.1 ~ T1.5、T1.7。T1.6 / T1.8 见同目录 s1_t16_t18.sh。

用例一律从 问题说明.md ⑥ 的 AC 原文派生（测试工程师未读实现代码）。

只读：只发 GET、试算 POST（/excel-view/dry-run、/dry-run-tab-formula、/components/{id}/dry-run）
与登录 POST；数据库只跑 SELECT。只对 固定单据清单-260918.tsv 的 31 张单下断言，格数只打印。

判定值口径 = 证据/脚本/excel_three_way.py（原样搬用，未改口径）：
  [页签(总计)]           → quote_card_values.tabs[componentId==tabKey].subtotal
  [页签.列] / [页签.列(小计)] → quote_card_values.tabs[componentId==tabKey].subtotalByColumn[列]
  判定值缺席（None）时按该脚本 dec() 口径视作 0，并单独计数打印（立项时 0864 的 8 格即此情形）。
  与原脚本唯一不同：**后端值为 null 一律判失败**（原脚本 dec() 会把 null 也当 0，那会让「一律返回空」的错误修法假绿，
  AC-2 反向断言 E-5 明确要求判定值为 0 的格返回 0 而不是 null）。

用法：
  python3 s1_cases.py --base http://localhost:8318 --base-b http://localhost:8081 --cases T1.1,T1.2,T1.3,T1.4
  python3 s1_cases.py --base http://localhost:8318 --cases T1.7 --md5-phase before
  python3 s1_cases.py --base http://localhost:8318 --cases T1.7 --md5-phase after --md5-before <上次目录>/T1.7-md5-before.tsv
  python3 s1_cases.py --base http://localhost:8318 --cases T1.5 --sqllog /path/to/backend-8318.log [--base-b ... --sqllog-b ...]
  自检（证明 T1.3 比较器判得出红）：追加 --perturb-b，会在内存里改掉 B 侧第一格，T1.3 必须 FAIL。
  证伪（证明 T1.2 反向断言接上了）：--simulate null → E-5 必须 FAIL；--simulate stored → E-6 必须 FAIL（只改内存）。

每次运行写入 证据/测试/S1/<yymmdd-HHMM>[-n]/ 新目录（已存在则追加 -2、-3…，绝不覆盖）。
退出码：0 = 所跑用例全 PASS；1 = 有用例 FAIL；2 = 脚本/环境错误（登录失败、连库身份不符等）。
"""
import argparse, datetime, json, os, re, subprocess, sys, time
from decimal import Decimal, InvalidOperation

HERE = os.path.dirname(os.path.abspath(__file__))
TASK = os.path.abspath(os.path.join(HERE, '..', '..', '..'))           # repair-260918 任务目录
FIXED_LIST = os.path.join(TASK, '证据', '输出', '固定单据清单-260918.tsv')
OUT_ROOT = os.path.join(TASK, '证据', '测试', 'S1')
PG = ['psql', '-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-X', '-A', '-t', '-F', '\t']
PGENV = {'PGPASSWORD': os.environ.get('PGPASSWORD', 'joii5231'), 'PATH': '/usr/bin:/bin'}

Q0881 = '40fe7ae7-c6e1-403a-aa30-17989ec758e9'
L0881 = '44954ae6-f5a1-4f0f-9847-d2e6ad23b800'
T0881 = '0ca31c74-4d5a-4b44-bd6f-7d4c5ddf1637'
C0011_ID = '3afead70-7f10-4586-8458-be02a3d1b263'
Q0607 = 'ae190116-aa69-44f4-a4a9-5fd1f3676756'
Q0564 = 'a99bfc8d-2bd1-432a-af67-436c7160a09c'
MASTER_0881_EXCELVIEW = ('0.003407173', '0', '39.547708538')    # 立项实测（AC-1 / AC-6 ① 的 master 值）
LIXIANG_0881_ORACLE = ('1.978941064', '0.317766357', '1.804589425')
DRYRUN_ERR = '样本卡片的卡片值不可用（尚未计算或计算失败），无法试算'


class EnvError(Exception):
    pass


# ---------------------------------------------------------------- infra
class Out:
    def __init__(self, d):
        self.d = d
        os.makedirs(os.path.join(d, 'raw'), exist_ok=False)
        self.fh = None

    def open_case(self, name):
        if self.fh:
            self.fh.close()
        self.fh = open(os.path.join(self.d, f'{name}.txt'), 'w', encoding='utf-8')

    def p(self, *a):
        s = ' '.join(str(x) for x in a)
        print(s, flush=True)
        if self.fh:
            self.fh.write(s + '\n'); self.fh.flush()

    def raw(self, name, text):
        with open(os.path.join(self.d, 'raw', name), 'w', encoding='utf-8') as f:
            f.write(text)


def make_outdir():
    stamp = datetime.datetime.now().strftime('%y%m%d-%H%M')
    d = os.path.join(OUT_ROOT, stamp); n = 2
    while os.path.exists(d):
        d = os.path.join(OUT_ROOT, f'{stamp}-{n}'); n += 1
    os.makedirs(os.path.dirname(d), exist_ok=True)
    return d


def psql(sql):
    r = subprocess.run(PG + ['-c', sql], capture_output=True, text=True, env=PGENV)
    if r.returncode != 0:
        raise EnvError('psql failed: ' + r.stderr)
    return r.stdout.rstrip('\n')


def now():
    return datetime.datetime.now().astimezone()


class Api:
    def __init__(self, base, jar):
        self.base, self.jar = base.rstrip('/'), jar

    def login(self):
        r = subprocess.run(['curl', '-s', '--noproxy', '*', '-c', self.jar, '-X', 'POST', f'{self.base}/api/cpq/auth/login',
                            '-H', 'Content-Type: application/json', '-d', '{"username":"admin","password":"Admin@2026"}'],
                           capture_output=True, text=True)
        try:
            ok = json.loads(r.stdout).get('code') == 200
        except Exception:
            ok = False
        if not ok:
            raise EnvError(f'登录失败 {self.base}: {r.stdout[:200]}')

    def _once(self, method, path, body):
        args = ['curl', '-s', '--noproxy', '*', '-b', self.jar, '-o', '-', '-w', '\n%{http_code}', '-X', method,
                f'{self.base}{path}']
        if body is not None:
            args += ['-H', 'Content-Type: application/json', '--data-binary', '@-']
        r = subprocess.run(args, input=json.dumps(body, ensure_ascii=False) if body is not None else None,
                           capture_output=True, text=True)
        text, _, code = r.stdout.rpartition('\n')
        return int(code or 0), text

    def call(self, method, path, body=None):
        """返回 (http_code, 原始正文, 解析后的 data 或 None, 发出时刻)。非预期响应先重登再重试一次。"""
        for attempt in (1, 2):
            t = now()
            code, text = self._once(method, path, body)
            data = None
            try:
                j = json.loads(text)
                data = j.get('data', j) if isinstance(j, dict) else j
            except Exception:
                pass
            if code == 200 and data is not None:
                return code, text, data, t
            if attempt == 1:
                self.login()
        return code, text, data, t


def identity(out, api, label):
    """探活验明正身：列表 totalElements 与开发库 quotation 行数一致 ⇒ 该后端连的是 cpq_db_0724。"""
    for _ in range(2):
        code, text, data, _t = api.call('GET', '/api/cpq/quotations?page=1&size=1')
        db = int(psql('select count(*) from quotation'))
        te = (data or {}).get('totalElements') if isinstance(data, dict) else None
        out.p(f'[身份] {label} {api.base}: HTTP {code} totalElements={te}  cpq_db_0724.quotation count={db}')
        if te == db:
            return
    raise EnvError(f'{label} {api.base} 连库身份与 cpq_db_0724 对不上（totalElements={te} vs {db}）')


def D(v):
    try:
        return Decimal(str(v))
    except (InvalidOperation, ValueError):
        return None


def load_fixed():
    rows = []
    with open(FIXED_LIST, encoding='utf-8') as f:
        hdr = f.readline().rstrip('\n').split('\t')
        for ln in f:
            ln = ln.rstrip('\n')
            if not ln or ln.startswith('('):
                continue
            rows.append(dict(zip(hdr, ln.split('\t'))))
    return rows


def oracle_of(col, card):
    """excel_three_way.py 同口径。返回 (判定值原值或 None, 说明)。表达式不是单 token 时返回 (None,'无法解析')。"""
    m = re.fullmatch(r'\[([^\]]+)\]', (col.get('expression') or '').strip())
    if not m:
        return None, '表达式无法按单 token 解析'
    tok = m.group(1)
    key_of = {t['alias']: t['tabKey'] for t in col.get('tabs') or []}
    tabs = {t.get('componentId'): t for t in (card or {}).get('tabs', [])}
    if tok.endswith('(总计)'):
        t = tabs.get(key_of.get(tok[:-4])); return (t.get('subtotal') if t else None), 'subtotal'
    a, c = tok.split('.', 1)
    c = c[:-4] if c.endswith('(小计)') else c
    t = tabs.get(key_of.get(a)); return ((t.get('subtotalByColumn') or {}).get(c) if t else None), f'subtotalByColumn[{c}]'


def line_rows(qid):
    """该单全部报价行：id, sort_order, template_id, quote_card_values, quote_excel_values（JSON 文本）。"""
    res = psql(f"select id, coalesce(sort_order,0), template_id, coalesce(quote_card_values::text,''), "
               f"coalesce(quote_excel_values::text,'') from quotation_line_item where quotation_id='{qid}' order by sort_order, id")
    out = []
    for ln in res.splitlines():
        lid, so, tid, cv, ev = ln.split('\t')
        out.append({'id': lid, 'sort': int(so), 'tid': tid, 'cv': cv, 'ev': ev})
    return out


def card_ok(cv):
    """AC-2 前置「quote_card_values 非空」：不为空、可解析、tabs 为非空数组、不含 __cardValueFailed:true。"""
    if not cv.strip():
        return False, '空'
    try:
        j = json.loads(cv)
    except Exception:
        return False, 'JSON 解析失败'
    if j.get('__cardValueFailed') is True:
        return False, '__cardValueFailed'
    if not isinstance(j.get('tabs'), list) or not j['tabs']:
        return False, 'tabs 缺失或为空'
    return True, 'ok'


class Case:
    def __init__(self, out, name):
        self.out, self.name, self.fails = out, name, []
        out.open_case(name)
        out.p(f'===== {name} 开始 {now().isoformat(timespec="seconds")} =====')

    def check(self, ok, msg):
        self.out.p(('  [PASS] ' if ok else '  [FAIL] ') + msg)
        if not ok:
            self.fails.append(msg)
        return ok

    def end(self):
        verdict = 'PASS' if not self.fails else f'FAIL（{len(self.fails)} 项）'
        self.out.p(f'===== {self.name} 结论：{verdict} =====')
        return not self.fails


# ---------------------------------------------------------------- T1.1 · AC-1
def t11(out, A, B, args):
    c = Case(out, 'T1.1')
    lr = [r for r in line_rows(Q0881) if r['id'] == L0881]
    ok, why = card_ok(lr[0]['cv']) if lr else (False, '行不存在')
    if not c.check(ok, f'前置：0881 行 44954ae6 quote_card_values 可用（{why}，长度 {len(lr[0]["cv"]) if lr else 0}）'):
        return c.end()
    ca, ta, da, tA = A.call('GET', f'/api/cpq/quotations/{Q0881}/excel-view')
    cb, tb, db_, tB = B.call('GET', f'/api/cpq/quotations/{Q0881}/excel-view')
    out.raw('T1.1-A-excel-view.json', ta); out.raw('T1.1-B-excel-view.json', tb)
    card = json.loads(line_rows(Q0881)[0]['cv'])     # 请求后立即取判定值
    out.p(f'  A={A.base} HTTP {ca} @ {tA.isoformat(timespec="seconds")}；B={B.base} HTTP {cb} @ {tB.isoformat(timespec="seconds")}')
    c.check(abs((tA - tB).total_seconds()) < 60, f'A/B 同一分钟内发出（相差 {abs((tA - tB).total_seconds()):.1f}s）')
    if not c.check(ca == 200 and isinstance(da, dict) and da.get('rows'), 'A 响应 200 且 rows 非空'):
        out.p('  A 原文：', ta[:500]); return c.end()
    if not c.check(cb == 200 and isinstance(db_, dict) and db_.get('rows'), 'B 响应 200 且 rows 非空'):
        return c.end()
    colsA, colsB = da['columns'], db_['columns']
    c.check(len(colsA) == 3, f'A columns 为 3 列（实际 {len(colsA)}）')
    ka = [(x.get('col_key'), x.get('title')) for x in colsA]; kb = [(x.get('col_key'), x.get('title')) for x in colsB]
    out.p(f'  A columns (col_key,title) = {ka}'); out.p(f'  B columns (col_key,title) = {kb}')
    c.check(ka == kb, 'A 与 B 的 col_key/title 一致')
    c.check([t for _, t in ka] == ['材料成本', '回收价格', '产品单价'], 'title = 材料成本 / 回收价格 / 产品单价')
    rA = da['rows'][0]; rB = db_['rows'][0]
    c.check(rA.get('_lineItemId') == L0881, f'A rows[0]._lineItemId = {rA.get("_lineItemId")}')
    valsA, valsB = [], []
    for i, col in enumerate(colsA):
        k = col['col_key']
        o, how = oracle_of(col, card)
        a, b = rA.get(k), rB.get(k)
        valsA.append(a); valsB.append(b)
        out.p(f'  {k} {col.get("expression")}: A(本分支)={a!r}  B(master)={b!r}  判定值({how})={o!r}  立项判定={LIXIANG_0881_ORACLE[i] if i < 3 else "-"}')
        if not c.check(o is not None, f'{k} 判定值非空'):
            continue
        c.check(a is not None and D(a) is not None and D(a) == D(o),
                f'{k} A 逐位等于判定值（判据：Decimal 差为 0；附：两者字符串{"相同" if str(a) == str(o) else "不同"}）')
    c.check(tuple(map(str, valsA)) != tuple(map(str, valsB)),
            f'master 返回值与本分支不同：A={valsA} B={valsB}（证明变化来自本次改动）')
    return c.end()


# ---------------------------------------------------------------- T1.2 · AC-2
E6 = [  # (单号, 行前缀或 None, col_key, 立项判定值)
    ('QT-20260908-0607', None, 'col_2', '10'), ('QT-20260908-0609', None, 'col_2', '10'),
    ('QT-20260908-0610', None, 'col_2', '10'), ('QT-20260908-0611', None, 'col_2', '10'),
    ('QT-20260914-0865', '60d086ed', 'col_1', '18271.631411'), ('QT-20260914-0865', '6c02a8fb', 'col_1', '19557.9316532'),
    ('QT-20260914-0865', '4b296616', 'col_1', '45916.28354'), ('QT-20260914-0865', '7a4c1eab', 'col_1', '21110.526275'),
]


def t12(out, A, B, args):
    c = Case(out, 'T1.2')
    fixed = load_fixed()
    c.check(len(fixed) == 31, f'固定单据清单读出 {len(fixed)} 张（应 31）')
    table, stats, cells = [], {}, {}
    per_comp = {}
    zero_cells = none_oracle = nrow_ok = 0
    for q in fixed:
        comp, qno, qid = q['excel_comp'], q['quotation_number'], q['quotation_id']
        code, text, data, t = A.call('GET', f'/api/cpq/quotations/{qid}/excel-view')
        out.raw(f'T1.2-{qno}-excel-view.json', text)
        if not c.check(code == 200 and isinstance(data, dict) and 'rows' in data, f'{qno} excel-view HTTP {code} 结构正常'):
            continue
        lines = {r['id']: r for r in line_rows(qid)}
        got = {r.get('_lineItemId'): r for r in data['rows']}
        if set(got) != set(lines):
            c.check(False, f'{qno} 响应行 {len(got)} 与库中报价行 {len(lines)} 不一一对应（缺 {sorted(set(lines) - set(got))}）')
        tj = [x for x in data['columns'] if x.get('source_type') == 'TAB_JOIN_FORMULA']
        for lid, brow in got.items():
            li = lines.get(lid)
            if li is None:
                c.check(False, f'{qno} 响应行 {lid} 不在库中该单的报价行里'); continue
            ok, why = card_ok(li['cv'])
            if not ok:
                c.check(False, f'{qno} 行 {lid[:8]} 前置 quote_card_values 可用（{why}）'); continue
            nrow_ok += 1
            card = json.loads(li['cv'])
            stored = (json.loads(li['ev']).get('rows') or [{}])[0] if li['ev'] else {}
            for col in tj:
                k = col['col_key']
                o, how = oracle_of(col, card)
                oracle_eff = o if o is not None else '0'        # excel_three_way.py dec() 口径
                if o is None:
                    none_oracle += 1
                b, s = brow.get(k), stored.get(k)
                if args.simulate == 'null':      # 证伪：模拟「一律返回空」的错误修法（只改内存，不改任何数据）
                    b = None
                elif args.simulate == 'stored':  # 证伪：模拟「照抄页面存值」的错误修法
                    b = s
                # 按正式账试算交叉核对（与原脚本同：传 cardValuesJson）
                dcode, dtext, ddata, _ = A.call('POST', f'/api/cpq/templates/{li["tid"]}/excel-view-config/dry-run-tab-formula',
                                                {'lineItemId': lid, 'column': col, 'cardValuesJson': li['cv']})
                dr = ddata.get('value') if isinstance(ddata, dict) else None
                be_ok = b is not None and D(b) is not None and D(b) == D(oracle_eff)
                st_ok = s is not None and D(s) is not None and abs(D(s) - D(oracle_eff)) <= Decimal('1e-8')
                dr_ok = dr is not None and D(dr) is not None and D(dr) == D(oracle_eff)
                kk = ('后端=判定' if be_ok else ('后端=null' if b is None else '后端≠判定'),
                      '存值=判定' if st_ok else '存值≠判定', '判定=试算' if dr_ok else '判定≠试算')
                stats[kk] = stats.get(kk, 0) + 1
                per_comp[comp] = per_comp.get(comp, 0) + 1
                cells[(qno, lid[:8], k)] = (b, s, o)
                if D(oracle_eff) == 0:
                    zero_cells += 1
                table.append(f'{comp}\t{qno}\t{lid[:8]}\t{k}\t{col["expression"]}\t后端={b}\t存值={s}\t判定={o}\t试算={dr}\t{"|".join(kk)}')
                if not be_ok:
                    c.fails.append(f'{qno} {lid[:8]} {k}: 后端={b!r} 判定={o!r}')
    out.p('excel组件\t单号\t行\t列\t表达式\t后端\t存值\t判定(正式账)\t试算(按正式账)\t结论')
    for ln in table:
        out.p(ln)
    out.p('\n== 汇总（后端逐位相等且非 null；存值容差 1e-8 仅作信息）==')
    for k, v in sorted(stats.items()):
        out.p(' / '.join(k), v)
    out.p(f'格数合计 {sum(stats.values())}（立项时 135；只打印不断言）；判定值为 0 的格 {zero_cells}；判定值缺席(None→按 0) {none_oracle}')
    out.p(f'各组件格数 {per_comp}')
    # 断言 1：格数 ≥1 且三组件各 ≥1
    c.check(sum(stats.values()) >= 1, '格数 ≥ 1')
    for comp in ('COMP-0011', 'COMP-2269', 'COMP-2509'):
        c.check(per_comp.get(comp, 0) >= 1, f'覆盖 {comp} 至少 1 格（实际 {per_comp.get(comp, 0)}）')
    # 断言 1：每一格后端 == 判定
    bad = [ln for ln in table if not ln.rsplit('\t', 1)[1].startswith('后端=判定|')]
    out.p(f'逐行前置 quote_card_values 可用的行数 {nrow_ok}（立项时 49；只打印）')
    c.check(not bad, f'每一格后端值逐位等于判定值（不等 {len(bad)} 格，明细见上表「后端≠判定」「后端=null」行）')
    # 反向 E-5
    b, s, o = cells.get(('QT-20260916-0872', '626ab535', 'col_2'), (None, None, None))
    out.p(f'  E-5 指名格 QT-20260916-0872 626ab535 col_2：后端={b!r} 判定={o!r}')
    c.check(o is not None and D(o) == 0, 'E-5 前置：该格判定值仍为 0')
    c.check(b is not None and D(b) == 0, 'E-5：判定值为 0 的指名格返回 0，不是 null')
    zero_null = [ln for ln in table if ('\t判定=0\t' in ln or '\t判定=None\t' in ln) and '\t后端=None\t' in ln]
    c.check(not zero_null, f'E-5 推广：所有判定值为 0 的格均无 null（null 格 {len(zero_null)}）')
    # 反向 E-6
    for qno, pre, k, lix in E6:
        hits = [(key, v) for key, v in cells.items() if key[0] == qno and key[2] == k and (pre is None or key[1] == pre)]
        if not c.check(len(hits) == 1, f'E-6 {qno} {pre or "(唯一行)"} {k} 定位到 1 格（实际 {len(hits)}）'):
            continue
        (_, lid8, _), (b, s, o) = hits[0]
        out.p(f'  E-6 {qno} {lid8} {k}：后端={b!r} 存值={s!r} 判定={o!r} 立项判定={lix}')
        c.check(o is not None and D(o) == D(lix), f'E-6 前置：判定值仍为立项值 {lix}')
        c.check(s is not None and D(s) is not None and D(s) != D(o), f'E-6 前置：存值({s}) ≠ 判定（该格仍有鉴别力）')
        c.check(b is not None and D(b) == D(o) and (D(s) is None or D(b) != D(s)), 'E-6：后端 = 判定值，不等于页面存值')
    return c.end()


# ---------------------------------------------------------------- T1.3 · AC-3
def t13(out, A, B, args):
    c = Case(out, 'T1.3')
    qs = [q for q in load_fixed() if q['excel_comp'] in ('COMP-2269', 'COMP-2509')]
    c.check(len(qs) == 11, f'COMP-2269/COMP-2509 单 {len(qs)} 张（应 11）')
    ncell = 0
    out.p('单号\t行\t列\tA(本分支)\tB(master)\t相同')
    for q in qs:
        qno, qid = q['quotation_number'], q['quotation_id']
        ca, ta, da, tA = A.call('GET', f'/api/cpq/quotations/{qid}/excel-view')
        cb, tb, db_, tB = B.call('GET', f'/api/cpq/quotations/{qid}/excel-view')
        out.raw(f'T1.3-{qno}-A.json', ta); out.raw(f'T1.3-{qno}-B.json', tb)
        if not c.check(ca == 200 and cb == 200 and isinstance(da, dict) and isinstance(db_, dict),
                       f'{qno} A/B 均 HTTP 200（{ca}/{cb}）'):
            continue
        if abs((tA - tB).total_seconds()) >= 60:
            c.check(False, f'{qno} A/B 相差 {abs((tA - tB).total_seconds()):.1f}s 超过一分钟')
        if args.perturb_b and db_.get('rows'):
            r0 = db_['rows'][0]; kk = next(k for k in r0 if k != '_lineItemId')
            out.p(f'  !! --perturb-b 自检：把 B {qno} rows[0].{kk} 由 {r0[kk]!r} 改为 "PERTURBED"'); r0[kk] = 'PERTURBED'
            args.perturb_b = False
        ja = json.dumps(da.get('columns'), ensure_ascii=False); jb = json.dumps(db_.get('columns'), ensure_ascii=False)
        if ja != jb:
            c.check(False, f'{qno} columns 数组逐字不同'); out.p('   A:', ja); out.p('   B:', jb)
        ra = {r.get('_lineItemId'): r for r in da.get('rows', [])}; rb = {r.get('_lineItemId'): r for r in db_.get('rows', [])}
        if set(ra) != set(rb):
            c.check(False, f'{qno} A/B 行集合不同 A={sorted(ra)} B={sorted(rb)}')
        for lid in sorted(set(ra) & set(rb)):
            keys = sorted((set(ra[lid]) | set(rb[lid])) - {'_lineItemId'})
            for k in keys:
                a, b = ra[lid].get(k, '<缺>'), rb[lid].get(k, '<缺>')
                same = (a == b) and type(a) is type(b)
                ncell += 1
                out.p(f'{qno}\t{lid[:8]}\t{k}\t{a!r}\t{b!r}\t{"相同" if same else "不同"}')
                if not same:
                    c.fails.append(f'{qno} {lid[:8]} {k}: A={a!r} B={b!r}')
    out.p(f'逐格比较 {ncell} 格（立项时 75；只打印不断言具体数）')
    c.check(ncell >= 1, '比较格数 ≥ 1（非空）')
    c.check(not [f for f in c.fails if ': A=' in f], 'A/B 每一格逐位相同（值与 JSON 类型都相同）')
    return c.end()


# ---------------------------------------------------------------- T1.4 · AC-6
def t14(out, A, B, args):
    c = Case(out, 'T1.4')
    lr = [r for r in line_rows(Q0881) if r['id'] == L0881]
    ok, why = card_ok(lr[0]['cv']) if lr else (False, '行不存在')
    if not c.check(ok, f'前置：0881 行正式账可用（{why}）'):
        return c.end()
    cols = json.loads(psql("select excel_columns::text from component where id='%s'" % C0011_ID))
    c.check(len(cols) == 3, f'COMP-0011 excel_columns 取自库（{len(cols)} 列：{[x["col_key"] for x in cols]}）')
    card = json.loads(lr[0]['cv'])
    oracle = [oracle_of(col, card)[0] for col in cols]
    out.p(f'  判定值（请求前取）= {oracle}')
    # ① /excel-view/dry-run
    body = {'templateId': T0881, 'columns': cols}
    ca, ta, da, tA = A.call('POST', f'/api/cpq/quotations/{Q0881}/excel-view/dry-run', body)
    cb, tb, db_, tB = B.call('POST', f'/api/cpq/quotations/{Q0881}/excel-view/dry-run', body)
    out.raw('T1.4-1-A-excel-view-dry-run.json', ta); out.raw('T1.4-1-B-excel-view-dry-run.json', tb)
    out.p(f'  ① A HTTP {ca} @ {tA.isoformat(timespec="seconds")} / B HTTP {cb} @ {tB.isoformat(timespec="seconds")}')
    c.check(abs((tA - tB).total_seconds()) < 60, '① A/B 同一分钟内')
    rowA = next((r for r in (da or {}).get('rows', []) if r.get('_lineItemId') == L0881), None) if isinstance(da, dict) else None
    rowB = next((r for r in (db_ or {}).get('rows', []) if r.get('_lineItemId') == L0881), None) if isinstance(db_, dict) else None
    if c.check(rowA is not None, '① A 响应含 0881 行'):
        for col, o in zip(cols, oracle):
            k = col['col_key']; a = rowA.get(k)
            out.p(f'    ① {k}: A={a!r} 判定={o!r} B={rowB.get(k) if rowB else None!r}')
            c.check(a is not None and o is not None and D(a) == D(o), f'① {k} A 逐位等于判定值')
        errs = da.get('errors', '<无此字段>')
        out.p(f'    ① A errors 字段 = {errs!r}')
        c.check(errs in ([], '<无此字段>'), '① A errors 为空数组或无该字段（见回报：AC 原文对 ① 的 errors 口径待主线澄清）')
    if c.check(rowB is not None, '① B(master) 响应含 0881 行'):
        got = tuple(str(rowB.get(col['col_key'])) for col in cols)
        c.check(all(D(x) == D(y) for x, y in zip(got, MASTER_0881_EXCELVIEW)), f'① master 对照 = 立项实测 {MASTER_0881_EXCELVIEW}（实际 {got}）')
    # ② 模板级 / ③ 组件级 dry-run，不带 cardValuesJson
    for tag, path in (('②', f'/api/cpq/templates/{T0881}/excel-view-config/dry-run-tab-formula'),
                      ('③', f'/api/cpq/components/{C0011_ID}/dry-run')):
        for i, (col, o) in enumerate(zip(cols, oracle)):
            b = {'lineItemId': L0881, 'column': col}
            ca, ta, da, tA = A.call('POST', path, b)
            cb, tb, db_, tB = B.call('POST', path, b)
            out.raw(f'T1.4-{"2" if tag == "②" else "3"}-col{i}-A.json', ta); out.raw(f'T1.4-{"2" if tag == "②" else "3"}-col{i}-B.json', tb)
            va = da.get('value') if isinstance(da, dict) else None; ea = da.get('errors') if isinstance(da, dict) else None
            vb = db_.get('value') if isinstance(db_, dict) else None; eb = db_.get('errors') if isinstance(db_, dict) else None
            out.p(f'    {tag} {col["col_key"]}: A HTTP {ca} value={va!r} errors={ea!r} | B HTTP {cb} value={vb!r} errors={eb!r} | 判定={o!r}'
                  f' | 相差 {abs((tA - tB).total_seconds()):.1f}s')
            c.check(va is not None and o is not None and D(va) == D(o), f'{tag} {col["col_key"]} A value 逐位等于判定值')
            c.check(ea == [], f'{tag} {col["col_key"]} A errors 为空数组')
            if tag == '②':
                c.check(vb is not None and D(vb) == 0, f'② {col["col_key"]} master 对照 = 立项实测 0（实际 {vb!r}）')
    return c.end()


# ---------------------------------------------------------------- T1.5 · AC-7
# 实测 8318（prod + log.sql=true）格式：每条语句以一行带 ANSI 色码的 "[Hibernate]" 开头，无线程名
SQL_RE = re.compile(r'\[Hibernate\]|\[org\.hib(ernate)?\.SQL\]|org\.hibernate\.SQL|^Hibernate:')


def count_sql(logpath, api, qid, out, tag):
    size0 = os.path.getsize(logpath)
    code, text, data, t = api.call('GET', f'/api/cpq/quotations/{qid}/excel-view')
    time.sleep(2.0)
    with open(logpath, encoding='utf-8', errors='replace') as f:
        f.seek(size0); seg = f.read()
    hits = [ln for ln in seg.splitlines() if SQL_RE.search(ln)]
    threads = {}
    for ln in hits:
        m = re.search(r'\(([^)]+)\)', ln); threads[m.group(1) if m else '?'] = threads.get(m.group(1) if m else '?', 0) + 1
    out.raw(f'T1.5-{tag}.log', seg)
    return code, len(hits), threads, hits[:3]


def t15(out, A, B, args):
    c = Case(out, 'T1.5')
    if not args.sqllog:
        c.check(False, '缺 --sqllog（本分支临时后端须以 -Dquarkus.hibernate-orm.log.sql=true 启动并把输出重定向到该文件）')
        return c.end()
    lines = {qid: len(line_rows(qid)) for qid in (Q0607, Q0564)}
    out.p(f'  行数：0607={lines[Q0607]}（应 1）0564={lines[Q0564]}（应 4）')
    c.check(lines[Q0607] == 1 and lines[Q0564] == 4, '前置：两单行数 1 / 4')
    for label, api, log in (('本分支', A, args.sqllog), ('master', B, args.sqllog_b)):
        if not log:
            out.p(f'  {label}：未提供 SQL 日志（--sqllog-b），master 条数 未验证'); continue
        for qid in (Q0607, Q0564):                 # 预热
            api.call('GET', f'/api/cpq/quotations/{qid}/excel-view')
        counts = {Q0607: [], Q0564: []}
        for rep in (1, 2, 3):
            for qid, nm in ((Q0607, '0607'), (Q0564, '0564')):
                code, n, th, sample = count_sql(log, api, qid, out, f'{label}-{nm}-rep{rep}')
                counts[qid].append(n)
                out.p(f'  {label} {nm} 第{rep}次: HTTP {code} SQL 条数={n} 线程分布={th} 样例={sample}')
        out.p(f'  {label} 计数表：0607={counts[Q0607]}  0564={counts[Q0564]}')
        if label == '本分支':
            c.check(all(n >= 1 for n in counts[Q0607] + counts[Q0564]), '判据非空：每次截取到 ≥1 条 SQL（否则日志未捕获，判据作废）')
            c.check(counts[Q0607] == counts[Q0564], '本分支：1 行单与 4 行单每次 SQL 条数相同')
    return c.end()


# ---------------------------------------------------------------- T1.7 · AC-11
def t17(out, A, B, args):
    c = Case(out, 'T1.7')
    ids = ','.join(f"'{q['quotation_id']}'" for q in load_fixed())
    whole = psql("select md5(string_agg(id||coalesce(quote_excel_values::text,'')||coalesce(quote_card_values::text,''), '|' ORDER BY id)), count(*) "
                 f"from quotation_line_item where quotation_id in ({ids})")
    per = psql("select li.id, q.quotation_number, md5(coalesce(li.quote_excel_values::text,'')||'#'||coalesce(li.quote_card_values::text,'')), "
               f"coalesce(li.quote_values_at::text,'') from quotation_line_item li join quotation q on q.id=li.quotation_id "
               f"where li.quotation_id in ({ids}) order by li.id")
    ts = now().isoformat(timespec='seconds')
    out.p(f'  [{args.md5_phase}] {ts} 整体 md5 / 行数 = {whole}')
    fn = os.path.join(out.d, f'T1.7-md5-{args.md5_phase}.tsv')
    with open(fn, 'w', encoding='utf-8') as f:
        f.write(f'#WHOLE\t{whole}\t{ts}\n{per}\n')
    c.check(bool(whole.split('\t')[0]) and int(whole.split('\t')[1]) >= 1, '非空：md5 已算出且行数 ≥ 1')
    if args.md5_phase == 'after':
        with open(args.md5_before, encoding='utf-8') as f:
            first = f.readline().rstrip('\n').split('\t'); prev = {l.split('\t')[0]: l.rstrip('\n') for l in f if l.strip()}
        out.p(f'  before（{first[3] if len(first) > 3 else "?"}）= {first[1]}  after = {whole.split(chr(9))[0]}')
        same = first[1] == whole.split('\t')[0]
        if not same:
            cur = {l.split('\t')[0]: l for l in per.splitlines()}
            for lid in sorted(set(prev) | set(cur)):
                if prev.get(lid) != cur.get(lid):
                    out.p(f'  变化行：before={prev.get(lid)}  after={cur.get(lid)}')
        c.check(same, '两次 md5 相同（不同则上面逐行列出变化行，须另行证明与本片请求无关）')
    return c.end()


CASES = {'T1.1': t11, 'T1.2': t12, 'T1.3': t13, 'T1.4': t14, 'T1.5': t15, 'T1.7': t17}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True, help='被测后端（本分支临时后端，如 http://localhost:8318）')
    ap.add_argument('--base-b', default='http://localhost:8081', help='A/B 对照后端（master 共享 8081，只读）')
    ap.add_argument('--cases', required=True)
    ap.add_argument('--sqllog'); ap.add_argument('--sqllog-b')
    ap.add_argument('--md5-phase', choices=['before', 'after'], default='before'); ap.add_argument('--md5-before')
    ap.add_argument('--perturb-b', action='store_true')
    ap.add_argument('--simulate', choices=['null', 'stored'], help='T1.2 证伪：内存中把后端值替换为 null / 页面存值，E-5 / E-6 必须变红')
    ap.add_argument('--note', default='')
    args = ap.parse_args()
    out = Out(make_outdir())
    out.open_case('00-run')
    out.p(f'运行目录 {out.d}')
    out.p(f'时刻 {now().isoformat(timespec="seconds")}  BASE(A)={args.base}  BASE_B={args.base_b}  cases={args.cases}  note={args.note}')
    out.p('命令行：' + ' '.join(sys.argv))
    try:
        A = Api(args.base, os.path.join(out.d, 'cj-A.txt')); A.login()
        B = Api(args.base_b, os.path.join(out.d, 'cj-B.txt')); B.login()
        identity(out, A, 'A'); identity(out, B, 'B')
        res = {}
        for name in args.cases.split(','):
            res[name] = CASES[name](out, A, B, args)
    except EnvError as e:
        out.open_case('00-run-error'); out.p('环境/脚本错误：', e); sys.exit(2)
    out.open_case('99-summary')
    for k, v in res.items():
        out.p(f'{k}\t{"PASS" if v else "FAIL"}')
    for fn in ('cj-A.txt', 'cj-B.txt'):
        try:
            os.remove(os.path.join(out.d, fn))
        except OSError:
            pass
    sys.exit(0 if all(res.values()) else 1)


if __name__ == '__main__':
    main()
