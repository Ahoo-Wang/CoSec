# Usability review before RC1: group D, the model

Read-only. Every claim below was checked against code (file named), not against another page.

## 1. Baseline

- Baseline: `8207a42651def246b938bdb434b2cbe594872cfe` (#1655), the commit this worktree started on.
- At the end (`git fetch`): **#1657 had merged** (`origin/main` = `dd4fc214`). **#1654 was still open.** Its diff
  touches none of the passages cited below (checked with `gh pr diff 1654`; its change to `wiki/guide/introduction.md`
  is one link).
- Nothing in the drill-2 list, issue #1656, roadmap §10 or U1–U20/F1/F2 is repeated here. The original U1–U20 report
  file (`usability-review-ga.md`) could not be found on this machine, so I compared against its summary in
  `docs/roadmap.md` (the F1/F2 rows and the decision row of 2026-11-07).

## 2. The five that matter most

1. **UD-1 (S1)** A `MembershipProvider` is asked only `(tenant, principal)`, so provider-backed tokens and every API key
   carry the same roles into every app. The docs say "a role belongs to one app" and that a token holds "the roles of
   that app". Two apps that share a role id therefore share the grant.
2. **UD-2 (S1)** `resource.attributes.*` compiles, but nothing on the request path ever sets it. The policy test kit
   accepts it in scenarios, though. A DENY on it can pass its test and never apply in production.
3. **UD-3 (S1)** The docs, `SecurityContext` and decision events call the anonymous principal `(0)`, but to a document
   it has no id. `{"eq": ["subject.id", "(0)"]}` and a principal binding to `(0)` compile and never match. Wrapped in
   `not`, the same comparison matches every anonymous request.
4. **UD-4 (S2)** The introduction, concepts and security-model pages (en and zh) still say the client names the app.
   `CoSec-App-Id` was removed in #1302. The real weak spot is not described: without a host app, the credential's app
   chooses the app document, and a token without `aud` reads none.
5. **UD-5 (S2)** A statement without `resource` matches every resource of every service. `{"effect":"allow",
   "action":"http:GET"}` in the platform document opens every GET in the deployment. The model's own rule is "none is
   wider by omission" (`Subject` KDoc), and nothing warns.

Count: **S1 3, S2 3, S3 11, S4 4** (21 findings).

## 3. Concept inventory

Columns: the type in `cosec-model` (or where it lives), the field in the documents (`schema/`), what an application
reads, the token claim, the `CoSec-Identity` member, the name in decision output (DecisionEvent, audit record
`AuditEvent`, `explain`, span, metric, problem), a `cosec.*` property, and the term in the English and Chinese docs.
⚠ marks a row where one thing has two names, or one name means two things.

| Concept | Model type (or home) | Document field | App reads | Token | `CoSec-Identity` | Decision output | `cosec.*` | EN docs | ZH docs | ⚠ |
|---|---|---|---|---|---|---|---|---|---|---|
| Tenant | `TenantId` | key `tenant:<id>`; principal subject `tenant`; role subject `tenant`; conditions `subject.tenant` **and** `scope.tenant` (same value) | `SecurityContext.tenantId` | `tenantId` | `tenant` | event `subject.tenant`, `scope.tenant`; audit `tenantId`; explain `tenant`; span `cosec.tenant_id` | `snapshot.max-tenants`, `fail-closed.tenants` | tenant | 租户 | ⚠ `tenantId`/`tenant`; two condition paths for one value |
| Default tenant | `TenantId.DEFAULT` = `(0)` | `tenant:(0)` (file `tenants/%280%29.json`) | `SecurityContext.DEFAULT_TENANT_ID` | `tenantId` absent | `"tenant":"(0)"` | `(0)` | – | default tenant | 默认租户 | ⚠ same spelling as the anonymous principal (UD-3) |
| Platform tenant | `TenantId.PLATFORM` = `(platform)` | `{"principal":…,"tenant":"(platform)"}`, `tenant:(platform)` | – | `tenantId` | `tenant` | – | – | platform tenant | 平台租户 | ⚠ "platform" also names the platform document and the "platform layer" (UD-19) |
| App | `AppId`; `Scope.app`; `HostScope.app`; none = `AppId("")` (`ScopeResolver.NO_APP`) | key `app:<id>`; binding `app` (`*`); tenant role `app`; condition `scope.app` (**absent** when none) | `SecurityScope.appId` (`""` when none) | `aud` | `scope.app` (`""` when none) | event `scope.app`; audit **`request.appId`**; explain `app`; span `cosec.app_id` | `cosec.authorization.scope.app-id`, `cosec.control.app-id`, route metadata `cosec-app-id` | app | 应用 | ⚠ none is absent in conditions and `""` everywhere else; the audit files it under the request; docs still say the client names it (UD-4) |
| Space | `SpaceId` (`DEFAULT` = `""`); `Scope.space` | binding `space` (`""`, `*`); role `space` (absent: any); `groups[].spaced`; condition `scope.space` (`""` is a value) | `SecurityScope.spaceId` | `roles` entries `role@space` | `scope.space` | event `scope.space`; audit `request.spaceId`; explain `space`; span `cosec.space_id`; control-plane resource `scope/<t>/<a>/(default)` | – | space, default space | 空间, 默认空间 | ⚠ default space `""` vs `(default)` (UD-9); concepts page calls it "a partition inside an app" (UD-6) |
| Principal | `PrincipalId`; engine `Subject`; `SubjectRef.Principal` | `{"principal":…}`; condition `subject.id` | `CoSecPrincipal.id` | `sub` | `sub` | event `subject` (`SubjectSummary`); audit `principal.id`; explain `principal`; span **`user.id`** | – | principal; model.md "Member" | 主体 | ⚠ principal / subject / sub / user / member (UD-10) |
| Anonymous principal | `CoSecPrincipal.ANONYMOUS_ID` = `(0)` (api); `Subject.authenticated = false` | reached only by `"subject":"*"`; `subject.id` **absent**; `subject.authenticated` false | `principal.id == "(0)"` | – | no header | event `subject.id` `(0)` | – | anonymous principal `(0)` | 匿名主体 `(0)` | ⚠ `(0)` everywhere but in a document (UD-3) |
| Subject (who a binding grants to) | `SubjectRef` (`Anyone`, `Principal`, `Role`) | `subject` | – | – | – | – | – | subject | 对象 (also "JSON object"), quoted as 主体 | ⚠ "subject" is also the requester (UD-10) |
| Credential | spi `Identity.Credential`, `Identity.Method` (`jwt`, `api-key`) | – | `ForwardedIdentity.credentialId` | the token itself (`jti`) | `auth.method` `jwt`/`api-key`, `auth.jti` | event `subject.method` **`token`/`apikey`**; problems `token-invalid`, `token-expired`, `token-revoked` | `cosec.pipeline.api-key-header` | credential | 凭证 | ⚠ two vocabularies for the method (UD-11) |
| Membership | `Memberships` (roles per space, attachments, malformed, source, active) | never in a document | only the scope's roles | `roles`, `policies`, `cosec.membership: "provider"` | `roles` (the scope's) | trace | `cosec.authorization.membership.timeout` | membership | 成员关系 | |
| Role | `RoleId`; `SubjectRef.Role(tenant?)`; `Scope.roles` | `roles[]` (app: system, tenant: custom, `app` required); `{"role":…}`; `scope.roles`, `subject.roles`, `subject.scopedRoles` | `SecurityScope.roles` | `roles` (`role`, `role@space`, all spaces) | `roles` (bare, the scope's only) | audit **`principal.roles`** (the scope's only); explain input `roles` (claim form) | – | role, system role, custom role | 角色, 系统角色, 自定义角色 | ⚠ `roles` is all roles in a token, one space's roles elsewhere (UD-8); app-blind in memberships (UD-1); no glossary entry (UD-6) |
| Permission (short form) | document `Catalog` entry → policy `perm:<app>/<id>` | `catalog.groups[].permissions[]` (`id`, `action`, `resource`); role `permissions` | – | – | – | binding id `role:<role>@<app>:<perm>`; policy `perm:…` | – | permission, short-form permission | 权限, 简写权限 | ⚠ "permission" also means the control plane's `cosec:*` actions; no glossary entry (UD-6) |
| Policy | `PolicyId`; document policy | `policies[]` (`id`, `attachable`, `boundary`, `condition`, `statements`) | – | `policies` (= attachments) | – | event `by.policy`; audit `source.policy`; explain `by.policy`; span `cosec.authorize.policy.id` | – | policy | 策略 | |
| Statement | `StatementRef.Policy(index, name)` | `statements[]` (`name`, `effect`, `action`, `resource`, `condition`, `onMissing`) | – | – | – | audit `source.statement`; explain `statement`, `name`; span `…statement.index/name` | – | statement | 语句 | |
| Effect | `Effect.ALLOW/DENY` | `effect: "allow"/"deny"` | – | – | – | explain trace `effect`; **audit `ALLOW` = the decision** | – | ALLOW/DENY | 效果 | ⚠ ALLOW names the effect and, in audit and metrics, the decision (UD-7) |
| Action | `Action(namespace, verb)` | `action` (`service:verb`, `service:*`, `*`, `http:<METHOD>`); route map `service`, `action`; condition `action.service` | – | – | – | event `action`; explain `action` | – | action, `service:verb`, the `http` namespace | 动作 | ⚠ the model's "namespace" is the docs' "service" (UD-21) |
| Resource | `Resource(type, id, attributes)` | `resource` (typed pattern or `http:path:`; absent: **any**) | – | – | – | event `resource`; explain `resource` (`type/id` or `/path`) | – | resource type and id | 资源 | ⚠ omitted = everything (UD-5); `attributes` never set (UD-2) |
| Condition | `Condition`, `CheckedCondition` | `condition` on statement, policy, rate limit rule | – | – | – | explain `CONDITION_MISS`, policy condition = **`GUARD_MISS`/`GUARD_ERROR`**; `Cause.MatcherFailed.guard` | platform `limits` | condition, policy condition | 条件 | ⚠ policy condition is a "guard" in output only (UD-21) |
| Subject attributes | `Attribute.Kind.SUBJECT_*` | `subject.attributes.<n>` | `CoSecPrincipal.attributes` | `attributes` | **`claims`** (those named) | – | `cosec.gateway.identity.claims` | principal attributes | 主体属性 | ⚠ `attributes` in the token, `claims` in the header |
| Request attributes | `REQUEST_ATTRIBUTE`, `HTTP_*`, `ENVIRONMENT_*` | `request.attributes.<n>`, `http.*`, `environment.*` | `deviceId`, `requestId` | – | `rid` | event `request` | – | request attributes | 请求属性 | |
| Resource attributes | `RESOURCE_ATTRIBUTE`, `RESOURCE_VAR` | `resource.attributes.<n>` (**never set**), `resource.var.<n>` | – | – | – | – | – | resource attributes, path variables | 资源属性, 路径变量 | ⚠ UD-2 |
| External attributes | `Kind.EXTERNAL`, `AttributeCatalog` | `ext.<provider>.<name>`; platform section **`attributes`**; `onMissing` | – | – | – | event `attributes` (statuses); `Cause.AttributeUnavailable` | `cosec.authorization.attributes.timeout` | external attributes | 外部属性 | ⚠ "attributes" names five things (UD-12) |
| Binding | `Binding`, `BindingId`, `BindingScope`, `Match` (all internal) | `bindings[]` (`id`, `subject`, `app`, `space`, `policy`, `expiresAt`, `breakGlass`) | – | – | – | event `via` (`BindingRef.Bound`); audit `binding`, `source.via=BINDING`; explain **`via`**; span `cosec.binding.id`; metric `source=binding` | – | binding | 绑定 | ⚠ `via` / `binding` / `binding.id` (UD-21) |
| Binding kinds | `SubjectRef.Anyone/Principal/Role`; `BindingRef.Attached`; `Binding.breakGlass` | `"*"`, `{"principal"}`, `{"role"}`, `breakGlass: true`; attachments are not in documents | – | **`policies`** (attachments) | `auth.capped` | audit `ATTACHMENT`, `attached:<policy>`; event `breakGlass` | `cosec.control.break-glass.max-duration` | Anyone, principal, role binding; attachment / attached policy; break-glass | 绑定到任何人, 主体绑定, 角色绑定, 附加策略, Break-glass | ⚠ attachments are `policies` in the token and in `explain` (UD-21) |
| Scope | `Scope(tenant, app, space, roles)`, `ScopeResolver`, `Rejected.Reason` | conditions `scope.*`; a binding's app × space | `SecurityContext.scope` (`SecurityScope`: no tenant) | – | `scope{app, space}`, `roles` beside it | event `scope`; explain **`Rejected`** + `APP_MISMATCH`/`SPACE_NOT_MEMBER`; problem `scope-rejected`; audit `SCOPE_REJECTED` | `cosec.authorization.scope.app-id` | scope; "role grant scope" | 作用域 | ⚠ also a binding's scope and the control plane's `scope/…` resources (UD-20) |
| Document kinds | `DocumentKind`; spi `SnapshotKey` | keys `platform`, `app:<id>`, `tenant:<id>`; **no `kind` field** | – | – | – | audit `source.document`; explain `document` | `snapshot.*` | platform, app, tenant document | 平台/应用/租户文档 | ⚠ model.md draws a `kind` field (UD-14) |
| Platform boundary | `StatementRef.Boundary`, `BindingRef.Boundary`, `Cause.BoundaryUnavailable` | policy `boundary: true`; platform `boundaries.default`; tenant `boundary` (a policy id) | – | – | – | event `BOUNDARY`; audit `EXPLICIT_DENY`+`BOUNDARY`; metric `explicit_deny`; problem `forbidden`/`unauthorized` | – | platform boundary | 平台边界 | ⚠ `boundary` is a boolean and a policy id; counted as an explicit deny (UD-7) |
| Decision | `Decision` | – | `AuthorizationChecker` booleans | – | – | event `outcome.reason`; audit `decision`/`reasonCode`; explain `decision`; scenarios `decision` | `cosec.pipeline.trace-level` | decision | 决策 | |
| Permit | `Decision.Permit` | – | – | – | – | event `PERMIT`; audit **`ALLOW`**; metric **`allow`**; span `PERMIT`; explain `Permit` | – | Permit | Permit | ⚠ UD-7 |
| Explicit deny | `Decision.Deny(by ≠ null)` | – | – | – | – | event `EXPLICIT_DENY`; audit `EXPLICIT_DENY` **also for scope rejection, boundary and 400** | – | explicit DENY | 显式拒绝 | ⚠ UD-7 |
| Implicit deny | `Decision.Deny(null)` | – | – | – | – | `IMPLICIT_DENY`, `implicit_deny`; explain without `by` | – | implicit deny | 隐式拒绝 | |
| Indeterminate | `Decision.Indeterminate(Cause)` | – | – | – | – | event `INDETERMINATE` + cause; audit `INDETERMINATE`; problem `unavailable`; metric `indeterminate` + cause | – | Indeterminate | Indeterminate | |
| Obligation | `Obligation.RateLimit`, `RateLimitSpec` | `rateLimits[]` | – | – | – | `TOO_MANY_REQUESTS`; problem `too-many-requests` | `cosec.authorization.rate-limit.timeout` | obligation, rate limit rule | 义务, 限流 | |
| Snapshot | runtime `SnapshotSource`, engine `SnapshotView` | – | – | – | – | `snapshotVersion` | `cosec.authorization.snapshot.*` | snapshot | 快照 | |
| Revision | `Long`; engine `ViewVersion`; `Cause.DocumentInvalid.revision` | `revision`, `provenance.parent.revision` | – | – | – | event and audit **`snapshotVersion`**; explain **`version`**, input **`at`**; span **`cosec.snapshot.version`** | `cosec.control.revisions.*` | revision, head | 修订 | ⚠ four names (UD-21) |
| Reserved ids | `Identifiers.RESERVED_PREFIX` `(`; `Match.WILDCARD` `*`; policy prefixes `cosec:`, `perm:`, `g:`, `r:`; binding prefix `role:` | spread across five tables | – | `sub` starting `(` refused (`IDN-12`) | – | – | – | no single place | 同 | ⚠ UD-17 |
| Wildcards | `Match.Any` | binding `*`; action `*`, `service:*`; typed `*`, `**`; HTTP `*`, `**`, `{*name}`; role `"*"`; `role@*` malformed | – | – | – | – | – | per table | 同 | each means "any" where it stands: fine |

