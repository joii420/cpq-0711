#!/usr/bin/env python3
"""task-260920 S-2 · AC-13 离线工具（只读本地导出文件，不连库）。
子命令：
  hash    <export.csv>                        按与 SQL ② 相同的口径从导出文件重算哈希（必须等于库内 ② 的 hash）
  tamper  <export.csv> <out.csv> [material]            阳性对照：复制导出件，把第一条 quote_adjusted 非空行 +1e-12 写到副本，再算哈希
  sample  <export.csv> [--seed N]             R3 抽样：从「quote_adjusted 非空」料号抽 200 + 从「全部值列为空」料号抽 20
  compare <before.csv> <after.csv> <sample.txt> <r3_start_utc>
                                              R3 判据②③：抽样料号逐行逐位相等（文本相等 = numeric 全位数相等）且 after 的 review_updated_at > r3_start
"""
import csv, hashlib, random, sys, datetime

VAL = ["quote_current", "quote_adjusted", "costing_current", "costing_adjusted", "diff_adjusted"]

def rows(p):
    with open(p, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))

def canon(r):
    return "|".join([r["material_no"], r["column_id"], r["budget_status"]] + [r[k] if r[k] != "" else "∅" for k in VAL])

def h(rs):
    # 行序 = 导出文件顺序（SQL 端 ORDER BY 与 ② 相同）；🚫 在 Python 里重排（排序规则与库 collation 不同）
    return hashlib.md5("\n".join(canon(r) for r in rs).encode("utf-8")).hexdigest()

def cmd_hash(p):
    rs = rows(p)
    print(f"rows={len(rs)} hash={h(rs)}")

def cmd_tamper(p, out, material=None):
    rs = rows(p)
    idx = next(i for i, r in enumerate(rs) if r["quote_adjusted"] != "" and (material is None or r["material_no"] == material))
    from decimal import Decimal
    old = rs[idx]["quote_adjusted"]
    rs[idx]["quote_adjusted"] = format(Decimal(old) + Decimal("0.000000000001"), "f")
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rs[0].keys())); w.writeheader(); w.writerows(rs)
    print(f"tampered row#{idx+1} material={rs[idx]['material_no']} quote_adjusted {old} -> {rs[idx]['quote_adjusted']}")
    print(f"original hash={h(rows(p))}")
    print(f"tampered hash={h(rows(out))}")

def cmd_sample(p, seed=20260920):
    rs = rows(p)
    by = {}
    for r in rs:
        by.setdefault(r["material_no"], []).append(r)
    valued = sorted(m for m, lst in by.items() if any(x["quote_adjusted"] != "" for x in lst))
    empty = sorted(m for m, lst in by.items() if all(all(x[k] == "" for k in VAL) for x in lst))
    rnd = random.Random(seed)
    s1 = rnd.sample(valued, min(200, len(valued)))
    s2 = rnd.sample(empty, min(20, len(empty)))
    print(f"# seed={seed} valued_pool={len(valued)} empty_pool={len(empty)} picked={len(s1)}+{len(s2)}", file=sys.stderr)
    for m in s1: print(f"VALUED\t{m}")
    for m in s2: print(f"EMPTY\t{m}")

def cmd_compare(before, after, sample, r3_start):
    b = {}; a = {}
    for r in rows(before): b.setdefault(r["material_no"], []).append(r)
    for r in rows(after): a.setdefault(r["material_no"], []).append(r)
    mats = [l.split("\t")[1].strip() for l in open(sample, encoding="utf-8") if "\t" in l]
    t0 = datetime.datetime.fromisoformat(r3_start.replace(" ", "T").replace("+00", "+00:00"))
    diff = 0; stale = 0; missing = 0; nonempty_cmp = 0
    for m in mats:
        if m not in b or m not in a:
            missing += 1; print(f"MISSING {m}"); continue
        kb = [canon(x) for x in b[m]]; ka = [canon(x) for x in a[m]]
        if kb != ka:
            diff += 1; print(f"DIFF {m}\n  before={kb}\n  after ={ka}")
        if any(x["quote_adjusted"] != "" for x in b[m]): nonempty_cmp += 1
        for x in a[m]:
            ts = datetime.datetime.fromisoformat(x["review_updated_at"].replace(" ", "T").replace("+00", "+00:00"))
            if ts <= t0:
                stale += 1; print(f"NOT-RECOMPUTED {m} updated_at={x['review_updated_at']}"); break
    print(f"sample={len(mats)} compared_with_value={nonempty_cmp} diff={diff} missing={missing} not_recomputed={stale}")
    sys.exit(0 if (diff == 0 and missing == 0 and stale == 0 and len(mats) > 0) else 1)

if __name__ == "__main__":
    c = sys.argv[1]
    if c == "hash": cmd_hash(sys.argv[2])
    elif c == "tamper": cmd_tamper(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
    elif c == "sample":
        seed = int(sys.argv[sys.argv.index("--seed")+1]) if "--seed" in sys.argv else 20260920
        cmd_sample(sys.argv[2], seed)
    elif c == "compare": cmd_compare(*sys.argv[2:6])
    else: print(__doc__); sys.exit(2)
