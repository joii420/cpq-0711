#!/usr/bin/env python3
"""
repair-260918 · 测试片 S2（私有写片）· 用例 T2.1~T2.4（AC-4 / AC-5 / AC-8 / AC-12）

用例只从 问题说明.md ⑥ 的 AC 原文派生；判定值口径逐字沿用 证据/脚本/excel_three_way.py：
  [页签(总计)]            -> tabs[componentId==tabKey].subtotal
  [页签.列] / [页签.列(小计)] -> tabs[componentId==tabKey].subtotalByColumn[列]
差异：本脚本**不把 null 当 0**（AC-4 要区分 null 与数字），判定值与后端值都按 Decimal 字符串逐位比较。

数据隔离（派工 d 段，写死）：
  - 只写本脚本本次 POST /copy 出来的单；创建后立即把 id 追加进回报文件台账，并改名 RP0918-S2-<原名>
  - 每次 UPDATE 都带 WHERE id='<本片复制单的行 id>'，执行前 SELECT count(*) 必须恰好 1 行，
    且该行所属单必须在本次运行创建清单内、单名以 RP0918-S2- 开头
  - finally 里 DELETE /api/cpq/quotations/{id} 并 SELECT count(*) 确认 0；删不掉的写进台账「待清理」

用法：
  BASE=http://localhost:8328 MASTER=http://localhost:8081 python3 run_s2.py T2.1,T2.2,T2.3,T2.4
  可选环境变量：OUT_DIR（证据目录；缺省 = 证据/测试/S2/<yymmdd-HHMM>，已存在则拒跑，不覆盖）
               REPORT（台账写入的回报文件；缺省 = 证据/回报-S2-260918.md）
退出码：0 = 全部断言通过；1 = 有断言失败；2 = 脚本/环境错误
"""
import datetime, http.cookiejar, io, json, os, re, subprocess, sys, time, traceback, urllib.error, urllib.request
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
EVID = os.path.abspath(os.path.join(HERE, '..', '..'))          # .../证据
BASE = os.environ.get('BASE', '').rstrip('/')
MASTER = os.environ.get('MASTER', 'http://localhost:8081').rstrip('/')
DB = os.environ.get('DB', 'cpq_db_0724')
REPORT = os.environ.get('REPORT', os.path.join(EVID, '回报-S2-260918.md'))
PGENV = {'PGPASSWORD': os.environ.get('PGPASSWORD', 'joii5231'), 'PATH': '/usr/bin:/bin'}

SRC_0881 = '40fe7ae7-c6e1-403a-aa30-17989ec758e9'
SRC_0564 = 'a99bfc8d-2bd1-432a-af67-436c7160a09c'
ASM_FEE_CID = 'd2272d54-0805-45b1-9315-3ad66caa3729'   # 组装加工费 页签 componentId（AC-8 原文）
DRYRUN_ERR = '样本卡片的卡片值不可用（尚未计算或计算失败），无法试算'  # api.md，逐字
PREFIX = 'RP0918-S2-'

if not BASE:
    sys.exit('BASE 未设置')

RUN_TAG = datetime.datetime.now().strftime('%y%m%d-%H%M')
OUT = os.environ.get('OUT_DIR') or os.path.join(EVID, '测试', 'S2', RUN_TAG)
if os.path.exists(OUT):
    print(f'证据目录已存在，拒绝覆盖：{OUT}'); sys.exit(2)
os.makedirs(OUT)

CREATED = []          # 本次运行复制出的单 id（唯一可写对象）
RESULTS = []          # (case, step, ac, verdict, detail)


# ---------------------------------------------------------------- 输出
def now():
    return datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')


def log(case, text):
    with open(os.path.join(OUT, f'{case}.log'), 'a', encoding='utf-8') as f:
        f.write(text.rstrip('\n') + '\n')


def check(case, step, ac, ok, detail):
    v = 'PASS' if ok else 'FAIL'
    RESULTS.append((case, step, ac, v, detail))
    log(case, f'  [{v}] {step} :: {detail}')
    print(f'[{v}] {case} {step} :: {detail}')
    return ok


def ledger(label, src, qid, state):
    line = f'| {now()} | `{os.path.relpath(OUT, EVID)}` | {label} | `{src}` | `{qid}` | {state} |\n'
    with open(REPORT, 'a', encoding='utf-8') as f:
        f.write(line)
    with open(os.path.join(OUT, 'ledger.tsv'), 'a', encoding='utf-8') as f:
        f.write('\t'.join([now(), label, src, qid, state]) + '\n')


# ---------------------------------------------------------------- DB
def psql(sql):
    r = subprocess.run(['psql', '-h', '10.177.152.12', '-U', 'postgres', '-d', DB, '-X', '-A', '-t', '-F', '\t', '-c', sql],
                       capture_output=True, text=True, env=PGENV)
    if r.returncode != 0:
        raise RuntimeError('psql failed: ' + r.stderr.strip() + ' :: ' + sql)
    return r.stdout.strip()


