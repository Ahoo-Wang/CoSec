# RFC 0005：条件 AST、动作词汇与编译执行（RFC 0001 迁移第 4 阶段，下篇）

> 状态：已确认（第 2 版，§15 / §11 的决定已于 2026-10-22 由维护者确认）· 上位文档：[RFC 0001](0001-target-architecture.md) P6、P10、§3.3、§3.10、§6 第 4 阶段、§7.1 ·
> 姊妹篇：[RFC 0004](0004-unified-model.md)（第 4 阶段上篇：绑定、租户分层、转换工具、门槛方法），本文以它为前提 ·
> 基线：`main` @ `a361084d`（第 1 版写于 `334da022`） · 交付：全部合入 `main`（8.0 线），不发布任何版本 ·
> 范围：`model/`、`engine/`、`cosec-core`、`cosec-openapi`、`cosec-gateway`、`cosec-webflux`、`cosec-webmvc`、`migrate/`
>
> **2026-10-26 修订（[RFC 0008](0008-conformance-and-direct-build.md)）**：8.0 不考虑兼容性。门槛 B（§8）与 PR-7 取消；PR-8、PR-9 并入 RFC 0008 的切换 PR；`legacy` 节点（§3.7、Q2）不实现，
> 自定义条件匹配器在转换时被拒绝，迁移到 RFC 0006 的 `AttributeProvider`（RFC 0008 §6 Q4）。§9.3 中对 7.0 的 100 % 吞吐要求（Q12）作为绝对预算保留，
> 对 `legacy` 语言的相对目标取消。A1–A4 作为规范性质保留。下文保留原样以便追溯。

## 0. 结论先行

RFC 0004 统一了"一条语句对谁、在哪里生效"。本文统一"一条语句匹配什么"，并让引擎不再线性扫描：

- **条件 AST**（§3）取代全部内置条件匹配器、OGNL 和 SpEL。运算符固定为 RFC 0001 §7.1 确认的九个：`eq`、`in`、`prefix`、`cidr`、`time`、`exists`、
  `and`、`or`、`not`。每个属性有静态类型，编译时检查；每个条件的成本在编译时有上界；没有正则、没有循环、没有方法调用、没有字符串拼接。
- **"本来就可能没有的属性"不是错误**（§3.4）：比较为假，`exists` 为假。RFC 0001 的 `onMissing` 只用于第 5 阶段 `AttributeProvider` 提供的外部属性。
- **可分析**（§4）：从任意条件中抽取索引键（可靠的上近似，索引因此不会漏掉语句），以及一个可靠但不完备的包含判断 `implies(c1, c2)`，供第 6 阶段的
  不能越权授权检查使用；判断不了的一律视为"不包含"。
- **动作**（§5）：规范形式是 `service:verb` 加带类型的资源；HTTP 是一个映射。`http` 命名空间（`http:GET` + 规范化路径）原样承载 7.x 的全部路径动作，
  供 Gateway 和不做映射的服务使用；服务内的路由到动作映射由 `cosec-openapi` 根据 OpenAPI 生成。
- **编译执行与索引**（§6）：动作按命名空间分区，`http` 路径用按段的前缀树，`service:verb` 用哈希；运行时才能确定的模式和不可分析的语句进"总是候选"桶。
  索引的完整性（候选集 ⊇ 暴力匹配的命中集）由性质测试守护。
- **迁移**（§7）：转换工具只做**精确**的翻译，翻译不了的拒绝并报告位置，不做近似。自定义条件匹配器经一个不可分析的 `legacy` 节点过渡到第 5 阶段（§3.7）。
- **门槛 B**（§8）沿用 RFC 0004 §9 的方法：未归类差异为零。改动目录只有三类，都只收紧或修复缺陷。
- 路径模板在 AST 中变成"捕获变量 + `eq`"，代入的值永远不会改变模式（§2 J9）。

---

## 1. 范围与非目标

### 1.1 要做的

1. `model/` 中的条件 AST、属性目录、类型系统、成本模型（§3）。
2. 可分析性：索引键抽取与包含判断（§4）。
3. 动作与资源模型、`http` 命名空间及纯 Kotlin 的路径模式实现、OpenAPI 路由映射（§5）。
4. 编译器与索引（§6）：`PolicyDocument (JSON) → AST → 类型检查 → CompiledStatement`，按命名空间分区的索引。
5. 转换工具的匹配器翻译，包括限流规则（§7）。
6. 门槛 B（§8）。
7. 删除 OGNL、SpEL、全部内置匹配器、`ActionMatcherFactory`/`ConditionMatcherFactory` SPI、`PartExtractor`、`StatementScopedContext`、
   `RuleGroup` 一类的适配契约、`HostFacts`。

### 1.2 非目标

| 内容 | 处理 | 留到 |
|---|---|---|
| `AttributeProvider`、`onMissing` 的执行 | 本文定义 `onMissing` 的语法和语义（§3.4）；本阶段没有外部属性，编译器拒绝写了 `onMissing` 的语句 | 第 5 阶段 |
| 限流成为义务 | 本文只翻译限流规则的匹配部分（§7.3），执行方式不变 | 第 5 阶段 |
| 不能越权授权检查本身 | 本文提供它需要的 `implies`（§4.2） | 第 6 阶段 |
| CEL | 不引入（RFC 0001 §7.1） | 将来确有需要时，作为不可分析的可选类型 |
| schema 从模型生成 | 手写 `schema/condition.schema.json`、`action.schema.json` 的新版本 | 第 6 阶段 |

---

## 2. RFC 0001 中与本文有关的不一致

| # | RFC 0001 原文 | 问题 | 本文的处理 |
|---|---|---|---|
| J1 | §3.3：动作模式编译进"路由前缀树"；§4：`cosec-engine` 只依赖 model；§3.10：引擎不用反射 | 7.x 的路径动作是 Spring 的 `PathPattern`（`spring-web`），引擎不能依赖它；`PathPattern` 还支持 `{var:regex}`，而 `java.util.regex` 不在 `EnginePurityTest` 的白名单里，正则也无法静态限定成本 | 引擎内用纯 Kotlin 实现 `HttpPathPattern`，语义与 `PathPattern` 在一个明确的子集上相同，用性质测试对照 Spring 的实现；子集之外的模式（`{var:regex}`、非 `/` 分隔符）转换时拒绝（§5.2）。Q1 |
| J2 | §3.3："不再为每种匹配器写一个 `ConditionMatcherFactory`"；§3.5：阻塞的自定义匹配器由第 5 阶段的 `AttributeProvider` 取代 | 第 4 阶段删除工厂，第 5 阶段才有替代品，自定义匹配器在中间一个阶段无处可去（RFC 0004 §3 I13） | 保留不可分析的 `legacy` 节点，承载已注册的自定义 `ConditionMatcherFactory`，第 5 阶段删除（§3.7）。Q2 |
| J3 | §3.10：OGNL/SpEL"在 AST 落地时标记为 deprecated，之后删除" | 8.0 不考虑兼容，也没有任何中间版本发布，"先 deprecated 再删除"没有使用者 | 切换 PR 直接删除；转换工具拒绝 OGNL/SpEL 条件并报告位置（§7.1）。Q3 |
| J4 | §6 第 5 阶段：限流成为义务 | 7.x 的限流规则用的就是动作匹配器、条件匹配器和 part 键（[`RateLimitRule`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/limiter/RateLimitRule.kt)）。本文删除匹配器，限流规则就无法加载 | 本文把限流规则的匹配部分一并翻译成 AST（§7.3），执行方式留到第 5 阶段。Q4 |
| J5 | §7.1：运算符包含 `time`；P1：引擎无 I/O | 7.x 的 `time` 条件读时钟（[`TimeConditionMatcher`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/TimeConditionMatcher.kt)），而 `EnginePurityTest` 禁止引擎读时钟 | `time` 读 `environment.time`，由调用方在请求开始时取一次（RFC 0004 §4.1）。决策因此可重放（回放用审计事件的时间戳） |
| J6 | §7.1：运算符集合固定 | 7.x 的 `endsWith`、`contains`、`regular` 在策略里很常见（例如按来源域名后缀放行），集合里没有对应的运算符 | 不加运算符。提供一个派生属性 `*.host.labels`（反转的域名标签，§3.2），使"域名后缀"可以写成 `prefix`；其余拒绝转换（§3.6）。Q5 |
| J7 | §7.1："按 action 键精确查找，索引快且不会漏" | 只对 `service:verb` 成立。`http` 命名空间的路径模式（`**`、`{var}`、模板）不能按键精确查找 | 按命名空间分区：`service:verb` 哈希，`http` 按段前缀树，模板和不可分析的进"总是候选"桶（§6.2） |
| J8 | §3.3 `onMissing` | 见 RFC 0004 §3 I7 | §3.4 |
| J9 | （7.x 现状，非 RFC 0001）`#{...}` 路径模板在求值后才成为模式的一部分 | 模板不可分析，也无法建索引 | 路径模板在 AST 中变成"捕获变量 + `eq`"（§7.2），代入的值只参与相等比较，永远不会改变模式。Q6 |

