#!/bin/bash
# repair-260911 · S1 取证脚本（只读 SELECT，无任何写操作）
# 用法: evidence.sh <tag>   → 产物落 $EVDIR/<tag>.{ccv,qcv}.json 并打印摘要
set -euo pipefail
TAG="${1:-snap}"
EVDIR="${EVDIR:-/tmp/claude-1000/-home-joii-project-cpq/1f85d6f5-a0ee-457f-9ae5-9cc114a41830/scratchpad/ev}"
LI='b7066093-ebc9-4a78-b1e4-4ca47298b73e'
export PGPASSWORD=joii5231
mkdir -p "$EVDIR"
psql -h 10.177.152.12 -U postgres -d cpq_db_0910 -tAc \
  "select costing_card_values from quotation_line_item where id='$LI';" > "$EVDIR/$TAG.ccv.json"
psql -h 10.177.152.12 -U postgres -d cpq_db_0910 -tAc \
  "select quote_card_values from quotation_line_item where id='$LI';" > "$EVDIR/$TAG.qcv.json"
python3 - "$EVDIR" "$TAG" <<'PY'
import json,sys,hashlib
ev,tag=sys.argv[1],sys.argv[2]
def load(p):
    raw=open(p,'rb').read()
    return raw, json.loads(raw) if raw.strip() else None
for kind in ('ccv','qcv'):
    raw,d=load(f'{ev}/{tag}.{kind}.json')
    print(f'--- {kind} sha256={hashlib.sha256(raw).hexdigest()} bytes={len(raw)}')
    if not d: continue
    for t in d.get('tabs',[]):
        cols=t.get('subtotalByColumn')
        print(f"    tab={t.get('tabName'):<8} subtotal={t.get('subtotal')} byCol={cols}")
        if t.get('tabName')=='BOM':
            for rr,fr in zip(t.get('resolvedRows',[]),t.get('formulaResults',[])):
                pn=rr.get('料号') or rr.get('__nodeId')
                print(f"        料号={str(pn):<8} 组成用量={rr.get('组成用量')} 物料成本(fr)={fr.get('values',{})}")
PY
