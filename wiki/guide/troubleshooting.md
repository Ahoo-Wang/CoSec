# Troubleshooting

Start from what the response tells you, find out which rule decided, then check the inputs that rule saw.

## Find out what decided

Every decision names the rule that made it. Turn on debug logging:

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: DEBUG   # matched policy statement or role permission
    me.ahoo.cosec.audit: DEBUG                               # audit events (denials are logged at WARN)
```

Each audit event records the principal, the request, the result and its **source**: `ROOT`, `BLACKLIST`,
`GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION`, or `NONE` when nothing matched (see
[Auditing](./operations.md#auditing)). Correlate a response with its event through the `CoSec-Request-Id`
header. With `opentelemetry-support` the same information is on the `cosec.authorize` span.

## By status code

The status codes and their reasons are defined in [Web Integration](./web-integration.md#responses).

| Status | Reason | Look at |
|---|---|---|
| `400` | — | The path contains `.` / `..` segments or a malformed percent-escape. Fix the client. |
| `401` | `Token Expired` / `Token Invalid` | The token, not the policy: see [Unexpected 401](#unexpected-401). |
| `401` | `Implicit Deny` | No token was found, and no rule allows anonymous access to this path. |
| `401` / `403` | `Explicit Deny` | A `deny` statement or permission matched. The debug log names it. |
| `403` | `Implicit Deny` | The caller is authenticated but no rule allowed the request, or a `regular` condition timed out. |
| `429` | `Too Many Requests` | A rate-limit condition: see [Rate limits behave unexpectedly](#rate-limits-behave-unexpectedly). |
| `500` | `Implicit Deny` | An error during authorization. The application log has the stack trace. |

## Unexpected 401

The caller was treated as anonymous.

- **Was a token sent where CoSec reads it?** The `Authorization` header or an `authorization` cookie; the
  query parameter only with `cosec.authorization.allow-query-token=true`. See
  [Authentication](./authentication.md#tokens).
- **`Token Invalid`**: the issuer and this service must share `cosec.jwt.secret` and `cosec.jwt.algorithm`.
  A revoked token is also reported as invalid.
- **`Token Expired`**: refresh it ([Refreshing](./authentication.md#refreshing)), or check the clocks and
  `cosec.jwt.token-validity.access`.

## Unexpected 403

The caller is known, but nothing allowed the request, or something denied it.

- Read the matched rule in the debug log. `Explicit Deny` names the deny that won; deny wins across all
  layers, so a global `allow` cannot override it.
- For `Implicit Deny`, check the rule you expected to match:
  - its **action**: method, trailing slash, `*` (one segment) versus `**` (any number);
  - its **condition** and the principal it saw: roles, attributes and tenant come from the token, so
    changes apply only after a new login or refresh;
  - for principal policies, that the policy id is in the token's `policies`;
  - for role permissions, the `CoSec-App-Id` and `CoSec-Space-Id` headers and the role's grants in that space.
- `inTenant` takes a tenant *kind* (`default`, `user`, `platform`), not a tenant id.

## Request allowed unexpectedly

- **Root principal**: the principal id `cosec` (or the `cosec.root` system property) bypasses all checks
  while `cosec.authorization.root-enabled` is `true`.
- **A broad allow**: look for a global `allow` with `"*"`, `/**` or no condition. Add a `deny`, or narrow the
  allow.
- **A deny that does not match**: its action and its condition must both match. A negated condition is a
  common mistake.

## Policy has no effect

- **Local files are loaded but not used.** Authorization reads the `PolicyRepository`. Set
  `cosec.authorization.local-policy.init-repository=true`, or write the policy to the repository yourself.
- **The policy already exists.** On startup, an existing id is kept unless `force-refresh=true`.
- **The file was skipped.** Invalid files are logged at `ERROR` and skipped
  ([Loading policies](./policies.md#loading-and-generating-policies)). Search the startup log for the file name.
- **Another file has the same id.** Only the first one with a given id is loaded.
- **Wrong policy type.** Only `global` policies apply to everyone; `system` and `custom` policies apply only
  to principals that list them.

## Every request fails with 500

- **Redis is unreachable** and the policy or permission is not in the local cache. This is deliberate: policy
  reads fail closed ([Redis and caching](./operations.md#redis-and-caching)).
- **A condition names an unsupported part.** The log shows `Unsupported part`; see the list of
  [parts](./policies.md#value-conditions). [Validating policies](./policies.md#validating-policies) catches
  this before deployment.
- **A custom matcher or repository throws.** The stack trace names it.

## Rate limits behave unexpectedly

- `rateLimiter` and `groupedRateLimiter` count **per instance**; with N instances the effective limit is N
  times higher. Use the Redis variants for a cluster-wide limit.
- Redis limiters with identical settings **share one quota**, even in different policies
  ([Rate limits](./policies.md#rate-limits)).
- A limit in a **policy-level** condition consumes a permit for every request the policy applies to.
- During a Redis outage, Redis limiters let requests through unless `strictFailure` is `true`.
