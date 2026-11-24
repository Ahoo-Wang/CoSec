# RFC 0009：身份与凭据——主体、凭据与委托的分层

> 状态：已确认（2026-10-28，维护者："按你推荐"）；§9 的 PR-1、PR-3 已合入，PR-2（§5 的两个属性）与 §7 的委托移到 8.1 ·
> 上位文档：[RFC 0001](0001-target-architecture.md) P9、§7.1；[RFC 0004](0004-unified-model.md) §4.2、§4.5；
> [RFC 0006](0006-coroutine-pipeline.md) §5.3 ·
> 范围：`spi`（`apikey`、`membership`、`token`）、`runtime`（`authn`、`MembershipResolver`）、`jwt`、`login`、`cocache`、`document`（`CoSec-Identity`）、
> `migrate`、`spring-boot`、`conformance`
>
> **2026-11-23 合订**：此前的十段修订说明（2026-10-28 至 2026-11-20）已并入正文，被取代的说法已删去；章节号、章节标题与 I1–I5、PR-1–PR-3、
> Q1–Q7、IDN 各条的编号不变。§11 是新增的一节，收拢修订说明给 CoSec 自己的令牌加上的规定（验证、撤销、会话、租户切换、缓存）。
> 合订前的全文见 git 历史：`git show 08bb71e4:docs/rfc/0009-identity-and-credentials.md`。修订沿革见文末。

## 0. 结论先行

User、API Key、服务账号（Service Account）、Agent Identity 不在同一个维度上。本 RFC 把它们拆成三层，每层只回答一个问题：

| 层 | 问题 | 8.0 的取值 |
|---|---|---|
| **主体** | 谁对请求负责，权限挂在谁身上 | 租户内的一个主体；服务账号是没有密码的工作负载主体。注册表登记它是 `user` 还是 `workload` 是 8.1 的工作（§5） |
| **凭据** | 拿什么证明自己是这个主体 | CoSec 签发的 JWT、API Key；以后加外部 IdP 的 token、工作负载身份 |
| **委托** | 是否在替另一个主体做事 | 8.0 不支持，遇到即拒绝；8.1 设计 |

由此得出四条决定：

1. **凭据只证明身份，权限只有一个来源。** 角色与策略来自快照文档和 `MembershipProvider`。凭据可以携带的只有两样：CoSec 签发时从权威来源抄下的、
   有效期有限的**已签名缓存**（JWT 的成员关系声明），以及**只能收窄**的上限（API Key 的 `roles` 上限）。API Key 不携带授予（§4）。
2. **Agent 不是一种主体类型。** "替别人做事"是关系，后端服务的 on-behalf-of 和 AI Agent 是同一回事：执行者是一个工作负载，委托另行表达（§7）。
3. **主体类型和认证方式是两个可验证的事实**，以属性 `principal.type`（来自注册表）与 `auth.method`（来自认证器）暴露给条件，不在引擎的 `Subject`
   上加封闭枚举（§5）。两个属性在 8.1 加入。
4. **不支持的语义一律拒绝**：带 `act` 声明的 token 在 8.0 判为无效（§6）。今天 CoSec 只校验自己签发的 HMAC token，这条规则还触发不到；
   外部 IdP（JWKS）支持落地时它就是一道必需的闸门，所以现在就锁进一致性规则。

## 1. 现状与问题

本节记录 7.x 的做法与它的问题，是本 RFC 的动机；所说的代码在 8.0 中已删除或改写。

- 7.x 的 `ApiKey`（`core/.../apikey/ApiKeys.kt`）带 `principalId`、`roles`、`policies`、`attributes`；`ApiKeyAuthenticator` 把
  `roles` 与 `policies` 当作 `MembershipSource.Claims`。权限因此挂在 key 上：
  - **陈旧没有上限**：key 默认不过期（`expiresAt = null`），撤销一个角色不影响已经抄进 key 的副本；
  - **不在文档里**：策略文档、访问分析（RFC 0007 §10.3）、影子窗口与授出分析（RFC 0007 §7.4）都看不到 key 上的授予；控制面"不能越权授权"在这里失效：
    能写 key 存储的人可以给任何主体任意角色；
  - **轮换要复制权限**：新 key 必须抄一遍旧 key 的角色，抄错即越权或断服；
  - **主体没有生命周期**：停用一个服务需要找出并停用它的每一把 key。
- 7.x 的 `Jwts.toPrincipal` 读 `roles`、`policies`、`attributes` 声明。它们由 CoSec 自己在登录时签发，是成员关系的**已签名缓存**，
  陈旧上限是 token 有效期，撤销 token 兜底（`RevocationReader`）；需要即时生效的部署用 `cosec.membership=provider` 标记（RFC 0004 §4.5.2）。
  这是可以接受的权衡（每个请求省一次查询），但 RFC 0004 §4.5.1 给的理由"不用重签 token"是 7.x 兼容的理由；8.0 保留它的理由是性能，并且有条件（§4.3）。
- 7.x 的 `Jwts` 不看 `act`。一张 RFC 8693 token 交换得到的委托 token 会被当成 `sub` 本人：执行者拿到委托人的全部权限。
- 引擎的 `Subject`（`model/.../AuthzRequest.kt`）没有主体类型；`CoSec-Identity` 带 `auth.method`，但条件读不到认证方式。8.0 仍是这样，§5 的属性在 8.1 补上。

## 2. 原则

