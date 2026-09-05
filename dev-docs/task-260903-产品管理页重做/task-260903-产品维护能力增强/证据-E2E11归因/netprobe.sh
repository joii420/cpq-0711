#!/bin/bash
# 只读探针：每 3s 记录 ①到 DB 主机的 ICMP ②psql select 1 ③本机后端 8092 业务端点
export PGPASSWORD=joii5231
LOG=/tmp/claude-1000/-home-joii-project-cpq/9f0ef33a-5008-44d3-8556-01c99d034580/scratchpad/netprobe.log
: > "$LOG"
while true; do
  TS=$(date +%H:%M:%S)
  P=$(timeout 3 ping -c1 -W2 10.177.152.12 >/dev/null 2>&1 && echo OK || echo DOWN)
  Q=$(timeout 8 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tAc "select 1" 2>&1 | tr -d '\n' | cut -c1-60)
  B=$(curl -s --noproxy '*' -m 8 -o /dev/null -w '%{http_code}' http://127.0.0.1:8092/api/cpq/components 2>/dev/null)
  echo "$TS ping=$P psql=${Q:-EMPTY} be8092=$B" >> "$LOG"
  sleep 3
done
