# RFC 0004：统一模型——绑定、租户分层与数据转换（RFC 0001 迁移第 4 阶段，上篇）

> 状态：已确认（第 2 版，§15 / §11 的决定已于 2026-10-22 由维护者确认）· 上位文档：[RFC 0001](0001-target-architecture.md) §3.2、§3.3、§6 第 4 阶段、§7.1 ·
> 前置：[RFC 0002](0002-pure-engine.md)（第 2 阶段，PR-1 到 PR-5 已合入并完成审查）、[RFC 0003](0003-policy-snapshot.md)（第 3 阶段，已确认，PR-1 到 PR-3 已合入）·
> 姊妹篇：[RFC 0005](0005-condition-ast.md)（第 4 阶段下篇：条件 AST、动作词汇、编译执行）·
> 基线：`main` @ `a361084d`（第 1 版写于 `334da022`） · 交付：全部合入 `main`（8.0 线）；8.0 在 RFC 0001 第 2–6 阶段全部完成、各做完两轮审查之前不发布任何版本 ·
> 范围：`model/`、`engine/`、`cosec-core`、`cosec-cocache`、`cosec-jwt`、`cosec-spring-boot-starter`，新增 `migrate/`（`cosec-migrate`）
>
> **2026-10-26 修订（[RFC 0008](0008-conformance-and-direct-build.md)）**：8.0 不考虑兼容性。门槛 A（§9 整节）、PR-6（分类器）取消；PR-7、PR-8 并入 RFC 0008 的切换 PR；PR-4 的文档直接使用
> RFC 0005 的 AST，不再有"绑定模型 + 7.x 匹配器"的中间格式，也不做 `UnifiedAuthorization`；PR-5 不做 `verify`（RFC 0008 §4.1、§6 Q3）。
> §5.3 的 C1–C6 不再是差异类别，而是登记在一致性套件中的规则；P1′、P8–P12 作为规范性质保留（RFC 0008 §3.5）。下文保留原样以便追溯。

## 0. 结论先行

第 4 阶段把 CoSec 现在的三套挂载方式（全局策略、主体直接引用的策略、角色权限）统一成**一种数据**：

```
Binding(谁, 在哪个作用域) → Policy
```

再加上两样不进快照的输入：经 `ScopeResolver` 校验的 `Scope`，以及来自 token 声明（或 `MembershipProvider`）的成员关系。

- **拆成两篇 RFC**（§1）。本文（RFC 0004）负责模型：`Binding`、平台层与租户层、成员关系与作用域、黑名单和 root 的新形态、
  扁平组合算法、`evaluate(request, snapshot)`、快照版本，以及面向用户的 7.x → 8.0 一次性转换工具和本阶段的门槛。
  [RFC 0005](0005-condition-ast.md) 负责语言：条件 AST、`service:verb` 动作与 `http` 命名空间、编译执行与索引。两篇共用一套门槛方法，
  各有一次切换。
- **转换后的数据不改变租户归属**（§4.3、§5）。7.x 的数据没有租户维度：全局策略对所有租户生效，主体可以引用任何策略，角色授予按
  `(app, role, space)` 存放、对所有租户生效（RFC 0003 §2 I1）。转换工具把这些数据原样放进**平台层**，租户层起初为空。租户自己的数据
  从第 6 阶段的控制面开始写入。这样本阶段的决策差异只来自刻意的语义修订，而不来自数据搬家。
- **快照由三份文档组合而成**：`Platform`、`App(appId)`、`Tenant(tenantId)`，版本号是三元组（§7）。RFC 0001 写的二元组
  `(platform, tenant)` 与 RFC 0003 已确认的按应用分区矛盾，本文指出并取三元组（§3 I4）。三份文档各自编译、各自缓存，一次评估持有它们的
  不可变组合；平台层更新不触发租户层重新编译（§3 I5）。
- **组合算法改为扁平顺序**（§6）：先对全部候选语句检查 DENY，再检查 ALLOW。比 RFC 0001 §3.3 的伪代码多两条规则：任何一条 DENY 命中
  都优先于别处的错误、缺失和不可用；DENY 出错不再与迭代顺序有关（§3 I6）。候选语句按"黑名单 → root → 全局 → 主体 → 角色"排序，
  所以没有错误时，连做出决策的那一条都与 7.x 相同（§6.3）。
- **语义变化逐条列出**（§5.3 C1–C6）。门槛（§9）沿用 RFC 0002 §8.2.1 的离线回放，比较器从"差异为零"改为"每一条差异都能被机械地
  归入一个已列出的类别，并且带有引擎 trace 提供的证据"。归不了类的差异就是失败。让结果变宽（放行原本拒绝的请求）的类别只有一个（C1a），
  它的每一条样本都要单独列出，由维护者逐条确认。
- **转换工具**（§8）是一个随 8.0 发布的模块 `cosec-migrate`：输入 7.x 的 Redis 数据或导出文件，输出 8.0 的快照文档、来源映射和报告。
  它拒绝在 7.x 中本来就会失败的数据，而不是替运维猜。用户用自己的审计日志验证转换结果，复用同一个回放器和同一个分类器。
- 拆成 8 个 PR（§14）。前 6 个只做加法，大部分可以在 RFC 0002 PR-6、RFC 0003 PR-5 之前开工；切换（PR-7）和删除（PR-8）在两者和本阶段门槛之后。

---

## 1. 为什么拆成两篇

RFC 0001 §6 第 4 阶段一句话里包含了六件事：`Binding`、成员关系、平台层与租户层、黑名单拆分、数据转换工具、条件 AST。再加上 RFC 0002 §1.2
推迟到第 4 阶段的扁平算法、编译执行和索引，以及 RFC 0001 §7.1 已确认的 `service:verb` 动作词汇，一份 RFC 装不下，审查也很难做到
RFC 0002、0003 的粒度。按"改的是什么"分成两篇：

| | RFC 0004（本文）：模型 | [RFC 0005](0005-condition-ast.md)：语言与执行 |
|---|---|---|
| 回答的问题 | 一条语句**对谁、在哪里**生效；多条语句怎么组合 | 一条语句**匹配什么**；怎么快速找到可能匹配的语句 |
| 主要内容 | `Binding`、平台层 / 租户层、成员关系、`ScopeResolver`、黑名单、root、扁平算法、`evaluate(request, snapshot)`、快照三元组、转换工具、门槛方法 | 条件 AST（类型、成本、可分析性）、`service:verb` 与类型化资源、`http` 命名空间、OpenAPI 路由映射、编译执行、索引及其完整性 |
| 匹配器 | 原样使用 7.x 的动作与条件匹配器（经 RFC 0002 PR-3 的适配层） | 以 AST 取代全部内置匹配器、OGNL、SpEL |
| 切换 | PR-7：在 7.x 匹配器之上切换到绑定模型（门槛 A） | 它的切换 PR：匹配器换成 AST，线性扫描换成索引（门槛 B） |

> **2026-10-26**：下面"两次切换各有一道门槛"的理由随门槛 A、B 一并取消（[RFC 0008](0008-conformance-and-direct-build.md)）。两篇仍按"模型 / 语言"分工，但只有一次切换（RFC 0008 §4.4），
> 中间格式不再实现。

**为什么先做模型、后做语言**：

- 扁平算法的前提之一是"没有层"（RFC 0002 §4.3），层的消失来自 `Binding`，所以扁平算法属于本文。另外两个前提（条件 AST 不抛任意异常、
  `onMissing`）只影响出错路径；扁平算法把 ALLOW 出错按未命中处理，即使匹配器还会抛任意异常，结果也只会更严（§6.2）。
- 两次切换各有一道门槛，每次的差异只来自一类改动：门槛 A 的差异只可能来自组合顺序、作用域和 root；门槛 B 的差异只可能来自匹配器的翻译。
  合成一次，一条差异就很难判断是哪一部分造成的。
- 条件 AST 的设计（运算符、类型、包含判断）可以与本文并行，`model/` 中的 AST 类型不依赖绑定模型；RFC 0005 的前两个 PR 可以和本文的 PR 同时开工。

两篇共用：§9 的门槛方法（分类器、证据、判定规则）、§8 的转换工具（RFC 0005 往里加匹配器翻译）、§12 的 JMH 方法。RFC 0005 只写它增加的部分。

**8.0 GA 时用户只做一次转换**。门槛 A 与门槛 B 之间的中间格式（绑定模型 + 7.x 匹配器 JSON）只存在于 `main` 上，从未发布；GA 版本的
转换工具一次完成两篇的全部转换（§8.6）。

---

## 2. 范围与非目标

### 2.1 本阶段（上篇）要做的

1. `model/` 中的新类型（§4.1）：值类型、`Binding`、`Memberships`、`Scope`（只能由 `ScopeResolver` 构造）、新的 `StatementRef`。
2. 快照文档 `Platform`、`App(appId)`、`Tenant(tenantId)` 的格式与编译（§7），复用 RFC 0003 的 `SnapshotSource`、`SnapshotDocumentStore`、
   条件读写和陈旧度机制。
3. 成员关系：从 token 声明解析（§4.5），`MembershipProvider` SPI 及其失败即拒绝的契约，按主体吊销全部 token（§4.5.3）。
4. `ScopeResolver`（§4.6）。
5. 黑名单拆分（§4.7）、root 改为 break-glass 绑定（§4.8）。
6. 引擎：扁平组合算法、绑定的适用性查找、`Engine.evaluate(request, snapshot)`、trace（§6）。
7. 一次性转换工具 `cosec-migrate`（§8）。
8. 门槛 A：差异分类器、改动目录、合成语料、离线回放（§9）。
9. 删除层、`RolePermission`/`AppRolePermission`、`PolicyType.GLOBAL` 的评估语义、`RootPrincipalMatcher`、`RoleScope` 在授权路径上的使用（§14 PR-8）。

### 2.2 非目标

| 内容 | 处理 | 留到 |
|---|---|---|
| 条件 AST、`service:verb`、索引、编译执行（RFC 0001 §3.3、P10） | 不在本文。本阶段的语句仍由 7.x 匹配器执行，引擎在适用的绑定内线性扫描 | [RFC 0005](0005-condition-ast.md) |
| `onMissing`、`AttributeProvider`（RFC 0001 §3.3、§3.5） | 本阶段没有外部属性来源（§3 I7）；黑名单名单仍由 `BlacklistChecker` 提供 | 第 5 阶段 |
| 503、`Decision` 直接进入 Pipeline、`DecisionListener`、`REQUIRED` 审计 | `Indeterminate` 仍映射为 500（RFC 0002 Q3、RFC 0003 Q16） | 第 5 阶段 |
| 协程、挂起的 `MembershipProvider` | 本阶段的 SPI 返回 `CompletableFuture`（与 RFC 0003 Q10 相同） | 第 5 阶段 |
| 平台边界策略、`withinBoundary`（RFC 0001 §3.7） | 只在算法里预留位置，本阶段恒为通过 | 第 6 阶段 |
| 控制面、不能越权授权、租户自己写入数据 | 本阶段只有 RFC 0003 的 `SnapshotPublisher`，扩展到绑定（§7.4）；"谁能写"不做检查 | 第 6 阶段 |
| 从模型生成 `schema/*.json` | 本阶段手工更新 schema | 第 6 阶段 |
| 把租户自己的数据从平台层拆出来 | 转换工具提供可选的 `--partition=by-owner`（§8.3），默认不拆 | 运维自行决定；第 6 阶段之后 |

---

## 3. RFC 0001 中与第 4 阶段有关的不一致

与 RFC 0003 §2 一样，不悄悄选边，逐条列出，每条对应 §15 的一个决定。

