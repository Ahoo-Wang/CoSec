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
# Smoke-tests a cosec-control-server image on its AOT cache (STA-10).
#
# Usage: aot-smoke.sh <image> <cosec-migrate>
#
# Needs a Redis on localhost:6379 and Docker. It checks, with -XX:AOTMode=on (a cache that cannot be mapped fails startup
# instead of falling back, as the default AOTMode=auto does):
#
# - the image does not start without the statement of what can reach it, and says what to write;
# - it starts under the statement, the liveness probe answers on the management port and the readiness probe is DOWN
#   until the platform document is installed (published here through `cosec-migrate publish`), then UP;
# - a call to the operators' API with no identity is 401 with problem+json, a call stating the platform administrator is
#   200 and one stating a principal nothing binds is 403; the scrape endpoint on the management port carries the snapshot
#   meters while the application port serves no actuator;
# - the image's own HEALTHCHECK probes the liveness group on the management port, and Docker reports it healthy.
#
set -eu

IMAGE=$1
MIGRATE=$2
REDIS_URI=redis://localhost:6379
PORT=8080
MGMT=8081
NAME=cosec-control-aot-smoke
WORK=$(mktemp -d)
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

failed=0
fail() {
  echo "FAIL $1" >&2
  failed=1
}

# 1. Without the statement the image refuses to start, and names the key.
if docker run --rm --name "$NAME" --network host \
  -e SPRING_DATA_REDIS_URL="$REDIS_URI" -e JAVA_OPTS="-XX:AOTMode=on" \
  "$IMAGE" >"$WORK/refused.log" 2>&1; then
  fail "the image started without the statement of what can reach it"
elif grep -q "cosec.pipeline.identity.only-trusted-callers-can-reach=true" "$WORK/refused.log"; then
  echo "ok   the image refuses to start without the statement, and names the key"
else
  fail "the image failed, but not with the statement's message"
  cat "$WORK/refused.log" >&2
fi

# 2. The platform document: members may call anything. Published after the image is up, so readiness has a before.
mkdir -p "$WORK/documents"
cat >"$WORK/documents/platform.json" <<'JSON'
{"schema":2,"revision":1,"publishedAt":"2026-10-27T00:00:00Z",
"policies":[{"id":"members","statements":[{"effect":"allow","action":"http:*","condition":{"eq":["subject.authenticated",true]}}]}],
"bindings":[{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},
{"id":"plato","subject":{"principal":"plato","tenant":"(platform)"},"app":"cosec-control","space":"","policy":"cosec:platform-admin"}]}
JSON
SHA=$(sha256sum "$WORK/documents/platform.json" | cut -d' ' -f1)
printf '{"accepted":true,"outputSha256":{"platform.json":"%s"}}\n' "$SHA" >"$WORK/documents/report.json"

# 3. The image, on its AOT cache, under the statement.
docker run -d --name "$NAME" --network host --health-interval 2s \
  -e SPRING_DATA_REDIS_URL="$REDIS_URI" \
  -e COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true \
  -e JAVA_OPTS="-XX:AOTMode=on -Xlog:aot=info" \
  "$IMAGE" >/dev/null

started=false
i=0
while [ "$i" -lt 120 ]; do
  if curl -fs -o /dev/null "http://localhost:$MGMT/actuator/health/liveness"; then
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
  echo "AOT smoke: the control server did not start with -XX:AOTMode=on" >&2
  docker logs "$NAME" >&2 || true
  exit 1
fi
echo "ok   the liveness probe answers on the management port"

readiness() {
  curl -s -o /dev/null -w '%{http_code}' "http://localhost:$MGMT/actuator/health/readiness"
}
if [ "$(readiness)" = 503 ]; then
  echo "ok   the readiness probe is DOWN until the platform document is installed"
else
  fail "the readiness probe is not DOWN before the platform document exists"
fi

"$MIGRATE" publish --to-redis "$REDIS_URI" --in "$WORK/documents"
ready=
i=0
while [ "$i" -lt 60 ]; do
  ready=$(readiness)
  [ "$ready" = 200 ] && break
  sleep 1
  i=$((i + 1))
done
if [ "$ready" = 200 ]; then
  echo "ok   the readiness probe is UP once the platform document is installed"
else
  fail "the readiness probe is [$ready] after the platform document was published"
fi

# 4. The operators' API: refused with no identity, as problem+json; no actuator on this port.
out=$(curl -s -o "$WORK/body" -D "$WORK/headers" -w '%{http_code}' "http://localhost:$PORT/cosec/control/v1/documents/platform")
type=$(tr -d '\r' <"$WORK/headers" | sed -n 's/^[Cc]ontent-[Tt]ype: *//p' | head -n 1)
case "$out $type" in
  "401 application/problem+json"*) echo "ok   a call with no identity is 401 problem+json" ;;
  *) fail "a call with no identity answered $out $type"; cat "$WORK/body" >&2 ;;
esac
# A call with an identity header, as the gateway or a service forwards it (base64url of JSON, no signature: the
# network is the boundary). plato is bound to the platform administrator's policy, mallory to nothing.
identity() {
  SUBJECT=$1 python3 - <<'PY'
import base64, json, os, time

now = int(time.time())
identity = {
    "v": 1, "tenant": "(platform)", "sub": os.environ["SUBJECT"], "scope": {"app": "cosec-control", "space": ""},
    "roles": [], "auth": {"method": "jwt", "jti": "smoke-" + os.environ["SUBJECT"], "exp": now + 600, "iat": now},
    "iat": now, "rid": "smoke-" + os.environ["SUBJECT"],
}
print(base64.urlsafe_b64encode(json.dumps(identity, separators=(",", ":")).encode()).rstrip(b"=").decode())
PY
}
for who in plato:200 mallory:403; do
  subject=${who%%:*}
  want=${who##*:}
  got=$(curl -s -o /dev/null -w '%{http_code}' -H "CoSec-Identity: $(identity "$subject")" "http://localhost:$PORT/cosec/control/v1/documents/platform")
  if [ "$got" = "$want" ]; then
    echo "ok   a call stating $subject answers $got"
  else
    fail "a call stating $subject answered $got, not $want"
  fi
done
scrape=$(curl -s "http://localhost:$MGMT/actuator/prometheus")
case "$scrape" in
  *cosec_snapshot_installed*) echo "ok   the scrape endpoint carries cosec_snapshot_installed" ;;
  *) fail "the scrape endpoint has no cosec_snapshot_installed" ;;
esac
if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/prometheus")" != 200 ]; then
  echo "ok   no actuator endpoint on the application port"
else
  fail "the application port serves /actuator/prometheus"
fi

# 5. The image's HEALTHCHECK: the liveness group on the management port, run by Docker with the image's own command.
check=$(docker inspect -f '{{join .Config.Healthcheck.Test " "}}' "$NAME")
case "$check" in
  *"/actuator/health/liveness"*) echo "ok   the image's HEALTHCHECK probes the liveness group" ;;
  *) fail "the image's HEALTHCHECK is [$check], not the liveness group" ;;
esac
health=
i=0
while [ "$i" -lt 30 ]; do
  health=$(docker inspect -f '{{.State.Health.Status}}' "$NAME")
  [ "$health" = healthy ] && break
  sleep 1
  i=$((i + 1))
done
if [ "$health" = healthy ]; then
  echo "ok   Docker reports the container healthy"
else
  fail "Docker reports the container [$health]"
  docker inspect -f '{{json .State.Health}}' "$NAME" >&2 || true
fi
if [ "$failed" != 0 ]; then
  docker logs "$NAME" >&2 || true
  exit 1
fi
echo "AOT smoke: passed"
