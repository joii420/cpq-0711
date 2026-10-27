#!/usr/bin/env bash
set -euo pipefail

# Prepared acceptance harness for AC-1/2/6/12/13. It is intentionally not run by test design.
# Required inputs prevent accidental use of the shared dev DB or occupied conventional ports.
: "${T261026_BACKEND_PORT:?set a reserved backend port}"
: "${T261026_FAKE_PORT:?set a reserved fake-service port}"
: "${T261026_DB_NAME:?set the isolated test database name}"
: "${T261026_JDBC_URL:?set the isolated test JDBC URL}"
: "${T261026_DB_USERNAME:?set the isolated test database user}"
: "${T261026_DB_PASSWORD:?set the isolated test database password}"
: "${T261026_EVIDENCE_DIR:?set a new evidence output directory}"

case "$T261026_DB_NAME" in
  *test*|*TEST*|*t261026*|*T261026*) ;;
  *) echo "Refusing non-test database name: $T261026_DB_NAME" >&2; exit 2 ;;
esac
case "$T261026_JDBC_URL" in
  *"/$T261026_DB_NAME"*|*"/$T261026_DB_NAME?"*) ;;
  *) echo "JDBC URL does not identify T261026_DB_NAME" >&2; exit 2 ;;
esac

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BACKEND_DIR=$(cd "$SCRIPT_DIR/../../.." && pwd)
REPO_DIR=$(cd "$BACKEND_DIR/.." && pwd)
JAR="$BACKEND_DIR/target/quarkus-app/quarkus-run.jar"
FAKE="$REPO_DIR/cpq-frontend/e2e/task261026-file-crypto-fake-server.mjs"
TOKEN='T261026-SECRET-MUST-NOT-LEAK'
CIPHER='T261026-CIPHERTEXT-MUST-NOT-LEAK'
PLAIN='T261026-DECRYPTED-PLAINTEXT-MUST-NOT-LEAK'
UPSTREAM='T261026-UPSTREAM-ERROR-MUST-NOT-LEAK'
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
EVIDENCE="$T261026_EVIDENCE_DIR/$RUN_ID"
TMP=$(mktemp -d -t T261026-CRYPTO-COLD-XXXXXX)
BACKEND_PID=''
FAKE_PID=''

cleanup() {
  if [[ -n "$BACKEND_PID" ]]; then kill "$BACKEND_PID" 2>/dev/null || true; wait "$BACKEND_PID" 2>/dev/null || true; fi
  if [[ -n "$FAKE_PID" ]]; then kill "$FAKE_PID" 2>/dev/null || true; wait "$FAKE_PID" 2>/dev/null || true; fi
  case "$TMP" in /tmp/T261026-CRYPTO-COLD-*) find "$TMP" -depth -delete 2>/dev/null || true ;; esac
}
trap cleanup EXIT INT TERM

[[ -f "$JAR" ]] || { echo "Build the worktree runner first: $JAR" >&2; exit 2; }
[[ -f "$FAKE" ]] || { echo "Missing fake service: $FAKE" >&2; exit 2; }
mkdir -p "$T261026_EVIDENCE_DIR"
[[ ! -e "$EVIDENCE" ]] || { echo "Evidence run already exists: $EVIDENCE" >&2; exit 2; }
mkdir "$EVIDENCE"

for port in "$T261026_BACKEND_PORT" "$T261026_FAKE_PORT"; do
  if ss -ltn "sport = :$port" | tail -n +2 | grep -q .; then
    echo "Reserved port already in use: $port" >&2
    exit 2
  fi
done

FAKE_CRYPTO_PORT="$T261026_FAKE_PORT" FAKE_CRYPTO_TOKEN="$TOKEN" \
  node "$FAKE" >"$EVIDENCE/fake.log" 2>&1 &
FAKE_PID=$!

for _ in $(seq 1 100); do
  if curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_FAKE_PORT/__identity" \
      >"$EVIDENCE/fake-identity.json"; then break; fi
  sleep 0.1
done
grep -q 'T261026-CRYPTO-FAKE-' "$EVIDENCE/fake-identity.json"

