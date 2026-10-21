---
title: "Architecture Overview"
description: "How CoSec is built: modules, the request path, the authorization chain, invariants and performance."
---

# Architecture Overview

For contributors changing CoSec and integrators who want to know how it works inside. This page explains the module layering, how a request travels through the pipeline, and the invariants every change must keep.

## Design goals

<!-- Planned (explanation). -->

## Module layering

<!-- Planned (explanation). Dependency rules between cosec-api, cosec-core and adapters; the module list itself lives in guide/introduction#modules. -->

## Request path

<!-- Planned (explanation). Adapter, SecurityPipeline, SecurityDecision. -->

## Authorization chain

<!-- Planned (explanation). Decorator chain (tracing, auditing, metrics, rate limiting, shadow, SimpleAuthorization). The evaluation order itself lives in guide/security-model. -->

## Invariants

<!-- Planned (explanation). The invariant table and the tests that guard each one. -->

## Data and caching

<!-- Planned (explanation). Implementation view; operational view lives in operations/storage. -->

## Performance

<!-- Planned (explanation). JMH numbers and how to reproduce them. -->