| # | RFC 0001 原文 | 问题 | 本文的处理 |
|---|---|---|---|
| I1 | §3.2：`Binding` 的主体是 `principal \| role \| group \| *` | `group` 在 RFC 0001 和现有代码里都没有定义。CoSec 从来没有"组"，身份源里的组到了 CoSec 就是角色 | 不引入 `group`；身份源的组以角色的形式写进 token。Q1 |
| I2 | §3.2：`Binding` 取代 `principal.policies`；P11：主体级的高基数数据不进快照 | 主体直接引用策略（`principal.policies`）是**每个用户**一份的数据。把它们变成快照里的绑定，快照就要装下所有用户，违反 P11；而且这些数据在 token 签发方手里，转换工具根本拿不到 | 主体直接引用的策略属于成员关系，叫**附加**（attachment），与角色一样来自 token 声明（§4.4）。快照里的主体绑定只用于少量、低基数的情形（break-glass root、服务账号）。Q2 |
| I3 | §3.2："D3 在模型层面消失：一个绑定不写 `app` 就不会匹配到别的应用" | 字面意思是"不写 `app` = 不匹配任何别的应用"，那么全局策略（对所有应用生效）就无法表达；如果"不写 = 所有应用"，这句话就反了 | 作用域的每个维度都必须显式写出，通配写作 `*`，没有隐含默认值（§4.2）。Q3 |
| I4 | §3.2、§3.4：快照版本是 `(platform, tenant)` 二元组；RFC 0003 §3.7："字段改名，含义一一对应" | RFC 0003 把应用权限按应用分成文档，理由是规模（角色授予按 `(app, role, space)` 计，空间可能很多，见 RFC 0003 §3.2 方案 C）。7.x 的应用权限和角色授予**没有租户维度**，转换后属于平台层；二元组意味着把全部应用放进一份平台文档，正是 RFC 0003 否决的方案 C | 保留按应用分区：一次评估读 `Platform`、`App(appId)`、`Tenant(tenantId)` 三份文档，版本是三元组（§7.2）。Q4 |
| I5 | §3.2："加载租户快照时，把平台层一起合并、编译成一份；平台层更新会触发所有已加载租户的重新编译" | 平台层的一次修改要重新编译每一个已加载的租户，代价是 O(租户数 × 租户规模)，正好发生在平台发布这个最需要快速生效的时刻 | 三份文档各自编译、各自安装；一次评估持有三者的不可变组合（`SnapshotView`），P7 仍然成立：组合里的每一份都是一个确定的版本（§7.3）。Q4 |
| I6 | §3.3 伪代码：DENY 扫描中遇到 `ERROR` 立即返回 `Indeterminate` | 结果依赖迭代顺序：同一组语句里，出错的 DENY 排在命中的 DENY 前面就是 `Indeterminate`，排在后面就是 `Deny`。决策应当与候选集的顺序无关 | 先扫完全部 DENY：任何一条命中即 `Deny`；没有命中而有出错（或有缺失、不可用的输入）才是 `Indeterminate`（§6.1）。Q6 |
| I7 | §3.3："语句引用的属性没有取到时，按 `onMissing` 处理，默认 `indeterminate`" | 把两件事混在一起：**本来就可能没有**的属性（请求头、路径变量、token 里没写的属性；7.x 读到空串）和**取不到**的属性（外部属性来源超时）。前者按 `indeterminate` 处理，每个不带某个可选请求头的请求都会 500 | 本来就可能没有的属性按"不存在"参与比较（比较为假，`exists` 为假），不是错误；`onMissing` 只适用于第 5 阶段 `AttributeProvider` 提供的属性。详见 RFC 0005 §3.4。Q7 |
| I8 | §3.2："root 同样受这条 DENY（黑名单）约束，除非显式豁免" | 没说 root 是否受**其他** DENY 约束。在扁平算法里，root 是一条允许一切的 ALLOW 绑定，任何命中的 DENY 都会胜过它；而 7.x 的 root 跳过全部策略 | root 受**全部** DENY 约束，没有豁免机制（§4.8，改动 C2）。Q8 |
| I9 | §3.2、§3.7：break-glass 绑定"只能由控制面创建，强制审计（`REQUIRED`）" | 控制面在第 6 阶段，`REQUIRED` 审计在第 5 阶段 | 本阶段：root 绑定由转换工具或配置生成，必须带过期时间；过期由引擎判定；使用时写一条 WARN 日志和现有的审计事件。`REQUIRED` 审计在第 5 阶段补上。Q8 |
| I10 | §3.2：黑名单名单"由 `AttributeProvider` 以 `environment.blocked` 之类的属性提供" | `AttributeProvider` 在第 5 阶段 | 本阶段 `BlacklistChecker` 继续作为名单来源，结果写进 `environment.blocked`；规则成为平台层的一条内置 DENY 语句（§4.7）。Q9 |
| I11 | §4：`cosec-model` 依赖 `kotlinx-serialization-core` | RFC 0002 已确认 `model/` 只依赖 Kotlin 标准库（`verifyPureClasspath`） | `model/` 保持纯净；文档的编解码放在 `cosec-core`，用 Jackson 流式 API，不用数据绑定（§11）。Q10 |
| I12 | §3.5："`ScopeResolver` 是内置实现，规则来自快照" | 没说是什么规则。空间成员关系来自主体，不来自快照；应用由宿主决定，也不来自快照 | 本阶段 `ScopeResolver` 不读快照（§4.6）。将来若要"应用必须在目录中存在"之类的规则，再从快照读。Q11 |
| I13 | §6 第 4 阶段："用条件 AST 取代 OGNL/SpEL 和各个 part 匹配器"；§3.3："不再为每种匹配器写一个 `ConditionMatcherFactory`"；§3.5：阻塞的自定义匹配器由第 5 阶段的 `AttributeProvider` 取代 | 自定义条件匹配器是 AGENTS.md 列出的扩展点。第 4 阶段删除 `ConditionMatcherFactory`，而它的替代品在第 5 阶段才有，中间一个阶段自定义匹配器无处可去 | 由 RFC 0005 处理：保留一个不可分析的 `legacy` 条件节点承载自定义匹配器，到第 5 阶段删除（RFC 0005 §3.7、Q2） |
| I14 | §3.2 示例：成员关系声明写作 `roles: {"blog/team-a": ["editor"]}`，键是 `应用/空间` | 7.x 的空间与应用无关：`editor@team-a` 在 team-a 里对请求所在的任何应用生效，授予才区分应用 | 成员关系的键只是空间；应用维度由绑定表达（§4.5）。现有的 `roles` 列表格式（`role@space`）就是按作用域写的声明，直接沿用，不需要重新签发 token。Q12 |
| I15 | §3.2："`Catalog` 供 UI、生成器、校验使用，不参与评估" | 7.x 的 `AppPermission.condition` 参与评估（应用条件不命中，整个角色层不生效） | 应用条件转换为每条权限策略的策略级条件（§5.1），`Catalog` 只保留不参与评估的部分 |
| I16 | §3.4、§3.2：快照"按租户分区"，暗含数据属于某个租户 | 7.x 的数据没有租户归属（RFC 0003 I1）。`Policy.tenantId` 虽然存在，授权从不读它，生成器还总是写默认租户 | 转换后的数据默认全部放进平台层，租户层起初为空（§5.2）。Q5 |
| I17 | §7.1："紧急撤销靠吊销该主体的全部 token" | 7.x 只有按单个 token 吊销（[`TokenRevoker`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/token/TokenRevoker.kt)），没有"吊销某个主体的全部 token" | 本阶段增加按主体吊销（§4.5.3），否则"成员关系默认来自 token 声明"这个已确认的决定缺少它依赖的紧急手段。Q13 |

---

## 4. 模型

### 4.1 `model/` 中的类型

```kotlin
package me.ahoo.cosec.model

@JvmInline value class TenantId(val value: String) {
    companion object { val PLATFORM = TenantId("(platform)"); val DEFAULT = TenantId("(0)") }   // 与 Tenant.PLATFORM_TENANT_ID、CoSec.DEFAULT 相同
}
@JvmInline value class PrincipalId(val value: String)
@JvmInline value class AppId(val value: String)
@JvmInline value class SpaceId(val value: String) { companion object { val DEFAULT = SpaceId("") } }   // 与 SpaceIdCapable.DEFAULT 相同
@JvmInline value class RoleId(val value: String)
@JvmInline value class PolicyId(val value: String)
@JvmInline value class BindingId(val value: String)

/** 谁。主体 id 只在租户内唯一（D5 的教训），所以主体引用总是带租户。 */
sealed interface SubjectRef {
    data class Principal(val tenant: TenantId, val id: PrincipalId) : SubjectRef
    /** 平台层的角色绑定 tenant == null，表示任何租户里叫这个名字的角色（7.x 语义，§5.2）；租户层的 tenant 就是文档所属租户。 */
    data class Role(val tenant: TenantId?, val id: RoleId) : SubjectRef
    /** 任何主体，包括匿名主体。即 7.x 的 GLOBAL。 */
    data object Anyone : SubjectRef
}

/** 在哪里。每个维度都显式写出（§3 I3）。 */
class BindingScope(val app: Match<AppId>, val space: Match<SpaceId>)
sealed interface Match<out T> { data object Any : Match<Nothing>; data class Exactly<T>(val value: T) : Match<T> }

class Binding(
    val id: BindingId,
    val subject: SubjectRef,
    val scope: BindingScope,
    val policy: PolicyId,
    /** 过期时刻（epoch 毫秒）。break-glass 绑定必须有，其余可选。过期的绑定不适用，trace 记为 EXPIRED。 */
    val expiresAt: Long? = null,
    val breakGlass: Boolean = false,
)

/** 主体的成员关系：不进快照（P11），每个请求从 token 声明或 MembershipProvider 得到。 */
class Memberships(
    val source: Source,
    /** 每个空间里的角色。默认空间的键是 SpaceId.DEFAULT。 */
    val roles: Map<SpaceId, Set<RoleId>>,
    /** 主体直接附加的策略（7.x 的 principal.policies），保持声明里的顺序。 */
    val attachments: List<PolicyId>,
    /** 声明里写了、但不合法的条目（如 `a@b@c`），只用于 trace；它们不在任何作用域生效，与 RoleScope 相同。 */
    val malformed: List<String> = emptyList(),
) { enum class Source { CLAIMS, PROVIDER } }

/** 经过校验的作用域。构造器是 internal 的，只有 cosec-core 的 ScopeResolver 能构造（RFC 0001 P4、§5"作用域可信"）。 */
class Scope internal constructor(
    val tenant: TenantId,
    val app: AppId,
    val appSource: AppSource,
    val space: SpaceId,
    /** 该空间里生效的角色，按声明顺序。 */
    val roles: List<RoleId>,
) { enum class AppSource { HOST, DECLARED } }
```

- RFC 0002 §3.1 推迟的值类型在这里引入。它们只出现在 API 边界上；编译后的结构内部用 `String`，因为 `Set<RoleId>` 之类的集合会给每个
  元素装箱，热路径上不能接受（§12 的分配目标守护）。
- `Scope` 的构造器是 `internal`，Kotlin 的 `internal` 以模块为界，所以 `ScopeResolver` 必须与 `Scope` 在同一个模块里。它是不做 I/O 的纯函数
  （§4.6），放在 `model/` 的 `me.ahoo.cosec.model.scope` 包中，满足 `model/` 的纯净约束。RFC 0001 §3.5 把它列在 `cosec-runtime` 里，那时 `Scope`
  也要一起搬走；第 5 阶段再定。一个 ArchUnit 规则另外守护：`Scope` 的构造器只被 `ScopeResolver` 调用。
- `AuthzRequest` 变化：`subject` 增加 `memberships`，删除 `roles`、`policies`、`root`；`scope` 改为上面的 `Scope`；`environment` 增加
  `time`（epoch 毫秒，由调用方提供，引擎不读时钟，`EnginePurityTest` 已禁止）。`HostFacts` 保留到 RFC 0005 的切换，承载 7.x 匹配器需要的
  `Request`/`SecurityContext`。
- **包的位置（PR-1，2026-10-25）**：`model/` 里已有 RFC 0002 的 `me.ahoo.cosec.model.Scope`（声明的作用域）、`StatementRef` 和 `Subject`，
  引擎和 `cosec-core` 都在用，到 PR-7/PR-8 才删除。为了不与它们同名，新的 `Scope` 与 `ScopeResolver`、`DeclaredScope`、`HostScope`、
  `ScopeResolution` 一起放在 `me.ahoo.cosec.model.scope`；`ScopeResolver` 的主体参数是同一包里的 `ScopeSubject(tenant, authenticated, memberships)`，
  不是 `Subject`。新的 `StatementRef`（含 `ScopeRejected`）推迟到 PR-2，引擎第一次用到它的时候。其余类型按上面的代码放在 `me.ahoo.cosec.model`。
- **`*` 是保留值（第一轮审查，2026-10-26）**：文档用 `*` 表示 `Match.Any`（§7.1），所以 `AppId`、`SpaceId` 和 `Match.Exactly` 都不接受 `*`
  （`Match.WILDCARD`），否则一条授予给字面空间 `*` 的数据解码回来就成了"所有空间"。`ScopeResolver` 把声明为 `*` 的应用按 `APP_MISMATCH`、
  空间按 `SPACE_NOT_MEMBER` 拒绝。

### 4.2 `Binding`

一条绑定说的是："`subject` 在 `scope` 里受 `policy` 约束"。策略里的语句决定允许还是拒绝，绑定本身没有效果。

**适用性**：对一个请求，绑定 `b` 适用当且仅当

```
appliesTo(b, req) =
    subjectMatches(b.subject, req.subject, req.scope)
    && (b.scope.app   == Any || b.scope.app   == Exactly(req.scope.app))
    && (b.scope.space == Any || b.scope.space == Exactly(req.scope.space))
    && (b.expiresAt == null || req.environment.time < b.expiresAt)

subjectMatches(Anyone, _, _)                 = true
subjectMatches(Principal(t, id), s, _)       = s.tenant == t && s.id == id && s.authenticated
subjectMatches(Role(t, r), s, scope)         = (t == null || t == s.tenant) && r in scope.roles
```

- 角色绑定看的是 `scope.roles`，即**请求所在空间**里生效的角色，与 7.x 的 `RoleScope.rolesIn` 相同：`editor` 只在默认空间生效，
  `editor@team-a` 只在 team-a 生效（§5.1）。
- 绑定的空间维度与成员关系的空间维度是两回事：成员关系决定"主体在这个空间里是什么角色"，绑定的 `space` 决定"这个角色在哪个空间里
  得到这条策略"。7.x 的 `RolePermissionCache` 按 `(app, role, space)` 存放授予，两者恰好一一对应（§5.1）。
- 匿名主体不匹配任何 `Principal` 绑定，也没有角色，只能匹配 `Anyone`。
- 作用域的比较是精确的、区分大小写的，不做去空白（`Match.matches`）。`*` 只表示 `Any`，不能作为具体的应用或空间 id（§4.1）。

**引用完整性**：绑定指向的策略在同一个组合快照（§7.3）里找不到，就是**编译错误**：文档不能发布（发布者在写入前编译检查），已经在
Redis 里的文档编译失败时按 RFC 0003 §4.4 F4 处理（读到它的请求失败）。这与 RFC 0001 §3.3 一致，D2 在发布前就被拦住。

### 4.3 平台层与租户层

| 层 | 文档 | 内容 | 谁写（本阶段） | 对谁生效 |
|---|---|---|---|---|
| 平台 | `Platform` | 平台策略（含共享策略库）、`Anyone` 绑定（7.x 全局策略）、break-glass 绑定、内置的黑名单规则（编译时注入，§4.7） | 转换工具；`SnapshotPublisher` | 所有租户 |
| 平台（按应用） | `App(appId)` | 该应用的 `Catalog`、由应用权限转换来的权限策略、该应用的角色绑定（7.x 的角色授予） | 转换工具；`SnapshotPublisher` | 所有租户（角色绑定的 `tenant == null`，§5.2） |
| 租户 | `Tenant(tenantId)` | 该租户自己的策略与绑定 | 本阶段只有 `SnapshotPublisher`（以及 `--partition=by-owner` 的转换） | 只对该租户的主体 |

**边界**：

- 租户层的绑定只能指向本租户或平台层的策略；平台层的绑定只能指向平台层的策略。一个租户的数据因此不可能影响另一个租户的主体，
  这是"租户隔离"在模型里的落点（RFC 0001 §3.2）。编译时检查，违反即编译错误。
