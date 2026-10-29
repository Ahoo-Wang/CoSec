# RFC 0009：身份与凭据——主体、凭据与委托的分层

> 状态：已确认（2026-10-28，维护者："按你推荐"） · 上位文档：[RFC 0001](0001-target-architecture.md) P9、§7.1；[RFC 0004](0004-unified-model.md) §4.2、§4.5；
> [RFC 0006](0006-coroutine-pipeline.md) §5.3 · 基线：`main` @ `7bc9fa88` 之后的批次（#1118–#1132）·
> 交付：§9 的 PR-1 至 PR-3 在 8.0 GA 之前合入 `main`；§7 的委托是 8.1 的工作，另开 RFC ·
> 范围：`core/`（`apikey`、`membership`）、`runtime/`（`authn`、`MembershipResolver`）、`jwt/`、`cocache/`、`model/`（属性目录）、
> `migrate/`、`spring-boot-starter/`、`schema/`、`conformance/`

> **2026-10-28 修订（[RFC 0010](0010-ga-extension-points.md) Q1）**：维护者要求不为未来功能预先抽象。GA 前只做 PR-1（拒绝 `act`，安全）与 PR-3（API Key 新格式，
> 存储格式的破坏与"策略即数据"）；PR-3 带上 `Memberships.active`（IDN-3 需要）。PR-2 的 `principal.type`、`auth.method` 属性与 `sub_type` 都可以以后新增，
> 移到 8.1，IDN-5 随之移走。下文保留原样以便追溯。

> **2026-10-29 再修订（RC 安全审查第 1 轮与 PR-3 第 2 轮审查，#1149、#1164）**：取代下一段中关于上限与 break-glass 的两句，以此为准。
>
> - **上限只去掉 ALLOW**（RC1-1）：有上限的凭据得不到主体绑定（平台与租户文档中的 `SubjectRef.Principal`）与直接附加策略的 ALLOW，
>   但它们的 DENY 照常适用，与对主体本身一样：上限只收窄授予，从不解除对主体的限制。`roles: []` 的含义是"只有 `Anyone` 可做的事，再减去主体被拒绝的"。
>   授出分析同样处理：有上限的授出者不持有按名绑定给它的 ALLOW，但受其 DENY 约束（`BND-9`、`IDN-2`、`GRT`）。
> - **break-glass 按来源判断**（RC1-26）：只有经 CoSec 自己的验证器（`JwtTokenVerifier`）验证的 token 的主体能得到 break-glass；
>   API Key（不论有无上限）、其他 `TokenVerifier` 接受的 token、其他认证器接受的主体都不能，不看凭据叫什么名字；方法名 `jwt` 保留给 CoSec 的 token（`BRK-8`）。
> - `Subject` 的 `principalBindings` 与 `breakGlass` 没有默认值，每处构造都要写明；模拟（simulate、explain）带上凭据的这两个标志；
>   审批在发布时按审批时记录的标志复核。
> - `MembershipProvider` 返回封闭类型 `MembershipAnswer`，"未知主体"是显式分支；按旧约定返回空成员关系的实现编译失败。
> - 迁移（`migrate api-keys`）：同一租户内 id 重复的 key 拒绝迁移；专用主体 `apikey:<keyId>` 已在文档或 `--memberships` 中出现时拒绝；
>   附加策略只有能精确解析时才转换；写回用比较并交换。

