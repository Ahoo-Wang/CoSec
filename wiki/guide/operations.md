# Operations

Running CoSec in production: storage and failure modes, auditing, observability, optional integrations,
deployment, and performance.

## Redis and caching

With `cache-support`, Redis is the **store of record** for policies, app permissions and role grants, and
also holds revoked token ids. Each instance keeps an in-process copy
([CoCache](https://github.com/Ahoo-Wang/CoCache)):

- **Reads** are answered from the local cache without a thread switch when the data is local; otherwise
  they go to Redis on a worker thread.
- **Writes** go to Redis, and every instance drops its local copy through an invalidation message, so
  changes apply cluster-wide immediately.
- **Global policy index** updates are atomic Redis set operations, so concurrent writers on different
  instances never lose entries.
- Policy and permission entries **never expire**. Local caches are bounded
  ([`maximum-size`](./configuration.md#cache), default 10,000 entries per cache) and reload from Redis on
  a miss.

**Failure modes:**

| Store | When Redis is unavailable |
|---|---|
| Policies, global index, app permissions, role grants | Locally cached data keeps working. Anything else fails, and the request is **denied** (`500`). |
| Revoked tokens | The check **allows** the token, so an outage doesn't reject every authenticated request. |
| Redis rate limiters | Allow, unless the condition sets `strictFailure: true`. |

Rolling upgrades: CoSec 6 keeps the Redis keys and formats of 5.x, so 5.x and 6.x instances can share one
Redis during an upgrade.

## Auditing

Every authorization decision produces an audit event (`cosec.audit.enabled`, default on):

- **who**: principal id, roles, policies, authenticated, tenant;
- **what**: request id, app and space ids, method, path, remote IP, device id and user agent, gateway route id;
- **decision**: `ALLOW`, `EXPLICIT_DENY`, `IMPLICIT_DENY`, `TOO_MANY_REQUESTS` or `ERROR`, with a reason
  code (for example `TOKEN_EXPIRED` or `REGEX_TIMEOUT`) and the evaluation time;
- **why**: the source (`ROOT`, `BLACKLIST`, `GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION` or
  `NONE`) and the matched policy and statement, or role and permission;
- **trace**: trace and span ids when tracing is active.

Events go to the log by default. With `kafka-support` and a `KafkaTemplate` bean they are published to
Kafka (topic [`cosec.audit.kafka.topic`](./configuration.md#audit)). Provide an `AuditEventSink` bean
to send them anywhere else. Sinks run after the decision, and a failing sink never affects it.

## Observability

With `opentelemetry-support`, each authorization is a span named `cosec.authorize` with these attributes:

| Attribute | Value |
|---|---|
| `user.id`, `user.roles` | principal id and roles |
| `cosec.tenant_id`, `cosec.app_id`, `cosec.space_id`, `device.id`, `cosec.request_id` | request context |
| `cosec.policies` | the principal's policies |
| `cosec.authorize.result` | `ALLOW`, `EXPLICIT_DENY` or `IMPLICIT_DENY` |
| `cosec.authorize.policy.id`, `cosec.authorize.statement.index`, `cosec.authorize.statement.name` | the matched policy statement |
| `cosec.authorize.role.id`, `cosec.authorize.permission.id` | the matched role permission |

Decisions are also logged at debug level by `me.ahoo.cosec.authorization.SimpleAuthorization`, including the
matched policy statement or role permission.

## OpenAPI

With `openapi-support` and Spring Boot Actuator:

- `GET /actuator/cosecPolicyGenerator` generates a policy from your OpenAPI description, and
  `GET /actuator/cosecAppPermissionGenerator` generates an app permission: one `allow` entry per operation
  (path and method), with a placeholder id and metadata. Use them as starting points: set the ids, narrow
  the rules, and review before deploying.
- The OpenAPI document declares bearer authentication, so API explorers can send tokens.

## IP geolocation

With `ip2region-support`, the client IP is looked up in an embedded
[ip2region](https://github.com/lionsoul2014/ip2region) database, and the result is added as the request
attribute `ipRegion`. Conditions can then use `request.attributes.ipRegion`, for example to restrict
access by country:

```json
{ "contains": { "part": "request.attributes.ipRegion", "value": "中国" } }
```

## Deployment

`cosec-gateway-server` is a ready-to-run Spring Cloud Gateway with CoSec authorization, Redis-backed
policies and local policy initialization. It is published as a container image:

```bash
docker run -p 8080:8080 -e COSEC_JWT_SECRET=... ahoowang/cosec-gateway:<version>   # or ghcr.io/ahoo-wang/cosec-gateway
```

Configure routes and CoSec through standard Spring configuration; see its
[`application.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/src/main/resources/application.yaml).
Example Kubernetes manifests are in [`k8s/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s). They read the
JWT secret from a Secret named `cosec-jwt-secret`; create it first
(`kubectl create secret generic cosec-jwt-secret --from-literal=secret=<random value>`), and set the image tag to
the version you deploy.

Checklist for any deployment:

- Set `cosec.jwt.secret` from a secret store, and keep access tokens short-lived.
- Set `cosec.authorization.remote-ip.max-trusted-index` to match your proxy chain.
- Enable `cosec.inject.enabled` only on services that cannot be reached except through the verifying
  gateway.
- Disable `cosec.authorization.root-enabled` if users can choose their own principal ids.

## Performance

Authorization runs in-process. On JDK 17, `SimpleAuthorizationBenchmark`
(about 80 path rules across global, principal and role layers) measures about **2 µs per decision** and
1.8–6.6 KB allocated. With data in the local cache, repository reads add no thread switch.

To reproduce:

```bash
./gradlew :cosec-core:jmh -PjmhIncludes=SimpleAuthorizationBenchmark
```
