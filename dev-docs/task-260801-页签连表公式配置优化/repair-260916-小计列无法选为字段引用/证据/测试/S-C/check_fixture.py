"""T-C1：核对共享夹具 tabjoin-excel-cases.json 的内容是否满足 AC-14 前置与期望值（独立 Decimal 重算，不信夹具里写的 expected）。
用法: python3 check_fixture.py <fixture.json>   末行 RESULT fails=N"""
import json, sys, re
from decimal import Decimal as D, getcontext
getcontext().prec = 50
fx = json.load(open(sys.argv[1], encoding='utf-8'))
fails = 0
def chk(ok, msg):
    global fails
    print(('✅ ' if ok else '❌ ') + msg)
    if not ok: fails += 1
tabs = {t.get('alias'): t for t in fx.get('tabs', [])}
wl = tabs.get('物料'); chk(wl is not None, f'夹具含页签 alias=物料（实际 aliases={list(tabs)}）')
if wl is None: print('RESULT fails=%d' % fails); sys.exit(1)
rows = wl.get('rows') or []
chk(len(rows) == 6, f'物料 rows 恰 6 行（实际 {len(rows)}）')
EXP_ROWS = ['0', '0.059191597', '0.463735546', '1.437983994', '0.015491845', '0.002538082']
got = [str(r.get('材料成本')) for r in rows]
chk(sorted(D(x) for x in got) == sorted(D(x) for x in EXP_ROWS), f'材料成本 6 值与 AC-14 前置一致（实际 {got}）')
s = sum((D(x) for x in got), D(0))
chk(s == D('1.978941064'), f'材料成本 6 行之和 = 1.978941064（实际 {s}）')
sub = (wl.get('subtotalByColumn') or {}).get('材料成本')
chk(sub is not None and D(str(sub)) == D('1.978941064'), f'subtotalByColumn.材料成本 = 1.978941064（实际 {sub}）')
flds = {f.get('name'): f for f in wl.get('fields') or []}
chk(flds.get('材料成本', {}).get('is_subtotal') in (True, 'true'), '材料成本 is_subtotal=true')
rk = set(wl.get('rowKeyFields') or [])
xs = [n for n, f in flds.items() if f.get('is_subtotal') in (False, 'false', None) and n not in rk and n != '材料成本'
      and all(re.fullmatch(r'-?\d+(\.\d+)?', str(r.get(n, ''))) for r in rows)]
chk(len(xs) >= 1, f'存在不勾小计的数值列 X（候选 {xs}）')
X = xs[0] if xs else None
sx = sum((D(str(r.get(X))) for r in rows), D(0)) if X else None
if X: chk(sx != 0, f'X={X} 各行之和非 0（={sx}），否则 b/c/e 无判别力')
# X 必须行间不全相同，才能区分「逐行相加」与「只取一行」
if X: chk(len({str(r.get(X)) for r in rows}) > 1, f'X 各行取值不全相同（{[r.get(X) for r in rows]}）')
norm = lambda e: re.sub(r'\s+', '', e or '')
cases = {norm(c.get('expression')): c for c in fx.get('cases', [])}
want = {
  'a': ('[物料.材料成本(小计)]', D('1.978941064')),
  'b': (f'[物料.{X}]', sx),
  'c': (f'[物料.{X}]*2+[物料.材料成本(小计)]', (sx * 2 + D('1.978941064')) if sx is not None else None),
  'd': ('[物料(总计)]', D(str(wl.get('tabTotal'))) if wl.get('tabTotal') not in (None, '', '…') else None),
  'e': (f'SUM([物料.{X}])', sx),
}
for k, (expr, ev) in want.items():
    c = cases.get(norm(expr))
    chk(c is not None, f'AC-14{k} 用例存在: {expr}（夹具 id={c.get("id") if c else None}）')
    if c is None: continue
    chk(ev is not None, f'AC-14{k} 可独立得出期望值（{ev}）')
    if ev is not None:
        chk(D(str(c.get('expected'))) == ev, f'AC-14{k} 夹具 expected={c.get("expected")} 与独立重算 {ev} 相等')
chk(wl.get('tabTotal') not in (None, '', '…'), f'物料 tabTotal 已给定（{wl.get("tabTotal")}）')
print('用例总数', len(fx.get('cases', [])), 'ids=', [c.get('id') for c in fx.get('cases', [])])
print('RESULT fails=%d' % fails)
