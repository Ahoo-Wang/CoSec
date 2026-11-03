# RFC 0010：GA 前的扩展性检查——企业级 API 安全网关场景

> 状态：已确认（2026-10-28；2026-11-03 复查已确认） · 上位文档：[RFC 0001](0001-target-architecture.md) P9；[RFC 0009](0009-identity-and-credentials.md) ·
> 基线：`main` @ `c9553ad4`（#1132），复查 @ `e82abdee` · 交付：§3 的各项在 8.0 GA 之前合入（C4、C5 由复查加入） · 范围：`runtime/`、`core/`、`spring-boot-starter/`、`conformance/`；C4 覆盖所有发布模块

## 0. 原则

维护者的两条要求放在一起：

- "v8 要有足够的扩展性支持企业级 API 安全网关场景，如果扩展会导致破坏性变更，那么应该在 GA 前做。"
- "不要过早地抽象公共 SPI，这个工作量太大了，属于过度设计。"

合起来的判断标准是：**GA 前只做两类改动——不做就会在以后静默放行的（安全），以及几乎零成本、却能免去一次破坏性变更的。**
其余场景等功能真正到来时再设计接口；到时候能新增就新增（新模块、新实现、带默认实现的新方法、带次构造器的新字段），不能新增就进下一个大版本。
不为还没有的功能预先抽象请求对象、构建器或演进框架。

## 1. 场景检查

"破坏"指破坏已有 API：删除或改变已有的公共类型、方法签名、`cosec.*` 属性、token 与文档格式、控制面 HTTP API 的含义。只是新增能力的特性增强，
不算破坏。逐个场景问：以后加它，是新增，还是要改已有的？

| 场景 | 以后加入时 | 走哪个接缝 |
|---|---|---|
| JWT 签名密钥轮换 | 新增 | 新属性 `cosec.jwt.keys[]`（`secret` 保留）、新的校验器；`cosecAuthenticator` 是 `@ConditionalOnMissingBean` |
| 外部 IdP（OIDC/JWKS、内省、声明映射） | 新增；线上协议一处现在改（§3 C3） | 新的 `Authenticator` 实现；`MembershipProvider` 是 `fun interface`，带声明的重载以默认方法加入；`RejectReason` 增加常量 |
| 委托（RFC 8693 `act`） | 新增 | `AuthenticatedPrincipal` 加 `actor` 字段并保留旧构造器；引擎的双重判定是内部实现；`CoSec-Identity` 升 v2（协议本来就带版本）；`DecisionEvent` 的构造器是 `internal` |
| mTLS / SPIFFE | 新增 | `Credentials`、`HttpFacts` 加可选的 `peer` 字段并保留旧构造器；适配器填充它是内部实现 |
| 请求签名防重放 | 新增 | `Credentials`/`HttpFacts` 加按需读取的请求体；nonce 存储是新的 SPI |
| 配额（按天、按月、按套餐） | 新增 | 文档格式加可选的 `quota` 字段；计数用新的 SPI，不扩充已有的 `RateLimiter`，旧的限流实现不会误读新规则 |
| OpenAPI 校验、请求体大小 | 新增 | `RequestGate` 是公共 `fun interface`，可以装饰；`ProblemType` 增加 413/415 |
| 基于请求体的策略属性 | 新增 | `AttributeRequest` 加字段并保留旧构造器；`Attribute.Kind` 增加种类 |
| 响应侧义务（字段脱敏） | 新增；但现在有一个放行隐患（§2、§3 C1） | `Obligation` 是 sealed，增加子类；`Outcome` 增加 `obligations` |
| API 发现、SIEM 导出 | 新增 | 新的 `DecisionListener` 模块 |
| 管理控制台 | 新增；版本路径现在顺手改（§3 C2） | 控制面 HTTP API 加新端点；请求与响应体以后加 descriptor |
| Agent 身份 | 新增 | 即委托（[RFC 0009](0009-identity-and-credentials.md) §7） |

结论：**现有架构的接缝足够，没有一个场景需要破坏已有 API。** 加入时遵守三条常规做法即可：给公共类加字段时保留旧构造器；给用户实现的接口加方法时带默认实现
（`-Xjvm-default=all-compatibility` 已经打开）；新语义用新 SPI，不改变旧 SPI 的含义。

唯一的细节是公共 SPI 里的 `data class`：加字段会改变 `copy` 与 `componentN`，这对已编译的调用方是二进制破坏。会在 GA 前重写的两个
（`ApiKey`、`Memberships`，[RFC 0009](0009-identity-and-credentials.md) PR-3）顺手改成普通类，没有额外成本；其余的等真正要加字段时再处理。