> **2026-10-29 修订（PR-3 安全审查，#1149）**：下面几处以本段为准。
>
> - **上限只留角色**（§3、§4.2）：有上限的凭据不取主体绑定（平台与租户文档中的 `SubjectRef.Principal`），也没有直接附加的策略；`Anyone` 绑定照常适用，
>   所以 `roles: []` 的含义是"只有 `Anyone` 可做的事"。break-glass 对 API Key 一律不适用，不论有无上限：它是给人的限时应急授权，不给长期凭据。
>   两者作为引擎的显式输入（是否取主体绑定、是否取 break-glass），由 `CandidateOrder` 读取；ALG/BND 规则与 IDN-2 各增场景与变异体。
> - **上限不能默认缺省**（§4.1）：配置里的 key 必须写 `roles`，不收窄要显式写 `uncapped: true`；`ApiKey` 的构造器不给 `roles` 默认值。
> - **`MembershipProvider` 的"不认识"是显式的**（§4.2）：返回值改为封闭类型，带显式的"未知主体"分支，旧实现编译失败，而不是把"空成员关系"静默当作已知主体。
> - **迁移不扩大任何凭据的权限**（§4.4 取代原文）：7.x 的 key 只授予它自己的角色与策略，`principalId` 只是标签。
>   1. key 的角色是主体现有成员关系的子集（运维提供 `--memberships` 导出）且没有附加策略时，原地转换，上限 = 原角色；
>   2. 否则默认为每把 key 建一个专用工作负载主体 `apikey:<keyId>`，恰好持有原角色，原附加策略改为绑定到它的 `Principal` 绑定，key 改指向它；
>      清单列出主体 id 改变的影响（审计主体、`principal.id` 条件、限流桶、`CoSec-Identity` 的 `sub`）与失去的原主体授权（只减不增）；
>   3. `--grant-to-principal` 显式选择后才把并集授予原主体（不含停用、过期的 key），清单列出主体新增的角色。
>
>   附加策略只有能精确解析（可附加，或两份文档中内容相同）时才转换，其他情况整把 key 拒绝迁移、列入清单：8.0 中解析不了的附加策略让决定不确定，
>   直接丢弃会让 key 的角色开始放行，也会丢掉其中的 DENY。写回用比较并交换，迁移期间被吊销或停用的 key 不会被复活。

## 0. 结论先行

User、API Key、服务账号（Service Account）、Agent Identity 不在同一个维度上。本 RFC 把它们拆成三层，每层只回答一个问题：

| 层 | 问题 | 8.0 的取值 |
|---|---|---|
| **主体** | 谁对请求负责，权限挂在谁身上 | 租户内的一个主体；注册表登记它是 `user` 还是 `workload`（服务账号） |
| **凭据** | 拿什么证明自己是这个主体 | CoSec 签发的 JWT、API Key；以后加外部 IdP 的 token、工作负载身份 |
| **委托** | 是否在替另一个主体做事 | 8.0 不支持，遇到即拒绝；8.1 设计 |

由此得出四条决定：

1. **凭据只证明身份，权限只有一个来源。** 角色与策略来自快照文档和 `MembershipProvider`。凭据可以携带的只有两样：CoSec 签发时从权威来源抄下的、
   有效期有限的**已签名缓存**（JWT 的成员关系声明），以及**只能收窄**的上限（API Key 的 `roles` 上限）。API Key 不再携带授予（§4）。
2. **Agent 不是一种主体类型。** "替别人做事"是关系，后端服务的 on-behalf-of 和 AI Agent 是同一回事：执行者是一个工作负载，委托另行表达（§7）。
3. **主体类型和认证方式是两个可验证的事实**，以属性 `principal.type`（来自注册表）与 `auth.method`（来自认证器）暴露给条件，不在引擎的 `Subject`
   上加封闭枚举（§5）。
4. **不支持的语义一律拒绝**：带 `act` 声明的 token 在 8.0 判为无效（§6）。今天 CoSec 只校验自己签发的 HMAC token，这条规则还触发不到；
   外部 IdP（JWKS）支持落地时它就是一道必需的闸门，所以现在就锁进一致性规则。

## 1. 现状与问题

- `core/.../apikey/ApiKeys.kt` 的 `ApiKey` 带 `principalId`、`roles`、`policies`、`attributes`；`runtime/.../authn/ApiKeyAuthenticator.kt` 把
  `roles` 与 `policies` 当作 `MembershipSource.Claims`。权限因此挂在 key 上：
  - **陈旧没有上限**：key 默认不过期（`expiresAt = null`），撤销一个角色不影响已经抄进 key 的副本；
  - **不在文档里**：策略文档、访问分析（RFC 0007 §10.3）、影子窗口与授出分析（RFC 0007 §7.4）都看不到 key 上的授予；控制面"不能越权授权"在这里失效：
    能写 key 存储的人可以给任何主体任意角色；
  - **轮换要复制权限**：新 key 必须抄一遍旧 key 的角色，抄错即越权或断服；
  - **主体没有生命周期**：停用一个服务需要找出并停用它的每一把 key。
- `jwt/.../Jwts.kt` 的 `toPrincipal` 读 `roles`、`policies`、`attributes` 声明。它们由 CoSec 自己在登录时签发，是成员关系的**已签名缓存**，
  陈旧上限是 token 有效期，撤销 token 兜底（`RevocationReader`）；需要即时生效的部署用 `cosec.membership=provider` 标记（RFC 0004 §4.5.2）。
  这是可以接受的权衡（每个请求省一次查询），但 RFC 0004 §4.5.1 给的理由"不用重签 token"是 7.x 兼容的理由；8.0 保留它的理由是性能，并且有条件（§4.3）。