## 4. Findings

### UD-1 · S1 · A provider's roles are app-blind; the docs say a role belongs to one app

- **Who meets it, when.** A product with several apps (a WMS and a POS) whose principals' memberships come from a
  `MembershipProvider`: tokens marked `cosec.membership: "provider"` (large or fast-changing memberships, the
  documented path), and **every API key** (`IDN-6`: an API key's roles always come from the provider).
- **Evidence.** `MembershipProvider.memberships(tenant, principal)` (`core/spi/.../membership/MembershipProvider.kt`) has
  no app. `ResolveMembers.memberships` (`core/runtime/.../stage/ResolveMembers.kt`) calls it with the tenant and id only.
  `Memberships.roles` is keyed by space alone. An app document is read whenever the credential's app has one
  (`ViewDocuments.appOf`), and its system roles bind `{"role": "<id>"}` in every tenant. So a principal the provider
  gives `admin`, meant for app A, is `admin` of app B as soon as it holds a B token or an uncapped B key, whenever B's
  document also has a role `admin`. The docs say the opposite:
  - `wiki/guide/model.md`: "A role belongs to one app"; "a token is issued for one app, so a space id never carries a
    grant from one app to another";
  - `wiki/integration/authentication.md#apps`: "a login that serves several apps issues one token per app, with the roles
    of that app";
  - RFC 0012 §3.5: "token 里的角色都属于这个 App，同名系统角色不再跨 App 生效".

  For claims this holds if the issuer filters (`user.rolesIn("order")` in the sample). For the provider it cannot hold:
  the provider is never told the app. `wiki/guide/security-model.md` step 5 adds to the wrong picture: "the app's
  document when the principal has roles in the scope's app". The code tests roles in the scope's **space**
  (`scope.roles.isNotEmpty()`).
- **What exists.** `wiki/scenarios/roles.md` says "prefer opaque ids for custom roles", but the reason it gives is
  renaming. `RoleChecks` warns when a tenant role reuses a system role id of its own app, not across apps.
- **Change.** Before RC1, docs: say once, in `model.md` and `authentication.md#apps`, that role ids are one namespace
  across apps in memberships. A provider answers the same roles for every app, so apps that share principals and a
  provider give their roles distinct ids (`wms.admin`; the WMS sample already does). Fix security-model step 5
  ("roles in the scope's space"). Put the same sentence in the `MembershipProvider` KDoc. 8.1: the provider overload
  already planned (roadmap §10, "`MembershipProvider` 带声明的默认方法重载") should carry the credential's app, so a
  provider can answer per app.
- **Touches.** Docs and KDoc now, not breaking. The 8.1 SPI overload is additive but changes an SPI: **needs the
  maintainer**.
- **Size.** S (docs), M (8.1 SPI). **When.** Docs before RC1; SPI 8.1.

### UD-2 · S1 · `resource.attributes.*` compiles and is never set, yet the policy test kit fills it

- **Who, when.** A policy author who knows ABAC writes `{"eq": ["resource.attributes.classification", "secret"]}` in a
  DENY, adds a scenario with `"resource": {"attributes": {"classification": "secret"}}`, and sees it denied.
- **Evidence.** `Attribute.Kind.RESOURCE_ATTRIBUTE` is in the catalog, so the path compiles. Every engine `Resource`
  built in production has `emptyMap()` attributes: `ReadRequest` (unmapped requests), `Enforceable` (keeps what it gets),
  `AuthorizationChecker`, `IdentityGate`, `ControlRequestJson` (`explain`, `simulate`). Route maps carry none. But
  `PolicyTestKit` (`enforceable = true`) reads `request.resource.attributes` in `EngineScenarios.kt`, and
  `RequestFacts.kt` resolves `RESOURCE_ATTRIBUTE` from it. `wiki/policies/testing.md` documents the field. So the test
  passes and production never denies; an ALLOW written `{"not": {"eq": ["resource.attributes.x", …]}}` matches every
  request. `wiki/policies/language.md` does say "Neither the request pipeline nor the documented checks set any", but
  the path compiles silently, and the test kit contradicts the sentence.
- **Precedent.** `DOC-8` refuses `http:HEAD` because "a DENY on it would deny nothing". The same reason applies here.
- **Change.** Before RC1: the test kit refuses `resource.attributes` in enforceable scenarios, naming the reason.
  And either the compiler refuses `resource.attributes.*` in documents (as `DOC-8` does), or, if the maintainer keeps
  it for a future producer, the control plane flags every statement that reads it.
- **Touches.** The test kit's scenario format (A/C). A compile rule, which **needs the maintainer**: it is breaking only
  for documents whose statements never matched.
- **Size.** S. **When.** Before RC1.

### UD-3 · S1 · The anonymous principal is `(0)` to everything but a document

- **Who, when.** An author who wants an ALLOW to `"*"` for signed-in users only, or a DENY for anonymous callers, and
  takes the anonymous id from the concepts page.
- **Evidence.** `wiki/guide/concepts.md`: "the **anonymous** principal, id `(0)`". `CoSecPrincipal.ANONYMOUS_ID =
  "(0)"`, and the decision event's `subject.id` is `(0)` (`RequestState`). But `EvaluatorAttributes`:
  `SUBJECT_ID -> if (authenticated) id else null`, so `subject.id` is absent. Under the language's own rules, `eq` on an
  absent value is false and `not` is plain negation. Three forms compile without a warning:
  - DENY `{"eq": ["subject.id", "(0)"]}` never matches, so anonymous callers are not denied;
  - ALLOW `{"not": {"eq": ["subject.id", "(0)"]}}`, meant as "signed-in only", matches **every** anonymous request;
  - `{"principal": "(0)", "tenant": "(0)"}` as a binding subject never applies (`CandidateOrder`: no principal bindings
    without `authenticated`), so a DENY bound to it is lost.

  No compile check looks at reserved ids except space ids (`DocumentChecks`, `RoleExpansion`). The control plane has
  two warnings in all (`SectionChecks`, `RoleChecks`), neither about this.
- **Change.** Before RC1, docs: the concepts page and the language's attribute table say that in a document the
  anonymous principal has no id, and that `subject.authenticated` is how to test for it. Also before RC1: refuse a
  principal binding or a `subject.id` literal starting with `(` (reserved ids never reach a document as a principal,
  `IDN-12`), like the reserved-space check that exists.
- **Touches.** Docs; one compile rule (**needs the maintainer**; it is breaking only for documents that never matched).
- **Size.** S. **When.** Before RC1.

### UD-4 · S2 · The docs still describe a client-declared app, and not the weak spot that replaced it

- **Who, when.** An architect reading the guide before deploying several apps.
- **Evidence.** `CoSec-App-Id` was removed in R7b (#1302). `CoSecParameters` reads `CoSec-Space-Id` and
  `CoSec-Device-Id` only, and the `ScopeResolver` KDoc says "The client never declares an app". Still on `main`, and
  not changed by #1654:
  - `wiki/guide/introduction.md`: "It does not verify the app a client names unless you bind it … a service that is not
    bound accepts the app the request names";
  - `wiki/guide/concepts.md`: the Request section ("the app and space the client names in `CoSec-*` parameters"), App
    ("the app a request names must match it"), Scope, and the diagram's `REQ -->|names| App and space`;
  - `wiki/guide/security-model.md`, "The app is the weak spot": "Without a host app, the client picks which app
    document's role bindings apply … A client that declares another app, or none, steps around them". Also "What CoSec
    does not protect against": "verify that a client-named app is the right one";
  - the zh twins (`wiki/zh/guide/introduction.md`, `concepts.md`, `security-model.md` "应用是薄弱环节").

  The same page's App bullet, a few lines up, says the reverse. What is true now is not written as a risk anywhere.
  Without a host app, **the credential's** app picks the app document, so a token issued for app A, used on a service
  of app B that sets no `app-id`, is decided in A. A token without `aud` reads no app document, so the role-bound DENYs
  of every app document miss it. `wiki/integration/authentication.md#apps` says "a token issued for one app is worth
  nothing to a service of another, even with … a role of the same name"; that holds only when the service has an app.
- **Change.** Rewrite those passages, en and zh, around the credential's app. Keep the hardening line (set
  `cosec.authorization.scope.app-id` on every one-app service). Qualify the authentication sentence with "on a service
  with an app".
- **Touches.** Docs only. **Size.** S. **When.** Before RC1.

### UD-5 · S2 · An omitted `resource` means every resource

- **Who, when.** Anyone writing an ALLOW by hand. The likeliest case is `http:<METHOD>` in simple mode, with one
  platform document for several services.
- **Evidence.** `PolicyFormats`: statement `resource`, "Absent: any resource". `StatementIndex` has an `anyResource`
  leaf. The catalog's `resource` (`CatalogFormats`) works the same way. So `{"effect":"allow","action":"http:GET"}` bound
  to `"*"` in the platform document opens every GET of every service, in every tenant, anonymous callers included.
  There is no explicit spelling for "any resource", so omission is the only way to say it, and a slip reads exactly
  like intent. The model states the opposite principle for its own types: "No default: whoever makes a subject says,
  so none is wider by omission" (`Subject` KDoc). Bindings follow that principle (`app` and `space` are required, with
  no default). `security-model.md#default-deny` says "a typo in a pattern closes an endpoint", which is true of a typo
  and false of an omission. The grant analysis only runs in the control plane, for grants.
