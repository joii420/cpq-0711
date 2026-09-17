"""盘点「引用了小计列」的存量公式与 Excel 连表列（只读）。
用法：
  PGPASSWORD=... psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -c \
    "select json_build_object('id',id,'code',code,'name',name,'type',component_type,'fields',fields,'formulas',formulas,'excel',excel_columns,'dir',directory_id) from component" > comps.jsonl
  python3 scan_subtotal_refs.py comps.jsonl
"""
import json, re, sys, collections
comps = [json.loads(l) for l in open(sys.argv[1])]
byId = {c['id']: c for c in comps}
byCode = {c['code']: c for c in comps}
def subcols(c): return {f.get('name') for f in (c['fields'] or []) if f.get('is_subtotal') in (True, 'true')}
stats = collections.Counter(); ex = collections.defaultdict(list)
def walk(toks, host, fname):
    for t in toks or []:
        ty = t.get('type')
        if ty == 'component_subtotal' and not t.get('is_tab_total') and t.get('value') not in (None, '', '__amount_total__'):
            stats['A 列小计引用(component_subtotal)'] += 1; ex['A'].append((host['code'], fname, t.get('component_code'), t.get('value')))
        if ty == 'cross_tab_ref':
            src = byId.get(t.get('source')); tgt = t.get('target'); agg = (t.get('agg') or 'NONE').upper()
            if tgt and not t.get('targetExpr') and src and tgt in subcols(src):
                k = f'B 按行引用小计列 agg={agg}'; stats[k] += 1; ex[k].append((host['code'], fname, src['code'], tgt))
            walk(t.get('targetExpr'), host, fname)
for c in comps:
    for f in c['formulas'] or []:
        walk(f.get('expression'), c, f.get('name'))
print(dict(stats))
for k, v in ex.items():
    print(k, len(v), '条 / 组件', len({x[0] for x in v}))
    for x in v: print('   ', x)
tok = re.compile(r'\[([^\[\]]+)\]'); ec = collections.Counter()
for c in comps:
    for col in c['excel'] or []:
        if col.get('source_type') != 'TAB_JOIN_FORMULA': continue
        ec['TAB_JOIN_FORMULA 列数'] += 1
        for m in tok.finditer(col.get('expression') or ''):
            b = m.group(1).strip(); total = b.endswith('(总计)'); body = b[:-4] if total else b
            if '.' not in body: ec['页签总计' if total else '裸字段'] += 1; continue
            a, colname = body.split('.', 1)
            src = byCode.get(a) or next((x for x in comps if x['name'] == a and x['dir'] == c['dir']), None)
            issub = src is not None and colname in subcols(src)
            ec[('带(总计)' if total else '不带(总计)') + '·' + ('小计列' if issub else '非小计列')] += 1
            if issub and not total: print('  Excel 列裸引用小计列:', c['code'], c['name'], col.get('col_key'), b)
print('EXCEL', dict(ec))