def lines_of(qid):
    """[(line_id, sort_order, template_id)] 按 sort_order"""
    out = psql(f"select id, sort_order, template_id from quotation_line_item where quotation_id='{qid}' order by sort_order, id")
    return [tuple(l.split('\t')) for l in out.splitlines() if l]


def card_text(line_id):
    return psql(f"select coalesce(quote_card_values::text,'<NULL>') from quotation_line_item where id='{line_id}'")


def excel_text(line_id):
    return psql(f"select coalesce(quote_excel_values::text,'<NULL>') from quotation_line_item where id='{line_id}'")


def guarded_update(case, line_id, set_sql):
    """对本片复制单的一行做 UPDATE；三道守卫全过才执行，UPDATE 必须恰好 1 行"""
    assert re.fullmatch(r'[0-9a-f-]{36}', line_id), line_id
    owned = "','".join(CREATED) or '00000000-0000-0000-0000-000000000000'
    n_own = psql(f"select count(*) from quotation_line_item li join quotation q on q.id=li.quotation_id "
                 f"where li.id='{line_id}' and q.id in ('{owned}') and q.name like '{PREFIX}%'")
    n_row = psql(f"select count(*) from quotation_line_item where id='{line_id}'")
    log(case, f'  [guard] line={line_id} 属本片单且已标记={n_own} 行数={n_row}')
    if n_own != '1' or n_row != '1':
        raise RuntimeError(f'守卫拒绝 UPDATE：line={line_id} own={n_own} rows={n_row}')
    sql = f"update quotation_line_item set {set_sql} where id='{line_id}'"
    r = subprocess.run(['psql', '-h', '10.177.152.12', '-U', 'postgres', '-d', DB, '-X', '-c', sql],
                       capture_output=True, text=True, env=PGENV)
    log(case, f'  [SQL] {sql}\n  [SQL-out] {r.stdout.strip()} {r.stderr.strip()}')
    if r.returncode != 0 or r.stdout.strip() != 'UPDATE 1':
        raise RuntimeError(f'UPDATE 非 1 行：{r.stdout} {r.stderr}')


# ---------------------------------------------------------------- HTTP
class Api:
    def __init__(self, base, name):
        self.base, self.name = base, name
        self.cj = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                              urllib.request.HTTPCookieProcessor(self.cj))
        self.login()

    def login(self):
        st, body = self._raw('POST', '/api/cpq/auth/login', {'username': 'admin', 'password': 'Admin@2026'})
        if st != 200:
            raise RuntimeError(f'{self.name} 登录失败 {st} {body[:200]!r}')

    def _raw(self, method, path, payload=None):
        data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with self.op.open(req, timeout=300) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def call(self, method, path, payload=None, binary=False):
        """返回 (http_status, 解析后的 JSON 或 bytes)。401 / 非 JSON 时重登后重试一次"""
        for attempt in (1, 2):
            st, body = self._raw(method, path, payload)
            if binary:
                if st == 401 and attempt == 1:
                    self.login(); continue
                return st, body
            try:
                j = json.loads(body.decode('utf-8'))
            except Exception:
                j = None
            if (st == 401 or j is None) and attempt == 1:
                self.login(); continue
            return st, (j if j is not None else body.decode('utf-8', 'replace'))
        return st, j


def data_of(j):
    return j.get('data', j) if isinstance(j, dict) else j


# ---------------------------------------------------------------- 判定
def card_usable(txt):
    """api.md「正式账不可用」口径的反面（仅用于前置断言与恢复判断，不作被测判据）"""
    if txt in (None, '', '<NULL>'):
        return False
    try:
        j = json.loads(txt)
    except Exception:
        return False
    if not isinstance(j, dict) or j.get('__cardValueFailed') is True:
        return False
    tabs = j.get('tabs')
    return isinstance(tabs, list) and len(tabs) > 0


def oracle(card_txt, col):
    """与 excel_three_way.py 同口径；取不到返回 None"""
    card = json.loads(card_txt)
    tabs = {t.get('componentId'): t for t in card.get('tabs', [])}
    tok = re.fullmatch(r'\[([^\]]+)\]', col['expression'].strip()).group(1)
    key_of = {t['alias']: t['tabKey'] for t in col['tabs']}
    if tok.endswith('(总计)'):
        t = tabs.get(key_of[tok[:-4]]); return t.get('subtotal') if t else None
    a, c = tok.split('.', 1)
    c = c[:-4] if c.endswith('(小计)') else c
    t = tabs.get(key_of[a]); return (t.get('subtotalByColumn') or {}).get(c) if t else None


def eq_exact(a, b):
    """逐位相等：两边都非 null 且 Decimal 差为 0"""
    if a is None or b is None:
        return False
    try:
        return Decimal(str(a)) - Decimal(str(b)) == 0
    except Exception:
        return False


def is_number(v):
    if v is None or v == '':
        return False
    try:
        Decimal(str(v)); return True
    except Exception:
        return False


