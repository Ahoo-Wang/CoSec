# CoSec 8.0 路线图

> 目标架构见 [RFC 0001](rfc/0001-target-architecture.md)，各阶段的细化设计见各自的 RFC。本文只记录**进度**：每个阶段走到了哪一步、
> 还差什么、在等谁的决定。决策和理由以 RFC 为准；本文与 RFC 不一致时，以 RFC 为准并修正本文。
>
> 更新于 2026-10-28，基线 `main` @ `7bc9fa88`（#1123）。
>
> 面向用户的路线图见 [CoSec 8.0 路线图](https://cosec.ahoo.me/zh/guide/roadmap)（[`wiki/zh/guide/roadmap.md`](../wiki/zh/guide/roadmap.md)）。

## 0. 总览

**2026-10-26 重新规划**（维护者决定，[RFC 0008](rfc/0008-conformance-and-direct-build.md)）：8.0 不考虑兼容性，按最终目标架构设计。各阶段与 7.x 或与上一阶段的等价门槛
（离线回放、门槛 A–D）全部取消，由**规范一致性套件**取代；只为等价存在的 PR 取消；目标路径的各部分以加法合入，最后由**一个切换 PR**
装配目标路径、删除 7.x 路径与全部等价脚手架。阶段仍按 RFC 划分，但不再各自切换。

**2026-10-26 修订（路线图审查）**（维护者决定，[RFC 0008](rfc/0008-conformance-and-direct-build.md) §4.4、§4.7）：切换拆成依次合入的 **S1–S5**：S1 在 starter 中装配 8.0 路径（不可回退点，RFC 0008 §3.9 的证据在它之前齐备），S2–S4 按模块分块删除 7.x 代码，S5 写文档。新增一个**端到端切片**，在 RFC 0004 PR-4 与 RFC 0006 PR-2 之后把快照、引擎、Pipeline 与 WebFlux 接起来，并第一次测量性能预算。RFC 0008 PR-3（7.0 基线）已派发；RFC 0007 PR-6 的分析部分作为 PR-6a 现在开工。RC 前提增加 AOT 冒烟与 RC 安全审查。

| 阶段 | 内容 | 设计 | 实现 | 状态 |
|---|---|---|---|---|
| 1 | 安全修复与依赖升级（D1–D7、CoCache 6.6、CosId 4.0） | RFC 0001 §6 | 已合入，随 7.0.0 发布 | ✅ 完成 |
| 2 | 纯函数决策引擎：`model/`、`engine/` | [RFC 0002](rfc/0002-pure-engine.md)，已确认 | PR-1 至 PR-5 已合入；PR-6 取消（并入切换） | ✅ 剩余工作并入切换 |
| 3 | 策略快照分发 | [RFC 0003](rfc/0003-policy-snapshot.md)，已确认 | PR-1 至 PR-4 已合入；PR-5 取消，PR-6 并入切换 | ✅ 剩余工作并入切换 |
| 4 | 统一模型：`Binding`、平台层/租户层、条件 AST、`service:verb`、编译执行、转换工具 | [RFC 0004](rfc/0004-unified-model.md) / [RFC 0005](rfc/0005-condition-ast.md)，已确认（2026-10-22） | RFC 0004 PR-1 至 PR-3、RFC 0005 PR-1 至 PR-4 已合入（PR-4 #1051）；RFC 0004 PR-4 已合入（#1052）；RFC 0004 PR-4b 已合入（#1053）；RFC 0004 PR-5（`cosec-migrate` 的 `convert`）已合入（#1067）；RFC 0005 PR-6（条件翻译）已合入（#1069）；RFC 0005 PR-5（路由映射）已合入（#1079）。实现部分完成，余下的只有切换步骤（S1–S5） | ✅ 实现完成 |
| 5 | Pipeline 重组：协程、`AttributeProvider`、义务、503 | [RFC 0006](rfc/0006-coroutine-pipeline.md)，已确认（2026-10-25） | PR-1 已合入（第 1 轮审查修复 #1031 已合入）；PR-2 已合入（#1044）；PR-3 已合入（#1054、#1055、#1057）；PR-4 已合入（#1066）；PR-6 已合入（#1068）；PR-7（适配器桥接）已合入（#1070）；PR-3 至 PR-7 的第 1 轮审查修复已合入（#1072）；PR-5（限流义务）已合入（#1078）。实现部分完成；`Pipeline` 改为接收一个 `PipelineComponents`（#1088）；BEST_EFFORT 监听器的批量交接与阶段 A 黑名单的代价（#1096）已合入。starter 对 8.0 路径的装配（未注册）已合入（S1a，#1097），注册与 7.x 的删除是 S1b | ✅ 实现完成 |
| 6 | 控制面 `cosec-control` | [RFC 0007](rfc/0007-control-plane.md)，已确认（2026-10-26，#1028） | PR-1（#1032）、PR-2（#1038）、PR-6a（授出分析，#1059）、PR-4（修订与发布，#1064）、PR-5（校验、编译与暂存，#1071）、PR-6（写操作的授权与审批，#1076）、PR-7（影子窗口，#1080）、PR-8（break-glass、租户自助、变更审计，#1087）、PR-3（平台边界，#1075，Q24 已接受）、PR-9（库与 HTTP，#1091）已合入；控制面第 1 轮审查修复（K1–K4）已合入（#1095）。starter 的 `controlSupport` 装配（未注册）在 S1a（#1097）。实现部分完成，余下的是 S1b 的注册 | ✅ 实现完成 |
| — | 规范一致性套件、端到端切片与切换 S1–S5 | [RFC 0008](rfc/0008-conformance-and-direct-build.md)，已确认（2026-10-26），路线图审查修订（2026-10-26） | PR-1（套件）已合入（#1045）；PR-3（7.0 基线）已合入（#1065）；PR-2（变异检查）已合入（#1086）；S1a（starter 装配 8.0 路径，未注册）已合入（#1097）；S1b（不可回退点）已合入（#1104）；S2–S4 合并为一个删除 PR，进行中 | 🟡 进行中 |

**发布策略**（RFC 0001 §6，2026-10-21）：目标架构全部达成、每个 PR 做完两轮审查之前，8.0 不发布任何版本，没有里程碑版和 RC。
切换 PR 的通过标准是一致性套件全绿、每条规则有正反例、不变量性质在固定种子与一次百万例运行下通过、变异体全部被杀死、
JMH 达到以 7.0 实测数字设定的绝对预算（RFC 0008 §3.9）。不需要任何真实部署的数据。（2026-10-26 修订：这些标准约束 S1。）
RC 前提（RFC 0008 §3.9）：S1–S5 与 RC 前清理全部合入并做完两轮审查；`cosec-gateway-server` 的 AOT 冒烟在 CI 中通过；RC 安全审查的发现全部修复；
`cosec-gateway-server` 与示例运行在 8.0 上。

**维护线**：`main` 是 8.0 线；`7.x`（从 `v7.0.0` 切出）只接收安全和关键修复，维护到 8.0.0 GA 之后 6 个月；`release/6.1.x` 只接收安全修复。
修复先进 `main`，再 cherry-pick 回移（[CONTRIBUTING.md](../CONTRIBUTING.md)）。

**维护版本**：2026-10-26 发布 [6.1.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v6.1.2) 与 [7.0.1](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.1)，修复 [GHSA-2293-jx8h-56m8](https://github.com/Ahoo-Wang/CoSec/security/advisories/GHSA-2293-jx8h-56m8)（路径模板的值被当作模式语法）；同一修复已进入 `main`（#1020）。

2026-10-26 发布 [7.0.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.2)（#1062，已在 Maven Central）：策略解码器拒绝格式错误的策略（未知字段、条件或动作对象不是恰好一个键、应为列表处写了标量、重复键），不再宽松地编译成匹配范围更大的策略。升级前要检查已存储的策略，见发布说明。

**过程**：每个阶段先写细化 RFC，由维护者审批；每个实现 PR 合入后做两轮独立审查，审查发现以 `fix(...): round-N review fixes` PR 修复。
2026-10-26 起（RFC 0008 §4.7）：同一领域有已合入、未做完两轮审查的 PR 时，该领域不开新的功能 PR；建议维护者在 `main` 的分支保护中要求合入前分支是最新的（`required_status_checks.strict`；#1024 + #1027、#1033 + #1034 各自全绿、合起来让 `main` 失败；落后的 PR 由协调者用 `gh pr update-branch` 更新；个人账号的仓库没有 merge queue）；按 PR 的状态只写在本文，[wiki 路线图](../wiki/zh/guide/roadmap.md)只在里程碑边界更新。

---

## 1. 第 2 阶段：纯函数决策引擎（RFC 0002）

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| PR-1 | `cosec-model`、`cosec-engine` 两个模块（目录 `model/`、`engine/`），纯度守护，JDK 25 基线 | #974、#975 | — | — |
| PR-2 | 决策模型、唯一的组合算法、三级 trace | #982 | ✅ 修复 #983 | ✅ 修复 #989 |
| PR-3 | `cosec-core` 适配层与 `EngineAuthorization` | #993 | ✅ 修复 #994 | ✅ 修复 #995 |
| — | `role` 层热路径优化（§7.1 后续） | #1000 | — | — |
| PR-4 | 决策比较器 `DecisionParity`、`PolicyTestKit.parity`、离线回放工具 | #997 | ✅ 修复 #1002 | ✅ 修复 #1016 |
| PR-5 | 基于引擎 trace 的决策解释（只在测试中装配） | #996 | ✅ 修复 #999 | ✅ 修复 #1001 |
| ~~PR-6~~ | ~~删除旧实现，`SimpleAuthorization` 改由引擎实现~~ | 取消（2026-10-26）：并入 RFC 0008 的切换 PR | — | — |

按层的引擎、`EngineAuthorization` 与 PR-4 的比较器和回放工具都是中间形态，在切换 PR 中删除（RFC 0008 §4.2）。第 2 阶段的性能豁免
（RFC 0002 §7.2 第 8 步）随 PR-6 失效，由 RFC 0008 §3.8 的绝对预算取代：编译执行在 `global`、`role`、`none`、`role-heavy` 上不低于 7.0 的 100 %。

---

## 2. 第 3 阶段：策略快照分发（RFC 0003）

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| PR-1 | 快照源：按需加载、单飞、有序安装、重新校验、陈旧时间上界 | #988 | ✅ 修复 #990、#991 | ✅ 修复 #992 |
| PR-2 | 快照编译，由快照构造 `PolicySet` | #998 | ✅ 修复 #1003 | ✅ 遗留项随 #1017 修复 |
| PR-3 | CoCache 上的快照文档存储与发布（条件写入） | #1006 | ✅ 修复 #1017 | ✅ 修复 #1026 |
| PR-4 | 快照的比较、回放与规模测量工具 | #1030 | 不做（工具在切换时删除） | 不做 |
| ~~PR-5~~ | ~~切换：授权读快照~~ | 取消（2026-10-26） | — | — |
| ~~PR-6~~ | ~~删除每请求读仓库的路径~~ | 并入 RFC 0008 的切换 PR | — | — |

`SnapshotSource`、`SnapshotDocumentStore`、`RedisSnapshotDocumentStore` 与条件写入是目标路径的一部分，原样复用；7.0 形状的
`Policies`/`App` 文档与 `SnapshotPolicySetLoader` 在切换时删除（RFC 0008 §4.2、§4.3）。PR-3 落实的审查要求（快照缓存 `foreignFormat = FAIL`、
`ttl = null`；发布冲突时重新读取；`read()` 不用负缓存；不加 `WAIT`）不变；原 PR-5 中仍然成立的要求（启动时检查 `noeviction`、固定缓存的编解码器与
key 过滤器、协调 `localMaxAge`）转入切换 PR（RFC 0008 §4.4）。

---

## 3. 第 4 阶段：统一模型（RFC 0004 / RFC 0005）

两篇 RFC 已于 2026-10-22 确认（#987）。2026-10-26 起：门槛 A、B 与 RFC 0004 PR-6、RFC 0005 PR-7 取消；两次切换（RFC 0004 PR-7/PR-8、RFC 0005 PR-8/PR-9）
并入 RFC 0008 的切换 PR；RFC 0004 PR-4 的文档直接使用 AST（没有"绑定模型 + 7.x 匹配器"的中间格式）；`legacy` 条件节点不实现（RFC 0008 §4.1）。

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| RFC 0004 PR-1 | 租户、绑定、成员关系与 `ScopeResolver`（`model/`） | #1015 | ✅ 修复 #1024 | ✅ 修复 #1033 |
| RFC 0004 PR-2 | 扁平组合算法与绑定索引（`engine/flat`） | #1029 | ✅ 修复 #1046 | — |
| RFC 0004 PR-3 | 成员关系：token 声明、`MembershipProvider`、按主体吊销 | #1027 | ✅ 修复 #1034 | — |
| RFC 0005 PR-1 | 条件 AST：类型、成本上限、规范化与求值 | #1022 | ✅ 修复 #1035 | — |
| RFC 0005 PR-2 | 索引键抽取与条件包含 `implies` | #1036 | ✅ 修复 #1037 | — |
| RFC 0005 PR-3 | 不依赖 Spring 的动作与资源匹配 | #1040 | — | — |
| RFC 0005 PR-4 | 编译语句与候选索引，扁平引擎改为评估编译后的语句；I1、I2、`ALG-7` 的性质；对 7.0 的吞吐预算 | #1051 | — | — |
| RFC 0004 PR-4 | `schema: 2` 的平台、应用、租户文档与编解码、文档编译与编译期检查、两层同名按内容解析（RFC 0007 N2）、格式描述、`DOC-*`/`TNT-2` 等场景 | #1052 | — | — |
| RFC 0004 PR-4b | `SnapshotSource` 推广为一个共享槽加多个 LRU 族，加载平台、应用、租户文档并实现 `SnapshotViews`；`SNP-1`、`SNP-2` 的守护 | 已合入（#1053） | — | — |
| RFC 0004 PR-5 | `cosec-migrate`：`convert`、`publish`、拒绝项、来源映射与报告（含受语义改动影响的 7.x 配置形态）；翻译动作，条件的翻译留给 RFC 0005 PR-6 | #1067 | ✅（只读审查，修复并入 #1069） | — |
| RFC 0005 PR-6 | 把 7.x 条件按方向翻译成 AST（不放宽 ALLOW、不收窄 DENY，做不到即拒绝）、限流规则翻译；`migrate/` 运行时依赖 `spring-web`、`spring-expression`（维护者批准）；审查修复：`"method": "*"` 拒绝、只差大小写的 id 拒绝、`CREATE_NEW` 与发布前 SHA-256 校验、`--resume`、带大小写映射的字符不让 DENY 收窄、含 DENY 的策略不随 by-owner 移走 | #1069 | ✅ 修复在 #1069 内 | — |
| RFC 0005 PR-5 | 路由映射（`cosec-core` 的 `me.ahoo.cosec.route`，整体加载或整体失败）、`OpenAPIServiceGenerator`、Pipeline 的 `routes` 参数；未映射的请求保持 `http:<METHOD>`，捕获值含 `/` 返回 400；`ACT-2` 不再 pending，新规则 `RTE-1`–`RTE-3` | #1079 | — | — |

第 4 阶段的实现 PR 全部合入；剩下的是切换步骤与审查轮次。
依赖见 RFC 0008 §4.5。

---

## 4. 第 5 阶段：协程化 Pipeline（RFC 0006）

RFC 0006 已于 2026-10-25 确认。PR-1（`runtime/` 模块与依赖）已合入（#1025），第 1 轮审查修复已合入（#1031）；PR-2 已合入（#1044）。2026-10-26 起：门槛 C 与 PR-8 取消；
PR-9、PR-10 并入切换；PR-2 增加"同一 `CoSec-*` 参数的 Header 与 query 值不同即 400"；性能对照从第 4 阶段改为 7.0（RFC 0008 §4.1、§3.8）。
2026-10-26 起：CoSec 写的错误响应改为 RFC 9457 problem+json，显式与隐式拒绝在响应中合并（RFC 0006 Q27）；PR-2 实现，PR-7 写回，切换 PR 写文档。
PR-2（#1044）：`HttpFacts` 与 `SCP-4`、`PathNormalizer`、`Authenticator` SPI、`Outcome` 与 `StatusMapping`（problem 词汇）、手写的 `ProblemEncoder`、
`problem` 格式描述与 `schema/problem.schema.json`、`Pipeline.decide` 骨架（整体超时、取消规则、`resumeOffIo` 与 T1–T3）、`SuspendingSnapshotSource`，全部用测试替身；
快照视图的来源是新的 `SnapshotViews` 接口，由 RFC 0004 PR-4 的文档源实现。PR-3（#1054，已合入）：`MembershipProvider` 改为 `suspend`（Q11），成员关系解析搬进 `runtime/` 的挂起 `MembershipResolver`，`TokenAuthenticator` 校验令牌、按令牌与按主体吊销（`MEM-4`）并给出 `token-*`（`PIP-6`），吊销的读接口是新的 `RevocationReader`（`TokenStore` 的同步方法留给 7.x 路径到切换为止）；PR-3b（#1055，已合入）：`CoCacheRevocationReader`（`CoCacheTokenStore.reader`，CoCache 异步视图上的非阻塞吊销读，保留文档化的降级）、`CoCacheMembershipProvider` 与 `MembershipLoader`（带 TTL 的成员关系缓存，读写失败抛出，`MembershipCacheFailureTest`）、新规则 `PIP-7`；PR-3c（#1057，已合入）：`ApiKeyReader`（`ApiKeyStore` 的挂起读接口）与 `RedisApiKeyReader`（响应式客户端，不阻塞）、`ApiKeyAuthenticator`、`CompositeAuthenticator`（带 API Key 的请求只按 Key 判断）、新规则 `PIP-8`。PR-4（#1066，已合入）：外部属性 `ext.<provider>.<name>` 与属性目录（未注册即编译错误）、`onMissing`（DENY 的 `nomatch` 只限平台文档）、纯函数 `Engine.plan`、阶段 A（与成员关系并发）与阶段 B（按提供者并发、各自超时）的预取，`BlacklistAttributeProvider`/`BlacklistSource`（读失败不当作"未拉黑"，没有 DENY 命中即 503）与 `Ip2RegionAttributeProvider`；性质 P9′、P14，新规则 `ATR-2` 至 `ATR-5`、`BLK-2`，`ATR-1` 不再 pending。PR-6（#1068，已合入）：`DecisionEvent`（每个结果都有事件，含 400、认证不可用、取消与缺陷；不含外部属性的值、请求头与 token）、`DecisionListener` 与投递（REQUIRED 在超时内并发、失败只把 Permit 变成 503；BEST_EFFORT 各自有界队列、不在请求线程上、满了丢弃）、break-glass 的 Permit 必须有 REQUIRED 监听器记录（Q20）、`ShadowListener`（只在采样命中时创建 `ReplayHandle`）、`AuditEvents.from` 与 `LoggingAuditListener`、`KafkaAuditListener`、`MetricsListener`、`TracingListener`，以及 `BridgeContextContributor` 的 OTel 与 MDC 实现；`OBS-1` 不再 pending，新规则 `OBS-2`。PR-7（#1070，已合入；第 1 轮审查修复 #1072，同时修复 PR-3 至 PR-6 的审查发现，并让 S1 必须装配真实的 `RevocationReader`）：三个适配器经 `mono {}`（WebFlux、Gateway）与 `runBlocking`（WebMvc，只在 Servlet 线程上）桥接到协程 Pipeline（`PipelineWebFilter`、`PipelineGatewayFilter`、`PipelineServletFilter`，与 7.x 的过滤器并存到 S1 装配、S3/S4 删除），`HttpFactsReader`，只写 `Outcome` 的状态、响应头与 `ProblemEncoder` 的字节（400 也带 `CoSec-Request-Id`，追踪生效时响应体带 `traceId`），`SecurityContext.scope`，挂起的 `AuthorizationChecker` 与 `checkMono`/`checkBlocking`；`WebAdapterParityTest` 只对新 Pipeline 运行，三个适配器在 §6.1 的每一个 `type` 上逐字节相同，`PipelineBlockHoundTest`，端到端切片加 WebFlux 适配器测试。2026-10-26 修订（路线图审查）：PR-3 至 PR-7 合入时各自把自己的一段接进端到端切片（RFC 0008 §4.4.3）；PR-9 的 starter 装配落在 S1。PR-5（#1078，已合入）：`Permit.obligations` 只随 Permit 计算（P15），平台 → 应用 → 租户的顺序；平台规则保留 7.x 的 Redis 桶，应用与租户规则带命名空间（`app:<app>/<id>`、`tenant:<tenant>/<id>`）；三种文档都有可选的 `rateLimits` 一节（`schema` 版本不变，RFC 0008 Q5）；第一个耗尽的规则返回 429，`strictFailure` 规则计数失败返回 503；`ReactiveRedisRateLimiter` 不经 `boundedElastic`；转换工具把规则写进平台文档的 `rateLimits`。

#1088（`refactor(runtime): pass pipeline components as one configuration`，已合入）：`Pipeline` 的构造参数收成一个不可变的 `PipelineComponents`（只有 `authenticator` 与 `snapshots` 必填，其余字段带原来的默认值），之后每增加一个组件只是这个类的一个字段，不再引起各 PR 在构造参数与夹具上的合并冲突；`PipelineFixtures.pipeline(...) { it.copy(...) }` 取代逐个转发；决策路径不变。
#1096（`perf(runtime): cut the BEST_EFFORT hand-off and stage-A blacklist cost`，已合入）：`DecisionListeners` 每个监听器的 `Channel` 换成无锁的有界队列加合并的唤醒，消费者每批唤醒一次并在批后停留 1 ms（`DecisionListeners.LINGER`，BEST_EFFORT 事件最多晚 1 ms 开始处理；`publish` 仍只是入队，队列满或已关闭就丢弃并计数）；只有存在 BEST_EFFORT 监听器时 `Pipeline.evaluate` 才构造发布事件；成员关系来自令牌声明时阶段 A 不再开 `coroutineScope`；没有阶段 B 的提供者时不做 `engine.plan`（`AttributeProviders.hasStageB`）；`InMemoryBlacklistSource` 按种类各查一个集合。CI 的 JMH A/B（三轮，共享机器，不是 S1 要的空闲机器测量）：`listener` 场景为基线的 138–143 %（相对 `global` 从 62–64 % 到 88–92 %），`blacklist` 为 105–108 %（B/op 7,409 → 7,076）。黑名单仍占 `global` 的约 17 %：剩余代价是通用的阶段 A 提供者路径（懒超时仍开一个 `coroutineScope` 与 `async`），改它会动 K13 的超时语义，另开 PR。数字见 `docs/perf/e2e-slice/README.md`。

---

## 5. 第 6 阶段：控制面（RFC 0007）

RFC 0007 已于 2026-10-26 确认（#1028）。PR-2（由文档格式描述生成 JSON schema）已合入（#1038）：此后每个改动文档格式的 PR 都要同时改格式描述并运行
`generateSchemas`，`verifySchemas` 在 `check` 中强制。PR-1（`control/` 模块，`ControlBoundaryTest` 守护不引用 Spring、Reactor、Redis 客户端）已合入（#1032）。2026-10-26 起：门槛 D 与 PR-10 取消；PR-11 并入切换；
PR-9 只做库与 HTTP，starter 装配移进切换（RFC 0008 §4.1）。
2026-10-26 修订（路线图审查）：PR-6 的分析部分（`GrantAnalyzer`、模式包含 N7、RFC 0007 §7.4）拆成 **PR-6a** 现在开工，依赖 RFC 0005 PR-2（#1036）、PR-3（#1040）都已合入；接线留在 PR-6（RFC 0008 §4.5）。

PR-5（#1071，`feat(control): validate, compile and stage changes`）：变更请求与状态机、带 `ifMatch` 的条目级操作、校验 V1–V3、按平台的 `limits` 与 `attributes` 严格编译（N3）、每个文档一个暂存槽、变基、发布前重查、声明式 `apply` 与回滚；`ChangeRequest` 与 `Stage` 缓存，`PublishCompileTest`、`ControlFailClosedTest`，`CTL-1`、`CTL-6`、`CTL-7` 场景。PR-4 已合入（#1064）。PR-6 已合入（#1076，见下）。PR-3（边界）已合入（#1075，见下）。

PR-7（#1080，已合入）：候选文档在 `candidate:` 命名空间，`CandidateSource` 带 LRU；`ShadowListener` 按窗口记录结果（`SAME`、`WIDENED`、`NARROWED`、`BECAME_INDETERMINATE`、`CANDIDATE_INDETERMINATE`、`CANDIDATE_ERROR`、`BY_CHANGED`、`BASE_MISMATCH`），比较时构造新的视图、从不触碰执法的视图；样本不带请求头、token 与属性值；`ShadowReportSink`/`ShadowReportReader` 的内存与 CoCache 实现（读失败抛出，故障不当作"没有样本"）；`Changes.shadowed` 的窗口判定（`CANDIDATE_ERROR` 立即以阻塞关闭，最短时长与最少样本，24 小时以"样本不足"关闭）与发布者按数字确认；break-glass 没有窗口；只读的 `propagation`。新规则 `CTL-10` 至 `CTL-13`。

PR-8（#1087，已合入）：变更审计（`ChangeEvent`、`ChangeListener` 与 Kafka 实现，主题 `cosec-control-audit`；PUBLISHING 与 break-glass 事件先投递给 REQUIRED 监听器，失败即 503、不写）；经控制面的 break-glass（`BreakGlass`、`cosec:breakglass`、默认 4 小时、最长 24 小时、发布时再查期限、两人、必须有 REQUIRED 审计）；N6（`onMissingConfirmed`，只由控制面写，作者写进文档的确认被丢弃；只限平台的过渡规则删除，`ATR-3` 改为 `ATR-6`）；N1（租户经 `app = a` 的绑定授出应用目录的权限，`TNT-4`）；`cosec-migrate` 的 `bootstrap`、`partition plan` 与经 `ControlStore` 的 `publish`。`BRK-3` 改为已实现，新增 `CTL-14`。待办：break-glass 绑定的元数据（PR-3 之后）与 `EXPIRE` 回收。

PR-6a（`feat(engine,control): analyze grants against the grantor's own rights`）：`engine/` 的模式包含与不相交判断（N7，`PatternContainment`，A7）、
`control/` 的 `GrantAnalyzer`（§7.4.1 拆片段、§7.4.3 算法、§7.4.4 收窄与 `protected`）、A5、A6、A8、§7.4.6 每一行的单元测试与 `GRT-*` 场景（`control` 级 runner 断言分析结果）。
`GRT-1` 改为已实现；`GRT-2` 的"送审批"一半随 PR-6 的接线实现，仍为 `pending`。分析的输入 `GrantSnapshot`/`GrantDocument` 由接线部分从 RFC 0004 PR-4 的文档构造。

PR-4（`feat(control,cocache): store revisions and publish documents with putIfMatch`）：`control/` 的 `ControlStore` SPI 与 `Revisions`
（§5.4 的写入顺序：记录先于头、`putIfMatch(expected = 基准摘要)`、冲突不自动变基；修订索引与沿 `parent` 链重建、`verifyChain`、回滚候选、§5.7 的保留与回收与租约、
§5.8 的反向引用索引与"被引用的策略不能删除"）、`InMemoryControlStore`；`cosec-cocache` 的 `CoCacheControlStore` 与四个 `storeOfRecord` 缓存（`CacheTtlPolicyTest` 覆盖）；
`schema: 2` 的文档头增加可选的 `provenance`（RFC 0008 Q5：不加号），格式描述与 `schema/` 随之更新；`CTL-2` 改为已实现，新增 `CTL-4`、`CTL-5`。
§16.3 列给 PR-4 的 `storage.md` 随 S5 一起写（RFC 0008 §4.4.1：S5 之前线上文档描述 7.x）。变更请求、暂存槽与发布流程是 PR-5。

PR-6（#1076，`feat(control): authorize control-plane writes and require approvals`）：§7.1 的 `cosec:*` 动作与 `doc/…`、`scope/…` 资源（`ControlActions`、
`ControlResources`）；§7.3 的三条内置策略 `cosec:platform-admin`、`cosec:tenant-admin`、`cosec:tenant-viewer` 像 `cosec:root` 一样由引擎注入平台文档；
§7.2 的授权：`Changes` 的每一步都带 `Operator`，先过不经策略的租户隔离硬性规则（`TenantIsolation`），再对每个目标经宿主引擎检查
（`PipelineControlAuthorizer`：`AuthorizationChecker.decide` 在 `(cosec-control, 默认空间)` 中求值，检查前经 `DocumentConfirmation` 确认文档是当前的），
拒绝为 403、无法判定为 503，什么都不记录；§7.4 的接线：干净的候选按当前头构造 `GrantSnapshot`（`GrantDocuments`），作者的 UNKNOWN 片段记为
`escalations`；§7.5 的审批：窗口关闭后有 escalation 的变更进入 `AWAITING_APPROVAL`，由作者以外、持有 `cosec:approve` 的人批准候选摘要，72 小时有效，
`four-eyes` 让信任根的变更也要第二个人，break-glass 绑定总是两人；`publish` 以发布者的身份在当前头上重做授权与分析，批准不再有效时回到
`AWAITING_APPROVAL`、不写头。`TNT-3`、`GRT-2` 改为已实现，新增 `CTL-8`、`CTL-9`、`APR-1` 至 `APR-4`。文档格式还没有 `protected` 标志，
`cosec:unprotect` 的检查已接好、暂时不会触发（随格式变更的 PR 加入）。

PR-3（#1075，`feat(engine,core): enforce platform boundaries`，已合入）：平台文档的 `boundaries.default` 与标记 `"boundary": true` 的策略、租户文档的 `boundary`（编解码、格式描述与 schema 随之更新）；边界只在平台文档、只含 ALLOW、至多 64 条语句、不能被附加或绑定，平台租户没有边界；引擎在 ALLOW 之后把 Permit 限制在租户的有效边界（租户自己的，否则平台默认）内，没有语句命中为 `Deny(StatementRef.Boundary)`，边界语句出错为 `Indeterminate(BoundaryUnavailable)`，命名的边界不存在为 `Indeterminate(MissingPolicies)`；检查只能把 Permit 变成 Deny 或 Indeterminate；没有任何边界数据时整段检查跳过（`SnapshotView.bounded` 每个视图算一次，`boundary = none` 每个 Permit 只多读一个字段）；公开的 `cosec-api` `AuditReasonCode` 新增 `BOUNDARY`（下游的穷尽 `when` 需要加分支）；控制面新增 `Operation.SetBoundary`（应用文档拒绝），需要 `cosec:boundary`（`doc/tenant/<t>/boundary` 或 `doc/platform`，内置的 `cosec:tenant-admin` 不持有），信任根以外的任何边界改动都按 escalation 处理、要第二个人审批；租户的 `boundary` 进入反向引用索引。`BDY-1` 改为已实现，P-B1–P-B3 在 `FlatEnginePropertyTest`。**Q24 已接受**：CI 的 JMH A/B（[run 40477072342](https://github.com/Ahoo-Wang/CoSec/actions/runs/40477072342)，三轮），`boundary = none` 相对 `main`，`global` 100.9 %、`none` 101.0 %、`role` 99.2 %、`role-heavy` 100.5 %，B/op 没有增加；`role` 在共享机器的误差内，但低于 100 % 线，由 S1 的空闲机器测量复核。数字见 `docs/perf/rfc-0007-boundary/`。

PR-9（#1091，`feat(control): expose the control plane as a library and over HTTP`，已合入）：`ControlPlane`/`DefaultControlPlane`（`read`、`history`、`diff`、`propose`、`apply`、`rollback`、`rebase`、`startShadow`、`skipShadow`、`approve`、`reject`、`abandon`、`publish`、`get`、`breakGlass`、`simulate`、`explain`、`analyze`、`propagation`），每次调用都经 `ChangeAuthority`，硬性规则在先；`Simulator` 用执法流程自己的部件（`ScopeResolver`、数据面的 `PolicyDocumentCompiler`、`CoSecFlatEngine.evaluate` 的完整 trace），没有第二个求值器（`CTL-3` 改为已实现）；`explain` 按 `SnapshotVersion` 决策，已不保留的修订为 410；决策与 trace 只在调用者能读该文档时点名语句，否则为 `redacted`，`analyze` 只列可读文档并统计隐藏与不透明的数量；`ControlHttp`（不依赖 Web 框架，`ControlBoundaryTest`）实现 RFC 0007 §10.2 的路由，每个路由要求管道放行的 `RequestSecurity`，否则 401；问题类型沿用 RFC 0006 的词汇，加上 `not-found`、`conflict`、`gone`、`precondition-failed`、`unprocessable`、`precondition-required`，401/403/404/500/503 不带 `detail`；AOT：控制面不需要提示，`ControlBoundaryTest` 新增规则，禁止反射式 Jackson 绑定、`Class.forName`、`ServiceLoader` 与 `kotlin.reflect.full`。§14.2 的延迟记在 `docs/perf/rfc-0007-control/README.md`，都在预算内（Redis、0.95 MB 租户文档：`propose` p99 125 ms，`publish` 端到端 p99 313 ms；8 MB 文档未测）。starter 的路由注册与 `controlSupport` 在 S1。`cosec:shadow-read` 已定义，暂无路由使用。

#1095（`fix(control): round-1 review fixes for the control plane`，已合入）：**K1（高）**：变更的 `kind` 由入口决定，不再由请求体决定：`Changes.propose` 只接受 `PUBLISH` 与 `BREAK_GLASS`，`POST /changes` 带 `kind` 字段为 400，`ROLLBACK` 只由 `rollback()` 设置，`MIGRATE`/`BOOTSTRAP` 只存在于 `cosec-migrate` 直接走 `Revisions` 的路径；此前持有写、授权、发布三个权限的操作员可以用 `MIGRATE`/`BOOTSTRAP` 创建可用的 `cosec:root` break-glass 绑定，绕过 `cosec:breakglass`、REQUIRED 变更审计与影子窗口，租户管理员也能借此绕过保留名检查。现在所有 break-glass 规则都按内容判定（`BreakGlassRules.touches`：变更放入 break-glass 绑定，或在其上放入另一个绑定），V5、期限上限、保留名检查对任何 kind 都不豁免，把 break-glass 绑定改成普通绑定也算 break-glass 变更；移除 break-glass 绑定仍只缩小范围、只需 `cosec:write`。**K2（中）**：所有以变更 id 为参数的步骤先过 `Changes.visible`，对不可读的变更与不存在的变更给出同一个 `404 "no change <id>"`（可读 = 租户隔离加该文档的 `cosec:read`，或是变更的作者）；隔离拒绝不再点名文档；没有 `cosec:read` 的人现在得到 404 而不是 403。**K3**：`Approval` 记录批准人的成员关系与属性，发布时重新对当前文档检查批准人的 `cosec:approve`，失败或无法判定则回到 `AWAITING_APPROVAL`；已知限制：成员关系取批准时的快照，IdP 中撤销的角色要等批准过期（`approvalExpireAfter`）才被发现；runtime 新增 `RequestSecurity.asPrincipal(principal, memberships)`。**K4**：`nomatch` 确认的语句摘要包含策略的条件（`StatementDigest.of(statement, policyCondition)`），改变条件即丢弃确认，没有策略条件时摘要不变。新规则 `BRK-5`、`CTL-15`；`ControlReviewFixesTest`。运维要求：影子报告的 key（`{prefix}:shadow:report:{changeId}@{ms}`、`{prefix}:shadow:status:instances`；第 2 轮审查更正了此前写错的 `cosec:…:shadow-report`）里的数据按原样被信任（`ShadowWindows` 直接求和），只有数据面实例可以写这些 key，用 Redis ACL 强制；数据面凭据对 `snapshot:*`、`candidate:*`、`control:*` 只读。第 2 轮审查的跟进 #1110（已合入）把报告按实例分 key（`{prefix}:shadow:report:{changeId}@{ms}:{instance}`，索引 `{prefix}:shadow:reporters:{changeId}@{ms}`），计数为负即失败，任一实例有 `CANDIDATE_ERROR` 即阻止，缺报告的实例使窗口保持打开；数据面不需要访问 `control:*`；回滚要求 `cosec:read`，永远不带回 break-glass；live break-glass 绑定授出的策略冻结；可读性无法判定的变更答 503；`RequestSecurity.asPrincipal` 改为内部（新规则 `CTL-16`–`CTL-18`、`APR-5`、`BRK-6`、`BRK-7`，见 RFC 0007 §5.6、§6.4、§8 的实现说明）。

---

## 6. 规范一致性套件与切换（RFC 0008）

| PR | 内容 | 依赖 | 状态 |
|---|---|---|---|
| RFC 0008 PR-1 | `conformance/` 模块、规则登记表、`verifyRuleCoverage`、已合入规则的正反例场景、`propertyStress` | — | ✅ 已合入（#1045） |
| RFC 0008 PR-2 | 变异体补丁与 `mutationCheck` | RFC 0008 PR-1 ✓ | ✅ 已合入（#1086）：`conformance/mutants/` 的 16 个变异体全部被杀死，`./gradlew :conformance:mutationCheck` 手动运行（`-Pmutants=` 选子集），变异体不再适用或不再编译也算失败；`tnt-1`、`brk-4` 原先只被引擎测试杀死，#1093 为它们补了场景（`TNT-1`、`BRK-4`），现在只靠 `:conformance:test` 即被杀死；平台边界（`BDY-1`）的变异体在 #1075 合入后仍未补 |
| RFC 0008 PR-3 | 从 `v7.0.1` 录 7.0 基线，作为 JMH 绝对预算的对照 | — | ✅ 已合入（#1065）：JMH 基线已录（[`docs/perf/rfc-0008-baseline`](perf/rfc-0008-baseline/README.md)），端到端待补（k6） |
| 端到端切片 | `e2e/` 测试模块：快照 → 扁平引擎 → 协程 Pipeline → WebFlux 桥接，真实 CoCache 与 Redis；第一次 JMH/k6 测量（RFC 0008 §4.4.3） | RFC 0005 PR-4 ✓、RFC 0004 PR-4 ✓、PR-4b ✓、RFC 0006 PR-2 ✓ | 已合入（#1056）：快照到 Pipeline 一段与第一次 JMH 测量（`anonymous` 只有 7.0 的 61 %）；计时器修复（#1058）后四个场景为 7.0 的 196–314 %，见 `docs/perf/e2e-slice/`；WebFlux 桥接随 RFC 0006 PR-7（#1070）合入；k6 端到端测量未做；#1073（已合入）测量黑名单与决策监听器经切片的代价，#1096 据此优化 BEST_EFFORT 的交接与阶段 A 黑名单；CI 的 JMH A/B 工作流（#1084 加入，#1090 把默认值调到 1 fork、2 次预热、3 次测量并在运行前估算时长、超过 80 分钟即失败、超时 90 分钟、去掉串行的 concurrency 组，#1094 给作业加 Redis 服务使 `e2e` 与 `cosec-cocache` 的基准能跑）可作首次信号，S1 的门槛仍在空闲机器上测 |
| ~~RFC 0008 PR-4~~ | ~~切换~~：2026-10-26 拆成 S1–S5（RFC 0008 §4.4.1） | — | — |
| S1a | `feat(starter): add the 8.0 auto-configuration`（RFC 0008 §4.4 的 S1 拆分，2026-10-27 批准）：加法，可以 revert；8.0 的自动配置类**不**登记进 `AutoConfiguration.imports`，由测试与端到端切片显式加载；没有开关，也没有 7.x 脚手架 | 其余全部实现 PR、端到端切片 | ✅ 已合入（#1097） |
| S1b | `refactor!: wire the 8.0 path in the starter`：登记 8.0 的自动配置，删除 7.x 的自动配置、L1、L2、L5 的属性与 `init-repository`/`force-refresh`；`cosec-gateway-server` 与 `k8s/` 改到 8.0；`aot-smoke` 作业；不可回退点，带 RFC 0008 §3.9 第 1–5 条的证据 | S1a ✓；§3.9 第 1–5 条 | ✅ 已合入（#1104，`7467cc18`，2026-10-27）。§3.9 证据（head `c2753727`）：CI 全绿；没有 `pending` 规则（`SNP-1`/`SNP-2`/`SNP-4` 为 `timing`）；百万例 `property-stress.yml` [40640041416](https://github.com/Ahoo-Wang/CoSec/actions/runs/40640041416) 全部通过；`mutationCheck` 21/21；`jmh-v7-v8.yml` [40640046212](https://github.com/Ahoo-Wang/CoSec/actions/runs/40640046212) 7 个场景全部 PASS（授权 364–601 %，Pipeline 126–219 %） |
| S2–S4 | 一个删除 PR（2026-10-27 合并，RFC 0008 §4.4.1）：`refactor!: remove the 7.x storage, decision path, contracts and matchers`，按提交依次删除存储与写入路径（`cosec-cocache`、RFC 0007 X1–X3）、7.x 决策路径（`cosec-core`、`engine/` 按层引擎、适配器、装饰器链）、7.x 契约与匹配器（`cosec-api`、OGNL/SpEL、序列化器） | S1 | ✅ 已合入（#1114，`04c9f65e`，2026-10-28）：删除 42,080 行、新增 1,400 行；`migrate/` 需要的 7.x 解码搬进 `migrate.v7`（内部，`ognl` 为 `compileOnly`，缺失时 `convert` 给出原因而不是崩溃）；8.0 代码用到的常量与函数搬进 8.0 的类，行为不变；`CoSecModule` 只剩 `PolicyType` 序列化与 `Scope` 拒绝（`SCP-5`）；`mutationCheck` 21/21；完成 §4.6 的 L1、L2（`cosec-cocache` 部分）、L6、L7、L8（`migrate/` 之外）、L9、L15、L18（目录条目保留给 `migrate` 的 `compileOnly`） |
| S5 | 文档：`wiki/`、`skills/`、`schema/`、AGENTS.md、`upgrading.md` | 删除 PR 合入后写完并合入 | 进行中（2026-10-28 派发） |
| RFC 0008 PR-5、PR-6 | RC 前清理：按 RFC 0008 §4.6 的清单删除遗留代码、构建配置与文档中的版本沿革，加入守护 G1–G7；清单清空（"保留"项除外）是 RC 前提 | S5；RFC 0008 §6 Q9–Q15 ✓（2026-10-26 批准） | 等 S5（L17、L31 可以提前） |
| RC 安全审查 | 集成系统的威胁模型审查：多租户隔离、控制面越权与授出分析、break-glass、快照与缓存投毒、problem+json 泄露、DoS；发现全部在 RC 前修复（RFC 0008 §3.9） | S1 后开始；PR-5、PR-6 后做最后一遍 | 未开工 |

**S1 拆成 S1a 与 S1b**（RFC 0008 §4.4.1，2026-10-27 批准）：S1a（#1097）是加法，可以 revert：starter 装配 8.0 路径的全部自动配置类，但不登记。内容：`Pipeline(PipelineComponents(...))`（JWT 校验加 CoCache 的吊销读接口，开启吊销而没有读接口则启动失败；API Key；成员关系、属性、`DecisionListeners`、限流器；`cosec.authorization.routes.location` 的路由映射，网关不加载，位置缺失则启动失败；空位置表示有意不用路由映射）、`DocumentSnapshots.source`（平台缺失 fail closed，`CoSecCacheSpecs.pinPolicySnapshot` 固定缓存并把 `localMaxAge` 协调成 `cosec.authorization.snapshot.local-max-age` 一个值，`noeviction` 启动检查即 `SNP-3`）、N3（`PlatformCompiler`：平台的 `limits`/`attributes` 改变时重新编译已装入的应用与租户文档）、WebMvc/WebFlux/Gateway 过滤器、日志/Kafka 审计、指标与追踪集成、`controlSupport` 特性变体（`CoCacheControlStore`、`Revisions`、`Changes`、`DefaultControlPlane`、`ControlHttp`、`PipelineControlAuthorizer` 用独立的快照源并在每次检查前强制读取确认，`cosec.control.http.base-path` 下的函数式路由，`explain`/`simulate` 走执法的同一个引擎）、actuator 的 `cosecServiceGenerator/{routes|app}`；端到端切片改由 starter 装配，并新增"没有平台文档时每个请求都是 unavailable"的场景。`RequestSecurity.of(SecurityContext)`（runtime）只对管道自己构造的上下文返回放行的安全信息，其余一律 401。新属性见 `cosec.pipeline.*`、`cosec.authorization.{scope,membership,attributes,rate-limit,routes,snapshot}.*`、`cosec.authentication.revocation.skew`、`cosec.audit.required.timeout`、`cosec.audit.kafka.delivery`、`cosec.control.*`（含 `cosec.authorization.snapshot.key-prefix`，默认 `cosec`，是对 RFC 属性列表的补充）。`cosec.jwt.token-revocation.enabled=false` 时吊销读接口保持 `NONE` 并在启动时警告；S1b 让吊销缓存随 `cacheSupport` 无条件开启。

**一致性登记表新增 `startup` 级别**（#1097）：与 `build` 一样，`startup` 规则由它们所列的测试守护，没有场景在该级别运行（`RuleCoverageTest` 有对应测试）。`SNP-3` 改为 `startup` 并不再 `pending`，守护为 `CacheTtlPolicyTest` ×2 与 `CoSecPipelineAutoConfigurationTest` 中"Redis 可能淘汰"的用例；`SCP-4` 增加守护 `WebAdapterParityTest#the three adapters answer byte for byte alike`。仍为 `pending` 的 4 条：`SNP-1`、`SNP-2`（快照时序，已有测试与性质守护，应归入一个时序级别）、`ACT-1`（写着 RFC 0005 PR-4）、`PIP-1`（写着 RFC 0006 PR-7），后两条指向已合入的 PR；#1097 的作者打算在 S1b 一并处理，除非维护者另有决定。

**S1b 的阻塞项**（2026-10-27）：(1) 三个过时的变异体（`atr-3`、`brk-4`、`pip-2`，#1075 改了它们打补丁的代码）已改写，都能重新应用（#1103，已转为可审查并开启自动合并）；(2) `cosec-core` 的百万例运行把原生线程耗尽（在本地观察到）。读代码找到的原因：`SnapshotSource.close()` 不等自己的线程，关闭后排队的工作仍在跑。#1103 的修复让 `close()` 最多等 5 秒，并有去掉修复就失败的确定性测试；百万例运行只在手动触发的 CI 工作流 `.github/workflows/property-stress.yml`（#1099）上进行，不在本机跑；(3) 性能：在 CI 上对比 v7 与 v8（新的手动工作流 `jmh-v7-v8.yml`，同一台 runner 上交替运行 7.0.x 与 8.0），每个场景 v8 不低于 v7 即通过；空闲机器上的 JMH 与 k6 不再是门槛（2026-10-27 维护者决定）。§3.9 第 1–5 条的证据在 S1b 自己的 head 上重跑：`property-stress.yml` 的百万例运行、`:conformance:mutationCheck`、`jmh-v7-v8.yml` 的对比，运行链接附在 S1b 的 PR 上。S1b 的草稿 PR 是 #1104。

取消的 PR：RFC 0002 PR-6、RFC 0003 PR-5、RFC 0004 PR-6、RFC 0005 PR-7、RFC 0006 PR-8、RFC 0007 PR-10，以及第 2、3 阶段的门槛与门槛 A–D。
并入切换的 PR：RFC 0003 PR-6、RFC 0004 PR-7/PR-8、RFC 0005 PR-8/PR-9、RFC 0006 PR-9/PR-10、RFC 0007 PR-11（分到 S1–S5 的哪一个，见 RFC 0008 §4.4.2）。

PR-1 的登记表最初是 `conformance/src/test/resources/rules.json`，有 64 条规则：34 条已有场景（`SCP`、`MEM`、`BND`、`BLK`、`BRK`、`ALG`、`TNT-1`、`CND`、`ANA`，
共 107 个正反例），30 条为 `pending`，各自写明实现它的 PR。#1093 把它按族拆成 `conformance/src/test/resources/rules/<FAMILY>.json`（23 个族文件，加登记表范围的 `rules/_meta.json`），拆分时共 96 条规则，每条与原来逐字节相同；`Corpus.rules()` 在规则出现在别族的文件里、或族文件除 `rules` 之外还有别的内容时失败；`propertyStress` 经 `providers.fileContents` 读取全部族文件，改动任何一个都会使配置缓存失效。每个实现 PR 把它实现的规则改为 `"pending": null` 并补上正例与反例，否则
`:conformance:check` 失败；切换 PR 要求 `pending` 为空（RFC 0008 §3.9）。

---

## 7. 进行中

**2026-10-28 状态**（S1b 之后的修复批 #1118–#1125 与 S5 文档 #1131 均已合入）：

| PR | 内容 | 状态 |
|---|---|---|
| #1127–#1130 | 快照就绪与监听器排空（RFC 0003 X6、RFC 0006 X8）、8.0 策略测试工具、CND-5 对所有属性引用拒绝凭据类请求头（含 Unicode 折叠绕过）、testkit 按平台的限制与属性目录编译 | ✅ 已合入 |
| #1136、#1137 | RFC 0008 PR-5：删除 8.0 路径不再使用的代码（扩大范围）；去掉无用依赖 | ✅ 已合入 |
| #1138 | RFC 0010 C1–C3：义务按穷尽 `when` 分派（`OBL-3`，构建级）；控制面 API 在 `{base-path}/v1`；`CoSec-Identity` 接受任何认证方式名 | ✅ 已合入 |
| #1139 | RFC 0011 PR-1：模块分组目录，项目名去掉 `cosec-` 前缀 | ✅ 已合入 |
| #1146、#1147 | CI：Codecov 自 #1139 起读到 0 %（上传路径过期，上传器转而搜到配置缓存文件）；CI 重构为一个按目录组推导的矩阵（`scripts/ci/plan.py`）、单一门禁 `CI`、覆盖率由各组上传合并、CI 上测试不走构建缓存；每次推送的 runner 时间 4384 s → 1141 s。必需检查改为 `CI`、`Analyze (java-kotlin)`、`Semantic PR Title`（此前 Architecture、Conformance 等守护测试不是必需检查） | ✅ 已合入 |
| #1141 | RFC 0011 PR-2：`spi` 模块、拆散 `core/`、starter 按类路径装配（STA-1：缺 Redis 模板或 REQUIRED 的 Kafka 审计即启动失败）、§5 的例外规则 | ✅ 已合入 |
| #1144 | RFC 0009 PR-1（`fix(jwt)!`）：拒绝带 `act` 的 token，验证与刷新两条路径（`IDN-4`，两个变异体） | ✅ 已合入 |
| #1149 | RFC 0009 PR-3（`feat!`）：API Key 只有角色上限、成员关系决定权限（有上限时不取主体绑定，break-glass 只给 CoSec 自己的 token）、`migrate api-keys`（默认原地转换或每把 key 一个专用主体）；顺带修复成员关系缓存用写入填充、下一次请求不是本地命中；`ApiKeys.hash` 改用 `HexFormat`（API Key 吞吐 +84 %） | ✅ 已合入（两轮独立审查） |

| #1151 | RFC 0008 PR-6：文档守卫 G4（`pnpm check:docs` 拒绝已删除的 API），文档检查进入 `CI` 门禁 | ✅ 已合入 |
| #1155 | RFC 0011 PR-3：ABI 校验（KGP 内置 `abiValidation`，22 个库模块的转储），`@ExperimentalCoSecApi`、`@CoSecSpi`、`SpiMarkerTest`；收窄公共面（cocache 编解码器、快照内部、`RequestGate` 只能由 CoSec 实现、控制面的执法 bean 不可替换） | ✅ 已合入 |
| v8.0.0-alpha.1 | 预览版，不承诺兼容性：GitHub prerelease、Maven Central、GitHub Packages、Docker 只推 `8.0.0-alpha.1`；`main` 回到 `8.0.0-SNAPSHOT`（#1156、#1157） | ✅ 2026-10-29 发布 |

| #1159、#1164 | RC 审查第 1 轮（`docs/security/8.0-rc-review.md`，49 项，4 项高危）与修复批次 A：上限只去掉 ALLOW（RC1-1）、break-glass 按来源、认证加固 | ✅ 已合入 |
| #1167 | `k8s/` 改为 Kustomize（`base` + `dev`/`production` overlay），pod 加固，CI 校验清单 | ✅ 已合入 |
| #1169 | `build!`：Java 21 基线（镜像仍运行在 JDK 25） | ✅ 已合入 |
| #1170 | `refactor!`：CoSec 自己的 `IdGenerator`，CosId 移到可选扩展 `cosec-cosid`（CosId 5.0.0-alpha.2） | ✅ 已合入 |
| #1171 | `AuthorizationChecker.decide` 返回决定 | ✅ 已合入 |
| #1172、#1173 | 未知属性检查忽略 Kubernetes 服务链接变量 | ✅ 已合入 |
| v8.0.0-alpha.2 | 预览版，不承诺兼容性（#1174）：GitHub prerelease、Maven Central（含 `cosec-cosid`）、GitHub Packages、Docker 只推 `8.0.0-alpha.2`；发布后 `main` 保持 `8.0.0-alpha.2`，不回到 SNAPSHOT（维护者 2026-10-29） | ✅ 2026-10-29 发布 |

接下来：RC 修复批次 B（授出分析与控制面，RC1-2/3/4 高危）→ 批次 C（网关，含身份转发按 scheme 选择加入）→ 批次 D（DoS、快照、泄露，含 Lettuce `SharedLock` 观察项）→ 第 2 轮（按模块）→ `cosec-engine`/`cosec-runtime` 公共 API 归类与 testkit 夹具 → RC。

2026-10-27 第二批合入：#1109（`jmh-v7-v8.yml` 用 `:cosec-engine` 的项目名；第一次运行因此失败）、#1111（`config/logback-parity.xml` 关掉 `SnapshotSource` 的日志：`SnapshotInstallOrderPropertyTest` 故意注入存储失败，每次一条带栈的 WARN，百万例运行写满了 runner 的磁盘；本地 2000 例从 626 MB 降到 16 KB）、#1110（控制面第 2 轮审查跟进 R1–R6，见 §5）。#1101、#1103、#1105 已合入。

- **百万例 `property-stress.yml` 在 `main` 上全部通过**：`model`、`engine`、`runtime`、`control`（[40628433863](https://github.com/Ahoo-Wang/CoSec/actions/runs/40628433863)），`cosec-core`（#1111 之后重跑，[40635399876](https://github.com/Ahoo-Wang/CoSec/actions/runs/40635399876)）。S1b 的证据在它自己的最终 head 上再跑一次。
- #1108（`fix(runtime)`，N3 重新编译覆盖加载中的槽位，审查发现 S4）：按旧平台编译的加载不安装，无法重新编译即 fail closed 并按退避重试；新规则 `SNP-4`（`pending`，S1b 把它移到 `timing`）；变异体拆成 4 个，全部被杀死。已合入。
- **S1b 已合入**（#1104，`7467cc18`）：8.0 路径是 starter 唯一装配的路径，证据见 §6。S2–S4 的删除 PR 已合入（#1114，见 §6）。S5 文档进行中，之后 RFC 0008 PR-5/PR-6 与 RC 安全审查。
- 后续项（#1114 审查）：`SnapshotConsistencyPropertyTest`（SNP-1）的视图由测试内的副本拼装，补一个经生产 `DocumentSnapshotViews`、在并发重新发布下的 runtime 级变体；`migrate/` 改用不需要匹配器的读取器，去掉 `OgnlSandbox` 与 `ognl`（L8 余项）。
- 观察项（再失败就按"可能放行 / 失效通知丢失"调查，不只重跑）：`PathTemplateValueTest` 的 LEGACY 限流偶发放行（应为 429）；`RedisSnapshotDocumentStoreTest#passesOnInvalidationsOfItsNamespaceFromEveryInstance`（REDIS）在 #1108 的 Codecov 作业失败一次。
- 后续项：影子报告索引改为只增的 Redis SET（`SADD`，ACL 只放行 `SADD`/`SMEMBERS`），关闭"在控制面第一次看到前被删出索引"的缺口，需要 starter 传入 Redis 客户端（S1b 之后）；`ACT-1`、`BDY-1` 的变异体。
- 后续项（#1144 审查）：`Jwts.toPrincipal` 遇到非数组的 `roles`/`policies` 声明时 NPE，表现为 500（已是 fail closed），改为 `TokenVerificationException`（401）。
- 后续项（#1149 审查）：未知主体的短期负缓存（被拒的请求不受限流，已删除主体的 key 每次都问提供者）；`RedisApiKeyReader` 的本地缓存（现在每个 API Key 请求都读 Redis）；签发 API Key 本身经授权（现在能写 key 存储的人可以为任何主体签发）。
- 后续项（CI，2026-10-29）：main 上的 Docker 作业推送阿里云镜像仓库时多次挂住，撞上 30 分钟超时（ghcr 几秒完成），连带 AOT 冒烟被跳过。把阿里云镜像同步拆成单独的非阻塞作业（在 ghcr 推送与 AOT 冒烟之后，失败不影响 main 的状态）。
- RC 前（#1155 审查）：把 `cosec-engine` 与 `cosec-runtime` 的每个公共声明归为"文档化的 API"或 `@InternalCoSecApi`，以 wiki 写到的为准；GA 冻结 ABI 转储之前完成，否则之后收窄就是破坏性变更。
- RC 前（Wow 的导入清单，2026-10-29）：Wow 用到的生产面（`SecurityContext`、`SecurityScope`、`CoSecPrincipal`、WebFlux 的上下文读取、`AuthorizationChecker`、`RouteMap` 与 `RouteMapCodec`、`CoSecParameters`、starter 的属性键）保持公开并有文档；测试面用到的内部类型（`PipelineComponents`、`SuspendingSnapshotSource`、`engine.flat.*`）在归类时多半转为 `@InternalCoSecApi`，在此之前 `cosec-testkit` 提供公开的夹具（从文档构建真实 Pipeline，给出 `evaluate` 与已放行的 `SecurityContext`，`@ExperimentalCoSecApi`）。
- RC 前（Wow 集成时遇到的问题，2026-10-29）：`cosec-migrate` 的发行包（`application` 插件的 `distZip`）作为 release 资产发布，并写明以 Kubernetes Job 运行；手写文档的播种走 `bootstrap` 加控制面 `apply`（不给 `publish` 加 `--no-report`：报告证明字节的来源），文档给出这条路径；`HostScope` 的按路由 app 即 RC1-6（批次 C）；macOS 上 Lettuce 的 kqueue 扩展 keepalive 启动失败写进排障；网关镜像的内存预期（堆、直接内存、ZGC）写进运维文档，并在批次 C 重新评估 512M 直接内存的默认值。`CoSec-Identity` v1 的字节视为稳定：任何改动都升 `v`。
- alpha.2 前：未知 `cosec.*` 属性检查把 Kubernetes 的服务链接环境变量（Service 名为 `cosec-gateway` 时的 `COSEC_GATEWAY_SERVICE_HOST`、`COSEC_GATEWAY_PORT_80_TCP_*` 等）当成 CoSec 属性，网关在 Kubernetes 中启动失败循环（Wow 的 GA E2E 发现）。修复：只在系统环境变量来源中忽略服务链接的形状，其余 `COSEC_*` 照常检查；`k8s/` 清单加 `enableServiceLinks: false`；排障文档。
- **alpha.2 的内容**：RC 修复批次 A（#1164）、Java 21 基线、CoSec 自己的 `IdGenerator` 与 `cosec-cosid`（CosId 5.0.0-alpha.2）、`AuthorizationChecker` 返回决定、上面的 Kubernetes 启动修复。发布等维护者确认。
- #1106、#1107 草稿只作保存，不合入。
- 协调过程：同一时间只运行一个子会话（共享机器的 CPU，维护者 2026-10-27）；CI 上的运行不受此限。

（#866 是 Renovate 的 pnpm 升级，不属于路线图。）

## 8. 等待维护者的决定

| 事项 | 背景 | 协调者的建议 |
|---|---|---|
| 何时发 8.0 RC / GA | RFC 0001 §6：由维护者决定 | 等 RC 前提全部满足后再议 |

## 9. 维护者决定（2026-10-26 起）

聊天中作出的过程决定。设计决定留在 RFC 里，本节只指路。

| 决定 | 日期 | 记在哪里 |
|---|---|---|
| **7.x 中发现的 bug** 先记入台账，不立即修；8.0 的速度优先。外部人员可利用的漏洞仍立即私下报告给维护者。台账由协调者保管，不在仓库中：条目可能涉及安全细节 | 2026-10-26 | 协调者的台账（不在仓库） |
| **S1 之前性能低于预算**不阻止合入：记录数字，随后开优化 PR。硬性门槛仍是 S1 | 2026-10-26 | [RFC 0008 §3.8](rfc/0008-conformance-and-direct-build.md) |
| **只用于测试的依赖**，若主流、有人维护且在 Maven Central，预先批准，且绝不进入运行时 classpath；PR 必须写明这一点。运行时依赖仍需维护者批准 | 2026-10-26 | 仅记在本文；PR 描述写明（依赖版本仍只在 `gradle/libs.versions.toml`） |
| **Renovate/Dependabot**：wiki、Actions 与构建工具的 patch/minor 升级在全绿时合入；运行时库的升级等维护者 | 2026-10-26 | 仅记在本文 |
| **端到端压测工具**用 k6，不用 wrk2（wrk2 不在 Homebrew core） | 2026-10-26 | [RFC 0008 §3.8](rfc/0008-conformance-and-direct-build.md) |
| **`migrate/` 的运行时依赖** `spring-web` 与 `spring-expression`：批准，使 `convert` 能解码 7.x 的路径动作与 `#{...}` 值 | 2026-10-26 | #1067、#1069 |
| **提交信息**用英文，不加 Co-authored-by 尾注 | 2026-10-26 | 仅记在本文 |
| **`main` 的分支保护**要求合入前分支是最新的 | 2026-10-26 | [RFC 0008 §4.7](rfc/0008-conformance-and-direct-build.md) |
| **共享文档只由协调者修改**：`docs/roadmap.md`、`docs/rfc/*.md` 与 wiki 路线图（中英文）。实现 PR 不改它们，把需要的更新写在 PR 描述的 "For the coordinator: shared-doc updates" 一节，协调者每批合入后统一更新。与改动直接相关的文档（`docs/perf/`、模块的 wiki 页面、`skills/`、`schema/`）仍随改动的 PR | 2026-10-27 | 仅记在本文 |
| **Q24：没有边界适用时平台边界整段跳过**（`SnapshotView.bounded`），接受 CI 上 `boundary = none` 的 A/B 结果（`global` 100.9 %、`none` 101.0 %、`role` 99.2 %、`role-heavy` 100.5 %，B/op 不增）；`role` 低于 100 % 线但在共享机器的误差内，由 S1 的空闲机器测量复核 | 2026-10-27 | [RFC 0007 Q24](rfc/0007-control-plane.md)、`docs/perf/rfc-0007-boundary/` |
| **S1 拆成 S1a 与 S1b**：S1a 加法、不登记、可 revert；S1b 注册并删除 7.x，是不可回退点，带 §3.9 的证据 | 2026-10-27 | [RFC 0008 §4.4.1](rfc/0008-conformance-and-direct-build.md) |
| **`cosec.authorization.snapshot.eviction-check=fail\|warn` 两个值都保留，默认 `fail`**：`warn` 给禁用 `CONFIG GET` 的托管 Redis（如 ElastiCache），启动时打印以 `!!!` 开头的 WARN，说明被淘汰的应用或租户文档读起来是缺失，它的 DENY 会悄悄失效；平台文档缺失 fail closed 不受此影响。S1b 的文档要写进 configuration 与 troubleshooting | 2026-10-27 | #1097 |
| **空的 `cosec.authorization.routes.location` 表示有意不用路由映射**；默认位置缺失仍使启动失败。**`cosec.authorization.snapshot.key-prefix`**（默认 `cosec`）是对 RFC 属性列表的补充 | 2026-10-27 | #1097 |
| **性能门槛改为 CI 上的 v7/v8 对比**：S1b 的性能证据是 CI 工作流 `jmh-v7-v8.yml` 的运行（同一台 runner 上交替运行 7.0.x 与 8.0），每个场景 v8 不低于 v7 即通过；不再要求空闲机器上的 JMH 与 k6。百万例 `propertyStress` 是正确性证据，保留，在 CI 上跑 | 2026-10-27 | [RFC 0008 §3.8](rfc/0008-conformance-and-direct-build.md) |
| **不在本机跑压力测试**：S1b 的百万例 `propertyStress` 只在手动触发的 CI 工作流 `.github/workflows/property-stress.yml` 上运行（`gh workflow run property-stress.yml -f ref=<branch>`，#1099），运行链接附在 S1b 的 PR 上，这是 §3.9 的证据 | 2026-10-27 | #1099、`AGENTS.md` 的 Feedback loop |
| **迭代效率**：本地只跑改动模块的测试与 detekt，完整的多模块 `check` 由 CI 跑（合入仍要求 CI 全绿）；空闲机器只留给 JMH；常规的性能前后对比用 CI 的 JMH A/B 工作流（`jmh-ab.yml`，#1084），S1 的门槛仍在空闲机器上测；Gradle 构建缓存、配置缓存与并行构建（#1085）；机械性任务用较便宜的模型 | 2026-10-27 | 仅记在本文；`AGENTS.md` 的 Feedback loop |
| **合并冲突逐块解决**，共享文档从不整文件取一边（#1076 曾因此回退 RFC 0008 与路线图，#1077 恢复） | 2026-10-27 | 仅记在本文 |
| **同一时间只运行一个子会话**：协调者派发的会话串行，避免与其他会话抢占共享机器的 CPU；CI 上的运行不受限 | 2026-10-27 | 仅记在本文 |
| **影子报告索引**先用"控制面持久化见过的实例"的方案，不为只增 SET 改动 S1b；信任模型写进 `wiki/operations/storage.md` | 2026-10-27 | #1110、RFC 0007 §6.4 |
| **S1b 合入授权**：证据（#1104 CI 全绿、`mutationCheck` 全部杀死、S1b head 上的百万例 `property-stress.yml`、`jmh-v7-v8.yml` 每个场景 v8 ≥ v7）齐备即由协调者合入；任何一项不满足就停下报告 | 2026-10-27 | #1104 |
| **S2–S4 合并为一个删除 PR**，按提交分组审查；S5 独立，在它之后 | 2026-10-27 | [RFC 0008 §4.4.1](rfc/0008-conformance-and-direct-build.md) |
| **8.0 发布前 SPI 与公共 API 不考虑兼容**，不需要维护者确认；以完美目标架构为先：高内聚、低耦合、代码清洁、可扩展、高性能、安全、易用。仍需确认：运行时依赖版本与发版 | 2026-10-28 | 仅记在本文 |
| **默认按协调者推荐推进并合入**；需要维护者的事项登记在 §8 | 2026-10-28 | 仅记在本文 |
| **网关身份传递取代 7.x 注入模式**：下游在可信内网，不复制密钥；`CoSec-Identity` v1 只含 `v`、`tenant`、`sub`、`scope`、当前作用域的生效 `roles`、`auth{method,jti,exp}`、`iat`、`rid` 与白名单 `claims`；匿名不发；网关始终清理入站的同名头；`forwardAuthorization` 默认 false | 2026-10-28 | #1124 |
| **`cosec-testkit` 作为独立发布模块**（部分推翻 RFC 0008 Q11：核心不依赖 JUnit），第一版只做引擎级场景 | 2026-10-28 | [RFC 0008 §6 Q11](rfc/0008-conformance-and-direct-build.md) |
| **租户文档的限流变更不需要额外审批**：计数器按文档隔离（`OBL-2`），平台规则叠加生效，租户只影响自己 | 2026-10-28 | #1122 |
| **模块目录统一去掉 `cosec-` 前缀**，与 `model/`、`engine/` 一致；Gradle 项目名与 Maven 坐标保持 `cosec-*` | 2026-10-28 | #1132、AGENTS.md |
| **死代码清理按引用判断**：8.0 路径上没有 `main` 引用、不是扩展点、文档不教的代码一律删除，G 系列守卫防回潮（RFC 0008 PR-5 扩大范围） | 2026-10-28 | [RFC 0008](rfc/0008-conformance-and-direct-build.md) PR-5 |
| **身份与凭据分层**：主体 / 凭据 / 委托；API Key 不携带授予、只能收窄；`principal.type`、`auth.method` 为属性；拒绝带 `act` 的 token；委托放到 8.1 | 2026-10-28 | [RFC 0009](rfc/0009-identity-and-credentials.md) |
| **GA 前只做"不做会静默放行"和"几乎零成本"的改动**，不为未来功能预先抽象公共 SPI；企业网关场景逐项检查后只剩三项（未知义务即拒绝、控制面 API 版本独立于 `base-path`、`auth.method` 开放取值） | 2026-10-28 | [RFC 0010](rfc/0010-ga-extension-points.md) |
| **模块架构：六边形分层与目录分组，GA 前完成**（取代当天早些时候的两条草案：`domain/application/adapters` 分组、`json` 与 `document` 模块）：`api`/`core`/`backends`/`edge`/`extensions`/`host`/`test`/`bom`/`platform` 分组（`platform/gateway-server` 是给用户直接部署的网关服务）；新增 `spi`，拆散 `core/` 模块；项目名去掉 `cosec-` 前缀；starter 按类路径装配并在缺模块时启动失败；`runtime` 可用 Jackson 但不进公共签名；RC 时启用 ABI 校验 | 2026-10-28 | [RFC 0011](rfc/0011-module-architecture.md) |
| **API Key 上限只留角色，迁移不扩大任何凭据**（#1149 安全审查）：有上限时不取主体绑定，API Key 不取 break-glass，上限必须显式写出，`MembershipProvider` 的未知主体是显式分支；迁移默认原地转换或每把 key 一个专用工作负载主体，并集授予原主体须显式选择 | 2026-10-29 | [RFC 0009](rfc/0009-identity-and-credentials.md) 修订、Q6、Q7 |
| **运行时依赖的 patch 升级**：Spring Boot 4.1.1 → 4.1.2、Spring Cloud 2025.1.3 → 2025.1.4（#1116），`ognl` 3.4.16 → 3.4.17（#1115，只剩 `migrate` 的 `compileOnly`） | 2026-10-29 | 维护者："升级" |
| **先发布 `8.0.0-alpha.1` 预览版，不承诺兼容性**：RFC 0011 PR-3（ABI 校验）合入后发布；GitHub 上标为 prerelease，Docker 只推 `8.0.0-alpha.1` 标签（不推 `latest`）；发布后 `main` 回到 `8.0.0-SNAPSHOT`。RC/GA 的前提不变（取代 RFC 0001 §6 "RC 前不发布任何版本"） | 2026-10-29 | 维护者："完成后，保守一点，发布 -alpha.1 ，不承诺兼容性。" |
| **RC 的两轮审查改为对 RC 候选提交做整体审查**：第 1 轮是 RC 安全审查，第 2 轮是按模块的正确性审查（每个模块一个只读审查者），不再逐个补做已合入 PR 的第二轮 | 2026-10-29 | [RFC 0008 §3.9](rfc/0008-conformance-and-direct-build.md) 修订；维护者："Go with that" |
| **核心层不再依赖 CosId，CosId 作为扩展**：核心层只需要"生成唯一字符串"，改为 CoSec 自己的端口 `IdGenerator`（`cosec-spi`，`@CoSecSpi`）与默认实现 `RandomIdGenerator`；新增扩展模块 `cosec-cosid`（`extensions/cosid`），基于 CosId `5.0.0-alpha.2` 实现 `IdGenerator`，由 starter 按类路径装配；`me.ahoo.cosid` 只出现在 `extensions/cosid` 与 `host/spring-boot`（架构规则）；`cosec-dependencies` 不再导入 `cosid-bom`。社交登录的 OAuth `state` 与 API Key 密钥始终用 SecureRandom，不经 `IdGenerator`。起因：CosId 5 与 CosId 4 二进制不兼容，Wow 的服务在运行时 `NoClassDefFoundError` | 2026-10-29 | 维护者："核心层不再依赖 CosId"；"添加一个 SPI 给 IdGenerator，cosid 作为扩展。并且 cosid 依赖升级到最新版：v5.0.0-alpha.2" |
| **CoSec 8.0 的基线升到 Java 21**（7.x 不变）：全部模块按 Java 21 编译，CI 与发布用 JDK 21；CosId 5 只支持 Java 21，`cosec-cosid` 因此是普通模块，RFC 0011 D2 不需要例外。破坏性变更，写进 `upgrading.md` | 2026-10-29 | 维护者："CoSec 基线升级到 JAVA 21 吧。" |
| **`AuthorizationChecker` 返回决定**（RC 前）：与 `check` 同语义，`check` 即"决定为 Permit"；决定只到进程内的宿主代码，不进响应体 | 2026-10-29 | Wow 的请求 |
| **网关身份转发改为按 scheme 选择加入**（RC1-49）：默认只转发给 `lb://` 路由，其他路由须写 `cosec-identity: true`；网关主机上暴露非 health 的 actuator 端点时启动失败（RC1-48） | 2026-10-29 | [RC 审查](security/8.0-rc-review.md) 的建议 |

## 10. 尚未派发的下一步

- **2026-10-28 起的顺序**：RFC 0008 PR-5（死代码清理，扩大范围）→ [RFC 0010](rfc/0010-ga-extension-points.md) 的 C1–C3（一个 PR）→ [RFC 0011](rfc/0011-module-architecture.md) PR-1、PR-2 → [RFC 0009](rfc/0009-identity-and-credentials.md) PR-1、PR-3（PR-2 移到 8.1）→ RFC 0008 PR-6 → RFC 0011 PR-3（RC 前）→ RC 前提。

可以穿插进行（不在主线上）：

- 端到端的 k6 测量（RFC 0008 §4.4.3），对照 7.0 基线；8.0 的 k6 在 S1b 之后。
- 阶段 A 提供者路径的懒超时（`coroutineScope` 加 `async`，#1096 的遗留）：动 K13 的超时语义，单独一个 PR。
- 核对已合入 PR 的两轮审查（RFC 0008 §4.7：同一领域先审查，再开新功能 PR）：RFC 0005 PR-5（#1079）、RFC 0006 PR-5（#1078）的第 1 轮，RFC 0007 PR-3 至 PR-9 的第 1 轮（#1095 已修复 PR-4 至 PR-9 的第 1 轮发现，其中 K1 为高危），之前合入、只做过第 1 轮的 PR 的第 2 轮，#1097 的审查；各表中"—"表示尚无已合入的修复 PR。