- **I1 可验证**：决策只依赖可以验证的事实。"是不是人类"无法验证（被盗的用户 token 可以跑在脚本里）；能验证的是注册表登记的类型和本次请求的认证方式。
- **I2 单一来源**：授予只来自快照文档与 `MembershipProvider`；凭据里的成员关系只能是它们的、由 CoSec 签发且有期限的副本。
- **I3 只收窄**：凭据可以声明比主体更小的权限，不能声明更大的。生效权限是交集。
- **I4 未知即拒绝**：凭据携带 8.0 不理解的授权语义（委托）时，判为无效，不忽略。
- **I5 命名空间隔离**：`principal.type` 与 `auth.method` 只能由注册表和认证器写入；token 的 `attributes` 声明、API Key 的属性、宿主属性都不能伪造它们。

## 3. 模型

```
凭据 ──认证器──▶ (主体, 认证方式, 上限?)
                      │
                      ▼
          MembershipResolver：成员关系 = 来源(Claims | Provider) ∩ 上限
                      │
                      ▼
          引擎：Subject(id, attributes, principalBindings, breakGlass) 与成员关系
```

- **主体**：`(tenant, principalId)`，主体 id 只在租户内唯一（RFC 0001 D5）。它是否被认识、是否停用由 `MembershipProvider` 回答（§4.2）；
  注册表登记它的类型是 8.1 的工作（§5）。服务账号就是工作负载主体，没有密码，只有凭据；它的角色和绑定与用户完全一样，写在文档里（`SubjectRef.Principal`）
  或由提供者给出。
- **主体的属性只读**（`IDN-21`）：`AuthenticatedPrincipal` 在构造时把认证器交来的 Map 包成不可修改的视图（不复制）；认证器交出 Map 之后不得再改，
  值必须不可变（字符串、布尔、数字，以及它们的不可变列表与 Map）；`cosec-jwt` 在解析声明时把嵌套的对象与数组变成只读。应用从 `SecurityContext`
  读到的属性就是同一请求后续检查（`AuthorizationChecker`）所依据的属性，所以应用能设置的东西不会进入任何决策。
- **认证方式**：认证器的事实，取值与 `Identity.Method` 一致（约定 `jwt`、`api-key`、`service`；RFC 0010 C3 起接受任何非空值）。`jwt` 只属于 CoSec 自己的令牌（§4.2）；
  `api-key` 与 `service` 是工作负载的凭据：服务以自己的主体调用控制面时（`ControlCaller.service`）声明的身份写 `service`，不是 `api-key`（`IDN-34`）。
- **上限**：一个角色集合（含 `role@space`）。成员关系与它求交；没有上限即不收窄。不引入第二套权限语言：上限用的就是文档里的角色名，
  授出分析和访问分析可以直接读。上限只收窄授予，从不解除对主体的限制（§4.2）。
- **引擎的两个显式输入**：`Subject.principalBindings`（按名绑定给主体的东西能否授予：主体绑定与直接附加的策略；有上限的凭据为 false）与
  `Subject.breakGlass`（break-glass 绑定能否适用；只有 CoSec 自己的令牌为 true）。两者没有默认值，每处构造都要写明；引擎在 `CandidateOrder` 里读取它们（§4.2）。

## 4. API Key

### 4.1 格式

```kotlin
class ApiKey(
    val id: String,
    val principalId: String,
    val tenantId: String,
    /** 所属的 App（RFC 0012 §3.5）；null 表示不声明。没有默认值。 */
    val app: String?,
    /** 上限，只收窄：null 不收窄；空集合等于没有任何角色。没有默认值。 */
    val roles: Set<String>?,
    val expiresAt: Instant? = null,
    val enabled: Boolean = true,
)
```

- 没有 `policies` 与 `attributes`。key 不授予任何东西（`IDN-1`）。`ApiKey` 是普通的类，不是 `data class`，以后加字段不破坏调用方（RFC 0010 C4）。
- `tenantId` 必填：一把 key 属于一个租户的一个主体，不回落到默认租户。
- **上限不能缺省**：`roles` 没有默认值，创建 key 的人必须写明。配置里的 key（`cosec.api-key.keys[]`）恰好写 `roles`（`[]` 表示没有角色）与
  `uncapped: true` 之一，两者都写或都不写时启动失败；写了 `policies` 或 `attributes` 的 key 同样启动失败，不被静默忽略。
- key 用 `app` 声明它属于哪个 App，宿主按 RFC 0012 §3.5 的表决定接受与否（`SCP-11`）。
- key 可以绑定任何类型的主体：绑定用户就是个人访问令牌（PAT），绑定工作负载就是服务账号的 key。I3 保证用户的 key 最多等于用户本人。
- 只存哈希、只显示一次。
- Redis 里的记录是格式 3（`ApiKeyCodec`）：每个成员都写出，`app`、`roles`、`expiresAt` 缺省时写 `null`，所以缺少上限或 App 不会出于漏写。
  读取是严格的：别的格式或未知成员一律拒绝（读到它的请求为 503），绝不读成没有上限的 key。

### 4.2 认证

`ApiKeyAuthenticator` 产出 `MembershipSource.Provider(cap)`，`cap` 是 key 的 `roles`：

1. 哈希查 key；未知、停用、过期：`API_KEY_INVALID`（按匿名求值，拒绝时 401 `token-invalid`）。
2. 经 `MembershipResolver` 向 `MembershipProvider` 取主体的成员关系。提供者返回封闭类型 `MembershipAnswer`：`Known(memberships)`，或显式的
   `Unknown`。"不认识"必须说出来：空成员关系是一个认识、没有角色的主体，它的 key 仍然有效；返回旧类型 `Memberships` 的实现编译失败，不会把空成员关系静默当作已知主体。
   `Memberships` 带主体的 `active`：
   - `Unknown`，或 `active = false`：凭据判为无效（API Key 为 `API_KEY_INVALID`）。停用服务账号即停用它所有的 key。带 `cosec.membership: provider`
     标记的令牌同样如此（`TOKEN_INVALID`，`IDN-3`）。
   - 提供者失败、超时或什么也不回答：503（fail closed）。
