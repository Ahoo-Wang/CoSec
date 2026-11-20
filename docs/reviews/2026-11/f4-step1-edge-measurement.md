# F4 step 1: the edge's share of a gateway request (PR #1759, head 1f3c798eb)

The runs are `jmh-ab.yml` with base = head = `perf/edge-request-path-benchmark`, so two samples of the same code.

- e2e: run 44742649286. jwt: run 44742659727.
- One fork, 3 warmup and 5 measured iterations of 1 s, with `-prof gc`.
- Host: AMD EPYC 9V74, 4 vCPUs (a GitHub runner). The absolute numbers are a shared runner's; the shares are what to read.
- Each value below is the mean of the two samples. Where the samples differ by more than 5 %, the row says so.

The request is GET `/orders/op49/detail?page=2&size=20`, built as Reactor Netty hands it to Spring:

- Netty `DefaultHttpHeaders` behind `Netty4HeadersAdapter`, read-only.
- Cookies decoded by Netty's `ServerCookieDecoder` on first read.
- 14 headers: a bearer token, `Cookie: SESSION=…; theme=dark`, a three-hop `X-Forwarded-For`, `traceparent`, and a browser's `Accept*`, `User-Agent`, `Referer` and `Origin`.

The pipeline is the `PipelineBenchmark` slice over Redis with its documents. The `admin` role permits the request.

- At the gateway, the route is `lb://`: it receives the identity, so the pipeline writes `CoSec-Identity`.
- The slice's authenticator is the test one, a few string splits. A real JWT is priced separately, in the last section.

## Raw numbers

Two notes on reading these:

- "net" subtracts the request's own construction: `reactiveRequest` on the reactive side, `servletRequest(NoReads)` on the servlet side.
- Servlet requests are `MockHttpServletRequest`s. Tomcat's real request is recycled and parses cookies lazily, so the servlet rows are rougher.

| operation | ns/op | B/op | net ns | net B |
|---|---:|---:|---:|---:|
| `reactiveRequest`: exchange and Netty-shaped request only | 957 | 1 696 | | |
| `reactiveRead`: `HttpFactsReader.read`, gateway form | 4 545 | 9 858 | 3 588 | 8 162 |
| ↳ `reactiveReadHeaders`: copy, then `Headers.of` | 3 552 | 7 481 | 2 595 | 5 785 |
| ↳ `reactiveReadCookies`: Netty decode, `mapValues`, `Cookies.of` | 1 548 | 3 289 | 591 | 1 593 |
| ↳ `reactiveReadAddress`: X-Forwarded-For split | 1 312 | 2 185 | 355 | 489 |
| `pipelineGateway`: `Pipeline.evaluate`, writes the identity | 4 933 | 9 298 | | |
| `pipelineHost`: `Pipeline.evaluate`, no identity | 3 723 | 6 037 | | |
| **`gatewayFilters`**: strip filter, pipeline filter, last hop | **16 675** | **35 979** | **15 718** | **34 283** |
| ↳ `gatewayStrip`: the unconditional `mutate` of R2B-9 (1) | 3 993 (±7 %) | 8 118 | 3 036 | 6 422 |
| ↳ `gatewayLastHop`: `IdentityHeadersFilter` full copy | 3 537 | 6 193 | 2 580 | 4 497 |
| `webFilter`: `PipelineWebFilter` at a host | 9 456 | 17 628 | 8 499 | 15 932 |
| `servletRequestNoReads`: mock request and response | 3 190 | 10 881 | | |
| `servletRequest`: same, plus 10 framework `getHeader` reads | 3 415 | 11 449 | | |
| `servletRead`: `ServletHttpFactsReader.read` | 5 762 | 20 250 | 2 572 | 9 369 |
| `servletFilter`: filter, then 10 reads through the wrapper | 10 516 | 29 544 | 7 101 | 18 095 |
| `servletFilterNoReads` | 10 322 (±10 %) | 28 832 | 7 132 | 17 951 |

## A gateway request, apportioned (CoSec's part: 15.7 µs, 34.3 KB = 100 %)

