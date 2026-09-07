import json,subprocess,sys,os,urllib.request,http.cookiejar
OUT=sys.argv[1]
os.makedirs(OUT,exist_ok=True)
env=dict(os.environ, PGPASSWORD="joii5231")
q="""SELECT json_agg(json_build_object('code',c.code,'cid',v.component_id::text,'cfg',v.builder_config))
FROM component_sql_view v JOIN component c ON c.id=v.component_id
WHERE v.builder_version IS NOT NULL AND v.builder_config::text LIKE '%FUNC_ELEMENT_PRICE%';"""
raw=subprocess.run(["psql","-h","10.177.152.12","-U","postgres","-d","cpq_db_0724","-tA","-c",q],
                   capture_output=True,text=True,env=env).stdout
rows=json.loads(raw)
print("回归对象组件数 =",len(rows))
cj=http.cookiejar.CookieJar(); # CLAUDE.md 坑1：本机 shell 设了 http_proxy=127.0.0.1:7890，urllib 会照用 → 502。
# curl 靠 --noproxy '*' 规避；urllib 要显式塞一个空 ProxyHandler。
op=urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cj))
op.open(urllib.request.Request("http://localhost:8098/api/cpq/auth/login",
        data=json.dumps({"username":"admin","password":"Admin@2026"}).encode(),
        headers={"Content-Type":"application/json"}))
ok=err=0
for r in sorted(rows,key=lambda x:x['code']):
    body=json.dumps(r['cfg']).encode()
    req=urllib.request.Request(f"http://localhost:8098/api/cpq/components/{r['cid']}/builder/compile",
                               data=body, headers={"Content-Type":"application/json"})
    try:
        d=json.loads(op.open(req).read())
        open(f"{OUT}/{r['code']}.json","w").write(json.dumps(d,ensure_ascii=False,sort_keys=True,indent=1))
        ok+=1
    except Exception as e:
        try: detail=e.read().decode()[:300]
        except Exception: detail=str(e)[:300]
        open(f"{OUT}/{r['code']}.ERR","w").write(detail); err+=1
        print("  ⚠️",r['code'],detail[:150])
print(f"编译成功 {ok} / 失败 {err}")
