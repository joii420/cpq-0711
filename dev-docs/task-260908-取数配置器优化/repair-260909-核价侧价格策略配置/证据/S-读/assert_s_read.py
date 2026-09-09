#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S-读 片断言执行器 · repair-260909
用法:  PORT=8097 python3 assert_s_read.py            # B-3 落地后跑
       PORT=8081 python3 assert_s_read.py --pre      # B-3 落地前跑（记录阳性对照/基准态）

只读：只发 GET field-tree / POST builder/compile / POST builder/preview。
compile 与 preview 均不落库（compile 已于 2026-09-09 实测验证：跑完 33 行
component_sql_view.sql_template md5 逐行不变）。

设计原则（每条断言都必须有可能为假）：
  * 先断言 HTTP 200 / 响应体含 'groups' —— 否则 404 体解析出的「0 个 PRICE 组」是假绿
  * 先断言样本非空（rowCount > 0）—— 否则「单价为空」在 0 行上恒真
  * AC-P1 断言 == 1，不是 >= 1
"""
import json, os, sys, urllib.parse, subprocess, hashlib, tempfile

PORT = os.environ.get("PORT", "8097")
PRE = "--pre" in sys.argv
BASEURL = "http://localhost:%s/api/cpq" % PORT
HERE = os.path.dirname(os.path.abspath(__file__))
BASELINE = os.path.join(HERE, "baseline")
OUT = os.path.join(HERE, "pre" if PRE else "after")
os.makedirs(OUT, exist_ok=True)
# 🚫 cookiejar 不落证据目录 —— 里面是 admin 的活会话 cookie，证据目录会进 git
JAR = os.path.join(tempfile.gettempdir(), "repair260909-s-read-cookiejar")

# 报价侧材质元素组件（已配价格列），compile/preview 都用它 + 覆盖 dialect/tabType
CID_QUOTE = "cb4c1af4-eabf-469e-bfbd-706ef2d33902"

RESULTS = []


def rec(ac, ok, detail):
    RESULTS.append((ac, ok, detail))
    print("[%s] %-7s %s" % ("PASS" if ok is True else ("FAIL" if ok is False else "N/A "), ac, detail))


def curl(args):
    p = subprocess.run(["curl", "-s", "--noproxy", "*", "-b", JAR, "-w", "\n%{http_code}"] + args,
                       capture_output=True, text=True)
    body, _, code = p.stdout.rpartition("\n")
    return code.strip(), body


def login():
    p = subprocess.run(["curl", "-s", "--noproxy", "*", "-c", JAR, "-o", "/dev/null",
                        "-w", "%{http_code}", "-X", "POST", BASEURL + "/auth/login",
                        "-H", "Content-Type: application/json",
                        "-d", '{"username":"admin","password":"Admin@2026"}'],
                       capture_output=True, text=True)
    if p.stdout.strip() != "200":
        print("!!! LOGIN FAILED http=%s on port %s — ABORT" % (p.stdout.strip(), PORT))
        sys.exit(1)
    print("login ok on port %s\n" % PORT)


def field_tree(dialect, tab, variant=None):
    url = "%s/config/semantic-graph/field-tree?dialect=%s&tabType=%s" % (
        BASEURL, dialect, urllib.parse.quote(tab))
    if variant:
        url += "&variantKey=" + variant
    code, body = curl([url])
    name = "fieldtree-%s-%s%s.json" % (dialect, tab, "-" + variant if variant else "")
    open(os.path.join(OUT, name), "w", encoding="utf-8").write(body)
    try:
        return code, json.loads(body)
    except Exception:
        return code, None


def price_groups(o):
    """返回 (是否可判定, PRICE 组列表)。响应体缺 groups 键 => 不可判定（不是 0 个）。"""
    if not isinstance(o, dict) or "groups" not in o or o["groups"] is None:
        return False, []
    return True, [g for g in o["groups"] if g.get("groupKind") == "PRICE"]


# ---------------------------------------------------------------- AC-P1
def ac_p1():
    for d in ("COST_BASIC", "COST_DETAIL"):
        code, o = field_tree(d, "材质元素")
        if code != "200":
            rec("AC-P1", False, "%s HTTP=%s（请求未打通，不可判定为「无组」）" % (d, code)); continue
        ok, pg = price_groups(o)
        if not ok:
            rec("AC-P1", False, "%s 响应体缺 groups 键 code=%s —— 假绿拦截" % (d, o.get("code"))); continue
        if len(pg) != 1:
            rec("AC-P1", False, "%s PRICE 组数=%d，要求恰好 1（组名=%s）" % (d, len(pg), [g.get("groupName") for g in pg])); continue
        if pg[0].get("groupName") != "价格策略":
            rec("AC-P1", False, "%s 组名=%r，要求「价格策略」" % (d, pg[0].get("groupName"))); continue
        kinds = [g.get("groupKind") for g in o["groups"]]
        if "MAIN" not in kinds:
            rec("AC-P1", False, "%s PRICE 组在但 MAIN 组不见了 kinds=%s" % (d, kinds)); continue
        flds = [(f.get("sourceColumn"), f.get("displayName")) for f in pg[0].get("fields") or []]
        rec("AC-P1", True, "%s PRICE 组恰好 1 个，组名=价格策略，字段=%s，全部分组 kinds=%s" % (d, flds, kinds))


# ---------------------------------------------------------------- AC-N1
N1_TARGETS = [("BOM", None), ("主件", None), ("费用类", "PROCESS_ASSEMBLY_FEE")]


def ac_n1():
    for d in ("COST_BASIC", "COST_DETAIL"):
        for tab, vk in N1_TARGETS:
            code, o = field_tree(d, tab, vk)
            label = "%s/%s%s" % (d, tab, "/" + vk if vk else "")
            if code != "200":
                rec("AC-N1", False, "%s HTTP=%s —— 请求没打通，0 个 PRICE 组不算证据（费用类须带 variantKey）" % (label, code)); continue
            ok, pg = price_groups(o)
            if not ok:
                rec("AC-N1", False, "%s 响应体缺 groups 键 code=%s" % (label, o.get("code"))); continue
            if pg:
                rec("AC-N1", False, "%s 出现了 %d 个 PRICE 组：%s" % (label, len(pg), [g.get("groupName") for g in pg])); continue
            kinds = [g.get("groupKind") for g in o["groups"]]
            rec("AC-N1", True, "%s HTTP=200，PRICE 组 0 个（其余分组 kinds=%s，证明请求确实打通）" % (label, kinds))


# ---------------------------------------------------------------- AC-N2
def ac_n2():
    """选了 FUNC_ELEMENT_PRICE 的列，但锚点(tabType=BOM)没有 PRICE 边 => COMPILE_PRICE_EDGE_NOT_FOUND。
    阳性对照（2026-09-09 于 8081 改动前实测）：
        QUOTE/BOM       -> 400 COMPILE_PRICE_EDGE_NOT_FOUND   <= 守卫确实会响
        COST_*/BOM      -> 400 COMPILE_COLUMN_SOURCE_UNKNOWN  <= 节点当时还不存在
    B-3 落地后 COST_*/BOM 应从 COLUMN_SOURCE_UNKNOWN 翻成 PRICE_EDGE_NOT_FOUND，
    这个「错误码翻转」本身就是 B-3 真的生效的判据。"""
    ctrl = os.path.join(HERE, "ac-n2-positive-control")
    for d in ("QUOTE", "COST_BASIC", "COST_DETAIL"):
        probe = os.path.join(ctrl, "probe-%s-BOM.json" % d)
        if not os.path.exists(probe):
            rec("AC-N2", None, "%s 缺 probe 文件 %s" % (d, probe)); continue
        code, body = curl(["-X", "POST", "%s/components/%s/builder/compile" % (BASEURL, CID_QUOTE),
                           "-H", "Content-Type: application/json", "--data-binary", "@" + probe])
        open(os.path.join(OUT, "n2-%s-BOM.json" % d), "w", encoding="utf-8").write(body)
        try:
            o = json.loads(body)
        except Exception:
            rec("AC-N2", False, "%s 响应非 JSON http=%s" % (d, code)); continue
        errcode = o.get("code")
        if d == "QUOTE":
            ok = (code == "400" and errcode == "COMPILE_PRICE_EDGE_NOT_FOUND")
            rec("AC-N2", ok, "QUOTE/BOM（阳性对照，守卫必须响）http=%s code=%s" % (code, errcode))
        else:
            if PRE:
                rec("AC-N2", None, "%s/BOM 改动前 http=%s code=%s（B-3 前节点不存在，记录用）" % (d, code, errcode))
            else:
                ok = (code == "400" and errcode == "COMPILE_PRICE_EDGE_NOT_FOUND")
                extra = "" if ok else "  <-- 仍是 %s 说明 B-3 的节点没进这个方言" % errcode
                rec("AC-N2", ok, "%s/BOM http=%s code=%s%s" % (d, code, errcode, extra))


# ---------------------------------------------------------------- AC-R2
def ac_r2():
    code, o = field_tree("QUOTE", "材质元素")
    if code != "200":
        rec("AC-R2", False, "QUOTE HTTP=%s" % code); return
    ok, pg = price_groups(o)
    if not ok:
        rec("AC-R2", False, "QUOTE 响应体缺 groups 键 code=%s" % o.get("code")); return
    if len(pg) != 1:
        rec("AC-R2", False, "QUOTE PRICE 组数=%d，要求恰好 1（B-23 修过「两个价格策略组」）" % len(pg)); return
    canon = json.dumps(o["groups"], ensure_ascii=False, sort_keys=True, indent=1) + "\n"
    cur_md5 = hashlib.md5(canon.encode()).hexdigest()
    base_f = os.path.join(BASELINE, "groups-canon-BEFORE-QUOTE-材质元素.json")
    open(os.path.join(OUT, "groups-canon-QUOTE-材质元素.json"), "w", encoding="utf-8").write(canon)
    if not os.path.exists(base_f):
        rec("AC-R2", None, "基准文件缺失 %s" % base_f); return
    base = open(base_f, encoding="utf-8").read()
    base_md5 = hashlib.md5(base.encode()).hexdigest()
    if len(base) < 50:
        rec("AC-R2", False, "基准文件只有 %d 字节 —— 空文件比对是假绿，拒绝判定" % len(base)); return
    if base == canon:
        rec("AC-R2", True, "报价侧 groups 逐字节不变 md5=%s（基准 %d 字节，PRICE 组=1）" % (cur_md5, len(base)))
    else:
        d = os.path.join(OUT, "R2-DIFF.txt")
        subprocess.run("diff -u %r %r > %r" % (base_f, os.path.join(OUT, "groups-canon-QUOTE-材质元素.json"), d), shell=True)
        rec("AC-R2", False, "报价侧 groups 变了 base_md5=%s cur_md5=%s，diff -> %s" % (base_md5, cur_md5, d))


# ---------------------------------------------------------------- AC-B1
B1_SAMPLES = ["300013", "300015"]          # 只在 COST_BASIC 有行且桥不到
B1_CUSTOMER = os.environ.get("B1_CUSTOMER", "CUST-0001")


def ac_b1(cost_component_id=None, price_field_name="元素单价"):
    """桥不到的生产料号 -> 单价为空，且不报错。
    ⚠️ 顺序不能反：先断言 rowCount > 0，否则「单价为空」在 0 行上恒真。
    ⚠️ 本函数只能证明「接口返回空值且不报错」。AC 原文里的「不显示『加载中…』」
       是前端 DATA_SOURCE 渲染层性质（AP-31/AP-38 族），必须用 UI 验，
       在报告里单列，🚫 不许用本函数的 PASS 顶替。"""
    if not cost_component_id:
        rec("AC-B1", None, "缺核价侧材质元素组件 id（须 B-2/B-3 落地后由主线指定），未执行")
        return
    cfg_f = os.environ.get("B1_CONFIG")
    if not cfg_f or not os.path.exists(cfg_f):
        rec("AC-B1", None, "缺 B1_CONFIG（核价侧含价格列的 builder_config），未执行")
        return
    cfg = json.load(open(cfg_f, encoding="utf-8"))
    for pn in B1_SAMPLES:
        body_obj = dict(cfg); body_obj["customerCode"] = B1_CUSTOMER; body_obj["partNo"] = pn
        tmp = os.path.join(OUT, "b1-req-%s.json" % pn)
        json.dump(body_obj, open(tmp, "w", encoding="utf-8"), ensure_ascii=False)
        code, body = curl(["-X", "POST", "%s/components/%s/builder/preview" % (BASEURL, cost_component_id),
                           "-H", "Content-Type: application/json", "--data-binary", "@" + tmp])
        open(os.path.join(OUT, "b1-resp-%s.json" % pn), "w", encoding="utf-8").write(body)
        if code != "200":
            rec("AC-B1", False, "COST_BASIC/%s preview HTTP=%s（AC 要求不报错）body=%s" % (pn, code, body[:200])); continue
        o = json.loads(body)
        n = o.get("rowCount")
        if not n:
            rec("AC-B1", False, "COST_BASIC/%s rowCount=%s —— 0 行则「单价为空」恒真，是空跑，不算通过" % (pn, n)); continue
        vals = [r.get(price_field_name) for r in o.get("rows") or []]
        empties = [v for v in vals if v is None or v == "" or v == "—"]
        if len(empties) == len(vals):
            rec("AC-B1", True, "COST_BASIC/%s rowCount=%d（非空已断言），单价列 %d/%d 全为空值 %r，HTTP 200 未报错"
                % (pn, n, len(empties), len(vals), sorted(set(map(str, vals)))))
        else:
            rec("AC-B1", False, "COST_BASIC/%s 单价列出现非空值 %r（桥不到却取到了价，需查桥接是否串号）" % (pn, vals))
    rec("AC-B1", None, "COST_DETAIL 侧：无样本·未验证 —— 其元素BOM里唯一料号 3120014539 桥得通，"
                       "构造不出本 AC 的场景（AC 原文 2026-09-09 已订正，🚫 不许打勾）")


def main():
    login()
    print("=" * 78)
    ac_p1(); print()
    ac_n1(); print()
    ac_n2(); print()
    ac_r2(); print()
    ac_b1(os.environ.get("B1_COMPONENT_ID"))
    print("=" * 78)
    p = sum(1 for _, o, _ in RESULTS if o is True)
    f = sum(1 for _, o, _ in RESULTS if o is False)
    n = sum(1 for _, o, _ in RESULTS if o is None)
    print("PASS=%d  FAIL=%d  未验证/不可判定=%d   产物目录: %s" % (p, f, n, OUT))
    for ac, o, d in RESULTS:
        if o is False:
            print("  FAIL  %s  %s" % (ac, d))
        if o is None:
            print("  N/A   %s  %s" % (ac, d))
    sys.exit(1 if f else 0)


if __name__ == "__main__":
    main()
