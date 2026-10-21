---
title: "Storage and Caching"
description: "Where policies, permissions and revoked tokens live, how they are cached, and how to publish changes."
---

# Storage and Caching

For operators running CoSec in production. This page explains the stores CoSec reads from, what happens when Redis is unavailable, and how to publish, roll back and keep caches consistent.

## Choosing a store

<!-- Planned (guide). Local files vs Redis (cosec-cocache). -->

## Local policy files

<!-- Planned (guide). Locations, duplicate-id and missing-location failures. -->

## Redis

<!-- Planned (guide). Keys, CoCache 6, local cache, never-expiring caches. -->

## When Redis is unavailable

<!-- Planned (guide). Table per store (from _legacy/guide/operations.md#redis-and-caching); link to security-model#fail-closed. -->

## Publishing changes

<!-- Planned (guide). Publishing policies, permissions and role grants; global index. -->

## Policy history and rollback

<!-- Planned (guide). From _legacy/guide/policies.md#policy-history-and-rollback. -->
