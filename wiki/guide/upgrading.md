# Upgrading

What changes between versions and what to do about it. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

## 6.1.1 to 6.1.2

6.1.2 is a security release. Upgrade every 6.x deployment.

### Templated path patterns reject values that change the pattern

A `#{...}` template in a path pattern, such as `/users/#{principal.id}/**`, used to be evaluated into a string that
was then parsed as a whole pattern, so a principal id or attribute containing pattern syntax changed what the
statement matched. Each template value now fills one literal part of the pattern, and a value that is empty, `.`,
`..`, or contains `*`, `?`, `{`, `}`, `\` or the path separator (`/`, or the `separator` option), and, with
`decodeAndParseSegments: false`, `%` or `;` is rejected ([Template values in path patterns](./policies.md#template-values-in-path-patterns)):

- an `allow` statement or permission no longer matches such a principal (`Implicit Deny`);
- a `deny` statement or permission fails such a principal's request closed with an error
  (`RejectedTemplateValueException`, a `500`), whatever its condition;
- a rate limit rule fails such a principal's matching requests closed the same way, and spends no permit;
- a templated matcher built in code with `PathActionMatcherFactory` and used outside a statement fails the same way.
  Code that deserializes statements, permissions or rate limit rules from JSON is unaffected; a custom deserializer
  that builds ALLOW statements itself can call `onRejectedTemplateValue(NO_MATCH)` on the action.

Principals whose ids or attributes contain these characters can no longer be served by templated statements. A
template that produced several segments, such as `#{principal.attributes['home']}/**` with `home` set to
`/users/alice`, is rejected the same way, and a template inside a `{...}` capture, such as `{name:#{...}}`, now
fails when the policy loads. What to do:

