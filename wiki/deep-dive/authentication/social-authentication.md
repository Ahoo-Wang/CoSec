---
title: Social Authentication
description: How CoSec integrates OAuth social login via the JustAuth library, including the SocialAuthentication flow, provider registry, and principal conversion.
---

# Social Authentication

CoSec provides OAuth-based social login through the `cosec-social` module. It uses the [JustAuth](https://github.com/justauth/JustAuth) library to support dozens of OAuth providers (GitHub, Google, WeChat, DingTalk, etc.) behind a unified authentication interface.

## Architecture Overview

Social authentication follows the standard CoSec `Authentication<C, P>` pattern but specializes it for OAuth flows. The key abstractions are:

- **`SocialCredentials`** -- carries the OAuth callback data plus a `provider` identifier
- **`SocialAuthenticationProvider`** -- per-provider logic for generating authorize URLs and exchanging codes for user data
- **`SocialAuthentication`** -- the top-level `Authentication` implementation that routes to the correct provider
- **`SocialUserPrincipalConverter`** -- converts the provider's user profile into a `CoSecPrincipal`

## Key Interfaces

### SocialCredentials

[SocialCredentials](cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialCredentials.kt) extends `Credentials` with a `provider` field:

```kotlin
interface SocialCredentials : Credentials {
    val provider: String
}
```

The concrete implementation is [JustAuthCredentials](cosec-social/src/main/kotlin/me/ahoo/cosec/social/justauth/JustAuthCredentials.kt), which also extends JustAuth's `AuthCallback` to carry the OAuth authorization code and state.

### SocialAuthenticationProvider

[SocialAuthenticationProvider](cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialAuthenticationProvider.kt) defines per-provider behavior:

```kotlin
interface SocialAuthenticationProvider : Named {
    fun authorizeUrl(): String
    fun authenticate(credentials: SocialCredentials): Mono<SocialUser>
}
```

- `authorizeUrl()` generates the OAuth authorization redirect URL
- `authenticate()` exchanges the authorization code for a `SocialUser`

### SocialProviderManager

[SocialProviderManager](cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialProviderManager.kt) is a singleton registry that maps provider names (e.g., "github", "google") to `SocialAuthenticationProvider` instances:

```kotlin
object SocialProviderManager {
    fun register(authProvider: SocialAuthenticationProvider)
    fun getRequired(provider: String): SocialAuthenticationProvider
}
```

### SocialUser

[SocialUser](cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialUser.kt) is a data class holding the provider's user profile:

```kotlin
data class SocialUser(
    val id: String,
    val username: String,
    val nickname: String? = null,
    val avatar: String? = null,
    val email: String? = null,
    val location: String? = null,
    val gender: Gender = Gender.UNKNOWN,
    val rawInfo: MutableMap<String, Any> = mutableMapOf(),
    val provider: String
)
```

### SocialUserPrincipalConverter

[SocialUserPrincipalConverter](cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialUserPrincipalConverter.kt) converts a `SocialUser` into a `CoSecPrincipal`:

```kotlin
fun interface SocialUserPrincipalConverter {
    fun convert(provider: String, authUser: SocialUser): Mono<CoSecPrincipal>
}
```

### DirectSocialUserPrincipalConverter

[DirectSocialUserPrincipalConverter](cosec-social/src/main/kotlin/me/ahoo/cosec/social/DirectSocialUserPrincipalConverter.kt) is the default implementation. It creates a `SimplePrincipal` with a composite ID format:

```kotlin
// ID format: "userId@provider" (e.g., "12345@github")
private fun asProviderUserId(provider: String, authUser: SocialUser): String {
    return authUser.id + "@" + provider
}
```

The principal starts with empty policies and roles -- these must be assigned separately after account creation/linking.

## JustAuth Integration

### JustAuthProvider

[JustAuthProvider](cosec-social/src/main/kotlin/me/ahoo/cosec/social/justauth/JustAuthProvider.kt) wraps a JustAuth `AuthRequest` to implement `SocialAuthenticationProvider`:

```kotlin
class JustAuthProvider(
    override val name: String,
    private val authRequest: AuthRequest,
    private val idGenerator: IdGenerator
) : SocialAuthenticationProvider
```

- `authorizeUrl()` calls `authRequest.authorize(state)` using a generated state token
- `authenticate()` calls `authRequest.login(credentials)` and converts the `AuthUser` response to a `SocialUser`

### RedisAuthStateCache

[RedisAuthStateCache](cosec-social/src/main/kotlin/me/ahoo/cosec/social/justauth/RedisAuthStateCache.kt) implements JustAuth's `AuthStateCache` interface using Redis to store OAuth state tokens across distributed instances. States expire after 3 minutes. Keys are prefixed with `cosec:oauth:state:`.

## Architecture Diagrams

### Social Authentication Class Diagram

```mermaid
classDiagram
    direction TB
    class Credentials {
        <<interface>>
    }
    class SocialCredentials {
        <<interface>>
        +provider: String
    }
    class JustAuthCredentials {
        +provider: String
    }
    class SocialAuthentication {
        -principalConverter: SocialUserPrincipalConverter
        +authenticate(credentials): Mono~CoSecPrincipal~
        +authorizeUrl(provider): String
    }
    class SocialAuthenticationProvider {
        <<interface>>
        +name: String
        +authorizeUrl(): String
        +authenticate(credentials): Mono~SocialUser~
    }
    class JustAuthProvider {
        +name: String
        -authRequest: AuthRequest
        -idGenerator: IdGenerator
    }
    class SocialProviderManager {
        <<object>>
        +register(authProvider)
        +getRequired(provider): SocialAuthenticationProvider
    }
    class SocialUser {
        +id: String
        +username: String
        +provider: String
    }
    class SocialUserPrincipalConverter {
        <<interface>>
        +convert(provider, authUser): Mono~CoSecPrincipal~
    }
    class DirectSocialUserPrincipalConverter {
        <<object>>
        +convert(provider, authUser): Mono~CoSecPrincipal~
    }

    Credentials <|-- SocialCredentials
    SocialCredentials <|-- JustAuthCredentials
    SocialAuthenticationProvider <|.. JustAuthProvider
    SocialAuthentication --> SocialProviderManager : delegates to
    SocialAuthentication --> SocialUserPrincipalConverter : converts via
    SocialUserPrincipalConverter <|.. DirectSocialUserPrincipalConverter
    JustAuthProvider --> SocialUser : produces



```

### Social OAuth Flow Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Browser
    participant App as Application
    participant SA as SocialAuthentication
    participant SPM as SocialProviderManager
    participant JA as JustAuthProvider
    participant OAuth as OAuth Provider
    participant Conv as SocialUserPrincipalConverter

    Browser->>App: GET /oauth/authorize/github
    App->>SA: authorizeUrl("github")
    SA->>SPM: getRequired("github")
    SPM-->>JA: JustAuthProvider
    JA->>JA: authorize(state)
    JA-->>App: redirect URL
    App-->>Browser: 302 Redirect to OAuth Provider

    Browser->>OAuth: User logs in and grants permission
    OAuth-->>Browser: 302 Redirect to callback with code

    Browser->>App: GET /oauth/callback/github?code=xxx&state=yyy
    App->>SA: authenticate(JustAuthCredentials)
    SA->>SPM: getRequired("github")
    SPM-->>JA: JustAuthProvider
    JA->>OAuth: exchange code for user info
    OAuth-->>JA: AuthResponse<AuthUser>
    JA->>JA: AuthUser.toSocialUser("github")
    JA-->>SA: SocialUser
    SA->>Conv: convert("github", socialUser)
    Conv->>Conv: id = "userId@github"
    Conv-->>SA: CoSecPrincipal
    SA-->>App: CoSecPrincipal
    App-->>Browser: Session / Token response

```

### Provider Registration Flow

```mermaid
flowchart TD
    A["Spring AutoConfiguration"] -->|"creates"| B["JustAuthRequest per provider"]
    B -->|"wraps"| C["JustAuthProvider"]
    C -->|"registers"| D["SocialProviderManager"]
    D -->|"stores in"| E["ConcurrentHashMap"]
    F["SocialAuthentication"] -->|"getRequired(provider)"| D

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Design Decisions

1. **Pluggable providers**: The `SocialAuthenticationProvider` abstraction allows swapping JustAuth for another OAuth library without changing application code.
2. **Composite user ID**: `userId@provider` format ensures globally unique principal IDs across all OAuth providers.
3. **Distributed state**: `RedisAuthStateCache` ensures OAuth state tokens work across multiple application instances.
4. **Customizable conversion**: The `SocialUserPrincipalConverter` interface allows applications to implement custom logic for creating principals (e.g., linking to existing accounts, assigning default roles).

## References

- [SocialAuthentication.kt:24](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialAuthentication.kt#L24) - Top-level social authentication
- [SocialAuthenticationProvider.kt:23](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialAuthenticationProvider.kt#L23) - Per-provider interface
- [SocialProviderManager.kt:22](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-social/src/main/kotlin/me/ahoo/cosec/social/SocialProviderManager.kt#L22) - Provider registry singleton
- [JustAuthProvider.kt:33](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-social/src/main/kotlin/me/ahoo/cosec/social/justauth/JustAuthProvider.kt#L33) - JustAuth wrapper implementation
- [DirectSocialUserPrincipalConverter.kt:25](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-social/src/main/kotlin/me/ahoo/cosec/social/DirectSocialUserPrincipalConverter.kt#L25) - Default principal converter with "userId@provider" format

## Related Pages

- [Authentication System](./authentication-system.md) - How social auth plugs into the provider registry
- [Token Management](./token-management.md) - Converting social auth principals to tokens
- [JWT Integration](./jwt-integration.md) - JWT token creation after social login
- [Authorization Flow](../authorization/authorization-flow.md) - How social principals are authorized
