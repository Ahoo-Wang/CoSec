# Usability review, group E: can each reader get from zero to a correct, safe result with the documentation

Read-only review, 2026-11-18. No code or docs changed, nothing committed, pushed or posted. No Gradle run. `pnpm install` and
`pnpm check:docs` were run once in `wiki/` (all green: 68 pages, 2450 internal links, en/zh anchor parity, no removed API,
the claims table held to `contract/token/claims.json`). Two read-only requests went to the public site and one to
`gh release list`: `https://cosec.ahoo.me/problems/forbidden` (404), `/llms.txt` (200), `/llms-full.txt` (404).

## 1. Baseline

`origin/main` = **`d642f0125741e81bbdd0ec90d177562fa1b864cc`** (#1654, with #1655 and #1657 under it). Everything below was read at this commit.

Not covered: nothing ran (no compiled sample, no site build, no browser), so "runnable" below means "checked line by line against the
signatures in the source". I did not read `wiki/policies/language.md`, `wiki/scenarios/external-source.md`, `wiki/scenarios/spaces.md`,
`wiki/operations/observability.md` or `wiki/architecture/overview.md` page by page: tasks that rest on them cite only the heading. The
`zh/` twins were compared mechanically (see section 5), not read in full. The sibling reports A to D were read first; where a finding
here is the documentation face of one of theirs, it cites their id and adds only what they missed.

## 2. The five that matter most

1. **UE-1 (S1)** The policy-author skill's "Minimal platform document" is an allow-all for every authenticated caller: `{"effect":"allow","action":"*"}` with no `resource`, bound to `*`/`*`/`*`. The only DENY next to it is an `http:*` one that the same skill says never applies to a route-mapped request.
2. **UE-2 (S1)** The same skill says `resource.attributes.<name>` is "set by checks in code". It is not: `AuthorizationChecker` builds every resource with `emptyMap()`, so a DENY an agent writes on it never applies.
3. **UE-6 (S2)** The Extending page's recipe for a custom `Authenticator` rebuilds `TokenAuthenticator` without the three `cosec.authentication.revocation.*` settings. An operator who set `fail-closed: true` and follows the recipe is open again, with no sentence saying so.
4. **UE-3 (S2)** The README's platform document cannot be changed through the control plane: it never permits `/cosec/control/v1/**`, and `publish` never overwrites. The Quick Start's own document does permit it; the README is the first thing readers copy.
5. **UE-4 (S2)** The home page, README, `llms.txt` and the Introduction say revocation is "always checked" and unreadable data "fails closed". With the default `cosec.authentication.revocation.fail-closed=false` a request's token is accepted while Redis cannot be read; the front-door pages carry neither exception, and the hardening checklist does not name the setting.

Count by severity: **S1: 2, S2: 4, S3: 14, S4: 6** (26 findings).

## 3. The journeys

Clicks are counted from the home page. The sidebar lists every page, so any page is one click from any other; what is not reachable by
click is an H3/H4 anchor (UE-19).

### J1. An application developer adds CoSec to one Spring Boot service (WebFlux, then servlet), until a role-protected endpoint works and a test proves it

Pages in order: home → Introduction (path "A developer integrating it") → Quick start → Web integration → Authentication → Checks in code → Configuration. The role part is on [Simple mode](../scenarios/simple-mode.md), which is not on this path; the test part is on Testing policies, which is not either.

Obstacles, in the order a reader meets them:
- Three tools before the first request: Redis (`docker run`), `cosec-migrate` (a zip or a Gradle build, named like a 7.x migration tool), the starter. All named; none surprising. The `docker run` publishes the Redis port on every host interface (UE-10).
- Quick Start steps are correct and every Kotlin line compiles against the current signatures (`TokenSubject(id, app, tenant, …)`, `TokenConverter.toToken`, `CompositeToken`, `ApplicationRunner`). The first request that answers is `200 hello`; the first denial is explained (`403` for DELETE, `403` implicit deny, `401` `token-invalid`). This is the best page in the set.
- **A role-protected endpoint is not in the Quick Start.** Its demo `/token?user=alice` issues no roles. Roles are on Simple mode, as one line of Kotlin (`TokenSubject(id = user.id, app = null, roles = setOf("admin"))`) that does not say where it goes; the reader changes the demo controller on their own. The Quick Start links Simple mode ("Roles come in Simple mode") but the developer path does not.
- **A servlet reader** substitutes `cosec-webmvc` for `cosec-webflux` (the dependency comment says so) and also has to know to swap the Spring starter. Reading the caller: the MVC form (a `SecurityContext` handler parameter) is on Web integration and good. Checking in code: the first sample on Checks is WebFlux only; the `checkBlocking` form appears first inside the object-level snippet with an undefined `security` (UE-15). On WebFlux a `suspend` handler has no documented way to the context (UA-7).
- **A test that proves it.** `PolicyTestKit` (documents) is the third-from-last section of the 700-line Testing policies page, whose title and first 500 lines are the control plane. No page shows a Spring-context test (`WebTestClient`, `MockMvc`) against the starter; `PipelineFixture` runs a pipeline over documents, not the host (UE-8).
- Found by search, not by a link from the Quick Start: the test kit, `remote-ip.max-trusted-index`, `revocation.fail-closed`, the hardening checklist.

End state: a working, correctly denying service. Not clean: the demo `/token` endpoint and its public statement, a `platformToken` runner that prints a `(platform)` administrator token on every start, and Redis on all interfaces are all still there, and "Next steps" removes none of them (UE-10). `max-trusted-index` stays at its default `1` (UB-4); the Quick Start never mentions it.

### J2. A policy author writes, tests and publishes a first policy set

Pages in order: Introduction (path "A policy author") → Concepts → Model → Policy language (693 lines) → Role permissions → Rate limiting → Testing policies. The first runnable document is on Quick Start/Simple mode, which this path does not contain.

Obstacles:
- The path opens with three explanation pages before any document. A first document is found in Quick start (4 policies), Simple mode (adds a role), or the app scenario; nothing says "start here" to an author.
- Short or long form for a permission (UC-15), and the header the author must write but does not control (UC-5, UD's note on `revision`): both found by trial.
- Test: Testing policies → "Policies in CI" → "Testing decisions". Everything a kit scenario can and cannot say is on one page and correct; what is missing is the way in (UE-8). The kit loads `platform.json` as an empty document when the file is missing, where production answers `503`; the page says so, the author will not read it (UC-7).
- Publish: first document `cosec-migrate publish` (documented in Quick start, Storage#the-first-documents, Upgrading); every later one through the control plane, which needs an application hosting `cosec-control` and a `(platform)` token. An author who is not the developer cannot publish; no page says who has to be present.
- `language.md#documents` says "A document nobody published reads as empty" in the paragraph about the documents every request reads (UE-11).

End state: safe if the author ran the kit. Nothing forces a DENY test, and the JSON Schema step is optional; a statement without `resource` matches everything (UD-5) and no page warns about it where an author writes a statement (`language.md#statements`).

### J3. An operator deploys the gateway with a service behind it, and runs it

Pages in order: Introduction (path "Running it in production": Configuration → Storage → Observability → Troubleshooting → Upgrading). **Deployment, the Gateway topologies scenario and the Security model are not on the path.** Real order for the operator: Deployment (via the sidebar) → Web integration#gateway → Gateway topologies → Security model#trust-boundaries.

Obstacles:
- The path starts with a 400-line reference (Configuration) and never reaches the page about the image (UE-9).
- Deployment is good: image, what the gateway forwards, "Only the gateway may reach a service in gateway mode" in bold with the exact WARN, readiness and probe guidance, Kubernetes, memory. Its `docker run` shows a gateway that answers `503` until a document exists and then explains why one page lower.
- First document: `cosec-migrate publish`; a change: no deployable control plane exists (UB-18); the page says "or by a control-plane application on the same Redis" and stops. An outage: Storage#when-redis-is-unavailable (good). An upgrade: Upgrading (7.x) or Upgrading previews; a fleet rolling upgrade after GA has no page (UB-23).
- The hardening checklist has one inbound link in the whole site (the gateway scenario's last line).

End state: the gateway-mode boundary is stated at the sample (bold, before the reader deploys), good. Not stated on the operator path: Redis persistence and backup (UB-16), JMX (UB-2), `revocation.fail-closed` (UE-4), CORS `*` in the sample manifest (UB-14).

### J4. A team upgrading from 7.x

Pages: Operations → Upgrading (1219 lines) → "Upgrade checklist" → Migrating the data → What 8.0 decides differently → Removed and renamed properties → Rollback.

Obstacles: none that stop. The page opens with who it is for, a nine-step checklist with an anchor per step, a short route for a 6.x service with no data ("No CoSec data of your own?"), the exit codes, `sha256` of the tool, a rollback section that says 8.0 never reads the 7.x keys. Drill findings 15 to 21 are fixed. Remaining friction is size (1219 lines, 12 H3s the outline hides, UE-19) and UB-23 (fleet upgrade after GA). End state: safe; "deploy 8.0, then check decisions on the requests you rely on" names `simulate` and `explain`.

### J5. A team upgrading from an 8.0 preview

Pages: Upgrading between previews (1380 lines, newest first) → the section of its own version, then every newer one.

Obstacles: a team on beta.3 reads two screens and is done; a team on alpha.1 reads 1380 lines and has no table "from X, read sections …". The first section carries the two security changes of the last weeks (`PIP-26`, `PIP-27`) with who is affected and what to do, in the right order. The "From beta.3" section omits what #1655 changed in the host (a `WARN` for `RevocationReader.NONE`, the unknown-property check running before any bean, one startup message naming every missing token store); none breaks a deployment, but each changes what an operator sees in the log (UE-23). End state: safe.

### J6. Someone extending CoSec (an SPI implementation)

Pages: Introduction#modules → Extending CoSec (606 lines) → skill `cosec-custom-matcher`.

Obstacles: a clear table of every extension point with module, package and what the starter takes; stability levels; the "Kotlin only" rule stated up front; samples for attribute provider, membership provider, listener, rate limiter match the interfaces (`AttributeProvider.attributes/timeout/fetch`, checked). Wrong or missing: the `Authenticator` recipe drops the revocation settings (UE-6); the `TokenStore` row says "used by logout" (UA-4); "A Java application … calls the builder" cannot be done (UA-13); imports are never shown (`AttributeType` is in `cosec-model`, `AttributeDeclaration` in `cosec-spi`, the page names only the package of the port). A custom `TokenVerifier` has a paragraph and no sample (task 32). The skill's name says "matcher" (UE-21). End state: an extension that works; whether it keeps the hardening the starter configured depends on UE-6.

### J7. A front-end developer using `@ahoo-wang/cosec-fetcher` and `@ahoo-wang/cosec-react`

Pages: nothing on the entry path mentions the packages (README, Introduction, Modules table, home, `llms.txt`: none names them) → the sidebar → Building a client → the package README.

Obstacles: **install** is clear, with an honest warning about the `0.0.0` placeholder on `latest`; `@beta` is wrong from RC1 on (UE-20). **Sign in**: the README never shows how the pair a login answers gets into the `TokenStorage` (`tokenStorage.signIn(pair)` exists, `tokenStorage.ts:127`; the README mentions `signIn` only in the upgrade notes). **Refresh**: the contract is well documented on Building a client (status table, rotation leeway, one refresh at a time, what to do on each problem type); the README's logout sample passes the configured fetcher where its own text says to keep refresh on a separate unconfigured one. **Errors**: a problem-type table with an action per type, good. The React example imports `./tokenStorage`, a module the page never shows. The `fetcher.ahoo.me` recipe link cannot be checked from here.
End state: a client that, if the reader reconstructs the sign-in, refreshes and logs out correctly; the defaults (`sameOriginTrust`) are safe.

### J8. An AI coding agent given only `llms.txt` and the skills

Pages: `llms.txt` (key facts, 22 links) → Quick start; skills `cosec-integration`, `cosec-policy-author`, `cosec-custom-matcher`, `cosec-troubleshoot`.

Obstacles: `llms.txt` reaches 22 of 33 pages; it omits Deployment, Model, route maps, the gateway scenario and seven other scenarios, and there is no `llms-full.txt` (UE-14). The integration and troubleshoot skills are accurate and unusually careful (they say "never suggest `warn` to silence a Redis that really evicts", name the revocation exception, state `PIP-26`). The policy-author skill's two samples are unsafe (UE-1, UE-2). The integration skill's "Minimal configuration" sets an app and a route map the Quick Start does not (UE-13), so an agent that combines the two meets `403 scope-rejected` or a startup failure.
End state: with the integration and troubleshoot skills, safe; with the policy-author skill's sample copied, open.

## 4. Task completeness (40 tasks)

"Runnable" = complete and correct against the code as written. "2 clicks" = the page is one click from anywhere through the sidebar; the answer is an H3/H4 anchor the right-hand outline does not list (UE-19) unless marked "h2".

| # | Task | Answer (page#anchor) | Complete, runnable | 2 clicks | Note |
|---|---|---|---|---|---|
| 1 | Protect an endpoint | guide/quick-start#write-a-first-policy | yes, and tested (`examples/quick-start`) | yes (h2) | |
| 2 | Role-protected endpoint | scenarios/simple-mode#the-document | document tested; the token line is a fragment | yes (h2) | where the `roles` line goes is not said (J1) |
| 3 | Read the caller | integration/web#accessing-the-security-context | MVC and Mono yes; WebFlux `suspend` handler no (UA-7) | yes (h2) | |
| 4 | Check in code | integration/checks#checking-a-permission | WebFlux sample yes; MVC no sample | yes (h2) | UE-15 |
| 5 | Object-level rule | integration/checks#object-level-rules | document and scenarios tested; handler line uses an undefined `AccessDeniedException` | yes (h2) | |
| 6 | Add a role | scenarios/roles#two-kinds-of-role, policies/role-permissions#roles | yes | yes | two pages, consistent |
| 7 | Bind a role to a user | integration/authentication#roles-and-spaces, #memberships; scenarios/external-source | no single recipe: "a member holds a role by its id in its token or `MembershipProvider`" is one sentence on the roles scenario | no | two real answers (token claim, provider) and a default that keeps old claims on refresh (UA-2); a recipe page is missing |
| 8 | Multi-tenant setup | guide/concepts#tenant, guide/model, scenarios/tenant-owner, integration/authentication#tenants | scattered; how a tenant is onboarded (document, first owner) is only implicit in tenant-owner | no | no recipe |
| 9 | Spaces | scenarios/spaces, policies/role-permissions#spaces | yes, tested | yes (h2) | |
| 10 | API keys | integration/authentication#api-keys, scenarios/service-account | yes; the second sample key has no `expires-at` (a gateway answers `500` for it) without saying so | yes (h2) | |
| 11 | Rate limit | policies/rate-limiting#rules | yes | yes (h2) | |
| 12 | Logout everywhere | integration/authentication#revoking-every-token-of-a-principal | yes | no (h3) | |
| 13 | Rotate the JWT secret | **none** | no | - | `configuration.md` says only "long random value from a secret store"; rotation invalidates every token, and any verifier can mint (UB-20) |
| 14 | Explain a 403 | operations/troubleshooting#forbidden → #find-out-what-decided → integration/checks#explaining-a-decision | yes | yes (h2/h3) | the link in troubleshooting goes to the top of `checks.md`, not to the anchor |
| 15 | Test a policy | policies/testing#testing-decisions | yes, runnable | no (h3, line 550 of 703) | UE-8 |
| 16 | Test an endpoint through Spring | **none** | no | - | UE-8 |
| 17 | Publish and roll back | policies/testing#publishing-and-rolling-back, operations/storage#revisions-history-and-rollback | yes | yes (h2/h3) | two pages, same facts |
| 18 | Shadow a change | policies/testing#trying-changes-in-shadow-mode | yes | yes (h2) | |
| 19 | Add an attribute provider | architecture/extending#external-attributes | yes, imports missing | yes (h2) | |
| 20 | Audit to Kafka | operations/observability#audit-log-and-kafka, operations/configuration#audit | yes | no (h3) | needs `cosec-kafka`: Introduction#modules |
| 21 | Metrics | operations/observability#metrics | yes | yes (h2) | |
| 22 | Health and readiness | operations/deployment#readiness, operations/observability#snapshot-health, integration/web#actuator-on-an-application | yes; three pages agree | yes (h2/h3) | the Quick Start's dependency block has no Actuator though it names the readiness path (UB report, E list) |
| 23 | Redis requirements | operations/storage#redis-requirements | partial: `noeviction` only | no (h3) | persistence, replication, backup absent (UB-16) |
| 24 | Upgrade from 7.x | operations/upgrading#upgrade-checklist | yes | yes (h3) | |
| 25 | Upgrade from a preview | operations/upgrading-previews | yes, no start-here table | yes (h2) | UE-23 |
| 26 | Service-to-service calls | scenarios/service-account (API key), integration/authentication#api-keys, scenarios/resource-server | two answers (API key; a token issued for a service principal), no page chooses between them | partly | the Wow drill's finding 18 is fixed for a resource server; the calling side is still implicit |
| 27 | A service behind the gateway | integration/web#services-behind-the-gateway, operations/deployment#services-behind-the-gateway, scenarios/gateway | yes; trust boundary stated at the sample | yes (h2/h3) | three pages, consistent |
| 28 | CORS | integration/web#cors, integration/clients#cors | yes, origin-specific sample, tested rows | yes (h2) | the sample manifest in `k8s/base` is `*` (UB-14) |
| 29 | The actuator | integration/web#actuator-on-an-application, #actuator-on-a-gateway | yes | no (h3) | |
| 30 | Custom authenticator | architecture/extending#authenticators | compiles; drops the revocation settings | yes (h2) | UE-6 |
| 31 | Custom token verifier | architecture/extending#authenticators (one paragraph), integration/authentication#verifying-a-token-outside-the-pipeline | partial: contract, no sample | yes | |
| 32 | TypeScript sign-in and refresh | integration/clients#refreshing-under-rotation, `typescript/cosec-fetcher/README.md` | contract yes; sign-in sample missing | yes | UE-5, UE-7 |
| 33 | Social login | integration/authentication#social-login, operations/configuration#social-login | yes | yes (h2) | |
| 34 | Switch tenant or app | integration/authentication#switching-tenant-and-app | yes, with a test | no (h3) | |
| 35 | Client IP behind proxies | integration/web#client-ip-behind-proxies | yes; the default (`1`) is unsafe for a directly exposed host (UB-4) and the Quick Start does not mention it | yes (h2) | |
| 36 | Route map from OpenAPI | policies/role-permissions#generating-a-catalog-from-openapi | yes; the skill names another springdoc artifact (UE-13) | yes (h2) | |
| 37 | Run without Redis (tests, local) | operations/storage#choosing-a-store | partial: `InMemorySnapshotDocumentStore` named; JWT still needs three token stores, so "without Redis" is not one recipe | no | no dev profile page |
| 38 | Deploy on Kubernetes | operations/deployment#kubernetes | yes; sample CORS `*` and demo route (UB-14), mounted config drops image defaults (UB-21) | yes (h2) | |
| 39 | Size memory | operations/deployment#memory | partial: no figure per document (UB-24) | yes (h2) | |
| 40 | Change a document after the first publish | guide/quick-start#change-a-document-later | yes, tested | yes (h2) | the demo token for it is marked "Demo only" |

Tasks with no answer: 13, 16. With two different answers: 7, 26 (both legitimate options, no page chooses); and one contradiction between pages, task "an absent platform document" (UE-11).

## 5. Samples: what is compiled or run, what is free text

`DocumentationExamplesTest` (`:conformance:test`) reads `wiki/examples/`: **124 files, all JSON** (documents, change bodies, route maps, scenarios). 35 `<<<` includes pull 35 of them into pages, on the scenario, Quick Start, checks and clients pages; the rest are scenarios the test runs. Everything else in the wiki is free text, outside the English upgrade pages: **40 Kotlin blocks, 11 YAML blocks, 31 inline JSON blocks, 20 bash blocks**. Nothing compiles a Kotlin sample or checks a `cosec.*` key in a YAML sample; `check:docs` checks links and anchors only.

What I checked by hand against the source, for journeys 1 to 3 and the extension pages: every Kotlin block of Quick start, Web integration (security context, CORS), Checks, Authentication (login, refresh, switch, verify-outside-pipeline, test), Testing (kit, `PipelineFixture`, CI compile), Extending (authenticator recipe, attribute provider), Resource server, plus every YAML block's key names (all exist: `api-key.keys[].*`, `pipeline.mode`, `scope.app-id`, `routes.location`, `management.endpoint.health.group.readiness.include`). All 31 inline JSON blocks parse. **No sample fails to compile**; what fails is smaller: Checks' `AccessDeniedException()` (a class the page never introduces) and an undefined `security` (UE-15); the fetcher README's imports (UE-5); `cosec.*` keys cited elsewhere in the wiki: every one outside the Configuration page is either a metric, a package, a claim, a removed key (in the Upgrading tables) or covered by a `cache.*` wildcard row.

Unsafe-by-example, each checked at the sample:
| Sample | Would copying leave something open? | Marked at the sample? |
|---|---|---|
| Quick start platform document, `/token` public statement | the endpoint exists only if the reader keeps the demo controller | the controller says "Demo only"; the document does not |
| Quick start `platformToken` runner | prints a `(platform)` admin token at every start | "Demo only" comment, in code only |
| Quick start `docker run -p 6379:6379` | Redis on every host interface | no |
| README platform document | cannot be changed later (UE-3); `/api/**` open to every authenticated caller of any tenant | no |
| Policy-author skill "Minimal platform document" | allow-all for authenticated callers | no (UE-1) |
| Extending `Authenticator` recipe | revocation unreadable accepted whatever `fail-closed` says | no (UE-6) |
| `authentication.md` test class `RevocationReader.NONE`, `Algorithm.HMAC256("test-secret")` | no, test scope | yes ("Testing a switch") |
| API key sample, `uncapped: true`, no `expires-at` | an uncapped key that never expires; 500 on a gateway | the comment says what it holds; the expiry is not mentioned |
| Web CORS sample | no: explicit origin | n/a |
| `k8s/base` CORS `*` | UB-14 | no |

## 6. en and zh

`pnpm check:docs` holds headings and anchors. I compared the 21 journey and reference pages mechanically: fenced-block counts and `<<<` includes (identical on every page), section-by-section paragraph, list-item and bold-warning counts (identical on all 13 pages compared), inline-code tokens (the differences are wrapped lines and translated placeholders), numbers and units. **The Chinese is a faithful mirror; no warning the English has is missing, no stale sentence found.** What differs:
- It inherits every English defect above, including UD-4's (the introduction, concepts and security-model pages in both languages still say the client names the app) and UE-11, UE-4, UE-12.
- `zh/guide/quick-start.md:188` keeps the English comment "Demo only: anyone who can run this can administer the deployment" where line 111 translates the sibling comment.
- `撤销` appears 25 times where the glossary says revoke is `吊销` and `撤销` is wrong (`zh/policies/testing.md`, `zh/guide/security-model.md`, `zh/operations/troubleshooting.md` and others). Most are "undo a publish or a break-glass grant", which the glossary does not cover; the glossary should say which word.
- `zh/operations/upgrading.md` says `凭据` twice, the glossary's rejected word for credential.
- The zh Quick Start has the same `docker run -p 6379:6379` as the English (UE-10).

## 7. Findings

Ordered by severity, then by reader harm. `Owner` is the page `AGENTS.md`'s table or `wiki/AGENTS.md` makes home of the fact.

### UE-1 — S1 — The policy-author skill's minimal platform document allows everything to every authenticated caller

- **Reader, journey:** an AI agent (J8), or a person reading the skill, writing a first platform document.
- **Where:** `skills/cosec-policy-author/SKILL.md`, "Minimal platform document", policy `members`.
- **What it says, what is true:** `{"id":"members","statements":[{"effect":"allow","action":"*","condition":{"eq":["subject.authenticated",true]}}]}`, bound to `"*"`, app `"*"`, space `"*"`. A statement without `resource` matches every resource (the skill's own "Policies and statements": "absent means any resource"; D's UD-5), `action: "*"` matches every action. So any authenticated principal of any tenant may do anything in every app. The one DENY, `no-admin-api`, is `http:*` on `http:path:/api/admin/**`; the skill's own "Actions and resources" says "A statement on `http:*` never applies to a route-mapped request", and its sibling skill's "Minimal configuration" sets a route map. The skill's "Security review" lists "broad `subject: "*"` ALLOWs" and "ALLOWs built on `not`" as defects; its sample is both.
- **Recommendation:** replace `members` by the Quick Start's `http:*` on `http:path:/orders/**` with `subject.authenticated` (document `examples/quick-start/documents/platform.json`), or keep the sample and mark it "DEMO: every authenticated caller may do everything; narrow `action` and `resource` before any real use". Add to "Security review": "an ALLOW with `action: "*"` or without `resource`". Make the skill's sample a file under `wiki/examples/` that `DocumentationExamplesTest` runs, and have the skill include it, so it cannot drift.
- **Owner:** `policies/language.md#statements` (facts); the skill is a copy. **Size:** S. **When:** before RC1.

### UE-2 — S1 — The policy-author skill says checks in code set `resource.attributes.*`; they set none

- **Reader, journey:** an AI agent writing a DENY that depends on a fact about the object (J8, J1).
- **Where:** `skills/cosec-policy-author/SKILL.md`, "Attributes": "`resource.attributes.<name>` (set by checks in code)".
- **What is true:** `AuthorizationChecker` builds every resource with `emptyMap()` (`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/AuthorizationChecker.kt:90-96`); the pipeline sets none (D, UD-2); `policies/language.md` ("Neither the request pipeline nor the documented checks set any") and `integration/checks.md#object-level-rules` ("A check passes no resource attributes") say so. A DENY on `resource.attributes.owner` compiles, publishes and never matches, and the policy test kit accepts the same field in a scenario, so a test can pass too (UD-2).
- **Recommendation:** replace the parenthesis with "never set on the request path or by `AuthorizationChecker`: put the facts in the resource id (checks#object-level-rules)". The code half (compile error, or the kit refusing the field) is UD-2's.
- **Owner:** `policies/language.md#attributes`. **Size:** S. **When:** before RC1.

### UE-3 — S2 — The README's platform document is one the control plane can never change

- **Reader, journey:** a developer who copies the README's "Quick start" (J1, J2).
- **Where:** `README.md` and `README.zh-CN.md`, "Quick start", the JSON document.
- **What is true:** it permits `POST /auth/login`, `GET /actuator/health` and `http:*` on `/api/**` for authenticated callers, and nothing for `/cosec/control/v1/**`. A control request first passes the host pipeline (`policies/testing.md#the-control-plane`), so every call is `403`; `cosec-migrate publish` never overwrites (`guide/quick-start.md#publish-the-platform-document`); and `quick-start.md#change-a-document-later` says "without that [statement], the only way back is to empty Redis" (C's UC-6 is the code side: nothing warns at publish). The README ends "Continue with the Quick Start", whose document differs, so the reader is not told they hold a different one.
- **Recommendation:** use the Quick Start's document in the README, or add the `control-api` policy and binding to it and say in one sentence what the statement is for; better, generate the README block from `wiki/examples/quick-start/documents/platform.json` with a check (the example is already tested). Mark the `/api/**` rule "any signed-in caller of any tenant" or narrow it.
- **Owner:** `guide/quick-start.md`. **Size:** S. **When:** before RC1.

### UE-4 — S2 — The front-door pages say revocation is always checked and unreadable data fails closed; two documented exceptions say otherwise

- **Reader, journey:** an evaluator or operator (J3), an agent (J8).
- **Where:** `wiki/index.md` ("revocation that is always checked", "data that cannot be read never permits"); `README.md` ("a request that cannot be decided fails closed"); `llms.txt` ("always-on token revocation", "fail closed"); `guide/introduction.md` ("Safe defaults … data that cannot be read denies"); `guide/security-model.md#hardening-checklist` (no mention of the setting).
- **What is true:** with the default `cosec.authentication.revocation.fail-closed=false` (`operations/configuration.md` row; `TokenAuthenticator(acceptUnavailableRevocations = true)`), a request's token is accepted while the revocation store cannot be read, which `integration/authentication.md#logout-and-revocation` and `guide/security-model.md#the-two-documented-exceptions` document; rate limit rules without `strictFailure` are skipped. The two owner pages are accurate. The pages a reader meets first state an absolute, and the checklist offers no `fail-closed: true`.
- **Recommendation:** on each front-door sentence add the clause "with two documented exceptions" and a link to the security model; in the hardening checklist add "decide `cosec.authentication.revocation.fail-closed`: `true` answers `503` while revocations cannot be read, at the price of every authenticated request failing during a Redis outage". Mention it in Quick Start "Next steps".
- **Owner:** `guide/security-model.md#the-two-documented-exceptions`. **Size:** S. **When:** before RC1.

### UE-5 — S2 — A front-end developer cannot complete sign-in, refresh and logout from the package READMEs

- **Reader, journey:** J7.
- **Where:** `typescript/cosec-fetcher/README.md`, "Example" and "Logging out"; `typescript/cosec-react/README.md`, "Example"; `integration/clients.md` (no code at all).
- **What is wrong:** (a) the README never shows how the pair a login answers reaches the `TokenStorage`: `signIn` appears only in "Upgrading" (`tokenStorage.signIn(compositeToken)` is `TokenStorage.signIn`, `src/tokenStorage.ts:127`); (b) "Example" ends with "Provide a `TokenRefresher` to enable token storage, Bearer injection, and automatic refresh. Keep refresh requests on a separate unconfigured Fetcher", and the next code block, "Logging out", builds `CoSecTokenRefresher({ fetcher: api, endpoint })` and `CoSecTokenRevoker({ fetcher: api, … })` on the Fetcher it then configures, with no imports; (c) the React example imports `{ tokenManager, tokenStorage } from './tokenStorage'`, a file the README never defines. Contract facts (`isTrusted`, `401` only ends a session, rotation leeway) are correct and thorough; the wiring is what is missing.
- **Recommendation:** one complete example in the fetcher README: a `Fetcher` for the API, one `new Fetcher()` for refresh and logout, `CoSecConfigurer` with refresher and revoker, `cosec.applyTo(api)`, a `login()` function that posts credentials on a bare fetcher and calls `cosec.tokenStorage.signIn(pair)`, and `await cosec.tokenManager.logout()`; export `tokenStorage`/`tokenManager` from the same module and show it in the React README. Type-check the README examples in the package's tests (the package already runs vitest and the host test).
- **Owner:** the package READMEs for wiring; `integration/clients.md` for the wire. **Size:** S to M. **When:** before RC1 (the client ships with the RC).

### UE-6 — S2 — The Extending page's custom `Authenticator` recipe drops the starter's revocation settings and does not say so

- **Reader, journey:** J6; an operator who hardened `cosec.authentication.revocation.*` (J3).
- **Where:** `architecture/extending.md#authenticators`, the `cosecAuthenticator` recipe; also the table row of `#credentials-and-revocation`.
- **What is true:** `TokenAuthenticator(JwtTokenVerifier(algorithm), revocations)` leaves `revocationSkew = SubjectRevocation.DEFAULT_SKEW` and `acceptUnavailableRevocations = true` (`core/runtime/.../authn/TokenAuthenticator.kt:64-78`), where the starter passes `cosec.authentication.revocation.skew` and `fail-closed`. The page tells the reader the bean "replaces all of it"; it does not say which settings go with it. UA-9 is the code side (nothing to delegate to); this is the sentence an operator needs. Three more sentences on the same page, confirmed for A's E list: the `TokenStore` row says "The write side of revocation, used by logout" (UA-4: `revoke(tokenId, ttl)` takes the `jti`, and applications log out through `TokenRevoker`); "A Java application configures the starter or calls the builder" (UA-13: a Java caller cannot use the pipeline the builder returns).
- **Recommendation:** pass the properties in the sample (inject the starter's revocation properties or `AuthenticationProperties`), or add: "The recipe does not carry `cosec.authentication.revocation.skew` or `fail-closed`; pass them to `TokenAuthenticator` yourself or the defaults apply." Rewrite the `TokenStore` row to "written by `TokenRevoker`; call `TokenRevoker.revoke(accessToken)`, never the store". Replace "or calls the builder" by what a Java application can do.
- **Owner:** `architecture/extending.md`. **Size:** S. **When:** before RC1.

### UE-7 — S3 — The TypeScript client is not mentioned on any entry page and is on no reader path

- **Reader, journey:** J7.
- **Where:** `README.md`, `wiki/index.md`, `guide/introduction.md` (Modules table, "Where to go next"), `llms.txt`: no mention of `@ahoo-wang/cosec-fetcher` or `@ahoo-wang/cosec-react` (grep). `integration/clients.md` is linked from eight pages, none an entry page; it is in the sidebar as "Building a Client".
- **Recommendation:** a row in the Modules table ("a browser or Node client: `@ahoo-wang/cosec-fetcher`, `@ahoo-wang/cosec-react`, on npm, released with CoSec"), a seventh reader path ("Building a client of a CoSec application": Quick start → Building a client → the package README), a sentence in the README's Modules section and in `llms.txt` with the package names.
- **Owner:** `guide/introduction.md#where-to-go-next` (reader paths). **Size:** S. **When:** before RC1.

### UE-8 — S3 — Testing is the third section of the control-plane page, and no page shows a test of the application itself

- **Reader, journey:** J1 ("a test proves it"), J2.
- **Where:** `policies/testing.md` (title "Testing Policies", opening paragraph "change documents in production"); `#testing-decisions` starts at line 550 of 703.
- **What is true:** `PolicyTestKit` and `PipelineFixture` are documented well and are correct against the source (`PolicyTestKit.load/tests/check/evaluate`, `PipelineFixture.load/request/evaluate/securityContext/unreadable`). The Quick Start never links either; Simple mode and App link the kit, the developer path does not; the Introduction names the kit in one sentence. No page, skill or README mentions `WebTestClient`, `MockMvc` or `@SpringBootTest` (grep of `wiki/`, `skills/`, READMEs): nothing tests the filter order, the starter wiring, a handler's use of `SecurityContext`, or a `403` on the live route. Quick Start's own scenarios (`examples/quick-start/scenarios`, seven files) are exactly what a "Check it" step needs and are only linked to GitHub.
- **Recommendation:** (1) split the page: "Testing policies" (kit, `PipelineFixture`, CI, 150 lines) and "Changing documents safely" (control plane), or at least rename the sidebar entry and move "Policies in CI" first; (2) a Quick Start section "Prove it with a test": the `alice-cannot-delete` scenario and the three-line `PolicyTestKit` class; (3) a Spring-context test recipe once the facts exist: how a test gets Redis (or an in-memory store), a token, and a platform document.
- **Owner:** `policies/testing.md`. **Size:** M (split and link: S; recipe: M). **When:** link and split 8.0.x; the recipe 8.1.

### UE-9 — S3 — The operator's reading path skips Deployment and the Security model; the hardening checklist has one inbound link and omits what other pages call risks

- **Reader, journey:** J3.
- **Where:** `guide/introduction.md#where-to-go-next`, row "Running it in production": Configuration → Storage → Observability → Troubleshooting → Upgrading. The `**Next:**` chain (`configuration` → `storage` → `observability` → `troubleshooting` → `upgrading`) never reaches `operations/deployment.md`; `deployment.md` has no `**Next:**` at all. `security-model.md#hardening-checklist` is linked from `scenarios/gateway.md` only (grep); the Introduction ends "Everyone should read the Security model before going to production" with no anchor, the Quick Start's last bullet likewise.
- **What the checklist omits** (each a fact another page or report owns): Redis persistence, replication and backup (UB-16; `storage.md#redis-requirements` says the documents have "no TTL and no other copy" and asks only for `noeviction`); the image's JMX port (UB-2); `revocation.fail-closed` (UE-4); `RevocationReader.NONE` (it says "load the secret", not "do not leave NONE"); removing the Quick Start's demo token and runner (UE-10).
- **Recommendation:** path: Security model#hardening-checklist → Deployment → Configuration → Storage → Observability → Troubleshooting → Upgrading, with `**Next:**` lines to match; link the checklist from Deployment "Before the first request", Quick Start "Next steps", the README and the configuration page's first paragraph; add the five items.
- **Owner:** `guide/security-model.md#hardening-checklist`; the path is `guide/introduction.md#where-to-go-next`. **Size:** S. **When:** before RC1.

### UE-10 — S3 — The Quick Start ends with three demo artifacts and a Redis on every interface, and says to remove none

- **Reader, journey:** J1.
- **Where:** `guide/quick-start.md`: Prerequisites (`docker run -d --name redis -p 6379:6379 redis:8`), "Write a first policy" (`hello-and-token` permits `GET /token` to anyone), "Change a document later" (`platformToken` `ApplicationRunner`), "Next steps".
- **What is true:** `-p 6379:6379` publishes the port on every host interface (Docker's semantics), while the text says `localhost:6379`; the checklist says "Treat write access to Redis as access to every decision". Whether the container accepts remote clients is the image's protected-mode and not something the doc should rely on. The runner prints a `(platform)` administrator token on every start, marked "Demo only" in a code comment; "Next steps" says "Replace the demo `/token` endpoint" but not that the `/token` statement stays in the document and the runner stays in the class. `cosec.authorization.remote-ip.max-trusted-index` keeps its default `1`; Simple mode says "`0` if clients reach the service directly", the Quick Start (which serves directly on `:8080`) does not (UB-4 is the default's side).
- **Recommendation:** `-p 127.0.0.1:6379:6379` in the Quick Start and in `CONTRIBUTING`/`AGENTS.md` test instructions; end the Quick Start with a "Before you keep this code" list: delete `/token` and its statement, delete the `platformToken` runner, set `max-trusted-index`, read the hardening checklist. Put "Demo only" into the JSON too (a statement `name` such as `demo-token` would do).
- **Owner:** `guide/quick-start.md`. **Size:** S. **When:** before RC1.

### UE-11 — S3 — `language.md` says an unpublished document reads as empty; for the platform document it is `503`

- **Reader, journey:** J2, J3. (D handed this over; confirmed.)
- **Where:** `policies/language.md#documents` (line 28-29): "Every request reads the platform document … A document nobody published reads as empty."
- **What is true:** `PipelineBuilder` builds `DocumentSnapshotViews(source, platformRequired = true)`; an absent platform document is unresolved, not empty (`DocumentSnapshotViews`, `MissingPlatformException`), so every request is `503`. This is what `guide/quick-start.md#publish-the-platform-document`, `guide/security-model.md` and `llms.txt` say. Only app and tenant documents read as empty.
- **Recommendation:** "An app or tenant document nobody published reads as empty. An absent platform document does not: every request is `503` until one is published." Same edit in `zh/policies/language.md`.
- **Owner:** `guide/security-model.md#fail-closed`. **Size:** S. **When:** before RC1.

### UE-12 — S3 — The problem `type` URIs CoSec writes return 404, and the pages say each has its own section

- **Reader, journey:** J7, J3 (anyone who follows the `type` link of a response or a log).
- **Where:** `ProblemType.TYPE_PREFIX = "https://cosec.ahoo.me/problems/"` (`core/runtime/.../http/ProblemType.kt:43`); `operations/troubleshooting.md` line 11 and `integration/web.md` line 212 ("one per row of the table above"); `wiki/public/` holds only `CNAME`; `curl https://cosec.ahoo.me/problems/forbidden` returned **404** on 2026-11-18, `/operations/troubleshooting` 200.
- **Recommendation:** publish a page or a static redirect for each slug (`public/problems/<slug>.html` redirecting to `/operations/troubleshooting#<slug>`; the anchors already exist), and make `check:docs` list the slugs in `contract/` and require both. The `type` is a wire contract; the pages are not, so this is a docs-only fix.
- **Owner:** `integration/web.md#problem-bodies`. **Size:** S. **When:** 8.0.x (before GA is better: clients and logs carry the URI).

### UE-13 — S3 — The integration skill's "Minimal configuration" is not minimal and conflicts with the Quick Start; it also names another springdoc artifact than the wiki

- **Reader, journey:** J8.
- **Where:** `skills/cosec-integration/SKILL.md`, "Minimal configuration" and the module table.
- **What is true:** the block sets `cosec.authorization.scope.app-id: order-service` and `routes.location: classpath:cosec-routes.json`. `ScopeResolver` serves a host with an app only credentials declaring that app ("any other, or none, is `APP_MISMATCH`"), so the Quick Start's demo token (`TokenSubject(id, app = null)`) is `403 scope-rejected` on every request; a `routes.location` naming a file the project does not have fails startup, which the skill itself lists. The Quick Start sets neither. The skill explains both consequences later; the block an agent copies first does not. Also: the skill's `cosec-openapi` row says `org.springdoc:springdoc-openapi-starter-common`; `guide/introduction.md#modules` says `…-starter-webflux-api` or `-webmvc-api`. `CoSecServiceGeneratorEndpoint` reads the document `/v3/api-docs` serves, and the host build tests against the `-api` starter; I did not run it, but two pages give two answers.
- **Recommendation:** make the block the Quick Start's (`jwt.secret` only) and move `app-id`, `routes.location` and `max-trusted-index` to the sections that need them, each with its consequence on the same line; align the springdoc artifact with the Introduction after one test run.
- **Owner:** `guide/quick-start.md` (configuration) and `guide/introduction.md#modules`. **Size:** S. **When:** before RC1.

### UE-14 — S3 — `llms.txt` omits 11 of 33 pages, and the site serves no `llms-full.txt`

- **Reader, journey:** J8.
- **Where:** `llms.txt`. Not linked: `guide/model`, `guide/roadmap`, `operations/deployment`, and the scenarios `app`, `roles`, `spaces`, `service-account`, `tenant-owner`, `external-source`, `route-maps`, `gateway` (its "Scenarios" section lists two and points to the Introduction). `curl https://cosec.ahoo.me/llms-full.txt` is 404 (the drill's finding 13, `llms.txt` itself, is fixed: 200).
- **Why it matters:** the key-facts paragraph says actions come "from a route map" and links no page about route maps; an agent cannot reach the gateway image, readiness, or the trust-boundary operations page from `llms.txt` alone.
- **Recommendation:** add Deployment, Model, Route maps, Gateway topologies and one line naming the other scenarios; generate `llms-full.txt` from the pages in the build; add to `check:docs` "every sidebar page appears in `llms.txt`".
- **Owner:** `llms.txt`. **Size:** S (links), M (`llms-full.txt`). **When:** links before RC1, `llms-full.txt` 8.0.x.

### UE-15 — S3 — The Checks page's samples are WebFlux-only, leave names undefined, and give a `suspend` WebFlux handler no way to the context

- **Reader, journey:** J1 (servlet and coroutine variants).
- **Where:** `integration/checks.md#checking-a-permission` (the only full sample is `Mono<Map<…>>` over `exchange.getSecurityContext()!!`), `#object-level-rules` (`checker.checkBlocking(security, …)`, `throw AccessDeniedException()`).
- **What is true:** `checkMono` and `checkBlocking` exist with the shown signatures (`edge/webflux/.../AuthorizationCheckers.kt:32`, `edge/webmvc/.../AuthorizationCheckers.kt:31`). `security` is undefined in the second sample, `AccessDeniedException` is not a CoSec class, and a servlet reader never sees `checkBlocking` used with the `SecurityContext` parameter they were told about on the Web page. UA-7 is the missing API; the text for it is missing too.
- **Recommendation:** one sample per stack, side by side (WebFlux `Mono`, WebFlux `suspend` using `exchange`, MVC with a `SecurityContext` parameter and `checkBlocking`), each with its imports; replace the exception by `ResponseStatusException(HttpStatus.FORBIDDEN)`, as the resource-server page does.
- **Owner:** `integration/checks.md`. **Size:** S. **When:** before RC1.

### UE-16 — S3 — No Kotlin or YAML sample in the wiki is compiled or checked against the property classes

- **Reader, journey:** all.
- **Where:** `wiki/examples/` (124 files, all JSON), `DocumentationExamplesTest`; free text: 40 Kotlin, 11 YAML, 31 inline JSON and 20 bash blocks outside the upgrade pages (counts above).
- **Evidence it matters:** the hand check in section 5 found none that fails today, after two drills and #1654; the next signature change (UA-1's constructor default, UA-7's accessors) will. `check:docs` checks links, anchors, parity and removed names, not a sample.
- **Recommendation:** a `docs-samples` test source set: Kotlin samples as `.kt` files under `wiki/examples-kt/` included with `<<<` (same pattern as JSON), compiled by one module; YAML samples run through the starter's unknown-property check (`UnknownPropertiesTest` machinery) in a test; inline JSON documents either moved to `examples/` or validated by the codec.
- **Owner:** `wiki/AGENTS.md` ("Samples of a scenario page are files") extended to all pages. **Size:** M to L. **When:** 8.0.x for YAML and the Kotlin of the three journey pages, 8.1 for the rest.

### UE-17 — S3 — The user-facing Roadmap page contradicts itself and the release state

- **Reader, journey:** evaluator (J1 entry).
- **Where:** `guide/roadmap.md`. It says "Status is as of `main` on 2026-10-29" (today 2026-11-18); M1 "only the switch steps remain", M2 "only the starter wiring at the switch remains", M3 "only the registration in the starter at the switch remains" while M5 (the switch) is "Done"; M7 "`8.0.0-alpha.1`, `8.0.0-alpha.2` and `8.0.0-alpha.3` are published" while the release policy two screens down names `8.0.0-beta.3` as latest (`gh release list`: beta.3 on 2026-11-18, alpha.4 and beta.1 and beta.2 exist); "[Upgrading] will list every change … when 8.0 is assembled" in the future tense for a page of 1219 lines that exists; internal names (S1–S5, "round 1", RFC ids) the reader cannot use. The page is in the sidebar, in the first group.
- **Recommendation:** for RC1 replace the milestone table by three paragraphs: what 8.0 is, what the preview promises (nothing), what RC means; keep the per-PR tracker out of the site. If the table stays, update it in the same PR that moves a milestone (it says it does).
- **Owner:** `guide/roadmap.md` (maintainer-owned, per memory: shared docs are the coordinator's). **Size:** S. **When:** before RC1.

### UE-18 — S3 — CONTRIBUTING.md does not send a contributor to the rules that decide whether their pull request passes

- **Reader, journey:** contributor.
- **Where:** `CONTRIBUTING.md` (134 lines). It never mentions `AGENTS.md` ("Where a change has to land", the structure guards, Codecov), `wiki/AGENTS.md`, `pnpm check:docs`, `:conformance:check`, `:architecture:check`, or the mutation run; it names `./gradlew check` only. `.github/PULL_REQUEST_TEMPLATE.md` has "Docs updated (wiki EN/ZH, configuration reference)". `AGENTS.md` is introduced as "for agents and contributors **changing** CoSec", but a human reads CONTRIBUTING.
- **Recommendation:** a section "Where your change has to land" in CONTRIBUTING linking `AGENTS.md#where-a-change-has-to-land` and `wiki/AGENTS.md`, the three docs commands, and the architecture and conformance checks. Also: CONTRIBUTING says tests use "Hamcrest and FluentAssert", AGENTS "JUnit 5, MockK, FluentAssert".
- **Owner:** `CONTRIBUTING.md`. **Size:** S. **When:** 8.0.x.

### UE-19 — S3 — The right-hand outline lists H2 only, so most anchors a reader needs are invisible

- **Reader, journey:** J3, J4, J5 (long pages).
- **Where:** `wiki/.vitepress/config/en.ts`/`index.ts` set no `outline` (VitePress shows level 2 only); `troubleshooting.md` has 32 H3 (25 of them "Startup fails on …" under one H2), `upgrading.md` 12 H3 and many H4, `web.md` 6, `upgrading-previews.md` 1380 lines with 7 H2. Local search is on, which finds terms, not the structure.
- **Recommendation:** `themeConfig.outline: [2, 3]` (and `zh.ts`). One line, no content change.
- **Owner:** `.vitepress/config/`. **Size:** S. **When:** before RC1.

### UE-20 — S3 — Every install instruction for the TypeScript packages says `@beta`; from RC1 that installs the beta

- **Reader, journey:** J7.
- **Where:** both package READMEs and their zh twins, `integration/clients.md#coming-from-ahoo-wangfetcher-cosec` (en and zh), `typescript/AGENTS.md`: "Until CoSec 8.0 is released … install `@beta` … After the release, drop `@beta`".
- **What is true:** `typescript/AGENTS.md` and `.github/workflows/package-deploy.yml`: `-alpha.n` → `alpha`, `-beta.n` → `beta`, `-RCn` → `rc`. At `8.0.0-rc.1` the `beta` tag is still `8.0.0-beta.N`, so a reader who follows the page installs the older client against an RC server.
- **Recommendation:** say "install the tag of the release you run (`@rc`, `@beta`) or the exact version"; write the sentence once in `integration/clients.md` and link it from the READMEs; add the replace-at-GA edit to the release checklist.
- **Owner:** `integration/clients.md`. **Size:** S. **When:** before RC1.

### UE-21 — S4 — Skill naming and invocation: `cosec-custom-matcher` is the extension skill; `$name` is another tool's syntax

- **Where:** `skills/plugins.json`, `skills/cosec-custom-matcher/SKILL.md` ("CoSec 7.x custom action and condition matchers … have no 8.0 equivalent"), `skills/cosec-integration/SKILL.md` ("use `$cosec-custom-matcher`", "use `$cosec-policy-author`"), `skills/*/agents/openai.yaml`.
- **What is true:** there are no matchers in 8.0 (root `AGENTS.md`: "there are no matcher plugins"); the skill is about SPI beans. An agent or person searching skills for "extend" or "SPI" sees a name that says the opposite. The `$` prefix is the OpenAI agent syntax; Claude Code and others invoke by name.
- **Recommendation:** rename to `cosec-extending` (the page it mirrors is "Extending CoSec"), keep the description, say "invoke the `cosec-policy-author` skill" without a prefix.
- **Owner:** `skills/`. **Size:** S. **When:** 8.0.x (a rename of a published skill: after RC1 only with a note).

### UE-22 — S4 — Chinese polish

- **Where:** `zh/guide/quick-start.md:188` (an untranslated comment next to a translated one); 25 uses of `撤销` where `wiki/AGENTS.md` glossary rejects it for revoke (`吊销`) without saying what to use for undo/rollback; two of `凭据` (`zh/operations/upgrading.md`).
- **Recommendation:** translate the comment, extend the glossary row ("undo a publish: 撤回", for example), fix the two `凭据`; add the glossary's rejected words to `check:docs` so they cannot return. **Owner:** `wiki/AGENTS.md`. **Size:** S. **When:** 8.0.x.

### UE-23 — S4 — Upgrading between previews has no "start here", and the beta.3 section omits #1655's host changes

- **Where:** `operations/upgrading-previews.md` (1380 lines, 7 H2, `From 8.0.0-alpha.4` alone 730 lines).
- **What is true:** a deployment on alpha.1 must read every section upward. The `From 8.0.0-beta.3` section covers `PIP-26` and `PIP-27` but not what #1655 changed in the host: the `WARN` over `RevocationReader.NONE`, the unknown-property check before any bean, one startup message for every missing token store. None breaks a deployment; each changes the log an operator reads after the upgrade.
- **Recommendation:** a four-row table at the top: "On beta.2 read X; on beta.1 read X and Y; …". Add one line for #1655 in the beta.3 section. **Owner:** `operations/upgrading-previews.md`. **Size:** S. **When:** before RC1 (the page is deleted at GA, so keep this cheap).

### UE-24 — S4 — Preview-version history in pages that must "document 8.0, not what it replaced"; the site is built from `main` with no version

- **Where:** `operations/deployment.md` (lines 217-218 "8.0.0-alpha.1 refused … since alpha.2", 273 "(`8.0.0-alpha.1`, `linux/arm64` …)"), `operations/storage.md:448` ("A store upgraded from 8.0.0-alpha.2 needs it once"); `.github/workflows/deploy-wiki.yml` deploys on every push to `main` and nothing selects a version.
- **What is true:** `check:legacy` catches removed APIs, not preview history. After GA a reader of 8.0.1 sees `main`.
- **Recommendation:** move the three sentences into `upgrading-previews.md` (deleted at GA); decide how a released version's docs are kept (a tagged build under `/8.0/`) before 8.1 changes behaviour. **Owner:** `wiki/AGENTS.md`. **Size:** S (sentences), M (versioning). **When:** sentences before RC1, versioning 8.1.

### UE-25 — S4 — The editor-schema example points at `refs/heads/main`

- **Where:** `policies/language.md#json-schema`, the VS Code `json.schemas` URL.
- **What is true:** a user on an RC or 8.0.x reads the schema of `main`, which will describe 8.1 documents `8.0` rejects ("A document is closed").
- **Recommendation:** `…/refs/tags/v<version>/schema/…` and say "your version", or publish the schemas on the site per version. **Owner:** `policies/language.md#json-schema`. **Size:** S. **When:** 8.0.x.

### UE-26 — S4 — Contributor-facing indexes are stale

- **Where:** `wiki/AGENTS.md` "Structure" table (omits `operations/deployment` and `guide/roadmap`, which are in the sidebar and in `check:docs`' page count) and its "Page types" table (no type for `deployment`); `SECURITY.md` "8.0 (in development)" for the line a user is about to install as an RC.
- **Recommendation:** add the two pages and their types; reword the SECURITY row at RC1 ("8.0 release candidate"). **Owner:** `wiki/AGENTS.md`, `SECURITY.md`. **Size:** S. **When:** before RC1.

## 8. The one-line table

| id | severity | defect | pages touched | size | when |
|---|---|---|---|---|---|
| UE-1 | S1 | Policy-author skill's minimal platform document allows everything to every authenticated caller | `skills/cosec-policy-author/SKILL.md`, `policies/language.md` | S | before RC1 |
| UE-2 | S1 | Skill says checks set `resource.attributes.*`; they set none, a DENY on it never applies | `skills/cosec-policy-author/SKILL.md` | S | before RC1 |
| UE-3 | S2 | README platform document never permits the control API, so it can never be changed | `README.md`, `README.zh-CN.md`, `guide/quick-start.md` | S | before RC1 |
| UE-4 | S2 | Front-door pages say revocation always checked and fail closed; two documented exceptions omitted | `index.md`, `README*.md`, `llms.txt`, `guide/introduction.md`, `guide/security-model.md` | S | before RC1 |
| UE-5 | S2 | Front-end READMEs lack sign-in; examples undefined or contradict their own advice | `typescript/cosec-fetcher/README.md`, `typescript/cosec-react/README.md` | S-M | before RC1 |
| UE-6 | S2 | Extending's custom Authenticator recipe drops revocation settings; `TokenStore` "used by logout" | `architecture/extending.md` | S | before RC1 |
| UE-7 | S3 | TypeScript client named on no entry page and on no reader path | `README.md`, `guide/introduction.md`, `llms.txt`, `index.md` | S | before RC1 |
| UE-8 | S3 | Test kit buried in control-plane page; no test of the application itself documented | `policies/testing.md`, `guide/quick-start.md` | M | 8.0.x |
| UE-9 | S3 | Operator path skips Deployment and Security model; hardening checklist linked once, incomplete | `guide/introduction.md`, `guide/security-model.md`, `operations/deployment.md` | S | before RC1 |
| UE-10 | S3 | Quick Start leaves demo token, admin-token runner, Redis on all interfaces, removes none | `guide/quick-start.md` | S | before RC1 |
| UE-11 | S3 | `language.md` says unpublished document reads as empty; absent platform document is 503 | `policies/language.md`, `zh/policies/language.md` | S | before RC1 |
| UE-12 | S3 | Problem `type` URIs return 404 though pages say each slug has a section | `integration/web.md`, `operations/troubleshooting.md`, `wiki/public` | S | 8.0.x |
| UE-13 | S3 | Integration skill's "minimal" config sets app and route map; names another springdoc artifact | `skills/cosec-integration/SKILL.md`, `guide/introduction.md` | S | before RC1 |
| UE-14 | S3 | `llms.txt` omits 11 pages including Deployment; no `llms-full.txt` | `llms.txt`, `wiki/.vitepress` | S-M | before RC1 |
| UE-15 | S3 | Checks samples WebFlux-only, undefined names, no `suspend` WebFlux or MVC sample | `integration/checks.md` | S | before RC1 |
| UE-16 | S3 | No Kotlin or YAML wiki sample is compiled or checked against properties | `wiki/examples`, `wiki/AGENTS.md`, `test/conformance` | M-L | 8.0.x |
| UE-17 | S3 | Roadmap page contradicts itself: stale date, milestones, alpha.3 as latest | `guide/roadmap.md` | S | before RC1 |
| UE-18 | S3 | CONTRIBUTING never points to `AGENTS.md`'s landing table or the docs and architecture checks | `CONTRIBUTING.md` | S | 8.0.x |
| UE-19 | S3 | Outline shows H2 only; 32 troubleshooting and other H3 anchors invisible | `wiki/.vitepress/config/en.ts`, `zh.ts` | S | before RC1 |
| UE-20 | S3 | All TypeScript install text says `@beta`; from RC1 that installs the beta | package READMEs, `integration/clients.md` | S | before RC1 |
| UE-21 | S4 | Extension skill named "custom-matcher"; `$name` invocation is another tool's | `skills/*`, `skills/plugins.json` | S | 8.0.x |
| UE-22 | S4 | zh: untranslated comment, 25 `撤销`, two `凭据` against the glossary | `zh/guide/quick-start.md`, `wiki/AGENTS.md` | S | 8.0.x |
| UE-23 | S4 | Preview upgrade page has no start-here table; omits #1655 host changes | `operations/upgrading-previews.md` | S | before RC1 |
| UE-24 | S4 | Preview-version history in deployment and storage; site unversioned | `operations/deployment.md`, `operations/storage.md` | S-M | before RC1 |
| UE-25 | S4 | Editor schema URL pinned to `refs/heads/main` | `policies/language.md` | S | 8.0.x |
| UE-26 | S4 | `wiki/AGENTS.md` tables omit Deployment and Roadmap; SECURITY says "in development" | `wiki/AGENTS.md`, `SECURITY.md` | S | before RC1 |

## 9. What the documentation does well, and must not be lost in a rewrite

- **The Quick Start is a real tutorial.** Prerequisites, one command per step, the expected status and problem type after each `curl`, the denied cases explained ("a client never learns whether a DENY exists"), `503` before the first document explained where the reader meets it, and every statement it makes about a decision is a scenario file that runs on every build.
- **One fact, one home, enforced.** `wiki/AGENTS.md`'s table is followed: the status mapping, the exceptions to fail-closed, the trust boundaries and the configuration reference each live once, and the other pages link. The two documented exceptions (revocation unreadable, rate limits) are stated exactly where they belong, with the setting that changes them.
- **Honest limits stated next to the feature**: gateway mode's "only the gateway may reach it" in bold with the exact `WARN`; "a check keeps permitting during the request"; "the kit applies no route map"; the `0.0.0` placeholder on npm.
- **Reference pages that are accurate.** Every default in `configuration.md` equals the code (B); every status and problem type is in one table and the TypeScript client is tested against `contract/`; the claims table is held to `contract/token/claims.json` by `check:docs`.
- **Troubleshooting by symptom**, with the problem-type slugs as anchors, the audit-event fields to read, and a section per startup failure that quotes the exact message.
- **Upgrading**: a checklist with anchors, a short route for a service with no data, a rollback section, and the preview notes moved to their own page after the second drill.
- **Skills**: the integration and troubleshoot skills carry the "never suggest" rules (never `warn` to silence a Redis that evicts, never turn revalidation off) and the verification list; keep that tone when UE-1, UE-2 and UE-13 are fixed.
- **en/zh parity at the paragraph level**, not only at the heading level: the mirror is faithful, so fixing a sentence in English is fixing it twice.