> **2026-10-29 补充（移到 8.1 的项逐项检查）**：都可以新增，不需要在 GA 前改动已有 API。
>
> | 项 | 以后加入时 | 理由（对照 `main` 的代码） |
> |---|---|---|
> | `subject.type`、`auth.method` 属性（RFC 0009 PR-2） | 新增 | 属性目录是封闭的 `Attribute.Kind`，今天引用这两个名字的策略编译失败；token 与宿主属性都在 `subject.attributes.*` 之下，不会撞名。`Memberships` 是普通类，加 `type` 字段保留旧构造器 |
> | 委托（RFC 8693 `act`） | 新增 | `AuthenticatedPrincipal`、`Subject`、`Memberships` 都是普通类，加 `actor` 保留旧构造器；`CoSec-Identity` 升 v2；8.0 拒绝 `act`，以后放开是新功能 |
> | 未知主体的负缓存 | 新增 | `CoCacheMembershipProvider` 内部实现加一个属性 |
> | `RedisApiKeyReader` 的本地缓存 | 新增，**默认关闭** | 今天吊销立即生效；默认打开会把它变成"失效通知到达或 TTL 之后"，属于授权语义的变化 |
> | 签发 API Key 经授权 | 新增 | 控制面加一个操作与权限；`ApiKeyStore` 保留为底层存储 |
>
> 两条约束保证它们是新增：
> 1. **公共类加字段只用次构造器**（新参数带默认值会改变 JVM 签名）。RFC 0011 PR-3 的 ABI 校验在 RC 后把它变成构建失败。`MembershipAnswer` 等 sealed 类型 GA 后不再加子类型：新信息加在值类上。
> 2. **`CoSec-Identity` 的解码是严格的**（未知成员即拒绝，故意如此：被忽略的 `act` 会放行）。所以给头加成员时，网关在开关后面发（默认不发），或者升版本；滚动升级先升级下游服务，再升级网关。这是部署顺序的约束，不是 API 破坏；8.1 的 RFC 写明。
>
> 非数组 `roles`/`policies` 声明返回 500 是缺陷，修成 401 不是破坏性变更，放进 RC 前的修复批。

> **2026-11-03 复查（对照 `main` @ `e82abdee`，在 RFC 0011 的模块重组与 RC 修复批次 B1、B2 之后）**：§1 的接缝仍然成立：
> - `Authenticator`、`TokenVerifier` 是 `cosec-spi` 里的 `@CoSecSpi`；
> - `Credentials`、`AuthenticatedPrincipal` 是普通类，主构造器为 `internal`，可以用次构造器加字段；
> - 根构建仍有 `-Xjvm-default=all-compatibility`；
> - C1–C3 已合入。
>
> 有两处不成立，一处要写明：
>
> 1. **公共 `data class` 加不了字段。** 上文"其余的等真正要加字段时再处理"在 ABI 校验之后行不通。
>    - 给 data class 加字段会同时改变主构造器和 `copy`：次构造器保得住前者，保不住后者（Kotlin 不允许手写 `copy`）。GA 后这就是删掉 ABI 文件里的一行，PR 要带 `!`。10-29 的约束 1 因此只对普通类成立。
>    - 发布模块 ABI 文件里的公共 `data class`：`cosec-model` 19 个，`cosec-runtime` 27 个，`cosec-control` 29 个，`cosec-spi` 8 个，`cosec-engine` 3 个，`cosec-api` 4 个，starter 的属性类 9 个。
>    - 其中已知会加字段的：
>      - `Identity` 与 `Identity.Credential`：委托的 `actor`。§1 只看了 `AuthenticatedPrincipal`。
>      - `PipelineComponents`：新端口，如请求签名的 nonce 存储。
>      - `SnapshotOptions`。
>      - `Audit*Data`：`actor`、`peer`。
>      - `InstanceStatus`：第 2 条。
>      - 控制面 API 的返回类型。
>      - starter 的属性类：每个新的 `cosec.*` 属性都要加字段。
>
>    → §3 C4。
> 2. **文档格式没有升级门槛。**
>    - 文档是严格读取的：未知字段即拒绝，故意如此。而配额、外部 IdP 的声明映射，都要给文档加字段。
>    - 控制面先发布带新字段的平台文档，还没升级的实例就编译不了它。平台文档是必需的，于是整个滚动升级期间所有请求都 fail closed：不会放行，但全面中断。
>    - 控制面已经收到每个实例的 `InstanceStatus`（RFC 0007 §6.5），缺的是实例能读的格式级别。状态的编解码也是严格的：8.1 再给它加字段，8.0 的控制面就读不了新实例的状态。
>
>    → §3 C5。
> 3. **会增长的封闭集合要写明。**
>    - §1 计划给 `RejectReason`、`Attribute.Kind`、`ProblemType` 加常量，给 `Obligation` 加子类；`ChangeEvent.Kind` 已经加了 `ABANDONED`（#1192）。
>    - 加成员不破坏 ABI。但应用里不带 `else` 的穷尽 `when`，重新编译会失败；旧二进制遇到新值，会抛 `NoWhenBranchMatchedException`。
>    - 10-29 说"`MembershipAnswer` 等 sealed 类型 GA 后不加子类型"，和 §1 里 `Obligation` 那一行冲突。以写明的为准：每个会增长的集合写在它的 KDoc 和 `wiki/architecture/extending.md` 里；监听器和存储实现不得穷尽匹配这些集合。没写明的集合，GA 后不加成员。
>
>    → §3 C4。
>
> `CoSec-Identity` 的严格解码与部署顺序，已由 10-29 的约束 2 写明，不需要改动。
>
> 公共面越小，以后能自由修改的就越多。`cosec-runtime` 有 161 个公共类型（含嵌套类型），`cosec-control` 148 个，`cosec-model` 135 个，`cosec-spi` 90 个，GA 时全部冻结。所以 C4 的归类覆盖所有发布模块，不只是 engine 和 runtime。

