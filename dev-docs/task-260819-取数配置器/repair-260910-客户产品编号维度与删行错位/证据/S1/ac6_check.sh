#!/bin/bash
# AC-6 checker: 谓词必须在 LEFT JOIN ... ON 内，且必须不在 WHERE 内
F="$1"
echo "########## AC-6 原始证据 · 文件: $F ##########"
echo
echo "----- [1] 完整 sql_template（带行号） -----"
/usr/bin/grep -an '' "$F"
echo
echo "----- [2] 含 customerProductNo 的所有行（/usr/bin/grep -an 原始输出） -----"
/usr/bin/grep -an 'customerProductNo' "$F" || echo "(无匹配)"
echo
echo "----- [3] 含 customer_product_no 的所有行 -----"
/usr/bin/grep -an 'customer_product_no' "$F" || echo "(无匹配)"
echo
echo "----- [4] JOIN 段（FROM 之后、WHERE 之前）内是否含谓词 -----"
JOINSEG=$(/usr/bin/sed -n '/^FROM /,/^WHERE /p' "$F" | /usr/bin/grep -a -v '^WHERE ')
echo "$JOINSEG"
echo "--- JOIN 段内匹配 ---"
IN_ON=0
echo "$JOINSEG" | /usr/bin/grep -a 'JOIN' | /usr/bin/grep -aq 'customer_product_no[[:space:]]*=[[:space:]]*:customerProductNo' && IN_ON=1
echo "$JOINSEG" | /usr/bin/grep -an 'customer_product_no[[:space:]]*=[[:space:]]*:customerProductNo' || echo "(JOIN 段无匹配)"
echo
echo "----- [5] WHERE 段（WHERE 到 ORDER BY/结尾）内是否含谓词（必须无） -----"
WHERESEG=$(/usr/bin/sed -n '/^WHERE /,$p' "$F" | /usr/bin/sed '/^ORDER BY/,$d')
echo "$WHERESEG"
echo "--- WHERE 段内匹配 ---"
IN_WHERE=0
echo "$WHERESEG" | /usr/bin/grep -aq 'customerProductNo' && IN_WHERE=1
echo "$WHERESEG" | /usr/bin/grep -an 'customerProductNo' || echo "(WHERE 段无匹配 —— 这是期望结果)"
echo
echo "----- [6] ORDER BY 行（A 层 tie-breaker 顺带观察） -----"
/usr/bin/grep -an '^ORDER BY' "$F" || echo "(无 ORDER BY)"
echo
echo "########## 判定 ##########"
echo "IN_ON(谓词在 LEFT JOIN..ON 内, 期望 1) = $IN_ON"
echo "IN_WHERE(谓词在 WHERE 内, 期望 0)      = $IN_WHERE"
if [ "$IN_ON" = 1 ] && [ "$IN_WHERE" = 0 ]; then echo "AC-6: PASS"; else echo "AC-6: FAIL"; fi
