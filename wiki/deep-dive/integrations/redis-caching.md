---
title: Redis Caching with CoCache
description: How CoSec uses CoCache with Redis for distributed caching of policies, permissions, and role mappings across multiple gateway instances.
---

# Redis Caching with CoCache

CoSec leverages CoCache to provide a two-level distributed caching layer (local Caffeine + Redis) for policies and role permissions. This ensures fast authorization decisions while maintaining consistency across multiple gateway instances.

## Architecture Overview

```mermaid
graph TD
    A["Authorization Request"] --> B["SimpleAuthorization"]
    B --> C["RedisPolicyRepository"]
    B --> D["RedisAppRolePermissionRepository"]
    C --> E["GlobalPolicyIndexCache"]
    C --> F["PolicyCache"]
    D --> G["AppPermissionCache"]
    D --> H["RolePermissionCache"]
    E --> I["Redis (L2)"]
    F --> I
    G --> I
    H --> I
    E --> J["Caffeine (L1)"]
    F --> J
    G --> J
    H --> J

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style I fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Core Components

### RedisPolicyRepository

Implements `PolicyRepository` backed by Redis caches. Provides three operations:

1. **`getGlobalPolicy()`** -- retrieves all global policies by first fetching the global policy index from `GlobalPolicyIndexCache`, then batch-fetching each policy from `PolicyCache`.
2. **`getPolicies(policyIds)`** -- fetches specific policies by ID from `PolicyCache`.
3. **`setPolicy(policy)`** -- stores a policy in `PolicyCache`. If the policy is `PolicyType.GLOBAL`, it also adds the policy ID to the `GlobalPolicyIndexCache`.

```mermaid
sequenceDiagram
    autonumber
    participant Auth as SimpleAuthorization
    participant Repo as RedisPolicyRepository
    participant IndexCache as GlobalPolicyIndexCache
    participant PolicyCache as PolicyCache
    participant Redis

    Auth->>Repo: getGlobalPolicy()
    Repo->>IndexCache: get(CACHE_KEY)
    IndexCache->>Redis: GET global_policy_index
    Redis-->>IndexCache: Set of policy IDs
    IndexCache-->>Repo: Set of policy IDs
    Repo->>PolicyCache: get(policyId1), get(policyId2), ...
    PolicyCache->>Redis: MGET policy:id1 policy:id2 ...
    Redis-->>PolicyCache: Serialized policies
    PolicyCache-->>Repo: List of Policy
    Repo-->>Auth: Mono of List of Policy



```

When `setPolicy()` is called, the policy is first validated via `DefaultPolicyEvaluator.evaluate(policy)` to ensure it is well-formed before caching. If the policy is a global policy, the global index is updated atomically.

### RedisAppRolePermissionRepository

Implements `AppRolePermissionRepository` by combining data from two caches:

1. **`AppPermissionCache`** -- maps `AppId` to `AppPermission` (the application's permission definitions).
2. **`RolePermissionCache`** -- maps `SpacedRoleId` to a `Set<PermissionId>` (permissions granted to each role within a space).

```mermaid
sequenceDiagram
    autonumber
    participant Auth as SimpleAuthorization
    participant Repo as RedisAppRolePermissionRepository
    participant AppCache as AppPermissionCache
    participant RoleCache as RolePermissionCache

    Auth->>Repo: getAppRolePermission(appId, spaceId, roleIds)
    Repo->>AppCache: get(appId)
    AppCache-->>Repo: AppPermission (or null)
    alt AppPermission found
        loop For each roleId
            Repo->>RoleCache: get(SpacedRoleId(roleId, spaceId))
            RoleCache-->>Repo: Set of PermissionId
        end
        Repo-->>Auth: Mono of AppRolePermission
    else AppPermission not found
        Repo-->>Auth: Mono.empty()
    end



```

### Cache Interfaces

All cache interfaces extend CoCache's `Cache<K, V>` interface, providing a unified API with L1 (Caffeine) and L2 (Redis) caching:

| Cache Interface | Key Type | Value Type | Purpose |
|----------------|----------|------------|---------|
| `PolicyCache` | `String` (policy ID) | `Policy` | Individual policy documents |
| `GlobalPolicyIndexCache` | `String` (fixed key) | `Set<String>` (policy IDs) | Index of all global policy IDs |
| `AppPermissionCache` | `AppId` | `AppPermission` | Application permission definitions |
| `RolePermissionCache` | `SpacedRoleId` | `Set<PermissionId>` | Role-to-permission mappings |

### GlobalPolicyIndexKeyConverter

A CoCache `KeyConverter` that maps all cache keys to a single fixed key. This ensures the `GlobalPolicyIndexCache` always reads and writes to the same Redis key, maintaining a single global index entry.

## Cache Configuration

The gateway's `application.yaml` configures cache maximum sizes:

```yaml
cosec:
  authorization:
    cache:
      policy:
        maximum-size: 100000
      role:
        maximum-size: 100000
```

## Cache Hierarchy

```mermaid
graph TD
    subgraph "L1 - Caffeine (In-Process)"
        A["PolicyCache (local)"]
        B["AppPermissionCache (local)"]
        C["RolePermissionCache (local)"]
        D["GlobalPolicyIndexCache (local)"]
    end
    subgraph "L2 - Redis (Distributed)"
        E["PolicyCache (Redis)"]
        F["AppPermissionCache (Redis)"]
        G["RolePermissionCache (Redis)"]
        H["GlobalPolicyIndexCache (Redis)"]
    end
    A -->|miss| E
    B -->|miss| F
    C -->|miss| G
    D -->|miss| H
    E -->|invalidate| A
    F -->|invalidate| B
    G -->|invalidate| C
    H -->|invalidate| D

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## References

- [cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/RedisPolicyRepository.kt:26](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/RedisPolicyRepository.kt#L26) -- Policy repository
- [cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/RedisAppRolePermissionRepository.kt:27](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/RedisAppRolePermissionRepository.kt#L27) -- Role permission repository
- [cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/PolicyCache.kt:23](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/PolicyCache.kt#L23) -- Policy cache interface
- [cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/AppPermissionCache.kt:20](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/AppPermissionCache.kt#L20) -- App permission cache interface
- [cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/GlobalPolicyIndexCache.kt:22](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/GlobalPolicyIndexCache.kt#L22) -- Global policy index cache
- [cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/GlobalPolicyIndexKeyConverter.kt:18](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/GlobalPolicyIndexKeyConverter.kt#L18) -- Key converter

## Related Pages

- [Spring Cloud Gateway Integration](./spring-cloud-gateway.md)
- [OpenTelemetry Integration](./opentelemetry.md)
- [Performance](../operations/performance.md)
- [Deployment](../operations/deployment.md)