3. 成员关系与 `cap` 求交（按 `(space, role)` 精确匹配）。

**上限只去掉 ALLOW**（RC1-1，`BND-9`、`IDN-2`）。有上限的凭据（`Subject.principalBindings = false`）得不到主体绑定（平台与租户文档中的
`SubjectRef.Principal`）与直接附加策略的 ALLOW，但它们的 DENY 照常适用，与对主体本身一样：上限只收窄授予，从不解除对主体的限制。
直接附加的策略因此留在成员关系里，引擎对有上限的凭据只读它们的 DENY（`CandidateOrder`）。`Anyone` 绑定照常适用，所以 `roles: []` 的含义是
"只有 `Anyone` 可做的事，再减去主体被拒绝的"。授出分析同样处理：有上限的授出者不持有按名绑定给它的 ALLOW，但受其 DENY 约束（`BND-9`、`IDN-2`、`GRT`）。

**break-glass 按来源判断**（RC1-26，`BRK-8`）。break-glass 是给人的限时应急授权，不给长期凭据。只有经 CoSec 自己的验证器（`JwtTokenVerifier`，
即内部接口 `CoSecTokenVerifier` 的实现）验证的令牌的主体能得到它（`Subject.breakGlass = true`）；API Key（不论有无上限）、其他 `TokenVerifier`
接受的令牌（包装 CoSec 验证器的也算其他）、其他认证器接受的主体都不能，不看凭据叫什么名字；方法名 `jwt` 保留给 CoSec 的令牌。
CoSec 的令牌是签名的：`JwtTokenVerifier` 与 `JwtTokenConverter` 在构造时拒绝不签名的算法（`Algorithm.none()`），所以不签发未签名的令牌，
也不把未签名的令牌当作 CoSec 自己的、因而能得到 break-glass 的令牌（`IDN-28`）。

**工作负载的凭据**（`IDN-34`）。一个主体是人还是工作负载，只看 `AuthenticatedPrincipal.workload`：API Key（`ApiKeyAuthenticator` 按来源认定，或凭据名为 `api-key`），
或凭据名为 `service` 的身份，不论这个名字由谁写下（转发的身份、服务声明的身份、应用自己的认证器，失败关闭）。工作负载从不批准变更、不是控制面的信任根
（`APR-7`、`APR-3`，RFC 0007 §7.3、§7.5），与任何不是 CoSec 自己令牌的凭据一样没有会话、没有 break-glass。`apiKey` 只回答“是不是 API Key”，
不据它做授权判断：主代码里除定义处之外没有类读它（`WorkloadCredentialTest`）。给应用自己的认证器一个一等的“工作负载”标志留到 8.1，与外部 IdP 一起设计。

**网关之后同样成立**：转发的 `CoSec-Identity` 为有上限的凭据带 `auth.capped: true`，后面的服务据此不取主体绑定；break-glass 只给方法为 `jwt` 的身份（§5）。

**模拟与审批**：模拟（simulate、explain）的请求带上凭据的这两个标志（不写时按 CoSec 的令牌），模拟出的决定与该凭据实际得到的一样，不会更宽。
审批记录审批人当时的两个标志，发布时按同样的标志复核，有上限的 key 不会以更多权限计入。审批人的成员关系来自提供者时，
发布时再问一次：提供者已不认识或已停用的审批人不计入。

启动检查：启用 API Key（`cosec.api-key.enabled=true`）而没有 `MembershipProvider` 时启动失败，提示配置 `CoCacheMembershipProvider` 或自己的实现（`IDN-6`）。

### 4.3 JWT 的条件

JWT 保留 `roles`、`policies`、`attributes` 声明作为已签名缓存，条件是：

- 只有 CoSec 自己的签发者可以用声明模式。外部 IdP 的 token（JWKS 支持落地时）一律走 `Provider`，它的 `groups` 之类的声明经显式映射才能成为角色，
  映射写在平台文档里，不在代码里。
- 令牌不得冒充别的凭据（`IDN-30`）：`apiKeyId` 是 API Key 认证留给策略条件读的属性，通过校验的令牌若在 `attributes` 里带它，按 `TOKEN_INVALID`
  处理；`JwtTokenConverter` 不签发这样的令牌。
- 随 §5 的属性在 8.1 加入：`attributes` 声明不能包含 `principal.type`、`auth.method`（I5）：签发时拒绝，解析时忽略这两个键并记 WARN；
  `principal.type` 在签发时从提供者抄进 token（`cosec.principal.type` 声明），与其他成员关系同一期限。

### 4.4 迁移

`cosec-migrate api-keys` 把 7.x 存下的 key 转成 §4.1 的格式，原则是**不扩大任何凭据的权限**：7.x 的 key 只授予它自己的角色与策略，`principalId`
只是标签。每把 key 走三条路之一：

1. **原地**：key 的角色是主体现有成员关系的子集（运维用 `--memberships` 提供导出）且没有附加策略时，key 留在原主体，上限 = 原角色。
2. **专用主体**（其余情况的默认）：为这把 key 建一个专用工作负载主体 `apikey:<keyId>`，恰好持有原角色，原附加策略改为按名绑定到它的 `Principal` 绑定，
   key 改指向它（不设上限）；清单列出主体 id 改变的影响（审计主体、`subject.id` 条件、按主体计的限流桶、`CoSec-Identity` 的 `sub`）。
