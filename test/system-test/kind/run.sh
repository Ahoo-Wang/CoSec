#!/usr/bin/env bash
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

# The kind leg of the system test (test/system-test/README.md): the Kubernetes manifests of k8s/, as the documentation says
# to use them, on a real cluster with the gateway image built from the commit under test.
#
# Usage: run.sh <image> <cosec-migrate>
#
# Needs docker, kind, kubectl and python3. It creates the cluster `cosec-system`, loads <image>, applies `k8s/overlays/dev`
# with that image, and checks, in order:
#   1. the dev overlay starts WITHOUT the Service its demo route names (cosec-auth, which no overlay provides): the gateway
#      runs, is live, and stays out of the Service (not ready) while no platform document is published;
#   2. after `cosec-migrate publish` through a port-forward, the pod turns ready and the Service has an endpoint;
#   3. a decision: anonymous 401, a signed-in caller on a denied path 403, both problem+json; a permitted request is decided
#      by CoSec and fails only at the missing backend (the gateway's 500 for a host it cannot resolve), and is counted;
#   4. the scrape endpoint on the management port, read from another pod through the pod's address, and that the Service
#      does not publish that port;
#   5. k8s/examples/gateway-mode-service-networkpolicy.yaml is valid for the API server, and selects what it says: the
#      service's pods in its namespace, admitted from the gateway's pods of the namespace the gateway runs in (every selector
#      read from the applied policy itself). Enforcement is not tested: kind's network plugin does not enforce it.
#   6. the decisions of step 3 are counted, by result (the scrape of step 4, read last).
# Every wait is a bounded poll against the property it comes from; nothing is retried.
set -euo pipefail

IMAGE=$1
MIGRATE=$2
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
CLUSTER=cosec-system
NS=cosec-dev
SERVICE_NS=cosec-svc
WORK=$(mktemp -d)
# A throwaway secret of the run, never a deployment's.
SECRET=kind-leg-only-not-a-secret-0123456789abcdef
PIDS=()

cleanup() {
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  rm -rf "$WORK"
}
trap cleanup EXIT

ok() { echo "ok   $*"; }
fail() { echo "FAIL $*" >&2; exit 1; }

# wait_for <what> <bound in seconds> <bound's source> <command...>: polls every 2 s until the command succeeds, or fails
# naming the bound.
wait_for() {
  local what=$1 bound=$2 source=$3
  shift 3
  local deadline=$((SECONDS + bound))
  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || fail "waited ${bound} s for $what ($source)"
    sleep 2
  done
}

kc() { kubectl -n "$NS" "$@"; }

# --- the cluster and the overlay -------------------------------------------------------------------------------------
command -v kind >/dev/null || fail "kind is not installed on this runner"
kind create cluster --name "$CLUSTER" --wait 180s
kind load docker-image "$IMAGE" --name "$CLUSTER"
kubectl create namespace "$NS"
kubectl -n "$NS" create secret generic cosec-jwt-secret --from-literal=secret="$SECRET"

