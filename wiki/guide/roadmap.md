---
title: "Roadmap"
description: "What CoSec 8.0 changes, where it stands before it ships, and what 7.x users should do now."
---

# Roadmap

For teams running CoSec 7.x, or evaluating CoSec, who want to know what 8.0 is and when it can ship. This page
stays at the level of what you can rely on. Per-PR progress is in the contributor tracker,
[`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md) (Chinese), and every design
decision is in the [RFCs](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc). Where this page and an RFC differ,
the RFC is right.

## What 8.0 is

8.0 is a ground-up redesign of CoSec with no compatibility goal: it follows the target architecture, not 7.x
behavior ([RFC 0008 §2](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0008-conformance-and-direct-build.md)).
Correctness is defined by the 8.0 specification and checked by a conformance suite. What changes:

- **A pure decision engine.** Authorization is a function of the request and an in-memory policy set, with no I/O
  ([RFC 0002](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0002-pure-engine.md)).
- **One flat algorithm over bindings**, with a platform layer and a tenant layer, memberships and scopes, instead of the
  7.x layer order of global policies, principal policies and role permissions
  ([RFC 0004](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0004-unified-model.md)).
- **A typed condition AST, `service:verb` actions and compiled execution**, replacing OGNL, SpEL and the built-in
  matchers ([RFC 0005](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0005-condition-ast.md)).
- **Policy snapshots** instead of repository reads on every request
  ([RFC 0003](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0003-policy-snapshot.md)).
- **A coroutine pipeline**: `Indeterminate` decisions answer 503, rate limits become obligations, and decision
  listeners replace the decorator chain
  ([RFC 0006](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0006-coroutine-pipeline.md)).
- **RFC 9457 problem+json** for every error response CoSec writes (RFC 0006 Q27).
- **A control plane** with revisions, shadow windows before publishing, and grant analysis so no one can grant more
  than they hold ([RFC 0007](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0007-control-plane.md)).
- **A JDK AOT cache** for the gateway image, which CI starts and sends requests to on every build
  ([RFC 0001 §3.10](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md)). Spring AOT
  and native images are not tested in 8.0 and not supported; they are planned for a later 8.x release.

7.x applications have to migrate: policies, permissions and configuration change shape, and `cosec-migrate`
converts 7.x data to the 8.0 format. [Upgrading](../operations/upgrading.md) lists every change and how to migrate.

## Where 8.0 stands

The architecture above is merged on `main`, and the 7.x code is gone. The current preview is `8.0.0-beta.7`. The last
two betas add a typed Kotlin API for the control plane, the same in process and over HTTP
([Roles From an External Source](../scenarios/external-source.md)), and they remove the settings CoSec can compute:
stage timeouts are shares of the pipeline's deadline, and the bounds of caches and snapshots are shares of the heap
([Computed, not configured](../operations/configuration.md#computed-not-configured)). They are built on Spring Boot 4.2
and CoCache 8.2. Next are two more rounds of review with their findings fixed, and then a release candidate. Per-PR
status is in
[`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md), which is the page that tracks it;
this one stays at the level of what you can rely on.

## Release policy

- **The 8.0 pre-releases (alphas and betas; latest `8.0.0-beta.7`) are previews with no compatibility promise.** Public APIs, `cosec.*` properties, the document
  and wire formats (`schema: 2`, `CoSec-Identity`) and authorization semantics may change in any later alpha or RC,
  without a migration path between previews. Try it outside production; do not build on it yet.
- A release candidate waits for the checks above: two review rounds of the whole system (the RC security review, then
  a review of each module) with their findings fixed, and ABI validation, which freezes the public API at GA (RFC 0011 D4). Only then does semantic versioning apply to 8.0.
- **8.0 is not slower than 7.0.** Performance budgets are set from 7.0 measurements: compiled authorization and
  pipeline throughput at least 100 % of 7.0, with no more allocation (RFC 0008 §3.8). They are first measured on the
  end-to-end slice, and they were met before the switch from 7.x merged.

## For 7.x users today

- `7.x` gets security and critical fixes until 6 months after 8.0 GA; `release/6.1.x` gets security fixes only
  ([`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md)).
- The latest fix is
  [7.0.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.2): the policy decoder now rejects malformed policies
  (unknown fields, scalars where a list belongs, duplicate keys) instead of compiling them to something broader.
  Read its upgrade note first: policies already stored in Redis may fail requests until you republish them.
- The latest advisory is
  [GHSA-2293-jx8h-56m8](https://github.com/Ahoo-Wang/CoSec/security/advisories/GHSA-2293-jx8h-56m8), fixed in
  7.0.1 and 6.1.2.
- What to do now: stay on 7.0.2, the latest 7.0.x, in production. To evaluate 8.0, try the latest pre-release (`8.0.0-beta.7`, Java 21) in a test
  environment with [Upgrading](../operations/upgrading.md); expect changes before the RC.

## How to follow

- [RFC index](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc): the design, decisions and open questions.
- [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md): per-PR progress and review
  rounds (Chinese).
- [Issues](https://github.com/Ahoo-Wang/CoSec/issues) and
  [pull requests](https://github.com/Ahoo-Wang/CoSec/pulls) on GitHub.
