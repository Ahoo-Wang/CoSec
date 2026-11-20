# Issue #1679: testing an application against CoSec. Design note

Design only, 2026-11-19, against `origin/main` fb3a6952. Nothing coded or pushed.

## 1. A context with no token and no documents

**Recommendation: `PermittedRequest.forSubject(...)`, made by the identity gate from the header
`IdentityHeader.forSubject` writes.** That is a production path: a service behind a gateway
(`cosec.pipeline.mode=gateway`) gets exactly this context for exactly this header.

```
// testkit.api
public final fun forSubject (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Set;Ljava/util/Map;)Lme/ahoo/cosec/api/context/SecurityContext;
// Kotlin
fun forSubject(subject: String, tenant: String = "(0)", app: String = "", space: String = "",
               roles: Set<String> = emptySet(), headers: Map<String, List<String>> = emptyMap()): SecurityContext
```

It runs `Pipeline.behindGateway()` over a request carrying the header, plus the request's other `headers`, as
`withToken` does. It refuses credential headers, and it throws when the gate does not permit (a malformed id, a role
with an unpaired surrogate).

What the context carries, written in the KDoc:

| Part | Value | Same as on an enforce-mode host? |
|---|---|---|
| principal, tenant | `subject`, `tenant`; `authenticated = true` | yes |
| scope | `app`, `space`, `roles` (effective there) | yes |
| attributes | none | yes for a token without claims |
| decision event `subject.method` | `jwt` (the header's credential) | yes, for CoSec's own token |
| session | made up by `forSubject` (random id, login now, generation 1) | no: a tenant switch issues from a session no family knows (`withToken` covers that case) |
| `SecurityContext.identity` | **non-null**: the forwarded identity | **no**: it is null on an enforce-mode host |

The last row is the one surprise. Code that branches on `identity != null` takes the behind-a-gateway branch.

**Why not a stand-in authenticator.** It would give `identity = null`, but it is a path nothing in production takes.
`PermittedRequest`'s promise is "made as in production", and IDN-22 depends on provenance (the session, the credential
method). The made-up session already has to be documented for `IdentityHeader`. The KDoc names `identity` and points to
`withToken` and `PipelineFixture` for code that reads it.

## 2. Binding a context on each stack

**Recommendation: (b) and (c), both in the adapter modules,** in a package named `testing` and an object named
`CoSecTestBindings`, so a production import reads wrong. `cosec-testkit` stays free of Reactor, Spring and the Servlet
API. The adapters' writers stay `@InternalCoSecApi`; only these entries call them, through the opt-in CoSec's own
modules have.

```
// webflux.api  (package me.ahoo.cosec.webflux.testing)
public final class me/ahoo/cosec/webflux/testing/CoSecTestBindings {
  public final fun filter (Lme/ahoo/cosec/runtime/Pipeline;)Lorg/springframework/web/server/WebFilter;          // (c)
  public final fun withSecurityContext (Lreactor/core/publisher/Mono;Lme/ahoo/cosec/api/context/SecurityContext;)Lreactor/core/publisher/Mono;  // Reactor context
  public final fun bind (Lorg/springframework/web/server/ServerWebExchange;Lme/ahoo/cosec/api/context/SecurityContext;)V                         // exchange attribute
}
// webmvc.api  (package me.ahoo.cosec.webmvc.testing)
public final class me/ahoo/cosec/webmvc/testing/CoSecTestBindings {
  public final fun filter (Lme/ahoo/cosec/runtime/Pipeline;)Ljakarta/servlet/Filter;                              // (c)
  public final fun bind (Ljakarta/servlet/http/HttpServletRequest;Lme/ahoo/cosec/api/context/SecurityContext;)V  // request attribute
  public final fun withSecurityContext (Lme/ahoo/cosec/api/context/SecurityContext;Lkotlin/jvm/functions/Function0;)Ljava/lang/Object;  // SecurityContextHolder, previous value restored
}
```