- 租户层不能削弱平台层：组合是 deny-first，平台层的 DENY 对每个租户都有效，租户层只能再加 DENY 或 ALLOW。平台层"必须包住租户层的
  ALLOW"（边界策略）在第 6 阶段。
- 平台租户 `(platform)` 自己也有一份 `Tenant((platform))` 文档，放平台管理员自己的策略。不要把它与平台层混淆：平台层对所有租户生效，
  平台租户的文档只对平台租户的主体生效。
- 主体评估时只读**自己租户**的租户文档。跨租户管理（平台管理员操作某个租户的资源）是第 6 阶段控制面的问题，本阶段不支持，与 7.x 相同：
  7.x 也只按主体自己的 `context.tenant` 评估。

### 4.4 附加：主体直接引用的策略

7.x 的 token 带 `policies` 声明，`SimpleAuthorization` 在主体层逐个读取。8.0 把它看成成员关系的一部分（§3 I2）：

- 声明不变：`policies: ["p1", "p2"]`，进入 `Memberships.attachments`，保持声明顺序（7.x 主体层按这个顺序评估，RFC 0002 §3.3）。
- **可附加的策略**：策略有一个 `attachable` 标志。主体只能附加本租户文档里或平台层里 `attachable == true` 的策略。转换工具把 7.x 的全部
  非全局策略放进平台层并标为可附加（§5.2），所以转换后的语义与 7.x 相同：任何主体都能附加任何策略。由应用权限转换来的权限策略
  不可附加：7.x 里权限 id 也不是策略 id，主体没法直接引用它们。
- 附加的策略作用域是 `(app = *, space = *)`，与 7.x 主体层相同（主体策略对任何应用、任何空间的请求都参与评估）。
- **缺失**：附加了找不到（或不可附加）的策略时，评估结果是 `Indeterminate(MissingPolicies)`，除非某条 DENY 已经命中（§6.1）。7.x 是
  到达主体层时抛 `MissingPolicyException`（D2）。差别只在"另一层有 DENY 命中"的情形，属于改动 C1b。
- **两层同名（PR-2 第一轮审查，2026-10-26）**：租户文档与平台层定义了同一个策略 id 时，附加和租户层绑定都不解析它，评估结果是
  `Indeterminate(AmbiguousPolicy)`，除非某条 DENY 已经命中（RFC 0007 §3.1 N2、Q15）。租户层优先会让租户定义一条同名策略，使 token 附加的
  平台 DENY 失效。在 PR-4 能比较编译结果之前，任何同名都按歧义处理。同一轮审查还让引擎在编译文档时拒绝：break-glass 绑定的主体不是平台
  租户的主体（§4.8）；租户文档绑定其他租户的主体或角色；应用文档的绑定作用于其他应用。这些绑定永远不会成为候选，写在那里的 DENY 会悄悄失效。

快照里的 `Principal` 绑定只用于数量很少、需要由策略管理员（而不是 token 签发方）控制的主体：break-glass root（§4.8）、服务账号。
转换工具只为 root 生成这种绑定。每个文档的主体绑定数设一个上限（默认 1,000，Q2），超过即编译错误，防止有人把用户级数据塞进快照。

### 4.5 成员关系

#### 4.5.1 来自 token 声明（默认）

按 RFC 0001 §7.1 的决定，默认来源是签名 token 里按作用域写的声明。7.x 的声明已经是按作用域写的（§3 I14），沿用：

| 声明 | 7.x 含义 | 8.0 `Memberships` |
|---|---|---|
| `roles: ["admin", "editor@team-a"]` | 裸角色只在默认空间生效；`role@space` 只在该空间生效；格式不对的（空角色、空空间、多于一个 `@`）哪里都不生效（`RoleScope`） | `roles = {"": [admin], "team-a": [editor]}`；格式不对的进 `malformed` |
| `policies: ["p1"]` | 主体层的策略 | `attachments = [p1]` |
| `tenantId` | 主体的租户 | `Subject.tenant` |
| （新增）`cosec.membership: "provider"` | — | 声明里没有成员关系，必须问 `MembershipProvider`（§4.5.2） |

**不需要重新签发 token**：解析规则与 `RoleScope.parse` 完全相同，同一份 token 在 7.x 与 8.0 中得到同样的角色作用域。这是选择沿用列表格式、
而不是 RFC 0001 示例里的对象格式的主要理由（Q12）。

**token 体积阈值**：CoSec 自己的签发方（[`JwtTokenConverter`](../../cosec-jwt/src/main/kotlin/me/ahoo/cosec/jwt/JwtTokenConverter.kt)）
在 `roles` 与 `policies` 序列化后超过 `cosec.jwt.membership-claim-limit`（默认 4 KB，RFC 0001 §7.1）时，不写这两个声明，改写
`cosec.membership: "provider"`。第三方签发方可以照做。

**撤销延迟**：等于 access token 的寿命。默认 10 分钟（[`JwtProperties`](../../cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/jwt/JwtProperties.kt)），
满足 RFC 0001 §7.1 的"不超过 15 分钟"。`wiki/integration/authentication.md` 和 `wiki/operations/configuration.md` 写明这个上限。

#### 4.5.2 `MembershipProvider`

```kotlin
/** SPI。成员关系变化频繁、需要即时撤销，或 token 装不下时使用。 */
interface MembershipProvider {
    /** 不阻塞，不同步抛出。主体不存在时以 Memberships(PROVIDER, emptyMap(), emptyList()) 完成；查询失败时异常完成。 */
    fun memberships(tenant: TenantId, principal: PrincipalId): CompletableFuture<Memberships>
}
```

- **何时调用**：只在 token 带 `cosec.membership: "provider"` 时调用；声明和 provider 不混用（一个主体的成员关系只有一个来源，否则撤销时
  要想清楚"哪个来源说了算"）。
- **失败即拒绝**：标记为 provider 而没有配置 provider，或 provider 失败、超时（`cosec.authorization.membership.timeout`，默认 500 ms），
  请求得到 `Indeterminate(MembershipUnavailable)` → 500（第 5 阶段 503）。不退回"没有角色"：没有角色只会让 ALLOW 不命中，但同样会让
  `inRole` DENY 不命中，那是放行方向。
- **缓存**：本阶段只提供 SPI 和一个内存实现（测试与本地开发）。基于 CoCache 的实现（按 `(tenant, principal)` 缓存，带 TTL，变更事件到达时
  失效，撤销延迟 = TTL）在第 5 阶段随 `cosec-store-redis` 一起提供（RFC 0001 §4）。理由：7.x 没有任何用户在用 provider，它不在门槛 A 的
  比较之内，先把契约定下来即可（Q14）。
- 返回 `CompletableFuture` 而不是 `suspend`，理由同 RFC 0003 Q10；第 5 阶段在 `cosec-runtime` 里加挂起包装。

#### 4.5.3 按主体吊销

增加 `TokenRevoker.revokeSubject(tenant, principal, issuedBefore = now)`：在吊销表里写一条 `(tenant, principal) → issuedBefore`，
`RevocableTokenVerifier` 拒绝 `iat < issuedBefore` 的 token。条目保留到 refresh token 的最长寿命（与现在按 token 吊销的 TTL 规则相同）。
吊销检查的降级行为不变（AGENTS.md 记录的两个例外之一）。这是 RFC 0001 §7.1"紧急撤销"依赖的手段（§3 I17，Q13）。

### 4.6 `ScopeResolver`

```kotlin
/** model/ 中的纯函数（§4.1 说明了为什么放在 model）。 */
object ScopeResolver {
    fun resolve(subject: Subject, declared: DeclaredScope, host: HostScope): ScopeResolution
}
class DeclaredScope(val app: String, val space: String)            // 来自 CoSec-App-Id / CoSec-Space-Id
class HostScope(val app: AppId?)                                   // 宿主配置的应用；Gateway 来自路由
sealed interface ScopeResolution {
    class Resolved(val scope: Scope) : ScopeResolution
    class Rejected(val reason: Reason) : ScopeResolution { enum class Reason { APP_MISMATCH, SPACE_NOT_MEMBER } }
}
```

规则：

| 维度 | 来源 | 规则 |
|---|---|---|
| 租户 | 凭证 | `subject.tenant`；匿名主体是默认租户 `(0)`。客户端无法声明，与 7.x 相同 |
| 应用 | 宿主优先 | 宿主配置了应用（`cosec.authorization.scope.app-id`，Gateway 由路由元数据 `cosec-app-id` 给出）时：声明为空或与之相同 → 宿主的应用，`appSource = HOST`；不同 → `Rejected(APP_MISMATCH)`。宿主没有配置时：用声明的值，`appSource = DECLARED`，与 7.x 相同 |
| 空间 | 声明，由成员关系确认 | 声明为空 → 默认空间。否则主体在该空间有至少一个角色（`memberships.roles[space]` 非空）→ 该空间；没有 → `Rejected(SPACE_NOT_MEMBER)` |

- **为什么应用默认仍取声明值**：7.x 的应用来自 Header，服务内嵌和网关部署都依赖它；改成必须配置会让每个现有部署在切换时失败。宿主配置
  是可选的加固：服务知道自己是哪个应用，网关知道路由属于哪个应用，客户端声明的值没有理由比它们更可信。`wiki/guide/security-model.md`
  的"信任边界"一节建议生产环境配置它（Q11）。配置后多出的差异（无 Header 的请求现在能得到该应用的角色授予）不在门槛 A 的数据里，
  因为回放按 7.x 的配置运行（§9.3）。
- **为什么声明了非成员空间要拒绝，而不是退回默认空间**：后端自己也读 `CoSec-Space-Id`。如果 CoSec 把请求当作默认空间评估、放行，后端
  却在客户端声明的空间里执行，就是一个绕过：主体用默认空间的角色操作了它不属于的空间。只有拒绝是安全的，所以不提供开关。这是改动 C3。
- 应用不要求成员关系：7.x 没有"应用成员"的概念，主体在应用里有什么权限完全由绑定决定。
- **检查顺序与匿名主体（PR-1，2026-10-25）**：先检查应用，再检查空间，所以既不一致又声明了非成员空间的请求总是 `APP_MISMATCH`。两者都是拒绝，
  只有原因不同。未认证的主体一律是默认租户 `(0)`，并且不看传入的成员关系（没有角色，见 §4.2）：7.x 的匿名上下文
  （`SimpleSecurityContext.anonymous()`、`SimpleTenantPrincipal.ANONYMOUS`）本来就在默认租户，所以与 7.x 相同。`ScopeResolverTest` 守护这两条。
- **声明的应用必须是合法的 id（第一轮审查，2026-10-26）**：非空的声明应用若为空白、为 `*`、含 `:`（`SnapshotKey.App` 的 key 分隔符）或超过 128 个字符，
  无论宿主是否配置了应用，都按 `APP_MISMATCH` 拒绝，而不是之后在读文档时变成 500。`ScopeSubject.tenant` 不能为空白（适配层把没有租户的凭证映射为
  `(0)`），`HostScope.app` 必须是合法的应用 id。
- `Rejected` 的映射：`Deny(StatementRef.ScopeRejected(reason))`，匿名主体 401、已认证主体 403，经 `SecurityPipeline` 现有的映射。
  2026-10-26 修订（RFC 0006 Q27）：响应体是 problem+json，匿名主体（包括带 `CoSec-Space-Id` 的匿名请求）得到 `unauthorized`（凭证被拒绝时是 `token-*`），
  已认证主体得到 `scope-rejected`；`APP_MISMATCH` 与 `SPACE_NOT_MEMBER` 的区别只进审计（RFC 0006 §6.1）。

### 4.7 黑名单：规则加名单

| 部分 | 7.x | 8.0（本阶段） |
|---|---|---|
| 名单 | 用户实现的 [`BlacklistChecker`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/blacklist/BlacklistChecker.kt)（`Mono<Boolean>`，CoSec 自身没有实现），在 `SimpleAuthorization` 里最先调用 | 不变：仍最先调用，结果写进 `environment.blocked`（RFC 0002 已经这样做）。第 5 阶段改为 `AttributeProvider`，并拆成主体、IP、设备三个集合 |
| 规则 | `SimpleAuthorization` 里的一行 `if`，先于 root | 平台层的一条 DENY 语句 `cosec:blacklist#0`：动作 `*`、条件"`environment.blocked` 为真"。**编译时注入**每个 `Platform` 组合，不存进 Redis 的文档，所以不可能被误删；排在所有候选语句的最前面（§6.3） |
| 出错 | 检查器的错误信号 → 500 | 检查器失败时 `environment.blocked` 为"未知"，这条 DENY 的匹配结果是 `ERROR`：没有别的 DENY 命中就是 `Indeterminate` → 500；有别的 DENY 命中就是 403（改动 C1b） |

被拉黑的请求在 7.x 中不读任何策略。8.0 中这条 DENY 必然命中，而 DENY 命中优先于不可用的输入（§6.1），所以 Pipeline 对被拉黑的请求只需要
常驻的 `Platform` 文档，不加载 `App`、`Tenant`：I/O 与 7.x 相同，租户文档读失败也不影响结果。

### 4.8 root：break-glass 绑定

7.0 的 root 由 `RootPrincipalMatcher` 判定（默认 `NONE`，`cosec.authorization.root-enabled=true` 时只认平台租户里 id 等于 `cosec.root` 的主体），
被判定为 root 的主体跳过全部策略，但不跳过黑名单。8.0：

- 平台层内置一条策略 `cosec:root`：一条 ALLOW 语句，动作 `*`，条件恒真。
- root 是一条 `Principal((platform), <id>) → cosec:root` 的绑定，`breakGlass = true`，**必须**有 `expiresAt`。转换工具在 7.x 部署开启了
  root 时生成它，过期时间由转换参数 `--root-expires-at` 给出，不给就拒绝转换（§8.4）。之后由 `SnapshotPublisher.setBinding` 续期或删除。
- 它受全部 DENY 约束（§3 I8，改动 C2）。理由：deny-first 因此对所有主体无条件成立，性质测试 P1 不再需要排除 root（RFC 0002 §8.4 P1
  现在要排除它）；一条命中的 DENY 是策略作者写下的明确意图，break-glass 用来绕过缺失的 ALLOW，不是绕过 DENY。真正需要绕过某条 DENY
  时，平台管理员改那条 DENY，修改本身留下记录。
