---
title: "Testing Policies"
description: "Catch policy mistakes before they reach production: validation, test cases, explanations, shadow mode and access reviews."
---

# Testing Policies

For policy authors and reviewers who change policies that are already in use. This page shows how to validate a
policy, pin its behavior with test cases, find out why a request got its decision, compare a candidate against live
traffic in shadow mode, and answer who can do what.

| Question | Tool | Runs |
|---|---|---|
| Does the file load at all? | [Validation](#validating-policies) | unit test |
| Do the decisions I rely on still hold? | [`PolicyTestKit`](#policy-test-cases) | unit test |
| Why did this caller get this decision? | [Explain](#debugging-a-decision-with-explain) | running application |
| What would this change do to real traffic? | [Shadow mode](#trying-changes-in-shadow-mode) | running application |
| Who can reach this endpoint? What can this user reach? | [`AccessAnalyzer`](#access-reviews) | unit test or report job |

## Validating policies

The application already refuses to start on a policy file that cannot be built, a duplicate id or a location that
matches nothing ([Loading policy files](./language.md#loading-policy-files)). Run the same loading in a unit test, so
the mistake fails the build instead of the deployment, and add the checks that loading cannot make:

```kotlin
@Test
fun policiesLoad() {
    val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/*-policy.json")).policies
    assertThat(policies.map { it.id }).contains("orders") // the default location may match nothing
    policies.forEach(DefaultPolicyEvaluator::evaluate)
}
```

`DefaultPolicyEvaluator` runs every condition and action once against a sample request, so configuration errors
that only show at request time, such as a [part](./language.md#parts) outside the list, fail the test. A condition
that a `bool` never reaches with the sample request is not run; [test cases](#policy-test-cases) cover those.
`DefaultAppPermissionEvaluator` does the same for app permissions. The Redis policy repository runs
`DefaultPolicyEvaluator` before it stores a policy, so a policy written at run time is checked the same way.

Rate limit rules are validated by loading them: `LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/*-rate-limit.json")).getRules()`.

## Policy test cases

Write the decisions you rely on as data and run them with `PolicyTestKit`. A suite is a JSON file that names the
policies, app permissions and role grants to load, and lists cases: a principal, a request, and the expected result.

```json
{
  "name": "orders",
  "policies": ["classpath:cosec-policy/*-policy.json"],
  "appPermissions": ["classpath:cosec-policy/*-app-permission.json"],
  "roles": { "clerk": ["order.read"], "clerk@team-a": ["order.read", "order.manage"] },
  "appRoles": { "order-app": { "manager": ["*"] } },
  "cases": [
    {
      "name": "users read their own orders",
      "principal": { "id": "alice" },
      "request": { "path": "/users/alice/orders/42" },
      "expect": { "result": "ALLOW", "policyId": "orders", "statementName": "own-orders" }
    },
    {
      "name": "users cannot read other users' orders",
      "principal": { "id": "alice" },
      "request": { "path": "/users/bob/orders/42" },
      "expect": { "result": "IMPLICIT_DENY" }
    },
    {
      "name": "clerks manage orders only in their space",
      "principal": { "id": "carol", "roles": ["clerk@team-a"] },
      "request": { "path": "/orders/42", "method": "PUT", "appId": "order-app", "spaceId": "team-b" },
      "expect": { "result": "IMPLICIT_DENY" }
    }
  ]
}
```

```kotlin
@Test
fun policies() = PolicyTestKit.run("classpath:cosec-policy-test/*.json").assertPasses()
```

| Field | Meaning |
|---|---|
| `name` | Suite name, shown in failures. |
| `policies`, `appPermissions` | Resource patterns of the files to load. Global policies apply to every case; other policies apply to principals that list them in `policies`. |
| `roles` | Role grants made in every loaded app: role to permission ids, `*` for all. `role@space` grants in that space only, a bare role in the default space only ([Role grant scope](./role-permissions.md#role-grant-scope)). |
| `appRoles` | Grants made in one app: app id to role to permission ids, added to `roles`. The app must be defined by one of `appPermissions`. |
| `cases[].principal` | `id`, and optionally `tenantId`, `roles`, `policies`, `attributes`. |
| `cases[].request` | `path`, and optionally `method` (default `GET`), `appId`, `spaceId`, `headers`, `attributes` and the other request fields of [an explanation](../integration/checks.md#explaining-a-decision). |
| `cases[].expect.result` | `ALLOW`, `EXPLICIT_DENY` or `IMPLICIT_DENY`. |
| `cases[].expect.policyId`, `statementName`, `roleId`, `permissionId` | Optional: also pin the rule that must decide. |

Each case runs through the [explainer](#debugging-a-decision-with-explain) against in-memory repositories, so a
failure says what decided instead:

```text
1 of 3 policy test cases failed:
- orders › users cannot read other users' orders: expected IMPLICIT_DENY, got ALLOW; decided by GLOBAL_POLICY policy [orders] statement [0] 'own-orders'
```

A case also fails when authorization ends in an error, for example a policy id the principal lists but no file
defines, or a matcher that throws. A suite file or a policy file that cannot be loaded, a malformed role, an app in
`appRoles` that no file defines, or a location that matches no suite fails the whole run.

No principal is root in a test run, as in the starter where `cosec.authorization.root-enabled` is off by default: a
case for the root id runs through the policies like any other principal. To test an application that enables root,
pass the matcher: `PolicyTestKit.run(locations, RootPrincipalMatcher.DEFAULT)`. Blacklists and rate limits are not
part of a test run.

## Debugging a decision with explain

The explainer runs the real authorization for one request against a snapshot of the repositories, then reports
which statement or permission decided, which missed on the action and which on the condition. Nothing is spent or
changed. The endpoint, its fields and its warnings are described in
[Explaining a decision](../integration/checks.md#explaining-a-decision); this is how to use it while working on
policies.

1. Turn it on where you debug, as shown in [Explaining a decision](../integration/checks.md#explaining-a-decision),
   never on a public instance without an administrator-only policy.

2. Ask for a principal you describe, to try a role or attribute before any token carries it:

   ```json
   {
     "principal": { "id": "carol", "roles": ["clerk@team-a"] },
     "request": { "path": "/orders/42", "method": "PUT", "appId": "order-app", "spaceId": "team-a" }
   }
   ```

3. Or, to see exactly what a real caller gets, leave out `principal` and pass the caller's credential (token mode).
   It is parsed by the application's own security context parser, revocation checks and API keys included; a
   rejected credential is explained as the anonymous request it becomes, with a `TOKEN_INVALID` warning:

   ```json
   {
     "request": { "path": "/orders/42", "method": "PUT", "appId": "order-app", "headers": { "Authorization": "Bearer eyJ..." } }
   }
   ```

Read `decidedBy` first, then the warnings: `MISSING_POLICY`, `NO_APP_PERMISSION` and `NO_ROLE_IN_SPACE` explain
most unexpected denials. `"exhaustive": true` also evaluates the layers authorization skipped after an explicit deny,
and `"detail": "full"` adds each matcher's configuration. When a case in a suite fails, its result carries the same
explanation (`PolicyTestResult.explanation`).

## Trying changes in shadow mode

A test shows what a change does to the requests you thought of. Shadow mode shows what it does to real traffic
before it takes effect: put the candidate versions of policies or app permissions in shadow files, and CoSec
evaluates each sampled request twice, once with the enforced rules, whose decision is the one returned, and once with
the candidates laid over them. Every request on which the two disagree is reported.

```yaml
cosec:
  shadow:
    enabled: true
    sample-rate: 0.1
```

All properties are in [Configuration](../operations/configuration.md#shadow-mode).

| Candidate file (default location) | Effect |
|---|---|
| a policy, `classpath:cosec-shadow/*-policy.json` | Replaces the enforced policy with the same `id`. A new `id` is added: as a global policy if its type is `global`, otherwise for principals that list it. To try removing a policy, give its candidate no statements. |
| an app permission, `classpath:cosec-shadow/*-app-permission.json` | Replaces the enforced catalog and condition with the same app `id`. Role grants stay as enforced, so a grant naming a permission the candidate removed makes the candidate fail with `CANDIDATE_ERROR`, as it would fail enforced ([Granting permissions to roles](./role-permissions.md#granting-permissions-to-roles)). Role grants themselves cannot be shadowed. |

One policy or app permission per file, in the same format as the enforced ones.

| Diff | Meaning |
|---|---|
| `WIDENED` | Denied today, allowed by the candidates: the change grants access. Review every one before enforcing. |
| `NARROWED` | Allowed today, denied by the candidates: the change revokes access. |
| `CANDIDATE_ERROR` | A candidate failed to evaluate, for example a matcher threw. Enforced, it would deny with an error. |
| `RULE_CHANGED` | Same result, decided by a different rule. Reported only with `report-rule-changes: true`. |

Each diff carries the request (id, tenant, principal, app, method, path) and both outcomes with the rule that decided
them. By default diffs are logged under `me.ahoo.cosec.shadow`, access changes and errors at `WARN`, rule changes at
`INFO`. Declare a `ShadowEventSink` bean to send them elsewhere, or a `ShadowPolicyProvider` bean to supply candidates
from somewhere other than files.

- **Sampling.** `sample-rate` (0 to 1, default `1.0`) is the share of requests, allowed or denied, that are evaluated
  a second time. Each sampled request costs a second evaluation inline, so keep it low on busy services.
- **No effect on the response.** The enforced decision is always returned; shadow errors are only logged. Candidates
  run on a separate security context marked as a policy evaluation, so custom matchers can skip side effects, and
  no rate limit permit is spent.
- **Strict loading.** A candidate left out would report "no difference" for a change it never evaluated. So a shadow
  file that cannot be read or built, two files with the same policy id or app permission id, and a configured
  location that does not exist or matches no file stop the application from starting. Only the two default locations
  may match nothing.

When the diffs look right, move the candidates into the enforced policies and remove the shadow files.

## Access reviews

Audits ask two questions: who can make this request, and what can this principal reach. `AccessAnalyzer` answers both
from the same files the application loads, in a test or a small report job:

```kotlin
val analyzer = AccessAnalyzer.load(
    policyLocations = listOf("classpath:cosec-policy/*-policy.json"),
    appPermissionLocations = listOf("classpath:cosec-policy/*-app-permission.json"),
    // app id to role to permission ids; `role@space` grants in that space only
    roles = mapOf("order-app" to mapOf("manager" to setOf("*"), "clerk" to setOf("order.read"), "clerk@team-a" to setOf("*"))),
)

val paths = analyzer.whoCan(
    EvaluateRequest(path = "/orders/42", method = "DELETE", headers = mapOf(AppIdCapable.APP_ID_KEY to "order-app")),
)
paths.allows // every rule that could allow it
paths.denies // every deny that could stop it

val reachable = analyzer.grantsOf(SimplePrincipal("alice", policies = setOf("ops"), roles = setOf("clerk")))
```

- `whoCan(request)` lists the statements and permissions whose action matches: global policies, policies by id, and
  the grants made in the request's app and space.
- `grantsOf(principal, appId, spaceId)` lists every rule the principal can reach: global policies, the policies it
  lists, and the grants of the roles it holds in each space. `appId` and `spaceId` narrow it to one app or space.
  A principal with `clerk` does not reach the `clerk@team-a` grant ([Role grant scope](./role-permissions.md#role-grant-scope)).

Each `AccessRule` names its `source` (`GLOBAL_POLICY`, `PRINCIPAL_POLICY` or `ROLE_PERMISSION`), the policy and
statement or the app, role, space and permission, the `effect`, the action and condition, and two flags:

- `conditional`: a condition must also match, so the rule applies only to some callers or requests. Conditions are
  reported, not evaluated.
- `templated`: the action depends on the caller, such as `/users/#{principal.id}/**`. Each template is widened to one
  path segment, so the rule is listed whenever some caller could match.

The analyzer works only on the data you give it, and when in doubt it lists a rule rather than leaving it out: a
review must not miss a grant. Loading is as strict as for the application; a malformed role in `roles` fails it.

## Policies in CI

A policy change is a security change. A pipeline that catches mistakes before they reach users:

1. **On every change**, in the unit tests of the repository that holds the files:
   - [load and evaluate](#validating-policies) every policy, app permission and rate limit file with the real
     parser, and assert the ids you expect are there;
   - run the [`PolicyTestKit` suites](#policy-test-cases): one case per allow/deny boundary you rely on, the
     decisive rule pinned for the ones that matter, and a case for every denial a change must not open;
   - snapshot [`whoCan`](#access-reviews) for sensitive endpoints (admin, export, delete) and fail when the list
     of allowing rules changes, so a widening shows up in review.
2. **Optionally, earlier**: validate files against [`schema/`](./language.md#json-schema) in the editor or a
   pre-commit hook. It catches typos, not semantics.
3. **Before enforcing** a change to policies already in use, deploy it as [shadow candidates](#trying-changes-in-shadow-mode)
   and promote it when every `WIDENED` diff is understood and `CANDIDATE_ERROR` is empty.

```kotlin
class PolicyGateTest {
    @Test
    fun filesLoad() {
        LocalPolicyLoader(setOf("classpath:cosec-policy/*-policy.json")).policies.forEach(DefaultPolicyEvaluator::evaluate)
        LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/*-rate-limit.json")).getRules()
    }

    @Test
    fun decisions() = PolicyTestKit.run("classpath:cosec-policy-test/*.json").assertPasses()

    @Test
    fun onlyAdminsDeleteOrders() {
        val analyzer = AccessAnalyzer.load(listOf("classpath:cosec-policy/*-policy.json"))
        val allows = analyzer.whoCan(EvaluateRequest(path = "/orders/42", method = "DELETE")).allows
        assertThat(allows.map { it.policyId to it.name }).containsExactly("admin" to "manage-orders")
    }
}
```

**Next:** publish tested policies to the store, in [Publishing changes](../operations/storage.md#publishing-changes).
