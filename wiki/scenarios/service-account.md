---
title: "A Service Account in One Space"
description: "Give a machine client an API key that can act only in one space of one app, by capping the key to a single role in that space."
---

# A Service Account in One Space

For a team that lets a job, a scanner or a partner call its API, and wants the credential to reach one space of one app
and nothing else. A **service account** is an ordinary principal that no person logs in as. Its **API key** is a
credential for that principal, and the key's **cap** narrows it to the roles you list. This page builds the scanner of
warehouse `wh-01`. The key rules are in [API keys](../integration/authentication.md#api-keys).

## What you need

| | A service account in one space |
|---|---|
| Dependencies | `cosec-cocache` and a `MembershipProvider` (with `cosec-cocache`, a `MembershipLoader` bean) |
| Configuration | `cosec.api-key.enabled: true`, and the key, below |
| Documents | The app document of [Apps with spaces](./spaces.md), with a role for the scanner |
| Concepts | A key grants nothing of its own; the cap; `uncapped`; a space is one the credential lists |

A provider is required even when the account's rights come only from its roles: a key's memberships are always its
principal's, read from the provider, so the application does not start without one (`IDN-6`). The account must exist
there; a principal the provider does not know, or knows as deactivated, has its keys refused. Deactivating the account
therefore turns off every one of its keys.

## The role

The scanner reads and ships inbound orders, in whichever space it works in:

<<< @/examples/service-account/documents/apps/wms.json [documents/apps/wms.json]

The provider answers for `svc-scanner` of tenant `acme` that it holds `wms.scanner` in `wh-01` and in `wh-02`: the
account serves both warehouses, and each key will serve one.

## The key

Generate the key and keep it away from the configuration; configure only its SHA-256:

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

`roles` is the cap: the key holds only `wms.scanner` in `wh-01`, though its principal holds it in `wh-02` too. A key
with neither `roles` nor `uncapped: true` does not start. `app: wms` binds the key to the app, as a token's `aud` does.
Give a key used through a gateway an `expires-at`.

The client sends it in the `X-API-Key` header, and names the space it works in:

```bash
curl -X POST https://api.example.com/inbound/42/ship \
  -H "X-API-Key: $KEY" -H "CoSec-Space-Id: wh-01"
```

## Check it

The scenarios in [`wiki/examples/service-account`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/service-account/scenarios)
run on every build, as a credential with the role cap `wms.scanner@wh-01`:

| Request | Result |
|---|---|
| ship inbound order 42, space `wh-01` | Permit |
| ship inbound order 42, space `wh-02` | `403` `scope-rejected`: the cap lists `wh-01` only, though the account is a scanner in `wh-02` |
| edit a warehouse, space `wh-01` | Deny: the role does not hold `warehouse.edit` |

A capped key never gets break-glass, and the DENYs bound to its principal still apply to it
([API keys](../integration/authentication.md#api-keys)).

## The same with a token

When the client can log in, as an agent acting for a user can, a token does the same without a key: issue it for the
app with only the roles of that space,

```kotlin
TokenSubject(id = "svc-scanner", app = "wms", tenant = "acme", roles = setOf("wms.scanner@wh-01"))
```

The space is then the only one the token lists, so the client cannot work in another. The choice between the two is how
the client authenticates; the limit is the same, and it comes from the roles the credential lists.

## Rotate and revoke

Add a new key for the account, move the client over, then remove the old one. To stop a key at once, store keys in
Redis (`cosec.api-key.store: redis`) and revoke it there; to stop every key of the account, deactivate the account in
your provider ([Memberships](../integration/authentication.md#memberships)).

## If it fails

- The application does not start with API keys enabled: no `MembershipProvider` (`IDN-6`), a key without `roles` or
  `uncapped`, or a hash that is not 64 lowercase hex characters
  ([Startup fails on API keys](../operations/troubleshooting.md#startup-fails-on-api-keys)).
- `401` `token-invalid` with a key: unknown, disabled or expired, or the provider does not know the account.
- `403` `scope-rejected`: the key's cap does not list the space, or its app is not the service's
  ([`scope-rejected`](../operations/troubleshooting.md#scope-rejected)).

**Next:** an owner for each tenant, with no document to change, in [A tenant owner](./tenant-owner.md).
