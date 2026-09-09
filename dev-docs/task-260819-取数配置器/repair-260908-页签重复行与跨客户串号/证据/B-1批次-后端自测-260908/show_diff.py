import json,re,subprocess,urllib.request,os,sys,difflib
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({}))
env=dict(os.environ,PGPASSWORD='joii5231')
PG=['psql','-h','10.177.152.12','-U','postgres','-d','cpq_db_0724','-At']
jar={}
for l in open('cookie.txt'):
    p=l.split('\t')
    if len(p)==7: jar[p[5]]=p[6].strip()
hdr={'Content-Type':'application/json','Cookie':'; '.join(f'{k}={v}' for k,v in jar.items())}
for name in sys.argv[1:]:
    cid=subprocess.run(PG+['-c',f"SELECT component_id FROM component_sql_view WHERE sql_view_name='{name}'"],capture_output=True,text=True,env=env).stdout.strip()
    before=subprocess.run(PG+['-c',f"SELECT sql_template FROM component_sql_view WHERE sql_view_name='{name}'"],capture_output=True,text=True,env=env).stdout.rstrip('\n')
    cfg=subprocess.run(PG+['-c',f"SELECT builder_config FROM component_sql_view WHERE sql_view_name='{name}'"],capture_output=True,text=True,env=env).stdout.strip()
    req=urllib.request.Request(f'http://localhost:8098/api/cpq/components/{cid}/builder/compile',data=cfg.encode(),headers=hdr,method='POST')
    try: after=json.load(OPENER.open(req))['sql']
    except Exception as e:
        print(f'##### {name}: 编译失败 {e}');
        try: print(e.read().decode()[:800])
        except Exception: pass
        continue
    print(f'##### {name}')
    print('\n'.join(difflib.unified_diff(before.split('\n'),after.split('\n'),'before','after',lineterm='',n=1)))
    print()
