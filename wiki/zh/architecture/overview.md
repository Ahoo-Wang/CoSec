---
title: "架构概览"
description: "CoSec 的内部构造：模块、请求路径、授权链、不变量与性能。"
---

# 架构概览 {#architecture-overview}

面向修改 CoSec 的贡献者，以及想了解其内部机制的集成者。本页说明模块分层、请求如何经过安全管道，以及每次修改都必须保持的不变量。

## 设计目标 {#design-goals}

<!-- Planned (explanation). -->

## 模块分层 {#module-layering}

<!-- Planned (explanation). Dependency rules between cosec-api, cosec-core and adapters; the module list itself lives in guide/introduction#modules. -->

## 请求路径 {#request-path}

<!-- Planned (explanation). Adapter, SecurityPipeline, SecurityDecision. -->

## 授权链 {#authorization-chain}

<!-- Planned (explanation). Decorator chain (tracing, auditing, metrics, rate limiting, shadow, SimpleAuthorization). The evaluation order itself lives in guide/security-model. -->

## 不变量 {#invariants}

<!-- Planned (explanation). The invariant table and the tests that guard each one. -->

## 数据与缓存 {#data-and-caching}

<!-- Planned (explanation). Implementation view; operational view lives in operations/storage. -->

## 性能 {#performance}

<!-- Planned (explanation). JMH numbers and how to reproduce them. -->
