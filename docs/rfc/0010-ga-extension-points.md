# RFC 0010：GA 前的扩展性检查——企业级 API 安全网关场景

> 状态：已确认（2026-10-28），C1–C5 全部完成 · 上位文档：[RFC 0001](0001-target-architecture.md) P9；[RFC 0009](0009-identity-and-credentials.md) ·
> 范围：所有发布模块的公共面
>
> **2026-11-23 合订**：此前的六段修订说明（2026-10-29 至 2026-11-20）已并入正文，被取代的说法已删去；章节号与 C1–C5、Q1–Q6 的编号不变。
> 合订前的全文见 git 历史：`git show 3cd4dca9:docs/rfc/0010-ga-extension-points.md`。修订沿革见文末。

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
| 外部 IdP（OIDC/JWKS、内省、声明映射） | 新增 | 新的 `Authenticator` 实现（`Authenticator`、`TokenVerifier` 是 `cosec-spi` 里的 `@CoSecSpi`）；`MembershipProvider` 是 `fun interface`，带声明的重载以默认方法加入；`RejectReason` 增加常量；线上协议已由 C3 放开 |
| 委托（RFC 8693 `act`） | 新增 | `AuthenticatedPrincipal`、`Subject`、`Memberships`、`Identity` 都是普通类，加 `actor` 保留旧构造器；引擎的双重判定是内部实现；`CoSec-Identity` 升 v2；8.0 拒绝 `act`，以后放开是新功能 |
| mTLS / SPIFFE | 新增 | `Credentials`、`HttpFacts` 加可选的 `peer` 字段并保留旧构造器；适配器填充它是内部实现 |
| 请求签名防重放 | 新增 | `Credentials`、`HttpFacts` 加按需读取的请求体；nonce 存储是新的 SPI |
| 配额（按天、按月、按套餐） | 新增 | 文档格式加可选的 `quota` 字段（升级门槛见 C5）；计数用新的 SPI，不扩充已有的 `RateLimiter`，旧的限流实现不会误读新规则 |
| OpenAPI 校验、请求体大小 | 新增 | `RequestGate` 是公共 `fun interface`，可以装饰；`ProblemType` 增加 413、415 |
| 基于请求体的策略属性 | 新增 | `AttributeRequest` 加字段并保留旧构造器；`Attribute.Kind` 增加种类 |
| 响应侧义务（字段脱敏） | 新增 | `Obligation` 是 sealed，增加子类；`Outcome` 增加 `obligations`；放行隐患已由 C1 关上 |
| API 发现、SIEM 导出 | 新增 | 新的 `DecisionListener` 模块 |
| 管理控制台 | 新增 | 控制面 HTTP API 加新端点；版本路径已由 C2 固定 |
| Agent 身份 | 新增 | 即委托（[RFC 0009](0009-identity-and-credentials.md) §7） |
| `subject.type`、`auth.method` 属性 | 新增 | 属性目录是封闭的 `Attribute.Kind`，今天引用这两个名字的策略编译失败；token 与宿主属性都在 `subject.attributes.*` 之下，不会撞名 |
| 未知主体的负缓存 | 新增 | `CoCacheMembershipProvider` 内部实现加一个属性 |
| `RedisApiKeyReader` 的本地缓存 | 新增，**默认关闭** | 今天吊销立即生效；默认打开会把它变成"失效通知到达或 TTL 之后"，属于授权语义的变化 |
| 签发 API Key 经授权 | 新增 | 控制面加一个操作与权限；请求只读 `ApiKeyReader`，存储是 `InMemoryApiKeyStore` 与 `RedisApiKeyStore` |

结论：**现有架构的接缝足够，没有一个场景需要破坏已有 API。** 加入时遵守四条约束：

1. **公共类加字段只用次构造器**（新参数带默认值会改变 JVM 签名），公共 `data class` 只能是审定过的、永不增长的值（C4）：
   给 `data class` 加字段会同时改变主构造器和 `copy`，次构造器保得住前者，保不住后者。ABI 校验（RFC 0011）把它变成构建失败。