## 2. 唯一的安全问题

`runtime/.../Pipeline.kt` 处理义务时跳过 `RateLimit` 以外的种类（`if (obligation !is Obligation.RateLimit) continue`）。今天只有 `RateLimit` 一种，
不会出问题；但以后加入字段脱敏之类的义务时，任何没有同步更新的处理路径都会**静默忽略**它，策略要求的限制不生效。这违反 fail closed。

## 3. GA 前的改动

| 编号 | 改动 | 成本 |
|---|---|---|
| C1 | `Pipeline` 对 sealed `Obligation` 用穷尽的 `when`（不带 `else`）分派，不跳过任何种类：新增一种义务时，处理路径不更新就编译不过；模型比管道新（版本错配）时请求以 500 失败，绝不放行。新规则 `OBL-3`（构建级），由 `ObligationDispatchTest` 守护 | 几行与一个架构测试 |
| C2 | `cosec.control.http.base-path` 默认改为 `/cosec/control`，路由固定在 `{base-path}/v1/...`，覆盖 `base-path` 不再丢掉版本 | 一个默认值与路由前缀 |
| C3 | `Identity.Method` 接受任何非空值（约定 `jwt`、`apikey`）：外部 IdP 落地后，网关发出新的方法名时，8.0 的下游不会把它当作格式错误 | 解码器一处 |
| C4 | 公共 `data class` 逐个归类：<br>- 永不增长的值（id、字面量、`SnapshotKey.App` 这类）：保留。<br>- 可能增长的记录：主构造器改为 `internal`，加 `@ConsistentCopyVisibility`（`copy` 随之变为 internal），对外只留次构造器或工厂；或者改成普通类。<br>- 应用用不到的：改为 `internal` 或 `@InternalCoSecApi`。<br>`test/architecture` 加一条规则：发布模块的公共 `data class` 必须在审过的值类型白名单里。会增长的封闭集合写在 KDoc 和 `extending.md` 里。10-29 的约束 1 随之改为：公共类加字段只用次构造器，公共 `data class` 只能是白名单里的值。这一项与 RC 前的公共声明归类合为一步，范围是所有发布模块 | 每个类一处机械改动，一个架构测试；GA 前改 ABI 文件不算破坏 |
| C5 | `InstanceStatus` 加上实例能读的文档格式级别（8.0 为 `1`），状态的编解码要求它。发布门槛等 8.1 第一次给文档加字段时再加，那时是纯新增：格式描述符标出每个字段从哪一级开始，控制面算出文档需要的级别；有活着的实例（按 RFC 0007 §6.5 的存活规则）报的级别更低，就拒绝发布 | 一个字段 |

C1–C3 合成一个 PR（`fix!`），排在 RFC 0008 PR-5（死代码清理）之后、RFC 0009 之前。文档：`wiki/operations/configuration.md`（`base-path`）、
`wiki/integration/web.md`（版本错配时的 500）、`wiki/operations/upgrading.md`。C4、C5 由 2026-11-03 的复查加入，放在 RC 前的公共声明归类那一步做
（[路线图](../roadmap.md) §10）。文档：`wiki/architecture/extending.md`（会增长的集合）、`wiki/operations/upgrading.md`（改了形状的公共类型）。

## 4. 决定记录

| 编号 | 决定 | 日期 |
|---|---|---|
| Q1 | GA 前只做"不做会静默放行"和"几乎零成本"的改动；不为未来功能预先抽象公共 SPI | 2026-10-28 |
| Q2 | 密钥轮换、外部 IdP、委托、配额、mTLS、请求签名等在功能到来时设计接口 | 2026-10-28 |
| Q3 | C1 采用穷尽 `when`（方案 C）而非运行时拒绝分支：漏处理在编译期暴露，运行时只剩版本错配一种情况 | 2026-10-28 |
| Q4 | 撤回 §1 的"其余 data class 等真正要加字段时再处理"：公共 `data class` 在 GA 前归类（C4），只留永不增长的值 | 2026-11-03 |
| Q5 | 文档格式的升级门槛：GA 前只在 `InstanceStatus` 加格式级别（C5），门槛本身随 8.1 第一个文档新字段加入 | 2026-11-03 |
| Q6 | 封闭集合以写明为准：写明会增长的才增长，取代 10-29 "sealed 类型 GA 后不加子类型"的笼统说法 | 2026-11-03 |
