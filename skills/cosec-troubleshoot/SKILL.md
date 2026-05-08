---
name: cosec-troubleshoot
description: "Debug and troubleshoot CoSec authorization issues. Use this skill when users report unexpected 403/401 errors, requests being denied when they should be allowed, policies not taking effect, JWT token issues, or need to understand why a specific request was authorized or denied."
---

# CoSec Troubleshooting Guide

This skill helps you debug authorization issues in CoSec. When a request gets an unexpected result (403, 401, or is allowed when it shouldn't be), follow this systematic approach.

## Step 1: Enable Debug Logging

The fastest way to understand authorization decisions is debug logging on `SimpleAuthorization`:

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: debug
```

This logs the full evaluation chain: root check → blacklist → global policies → principal policies → role permissions → final result.

For more granular tracing:
```yaml
logging:
  level:
    me.ahoo.cosec.policy: debug
    me.ahoo.cosec.authentication: debug
    me.ahoo.cosec.jwt: debug
```

## Step 2: Understand the Evaluation Order

`SimpleAuthorization` evaluates in this order, stopping at the first definitive result:

```
1. Root user check
   └─ If principal.id == "cosec" → ALLOW (bypass everything)

2. Blacklist check
   └─ If principal is blacklisted → EXPLICIT_DENY

3. Global policies (type: "global")
   └─ For each global policy:
      a. Check policy-level condition → skip if no match
      b. Check DENY statements → EXPLICIT_DENY if any matches
      c. Check ALLOW statements → ALLOW if any matches
   └─ First definitive result wins

4. Principal-specific policies
   └─ Policies attached to the user (via policy IDs on the principal)
   └─ Same evaluation as global policies

5. Role-based app permissions
   └─ Evaluate role permissions for the request's appId/spaceId
   └─ Only applies when request has an appId

6. Default → IMPLICIT_DENY
```

Each step uses `switchIfEmpty` to fall through to the next if no match is found.

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
2. Another ALLOW statement matches first (but DENY should take precedence)
3. Root user bypass — check if the user ID is "cosec"

**Debug:** Enable debug logging and check which statement matched.

### JWT token rejected

**Symptoms:** Requests with valid JWT tokens return 401.

**Likely causes:**
1. `cosec.jwt.secret` doesn't match the token issuer's secret
2. `cosec.jwt.algorithm` doesn't match the token's algorithm
3. Token is expired
4. Token format is wrong (not a standard JWT)

**Check:**
```yaml
cosec:
  jwt:
    algorithm: hmac256    # must match the signing algorithm
    secret: exact-same-secret-used-by-issuer
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

**Symptoms:** Rate limiting conditions are ignored.

**Cause:** Rate limiters require a shared state. In a distributed setup, you need Redis-backed caching (`cosec-cocache`).

**Fix:** Add the cocache dependency and configure Redis.

### Path variables not matching

**Symptoms:** `/user/123` doesn't match `/user/{id}`.

**Check:**
1. Use `{varName}` not `:varName` (Spring WebFlux style)
2. Access the variable via `request.path.var.varName` in conditions
3. Ensure the path pattern is correct (no trailing slash mismatch)

### SpEL template not evaluating

**Symptoms:** `#{principal.id}` is treated as a literal string.

**Cause:** SpEL templates use `#{}` syntax. `{}` alone is a path variable, not SpEL.

**Fix:** Use `#{principal.id}` not `{principal.id}`.

### Condition part path is wrong

**Symptoms:** Condition always returns false.

**Valid part paths:**
- `request.path.var.{name}` — path variable
- `request.remoteIp` — client IP address
- `request.origin` — Origin header
- `request.method` — HTTP method
- `request.attributes.{key}` — request attributes
- `request.headers.{name}` — request header
- `context.principal.id` — user ID
- `context.principal.attributes.{key}` — principal attribute

Common mistakes:
- `request.ip` (wrong) → `request.remoteIp` (correct)
- `principal.id` (wrong) → `context.principal.id` (correct)
- `request.pathVariable.id` (wrong) → `request.path.var.id` (correct)

## Step 4: Testing Policies Locally

### Unit test with SimpleAuthorization

```kotlin
@Test
fun `test policy evaluation`() {
    val policyLoader = LocalPolicyLoader("classpath:cosec-policy/test-policy.json")
    val policies = policyLoader.load()

    val evaluator = DefaultPolicyEvaluator(policies)

    val request = mockk<Request> {
        every { path } returns "/api/users/123"
        every { method } returns "GET"
        every { remoteIp } returns "192.168.1.1"
    }

    val principal = mockk<CoSecPrincipal> {
        every { id } returns "user-123"
        every { authenticated } returns true
        every { roles } returns setOf("user")
    }

    val context = mockk<SecurityContext> {
        every { this@mockk.principal } returns principal
    }

    val result = evaluator.evaluate(request, context)
    assertThat(result.authorized).isTrue()
}
```

### Test specific matcher

```kotlin
@Test
fun `test path action matcher`() {
    val factory = PathActionMatcherFactory()
    val matcher = factory.create(Configuration.of("pattern" to "/api/users/*"))

    val request = mockk<Request> {
        every { path } returns "/api/users/123"
        every { method } returns "GET"
    }

    assertThat(matcher.match(request, mockk())).isTrue()
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
| `EXPLICIT_DENY` | `false` | Explicitly denied by a DENY statement |
| `IMPLICIT_DENY` | `false` | No statement matched (default deny) |
| `TOKEN_EXPIRED` | `false` | JWT token has expired |
| `TOO_MANY_REQUESTS` | `false` | Rate limiter exceeded |