2. **给用户实现的接口加方法时带默认实现**（根构建打开了 `-Xjvm-default=all-compatibility`）；新语义用新 SPI，不改变旧 SPI 的含义。
3. **会增长的封闭集合以写明为准**（Q6）：每个会增长的集合（`RejectReason`、`Attribute.Kind`、`ProblemType`、`Obligation`、`ChangeEvent.Kind`、`Cause`）
   写在它的 KDoc 和 `wiki/architecture/extending.md` 里；监听器与存储实现不得穷尽匹配这些集合，`DecisionListener` 的实现要带 `else`。
   加成员不破坏 ABI，但不带 `else` 的穷尽 `when` 重新编译会失败，旧二进制遇到新值会抛 `NoWhenBranchMatchedException`。没写明的集合，GA 后不加成员。
4. **`CoSec-Identity` 的解码是严格的**（未知成员即拒绝，故意如此：被忽略的 `act` 会放行）。给头加成员时，网关在开关后面发（默认不发），或者升版本；
   滚动升级先升级下游服务，再升级网关。这是部署顺序的约束，不是 API 破坏。

公共面越小，以后能自由修改的就越多；GA 时全部冻结。所以 C4 的归类覆盖所有发布模块。

## 2. 唯一的安全问题

`Pipeline` 处理义务时曾跳过 `RateLimit` 以外的种类。当时只有 `RateLimit` 一种，不会出问题；但以后加入字段脱敏之类的义务时，任何没有同步更新的
处理路径都会**静默忽略**它，策略要求的限制不生效。这违反 fail closed。由 C1 修掉。

## 3. GA 前的改动

五项都已合入。

| 编号 | 改动 | 守护 |
|---|---|---|
| C1 | `Pipeline` 对 sealed `Obligation` 用穷尽的 `when`（不带 `else`）分派，不跳过任何种类：新增一种义务时，处理路径不更新就编译不过；模型比管道新（版本错配）时请求以 500 失败，绝不放行 | 规则 `OBL-3`（构建级），`ObligationDispatchTest` |
| C2 | `cosec.control.http.base-path` 默认是 `/cosec/control`，路由固定在 `{base-path}/v1/...`，覆盖 `base-path` 不丢掉版本 | 控制面的路由测试 |
| C3 | `Identity.Method` 接受任何非空值（约定 `jwt`、`api-key`、`service`；后两者是工作负载的凭据，`IDN-34`；方法名只是标签，人还是工作负载由封闭的 `Identity.Credential.kind` 决定，`IDN-44`，RFC 0014 R3）：外部 IdP 落地后，网关发出新的方法名时，8.0 的下游不会把它当作格式错误 | `IdentityCodec` 的测试 |
| C4 | 公共声明逐个归类，衡量标准是**文档写到的才算 API**（见下）。可能增长的记录不是 `data class`：决策值（`Decision` 的三种情形、`Cause` 除 `NotLoaded` 之外的各种情形、`Obligation.RateLimit`、引擎的 `ViewVersion`）与其他会增长的记录都是普通的 final 类，`equals`、`hashCode`、`toString` 写出，没有 `copy` 与 `componentN`，以后加字段不破坏调用方。仍是 `data class` 的公开类型各带不会增长的理由 | `PublicApiStabilityTest`（`REVIEWED_VALUES`），各模块的 ABI 转储 |
| C5 | `InstanceStatus` 带上实例能读的文档格式级别（8.0 为 `1`，`InstanceStatus.FORMAT_LEVEL`），状态的编解码要求它。发布门槛等 8.1 第一次给文档加字段时再加，那时是纯新增：格式描述符标出每个字段从哪一级开始，控制面算出文档需要的级别；有活着的实例（按 RFC 0007 §6.5 的存活规则）报的级别更低，就拒绝发布 | 状态编解码的测试 |

**C5 为什么需要**：文档是严格读取的，未知字段即拒绝。控制面先发布带新字段的平台文档，还没升级的实例就编译不了它；平台文档是必需的，
于是整个滚动升级期间所有请求都 fail closed：不会放行，但全面中断。控制面已经收到每个实例的状态，缺的只是实例能读的格式级别。

**C4 的归类结果**（313 个公共顶层类型里，约三分之二保持稳定或 SPI，其余转为实验性或内部；不涉及任何安全性质）：

