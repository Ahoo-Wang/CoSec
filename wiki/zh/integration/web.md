---
title: "Web 集成"
description: "把 CoSec 接入 Spring WebFlux、Spring MVC 或 Spring Cloud Gateway，并在代码里读取它的决策结果。"
---

# Web 集成 {#web-integration}

面向把 CoSec 接入 Web 应用或网关的开发者。本页介绍如何选择适配器、CoSec 写出的响应、它读取的请求头和请求属性，以及如何在处理器中拿到安全上下文。

## 选择适配器 {#choosing-an-adapter}

<!-- Planned (guide). WebFlux, MVC, Gateway: which capability, what each one intercepts. -->

## 响应与状态码 {#responses-and-status-codes}

<!-- Planned (guide). Single home of decision to status mapping (401/403/429/500) and the error body. -->

## 访问安全上下文 {#accessing-the-security-context}

<!-- Planned (guide). Reactive and servlet access to SecurityContext and principal. -->

## 应用与空间 {#app-and-space}

<!-- Planned (guide). CoSec-App-Id, CoSec-Space-Id, CoSec-Device-Id headers and how they are resolved. -->

## 代理后的客户端 IP {#client-ip-behind-proxies}

<!-- Planned (guide). max-trusted-index and X-Forwarded-For. -->

## 请求属性 {#request-attributes}

<!-- Planned (guide). Attributes available to conditions (ip2region etc.). -->

## CORS {#cors}

<!-- Planned (guide). Preflight handling. -->

## 网关 {#gateway}

<!-- Planned (guide). Gateway notes, context injection to downstream services, and the ready-to-run cosec-gateway-server image and k8s manifests (from _legacy/guide/operations.md#deployment). -->
