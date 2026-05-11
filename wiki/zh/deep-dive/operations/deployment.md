---
title: 部署
description: 如何使用 Docker、Kubernetes、健康探针、水平 Pod 自动扩缩和 Redis 缓存将 CoSec Gateway 部署到生产环境。
---

# 部署

CoSec Gateway 作为容器化的 Spring Boot 应用部署在 Kubernetes 上。部署包括多架构 Docker 镜像、健康探针、水平 Pod 自动扩缩和外部化配置。

## 部署架构

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

## Docker 镜像

CoSec Gateway 发布支持 `linux/amd64` 和 `linux/arm64` 的多架构 Docker 镜像：

| 镜像仓库 | 镜像 |
|----------|------|
| Docker Hub | `ahoowang/cosec-gateway` |
| GitHub 容器仓库 | `ghcr.io/ahoo-wang/cosec-gateway` |
| 阿里云容器镜像服务 | `registry.cn-shanghai.aliyuncs.com/ahoo/cosec-gateway` |

标签遵循语义化版本模式：`{{version}}`、`{{major}}.{{minor}}`、分支名和 PR 编号。

## Kubernetes 资源

### Deployment

网关 Deployment 指定了资源限制、健康探针和卷挂载：

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

Kubernetes Service 暴露 80 端口映射到容器的 8080 端口：

```yaml
spec:
  ports:
    - name: http
      port: 80
      targetPort: 8080
```

### 水平 Pod 自动扩缩器

根据 CPU 利用率在 2 到 10 个副本之间自动扩缩：

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

外部化配置挂载到 `/opt/cosec-gateway-server/config/`：

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

## JVM 配置

网关使用 ZGC（Z 垃圾回收器），JVM 选项如下：

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

选择 ZGC 是因为其低延迟暂停时间，这对于必须在微秒内响应的授权网关至关重要。

## CI/CD 流水线

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

流水线在以下情况触发：
- 推送到任何分支
- 匹配 `v*.*.*` 的标签
- 向 `main` 分支提交 Pull Request
- 每日定时构建（UTC 10:00）

## 参考资料

- [k8s/cosec-gateway-deployment.yml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-deployment.yml) -- Kubernetes 部署
- [k8s/cosec-gateway-config.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-config.yaml) -- 网关配置
- [k8s/cosec-gateway-hpa.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-hpa.yaml) -- 水平 Pod 自动扩缩器
- [k8s/cosec-gateway-service.yaml](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/cosec-gateway-service.yaml) -- Kubernetes Service
- [.github/workflows/docker-deploy.yml](https://github.com/Ahoo-Wang/CoSec/blob/main/.github/workflows/docker-deploy.yml) -- CI/CD 流水线
- [cosec-gateway-server/build.gradle.kts:35](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/build.gradle.kts#L35) -- JVM 选项

## 相关页面

- [Spring Cloud Gateway 集成](../integrations/spring-cloud-gateway.md)
- [Redis 缓存](../integrations/redis-caching.md)
- [OpenTelemetry 集成](../integrations/opentelemetry.md)
- [性能](./performance.md)
