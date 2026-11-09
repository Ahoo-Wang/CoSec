---
title: "限定在一个空间的服务账号"
description: "给机器客户端一个只能在一个应用的一个空间里行动的 API key：把 key 的上限限定为该空间的单个角色。"
---

# 限定在一个空间的服务账号 {#a-service-account-in-one-space}

面向让作业、扫描器或合作方调用 API，并且希望凭证只能触及一个应用的一个空间的团队。**服务账号**是普通的主体，只是没有人以它登录。它的 **API key** 是这个主体的凭证，key 的**上限**把它收窄到你列出的角色。本页搭建仓库 `wh-01` 的扫描器。key 的规则见 [API key](../integration/authentication.md#api-keys)。

## 你需要什么 {#what-you-need}

| | 限定在一个空间的服务账号 |
|---|---|
| 依赖 | `cosec-cocache` 和一个 `MembershipProvider`（有 `cosec-cocache` 时，提供一个 `MembershipLoader` bean） |
| 配置 | `cosec.api-key.enabled: true`，以及下面的 key |
| 文档 | [使用空间的应用](./spaces.md)的应用文档，加上扫描器的角色 |
| 概念 | key 自身不授予任何权限；上限；`uncapped`；空间必须是凭证列出的空间 |

即使账号的权限全部来自角色也需要 provider：key 的成员关系永远是它主体的，从 provider 读取，所以没有 provider 应用无法启动（`IDN-6`）。账号必须在 provider 里存在；provider 不认识或认为已停用的主体，它的 key 会被拒绝。因此停用账号就停用了它的所有 key。

## 角色 {#the-role}

扫描器在它工作的任何空间里读取并发出入库单：

<<< @/examples/service-account/documents/apps/wms.json [documents/apps/wms.json]

provider 对租户 `acme` 的 `svc-scanner` 回答：它在 `wh-01` 和 `wh-02` 都持有 `wms.scanner`。账号服务两个仓库，每个 key 只服务一个。

## key {#the-key}

生成 key，不要放进配置；配置里只写它的 SHA-256：

```bash
KEY=cosec_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')   # the form ApiKeys.generate() makes
echo "$KEY"                                             # give this to the client, once
printf '%s' "$KEY" | shasum -a 256 | cut -d' ' -f1      # the hash; Linux: sha256sum
```

```yaml
cosec:
  api-key:
    enabled: true
    keys:
      - id: scanner-wh-01
        hash: <the hash from above>
        principal-id: svc-scanner
        tenant-id: acme
        app: wms
        roles: [wms.scanner@wh-01]
        expires-at: 2027-11-01T00:00:00Z
```

`roles` 就是上限：这个 key 只持有 `wh-01` 里的 `wms.scanner`，虽然它的主体在 `wh-02` 也持有。既没有 `roles` 也没有 `uncapped: true` 的 key 无法启动。`app: wms` 把 key 绑定到应用，和令牌的 `aud` 一样。经网关使用的 key 要设 `expires-at`。

客户端在 `X-API-Key` 头里发送它，并说明工作在哪个空间：

```bash
curl -X POST https://api.example.com/inbound/42/ship \
  -H "X-API-Key: $KEY" -H "CoSec-Space-Id: wh-01"
```

## 验证 {#check-it}

[`wiki/examples/service-account`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/service-account/scenarios) 里的场景在每次构建时运行，凭证的角色上限是 `wms.scanner@wh-01`：

| 请求 | 结果 |
|---|---|
| 发出入库单 42，空间 `wh-01` | Permit |
| 发出入库单 42，空间 `wh-02` | `403` `scope-rejected`：上限只列了 `wh-01`，虽然账号在 `wh-02` 也是扫描器 |
| 编辑仓库，空间 `wh-01` | Deny：角色没有 `warehouse.edit` |

带上限的 key 永远得不到 break-glass，绑定在其主体上的 DENY 仍然适用于它（[API key](../integration/authentication.md#api-keys)）。

## 用令牌做同样的事 {#the-same-with-a-token}

客户端能登录时，例如代表用户行动的 agent，令牌不需要 key 也能做到同样的事：为应用签发令牌，只带该空间的角色，

```kotlin
TokenSubject(id = "svc-scanner", app = "wms", tenant = "acme", roles = setOf("wms.scanner@wh-01"))
```

这样令牌列出的就只有这一个空间，客户端无法在别的空间工作。两者的选择取决于客户端如何认证；限制是一样的，都来自凭证列出的角色。

## 轮换与吊销 {#rotate-and-revoke}

为账号添加新 key，把客户端迁过去，再删除旧 key。要立即停用某个 key，把 key 存在 Redis 里（`cosec.api-key.store: redis`）并在那里吊销；要停用账号的所有 key，在你的 provider 里停用账号（[成员关系](../integration/authentication.md#memberships)）。

## 出问题时 {#if-it-fails}

- 启用 API key 后应用无法启动：没有 `MembershipProvider`（`IDN-6`）、key 没有 `roles` 或 `uncapped`，或 hash 不是 64 位小写十六进制（[API key 导致启动失败](../operations/troubleshooting.md#startup-fails-on-api-keys)）。
- 用 key 请求得到 `401` `token-invalid`：key 未知、被停用或已过期，或 provider 不认识这个账号。
- `403` `scope-rejected`：key 的上限没有列出该空间，或它的应用不是服务的应用（[`scope-rejected`](../operations/troubleshooting.md#scope-rejected)）。

**下一步：**每个租户一个所有者，无需修改文档，见[租户所有者](./tenant-owner.md)。