3. **授予原主体**：只有 `--grant-to-principal` 显式点名的主体（或 `all`），才把它可用的 key（不含停用、过期的）的角色并集授予它；每把 key 以自己原来的
   角色作上限，附加策略绑定到一个只给这把 key 的角色（`--role-prefix` 加 key id）；清单列出主体新增的角色。

原地与授予两条路上 key 有上限，失去的原主体授权（主体绑定的 ALLOW、break-glass）逐把列入清单：只减不增；key 仍受其约束的 DENY 绑定另列。

**专用主体一条路上，被点名的主体的 key 不迁走**（`MIG-6`，第二轮审查 DR2-M-1、DR2-M-9）：只要有东西按 id 点名 key 的原主体，专用主体就会逃出它，所以这把 key 被拒绝迁移、留在原主体
（原地，或经 `--grant-to-principal` 加上限）。“点名”指：DENY 方向上（DENY 语句或其策略的条件，或 ALLOW 里的否定之下，每个 `not` 翻转方向）把 `subject.id` 与对它成立的字面量比较
（`eq`、`in`、`prefix`）的条件；把 `subject.id` 与文档定不下来的值（另一个属性）比较的条件，它点名每一个主体；策略含 DENY（或没有文档定义）的主体绑定；`--memberships` 说提供者附加给它的 DENY；
以及应用的黑名单拉黑了它（`--blacklist` 给出应用的 `BlacklistSource` 里的 `<租户>:<主体 id>`）。黑名单在应用自己的数据源里，工具看不到：没有 `--blacklist` 时，工具只说“按给出的文档、成员关系与黑名单，
没有 key 获得更多访问”，并在清单里对每把迁走的 key 写出应补进黑名单的值。存下的 key 记录逐个成员检查类型，类型不符的记录不可读并点名该成员（DR2-M-2）。

整把 key 拒绝迁移、列入清单的情况：附加策略不能精确解析（不存在、不可附加，或租户与平台两份文档中内容不同）：8.0 中解析不了的附加策略让决定不确定，
直接丢弃会让 key 的角色开始放行，也会丢掉其中的 DENY；同一租户内 id 重复；专用主体 `apikey:<keyId>` 已在文档或 `--memberships` 中出现
（key 会得到那个主体持有的东西）；以及 key 带属性、角色或 id 的写法不合规则等，清单写明原因。有一把被拒绝时，命令不改写任何 key。

- 命令改写 key，并输出变更清单（`api-keys-report.json`）：清单里的成员关系由运维写进 `MembershipProvider` 的来源，`Principal` 绑定与角色绑定经控制面的变更加入。
  `--dry-run` 只出清单；`--app` 指定转换后的 key 所属的 App。
- 写回用比较并交换：迁移期间被吊销或停用的 key 不会被复活；已转换的 key 不动，命令可以重复运行。
- 没有 `tenantId` 的 key 写入默认租户，并在清单里标出。

## 5. 属性

本节的两个属性与 `CoSec-Identity` 的 `sub_type` 是 PR-2 的内容，在 8.1 加入（Q8）：它们以后可以新增，不破坏已有 API（RFC 0010 §1）。8.0 的属性目录里没有它们，
引用这两个名字的策略编译失败。

| 属性 | 来源 | 取值 | 缺失时 |
|---|---|---|---|
| `principal.type` | `MembershipProvider` 或 token 的 `cosec.principal.type` | 开放字符串；约定 `user`、`workload` | 匿名请求与旧 token 没有；比较判为不确定（RFC 0005 的缺失语义），DENY 不因缺失而失效 |
| `auth.method` | 认证器 | 与 `Identity.Method` 一致（约定 `jwt`、`api-key`、`service`）；匿名请求为 `anonymous` | 不会缺失 |

- 两者登记进属性目录（`Attribute.Kind`），可用于条件，也可作限流 `key`。
- `CoSec-Identity` v1 增加 `sub_type`（即 `principal.type`）；`auth.method` 已在。v1 已随 8.0 发布，解码是严格的，所以加成员时遵守 RFC 0010 §1 的约束 4。
- 不在 `Subject` 上加枚举：开放属性让新类型（例如以后的 `device`）不成为破坏性变更；引擎不因类型改变求值方式。

**8.0 的 `CoSec-Identity`**。网关把验证过的身份放进 `CoSec-Identity`（格式 v1，`IdentityCodec`，`schema/8.0/cosec-identity.schema.json`）转给后面的服务，
后面的服务据此认证（`Pipeline.behindGateway`）。其中出自本 RFC 的成员：

- `auth.method`：认证方式。后面的服务只给 `jwt` 的身份 break-glass（§4.2）。
- `auth.capped`：有上限的凭据为 `true`，其余不出现；后面的服务据此不取主体绑定（§4.2）。
- `auth.iat`（可选）：凭据的签发时间，不晚于 `auth.exp`。
- `auth.session`（可选）：CoSec 自己的令牌的会话，`sid`、`auth_time`（登录时间）、`generation`（代数）三者都必填；只属于 `method` 为 `jwt` 的身份，
  且必须同时有 `auth.iat`。网关默认不把 `Authorization` 转给后面的服务（`IDN-8`），所以这些网关验证过的事实随身份转发，它们都不是秘密；后面的服务凭它读用户的
  登录时间，租户切换凭它取会话（§11.4）。

