#!/usr/bin/env python3
"""AC-7 比对器：报价侧 quote_card_values 两次快照的『数值等价』比对。
   —— 为什么不是逐字节 diff：实测（在**未修复**代码上）仅打开报价单编辑页就会触发一次
      重写，把部分 JSON number 归一成 string（1 -> "1"），数值本身不变。
      逐字节判据会把这条既有行为误报成本次修复的回归。
   本比对器：键集合必须完全一致；对每个叶子值，能转 float 的按数值比，否则按字符串比。"""
import json, sys, hashlib

def flat(o, p=''):
    if isinstance(o, dict):
        for k, v in o.items(): yield from flat(v, p + '/' + str(k))
    elif isinstance(o, list):
        for i, v in enumerate(o): yield from flat(v, p + f'[{i}]')
    else: yield p, o

def norm(v):
    if v is None: return None
    try: return ('num', float(v))
    except (TypeError, ValueError): return ('str', str(v))

a_p, b_p = sys.argv[1], sys.argv[2]
ra, rb = open(a_p, 'rb').read(), open(b_p, 'rb').read()
print(f'A={a_p} sha256={hashlib.sha256(ra).hexdigest()}')
print(f'B={b_p} sha256={hashlib.sha256(rb).hexdigest()}')
print('byte-identical:', ra == rb)
fa, fb = dict(flat(json.loads(ra))), dict(flat(json.loads(rb)))
ka, kb = set(fa), set(fb)
ok = True
if ka - kb: ok = False; print('!! 仅 A 有的键:', sorted(ka - kb)[:30])
if kb - ka: ok = False; print('!! 仅 B 有的键:', sorted(kb - ka)[:30])
num_ch = [(k, fa[k], fb[k]) for k in ka & kb if norm(fa[k]) != norm(fb[k])]
typ_ch = [k for k in ka & kb if fa[k] != fb[k] and norm(fa[k]) == norm(fb[k])]
print(f'数值/文本发生变化的叶子: {len(num_ch)}')
for k, x, y in num_ch[:40]: print('   CHANGED', k, repr(x), '->', repr(y))
print(f'仅类型归一(number<->string，数值不变)的叶子: {len(typ_ch)}')
if num_ch: ok = False
print('AC-7 数值等价:', 'PASS' if ok else 'FAIL')
sys.exit(0 if ok else 1)
