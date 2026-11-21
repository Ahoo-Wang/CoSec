# Design: the control plane as an image CoSec ships, and as a library an IAM service embeds

## Hand-over (keep at the top)
- **Asked by:** the coordinator, for the maintainer ("RC1 之前做。控制面宿主是在网关后面的服务。"), then four settlements: the callers' trust ("按你推荐": trust by reachability, still authorized and audited), the SDK ("IAM 服务直接使用 控制面SDK"), and the two deployments ("1. 网关 + 控制面宿主 + IAM  2. 网关 + IAM（内嵌 控制面宿主）"). A design only: nothing built, no branch, no PR.
- **Revision 4.** Earlier revisions weighed the callers' authentication; that is decided and gone. This one is organised around the two topologies and only those.
- **Read against:** `main` at `8981ad49b` (#1782). No Gradle, image or system test was run. Every statement about code is from reading it; what I infer, the text says; section 10 lists what I did not check.
- **Schedule:** the implementation is the content of 8.0.0-beta.6 (after beta.5, before RC1); section 9 says which PRs must be in and which may follow in the RC.
- **Questions that remain:** 0.12.
- **Not in scope:** a web console, multi-region, asymmetric keys (8.1), a third arrangement (0.6).

## Decided (not re-argued here)
1. Trust by reachability: no key and no token on a service's call; the same trust for the gateway's forwarded requests.
2. A trusted call is still **authorized and audited**: it states the principal it acts as, the control plane decides for that principal, the record says it was asserted. One code path, no bypass. **What this is and is not:** it contains mistakes and keeps the audit; it is **not** a boundary against a compromised trusted caller, which can assert anyone, and so be both author and approver of a change. The boundary is the network.
3. Narrowing, all three: the NetworkPolicy admits exactly the gateway and the named services (one sentence on what a compromise of any of them means); the service API on a cluster-internal port of its own, apart from probes and metrics; no start without the operator's explicit statement.
4. An optional switch, off by default: service calls must present an API key (0.9).
5. Service API, first version, narrow (0.8). Token revocation and API-key management stay where they are.
6. Asymmetric keys stay 8.1.
7. **Two deployments, only these:** (1) gateway + control-plane host + IAM; (2) gateway + IAM with the control plane embedded. The IAM service uses the control-plane SDK in process in (2).

---

## 0. The frame

```
Topology 1                                    Topology 2
operator ──▶ gateway ──▶ control host         operator ──▶ gateway ──▶ IAM  (control plane embedded)
                           ▲ service API              ▲
IAM ───────────────────────┘ (direct, trusted)        IAM's own code ──▶ ControlPlane SDK, in process
```

**Both topologies are the same code.** A process that embeds the control plane *is* a control-plane instance; the image is the smallest such application: the starter with `cosec-control` and `cosec-cocache`, and a `main`. Nothing exists only in the image. Whatever the image needs (one wiring, a second listener, a switch for maintenance) is a starter feature behind a property, and the image's `application.yaml` sets the properties. That is what makes topology 2 "the same by dependency".

### 0.1 The one new piece of wiring: decide with policies, caller from the forwarded identity

What exists, read in code:
- `IdentityGate` (`core/runtime/identity/`) is a `RequestGate`: it decodes `CoSec-Identity` and builds a `RequestSecurity` (tenant, subject, roles as claims-memberships, credential, scope), refusing malformed, oversize, doubled, expired, too-old (`max-age` 60 s) or future-dated headers. The web adapters take a `RequestGate` (`CoSecPipelineWebAutoConfiguration`), so either gate fits.
- The control plane needs only that. `ControlHttp` takes the `RequestSecurity` the gate left and answers `401` without a principal; every action is decided by `PipelineControlAuthorizer` through `AuthorizationChecker.decideAs(security, principal, memberships, app, action, resource)` on the control plane's own snapshot source: **by the policies**, after confirming the documents against Redis. The credential never enters it.
- `gateway` mode has the gate and none of the rest ("no authenticator, API keys, snapshot, policies, control plane or Redis of its own"). `enforce` has the rest and the credential `Pipeline` as the gate. The `Authenticator` SPI receives `Credentials(token, apiKey)` only, so the forwarded identity cannot be an `Authenticator` without widening an SPI (ask-first; rejected).

| | A. A third mode | B. Widen `gateway` mode when the control plane is on | **C. A gate switch on `enforce` (recommended)** |
|---|---|---|---|
| Idea | `cosec.pipeline.mode=trusted` | `gateway` mode also wires the store and snapshot the control plane reads | `cosec.pipeline.mode=enforce` as today, and `cosec.pipeline.caller=forwarded-identity` (default `credential`) makes the web adapters' gate an `IdentityGate` |
| Starter change | `ConditionalOnEnforceMode` widens in about seven autoconfigurations | `CoSecSnapshotAutoConfiguration` splits into a store half and a data-plane half; the attribute providers move; readiness for the control snapshot | One bean choice where the adapters take their gate, and the guard below |
| Cost to an adopter | A new public mode: "policies plus an unsigned header" becomes a documented shape | The meaning of `gateway` changes under every service that uses it | One property with one meaning; the data plane's `Pipeline`, snapshot source and authenticators are built and idle |
| Verdict | Rejected: more vocabulary than one host needs | Rejected: reopens `gateway` for every service | **Recommended** |

**The guard follows the gate, not the mode.** `STA-7` (a statement of what the host trusts) and `STA-6` (no hidden-method filter) are written for `gateway` mode, so a host that took an `IdentityGate` another way would escape both. With C they attach to "the web adapter's gate is an `IdentityGate`": whichever mode and wiring got it, including a custom one. This closes a hole that exists today (an application that declares an `IdentityGate` bean beside `enforce` is asked for nothing). The statement's key follows what the host admits:
- only the gateway reaches it (topology 2, and any `gateway`-mode service): `cosec.pipeline.identity.only-gateway-can-reach=true`, as today;
- the gateway **and services** reach it (topology 1: a service listener is configured): `cosec.pipeline.identity.only-trusted-callers-can-reach=true`, and the gateway's key is refused there, because it would be false.

**The cost C leaves, which I have not run:** with `cosec.jwt.enabled=false` and `cosec.api-key.enabled=false` the starter must build in `enforce` with no credential authenticator (`cosecAuthenticator` takes both, I read `ConditionalOnJwtEnabled` and the composite and did not start it), and the control plane needs a `RevocationReader` over the token store without the signing secret (RC1-11: an approver's revoked token no longer approves; `revocations()` takes a bean of that type first). If either needs a code change it is small and in the starter: an `Authenticator` that never authenticates, and a read-only revocation reader over CoCache. PR 0 spikes it first.

**What the host decides, with this gate.**

| | Gateway / `enforce` host today | Control host or IAM with the control plane |
|---|---|---|
| The `http:<METHOD>` route against the platform document's statements | decided | **not decided at the host** (the gateway decides it for operators; the host's own routes are the service's own code, as in `gateway` mode) |
| Each control action, for the stated principal | n/a | **decided, by the policies**, on every call: `cosec:read`, `write`, `grant`, `publish`, `approve`, `boundary`, `breakglass`, in the app `cosec.control.app-id` |
| Rate-limit obligations, decision events of the `http` decision | at the gateway | at the gateway for operators; none at the host |
| What the host records | | the change log, with `asserted`, `via` and the peer (0.4) |

### 0.2 What the model is and is not (the sentence, and the page)

**The sentence for the page and the startup message:** *"The control plane believes whoever reaches it, and contains mistakes and keeps the audit; it is not a defence against a compromised workload that can reach it: such a workload can state any principal, a platform administrator included, can be both the author and the approver of a change, and can publish any policy of any tenant. The network is the boundary."*

What follows from it, and is stated, not hidden:
- the **author and approver of a change can be one workload**: the four-eyes control holds against an operator who goes through the gateway (the gateway verified who each one is) and against a service's mistakes, not against a compromised peer;
- an approver's revocation is read by the `jti` and subject the caller **states**: an invented one is never revoked;
- a request from outside the trusted set has nothing to distinguish it from a trusted one. The NetworkPolicy and the list of callers are the only thing that makes the model true. The page says so and does not suggest a second line of defence that is not there.

**What narrows the radius, by default (all three decided):**
1. **The statement** (0.1): no start without it; the message says the sentence above.
2. **The NetworkPolicy** names the gateway pods and the labelled service pods and nothing else, per port (0.3, 0.7). One sentence in the manifest's header and on the page: *a compromise of any admitted pod is a compromise of every tenant's policies* (topology 1) *and a compromise of the IAM service is the same, and it holds the signing secret too* (topology 2).
3. **A separate service port** with the narrow route set (0.7): a service, however it states itself, reaches only tenant-scoped reads and changes: not the platform document, not boundaries, not break-glass, not approval. This is the one place the radius shrinks by construction rather than by configuration.

**Break-glass** is a person's emergency grant and only CoSec's own verified token may use it (`BRK-8`). On this host the operator's `jwt` identity is asserted by the gateway, whose compromise already holds the signing secret, so break-glass on the **operators' port** is no worse than the gateway's own trust; on the **service port** it is not served at all. I do not recommend turning it off on the operators' port (revision 3 did, before the port split made it unnecessary).

**Auditing, not authentication, is the point of the record.** Every change event and stored change says `asserted: true` (always, on a host whose gate is an `IdentityGate`), `via` (the credential method as stated, the `jti`, the request id) and the **peer**: the raw TCP peer address of the caller, which the adapters have and `HttpFacts` does not carry (its `remoteAddress` is the client IP resolved through `X-Forwarded-For`, caller-supplied on a direct call). In-process calls (topology 2) say `via.method: in-process` and no peer.

### 0.3 Topology 1: gateway + control-plane host + IAM

| | |
|---|---|
| **Processes** | Gateways (CoSec's image), the **control host** (`cosec-control-server`, N replicas, `Recreate`), the IAM service (the adopter's: a login service in `gateway` mode, with `cosec.jwt.enabled=true` to issue), services behind the gateway. |
| **Redis write account** | The control host's alone (`cosec-control`: every key under the prefix). Gateways and services: the data-plane user (`DataPlaneAclTest`). The IAM service: the data-plane user plus the token and session keys it writes. |
| **An operator's request** | Client → gateway: the token verified, the route and its app (`cosec-app-id: cosec-control`) decided against the platform document, rate limits applied, the edge audit recorded → control host, **operators' port 8080**, with `CoSec-Identity` → the gate believes it → **each control action decided for that principal** by the policies → record with `asserted`, `via`, peer. Two decisions, two processes: the route at the gateway, the action at the host. |
| **The IAM service's own calls** | Directly to the control host's **service port 8082**, never through the gateway, with a `CoSec-Identity` of the user it forwards, or of its own principal (`svc-iam` in `(platform)`, bound to a platform policy: 0.5). No key by default; with the switch (0.9), a key. Decided at the host for the stated principal; only the service profile's routes exist on that port. |
| **Maintenance passes** | The control host's replicas (leases keep them apart). The IAM does not run them. |
| **Upgraded together** | The control host's replicas, with `cosec-migrate repair-references` across a stored-format change: `Recreate`, no mixed versions. The IAM and the gateways read documents only, not the control store, so they are not in the skew; a document `schema` they do not know fails closed for the requests that need it. |
| **What the statement covers** | `only-trusted-callers-can-reach=true`: only the gateway and the named services can reach **8080 and 8082**. |
| **NetworkPolicy** | 8080 ← gateway pods; 8082 ← pods labelled `cosec.ahoo.me/control-client=true` in the listed namespaces; 8081 (probes, metrics) ← the monitoring namespace; egress: Redis, Kafka (if on), DNS. One policy per port, not one for the pod. |

### 0.4 Topology 2: gateway + IAM with the control plane embedded

| | |
|---|---|
| **Processes** | Gateways, the **IAM service, which is the control-plane instance** (N replicas), services behind the gateway. No control host. |
| **Redis write account** | The IAM service's: the same `cosec-control`-class user (every key under the prefix) **plus** the token and session keys it already writes. Gateways and services: the data-plane user. The IAM replaces the control host as the one process that can write every document: the blast radius of the IAM service is the whole policy store, and it also holds the signing secret. |
| **An operator's request** | Client → gateway (route to the IAM's control path, `cosec-app-id: cosec-control`, the token verified, the route decided) → the IAM's own port, `CoSec-Identity` → the same gate and the same per-action decision as topology 1, because it is the same code. Nothing is exposed if the IAM sets `cosec.control.http.enabled=false` (a new switch, 0.10): then operators have no route and only the SDK exists. |
| **The IAM's own calls** | **In process**, through the SDK (0.10): `ControlPlane` is called with the caller the IAM states, built from the request's `SecurityContext` (the user it authenticated) or from its own principal. Decided by the policies for that principal, exactly as an HTTP call; the record says `via.method: in-process`. No listener, no header, no key, no hop. |
| **Maintenance passes** | The IAM's replicas, **on by default** (an instance without them leaves shadow windows open for ever and never reclaims a revision, silently). `cosec.control.maintenance.enabled=false` (new) leaves them to a control host: the third arrangement (0.6). |
| **Upgraded together** | Every IAM replica, across a stored-format change: **no mixed versions of the control plane**. A skew fails closed per record (`wiki/operations/storage.md#record-formats`): an older replica reads a newer change as unreadable (`GET /changes/{id}` is a `500`, no revision is reclaimed while it is listed open), a newer staging slot as refused (`500`), a newer document as `503` for the requests that need it, a back-reference set it cannot read blocks the check it serves (`CTL-42`, `503`), and the replica logs the record, its format and `cosec-migrate repair-references` once at `ERROR`. **Login is unaffected** (it does not use the control store), so a rolling upgrade of the IAM degrades the control plane fail-closed for the length of the roll and nothing else. Between formats, a rolling upgrade is fine; across one, the control features are down until every replica is new, and the page says so. |
| **What the statement covers** | `only-gateway-can-reach=true`, as for every service behind the gateway: no other service calls the IAM's control path. |
| **NetworkPolicy** | The IAM's port ← gateway pods (the existing example, `k8s/examples/gateway-mode-service-networkpolicy.yaml`); probes and metrics apart. |
| **What the IAM takes on that it did not have** | Redis and documents: the IAM is now `enforce`-wired, so **its readiness waits for the platform document** (a login service that was ready on an empty Redis is not any more) and its startup fails on `maxmemory-policy`, a Redis user that cannot publish (`Redis denied publishing to the invalidation channel[...]`, the "no write access" refusal), a missing `RevocationReader`, `STA-6`/`STA-7`. Migrating a `gateway`-mode IAM is `cosec.pipeline.mode=enforce` plus `cosec.pipeline.caller=forwarded-identity`, the statement, the store, and the checks above. It does not take on the control store's format in readiness (an alert, as today). |
| **The startup line** | One `INFO` at start: `Control plane embedded: maintenance passes on, HTTP routes on /cosec/control, caller from the forwarded identity (statement: only-gateway-can-reach)`, so an operator can see which instance runs what. |

### 0.5 What a caller states, and how a service's principal comes to exist

**An operator.** The gateway verifies the token, decides the route, forwards `CoSec-Identity` (a `lb://` route receives it by default; nothing new is asked of the gateway): the tenant, subject, app, space, roles it resolved, `credential.method: jwt`, `jti`, `exp`, `iat`.

**A service acting for a user** (the IAM forwarding a signed-in user). It states the user: `tenant`, `subject`, `roles` from its data, `credential.method: jwt` if the user holds CoSec's token it verified or issued, a `jti`, an `exp` and a fresh `iat` per call (`max-age` 60 s).

**A service acting in its own name** (onboarding, reading). A principal like any other of the platform tenant: `tenant: (platform)`, `subject: svc-iam`, `app: cosec-control`, `roles: []`, `credential: { method: api-key, jti: svc-iam, exp: now+1m }`. The schema has no "service" form and I would not add one.
- **`api-key` is the method the control plane already treats as a machine**: `AuthenticatedPrincipal.apiKey = apiKeyCredential || credential.method == API_KEY`, and `ApprovalRules` and `ChangeAuthority` bar it from approving, from being the trust root, and from break-glass (`BRK-8`). A service that names its credential so is barred from the three powers that are a person's, with no code. It is a misnomer (there is no key) and I recommend it for RC1; the clean `service` method, with the same treatment for every asserted method but `jwt`, is a follow-up (a rule and a small change in `cosec-control`).
- **How the principal exists (bootstrap).** CoSec has no principal registry. `cosec-migrate publish` (the platform document), `cosec-migrate bootstrap --platform-admin <operator>` (the first operator), then **the first operator writes the service's grants through the control plane**: a platform policy (`control-service`: tenant-scoped `cosec:read`, `write`, `grant`, `publish`, `skip-shadow`; never `approve`, `platform-admin`, `boundary`, `breakglass`) and a **principal binding** of `svc-iam` to it. I recommend the binding over a role the service claims: the service then chooses who it says it is but not how much power it brings, and its grants are a reviewed document change. In topology 2 the IAM's own principal is the same, stated in process.
- **Writing the header.** `IdentityCodec` is `@InternalCoSecApi`: an application cannot use it, and the schema is the only public form. Topology 1 needs a public writer (0.10): `@ExperimentalCoSecApi` in `cosec-document` for Kotlin, a function in `@ahoo-wang/cosec-fetcher` for TypeScript. The testkit's `IdentityHeader` stays for tests.

**The client IP of a direct caller is caller-supplied** (`cosec.authorization.remote-ip.max-trusted-index` is one value per process: `1` behind the gateway, but a service has no proxy, so the entry taken is the one the service sent). Documents must not restrict a service by `remote-ip` or `ext.ip2region.*`; the raw TCP peer is what the record keeps.

### 0.6 A third arrangement, which falls out for free: refused to design, supported by construction

An IAM service that embeds the SDK **while a control host also runs** is two control-plane instances in two processes over one store: the same thing as two replicas. Supported by construction (leases and compare-and-set make two instances safe), with three conditions the page states: both write account users, the same version across a stored-format change (as for replicas), and the embedder sets `cosec.control.maintenance.enabled=false` unless it wants to share the passes. Nothing refuses it and nothing tests it by default; I do not design for it.

### 0.7 The listeners: operators' API, service API, management

Three ports in topology 1, each with its own NetworkPolicy rule and its own purpose:

| Port | Property | Serves | Reached by |
|---|---|---|---|
| **8080** | `server.port` | The control API in full (the existing routes), for operators | The gateway pods |
| **8082** | `cosec.control.http.service-port` (unset: none) | The **service profile**: the same routes, narrowed (0.8), nothing else | The labelled service pods |
| **8081** | `management.server.port` | Probes and metrics (liveness, readiness, `/actuator/prometheus`) | Kubelet, monitoring |

- **How the second port is served.** Spring Boot has one application server per context (the management port is a child context). The service port is a second Reactor Netty `HttpServer` over the same `HttpHandler`, started by a `SmartLifecycle` in the starter (about 60 lines, WebFlux only; on MVC a service port is not offered). A filter reads `exchange.request.localAddress.port` and gives each port its profile; `ControlHttp` takes the profile (a small addition to `cosec-control`: it knows its routes, so the narrowing lives with them and `ControlHttp` rejects a route outside the profile with `404`, before any body is read). **A spike in PR 1 confirms the second server starts with the statement, graceful shutdown and the same filters**; if it does not, the fallback is a second Boot management-style child context, still free of Spring Cloud Gateway.
- **Why not one port and a filter on the path.** A path filter cannot be told apart by the NetworkPolicy: the policy would admit the services to 8080 and the narrowing would rest on a filter the compromised caller is trying to get around. Two ports let the network, not the code, say who may ask for what.
- **The probes and metrics are on their own port today** (`management.server.port=8081`, the gateway image's own choice). That is the "port apart from probes and metrics": free.
- **Topology 2** has one application port (the IAM's) and no service port.

### 0.8 The service API, first version, narrow

The service profile is the **tenant-scoped subset of the existing routes** plus one new read, and the SDK exposes the same operations (0.10), so there is one scope.

| Route | Profile | Notes |
|---|---|---|
| `GET /documents/{key}`, `/revisions`, `/revisions/{rev}`, `/diff` with `key = tenant:<id>` or `app:<id>` | **service** (read) | Roles as written; the effective ones through the diff. Spaces and a principal's bindings are in the document: filter client side (about 20 bindings a tenant at the target). Spaces have **no registry** in CoSec (an id in memberships and bindings): not a control-plane question. |
| `GET /documents?kind=tenant\|app&after=&limit=` | **service** (read), **new** | Which tenants and apps exist, paged from the store's document list (2.1 MB at 50,000 tenants). Authorized as `cosec:read` on `doc/platform` for the list. The only new route. |
| `GET /documents/tenant:<id>/bindings?subject=` | **service** (read), **new, optional** | A convenience over the document read; I would not add it until a caller needs it. |
| `POST /documents/tenant:<id>:apply`, `POST /changes` (key `tenant:<id>`) | **service** (write) | Tenant documents only, authorized for the stated principal by `cosec:write`/`grant` as always. |
| `GET /changes/{id}`, `GET /changes?document=&state=`, `:rebase`, `:abandon`, `:skip-shadow`, `:publish` | **service** (write) | The propose-and-publish flow, below. |
| `:shadow`, `:approve`, `:reject`, `:rollback`, `/break-glass*`, `simulate`, `explain`, `analyze`, `propagation`, anything with key `platform` | **operators only** | A person's actions, or the platform's. |

**The documented flow for a change that needs no approval** (a tenant onboarding, a role added): `apply` (→ `CHECKED`) → `skip-shadow` where the tenant has no traffic (→ `READY`, or `AWAITING_APPROVAL` when the analysis finds the change escalates or the platform asks for four eyes) → `publish` (→ `PUBLISHED`). A client loops on `state` and **stops at `AWAITING_APPROVAL`**, reporting the change id to a person who approves on the operators' port. A retry after a timeout first finds its change with `GET /changes?document=&state=`, since `apply` opens a new one each time. The flow is a documented client (TypeScript `cosec-fetcher` grows it; a Kotlin sample in `wiki/examples/control-service/`), not a new route.
- **Open, to measure before promising:** whether granting `cosec:tenant-admin` in a brand-new tenant's document is "contained" for the analysis. If yes, onboarding completes without a human; if not, it stops at `AWAITING_APPROVAL` by design, and I would not remove that. I read the approval rules and did not run the case; the system test's tenant scenarios decide it in PR 5.
- **Token revocation and API-key management stay where they are.** I looked for whether the login service cannot do without them in the control plane, and it can: the IAM holds the token stores (it issues), so it revokes in its own process with `TokenRevoker` today, and logout, subject revocation and refresh are already its own routes. An operator's lock-out of a compromised principal from a console is a real need, but it is the operator's, not the IAM's, and it belongs to a later `cosec:revoke` action (its own RC1.1 design: a new action, a new route, a rule). API keys: `RedisApiKeyStore.issue/revoke` are in-process calls of whichever service manages keys; a control route that issues a key for any principal lets whoever holds `cosec:write` mint the powers of `platform-admin`, which no existing action analyses. If the optional switch (0.9) ships, **one CLI** (`cosec-migrate api-keys issue|revoke`, run by someone with store credentials, who is already the trust root) is the only addition.

### 0.9 The optional switch: service calls must present an API key (off by default)

`cosec.control.http.service-port-api-key=required` (default `off`). It adds a credential to the **service port only**; the operators' port never changes, so what the gateway's requests do when it is on is: nothing (they arrive on 8080, trusted, as before; a gateway cannot present a key, and binding the gateway too is what mutual TLS is for, a separate hardening).

**How it composes with the trusted identity.** The service port's gate becomes two steps:
1. **The key proves the caller.** The request goes through the host's own `Pipeline` (which `enforce` already builds), which authenticates `X-API-Key` with the existing `ApiKeyAuthenticator`, decides the `http:` route for the **caller** against the platform document (so the platform document says which services may reach the service API at all), and yields the caller's principal. No key, an unknown, disabled or expired one: `401 token-invalid`, as everywhere.
2. **The forwarded identity says for whom.** If the request also carries `CoSec-Identity`, the gate takes the stated principal as the **actor** of the control action and records the key's principal as `via.caller`. If it carries none, the key's own principal is the actor.

So the record says both: *who was proven* and *who was said*. The control plane still decides for the actor.
- **This is the delegation `IDN-4` refuses for credentials** ("a key cannot act for someone else"). The distinction is that no credential is delegated: the service vouches, within the trust the key establishes, for a principal's identity. The rule needs one sentence saying so, and the page must not call it delegation.
- **The `IDN-6` obstacle:** `cosec.api-key.enabled=true` fails startup without a `MembershipProvider`, and the image has none. With the switch on, JWT is off and no token carries the `provider` marker, so the deny-bypass hazard of a provider that says "known" does not arise here. I propose a small first-party provider in the starter, enabled by the same switch: known, with no roles, for principals of `(platform)`; unknown for every other tenant. A service is deactivated by disabling or revoking its key (IDN-3's "deactivate the principal" does not apply). A rule is needed.
- **Where it ends:** the key store is Redis (`cosec.api-key.store=redis`), which the control account reads; keys are issued by the CLI above.

### 0.10 The SDK: topology 2, in process

**What it is.** A **narrow facade**, not `ControlPlane` itself. `ControlPlane` is `@InternalCoSecApi` today, with a signature surface of about twenty values (`PolicyDocument`, `ChangeRequest`, `Operation`, `Confirmation`, `AccessQuery`, `AccessReport`, `Simulation`, `Explanation`, `Propagation`, `ViewVersion`, `BreakGlass`...) that G2e is still shaping. Promoting all of it freezes all of it. The SDK is the **service profile as a Kotlin interface**, `@ExperimentalCoSecApi`, in `cosec-control` (package `me.ahoo.cosec.control.sdk`): the same operations as 0.8, over the values they need (`SnapshotKey`, `PolicyDocument`, `ApplyMode`, `ChangeId`, `ChangeRequest`, `ChangeState`, `ChangePage`, `RevisionSummary`, `DocumentDiff`), and nothing else. It is implemented over `ControlPlane`, which stays internal, with every other declaration of `cosec-control`. No new module: the facade lives in `cosec-control`, whose ABI dump records it as experimental (`@ExperimentalCoSecApi` is in the dumps, as `AGENTS.md` says), and `ControlBoundaryTest` is unchanged.
- **One scope for two ways in.** The HTTP service port and the SDK are the same operations, decided by the same authorizer, so a change to one is a change to the other, and the system test's service-path scenarios run against both.

**How application code says who is acting.** `Operator` is `@InternalCoSecApi`, built from the `RequestSecurity` only the adapter makes (`PIP-29`: the testkit binds a context only through a pipeline-made one). The SDK's public value is `ControlCaller` (`@ExperimentalCoSecApi`):
- `ControlCaller.of(context: SecurityContext)`: the user whose request the IAM is serving. The context is the one the gate made (a controller's injected `SecurityContext`); a context not made by the gate is refused, as `PIP-29` refuses one.
- `ControlCaller.service(principal)`: the IAM's own principal, for work with no request (a job, a start-up onboarding). It builds the same identity a header would carry (0.5: method `api-key`, `(platform)`, a short `exp`) and passes it through the **same function `IdentityGate` uses** to turn an `Identity` into a `RequestSecurity`, now shared (a small refactor of `IdentityGate`). So in-process and over HTTP the validation is one code path and the decision is one pipeline. **No opt-in to `InternalCoSecApi` is needed or possible** (`SourceRulesTest` already forbids it in doc samples), which answers `ConstructibleApiTest`'s question: an application constructs a `ControlCaller` from stable (`SecurityContext`, `PrincipalId`) or experimental values only.
- Usage, in the IAM: `controlPlane.changes.apply(ControlCaller.of(securityContext), key, document, ApplyMode.MERGE, "onboard acme")`.

**Authorization in process.** Through the pipeline's policies like any control write: the SDK builds the `Operator` and calls `ControlPlane`, whose `PipelineControlAuthorizer` confirms the documents and decides for the principal the caller states. What the IAM must run: `enforce` with the caller from the forwarded identity (0.1), because the control plane needs the store and snapshot and not the data plane's `Pipeline`. **This is a change for an existing IAM in `gateway` mode** (0.4's migration paragraph).

**A second reading, a typed HTTP client** for the service port (a Kotlin class over the JDK client or WebClient, and the TypeScript one): it adds the header writer and the flow above as code instead of a sample, and the compatibility surface of the client. About a fifth of a window beyond what 0.8 already needs, and needed only for IAM services that are not Kotlin or not embedding. A later addition: nothing in the design blocks it.

**`cosec.control.http.enabled`** (new, default `true`): `false` serves no control routes at all (the SDK only). **`cosec.control.maintenance.enabled`** (new, default `true`): whether the instance runs the maintenance passes. Both are `configuration.md` rows.

### 0.11 The page: "three ways to reach the control plane"

In `wiki/operations/topologies.md`, the control-plane section is rewritten around **two named topologies** and one sentence for the third:

| | Topology 1: gateway + control-plane host + IAM | Topology 2: gateway + IAM with the control plane embedded |
|---|---|---|
| Choose it when | The control plane should scale, upgrade and fail apart from login; the IAM is not a Kotlin/Spring application you can link; several services call the control plane. | The IAM is your own Kotlin/Spring service and the control plane's operators and callers are few; one fewer deployment. |
| Operators reach it | Through the gateway, to the host. | Through the gateway, to the IAM, or not at all (`http.enabled=false`). |
| The IAM calls it | Directly, as a trusted service, over HTTP. | In process, through the SDK. |
| What a compromise costs | The host, the gateway or any admitted service: every tenant's policies. | The IAM: every tenant's policies and the signing secret. |
| Upgrading | The host: stop, convert, start. | Every IAM replica together across a format change. |

A third arrangement (the SDK in the IAM while a host also runs) is the page's last paragraph, supported as two replicas are. The sentence of 0.2 is on the page, once, in the control-plane section, and again in the NetworkPolicy manifest's header.

### 0.12 The questions that remain

1. **Confirm C**: the `cosec.pipeline.caller=forwarded-identity` switch on `enforce`, with the guard following the gate (STA-6/STA-7 amended), the two statement keys, and the second key refused for a host with a service listener.
2. **The SDK as a narrow facade in `cosec-control`** (`@ExperimentalCoSecApi`), with `ControlPlane` staying internal: yes, or the whole `ControlPlane` promoted.
3. **The service port and the service profile** (a second Netty server in the starter; `ControlHttp` takes a profile): yes. If the spike fails, the fallback is a child context.
4. **The new public pieces to approve** (ask-first): `ControlCaller`, the facade, the header writer in `cosec-document` (Kotlin) and `cosec-fetcher` (TypeScript), the properties `cosec.pipeline.caller`, `cosec.pipeline.identity.only-trusted-callers-can-reach`, `cosec.control.http.service-port`, `cosec.control.http.enabled`, `cosec.control.maintenance.enabled`, the service-port key switch.
5. **The method a service names for itself**: `api-key` for RC1 (zero code, fails safe), or `service` now (a rule and a change in `cosec-control`).
6. **The listing route** (`GET /documents?kind=`): yes (it is the only new route of the first version) and the optional bindings route: later.
7. **Ids** for the rules: the generalised STA-6/STA-7; the statement keys; the record's `asserted`/`via`/peer; the service profile (routes outside it are `404`); `maintenance.enabled`; the service-port key switch and its provider; readiness on an unconverted store (the image only).
8. **Replicas:** one by default, two supported.

---

## 1. What is in it

**Starting point, `test/system-auth/build.gradle.kts`:** `:spring-boot`, `:webflux`, `:cocache`, `:control`, the CoCache starter, `spring-boot-starter-webflux`, `-data-redis`, `-actuator`, `kotlinx-coroutines-reactor`. `:spring-boot` brings `cosec-runtime`, `cosec-login` and `cosec-jwt`.

**Principle: the image is the starter with the control plane and nothing else; its capability set is the gateway image's, minus the gateway, plus the control plane.** The reason is not tidiness: a change is *compiled on the control host*, and it must compile on every instance that will install it. `wiki/operations/deployment.md` already says the control host "must register the same `AttributeProvider`s, declaring the same `ext.*` attributes", or a change that the control host accepts can fail on a gateway without the provider (in a shadow window that instance reports `CANDIDATE_ERROR`). So any module on the gateway image that contributes to compilation must be on the control image too.

| Module | In? | Why |
|---|---|---|
| `spring-boot`, `webflux`, `cocache`, `control` | in | The host, the adapter, the store, the plane. |
| `jwt`, `login` (through the starter) | on the classpath, **both off** | The control server verifies no token and issues none (section 0): `cosec.jwt.enabled=false`, so it holds no signing secret. They come with the starter; the image keeps them off and refuses to start with JWT or API keys on (section 8). |
| `kafka` (change listener) | in, **off by default** | Same default as the gateway (`cosec.audit.kafka.enabled: false`): `KafkaAutoConfiguration` otherwise builds a producer to `localhost:9092` and the best-effort listener logs on every change. An adopter that keeps an audit trail turns it on with `COSEC_CONTROL_AUDIT_KAFKA_ENABLED=true` and `SPRING_KAFKA_BOOTSTRAP_SERVERS`. Break-glass needs `delivery=required` (RFC 0007 §8), so a control plane without Kafka cannot grant break-glass: the doc says so. |
| `micrometer` + Prometheus registry | in | The gateway's set. The meters of the snapshot sources and the listeners are what an operator scrapes; the system test reads them (`Metrics.sum`). |
| `opentelemetry` | in | Parity with the gateway: one hop of one trace. Cheap on the classpath, off until configured. |
| `ip2region` | in, **off by default** | The compile rule above: a platform document that reads `ext.ip2region.*` must compile on the control host. It is on exactly where it is on the gateways (`COSEC_IP2REGION_ENABLED=true`, about 11 MB of heap). The doc says "same as your gateways". |
| `cosid` | **out** | Change ids come from `RandomIdGenerator`; CosId adds a dependency for sortable ids nobody asked for (`DependencyRules.COSID_OWNERS` keeps it in the starter and `cosid`). |
| `gateway`, Spring Cloud Gateway | **out** | The server is not a gateway; a gateway host also cannot run `gateway` mode and would add `IdentityStripFilter`s it does not need. |
| `webmvc` | **out** | One stack. WebFlux because `ControlHttp` runs on `Dispatchers.Default` through `mono`, with no thread blocked; MVC would `runBlocking` per request. |
| `social` | **out** | No login in the image. |
| `openapi` | **out** | A generator of route maps for services; the control plane reads maps through `cosec.control.analysis.route-maps` (resource locations an adopter mounts). |

**Rejected alternative: a library `cosec-control-server` on Maven Central.** Contradicts the decision ("never published, shipped as an image", like `gateway-server`) and would put an application's `main` in `verifyUnpublished`'s blind spot. **Rejected alternative: ship the control plane inside the gateway image** behind `cosec.control.enabled`. It is what `wiki/operations/topologies.md` says CoSec does not do ("the gateway image has none"), and it puts a write-capable Redis user in the process every client reaches, which is the first thing the Redis ACL section tries to prevent.

**Source of the project.** One Kotlin file (`ControlServer.kt`: `@SpringBootApplication`, `main`), `application.yaml`, `banner.txt`, `Dockerfile`, `aot-train.sh` (shared, section 6), `build.gradle.kts`. Name: project `:control-server` in `platform/control-server`, application `cosec-control-server`, image `ahoowang/cosec-control` and `ghcr.io/ahoo-wang/cosec-control` (as `cosec-gateway`).

**The image's `application.yaml` (defaults in the jar; a mounted `config/application.yaml` wins key by key, as the gateway's):**

```yaml
server: { port: 8080 }                        # the operators' API
spring: { application: { name: ${service.name:cosec-control} } }
cosec:
  pipeline: { mode: enforce, caller: forwarded-identity }   # policies wired; the gate is the IdentityGate (0.1)
  jwt: { enabled: false }                     # no token is verified or issued here: no COSEC_JWT_SECRET
  api-key: { enabled: false }                 # on only with the optional switch (0.9)
  authorization:
    scope: { app-id: ${cosec.control.app-id:cosec-control} }
    shadow: { enabled: false }                # a replica is not a data-plane instance a window waits for (section 3)
  authentication: { login: { enabled: false } }
  audit: { kafka: { enabled: false } }
  control:
    http: { service-port: 8082 }              # the service API, its own port (0.7)
    audit: { kafka: { enabled: false } }
  ip2region: { enabled: false }
management:
  server: { port: 8081 }                      # probes and scrape, outside CoSec, like the gateway's
  endpoint: { health: { probes: { enabled: true } } }
  endpoints: { web: { exposure: { include: health,prometheus } } }
```

`cosec.authorization.scope.app-id` is the host app: an identity that states another app is `403 scope-rejected` (the gate resolves the scope as any host does); binding it to `cosec.control.app-id` means one value to change. **Nothing here exists only in the image**: every key above is a starter property with a default, and the image sets them; an IAM that embeds the control plane (topology 2) sets the same keys with `service-port` unset, JWT on, and `statement: only-gateway-can-reach`. The statement itself is **not** in the defaults: it is the operator's, and the host does not start without it (section 7).

---

## 2. Login and token issuing

**Decision: not in the image, not even off. The control server verifies no token and issues none** (it believes the identity it is sent, section 0).

What there is to switch on: nothing. The starter's login routes need an `Authentication<T>` bean, and CoSec ships none (`system-auth`'s is `SystemLoginAuthentication`, which has no password check and says it "must never be anything else"). "In it and off by default" would ship a switch that, turned on, fails startup for want of a bean the adopter would have to supply, by building a derived image. That is the worst of both: the surface exists in the artifact, and the adopter still writes the code.

From what an adopter with tens of thousands of tenants runs: it already has an identity service. It issues CoSec's tokens (the claims, `jti`, `sid`, the app and tenant) with `TokenConverter` and the shared `cosec.jwt` settings, in its own application, with its own user store, MFA, lockout and tenant-aware sign-in. The control plane's operators are the same people it signs in the same way: platform administrators in `(platform)` and, where tenants customise their own roles, tenant administrators. A second sign-in path in a CoSec-owned image would be a second place to harden, and it would sign with the same secret.

**Rejected: login on, with a built-in user store.** CoSec does no password handling by design (`introduction.md#what-cosec-does-not-do`); this is a product, not a host. **Rejected: login in the image off by default.** See above.

**What the system test uses.** `test/system-auth` stays as the test's identity service and loses its control plane: no `:control` dependency, so it is the login host (`/auth/login`, the starter's refresh and logout, `/admin/revoke-subject`) and nothing else. I keep the name `system-auth` (a rename churns `ProjectListTest`, `plan.py`, the labeler, Compose and the README for no gain). **Rejected: delete it.** The scenarios need tokens of arbitrary tenant, app and roles with no password, and the control server must not be the thing that issues them.

**The login and authentication service is a *caller* of the control server**, the maintainer's example of one. It needs no credential from anyone: it sends a `CoSec-Identity` of its own principal, or of the user it forwards (0.3), directly, not through the gateway. The system test's `auth` host plays that part for the service-path scenarios (it calls the service port with the public header writer: section 8).

**Consequence to say out loud:** refresh and logout of an operator token are the issuer's routes, not the control server's, and `cosec.authentication.login.*.path` in the image is unset. The operator's token never reaches the control server at all: the gateway verifies it.

**No gap on day one at the control server:** the first operator's token is the gateway's concern (the adopter's login service issues it, as for every route); what the control plane needs is `cosec-migrate publish` and `bootstrap`, which exist. There is no `issue-token` command to add (it was revision 2's question 3).

---

## 3. Redis accounts

The gateway-side lines exist: `wiki/operations/storage.md#who-may-write-which-keys`, held by `DataPlaneAclTest` (`host/spring-boot`, on real Redis, every CI run): `%R~` on `snapshot:*`, `candidate:*`, `candidate-staged:*`, write on `shadow:*` for the instance's own keys, no `control:*`. `cosec-pod-a` is that user.

**The control-plane counterpart does not exist as a held artifact.** The doc says "Control-plane credentials: every key under `<s>:`" and points to CoCache's command list for the set stores. Nothing runs it. The brief says the write account is the control host's alone, so it has to be tested the way the data-plane user is.

**Three accounts, then:**

| Account | Keys | Commands | Held by |
|---|---|---|---|
| `cosec-gw` (every gateway, every enforcing service) | the existing line | the existing line | `DataPlaneAclTest` (exists) |
| `cosec-control` (the control server's replicas) | `~cosec:*` and `&cosec:*` (every key and channel under the prefix), plus `~cocache:!probe:*` / `&cocache:!probe:*` with `channel-mode: pubsub` | the data-plane commands plus what CoCache's set stores and the control store write: the set commands the doc names; `+hset +hdel +hlen +pexpire +persist` are already in the data-plane list, the rest to be taken from the test | **new `ControlPlaneAclTest`** |
| `cosec-migrate` (operators' CLI: `publish`, `bootstrap`, `repair-references`, `verify-chain`, `restore`) | as `cosec-control` | as `cosec-control` | the same test, as a second user, because it connects with the same command set but not through CoCache's runtime |

`ControlPlaneAclTest` mirrors `DataPlaneAclTest`: on real Redis with the three users, `ACL DRYRUN` and real operations: the control user starts a control plane (store, snapshot source, publishes a candidate and a head), a data-plane user hears the publish and cannot `SET` a head, and the `ACL LOG` is empty. The commands list is derived by running, not by copying CoCache's page: exactly what `DataPlaneAclTest` did for the data plane.

**Service callers need no account of their own**: the control server's account does the Redis work for every caller, and no caller presents a credential to Redis or to the host.

**Revocations** (`<s>:token:*`): the control server reads them (an approver's token at publish, RC1-11) through a `RevocationReader` of its own over the CoCache token store, and writes none, because it issues and revokes nothing. The doc says token keys stay read-write "wherever JWT is on"; JWT is off here, so a narrower line, `%R~cosec:token:*`, is possible and I would try it in `ControlPlaneAclTest` (PR 6) rather than claim it. Subject revocation (3.2, optional) would need write on them.

**`cosec.authorization.shadow.enabled: false` on the control server** (the image default above). With it on, each replica is an instance a shadow window waits for (a listed instance without a report keeps the window open) and writes `shadow:report`, `status`, `reporters` and `instances` keys. The control server decides only operators' requests on the control app: sampling them tells nobody anything about a candidate. Off, the replicas are not part of any window and write no shadow keys, which also makes the control account's `shadow:*` rights read-only in practice. **Rejected: leave it on.** A control-server replica that is slow to start or restarts mid-window would block windows for a reason unrelated to the data plane.

**The system test's Redis runs with the ACL users** (Compose `redis` with a `users.acl` file): the gateways, `behind`, `enforcer` and `auth` get `cosec-gw`; only `control` gets `cosec-control`. That turns the "writes only from the control host" claim into a scenario. Two parts: with the ACL on, every other scenario still passes (so the data-plane line is enough for a real deployment), and a `redis-cli` as `cosec-gw` that `SET`s a head is refused (`NOPERM`). The control API cannot show the second part, since a request never carries a Redis credential; the harness connects to Redis directly for it, as it already does for `docker compose stop redis`. Costs: `auth` needs the token keys read-write (it issues tokens), so its user is `cosec-gw` plus `~cosec:token:*` write; to be taken from `DataPlaneAclTest`'s "other families were not tried" caveat, which the system test would then also close for tokens.

---

## 4. Running it

**Replicas.** Supported by design: every maintenance pass takes a lease, "one instance at a time does each document" (`cosec.control.revisions.reclaim-interval`), the shadow check is "by one instance at a time", and changes advance by compare-and-set on heads. I infer, not verified end to end, that a second replica is safe; the system test proves it (below). Default **one replica** in the manifests (a few requests an hour from humans; a restart is seconds; HA of a console is not worth a second JVM with its own document cache), **two supported** with a `PodDisruptionBudget` in the production overlay. **Rejected: HPA** (the load is operators, not traffic).

**Upgrades across a stored-format change** ("stop the control plane, upgrade, start"; `repair-references`). With a format change between versions, old and new replicas must not run together: an old replica fails closed on what it cannot read (`CTL-42`), but a new replica writes sets an old one reads wrongly. So the control server's Deployment is `strategy: Recreate`, and the upgrade page says: stop the control server, upgrade the image, run `cosec-migrate repair-references` (a lease two runs share; idempotent) against the store, start. A rolling update is correct only when the release notes say the store format did not change. **Rejected: `RollingUpdate` by default.** It is right most days and wrong on the day it matters; a default must be right on that day. The data plane is unaffected: gateways read documents, never the control store.

**Probes and readiness.** The WebFlux application port is decided by the pipeline, so its actuator would be `401`/`403` to a kubelet, and refused with `503` until the platform document exists. Probes therefore run on the **management port 8081** (outside CoSec, like the gateway's actuator; it is published to monitoring only): `startupProbe` and `livenessProbe` on `/actuator/health/liveness`, `readinessProbe` on `/actuator/health/readiness`. Readiness is `REFUSING_TRAFFIC` until the platform document is installed (the data plane's `SnapshotReadiness`), plus one thing the control server adds: the `cosecControlStoreFormat` indicator (an old open-change or document list present, `CTL-54`) joins the readiness group, so an unconverted store is *unready*, visible and diagnosable instead of a pod that serves `503`s for a reason the kubelet cannot see (section 8). **Rejected: probe the application port with a `tcpSocket`** (the system-auth trick): it proves a listener, not that the plane works.

**The maintenance scheduler** (`ControlMaintenanceScheduler`) starts with the context on every replica: `reconcileApps` and `keepStagedList` at start with a backoff to a minute, `reclaim` every `reclaim-interval` (1h), `closeShadowWindows` every `check-interval` (1m). Leases keep two replicas apart; a failed pass is logged and tried again. The image changes none of the defaults. Shutdown: the Deployment's `terminationGracePeriodSeconds: 45` and a `preStop` of 5 s, as the gateway's, plus the change listeners' drain (`cosec.observability.listener.drain-timeout`, 5 s default).

**What it logs and meters.** `LoggingChangeListener` logs every change step (always on, the "change log"); the Kafka listener adds events when on; the starter's meters are on the management port. No new meters in the image: `micrometer` has none for the control plane (grep of its sources; `observability.md` documents the change log, the events and the `cosecControlStoreFormat` indicator, no control meter). The operator's signals are the change events (log and Kafka), the health indicators and the snapshot meters of the two sources. I would not invent a metric before the system test shows what an operator cannot see; if one is wanted (changes by state, a maintenance pass's age), it is its own PR on `cosec-control`, not this work.

**Heap and the memory limit for the target (10k+ tenants, 100k+ members, a dozen apps by a dozen roles).** From `saas-app-change-cost.md` (the numbers are that session's, computed for Redis, measured in memory), not from a run of this image:
- The control host holds **two** snapshot sources (the pipeline's, and `ControlSnapshot`'s, "apart from the data plane's"). Each holds an entry per tenant it decides for. The control server decides only operators' requests, but a SaaS where tenants customise their own roles through the control API has tenant administrators: up to `max-tenants` (16,384) entries of about 30 KB each, about 490 MB, **twice**.
- An app change at 50,000 tenants (the case of the note): one change record of 48 MB, rewritten at every transition, and the audit event of 45 MB, until the planned fix of that note lands (PR A/B of G3). With the fix the record is bounded by the first 20 referrers per widened policy.
- So: the image defaults to the gateway's JVM arguments, with **`-Xms512M -Xmx2048M`** (ZGC), the kind base at **1536Mi** with `-Xmx1G` and `cosec.authorization.snapshot.max-tenants: 2048` (a dev cluster), the production overlay at the gateway's rule (heap 2 GiB, limit **3200Mi**, "the heap, plus direct, plus about 250 MiB, plus a quarter") and `max-tenants` left at its default. **Rule written down in the docs:** size the control server's heap at `2 × (max-tenants × 30 KB) + 256 MiB + the largest change record × 3`, then the limit by the gateway's rule. I would not publish numbers of my own for the 50,000-tenant app change until G3 has landed and the system test (or a `jmh`) has measured the control host with a real change; the note says "computed", and so does this one.
- **Rejected: a smaller default heap "because it is a console".** The failure is a pod OOM during an app change that a platform administrator is waiting on, on a Redis already partly written.

**System test, replicas:** the Compose file runs `control-1` and `control-2` (same image, same ACL user). One scenario proposes on one and approves and publishes on the other, and reads the window's state from both; another stops `control-1` mid-window and the window still closes (the lease moves). This is how "two replicas are supported" becomes a claim with a test, as the gateway's two replicas did.

---

## 5. Shipping it

**Dockerfile.** A copy of the gateway's with the name changed, plus what the control server does not need removed: no `HEALTHCHECK` on the application port (the management port's liveness, with `curl`), `EXPOSE 8080 8081`, `ENTRYPOINT ["bin/cosec-control-server"]`, `JDK_JAVA_OPTIONS` carrying `-XX:AOTCache`. JDK 25 on Dragonwell as the gateway's, same reason (the AOT cache; the libraries are compiled for 21).

**What of the AOT/CDS work applies.** All of it in method: record then create, in the final stage, from the same path, with the throwaway secret, `cocache.backend=memory`, ephemeral ports, the management port read from the JVM's own log, SIGTERM. `aot-train.sh` is written for the gateway (`bin/cosec-gateway-server`, a few requests to the gateway). Two things differ: the binary's name, and the training workload (the control server on the in-memory CoCache backend starts, listens and answers `401` to a control call: enough to load the web and pipeline classes; the control classes load at startup through the beans). **To be verified in the first PR, not assumed:** that the control plane starts on `cocache.backend=memory` without Redis (the e2e tests suggest so; I have not run it). If it does not, training needs a different path and the cache is optional (`AOTMode=auto` falls back).
- **Decision: one `platform/aot-train.sh` parameterised by the application name, shared by both Dockerfiles through a named build context** (`docker build --build-context shared=platform`; `docker/build-push-action` takes `build-contexts: shared=platform`), `COPY --from=shared aot-train.sh`. **Rejected: copy the script** (a 120-line script in two places: the second copy drifts, and this one carries the lessons of four CI incidents). **Rejected: move the build context to `platform/`** (`platform/gateway-server`'s build files, the system-test workflow's `docker build ... platform/gateway-server` and the smoke script change for no reason). The shared script moves in PR 1 and the gateway's `aot-smoke.sh` check proves nothing regressed.
- `aot-smoke.sh` is likewise parameterised (image, name, the `cosec-migrate` binary: the gateway's smoke publishes a document through it; the control server's smoke publishes the platform document and asserts readiness flips).

**`docker-deploy.yml`.** One workflow, a **matrix of two images** (`gateway`, `control`): `gradle <project>:installDist`, buildx with the `shared` context, the same `provenance: false, sbom: false`, tags `ahoowang/cosec-<name>` and `ghcr.io/ahoo-wang/cosec-<name>`. The `paths:` filter already has `platform/**` and `host/**`. Rejected: a second workflow file (the resolve-dependencies step, the retries and the Aliyun handling would be copied; every fix to one needs a second PR).
- **The mirror** (`aliyun-sync`): a matrix over both images too. It copies from ghcr, retries, and its failure never reds the run (`continue-on-error`); keep that, and add the control image to the same job with the same limits (the copy is about 800 MB cold; two images in series fit the 40-minute bound only if run in parallel: matrix, not a loop).
- **`system-test.yml`:** `./gradlew :gateway-server:installDist :control-server:installDist ...`, `docker build --tag cosec-control:system platform/control-server` beside the gateway's, a new `COSEC_SYSTEM_CONTROL_IMAGE`, the `image=` input generalised (`control_image`), `paths:` gains `platform/control-server/**`. A published control image can be tested as the gateway's can.

**`k8s/`.** The directory's `base/` is the gateway's. The control server gets **`k8s/control/`** (not a rewrite of `base/`, which the production overlay and the docs link to):
- `k8s/control/base/`: Deployment (`strategy: Recreate`, one replica, the gateway's `securityContext`, `readOnlyRootFilesystem`, the same volumes, probes on port `management`), Service (8080 only), `application.yaml` ConfigMap with the route-map location and Kafka off by default.
- `k8s/overlays/dev` and `production` gain `../../control/base` as a resource, so `kubectl apply -k k8s/overlays/dev` brings both and the dev overlay's "try CoSec on any cluster" includes the control plane. The Redis address ConfigMap `cosec-control-redis` is the overlay's, as `cosec-gateway-redis`; the credentials come from the **Secret `cosec-control-redis-credentials`** (the ACL user of section 4), never the gateway's.
- **NetworkPolicy** (`k8s/control/base/networkpolicy.yaml`, applied by the production overlay), **one rule per port**: **8080** ← the gateway pods (`app.kubernetes.io/name: cosec-gateway`, the namespace selector of the existing example); **8082** ← pods labelled `cosec.ahoo.me/control-client: "true"` in the namespaces the overlay lists; **8081** ← a monitoring namespace the overlay names; egress to Redis, Kafka (when on), DNS and nothing else. **The policy is the control, not defence in depth** (0.2): the manifest's header comment, the page and the startup message say in the same words what a compromise of any admitted pod means. It is the example `k8s/examples/gateway-mode-service-networkpolicy.yaml` made into a manifest the adopter applies. The honesty line from `deployment.md` stays: kind's CNI does not enforce it, so the kind leg proves the policy is valid and selects the right pods, not that it holds; on a cluster whose CNI enforces it, the doc gives a one-line `kubectl run` check. **Topology 2 needs only the existing example** for the IAM's port.
- The kind leg of the system test applies the control manifests, asserts the pod is live and **unready** until `cosec-migrate publish`, then ready; a control call through the gateway route works; the Service does not publish 8081; the policy selects the right pods.

**The system test's Compose file.**
- `control-1`, `control-2` (image `${COSEC_SYSTEM_CONTROL_IMAGE:-cosec-control:system}`, ports `127.0.0.1:8093` and `8094` for the scenarios that talk to a replica's **operators' port** directly, `127.0.0.1:8095`/`8096` for its **service port**; the gateway reaches the operators' port as `lb://control`; probes on the management port with `curl`; `depends_on: redis`). Leg 2 of the system test replaces them: section 8.
- The `shortened-bounds.yaml` profile is mounted as for the other processes (it holds the control plane's shadow bounds).
- A route in `gateway/application.yaml`: `id: control`, `uri: lb://control`, `Path=/cosec/control/**`, `metadata: { cosec-app-id: cosec-control }`: an ordinary `lb://` route, which receives the identity by default. The services of the Compose file that play callers (`auth`, the harness) send their own header to `control-1` directly; the statement `COSEC_PIPELINE_IDENTITY_ONLY_TRUSTED_CALLERS_CAN_REACH=true` is set on `control-*`.
- `auth` keeps port 8090, loses control, and is the login service, which also calls the control server as a service (a platform policy and a principal binding for `svc-login` in the platform document); the scenarios' `Stack` points the control client at the gateway and gets the direct ports for the replica scenarios.

**What happens to `test/system-auth`.** It is cut to a login host. Dropping `implementation(project(":control"))` and `cosec-cocache`'s control store use is enough, with `cosec.control.enabled` absent because the class is absent. Its README row and Compose header comment change. It stays in `testOnlyProjects`.

**`pnpm test:system` (the TypeScript client through the gateway)** reaches the control plane today through the port 8090 of `auth`, I believe, not through the gateway; PR 4 must move those calls to the gateway route (the contract the client documents), and I have not checked how many there are.

---

## 6. Where it has to land (root `AGENTS.md`'s table)

Verified by reading; the architecture tests will say if I missed one.

**Gradle and structure**
- `settings.gradle.kts`: `"platform" to listOf("gateway-server", "control-server")`.
- **Starter and rules** (the new parts of 0.1, 0.7, 0.10): `host/spring-boot` (`PipelineProperties`, the gate choice, the guard, the service listener, the two control switches), `core/control` (the profile, the facade, `ControlCaller`; `ControlBoundaryTest` unchanged), `core/document` (the public header writer), `core/runtime` (`IdentityGate`'s shared function), `test/conformance/.../rules/{STA,CTL,IDN}.json` with their scenarios and mutants, the ABI dumps of every module touched, `wiki/operations/configuration.md` (en, zh) for each new property.
- `build.gradle.kts`: `serverProjects` gains `project(":control-server")` (`verifyUnpublished` is applied to it; publishing it stays off).
- `ProjectListTest` (two lists: the groups, `UNTESTED`): add. `control-server` is **not** in `UNTESTED`: it has tests (section 8), which also puts it in `scripts/ci/plan.py`'s `REDIS` (its context tests need Redis) and **out of** `EXCLUDED`; the gateway is excluded because `docker-deploy.yml` builds and smoke-tests it, and the control server is too, but its refusals are code worth `check`. `UNCOVERED` in `plan.py` stays unchanged (it is a server, not a test-only project: it gets JaCoCo).
- `DependencyRules`: the table has entries per layer; servers have a free hand today because `gateway-server` has no entry in the files I grepped. A `control-server` entry must say: never `:gateway`, `:webmvc`, `:social`; the starter, `:control`, `:cocache`, `:kafka`, `:micrometer`, `:opentelemetry`, `:ip2region`, `:webflux`. The exact shape is for PR 1 to read; I did not.
- `SourceRulesTest`: nothing new if the module's only class is the `main`. `SourceSizeTest`, `TypesPerFileTest` apply by default.
- `PublishedMetadataTest`: it must not see the server (never published): `verifyUnpublished` and the `serverProjects` set.
- Labeler: `'module: control-server'` for `platform/control-server/**`, and the existing `k8s/**` and workflow globs unchanged. Bug report template: `cosec-control-server` in the module list. `codecov.yml`: `plan.py` fails when it and `settings.gradle.kts` disagree; add the project's flag as `plan.py` says.
- `llms.txt`, the "never publish" line of root `AGENTS.md` (the line ends "never publish `cosec-gateway-server`"): **sentence for you**, since I do not edit it: "Never publish `cosec-gateway-server` or `cosec-control-server`; they are applications, not libraries." Also the layer list's "`platform/` (`gateway-server`)" and the `test/system-auth` description ("the login and control-plane host") and the table row for "The gateway image, `k8s/`..." gaining `platform/control-server/**`.

**Docs (en + zh, same headings, explicit anchors)**
- `wiki/operations/deployment.md`: new section "Deploying the control server" next to the gateway's (the image, the route, the credential forwarding, probes, `Recreate`, heap, the NetworkPolicy, the first administrator); the system-test section's list (the host is now the image that ships); the AOT cache section's "has no control plane" sentence (it says "or by a control-plane application on the same Redis").
- `wiki/operations/topologies.md#the-control-plane`: the table's "Runs" row ("No CoSec image runs the control plane" becomes the image); "Sets" (the route, `cosec-app-id`, `only-trusted-callers-can-reach`), "Rests on" (now: the network that admits callers, and write access to Redis; no longer "whoever holds the signing secret" for this host), "CoSec does not check" (that the statement is true) unchanged.
- `wiki/guide/introduction.md#modules`: the image is not a module; one line under the list ("the control plane has an image").
- `wiki/operations/storage.md`: the ACL section (the control line pair, the three accounts), "The first documents" (the first administrator now has a shipped host to talk to; a service's principal and binding), `wiki/integration/web.md#services-behind-the-gateway` (the generalised statement), `wiki/operations/configuration.md` (`cosec.pipeline.identity.only-trusted-callers-can-reach`).
- `wiki/guide/quick-start.md#change-a-document-later`: it tells an adopter to embed the control plane in its own application; add "or run `cosec-control`".
- `k8s/README.md`, `test/system-test/README.md`, `wiki/AGENTS.md` if it names the hosts.
- `skills/`: `cosec-integration` (the control host), `cosec-troubleshoot` (the unconverted store, readiness).
- `wiki/operations/upgrading-previews.md`: the first beta that ships the image: "the control plane has an image; an application of your own that embeds it still works".

---

## 7. Startup refusals

**Inherited, nothing to write** (each has a rule and a test): the Redis eviction policy (`SNP-3`), Redis `INFO` denied, **a Redis user that cannot publish to the invalidation channel** (`Redis denied publishing to the invalidation channel[<s>:inv:snapshot]`: this is the "no write access to the store" refusal, it exists, and applies to both topologies; the system test holds it end to end with an ACL user that cannot write, PR 6), no `RevocationReader`, `delivery=required` without a Kafka listener, replicas reading from Redis replicas, and `STA-1` for a missing module.

**Added, in the starter (so both topologies have them):**

| Refusal | Message (draft) | Why |
|---|---|---|
| The web adapter's gate is an `IdentityGate` (`cosec.pipeline.caller=forwarded-identity`, or `gateway` mode) and the statement is not `true`, exactly | `This host believes the CoSec-Identity header of whoever reaches it, and the header carries no signature: whoever can reach it is whoever they claim to be, a platform administrator included, and can publish any policy of any tenant. The control plane contains mistakes and keeps the audit; it is not a defence against a compromised workload that can reach it, which can also approve its own changes. The network is the boundary. That is acceptable only if nothing but <the gateway \| the gateway and the services you name> can reach it: a NetworkPolicy that admits those pods only, mutual TLS, or a private network. State it once it is true: <key>=true (<ENV>=true).` | `STA-7`, generalised; the key is `only-gateway-can-reach` with no service port, `only-trusted-callers-can-reach` with one. The other key does not satisfy it. |
| The same host with a hidden-method filter | as `STA-6`'s | A service's method must be what it said. |
| `cosec.control.http.service-port` set and the statement is the gateway's | names the right key | A host that admits services must not say only the gateway can reach it. |
| `cosec.control.http.service-port` equal to `server.port` or `management.server.port` | `the service port must be its own` | Three ports, three rules. |
| `cosec.control.http.service-port-api-key=required` without `cosec.api-key.enabled=true` and a store, or with `cosec.jwt.enabled=true` | names the key | The switch composes with the key authenticator only (0.9); two ways to authenticate on one listener is how an adopter ends up unsure what verified a call. |

**The image's own** (a small `StartupTask` in `platform/control-server`, nothing the starter cannot already say): `cosec.control.enabled=false` (an empty application that is "up"), `cosec.jwt.enabled=true` (the image verifies no token: use an IAM or an application of your own to issue them; the optional hardening of tokens is not in the image).

**Not refused, and why:** "not behind the gateway": the host cannot know, and it serves services directly by design; the statement is the only honest form of that check. "A store not converted" (`CTL-42`, `CTL-54`): **readiness `DOWN` in the image** (not a startup refusal: a crash loop hides the reason in logs the kubelet does not read; under `Recreate` the effect is the same, with a message), the existing `ERROR` at startup naming `cosec-migrate repair-references`, the existing health detail `cosecControlStoreFormat`, and the request-level answers. An **embedded** control plane keeps the indicator as an alert only (0.4): login must not go unready on a format the login path never reads.

**Rules needed** (ask for ids; each a positive and a negative scenario and a mutant): (1) STA-6/STA-7 generalised to the gate; (2) the two statement keys and the service-port refusals; (3) the service profile: a route outside it is `404`, and none of the platform document, a boundary, an approval or break-glass is in it; (4) `asserted`, `via` and the peer in the record; (5) `maintenance.enabled=false` runs no pass (and the default runs them); (6) the key switch and its provider; (7) readiness on an unconverted store in the image.

---

## 8. The system test: both topologies

**Topology 1 is the new image; topology 2 is `test/system-auth`.** Your suggestion holds, with one precision: `system-auth` is one application that is the IAM service of **both** legs.

- **Leg 1 (the image).** `control-1`/`control-2` (the shipped image, statement `only-trusted-callers-can-reach`, service port 8082) beside the gateways. `auth` is the IAM *caller*: `COSEC_CONTROL_ENABLED=false`, in `gateway` mode with JWT on as the IAM is today, and a test route (`/admin/onboard`) that calls the host's service port with the public header writer: the one real process that exercises PR 3's writer and the service port as an IAM would. The harness also calls the service port directly.
- **Leg 2 (the embedded IAM).** The same `auth` with the control plane on, in `enforce` with `cosec.pipeline.caller=forwarded-identity`, statement `only-gateway-can-reach`, **no `control-*` services**; the gateway route `/cosec/control/**` goes to `lb://auth`. A test route calls the SDK in process with `ControlCaller.of(securityContext)` and `ControlCaller.service(...)`.
- **One scenario set, two legs.** The control scenarios speak HTTP to the gateway's control route, which is the same URL in both legs; the harness reads `COSEC_TOPOLOGY` and nothing else of them differs, so the same scenarios prove both. The legs differ in what only one has: leg 1 asserts the service port's route set, the three ports' reachability, the statement refusal, the key switch; leg 2 asserts the SDK, `http.enabled=false`, the maintenance switch and the startup line.
- **Two stacks, not one.** Both control planes on one store are the third arrangement (0.6): they would share documents, leases and changes, and every assertion would hold for both or neither. A stack with two key prefixes would need two gateway fleets. So the legs are **two Compose runs**, a matrix of the existing workflow: `compose.yaml` plus `compose.control-host.yaml` or `compose.embedded.yaml` overrides. Leg 2 runs the `control` and `service-path` scenario tags only, not the whole suite.
- **Cost.** About +2 to +3 minutes of runner time per run for leg 2 (the existing run is about five), one more image build (`control`), the kind job unchanged but for the control manifests of PR 7. CI minutes: roughly +50% on `system-test.yml` per run; it is not part of `check`.

**What each PR adds to the system test** is in section 9.

---

## 9. The PRs, and the estimate: the content of 8.0.0-beta.6

The maintainer's schedule: the implementation is the content of **8.0.0-beta.6** (after beta.5, before RC1). Not more than three PRs open at once. ST = what the system test asserts afterwards.

| PR | What | ST after |
|---|---|---|
| **0** `feat(spring-boot)!` the gate switch | `cosec.pipeline.caller=forwarded-identity`; STA-6/STA-7 follow the gate; the two statement keys; an `Authenticator` that never authenticates and a read-only revocation reader if the spike needs them; `configuration.md`, `deployment.md#services-behind-the-gateway`, `upgrading-previews.md` (an application that declared an `IdentityGate` beside `enforce` now needs the statement), en + zh; the starter's ABI is unaffected | unchanged, green |
| **1** `feat(platform)!` `platform/control-server` | The project (a `main`, `application.yaml` of 1, `Dockerfile`), the registries of 6, shared `platform/aot-train.sh`, `docker-deploy.yml` as a matrix, the mirror; the image's own refusals; tests in the module (Redis): it starts with the statement, refuses without it, the control route is `401` with no header and `200` with a platform principal's. **The spike**: the starter in `enforce` with JWT and keys off; the second Netty server on the service port (0.7) | unchanged, green; the image builds |
| **2** `feat(control)!` the record, the service profile, maintenance | `asserted`/`via`/peer on `ChangeEvent` and the stored change; the raw peer from the adapters; `ControlHttp` takes a profile; `cosec.control.http.enabled`, `cosec.control.maintenance.enabled`; the second listener in the starter; ABI dump of `control` | the record of a change published through a service identity, read back from Kafka and the store, carries the three fields; a route outside the service profile on 8082 is `404`; the maintenance switch (leg 2) |
| **3** `feat(document,control)!` the SDK and the writer | `ControlCaller`, the facade in `cosec-control`, the shared identity-to-security function in `IdentityGate`, the header writer (Kotlin in `cosec-document`, TypeScript in `cosec-fetcher`), the API report and ABI dumps, `wiki/integration/` and `checks.md`; the testkit gains nothing | `auth`'s two test routes (header writer; SDK) are exercised by the scenarios of leg 1 and 2 once PR 5 lands; here, unit tests only |
| **4** `test(system)` leg 1 | Compose `control-*`, the gateway route, `auth` loses the control plane in leg 1, the harness, the workflow builds the control image, `wiki/examples/control-service/` (the platform policy and the principal binding of a service, run by `DocumentationExamplesTest`) | every existing control scenario green against the image through the gateway; the service-path scenarios: a service principal bound to a read-only policy reads and is refused a write by the action (`403`), `api-key` cannot approve and is never the trust root, an asserted `jwt` can (the blast radius, named for what it shows), the three ports' reachability, the host exits without the statement, a doubled/expired/too-old header; two replicas: propose on one, approve and publish on the other, stop `control-1` mid-window and it still closes |
| **5** `test(system)` leg 2 | `compose.embedded.yaml`, `auth` embedding the control plane, the SDK route, the scenario tags and the workflow matrix | the control and service-path scenarios green with no control host; the SDK's in-process change is recorded `via.method: in-process`; `http.enabled=false` serves no route; whether onboarding by a service completes without a human (the open question of 0.8) |
| **6** `test(redis)` ACL | `ControlPlaneAclTest`, the ACL section of `storage.md` (en, zh), the system test's Redis with `users.acl` | the suites pass with `cosec-gw` for every non-writer and `cosec-control` for the writer; a `cosec-gw` `SET` of a head is `NOPERM`; a control process started with a read-only user fails with the publish message |
| **7** `feat(k8s)!` manifests | `k8s/control/`, the overlays, **one NetworkPolicy per port**, the kind leg, `k8s/README.md` | the kind job: live, unready until publish, ready after; the Service does not publish 8081 or 8082 to the gateway's namespace; the policies select the right pods |
| **8** `docs(operations)!` | `deployment.md`, `topologies.md` (the two topologies by name, 0.11), `introduction.md#modules`, `quick-start.md`, `upgrading-previews.md` (a `gateway`-mode IAM becomes `enforce` + `caller`), `llms.txt`, the skills, the sentence of 0.2 on the page and in the manifest; en + zh | `pnpm check:docs`, `pnpm build` |
| **Optional, each on your yes** | **9** the listing route `GET /documents?kind=`; **10** the service-port key switch, its provider and the key CLI; **11** a typed Kotlin client for the service port; **12** the `service` method | each adds its scenarios to leg 1 |

**Which PRs must all be in before beta.6 is cut, and which could follow in the RC.** The test I applied: *whatever adds a public property, a public signature, a record field or a rule id lands in the beta, so that RC1 freezes the surface with a beta of feedback behind it* (a beta may still change an experimental or `!` item; after GA a removed or changed line is breaking). Whatever only adds a test, a manifest's polish or a convenience may follow.

| | PRs | Why |
|---|---|---|
| **Must be in beta.6** | **0** the gate switch and the generalised guard; **1** the image (its first tag is beta.6's, so the image name, the ports and the defaults are what RC1 inherits); **2** the record fields, the service profile, the two control switches; **3** the SDK facade, `ControlCaller` and the Kotlin header writer; **4** system-test leg 1; **5** leg 2; **8** the docs with the two topologies by name and the sentence of 0.2 | The whole model (0, 2), both deployables (1, 3), a system test that holds each topology (4, 5: a topology the system test does not drive is not claimed), and the docs that say what the adopter must do (`gateway`-mode IAM migrating to `enforce` + `caller`; the statement). 0, 2 and 3 carry the new properties, the record fields and the experimental API: they must precede RC1. |
| **Must be in beta.6, in reduced form** | **7** the manifests: `k8s/control/` with **one NetworkPolicy per port** and the header sentence (decided narrowing no. 3, and the doc points to it); the **kind leg** may follow | A shipped image without its NetworkPolicy contradicts the statement the host demands. The kind leg proves the manifests apply, not that the policy holds (kind's CNI does not enforce it), so it is not what the model rests on. |
| **Could follow in the RC** | **6** `ControlPlaneAclTest` and the system test's ACL users; the **TypeScript** header writer (the harness and `auth` use the Kotlin one; a TypeScript service waits); the kind leg of 7; **9** the listing route; **10** the service-port key switch with its provider and the key CLI; **11** the typed Kotlin client; **12** the `service` method | None adds a surface the model needs. **One caveat on 6:** the ACL **lines for the control account** must not appear in `storage.md` untested, so either 6 is in beta.6 or the docs say only what `DataPlaneAclTest` holds and the control account is "every key under the prefix", as today. I recommend the second, and 6 in RC1. **Caveat on 9 and 10:** each adds properties and a rule; if the maintainer wants them in RC1 they should be in a beta first, i.e. in beta.6, which is the only beta left. |

**The cut I would make if beta.6 is tight:** keep 0, 1, 2, 3, 4, 5, 8 and the manifests of 7; drop the rest to RC1. That is the "one and a half" cut of the estimate below, and nothing in the model is lost.

**Order:** 0 and 3 in parallel; 1 after 0; 2 after 1; 4 after 1, 2, 3; 5 after 2, 3 and 4; 6 and 7 after 4; 8 last.

**The estimate.** You told the maintainer one to one and a half windows on top of the earlier one. **The design says more: two to three, by my reading, for one session doing everything above without the optional rows.** What takes it there, in rough shares: the starter work and its rules, STA-6/7 generalised and the second listener (0.3); the image, CI, the shared AOT script, the mirror and k8s (0.7); the record, the profile and the SDK with two public header writers (0.4); the system test twice (0.5), the heaviest and the one that finds things; the ACL test (0.2); and the docs in two languages (0.3). It is a rough estimate, not a measurement. **To land inside one and a half:** drop PR 7's kind leg and the TypeScript writer (the harness uses the Kotlin one), run leg 2 as a tag-filtered job only (as above), defer PR 6 to RC1.1, and leave 9 to 12 out. I would not drop PR 0, 2, 4 or 5: they are what makes the model true, and what the system test is for.

---

## 10. What I did not verify

- That the starter starts in `enforce` with JWT and API keys off, no credential authenticator and a web filter over `IdentityGate`: I read `ConditionalOnJwtEnabled`, `cosecAuthenticator`, `CoSecPipelineWebAutoConfiguration`; I did not start it (PR 1's spike).
- That a second Reactor Netty server over the same `HttpHandler` starts under the starter with graceful shutdown and the same filters (PR 1's spike), and that `exchange.request.localAddress.port` is the listener's port.
- That `ControlHttp` and `PipelineControlAuthorizer` never use the host `Authenticator` to decide an action, only the principal and memberships of the `RequestSecurity`: read in `PipelineControlAuthorizer.decide`/`decideAs` and `cosecControlAuthorizer`; not traced to the last call.
- That an identity naming `api-key` is a machine in every place that matters (`AuthenticatedPrincipal.apiKey`, `ApprovalRules.apiKey`, `ChangeAuthority`'s trust root, `BRK-8`): read, not run; PR 4's scenario holds it.
- That the SDK can share `IdentityGate`'s identity-to-`RequestSecurity` function without exposing it: a refactor I read the shape of, not made.
- That granting `cosec:tenant-admin` in a new tenant's document is "contained" for the analysis (0.8): decided by PR 5's scenario.
- That `remote-ip.max-trusted-index: 1` takes a caller-supplied `X-Forwarded-For` entry on a direct call: inferred from the documented rule.
- That the raw TCP peer is cheap to carry from the adapters: not looked at.
- That a read-only line `%R~cosec:token:*` is enough for the control account when JWT is off: PR 6's test decides.
- That `GET /documents?kind=` serves from the store's list without a scan at 50,000 tenants (the list is 2.1 MB there): to be measured in PR 9.
- `DependencyRules`' shape for a server project; the TypeScript client's host tests' use of port 8090.
- Heap and limits: derived from the gateway's measured per-entry costs and `saas-app-change-cost.md`, never from this image.