# The dev overlay, with the image under test in place of the published one: the overlay itself is not edited.
mkdir -p "$WORK/overlay"
RELATIVE=$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$ROOT/k8s/overlays/dev" "$WORK/overlay")
IMAGE_NAME=${IMAGE%:*}
IMAGE_TAG=${IMAGE##*:}
cat >"$WORK/overlay/kustomization.yaml" <<YAML
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - $RELATIVE
images:
  - name: ghcr.io/ahoo-wang/cosec-gateway
    newName: $IMAGE_NAME
    newTag: "$IMAGE_TAG"
YAML
kubectl apply -k "$WORK/overlay"

# --- 1. the overlay starts without the Service its demo route names -------------------------------------------------
kc rollout status deployment/redis --timeout=120s
# The startup probe allows 90 failures of 2 s (k8s/base/deployment.yaml): the container is started within 180 s.
started() { [ "$(kc get pod -l app.kubernetes.io/name=cosec-gateway -o jsonpath='{.items[0].status.containerStatuses[0].started}')" = true ]; }
wait_for "the gateway container to start" 180 "startupProbe: 90 x 2 s" started
GATEWAY_POD=$(kc get pod -l app.kubernetes.io/name=cosec-gateway -o jsonpath='{.items[0].metadata.name}')
kc exec "$GATEWAY_POD" -- curl -fs -o /dev/null http://localhost:8080/livez || fail "the gateway is not live on /livez"
if kc exec "$GATEWAY_POD" -- curl -fs -o /dev/null http://localhost:8080/readyz; then
  fail "the gateway is ready with no platform document: /readyz must refuse"
fi
[ "$(kc get pod "$GATEWAY_POD" -o jsonpath='{.status.containerStatuses[0].ready}')" = false ] || fail "the pod is Ready with no platform document"
[ "$(kc get pod "$GATEWAY_POD" -o jsonpath='{.status.containerStatuses[0].restartCount}')" = 0 ] || fail "the gateway restarted: a liveness probe must not follow the documents"
[ -z "$(kc get endpoints cosec-gateway -o jsonpath='{.subsets[*].addresses[*].ip}')" ] || fail "the Service has an endpoint for a gateway that has no documents"
ok "the dev overlay starts without the Service cosec-auth; /livez 200, /readyz refuses, the pod is out of the Service, no restart"

# --- 2. publish, and the pod turns ready --------------------------------------------------------------------------
kubectl -n "$NS" port-forward svc/redis 6379:6379 >"$WORK/redis-forward.log" 2>&1 &
PIDS+=($!)
wait_for "the Redis port-forward" 30 "kubectl port-forward" bash -c 'exec 3<>/dev/tcp/127.0.0.1/6379'
"$MIGRATE" publish --to-redis redis://127.0.0.1:6379 --file "$ROOT/test/system-test/kind/platform.json" --document platform
# The pod's readiness: the next /readyz probe (every 5 s, k8s/base) after the revalidation (SNP-6: 10 s) and a cold load (2 s).
wait_for "the gateway to turn ready" 60 "readinessProbe 5 s + revalidate-interval 10 s (SNP-6) + cold-load-timeout 2 s" \
  kc wait --for=condition=Ready "pod/$GATEWAY_POD" --timeout=1s
has_endpoint() { [ -n "$(kc get endpoints cosec-gateway -o jsonpath='{.subsets[*].addresses[*].ip}')" ]; }
wait_for "the Service to have an endpoint" 30 "endpoints follow readiness" has_endpoint
ok "after cosec-migrate publish the pod is Ready and the Service has an endpoint"

# --- 3. decisions -----------------------------------------------------------------------------------------------------
kubectl -n "$NS" port-forward svc/cosec-gateway 8080:80 >"$WORK/gateway-forward.log" 2>&1 &
PIDS+=($!)
wait_for "the gateway port-forward" 30 "kubectl port-forward" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'
TOKEN=$(SECRET=$SECRET python3 - <<'PY'
import base64, hashlib, hmac, json, os, time, uuid

def part(value):
    raw = value if isinstance(value, bytes) else json.dumps(value, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

now = int(time.time())
signing = part({"alg": "HS256", "typ": "JWT"}) + "." + part(
    {"jti": uuid.uuid4().hex, "sub": "kind", "tenantId": "kind", "iat": now, "exp": now + 600}
)
print(signing + "." + part(hmac.new(os.environ["SECRET"].encode(), signing.encode(), hashlib.sha256).digest()))
PY
)
status_of() { curl -s -o "$WORK/body" -D "$WORK/headers" -w '%{http_code}' "$@"; }
type_of() { tr -d '\r' <"$WORK/headers" | sed -n 's/^[Cc]ontent-[Tt]ype: *//p' | head -n 1; }
GATEWAY=http://127.0.0.1:8080

[ "$(status_of "$GATEWAY/cosec-auth/orders/1")" = 401 ] || fail "an anonymous request is not 401: $(cat "$WORK/body")"
case "$(type_of)" in application/problem+json*) ;; *) fail "the 401 is not problem+json" ;; esac
grep -q 'problems/unauthorized' "$WORK/body" || fail "the 401 is not the problem type unauthorized: $(cat "$WORK/body")"
[ "$(status_of -H "Authorization: Bearer $TOKEN" "$GATEWAY/cosec-auth/denied/1")" = 403 ] || fail "a denied path is not 403: $(cat "$WORK/body")"
grep -q 'problems/forbidden' "$WORK/body" || fail "the 403 is not the problem type forbidden: $(cat "$WORK/body")"
# The backend does not exist: Spring Cloud Gateway answers 500 for the host it cannot resolve (UnknownHostException), which
# only happens once CoSec has permitted the request. A refusal of CoSec's is a problem+json of cosec.ahoo.me/problems.
PERMITTED=$(status_of -H "Authorization: Bearer $TOKEN" "$GATEWAY/cosec-auth/orders/1" || true)
case "$PERMITTED" in
  401 | 403) fail "a permitted request was refused by CoSec ($PERMITTED): $(cat "$WORK/body")" ;;
  5??) grep -q 'cosec.ahoo.me/problems' "$WORK/body" && fail "the request failed with a CoSec problem, not at the missing backend: $(cat "$WORK/body")" ;;
  *) fail "a request to the missing backend answered $PERMITTED, not a gateway error" ;;
