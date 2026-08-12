# CoSec 项目独立性清理设计

## 目标

CoSec 必须作为独立项目维护。当前工作树不再保留 RoGraph 专用的生产实现、自动配置、配置项、测试夹具、证据文档、架构约束或示例命名。

## 当前状态

提交 `7ee6b249` 引入了 RoGraph service edge adapter，包含 WebFlux 生产工厂、Spring Boot 自动配置、专用测试和集成证据文档，同时修改了 `AGENTS.md` 与 `.gitignore`。提交 `27fc57c4d` 后续又在通用 OGNL 安全测试中使用了 `rograph.workspaceId` 示例。

其中 `.gitignore` 的 `build/`、`out/`、`.kotlin/` 规则属于通用项目维护，不依赖 RoGraph，不应随本次清理回退。

## 方案选择

采用精确删除，不机械反向应用整个提交，也不重写 Git 历史。

原因：

- 精确删除可以完整覆盖生产代码、配置、测试、文档和后续残留命名。
- 保留与外部集成无关的 `.gitignore` 演进，避免引入无关回归。
- 不重写共享提交历史，避免改变已有提交 SHA 和协作分支基线。

## 变更范围

### 删除生产集成

- 删除 `cosec-webflux` 中 `me.ahoo.cosec.webflux.rograph` 包及 `RoGraphServiceEdgeWebFilterFactory`。
- 删除 `cosec-spring-boot-starter` 中 `authorization.rograph` 包及 `CoSecRoGraphServiceEdgeAutoConfiguration`。
- 从 `AutoConfiguration.imports` 删除该自动配置注册项。
- 删除 `cosec.authorization.rograph.service-edge.enabled` 配置入口，不提供兼容别名或弃用空壳。

### 删除专用测试

- 删除 WebFlux 层的 RoGraph fixture、adapter 和 production factory 测试。
- 删除 Spring Boot starter 层的自动配置测试和下游 consumer fixture 测试。
- 删除 test-support 中的 RoGraph adapter、evidence sink 和相关数据模型。
- 将通用 `OgnlConditionMatcherSecurityTest` 中的 `rograph.workspaceId` 改为中性测试键 `security.workspaceId`，保留原有只读沙箱安全断言。

### 删除文档与架构约束

- 删除 `docs/contracts/rograph-integration.md`。
- 删除 `docs/delivery` 下两份 RoGraph service edge 文档。
- 删除 `docs/evidence` 下四份 RoGraph evidence 文档。
- 删除 `AGENTS.md` 中 `RoGraph Integration` 章节。
- 本设计文档仅用于实施前评审；实施完成前一并删除，避免最终工作树继续保留 RoGraph 相关过程信息。

## 保留范围

- 保留通用 `SecurityContext`、`Authorization`、`ReactiveAuthorizationFilter`、Gateway filter 和 Spring Boot starter 能力。
- 保留 `.gitignore` 中与 Gradle、Kotlin、IDE 和本地计划目录有关的当前规则。
- 不修改 CoSec SPI 接口、依赖版本或公开通用 API。
- 不重写 Git 历史；旧提交对象仍可能包含已删除内容。

## 架构结果

清理后不再存在产品专用 service edge 组合层。CoSec 只暴露通用安全上下文、授权、过滤器、Gateway 与 Spring Boot 自动配置能力，具体产品通过既有通用扩展点自行组合，不在 CoSec 内形成产品命名、产品配置或产品证据模型。

## 验证策略

1. 运行大小写不敏感的全仓扫描，确认当前工作树中不存在 `RoGraph` 或 `rograph`。
2. 扫描 `Idena`、`Atria`、`Luma`、`Orvia`、`Raema`、`Published Language` 和 `Evidence Run Identity`，确认本次集成引入的关联产品语义没有残留。
3. 运行 `:cosec-core:test`，验证 OGNL 安全测试仍覆盖上下文属性变更拦截。
4. 运行 `:cosec-webflux:test` 和 `:cosec-spring-boot-starter:test`，验证删除专用实现后通用模块仍通过。
5. 运行 `./gradlew test`、`./gradlew detekt` 和 `./gradlew build`，完成项目级回归验证。
6. 检查 `git diff --check`、删除文件清单和最终 `git status`，确认没有无关修改。

## 风险与控制

- 依赖专用 factory 或 property 的外部消费者会发生破坏性变化。该变化符合“CoSec 项目独立、不保留 RoGraph 信息”的 clean break 目标，因此不保留兼容层。
- 机械回退提交可能误改 `.gitignore`；通过按文件和按行精确修改规避。
- 删除专用测试会减少测试数量，但不会降低通用能力的覆盖；通用模块测试和全量构建用于验证边界未被误删。
- Git 历史仍可检索旧信息。若未来要求从所有历史对象中物理清除，需要单独批准历史重写、协作迁移和远端强制更新方案。

## 完成标准

- 当前工作树不存在 RoGraph 或其关联产品专用信息。
- 不存在 RoGraph 专用生产类、测试类、包、自动配置或 property。
- 通用 CoSec API、实现与自动配置保持可构建、可测试。
- 所有残留扫描、模块测试、全量测试、Detekt 和构建均通过；若出现环境性失败，必须记录原始错误与根因，不得声明验证通过。
