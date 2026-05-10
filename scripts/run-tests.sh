#!/usr/bin/env bash
# CPQ 测试运行脚本 - 统一封装 JDK17 + Maven 3.9 + 本地 PG/Redis 配置覆盖
# 用法: scripts/run-tests.sh [maven args...]
# 例:   scripts/run-tests.sh test
#       scripts/run-tests.sh test -Dtest=AuthResourceTest
#       scripts/run-tests.sh "-pl cpq-backend test"

set -e

export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export PATH="$JAVA_HOME/bin:/c/Apps/apache-maven-3.9.15/bin:/c/Apps/redis:$PATH"
export MAVEN_OPTS="-Dmaven.repo.local=D:/a-joii/project/CPQ-superpowers/repository ${MAVEN_OPTS:-}"

PG_URL="jdbc:postgresql://10.177.152.12:5432/cpq_db"
PG_USER="postgres"
PG_PASS="joii5231"
REDIS_URL="redis://:joii5231@10.177.152.12:6379/0"

cd "D:/a-joii/project/CPQ-superpowers/dev/cpq-backend"

mvn \
  -Dquarkus.datasource.jdbc.url="$PG_URL" \
  -Dquarkus.datasource.username="$PG_USER" \
  -Dquarkus.datasource.password="$PG_PASS" \
  -Dquarkus.datasource.\"datasource-readonly\".jdbc.url="$PG_URL" \
  -Dquarkus.datasource.\"datasource-readonly\".username="$PG_USER" \
  -Dquarkus.datasource.\"datasource-readonly\".password="$PG_PASS" \
  -Dquarkus.redis.hosts="$REDIS_URL" \
  -Dquarkus.flyway.migrate-at-start=true \
  "$@"