| part | µs | KB | share of time | finding |
|---|---:|---:|---:|---|
| `Pipeline.evaluate`: route, authenticate (test token), engine, identity encode | 4.93 | 9.3 | 31 % | — |
| reading the request into `HttpFacts` | ≤ 3.6 | ≤ 8.2 | ≤ 23 % | R2B-8 |
| ↳ headers copied twice | ≤ 2.6 | ≤ 5.8 | ≤ 17 % | R2B-8 (1, 2) |
| ↳ cookies parsed and copied | 0.6 | 1.6 | 4 % | R2B-8 (3) |
| ↳ client address resolved eagerly | 0.35 | 0.5 | 2 % | R2B-8 (4) |
| unconditional strip (`exchange.mutate()`, header copy) | 3.0 | 6.4 | 19 % | R2B-9 (1) |
| last hop copies every header to set one | 2.6 | 4.5 | 16 % | R2B-9 (2) |
| the rest: bridge (`mono`, principal decorator, security context), downstream `mutate`, route facts twice, method-override and credential removal | ≈ 1.6 | ≈ 5.9 | ≈ 10 % | R2B-9 (2, 3), part |
| `PipelineGatewayFilter` cookie-stripping job, for a request without CoSec's cookie | ~0 | ~0 | ~0 | R2B-14: structure, no cost |
| `Unbound` written twice | 0 | 0 | 0 | R2B-13: duplication, no cost |

Two caveats on these parts:

- **They overlap.** Inside `gatewayFilters`, the reader reads the stripped request. Its headers are a Spring `LinkedCaseInsensitiveMap` copy, which is cheaper to read than Netty's adapter. So "≤" marks the reader's share as an upper bound. Removing the strip does not save the full 3.0 µs: the reader then reads Netty's headers. Each fix's saving is settled by its own A/B against `gatewayFilters`.
- **The last hop's input differs.** In production its input is that same Spring copy; here it is Netty's adapter. The copy cost should be similar but is not identical.

**Headline: the edge costs about 2.2 times the decision.** Of 15.7 µs, CoSec's edge code takes about 10.8 µs and 25 KB, against 4.9 µs and 9.3 KB for the pipeline. On a host, `webFilter` nets 8.5 µs, of which 3.7 µs is the pipeline: the reader is about 3.6 µs and the bridge about 1.2 µs.

**R2B-15 is not measurable.** Ten `getHeader` reads through `PermittedServletRequest`, against the raw request, differ by −31 ns (noise ±900 ns) and 144 B per request, about 14 B per read; escape analysis removes most of the `Pair`s. The servlet reader itself nets 2.6 µs and 9.4 KB, the same scale as the WebFlux reader.

## Proposed order of the fixes

1. **R2B-25: done, in this PR.** `RealServerFactsParityTest` compares the facts the two readers hand the pipeline. It sends raw requests to Tomcat, Jetty, Reactor Netty and the reactive Tomcat and Jetty, and compares method, path, query, address and every value of the named headers and cookies. It found three differences, pinned as open "splits":
   - A. On Reactor Netty, and so the gateway, a header repeated under several letter cases is read once per case: 3 cases give 9 values. Netty's names are distinct by case; each name's values are not.
   - B. Tomcat (servlet and reactive) keeps a cookie value's quotes; Jetty and Netty unwrap them.
   - C. Reactive Tomcat and Jetty read a null query for a bare `?`; the servlet containers and Reactor Netty read "".

   No decision differs today. Every reader of header values takes the first value (`Authenticate`, request id), treats identical repeats as one (`isAmbiguous`, PIP-12), or asks for "more than one" (`IdentityGate`). Decisions for you:
   - B: unwrap in both readers, as Netty, Jetty and the gateway's stripping do. I recommend this.
   - C: an empty query is null in both readers. I recommend this; the reactive servers' URI has already lost the difference.
   - A: fixed by item 3.
2. **R2B-9 with R2B-14, one perf(gateway) PR.** First a commit that moves `CredentialStripping` out with no behaviour change, which brings the file back under the 400-line cap. Then:
   - Guard the strip with `containsHeader`, as `IdentityStripFilter` does: about 19 %, a two-line change.
   - The last hop returns its input unchanged when the input already holds exactly the forwarded value, once. The pipeline filter set it, and only a later filter that tampered with it needs the copy. About 16 %.
   - One downstream `mutate`, lower-cased credential names, and the route facts computed once per route.

   Measured with `gatewayFilters`; expected from about 15.7 to about 10 µs.
3. **R2B-8 with R2B-15, one perf(webflux,webmvc) PR.**
   - `Headers` becomes a view over the host's case-insensitive headers. This removes the 2.6 µs and 5.8 KB of double copying, and fixes split A, since lookups go through Spring's case-insensitive `get`.
   - Cookies are read lazily (0.6 µs) and the address only when the plan reads `request.ip` (0.35 µs).
   - The R2B-15 `Pair` goes in passing.

   One design point first. `HttpFacts` says the pipeline "never goes back to the host". A view is safe only while nothing writes the host's headers during `evaluate`. Netty's are read-only, and the gateway's stripped copy is written only after the outcome, by the chain. And whatever outlives the request must copy what it keeps: the decision events, BEST_EFFORT listeners and audit. I will check that before writing it.