---

## 3. 条件 AST

### 3.1 语法

条件是一个 JSON 对象，只有一个键（运算符）：

```jsonc
{ "and": [
    { "eq": ["subject.authenticated", true] },
    { "in": ["scope.space", ["team-a", "team-b"]] },
    { "not": { "cidr": ["environment.ip", ["10.0.0.0/8"]] } },
    { "eq": ["resource.var.ownerId", { "attr": "subject.id" }] },
    { "time": { "notAfter": "2026-12-31T00:00:00Z", "zone": "Asia/Shanghai", "daysOfWeek": ["MON", "FRI"], "from": "09:00", "to": "18:00" } }
] }
```

| 运算符 | 形式 | 含义 |
|---|---|---|
| `eq` | `[属性, 字面量 \| {"attr": 属性}]`，可选 `"ignoreCase": true` | 相等。右边可以是另一个属性（取代 7.x 的 `#{...}` 模板） |
| `in` | `[属性, [字面量...]]`，或 `[字面量, 集合属性]` | 前者：属性值在字面量集合中；后者：集合属性含该字面量（例如 `["admin", "scope.roles"]`） |
| `prefix` | `[属性, 字面量]`，可选 `ignoreCase` | 字符串前缀；对 `*.labels` 属性是标签前缀（§3.2） |
| `cidr` | `[IP 属性, [CIDR...]]` | IP 在任一网段内（IPv4 / IPv6） |
| `time` | 与 7.x `time` 条件相同的配置 | 读 `environment.time`（§2 J5）；语义与 7.x 逐条相同 |
| `exists` | `属性` | 属性存在（§3.4） |
| `and`、`or` | `[条件...]`，至少一个 | 短路求值，从左到右 |
| `not` | `条件` | 取反 |

没有 `true`/`false` 字面量条件：省略 `condition` 即恒真（7.x 的 `all`）；恒假的条件没有用处。

### 3.2 属性与类型

属性是一个固定目录里的点分路径，类型在编译时确定。目录之外的路径是编译错误（7.x 的 `DefaultPartExtractor.isSupported` 也在加载时拒绝未知 part）。

| 属性 | 类型 | 来源 | 7.x 对应 |
|---|---|---|---|
| `subject.id` | `String` | 凭证 | `context.principal.id` |
| `subject.tenant` | `String` | 凭证 | `context.tenantId` |
| `subject.authenticated` | `Bool` | 凭证 | `authenticated` 条件 |
| `subject.roles` | `Set<String>` | 成员关系：**任何空间**里的角色（裸角色 id） | `inRole` 的 DENY 用法（§7.1） |
| `subject.scopedRoles` | `Set<String>` | 成员关系，写法与 token 声明相同：默认空间是 `role`，其他空间是 `role@space` | 带空间的 `inRole` 的 DENY 用法（§7.1） |
| `subject.attributes.<name>` | `String` | 凭证（token 的 `attributes` 声明，值按 7.x 的 `toString()` 规则转成字符串） | `context.principal.attributes.<name>` |
| `scope.tenant`、`scope.app`、`scope.space` | `String` | `ScopeResolver` | `context.tenantId`、`request.appId`、`request.spaceId` |
| `scope.roles` | `Set<String>` | 成员关系：**请求所在空间**里生效的角色 | `inRole` 的 ALLOW 用法（§7.1） |
| `action.service`、`action.verb` | `String` | 动作 | `request.method`（`http` 命名空间中 `verb` 即方法） |
| `resource.type`、`resource.id` | `String` | 资源 | `request.path`（`http` 命名空间中 `id` 即规范化路径） |
| `resource.attributes.<name>` | `String` | 资源属性（`CheckRequest.resource`、请求属性追加器） | `resource.<name>` |
| `resource.var.<name>` | `String` | 本语句的动作 / 资源模式捕获的变量（P6），只在同一语句的条件里可见 | `request.path.var.<name>` |
| `environment.ip` | `Ip` | 宿主（经 `RemoteIpResolver`） | `request.remoteIp` |
| `environment.time` | `Instant` | 调用方 | 时钟 |
| `environment.device` | `String` | 声明 | `request.deviceId` |
| `environment.blocked` | `Bool` | `BlacklistChecker`（RFC 0004 §4.7） | — |
| `http.header.<name>` | `String` | 请求（只在 `http` 宿主上存在） | `request.header.<name>` |
| `http.origin`、`http.referer` | `String` | 请求头 | `request.origin`、`request.referer` |
| `http.origin.host`、`http.referer.host` | `String` | 请求头 | `request.origin.host`、`request.referer.host` |
| `http.origin.host.labels`、`http.referer.host.labels` | `Labels` | 请求头，派生 | — |
| `request.attributes.<name>` | `String` | 请求属性追加器（如 `ipRegion`、Gateway 的 `routeId`） | `request.attributes.<name>` |

- `Labels` 是反转后的域名标签序列：`api.example.com` 是 `[com, example, api]`。`prefix` 作用于它时按整标签比较，所以
  `{"prefix": ["http.origin.host.labels", "com.example"]}` 匹配 `example.com` 和它的任何子域，不匹配 `badexample.com`。这是 7.x `endsWith`
  最常见用法的精确、可分析替代（§2 J6）；7.x 的 `endsWith: ".example.com"` 恰好不匹配 `example.com` 本身，转换工具据此生成 `and(prefix(labels), not(eq(host)))`（§7.1）。
- `Ip` 在请求解析时规范化（IPv4 映射的 IPv6 地址归为 IPv4）；无法解析的 `remoteIp` 使 `environment.ip` 不存在。
- 类型规则：`eq` 两边类型相同；`in` 的字面量类型与属性相同，或者字面量与集合属性的元素类型相同；`prefix` 只用于 `String` 和 `Labels`；`cidr` 只用于 `Ip`；
  `ignoreCase` 只用于 `String`（ASCII 折叠，字面量在编译时折叠一次）。违反即编译错误，错误信息指出 JSON 路径。
- `Bool` 字面量只能是 JSON 的 `true`/`false`；不做隐式转换（7.x 把一切当字符串比较，`"true"` 与 `true` 相同，转换工具负责把字符串字面量转成对应类型，§7.1）。

### 3.3 求值

```
eval(c, req) : Boolean        // 不抛出：AST 求值中没有任何可能失败的操作
```

- 每个运算符都是全函数：属性取值是对 `AuthzRequest` 字段的直接读取或一次哈希查找；比较是 O(值长度)；`in` 对字面量集合是编译时构造的哈希集合；
  `cidr` 是编译时构造的前缀树。没有正则，没有超时，因此也没有 7.x 的 `RegexTimeoutException`（RFC 0002 §3.5 表中它映射为 403）。
- 因为不抛出，扁平算法（RFC 0004 §6.1）的 `ERROR` 只剩两个来源：`legacy` 节点（§3.7）和适配层违反契约。门槛 B 因此会看到一类"7.x 出错、8.0 不出错"的差异（§8 D3）。

### 3.4 不存在的属性

7.x 的 `PartExtractor` 对不存在的东西返回空串：没有的请求头、没有的属性、本语句没有捕获的路径变量，都读成 `""`。AST 区分"不存在"和"空串"：

| 运算符 | 属性不存在时 |
|---|---|
| `eq`、`in`（属性在左）、`prefix`、`cidr` | 假 |
| `in`（集合属性在右） | 假（不存在的集合视为空集） |
| `eq` 右边的 `{"attr": ...}` 不存在 | 假（两个都不存在也是假：不存在不等于任何值，包括另一个不存在） |
| `exists` | 假 |
| `not` | 对子条件的结果取反（二值逻辑，没有"未知"） |

**为什么不按 RFC 0001 §3.3 默认 `indeterminate`**（RFC 0004 §3 I7）：这些属性"不存在"是请求的正常状态，不是故障。`indeterminate` 会让每一个
不带某个可选请求头的请求在相关 DENY 上得到 500。

