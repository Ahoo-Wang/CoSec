---
title: "构建客户端"
description: "CoSec 8.0 应用的客户端可以依赖什么：哪些端点由应用提供、每种 problem 类型下客户端该做什么、轮换下如何刷新、能读取哪些声明、CoSec-* 请求头，以及 CORS。"
---

# 构建客户端 {#building-a-client}

面向用任意语言编写客户端（浏览器应用、移动应用、服务）、去访问运行 CoSec 的应用的开发者。本页说明服务端做什么、客户端必须
对此做什么；应用提供什么由应用自己决定（[认证](./authentication.md)），每个状态码和响应体只在
[Web 集成](./web.md#responses-and-status-codes)中定义一次。本页的内容不针对某一个客户端库。

客户端要解析的事实也以文件形式放在 [`contract/`](https://github.com/Ahoo-Wang/CoSec/tree/main/contract) 中（`PIP-25`）：每种
problem 类型的状态码、响应头和响应体，`CoSec-*` 请求头和请求 id 的语法，令牌对的响应体，CoSec 令牌的声明，以及 starter 提供的刷新和登出
路由的回答。服务端的测试按解析后的 JSON 把它的回答与这些文件比对，所以客户端自己的测试可以把它们当作预期的回答。文件中标为 `optional` 的声明可能不在令牌里，读取它的客户端要处理缺失的情况：缺失的含义见[客户端可以读取的声明](#the-claims-a-client-may-read)一节的表。`@ahoo-wang/cosec-fetcher` 的测试对着同样的文件运行，文档检查也把那张表与 `token/claims.json` 比对。

## 你的应用提供什么 {#what-your-application-serves}

CoSec 不提供登录和租户切换：它们属于应用，用 CoSec 的类编写（[登录](./authentication.md#logging-in)），路径、请求体和每种失败的状态码都由应用决定。刷新和登出在设置了路径时由 starter 自己提供；一个路径都不设置的应用，像写登录一样自己写它们。

| 端点 | 由谁提供 | 入参和出参 |
|---|---|---|
| 登录 | 应用：`TokenCompositeAuthentication.authenticateAsToken(credentials)` | 入：你的凭证；出：`CompositeToken` |
| 刷新 | starter，位于 `cosec.authentication.login.refresh.path`（`IDN-23`）；未设置时由应用基于 `RefreshTokenAuthentication` 提供。starter 的路由需要 `MembershipRefresh` Bean 或 `cosec.jwt.session.memberships-on-refresh=from-token`（`STA-8`） | 入：`{"accessToken": "...", "refreshToken": "..."}`；出：`CompositeToken` |
| 登出 | starter，位于 `cosec.authentication.login.logout.path`（`IDN-24`）；未设置时由应用提供：`TokenRevoker.revoke(accessToken)`，访问令牌过期后用 `revoke(compositeToken)` | 提供的路由：入：令牌对，出：`204`；会话在服务端结束 |
| 租户或应用切换 | 应用：`SwitchTenantAuthentication`，凭一个有效会话的令牌对发起 | 入：`Authorization` 头里的当前令牌，以及请求体里的令牌对（`accessToken`、`refreshToken`）和你的凭证类；出：`CompositeToken` |

`CompositeToken` 写成 `{"accessToken":"<jwt>","refreshToken":"<jwt>"}`，没有别的字段（`CompositeTokenJsonTest` 逐字节固定它）。
应用在响应里加字段不会破坏 CoSec，客户端要忽略它不认识的字段。客户端发回的只能正好是令牌对，不能是原样的响应：
刷新与退出路由对其他请求体回答 `400 invalid-request`。`cosec-fetcher` 只保留交给 `signIn` 的对象里的 `accessToken` 和
`refreshToken`。

**提供的刷新路由如何应答。** 每个应答都带 `Cache-Control: no-store`。

| 请求，或出了什么问题 | 状态码 | 响应体 |
|---|---|---|
| `POST`，`Content-Type: application/json`，只有令牌对，最多 32 KiB | `200` | `CompositeToken` |
| 多出的成员、缺失或为空的值、其他媒体类型、更大的请求体、不是 JSON | `400` | `invalid-request` |
| 会话的寿命已到（`cosec.jwt.session.lifetime`），或刷新令牌已过期 | `401` | `token-expired`：重新登录 |
| 会话已结束：登出，或某个令牌对被再次出示（`IDN-16`、`IDN-18`） | `401` | `token-revoked`：重新登录 |
| 令牌对无效、不属于任何会话，或不是同一对 | `401` | `token-invalid`：重新登录 |
| 吊销记录或保存会话的存储无法应答：没有签发任何东西（`IDN-14`、`IDN-19`） | `503` | `unavailable`：保留令牌对，稍后重试 |
| 其他任何错误 | `500` | `internal-error`：保留令牌对 |

不是令牌的响应体都是流水线所写的问题（[问题类型](#on-this-problem-type-a-client-does-this)）：它携带 `CoSec-Request-Id` 头的请求 id，不带 trace id，也不带请求的任何其他内容。提供的登出在会话被结束、以及会话早已结束时都应答 `204`，因此响应丢失的登出可以再发一次；其他情况与刷新相同，是 `400`、`401`、`503` 和 `500`。

这个路由从请求体读取凭证，也不设置 Cookie，因此别的站点的页面无法让浏览器替它发出这些凭证：它不是跨站请求伪造的目标。网关不提供它，在网关上设置路径会让启动失败：签发会话的服务在网关之后提供这个路由，网关像转发其文档允许的其他路由一样转发它。

自己编写刷新路由的应用按这张表应答：`RefreshTokenAuthentication` 抛出异常，而 CoSec 不为它不提供的路由注册异常处理器，所以处理器没有映射的异常，由框架决定如何响应。

| 抛出 | 应答 |
|---|---|
| `TokenExpiredException`、`TokenRevokedException`、其他任何 `TokenVerificationException`（`RefreshTokenMismatchException`、`DelegationUnsupportedException`） | `401`，类型同上表对应的行 |
| `RevocationUnavailableException`、`SessionStoreUnavailableException`（保存会话的存储） | `503`：没有签发任何东西 |
| 其他任何异常 | `500` |

**登录和刷新必须允许匿名调用方访问。** 刷新请求带着已经过期的访问令牌，过期的令牌是被拒绝的凭证：请求按匿名主体求值（`PIP-6`）。
如果没有任何语句让匿名调用方到达刷新路由，刷新请求本身就是 `401 token-expired`，客户端无法续期。刷新时不带 `Authorization`
头的客户端不会改变这一点：两种情况下都是匿名。提供的路由也不例外（`STA-4`）：过滤器不豁免任何路由；文档拒绝其中一个时，starter 记录一条点名其属性的 `WARN`，并在 `cosecLoginRoutes` 健康指示器上显示。

<<< @/examples/clients/documents/platform.json [documents/platform.json：客户端需要的路由的语句]

下面的场景针对它运行：带着已过期访问令牌的刷新请求按匿名调用方判定，有这条语句时被允许，没有时被拒绝。

<<< @/examples/clients/scenarios/refresh-is-permitted-to-an-expired-access-token.json [scenarios/refresh-is-permitted-to-an-expired-access-token.json]

<<< @/examples/clients/scenarios-closed/refresh-is-denied-without-a-statement.json [scenarios-closed/refresh-is-denied-without-a-statement.json，同一个请求，没有这条语句]

## 在这个 problem 类型下，客户端这样做 {#on-this-problem-type-a-client-does-this}

被拒绝的请求，响应体是 `application/problem+json`，永远是相同的五个成员、相同的顺序（`type`、`title`、`status`、`code`、
`requestId`，再加上追踪捕获到时的 `traceId`），没有别的：没有策略、没有语句、没有原因（[响应与状态码](./web.md#responses-and-status-codes)）。
按 `code`（或 `type` 的最后一段）判断，不要按 `title`。类型集合会在小版本中增加（RFC 0010 Q6），所以每个客户端都要对不认识的类型有默认处理：
按表里同一状态码类别的做法处理。

| `code` | 状态码 | 含义 | 客户端 |
|---|---|---|---|
| `INVALID_REQUEST` | `400` | 请求无法被读成一个请求：`CoSec-*` 参数发了两次且值不同、管道拒绝的路径，或（网关之后）读不出的身份 | 是客户端的 bug。不要重试同一个请求，也不要刷新 |
| `UNAUTHORIZED` | `401` | 没有凭证，或匿名调用方声明了空间（`SCP-3`） | 去登录。如果它手里有令牌，检查请求有没有带上 |
| `TOKEN_EXPIRED` | `401` | 访问令牌已过期 | 用令牌对刷新一次，然后重试请求一次。刷新被拒绝（`401`）就去登录 |
| `TOKEN_REVOKED` | `401` | 令牌或其主体的所有令牌已被吊销 | 去登录。刷新没有用：会话已经结束（`IDN-18`、`IDN-22`） |
| `TOKEN_INVALID` | `401` | 凭证不被接受：签名或声明有问题、该放访问令牌的地方放了刷新令牌（`IDN-13`）、委托令牌（`IDN-4`）、其提供者已不认识的主体（`IDN-3`） | 去登录。刷新没有用 |
| `FORBIDDEN` | `403` | 调用方已认证，决策是 Deny | 不重试。刷新很少有用：刷新带的是旧声明，除非应用重新读取了用户。向用户说明没有权限 |
| `SCOPE_REJECTED` | `403` | 应用不是宿主的，或空间是主体没有角色的空间，或以 `(` 开头（`SCP-1`、`SCP-2`、`SCP-9`） | 不重试，也不自己另选空间：换一个空间或退回默认空间，会把用户接下来的写入悄悄带到他们不想去的地方。它把情况告诉应用，由应用让用户选一个主体有角色的空间，或为宿主服务的应用重新登录。服务端拒绝过的空间，在应用重新选定之前不再发送 |
| `TOO_MANY_REQUESTS` | `429` | 请求已被许可，但某条[限流规则](../policies/rate-limiting.md)没有剩余的许可 | 等 `Retry-After` 秒后重试。绝不当作登出，刷新路由上也一样 |
| `UNAVAILABLE` | `503` | 无法做出决策（失败即拒绝） | 稍后退避重试，并保留会话。`503` 从不带 `Retry-After` |
| `INTERNAL_ERROR` | `500` | CoSec 以它没有建模的方式失败 | 不要盲目重试；报告 `requestId` |

随之而来的响应头：

- `CoSec-Request-Id` 出现在每一个响应上，`400` 也不例外：是客户端自己的值（如果它是普通 id），否则是生成的值，并且等于问题体里的
  `requestId`。记录下来：用它可以在审计和追踪里找到这个请求。
- `RateLimit-Limit`、`RateLimit-Remaining` 和 `RateLimit-Reset` 出现在 `429` 上，也出现在花掉了许可的已放行响应上；`Retry-After` 只在
  `429` 上，并且等于 `RateLimit-Reset`（[响应与响应头](../policies/rate-limiting.md#responses-and-headers)）。
- CoSec 不设置 `WWW-Authenticate`。

**被拒绝的令牌不是错误。** 公开路由对令牌已过期的请求返回 `200`，并且不说明这件事：调用方是匿名的。想知道令牌快过期的客户端读取 `exp`，
在请求之前刷新；不要等 `401`。

## 自己渲染 problem 的宿主 {#a-host-that-renders-its-own-problems}

宿主可以用自己的 problem 目录渲染 CoSec 拒绝的响应体（`ProblemRenderer`，`PIP-34`，[Problem 渲染器](../architecture/extending.md#problem-renderer)）。状态码和头仍是 CoSec 的；响应体是宿主的。**这个客户端只凭 `type`（`https://cosec.ahoo.me/problems/<slug>`）识别拒绝**，并且只在 `type` 属于 CoSec 时读取 `code`。在替换了 `type` 的宿主上，每个拒绝都是它不认识的：

- 任何 `401` 都会刷新一次：过期的令牌仍会被刷新，但被吊销或无效的令牌不会结束会话，没有凭证的请求也会白白刷新；
- `onUnauthorized` 和 `onForbidden` 回调得不到 problem，所以应用分不清过期的令牌和被吊销的令牌，也分不清 `FORBIDDEN` 和 `SCOPE_REJECTED`。

要让它不失明：宿主保留 CoSec 的 `type` 和 `code`，并在它们旁边把自己目录的标识加为扩展成员（[Problem 渲染器](../architecture/extending.md#problem-renderer) 中的示例就是这样做的）。必须替换 `type` 的宿主，要给它的客户端提供对这些状态码的自己的处理，而不是用这个客户端的处理。

## 轮换下的刷新 {#refreshing-under-rotation}

会话是一族令牌对，只有最新的一对可以刷新（[会话](./authentication.md#sessions)）。这对客户端意味着：

- **每个令牌对同一时刻只刷新一次，对它的每一份副本都是如此。** 共享同一个已存令牌对的标签页、窗口和进程共用一把锁，拿到锁后重新读取存储。同一个会话的
  两份副本各自刷新，就是在刷新之后又使用了副本：会话结束（`IDN-16`）。每个标签页各存一对（放在内存里、放在标签页不共享的存储里）或每个进程各存一对，就是这种情况。
- **先保存新的令牌对再使用它，并丢掉旧的。**
- **没有等到响应的刷新**（超时、连接丢失）可能已经被记录，也可能没有，客户端无从判断。在移动之后的 `cosec.jwt.session.rotation-leeway`
  （默认 30 秒，是运维的设置，客户端读不到）之内再次出示同一个令牌对，会得到当前代的令牌对，并且什么都不移动。由此可知：客户端自己的重试窗口是配置，
  必须不大于服务端的值；一次迟到的重试如果发现刷新已被记录，在服务端看来就是一个被再次出示的令牌对，运维可能在审计中看到它。超过宽限期之后再出示，整个会话结束，
  所有标签页和设备都会登出。所以要么在宽限期内重试，要么不重试：只有第一次请求根本没有到达服务端，迟到的重试才无害。不重试的最坏结果是重新登录，
  迟到的重试的最坏结果也是重新登录。
- **读刷新的状态码。** `401` 表示会话结束：丢弃令牌对，去登录。`503`、网络错误或超时都不是结论：保留令牌对。其他 `4xx`（`429`、`400`）
  同样不是对会话的结论：遇到任何 `4xx` 都登出用户的客户端，会把只是被限流暂停的会话结束掉。
- **刷新令牌的 `exp` 就是尽头。** 令牌对里没有任何令牌活得比会话长（`cosec.jwt.session.lifetime`，`IDN-17`），所以读刷新令牌 `exp` 的客户端
  知道什么时候刷新一定不会成功，可以省掉这次调用。

**令牌对存在哪里，谁能读到。** `cosec-fetcher` 默认把令牌对存在 `localStorage`，所以在页面同源上运行的任何脚本（依赖带进来的或被注入的）都能读到刷新令牌，并在会话有效期结束之前一直续期。正是这一点让一个会话的所有标签页只做一次刷新。`sessionStorage` 或内存（`new TokenStorage({ storage: ... })`）能让令牌对不被其他标签页看到，却挡不住页面上的脚本，还要付出共享刷新的代价：每个标签页各持一份，不能与另一份同时刷新（见上）。保护令牌对的是让外来脚本进不了这个源：Content-Security-Policy，以及你信得过的依赖。

## 客户端可以读取的声明 {#the-claims-a-client-may-read}

客户端不验证令牌：只有应用能验证，所以它读到的东西不决定任何事，只用来显示。访问令牌的声明如下（[令牌](./authentication.md#tokens)）：

| 声明 | 客户端可以用来 | 缺失时 |
|---|---|---|
| `exp` | 决定何时刷新。没有 `exp` 的令牌会被拒绝，所以它总是存在 | |
| `iat` | 令牌的签发时间 | |
| `sub` | 主体 id，用于显示或填充路径 | |
| `tenantId` | 租户，用于显示或填充路径 | 主体在默认租户；第一步登录得到的令牌不指明租户 |
| `aud` | 令牌所属的应用，一个字符串 | 令牌不属于任何应用：读不到应用文档 |
| `attributes` | 应用放在里面的显示数据 | 为空 |
| `roles`、`policies` | 不要用来判断用户能做什么：为空时不写，**`cosec.membership` 为 `"provider"` 时也不写**，变化要到下次登录或刷新才生效 | 不表示"没有角色" |
| `jti` | 一个 id | |
| `sid`、`auth_time` | 会话 id 和用户登录的时间（秒） | 不属于会话：这个令牌对永远不会被刷新 |
| `cosec.generation`、`cosec.token`、`cosec.membership` | 不要用：客户端不能依赖它们 | |

刷新令牌也是 JWT。读它的 `exp`；它的其他声明指明会话和它的访问令牌，客户端用不到。

## CoSec 请求头 {#the-cosec-request-headers}

它们都只作为请求头：URL 中的参数不会被读取，因为 URL 会进入访问日志和 `Referer` 头（[应用与空间](./web.md#app-and-space)）。

| 请求头 | 发什么 | 服务端做什么 |
|---|---|---|
| `Authorization` | `Bearer <访问令牌>` | 令牌。刷新调用可以带上已过期的令牌；它按匿名处理 |
| `CoSec-Space-Id` | 一个值，只用于在某个空间内的请求，并且只能是已登录主体有角色的空间。**登录和刷新调用、以及任何不带令牌的请求，都不要发**：这些请求的调用方是匿名的，声明了空间就是 `401`（`SCP-3`） | 匿名且带空间：`401`（`SCP-3`）。没有角色的空间，或以 `(` 开头：`403 scope-rejected`（`SCP-1`、`SCP-9`）。空或空白表示默认空间 |
| `CoSec-Device-Id` | 一个值，每次安装保持稳定 | 对已认证的请求记录；策略或黑名单要读时才被读 |
| `CoSec-Request-Id` | 一个值，1 到 64 个字符，取自 `A-Z a-z 0-9 . _ -`，每个请求一个新的 | 保留并回显；其他值会被生成的值替换 |

同一个参数发了两次且值不同，或一个空白值紧挨着一个非空白值，是 `400 invalid-request`（`SCP-4`）；空白只指 ASCII 空白字符。没有任何请求头声明应用，客户端为此发送的头不会改变任何事情：应用是令牌的（见下）。

**只把它们发往应用所面向的源**：应用自己 API 的源，以及页面的源。指向别处的分页链接、下载地址或回调拿不到这四项中的任何一个，它的 `401` 也不是刷新或结束会话的理由：
它对本应用的会话什么都没说明。从令牌读到的租户或所有者，同样不会写进这种源的 URL。`@ahoo-wang/cosec-fetcher` 默认就是这样做的（`isTrusted`，默认
`sameOriginTrust`）；必须发往其他源的应用，在自己的判断函数里写明那个源。

## 应用来自令牌 {#the-app-comes-from-the-token}

客户端不选择应用：应用是令牌的 `aud`，由签发者决定。只服务一个应用的宿主（`cosec.authorization.scope.app-id`，或网关路由的 `cosec-app-id`）
会拒绝属于另一个应用、或不属于任何应用的令牌，返回 `403 scope-rejected`（`SCP-2`、`SCP-11`）。要在另一个应用里工作，客户端调用应用的切换端点，
保存它返回的令牌对；它从不改写请求头（[应用](./authentication.md#apps)）。

## 登出与切换 {#logout-and-switching}

- **登出先调服务端，再清本地。** 客户端只丢掉令牌对，令牌对在会话结束之前仍然可以刷新。调用登出路由，starter 的或应用的（它结束会话并吊销访问令牌，`IDN-18`；`cosec-fetcher` 的 `JwtTokenManager.logout()` 完成这两步），
  再丢掉令牌对。访问令牌过期后，端点需要整个令牌对。
  用户点击“登出”时调用服务端的登出；只有在已知会话已经结束时，才只忘掉令牌对。服务端没有确认的登出（`503`、`500`、没有回答）仍会在客户端删除令牌对：用户要求离开这台设备。随后客户端告诉用户：服务端可能仍持有该会话，直到它在那里结束，最迟到其刷新令牌过期。登出会等待正在进行的刷新，并用那次刷新存下的令牌对结束会话，因为被它轮换掉的令牌对会被拒绝。在 React 中，`cosec-react` 的 `logout`（配合 `tokenManager` 选项）就是这个登出，`signOut` 只是本地那一步。
- **切换返回新的令牌对。** 像刷新一样出示当前的令牌对（访问令牌也放在 `Authorization` 里），保存返回的令牌对，丢掉旧的。切换只能凭有效会话的当前令牌对发起（或轮换宽限期内刚被替换的那一对），并保留登录时间（`IDN-17`、`IDN-22`）；它不会让用户重新登录。如果别的标签页早已刷新过，请先刷新：更早的令牌对被出示来切换，会像刷新那样吊销会话。
  客户端用 `tenantId` 填充的路径参数会随新令牌改变。

## CORS {#cors}

浏览器客户端需要应用配置 CORS：CoSec 自己不设置任何 `Access-Control-*` 头，只有应用声明了 `CorsConfigurationSource` bean 时，启动器才会在管道之前注册 CORS 过滤器。
[CORS](./web.md#cors)说明了各个栈上的预检请求会怎样（有这个 bean 时被回答、不被判定；没有时按 `http:OPTIONS`、匿名调用方判定）、`@CrossOrigin` 和
`addCorsMappings` 还剩什么作用，以及要允许的请求头和要暴露的响应头：客户端要读的
`CoSec-Request-Id`、`RateLimit-Limit`、`RateLimit-Remaining`、`RateLimit-Reset` 和 `Retry-After`。客户端在应该看到 `401` 的地方看到网络错误，
就是看到了一个没有 CORS 头的拒绝响应。

## 从 `@ahoo-wang/fetcher-cosec` 迁移 {#coming-from-ahoo-wang-fetcher-cosec}

CoSec 8 应用的客户端是 `@ahoo-wang/cosec-fetcher`，它的 React hooks 是 `@ahoo-wang/cosec-react`。它们取代 `@ahoo-wang/fetcher-cosec` 和 `@ahoo-wang/fetcher-react` 中的 CoSec hooks，随 CoSec 发布并使用 CoSec 的版本号，遵循本页的约定。旧包遵循的是 CoSec 7 的约定，不能用于 8.0 服务器。连同它们的对等依赖（fetcher 的各个包）一起安装：

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus @ahoo-wang/fetcher-storage \
  @ahoo-wang/cosec-fetcher@beta @ahoo-wang/cosec-react@beta
```

在 8.0 正式发布之前，这两个包的 `latest` 标签指向一个标为已弃用的 `0.0.0` 占位版本，所以不带标签安装得到的是占位版本和一条弃用提示，而不是客户端。安装你所运行的 CoSec 版本对应的标签：`8.0.0-beta.n` 用 `@beta`（如上），候选发布版用 `@rc`，8.0 正式发布之后不带标签；也可以写明确切版本，它在所有这些阶段都正确。标签不会越过它所指的版本线：候选发布版出现之后，`@beta` 仍是最后一个 beta，与 RC 的服务端不匹配。把 `package.json` 和每处 import 中的包名改掉，把 hooks 的 import 从 `@ahoo-wang/fetcher-react` 改为 `@ahoo-wang/cosec-react`。行为上的不同：

| 旧包的做法 | `cosec-fetcher` 的做法 |
|---|---|
| 把令牌、`CoSec-Device-Id`、`CoSec-Request-Id` 和 `CoSec-Space-Id` 发给每一个绝对 URL，并用令牌填充 URL 里的 `{tenantId}` 和 `{ownerId}` | 只发给绝对 `baseURL` 的源和页面自己的源（`isTrusted`，默认 `sameOriginTrust`）。依赖令牌的、发往其他源的调用现在不再带它：在你自己的 `isTrusted` 谓词里写出那个源。发往其他源并得到 `401` 的调用不会启动刷新，也不会结束会话 |
| 发送 `CoSec-App-Id`，来自 `appId` 选项 | 没有 `appId` 选项，也不发送 `CoSec-App-Id`：服务器不读取它，应用是令牌的（[应用来自令牌](#the-app-comes-from-the-token)） |
| 遇到 `401` 就刷新 | 读取 `401` 的 problem 类型。`token-expired`，或不是 CoSec 写出的无正文 `401`，刷新一次并重试请求。`unauthorized` 不刷新。`token-revoked` 和 `token-invalid` 也不刷新，并删除已保存的令牌对 |
| 刷新失败就结束会话，不论什么原因 | 只有刷新路由回答 `401` 才结束会话；`400`、`403`、`404`、`429`、`5xx` 或网络失败都保留会话。`isRefreshRejected` 可以指定别的拒绝方式。刷新请求 10 秒后被截断 |
| 把每个 `403` 交给 `onForbidden` | 把 `403 scope-rejected` 交给 `onScopeRejected`，没有它时交给 `onForbidden`。回调的第二个参数是 problem |
| 在 `RefreshTokenError` 和 `RefreshUnavailableError` 上带着令牌对 | 它抛出的任何错误上都不带令牌：刷新或退出的错误只保留其请求错误的名字和消息，因为那个请求的请求体就是令牌对。你自己请求的错误仍然带着它的 `exchange`，其请求持有 `Authorization` 头：发送给错误上报器之前先清理 |

该包自己的 README 逐项列出被移除的成员。
