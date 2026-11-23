# CoSec 8.0 RC deep review, round 1, area H: migration, the test kit and the clients

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area H: the 7.x converter (`migration/migrate`), the policy test kit (`test/testkit`) and the
TypeScript clients (`typescript/cosec-fetcher`, `typescript/cosec-react`). It also holds the manual check of RFC 0008
G5 the coordinator asked for: no main type is used only by tests. Areas A to G are not repeated here.

| | |
|---|---|
| Commit reviewed | `origin/main` at `2f374f25` (#1920) |
| Date | 2026-11-23 |
| Scope | `migration/migrate` (151 files, 12,707 lines); `test/testkit` (17 files, 2,438 lines); `typescript/cosec-fetcher/src` (30 files, 4,889 lines); `typescript/cosec-react/src` (8 files, 711 lines) |
| Reviewer | one read-only session, which read the converter itself, and one read-only sub-reviewer it briefed for the test kit and the clients; none is an author of the code reviewed. Every finding below was checked against the code by the lead reviewer. No code, test, rule or RFC was changed |
| Method | the trust boundary first, then the files against RFC 0005 §7 (the conversion's direction rule), RFC 0004 §8, RFC 0009 §4.4 and §11.3–§11.4, the `MIG` rules, `PIP-25` to `PIP-29`, `contract/`, `wiki/integration/clients.md` and `wiki/policies/testing.md#testing-your-application`. For the converter: every translation read in the direction of its statement, against the 7.x matcher it replaces (`me.ahoo.cosec.migrate.v7`) |

**Severity** as in areas A and E. **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1 and no S2. In the parts of the converter read, `MIG-1` holds on every path: a converted DENY is never narrower
and a converted ALLOW never wider than its 7.x statement. Each part condition, regex shape, role, tenant, template
and rate-limit key was read against the 7.x matcher it replaces. Where 8.0 cannot say the same, the converter rejects
the statement or records the change. The test kit's bindings run the real filters and refuse a context the pipeline
did not make (`PIP-29`). The client sends tokens only to origins it trusts, never puts them in a URL, refreshes once
per pair across tabs, and decides nothing from a token it decoded.

The two S3s are both in the client:
- A failed refresh's error keeps the request that carried the token pair, so an application's error reporter receives
  a live refresh token.
- A sign-in answer with members beyond the pair is sent back whole, so CoSec's own refresh and logout routes refuse it:
  the session never renews, and a logout never ends it at the server.

The G5 check found no main type used only by tests.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-H-1 | S3 | cosec-fetcher, cosec-react | `RefreshTokenError` and `RefreshUnavailableError` keep the refresher's error as `cause`, whose exchange's request body is the token pair; `RefreshableRouteGuard` logs it with `console.error` | open |
| DR1-H-2 | S3 | cosec-fetcher | `signIn` keeps the answer as given, and refresh and logout send it whole: a login answer with a member besides the pair (which `clients.md` allows) is refused 400 by CoSec's routes, so refresh never succeeds and logout never revokes | open |
| DR1-H-3 | S4 | testkit, runtime | A context the kit made (`PermittedRequest`, `PipelineFixture`) is honoured by any pipeline's `AuthorizationChecker`, with the roles it claims; nothing keeps the published kit off a production classpath | open |
| DR1-H-4 | S4 | cosec-fetcher, docs | The pair is kept in `localStorage` by default, and neither `clients.md` nor the README states what that exposes to a script on the origin | open |

## Files read

| Part | Files | Lines | Read |
|---|---|---|---|
| `migration/migrate`: the translators (`StatementTranslator`, `MatcherTranslator`, `ConditionTranslator`, `PartTranslation`, `Translated`, `LiteralRegex`, `TimeTranslation`, `RateLimitTranslation`) | 8 | 1,607 | end to end, by the lead reviewer |
| `migration/migrate`: `Converter.kt` | 1 | 918 | the policy, app, grant and rate-limit paths (about 700 lines); the document assembly at its end in part |
| `migration/migrate`: the API-key migration (`ApiKeyMigration`, `LegacyApiKey`, `ConvertedApiKey`, `ApiKeyMigrationPlan`), `RedisUris`, `PublishedDocuments`, `StoredDocuments` | 8 | about 750 | end to end, `ApiKeyMigrationPlan` and `StoredDocuments` in part |
| `migration/migrate/v7`: the 7.x matchers the translations were checked against (`PathPatternParsers`, `PathActionMatcher`, `BoolConditionMatcher`, `AbstractConditionMatcher`, `EqConditionMatcher`, `PartConditionMatcher`) | 6 | about 460 | as needed |
| `test/testkit/src/main` | 17 | 2,438 | end to end, by the sub-reviewer |
| `typescript/cosec-fetcher/src` | 30 | 4,889 | end to end, by the sub-reviewer |
| `typescript/cosec-react/src` | 8 | 711 | end to end, by the sub-reviewer |

**Not read** (about 9,300 lines of `migration/migrate`, for round 2):
- the rest of the 7.x model copy (`v7/`, about 4,500 lines);
- the commands and their options (`MigrateMain`, `*Command`, `Options`);
- the Redis reading and writing (`CoSec7xReader`, `Source7x`, `RedisAccess`, `CoCache6Format`, `StoreConversion`);
- publishing, restoring and the chain (`DocumentPublisher`, `DocumentRestorer`, `ChainVerification`, `VerifiedChain`);
- `Bootstrap` (`MIG-3`) and `ReferenceRepair` (`MIG-4`);
- `ConvertedDirectory`, `ExportDirectory`;
- the report (`ConversionReport`, `SemanticScan`, `UnmappedVocabulary`, `Unreachable`);
- `LegacyConversion`, `LegacyReferences`, `MembershipsExport`, `PartitionPlan`.

The tests of the three projects were read only to decide whether a property has a guard.

## Trust boundary

| Input | Comes from | Verified before it is used | What the code assumes |
|---|---|---|---|
| 7.x policies, app permissions, grants, rate limit rules, API keys | the operator's 7.x Redis, or an export of it | strict JSON (duplicates refused), the 7.x decoder, the schema marker; every statement translated by direction | the 7.x data is what 7.x enforced; anything that cannot be said in its direction is rejected and nothing is published |
| Redis URIs with credentials | a Kubernetes Secret, on the command line | `RedisUris.scrub` removes user info from everything printed | none printed |
| Scenario files and kit contexts | the application's tests | strict scenario format; vacuous scenarios refused | a kit context is used in tests only (DR1-H-3) |
| The token pair, problem bodies, `Retry-After` | CoSec's routes and the application's login | `isCompositeToken`; problem slugs from `contract/`; `Retry-After` capped at 60 s | the login answer is the pair (DR1-H-2) |
| Request URLs | the application | trust decided once per exchange, by origin, after the URL parser resolves it | a token goes only to a trusted origin |

Attackers considered: a script on the application's origin; another origin the application calls; an error reporter
or log that receives the application's errors; a network fault or an outage of the refresh route; another tab; an
operator converting 7.x data whose policies were written for 7.x's reading; code on a production classpath that uses
the test kit.

## Findings

### DR1-H-1 · S3 · A failed refresh's error carries the token pair

- **Location:** `cosec-fetcher/src/errors.ts`: the `RefreshTokenError` and `RefreshUnavailableError` constructors pass
  the cause on whole, while `LogoutUnconfirmedError` passes `withoutExchange(cause)`. `jwtTokenManager.ts` rethrows
  the refresher's error in them. `CoSecTokenRefresher.refresh` posts the pair as the request body.
  `cosec-react/src/cosec/RefreshableRouteGuard.tsx` logs the error with `console.error`.
- **Trigger:** the refresh route answers 503, another 5xx, 429, 400 or 403, times out, or the network fails.
- **Actual:**
  - The client raises `RefreshUnavailableError` and keeps the session. Its `cause` is the fetcher's `ExchangeError`,
    whose `exchange.request.body` is both tokens.
  - The refresh token is live, and still the current generation.
  - Every business request during the outage fails with an error that holds this one.
  - An error reporter, a JSON logger on a server-rendered page, or a console-capture integration (through
    `RefreshableRouteGuard`) receives the refresh token, which renews the session until its lifetime ends (30 days by
    default).
- **Expected:** `clients.md` says the client "Puts no token on any error". It warns only that an exchange holds the
  `Authorization` header, not that a refresh's exchange holds the refresh token. The logout error strips its exchange
  for exactly this reason.
- **Why S3:** the credential goes only to the application's own reporting, not to another origin. The coordinator may
  weigh it as S2 under "a credential leak".
- **Guard:** none that can catch it. The test "keeps the token when refresh fails with %s" mocks a refresher whose
  error has no exchange. The logout test ("carries no token: not on the error, not on its causes, not on an exchange")
  covers logout only.
- **Reproduced:** no; by the code.
- **Fix direction:** build both refresh errors as the logout error is built: their `problem` and `status`, and a cause
  without its exchange. Log the guard's error by name and message only.
- **Regression guard:** run the real `CoSecTokenRefresher` against the fake host for 503, 429, a timeout, a network
  error and 401. Walk the own properties of the error the caller receives, and assert neither token appears anywhere.

### DR1-H-2 · S3 · Extra members of the sign-in answer make refresh and logout fail

- **Location:**
  - `tokenStorage.ts` `signIn` keeps the object it is given (`new JwtCompositeToken(compositeToken)`).
  - `JwtCompositeTokenSerializer.serialize` spreads `value.token`, and `deserialize` strips only `sessionId`.
  - `tokenRefresher.ts` and `tokenRevoker.ts` send `body: jwtToken.token`.
- **Trigger:** the application's login answers the pair with another member (`expiresIn`, a user object), which
  `clients.md` allows ("a client must ignore the ones it does not know"), and the application calls
  `signIn(() => response.json())` as the documentation shows.
- **Actual:**
  - CoSec's refresh and logout routes refuse a body that is not exactly the pair: 400 `invalid-request`
    (`contract/routes/refresh.json`, `logout.json`).
  - A refresh answered 400 is "not processed": the client raises `RefreshUnavailableError` and keeps the session.
    Every request then fails, with no `onUnauthorized`, until the refresh token expires.
  - `logout()` gets 400, removes the pair locally and raises `LogoutUnconfirmedError`. The family lives on at the
    server until `cosec.jwt.session.lifetime`, so the logout `IDN-18` describes never happens. The only signal is an
    "unconfirmed" error.
- **Expected:** the client sends exactly `{accessToken, refreshToken}` (`contract/token/composite-token.json`).
- **Guard:** none. The contract test of the refresh body signs in with exactly the pair.
- **Reproduced:** no; by the code.
- **Fix direction:** keep only the two members at `signIn` and `deserialize`, or build the refresh and logout bodies
  from those two fields.
- **Regression guard:** sign in with an extra member, then refresh and log out against the strict fake host and the
  real client host, expecting 200 and 204.

### DR1-H-3 · S4 · Any pipeline honours a context the test kit made

- **Location:**
  - In the kit: `PermittedRequest.forSubject` builds its context over `PermittingHost` (an engine that always
    permits), with the roles the test claims. `PipelineFixture` does the same.
  - In the runtime: `RequestSecurity.of` accepts any context a pipeline made. `AuthorizationChecker` checks with that
    context's scope and memberships, and never asks which pipeline made it.
- **Trigger:** code on a production classpath calls the production `AuthorizationChecker` with
  `PermittedRequest.forSubject("x", "acme", roles = setOf("admin"))`, or binds such a context.
- **Actual:** the production engine decides on the claimed roles, with no token and no membership provider.
  `cosec-testkit` is published and in the BOM, and the starter does not look for it on a host's classpath. The
  experimental `Pipeline.builder` with any `Authenticator` already allows the same thing; the kit makes it one call.
- **Expected:** `RequestSecurity`'s own comment says "nothing outside the pipeline mints one". That holds per
  pipeline, not per host.
- **Fix direction:** stamp each `RequestSecurity` with the pipeline that made it, and have `Pipeline.check` and
  `AuthorizationChecker` deny a context another pipeline made. Optionally, the starter warns when
  `me.ahoo.cosec.testkit` is on a host's classpath.
- **Regression guard:** an `AuthorizationCheckerTest` case: "a context another pipeline made is denied".

### DR1-H-4 · S4 · The default token storage's exposure is not documented

- **Location:** the default `TokenStorage` (`tokenStorage.ts`, `storageEventBus.ts`) is `localStorage`.
- **Actual:** the refresh token, which renews the session for up to its lifetime, is readable by any script on the
  origin. Neither `clients.md` nor the package README says so, or compares memory or `sessionStorage` (with what they
  cost the cross-tab refresh, `sharedAcrossTabs`).
- **Fix direction:** one paragraph in `clients.md` (both languages) and the README.

## Threats checked and found sound

| Threat | Code | Rule and guard |
|---|---|---|
| A converted DENY narrower, or ALLOW wider, than its 7.x statement | every translator reads its condition in a direction; each `negate` flips it; only an ALLOW is dropped, a DENY left with no action is rejected | `MIG-1`, the converter's direction tests |
| A converted ALLOW reaching control-plane actions | an ALLOW of every 7.x action is `http:*`, a DENY stays `*` | `MIG-2` |
| 7.x's reading of absent parts as `""`, and the anonymous id `(0)` | `PartTranslation.absent` adds `not(exists)` exactly where 7.x's empty value matched | `MIG-1` |
| `eq` against `#{principal.attributes['k']}` when one side is absent | 7.x compares with a null template value (no match); the DENY translation adds back exactly the cases 7.x matched | `MIG-1` (D2, recorded) |
| Case folding: 7.x folds beyond ASCII (Kotlin's `IGNORE_CASE` adds Unicode case), 8.0 ASCII only | a DENY with a letter that folds is rejected; an ALLOW narrows, recorded | DOC-9 |
| A regular expression mistaken for a literal | `LiteralRegex` accepts only literals, choices of literals and a literal prefix; any metacharacter or an escaped letter is not a literal; `.*` (which stops at line terminators) is a prefix only in a DENY, and only without letters | `MIG-1` |
| Path patterns matched otherwise than 7.x | separator `/` only, the case and decode options carried per resource, `#{...}` templates rejected | RFC 0005 §7.2 |
| HEAD decided as GET | a DENY on HEAD without GET is rejected; an ALLOW drops HEAD, recorded | `PIP-18` |
| Parts 8.0 never fills (request attributes, `resource.*`) | a predicate on one is a constant; only an ALLOW that can no longer hold translates | `MIG-1` (round 2 addition C) |
| A rate-limit rule escaping its limit | its condition reads in the DENY direction; a key absent in 8.0 lands in one shared bucket, as 7.x's `(0)` did (`EvaluatorObligations.bucket`) | RFC 0005 §7.3 |
| An API key gaining access in its migration | each key keeps its roles as a cap, or moves to a principal of its own that holds exactly its roles; a key with attributes, an unresolved attachment or a reused id is rejected; no principal gains roles unless named | RFC 0009 §4.4 |
| A Redis password in the tool's output | `RedisUris.scrub` on every printed message | review of the Job's log |
| A token sent to another origin or put in a URL | `isTrustedRequest` per exchange, after the URL parser resolves the URL; path parameters encoded | `requestTrust.test.ts`, `refreshEndpointTrust.test.ts` |
| Refresh loops, double refreshes, cross-tab races | one retry per exchange; one refresh per pair, in turns across tabs (Web Lock), storage re-read before a write; the retry window stays under the server's leeway | `IDN-16`, `refreshRetryBound`, `concurrentCrossTabRefresh`, `crossTabRefreshRace` tests |
| A problem type handled otherwise than `clients.md` says | `token-expired` refreshed once, `token-revoked` and `token-invalid` end the session, 403 never retried, `scope-rejected` routed | `PIP-25`, `clientContract.test.ts` |
| Access decided from a decoded token | `RouteGuard` and `useSecurity` read `exp` for display only | `clients.md` |
| The kit's bindings bypassing the real filter | they run `PipelineWebFilter` and `PipelineServletFilter`, and refuse hand-made contexts | `PIP-29` |
| The kit passing a scenario the pipeline would fail | vacuous scenarios refused; the decision agrees across trace levels; where the kit differs from production, production is stricter | `wiki/policies/testing.md` |

## G5: no main type used only by tests

RFC 0008 G5. Every public top-level type of the main sources (703) was searched with `git grep -w` for a reference
outside its own file, in main sources, tests and docs. Six have no reference in another main file:

| Type | References | Judgement |
|---|---|---|
| `InMemoryBlacklistSource` (`core/runtime`) | 7 tests, 5 docs | **public API, keep.** Applications declare it as their `BlacklistSource` bean (`wiki/architecture/extending.md`, `skills/cosec-extending`); it is in `runtime.api` |
| `InMemorySocialStateStore` (`core/spi`) | 4 tests, 4 docs | **public API, keep.** The `SocialStateStore` of an application without Redis (`extending.md`); in `spi.api`. Its lack of a bound is area B's |
| `InMemoryTokenFamilyStore` (`core/spi`) | 36 tests, 4 docs | **public API, keep.** The `TokenFamilyStore` of an application without `cosec-cocache` (`wiki/integration/authentication.md`'s sample); in `spi.api` |
| `BootstrapRefusedException` (`migration/migrate`) | 2 tests | **used in main code:** `Bootstrap` throws it in the same file. `cosec-migrate`'s contract is its command line, so it could be `internal`; nothing outside the tool needs it |
| `ControlServer` (`platform/control-server`) | 1 test | **used in main code:** the image's application class, run by `main` in the same file and named by the build's `mainClass` |
| `GatewayServer` (`platform/gateway-server`) | none | **used in main code:** as `ControlServer` |

No main type is used only by tests. The scan counted references outside the declaring file only, so a type used where
it is declared appears in this list; the last three are such cases. A repeatable form of the check should also count
uses in the declaring file other than the declaration itself.
