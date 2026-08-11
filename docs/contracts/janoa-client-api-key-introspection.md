# Janoa Client API Key Introspection Contract

## 文档职责

本文档定义 CoSec 消费 Janoa ClientApiKey introspection 的 consumer contract。Janoa 拥有 `ClientApiKey` 的创建、轮换、撤销、过期、verifier、审计和 quota binding；CoSec 只负责认证终端请求、调用受限 introspection，并向 Janoa 签发 `TrustedSecurityContext`。

本文档不把 `ClientApiKey` 所有权移入 CoSec，不把 Janoa quota decision 移入 CoSec，也不声明该接口已实现、已部署或已获得双方 owner approval。

## Contract Status

| Item | Status |
|---|---|
| Provider | Janoa |
| Consumer | CoSec |
| Owner | `ClientApiKey.owner = Janoa` |
| Consumer relation | `ClientApiKey.introspectionConsumer = CoSec` |
| Trusted context issuer | `TrustedSecurityContext.issuer = CoSec` |
| Published form | Internal REST resource |
| Implementation | Not Claimed |
| Approval | Pending RoGraph and CoSec owner review |

## Wire Contract

`POST /internal/v1/client-api-keys/introspections`

- request media type: `application/json`；
- success media type: `application/json`；
- request body only contains `apiKey`，最小长度为 32，且为 `writeOnly`；
- response required fields: `active`, `version`, `expiresAt`, `cacheTtlSeconds`；
- response optional nullable identity fields: `tenantId`, `clientApiKeyId`；
- `cacheTtlSeconds` 范围为 0–30。

权威 schema 位于 Janoa `docs/contracts/janoa-internal-v1-proposal.yaml`。CoSec review 必须针对同一 wire shape，不能在本仓库分叉定义第二份 schema。

## Request Example

下列值是显式 synthetic 文档 sentinel，不是可用 secret。它满足 wire contract 的 32 字符下限，但不得配置为真实 credential；真实测试 fixture 必须生成临时值且不得写入仓库。

```json
{"apiKey":"jk_live_example-only-not-a-real-key"}
```

## Response Shape Example

下列响应展示 active result 的字段形状，不表示仓库中存在与 synthetic sentinel 对应的 live key。

```json
{
  "active": true,
  "tenantId": "tenant-1",
  "clientApiKeyId": "key-1",
  "version": 3,
  "expiresAt": "2027-01-01T00:00:00Z",
  "cacheTtlSeconds": 30
}
```

## Consumer Semantics

1. CoSec 只通过受 service identity、独立 internal listener、NetworkPolicy 和 internal Service 限制的接口调用 introspection；该接口不得暴露在 public ingress。
2. CoSec 不记录、转发到 telemetry 或持久化 raw `apiKey`；日志、metric、trace 和 error body 均不得包含它。
3. positive cache key 必须绑定 `clientApiKeyId` 与 `version`；TTL 不得超过 `expiresAt` 剩余时间、响应 `cacheTtlSeconds` 和 30 秒中的最小值。
4. inactive response 使用 `cacheTtlSeconds=0`。新 key、cache miss、cache expiry、版本无法证明或 introspection 不可用时必须 fail closed。
5. introspection 只回答 key active/version/freshness，不返回 Janoa quota decision；quota reserve、settle 和 rejection 仍由 Janoa 负责。
6. CoSec 完成终端认证授权后签发 `TrustedSecurityContext`；Janoa public dispatch endpoint 不接收或验证 raw key。

## TrustedSecurityContext Consumer Contract

CoSec 向 Janoa 发送的授信上下文必须包含：

- `tenantId`；
- `subjectId`；
- optional `workspaceId`；
- optional `systemId`；
- optional `clientApiKeyId`；
- `credentialVersion`；
- `expiresAt`；
- `correlationId`。

Janoa 只能从该上下文读取或校验 tenant、subject、workspace、system 和 `clientApiKeyId`，不得接受 request body 覆盖。上下文过期、签发者不可验证、字段缺失或 freshness 不可证明时必须 fail closed。

## Compatibility, Migration and Rollback

- Janoa greenfield v1 是 clean break；previous Janoa API、event 和 schema unsupported，CoSec 必须显式迁移到本 v1 contract。
- 在 consumer cutover 前，CoSec 必须以 contract test 验证 request/response、nullable fields、version、expiry、cache TTL、revocation propagation 和 fail-closed 行为。
- rollback 通过保留独立旧环境与受控流量切回执行；不得让新旧 introspection cache、key version 或 trusted context 混用。
- 任何 endpoint、field、status/error、versioning、service identity、cache TTL 上限或 revocation semantics 变化都属于跨仓 contract change，必须重新经过 RoGraph 与 CoSec review。

## Approval Gate

本文件只是可评审 proposal。只有 RoGraph 与 CoSec owner review 均批准，且 approved review URL 与 commit SHA 已记录到 Janoa approval record 后，Gate 0 才能打开；本地 commit、CI 或文档存在不能替代 human approval。
