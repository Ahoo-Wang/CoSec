---
name: cosec-troubleshoot
description: Diagnose CoSec authentication and authorization failures, including unexpected 401/403/429 responses, policy loading, JWT rejection, matcher mismatches, Redis limiters, and unexplained access decisions. Do not change behavior unless the user asks for a fix.
---

# CoSec Troubleshooting

Find the first incorrect transition from request parsing to authentication, policy loading, matching, and response mapping. Gather evidence before proposing a policy or code change.

## Start with observable facts

Capture the request method/path, response status and CoSec JSON reason, principal ID/authenticated state, app/space/tenant IDs, effective configuration, loaded policy IDs, and relevant startup/request logs. Redact tokens and secrets.

Enable focused logging:

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: DEBUG
    me.ahoo.cosec.audit: DEBUG
```

`SimpleAuthorization` identifies the matched policy/statement or role/permission. When `AuditingAuthorization` is active, the default sink emits structured deny events at WARN and allow events at DEBUG, including principal, request, decision, and matched rule when available. A custom `Authorization` bean is not wrapped automatically.

## Interpret the status first

| HTTP status | CoSec meaning |
|---|---|
| 400 | Invalid normalized request path |
| 401 | Authorization denied while the parsed principal is anonymous; invalid/expired tokens also fall back to anonymous and preserve the token reason |
| 403 | An authenticated principal was denied, or a regex matcher timed out and failed closed |
| 429 | A local or Redis rate limiter threw `TooManyRequestsException` |
| 500 | Unexpected authorization error; inspect the server exception |

The authorization result alone does not choose 401 versus 403; the filter maps a denied anonymous principal to 401 and a denied authenticated principal to 403.

## Decision order

`SimpleAuthorization` evaluates:

1. Root principal ID (`cosec` by default, overridden by JVM system property `cosec.root`) → allow.
2. Blacklist → explicit deny.
3. Global policies.
4. Principal-attached policies.
5. Role permissions for `request.appId` + `request.spaceId`.
6. No match → implicit deny.

Each policy tier evaluates matched policy-level conditions once, pools all statements, checks every deny before any allow, and stops at the first tier with a result. A deny only overrides allows in the same tier; a global allow prevents later principal or role denies from running.

## Symptom guide

### Unexpected 401

- Check whether the request actually carried a bearer token and whether parsing produced an authenticated principal.
- Match `cosec.jwt.algorithm` and secret to the issuer; only HMAC256/384/512 are supported.
- Check expiration and the response reason (`Token Expired` versus `Token Invalid`).
- If revocation is enabled, verify the cache capability supplied a real Redis-backed `TokenStore`; otherwise the starter warns that logout is ineffective.
- If the token is valid but the request remains anonymous, inspect the active `SecurityContextParser` and transport filter.

### Unexpected 403 or missing allow

- Read the debug/audit match. An explicit deny is a matched deny; an implicit deny means no rule in any tier matched.
- Confirm a global policy loaded and its policy-level condition matched.
- Check the action's HTTP method, path, trailing slash, and captured variables.
- Check condition parts and the actual principal roles/attributes/tenant.
- Remember that `inTenant.value` is `default`, `user`, or `platform`, not a tenant ID.

### Request allowed unexpectedly

- Check root bypass first.
- Find the first matched tier. A broad global allow short-circuits stricter principal and role rules.
- Verify the deny action and condition both match; array order cannot make an allow beat a same-tier deny.
- Confirm the intended policy was loaded rather than skipped or shadowed by a duplicate ID.

### Policy file has no effect

- Confirm `cosec.authorization.local-policy.enabled=true` and the file matches `local-policy.locations`.
- Inspect startup errors: `LocalPolicyLoader` logs and skips malformed JSON, missing required fields, unknown matcher types, and constructor-time configuration errors rather than failing the application.
- Deferred matcher errors still load. For example, an unsupported `part` throws during authorization and the transport returns 500; reproduce it with `DefaultPolicyEvaluator` or the failing request.
- Local policies are deduplicated by ID after location expansion; assert that the expected ID and content loaded.
- `LocalPolicyLoader` does not serve authorization decisions directly. Enable `init-repository` or prepopulate `PolicyRepository`; use `force-refresh` only when overwriting repository state is intended.

### Matcher mismatch

- Path variables use `{id}` and conditions read `request.path.var.id`; `:id` and `request.pathVariable.id` are invalid.
- Path SpEL templates use `#{principal.id}`. SpEL condition expressions use root properties such as `context.principal.id`; they are different evaluation roots.
- Valid part prefixes are singular `request.header.`, `request.attributes.`, `request.path.var.`, and `context.principal.attributes.`. Unsupported parts throw `IllegalArgumentException`.
- Regex timeouts fail closed as 403. Simplify or bound the pattern instead of increasing exposure to ReDoS.

### Rate limiting differs across instances

- `rateLimiter` and `groupedRateLimiter` are in-memory and per JVM.
- `redisRateLimiter` and `redisGroupedRateLimiter` are cluster-wide and require cache support plus `StringRedisTemplate`.
- Redis limiter outages fail open by default. Set the matcher's `strictFailure: true` only when availability loss should return 429.
- Check `cosec.limiter.key-prefix`, `windowSeconds`, grouping `part`, and whether multiple policies intentionally share an identical quota configuration.

## Reproduce narrowly

Prefer the smallest existing test layer that crosses the failing boundary:

- matcher behavior: create the real factory with `mapOf(...).asConfiguration()` and test `match`;
- policy parsing: load the exact resource with `LocalPolicyLoader`, assert its ID, then run `DefaultPolicyEvaluator.evaluate`;
- decision order: test `SimpleAuthorization` with the smallest repositories needed;
- transport mapping: use the WebFlux, WebMVC, or Gateway filter tests for 401/403/429 behavior;
- Redis behavior: run the cache integration test with Redis.

In this repository, run a focused class first, for example:

```bash
./gradlew :cosec-core:test --tests "me.ahoo.cosec.policy.DefaultPolicyEvaluatorTest"
```

Use `$cosec-policy-author` to rewrite a confirmed-bad policy and `$cosec-integration` to change confirmed-bad Spring configuration. For diagnosis-only requests, report the root cause and evidence without modifying files.
