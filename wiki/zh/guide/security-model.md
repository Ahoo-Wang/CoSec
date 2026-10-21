---
title: "安全模型"
description: "CoSec 对每次决策作出的保证，以及留给你负责的部分。"
---

# 安全模型 {#security-model}

面向需要准确知道请求何时被放行的安全评审、架构师和策略作者。本页是求值顺序以及“拒绝优先、默认拒绝、失败即拒绝”这些保证的唯一出处，其他页面只链接到这里，不再复述。

## 求值顺序 {#evaluation-order}

<!-- Planned (explanation). Blacklist, root, global policies, principal policies, role permissions; then rate limiting for allowed requests. Must agree with AuthorizationExplainer. -->

## 拒绝优先 {#deny-first}

<!-- Planned (explanation). No ALLOW wins over a matched DENY, within or across layers. Worked example. -->

## 默认拒绝 {#default-deny}

<!-- Planned (explanation). No match means IMPLICIT_DENY. -->

## 失败即拒绝 {#fail-closed}

<!-- Planned (explanation). Errors deny; the documented exceptions (rate limit rules without strictFailure, revoked-token check) and why. -->

## 租户隔离 {#tenant-isolation}

<!-- Planned (explanation). What keeps one tenant's policies, roles and grants from reaching another. -->

## 根主体 {#root-principal}

<!-- Planned (explanation). Off by default, platform tenant only, how to issue such tokens safely. -->

## CoSec 不防护的范围 {#what-cosec-does-not-protect-against}

<!-- Planned (explanation). Trust boundaries: token issuing, proxies and IP headers, injected contexts, data-level filtering in your code. -->

## 加固清单 {#hardening-checklist}

<!-- Planned (explanation). From _legacy/guide/operations.md#deployment: secret handling, trusted proxy index, inject only behind the gateway, root off. -->
