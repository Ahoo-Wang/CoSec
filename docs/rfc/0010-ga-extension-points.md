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

| 场景 | 结论 | 依据 |
|---|---|---|
| JWT 签名密钥轮换 | 以后新增 | `cosec.jwt.keys[]`、新的校验器、覆盖 `cosecAuthenticator`（`@ConditionalOnMissingBean`）都是新增 |
| 外部 IdP（OIDC/JWKS、内省、声明映射） | 以后新增；一处现在改（§3 C3） | 新的 `Authenticator`；`MembershipProvider` 是 `fun interface`，以后加带默认实现的重载即可 |
| 委托（RFC 8693 `act`） | 以后新增 | RFC 0009 §6 现在拒绝；`CoSec-Identity` 升 v2；`DecisionEvent` 的构造器是 `internal` |
| mTLS / SPIFFE、请求签名 | 功能到来时设计 | 认证器今天只看到 `Credentials(token, apiKey)`；到时给 `Credentials` 加字段，或进下一个大版本 |
| 配额 | 功能到来时设计 | 新的规则种类走新的限流实现；第三方 `RateLimiter` 很少，不为它预先抽象 |
| OpenAPI 校验、请求体大小 | 以后新增 | `RequestGate` 是公共 `fun interface`，可以装饰 |
| 基于请求体的策略属性 | 功能到来时设计 | 需要适配器缓冲请求体，代价大，按需求再定 |
| 响应侧义务（字段脱敏） | **现在改（§3 C1）** | 见 §2 |
| API 发现、SIEM 导出 | 以后新增 | 新的 `DecisionListener` 模块 |
| 管理控制台 | **现在改（§3 C2）** | 版本号只在 `base-path` 的默认值里 |

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
