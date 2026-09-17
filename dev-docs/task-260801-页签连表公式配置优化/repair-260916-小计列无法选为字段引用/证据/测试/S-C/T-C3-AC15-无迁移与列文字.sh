#!/usr/bin/env bash
# T-C3（AC-15 ①②，D-15 修订：本分支无迁移、存量不改写）
#   T-C3 before   # S-C 各步执行之前跑一次
#   T-C3 after    # S-C 全部步骤（含临时后端、导入、E2E）结束后再跑一次
# ① git：本分支相对分叉点无新增/修改迁移文件（含未跟踪文件）
# ② 一次性库 8 个 Excel 连表公式列：文字与问题说明 4.3 原文逐字一致，且 before/after 整列对象逐字相同；
#    另只读采样开发库 cpq_db_0724 同 8 列（合并前参照，合并后的复核由主线做）
source "$(dirname "$0")/common.sh"; guard_wt; guard_db
phase=${1:?before|after}; O="$S/out-C3"; mkdir -p "$O"; FAILS=0
exec > >(tee "$O/log-$phase.txt") 2>&1
echo "阶段=$phase 采样时刻=$(now) HEAD=$(git -C "$WT" rev-parse --short HEAD) 分叉点=$(git -C "$WT" merge-base master HEAD | cut -c1-8)"
echo "== ① 本分支无迁移文件 =="
d1=$(git -C "$WT" diff --name-only master...HEAD -- cpq-backend/src/main/resources/db/migration)
d2=$(git -C "$WT" diff --name-only HEAD -- cpq-backend/src/main/resources/db/migration)
d3=$(git -C "$WT" ls-files --others --exclude-standard -- cpq-backend/src/main/resources/db/migration)
echo "已提交差异: ${d1:-空}"; echo "工作区未提交改动: ${d2:-空}"; echo "未跟踪文件: ${d3:-空}"
[ -z "$d1" ] && pass "① git diff --name-only master...HEAD -- migration 为空" || fail "① 已提交差异非空"
[ -z "$d2$d3" ] && pass "① 工作区无未提交/未跟踪迁移文件" || fail "① 工作区存在迁移改动（未提交或未跟踪）"
# 阳性对照：同一命令对一个确有迁移差异的已知区间必须非空（证明命令本身能输出东西）
ctl=$(git -C "$WT" diff --name-only 88e53e65 ae6dae0f -- cpq-backend/src/main/resources/db/migration)
[ -n "$ctl" ] && echo "   阳性对照 88e53e65..ae6dae0f → $ctl（命令可输出）" || fail "① 阳性对照为空，判据不可信"
v=$(q "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+\$'"); echo "一次性库 flyway 最高版本=$v"
[ "$v" = 445 ] && pass "一次性库仍停在 V445（无本任务迁移落库）" || fail "一次性库 flyway 最高版本=$v（应 445）"
echo "== ② 8 列文字 =="
COLS="select json_build_object('code',c.code,'col',e) from component c, jsonb_array_elements(c.excel_columns) e where jsonb_typeof(c.excel_columns)='array' and e->>'source_type'='TAB_JOIN_FORMULA' order by c.code,e->>'col_key'"
q "$COLS" > "$O/tabjoin-cols-$phase.jsonl"
psql -h $DBHOST -U postgres -d cpq_db_0724 -At -c "$COLS" > "$O/tabjoin-cols-devdb-$phase-readonly.jsonl"
python3 - "$O/tabjoin-cols-$phase.jsonl" "$O/tabjoin-cols-before.jsonl" "$S/out-C0/tabjoin-cols-before.jsonl" "$O/tabjoin-cols-devdb-$phase-readonly.jsonl" <<'PY'
import json, sys, os
def load(p):
    return {(o['code'], o['col']['col_key']): o['col'] for o in map(json.loads, open(p, encoding='utf-8'))} if os.path.exists(p) else None
cur, before, rp0916c, dev = (load(p) for p in sys.argv[1:5])
EXP = {('COMP-0011','col_1'):'[物料.材料成本]',('COMP-0011','col_2'):'[物料.回收成本]',('COMP-0011','col_3'):'[产品小计(总计)]',
  ('COMP-2269','col_1'):'[材质元素.元素小计]',('COMP-2269','col_2'):'[小计(总计)]',
  ('COMP-2509','col_1'):'[材质元素(总计)]',('COMP-2509','col_2'):'[BOM(总计)]',('COMP-2509','col_3'):'[XJ(总计)]'}
bad = 0
def ck(ok, m):
    global bad; print(('✅ ' if ok else '❌ ') + m); bad += (not ok)
ck(len(cur) == 8, f'一次性库连表公式列数 = 8（实际 {len(cur)}，非空保护）')
ck({k: v['expression'] for k, v in cur.items()} == EXP, '一次性库 8 列文字 = 问题说明 4.3 原文（逐字）')
for k in sorted(cur): print(f'   {k[0]} {k[1]} {cur[k]["expression"]}')
ck(cur == rp0916c, '一次性库 8 列整列对象 = out-C0 基线（cpq_db_rp0916c 迁移前采样，与开发库同源）')
if before is not None and sys.argv[1] != sys.argv[2]:
    ck(cur == before, '② after 与 before 整列对象逐字相同（S-C 全过程未改写存量列）')
print('开发库 cpq_db_0724（只读参照）:', '与 4.3 原文一致' if dev and {k: v['expression'] for k, v in dev.items()} == EXP else f'不一致/缺失 {dev and {k: v["expression"] for k, v in dev.items()}}')
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] && pass "② 8 列文字不变" || fail "② 见上"
echo "RESULT FAILS=$FAILS"; [ $FAILS -eq 0 ]
