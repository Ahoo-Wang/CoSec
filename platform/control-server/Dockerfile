# Build: ./gradlew :control-server:installDist, then docker build --build-context shared=platform platform/control-server
#   (the shared context holds aot-train.sh, which this image and the gateway's both train their AOT cache with).
# docker run --name cosec-control-server -d -p 8080:8080 --link redis -e SPRING_DATA_REDIS_URL=redis://redis:6379 \
#   -e COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true ahoowang/cosec-control
# It starts only under that statement: it believes the CoSec-Identity header of whoever can reach it.

ARG APP_NAME=cosec-control-server
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
COPY --chmod=755 --from=shared aot-train.sh /usr/local/bin/aot-train.sh

WORKDIR ${WORK_HOME}
USER appuser

# Train the JDK 25 AOT cache here, in the final stage: the cache records the class path including jar mtimes,
# so it has to be built against these exact files at this exact path. Training needs no Redis. The statement of what can
# reach the host is the one thing the application will not start without, so the training run states it, for this step
# only (it is not in the image's environment); the requests are the ones a kubelet or a client would make, answered 401.
RUN AOT_TRAIN_PATHS="/cosec/control/v1/documents/platform /aot-training" \
    AOT_TRAIN_OPTS="-Dcosec.control.http.service-port=0" \
    COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true \
    aot-train.sh ${WORK_HOME} ${WORK_HOME}/aot/${APP_NAME}.aot

# JDK_JAVA_OPTIONS (read by the java launcher) rather than JAVA_OPTS, so a deployment that sets JAVA_OPTS keeps
# the cache. A missing or mismatched cache only loses the speedup: the default AOTMode=auto falls back.
ENV JDK_JAVA_OPTIONS="-XX:AOTCache=${WORK_HOME}/aot/${APP_NAME}.aot"

# 8080 is the operators' API, decided by CoSec's pipeline on the identity header it is sent; 8082 is the service API, the
# narrow profile for services that call it directly (publish it to the labelled service pods only); 8081 is the management
# port, the actuator (/actuator/health/liveness, /actuator/health/readiness, /actuator/prometheus): the probes run
# there, outside CoSec. Publish 8081 to monitoring only, and 8080 to the gateway (and to the services you name) only.
EXPOSE 8080 8081 8082
# The liveness group: up once the application runs, whatever Redis holds. Readiness is not the container's business: it
# is down until the platform document is installed, and while the control store is in an older format.
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8081/actuator/health/liveness || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/cosec-control-server"]
