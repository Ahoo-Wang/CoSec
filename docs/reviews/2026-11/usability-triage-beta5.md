# Usability findings not fixed: triage for beta.5, 8.1 and drop

Base: `origin/main` at `dd9b151d` (8.0.0-beta.4 release commit), read 2026-11-19. Nothing was built or run: every "still true" below is a read of the file named, and every FIXED is a read of the code or page on `main` plus the PR that carries the id. Findings are classed by the principle in the brief (breaking after the RC → BETA5; purely additive → 8.1; docs only → DOCS; else DROP). A finding fixed in part is counted by what is still open, and the fixed part is named in its row; "remainders" of FIXED findings are in section 7.

## 1. Counts

| Report | Findings | FIXED | STALE | BETA5 | 8.1 | DOCS | DROP |
|---|---|---|---|---|---|---|---|
| A developer surface (UA) | 17 | 8 | 0 | 1 | 7 | 1 | 0 |
| B starter and operations (UB) | 25 | 13 | 0 | 0 | 7 | 4 | 1 |
| C policy authors (UC) | 22 | 7 | 0 | 1 | 12 | 2 | 0 |
| D model (UD) | 21 | 14 | 0 | 0 | 0 | 7 | 0 |
| E documentation (UE) | 26 | 19 | 0 | 0 | 1 | 6 | 0 |
| **Total** | **111** | **61** | **0** | **2** | **27** | **20** | **1** |

Only 2 findings are BETA5 on their own. Section 3 lists 5 more places where a *remainder* of a fixed or open finding would be breaking if done after the RC; the maintainer should decide those (the recommendation is not to do them).

## 2. BETA5

| id | sev | what is wrong | evidence on `main` | why BETA5 | module(s) | size | maintainer decision? |
|---|---|---|---|---|---|---|---|
| UA-7 | S3 | The same name reads a caller's context four ways: `ContextView.getSecurityContext()` throws, `ServerWebExchange.getSecurityContext()` returns null, and `ContextView.getSecurityContextOrEmpty()` returns null, not an empty value; its throw says only "SecurityContext is null." | `edge/webflux/.../ReactiveSecurityContexts.kt:29,38`, `ServerWebExchanges.kt:43` | Renaming (`requiredSecurityContext()` for the throwing form, `securityContext()` for the nullable one) removes lines from `webflux.api`. **Both ways:** rename now (BETA5) or only add the missing pieces later (8.1: a reactive `HandlerMethodArgumentResolver`, `suspend currentSecurityContext()`, `ServerWebExchange.requiredSecurityContext()`, a message in the style of `UNBOUND`). **Recommend:** rename the two `ContextView` readers and give the throw a message in beta.5 (the only breaking part), add the rest in 8.1. | `cosec-webflux` (+ docs `integration/web.md`, `checks.md`, `webflux.api`) | S | Not an SPI, format or semantics change; a rename of stable API, so the maintainer should say yes. |
| UC-14 | S3 | `diff` lists changed entry ids only and `sections` is a boolean ("something else differs"), so a reviewer must fetch both revisions to see a DENY become an ALLOW. | `core/control/.../api/DocumentDiff.kt:37-41` (`sections: Boolean`), `ControlResponseJson.kt:86`, `wiki/policies/testing.md:52` | Turning `sections` into a list of names changes a public data class (`control.api`) and the control API response. **Both ways:** replace the boolean by a list (BETA5), or keep it and add `sectionNames` plus per-entry before/after digests (8.1, additive, leaves a vestigial boolean). **Recommend:** replace (beta.5); the per-entry digests are additive and can follow. | `cosec-control` (`DocumentDiff`, `ControlResponseJson`), `wiki/policies/testing.md` en+zh | S–M | Wire shape of a documented control-API response, not an SPI or the document format; ask first because the control API is a published contract. |

## 3. Remainders that would be breaking if done after the RC (decision candidates, not counted)

