---
title: "升级"
description: "如何把 CoSec 7.x 部署升级到 8.0：有哪些变化，以及如何迁移数据、代码、配置和客户端。"
---

# 升级 {#upgrading}

面向把 CoSec 7.x 部署升级到 8.0 的团队。本页先给出一份检查清单，再按领域给出细节：数据迁移、8.0 在哪些地方决策不同、响应、认证、
存储、API、属性和可观测性。本页是迁移步骤的唯一出处。每个版本的发布说明见
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 7.x 到 8.0 {#_7-x-to-8-0}

8.0 只支持从 7.x 升级。从 6.x 升级时，先按
[`7.x` 分支上的升级指南](https://github.com/Ahoo-Wang/CoSec/blob/7.x/wiki/operations/upgrading.md)升到 7.x（包括其中的数据迁移），
再回到这里。`cosec-migrate convert` 拒绝不是 7.0 布局的 Redis 数据。

简要地说，有这些不同：

- **策略是文档。** 一份平台文档、每个应用一份应用文档、每个租户一份租户文档，取代 7.x 的策略、全局策略索引、应用权限和角色授权。
  Redis 仍是权威存储；每个实例把文档编译成快照，决策时不再按请求读 Redis。格式见[策略语言](../policies/language.md)。
- **一条决策路径。** 7.x 的 `Authorization` 链已删除。每个适配器（WebFlux、Spring MVC、Gateway）把请求交给同一条协程 pipeline，
  由它认证、解析作用域、决策，并把结果映射为状态码。
- **变更经过控制面。** 写入、校验、模拟、影子、审批、解释和回滚策略都是控制面（`controlSupport`）的操作，而不是应用代码或本地文件。
- **条件是数据。** 一套小的条件语言取代了匹配器工厂、OGNL 和 SpEL。策略中的任何内容都不会执行代码。
- **错误响应是 `application/problem+json`。** 响应不再告诉客户端是否存在 DENY。

### 升级检查清单 {#upgrade-checklist}

1. **确认是 7.x 数据。** Redis 数据必须是 7.0 布局（schema 标记为 `7`）；6.x 部署先升级到 7.x。
2. **找出没有 8.0 形式的内容。** 在策略和代码中搜索：`spel` 与 `ognl` 条件、自定义 `ActionMatcherFactory` 或
   `ConditionMatcherFactory` 类型、动作中的 `#{...}` 路径模板、`regular` 条件、DENY 条件中的 `ignoreCase`、`BlockingAware`、
   `Authorization`/`Statement`/`Policy` 的实现、`CoSecAuthorizationCustomizer`、root（`root-enabled`）、本地影子文件和 `cosecExplain`
   端点。[转换](#migrating-the-data)会报告每一条无法翻译的策略。
3. **用 `cosec-migrate convert` 转换数据**，修正每一个拒绝项，直到转换被接受。对照[8.0 在哪些地方决策不同](#what-8-0-decides-differently)
   阅读 `report.json` 中的语义改动。
4. **更新代码**：删除对[已删除 API](#removed-and-replaced-apis) 的使用，改为注册 8.0 的扩展 bean。
5. **更新配置**：改名或删除每一个 [7.x key](#removed-and-renamed-properties)，并对新属性做出决定。8.0 不读取的 `cosec.*` key 会让启动
   失败，并给出替代它的配置。另外三种常见情况也会让启动失败：WebFlux 或
   Spring MVC 宿主上 `classpath:cosec-routes.json` 没有路由映射（把 `cosec.authorization.routes.location` 设为一份映射，或设为空）；
   使用 JWT 却既没有 `cache-support` 也没有自己的吊销存储（[令牌吊销始终开启](#token-revocation-is-always-on)）；Redis 的驱逐策略不是
   `noeviction` 或读不到（[读不到 Redis 的驱逐策略时启动失败](#startup-fails-when-redis-s-eviction-policy-cannot-be-read)）。
6. **更新客户端和告警**：错误响应体、`reason` 取值和部分状态码变了（[响应与状态码](#responses-and-status-codes)）；审计字段、指标标签和
   span 也变了（[可观测性的变化](#observability-changes)）。
7. **发布**：用 `cosec-migrate publish` 发布转换得到的文档；如果运行控制面，用 `cosec-migrate bootstrap` 绑定它的第一个管理员。
   平台文档发布之前，CoSec 需要决策的每个请求都返回 `503`，就绪状态拒绝流量。
8. **部署 8.0**，然后检查你依赖的请求的决策（控制面的 `simulate` 和 `explain`，见[测试策略](../policies/testing.md)）。
9. **稍后清理。** 8.0 从不读取、修改或删除 7.x 的 key。确定不再需要[回滚](#rollback)之后再删除它们。

### 迁移数据 {#migrating-the-data}

`cosec-migrate` 是 `migration/migrate/` 模块中的一次性命令行工具。没有应用依赖它，starter 也从不依赖它。用你要部署的版本的 CoSec 源码构建它：

```bash
./gradlew :migrate:installDist
migration/migrate/build/install/cosec-migrate/bin/cosec-migrate convert ...
```

它有四个命令。退出码：`0` 完成，`1` 数据被拒绝或写入失败，`2` 用法错误。

| 命令 | 作用 |
|---|---|
| `convert` | 读取 7.x 数据（Redis 或导出的文件），翻译后把 8.0 文档和一份报告写入一个新目录。不向 Redis 写任何东西。 |
| `publish` | 把一次已接受的转换中的文档作为它们的第一个修订写入 Redis，经过控制面的存储。从不触碰 7.x 的 key。 |
| `bootstrap` | 把平台租户的一个主体绑定到 `cosec:platform-admin`，让控制面有人可以写入。 |
| `partition plan` | 分别用 `--partition platform` 和 `by-owner` 转换两次，为每个租户写出一份计划，由控制面提交。不向 Redis 写任何东西。 |

#### 转换 {#convert}

```bash
cosec-migrate convert --from-redis redis://localhost:6379 [--key-prefix cosec] --out ./converted
cosec-migrate convert --from-files ./export --out ./converted
```

| 选项 | 含义 |
|---|---|
| `--from-redis <uri>` | 从这个 Redis 读取 7.x 数据：`--key-prefix` 下的策略、全局策略索引、应用权限和角色授权，以及 schema 标记 `<prefix>:meta:schema`（必须为 `7`）。策略历史（`<prefix>:policy:history:*`）被跳过。 |
| `--from-files <dir>` | 改为读取一份导出：`policies.json`（7.x 策略的数组，每条带 `id`）、`global-policies.json`（全局策略索引中 id 的数组）和 `app-role-permissions.json`（每个应用一项：`{"appPermission": {...}, "grants": {"admin": ["*"], "auditor@s1": ["invoice.read"]}}`；没有应用权限的应用写 `{"appId": "...", "grants": {...}}`）。这三个文件必须存在；`rate-limit-rules.json` 可选。7.x 的本地策略文件用这种方式转换：把它们的内容放进 `policies.json`。 |
| `--key-prefix <p>` | 7.x 的 key 前缀；默认 `cosec`。 |
| `--out <dir>` | 一个不存在或为空的目录。`convert` 从不覆盖。 |
| `--rate-limit-rules <file>` | 7.x 的限流规则（`cosec.limiter.locations`）：一个规则对象，或规则的数组，每条带 `id`。多个规则文件请合并成一个数组。 |
| `--partition platform\|by-owner` | `platform`（默认）把每条策略放进平台文档，任何人都可以附加，与 7.x 相同。`by-owner` 把每条非全局策略放进其 `tenantId` 所指租户的文档（见下文规则 C5）；含 DENY 语句的策略留在平台文档中，并给出警告。 |
| `--root-enabled`、`--root-id <id>`、`--root-expires-at <instant>` | 把 7.x 的 root 转换为 [break-glass 绑定](#root-becomes-break-glass)。`--root-id` 默认为 `cosec`；后两个选项任一出现即隐含 `--root-enabled`。没有 `--root-expires-at` 的 root 会被拒绝。 |

输出目录：

| 文件 | 内容 |
|---|---|
| `platform.json` | 平台文档。 |
| `apps/<appId>.json` | 每个 7.x 应用权限对应一份应用文档：它的权限目录和角色绑定。 |
| `tenants/<tenantId>.json` | 租户文档，只在 `--partition by-owner` 时出现。 |
| `provenance.json` | 每条策略和绑定来自 7.x 数据的哪里。只在转换被接受时写出。 |
| `report.json` | `accepted`、输入的 SHA-256、计数、`rejections`、`warnings`、`semanticChanges`，以及写出的每个文件的 SHA-256。 |

被拒绝的转换只写出 `report.json`。控制台打印同样的计数、警告和拒绝项。

#### 拒绝项 {#rejections}

每个拒绝项给出种类、在 7.x 数据中的位置、一条消息和建议。修正 7.x 数据（或导出），然后转换到一个新目录。

| 种类 | 原因 | 怎么办 |
|---|---|---|
| `SCHEMA_MARKER` | Redis 数据不是 7.0 布局。 | 先升级到 7.x（含它的数据迁移），再转换。 |
| `GLOBAL_INDEX_WITHOUT_CONTENT` | 全局策略索引列出了一条没有内容的策略。7.x 中每个请求都会失败。 | 存入这条策略，或从索引中删除它的 id。 |
| `GLOBAL_TYPE_NOT_INDEXED` | 一条 `GLOBAL` 类型的策略不在全局策略索引中。 | 把它加入索引，或改变它的类型。 |
| `UNDEFINED_PERMISSION` | 角色授权引用了所属应用没有定义的权限（规则 C6）。 | 定义这个权限，或从授权中删除它。 |
| `UNDECODABLE` | 不是合法的 7.x JSON、7.x 自己的模型无法解码，或需要类路径上缺少的类（`ognl` 条件、自定义匹配器）。 | 修正存储的 JSON。自定义匹配器没有 8.0 形式，见 `CUSTOM_ACTION` 和 `CUSTOM_CONDITION`。 |
| `RESERVED_POLICY_ID` | 策略 id 以 `perm:`、`g:`、`r:` 或 `cosec:` 开头，这些前缀留给生成的 id。 | 重命名这条策略，以及引用它的每个附加策略。 |
| `INVALID_ID` | 8.0 无法容纳的 id：应用或空间 id 为 `*` 或空、应用 id 含 `:`、角色 id 含 `@`。 | 重命名。8.0 中 `*` 表示任意应用或空间。 |
| `DUPLICATE_ID` | 导出中同一条策略出现两次，或同一个应用中同一个权限出现两次。 | 只保留一个。 |
| `ROOT_WITHOUT_EXPIRY` | 启用了 root 却没有 `--root-expires-at`。 | 给出过期时间，或不带 root 转换。 |
| `OWNER_TENANT` | 使用 `--partition by-owner`，而策略的 `tenantId` 不指向任何租户。 | 设置它的 `tenantId`，或改用 `--partition platform` 转换。 |
| `CONDITION_NOT_TRANSLATED` | 没有精确翻译的 7.x 内置条件，例如不是字面量、字面量前缀或字面量选择的 `regular`，或者动作不是 `all` 的语句中的 `path` 条件。 | 按消息给出的条件（`eq`、`in`、`startsWith` ……）重写。 |
| `EXPRESSION_CONDITION` | `spel` 或 `ognl` 条件。 | 用[策略语言](../policies/language.md)的条件重写。 |
| `CUSTOM_CONDITION` | 自定义 `ConditionMatcherFactory` 的条件。 | 把它读取的值移进一个 `AttributeProvider`，并在这个属性上写条件（[扩展](../architecture/extending.md)）。 |
| `ACTION_NOT_TRANSLATED` | `#{...}` 路径模板、不是 `/` 的路径 `separator`、成员方法不同的动作数组，或者不是 HTTP token 的方法。 | 见[动作中的路径模板](#path-templates-in-actions)；按方法拆分语句；删除任何请求都不可能有的方法。 |
| `CUSTOM_ACTION` | 自定义 `ActionMatcherFactory` 的动作。 | 改写为 HTTP 路径和方法，或路由映射出的动作。 |
| `DOES_NOT_COMPILE` | 翻译后的语句无法编译，例如超出 8.0 接受范围的路径模式。 | 见消息和[策略语言](../policies/language.md)。 |

警告不会让转换停止。目前的警告有：全局策略索引中类型不是 `GLOBAL` 的策略（按可附加策略转换，因为 7.x 并不把它应用于所有人）；
`by-owner` 时留在平台文档中、含 DENY 语句的租户策略；没有应用权限的应用上的角色授权（7.x 忽略它们，所以被丢弃）。

#### 报告中的语义改动 {#semantic-changes-in-the-report}

`semanticChanges` 按 7.x 数据中的位置，列出 8.0 有意做出不同决策的形态。`change` 是[8.0 在哪些地方决策不同](#what-8-0-decides-differently)
中的规则 id（`C1a`–`C6`、`D1`–`D3`）之一，或者：

- `ASCII-CASE`：带 `ignoreCase` 的 ALLOW 条件。8.0 只折叠 ASCII 字母，所以这条 ALLOW 匹配的写法更少。DENY 条件的值含 ASCII 以外的字母时，
  这个条件改为被拒绝。
- `RATE-LIMIT`：一条 7.x 限流规则，翻译成了平台文档的限流规则（[限流规则变为义务](#rate-limit-rules-become-obligations)）。

逐条阅读。报告中的条目都不要求修改数据，但每一条都可能改变某个响应。

#### 发布 {#publish}

```bash
cosec-migrate publish --to-redis redis://localhost:6379 [--key-prefix cosec] --in ./converted \
  [--resume] [--break-glass-max-duration PT4H]
```

`publish` 只读取被接受的转换，而且只读取 `report.json` 列出的文件，并校验其中记录的 SHA-256：`convert` 之后被修改、缺失或新增的文件会让
它停止。然后它编译每一份文档，按平台文档检查每份应用文档的引用，并拒绝在 `--break-glass-max-duration`（默认 `PT4H`）之后才过期的
break-glass 绑定。它经过控制面的存储把每份文档写成修订 1，所以文档的修订历史从这里开始；并且只在文档尚不存在时写入：它从不覆盖 8.0 数据。

每份文档写一次。如果某一次失败，消息会列出已经写入的文档；用 `--resume` 再运行一次，它跳过内容相同的已发布文档，拒绝内容不同的。
`--key-prefix` 是 8.0 的快照前缀，与 `cosec.authorization.snapshot.key-prefix` 相同。

`publish` 之后，只通过控制面修改策略。再次运行 `convert` 和 `publish` 不会更新已存在的文档。

#### 引导控制面 {#bootstrap-the-control-plane}

```bash
cosec-migrate bootstrap --to-redis redis://localhost:6379 [--key-prefix cosec] --platform-admin alice [--force]
```

控制面用快照中的策略授权自己的写操作，所以没有管理员时谁都不能写入。`bootstrap` 只写平台租户的文档 `Tenant((platform))`；平台文档本身来自 `publish`，所以先发布。`bootstrap` 在平台租户自己的文档中，把平台租户的主体 `alice`
绑定到内置策略 `cosec:platform-admin`，并记为一个 `BOOTSTRAP` 种类的修订。当这份文档已经把某人绑定到 `cosec:platform-admin` 时它拒绝执行；
`--force` 用于这些管理员全部丢失后的恢复。持有 Redis 凭证的人本来就能写入；`bootstrap` 只是留下一条记录。

#### 回滚 {#rollback}

`publish` 和 `bootstrap` 只写 8.0 的 key（`<prefix>:snapshot:*` 和 `<prefix>:control:*`），8.0 也从不读取或修改 7.x 的 key。要回滚，
重新部署 7.x：它读到的 7.x 数据保持原样。切换之后通过 8.0 控制面做的变更不在 7.x 数据中。从 `convert` 开始一直停止 7.x 的写入方，直到你
不再打算回滚；或者在发布之前重新转换：`convert` 之后的 7.x 写入不在转换出的文档中。

7.x 的策略历史（`<prefix>:policy:history:*`）既不转换也不删除。需要的话请自行导出。

### 8.0 在哪些地方决策不同 {#what-8-0-decides-differently}

在 8.0 中，下面这些就是规则；每条在一致性套件中都有 id。在没有错误、没有缺失数据、也没有下列形态时，8.0 对转换后的数据做出与 7.x 相同的
决策。决策算法本身见[安全模型](../guide/security-model.md)。

| 规则 | 8.0 | 7.x | 受影响的 7.x 形态 |
|---|---|---|---|
| **C1a**（`ALG-4`） | 出错的 ALLOW 语句（或其策略条件）按未命中处理：其他 ALLOW 仍可放行，否则为默认拒绝（`401`/`403`）。 | `500`。 | 条件可能出错的 ALLOW（表达式、正则表达式、模板、自定义匹配器）。这是唯一可能放行 7.x 以 `500` 拒绝的请求的规则。 |
| **C1b**（`ALG-1`） | 命中的 DENY 做出决策（`403`/`401`），无论别处有什么错误、缺失或不可用的数据。 | 按层顺序先发生的失败为 `500`。 | 每条 DENY 语句。 |
| **C1c** | 策略条件在它的语句第一次被访问时求值；出错时，它的 DENY 语句算作出错，ALLOW 语句算作未命中。 | 每层先求值该层全部策略条件；出错时该层为 `500`。 | `all` 以外的策略级 `condition`。 |
| **C1d**（`BLK-1`） | 被拉黑的主体和 break-glass 主体仍需要平台文档；平台文档不可用时请求为 `503`。 | 黑名单和 root 不读任何策略。 | root、黑名单。 |
| **C2**（`BRK-1`） | 命中的 DENY 也拒绝 break-glass 主体。 | root 跳过所有策略。 | root，以及任何全局 DENY。 |
| **C3**（`SCP-1`） | 声明的空间（非空的 `CoSec-Space-Id`）必须是主体在其中有角色的空间；否则请求以 `scope-rejected`（`403`）拒绝，匿名时为 `401`。不会退回默认空间。 | 按声明的空间求值：该空间的角色权限为空，策略照常生效。 | 默认空间以外的角色授权；发送空间的客户端。 |
| **C4**（`SCP-2`） | 设置了 `cosec.authorization.scope.app-id` 时，声明其他应用的请求被拒绝（`scope-rejected`）；没有声明时使用宿主的应用。 | 声明的应用总是生效。 | 只服务一个应用的宿主。 |
| **C5**（`BND-2`） | 主体只能附加自己租户文档或平台层中可附加的策略。 | 任何主体都能附加任何策略。 | 主体策略，仅在 `--partition by-owner` 时。默认分区保持 7.x 的行为。 |
| **C6** | 引用了所属应用未定义权限的角色授权，被 `convert` 拒绝。 | 到达它的请求为 `500`。 | 带有过时权限 id 的授权。 |
| **D1** | ALLOW 中的 `inRole: r` 只匹配在请求空间中生效的角色；ALLOW 中的 `inRole: r@s` 只在请求处于空间 `s` 时匹配；DENY 中的 `inRole: r` 匹配在任意空间持有的该角色。 | 按字面匹配角色，裸角色也匹配 `r@<请求的空间>`。 | `inRole` 条件。DENY 只会变宽，ALLOW 只会变窄。 |
| **D2**（`CND-1`） | 不存在的属性不等于任何值，也不等于空串。 | 不存在的 part 读作 `""`。 | 与 `#{principal.id}`、`#{principal.attributes['k']}` 或 `#{tenant.tenantId}` 比较的 `eq`。ALLOW 中，不存在的 part 不再匹配空的属性（变窄）。DENY 中，`convert` 补上 7.x 的情形，保持 7.x 的读法。 |
| **D3** | 没有正则表达式，所以也没有正则超时。 | `RegexTimeoutException` 按默认拒绝处理。 | `regular` 条件；只有字面量、字面量前缀和字面量选择的模式能翻译。 |

#### 声明空间的匿名请求返回 401 {#anonymous-requests-that-declare-a-space-are-401}

规则 `SCP-3`：带非空 `CoSec-Space-Id`（请求头或查询参数）的匿名请求返回 `401` `unauthorized`，即使路径对所有人开放。匿名主体没有角色，
所以不是任何空间的成员（C3）。令牌被拒绝的请求也是匿名请求，它得到带该令牌 problem 类型的 `401`。在公开端点上发送 `CoSec-Space-Id`
的客户端必须停止在那里发送它。

#### root 变为 break-glass {#root-becomes-break-glass}

`cosec.authorization.root-enabled` 和根主体已删除。8.0 中对应的是 break-glass 绑定：有时间上限（最多 24 小时，
`cosec.control.break-glass.max-duration`，默认 4 小时），受每一条 DENY 约束（C2），并且只有在 REQUIRED 决策监听器记录了请求时才能放行
（否则 `503`，规则 `OBS-2`）。需要时通过控制面申请。`convert --root-enabled --root-expires-at` 会把 7.x 的 root 转换为平台租户中
`--root-id` 主体的这样一个绑定，但除非它在 `--break-glass-max-duration` 之内过期，`publish` 会拒绝它。通常请不带 root 转换。

#### 动作中的路径模板 {#path-templates-in-actions}

带 `#{...}` 模板的 7.x `path` 动作，例如 `/users/#{principal.id}/**`，会被 `convert` 拒绝。8.0 中由路由映射给出资源及其 id
（`"path": "/users/{userId}"`、`"resource": "user/{userId}"`），再用一个条件把资源属性与主体比较。见[策略语言](../policies/language.md)。
`eq` 条件中的 `#{...}` 模板可以翻译（D2）。

#### 限流规则变为义务 {#rate-limit-rules-become-obligations}

7.x 的限流规则文件（`cosec.limiter.*`）已删除。`convert --rate-limit-rules` 把规则翻译成平台文档的限流规则。与 7.x 一样，规则作用于策略
放行的请求：第一条耗尽的规则返回 `429`，被拒绝的请求不消耗许可。带 `strictFailure` 的规则在限流器失败时，现在返回 `503` 而不是 `429`（R3）。
存在 `ReactiveStringRedisTemplate` bean 时，Redis 限流器使用相同的 key（`cosec:rate-limiter:<rule>:<bucket>:<window>`）；否则每个实例
各自计数。见[限流](../policies/rate-limiting.md)。

### 响应与状态码 {#responses-and-status-codes}

#### 错误响应体是 problem+json {#error-bodies-are-problem-json}

每个不放行的响应都带 `Content-Type: application/problem+json`、`CoSec-Request-Id` 响应头，以及下面这样的响应体（成员顺序固定；
只有开启追踪时才有 `traceId`）：

```json
{"type":"https://cosec.ahoo.me/problems/forbidden","title":"Forbidden","status":403,"code":"FORBIDDEN","requestId":"..."}
```

7.x 以 `application/json` 返回的 `{"authorized":false,"reason":"..."}` 已删除。显式与隐式拒绝在响应中不再区分：两者都是 `unauthorized` 或
`forbidden`，所以响应永远不会告诉调用方存在 DENY。审计事件保留这一区别。`400` 现在也带响应体和 `CoSec-Request-Id` 响应头；7.x 中两者都没有。
完整的状态码表见 [Web 集成](../integration/web.md)。

| 7.x 状态码与 `reason` | 8.0 状态码与 `code` |
|---|---|
| `400`，无响应体 | `400` `INVALID_REQUEST` |
| `401` `Explicit Deny` 或 `Implicit Deny` | `401` `UNAUTHORIZED` |
| `401` `Token Expired` | `401` `TOKEN_EXPIRED` |
| `401` `Token Invalid`（无效的令牌或 API key） | `401` `TOKEN_INVALID` |
| `401` `Token Invalid`（已吊销的令牌） | `401` `TOKEN_REVOKED` |
| `403` `Explicit Deny` 或 `Implicit Deny` | `403` `FORBIDDEN`；声明的应用或空间被拒绝时为 `403` `SCOPE_REJECTED`（C3、C4） |
| `429` `Too Many Requests` | `429` `TOO_MANY_REQUESTS`，`RateLimit-*` 与 `Retry-After` 响应头不变 |
| `strictFailure` 规则的限流器失败时的 `429` `Too Many Requests` | `503` `UNAVAILABLE`（R3） |
| `500` `Implicit Deny`（无法做出决策） | `503` `UNAVAILABLE`（R1、R2） |
| `500` `Implicit Deny`（其他错误） | `500` `INTERNAL_ERROR` |

与 7.x 一样，被拒绝的凭证按匿名求值：策略对匿名调用方放行的请求仍是 `200`；被拒绝时，problem 会说明凭证被拒绝的原因。

#### 无法决策时用 503 取代 500 {#_503-replaces-500-when-no-decision-can-be-made}

| 规则 | 8.0 | 7.x |
|---|---|---|
| **R1**（`PIP-2`） | 无法做出决策（文档不可用或未及时加载、成员关系不可用、黑名单读不到、外部属性取不到）：`503`。 | `500`。 |
| **R2** | 认证器自身的依赖（读不到的吊销存储或 API key 存储）失败：`503`。 | `500`。 |
| **R3** | 带 `strictFailure` 的限流规则无法计数：`503`。 | `429`。 |

`500` 留给没有建模的异常，即缺陷。`503` 从不带 `Retry-After`。

#### 有歧义的 CoSec 参数返回 400 {#ambiguous-cosec-parameters-are-400}

规则 `SCP-4`：当 `CoSec-App-Id`、`CoSec-Space-Id` 或 `CoSec-Device-Id` 以两个不同的非空值出现（请求头与查询字符串之间，或其中任一之内），
请求返回 `400`。7.x 不会拒绝这样的请求。

#### Spring MVC 只从查询字符串读取查询参数 {#spring-mvc-reads-query-parameters-from-the-query-string-only}

在 Spring MVC 上，7.x 用 `HttpServletRequest.getParameter` 读取查询参数，而它也会返回 `application/x-www-form-urlencoded` 请求体中的字段。
所以对表单 POST，容器会在 CoSec 的过滤器中解析并消费请求体，请求体字段也被当作查询参数。8.0 在每个适配器上都只读取 URL 查询字符串，
并且永远不碰请求体（[Spring MVC 上经过 CoSec 后表单 POST 请求体为空](./troubleshooting.md#form-post-body-is-empty-after-cosec-on-spring-mvc)）。
表单字段不再设置 `CoSec-App-Id`、`CoSec-Space-Id` 或 `CoSec-Device-Id`，也不再设置在 `cosec.pipeline.allow-query-token=true` 时读取的
`authorization` 令牌。把这些作为表单字段发送的客户端，必须改为通过请求头或查询字符串发送。

### 认证 {#authentication}

#### 令牌吊销始终开启 {#token-revocation-is-always-on}

`cosec.jwt.token-revocation.enabled` 已删除。启用 JWT 时，每个令牌都要检查是否已吊销；没有任何东西能回答这个检查时，启动失败：加上
`cache-support`（它带来 Redis 吊销存储），或者注册自己的 `TokenStore` 和 `RevocationReader` bean。7.x 中没有 `cache-support` 时，starter
使用一个什么都不记录的存储，登出悄无声息地不起作用。只签发令牌、从不校验令牌的服务可以改为设置 `cosec.jwt.enabled=false`
（[登出与吊销](../integration/authentication.md#logout-and-revocation)）。7.x 写下的吊销继续有效：8.0 从相同的 key（`<prefix>:token:revoked:*`）
读取它们。吊销检查报告的错误返回 `503`，永远不会接受该令牌（`PIP-7`）。

#### 令牌与 API key {#tokens-and-api-keys}

- 7.x 签发的令牌继续可用：8.0 读取同样的声明（`roles`、`policies`、`attributes`、`tenantId`），并按 7.x 的方式读取角色声明（`role` 在默认空间，
  `role@space` 只在该空间，规则 `MEM-3`）。
- `cosec.api-key.header` 改为 `cosec.pipeline.api-key-header`（默认 `X-API-Key`）。`cosec.api-key.store-blocking` 已删除：Redis 的 key
  读取器从不阻塞。携带 API key 的请求只按 key 判定，即使它同时携带令牌（`PIP-8`）。
- `cosec.authorization.allow-query-token` 改为 `cosec.pipeline.allow-query-token`（默认 `false`）。
- 7.x 自定义的 `SecurityContextParser` bean 不再被使用。改为注册一个 `Authenticator` bean（[认证](../integration/authentication.md)）。
- `cosec.authentication.enabled` 改为 `cosec.authentication.login.enabled`，`cosec.authentication.social.*` 改为
  `cosec.authentication.login.social.*`。这个开关打开或关闭登录 bean（`TokenCompositeAuthentication`、你的 `Authentication` bean、社交登录）；
  无论它如何设置，请求都由管道认证。
- 刷新令牌时应用与请求相同的 `cosec.authentication.revocation.skew`（默认 `5s`）：签发时间在主体吊销之后不超过该偏差的令牌不能再被刷新。

#### 注入模式变为身份传递 {#inject-mode-becomes-identity-propagation}

7.x 的注入模式（`cosec.inject.enabled=true`）让网关之后的服务不经校验地解析转发来的 JWT。8.0 没有不校验就读取令牌的模式。取而代之的是：
网关转发它验证过的身份，其后的服务信任网关：

| 位置 | 7.x | 8.0 |
|---|---|---|
| 网关 | 转发客户端的 `Authorization` 头。 | 无需设置：对有主体的放行，它设置 `CoSec-Identity`，并总是去掉客户端自己发来的。`cosec.gateway.identity.claims` 指定要转发的主体属性；路由元数据 `cosec-identity: false` 让该路由不转发。除非 `cosec.gateway.identity.forward-authorization=true`，转发的请求中会去掉 `Authorization` 头（[网关身份](./configuration.md#gateway-identity)）。 |
| 其后的每个服务 | `cosec.inject.enabled=true` | `cosec.pipeline.mode=gateway`（[网关之后的服务](../integration/web.md#services-behind-the-gateway)）：没有 key、策略、快照或 Redis；除非它签发令牌，否则设置 `cosec.jwt.enabled=false`。只有网关可以访问到该服务（网络策略、私有网络、mTLS）；其他能访问到它的人声称自己是谁就是谁。 |
| 应用代码 | 从 `SecurityContext` 读取调用方。 | 相同。附加策略不被转发，角色是网关决策时所在作用域中的角色。 |

永不过期的凭证（例如没有 `expires-at` 的 API key）不会被转发：网关对它的放行返回 `500`。给这类 key 设置过期时间，或让它们的路由不转发身份。

在 CoSec 网关之后、自己校验令牌的服务不再收到 `Authorization` 头。要么在网关上设置 `cosec.gateway.identity.forward-authorization=true`，
要么把该服务切换到 `cosec.pipeline.mode=gateway`。放在 cookie 中的令牌仍会被转发。

### 存储与 Redis {#storage-and-redis}

#### Redis key {#redis-keys}

8.0 把文档存在 `<prefix>:snapshot:*` 下，控制面的数据存在 `<prefix>:control:*`、`<prefix>:candidate:*` 和 `<prefix>:shadow:*` 下。它不读取
7.x 的 key：`<prefix>:policy:*`、`<prefix>:global:*`、`<prefix>:app:permission:*`、`<prefix>:app:role:permission:*` 和 `<prefix>:meta:schema`。
直接读写这些 key 的工具必须改用控制面。只给控制面的 Redis 账号 `<prefix>:snapshot:*` 和 `<prefix>:control:*` 的写权限。全部 key 见
[存储](./storage.md)。

#### 读不到 Redis 的驱逐策略时启动失败 {#startup-fails-when-redis-s-eviction-policy-cannot-be-read}

启动时 CoSec 读取 Redis 的 `maxmemory-policy`，不是 `noeviction` 就拒绝启动，因为被驱逐的平台文档会让其中的 DENY 悄无声息地失效。
`CONFIG GET` 失败或不被允许时（某些托管 Redis 就是这样），启动同样失败；CoCache 后端是包装器或检查读不了的自定义后端时也一样。如果你已经
自行设置了 `noeviction`，请设置 `cosec.authorization.snapshot.eviction-check=warn`：启动继续，并记录一条以 `!!!` 开头的警告。

#### 每个实例依据快照决策 {#each-instance-decides-from-a-snapshot}

- **不再按请求读 Redis。** 每个实例加载平台文档以及它的请求需要的应用文档和租户文档，编译它们，并每隔
  `cosec.authorization.snapshot.revalidate-interval`（默认 `10s`）对每份已加载的文档做一次条件读。只有文档改变时 Redis 才传输它。
- **Redis 故障期间，已安装的文档按最后一次确认的内容继续决策**，所以平台的 DENY 仍然有效。需要尚未加载的文档的请求最多等待
  `cold-load-timeout`（默认 `2s`），然后返回 `503`。7.x 中，本地副本过期后（默认 5 分钟）请求开始以 `500` 失败。对于列在
  `cosec.authorization.snapshot.fail-closed.tenants` 中的租户，若请求读取的文档上次确认距今超过 `fail-closed.max-lag`，请求改为 `503`，
  除非有 DENY 匹配。
- **就绪状态等待快照。** 平台文档、然后是 `prewarm-apps` 和 `prewarm-tenants`，在应用启动的同时加载；在它们全部装入之前，就绪状态拒绝
  流量；存活状态不受影响。尚未加载的应用的第一个请求要编译它的整份文档；把繁忙的应用列进 `prewarm-apps`。
- **缺失的平台文档按失败即拒绝处理。** 平台文档发布之前，CoSec 需要决策的每个请求都是 `503`。
- **没有 `cache-support` 时**，starter 使用一个初始为空的内存存储，所以在应用把文档放进去之前每个请求都是 `503`。本地策略文件不再被加载
  （`cosec.authorization.local-policy.*` 已删除）。
- 读取失败表现为 `SnapshotUnavailableException`（原异常作为其 cause）和 `503`，取代 7.x 的 `CacheReadException` 和 `500`。

发布、文档回滚以及 Redis 不可用时的行为见[存储](./storage.md)。

### 已删除和替换的 API {#removed-and-replaced-apis}

使用下列任何一项的代码不再能编译，或者不再被调用。

| 7.x | 8.0 |
|---|---|
| `Authorization`、`AuthorizeResult`、`SimpleAuthorization`、装饰器链（`DecisionAuthorization`、追踪、审计、指标、限流、影子） | pipeline（`cosec-runtime`）。用 `DecisionListener` bean 观察决策。 |
| `SecurityPipeline`、`SecurityDecision`、`RequestParser`、`SecurityContextParser` | pipeline；凭证用 `Authenticator` bean。 |
| `CoSecAuthorizationCustomizer` | `Authenticator`、`DecisionListener`、`AttributeProvider`、`MembershipProvider`、`RateLimiter` 的 bean。 |
| `Policy`、`Statement`、`PermissionVerifier`、`ActionMatcher`、`ConditionMatcher`、`AppPermission`、`AppRolePermission`、`RolePermission` 以及 `cosec-api` 中其余的策略与权限模型 | 文档（[策略语言](../policies/language.md)）。覆写了 `verify` 的 `Statement` 没有对应物。 |
| `ActionMatcherFactory`、`ConditionMatcherFactory`、`MatcherFactoryRegistry`、内置匹配器、OGNL 与 SpEL | 只有内置的动作和条件。自定义条件读取的值改由 `AttributeProvider` 提供（[扩展](../architecture/extending.md)）。 |
| `BlockingAware` | 无：没有条件会执行代码。阻塞的查询放进会挂起的 `AttributeProvider`。 |
| `RootPrincipalMatcher`、`CoSecPrincipal.isRoot`、`root-enabled` | break-glass 绑定（[root 变为 break-glass](#root-becomes-break-glass)）。 |
| `BlacklistChecker` | 放在 `AttributeProvider` bean（`BlacklistAttributeProvider`）后面的 `BlacklistSource`。 |
| `RequestAttributesAppender`、`GatewayRouteRequestAttributesAppender` | `AttributeProvider`。 |
| `PolicyRepository`、`AppRolePermissionRepository`、Redis 仓库与缓存、`SnapshotPublisher`、`RedisSnapshotPublisher`、`PolicyHistory`、`RedisPolicyRevisionStore`、对 `AppPermissionCache`/`RolePermissionCache` 的写入 | 写入用控制面（库或 HTTP API）；第一次写入用 `cosec-migrate publish`。 |
| `LocalPolicyInitializer` 以及 `init-repository`/`force-refresh` | `cosec-migrate convert` 与 `publish`，或控制面的声明式 `apply`。 |
| `CoSecExplainEndpoint`（`cosecExplain` actuator）、`AuthorizationExplainer`、`ExplainWarningCode` | 控制面的 `explain`（需要权限，可指定任一修订）；见[代码中的检查](../integration/checks.md)。 |
| 影子模式（`ShadowAuthorization`、`ShadowPolicyProvider`、`cosec.shadow.*` 文件） | 控制面变更的影子窗口；见[测试策略](../policies/testing.md)。 |
| `AccessAnalyzer` | 控制面的 `analyze`。 |
| `PolicyTestKit`（`cosec-core`） | `cosec-testkit` 模块中的 `PolicyTestKit`（[策略测试套件](#policy-test-suites)）。 |
| `cosec-api` 中 7.x 的请求模型：`Request`、`Attributes`、`AppIdCapable`、`DeviceIdCapable`、`RequestIdCapable`（及其别名 `AppId`、`DeviceId`、`RequestId`）、`COSEC_EXTEND_KEY_PREFIX`、`SpaceIdCapable.SPACE_ID_KEY`；`cosec-core` 的 `RequestSecurityContexts` 和 `InvalidRequestPathException` | 无：适配器读取 `HttpFacts`，安全上下文携带校验后的 `scope`。`SpaceIdCapable` 和 `SpaceId` 为 `SpacedRoleId` 保留。 |
| `InternalIds`（`cosec-api`） | 无。 |
| `me.ahoo.cosec.limiter.RateLimiter` 及其 `LocalRateLimiter`、`RedisRateLimiter`（`cosec-cocache`） | 挂起的 SPI `me.ahoo.cosec.runtime.ratelimit.RateLimiter`；starter 注册它的 `LocalRateLimiter` 或 `ReactiveRedisRateLimiter`（[限流](../policies/rate-limiting.md)）。`RateLimit` 和 `RateLimitResult` 保留。 |
| `Jwts.toPrincipal(String)`、`Jwts.decode`、`Jwts.verifyTimeClaims` | 无：`Jwts` 是 `cosec-jwt` 的内部类。用会校验签名的 `TokenVerifier.verify` 把令牌变成主体。 |
| `PrincipalConverter`、`TokenVerifier.toPrincipal` | `TokenVerifier.verify`。 |
| `AuthorizationChecker.check(...)`：`Mono<Boolean>` | `AuthorizationChecker.check(security, action, resource)`，挂起函数；`cosec-webflux` 提供 `Mono<Boolean>` 形式，`cosec-webmvc` 提供阻塞形式（[代码中的检查](../integration/checks.md)）。 |
| 过滤器 `ReactiveAuthorizationFilter`、`AuthorizationFilter`（servlet）、`AuthorizationGatewayFilter`，以及它们接受 `Authorization` 的构造器 | 由 starter 创建的 `PipelineWebFilter`、`PipelineServletFilter`、`PipelineGatewayFilter`。 |
| `ReactiveInjectSecurityContextWebFilter`、`InjectSecurityContextFilter`（注入模式） | CoSec 网关之后的 `cosec.pipeline.mode=gateway`（[注入模式变为身份传递](#inject-mode-becomes-identity-propagation)）。8.0 的过滤器在每个放行的请求上放入安全上下文。 |
| 授权之后留在调用方安全上下文中的路径变量 | 无。从你的 Web 框架读取路径变量。 |
| `cosecPolicyGenerator`、`cosecAppPermissionGenerator` actuator 端点 | `cosecServiceGenerator/routes`（路由映射）和 `cosecServiceGenerator/app`（应用文档）。 |

仍然可用：`SecurityContext`（新增 `scope`，即校验后的应用、空间和角色；应读取它，而不是客户端声明的 `CoSec-*` 请求头）、`ServerWebExchange`、
Reactor `ContextView` 和 `HttpServletRequest` 上的 `getSecurityContext()`、`AuditEventSink`、`TokenStore`，以及 `cosec-api` 的审计类型。

#### 策略测试套件 {#policy-test-suites}

8.0 的测试工具是 `cosec-testkit` 模块。`PolicyTestKit.load(dir)` 读取按 `cosec-migrate convert` 输出布局存放的文档，`tests(dir)` 为每个
场景文件返回一个 JUnit 5 动态测试，供 `@TestFactory` 使用（[测试策略](../policies/testing.md)）。7.x 套件的 `policies`、`appPermissions` 和角色
授予变成转换得到的文档；它的每个 `cases` 条目变成一个场景文件：

| 7.x 用例 | 8.0 场景 |
|---|---|
| `name` | `description`（以及文件名） |
| `principal` | `credential`：`tenant`、`id`、`roles`、`policies`、`attributes` |
| `request` | `request`：`app`、`space`，以及路由映射给出的 `action` 和 `resource`（`type`、`id`）；测试工具不映射 HTTP 请求 |
| `expect.result: ALLOW` | `"expect": {"decision": "Permit"}` |
| `expect.result: EXPLICIT_DENY` | `"expect": {"decision": "Deny", "by": {...}}`，`by` 指明做出决策的语句 |
| `expect.result: IMPLICIT_DENY` | `"expect": {"decision": "Deny", "by": null}` |
| `policyId`、`statementName` | `by`：`policy`、`statement`（它的下标）和 `name` |

只比较 `expect` 中写出的字段。

### 已删除和改名的属性 {#removed-and-renamed-properties}

这里没有列出的 7.x `cosec.*` 属性保持名称和含义不变。8.0 的全部属性及默认值见[配置](./configuration.md)。

下面每个 key 在 8.0 中都会让启动失败，错误信息会给出替代它的配置（starter 中的 `UnknownProperties.RENAMED`）。8.0 不读取的其他
`cosec.*` key 也一样；`cosec.unknown-properties=warn` 只记录它们（[未知配置项](./configuration.md#unknown-properties)）。以 `.` 结尾的
key 代表它之下的每个 key。

| 7.x | 8.0 |
|---|---|
| `cosec.authentication.enabled` | `cosec.authentication.login.enabled` |
| `cosec.authentication.social.` | `cosec.authentication.login.social.` |
| `cosec.authorization.allow-query-token` | `cosec.pipeline.allow-query-token` |
| `cosec.api-key.header` | `cosec.pipeline.api-key-header` |
| `cosec.authorization.enabled` | 已删除：`cosec.enabled=false` 关闭 CoSec。 |
| `cosec.authorization.local-policy.` | 已删除：用 `cosec-migrate` 转换并发布这些文件（[迁移数据](#migrating-the-data)）。 |
| `cosec.authorization.root-enabled` | 已删除：root 变为 break-glass（[root 变为 break-glass](#root-becomes-break-glass)）。 |
| `cosec.authorization.cache.enabled` | 已删除：快照缓存是 `cosec.authorization.snapshot.*`。 |
| `cosec.authorization.cache.policy.` | 已删除：快照缓存是 `cosec.authorization.snapshot.*`。 |
| `cosec.authorization.cache.role.` | 已删除：快照缓存是 `cosec.authorization.snapshot.*`。 |
| `cosec.authorization.cache.history.` | 已删除。 |
| `cosec.authorization.cache.migration.` | 已删除。 |
| `cosec.authorization.cache.token.concurrency-level` | 已删除：自 CoCache 5 起被忽略。 |
| `cosec.authorization.cache.token.expire-after-write` | `cosec.authorization.cache.token.local-max-age`，一个时长（`30s`）。 |
| `cosec.authorization.cache.token.expire-unit` | 已删除：时长自带单位。`expire-after-access` 也是时长；不带单位的数字仍表示秒。 |
| `cosec.authorization.gateway.enabled` | 已删除：网关过滤器随 `gatewaySupport` 提供。 |
| `cosec.limiter.` | 已删除：限流规则在平台文档中（[限流规则变为义务](#rate-limit-rules-become-obligations)）；要使用不同的限流 key 前缀，需要自己的 `RateLimiter` bean。 |
| `cosec.shadow.` | 已删除：影子窗口是控制面的操作；每个实例的部分是 `cosec.authorization.shadow.*`。 |
| `cosec.explain.enabled` | 已删除：使用控制面的 `explain`。 |
| `cosec.inject.enabled` | 已删除（[注入模式变为身份传递](#inject-mode-becomes-identity-propagation)）。 |
| `cosec.api-key.store-blocking` | 已删除。 |
| `cosec.jwt.token-revocation.enabled` | 已删除：吊销始终开启（[令牌吊销始终开启](#token-revocation-is-always-on)）。 |

`cosec.authorization.cache.key-prefix` 和 `cosec.authorization.cache.token.*` 保留。

新的属性组：`cosec.pipeline.*`（超时、查询令牌、API key 请求头、trace 级别、模式）、`cosec.gateway.identity.*`、`cosec.unknown-properties`、
`cosec.authorization.scope.app-id`、`cosec.authorization.{membership,attributes,rate-limit}.timeout`、`cosec.authorization.routes.location`、
`cosec.authorization.snapshot.*`、`cosec.authorization.shadow.*`、`cosec.authentication.revocation.skew`、`cosec.audit.required.timeout`、
`cosec.audit.kafka.delivery`、`cosec.observability.listener.*` 和 `cosec.control.*`。

升级时有两个默认值需要做出决定：

- `cosec.authorization.routes.location` 默认为 `classpath:cosec-routes.json`。在 WebFlux 或 Spring MVC 宿主上，映射不存在会让启动失败；把这个
  属性设为空即可不使用路由映射，此时每个请求都是 `http` 命名空间中针对其路径的动作。网关不加载路由映射。
- `cosec.authorization.scope.app-id` 默认不设置。只服务一个应用的宿主请设置它（规则 C4）。

8.0 预览版期间有两项设置变化：

- `cosec.control.http.base-path` 指控制 API 的位置，不含版本：路由位于 `{base-path}/v1` 之下，因此默认值 `/cosec/control` 提供
  `/cosec/control/v1`。以 `/v1` 结尾的设置去掉这一段即可。
- CoSec 网关服务只读取 `application.yaml`，不再启动 Spring Cloud 的 bootstrap 上下文。通过 `bootstrap.yaml` 配置它的部署（例如
  Spring Cloud Config 或 Nacos）改为在 `application.yaml` 中用 `spring.config.import` 引入。

### 可观测性的变化 {#observability-changes}

- **审计事件**为每个结果写出，包括 `400`、取消的请求、认证失败和错误，由 `kind` 说明是哪一种（`DECIDED`、`BAD_REQUEST`、
  `AUTHENTICATION_UNAVAILABLE`、`CANCELLED`、`ERROR`）。新字段：`snapshotVersion`（读取的修订）、`binding`（做出决策的语句所经过的绑定，或
  `attached:<policy>`）、`breakGlass`。新取值：决策 `INDETERMINATE`；原因码 `INDETERMINATE`、`SCOPE_REJECTED`、`RATE_LIMITER_UNAVAILABLE`、
  `TOKEN_REVOKED`、`BOUNDARY`。来源类型保留 7.x 的名称：平台文档或租户文档中的绑定为 `GLOBAL_POLICY`，附加策略为 `PRINCIPAL_POLICY`，
  应用文档中的角色绑定为 `ROLE_PERMISSION`，内置黑名单规则为 `BLACKLIST`。`elapsedNanos` 现在覆盖整个 pipeline（认证、属性、限流），而不只是
  授权。7.x 的 `AuditEventSink` 无需修改即可继续使用。
- **指标**：`cosec.authorization` 计时器同样覆盖整个 pipeline。它的 `source` 标签现在是 `binding`、`attachment`、`builtin`、`boundary`、
  `scope` 或 `none`（7.x：`global_policy`、`principal_policy`、`role_permission`、`root`、`blacklist`、`none`、`unknown`）；`result` 新增
  `indeterminate`、`bad_request` 和 `cancelled`；`cause` 是新标签，只出现在 `indeterminate` 上。新计数器：`cosec.listener.dropped`、
  `cosec.listener.failures`、`cosec.audit.required.failures`。请更新仪表盘和告警。
- **追踪**：`cosec.authorize` span 在决策之后由决策事件生成，所以一次求值中的 I/O span 不再是它的子 span，审计事件的 `spanId` 是父 span 的 id。
- **Kafka 审计**默认尽力投递，除非设置 `cosec.audit.kafka.delivery=required`；失败的 REQUIRED 监听器会把放行变为 `503`。
- **控制面变更**有自己的事件（`ChangeListener`；默认写日志，`KafkaChangeListener` 写 Kafka）。
- **日志**：`SimpleAuthorization` 的 debug 日志已删除；决策在审计日志中。
- **线程**：在 WebFlux 上，真正挂起之后，下游处理链在 `Dispatchers.Default` 的线程上继续，而不是 `boundedElastic`。代码不能依赖线程名或
  `ThreadLocal`。

见[可观测性](./observability.md)。

### RFC 中的差异清单 {#rfc-change-lists}

8.0 背后的 RFC 列出了已知差异。每一条要么在上文中说明，要么描述的是 7.x 与 8.0 之间的中间状态，没有任何发布版本有过这种状态。

| 条目 | 位置 |
|---|---|
| RFC 0002 X1（按层读取） | 中间状态。8.0 不按请求读取策略（[每个实例依据快照决策](#each-instance-decides-from-a-snapshot)）。 |
| RFC 0002 X2（`BlockingAware` 的线程切换） | 中间状态。`BlockingAware` 已删除（[已删除和替换的 API](#removed-and-replaced-apis)）。 |
| RFC 0002 X3（调用方上下文不再留下路径变量） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0002 X4（自定义匹配器写路径变量） | 中间状态。自定义匹配器已删除。 |
| RFC 0002 X5（覆写 `Statement.verify`） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0002 X6（7.x 解释器的输出） | 中间状态。7.x 解释器已删除；`explain` 是控制面的操作。 |
| RFC 0002 X7（`SimpleAuthorization` 的 debug 日志） | [可观测性的变化](#observability-changes)。 |
| RFC 0002 X8（引擎捕获哪些异常） | 中间状态。8.0 中没有策略会执行代码；没有建模的异常为 `500` `internal-error`。 |
| RFC 0003 X1（Redis 故障） | [每个实例依据快照决策](#each-instance-decides-from-a-snapshot)。 |
| RFC 0003 X2（I/O 形态） | [每个实例依据快照决策](#each-instance-decides-from-a-snapshot)。 |
| RFC 0003 X3（全局策略的顺序） | 多条效果相同的语句同时命中时，被记为做出决策的那一条（审计的 `source`、`binding`）可能与 7.x 不同；7.x 的顺序本来就没有定义。决策相同。 |
| RFC 0003 X4（异常类型） | [每个实例依据快照决策](#each-instance-decides-from-a-snapshot)。 |
| RFC 0003 X5（索引项没有内容） | `GLOBAL_INDEX_WITHOUT_CONTENT`（[拒绝项](#rejections)）。 |
| RFC 0003 X6（冷启动） | [每个实例依据快照决策](#each-instance-decides-from-a-snapshot)。 |
| RFC 0003 X7（写 API） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0003 X8（新应用的第一个请求） | [每个实例依据快照决策](#each-instance-decides-from-a-snapshot)。 |
| RFC 0004 C1a–C6 | [8.0 在哪些地方决策不同](#what-8-0-decides-differently)。 |
| RFC 0004 §13 X1（审计来源） | [可观测性的变化](#observability-changes)：来源类型保留 7.x 的名称，新增 `binding`。 |
| RFC 0004 §13 X2（每个请求三份文档） | [每个实例依据快照决策](#each-instance-decides-from-a-snapshot)。 |
| RFC 0004 §13 X3（按绑定组织的解释） | 控制面的 `explain`（[代码中的检查](../integration/checks.md)）。 |
| RFC 0004 §13 X4（写入需要指定文档） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0004 §13 X5（新的异常与原因） | [无法决策时用 503 取代 500](#_503-replaces-500-when-no-decision-can-be-made)；[排障](./troubleshooting.md)。 |
| RFC 0004 §13 X6（`SecurityContext.scope`） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0005 D1–D3 | [8.0 在哪些地方决策不同](#what-8-0-decides-differently)。 |
| RFC 0006 R1–R3 | [无法决策时用 503 取代 500](#_503-replaces-500-when-no-decision-can-be-made)。 |
| RFC 0006 X1–X4（审计、耗时、追踪、指标） | [可观测性的变化](#observability-changes)。 |
| RFC 0006 X5（响应体） | [错误响应体是 problem+json](#error-bodies-are-problem-json)。 |
| RFC 0006 X6（线程） | [可观测性的变化](#observability-changes)。 |
| RFC 0006 X7（API） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0006 X8（配置） | [已删除和改名的属性](#removed-and-renamed-properties)。监听器队列是 `cosec.observability.listener.*`（[配置](./configuration.md#decision-listeners)）。 |
| RFC 0007 X1（写 API、策略历史） | [已删除和替换的 API](#removed-and-replaced-apis)、[回滚](#rollback)。 |
| RFC 0007 X2（影子） | [已删除和替换的 API](#removed-and-replaced-apis)、[已删除和改名的属性](#removed-and-renamed-properties)。 |
| RFC 0007 X3（解释） | [已删除和替换的 API](#removed-and-replaced-apis)。 |
| RFC 0007 X4（文档格式） | 中间状态：7.x 没有文档。8.0 只读取自己的格式；8.0 没有 schema 标记。 |
| RFC 0007 X5（配置） | [已删除和改名的属性](#removed-and-renamed-properties)。`cosec.policy.limits.*` 在 7.x 中从未存在；限制在平台文档中。 |
| RFC 0007 X6（生成的 JSON schema） | `schema/` 文件描述 8.0 的格式（[策略语言](../policies/language.md)）。 |
| RFC 0007 X7（控制面审计、`BOUNDARY`） | [可观测性的变化](#observability-changes)。 |
| RFC 0007 X8（Redis 命名空间） | [Redis key](#redis-keys)。 |