- `RootPrincipalMatcher`、`cosec.authorization.root-enabled`、`CoSecPrincipal.ROOT_ID`/`isRoot`、`cosec.root` 系统属性在 PR-8 删除。
- 使用 root 绑定做出的决策：本阶段写一条 WARN 日志，审计事件的 `source` 指向这条绑定；第 5 阶段改为 `REQUIRED` 审计（§3 I9）。

### 4.9 `Catalog`

7.x 的 `AppPermission` 同时承担两件事：权限目录（分组、名称、描述，供 UI 和生成器使用）和评估（应用条件、权限语句）。8.0 拆开：

- `Catalog(appId)`：分组、权限 id、名称、描述、`spaced` 标志。不参与评估（`PermissionGroup.spaced` 在 7.x 里本来就不参与评估）。
- 评估的部分变成 `App(appId)` 文档里的权限策略（§5.1）。

---

## 5. 7.x 到 8.0 的映射

### 5.1 逐个构造

| 7.x 构造 | 8.0 | 语义 |
|---|---|---|
| `PolicyType.GLOBAL` 的策略（在 `GlobalPolicyIndexCache` 中，见 RFC 0003 §3.1） | `Platform` 文档中的策略 + 一条 `Anyone → policy` 绑定，作用域 `(app = *, space = *)` | 相同：对所有租户、所有应用、所有空间、包括匿名主体生效 |
| `PolicyType.SYSTEM`、`CUSTOM` 的策略 | `Platform` 文档中 `attachable = true` 的策略，元数据 `managedBy = PLATFORM` / `TENANT` 保留原类型 | 相同：7.x 的类型不参与评估 |
| `Policy.tenantId` | 元数据 `owner`，不参与评估；`--partition=by-owner` 时决定放进哪个租户文档 | 相同（默认） |
| `Policy.condition`（策略级条件） | 策略级条件，评估方式见 §6.1 的"前置条件" | 出错路径不同（C1c） |
| `principal.policies`（token 声明） | `Memberships.attachments`（§4.4） | 相同；缺失时的状态码见 C1b |
| `principal.roles`（token 声明，`role@space`） | `Memberships.roles`，按 `RoleScope.parse` 的规则解析（§4.5.1） | 相同 |
| `AppPermission(appId)` 的目录部分 | `App(appId)` 文档中的 `Catalog` | 不参与评估，相同 |
| `Permission`（应用权限中的一条） | `App(appId)` 中的权限策略 `perm:<appId>/<permissionId>`：一条语句（即这条权限），策略级条件 = 应用条件 | 相同（应用条件不命中 ⇔ 该应用的权限都不生效） |
| 授予 `*`（`AppRolePermission.ALL_PERMISSION_ID`） | 权限策略 `perm:<appId>/*`：按 `permissionIndexer` 的顺序包含全部权限语句，策略级条件 = 应用条件 | 相同，包括语句顺序（RFC 0002 S5） |
| `RolePermissionCache` 条目 `(app, role, space) → [permissionId...]` | `App(appId)` 中按授予顺序的绑定 `Role(null, role) → perm:<appId>/<permissionId>`，作用域 `(app = Exactly(app), space = Exactly(space))` | 相同：角色只在授予所在的应用和空间生效（D3，7.0）；对任何租户里同名的角色生效（7.x 语义，见 §5.2） |
| 授予了应用没有定义的权限 id（S10a，7.x 运行时 500） | 转换时**拒绝** | 见 C6 |
| 只有授予、没有应用权限的应用（7.x 不生效） | 不转换，报告 | 相同（都不生效） |
| `BlacklistChecker` | 名单来源不变；规则成为内置 DENY（§4.7） | 出错路径见 C1b |
| `RootPrincipalMatcher` + `root-enabled` | break-glass 绑定（§4.8） | C2 |
| `CoSec-App-Id`、`CoSec-Space-Id` | `ScopeResolver` 的声明输入（§4.6） | C3 |
| `inRole`、`inTenant`、各 part 条件、OGNL、SpEL、动作匹配器 | **本阶段不变**，原样作为语句的匹配器执行；翻译见 RFC 0005 | 相同 |
| 限流规则 | 不变（第 5 阶段成为义务） | 相同 |
| `PolicyVerifySource`、`AuditSourceType`（`ROOT`、`BLACKLIST`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、`ROLE_PERMISSION`） | `StatementRef`：`Policy(policyId, index)` + 经过的绑定或附加 | 非决策差异 X1 |

### 5.2 为什么转换后的数据全部放在平台层

可选的做法有三种（Q5）：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A. 全部放进平台层（已决定，默认）** | 全局策略、全部非全局策略（可附加）、全部应用文档都属于平台层；租户文档为空 | 语义与 7.x 相同：任何主体能附加任何策略，角色授予对所有租户里的同名角色生效。门槛 A 的差异只来自 C1–C3。缺点是"同名角色跨租户"这个 7.x 的风险原样保留（§5.4） |
| B. 按 `Policy.tenantId` 放进租户文档（`--partition=by-owner`） | 非全局策略按 `tenantId` 分到租户文档 | 改变语义：主体不能再附加别的租户的策略。7.x 的生成器（[`OpenAPIPolicyGenerator`](../../cosec-openapi/src/main/kotlin/me/ahoo/cosec/openapi/generator/OpenAPIPolicyGenerator.kt)）总是写默认租户，`tenantId` 多半不反映真实归属，按它分区会让大量附加失效（500）。作为可选项提供，转换工具报告每个租户的策略数和跨租户附加的样本数（需要请求样本） |
| C. 角色授予复制到每个租户 | 每个租户一份应用文档 | 规模乘以租户数；转换工具不知道有哪些租户；新租户没有授予 |

A 不是终点：第 6 阶段控制面让租户写自己的数据，运维可以逐步把共享数据收窄。本阶段交付的是**机制**：租户文档、租户隔离的编译检查、
按租户的 LRU 和陈旧度。

### 5.3 语义改动目录（门槛 A）

> **2026-10-26**：门槛 A 取消（[RFC 0008](0008-conformance-and-direct-build.md)）。下表不再用于给回放差异分类；每一项是 8.0 的一条规则，登记在一致性套件中（例如 C1a → `ALG-4`、
> C1b → `ALG-1`、C2 → `BRK-1`、C3 → `SCP-1`、C4 → `SCP-2`、C5 → `BND-2`，RFC 0008 §3.2），并写进 `upgrading.md`（RFC 0008 §4.4）。

下面是本阶段**有意**改变的全部决策语义。门槛（§9）要求每一条回放差异都归入其中某一类，并带有该类要求的证据。

| # | 改动 | 7.x | 8.0 | 方向 | 证据（候选 trace 必须给出） | 理由 |
|---|---|---|---|---|---|---|
| C1a | **ALLOW 出错按未命中处理**（RFC 0002 E1、E4） | 任何一层的 ALLOW 语句或其前置条件抛错 → 500 | 跳过它：别的 ALLOW 命中则 Permit，否则隐式拒绝（401/403） | E1 **放宽**（500 → 放行）；E4 只改状态码 | 基准的异常来自一条 ALLOW 语句或只含 ALLOW 的策略的前置条件 `S`；候选 trace 中 `S` 为 `ERROR`、`afterDecision = false`；所有 DENY 候选都是 `NO_MATCH`；没有缺失和不可用的输入 | deny-first 下 ALLOW 出错按未命中只会更严（RFC 0001 §3.3）；一条出错的 ALLOW 不应让整个请求失败 |
| C1b | **DENY 命中优先于别处的错误、缺失和不可用**（RFC 0002 E2、§3 I6） | 层顺序在前的错误、缺失（D2）、读取失败先发生 → 500 | 只要有一条 DENY 命中 → 403/401 | 只改状态码（都拒绝） | 候选决策为 `Deny(s)`；基准的错误发生在按 7.x 层顺序排在 `s` 之前的位置 | 拒绝已经确定，"无法判定"不再成立；决策与候选顺序无关 |
| C1c | **前置条件只在需要时评估**（RFC 0002 E5、S4） | 每层先评估该层全部策略的前置条件，任何一个抛错 → 500 | 前置条件在它的第一条候选语句被访问时评估；出错时它的 DENY 语句算 `ERROR`、ALLOW 语句算未命中 | 只改状态码，或按 C1a 放宽 | 基准的异常来自策略 `P` 的前置条件；候选 trace 中 `P` 的前置条件为 `ERROR` 或未评估（决策在访问 `P` 之前已确定） | 前置条件只是语句的一个合取项，按语句处理就不需要"每层先评估全部"这个特殊规则 |
| C1d | **黑名单和 root 不再短路平台文档的读取** | 被拉黑或 root 时不读任何策略 | 仍需常驻的 `Platform` 文档（以及 root 的平台租户文档） | 只改状态码（`Platform` 不可用时 403/放行 → 500） | 候选原因为 `SourceUnavailable(Platform)` 或 root 的租户文档不可用 | 黑名单规则和 root 本身都在平台层；`Platform` 启动时预热、常驻不淘汰（RFC 0003 §4.3），实际只在实例未就绪时发生 |
| C2 | **root 受全部 DENY 约束**（§4.8） | root 跳过全部策略 → 放行 | 命中的 DENY 拒绝 root | **收紧** | 主体匹配 break-glass 绑定；候选决策为 `Deny(s)`，`s` 不是黑名单 | deny-first 对所有主体成立 |
| C3 | **声明的空间必须由成员关系确认**（§4.6） | 按声明的空间评估：角色层可能为空，策略照常评估，条件读到声明的 `request.spaceId` | `Rejected(SPACE_NOT_MEMBER)` → 403/401 | **收紧**（可能把原来的放行变成拒绝） | 候选决策为 `Deny(ScopeRejected(SPACE_NOT_MEMBER))`；样本的成员关系里没有该空间 | 客户端只能在凭证给出的作用域里选择（P4），否则后端会在主体不属于的空间里执行请求 |

**不在目录里、因此必须为零差异的**：附加与缺失策略（除 C1b 外）、角色作用域、全局策略、应用条件、权限语句顺序、做出决策的语句（没有错误时，
§6.3）、任何匹配器的行为。

**C4–C6 不产生回放差异，但仍是语义改动**，写进 `upgrading.md`：

| # | 改动 | 为什么回放看不到 |
|---|---|---|
| C4 | 宿主配置了应用后，客户端声明的应用不再生效（不一致即拒绝，没有声明时用宿主的应用） | 回放按 7.x 的配置运行（宿主不配置应用） |
| C5 | 主体只能附加本租户或平台层中可附加的策略 | 默认的方案 A 把全部策略放进平台层并标为可附加 |
| C6 | 授予了不存在的权限 id 的数据在转换时被拒绝（7.x 是到达时 500） | 运维修正数据之后才能转换，回放的两边读的是修正后的同一份数据（§9.3） |

### 5.4 保留下来的 7.x 风险

方案 A 原样保留了两个 7.x 的行为，它们不是本阶段引入的，但在新模型里变得显眼，`wiki/guide/security-model.md` 的"租户隔离"一节要写清楚：

- **同名角色跨租户**：平台层的角色绑定 `Role(null, admin)` 对任何租户里叫 `admin` 的角色生效。7.x 的角色授予本来就不区分租户。
  需要区分时，运维把授予改写进租户文档（第 6 阶段之后由控制面完成）。
- **任何主体可以附加任何共享策略**：token 签发方决定主体附加哪些策略，与 7.x 相同。

---

## 6. 引擎

### 6.1 扁平组合算法

```
evaluate(req, snap, level):
    applicable = snap.bindingsFor(req.subject, req.scope, req.environment.time)   // §6.4，按 §6.3 的顺序
    unresolved = snap.unresolved(req)        // 缺失的附加、不可用的文档；不含已经确定"不需要"的文档
    // DENY 扫描：扫完全部 DENY，任一命中即拒绝
    firstError = null
    for (s, via) in candidates(applicable) where s.effect == DENY:
        when matchWithGuard(s, req):
            MATCH    -> return Deny(ref(s, via))
            ERROR    -> firstError = firstError ?: ref(s, via)
            NO_MATCH -> continue
    if firstError != null: return Indeterminate(MatcherFailed(firstError))
    if unresolved != ∅:    return Indeterminate(unresolved.cause)   // 无法确认那里没有 DENY
    // ALLOW 扫描：所有 DENY 都已确认不适用
    for (s, via) in candidates(applicable) where s.effect == ALLOW:
        when matchWithGuard(s, req):
            MATCH    -> return withinBoundary(Permit(ref(s, via)))   // 第 6 阶段之前恒为通过
            ERROR    -> continue                                     // 记入 trace，结果只会更严
            NO_MATCH -> continue
    return Deny(null)

matchWithGuard(s, req):
    g = s.policy.guard                        // 策略级条件；每次评估每个策略至多评估一次，结果记在位图里
    if g 未评估: g.result = try g.matches(req) catch -> ERROR
    if g.result == ERROR: return ERROR
    if g.result == NO_MATCH: return NO_MATCH
    return try s.match(req) catch -> ERROR    // 7.x 匹配器经 RFC 0002 PR-3 的适配层，抛出的 Exception 变成 ERROR
```

- **黑名单与作用域**：`ScopeResolver` 在引擎之前运行，`Rejected` 不进入引擎（§4.6）。黑名单是候选集的第一条 DENY（§4.7）。
- **`unresolved` 的位置**：在 DENY 扫描之后检查，所以一条命中的 DENY 胜过缺失和不可用（C1b）；在 ALLOW 扫描之前检查，所以缺失和不可用
  永远不会产生 Permit（失败即拒绝）。
- **`Error` 的处理**不变（RFC 0002 §3.2）：只捕获 `Exception`；`Error` 照常传播。
- **一个策略的前置条件**只评估一次，并且只在它的某条语句成为候选时评估。本阶段没有索引，适用绑定的全部语句都是候选，所以实际上
  适用策略的前置条件都会被评估，除非 DENY 扫描提前返回。RFC 0005 有了索引之后，没有候选语句的策略不评估前置条件（C1c）。