| from | what | contract it would change | recommendation |
|---|---|---|---|
| UD-5 (FIXED as docs, #1664) | `resource` omitted means every resource; making it required (roadmap says 9.0) | document format and authorization semantics (an existing document stops compiling) | Roadmap puts it at 9.0, but under the new principle it is a beta.5 or never choice. Recommend: do not require it; add a compile WARNING for an ALLOW without `resource` (additive, 8.1). Maintainer decision (format). |
| UB-4 (FIXED by a WARN, #1663) | `max-trusted-index` default stays `1`, unsafe on a host reached directly | a `cosec.*` default (authorization semantics) | Keep `1` (the report's own trade-off); a changed default is breaking after RC. Maintainer decision. |
| UB-12 (DROP below) | `cosec.pipeline.authentication-timeout` → `cosec.authentication.*`, other spelling renames | `cosec.*` property names | Decision of 2026-11-19 was "property names do not change". If one rename is ever wanted, that is the one. |
| UD-18, UD-21 (DOCS below) | `boundary` boolean/id, `via`/`binding`/`cosec.binding.id`, `revision`/`version`/`at` renames | format member names, emitted names | Roadmap says 9.0. Recommend: document the pairs (DOCS), never rename before 9.0. |
| UA-16 (8.1 below) | A failed check reports `Cause.SourceUnavailable(PLATFORM)`; a new `Cause.CheckFailed` would change the cause a failing check carries | a value of the decision cause (also a `cause` metric tag if a check were ever metered) | Additive member; I judged it 8.1, but if the maintainer reads "a value CoSec emits" strictly it is BETA5 (S, `model.api`). |

## 4. 8.1 (purely additive)

| id | sev | what is wrong | evidence | what would be added | module(s) | size / decision |
|---|---|---|---|---|---|---|
| UC-4 | S2 | The author sees shadow counts only, after the window closes; the 20 samples per outcome are collected but never served. | `ShadowWindows.look` sums counts; no `ControlPlane` route for samples (`core/control`) | `GET /changes/{id}/shadow` (live counts + samples, redacted), `analyze` with a candidate change | control, runtime | M; privacy review of samples (maintainer) |
| UC-8 | S2 | A failing scenario prints decided/expected only, no trace; the kit's `via` shape differs from `explain`'s; no coverage of unexercised statements. | `test/testkit/.../EngineExpectations.kt:76-78` | Trace table in the failure message, kit accepts both `by` shapes (the kit is experimental, `explain` unchanged), `PolicyTestKit.coverage()` | testkit | M |
| UC-9 | S2 | `explain`/`simulate` drop `expired` and `unresolved` of the trace and say nothing on why a binding was not a candidate. | `ControlResponseJson.trace` writes `redacted`, `candidates` only | `expired`, `unresolved` (policy ids), `notCandidates` summary in the response | control, engine (trace) | M |
| UC-10 | S2 | Statements that can never match (contradictory `scope.space`, `resource.type` vs pattern) compile and publish silently. | `IndexKeys.intersect` result unused by `PolicyCompiler.statement`; no "never match" finding in `core/control` | `COMPILE` WARNING findings | document, control | M |
| UC-11 | S2 | Validating documents in CI needs a Kotlin program; the three checkers check different things. | `cosec-migrate` commands: no `check` (`MigrateMain.kt:68`) | `cosec-migrate check` command; kit `load` runs `checkReferences` | migrate, testkit | M; new CLI command (CLI is the tool's contract, additive) |
| UA-9 | S3 | An own `Authenticator` bean replaces the starter's, with its revocation settings, and cannot delegate to it. | `CoSecPipelineAutoConfiguration.kt:154` (`cosecAuthenticator`, no exposed built-in) | A `cosecBuiltInAuthenticator` bean or holder to delegate to. The recipe now keeps the settings (docs, #1664). | spring-boot | S |
| UA-11 | S3 | Refused and unavailable CoSec exceptions share one parent, and the correct mapping `RouteFailure.typeOf` is internal. | `extensions/login/.../route/RouteFailure.kt:37` (`internal object`) | A public failure-to-`ProblemType` mapping, optional `CoSecRefusedException`/`CoSecUnavailableException` | login or runtime | S |
| UA-12 | S3 | A pipeline with API keys and no membership provider builds, then answers 503 to every key. | `PipelineBuilder.kt:85-86` KDoc names tokens only; no build-time check | KDoc fix, a build-time refusal | runtime | S |
| UA-13 | S3 | A Java caller cannot call most of the API (suspend), and `ServletRequests`/`ReactiveSecurityContexts` readers lack `@JvmStatic`, `TokenSubject`/`PipelineFixture.Principal` lack `@JvmOverloads`. | `edge/webmvc/.../ServletRequests.kt:29`, `ReactiveSecurityContexts.kt:26` (objects, no `@JvmStatic`); `extending.md:24` already says what Java can call | `@JvmStatic`, `@JvmOverloads` (additive ABI lines) | webmvc, webflux, login, testkit | S |
| UB-6 | S3 | A wrong value stops startup but the message names a Kotlin parameter, six control-plane conditions all say `Invalid change options.` | `core/control/.../lifecycle/ChangeOptions.kt:50` | A property-naming `Validator` or rethrow in the starter; distinct control-plane messages | spring-boot, control | M |
| UB-7 | S3 | IDE metadata has no defaults (1 of 94 entries has one) and nothing ties `configuration.md` to the code. | `host/spring-boot/src/test/resources/configuration-metadata.json` | `additional-spring-configuration-metadata` with defaults, a test against `configuration.md` | spring-boot (build, tests) | M |
| UB-9 | S3 | A present module or written property silently does nothing (Kafka without `KafkaOperations`, gateway mode with `snapshot.*`). | `CoSecPipelineIntegrationAutoConfiguration.kt:143` (`@ConditionalOnBean`), no message | WARN (not failure: failing would break a host that starts today) | spring-boot | M |
| UB-15 | S4 | `compositeAuthentication` is `@Primary` with no back-off; `tokenCompositeAuthentication` has no back-off. | `CoSecAuthenticationAutoConfiguration.kt:61-63`, `CoSecJwtAutoConfiguration.kt:186-188` | `@ConditionalOnMissingBean` on both, or a table of replaceable beans in `extending.md` | spring-boot | S |
| UB-18 | S3 | The control plane and login service have no deployable artifact; the gateway topology ends at the first `cosec-migrate publish`. | `topologies.md:138` states it ("No CoSec image runs the control plane"); no `k8s/overlays/control-plane` | A worked example/overlay (8.0.x or 8.1), an image later (roadmap: 8.1) | k8s, wiki/examples | M (example), L (image) |
| UB-19 | S3 | `gateway` mode rests on the network alone. The NetworkPolicy example now exists (`k8s/examples/gateway-mode-service-networkpolicy.yaml`, `topologies.md:73`); the header is still unsigned. | `IdentityCodec` (no MAC) | Optional HMAC over `CoSec-Identity` (roadmap: 8.1) | document (identity), runtime, gateway | L; **maintainer: `CoSec-Identity` format member, ask first** |
| UB-20 | S3 | One HMAC secret both signs and verifies and has no rotation path. Docs now say a verifier's secret is an issuer's (`topologies.md:30-34`). | `cosecTokenAlgorithm`, one `Algorithm` | `cosec.jwt.previous-secrets` (verify-only) or the 8.1 identity-provider RFC | jwt, spring-boot | M; new property (additive) |
| UA-15 | S4 | `Outcome` encodes proceed/refuse as nullable `problem`, `security`, so callers write `security!!`. | `core/runtime/.../http/Outcome.kt:36-60` | `requiredSecurity()` with a message; a sealed type is allowed any time (`@ExperimentalCoSecApi`) | runtime | S |
| UA-16 | S4 | A check that throws reports `SourceUnavailable(PLATFORM)`, a platform document that could not be read. | `AuthorizationChecker.kt:146,149` | `Cause.CheckFailed` (see section 3: strict reading makes it BETA5) | model, runtime | S |
| UA-17 | S4 | `AuthenticatedPrincipal(id, tenant, null)` reads as "no roles", means "ask the provider". | `core/spi/.../authn/Authenticator.kt:241` (KDoc names the provider) | Named factories `withClaims`/`fromProvider` | spi | S |
| UC-12 | S3 | Errors: first decode error only, operation errors without index, engine `require` errors at path `$`. | `PolicyDocumentCompiler.kt:148` (`CompileError("$", …)`) | `operations[i]` prefix, `$.bindings[i]` paths, collect all | document, control | M |
| UC-13 | S3 | Common mistakes (`"resource":"*"`, unknown operator/attribute, swapped `in`, number literal) say what is wrong, not what to write. | `ConditionJson.kt:53`, `ResourcePattern.kt:252` | Better messages, "did you mean" | document, engine | S |
| UC-17 | S3 | `convert` prints bare rule ids (`semantic change C1b: 14 place(s)`). | `ConvertCommand.kt:59` | One sentence per id, first locations, pointer to the report | migrate | S |
| UC-18 | S3 | Race refusals (`is staged by change X`, `is at revision N`, `is DRAFT`) give no next step. | `Staging.kt:219` | Append next step and who may act | control | S |
| UC-19 | S3 | From a production 403 to an `explain`/scenario takes hand-assembly. | decision event has revisions but no request shape | `POST /explain {requestId}` and a shared request shape (roadmap: 8.1) | control, runtime | M |
| UC-20 | S3 | A rate-limit rule without `key` is one global bucket and compiles without comment; per-second maths. | `rate-limiting.md:90-112` documents it; no finding | WARNING for a keyless rule; a count+window quota would be a format member (8.1, **maintainer**) | control/document | S |
| UC-21 | S4 | App-id message omits `/`, the character most often refused. | `EntryChecks.kt:113` | One word in the message | control | S |
| UB-8 (rest) | S3 | The one documented fail-open (revocation `fail-closed=false`) has no health signal. (The `problem` tag is fixed, #1663.) | no `cosecRevocation` indicator in `host/spring-boot` | A health indicator, outside readiness | spring-boot | S |
| UE-16 | S3 | No Kotlin or YAML sample in the wiki is compiled or checked against the property classes. | `wiki/examples/` JSON only; `ShippedConfigurationTest` covers the k8s file only | A docs-samples source set; YAML through the unknown-key check | wiki, conformance | M–L |

## 5. DOCS (one docs PR; none needs code)

| id | sev | what is wrong | evidence | what to write | size |
|---|---|---|---|---|---|
| UB-11 | S3 | No page says who belongs in `prewarm-tenants`/`prewarm-apps` or that `cold-load-timeout` must fit `pipeline.timeout`. | `deployment.md:157` defines the lists only | One paragraph (the check is 8.1, a WARN at startup) | S |
| UB-24 | S3 | Scaling is in counts, not bytes of document; no symptom → knob for OOM. | `deployment.md:252-291` sizes the process only | Measured heap per document, an OOM row in `troubleshooting.md` (a size gauge is 8.1) | M |
| UB-13 | S4 | Troubleshooting and the skill miss several startup messages (`STA-5`, shadow `report-interval`, kafka `delivery`, client-address family). | `troubleshooting.md` has no `STA-5`; `skills/cosec-troubleshoot/SKILL.md` has no client-address entry (the ApiKeyReader quote is now right, line 826) | A row each, one skill entry | S |
| UB-25 | S4 | The gateway image's tags (`main`, `7.x`, `8.0` float) and "pin a version or digest" are not said. | `deployment.md:14-18` lists registries only | Two sentences | S |
| UC-15 | S3 | Two forms for permissions and for `space`; pages do not say which to prefer. | `role-permissions.md` has no "Choosing a form" | One paragraph, `spaced` at the first example | S |
| UC-16 | S3 | `by` shows derived names (`perm:order/order.ship`, `role:clerk@order:order.ship`, statement index); five spellings of allow/permit. | no "Names" table in `security-model.md` | A Names table; say `name` is what to pin. (A `source` field is 8.1.) | S |
| UA-14 | S4 | `TokenSubject` puts `app` before `tenant` and `TokenSubject("alice","acme")` compiles to the wrong tenant. | `extensions/login/.../TokenSubject.kt:25-34` | Do not reorder (silent swap); named arguments in every KDoc and sample | S |
| UD-12 | S3 | "attributes" names five things; the platform `attributes` section is external declarations. | no "external attribute declarations" in `wiki/` | Name it so in the pages (alias `externalAttributes` is a format change, 8.1, ask) | S |
| UD-15 | S3 | No scenario sets up several tenants (tenant A's binding not reaching B, a boundary). | `wiki/scenarios/`, `wiki/examples/` have none | A "Several tenants" scenario with runnable scenario files | M |
| UD-17 | S3 | Id grammar in five places, special values (`(0)`×2, `(platform)`, `""`, `(default)`, `*`) in none. | no table in `language.md` | One table, linked from the glossary | S |
| UD-18 | S4 | `boundary` is a boolean on a policy and an id on a tenant. | `language.md` | Note both uses; rename never before 9.0 (section 3) | S |
| UD-19 | S4 | "Platform" names three things. | `concepts.md:71` covers only the tenant | Glossary names all three | S |
| UD-20 | S4 | "Scope" is overloaded. | `concepts.md:99` | Say "a binding's app and space" | S |
| UD-21 | S4 | One concept, several names in output (`via`/`binding`, `revision`/`version`/`at`, service/namespace, guard). | no pair table | Document each pair once; no rename (section 3) | S |
| UE-8 | S3 | Testing is the 10th H2 of `testing.md` ("Testing Policies" opens on changing documents); the Quick Start never links the kit. | `testing.md` headings; `quick-start.md` has no kit link. (A test of the application itself exists now, #1688.) | Reorder or split, add "Prove it with a test" | S–M |
| UE-12 | S3 | The `type` URIs written in problem bodies (`https://cosec.ahoo.me/problems/<slug>`) are 404; pages say each has its own section. | `wiki/public/` holds only `CNAME` | Static redirect per slug to `troubleshooting#<slug>`, a `check:docs` rule (the URI is a wire contract and stays) | S |
| UE-18 | S3 | CONTRIBUTING does not send a contributor to `AGENTS.md` "Where a change has to land", the checks, Codecov. | `CONTRIBUTING.md` only names `typescript/AGENTS.md` | A section with the links | S |
| UE-21 | S4 | The extension skill is called `cosec-custom-matcher` (no matchers in 8.0) and invoked with `$name`. | `skills/` (4 skills, still that name) | Rename to `cosec-extending`; a published skill name, so say so in the release notes | S |
| UE-22 | S4 | Chinese polish: 27 `撤销` where the glossary rejects it, an untranslated comment, two `凭据`. | `wiki/zh` count | Fix and extend the glossary row | S |
| UE-25 | S4 | The editor-schema example points at `refs/heads/main`. | `language.md:704` | Use the tag of the release | S |

## 6. DROP

| id | sev | what is wrong | evidence | reason |
|---|---|---|---|---|
| UB-12 | S4 | The same concept has several property spellings (`pipeline.authentication-timeout` vs `authorization.*.timeout`, two identity `max-size`, two shadow groups). | `configuration.md:116,123,159` | Fixing it is a property rename (breaking), and the maintainer decided property names do not change; the cross-links the report asks for fit in the `configuration.md` pass of section 5 if wanted. |

## 7. Remainders of FIXED findings (open, not counted above)

| from | rest | class |
|---|---|---|
| UD-1 | a `MembershipProvider` overload that answers per app (roadmap) | 8.1, additive SPI overload (maintainer: SPI) |
| UB-16 | startup read of `INFO persistence` with `persistence-check: warn/fail/off` | 8.1, new property |
| UB-14 | none | — |
| UB-8 | `cosecRevocation` health indicator | in section 4 |
| UE-14 | `llms-full.txt` is not served | DROP (cheap, not a contract) |
| UD-8 | `AuditPrincipal.roles` KDoc ("roles in the request's space"); `api/audit/AuditEvent.kt:106` | DOCS, 1 line, with the section 5 PR |
| UD-9 | claims whose space is reserved are not listed in the trace's `malformed` | DROP (trace only; applies nowhere either way) |
| UD-10 | the zh page does not pick a separate term for the binding's subject (not checked) | DOCS with UE-22 |
| UE-26 | `SECURITY.md` row "8.0 (pre-release)" to reword at RC1 | release checklist, not a finding |

## 8. FIXED (61): what was wrong and where it landed

| id | sev | what was wrong | fixed by |
|---|---|---|---|
| UA-1 | S1 | `TokenAuthenticator` accepted revoked tokens unless told otherwise | #1662 |
| UA-2 | S1 | the refresh hook's KDoc promised the opposite of the default | #1662 |
| UA-3 | S2 | an own transport could not end its requests | #1676 (`Outcome.close()`) |
| UA-4 | S2 | logout through `TokenStore` revoked nothing | #1662 |
| UA-5 | S2 | a misused check looked like "no policy permits" | #1676 (bounded WARN) |
| UA-6 | S3 | `Algorithm.none()` accepted | #1662 |
| UA-8 | S3 | request `Principal` differed between stacks | #1676 (`PIP-28`) |
| UA-10 | S3 | pipeline builder had no rate limiter; kit could not test 429 | #1676 |
| UB-1 | S1 | a switch present but not `true` silently turned CoSec off | #1663 |
| UB-2 | S1 | gateway image JMX port on every interface | #1674 |
| UB-3 | S2 | waiting for the platform document said nothing | #1663 |
| UB-4 | S3 | `max-trusted-index` default unsafe when direct | #1663 (WARN; default kept, section 3) |
| UB-5 | S3 | startup did not say what CoSec is configured as | #1663 |
| UB-8 | S3 | decision timer could not tell 401 from 403 | #1663 (`problem` tag) |
| UB-10 | S3 | own controllers outside CoSec on a gateway host | #1663 |
| UB-14 | S4 | k8s sample opened CORS to `*` and kept a demo route | `k8s/base/application.yaml` (origin `app.example.com`, route marked DEMO) |
| UB-16 | S1 | no page asked for Redis persistence/backup | #1668 (`storage.md`) |
| UB-17 | S3 | no topologies page | #1668 (`operations/topologies.md`) |
| UB-21 | S3 | image defaults lost under a mounted file | #1674 |
| UB-22 | S3 | shipped config never tested against the starter | `ShippedConfigurationTest` (#1674), #1683 |
| UB-23 | S3 | no upgrade/rollback page for a fleet | #1668 (`upgrading.md#upgrading-a-running-deployment`) |
| UC-1 | S1 | an `http:` DENY/limit stops applying once a route is mapped | #1680, #1684 |
| UC-2 | S1 | `MERGE` kept what the file left out, silently | #1675 |
| UC-3 | S1 | non-ASCII `ignoreCase` DENY missed spellings | #1665 (`DOC-9`) |
| UC-5 | S2 | `REPLACE` last-writer-wins | #1675 |
| UC-6 | S2 | a platform change could lock out the control API | #1675 |
| UC-7 | S2 | the kit could pass against documents that never loaded | #1680 |
| UC-22 | S4 | generated catalogs not `spaced`, ids are the operations' | `role-permissions.md:336-340` states both |
| UD-1 | S1 | a provider's roles are app-blind; docs said otherwise | #1664 (docs, KDoc) |
| UD-2 | S1 | `resource.attributes.*` compiles, is never set | #1665 (`DOC-10`) |
| UD-3 | S1 | anonymous principal `(0)` in a binding | #1665 (`DOC-11`) |
| UD-4 | S2 | docs described a client-declared app | #1664 |
| UD-5 | S2 | omitted `resource` means every resource | #1664 (docs, `language.md:131`, example `no-resource`); section 3 |
| UD-6 | S2 | glossary missed central concepts | #1668 (`concepts.md`) |
| UD-7 | S3 | "allow"/"explicit deny" meant different things | #1682 |
| UD-8 | S3 | `roles` meant every role or one space's | `web.md:283,676`; KDoc in section 7 |
| UD-9 | S3 | default space spelled two ways | `concepts.md:97`, `Ids.kt:83` |
| UD-10 | S3 | "subject" had two meanings | `concepts.md:58-62` (en) |
| UD-11 | S3 | credential method named two ways | #1682 |
| UD-13 | S3 | example set `spaced: true` where it did nothing | `role-permissions.md:189` is short form now |
| UD-14 | S3 | model diagrams drew fields the code lacks | `model.md:91-140` has Boundary, Obligation |
| UD-16 | S3 | role condition in platform document reaches every tenant | `simple-mode.md:75-78` |
| UE-1 | S1 | skill's minimal platform document allowed everything | #1664 |
| UE-2 | S1 | skill said checks set `resource.attributes.*` | #1664 |
| UE-3 | S2 | README platform document could never change | #1664 |
| UE-4 | S2 | front door said revocation always checked | #1664 |
| UE-5 | S2 | front-end developer could not finish sign-in from READMEs | #1668 |
| UE-6 | S2 | `Authenticator` recipe dropped revocation settings | #1664 (`extending.md`) |
| UE-7 | S3 | TypeScript client on no entry page | `README.md`, `introduction.md`, `llms.txt` (not `wiki/index.md`) |
| UE-9 | S3 | operator path skipped Deployment and hardening checklist | `introduction.md:196` |
| UE-10 | S3 | Quick Start left demo artifacts, Redis on every interface | `quick-start.md:19,82` (`127.0.0.1`, "demo document") |
| UE-11 | S3 | `language.md` said unpublished platform document reads empty | `language.md:28-30` |
| UE-13 | S3 | skill's minimal configuration not minimal | skill table lines 69-70 name each consequence; springdoc artifact aligned |
| UE-14 | S3 | `llms.txt` omitted 11 pages | all 13 checked pages linked now (`llms-full.txt`: section 7) |
| UE-15 | S3 | Checks samples WebFlux-only | #1668 (`checks.md`, `ResponseStatusException`) |
| UE-17 | S3 | user Roadmap page contradicted the release state | `guide/roadmap.md:54` current |
| UE-19 | S3 | outline listed H2 only | `en.ts:92`, `zh.ts:92` `outline: [2, 3]` |
| UE-20 | S3 | install said `@beta` only | `clients.md:230-234` says tag per release |
| UE-23 | S4 | no "start here" in previews page | `upgrading-previews.md` "Start here" |
| UE-24 | S4 | preview history inside pages that document 8.0 | no `alpha` left in `deployment.md`, `storage.md` |
| UE-26 | S4 | contributor indexes stale | `wiki/AGENTS.md:31-50` lists both pages |

## 9. Not decided, and why

- **FIXED means read, not tested.** For findings the roadmap lists as the 32 blocking ones I trusted PR titles and bodies plus a code or docs read where cheap; I did not re-run any guard. Findings "fixed in passing" (UB-14, UC-22, UD-8/9/10/13/14/16, UE-7/9/10/11/13/14/15/17/19/20/23/24/26) I confirmed only on the one line named; the zh twins were not compared.
- **UA-16 BETA5 or 8.1.** Depends on whether a check's cause is something operators match on; I could not tell whether checks emit events or metrics.
- **UC-14 and UA-7 "replace or add".** I recommended replacing (beta.5) for both, which leaves one breaking line each; if the maintainer prefers to keep the current names, both become 8.1 additions and the BETA5 count falls to 0.
- **UD-5, UB-4, UB-12, UD-18/21** (section 3) are the maintainer's: they are format, default or naming choices.
- **UB-9 failing instead of warning** would break hosts that start today; I classed only the WARN as 8.1.
- **UD-10 (zh)**, **UC-12 path values** (`$`): not checked in Chinese text / not checked whether any client matches on a path.
- **STALE: none.** I found no open finding whose fact is no longer true; the nearest are UB-13 (the quoted ApiKeyReader message is now right) and UA-13 (`extending.md` now says what Java can call), both kept because the rest of each stands.
