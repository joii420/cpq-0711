# syntax=docker/dockerfile:1.6
#
# CPQ all-in-one image (frontend dist bundled into Quarkus static resources).
#
# This Dockerfile lives in dev/deploy/ but expects the build context to be the
# parent directory (dev/) so COPY paths "cpq-frontend/" and "cpq-backend/"
# resolve. Requires BuildKit so that "Dockerfile.dockerignore" (per-Dockerfile
# ignore, sibling to this file) is honored.
#
# Standalone build (from dev/deploy):
#   DOCKER_BUILDKIT=1 docker build -t cpq:latest -f Dockerfile ..
# Or via compose (from dev/deploy):
#   docker compose --env-file .env up -d --build
#
# Run (external Postgres + Redis):
#   docker run -d --name cpq -p 8200:8201 --env-file .env cpq:latest

# ============================================================================
# Stage 1: Frontend build (Vite -> dist/)
# ============================================================================
FROM node:24-alpine AS frontend-builder
WORKDIR /app/frontend

# Dependency layer (cached unless package.json / lock changes)
COPY cpq-frontend/package.json cpq-frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

# Source + build
COPY cpq-frontend/ ./
RUN npm run build

# ============================================================================
# Stage 2: Backend build (Maven -> quarkus-app/)
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS backend-builder
WORKDIR /app/backend

# Warm the Maven cache (cached unless pom.xml changes)
COPY cpq-backend/pom.xml ./
RUN mvn -B -e -ntp -DskipTests dependency:go-offline || true

# Backend source
COPY cpq-backend/src ./src

# Bundle frontend build output into Quarkus static-resources path.
# Quarkus serves META-INF/resources/* from the classpath at /; SpaFallbackRoute
# rewrites unknown non-API GETs to /index.html for React Router.
COPY --from=frontend-builder /app/frontend/dist/ ./src/main/resources/META-INF/resources/

# Produce fast-jar layout under target/quarkus-app/
RUN mvn -B -e -ntp -DskipTests package

# ============================================================================
# Stage 3: Runtime (slim JRE 17, non-root, Asia/Shanghai TZ)
# ============================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

# Quarkus scheduler assumes UTC+8 — set TZ at OS + JVM level.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata curl ca-certificates \
 && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai

# Non-root user
RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
WORKDIR /app

# Quarkus fast-jar layout — four distinct layers so libraries (largest, slow to
# change) get reused on application-only rebuilds.
COPY --from=backend-builder --chown=app:app /app/backend/target/quarkus-app/lib/     ./lib/
COPY --from=backend-builder --chown=app:app /app/backend/target/quarkus-app/*.jar    ./
COPY --from=backend-builder --chown=app:app /app/backend/target/quarkus-app/app/     ./app/
COPY --from=backend-builder --chown=app:app /app/backend/target/quarkus-app/quarkus/ ./quarkus/

USER app
EXPOSE 8201

# Override Quarkus HTTP port to 8201 (application.properties keeps 8081 for local dev).
ENV QUARKUS_HTTP_PORT=8201

# JVM defaults; override JAVA_OPTS at runtime if more heap is needed.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai -Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8201/api/cpq/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
