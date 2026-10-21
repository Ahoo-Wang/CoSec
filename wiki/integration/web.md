---
title: "Web Integration"
description: "Wire CoSec into Spring WebFlux, Spring MVC or Spring Cloud Gateway, and read its decisions in your code."
---

# Web Integration

For developers wiring CoSec into a web application or gateway. This page covers choosing an adapter, the responses CoSec writes, the headers and request attributes it reads, and how to reach the security context from your handlers.

## Choosing an adapter

<!-- Planned (guide). WebFlux, MVC, Gateway: which capability, what each one intercepts. -->

## Responses and status codes

<!-- Planned (guide). Single home of decision to status mapping (401/403/429/500) and the error body. -->

## Accessing the security context

<!-- Planned (guide). Reactive and servlet access to SecurityContext and principal. -->

## App and space

<!-- Planned (guide). CoSec-App-Id, CoSec-Space-Id, CoSec-Device-Id headers and how they are resolved. -->

## Client IP behind proxies

<!-- Planned (guide). max-trusted-index and X-Forwarded-For. -->

## Request attributes

<!-- Planned (guide). Attributes available to conditions (ip2region etc.). -->

## CORS

<!-- Planned (guide). Preflight handling. -->

## Gateway

<!-- Planned (guide). Gateway notes, context injection to downstream services, and the ready-to-run cosec-gateway-server image and k8s manifests (from _legacy/guide/operations.md#deployment). -->