- **实验性**（`@ExperimentalCoSecApi`：公开、可用，次版本可变）：不经 Spring 装配 `Pipeline` 的构建器（`Pipeline.builder`，
  `PipelineBuilder`、`PipelineSettings`、`SnapshotSettings`）；适配器契约（`RequestGate`、`HttpFacts`、`Outcome` 等）；`ControlStore`、影子端口及其记录；
  评估轨迹（`me.ahoo.cosec.engine.trace.EvaluationTrace`）。构建器取代了最初那个十一个参数的构造器：`PipelineComponents` 是内部的，
  `PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource` 是 `@InternalCoSecApi`，只给 CoSec 自己的宿主用。
- **保持稳定**：`Pipeline` 类、`AuthorizationChecker` 的字符串形式、`ProblemType`、`CoSecParameters` 的请求头名、`RouteMap` 与 `RouteMapCodec`、
  `DecisionListener` 与 `DecisionEvent`、`ChangeListener` 与 `ChangeEvent` 及其携带的值、`me.ahoo.cosec.engine.ViewVersion`、`cosec-api` 的全部类型。
- **退出 API**：`cosec-cocache` 的四个缓存接口与带 CoCache、Spring Data 类型的构造器；starter 的 `*Properties` 类与 `@ConditionalOn*` 注解（契约是属性键，
  由 `ConfigurationMetadataTest` 钉住）；条件 AST 的字面量、`ScopeResolver`、`IdentityCodec`、`PathNormalizer`、`RemoteIpResolver`；社交登录的注册表与提供者接口
  （8.0 不能添加 JustAuth 之外的提供者，列为 8.1 外部身份提供者 RFC 的候选）。
- **SPI 变更**：`ApiKeyStore` 接口删除；`cosec-login` 的 `Credentials` 改名 `LoginCredentials`，`Authentication.supportCredentials` 改名 `credentialsType`。
- **不被内联的版本号**：`SnapshotDocument.DOCUMENT_SCHEMA`、`InstanceStatus.FORMAT_LEVEL` 等不是 `const`。
- **原则**：应用自己的测试需要构造的类型保持可达。SPI 实现者与监听器作者不 opt-in 内部 API 也能做单元测试：`cosec-testkit` 的 `PipelineFixture`
  接受路由映射与监听器，`AttributeRequests` 构造属性提供者的请求。没有手工构造 `DecisionEvent` 的工厂：结果只能由 `StatusMapping` 决定，测试里也不设第二条决策路径。
- **守护**（`test/architecture`）：稳定的公共签名不得引用实验性类型；转储里不得有空的公共伴生对象，也不得有外层类已被排除的嵌套类；
  `AuditRecordFormatTest` 逐字节钉住决策事件变成的审计记录，类或包的移动不能改变下游读到的 JSON。

## 4. 决定记录

| 编号 | 决定 | 日期 |
|---|---|---|
| Q1 | GA 前只做"不做会静默放行"和"几乎零成本"的改动；不为未来功能预先抽象公共 SPI | 2026-10-28 |
| Q2 | 密钥轮换、外部 IdP、委托、配额、mTLS、请求签名等在功能到来时设计接口 | 2026-10-28 |
| Q3 | C1 采用穷尽 `when` 而非运行时拒绝分支：漏处理在编译期暴露，运行时只剩版本错配一种情况 | 2026-10-28 |
| Q4 | 公共 `data class` 在 GA 前归类（C4），只留永不增长的值；不等“真正要加字段时再处理” | 2026-11-03 |
| Q5 | 文档格式的升级门槛：GA 前只在 `InstanceStatus` 加格式级别（C5），门槛本身随 8.1 第一个文档新字段加入 | 2026-11-03 |
| Q6 | 封闭集合以写明为准：写明会增长的才增长。`Cause` 列为会增长的集合（8.x 可能为新端口的失败增加成员） | 2026-11-03，2026-11-13 补充（#1318） |

## 修订沿革

只列日期与出处，内容都在正文里。

- 2026-10-28：确认；C1–C3。
- 2026-10-29：移到 8.1 的各项逐项检查；约束 1、4。
- 2026-11-03：复查（模块重组与 RC 修复批次之后）；C4、C5、约束 3。
- 2026-11-09（#1307）：C4、C5 完成。
- 2026-11-13（#1318）：`Cause` 列为会增长的集合。
- 2026-11-14（#1354、#1355、#1356、#1358、#1359）：ABI 冻结前的公共声明归类。
- 2026-11-20（#1741，第二轮整体审查 R2A-1）：C4 扩到决策值。
