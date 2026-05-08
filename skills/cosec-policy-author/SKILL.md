---
name: cosec-policy-author
description: "Author, validate, and explain CoSec security policy JSON files. Use this skill whenever the user mentions writing, creating, editing, or debugging CoSec policies, policy statements, action matchers, condition matchers, or authorization rules. Also use when users ask about policy JSON format, how to allow/deny specific endpoints, how to set up role-based access, or how to configure rate limiting in CoSec policies."
---

# CoSec Policy Authoring

This skill helps you write, validate, and explain CoSec security policy JSON files. CoSec uses an AWS IAM-like policy model where policies contain statements that define ALLOW or DENY rules matched against requests.

## Policy JSON Structure

A policy file is a single JSON object placed in `src/main/resources/cosec-policy/`. The file naming convention is `*-policy.json`.

```json
{
  "id": "unique-policy-id",
  "name": "Human-readable name",
  "category": "optional-category",
  "description": "What this policy does",
  "type": "global",
  "tenantId": "(platform)",
  "condition": { ... },
  "statements": [ ... ]
}
```

### Fields

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique identifier for the policy |
| `name` | Yes | Human-readable display name |
| `category` | No | Logical grouping label |
| `description` | No | Detailed description |
| `type` | Yes | `global` (applies to all requests), `system` (system-level), or `custom` (user/role-specific) |
| `tenantId` | Yes | Tenant scope. Use `(platform)` for global/system policies |
| `condition` | No | Policy-level ConditionMatcher — if present and doesn't match, entire policy is skipped |
| `statements` | Yes | Array of Statement objects |

## Statement Structure

Each statement defines a single permission rule:

```json
{
  "name": "StatementName",
  "effect": "allow",
  "action": "...",
  "condition": { ... }
}
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `name` | No | — | Descriptive name for the statement |
| `effect` | No | `"allow"` | `"allow"` or `"deny"`. DENY takes precedence over ALLOW |
| `action` | Yes | — | ActionMatcher definition (see below) |
| `condition` | No | — | ConditionMatcher definition (see below) |

## Evaluation Order

1. Policy-level condition checked first — if it doesn't match, the entire policy returns IMPLICIT_DENY
2. **DENY statements evaluated first** — any match returns EXPLICIT_DENY immediately
3. **ALLOW statements evaluated next** — any match returns ALLOW
4. Default: IMPLICIT_DENY

This means you should write DENY rules before ALLOW rules in the statements array for clarity, though the framework handles ordering internally.

## Action Matchers

The `action` field defines which requests a statement applies to. There are several formats:

### Simple string — path pattern
```json
"action": "/api/users"
```
Matches the exact path. Supports Spring path patterns with wildcards and variables.

### String with SpEL template
```json
"action": "/user/#{principal.id}/*"
```
`#{principal.id}` is evaluated at match time against the current security context principal.

### String with path variables
```json
"action": "/user/{id}"
```
Matches path segments. Access the variable in conditions via `request.path.var.id`.

### Array — multiple paths (OR logic)
```json
"action": ["/auth/register", "/auth/login", "/auth/logout"]
```
Matches if ANY path in the array matches.

### Wildcard
```json
"action": "*"
```
Matches all requests. Use with conditions to restrict scope.

### Object — path matcher with options
```json
"action": {
  "path": {
    "method": "GET",
    "pattern": "/api/users/*",
    "options": {
      "caseSensitive": false,
      "separator": "/",
      "decodeAndParseSegments": false
    }
  }
}
```

The `method` field can be a single string or array: `"method": ["GET", "POST"]`.

The `pattern` field can be a single string or array of patterns.

### Object — all matcher with method filter
```json
"action": {
  "all": {
    "method": "GET"
  }
}
```
Matches all GET requests regardless of path.

### Object — composite matcher (OR logic across different matcher types)
```json
"action": {
  "composite": [
    "/api/public/*",
    {
      "path": {
        "method": "POST",
        "pattern": "/api/webhook/*"
      }
    }
  ]
}
```

## Condition Matchers

The `condition` field adds additional constraints beyond path matching. All condition types:

### authenticated — user must be logged in
```json
"condition": { "authenticated": {} }
```

### inRole — user must have the specified role
```json
"condition": { "inRole": { "value": "admin" } }
```

### inTenant — request must be from the specified tenant
```json
"condition": { "inTenant": { "value": "tenant-abc" } }
```

### eq — exact value match
```json
"condition": {
  "eq": {
    "part": "request.path.var.id",
    "value": "#{principal.id}"
  }
}
```

The `part` field is a path expression that extracts a value from the request or security context:
- `request.path.var.{name}` — path variable
- `request.remoteIp` — client IP
- `request.origin` — request origin
- `request.method` — HTTP method
- `request.attributes.{key}` — request attributes (e.g., `request.attributes.ipRegion`)
- `context.principal.id` — current user ID
- `context.principal.attributes.{key}` — principal attributes