def tj_cols(cols):
    return [c for c in cols if c.get('source_type') == 'TAB_JOIN_FORMULA']


# ---------------------------------------------------------------- 业务动作
def copy_quote(api, case, src, label):
    st, j = api.call('POST', f'/api/cpq/quotations/{src}/copy', {})
    log(case, f'  [copy {label}] {api.name} POST /quotations/{src}/copy -> {st} {json.dumps(j, ensure_ascii=False)[:400]}')
    d = data_of(j)
    qid = d.get('id') if isinstance(d, dict) else None
    if st != 200 or not qid:
        raise RuntimeError(f'复制失败 {label}: {st}')
    CREATED.append(qid)
    ledger(label, src, qid, '已创建')                    # 先登记，再做任何写
    n = psql(f"select count(*) from quotation where id='{qid}'")
    if n != '1':
        raise RuntimeError(f'改名前计数≠1: {n}')
    out = subprocess.run(['psql', '-h', '10.177.152.12', '-U', 'postgres', '-d', DB, '-X', '-c',
                          f"update quotation set name='{PREFIX}'||name where id='{qid}'"],
                         capture_output=True, text=True, env=PGENV).stdout.strip()
    nm = psql(f"select name from quotation where id='{qid}'")
    log(case, f'  [rename {label}] {out} -> name={nm}')
    if out != 'UPDATE 1' or not nm.startswith(PREFIX):
        raise RuntimeError(f'改名失败 {label}: {out} {nm}')
    return qid


def ensure_card(api, case, qid, want_usable=True, timeout=120):
    """POST ensure-card-values；warming 时轮询，直到本单所有行卡片值可用或超时"""
    t0 = time.time()
    while True:
        st, j = api.call('POST', f'/api/cpq/quotations/{qid}/ensure-card-values')
        d = data_of(j) if isinstance(j, dict) else {}
        warming = isinstance(d, dict) and d.get('cardValuesWarming') is True
        states = [(lid, card_usable(card_text(lid))) for lid, _, _ in lines_of(qid)]
        log(case, f'  [ensure-card-values] {api.name} {qid} -> http={st} warming={warming} 各行可用={states}')
        if all(u for _, u in states) or not want_usable or time.time() - t0 > timeout:
            return st, all(u for _, u in states)
        time.sleep(3)


def excel_view(api, case, qid, tag):
    st, j = api.call('GET', f'/api/cpq/quotations/{qid}/excel-view')
    raw = json.dumps(j, ensure_ascii=False)
    log(case, f'  [GET excel-view {tag}] {api.name} -> {st}\n    {raw}')
    return st, data_of(j) if isinstance(j, dict) else None


def row_of(view, line_id):
    for r in (view or {}).get('rows', []) or []:
        if r.get('_lineItemId') == line_id:
            return r
    return None


def fmt(row, cols):
    return ' / '.join(f"{c['col_key']}={row.get(c['col_key'], '<缺键>') if row is not None else '<无行>'}" for c in cols)


