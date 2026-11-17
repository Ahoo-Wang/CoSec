---
title: "构建客户端"
description: "CoSec 8.0 应用的客户端可以依赖什么：哪些端点由应用提供、每种 problem 类型下客户端该做什么、轮换下如何刷新、能读取哪些声明、CoSec-* 请求头，以及 CORS。"
---

# 构建客户端 {#building-a-client}

面向用任意语言编写客户端（浏览器应用、移动应用、服务）、去访问运行 CoSec 的应用的开发者。本页说明服务端做什么、客户端必须
对此做什么；应用提供什么由应用自己决定（[认证](./authentication.md)），每个状态码和响应体只在
[Web 集成](./web.md#responses-and-status-codes)中定义一次。本页的内容不针对某一个客户端库。

## 你的应用提供什么 {#what-your-application-serves}

CoSec 不提供登录、刷新、登出或租户切换端点。应用用 CoSec 的类自己写每一个处理器
（[登录](./authentication.md#logging-in)），所以路径、请求体和每种失败的状态码都由应用选择，并由应用告诉它的客户端。

| 端点 | CoSec 给处理器的东西 | 入参和出参 |
|---|---|---|
| 登录 | `TokenCompositeAuthentication.authenticateAsToken(credentials)` | 入：你的凭证；出：`CompositeToken` |
| 刷新 | `RefreshTokenAuthentication`，基于 `RefreshTokenCredentials` | 入：`{"accessToken": "...", "refreshToken": "..."}`（常用凭证类的形式）；出：`CompositeToken` |
| 登出 | `TokenRevoker.revoke(accessToken)`；访问令牌过期后用 `revoke(compositeToken)` | 由你决定；会话在服务端结束 |
| 租户或应用切换 | `SwitchTenantAuthentication`，从一个有效会话发起 | 入：`Authorization` 头里的当前令牌，加上你的凭证类的请求体；出：`CompositeToken` |

`CompositeToken` 写成 `{"accessToken":"<jwt>","refreshToken":"<jwt>"}`，没有别的字段（`CompositeTokenJsonTest` 逐字节固定它）。
应用在响应里加字段不会破坏 CoSec，客户端要忽略它不认识的字段。

**刷新失败返回什么状态，由刷新处理器决定，不由 CoSec 决定。** `RefreshTokenAuthentication` 抛出异常，而 CoSec 不注册异常处理器：
你的处理器没有映射的异常，由框架决定如何响应。每个异常的含义由 CoSec 定义：

| 抛出 | 含义 | 应该告诉客户端 |
|---|---|---|
| `TokenExpiredException` | 会话的寿命已到（`cosec.jwt.session.lifetime`），或刷新令牌已过期 | `401`：重新登录 |
| `TokenRevokedException` | 会话已结束：登出，或某个令牌对被再次出示（`IDN-16`、`IDN-18`） | `401`：重新登录 |
| `TokenVerificationException`（以及 `RefreshTokenMismatchException`、`DelegationUnsupportedException`） | 令牌对无效、不属于任何会话，或不是同一对 | `401`：重新登录 |
| `RevocationUnavailableException`，或保存会话的存储出错 | 没有签发任何东西，也无法得知这个令牌对的状态（`IDN-14`、`IDN-19`） | `503`：保留令牌对，稍后重试 |

最后一列的状态码是[刷新令牌](./authentication.md#refreshing-tokens)要求处理器返回的；它是对应用的建议，本页其余部分假定应用照做。

**登录和刷新必须允许匿名调用方访问。** 刷新请求带着已经过期的访问令牌，过期的令牌是被拒绝的凭证：请求按匿名主体求值（`PIP-6`）。
如果没有任何语句让匿名调用方到达刷新路由，刷新请求本身就是 `401 token-expired`，客户端无法续期。刷新时不带 `Authorization`
头的客户端不会改变这一点：两种情况下都是匿名。

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
| `SCOPE_REJECTED` | `403` | 应用不是宿主的，或空间是主体没有角色的空间，或以 `(` 开头（`SCP-1`、`SCP-2`、`SCP-9`） | 丢掉它保存的 `CoSec-Space-Id`（回退到默认空间），或为宿主服务的应用重新登录；同一个请求重试仍会被拒绝 |
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

## 轮换下的刷新 {#refreshing-under-rotation}

会话是一族令牌对，只有最新的一对可以刷新（[会话](./authentication.md#sessions)）。这对客户端意味着：

- **每个令牌对同一时刻只刷新一次，对它的每一份副本都是如此。** 共享同一个已存令牌对的标签页、窗口和进程共用一把锁，拿到锁后重新读取存储。同一个会话的
  两份副本各自刷新，就是在刷新之后又使用了副本：会话结束（`IDN-16`）。每个标签页各存一对（放在内存里、放在标签页不共享的存储里）或每个进程各存一对，就是这种情况。
- **先保存新的令牌对再使用它，并丢掉旧的。**
- **没有等到响应的刷新**（超时、连接丢失）可能已经被记录，也可能没有，客户端无从判断。在移动之后的 `cosec.jwt.session.rotation-leeway`
  （默认 30 秒，是运维的设置，客户端读不到）之内再次出示同一个令牌对，会得到当前代的令牌对，并且什么都不移动。超过之后再出示，整个会话结束，
  所有标签页和设备都会登出。所以要么在宽限期内重试，要么不重试：只有第一次请求根本没有到达服务端，迟到的重试才无害。不重试的最坏结果是重新登录，
  迟到的重试的最坏结果也是重新登录。
- **读刷新的状态码。** `401` 表示会话结束：丢弃令牌对，去登录。`503`、网络错误或超时都不是结论：保留令牌对。其他 `4xx`（`429`、`400`）
  同样不是对会话的结论：遇到任何 `4xx` 都登出用户的客户端，会把只是被限流暂停的会话结束掉。
- **刷新令牌的 `exp` 就是尽头。** 令牌对里没有任何令牌活得比会话长（`cosec.jwt.session.lifetime`，`IDN-17`），所以读刷新令牌 `exp` 的客户端
  知道什么时候刷新一定不会成功，可以省掉这次调用。

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
| `CoSec-Space-Id` | 一个值，只用于在某个空间内的请求，并且只能是已登录主体有角色的空间 | 匿名且带空间：`401`（`SCP-3`）。没有角色的空间，或以 `(` 开头：`403 scope-rejected`（`SCP-1`、`SCP-9`）。空或空白表示默认空间 |
| `CoSec-Device-Id` | 一个值，每次安装保持稳定 | 对已认证的请求记录；策略或黑名单要读时才被读 |
| `CoSec-Request-Id` | 一个值，1 到 64 个字符，取自 `A-Z a-z 0-9 . _ -`，每个请求一个新的 | 保留并回显；其他值会被生成的值替换 |

同一个参数发了两次且值不同，或一个空白值紧挨着一个非空白值，是 `400 invalid-request`（`SCP-4`）；空白只指 ASCII 空白字符。没有任何请求头声明应用，客户端为此发送的头不会改变任何事情：应用是令牌的（见下）。

## 应用来自令牌 {#the-app-comes-from-the-token}

客户端不选择应用：应用是令牌的 `aud`，由签发者决定。只服务一个应用的宿主（`cosec.authorization.scope.app-id`，或网关路由的 `cosec-app-id`）
会拒绝属于另一个应用、或不属于任何应用的令牌，返回 `403 scope-rejected`（`SCP-2`、`SCP-11`）。要在另一个应用里工作，客户端调用应用的切换端点，
保存它返回的令牌对；它从不改写请求头（[应用](./authentication.md#apps)）。

## 登出与切换 {#logout-and-switching}

- **登出先调服务端，再清本地。** 客户端只丢掉令牌对，令牌对在会话结束之前仍然可以刷新。调用应用的登出端点（它结束会话并吊销访问令牌，`IDN-18`），
  再丢掉令牌对。访问令牌过期后，端点需要整个令牌对。
- **切换返回新的令牌对。** 出示当前令牌，保存返回的令牌对，丢掉旧的。切换只能从有效会话发起，并保留登录时间（`IDN-17`、`IDN-22`）；它不会让用户重新登录。
  客户端用 `tenantId` 填充的路径参数会随新令牌改变。

## CORS {#cors}

浏览器客户端需要应用配置 CORS：CoSec 不设置任何 `Access-Control-*` 头，也不接线 CORS 过滤器。[CORS](./web.md#cors)说明了各个栈上的预检请求会怎样、
让预检请求和拒绝响应都能被脚本读到的那一种安排（CoSec 之前的 CORS 过滤器），以及要允许的请求头和要暴露的响应头：客户端要读的
`CoSec-Request-Id`、`RateLimit-Limit`、`RateLimit-Remaining`、`RateLimit-Reset` 和 `Retry-After`。客户端在应该看到 `401` 的地方看到网络错误，
就是看到了一个没有 CORS 头的拒绝响应。
