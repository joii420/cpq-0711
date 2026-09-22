#!/usr/bin/env bash
# 只生成 SQL 文本，不连库。用法：bash R5-复位-生成.sh <备份目录绝对路径> '{A,B}' '2026-09-22 04:00:00+00' [CUST-0004]
set -euo pipefail
d="$1"; m="$2"; t0="$3"; c="${4:-CUST-0004}"
sed -e "s#__DIR__#${d}#g" -e "s#__MATS__#${m}#g" -e "s#__T0__#${t0}#g" -e "s#__CUST__#${c}#g" "$(dirname "$0")/R5-复位-模板-v2.sql"