- **Change.** Before RC1, docs: a line in `#default-deny` and in the language's statement table, "An ALLOW without
  `resource` grants its actions on every resource of every service". 8.0.x (C): a control-plane finding for an ALLOW
  whose actions include `*`, `http:*` or `http:<METHOD>` and that has no `resource`. 8.1 or 9.0: an explicit
  `"resource": "**"`, then `resource` required.
- **Touches.** Docs; a control-plane warning (C); the format (**needs the maintainer**, breaking: not 8.0).
- **Size.** S / S / M. **When.** Docs before RC1, warning 8.0.x, format 9.0.

### UD-6 · S2 · The glossary misses the central concepts and contradicts the model page

- **Who, when.** Every first reader: `concepts.md` is "the vocabulary CoSec uses".
- **Evidence.** `wiki/guide/concepts.md` defines principal, tenant, request, app, space, scope, action/resource,
  document, policy, statement, condition, binding, attachment, membership, catalog, decisions, obligation, snapshot,
  revision, control plane, change, break-glass. Missing: **role** (the concept "two roles in one tenant" rests on),
  **permission** / short-form permission, **platform boundary** (used in its Decisions table, undefined), **credential**,
  **subject**, **effect**, and the attribute families. Roles, permissions and boundaries are only in `model.md`'s
  entity table. Where the two pages overlap they disagree: concepts calls a space "a partition inside an app", while
  model.md and RFC 0012 §3.2 say "One space shared by several apps" is supported. Concepts also says the client names
  the app (UD-4).
