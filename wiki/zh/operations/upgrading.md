---
title: "升级"
description: "如何把 CoSec 7.x 部署升级到 8.0：有哪些变化，以及如何迁移数据、代码、配置和客户端。"
---

# 升级 {#upgrading}

面向把 CoSec 7.x 部署升级到 8.0 的团队。本页先给出一份检查清单，再按领域给出细节：数据迁移、8.0 在哪些地方决策不同、响应、认证、
存储、API、属性和可观测性。本页是迁移步骤的唯一出处。每个版本的发布说明见
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 从 8.0.0-alpha.4 升级 {#from-8-0-0-alpha-4}

8.0.0-alpha.4 是预览版，没有兼容性承诺。**在此版本之前签发的令牌都没有声明应用，配置了应用的服务会拒绝它们**（见下）：请安排重新登录，或等待令牌过期。以 format 2 存储的 API key 会失败，需要重新发放。

- **starter 的启动检查在延迟初始化下也会运行**（`STA-3`）。设置 `spring.main.lazy-initialization=true` 时，此前没有任何检查运行：缺少 `cosec-webmvc` 的 Spring MVC 宿主在完全没有授权的情况下启动，`gateway` 模式的 Spring Cloud Gateway 宿主不经检查地转发每个请求，Redis 的逐出策略、从副本读取、暴露的 actuator 端点、没有 `MembershipProvider` 的 API key 和未知的 `cosec.*` 属性都不受检查。现在它们与不使用延迟初始化时一样使启动失败。只因检查被跳过才能启动的配置不再能启动：按消息修正。
- **社交登录注册中的未知字段使启动失败**（第 2 轮 L4）。此前 `cosec.authentication.login.social.registration.<name>` 下拼错的字段（`pcke`、`scope`）被忽略，提供者的配置与作者的本意不同；现在消息会指出这个键。请改名或删除。
- **不起作用的 `cosec.cosid.generator` 使启动失败**（第 2 轮 L7）。此前在没有 `CosIdRuntime` bean、或者应用自己有 `IdGenerator` bean 时设置它，它会被忽略，CoSec 仍使用随机 id。请配置 CosId，或删除该属性。
- **两个哈希相同的内存 API key 使启动失败**（第 2 轮 L5）。此前该 key 以 `cosec.api-key.keys` 中最后一项认证；每个 key 只保留一项。
- **控制面的授权 bean 不可替换，无论是否 `@Primary`**（第 2 轮 L6）。此前标记 `@Primary`、或按注入点参数名命名的第二个 `ControlAuthorizer`、`ChangeAuthority`、`Changes`、`ControlPlane` 或 `ControlHttp` bean 会取代 CoSec 的 bean 被注入；现在它与普通的第二个 bean 一样使启动失败。
- **生成的路由映射位于 `servers` 路径之下，资源包含路径的每个变量**（第 2 轮 L4）。`cosecServiceGenerator` 端点丢掉了服务器路径（`/api/v1`），生成的路由匹配不到任何请求，所有请求都落在 `http` 命名空间；`/users/{id}/avatar/{size}` 的资源是 `user/{size}`，所有用户共用一个资源。现在路由以服务器路径开头（不含上下文路径），资源是 `user/{id}/{size}`：重新生成路由映射，并改写策略中按生成结果写的资源。多个服务器路径不同，或路径含服务器变量时，生成失败。
- **ip2region：无法定位的是取不到**（第 2 轮 M5）。只有随附的 IPv4 数据库时，IPv6 客户端没有国家，写成 `country in [...]` 的地域封锁 DENY 会放行它；现在它是取不到，这条 DENY 使请求返回 `503`（或者用 `onMissing: nomatch` 放行）。用 `cosec.ip2region.ipv6-location` 加载 IPv6 数据库。`Ip2RegionAttributeProvider` 改为接收数据库列表，每个数据库的版本取自其文件头，不再接收单个数据库和版本。
- **失败的 `IdGenerator` 只损失它自己的 id**（`PIP-23`）。此前抛出异常的生成器（时钟回拨后 CosId 的 `ClockBackwardsException`）会在得出任何结果之前离开管道：没有问题体，没有 `CoSec-Request-Id`，没有决策事件，各适配器各自应答。现在以随机 id 代替，并至多每十秒记录一次 WARN；`CosIdIdGenerator` 对 CosId 的失败也是如此。请把 CosId 的时钟回拨策略配置为失败而不是等待。
- **处理器从 `SecurityContext` 参数读取安全上下文**（第 2 轮 M4）。starter 注册了 `SecurityContextArgumentResolver`。`SecurityContextHolder` 只用于请求线程上的阻塞代码：在另一个请求的线程上恢复的 `suspend` 处理器，此前会从它读到那个请求的上下文。有 `io.micrometer:context-propagation` 并设置 `spring.reactor.context-propagation=auto` 时，Spring 现在也会恢复持有者；否则在 `suspend` 处理器中持有者是未绑定的，在那里读取会抛出异常（`PIP-22`）。
- **应用只读取安全上下文**（第 2 轮 L5）。`SecurityContextHolder.setContext` 和 `remove`、`ServletRequests.setSecurityContext`、`ServerWebExchanges.setSecurityContext`，以及 `ReactiveSecurityContexts.setSecurityContext` 和 `writeSecurityContext` 都是内部 API：只有适配器绑定上下文。自行绑定上下文的代码（`AuthorizationChecker` 本来就拒绝这样的上下文）不再能编译。
- **context path 按段去掉**（`PIP-20`）。Jetty 以规范形式给出 context path，而 URI 保留客户端的写法：context path 为 `/app` 时，`/%61pp/orders/1` 此前按 `/app/orders/1` 判定，绕过了 `/orders` 上的所有语句；现在是 `/orders/1`。不以 context path 开头的 URI 是 `400`。
- **Spring MVC 过滤器的顺序改为 `-9999`**，紧跟在 Spring Boot 的隐藏方法过滤器（`-10000`）之后，而不再是 `HIGHEST_PRECEDENCE + 100`。打开 `spring.mvc.hiddenmethod.filter.enabled` 时，带 `_method=DELETE` 的 `POST` 此前按 POST 授权、按 DELETE 执行；WebFlux 一直授权的是 DELETE。必须排在 CoSec 之前的过滤器（例如 CORS 过滤器）需要低于 `-9999` 的顺序。
- **HEAD 按 GET 判定，含小写字母的方法是 `400`**（`PIP-18`）。服务器用 GET 的处理器处理 HEAD，所以 `http:GET` 上的 DENY 拦不住它：现在 HEAD 使用路由映射的 HEAD 路由，没有时使用 GET 的路由，未映射时是 `http:GET`；`http:HEAD` 上的语句只有在路由映射映射了 HEAD 时才会匹配。`delete` 这样的方法此前按 `http:delete` 判定，`http:DELETE` 上的 DENY 不会命中，而会折叠大小写的后端把它当作 DELETE 处理：它现在是 `400 invalid-request`。`AuthorizationChecker` 以及控制面的 simulate、explain、analyze 按同样的方式读取 `http` 动作：`http:HEAD` 按 `http:GET`，小写动词为拒绝或 `400`。
- **客户端地址只读取一次**（`PIP-16`）。服务器自己解析转发头时（`server.forward-headers-strategy=native`，Spring Boot 在 Kubernetes 以及它识别出的其他云平台上也会自行开启），CoSec 不再第二次读取 `X-Forwarded-For`：在 Tomcat 上采用 `RemoteIpValve` 解析出的地址，在 Reactor Netty 和 Jetty 上读取该请求头而绝不使用服务器转发来的地址。此前在 Tomcat 上，两次读取叠加会取到客户端写的条目。现在以下情形启动失败：`framework`；`native` 下的 Tomcat 显式设置了 `cosec.authorization.remote-ip.max-trusted-index`；`native` 下的 Reactor Netty 或 Jetty 设置了 `max-trusted-index: 0`；CoSec 无法判断的服务器上的 `native`。迁移方法：按消息修改（[故障排查](./troubleshooting.md#startup-fails-on-the-client-address)）。`XForwardedRemoteIpResolver` 现在是内部 API。
- **多行 `X-Forwarded-For` 视为一个列表，读不出的客户端地址是“取不到”**（`PIP-17`）。此前带两行该请求头的请求使用连接的对端，客户端可借此绕开针对它自己地址的封禁；现在按顺序读取这些行。带端口、方括号或 zone 的可信条目按其中的地址读取。不是地址的条目（`unknown`）此前使 `environment.ip` 缺失，DENY 视为不匹配；现在它是取不到：这样的 DENY 返回 `503`，黑名单也回答取不到。
- **凭证的租户必须是租户 id**（`IDN-11`）。`tenantId` 为空白、`*`、含 `:` 或控制字符、或超过 128 个字符的令牌现在是 `401 token-invalid`（此前为 `503`，空白时为 `500`），`JwtTokenConverter` 也不签发；租户如此的 `ApiKey` 无法构造，所以已存储的会查找失败（`503`），配置的会启动失败。
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
- **`SecurityContext` 是只读的**：`tenantId`、`principal`、非空的 `scope` 和 `forwardedIdentity`（`gatewayRequestId`、`credentialId`，取代属性 `cosec.identity.rid` 和 `cosec.identity.jti`）。属性表及其访问方法已删除；`tenant.tenantId` 变为 `tenantId`。
- **`CoSecPrincipal` 是应用读取到的请求信息**：`id`、显式的 `authenticated`、`attributes`。请求生效的角色在 `SecurityContext.scope.roles` 中；`principal.roles` 和 `principal.policies` 已删除。
- **令牌契约都是值。** `TokenVerifier.verify(accessToken: String)` 和 `refresh` 返回 `VerifiedToken`（`me.ahoo.cosec.spi.token`），没有类型参数；`TokenRevoker.revoke` 接收令牌字符串。`TokenConverter` 接收 `TokenSubject`（`me.ahoo.cosec.login.token`），`Authentication<C>` 返回它。已删除：包装类 `SimplePrincipal`、`SimpleTenantPrincipal`、`TenantPrincipal`、`TokenPrincipal`、`TokenTenantPrincipal`、`SimpleTokenPrincipal`、`SimpleTokenTenantPrincipal`、`MembershipMarkedPrincipal`、`Delegated`、`findInDelegationChain`、`IssuedAtCapable`、`ExpiresAtCapable`、`MembershipMarkerCapable`、`AccessToken`、`RefreshToken`、`Token` 和 `TokenIdCapable`。自定义的 `TokenVerifier` 改为构造 `VerifiedToken`；它的构造函数拒绝无效声明，抛出的正是 `verify` 本来就会抛出的 `TokenVerificationException`。
- **`RoleCapable`、`PolicyCapable`、`SpacedRoleId`、`RoleIdCapable`、`SpaceIdCapable`、`Tenant`、`TenantCapable`、`SimpleTenant` 和 `Named` 已删除。** 声明名是 `MembershipClaims` 的常量，租户 id 是 `TenantId.DEFAULT` 和 `TenantId.PLATFORM`；`role@space` 语法不变。`SocialAuthenticationProvider` 保留 `name`。
- **`sub` 以 `(` 开头的令牌无效**（`IDN-12`），没有 `sub` 或 `jti` 的令牌也无效：令牌不能使用 CoSec 自己的 id，尤其是匿名主体 `(0)`。
- **`AttributeRequest` 携带 `tenant`**，`Subject.tenantId` 已删除：A 阶段提供者读取 `request.tenant`。
- **`SwitchTenantCredentials` 携带请求的 `context`（`SecurityContext`）**，不再是 `principal`，`switchTenant` 接收它。新令牌保留原令牌的应用。
- **登录改为挂起函数，`cosec-login` 不再引入 Reactor**（批次 R7c）。`Authentication.authenticate`、`TokenCompositeAuthentication.authenticateAsToken`、`AbstractSwitchTenantAuthentication.switchTenant`、`SocialAuthenticationProvider.authorizeUrl` 和 `authenticate`、`SocialAuthentication.authorizeUrl` 以及 `SocialUserPrincipalConverter.convert` 都是 `suspend` 函数，直接返回值而不是 `Mono`。迁移方法：把重写改成 `suspend` 并返回值；用 `awaitSingle()` 等待 Reactor 客户端；把控制器函数改成 `suspend`。用 `Mono.empty()` 拒绝登录的代码要改为抛出异常，因为不再有“空”的回答。通过 `cosec-login` 编译 Reactor 的模块需要自行声明它。`cosec-social` 不再引入 Guava。
- **`DefaultAuthenticationProvider` 和 `SocialProviderManager` 改为类**，starter 为每个应用上下文各装配一个（#1222）。对旧单例的调用请换成 `AuthenticationProvider` 或 `SocialProviderManager` Bean；`SocialAuthentication` 通过构造函数接收管理器。
- **`JustAuthProvider` 运行在自己的调度器上**，每个提供方最多 16 个线程，社交登录不再需要 `Schedulers.boundedElastic()`。
- **库模块导出其公共类实现的类型**（`cocache`、`kafka`、`ip2region`、`micrometer`、`opentelemetry`）：只为编译它们而添加的依赖可以去掉。

- **公共 API 已归类**（RFC 0010 C4）。应用需要的保持公开；其余的是 `@InternalCoSecApi`（应用永不选择加入）或 `internal`。`cosec-engine` 的大部分、`cosec-model` 的条件 AST、`cosec-runtime` 的快照视图、影子记录与文档模型、控制面的 `ControlPlane`、授权与访问分析，以及 starter 的自动配置类现在都是内部的。如果你编译时依赖其中之一，请改用文档页面描述的方式：检查和监听器都还在。测试工具里只有 `PolicyTestKit`、`PipelineFixture`、`EngineOutcome` 及其异常是公共的。
- **`PipelineComponents` 成为普通类**，`SnapshotOptions` 也是；两者都失去了 `copy` 和 `componentN` 函数。`PipelineComponents` 不再接受 `engine`。不使用 Spring Boot 的宿主从 `SuspendingSnapshotSource.of(store)` 获得快照。
- **`PolicyDocumentCompiler.compile` 成为内部 API。** CI 检查调用 `validate`；`forPlatform(platform)` 是对应的重载。
- **公共 `data class` 现在只是不会增长的值**（RFC 0010 C4）：`Identity`、`Identity.Credential`、`TokenSubject`、`SocialUser`、`ForwardedIdentity`、`Audit*Data`、`ShadowReport`、`ShadowSample`、`InstalledDocument`、`InstanceStatus` 以及 starter 的 `*Properties` 失去了 `copy` 和 `componentN`。`Identity`、`Identity.Credential`、`TokenSubject` 和影子记录仍按值比较；其余的不再定义 `equals`。
- **需要访问令牌的地方拒绝刷新令牌，需要刷新令牌的地方也拒绝访问令牌**（`IDN-13`）。`JwtTokenConverter` 在每个刷新令牌上写 `cosec.token: "refresh"`，带该声明的令牌不会完成认证；刷新只读取带该声明、且 `iat` 与访问令牌相同的令牌。此前签发的访问令牌在过期前继续有效；它们的刷新令牌没有该声明，不能再刷新，其用户需要重新登录。
- **刷新令牌属于会话**（RFC 0009，第二轮复审 M2）。登录会开始一个会话，即一族令牌对，两个令牌都带有它的 id、登录时间和代数（`sid`、`auth_time`、`cosec.generation`），保存在 Redis 的 `<cosec.key-prefix>:token:family:<sid>` 下（[会话](../integration/authentication.md#sessions)）。只有当前一对能刷新；更早的一对再次出现会结束会话；会话从登录起持续 `cosec.jwt.session.lifetime`（30 天）；登出会结束整个会话。对应用的影响：
  - **每个用户需要重新登录一次：** 本版本之前签发的令牌对不带会话，刷新时被拒绝（`token-invalid`）；它的访问令牌在过期前仍可用。
  - **通过 `TokenCompositeAuthentication` 签发令牌**，它会开始会话；或者用 `TokenFamilies.start` 和 `TokenSubject.inSession` 自己开始会话：单独由 `TokenConverter.toToken` 签发的令牌对永远不能刷新。`TokenCompositeAuthentication` 和 `SimpleRefreshTokenAuthentication` 接收一个 `TokenFamilies`（starter 提供的 Bean）。
  - **客户端只保留最新的一对：** 在更新的一对签发之后出示旧的一对会结束会话，除非发生在那次刷新之后的 `cosec.jwt.session.rotation-leeway`（30 秒）之内：此时它会得到最新一代的一对令牌，因此响应丢失的刷新可以重试。
  - **`TokenRevoker.revoke(compositeToken)`** 用于访问令牌已过期时登出；`revoke(accessToken)` 现在也会结束会话。自定义的 `TokenRevoker` 要实现两者。
  - **切换租户需要访问令牌：** `SwitchTenantCredentials.accessToken`，`AbstractSwitchTenantAuthentication` 接收 `TokenVerifier`；新令牌保留登录时间。
  - **不使用 `cosec-cocache` 时，请注册 `TokenFamilyStore`**，否则启动失败（单实例可用 `InMemoryTokenFamilyStore`）。Redis 中每个存活的会话一个 key。
- **无法读取的吊销存储会被如实报告，绝不读成“未吊销”**（第二轮复审 H1，`IDN-14`）。存储无法读取时，`RevocationReader` 抛出 `RevocationUnavailableException`；吊销表的 `unknown-read` 锁定为 `throw`，设置 `cocache.caches.[RevokedTokenCache].unknown-read` 会让启动失败。此时流水线仍像以前一样接受请求的令牌；刷新改为失败而不签发令牌，审批人的吊销无法读取时，控制面不发布任何东西。读取失败时回答“未吊销”的自定义 `RevocationReader` 改为抛出 `RevocationUnavailableException`。若请求也要拒绝，请设置 `cosec.authentication.revocation.fail-closed: true`，它取代了吊销表上的 `unknown-read: throw`。
- **吊销只有一个判断，令牌契约不做阻塞 I/O。** 按契约，`TokenVerifier` 是纯计算。`RevocableTokenVerifier` 已移除：流水线、刷新和登出都通过 `TokenRevocation`（`cosec-runtime`）读取 `RevocationReader` 中的吊销。`TokenStore` 只保留写入 `revoke` 和 `revokeSubject`，二者改为 `suspend`；同步的 `isRevoked`、`subjectRevokedBefore` 已移除（请通过 `RevocationReader` 读取），`TokenStore.NoOp` 也已移除。`TokenRevoker.revoke` 和 `revokeSubject` 改为 `suspend`：从 `suspend` 处理函数中调用即可，无需再移出事件循环。`DefaultTokenRevoker` 移到 `me.ahoo.cosec.runtime.token`，并接收 `TokenRevocation`。`SimpleRefreshTokenAuthentication(tokenVerifier)` 改为 `SimpleRefreshTokenAuthentication(tokenVerifier, tokenRevocation)`，不再使用自己的调度器。starter 的 `TokenVerifier` Bean 是普通的 `JwtTokenVerifier`，另外提供一个 `TokenRevocation` Bean。自定义的 `TokenStore` 以 `suspend` 函数实现两个写入，读取则作为 `RevocationReader` Bean 提供。
- **实例状态携带文档格式级别**（RFC 0010 C5）：没有 `formatLevel` 的实例状态会被本版本的控制面拒绝，读取到它的影子窗口会失败，直到所有实例升级完毕（状态一分钟后过期）。

- **控制面拒绝原本到运行时才失败的内容。** 平台变更把被其他文档引用的策略改成边界、或把被引用的边界改成策略，是 `REFERENCES`（`422`、`CTL-30`）。绑定指向平台边界同样是 `REFERENCES`：这样的绑定从未授予任何东西，它适用的每个请求都是 `503`，含有它的文档要先移除它才能再次发布。变更写入的 id 不能含 `/`（`V2`、`422`）：以 `*` 结尾的规则匹配不到这样的条目。权限策略的 `perm:<app>/<id>` 可以含；文档中已有这种 id 的条目可以保留或删除。
- **从平台或应用文档删除 DENY 需要第二个人**（RFC 0007 §7.4.4、`CTL-31`）。此前，没有 `cosec:approve` 的平台主体可以独自发布只删除条目的变更。现在，拿掉 DENY 的删除（含 DENY 的策略，或所绑策略含 DENY 的绑定，也包括经删除角色或以更少权限 `putRole` 去掉的）会停在 `AWAITING_APPROVAL`，每项带一个 `NOT_ANALYZED` 升级；开启 `four-eyes` 时信任根的这类变更也是如此（[审批](../policies/testing.md#approval)）。删除 ALLOW、租户删除自己的 DENY 以及撤销 break-glass 不变。请为紧急删除平台 DENY 安排好审批人。
- **`cosec:grant` 看角色实际派生了什么**（RFC 0012 §7.2）：让 `"*"` 角色派生出新绑定的 `putCatalog` 或 `putPolicy`，与 `putRole` 一样需要该角色作用域上的 `cosec:grant`。
- **break-glass 只需要 `cosec:breakglass`**（RFC 0007 §8）：申请、批准后发布以及撤销都不再额外需要 `cosec:publish`。其他变更不能用 `cosec:breakglass` 代替。
- **回滚可以带 `confirmations`**，恢复带 `onMissing: "nomatch"` 的 DENY 时与提议相同。此前这样的回滚根本无法确认。
- **`repair-references` 也会删除**未能写入头的发布登记的条目，并输出 `Removed <n> reference(s) no head refers to.`。见[修复反向引用索引](#repair-the-back-reference-index)。

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
- **未知的 `cosec.*` key 总是让启动失败**（批次 R6、`SNP-9`）。`cosec.unknown-properties` 和 `UnknownPropertiesMode` 已删除，设置这个配置项本身现在就是未知 key：请移除它。错误信息列出这些 key，并链接到[已删除和改名的 key 对照表](#removed-and-renamed-properties)，不再逐个给出替代项。
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

8.0.0-alpha.2 是预览版，没有兼容性承诺。此后来自 [RC 安全评审](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md)的以下变更，让授权分析按执行时的方式读取作者的权限，因此一些过去可以单独发布的变更现在要等待审批（[策略测试](../policies/testing.md#approval)）。文档的内容无需迁移，不过 8.0.0 经 CoCache 8 保存数据，而 CoCache 8 读不了 alpha.2 经 CoCache 6 写入的内容：请在所有实例停止后用 `cosec-migrate cocache8` 迁移（[经 CoCache 6 写入的数据](#data-written-through-cocache-6)）。从 **身份转发** 开始的那些变更涉及网关与租户隔离的边缘（RC1-5、RC1-6、RC1-15 至 RC1-18、RC1-48、RC1-49），改变网关转发什么、转发给哪些路由：升级网关之前请先阅读。

- **任意类型的 token 属性**（RC1-2、`GRT-5`）。分析按引擎的方式读取 `subject.attributes.*`，布尔值和数字读作它们的文本。token 中写着 `"suspended": true` 时，条件为未被 `suspended` 的 ALLOW 不再算作作者持有。代码中，`Operator.attributes` 与 `Grantor.attributes` 是 `Map<String, Any>`，即 token 声明原样，alpha.2 只保留了字符串。
- **会到期的绑定**（RC1-3、`GRT-3`）。作者经带 `expiresAt` 的绑定持有的权限，只包含自身绑定不晚于它到期的授出；没有 `expiresAt` 的授出、到期更晚的授出、把策略设为可附加，都是升级，短缺为 `EXPIRY`。
- **删除平台也定义了同一 id 的租户策略**（RC1-9、`GRT-4`）按把平台的策略授给租户中该 id 的绑定与附加来分析。两边都定义的 id 不给作者任何权限。
- **每一份 app 文档**（RC1-4、`GRT-6`）。租户变更的分析读取 app 列表（`<s>:control:apps:all`，[存储](./storage.md#publishing-through-the-control-plane)）列出的每一份 app 文档，而不只是变更点名的那些，所以 app 绑定给作者某个角色的 DENY，会在触及该 app 的每个作用域中限制作者可以授出的东西。读不到的 app 文档使这样的授出成为升级（`UNREADABLE`）。分析只在 app 列表完整时信任它，而升级上来的存储的列表并不完整：**升级后，在 [`cosec-migrate repair-references`](#repair-the-back-reference-index) 运行之前，覆盖所有 app 的每个租户授出（app 为 `*` 的绑定，或附加，包括经条件收窄到变更没有点名的 app 的授出）都需要审批**（`UNREADABLE`），控制面会记一条点名该命令的 WARN，每分钟至多一次。
- **平台策略不能占用租户的 id**（RC1-9、`CTL-21`）。平台变更新增的策略 id 若已被某个租户文档定义或绑定，变更被拒绝（`V2`、`422`，列出这些租户文档）。自己定义了该 id 的租户，不再阻止平台删除它自己的同 id 策略。反向引用索引只记下租户在升级之后新增的 id；租户文档原有的 id 只有经 `repair-references` 才进入索引：**在每个控制面实例和每个 `cosec-migrate` 二进制都运行这个版本之后，以 Kubernetes Job 运行一次 [`cosec-migrate repair-references`](#repair-the-back-reference-index)**，使用与控制面相同的 Redis 和 key 前缀；任何一次降级之后再运行一次。在它运行之前，平台策略若占用了某个租户在升级前就有的自有策略的 id，不会被拒绝，只会在那个租户中二义，执行时对此失败即拒绝。
- **升级**新增原因 `UNRESOLVED`（新绑定指向没有任何文档定义的策略，RC1-32）、`UNREADABLE`（RC1-4）与 `TENANT_BINDING`（应用文档的变更放宽了的每条租户绑定，RC1-34、`APR-6`）。`TENANT_BINDING` 仅供参考：应用变更因 `NOT_ANALYZED` 而需要审批，这一项只写出绑定和它所在的租户文档。穷举 `UnknownReason` 或 `Shortfall` 的代码要加上 `UNRESOLVED`、`UNREADABLE` 与 `EXPIRY`。
- **发布后的头变化**（RC1-33）。变更检查时读过的其他文档或 app 列表在它写入之前变化时，控制面在 `PUBLISHED` 之后记 `HEADS_MOVED` 或 `REVIEW_NEEDED`，变化的头在新成员 `moved` 中（[一次发布比较什么](./storage.md#what-a-publish-compares)）。处理每一种 `ChangeEvent.Kind` 的变更监听器要加上这两种。
- **信任根属于平台**（RC1-10、`APR-3`）。只有持有 `cosec:approve` 的平台主体发布自己的变更时不经分析；平台授予了 `cosec:approve` 的租户主体，它自己的变更现在要经分析，它审批别人的变更。租户的边界属于平台：租户主体的 `SetBoundary` 返回 `403`，它以 `REPLACE` 做的 `apply` 或回滚如果会删除或改变平台设定的边界，同样返回 `403`；也只有平台主体能审批、驳回或放弃设置边界的变更（`BDY-2`）。API key 从来不是信任根，即使其主体是持有 `cosec:approve` 的平台主体；任何名为 `api-key` 的凭证（包括转发来的身份所带的）也不是。开启 `four-eyes` 时，平台主体的变更需要另一位平台主体审批。
- **`cosec:platform-admin` 只由平台绑定**（RC1-36、`TNT-2`）：在平台文档中，或在平台租户自己的文档中（`bootstrap` 就写在这里）。绑定它的租户或 app 文档不再能编译；升级前请删除这样的绑定。
- **审批是人的行为，并会再次核查**（RC1-11、`APR-7`、`APR-8`）。以 API key 认证的主体不能审批（`403`）。审批记录审批人的凭证（`jti`、`iat`）；变更发布之前吊销这个令牌，或吊销审批人的令牌，审批就不再算数。成员关系来自成员关系提供者的审批人，既要按它审批时持有且现在仍持有的角色被放行，也要按提供者现在的答复被放行；starter 现在把应用的 `MembershipProvider` 交给控制面（RC1-13）。**没有记下凭证 id 的审批不算数**：升级前审批过的每个变更，发布时都会回到 `AWAITING_APPROVAL`，需要重新审批；凭证不带 id 的主体不能审批（`403`）。API 中变更的视图（`GET /changes/{id}` 及各步骤的答复）不再显示审批的 `memberships`、`attributes`、`credentialId` 和 `issuedAt`。代码中，`AuthenticatedPrincipal` 多了 `issuedAt` 和 `apiKey`。
- **starter 检查审批如何被吊销。** 控制面和数据面管道一样需要吊销读取器：一个 `RevocationReader` bean，或 CoSec 的令牌加 `cosec-cocache`；两者都没有时启动失败（审批人的凭证从不吊销时，声明 `RevocationReader.NONE`）。吊销的保留时长 `cosec.jwt.token-validity.refresh` 短于 `cosec.control.approval.expire-after` 时，启动也会失败。
- **被占用的暂存槽可以被抢占**（RC1-12、`CTL-22`、`BRK-9`）：撤销 break-glass，以及持有 `cosec:skip-shadow` 的人提交的只收窄变更（[抢占被占用的暂存槽](../policies/testing.md#taking-a-busy-slot)）。原先持有槽的变更成为 `CONFLICTED`。答复丢失后重试的发布报告 `PUBLISHED`。
- **租户 `(0)` 中的 `Anyone`**（RC1-35、`GRT-7`）：这样的授出会作用于未认证的调用方，需要审批（`ANONYMOUS`）。穷举 `UnknownReason` 的代码要加上 `ANONYMOUS`。
- **控制面资源中的默认空间写作 `(default)`**（RC1-37、`SCP-9`），以 `(` 开头的空间 id 保留给 CoSec：声明这种空间的请求返回 `403`（`scope-rejected`），在这种空间中绑定的文档不能编译。为 `scope/<tenant>/<app>/-` 写的控制面策略现在指名为 `-` 的空间；默认空间请写 `scope/<tenant>/<app>/(default)`。**写在 `scope/*/*/-` 上的控制面 DENY 不再作用于默认空间**：升级前把它改写为 `scope/*/*/(default)`，否则它原先挡住的授出会被放行。空间 id 以 `(` 开头的绑定会让所在文档无法编译，这种空间中的请求也会被拒绝：升级前在每份文档中查找 `"space": "(`，把这样的绑定移到你自己的空间 id，并把该空间的成员关系一并迁过去。
- **break-glass**（RC1-27、RC1-29、RC1-30）：`cosec-migrate` 不写 break-glass 绑定（[root 变为 break-glass](#root-becomes-break-glass)）；`BreakGlass.request` 自行派生绑定 id，不再有 `binding` 参数；审批 break-glass 需要 REQUIRED 变更监听器（否则 `503`，`BRK-3`）。
- **撤销 break-glass 按效果作答**（R4-1）。`DELETE /break-glass/{bindingId}` 只在该绑定在平台文档的头上不再生效之后才返回 `200`，无论是谁删除的。撤销后绑定仍在生效时，现在返回 `409`，`detail` 写明调用方的变更所处的状态（`CONFLICTED` 等），此前返回的是带着该变更的 `200`：把 `200` 当作"已撤销"而不看 `state` 的客户端，需要处理这个 `409`。平台文档中没有的绑定 id 现在返回 `404`；该 id 的绑定不是 break-glass 绑定时（无论是否到期）返回 `409`（`binding [<id>] is not a break-glass binding`）；两者都不提议任何变更，此前都返回带着 `DRAFT` 变更的 `200`（`BRK-11`）。被另一个撤销抢占，或遇到已变化的头的撤销，会再撤销一次，至多再试三次。
- **放弃变更**（RC1-51、`BRK-10`）。撤销仍在生效的 break-glass 绑定的变更只有其作者能放弃：其他人（包括被撤销的主体）得到 `403`。请改为再撤销一次该绑定，这会发布停住的撤销，或抢走它的暂存槽。每次放弃都记一条新的变更事件 `ABANDONED`；放弃 break-glass 变更需要 REQUIRED 变更监听器（否则 `503`）。处理每一种 `ChangeEvent.Kind` 的变更监听器要加上 `ABANDONED`。
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
- **没有使用者的公共 API 已移除。** `KafkaAuditEventSink`：starter 注册的是 `KafkaAuditListener`，它写入相同的 key 和 value。`SimpleSecurityContext` 及其扩展属性 `CoSecPrincipal.tenant`：请求的 `SecurityContext` 由管道创建，测试中请 mock 或自行实现该接口。引擎请求中的 `AuthzRequest.scope`、类 `me.ahoo.cosec.model.Scope`，以及 `Subject.roles`、`policies` 和 `root`，引擎都不读取：构造 `AuthzRequest` 或 `Subject` 的代码去掉这些参数即可。引擎使用的租户、角色和附加策略在 `FlatRequest.scope` 与 `FlatRequest.memberships` 中。`Subject.tenantId` 保留，供 stage A 的 `AttributeProvider` 使用，它在作用域解析之前运行。

### 经 CoCache 6 写入的数据 {#data-written-through-cocache-6}

8.0.0-alpha.2 经 CoCache 6 在 Redis 中保存数据；8.0.0 经 CoCache 8 保存，key 不变，但 CoCache 8 读不了 CoCache 6 的格式。它
在这种格式中读到的内容按读不懂的内容处理，失败即拒绝：需要这种文档的请求是 `503`（`SourceUnavailable`），在其中被吊销的令牌
或主体是 `503` 而不是 `401`，控制面 API 回答 `500`。没有任何读取会把这样的值当作不存在，所以被吊销的令牌绝不会重新被接受，
吊销表也不会。

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
   这条命令会删除标记。
6. 启动 8.0.0 实例。每份文档的修订号从 1 重新开始；迁移之前的历史不会保留，如果需要，在第 5 步之前保存一份
   `<p>:control:revision:*`。

摘要现在是 SHA-256，alpha.2 用的是 CoCache 6 的 SHA-1：控制 API 的修订历史、变更的基线与检查，以及提到摘要的事件，都写作
`SHA-256:<64 位十六进制>`。自己实现的 `SnapshotDocumentStore` 必须给出 32 字节的 SHA-256 摘要，否则每次读取都失败即拒绝
（[扩展](../architecture/extending.md#snapshot-document-stores)）。

## 从 8.0.0-alpha.1 升级 {#from-8-0-0-alpha-1}

8.0.0-alpha.1 是预览版，没有兼容性承诺。此后来自 [RC 安全评审](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md)的以下变更，会破坏基于它写成的决策、代码或配置。7.x 部署在下面各节中读到它们。

- **角色上限只移除 ALLOW**（RC1-1、`BND-9`、`IDN-2`）。带上限的凭证（带 `roles` 的 API key）仍然得不到其主体附加策略和主体绑定的授权，但它们的 DENY 现在作用于它：主体被拒绝的请求，该主体的每个 key 都被拒绝，无论是否有上限。alpha.1 中上限把这些 DENY 也一并去掉了，因此带上限的 key 能做主体不能做的事。无需迁移；要看哪些请求的结果会变，用 `principalBindings: false` 对它们做 `simulate`（[策略测试](../policies/testing.md)）。授权分析以同样方式计入带上限作者的 DENY。
- **break-glass 按来源判断**（RC1-26、`BRK-8`）。只有 CoSec 自己的 `JwtTokenVerifier` 校验过的令牌才能得到 break-glass。包装其他 `TokenVerifier` 的 `TokenAuthenticator` 认证其令牌时不带 break-glass，并以它的 `method`（默认 `token`）而不是 `jwt` 命名凭证。方式 `jwt` 是保留名：`AuthenticatedPrincipal` 拒绝你的代码用这个方式构造的凭证（`IllegalArgumentException`，即 `503`），`TokenAuthenticator` 也拒绝为非 CoSec 的校验器设置 `method = "jwt"`。请按凭证给你的认证器的方式命名（[扩展](../architecture/extending.md#authenticators)）。
- **改名后的 API key 头是携带凭证的请求头**（RC1-21、`CND-5`）。设置了 `cosec.pipeline.api-key-header` 时，读取该请求头的文档（条件或限流规则的 `key`）无论在控制面还是实例上都不再能编译，管道也从不为条件读取它。升级前删除这类引用。代码中，`ConditionChecker`、`PolicyDocumentCompiler.forPlatform` 和 `of`、`PlatformCompiler`、`ChangeChecks`、`Simulator` 与 `HttpAttributes` 以新参数接收该请求头。
- **`tenantId` 声明不是字符串时令牌无效**（RC1-22），alpha.1 则把它读作默认租户 `(0)`。请以字符串签发租户。
- **租户切换重新得出成员关系**（RC1-23）。`AbstractSwitchTenantAuthentication.switchTenant` 回答一个 `TenantMembership`，即用户在目标租户中的角色、策略和属性（或 `TenantMembership.fromProvider()`），而不是一个主体；`authenticate` 只依据它构造新主体。包装 `previousPrincipal` 的实现会把先前租户的角色带进新令牌（[租户](../integration/authentication.md#tenants)）。
- **JWT 密钥有最短长度**（RC1-24）：`hmac256`、`hmac384`、`hmac512` 分别至少 32、48、64 个 UTF-8 字节。更短的 `cosec.jwt.secret` 会让启动失败；在签发方和每个校验方设置更长的密钥，用旧密钥签发的令牌随之失效。
- **社交登录只签名提供方拥有的字段**（RC1-25）。`DirectSocialUserPrincipalConverter` 只给主体两个属性 `cosec_oauth_provider` 和 `cosec_oauth_user_id`，不再使用用户可编辑的提供方原始用户信息，也不再写入它转换的 `SocialUser`。`attributes` 中带 `apiKeyId`（CoSec 为 API key 设置）的令牌按 `token-invalid` 拒绝，`JwtTokenConverter` 也不会签发这样的令牌。要保留资料字段，请声明你自己的 `SocialUserPrincipalConverter`，并且只保留用户无法更改的字段（[社交登录](../integration/authentication.md#social-login)）。

## 7.x 到 8.0 {#_7-x-to-8-0}

8.0 只支持从 7.x 升级。从 6.x 升级时，先按
[`7.x` 分支上的升级指南](https://github.com/Ahoo-Wang/CoSec/blob/7.x/wiki/operations/upgrading.md)升到 7.x（包括其中的数据迁移），
再回到这里。`cosec-migrate convert` 拒绝不是 7.0 布局的 Redis 数据。

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
   端点。[转换](#migrating-the-data)会报告每一条无法翻译的策略。
3. **用 `cosec-migrate convert` 转换数据**，修正每一个拒绝项，直到转换被接受。对照[8.0 在哪些地方决策不同](#what-8-0-decides-differently)
   阅读 `report.json` 中的语义改动。
4. **更新依赖和代码**：把构建和运行环境换到 JDK 21，用模块本身替换 starter 的 capability，把 import 改到新的包（[模块、制品与包](#modules-artifacts-and-packages)），删除对[已删除 API](#removed-and-replaced-apis) 的使用，改为注册 8.0 的扩展 bean。
5. **更新配置**：改名或删除每一个 [7.x key](#removed-and-renamed-properties)，并对新属性做出决定。8.0 不读取的 `cosec.*` key 会让启动
   失败，并给出替代它的配置。另外三种常见情况也会让启动失败：
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

它的命令如下。退出码：`0` 完成，`1` 数据被拒绝或写入失败，`2` 用法错误。`publish`、`bootstrap` 与 `repair-references` 接受 `--cluster`，用于 Redis Cluster：这时 `--to-redis` 写种子节点，以逗号分隔（`redis://node-1:6379,redis://node-2:6379`）。其他命令读 Redis 的客户端不支持 Cluster，会拒绝这个选项。每个命令只接受为它列出的选项：别的选项，例如 `publish --dry-run`，在读写任何东西之前就是用法错误，绝不会被忽略。

| 命令 | 作用 |
|---|---|
| `convert` | 读取 7.x 数据（Redis 或导出的文件），翻译后把 8.0 文档和一份报告写入一个新目录。不向 Redis 写任何东西。 |
| `publish` | 把一次已接受的转换中的文档作为它们的第一个修订写入 Redis，经过控制面的存储。从不触碰 7.x 的 key。 |
| `bootstrap` | 把平台租户的一个主体绑定到 `cosec:platform-admin`，让控制面有人可以写入。 |
| `partition plan` | 分别用 `--partition platform` 和 `by-owner` 转换两次，为每个租户写出一份计划，由控制面提交。不向 Redis 写任何东西。 |
| `repair-references` | 把每份文档的头所引用、而反向引用索引缺少的条目补进索引，删除没有任何头引用的条目，并补全 app 列表。 |

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
| `DUPLICATE_ID` | 导出中同一条策略出现两次，导出中同一个应用出现两次（一项会替换另一项，它的授权就丢了），同一个应用中同一个权限出现两次，或两个应用 id 或租户 id 转换后的文件名只有大小写之差（`Order` 与 `order`：在 macOS 和 Windows 上是同一个文件）。文件名不同的 id，例如 `strasse` 与 `straße`，不算冲突。 | 只保留一个，或改名。 |
| `OWNER_TENANT` | 使用 `--partition by-owner`，而策略的 `tenantId` 不指向任何租户。 | 设置它的 `tenantId`，或改用 `--partition platform` 转换。 |
| `CONDITION_NOT_TRANSLATED` | 没有精确翻译的 7.x 内置条件，例如不是字面量、字面量前缀或字面量选择的 `regular`，或者动作不是 `all` 的语句中的 `path` 条件。 | 按消息给出的条件（`eq`、`in`、`startsWith` ……）重写。 |
| `EXPRESSION_CONDITION` | `spel` 或 `ognl` 条件。 | 用[策略语言](../policies/language.md)的条件重写。 |
| `CUSTOM_CONDITION` | 自定义 `ConditionMatcherFactory` 的条件。 | 把它读取的值移进一个 `AttributeProvider`，并在这个属性上写条件（[扩展](../architecture/extending.md)）。 |
| `ACTION_NOT_TRANSLATED` | `#{...}` 路径模板、不是 `/` 的路径 `separator`、成员方法不同的动作数组，或者不是 HTTP token 的方法。 | 见[动作中的路径模板](#path-templates-in-actions)；按方法拆分语句；删除任何请求都不可能有的方法。 |
| `CUSTOM_ACTION` | 自定义 `ActionMatcherFactory` 的动作。 | 改写为 HTTP 路径和方法，或路由映射出的动作。 |
| `DOES_NOT_COMPILE` | 翻译后的语句无法编译，例如超出 8.0 接受范围的路径模式；或文档整体无法编译，例如每条策略都在路径模式的预算之内，合在一起却超出。拒绝项指出能追溯到的策略，否则指出文档。 | 见消息和[策略语言](../policies/language.md)。 |

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
cosec-migrate publish --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --in ./converted [--resume]
```

`publish` 只读取被接受的转换，而且只读取 `report.json` 列出的文件，并校验其中记录的 SHA-256：`convert` 之后被修改、缺失或新增的文件会让
它停止。然后它编译每一份文档，按平台文档检查每份应用文档的引用，并拒绝含 break-glass 绑定的文档，什么也不写（`bootstrap` 同样拒绝）。它经过控制面的存储把每份文档写成修订 1，所以文档的修订历史从这里开始；并且只在文档尚不存在时写入：它从不覆盖 8.0 数据。

每份文档写一次。如果某一次失败，消息会列出已经写入的文档；用 `--resume` 再运行一次，它跳过内容相同的已发布文档，拒绝内容不同的。
`--key-prefix` 是部署的前缀，与 [`cosec.key-prefix`](./configuration.md#key-prefix) 相同。`publish` 没有 `--dry-run`；想知道它会做什么，读 `report.json`。

`publish` 之后，只通过控制面修改策略。再次运行 `convert` 和 `publish` 不会更新已存在的文档。

#### 引导控制面 {#bootstrap-the-control-plane}

```bash
cosec-migrate bootstrap --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --platform-admin alice [--force]
```

控制面用快照中的策略授权自己的写操作，所以没有管理员时谁都不能写入。`bootstrap` 只写平台租户的文档 `Tenant((platform))`；平台文档本身来自 `publish`，所以先发布。`bootstrap` 在平台租户自己的文档中，把平台租户的主体 `alice`
绑定到内置策略 `cosec:platform-admin`，并记为一个 `BOOTSTRAP` 种类的修订。当这份文档已经把某人绑定到 `cosec:platform-admin` 时它拒绝执行；
`--force` 用于这些管理员全部丢失后的恢复。持有 Redis 凭证的人本来就能写入；`bootstrap` 只是留下一条记录。

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

`kubectl -n cosec logs job/cosec-repair-references` 显示工具的输出；命令失败时（退出码 `1` 或 `2`）Job 也失败。`--key-prefix` 设为你的实例的 [`cosec.key-prefix`](./configuration.md#key-prefix)。运行 `bootstrap` 时把 `args` 改为 `["bootstrap", "--to-redis", "$(REDIS_URI)", "--platform-admin", "alice"]`。Redis Cluster 则给支持的命令加上 `--cluster`，并在 Secret 里写以逗号分隔的种子节点。

`convert`、`publish`、`partition plan` 与 `api-keys` 读写目录：在主容器的 `/data` 挂载一个 PersistentVolumeClaim，路径写成 `--out /data/converted` 与 `--in /data/converted`。把 `convert` 作为一个 Job 运行，从卷上读 `report.json`（再起一个挂载它的 Pod，或用 `kubectl cp`），转换被接受之后才把 `publish` 作为另一个 Job 运行：`publish` 只接受 `report.json` 列出的、SHA-256 与之一致的文件。

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
`cosec-cocache`（它带来 Redis 吊销存储），或者注册自己的 `TokenStore` 和 `RevocationReader` bean。7.x 中没有 `cosec-cocache` 时，starter
使用一个什么都不记录的存储，登出悄无声息地不起作用。只签发令牌、从不校验令牌的服务可以改为设置 `cosec.jwt.enabled=false`
（[登出与吊销](../integration/authentication.md#logout-and-revocation)）。7.x 写下的吊销继续有效：8.0 从相同的 key（`<prefix>:token:revoked:*`）
读取它们。吊销检查报告的错误返回 `503`，永远不会接受该令牌（`PIP-7`）。

#### 令牌与 API key {#tokens-and-api-keys}

- 7.x 签发的令牌继续可用：8.0 读取同样的声明（`roles`、`policies`、`attributes`、`tenantId`），并按 7.x 的方式读取角色声明（`role` 在默认空间，
  `role@space` 只在该空间，规则 `MEM-3`）。
- **带 `act` 声明的令牌现在会被拒绝**，结果为 `token-invalid`（`IDN-4`）。7.x 忽略这个声明，因此 RFC 8693 委托令牌被当作它的 `sub`
  读取，执行方得到主体的权限。CoSec 自己从不签发这种令牌；接受其他签发方令牌的校验器在支持委托之前不得放行它们
  （[认证](../integration/authentication.md#tokens)）。拒绝时审计记录原因码 `DELEGATION_UNSUPPORTED`；放行时审计记录 `ALLOW`，
  原因只出现在决策事件的 `subject.rejected` 中。刷新或吊销这种令牌同样失败。
- **`tenantId` 声明为空或不是字符串**，或 `attributes` 声明中有 `apiKeyId`（CoSec 只为 API key 设置），令牌为 `token-invalid`；7.x 把这样的租户读作没有租户。
  **`cosec.jwt.secret`** 对 `hmac256`、`hmac384`、`hmac512` 分别至少 32、48、64 个 UTF-8 字节，否则启动失败。
- **break-glass（7.x 的 root）** 只作用于 CoSec 自己的 `JwtTokenVerifier` 校验过的令牌，从不作用于自定义 `TokenVerifier` 的令牌，也不作用于其他认证器认可的主体（`BRK-8`）。
- **租户切换与社交登录。** `AbstractSwitchTenantAuthentication.switchTenant` 回答用户在目标租户中的 `TenantMembership`，而不是一个主体，因此新令牌不会带上先前租户的角色。
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

以下情况 key 被拒绝，此时命令以 `1` 退出，不写入任何 key：带属性（条件可能读取它们）、角色格式错误、id 含 `@`、`*` 或空白、某个角色以角色前缀开头、某个文档已经绑定了按 key 生成的角色，或附加的策略不能精确解析：不存在、不可附加，或租户和平台都定义了它但内容不同。这样的附加在 8.0 中让该 key 的每个请求都不确定，丢掉它又可能丢掉一个 DENY：先修正文档或 key。7.x 不保证 key id 唯一，所以以下情况 key 也会被拒绝：同一租户中另一个 key 的 id 相同（两者会变成同一个主体，或共用一个按 key 生成的角色）；`own-principal` 的 key 要迁往的主体 `apikey:<key id>` 已经存在，即某个文档按名绑定了它，或 `--memberships` 列出了它，这时 key 会获得该主体拥有的权限。`--memberships` 文件中同一主体出现两次时被拒绝。没有租户的 key 归入默认租户（`defaultTenant`）。

没有角色的 `own-principal` key 也会列在 `memberships` 中，`roles: []`。它的主体同样必须在你的 `MembershipProvider` 中存在，只是没有角色：提供者不认识的主体会被拒绝，带该 key 的每个请求都会得到 `401`。

1. 带 `--dry-run` 运行并阅读报告。
2. 在你的 `MembershipProvider` 的数据源中授予 `memberships`，并通过控制面（变更请求）添加 `principalBindings` 和 `roleBindings`。
3. 不带 `--dry-run` 再运行一次。每个 key 只在仍然是读取时的内容时才被改写，因此期间被吊销或停用的 key 绝不会被恢复：报告在 `changedSinceRead` 中列出它，命令以 `1` 退出并要求再运行一次。已经转换的 key 保持不变，因此命令可以重复运行。

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
| `micrometer-support` | `cosec-micrometer`，同时添加 `micrometer-core` |
| `openapi-support` | `cosec-openapi`，同时添加 `spring-boot-starter-actuator` 和 `springdoc-openapi-starter-common` |
| `kafka-support` | `cosec-kafka`，同时添加 `spring-boot-starter-kafka` |
| （8.0 新增） | `cosec-control`，即控制面，同时添加 `cosec-cocache` |
| （8.0 新增） | `cosec-cosid`，即来自 CosId 5 的 CoSec id（见下文） |

完整列表见[模块](../guide/introduction.md#modules)。已开启的功能所需的模块会在启动时检查：缺少适配器模块的 Web 宿主、缺少 `cosec-gateway` 的网关、缺少 `cosec-cocache` 的 `cosec.api-key.store=redis`、缺少 `cosec-kafka` 的 `cosec.audit.kafka.delivery=required`，都会启动失败并给出制品名（[缺少模块时启动失败](./troubleshooting.md#startup-fails-on-a-missing-module)）。

**`cosec-core` 不再存在。** 它的代码按性质拆分：应用要实现的端口在 `cosec-spi`（`me.ahoo.cosec.spi.*`）；文档、快照、令牌校验和写日志的审计 sink 在 `cosec-runtime`（`me.ahoo.cosec.runtime.*`）；登录与签发令牌在 `cosec-login`（`me.ahoo.cosec.login`）。starter 会引入这三个模块。只实现某个端口的库依赖 `cosec-spi` 即可。移动了的包：

| 7.x 包 | 8.0 包（模块） | 类型 |
|---|---|---|
| `me.ahoo.cosec.api.authentication`、`me.ahoo.cosec.authentication` | `me.ahoo.cosec.login`（`cosec-login`） | `Authentication`、`AuthenticationProvider`、`Credentials`、`CompositeAuthentication`、`DefaultAuthenticationProvider` |
| `me.ahoo.cosec.authentication.token` | `me.ahoo.cosec.login.token`（`cosec-login`） | `AbstractRefreshTokenAuthentication`、`AbstractSwitchTenantAuthentication` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.login.token`（`cosec-login`） | `TokenConverter`、`TokenCompositeAuthentication`、`RefreshTokenMismatchException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.spi.token`（`cosec-spi`） | `TokenVerifier`、`TokenStore`、`TokenRevoker`、`RevocationReader`、`TokenVerificationException`、`TokenExpiredException`、`TokenRevokedException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.runtime.token`（`cosec-runtime`） | `SimpleCompositeToken`（`RevocableTokenVerifier` 已移除：见[从 8.0.0-alpha.4 升级](#from-8-0-0-alpha-4)） |
| `me.ahoo.cosec.context` | `me.ahoo.cosec.servlet`（`cosec-webmvc`） | `SecurityContextHolder` |
| `me.ahoo.cosec.context.request` | `me.ahoo.cosec.runtime.http`（`cosec-runtime`） | `RemoteIpResolver`、`XForwardedRemoteIpResolver` |
| `me.ahoo.cosec.api.audit` | `me.ahoo.cosec.spi.audit`（`cosec-spi`） | `AuditEventSink` |
| `me.ahoo.cosec.audit` | `me.ahoo.cosec.runtime.audit`（`cosec-runtime`） | `LoggingAuditEventSink`、`AuditEventData` |
| `me.ahoo.cosec.apikey` | `me.ahoo.cosec.spi.apikey`（`cosec-spi`） | `ApiKey`、`ApiKeyReader`、`ApiKeyStore`、`InMemoryApiKeyStore` |
| `me.ahoo.cosec.limiter` | `me.ahoo.cosec.spi.ratelimit`（`cosec-spi`） | `RateLimit`、`RateLimitResult`、`RateLimiter`（[见下文](#removed-and-replaced-apis)） |
| `me.ahoo.cosec` | `me.ahoo.cosec.model`（`cosec-model`） | `CoSecException` |

8.0 的 SPI 也在这些地方：`MembershipProvider` 和 `MembershipClaims` 在 `me.ahoo.cosec.spi.membership`，`SnapshotDocumentStore` 和 `InMemorySnapshotDocumentStore` 在 `me.ahoo.cosec.spi.snapshot`，`AttributeProvider` 在 `me.ahoo.cosec.spi.attribute`，`Authenticator` 在 `me.ahoo.cosec.spi.authn`；`SnapshotSource` 在 `me.ahoo.cosec.runtime.snapshot`，文档编解码器和编译器在 `me.ahoo.cosec.runtime.document`，路由映射在 `me.ahoo.cosec.runtime.route`（[扩展 CoSec](../architecture/extending.md#extension-points)）。

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
| `SecurityPipeline`、`SecurityDecision`、`RequestParser`、`SecurityContextParser` | pipeline；凭证用 `Authenticator` bean。 |
| `CoSecAuthorizationCustomizer` | `Authenticator`、`DecisionListener`、`AttributeProvider`、`MembershipProvider`、`RateLimiter` 的 bean。 |
| `Policy`、`Statement`、`PermissionVerifier`、`ActionMatcher`、`ConditionMatcher`、`AppPermission`、`AppRolePermission`、`RolePermission` 以及 `cosec-api` 中其余的策略与权限模型 | 文档（[策略语言](../policies/language.md)）。覆写了 `verify` 的 `Statement` 没有对应物。 |
| `ActionMatcherFactory`、`ConditionMatcherFactory`、`MatcherFactoryRegistry`、内置匹配器、OGNL 与 SpEL | 只有内置的动作和条件。自定义条件读取的值改由 `AttributeProvider` 提供（[扩展](../architecture/extending.md)）。 |
| `BlockingAware` | 无：没有条件会执行代码。阻塞的查询放进会挂起的 `AttributeProvider`。 |
| `RootPrincipalMatcher`、`CoSecPrincipal.isRoot`、`root-enabled` | break-glass 绑定（[root 变为 break-glass](#root-becomes-break-glass)）。 |
| `BlacklistChecker` | 放在 `AttributeProvider` bean（`BlacklistAttributeProvider`）后面的 `BlacklistSource`。 |
| `RequestAttributesAppender`、`GatewayRouteRequestAttributesAppender` | `AttributeProvider`。 |
| `SimpleSecurityContext`、扩展属性 `CoSecPrincipal.tenant` | 无：请求的 `SecurityContext` 由管道创建。测试中请 mock 或自行实现该接口；租户是 `SecurityContext.tenantId`。 |
| `KafkaAuditEventSink` | `KafkaAuditListener`，一个 `DecisionListener`，classpath 上有 `cosec-kafka` 时由 starter 注册（[可观测性](./observability.md)）。记录的 key 不变，value 是审计事件（[可观测性的变化](#observability-changes)）。 |
| `PolicyRepository`、`AppRolePermissionRepository`、Redis 仓库与缓存、`SnapshotPublisher`、`RedisSnapshotPublisher`、`PolicyHistory`、`RedisPolicyRevisionStore`、对 `AppPermissionCache`/`RolePermissionCache` 的写入 | 写入用控制面（库或 HTTP API）；第一次写入用 `cosec-migrate publish`。 |
| `LocalPolicyInitializer` 以及 `init-repository`/`force-refresh` | `cosec-migrate convert` 与 `publish`，或控制面的声明式 `apply`。 |
| `CoSecExplainEndpoint`（`cosecExplain` actuator）、`AuthorizationExplainer`、`ExplainWarningCode` | 控制面的 `explain`（需要权限，可指定任一修订）；见[代码中的检查](../integration/checks.md)。 |
| 影子模式（`ShadowAuthorization`、`ShadowPolicyProvider`、`cosec.shadow.*` 文件） | 控制面变更的影子窗口；见[测试策略](../policies/testing.md)。 |
| `AccessAnalyzer` | 控制面的 `analyze`。 |
| `PolicyTestKit`（`cosec-core`） | `cosec-testkit` 模块中的 `PolicyTestKit`（[策略测试套件](#policy-test-suites)）。 |
| `cosec-api` 中 7.x 的请求模型：`Request`、`Attributes`、`AppIdCapable`、`DeviceIdCapable`、`RequestIdCapable`（及其别名 `AppId`、`DeviceId`、`RequestId`）、`COSEC_EXTEND_KEY_PREFIX`、`SpaceIdCapable.SPACE_ID_KEY`；`cosec-core` 的 `RequestSecurityContexts` 和 `InvalidRequestPathException` | 无：适配器读取 `HttpFacts`，安全上下文携带校验后的 `scope`。`SpaceIdCapable` 和 `SpaceId` 为 `SpacedRoleId` 保留。 |
| `InternalIds`（`cosec-api`） | 无。 |
| `me.ahoo.cosec.limiter.RateLimiter` 及其 `LocalRateLimiter`、`RedisRateLimiter`（`cosec-cocache`） | 挂起的 SPI `me.ahoo.cosec.spi.ratelimit.RateLimiter`；starter 注册它的 `LocalRateLimiter` 或 `ReactiveRedisRateLimiter`（[限流](../policies/rate-limiting.md)）。`RateLimit` 和 `RateLimitResult` 保留。 |
| `Jwts.toPrincipal(String)`、`Jwts.decode`、`Jwts.verifyTimeClaims` | 无：`Jwts` 是 `cosec-jwt` 的内部类。用会校验签名的 `TokenVerifier.verify` 把令牌变成主体。 |
| `PrincipalConverter`、`TokenVerifier.toPrincipal` | `TokenVerifier.verify`。 |
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

8.0 预览版中，应用可以用自己的 bean 替换执行控制面授权与审批的部分：`ControlAuthorizer`、`ChangeAuthority`、`Changes`、`ControlPlane` 和 `ControlHttp`。现在不行了：这些类型中任何一个出现第二个 bean，启动都会失败（`ControlHttp` 是在提供控制面路由时）。`ControlAuthorizer`、`DocumentConfirmation`、`PipelineControlAuthorizer`、`ChangeAuthority`、`Changes`、`DefaultControlPlane`、`ControlHttp`、`ControlHttpRequest` 和 `ControlHttpResponse` 是 `@InternalCoSecApi`：通过 `ControlPlane` bean 或它的 HTTP API 调用控制面。`ControlStore`、`ChangeOptions`、变更监听器、`Revisions`、`ControlSnapshot` 和维护相关的 bean 仍可替换。

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
| `cosec.authorization.enabled` | 已删除：`cosec.enabled=false` 关闭 CoSec。 |
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
| `cosec.authorization.cache.key-prefix` | `cosec.key-prefix`，每个 key 的前缀：先复制令牌吊销（[哪些 key 族会移动](#from-8-0-0-alpha-2)）。 |
| `cosec.api-key.key-prefix` | `cosec.key-prefix`：先复制 API key（[哪些 key 族会移动](#from-8-0-0-alpha-2)）。 |
| `cosec.authorization.snapshot.key-prefix`（8.0 预览版） | `cosec.key-prefix`：把它设为这个前缀（[哪些 key 族会移动](#from-8-0-0-alpha-2)）。 |
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
