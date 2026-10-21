---
title: "存储与缓存"
description: "策略、权限和已吊销令牌存放在哪里、如何缓存，以及如何发布变更。"
---

# 存储与缓存 {#storage-and-caching}

面向在生产环境运行 CoSec 的运维人员。本页说明 CoSec 读取哪些存储、Redis 不可用时会发生什么，以及如何发布、回滚变更并保持缓存一致。

## 选择存储 {#choosing-a-store}

<!-- Planned (guide). Local files vs Redis (cosec-cocache). -->

## 本地策略文件 {#local-policy-files}

<!-- Planned (guide). Locations, duplicate-id and missing-location failures. -->

## Redis {#redis}

<!-- Planned (guide). Keys, CoCache 6, local cache, never-expiring caches. -->

## Redis 不可用时 {#when-redis-is-unavailable}

<!-- Planned (guide). Table per store (from _legacy/guide/operations.md#redis-and-caching); link to security-model#fail-closed. -->

## 发布变更 {#publishing-changes}

<!-- Planned (guide). Publishing policies, permissions and role grants; global index. -->

## 策略历史与回滚 {#policy-history-and-rollback}

<!-- Planned (guide). From _legacy/guide/policies.md#policy-history-and-rollback. -->