**`onMissing`**（第 5 阶段生效）：语句可以写 `"onMissing": "indeterminate" | "nomatch"`，只作用于外部属性（`AttributeProvider` 提供的、取值可能**失败**
的属性），默认 `indeterminate`；`nomatch` 用在 DENY 语句上时控制面要求显式确认（RFC 0001 §3.3）。本阶段没有外部属性，编译器拒绝在没有外部属性的语句上写
`onMissing`，避免写了却不起作用。

**`not` 与不存在**：`{"not": {"eq": ["http.header.X-Debug", "1"]}}` 在没有这个头时为真，与 7.x 的 `negate` 相同（7.x 读到 `""`，`eq` 为假，取反为真）。
转换工具只在字面量是 `""` 时需要改写（§7.1）。

### 3.5 成本上界

每个条件编译时计算成本，超限即编译错误：

| 限制 | 默认 | 理由 |
|---|---|---|
| 节点数 | ≤ 64 | 一条语句的条件就是几个合取项；64 足够容纳 7.x 中见过的最大 `bool` 嵌套（转换时测量，§7.4） |
| 深度 | ≤ 8 | 防止深层递归；求值是递归下降 |
| `in` 字面量数 | ≤ 1,024 | 哈希集合，常数时间；大名单属于黑名单这类外部数据，不该写进策略 |
| `cidr` 网段数 | ≤ 256 | 前缀树，O(32) / O(128) |
| 字面量长度 | ≤ 1 KB | `prefix`、`eq` 是 O(长度) |
| 每条语句的成本 | Σ 节点权重 ≤ 256（`and`/`or`/`not`/`exists` = 1，`eq`/`prefix` = 2，`in`/`cidr` = 4，`time` = 8） | 一次评估的成本上界 = 候选语句数 × 256，候选数由索引限定（§6） |

阈值是快照级的配置（`cosec.policy.limits.*`），控制面（第 6 阶段）在发布前检查；本阶段由 `SnapshotPublisher` 和转换工具检查。

### 3.6 不能再表达的

| 7.x 能写的 | 为什么去掉 | 替代 |
|---|---|---|
| `regular`（正则，默认忽略大小写，有时间预算） | 成本无法静态限定（只能靠运行时超时）；不能抽取索引键，也不能做包含判断 | 结构简单的正则由转换工具精确翻译（§7.1）；其余改写成 `eq`/`in`/`prefix`，或者把判断移到属性追加器里产生一个属性 |
| `contains` | 不能抽取索引键；包含判断只能靠子串关系 | `in` 列出值；或者属性追加器 |
| `endsWith` | 同上 | 域名用 `*.host.labels` 的 `prefix`；其余拒绝 |
| `path` 条件（对任意 part 做路径模式匹配） | 是第二套模式语言 | 作用于 `request.path` 且语句动作为 `all` 时，移进语句的资源模式（§7.1）；其余拒绝 |
| `ognl`、`spel` | 图灵完备，攻击面大（D6），需要反射（RFC 0001 §3.10） | 手工改写；转换工具报告每一处 |
| `#{...}` 模板（`eq` 的值、路径动作） | 求值结果成为模式或比较值的一部分，不可分析（§2 J9） | `eq` 右边的 `{"attr": ...}`；路径模板改为捕获变量 + `eq`（§7.2） |
| 自定义 `ConditionMatcherFactory` | 不可分析 | 本阶段经 `legacy` 节点过渡（§3.7）；第 5 阶段改为 `AttributeProvider` + AST |
| 自定义 `ActionMatcherFactory` | 动作必须可以建索引 | 映射到 `service:verb`（§5）；转换工具拒绝 |

### 3.7 `legacy` 节点（过渡到第 5 阶段）

> **2026-10-26：不实现**（[RFC 0008](0008-conformance-and-direct-build.md) §6 Q4）。只有一次切换时，`AttributeProvider` 与条件 AST 同时可用，不需要过渡。以下原文保留，仅供追溯。

```jsonc
{ "legacy": { "type": "geoFence", "config": { ... } } }   // type 是一个已注册的自定义 ConditionMatcherFactory
```

- 只能引用**自定义**工厂；内置类型（含 `ognl`、`spel`）一律不可用 `legacy` 承载。
- 不可分析：抽取索引键时视为"无约束"（§4.1），所以它所在的语句除动作之外不会被条件索引排除；包含判断时视为"不知道"。
- 求值经 RFC 0002 PR-3 的语句作用域上下文调用原匹配器；抛出的 `Exception` 是 `ERROR`（RFC 0004 §6.1：DENY 中 → 没有别的 DENY 命中时 `Indeterminate`；
  ALLOW 中 → 未命中）。`BlockingAware` 继续生效（第 5 阶段删除）。
- 带 `legacy` 节点的语句只能由平台层的文档发布（与 RFC 0001 §7.1 对 CEL 的约束相同）；转换工具把它们留在平台层，这正是方案 A 的默认（RFC 0004 §5.2）。
- 第 5 阶段 `AttributeProvider` 落地时删除 `legacy` 节点与 `ConditionMatcherFactory` SPI。

---

## 4. 可分析性

### 4.1 索引键抽取

对条件 `c` 和一个**可索引属性** `a`（`scope.app`、`scope.space`、`subject.tenant`、`resource.type`，以及在快照级配置里声明为可索引的 `resource.attributes.*`），
定义 `keys(c, a)`：一个字面量集合 `S`，满足"`c` 为真 ⇒ `a` 的值 ∈ `S`"；或者 `⊤`（无约束）。

```
keys(eq(a, lit), a)          = {lit}
keys(in(a, lits), a)         = lits
keys(and(c1..cn), a)         = ⋂ { keys(ci, a) | keys(ci, a) ≠ ⊤ }，全为 ⊤ 时为 ⊤
keys(or(c1..cn), a)          = ⊤ 若某个 keys(ci, a) = ⊤，否则 ⋃ keys(ci, a)
keys(not(_), a)              = ⊤
keys(legacy, a)              = ⊤
keys(其他, a)                = ⊤
```

- **可靠性**：`keys` 是上近似。按 `keys` 把语句放进索引（`S` 中的每个键各放一次，`⊤` 放进"任意值"桶），索引就不会漏掉任何可能命中的语句。
  `and` 取交集仍然可靠：交集中的每个约束都是 `c` 为真的必要条件。
- `eq` 加 `ignoreCase` 时键按折叠后的值；查找时对请求值同样折叠。
- 交集为空集说明条件不可满足：编译时给出警告（多半是写错了），语句仍然编译，只是不会进入任何候选集。

### 4.2 包含判断

第 6 阶段的不能越权授权检查要回答："被授出的语句是否被授权者自己的权限包含"（RFC 0001 §3.7、§7.1）。本文提供它的条件部分：

```
implies(c1, c2) : YES | UNKNOWN          // YES 表示：凡是满足 c1 的请求都满足 c2
```

规则（全部可靠；判断不了的返回 `UNKNOWN`，调用方把 `UNKNOWN` 当作"不包含"）：

1. **规范化**：展平嵌套的 `and`/`or`，去重，按规范顺序排序；`not(not(x)) → x`；字面量折叠 `ignoreCase`。
2. `c2` 是 `and(d1..dn)`：对每个 `di` 都有 `implies(c1, di) = YES` 时为 `YES`。
3. `c1` 是 `and(e1..em)`：存在某个 `ej` 使 `implies(ej, c2) = YES` 时为 `YES`。（RFC 0001 §7.1 的"授权者的条件 AND 额外条件"就是这一条。）
4. `c1` 是 `or(e1..em)`：对每个 `ej` 都有 `implies(ej, c2) = YES` 时为 `YES`。
5. `c2` 是 `or(d1..dn)`：存在某个 `di` 使 `implies(c1, di) = YES` 时为 `YES`。
6. 原子之间：
   - `eq(a, x) ⇒ eq(a, x)`；`eq(a, x) ⇒ in(a, S)` 若 `x ∈ S`；`in(a, S1) ⇒ in(a, S2)` 若 `S1 ⊆ S2`；
   - `eq(a, x) ⇒ prefix(a, p)` 若 `x` 以 `p` 开头；`prefix(a, p1) ⇒ prefix(a, p2)` 若 `p1` 以 `p2` 开头；`in(a, S) ⇒ prefix(a, p)` 若 `S` 中每个值都以 `p` 开头；
   - `cidr(a, N1) ⇒ cidr(a, N2)` 若 `N1` 的每个网段都被 `N2` 的某个网段包含；
   - `time(t1) ⇒ time(t2)`：只在 `t2` 只有 `notBefore`/`notAfter` 且 `t1` 的有效期落在其内时为 `YES`；含 `daysOfWeek`/`from`/`to` 的只在配置完全相同时为 `YES`；
   - 任何原子 `x ⇒ exists(a)` 若 `x` 是 `a` 上的 `eq`/`in`/`prefix`/`cidr`（这些在 `a` 不存在时为假，§3.4）；
   - `ignoreCase` 不同的两个原子：只有 `c1` 区分大小写、`c2` 不区分时，按折叠后的值比较。