- **Change.** One glossary: add role, permission, boundary, credential, subject (with UD-10's distinction), effect, and
  the attribute families, each in two or three lines linking to its home. Make model.md link there instead of keeping
  a second entity table of definitions. Fix the space definition.
- **Touches.** Docs en/zh. **Size.** M. **When.** Before RC1.

### UD-7 · S3 · "Allow" and "explicit deny" mean different things in different outputs

- **Who, when.** An operator building audit dashboards or alerts.
- **Evidence.**
  - Engine: `Permit` / `Deny` / `Indeterminate`.
  - `DecisionEvent` (`OutcomeSummary.Reason`): `PERMIT`, `EXPLICIT_DENY`, `SCOPE_REJECTED`, `BOUNDARY`, `INVALID_REQUEST`.
  - Audit (`AuditEvents.decision`): `ALLOW`. `EXPLICIT_DENY` is used for a matched DENY **and** for a scope rejection,
    a boundary refusal and a `400`, where the reason code is also `EXPLICIT_DENY`.
  - Metrics (`MetricsListener.result`): `allow`; `explicit_deny` for DENY, scope rejection and boundary; `bad_request`
    for the 400 that audit calls `EXPLICIT_DENY`.
  - Span: `PERMIT`. `explain` and scenarios: `Rejected` for a scope rejection, which concepts.md calls "a Deny".

  `security-model.md` defines an explicit deny as "the DENY that matched". So an alert on audit `EXPLICIT_DENY` fires
  on malformed requests, and a reviewer believes a DENY statement exists where none does. "ALLOW" is also the statement
  effect. `observability.md` tabulates the mapping honestly; the vocabulary is still several.
- **Change.** Before RC1, KDoc on `AuditDecision` and `AuditReasonCode`: "EXPLICIT_DENY: refused by a DENY statement,
  a rejected scope, the platform boundary, or a 400; `reasonCode` and `kind` tell which", and the same line in
  concepts.md. Before GA if the maintainer agrees: give the audit's 400 the reason `INVALID_REQUEST` (metrics already
  separate it). Renaming `ALLOW` to `PERMIT` waits for 9.0.
