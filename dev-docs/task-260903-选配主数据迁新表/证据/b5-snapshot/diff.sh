#!/usr/bin/env bash
# B-5 快照 diff —— task-260903
# 用法: diff.sh <BEFORE目录> <AFTER目录>
# 判据: 任何一段 SQL 的 (列名/列序/行序/单元格值) 有差异 → 报出视图名+档位+客户+首个差异行列
set -uo pipefail
B="${1:?}"; A="${2:?}"
RED=0; SAME=0; NEWEMPTY=0; NEWERR=0; MISS=0

echo "=================== B-5 快照 DIFF ==================="
echo "BEFORE=$B  AFTER=$A"
echo
# 1) 并发写入守卫比对
echo "--- 守卫水位 ---"
echo "BEFORE_pre : $(cat "$B/guard_pre.txt")"
echo "BEFORE_post: $(cat "$B/guard_post.txt")"
echo "AFTER_pre  : $(cat "$A/guard_pre.txt")"
echo "AFTER_post : $(cat "$A/guard_post.txt")"
echo "⚠️ 上面四行的 5 个 max(created_at) 与 5 个 count 必须两两一致；不一致 = 期间有并发写入, 本轮 diff 作废"
echo

# 2) 逐段比对
while IFS=$'\t' read -r VNAME VID TIER CUS ST NC NR SHA; do
  [ "$VNAME" = "view_name" ] && continue
  KEY="${VNAME}__${VID:0:8}__${TIER}__${CUS}"
  BF="$B/data/$KEY.csv"; AF="$A/data/$KEY.csv"
  AROW=$(awk -F'\t' -v k="$KEY" 'NR>1{ if ($1"__"substr($2,1,8)"__"$3"__"$4==k) print $5"\t"$7 }' "$A/manifest.tsv")
  AST=$(cut -f1 <<<"$AROW"); ANR=$(cut -f2 <<<"$AROW")
  if [ -z "$AST" ]; then echo "❌ [缺失] $KEY —— AFTER 没有这一段（视图被删/改名？）"; MISS=$((MISS+1)); continue; fi
  if [ "$ST" = "ERROR" ] && [ "$AST" = "ERROR" ]; then SAME=$((SAME+1)); continue; fi
  if [ "$AST" = "ERROR" ]; then
    echo "❌ [改造后报错] $KEY —— BEFORE=$ST($NR行) AFTER=ERROR"
    echo "     $(head -c 300 "$A/data/$KEY.err" 2>/dev/null | tr '\n' ' ')"
    NEWERR=$((NEWERR+1)); RED=$((RED+1)); continue
  fi
  if [ "$ST" = "OK" ] && [ "$NR" -gt 0 ] && [ "$AST" = "EMPTY" ]; then
    echo "❌ [改造后变空] $KEY —— BEFORE=$NR 行, AFTER=0 行（典型症状: INNER JOIN 吞行 / 表名替换错）"
    NEWEMPTY=$((NEWEMPTY+1)); RED=$((RED+1)); continue
  fi
  if [ "$SHA" = "$(awk -F'\t' -v k="$KEY" 'NR>1{ if ($1"__"substr($2,1,8)"__"$3"__"$4==k) print $8 }' "$A/manifest.tsv")" ]; then
    SAME=$((SAME+1)); continue
  fi
  # sha 不同 → 定位首个差异
  RED=$((RED+1))
  echo "❌ [差异] $KEY  行数 $NR -> $ANR"
  # 列名比对
  BH=$(head -1 "$BF" 2>/dev/null); AH=$(head -1 "$AF" 2>/dev/null)
  if [ "$BH" != "$AH" ]; then
    echo "     ⚠️ 列名/列序变了:"
    echo "       BEFORE: $BH"
    echo "       AFTER : $AH"
  fi
  # 首个不同的行
  LN=$(diff <(cat "$BF") <(cat "$AF") 2>/dev/null | head -6)
  echo "     首处差异(diff 前 6 行):"
  sed 's/^/       /' <<<"$LN"
done < "$B/manifest.tsv"

echo
echo "=================== 结论 ==================="
echo "逐字相同 : $SAME"
echo "有差异   : $RED  (其中 改造后变空=$NEWEMPTY  改造后报错=$NEWERR  段缺失=$MISS)"
if [ "$RED" -eq 0 ] && [ "$MISS" -eq 0 ]; then echo "✅ B-AC-2 / B-AC-3 快照 diff 全绿"; exit 0; else echo "🚨 B-AC-2 快照 diff 红 —— 上面每一条都要有交代"; exit 1; fi