start_backend() {
  local profile=$1
  local server_override=$2
  local label=$3
  local -a args=(
    "-Dquarkus.profile=$profile"
    "-Dquarkus.http.port=$T261026_BACKEND_PORT"
    "-Dquarkus.datasource.jdbc.url=$T261026_JDBC_URL"
    "-Dquarkus.datasource.username=$T261026_DB_USERNAME"
    "-Dquarkus.datasource.password=$T261026_DB_PASSWORD"
    "-Dquarkus.datasource.\"datasource-readonly\".jdbc.url=$T261026_JDBC_URL"
    "-Dquarkus.datasource.\"datasource-readonly\".username=$T261026_DB_USERNAME"
    "-Dquarkus.datasource.\"datasource-readonly\".password=$T261026_DB_PASSWORD"
    "-Dcpq.security.rbac.enabled=false"
    "-Dcpq.price-adjust.startup-recovery.enabled=false"
  )
  if [[ -n "$server_override" ]]; then args+=("-Dfile.encryption.server=$server_override"); fi
  printf 'profile=%s\nserverOverride=%s\nport=%s\ndatabase=%s\n' \
    "$profile" "${server_override:-<application.properties default>}" \
    "$T261026_BACKEND_PORT" "$T261026_DB_NAME" >"$EVIDENCE/$label-command.txt"
  CRYPTO_API_TOKEN="$TOKEN" java "${args[@]}" -jar "$JAR" >"$EVIDENCE/$label-backend.log" 2>&1 &
  BACKEND_PID=$!
  for _ in $(seq 1 300); do
    local status
    status=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
      "http://127.0.0.1:$T261026_BACKEND_PORT/api/cpq/components" || true)
    if [[ "$status" != 000 ]]; then return; fi
    sleep 0.2
  done
  echo "Backend did not start for $label" >&2
  return 1
}

stop_backend() {
  kill "$BACKEND_PID" 2>/dev/null || true
  wait "$BACKEND_PID" || true
  BACKEND_PID=''
}

reset_fake() {
  curl --noproxy '*' -fsS -X POST "http://127.0.0.1:$T261026_FAKE_PORT/__reset" >/dev/null
}

plan_fake() {
  curl --noproxy '*' -fsS -X POST -H 'Content-Type: application/json' \
    --data-binary "@$1" "http://127.0.0.1:$T261026_FAKE_PORT/__plan" >/dev/null
}

upload() {
  local input=$1
  local filename=$2
  local output=$3
  curl --noproxy '*' -sS -o "$output" -w '%{http_code}' \
    -F "file=@$input;filename=$filename;type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
    "http://127.0.0.1:$T261026_BACKEND_PORT/api/cpq/excel-templates/parse-headers"
}

# Non-prod baseline: fake positive control, then application upload must remain at zero calls.
start_backend crypto-contract '' nonprod
curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_BACKEND_PORT/api/cpq/customers/import/template" \
  >"$TMP/plain-valid.xlsx"
printf '%s' "$PLAIN" >>"$TMP/plain-valid.xlsx"
reset_fake
curl --noproxy '*' -fsS -X POST -H 'Content-Type: application/octet-stream' \
  -H "Authorization: Bearer $TOKEN" -H 'X-File-Name: positive.xlsx' \
  -H 'X-Request-Id: T261026-positive-control' --data-binary 'positive' \
  "http://127.0.0.1:$T261026_FAKE_PORT/v1/inspect" >/dev/null
curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_FAKE_PORT/__requests" \
  >"$EVIDENCE/observer-positive-control.json"
node --input-type=module - "$EVIDENCE/observer-positive-control.json" <<'NODE'
import fs from 'node:fs';
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (value.requests.length !== 1 || value.requests[0].path !== '/v1/inspect') process.exit(1);
NODE
reset_fake
[[ "$(upload "$TMP/plain-valid.xlsx" 'plain baseline.xlsx' "$EVIDENCE/nonprod-response.json")" == 200 ]]
curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_FAKE_PORT/__requests" \
  >"$EVIDENCE/nonprod-requests.json"
grep -q '"requests":\[\]' "$EVIDENCE/nonprod-requests.json"
stop_backend

# AC-6 requires one prod cold start with the committed default and no upload/network call.
start_backend prod '' prod-default
stop_backend

# JVM -D override includes a trailing slash to prove normalized, non-duplicated path joining.
OVERRIDE="http://127.0.0.1:$T261026_FAKE_PORT/"
start_backend prod "$OVERRIDE" prod-override

