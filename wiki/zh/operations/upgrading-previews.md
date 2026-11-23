---
title: "8.0 预览版之间的升级"
description: "每个 8.0 预览版（alpha 与 beta）到下一个之间的变化，新的在前，面向已在运行预览版的部署。"
---

# 8.0 预览版之间的升级 {#between-8-0-previews}

只适用于已在运行 8.0.0 预览版的部署：每个预览版到下一个之间的变化，新的在前。7.x 部署不需要这一部分，[升级](./upgrading.md)就是完整的升级。8.0 正式发布时这些说明将删除。

## 从哪里开始 {#start-here}

下面每一节写的是离开那个预览版时会变什么。读你所运行版本的那一节，以及它上面的每一节：

| 你运行的版本 | 阅读 |
|---|---|
| `8.0.0-beta.7` | [从 beta.7 升级](#from-8-0-0-beta-7) |
| `8.0.0-beta.6` | [从 beta.6 升级](#from-8-0-0-beta-6)，然后是上面那一节 |
| `8.0.0-beta.5` | [从 beta.5 升级](#from-8-0-0-beta-5)，然后是上面两节 |
| `8.0.0-beta.4` | [从 beta.4 升级](#from-8-0-0-beta-4)，然后是上面三节 |
| `8.0.0-beta.3` | [从 beta.3 升级](#from-8-0-0-beta-3)，然后是上面四节 |
| `8.0.0-beta.2` | [从 beta.2 升级](#from-8-0-0-beta-2)，然后是上面五节 |
| `8.0.0-beta.1` | [从 beta.1 升级](#from-8-0-0-beta-1)，然后是上面六节 |
| `8.0.0-alpha.4` | [从 alpha.4 升级](#from-8-0-0-alpha-4)，然后是上面七节 |
| `8.0.0-alpha.3`、`alpha.2`、`alpha.1` | [从 alpha.3](#from-8-0-0-alpha-3)、[alpha.2](#from-8-0-0-alpha-2) 或 [alpha.1](#from-8-0-0-alpha-1) 升级，然后是它上面的每一节 |

库的哪个预览版可以运行在网关镜像的哪个预览版之后：见[升级运行中的部署](./upgrading.md#upgrading-a-running-deployment)。

## 从 8.0.0-beta.7 升级 {#from-8-0-0-beta-7}

- **文档的修订记录在 `min-age` 之内也有上限**（`CTL-84`，DR1-F-6）。受影响的：频繁发布大文档的文档。在最近 `max-count` 个修订之外，一个文档至多保留 `max-count` 乘以文档上限的记录（400 MB）与 26,214 条索引项；超出任一项，从最老的开始删除，不论是否早于 `min-age`，由 `cosec.control.revisions.reclaimed-young` 计数并记 `WARN`。新增四个度量：`cosec.control.revisions.records`、`.bytes`、`.reclaimed-young` 与 `cosec.control.changes.open`；量规请跨实例求和（[可观测性](./observability.md)）。每条修订索引项现在记着它的记录大小；较早的控制面读取时会跳过它，它写入的不带大小的索引项，由下一轮回收读取记录得出大小。

- **一份文档有了 `cosec.control.revisions.max-count` 个未结束变更后不再接受新变更**（默认 50，`CTL-83`，DR1-E-2；在写入之前计数，同一时刻提出的变更可能都会通过）。受影响的：在同一份文档上
  留着很多未结束变更的客户端。超过上限的提议、`:apply` 或回滚，先放弃其作者自己最老的、检查失败的变更，否则以 `409` 拒绝，
  冲突种类为新增的 `OPEN_LIMIT`，`heldBy` 给出最老的未结束变更；类型化客户端看到 `ConflictKind.OPEN_LIMIT`。请放弃不再需要的变更。
  升级时已经超出上限的文档不受追溯：它的变更会在 `cosec.control.changes.expire-after`（7 天）之内过期，回到上限以内，在那之前它不接受新的变更。
- **一份文档至多保留 `max-count` 个已结束的变更，回收任务删除旧的**（`CTL-83`，DR1-E-2）。受影响的：在变更结束很久之后还读取它的客户端。
  结束一个变更时，删除该文档超出 `cosec.control.revisions.max-count` 的最早的已结束变更，不论年龄；每小时的回收任务删除早于
  `cosec.control.revisions.min-age`、且其变更发布的修订已不再保留的那些。读取已删除的变更 `GET /changes/{id}` 返回 `404`。
  要知道谁发布了某个修订，读修订本身：`GET /documents/{key}/revisions/{revision}` 在 `provenance` 中给出它的变更、作者与审批人，
  `explain` 也仍然基于它决定。自己实现的 `ControlStore` 要实现 `endedChanges` 与 `removeChange`。升级之前结束的变更没有登记，
  所以不会被删除：想删掉它们，请自己删除对应的 `<s>:control:change:<id>` 键。
- **不携带文档的控制路由最多读取 64 KB**（DR1-F-3）。受影响的：向 `simulate`、`explain`、`analyze`、变更的各步、回滚或
  break-glass 发送超过 64 KB 请求体的客户端。这样的请求体现在在解析之前即返回 `400`；此前它会在任何授权之前被读取并解析，
  最多 10 MB。提议、`:apply` 及其校验仍读取至多 10 MB。超过 64 KB 的模拟请求带的头与属性比真实请求能带的还多：请删减。
- **破坏性变更：有自己处理器的网关宿主在列出它们之前不会启动**（`STA-13`）。受影响的是 enforce 模式下、应用声明了自己的
  `@Controller`、`@RestController` 或 `RouterFunction` bean 的 Spring Cloud Gateway 宿主。
  - 这些处理器映射在所有网关路由之前，所以作为网关路由过滤器的 CoSec 从未决策过它们。原来启动时只告警，然后继续。
  - 现在启动失败，并按 bean 名逐个指明。迁移方式：把它们移到网关之后的服务中，由路由来决策；或者把由其他手段保护的那些列在
    `cosec.gateway.unprotected-handlers` 中，启动时会用一行 INFO 指明它们。
  - 带 `*` 的名字被拒绝。Spring 的、CoSec 的和 actuator 的处理器不算应用的，不需要列出（[网关自己的处理器](../integration/web.md#the-gateways-own-handlers)）。库的控制器算：网关宿主上 springdoc 的 `openApiResource` 和你自己的处理器一样被拒绝（用 `springdoc.api-docs.enabled=false` 关掉它，或者列出它）。
- **在保留修订上的 `explain` 像头一样被缓存**（DR1-F-4）。无需改动。重复它不再编译，此前每次调用都重新编译它的文档。
- **租户切换开始的会话随登录一起结束**（`IDN-36`）。受影响的是提供租户或应用切换（`SwitchTenantAuthentication`）的应用，
  以及你自己的 `TokenFamilyStore`。
  - 原来切换出来的会话是独立的：它保留登录时间，但登录会话被登出、或因令牌对重用被吊销后，它仍能一直刷新到有效期结束。
    现在它把登录会话记为自己的根，根结束后它的下一次刷新或切换得到 `401 token-revoked`；它的访问令牌在过期之前仍然有效。
  - **你自己的 `TokenFamilyStore` 必须保存 `TokenFamily.root`**（新的可空成员；不带它的构造器保留）。根没有被保存时，
    切换读回会话后被拒绝，信息里点名这个成员。
  - **滚动升级。** 切换出来的会话的记录现在写成 format 2。8.0.0-beta.7 的实例拒绝它，于是那个会话在那里结束：在已升级的实例上
    切换、又到旧实例上刷新的用户需要重新切换一次。登录开始的会话不变（format 1）。
  - **回退到 8.0.0-beta.7** 会因同样的原因结束升级之后切换出来的所有会话。
  - 升级之前切换出来的会话没有根，保持原来的行为直到结束。
- **会话记录保留得不比它的刷新令牌更久，每个主体 64 个**（`IDN-35`，DR1-G-2）。受影响的是所有签发 CoSec 令牌的部署，
  以及你自己的 `TokenFamilyStore`。
  - 会话记录（`<s>:token:family:<sid>`）原来在 Redis 中保留整个会话有效期（30 天），即使一周后就没人再刷新它。
    现在每次写入只把它保留会话剩余时间与 `cosec.jwt.token-validity.refresh` 中较短的那个。
  - 一个主体在一个租户里保留开始时间最近的 64 个会话，计在新 key `<s>:token:sessions:...` 中。超过它们的登录或切换
    会删除最早的那个，它下一次刷新得到 `401 token-invalid`：那台设备重新登录。
  - 之前写下的记录保持原来的寿命，也不被计入，照旧离开 Redis。
  - **你自己的 `TokenFamilyStore`** 可以像以前那样把令牌族保留到 `expiresAt`，契约现在称之为上界。它应当像 CoSec 的存储那样，
    每个主体在每个租户保留 `TokenFamilyStore.SESSIONS_PER_PRINCIPAL` 个会话（[扩展](../architecture/extending.md)）。
- **`analyze` 只向能读租户文档的调用者列出它的绑定**（`CTL-51`，DR1-F-8）。受影响的：对本租户文档有 `cosec:analyze`
  却没有 `cosec:read` 的主体（没有内置角色是这样）。它此前会得到该文档中所有匹配的绑定，现在一个也得不到，该文档计入 `hidden`。
  请把 `cosec:read` 与 `cosec:analyze` 一起授予，就像 `cosec:tenant-viewer` 那样。
- **令牌吊销记录比它指向的令牌活得长**（`IDN-37`）。受影响的是 `cosec.jwt.token-validity.access` 长于
  `cosec.jwt.token-validity.refresh` 的部署。原来吊销记录只保留刷新令牌有效期，所以有效期更长的访问令牌在记录过期后会重新被接受。
  现在保留两者中较长的那个，再加 `cosec.authentication.revocation.skew`（签发方的时钟可能超前这么多），启动时会记录这个值。
  之前写下的记录保持原来的寿命。控制面检查吊销记录比审批（`cosec.control.approval.expire-after`）活得长时使用同一个值，
  其信息也变了：现在以 `A token revocation is kept for` 开头（[故障排查](./troubleshooting.md)）。
- **`HttpControlService` 转述用户的属性**（`CTL-77`，DR1-F-1）。受影响的：以正在服务的用户身份（`ControlCaller.of`）
  经 `HttpControlService` 调用控制面宿主、且控制策略读取 `subject.attributes` 的应用。此前调用不带任何属性，这样的 DENY
  经 HTTP 匹配不上，调用只按 ALLOW 判定，而进程内的门面会拒绝它。现在全部属性都随调用发出，每一项都是引擎读到的文本，
  这样的 DENY 两条路都拒绝。属性因此会到达宿主，由服务端口的网络策略保护。属性超出请求头上限（合计 8 KB，单个字符串
  1,024 个字符）的用户不能再经它调用：调用在发出之前失败，写明属性与大小。宿主能先升级就先升级；beta.7 的宿主同样会读这些属性
  （[部署](./deployment.md#the-control-plane-host)）。
- **太大而无法分析的变更只有一项 `TOO_LARGE`**（`CTL-49`，DR1-E-1）。受影响的：被授出的 ALLOW 合计超过 80,000 个片段
  （每个动作乘每个资源，再乘到达它的每条绑定）的租户变更，以及读变更越权项的程序。这样的变更在拆分之前就被计数：任何部分都不分析，
  以一项 `change of tenant:<t>` 等待审批。此前它被整个拆开、每个片段一项，一条一千个动作乘一千个资源的语句就能耗尽控制面的堆。
  分析用完四百万次比较之后走到的授出也合为一项 `<n> fragments of tenant:<t>`，此前每个一项。beta.7 中对这种变更的批准
  覆盖不了新的那一项：须重新批准。
- **以自己的主体调用控制面的服务记为 `service`**（`ControlCaller.service`，`IDN-34`）。受影响的：读取控制请求记录的东西，以及升级的顺序。
  - **记录。** 这样的服务所声明的身份把凭证命名为 `service`，beta.7 命名为 `api-key`。它不是 API key，记录现在照实写：它对控制面宿主的请求在决策事件与审计里的 `subject.method`、变更日志里的 `origin.via.method` 都是 `service`（经进程内 `ControlService` 的调用仍是 `in-process`）。此前存储的变更保留 `api-key`。按 `api-key` 找服务调用方的看板、告警或查询，也要找 `service`。
  - **它能做什么没有变。** `service` 和 `api-key` 一样是工作负载的凭证：从不审批，也从不是信任根，没有会话，也没有 break-glass（新增 `AuthenticatedPrincipal.workload`）。
  - **先升级控制面宿主，再升级调用它们的服务。** beta.7 宿主不认识 `service`，把它当作人的凭证。在它的服务端口上这不改变什么：那里不提供审批，也没有人是信任根。但如果某个服务的 `cosec.control.service-url` 指向 beta.7 宿主的运维端口（相信 `CoSec-Identity` 的端口），它就能审批变更；如果它是持有 `cosec:approve` 的平台管理员，还会是信任根，而 beta.7 的 `api-key` 两样都做不到。已升级的调用方调用 beta.7 宿主的服务端口是安全的；无论如何都不要让服务指向运维端口。beta.7 调用方调用已升级的宿主时仍发送 `api-key`，已升级的宿主仍把它当作工作负载的凭证。
- **你自己的 `Authenticator` 把凭证命名为 `service`** 时，现在命名的是工作负载的凭证：它的主体不再能审批变更或成为信任根，与 `api-key` 相同。受影响的：只有用了这个名字的认证器。如果它的主体是人，请改名；否则无需改动（[扩展 CoSec](../architecture/extending.md)）。
- **服务器不报告对端地址的请求，其客户端地址读作取不到**（`PIP-17`，DR1-C-8）。受影响的：服务器不报告对端（例如监听 Unix 域套接字的 Reactor Netty）、且有语句或黑名单读 `environment.ip` 的主机。此前地址为空，读作不存在：按它的 DENY 不匹配，IP 黑名单也不被查询。现在它是 `unknown`，与可信代理写的 `unknown` 条目一样：这样的 DENY 与黑名单返回 `503`，三个适配器一致。在这样的主机前放一个写 `X-Forwarded-For` 的代理，并把该索引设为 `1` 或更大（[Web 集成](../integration/web.md)）；服务器报告对端的主机不受影响。
- **长于 256 个字符的限流键值改用其哈希命名桶**（`OBL-6`，DR1-C-1）。受影响的：`key` 读取这么长的值的规则，几乎总是客户端自选的请求头。这样的桶现在是 `h:<sha-256>`，此前是 `k:<value>`（[限流](../policies/rate-limiting.md#keys)），所以它的计数会重新开始一次：在 Redis 上旧键不再被读取，两个窗口后过期；本地限流器的计数本来就只属于该实例。滚动升级期间两个版本同时服务时，这样的值会计入两个桶（每个版本一个），所以在最后一个实例升级之前，它最多得到两倍的限额。256 个字符及以内的值保留原来的桶与计数。无需改动。
- **生成的路由映射不再取决于最后是谁访问了 API 文档**（`RTE-4`，DR1-C-2）。受影响的：在 OpenAPI 文档没有写 `servers` 的宿主上，用 `cosecServiceGenerator/routes` 生成的路由映射。这时 springdoc 会在它缓存的文档里放一个自己的服务器，取自最近一次请求 API 文档的请求（含查询串），路由就生成在它的路径下。现在这个服务器被排除，路由位于应用的根路径。若曾在 `/v3/api-docs` 之类的路径下生成过路由映射，请重新生成；服务部署在宿主不会去掉的路径下时，在文档里写上 `servers`（[角色权限](../policies/role-permissions.md#generating-a-catalog-from-openapi)）。
- **被取消的 `HttpControlService` 调用现在会结束它在宿主上的请求**（`CTL-82`，#1893）。受影响的：无需改动。经 `HttpControlService` 的调用所在的协程被取消时，调用会关闭它的 HTTP 交换，控制面宿主（WebFlux）随之取消该请求。此前，请求会留在宿主上，直到调用自己的超时（默认 30 秒）结束它。控制调用没有自己的期限，由你决定：包住调用的 `withTimeout`，或 `HttpControlService` 的 `timeout`（[在代码中修改策略](../integration/checks.md#changing-policies-from-your-code)）。
- **成员关系由每个实例自己持有，从不放进 Redis**（`MEM-5`，DR1-D-2）。受影响的：有 `MembershipLoader` 的应用。此前每个实例都读写 `<s>:membership:*` 下的共享缓存，并把那里的任何东西读作加载器的回答，所以任何数据面 Redis 凭据都能借此授予角色。现在每个实例把它自己的加载器的回答放在自己的内存中，不从 Redis 读取任何东西：
  - **行为。** 原来第二个实例的未命中是一次 Redis 读取，现在是一次加载器调用：N 个实例在一个寿命内对同一主体最多加载 N 次，请按此评估加载器的容量。`invalidate(tenant, principal)` 仍会经 Redis 的失效通道（`<s>:inv:membership`）在所有实例上丢弃该主体，这不需要任何键权限。
  - **配置。** 寿命是 `cosec.authorization.membership.ttl`（一分钟，与之前相同）。任何 `cocache.caches.MembershipCache.*` 设置，尤其是它的 `ttl`，都会使启动失败并指出该属性：请迁移过去。成员关系占用堆的六十四分之一，见启动日志。
  - **Redis。** `<s>:membership:*` 键不再被读写：所有实例都运行此版本后可以删除（`redis-cli --scan --pattern '<s>:membership:*' | xargs redis-cli del`），并从数据面的 ACL 中去掉 `~<s>:membership:*`（[谁可以写哪些 key](./storage.md#who-may-write-which-keys)）。新旧实例并存期间，旧实例的 `invalidate` 仍能到达新实例（同一通道、同样的帧）。
- **条目预算计入角色与目录简写派生的条目**（`CTL-40`，DR1-B-1）。受影响的：角色列了很多权限的文档，或目录里有很多带 `action` 的权限的应用。`maxRoleBindings` 现在把角色的绑定（它所列的每个权限一条，`"*"` 为目录的全部权限）与手写的一起计入，默认值从 10,000 调到 20,000，让 100 个角色各 100 个权限留有余量；`maxPolicies` 与 `maxStatements` 为每个带 `action` 的目录权限计入一条只含一条语句的策略。beta.7 能发布、但会把这样的计数抬过预算的变更，现在停留在草稿并带 `V2` 错误；已存储的超预算文档继续被服务，只要不增长就仍可修改。如何判断：对它的任意变更调用 `POST /changes:check`（[不提出而检查](../policies/testing.md#checking-without-proposing)），`V2` 警告会写出每个超过预算的计数与上限（[条目预算](../policies/language.md#entry-budgets)）；或数出它派生的绑定，即各角色权限数之和：`jq '[.roles[]?.permissions | if type == "array" then length else 0 end] | add + ([.bindings[]? | select(.subject.role)] | length)'`（`"*"` 角色再加上它的应用目录的权限数）。文档确实需要时，在平台的 `limits` 中调高预算。分散在不同权限上的派生绑定，每千条让租户管理员变更的检查多约 0.5 秒，每条在持有该文档的每个实例上约占 240 字节。
- **在拒绝方向上末尾的 `/` 不算数**（`PIP-35`，DR1-C-9）。受影响的：所有部署，方向是放行得更少。写在 `/orders/{id}` 上的 DENY 语句或限流规则现在也作用于 `/orders/123/`，写在 `/orders/` 上的也作用于 `/orders`；ALLOW 与平台边界仍按路径原样匹配。在有路由映射的宿主上，路径以 `/` 结尾、映射不上、去掉后能映射的请求（路由 `/orders/{id}` 时的 `DELETE /orders/123/`）现在答 `400` `invalid-request`，此前在 `http` 命名空间里决策。向映射宿主发送这种路径的客户端应去掉末尾的 `/`（[Web 集成](../integration/web.md)）。
- **社交登录的 state 有了上界**（DR1-D-9）。受影响的：提供社交登录的部署。一个部署最多持有 Redis 报告的内存的六十四分之一的进行中登录（每个 512 字节；Redis 不报告内存时为 100,000），超过时开始登录以 `SocialAuthenticationException`（“稍后再试”）拒绝，而不是再写两把键。提供社交登录的进程需要 Redis 的 `INFO` 权限，`SNP-3` 本来就要求；没有时使用固定上界，并记一条 WARN。`InMemorySocialStateStore` 最多持有 10,000 个。除了在边缘对开始登录的路由限流，无需改动（[存储](./storage.md#redis-keys)）。

## 从 8.0.0-beta.6 升级 {#from-8-0-0-beta-6}

无需任何改动：beta.6 部署用到的属性、格式、状态码、存储的记录和公共签名都没有变化，网关镜像和控制面镜像只有版本号不同。

- **测试工具包新增 `IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY`**（#1876）。受影响的：没有。它是 `IdentityHeader.ONLY_GATEWAY_CAN_REACH` 单独的 key，供用接受属性 map 的启动器启动应用的测试使用：`mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")`（[测试你的应用](../policies/testing.md#testing-your-application)）。

## 从 8.0.0-beta.5 升级 {#from-8-0-0-beta-5}

- **新增：宿主渲染拒绝的响应体**（`ProblemRenderer`，`PIP-34`，#1840）。受影响的：除非你声明一个，否则没有。有自己的 problem 目录的宿主框架声明一个 `ProblemRenderer` bean，三个适配器和 starter 提供的登录路由后面的每个拒绝都使用它的响应体；状态码和头仍是 CoSec 的，失败的渲染器会被 CoSec 的响应体替代（`cosec.problem.render.failed`）。两个渲染器导致启动失败。请在响应体中保留 CoSec 的 `type` 和 `code`，否则 CoSec 的 TypeScript 客户端读不懂这些拒绝（[Problem 渲染器](../architecture/extending.md#problem-renderer)）。
- **CoSec 基于 Spring Boot 4.2 和 Spring Cloud 2026.0 构建。** 受影响：使用 Spring Boot 4.1 的应用，或使用 `cosec-gateway`
  且在 Spring Cloud 2025.1 上的应用。CoSec 的模块把构建所用的版本写作最低版本
  （[CoSec 对构建的要求](../guide/introduction.md#what-cosec-needs-from-your-build)）：按 Spring Boot 4.2 的发布说明把应用升级到
  Spring Boot 4.2（使用 `cosec-gateway` 时还要升级到 Spring Cloud 2026.0.0）；不支持 Spring Boot 4.1 与 4.2 模块混合的类路径。
  - **转发头策略改为 `server.request.header.forwarded.strategy`**（`web-server`、`framework`、`none`），这是 Spring Boot 4.2 的
    名称。CoSec 读取它，也仍读取已弃用的 `server.forward-headers-strategy`（其 `native` 即 `web-server`），以决定如何读取客户端
    地址；它的启动消息使用新的 key（[代理后的客户端 IP](../integration/web.md#client-ip-behind-proxies)）。两者都设置时 Spring
    Boot 启动失败。
  - CORS 无需处理：Spring MVC 不再自带 `CorsConfigurationSource`（Spring Framework 7.1），所以 CoSec 的 CORS 过滤器（`STA-5`）
    仍然只在应用声明了一个时才运行。
- **`cosec-cocache` 需要 CoCache 8.2.0**（它的 POM 和模块元数据把它写成最低版本）。受影响的：固定在 CoCache 8.1.x 的应用。在 Spring Boot 4.2 上（CoCache 8.1.0 不是针对它的 Lettuce 7.8 构建的），Redis Cluster 连接会拒绝最初的命令（`Currently not connected. Commands are rejected.`）；8.2.0 先连接各主节点。随之改变的两种行为：
  - CoCache 对其 `Store` 的启动驱逐检查会重试超时的读取（最多五次），失败时说明原因：Redis 拒绝了 `INFO`，或者没有应答（[故障排查](./troubleshooting.md#startup-fails-on-the-eviction-check)）。CoSec 自己的检查（`SNP-3`）在启动时以同样的方式读取：慢的 Redis 最多被问五次后启动才失败，消息写明是超时（`Redis did not answer INFO memory ...`）还是拒绝（`Redis refused INFO ...`）。旧的原因文本 `INFO memory failed or is not allowed` 不再出现：基于这段文本的告警现在匹配不到任何东西。周期性的复查仍然每个间隔读一次，原因相同。
  - 解码为 `null` 的存储内容会让读取失败，而不是读作不存在。CoSec 不写这样的内容；别的写入者在吊销表里留下的 JSON `null` 条目，过去读作“未吊销”，现在作为外来内容让请求失败。
- **路径段的上限按解码后计**（`PIP-5`，#1853）。受影响的是路径段只在百分号编码后才变长的客户端，典型的是非拉丁文字的标识符。直到 beta.5，按收到时超过 256 个字符的段答 `400` `invalid-request`，所以 29 个汉字（编码后 261 个字符）被拒绝，而 256 个 ASCII 字符可以通过。现在段按匹配器看到的样子计数，即解码后的 UTF-16 单元：此前被拒绝的这类请求现在会被承载并决策。整个路径仍按收到时限制在 2,048 个字符，所以单个由汉字组成的段最多 227 个。其他所有拒绝都不变，长段内部也一样：编码后的 `/`、`;` 或 `\`、控制字符、格式错误的转义、点号段或空段。控制面模拟、解释或分析的类型化 id 仍按书写计；模式设置了 `decodeAndParseSegments: false` 的 ALLOW 也一样：它按收到的原文读取各段，所以不匹配任何按收到时含有超过 256 个字符的段的路径，这样的请求由其他语句决策。除非你依赖这种拒绝，否则无需改动。
- **新增：角色类 `cosec:manage-roles`**（`CTL-79`）。持有者对租户文档只写入或删除角色的变更，不再因“由谁授予”而等人审批；它记下一条 `ROLE_CLASS` 升级，这是封闭集合的一个新值，映射升级原因的客户端会读到它（[角色类](../policies/testing.md#the-role-class)）。不持有它的主体一切照旧。**控制面自身应用（`cosec.control.app-id`）的角色从不属于这个类**：它的成员持有对本租户文档的控制调用，而由谁治理租户的策略应由人来任命。投影这类角色的同步仍然要等审批；只通过这个类投影业务应用的角色。
- **新增：服务通过服务端口和 `ControlService` 修改应用文档**（`CTL-58`、`CTL-65`）：应用文档的 `propose`、`check` 和变更流程，此前在那里是 `404`。这样的变更与在运维人员端口上一样被分析：删除角色、策略或绑定可以单独发布，其他任何变更（目录、写入角色）都要等人审批。平台文档仍不可触及。不在 `doc/app/...` 上持有 `cosec:write` 的服务一切照旧。
- **服务端口和 `ControlService` 上没有信任根**（`CTL-81`）。在那里声明平台管理员身份、以便不经分析发布租户（或应用）文档的服务，现在要等人审批：不论声明的是谁，变更都要经过分析，分析无法证明的授予是 `AWAITING_APPROVAL`。通过[角色类](../policies/testing.md#the-role-class)（`cosec:manage-roles`）把角色投影进租户，或在运维人员的路由上由人审批。同一个主体在运维人员的路由上一切照旧。
- **实例的状态拆分为两条记录**（`CTL-74`、`CTL-75`）。`<s>:shadow:status:<instance>` 处的状态现在很小（存活信息、`shadows`、
  已确认的窗口），没有 `documents` 成员。实例持有的文档是一条独立的记录 `<s>:shadow:resident:<instance>`，只有
  `propagation` 读取它（[存储](./storage.md#redis-keys)）。`propagation` 的应答多了一个成员 `unknown`，总是写出。
  - **按这个顺序升级：先升级所有控制面实例，再升级数据面。** beta.6 的控制面能读取 beta.5 实例的状态（其中的
    `documents` 被忽略），在该实例升级之前把它计入 `unknown`，因为它不写常驻记录。
  - **beta.5 的控制面读不了 beta.6 的状态**：它要求有 `documents`。按 `CTL-73`，它把每个 beta.6 实例当作存活但什么都
    不确认，于是每个影子窗口都会等待，到 `max-duration` 时以证据不足关闭。没有窗口会通过，也没有变更会被错误地发布；
    控制面升级完成后，窗口照常关闭。
- **数据面的 Redis 用户需要常驻记录的 key。** 如果你照抄了[存储](./storage.md#redis-keys)中的数据面用户，在它的
  `shadow:status` key 旁边加上 `~cosec:shadow:resident: ~cosec:shadow:resident:<instance>.*`（换成你的前缀和实例 id）；
  没有它们，beta.6 实例启动时会因 `NOPERM` 失败。
- **SPI（实验性）：新增抽象方法 `ShadowReportSink.write(ResidentDocuments)` 和 `ShadowReportReader.residents(instances)`；
  移除 `InstanceStatus.documents` 和 `InstalledDocument`。** 自己实现的后端要为每个实例另存一条记录，生命期为
  `ShadowReportReader.RESIDENT_TTL`，`residents` 应答能读取的记录，并在 `unreadable` 中列出记录缺失或无法解码的实例
  （[扩展](../architecture/extending.md)）。没有默认实现：回答“什么都不持有”的默认实现会让 `installed == holding` 在
  并不知情时声称没有实例落后。
- **控制面门面成为独立模块 `cosec-control-sdk`**（`CTL-65`）。受影响：调用 `ControlService` 的代码。`ControlService`、`ControlCaller` 和 `ControlRefusedException` 的名称和包（`me.ahoo.cosec.control.sdk`）不变；`cosec-control` 会带上这个模块，所以已依赖 `cosec-control` 的构建无需改动。只调用控制面宿主的代码需要 `cosec-control-sdk` 及其 HTTP 实现 `cosec-control-http`（`cosec.control.service-url`），不必再自己写 `CoSec-Identity`。
  - `ControlService.changes` 按 `me.ahoo.cosec.control.sdk.ChangeState` 过滤，这是线上格式的封闭集合，未知值保留其文本（`isUnknown`），不再是控制面内部的枚举：请传入新包的 `ChangeState.READY`，或 `ChangeState.of("READY")`。
  - `ControlCaller.of(context)` 不再对 CoSec 未创建的安全上下文抛出异常：改由接收它的调用抛出同样的 `IllegalArgumentException`（`PIP-29`）。
- **每个阶段的超时都是 `cosec.pipeline.timeout` 的一份，十个 key 被删除**（`PIP-33`、`OBS-10`；能计算出来的就不配置）。受影响：设置了其中某一项的配置，以及在 `Pipeline.builder` 的 `options { }` 或 `listeners { }` 中设置过超时的代码。启动会失败并列出 CoSec 不读取的 key，对每个都说明它已不再是配置项以及由什么取代。删掉这个 key；如果你原来移动过这个值，就改为移动 `cosec.pipeline.timeout`（或下表所列的值）。在默认的 5 秒期限下，每个值都等于它原来的默认值，所以什么都没设置的部署照旧应答（[由计算得出，而非配置](./configuration.md#computed-not-configured)）。

  | 已删除 | 现在 |
  |---|---|
  | `cosec.pipeline.authentication-timeout` | `cosec.pipeline.timeout` 的 20 % |
  | `cosec.authorization.membership.timeout` | 它的 10 %，也是成员关系缓存一次填充的时限 |
  | `cosec.authorization.attributes.timeout` | 它的 6 %，用于没有自行设置超时的 `AttributeProvider` |
  | `cosec.authorization.rate-limit.timeout` | 它的 10 % |
  | `cosec.authentication.revocation.read-timeout` | 认证超时的 30 %：一个令牌的两次读取按构造就在认证超时之内结束，比较两者的启动检查随之删除 |
  | `cosec.observability.listener.required-timeout` | `cosec.pipeline.timeout` 的 20 % |
  | `cosec.control.audit.required-timeout` | `cosec.pipeline.timeout` 本身，即控制请求自身授权的上限 |
  | `cosec.observability.listener.drain-timeout` | `spring.lifecycle.timeout-per-shutdown-phase` 的六分之一，变更监听器和 Kafka 监听器也一样 |
  | `cosec.authorization.snapshot.local-max-age` | 3 × `cosec.authorization.snapshot.revalidate-interval` |
  | `cosec.authorization.membership.unknown-ttl` | 常量，5 秒 |

  - `PipelineSettings`（在 `options { }` 中）保留 `timeout`，去掉 `authenticationTimeout`、`membershipTimeout`、`attributeTimeout` 和 `rateLimitTimeout`；`ListenerSettings`（在 `listeners { }` 中）去掉 `requiredTimeout` 和 `drainTimeout`，下一项连同这个类本身一起删除。改为设置 `timeout`，或删掉这一行。
  - 自带超时的 `AttributeProvider`，以及你自己的 `RevocationReader`，保留它们自己的时限。
  - `cosec.pipeline.timeout` 低于 20 ms 时，每一份以 1 ms 为下限，一个令牌的两次吊销读取可能不再在认证超时之内结束。
  - **`cosec.pipeline.timeout` 低于默认值的部署**同时缩短了吊销读取，这是唯一一个失败即放行的阶段：`1s` 时每次读取只有 60 ms，而原来是 300 ms，超过它的读取会接受请求的令牌，除非设置了 `cosec.authentication.revocation.fail-closed=true`。请设置 `fail-closed`，或保留默认期限。
- **内存上限是堆的一份，三个 key 被删除**（`STA-12`、`OBS-11`、`IDN-32`、`IDN-33`；能计算出来的就不配置）。受影响：设置了其中某一项的配置，以及调用过 `Pipeline.builder` 的 `listeners { }` 的代码。启动会失败并列出 CoSec 不读取的 key，说明每个由什么取代。删掉这个 key；要容纳更多，就给实例更多的堆。在生产 overlay 的 2 GiB 堆上，没有哪个上限低于它所取代的数；堆越小得到的越少，启动日志行写明每一项得到多少（[由计算得出，而非配置](./configuration.md#computed-not-configured)）。

  | 已删除 | 现在 | 2 GiB 时 | 太紧的表现 |
  |---|---|---|---|
  | `cosec.authentication.revocation.cache.maximum-size`（500,000） | 堆的八分之一，按每表每条 290 + 230 字节计数；CoSec 锁定这个数 | 每表 516,222 条 | 带标签 `cache=RevokedTokenCache` 或 `RevokedSubjectCache` 的 `cocache.misses`（`cocache_misses_total`） |
  | `cosec.authentication.token-cache.max-entries`（16,384） | 堆的十六分之一，每个条目按令牌长度 × 5 字节计重 | 128 MiB，多于 16,384 个大令牌 | `cosec.token.cache.evictions` |
  | `cosec.observability.listener.queue-capacity`（1,024） | 堆的六十四分之一，由各 `BEST_EFFORT` 监听器均分，按每个事件 1 KB 计数，`cosec.pipeline.trace-level` 为 `full` 或 `exhaustive` 时按 8 KB | 四个监听器时每个 8,192 个事件，追踪时 1,024 个 | `cosec.listener.dropped` |

  - `ListenerSettings` 和 `PipelineBuilder.listeners { }` 被删除：监听器如何运行不再有任何设置。删掉这个代码块。
  - `cocache.caches.[RevokedTokenCache].local-maximum-size`（或 `RevokedSubjectCache`）现在会让启动失败：这个数被锁定。
  - 变更监听器占堆的三十二分之一不变，现在也在同一张表里。
- **快照族的上限由堆的四分之一推出，四个 key 被删除**（`SNP-24`、`STA-12`）。受影响：设置了其中某一项的配置，以及在 `Pipeline.builder` 的 `snapshots { }` 中设置过它们的代码。启动会失败并列出 CoSec 不读取的 key，说明每个由什么取代。删掉这个 key；要持有更多文档，就给实例更多的堆（[由计算得出，而非配置](./configuration.md#computed-not-configured)）。

  | 已删除 | 现在 |
  |---|---|
  | `cosec.authorization.snapshot.max-apps`、`max-tenants` | 每个族已持有的，加上堆的四分之一（减去"不存在"条目那一份）剩下的空间，按其常驻文档的典型重量计数（一个从不低于实际保留量的估计），每轮重新确认时以及每次淘汰之前重新计算，从不低于 64 |
  | `cosec.authorization.snapshot.max-absent-apps`、`max-absent-tenants` | 文档那四分之一中固定的十六分之一，每个条目 512 字节：2 GiB 时 256 个应用 id 和 65,280 个租户 id |

  - **原先在 2 GiB 上依赖 16,384 个常驻租户的网关现在约持有 14,500 个**部署页面那种典型租户（每个估计 34.6 KB，实际保留 29.5 KB）。要全部持有，给它 2.5 GiB 的堆（`-Xmx2560m`，约 18,200 个）；`cosec.snapshot.bound` 和启动日志行写明它持有多少。
  - 控制面与管道同在一个进程时，两个快照源各占那四分之一的一半。
  - `SnapshotSettings`（在 `snapshots { }` 中）去掉 `maxApps`、`maxTenants`、`maxAbsentApps` 和 `maxAbsentTenants`：删掉这些行。
  - 小 pod 得到的文档较少，照旧变为就绪；比整个份额还重的文档仍然会被提供。

## 从 8.0.0-beta.4 升级 {#from-8-0-0-beta-4}

- **十二个配置项改为计算得出，不再有 key**（`SNP-23`、`CTL-72`；规则是：能计算出来的就不配置）。受影响：设置了其中某一项的配置。启动会失败并列出 CoSec 不读取的 key，对每个都说明它已不再是配置项以及由什么取代。删掉这个 key；如果你原来移动过这个值，就移动它所依据的值（[由计算得出，而非配置](./configuration.md#computed-not-configured)）。`snapshot.reload-concurrency` 是其中没有出现在任何发布版本里的一个。

  | 已删除 | 现在 |
  |---|---|
  | `cosec.authorization.snapshot.lag-warn`、`lag-escalate` | `revalidate-interval` 的 6 轮和 30 轮：要移动它们就设置 `revalidate-interval` |
  | `cosec.authorization.snapshot.cold-load-timeout` | `cosec.pipeline.timeout` 的 40 %：要移动它就设置后者 |
  | `cosec.authorization.snapshot.compile-threads` | 每 2 个核心一个，至少 1 个、至多 4 个 |
  | `cosec.authorization.snapshot.reload-concurrency` | 16，常量 |
  | `cosec.authorization.shadow.report-interval` | 状态存活期的六分之一（10 秒），且不比 `cosec.control.shadow.min-duration` 的五分之一或一秒更频繁 |
  | `cosec.authentication.revocation.cache.initial-capacity`、`expire-after-access` | 没有：缓存自己决定大小，`local-max-age` 让每个条目过期 |
  | `cosec.control.revisions.orphan-age`、`reclaim-interval` | 满一轮回收，以及 1 小时 |
  | `cosec.control.shadow.check-interval` | `cosec.control.shadow.min-duration` 的十五分之一，在 250 ms 到 1 分钟之间 |
  | `cosec.control.http.max-body-size` | 8 MB 的文档上限再加 2 MB：10 MB |

  启动时 starter 记录一行以 `CoSec derived, not configured:` 开头的日志，说明它推出了什么、依据是什么。原来缩短了 `report-interval` 和 `check-interval` 的系统测试，现在只缩短 `cosec.control.shadow.min-duration`，两者随之变化。
- **新增：`GET /documents?kind=tenant|app` 列出有哪些租户文档和 app 文档**（`CTL-68`），按页给出，在运维人员的路由、服务端口和门面（`ControlService.documents`）上都可用。谁可以列出、一页说明什么，见[列出文档](../policies/testing.md#listing-documents)。无需改动。
- **控制 API 的列表用不透明游标分页，未到末尾的一页也可能不满或为空**（`CTL-55`、`CTL-68`，第三轮评审）。受影响：`GET /changes` 和 `GET /documents` 的客户端。
  - `next` 和 `after` 是令牌：把 `next` 原样作为 `after` 传回，不要从中读取任何内容。格式错误的令牌、属于另一个列表的令牌，或指向该列表没有的位置的令牌，返回 `400`，绝不会从头列出。
  - `GET /changes?state=` 在读取存储之前先做决策，每个请求至多读取四页的变更：带 `next` 的一页可能少于 `limit` 条，甚至为空。带着 `after` 再次请求，直到没有 `next`（[列表游标](../policies/testing.md#listing-cursors)）。
- **变更的应答带有其作者是如何被识别的**（`CTL-70`）。每个读取者都得到 `origin.asserted` 和 `origin.via.method`；在该变更的文档上持有 `cosec:approve`、或在平台文档上持有 `cosec:read` 的读取者，还会得到 `via.credential`、`via.request` 和 `peer`。不是由请求创建的变更没有 `origin`。不认识这个成员的客户端会忽略它（[变更应答什么](../policies/testing.md#what-a-change-answers)）。
- **变更监听器按写入顺序接收一个变更的事件，通过它自己的、以堆的一部分为上限的队列**（`CTL-66`）。受影响：你自己的变更监听器，以及变更流的使用者。
  - 每个监听器有一个队列和一个消费者，所以不会再乱序收到同一个变更的事件。
  - `REQUIRED` 监听器没有空间、失败或在 `cosec.control.audit.required-timeout` 内没有应答时，受控步骤失败（`503`），与以前一样。对于其他步骤，这一步照常存储，找不到空间、或调用失败的事件会丢失：计入 `cosec.control.listener.lost`，并以 `ERROR` 记录，写明是哪个变更。
  - 关闭时，队列在 `cosec.observability.listener.drain-timeout` 内投递（[顺序与队列](./observability.md#change-event-order)）。
- **CoSec 自己的令牌校验器对已校验过的令牌从内存作答，直到它过期**（`IDN-32`）。默认开启；吊销检查和保留属性检查仍在每个请求上运行。`cosec.authentication.token-cache.enabled` 和 `max-entries`（`16384`）是新增的；应用自己的 `TokenVerifier` 不会得到缓存（[已校验令牌缓存](../integration/authentication.md#the-verified-token-cache)）。无需改动。
- **对吊销检查而言，挂起的 Redis 被当作宕机的 Redis 处理**（`PIP-7`）。受影响：设置了 `cosec.pipeline.authentication-timeout` 的部署。CoSec 吊销读取器的每次读取现在有自己的预算，即新增的 `cosec.authentication.revocation.read-timeout`（`300ms`）；超过后令牌被接受（使用 `fail-closed` 时被拒绝），刷新失败，而以前挂起会让每个经过认证的请求都返回 `503`。除非读取超时的两倍小于认证超时，否则启动失败，并写明这两个配置项。应用自己的 `RevocationReader` 自行限定它的读取。
- **数据面实例读取暂存列表，并在看到暂存候选时就编译它**（`CTL-47`、`CTL-12`、`OBS-8`）。实例只对读取列表所列文档的请求采样；对无法编译的候选，在看到它之后的一个报告间隔内报告 `CANDIDATE_ERROR`，不论它有没有流量；没有采样时，不为影子监听器生成决策事件。读不到的列表按没有列表处理，每个候选照旧按 key 读取。无需改动；实例需要的是上面 `CTL-46` 那一行 Redis ACL。
- **控制 API 的服务端口就是应用自己的服务器，只是在另一个端口上**（`CTL-71`）。它沿用应用的 TLS 与客户端认证、绑定地址、HTTP/2、各项限制与超时，只提供控制 API 的服务档，不提供应用的任何路由。如果让“什么能连到宿主”这一声明属实的是应用端口上的双向 TLS，它在服务端口上同样成立。
- **定义了另一个 app 已定义的系统角色 id 的 app 变更会带上警告**（`ROL-12`）：一条 `REFERENCES` 发现，写明角色 id 和其他 app，从不拒绝。
- **登录相关的 bean 让位于应用自己的，`min-samples: 0` 会在启动时说明**（UB-15）。声明了自己的 `CompositeAuthentication`（或令牌组合）的应用会使用它启动，而以前会因为有两个 primary 而失败。设置 `cosec.control.shadow.min-samples=0` 时，启动日志记录一条 `WARN`：窗口此时只按时间关闭。
- **新增：控制面有了镜像 `cosec-control`，以及两种有名字的部署**（[控制面宿主](./deployment.md#the-control-plane-host)、[选择部署方式](./topologies.md#the-control-plane)）。内嵌控制面的应用无需任何改动：它就是第二种部署，原样不变。想把控制面作为自己一个进程来运行的部署，在网关旁边运行该镜像，并声明 `cosec.pipeline.identity.only-trusted-callers-can-reach=true`（没有它镜像不会启动），再配上使它属实的三条网络策略（`k8s/control/`）。
- **原来处于 `gateway` 模式、现在需要控制面的 IAM 服务，改为运行在 `enforce`，并设置 `cosec.pipeline.caller=forwarded-identity`。** 受影响：原来处于 `cosec.pipeline.mode=gateway`、现在要加入 `cosec-control` 的服务。`gateway` 模式不接入文档、Redis 和控制面，所以控制面无法加到它上面。
  - 把 `cosec.pipeline.mode` 改为 `enforce`，并加上 `cosec.pipeline.caller=forwarded-identity`：服务仍然相信网关转发的身份，仍然要声明什么能连到它。声明相同（`only-gateway-can-reach`，开启服务端口之后是 `only-trusted-callers-can-reach`），对隐藏方法过滤器的拒绝也相同。
  - 它现在需要 `cosec-cocache`、`cosec-control` 和一个可以写文档的 Redis 用户（[谁可以写哪些 key](./storage.md#who-may-write-which-keys)）。
  - 它自己的路由仍然要么在网关处授权，要么没有授权。代码只在进程内访问控制面时，设 `cosec.control.http.enabled=false`（[配置](./configuration.md#control-plane)）。
- **CoSec 自己的路由不再在管理端口上应答**（`CTL-69`）。受影响：有独立 `management.server.port` 的 WebFlux 宿主（或开启了控制面的网关宿主），其客户端曾在那里访问控制 API（`/cosec/control/v1/...`）或登录路由（`cosec.authentication.login.refresh.path`、`.logout.path`）。Spring Boot 的管理服务器会继承应用的 `RouterFunction`，所以它们曾在管理端口上、经管道应答；对相信所述身份的宿主（`cosec.pipeline.caller=forwarded-identity`、控制面宿主镜像），那等于把整个控制 API 提供给任何能访问该端口的人。现在它们只在应用端口上应答（控制 API 还有它的服务端口），其余端口上是 `404`。请改在应用端口上调用。应用自己的路由不变。
- **新增：控制面门面 `ControlService`，供内嵌控制面的服务使用**（`CTL-65`）。
  - 调用按它们声明的 `ControlCaller` 决策，并记为该调用方的步骤。
  - 它只触及窄集合：租户与 app 文档的读取，以及租户的变更流程。
  - 请求与应答是控制 API 的请求体和 JSON。
  - `IdentityHeader` 写出 `CoSec-Identity` 头。
  - 不使用它们的应用无需改动（[在代码中修改策略](../integration/checks.md#changing-policies-from-your-code)）。
- **只有每个存活实例都确认了影子窗口，它才会通过**（设计 D2，`CTL-10`）。受影响：升级期间及之后的影子窗口，以及读取变更 `shadow` 的客户端。
  - 每个数据面实例现在在每个上报间隔写入的状态中，列出它已编译其候选的窗口。样本已够的窗口仍要等到最近一分钟内写过状态的每个实例都列出它；到达 `cosec.control.shadow.max-duration` 时与以前一样以证据不足关闭。
  - **滚动升级期间**，上一个预览版的实例不发送确认，因此这期间开启的窗口不会早于升级结束时关闭（这些实例停止后，其状态在两分钟内过期），或在 `max-duration` 时以证据不足关闭。需要尽快关闭的变更，请先升级数据面再暂存。
  - 窗口未关闭时，`GET /changes/{id}` 增加 `shadow.acknowledged` 和 `shadow.waitingFor`（[变更的应答](../policies/testing.md#what-a-change-answers)）；`contract/control/change.json` 固定了它们。
- **跳过影子窗口和 rebase 变更都记为变更事件**（`CTL-63`）。受影响的是变更监听器，以及变更日志或审计流的消费方。
  - `ChangeEvent.Kind` 新增 `SHADOW_SKIPPED` 和 `REBASED`。过去跳过只记在它所发布的修订的来源中，所以变更被放弃时跳过不留任何记录；现在
    `:skip-shadow` 以及 break-glass 变更免除的窗口会写 `SHADOW_SKIPPED`，备注写明原因，`:rebase` 写 `REBASED`。
  - 处理每一种类型的监听器要加上这两种；按类型文档的要求带 `else` 分支的监听器无需改动。
- **控制面记录每一步的操作者是如何被识别的，并可增加服务端口**（`CTL-57`、`CTL-58`、`CTL-59`）。受影响的是针对 `ChangeEvent`、`ChangeRequest` 或 `HttpFacts`（实验性）编译的代码：它们的构造函数各在末尾增加了一个带默认值的参数（`origin`、`origin`、`peerAddress`），请重新编译。以 JSON 写出的变更事件和存储的变更增加了 `origin` 成员（`asserted`、`via`、`peer`），不认识它的读取方会忽略。新增且默认不设置：`cosec.control.http.enabled` 和 `cosec.control.http.service-port`（[配置](./configuration.md#control-plane)）。两者都没有设置的部署无需改动。每个控制面实例都运行维护任务，由租约隔开，没有任何配置项能把它们关掉。
- **控制 API 由自己的应答模型作答；被拒绝的作用域在 `reason` 下说明原因；传播只统计持有该文档的实例**（第二轮评审，
  `R2C-11`、`R2C-12`、`CTL-61`、`CTL-62`）。受影响的是控制 API 的客户端。
  - 变更的应答（`GET /changes/{id}` 以及每个应答变更的路由）不再包含 `checkedWith`、`checkedApps` 和窗口的
    `shadow.reporters`：它们是存储的簿记，不属于 API。其余成员不变（[变更的应答](../policies/testing.md#what-a-change-answers)）。
  - `simulate` 与 `explain`：`Rejected` 决策的 `APP_MISMATCH` 或 `SPACE_NOT_MEMBER` 放在 `reason` 下，不再放在 `cause`
    下。`cause` 只属于 `Indeterminate` 决策，取值不变。
  - `GET /documents/{key}/revisions/{n}/propagation` 新增 `holding`（持有该文档的最近实例）和 `asOf`（读取状态的时间；
    应答最多可能是 5 秒前的）。`installed` 与 `recent` 含义不变。`installed` 等于 `holding` 时没有任何实例落后：
    大多数实例从不持有某个租户，所以对它而言 `installed` 从来到不了 `recent`。`holding: 0` 表示没有实例持有该文档，第一个请求会按已发布的修订装入它。状态读取失败现在是 `503`，不再是 `500`。
  - `contract/control/` 固定了这些应答的成员、它们写出的封闭取值集合以及每个路由的审计种类；改变其中任何一项的版本会写明。
- **从 `CoSec-Identity` 头取得调用方的宿主，无论怎样得到这个 gate，都要声明什么能访问它**（`STA-6`、`STA-7`、`STA-9`）。受影响的是：在 `cosec.pipeline.mode=enforce` 旁边自己声明了 `IdentityGate` bean 的应用，它此前没有任何声明也能启动。现在它启动失败，直到 `cosec.pipeline.identity.only-gateway-can-reach=true`（或 `only-trusted-callers-can-reach=true`）声明除网关和你指名的服务外没有任何东西能访问它，并且关闭 hidden-method 过滤器。`gateway` 模式的宿主不变。
- **新增：`cosec.pipeline.caller=forwarded-identity`。** `enforce` 模式的宿主在装配了快照和控制面的情况下，可以从身份头而不是凭证取得调用方：控制面宿主，或内嵌控制面的 IAM 服务就是这种形态。没有设置它的宿主无需改动（[配置](./configuration.md#pipeline)）。
- **每条存储的记录都带有格式，读取方忽略已知格式中不认识的内容**（第二轮评审，`R2C-10`、`R2C-28`、`CTL-53`、`IDN-31`）。受影响的是每个部署，一次。
  - **停止控制面，升级，再启动。** 此前的预览版拒绝新记录带有的 `format` 成员，所以两个版本的实例不能同时运行。从这个版本起读取方是容忍的，以后的版本可以原地升级。
  - **一起滚动升级登录主机。** 仍运行旧预览版的登录主机读不了新版本写入的会话：刷新落到那里的用户需要重新登录。
  - **仍运行旧预览版的数据面实例**会跳过新控制面写入的暂存候选，在升级之前不为它的窗口报告任何内容。窗口会等待，不会通过。
  - **无需转换：** 有格式之前写入的记录按格式 1 读取。窗口报告者和实例索引现在是对象（`{"format":1,"reporters":[...]}`），它们以前的形式仍可读取。
  - 存储记录中无法解码的值现在总是格式错误。以前有些是 `IllegalArgumentException`，控制 API 返回 `400`。
- **控制存储无法应答时，控制 API 返回 `503`**（第二轮评审，`R2C-6`、`CTL-52`）。受影响的是控制 API 的客户端与告警，以及你自己的 `ControlStore`。
  - 发布、读取文档或修订期间的 Redis 故障或超时，以前返回 `500` `internal-error`，现在返回客户端可以重试的 `503` `unavailable`，
    并以 `WARN` 而非 `ERROR` 记录。已存储的值无法解码时仍是 `500`。
  - `ControlStore` 以 `me.ahoo.cosec.control.store.ControlStoreException` 表示"存储无法应答"。你自己的 `ControlStore` 遇到故障时抛出它；
    它抛出的其他异常都是 `500`。
- **应用变更按每条策略一项列出它触及的租户；升级项的原因是枚举**（第二轮评审，`R2C-14`、`R2C-5`、`CTL-48`）。受影响的是读取变更
  `escalations` 的一方（控制 API 的 `GET /changes/{id}`、`ChangeListener`、Kafka 上的审计 JSON），以及构造或读取 `Escalation` 的代码。
  - 每条租户绑定一项的 `TENANT_BINDING` 已移除。放宽权限策略的应用变更，每条策略带一项 `TENANT_BINDINGS`：
    `tenants binding [<policy>] in app <app>`，写出语句、绑定它的租户文档数（来自反向引用索引的上限）及其中前 20 个。以
    `TENANT_BINDING` 条数判断变更触及多少租户的使用方，改读 `detail` 中的数量。在 50,000 个租户时，变更记录从约 50 MB 降到几 KB，
    每个审计事件同样如此，检查变更也不再读取任何租户文档。
  - `Escalation.reason` 是 `Escalation.Reason` 枚举，名称就是 JSON 一直携带的名称；`Escalation.TENANT_BINDING` 与
    `Escalation.DETECTION_TIMED_OUT` 变为 `Escalation.Reason.TENANT_BINDINGS` 与 `Escalation.Reason.DETECTION_TIMED_OUT`。已存储的
    变更中本版本不认识的名称读作 `UNKNOWN`，它从不是仅供参考的。
  - `ChangeEvent`、`Escalation`、`RevisionRef` 与 `MovedHead` 标为 `@ExperimentalCoSecApi`：你的 `ChangeListener` 需要 opt in，在某个
    版本用过它们之前，它们可能在小版本中变化。
- **核心模块的公共形状在 RC 之前定下**（第二轮评审，`R2A-1` 至 `R2A-7`）。受影响的是针对 `cosec-model`、`cosec-spi`、`cosec-api` 或引擎编译的代码；
  文档、属性、已存储的值和线上格式都不变。
  - 决策值是写明 `equals`、`hashCode` 和 `toString` 的普通 final 类，不再是 data class：`Decision.Permit`、`Deny`、`Indeterminate`、
    `Obligation.RateLimit`、`ViewVersion`（在 `cosec-engine`）以及除 `NotLoaded` 之外的每个 `Cause` 都没有 `copy` 和 `componentN`，
    以后加字段不会破坏调用方。读属性（`decision.by`、`cause.error`），不要解构决策。
  - `me.ahoo.cosec.spi.ratelimit.RateLimit` 和 `RateLimiter.window` 已删除。`RateLimitSpec` 有 `limitPerWindow` 和 `windowMillis`，
    `limit.resultOf(allowed, estimate, nowMillis)`（导入 `me.ahoo.cosec.spi.ratelimit.resultOf`）构造 `RateLimitResult`：
    你自己的 `RateLimiter` 里，用 `limit` 取代 `RateLimiter.window(limit)`。
  - `AuthnResult.Authenticated(principal, method)` 的 `method` 是 `Identity.Method`，不再是字符串：`Identity.Method.API_KEY`，
    或自己凭证的 `Identity.Method("ldap")`，任何非空名称。审计记录和 `CoSec-Identity` 中 CoSec 自己的方法取值不变（`jwt`、`api-key`）。
  - `AuditEvent.kind` 是 `AuditEventKind` 枚举，取值仍是原来的五个；sink 或 Kafka 消费者读到的 JSON 字节不变。要知道请求为何如此结束，读
    `authorization.reasonCode`（`decision` 是它较粗的分组，对应关系写在 `AuditDecision` 的 KDoc 中）；`binding` 是给人看的标签，
    类型化的事实是 `authorization.source.via` 和 `document`。
  - `SnapshotDocumentStore` 标为 `@ExperimentalCoSecApi`：它的形状跟随唯一的后端 `cosec-cocache`，批量或整库的条件读已在计划中。
    你自己的实现需要选择加入，并可能在次版本中修改。
  - `Resource.attributes` 默认为空 map：目前没有任何地方设置它（`DOC-10`）。
- **控制面维护一份暂存候选的列表**（`CTL-46`），`<s>:candidate-staged:all`，实例将读取它，而不再为所服务的每个文档读一个候选 key。受影响：
  - 数据面实例的 Redis ACL：在它的 key 中加上 `%R~cosec:candidate-staged:*`（[谁可以写哪些 key](./storage.md#who-may-write-which-keys)）；
  - 把控制面降级到此前的预览版：先删除 `<s>:candidate-staged:all`，否则实例会信任一份没人维护的列表。这样的列表只会让影子窗口少得样本，绝不会让窗口通过。
- **反向引用和文档列表是集合：运行一次 `cosec-migrate repair-references`**（`CTL-42`、`CTL-43`、`CTL-44`、`MIG-4`）。受影响的是 8.0.0-beta.4 或更早版本的控制面写过的每个存储。数据面实例不受影响：它们不读这些 key。
  - 一个策略的反向引用（`<s>:control:refs:<doc>:<policyId>`）原来是一个值，每个加入或退出的租户都整体重写它；现在它是一个集合，每个引用者的每个修订一个成员，文档列表也从 `<s>:control:list:documents` 移到了集合 `<s>:control:documents:all`。租户的一次发布现在只写它自己的成员，无论有多少租户（[存储](./storage.md#redis-keys)）。
  - 停掉所有更早版本的控制面，然后运行一次 `cosec-migrate repair-references --to-redis <uri> [--cluster] [--key-prefix <p>]`；本版本的控制面可以同时运行。它转换头触及的每个反向引用值，从不替换集合，最后删除旧的文档列表，并输出 `The store is in the format of 8.0.0-beta.5.`。再运行一次不会改变任何东西（[修复反向引用索引](./upgrading.md#repair-the-back-reference-index)，那里给出了它的开销：50,000 个租户约 130 万条命令，往返 1 ms 时约 22 分钟）。
  - 在它运行之前，控制面照常启动，记录一条写明该命令的 `ERROR`，其 `cosecControlStoreFormat` 健康指示器为 `DOWN`（[可观测性](./observability.md#snapshot-health)）。触及旧格式反向引用的变更会被拒绝（`503`，写明该命令），从不放过。
  - 没有任何头触及的值保持旧格式：它什么也不阻止；以后触及它的变更失败关闭，写明该命令，直到再运行一次。
  - 这两个集合从不在本地保留：`ReferenceIndexCache` 和 `ControlDocumentSetCache` 的 `local-max-age` 与 `degraded-local-max-age` 锁定为 1 ns，设置其中之一的 `cocache.caches.*` 条目会让启动失败（[配置](./configuration.md#cocache-properties-that-affect-cosec)）。
- **未完成的变更是集合，`GET /changes` 列出它们：停掉控制面，运行 `cosec-migrate repair-references`**（第二轮评审，`R2C-2`、`R2C-4`、`CTL-54`、`CTL-55`）。受影响的是 8.0.0-beta.4 或更早版本的控制面写过的每个存储，以及你自己的 `ControlStore`。
  - 未完成的变更原来是一个值 `<s>:control:list:open-changes`，每个变更都重写它；现在它们是权威集合，每个文档一个、每个状态一个，外加一个标记 `<s>:control:changes:kept`（[存储](./storage.md#redis-keys)）。两个变更不再争用同一个值，维护任务只读取一个文档或一个状态的变更，而不是所有未完成的变更。
  - 停掉所有控制面，升级它，运行 `cosec-migrate repair-references`（与上面反向引用的是同一次运行）：它把旧列表中仍未完成的每个变更登记到集合中，只有在每个变更都读取成功之后，才把集合标记为 kept 并删除旧列表。它输出 `Listed N open change(s) in the open-change sets.`，每个记录无法读取的变更输出一行 `FAILED`，只要还剩一个就失败：删除或修复该记录后再运行一次。本版本的控制面创建的存储没有旧列表，从一开始就是 kept。
  - 在集合被标记为 kept 之前，以及每当旧列表再次出现时（更早的控制面在该存储上启动过），控制面不回收任何修订，记录一条点名该命令的 `WARN`，`GET /changes` 返回 `503`：集合不被信任为包含每个未完成的变更，而未完成变更所依赖的修订不能被回收。不在影子中的变更现在由回收任务（`cosec.control.revisions.reclaim-interval`，每小时）或在被读取时过期，不再每隔 `cosec.control.shadow.check-interval` 过期。
  - `GET /changes?document=&state=&after=&limit=` 以摘要列出调用者可以读取的未完成变更（[查找未完成的变更](../policies/testing.md#finding-open-changes)）。
  - `ControlStore.openChanges()` 没有了。`ControlStore` 有 `openChangesKept()`、`keepOpenChanges()`、`openChanges(document)` 和 `changes(state, after, limit, documents)`，它们返回 `ListedChange`（`Readable` 或 `Unreadable`）和 `ChangePage`，都是 `@ExperimentalCoSecApi`。
  - `ControlChangeSetCache` 是权威集合：它的 `local-max-age` 和 `degraded-local-max-age` 锁定为 1 ns，与其他集合相同（[配置](./configuration.md#cocache-properties-that-affect-cosec)）。
- **报告者消失的影子窗口先等待，再以证据不足关闭；影子任务跳过读不了的内容**（第二轮评审，`R2C-3`、`R2C-17`、`CTL-56`）。受影响的是处于影子模式的变更、读取已关闭窗口摘要的一方（`GET /changes/{id}`、`ChangeListener`、Kafka 上的审计 JSON），以及针对控制面日志的告警。
  - 在窗口上报告过、而现在没有报告的实例，原来会让对该窗口的每次检查都失败（`POST /changes/{id}:shadow` 的调用者得到 `503`，每轮影子任务记一条 `WARN`），窗口永远不关闭。现在窗口等待它，并记录 `Shadow window <w> of change <c> is waiting for [<instances>]`；到达 `cosec.control.shadow.max-duration` 时，窗口像样本不足的窗口一样以*证据不足*关闭，摘要的 `vanished` 点名这些实例。它绝不会凭其他实例的报告通过：发布需要确认摘要（[测试变更](../policies/testing.md#trying-changes-in-shadow-mode)）。
  - `ShadowSummary` 新增 `vanished`，没有实例消失时为空；存储的变更和审计事件的 `shadow` 只在它非空时以数组携带它。
  - 影子任务读不了的某个影子中变更不再妨碍它检查其他窗口；每小时至多以一条 `WARN` 点名一次。任务先检查最久未检查的窗口，被间隔截断的一轮记录 `looked at <k> of <n> windows`。
  - `ControlMaintenance` 接受一个 `Clock`（最后一个参数，默认系统时钟）。
  - `ShadowReportReader` 新增 `readings(window)`，返回 `ShadowReadings`：能读取的报告，以及报告已列出却无法读取的实例，窗口把它们当作已消失。`CoCacheShadowReports` 覆盖了它。你自己的读取器也可以覆盖（[扩展](../architecture/extending.md#control-store)）；不覆盖时，`readings` 读取 `reports`，在它抛出的地方照样抛出。
- **gateway 模式声明它所信任的网络边界，网关转发它所决策的方法**（`STA-7`、`STA-6`、`PIP-31`）。受影响的是每个 `cosec.pipeline.mode=gateway` 的服务和每个网关。
  - `gateway` 模式的服务相信任何 `CoSec-Identity` 头，而这个头没有签名，所以网络就是它的边界。现在除非你写下 `cosec.pipeline.identity.only-gateway-can-reach: true`（`COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true`），声明只有网关能访问它，否则它**启动失败**。未设置、空、`false`、`1` 和 `on` 同样失败，信息会说明该写什么。先让它成真（只放行网关 pod 的 `NetworkPolicy`、双向 TLS、私有网络；[`k8s/examples/gateway-mode-service-networkpolicy.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/examples/gateway-mode-service-networkpolicy.yaml)）。设置之后，beta.4 之前的 `WARN` 变为一行 `INFO`。启动这样一个服务的 Spring Boot 测试也要声明它：只有测试自己的客户端访问它，所以在测试中这个声明是真的。从 beta.6 起工具包提供这一行：`@SpringBootTest(properties = [IdentityHeader.ONLY_GATEWAY_CAN_REACH])`，只用于测试（[测试你的应用](../policies/testing.md#testing-your-application)）。
  - `gateway` 模式的服务只要有 hidden-method 过滤器就会**启动失败**：`spring.mvc.hiddenmethod.filter.enabled=true`、`spring.webflux.hiddenmethod.filter.enabled=true`，或它自己的 `HiddenHttpMethodFilter` bean。网关决策的是请求行的方法，而 `_method` 表单字段会让服务执行另一个方法。移除它。自己决策的宿主（`enforce`）不受影响。
  - 网关从它转发的每个请求、每条路由上移除 `X-HTTP-Method-Override`、`X-HTTP-Method` 和 `X-Method-Override`（不区分大小写）。不可配置。以前会遵从这些头的后端，对经过网关的请求不再遵从：把方法写在请求行里。
- **路径含有编码后的 `;` 或控制字符时答 `400`**（R1A-3，`PIP-30`）。受影响的是在路径任何位置发送 `%3B` 或 `%00` 到 `%1F`、`%7F`（大小写均可）的客户端。直到 beta.4，它们被当作某一段里的文本来决定，Spring 也这样读；但网关之后先解码、再去掉路径参数的后端会把 `/admin%3Bx/users` 当作 `/admin/users` 提供服务，绕过 `/admin/**` 上的 `DENY`。现在它们在认证之前被拒绝，无论是否映射了路由，答管道不予解释的路径所用的 `400` `invalid-request`；网关在转发任何东西之前就拒绝它们。未编码的控制字符也一样，CoSec 运行的服务器本来就会自己拒绝它。含有这类字符的值放到查询参数或请求体里，或者把它的 `%` 再编码一次（`%253B`）：管道解码一次，得到文本 `%3B`。`%25`、结尾的 `.` 和结尾的空格不变。检查方法：在访问日志或审计记录的 `request.path` 里搜索请求路径中的 `%3B`、`%00` 到 `%1F` 和 `%7F`（[故障排查](./troubleshooting.md#invalid-request)）。没有开关可以接受它们。
- **两个修订的差异列出不同的部分**（`GET /documents/{key}/diff`，UC-14）。受影响的是读取其 `sections` 的脚本和工具。直到 beta.4，
  `sections` 是布尔值，策略、绑定和角色之外有任何不同即为 `true`。现在它是这些部分名称的列表，取自 `catalog`、`boundary`、
  `limits`、`attributes` 和 `rateLimits`，按此顺序，都相同时为 `[]`。原来读取 `sections` 的地方改为 `sections.length > 0`
  （或等价写法），或查找你关心的那个部分（[测试策略](../policies/testing.md)）。
- **被拒绝的转发身份会被计数并说明原因，网关不再写出服务会拒绝的身份**（#1690）。受影响的是 `cosec.pipeline.mode=gateway` 的服务、它们的仪表盘和日志告警，以及在测试中调用 `IdentityHeader.of` 的代码。
  - 这样的服务以 `WARN` 在 logger `me.ahoo.cosec.runtime.identity.IdentityGate` 上记录每个被拒绝的 `CoSec-Identity` 头的原因，每种问题类型最多每分钟一次（每一次都记录在 `DEBUG`）。直到 beta.4，它只在 `DEBUG` 记录。响应不变：从不说明原因。
  - 有 `cosec-micrometer` 和 `MeterRegistry` 时，它在 `cosec.identity.refused{problem}`（`invalid-request`、`token-invalid`、`token-expired`）中对每次拒绝计数。对任何非零速率告警（[可观测性](./observability.md#metrics)）。
  - 网关拒绝写出服务会拒绝读取的身份（空 id、落单的 UTF-16 代理项、晚于 9999 年的时间、空的声明名）：请求以 `500` 失败，不转发任何头；直到 beta.4，其后的服务会回答 `400`。CoSec 自己的网关不会构造这样的身份；自定义 `Authenticator` 可能会。对这样的身份，`IdentityHeader.of` 抛出 `IllegalArgumentException`，与身份过大时一样。
- **安全上下文的读取入口按其行为命名**（UA-7）。受影响的是从 Reactor 上下文、exchange、Servlet 请求或 `SecurityContextHolder` 读取 `SecurityContext` 的代码。每个读取入口现在都有两种：`requireSecurityContext()` 在 CoSec 没有处理该请求时抛出 `IllegalStateException`，并说明该怎么做；`securityContextOrNull()` 返回 `null`。它们都带 `@JvmStatic`。旧名称没有了，也没有别名：

  | 直到 beta.4 | 从 beta.5 起 |
  |---|---|
  | `ContextView.getSecurityContext()`（抛出 `IllegalStateException("SecurityContext is null.")`） | `ContextView.requireSecurityContext()` |
  | `ContextView.getSecurityContextOrEmpty()`（返回 `null`） | `ContextView.securityContextOrNull()` |
  | `ServerWebExchange.getSecurityContext()`（返回 `null`） | `ServerWebExchange.securityContextOrNull()`，或 `requireSecurityContext()` |
  | `HttpServletRequest.getSecurityContext()`（返回 `null`） | `HttpServletRequest.securityContextOrNull()`，或 `requireSecurityContext()` |
  | `SecurityContextHolder.context`（Java：`getContext()`） | `SecurityContextHolder.securityContextOrNull()` |
  | `SecurityContextHolder.requiredContext`（Java：`getRequiredContext()`），抛出 `IllegalArgumentException` | `SecurityContextHolder.requireSecurityContext()`，抛出 `IllegalStateException`：捕获原来那种异常的代码不再捕获它 |

  处理器的 `SecurityContext` 参数不变，它的消息现在与请求读取入口的消息相同（[Web](../integration/web.md)）。

  不是 CoSec 管道构造的已绑定上下文（手工构造的或 mock）仍然被忽略（`PIP-27`），但不再无声无息：`requireSecurityContext()` 会说明这一点（"A SecurityContext is bound … that CoSec's pipeline did not make, and it is ignored"），`securityContextOrNull()` 以 `WARN` 记录这句话，每个读取入口每分钟最多一次。绑定这种上下文的测试会看到调用方缺失或是匿名的，或者在代码放行缺失上下文的地方看到放行。在测试中搜索 `SecurityContext.KEY`、`mockk<SecurityContext>` 以及你自己对 `SecurityContext` 的实现，改为绑定管道构造的上下文（[测试你的应用](../policies/testing.md#testing-your-application)）。
- **抛出异常的检查报告它自己的原因**（UA-16）。受影响的是读取检查（`AuthorizationChecker.decide`）的 `Indeterminate` 决策原因的代码。现在是 `Cause.CheckFailed(error)`；直到 beta.4，无论什么出错都是 `Cause.SourceUnavailable(PLATFORM, error)`。检查读不到某个文档时仍是 `SourceUnavailable`。检查不经过任何决策监听器，所以事件、审计、指标和 span 都不变（[代码中的权限检查](../integration/checks.md)）。
- **接受 API key 的管道在构建时就需要成员关系提供者**（UA-12）。受影响的是用 `ApiKeyAuthenticator`（单独或在 `CompositeAuthenticator` 中）构建管道却没有 `memberships(...)` 的应用。`build()` 以 `IllegalStateException` 失败，说明要添加一个。直到 beta.4，它能构建，然后对每个 key 回答 `503`。starter 在启动时就已经拒绝这样的宿主（`IDN-6`）。
- **测试工具的入口对 Java 是静态的**（UA-13）。受影响的是针对 beta.4 编译、调用 `PermittedRequest` 或 `IdentityHeader` 的代码。它们的函数现在带 `@JvmStatic`，所以 `INSTANCE` 上的方法没有了：请重新编译。Kotlin 源码不变；Java 去掉 `INSTANCE`（`PermittedRequest.withToken(...)`）。`PipelineFixture` 的伴生对象（`load`、`of`、`unreadable`）、`PipelineFixture.request`、`PipelineFixture.Principal` 和 `TokenSubject` 的构造函数，以及 `TokenSubject.of` 增加了 `@JvmOverloads` 和 `@JvmStatic`，这只是新增。
- **技能 `cosec-custom-matcher` 改名为 `cosec-extending`**（UE-21）。8.0 没有匹配器，这个技能讲的是用 Bean 扩展 CoSec（`AttributeProvider`、`MembershipProvider`、`DecisionListener`、`RateLimiter`、`Authenticator`）。目录是 `skills/cosec-extending`，它的 `name` 是 `cosec-extending`，智能体用 `$cosec-extending` 调用它。受影响的是写着 `cosec-custom-matcher` 的智能体配置、提示词或插件列表；运行中的部署没有任何变化。
- **测试绑定属于测试工具包**（Wow 演练）。受影响的是导入 `CoSecTestBindings` 的测试。它们从生产 jar 移到 `cosec-testkit`，每个栈一个：

  | 直到 beta.4 | 从 beta.5 起 |
  |---|---|
  | `me.ahoo.cosec.webflux.testing.CoSecTestBindings`（`cosec-webflux`） | `me.ahoo.cosec.testkit.webflux.CoSecTestBindings`（`cosec-testkit`） |
  | `me.ahoo.cosec.webmvc.testing.CoSecTestBindings`（`cosec-webmvc`） | `me.ahoo.cosec.testkit.webmvc.CoSecTestBindings`（`cosec-testkit`） |

  修改导入；构建里没有 `cosec-testkit` 时把它加为测试依赖。函数不变。WebFlux 的增加了挂起的 `withSecurityContext(context) { ... }`，用于被测的协程代码；`PipelineFixture.of` 和 `load` 接受应用自己的 `authenticator`（[测试你的应用](../policies/testing.md#testing-your-application)）。工具包不带来任何适配器；绑定需要测试的 classpath 上有你的 Web 栈的适配器，测试这种处理器时本来就有。

- **网关只在接受了调用方的凭证时才转发它们**（R1A-7，`IDN-8`）。受影响的是打开了 `cosec.gateway.identity.forward-authorization=true` 的网关。直到 beta.4，令牌过期、被吊销或无效的请求以匿名身份继续，公开路由放行它时，它的 `Authorization` 头、API key、查询令牌和 cookie 仍会发往接收身份的路由。现在它们留在网关：后端看到的是匿名请求。靠自己校验令牌来回答这类请求的后端，现在收不到令牌。
- **IP 黑名单按规范形式比较地址**（R1A-4）。受影响的是 `BlacklistSource` 的实现，以及 `InMemoryBlacklistSource.block` 的调用方。`contains(BlacklistKind.IP, value)` 收到的是规范形式：同一地址的任何写法都是 `2001:db8::1`，`::ffff:192.0.2.1` 是 `192.0.2.1`；被拦截的地址请按这种形式保存（[扩展](../architecture/extending.md)）。`InMemoryBlacklistSource` 就这样保存，并以 `IllegalArgumentException` 拒绝不是地址的值。
- **id 含控制字符、或 DENY 按未解码路径匹配的文档不再能编译**（R1B-3、R1A-1；`DOC-13`、`DOC-14`）。受影响的是含其中任一情况的文档。
  - **id 中的控制字符。** 文档写下的任何 id 都不能含控制字符或未配对的代理项：策略 id 和语句名、绑定 id 和主体、角色、目录权限 id、声明的属性、属性路径和路径变量名（[id 与特殊值](../policies/language.md#ids-and-special-values)）。直到 beta.4，策略 id 可以含换行，会在每个配置了路由映射的宿主上伪造一行日志。
  - **按收到的原文匹配路径的 DENY。** DENY 语句或限流规则不能再把 `decodeAndParseSegments` 设为 `false`：它会漏掉路径的每一种百分号编码写法（`/%61dmin/users` 对应 `/admin/users`）。去掉这个选项；ALLOW 可以保留它（[HTTP 路径](../policies/language.md#http-paths)）。`cosec-migrate convert` 拒绝这样写的 7.x DENY（`DOES_NOT_COMPILE`，定位到它的语句），不写出任何文档。
  - **已存储的文档会怎样。** 宿主对它失败即关闭，与对待任何无法编译的文档一样：每个需要该文档的请求回答 `503`，宿主以 `ERROR` 记录 `Snapshot document [<doc>] revision <n> does not compile`，附每个错误的 JSON 路径，id 以转义形式显示，`cosecSnapshot` 健康指示器把它计入 `invalid`（[故障排查](./troubleshooting.md)）。控制面拒绝会发布这种文档的变更：在 id 或语句的路径上给出 `COMPILE` 发现，变更停留在草稿。
  - **升级宿主之前**，用 beta.5 的编译器检查每个已发布的文档，做法见 [CI 中的策略](../policies/testing.md#policies-in-ci)（`PolicyDocumentCompiler.validate`），并为它拒绝的每个文档发布一个修正后的修订：重命名 id、去掉该选项。两者都不含的文档不受影响。
  - **然后先升级一台宿主**（或控制面的宿主），在升级其余宿主之前，查看它的 `cosecSnapshot` 健康指示器的 `invalid` 计数，以及它为每个文档记录的 `ERROR` 日志行。没有修正的文档在每台已升级的宿主上失败即关闭：那里每个需要它的请求都回答 `503`。
- **控制面以条目预算约束每个变更，并限制一个变更的操作数和请求体**（R1B-4，`CTL-40`、`CTL-41`）。受影响的是含有超出默认预算的文档、或提议非常大的变更的控制面，以及为控制 API 调高了 `spring.codec.max-in-memory-size` 的 WebFlux 宿主。在这些预算之前，一个 100,000 条目的变更曾在控制面耗尽 512 MB 的堆。
  - **条目预算。** 平台的 `limits` 接受每个文档的 `maxPolicies`（10,000）、`maxStatements`（20,000）、`maxRoleBindings`（10,000）和 `maxAnyoneBindings`（1,000）（[条目预算](../policies/language.md#entry-budgets)）。会把某个计数提高到超出预算的变更得到写明预算、计数和限制值的 `V2` 发现（`422`），停留在草稿。已经超出预算的文档仍由每台宿主继续提供，可以修改，也可以缩小；只有增加超出预算的计数的变更会被拒绝，检查会对每个仍然超出的计数给出警告。要让大文档继续增长，先在平台文档中调高预算。`cosec-migrate convert` 对超出默认预算的转换结果给出警告并照常写出。
  - **操作数。** 客户端提议的一个变更最多带 `maxChangeOperations`（20,000）个操作，同样位于平台的 `limits` 中；更大的变更返回 `400`，请拆成几个变更提议。`apply` 和回滚不受其约束。
  - **请求体。** `cosec.control.http.max-body-size` 的默认值由 `16MB` 改为 `10MB`，并且现在 WebFlux 上的控制 API 也受其约束，代替 `spring.codec.max-in-memory-size`（默认 256 KB，此前使 WebFlux 控制面无法接收更大的变更）。更大的请求体返回 `400` `invalid-request`。
- **实例持有它所服务的租户**（`SNP-7`、`SNP-14`）。受影响：所有做决定的实例，网关首当其冲。
  - `cosec.authorization.snapshot.max-tenants` 默认值从 1,024 改为 16,384。实例记住的没有文档的 id 现在每个族各有上限：
    `max-absent-apps`（256，与之前相同）和 `max-absent-tenants`（65,536，之前两个族共用 256）。两者都是属性，`SnapshotSettings` 也有。
  - 所有位置都用满时，按每个含 5 个各 20 项权限的角色和 20 条绑定的租户文档 30 KB 计，租户条目约占 520 MB 堆。请对照
    [为 SaaS 网关定容量](./deployment.md#sizing-a-saas-gateway)检查你的堆，或把这两个属性设回原值。`k8s/` 的生产 overlay 现在给网关
    2 GiB 堆和 3200Mi 限制，之前是 1 GiB 和 2Gi：足以容纳所有快照和撤销默认值都到上限时的内容。
  - 新增 `cosec.authorization.snapshot.reload-concurrency`，一轮重新确认每次读 16 个文档，而不是 4 个。每个间隔的读取次数不变；
    一轮结束得快四倍，读取也密集四倍。
  - 安装使族超过封顶时，在它自己的线程上最多驱逐该类 64 个条目，其余由后勤线程驱逐；之前是一直驱逐回上限。
- **配置的 `cosec.authorization.shadow.instance-id` 成为前缀。** id 为 `<instance-id>.<后缀>`，后缀每次启动都换新，所以重启后的进程
  绝不会替换前一个进程的影子报告，否则可能抹掉一个 `CANDIDATE_ERROR` 而使窗口解除阻塞。受影响：列出实例 key 的 Redis ACL。把
  `~cosec:shadow:report:*:pod-a` 和 `~cosec:shadow:status:pod-a` 改为 `~cosec:shadow:report:*:pod-a.*` 和
  `~cosec:shadow:status:pod-a.*`（[谁可以写哪些 key](./storage.md#who-may-write-which-keys)）；保留旧行的实例写报告和状态会被拒绝，
  并以 `WARN` 记录。
- **主机在启动时加载它指定的应用**（`SNP-20`）。它的 `cosec.authorization.scope.app-id`，网关上还有每条路由的 `cosec-app-id`，与
  `prewarm-apps` 一起加载，就绪像等待 `prewarm-apps` 一样等待它们：Redis 无法为其中一个应用作答的实例保持未就绪并重试。`prewarm-apps`
  照常工作。`cosec.authorization.snapshot.prewarm-host-apps=false` 恢复为在第一个请求时加载这些应用。
- **提供刷新路由的宿主要写明刷新出的令牌怎样得到成员关系**（`STA-8`）。受影响的是设置了 `cosec.authentication.login.refresh.path`、又既没有自己的 `RefreshTokenAuthentication`、也没有 `MembershipRefresh<RefreshTokenCredentials>` Bean 的宿主。直到 beta.4，这样的宿主悄悄地用 `MembershipRefresh.CLAIMS` 刷新：从成员身上移除的角色，在成员下一次登录之前一直有效，只要他们在刷新令牌的寿命之内刷新，最长到 `cosec.jwt.session.lifetime`（30 天）。现在它**启动失败**，消息说明这个选择的代价。二选一：
  - 保持宿主原来的行为：写 `cosec.jwt.session.memberships-on-refresh: from-token`（`COSEC_JWT_SESSION_MEMBERSHIPSONREFRESH=from-token`）。其他任何值都和未设置一样失败。启动日志有一行 `INFO`，说明它意味着的会话寿命；
  - 或声明 `MembershipRefresh<RefreshTokenCredentials>` Bean，重新读取成员的角色和附加策略，移除的角色或附加策略在 `cosec.jwt.token-validity.access`（10 分钟）之内生效。

  无论哪种，`TokenRevoker.revokeSubject` 都会立刻结束成员的全部令牌（[被移除的角色还能用多久](../integration/authentication.md#how-long-a-removed-role-lives)）。不提供刷新路由、或自己构造 `RefreshTokenAuthentication` 的宿主不受影响。
- **公布的 JSON schema 有了新的标识，文件移到了 `schema/8.0/`**（R2A-5）。受影响的是按 URL 或路径加载 CoSec schema、或按 `$id` 固定了某个 schema 的编辑器、CI 检查和工具。
  - 直到 beta.4，每个文件的 `$id`，以及文件之间 `$ref` 的解析基准，都是 `main` 上的文件 `raw.githubusercontent.com/Ahoo-Wang/CoSec/refs/heads/main/schema/<文件>`，所以 8.0 的文件会按 `main` 之后的内容解析。现在是 `https://cosec.ahoo.me/schema/8.0/<文件>`：`8.0` 是发布线（不是文档的 `schema: 2`），站点提供这些文件，仓库把它们放在 `schema/8.0/<文件>`。
  - 仍然指向 raw `main` 地址的编辑器设置，在这次变更进入 `main` 后得到 `404`：补全和校验停止，编辑器给出“无法加载 schema”的警告。请把它指向 `https://cosec.ahoo.me/schema/8.0/cosec-snapshot-document.schema.json`，或你所运行版本的标签（beta.4 之后的第一个标签起为 `.../refs/tags/<标签>/schema/8.0/<文件>`；beta.4 自己的文件仍在 `schema/<文件>`）。指向 beta.4 标签的设置继续可用，也保留它原来的 `$id`。
  - 8.0.0 之前，站点的 `/schema/8.0/` 是 `main` 上 8.0 版本线的内容，所以在预览版之间会变；标签是不可变的副本（[策略语言](../policies/language.md#json-schema)）。在检出的仓库里按 `schema/<文件>` 校验的 CI 任务，现在读 `schema/8.0/<文件>`。没有文档因此不能编译。
- **登录、JWT、WebMvc、网关与指标模块的公开形状在 RC 之前定下**（R2B-1、R2B-2、R2B-4 至 R2B-6、R2B-10、R2B-11、R2B-12、R2B-16、R2B-24）。受影响的是构造 `TokenSubject`、`JwtTokenVerifier` 或 `JwtTokenConverter`、读取 WebMvc 的 holder、捕获 `RefreshTokenMismatchException`，或提供 `Algorithm` Bean 的应用。
  - **`TokenSubject`：`tenant` 现在排在 `app` 之前。** `TokenSubject("alice", "acme")` 仍能编译，现在表示租户 `acme`，之前表示应用 `acme`。查找每一处传了第二个位置参数的调用，改成按名字写（`TokenSubject(id = "alice", app = "acme")`）；`@JvmOverloads` 去掉后，Java 重载也没有了。`toString()` 不再打印属性，只给出个数。
  - **`JwtSigning` 取代公开构造函数里 java-jwt 的 `Algorithm`。** `JwtTokenVerifier(JwtSigning, skew)` 与 `JwtTokenConverter(idGenerator, JwtSigning, ...)`，用 `JwtSigning.hmac256(secret)`、`hmac384`、`hmac512`。接受 `Algorithm` 的构造函数成为内部的。starter 的 `Algorithm` Bean 现在是同样的属性（`cosec.jwt.secret`、`cosec.jwt.algorithm`）构建出的 `JwtSigning` Bean，运维写的配置不变；声明了自己的 `Algorithm` Bean 来替换它的应用，改为声明 `JwtSigning`。RSA 或 EC 密钥对暂时没有公开入口。
  - **`RefreshTokenMismatchException` 是 `me.ahoo.cosec.spi.token.RefreshTokenMismatchException`。** 改 import。`cosec-jwt` 的校验一侧不再导入 `cosec-login` 的任何东西；转换器仍然导入（`TokenConverter`、`TokenSubject`）。
  - **`me.ahoo.cosec.webmvc.SecurityContextHolder` 改名为 `CoSecSecurityContextHolder`**，以免被当成 Spring Security 的。`requireSecurityContext()` 与 `securityContextOrNull()` 名字不变。
  - **只有 starter 用的类成为内部的：** `IdentityStripFilter`、`IdentityHeadersFilter`、`OrderedCorsWebFilter` 的构造函数，以及这些过滤器的顺序与属性常量。starter 会注册它们。
  - **`MetricsListener.bind(...)` 及其常量移到 `CoSecMeters`**（内部的：由 starter 注册）。`MetricsListener` 保持原名、`cosec.authorization` 与四个标签名。指标的名字和标签不变。
  - **刷新时，刷新令牌也会去掉 `Bearer ` 前缀**，访问令牌原本就这样。Kafka 审计监听器对发送失败每 10 秒记录一行并带上计数；已关闭的发送器会说自己已关闭。
- **社交登录的 state 是 `cosec-spi` 的 `SocialStateStore`，Redis 实现在 `cosec-cocache`；登录开始请求有上限；未知主体被短暂记住**（R2B-3、R2B-23、R2C-18）。
  - **`SocialStateStore` 与 `InMemorySocialStateStore` 移到 `me.ahoo.cosec.spi.social`。** 如果你实现了 `SocialStateStore` 或构造了内存版本，改 import：接口不变（`put` 与 `take`，都是挂起函数）。`RedisSocialStateStore` 现在是 `me.ahoo.cosec.cocache.RedisSocialStateStore`，基于非阻塞客户端和 `GETDEL`，对 starter 内部使用（构造函数接收 `ReactiveStringRedisTemplate`，不再有并行度参数）。key 不变，仍是 `<s>:oauth:binding:<state>`，所以升级时进行中的登录仍有效。
  - **社交登录需要 `cosec-cocache`**（或你自己的 `SocialStateStore` Bean）：两者都没有时启动失败并指明工件（`STA-1`）。`cosec-social` 不再把 `spring-data-redis` 带进你的编译类路径；JustAuth 自己的 state 缓存仍会阻塞，需要 `StringRedisTemplate`，应用使用 Redis 时本来就有。
  - **`authorize` 拒绝无法立即处理的请求。** 每个 `JustAuthProvider` 同时最多有 64 个登录开始请求在进行，使用与登录分开的线程池；多出的一个抛出 `SocialAuthenticationException`，之前它们会无限排队。
  - **`cosec.authorization.membership.unknown-ttl`**（默认 `5s`，最长 `1m`，`0s` 恢复旧行为）：一个实例记住你的 `MembershipLoader` 对某个主体回答过 `Unknown`，所以被移除成员的令牌不再在每个请求上到达 loader。你新增的主体在其他实例上于这段时间结束时被知道，在调用了 `invalidate` 的实例上立即知道。失败的加载从不被记住。
- **控制 API 说得更少，拒绝得更多**（R2C-32、R2C-33、R2C-34）。
  - **`simulate` 与 `explain` 只对能读取*定义*其策略的文档的调用方点名语句。** 租户绑定了平台策略或应用的权限策略时，租户管理员不再看到该策略的语句，决策里是 `"redacted": true`，追踪里只计数。之前由绑定所在的文档决定，而租户管理员能读它。能读取定义文档的调用方（平台运维）看到的与之前相同。
  - **带 `ifMatch` 的区段操作是 `400`。** `putCatalog`、`setLimits`、`setAttributes` 与 `setBoundary` 替换的是没有自己摘要的区段，之前接受 `ifMatch` 却忽略它。变更的 `base`（提议时文档的摘要）保护整份文档。去掉该成员即可。每个操作现在也只接受自己的成员：比如带 `policy` 的 `removePolicy` 是未知成员。
  - **变更在数据面读取的那份文档上检查、分析与批准**：候选写出再读回，而不是操作构造出的那份。预期结果不变：除非格式编解码器没有往返某个成员，两者相同。
- **冷加载会等待已满的编译池；`fail-closed.tenants` 接受 `*`；本地限流器为每份文档的规则设上限**（R2D-3、R2D-4、R2D-5）。文档、属性默认值、存储的值和线上格式都不变。
  - 被已满的编译队列拒绝的冷加载不再立即回答 `503`。它每隔几毫秒重新编译，直到 `cold-load-timeout`（2 秒），然后照旧失败即拒绝。新副本收到的一批租户会排队，而不是被拒绝（[新副本的代价](./deployment.md#what-a-new-replica-costs)）。
  - `cosec.authorization.snapshot.fail-closed.tenants: "*"` 让所有租户在超过 `fail-closed.max-lag` 后失败即拒绝。默认仍为空。
  - `LocalRateLimiter` 最多把四分之一的桶留给一份文档的规则，并在 `cosec.ratelimit.local.refused` 中为它拒绝的每个桶计数。它的构造函数在 `maximumBucketsPerRule` 之后接受 `maximumBucketsPerOwner`：用具名参数调用它的代码无需改动即可编译；针对 beta.4 编译、构造它的代码需要重新编译。

- **`TokenVerifier` Bean 不需要 `cosec.jwt.secret` 就能开启令牌认证**（评审第二轮，`R2D-7`）。受影响：声明了自己的 `TokenVerifier`、又没有 CoSec 签名密钥（`cosec.jwt.enabled=false`）的应用。
  - 以前只有存在签名密钥时令牌才开启：这样的应用，其认证器会拒绝每个带令牌的请求（`token-invalid`），不管它的校验器怎么说，应用只能配一个永远用不上的密钥。现在存在 `TokenVerifier` Bean 或签名密钥时令牌即开启。只有校验器时不签发任何令牌：登录和刷新路由、令牌转换器仍随密钥而来。
  - **只有校验器时需要吊销读取器**：`RevocationReader` Bean，或指向签发方 Redis 的 `cosec-cocache`。没有时启动失败并给出指名它的信息，而不是拒绝每个令牌。`RevocationReader.NONE` 接受已吊销的令牌直到其过期。
  - 如果声明 `TokenVerifier` Bean 是为了别的用途，而其令牌本应继续被拒绝，请移除该 Bean：没有校验器也没有密钥时，带令牌的请求仍是 `token-invalid`。

## 从 8.0.0-beta.3 升级 {#from-8-0-0-beta-3}

- **策略测试工具拒绝可能因错误原因通过的运行**（`PolicyTestKit`，实验性 API；UC-7）。没有 `platform.json` 时 `load` 失败（要在没有它的情况下测试，就在那里写一份空文档），目录里有工具不会读取的文件或目录时也失败。场景要写明 `credential`（`tenant` 与 `id`，或 `"anonymous": true`）以及请求的 `action` 与 `resource`：示例默认值（`acme`、`alice`、`order:read`、`order/1`）不再存在。目录里有租户文档时，在没有文档的租户中判定的场景会失败；有应用文档时，在没有文档的应用中判定的场景也会失败，除非写明 `"withoutDocument": ["tenant"]` 或 `["app"]`（[测试决策](../policies/testing.md#testing-decisions)）。受影响的是依赖默认值或放错位置的文档的场景；`PipelineFixture.load` 按同样的规则读取目录。写明这些字段，把文件移到正确的位置。
- **`cosec-gateway-server` 镜像：没有 JMX agent，actuator 在管理端口，镜像默认值在挂载文件之下依然保留**（`UB-2`、`UB-21`）。受影响的是运行该镜像的人。库的一个预览版上的服务放在另一个预览版的网关镜像之后：见[升级运行中的部署](./upgrading.md#upgrading-a-running-deployment)。
  - 直到 beta.3，启动脚本会在 5555 端口、所有网卡上启动一个不需要认证的 JMX agent。现在不再启动。要附加，在 `JAVA_OPTS` 里设置并绑定到回环地址（[镜像](./deployment.md#the-image)）；Kubernetes base 设置的 `-Dcom.sun.management.jmxremote.host=127.0.0.1` 随之取消。
  - actuator 现在由管理端口 `8081`（`management.server.port`）提供，不再在 `8080` 上：8080 上的 `/actuator/health/*` 是 `404`。探针现在是 8080 上的 `/livez` 和 `/readyz`（同样的分组，由网关自己的端口提供，不经 CoSec 决策）；`/actuator/health` 和 `/actuator/prometheus` 在 8081 上。把你自己的探针或健康检查改为 `/livez` 和 `/readyz`（镜像的 `HEALTHCHECK` 和 `k8s/` 清单已经改了），如果要抓取 8081，只把它发布给监控网络。
  - 镜像带有 `cosec-micrometer` 和 Prometheus 注册表（Micrometer 1.17.2，来自 Spring Boot 的依赖管理）：`cosec.authorization`、`cosec.snapshot.*` 等在 `/actuator/prometheus` 上。
  - 镜像的默认值现在在它的 jar 里，你挂载的 `config/application.yaml` 被追加读取并逐 key 覆盖；以前它会整体替换默认值（Kafka 审计、登录和 ip2region 会重新开启）。设置了所需内容的文件不受影响；漏掉默认值的文件现在会得到它们。`routes` 这样的列表仍然整体替换。
  - `k8s/base`：演示用的 CORS 来源改为占位符（`https://app.example.com`，不再是 `*`；`max-age` 的单位是秒），演示路由和发现列表已标注，容器有了 `management` 端口，production 的 NetworkPolicy 只放行来自 `monitoring` 命名空间的该端口。
- **CoSec 无法读取值的 `cosec.*` 开关使启动失败**（`UB-1`）。直到 beta.3，`cosec.enabled=1`、`on`、空字符串或 ` true ` 这样的值对 Spring 的绑定器是 true，对装配 bean 的条件却是"关闭"，于是 CoSec 或该功能被悄悄关闭；空的 `cosec.pipeline.mode` 不装配任何管道，Spring Cloud Gateway 宿主会成为开放代理。`cosec.authorization.shadow.enabled=off`（或 `0`）还会让影子评估保持开启。现在 starter 在创建任何 bean 之前拒绝每个不是 `true` 或 `false` 的开关（不区分大小写，两侧不能有空格；`cosec.pipeline.mode` 接受 `enforce` 或 `gateway`，`cosec.api-key.store` 接受 `memory` 或 `redis`），信息指明 key、读到的值和可接受的值（[开关无法读取导致启动失败](./troubleshooting.md#startup-fails-on-an-unreadable-switch)）。受影响的是用其他写法写开关的配置、环境变量或 Helm 值。写成 `true` 或 `false`，或删掉这个 key 取默认值。写成 `false` 的开关仍会关闭它的功能。
- **`cosec.authorization` 计时器新增 `problem` 标签，所有序列的标签 key 相同**（`UB-8`）。新标签是客户端被告知的问题的 slug（`unauthorized`、`token-expired`、`forbidden` 等）或 `none`，因此可以区分 `401` 与 `403`，以及不同的令牌问题（[指标](./observability.md#metrics)）。原先只有 `indeterminate` 序列才有的 `cause` 标签现在出现在每个序列上，`indeterminate` 之外为 `none`：Prometheus 注册表拒绝同名但标签 key 不同的计量器。受影响的是按"没有 `cause`"选取序列的查询（`cause=""`），它现在应选 `cause="none"`；对新标签求和得到原来的序列。
- **“什么做出了决定”只有一套词：指标、审计和决策事件的认证方式**（UD-7、UD-11）。直到 beta.3，每个输出各自命名决定，对显式拒绝的告警还会计入并非被 DENY 语句拒绝的请求。现在每个输出都使用决策事件的词（[每个输出中的“什么做出了决定”](./observability.md#what-decided-in-every-output)）：

  | 输出 | 之前（beta.3） | 现在 |
  |---|---|---|
  | `cosec.authorization` 的 `result` | `allow` | `permit` |
  | | DENY 语句、作用域拒绝和边界拒绝都是 `explicit_deny` | `explicit_deny`（仅 DENY 语句）、`scope_rejected`、`boundary` |
  | | `bad_request` | `invalid_request` |
  | 审计 `authorization.decision` | `ALLOW` | `PERMIT` |
  | | DENY 语句、作用域拒绝、边界拒绝和 `400` 都是 `EXPLICIT_DENY` | `EXPLICIT_DENY`、`SCOPE_REJECTED`、`BOUNDARY`、`INVALID_REQUEST` |
  | 审计 `authorization.reasonCode` | `ALLOW`；`400` 为 `EXPLICIT_DENY`；尚未生效的令牌为 `TOKEN_INVALID` | `PERMIT`；`INVALID_REQUEST`；`TOKEN_NOT_YET_VALID` |
  | 决策事件 `subject.method` | 每个令牌都是 `token`，API key 为 `apikey` | `jwt`（CoSec 自己的令牌）、`api-key`、其他校验器的名字（默认 `token`）：与 `CoSec-Identity` 一致 |

  计时器的这项变化与上一条的 `problem` 标签和每个序列上的 `cause` 一起发生：仪表盘只需为两者迁移一次。受影响的是：基于 `result="allow"`、`explicit_deny` 计数（现在计入的请求更少：加上 `scope_rejected` 和 `boundary` 才是原来的总数）或 `bad_request` 的仪表盘和告警；匹配 `ALLOW`、或期望其他三种拒绝为 `EXPLICIT_DENY` 的审计消费者（日志、Kafka 记录）；对 CoSec 的令牌匹配 `subject.method` 为 `token`、或匹配 `apikey` 的决策监听器。`ApiKeyAuthenticator.METHOD` 为 `api-key`。`AuditDecision` 和 `AuditReasonCode`（`cosec-api`）去掉了 `ALLOW`：请针对这个版本重新编译。
- **新的日志行**（无需改动）：一行 INFO 摘要说明 CoSec 被配置成什么样，`cosec.enabled=false` 时一条 WARN（[启动摘要](./observability.md#startup-summary)）；平台文档缺失期间每隔 `lag-warn` 重复的一条 WARN，指明读取的 key、key 前缀和 Redis（[实例未就绪](./troubleshooting.md#instance-is-not-ready)）；Spring Cloud Gateway 宿主上有自己控制器或函数式路由、而 CoSec 不决策它们时的一条 WARN（[网关自己的处理器](../integration/web.md#the-gateways-own-handlers)）；以及客户端地址那一行现在会说明默认 `max-trusted-index` 的风险。
- **被已映射路由绕开的 `http:` 语句会被报告**（UC-1，无需改动）。平台的 DENY 或限流规则写在 `http:*` 上时，路径一旦被路由映射映射就不再受它约束。配置了路由映射的宿主现在对它安装的每个含这类语句的文档修订记一条 WARN，并列出这些语句（[CoSec 会警告什么](../scenarios/route-maps.md#what-cosec-warns-about)）；没能执行的审查计入 `cosec.snapshot.housekeeping.refused` 的 `task="install-review"`。控制面在新增的可选属性 `cosec.control.analysis.route-maps` 给出各服务的路由映射时，为候选文档含这类语句的变更加一条 `ROUTES` 警告（[校验策略](../policies/testing.md#validating-policies)）。
- **`cosec-testkit` 的 `IdentityHeader`**（#1656，实验性，无需改动）用 CoSec 自己的编码器写出网关转发的 `CoSec-Identity` 头，供网关之后的服务的测试与本地客户端使用：`of`、`forSubject`、`withToken`（[用真实管道测试代码](../policies/testing.md#testing-your-code-against-a-real-pipeline)）。
- **安全：对已结束请求的检查被拒绝**（`PIP-26`）。直到 beta.3，在请求之后保留的 `SecurityContext`（存在字段、缓存或发给消费者的消息里）仍能通过 `AuthorizationChecker` 放行，依据的是创建它时的成员关系，即使已经登出、令牌已被吊销或角色已被移除。现在绑定请求的适配器在请求完成时结束它，之后对它的每次检查都为 `false`（`decide` 返回 `Deny(null)`）。进行中的请求照旧作答，无论持续多久：流式或服务器发送事件（SSE）响应体、异步 Servlet 请求。受影响的是保留上下文并在响应写出之后用它检查的代码。改为在请求内检查；之后运行的工作（消费者、定时作业）自己携带授权（[代码中的权限检查](../integration/checks.md#how-a-check-relates-to-the-request-pipeline)）。你自己的传输交给 `Pipeline.evaluate` 的请求以及测试工具包的请求，其上下文只在你结束它时结束：关闭 `Outcome`，或调用 `PipelineFixture.end` / `PermittedRequest.end`（见下文）。
- **安全：安全上下文的读取入口只返回管道构造的上下文**（`PIP-27`）。`exchange.getSecurityContext()`、`ContextView.getSecurityContextOrEmpty()` / `getSecurityContext()`、`request.getSecurityContext()`、`SecurityContext` 处理器参数和 `SecurityContextHolder` 以前返回 `SecurityContext.KEY` 下的任何对象，所以应用的过滤器或某个库自己写入的上下文会被当作已认证的调用者。现在其他任何东西都读作没有上下文：`null`，或未绑定上下文时的那种失败。受影响的是在这个 key 下绑定自己构造的 `SecurityContext`（mock、该接口的实现）的代码，尤其是测试代码。测试中使用管道构造的上下文，并让适配器的测试入口把它放到位（[测试你的应用](../policies/testing.md#testing-your-application)）：处理器测试运行在 CoSec 自己的过滤器之后，即 `CoSecTestBindings.filter(fixture.pipeline)`（`cosec-webflux`、`cosec-webmvc`），带上 fixture 主体的 `Authorization`；单元测试用 `CoSecTestBindings.withSecurityContext` 或 `bind` 绑定 `PermittedRequest.forSubject(...)`（或 `withIdentity`、`withToken`）。自己运行管道的过滤器绑定 `outcome.security!!.securityContext`。

  这样的测试不一定会明显地失败。它绑定的上下文读作没有上下文，所以被测代码看到的是缺失或匿名的调用者（`401`、被拒绝的处理器）；而当你的代码放行缺失的上下文时，例如把它当作“没有自己请求的命令”，就会给出测试没有预料到的放行：期望被拒绝的测试看到的是允许。在测试里搜索 `SecurityContext.KEY`、`mockk<SecurityContext>` 以及每个实现了 `SecurityContext` 的类。
- **破坏性变更：三种从未按字面生效的文档写法不再能编译**（`DOC-9`、`DOC-10`、`DOC-11`）。每一条拒绝的文档，其语句本来就没有按所写的方式生效：
  - 在漏掉一种写法就会放过请求的地方，对 `eq` 或 `prefix` 加 `ignoreCase`、而字面量含有 ASCII 以外的字母（`Ärzte`）：DENY 语句或限流规则、`not` 之下的 ALLOW、守护 DENY 的策略条件。折叠只限 ASCII，所以 `ärzte` 会从 DENY 漏过。改为在不带 `ignoreCase` 的 `in` 里写出每种写法（[大小写折叠](../policies/language.md#case-folding)）。在 ALLOW 里仍能编译，但会给出警告。
  - 条件或限流 `key` 里的 `resource.attributes.<name>`：没有任何地方设置它，对它的 DENY 什么也没拒绝。把事实写进资源 id，用 `resource.var.<name>` 读取（[对象级规则](../integration/checks.md#object-level-rules)）。设置 `request.resource.attributes` 的测试工具场景也会被拒绝。
  - 以 `(` 开头的 `subject.id` 字面量（`{"not": {"eq": ["subject.id", "(0)"]}}` 匹配每一个匿名请求），以及主体 id 以 `(` 开头的主体绑定（它从不生效）。匿名请求写 `{"eq": ["subject.authenticated", false]}`。

  受影响的是存有这类文档的部署。升级之后，实例把这样的文档装为失败，每个读取它的请求都失败即拒绝（`503`），所以要先找出来。在 beta.3 仍在运行时，读取每个头修订（`GET <控制面前缀>/documents/{key}`，[控制 API](../policies/testing.md#the-control-plane)），或取你发布所用的文件，搜索它们：`grep -nE 'resource\.attributes\.|"subject\.id" *, *"\(|"principal" *: *"\(' <文件>` 能找出第二、三种写法；第一种用 `grep -n '"ignoreCase": *true' <文件>`，再看旁边有没有 ASCII 以外的字母（`grep -nP '[^\x00-\x7F]'`）。也可以用本版本的 `PolicyTestKit.load` 加载这些文件，它像实例一样编译每个文档，并给出每个错误的路径。`cosec-migrate convert` 现在也拒绝 7.x 数据里的同样写法：基于 7.x 资源属性的 ALLOW 是一条永远不会成立的语句，需要你删掉。
- **破坏性变更：`:apply` 的 `REPLACE` 检查文件读取时的修订。** 直到 beta.3，文件头的 `revision` 必填却被忽略，`REPLACE` 会删除头修订中文件没有的一切，包括文件读取之后才发布的内容。现在 `revision` 不是头修订的文件返回 `409`。受影响的是从 git 应用文件、`revision` 固定或过时的作业：发送作业上次读取的修订（`GET /documents/{key}` 的 `revision`），并合并之后的变化（[变更](../policies/testing.md#changes)）。尚未发布的文档不比较。`MERGE` 不变，它的变更现在会在 `WARNING` 中点名因文件没有列出而保留的条目。
- **会把发布者锁在控制面之外的变更需要确认。** 对平台文档、平台租户的文档或控制应用的文档的变更，如果会让其操作者到达不了控制面，就带有 `V4` 警告，它的发布返回 `422`，除非请求体写明 `"lockout": true`（[发布与回滚](../policies/testing.md#publishing-and-rolling-back)）。受影响的是有意发布这类变更的自动化：加上这一确认。被锁在外面之后的出路是新增的 `cosec-migrate restore`，而不是清空 Redis（[从被锁在外面中恢复](./storage.md#recovering-from-a-lock-out)）。
- **已修复：丢失的失效消息由其后的第一轮装上，即使碰到在途的加载。** beta.3 的发布说明把它列为已知问题：重新确认的一轮碰到某文档已在途的加载时，把那次加载的读当作自己的确认，而那次读可能早于变化，所以失效消息丢失的变化最多要两个 `revalidate-interval` 才装上，其间的决策使用上一修订。现在一轮只加入在它之后开始的加载，否则再等一次加载（`SNP-6`，[存储](./storage.md#how-instances-read-documents)）。无需任何改动；一轮碰到某文档的加载时，Redis 可能多收到一次该文档的条件读。
- **`cosec-gateway-server` 镜像关闭了 ip2region。** 它的 `config/application.yaml` 现在设置 `cosec.ip2region.enabled: false`，与 Kubernetes 的 base 一致；直到 beta.3，镜像一直开启它。读取 `ext.ip2region.*` 的文档在它上面不再能编译：升级镜像之前设置 `COSEC_IP2REGION_ENABLED=true`。它的 Docker `HEALTHCHECK` 现在探测 `/actuator/health/liveness`，因此等待平台文档的网关不再被报告为不健康。
- **`@ahoo-wang/cosec-react`：`logout` 在服务端结束会话。** `useSecurity` 和 `SecurityProvider` 新增 `tokenManager` 选项（同一个 `TokenStorage` 的 `JwtTokenManager`，带 `tokenRevoker`）和 `logout()`：它调用服务端的登出，然后删除这里的令牌对（[构建客户端](../integration/clients.md#logout-and-switching)）。`signOut` 不变：它只删除这里的令牌对，会话在服务端仍可刷新，直到其刷新令牌过期。没有任何破坏性变化；把用户的"退出"改为调用 `logout`。
- **安全：构造 `TokenAuthenticator` 的人要指明吊销读取器**（`IDN-27`）。直到 beta.3，`TokenAuthenticator(verifier)` 可以编译，并从 `RevocationReader.NONE` 读取吊销：应用自己构造的认证器（自定义的 `Authenticator` Bean、Spring 之外的管道）在登出或 `revokeSubject` 之后仍接受每个被吊销的令牌，直到它过期，而且没有任何警告。现在这个参数没有默认值，`PermittedRequest.withToken`（`cosec-testkit`）的也没有。受影响的是不传读取器就构造二者的代码：它不再能编译。传入你的登出写入的那个 `TokenStore` 的读取器（starter 的 `RevocationReader` Bean），或者在从不吊销的地方（例如大多数测试）传 `RevocationReader.NONE`。二进制兼容；starter 不变。
- **`RefreshTokenAuthentication(verifier, revocation, families)` 要指明 `MembershipRefresh`**（`IDN-27`）。这个工厂函数默认使用 `MembershipRefresh.CLAIMS`，它保留旧令牌声明的角色，而 `MembershipRefresh` 的 KDoc 说的恰好相反。现在它的 KDoc 说明实际行为，工厂函数也没有默认值。受影响的是用三个参数调用工厂函数的代码：它不再能编译。加上 `MembershipRefresh.CLAIMS` 以保持现在的行为（移除的角色在下次登录或 `revokeSubject` 之后生效），或者传入重新查询用户的 `MembershipRefresh`。除非你声明 `MembershipRefresh` Bean，starter 的刷新路由仍使用 `CLAIMS`。`cosec-login` 中的合成方法 `RefreshTokenAuthentication$default` 已移除：请针对这个版本重新编译。
- **安全：`JwtTokenVerifier` 和 `JwtTokenConverter` 拒绝 `Algorithm.none()`**（`IDN-28`）。java-jwt 的 `none` 接受任何签名为空的令牌，所以基于它的校验器会把任何人都能写出的令牌当作 CoSec 自己的令牌，包括应急访问（break-glass）。现在两个构造函数都抛出 `IllegalArgumentException`。受影响的是基于 `Algorithm.none()` 构造二者的代码，尤其是测试代码。请传入签名算法，例如 `Algorithm.HMAC256(secret)`。
- **`CoCacheTokenStore.revoke` 拒绝把令牌当作令牌 id 传入。** `TokenStore.revoke(tokenId, ttl)` 接收令牌的 id（`VerifiedToken.id`，即 `jti`）；把访问令牌传到这里的登出什么也没吊销，还把令牌写成了 Redis 的 key。现在形如令牌的值（JOSE 紧凑序列化，带或不带 `Bearer `）会抛出 `IllegalArgumentException`，消息指向 `TokenRevoker`；CoSec 或其他签发方给出的令牌 id 都不会被拒绝。受影响的是基于 `TokenStore` 编写的登出，它从未生效过。请调用 `TokenRevoker.revoke(accessToken)`。
- **用 `Pipeline.builder` 构建的管道会计数限流**（UA-10）。直到 beta.3，没有调用 `rateLimiter(...)` 构建的管道没有限流器：带 `strictFailure` 的规则对它覆盖的每个请求都返回 `503`，其他规则都被跳过。现在它在自己的 `LocalRateLimiter` 中计数，与 starter 的默认一样，超出规则限制的请求为 `429`。`PipelineFixture.of` 和 `load` 也这样计数，并接受 `rateLimiter` 参数，所以测试能看到 `429`（传入使用测试自己时钟的 `LocalRateLimiter` 可以跨越窗口）。受影响的是 Spring 之外的管道以及测试，其文档带有限流规则：现在它们会被限流。要对整个服务限流，给 `rateLimiter(...)` 一个跨实例共享的限流器。starter 不变。`PipelineFixture.of` 和 `load` 增加了一个参数：请针对这个版本重新编译测试。
- **你自己的传输可以结束它的请求**（`PIP-26`）。`Outcome` 实现了 `AutoCloseable`：在响应完成时关闭它（`pipeline.evaluate(facts).use { … }`），或者在响应比处理器活得更久时从响应的完成回调里关闭，之后对其上下文的每次检查都为 `false`（[管道在适配器之外](../architecture/extending.md#pipeline-outside-the-adapters)）。测试中用 `PipelineFixture.end(context)` 和 `PermittedRequest.end(context)`。没有破坏性变化：不关闭它的传输行为照旧。
- **因调用方式而被拒绝的检查会被记录**（UA-5）。管道没有构造的上下文、请求结束之后的检查以及格式错误的动作字符串仍然返回 `false`（`Deny(null)`），现在每种每分钟记录一次 `WARN`，指出请求（[故障排查](./troubleshooting.md#a-check-answers-false)）。无需任何改动。
- **在每个栈上，当且仅当 CoSec 认证了请求时，请求的 `Principal` 才是 CoSec 的**（`PIP-28`）。直到 beta.3，WebFlux 和网关把匿名主体 `(0)` 作为 `exchange.getPrincipal()` 和 `java.security.Principal` 处理器参数交给匿名调用方，所以把"存在 principal"当作"已登录"的代码会把所有匿名调用方当作同一个用户；Spring MVC 则完全不设置。现在：WebFlux 和网关不再为匿名请求设置它，受影响的是期望匿名调用方也有 principal 的代码（现在以空完成的 `exchange.principal.map { … }`、非空的 `Principal` 参数）。Spring MVC 为已登录的请求设置 CoSec 的主体：`getUserPrincipal()` 是它，`getRemoteUser()` 是它的 id，`Principal` 处理器参数不再为 null。它替换容器或前面的过滤器（Spring Security）已设置的 principal；在 CoSec 视为匿名的请求上，那个 principal 保持原样。对 CoSec 认证了的请求，`isUserInRole` 为 `false`，`getAuthType()` 为 `null`。受影响的是读取 `getUserPrincipal()` 或 `getRemoteUser()` 的 Servlet 应用（已登录的请求现在得到 CoSec 的调用方），以及在 CoSec 后面询问 `isUserInRole` 的应用（改为通过检查询问 CoSec）。从 `SecurityContext` 读取调用方（`principal.authenticated`、`principal.id`），它在三者上相同（[Web](../integration/web.md)）。
- **已修复：时钟偏快的签发方签发的令牌被接受**（#1666，`IDN-29`）。直到 beta.3，`JwtTokenVerifier` 不给时钟任何余量：`iat` 只要比校验方时钟晚几毫秒，令牌就是 `401 token-invalid`，所以签发方与网关不共用时钟的部署会在某一秒的最后几毫秒看到偶发的 `401`。现在令牌的 `iat` 和 `nbf` 可以比本实例时钟晚至多 `cosec.authentication.revocation.skew`（5 秒）；`exp` 仍然严格。超出余量的令牌对客户端仍是 `token-invalid`，决策事件将其记为 `TOKEN_NOT_YET_VALID`（`AuthnResult.RejectReason` 的新成员，这是一个会增长的集合，对它的 `when` 本应已有 `else`）；日志写着 `Token refused as not valid yet`。`JwtTokenVerifier(algorithm, issuerSkew)` 接受这个余量，starter 传入 `cosec.authentication.revocation.skew`；你自己构造的校验器默认 5 秒，如果你改过 `TokenAuthenticator` 的 `revocationSkew`，请传入同一个值。没有破坏性变化。
- **宿主在启动时记录的内容。** 不会有什么坏掉，但运维人员读到的日志变了。CoSec 不读取的 `cosec.*` 属性会在创建任何 Bean 之前让启动失败，所以带有已移除键的配置会先听到每一个。令牌按 `RevocationReader.NONE` 校验时，宿主记录一条 `WARN`，说明被吊销的令牌在过期之前仍会被接受。令牌存储缺失时，一条启动信息会列出每个缺失的存储，而不只是第一个。
- **测试应用**（#1679，`PIP-29`，无需改动）。`PermittedRequest.forSubject(subject, tenant, app, space, roles)` 在没有令牌、没有文档的情况下构造已登录调用方的上下文，与 `PipelineFixture` 构造的相同；`PermittedRequest.withIdentity(header)` 构造网关后面的服务读取的上下文。`CoSecTestBindings`（`cosec-webflux`、`cosec-webmvc`，包 `testing`）在测试管道之上运行 CoSec 自己的过滤器，或者把工具包的上下文绑定到 Reactor 上下文、exchange、请求或 `SecurityContextHolder`，并拒绝手工构造的上下文。`PipelineFixture.authorization(principal)` 是 fixture 主体用于测试自建请求的 `Authorization`（[测试你的应用](../policies/testing.md#testing-your-application)）。

## 从 8.0.0-beta.2 升级 {#from-8-0-0-beta-2}

- **安全：吊销表最多把“未吊销”保留 30 秒。** 直到 8.0.0-beta.2，`RevokedTokenCache` 和 `RevokedSubjectCache` 的 `absent-ttl` 没有锁定，所以 `cocache.caches.RevokedTokenCache.absent-ttl`、`cocache.defaults.store.absent-ttl` 或定制器都能延长它，错过失效消息的实例会在那么长时间里接受已吊销的令牌。现在它由 CoSec 决定，为 30 秒：为任一吊销表设置 `cocache.caches.[name].absent-ttl` 会使启动失败（请删除它），全局默认值和定制器也不再影响它。
- **安全：请求用应用的 `TokenVerifier` 校验。** 直到 8.0.0-beta.2，starter 的管道一直用 CoSec 自己的 `JwtTokenVerifier` 校验请求的令牌，即使应用声明了 `TokenVerifier` Bean（刷新和登出确实用了它）。现在请求、刷新和登出用同一个校验器（`IDN-25`）。变化取决于你的校验器：
  - 校验其他签发者令牌的校验器，现在在请求上也像一直以来在刷新时一样生效。
  - 包装 CoSec 自己校验器的校验器（`JwtTokenVerifier` 加上你自己的检查）让它的令牌在每个请求上都不再是 CoSec 的：主体的凭证是 `token` 而不是 `jwt`，没有会话，因此不能从该请求的会话[切换租户](../integration/authentication.md#switching-tenant-and-app)，网关也不转发会话，并且永远没有 break-glass。直到 beta.2，它的请求三者都有。除非这正是你想要的，否则不要声明这样的校验器。

  没有自己 `TokenVerifier` 的应用不受影响。
- **安全：提供的刷新路由使用你的 `RefreshTokenAuthentication`。** 直到 8.0.0-beta.2，该路由自己构建刷新认证，只使用 `MembershipRefresh<RefreshTokenCredentials>` Bean，所以声明在你的 `RefreshTokenAuthentication` Bean 中的钩子（例如拒绝已停用的用户）对它不起作用。现在由你的 Bean 提供这个路由。基于你自己凭证类的 Bean 在设置了 `cosec.authentication.login.refresh.path` 时会使启动失败：请从你自己的路由提供那个刷新并取消该路径，或者基于 `RefreshTokenCredentials` 声明它。
- **会话存储失败或无法执行某个调用。** `TokenRevoker.revokeSubject` 中存储失败（例如 Redis 宕机）时，现在抛出 `SessionStoreUnavailableException`，与 `revoke` 一直以来的做法相同：返回 `503` 并再次吊销。原来在 `revokeSubject` 外捕获存储自身异常的代码，改为捕获 `SessionStoreUnavailableException`。会话存储因为根本无法执行某个调用而抛出 `UnsupportedOperationException` 时，现在在每次会话存储调用（登录、刷新、切换租户、登出和两种吊销）中都原样抛出，而以前会变成 `SessionStoreUnavailableException`：这是配置问题，不是故障。在 starter 提供的刷新和登出路由上，这意味着 `500`，以前是 `503`。

## 从 8.0.0-beta.1 升级 {#from-8-0-0-beta-1}

- **升级前：给每个限制了命令的数据面 Redis 用户加上 `+evalsha_ro +eval_ro`。** 不运行控制面的实例现在以只读方式读取文档和候选，即 `EVALSHA_RO`（Redis 7.0 或更高版本，CoCache 8 本已要求）。用一个列出命令的用户（例如 beta.1 的[谁可以写哪些 key](./storage.md#who-may-write-which-keys) 给出的那个：先 `-@all`，再在其他命令中给出 `+evalsha +eval`），升级后的实例会在启动时失败：`Read-only store of Keyspace(...): Redis denied the command [evalsha_ro]: allow [+evalsha_ro] ...`。请先加上这两个命令；没有命令列表的用户（`+@all`）不需要任何改动。把文档和候选收窄为读权限（`%R~<s>:snapshot:* %R~<s>:candidate:*`）是可选的，页面上给出了写法。运行控制面的进程不变：它仍然写入它们。
- **`cosec.control.enabled` 可以在实例上关闭控制面**（新增：无需迁移；默认值 `true` 不改变任何东西）。两种角色共用一个带 `cosec-control` 镜像的部署，在数据面角色上设置 `COSEC_CONTROL_ENABLED=false`：它不运行控制面，以只读方式读取文档和候选，可以使用[谁可以写哪些 key](./storage.md#who-may-write-which-keys) 中的只读用户。`true` 或 `false` 以外的值会使启动失败。
- **starter 可以提供刷新和登出路由**（新增：在设置路径之前什么都不变）。`cosec.authentication.login.refresh.path` 和 `logout.path` 在 Spring MVC 和 WebFlux 上提供它们，状态码和响应体见[构建客户端](../integration/clients.md#what-your-application-serves)。你自己写的路由照常工作：不设置路径就不提供任何路由。要迁移到提供的路由，设置客户端调用的路径，删除你的处理器及其异常映射，并保留允许匿名 `POST` 它的语句。
- **会话存储的故障以 `SessionStoreUnavailableException`（`cosec-login`）离开 `TokenFamilies` 和 `StoreTokenRevoker`**，存储自身的异常作为其 cause，从而把无法应答的存储（`503`）与错误（`500`）区分开。在刷新、切换或登出周围捕获存储自身异常类型的代码，改为捕获它。`StoreTokenRevoker.revokeSubject` 不变。
- **`MembershipRefresh` 以 `TokenVerificationException` 拒绝**（成员关系已不存在时用 `TokenRevokedException`），这是 `401`；其他任何异常都是错误，在提供的路由上是 `500`，客户端保留令牌对。以其他异常（例如 `IllegalStateException`）拒绝的钩子，改为抛出 `TokenRevokedException`。
- **快照源让每个文档的键经由各自的运行器处理**（无需迁移，API 与属性都不变；决策不变）。运维能看到的不同：
  - 在文档加载期间发出、在该加载结束之后才被取出的请求，得到已安装的文档，算作命中；beta.1 把它并入那次加载，算作冷加载：文档相同，但 `cosec.snapshot.loads{result=cold}` 少计一次。
  - `close` 结束了某次加载之后才答复"不存在"的存储读取不再上报：已关闭的实例不会因此记录 `Snapshot document [<doc>] disappeared from the store; failing closed.`，也不计入 `cosec.snapshot.lost`。在存储读取超时（30 秒）时被放弃、之后才答复"不存在"的读取，由该文档的下一次加载上报，而不是立即上报。
  - 不存在文档的 id 的上限不再有空档（[实例如何读取文档](./storage.md#how-instances-read-documents)）：驱逐会跳过正在加载的条目，当这次加载发现文档仍不存在时，该条目现在自己请求回到上限。beta.1 中，族会一直高于这个上限，直到之后某次安装。
  - 平台文档变化、所有文档重新编译时，有在途加载的文档可能多加载一次：每个文档至多多读一次存储。
  - 每个持有的文档多占约 64 字节堆内存，每次冷加载多分配约 1.2 KB。
- **快照源有了指标**（新增：无需迁移）。有 `cosec-micrometer` 和 `MeterRegistry` 时，数据面的快照源现在注册 `cosec.snapshot.*` 指标：新增 80 个序列，与文档数量无关，因为没有任何标签携带应用或租户 id。找到已安装文档的请求不计数：只计请求不得不等待的加载。[`cosec.metrics.enabled`](./configuration.md#feature-switches)`=false` 会连同 CoSec 的其他指标一起关闭它们。各指标及告警条件见[快照指标](./observability.md#snapshot-metrics)。
- **快照源的维护线程拒绝的任务被丢弃并计数**（`SNP-19`；无需迁移）。该线程写快照源的日志、执行驱逐，队列容纳 4,096 个任务。队列满时，beta.1 在提交任务的线程（常常是请求线程）上执行被拒绝的任务，于是慢的日志 appender 会拖慢请求。现在任务被丢弃并计入 `cosec.snapshot.housekeeping.refused{task}`，该线程最多每 10 秒记录一条 `WARN`：`Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped …`，列出每种任务最后被丢弃的那条日志，不带堆栈；关闭中的实例把剩下的写出一次。抛出异常的指标在同一行里计数，而不单独记录日志。无需任何操作，除非出现这条摘要：那时改用异步日志 appender（[快照日志缺失](./troubleshooting.md#snapshot-log-lines-are-missing)），并对该计数器的任何上升告警。

## 从 8.0.0-alpha.4 升级 {#from-8-0-0-alpha-4}

**如果你发布过任一预览版的 `cosec-migrate convert` 写出的文档，请检查其中的 ALLOW 语句**（下面第一条）：转换得到的 7.x"所有动作"授予了控制面。
8.0.0-alpha.4 是预览版，没有兼容性承诺。**在此版本之前签发的令牌都没有声明应用，配置了应用的服务会拒绝它们**（见下）：请安排重新登录，或等待令牌过期。以 format 2 存储的 API key 会失败，需要重新发放。**升级前请关闭或撤回打开的影子窗口**：8.0.0-alpha.4 暂存的候选在文档旁另写了头部，会被当作无法读取而拒绝，于是它的窗口不被比较。

- **修订索引的条目增加了 `parent`，每个文档第一次读取时会重写 alpha 写下的索引**（`CTL-35`）。`<s>:control:revisions:<doc>` 的每个条目带着其文档所声明的父修订的摘要；只有当最后一个条目是头、且每个条目都指向它下面那个条目时，才会返回文档的历史。此前写下的索引没有 `parent`：每个文档第一次读取（一次控制 API 调用，或维护任务）会沿头的链走一遍，解码每条保留的记录，并用它重写条目；不需要迁移，也不会丢失任何东西。`RevisionEntry` 的构造函数多了第四个参数 `parent`（默认 `null`），供自己构造它的代码使用。同一次读取还会修复头退回、修订号被重用的情形——此前这会读到旧分支的文档；见[修订、历史与回滚](./storage.md#revisions-history-and-rollback)。
- **安全：租户切换不再从已吊销或已结束的会话签发。** 从 8.0.0-alpha.1 到 8.0.0-alpha.4，切换自己不读取吊销记录：吊销存储无法读取时（请求路径此时把它读作"未吊销"），已登出的令牌，或被吊销主体的令牌，可以被切换成一个没有任何吊销记录指名的新令牌。会话是在 8.0.0-alpha.4 之后才有的；在这次修改之前，切换还会从一个自身会话已被吊销的令牌开始一个有效的会话：会话被刷新之后再次出示的令牌对吊销（`IDN-16`，其访问令牌在 `cosec.jwt.token-validity.access`（10 分钟）之内仍然有效），或被登出吊销。复制了令牌对并抢先刷新的人因此可以持有一个会话，直到用户的会话本该结束时为止，最长 `cosec.jwt.session.lifetime`。这一点不在任何预览版中。现在切换只从有效的会话签发，并严格读取吊销记录，与刷新共用同一个检查（`IDN-22`、`IDN-14`）：从已失效的会话切换回答 `token-revoked` 或 `token-expired`，存储无法读取时回答 503。预览版的切换在吊销存储故障期间签发的令牌，不会被它躲过的那条吊销记录吊销；如果可能存在这样的令牌，请[吊销该主体](../integration/authentication.md#revoking-every-token-of-a-principal)。
- **安全：转换得到的 7.x"所有动作"不再授予控制面。** 从 8.0.0-alpha.1 到 8.0.0-alpha.4，`convert` 把针对所有 7.x 动作的 ALLOW 写成
  `"action": "*"`，它还匹配每一个控制面动作（`cosec:*`）和代码中检查的每一个动作。像"登录用户可以做任何事"这样的全局策略经转换后，给了每个已认证主体
  对其所在租户文档的全部控制面动作，`(platform)` 的主体则得到对平台文档和应用文档的全部控制面动作。可附加的策略对列出它的每个令牌也是如此。这样的主体
  可以在该文档中写入和授权，持有同样授权的另一个主体可以批准。`convert` 现在写 `"action": "http:*"`
  （[所有动作即所有 HTTP 请求](./upgrading.md#every-action-is-every-http-request)）；针对所有动作的 DENY 仍是 `*`。如果你发布过转换得到的文档：
  1. 找出每个文档（`platform`、`app:<id>`、`tenant:<id>`）中 `*` 上的 ALLOW 语句。管理员通过控制 API 读取 head
     （[修订、历史与回滚](./storage.md#revisions-history-and-rollback)）：

     ```bash
     curl -s "$CONTROL/documents/platform" -H "$AUTH" \
       | jq -r '.policies[] | select(any(.statements[]; .effect == "allow" and ([.action] | flatten | index("*")))) | .id'
     ```

     `convert` 写出的那些在这次转换的 `platform.json`、`apps/` 和 `tenants/` 中，它的 `provenance.json` 给出每一条对应的 7.x 策略。升级之后
     你自己写的 `*` 上的语句由你决定是否保留。
  2. 通过控制面把每一条转换得到的语句改为 `http:*`：提交一个变更，以 `"action": "http:*"` 重新写入该策略。如果某个文档自 `publish` 以来没有变过，
     也可以对它 `apply` 新一次 `convert` 写出的文件，它提出的变更正好是这些语句。
- **租户和应用 id 不含 `/`**（`SCP-13`）。8.0 预览版接受 `acme/west` 这样的租户 id 和 `a/b` 这样的应用 id；本版本在每个入口都拒绝它们，
  所以存储过这种 id 的预览版部署必须在升级前删除它们的记录。在预览版上用 `GET /documents` 查找：`tenant:` 或 `app:` 之后含 `/` 的键。
  预览版自己的控制面无法删除它们，因为它同样拒绝这种 id。先停止控制面，然后对每个这样的文档 `<doc>`（`tenant:acme/west`）删除 `<s>` 下的这些
  Redis 键（[Redis 键](./storage.md#redis-keys)）：
  - `<s>:snapshot:<doc>`（头）和 `<s>:candidate:<doc>`（暂存的候选）；
  - `<s>:control:revisions:<doc>` 以及每个 `<s>:control:revision:<doc>:*`（修订索引与修订记录）；
  - 每个 `<s>:control:refs:<doc>:*`（指向它的策略的反向引用）；
  - `<s>:control:stage:<doc>` 和 `<s>:control:lease:reclaim:<doc>`；
  - 每个 `"key"` 为 `<doc>` 的 `<s>:control:change:<id>`，以及 `<s>:control:list:open-changes` 中它的 id；
  - `<s>:control:list:documents` 中的条目 `<doc>`；应用还要删除 `<s>:control:apps:all` 中它的条目；
  - 每个 `"tenantId"` 或 `"app"` 含 `/` 的 `<s>:api-key:<sha256>`。

  然后删除每个在引用方中列出 `<doc>` 的 `<s>:control:refs:*` 集合，再运行 [`repair-references`](./upgrading.md#repair-the-back-reference-index)，它会写回其余的头所引用的内容。
  完成之前，本版本在每条这样的记录上整体失败并指名它（`SCP-10`）：反向引用集合让发布其策略失败，变更回答 `503`，未完成的变更让每轮维护停止，
  API key 查找失败（`503`）。安装过这种文档的预览版实例，其实例状态会让影子窗口的状态读取失败，直到它过期，即该实例停止两分钟之后（状态 key 的存活时间，`ShadowCacheSpecs.STATUS_TTL`）。
  给租户或应用改名，并以新 id 发布它的文档；旧 id 的令牌是 `401 token-invalid`（[租户和应用 id 不含斜杠](./upgrading.md#tenant-and-app-ids-hold-no-slash)）。
- **没有任何 CoSec 模块导入 platform，`cosec-dependencies` 不再发布**（R11）。此前每个库模块的 POM 和 Gradle 模块元数据都导入 `cosec-dependencies`，后者导入 Spring Boot、Spring Cloud、CoCache、OpenTelemetry、springdoc 和 fluent-assert 的 BOM，并管理 jmh、mockk、archunit、blockhound、json-schema-validator 和 detekt-formatting 的版本，所以加入一个模块就会提升应用整个构建中的这些版本，包括 `cosec-cocache` 的 CoCache 8 盖过应用自己的 CoCache 4。现在模块的元数据只写明它需要的库及其构建时的版本（作为最低版本），别无其他；Maven 也不再需要那个导入来解析它们。导入过 `me.ahoo.cosec:cosec-dependencies` 的应用请去掉这个导入，自己管理想要的版本（[CoSec 对构建的要求](../guide/introduction.md#what-cosec-needs-from-your-build)）。`cosec-bom` 列出应用可以依赖的 CoSec 模块（不含 `cosec-migrate`），不导入任何东西。`cosec-cocache` 和 `cosec-kafka` 的 POM 把 `cosec-control` 标为 `provided`：使用控制面的 Maven 应用自己添加 `cosec-control`，此前它们会把它（连同 `cosec-runtime`）带到运行时。
- **三个包名与其模块名对齐。** `me.ahoo.cosec.cache`（`cosec-cocache`）现在是 `me.ahoo.cosec.cocache`，`me.ahoo.cosec.servlet`（`cosec-webmvc`）是 `me.ahoo.cosec.webmvc`，`me.ahoo.cosec.spring.boot.starter`（`cosec-spring-boot`）是 `me.ahoo.cosec.spring.boot`。子包在新根之下保持原名；没有类名、artifact id、`cosec.*` 属性、bean 名或 Redis key 改变。在应用中需要修改：import；`spring.autoconfigure.exclude` 与 `@ConditionalOnClass(name = ...)` 字符串中的自动配置类名（`me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration` 现在是 `me.ahoo.cosec.spring.boot.CoSecAutoConfiguration`）；以及日志配置中的 logger 名（`logging.level.me.ahoo.cosec.cache` 现在是 `logging.level.me.ahoo.cosec.cocache`）。
- **策略文档、路由映射和 JSON 格式成为独立模块 `cosec-document`。** `me.ahoo.cosec.runtime.document` 现在是 `me.ahoo.cosec.document`，`me.ahoo.cosec.runtime.document.format` 是 `me.ahoo.cosec.document.format`，`me.ahoo.cosec.runtime.route` 是 `me.ahoo.cosec.document.route`；类（`PolicyDocument`、`PolicyDocumentCodec`、`PolicyDocumentCompiler`、`DocumentCompileException`、`Actor`、`RouteMap`、`RouteMapCodec` 等）保持原名。没有文档、schema、`cosec.*` 属性、bean 或 Redis key 改变。在应用中修改 import 即可。`cosec-runtime` 和 starter 会带上 `cosec-document`，所以依赖不变；只读写文档的代码（生成器、[CI 检查](../policies/testing.md#policies-in-ci)）可以只依赖 `me.ahoo.cosec:cosec-document`，不必依赖 `cosec-runtime`。`cosec-openapi` 现在依赖 `cosec-document` 而不是 `cosec-runtime`：经由它编译依赖管道的应用需要自己添加 `cosec-runtime` 或 starter。
- **影子窗口端口及其值移到 `cosec-spi`。** `CandidateStore`、`StoredCandidate`、`ShadowReportSink`、`ShadowReportReader`、`ShadowReport`、`ShadowSample`、`ShadowOutcome`、`InstanceStatus` 和 `InstalledDocument` 从 `me.ahoo.cosec.runtime.shadow` 移到 `me.ahoo.cosec.spi.shadow`，仍是实验性 API，名称不变。你自己的存储修改 import 即可，并且可以只依赖 `cosec-spi`。`cosec-cocache` 不再依赖 `cosec-runtime`：只经由 `cosec-cocache` 编译依赖管道的应用需要自己添加 `cosec-runtime` 或 starter。存储格式和 Redis key 都不变。
- **签发令牌归 `cosec-login`。** `TokenFamilies`、`DefaultTokenRevoker` 和 `SimpleCompositeToken` 从 `me.ahoo.cosec.runtime.token` 移到 `me.ahoo.cosec.login.token`，名称不变，其中两个后来又变了：`DefaultTokenRevoker` 是同一个包里的 `StoreTokenRevoker`，`SimpleCompositeToken` 已不存在，由 `cosec-api` 中的 `CompositeToken` 取代（均见下）；`me.ahoo.cosec.runtime.token` 保留校验请求令牌所需的部分（`TokenRevocation`、`CoSecTokenVerifier`）。在应用中修改 import 即可；starter 和 `cosec-jwt` 会带上 `cosec-login`，所以依赖不变。
- **适配器契约集中到一个包 `me.ahoo.cosec.runtime.http`。** `RequestGate`、`Outcome`、`Problem`、`ProblemType`、`ProblemEncoder` 和 `RequestSecurity` 从 `me.ahoo.cosec.runtime` 移入，`TraceContext` 从 `me.ahoo.cosec.runtime.event` 移入，与 `HttpFacts`、`Headers`、`Cookies` 放在一起；类名不变。在应用中修改 import 即可：你自己的适配器、读取 `RequestSecurity` 的处理器、读取 `Outcome` 的测试。状态码、响应头、响应体和属性都不变。有五个 logger 改为以现在独立记录日志的类命名，按 logger 名设置级别的日志配置改用新名字：

  | 之前 | 之后 |
  |---|---|
  | `me.ahoo.cosec.runtime.FallbackIds` | `me.ahoo.cosec.runtime.call.FallbackIds` |
  | `me.ahoo.cosec.runtime.attribute.AttributeProviders` | `me.ahoo.cosec.runtime.attribute.FetchedAttributes` |
  | `me.ahoo.cosec.runtime.event.ShadowListener` | `me.ahoo.cosec.runtime.shadow.ShadowListener` |
  | `me.ahoo.cosec.runtime.shadow.Candidates` | `me.ahoo.cosec.runtime.shadow.CachingCandidateSource` |
  | `me.ahoo.cosec.runtime.shadow.ShadowRecorder` | `me.ahoo.cosec.runtime.shadow.ShadowReporter` |
- **变更日志有一个有意取定的 logger 名。** `LoggingChangeListener` 原来把变更日志写到 logger `me.ahoo.cosec.control.audit.ChangeAudit`，这个名字来自它所在的文件；现在写到 `me.ahoo.cosec.changelog`（`LoggingChangeListener.LOGGER_NAME`），与决策审计写到 `me.ahoo.cosec.audit` 一样：这个名字在所有包之外，任何类的 logger 都不是它的子 logger。按旧名路由或过滤的日志配置或日志采集器改用新名（[控制面变更日志](./observability.md#control-plane-change-log)）。日志行本身不变。分发器自己的警告 `Change listener [<name>] could not record <event>.` 现在由它自己那个类的 logger `me.ahoo.cosec.control.audit.ChangeListeners` 记录。
- **不使用 Spring 时用 `Pipeline.builder` 构建管道。** `Pipeline.builder(authenticator, store)` 像 starter 一样编译并装载 `SnapshotDocumentStore` 中的文档。`memberships`、`attributes`、`listener`、`rateLimiter`、`routes`、`ids`、`clock` 和 `forwardIdentity { … }` 设置其余部分；`options { … }`、`snapshots { … }` 和 `listeners { … }` 设置原来由 `PipelineOptions`、`SnapshotOptions` 和 `DecisionListeners` 的参数设置的内容，名称和默认值不变。`snapshots { prewarmApps = …; prewarmTenants = … }` 指定构建时装载的文档，与 starter 的 `cosec.authorization.snapshot.prewarm-apps` 和 `prewarm-tenants` 相同；`awaitDocuments()` 等到它们都已读取，返回尚未装载的那些，就绪时为空集合。`close()` 先投递监听器队列中的事件，再关闭文档。位于 CoSec 网关之后的服务用 `Pipeline.behindGateway { … }` 获得它的 gate。`Pipeline` 的构造器、`PipelineComponents` 和 `SuspendingSnapshotSource.of` 已删除；`PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、`IdentityIssuer`、`IdentityGate` 和 `CoSecParameters.read` 现在是 `@InternalCoSecApi`，应用从不选择使用它们。状态码、响应头、响应体和 `cosec.*` 属性都不变。对手工组装的管道有一处朝安全方向的变化：它的文档现在像 starter 的一样编译，所以读取它所读 API key 请求头（`options { apiKeyHeader = … }`）的条件或限流规则 key 不能编译（`CND-5`）。这样的文档被装载为失败，其请求是 503；此前 `SuspendingSnapshotSource.of(store)` 会装载它。给同一项输入两种形式的构建器（例如 `listener` 与 `DecisionListeners`）拒绝构建，而不是丢掉其中一个。

  ```kotlin
  val pipeline = Pipeline.builder(authenticator, store)
      .snapshots { prewarmTenants = setOf("acme") }
      .memberships(memberships)
      .build()
  check(pipeline.awaitDocuments().isEmpty()) // 在第一个请求之前
  ```
- **公共与 SPI 签名中的时长都是 `java.time.Duration`。** `AttributeProvider.timeout` 是 `java.time.Duration?`：自己的提供方原来写 `200.milliseconds` 的地方，改为使用 `java.time.Duration` 的 `override val timeout: Duration? = Duration.ofMillis(200)`。`CoCacheMembershipProvider.DEFAULT_FILL_TIMEOUT`，以及 `KafkaAuditListener` 和 `KafkaChangeListener` 的 `drainTimeout`，同样使用 `java.time.Duration`；原来传入 `kotlin.time.Duration` 的代码用 `toJavaDuration()` 转换。属性、默认值和行为都不变。
- **`CompositeToken` 是 `cosec-api` 中的 final 类，`SimpleCompositeToken` 已删除。** 用 `CompositeToken(accessToken, refreshToken)` 构造。它按值相等，没有 `copy` 或 `componentN`。记录令牌的日志不再打印令牌：它的字符串是 `CompositeToken(accessToken=***, refreshToken=***)`。登录或刷新端点返回的 JSON 不变（`{"accessToken":…,"refreshToken":…}`）。`RefreshTokenCredentials` 自己声明 `accessToken` 和 `refreshToken`，不再是 `CompositeToken`：应用实现的凭证不继承一个值。你的刷新凭证类像以前一样覆盖这两个属性：

  ```kotlin
  data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
      RefreshTokenCredentials
  ```

  在需要 `CompositeToken` 的地方（`TokenRevoker.revoke`）传入它的代码，要构造一个：`CompositeToken(credentials.accessToken, credentials.refreshToken)`。
- **`SimpleRefreshTokenAuthentication` 更名为 `RefreshTokenAuthentication`**，包和构造参数不变：唯一的实现不需要前缀。在声明它的 bean 的地方改类名。
- **`DefaultTokenRevoker` 更名为 `StoreTokenRevoker`**：它经由 `TokenStore` 吊销，名字现在说出了这一点。包（`me.ahoo.cosec.login.token`）和构造参数不变；starter 的 bean 仍是 `cosecTokenRevoker`，`TokenRevoker` 仍是 SPI。在构造它的地方改类名。
- **`api.CoSec` 已删除。** 它的值拼写不变，在有含义的地方各有名字：匿名主体是 `CoSecPrincipal.ANONYMOUS_ID`，默认租户是 `TenantId.DEFAULT`，两个都拼作 `(0)` 的不同 id；`cosec.*` 属性和 Redis key 的默认前缀（`cosec.key-prefix`，`cosec`）不变，span 属性（`cosec.tenant_id` 等）和 OpenAPI 方案 `cosec.BearerAuth` 也不变。读取 `CoSec.COSEC`、`CoSec.COSEC_PREFIX` 或 `CoSec.DEFAULT` 的代码，改为写出它所指的值。key、属性和 span 属性都不变。
- **租户切换是带 `TenantSwitch` 的 `SwitchTenantAuthentication`，它可以设定应用。** `AbstractSwitchTenantAuthentication` 已删除。原来继承它并实现 `switchTenant(targetTenantId, previous)` 的地方，改为用 starter 的 `TokenFamilies` 和 `TokenRevocation` bean，把 `SwitchTenantAuthentication(families, revocation, YourCredentials::class.java) { credentials -> … }` 声明为 bean：切换拿到的是你自己的凭证类（`credentials.targetTenantId`、`credentials.context`，以及它携带的其他内容），像以前一样回答 `TenantMembership`。`SwitchTenantCredentials` 新增 `targetAppId`（用户请求的应用，默认为 null），并删除了 `accessToken`：切换从请求的会话签发，管道构造的 `context` 携带这个会话（`IDN-22`），所以不要再传令牌文本（也不要为此解析 `Authorization`）。`TenantMembership` 新增 `app`。新令牌属于你的切换回答的应用，否则属于先前令牌的应用（`SCP-12`）：不回答应用的切换行为不变，用户也永远不能靠指名得到一个应用。`TokenSubject.authTime` 已删除，`TokenFamilies.rotate` 和 `TokenFamilies.startAt`（原来的 `start(…, authTime)`）归 CoSec 自己使用（`@InternalCoSecApi`）：从先前的登录签发，必须经过有效会话的检查。见[切换租户和应用](../integration/authentication.md#switching-tenant-and-app)。
- **转发的身份携带 CoSec 令牌的会话和签发时间**（`auth.session`：`sid`、`auth_time`、`generation`；`auth.iat`），使网关之后的服务知道用户何时登录，并让那里的租户切换从这个会话签发（`IDN-22`）。格式仍是版本 1，服务严格读取它：比网关旧的服务会以 `400` 拒绝这些新成员。**请先升级网关之后的服务，再升级网关。** 没有这些成员的身份（API key 的，或更早的网关发送的）照旧读取，但不能从它切换。`Identity.Credential` 新增可选的 `issuedAt` 和 `session`。
- **`SecurityContext` 新增 `requestId` 和 `deviceId`。** `requestId` 是 CoSec 为该请求使用的 id（客户端的 `CoSec-Request-Id`，或 CoSec 生成的、任何请求头都没有的 id）；`deviceId` 是客户端的 `CoSec-Device-Id`，未声明时为空，CoSec 只检查其形式，它不能证明是哪台设备。请从上下文读取它们，而不是读请求头。你自己的 `SecurityContext` 实现照样能编译：两者默认都是 `""`。在网关之后，服务现在按网关读取 `CoSec-Device-Id` 的同一规则读取它（只能有一个值，否则 `400`），因此代码中检查的 `environment.device` 在那里也能看到它；以前在网关之后看不到设备。
- **刷新可以重新读取用户。** `RefreshTokenAuthentication` 接受一个可选的 `MembershipRefresh`，回答用户现在拥有什么；校验、吊销检查、轮换和会话仍由这个类完成。原来为在刷新时重新读取用户而编写自己的 `Authentication` 的地方，改为传入 `MembershipRefresh`。不传时，刷新照旧保留令牌的声明。钩子会拿到令牌和刷新的凭证（`{ token, credentials -> … }`）；要在那里读取你自己的内容（例如设备），把你的凭证类也传进去：`RefreshTokenAuthentication(verifier, revocation, families, YourCredentials::class.java) { token, yours -> … }`，与租户切换接受自己的凭证类一样。`RefreshTokenAuthentication(verifier, revocation, families)` 的写法不变。
- **`TokenCompositeAuthentication` 用 `TokenCompositeAuthentication.of(authentications, tokenConverter, families)` 构建。** 它的构造器需要两个应用无法构建的类型（`CompositeAuthentication`、`DefaultAuthenticationProvider`），现在是 `@InternalCoSecApi`。starter 的 bean 不变；手工组装的登录（例如在测试中）把它的 `Authentication` 传给 `of`。
- **任何请求都不可能有的 `http` 动作不能编译**（`DOC-8`）。`http:HEAD`（自 `PIP-18` 起按 `http:GET` 判定）或动词含小写字母（`http:get`）的语句或限流规则不匹配任何请求，这样的 DENY 什么也拒绝不了。现在这样的文档被拒绝：已存储的会被装载为失败，其请求失败即拒绝（`503`），控制面也拒绝会产生这种文档的变更。升级之前，请在控制面列出的文档中查找它们（`"http:HEAD"`，或冒号后含小写字母的 `"http:` 动作），改为 `http:GET` 或大写的方法后重新发布。对 7.x 的转换，`cosec-migrate convert` 不论语句如何都会报告一次：HEAD 请求现在按 GET 决策（`PIP-18`：GET 被放行处放行，GET 被拒绝处拒绝）；它把 7.x 的 `ipRegion` 的国家前缀读作 `ext.ip2region.country`，平台文档随后声明它（执行这类语句的每个宿主都需要 `cosec-ip2region`）；只有 7.x 的请求附加器填充过的部分（`ipRegion`、应用自己的属性）在 8.0 中始终缺失，所以对它的条件只在收窄 ALLOW 时才翻译（记为 `NOT-IN-8.0`），其余情况都被拒绝（`MIG-1`），因为它会放宽 ALLOW、拒绝范围内的每个请求，或使 DENY 不再匹配 7.x 拒绝过的内容。
- **`cosec-migrate cocache8` 已删除。** 它把 8.0.0-alpha.2 的数据从 CoCache 6 迁移到 CoCache 8（[经 CoCache 6 写入的数据](#data-written-through-cocache-6)）：仍保存这种数据的部署，请在升级之前用 8.0.0-alpha.4 的 `cosec-migrate` 迁移。`<p>:meta:cocache8` 存在时，`publish` 拒绝执行，不写入任何内容：这个标记由一次在删除文档之后中断的升级留下，那些文档在它的导出目录里，不在 Redis 中。请用 8.0.0-alpha.4 的 `cosec-migrate` 完成那次升级（`publish --resume`，`--in` 指向消息给出的导出目录），或者确认标记列出的每份文档都已发布，再删除这个 key。
- **`cosec-migrate api-keys --app` 可用了**（第 2 轮 M3）。自 #1302 起这一步无法运行：#1302 把 `--app` 加进了代码和本页，却没有加进该命令接受的选项，命令因此把 `--app` 当作它不接受的选项而拒绝。对配置了应用的服务必须接受的 key，请带上 `--app <id>` 重新运行。
- **starter 的启动检查在延迟初始化下也会运行**（`STA-3`）。设置 `spring.main.lazy-initialization=true` 时，此前没有任何检查运行：缺少 `cosec-webmvc` 的 Spring MVC 宿主在完全没有授权的情况下启动，`gateway` 模式的 Spring Cloud Gateway 宿主不经检查地转发每个请求，Redis 的逐出策略、从副本读取、暴露的 actuator 端点、没有 `MembershipProvider` 的 API key 和未知的 `cosec.*` 属性都不受检查。现在它们与不使用延迟初始化时一样使启动失败。只因检查被跳过才能启动的配置不再能启动：按消息修正。
- **社交登录注册中的未知字段使启动失败**（第 2 轮 L4）。此前 `cosec.authentication.login.social.registration.<name>` 下拼错的字段（`pcke`、`scope`）被忽略，提供者的配置与作者的本意不同；现在消息会指出这个键。请改名或删除。
- **不起作用的 `cosec.cosid.generator` 使启动失败**（第 2 轮 L7）。此前在没有 `CosIdRuntime` bean、或者应用自己有 `IdGenerator` bean 时设置它，它会被忽略，CoSec 仍使用随机 id。请配置 CosId，或删除该属性。
- **两个哈希相同的内存 API key 使启动失败**（第 2 轮 L5）。此前该 key 以 `cosec.api-key.keys` 中最后一项认证；每个 key 只保留一项。
- **控制面的授权 bean 不可替换，无论是否 `@Primary`**（第 2 轮 L6）。此前标记 `@Primary`、或按注入点参数名命名的第二个 `ControlAuthorizer`、`ChangeAuthority`、`Changes`、`ControlPlane` 或 `ControlHttp` bean 会取代 CoSec 的 bean 被注入；现在它与普通的第二个 bean 一样使启动失败。
- **主体的属性只读**（第 2 轮 L3）。此前 `SecurityContext.principal.attributes` 就是认证器交出的那个映射：应用把它强转为可变映射并修改，就改变了该请求之后每一次 `AuthorizationChecker` 检查所依据的内容。现在它是不可修改的视图，令牌中嵌套的对象和数组也是只读的：修改会抛出 `UnsupportedOperationException`。要修改请先复制。你自己的认证器交出映射后不得再修改它。
- **测试工具按执行的方式读取请求**（第 2 轮 M2，`PIP-15`）。此前 `PolicyTestKit` 按原样判定 `http:HEAD`、含小写字母的方法和带点段的路径，所以会确认一条 `http:HEAD` 上的 DENY，而管道把 HEAD 按 GET 判定，从不应用它。现在它把 HEAD 按 GET 判定、规范化路径，并在管道回答 `400` 时给出 `{"decision": "InvalidRequest"}`：请改写这样的场景，并把 `http:HEAD` 上的语句改写为 `http:GET` 上的。`expect` 必须写出 `decision`（此前空的 `expect` 无论决策如何都会通过）；文件名被缩短的目录需要 `cosec-migrate convert` 在旁边写下的 `report.json`。实验性的 `evaluate(JsonNode)` 和 `check(String, JsonNode)` 已删除：以文本传入场景，用 `check(name, json)`。
- **生成的路由映射位于 `servers` 路径之下，资源包含路径的每个变量**（第 2 轮 L4）。`cosecServiceGenerator` 端点丢掉了服务器路径（`/api/v1`），生成的路由匹配不到任何请求，所有请求都落在 `http` 命名空间；`/users/{id}/avatar/{size}` 的资源是 `user/{size}`，所有用户共用一个资源。现在路由以服务器路径开头（不含上下文路径），资源是 `user/{id}/{size}`：重新生成路由映射，并改写策略中按生成结果写的资源。多个服务器路径不同，或路径含服务器变量时，生成失败。
- **ip2region：无法定位的是取不到**（第 2 轮 M5）。只有随附的 IPv4 数据库时，IPv6 客户端没有国家，写成 `country in [...]` 的地域封锁 DENY 会放行它；现在它是取不到，这条 DENY 使请求返回 `503`（或者用 `onMissing: nomatch` 放行）。用 `cosec.ip2region.ipv6-location` 加载 IPv6 数据库。`Ip2RegionAttributeProvider` 改为接收数据库列表，每个数据库的版本取自其文件头，不再接收单个数据库和版本。
- **失败的 `IdGenerator` 只损失它自己的 id**（`PIP-23`）。此前抛出异常的生成器（时钟回拨后 CosId 的 `ClockBackwardsException`）会在得出任何结果之前离开管道：没有问题体，没有 `CoSec-Request-Id`，没有决策事件，各适配器各自应答。现在以随机 id 代替，并至多每十秒记录一次 WARN；`CosIdIdGenerator` 对 CosId 的失败也是如此。请把 CosId 的时钟回拨策略配置为失败而不是等待。
- **处理器从 `SecurityContext` 参数读取安全上下文**（第 2 轮 M4）。starter 注册了 `SecurityContextArgumentResolver`。`SecurityContextHolder` 只用于请求线程上的阻塞代码：在另一个请求的线程上恢复的 `suspend` 处理器，此前会从它读到那个请求的上下文。有 `io.micrometer:context-propagation` 并设置 `spring.reactor.context-propagation=auto` 时，Spring 现在也会恢复持有者；否则在 `suspend` 处理器中持有者是未绑定的：在那里 `SecurityContextHolder.context` 为 `null`，`requiredContext` 会抛出异常（`PIP-22`）。
- **应用只读取安全上下文**（第 2 轮 L5）。`SecurityContextHolder.setContext` 和 `remove`、`ServletRequests.setSecurityContext`、`ServerWebExchanges.setSecurityContext`，以及 `ReactiveSecurityContexts.setSecurityContext` 和 `writeSecurityContext` 都是内部 API：只有适配器绑定上下文。自行绑定上下文的代码（`AuthorizationChecker` 本来就拒绝这样的上下文）不再能编译。
- **context path 按段去掉**（`PIP-20`）。Jetty 以规范形式给出 context path，而 URI 保留客户端的写法：context path 为 `/app` 时，`/%61pp/orders/1` 此前按 `/app/orders/1` 判定，绕过了 `/orders` 上的所有语句；现在是 `/orders/1`。不以 context path 开头的 URI 是 `400`。
- **Spring MVC 过滤器的顺序改为 `-9999`**，紧跟在 Spring Boot 的隐藏方法过滤器（`-10000`）之后，而不再是 `HIGHEST_PRECEDENCE + 100`。打开 `spring.mvc.hiddenmethod.filter.enabled` 时，带 `_method=DELETE` 的 `POST` 此前按 POST 授权、按 DELETE 执行；WebFlux 一直授权的是 DELETE。必须排在 CoSec 之前的过滤器（例如 CORS 过滤器）需要低于 `-9999` 的顺序。
- **HEAD 按 GET 判定，含小写字母的方法是 `400`**（`PIP-18`）。服务器用 GET 的处理器处理 HEAD，所以 `http:GET` 上的 DENY 拦不住它：现在 HEAD 使用路由映射的 HEAD 路由，没有时使用 GET 的路由，未映射时是 `http:GET`；`http:HEAD` 上的语句会被拒绝（`DOC-8`，见上），因为 HEAD 按 `http:GET` 或路由映射的 HEAD 路由的动作判定。`delete` 这样的方法此前按 `http:delete` 判定，`http:DELETE` 上的 DENY 不会命中，而会折叠大小写的后端把它当作 DELETE 处理：它现在是 `400 invalid-request`。`AuthorizationChecker` 以及控制面的 simulate、explain、analyze 按同样的方式读取 `http` 动作：`http:HEAD` 按 `http:GET`，小写动词为拒绝或 `400`。
- **客户端地址只读取一次**（`PIP-16`）。服务器自己解析转发头时（`server.forward-headers-strategy=native`，Spring Boot 在 Kubernetes 以及它识别出的其他云平台上也会自行开启），CoSec 不再第二次读取 `X-Forwarded-For`：在 Tomcat 上采用 `RemoteIpValve` 解析出的地址，在 Reactor Netty 和 Jetty 上读取该请求头而绝不使用服务器转发来的地址。此前在 Tomcat 上，两次读取叠加会取到客户端写的条目。现在以下情形启动失败：`framework`；`native` 下的 Tomcat 显式设置了 `cosec.authorization.remote-ip.max-trusted-index`；`native` 下的 Reactor Netty 或 Jetty 设置了 `max-trusted-index: 0`；CoSec 无法判断的服务器上的 `native`。迁移方法：按消息修改（[故障排查](./troubleshooting.md#startup-fails-on-the-client-address)）。`XForwardedRemoteIpResolver` 现在是内部 API。
- **多行 `X-Forwarded-For` 视为一个列表，读不出的客户端地址是“取不到”**（`PIP-17`）。此前带两行该请求头的请求使用连接的对端，客户端可借此绕开针对它自己地址的封禁；现在按顺序读取这些行。带端口、方括号或 zone 的可信条目按其中的地址读取。不是地址的条目（`unknown`）此前使 `environment.ip` 缺失，DENY 视为不匹配；现在它是取不到：这样的 DENY 返回 `503`，黑名单也回答取不到。
- **凭证的租户必须是租户 id**（`IDN-11`）。`tenantId` 为空白、`*`、含 `:` 或控制字符、含 `/`、或超过 128 个字符的令牌现在是 `401 token-invalid`（此前为 `503`，空白时为 `500`），`JwtTokenConverter` 也不签发；租户如此的 `ApiKey` 无法构造，所以已存储的会查找失败（`503`），配置的会启动失败。
- **`cosec-spi` 的 `SnapshotVersion` 已删除**：它表示 7.x 的两份文档。决策事件的 `snapshotVersion` 仍是 `ViewVersion`（`platform`、`app`、`tenant`）。
- **限流窗口至多一天**（`windowSeconds` 至多 `86400`），`permitsPerSecond × windowSeconds` 至多 2⁶³：更大的配额会使限流器的算术溢出，更长的窗口会在一个不得逐出的 Redis 中为每个桶值保留一个 key 两个窗口（第二轮复审 M3）。这样的文档不再能解码；`RateLimit` 拒绝同样的取值。迁移方式：把按天或更长的配额写成一天窗口内的每秒速率。
- **Redis 限流器每个桶一个 key，并按 Redis 的时钟计数**（第二轮复审 L1）。一个桶现在是 hash `<cosec.key-prefix>:rate-limiter:<rule>:<bucket>`，而不是每个窗口一个 key（`...:{<rule>:<bucket>}:<window>`）；脚本从 Redis 取时间，时钟不一致的实例不再各自计数。旧版本写下的计数器不会被读取，会自行过期：升级时每个桶从零重新计数一次。`ReactiveRedisRateLimiter` 不再接收 `clock`。
- **一份文档至多 64 条限流规则**（`DOC-6`）。被放行的请求每匹配一条规则就调用一次限流器，所以超过的文档不再能编译：已存储的这种文档被装载为失败（其请求返回 `503`），控制面以 `422` 拒绝会产生这种文档的变更。迁移方法：把只在计数对象上不同的规则合并为一条带 `key` 的规则。
- **有多个资源模式的语句，含义等于每个模式各一条语句**（`ALG-10`）。条件读取路径变量、且请求匹配其中多个模式时，现在依次用每个匹配模式的捕获值判断条件，任一成立即命中；此前只读第一个匹配模式的捕获值，所以 DENY 可能漏掉它自己的某个模式会拒绝的请求，模式顺序也会改变决策。这样写的 DENY 现在拒绝的不少于以前；ALLOW 可能放行更多。按变量做 key 的限流规则，取第一个使条件成立的模式的值。检查方法：在文档中查找有多个 `resource` 模式且 `condition` 读取 `resource.var.*` 的语句。
- **含空段、编码分隔符或反斜杠的路径答 `400`**（`PIP-14`）。`//admin`、`/admin//x`、`/;x/admin`、`/admin%2Fx`、`/admin\x` 和 `/admin%5Cx` 以前被当作独立的路径决定，所以 `/admin` 之下的 `DENY` 匹配不到它们，而会合并斜杠、解码分隔符或把 `\` 当作 `/` 的后端却按 `/admin/...` 提供服务。末尾的 `/` 不变：`/admin/` 仍是一条独立的路径。以前能得到服务、现在被拒绝的请求：
  - 路径中的 `\`，无论是否编码：客户端本不应发送它，浏览器会把它变成 `/`；
  - 路径变量中编码的 `/`，例如 `/files/a%2Fb.txt` 或 `/repos/group%2Fproject`：把值放进查询参数，或者对其中的 `/` 编码两次（`%252F`），管道解码一次得到文本 `%2F`，由应用再解码一次；
  - 用各自带 `/` 的两段拼接出的 URL（`https://host/api/` + `/orders`）：修正客户端；
  - 最后一段之前单独成段的路径参数（`/;jsessionid=x/orders`）：URL 重写会把它放在最后一段的末尾（`/orders;jsessionid=x`），这种写法仍被接受。

  没有开关可以接受它们：模式无法表达后端会把这些路径当成什么，决定它们就会让绕过重新出现。
- **检查拒绝执行会拒绝的内容**（`PIP-15`）。`AuthorizationChecker.check` 和 `decide` 现在按管道读取请求的方式读取路径：管道答 `400` 的路径是拒绝，超过请求路径长度上限的资源也是拒绝。以前 `/public` 之下的 `ALLOW` 可能放行 `/public/../admin`。
- **影子窗口多统计两种结果**：`BECAME_DENIED` 和 `OBLIGATIONS_CHANGED` 以前被计为 `SAME`，现在发布时必须确认。滚动升级之前请结束或放弃未关闭的窗口：上一版本的实例读不了包含它们的报告。
- **社交登录的 state 只能用一次，并绑定到发起登录的浏览器**（第二轮复审 M4）。JustAuth 只检查 state 是否存在：一个 state 能让任何打开带它的回调的浏览器登录，在 3 分钟内打开几次就登录几次，这使登录 CSRF 成为可能。`SocialAuthentication.authorizeUrl(provider)` 改为 `authorize(provider)`，返回 `SocialAuthorization`（`url`、`binding`）；`SocialAuthenticationProvider` 同样如此。重定向浏览器之前，先在浏览器上设置 `authorization.setCookie(path)`，并把 Cookie 的值传给 `JustAuthCredentials(provider, binding)` 的第二个参数；`SocialCredentials` 增加了 `binding`。回调时 state 从 Redis 中被取走（`<s>:oauth:binding:<state>`，`GETDEL` 需要 Redis 6.2 或更高版本）。`JustAuthProvider` 接收一个 `SocialStateStore`。注册上设置 `ignore-check-state: true` 会让启动失败。
- **令牌和 API key 声明它们的应用**（批次 R7b、RFC 0012 §3.5、`SCP-11`）。令牌的 `aud` 和 API key 的 `app` 指明凭证属于哪个应用，`ScopeResolver` 把它们与宿主的应用比较：配置了应用的宿主（`cosec.authorization.scope.app-id` 或路由的 `cosec-app-id`）拒绝声明其他应用或未声明应用的凭证，返回 `403 scope-rejected`。没有宿主应用时取凭证的应用，两者都没有时不读取应用文档。迁移方法：签发令牌时设置 `TokenSubject.app`，每个应用一个令牌；为 API key 设置 `app`（`ApiKey`、`cosec.api-key.keys[].app`）。存储的 key 升级为带必填 `app` 字段的 format 3：format 2 会失败（`API key format [2] is not 3.`），请重新发放（`cosec-migrate api-keys --app <id>` 会为它从 7.x 转换的 key 设置应用）。操作控制面的令牌发给 `cosec.control.app-id`（默认 `cosec-control`）；宿主应用与之不同的宿主上的控制面路由返回 `403`，请把它们放在没有应用或就是该应用的宿主上。
- **`CoSec-App-Id` 已删除。** 客户端不再声明应用；该请求头不被读取，发送它的请求取凭证的应用。网关转发的身份仍然携带它的应用：配置了应用的服务拒绝声明其他应用或未声明应用的身份，返回 `400 invalid-request`。请读取 `SecurityContext.scope.appId` 获取请求的应用。
- **`SecurityContext` 是只读的**：`tenantId`、`principal`、非空的 `scope` 和 `forwardedIdentity`（`gatewayRequestId`、`credentialId`，取代属性 `cosec.identity.rid` 和 `cosec.identity.jti`）以及 `requestId` 和 `deviceId`（见上）。属性表及其访问方法已删除；`tenant.tenantId` 变为 `tenantId`。
- **`CoSecPrincipal` 是应用读取到的请求信息**：`id`、显式的 `authenticated`、`attributes`。请求生效的角色在 `SecurityContext.scope.roles` 中；`principal.roles` 和 `principal.policies` 已删除。
- **令牌契约都是值。** `TokenVerifier.verify(accessToken: String)` 和 `refresh` 返回 `VerifiedToken`（`me.ahoo.cosec.spi.token`），没有类型参数；`TokenRevoker.revoke` 接收令牌字符串，或一个 `CompositeToken`（见下）。`TokenConverter` 接收 `TokenSubject`（`me.ahoo.cosec.login.token`），`Authentication<C>` 返回它。已删除：包装类 `SimplePrincipal`、`SimpleTenantPrincipal`、`TenantPrincipal`、`TokenPrincipal`、`TokenTenantPrincipal`、`SimpleTokenPrincipal`、`SimpleTokenTenantPrincipal`、`MembershipMarkedPrincipal`、`Delegated`、`findInDelegationChain`、`IssuedAtCapable`、`ExpiresAtCapable`、`MembershipMarkerCapable`、`AccessToken`、`RefreshToken`、`Token` 和 `TokenIdCapable`。自定义的 `TokenVerifier` 改为构造 `VerifiedToken`；它的构造函数拒绝无效声明，抛出的正是 `verify` 本来就会抛出的 `TokenVerificationException`。
- **`RoleCapable`、`PolicyCapable`、`SpacedRoleId`、`RoleIdCapable`、`SpaceIdCapable`、`Tenant`、`TenantCapable`、`SimpleTenant` 和 `Named` 已删除。** 声明名是 `MembershipClaims` 的常量，租户 id 是 `TenantId.DEFAULT` 和 `TenantId.PLATFORM`；`role@space` 语法不变。`SocialAuthenticationProvider` 保留 `name`。
- **`sub` 以 `(` 开头的令牌无效**（`IDN-12`），没有 `sub` 或 `jti` 的令牌也无效：令牌不能使用 CoSec 自己的 id，尤其是匿名主体 `(0)`。
- **`AttributeRequest` 携带 `tenant`**，`Subject.tenantId` 已删除：A 阶段提供者读取 `request.tenant`。
- **`SwitchTenantCredentials` 携带请求的 `context`（`SecurityContext`）**，不再是 `principal`，`switchTenant` 接收它（`AbstractSwitchTenantAuthentication.switchTenant` 现在是 `SwitchTenantAuthentication` 的 `TenantSwitch` 钩子，见上）。新令牌保留原令牌的应用。
- **登录改为挂起函数，`cosec-login` 不再引入 Reactor**（批次 R7c）。`Authentication.authenticate`、`TokenCompositeAuthentication.authenticateAsToken`、`AbstractSwitchTenantAuthentication.switchTenant`（现在是 `SwitchTenantAuthentication` 的 `TenantSwitch` 钩子，见上）、`SocialAuthenticationProvider.authorizeUrl` 和 `authenticate`、`SocialAuthentication.authorizeUrl` 以及 `SocialUserPrincipalConverter.convert` 都是 `suspend` 函数，直接返回值而不是 `Mono`。迁移方法：把重写改成 `suspend` 并返回值；用 `awaitSingle()` 等待 Reactor 客户端；把控制器函数改成 `suspend`。用 `Mono.empty()` 拒绝登录的代码要改为抛出异常，因为不再有“空”的回答。通过 `cosec-login` 编译 Reactor 的模块需要自行声明它。`cosec-social` 不再引入 Guava。
- **`DefaultAuthenticationProvider` 和 `SocialProviderManager` 改为类**，starter 为每个应用上下文各装配一个（#1222）。对旧单例的调用请换成 `AuthenticationProvider` 或 `SocialProviderManager` Bean；`SocialAuthentication` 通过构造函数接收管理器。
- **`JustAuthProvider` 运行在自己的调度器上**，每个提供方最多 16 个线程，社交登录不再需要 `Schedulers.boundedElastic()`。
- **库模块导出其公共类实现的类型**（`cocache`、`kafka`、`ip2region`、`micrometer`、`opentelemetry`）：只为编译它们而添加的依赖可以去掉。

- **公共 API 已归类**（RFC 0010 C4）。应用需要的保持公开；其余的是 `@InternalCoSecApi`（应用永不选择加入）或 `internal`。`cosec-engine` 的大部分、`cosec-model` 的条件 AST、`cosec-runtime` 的快照视图、影子记录与文档模型、控制面的 `ControlPlane`、授权与访问分析，以及 starter 的自动配置类现在都是内部的。如果你编译时依赖其中之一，请改用文档页面描述的方式：检查和监听器都还在。测试工具里只有 `PolicyTestKit`、`PipelineFixture`、`PermittedRequest`、`AttributeRequests`、`EngineOutcome` 及其异常是公共的。
- **`PipelineComponents` 成为普通类**，`SnapshotOptions` 也是；两者都失去了 `copy` 和 `componentN` 函数。`PipelineComponents` 不再接受 `engine`。不使用 Spring Boot 的宿主用 `Pipeline.builder(authenticator, store)` 构建（本条最初写的 `SuspendingSnapshotSource.of(store)` 后来已删除，见上）。
- **`PolicyDocumentCompiler.compile` 成为内部 API。** CI 检查调用 `validate`；`forPlatform(platform)` 是对应的重载。
- **公共 `data class` 现在只是不会增长的值**（RFC 0010 C4）：`Identity`、`Identity.Credential`、`TokenSubject`、`SocialUser`、`ForwardedIdentity`、`Audit*Data`、`ShadowReport`、`ShadowSample`、`InstalledDocument`、`InstanceStatus` 以及 starter 的 `*Properties` 失去了 `copy` 和 `componentN`。`Identity`、`Identity.Credential`、`TokenSubject` 和影子记录仍按值比较；其余的不再定义 `equals`。
- **需要访问令牌的地方拒绝刷新令牌，需要刷新令牌的地方也拒绝访问令牌**（`IDN-13`）。`JwtTokenConverter` 在每个刷新令牌上写 `cosec.token: "refresh"`，带该声明的令牌不会完成认证；刷新只读取带该声明、且 `iat` 与访问令牌相同的令牌。此前签发的访问令牌在过期前继续有效；它们的刷新令牌没有该声明，不能再刷新，其用户需要重新登录。
- **刷新令牌属于会话**（RFC 0009，第二轮复审 M2）。登录会开始一个会话，即一族令牌对，两个令牌都带有它的 id、登录时间和代数（`sid`、`auth_time`、`cosec.generation`），保存在 Redis 的 `<cosec.key-prefix>:token:family:<sid>` 下（[会话](../integration/authentication.md#sessions)）。只有当前一对能刷新；更早的一对再次出现会结束会话；会话从登录起持续 `cosec.jwt.session.lifetime`（30 天）；登出会结束整个会话。对应用的影响：
  - **每个用户需要重新登录一次：** 本版本之前签发的令牌对不带会话，刷新时被拒绝（`token-invalid`）；它的访问令牌在过期前仍可用。
  - **通过 `TokenCompositeAuthentication` 签发令牌**，它会开始会话；或者用 `TokenFamilies.start` 和 `TokenSubject.inSession` 自己开始会话：单独由 `TokenConverter.toToken` 签发的令牌对永远不能刷新。`TokenCompositeAuthentication` 和 `SimpleRefreshTokenAuthentication`（现在是 `RefreshTokenAuthentication`，见上）接收一个 `TokenFamilies`（starter 提供的 Bean）。
  - **客户端只保留最新的一对：** 在更新的一对签发之后出示旧的一对会结束会话，除非发生在那次刷新之后的 `cosec.jwt.session.rotation-leeway`（30 秒）之内：此时它会得到最新一代的一对令牌，因此响应丢失的刷新可以重试。
  - **`TokenRevoker.revoke(compositeToken)`** 用于访问令牌已过期时登出；`revoke(accessToken)` 现在也会结束会话。自定义的 `TokenRevoker` 要实现两者。
  - **切换租户需要会话：** 此前是 `SwitchTenantCredentials.accessToken` 和接收 `TokenVerifier` 的 `AbstractSwitchTenantAuthentication`；现在它从请求的会话签发（`SwitchTenantAuthentication`，见上），新令牌保留登录时间。
  - **不使用 `cosec-cocache` 时，请注册 `TokenFamilyStore`**，否则启动失败（单实例可用 `InMemoryTokenFamilyStore`）。Redis 中每个存活的会话一个 key。
- **无法读取的吊销存储会被如实报告，绝不读成“未吊销”**（第二轮复审 H1，`IDN-14`）。存储无法读取时，`RevocationReader` 抛出 `RevocationUnavailableException`；吊销表的 `unknown-read` 锁定为 `throw`，设置 `cocache.caches.[RevokedTokenCache].unknown-read` 会让启动失败。此时流水线仍像以前一样接受请求的令牌；刷新改为失败而不签发令牌，审批人的吊销无法读取时，控制面不发布任何东西。读取失败时回答“未吊销”的自定义 `RevocationReader` 改为抛出 `RevocationUnavailableException`。若请求也要拒绝，请设置 `cosec.authentication.revocation.fail-closed: true`，它取代了吊销表上的 `unknown-read: throw`。
- **吊销只有一个判断，令牌契约不做阻塞 I/O。** 按契约，`TokenVerifier` 是纯计算。`RevocableTokenVerifier` 已移除：流水线、刷新和登出都通过 `TokenRevocation`（`cosec-runtime`）读取 `RevocationReader` 中的吊销。`TokenStore` 只保留写入 `revoke` 和 `revokeSubject`，二者改为 `suspend`；同步的 `isRevoked`、`subjectRevokedBefore` 已移除（请通过 `RevocationReader` 读取），`TokenStore.NoOp` 也已移除。`TokenRevoker.revoke` 和 `revokeSubject` 改为 `suspend`：从 `suspend` 处理函数中调用即可，无需再移出事件循环。`DefaultTokenRevoker` 接收 `TokenRevocation`（现在是 `me.ahoo.cosec.login.token` 中的 `StoreTokenRevoker`，见上）。`SimpleRefreshTokenAuthentication(tokenVerifier)` 改为 `SimpleRefreshTokenAuthentication(tokenVerifier, tokenRevocation)`（现在是 `RefreshTokenAuthentication`，见上），不再使用自己的调度器。starter 的 `TokenVerifier` Bean 是普通的 `JwtTokenVerifier`，另外提供一个 `TokenRevocation` Bean。自定义的 `TokenStore` 以 `suspend` 函数实现两个写入，读取则作为 `RevocationReader` Bean 提供。
- **实例状态携带文档格式级别**（RFC 0010 C5）：没有 `formatLevel` 的实例状态会被本版本的控制面拒绝，读取到它的影子窗口会失败，直到所有实例升级完毕（控制面只计入最近一分钟内写入的状态，`ShadowReportReader.STATUS_TTL`；key 本身存活两分钟）。

- **控制面拒绝原本到运行时才失败的内容。** 平台变更把被其他文档引用的策略改成边界、或把被引用的边界改成策略，是 `REFERENCES`（`422`、`CTL-30`）。绑定指向平台边界同样是 `REFERENCES`：这样的绑定从未授予任何东西，它适用的每个请求都是 `503`，含有它的文档要先移除它才能再次发布。变更写入的 id 不能含 `/`（`V2`、`422`）：以 `*` 结尾的规则匹配不到这样的条目。权限策略的 `perm:<app>/<id>` 可以含；文档中已有这种 id 的条目可以保留或删除。移除租户所绑定的策略、或移除应用简写形式所派生的策略的应用变更，在检查时就是 `REFERENCES`：以前它能到达 `CHECKED`，直到发布时才失败（`CTL-30`）。
- **`ChangeEvent.Kind` 去掉了 `BOOTSTRAP` 和 `MIGRATE`。** 这两种变更事件从未被发出过：bootstrap 和迁移通过存储写入修订，记录在修订的
  `provenance.kind`（`BOOTSTRAP`、`MIGRATE`）里。对 `ChangeEvent.Kind` 做 `when` 且列出了它们的代码不再能编译；删掉这些分支即可，
  它们从未收到过任何东西。
- **应用声明了 `CorsConfigurationSource` bean 时，预检请求不再被策略、限流或审计看到**（`STA-5`，[CORS](../integration/web.md#cors)）。应用声明了所在栈的
  `CorsConfigurationSource`、且没有自己的 `CorsFilter` 或 `CorsWebFilter` 时，启动器现在会在 Spring MVC 和 WebFlux 上于 CoSec 之前注册一个 CORS 过滤器：
  预检请求仅凭 source 由它回答，所以针对 `http:OPTIONS` 的语句、限流、黑名单、审计、判定事件和指标都看不到预检请求；source 不允许的 origin 在管道之前得到一个普通的
  `403`，没有 problem 体和 `CoSec-Request-Id`；拒绝响应现在带 CORS 头。`@CrossOrigin` 和 `addCorsMappings` 不再回答预检请求：把每个 origin、方法和请求头
  都写进这个 bean。只为让预检请求通过而写的语句（`http:OPTIONS`）不再需要，留着也无妨。没有这个 bean、或有自己的过滤器的应用不受影响。
- **从平台或应用文档删除 DENY 需要第二个人**（RFC 0007 §7.4.4、`CTL-31`）。此前，没有 `cosec:approve` 的平台主体可以独自发布只删除条目的变更。现在，拿掉 DENY 的删除（含 DENY 的策略，或所绑策略含 DENY 的绑定，也包括经删除角色或以更少权限 `putRole` 去掉的）会停在 `AWAITING_APPROVAL`，每项带一个 `NOT_ANALYZED` 升级；开启 `four-eyes` 时信任根的这类变更也是如此（[审批](../policies/testing.md#approval)）。删除 ALLOW、租户删除自己的 DENY 以及吊销 break-glass 不变。请为紧急删除平台 DENY 安排好审批人。
- **`cosec:grant` 看角色实际派生了什么**（RFC 0012 §7.2）：让 `"*"` 角色派生出新绑定的 `putCatalog` 或 `putPolicy`，与 `putRole` 一样需要该角色作用域上的 `cosec:grant`。
- **break-glass 只需要 `cosec:breakglass`**（RFC 0007 §8）：申请、批准后发布以及吊销都不再额外需要 `cosec:publish`。其他变更不能用 `cosec:breakglass` 代替。
- **回滚可以带 `confirmations`**，恢复带 `onMissing: "nomatch"` 的 DENY 时与提议相同。此前这样的回滚根本无法确认。
- **`repair-references` 也会删除**未能写入头的发布登记的条目，并输出 `Removed <n> reference(s) no head refers to.`。见[修复反向引用索引](./upgrading.md#repair-the-back-reference-index)。

- **`cosec-model`、`cosec-engine` 和 `cosec-spi` 的公开 API 已按 8.0 归类**（R9、RFC 0010 C4）：仍然公开的，就是 8.0 保持稳定的。应用会注意到：
  - `ApiKeyStore` 已删除。请求通过 `ApiKeyReader` 读取 key。管理 key 请用 `InMemoryApiKeyStore` 或 `RedisApiKeyStore` 这个 Bean，它们的方法不变；按 Bean 自己的类型注入。流水线从不读取应用自己声明的 `ApiKeyStore` Bean：要从别处查找 key，请声明 `ApiKeyReader`。
  - 评估轨迹换了包，并且是实验性的。`me.ahoo.cosec.engine.flat.FlatTrace` 改为 `me.ahoo.cosec.engine.trace.EvaluationTrace`，`CandidateTrace` 和 `CandidateOutcome` 在同一个包中。这些类型和 `DecisionEvent.evaluationTrace` 是 `@ExperimentalCoSecApi`（用 `@OptIn(ExperimentalCoSecApi::class)` 选择使用），构造器是内部的。`ViewVersion` 和 `AttributeStatus` 在 `me.ahoo.cosec.engine` 中，保持稳定。审计记录及其 JSON 不变。
  - `RevocationUnavailableException` 继承 `CoSecException`，与 `MembershipUnavailableException` 一样。把所有 `CoSecException`（一种 `SecurityException`）都答成 `401` 的处理器现在也会捕获它：请先处理它，它表示吊销存储不可用。
  - `RateLimitResult` 不再是 `data class`：没有了 `copy` 和 `componentN`，相等性不变。
  - `SnapshotDocument.DOCUMENT_SCHEMA` 不再是 `const`，已编译的代码读取的是运行时 CoSec 的 schema。用它初始化的 `const val` 不再能编译：改为 `val`。
  - **`SnapshotDocument` 由它的字节构造**（`SNP-12`）：构造函数现在是内部的。把 `SnapshotDocument(schema, revision, publishedAt, bytes)` 换成 `SnapshotDocuments.read(bytes)`（`cosec-document`），它从字节中读取头部，并拒绝不是文档的字节。存储的字节不变。
  - 以下现在是内部 API（`@InternalCoSecApi`）：`Environment`、条件字面量（`Literal`）、`Attribute` 及其 `Kind`、`ScopeResolver`、`ScopeSubject`、`SubjectRevocation`、`IdentityFormatException`、`IdentityOverflowException` 和 `Identifiers.isUnpairedSurrogateAt`。`ScopeResolution.Resolved` 和 `ScopeResolution.Rejected` 的构造器是内部的；`ScopeResolution.Rejected.Reason` 保留。

- **`cosec-runtime` 和 Web 适配器的公开 API 已按 8.0 归类**（R9）。使用 starter 时运行时没有变化；在代码中：
  - 不使用 Spring 组装管道是实验性的：`Pipeline.builder` 和 `PipelineBuilder`、`Pipeline.evaluate`、`RequestGate`、`HttpFacts`、`Headers`、`Cookies`、`Outcome`、`Problem`、`ProblemEncoder` 和 `RequestSecurity` 是 `@ExperimentalCoSecApi`：用 `@OptIn(ExperimentalCoSecApi::class)` 选择使用。`PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、`IdentityIssuer`、`IdentityGate` 和 `CoSecParameters.read` 是 `@InternalCoSecApi`（不能选择使用），`Pipeline` 的构造器和 `PipelineComponents` 是 `internal`；它们起初是实验性的，后来由 `Pipeline.builder` 取代。用这种方式构建管道的测试改从 `PipelineFixture`（`cosec-testkit`）获得管道。`Pipeline` 本身、`AuthorizationChecker(pipeline)`、`ProblemType` 和 `CoSecParameters` 的请求头名保持稳定。
  - 以下现在是内部 API（`@InternalCoSecApi`）：`IdentityCodec`（契约是 [Web 集成](../integration/web.md)中的 `CoSec-Identity` 格式）、`RemoteIpResolver`、`PathNormalizer`、`HttpAttributes`、`PolicyDocument.attributes`、`PolicyDocumentCodec.encode`、`ATTRIBUTE_TYPES`、`WILDCARD` 和 `SCHEMA`、`PolicyDocumentCompiler` 的上限、`PERMISSION_PREFIX` 和 `permissionPrefix`，以及接收 `RequestSecurity` 的 `AuthorizationChecker.check` 和 `decide`（接收 `SecurityContext` 与字符串的保留）。`PipelineWebFilter`、`PipelineServletFilter` 和 `PipelineGatewayFilter` 的构造器（使用 starter 的 Bean，不要自己声明）、`LoggingAuditEventSink(KLogger)`（无参构造器保留）和 `MetricsListener.bind(registry, listeners)` 也是。`RouteResolution.Mapped` 和 `Invalid` 的构造器是内部的：由 `RouteMap.resolve` 给出。
  - `InstanceStatus.FORMAT_LEVEL` 不再是 `const`。

- **`cosec-control` 和 `cosec-cocache` 的公开 API 已按 8.0 归类**（R9）：
  - `ControlStore`、`InMemoryControlStore`、`CoCacheControlStore` 和存储保存的记录（`ChangeRequest`、`ChangeRequestCodec`、`ChangeState`、`Base`、`Approval`、`CheckedApps`、`Finding`、`Severity`、`Operation`、`ShadowState`、`StageSlot`、`AppList`、`PendingRecord`、`RevisionEntry`、`RevisionIndex`、`StoredDocument`、`Versioned`、`HeadWrite`），连同影子窗口端口及其报告（`CandidateStore`、`ShadowReportSink`、`ShadowReportReader`、`CoCacheShadowReports`、`InstanceStatus`、`InstalledDocument`、`ShadowReport`、`ShadowSample`、`StoredCandidate`、`ShadowOutcome`）和 `ProvenanceKind`，是 `@ExperimentalCoSecApi`：自己实现的存储需要选择使用。`ChangeListener`、`ChangeEvent` 及其携带的内容保持稳定；`Escalation` 和 `Confirmation` 不再是 `data class`（没有 `copy` 和 `componentN`，相等性不变）。
  - CoCache 缓存（`RevokedTokenCache`、`RevokedSubjectCache`、`MembershipCache`、`PolicySnapshotCache`）是 `@InternalCoSecApi`，`cosec-cocache` 中接收 CoCache 或 Spring Data 类型的构造器和工厂（`CoCacheTokenStore`、`CoCacheMembershipProvider`、`CoCacheTokenFamilyStore`、`RedisApiKeyReader`、`RedisApiKeyStore`、`ReactiveRedisRateLimiter`、`RedisSnapshotDocumentStore` 及其摘要辅助函数、`CoCacheControlStore.create`、`CoCacheShadowReports.create`）以及 `DEFAULT_*_KEY_PREFIX` 常量也是。这些缓存只能由本来就是内部的 `CoSecCacheSpecs` 构建：starter 构建它们，应用按 Bean 的类型（`RedisApiKeyStore`、`CoCacheMembershipProvider` 等）使用。`cocache.caches.[<CacheName>]` 中的缓存名不变。
- **`LocalRateLimiter` 保留每一个仍可能拒绝请求的计数，满了就无法计数**（`OBL-5`、`OBL-6`）。它曾丢弃空闲一小时的桶，于是窗口更长（最长一天）的规则在客户端停顿一小时后又放它进来；它也曾按容量逐出桶，于是某条规则上大量新键会重置所有规则的计数。现在桶只有在空闲满一小时、且空闲满其规则的两个窗口后才会被移除，且从不为腾出空间而移除。限流器总共最多 100,000 个桶，单条规则最多 50,000 个；超出任一上限的新桶无法计数，由规则的 `strictFailure` 决定：严格规则返回 `503`（`RateLimiterUnavailable`），宽松规则被跳过。以前它会从零开始计数。如果一条以客户端可以变化的内容为键的规则现在返回 `503`，请改用主体或可信地址作为键（[本地限流器有上限](../policies/rate-limiting.md#the-local-limiter-is-bounded)）。`LocalRateLimiter` 的构造器多了第二个参数 `maximumBucketsPerRule`，位于 `expireAfterAccess` 和 `clock` 之前：按名传参的调用不受影响，按位置传参的需要修改。`cosec-runtime` 不再引入 Guava：经由 CoSec 使用它的应用需要自己声明。

- **starter、`cosec-login`、`cosec-social` 和 Web 适配器的公开 API 已按 8.0 归类**（R9）：
  - **`cosec-login` 的 `Credentials` 改为 `LoginCredentials`，`Authentication.supportCredentials` 改为 `credentialsType`。** 一个产品里有两个公开的 `Credentials`，指的却是不同的东西：管道的那个（`me.ahoo.cosec.spi.authn.Credentials`，请求的令牌或 API key）保留原名。你自己的 `Authentication` 这样改：

    ```kotlin
    // 之前
    data class PasswordCredentials(val username: String, val password: String) : Credentials
    class PasswordAuthentication : Authentication<PasswordCredentials> {
        override val supportCredentials = PasswordCredentials::class.java
        // ...
    }
    // 之后
    data class PasswordCredentials(val username: String, val password: String) : LoginCredentials
    class PasswordAuthentication : Authentication<PasswordCredentials> {
        override val credentialsType = PasswordCredentials::class.java
        // ...
    }
    ```

  - `AbstractRefreshTokenAuthentication` 已删除：`SimpleRefreshTokenAuthentication`（现在是 `RefreshTokenAuthentication`，见上）自己实现 `Authentication<RefreshTokenCredentials>`。`AuthenticationProvider`、`DefaultAuthenticationProvider` 和 `CompositeAuthentication` 的构造器是 `@InternalCoSecApi`：starter 注册每个 `Authentication` Bean。
  - `cosec-social`：`SocialProviderManager` 和 `SocialAuthenticationProvider` 是 `@InternalCoSecApi`，`SocialAuthentication` 和 `JustAuthProvider` 的构造器（后者用到 JustAuth 的 `AuthRequest`）也是。`JustAuthProvider.name()` 已删除：读取 `name`。
  - starter 的 `*Properties` 类和 `EnabledCapable` 是 `@InternalCoSecApi`，`@ConditionalOn*` 注解是 `internal`。契约是 `cosec.*` 配置键，它们不变。读取配置值请用 `@Value("\${cosec.jwt.enabled}")` 或 `Environment`，不要注入 CoSec 的 `*Properties` Bean；给你自己的 Bean 加条件请用 `@ConditionalOnProperty("cosec.jwt.enabled")`，不要用 `@ConditionalOnJwtEnabled`。
  - `checkMono`（`cosec-webflux`）和 `checkBlocking`（`cosec-webmvc`）位于名为 `AuthorizationCheckers` 的 Java 类中，不再是 `PipelineWebFilterKt` 和 `PipelineServletFilterKt`；Kotlin 调用方不受影响。`SuspendingHandlerHolderInterceptor` 是 `@InternalCoSecApi`：starter 会注册它。
- **`cosec-dependencies` 不再管理 Guava 的版本**，CoSec 的模块也都不再引入 Guava。从 CoSec 的 BOM 取 Guava 版本的应用需要自己声明。

- **`ChangeState.SHADOWED` 已删除。** 没有任何变更处于过这个状态：影子窗口关闭不是一个状态，而是变更的 `shadow`（`ShadowState.closed`）。点名 `ChangeState.SHADOWED` 的代码（`when`、按状态的过滤、测试）不再编译：删掉该分支。存储的变更请求不可能带它，`"state":"SHADOWED"` 也无法解码；没有任何版本写过。现有状态为 `DRAFT`、`CHECKED`、`SHADOWING`、`AWAITING_APPROVAL`、`READY`、`CONFLICTED`、`PUBLISHED`、`REJECTED`、`ABANDONED` 和 `EXPIRED`。变更事件的 `SHADOWED`（"窗口已关闭"）保留，其日志行也保留。
- **一个文档族最多持有其上限加上明确的余量**（`SNP-14`）。冷加载突发时，一个族可能持有约 `cosec.authorization.snapshot.max-apps` 或 `max-tenants` 再加上后台线程排队的 4,096 个驱逐。现在它最多持有上限的两倍，且绝不超出上限 4,096 个以上，另加此刻正在加载的文档；安装时发现族超出这个数的，在安装线程上先驱逐再应答。无需配置，没有默认值改变；按旧的最坏情况做的内存预算依然安全。[这个属性](./configuration.md#snapshot)写明了这个数。
- **`Retry-After` 与 `RateLimit-Reset` 向上取整，低于一毫秒的上报间隔使启动失败。** 窗口还剩 1.75 秒时，`429` 曾答 `1`，按此等待的客户端会撞上同一个窗口；两个头现在都是剩余时间的整秒数，向上取整，至少为 `1`（`1 ms` 与 `1000 ms` 为 `1`，`1001 ms` 为 `2`）。无需处理：客户端最多多等一秒，`RateLimit-Remaining` 和 `-Limit` 不变。starter 现在拒绝低于 `1ms` 的 `cosec.authorization.shadow.report-interval`（`cosec.authorization.shadow.report-interval is at least one millisecond, not …`），此前它会启动一个永不挂起的上报循环：请设为 `1ms` 或更大（默认 `10s`）。
- **此前的 `cosec-migrate bootstrap` 丢弃平台租户的角色。** 它只用策略、绑定和限流规则重建平台租户的文档（`tenant:(platform)`），所以它发布的 `BOOTSTRAP` 修订没有 `roles`。把 7.x 角色转换进该文档、再运行 `bootstrap`（带或不带 `--force`）的迁移丢掉了它们，这些角色的成员随后被拒绝：失败即拒绝，绝不放开。现在它保留文档的其余全部内容。如果你在转换角色之后运行过更早的 `bootstrap`，请以管理员身份用控制面 API 检查该文档：找到 `provenance.kind` 为 `BOOTSTRAP` 的修订（`GET /documents/tenant:(platform)/revisions`，再 `GET …/revisions/<n>`），然后读 `GET /documents/tenant:(platform)/diff?from=<n-1>&to=<n>`：`roles.removed` 非空，就是 bootstrap 丢掉的角色。用一次变更把它们放回去（`putRole` 操作，定义取自修订 `<n-1>`）。先 bootstrap 后转换角色、或平台租户没有角色的部署，无需处理。
- **`ControlStore` 新增 `stagedCandidate`，`putCandidate` 按摘要写入。** 候选只会覆盖写入者读到的那一份，所以被抢占的变更迟到的写入不会替换占了槽位的那个变更的候选（#1235）。`suspend fun stagedCandidate(key: SnapshotKey): Versioned<StoredCandidate>?` 是新增的；`putCandidate(key, expected: SnapshotDigest?, candidate: StoredCandidate): Boolean` 接收它期望的摘要（`null`：仅当还没有候选时写入），期间候选变了就答 `false`。自己的 `ControlStore` 要实现这两个（`CoCacheControlStore` 与 `InMemoryControlStore` 都实现了）；写入结果未知时回读，而不是再写一次。使用 starter 的 Redis 存储无需处理。
- **`cosec-migrate` 命令行更严格，并接受 `--name=value`。** `--name value` 与 `--name=value` 都可以；以 `--` 开头的值是用法错误（此前下一个选项会被当作值，它自己的值被丢掉）：这样的值请写 `--name=--value`。重复给出的开关是 `--flag is given twice.`，给开关加值是 `--flag takes no value.`，重复或未知的选项是用法错误。依赖某个选项吞掉下一个参数的脚本现在会失败：请修正它的参数。
- **控制面 API 的路径参数只做一次百分号解码。** `GET /documents/tenant:acme%20west` 曾读取键 `tenant:acme%20west`，它不是存储和请求体使用的 `tenant:acme west` 那份文档；现在先把路径拆成段，再对每个参数解码一次（`+` 仍是 `+`，编码的 `/` 是值的一部分、不会成为第二段，`tenant%3Aacme` 即 `tenant:acme`，路由自己的词按原样匹配）。对 id 编码了两次、或发送编码后的 id 并指望它原样到达控制面的客户端，现在指向另一份文档：只编码一次。
- **访问审查列出破窗绑定。** `POST /analyze` 曾把破窗绑定（它的策略 `cosec:root` 不由任何文档定义）算作 `opaque`。现在它是 `allows` 中的一项，来自引擎自己的语句的 `cosec:root` 的 allow，带 `breakGlass: true`、`expiresAt` 和 `conditional: true`（审查不求值窗口）；`conditional` 现在也标记任何带窗口的绑定。解析审查结果的客户端会看到 `opaque` 减少，平台租户的审查里多出 `cosec:root` 的 `allows`；把每个 allow 都当作永久的工具应读取 `conditional` 与 `expiresAt`。
- **生成的路由映射按操作取服务器前缀，并拒绝它放不下的内容。** 前缀取自操作自己的 `servers`，没有则取其路径项的，再没有则取文档的；服务器 URL 按组成部分读取：`//example.test/v1`、`https://example.test:8443/v1` 和 `/v1` 都给出 `/v1`。此前能生成路由映射的文档，现在可能被拒绝，并点名操作（`GET /orders: …`）：一个操作的服务器路径不同、路径中有服务器变量、服务器路径不以 `/` 开头（`v1`、`example.test:8080/v1`、`{baseUrl}/v1`：它相对于文档被获取的位置，生成器不知道）或含点段。请重新生成路由映射；给被拒绝的文档写绝对的服务器 URL，或以 `/` 开头的路径。
- **存储的、无法编译的文档会被点名、记录和计数。** `cosec-model` 中的 `Cause` 新增 `DocumentInvalid(document, revision, error)`，所以对 `Cause` 做穷尽 `when` 的应用不再编译：补上分支。决策仍是 `503`（实例无法决策）；`SourceUnavailable` 现在表示存储无法读取。实例对每个修订、摘要和输入记录一行 `ERROR`，重新可编译时记录一行 `INFO`；`cosecSnapshot` 健康指示器给出 `invalid`（无法编译的文档数）和 `invalidFirst`（前十个及其存储的修订）（[快照健康状况](./observability.md#snapshot-health)）。除 `when` 外无需处理；原先靠"没有日志"发现这种情况的，请改为对这行日志告警。
- **请求不再记录被丢弃的决策事件。** BEST_EFFORT 监听器的队列已满或已关闭时，请求只计数；一个上报协程在请求线程之外，对每个监听器每个间隔记录一行 `WARN`（`Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.`，或 `is closed`）。原先每个被丢弃的事件一行的日志告警，现在每个间隔只看到一个计数。
- **`TraceContext` 新增 `traceState`。** 父 span 的 W3C `tracestate`，不透明，没有则为 `""`；三个参数的构造器保留。`cosec.authorize` span 继承它，此前从默认状态开始。它随事件到达每个 `DecisionListener`，是厂商数据：不要记录它。审计记录、Kafka 记录和指标标签都不带它。
- **`cosec-testkit` 的签名增加。** `PermittedRequest.withToken(token, verifier, revocations, headers)` 接收请求头（默认空）；携带凭证的头（`Authorization`、`X-API-Key`、`CoSec-Identity`）被拒绝，两个不同的 `CoSec-Device-Id` 值会像管道那样使请求失败。`PipelineFixture.of` 与 `load` 接收 `routes` 和 `listeners`（默认没有），`AttributeRequests` 为提供者的测试构造 `AttributeRequest`。测试工具的异常带原因，并拒绝它读取内容中重复的字段，以及两个存放同一份文档的文件。源码兼容（有默认值），二进制不兼容：请针对这个版本重新编译测试。

## 从 8.0.0-alpha.3 升级 {#from-8-0-0-alpha-3}

8.0.0-alpha.3 是预览版，没有兼容性承诺。它保存的数据仍然可读，只有反向引用集合、已批准的变更以及用其他拼法写星期的文档例外（见下）。

- **控制面规模**（批次 E2b：#1214、#1217、#1218、#1219，以及 #1290 留下的暂存窗口）。实例把自己的状态写在自己的 key
  `<s>:shadow:status:<instance>` 下，并登记在 `<s>:shadow:instances:all` 中，所以一次心跳只写一个状态，与实例数无关；旧的
  `<s>:shadow:status:instances` 不再被读取，两分钟后过期。实例升到这个版本之前，读取某个修订的传播情况时不会计入它（只读；
  发布从不等它）：请一起升级数据面实例，并把 `~<s>:shadow:instances:*` 加入实例 Redis ACL 的 key 模式
  （[Redis key](./storage.md#redis-keys)）。同一个变更的所有决定基于对控制面文档的一次确认，结束时再确认一次，大变更不再为每个条目
  读两次。代码方面：自己实现的 `ControlStore` 从 `openChanges()` 返回未结束的变更请求（`List<ChangeRequest>`，每个只读一次），并把
  `putCandidate(key, candidate)` 换成 `stagedCandidate(key)` 与 `putCandidate(key, expected, candidate): Boolean`，即在读到的候选之上写入
  （[扩展](../architecture/extending.md#control-store)）。`CoCacheShadowReports` 的第四个存储是实例列表。`SnapshotDigest.toString()`
  和条目摘要不变，只是更快。
- **审计记录说明是什么做出了决定**（批次 R7a，#1237）。`authorization.source` 现在是 `{ via, document, policy, statement }`
  （[审计事件](./observability.md#audit-events)）：旧的 `type` 把租户的角色绑定和边界都记成 `GLOBAL_POLICY`。边界拒绝过去的来源是
  `NONE`、没有 `binding`，现在 `via` 为 `BOUNDARY`，`policy` 是该边界，`binding` 为 `boundary:<tenantId>`。审计日志和 Kafka topic
  的消费方随之修改：

  | 之前 | 现在 |
  |---|---|
  | `source.type` `BLACKLIST` | `source.via` `BUILTIN` |
  | `source.type` `ROOT` | `source.via` `BINDING`、`source.policy` `cosec:root`（break-glass 放行还带 `breakGlass: true`） |
  | `source.type` `ROLE_PERMISSION` | `source.via` `BINDING`、`source.document` `APP` |
  | `source.type` `PRINCIPAL_POLICY` | `source.via` `ATTACHMENT` |
  | `source.type` `GLOBAL_POLICY` | `source.via` `BINDING`、`source.document` `PLATFORM` 或 `TENANT` |
  | `source.policy.id` | `source.policy` |
  | `source.policy.statement` | `source.statement` |
  | `source.policy.type`、`source.role`、`principal.policies`、`request.device.userAgent` | 已删除：`type` 只是重复 `source.type`，其余的总是空的 |

  代码层面：`AuditSourceType`、`AuditPolicy`、`AuditRole`、`AuditPermission` 及其数据类、`PolicyType` 和
  `AuditReasonCode.REGEX_TIMEOUT` 已删除，新增 `AuditVia` 和 `AuditDocument`，没有人读取的属性键
  `AuditTrace.TRACE_ID_ATTRIBUTE_KEY` 和 `SPAN_ID_ATTRIBUTE_KEY` 也已移除。
- **关闭时等待 Kafka 审计**（批次 R7a，#1250）。`KafkaAuditListener` 最多等待 `cosec.observability.listener.drain-timeout`，
  让它手中的发送和 broker 的应答完成，之后才关闭 producer。一次关闭可能用掉两倍这段时间（一次给监听器的队列，一次给这里）：
  请检查 pod 的终止宽限期。
- **`authorization` cookie 只在明确开启时读取**（批次 R6、`PIP-13`）。唯一凭证是这个 cookie 的请求现在是匿名请求。浏览器会自动带上 cookie，跨站请求也不例外，默认读取它等于让每个部署都要应对 CSRF。把令牌放在 `Authorization` 请求头中，或者设置 `cosec.pipeline.allow-cookie-token=true` 并自行防御 CSRF（[认证](../integration/authentication.md)）。无论是否读取，网关都会从转发的请求中移除这个 cookie。
- **`CoSec-App-Id`、`CoSec-Space-Id` 和 `CoSec-Device-Id` 只读请求头**（批次 R6、`SCP-4`）。同名查询参数不再被读取：它不设置任何值，也不再让请求产生歧义。把它们放在 URL 中的客户端改为发送请求头（[应用与空间](../integration/web.md#app-and-space)）。
- **未知的 `cosec.*` key 总是让启动失败**（批次 R6、`SNP-9`）。`cosec.unknown-properties` 和 `UnknownPropertiesMode` 已删除，设置这个配置项本身现在就是未知 key：请移除它。错误信息列出这些 key，并链接到[已删除和改名的 key 对照表](./upgrading.md#removed-and-renamed-properties)，不再逐个给出替代项。
- **没有 `format` 的已存储 API key 和其他错误格式一样失败**（批次 R6）：消息是 `API key format [null] is not 2.`。这样的 key 原本就被拒绝；`cosec-migrate api-keys` 仍然可以转换它。
- **角色和简写权限**（RFC 0012）。目录条目可以用 `action` 和 `resource` 定义权限，应用和租户文档可以有 `roles`（[角色权限](../policies/role-permissions.md#roles)）。`schema` 仍是 2，已发布内容的含义不变。旧实例读不了带这些字段的文档：严格读取器拒绝未知字段，按失败关闭处理。**先升级全部数据面实例，再发布这样的文档**。变更不能再写入 id 以 `role:` 开头的策略或绑定，已有的仍可读。`GET /documents/{key}/diff` 多了 `roles`，策略和绑定按展开后的文档报告。`POST /analyze` 不再把租户对可读应用权限的绑定算作 opaque。代码方面：`Operation` 增加 `PutRole` 和 `RemoveRole`，`DocumentDiff`、`CatalogPermission` 和 `PolicyDocument` 增加了字段。
- **控制面的正确性**（审计批次 E2a：#1205、#1206、#1207、#1210、#1226、#1227）。修订 diff 的 `sections` 按内容比较目录、限制、属性、边界和限流规则：同 id 的规则改了额度也算变化。`POST /analyze` 只列出能在所查询租户生效的绑定，所以平台中给别的租户的主体或角色的绑定不再列出。候选撤回、最后一次报告写入之后，实例忘掉这个影子窗口。代码方面：自己实现的 `ControlStore` 要实现 `withdrawCandidate(key, window)`（`putCandidate` 只接受候选，不再接受 `null`）、带每个引用方修订的 `referrers` 与 `putReferrers`（`Map<SnapshotKey, Long>`），并保存 `RevisionIndex.reclaiming`；`releaseLease` 必须在一步中检查并删除（[扩展](../architecture/extending.md#control-store)）。`ShadowRecorder.record` 的第一个参数是窗口的文档，`forget` 接受它最后一次报告计入的样本数，`ShadowReporter` 接受它读取已结束窗口所用的 `CandidateStore`。
- **控制面实例一起升级，并重建反向引用集合**（批次 R4）。反向引用集合（`<p>:control:refs:*`）只能按对象形式读取：每个引用方及其修订。alpha.3 写入的文档 key 的 JSON 数组是格式错误：涉及它的发布会失败，而 `cosec-migrate repair-references` 写之前要先读集合，所以也越不过它。先停掉 alpha.3 的控制面实例，删除这些 key，运行这个版本的 `cosec-migrate repair-references`（它按 head 重新写入每个集合，带上每个引用方的修订），然后才启动这个版本的控制面实例。alpha.3 的控制面实例读不了这样写入的集合，也读不了列有待删记录的修订索引，遇到它们即按失败关闭处理：它涉及这些集合的发布会失败，对该文档的回收会停止。数据面实例不必按顺序：alpha.3 的实例只是会一直写已结束影子窗口的报告。
- **存储的批准与确认只有一种形状**（批次 R4）。存储的批准总是带 `memberships`、`attributes`、`principalBindings`、`breakGlass`、`credentialId` 和 `issuedAt`，没有时为 `null`，缺少这些字段就是格式错误。升级前请发布或放弃每个带批准的未结束变更，或者在升级后移除它的记录（`<p>:control:change:<id>`）：只要这样的变更还开着，它就回答 `503`，每一轮维护都会停下，什么都不回收。`deny-nomatch` 确认的摘要总是基于 `{statement, policyCondition}` 计算，所以升级前对没有条件的策略给出的确认不再匹配：对应的变更会被拒绝，直到重新确认该语句（`428`）。已发布的文档保留它的确认。
- **星期只有一种拼法**（批次 R5）。`daysOfWeek` 只接受 `MON`、`TUE`、`WED`、`THU`、`FRI`、`SAT` 和 `SUN`，这也是 schema 和[策略语言](../policies/language.md)一直列出的名称。`MONDAY`、`mon` 和 ` Fri ` 不再能编译：写入它们的变更被拒绝，保存了它们的文档被安装为失败，读取它的请求回答 `503`。升级前请把这些文档改用三字母名称发布。`cosec-migrate` 现在写三字母名称，并且仍然读取 7.x 接受的任何拼法。
- **`tenantId` 声明为空时令牌无效**（批次 R5、`IDN-11`），过去它表示默认租户 `(0)`：读作没有租户，会把主体连同令牌的角色放进默认租户。请求按匿名评估，拒绝时回答 `401 token-invalid`，刷新这样的令牌也会失败。没有 `tenantId` 声明的令牌仍属于默认租户。自定义 `TokenVerifier` 的主体遵守同一规则；主体的租户 id 为空时，`JwtTokenConverter` 抛出 `IllegalArgumentException`：请不给它租户，或者用 `SimpleTenant.DEFAULT`。
- **限流计数器只有一种形式**（批次 R5）。每条规则按它的文档和 id 计数：平台规则从原来的裸 id 改为 `platform/<id>`，应用和租户规则不变（`app:<appId>/<id>`、`tenant:<tenantId>/<id>`）。按 key 计数的桶是 `k:<value>`，不再是 `g:<value>`。文档中的规则 id 不变。Redis 计数器重新开始，所以每条规则的当前窗口从完整额度开始，两个版本同时服务时各自计数。你自己的 `RateLimiter`，以及读取 `Cause.RateLimiterUnavailable.ruleId` 或 `Basis.RateLimited.ruleId` 的代码，会看到新的名称。

- **三组配置项改名。** 旧 key 会作为未知配置项使启动失败：

  | 旧 key | 新 key |
  |---|---|
  | `cosec.pipeline.gateway.max-age`、`cosec.pipeline.gateway.max-size` | `cosec.pipeline.identity.max-age`、`cosec.pipeline.identity.max-size` |
  | `cosec.audit.required.timeout` | `cosec.observability.listener.required-timeout` |
  | `cosec.authorization.cache.token.*` | `cosec.authentication.revocation.cache.*` |

- **路由映射改为显式启用。** `cosec.authorization.routes.location` 默认未设置，未设置的宿主不加载路由映射，所以没有映射的 WebFlux 或 Spring MVC 服务无需任何配置。依赖默认读取 `classpath:cosec-routes.json` 的服务会启动失败，报错说明修法：把配置项设为该位置。没有路由的映射不再能加载（[路由映射](../integration/web.md#route-maps)）。
- **`cosec.pipeline.mode=gateway` 时 `cosec.jwt.enabled` 默认为 `false`。** 网关之后也签发令牌的服务要设为 `true`；否则注入 `TokenConverter` 的地方找不到它。`enforce` 模式不变。
- **`cosec:tenant-admin` 也持有 `cosec:skip-shadow`，且只在自己租户的文档上**（`CTL-27`），这扩大了租户管理员能做的事：它可以带着记录的原因跳过窗口，只收窄的变更也能抢占该文档上被占用的槽位。授出分析和审批不变。要保持原来的行为，改为绑定你自己的策略（[控制面](../policies/testing.md#the-control-plane)）。
- **有 `cosec-cocache` 而没有 `CacheRuntime` 时启动失败**，并点名 `me.ahoo.cocache:cocache-spring-boot-starter`。这种组合本来就不能工作：结果是误导性的报错，或永远不就绪的实例。
- **`cosecServiceGenerator` 端点读取 springdoc 的扫描结果**，需要 `springdoc-openapi-starter-webflux-api` 或 `-webmvc-api`。只有一个 `OpenAPI` bean 不再能让它生成；扫描不到任何操作时报错，而不是返回空文档。
- **`AuthorizationChecker.decideAs` 和 `decide(security, app, action, resource)` 改为 `@InternalCoSecApi`**：它们属于控制面，应用不要启用。按请求自身的主体检查，用 `check` 和 `decide`（[代码中的检查](../integration/checks.md)）。

## 从 8.0.0-alpha.2 升级 {#from-8-0-0-alpha-2}

8.0.0-alpha.2 是预览版，没有兼容性承诺。此后来自 [RC 安全评审](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md)的以下变更，让授权分析按执行时的方式读取作者的权限，因此一些过去可以单独发布的变更现在要等待审批（[策略测试](../policies/testing.md#approval)）。文档的内容无需迁移，不过 8.0.0 经 CoCache 8 保存数据，而 CoCache 8 读不了 alpha.2 经 CoCache 6 写入的内容：请在所有实例停止后用 8.0.0-alpha.4 的 `cosec-migrate cocache8` 迁移，它是最后一个带有这条命令的版本（[经 CoCache 6 写入的数据](#data-written-through-cocache-6)）。从 **身份转发** 开始的那些变更涉及网关与租户隔离的边缘（RC1-5、RC1-6、RC1-15 至 RC1-18、RC1-48、RC1-49），改变网关转发什么、转发给哪些路由：升级网关之前请先阅读。

- **从 8.0.0-alpha.2 升级的存储，运行一次 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)**，让 app 文档列表（`<s>:control:list:documents`）完整（[存储](./storage.md#publishing-through-the-control-plane)）。
- **任意类型的 token 属性**（RC1-2、`GRT-5`）。分析按引擎的方式读取 `subject.attributes.*`，布尔值和数字读作它们的文本。token 中写着 `"suspended": true` 时，条件为未被 `suspended` 的 ALLOW 不再算作作者持有。代码中，`Operator.attributes` 与 `Grantor.attributes` 是 `Map<String, Any>`，即 token 声明原样，alpha.2 只保留了字符串。
- **会到期的绑定**（RC1-3、`GRT-3`）。作者经带 `expiresAt` 的绑定持有的权限，只包含自身绑定不晚于它到期的授出；没有 `expiresAt` 的授出、到期更晚的授出、把策略设为可附加，都是升级，短缺为 `EXPIRY`。
- **删除平台也定义了同一 id 的租户策略**（RC1-9、`GRT-4`）按把平台的策略授给租户中该 id 的绑定与附加来分析。两边都定义的 id 不给作者任何权限。
- **每一份 app 文档**（RC1-4、`GRT-6`）。租户变更的分析读取 app 列表（`<s>:control:apps:all`，[存储](./storage.md#publishing-through-the-control-plane)）列出的每一份 app 文档，而不只是变更点名的那些，所以 app 绑定给作者某个角色的 DENY，会在触及该 app 的每个作用域中限制作者可以授出的东西。读不到的 app 文档使这样的授出成为升级（`UNREADABLE`）。分析只在 app 列表完整时信任它，而升级上来的存储的列表并不完整：**升级后，在 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index) 运行之前，覆盖所有 app 的每个租户授出（app 为 `*` 的绑定，或附加，包括经条件收窄到变更没有点名的 app 的授出）都需要审批**（`UNREADABLE`），控制面会记一条点名该命令的 WARN，每分钟至多一次。
- **平台策略不能占用租户的 id**（RC1-9、`CTL-21`）。平台变更新增的策略 id 若已被某个租户文档定义或绑定，变更被拒绝（`V2`、`422`，列出这些租户文档）。自己定义了该 id 的租户，不再阻止平台删除它自己的同 id 策略。反向引用索引只记下租户在升级之后新增的 id；租户文档原有的 id 只有经 `repair-references` 才进入索引：**在每个控制面实例和每个 `cosec-migrate` 二进制都运行这个版本之后，以 Kubernetes Job 运行一次 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)**，使用与控制面相同的 Redis 和 key 前缀；任何一次降级之后再运行一次。在它运行之前，平台策略若占用了某个租户在升级前就有的自有策略的 id，不会被拒绝，只会在那个租户中二义，执行时对此失败即拒绝。
- **升级**新增原因 `UNRESOLVED`（新绑定指向没有任何文档定义的策略，RC1-32）、`UNREADABLE`（RC1-4）与 `TENANT_BINDING`（应用文档的变更放宽了的每条租户绑定，RC1-34、`APR-6`）。`TENANT_BINDING` 仅供参考：应用变更因 `NOT_ANALYZED` 而需要审批，这一项只写出绑定和它所在的租户文档。穷举 `UnknownReason` 或 `Shortfall` 的代码要加上 `UNRESOLVED`、`UNREADABLE` 与 `EXPIRY`。
- **发布后的头变化**（RC1-33）。变更检查时读过的其他文档或 app 列表在它写入之前变化时，控制面在 `PUBLISHED` 之后记 `HEADS_MOVED` 或 `REVIEW_NEEDED`，变化的头在新成员 `moved` 中（[一次发布比较什么](./storage.md#what-a-publish-compares)）。处理每一种 `ChangeEvent.Kind` 的变更监听器要加上这两种。
- **信任根属于平台**（RC1-10、`APR-3`）。只有持有 `cosec:approve` 的平台主体发布自己的变更时不经分析；平台授予了 `cosec:approve` 的租户主体，它自己的变更现在要经分析，它审批别人的变更。租户的边界属于平台：租户主体的 `SetBoundary` 返回 `403`，它以 `REPLACE` 做的 `apply` 或回滚如果会删除或改变平台设定的边界，同样返回 `403`；也只有平台主体能审批、驳回或放弃设置边界的变更（`BDY-2`）。API key 从来不是信任根，即使其主体是持有 `cosec:approve` 的平台主体；任何名为 `api-key` 的凭证（包括转发来的身份所带的）也不是。开启 `four-eyes` 时，平台主体的变更需要另一位平台主体审批。
- **`cosec:platform-admin` 只由平台绑定**（RC1-36、`TNT-2`）：在平台文档中，或在平台租户自己的文档中（`bootstrap` 就写在这里）。绑定它的租户或 app 文档不再能编译；升级前请删除这样的绑定。
- **审批是人的行为，并会再次核查**（RC1-11、`APR-7`、`APR-8`）。以 API key 认证的主体不能审批（`403`）。审批记录审批人的凭证（`jti`、`iat`）；变更发布之前吊销这个令牌，或吊销审批人的令牌，审批就不再算数。成员关系来自成员关系提供者的审批人，既要按它审批时持有且现在仍持有的角色被放行，也要按提供者现在的答复被放行；starter 现在把应用的 `MembershipProvider` 交给控制面（RC1-13）。**没有记下凭证 id 的审批不算数**：升级前审批过的每个变更，发布时都会回到 `AWAITING_APPROVAL`，需要重新审批；凭证不带 id 的主体不能审批（`403`）。API 中变更的视图（`GET /changes/{id}` 及各步骤的答复）不再显示审批的 `memberships`、`attributes`、`credentialId` 和 `issuedAt`。代码中，`AuthenticatedPrincipal` 多了 `issuedAt` 和 `apiKey`。
- **starter 检查审批如何被吊销。** 控制面和数据面管道一样需要吊销读取器：一个 `RevocationReader` bean，或 CoSec 的令牌加 `cosec-cocache`；两者都没有时启动失败（审批人的凭证从不吊销时，声明 `RevocationReader.NONE`）。吊销的保留时长 `cosec.jwt.token-validity.refresh` 短于 `cosec.control.approval.expire-after` 时，启动也会失败。
- **被占用的暂存槽可以被抢占**（RC1-12、`CTL-22`、`BRK-9`）：吊销 break-glass，以及持有 `cosec:skip-shadow` 的人提交的只收窄变更（[抢占被占用的暂存槽](../policies/testing.md#taking-a-busy-slot)）。原先持有槽的变更成为 `CONFLICTED`。答复丢失后重试的发布报告 `PUBLISHED`。
- **租户 `(0)` 中的 `Anyone`**（RC1-35、`GRT-7`）：这样的授出会作用于未认证的调用方，需要审批（`ANONYMOUS`）。穷举 `UnknownReason` 的代码要加上 `ANONYMOUS`。
- **控制面资源中的默认空间写作 `(default)`**（RC1-37、`SCP-9`），以 `(` 开头的空间 id 保留给 CoSec：声明这种空间的请求返回 `403`（`scope-rejected`），在这种空间中绑定的文档不能编译。为 `scope/<tenant>/<app>/-` 写的控制面策略现在指名为 `-` 的空间；默认空间请写 `scope/<tenant>/<app>/(default)`。**写在 `scope/*/*/-` 上的控制面 DENY 不再作用于默认空间**：升级前把它改写为 `scope/*/*/(default)`，否则它原先挡住的授出会被放行。空间 id 以 `(` 开头的绑定会让所在文档无法编译，这种空间中的请求也会被拒绝：升级前在每份文档中查找 `"space": "(`，把这样的绑定移到你自己的空间 id，并把该空间的成员关系一并迁过去。
- **break-glass**（RC1-27、RC1-29、RC1-30）：`cosec-migrate` 不写 break-glass 绑定（[root 变为 break-glass](./upgrading.md#root-becomes-break-glass)）；`BreakGlass.request` 自行派生绑定 id，不再有 `binding` 参数；审批 break-glass 需要 REQUIRED 变更监听器（否则 `503`，`BRK-3`）。
- **吊销 break-glass 按效果作答**（R4-1）。`DELETE /break-glass/{bindingId}` 只在该绑定在平台文档的头上不再生效之后才返回 `200`，无论是谁删除的。吊销后绑定仍在生效时，现在返回 `409`，`detail` 写明调用方的变更所处的状态（`CONFLICTED` 等），此前返回的是带着该变更的 `200`：把 `200` 当作"已吊销"而不看 `state` 的客户端，需要处理这个 `409`。平台文档中没有的绑定 id 现在返回 `404`；该 id 的绑定不是 break-glass 绑定时（无论是否到期）返回 `409`（`binding [<id>] is not a break-glass binding`）；两者都不提议任何变更，此前都返回带着 `DRAFT` 变更的 `200`（`BRK-11`）。被另一个吊销抢占，或遇到已变化的头的吊销，会再吊销一次，至多再试三次。
- **放弃变更**（RC1-51、`BRK-10`）。吊销仍在生效的 break-glass 绑定的变更只有其作者能放弃：其他人（包括被吊销的主体）得到 `403`。请改为再吊销一次该绑定，这会发布停住的吊销，或抢走它的暂存槽。每次放弃都记一条新的变更事件 `ABANDONED`；放弃 break-glass 变更需要 REQUIRED 变更监听器（否则 `503`）。处理每一种 `ChangeEvent.Kind` 的变更监听器要加上 `ABANDONED`。
- **身份转发改为按 scheme 选择加入**（RC1-49、`IDN-7`）。网关只把 `CoSec-Identity` 转发给指向已注册服务的路由（`uri: lb://...`、`lb:ws://...`），或元数据为 `cosec-identity: true` 的路由；`false` 仍然让任何路由都不接收，`true` 以外的值都按 `false` 处理。**经 `http://` 或 `https://` 指向你自己服务的路由不再收到身份，其后的服务看到的是匿名请求**：升级网关之前，给这些路由的元数据加上 `cosec-identity: true`，不要给指向第三方的路由加（[转发身份](../integration/web.md#forwarding-the-identity)）。请求属性 `cosec.identity.forward` 现在在每个被路由的请求上都是 `true` 或 `false`，过去只设为 `false`。
- **不向下游转发任何凭证**（RC1-5、`IDN-8`）。除 `Authorization` 外，网关还从它转发的每个请求中移除 API key 头（`cosec.pipeline.api-key-header`，无论名称设成什么）和 `authorization` 查询参数，不论网关自己是否从查询参数读令牌（只有 `cosec.pipeline.allow-query-token=true` 时才读，默认关闭）。自己读取它们的服务需要 `cosec.gateway.identity.forward-authorization=true`；打开后，它们现在只发往本会接收身份的路由（`lb://`，或 `cosec-identity: true`），因此指向第三方的路由一个也收不到。
- **`authorization` cookie 也留在网关**（`IDN-8`）。在同一个开关下，网关从它转发的每个 `Cookie` 头中移除它（名称比较不区分大小写），其他 cookie 保留；不再剩下任何 cookie 的头不发送。从这个 cookie 读取令牌的服务需要 `forward-authorization=true`，以及一条本会接收身份的路由。
- **路由指定它的应用**（RC1-6、`SCP-2`）。路由元数据 `cosec-app-id` 是该路由上请求的应用，优先于 `cosec.authorization.scope.app-id`：在这样的路由上，声明了其他应用的请求为 `403` `scope-rejected`（匿名调用方为 `401`），没有声明的请求取路由的应用、它的角色绑定及其中的 DENY。没有这项元数据的路由沿用网关的宿主应用，或客户端声明的应用。在面向多个应用的网关上，给每条路由指定它的应用。不是字符串、或不能作为应用 id 的 `cosec-app-id` 使该路由的每个请求失败：数字形式的应用 id 要加引号。
- **网关上的 actuator**（RC1-48、`STA-2`）。按策略决策的网关宿主，在其应用端口上（`management.server.port` 未设置，或与 `server.port` 相同）通过 HTTP 暴露了 `health` 以外的 actuator 端点时不再启动：CoSec 授权的是网关的路由，不是这些端点。把 actuator 放到单独的端口上并置于网络策略之后，只暴露 `health`，或在新配置项 `cosec.gateway.unprotected-endpoints` 中列出由其他手段保护的端点；它从不接受 `gateway`（[网关上的 actuator](../integration/web.md#actuator-on-a-gateway)）。`cosec-gateway-server` 和 `k8s/` 只暴露 `health`。
- **空白的 `CoSec-*` 值与另一个值同时出现即有歧义**（RC1-16、`SCP-4`）：请求头 `CoSec-Space-Id: s1` 加上 `?CoSec-Space-Id=` 现在是 `400` `invalid-request`，过去取 `s1`。空白只指 ASCII 空白字符，因此由 Unicode 空格组成的值（例如 `%E3%80%80`）是一个值：单独出现时，它是主体必须在其中有角色的空间。每个参数只发送一次。
- **应用 id 拒绝控制字符**（RC1-15、`SCP-10`），与租户 id 相同：声明的应用含控制字符时为 `403` `scope-rejected`（匿名调用方为 `401`），宿主应用含控制字符时启动失败。
- **id 拒绝落单的 UTF-16 代理项**（RC1-52、`IDN-10`）。JSON 令牌或请求体可以用转义写出一个落单的代理项（`"beta\uD800"`），而 Redis key 在它的位置写 `?`，所以租户 `beta\uD800` 读到的是租户 `beta?` 的文档，主体的成员关系、吊销与限流的桶也同样共用。现在：令牌的 `sub`、`tenantId`、`jti`、`roles` 或 `policies` 里有落单的代理项时为 `401` `token-invalid`，CoSec 自己的签发方拒绝签出这样的令牌（登录失败）；指名这种 id 的 `CoSec-Identity` 为 `400`；路径、查询或请求体任何位置带有落单代理项的控制请求为 `400`；给出这种 id 的认证器、API Key 记录或成员关系提供方让请求以 `503` 失败；带落单代理项的应用或租户 id 与带控制字符的一样被拒绝。限流 `key` 的值带落单代理项时计入它自己的桶（`e:` 加上转义后的值）。含代理对（如 emoji）的 id 不受影响。日志与错误信息把这种代理项写成 `\uXXXX`，从不写成 `?`。在代码中，`Identifiers`（`cosec-model`）持有这条规则；`ApiKey` 与 `AuthenticatedPrincipal` 在构造时拒绝这种 id，`RevokedTokenCache.key`、`RevokedSubjectCache.key` 与 `MembershipCache.key` 对它们抛出异常。无需迁移；如果令牌签发方或脚本可能发送过这种 id，以带 `?` 的 id 存储的内容可能是为另一个 id 写入的。
- **控制面只接受应用 id**（`SCP-10`）。应用 id 不为空白、不是 `*`、不含 `:` 和控制字符，且不超过 128 个字符；之前的构建接受任何不含 `:` 的应用。现在其他应用中的绑定是位于 `$.bindings[i].app` 的 `V2` 发现（`400`），无论它在候选中的什么位置。`analyze` 对这种应用回答 `400`。**升级控制面之前**，移除这种应用中的租户绑定，放弃这种应用文档上未结束的变更，并清空以这种 id 发布的应用文档（发布一个没有策略和绑定的修订），这会移除它们的反向引用。升级之后就无法再处理它们，遗留下来的内容是格式错误，按失败关闭处理，绝不跳过：保留这种绑定的文档仍然可读，而该绑定不授予任何东西，但发布和 `repair-references` 都过不去；这种应用上未结束的变更回答 `503`，并让每一轮维护都停下，直到它的记录被移除；应用列表中的条目让授予涉及的每个应用都变成未知，直到删除 `<p>:control:apps:all` 并运行 `cosec-migrate repair-references`；指向这种应用的反向引用让读取该集合的操作都失败，直到删除该集合并运行 `repair-references`；实例状态的条目让实例状态的读取失败，直到它过期（该实例最后一次写入后两分钟）。
- **按策略决策的宿主丢弃客户端的 `CoSec-Identity`**（RC1-18、`IDN-9`）。`enforce` 模式的 WebFlux 或 Spring MVC 宿主会从处理器看到的请求中移除该头，因此它从不转递客户端写的身份。在那里读取它的代码读到的是任何客户端都能写的内容；请读取 `SecurityContext`。
- **租户把应用权限授给 `Anyone` 或某个主体的绑定现在能解析**（RC1-17、`TNT-4`）。该应用中的请求现在无论主体有什么角色都读取应用文档，因此绑定中的 DENY 或 ALLOW 生效（过去这样的请求都是 `503`），应用文档的限流规则也随之生效。`simulate`、`explain` 和影子窗口按同一规则读取它（`CTL-3`）。代码中，`CompiledDocument.tenant` 接收集合 `boundApps`，由 `CompiledDocument.needsApp` 回答；自行构造的 `ShadowListener` 需要已安装的视图（`views`），才能比较让请求读取应用文档的租户候选，没有视图时上报 `CANDIDATE_ERROR`。
- **一个 key 前缀**（RC1-46、`SNP-9`）。[`cosec.key-prefix`](./configuration.md#key-prefix) 取代 `cosec.authorization.snapshot.key-prefix`、`cosec.authorization.cache.key-prefix` 和 `cosec.api-key.key-prefix`。仍设置其中任何一项的环境都会启动失败，和其他 CoSec 不读取的 key 一样。每个 key 都在它之下：API key 在 `<p>:api-key` 下，过去固定的限流计数器和社交登录的 OAuth 状态分别在 `<p>:rate-limiter` 和 `<p>:oauth:state` 下（[Redis key](./storage.md#redis-keys)）。使用默认值时每个 key 都还在原处。设置过这三项之一的部署，改为把 `cosec.key-prefix` 设成原来的快照前缀；**保存在其他前缀下的吊销和 API key 不再被读取**，在那里吊销的令牌会重新被接受：切换之前，把每个这样的 key 复制到新前缀下（`COPY`，或保留过期时间的 `RENAME`）。成员关系会自行重新填充。`cosec-migrate api-keys` 不再接受 `--api-key-prefix`：它转换 `<--key-prefix>:api-key` 下的 key。

  设 `<p>` 为 `cosec.key-prefix` 的值，各 key 族的移动如下：
  - 快照文档、控制面记录、候选和影子报告：从旧的 `cosec.authorization.snapshot.key-prefix`（默认 `cosec`）移到 `<p>`；
  - 令牌吊销和成员关系：从旧的 `cosec.authorization.cache.key-prefix`（默认 `cosec`）移到 `<p>`，应用自己创建的成员关系缓存除外，它们留在自己的前缀下；
  - API key：从旧的 `cosec.api-key.key-prefix`（默认 `cosec:api-key`）移到 `<p>:api-key`；
  - 限流计数器和社交登录的 OAuth 状态：从 `cosec` 移到 `<p>`。
- **`revalidate-interval` 不能关闭周期确认**（RC1-50、`SNP-6`）。失效消息丢失时，周期确认是唯一的界，因此 `cosec.authorization.snapshot.revalidate-interval` 必须至少为 `1s`（每一轮都从 Redis 读取每个已安装的文档）、不超过 `5m`、小于 `lag-warn`，配置了 `fail-closed.tenants` 时还要小于 `fail-closed.max-lag`；否则启动失败并指出该配置项，`SnapshotOptions` 也同样拒绝。把 `0` 换成范围内的值；更长的间隔可以降低确认在 Redis 上的开销（[存储](./storage.md#what-revalidation-costs-redis)）。
- **路径模式有开销上界**（RC1-8、`DOC-4`）。`http:path:` 模式不超过 2,048 个字符，把 `*`、`?` 或 `{name}` 与其他文本混在一起的段最多 16 个部分（连续的 `*` 算一个），一个文档中这样的片段合计最多 2,048 个通配单位（它们的部分数加上文本的字符数，前导 `**` 之后的片段和以捕获变量为 key 的限流规则计两次），类型化模式不超过 2,058 个字符（[策略语言](../policies/language.md#path-pattern-bounds)）。控制面对超出上界的变更回答 `COMPILE` 发现（`422`），位置为该模式或 `$`。之前发布、含有这种模式的文档在升级后的实例上不再能编译，读取它的每个请求都失败关闭：升级数据面之前，改写它并发布。
- **每个策略在每一遍中对每个绑定它的文档只求值一次**（`ALG-9`），无论该文档有多少条绑定生效；在附加策略中声明两次的策略只附加一次。决策及其报告的语句和绑定都不变：报告的是候选顺序中的第一条绑定。追踪按第一条绑定的结果列出之后的绑定，不再重新求值。
- **文档读取的请求头重复出现时为 `400`**（RC1-38、`PIP-12`）。请求以不同的值多次发送某个请求头，而它的视图所读文档中的任何语句、策略条件、边界或限流 key 读取这个请求头（包括 `Origin` 和 `Referer`）时，管道在求值之前回答 `400` `invalid-request`；过去读取它的 DENY 使请求为 `503`，读取它的 ALLOW 不匹配。相同的重复值算一个值（[Web](../integration/web.md#responses-and-status-codes)）。
- **格式错误的 `roles`、`policies` 或 `attributes` 声明是无效令牌**（`PIP-6`）：`401` `token-invalid`，按匿名求值，过去是 `500`。
- **处处拒绝重复的 key**（RC1-44、`DOC-5`）。任何含重复 key 的存储格式（API key、成员关系、影子报告、吊销）和控制面请求体都无法读取：存储的值为 `503`，请求体为 `400`。`cosec-migrate` 也拒绝含重复 key 的 7.x 导出和文件。
- **CoSec 的缓存无论由谁构建都保留其固定项**（RC1-45、`SNP-3`）。`createPolicySnapshotCache` 对任何 spec 都应用权威存储的策略、快照的编解码器，并去掉 key 过滤器、按条目的 TTL 和加载器；`CacheSpecCustomizer` 不再能改变 CoSec 缓存的编解码器、加载器、key 过滤器或按条目的 TTL。
- **编译时栈溢出的文档失败关闭**（RC1-43、`SNP-8`），与其他编译错误一样，而不再保留之前的修订。
- **限流在 Redis Cluster 上计数**（#1229、`OBL-4`）。Redis 限流器的计数器移到 `<p>:rate-limiter:{<rule>:<bucket>}:<window>`：hash tag 让一个桶的两个窗口（脚本一起读取它们）在同一个 slot 上。在集群上每次调用都以 `CROSSSLOT` 失败，于是没有 `strictFailure` 的规则被跳过、什么也不限制，有 `strictFailure` 的规则让每个放行的请求返回 `503`。在单个 Redis 上只有 key 变化。**滚动升级期间，两个版本的实例各自在自己的 key 里计数**：新旧版本的实例同时服务期间，以及最后一个旧实例停止后的至多两个窗口内，一条规则至多放行两倍的上限。旧 key 在两个窗口后过期。第一个 `{` 后紧跟 `}`（空 hash tag）的 `cosec.key-prefix` 现在会启动失败。
- **边界的外部属性会被获取**（#1208、`ATR-8`）。属性计划包含约束这次请求的边界的属性，即它的语句的与它的策略条件的，于是阶段 B 的提供者会被询问这些属性。这样的边界允许的请求过去返回 `503`（`BoundaryUnavailable`），现在放行；每个请求询问提供者的属性可能变多。
- **检查获取它需要的属性**（#1209、`ATR-8`）。`AuthorizationChecker` 向提供者询问它的动作与资源读取的每个属性，包括阶段 A 的提供者（与阶段 A 一样不带作用域）；过去除非注册了某个阶段 B 的提供者，阶段 A 的属性都取不到（`AttributeUnavailable`）。现在每次需要阶段 A 提供者某个属性的检查都会调用它。
- **向提供者询问的属性变少**（#1211、`ATR-8`）。属性计划遍历求值会访问的语句，即语句索引给出的语句，而不再是动作匹配的每一条语句：资源路径不可能匹配请求的语句不会规划任何属性，它的策略条件也不会。没有任何读取某属性的语句可能匹配的请求，不再调用阶段 B 的提供者。请求依据什么决策不变。
- **同一时间只有一轮重新确认**（#1215、`SNP-6`）。超过 `revalidate-interval` 的周期确认不再有另一轮叠加上来：下一轮在它结束后立即开始，所以慢的 Redis 同一时间至多收到一个实例的四个重新确认读。失效消息丢失的变化由第一个到达该文档的一轮装上，这时可能超过一个间隔（[存储](./storage.md#how-instances-read-documents)）。
- **各种标准数字类型的身份声明**（#1228）。网关转发 `Byte`、`Short`、`Long` 范围内的 `BigInteger`、`Float`，以及 `Double` 能精确表示的 `BigDecimal` 类型的声明，过去这样的请求返回 `500`。头不能精确表示的数字仍以 `500` 拒绝，`NaN` 和无穷大也一样，JSON 没有表示它们的数字。
- **`simulate` 与 `explain` 在决定该请求的租户上决策与授权**（RC1-40、`CTL-23`）。没有 `principal` 的请求与执法一样在
  `(0)` 的文档上决策：它需要 `(0)` 上的 `cosec:simulate` 或 `cosec:explain`，而 alpha.2 检查的是请求指定的租户；指定其他
  租户的请求是 `400`。要模拟匿名请求，请写 `"tenant": "(0)"`，由能在那里模拟的主体来做。
- **`simulate` 与 `explain` 拒绝执法所拒绝的**（`CTL-3`、`PIP-12`）：以不同的值发送其文档所读取的头的请求是 `400`，而之前
  会被决策（DENY 读它时为 Indeterminate）；`http` 资源与请求路径经同一个规范化：执法拒绝的路径（点号段、格式错误的转义、
  超过 2,048 个字符或每段 256 个）是 `400`，路径参数在决策前去掉，而之前按原样决策；超过这些长度的类型化 id 是 `400`。
  `analyze` 的资源也一样。
- **控制 API 的 `400` 说得更少，存储的值解码失败是 `500`**（RC1-39）。`400` 的 `detail` 是控制面自己的某项检查，或者点名
  解析不了的字段；它不再带解析器或异常的消息（`No enum constant ...`、`For input string ...`、JSON 解析器的消息），解析
  不了的时间（`expiresAt`、`time`）是 `400`，而之前是 `500`。存储的文档或记录解码失败是不带详情的 `500`，而之前是带有存储
  文档 JSON 路径的 `400`；控制面主机按 key 记录它。按 `detail` 文本做匹配的客户端，请改为按状态码和 `code` 匹配。
- **路径模式中落单的代理项不再编译**（#1200 审查的 L4）：`http:path:` 模式文本中只有一半的代理对是一个 `COMPILE` 发现，
  而之前它能编译，并能匹配一个代理对的中间，Spring 永远不会这样匹配。之前发布的含有它的文档，在升级后的实例上失败即拒绝。
- **CoSec 的 Redis 模板从主节点读**（RC1-47）：通过 `spring.data.redis.lettuce.read-from` 或客户端定制器从副本读的
  `LettuceConnectionFactory` 会让启动失败（[Redis 要求](./storage.md#redis-requirements)）。另外，新的健康指示器
  `cosecSnapshotEviction` 在 Redis 可能驱逐快照时为 `DOWN`，每个 `revalidate-interval` 读一次；读不到策略时，
  `eviction-check: fail` 下为 `DOWN`，`warn` 下为 `UP`，详情 `eviction: unknown`。它是告警：`cosecSnapshot` 不变，
  只有它属于就绪组。
- **滚动升级期间**，旧实例新建的 app 文档不在 app 列表中；新实例在启动时和每次回收任务（`cosec.control.revisions.reclaim-interval`，默认 1 小时）时发现它，在此之前分析授出时可能读不到这个 app 的 DENY。滚动期间不要经旧实例新建 app，或者在此期间调低 `reclaim-interval`。旧实例还会把持有 `cosec:approve` 的租户主体当作信任根，并接受 API key 的审批：滚动完成之前，只经已升级的实例审批和发布。旧实例接受的审批没有记下凭证，已升级的实例不会凭它发布。
- **没有使用者的公共 API 已移除。** `KafkaAuditEventSink`：starter 注册的是 `KafkaAuditListener`，它写入相同的 key 和 value。`SimpleSecurityContext` 及其扩展属性 `CoSecPrincipal.tenant`：请求的 `SecurityContext` 由管道创建，测试中请 mock 或自行实现该接口。引擎请求中的 `AuthzRequest.scope`、类 `me.ahoo.cosec.model.Scope`，以及 `Subject.roles`、`policies` 和 `root`，引擎都不读取：构造 `AuthzRequest` 或 `Subject` 的代码去掉这些参数即可。引擎使用的租户、角色和附加策略在 `EngineRequest.scope` 与 `EngineRequest.memberships` 中。`Subject.tenantId` 保留，供 stage A 的 `AttributeProvider` 使用，它在作用域解析之前运行。

### 经 CoCache 6 写入的数据 {#data-written-through-cocache-6}

8.0.0-alpha.2 经 CoCache 6 在 Redis 中保存数据；8.0.0 经 CoCache 8 保存，key 不变，但 CoCache 8 读不了 CoCache 6 的格式。它
在这种格式中读到的内容按读不懂的内容处理，失败即拒绝：需要这种文档的请求是 `503`（`SourceUnavailable`），在其中被吊销的令牌
或主体是 `503` 而不是 `401`，控制面 API 回答 `500`。没有任何读取会把这样的值当作不存在，所以被吊销的令牌绝不会重新被接受，
吊销表也不会。

8.0.0 的 `cosec-migrate` 不再有迁移这些数据的命令：请在升级到 8.0.0 之前，用 8.0.0-alpha.4 的 `cosec-migrate` 按下面的
步骤迁移（[从 8.0.0-alpha.4 升级](#from-8-0-0-alpha-4)）。

| 数据 | Key | 迁移之前，在 8.0.0 上 | `cosec-migrate cocache8` |
|---|---|---|---|
| 快照文档 | `<p>:snapshot:*` | 读取它的每个请求都是 `503` | 作为每份文档的第 1 个修订重新发布（来源 `MIGRATE`，没有父修订：父修订的 SHA-1 摘要不是 CoCache 8 认识的摘要） |
| 吊销 | `<p>:token:revoked:*`、`<p>:token:subject:revoked:*` | 被吊销的令牌或主体是 `503` | 重新写入，各自保留剩余的寿命 |
| 控制面记录 | `<p>:control:*` | 控制面 API 回答 `500` | 删除：历史从重新发布的文档开始 |
| 候选与影子报告 | `<p>:candidate:*`、`<p>:shadow:*` | 影子窗口无法报告 | 删除；仍在进行的变更会让命令拒绝执行（见下） |
| 成员关系 | `<p>:membership:*` | 从 `MembershipLoader` 重新加载 | 删除；它们会重新填充 |
| API key、限流计数器、OAuth 状态 | `<p>:api-key:*`、`<p>:rate-limiter:*`、`<p>:oauth:state:*` | 不变：由 Spring 的 Redis 模板保存，不经 CoCache | 不处理 |

1. 发布或放弃每个进行中的变更，并结束每个破窗授权：存在打开的变更（包括草稿）、暂存槽或候选时，除非指定
   `--discard-pending`，命令拒绝执行；含破窗绑定的文档也会被拒绝，与 `publish` 相同。
2. 如果 alpha.2 设置过被 `cosec.key-prefix` 取代的 key 前缀之一，先迁移那些 key（见上面的“一个 key 前缀”）：命令只读一个前缀。
3. 停止所有 CoSec 实例，包括控制面。alpha.2 实例与 8.0.0 实例既读不了对方的值，也收不到对方的失效消息：CoCache 8 的失效通道
   以 key 前缀命名。
4. 先看看会迁移什么，不写入任何内容：
   `cosec-migrate cocache8 --redis redis://localhost:6379 --key-prefix cosec --out ./cocache8 --dry-run`
   （Redis Cluster 加 `--cluster` 并给出种子节点）。它会列出拒绝执行的原因：仍打开的变更（包括草稿）、`publish` 会拒绝的
   文档、读不懂的值，或者既没有需要迁移的文档也没有平台文档。
5. 去掉 `--dry-run` 再运行。它先把文档按 `publish` 读取的布局写到 `--out`，并写一个标记 `<p>:meta:cocache8`，再写吊销
   （覆盖 CoCache 6 的值，所以任何时刻都不缺吊销），然后删除其他 CoCache 6 的值，发布文档，最后删除标记。如果它在删除之后
   中断，不要再运行 `cocache8` 来完成：它看不到自己已删除的文档，标记还在时它会拒绝执行。用它打印的命令完成：
   `cosec-migrate publish --to-redis <uri> --key-prefix <p> [--cluster] --in ./cocache8 --resume`，每份文档都重新发布后，
   这条命令会删除标记：这里用的是 alpha.4 的 `publish`，8.0.0 的 `publish` 在标记存在时会拒绝执行。
6. 启动 8.0.0 实例。每份文档的修订号从 1 重新开始；迁移之前的历史不会保留，如果需要，在第 5 步之前保存一份
   `<p>:control:revision:*`。

摘要现在是 SHA-256，alpha.2 用的是 CoCache 6 的 SHA-1：控制 API 的修订历史、变更的基线与检查，以及提到摘要的事件，都写作
`SHA-256:<64 位十六进制>`。自己实现的 `SnapshotDocumentStore` 必须给出 32 字节的 SHA-256 摘要，否则每次读取都失败即拒绝
（[扩展](../architecture/extending.md#snapshot-document-stores)）。

## 从 8.0.0-alpha.1 升级 {#from-8-0-0-alpha-1}

8.0.0-alpha.1 是预览版，没有兼容性承诺。此后来自 [RC 安全评审](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md)的以下变更，会破坏基于它写成的决策、代码或配置。7.x 部署在下面各节中读到它们。

- **Kubernetes 根据 Service 设置的 `cosec.gateway.*` 变量**（`COSEC_GATEWAY_SERVICE_HOST` 之类）在 8.0.0-alpha.1 中会被当作 CoSec 不读取的 `cosec.*` key 而拒绝启动。从 alpha.2 起，检查会跳过这些确切的变量；清单里的 `enableServiceLinks: false` 本来就不让它们进来（[部署](./deployment.md#what-the-manifests-set)）。
- **角色上限只移除 ALLOW**（RC1-1、`BND-9`、`IDN-2`）。带上限的凭证（带 `roles` 的 API key）仍然得不到其主体附加策略和主体绑定的授权，但它们的 DENY 现在作用于它：主体被拒绝的请求，该主体的每个 key 都被拒绝，无论是否有上限。alpha.1 中上限把这些 DENY 也一并去掉了，因此带上限的 key 能做主体不能做的事。无需迁移；要看哪些请求的结果会变，用 `principalBindings: false` 对它们做 `simulate`（[策略测试](../policies/testing.md)）。授权分析以同样方式计入带上限作者的 DENY。
- **break-glass 按来源判断**（RC1-26、`BRK-8`）。只有 CoSec 自己的 `JwtTokenVerifier` 校验过的令牌才能得到 break-glass。包装其他 `TokenVerifier` 的 `TokenAuthenticator` 认证其令牌时不带 break-glass，并以它的 `method`（默认 `token`）而不是 `jwt` 命名凭证。方式 `jwt` 是保留名：`AuthenticatedPrincipal` 拒绝你的代码用这个方式构造的凭证（`IllegalArgumentException`，即 `503`），`TokenAuthenticator` 也拒绝为非 CoSec 的校验器设置 `method = "jwt"`。请按凭证给你的认证器的方式命名（[扩展](../architecture/extending.md#authenticators)）。
- **改名后的 API key 头是携带凭证的请求头**（RC1-21、`CND-5`）。设置了 `cosec.pipeline.api-key-header` 时，读取该请求头的文档（条件或限流规则的 `key`）无论在控制面还是实例上都不再能编译，管道也从不为条件读取它。升级前删除这类引用。代码中，`ConditionChecker`、`PolicyDocumentCompiler.forPlatform` 和 `of`、`PlatformCompiler`、`ChangeChecks`、`Simulator` 与 `HttpAttributes` 以新参数接收该请求头。
- **`tenantId` 声明不是字符串时令牌无效**（RC1-22），alpha.1 则把它读作默认租户 `(0)`。请以字符串签发租户。
- **租户切换重新得出成员关系**（RC1-23）。`AbstractSwitchTenantAuthentication.switchTenant` 回答一个 `TenantMembership`，即用户在目标租户中的角色、策略和属性（或 `TenantMembership.fromProvider()`），而不是一个主体；`authenticate` 只依据它构造新主体。包装 `previousPrincipal` 的实现会把先前租户的角色带进新令牌（[租户](../integration/authentication.md#tenants)）。
- **JWT 密钥有最短长度**（RC1-24）：`hmac256`、`hmac384`、`hmac512` 分别至少 32、48、64 个 UTF-8 字节。更短的 `cosec.jwt.secret` 会让启动失败；在签发方和每个校验方设置更长的密钥，用旧密钥签发的令牌随之失效。
- **社交登录只签名提供方拥有的字段**（RC1-25）。`DirectSocialUserPrincipalConverter` 只给主体两个属性 `cosec_oauth_provider` 和 `cosec_oauth_user_id`，不再使用用户可编辑的提供方原始用户信息，也不再写入它转换的 `SocialUser`。`attributes` 中带 `apiKeyId`（CoSec 为 API key 设置）的令牌按 `token-invalid` 拒绝，`JwtTokenConverter` 也不会签发这样的令牌。要保留资料字段，请声明你自己的 `SocialUserPrincipalConverter`，并且只保留用户无法更改的字段（[社交登录](../integration/authentication.md#social-login)）。