读取对形式是严格的：缺成员、类型不对、代数不是正整数、`api-key` 的身份带了会话、有会话而没有 `iat`，一律按格式错误拒绝（400），绝不读成“没有会话”。
服务对未知成员同样严格，所以**先升级网关后面的服务，再升级网关**。不带这两个成员的身份（API Key 的，或旧网关发来的）照旧可读，只是不能据此切换租户。

**登录时间与签发时间不比较先后。** 两者出自两个时钟：登录时间由受理登录的实例写进会话家族，签发时间由做刷新或切换的实例写进令牌；两台实例差几毫秒又恰好跨秒时，
合法令牌的登录时间就晚于签发时间。没有任何规则依赖这个先后：会话到期按 `auth_time` 加寿命算，家族校验比较 `auth_time` 是否相等，撤销只读 `iat`。
严格只针对形式；出自同一次签发的 `iat` 与 `exp` 仍然比较。

## 6. 委托 token 在 8.0 的处理

- 带 `act` 声明的 token 在验证器里被拒（`JwtTokenVerifier`，验证与刷新两条路径；`TokenAuthenticator` 只看到验证结果 `VerifiedToken`，读不到原始声明），
  结果是 `RejectReason.DELEGATION_UNSUPPORTED`：按匿名求值，拒绝时为 `token-invalid`（401），审计原因码 `DELEGATION_UNSUPPORTED`。自定义的
  `TokenVerifier` 或自行解析 token 的 `Authenticator` 承担同样的义务。`may_act` 只表示"允许被谁代理"，不改变主体，忽略。
- 与外部 IdP 无关地成立：CoSec 自己永远不签发带 `act` 的 token，直到 §7 落地。

## 7. 委托（8.1，另开 RFC）

只记录已经确定的方向，细节留给 8.1 的 RFC：

- 执行者是一个主体（通常是 `workload`），委托人是另一个主体，委托有范围（角色上限）、期限，可以单独撤销。
- **生效 = 执行者 ∩ 委托人 ∩ 委托范围**：执行者与委托人各自判定一次，两者都 Permit 才放行，任何一方 Deny 即拒绝（deny-first 不变）；
  委托人的 root 与 break-glass 不传给执行者。只有委托请求多一次判定。
- 委托深度先限为 1。
- 限流两方各自计数；审计同时记录执行者与委托人；`CoSec-Identity` 升到 v2 携带 `act`。
- 签发走 RFC 8693 token 交换；敏感操作可以通过义务要求人工确认。
- 待定：委托人与执行者跨租户、委托撤销的传播时延、影子模式如何比较双重判定。

## 8. 一致性规则（新族 `IDN`）

规则登记在 `test/conformance/src/test/resources/rules/IDN.json`，措辞与守卫以登记表为准。下表是出自本 RFC 的各条；族里的其他规则出自别处：
`IDN-7` 至 `IDN-9`（RFC 0006 §7.3）、`IDN-11`（RFC 0004 §4.5.1）、`IDN-12`（RFC 0001 D5）、`IDN-25` 至 `IDN-27`、`IDN-29`、`IDN-33`。

| 规则 | 级别 | 内容 |
|---|---|---|
| IDN-1 | pipeline | API Key 的生效成员关系是主体成员关系的子集；key 没有任何授予 |
| IDN-2 | pipeline | 上限只收窄：有上限时不出现上限之外的角色（按空间与角色精确匹配），直接附加策略与主体绑定的 ALLOW 不到达 key；它们的 DENY 照常适用（RC1-1） |
| IDN-3 | pipeline | 成员关系来自提供者的凭据（API Key，或带 `cosec.membership: provider` 标记的令牌），在主体未知或停用时判为无效 |
| IDN-4 | pipeline | 带 `act` 的 token 判为无效 |
| IDN-5 | engine | `principal.type`、`auth.method` 只来自注册表与认证器；token 声明、key 与宿主属性不能写入。随 §5 在 8.1 加入，登记表里还没有 |
| IDN-6 | startup | 启用 API Key 而没有 `MembershipProvider` 时启动失败 |
| IDN-10 | pipeline | 含落单 UTF-16 代理项的 id 在进入处被拒绝：令牌声明为 401，签发方拒绝签发，转发的身份为 400，控制请求为 400，认证器、API Key 记录与成员关系提供者给出它时为 503（RC1-52） |
| IDN-13 | pipeline | 刷新令牌在需要访问令牌的地方无效；刷新只收与所出示的访问令牌同时签发的刷新令牌（双向） |
| IDN-14 | pipeline | 刷新与租户切换读不到撤销时不签发，绝不按未撤销处理；`PIP-7` 的降级只适用于验证令牌（§11.2） |
| IDN-15 | pipeline | 只有族的当前一代推进族，以条件写推进：同一对令牌的并发刷新恰好推进一次（§11.3） |
| IDN-16 | pipeline | 刚被替换的一代在宽限期内得到当前代的令牌对、不推进族；更早的一代或宽限期后的上一代撤销整族（§11.3） |
| IDN-17 | pipeline | 族在登录后 `cosec.jwt.session.lifetime` 结束，令牌对不超过它；租户切换保留登录时间（§11.3） |
| IDN-18 | pipeline | 登出结束会话：撤销整族与访问令牌的 id，族标记为撤销而不删除（§11.3） |
| IDN-19 | pipeline | 族记录读不到或写入无法确认时不签发（503）；记录不存在时拒绝刷新（401）（§11.3） |
| IDN-20 | build | 社交登录的回调只接受 CoSec 签发的 `state`，只用一次，且只来自发起登录的浏览器（§11.1） |
| IDN-21 | build | 主体的属性在认证器交出后不可改（§3） |
| IDN-22 | pipeline | 租户切换只从活着的会话签发；刷新在同一处检查（§11.4） |
| IDN-23、IDN-24 | pipeline | starter 提供的刷新路由与登出路由（§11.3） |
| IDN-28 | startup | CoSec 的令牌是签名的：验证器与签发器拒绝 `Algorithm.none()`（§4.2） |
| IDN-30 | pipeline | 令牌不得冒充别的凭据：`attributes` 里带 `apiKeyId` 的令牌无效，也不签发（§4.3） |
| IDN-31 | build | 会话记录带格式号，按读者的规则读，更晚的格式拒绝（§11.3） |
| IDN-32 | build | 缓存的验证结果就是再验证一遍会得到的结果（§11.5） |

