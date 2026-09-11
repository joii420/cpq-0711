#!/bin/bash
# AC-13 三态证伪检查器（task-260911 · S1）
#   继承 repair-260910 的 ac6_check.sh 判据，泛化到本任务的「集合成员谓词」形态。
#   用法: ac13_check.sh <sql文件> [占位符名, 默认 customerProductNos]
#   三态：谓词缺失 → FAIL(MISSING) / 谓词误入 WHERE → FAIL(IN_WHERE) / 谓词在 LEFT JOIN..ON → PASS
F="$1"; PH="${2:-customerProductNos}"
G=/usr/bin/grep
echo "########## AC-13 原始证据 · 文件=$F · 占位符=:$PH ##########"
echo "----- [1] 完整 sql_template（带行号，$G -an 原始输出） -----"
$G -an '' "$F"
echo
echo "----- [2] 含 :$PH 的所有行 -----"
$G -an ":$PH" "$F" || echo "(无匹配)"
echo
echo "----- [3] 含 customer_product_no 的所有行 -----"
$G -an 'customer_product_no' "$F" || echo "(无匹配)"
echo

# 按 UNION 切块，逐块分 JOIN 段 / WHERE 段（BOM 类视图有 UNION ALL 两块）
TMPD=$(mktemp -d)
awk -v d="$TMPD" 'BEGIN{n=0}{ u=toupper($0); gsub(/^[ \t]+|[ \t]+$/,"",u); if(u=="UNION"||u=="UNION ALL"){n++;next} print > (d "/blk_" n) }' "$F"
echo "----- [0] UNION 分块数 = $(ls "$TMPD" | wc -l) -----"

IN_ON=0; IN_WHERE=0; PRESENT=0
$G -aq ":$PH" "$F" && PRESENT=1
for b in "$TMPD"/blk_*; do
  [ -f "$b" ] || continue
  JOINSEG=$(/usr/bin/sed -n '/^[[:space:]]*FROM /,$p' "$b" | /usr/bin/sed '/^[[:space:]]*WHERE /,$d')
  WHERESEG=$(/usr/bin/sed -n '/^[[:space:]]*WHERE /,$p' "$b" | /usr/bin/sed '/^[[:space:]]*ORDER BY/,$d')
  echo "----- [4] 块 $(basename $b) · JOIN 段（FROM..WHERE 之间） -----"; echo "$JOINSEG"
  echo "--- JOIN 段内 :$PH 匹配 ---"
  echo "$JOINSEG" | $G -an ":$PH" || echo "(JOIN 段无匹配)"
  echo "$JOINSEG" | $G -a 'JOIN' | $G -aq ":$PH" && IN_ON=1
  echo "----- [5] 块 $(basename $b) · WHERE 段（必须无 :$PH） -----"; echo "$WHERESEG"
  echo "--- WHERE 段内 :$PH 匹配 ---"
  echo "$WHERESEG" | $G -an ":$PH" && IN_WHERE=1 || echo "(WHERE 段无匹配 —— 这是期望结果)"
  echo
done
rm -rf "$TMPD"

echo "########## 判定 ##########"
echo "PRESENT (谓词存在,      期望 1) = $PRESENT"
echo "IN_ON   (在 LEFT JOIN..ON, 期望 1) = $IN_ON"
echo "IN_WHERE(在 WHERE 段,    期望 0) = $IN_WHERE"
if   [ "$PRESENT" = 0 ]; then echo "AC-13: FAIL (MISSING — 谓词根本没生成)"; exit 2
elif [ "$IN_WHERE" = 1 ]; then echo "AC-13: FAIL (IN_WHERE — 谓词误入 WHERE，空客编行会整页签 0 行)"; exit 3
elif [ "$IN_ON"   = 0 ]; then echo "AC-13: FAIL (NOT_IN_ON — 谓词存在但不在 JOIN..ON 段)"; exit 4
else echo "AC-13: PASS"; exit 0; fi
