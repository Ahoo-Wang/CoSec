# docker run --name cosec-gateway-server -d -p 8080:8080 --link redis -e SPRING_REDIS_URL=redis://redis:6379 ahoowang/cosec-gateway-server:0.8.0

ARG APP_NAME=cosec-gateway-server
ARG WORK_HOME=/opt/${APP_NAME}

FROM dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:21-ubuntu AS base

FROM base as build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .

FROM base as run
ARG WORK_HOME

LABEL maintainer="ahoowang@qq.com"
COPY --from=build ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
EXPOSE 8080

ENTRYPOINT ["bin/cosec-gateway-server"]