esac
ok "decisions: anonymous 401 unauthorized, a denied path 403 forbidden, a permitted request passes CoSec and fails at the missing backend ($PERMITTED, not a CoSec problem)"

# --- 4. the scrape endpoint, from inside the cluster -----------------------------------------------------------------
# The pods of this leg run the image under test (already loaded into the cluster, never pulled): it has curl and a shell, and
# nothing else is fetched from a registry that the leg chooses.
# A probe pod is created, waited for until it has finished, read with `kubectl logs` and deleted: no `-i`, no attach, which can
# return before the pod's output (a leg that loses a step's output is a defect of the leg, and nothing is retried).
pod_finished() { case "$(kc get pod "$1" -o jsonpath='{.status.phase}')" in Succeeded | Failed) return 0 ;; *) return 1 ;; esac; }
probe() { # <name> <command...>: runs the command in a pod of the image, prints its output; fails when the command does
  local name=$1 phase
  shift
  kc run "$name" --image="$IMAGE" --image-pull-policy=Never --restart=Never --command -- "$@" >/dev/null
  # The image is on the node already (loaded, never pulled): the pod starts in seconds; its longest command is a curl of at
  # most 10 s (its own -m).
  wait_for "the probe pod $name to finish" 60 "pod start with the image on the node + curl's own 10 s timeout" pod_finished "$name"
  phase=$(kc get pod "$name" -o jsonpath='{.status.phase}')
  kc logs "$name"
  kc delete pod "$name" --wait=true >/dev/null
  [ "$phase" = Succeeded ]
}
POD_IP=$(kc get pod "$GATEWAY_POD" -o jsonpath='{.status.podIP}')
SCRAPE=$(probe scrape curl -fs -m 10 "http://$POD_IP:8081/actuator/prometheus") || fail "the scrape endpoint is not reachable from another pod on $POD_IP:8081"
# Searched in a file, never through `echo | grep -q`: the scrape is far larger than a pipe, grep -q leaves at its first match, and
# echo then dies of SIGPIPE, which `set -o pipefail` reports as a failed pipeline whatever grep found.
printf '%s\n' "$SCRAPE" >"$WORK/scrape.txt"
grep -q '^cosec_authorization_seconds_count{' "$WORK/scrape.txt" || fail "the scrape has no cosec.authorization timer; the scrape answered: ${SCRAPE:0:300}"
# 000: curl got no answer at all (the Service has no port 8081), whatever kubectl does with the pod's exit code.
PUBLISHED=$(probe published curl -s -m 5 -o /dev/null -w '%{http_code}' http://cosec-gateway:8081/actuator/prometheus || true)
case "$PUBLISHED" in *000*) ;; *) fail "the Service cosec-gateway publishes the management port 8081 (answered: $PUBLISHED)" ;; esac
ok "the scrape endpoint answers on the pod's management port, with the decision timer; the Service does not publish it"