# ================================================================ T2.1 · AC-4
def t2_1(b, m, ctx):
    C = 'T2.1'
    AC = 'AC-4 正式账不可用 → 空白，不退回草稿纸'
    log(C, f'# T2.1 · {AC}\n# BASE={b.base} MASTER={m.base} 开始 {now()}')
    c1 = ctx['C1'] = copy_quote(b, C, SRC_0881, 'C1(0881)')
    c2 = ctx['C2'] = copy_quote(b, C, SRC_0564, 'C2(0564)')
    for q in (c1, c2):
        ensure_card(b, C, q)
    l1 = lines_of(c1); l2 = lines_of(c2)
    log(C, f'  C1 行={l1}\n  C2 行={l2}')
    check(C, '前置-行数', AC, len(l1) == 1 and len(l2) == 4, f'C1 行数={len(l1)}（应 1）C2 行数={len(l2)}（应 4）')
    for q, ls in ((c1, l1), (c2, l2)):
        for lid, so, _ in ls:
            check(C, f'前置-正式账非空 {lid[:8]}', AC, card_usable(card_text(lid)), f'单 {q[:8]} 行 {lid[:8]} sort={so} 正式账可用')
    line1, _, tpl1 = l1[0]
    st, v0 = excel_view(b, C, c1, '健康态')
    cols = v0['columns']; tj = tj_cols(cols)
    ctx['C1_cols'] = cols; ctx['C1_line'] = line1; ctx['C1_tpl'] = tpl1
    check(C, '前置-列', AC, len(cols) == 3 and len(tj) == 3, f"健康态 columns={[c['col_key'] + ':' + c['title'] for c in cols]}")
    cv0 = card_text(line1)
    log(C, f"  健康态 C1 判定值 = {[oracle(cv0, c) for c in tj]}；本分支返回 {fmt(row_of(v0, line1), tj)}")

    def state_null(step, set_sql, state_desc, gate):
        guarded_update(C, line1, set_sql)
        before = card_text(line1)
        log(C, f'  状态 {step}: C1 行 quote_card_values = {before[:200]}')
        st, vb = excel_view(b, C, c1, f'{step} 本分支')
        rb = row_of(vb, line1)
        stm, vm = excel_view(m, C, c1, f'{step} master')
        rm = row_of(vm, line1)
        after = card_text(line1)
        held = after == before
        check(C, f'{step} 状态保持（两次请求期间 DB 卡片值未被改写）', AC, held,
              f'请求前={before[:80]} 请求后={after[:80]}')
        ok = (st == 200 and vb is not None and len(vb.get('columns', [])) == 3 and rb is not None
              and all(rb.get(c['col_key']) is None for c in tj))
        check(C, f'{step} 本分支三列 null', AC + ('' if gate else '（补充，api.md 口径）'), ok,
              f"{state_desc}；HTTP={st} columns={len((vb or {}).get('columns', []))} 行={fmt(rb, tj)}"
              f"｜键存在={[c['col_key'] in (rb or {}) for c in tj]}")
        mnum = rm is not None and all(is_number(rm.get(c['col_key'])) for c in tj)
        if step == 'S1':
            check(C, f'{step} master 对照非空数字', AC, stm == 200 and mnum, f'master HTTP={stm} 行={fmt(rm, tj)}')
        else:
            log(C, f'  [记录] {step} master HTTP={stm} 行={fmt(rm, tj)}')
        return after

    # 1. NULL
    state_null('S1', 'quote_card_values = NULL', 'quote_card_values=NULL', True)
    # 2. 失败标记 + 空 tabs（AC-4 原文形态）
    state_null('S2', """quote_card_values = '{"tabs":[],"__cardValueFailed":true}'::jsonb""",
               'quote_card_values={"tabs":[],"__cardValueFailed":true}', True)
    # 3. 同一状态下两个试算入口
    body = {'templateId': tpl1, 'columns': cols}
    for api, who in ((b, '本分支'), (m, 'master')):
        st, j = api.call('POST', f'/api/cpq/quotations/{c1}/excel-view/dry-run', body)
        log(C, f'  [POST excel-view/dry-run S3 {who}] -> {st}\n    {json.dumps(j, ensure_ascii=False)}')
        r = row_of(data_of(j) if isinstance(j, dict) else None, line1)
        if who == '本分支':
            check(C, 'S3 dry-run 三列 null', AC, st == 200 and r is not None and all(r.get(c['col_key']) is None for c in tj),
                  f'HTTP={st} 行={fmt(r, tj)}')
        else:
            log(C, f'  [记录] S3 master dry-run 行={fmt(r, tj)}')
        tf_body = {'lineItemId': line1, 'column': tj[0]}
        st, j = api.call('POST', f'/api/cpq/templates/{tpl1}/excel-view-config/dry-run-tab-formula', tf_body)
        log(C, f'  [POST dry-run-tab-formula col_1 S3 {who}] -> {st}\n    {json.dumps(j, ensure_ascii=False)}')
        d = data_of(j) if isinstance(j, dict) else {}
        if who == '本分支':
            check(C, 'S3 dry-run-tab-formula value=null 且 errors 恰含固定文案', AC,
                  st == 200 and isinstance(d, dict) and d.get('value') is None and d.get('errors') == [DRYRUN_ERR],
                  f"HTTP={st} value={d.get('value') if isinstance(d, dict) else d!r} errors={d.get('errors') if isinstance(d, dict) else None}")
        else:
            log(C, f"  [记录] S3 master tab-formula value={d.get('value') if isinstance(d, dict) else d!r} errors={d.get('errors') if isinstance(d, dict) else None}")
    # 补充形态（api.md 口径；jsonb 列无法构造「空白字符串 / 解析失败」两种，见回报「未验证」）
    real_tabs = json.loads(cv0)['tabs']
    state_null('S2b', """quote_card_values = '{"tabs":[]}'::jsonb""", 'quote_card_values={"tabs":[]}（无失败标记）', False)
    state_null('S2c', "quote_card_values = '{}'::jsonb", 'quote_card_values={}（tabs 缺失）', False)
    fail_real = json.dumps({'__cardValueFailed': True, 'tabs': real_tabs}, ensure_ascii=False).replace("'", "''")
    state_null('S2d', f"quote_card_values = '{fail_real}'::jsonb", 'quote_card_values=真实 tabs + __cardValueFailed:true（判别性：tabs 非空仍应 null）', False)

    # 4. C2 第 2 行置 NULL，其余行按各自判定值
    l2_ids = [x[0] for x in l2]
    target = l2_ids[1]
    guarded_update(C, target, 'quote_card_values = NULL')
    for api, who in ((b, '本分支'), (m, 'master')):
        st, v = excel_view(api, C, c2, f'S4 {who}')
        tj2 = tj_cols((v or {}).get('columns', []))
        check(C, f'S4 {who} 前置-列非空', AC, len(tj2) >= 1, f'{who} TAB_JOIN_FORMULA 列数={len(tj2)}')
        for idx, lid in enumerate(l2_ids):
            r = row_of(v, lid)
            if lid == target:
                ok = r is not None and all(r.get(c['col_key']) is None for c in tj2)
                if who == '本分支':
                    check(C, f'S4 本分支 第2行 null', AC, ok, f'行 {lid[:8]} {fmt(r, tj2)}')
                else:
                    log(C, f'  [记录] S4 master 第2行 {fmt(r, tj2)}')
            else:
                cv = card_text(lid)
                orc = [oracle(cv, c) for c in tj2]
                ok = r is not None and all(eq_exact(r.get(c['col_key']), o) for c, o in zip(tj2, orc))
                if who == '本分支':
                    check(C, f'S4 本分支 第{idx + 1}行=判定值', AC, ok, f'行 {lid[:8]} 返回 {fmt(r, tj2)} 判定 {orc}')
                else:
                    log(C, f'  [记录] S4 master 第{idx + 1}行 返回 {fmt(r, tj2)} 判定 {orc} 相等={ok}')

    # 5. ensure-card-values 恢复 → 全部格 = 判定值
    for q in (c1, c2):
        st, usable = ensure_card(b, C, q, timeout=60)
        if not usable:
            # 恢复不到（例如失败标记非 NULL 时补算不重算）→ 如实记录，再置 NULL 重试
            log(C, f'  [观察] ensure-card-values 后 {q[:8]} 仍有行不可用：{[(l[0][:8], card_text(l[0])[:120]) for l in lines_of(q)]}')
            for lid, _, _ in lines_of(q):
                if not card_usable(card_text(lid)):
                    guarded_update(C, lid, 'quote_card_values = NULL')
            ensure_card(b, C, q)
    for q, ls in ((c1, l1), (c2, l2)):
        for api, who in ((b, '本分支'), (m, 'master')):
            st, v = excel_view(api, C, q, f'S5 {who}')
            tjq = tj_cols((v or {}).get('columns', []))
            for lid, so, _ in ls:
                cv = card_text(lid)
                orc = [oracle(cv, c) for c in tjq] if card_usable(cv) else None
                r = row_of(v, lid)
                ok = orc is not None and r is not None and all(eq_exact(r.get(c['col_key']), o) for c, o in zip(tjq, orc))
                if who == '本分支':
                    check(C, f'S5 本分支 {q[:8]}/{lid[:8]}=判定值', AC, ok, f'返回 {fmt(r, tjq)} 判定 {orc}')
                else:
                    log(C, f'  [记录] S5 master {q[:8]}/{lid[:8]} 返回 {fmt(r, tjq)} 判定 {orc} 相等={ok}')


