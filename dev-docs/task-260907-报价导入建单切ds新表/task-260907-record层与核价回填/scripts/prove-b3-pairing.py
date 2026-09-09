#!/usr/bin/env python3
"""
task-260907 第二段 · P0-2 —— B-3 / D-35 落库的「静态成对性」证明（🚫 只读，不执行任何 DDL/DML）。

证明目标：**B-3 与 D-35 两条迁移落库后**，Registry 的期望列集与库的实际列集
          在「列名」与「列类型」两个维度上**双向零差**。

为什么必须双向：只查一个方向看不见「源文件删了产物没清」这类残留（V423 事故的形状）。
  · 期望 - 实际  → 启动报「缺列: x」
  · 实际 - 期望  → 启动报「多出未声明的列: x」
两者都会让 DatasetSchemaSelfCheck 直接抛异常、服务起不来。

实际侧 = information_schema 现状 ∪ 迁移 SQL 文本里解析出的 ADD COLUMN / CREATE TABLE
        （**解析文本，不执行**）。
"""
import re, subprocess, sys, collections

PSQL = ["psql", "-h", "10.177.152.12", "-U", "postgres", "-d", "cpq_db_0724", "-tAF|", "-c"]
ENV = {"PGPASSWORD": "joii5231", "PATH": "/usr/bin:/bin"}

def q(sql):
    r = subprocess.run(PSQL + [sql], capture_output=True, text=True, env=ENV, timeout=120)
    if r.returncode != 0:
        sys.exit("psql 失败: " + r.stderr[:400])
    return [l for l in r.stdout.strip().split("\n") if l]

# ── 实际侧①：information_schema 现状（归一成与 DatasetSchemaSelfCheck#normalize 同款写法）──
NORM = """
SELECT table_name, column_name,
  CASE data_type
    WHEN 'character varying' THEN 'varchar('||character_maximum_length||')'
    WHEN 'character'         THEN 'char('||character_maximum_length||')'
    WHEN 'numeric'           THEN 'numeric('||numeric_precision||','||numeric_scale||')'
    ELSE data_type END
FROM information_schema.columns
WHERE table_schema='public' AND (table_name LIKE 'ds\\_quote\\_%' OR table_name='ds_quote_record_stale')
"""
actual = collections.defaultdict(dict)
for line in q(NORM):
    t, c, ty = line.split("|")
    actual[t][c] = ty

# ── 实际侧②：迁移 SQL 文本（🚫 不执行）──
MIG = "cpq-backend/src/main/resources/db/migration-pending-260907/"
added = collections.defaultdict(dict)
sql_b3 = open(MIG + "V___task260907_ds_quote_source_quotation_id.sql", encoding="utf-8").read()
for t, c, ty in re.findall(r"^ALTER TABLE\s+(\S+)\s+ADD COLUMN\s+(\w+)\s+(\w+);", sql_b3, re.M):
    added[t][c] = ty
sql_d35 = open(MIG + "V___task260907_ds_quote_record_stale.sql", encoding="utf-8").read()
m = re.search(r"CREATE TABLE (ds_quote_record_stale) \((.*?)\n\);", sql_d35, re.S)
stale_cols = {}
if m:
    for ln in m.group(2).split("\n"):
        ln = ln.strip().rstrip(",")
        if not ln or ln.startswith("--"):
            continue
        parts = ln.split()
        stale_cols[parts[0]] = parts[1] if len(parts) > 1 else "-"

def post_migration(table):
    """落库后的实际列集 = 现状 ∪ 该迁移新增的列。"""
    d = dict(actual.get(table, {}))
    d.update(added.get(table, {}))
    return d

# ── 期望侧：Registry（DumpExpectedTyped 的输出）──
expected = {}
for line in open(sys.argv[1], encoding="utf-8"):
    kind, table, cols = line.rstrip("\n").split("\t")
    expected[table] = (kind, dict(x.split(":", 1) for x in cols.split(";")))

fail = 0
print("=" * 96)
print("① B-3 / _record：Registry 期望  ↔  (information_schema 现状 ∪ 迁移 ADD COLUMN)   双向差集")
print("=" * 96)
for table, (kind, exp) in expected.items():
    act = post_migration(table)
    if not act:
        print(f"❌ {kind:6} {table:44} 表不存在"); fail += 1; continue
    miss  = [c for c in exp if c not in act]                 # 期望有、库里没有 → 启动报「缺列」
    extra = [c for c in act if c not in exp]                 # 库里有、期望没有 → 启动报「多出未声明的列」
    tmis  = [f"{c}(期望{exp[c]}/实际{act[c]})" for c in exp
             if c in act and exp[c] != "-" and exp[c] != act[c]]
    ok = not (miss or extra or tmis)
    if not ok: fail += 1
    print(f"{'✅' if ok else '❌'} {kind:6} {table:44} 列{len(exp):3}  "
          f"缺={miss or '[]'}  多={extra or '[]'}  类型不符={tmis or '[]'}")

print()
print("=" * 96)
print("② D-35 标记表 ds_quote_record_stale：迁移建表 ↔ 库现状")
print("=" * 96)
cur = actual.get("ds_quote_record_stale", {})
print(f"   迁移将建列 {len(stale_cols)}: {list(stale_cols)}")
print(f"   库中现状   {len(cur)} 列  ⇒ {'尚未落库（预期）' if not cur else cur}")
print("   ⚠️ 该表**不进 DatasetSchemaSelfCheck**（不是 Registry 声明的数据集表），")
print("      落库前后都不会影响启动；DsRecordStaleService 有 information_schema 探针，表不在就整体 no-op。")

print()
print("=" * 96)
print(f"结论：{'✅ 双向零差 —— B-3 落库后 Registry 与库一致' if fail == 0 else f'❌ {fail} 张表不成对'}")
print("=" * 96)
sys.exit(1 if fail else 0)
