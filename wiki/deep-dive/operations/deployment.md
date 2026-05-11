---
title: Deployment
description: How to deploy CoSec Gateway to production with Docker, Kubernetes, health probes, horizontal pod autoscaling, and Redis caching.
---

# Deployment

CoSec Gateway is deployed as a containerized Spring Boot application on Kubernetes. The deployment includes multi-architecture Docker images, health probes, horizontal pod autoscaling, and externalized configuration.

## Deployment Architecture

```mermaid
graph TD
    subgraph "Kubernetes Cluster"
        A["Ingress / Load Balancer"]
        B["Service<br>(port 80 -> 8080)"]
        C["HPA Controller<br>(2-10 replicas)"]
        D["Deployment: cosec-gateway"]
        E["Pod 1"]
        F["Pod 2"]
        G["Pod N..."]
        H["ConfigMap<br>(application.yaml)"]
        I["Secret<br>(Redis credentials)"]
    end
    subgraph "External"
        J["Redis Cluster"]
        K["Docker Registry<br>(multi-arch)"]
    end
    A --> B
    B --> E
    B --> F
    B --> G
    C --> D
    D --> E
    D --> F
    D --> G
    H --> E
    H --> F
    H --> G
    I --> E
    I --> F
    I --> G
    E --> J
    F --> J
    G --> J
    K --> D

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
    style K fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Docker Images

CoSec Gateway publishes multi-architecture Docker images supporting both `linux/amd64` and `linux/arm64`:

| Registry | Image |
|----------|-------|
| Docker Hub | `ahoowang/cosec-gateway` |
| GitHub Container Registry | `ghcr.io/ahoo-wang/cosec-gateway` |
| Alibaba Cloud CR | `registry.cn-shanghai.aliyuncs.com/ahoo/cosec-gateway` |

Tags follow semver patterns: `{{version}}`, `{{major}}.{{minor}}`, branch names, and PR numbers.

## Kubernetes Resources

### Deployment

The gateway deployment specifies resource limits, health probes, and volume mounts:

```yaml
spec:
  containers:
    - image: registry.cn-shanghai.aliyuncs.com/ahoo/cosec-gateway:2.0.1
      startupProbe:
        httpGet: { port: http, path: /actuator/health }
      readinessProbe:
        httpGet: { port: http, path: /actuator/health/readiness }
      livenessProbe:
        httpGet: { port: http, path: /actuator/health/liveness }
      resources:
        limits: { cpu: "4", memory: 2816Mi }
        requests: { cpu: 500m, memory: 2048Mi }
```

```mermaid
sequenceDiagram
    autonumber
    participant Kubelet
    participant Pod as Gateway Pod
    participant App as Spring Boot

    Kubelet->>Pod: Start container
    activate Pod
    Pod->>App: JVM startup (ZGC)
    App->>App: Spring context initialization
    App->>App: Redis connection established
    loop Startup Probe
        Kubelet->>App: GET /actuator/health
        App-->>Kubelet: 503 (not ready)
    end
    App->>App: Ready
    Kubelet->>App: GET /actuator/health
    App-->>Kubelet: 200 OK
    Kubelet->>Kubelet: Mark pod as started
    loop Readiness Probe
        Kubelet->>App: GET /actuator/health/readiness
        App-->>Kubelet: 200 OK
    end
    loop Liveness Probe
        Kubelet->>App: GET /actuator/health/liveness
        App-->>Kubelet: 200 OK
    end



```

### Service

A Kubernetes Service exposing port 80 mapped to container port 8080:

```yaml
spec:
  ports:
    - name: http
      port: 80
      targetPort: 8080
```

### Horizontal Pod Autoscaler

Scales between 2 and 10 replicas based on CPU utilization:

```yaml
spec:
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 600
```

### ConfigMap

Externalized configuration mounted at `/opt/cosec-gateway-server/config/`:

```yaml
data:
  application.yaml: |
    server:
      port: 8080
    cosec:
      jwt:
        algorithm: hmac256
        secret: FyN0Igd80Gas8stTavArGKOYnS9uLWGA_
      authorization:
        cache:
          policy:
            maximum-size: 100000
          role:
            maximum-size: 100000
```

## JVM Configuration

The gateway uses ZGC (Z Garbage Collector) with the following JVM options:

```mermaid
graph LR
    A["Heap: 2048M<br>(-Xms2048M -Xmx2048M)"] --> B["ZGC<br>(-XX:+UseZGC)"]
    C["Metaspace: 256M<br>(-XX:MaxMetaspaceSize=256M)"] --> D["Runtime"]
    E["Direct Memory: 512M<br>(-XX:MaxDirectMemorySize=512M)"] --> D
    F["Thread Stack: 1M<br>(-Xss1m)"] --> D
    G["Heap Dump<br>(-XX:+HeapDumpOnOutOfMemoryError)"] --> H["data/ directory"]
    I["GC Logging<br>(-Xlog:gc*)"] --> J["logs/ directory"]
    K["JMX Remote<br>(port 5555)"] --> L["Monitoring"]
    D --> M["Application Server"]

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
    style K fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style M fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

ZGC is chosen for its low-latency pause times, which is critical for an authorization gateway that must respond in microseconds.

## CI/CD Pipeline

```mermaid
graph LR
    A["Push to branch<br>or tag v*.*.*"] --> B["GitHub Actions<br>(docker-deploy.yml)"]
    B --> C["Setup JDK 17"]
    C --> D["Build Dist<br>(installDist)"]
    D --> E["Setup QEMU + Buildx"]
    E --> F["Docker build<br>(amd64 + arm64)"]
    F --> G["Push to 3 registries"]
    G --> H["Docker Hub"]
    G --> I["GHCR"]
    G --> J["Alibaba Cloud CR"]

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

The pipeline triggers on:
- Push to any branch
- Tags matching `v*.*.*`
- Pull requests to `main`
- Scheduled daily build (10:00 UTC)

## References

- [k8s/cosec-gateway-deployment.yml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-deployment.yml) -- Kubernetes deployment
- [k8s/cosec-gateway-config.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-config.yaml) -- Gateway configuration
- [k8s/cosec-gateway-hpa.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-hpa.yaml) -- Horizontal pod autoscaler
- [k8s/cosec-gateway-service.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-service.yaml) -- Kubernetes service
- [.github/workflows/docker-deploy.yml](https://github.com/Ahoo-Wang/CoSec/blob/main/.github/workflows/docker-deploy.yml) -- CI/CD pipeline
- [cosec-gateway-server/build.gradle.kts:35](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/build.gradle.kts#L35) -- JVM options

## Related Pages

- [Spring Cloud Gateway Integration](../integrations/spring-cloud-gateway.md)
- [Redis Caching](../integrations/redis-caching.md)
- [OpenTelemetry Integration](../integrations/opentelemetry.md)
- [Performance](./performance.md)