# --- 5. the example NetworkPolicy ---------------------------------------------------------------------------------------
EXAMPLE="$ROOT/k8s/examples/gateway-mode-service-networkpolicy.yaml"
POLICY=only-the-gateway-reaches-the-service
kubectl create namespace "$SERVICE_NS"
kubectl -n "$SERVICE_NS" apply --dry-run=server -f "$EXAMPLE" >/dev/null || fail "the example NetworkPolicy is not valid for the API server"
kubectl -n "$SERVICE_NS" apply -f "$EXAMPLE"
# Three label selectors, read from the applied policy itself: the pods it protects, and the namespace and the pods it admits.
SELECTORS=$(kubectl -n "$SERVICE_NS" get networkpolicy "$POLICY" -o json | python3 -c '
import json, sys
spec = json.load(sys.stdin)["spec"]
def selector(match):
    return ",".join(f"{key}={value}" for key, value in sorted(match["matchLabels"].items()))
admitted = spec["ingress"][0]["from"][0]
print(selector(spec["podSelector"]))
print(selector(admitted["namespaceSelector"]))
print(selector(admitted["podSelector"]))
')
SERVICE_SELECTOR=$(sed -n 1p <<<"$SELECTORS")
FROM_NAMESPACE_SELECTOR=$(sed -n 2p <<<"$SELECTORS")
FROM_POD_SELECTOR=$(sed -n 3p <<<"$SELECTORS")
# The service's pod only has to exist with the label: enforcement is not tested (kindnet does not enforce), nothing connects.
kubectl -n "$SERVICE_NS" run service --image="$IMAGE" --image-pull-policy=Never --restart=Never --labels="$SERVICE_SELECTOR" \
  --command -- sleep 3600
[ "$(kubectl -n "$SERVICE_NS" get pods -l "$SERVICE_SELECTOR" -o name | wc -l | tr -d ' ')" = 1 ] || fail "the example's podSelector ($SERVICE_SELECTOR) selects no pod of the service"
SELECTED_NAMESPACES=$(kubectl get namespace -l "$FROM_NAMESPACE_SELECTOR" -o name)
grep -qx "namespace/$NS" <<<"$SELECTED_NAMESPACES" || fail "the example's namespaceSelector ($FROM_NAMESPACE_SELECTOR) does not select $NS"
SELECTED_PODS=$(kc get pods -l "$FROM_POD_SELECTOR" -o name)
grep -qx "pod/$GATEWAY_POD" <<<"$SELECTED_PODS" || fail "the example's podSelector for the gateway ($FROM_POD_SELECTOR) does not select the gateway pod of $NS"
ok "the example NetworkPolicy is valid, selects the service's pod ($SERVICE_SELECTOR) and admits the gateway's pod of the namespace $FROM_NAMESPACE_SELECTOR ($FROM_POD_SELECTOR)"

# --- 6. the decisions are counted, by result ----------------------------------------------------------------------------
# The scrape of step 4 was taken after the three decisions of step 3.
grep -q '^cosec_authorization_seconds_count{.*result="permit"' "$WORK/scrape.txt" || fail "the scrape has no cosec.authorization count for result=permit"
grep -q '^cosec_authorization_seconds_count{.*result="explicit_deny"' "$WORK/scrape.txt" || fail "the scrape has no cosec.authorization count for result=explicit_deny"
ok "the decisions of step 3 are counted: a permit and an explicit deny"

echo "kind leg: passed"
