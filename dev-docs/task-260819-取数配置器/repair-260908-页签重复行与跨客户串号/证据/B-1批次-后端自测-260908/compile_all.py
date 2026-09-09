import json, re, subprocess, urllib.request, os
# ⚠️ 本机 shell 有 http_proxy=127.0.0.1:7890，urllib 默认走它 → 访问 localhost 恒 502（CLAUDE.md 坑1）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PG=['psql','-h','10.177.152.12','-U','postgres','-d','cpq_db_0724','-At','-F','\t']
env=dict(os.environ, PGPASSWORD='joii5231')
rows=subprocess.run(PG+['-c',"SELECT sql_view_name, component_id::text FROM component_sql_view WHERE builder_config IS NOT NULL AND status='ACTIVE' ORDER BY component_id, sql_view_name"],capture_output=True,text=True,env=env).stdout.strip().split('\n')
cookie=open('cookie.txt').read()
sess=[l.split('\t')[-1] for l in cookie.splitlines() if 'SESSION' in l.upper() or 'session' in l]
# 直接从 netscape cookie 文件解析
jar={}
for l in cookie.splitlines():
    p=l.split('\t')
    if len(p)==7: jar[p[5]]=p[6]
hdr={'Content-Type':'application/json','Cookie':'; '.join(f'{k}={v}' for k,v in jar.items())}

CUST_LIT=re.compile(r' AND [A-Za-z_]\w*\.customer_no = (?::customerCode|[A-Za-z_]\w*\.customer_no)')
report=[]; out_all=[]
for line in rows:
    name, cid = line.split('\t')
    before=subprocess.run(PG+['-c',f"SELECT sql_template FROM component_sql_view WHERE sql_view_name='{name}'"],capture_output=True,text=True,env=env).stdout.rstrip('\n')
    cfg=subprocess.run(PG+['-c',f"SELECT builder_config FROM component_sql_view WHERE sql_view_name='{name}'"],capture_output=True,text=True,env=env).stdout.strip()
    req=urllib.request.Request(f'http://localhost:8098/api/cpq/components/{cid}/builder/compile',data=cfg.encode(),headers=hdr,method='POST')
    try:
        body=json.load(OPENER.open(req))
    except Exception as e:
        report.append((name,'COMPILE_ERROR',str(e)[:200])); continue
    after=body.get('sql','')
    out_all.append(f'{name}\n{after}\n---8<---')
    if after==before: report.append((name,'NO_CHANGE','')); continue
    # 🚨 必须**两边都剔**：部分视图 before 里本来就有 LEFT JOIN ... ON ... customer_no = :customerCode
    #    （task-260908 的查名边），只剔 after 会把它们误判成「其他差异」（首跑实测踩到，4 例全是误报）
    if CUST_LIT.sub('',after)==CUST_LIT.sub('',before):
        report.append((name,'仅客户谓词', f"+{len(CUST_LIT.findall(after))-len(CUST_LIT.findall(before))} 条谓词"))
    else: report.append((name,'⚠️其他差异','见 diff'))
        
open('28视图-预览后-编译产物全文.txt','w').write('\n'.join(out_all))
w=max(len(r[0]) for r in report)
print(f"{'视图':<{w}}  分类           备注")
for n,k,d in report: print(f"{n:<{w}}  {k:<12}  {d}")
from collections import Counter
print('\n汇总:', dict(Counter(k for _,k,_ in report)))
