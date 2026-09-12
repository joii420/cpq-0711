#!/usr/bin/env python3
"""repair-260911 · AC-1（接口层，只读）+ AC-2 可执行性探测。
AC 原文见 问题说明.md ⑥。四列表达式均为 [页签(总计)]，同一卡片每行显示同一整页签总计（已知语义）。
期望值来源：costing_card_values 各页签 subtotal（实查，见 test-report 证据表）。
用法: ac_api_check.py <cookiejar> <port>   退出码 0=全绿
"""
import json, subprocess, sys
from decimal import Decimal
CJ, PORT = sys.argv[1], sys.argv[2]
BASE = f'http://localhost:{PORT}/api/cpq'
def get(u):
    return json.loads(subprocess.run(['curl','-s','--noproxy','*','-b',CJ,u],capture_output=True,text=True).stdout)

Q0010='405ab315-3ee6-43e0-8a4d-9837b762ab81'; T0010='ffae0668-0850-452a-8764-2d71e00d17af'
Q0009='ab00eb8f-a865-4fd9-9e09-17f4913df477'; T0009='c9a2afb4-291f-46f7-b837-6733b08d1591'
# lineItemId -> 卡片 -> {列标题: 期望}
EXPECT = {
 'b7066093-ebc9-4a78-b1e4-4ca47298b73e': ('S0001', {'元素小计':'489985','物料小计':'5438667.5','加工费':'5.8','单价':'5438673.3'}),
 '9c1dad08-93d1-45ff-89a9-4d7ba75bc759': ('S0004', {'元素小计':'632046','物料小计':'0','加工费':'3.8','单价':'3.8'}),
 'bf60ee2c-13db-4e2a-ba54-862e3d13e607': ('S0008', {'元素小计':'677615','物料小计':'0','加工费':'2.05','单价':'2.05'}),
 '2d442d03-161c-4a09-bd9f-d4f211681dd5': ('S0012', {'元素小计':'641025','物料小计':'0','加工费':'4.7','单价':'4.7'}),
}
fails=[]
d = get(f'{BASE}/quotations/{Q0010}/excel-view?templateId={T0010}')['data']
cols, rows = d.get('columns',[]), d.get('rows',[])
key = {c['title']: c['col_key'] for c in cols}
print(f'== AC-1 QT-20260911-0010 / 核价通用1  列={[c["title"] for c in cols]} 行数={len(rows)}')
assert cols, '列为空 —— 断言会空跑'
assert rows, '行为空 —— 断言会空跑'
seen=set()
for r in rows:
    li = r.get('_lineItemId'); seen.add(li)
    if li not in EXPECT: fails.append(f'意外行 lineItemId={li}'); continue
    card, want = EXPECT[li]
    for title, exp in want.items():
        ck = key.get(title)
        act = r.get(ck)
        ok = act is not None and Decimal(str(act)) == Decimal(exp)
        print(f'   {"✅" if ok else "❌"} {card} {title}({ck}) 期望 {exp} 实际 {act}')
        if not ok: fails.append(f'{card}.{title}: 期望 {exp} 实际 {act}')
missing = set(EXPECT) - seen
if missing: fails.append(f'缺卡片行: {missing}')

d9 = get(f'{BASE}/quotations/{Q0009}/excel-view?templateId={T0009}')['data']
print(f'\n== AC-2 QT-20260911-0009 / 核价模板 c9a2afb4  列={[c["title"] for c in d9.get("columns",[])]} 行数={len(d9.get("rows",[]))}')
if not d9.get('columns'):
    print('   ⚠️ 该核价模板未配置 Excel 组件 ⇒ 视图零列，AC-2 在当前数据下不可执行（不是修复失败）')

print(f'\n失败断言 {len(fails)} 条'); [print('  -',f) for f in fails]
sys.exit(1 if fails else 0)
