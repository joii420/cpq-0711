#!/usr/bin/env python3
"""task-260920 S-2 · R5 前「当前值 vs E-6 备份」逐列差异（只读本地 CSV）。用法：R5-差异报告.py <备份目录> <当前导出目录>"""
import csv, sys, os
csv.field_size_limit(sys.maxsize)
bk, cur = sys.argv[1], sys.argv[2]
KEY = {'qli.csv': 'id', 'qlcd.csv': 'id', 'quotation.csv': 'id', 'qpr.csv': 'id', 'mpvr.csv': 'id',
       'ds_quote_material_bom_record.csv': 'id', 'ds_quote_element_bom_record.csv': 'id', 'qvs.csv': 'id'}
def load(p, k):
    if not os.path.exists(p): return {}
    return {r[k]: r for r in csv.DictReader(open(p, newline='', encoding='utf-8'))}
total = 0
for f, k in KEY.items():
    a, b = load(os.path.join(bk, f), k), load(os.path.join(cur, f), k)
    added = sorted(set(b) - set(a)); gone = sorted(set(a) - set(b)); changed = {}
    for i in set(a) & set(b):
        for c in a[i]:
            if a[i][c] != b[i].get(c):
                changed.setdefault(c, []).append(i)
    print(f'== {f}: 备份 {len(a)} 行 / 当前 {len(b)} 行；新增 {len(added)}；消失 {len(gone)}；有变化的列 {len(changed)}')
    for c, ids in sorted(changed.items()):
        ex = ids[0]; va = a[ex][c]; vb = b[ex][c]
        short = lambda v: (v[:80] + f'…(len {len(v)})') if len(v) > 80 else v
        print(f'   列 {c}: {len(ids)} 行变化  例 id={ex}  备份={short(va)!r}  当前={short(vb)!r}')
    for i in added:
        r = b[i]; print('   新增', {kk: (v[:60] if isinstance(v, str) else v) for kk, v in r.items() if kk in ('id', 'material_no', 'version_id', 'revision_no', 'based_version_id', 'sealed', 'quote_total_amount', 'created_at', 'updated_at')})
    for i in gone: print('   消失', i)
    total += len(added) + len(gone) + sum(len(v) for v in changed.values())
print(f'合计差异项 {total}')
