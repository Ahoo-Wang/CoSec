# AGENTS.md

## Build & Run Commands

```bash
# Build all modules
./gradlew build

# Run all tests
./gradlew test

# Run tests for a single module
./gradlew :cosec-core:test

# Run a single test class
./gradlew :cosec-core:test --tests "me.ahoo.cosec.policy.DefaultPolicyEvaluatorTest"

# Run Detekt static analysis
./gradlew detekt

# Run JMH benchmarks
./gradlew :cosec-core:jmh
```

## Testing

- JUnit 5 with `@Test` annotations
- MockK for mocking, FluentAssert (`me.ahoo.test.asserts.assert`) for assertions
- Hamcrest matchers also used
- Reactor Test (`StepVerifier`) for reactive tests
- Integration tests require Redis (see CI workflow for setup)

## Project Structure

```
cosec-api/          - Core interfaces (no framework deps)
cosec-core/         - Policy evaluation, auth logic implementations
cosec-jwt/          - JWT token handling (auth0/java-jwt)
cosec-cocache/      - Redis caching for policies/permissions
cosec-social/       - OAuth social auth via JustAuth
cosec-ip2region/    - IP geolocation enrichment
cosec-opentelemetry/ - OpenTelemetry tracing instrumentation
cosec-openapi/      - Swagger/OpenAPI integration
cosec-webflux/      - Reactive WebFilter integration
cosec-webmvc/       - Servlet filter integration
cosec-gateway/      - Spring Cloud Gateway GlobalFilter
cosec-spring-boot-starter/ - Auto-configuration (aggregates all)
cosec-gateway-server/ - Standalone gateway app (not published)
cosec-dependencies/ - Version catalog
cosec-bom/          - Bill of Materials
```

## Code Style

- Kotlin 2.x with strict JSR-305 and JVM default compatibility
- All public APIs are interfaces in `cosec-api`; implementations in `cosec-core`
- Reactive throughout: core interfaces return `Mono<T>`
- No comments by default; only add when WHY is non-obvious
- Detekt with auto-correct for linting

## Architecture

- AWS IAM-inspired policy model: Policy → Statement → Effect + ActionMatcher + ConditionMatcher
- Deny-first evaluation: DENY statements checked before ALLOW
- SPI extension points via Java ServiceLoader for ActionMatcher and ConditionMatcher
- Multi-tenant: TenantPrincipal, tenant-scoped policies

## RoGraph Integration

- CoSec is a RoGraph security gateway foundation, not a commercial Product Context.
- RoGraph-facing Published Language must use RESTful API as the publication form.
- Events, SDKs, internal reactive APIs, filters, gateway adapters, claims, and storage schemas may be implementation or integration mechanisms, but they must not replace RoGraph Published Language.
- Do not treat CoSec as the identity or access source of truth; Idena owns identity, access, credentials, security context, and identity audit facts.

## Boundaries

- ✅ Always add new interfaces to `cosec-api`
- ✅ Always run `./gradlew detekt` before committing
- ✅ Use FluentAssert for test assertions
- ⚠️ Ask before modifying SPI interfaces (ActionMatcherFactory, ConditionMatcherFactory)
- 🚫 Never modify cosec-dependencies versions without approval
- 🚫 Never publish cosec-gateway-server (it's not a library)