7. 其余（含 `not`、`legacy`、属性之间的 `eq`）返回 `UNKNOWN`；但 `implies(x, x) = YES` 对任何规范化后相同的子树成立。

动作与资源的包含见 §5.5。完整的越权检查（语句的效果、动作、资源、条件四者合起来）在第 6 阶段的 RFC 中设计，本文只保证它需要的两个函数存在、可靠、并有性质测试。

### 4.3 性质测试

- **A1 `keys` 可靠**：随机条件与随机请求，`eval(c, req)` 为真时，对每个可索引属性 `a`，`keys(c, a) = ⊤` 或 `req.a ∈ keys(c, a)`。
- **A2 `implies` 可靠**：随机生成 `(c1, c2)` 和大量请求，`implies(c1, c2) = YES` 时，不存在 `eval(c1) ∧ ¬eval(c2)` 的请求。生成器偏向生成"`c1 = c2 AND 额外`"
  这类应当为 `YES` 的对，以测到 `YES` 分支，而不只是 `UNKNOWN`。
- **A3 规范化不改变语义**：`eval(normalize(c)) = eval(c)`。
- **A4 成本上界**：任意通过编译的条件，求值访问的节点数 ≤ 节点数上限。

---

## 5. 动作与资源

### 5.1 规范模型

```kotlin
class Action(val service: String, val verb: String)               // "order:cancel"；http 命名空间为 ("http", "GET")
class Resource(val type: String, val id: String, val attributes: Map<String, String>)   // ("order", "123", ...)；http 为 ("http:path", "/orders/123", ...)
```

语句写法：

```jsonc
{ "effect": "allow",
  "action":   ["order:read", "order:list"],            // 或 "order:*"，或 "*"
  "resource": ["order/{orderId}", "order/*"],           // 类型/模式；省略即任意资源
  "condition": { "eq": ["resource.var.orderId", { "attr": "subject.attributes.orderId" }] } }
```

- 动作模式：`service:verb`、`service:*`、`*`。不允许其他通配，所以动作的包含判断是平凡的。
- 资源模式：`type/段/段...`，段是字面量、`*`（一段）、`{name}`（一段并捕获）、末尾的 `**`（任意多段）。捕获的变量进 `resource.var.<name>`，只在本语句可见（P6）。
- `service` 与 `type` 的命名由 `Catalog` 声明（RFC 0004 §4.9）；控制面（第 6 阶段）据此校验，本阶段编译器只检查语法。

### 5.2 `http` 命名空间

7.x 的全部动作都是 HTTP 路径加方法，转换后落在 `http` 命名空间，语义不变：

| 7.x | `http` 命名空间 |
|---|---|
| `"action": "/orders/{id}"`（方法不限） | `"action": "http:*"`，`"resource": "http:path:/orders/{id}"` |
| `{"path": {"pattern": "/orders/**", "method": ["GET", "HEAD"]}}` | `"action": ["http:GET", "http:HEAD"]`，`"resource": "http:path:/orders/**"` |
| `"*"`、`all` | `"action": "*"`，省略 `resource` |
| 多个模式（数组、`composite`） | `resource` 数组（任一匹配） |
| `options.caseSensitive: false` | 资源模式上的 `"caseSensitive": false` |

**`HttpPathPattern`**（`engine/`，纯 Kotlin）：支持 Spring `PathPattern` 的这个子集：字面量段、`?`、段内 `*`、`{name}`、`{*name}`（剩余路径）、`**`，
以及 `caseSensitive`、`decodeAndParseSegments` 两个选项。语义以 `main` 所用 `spring-web` 版本的 `PathPattern` 为准，由一个性质测试守护：在 `cosec-core` 的测试中，
随机生成子集内的模式与路径（含编码字符、尾斜杠、空段、`..`），断言 `HttpPathPattern` 与 `PathPattern` 的匹配结果和捕获变量完全相同。

子集之外的模式在转换时拒绝（§7.2）：`{name:regex}`（正则，§2 J1）、非 `/` 的 `separator`。转换报告列出每一处，运维改写成 `{name}` 加条件，或者拆成多条语句。

**有意的差异**（PR-3 实现时对照 `spring-web` 7.0 确定；对照测试 `HttpPathPatternParityTest` 逐项断言"Spring 能编译、`HttpPathPattern` 拒绝"，除此之外两者对任何模式与路径的结果和捕获值都相同）。
全部是编译错误，即只会让转换失败，不会让匹配结果变宽或变窄：

| # | 模式 | Spring 的行为 | `HttpPathPattern` |
|---|---|---|---|
| P1 | `{name:regex}` | 正则捕获 | 拒绝（§2 J1） |
| P2 | 空模式 `""` | 匹配 `""` 与 `/` | 拒绝 |
| P3 | 只由 `*` 与捕获组成、因而能匹配空值的段（单独的 `*` 除外），如 `***`、`{a}{b}`、`*{a}` | 这种段会匹配分隔符：`/a/***/b` 匹配 `/a///b`；`/{a}{b}/x` 匹配 `///x` 时在捕获阶段抛 `ClassCastException` | 拒绝；加一个字面量字符或 `?` |
| P4 | `{}`、`{*}`（没有名字的捕获） | 捕获到名为 `""` 的变量 | 拒绝：条件无法引用 |
| P5 | `{*a}` 与 `{a}` 同名 | 允许，其中一个值丢失 | 拒绝（同名捕获一律拒绝） |

实现时确认的 Spring 7.0 语义（`HttpPathPattern` 照此实现）：`**` 与 `{*name}` 可以在模式开头（`/**/*.css`），此时其余部分匹配路径的末尾，
`{*name}` 捕获前面的部分；不解码（`decodeAndParseSegments: false`）时 `%2F`（仅大写）仍读作 `/`；大小写不敏感时，字面量段按 `String.equalsIgnoreCase` 比较，
只含 `?` 的段逐字符按 `Character.toLowerCase` 比较，含 `*` 或捕获的段（Spring 中是正则）只折叠 ASCII；段内的 `*`、`?` 按码点而不是 UTF-16 字符计。
§5.1 的资源类型写作 `http:path`，而 RFC 0004 的请求模型（`Resource`）中 HTTP 资源的类型是 `http`：PR-3 的 `http:path:` 模式匹配类型为 `http` 的资源，两者在 PR-4 接入时统一。

路径在 Pipeline 中规范化一次（RFC 0001 §3.5；路径规范化本身属于第 5 阶段的 Pipeline 重组），本阶段沿用 7.x 的 `RequestPaths` 规范化结果作为 `resource.id`。

### 5.3 OpenAPI 生成的路由映射

服务内嵌部署可以把 HTTP 路由映射到 `service:verb`，让策略不随 URL 重构而变（RFC 0001 §7.1）：

```jsonc
// cosec-routes.json，由 cosec-openapi 生成，随服务一起发布
{ "service": "order",
  "routes": [
    { "method": "GET",  "path": "/orders/{orderId}",        "action": "order:read",   "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" }
  ] }
```

- **生成规则**：每个 operation 一条；`action` = `<service>:<operationId>`，可以用扩展字段 `x-cosec-action` 覆盖；`resource` 默认 `<service>/<path 中最后一个变量>`（没有变量时为
  `<service>`），可以用 `x-cosec-resource` 覆盖。`service` 取 `info.title`（与 [`OpenAPIAppPermissionGenerator`](../../cosec-openapi/src/main/kotlin/me/ahoo/cosec/openapi/generator/OpenAPIAppPermissionGenerator.kt)
  取应用 id 的方式相同），可以用 `x-cosec-service` 覆盖。同一个 `operationId` 出现两次即生成失败。
- **宿主侧**：WebFlux / WebMvc 适配器加载映射（`cosec.authorization.routes.location`），对请求做一次路由查找（同样用 `HttpPathPattern`），得到 `Action` 与 `Resource`。
- **没有映射到的路由**：请求的动作是 `http:<METHOD>`、资源是 `http:path:<路径>`，即 `http` 命名空间。这不是降级：`http` 命名空间的策略照常生效，只有写成 `service:verb` 的策略不适用；
  所以 `service:verb` 的 ALLOW 不会意外覆盖到没有映射的路由，`http` 命名空间的 DENY 也不会因为加了映射而失效。
