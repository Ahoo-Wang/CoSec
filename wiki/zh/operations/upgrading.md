---
title: "升级"
description: "如何升级正在运行的 8.0 部署、按什么顺序、哪些没有承诺，以及如何把 CoSec 7.x 部署升级到 8.0：有哪些变化，以及如何迁移数据、代码、配置和客户端。"
---

# 升级 {#upgrading}

面向升级正在运行的 8.0 部署（[升级正在运行的部署](#upgrading-a-running-deployment)）的团队，以及把 CoSec 7.x 部署升级到 8.0 的团队。7.x 部分先给出一份检查清单，再按领域给出细节：数据迁移、8.0 在哪些地方决策不同、响应、认证、
存储、API、属性和可观测性。本页是迁移步骤的唯一出处。每个版本的发布说明见
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

已在运行 8.0 预览版（`8.0.0-alpha` 或 `8.0.0-beta`）的部署，读[8.0 预览版之间的升级](./upgrading-previews.md)，而不是本页。

## 升级正在运行的部署 {#upgrading-a-running-deployment}

面向把正在运行的 8.0 部署（网关、其后的服务、控制面和 Redis）升级到另一个版本的运维人员。本节说明两个版本相遇时代码会怎么做，由此得出升级和回滚的顺序。它的承诺仅限于此：没有承诺的内容列在最后。从 7.x 升级见下一节。

有四样东西会跨越进程边界，每一样都被严格读取：

| 跨越边界的内容 | 谁写，谁读 | 不认识其内容的实例会怎么做 |
|---|---|---|
| `CoSec-Identity` 头，格式 `v: 1` | 网关写，每个 `gateway` 模式的服务读 | 拒绝它，状态码说明原因：不认识的成员、类型错误、重复的成员、重复的角色、越界的值、超过大小限制或重复发送的头，返回 `400` `invalid-request`；不读取的格式版本、没有过期时间的凭证、签发时间在未来，返回 `401` `token-invalid`；凭证或身份太旧，返回 `401` `token-expired`。成员先于版本检查，所以既提高 `v` 又增加成员的身份是 `400`，只提高 `v` 的是 `401`（[网关后面的服务](../integration/web.md#services-behind-the-gateway)） |
| 策略文档，`schema: 2` | 控制面和 `cosec-migrate` 写，每个实例读 | 读取时拒绝 `schema` 不同的文档，也不安装它无法编译的文档（不认识的成员、重复的键、类型错误）：需要它的请求失败即拒绝（`503`，决策事件里是 `DocumentInvalid` 或 `SourceUnavailable`，除非有 DENY 先命中，[故障排查](./troubleshooting.md)） |
| Redis 中的值（吊销记录、会话、API key、成员关系、控制面记录） | 写入它的实例，以及读取它的每个实例 | 无法解码的值让读取失败，并留在 Redis 里：从不删除。无法解码的吊销记录让请求返回 `503`，绝不当作“未吊销”；无法解码的文档记录绝不当作“没有文档”，需要它的请求返回 `503`，除非有 DENY 先命中；无法解码的控制面记录在控制 API 上是 `500`（[Redis 是权威存储](./storage.md#redis-is-the-store-of-record)） |
| 令牌 | 签发方签名，每个校验方读取 | 读取它认识的声明（[客户端可以读取的声明](../integration/clients.md#the-claims-a-client-may-read)），忽略其余；拒绝带 `act` 声明的令牌、本该是访问令牌的位置上的刷新令牌，以及会话声明不完整的令牌 |

**升级顺序。**

1. **在每个实例都运行新版本之前，不要发布使用新内容的文档。** 不认识新成员的实例不安装这份文档，需要它的请求失败即拒绝。含有这种实例的影子窗口会报告 `CANDIDATE_ERROR`，永远不会干净地关闭，所以这份变更无法通过它发布（[策略测试](../policies/testing.md#trying-changes-in-shadow-mode)）。
2. **先升级网关后面的服务，再升级网关。** 服务严格读取身份，所以比网关旧的服务会以 `400` `invalid-request` 拒绝网关新增的成员；网关提高身份的格式版本 `v` 时，它返回 `401` `token-invalid`，同时还增加成员则是 `400`。
3. **然后是控制面和 `cosec-migrate`**；当写入这个 Redis 的每个控制面实例和每个 `cosec-migrate` 都运行新版本后，运行一次 `cosec-migrate repair-references`（[修复反向引用索引](#repair-the-back-reference-index)）和 `verify-chain`（[检查修订链](#verify-the-revision-chains)）。
4. **Redis 按 Redis 自己的方式升级**，不由 CoSec 负责：CoSec 的键在 `cosec.key-prefix` 之下，重启或故障转移见 [Redis 要求](./storage.md#redis-requirements)。

**回滚顺序**相反：先网关，再其后的服务。回滚文档不需要任何实例变化：`POST /documents/<doc>:rollback` 把文档恢复成较早的修订，它是一次与其他变更一样要经过检查的变更（[修订、历史与回滚](./storage.md#revisions-history-and-rollback)）。降级控制面或 `cosec-migrate` 之后，再次运行 `repair-references` 和 `verify-chain`。

**混合版本的表现**：需要某份旧实例无法编译的文档的请求返回 `503`（`DocumentInvalid`）；拒绝新网关新增成员的服务返回 `400` `invalid-request`，只遇到更高身份格式版本的服务返回 `401` `token-invalid`；持有另一个签名密钥的实例返回 `401` `token-invalid`。最后一个实例运行同一版本时，这些就结束了。

**没有承诺的内容。** 上述四种格式在两个版本之间能否互通：8.0 预发布版是最新版本期间，版本之间完全没有承诺（[8.0 预览版之间的升级](./upgrading-previews.md)），之后由每个版本的发布说明写明它改了什么。新的服务读得懂每个旧网关的身份，或旧的服务读得懂新网关的身份。新版本写入 Redis 的值能被旧版本读取。新签发方签名的、带有旧校验方不读取的声明的令牌，对旧校验方意味着同样的东西。请缩短混合版本的时间，并在升级承载流量之前，用新版本的 `simulate` 和 `explain` 在文档副本上测试。

## 7.x 到 8.0 {#_7-x-to-8-0}

8.0 从 7.x 升级。从 6.x 升级时，先按
[`7.x` 分支上的升级指南](https://github.com/Ahoo-Wang/CoSec/blob/7.x/wiki/operations/upgrading.md)升到 7.x（包括其中的数据迁移），
再回到这里。`cosec-migrate convert` 拒绝不是 7.0 布局的 Redis 数据。从 4.x 和 5.x 升级，以及从任何把策略存放在你自己数据库里的版本升级，只有一条路：[从 4.x、5.x 和 6.x 升级](#from-4-x-5-x-and-6-x)。

**没有自己的 CoSec 数据？** 没有策略、也没有 CoSec 的 Redis 数据需要转换的应用（基于 CoSec API 构建的库，或在自己代码中决策的 6.x inject 模式服务）没有任何东西要迁移。从 6.x 升级时，跳过数据相关的章节（[迁移数据](#migrating-the-data)、[回滚](#rollback)）和 7.x 指南中的数据步骤；阅读代码与配置的章节：[8.0 的决策有何不同](#what-8-0-decides-differently)、[认证的变化](#authentication)、[模块、制品和包](#modules-artifacts-and-packages)、[已移除和被取代的 API](#removed-and-replaced-apis) 与[已移除和已重命名的属性](#removed-and-renamed-properties)。它们写的是 7.x 的类名和键，6.x 大多与之相同。校验另一个服务所签发令牌、自己没有策略的服务，是[单独的一个场景](../scenarios/resource-server.md)。

简要地说，有这些不同：

- **策略是文档。** 一份平台文档、每个应用一份应用文档、每个租户一份租户文档，取代 7.x 的策略、全局策略索引、应用权限和角色授权。
  Redis 仍是权威存储；每个实例把文档编译成快照，决策时不再按请求读 Redis。格式见[策略语言](../policies/language.md)。
- **一条决策路径。** 7.x 的 `Authorization` 链已删除。每个适配器（WebFlux、Spring MVC、Gateway）把请求交给同一条协程 pipeline，
  由它认证、解析作用域、决策，并把结果映射为状态码。
- **变更经过控制面。** 写入、校验、模拟、影子、审批、解释和回滚策略都是控制面（`cosec-control`）的操作，而不是应用代码或本地文件。
- **条件是数据。** 一套小的条件语言取代了匹配器工厂、OGNL 和 SpEL。策略中的任何内容都不会执行代码。
- **错误响应是 `application/problem+json`。** 响应不再告诉客户端是否存在 DENY。
- **Java 21。** 8.0 的每个模块都按 Java 21 编译，而 7.x 按 Java 17 编译：应用要在 JDK 21 或更高版本上构建和运行。`cosec-gateway-server` 镜像例外：它运行在 JDK 25 上，并带有 AOT 缓存（[部署](./deployment.md#the-image)）。

### 升级检查清单 {#upgrade-checklist}

1. **确认是 7.x 数据。** Redis 数据必须是 7.0 布局（schema 标记为 `7`）；6.x 部署先升级到 7.x。
2. **找出没有 8.0 形式的内容。** 在策略和代码中搜索：`spel` 与 `ognl` 条件、自定义 `ActionMatcherFactory` 或
   `ConditionMatcherFactory` 类型、动作中的 `#{...}` 路径模板、`regular` 条件、DENY 条件中的 `ignoreCase`、`BlockingAware`、
   `Authorization`/`Statement`/`Policy` 的实现、`CoSecAuthorizationCustomizer`、root（`root-enabled`）、本地影子文件和 `cosecExplain`
   端点，以及[含 `/` 的租户和应用 id](#tenant-and-app-ids-hold-no-slash)。[转换](#migrating-the-data)会报告每一条无法翻译的策略。
3. **用 `cosec-migrate convert` 转换数据**，修正每一个拒绝项，直到转换被接受。对照[8.0 在哪些地方决策不同](#what-8-0-decides-differently)
   阅读 `report.json` 中的语义改动。
4. **更新依赖和代码**：把构建和运行环境换到 JDK 21，用模块本身替换 starter 的 capability，把 import 改到新的包（[模块、制品与包](#modules-artifacts-and-packages)），删除对[已删除 API](#removed-and-replaced-apis) 的使用，改为注册 8.0 的扩展 bean。
5. **更新配置**：改名或删除每一个 [7.x key](#removed-and-renamed-properties)，并对新属性做出决定。8.0 不读取的 `cosec.*` key 会让启动
   失败：错误信息列出所有这样的 key，并链接到[已删除和改名的 key 对照表](#removed-and-renamed-properties)，请在表中查找各自的替代项。另外三种常见情况也会让启动失败：
   `classpath:cosec-routes.json` 存在而 `cosec.authorization.routes.location` 没有指明它（把这个配置项设为它，或设为空）；
   使用 JWT 却既没有 `cosec-cocache` 也没有自己的吊销存储（[令牌吊销始终开启](#token-revocation-is-always-on)）；Redis 的驱逐策略不是
   `noeviction` 或读不到（[读不到 Redis 的驱逐策略时启动失败](#startup-fails-when-redis-s-eviction-policy-cannot-be-read)）。
6. **更新客户端和告警**：错误响应体、`reason` 取值和部分状态码变了（[响应与状态码](#responses-and-status-codes)）；审计字段、指标标签和
   span 也变了（[可观测性的变化](#observability-changes)）。
7. **发布**：用 `cosec-migrate publish` 发布转换得到的文档；如果运行控制面，用 `cosec-migrate bootstrap` 绑定它的第一个管理员。
   平台文档发布之前，CoSec 需要决策的每个请求都返回 `503`，就绪状态拒绝流量。
8. **部署 8.0**，然后检查你依赖的请求的决策（控制面的 `simulate` 和 `explain`，见[测试策略](../policies/testing.md)）。
9. **稍后清理。** 8.0 从不读取、修改或删除 7.x 的 key。确定不再需要[回滚](#rollback)之后再删除它们。

### 迁移数据 {#migrating-the-data}

`cosec-migrate` 是 `migration/migrate/` 模块中的一次性命令行工具。没有应用依赖它，starter 也从不依赖它。它的契约是命令行：它的类不是库 API，任何版本都可能改变。使用你要部署的版本的工具：每个 [GitHub release](https://github.com/Ahoo-Wang/CoSec/releases) 都带有 `cosec-migrate-<version>.zip`，旁边是 `cosec-migrate-<version>.zip.sha256`。它不在 Maven Central。需要 Java 21 或更高版本：

```bash
sha256sum -c cosec-migrate-8.0.0.zip.sha256   # macOS：shasum -a 256 -c
unzip cosec-migrate-8.0.0.zip
cosec-migrate-8.0.0/bin/cosec-migrate convert ...
```

要从 CoSec 源码构建，运行 `./gradlew :migrate:installDist`；工具在 `migration/migrate/build/install/cosec-migrate/bin/cosec-migrate`。

它的命令如下。退出码：`0` 完成，`1` 数据被拒绝、写入失败或 `verify-chain` 发现断链，`2` 用法错误。`publish`、`bootstrap`、`repair-references` 与 `verify-chain` 接受 `--cluster`，用于 Redis Cluster：这时 `--to-redis` 写种子节点，以逗号分隔（`redis://node-1:6379,redis://node-2:6379`）。其他命令读 Redis 的客户端不支持 Cluster，会拒绝这个选项。每个命令只接受为它列出的选项：别的选项，例如 `publish --dry-run`，在读写任何东西之前就是用法错误，绝不会被忽略。

| 命令 | 作用 |
|---|---|
| `convert` | 读取 7.x 数据（Redis 或导出的文件），翻译后把 8.0 文档和一份报告写入一个新目录。不向 Redis 写任何东西。 |
| `publish` | 把一次已接受的转换中的文档作为它们的第一个修订写入 Redis，经过控制面的存储。从不触碰 7.x 的 key。 |
| `bootstrap` | 把平台租户的一个主体绑定到 `cosec:platform-admin`，让控制面有人可以写入。 |
| `partition plan` | 分别用 `--partition platform` 和 `by-owner` 转换两次，为每个租户写出一份计划，由控制面提交。不向 Redis 写任何东西。 |
| `repair-references` | 把每份文档的头所引用、而反向引用索引缺少的条目补进索引，删除没有任何头引用的条目，并补全 app 列表。 |
| `verify-chain` | 沿每份文档的修订链检查，指出记录缺失的修订，即 Redis 故障切换丢失写入留下的痕迹，以及不跟随头的链的修订索引。不写入任何东西。 |

#### 转换 {#convert}

```bash
cosec-migrate convert --from-redis redis://localhost:6379 [--key-prefix cosec] --out ./converted
cosec-migrate convert --from-files ./export --out ./converted
```

| 选项 | 含义 |
|---|---|
| `--from-redis <uri>` | 从这个 Redis 读取 7.x 数据：`--key-prefix` 下的策略、全局策略索引、应用权限和角色授权，以及 schema 标记 `<prefix>:meta:schema`（必须为 `7`）。策略历史（`<prefix>:policy:history:*`）被跳过。 |
| `--from-files <dir>` | 改为读取一份导出：`policies.json`（7.x 策略的数组，每条带 `id`）、`global-policies.json`（全局策略索引中 id 的数组）和 `app-role-permissions.json`（每个应用一项：`{"appPermission": {...}, "grants": {"admin": ["*"], "auditor@s1": ["invoice.read"]}}`；没有应用权限的应用写 `{"appId": "...", "grants": {...}}`；`grants` 是对象，没有则表示没有授权，其他类型会使读取失败，并指出 `app-role-permissions.json[i].grants`）。这三个文件必须存在；`rate-limit-rules.json` 可选。7.x 的本地策略文件用这种方式转换：把它们的内容放进 `policies.json`。 |
| `--key-prefix <p>` | 7.x 的 key 前缀；默认 `cosec`。 |
| `--out <dir>` | 一个不存在或为空的目录。`convert` 从不覆盖。 |
| `--rate-limit-rules <file>` | 7.x 的限流规则（`cosec.limiter.locations`）：一个规则对象，或规则的数组，每条带 `id`。多个规则文件请合并成一个数组。 |
| `--partition platform\|by-owner` | `platform`（默认）把每条策略放进平台文档，任何人都可以附加，与 7.x 相同。`by-owner` 把每条非全局策略放进其 `tenantId` 所指租户的文档（见下文规则 C5）；含 DENY 语句的策略留在平台文档中，并给出警告。 |

输出目录：

| 文件 | 内容 |
|---|---|
| `platform.json` | 平台文档。 |
| `apps/<appId>.json` | 每个 7.x 应用权限对应一份应用文档：它的权限目录和角色绑定。 |
| `tenants/<tenantId>.json` | 租户文档，只在 `--partition by-owner` 时出现。 |
| `provenance.json` | 每条策略和绑定来自 7.x 数据的哪里。只在转换被接受时写出。 |
| `report.json` | `accepted`、输入的 SHA-256、计数、`rejections`、`warnings`、`semanticChanges`、写出的每个文件的 SHA-256，以及 `documentIds`。 |

文件名是经 URL 编码的 id。编码后的名字长于 200 个字符的 id（30 个汉字编码后是 275 个）改用有界的名字：编码后 id 的一段前缀、`~` 和该 id 的 SHA-256。编码后的名字里不会出现 `~`；`report.json` 的 `documentIds` 记录这类文件各装着哪个 id，`publish` 会按这个 id 检查文件名。写入失败时会删掉本次写出的文件，目录和原来一样是新的，`convert` 可以重新运行。

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
| `INVALID_ID` | 8.0 无法容纳的 id：应用或空间 id 为 `*` 或空、应用 id 含 `:`、角色 id 含 `@`，或者转换出的文档中任何 id 含落单的 UTF-16 代理项（如 JSON 转义 `"\uD800"`，`IDN-10`），位置为文档及其 JSON 路径。 | 重命名。8.0 中 `*` 表示任意应用或空间。 |
| `SLASH_IN_ID` | 含 `/` 的租户 id（`--partition by-owner` 时策略的 `tenantId`）或应用 id，任何 8.0 租户或应用 id 都不能含 `/`（[见下文](#tenant-and-app-ids-hold-no-slash)）。 | 在源系统中给该租户或应用改名，使其 id 不含 `/`，然后重新转换。 |
| `DUPLICATE_ID` | 导出中同一条策略出现两次，导出中同一个应用出现两次（一项会替换另一项，它的授权就丢了），同一个应用中同一个权限出现两次，或两个应用 id 或租户 id 转换后的文件名只有大小写之差（`Order` 与 `order`：在 macOS 和 Windows 上是同一个文件）。文件名不同的 id，例如 `strasse` 与 `straße`，不算冲突。 | 只保留一个，或改名。 |
| `OWNER_TENANT` | 使用 `--partition by-owner`，而策略的 `tenantId` 不指向任何租户。 | 设置它的 `tenantId`，或改用 `--partition platform` 转换。 |
| `CONDITION_NOT_TRANSLATED` | 没有精确翻译的 7.x 内置条件，例如不是字面量、字面量前缀或字面量选择的 `regular`，或者动作不是 `all` 的语句中的 `path` 条件。还包括只有 7.x 填写的部件（除网关的 `cosec.audit.routeId` 之外的 `request.attributes.*`，由 `RequestAttributesAppender` 设置；以及 `resource.*`），它们在 8.0 中总是缺失，所以其上的条件在 8.0 中是常量：在缺失值上成立的条件，无论在 DENY 还是 ALLOW 中（它会对每个请求成立，而 7.x 只在该部件为空时成立）；以及在缺失值上不成立的条件，在 DENY 中或在 DENY 中被取反（这条 DENY 永不成立，或对每个请求成立）。只有不再可能成立的 ALLOW 会被翻译，并记录为 `NOT-IN-8.0`；与模板的比较（`eq resource.owner #{principal.id}`）就是一例，因为缺失值不等于任何值。7.x ip2region 的 `ipRegion`（`country|region|province|city|isp`）只有一种形状能翻译：国家前缀（`startsWith "中国|"`，或表达它的 `regular`），翻译为 `ext.ip2region.country`；其上的其他条件按只有 7.x 填写的部件处理。以及在 DENY 需要时区分方法 HEAD 与 GET 的条件，因为 8.0 把 HEAD 请求按 GET 判定（包括 DENY 中的 `not eq GET`）。 | 按消息给出的条件（`eq`、`in`、`startsWith` ……）重写；appender 设置的值改为 `AttributeProvider` 的 `ext.*` 属性（`ipRegion`：`ext.ip2region.*`）。 |
| `EXPRESSION_CONDITION` | `spel` 或 `ognl` 条件。 | 用[策略语言](../policies/language.md)的条件重写。 |
| `CUSTOM_CONDITION` | 自定义 `ConditionMatcherFactory` 的条件。 | 把它读取的值移进一个 `AttributeProvider`，并在这个属性上写条件（[扩展](../architecture/extending.md)）。 |
| `ACTION_NOT_TRANSLATED` | `#{...}` 路径模板、不是 `/` 的路径 `separator`、成员方法不同的动作数组、不是 HTTP token 的方法，或者含 HEAD 而不含 GET 的 DENY：8.0 把 HEAD 请求按 GET 判定（`PIP-18`），无法脱离 GET 单独拒绝 HEAD。 | 见[动作中的路径模板](#path-templates-in-actions)；按方法拆分语句；删除任何请求都不可能有的方法；同时拒绝 GET，或删除 HEAD。 |
| `CUSTOM_ACTION` | 自定义 `ActionMatcherFactory` 的动作。 | 改写为 HTTP 路径和方法，或路由映射出的动作。 |
| `DOES_NOT_COMPILE` | 翻译后的语句无法编译，例如超出 8.0 接受范围的路径模式；或文档整体无法编译，例如每条策略都在路径模式的预算之内，合在一起却超出。拒绝项指出能追溯到的策略，否则指出文档。 | 见消息和[策略语言](../policies/language.md)。 |

警告不会让转换停止。目前的警告有：全局策略索引中类型不是 `GLOBAL` 的策略（按可附加策略转换，因为 7.x 并不把它应用于所有人）；
`by-owner` 时留在平台文档中、含 DENY 语句的租户策略；没有应用权限的应用上的角色授权（7.x 忽略它们，所以被丢弃）。

#### 租户和应用 id 不含斜杠 {#tenant-and-app-ids-hold-no-slash}

8.0 把租户 id 和应用 id 都当作一个路径段（`SCP-13`），所以两者都不能含 `/`；7.x 接受过这样的 id。控制面在 `doc/tenant/<tenantId>/…`
这样的资源上授权，`acme/west` 在那里会读作租户 `acme` 再接一段路径。升级前，请在拥有该 id 的系统里（往令牌写 `tenantId` 的签发方、应用的注册处）
给这样的租户或应用改名：

- **查找方法：**在数据副本上运行 `cosec-migrate convert`：它对每个这样的 id 给出 `SLASH_IN_ID` 拒绝项，位置在应用
  （`app-role-permissions.json[<i>]`），或在 `--partition by-owner` 时在 `tenantId` 含 `/` 的策略上。只出现在令牌里的租户不在 7.x 数据中：
  请在签发方的租户里搜索 `/`。
- **`partition plan`** 以同样方式拒绝这样的租户，什么也不写；**`api-keys`** 拒绝这类租户的密钥。
- **令牌**的 `tenantId` 或 `aud` 含 `/` 时，每个适配器都回答 `401 token-invalid`，所以租户改名后，这样的主体需要重新登录。
  在网关之后，这类租户的转发身份是 `400 invalid-request`。

其他字符保持不变，文件名仍会编码它们：`acme%west` 转换为 `tenants/acme%25west.json`。

#### 报告中的语义改动 {#semantic-changes-in-the-report}

`semanticChanges` 按 7.x 数据中的位置，列出 8.0 有意做出不同决策的形态。`change` 是[8.0 在哪些地方决策不同](#what-8-0-decides-differently)
中的规则 id（`C1a`–`C8`、`D1`–`D3`）之一，或者：

- `ASCII-CASE`：带 `ignoreCase` 的 ALLOW 条件。8.0 只折叠 ASCII 字母，所以这条 ALLOW 匹配的写法更少。DENY 条件的值含 ASCII 以外的字母时，
  这个条件改为被拒绝。
- `RATE-LIMIT`：一条 7.x 限流规则，翻译成了平台文档的限流规则（[限流规则变为义务](#rate-limit-rules-become-obligations)）。
- `PIP-18`：每次转换记一次：HEAD 请求按 GET 判定，所以凡是允许或拒绝 GET 的地方也同样允许或拒绝它。语句上也会记录：从 ALLOW 中去掉的方法 HEAD（与 GET 并列时它没有更多含义，去掉时不记录），或者被去掉的只针对 HEAD 的 ALLOW，这使它变窄。
- `IP2REGION`：语句上，`ipRegion` 的国家前缀按 `ext.ip2region.country` 读取；每次转换记一次：平台文档声明了这个属性，所以执行这些语句的每个宿主都需要 `cosec-ip2region`（没有它，该属性取不到，这样的 DENY 是 Indeterminate，`503`），且已加载的库都不覆盖的地址同样取不到（`ATR-9`）。
- `NOT-IN-8.0`：读取 8.0 从不设置的部件（appender 设置的请求属性、`resource.*`）、或含 HEAD 而不含 GET 的方法的 ALLOW 条件：这条 ALLOW 匹配的请求更少。DENY 条件中同样的情形会被拒绝。

逐条阅读。报告中的条目都不要求修改数据，但每一条都可能改变某个响应。

#### 发布 {#publish}

```bash
cosec-migrate publish --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --in ./converted [--resume]
```

`publish` 只读取被接受的转换，而且只读取 `report.json` 列出的文件，并校验其中记录的 SHA-256：`convert` 之后被修改、缺失或新增的文件会让
它停止。你自己写的文档（而不是 `convert` 写出的）用 `--file <document.json> --document <platform|app:<id>|tenant:<id>>` 代替 `--in` 发布，检查相同。然后它编译每一份文档，按平台文档检查每份应用文档的引用，并拒绝含 break-glass 绑定的文档，什么也不写（`bootstrap` 同样拒绝）。它经过控制面的存储把每份文档写成修订 1，所以文档的修订历史从这里开始；并且只在文档尚不存在时写入：它从不覆盖 8.0 数据。

每份文档写一次。如果某一次失败，消息会列出已经写入的文档；用 `--resume` 再运行一次，它跳过内容相同的已发布文档，拒绝内容不同的。
`--key-prefix` 是部署的前缀，与 [`cosec.key-prefix`](./configuration.md#key-prefix) 相同。`publish` 没有 `--dry-run`；想知道它会做什么，读 `report.json`。

`publish` 之后，只通过控制面修改策略。再次运行 `convert` 和 `publish` 不会更新已存在的文档。

#### 引导控制面 {#bootstrap-the-control-plane}

```bash
cosec-migrate bootstrap --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --platform-admin alice [--force]
```

控制面用快照中的策略授权自己的写操作，所以没有管理员时谁都不能写入。`bootstrap` 只写平台租户的文档 `Tenant((platform))`；平台文档本身来自 `publish`，所以先发布。`bootstrap` 在平台租户自己的文档中，把平台租户的主体 `alice`
绑定到内置策略 `cosec:platform-admin`，并记为一个 `BOOTSTRAP` 种类的修订。当这份文档已经把某人绑定到 `cosec:platform-admin` 时它拒绝执行；
`--force` 用于这些管理员全部丢失后的恢复。两种情况下它都只添加自己的绑定，并保留这份文档中的其余内容：策略、其他绑定、限流规则和角色。持有 Redis 凭证的人本来就能写入；`bootstrap` 只是留下一条记录。

文档按数据面的方式编译，用平台文档声明的限额和属性，所以恢复会保留使用 `ext.*` 属性的策略。读不到平台文档时，命令失败。

#### 分区计划 {#partition-plan}

```bash
cosec-migrate partition plan (--from-redis redis://localhost:6379 [--key-prefix cosec] | --from-files ./export) --out ./plan
```

以 `--partition platform` 发布之后，`partition plan` 分步把每个租户的可附加策略挪到该租户自己的文档，每一步都是带影子窗口的普通变更。它把数据转换两次，一次 `platform`、一次 `by-owner`，为每个租户写出一份文档，内容是该租户拥有的策略的**完全相同的副本**，写到 `--out` 下的 `tenant-<编码后的 id>.json`（编码与长度限制和转换目录的文件相同；控制台列出每个文件属于哪个租户）。它不向 Redis 写任何东西；计划的文件中有任何一个已存在时，什么也不写。

1. 把每个文件用 `apply` 的合并模式提交。租户文档只有在策略是平台同 id 策略的完全相同的副本时，才可以持有这个 id，所以变更通过检查；两层从此内容相同，引擎把它们当作一条策略解析，影子窗口没有差异。
2. 租户持有副本期间，平台的这条策略不能修改（`V2`，指出该租户）：修改会让一个 id 有两份内容，引擎会拒绝。副本也不能编辑。
3. 用平台变更删除平台的策略。允许删除租户持有副本的策略；租户的绑定随后解析到租户的副本，它授予的与平台的一模一样。
4. 这些副本此后是租户自己的策略，可以编辑。

#### 修复反向引用索引 {#repair-the-back-reference-index}

```bash
cosec-migrate repair-references --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec]
```

控制面维护一个"哪些文档引用了哪条策略"的索引，使平台变更不能删除文档仍在使用的策略，也不能新增与租户文档所用 id 相同的策略（[存储](./storage.md#redis-keys)）。`repair-references` 读取存储列出的每份文档的头，补上索引缺少的条目，并输出访问了多少文档、补了多少条引用。然后它删除没有任何头引用的条目（这些条目是未能写入头的发布登记的）并输出数量：留着的话，会永远拦住对那条策略的删除。只有文档的头不低于登记该条目的修订时才删除，所以仍在进行的发布的条目会保留，失败的发布留下的条目要等该文档发布到更高修订之后才能删除。因此它可以在控制面运行时执行，再运行一次也不会改变任何东西。头读不到的文档输出为 `FAILED <doc>: <原因>`，其余文档照常访问，命令以 `1` 退出。

在写这个 Redis 的每个控制面实例和每个 `cosec-migrate` 二进制都运行这个版本之后再运行它，不要在滚动升级期间运行；降级之后要再运行一次。只要还有旧版本实例在运行，它就按旧的分析自行判断：它检查的变更不受 RC1-4 的保护，它发布的 app 文档也不会被列入。每个新实例在启动时对账一次，之后每次回收任务再对账：完整的列表缺了某份有头的 app 文档时，列表被标回不完整（[存储](./storage.md#publishing-through-the-control-plane)），新实例于是让覆盖所有 app 的授出退回审批。升级完成之后运行的那次 `repair-references`，才结束这个窗口。

然后它把文档中的每份 app 文档列入 app 列表，并把列表标记为完整，输出 `The app list is complete: <n> app(s).`；在此之前，授出分析把覆盖所有 app 的每个授出都当作未知。如果列表在它执行期间一直在变，它输出 `FAILED the app list could not be completed` 并以 `1` 退出：再运行一次即可。它补全的列表只和 `<s>:control:list:documents` 一样可靠：只要那个列表没有丢失，它就列着控制面或 `cosec-migrate` 发布过的每一份文档；以其他方式写进 Redis 的文档两个列表里都没有。

#### 检查修订链 {#verify-the-revision-chains}

```bash
cosec-migrate verify-chain --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] [--document platform|app:<id>|tenant:<id>]
```

每个修订都记着它的父修订及其摘要，控制面把每个修订的记录按摘要保存（[存储](./storage.md#revisions-history-and-rollback)）。Redis 异步复制，故障切换可能丢失它已经确认过的记录。`verify-chain` 对存储列出的每份文档（或 `--document` 指定的那一份），从头开始沿父修订一路检查到修订索引仍列着的最早修订，每份文档输出一行：

- `ok <doc>: intact down to revision <n>`；没有头的文档输出 `ok <doc>: no head`；
- `BROKEN <doc>: no record holds revision <n> (<digest>): ...`：链需要的某个修订的记录不存在，或者存的是另一个修订；
- `INDEX <doc>: the revision index does not follow the head's chain: ...`：这份文档的修订索引落后于头的幅度超过一次正在进行的发布，或在另一条分支上（故障切换或部分恢复所致，`CTL-35`）：它的最后一项不是头，或某一项指向的父修订不是它下面那一项。**这是一个发现，会使本次运行失败。**没有任何东西丢失：下一次读取这份文档的历史时，会从头的记录重建索引；`verify-chain` 自己不修复；
- `note <doc>: the revision index is one revision behind the head ...`：索引恰好止于头的父修订：一次正在完成的发布（头已写入，索引项尚未写入），或索引写入失败的发布。**不算失败**；下一次读取会补上这一项；
- `note <doc>: <n> revision index entries predate the parent field ...`：旧版本写下的条目，在第一次读取重写它们之前无法检查。**不算失败**；
- `FAILED <doc>: <原因>`：这份文档读不到；其余文档照常检查。

最后输出 `Verified <n> document(s): <b> broken, <f> could not be read.`，有索引不一致时在句号前加 `, <i> with a revision index that does not follow the head's chain`。只要有链断开、索引不一致或读不到，它就以 `1` 退出。被[保留策略](./storage.md#revisions-history-and-rollback)回收的修订不算断链：检查止于仍保留的最早修订。它只读，所以可以在控制面运行时执行，也可以随时重复执行。

断链不改变实例执行的内容，实例执行的是头。它说明 Redis 丢了写入：这份文档的历史缺了那个修订，故障切换前后发布的变更也可能不在头里。把头与发布过的内容对照，把缺的部分重新发布，做法与修订回退之后相同（[排障](./troubleshooting.md#decisions-use-an-old-revision)）。

#### 以 Kubernetes Job 运行 {#run-as-a-kubernetes-job}

在集群里，把每条命令作为 Job 运行，放在能连上 Redis 的命名空间，这样不必从你的机器访问 Redis。init 容器下载 release 的 zip、校验 SHA-256 并解压到一个卷；主容器在普通 JRE 上运行工具。带凭据的 Redis 地址来自一个 Secret，作为 `--to-redis` 交给工具（工具只从选项读取 Redis 地址；Kubernetes 会展开 `args` 里的 `$(REDIS_URI)`）：

```bash
kubectl -n cosec create secret generic cosec-migrate-redis --from-literal=uri='redis://:<password>@redis:6379'
```

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: cosec-repair-references
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    spec:
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        seccompProfile:
          type: RuntimeDefault
      volumes:
        - name: tool
          emptyDir: {}
      initContainers:
        - name: fetch
          image: eclipse-temurin:21-jdk # 有 curl 和 jar；JRE 镜像没有 jar
          env:
            - name: VERSION
              value: 8.0.0 # 你要部署的 CoSec 版本
          volumeMounts:
            - name: tool
              mountPath: /tool
          command: ["sh", "-ec"]
          args:
            - |
              cd /tool
              base="https://github.com/Ahoo-Wang/CoSec/releases/download/v${VERSION}"
              curl -fsSLO "$base/cosec-migrate-${VERSION}.zip"
              curl -fsSLO "$base/cosec-migrate-${VERSION}.zip.sha256"
              sha256sum -c "cosec-migrate-${VERSION}.zip.sha256"
              jar xf "cosec-migrate-${VERSION}.zip"
              mv "cosec-migrate-${VERSION}" cosec-migrate
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
      containers:
        - name: migrate
          image: eclipse-temurin:21-jre
          env:
            - name: REDIS_URI
              valueFrom:
                secretKeyRef:
                  name: cosec-migrate-redis
                  key: uri
          volumeMounts:
            - name: tool
              mountPath: /tool
          # `jar` 不恢复可执行位，所以启动脚本经由 `sh` 运行。
          command: ["sh", "/tool/cosec-migrate/bin/cosec-migrate"]
          args: ["repair-references", "--to-redis", "$(REDIS_URI)", "--key-prefix", "cosec"]
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
```

`kubectl -n cosec logs job/cosec-repair-references` 显示工具的输出；命令失败时（退出码 `1` 或 `2`）Job 也失败。工具输出的内容不含它收到的 Redis URI 中的凭据，因此日志里没有密码：它打印的下一步命令（例如 `publish ... --resume`）需要再次使用 `$(REDIS_URI)`。`--key-prefix` 设为你的实例的 [`cosec.key-prefix`](./configuration.md#key-prefix)。运行 `bootstrap` 时把 `args` 改为 `["bootstrap", "--to-redis", "$(REDIS_URI)", "--platform-admin", "alice"]`。Redis Cluster 则给支持的命令加上 `--cluster`，并在 Secret 里写以逗号分隔的种子节点。

`convert`、`publish`、`partition plan` 与 `api-keys` 读写目录：在主容器的 `/data` 挂载一个 PersistentVolumeClaim，路径写成 `--out /data/converted` 与 `--in /data/converted`。把 `convert` 作为一个 Job 运行，从卷上读 `report.json`（再起一个挂载它的 Pod，或用 `kubectl cp`），转换被接受之后才把 `publish` 作为另一个 Job 运行：`publish` 只接受 `report.json` 列出的、SHA-256 与之一致的文件。

#### 回滚 {#rollback}

`publish` 和 `bootstrap` 只写 8.0 的 key（`<prefix>:snapshot:*` 和 `<prefix>:control:*`），8.0 也从不读取或修改 7.x 的 key。要回滚，
重新部署 7.x：它读到的 7.x 数据保持原样。切换之后通过 8.0 控制面做的变更不在 7.x 数据中。从 `convert` 开始一直停止 7.x 的写入方，直到你
不再打算回滚；或者在发布之前重新转换：`convert` 之后的 7.x 写入不在转换出的文档中。

7.x 的策略历史（`<prefix>:policy:history:*`）既不转换也不删除。需要的话请自行导出。

### 从 4.x、5.x 和 6.x 升级 {#from-4-x-5-x-and-6-x}

7.x 之前的版本没有单独的迁移工具。受支持的路径是 `cosec-migrate convert --from-files`：你把策略导出为它读取的三个文件，它像转换 7.x 的数据一样转换它们。数据在 Redis 里的 6.x，先经过 7.x 更省事，如本节开头所说。本页其余关于数据的内容，从这里起都适用。没有 CoSec 数据要转换的应用，把它们全部跳过（[没有自己的 CoSec 数据？](#_7-x-to-8-0)）。

**这三个文件是什么。**`convert --from-files <dir>` 读取 `policies.json`（策略数组，每个策略有 `id`）、`global-policies.json`（适用于所有人的策略的 id 数组）和 `app-role-permissions.json`（数组，每个应用一项：它的权限列表 `appPermission` 和角色授权 `grants`）。它按 7.x 自己的反序列化器读取策略 JSON，4.3 写出的就是这种（`{"all": {}}`、`{"path": {...}}` 和 `{"authenticated": {}}` 对象，路径的简写是一个模式字符串）。`--from-redis` 只读取 7.0 的布局，所以读不了更老的存储。存储的权威来源是自己数据库的应用，自己写导出程序：按你的版本序列化每个策略的方式写出，并写这三个文件。一个很小的导出：

```json
[
  {
    "id": "orders-reader", "name": "Orders reader", "category": "order", "description": "Reads orders, never deletes them",
    "type": "custom", "tenantId": "(0)",
    "statements": [
      {
        "name": "read-orders", "effect": "allow",
        "action": { "path": { "pattern": "/orders/**", "method": "GET" } },
        "condition": { "authenticated": {} }
      },
      { "name": "no-deletes", "effect": "deny", "action": { "path": { "pattern": "/orders/*", "method": "DELETE" } } }
    ]
  }
]
```

保存为 `policies.json`，`global-policies.json` 为 `[]`，应用的权限及持有它们的角色是：

```json
[
  {
    "appPermission": {
      "id": "orders",
      "groups": [
        { "name": "order", "description": "order management",
          "permissions": [ { "id": "order.ship", "name": "Ship", "description": "Ship", "action": "/order/ship" } ] }
      ]
    },
    "grants": { "clerk": ["order.ship"] }
  }
]
```

保存为 `app-role-permissions.json`。`cosec-migrate convert --from-files export --out converted` 接受这两份文件，语义变化为 `C1b` 和 `PIP-18`，并写出平台文档和应用文档 `orders`。

**什么能转换。**下面每一行都用 `convert --from-files` 分别作为 ALLOW 和 DENY 语句转换过；以你自己的数据的报告为准，请阅读它（[拒绝](#rejections)、[报告中的语义变化](#semantic-changes-in-the-report)）。

| 构造 | 结果 |
|---|---|
| 动作 `*` 或 `{"all": {}}` | ALLOW 变成 `http:*`，即每个 HTTP 请求，不含控制面动作和代码中检查的动作；DENY 保持 `*`（[所有动作即所有 HTTP 请求](#every-action-is-every-http-request)）。 |
| 动作 `{"all": {"method": "GET"}}`、路径模式（字符串、数组、`{"path": {"pattern", "method"}}`、`**`） | `http:<METHOD>` 或 `http:*`，作用在 `http:path:<pattern>` 上。 |
| 条件 `all`、`authenticated`、`inTenant`、`in`、`startsWith`、对方法的 `eq`、带 `and` 和 `or` 的 `bool` | 8.0 语言中的同一个条件；`all` 则没有条件。 |
| `inRole` | 对 `scope.roles`（ALLOW）或 `subject.roles`（DENY）的 `in`，记录为 `D1`。 |
| 与 `#{principal.id}` 这类引用做 `eq` | 与属性的比较，记录为 `D2`。 |
| 模式是字面量的 `regular` | 仅 ALLOW，转为带 `ignoreCase` 的 `eq`，记录为 `ASCII-CASE` 和 `D3`。 |
| 每一个 DENY | 如上转换，各记录为 `C1b`。 |
| 带路径动作的应用权限及其角色授权 | 一份应用文档：目录、每个权限一条 `perm:<app>/<id>` 策略、每个授权一条角色绑定（`role` 或 `role@space`）。 |
| 部署的限流规则 | `--rate-limit-rules`，用 7.x 的文件格式（[转换](#convert)）。 |

**什么需要你来判断。**每一项都以一条说明要改写什么的信息被拒绝；在导出的数据里修正，然后重新转换：带 `#{...}` 模板的路径（`ACTION_NOT_TRANSLATED`：[动作中的路径模板](#path-templates-in-actions)）；由不同方法组成的复合动作（`ACTION_NOT_TRANSLATED`：按方法拆开）；对客户端地址的 `eq`、`contains`、`endsWith`、模式不是字面量的 `regular`、对 `request.path` 之外的部分的 `path`，以及 DENY 里的 `regular`（`CONDITION_NOT_TRANSLATED`）。

**什么不能转换。**`spel` 和 `ognl` 条件（`EXPRESSION_CONDITION`）、自定义的动作和条件类型（`CUSTOM_ACTION`、`CUSTOM_CONDITION`），以及 4.x 的 `rateLimiter` 和 `groupedRateLimiter` 条件：它们以 `UNDECODABLE` 加 `CUSTOM_CONDITION` 被拒绝，因为 7.x 没有这种条件，它们的 `UNDECODABLE` 信息说明输入不是 7.x 格式，并指向本节。请改写为[限流规则](../policies/rate-limiting.md)。策略历史不会被转换。

**工具无法决定的事**，需要你在发布之前决定：

1. **ALLOW `*` 应该是什么意思。**它变成 `http:*`：与它原来放行的请求相同，不含控制面的任何东西。如果你的 4.x 策略把它用在代码中检查的动作上，请把那些动作写出来。
2. **角色授权放在哪里。**`convert` 把每个授权写成应用文档的绑定，应用文档属于平台，在每个租户中都适用；`--partition by-owner` 把策略移到它们的租户，不移动授权，因为授权不带租户。角色归租户所有的身份系统，应改为通过控制面把它们作为租户文档的 `roles` 投影进去（[外部事实源同步角色](../scenarios/external-source.md)）。
3. **你的应用写入策略时做的检查由什么取代。**`convert` 是命令行，不是库。在 8.0 中，文档在提出变更时由控制面检查，无法编译的策略是带发现的 `DRAFT`（[策略测试](../policies/testing.md#validating-policies)）。

然后像 7.x 的数据一样[发布](#publish)。应用自己的代码按本页其余部分迁移：它的 import 和已删除的 API（[已删除和被取代的 API](#removed-and-replaced-apis)）。

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
| **C7**（`MIG-2`） | 针对所有动作（`*`）的 DENY，对它匹配的请求，也拒绝代码中的检查和控制面的动作（`cosec:*`）。 | 只决策 HTTP 请求。 | 针对所有动作的 DENY 语句（[所有动作即所有 HTTP 请求](#every-action-is-every-http-request)）。 |
| **C8**（`MIG-2`） | 针对所有动作的 ALLOW 是 `http:*`，即所有 HTTP 请求，而[路由映射](../policies/language.md#route-maps)会把它映射的每条路由从中拿走：被映射的路由是它的业务动作，转换后的策略并不授予它。 | 该路由就是一个 HTTP 请求。 | 针对所有动作的 ALLOW 语句，报告在它们的动作处记录。加入路由映射后，请用各自的策略授予被映射的动作（[所有动作即所有 HTTP 请求](#every-action-is-every-http-request)）。 |
| **D1** | ALLOW 中的 `inRole: r` 只匹配在请求空间中生效的角色；ALLOW 中的 `inRole: r@s` 只在请求处于空间 `s` 时匹配；DENY 中的 `inRole: r` 匹配在任意空间持有的该角色。 | 按字面匹配角色，裸角色也匹配 `r@<请求的空间>`。 | `inRole` 条件。DENY 只会变宽，ALLOW 只会变窄。 |
| **D2**（`CND-1`） | 不存在的属性不等于任何值，也不等于空串。 | 不存在的 part 读作 `""`。 | 与 `#{principal.id}`、`#{principal.attributes['k']}` 或 `#{tenant.tenantId}` 比较的 `eq`。ALLOW 中，不存在的 part 不再匹配空的属性（变窄）。DENY 中，`convert` 补上 7.x 的情形，保持 7.x 的读法。 |
| **D3** | 没有正则表达式，所以也没有正则超时。 | `RegexTimeoutException` 按默认拒绝处理。 | `regular` 条件；只有字面量、字面量前缀和字面量选择的模式能翻译。 |
| **D4**（`ALG-10`） | 有多个路径模式的语句，只要条件在任一匹配模式的变量下成立即命中。 | 只读第一个匹配模式的变量。 | 有多个模式、条件读取 `request.path.var.*` 的路径动作：DENY 现在拒绝任一模式会拒绝的请求，ALLOW 放行任一模式会放行的请求。 |

#### 声明空间的匿名请求返回 401 {#anonymous-requests-that-declare-a-space-are-401}

规则 `SCP-3`：带非空 `CoSec-Space-Id` 请求头的匿名请求返回 `401` `unauthorized`，即使路径对所有人开放。匿名主体没有角色，
所以不是任何空间的成员（C3）。令牌被拒绝的请求也是匿名请求，它得到带该令牌 problem 类型的 `401`。在公开端点上发送 `CoSec-Space-Id`
的客户端必须停止在那里发送它。

#### root 变为 break-glass {#root-becomes-break-glass}

`cosec.authorization.root-enabled` 和根主体已删除。8.0 中对应的是 break-glass 绑定：有时间上限（最多 24 小时，
`cosec.control.break-glass.max-duration`，默认 4 小时），受每一条 DENY 约束（C2），并且只有在 REQUIRED 决策监听器记录了请求时才能放行
（否则 `503`，规则 `OBS-2`）。需要时通过控制面申请，由两个人完成。`cosec-migrate` 不写 break-glass 绑定：`convert` 不转换 7.x 的 root，
`--root-enabled`、`--root-id`、`--root-expires-at` 与 `publish --break-glass-max-duration` 已删除（传入时报用法错误并说明原因）。

#### 所有动作即所有 HTTP 请求 {#every-action-is-every-http-request}

7.x 只决策 HTTP 请求，所以它表示"所有动作"的写法指的是所有 HTTP 请求：模式 `*`、不带方法的 `all`、含 `*` 的 `path` 数组，以及含其中之一的数组或
composite。8.0 中 `*` 还匹配控制面的动作（`cosec:*`）和[代码中的检查](../integration/checks.md)的动作，这些 7.x 从未授予过。所以 `convert`
把针对所有动作的 ALLOW 写成 `"action": "http:*"`（`MIG-2`），对 HTTP 请求的决策与 7.x 相同。

针对所有动作的 DENY 仍是 `"action": "*"`：它继续拒绝它拒绝过的请求，现在也经由代码中的检查和控制面拒绝，报告把它记为 C7。如果这样的 DENY
可能匹配管理员自己的请求，它也会拒绝管理员使用控制面。

`http:*` 上的语句永远不匹配[路由映射](../policies/language.md#route-maps)映射过的请求。加入路由映射之后，用单独的策略授予它命名的动作。报告把每条针对所有动作的 ALLOW 记为 C8，位置在它的动作处，这样加入路由映射那天不再被允许的路由有一行可查。

#### 动作中的路径模板 {#path-templates-in-actions}

带 `#{...}` 模板的 7.x `path` 动作，例如 `/users/#{principal.id}/**`，会被 `convert` 拒绝。8.0 中由路由映射给出资源及其 id
（`"path": "/users/{userId}"`、`"resource": "user/{userId}"`），再用一个条件把资源属性与主体比较。见[策略语言](../policies/language.md)。
`eq` 条件中的 `#{...}` 模板可以翻译（D2）。

#### 限流规则变为义务 {#rate-limit-rules-become-obligations}

7.x 的限流规则文件（`cosec.limiter.*`）已删除。`convert --rate-limit-rules` 把规则翻译成平台文档的限流规则。与 7.x 一样，规则作用于策略
放行的请求：第一条耗尽的规则返回 `429`，被拒绝的请求不消耗许可。带 `strictFailure` 的规则在限流器失败时，现在返回 `503` 而不是 `429`（R3）。
存在 `ReactiveStringRedisTemplate` bean 时由 Redis 限流器计数，否则每个实例各自计数。它的 key 是新的，
`<cosec.key-prefix>:rate-limiter:{<rule>:<bucket>}:<window>`，让一个桶的两个窗口在 Redis Cluster 的同一个 slot 上：7.x 与 8.0
的实例同时服务期间，以及最后一个 7.x 实例停止后的至多两个窗口内，两个版本各自计数，一条规则至多放行两倍的上限。见[限流](../policies/rate-limiting.md)。

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

规则 `SCP-4`：当 `CoSec-Space-Id` 或 `CoSec-Device-Id` 以两个不同的非空值出现（请求头与查询字符串之间，或其中任一之内），
请求返回 `400`。7.x 不会拒绝这样的请求。

#### Spring MVC 只从查询字符串读取查询参数 {#spring-mvc-reads-query-parameters-from-the-query-string-only}

在 Spring MVC 上，7.x 用 `HttpServletRequest.getParameter` 读取查询参数，而它也会返回 `application/x-www-form-urlencoded` 请求体中的字段。
所以对表单 POST，容器会在 CoSec 的过滤器中解析并消费请求体，请求体字段也被当作查询参数。8.0 在每个适配器上都只读取 URL 查询字符串，
并且永远不碰请求体。
表单字段不再设置 `CoSec-Space-Id` 或 `CoSec-Device-Id`，也不再设置在 `cosec.pipeline.allow-query-token=true` 时读取的
`authorization` 令牌。把这些作为表单字段发送的客户端，必须改为通过请求头发送；令牌在开启该配置项时也可以放在查询字符串中。

### 认证 {#authentication}

#### 令牌吊销始终开启 {#token-revocation-is-always-on}

`cosec.jwt.token-revocation.enabled` 已删除。启用 JWT 时，每个令牌都要检查是否已吊销；没有任何东西能回答这个检查时，启动失败：加上
`cosec-cocache`（它带来 Redis 吊销存储），或者注册自己的全部三个 bean：`TokenStore`、`RevocationReader` 和 `TokenFamilyStore`。启动失败信息一次列出所有缺少的 bean。`RevocationReader.NONE` 可以启动，会记录一条 `WARN`，并接受每个已吊销的令牌直到它过期。7.x 中没有 `cosec-cocache` 时，starter
使用一个什么都不记录的存储，登出悄无声息地不起作用。既不签发令牌也不读取令牌的服务可以改为设置 `cosec.jwt.enabled=false`
（[登出与吊销](../integration/authentication.md#logout-and-revocation)）；只签发令牌的服务保持它开启，并配好存储（[只签发令牌的服务](../integration/authentication.md#a-service-that-only-issues-tokens)）。7.x 写下的吊销继续有效：8.0 从相同的 key（`<prefix>:token:revoked:*`）
读取它们。吊销检查报告的错误返回 `503`，永远不会接受该令牌（`PIP-7`）。

#### 令牌与 API key {#tokens-and-api-keys}

- 7.x 签发的令牌继续可用：8.0 读取同样的声明（`roles`、`policies`、`attributes`、`tenantId`），并按 7.x 的方式读取角色声明（`role` 在默认空间，
  `role@space` 只在该空间，规则 `MEM-3`）。
- **6.x 签发的令牌**带有 6.1 的 `JwtTokenConverter` 所写的内容：`jti`、`sub`、`iat` 和 `exp`，以及主体拥有时的 `roles`（`role` 或 `role@space`）、`policies`、`attributes` 和字符串形式的 `tenantId`。只要持有相同的 `cosec.jwt.algorithm` 和 `cosec.jwt.secret`（长度不得短于下文要求），8.0 就能校验它们，并要求有 `jti`、`sub` 和 `exp`，6.x 总会设置它们。它们缺少的东西很要紧：**没有 `aud`**，所以请求没有应用（在没有 `cosec.authorization.scope.app-id` 的宿主上，`scope.appId` 为空，不读取任何应用文档；设置了它，每个这样的令牌都是 `403` `scope-rejected`，见[应用](../integration/authentication.md#apps)）；**没有会话声明**（`sid`、`auth_time`、`cosec.generation`），所以 8.0 的服务永远不会刷新 6.x 的令牌对：用户要在 8.0 的签发方重新登录。6.x 的签发方只有在 `cosec.jwt.token-revocation.enabled=true` 时才吊销，且按令牌 id 吊销；8.0 的校验方是否读取它写下的内容没有确定：不要指望 6.x 签发方的登出能到达 8.0 的校验方，在签发方升到 8.0 之前把访问令牌的有效期保持得短（[校验另一个服务所签发令牌的服务](../scenarios/resource-server.md)）。
- **带 `act` 声明的令牌现在会被拒绝**，结果为 `token-invalid`（`IDN-4`）。7.x 忽略这个声明，因此 RFC 8693 委托令牌被当作它的 `sub`
  读取，执行方得到主体的权限。CoSec 自己从不签发这种令牌；接受其他签发方令牌的校验器在支持委托之前不得放行它们
  （[认证](../integration/authentication.md#tokens)）。拒绝时审计记录原因码 `DELEGATION_UNSUPPORTED`；放行时审计记录 `ALLOW`，
  原因只出现在决策事件的 `subject.rejected` 中。刷新或吊销这种令牌同样失败。
- **`tenantId` 声明为空或不是字符串**，或 `attributes` 声明中有 `apiKeyId`（CoSec 只为 API key 设置），令牌为 `token-invalid`；7.x 把这样的租户读作没有租户。
  **`cosec.jwt.secret`** 对 `hmac256`、`hmac384`、`hmac512` 分别至少 32、48、64 个 UTF-8 字节，否则启动失败。
- **break-glass（7.x 的 root）** 只作用于 CoSec 自己的 `JwtTokenVerifier` 校验过的令牌，从不作用于自定义 `TokenVerifier` 的令牌，也不作用于其他认证器认可的主体（`BRK-8`）。
- **租户切换与社交登录。** 租户切换（7.x 的 `AbstractSwitchTenantAuthentication`，现为带 `TenantSwitch` 的 `SwitchTenantAuthentication`，见[从 8.0.0-alpha.4 升级](./upgrading-previews.md#from-8-0-0-alpha-4)）回答用户在目标租户和应用中的 `TenantMembership`，而不是一个主体，因此新令牌不会带上先前租户的角色。
  `DirectSocialUserPrincipalConverter` 只签名 `cosec_oauth_provider` 和 `cosec_oauth_user_id`，不签名提供方的用户信息（[认证](../integration/authentication.md#tenants)）。
- `cosec.api-key.header` 改为 `cosec.pipeline.api-key-header`（默认 `X-API-Key`），任何文档都不能读取它指定的请求头（`CND-5`）。`cosec.api-key.store-blocking` 已删除：Redis 的 key
  读取器从不阻塞。携带 API key 的请求只按 key 判定，即使它同时携带令牌（`PIP-8`）。
- `cosec.authorization.allow-query-token` 改为 `cosec.pipeline.allow-query-token`（默认 `false`）。
- 7.x 自定义的 `SecurityContextParser` bean 不再被使用。改为注册一个 `Authenticator` bean（[认证](../integration/authentication.md)）。
- `cosec.authentication.enabled` 改为 `cosec.authentication.login.enabled`，`cosec.authentication.social.*` 改为
  `cosec.authentication.login.social.*`。这个开关打开或关闭登录 bean（`TokenCompositeAuthentication`、你的 `Authentication` bean、社交登录）；
  无论它如何设置，请求都由管道认证。
- 刷新令牌时应用与请求相同的 `cosec.authentication.revocation.skew`（默认 `5s`）：签发时间在主体吊销之后不超过该偏差的令牌不能再被刷新。

#### API key 只收窄，成员关系决定 {#api-keys-narrow-and-memberships-decide}

7.x 中 API key 自带角色、策略和属性的授予。8.0 中 key 不授予任何东西：它的成员关系就是它的主体的，来自 `MembershipProvider`，再按 key 的 `roles` 收窄（RFC 0009 §4，[API key](../integration/authentication.md#api-keys)）。会破坏的地方：

- **`MembershipProvider` 返回 `MembershipAnswer`**（`Known(memberships)` 或 `Unknown`），`MembershipLoader.load` 也一样。为 8.0 里程碑版本编写、返回 `Memberships` 的提供者不再能编译：为每个它不认识的主体决定答案 `Unknown`，不要再对它返回空的成员关系，否则该主体的 API key 会继续有效。`Memberships` 增加了 `active`，已停用的主体为 false。未知或已停用主体的 API key，以及它带 `cosec.membership: provider` 标记的令牌，都按 `token-invalid` 拒绝（`IDN-3`）。约定见[扩展](../architecture/extending.md#memberships)。
- **已存储的 key。** Redis 格式改变。8.0 之前存储的 key 在读取时被拒绝：带它的请求返回 `503`，日志会提到 `cosec-migrate api-keys`。切换前先转换 key（见下文）。
- **必须有提供者。** `cosec.api-key.enabled=true` 而没有 `MembershipProvider` Bean 时启动失败（`IDN-6`）。`cosec.api-key.enabled` 只能是 `true` 或 `false`；`on`、`yes` 或 `1` 会导致启动失败。
- **上限同样收窄主体绑定和 break-glass。** 带 `roles` 的 key 得不到其主体的主体绑定和附加策略的任何授权，但它们的 DENY 照样作用于它；`roles: []` 只有 `Anyone` 绑定给予的，任何 API key 都得不到 break-glass（`BND-9`、`BRK-8`）。
- **配置中的 key**（`cosec.api-key.keys[]`）：`tenant-id` 必填，每个 key 恰好设置 `roles`（它的上限；`[]` 表示没有角色）与 `uncapped: true` 之一。`policies` 和 `attributes` 已删除；仍设置它们的 key 会导致启动失败，`roles` 与 `uncapped` 都不设或都设的 key 也一样。7.x 的 key 只拥有它列出的角色和策略；要保留一个什么都没有的 key，给它设置 `roles: []`。
- **`ApiKey`** 是普通类 `ApiKey(id, principalId, tenantId, roles, expiresAt, enabled)`：`policies`、`attributes` 和 `toPrincipal()` 已删除，`tenantId` 必填，`roles: Set<String>?` 是上限，且没有默认值。

**转换已存储的 key。** `--app <id>` 指明转换后的 key 属于哪个应用（[应用](../integration/authentication.md#apps)）；7.x 的 key 没有声明应用，不指定时配置了应用的服务会拒绝它们。`cosec-migrate api-keys` 读取部署读取 API key 的 `<--key-prefix>:api-key`（默认 `cosec:api-key`）下的每个 key，并把 `api-keys-report.json` 写入 `--out`。导出每个主体当前拥有的角色，格式为 JSON 数组 `{"tenant", "principal", "roles": ["role", "role@space", ...]}`，作为 `--memberships` 传入：

```bash
cosec-migrate api-keys --redis redis://localhost:6379 --memberships ./memberships.json --out ./api-keys --dry-run
```

每个 key 走三条路之一（`keys[].mode`）：

| 模式 | 条件 | 之后的 key |
|---|---|---|
| `in-place` | `--memberships` 表明其主体拥有该 key 列出的每个角色，且该 key 没有附加策略 | 留在其主体上，上限为这些角色 |
| `own-principal` | 其他情况，默认 | 迁到其租户中的工作负载主体 `apikey:<key id>`，没有上限；`memberships` 列出该主体需要拥有的 key 的角色，`principalBindings` 把它附加的策略按名绑定给该主体 |
| `granted` | 只针对 `--grant-to-principal <ids>`（或 `all`）列出的主体 | 留在其主体上，上限为它的角色加上它自己的一个角色（`--role-prefix`，默认 `api-key.`，加上 key id），`roleBindings` 把这个角色绑定到它附加的策略；主体被授予其各个可用 key 的上限之并，`memberships[].newlyGained` 指出它原来没有的 |

没有 key 会获得更多权限；除非在 `--grant-to-principal` 中列出，也没有主体会获得更多权限。key 失去的东西按 key 列出：`lost` 是 key 不再能用到的其主体的主体绑定和 break-glass 绑定；对 `own-principal`，`changes` 是依赖主体 id 的东西（审计主体、`subject.id` 上的条件、按主体计数的限流桶、转发的 `CoSec-Identity` 的 `sub`）。

以下情况 key 被拒绝，此时命令以 `1` 退出，不写入任何 key：带属性（条件可能读取它们）、角色格式错误、id 含 `@`、`*` 或空白、某个角色以角色前缀开头、某个文档已经绑定了按 key 生成的角色，或附加的策略不能精确解析：不存在、不可附加，或租户和平台都定义了它但内容不同。这样的附加在 8.0 中让该 key 的每个请求都不确定，丢掉它又可能丢掉一个 DENY：先修正文档或 key。7.x 不保证 key id 唯一，所以以下情况 key 也会被拒绝：同一租户中另一个 key 的 id 相同（两者会变成同一个主体，或共用一个按 key 生成的角色）；`own-principal` 的 key 要迁往的主体 `apikey:<key id>` 已经存在，即某个文档按名绑定了它，或 `--memberships` 列出了它，这时 key 会获得该主体拥有的权限。`--memberships` 文件中同一主体出现两次，或 `tenant`、`principal`、`roles` 的类型不对（`roles` 是字符串数组，只有 `[]` 表示没有角色）时被拒绝。没有租户的 key 归入默认租户（`defaultTenant`）。

没有角色的 `own-principal` key 也会列在 `memberships` 中，`roles: []`。它的主体同样必须在你的 `MembershipProvider` 中存在，只是没有角色：提供者不认识的主体会被拒绝，带该 key 的每个请求都会得到 `401`。

1. 带 `--dry-run` 运行并阅读报告。
2. 在你的 `MembershipProvider` 的数据源中授予 `memberships`，并通过控制面（变更请求）添加 `principalBindings` 和 `roleBindings`。
3. 不带 `--dry-run` 再运行一次。每个 key 只在仍然是读取时的内容时才被改写，因此期间被吊销或停用的 key 绝不会被恢复：报告在 `changedSinceRead` 中列出它，命令以 `1` 退出并要求再运行一次。已经转换的 key 保持不变，因此命令可以重复运行。8.0 预览版以其他格式（`"format":2`）存储的 key 既不会被转换，也不会被 8.0 读取：命令会拒绝这次运行，并指出它是由预览版存储的；请为它签发一个新 key 并删除旧 key，然后再运行。

转换后的 key 不再保存原来的角色和策略：它们只存在于报告里。报告在第一个 key 被改写之前就写好（`status: planned`），运行结束时换成最终的（`converted`）；`--out` 中已有的报告若不是试运行或被拒绝那次运行的，命令在碰任何 key 之前就停止：重复运行会发现这些 key 已转换，而把计划替换成空的。重复运行请用自己的 `--out`，报告里的 `memberships` 和绑定都落实之后才把它移走。两次运行只有在第一次是 `--dry-run` 时才可以共用一个 `--out`。`--out` 在写任何东西之前先创建，写不了的 `--out` 会让运行先停下。`SCAN` 可能把同一个 key 返回两次；它只被读一次。

在第 2 步完成之前被转换的 key 会被拒绝或权限更窄，绝不会更宽。

#### 注入模式变为身份传递 {#inject-mode-becomes-identity-propagation}

7.x 的注入模式（`cosec.inject.enabled=true`）让网关之后的服务不经校验地解析转发来的 JWT。8.0 没有不校验就读取令牌的模式。取而代之的是：
网关转发它验证过的身份，其后的服务信任网关：

| 位置 | 7.x | 8.0 |
|---|---|---|
| 网关 | 转发客户端的 `Authorization` 头。 | 对有主体的放行，它在指向已注册服务的路由（`lb://`）上、以及元数据为 `cosec-identity: true` 的其他路由上设置 `CoSec-Identity`；它总是去掉客户端自己发来的。`cosec.gateway.identity.claims` 指定要转发的主体属性；路由元数据 `cosec-identity: false` 让该路由不接收身份。除非 `cosec.gateway.identity.forward-authorization=true`，转发的请求中会去掉 `Authorization` 头、API key 头、`authorization` 查询参数和 `authorization` cookie（[网关身份](./configuration.md#gateway-identity)）。 |
| 其后的每个服务 | `cosec.inject.enabled=true` | `cosec.pipeline.mode=gateway`（[网关之后的服务](../integration/web.md#services-behind-the-gateway)）：没有 key、策略、快照或 Redis；除非它签发令牌，否则设置 `cosec.jwt.enabled=false`。只有网关可以访问到该服务（网络策略、私有网络、mTLS）；其他能访问到它的人声称自己是谁就是谁。 |
| 应用代码 | 从 `SecurityContext` 读取调用方。 | 相同。附加策略不被转发，角色是网关决策时所在作用域中的角色。 |

永不过期的凭证（例如没有 `expires-at` 的 API key）不会被转发：网关对它的放行返回 `500`。给这类 key 设置过期时间，或让它们的路由不转发身份。

在 CoSec 网关之后、自己校验令牌的服务不再收到 `Authorization` 头。要么在网关上设置 `cosec.gateway.identity.forward-authorization=true`，
并经 `lb://` 或带 `cosec-identity: true` 的路由访问该服务，要么把该服务切换到 `cosec.pipeline.mode=gateway`。放在 `authorization` cookie 中的令牌在同一个开关下也会被移除。

#### 在较旧的网关之后上线 inject 模式服务 {#rolling-out-an-inject-mode-service-behind-an-older-gateway}

6.x 或 7.x inject 模式的服务位于一个网关之后，该网关转发客户端的 `Authorization` 头，并且不发送 `CoSec-Identity`。只要那个网关还在它前面，服务就不能直接切到 `cosec.pipeline.mode=gateway`，`main` 上有两个原因：gateway 模式的服务不读取令牌，所以没有 `CoSec-Identity` 的请求按匿名主体 `(0)` 继续，服务的每个调用方都是匿名的；而较旧的网关不会剥离客户端发送的 `CoSec-Identity` 头，服务却会相信它，于是任何能到达网关的人想是谁就是谁（[网关之后的服务](../integration/web.md#services-behind-the-gateway)）。可行的顺序：

1. **服务先切到 `enforce`，校验网关转发的令牌。** 它需要签发方的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`、签发方的吊销，以及一份放行其已认证调用方的平台文档：[校验另一个服务所签发令牌的服务](../scenarios/resource-server.md)。请求的应用是令牌的 `aud`，不来自任何头：网关或客户端发送的 `CoSec-App-Id` 不会被读取。6.x 或 7.x 签发方的令牌没有 `aud`，所以在签发方升到 8.0 之前请求没有应用（[令牌与 API key](#tokens-and-api-keys)）。
2. **网关升到 8.0**，并设置 `cosec.gateway.identity.forward-authorization=true`。不设置的话，8.0 网关会移除 `Authorization` 头，而仍在读取它的 `enforce` 服务会看到匿名请求。网关随后也会发送 `CoSec-Identity`；`enforce` 服务不读取它，它的适配器会把它从处理器看到的请求中剥离。
3. **服务切到 `gateway` 模式**，当 8.0 网关成为唯一入口之后：`cosec.pipeline.mode=gateway`，不再需要吊销存储和文档，并配上只放行网关的网络策略。此后可以去掉 `forward-authorization`。

停留在 `enforce` 模式的服务是一个完成的状态，不是一个步骤：它继续校验令牌，使用自己的文档和吊销读取器。

#### 客户端发送令牌和作用域的方式变了 {#clients-send-tokens-and-scope-differently}

- 7.x 在每个请求上都读取的 `authorization` cookie，现在只在 `cosec.pipeline.allow-cookie-token=true` 时读取：浏览器会自动带上 cookie，跨站请求也不例外，而 CoSec 不提供 CSRF 防护。把令牌放在 `Authorization` 请求头中，或者打开这个配置项并自行防御 CSRF（[认证](../integration/authentication.md)）。
- 7.x 也从查询字符串读取的 `CoSec-Space-Id` 和 `CoSec-Device-Id`，现在只从请求头读取（[应用与空间](../integration/web.md#app-and-space)）。

### 存储与 Redis {#storage-and-redis}

#### Redis key {#redis-keys}

8.0 把文档存在 `<prefix>:snapshot:*` 下，控制面的数据存在 `<prefix>:control:*`、`<prefix>:candidate:*` 和 `<prefix>:shadow:*` 下。它不读取
7.x 的 key：`<prefix>:policy:*`、`<prefix>:global:*`、`<prefix>:app:permission:*`、`<prefix>:app:role:permission:*` 和 `<prefix>:meta:schema`。
直接读写这些 key 的工具必须改用控制面。只给控制面的 Redis 账号 `<prefix>:snapshot:*` 和 `<prefix>:control:*` 的写权限。全部 key 见
[存储](./storage.md)。

#### 读不到 Redis 的驱逐策略时启动失败 {#startup-fails-when-redis-s-eviction-policy-cannot-be-read}

启动时 CoSec 读取 Redis 的 `maxmemory-policy`，不是 `noeviction` 就拒绝启动，因为被驱逐的平台文档会让其中的 DENY 悄无声息地失效。
它从 `INFO memory` 读取。`INFO` 失败或不被允许时，启动同样失败；CoCache 后端是包装器或检查读不了的自定义后端时也一样。如果你已经
自行设置了 `noeviction`，请设置 `cosec.authorization.snapshot.eviction-check=warn`：启动继续，并记录一条以 `!!!` 开头的警告。
CoCache 8 把 CoSec 的文档保存为 `Store`，它也检查这一策略，并且不受上述设置影响：还要设置
`cocache.redis.assume-maxmemory-policy=noeviction`（[Redis 要求](./storage.md#redis-requirements)）。

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
- **没有 `cosec-cocache` 时**，starter 使用一个初始为空的内存存储，所以在应用把文档放进去之前每个请求都是 `503`。本地策略文件不再被加载
  （`cosec.authorization.local-policy.*` 已删除）。
- 读取失败表现为 `SnapshotUnavailableException`（原异常作为其 cause）和 `503`，取代 7.x 的 `CacheReadException` 和 `500`。

发布、文档回滚以及 Redis 不可用时的行为见[存储](./storage.md)。

### 模块、制品与包 {#modules-artifacts-and-packages}

**starter 是 `cosec-spring-boot`，不再有 capability。** 7.x 的 `cosec-spring-boot-starter` 把每个集成作为一个 Gradle capability 提供（`webflux-support`、`cache-support` 等）。8.0 的制品是 `me.ahoo.cosec:cosec-spring-boot`，应用在它旁边添加所需的模块，Gradle 和 Maven 写法相同。starter 装配它在类路径上找到的每个模块。

```kotlin
// 7.x
implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.cosec:webflux-support") }
}
implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.cosec:cache-support") }
}
// 8.0
implementation("me.ahoo.cosec:cosec-spring-boot")
implementation("me.ahoo.cosec:cosec-webflux")
implementation("me.ahoo.cosec:cosec-cocache")
implementation("org.springframework.boot:spring-boot-data-redis")
implementation("me.ahoo.cocache:cocache-spring-boot-starter")
```

| 7.x capability | 8.0 模块 |
|---|---|
| `webflux-support`、`webmvc-support`、`gateway-support` | `cosec-webflux`、`cosec-webmvc`、`cosec-gateway` |
| `cache-support` | `cosec-cocache`，同时添加 `spring-boot-data-redis` 和 `cocache-spring-boot-starter` |
| `oauth-support` | `cosec-social` |
| `opentelemetry-support`、`ip2region-support` | `cosec-opentelemetry`、`cosec-ip2region` |
| `micrometer-support` | `cosec-micrometer`，它自带 `micrometer-core` |
| `openapi-support` | `cosec-openapi`，同时添加 `spring-boot-starter-actuator` 和 `springdoc-openapi-starter-common` |
| `kafka-support` | `cosec-kafka`，同时添加 `spring-boot-starter-kafka` |
| （8.0 新增） | `cosec-control`，即控制面，同时添加 `cosec-cocache` |
| （8.0 新增） | `cosec-cosid`，即来自 CosId 5 的 CoSec id（见下文） |

完整列表见[模块](../guide/introduction.md#modules)。已开启的功能所需的模块会在启动时检查：缺少适配器模块的 Web 宿主、缺少 `cosec-gateway` 的网关、缺少 `cosec-cocache` 的 `cosec.api-key.store=redis`、缺少 `cosec-kafka` 的 `cosec.audit.kafka.delivery=required`，都会启动失败并给出制品名（[缺少模块时启动失败](./troubleshooting.md#startup-fails-on-a-missing-module)）。

**`cosec-core` 不再存在。** 它的代码按性质拆分：应用要实现的端口在 `cosec-spi`（`me.ahoo.cosec.spi.*`）；文档、快照、令牌校验和写日志的审计 sink 在 `cosec-runtime`（`me.ahoo.cosec.runtime.*`）；登录与签发令牌在 `cosec-login`（`me.ahoo.cosec.login`）。starter 会引入这三个模块。只实现某个端口的库依赖 `cosec-spi` 即可。移动了的包：

| 7.x 包 | 8.0 包（模块） | 类型 |
|---|---|---|
| `me.ahoo.cosec.api.authentication`、`me.ahoo.cosec.authentication` | `me.ahoo.cosec.login`（`cosec-login`） | `Authentication`、`Credentials`（现为 `LoginCredentials`）、`CompositeAuthentication` |
| `me.ahoo.cosec.authentication.token` | `me.ahoo.cosec.login.token`（`cosec-login`） | `AbstractSwitchTenantAuthentication` 现为带 `TenantSwitch` 的 `SwitchTenantAuthentication`；`SimpleRefreshTokenAuthentication` 现为 `RefreshTokenAuthentication`（`AbstractRefreshTokenAuthentication` 已移除）（[从 8.0.0-alpha.4 升级](./upgrading-previews.md#from-8-0-0-alpha-4)） |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.login.token`（`cosec-login`） | `TokenConverter`、`TokenCompositeAuthentication`、`RefreshTokenMismatchException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.spi.token`（`cosec-spi`） | `TokenVerifier`、`TokenStore`、`TokenRevoker`、`RevocationReader`、`TokenVerificationException`、`TokenExpiredException`、`TokenRevokedException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.login.token`（`cosec-login`） | `DefaultTokenRevoker` 现为 `StoreTokenRevoker`（[从 8.0.0-alpha.4 升级](./upgrading-previews.md#from-8-0-0-alpha-4)） |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.api.token`（`cosec-api`） | `SimpleCompositeToken` 已移除：`CompositeToken` 是 final 类（`RevocableTokenVerifier` 也已移除：见[从 8.0.0-alpha.4 升级](./upgrading-previews.md#from-8-0-0-alpha-4)） |
| `me.ahoo.cosec.api.CoSec` | 已删除 | 它的值在有含义的地方各有名字：`CoSecPrincipal.ANONYMOUS_ID`（`me.ahoo.cosec.api.principal`）、`TenantId.DEFAULT`（`me.ahoo.cosec.model`）；`cosec.` 属性前缀直接写出（[从 8.0.0-alpha.4 升级](./upgrading-previews.md#from-8-0-0-alpha-4)） |
| `me.ahoo.cosec.context` | `me.ahoo.cosec.webmvc`（`cosec-webmvc`） | `SecurityContextHolder` |
| `me.ahoo.cosec.context.request` | `me.ahoo.cosec.runtime.http`（`cosec-runtime`） | `RemoteIpResolver`、`XForwardedRemoteIpResolver` |
| `me.ahoo.cosec.api.audit` | `me.ahoo.cosec.spi.audit`（`cosec-spi`） | `AuditEventSink` |
| `me.ahoo.cosec.audit` | `me.ahoo.cosec.runtime.audit`（`cosec-runtime`） | `LoggingAuditEventSink`、`AuditEventData` |
| `me.ahoo.cosec.apikey` | `me.ahoo.cosec.spi.apikey`（`cosec-spi`） | `ApiKey`、`ApiKeyReader`、`InMemoryApiKeyStore` |
| `me.ahoo.cosec.limiter` | `me.ahoo.cosec.spi.ratelimit`（`cosec-spi`） | `RateLimit`、`RateLimitResult`、`RateLimiter`（[见下文](#removed-and-replaced-apis)） |
| `me.ahoo.cosec` | `me.ahoo.cosec.model`（`cosec-model`） | `CoSecException` |
| `me.ahoo.cosec.spring.boot.starter` 及其子包 | `me.ahoo.cosec.spring.boot` 及相同的子包（`cosec-spring-boot`） | starter 的类，例如 `jwt.JwtProperties`：`me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties` 现在是 `me.ahoo.cosec.spring.boot.jwt.JwtProperties`，并且与其他 `*Properties` 类一样是 `@InternalCoSecApi`（[从 8.0.0-alpha.4 升级](./upgrading-previews.md#from-8-0-0-alpha-4)）：请用 `@Value` 或 `Environment` 读取 `cosec.jwt.*`，不要复用它的嵌套类型 |

8.0 的 SPI 也在这些地方：`MembershipProvider` 和 `MembershipClaims` 在 `me.ahoo.cosec.spi.membership`，`SnapshotDocumentStore` 和 `InMemorySnapshotDocumentStore` 在 `me.ahoo.cosec.spi.snapshot`，`AttributeProvider` 在 `me.ahoo.cosec.spi.attribute`，`Authenticator` 在 `me.ahoo.cosec.spi.authn`；`SnapshotSource` 在 `me.ahoo.cosec.runtime.snapshot`，文档编解码器和编译器在 `me.ahoo.cosec.document`（`cosec-document`），路由映射在 `me.ahoo.cosec.document.route`（[扩展 CoSec](../architecture/extending.md#extension-points)）。

**`CoSecJsonSerializer` 已删除。** CoSec 的 Jackson mapper 是 CoSec 内部的，核心模块的公共签名中不出现 Jackson 类型。用你自己的 mapper 序列化你自己的类型；用 `PolicyDocumentCodec` 读写策略文档。mapper 的配置没有变化，所以已存放在 Redis 中或已发送到 Kafka 的文档、令牌和记录照常可读。标注了 `@InternalCoSecApi` 的声明之所以公开，只是为了让 CoSec 的模块互相调用：它没有兼容性承诺，任何版本都可能改变，而且不显式 opt in 编译器就拒绝调用它。不要在应用中 opt in。

**CoSec 不再引入 CosId。** 在 7.x 中，`cosec-core`、`cosec-jwt` 和 `cosec-social` 在其 API 中带有 `cosid-core`，CoSec 的平台 `cosec-dependencies` 还导入了 `cosid-bom`。在 8.0 中只有 `cosec-cosid` 依赖 CosId，CoSec 的 BOM 都不导入它、也不设定它的版本，因此应用可以使用任意版本的 CosId，也可以不用，并自己声明它及其版本。CoSec 通过自己的端口 `IdGenerator`（`me.ahoo.cosec.spi.id`，位于 `cosec-spi`）生成 id。没有你自己的 bean 时，id 来自 `RandomIdGenerator`，即随机的版本 4 UUID。要继续使用 CosId 的 id（[Id](../architecture/extending.md#ids)）：

- **使用 CosId 5 时**，添加 `cosec-cosid`：starter 把 `cosec.cosid.generator` 指定的生成器（未设置时为 CosId 的 `default`）的 id 交给 CoSec。CosId 5 本身也需要迁移：见它的 [4.x 到 5.0 指南](https://github.com/Ahoo-Wang/CosId/blob/main/documentation/docs/zh/guide/migration/from-4x.md)。
- **使用 CosId 4 时**，声明一个 bean：

```kotlin
// CosId 4：7.x starter 接收的 StringIdGenerator
@Bean
fun cosecIdGenerator(ids: StringIdGenerator): IdGenerator = IdGenerator { ids.generateAsString() }
```

社交登录的 OAuth `state` 不再来自这个 bean：它始终是来自 `SecureRandom` 的 128 个随机位，因为回调依赖于它不可预测。

### 已删除和替换的 API {#removed-and-replaced-apis}

使用下列任何一项的代码不再能编译，或者不再被调用。

| 7.x | 8.0 |
|---|---|
| `Authorization`、`AuthorizeResult`、`SimpleAuthorization`、装饰器链（`DecisionAuthorization`、追踪、审计、指标、限流、影子） | pipeline（`cosec-runtime`）。用 `DecisionListener` bean 观察决策。 |
| `SecurityPipeline`、`SecurityDecision`、`RequestParser`、`SecurityContextParser`、`DefaultSecurityContextParser` | pipeline；凭证用 `Authenticator` bean。 |
| `CoSecAuthorizationCustomizer` | `Authenticator`、`DecisionListener`、`AttributeProvider`、`MembershipProvider`、`RateLimiter` 的 bean。 |
| `Policy`、`Statement`、`PermissionVerifier`、`VerifyContext`、`RoleVerifyContext`、`VerifyResult`、`ActionMatcher`、`ConditionMatcher`、`AppPermission`、`AppRolePermission`、`RolePermission` 以及 `cosec-api` 中其余的策略与权限模型 | 文档（[策略语言](../policies/language.md)）。覆写了 `verify` 的 `Statement` 没有对应物。请求被放行时所在的应用，原先由 `VerifyContext` 携带，现在是 `SecurityContext.scope.appId`，已按令牌的 `aud` 校验（[应用](../integration/authentication.md#apps)）。 |
| `ActionMatcherFactory`、`ConditionMatcherFactory`、`MatcherFactoryRegistry`、内置匹配器、OGNL 与 SpEL | 只有内置的动作和条件。自定义条件读取的值改由 `AttributeProvider` 提供（[扩展](../architecture/extending.md)）。 |
| `AuthenticationProvider`、`DefaultAuthenticationProvider` | 声明 `Authentication` Bean：starter 会注册它们。 |
| `AbstractRefreshTokenAuthentication` | `RefreshTokenAuthentication`，或你自己的 `Authentication<RefreshTokenCredentials>`。 |
| `ApiKeyStore` | 查找 key 用 `ApiKeyReader`；管理 key 用 `InMemoryApiKeyStore` 或 `RedisApiKeyStore` Bean。 |
| `BlockingAware` | 无：没有条件会执行代码。阻塞的查询放进会挂起的 `AttributeProvider`。 |
| `CoSecPrincipal.anonymous` | `!CoSecPrincipal.authenticated`：`authenticated` 在凭证证明了主体时为 true，由管道声明，从不从 id 读取。匿名主体的 id 仍是 `CoSecPrincipal.ANONYMOUS_ID`。 |
| `RootPrincipalMatcher`、`CoSecPrincipal.isRoot`、`root-enabled` | break-glass 绑定（[root 变为 break-glass](#root-becomes-break-glass)）。 |
| `BlacklistChecker` | 放在 `AttributeProvider` bean（`BlacklistAttributeProvider`）后面的 `BlacklistSource`。 |
| `RequestAttributesAppender`、`GatewayRouteRequestAttributesAppender` | `AttributeProvider`。 |
| `SimpleSecurityContext`、扩展属性 `CoSecPrincipal.tenant` | 无：请求的 `SecurityContext` 由管道创建。测试中请 mock 或自行实现该接口；租户是 `SecurityContext.tenantId`。 |
| `cosec-core` 的主体与租户包装类：`SimplePrincipal`、`SimpleTenantPrincipal`、`TenantPrincipal`、`TokenPrincipal`、`TokenTenantPrincipal`、`SimpleTokenPrincipal`、`SimpleTokenTenantPrincipal`；`SimpleTenant`、`Tenant`、`TenantCapable` | 令牌为之签发的是 `TokenSubject`（`me.ahoo.cosec.login.token`）：主体的 `id`、它所针对的 `app` 和 `tenant`、声明的 `roles` 和 `policies`，以及 `attributes`。`Authentication` 返回它，`TokenConverter` 把它变成令牌（[认证](../integration/authentication.md)）。请求读取的是 `CoSecPrincipal`（`SecurityContext.principal`），其租户在 `SecurityContext.tenantId` 中。租户 id 是 `TenantId.DEFAULT` 和 `TenantId.PLATFORM`（`me.ahoo.cosec.model`）。 |
| `RoleCapable`、`PolicyCapable`（主体的 `roles` 和 `policies`） | 主体的成员关系 `Memberships`（`me.ahoo.cosec.model`），来自令牌的声明或 `MembershipProvider`。对请求生效的角色是 `SecurityContext.scope.roles`：请求所在空间中的角色，用裸名称表示（`editor`，而不是 `editor@team-a`）。主体在其他空间中的角色不在上下文中，附加给它的策略也不在：令牌或成员关系提供方的附加策略 id 在 8.0 中不暴露。要根据附加策略原本的含义分支，就检查该策略放行的某个动作（[代码中的权限检查](../integration/checks.md)），或者把权限给角色。 |
| `cosec-api` 的 `String` 别名 `RoleId`、`PolicyId` 和 `PermissionId` | `RoleId` 和 `PolicyId` 是 `me.ahoo.cosec.model` 的值类，名字相同、包不同：`RoleId("admin")`，用 `.value` 取得字符串。`PermissionId`：无。权限是一个动作，由策略或目录条目授予（[角色权限](../policies/role-permissions.md)）。 |
| `KafkaAuditEventSink` | `KafkaAuditListener`，一个 `DecisionListener`，classpath 上有 `cosec-kafka` 时由 starter 注册（[可观测性](./observability.md)）。记录的 key 不变，value 是审计事件（[可观测性的变化](#observability-changes)）。 |
| `PolicyRepository`、`AppRolePermissionRepository`、Redis 仓库与缓存、`SnapshotPublisher`、`RedisSnapshotPublisher`、`PolicyHistory`、`RedisPolicyRevisionStore`、对 `AppPermissionCache`/`RolePermissionCache` 的写入 | 写入用控制面的 HTTP API，由你自己的客户端调用（[外部事实源同步角色](../scenarios/external-source.md)）；第一次写入用 `cosec-migrate publish`。 |
| `LocalPolicyInitializer` 以及 `init-repository`/`force-refresh` | `cosec-migrate convert` 与 `publish`，或控制面的声明式 `apply`。 |
| `CoSecExplainEndpoint`（`cosecExplain` actuator）、`AuthorizationExplainer`、`ExplainWarningCode` | 控制面的 `explain`（需要权限，可指定任一修订）；见[代码中的检查](../integration/checks.md)。 |
| 影子模式（`ShadowAuthorization`、`ShadowPolicyProvider`、`cosec.shadow.*` 文件） | 控制面变更的影子窗口；见[测试策略](../policies/testing.md)。 |
| `AccessAnalyzer` | 控制面的 `analyze`。 |
| `PolicyTestKit`（`cosec-core`） | `cosec-testkit` 模块中的 `PolicyTestKit`（[策略测试套件](#policy-test-suites)）。 |
| `cosec-api` 中 7.x 的请求模型：`Request`、`Attributes`、`AppIdCapable`、`DeviceIdCapable`、`RequestIdCapable`（及其别名 `AppId`、`DeviceId`、`RequestId`）、`COSEC_EXTEND_KEY_PREFIX`、`SpaceIdCapable.SPACE_ID_KEY`；`cosec-core` 的 `RequestSecurityContexts` 和 `InvalidRequestPathException` | `SecurityContext.deviceId` 和 `SecurityContext.requestId` 回答 `DeviceIdCapable` 和 `RequestIdCapable` 原来给出的东西（客户端的 `CoSec-Device-Id`，没有发送时为空；CoSec 在审计记录和问题响应中使用的 id）。适配器读取 `HttpFacts`，安全上下文携带校验后的 `scope`；应用没有 `Request` 可用。`SpaceIdCapable`、`SpacedRoleId` 和别名 `SpaceId` 也已删除：空间 id 是 `me.ahoo.cosec.model` 的值类 `SpaceId`，请求的空间是 `SecurityContext.scope.spaceId`，空间中的角色写作 `role@space`。 |
| `InternalIds`（`cosec-api`） | 无。 |
| `me.ahoo.cosec.limiter.RateLimiter` 及其 `LocalRateLimiter`、`RedisRateLimiter`（`cosec-cocache`） | 挂起的 SPI `me.ahoo.cosec.spi.ratelimit.RateLimiter`；starter 注册它的 `LocalRateLimiter` 或 `ReactiveRedisRateLimiter`（[限流](../policies/rate-limiting.md)）。`RateLimit` 和 `RateLimitResult` 保留。 |
| `Jwts.toPrincipal(String)`、`Jwts.decode`、`Jwts.verifyTimeClaims` | 无：`Jwts` 是 `cosec-jwt` 的内部类。用 `TokenVerifier.verify` 把令牌变成主体，它检查签名、过期时间和声明；然后对它的返回值调用 `TokenRevocation.check`（`me.ahoo.cosec.runtime.token`，starter 的 bean）：只做 `verify` 会接受已吊销的令牌。无法读取的吊销存储会抛出 `RevocationUnavailableException`，由你的代码决定怎么办（[在管道之外验证令牌](../integration/authentication.md#verifying-a-token-outside-the-pipeline)）。 |
| `PrincipalConverter`、`TokenVerifier.toPrincipal` | `TokenVerifier.verify`，然后对其结果调用 `TokenRevocation.check`（starter 的 bean），如上（[在管道之外验证令牌](../integration/authentication.md#verifying-a-token-outside-the-pipeline)）。 |
| `Authentication.authenticate`、`TokenCompositeAuthentication.authenticateAsToken`、社交登录提供方：`Mono<...>` | 直接返回值的挂起函数；登录失败时抛出异常（[登录](../integration/authentication.md#logging-in)）。 |
| 作为 object 的 `DefaultAuthenticationProvider` 和 `SocialProviderManager` | 改为类，每个应用上下文一个实例：请注入 `AuthenticationProvider` 或 `SocialProviderManager` Bean。 |
| `AuthorizationChecker.check(...)`：`Mono<Boolean>` | `AuthorizationChecker.check(security, action, resource)`，挂起函数；`cosec-webflux` 提供 `Mono<Boolean>` 形式，`cosec-webmvc` 提供阻塞形式（[代码中的检查](../integration/checks.md)）。 |
| 过滤器 `ReactiveAuthorizationFilter`、`AuthorizationFilter`（servlet）、`AuthorizationGatewayFilter`，以及它们接受 `Authorization` 的构造器 | 由 starter 创建的 `PipelineWebFilter`、`PipelineServletFilter`、`PipelineGatewayFilter`。 |
| `ReactiveInjectSecurityContextWebFilter`、`InjectSecurityContextFilter`（注入模式） | CoSec 网关之后的 `cosec.pipeline.mode=gateway`（[注入模式变为身份传递](#inject-mode-becomes-identity-propagation)）。8.0 的过滤器在每个放行的请求上放入安全上下文。 |
| 授权之后留在调用方安全上下文中的路径变量 | 无。从你的 Web 框架读取路径变量。 |
| `cosecPolicyGenerator`、`cosecAppPermissionGenerator` actuator 端点 | `cosecServiceGenerator/routes`（路由映射）和 `cosecServiceGenerator/app`（应用文档）。 |
| `JwtTokenConverter`、`JustAuthProvider` 和 starter 的 bean 中的 `StringIdGenerator`、`UuidGenerator`（CosId）；在 8.0 预览版中还有 `PipelineComponents.idGenerator` 和 `IdentityGate` | `IdGenerator`（`cosec-spi`，[Id](../architecture/extending.md#ids)），默认是 `RandomIdGenerator`。`JustAuthProvider` 不接收 id 生成器：它从 `SecureRandom` 抽取 OAuth `state`。 |
| `SocialUserConverter`（`cosec-social`） | 无：它是 `cosec-social` 的内部实现。用 `SocialUserPrincipalConverter` bean 把提供方的用户变成你的主体（[社交登录](../integration/authentication.md#social-login)）。 |

仍然可用：`SecurityContext`（新增 `scope`，即校验后的应用、空间和角色；应读取它，而不是客户端声明的 `CoSec-*` 请求头）、`ServerWebExchange`、
Reactor `ContextView` 和 `HttpServletRequest` 上的 `getSecurityContext()`、`AuditEventSink`、`TokenStore`，以及 `cosec-api` 的审计类型，其中一些换了包（[模块、制品与包](#modules-artifacts-and-packages)）。

8.0 预览版中，应用可以用自己的 bean 替换执行控制面授权与审批的部分：`ControlAuthorizer`、`ChangeAuthority`、`Changes`、`ControlPlane` 和 `ControlHttp`。现在不行了：这些类型中任何一个出现第二个 bean，启动都会失败（`ControlHttp` 是在提供控制面路由时）。`ControlAuthorizer`、`DocumentConfirmation`、`PipelineControlAuthorizer`、`ChangeAuthority`、`Changes`、`DefaultControlPlane`、`ControlHttp`、`ControlHttpRequest` 和 `ControlHttpResponse` 是 `@InternalCoSecApi`，`ControlPlane` 和它每个方法都要的 `Operator` 也是：控制面不是应用可以调用的库，应用通过它的 HTTP API 写入（[外部事实源同步角色](../scenarios/external-source.md)）。`ControlStore`、`ChangeOptions`、变更监听器、`Revisions`、`ControlSnapshot` 和维护相关的 bean 仍可替换。

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

下面每个 key 在 8.0 中都会让启动失败，错误信息会链接到这张表。8.0 不读取的其他 `cosec.*` key 也一样；没有任何配置项能关闭这项检查（[未知配置项](./configuration.md#unknown-properties)）。以 `.` 结尾的 key 代表它之下的每个 key。

| 7.x | 8.0 |
|---|---|
| `cosec.authentication.enabled` | `cosec.authentication.login.enabled` |
| `cosec.authentication.social.` | `cosec.authentication.login.social.` |
| `cosec.authorization.allow-query-token` | `cosec.pipeline.allow-query-token` |
| `cosec.api-key.header` | `cosec.pipeline.api-key-header` |
| `cosec.authorization.enabled` | 已删除，它的替代是一份文档，不是一个开关：服务用平台文档决策，在文档装入之前每个请求都是 `503`，就绪探针拒绝流量。`cosec.enabled=false` 关闭每一个 CoSec bean，登录和签发令牌也在内，所以让用户登录而不执行任何授权的服务不用它（[只签发令牌的服务](../integration/authentication.md#a-service-that-only-issues-tokens)）。校验另一个服务所签发的令牌并在自己代码中决策的服务，在 `enforce` 模式下运行，用一份放行其已认证调用方的文档，并且必须读取签发方的吊销（[校验另一个服务所签发令牌的服务](../scenarios/resource-server.md)）。 |
| `cosec.authorization.local-policy.` | 已删除：用 `cosec-migrate` 转换并发布这些文件（[迁移数据](#migrating-the-data)）。 |
| `cosec.authorization.root-enabled` | 已删除：root 变为 break-glass（[root 变为 break-glass](#root-becomes-break-glass)）。 |
| `cosec.authorization.cache.enabled` | 已删除：快照缓存是 `cosec.authorization.snapshot.*`。 |
| `cosec.authorization.cache.policy.` | 已删除：快照缓存是 `cosec.authorization.snapshot.*`。 |
| `cosec.authorization.cache.role.` | 已删除：快照缓存是 `cosec.authorization.snapshot.*`。 |
| `cosec.authorization.cache.history.` | 已删除。 |
| `cosec.authorization.cache.migration.` | 已删除。 |
| `cosec.authorization.cache.token.concurrency-level` | 已删除：自 CoCache 5 起被忽略。 |
| `cosec.authorization.cache.token.expire-after-write` | `cosec.authentication.revocation.cache.local-max-age`，一个时长（`30s`）。 |
| `cosec.authorization.cache.token.expire-unit` | 已删除：时长自带单位。`expire-after-access` 也是时长；不带单位的数字仍表示秒。 |
| `cosec.authorization.gateway.enabled` | 已删除：网关过滤器随 `cosec-gateway` 提供。 |
| `cosec.limiter.` | 已删除：限流规则在平台文档中（[限流规则变为义务](#rate-limit-rules-become-obligations)）；要使用不同的限流 key 前缀，需要自己的 `RateLimiter` bean。 |
| `cosec.shadow.` | 已删除：影子窗口是控制面的操作；每个实例的部分是 `cosec.authorization.shadow.*`。 |
| `cosec.explain.enabled` | 已删除：使用控制面的 `explain`。 |
| `cosec.inject.enabled` | 已删除（[注入模式变为身份传递](#inject-mode-becomes-identity-propagation)）。 |
| `cosec.api-key.store-blocking` | 已删除。 |
| `cosec.jwt.token-revocation.enabled` | 已删除：吊销始终开启（[令牌吊销始终开启](#token-revocation-is-always-on)）。 |
| `cosec.authorization.cache.key-prefix` | `cosec.key-prefix`，每个 key 的前缀：先复制令牌吊销（[哪些 key 族会移动](./upgrading-previews.md#from-8-0-0-alpha-2)）。 |
| `cosec.api-key.key-prefix` | `cosec.key-prefix`：先复制 API key（[哪些 key 族会移动](./upgrading-previews.md#from-8-0-0-alpha-2)）。 |
| `cosec.authorization.snapshot.key-prefix`（8.0 预览版） | `cosec.key-prefix`：把它设为这个前缀（[哪些 key 族会移动](./upgrading-previews.md#from-8-0-0-alpha-2)）。 |
| `cosec.unknown-properties`（8.0 预览版） | 已删除：未知 key 总是让启动失败。 |

`cosec.authorization.cache.token.*` 现在是 `cosec.authentication.revocation.cache.*`。

新的属性组：`cosec.pipeline.*`（超时、查询和 cookie 令牌、API key 请求头、trace 级别、模式）、`cosec.gateway.identity.*`、
`cosec.authorization.scope.app-id`、`cosec.authorization.{membership,attributes,rate-limit}.timeout`、`cosec.authorization.routes.location`、
`cosec.authorization.snapshot.*`、`cosec.authorization.shadow.*`、`cosec.authentication.revocation.*`、
`cosec.audit.kafka.delivery`、`cosec.observability.listener.*` 和 `cosec.control.*`。

升级时有两个默认值需要做出决定：

- `cosec.authorization.routes.location` 默认不设置，未设置的宿主不加载路由映射，此时每个请求都是 `http` 命名空间中针对其路径的动作。有映射的宿主要指明它；放在 `classpath:cosec-routes.json` 而配置项未设置时启动失败。网关不加载路由映射。
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
  `TOKEN_REVOKED`、`BOUNDARY`。`authorization.source` 用绑定模型的术语说明是什么做出了决定：`via`、`document`、`policy` 和 `statement`
  取代来源的 `type`、`policy` 和 `role`，`principal.policies` 和 `request.device.userAgent` 已删除（[审计事件](./observability.md#audit-events)）。
  `elapsedNanos` 现在覆盖整个 pipeline（认证、属性、限流），而不只是授权。`AuditEventSink` 仍然收到扁平记录；读取上述字段的实现需要随之修改。
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

**下一步：**部署承载流量之前，逐项完成[加固清单](../guide/security-model.md#hardening-checklist)。