- `Jwts` 不看 `act`。一张 RFC 8693 token 交换得到的委托 token 会被当成 `sub` 本人：执行者拿到委托人的全部权限。
- 引擎的 `Subject`（`model/.../AuthzRequest.kt`）没有主体类型；`CoSec-Identity` 有 `auth.method`，但条件读不到认证方式。

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
          引擎：Subject(id, tenant, roles, policies, attributes ⊇ {principal.type, auth.method})
```

- **主体**：`(tenant, principalId)`，和今天相同（D5）。它的类型、是否停用由 `MembershipProvider` 回答（§4.2）。服务账号就是类型为 `workload` 的主体，
  没有密码，只有凭据；它的角色和绑定与用户完全一样，写在文档里（`SubjectRef.Principal`）或由提供者给出。
- **认证方式**：认证器的事实，取值与 `Identity.Method` 一致（`jwt`、`apikey`）。
- **上限**：一个角色集合（含 `role@space`）。成员关系与它求交；没有上限即不收窄。不引入第二套权限语言：上限用的就是文档里的角色名，
  授出分析和访问分析可以直接读。

## 4. API Key

### 4.1 格式

```kotlin
data class ApiKey(
    val id: String,
    val tenantId: String,
    val principalId: String,
    /** 只收窄：null 不收窄；空集合等于没有任何角色。 */
    val roles: Set<String>? = null,
    val expiresAt: Instant? = null,
    val enabled: Boolean = true,
)
```

- 删除 `policies` 与 `attributes`。key 不再授予任何东西。
- `tenantId` 必填：一把 key 属于一个租户的一个主体，不再回落到默认租户。
- key 可以绑定任何类型的主体：绑定用户就是个人访问令牌（PAT），绑定工作负载就是服务账号的 key。I3 保证用户的 key 最多等于用户本人。
- 只存哈希、只显示一次（现状不变）。

### 4.2 认证

`ApiKeyAuthenticator` 产出 `MembershipSource.Provider(cap = key.roles)`：

1. 哈希查 key；未知、停用、过期：`API_KEY_INVALID`（不变）。
2. 经 `MembershipResolver` 向 `MembershipProvider` 取主体的成员关系与状态。`Memberships` 增加主体的 `type` 与 `active`：
   - 提供者不认识这个主体，或 `active = false`：`API_KEY_INVALID`。停用服务账号即停用它所有的 key。
   - 提供者失败：503（不变，fail closed）。
3. 成员关系与 `cap` 求交（按 `(space, role)` 精确匹配）；`attachments`（直接附加的策略）在有 `cap` 时清空：上限只能用角色表达。

启动检查：启用 API Key 而没有 `MembershipProvider` 时启动失败，提示配置 `CoCacheMembershipProvider` 或自己的实现。

### 4.3 JWT 的条件

JWT 保留 `roles`、`policies`、`attributes` 声明作为已签名缓存，条件是：

- 只有 CoSec 自己的签发者可以用声明模式。外部 IdP 的 token（JWKS 支持落地时）一律走 `Provider`，它的 `groups` 之类的声明经显式映射才能成为角色，
  映射写在平台文档里，不在代码里。
- `attributes` 声明不能包含 `principal.type`、`auth.method`（I5）：签发时拒绝，解析时忽略这两个键并记 WARN。
- `principal.type` 在签发时从提供者抄进 token（`cosec.principal.type` 声明），与其他成员关系同一期限。

### 4.4 迁移

`migrate` 新增 `api-keys` 子命令：读旧格式的 key，

- `roles`/`policies` 非空的 key：为它的主体生成成员关系（写入 `CoCacheMembershipProvider` 的存储）或租户文档中的 `Principal` 绑定，
  key 本身改为 `roles = null`；同一主体的多把 key 权限不同时，取并集授予主体，每把 key 用自己原来的角色作上限，权限不变；
  有直接附加策略的，转成一个只给该主体的角色（名字由 `--role-prefix` 决定）。
- 输出变更清单；`--dry-run` 只打印。
- 没有 `tenantId` 的 key 写入默认租户，并在清单里标出。

## 5. 属性

| 属性 | 来源 | 取值 | 缺失时 |
|---|---|---|---|
| `principal.type` | `MembershipProvider` 或 token 的 `cosec.principal.type` | 开放字符串；约定 `user`、`workload` | 匿名请求与旧 token 没有；比较判为不确定（RFC 0005 的缺失语义），DENY 不因缺失而失效 |
| `auth.method` | 认证器 | `jwt`、`apikey`；匿名请求为 `anonymous` | 不会缺失 |

- 两者登记进属性目录（`AttributeCatalog`），可用于条件，也可作限流 `key`。
- `CoSec-Identity` v1 增加 `sub_type`（即 `principal.type`）；`auth.method` 已在。v1 尚未发布，直接扩展 v1，不升版本。
- 不在 `Subject` 上加枚举：开放属性让新类型（例如以后的 `device`）不成为破坏性变更；引擎不因类型改变求值方式。

## 6. 委托 token 在 8.0 的处理

- 带 `act` 声明的 token 在验证器里被拒（`JwtTokenVerifier`，验证与刷新两条路径；`TokenAuthenticator` 只看到 `TokenPrincipal`，读不到原始声明），结果是 `RejectReason.DELEGATION_UNSUPPORTED`：拒绝时为 `TOKEN_INVALID`（401），审计原因码 `DELEGATION_UNSUPPORTED`。自定义的 `TokenVerifier` 或自行解析 token 的 `Authenticator` 承担同样的义务。`may_act` 只表示"允许被谁代理"，不改变主体，忽略。
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

| 规则 | 级别 | 内容 |
|---|---|---|
| IDN-1 | pipeline | API Key 的生效成员关系是主体成员关系的子集；key 没有任何授予 |
| IDN-2 | pipeline | 上限只收窄：有上限时不出现上限之外的角色（按空间与角色精确匹配），直接附加策略与主体绑定的 ALLOW 不到达 key；它们的 DENY 照常适用（RC1-1） |
| IDN-3 | pipeline | 主体未知或停用时，它的 key 判为无效 |
| IDN-4 | pipeline | 带 `act` 的 token 判为无效 |
| IDN-5 | engine | `principal.type`、`auth.method` 只来自注册表与认证器；token 声明、key 与宿主属性不能写入 |
| IDN-6 | startup | 启用 API Key 而没有 `MembershipProvider` 时启动失败 |

每条规则配正反场景，IDN-1 至 IDN-5 各配一个变异体。

## 9. 交付

排在 RFC 0008 PR-5（死代码清理）之后，串行：

| PR | 内容 | 破坏性 |
|---|---|---|
| PR-1 | `fix(runtime)`：拒绝带 `act` 的 token（§6、IDN-4） | 否 |
| PR-2 | `feat!`：`principal.type`、`auth.method` 属性，`Memberships.type/active`，`CoSec-Identity` 的 `sub_type`，JWT 签发写入类型，I5 的隔离（§4.3、§5、IDN-5） | 是：`MembershipProvider` 的返回值、token 声明 |
| PR-3 | `feat!`：API Key 新格式、`MembershipSource.Provider(cap)`、启动检查、`migrate api-keys`（§4、IDN-1/2/3/6） | 是：API Key 存储格式 |

- 文档：`wiki/integration/authentication.md`（服务账号、PAT、上限、委托不支持）、`wiki/policies/language.md`（两个属性）、
  `wiki/operations/upgrading.md`（key 迁移）、`skills/cosec-integration`。
- 性能：PR-3 让 API Key 请求多一次成员关系查询；`CoCacheMembershipProvider` 本地命中时不出线程。热路径改动附 `jmh-ab.yml` 的前后对比。

## 10. 决定记录

| 编号 | 决定 | 日期 |
|---|---|---|
| Q1 | 采用三层模型；Agent 是委托关系，不是主体类型 | 2026-10-28 |
| Q2 | 主体类型与认证方式是开放属性，不是引擎枚举 | 2026-10-28 |
| Q3 | API Key 不携带授予，只有角色上限；可绑定任何主体（含 PAT） | 2026-10-28 |
| Q4 | JWT 的成员关系声明保留为 CoSec 签发的已签名缓存；外部 IdP 的 token 只走提供者 | 2026-10-28 |
| Q5 | 8.0 拒绝带 `act` 的 token；委托放到 8.1，不预留字段（`CoSec-Identity` 的版本号就是扩展点） | 2026-10-28 |
| Q6 | 上限只留角色：有上限时不取主体绑定；API Key 不取 break-glass；上限必须显式；提供者的"未知主体"是封闭类型的显式分支 | 2026-10-29 |
| Q7 | 迁移不扩大任何凭据：默认原地转换（子集时）或每把 key 一个专用工作负载主体；并集授予原主体须显式选择；解析不了的附加策略整把 key 拒绝 | 2026-10-29 |
