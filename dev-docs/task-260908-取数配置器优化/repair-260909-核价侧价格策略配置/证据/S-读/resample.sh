#!/usr/bin/env bash
# S-读 片 · 重采脚本（BEFORE/AFTER 通用）
# 用法: PORT=8081 PHASE=BEFORE ./resample.sh
#       PORT=8097 PHASE=AFTER  ./resample.sh
# 🚫 只读：只打 GET field-tree / POST builder/compile（compile 不落库，已实测验证）
set -u
PORT="${PORT:-8081}"
PHASE="${PHASE:-AFTER}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/$( [ "$PHASE" = BEFORE ] && echo baseline || echo after )"
mkdir -p "$OUT"
JAR="$(mktemp)"
BASEURL="http://localhost:$PORT/api/cpq"

log(){ printf '%s\n' "$*"; }

log "### login ($BASEURL)"
code=$(curl -s --noproxy '*' -o /dev/null -c "$JAR" -w '%{http_code}' \
  -X POST "$BASEURL/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@2026"}')
log "login http=$code"
[ "$code" = 200 ] || { log "!!! LOGIN FAILED - ABORT"; exit 1; }

enc(){ python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }

# ---------- field-tree ----------
ft(){ # $1=dialect $2=tabType $3=variantKey(optional) $4=file-suffix
  local d="$1" t="$2" vk="${3:-}" sfx="${4:-}"
  local url="$BASEURL/config/semantic-graph/field-tree?dialect=$d&tabType=$(enc "$t")"
  [ -n "$vk" ] && url="$url&variantKey=$vk"
  local f="$OUT/fieldtree-$PHASE-$d-$t$sfx.json"
  local c
  c=$(curl -s --noproxy '*' -b "$JAR" -o "$f" -w '%{http_code}' "$url")
  python3 - "$f" "$c" "$d" "$t$sfx" <<'PY'
import json,sys
f,c,d,t=sys.argv[1:5]
try: o=json.load(open(f,encoding='utf-8'))
except Exception as e: print("%-12s %-28s http=%s PARSE-ERR %s"%(d,t,c,e)); sys.exit()
if 'groups' not in o:
    print("%-12s %-28s http=%s  NO-GROUPS-KEY code=%s  <-- 请求没打通,不算证据"%(d,t,c,o.get('code'))); sys.exit()
gs=o['groups']; kinds=[g.get('groupKind') for g in gs]
print("%-12s %-28s http=%s groups=%d kinds=%s PRICE_n=%d"%(d,t,c,len(gs),kinds,kinds.count('PRICE')))
PY
}

log ""
log "### field-tree · 材质元素（AC-P1 / AC-R2）"
for D in QUOTE COST_BASIC COST_DETAIL; do ft "$D" 材质元素; done

log ""
log "### field-tree · 非材质元素页签（AC-N1）"
log "    注意 费用类 必须带 variantKey，裸请求返 404 -> groups 缺省 -> 假绿"
for D in QUOTE COST_BASIC COST_DETAIL; do
  ft "$D" BOM
  ft "$D" 主件
  ft "$D" 费用类 PROCESS_ASSEMBLY_FEE -PROCESS_ASSEMBLY_FEE
  ft "$D" 费用类 INCOMING_OTHER_FEE  -INCOMING_OTHER_FEE
done

# ---------- 归一化 groups（AC-R2 逐字比对用） ----------
log ""
log "### 归一化 groups（sort_keys，供 diff / md5）"
python3 - "$OUT" "$PHASE" <<'PY'
import json,sys,os,hashlib
out,phase=sys.argv[1],sys.argv[2]
for d in ('QUOTE','COST_BASIC','COST_DETAIL'):
    src=os.path.join(out,'fieldtree-%s-%s-材质元素.json'%(phase,d))
    if not os.path.exists(src): continue
    o=json.load(open(src,encoding='utf-8'))
    if 'groups' not in o: print(d,'skipped (no groups key)'); continue
    canon=json.dumps(o['groups'],ensure_ascii=False,sort_keys=True,indent=1)
    dst=os.path.join(out,'groups-canon-%s-%s-材质元素.json'%(phase,d))
    open(dst,'w',encoding='utf-8').write(canon+'\n')
    print('%-12s canon bytes=%5d md5=%s'%(d,len(canon),hashlib.md5(canon.encode()).hexdigest()))
PY

# ---------- AC-N2 阳性对照：选 FUNCTION 列但锚点无 PRICE 边 ----------
# componentId 为报价侧材质元素组件（compile 不落库）
CID="${CID:-cb4c1af4-eabf-469e-bfbd-706ef2d33902}"
log ""
log "### AC-N2 阳性对照 · componentId=$CID · tabType=BOM（该锚点无 PRICE 边）"
for D in QUOTE COST_BASIC COST_DETAIL; do
  P="$HERE/ac-n2-positive-control/probe-$D-BOM.json"
  [ -f "$P" ] || { log "missing probe $P"; continue; }
  f="$OUT/n2-$PHASE-$D-BOM.json"
  c=$(curl -s --noproxy '*' -b "$JAR" -o "$f" -w '%{http_code}' \
      -X POST "$BASEURL/components/$CID/builder/compile" \
      -H 'Content-Type: application/json' --data-binary @"$P")
  python3 -c "
import json,sys
d=json.load(open('$f',encoding='utf-8'))
print('%-12s http=$c code=%s'%('$D',d.get('code','<200 OK, sql-len=%d>'%len(d.get('sql') or ''))))"
done

# ---------- 空文件哨兵 ----------
log ""
log "### 空文件哨兵（任何 <50 字节的产物都不作为证据）"
bad=0
for f in "$OUT"/*; do
  [ -f "$f" ] || continue
  case "$f" in *MANIFEST*) continue;; esac
  sz=$(wc -c <"$f")
  [ "$sz" -lt 50 ] && { log "!!! TOO SMALL: $(basename "$f") ($sz)"; bad=1; }
done
[ $bad -eq 0 ] && log "ALL OUTPUTS >=50 bytes - PASS" || log "!!! 有空产物，本轮不算证据"

( cd "$OUT" && md5sum * 2>/dev/null | sort -k2 > "MANIFEST-$PHASE.md5" )
log ""
log "manifest -> $OUT/MANIFEST-$PHASE.md5"
rm -f "$JAR"
