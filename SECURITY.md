# Security Policy

CoSec is a security framework, so we treat vulnerability reports with priority.

## Supported versions

Security fixes land on `main` first and are backported to the maintenance branches (see the branching model in
[CONTRIBUTING.md](CONTRIBUTING.md#2-branching-model)).

| Version | Branch | Supported |
|---|---|---|
| 8.0 (in development) | `main` | ✅ |
| 7.x | `7.x` | ✅ security and critical fixes, until 6 months after 8.0.0 GA |
| 6.1.x | `release/6.1.x` | ✅ security fixes only |
| < 6.1 | — | ❌ |

## Reporting a vulnerability

**Do not open a public issue, pull request, or discussion for a vulnerability.**

Report it privately through GitHub's
[**Report a vulnerability**](https://github.com/Ahoo-Wang/CoSec/security/advisories/new) form (preferred).
If you cannot use GitHub, email **ahoowang@qq.com** with the subject `[CoSec Security]`. Include:

- the affected version(s) and module(s);
- a description of the issue and its impact (for example, an authorization bypass or a fail-open path);
- steps or a minimal policy/configuration to reproduce it;
- any suggested fix, if you have one.

## What to expect

- We acknowledge the report within **3 business days**.
- We confirm the issue, assess its severity, and agree on a disclosure timeline with you.
- Fixes are released as a patch version and announced in the GitHub release notes, credited to you
  unless you prefer to stay anonymous.

## Scope

In scope: authorization decisions (policy evaluation, deny-first semantics, boundaries and break-glass),
token verification and revocation, request parsing (path traversal, remote IP resolution, `CoSec-*` scope
parameters), condition compilation (type checks, cost and size limits), the control plane's authorization of
changes, and snapshot or caching behavior that could fail open.