- **Touches.** KDoc, docs; a public enum value of `cosec-api` (**needs the maintainer**; breaking for audit consumers
  after GA). **Size.** S. **When.** Docs before RC1; values before RC1 or 9.0.

### UD-8 · S3 · `roles` is every role in one place and one space's roles in another

- **Who, when.** A service behind a gateway, or an auditor.
- **Evidence.** The token's `roles` holds every role as `role` / `role@space` (`MembershipClaims`). The
  `CoSec-Identity` header's `roles` is the bare roles of the scope's space only (`IdentityCodec`), and so is
  `SecurityScope.roles`. The audit record's `principal.roles` is `scope.roles` (`AuditEvents`, line 53), filed under
  *principal*, which reads as "the principal's roles". The `explain` input `roles` is the claim form. Conditions have
  three (`subject.roles`, `subject.scopedRoles`, `scope.roles`); security-model.md explains those three well.
- **Change.** KDoc on `AuditPrincipal.roles` ("the roles in the request's space"); one line in `web.md`'s identity
  table ("the scope's roles, not the token's"). Renaming the audit field waits for 9.0.
- **Touches.** KDoc, docs. **Size.** S. **When.** Before RC1.

### UD-9 · S3 · The default space has two spellings; an empty value means "default" or "none"

- **Who, when.** Someone who learns the control-plane resources first (`testing.md`), or writes claims by hand.
- **Evidence.** Documents, claims, `scope.space` and `SpaceId.DEFAULT`: `""`. Control-plane resources: `(default)`
  (`ControlResources.DEFAULT_SPACE`). The `Identifiers.RESERVED_PREFIX` KDoc lists "the space `(default)`" among
  CoSec's ids, as if it were the default space's id. A claim `admin@(default)` is accepted by `MembershipClaims.parse`
  (not malformed) into a space no request can select (`SCP-9`), so the role applies nowhere (less access) and the
  trace's `malformed` list does not show it. Empty also means two things:
  - `scope.space` `""` is a value you can compare;
  - for the app, "none" is `AppId("")`: absent in conditions (`EvaluatorAttributes` maps `""` to null, so
    `{"eq":["scope.app",""]}` compiles and is always false), but `""` in `SecurityScope.appId`, `CoSec-Identity`
    `scope.app` and the audit's `request.appId`;
  - rate-limit keys put an absent value in the bucket `""` (`EvaluatorObligations`: `value.orEmpty()`), while
    conditions hold that absent equals nothing, not even `""`.

  "Default" is spelled `(0)` for the tenant, `""`/`(default)` for the space and `default` for the boundary.
- **Change.** Before RC1: fix the `Identifiers` KDoc ("`(default)` stands for the default space in control-plane
  resources only"). Count claims whose space is reserved as `malformed`. Add one table of special values (see UD-17)
  saying where each spelling appears.
- **Touches.** KDoc, docs; the `malformed` change is trace-only (applies nowhere either way). **Size.** S.
  **When.** Before RC1.

### UD-10 · S3 · "Subject" is both who a binding grants to and who is asking

- **Who, when.** Policy authors, and every zh reader.
- **Evidence.** A binding's `subject` is the grantee: `"*"`, a principal or a **role** (`SubjectRef`; model.md's class
  `Subject {anyone, principal, role}`). Everywhere else "subject" is the requesting principal: `subject.*` attributes,
  the `sub` claim, the engine's `Subject`, `DecisionEvent.subject` (`SubjectSummary`), `TokenSubject`, `ScopeSubject`.
  Spans call it `user.id`, while model.md says "User: not in CoSec" and calls the principal a "Member". Concepts
  defines no "subject". The zh concepts page quotes the binding definition as "这个主体受这条策略约束" (主体 =
  principal, though a binding may name a role or anyone). It renders the binding subject as 对象, which in the same
  pages also means "JSON object" (`language.md`: "`"*"` 或对象") and the business objects of 对象级规则.
- **Change.** The glossary entry (UD-6): "subject, in a binding, is who it grants to; `subject.*` in a condition is the
  principal making the request". Pick one zh term for the binding subject (e.g. 授予对象) and use 主体 only for the
  principal.
- **Touches.** Docs en/zh. **Size.** S. **When.** Before RC1.

### UD-11 · S3 · The credential method has two vocabularies