每条规则配正反场景；变异体在 `test/conformance/mutants/idn-*`，由 `mutation.yml` 运行。

## 9. 交付

| PR | 内容 | 破坏性 | 状态 |
|---|---|---|---|
| PR-1 | `fix`：拒绝带 `act` 的 token（§6、IDN-4） | 否 | 已合入（#1144） |
| PR-2 | `feat!`：`principal.type`、`auth.method` 属性，`Memberships.type`，`CoSec-Identity` 的 `sub_type`，JWT 签发写入类型，I5 的隔离（§4.3、§5、IDN-5） | 是：`MembershipProvider` 的返回值、token 声明 | 移到 8.1（Q8） |
| PR-3 | `feat!`：API Key 新格式、`MembershipSource.Provider(cap)`、`Memberships.active`、启动检查、`migrate api-keys`（§4、IDN-1/2/3/6） | 是：API Key 存储格式 | 已合入（#1149、#1164） |

- 文档：`wiki/integration/authentication.md`（服务账号、PAT、上限、委托不支持、会话）、`wiki/operations/upgrading.md`（key 迁移）、`skills/cosec-integration`；
  `wiki/policies/language.md` 的两个属性随 PR-2。
- 性能：PR-3 让 API Key 请求多一次成员关系查询；`CoCacheMembershipProvider` 本地命中时不出线程。

## 10. 决定记录

| 编号 | 决定 | 日期 |
|---|---|---|
| Q1 | 采用三层模型；Agent 是委托关系，不是主体类型 | 2026-10-28 |
| Q2 | 主体类型与认证方式是开放属性，不是引擎枚举 | 2026-10-28 |
| Q3 | API Key 不携带授予，只有角色上限；可绑定任何主体（含 PAT） | 2026-10-28 |
| Q4 | JWT 的成员关系声明保留为 CoSec 签发的已签名缓存；外部 IdP 的 token 只走提供者 | 2026-10-28 |
| Q5 | 8.0 拒绝带 `act` 的 token；委托放到 8.1，不预留字段（`CoSec-Identity` 的版本号就是扩展点） | 2026-10-28 |
| Q6 | 上限只收窄授予：有上限时主体绑定与直接附加策略的 ALLOW 不到达，DENY 照常；break-glass 只给 CoSec 自己的验证器验证的令牌，按来源判断；上限必须显式；提供者的"未知主体"是封闭类型的显式分支 | 2026-10-29（同日再修订：RC1-1、RC1-26） |
| Q7 | 迁移不扩大任何凭据：默认原地转换（子集时）或每把 key 一个专用工作负载主体；并集授予原主体须显式选择；解析不了的附加策略整把 key 拒绝 | 2026-10-29 |
| Q8 | 不为未来功能预先抽象（RFC 0010 Q1）：GA 前只做 PR-1（安全）与 PR-3（存储格式的破坏，带 `Memberships.active`）；PR-2 的属性与 `sub_type` 以后可以新增，移到 8.1 | 2026-10-28 |
| Q9 | 刷新令牌属于会话：令牌族轮换、带宽限期的重放检测、绝对寿命、登出结束会话；签发令牌的路径对撤销读失败关闭（§11） | 2026-11-14（维护者："按推荐做"） |

## 11. CoSec 的令牌：验证、撤销与会话

本节规定 CoSec 自己签发与验证的令牌（`cosec-jwt`、`cosec-login`）的验证、撤销与会话。签名与 break-glass 见 §4.2，`apiKeyId` 见 §4.3，`act` 见 §6。

### 11.1 令牌与验证

- 令牌用 `aud` 声明 App：单个字符串，或只含一个字符串的数组（RFC 0012 §3.5，`SCP-11`）。
- `TokenVerifier` 是纯计算：不做 I/O、不阻塞、不挂起，撤销不是它的问题；验证结果是不可变的 `VerifiedToken`。
- 主体为空白（空串或纯空白）的令牌在每条验证它的路径上都无效（请求与刷新，401 `token-invalid`），CoSec 的签发方也不签这样的令牌（`IDN-38`，RFC 0004 §4.1）。
- 宿主有 `TokenVerifier` bean 或 `JwtSigning` 就认证令牌：应用自己的 `TokenVerifier` 优先，否则用 CoSec 自己的；请求、刷新与登出用同一个验证器（`IDN-25`）。
  启动时的一行日志说出由什么认证。
- 社交登录的回调只接受 CoSec 签发的 `state`：一次性，并绑定发起登录的浏览器（`IDN-20`）。关掉 JustAuth 的 `state` 检查时启动失败；关掉它的回调地址检查
  （`ignore-check-redirect-uri`）可以，启动时记 WARN：那项检查只看运维写的 `redirect-uri` 是不是 http(s) 地址，不检查请求带来的任何东西。

