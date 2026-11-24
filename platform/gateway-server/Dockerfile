# Build: ./gradlew :gateway-server:installDist, then docker build --build-context shared=platform platform/gateway-server
#   (the shared context holds aot-train.sh, which this image and the control server's both train their AOT cache with).
# docker run --name cosec-gateway-server -d -p 8080:8080 --link redis -e SPRING_DATA_REDIS_URL=redis://redis:6379 -e COSEC_JWT_SECRET=... ahoowang/cosec-gateway

ARG APP_NAME=cosec-gateway-server
ARG WORK_HOME=/opt/${APP_NAME}

# The image runs on JDK 25, for its AOT cache; the CoSec libraries in it are compiled for Java 21 like every module.
FROM dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:25-ubuntu AS base

FROM base AS build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .
# Nothing writable but by its owner, whatever the build host's umask: the run stage copies these modes as they are.
RUN chmod -R u=rwX,go=rX . && chmod 755 bin/*

FROM base AS run
ARG WORK_HOME
ARG APP_NAME

LABEL maintainer="ahoowang@qq.com"

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Root's, and read-only to the process (DR1-G-8): the jars, the start script and the AOT cache are not its to rewrite,
# so a process that is made to write files cannot replace the code the next start runs. Only logs/ and data/ are its own,
# as they are the only directories the Kubernetes manifests mount writable over a read-only root filesystem.
COPY --from=build ${WORK_HOME} ${WORK_HOME}
COPY --chmod=755 --from=shared aot-train.sh /usr/local/bin/aot-train.sh

WORKDIR ${WORK_HOME}

# Train the JDK 25 AOT cache here, in the final stage, as root, which owns the files: the cache records the class path
# including jar mtimes, so it has to be built against these exact files at this exact path. Training needs no Redis.
RUN aot-train.sh ${WORK_HOME} ${WORK_HOME}/aot/${APP_NAME}.aot \
    && chown appuser:appgroup logs data

USER appuser

# JDK_JAVA_OPTIONS (read by the java launcher) rather than JAVA_OPTS, so a deployment that sets JAVA_OPTS keeps
# the cache. A missing or mismatched cache only loses the speedup: the default AOTMode=auto falls back.
ENV JDK_JAVA_OPTIONS="-XX:AOTCache=${WORK_HOME}/aot/${APP_NAME}.aot"

# 8080 is the gateway (and its probes, /livez and /readyz); 8081 is the management port, the actuator
# (/actuator/health, /actuator/prometheus): publish it to a monitoring network only, never to clients.
EXPOSE 8080 8081
# The liveness group, on the application port as /livez: up once the application runs, whatever Redis holds, and proof
# that the listener clients use answers. /actuator/health is DOWN until the platform document is installed, and while
# Redis may evict it (cosecSnapshotEviction): a probe of it would call a gateway that only waits for its documents
# unhealthy.
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/livez || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/cosec-gateway-server"]
