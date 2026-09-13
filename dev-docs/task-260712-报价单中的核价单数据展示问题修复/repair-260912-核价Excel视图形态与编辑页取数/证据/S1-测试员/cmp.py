#!/usr/bin/env python3
"""AC-4 比对器：键集合一致 + 叶子数值等价（str/number 归一；数值须逐位相等）。"""
import json, sys
def norm(v):
    if isinstance(v, bool) or v is None: return ('raw', v)
    if isinstance(v, (int, float)): return ('num', float(v))
    if isinstance(v, str):
        try: return ('num', float(v.strip()))
        except ValueError: return ('raw', v.strip())
    return ('raw', v)
def walk(o, path, out):
    if isinstance(o, dict):
        out.append(('KEYS', path, tuple(sorted(o.keys()))))
        for k in sorted(o): walk(o[k], path+'/'+k, out)
    elif isinstance(o, list):
        out.append(('LEN', path, len(o)))
        for i,x in enumerate(o): walk(x, '%s[%d]'%(path,i), out)
    else:
        out.append(('LEAF', path, norm(o)))
def diff(a,b):
    ta,tb=[],[]; walk(a,'',ta); walk(b,'',tb)
    da={(k,p):v for k,p,v in ta}; db={(k,p):v for k,p,v in tb}
    return ['%s %s : before=%r after=%r'%(k[0],k[1],da.get(k,'<缺>'),db.get(k,'<缺>'))
            for k in sorted(set(da)|set(db),key=str) if da.get(k,'<缺>')!=db.get(k,'<缺>')]
if __name__=='__main__':
    d=diff(json.load(open(sys.argv[1])), json.load(open(sys.argv[2])))
    lab=sys.argv[3] if len(sys.argv)>3 else ''
    if d:
        print('DIFF(%d) %s'%(len(d),lab))
        for x in d[:40]: print('  ',x)
        sys.exit(1)
    print('SAME %s'%lab)