# ================================================================ T2.2 · AC-5
def read_xlsx(path):
    import openpyxl
    wb = openpyxl.load_workbook(path)          # 读单元格原值（不是显示格式）
    ws = wb.worksheets[0]
    rows = [[c.value for c in r] for r in ws.iter_rows()]
    types = [[c.data_type for c in r] for r in ws.iter_rows()]
    return rows, types


def cell_of(hdr, data, col):
    """表头按 col_key 定位（master 实测表头是 col_1/col_2/col_3），找不到再按 title；都找不到返回 '<无此列>'"""
    if data is None:
        return '<无数据行>'
    for k in (col['col_key'], col.get('title')):
        if k in hdr:
            return data[hdr.index(k)]
    return '<无此列>'


def export(api, C, qid, name):
    st, body = api.call('GET', f'/api/cpq/quotations/{qid}/export-excel-view', binary=True)
    p = os.path.join(OUT, name)
    with open(p, 'wb') as f:
        f.write(body)
    log(C, f'  [GET export-excel-view] {api.name} -> {st} bytes={len(body)} 存 {name}')
    return st, p


def t2_2(b, m, ctx):
    C = 'T2.2'
    AC = 'AC-5 导出兜底按正式账'
    log(C, f'# T2.2 · {AC}\n# BASE={b.base} MASTER={m.base} 开始 {now()}')
    if 'C1' not in ctx:
        ctx['C1'] = copy_quote(b, C, SRC_0881, 'C1(0881,T2.2单跑)')
        ensure_card(b, C, ctx['C1'])
        ctx['C1_line'] = lines_of(ctx['C1'])[0][0]
        st, v = excel_view(b, C, ctx['C1'], '取列')
        ctx['C1_cols'] = v['columns']
    c1, line1 = ctx['C1'], ctx['C1_line']
    tj = tj_cols(ctx['C1_cols'])
    cv = card_text(line1)
    check(C, '前置-C1 正式账已恢复非空', AC, card_usable(cv), f'C1 行 {line1[:8]} 正式账可用={card_usable(cv)}')
    orc = [oracle(cv, c) for c in tj]
    log(C, f'  判定值 = {orc}')

    files = {}
    for api, who, fname in ((b, '本分支', 'T2.2-本分支-正式账可用.xlsx'), (m, 'master', 'T2.2-master-正式账可用.xlsx')):
        guarded_update(C, line1, 'quote_excel_values = NULL')          # 每次导出前都重新置空（防导出自身补算写回）
        log(C, f'  导出前 quote_excel_values = {excel_text(line1)[:200]}')
        st, p = export(api, C, c1, fname)
        log(C, f'  导出后 quote_excel_values = {excel_text(line1)[:200]}')
        rows, types = read_xlsx(p)
        log(C, f'  {who} xlsx 全部行 = {rows}\n  单元格类型 = {types}')
        files[who] = (st, rows)
    hb, hm = files['本分支'][1][0] if files['本分支'][1] else None, files['master'][1][0] if files['master'][1] else None
    check(C, '表头两份一致', AC, hb is not None and hb == hm, f'本分支表头={hb} master表头={hm}')
    for who in ('本分支', 'master'):
        st, rows = files[who]
        hdr = rows[0] if rows else []
        data = rows[1] if len(rows) > 1 else None
        vals = [cell_of(hdr, data, c) for c in tj]
        diffs = []
        for v, o in zip(vals, orc):
            try:
                diffs.append(abs(Decimal(str(v)) - Decimal(str(o))))
            except Exception:
                diffs.append(None)
        if who == '本分支':
            check(C, '本分支数据行=判定值(≤1e-9)', AC, st == 200 and all(d is not None and d <= Decimal('1e-9') for d in diffs),
                  f'HTTP={st} 第2行={vals} 判定={orc} 差={diffs}')
        else:
            check(C, 'master 数据行≠判定值（草稿纸值，对照）', AC,
                  st == 200 and all(is_number(v) for v in vals) and any(d is not None and d > Decimal('1e-9') for d in diffs),
                  f'HTTP={st} 第2行={vals} 判定={orc} 差={diffs}')
    # 正式账也置空 → 本分支导出空单元格
    guarded_update(C, line1, 'quote_excel_values = NULL, quote_card_values = NULL')
    st, p = export(b, C, c1, 'T2.2-本分支-正式账也空.xlsx')
    log(C, f'  导出后 quote_excel_values = {excel_text(line1)[:200]} quote_card_values = {card_text(line1)[:120]}')
    rows, types = read_xlsx(p)
    log(C, f'  本分支(正式账也空) xlsx 全部行 = {rows}\n  单元格类型 = {types}')
    hdr = rows[0] if rows else []
    data = rows[1] if len(rows) > 1 else None
    vals = [cell_of(hdr, data, c) for c in tj]
    check(C, '正式账也空：本分支数据行三格为空单元格', AC, st == 200 and data is not None and all(v in (None, '') for v in vals),
          f'HTTP={st} 第2行={vals}')
    ensure_card(b, C, c1)   # 恢复，供后续用例