cat >"$TMP/plain-plan.json" <<'JSON'
{"inspectStatus":200,"inspectBody":"{\"encrypted\":false}","decryptStatus":200,"decryptBodyBase64":""}
JSON
plan_fake "$TMP/plain-plan.json"
[[ "$(upload "$TMP/plain-valid.xlsx" 'plain prod.xlsx' "$EVIDENCE/prod-plain-response.json")" == 200 ]]
curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_FAKE_PORT/__requests" \
  >"$EVIDENCE/prod-plain-requests.json"
node --input-type=module - "$EVIDENCE/prod-plain-requests.json" <<'NODE'
import fs from 'node:fs';
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const paths = value.requests.map((request) => request.path);
if (JSON.stringify(paths) !== JSON.stringify(['/v1/inspect'])) process.exit(1);
NODE

printf '%s' "$CIPHER" >"$TMP/cipher.xlsx"
PLAIN_BASE64=$(base64 -w 0 "$TMP/plain-valid.xlsx")
printf '{"inspectStatus":200,"inspectBody":"{\\"encrypted\\":true}","decryptStatus":200,"decryptBodyBase64":"%s"}\n' \
  "$PLAIN_BASE64" >"$TMP/encrypted-plan.json"
plan_fake "$TMP/encrypted-plan.json"
[[ "$(upload "$TMP/cipher.xlsx" 'override encrypted.xlsx' "$EVIDENCE/prod-encrypted-response.json")" == 200 ]]
curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_FAKE_PORT/__requests" \
  >"$EVIDENCE/prod-encrypted-requests.json"

node --input-type=module - "$EVIDENCE/prod-encrypted-requests.json" <<'NODE'
import fs from 'node:fs';
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const paths = value.requests.map((request) => request.path);
if (JSON.stringify(paths) !== JSON.stringify(['/v1/inspect', '/v1/decrypt'])) process.exit(1);
if (paths.some((path) => path.includes('//') || path.includes('/v1/inspect/v1/'))) process.exit(1);
if (!value.requests.every((request) => request.authorizationMatched)) process.exit(1);
NODE

# A/B response shape and key values: non-prod and prod inspect=false use the same real parser.
node --input-type=module - "$EVIDENCE/nonprod-response.json" "$EVIDENCE/prod-plain-response.json" <<'NODE'
import fs from 'node:fs';
const left = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const right = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const shape = (value) => Array.isArray(value) ? value.map(shape)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, shape(value[key])]))
    : typeof value;
if (JSON.stringify(shape(left)) !== JSON.stringify(shape(right))) process.exit(1);
if (left.code !== right.code || JSON.stringify(left.data) !== JSON.stringify(right.data)) process.exit(1);
NODE

# Upstream body reflection and backend log leakage check, with a mandatory positive control.
printf '{"inspectStatus":500,"inspectBody":"%s","decryptStatus":200,"decryptBodyBase64":""}\n' \
  "$UPSTREAM" >"$TMP/error-plan.json"
plan_fake "$TMP/error-plan.json"
[[ "$(upload "$TMP/cipher.xlsx" 'error cipher.xlsx' "$EVIDENCE/prod-error-response.json")" == 502 ]]
printf '%s\n%s\n%s\n%s\nBearer %s\nAuthorization: Bearer %s\n' \
  "$TOKEN" "$CIPHER" "$PLAIN" "$UPSTREAM" "$TOKEN" "$TOKEN" >"$TMP/positive-control.log"
grep -Eq "$TOKEN|$CIPHER|$PLAIN|$UPSTREAM|Bearer $TOKEN|Authorization: Bearer $TOKEN" \
  "$TMP/positive-control.log"
cp "$TMP/positive-control.log" "$EVIDENCE/log-sentinel-positive-control.txt"
if grep -E "$TOKEN|$CIPHER|$PLAIN|$UPSTREAM|Bearer $TOKEN|Authorization: Bearer $TOKEN" \
    "$EVIDENCE/prod-override-backend.log" "$EVIDENCE/prod-error-response.json"; then
  echo 'Sensitive sentinel leaked to backend log or client response' >&2
  exit 1
fi
printf 'positive-control=matched\napplication-log-and-response=zero-matches\n' \
  >"$EVIDENCE/log-sentinel-scan.txt"

stop_backend
curl --noproxy '*' -fsS "http://127.0.0.1:$T261026_FAKE_PORT/__requests" \
  >"$EVIDENCE/final-fake-requests.json"
printf 'AC-1/2/6/12/13 cold-start harness completed\n' >"$EVIDENCE/result.txt"
