---
layout: home
hero:
  name: CoSec
  text: Multi-tenant authorization for Spring
  tagline: Policies as versioned documents, decided deny-first by a pure engine on every request.
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
    details: JSON documents for the platform, each app and each tenant. A deny wins wherever it comes from, anything unmatched is denied, and data that cannot be read never permits.
    link: /policies/language
  - icon: 🏢
    title: Multi-tenant, role-based
    details: Principals belong to tenants, roles apply per space inside an app, and permission catalogs are granted to roles through bindings.
    link: /policies/role-permissions
  - icon: 🧪
    title: Reviewed before it is enforced
    details: A control plane checks and compiles every change, tries it on live traffic in a shadow window, asks a second person to approve grants its author cannot make alone, and publishes revisions you can diff and roll back.
    link: /policies/testing
  - icon: ⚡
    title: Fast and pure
    details: A compiled engine with no I/O over in-memory snapshots. Under 2 µs per request through the whole pipeline, with no event-loop blocking.
    link: /architecture/overview#performance
  - icon: 🔌
    title: WebFlux, MVC and Gateway
    details: One coroutine pipeline behind three adapters that answer byte for byte alike.
    link: /integration/web
  - icon: 🔑
    title: JWT and social login
    details: Token issuing, refresh and revocation that is always checked, API keys for machines, and OAuth login through JustAuth.
    link: /integration/authentication
  - icon: 📊
    title: Auditable
    details: A decision event for every request, naming the statement and binding that decided it and the document revisions it read, for audit logs, metrics, OpenTelemetry and Kafka.
    link: /operations/observability
---
