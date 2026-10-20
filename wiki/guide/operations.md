# Operations

Running CoSec in production: storage and failure modes, auditing, observability, deployment, and
performance.

## Redis and caching

With `cache-support`, Redis is the **store of record** for policies, app permissions and role grants, and
also holds revoked token ids. Each instance keeps an in-process copy
([CoCache 6](https://cocache.ahoo.me)):

- **Reads** are answered from the local cache without a thread switch when the data is local. Otherwise they
  read Redis without blocking a thread, and the request continues on a worker thread.
- **Writes** go to Redis, and every instance drops its local copy through an invalidation message, so
  changes apply cluster-wide immediately. Write through CoSec (`PolicyRepository.setPolicy`, the
  `AppPermissionCache` and `RolePermissionCache` beans), never with Redis commands: every value starts with a
  binary header that CoCache checks.
- **Global policy index** updates are atomic Redis operations, so concurrent writers on different
  instances never lose entries.
- **Publishing a policy** writes its content and its global index entry separately, not atomically. The order
  keeps a failure safe: a policy becoming global is indexed only after its content is written, and a policy
  leaving global leaves the index before its content changes. A failed publish returns an error; retry it.
- Policy and permission entries **never expire** in Redis. A local copy lives at most 5 minutes by default
  ([`expire-after-write`](./configuration.md#cache)), then is read from Redis again; this bounds how stale a copy
  can get if an invalidation message is lost. Local caches are bounded
  ([`maximum-size`](./configuration.md#cache), default 10,000 entries per cache).

Keys are `<key-prefix>:policy:<id>`, `<key-prefix>:global:policy`, `<key-prefix>:app:permission:<appId>`,
`<key-prefix>:app:role:permission:<appId>:<roleId>[@<spaceId>]` and `<key-prefix>:token:revoked:<tokenId>`; each
cache has its own invalidation channel, `cocache:inv:<key-prefix>:policy` and so on. Redis must be 7.0 or later (or
Valkey 7.2 or later), reached through Lettuce. A minimal Redis ACL must grant what
[CoCache lists](https://cocache.ahoo.me/operations/#acl), which since CoCache 6.3 includes `+hkeys +hget` (keep
`+hgetall`).

**Failure modes:**

| Store | When Redis is unavailable |
|---|---|
| Policies, global index, app permissions, role grants | Locally cached data keeps working. Anything else fails, and the request is **denied** (`500`). |
| Revoked tokens | The check **allows** the token, so an outage doesn't reject every authenticated request. |
| Rate limit counters | Allow, unless the rule sets `strictFailure: true` (then `429`). |

Upgrades: 7.0 cannot share Redis with 6.x, so every instance switches at once, and the 6.x data is migrated
before 7.0 starts (see [Upgrading from 6.1 to 7.0](./upgrading.md#_6-1-to-7-0)). Rate limit
counters are keyed by rule id since 6.1 (see
[Migrating from rate-limit conditions](./rate-limiting.md#migrating-from-rate-limit-conditions)).

## Auditing

Every authorization decision produces an audit event (`cosec.audit.enabled`, default on):

- **who**: principal id, roles, policies, authenticated, tenant;
- **what**: request id, app and space ids, method, path, remote IP, device id and user agent, gateway route id;
- **decision**: `ALLOW`, `EXPLICIT_DENY`, `IMPLICIT_DENY`, `TOO_MANY_REQUESTS` or `ERROR`, with a reason
  code (for example `TOKEN_EXPIRED` or `REGEX_TIMEOUT`) and the evaluation time;
- **why**: the source (`BLACKLIST`, `ROOT`, `GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION` or
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

With `micrometer-support` and a `MeterRegistry` (for example from Spring Boot Actuator), every decision is
recorded by the timer `cosec.authorization` (`cosec.metrics.enabled`, default on). It has two tags, each with a
small fixed set of values, so the number of time series stays bounded:

| Tag | Values |
|---|---|
| `result` | `allow`, `explicit_deny`, `implicit_deny`, `too_many_requests`, `error` |
| `source` | `global_policy`, `principal_policy`, `role_permission`, `root`, `blacklist`, `none`, `unknown` |

Alert on the rate of `explicit_deny` and `error`. A sudden rise usually means an attack, a broken policy or an
unreachable policy store. The timer measures authorization only, not token parsing.

Decisions are also logged at debug level by `me.ahoo.cosec.authorization.SimpleAuthorization`, including the
matched policy statement or role permission.

## Access reviews

Audits ask two questions: *who can do this?* and *what can this person do?* `AccessAnalyzer` answers both from your
policy files, app permissions and role grants, typically in a test or a small reporting job:

```kotlin
val analyzer = AccessAnalyzer.load(
    policyLocations = listOf("classpath:cosec-policy/*-policy.json"),
    appPermissionLocations = listOf("classpath:cosec-policy/*-app-permission.json"),
    // app id to role to permission ids; `role@space` grants in that space only
    roles = mapOf("shop" to mapOf("admin" to setOf("*"), "clerk" to setOf("order.read"), "clerk@east" to setOf("*"))),
)

val paths = analyzer.whoCan(EvaluateRequest(path = "/orders/42", method = "DELETE",
    headers = mapOf(AppIdCapable.APP_ID_KEY to "shop")))
paths.allows   // every rule that could allow it: global policies, policies by id, roles and permissions
paths.denies   // every deny that could stop it

val grants = analyzer.grantsOf(SimplePrincipal("alice", policies = setOf("ops"), roles = setOf("clerk")))
```

Role grants follow [role grant scope](./policies.md#role-grant-scope): each one belongs to an app, a role and a
space, written as in principals' roles: `clerk` is granted in the default space, `clerk@east` in `east` only.
`whoCan` lists the grants made in the request's app (`CoSec-App-Id`) and space (`CoSec-Space-Id`). `grantsOf`
reaches a grant only through a role the principal holds in that space, so `clerk` above does not reach the
`clerk@east` grant; pass `appId` and `spaceId` to narrow it to one app or space. A malformed role (an empty role
or space, or more than one `@`) fails `AccessAnalyzer` construction.

Each rule names its source (`GLOBAL_POLICY` applies to everyone, `PRINCIPAL_POLICY` to principals listing the
policy, `ROLE_PERMISSION` to members of the role in that app and space, given by `roleId` and `spaceId`), the statement or permission, its action and
condition, and two flags:

- `conditional`: a condition must also match, so the rule applies only to some callers or requests.
- `templated`: the action depends on the caller, for example `/users/#{principal.id}/**`. Each template is
  widened to one path segment, so the rule is listed whenever some caller could match it.

Repositories cannot list every policy or role, so the analysis works on the data you give it. It matches actions
only and reports conditions without evaluating them, and when in doubt it lists a rule rather than leaving it out.
To check one concrete principal and request, [explain the decision](./troubleshooting.md#explain-a-decision).

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
- Keep `cosec.authorization.root-enabled` off (the default) unless you need root. When on, root is the platform
  tenant's root id only, so issue platform-tenant tokens from a source users cannot influence.

## Performance

Authorization runs in-process and takes about 2 µs per decision; reads served from the local cache add no
thread switch. Benchmark details and how to reproduce them are in the
[architecture overview](../architecture/overview.md#performance).