# ================================================================ T2.3 · AC-8
def t2_3(b, m, ctx):
    C = 'T2.3'
    AC = 'AC-8 复制 → 改值 → 失效 → 恢复'
    log(C, f'# T2.3 · {AC}\n# BASE={b.base} MASTER={m.base} 开始 {now()}')
    c3 = ctx['C3'] = copy_quote(b, C, SRC_0881, 'C3(0881)')
    ensure_card(b, C, c3)
    ls = lines_of(c3)
    check(C, '前置-行数', AC, len(ls) == 1, f'C3 行数={len(ls)}')
    line3 = ls[0][0]
    if os.environ.get('AC8_FRESH_BASELINE') == '1':
        # 默认关闭。仅当主线裁决「AC-8 的 V1 取重算后的正式账」后才开（见 回报-S2 §AC-8 前提问题）：
        # 复制单带来的是源单旧卡片值，重算后 col_1/col_2 会变，导致第 3 步两条断言的前提不成立
        log(C, '  [AC8_FRESH_BASELINE=1] 先把 C3 行正式账置 NULL 并重算，V1 取重算后的正式账')
        guarded_update(C, line3, 'quote_card_values = NULL')
        ensure_card(b, C, c3)
    cv1 = card_text(line3)
    check(C, '前置-正式账非空', AC, card_usable(cv1), f'C3 行 {line3[:8]} 正式账可用={card_usable(cv1)}')
    tab = next((t for t in json.loads(cv1)['tabs'] if t.get('componentId') == ASM_FEE_CID), None)
    rk = None
    if tab:
        for src in (tab.get('editRows') or [], tab.get('formulaResults') or []):
            if src and src[0].get('rowKey'):
                rk = src[0]['rowKey']; break
    check(C, '前置-组装加工费 rowKey', AC, rk is not None, f'C3 行={line3} 组装加工费 rowKey={rk!r}（立项 0881 为 S3120011203||Z101）')

    # 1
    st, v = excel_view(b, C, c3, 'P1 本分支')
    tj = tj_cols(v['columns'])
    V1 = [oracle(cv1, c) for c in tj]
    r = row_of(v, line3)
    check(C, 'P1 三列=判定值V1', AC, r is not None and all(eq_exact(r.get(c['col_key']), o) for c, o in zip(tj, V1)),
          f'返回 {fmt(r, tj)} V1={V1}')
    stm, vm = excel_view(m, C, c3, 'P1 master')
    log(C, f'  [记录] P1 master {fmt(row_of(vm, line3), tj)}')
    # 2
    body = {'componentId': ASM_FEE_CID, 'rowKey': rk, 'fieldName': '加工费', 'value': '50'}
    st, j = b.call('PUT', f'/api/cpq/quotations/line-items/{line3}/quote-card-edit', body)
    log(C, f'  [PUT quote-card-edit] body={json.dumps(body, ensure_ascii=False)} -> {st}\n    {json.dumps(j, ensure_ascii=False)[:1500]}')
    code = j.get('code') if isinstance(j, dict) else None
    check(C, 'P2 quote-card-edit 200', AC, st == 200 and code in (None, 200), f'HTTP={st} code={code}')
    # 3
    cv2 = card_text(line3)
    if not card_usable(cv2):
        log(C, f'  [观察] 改值后正式账不可用：{cv2[:200]}；调 ensure-card-values')
        ensure_card(b, C, c3); cv2 = card_text(line3)
    V2 = [oracle(cv2, c) for c in tj]
    st, v = excel_view(b, C, c3, 'P3 本分支')
    r = row_of(v, line3)
    check(C, 'P3 三列=改值后判定值V2', AC, r is not None and all(eq_exact(r.get(c['col_key']), o) for c, o in zip(tj, V2)),
          f'返回 {fmt(r, tj)} V2={V2}')
    k3 = [i for i, c in enumerate(tj) if c['col_key'] == 'col_3']
    ok_gt = bool(k3) and V1[k3[0]] is not None and V2[k3[0]] is not None and Decimal(str(V2[k3[0]])) > Decimal(str(V1[k3[0]]))
    check(C, 'P3 V2.col_3 > V1.col_3', AC, ok_gt, f'V1.col_3={V1[k3[0]] if k3 else None} V2.col_3={V2[k3[0]] if k3 else None}')
    same12 = all(eq_exact(V2[i], V1[i]) for i, c in enumerate(tj) if c['col_key'] in ('col_1', 'col_2'))
    check(C, 'P3 col_1/col_2 与 V1 相同', AC, same12, f'V1={V1} V2={V2}')
    stm, vm = excel_view(m, C, c3, 'P3 master')
    log(C, f'  [记录] P3 master {fmt(row_of(vm, line3), tj)}')
    # 4
    guarded_update(C, line3, 'quote_card_values = NULL')
    st, v = excel_view(b, C, c3, 'P4 本分支')
    r = row_of(v, line3)
    check(C, 'P4 正式账 NULL → 三列 null', AC, st == 200 and r is not None and all(r.get(c['col_key']) is None for c in tj),
          f'HTTP={st} 返回 {fmt(r, tj)} ｜请求后 DB 卡片值={card_text(line3)[:60]}')
    stm, vm = excel_view(m, C, c3, 'P4 master')
    log(C, f'  [记录] P4 master {fmt(row_of(vm, line3), tj)}')
    # 5
    ensure_card(b, C, c3)
    cv5 = card_text(line3)
    V5 = [oracle(cv5, c) for c in tj] if card_usable(cv5) else None
    st, v = excel_view(b, C, c3, 'P5 本分支')
    r = row_of(v, line3)
    check(C, 'P5 恢复后三列=判定值', AC, V5 is not None and r is not None and all(eq_exact(r.get(c['col_key']), o) for c, o in zip(tj, V5)),
          f'返回 {fmt(r, tj)} 判定={V5}')
    check(C, 'P5 恢复后判定值=V2', AC, V5 is not None and all(eq_exact(a, x) for a, x in zip(V5, V2)), f'V5={V5} V2={V2}')
    stm, vm = excel_view(m, C, c3, 'P5 master')
    log(C, f'  [记录] P5 master {fmt(row_of(vm, line3), tj)}')