- **一个请求只有一个动作**：映射到了就是 `service:verb`，没有映射到就是 `http:`。同一个 deny 要同时覆盖两种写法时，写两条语句。`wiki/policies/language.md` 解释这一点。
- **Gateway** 不做映射：网关看到的是外部路由，一律用 `http` 命名空间。
- `OpenAPIPolicyGenerator`、`OpenAPIAppPermissionGenerator` 改为生成 `service:verb` 的权限策略和 `Catalog`，并同时生成路由映射。

> **实现说明（2026-10-27，#1079）**：路由映射在 `cosec-core`（`me.ahoo.cosec.route`），宿主一次加载全部映射，任何一个错误都整体失败，错误带位置与 JSON 路径（`RTE-3`）。
> 同一方法的两条路由只要有路径能同时匹配就拒绝，动作相同也一样（`RTE-1`）；只由字面量与 `{var}` 段组成的路径用精确规则判定不相交，其余用 `PatternContainment` 的可靠判定。
> 捕获值含 `/`（解码后的 `%2F`）在认证之前返回 400 `invalid-request`（`RTE-2`）。生成器是 `OpenAPIServiceGenerator`（路由映射与应用文档）；7.x 的生成器留到
> S1 把 actuator 的生成端点改接新生成器、S4 删除。`cosec.authorization.routes.location` 的装配属于 S1。

> **实现说明（2026-10-27，#1097）**：路由映射的装配在 S1a（未登记，S1b 登记）：WebMvc 与 WebFlux 从 `cosec.authorization.routes.location` 加载进 `Pipeline` 的 `routes`，网关不加载；默认位置缺失使启动失败，空的位置表示有意不用路由映射。actuator 的生成端点是 `cosecServiceGenerator/{routes|app}`（`OpenAPIServiceGenerator`），7.x 的生成端点留到 S1b 删除。

### 5.4 代码内检查

`AuthorizationChecker.check(context, CheckRequest(path, method))` 改为 `check(context, action, resource)`；原签名保留为 `http` 命名空间的便捷方法。
`wiki/integration/checks.md` 改写。

### 5.5 动作与资源的包含

供第 6 阶段使用：`service:verb ⊑ service:* ⊑ *`；资源模式按段比较，字面量 ⊑ `{x}` = `*` ⊑ `**`（只在末尾）；`http` 路径模式中含 `?` 或段内 `*`（如 `a*b`）的段只与完全相同的段比较，其余 `UNKNOWN`。

---

## 6. 编译执行与索引

### 6.1 编译

```
文档 JSON ──流式解析──> AST（model/）──类型检查、成本检查、规范化──> CompiledStatement（engine/）
                                                                     ├ 动作集合（按命名空间）
                                                                     ├ 资源模式（HttpPathPattern 或类型化段模式）
                                                                     ├ 条件求值树（sealed 节点，字面量已预处理：哈希集合、CIDR 前缀树、折叠后的字符串）
                                                                     └ 索引键（§4.1）
```

- 编译在 RFC 0003 的编译线程上，每个文档修订一次（RFC 0003 §4.3）。
- 求值树是 sealed 类层次加 `when` 分派，没有反射、没有 lambda 捕获请求状态。
- 路径变量：资源模式匹配返回捕获结果（一个按变量下标的小数组，模式编译时就知道有哪些变量），条件中的 `resource.var.<name>` 编译成下标读取。这就是 RFC 0001 P6
  的 `MatchResult(bindings)`：没有共享状态，`StatementScopedContext` 删除。

### 6.2 索引

一个编译后的文档（RFC 0004 §7）有一个语句索引：

| 分区 | 结构 | 查找 |
|---|---|---|
| `service:verb` | `HashMap<String, IntArray>`，键是 `service:verb` 与 `service:*` | 请求的动作查两次 |
| `http` | 按方法分组（含 `*`）的按段前缀树：模式的前导字面量段是树的边；遇到第一个非字面量段（`*`、`{x}`、`**`）就挂在该节点上 | 沿请求路径的段下降，收集经过的每个节点上挂的语句 |
| 资源类型（`service:verb` 语句） | 按 `resource.type` 的哈希，与动作的结果求交 | — |
| 可索引条件属性（§4.1） | 每个可索引属性一个 `值 → 语句` 的哈希，加一个"任意值"桶 | 只在语句数超过阈值（默认 256）的分区上启用；求交 |
| 总是候选 | 动作为 `*` 的语句、带 `legacy` 节点且动作也不可索引的语句 | 每次都在 |

候选集 = 索引结果 ∩ 适用的绑定（RFC 0004 §6.4）。实现上，索引给出语句下标的有序数组，适用的绑定给出"策略 → 经由的绑定"的映射，按 RFC 0004 §6.3 的候选顺序合并；
合并用访问者，不物化中间列表。

**实现说明（PR-4，2026-10-26）**。与上表的三处差别，都不改变决策：

- **索引属于策略，不属于文档。** 候选顺序（RFC 0004 §6.3）按绑定遍历，在绑定到达的策略上按语句顺序评估；所以每个被到达的策略、每个效果各一个索引，
  一次查找就是"索引结果 ∩ 适用的绑定"，不需要合并两者。平台策略被应用与租户文档引用时只有一份索引，平台更新不重新编译它们（RFC 0004 §3 I5）。
  一个策略某个效果的语句少于 `minStatements`（默认 8）时不建索引、按顺序扫描。
- **语句的匹配顺序固定**：动作 → 可索引属性的索引键（§4.1）→ 资源 → 策略级条件 → 语句条件，遇到第一个不成立的即停；资源匹配出错（路径无法解码）时
  仍看策略级条件，它不成立则未命中，否则出错，所以结果不取决于同一策略里先评估了哪些语句。索引只按动作、索引键和路径的字面前缀排除语句，
  这三者不调用宿主代码、也不会出错（路径无法解码时不按路径排除），所以排除一条语句与评估它得到同一结果；索引键覆盖条件读到的全部可索引属性，
  是语义的一部分，与索引参数无关，因此阈值、是否拆分（I2）在宿主违反 `ConditionInput` 契约时也不改变决策。
- **策略级条件惰性求值**：只在该策略的某条语句的动作、索引键匹配且资源匹配（或匹配出错）之后求值，每次评估至多一次（`ALG-7`）。

- **DENY 不能写裸类型**：资源模式 `order` 只匹配 id 为空的 `order`，`order/**` 才匹配全部；写在 DENY 里会让它覆盖的范围远小于字面意思，
  所以是编译错误（错误信息给出 `order/**` 的写法；只想拒绝 id 为空的资源时，加 `resource.id` 等于空串的条件）。ALLOW 写裸类型只会收窄，照常编译。
- **段内通配是线性的**：`HttpPathPattern` 判断一段是否匹配用双指针的通配匹配（失败时只回到最后一个 `*`），步数不超过记号数 × 段长；
  只有匹配成功、且条件读捕获变量时，才按正则的贪婪回溯取捕获值（#1040 第 1 轮审查 F4）。

可索引条件属性只能是引擎自己从请求读出的属性（`scope.*`、`subject.tenant`、`resource.type`、`resource.attributes.*`），读取不会失败。

### 6.3 完整性

**不变量**：对任意快照和请求，索引给出的候选集 ⊇ 暴力扫描（对适用绑定的全部语句逐条匹配动作、资源和条件）的命中集。

- **I1 索引完整**（性质测试）：随机快照（含 `http` 模式的全部子集语法、`service:verb`、可索引条件、`legacy` 节点）与随机请求，比较两者，且要求决策（含 `by`）相同。
  这是 RFC 0001 §3.3 要求的那个测试。
- **I2 顺序无关**：候选的访问顺序只由 RFC 0004 §6.3 决定，与索引的内部结构无关：对同一个快照用不同的索引参数（阈值、是否启用条件索引）编译，决策和 `by` 都相同。
- 编译器在开发构建中可以开启"影子暴力扫描"（`cosec.engine.verify-index=true`，只用于测试），每次评估同时跑暴力扫描并在不一致时抛出。

### 6.4 引擎接口

`Engine.evaluate(request, snapshot, trace)` 的签名不变（RFC 0004 §6.4），`SnapshotView` 里的编译结果换成本文的 `CompiledStatement` 和索引。
RFC 0002 的 `Rule`、`Guard`、`RuleGroup`、`RuleVisitor`、`HostFacts`、`LegacyFacts` 删除；`legacy` 节点（§3.7）是唯一还调用用户代码的地方，它通过一个窄接口
`LegacyCondition.matches(request): Boolean`（由 `cosec-core` 实现）接入。

