---
title: "Roadmap"
description: "What CoSec 8.0 changes, where it stands before it ships, and what 7.x users should do now."
---

# Roadmap

For teams running CoSec 7.x, or evaluating CoSec, who want to know what 8.0 is and when it can ship. This page
stays at the level of what you can rely on. Where the work stands, what is still open before the
release candidate and what waits for 8.1 are in the contributor tracker,
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

8.0 is released: [`8.0.0`](https://github.com/Ahoo-Wang/CoSec/releases/tag/v8.0.0). The architecture above is the
code on `main`, and the 7.x code is gone from it. What an application compiles against is settled: what is stable,
what stays experimental through 8.0, and what is CoSec's own ([Stability levels](../architecture/extending.md#stability-levels)).
Each build is tested against the previous release, both on one store and one network. It is built on Spring Boot 4.2
and CoCache 8.2. What comes next, in 8.1 and later, is in
[`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md), which is the page that tracks it;
this one stays at the level of what you can rely on.

## Release policy

- **Semantic versioning applies from 8.0.0.** A public API, a `cosec.*` property or an authorization semantic is removed
  or changed only in a major release; ABI validation holds the public API to its dump (RFC 0011 D4). What is marked
  `@ExperimentalCoSecApi` may change in a minor release, and the release notes say so.
- **Two adjacent minor versions run mixed.** From 8.0.0, 8.n and 8.n+1 run side by side in one deployment, whichever is
  upgraded first, and a rollback of one minor version is supported ([What is promised](../operations/upgrading.md#what-is-promised)).
- **The 8.0 pre-releases were previews.** The alphas, betas and release candidates of 8.0.0 promised nothing to each
  other or to 8.0.0: a deployment on one moves to 8.0.0 as the release notes say, not under the promise above.
- **8.0 is not slower than 7.0.** Performance budgets are set from 7.0 measurements: compiled authorization and
  pipeline throughput at least 100 % of 7.0, with no more allocation (RFC 0008 §3.8). They are first measured on the
  end-to-end slice, and they were met before the switch from 7.x merged.

## For 7.x users

- **Upgrade to 8.0.** [Upgrading](../operations/upgrading.md) starts with a checklist for a 7.x deployment, then gives
  every change by area; `cosec-migrate` converts the 7.x data. The services, the gateway and the clients move together:
  the order is on that page.
- `7.x` gets security and critical fixes until 2027-05-26, 6 months after 8.0.0 (released 2026-11-26); `release/6.1.x` gets security fixes only
  ([`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md)).
- The latest 7.x fix is
  [7.0.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.2): the policy decoder rejects malformed policies
  (unknown fields, scalars where a list belongs, duplicate keys) instead of compiling them to something broader.
  Until you upgrade, run 7.0.2, and read its upgrade note: policies already stored in Redis may fail requests until you
  republish them.
- The latest advisory is
  [GHSA-2293-jx8h-56m8](https://github.com/Ahoo-Wang/CoSec/security/advisories/GHSA-2293-jx8h-56m8), fixed in
  7.0.1 and 6.1.2.

## How to follow

- [RFC index](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc): the design, decisions and open questions.
- [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md): where the work stands and what
  is planned for 8.1 (Chinese).
- [Issues](https://github.com/Ahoo-Wang/CoSec/issues) and
  [pull requests](https://github.com/Ahoo-Wang/CoSec/pulls) on GitHub.
