---
title: "升级"
description: "CoSec 各版本之间有哪些破坏性变更，以及如何迁移。"
---

# 升级 {#upgrading}

面向把现有 CoSec 部署升级到新版本的团队。每一节列出破坏了什么、要改什么，以及一份检查清单；本页是迁移步骤的唯一出处。每个
版本的发布说明见 [GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 7.0.1 到 7.0.2 {#_7-0-1-to-7-0-2}

### 格式错误的策略会失败，而不是匹配得更宽 {#malformed-policies-fail-instead-of-matching-more}

8.0 同样严格解码策略。7.0.1 及更早版本会接受作者明显写错的策略 JSON，并把它编译成比所写内容 **匹配得更宽** 的规则：

| 写法 | 7.0.1 的理解 |
|---|---|
| `"conditon": { "inRole": { "value": "admin" } }`（字段名拼错） | 没有条件：语句对所有人生效 |
| `"condition": { "all": {}, "inRole": { "value": "admin" } }`（两个键） | `all`：只读取第一个键 |
| `{ "bool": { "and": "inRole" } }`（应为列表的位置写了标量） | 空的 `and`，匹配一切 |
| `"action": { "all": {}, "path": "/admin/**" }`（两个键） | `all` |

现在解码策略、应用权限或限流规则时，以上每一种都会以 `MalformedPolicyException`（一种 `IllegalArgumentException`）失败，
消息会指出 JSON 路径：`Malformed JSON at $.statements[0]: unknown field [conditon] in a statement, expected only [...]`。
会失败的情况：

- **未知字段。** 每种对象只接受其文档列出的字段：

  | 对象 | 字段 |
  |---|---|
  | 策略 | `$schema`、`id`、`name`、`category`、`description`、`type`、`tenantId`、`condition`、`statements` |
  | 语句 | `name`、`effect`、`action`、`condition` |
  | 应用权限 | `$schema`、`id`、`condition`、`groups` |
  | 权限组 | `name`、`description`、`permissions`、`spaced`（在 JSON schema 中；与以前一样接受并忽略） |
  | 权限 | `id`、`name`、`description`、`effect`、`action`、`condition` |
  | 限流规则 | `$schema`、`id`、`action`、`condition`、`key`、`permitsPerSecond`、`windowSeconds`、`strictFailure` |

  匹配器配置（`inRole`、`path` 等键下的对象）不检查未知字段。
- **不是恰好一个键的匹配器对象。** 条件，以及写成对象的动作，必须恰好有一个键，即其类型。`{}` 也会失败。
- **应为列表的位置写了标量。** `bool` 的 `and` 和 `or`、`in` 的 `value`、`time` 的 `daysOfWeek`、`composite`，以及
  `statements`、`groups` 和 `permissions` 都必须是数组。`pattern` 和 `method` 仍接受字符串或数组。
- 任何对象中的 **重复键**，以前会悄悄以最后一个为准。

解码错误从不跳过策略，所以不会丢掉拒绝。错误出现在：

- **本地文件**（`cosec.authorization.local-policy.locations`、`cosec.limiter.locations`、`cosec.shadow.*`）会让启动以
  `Failed to load policy [<file>]: Malformed JSON at ...` 失败（[加载策略文件时启动失败](./troubleshooting.md#startup-fails-loading-a-policy-file)），
  与 7.0 起任何无法解码的文件一样。`PolicyTestKit` 和 `AccessAnalyzer` 的运行也会同样失败。
- **你自己的代码** 在调用 `PolicyRepository.setPolicy` 或写入应用权限之前解码 JSON 时会得到该异常，什么也不会写入。
- **存储在 Redis 中的策略和应用权限** 是按 7.0.1 解码后的结果存储的，因此：
  - 含有标量 `and`、`or`、`in` 的 `value` 或 `daysOfWeek` 的存储副本按原样保留了它，现在会 **读取失败**：每个需要它的请求都以
    `500` 失败（全局策略：所有请求；主体的策略：该主体；应用权限：该应用中到达角色层的请求）；在 `local-policy.init-repository`
    且 `force-refresh=false` 时，启动以 `Init Policy - [<id>] failed.` 失败。含有它的策略历史版本无法回滚。该值会留在 Redis 中，
    直到重新写入（[所有请求都返回 500](./troubleshooting.md#every-request-fails-with-500)）；
  - 字段名拼错或多出键的策略，其存储副本 **可以正常读取**：7.0.1 在存储前就丢掉了它们，所以它仍然是 7.0.1 编译出的、更宽的含义。
    只有你发布时所用的源文件才能看出错误。

升级前要做的：

1. **检查你发布用的源文件**：本地策略、应用权限和限流规则文件，以及应用传给 `setPolicy` 的 JSON 所在之处。对每个文件运行下面的
   `jq` 脚本；它为 7.0.2 拒绝的每个问题输出一行，干净的文件不输出任何内容：

   ```bash
   for f in src/main/resources/cosec-policy/*.json; do jq -r -f cosec-policy-lint.jq "$f" | sed "s|^|$f: |"; done
   ```

   ::: details `cosec-policy-lint.jq`

   ```text
   def keys_not_in($allowed; $path):
     keys[] | select(. as $k | $allowed | index($k) | not) | "\($path): unknown field \(.)";
   def condition($path):
     if type != "object" then "\($path): a condition must be an object"
     elif length != 1 then "\($path): a condition must have exactly one key, has \(keys)"
     else to_entries[0] as $e
       | if $e.key == "bool" and ($e.value | type) == "object" then
           $e.value | (
             (to_entries[] | select(.key == "and" or .key == "or") as $l
               | if ($l.value | type) != "array" then "\($path).bool.\($l.key): must be an array"
                 else $l.value | to_entries[] | .key as $i | .value | condition("\($path).bool.\($l.key)[\($i)]") end))
         elif $e.key == "in" and ($e.value.value | type) != "array" then "\($path).in.value: must be an array"
         elif $e.key == "time" and ($e.value | has("daysOfWeek")) and ($e.value.daysOfWeek | type) != "array" then
           "\($path).time.daysOfWeek: must be an array"
         else empty end
     end;
   def action($path):
     if type == "string" then empty
     elif type == "array" then to_entries[] | .key as $i | .value | action("\($path)[\($i)]")
     elif type == "object" then
       if length != 1 then "\($path): an action must have exactly one key, has \(keys)"
       elif has("composite") then
         if (.composite | type) != "array" then "\($path).composite: must be an array"
         else .composite | to_entries[] | .key as $i | .value | action("\($path).composite[\($i)]") end
       else empty end
     else "\($path): an action must be a string, an array or an object" end;
   def statement($path; $allowed):
     keys_not_in($allowed; $path),
     (if has("action") then .action | action("\($path).action") else empty end),
     (if has("condition") then .condition | condition("\($path).condition") else empty end);
   def items($path):
     if type != "array" then {problem: "\($path): must be an array"}
     else to_entries[] | {path: "\($path)[\(.key)]", value: .value} end;
   def document:
     def rule($p): statement($p; ["$schema","id","action","condition","key","permitsPerSecond","windowSeconds","strictFailure"]);
     if type == "array" then to_entries[] | .key as $i | .value | rule("$[\($i)]")
     elif has("groups") then
       keys_not_in(["$schema","id","condition","groups"]; "$"),
       (if has("condition") then .condition | condition("$.condition") else empty end),
       (.groups | items("$.groups") | if has("problem") then .problem else .path as $g | .value
         | keys_not_in(["name","description","permissions","spaced"]; $g),
           (if has("permissions") then .permissions | items("\($g).permissions")
             | if has("problem") then .problem
               else .path as $pp | .value | statement($pp; ["id","name","description","effect","action","condition"]) end
            else empty end) end)
     elif has("permitsPerSecond") then rule("$")
     else
       keys_not_in(["$schema","id","name","category","description","type","tenantId","condition","statements"]; "$"),
       (if has("condition") then .condition | condition("$.condition") else empty end),
       (if has("statements") then .statements | items("$.statements")
         | if has("problem") then .problem else .path as $sp | .value | statement($sp; ["name","effect","action","condition"]) end
        else empty end)
     end;
   document
   ```

   :::

   该脚本只检查上述形状，不检查匹配器配置和语义。权威的检查是在单元测试中用 7.0.2 加载这些文件
   （[校验策略](../policies/testing.md#validating-policies)）。
2. **检查 Redis 中的内容。** 存储的值是二进制头加上 JSON。以 `cosec` 作为 `cosec.authorization.cache.key-prefix` 时：

   ```bash
   redis-cli --scan --pattern 'cosec:policy:*' | grep -v ':policy:history:' | while read -r key; do
     redis-cli --raw GET "$key" | LC_ALL=C sed '1s/^[^{]*//' | grep '^{' | jq -r -f cosec-policy-lint.jq | sed "s|^|$key: |"
   done
   ```

   对应用权限用 `cosec:app:permission:*` 再执行一次。这里只会出现标量列表。
3. **修正每一处发现**：在源文件中修正，然后在升级之后（或之前：7.0.1 也能读取修正后的版本）用 `setPolicy` 重新发布（或重新写入应用
   权限）。源文件中有拼错字段或多出键的策略，即使 Redis 能正常读取，也要重新发布，让存储副本表达所写的含义。
4. **然后升级**，并在启动日志中确认每个本地文件都已加载。

## 7.0.0 到 7.0.1 {#_7-0-0-to-7-0-1}

Redis `RateLimiter` bean 的名称由 `redisRateLimiter` 改为 `cosecRedisRateLimiter`；旧名称与 Spring Cloud Gateway 使用的名称相同
（[由此导致的启动失败](./troubleshooting.md#startup-fails-with-a-redisratelimiter-bean-conflict)）。它的类型和条件不变，所以按类型注入
`RateLimiter` 或用自己的 bean 替换它都和以前一样。只有按名称 `redisRateLimiter` 引用 CoSec 这个 bean 的代码（`@Qualifier`、
`getBean("redisRateLimiter")`、`@ConditionalOnBean(name = …)`）需要改用 `cosecRedisRateLimiter`。

### 模板化的 path 动作拒绝会改变模式的值 {#templated-path-actions-reject-values-that-change-the-pattern}

6.1.2 和 8.0 中同样适用。`path` 动作中的 `#{...}` 模板（例如 `/users/#{principal.id}/**`）以前会先求值成字符串，再整体解析为模式，所以含有模式语法的
主体 id 或属性会改变语句匹配的范围。现在每个模板值只填充模式中的一段字面量，值为空、`.`、`..`，或含有 `*`、`?`、`{`、`}`、`\` 或路径分隔符（`/`，或 `separator` 选项），或在 `decodeAndParseSegments: false` 时含有 `%` 或 `;` 时会被拒绝
（[`path` 动作中的模板值](../policies/language.md#template-values-in-path-actions)）：

- `allow` 语句或权限不再匹配这样的主体（`Implicit Deny`）；
- 无论条件如何，`deny` 语句或权限都会让这样的主体的请求以错误失败关闭（`RejectedTemplateValueException`，`500`）；
- 限流规则同样失败关闭，且不消耗任何许可：值在匹配路径之前就被拒绝，所以到达模板化拒绝或限流规则的每个请求都会失败；
- 用 `PathActionMatcherFactory` 在代码中构建、不在语句中使用的模板化匹配器同样失败。从 JSON 反序列化语句、权限或限流规则的
  代码不受影响；自己构建 ALLOW 语句的自定义反序列化器可以对动作调用 `onRejectedTemplateValue(NO_MATCH)`。

id 或属性含有这些字符的主体不再能使用模板化语句。产生多个路径段的模板，例如 `home` 为 `/users/alice` 时的
`#{principal.attributes['home']}/**`，同样会被拒绝；放在 `{...}` 捕获内部的模板（例如 `{name:#{...}}`）现在会在加载策略时失败。
需要做的：

1. 找出模板化的`path` 动作：在策略、应用权限和限流规则的 `action` 中搜索 `#{`。
2. 如果主体 id 或模板用到的属性可能含有这些字符，把语句改写为静态模式加上对路径变量的 `eq` 条件（[示例](../policies/language.md#template-values-in-path-actions)）。
   `eq` 比较字符串，任何值都可以。
3. 确认预期的主体仍然匹配（[模板化语句忽略某个主体或返回 500](./troubleshooting.md#templated-statement-ignores-one-principal-or-fails-with-500)）。

## 6.1 到 7.0 {#_6-1-to-7-0}

7.0 在多处收紧了授权：在这些地方，6.x 可能会误放行请求。它还迁移到了 [CoCache 6.6](https://cocache.ahoo.me/guide/migration-6)
和 [CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md)。CoCache 6 以新格式在 Redis 中存值，所以这次
升级需要所有实例短暂停机，并做一次性的数据迁移。不支持滚动升级。

开始之前先读完整节。然后依次完成 [检查清单](#upgrade-checklist)、[迁移](#migrating-the-data)，并在备份被删除之前随时准备好
[回滚](#rollback)。

更严格的授权让 `SimpleAuthorization` 的吞吐比 6.1.0 低 6% 到 9%，每次决策约多 0.2 µs；升级时无需为此做任何修改（[7.0 修复的代价](../architecture/overview.md#what-the-7-0-fixes-cost)）。

### 破坏性变更 {#breaking-changes}

每一行是一项变更；链接指向它的含义和要做的事。

| 变更 | 一句话说明 |
|---|---|
| [缺失的主体策略会失败](#missing-principal-policies-fail) | 主体列出、但仓库中没有的策略 id，现在会让授权以 `500` 失败，而不是被跳过。 |
| [缺失的角色权限会失败](#missing-role-permissions-fail) | 授予角色、但其应用的权限目录中没有的权限 id，现在会让授权以 `500` 失败，而不是被跳过。 |
| [Root 默认关闭、只限平台租户、受黑名单约束](#root-is-off-platform-only-and-blacklisted) | `root-enabled` 默认 `false`，root 必须属于平台租户，黑名单先于 root 检查。 |
| [角色授权属于一个应用和一个空间](#role-grants-belong-to-an-app-and-a-space) | 授权移到 `cosec:app:role:permission:<appId>:<roleId>[@<spaceId>]`，请求只看到为其空间写的角色。 |
| [路径变量属于捕获它的语句](#path-variables-belong-to-their-statement) | 条件只能读取它自己的 `path` 动作捕获的变量；其他读取都是加载错误。 |
| [`inRole` 跟随角色进入其空间](#inrole-follows-roles-into-their-space) | 裸的 `inRole` 值也匹配 `role@<请求空间>`；`in`、`spel` 和 `ognl` 仍看到原始字符串。 |
| [无效的本地策略文件会让启动失败](#invalid-local-policy-files-stop-startup) | 无法解析或构建的本地策略文件会让加载失败，而不是被跳过。 |
| [重复的 id 和不存在的位置会让启动失败](#duplicate-ids-and-missing-locations-stop-startup) | 两个文件声明同一个策略 id，或配置的位置匹配不到文件，都会让加载失败。 |
| [限流规则和影子候选同样严格加载](#rate-limit-rules-and-shadow-candidates-load-strictly) | 同样的严格规则适用于 `cosec.limiter.locations` 和 `cosec.shadow.*`，错误类型变为 `IllegalStateException`。 |
| [限流 key 在加载时检查](#rate-limit-keys-are-checked-at-load) | 不支持的 `key` 部分在规则加载时就失败，而不是在每个请求上失败。 |
| [条件的 part 在加载时检查](#condition-parts-are-checked-at-load) | 不支持的条件 `part` 在策略加载时就失败；含有它的已存储副本在读取时失败。 |
| [策略 id 不能以 `history:` 开头](#policy-ids-may-not-start-with-history) | `setPolicy` 会拒绝它们，因为它们会与策略历史共用一个 key。 |
| [OpenAPI 应用 id 替换 `:`](#openapi-app-ids-replace) | 像 `orders:api` 这样的 OpenAPI 标题得到应用 id `orders-api`。 |
| [Spring MVC 按请求 URI 授权](#spring-mvc-authorizes-the-request-uri) | 不再按 `servletPath`：策略能看到 `DispatcherServlet` 的前缀，路径也不再做百分号解码。 |
| [去掉路径参数，并拒绝 `..;`](#path-parameters-are-removed) | 在所有适配器上，`/admin;x` 都按 `/admin` 授权，`/public/..;/admin` 得到 `400`。 |
| [正则超时对匿名调用方返回 `401`](#a-regex-timeout-answers-401-to-anonymous-callers) | 它与其他拒绝一样映射，不再对所有调用方都返回 `403`。 |
| [不匹配的刷新令牌按无效令牌处理](#a-mismatched-refresh-token-is-an-invalid-token) | `refresh` 抛出 `RefreshTokenMismatchException`（一种 `TokenVerificationException`），而不是 `IllegalArgumentException`。 |
| [不支持滚动升级](#no-rolling-upgrade) | 6.x 和 7.0 无法共用缓存：停掉所有 6.x 实例，迁移，再启动 7.0。 |
| [通过 CoSec 写入，而不是直接写 Redis](#write-through-cosec-not-redis) | 用 `SET` 或 `SADD` 写入的值不会被读取；缓存 API 改为 CoCache 6 的 API。 |
| [Redis 7、Lettuce 和更宽的 ACL](#redis-7-lettuce-and-a-wider-acl) | Redis 7.0+（或 Valkey 7.2+），不支持 Jedis 和静态主从，ACL 中需要 `+hkeys +hget`。 |
| [本地副本最多存活 5 分钟](#local-copies-live-at-most-5-minutes) | 每个本地副本的寿命都有上界，由 `expire-after-write` 设置。 |
| [CoSec 自己创建缓存](#cosec-creates-its-caches-itself) | 6.x 的缓存组件 bean 和 `CacheProperties` 的五个 key 前缀已删除；通过 CoCache 调整缓存。 |
| [id 生成器是 `StringIdGenerator`](#id-generators-are-stringidgenerators) | CosId 4 的生成器只实现 `StringIdGenerator`，CoSec 现在接收的就是它。 |
| [未迁移的数据会让启动失败](#unmigrated-data-stops-startup) | 没有 schema 标记 `cosec:meta:schema` 时，Redis 中的 CoSec 数据会让启动以 `CoSecSchemaException` 失败。 |
| [未迁移的数据失败即拒绝](#unmigrated-data-fails-closed) | 6.x 的值读取时以 `500` 失败，且绝不会被删除，无论 `foreign-format` 怎么设。 |
| [全局策略索引会对照策略检查](#the-global-policy-index-is-checked-against-the-policies) | 索引中有 id 但没有内容时以 `500` 失败；索引中不是 `global` 的内容被忽略。 |

### 升级检查清单 {#upgrade-checklist}

以下事项都在 6.x 仍在运行时完成。

1. **Redis。** 确认 Redis 是 7.0 或更高（或 Valkey 7.2 或更高），应用通过 Lettuce 连接它，且 ACL 允许 `+hkeys +hget`（保留
   `+hgetall`）（[详情](#redis-7-lettuce-and-a-wider-acl)）。
2. **策略、应用权限和限流规则。** 在其中（包括存储在 Redis 中的）搜索 `request.path.var.` 和 `PATH_VARIABLES`，修正其作用域
   （[详情](#path-variables-belong-to-their-statement)）。确认每个本地策略文件都能加载、策略 id 在文件之间唯一、每个配置的位置都
   存在（[详情](#duplicate-ids-and-missing-locations-stop-startup)），每个限流 `key` 和每个条件 `part` 都是支持的部分
   （[详情](#rate-limit-keys-are-checked-at-load)、[详情](#condition-parts-are-checked-at-load)），以及没有以 `history:` 开头的策略 id
   （[详情](#policy-ids-may-not-start-with-history)）。
3. **主体。** 找出列出了仓库中不存在的策略 id 的主体（令牌、API key、用户记录），写入那些策略或移除这些 id
   （[详情](#missing-principal-policies-fail)）。
4. **角色。** 决定每个 6.x 角色（和空间）属于哪个应用，并在令牌和 API key 中为每个非默认空间签发 `role@space` 角色
   （[详情](#role-grants-belong-to-an-app-and-a-space)）。审查基于 `context.principal.roles` 的 `in`、`spel` 和 `ognl` 条件，
   它们看到的是原始的 `role@space` 字符串（[详情](#inrole-follows-roles-into-their-space)）。确认授予角色的每个权限 id（`*` 除外）
   都在其应用的权限目录中有定义（[详情](#missing-role-permissions-fail)）。
5. **Root。** 如果依赖 root，显式开启它，并在平台租户中签发它的令牌（[详情](#root-is-off-platform-only-and-blacklisted)）。
6. **OpenAPI。** 如果某个 OpenAPI 标题包含 `:`，让客户端改用新的 `CoSec-App-Id`（[详情](#openapi-app-ids-replace)）。
7. **路径和客户端。** 在设置了 `spring.mvc.servlet.path` 的 Spring MVC 应用中，给策略、应用权限和限流规则的路径加上这个前缀
   （[详情](#spring-mvc-authorizes-the-request-uri)）。检查把 `request.path` 当字符串比较的条件（[详情](#path-parameters-are-removed)），
   以及把 `401` 当作"需要重新登录"的客户端（[详情](#a-regex-timeout-answers-401-to-anonymous-callers)）。
8. **代码。** 把直接写 Redis 的应用权限和角色授权改为通过 CoSec 写入（[详情](#write-through-cosec-not-redis)），用
   `CacheSpecCustomizer` 或 `cocache.caches.<CacheName>.*` 替换 6.x 的缓存组件 bean（[详情](#cosec-creates-its-caches-itself)），
   把持有 `CosIdGenerator` 的 `IdGenerator` 声明改为 `StringIdGenerator`（[详情](#id-generators-are-stringidgenerators)），
   并在刷新调用外捕获 `TokenVerificationException`（[详情](#a-mismatched-refresh-token-is-an-invalid-token)）。
9. **规划停机。** 准备一个一次性的迁移任务（见下文），并规划一个窗口，期间没有任何 6.x 实例、7.0 实例或其他写入方触碰 CoSec 的 key。
10. **然后：** 停止所有写入方，迁移数据，核对，再启动 7.0（[迁移数据](#migrating-the-data)）。只有在每个应用都迁移完之后，才删除旧的角色授权。

### 迁移数据 {#migrating-the-data}

CoCache 6 读不了 CoSec 6.x 用 CoCache 5 存的值，而 Redis 是策略、应用权限和角色授权的唯一副本（[存储](./storage.md)）。
有两个迁移负责转换它们。两者都是你从 7.0 进程（一个管理应用，或一个使用带 `cache-support` 的 starter 的一次性任务）中调用的
类，而不是 Actuator 端点：会写授权数据的端点本身就必须被保护，而且在迁移结束很久之后仍会暴露在外。

- `CoSecCoCache5Migration` 用 CoCache 的 [`CoCache5Migrator`](https://cocache.ahoo.me/guide/migration-6) 原地转换策略、全局
  策略索引、应用权限和已吊销令牌，并先备份每个原始值。策略历史（`cosec:policy:history:*`）不是 CoCache 数据，不做改动。无论
  执行迁移的进程是否开启令牌吊销，已吊销令牌都会被迁移。
- `RolePermissionKeyMigration` 把角色授权复制到新的、按应用划分的 key，一次一个应用（[迁移角色授权](#migrating-role-grants)）。
  6.x 的 key 保持原样。

::: danger 迁移期间任何东西都不能写入
迁移过程中被修改的 key 会让这次运行中止；在迁移之前由 7.0 写入的 key 会被跳过且 **没有备份**，因此回滚无法恢复它
（[迁移前的写入](#writes-before-the-migration)）。
:::

#### 1. 停止所有写入方 {#_1-stop-every-writer}

停掉所有 6.x 实例，以及其他任何会写 CoSec key 的东西，例如写入策略或授权的管理服务。不要启动任何会写入的 7.0 实例。在迁移
完成之前，[schema 检查](#unmigrated-data-stops-startup) 会阻止普通的 7.0 实例启动。

#### 2. 准备迁移任务 {#_2-prepare-the-migration-job}

在一个一次性任务中执行迁移，这个任务：

- 设置 `cosec.authorization.cache.migration.check-schema=false`，因为 schema 检查会阻止它启动。**绝不要把这个设置部署到处理
  流量的实例上**；
- 不处理任何流量；
- 6.x 使用了与当前不同的 key 前缀时，设置 `cosec.authorization.cache.migration.legacy-key-prefix`；6.x 使用了非默认的
  `cocache.redis.missing-guard-sentinel` 时，设置 `cosec.authorization.cache.migration.missing-guard-sentinel`；要把备份放到
  `cocache5-backup:` 以外的地方时，设置 `cosec.authorization.cache.migration.backup-prefix`。

6.x 的 key 前缀要 **与当时的配置完全一致**。6.x 不会去掉末尾的 `:`（`myapp:` 把策略存在 `myapp::policy:` 下），而 7.0 会
（`myapp:policy:`）。使用这样的前缀时，迁移会把每个值复制到它的 7.0 key，而不是原地转换，6.x 的 key 保持不变；等 7.0 运行良好
后自己删除它们，但 `myapp::policy:history:*` 除外，7.0 仍在使用它。key 前缀改变时，策略历史不会被移动：7.0 把修订记录在
`<new prefix>:policy:history:*` 下，6.x 的修订留在旧前缀下。

迁移用 CoSec 自己的缓存声明写入值，不经过应用的 `CacheSpecCustomizer`。修改了某个 CoSec 缓存编解码器的定制器，会让迁移后的值
对该缓存不可读：它的读取会失败（`500`），而不会被读成不存在。

#### 3. 先演练，再迁移 {#_3-dry-run-then-migrate}

在所有写入方都停止后，先做一次演练（dry run），它读取并解码每个值，但不写入任何东西；然后执行迁移，它在最后写入 schema 标记
（`cosec:meta:schema`）：

```kotlin
@Bean
fun migrateCoSecData(migration: CoSecCoCache5Migration) = ApplicationRunner {
    log.info { "dry run: ${migration.dryRun()}" }
    log.info { "migrated: ${migration.migrate()}" }
}
```

阅读每次的报告：按缓存列出 `read`、`written`、`skipped`（已迁移）和 `negatives`（缓存未命中，迁移为缓存未命中）的 key 数。

- 无法解码的值会让运行以 `CoCache5MigrationException` 中止，并给出该 key。修正后重新运行。
- 迁移过程中过期的已吊销令牌也会让运行中止（"the key changed"），令牌寿命短时更容易发生。重新运行即可。
- 已迁移的 key 会被跳过，所以可以重复运行。

#### 4. 迁移角色授权，每个应用一次 {#_4-migrate-role-grants-once-per-app}

为每个应用运行 `RolePermissionKeyMigration.migrate`，先演练（[迁移角色授权](#migrating-role-grants)）。

#### 5. 核对 {#_5-verify}

检查报告，然后通过 7.0 进程读取几个策略、全局策略和授权，例如用 [解释](../integration/checks.md#explaining-a-decision)。仍以
`ForeignFormatException` 失败的读取，会给出一个没有被迁移的 key。

#### 6. 启动 7.0 {#_6-start-7-0}

启动 7.0 实例，**不要** 带 `check-schema=false`。本地策略文件只在其 id 尚不存在时才在启动时写入 Redis（`force-refresh=false`），
所以启动会先读取已存储的副本。如果这个副本读不了，因为它没有被迁移，或不再通过 7.0 的 [路径变量](#path-variables-belong-to-their-statement)
检查，启动就会失败（[故障排查](./troubleshooting.md#startup-fails-reading-a-stored-policy)）。

#### 7. 清理 {#_7-clean-up}

等 7.0 运行良好后：

- 用 `CoSecCoCache5Migration.dropBackups()` 删除备份。之后就无法再 [回滚](#rollback)；
- 用 `RolePermissionKeyMigration.deleteLegacy()` 删除 6.x 的角色授权 key，**只能在每个应用都运行过 `migrate` 之后**：它删除的是
  所有应用的旧授权，而不只是最后迁移的那个应用。先审查每份报告的 `skipped` 和 `conflicts`，再把 `deleteLegacy(dryRun = true)`
  列出的 key 与报告中迁移的 key 对比；
- 6.x 的 key 前缀以 `:` 结尾时，自己删除 6.x 的 key，但 `<prefix>:policy:history:*` 除外。

### 迁移角色授权 {#migrating-role-grants}

这是 [迁移数据](#migrating-the-data) 的第 4 步。6.x 的授权没有记录它属于哪个应用，所以 CoSec 无法自行迁移：由你指定目标应用，
不会把任何东西复制到所有应用。复制由 `RolePermissionKeyMigration`（`cache-support` 下的一个 bean）完成。

1. 决定每个 6.x 角色（和空间）属于哪个应用。几个应用的角色共用旧 key 空间时，用 `roleIds` 参数把它们分开。
2. 在令牌和 API key 中，为每个非默认空间以 `role@space` 签发角色。
3. 在没有实例处理流量时，从连接同一个 Redis 的 7.0 进程（例如管理应用或一次性任务）为每个应用运行一次迁移。先演练，阅读报告：

   ```kotlin
   @Bean
   fun migrateRoleGrants(migration: RolePermissionKeyMigration) = ApplicationRunner {
       val report = migration.migrate(appId = "order-app", dryRun = true) // then dryRun = false
       log.info { "migrated=${report.migrated} conflicts=${report.conflicts} skipped=${report.skipped}" }
   }
   ```

   写入经过 `RolePermissionCache`，所以运行中的实例会丢弃它们的本地副本。运行是幂等的：已迁移的授权报告为 `unchanged`；目标中
   已有不同授权时报告为冲突（conflict）并保留目标。保存缓存未命中的 key、不是集合的 key，以及角色或空间有歧义的 key（多于一个
   `@`，或某部分为空）报告为 `skipped`。
4. 当每个实例都运行 7.0，且 **每个** 应用的授权都已迁移并核对后，用 `deleteLegacy()` 删除旧 key。它删除所有旧 key，不论原本属于
   哪个应用，所以放到最后：审查每份报告的 `skipped` 和 `conflicts`，然后运行 `deleteLegacy(dryRun = true)`，把它列出的 key 与
   报告中迁移的 key 对比。在此之前，回滚到 6.1 仍能找到它们。

在某个角色的授权迁移到某个应用之前，依赖这些授权的请求在该应用中会被拒绝。

- **首次运行后又通过 6.x 修改了授权**（发生在回滚期间：不支持滚动升级）：用 `overwrite = true` 重新运行迁移。它会改写那些旧
  key 中授权不同的目标，但不会删除 6.x 已删除的授权（旧 key 已不存在，或保存的是缓存未命中）；这些要通过 `RolePermissionCache`
  删除。
- **lease 标记。** 读取一个还没有 7.0 授权的角色时，会在它的新 key 上留下一个短暂的 lease 标记（约 10 秒），演练时也会。它不含
  授权，会自行过期。

### 迁移前的写入 {#writes-before-the-migration}

迁移完成之前任何东西都不能写入，schema 检查也会阻止 7.0 实例在此之前启动。仍然写入的进程（比如迁移任务本身）只受到部分检查：

- **策略发布会失败，但不完全。** 每次 `setPolicy` 都会更新全局策略索引，而在索引仍是 6.x 原生 SET 时，CoCache 6 不会覆盖它：
  发布以 `CacheWriteException` 失败，原因为指出索引 key（`cosec:global:policy`）的 `ForeignFormatException`。索引保持原样，
  迁移仍会转换它。`setPolicy` 以这个错误结束（`force-refresh=true` 的本地策略会让启动失败，见
  [故障排查](./troubleshooting.md#publishing-a-policy-fails-before-the-migration)），请求仍以 `500` 被拒绝。
- **其他写入不受检查。** 全局策略的内容（在索引之前写入）和应用权限会以 7.0 格式替换其 key 上的 6.x 值。迁移随后会跳过这个
  key，并且 **不保留它的备份**，所以回滚无法恢复它的 6.x 值。
- **角色授权** 写到新 key，在迁移之前写入的授权会在那里被报告为冲突。

### 回滚 {#rollback}

在备份被删除之前，可以恢复到 6.1：

1. 停止所有 7.0 实例和所有写入方。
2. 调用 `CoSecCoCache5Migration.rollback()`。存在没有备份的 key 时它会拒绝执行（见下文）。
3. **在不接流量的情况下** 启动 6.1。
4. 通过 6.1 重新写入 7.0 创建的内容（见下文）。
5. 然后才让 6.1 处理流量。

回滚第一步就删除 schema 标记，所以在恢复后的数据上启动的 7.0 实例会拒绝启动；然后恢复迁移时的快照：

- **已迁移的 key**（策略、应用权限、已吊销令牌、全局策略索引）从备份恢复其 6.x 值：类型、内容和剩余寿命。7.0 对它们所做的修改
  会丢失，7.0 设为全局的策略会离开索引。
- **没有备份的 key** 是 7.0 在迁移之后创建的 key（例如新的策略、应用权限或吊销记录），以及它在 [迁移之前](#writes-before-the-migration)
  写入的 key。它们保持 7.0 格式。6.1 不会因它们失败：CoCache 5 把解码不了的值读成不存在，并 **用缓存未命中覆盖它**。于是 7.0
  创建的拒绝策略会悄悄失效，7.0 吊销的令牌会重新被接受。

  ::: danger 没有备份的 key
  `rollback()` 会列出这些 key（`keysWithoutBackup()`），只要还有这样的 key，就以 `KeysWithoutBackupException` 拒绝执行。审查
  它们，调用 `rollback(allowKeysWithoutBackup = true)`，然后在 6.1 处理流量之前，通过 6.1 重新写入那些策略和应用权限，并重新
  吊销那些令牌。
  :::

- **6.x 的 key 前缀以 `:` 结尾。** 7.0 使用的副本被删除，6.1 读取它自己从未被修改过的 key。7.0 在自己前缀下创建的 key 对 6.1
  不可见，所以它们也必须通过 6.1 重新写入。
- **角色授权。** 6.x 的角色授权 key 从未被修改（除非运行过 `deleteLegacy()`），所以在 7.0 中增删的授权必须通过 6.1 重做。
- **策略历史** 不会回滚：7.0 记录的修订仍留在其中，所以通过 6.1 恢复某个策略的最新修订，可能会带回 7.0 写的内容。恢复修订之前
  先检查它的内容。

### 常见问题 {#common-questions}

**6.x 和 7.0 的实例能并存吗？** 不能。它们读不了彼此的值和失效消息（[不支持滚动升级](#no-rolling-upgrade)）。

**7.0 以 `CoSecSchemaException` 拒绝启动。** 数据还没有迁移，或已被回滚（[故障排查](./troubleshooting.md#startup-fails-with-cosecschemaexception)）。

**升级后每个请求都得到 `500`。** 有数据没有迁移，或某个全局策略没有内容（[故障排查](./troubleshooting.md#every-request-fails-with-500)）。

**升级后基于角色的请求被拒绝。** 它们的授权没有迁移到请求的应用，或令牌中的角色没有为请求的空间书写（[迁移角色授权](#migrating-role-grants)）。

**能否设置 `cocache.defaults.foreign-format=reclaim` 来清掉旧值？** 它对 CoSec 的缓存不起作用，也不应该起作用：没有加载器时，
回收会删掉唯一的副本（[未迁移的数据失败即拒绝](#unmigrated-data-fails-closed)）。

**能否手工写入 schema 标记？** 可能还有 6.x 数据时不能。标记告诉 7.0 每个值都是 7.0 布局；位于 7.0 不读的 6.x key 下的数据会被
读成不存在。

**迁移完一个应用后能运行 `deleteLegacy()` 吗？** 不能：它会删除所有应用的旧授权。放到最后运行（[清理](#_7-clean-up)）。

**我已经在测试环境中让 7.0 连过这个 Redis。** `migrate()` 会跳过已迁移的数据并写入标记
（[故障排查](./troubleshooting.md#startup-fails-with-cosecschemaexception)）。

### 授权与加载的变更 {#authorization-and-loading-changes}

[总表](#breaking-changes) 中授权和加载相关各行的含义，以及要做的事。

#### 缺失的主体策略会失败 {#missing-principal-policies-fail}

当主体列出的策略 id 在 `PolicyRepository` 中查不到时（已删除、从未写入、从 Redis 中丢失），`SimpleAuthorization` 现在以
`MissingPolicyException` 失败，管道把它映射为 `500`。以前这个 id 会被跳过，由其余策略决定：当缺失的策略是一条拒绝（比如封禁
账户的拒绝）时，一条全局允许就会放行请求。对于这样的主体，`AuthorizationChecker` 返回 `false`，解释结果报告 `500` 并带有
`MISSING_POLICY` 警告。

升级之前，找出列出了仓库中不存在的策略 id 的主体（令牌、API key、用户记录），写入这些策略或移除这些 id。
`PolicyRepository.getPolicies` 本身没有变化：它仍返回找到的策略，所以自定义仓库无需修改。

#### 缺失的角色权限会失败 {#missing-role-permissions-fail}

授予角色的权限 id 在其应用的权限目录中没有定义时（已从目录中删除，或 id 写错），`SimpleAuthorization` 现在会以
`MissingPermissionException` 失败，管道将其映射为 `500`。以前，这个 id 会被跳过：如果它指向一条拒绝权限，全局或主体策略中的允许就会放行请求。
对在该应用和空间中持有这个角色的主体，只要角色层被评估、且在此之前没有拒绝匹配，请求就会失败：应用的条件不匹配时，这一层不适用；
已被某条拒绝（前面某一层的，或角色层中更靠前的）匹配的请求仍然被拒绝。`*` 仍然表示授予全部权限。`AuthorizationChecker` 返回 `false`；
解释结果会给出 `500` 并带有 `MISSING_PERMISSION` 警告；影子模式中去掉了某个已授予 id 的候选应用权限会报告 `CANDIDATE_ERROR`。
`AccessAnalyzer` 和 `PolicyTestKit` 在构建或运行时拒绝这样的授权；测试套件的 `roles` 在每个应用中都会授予，因此每个应用都必须定义这些权限
（只属于一个应用的 id 请用 `appRoles`）。`AppRolePermission.rolePermissionIndexer` 的默认实现也会抛出同一个异常。

升级之前，对照每个应用的权限目录检查其角色授权，补上缺失的权限，或从授权中移除这个 id
（[把权限授予角色](../policies/role-permissions.md#granting-permissions-to-roles)）。

#### Root 默认关闭、只限平台租户、受黑名单约束 {#root-is-off-platform-only-and-blacklisted}

以前，任何 id 为 `cosec`（或系统属性 `-Dcosec.root` 的值）的主体都会绕过所有检查，包括黑名单，且
`cosec.authorization.root-enabled` 默认为 `true`。如果某个用户租户的身份提供方允许用户自选 id `cosec`，这个用户就获得了整个平台
的访问权。现在：

- `cosec.authorization.root-enabled` 默认为 `false`：除非你开启，否则没有主体是 root。
- 开启后，`RootPrincipalMatcher.DEFAULT` 只在主体 id 等于 root id **且** 租户为平台租户（`(platform)`，
  `Tenant.PLATFORM_TENANT_ID`）时才把它当作 root。用户租户中、默认租户中或没有租户的主体，即使 id 是 root id，也是普通主体。
- 黑名单先于 root 检查，所以来自 root 的被拉黑请求会被拒绝。在解释结果中，`BLACKLIST` 层现在排在 `ROOT` 之前，`ExplainLayer`
  也按这个顺序列出。
- 直接构造 `SimpleAuthorization`、`AuthorizationExplainer` 或 `ShadowAuthorization` 的代码也默认使用 `RootPrincipalMatcher.NONE`，
  与 starter 和 `PolicyTestKit` 一样：不传 `rootPrincipalMatcher` 参数时，没有主体是 root。

如果依赖 root，设置 `cosec.authorization.root-enabled=true`，并以 `tenantId` `(platform)` 签发根主体的令牌。直接构造这些类的
代码传入 `rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT`，`PolicyTestKit` 则把它作为 `run` 的 matcher 参数。如果 root 必须
以别的方式工作，提供你自己的 `RootPrincipalMatcher` bean。

#### 角色授权属于一个应用和一个空间 {#role-grants-belong-to-an-app-and-a-space}

在 6.x 中，角色授权按角色和空间存储，不含应用，而应用（`CoSec-App-Id`）和空间（`CoSec-Space-Id`）都由客户端选择。因此一条授权
（包括 `*`）在所有定义了相同权限 id 的应用中都生效，修改 `CoSec-Space-Id` 还能拿到另一个空间中同名角色的授权。在 7.0 中：

- 授权按应用、角色和空间作为 key。Redis key 从 `cosec:role:permission:<roleId>[@<spaceId>]` 移到
  `cosec:app:role:permission:<appId>:<roleId>[@<spaceId>]`，`RolePermissionCache` 接收 `AppSpacedRoleId(appId, roleId, spaceId)`
  而不是 `SpacedRoleId`。应用 id 不能包含 `:`，角色 id 不能包含 `@`；OpenAPI 应用权限生成器会替换标题中的 `:`。
- 带 `CoSec-Space-Id: x` 的请求只用主体中写作 `role@x` 的角色评估；不带该头时，只用没有后缀的角色。检查成员关系之后，在令牌和
  API key 中为主体拥有角色的每个空间签发 `role@space`（[角色与空间](../integration/authentication.md#roles-and-spaces)）。
- 授权从不读取 6.x 的 key。在它们被迁移之前，角色没有授权，基于角色的请求被拒绝（失败即拒绝）（[迁移角色授权](#migrating-role-grants)）。
- `AppRolePermissionRepository.getAppRolePermission` 的签名不变。`SimpleAuthorization` 现在传入在该空间生效的角色，且不带
  `@space` 后缀；自定义仓库只能返回为该应用和空间做的授权。
- 解释器增加了 `NO_ROLE_IN_SPACE` 警告。
- `AccessAnalyzer` 和 `PolicyTestKit` 以同样方式限定角色授权。没有后缀的授权角色只在默认空间授权；其他空间的授权要写成
  `role@space`。`whoCan` 取请求所在空间的授权，`grantsOf` 只通过主体在授权所在空间持有的角色到达授权，并接收可选的 `spaceId`，
  `AccessRule` 增加了 `spaceId`。测试套件用 `appRoles` 表示在一个应用中做的授权；`roles` 仍在所有应用中授权。格式错误的角色现在
  会失败，而不是永远匹配不上。

模型本身见 [角色授权范围](../policies/role-permissions.md#role-grant-scope)。

#### 路径变量属于捕获它的语句 {#path-variables-belong-to-their-statement}

以前，`request.path.var.*` 读取的是请求中最后一个匹配的 `path` 动作捕获的内容，可以来自任意语句、策略或层。因此一个条件可能依赖
另一条语句：一条从未匹配的拒绝语句可能让一条 `all` 动作的允许匹配上（加一条拒绝反而扩大了访问），策略条件读到全局层的变量，
没有 `path` 动作的限流规则读到某个策略的变量。现在：

- 在每条语句、权限和限流规则匹配其动作之前，以及在策略和应用权限的条件之前，变量都会被清空，所以条件只能看到它自己的 `path`
  动作捕获的内容；
- 在没有同一语句或规则的 `path` 动作捕获变量的地方读取它们，是一个加载错误 `PathVariableScopeException`：在策略或应用权限的
  `condition` 中，在动作不是 `path` 的语句或权限的条件中，在动作不是 `path` 的限流规则的 `condition` 或 `key` 中，以及在任何
  动作的配置中，例如 `path` 模板 `/orders/#{...PATH_VARIABLES...}`：模板在其动作匹配之前求值，此时变量还不是它自己的
  （[路径变量](../policies/language.md#path-variables)）。

解析这样的策略、应用权限或限流规则会失败，用它构造 `PolicyData`、`AppPermissionData` 或 `RateLimitRule` 也会失败。
`RedisPolicyRepository.setPolicy` 会拒绝它。包含这种写法的本地策略文件会让应用无法启动。已存储在 Redis 中的这类策略读取时失败，
请求以 `500` 被拒绝。

升级之前，在策略、应用权限和限流规则中搜索 `request.path.var.` 和 `PATH_VARIABLES`。把每处引用移到其 `path` 动作捕获了该变量的
语句中，例如把 `"action": "*"` 换成声明该变量的模式，并把策略级条件移到它各语句的条件中。

#### `inRole` 跟随角色进入其空间 {#inrole-follows-roles-into-their-space}

当角色以 `role@space` 签发时，裸的 `inRole` 值现在也匹配主体的 `role@<请求空间>`，所以像 `inRole: suspended` 这样的拒绝在令牌
从 `suspended` 改为 `suspended@team-a` 后仍然匹配。没有后缀的角色仍在每个空间中匹配。写成 `role@space` 的值与主体的角色按字面
比较，与以前相同。以其他方式读取 `context.principal.roles` 的条件（`in`、`spel`、`ognl`）仍看到原始字符串，包括
`suspended@team-a`，一旦令牌带上 `role@space`，它们就不再匹配裸角色名（[匹配角色](../policies/language.md#matching-roles)）。

#### 无效的本地策略文件会让启动失败 {#invalid-local-policy-files-stop-startup}

`LocalPolicyLoader` 以前遇到无法解析或构建的策略文件（JSON 格式错误、未知的匹配器类型、无效的选项）时，只记录日志并跳过，这可能
悄悄丢掉一条拒绝。现在任何这样的文件都会让加载失败（与限流规则文件一直以来的行为一致），错误会给出文件名
（[故障排查](./troubleshooting.md#startup-fails-loading-a-policy-file)）。

#### 重复的 id 和不存在的位置会让启动失败 {#duplicate-ids-and-missing-locations-stop-startup}

`LocalPolicyLoader` 以前遇到两个文件声明同一个策略 id 时，只保留其中一个并悄悄丢掉另一个；对于
`cosec.authorization.local-policy.locations` 中不存在或匹配不到文件的位置，只记录一条告警。两者都可能在不知不觉中丢掉一条拒绝，
例如只存放拒绝的位置被拼错。现在两者都会让加载失败，并给出 id 或位置。默认位置 `classpath:cosec-policy/*-policy.json` 仍可以匹配
不到文件，所以没有本地策略的应用不受影响。升级之前，确认每个配置的位置都存在，且策略 id 在文件之间唯一；通过两个位置到达的同一
个文件只加载一次。

#### 限流规则和影子候选同样严格加载 {#rate-limit-rules-and-shadow-candidates-load-strictly}

`LocalRateLimitRuleLoader` 和 `LocalShadowPolicyProvider` 以前对不存在或匹配不到文件的位置什么也不加载，所以
`cosec.limiter.locations` 中的拼写错误会悄悄删掉它包含的所有配额，`cosec.shadow.locations` 或
`cosec.shadow.app-permission-locations` 中的拼写错误会让影子模式对一个从未评估过的变更报告"没有差异"。现在：

- 这样的位置会让启动失败（`Rate limit rule location [...] matches no file`、`Shadow policy location [...]`、
  `Shadow app permission location [...]`）；只有默认位置（`classpath:cosec-rate-limit/*-rate-limit.json`、
  `classpath:cosec-shadow/*-policy.json`、`classpath:cosec-shadow/*-app-permission.json`）可以匹配不到文件；
- 规则 id、影子策略 id 或影子应用权限 id 被声明两次时，以 `... id [<id>] is declared more than once` 失败并给出文件，异常类型
  现在是 `IllegalStateException`（以前是 `IllegalArgumentException`）；
- 通过两个位置到达的同一个文件只加载一次；以前，被两个重叠位置匹配的规则或候选文件会被当作重复而失败；
- `PolicyTestKit` 和 `AccessAnalyzer.load`（本来就对不存在的位置严格）现在对这些情况抛出 `IllegalStateException` 而不是
  `IllegalArgumentException`，并且在两个文件声明同一个策略或应用权限 id 时也会失败；
- 重复本地策略的错误信息现在为 `Policy id [<id>] is declared more than once`。

#### 限流 key 在加载时检查 {#rate-limit-keys-are-checked-at-load}

不是支持部分的 `key`（例如用 `request.ip` 代替 `request.remoteIp`）以前能加载，然后在每个匹配的请求上以 `500` 失败，而此时更早的
规则已经消耗了许可。现在构造或加载这样的规则会失败（[Key](../policies/rate-limiting.md#keys)）。

#### 条件的 part 在加载时检查 {#condition-parts-are-checked-at-load}

`part` 不受支持的取值条件（`eq`、`in`、`contains`、`startsWith`、`endsWith`、`regular`、`path`），例如把 `request.remoteIp` 写成
`request.ip`，或把 `request.header.x` 写成 `request.headers.x`，以前能加载，然后让每个匹配的请求都以 `500` 失败。现在构建这样的匹配器会失败，
报 `Condition [<type>] part [<part>] is not a supported part`，与限流 key 的做法一致：含有它的本地策略文件会让启动失败，`setPolicy` 会拒绝它，
已存储在 Redis 中的这类策略在读取时失败，所有读取该策略的请求都会以 `500` 被拒绝，而不仅是其语句匹配到的请求。升级之前，在策略、应用权限和
限流规则的条件中搜索 `"part"`，逐个对照[支持的 part](../policies/language.md#parts)。

#### 策略 id 不能以 `history:` 开头 {#policy-ids-may-not-start-with-history}

策略历史把策略 `<id>` 的修订记录存放在 `<prefix>:policy:history:<id>`（以及 `<id>:seq`）下，与策略本身在同一个命名空间中，因此 id 为
`history:<id>` 的策略会与策略 `<id>` 的历史记录共用一个 key（[Redis key](./storage.md#redis-keys)）。`RedisPolicyRepository.setPolicy`
现在会拒绝这样的 id，报 `Policy id [...] must not start with [history:]`；启动时把这样的本地策略写入 Redis 也会失败。`history`、
`history-viewer` 这类 id 不受影响。升级之前请重命名 id 以 `history:` 开头的策略；6.x 迁移会把这类 key 当作策略历史跳过。

#### OpenAPI 应用 id 替换 `:` {#openapi-app-ids-replace}

`OpenAPIAppPermissionGenerator` 从 OpenAPI 标题推导应用 id，而应用 id 不能再包含 `:`。标题包含 `:` 的应用（例如 `orders:api`）得到
id `orders-api`；客户端必须在 `CoSec-App-Id` 中发送新的 id，它的角色授权也必须为新 id 写入。

### 请求与令牌的变更 {#request-and-token-changes}

[表格](#breaking-changes)中每一行请求与令牌变更的含义，以及要做的事。

#### Spring MVC 按请求 URI 授权 {#spring-mvc-authorizes-the-request-uri}

WebMvc 适配器原来使用 `HttpServletRequest.servletPath`，它不含 `pathInfo`。`DispatcherServlet` 映射在前缀下（`spring.mvc.servlet.path=/api`）时，
每个请求都按 `/api` 授权：`/api/admin/**` 上的拒绝永远不会匹配，而 `/api/**` 上的允许会放行所有请求。现在使用去掉上下文路径之后的请求 URI，
与 Spring MVC 的路由和 WebFlux 看到的路径一致（[请求路径](../integration/web.md#the-request-path)）。使用默认的 `/` 映射时，匹配的路径与以前相同，
只是不再做百分号解码：`path` 动作仍像以前一样逐段解码，但把 `request.path` 当字符串比较的条件（`eq`、`regular` 等）现在看到的是 `/a%20b`
而不是 `/a b`。升级配置了 Servlet 路径的应用之前，给它的策略、应用权限和限流规则加上这个前缀。

#### 去掉路径参数，并拒绝 `..;` {#path-parameters-are-removed}

在所有适配器上，`/admin;jsessionid=1` 都按 `/admin` 授权，与 Spring 的路由一致；以前 WebFlux 和 Gateway 原样传递，`request.path` 条件可能匹配不到。
后面跟着路径参数的 `.` 或 `..` 段（`/public/..;/admin`）会被 Servlet 容器当作 `..` 处理，现在像其他路径穿越一样返回 `400`。

#### 正则超时对匿名调用方返回 `401` {#a-regex-timeout-answers-401-to-anonymous-callers}

`regular` 条件超出时间预算时，以前不论调用方是谁都返回 `403`。现在它与其他拒绝一样映射：匿名调用方返回 `401`（令牌校验失败时原因为
`Token Expired` 或 `Token Invalid`），已认证调用方返回 `403`（[响应与状态码](../integration/web.md#responses-and-status-codes)）。
把 `401` 当作"需要重新登录"的客户端，对这类请求也会收到 `401`。

#### 不匹配的刷新令牌按无效令牌处理 {#a-mismatched-refresh-token-is-an-invalid-token}

刷新令牌不是和访问令牌一起签发时，`JwtTokenVerifier.refresh` 以前抛出 `IllegalArgumentException`（`Illegal refreshToken.`），这种不匹配会
表现为意外错误。现在它抛出 `RefreshTokenMismatchException`，与其他令牌失败一样是 `TokenVerificationException`
（[刷新令牌](../integration/authentication.md#refreshing-tokens)）。在刷新调用外捕获 `IllegalArgumentException` 的代码，请改为捕获
`TokenVerificationException`。

### CoCache 6 与 CosId 4 {#cocache-6-and-cosid-4}

CoSec 7.0 运行在 [CoCache 6.6](https://cocache.ahoo.me/guide/migration-6) 和
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md) 之上。CoCache 6 改变了值在 Redis 中的存储方式，
所以这次升级需要短暂停机和一次性的数据迁移（[迁移数据](#migrating-the-data)）。

#### 不支持滚动升级 {#no-rolling-upgrade}

CoCache 6 改变了 Redis 中值的布局和失效通道，所以 6.x 和 7.0 的实例无法共用缓存：彼此都看不到对方的写入。停掉所有 6.x 实例，
迁移数据，再启动 7.0。

策略（`cosec:policy:<id>`）、全局策略索引（`cosec:global:policy`）、应用权限（`cosec:app:permission:<appId>`）和已吊销令牌
（`cosec:token:revoked:<tokenId>`）的 Redis key 保持不变，其中 `cosec` 是
[`cosec.authorization.cache.key-prefix`](./configuration.md#cache)；只有它们的值改变了格式。角色授权移到按应用划分的 key
（[见上文](#role-grants-belong-to-an-app-and-a-space)）。策略历史、API key 和限流计数器不是 CoCache 数据，key 和格式都不变。所有
key 列在 [存储](./storage.md#redis-keys) 中。

#### 通过 CoSec 写入，而不是直接写 Redis {#write-through-cosec-not-redis}

每个 CoCache 6 值都以一个二进制头开头，所以用 `SET` 或 `SADD` 写入的数据（例如管理服务写入的应用权限和角色授权）不会被读取。
用 `PolicyRepository.setPolicy` 写入策略，通过 `AppPermissionCache` 和 `RolePermissionCache` bean 写入应用权限和角色授权。它们的
API 是 CoCache 6 的：

| 6.x（CoCache 5） | 7.0（CoCache 6） |
|---|---|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `cache[key] = value`、`set(key, ttlAt, value)` | `put(key, value)`、`put(key, value, ttl: Duration)` |
| `evict(key)` | `invalidate(key)` |
| `GlobalPolicyIndexCache` 是 `SetElementsCache`：`addElements` / `removeElements` | 它是 `SetCache`：`add` / `remove` |

#### Redis 7、Lettuce 和更宽的 ACL {#redis-7-lettuce-and-a-wider-acl}

CoCache 6 需要 Redis 7.0 或更高（或 Valkey 7.2 或更高），并根据 `spring.data.redis.*` 自建 Lettuce 客户端：不支持 Jedis，静态主从
配置会在启动时失败。最小化的 Redis ACL 还必须允许 `+hkeys +hget`（保留 `+hgetall`）（[Redis 要求](./storage.md#redis-requirements)）。

#### 本地副本最多存活 5 分钟 {#local-copies-live-at-most-5-minutes}

CoCache 6 总会限定本地副本的寿命（`localMaxAge`，默认 5 分钟），之后重新读 Redis；它没有"无限"选项。在 6.x 中，策略或权限的
本地副本会一直存活到某次变更使它失效。`cosec.authorization.cache.policy`、`role` 和 `token` 的 `expire-after-write` 现在设置的就是
这个上界，所以它适用于每个本地副本（[本地缓存设置](./configuration.md#local-cache-settings)）。

#### CoSec 自己创建缓存 {#cosec-creates-its-caches-itself}

缓存不再用 `@EnableCoCache` 声明，6.x 的组件 bean（`PolicyCache.KeyConverter`、`PolicyCache.ClientSideCache`、
`PolicyCache.DistributedCache`，以及其他缓存的同类 bean）已被删除。用 CoCache 的 `CacheSpecCustomizer` bean 或
`cocache.caches.<CacheName>.*` 调整缓存，缓存名为 `PolicyCache`、`GlobalPolicyIndexCache`、`AppPermissionCache`、
`RolePermissionCache` 和 `RevokedTokenCache`。有两项设置仍由 CoSec 决定：存储布局（那里的 `key-prefix` 被忽略），以及对策略和权限
而言的"永不过期，Redis 读不了就失败"，它在所有定制器和配置项之后应用（[优先级](./configuration.md#precedence)）。

`CacheProperties` 删除了 `globalPolicyIndexKey`、`policyKeyPrefix`、`appPermissionKeyPrefix`、`rolePermissionKeyPrefix` 和
`revokedTokenKeyPrefix`。CoCache 自己也重命名或删除了若干 `cocache.*` 配置项（例如 `cocache.redis.strict-failure`）；使用已删除的
配置项会导致启动失败，错误信息会给出替代项。

#### id 生成器是 `StringIdGenerator` {#id-generators-are-stringidgenerators}

在 CosId 4 中，`UuidGenerator` 和 `CosIdGenerator` 只实现 `StringIdGenerator`。`AuditingAuthorization`、`JwtTokenConverter`、
`JustAuthProvider`、`ReactiveRequestParser` 和 `ServletRequestParser` 接收 `StringIdGenerator`，由 starter 注入：存在
`CosIdGenerator` 或其他 id 生成器 bean 时仍会使用它，否则使用 `UuidGenerator`。传入 `IdGenerator` 的代码仍能编译；用 `IdGenerator`
声明来持有 `CosIdGenerator` 的代码必须改为声明 `StringIdGenerator`。如果应用自己也使用 CosId，请按 CosId 的
[迁移指南](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md) 操作。

#### 未迁移的数据会让启动失败 {#unmigrated-data-stops-startup}

7.0 并不总能在 6.x 存放数据的地方读到它：key 前缀以 `:` 结尾时（6.x 存在 `myapp::policy:*`，7.0 读 `myapp:policy:*`）、key 前缀
改过时、或回滚这类迁移之后，7.0 会找不到任何策略、任何全局 `DENY`、任何吊销记录，并把它们读成不存在。所以 starter 在创建任何
CoSec 缓存之前检查 schema 标记 `cosec:meta:schema`。没有标记时，6.x key 前缀（`legacy-key-prefix`，原样配置）或当前前缀下的
CoSec 数据会让启动以 `CoSecSchemaException` 失败；迁移在结束时写入标记。没有标记且没有任何 CoSec 数据时，这是一个新部署，标记会
被写入。检查找不到它不知道的前缀下的 6.x 数据：key 前缀改变时，设置 `cosec.authorization.cache.migration.legacy-key-prefix`
（[Schema 标记](./storage.md#schema-marker)）。

#### 未迁移的数据失败即拒绝 {#unmigrated-data-fails-closed}

7.0 实例读取 CoSec 6.x 写入的值时，读取会失败（`CacheReadException`，原因为 CoCache 的 `ForeignFormatException`），并把该值留在
Redis 中，所以请求以 `500` 被拒绝。每个 CoSec 缓存都保持这种行为，即使设置了 `cocache.defaults.foreign-format=reclaim`：没有加载器
时，回收会删掉策略或权限的唯一副本，也会删掉 6.x 的吊销记录，让对应令牌重新有效。

#### 全局策略索引会对照策略检查 {#the-global-policy-index-is-checked-against-the-policies}

索引和策略内容是两个独立的 Redis key。在 6.x 中，索引中有 id 但没有内容时会被跳过，所以丢失的全局 `DENY` 会悄悄失效；7.0 以
`MissingPolicyException` 让读取失败，请求以 `500` 被拒绝（[故障排查](./troubleshooting.md#every-request-fails-with-500)）。索引中
内容类型不是 `global` 的 id（并发的或只完成一半的重新发布）会被忽略，而不是对所有人生效
（[全局策略索引](./storage.md#the-global-policy-index)）。

## 6.0 到 6.1 {#_6-0-to-6-1}

### 破坏性变更 {#breaking-changes-1}

**限流是规则，而不是策略条件。** `rateLimiter`、`groupedRateLimiter`、`redisRateLimiter` 和 `redisGroupedRateLimiter` 条件已被
删除，`TooManyRequestsException` 也一样。仍使用它们的策略不再能解析：在 6.1 中，使用它们的本地策略文件会被跳过并记录 `ERROR` 日志
（自 7.0 起会让启动失败），存储在 Redis 中的这类策略读取时失败，请求被拒绝。升级之前把每个限制移到一条
[限流规则](../policies/rate-limiting.md#rules) 中：

| 之前（策略语句） | 之后（限流规则） |
|---|---|
| 语句的 `action` | 规则的 `action` |
| 语句的其他条件 | 规则的 `condition` |
| `part` | `key` |
| `permitsPerSecond`、`windowSeconds`、`strictFailure` | 同名字段 |
| `rateLimiter` / `groupedRateLimiter` | 没有 Redis 时规则运行在 `LocalRateLimiter` 上 |
| `expireAfterAccessSecond` | 不再需要：空闲的桶在一小时后丢弃 |

随之改变的三种行为：

- **被拒绝的请求不再消耗许可。** 以前，策略条件中的限流也会计入随后被其他层拒绝的请求。
- **配额属于规则 `id`。** 以前，设置相同的 Redis 限流器共用一个配额。
- **计数器重新开始。** Redis 计数器从 `<key-prefix>:<settings hash>:…` 移到 `<key-prefix>:<id>:…`，旧计数器被弃用并自行过期。
  滚动升级期间，新旧实例分别计数。

**响应可能带有限流头。** 有规则匹配时，响应包含 `RateLimit-Limit`、`RateLimit-Remaining` 和 `RateLimit-Reset`，`429` 响应还包含
`Retry-After`（[响应与响应头](../policies/rate-limiting.md#responses-and-headers)）。

**`Authorization` bean 被装饰。** 开启限流时（`cosec.limiter.enabled`，默认 `true`），自动配置的 bean 是包住 `SimpleAuthorization`
的 `RateLimitingAuthorization`，使用 `micrometer-support` 时外面还有一层 `MeteredAuthorization`。把这个 bean 强制转换为
`SimpleAuthorization` 的代码必须改为以 `Authorization` 注入，或解开 `Delegated.delegate`。

**决策类型增加了字段。** `AuthorizationDecision` 和 `SecurityDecision` 增加了 `rateLimit` 属性。按位置解构它们或调用 `copy` 的
Kotlin 代码必须重新编译。

### 新功能 {#new-features}

以下都是新增功能。会改变请求行为的功能默认关闭。

| 功能 | 默认 | 位置 |
|---|---|---|
| 限流规则：按 IP、按用户或共享的配额，在 Redis 或内存中计数 | 开启，只要存在规则文件 | [限流](../policies/rate-limiting.md) |
| 为描述出来的主体，或真实的令牌或 API key 解释决策 | 关闭（`cosec.explain.enabled`） | [解释一个决策](../integration/checks.md#explaining-a-decision) |
| 影子模式：在真实流量上试验候选策略和应用权限 | 关闭（`cosec.shadow.enabled`） | [影子模式](../policies/testing.md#trying-changes-in-shadow-mode) |
| `AuthorizationChecker`：在代码中检查权限，一个或多个动作 | 始终可用 | [检查权限](../integration/checks.md#checking-a-permission) |
| 使用 `resource.<name>` 部分的对象级规则 | 按策略选用 | [对象级规则](../integration/checks.md#object-level-rules) |
| `time` 条件：有效期、每日时间窗、自动过期的授权 | 按策略选用 | [时间条件](../policies/language.md#time-conditions) |
| 策略历史与回滚 | 关闭（`cosec.authorization.cache.history.enabled`） | [策略历史](./storage.md#policy-history-and-rollback) |
| 声明式策略测试用例（`PolicyTestKit`） | 库 | [策略测试用例](../policies/testing.md#policy-test-cases) |
| 访问审查（`AccessAnalyzer`）：谁能做什么 | 库 | [访问审查](../policies/testing.md#access-reviews) |
| 面向机器客户端的 API key，存于内存或 Redis | 关闭（`cosec.api-key.enabled`） | [API key](../integration/authentication.md#api-keys) |
| `cosec.authorization` 决策指标 | 有 `micrometer-support` 和 `MeterRegistry` 时开启 | [可观测性](./observability.md#metrics) |

新增配置项：`cosec.limiter.locations`、`cosec.explain.enabled`、`cosec.shadow.*`、`cosec.metrics.enabled`、`cosec.api-key.*` 和
`cosec.authorization.cache.history.*`（[配置](./configuration.md)）。

### 升级检查清单 {#upgrade-checklist-1}

1. 在策略中（包括存储在 Redis 中的）搜索这四种已删除的条件类型，并在 `classpath:cosec-rate-limit/` 下写出等价的限流规则。
2. 把自定义匹配器抛出的 `TooManyRequestsException` 替换为限流规则。
3. 确认客户端和代理能容忍新的限流头。
4. 替换把 `Authorization` bean 强制转换为 `SimpleAuthorization` 的代码。
5. 升级，然后在启动日志中确认每个限流规则文件都已加载。读不了的规则文件会让启动失败。
6. 可选：在再次修改策略之前，为你依赖的决策加上 `PolicyTestKit` 测试套件。