---

## 7. 迁移：每种 7.x 匹配器

### 7.1 条件

转换工具（RFC 0004 §8）逐个翻译，只做精确翻译；"拒绝"即报告位置并使转换失败。

| 7.x 类型 | 翻译 | 说明 |
|---|---|---|
| `all` | 省略条件 | |
| `authenticated` | `eq(subject.authenticated, true)` | |
| `inTenant: default / user / platform` | `eq(subject.tenant, "(0)")` / `not(in(subject.tenant, ["(0)", "(platform)"]))` / `eq(subject.tenant, "(platform)")` | 7.x 读 `context.tenant`，它来自主体（[`SimpleSecurityContext`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/context/SimpleSecurityContext.kt)） |
| `inRole: x`（裸），在 DENY 语句或 DENY 方向的条件中 | `in("x", subject.roles)` | 7.x：匹配裸角色 `x`（任何空间）或请求空间里的 `x@space`。8.0：主体在**任何空间**有 `x` 就匹配。只会让 DENY 更宽（改动 D1） |
| `inRole: x`（裸），在 ALLOW 方向 | `in("x", scope.roles)` | 7.x：裸角色 `x` 在任何空间都生效（第二轮审查 M2）。8.0：只在 `x` 于请求空间生效时匹配。只会让 ALLOW 更窄（D1） |
| `inRole: x@s`（带空间），在 DENY 方向 | `in("x@s", subject.scopedRoles)` | 7.x 按字面比较，主体持有 `x@s` 就在**任何**请求空间里匹配（M1）。照搬这个语义，DENY 不变窄 |
| `inRole: x@s`（带空间），在 ALLOW 方向 | `and(eq(scope.space, "s"), in("x", scope.roles))` | 8.0 只在请求空间是 `s` 时匹配。只会让 ALLOW 更窄（D1） |
| `negate: true` | `not(...)` | 同时翻转"方向"（见下） |
| `bool: {and: [...], or: [...]}` | `and(...and, or(...or))`；`or` 为空时省略 | 7.x 的语义：`and` 全真，且（`or` 为空或任一为真） |
| `eq: {part, value}` | `eq(属性(part), 字面量)` | `value` 为 `""` 时翻译为 `or(not(exists(a)), eq(a, ""))`（§3.4）；`ignoreCase` 原样 |
| `eq` 的值是模板 `#{principal.id}` / `#{principal.attributes['k']}` / `#{tenant.tenantId}` | `eq(a, {"attr": "subject.id"})` / `subject.attributes.k` / `subject.tenant` | 其他模板拒绝。7.x 中模板求值为 `null`（属性不存在）时比较为假，与 8.0 相同；但 part 不存在（读作 `""`）而模板求值为空串时，7.x 相等、8.0 不相等（§3.4）：差异类 D2 |
| `in: {part, value: [...]}` | `in(a, [...])`；含 `""` 时加 `or(not(exists(a)), ...)` | |
| `startsWith: {part, value}` | `prefix(a, value)`；`value` 为 `""` 时省略条件（恒真） | |
| `endsWith`，part 是 `request.origin.host` / `request.referer.host`，值形如 `.example.com`，`ignoreCase: true` | `and(prefix(labels, "com.example"), not(eq(host, "example.com", ignoreCase)))` | `Labels` 按小写构造。区分大小写的写法拒绝，提示加上 `ignoreCase`（主机名本来就不区分大小写）；其余 `endsWith` 拒绝 |
| `contains` | 拒绝 | |
| `regular`（总是忽略大小写） | `^字面量$` → `eq(..., ignoreCase)`；`^字面量` 后接 `.*` 或 `.*$` → `prefix(..., ignoreCase)`；`^(a\|b\|c)$`（各分支都是字面量）→ `or(eq...)`（`in` 不支持忽略大小写）；其余拒绝 | 7.x 的 `matches` 是整串匹配，没有 `^`/`$` 的字面量等同于有 |
| `path: {part, pattern}`，`part` 是 `request.path`，语句动作为 `all` / `*` | 移进语句的 `resource` | 两者是合取，动作为 `all` 时没有别的资源约束，等价 |
| `path`，其余情形 | 拒绝 | |
| `time` | `time`（同配置） | 时间来源改为 `environment.time`（§2 J5）；回放两边都用事件时间戳 |
| `spel`、`ognl` | 拒绝 | 报告列出每一处的策略 id、语句、表达式 |
| part `request.path.var.x` | `resource.var.x` | 只在 7.x 允许读取的位置（`PathVariableScope.requireScoped` 已在加载时保证） |
| part `request.header.k`、`request.attributes.k`、`resource.k`、`context.principal.attributes.k` | `http.header.k`、`request.attributes.k`、`resource.attributes.k`、`subject.attributes.k` | 头名不区分大小写，编译时折叠 |
| 自定义类型 | `legacy`（Q2 获批时）或拒绝 | |

**方向**：`inRole` 的翻译取决于条件在决策中的方向。方向在 AST 上计算：语句是 DENY 时从"DENY 方向"出发，ALLOW 时从"ALLOW 方向"出发，每经过一个 `not` 翻转一次；
`and`/`or` 不改变方向。同一个 `inRole` 若出现在两种方向下（例如一个策略级条件同时作用于 DENY 和 ALLOW 语句），转换工具把策略级条件下推到每条语句，分别翻译。

### 7.2 动作

| 7.x | 翻译 |
|---|---|
| `path` 模式在 `HttpPathPattern` 子集内 | §5.2 的表 |
| `path` 模式含 `{x:regex}` 或自定义 `separator` | 拒绝 |
| 路径模板 `/users/#{principal.id}/**` | 资源 `http:path:/users/{__t0}/**` + 条件 `eq(resource.var.__t0, {"attr": "subject.id"})`。代入的值只参与相等比较，不改变模式（§2 J9） |
| 路径模板出现在一段的中间（`/a/x-#{principal.id}`） | 拒绝（段内模板没有对应的捕获语法） |
| 自定义 `ActionMatcherFactory` | 拒绝 |

### 7.3 限流规则

`RateLimitRule` 的 `action`、`condition` 按 §7.1、§7.2 翻译；`key`（part）翻译成属性路径（`request.remoteIp` → `environment.ip`，`context.principal.id` → `subject.id`，
其余按 §7.1 的 part 对照表），`key` 引用的属性不存在时与 7.x 相同：落进同一个空值桶。规则仍由 `RateLimitRuleProvider` 提供、由 `RateLimitingAuthorization` 执行（第 5 阶段成为义务）。
`schema/rate-limit-rule.schema.json` 随之更新。

> **2026-10-26 实现说明（PR-6，#1069）**：
>
> - **方向。** 每个条件都按方向翻译：语句是 DENY，或者是限流规则，就从"DENY 方向"出发；每个 `negate` 翻转一次。在 ALLOW 方向，翻译只许比 7.x 窄；在 DENY 方向只许比 7.x 宽；
>   做不到就拒绝并报告位置。所以翻译永远不会放行 7.x 拒绝的请求。策略级条件（含应用条件）在 DENY、ALLOW 两个方向下翻译结果不同时，下推到每条语句。
> - **不存在的属性。** 7.x 把不存在的 part 读成 `""`，把匿名主体的 id 读成 `(0)`。转换时直接在这个值上求 7.x 的谓词，为真就加上 `not(exists)`。
>   这样不存在的情形精确翻译，`eq` 的值为 `""` 不再是差异。
> - **大小写。** 8.0 的 `ignoreCase` 只折叠 ASCII。7.x 的 `equals`/`startsWith` 带 `ignoreCase`，以及 `regular`（Java 的 `CASE_INSENSITIVE`）还会折叠其他字符，
>   例如 U+212A KELVIN SIGN 等于 `k`，这一点由一致性测试发现。因此值里含字母时：ALLOW 方向按收窄处理并记录 `ASCII-CASE`，DENY 方向拒绝。
> - **`regular`。** `^字面量.*` 只在 DENY 方向、且不含字母时翻译成 `prefix`：`.*` 不匹配行终止符，而 `prefix` 匹配。
> - **`eq` 的模板值。** ALLOW 方向按 D2 收窄；DENY 方向把 7.x 读法的情形（不存在等于空串、匿名主体的 `(0)`）加回，保持精确，不让 DENY 变窄。
> - **路径条件。** `path` 条件（part 为 `request.path`）在语句的动作为 `all` 时成为语句的资源。如果其余条件读路径变量，就拒绝：7.x 的路径条件不捕获变量，移成资源后会多匹配。
> - **仍然拒绝的。** `request.remoteIp` 的字符串比较（改用 `cidr`）、`contains`、动作中的路径模板（主体 id 含模式字符时 8.0 会多匹配），以及 `spel`、`ognl` 和自定义匹配器。
> - **限流规则。** 按 DENY 方向翻译，写进平台文档的 `rateLimits`（RFC 0006 PR-5，#1078；此前写进 `rate-limits.json`）。
> - **一致性测试。** `ConditionTranslatorTest` 在语料的每一行上，用随机与固定的对抗输入同时求 7.x 匹配器和翻译后的 AST：标为相同的行逐例相同，其余各行按方向不放宽。

