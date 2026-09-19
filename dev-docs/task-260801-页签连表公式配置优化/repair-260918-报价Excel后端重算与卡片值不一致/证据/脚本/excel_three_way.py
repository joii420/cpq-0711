#!/usr/bin/env python3
"""
repair-260918 立项期实测：报价 Excel 视图「后端现场重算 / 页面存值 / 正式账（卡片值）」三方对账。

对开发库中所有「模板配了 Excel 视图」的报价单逐行逐列比对：
  后端   = GET  /api/cpq/quotations/{qid}/excel-view                     （后端现场重算，本缺陷所在）
  存值   = quotation_line_item.quote_excel_values                          （页面前端引擎算好存下的）
  判定值 = 直接从该行 quote_card_values（正式账）JSON 取：
             [页签(总计)]        → tabs[componentId==tabKey].subtotal
             [页签.列] / (小计)  → tabs[componentId==tabKey].subtotalByColumn[列]
           另用 POST /api/cpq/templates/{tid}/excel-view-config/dry-run-tab-formula（传入 cardValuesJson）
           交叉核对判定值（该端点走 CardEffectiveRows，即「按正式账算」的既有代码路径）。

只读：只发 GET 与试算 POST（试算端点无写库），只跑 SELECT。

用法（先登录拿 cookie）：
  export WORKDIR=/tmp/rp0918 BASE=http://localhost:8081 DB=cpq_db_0724
  mkdir -p $WORKDIR && curl -s --noproxy '*' -c $WORKDIR/cj.txt -X POST $BASE/api/cpq/auth/login \
       -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@2026"}'
  python3 excel_three_way.py > 输出/三方对账-<yymmdd-HHMM>.txt
退出码：0 = 跑完（不论是否有差异）；非 0 = 脚本自身出错（登录失效会打印 LOGIN? 并退出 2）。
"""
import json, os, re, subprocess, sys
from decimal import Decimal

W = os.environ.get('WORKDIR', '/tmp/rp0918')
BASE = os.environ.get('BASE', 'http://localhost:8081')
DB = os.environ.get('DB', 'cpq_db_0724')
ENV = {'PGPASSWORD': os.environ.get('PGPASSWORD', 'joii5231'), 'PATH': '/usr/bin:/bin'}
TOL = Decimal('0')  # 判定：逐位相等


def psql(sql):
    r = subprocess.run(['psql', '-h', '10.177.152.12', '-U', 'postgres', '-d', DB, '-X', '-A', '-t', '-F', '\t', '-c', sql],
                       capture_output=True, text=True, env=ENV)
    if r.returncode != 0:
        sys.exit('psql failed: ' + r.stderr)
    return r.stdout.strip()


def curl(args):
    return subprocess.run(['curl', '-s', '--noproxy', '*', '-b', f'{W}/cj.txt'] + args, capture_output=True, text=True).stdout


def dec(v):
    return Decimal(str(v if v not in (None, '') else 0))


quotes = psql("""
  select c.code, q.quotation_number, q.id
    from template t
    join component c on c.id::text = t.excel_view_config->>'excel_component_id'
    join quotation_line_item li on li.template_id = t.id
    join quotation q on q.id = li.quotation_id
   group by 1,2,3, q.created_at order by q.created_at""").splitlines()

stats, lines_out = {}, []
for row in quotes:
    comp, qno, qid = row.split('\t')
    raw = curl([f'{BASE}/api/cpq/quotations/{qid}/excel-view'])
    try:
        body = json.loads(raw); body = body.get('data', body); be_rows = body['rows']; cols = body['columns']
    except Exception:
        print('LOGIN? excel-view 响应异常:', raw[:200]); sys.exit(2)
    tj = [c for c in cols if c.get('source_type') == 'TAB_JOIN_FORMULA']
    for brow in be_rows:
        lid = brow['_lineItemId']
        tid, ev, cv = psql(f"select template_id, coalesce(quote_excel_values::text,''), coalesce(quote_card_values::text,'') "
                           f"from quotation_line_item where id='{lid}'").split('\t')
        stored = json.loads(ev)['rows'][0] if ev else {}
        card = json.loads(cv) if cv else {'tabs': []}
        tabs = {t.get('componentId'): t for t in card.get('tabs', [])}
        for col in tj:
            tok = re.fullmatch(r'\[([^\]]+)\]', col['expression'].strip()).group(1)
            key_of = {t['alias']: t['tabKey'] for t in col['tabs']}
            if tok.endswith('(总计)'):
                t = tabs.get(key_of[tok[:-4]]); oracle = t.get('subtotal') if t else None
            else:
                a, c = tok.split('.', 1)
                c = c[:-4] if c.endswith('(小计)') else c
                t = tabs.get(key_of[a]); oracle = (t.get('subtotalByColumn') or {}).get(c) if t else None
            with open(f'{W}/req.json', 'w') as f:
                json.dump({'lineItemId': lid, 'column': col, 'cardValuesJson': cv}, f, ensure_ascii=False)
            dr = json.loads(curl(['-X', 'POST', '-H', 'Content-Type: application/json', '--data-binary', f'@{W}/req.json',
                                  f'{BASE}/api/cpq/templates/{tid}/excel-view-config/dry-run-tab-formula']))
            dr = dr.get('data', dr).get('value')
            b, s = brow.get(col['col_key']), stored.get(col['col_key'])
            k = ('后端=判定' if abs(dec(b) - dec(oracle)) <= TOL else '后端≠判定',
                 '存值=判定' if abs(dec(s) - dec(oracle)) <= Decimal('1e-8') else '存值≠判定',
                 '判定=试算' if abs(dec(oracle) - dec(dr)) <= TOL else '判定≠试算')
            stats[k] = stats.get(k, 0) + 1
            lines_out.append(f'{comp}\t{qno}\t{lid[:8]}\t{col["col_key"]}\t{col["expression"]}\t'
                             f'后端={b}\t存值={s}\t判定={oracle}\t试算={dr}\t{"|".join(k)}')

print('excel组件\t单号\t行\t列\t表达式\t后端\t存值\t判定(正式账)\t试算(按正式账)\t结论')
print('\n'.join(lines_out))
print('\n== 汇总（后端逐位相等；存值容差 1e-8，见 BL-0302）==')
for k, v in sorted(stats.items()):
    print(' / '.join(k), v)
print('格数合计', sum(stats.values()))