### 6.2 与层顺序的差别

RFC 0002 §4.3 证明了：没有错误的输入上，层顺序与扁平顺序结果相同，做出决策的语句也相同（都是按（层，组，规则）顺序的第一条）。
§6.3 的候选顺序让这个论证在绑定模型里继续成立。出错路径上的差别就是 §5.3 的 C1a–C1d，与 RFC 0002 §4.3 的 E1–E5 对应：

| RFC 0002 | 本文 | 说明 |
|---|---|---|
| E1 | C1a | 唯一的放宽 |
| E2 | C1b | |
| E3（全局 DENY 命中，主体策略缺失） | **不再是差异** | RFC 0002 写的扁平算法在评估前处理缺失，所以是 500；本文的算法让命中的 DENY 优先，结果与 7.x 相同（403） |
| E4 | C1a | |
| E5 | C1c | |

### 6.3 候选顺序

候选语句按下面的键排序。顺序**不影响决策**（扁平算法对 DENY 和 ALLOW 都只问"有没有命中"），只决定"多条同时命中时记哪一条"，
也就是 trace、`VerifyContext` 和审计事件的 `source`：

| 次序 | 来源 | 7.x 对应 | 内部顺序 |
|---|---|---|---|
| 0 | 内置黑名单规则 | 黑名单检查 | — |
| 1 | 平台层 `Anyone` 绑定 | 全局层 | 按策略 id（RFC 0003 Q13） |
| 2 | 租户层 `Anyone` 绑定 | （无） | 按策略 id |
| 3 | 附加的策略，以及快照里的 `Principal` 绑定（root 除外） | 主体层 | 附加按声明顺序；绑定按绑定 id |
| 4 | 角色绑定：平台层的 `App(appId)`，再到租户层 | 角色层 | 按 `scope.roles` 的顺序（声明顺序），同一角色内按授予顺序 |
| 5 | break-glass 绑定 | root | 按绑定 id |

2026-10-26 修订（#1070 第 1 轮审查 A2）：break-glass 绑定从第 1 位移到最后。它绕过的是"缺少的 ALLOW"（§4.8），所以只在别的 ALLOW 都不命中时才被记为放行的来源；
放在前面时，一个普通绑定也放行的请求会被记成 break-glass 的使用，没有 REQUIRED 监听器时变成 503（RFC 0006 §9.4.3），有时则写下一条假的 break-glass 审计。
与 7.x 的 root 先于各层不同，这只改变"记哪一条"，不改变决策。

每个策略内按语句下标。于是没有错误时，第一条命中的 DENY（或在没有 DENY 命中时第一条命中的 ALLOW）与 7.x 按（层，组，规则）顺序的第一条
是同一条，经来源映射（§8.2）后门槛直接比较。

### 6.4 `evaluate(request, snapshot)` 与适用性查找

```kotlin
interface Engine {
    fun evaluate(request: AuthzRequest, snapshot: SnapshotView, trace: TraceLevel = TraceLevel.OFF): Evaluation
}

/** 一次评估持有的组合快照（§7.3）：三份已编译文档的不可变引用，外加本请求的 unresolved。构造它不复制任何集合。 */
class SnapshotView(val platform: CompiledPlatform, val app: CompiledApp?, val tenant: CompiledTenant, val unresolved: List<Cause>)
```

`RFC 0002` 的 `evaluate(request, PolicySet)` 在切换（PR-7）之前与它并存，PR-8 删除。

**适用性查找**（`bindingsFor`）在每份编译后的文档里是哈希查找，不扫描全部绑定：

| 索引 | 键 | 值 |
|---|---|---|
| `anyone` | — | 按 §6.3 排好序的 `Anyone` 绑定数组（编译时排序，请求期直接复用，零分配） |
| `byPrincipal` | `(tenant, principalId)` | 主体绑定 |
| `byRole` | `roleId` | 该角色的绑定数组，每个元素带 `(app, space)` 过滤条件 |
| `attachable` | `policyId` | 可附加的策略 |

角色绑定的查找：对 `scope.roles` 中的每个角色，在 `App(scope.app)` 和 `Tenant` 的 `byRole` 里各查一次，按作用域过滤。每个请求的工作量
与"主体的角色数 + 附加数 + 适用的绑定数"成正比，与快照里绑定和策略的总数无关。

**分配**：`OFF` 时，候选的遍历用访问者（与 RFC 0002 `RuleGroup.forEachRule` 相同的做法），不物化候选列表；策略前置条件的结果用位图，
按"本请求涉及的策略数"分配（≤ 64 个时是一个 `Long` 局部变量）。目标见 §12。

### 6.5 trace

RFC 0002 的 `Trace`（按层的 `LayerTrace`）在 PR-8 随层一起删除，换成按候选的 trace：

```kotlin
class Trace(val scope: ScopeTrace, val candidates: List<CandidateTrace>, val unresolved: List<Cause>)
class CandidateTrace(val ref: StatementRef, val via: BindingRef, val effect: Effect, val outcome: RuleOutcome, val error: Throwable?, val afterDecision: Boolean)
sealed interface BindingRef { data class Bound(val id: BindingId, val layer: Layer) : BindingRef; data class Attached(val policy: PolicyId) : BindingRef; data object Builtin : BindingRef }
```

`Explanation`（`CoSecExplainEndpoint` 的 JSON）在 PR-7 改为按绑定组织：每条候选语句说明它经哪条绑定或附加进入、在哪个文档、结果如何。
这是 explain 的破坏性变更（X3）。`FULL`/`EXHAUSTIVE` 的含义不变：决策之后继续评估剩余候选，记为 `afterDecision`；性质测试 P2 不变。

---

## 7. 快照：文档、版本与加载

### 7.1 文档

```kotlin
sealed interface SnapshotKey {
    data object Platform : SnapshotKey                   // 常驻，启动时预热，不被淘汰
    data class App(val appId: AppId) : SnapshotKey       // 按需加载，LRU
    data class Tenant(val tenantId: TenantId) : SnapshotKey   // 按需加载，LRU
}
```

RFC 0003 的 `Policies`、`App(appId)` 在 PR-7 被替换：`Policies` 的内容并入 `Platform`；`App` 的 key 不变，内容从"应用权限 + 授予"变成
"目录 + 权限策略 + 角色绑定"（§4.3）。Redis key：`<p>:snapshot:platform`、`<p>:snapshot:app:<appId>`、`<p>:snapshot:tenant:<tenantId>`。
`tenantId` 的编码规则与 `appId` 相同（RFC 0003 §4.1：显式编码、拒绝含 `:` 的 id），`(platform)`、`(0)` 这类带括号的 id 原样编码。

```jsonc
// SnapshotKey.Platform
{
  "schema": 2, "revision": 42, "publishedAt": "2026-11-01T08:00:00Z",
  "policies": [ { "id": "deny-ip", "attachable": false, "statements": [ ... ] }, ... ],   // 7.x 策略格式（RFC 0005 切换后改为 AST）
  "bindings": [
    { "id": "g:deny-ip", "subject": "*", "app": "*", "space": "*", "policy": "deny-ip" },
    { "id": "root", "subject": { "principal": "cosec", "tenant": "(platform)" }, "app": "*", "space": "*",
      "policy": "cosec:root", "breakGlass": true, "expiresAt": "2026-12-31T00:00:00Z" }
  ]
}
// SnapshotKey.App("order-service")
{
  "schema": 2, "revision": 7, "publishedAt": "...",
  "catalog": { "groups": [ { "name": "order", "permissions": [ { "id": "order.read", "name": "...", "description": "..." } ] } ] },
  "policies": [ { "id": "perm:order-service/order.read", "condition": { ... 应用条件 ... }, "statements": [ ... ] }, ... ],
  "bindings": [ { "id": "r:editor@team-a:0", "subject": { "role": "editor" }, "app": "order-service", "space": "team-a", "policy": "perm:order-service/order.read" } ]
}
// SnapshotKey.Tenant("acme")：结构同 Platform，没有 breakGlass
```

`schema` 从 RFC 0003 的 `1` 升为 `2`。`SnapshotDocumentCodec` 只认识 `2`，读到 `1` 是 `SnapshotFormatException`（RFC 0003 F3），即 500：
`main` 上第 3 阶段格式的开发部署必须重新转换（§8.5）。

### 7.2 版本

```kotlin
/** 一次评估读到的三份文档各是哪个修订。不需要或不存在的文档为 0。第 5 阶段写进 DecisionEvent。 */
data class SnapshotVersion(val platform: Long, val app: Long, val tenant: Long)
```

三份文档之间的引用只有一个方向：`App` 与 `Tenant` 的绑定可以指向 `Platform` 的策略；`Tenant` 的绑定可以指向 `App` 的权限策略吗？**不可以**
（编译检查）：权限策略只经同一个 `App` 文档里的角色绑定生效，否则 `App` 文档删除一个权限就会让某个租户文档的引用悬空，而两份文档分别带版本读取。

`App`、`Tenant` 指向 `Platform` 的引用跨文档，存在版本竞态：平台删掉一条共享策略，租户文档还引用它。处理与 RFC 0003 §4.8 分片方案相同：
悬空的引用表现为 `unresolved`（`MissingPolicies`），失败即拒绝，不会放行；发布者删除平台策略之前检查是否仍被已发布的 `App` 文档引用（`Tenant` 文档的
引用在第 6 阶段控制面检查，本阶段写进 `storage.md` 的发布注意事项）。

### 7.3 组合，而不是合并

RFC 0001 §3.2 要求"一次评估只读一份快照"（P7）。本文的 `SnapshotView` 满足它：三个引用都指向已安装的、不可变的编译结果，在构造
`SnapshotView` 的那一刻取定，评估期间不变。与"合并编译成一份"的区别只在实现：

| | 合并（RFC 0001 原文） | 组合（本文） |
|---|---|---|
| 平台层更新 | 重新编译每个已加载的租户 | 只编译 `Platform` |
| 内存 | 每个租户一份含平台层的副本 | 平台层一份 |
| 查找 | 一个索引 | 三个索引各查一次（§6.4），常数倍开销 |
| 一致性 | 一个版本号 | 三元组（§7.2） |

查找多两次哈希，换来平台发布时不需要 O(租户数) 的重新编译。JMH 的 `tenant` 场景（§12）测量这笔开销。

### 7.4 加载与写入：复用 RFC 0003

RFC 0003 的 `SnapshotSource`（单飞、有序安装、条件读重新校验、落后时长、LRU、预热、就绪、指标）、`SnapshotDocumentStore`、
`PolicySnapshotCache` 声明、失效监听、`SnapshotPublisher` 的条件写入，全部**原样复用**，只换 key 和文档类型（RFC 0003 §3.7 的承诺）。
新增的只有：

| 项 | 内容 |
|---|---|
| 一次评估需要的文档 | 被拉黑：`Platform`。否则：`Platform` + `Tenant(subject.tenant)` + （`scope.roles` 非空且 `scope.app` 非空时）`App(scope.app)`。与 RFC 0003 §3.2 的规则同构 |
| LRU | `App` 与 `Tenant` 各自一个上限（默认各 1,024，Q15，用 RFC 0003 §4.8 的测量工具校准） |
| 预热 | `Platform`，以及 `prewarm-apps`、新增的 `prewarm-tenants` |
| `fail-closed` | RFC 0003 §4.5 的"按租户"在本阶段有了真正的租户文档：判断依据仍是请求的租户和本次评估用到的文档的最大落后时长 |
| `SnapshotPublisher` | 增加 `setBinding(key, binding)`、`removeBinding(key, id)`；`setRoleGrants` 改为写 `App` 文档里的角色绑定（签名不变，过渡到第 6 阶段）；`setPolicy` 增加目标文档参数（`Platform` 或 `Tenant`） |
| 发布前编译 | 发布者在条件写入之前编译整个组合（新文档 + 当前的 `Platform`），编译错误（悬空引用、跨租户引用、主体绑定超限、break-glass 缺少过期时间）直接拒绝写入 |
| 成员关系 | 不进快照；`MembershipProvider` 的结果不经 `SnapshotSource` |

---

## 8. 一次性数据转换工具 `cosec-migrate`

### 8.1 定位

- 新模块 `migrate/`，构件 `cosec-migrate`（目录短名、构件保留前缀，与 `model/`、`engine/` 一致）。它是用户在升级时运行的工具，所以
  **发布**（不同于 `cosec-gateway-server`），形式是一个带 `main` 的 jar，也可以作为库调用（Q16）。
- 依赖 `cosec-core`、`cosec-cocache`（读取 7.x Redis 数据用 RFC 0003 的 `CoSec7xReader`），不依赖 Spring。
- 不进入任何应用的运行时类路径：starter 不依赖它。

### 8.2 输入与输出

```bash
java -jar cosec-migrate.jar convert --from-redis redis://localhost:6379 --key-prefix cosec --out ./converted [--root-expires-at 2026-12-31T00:00:00Z] [--partition by-owner]
java -jar cosec-migrate.jar convert --from-files ./export --out ./converted
java -jar cosec-migrate.jar publish --to-redis redis://localhost:6379 --key-prefix cosec --in ./converted
java -jar cosec-migrate.jar verify --in ./converted --requests ./audit.jsonl [--report ./verify-report.json]
```

| 输入 | 内容 |
|---|---|
| `--from-redis` | 7.x 布局的 Redis（schema 标记 `"7"`），经 `CoSec7xReader` 读取：策略、全局索引、应用权限、角色授予（RFC 0003 §4.7） |
| `--from-files` | RFC 0002 §8.2.1 的导出格式：`policies.json`、`app-role-permissions.json`，另加一个 `global-policies.json`（全局索引里的 id）。维护者的回放数据就是这个格式 |
| `--root-expires-at`、`--root-id` | 7.x 部署开启了 root 时必需（§8.4） |
| `--partition` | `platform`（默认，§5.2 方案 A）或 `by-owner` |
| 匹配器工厂 | 自定义动作 / 条件匹配器的 jar 放在类路径上（`-cp`），转换工具用与运行时相同的 `MatcherFactoryRegistry` 解析；本阶段只校验能解析，不翻译 |

