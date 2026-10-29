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

FROM base AS run
ARG WORK_HOME
ARG APP_NAME

LABEL maintainer="ahoowang@qq.com"

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY --from=build --chown=appuser:appgroup ${WORK_HOME} ${WORK_HOME}
COPY --chmod=755 aot-train.sh /usr/local/bin/aot-train.sh

WORKDIR ${WORK_HOME}
USER appuser

# Train the JDK 25 AOT cache here, in the final stage: the cache records the class path including jar mtimes,
# so it has to be built against these exact files at this exact path. Training needs no Redis.
RUN aot-train.sh ${WORK_HOME} ${WORK_HOME}/aot/${APP_NAME}.aot

# JDK_JAVA_OPTIONS (read by the java launcher) rather than JAVA_OPTS, so a deployment that sets JAVA_OPTS keeps
# the cache. A missing or mismatched cache only loses the speedup: the default AOTMode=auto falls back.
ENV JDK_JAVA_OPTIONS="-XX:AOTCache=${WORK_HOME}/aot/${APP_NAME}.aot"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/cosec-gateway-server"]