### 11.2 撤销

- 端口如实报告，由调用方决定失败的含义。`RevocationReader` 读不到即抛出（`RevocationUnavailableException`），绝不回答"未撤销"。
  撤销判断只有一份实现（`TokenRevocation`），请求、刷新与登出共用；`TokenRevoker` 是挂起函数。
- “读失败按未撤销”这一文档化例外只适用于**验证请求的令牌**，只在 `TokenAuthenticator` 一处，每个这样放行的令牌都计数（`cosec.revocation.unchecked`）并限频记日志；
  `cosec.authentication.revocation.fail-closed=true` 关掉它，请求答 503（`PIP-7`）。
- 任何**签发**令牌的路径（刷新、租户切换）对撤销读失败关闭：读不出来就不签发（`IDN-14`）。否则一次 Redis 读故障就能把已登出、已撤销主体的令牌
  换成一对撤销覆盖不到的新令牌。
- CoSec 自己的撤销读取器（`CoCacheRevocationReader`）每次读 Redis 有预算：认证阶段预算的 30 %（`StageBudgets.revocationRead`；`cosec.pipeline.timeout`
  为默认的 5 秒时是 300 ms），所以一个令牌的两次读都在认证预算之内结束。挂住的存储按“不可读的存储”回答，适用上面的例外：验证请求的令牌时放行（`PIP-7`），
  刷新与切换租户绝不放行（`IDN-14`）。
- 请求路径只查令牌的 `jti` 与主体撤销。撤销主体（`revokeSubject`）也阻止它之前签发的任何令牌对刷新。
- 撤销记录保留到它能点名的每个令牌都已过期之后，所以记录过期时，它撤销的令牌不会重新有效：starter 保留令牌与主体的撤销
  `cosec.jwt.token-validity.access` 与 `cosec.jwt.token-validity.refresh` 中较长的一个，再加 `cosec.authentication.revocation.skew`（`IDN-29`）。
  这个值是算出的，不配置；控制面检查“吊销活得比审批久”时读同一个值（`IDN-37`，RFC 0004 §4.5.3）。

### 11.3 会话（刷新令牌族）

1. 一次登录开始一个会话，即一个令牌族；族 id（`sid`）、登录时间（`auth_time`）与代数（`cosec.generation`）写入族内每一对令牌。
2. 刷新即轮换：只有族的当前一代能刷新，成功的刷新以对所读记录的条件写把族推进到下一代；同一对令牌的并发刷新，不论在一个还是多个实例上，恰好推进一次（`IDN-15`）。
   族用代数而不是当前令牌对的 `jti` 标识当前一代。
3. 重放检测与宽限期（`IDN-16`）：刚被替换的一代在宽限期内（`cosec.jwt.session.rotation-leeway`，默认 30 秒，自族推进时起算，不滑动）得到一对当前代的
   新令牌，族不推进、不写入：刷新在服务端成功而响应没有到达客户端时，客户端用手里的旧令牌对重试即可恢复，多个标签页同时刷新也都得到可用的令牌；
   宽限期内若只拒绝，每丢失一次刷新响应，用户就被登出一次。更早的一代，或宽限期过后的上一代，再次出现即撤销整族并拒绝。宽限期内被答复的副本
   是第二对当前代的令牌，下一次刷新时两者中后到的那个撤销整族，所以复制仍会被发现；代价是持有刚被替换的令牌对的人在宽限期内可以换到新令牌，
   而持有者片刻之前本来就持有当前代。
4. 族有自登录时间起的绝对寿命（`cosec.jwt.session.lifetime`，默认 30 天），到期必须重新登录；族签发的令牌对都不超过它，角色变更最迟此时生效（`IDN-17`）。
5. 登出撤销整族与当前访问令牌的 `jti`：访问令牌有效时凭它即可，过期后凭令牌对（按刷新的要求绑定）；之后族内任何一对都不能刷新，包括它由之刷新而来的各对。
   族标记为撤销，不删除，直到它结束（`IDN-18`）。
6. 请求路径不读族：仍只查 `jti` 与主体撤销，所以更早一代的访问令牌在其有效期内仍可用；`CoSec-Identity` 的会话成员见 §5。
7. 族记录只在签发（登录、租户切换）、刷新与登出时读写，并失败关闭：读不到或写入无法确认时不签发（503），不存在时拒绝刷新（401）（`IDN-19`）。
   记录自每次写入起保留它剩余的寿命与刷新令牌有效期（`cosec.jwt.token-validity.refresh`）中较短的一段，所以没人刷新的会话随它最后一个刷新令牌离开存储。
   存储为每个租户里的每个主体保留最近开始的 64 个会话（`TokenFamilyStore.SESSIONS_PER_PRINCIPAL`）：再开始一个就删除（不是标记）最早开始的那个的记录，
   不论它活着、已撤销还是已结束，它的下一次刷新按未知会话拒绝（`IDN-35`，DR1-G-2）。记录带格式号，按 RFC 0007 §5.2 的读规则读：更晚版本加的成员忽略，没有格式号的是格式 1，更晚的格式拒绝，那个会话因此结束，
   绝不读成另一个会话（`IDN-31`）。
8. 撤销主体阻止它之前签发的任何令牌对刷新（§11.2）。
9. 切换租户开始一个新族，继承登录时间（§11.4）。