4. **R2B-22, a cache of verified tokens**, after you decide on the design below. For the target deployment's token it is the largest single saving.
5. **R2B-13** (one `Unbound`): a refactor with no runtime effect, at any time.

## R2B-22: a cache of verified tokens

What verification costs today (HS256; `authenticate` is verification, both revocation reads from a local map, and the principal):

| token | length | `verify` µs | `verify` B/op | `authenticate` µs | `authenticate` B/op | `digest` (SHA-256 of the token) µs | `digest` B/op |
|---|---:|---:|---:|---:|---:|---:|---:|
| bare | 225 | 1.69 | 5 792 | 1.87 | 6 840 | 0.16 | 296 |
| rich: 4 roles, 2 policies, nested attributes | 529 | 3.82 | 14 232 | 4.28 | 17 056 | 0.32 | 600 |
| large: 12 roles in each of 4 spaces, 8 policies | 1 648 | 8.56 | 37 428 | 10.25 | 49 296 | 0.87 | 1 712 |

The slice's test authenticator costs near nothing. So a real gateway request with the large token is about 15.7 + 10.3 ≈ 26 µs, and verification is a third of it, more than the whole pipeline. A hit costs the digest plus a map read: about 0.2, 0.4 and 0.9 µs. It would save about 1.5, 3.4 and 7.7 µs and 5.5, 13.6 and 35.7 KB per request.

**Proposal: build it, on the request path only, keyed by a digest.**

- **Where.** A decorator of CoSec's own `TokenVerifier`, used only by the pipeline's `TokenAuthenticator`. It is never on refresh, tenant switch or logout. Not for another verifier's tokens: a third party's verdict may depend on more than the token string, such as introspection. Opt-in or off for those.
- **Key.** SHA-256 of the whole presented token, 32 bytes (`Bearer ` stripped first, as `verify` does).
  - Not the raw token. Hashing a `String` is cheaper, but the map would then hold every live bearer token for its lifetime, there for a heap dump to collect.
  - Not a non-cryptographic or truncated hash. A crafted collision would hand an attacker another principal's verdict.
  - Not the signature segment alone. That would make 43 characters a credential by themselves.
  - The digest costs about 10 % of what the hit saves.
- **Value.** The immutable `VerifiedToken`, which `verify` already returns (attributes are copied read-only, #1343).
  - Only successful verifications are cached. An invalid, expired, not-yet-valid (`IDN-29`), delegation (`IDN-4`) or refresh-as-access (`IDN-13`) token always goes through `verify` and is refused as today.
  - An attacker can fill the cache only with tokens validly issued to them.
- **Lifetime.**
  - A hit checks expiry with the verifier's own clock and leeway. At or after `exp` it is a miss, and `verify` answers `TOKEN_EXPIRED` as today. Never past `exp`.
  - Nothing else can make a verified token invalid before `exp` in 8.0: one static HMAC key, and changing it means a restart.
  - When keys rotate at runtime (the 8.1 IdP work), the key id joins the cache key, or a rotation clears the cache. I'll note that in the KDoc and the 8.1 RFC.
- **Revocation and `IDN-14`: unchanged.**
  - `TokenAuthenticator` runs `revocation.check(token)` and the `RESERVED_ATTRIBUTES` check after `verify` on every request, hit or miss. So a revoked token, or one of a revoked subject, is refused on its next request exactly as today. PIP-7's degraded acceptance during a revocation outage is unchanged.
  - `IDN-14` is about issuing from an earlier token. The issuing paths (`refresh`, switch) never see the cache, so no token is issued from a cached verdict.
- **Bound.**
  - Entry count, configurable (`cosec.authentication.token-cache.max-entries`), plus `enabled`.
  - A `VerifiedToken` of the large kind holds about 48 role strings and sets: an estimated 5 to 8 KB retained. At a default of 10 000 entries that is about 50 to 80 MB worst case, a few MB for bare tokens.
  - Eviction is simple: size-bounded, approximate LRU or random. A miss costs one `verify`, so eviction can only cost speed, never correctness.
  - Metered: hits, misses, evictions.
  - I'll measure retained size with JOL in the PR, and the hit cost with a `cached` variant of `JwtVerificationBenchmark`.
- **Rules.** A new `IDN-*` rule ("a cached verification never outlives `exp` and never skips the revocation check") with two mutants: one serves an entry past `exp`, one skips `revocation.check` on a hit.

The argument against is memory and one more state to reason about. The numbers above say it pays: at the large token it saves more than the pipeline costs.

## Other data

- The run times show the cost of the bridges themselves, beyond the pipeline: `webFilter` nets 8.5 µs and `servletFilter` 7.1 µs, against 3.7 µs for `pipelineHost`.
- Token lengths are from the run log (`JwtVerificationBenchmark` prints them in setup).
