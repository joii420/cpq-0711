#!/usr/bin/env bash
# build-bundle.sh — one-shot offline-bundle builder for CPQ.
#
# Runs on a machine with internet access. Produces a single tarball you can
# scp to the intranet host:
#
#   cpq-offline-bundle-YYYYMMDD-HHMM.tar.gz
#     ├── cpq-latest-amd64.tar.gz      (the docker image, gzipped)
#     ├── docker-compose.offline.yml   (runtime-only compose, no build section)
#     ├── .env.example                 (env template — fill in on the intranet)
#     └── README.txt                   (intranet deployment cheat sheet)
#
# Usage:
#   cd dev/deploy
#   chmod +x build-bundle.sh
#   ./build-bundle.sh
#
# Optional overrides:
#   IMAGE_TAG=cpq:1.0.0  ./build-bundle.sh     # tag the image differently
#   PLATFORM=linux/arm64 ./build-bundle.sh     # build for arm64 target host
#   SKIP_BUILD=1         ./build-bundle.sh     # reuse existing local image

set -euo pipefail

# ---------- config ---------------------------------------------------------
IMAGE_TAG="${IMAGE_TAG:-cpq:latest}"
PLATFORM="${PLATFORM:-linux/amd64}"
BUILD_DATE="$(date +%Y%m%d-%H%M)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_CTX="$(cd "${SCRIPT_DIR}/.." && pwd)"   # = dev/
STAGING_DIR="${SCRIPT_DIR}/bundle-staging"
OUT_TARBALL="${SCRIPT_DIR}/cpq-offline-bundle-${BUILD_DATE}.tar.gz"
IMAGE_TAR_NAME="cpq-latest-amd64.tar.gz"

# ---------- pretty output --------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { printf "${BLUE}[%s]${NC} %s\n" "$(date +%H:%M:%S)" "$*"; }
ok()   { printf "${GREEN}OK${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}!${NC} %s\n" "$*"; }
die()  { printf "${RED}X %s${NC}\n" "$*" >&2; exit 1; }

# ---------- preflight ------------------------------------------------------
command -v docker >/dev/null || die "docker not found in PATH"
command -v tar    >/dev/null || die "tar not found in PATH"
command -v gzip   >/dev/null || die "gzip not found in PATH"

[ -f "${SCRIPT_DIR}/Dockerfile" ] \
    || die "Dockerfile missing in ${SCRIPT_DIR}"
[ -f "${SCRIPT_DIR}/docker-compose.offline.yml" ] \
    || die "docker-compose.offline.yml missing - run from dev/deploy/"
[ -f "${SCRIPT_DIR}/.env.example" ] \
    || die ".env.example missing in ${SCRIPT_DIR}"
[ -d "${BUILD_CTX}/cpq-frontend" ] && [ -d "${BUILD_CTX}/cpq-backend" ] \
    || die "cpq-frontend or cpq-backend missing under ${BUILD_CTX}"

log "Config:"
echo "    IMAGE_TAG  = ${IMAGE_TAG}"
echo "    PLATFORM   = ${PLATFORM}"
echo "    BUILD_CTX  = ${BUILD_CTX}"
echo "    OUTPUT     = ${OUT_TARBALL}"
echo

# ---------- build ----------------------------------------------------------
if [ "${SKIP_BUILD:-0}" = "1" ]; then
    warn "SKIP_BUILD=1 set - reusing existing local image ${IMAGE_TAG}"
    docker image inspect "${IMAGE_TAG}" >/dev/null \
        || die "Image ${IMAGE_TAG} not present locally; cannot skip build"
else
    log "Building image (BuildKit enabled, platform=${PLATFORM})..."
    DOCKER_BUILDKIT=1 docker build \
        --platform "${PLATFORM}" \
        -t "${IMAGE_TAG}" \
        -f "${SCRIPT_DIR}/Dockerfile" \
        "${BUILD_CTX}"
    ok "Image built: ${IMAGE_TAG}"
fi

# ---------- stage files ----------------------------------------------------
log "Staging bundle contents in ${STAGING_DIR}/"
rm -rf "${STAGING_DIR}"
mkdir -p "${STAGING_DIR}"

log "Saving image to ${IMAGE_TAR_NAME} (this can take a minute)..."
docker save "${IMAGE_TAG}" | gzip -1 > "${STAGING_DIR}/${IMAGE_TAR_NAME}"
ok "Image tarball: $(du -h "${STAGING_DIR}/${IMAGE_TAR_NAME}" | cut -f1)"

cp "${SCRIPT_DIR}/docker-compose.offline.yml" "${STAGING_DIR}/"
cp "${SCRIPT_DIR}/.env.example"               "${STAGING_DIR}/"

cat > "${STAGING_DIR}/README.txt" <<EOF
CPQ Offline Deployment Bundle
=============================
Built:    ${BUILD_DATE}
Image:    ${IMAGE_TAG}
Platform: ${PLATFORM}

Files in this bundle:
  - ${IMAGE_TAR_NAME}           (gzipped docker image, ~300-450 MB)
  - docker-compose.offline.yml  (runtime-only compose, no build section)
  - .env.example                (env template - fill before starting)
  - README.txt                  (this file)

Intranet deployment steps
-------------------------
  1) Pick a working directory, e.g.:
       mkdir -p /opt/cpq && cd /opt/cpq

  2) Copy this bundle's contents into that directory (after extraction):
       tar -xzf cpq-offline-bundle-*.tar.gz -C /opt/cpq

  3) Load the docker image (one-time):
       gunzip -c ${IMAGE_TAR_NAME} | docker load
       docker images | grep cpq

  4) Configure environment:
       cp .env.example .env
       chmod 600 .env
       vim .env       # fill DB_*, REDIS_*, CPQ_ENCRYPTION_KEY

  5) Start:
       docker compose -f docker-compose.offline.yml --env-file .env up -d
       docker compose -f docker-compose.offline.yml logs -f app

  6) Verify (wait 30-90s for Flyway):
       curl -fsS http://localhost:\${HTTP_PORT:-8200}/api/cpq/health

Upgrades
--------
  Bring a new bundle. Then:
     docker compose -f docker-compose.offline.yml down
     gunzip -c <new-image>.tar.gz | docker load
     docker compose -f docker-compose.offline.yml --env-file .env up -d

DO NOT add --build to any docker compose command on the intranet -
the offline compose has no build section and will fail fast.
EOF

ok "Staging ready: $(ls -1 "${STAGING_DIR}" | tr '\n' ' ')"

# ---------- pack -----------------------------------------------------------
log "Creating final bundle: ${OUT_TARBALL}"
tar -czf "${OUT_TARBALL}" -C "${STAGING_DIR}" .
rm -rf "${STAGING_DIR}"

BUNDLE_SIZE="$(du -h "${OUT_TARBALL}" | cut -f1)"
ok "Bundle ready: ${OUT_TARBALL}  (${BUNDLE_SIZE})"

# ---------- next steps -----------------------------------------------------
cat <<EOF

----------------------------------------------------------------------
 Next steps
----------------------------------------------------------------------
 1. Transfer the bundle to the intranet host (scp / portable disk / ...):
      scp ${OUT_TARBALL} user@intranet-host:/tmp/

 2. On the intranet host:
      mkdir -p /opt/cpq && cd /opt/cpq
      tar -xzf /tmp/$(basename "${OUT_TARBALL}")
      cat README.txt        # follow the steps inside

 3. Sanity check on THIS machine right now (optional):
      docker run --rm ${IMAGE_TAG} java -version
----------------------------------------------------------------------
EOF