- **(c) is the faithful form for handler tests.** `filter(pipeline)` is CoSec's own filter over a kit pipeline. A
  test sends `WebTestClient` / `MockMvc` requests with a token the fixture issues, and nothing is bound by hand. It
  needs one testkit addition: `PipelineFixture.authorization(principal): String`, the `Authorization` header value for
  a fixture principal (today only `fixture.request(...)` mints one).
- **(b) is for a unit test of a function** that reads the context.
- **Every binder refuses a context the pipeline did not make** (`RequestSecurity.of(ctx) == null`) with
  `IllegalArgumentException`: *"Only a context CoSec's pipeline made can be bound: use PermittedRequest.withToken or
  forSubject, or PipelineFixture.securityContext."* A test never runs as anonymous without saying so.
- **`PIP-26` holds unchanged:** a bound context whose request has ended is still denied, since binding does not touch
  `RequestSecurity`.
- **(a), documenting writes under `SecurityContext.KEY`, alone:** rejected. It leaves `SecurityContextHolder` with
  nothing, and invites hand-made contexts that `PIP-27` turns into "no context".

## 3. Slices

**Recommendation: no slice annotation now. (2c) covers slices without CoSec's auto-configurations.**
- `@WebMvcTest` includes `Filter` beans and `@WebFluxTest` includes `WebFilter` beans. So a test configuration that
  declares `CoSecTestBindings.filter(fixture.pipeline)` as a bean, plus `SecurityContextArgumentResolver` (public
  already) through a `WebMvcConfigurer`, gives a slice the real filter over the kit's documents.
- An `@AutoConfigureCoSec` meta-annotation would import the starter, which needs a `SnapshotDocumentStore`, a token
  store and CoCache (Redis, or in-memory backends wired by properties), and wires about 20 configurations into a slice
  that only wants the filter. That is a larger surface to keep stable, for less faithful documents than a fixture.
- The page shows the slice configuration (about 10 lines per stack). An annotation can come later if users ask, without
  breaking anything.

## 4. Where it is written down
- **A new section "Testing your application" in `wiki/policies/testing.md`** (en, zh), beside "Testing your code
  against a real pipeline". It shows a handler test on each stack, both the standalone form (`WebTestClient` /
  `MockMvc` with `filter(...)`) and the slice form; a unit test with `forSubject` and a binder; and what `identity` is.
- `skills/cosec-integration` gets a few lines.
- **The `PIP-27` entry of `upgrading-previews.md`** (en, zh): replace "bind the context" with the two forms.

## 5. Rules and guards
- **New rule `PIP-29` (build):** "A test binder binds only a context CoSec's pipeline made, and refuses any other with
  an error; the kit's token-less context is made by the identity gate."
  - Guards: per stack, a test that binds a pipeline-made context (positive) and one that refuses a hand-made one
    (negative), plus `PermittedRequestTest` for `forSubject`'s provenance.
  - Mutant: the webmvc binder accepts any `SecurityContext`. That one, because `SecurityContextHolder` is the binder
    with the widest reach.
- **ABI:** webflux +1 class (3 functions), webmvc +1 class (3 functions), testkit +2 lines (`forSubject` and its
  `$default`, and `PipelineFixture.authorization`). All `@ExperimentalCoSecApi` and additive.
- **`SpiMarkerTest`:** no new interface, so nothing to mark.
- **`ConstructibleApiTest`:** `CoSecTestBindings` is an `object`, and the binders take only types the tests already
  get from the kit, so nothing new needs constructing.
- **`DependencyRulesTest`:** `cosec-testkit` is unchanged. The adapter modules gain no dependency, because `Pipeline`
  comes from `cosec-runtime`, which they already have.

## 6. Size
- **Main code:** about 230 lines (testkit 50, webflux 80, webmvc 100).
- **Docs:** about 300 lines (en and zh section, skill, upgrading entry).
- **Tests:** about 350 lines, not counted.

That is one PR at about 530 lines of main code and docs. If it grows, the cut is the docs section and skill as a
second PR, with the code and its KDoc first.
