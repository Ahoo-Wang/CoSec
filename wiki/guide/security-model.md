---
title: "Security Model"
description: "The guarantees CoSec makes about every decision, and the ones it leaves to you."
---

# Security Model

For security reviewers, architects and policy authors who need to know exactly when a request is allowed. This page is the single home of the evaluation order and the deny-first, default-deny and fail-closed guarantees; every other page links here instead of restating them.

## Evaluation order

<!-- Planned (explanation). Blacklist, root, global policies, principal policies, role permissions; then rate limiting for allowed requests. Must agree with AuthorizationExplainer. -->

## Deny first

<!-- Planned (explanation). No ALLOW wins over a matched DENY, within or across layers. Worked example. -->

## Default deny

<!-- Planned (explanation). No match means IMPLICIT_DENY. -->

## Fail closed

<!-- Planned (explanation). Errors deny; the documented exceptions (rate limit rules without strictFailure, revoked-token check) and why. -->

## Tenant isolation

<!-- Planned (explanation). What keeps one tenant's policies, roles and grants from reaching another. -->

## Root principal

<!-- Planned (explanation). Off by default, platform tenant only, how to issue such tokens safely. -->

## What CoSec does not protect against

<!-- Planned (explanation). Trust boundaries: token issuing, proxies and IP headers, injected contexts, data-level filtering in your code. -->

## Hardening checklist

<!-- Planned (explanation). From _legacy/guide/operations.md#deployment: secret handling, trusted proxy index, inject only behind the gateway, root off. -->