### 7.4 转换报告新增的内容

每种 7.x 匹配器类型的数量、翻译成功数、拒绝数和拒绝位置；拒绝的 OGNL/SpEL 表达式原文（只在本地报告里）；每条语句翻译后的节点数和成本分布（用来校准 §3.5 的上限）；
`inRole` 按方向的计数；`legacy` 节点数。

---

## 8. 门槛 B

> **2026-10-26：本节由 [RFC 0008](0008-conformance-and-direct-build.md) 取代。** D1–D3 是 8.0 的规则（例如 D2 → `CND-1`），登记在一致性套件中并写进 `upgrading.md`；不再与 7.x 比较。
> "7.x 匹配器与翻译后 AST 在 §7.1 标注相同的行上逐例相同"作为 PR-6 中转换工具自己的单元测试保留。以下原文保留，仅供追溯。

方法与 RFC 0004 §9 完全相同：基准是 RFC 0004 切换后的实现（绑定模型 + 7.x 匹配器）在转换工具的模型输出上；候选是本文的实现在完整转换（模型 + AST）的输出上；
未归类差异为零；放宽类逐条确认。改动目录：

| # | 改动 | 方向 | 证据 |
|---|---|---|---|
| D1 | **`inRole` 按方向与作用域判定**（§7.1；RFC 0002 Q12 的 M1/M2） | 只收紧：DENY 变宽、ALLOW 变窄 | 决定性的语句（或缺少的那条 ALLOW）含一个由 `inRole` 翻译来的节点；基准 trace 中该节点为真、候选为假（ALLOW），或者相反（DENY） |
| D2 | **空串不再等于"不存在"**：一侧不存在（7.x 读作 `""`）、另一侧是空串的 `eq(a, {"attr": b})` | ALLOW 方向收紧；DENY 方向放宽 | 决定性的差异节点是 `eq(a, {"attr": b})`，样本中一侧不存在、另一侧为空串。**DENY 方向的样本逐条确认**（这是本文唯一可能放宽的一类：`deny if resource.ownerId == principal.attributes.x`，资源属性缺失而主体属性是空串时，7.x 拒绝，8.0 不拒绝） |
| D3 | **7.x 匹配器的错误消失**：`RegexTimeoutException` 不再可能（正则已翻译成精确的 `eq`/`prefix`） | 只改状态码 | 基准异常类为 `RegexTimeoutException`，候选 trace 中对应语句为 `MATCH` 或 `NO_MATCH` |

**为什么 D2 不能直接避免**：可以把 `eq(a, {"attr": b})` 翻译成"不存在等于空串"，但那正是 7.x 的缺陷形态：一个缺少 `ownerId` 资源属性的请求，因为主体的对应属性是空串，
就被当作"所有者本人"放行（ALLOW 方向）。所以本文选择不存在不等于任何值，DENY 方向的少数样本由维护者确认（Q7）。

语料：RFC 0004 §9.3 的全部，另加：每种 7.x 匹配器的翻译正例（随机生成该类型的配置与请求，比较 7.x 匹配器与翻译后 AST 的结果，在 §7.1 标注"相同"的行上必须逐例相同）；
`HttpPathPattern` 与 `PathPattern` 的对照（§5.2）；I1、I2；A1–A4。

---

## 9. 不变量、AOT 与性能

### 9.1 不变量

| 不变量 | 如何成立 | 守护 |
|---|---|---|
| Deny-first | 不变（RFC 0004） | P1′ |
| 索引完整 | §6.3 | I1、I2 |
| Fail closed | AST 求值不抛出；`legacy` 出错按 RFC 0004 §6.1；编译失败的文档不安装（RFC 0003 F4） | 单元测试；P9 |
| 纯决策 | 引擎不调用用户代码，`legacy` 节点除外（第 5 阶段删除） | `EnginePurityTest`；ArchUnit：`engine` 中只有 `LegacyCondition` 一个用户代码入口 |
| 成本有界 | §3.5 | A4 |
| 路径变量只属于本语句（D7） | 捕获结果是语句匹配的返回值（§6.1） | 结构保证；`PathVariableScopeTest` 改写为编译期检查的测试 |
| 热路径有基准 | §9.3 | `EngineBenchmark` |

### 9.2 AOT

| 约束 | 做法 |
|---|---|
| 不用反射 | AST 是 sealed 类；文档用 Jackson 流式 API 解析；不再有 OGNL（重度反射）和 SpEL（`SpelConditionMatcher.Root` 需要逐类型 hint），RFC 0001 §3.10 列出的两个已知障碍随之消失 |
| 匹配器工厂注册 | 内置工厂全部删除；`MatcherFactoryRegistry` 只剩 `legacy` 节点用的条件工厂，第 5 阶段删除 |
| 路由映射是资源 | `cosec-webflux`/`cosec-webmvc` 的 `RuntimeHintsRegistrar` 注册 `cosec.authorization.routes.location` 的默认位置；缺少时启动失败（不是"没有映射"，那会让所有请求落进 `http` 命名空间） |
| Spring `PathPattern` | 引擎不再使用；只有 `cosec-core` 的对照测试使用 |

### 9.3 性能

> **2026-10-26**：第二行（对 7.0 的 100 %）保留，基线按 [RFC 0008](0008-conformance-and-direct-build.md) §3.8 从 `v7.0.1` 标签同机重跑；第三行（对 `legacy` 语言的 95 %）取消；其余只记录或与规模有关的目标保留。

| 基准 | 目标 |
|---|---|
| `EngineBenchmark`，参数 `statements` ∈ {10, 1,000, 10,000, 100,000}（适用绑定内），`namespace` ∈ {`http`, `service`} | 同一场景下 B/op 不随 `statements` 变化；吞吐在 10,000 条时不低于 10 条时的 50%（索引使每请求工作量与命中的分区大小成正比，而不是总数） |
| `SimpleAuthorizationBenchmark`，场景 `global`、`role`、`none`、`role-heavy`，与 **7.0 的 `SimpleAuthorization`** 比较（**硬性要求**，来自第 2 阶段的性能豁免，RFC 0002 §7.2 第 8 步） | 吞吐**不低于 7.0 的 100 %**，按 RFC 0002 §7.2 的条件测量（JDK 25、5 次预热、10 次迭代、3 个 fork、交替三轮、空闲机器）。任一场景达不到，切换 PR（PR-8）停止，交回维护者决定 |
| `SimpleAuthorizationBenchmark`，参数 `language` ∈ {`legacy`, `ast`} | 吞吐不低于 `legacy` 的 95%；B/op 不高于 `legacy`。预期明显更好：不再有 `PathContainer` 解析缓存、`ConcurrentHashMap` 属性、SpEL 模板求值 |
| `ConditionBenchmark` | 每个运算符的单次求值耗时与分配（`OFF` 时零分配） |
| `HttpPathPatternBenchmark` | 与 Spring `PathPattern.matchAndExtract` 对照，只记录 |
| `SnapshotCompileBenchmark`（RFC 0003） | 增加 AST 文档；编译 p99 仍满足 RFC 0003 Q14 的 200 ms |

测量方法同 RFC 0002 §7.2，结果记在 `docs/perf/rfc-0005-ast/README.md`。

---

## 10. 实现拆分