# ================================================================ T2.4 · AC-12
def t2_4(b, m, ctx):
    C = 'T2.4'
    AC = 'AC-12 正式账缺失时补算不写 Excel 存值'
    log(C, f'# T2.4 · {AC}\n# BASE={b.base} MASTER={m.base} 开始 {now()}')
    c4 = ctx['C4'] = copy_quote(b, C, SRC_0881, 'C4(0881,本分支用)')
    c5 = ctx['C5'] = copy_quote(b, C, SRC_0881, 'C5(0881,master用)')
    for q in (c4, c5):
        ensure_card(b, C, q)
    l4 = lines_of(c4)[0][0]; l5 = lines_of(c5)[0][0]
    for lid in (l4, l5):
        check(C, f'前置-正式账非空 {lid[:8]}', AC, card_usable(card_text(lid)), f'行 {lid[:8]}')
        guarded_update(C, lid, 'quote_card_values = NULL, quote_excel_values = NULL')
        log(C, f'  置空后 {lid[:8]} card={card_text(lid)[:20]} excel={excel_text(lid)[:20]}')
    st, j = b.call('POST', f'/api/cpq/quotations/{c4}/ensure-excel-values')
    log(C, f'  [POST ensure-excel-values C4] 本分支 -> {st} {json.dumps(j, ensure_ascii=False)[:600]}')
    st5, j5 = m.call('POST', f'/api/cpq/quotations/{c5}/ensure-excel-values')
    log(C, f'  [POST ensure-excel-values C5] master -> {st5} {json.dumps(j5, ensure_ascii=False)[:600]}')
    e4 = excel_text(l4); e5 = excel_text(l5)
    check(C, 'C4 quote_excel_values 仍为 NULL', AC, e4 == '<NULL>', f'HTTP={st} C4 excel={e4} ｜C4 card={card_text(l4)[:40]}')
    check(C, 'C5(master) 被写入非空值（对照）', AC, e5 != '<NULL>', f'HTTP={st5} C5 excel={e5} ｜C5 card={card_text(l5)[:40]}')
    # 恢复后补算写对
    ensure_card(b, C, c4)
    st, j = b.call('POST', f'/api/cpq/quotations/{c4}/ensure-excel-values')
    log(C, f'  [POST ensure-excel-values C4 恢复后] -> {st} {json.dumps(j, ensure_ascii=False)[:600]}')
    cv = card_text(l4); e4 = excel_text(l4)
    st_v, v = excel_view(b, C, c4, '取列')
    tj = tj_cols((v or {}).get('columns', []))
    orc = [oracle(cv, c) for c in tj] if card_usable(cv) else None
    try:
        r0 = json.loads(e4)['rows'][0]
    except Exception:
        r0 = None
    ok = orc is not None and r0 is not None and len(tj) == 3 and all(eq_exact(r0.get(c['col_key']), o) for c, o in zip(tj, orc))
    check(C, '恢复后 C4 rows[0] 三列=判定值', AC, ok, f'excel={e4} 判定={orc}')