The `value` field supports SpEL templates like `#{principal.id}`.

### contains — substring match
```json
"condition": {
  "contains": {
    "part": "request.attributes.ipRegion",
    "value": "上海"
  }
}
```

### startsWith / endsWith — prefix/suffix match
```json
"condition": {
  "startsWith": {
    "part": "request.attributes.ipRegion",
    "value": "中国"
  }
}
```

### in — value must be in a list
```json
"condition": {
  "in": {
    "part": "context.principal.id",
    "value": ["adminId", "developerId"]
  }
}
```

### regular — regex match
```json
"condition": {
  "regular": {
    "negate": true,
    "part": "request.origin",
    "pattern": "^(http|https)://github.com"
  }
}
```
Set `negate: true` to invert the match (matches when regex does NOT match).

### path — path pattern match on arbitrary values
```json
"condition": {
  "path": {
    "part": "request.remoteIp",
    "pattern": "192.168.0.*",
    "options": {
      "caseSensitive": false,
      "separator": ".",
      "decodeAndParseSegments": false
    }
  }
}
```

### bool — boolean logic (AND/OR)
```json
"condition": {
  "bool": {
    "and": [
      { "authenticated": {} }
    ],
    "or": [
      { "in": { "part": "context.principal.id", "value": ["dev1"] } },
      { "path": { "part": "request.remoteIp", "pattern": "10.0.0.*" } }
    ]
  }
}
```
All items in `and` must match. At least one item in `or` must match. Both are optional — you can use just `and`, just `or`, or both.

### spel / ognl — expression-based
```json
"condition": {
  "spel": {
    "expression": "#principal.attributes['vip'] == 'true'"
  }
}
```

### rateLimiter — rate limiting
```json
"condition": {
  "rateLimiter": {
    "permitsPerSecond": 10
  }
}
```

### groupedRateLimiter — grouped rate limiting
```json
"condition": {
  "groupedRateLimiter": {
    "permitsPerSecond": 100,
    "groupKey": "context.principal.id"
  }
}
```

## Common Patterns

### Public endpoints (no auth required)
```json
{
  "id": "public-api",
  "name": "Public API",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "PublicEndpoints",
      "action": ["/auth/login", "/auth/register", "/health"]
    }
  ]
}
```

### Admin-only endpoints
```json
{
  "id": "admin-api",
  "name": "Admin API",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "AdminOnly",
      "action": "/admin/**",
      "condition": { "inRole": { "value": "admin" } }
    }
  ]
}
```

### User can only access their own resources
```json
{
  "name": "OwnResourcesOnly",
  "action": "/api/users/{id}/**",
  "condition": {
    "eq": {
      "part": "request.path.var.id",
      "value": "#{principal.id}"
    }
  }
}
```

### IP whitelist with deny
```json
{
  "name": "BlockExternalIp",
  "effect": "deny",
  "action": "*",
  "condition": {
    "regular": {
      "negate": true,
      "part": "request.remoteIp",
      "pattern": "^(10\\.0\\.0\\.|192\\.168\\.)"
    }
  }
}
```

### CORS origin restriction
```json
{
  "name": "RestrictOrigin",
  "effect": "deny",
  "action": "*",
  "condition": {
    "regular": {
      "negate": true,
      "part": "request.origin",
      "pattern": "^https://(app\\.example\\.com|admin\\.example\\.com)"
    }
  }
}
```

### Rate-limited authenticated API
```json
{
  "name": "RateLimitedApi",
  "action": "/api/**",
  "condition": {
    "bool": {
      "and": [
        { "authenticated": {} },
        { "rateLimiter": { "permitsPerSecond": 10 } }
      ]
    }
  }
}
```

### Health probe (for Kubernetes)
```json
{
  "id": "(health-probe)",
  "name": "Health Probe",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "actuator",
      "action": [
        "/actuator/health",
        "/actuator/health/readiness",
        "/actuator/health/liveness"
      ]
    }
  ]
}
```

## Validation Checklist

When reviewing a policy, check:

1. **DENY before ALLOW** — while the framework handles ordering, writing DENY statements first improves readability
2. **SpEL templates** — `#{principal.id}` is valid; `{principal.id}` is NOT (conflicts with path variables)
3. **Path variables** — use `{varName}` syntax, access via `request.path.var.varName` in conditions
4. **Wildcard with conditions** — `"action": "*"` alone allows everything; always pair with a condition
5. **Negate logic** — `regular` matcher has `negate` field; other matchers don't
6. **Bool structure** — `and` and `or` are sibling fields, not nested
7. **Part paths** — must be valid: `request.*`, `context.principal.*`, `request.path.var.*`, `request.attributes.*`
8. **Policy type** — `global` policies apply to all requests; `custom` policies are attached to specific users/roles