| PR | 标题 | 范围 | 依赖 | 能否提前开工 |
|---|---|---|---|---|
| PR-1 | `feat(model): add the condition AST with types and cost bounds` | §3 的 AST、属性目录、类型检查、成本、规范化、求值；A3、A4 | 本 RFC 获批 | **能**：与 RFC 0004 PR-1 并行 |
| PR-2 | `feat(model): extract index keys and decide condition containment` | §4.1、§4.2；A1、A2 | PR-1 | **能** |
| PR-3 | `feat(engine): match actions and resources without Spring` | §5.1、§5.2：`Action`/`Resource` 模式、`HttpPathPattern`；与 `PathPattern` 的对照测试（`cosec-core`） | PR-1 | **能** |
| PR-4 | `feat(engine): compile statements and index candidates` | §6：`CompiledStatement`、索引、捕获结果、引擎接入；I1、I2；`EngineBenchmark` 的新参数 | PR-2、PR-3；RFC 0004 PR-2 | RFC 0004 PR-2 合入后 |
| PR-5 | `feat(openapi): generate route maps and service actions`（已合入，#1079） | §5.3：路由映射生成、宿主侧加载与查找、生成器改为 `service:verb`；§5.4 | PR-3 | **能** |
| PR-6 | `feat(migrate): translate 7.x matchers into the condition AST`（已合入，#1069）（**2026-10-26 改动**：不做 `legacy` 节点，自定义条件匹配器转换时拒绝；见 [RFC 0008](0008-conformance-and-direct-build.md) §4.1） | §7 全部，包括限流规则；`legacy` 节点（Q2）；报告 | PR-4；RFC 0004 PR-5 | RFC 0004 PR-5 合入后 |
| ~~PR-7~~ | ~~`test: classify AST migration differences in replay`~~ **已取消（2026-10-26，[RFC 0008](0008-conformance-and-direct-build.md)）** | D1–D3、正例与反例、翻译对照语料 | PR-6；RFC 0004 PR-6 | RFC 0004 PR-6 合入后 |
| — | ~~**门槛 B**~~ **已取消（2026-10-26）** | 维护者本地回放 | PR-7；RFC 0004 PR-7（基准是切换后的实现） | — |
| ~~PR-8~~ | ~~`refactor!: evaluate compiled conditions and actions`~~ **并入 RFC 0008 的切换 PR（2026-10-26）**，100 % 吞吐要求不变 | 切换（前提：§9.3 对 7.0 的 100 % 吞吐要求已满足，否则停止并交回维护者）：文档 `schema: 3`（AST），schema 标记更新（RFC 0004 §8.5）；starter 装配路由映射；`AuthorizationChecker` 的新签名；文档；JMH | 门槛 B | **不能** |
| ~~PR-9~~ | ~~`refactor!: remove the legacy matchers`~~ **并入 RFC 0008 的切换 PR（2026-10-26）**，删除清单不变 | 删除 OGNL、SpEL（含 `OgnlSandbox` 和依赖）、全部内置匹配器与工厂、`ActionMatcherFactory` SPI、`PartExtractor`、`Expression`/模板、`StatementScopedContext`、`Rule`/`Guard`/`RuleGroup`、`HostFacts`/`LegacyFacts`、`PathVariableScope`；`cosec-api` 的 `ActionMatcher`、`ConditionMatcher`、`Statement`/`Policy` 中的匹配器字段（Q8） | PR-8 | **不能** |

**文档**（中英文同步，PR-8/PR-9）：`wiki/policies/language.md` 重写（动作、资源、条件 AST、属性目录、不存在的属性、成本上限、迁移对照表）；
`wiki/policies/rate-limiting.md`（规则的新写法）；`wiki/guide/security-model.md` 的 "Role scope and inRole"、"Path variable scope"；`wiki/integration/checks.md`；
`wiki/integration/web.md`（路由映射、`http` 命名空间）；`wiki/architecture/extending.md` 与 `skills/cosec-custom-matcher`（自定义匹配器 → `legacy` 过渡 → 第 5 阶段 `AttributeProvider`）；
`wiki/architecture/overview.md`（编译、索引、JMH）；`wiki/operations/upgrading.md`（D1–D3、拒绝项、如何改写 OGNL/SpEL/正则）；`wiki/operations/troubleshooting.md` 与 `skills/cosec-troubleshoot`
（编译错误、成本超限、路由映射缺失）；`skills/cosec-policy-author`（全部示例改为 AST）；`schema/condition.schema.json`、`action.schema.json`（新增 `resource`）、`cosec-policy.schema.json`、
`rate-limit-rule.schema.json`；`AGENTS.md` 的 "Where a change has to land" 中"New action/condition type"一行（工厂与 `META-INF/services` 不再存在，改为"属性目录与运算符"）。

---

## 11. 决定

以下各项已于 **2026-10-22** 由维护者确认，全部按本文的推荐决定；Q12 是同日新增的硬性要求。标注"测量后确认"的，PR 按这里的值实现，数字由测量结果校准。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q1 | **HTTP 路径模式的实现**（§2 J1、§5.2） | **已决定**：引擎内的纯 Kotlin `HttpPathPattern`，覆盖 Spring `PathPattern` 去掉正则捕获和自定义分隔符之后的子集，用对照性质测试保证等价；子集之外的转换时拒绝 | 引擎不能依赖 `spring-web`，也不能用正则；7.x 策略中的正则捕获预计很少，转换报告会给出确切数量 |
| Q2 | **自定义条件匹配器的过渡**（§2 J2、§3.7；**2026-10-26 由 [RFC 0008](0008-conformance-and-direct-build.md) §6 Q4 取代**：不实现 `legacy` 节点） | **已决定**：保留不可分析的 `legacy` 节点，只承载自定义工厂，只能由平台层发布，第 5 阶段删除 | 否则第 4 阶段到第 5 阶段之间自定义匹配器无处可去；不可分析的代价由"总是候选"与平台层发布承担 |
| Q3 | **OGNL/SpEL**（§2 J3） | **已决定**：切换时直接删除；转换工具拒绝并报告每一处 | 没有发布中间版本，"deprecated"没有使用者；表达式无法精确翻译 |
| Q4 | **限流规则**（§2 J4、§7.3） | **已决定**：本文翻译限流规则的匹配部分，执行方式留到第 5 阶段 | 删除匹配器后规则无法加载；匹配语言应当只有一种 |
| Q5 | **`endsWith` 等不能表达的运算**（§2 J6、§3.6） | **已决定**：不加运算符；提供 `*.host.labels` 派生属性覆盖域名后缀；其余拒绝转换 | 保持 RFC 0001 §7.1 确认的运算符集合；域名后缀是最常见、也最容易写错的用法（`endsWith("example.com")` 匹配 `badexample.com`） |
| Q6 | **路径模板**（§2 J9） | **已决定**：作为安全修复单独处理；AST 从结构上消除这一类问题 | 路径模板变成捕获变量 + `eq`，代入的值永远不会改变模式 |
| Q7 | **不存在的属性与 D2**（§3.4、§8） | **已决定**：不存在不等于任何值，包括另一个不存在；DENY 方向的回放样本逐条确认 | "两边都不存在即相等"会在 ALLOW 方向放行不该放行的请求 |
| Q8 | **`cosec-api` 匹配器接口的删除**（AGENTS.md 要求先问） | **已决定**：PR-9 删除 `ActionMatcher`、`ConditionMatcher` 及 `Statement`/`Policy` 中的匹配器字段；`legacy` 节点用 `cosec-core` 内部的窄接口 | 它们是第二种执行模型；保留就无法删除适配层 |
| Q9 | **成本上限的默认值**（§3.5，测量后确认） | **已决定**：节点 64、深度 8、`in` 1,024、`cidr` 256、字面量 1 KB、语句成本 256 | 用转换报告中 7.x 策略的实际分布校准 |
| Q10 | **未映射路由的动作**（§5.3） | **已决定**：`http:<METHOD>` + `http:path`，即 `http` 命名空间；一个请求只有一个动作 | 失败即拒绝：`service:verb` 的 ALLOW 不会覆盖未映射的路由；`http` 的 DENY 不因加了映射而失效 |
| Q11 | **`inRole` 带空间值在 DENY 方向的翻译**（§7.1） | **已决定**：新增属性 `subject.scopedRoles`（`role@space` 形式），保持 7.x 语义 | 否则 DENY 会变窄，即放宽访问 |
| Q12 | **编译执行的吞吐要求**（2026-10-22 新增，来自第 2 阶段的性能豁免，RFC 0002 §7.2 第 8 步） | **已决定**：编译执行在 `global`、`role`、`none`、`role-heavy` 上的吞吐不低于 7.0 的 `SimpleAuthorization` 的 100 %，按 RFC 0002 §7.2 的条件测量（§9.3）；达不到时切换 PR 停止，交回维护者 | 第 2 阶段接受了最多约 7 % 的差距，理由是编译执行将取代整个适配路径；本阶段必须兑现 |