# ================================================================ main
CASES = {'T2.1': t2_1, 'T2.2': t2_2, 'T2.3': t2_3, 'T2.4': t2_4}


def cleanup(b):
    for qid in CREATED:
        try:
            st, j = b.call('DELETE', f'/api/cpq/quotations/{qid}')
            n = psql(f"select count(*) from quotation where id='{qid}'")
            msg = f'DELETE -> HTTP {st} {json.dumps(j, ensure_ascii=False)[:200]}；count={n}'
            log('cleanup', f'{qid} {msg}')
            ledger('收尾', '-', qid, '已删除（count=0）' if n == '0' else f'**待清理**（{msg}）')
            print(f'[cleanup] {qid} {msg}')
        except Exception as e:
            log('cleanup', f'{qid} 删除异常 {e!r}')
            ledger('收尾', '-', qid, f'**待清理**（删除异常 {e!r}）')


def main():
    want = [c.strip() for c in (sys.argv[1] if len(sys.argv) > 1 else 'T2.1,T2.2,T2.3,T2.4').split(',') if c.strip()]
    with open(os.path.join(OUT, 'RUN_INFO.txt'), 'w', encoding='utf-8') as f:
        f.write(f'start={now()}\nBASE={BASE}\nMASTER={MASTER}\nDB={DB}\ncases={want}\nscript={os.path.abspath(__file__)}\n')
    rc = 0
    b = m = None
    ctx = {}
    try:
        b = Api(BASE, f'BASE({BASE})')
        m = Api(MASTER, f'MASTER({MASTER})')
        for c in want:
            try:
                CASES[c](b, m, ctx)
            except Exception:
                tb = traceback.format_exc()
                log(c, '  [ERROR] ' + tb)
                RESULTS.append((c, '脚本异常', '', 'ERROR', tb.strip().splitlines()[-1]))
                print(f'[ERROR] {c}: {tb}')
    finally:
        if b is not None:
            cleanup(b)
        elif CREATED:
            ledger('收尾', '-', ','.join(CREATED), '**待清理**（未能登录，无法删除）')
    with open(os.path.join(OUT, 'results.tsv'), 'w', encoding='utf-8') as f:
        f.write('case\tstep\tac\tverdict\tdetail\n')
        for r in RESULTS:
            f.write('\t'.join(str(x).replace('\t', ' ').replace('\n', ' ') for x in r) + '\n')
    n_fail = sum(1 for r in RESULTS if r[3] != 'PASS')
    print(f'\n== 汇总 {now()} BASE={BASE} 断言 {len(RESULTS)} 条，PASS {len(RESULTS) - n_fail}，FAIL/ERROR {n_fail}；证据 {OUT}')
    if any(r[3] == 'ERROR' for r in RESULTS):
        return 2
    return 1 if n_fail else 0


if __name__ == '__main__':
    sys.exit(main())