| 输出（`--out` 目录） | 内容 |
|---|---|
| `platform.json`、`apps/<appId>.json`、`tenants/<tenantId>.json` | §7.1 的文档，`revision = 1` |
| `provenance.json` | 来源映射：每个 8.0 的 `(policyId, statementIndex)` 和每条绑定对应的 7.x 位置（`GLOBAL`/`PRINCIPAL` 策略 id 与下标，或 `(appId, roleId, spaceId, permissionId)`）。门槛和 `verify` 用它比较"做出决策的规则" |
| `report.json` + 控制台摘要 | 每类对象的数量和字节数；拒绝项（§8.4）及其位置；警告项；输入与输出文件的 SHA-256 |

`publish` 用 `SnapshotPublisher` 的条件写入（`expected = null`，即"仅当不存在"）写入文档，然后写 schema 标记；已经存在 8.0 文档时失败，不覆盖。
它不删除 7.x 的 key，回滚就是不切换（`upgrading.md` 的回滚一节）。

### 8.3 转换规则

即 §5.1 的表。要点：

- 全局策略 = 全局索引中、类型为 `GLOBAL` 的策略（与 `RedisPolicyRepository.globalPoliciesOf` 相同）。
- 策略 id、权限 id、角色 id 原样保留；生成的 id 带保留前缀：`perm:`、`g:`、`r:`、`cosec:`。7.x 数据里出现以保留前缀开头的策略 id 时拒绝（§8.4）。
- 权限策略按需生成：只为被授予的权限和被 `*` 授予的应用生成，未被任何角色授予的权限只进 `Catalog`。
- 角色绑定按 7.x 授予的顺序编号（`r:<role>[@<space>]:<n>`），候选顺序由编号决定（§6.3）。
- 策略与语句的匹配器 JSON 原样搬运（本阶段），RFC 0005 加上翻译。

### 8.4 拒绝什么

原则：7.x 中会让请求失败、或语义不确定的数据，**拒绝并报告位置**，由运维修正后重跑；7.x 中本来就不生效的数据，**丢弃并警告**。
转换工具不替运维决定语义。

| 情形 | 7.x 的行为 | 处理 |
|---|---|---|
| 全局索引里有 id 却没有内容 | 每个请求 500（D4 的读侧，RFC 0003 X5） | 拒绝 |
| 类型为 `GLOBAL` 却不在索引里 | 不当作全局策略 | 拒绝（意图不明：是忘了加进索引，还是类型写错） |
| 授予了应用没有定义的权限 id | 拒绝扫描到达时 500（S10a） | 拒绝（C6） |
| 只有授予、没有应用权限 | 不生效 | 丢弃，警告 |
| 策略或应用权限无法解码、匹配器类型未注册 | 读到它的请求 500 | 拒绝 |
| 策略 id 以保留前缀开头 | — | 拒绝 |
| `appId` 含 `:`、角色 id 含 `@`（`AppSpacedRoleId` 的约束） | 7.0 已不可能写入 | 拒绝 |
| 开启了 root 但没有给 `--root-expires-at` | — | 拒绝（§4.8） |
| `--partition by-owner` 时，策略的 `tenantId` 为空或是不存在的租户格式 | — | 拒绝 |
| schema 标记不是 `"7"` | — | 拒绝（6.x 数据先按 7.0 的步骤迁移） |

### 8.5 schema 标记

`CoSecSchemaMarker` 在 RFC 0003 PR-5 升为 `"8"`（第 3 阶段的文档布局）。本阶段的布局再次变化，标记改为 `"8.4"`（"8.0 线、第 4 阶段的布局"），
8.0 启动时只接受它（Q17）。`main` 上读到 `"8"` 的开发部署启动失败，提示用 `cosec-migrate` 重新从 7.x 数据转换，或者用 RFC 0003 的
`snapshotConvert` 先回到第 3 阶段格式再转换。GA 前如果布局还有变化（RFC 0005 切换），标记再变；GA 时定为最终值。

### 8.6 与 RFC 0005 的关系

GA 版本的 `convert` 一次完成两篇的转换：先按本文转换模型，再按 RFC 0005 §7 把每条语句的匹配器翻译成 AST，翻译不了的按 RFC 0005 的规则拒绝。
用户不会见到中间格式。

> **2026-10-26 实现说明（PR-5，#1067）**：
> - **翻译的范围。** PR-5 只翻译动作（§5.2 的 `http` 命名空间：`all`、`path` 模式及其方法与选项、`composite`）与 `all` 条件。其余条件按 RFC 0005 §7.1 由 RFC 0005 PR-6 翻译；在那之前转换时拒绝并报告位置（`CONDITION_NOT_TRANSLATED`）。`spel`、`ognl`（`EXPRESSION_CONDITION`）与自定义条件（`CUSTOM_CONDITION`，RFC 0008 Q4）在任何版本都拒绝。路径模板与非 `/` 的分隔符同样留给 PR-6。
> - **解码。** 每个 7.x 策略与应用权限先用 7.x 的反序列化器解码，7.x 读不了的数据拒绝（`UNDECODABLE`），不会被转换成会放行的语句。
> - **报告。** `report.json` 的 `semanticChanges` 列出受 C1a–C6、D1–D3 影响的每一处 7.x 配置形态（RFC 0008 Q3）。
> - **root。** 用 `--root-enabled`、`--root-id` 声明 root 已开启，必须同时给出 `--root-expires-at`。
> - **`--from-files`。** 读 `policies.json`、`global-policies.json`、`app-role-permissions.json`。
> - **`publish`。** 每个文档都是"仅当不存在"的条件写入，已有任何 8.0 文档时什么也不写。它不写 schema 标记：标记属于切换 S1，写了会让仍在运行的 7.x 实例重启失败。
> - **命令行。** 参数解析不引入新依赖。
> - **类路径。** 7.x 的 `path` 匹配器依赖 `spring-web`，`cosec-migrate` 把它声明为运行时依赖（维护者 2026-10-26 批准）；类路径缺少某个匹配器的类时，用到它的条目以"类路径缺少类"为由拒绝。

### 8.7 用户怎么验证

> **2026-10-26**：`verify` 在 8.0.0 中不提供（[RFC 0008](0008-conformance-and-direct-build.md) §6 Q3）：它依赖只为门槛 A 存在的分类器。`convert` 的报告列出受语义改动影响的 7.x 配置形态。以下原文保留，仅供追溯。

维护者的门槛（§9）能用旧实现做基准，因为在切换之前旧实现还在 `main` 上。用户升级时旧实现已经不在 8.0 里了，所以 `verify` 以**用户自己的
7.x 审计日志中记录的决策**为基准：

1. 在 7.x 上冻结策略写入（或者只取最后一次策略变更之后的审计事件），运行 `convert`。
2. 导出这段时间的审计事件（`AuditEvent`，Kafka 或日志 sink，字段见 RFC 0002 §8.2.1），每行一个 JSON。
3. 运行 `verify`：用 8.0 的引擎在转换后的文档上评估每个请求，与审计事件记录的 `decision`、`reasonCode`、`source` 比较，`source` 经 `provenance.json`
   映射。差异用门槛的同一个分类器（§9.2）归类。
4. 报告：样本数、时间跨度、一致数、按 C1–C3 分类的差异数、未归类的差异数和明细（写在本地 `verify-mismatches.jsonl`）。**未归类的差异必须为零**才建议切换；
   C1a 的每一条都单独列出。

局限，写进 `upgrading.md`：审计事件不含请求头、请求属性和主体属性（RFC 0002 §8.2.1 的表），读取这些输入的条件在 `verify` 里只走"值为空"的一支，
报告列出这些策略；审计事件记录的是 7.x 执法时的策略版本，策略在采样期间变过就会出现与转换无关的差异，所以第 1 步要冻结或截取。

---

## 9. 门槛

> **2026-10-26：本节整体由 [RFC 0008](0008-conformance-and-direct-build.md) 取代。** §9.5 的 P1′、P8–P12 作为规范性质保留（已在 `engine/`、`model/` 中合入，RFC 0008 §3.5）。以下原文保留，仅供追溯。

### 9.1 要证明什么

本阶段有意改变语义，所以"差异为零"不再是标准。要证明的是 RFC 0001 §6 那句话的本意：**每一条决策差异都是有意的，并且已经列出**。拆成三条可检查的判据：

1. **完备**：回放中的每一条差异都被分类器归入 §5.3 的某一类；归不了类的差异数为 0。
2. **可信**：每一条归类都附带该类要求的证据（§5.3 的"证据"列），证据由候选的 trace 和基准的错误机械地检查，不靠人工判断。
3. **可审查**：让结果变宽的类别（只有 C1a）每一条样本都单独列出，维护者逐条确认；其余类别给出计数和抽样。

### 9.2 分类器

在 `DecisionParity`（RFC 0002 Q14 / RFC 0003 Q12 保留的通用比较器，已在 `cosec-core` 的测试夹具中，#997、#1002）上增加一层 `DifferenceClassifier`：

```kotlin
/** 一个改动类别。matches 只读两边的结果元组、候选的 trace 和样本，不读任何外部状态。 */
interface ChangeClass {
    val id: String                          // "C1a" ...
    val widens: Boolean                     // 是否可能把拒绝 / 出错变成放行
    fun matches(baseline: Outcome, candidate: Outcome, trace: Trace, sample: Sample): Evidence?
}
class Evidence(val classId: String, val statement: StatementRef?, val detail: String)
```

- **两边都开 trace**：基准用 RFC 0002 的 `TraceLevel.FULL`，候选用本文的 trace。分类器只读 trace，不重新评估。
- **结果元组**与 RFC 0002 §8.3 相同（状态、`AuthorizeResult.reason`、做出决策的规则、出错时的异常类名）。"做出决策的规则"经 `provenance.json` 把候选的
  `StatementRef` 映射回 7.x 的 `DecisionRef` 再比较；映射不到的就是差异。
- **一条差异至多归入一类**；能归入多类时取 `widens` 为真的那一类，否则取编号最小的。这避免一条放宽的差异借道"只改状态码"的类别通过。
- **每个类别都有反例测试**：对每一类构造一个**不**满足证据的差异（例如 C1a 的出错语句是 DENY），断言分类器不把它归入该类。分类器本身的错误只会让门槛更难通过，
  但过宽的分类器会让门槛失效，所以反例测试与正例同等重要。
- **合成语料**：对每一类至少一个能触发它的固定用例（`C1a`：一条抛错的 ALLOW 加一条命中的 ALLOW；`C3`：声明非成员空间……），断言候选的行为与 §5.3 一致、
  分类器给出该类。真实数据不一定碰得到每一类，合成语料保证每一类都被测过。

### 9.3 语料与回放

| 来源 | 内容 | 何时跑 |
|---|---|---|
| 现有测试 | RFC 0003 §8.2 第一行的测试集，参数化为"第 3 阶段实现 / 本阶段实现"两边各跑一遍；预期不同的断言按类别标注（例如 root 的用例改为 C2 的预期），每一处在 PR 描述里列出 | 每次 `check`，直到 PR-8 |
| `PolicyTestKit` 套件 | `cosec-core/src/test/resources/cosec-policy-test/` 下的套件经转换后两边各跑一遍 | 每次 `check` |
| 合成语料 | §9.2 每一类的正例与反例 | 每次 `check` |
| 随机生成 | 复用 RFC 0002/0003 的生成器，另加：成员关系（含非成员空间、`malformed`）、break-glass 绑定（含过期）、按概率抛错的 DENY/ALLOW 和前置条件、缺失的附加、不可用的文档；数据经转换工具转换 | `check` 跑固定种子 2,000 例；`-PparityIterations=1000000` 手动跑百万例，PR-7 前必须跑过一次。**随机语料同样要求未归类差异为零** |
| 真实数据离线回放 | RFC 0002 §8.2.1 的同一批导出，不需要重新导出；基准是第 3 阶段实现在原始导出上，候选是本阶段实现在 `cosec-migrate convert --from-files` 的输出上 | PR-6 合入之后、PR-7 合入之前 |

**回放怎么装载两边**：基准沿用 RFC 0003 §8.2（原始导出 → 快照文档 → `SnapshotSource`）；候选先运行转换工具，再装进内存 `SnapshotDocumentStore`。转换工具因此
也在门槛之内：用户在升级时运行的就是同一段代码。回放的宿主配置与导出的部署相同（`replay.json`：root 配置、是否配置了宿主应用），默认不配置宿主应用（C4 不出现）。

**导出数据被转换工具拒绝时**：维护者先在导出数据上修正（例如删掉悬空的授予），两边读同一份修正后的数据，修正的内容按类型计数记入证据（不含明细）。

### 9.4 判定

- 合成语料、离线语料与随机用例：未归类差异为 0。
- 离线回放：每个部署至少 100 万条、至少 7 天，两边同样出错的样本不超过 1 %（即测试夹具中 `ReplayGate` 的默认值；RFC 0002 Q7 的部署与数据）：未归类差异为 0；`candidate_error` 为 0，或者每一条都对应基准同样出错的样本，
  或者归入 C1b/C1d；C1a 的样本逐条列在维护者本地的 `replay-widening.jsonl`，维护者逐条确认（确认的是"这条出错的 ALLOW 被跳过之后、另一条 ALLOW 放行
  是正确的"），确认结论登记在附录 A，只记类别、计数和语句的 id 哈希。
- 各类别的计数写进 PR-7 的门槛证据，只有计数和哈希（RFC 0002 §8.2.1 的数据处理规则不变）。
- 发现未归类差异：要么修候选（缺陷），要么证明是基准的缺陷并登记附录 A，要么**修订本 RFC 的改动目录**（新增一类、写明理由和证据，经维护者确认）后从零重跑。
  第三种情况必须修订 RFC，而不是只改分类器：改动目录是本阶段语义的唯一记录。

### 9.5 性质测试

在 RFC 0002 P1–P4、RFC 0003 P5–P7 之外：

- **P1′ 加入匹配的 DENY 绝不会得到 Permit**：对任意快照和请求，加入一条适用于该主体、必然命中的 DENY 绑定，结果为 `Deny`。**不再排除 root**（§4.8），
  也不再接受 `Indeterminate`：DENY 命中优先（§6.1）。
