---
name: cosec-troubleshoot
description: "Use when diagnosing CoSec authentication or authorization failures such as unexpected 401/403 responses, denied requests that should be allowed, policies not loading, JWT token rejection, matcher mismatches, or unclear access decisions."
---

# CoSec Troubleshooting Guide

This skill helps you debug authorization issues in CoSec. When a request gets an unexpected result (403, 401, 429, or is allowed when it shouldn't be), follow this systematic approach.

## Step 1: Enable Debug Logging

The fastest way to understand authorization decisions is debug logging on `SimpleAuthorization`:

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: debug
```

This logs every matched statement, the policy/statement it came from, and the final result, e.g. `Verify [request] [context] matched Policy[globalPolicy] Statement[2][RequestOriginDeny] - [EXPLICIT_DENY].` (statement index is 0-based)

For more granular tracing:
```yaml
logging:
  level:
    me.ahoo.cosec.policy: debug
    me.ahoo.cosec.authentication: debug
    me.ahoo.cosec.jwt: debug
```

## Step 2: Understand the Evaluation Order

`SimpleAuthorization.authorize` evaluates in this order, falling through with `switchIfEmpty` when a step produces no match:

```
1. Root user check
   └─ If principal.id == root ID (default "cosec") → ALLOW (bypass everything)

2. Blacklist check
   └─ If principal is blacklisted → EXPLICIT_DENY

3. Global policies (type: "global")
   └─ Skip policies whose policy-level condition doesn't match
   └─ Pool ALL statements from ALL matched policies, then deny-first:
      a. Check every DENY statement → EXPLICIT_DENY if any matches
      b. Check every ALLOW statement → ALLOW if any matches

4. Principal-specific policies
   └─ Policies attached to the user (via policy IDs on the principal)
   └─ Same pooled deny-first evaluation as global policies

5. Role-based app permissions
   └─ Only when the principal has roles; permissions are looked up
      by request.appId + request.spaceId, then evaluated deny-first

6. Default → IMPLICIT_DENY
```

Key consequence: a DENY statement in ANY policy of the same tier beats an ALLOW statement in ANY other policy of that tier — policy order within a tier does not matter.

## Step 3: Common Issues and Fixes

### All requests return 403

**Symptoms:** Every endpoint returns 403, even public ones.

**Likely causes:**
1. No policy files loaded — check `cosec.authorization.local-policy.enabled=true`
2. Policy files don't match the location pattern — default is `classpath:cosec-policy/*-policy.json`
3. Policy JSON syntax error — check startup logs for deserialization errors

**Fix:**
```yaml
cosec:
  authorization:
    local-policy:
      enabled: true
      locations: classpath:cosec-policy/*-policy.json
```

### Specific endpoint returns 403 when it should be public

**Symptoms:** Most endpoints work, but a new public endpoint returns 403.

**Cause:** No ALLOW statement matches the endpoint. By default, CoSec uses implicit deny — anything not explicitly allowed is denied.

**Fix:** Add a statement for the endpoint:
```json
{
  "name": "NewPublicEndpoint",
  "action": "/api/new-endpoint"
}
```

### Request allowed when it should be denied

**Symptoms:** A request that should be blocked gets through.

**Likely causes:**
1. DENY statement doesn't match — check action pattern and condition
2. An ALLOW statement in a LATER tier matches (tiers fall through: global → principal → role permissions) — a DENY only overrides ALLOW within the same tier
3. Root user bypass — check if the user ID equals the root ID (default `"cosec"`, override with the `cosec.root` system property)

**Debug:** Enable debug logging and check which statement matched.

### JWT token rejected

**Symptoms:** Requests with valid JWT tokens return 401.

**Likely causes:**
1. `cosec.jwt.secret` doesn't match the token issuer's secret
2. `cosec.jwt.algorithm` doesn't match the token's algorithm (supported: `hmac256`, `hmac384`, `hmac512`)
3. Token is expired (`token-validity.access` defaults to 10 minutes)
4. Token was revoked (logout) while `cosec.jwt.token-revocation.enabled=true`

**Check:**
```yaml
cosec:
  jwt:
    algorithm: hmac256    # must match the signing algorithm
    secret: exact-same-secret-used-by-issuer
    token-validity:
      access: 10m         # access token TTL
      refresh: 7d         # refresh token TTL
    token-revocation:
      enabled: false      # when true, revoked (logged-out) tokens are rejected
```

### Policies not loading from local files

**Symptoms:** Startup succeeds but policies don't take effect.

**Checklist:**
1. File location: `src/main/resources/cosec-policy/` (not `resources/main/...`)
2. File naming: must match `*-policy.json` pattern
3. Property: `cosec.authorization.local-policy.enabled=true`
4. JSON validity: parse errors are logged at startup
5. Policy type: must be `"global"` for the policy to apply to all requests

### Rate limiter not working

**Symptoms:** Limits are per-instance instead of shared across the cluster.

**Cause:** Both `rateLimiter` and `groupedRateLimiter` keep state in memory, per JVM instance — there is no built-in distributed limiter, and `cosec-cocache` does not provide one (it only caches policies/permissions/tokens).

**Fix:** Implement a custom `ConditionMatcher` backed by a shared store such as Redis — see the `cosec-custom-matcher` skill. Also note: a tripped limiter produces `TOO_MANY_REQUESTS` (HTTP 429), not a plain 403.

### Path variables not matching

**Symptoms:** `/user/123` doesn't match `/user/{id}`.

**Check:**
1. Use `{varName}` — the `:varName` colon syntax is not supported by CoSec's path patterns
2. Access the variable via `request.path.var.varName` in conditions
3. Ensure the path pattern is correct (no trailing slash mismatch)

### SpEL template not evaluating

**Symptoms:** `#{principal.id}` is treated as a literal string.

**Cause:** SpEL templates use `#{}` syntax. `{}` alone is a path variable, not SpEL.

**Fix:** Use `#{principal.id}` not `{principal.id}`.

### Condition part path is wrong

**Symptoms:** Condition always returns false, or evaluation throws `IllegalArgumentException: Unsupported part`.

**Valid part paths:**
- `request.path` — full request path
- `request.path.var.{name}` — path variable
- `request.method` — HTTP method
- `request.remoteIp` — client IP address
- `request.origin` — Origin; `request.origin.host` — origin host
- `request.referer` — Referer; `request.referer.host` — referer host
- `request.appId` / `request.spaceId` / `request.deviceId` — identifiers
- `request.header.{name}` — request header (singular `header`)
- `request.attributes.{key}` — request attributes
- `context.tenantId` — tenant ID
- `context.principal.id` — user ID
- `context.principal.attributes.{key}` — principal attribute

Common mistakes:
- `request.headers.X-Foo` (wrong) → `request.header.X-Foo` (correct)
- `request.ip` (wrong) → `request.remoteIp` (correct)
- `principal.id` (wrong) → `context.principal.id` (correct)
- `request.pathVariable.id` (wrong) → `request.path.var.id` (correct)

## Step 4: Testing Policies Locally

These patterns mirror the real tests in `cosec-core/src/test/kotlin/me/ahoo/cosec/authorization/SimpleAuthorizationTest.kt`.

### Unit test a full authorization decision

```kotlin
@Test
fun `test policy evaluation`() {
    val globalPolicy = mockk<Policy> {
        every { id } returns "globalPolicy"
        every { condition } returns AllConditionMatcher.INSTANCE
        every { statements } returns listOf(
            StatementData(
                name = "PublicEndpoints",
                effect = Effect.ALLOW,
                action = PathActionMatcherFactory.INSTANCE
                    .create(mapOf("pattern" to "/api/users/*").asConfiguration()),
            ),
        )
    }
    val policyRepository = mockk<PolicyRepository> {
        every { getGlobalPolicy() } returns Mono.just(listOf(globalPolicy))
        every { getPolicies(any()) } returns Mono.empty()
    }
    val permissionRepository = mockk<AppRolePermissionRepository> {
        every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
    }

    val authorization = SimpleAuthorization(policyRepository, permissionRepository)
    val request = mockk<Request> {
        every { path } returns "/api/users/123"
        every { method } returns "GET"
    }
    // relaxed: evaluation writes path variables and the verify context into the context
    val securityContext = mockk<SecurityContext>(relaxed = true) {
        every { principal.id } returns "user-123"
    }

    authorization.authorize(request, securityContext)
        .test()
        .expectNext(AuthorizeResult.ALLOW)
        .verifyComplete()
}
```

Imports worth knowing: `me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration` for building matchers from maps, `me.ahoo.cosec.policy.StatementData` / `me.ahoo.cosec.policy.action.PathActionMatcherFactory` for real (non-mock) matchers, and `reactor.kotlin.test.test` for stepping through the `Mono`.

### Smoke-test a local policy file

`LocalPolicyLoader` takes a set of resource patterns and exposes loaded policies as a property; `DefaultPolicyEvaluator` is a stateless object that dry-runs every matcher of a policy against a mock request to surface configuration errors (it swallows rate-limit and regex-timeout errors):

```kotlin
val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/test-policy.json")).policies
policies.forEach { DefaultPolicyEvaluator.evaluate(it) }
```

### Test a specific matcher

```kotlin
@Test
fun `test path action matcher`() {
    val matcher = PathActionMatcherFactory.INSTANCE
        .create(mapOf("pattern" to "/api/users/*").asConfiguration())

    val request = mockk<Request> {
        every { path } returns "/api/users/123"
        every { method } returns "GET"
    }

    matcher.match(request, SimpleSecurityContext.anonymous()).assert().isTrue()
}
```

## Step 5: Request Attributes for Debugging

When debugging, inspect the request attributes that CoSec sets:

- `COSEC_SECURITY_CONTEXT` — the parsed security context
- `request.attributes.ipRegion` — IP geolocation (if `cosec-ip2region` is enabled)

In a WebFlux handler:
```kotlin
@GetMapping("/debug/whoami")
fun whoami(exchange: ServerWebExchange): Mono<Map<String, Any?>> {
    val context = exchange.getAttribute<SecurityContext>(COSEC_SECURITY_CONTEXT)
    return Mono.just(mapOf(
        "principal" to context?.principal?.id,
        "authenticated" to context?.principal?.authenticated,
        "roles" to context?.principal?.roles,
        "tenant" to context?.tenant?.tenantId
    ))
}
```

## Quick Reference: Authorization Results

| Result | `authorized` | Meaning |
|--------|-------------|---------|
| `ALLOW` | `true` | Explicitly allowed by a policy statement |
| `EXPLICIT_DENY` | `false` | Explicitly denied by a DENY statement or blacklist |
| `IMPLICIT_DENY` | `false` | No statement matched (default deny) |
| `TOKEN_EXPIRED` | `false` | JWT token has expired |
| `TOO_MANY_REQUESTS` | `false` | Rate limiter exceeded |