- **Who, when.** Operators reading decision events beside the security model; authors of authenticators.
- **Evidence.** `Identity.Method`: `jwt`, `api-key`. These are what `CoSec-Identity` `auth.method` carries, what
  `BRK-8` reserves, and what the docs name ("any credential named `api-key` … counts as an API key",
  `security-model.md`). The decision event's `subject.method` comes from `TokenAuthenticator.METHOD = "token"` (for
  CoSec's own token too) and `ApiKeyAuthenticator.METHOD = "apikey"`. The `SubjectSummary.method` KDoc lists
  "`token`, `api-key`", which mixes both. So one API key is `apikey` in the event and `api-key` in the header.
- **Change.** Before RC1: make `ApiKeyAuthenticator.METHOD` `api-key`, and either name CoSec's own token `jwt` in the
  event or document "the event names the authenticator, the header the credential". Fix the KDoc.
- **Touches.** A public constant's value and an event field value (A; **maintainer's call**; breaking after GA only).
  **Size.** S. **When.** Before RC1.

### UD-12 · S3 · "attributes" names five things

- **Evidence.**
  - the token's `attributes` claim, which conditions read as `subject.attributes.*` and the identity header forwards as
    `claims`;
  - the platform document's `attributes` section, which declares **external** attributes only;
  - `request.attributes.*`, set by the host;
  - `resource.attributes.*`, set by nothing (UD-2);
  - `DecisionEvent.attributes`, the statuses of external attributes;
  - the test kit's request `attributes`, meaning `request.attributes.*`.

  A reader of a platform document can take `attributes` for subject attributes.
- **Change.** Docs: call the platform section "external attribute declarations" wherever it is named. 8.1: consider an
  alias `externalAttributes` (format: **needs the maintainer**).
- **Size.** S. **When.** Docs 8.0.x; format 8.1.

### UD-13 · S3 · An example sets `spaced: true` where it has no effect