- **P8 决策与候选顺序无关**：随机打乱候选顺序，`Decision` 的类型（Permit / Deny / Indeterminate）不变；Permit 与 Deny 的 `by` 可能不同。
- **P9 缺失与不可用不会产生 Permit**：对任意快照，把任意一份文档换成不可用、或删除任意一个附加的策略，结果不会从 Deny/Indeterminate 变成 Permit。
- **P10 适用性查找与暴力扫描一致**：`bindingsFor` 的结果（作为集合）等于对快照中全部绑定逐条计算 `appliesTo` 的结果，顺序符合 §6.3。
- **P11 作用域只能收窄**：对任意主体和声明，`ScopeResolver` 的 `Resolved` 结果里 `scope.roles ⊆ memberships.roles[scope.space]`，并且声明的非空空间不在成员关系里时必为 `Rejected`。
- **P12 租户隔离**：对任意两个租户 T、U，修改 U 的租户文档不改变 T 的主体的任何决策（随机生成，比较修改前后）。

---

## 10. 不变量

| 不变量 | 本阶段如何成立 | 守护 |
|---|---|---|
| **Deny-first** | 单一扁平算法；DENY 命中优先于一切 | P1′（无 root 例外）、P8；`SimpleAuthorizationTest` 的用例作为语料 |
| **Fail closed** | 缺失的附加、不可用的文档、成员关系不可用、DENY 出错 → `Indeterminate`（没有 DENY 命中时）；作用域不确认 → 拒绝；悬空引用在发布前被拒绝。唯一的放宽 C1a 只在全部 DENY 都确认不命中之后发生 | P9；`MembershipFailClosedTest`；`ScopeResolverTest`；`SnapshotPublisherCompileTest` |
| **One decision path** | `ScopeRejected`、新的 `Indeterminate` 原因都经 `SecurityPipeline` 现有的映射（403/401/500） | `WebAdapterParityTest` 增加"非成员空间""宿主应用不一致""成员关系不可用"三例 |
| **No blocking on the event loop** | 快照查找不变（RFC 0003）；`MembershipProvider` 返回 `CompletableFuture`，只在 token 标记为 provider 时调用，未完成时请求在 `boundedElastic` 上继续（与冷加载相同）；`BlockingAware` 保留到第 5 阶段 | `MembershipProviderOffEventLoopTest`；RFC 0003 的 `SnapshotHotPathTest` 扩展到三份文档 |
| **Authorization is a pure decision** | 引擎在 `engine/`、`ScopeResolver` 在 `model/`，都无 I/O、无时钟（`environment.time` 由调用方给出） | `EnginePurityTest` |
| **策略缓存 `ttl = null`、严格读** | 不变（RFC 0003 §6）：仍是同一个 `PolicySnapshotCache` | `CacheTtlPolicyTest`、`StrictCacheFailureTest` |
| **Hot-path changes come with a JMH before/after** | §12 | `SimpleAuthorizationBenchmark`、`EngineBenchmark` |
| （新）**作用域可信** | `Scope` 只能由 `ScopeResolver` 构造 | ArchUnit 规则（§4.1）、P11 |
| （新）**租户隔离** | 租户文档只能引用本租户或平台层；主体只读自己租户的文档 | 编译检查的单元测试、P12 |
| （新）**break-glass 有期限** | 没有 `expiresAt` 的 break-glass 绑定是编译错误；过期的绑定不适用 | `BreakGlassBindingTest` |

AGENTS.md 的 Invariants 表在 PR-7 更新：Deny-first 一行的守护改为 P1′；新增"作用域可信""租户隔离"两行。

---

## 11. AOT（RFC 0001 §3.10）

| 约束 | 本阶段的做法 |
|---|---|
| `model/`、`engine/` 不用反射和动态代理 | 新类型都是普通类和 sealed 接口；`ScopeResolver` 在 `model/`；`EnginePurityTest` 守护 |
| 文档编解码不用反射 | `SnapshotDocumentCodec` 用 Jackson 流式 API 读写 `schema: 2` 文档的绑定、目录和头部；策略正文在本阶段仍走现有的 `CoSecModule` 反序列化器（RFC 0003 §10 已为它注册 hint），RFC 0005 切换后改为流式解析 AST |
| 新 SPI 显式注册 | `MembershipProvider` 是 Spring bean，没有 `ServiceLoader` 或类路径扫描 |
| 缺少 hint 必须导致失败和拒绝 | 文档解码失败是 RFC 0003 F3；成员关系解析失败是 `Indeterminate`；都不会让一条绑定或语句被跳过 |
| `cosec-migrate` | 命令行工具，不在应用的运行时类路径上，不受 AOT 约束；它不支持 native image |
| JDK 17 | 没有用到 17 以后的 API（`@JvmInline value class` 是 Kotlin 特性） |
| JDK 25 AOT cache | 预热（§7.4）让训练运行加载 `Platform`；`docs/perf/gateway-aot-cache` 的测量在 PR-7 后重跑一次作参考 |

---

## 12. 性能

### 12.1 目标

> **2026-10-26**：下表中与 `layered`（第 3 阶段实现）比较的目标取消，改为 [RFC 0008](0008-conformance-and-direct-build.md) §3.8 以 7.0 实测数字设定的绝对预算；与规模无关的分配目标保留。

| 基准 | 比较 | 目标 |
|---|---|---|
| `SimpleAuthorizationBenchmark`（场景 `global`、`role`、`none`、`global-deny`） | 增加参数 `model` ∈ {`layered`, `unified`}：`layered` 是第 3 阶段实现（快照来源），`unified` 是本阶段实现（转换后的数据） | 吞吐不低于 `layered` 的 95%（判定方法同 RFC 0002 §7.1）；B/op 不高于 `layered` |
| 新场景 `tenant` | 平台层 + 一个非空的租户文档 + 应用文档，主体有 3 个角色、2 个附加 | 只记录，用来量化 §7.3 的"三个索引各查一次" |
| 新场景 `blocked` | 被拉黑的主体 | 吞吐不低于 `layered` 的 95%（§4.7：只读 `Platform`） |
| `EngineBenchmark`（`engine/`） | 新增参数 `bindings` ∈ {10, 1,000, 100,000}（快照中的绑定总数，适用于主体的固定为 10）、`roles` ∈ {1, 10} | 同一场景下 B/op 不随 `bindings` 变化；吞吐随 `bindings` 的下降不超过 5%（适用性查找是哈希，§6.4） |
| `ScopeResolverBenchmark`（`engine/`） | 默认空间、带空间、非成员空间 | 只记录；默认空间零分配 |

### 12.2 测量方法

完全沿用 RFC 0002 §7.2 与 RFC 0003 §9.2：JDK 25、`-PjmhWarmupIterations=5 -PjmhIterations=10 -PjmhFork=3 -PjmhProfilers=gc`、空闲机器（负载 < 1）、
`layered` 与 `unified` 交替三轮。结果记录在 `docs/perf/rfc-0004-unified/README.md`，PR-7 描述附前后对比，PR-8 把最终数字写进 `wiki/architecture/overview.md`（中英文）。

---

## 13. 已知的非决策差异

| # | 差异 | 谁能观察到 | 处理 |
|---|---|---|---|
| X1 | **审计与 `VerifyContext` 的来源**：`AuditSourceType` 的 `ROOT`、`BLACKLIST`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、`ROLE_PERMISSION` 换成 `POLICY`（带策略 id、语句下标）加上 `BINDING`（绑定 id）或 `ATTACHMENT`；新增 `SCOPE`（作用域拒绝）。`PolicyVerifyContext`、`RoleVerifyContext`、`RootVerifyContext`、`BlacklistVerifyContext` 合并为一个 `StatementVerifyContext` | 审计消费方、读 `VerifyContext` 的应用代码 | 破坏性变更（`cosec-api` 的 `AuditSourceType`，Q18），写进 `upgrading.md` 和 `observability.md` |
| X2 | **I/O**：每个请求读三份文档（`Platform` 常驻；`App` 与 `Tenant` 经 LRU） | Redis 读次数（冷加载时） | 接受；RFC 0003 的测量工具增加租户维度 |
| X3 | **解释输出**按绑定组织（§6.5） | `CoSecExplainEndpoint` 的使用者、`PolicyTestKit` 的失败消息 | 破坏性变更，写进 `upgrading.md` |
| X4 | **写 API**：`SnapshotPublisher.setPolicy` 需要目标文档；新增绑定的写入 | 写策略的应用代码 | 破坏性变更 |
| X5 | **异常与原因**：新增 `MembershipUnavailableException`、`ScopeRejected`；`MissingPolicyException` 的 `source` 改为附加 | 按异常类型处理的代码、告警规则 | 写进 `troubleshooting.md` |
| X6 | **`Request.appId`/`spaceId` 仍是原始声明值**；校验后的作用域在 `SecurityContext.scope`（新增） | 读 `request.spaceId` 的应用代码 | 文档写明：后端应读 `SecurityContext.scope`，而不是 Header |

---

## 14. 实现拆分

### 14.1 交付方式

与 RFC 0002 §9.1、RFC 0003 §11.1 相同：全部以 `main` 为 base，squash 合并；不发布任何版本；PR-1 到 PR-6 只做加法，starter 不装配，标题不带 `!`；
PR-7 是切换、PR-8 是删除；每个 PR 合入后两轮审查。

### 14.2 PR 列表

| PR | 标题 | 范围 | 依赖 | 能否提前开工 | 完成标准 |
|---|---|---|---|---|---|
| PR-1 | `feat(model): add tenants, bindings, memberships and resolved scopes` | §4.1 的类型；`ScopeResolver`（§4.6）；P11；ArchUnit 规则 | 本 RFC 获批 | **能**：只依赖已合入的 `model/` | `:model:check`、`:engine:check` 通过 |
| PR-2 | `feat(engine): evaluate the flat algorithm over bindings` | `SnapshotView` 的引擎侧接口、编译后的绑定索引（§6.4）、扁平算法（§6.1）、候选顺序（§6.3）、trace（§6.5）、`evaluate(request, snapshot)`；P1′、P8、P9、P10、P12；`EngineBenchmark` 的新参数 | PR-1；RFC 0002 PR-2（已合入） | **能** | 变更行覆盖率 ≥ 80%；§12 的分配目标 |
| PR-3 | `feat(core): resolve memberships from claims and providers` | 声明解析（§4.5.1）、`MembershipProvider` SPI 与内存实现、`cosec.membership` 标记、`JwtTokenConverter` 的体积阈值、按主体吊销（§4.5.3） | PR-1 | **能** | `:cosec-core:check`、`:cosec-jwt:check` 通过；`MembershipFailClosedTest` |
| PR-4 | `feat(core): compile platform, app and tenant snapshot documents`（**2026-10-26 改动**：语句直接用 RFC 0005 的 AST，依赖改为 RFC 0005 PR-4；不做 `UnifiedAuthorization` 与 `SnapshotPublisher` 的绑定写入；编解码器拒绝 `Match.Exactly("*")`；见 [RFC 0008](0008-conformance-and-direct-build.md) §4.1） | `schema: 2` 文档与编解码、编译（7.x 匹配器经 RFC 0002 PR-3 的适配层）、编译期检查（引用完整性、租户隔离、主体绑定上限、break-glass）、内置的黑名单和 root 策略、`UnifiedAuthorization`（`DecisionAuthorization`，测试中装配）、`SnapshotPublisher` 的绑定写入 | PR-2、PR-3；RFC 0002 PR-3（#993）；RFC 0003 PR-1、PR-2（`SnapshotSource`、编译器、`LayerReads`、`EngineDecisionFlow`，均已合入） | **能**：依赖都已合入，不需要 RFC 0002 PR-6 | `:cosec-core:check` 及依赖模块通过 |
| PR-5 | `feat(migrate): add the 7.x to 8.0 data converter`（**2026-10-26 改动**：不做 `verify`；拒绝 `*` 作为应用或空间 id；见 [RFC 0008](0008-conformance-and-direct-build.md) §4.1） | `migrate/` 模块、`convert`/`publish`/`verify`（`verify` 依赖 PR-6 的分类器，先只做 `convert`/`publish`）、拒绝项（§8.4）、来源映射、报告 | PR-4；RFC 0003 PR-3（#1006，CoCache 6.7 的文档存储与发布者，已合入；`CoSec7xReader` 随它提供） | **能** | 合成 7.x 数据（含每一种拒绝项）的转换测试；转换后的文档经 PR-4 编译通过 |
| ~~PR-6~~ | ~~`test: classify intended decision differences in replay`~~ **已取消（2026-10-26，[RFC 0008](0008-conformance-and-direct-build.md)）** | `DifferenceClassifier`、C1a–C3 的类别、正例与反例、合成语料、回放工具的候选模式（转换 + 本阶段实现）、`cosec-migrate verify`；百万例任务 | PR-5；`DecisionParity` 与回放工具（`cosec-core` 测试夹具，#997、#1002，已合入）；RFC 0003 PR-4 | **能**：比较器已经是通用的（比较任意两个 `DecisionAuthorization`）；回放任务现名 `engineReplay`，RFC 0002 PR-6 改名为 `decisionReplay` 后跟随改名 | 每一类的正例被归入、反例不被归入；用仓库内的合成数据跑通回放与 `verify` |
| — | ~~**门槛 A**~~ **已取消（2026-10-26）** | 维护者在本地：对同一批导出跑回放（§9.4），逐条确认 C1a 的样本 | PR-6 | — | 未归类差异为 0；证据（计数与哈希）附在 PR-7 |
| ~~PR-7~~ | ~~`refactor!: authorize from bindings over platform and tenant snapshots`~~ **并入 RFC 0008 的切换 PR（2026-10-26）** | starter 装配 `UnifiedAuthorization`、三种 `SnapshotKey`、`cosec.authorization.scope.*`、`cosec.authorization.membership.*`、`prewarm-tenants`；`SecurityContext.scope`；schema 标记 `"8.4"`；`AuditSourceType`（Q18）；解释按绑定；`PolicyTestKit` 读新文档；文档（§14.3）；JMH | RFC 0003 PR-6；PR-6；门槛 A | **不能** | 全部模块 `check` 通过；门槛证据；§12 的目标 |
| ~~PR-8~~ | ~~`refactor!: remove layers, role permissions and the root matcher`~~ **并入 RFC 0008 的切换 PR（2026-10-26）**，删除清单不变 | 删除 `LayerKind`、`PolicySet`/`LayerInput`/`RuleGroup`、`evaluate(request, PolicySet)`、`RoleScope` 在授权路径上的使用、`RootPrincipalMatcher`、`root-enabled`、`ROOT_ID`/`isRoot`；`cosec-api` 的 `AppRolePermission`、`RolePermission`、`PolicyCapable` 中与评估有关的部分、`PolicyType.GLOBAL` 的评估含义（Q19）；RFC 0003 的 `Policies` key | PR-7 | **不能** | 全部模块 `check` 通过；`wiki` 检查通过 |

