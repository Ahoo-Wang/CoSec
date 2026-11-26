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

The architecture above is merged on `main`, and the 7.x code is gone. The current release is
[`8.0.0-rc.2`](https://github.com/Ahoo-Wang/CoSec/releases/tag/v8.0.0-rc.2), the second release candidate: 8.0.0-rc.1,
whose npm packages also accept fetcher 8 ([Upgrading between previews](../operations/upgrading-previews.md#from-8-0-0-rc-1));
no JVM module changed. The first candidate settled the public surface for GA. The adapter contract (`RequestGate`,
`HttpFacts`, `Outcome` and the problems it answers), the test kit's request contexts (`PermittedRequest`,
`IdentityHeader`, `CoSecTestBindings`), the writer of the identity header and the control plane's change events are
stable; the control plane's storage and the shadow window's ports are CoSec's own; what stays experimental through 8.0
says so ([Stability levels](../architecture/extending.md#stability-levels)). Each build is tested against the release
before it, 8.0.0-rc.1, both releases on one store.
It is built on Spring Boot 4.2 and CoCache 8.2. Next is 8.0 GA. What is still open before it is in
[`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md), which is the page that tracks it;
this one stays at the level of what you can rely on.

## Release policy

- **The 8.0 alphas and betas were previews with no compatibility promise.** Public APIs, `cosec.*` properties, the
  document and wire formats (`schema: 2`, `CoSec-Identity`) and authorization semantics changed between them, without a
  migration path between previews.
- **A release candidate (latest `8.0.0-rc.2`) is what 8.0 is to be.** Its public API, `cosec.*` properties, formats and
  authorization semantics change before GA only to fix a defect, and the next candidate's notes list each such change.
  The first came after the checks above: two review rounds of the whole system with their findings fixed, ABI validation, a
  project of its own on 8.0.0-beta.9, and gates on its commit, a mixed run with 8.0.0-beta.9 among them. Semantic
  versioning applies to 8.0 from GA, when ABI validation freezes the public API (RFC 0011 D4).
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
- What to do now: stay on 7.0.2, the latest 7.0.x, in production. To evaluate 8.0, try the release candidate (`8.0.0-rc.2`, Java 21) in a test
  environment with [Upgrading](../operations/upgrading.md); before GA it changes only to fix a defect.

## How to follow

- [RFC index](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc): the design, decisions and open questions.
- [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md): where the work stands, the
  gates of the release candidate, and what is left for 8.1 (Chinese).
- [Issues](https://github.com/Ahoo-Wang/CoSec/issues) and
  [pull requests](https://github.com/Ahoo-Wang/CoSec/pulls) on GitHub.