- **Evidence.** The first app document in `wiki/policies/role-permissions.md` puts `"spaced": true` on a group whose
  permissions are hand-written `perm:` policies. `spaced` applies only to short-form permissions (`language.md`
  catalog table; the page's own short-form section). A reader takes `spaced` to control these policies. With
  `spaced: false` they would still apply in every space a role is held in, through the `space: "*"` bindings, which
  is more than the reader expects.
- **Change.** Drop `spaced` from that example, or turn it into short form.
- **Touches.** Docs en/zh. **Size.** S. **When.** Before RC1.

### UD-14 · S3 · The model's diagrams draw fields and relations the code does not have

- **Evidence.** `wiki/guide/model.md`'s class diagram:
  - `Permission { … spaced }`: `spaced` belongs to the catalog **group** (`groups[].spaced`), and there is no group box;
  - `PolicyDocument { kind }`: no document has a `kind` field (the key says the kind, and an unknown field fails the
    document);
  - no Boundary box, although the entity table above it lists Boundary; `Policy` lacks `boundary`;
  - no Obligation.

  The concepts flowchart has the request naming the app (UD-4). The security-model flowchart and the overview's
  sequence diagram match the code.
- **Change.** Fix the four boxes and the arrow. **Touches.** Docs en/zh. **Size.** S. **When.** Before RC1.

### UD-15 · S3 · Several tenants have no path through the docs

- **Who, when.** The second step after simple mode, and CoSec's main use.
- **Evidence.** Scenario pages exist for simple mode, an app, spaces, roles, a tenant owner, a service account, a
  gateway, route maps and an external source; none sets up two tenants. What changes is spread over several pages:
  - the `tenantId` claim (`authentication.md`);
  - `(0)` holds both tenantless tokens and anonymous callers, so `"*"` in `tenant:(0)` reaches both (`simple-mode.md`);
  - platform `Anyone` bindings and app role bindings without `tenant` reach every tenant (`security-model.md#tenant-isolation`);
  - boundaries (`language.md`).

  `#tenant-isolation` is good but is a list of hazards, not a setup.
- **Change.** A scenario "Several tenants": two tenant documents, one shared app, a boundary, and runnable scenarios
  that show tenant A's role binding not reaching tenant B.
- **Touches.** Docs, examples. **Size.** M. **When.** 8.0.x.

### UD-16 · S3 · Two roles in one tenant can be written two ways with different reach

- **Evidence.** `simple-mode.md` grants roles by condition (`{"in": ["admin", "scope.roles"]}` in the platform
  document), then, under "Many roles", by role bindings in `tenant:(0)`. The first applies to `admin` of **every**
  tenant, because the platform document is shared. The second applies to tenant `(0)` only. The page explains why the
  platform document cannot hold a role binding, but not that the condition form reaches every tenant. Someone who later
  adds tenants keeps a platform-wide admin by accident.
- **Change.** One sentence under the example: "a role condition in the platform document applies to that role in every
  tenant; once you have tenants, move it to the tenant's document".
- **Touches.** Docs en/zh. **Size.** S. **When.** Before RC1.

### UD-17 · S3 · The id grammar is written in five places, and the special values in none

- **Evidence.**
  - tenant and app ids: `language.md#documents`;
  - space ids: the binding table (not `*`, not starting with `(`), plus `MembershipClaims` (no `@`, not empty, so the
    default space is a bare role) and `ControlResources` (may hold `/`);
  - role ids: the role table (not `*`, no `@`);
  - principal ids: `IDN-12` (not starting with `(`);
  - policy ids: the policy list (`cosec:`, `perm:`, `g:`, `r:`);
  - binding ids: `cosec:`, `g:`, `r:`, `role:`;
  - rate limit ids: letters, digits, `.`, `_`, `-`.

  The special values (`(0)` ×2, `(platform)`, `""` space, `(default)`, `""` app, `*`, absent) have no table.
- **Change.** One table in `language.md`: each kind of id with its grammar and reserved forms, and each special value
  with where it appears and what it means. Link it from the glossary.
- **Touches.** Docs en/zh. **Size.** S. **When.** 8.0.x.

### UD-18 · S4 · `boundary` is a boolean on a policy and a policy id on a tenant

- **Evidence.** Policy `"boundary": true` versus tenant document `"boundary": "<policy id>"` versus platform
  `boundaries.default`.
- **Change.** Note the two uses in the language table. A rename (`boundaryPolicy`) waits for 9.0.
- **Size.** S. **When.** 8.0.x.

### UD-19 · S4 · "Platform" names three things

- **Evidence.** The platform document (`platform`); the platform tenant `(platform)` with its own `tenant:(platform)`
  document; the "platform layer", app documents included (`DocumentKind` KDoc: "APP documents belong to the platform
  layer"). Sentences such as "`cosec:platform-admin` is bound only in the platform document and the platform tenant's
  own" need a second reading.
- **Change.** The glossary names all three and always says "the platform tenant `(platform)`" in full.
- **Size.** S. **When.** 8.0.x.

### UD-20 · S4 · "Scope" is overloaded

- **Evidence.** The request's scope (`Scope`: tenant, app, space, roles); a binding's scope (`BindingScope`: app ×
  space); the control plane's `scope/<t>/<a>/<s>` resources and "grant scope". `SecurityScope` has no tenant while
  `Scope` has one, and `scope.tenant` duplicates `subject.tenant`.
- **Change.** The glossary says "a binding's app and space" rather than its scope.
- **Size.** S. **When.** 8.0.x.

### UD-21 · S4 · One concept, several names in output

- **Evidence.**
  - the action's first part is `namespace` in `Action` and "service" in the docs, `action.service` and the route map;
  - a policy's condition is a "guard" only in output (`GUARD_MISS`, `Cause.MatcherFailed.guard`);
  - the binding a statement applied through is `via` (explain), `binding` (audit) and `cosec.binding.id` (span);
  - a revision is `revision`, `version`, `at`, `snapshotVersion` and `cosec.snapshot.version`;
  - an attachment is `policies` in the token and in `explain`.
- **Change.** Document each pair once (the glossary, beside the term). Renames wait for 9.0.
- **Size.** S. **When.** 8.0.x.

## 5. What is good, and must stay

- **Nothing is wider by omission in bindings.** `app` and `space` are required with no default; `Subject` has no
  default for `principalBindings` and `breakGlass`. Extend this rule (UD-5); do not relax it to make bindings shorter.
- **A rejected space is refused, never moved to the default space** (`SPACE_NOT_MEMBER`), and `*` and reserved spaces
  are never members. Keep it.
- **Deny-first, decided by the set of candidates, not their order**, explained once with a reason in
  `security-model.md`. The candidate order changes only which statement is reported. That page is the best piece of
  the model's presentation.
- **Roles in conditions** (`scope.roles` versus `subject.roles` versus `subject.scopedRoles`, with when to use each in
  a DENY and an ALLOW) is exact and safe.
- **Absent values have a written semantics** (`eq` false, absent ≠ `""`, `not` plain), applied in one evaluator.
  Fix UD-3 and UD-9 without changing it.
- **Indeterminate is its own decision** and never a Permit; every cause is typed (`Cause`).
- **Roles stay in short form in the stored document and expand in one function** (`RoleExpansion.effective`), so
  compile, analysis, diff and explain see one thing.
- **The scenario pages run** (`DocumentationExamplesTest`): every claim made as a scenario is checked on every build.

## 6. For another group

- **A:** the `SubjectSummary.method` KDoc lists `api-key`, but the value is `apikey` (UD-11 is the naming; the KDoc
  fix is A's).
- **A/testkit:** `PolicyTestKit` accepts request fields that production never sets (`resource.attributes`; UD-2). Check
  whether other scenario fields have the same gap.
- **B:** three spellings for the host app's id: `cosec.authorization.scope.app-id`, `cosec.control.app-id`, route
  metadata `cosec-app-id`.
- **C:** a document's `revision` and `publishedAt` are required in a file the control plane then overwrites on
  `:apply`. Authors meet "revision" as a field they must invent.
- **C:** the control plane has two warning sites in all (`SectionChecks`, `RoleChecks`). Statements that can never
  match (UD-2, UD-3), an ALLOW with no `resource` on `http:*` (UD-5), and a role id shared by two app documents (UD-1)
  are candidates.
- **E:** `wiki/policies/language.md#documents`: "A document nobody published reads as empty". That is true for app and
  tenant documents; an absent platform document is unresolved (`DocumentSnapshotViews`, `platformRequired`), as
  security-model.md says.
- **E:** the zh twins of every docs finding above (UD-4, UD-6, UD-10, UD-13, UD-14, UD-16).

## Appendix: answers to method steps 2–6

**The minimum.**

- *One service, two roles, one tenant.* Needed:
  - the platform document (always; an absent one is 503 for everything, drill finding 23);
  - policy, statement (effect, action, resource), the `http` namespace;
  - binding (subject `"*"` or a role, with `app` and `space` written as `*`);
  - the token's `roles` claim;
  - the default tenant `(0)` when tokens carry no tenant;
  - deny-first and default deny.

  Ignorable, and CoSec lets you ignore them: app (no `aud`, no `app-id`), space, route maps, catalogs, role entries
  (they need an app), boundaries, attachments, rate limits, external attributes. Not ignorable: `app` and `space` on
  every binding (`*`: correct, see §5) and `revision`/`publishedAt` (C). `simple-mode.md` introduces them in that order
  and lists them; see UD-16 for the one trap.
- *Several tenants.* Add the `tenantId` claim, tenant documents, the reach of platform `Anyone` bindings and of app role
  bindings without `tenant`, the `(0)` mix, and boundaries if wanted. No page walks it (UD-15).
- *Several apps behind one gateway.* Add `aud` per app, route metadata `cosec-app-id` (else the credential's app
  decides, UD-4), app documents, catalogs, system roles, and route maps per service. Distinct role ids per app if a
  provider or API keys are used (UD-1).
- *Spaces.* Add `role@space` claims, `CoSec-Space-Id`, `spaced` on groups, a role's `space`, and binding `space`.
  `spaces.md` lists them. The default space's two spellings are UD-9.

**The confusable pairs.**

| Pair | What tells them apart | Said in one place? | If confused |
|---|---|---|---|
| tenant / app / space | Tenant: the credential's customer, isolates documents. App: the credential's (`aud`), checked against the host, picks an app document. Space: chosen by the client among the credential's spaces, picks the roles that count. | Partly: model.md and security-model "Trust boundaries"; concepts.md is wrong on app and space (UD-4, UD-6) | App as client input: the user misjudges where the weak spot is (UD-4). Space as part of an app: the user expects isolation across apps that does not exist; RFC 0012 says role ids, not spaces, separate apps, and UD-1 shows they don't with a provider |
| principal / subject | The binding's subject is the grantee (anyone, principal, role); `subject.*` is the requester | No (UD-10) | Mostly reading friction; zh readers think bindings name principals only |
| role / policy / binding / permission | A permission is a catalog entry = policy `perm:`; a role is a named set of permissions that expands into bindings; a binding attaches a policy to a subject in app × space; a policy is statements | Yes: model.md's class diagram and `role-permissions.md` (diagram errors, UD-14) | Writing a permission as a policy and also as a short form does not compile; harmless |
| role binding in an app document / in a tenant document | App: every tenant unless `tenant` is named. Tenant: this tenant | Yes, `language.md` subject table, `security-model.md#tenant-isolation` | Copying a binding from a tenant document into an app document widens it to every tenant (more access); review catches it because app documents are platform-owned |
| attached / bound policy | Attached: listed by the principal's memberships (`policies` claim), applies in every app and space, must be `attachable`. Bound: a document says who | Yes, `language.md#attachments` | Thinking an attachment is per app or space: more access than intended. The claim name `policies` (UD-21) invites reading it as "every policy that applies" |
| explicit / implicit deny / indeterminate | DENY matched / nothing matched / could not decide, 503 | Yes, `security-model.md` | Output vocabularies blur explicit deny (UD-7) |
| request scope / binding scope | Scope = tenant, app, space, roles; a binding matches app × space | Partly (UD-20) | Low harm |
| platform boundary / DENY | A boundary is an allow-list ceiling on a whole tenant's permits, ALLOW statements only, not on `(platform)`, checked after a Permit. A DENY is any statement that matches | Yes, `security-model.md#platform-boundary` | Writing a "boundary" with DENY statements does not compile; outputs call a boundary refusal an explicit deny (UD-7) |
| `service:verb` / `http:<METHOD>` | A mapped route is only its action; an unmapped request is only `http:` | Yes, at length (known: U3, route maps) | Covered by earlier reviews |
| resource type and id / HTTP path | Typed `type/segments`, `*` = one segment, `**` last only; `http:path:` follows `PathPattern`, `*` inside a segment = characters, `**` first or last | Yes, `language.md#resources` | Low; patterns of the wrong kind do not compile |

**Defaults and special values.** See UD-3 (anonymous `(0)` versus absent), UD-9 (default space `""`/`(default)`;
empty app = absent in conditions, `""` elsewhere; absent = `""` in rate-limit keys), UD-5 (omitted `resource` is
everything). Also: an omitted policy or statement `condition` means always, as expected; a role's omitted `space` means
any space the member holds the role in, which is bounded by holding. Nothing else turns a missing value into
"everything". `*` means "any" in each place it is allowed and is refused where it would be ambiguous (`role@*`, app
and space ids).

**Predictability.** With deny-first and a decision independent of candidate order, a reader can predict decisions from
the documents, except where an input is invisible in the documents. The rules users most plausibly get wrong:

- an attribute that is always absent (UD-2, UD-3), together with `not` over absent;
- which app document applies (UD-4) and which roles are in it (UD-1);
- a policy-level condition silently disabling its DENYs (documented, with advice);
- a route map moving a path out of `http:` (known);
- an app document's rate limits not holding requests whose principal has no role in the space (documented in
  `rate-limiting.md`).

What tells them: compile errors for patterns and attribute paths; two control-plane warnings; `explain`/`simulate`
(no external or token attributes); the test kit (which hides UD-2). Nothing flags a statement that can never match.

**Diagrams and glossary.** The overview's sequence diagram and the security-model flowchart match the code. model.md's
class diagram has four errors and concepts.md's flowchart one (UD-14). The glossary is incomplete and partly stale
(UD-6).

## Appendix: triage table (added at the coordinator's request)

| id | severity | defect | fix touches | breaking | size | when | needs the maintainer |
|---|---|---|---|---|---|---|---|
| UD-1 | S1 | Provider is never told the app; one role id grants in every app | docs term, KDoc; 8.1 SPI overload (public name) | no (docs); SPI overload additive | S docs / M SPI | docs before RC1; SPI 8.1 | yes (SPI part): downstream implements the SPI |
| UD-2 | S1 | `resource.attributes.*` compiles but is never set; the test kit fills it anyway | test-kit message, stricter compile (or a control-plane warning) | stricter compile | S | before RC1 | yes: changes what documents compile |
| UD-3 | S1 | Anonymous is `(0)` in outputs but has no id in documents; comparisons never match | docs term, stricter compile | stricter compile | S | before RC1 | yes: changes what documents compile |
| UD-4 | S2 | Docs say the client names the app; removed in #1302; real risk undescribed | docs term, diagram | no | S | before RC1 | no: docs only |
| UD-5 | S2 | A statement without `resource` matches every resource of every service; nothing warns | docs term; message (control-plane warning); document format later | no (docs, warning); format (9.0) | S / S / M | docs before RC1; warning 8.0.x; format 9.0 | yes (format part): makes `resource` required |
| UD-6 | S2 | Glossary lacks role, permission, boundary, credential, subject; calls a space app-internal | docs term | no | M | before RC1 | no: docs only |
| UD-7 | S3 | Audit and metrics call scope, boundary and 400 refusals explicit deny; ALLOW means Permit | KDoc, docs; public name (audit enum value) | no (KDoc); name (enum value) | S | KDoc before RC1; values before RC1 or 9.0 | yes (value part): audit consumers depend on it |
| UD-8 | S3 | `roles` is all roles in a token, one space's roles in header and audit | KDoc, docs | no | S | before RC1 | no: KDoc and docs only |
| UD-9 | S3 | Default space is `""` and `(default)`; `role@(default)` silently applies nowhere | KDoc, docs, message (trace `malformed`) | no | S | before RC1 | no: trace and docs only |
| UD-10 | S3 | "Subject" means a binding's grantee and the requester; zh terms collide | docs term | no | S | before RC1 | no: docs only |
| UD-11 | S3 | Credential method is `jwt`/`api-key` in the header, `token`/`apikey` in decision events | public name (constant value), KDoc | name | S | before RC1 | yes: public constant and event value |
| UD-12 | S3 | "attributes" names five things; the platform section declares only external ones | docs term; document format (8.1 alias) | no (docs); format (additive alias) | S | docs 8.0.x; format 8.1 | yes (alias part): document format change |
| UD-13 | S3 | Example sets `spaced: true` on hand-written policies, where it has no effect | docs (example) | no | S | before RC1 | no: example only |
| UD-14 | S3 | Class diagram puts `spaced` on permission, draws a `kind` field, omits Boundary and Obligation | diagram | no | S | before RC1 | no: diagram only |
| UD-15 | S3 | No scenario page sets up several tenants | docs (scenario page, examples) | no | M | 8.0.x | no: docs only |
| UD-16 | S3 | Simple mode's platform role condition reaches that role in every tenant, unsaid | docs term | no | S | before RC1 | no: docs only |
| UD-17 | S3 | Id grammar is spread over five places; special values have no table | docs term | no | S | 8.0.x | no: docs only |
| UD-18 | S4 | `boundary` is a boolean on a policy and a policy id on a tenant | docs term; rename deferred to 9.0 | no (docs); format (9.0 rename) | S | docs 8.0.x | no now; a 9.0 rename would |
| UD-19 | S4 | "Platform" names a document, the `(platform)` tenant and the platform layer | docs term | no | S | 8.0.x | no: docs only |
| UD-20 | S4 | "Scope" names request scope, a binding's app × space, and control-plane resources | docs term | no | S | 8.0.x | no: docs only |
| UD-21 | S4 | One concept, several output names: namespace/service, guard, via/binding, revision/version | docs term; renames deferred to 9.0 | no | S | 8.0.x | no now; 9.0 renames would |
