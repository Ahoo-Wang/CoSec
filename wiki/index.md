---
layout: home
hero:
  name: CoSec
  text: Multi-tenant reactive security for Spring
  tagline: IAM-style policies and role-based permissions, evaluated deny-first for every request.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/introduction
    - theme: alt
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoSec
features:
  - icon: 🛡️
    title: Policies as data
    details: JSON policies with path actions, composable conditions and rate limits. Deny wins across every layer, and anything unmatched is denied.
    link: /guide/policies
  - icon: 🏢
    title: Multi-tenant, role-based
    details: Tenant-aware principals, plus per-application permission catalogs granted to roles per space.
    link: /guide/policies#app-permissions-and-roles
  - icon: ⚡
    title: Reactive and fast
    details: Built on Project Reactor. About 2 µs per decision, with no event-loop blocking.
    link: /architecture/overview#performance
  - icon: 🔌
    title: WebFlux, MVC and Gateway
    details: One security pipeline behind three adapters that respond identically.
    link: /guide/web-integration
  - icon: 🔑
    title: JWT and social login
    details: Token issuing, refresh and immediate revocation, plus OAuth login through JustAuth.
    link: /guide/authentication
  - icon: 📊
    title: Auditable
    details: An audit event and an OpenTelemetry span for every decision, naming the policy or permission that decided it.
    link: /guide/operations#auditing
---