没有族的令牌不能刷新，不设兼容路径。刷新只收刷新令牌：刷新槽里须是与所出示的访问令牌同时签发的刷新令牌，刷新令牌在需要访问令牌的地方也无效（`IDN-13`，双向）。

实现：族记录在 `TokenFamilyStore`（`@CoSecSpi`，`cosec-spi`；Redis 实现 `CoCacheTokenFamilyStore`，键 `<prefix>:token:family:<sid>`，单键条件写），
规则集中在 `TokenFamilies`（`cosec-login`）；开启 JWT 而没有族存储时启动失败。设置 `cosec.authentication.login.refresh.path` 与
`cosec.authentication.login.logout.path` 后，starter 提供刷新路由与登出路由（`IDN-23`、`IDN-24`）。

### 11.4 租户切换

凭旧令牌签发新令牌的只有两处：刷新与租户切换。它们共用一处检查（`LiveSessions`）：撤销严格读取（读不出来就不签发，`IDN-14`），会话家族可读、
属于该主体与租户、既未撤销也未结束（`IDN-22`）。会话未知、属于别人、已撤销或已结束时为 401，族或撤销读不出来时为 503。

- 切换不接收令牌原文：它从管道认证过的请求里取会话（`SwitchTenantCredentials.context`）。`AuthenticatedPrincipal` 的会话只有 CoSec 自己的令牌认证器能设置；
  网关之后，会话是网关验证过、随 `CoSec-Identity` 转发的 `auth.session`（§5）。
- 切换开始一个新会话，继承原会话的登录时间，所以它在原会话结束时结束（`IDN-17`）；原会话的内容不变，登出只结束交给它的那一对令牌。
- 切换开始的会话记着它的根，即用户登录开始的那个会话（被切换会话的根，或登录本身的会话），只在根活着时活着：它的刷新、轮换宽限期内的重试与从它再切换都读根，
  根已结束、已撤销或不在时撤销它并拒绝（401 `token-revoked`）；每次这样的回答都以条件写把根原样写回，所以根保留得与依赖它的会话一样久。
  切换读回它新建的会话，存储没有保留根时拒绝并结束它；这样的记录写为格式 2，读格式 1 的读者拒绝它（`IDN-36`、`IDN-31`）。
- 新令牌的 App 由签发方的 `TenantSwitch` 回答决定，不取自请求（`SCP-12`，RFC 0013 §7.3）。
- 守卫有两层：`cosec-login` 内，每个读旧凭据的认证都持有这处检查（`LiveSessionsGuardTest`）；跨模块，从编译产物看，只有这处检查调用会话家族的轮转、
  按旧登录时间新开与有效性查询（`LiveSessionCallersTest`），别的 CoSec 模块即使 opt-in 也不能绕开。

### 11.5 已验证令牌的缓存

只有 CoSec 自己的验证器在请求路径上使用它（`cosec.authentication.token-cache.enabled`，默认开；默认开是协调者定的取舍，登记在路线图 §6）。

- 缓存的验证结果就是再验证一遍会得到的结果（`IDN-32`）：只在自己的时钟处于“再验证也会成功”的区间内才从缓存回答，所以缓存的令牌活不过它的 `exp`，
  也不早于 `iat` 与 `nbf`（含时钟偏差）；差一个字节的令牌重新验证；只缓存成功；吊销检查与保留属性检查在它之后、每个请求都做。
- 刷新、切换租户与登出从不读它，应用自己的 `TokenVerifier` 不会拿到它。
- 键是令牌的 SHA-256。容量是堆里令牌缓存的那一份（`HeapBudget`，`STA-12`；堆的十六分之一），每条按令牌长度称重；被逐出的条目只多花一次验证。

## 修订沿革

只列日期与出处，内容都在正文里。

- 2026-10-28：确认（#1133）；§6 的拒绝放在验证器里（#1145）；PR-2 移到 8.1（RFC 0010 Q1，#1134）；PR-3 安全审查的决定（#1150）。
- 2026-10-29：PR-3 安全审查（#1149）；RC 安全审查第 1 轮与 PR-3 第 2 轮审查：上限只去掉 ALLOW、break-glass 按来源（#1164；记录于 #1177）。
- 2026-11-07（#1254）：`IDN-10`。
- 2026-11-09（#1302）：令牌与 API Key 声明 App；API Key 记录格式 3；`VerifiedToken`。
- 2026-11-14：主体的属性只读（#1343，`IDN-21`）；撤销读的失败语义、刷新令牌族（#1325，`IDN-15` 至 `IDN-19`）；社交登录的 `state`（#1323，`IDN-20`）。
- 2026-11-15（#1415、#1417）：租户切换只从活着的会话签发（`IDN-22`）；`CoSec-Identity` 带会话。
- 2026-11-19：令牌是签名的（#1662，`IDN-28`）；令牌不得冒充 API Key（#1718，`IDN-30`）。
- 2026-11-20（#1765、#1766、#1773、#1774）：撤销读的预算；会话记录带格式号（`IDN-31`）；令牌认证的开启；已验证令牌的缓存（`IDN-32`）。
- 2026-11-23（#1898）：服务调用方的凭据方法 `service`，工作负载的凭据只有一处定义（`IDN-34`）；撤销的保留期（#1939，`IDN-37`）；会话的保留期与每个主体的上限（#1942，`IDN-35`）；切换开始的会话依赖根（#1945，`IDN-36`）。
- 2026-11-24（#1953）：主体 id 不是空白（`IDN-38`）。
- 2026-11-24（#1970）：被 DENY 或黑名单点名的主体的 key 不迁到专用主体（`MIG-6`）。
