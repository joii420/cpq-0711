#!/usr/bin/env bash
# B-6b: 端到端渲染耗时 + N+1 代理指标 (B-AC-7 / B-AC-8)
# 用法: perf_e2e.sh <outdir>
# 🚨 必须与 perf_sql.sh 串行, 否则互相污染计时
# 靶子: 1845 行报价单 (库里仅 5 张 >=100 行, 全部 1845 行)
# B-AC-8 判据: 用 pg_stat_user_tables 的 (seq_scan+idx_scan) 增量当 SQL 条数代理 ——
#   pg_stat_statements 未安装(shared_preload_libraries 为空), 装它要改共享环境全局配置 = §3.2 红线, 🚫 不做。
#   扫描次数与「按行循环查询」同增, 是 N+1 的有效代理: 常数条 => 增量与行数无关。
set -uo pipefail
OUT="${1:?}"; mkdir -p "$OUT"
QID=6441b4d2-96da-493e-9085-0daca297b244   # QT-20260901-0233, 1845 行
BASE=http://localhost:8081/api/cpq
C="$OUT/cookies.txt"
PGHOST=10.177.152.12; PGUSER=postgres; PGDB=cpq_db_0724
export PGPASSWORD=joii5231
PSQL="psql -h $PGHOST -U $PGUSER -d $PGDB -tAX"

curl -s --noproxy '*' -c "$C" -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@2026"}' -o /dev/null -w 'login=%{http_code}\n' | tee "$OUT/login.txt"
# 阳性对照: cookie 拿到 != cookie 生效
ME=$(curl -s --noproxy '*' -b "$C" "$BASE/auth/me")
echo "$ME" | grep -q '"role":"SYSTEM_ADMIN"' || { echo "🚨 会话未生效, 本轮测量作废: $ME"; exit 2; }

snap_v6 () { $PSQL -tAX -c "SELECT (SELECT max(created_at)::text FROM material_master)||'|'||(SELECT max(created_at)::text FROM material_bom_item)||'|'||(SELECT max(created_at)::text FROM element_bom_item)||'|'||(SELECT count(*)::text FROM material_bom_item)"; }
snap_act() { $PSQL -tAX -c "SELECT count(*) FROM pg_stat_activity WHERE datname='$PGDB' AND state<>'idle'"; }
snap_scan(){ $PSQL -tAX -c "SELECT relname||'='||(coalesce(seq_scan,0)+coalesce(idx_scan,0)) FROM pg_stat_user_tables WHERE relname IN ('material_master','material_bom','material_bom_item','element_bom','element_bom_item','material_recipe','component_sql_view','quotation_line_item') ORDER BY relname" | paste -sd' '; }

echo "V6_PRE : $(snap_v6)"  | tee    "$OUT/guard.txt"
echo "ACT_PRE: $(snap_act)" | tee -a "$OUT/guard.txt"

# 预热(丢弃) —— 首次含 JIT/缓存冷启动, 计进去会把 BEFORE 抬高、制造虚假的"AFTER 更快"
curl -s --noproxy '*' -b "$C" -o /dev/null --max-time 300 "$BASE/quotations/$QID"
echo "--- 预热完成 ---"

printf 'sample\thttp\ttime_s\tbytes\tactive_conn\n' > "$OUT/samples.tsv"
for i in 1 2 3 4 5 6 7; do
  A=$(snap_act)
  R=$(curl -s --noproxy '*' -b "$C" -o /dev/null --max-time 300 -w '%{http_code}\t%{time_total}\t%{size_download}' "$BASE/quotations/$QID")
  printf '%d\t%s\t%s\n' "$i" "$R" "$A" >> "$OUT/samples.tsv"
  echo "  sample $i: $R (active=$A)"
done

# N+1 代理: 单次请求前后的扫描次数增量
S1=$(snap_scan)
curl -s --noproxy '*' -b "$C" -o /dev/null --max-time 300 "$BASE/quotations/$QID"
S2=$(snap_scan)
{ echo "SCAN_PRE : $S1"; echo "SCAN_POST: $S2"; } > "$OUT/scan_delta.txt"
python3 - "$OUT/scan_delta.txt" >> "$OUT/scan_delta.txt" <<'EOF'
import sys,re
L=open(sys.argv[1],encoding='utf-8').read().strip().split('\n')
def d(s): return dict(kv.split('=') for kv in s.split(': ',1)[1].split())
a,b=d(L[0]),d(L[1])
print("DELTA(单次渲染的表扫描次数增量):")
for k in sorted(a): print(f"  {k:24s} {int(b[k])-int(a[k]):>8d}")
EOF

echo "V6_POST : $(snap_v6)"  | tee -a "$OUT/guard.txt"
echo "ACT_POST: $(snap_act)" | tee -a "$OUT/guard.txt"

echo "=== 汇总 (中位/最小/最大 秒) ==="
awk -F'\t' 'NR>1{t[NR-1]=$3} END{n=asort(t); printf "median=%.3f min=%.3f max=%.3f n=%d\n", t[int((n+1)/2)], t[1], t[n], n}' "$OUT/samples.tsv" 2>/dev/null \
 || awk -F'\t' 'NR>1{print $3}' "$OUT/samples.tsv" | sort -g | awk '{a[NR]=$1} END{printf "median=%.3f min=%.3f max=%.3f n=%d\n", a[int((NR+1)/2)], a[1], a[NR], NR}'
cat "$OUT/scan_delta.txt" | tail -12
