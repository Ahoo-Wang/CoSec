# RFC 0010：GA 前的扩展性检查——企业级 API 安全网关场景

> 状态：已确认（2026-10-28） · 上位文档：[RFC 0001](0001-target-architecture.md) P9；[RFC 0009](0009-identity-and-credentials.md) ·
> 基线：`main` @ `c9553ad4`（#1132） · 交付：§3 的三项在 8.0 GA 之前合入 · 范围：`runtime/`、`core/`、`spring-boot-starter/`、`conformance/`

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

## 2. 唯一的安全问题

`runtime/.../Pipeline.kt` 处理义务时跳过 `RateLimit` 以外的种类（`if (obligation !is Obligation.RateLimit) continue`）。今天只有 `RateLimit` 一种，
不会出问题；但以后加入字段脱敏之类的义务时，任何没有同步更新的处理路径都会**静默忽略**它，策略要求的限制不生效。这违反 fail closed。

## 3. GA 前的三项改动

| 编号 | 改动 | 成本 |
|---|---|---|
| C1 | `Pipeline` 遇到不认识的义务种类即拒绝（500，`obligation-unsupported`），不跳过；新规则 `OBL-3`，配变异体 | 几行与一个测试 |
| C2 | `cosec.control.http.base-path` 默认改为 `/cosec/control`，路由固定在 `{base-path}/v1/...`，覆盖 `base-path` 不再丢掉版本 | 一个默认值与路由前缀 |
| C3 | `Identity.Method` 接受任何非空值（约定 `jwt`、`apikey`）：外部 IdP 落地后，网关发出新的方法名时，8.0 的下游不会把它当作格式错误 | 解码器一处 |

三项合成一个 PR（`fix!`），排在 RFC 0008 PR-5（死代码清理）之后、RFC 0009 之前。文档：`wiki/operations/configuration.md`（`base-path`）、
`wiki/integration/web.md`（500 的新原因）、`wiki/operations/upgrading.md`。

## 4. 决定记录

| 编号 | 决定 | 日期 |
|---|---|---|
| Q1 | GA 前只做"不做会静默放行"和"几乎零成本"的改动；不为未来功能预先抽象公共 SPI | 2026-10-28 |
| Q2 | 密钥轮换、外部 IdP、委托、配额、mTLS、请求签名等在功能到来时设计接口 | 2026-10-28 |
