#!/usr/bin/env python3
# S1 只读基线采集器：AC-7（普通组件加法式）/ AC-11（两个产品视图所属模板渲染）
# 仅调只读端点 batch-expand；不写任何库
import json, hashlib, subprocess, sys, os

BASE = "http://localhost:8081"
CJ = sys.argv[1]
OUT = sys.argv[2]
TAG = sys.argv[3]          # BEFORE / AFTER
PORT_BASE = os.environ.get("CPQ_BASE", BASE)

FIX = [
 # (fixtureName, quotationId, quotationNo, customerId, [ (lineId, partNo, custPartNo) ], [ (compId, compName) ])
 ("F1-取值测试模板1/QT-20260907-0564",
  "a99bfc8d-2bd1-432a-af67-436c7160a09c","QT-20260907-0564","f6d10ef0-04cc-45f3-829c-568c8cce3adf",
  [("2a3ba7cb-9e98-4dda-af36-2e312e314e60","S0001","A002"),
   ("dcb8d49e-645b-45c3-aacc-566ca55dc60d","S0004","RW-A004"),
   ("a9cf4182-9479-412c-98de-b588ba0567ba","S0008","TC-B008"),
   ("28fc4e91-47f8-4b62-9ca0-9e5bc8208d0b","S0012","ZT-C012")],
  [("7277969c-c41c-47d3-b949-8fbf51d1a5fb","产品[AC-11视图]"),
   ("452bbdbe-13f7-4b74-9ed7-af13fac6716e","BOM[AC-7]"),
   ("dc3297cc-e9a3-4184-8bc8-bb61db2a07d2","材质元素[AC-7]")]),
 ("F2-取值测试模板2/QT-20260908-0624",
  "4ce0fcc4-a73b-4672-ba45-6387e03491ad","QT-20260908-0624","1f5818d8-b934-44be-b1c3-47df7053cff4",
  [("c7c3bb3a-a6c4-4cf5-86df-addd703f53fa","S0001","A002"),
   ("816be438-6542-43af-946a-32e0ac11ccab","S0001","A002"),
   ("f26959dd-9a5d-4e99-a7ba-446b82c41721","S0004","RW-A004"),
   ("e785fa95-a6f2-424e-82c2-b8f87a546412","S0004","RW-A004"),
   ("19b6dfd2-dd08-4c80-8a1e-ae7e2e5a11eb","S0008","TC-B008"),
   ("9f0dca27-a97f-406a-803d-992eefddfc88","S0008","TC-B008"),
   ("a440c90c-0787-4c77-aa26-9812516b2da4","S0012","ZT-C012"),
   ("bd61e120-a7e3-4d10-b1e1-adb486df36f8","S0012","ZT-C012")],
  [("a71947b6-8d50-4e04-8bf0-f2d4244b3cfe","产品[AC-11视图]"),
   ("eb834021-ed65-4fc9-a57f-6b1662279d5d","BOM[AC-7]"),
   ("ba81142a-5ddb-4987-898f-8912d07f1095","材质元素[AC-7]"),
   ("9cc11850-f425-45b7-8d4b-70705fde7dcf","加工费[AC-7]")]),
 ("F3-正泰测试模板1/QT-20260910-0816",
  "4ffa0460-2b13-4302-91f3-699cb0e539bd","QT-20260910-0816","1f5818d8-b934-44be-b1c3-47df7053cff4",
  [("aa040894-70bc-4211-8e7e-4084f34ee5d9","T260910D-P",""),
   ("174162eb-f534-42a2-b85c-31c3efa985b7","S0004",""),
   ("98083d41-9293-48e8-80f2-339bb3fbac1d","0028-2609000018","")],
  [("221dc766-8ab6-4d95-82c0-08cc03e6267d","产品[本任务改动目标]"),
   ("7f9a5bbf-264f-4b07-8dff-3118a9428a48","BOM[AC-7]"),
   ("98ee51d1-832e-4881-aa3d-e9512824cc14","材质元素[AC-7]"),
   ("4aa23fde-7ac9-4189-997c-cdfe00a6080f","加工费[AC-7]"),
   ("3fda805a-52da-4c9a-8741-51766ccf820f","自制加工费[AC-7]")]),
]

def call(task):
    body = json.dumps({"tasks":[task]})
    p = subprocess.run(["curl","-s","--noproxy","*","-b",CJ,"-X","POST",
        PORT_BASE+"/api/cpq/components/batch-expand","-H","Content-Type: application/json","-d",body],
        capture_output=True, text=True)
    return json.loads(p.stdout)

raw = {}
summary = []
for fname,qid,qno,cid,lines,comps in FIX:
    for lid,pno,cpn in lines:
        for compid,compname in comps:
            task = {"componentId":compid,"customerId":cid,"partNo":pno,
                    "lineItemId":lid,"quotationId":qid,"compositeType":"SIMPLE"}
            r = call(task)
            res = r.get("data",{}).get("results",[{}])[0]
            d = res.get("data") or {}
            canon = json.dumps(d, ensure_ascii=False, sort_keys=True, separators=(",",":"))
            h = hashlib.md5(canon.encode()).hexdigest()
            k = f"{fname}|line={lid[:8]}({pno}/{cpn or '∅'})|comp={compname}"
            raw[k] = {"request":task,"status":res.get("status"),"error":res.get("error"),"data":d}
            summary.append((k, res.get("status"), d.get("rowCount"), d.get("driverPath"), h))

with open(OUT+f"/{TAG}-expand-raw.json","w") as f:
    json.dump(raw, f, ensure_ascii=False, sort_keys=True, indent=1)
with open(OUT+f"/{TAG}-expand-summary.tsv","w") as f:
    f.write("fixture|line|component\tstatus\trowCount\tdriverPath\tmd5(canonical data)\n")
    for s in summary:
        f.write("\t".join(str(x) for x in s)+"\n")
for s in summary:
    print("\t".join(str(x) for x in s))