PR-1、PR-3 可以立即并行；PR-2 只等 PR-1。RFC 0005 的 PR-1（AST 类型）与本文 PR-1 互不依赖，可以同时开工。

### 14.3 文档落点（中英文同步）

| 文件 | PR | 内容 |
|---|---|---|
| `wiki/guide/concepts.md` | PR-7 | 绑定、附加、平台层与租户层、成员关系、作用域；删除"三层"的说法 |
| `wiki/guide/security-model.md` | PR-7 | "Evaluation order" 改为扁平算法与候选顺序；"Fail closed" 增加成员关系与作用域；"Root principal" 改为 break-glass 绑定；"Trust boundaries" 增加 `ScopeResolver` 与宿主应用；"Tenant isolation" 重写（§4.3、§5.4）；"Role scope and inRole"（`inRole` 的修订在 RFC 0005） |
| `wiki/policies/role-permissions.md` | PR-7 | 改为"角色绑定"：目录、权限策略、授予 = 绑定，作用域 |
| `wiki/policies/language.md` | PR-7 | 文档结构（`attachable`、绑定）；匹配器部分在 RFC 0005 重写 |
| `wiki/integration/authentication.md` | PR-3、PR-7 | 成员关系声明、`cosec.membership`、体积阈值、`MembershipProvider`、按主体吊销、撤销延迟上限 |
| `wiki/integration/web.md` | PR-7 | 作用域的解析与拒绝（403/401）、宿主应用、`SecurityContext.scope` |
| `wiki/integration/checks.md` | PR-7 | 代码内检查在什么作用域里进行 |
| `wiki/operations/configuration.md` | PR-7、PR-8 | `cosec.authorization.scope.app-id`、`cosec.authorization.membership.timeout`、`cosec.jwt.membership-claim-limit`、`prewarm-tenants`、`max-tenants`；删除 `root-enabled` |
| `wiki/operations/storage.md` | PR-7 | 三种文档、Redis key、跨文档引用与发布顺序（§7.2）、schema 标记 `"8.4"` |
| `wiki/operations/upgrading.md` | PR-7、PR-8 | 7.x → 8.0：**数据迁移**（`cosec-migrate convert`/`verify`/`publish`、拒绝项、回滚）、C1–C6、X1–X6、删除的 API |
| `wiki/operations/troubleshooting.md`、`skills/cosec-troubleshoot` | PR-7 | 新症状：非成员空间 403、成员关系不可用 500、break-glass 过期、转换被拒绝、schema 标记不符 |
| `wiki/operations/observability.md` | PR-7 | 审计来源类型（X1）、成员关系指标 |
| `wiki/architecture/overview.md` | PR-7、PR-8 | 请求路径（`ScopeResolver`）、组合算法、快照三元组、新不变量、JMH 数字 |
| `wiki/architecture/extending.md`、`skills/cosec-custom-matcher` | PR-7 | `MembershipProvider` 扩展点 |
| `wiki/guide/quick-start.md`、`skills/cosec-integration` | PR-7 | 写绑定与角色授予的示例 |
| `skills/cosec-policy-author` | PR-7 | 绑定、附加、作用域 |
| `schema/` | PR-7 | 新增 `cosec-snapshot-document.schema.json`（三种文档）、`cosec-binding.schema.json`；`cosec-policy.schema.json` 增加 `attachable`、删除 `type` 的评估含义；`cosec-app-permission.schema.json` 改为 `cosec-catalog.schema.json` |
| `AGENTS.md` | PR-7 | Mental model 的 "Authorization" 一条（扁平算法、绑定）；Invariants 表（§10） |

---

## 15. 决定

以下各项已于 **2026-10-22** 由维护者确认，全部按本文的推荐决定。标注"测量后确认"的，PR 按这里的值实现，数字由测量结果校准。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q1 | **`Binding` 的主体是否包括 `group`**（§3 I1） | **已决定**：不包括：`Principal`、`Role`、`Anyone` 三种 | CoSec 没有组的概念；身份源的组以角色的形式进入 token，效果相同，模型更小 |
| Q2 | **主体直接引用的策略**（§3 I2、§4.4） | **已决定**：作为成员关系的"附加"，来自 token 的 `policies` 声明；策略加 `attachable` 标志；快照里的主体绑定只用于 break-glass 与服务账号，每个文档上限 1,000 | 用户级数据不进快照（P11）；转换工具拿不到 token 签发方的数据；沿用声明就不需要重新签发 token |
| Q3 | **作用域的默认值**（§3 I3） | **已决定**：每个维度必须显式写出，通配写 `*` | RFC 0001 原文有两种相反的读法；显式写出没有歧义，转换工具生成时不吃亏 |
| Q4 | **快照版本与组合方式**（§3 I4、I5） | **已决定**：三份文档 `Platform`、`App(appId)`、`Tenant(tenantId)`，版本三元组；组合而不合并 | 保留 RFC 0003 已确认的按应用分区；平台发布不需要 O(租户数) 的重新编译 |
| Q5 | **转换后的数据放在哪一层**（§5.2） | **已决定**：默认全部放平台层（方案 A）；`--partition by-owner` 作为可选项 | 7.x 的数据本来就对所有租户生效；只有这样门槛 A 的差异才只来自刻意的语义修订；`tenantId` 多半不反映真实归属 |
| Q6 | **扁平算法的两条补充规则**（§3 I6、§6.1） | **已决定**：DENY 命中优先于错误、缺失和不可用；先扫完全部 DENY 再判断出错 | 决策与候选顺序无关（P8）；拒绝已经确定时报告"无法判定"没有意义；E3 不再是差异 |
| Q7 | **"属性缺失"的含义**（§3 I7） | **已决定**：本来就可能没有的属性按"不存在"参与比较，不是错误；`onMissing` 只用于第 5 阶段的外部属性 | 否则每个不带可选请求头的请求都会 500；详细规则在 RFC 0005 §3.4 |
| Q8 | **root**（§3 I8、I9、§4.8） | **已决定**：break-glass 绑定，必须有过期时间，受全部 DENY 约束，没有豁免；本阶段 WARN 日志 + 现有审计，第 5 阶段 `REQUIRED` 审计 | deny-first 对所有主体成立，P1 不再需要例外；break-glass 是补缺失的 ALLOW，不是绕过 DENY |
| Q9 | **黑名单**（§3 I10、§4.7） | **已决定**：名单仍由 `BlacklistChecker` 提供（第 5 阶段改为 `AttributeProvider`）；规则是编译时注入的内置 DENY，排在最前 | 规则进了快照又不可能被误删；被拉黑的请求的 I/O 与 7.x 相同 |
| Q10 | **编解码放在哪里**（§3 I11） | **已决定**：`model/` 保持只依赖标准库；文档编解码在 `cosec-core`，用 Jackson 流式 API | RFC 0002 的纯净约束已确认；流式 API 不需要反射 hint |
| Q11 | **应用维度的信任**（§4.6） | **已决定**：宿主可以配置应用（服务内嵌：属性；Gateway：路由元数据），配置后声明不一致即拒绝；不配置时沿用声明值；生产文档建议配置 | 不强制配置，现有部署切换时不会失败；配置是低成本的加固 |
| Q12 | **成员关系声明的格式**（§3 I14、§4.5.1） | **已决定**：沿用 7.x 的 `roles` 列表（`role@space`）和 `policies` 列表；键只是空间；新增 `cosec.membership: "provider"` 标记 | 不需要重新签发 token；7.x 的空间本来与应用无关 |
| Q13 | **按主体吊销**（§3 I17、§4.5.3） | **已决定**：本阶段增加 `TokenRevoker.revokeSubject` | RFC 0001 §7.1 的"成员关系默认来自声明"依赖它作为紧急手段，而现在没有 |
| Q14 | **`MembershipProvider` 的交付范围**（§4.5.2） | **已决定**：本阶段：SPI（`CompletableFuture`）、失败即拒绝的契约、内存实现；Redis 实现与挂起包装在第 5 阶段 | 7.x 没有用户在用它，它不在门槛 A 的比较之内；先定契约，实现随 `cosec-store-redis` |
| Q15 | **`App`、`Tenant` 文档的 LRU 上限**（测量后确认） | **已决定**：各 1,024 | 与 RFC 0003 Q19 一致；用测量工具的租户维度校准 |
| Q16 | **`cosec-migrate` 是否发布** | **已决定**：发布，带 `main` 的 jar，也可作为库调用；不依赖 Spring | 用户升级时必须运行它；它不是 `cosec-gateway-server` 那样的应用 |
| Q17 | **schema 标记**（§8.5） | **已决定**：本阶段改为 `"8.4"`；GA 时定为最终值 | 第 3 阶段的 `"8"` 布局在 `main` 上已有开发部署；标记不变的话，它们会读到"没有 `Platform` 文档"，全局 DENY 静默失效（RFC 0003 §4.7 同类问题） |
| Q18 | **`AuditSourceType` 等 `cosec-api` 变更**（X1；AGENTS.md 要求先问） | **已决定**：PR-7 改为 `POLICY`/`BINDING`/`ATTACHMENT`/`SCOPE`，合并四种 `VerifyContext` | 层消失之后，旧的来源类型无法如实表达"哪条绑定的哪条语句" |
| Q19 | **`cosec-api` 中旧模型的删除**（AGENTS.md 要求先问） | **已决定**：PR-8 删除 `AppRolePermission`、`RolePermission`、`PolicyType.GLOBAL` 的评估含义、`RootPrincipalMatcher`、`ROOT_ID`；`Policy`/`Statement`/`ActionMatcher`/`ConditionMatcher` 保留到 RFC 0005 的切换 | 留着它们就留着第二种挂载方式；匹配器接口在 RFC 0005 之前仍是语句的执行者 |
| Q20 | ~~**门槛标准**（§9）~~（2026-10-26 由 [RFC 0008](0008-conformance-and-direct-build.md) 取代） | **已决定**："未归类差异为零"取代"差异为零"；放宽类（C1a）逐条确认；新增类别必须修订本 RFC | 本阶段有意改变语义；把"有意"变成可机械检查的证据，而不是人工判断 |
| Q21 | **C3：声明了非成员空间**（§4.6） | **已决定**：拒绝，不提供开关 | 退回默认空间会让后端在主体不属于的空间执行请求，是绕过 |

---

## 附录 A：已确认差异

门槛期间发现、并经维护者确认的差异登记在这里：基准缺陷，以及 C1a 的逐条确认结论（只记类别、计数和语句 id 的哈希）。初始为空。

| 编号 | 类别 | 计数 | 结论 | 确认人 / 日期 |
|---|---|---|---|---|

## 附录 B：涉及的现有代码

本文对现有行为的描述，以 `main` @ `334da022` 的下列代码为准：

- 契约：`cosec-api/.../principal/CoSecPrincipal.kt`（`ROOT_ID`、`isRoot`）、`RoleCapable.kt`（`SpacedRoleId`、`roles` 的作用域约定）、`PolicyCapable.kt`、
  `policy/Policy.kt`、`PolicyType.kt`、`Statement.kt`、`permission/AppPermission.kt`、`AppRolePermission.kt`、`RolePermission.kt`、`PermissionGroup.kt`（`spaced`）、
  `MissingPermissionException.kt`、`context/request/Request.kt`（`appId`、`spaceId` 来自 Header 或 Query）、`SpaceIdCapable.kt`、`tenant/Tenant.kt`、`CoSec.kt`、
  `audit/AuditEvent.kt`（`AuditSourceType`）
- 决策：`cosec-core/.../authorization/SimpleAuthorization.kt`、`RoleScope.kt`、`RootPrincipalMatcher.kt`、`PolicyVerifyContext.kt`、`MissingPolicyException.kt`、
  `blacklist/BlacklistChecker.kt`、`context/SimpleSecurityContext.kt`（租户来自主体）、`context/DefaultSecurityContextParser.kt`
- 匹配器（本阶段不改，RFC 0005 翻译）：`policy/condition/context/InRoleConditionMatcher.kt`、`InTenantConditionMatcher.kt`、`policy/condition/part/PartExtractor.kt`
- 引擎：`model/src/main/kotlin/me/ahoo/cosec/model/AuthzRequest.kt`、`Decision.kt`、`LayerKind.kt`；`engine/src/main/kotlin/me/ahoo/cosec/engine/Engine.kt`、`PolicySet.kt`、`Evaluator.kt`
- 认证：`cosec-jwt/.../JwtTokenConverter.kt`、`Jwts.kt`（`roles`、`policies`、`tenantId` 声明）、`cosec-core/.../token/TokenRevoker.kt`、`RevocableTokenVerifier.kt`、
  `cosec-spring-boot-starter/.../jwt/JwtProperties.kt`（access token 默认 10 分钟）
- 存储：RFC 0003 附录 B 的全部；`cosec-cocache/.../cache/AppSpacedRoleId.kt`、`CoSecSchemaMarker.kt`
- 生成器：`cosec-openapi/.../generator/OpenAPIPolicyGenerator.kt`（`tenantId` 总是默认租户）、`OpenAPIAppPermissionGenerator.kt`
- Gateway：`cosec-gateway/.../GatewayRouteRequestAttributesAppender.kt`（路由 id 进入请求属性）
