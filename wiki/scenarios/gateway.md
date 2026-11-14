---
title: "Gateway Topologies"
description: "Authorize at a Spring Cloud Gateway and trust the services behind it, or decide again in each service: which action names the documents use in each, with a checked app document."
---

# Gateway Topologies

For a team that puts CoSec on a Spring Cloud Gateway in front of its services. The gateway loads no route map, so what
a document has to say depends on who decides. This page compares the two topologies, says which action names each
needs, and shows an app document that serves both. Settings are in [Gateway](../integration/web.md#gateway).

## Two topologies

| | The gateway decides | The gateway and the service decide |
|---|---|---|
| Gateway | `cosec-gateway-server`, or `cosec-gateway` with `cosec-cocache` and Redis | The same, with `cosec.gateway.identity.forward-authorization: true`; the credentials still go only to `lb://` routes and routes marked `cosec-identity: true` |
| Service | `cosec-webflux` or `cosec-webmvc`, with `cosec.pipeline.mode: gateway` | The full pipeline (`enforce`), with the gateway's `cosec.jwt.secret`, Redis and `cosec.key-prefix`, and its own `cosec.authorization.scope.app-id` |
| Who authenticates | The gateway; the service reads the identity it forwards | Both |
| Action names | `http:<METHOD>` on `http:path:<external path>` only | `http:` at the gateway and, with a route map, `service:verb` in the service |
| Network | Only the gateway may reach the service | Same, and the service also checks the token |

The gateway sees the request as a client sent it, so its statements are about **external paths**, such as
`/billing/invoices/7`. A service behind it may see a different path, and with a route map a business action.

## Services that trust the gateway

Everything is decided at the edge, in the `http` namespace. Give each route its app, so that the gateway reads that
app's document and refuses credentials of another app:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: billing
              uri: lb://billing
              predicates:
                - Path=/billing/**
              metadata:
                cosec-app-id: billing
```

```yaml
# the billing service
cosec:
  pipeline:
    mode: gateway
```

A service in this mode runs no policies; it believes the identity header, so only the gateway may be able to reach it
([Services behind the gateway](../operations/deployment.md#services-behind-the-gateway)).

Permissions in the app document are written on `http` actions and external paths. Neither the OpenAPI generator nor a
`service:verb` route map applies here, so write the catalog by hand:

```json
{ "id": "invoice.read", "name": "Read invoices", "action": "http:GET", "resource": "http:path:/billing/invoices/**" }
```

## Services that decide again

When a service verifies the credential and decides itself (`forward-authorization: true`), the same request is decided
twice: at the gateway as `http:GET` on `http:path:/billing/invoices/7`, and in the service as, say,
`billing:invoice.read` on `invoice/7`. Both need an ALLOW. A permission can carry both forms, because `action` and
`resource` take lists:

<<< @/examples/gateway/documents/apps/billing.json [documents/apps/billing.json]

The statement matches when one of its actions and one of its resources match, so each hop uses the form it sees. The
scenarios in [`wiki/examples/gateway`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/gateway/scenarios) run
on every build:

| Where | Request | Result |
|---|---|---|
| Gateway | `GET /billing/invoices/7`, role `accountant` | Permit |
| Gateway | `DELETE /billing/invoices/7`, role `accountant` | Deny |
| Service | `billing:invoice.read` on `invoice/7`, role `accountant` | Permit |
| Route of app `billing` | the same, token issued for `wms` | `403` `scope-rejected` |

A DENY applies only where its form is seen: one on `http:*` is seen at the gateway, and one on `billing:*` in a service
with a route map, as in [Authorizing business actions](./route-maps.md#a-request-has-one-action). Write a DENY that must
hold at both hops in both forms.

## API keys through a gateway

A key sent to the gateway is authenticated there, and its identity is forwarded. The gateway refuses to forward the
identity of a credential that never expires, so give such a key an `expires-at`
([Forwarding the identity](../integration/web.md#forwarding-the-identity)).

## If it fails

- A service refuses requests: the identity header is missing or older than its maximum age, or the service is not in
  `gateway` mode ([A service behind the gateway refuses requests](../operations/troubleshooting.md#a-service-behind-the-gateway-refuses-requests)).
- Everything is `403` at the gateway although the service's tests pass: the document grants `service:verb`, and the
  gateway sees `http:`. Add the `http` form to the permission.
- A route of another app is reachable: its `cosec-app-id` is missing, so the client's token app is not compared with
  the route's ([Trust boundaries](../guide/security-model.md#trust-boundaries)).

**Next:** before production, the [Security model](../guide/security-model.md#hardening-checklist), then
[Deployment](../operations/deployment.md).
