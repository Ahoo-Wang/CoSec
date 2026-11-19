---
title: "Choosing a Deployment"
description: "The ways to run CoSec: one service that enforces, a gateway with services behind it, a service that verifies another's tokens, a login service and the control plane. For each, what runs, what it needs, what it must reach and what holds it safe."
---

# Choosing a Deployment

For an operator or an architect deciding which processes run CoSec and what each one needs. Every topology below is a
set of Spring Boot applications on one Redis; they differ in who decides, who verifies tokens and who holds which
secret. Read the [hardening checklist](../guide/security-model.md#hardening-checklist) with it: this page says what each
topology rests on, the checklist what to set before it carries traffic. The settings are in
[Configuration](./configuration.md), the gateway image in [Deployment](./deployment.md).

| Topology | Processes | Who decides | Who verifies tokens | Rests on |
|---|---|---|---|---|
| [One service that enforces](#one-service-that-enforces) | one application | the service | the service | the signing secret, and Redis |
| [A gateway, services in `gateway` mode](#a-gateway-with-services-behind-it) | a gateway and its services | the gateway | the gateway | the network between the gateway and the services |
| [A gateway and services that decide again](#a-gateway-and-services-that-decide-again) | a gateway and its services | both | both | the signing secret in every process, and Redis |
| [A service that verifies tokens another service issues](#a-service-that-verifies-tokens-another-service-issues) | one application beside an issuer | the service's own code | the service | the issuer's secret, and the issuer's Redis |
| [A login service](#a-login-service) | one application | | it issues | the signing secret |
| [The control plane](#the-control-plane) | one application | | | whoever holds a `(platform)` token, and write access to Redis |

## What every topology shares

- **Redis is the store of record** for the documents, revocations and sessions, with `maxmemory-policy noeviction`,
  persistence, a replica and a backup ([Redis requirements](./storage.md#redis-requirements)). Everything that reads or
  writes CoSec's keys uses one `cosec.key-prefix` per environment.
- **`cosec.jwt.secret` signs and verifies.** HMAC is symmetric: **every process that holds the secret can issue a token
  for any tenant, principal and role**, the `(platform)` administrator of the control plane included. A service that
  "only verifies" is an issuer to anyone who takes its configuration. Give the secret only to processes you would trust
  to issue, keep it in a secret store, and treat a leak as a leak of every account. Rotating it invalidates every token
  signed with the old one: at the moment instances switch, each request is `401` `token-invalid` on an instance that
  has the other secret, and every session has to log in again. There is one secret per deployment and no key id, so a
  rolling restart is a mixed fleet for its duration; plan a rotation in a quiet window, and expect to log everyone out.
- **The starter checks modules at startup** and fails naming the artifact that is missing
  ([Modules](../guide/introduction.md#modules), STA-1).

## One service that enforces

What the Quick Start builds: one Spring Boot application with the default `cosec.pipeline.mode=enforce`.

| | |
|---|---|
| Runs | the application, with `cosec-spring-boot`, its web adapter (`cosec-webflux` or `cosec-webmvc`) and `cosec-cocache` with Spring's Redis starter and CoCache's; optionally `cosec-control`, `cosec-kafka`, `cosec-micrometer` |
| Sets | `cosec.jwt.secret`, `spring.data.redis.*`, and, for what it serves, `cosec.authorization.scope.app-id` and `cosec.authorization.remote-ip.max-trusted-index` |
| Reaches | Redis (the primary), and Kafka when audit goes there |
| Rests on | the signing secret; the client address, which depends on the proxies in front of it |
| CoSec checks | the modules a feature needs; the three token stores; that Redis cannot evict; the client-address setting; readiness on the platform document |
| CoSec does not check | that Redis persists or has a replica; that two environments use two prefixes; that the secret is the same across processes |

See [Quick start](../guide/quick-start.md), [Web integration](../integration/web.md) and [Storage](./storage.md).

## A gateway with services behind it

A Spring Cloud Gateway decides with the documents; each service sets `cosec.pipeline.mode=gateway` and reads the caller
from the `CoSec-Identity` header the gateway forwards.

| | |
|---|---|
| Runs | the gateway (the [image](./deployment.md), or `cosec-gateway` with `cosec-cocache`), and each service with `cosec-webflux` or `cosec-webmvc` |
| Sets | gateway: `cosec.jwt.secret`, Redis, the routes; service: `cosec.pipeline.mode=gateway` and nothing else (no Redis, no secret) |
| Reaches | gateway: Redis and the services; service: nothing of CoSec's |
| Rests on | **the network, alone** |
| CoSec checks | a `WARN` at the service's start; startup fails when a gateway host sets `gateway` mode; per request, a header that is unreadable, from an unknown format version, too old or too large is refused |
| CoSec does not check | who can reach the service |

The header is unsigned base64url JSON, with no key and no signature. **A service in `gateway` mode believes any
`CoSec-Identity` it receives**: anyone who can send it a request can be the platform administrator. What holds the
boundary is the network: a Kubernetes `NetworkPolicy` that admits only the gateway's pods, a private network, or mutual
TLS. A namespace with no policy, which is the Kubernetes default, lets any pod in the cluster send the header.
`cosec.pipeline.identity.max-age` bounds only how old a captured header may be, and a forger sets it to now. Build
the boundary before the service is reachable at all ([Services behind the gateway](./deployment.md#services-behind-the-gateway),
[Trust boundaries](../guide/security-model.md#trust-boundaries)).

## A gateway and services that decide again

The gateway authenticates and decides, forwards the credential (`cosec.gateway.identity.forward-authorization: true`,
only to `lb://` routes and routes marked `cosec-identity: true`), and each service runs the full pipeline.

| | |
|---|---|
| Runs | the gateway as above, and services in `enforce` mode |
| Sets | on each service: the gateway's `cosec.jwt.secret`, Redis, the same `cosec.key-prefix`, and its own `cosec.authorization.scope.app-id` |
| Reaches | gateway and services: Redis |
| Rests on | the secret shared by every process (see above), and the prefix agreeing |
| CoSec checks | nothing across processes |
| CoSec does not check | that the gateway and a service hold the same secret or prefix |

A different secret shows as `401` `token-invalid` at the service; a different prefix as `503`, because the service
reads no platform document. Documents, action names and the two decisions are in
[Gateway topologies](../scenarios/gateway.md).

## A service that verifies tokens another service issues

A resource server beside an issuer: it verifies the issuer's tokens and decides in its own code.

| | |
|---|---|
| Runs | one application in `enforce` mode, with `cosec-cocache`, and no login |
| Sets | the issuer's `cosec.jwt.secret` and `cosec.jwt.algorithm`; `spring.data.redis.*` of **the issuer's Redis** and the issuer's `cosec.key-prefix`; a platform document of its own, in memory |
| Reaches | the issuer's Redis |
| Rests on | the issuer's secret, and reading the issuer's revocations |
| CoSec checks | the three token stores at startup; a `WARN` when the reader is `RevocationReader.NONE` |
| CoSec does not check | that the prefix is the issuer's: a different one reads an empty table, and revoked tokens are accepted without a message |

[A service that verifies tokens another service issues](../scenarios/resource-server.md) has the document and the
sample.

## A login service

The application that signs tokens (`cosec-login` and `cosec-jwt` come with the starter).

| | |
|---|---|
| Runs | one application with `cosec.authentication.login.*`, which can serve the refresh and logout routes |
| Sets | `cosec.jwt.*`, `cosec-cocache` for the stores; behind a gateway, `cosec.jwt.enabled=true` |
| Reaches | Redis (it writes revocations and sessions) |
| Rests on | **the signing secret, which is the root of trust of every account** |
| CoSec checks | the secret's presence, the token stores, and the refresh and logout routes at startup |
| CoSec does not check | that the gateway and the verifiers hold the same secret |

See [Authentication](../integration/authentication.md) and [What your application serves](../integration/clients.md#what-your-application-serves).

## The control plane

The only writer of documents: changes are validated, shadowed and approved before they publish.

| | |
|---|---|
| Runs | an application of your own with `cosec-control` and `cosec-cocache`. No CoSec image runs the control plane: the gateway image has none, so after the first `cosec-migrate publish` every change needs this application |
| Sets | `cosec.control.*`; Redis credentials that may write the documents; `cosec.control.enabled=false` on the data-plane instances, which then read the documents read-only; the first administrator with `cosec-migrate bootstrap`, and a way to issue a `(platform)` token |
| Reaches | Redis (writes), and Kafka when audit goes there |
| Rests on | whoever holds a `(platform)` administrator token or the signing secret; write access to Redis |
| CoSec checks | the modules, the revocation reader, the token retention against approval expiry, the control-plane beans (not replaceable), and Redis permission failures |
| CoSec does not check | that an administrator can obtain a token at all |

See [The control plane](../policies/testing.md#the-control-plane), [Storage](./storage.md#publishing-documents) and
[Change a document later](../guide/quick-start.md#change-a-document-later).

**Next:** run the gateway image and its manifests, in [Deployment](./deployment.md).
