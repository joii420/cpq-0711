#!/usr/bin/env python3
"""AC-3 报价侧 quote_excel_values 数值等价比对器。
🚫 不做逐字节 diff：autosave 会把叶子 number 归一成 string（1 -> "1"），
   逐字节会假红。判据 = 「键集合一致 + 叶子数值等价」。
用法: qev_cmp.py <before.json> <after.json>   退出码 0=无差异, 1=有差异
"""
import json, sys
from decimal import Decimal, InvalidOperation

def norm(v):
    """叶子归一：能解析成数的一律按 Decimal 比，否则按原字符串比。"""
    if isinstance(v, bool) or v is None:
        return ('raw', v)
    if isinstance(v, (int, float, str)):
        try:
            return ('num', Decimal(str(v)))
        except InvalidOperation:
            return ('raw', str(v))
    return ('raw', v)

def walk(o, path, out):
    if isinstance(o, dict):
        for k in sorted(o): walk(o[k], f'{path}.{k}', out)
    elif isinstance(o, list):
        for i, v in enumerate(o): walk(v, f'{path}[{i}]', out)
    else:
        out[path] = norm(o)

def flat(rec):
    out = {}
    for x in rec:
        v = x['v']
        if isinstance(v, str): v = json.loads(v)
        walk(v, x['part'], out)
    return out

a, b = flat(json.load(open(sys.argv[1]))), flat(json.load(open(sys.argv[2])))
diffs = []
for k in sorted(set(a) | set(b)):
    if k not in a: diffs.append(f'仅 after 有: {k} = {b[k]}')
    elif k not in b: diffs.append(f'仅 before 有: {k} = {a[k]}')
    elif a[k] != b[k]: diffs.append(f'值变化: {k}: {a[k]} -> {b[k]}')
print(f'叶子数 before={len(a)} after={len(b)} 差异={len(diffs)}')
for d in diffs[:40]: print('  ', d)
sys.exit(1 if diffs else 0)
