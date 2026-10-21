---
title: "测试策略"
description: "在策略上线之前发现问题：校验、测试用例、explain、影子模式与访问审查。"
---

# 测试策略 {#testing-policies}

面向修改线上策略的策略作者与评审。本页介绍如何校验策略、用测试用例固定它的行为、弄清某个请求为什么得到这个决策、在影子模式下用真实流量对比候选策略，以及回答“谁能做什么”。

| 问题 | 工具 | 在哪里运行 |
|---|---|---|
| 文件能加载吗？ | [校验](#validating-policies) | 单元测试 |
| 我依赖的决策还成立吗？ | [`PolicyTestKit`](#policy-test-cases) | 单元测试 |
| 这个调用方为什么得到这个决策？ | [explain](#debugging-a-decision-with-explain) | 运行中的应用 |
| 这次改动对真实流量有什么影响？ | [影子模式](#trying-changes-in-shadow-mode) | 运行中的应用 |
| 谁能访问这个接口？这个用户能访问什么？ | [`AccessAnalyzer`](#access-reviews) | 单元测试或报表任务 |

## 校验策略 {#validating-policies}

策略文件无法构建、id 重复或位置匹配不到任何文件时，应用本来就不会启动（[加载策略文件](./language.md#loading-policy-files)）。
在单元测试中执行同样的加载，让错误在构建时而不是部署时暴露，并补上加载做不到的检查：

```kotlin
@Test
fun policiesLoad() {
    val policies = LocalPolicyLoader(setOf("classpath:cosec-policy/*-policy.json")).policies
    assertThat(policies.map { it.id }).contains("orders") // the default location may match nothing
    policies.forEach(DefaultPolicyEvaluator::evaluate)
}
```

`DefaultPolicyEvaluator` 用一个示例请求把每个条件和动作都运行一次，所以只在请求时才暴露的配置错误（例如不在列表中的 [part](./language.md#parts)）会让测试失败。
示例请求下 `bool` 执行不到的条件不会运行，这些要靠[测试用例](#policy-test-cases)覆盖。`DefaultAppPermissionEvaluator` 对应用权限做同样的事。
Redis 策略仓库在保存策略之前会运行 `DefaultPolicyEvaluator`，所以运行时写入的策略也会经过同样的检查。

限流规则通过加载来校验：`LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/*-rate-limit.json")).getRules()`。

## 策略测试用例 {#policy-test-cases}

把你依赖的决策写成数据，用 `PolicyTestKit` 运行。测试套件是一个 JSON 文件，指明要加载的策略、应用权限和角色授权，并列出用例：一个主体、一个请求和预期结果。

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

| 字段 | 含义 |
|---|---|
| `name` | 套件名称，出现在失败信息中。 |
| `policies`、`appPermissions` | 要加载的文件的资源模式。全局策略作用于每个用例；其他策略作用于在 `policies` 中列出它们的主体。 |
| `roles` | 在所有已加载应用中做出的角色授权：角色到权限 id，`*` 表示全部。`role@space` 只在该空间授权，不带后缀的角色只在默认空间授权（[角色授权范围](./role-permissions.md#role-grant-scope)）。 |
| `appRoles` | 在某一个应用中做出的授权：应用 id 到角色再到权限 id，叠加在 `roles` 之上。该应用必须由 `appPermissions` 中的某个文件定义。 |
| `cases[].principal` | `id`，可选 `tenantId`、`roles`、`policies`、`attributes`。 |
| `cases[].request` | `path`，可选 `method`（默认 `GET`）、`appId`、`spaceId`、`headers`、`attributes`，以及 [explain](../integration/checks.md#explaining-a-decision) 的其他请求字段。 |
| `cases[].expect.result` | `ALLOW`、`EXPLICIT_DENY` 或 `IMPLICIT_DENY`。 |
| `cases[].expect.policyId`、`statementName`、`roleId`、`permissionId` | 可选：同时固定必须做出决策的规则。 |

每个用例都通过 [explain](#debugging-a-decision-with-explain) 在内存仓库上运行，所以失败时会说明实际是谁做出的决策：

```text
1 of 3 policy test cases failed:
- orders › users cannot read other users' orders: expected IMPLICIT_DENY, got ALLOW; decided by GLOBAL_POLICY policy [orders] statement [0] 'own-orders'
```

授权以错误结束时用例也会失败，例如主体列出了某个策略 id 但没有文件定义它，或者某个匹配器抛出异常。套件文件或策略文件无法加载、角色格式错误、
`appRoles` 中的应用没有文件定义、或者位置匹配不到任何套件，都会让整次运行失败。

测试运行中没有主体是 root，与 starter 中 `cosec.authorization.root-enabled` 默认关闭一致：针对 root id 的用例和其他主体一样经过策略。
要测试开启了 root 的应用，传入匹配器：`PolicyTestKit.run(locations, RootPrincipalMatcher.DEFAULT)`。测试运行不包含黑名单和限流。

## 用 explain 调试决策 {#debugging-a-decision-with-explain}

explain 针对仓库快照为一个请求运行真正的授权，然后报告哪条语句或权限做出了决策、哪些在动作上未命中、哪些在条件上未命中。不会消耗或改变任何东西。
端点、字段和警告在[解释决策](../integration/checks.md#explaining-a-decision)中说明；这里介绍编写策略时如何使用它。

1. 在调试的环境中开启它；在公开实例上，必须有只允许管理员访问的策略：

   ```yaml
   cosec:
     explain:
       enabled: true
   management:
     endpoints:
       web:
         exposure:
           include: cosecExplain
   ```

2. 对一个你描述的主体提问，在任何 Token 携带某个角色或属性之前先试一试：

   ```json
   {
     "principal": { "id": "carol", "roles": ["clerk@team-a"] },
     "request": { "path": "/orders/42", "method": "PUT", "appId": "order-app", "spaceId": "team-a" }
   }
   ```

3. 或者，要看真实调用方实际得到什么，省略 `principal`，传入调用方的凭证（Token 模式）。凭证由应用自己的安全上下文解析器解析，包括吊销检查和 API Key；
   被拒绝的凭证会按它实际变成的匿名请求来解释，并带有 `TOKEN_INVALID` 警告：

   ```json
   {
     "request": { "path": "/orders/42", "method": "PUT", "appId": "order-app", "headers": { "Authorization": "Bearer eyJ..." } }
   }
   ```

先看 `decidedBy`，再看警告：`MISSING_POLICY`、`NO_APP_PERMISSION` 和 `NO_ROLE_IN_SPACE` 能解释大多数意外的拒绝。`"exhaustive": true` 还会求值授权在显式拒绝之后跳过的层，
`"detail": "full"` 会加上每个匹配器的配置。套件中的用例失败时，其结果带有同样的解释（`PolicyTestResult.explanation`）。

## 在影子模式下试验变更 {#trying-changes-in-shadow-mode}

测试只能展示改动对你想到的请求有什么影响。影子模式在改动生效之前展示它对真实流量的影响：把策略或应用权限的候选版本放进影子文件，
CoSec 对每个被采样的请求求值两次，一次用正在执行的规则（返回的是它的决策），一次用叠加在其上的候选。两者不一致的每个请求都会被报告。

```yaml
cosec:
  shadow:
    enabled: true
    sample-rate: 0.1
```

全部属性见[配置](../operations/configuration.md#shadow-mode)。

| 候选文件（默认位置） | 效果 |
|---|---|
| 策略，`classpath:cosec-shadow/*-policy.json` | 替换 `id` 相同的正在执行的策略。新 `id` 会被加入：类型是 `global` 时作为全局策略，否则作用于列出它的主体。要试验删除一个策略，给它的候选不写任何语句。 |
| 应用权限，`classpath:cosec-shadow/*-app-permission.json` | 替换应用 `id` 相同的正在执行的目录和条件。角色授权保持不变，所以授予了被候选删除的权限的授权什么也不授予。角色授权本身无法做影子试验。 |

每个文件一个策略或一个应用权限，格式与正在执行的相同。

| 差异 | 含义 |
|---|---|
| `WIDENED` | 现在拒绝，候选允许：这次改动放开了访问。执行前逐一评审。 |
| `NARROWED` | 现在允许，候选拒绝：这次改动收回了访问。 |
| `CANDIDATE_ERROR` | 候选求值失败，例如某个匹配器抛出异常。如果执行它，会以错误拒绝。 |
| `RULE_CHANGED` | 结果相同，但由另一条规则决定。只有设置 `report-rule-changes: true` 时才报告。 |

每条差异都带有请求信息（id、租户、主体、应用、方法、路径），以及两个结果和各自做出决策的规则。默认情况下差异记录在日志 `me.ahoo.cosec.shadow` 下，
访问变化和错误为 `WARN`，规则变化为 `INFO`。声明一个 `ShadowEventSink` Bean 可以把差异发送到别处，声明一个 `ShadowPolicyProvider` Bean 可以从文件以外的地方提供候选。

- **采样。** `sample-rate`（0 到 1，默认 `1.0`）是被第二次求值的请求比例，无论请求被允许还是被拒绝。每个被采样的请求都要在请求内多求值一次，所以繁忙的服务要把它调低。
- **不影响响应。** 返回的永远是正在执行的决策；影子的错误只记录日志。候选在一个单独的、标记为策略求值的安全上下文中运行，所以自定义匹配器可以跳过副作用，也不会消耗任何限流配额。
- **严格加载。** 被漏掉的候选会对一个从未求值的改动报告“没有差异”。所以影子文件无法读取或构建、两个文件的策略 id 或应用权限 id 相同、或配置的位置不存在或匹配不到任何文件，
  都会让应用无法启动。只有两个默认位置允许什么都匹配不到。

差异看起来没问题后，把候选移到正在执行的策略中，并删除影子文件。

## 访问审查 {#access-reviews}

审计关心两个问题：谁能发出这个请求，以及这个主体能访问什么。`AccessAnalyzer` 基于应用加载的同一批文件回答这两个问题，可以在测试或小型报表任务中运行：

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

- `whoCan(request)` 列出动作匹配的语句和权限：全局策略、按 id 引用的策略，以及在请求的应用和空间中做出的授权。
- `grantsOf(principal, appId, spaceId)` 列出主体能到达的每条规则：全局策略、它列出的策略，以及它在各个空间持有的角色的授权。`appId` 和 `spaceId` 把范围收窄到一个应用或空间。
  持有 `clerk` 的主体到达不了 `clerk@team-a` 的授权（[角色授权范围](./role-permissions.md#role-grant-scope)）。

每个 `AccessRule` 给出它的 `source`（`GLOBAL_POLICY`、`PRINCIPAL_POLICY` 或 `ROLE_PERMISSION`），策略和语句或者应用、角色、空间和权限，`effect`，动作和条件，以及两个标志：

- `conditional`：还必须匹配某个条件，所以这条规则只对部分调用方或请求生效。条件只报告，不求值。
- `templated`：动作取决于调用方，例如 `/users/#{principal.id}/**`。每个模板被放宽为一个路径段，所以只要某个调用方可能匹配，这条规则就会被列出。

分析器只基于你给它的数据工作，拿不准时它会列出一条规则而不是漏掉它：审查不能漏掉任何授权。加载和应用一样严格；`roles` 中格式错误的角色会让它失败。

## 在 CI 中验证策略 {#policies-in-ci}

策略改动就是安全改动。一个能在问题到达用户之前发现它的流水线：

1. **每次改动时**，在存放这些文件的仓库的单元测试中：
   - 用真正的解析器[加载并求值](#validating-policies)每个策略、应用权限和限流文件，并断言你预期的 id 都在；
   - 运行 [`PolicyTestKit` 套件](#policy-test-cases)：你依赖的每个允许/拒绝边界一个用例，重要的用例固定做出决策的规则，改动不能放开的每个拒绝也要有用例；
   - 为敏感接口（管理、导出、删除）的 [`whoCan`](#access-reviews) 做快照，允许它的规则列表变化时让测试失败，这样放宽会出现在评审中。
2. **可选，更早一步**：在编辑器或 pre-commit 钩子中按 [`schema/`](./language.md#json-schema) 校验文件。它能发现拼写错误，但发现不了语义问题。
3. **在执行之前**，对已在使用的策略的改动，先作为[影子候选](#trying-changes-in-shadow-mode)部署，等每条 `WIDENED` 差异都弄清楚、`CANDIDATE_ERROR` 为空之后再推广。

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
