"""S-C 扫描器：沿用 证据/离线判决/scan_subtotal_refs.py 的判定口径（引用串 → 组件 → 该组件勾了小计的列），
扩展到 AC-15③ 要求的四处存储，并识别新写法 (小计) 后缀。
用法: python3 scan_s_c.py <dir>   # dir 下需有 comps.jsonl，可选 templates.jsonl / tcs.jsonl / cet.jsonl
输出末行: RESULT bare_subtotal_refs=<N> suffix_on_non_subtotal=<M> unresolved=<K> tabjoin_cols=<C>
页签解析顺序（与问题说明 5.4 一致）：列自身 tabs[] 中 alias 相同者的 tabKey 冒号前一段 → 组件 id（idx: 开头跳过）；
解析不到时退回原脚本口径（按 code，或同目录同名组件，仅 component 存储可用）。解析不到的引用单独计数并打印，不静默跳过。
"""
import json, re, sys, os, collections
d = sys.argv[1]
def load(n):
    p = os.path.join(d, n)
    return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()] if os.path.exists(p) else []
comps = load('comps.jsonl')
byId = {c['id']: c for c in comps}; byCode = {c['code']: c for c in comps}
def subcols(c): return {f.get('name') for f in (c.get('fields') or []) if f.get('is_subtotal') in (True, 'true')}
tok = re.compile(r'\[([^\[\]]+)\]')
cnt = collections.Counter()
def scan_cols(where, cols, dirid=None):
    for col in cols or []:
        if not isinstance(col, dict) or col.get('source_type') != 'TAB_JOIN_FORMULA': continue
        cnt['tabjoin_cols'] += 1
        tabs = {t.get('alias'): t.get('tabKey') for t in (col.get('tabs') or []) if isinstance(t, dict)}
        for m in tok.finditer(col.get('expression') or ''):
            b = m.group(1).strip()
            suf = None
            for s in ('(总计)', '(小计)'):
                if b.endswith(s): suf, b = s, b[:-len(s)]
            if '.' not in b: continue   # [页签(总计)] / [页签(小计)] / 裸字段：不属于「引用小计列」
            a, cn = b.split('.', 1)
            src = None
            tk = tabs.get(a)
            if tk and not str(tk).startswith('idx:'): src = byId.get(str(tk).split(':', 1)[0])
            if src is None: src = byCode.get(a) or (next((x for x in comps if x['name'] == a and x.get('dir') == dirid), None) if dirid else None)
            if src is None:
                cnt['unresolved'] += 1; print(f'  [unresolved] {where} {col.get("col_key")} [{m.group(1)}]'); continue
            issub = cn in subcols(src)
            if suf is None and issub:
                cnt['bare_subtotal_refs'] += 1; print(f'  [裸引用小计列] {where} {col.get("col_key")} [{m.group(1)}]')
            if suf == '(小计)' and not issub:
                cnt['suffix_on_non_subtotal'] += 1; print(f'  [(小计)用在非小计列] {where} {col.get("col_key")} [{m.group(1)}]')
            if suf == '(小计)' and issub: cnt['suffix_ok'] += 1
for c in comps:
    ex = c.get('excel')
    if isinstance(ex, list): scan_cols(f'component:{c["code"]}', ex, c.get('dir'))
for t in load('templates.jsonl'):
    cfg = t.get('cfg')
    cols = cfg if isinstance(cfg, list) else ((cfg or {}).get('column_overrides') if isinstance(cfg, dict) else None)
    scan_cols(f'template:{t["id"]}', cols)
for t in load('tcs.jsonl'): scan_cols(f'template_component_snapshot:{t["id"]}', t.get('excel') if isinstance(t.get('excel'), list) else [])
for t in load('cet.jsonl'): scan_cols(f'customer_excel_template:{t["id"]}', t.get('excel') if isinstance(t.get('excel'), list) else [])
print('RESULT ' + ' '.join(f'{k}={cnt[k]}' for k in ('bare_subtotal_refs', 'suffix_on_non_subtotal', 'suffix_ok', 'unresolved', 'tabjoin_cols')))
