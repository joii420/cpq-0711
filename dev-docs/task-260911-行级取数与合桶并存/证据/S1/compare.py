#!/usr/bin/env python3
"""S1 · BEFORE/AFTER 逐字节对照器（AC-7 / AC-11）
用法: compare.py <证据目录>
判据:
  AC-7  普通组件(BOM/材质元素/加工费/自制加工费) 的 expand 结果必须逐字节相同 → 任一 md5 变化 = FAIL
  AC-11 两个 AC-11「产品」视图的渲染结果必须相同 → md5 变化 = FAIL(需人工看 diff 是否语义等价)
  另: 95 个不含行级维度列的 builder 视图 sql_template md5 必须不变
"""
import json,sys,os
E=sys.argv[1]
def load_tsv(p):
    d={}
    with open(p) as f:
        for i,l in enumerate(f):
            l=l.rstrip("\n")
            if not l: continue
            if l.startswith("fixture|"): continue
            parts=l.split("\t")
            d[parts[0]]=parts[1:]
    return d
fail=0; warn=0
# ---- 1. expand 结果对照 ----
b=load_tsv(f"{E}/BEFORE-expand-summary.tsv"); a=load_tsv(f"{E}/AFTER-expand-summary.tsv")
print("=== [1] expand 结果逐条对照（status / rowCount / driverPath / md5）===")
for k in sorted(set(b)|set(a)):
    if k not in b: print(f"  ⚠ 仅 AFTER 有: {k}"); warn+=1; continue
    if k not in a: print(f"  ⚠ 仅 BEFORE 有: {k}"); warn+=1; continue
    same = b[k]==a[k]
    is_ac7 = "AC-7" in k
    is_ac11 = "AC-11" in k
    tag = "AC-7 " if is_ac7 else ("AC-11" if is_ac11 else "参考 ")
    if same:
        print(f"  ✅ {tag} 一致 rows={b[k][1]} md5={b[k][3]}  {k}")
    else:
        lvl = "❌FAIL" if (is_ac7 or is_ac11) else "⚠参考项变化"
        if is_ac7 or is_ac11: fail+=1
        else: warn+=1
        print(f"  {lvl} {tag} BEFORE={b[k]}  AFTER={a[k]}  {k}")
# ---- 2. sql_template md5 对照 ----
print("\n=== [2] component_sql_view.sql_template md5 对照（98 builder 视图）===")
def load_v(p):
    d={}
    for l in open(p):
        c=l.rstrip("\n").split("\t")
        if len(c)>=5: d[c[0]]=(c[3],c[4])
    return d
vb=load_v(f"{E}/BEFORE-component_sql_view-md5.tsv"); va=load_v(f"{E}/AFTER-component_sql_view-md5.tsv")
changed=[k for k in vb if k in va and vb[k]!=va[k]]
EXPECT={"builder_221dc7668ab6","builder_7277969cc41c","builder_a71947b68d50"}
print(f"  变化的视图数 = {len(changed)}")
for k in sorted(changed):
    mark = "✅预期内" if k in EXPECT else "❌FAIL 非预期视图被改"
    if k not in EXPECT: fail+=1
    print(f"   {mark} {k}: len {vb[k][0]}→{va[k][0]}  md5 {vb[k][1]}→{va[k][1]}")
missing=[k for k in EXPECT if k in vb and vb[k]==va.get(k)]
for k in missing: print(f"   ⚠ 预期应变化但未变: {k}（视图可能没重编译 = 测到旧产物）"); warn+=1
print(f"\n=== 判定: FAIL={fail}  WARN={warn} ===")
sys.exit(1 if fail else 0)