1. Find templated path patterns: search policies, app permissions and rate limit rules for `#{` in an `action`.
2. If principal ids or the attributes they use can contain these characters, rewrite the statement as a static
   pattern plus an `eq` condition on the path variable ([example](./policies.md#templates)). `eq` compares strings, so any
   value works.
3. Check that the principals you expect still match
   ([Templated statement ignores one principal or fails with 500](./troubleshooting.md#templated-statement-ignores-one-principal-or-fails-with-500)).

## 6.1.0 to 6.1.1

6.1.1 is a security release. Upgrade every 6.x deployment; there is no change to policies, properties or APIs.

### Security fixes

**Spring MVC authorizes the full path within the application.** 6.1.0 authorized only `servletPath`. With the
`DispatcherServlet` under a prefix (`spring.mvc.servlet.path=/api`, or a servlet registered as `/api/*`),
`servletPath` is `/api` for every request, so a deny on `/api/admin/**` never matched while an allow on `/api/**`
let every request through. The path is now `servletPath` followed by `pathInfo`. Under the default `/` mapping
`pathInfo` is empty, so nothing changes there.

**`..;` no longer hides a path traversal.** On WebFlux and Gateway, `/public/..;/admin` passed the traversal check
(the segment `..;` is not `..`) and could be authorized by a rule on `/public/**`, while a servlet container behind
the gateway drops the `;` parameter and serves `/admin`. `;` path parameters are now removed from the authorized path
on every adapter, and a `.` or `..` segment behind one answers `400`.

**The parser that skips signature checks is used only without authorization.** With `cosec.inject.enabled=true` and
authorization on, which `SecurityContextParser` the filters got depended on the order the two auto-configurations
were registered in; registered the other way round, the parser that does not verify token signatures was used for
authorization. It now exists only with `cosec.authorization.enabled=false`.

### Compatibility

- **Spring MVC under a prefix mapping**: `request.path` now includes `pathInfo`, for example `/api/admin/users`
  instead of `/api`. Rules on `/api/**` keep matching; a rule on the bare `/api`, which in 6.1.0 matched every
  request under the prefix, now matches only `/api` itself and must become `/api/**`. The path stays percent-decoded as in 6.1.0; 7.0 switches to the
  undecoded request URI.
- **`;` path parameters**: `request.path` no longer contains them (`/orders;jsessionid=1/42` is `/orders/42`), and
  paths with a `..;` or `.;` segment answer `400`. Under the default Spring MVC mapping the container had already
  removed them.
- **Inject mode**: an application that enables `cosec.inject.enabled`, leaves `cosec.authorization.enabled` at its
  default `true` and excludes `CoSecAuthorizationAutoConfiguration` no longer gets a `SecurityContextParser` and
  fails at startup. Set `cosec.authorization.enabled=false`, which is what inject mode needs.

## 6.0 to 6.1

### Breaking changes

**Rate limits are rules, not policy conditions.** The `rateLimiter`, `groupedRateLimiter`, `redisRateLimiter` and
`redisGroupedRateLimiter` conditions are removed, together with `TooManyRequestsException`. A policy that still uses
one no longer parses. Local policy files that use them are skipped with an `ERROR` log, and policies stored in
Redis that use them fail their reads, which denies requests. Before upgrading, move each limit into a
[rate limit rule](./rate-limiting.md#rules) following the
[migration table](./rate-limiting.md#migrating-from-rate-limit-conditions). Three behaviors change with it:

- Only requests that authorization allows spend permits. Before, a limit in a policy condition also counted
  requests that another layer then denied.
- Each quota belongs to its rule `id`. Before, Redis limiters with identical settings shared one quota.
- Redis counters move from `<key-prefix>:<settings hash>:…` to `<key-prefix>:<id>:…`, so counters restart. During a
  rolling upgrade, old and new instances count separately.

**Responses can carry rate limit headers.** When a rule matches, responses include `RateLimit-Limit`,
`RateLimit-Remaining` and `RateLimit-Reset`, and `429` responses include `Retry-After`
([Responses](./rate-limiting.md#responses)).

**The `Authorization` bean is decorated.** With rate limiting enabled (`cosec.limiter.enabled`, default `true`)
the auto-configured bean is a `RateLimitingAuthorization` around `SimpleAuthorization`, and with
`micrometer-support` a `MeteredAuthorization` around that. Code that casts the bean to `SimpleAuthorization` must
inject it as `Authorization`, or unwrap `Delegated.delegate`.

**Decision types gained a field.** `AuthorizationDecision` and `SecurityDecision` have a new `rateLimit` property.
Kotlin code that destructures them or calls `copy` by position must be recompiled.

### New features

Everything below is additive. Features that change what a request experiences are off by default.

| Feature | Default | Where |
|---|---|---|
| Rate limit rules: per-IP, per-user or shared quotas, counted in Redis or in memory | on, once a rule file exists | [Rate Limiting](./rate-limiting.md) |
| Explain a decision, for a described principal or a real token or API key | off (`cosec.explain.enabled`) | [Troubleshooting](./troubleshooting.md#explain-a-decision) |
| Shadow mode: try candidate policies and app permissions on real traffic | off (`cosec.shadow.enabled`) | [Policies](./policies.md#trying-changes-in-shadow-mode) |
| `AuthorizationChecker`: check permissions in code, one action or many | always available | [Web Integration](./web-integration.md#checking-permissions-in-code) |
| Object-level rules with `resource.<name>` parts | opt in per policy | [Policies](./policies.md#object-level-rules) |
| `time` condition: validity periods and daily windows, self-expiring grants | opt in per policy | [Policies](./policies.md#time-conditions) |
| Policy history and rollback | off (`cosec.authorization.cache.history.enabled`) | [Policies](./policies.md#policy-history-and-rollback) |
| Declarative policy test cases (`PolicyTestKit`) | library | [Policies](./policies.md#policy-test-cases) |
| Access reviews (`AccessAnalyzer`): who can do what | library | [Operations](./operations.md#access-reviews) |
| API keys for machine clients, in memory or in Redis | off (`cosec.api-key.enabled`) | [Authentication](./authentication.md#api-keys) |
| `cosec.authorization` decision metrics | on with `micrometer-support` and a `MeterRegistry` | [Operations](./operations.md#observability) |

New properties: `cosec.limiter.locations`, `cosec.explain.enabled`, `cosec.shadow.*`, `cosec.metrics.enabled`,
`cosec.api-key.*` and `cosec.authorization.cache.history.*` (see [Configuration](./configuration.md)).

### Upgrade checklist

1. Search your policies, including those stored in Redis, for the four removed condition types, and write the
   equivalent rate limit rules under `classpath:cosec-rate-limit/`.
2. Replace any `TooManyRequestsException` thrown by custom matchers with a rate limit rule.
3. Check that clients and proxies tolerate the new rate limit headers.
4. Replace casts of the `Authorization` bean to `SimpleAuthorization`.
5. Upgrade, then confirm in the startup log that every rate limit rule file loaded. An unreadable rule file stops
   startup.
6. Optionally, add `PolicyTestKit` suites for the decisions you rely on before you change policies again.
