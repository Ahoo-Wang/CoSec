#!/usr/bin/env sh
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Smoke-tests a cosec-gateway-server image on its AOT cache (RFC 0008 §3.9, RC prerequisite 5).
#
# Usage: aot-smoke.sh <image> <cosec-migrate>
#
# Needs a Redis on localhost:6379 and Docker. It publishes a platform document through `cosec-migrate publish`
# (the 8.0 way to seed Redis), starts the image with -XX:AOTMode=on (a cache that cannot be mapped fails startup
# instead of falling back, as the default AOTMode=auto does), and checks four requests:
#
# - the health probe is allowed;
# - an unauthenticated request to a protected route is 401 with problem+json;
# - a request with a valid token is allowed;
# - a request a DENY matches is 403 with problem+json.
#
# CoSec authorizes the gateway's routes only, so the image gets one route, /smoke/**, that forwards to its own
# health endpoint: an allowed request answers 200, one CoSec refuses never leaves the gateway.
set -eu

IMAGE=$1
MIGRATE=$2
REDIS_URI=redis://localhost:6379
PORT=8080
# A throwaway secret: the job's own, never a deployment's.
SECRET=aot-smoke-only-not-a-secret-0123456789abcdef
NAME=cosec-aot-smoke
WORK=$(mktemp -d)
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

# 1. The platform document: members may call anything, nobody may call /smoke/denied/**.
mkdir -p "$WORK/documents"
cat >"$WORK/documents/platform.json" <<'JSON'
{"schema":2,"revision":1,"publishedAt":"2026-10-27T00:00:00Z",
"policies":[
{"id":"members","statements":[{"effect":"allow","action":"http:*","condition":{"eq":["subject.authenticated",true]}}]},
{"id":"no-denied","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/smoke/denied/**"}]}],
"bindings":[
{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},
{"id":"no-denied","subject":"*","app":"*","space":"*","policy":"no-denied"}]}
JSON
# publish reads what convert writes: report.json records each document's SHA-256.
SHA=$(sha256sum "$WORK/documents/platform.json" | cut -d' ' -f1)
printf '{"accepted":true,"outputSha256":{"platform.json":"%s"}}\n' "$SHA" >"$WORK/documents/report.json"
"$MIGRATE" publish --to-redis "$REDIS_URI" --in "$WORK/documents"

# 2. The image, on its AOT cache.
ROUTES='{"spring":{"cloud":{"gateway":{"server":{"webflux":{"routes":[{"id":"smoke","uri":"http://localhost:'$PORT'","predicates":["Path=/smoke/**"],"filters":["SetPath=/actuator/health"]}]}}}}}}'
docker run -d --name "$NAME" --network host \
  -e SPRING_DATA_REDIS_URL="$REDIS_URI" \
  -e COSEC_JWT_SECRET="$SECRET" \
  -e JAVA_OPTS="-XX:AOTMode=on -Xlog:aot=info" \
  -e SPRING_APPLICATION_JSON="$ROUTES" \
  "$IMAGE" >/dev/null

started=false
i=0
while [ "$i" -lt 120 ]; do
  if curl -fs -o /dev/null "http://localhost:$PORT/actuator/health"; then
    started=true
    break
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$NAME")" != true ]; then
    break
  fi
  sleep 1
  i=$((i + 1))
done
if [ "$started" != true ]; then
  echo "AOT smoke: the gateway did not start with -XX:AOTMode=on" >&2
  docker logs "$NAME" >&2 || true
  exit 1
fi

# 3. A token the gateway accepts: HS256 over the job's secret.
TOKEN=$(SECRET=$SECRET python3 - <<'PY'
import base64, hashlib, hmac, json, os, time, uuid

def part(value):
    raw = value if isinstance(value, bytes) else json.dumps(value, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

now = int(time.time())
signing = part({"alg": "HS256", "typ": "JWT"}) + "." + part(
    {"jti": uuid.uuid4().hex, "sub": "smoke", "tenantId": "smoke", "iat": now, "exp": now + 600}
)
signature = hmac.new(os.environ["SECRET"].encode(), signing.encode(), hashlib.sha256).digest()
print(signing + "." + part(signature))
PY
)

failed=0
# expect <name> <status> <problem+json: yes|no> <path> [token]
expect() {
  name=$1
  status=$2
  problem=$3
  path=$4
  if [ $# -ge 5 ]; then
    set -- -H "Authorization: Bearer $5"
  else
    set --
  fi
  out=$(curl -s -o "$WORK/body" -D "$WORK/headers" -w '%{http_code}' "$@" "http://localhost:$PORT$path")
  type=$(tr -d '\r' <"$WORK/headers" | sed -n 's/^[Cc]ontent-[Tt]ype: *//p' | head -n 1)
  ok=true
  [ "$out" = "$status" ] || ok=false
  if [ "$problem" = yes ]; then
    case "$type" in application/problem+json*) ;; *) ok=false ;; esac
  fi
  if [ "$ok" = true ]; then
    echo "ok   $name: $out $type"
  else
    echo "FAIL $name: expected $status$([ "$problem" = yes ] && echo ' problem+json'), got $out $type" >&2
    cat "$WORK/body" >&2
    echo >&2
    failed=1
  fi
}

expect "the health probe is allowed" 200 no /actuator/health
expect "an unauthenticated request is refused" 401 yes /smoke/orders
expect "a request with a valid token is allowed" 200 no /smoke/orders "$TOKEN"
expect "a request a DENY matches is forbidden" 403 yes /smoke/denied/orders "$TOKEN"

if [ "$failed" != 0 ]; then
  docker logs "$NAME" >&2 || true
  exit 1
fi
echo "AOT smoke: passed"
