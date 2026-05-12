#!/usr/bin/env bash
# 检查后端是否运行，若未运行则提示并退出
curl -fsS http://localhost:8081/api/cpq/health || {
  echo ""
  echo "ERROR: Backend not running."
  echo "Start cpq-backend first: cd cpq-backend && mvn quarkus:dev"
  echo ""
  exit 1
}
echo "Backend is up."
