# AGENTS.md — CoSec Wiki (VitePress)

The user-facing docs site, published at https://cosec.ahoo.me from `main`. English at the root, Chinese
under `zh/`. Rules for changing CoSec itself are in the root [`AGENTS.md`](../AGENTS.md).

## Commands

Run from `wiki/`:

```bash
pnpm install
pnpm dev                 # dev server
pnpm check:mermaid       # parses every Mermaid diagram in headless Chrome and checks the conventions below
pnpm fix:mermaid         # rewrites <br/> to <br>, adds autonumber to sequence diagrams, and similar fixes
pnpm check:docs          # links, #anchors, en/zh heading parity, en/zh sidebar parity, then check:legacy
pnpm check:legacy        # no mention of an API or property that 8.0 removed (RFC 0008 G4)
pnpm build               # production build; fails on dead links
```

The `docs` job of `.github/workflows/ci.yml` runs `check:docs` and `build` on every pull request, and the required
`CI` check needs it. `.github/workflows/deploy-wiki.yml` runs `check:mermaid`, `check:docs` and `build` again before
it deploys from `main`; `check:mermaid` is not run on pull requests, so run it before pushing a change to a diagram.
It needs Chrome; set `CHROME_BIN` if it is not on `PATH`.

## Structure

Organized by the task the reader is doing, not by module.

| Area | Reader | Pages |
|---|---|---|
| `guide/` | Evaluating CoSec, or starting with it | `introduction`, `quick-start`, `concepts`, `security-model`, `roadmap` |
| `integration/` | Developer wiring CoSec into an application | `web`, `authentication`, `checks` |
| `policies/` | Writing and reviewing the rules | `language`, `role-permissions`, `rate-limiting`, `testing` |
| `operations/` | Running CoSec in production | `configuration`, `storage`, `upgrading`, `observability`, `troubleshooting` |
| `architecture/` | Contributor, or integrator who wants the internals | `overview`, `extending` |
| `zh/` | One-to-one Chinese mirror of everything above | |

Navigation and sidebar live in `.vitepress/config/en.ts` and `zh.ts`; `check:docs` fails if the two differ or a
page is missing from the sidebar. Site-wide settings (domain, analytics, Mermaid, search) are in
`.vitepress/config/index.ts`.

## Page types

Every page is one type. Do not mix them: a reference page that starts teaching, or a guide that lists every
option, is harder to use for both jobs.

| Type | Answers | Shape | Pages |
|---|---|---|---|
| **Guide** | "How do I …?" | Steps or recipes toward one goal; links to references for every option | `quick-start`, `integration/*`, `role-permissions`, `rate-limiting`, `testing`, `storage`, `upgrading`, `observability`, `troubleshooting`, `extending` |
| **Reference** | "What exactly does X mean?" | Complete, terse, ordered for lookup (tables); no narrative | `policies/language`, `operations/configuration` |
| **Explanation** | "Why does it behave this way?" | Concepts, guarantees, trade-offs, diagrams | `introduction`, `concepts`, `security-model`, `architecture/overview` |

## One fact, one home

Each fact is written in exactly one place. Everywhere else links to it. A copied fact goes stale the first
time one copy is updated. The homes that are easiest to duplicate by accident:

| Fact | Home |
|---|---|
| Decision algorithm and candidate order; deny-first, default-deny and fail-closed guarantees and their exceptions; boundary, blacklist and break-glass | `guide/security-model.md` |
| Which inputs the client controls (tenant, app, space, remote IP) | `guide/security-model.md#trust-boundaries` |
| Terms (principal, tenant, document, policy, statement, binding, membership, decision) | `guide/concepts.md` |
| Module list, what each module needs, and the starter's module check (STA-1) | `guide/introduction.md#modules` |
| Reader paths (which page follows which) | `guide/introduction.md#where-to-go-next` |
| Outcome to HTTP status mapping; problem+json bodies; `CoSec-*` parameters | `integration/web.md` |
| Credentials, membership claims, revocation, API keys | `integration/authentication.md` |
| `AuthorizationChecker`; explaining a decision | `integration/checks.md` |
| Every document field, action and resource pattern, condition operator and attribute | `policies/language.md` |
| App catalogs, role bindings and how they are scoped to an app and a space | `policies/role-permissions.md` |
| Rate-limit obligations; `RateLimit-*` and `Retry-After` headers | `policies/rate-limiting.md` |
| Control-plane changes: validation, simulate, shadow windows, approval, analysis | `policies/testing.md` |
| Every `cosec.*` property and default | `operations/configuration.md` |
| Every Redis key; publishing, revisions and rollback | `operations/storage.md` |
| Behavior when Redis is unavailable | `operations/storage.md#when-redis-is-unavailable` |
| Decision listeners, audit events, metrics, spans | `operations/observability.md` |
| Migration steps | `operations/upgrading.md` |
| The request path through the pipeline and why | `architecture/overview.md` |
| Invariants and the tests that guard them; performance numbers | `architecture/overview.md` |
| Extension points (SPIs) | `architecture/extending.md` |

Outside the wiki: contribution workflow and releases in `CONTRIBUTING.md`, agent build rules in the root
`AGENTS.md`, API details in KDoc, JSON formats in the format descriptors that generate `schema/*.schema.json`.

## Conventions

- **en and zh in pairs.** Every page under the root has a twin under `zh/` with the same headings in the same
  order. Every Chinese heading, including the `#` title, carries the English slug as an explicit anchor:
  `## 求值顺序 {#evaluation-order}`. `check:docs` compares the anchor sets.
- Every page has `title` and `description` frontmatter, then a short paragraph saying who the page is for and
  what problem it solves.
- **Verify every claim against the code**, not against other docs. A wrong doc is worse than a
  missing one. CoSec makes security decisions: say exactly when a request is allowed.
- **Link to files, never to line numbers.** Line anchors rot with every commit. Use relative links between
  pages (`../policies/language.md#actions`) so `check:docs` and the build can verify them.
- Write any text containing `<…>`, such as `Mono<T>`, in backticks; otherwise Vue parses it as HTML.
- **Document 8.0, not what it replaced.** No version-history notes ("since 7.0", "removed in 6.1"), and no
  mention of an API, property or version that 8.0 removed. `scripts/check-legacy.mjs` (`check:legacy`, part of
  `check:docs`) fails on a whole-word match of its deny list in `wiki/`, `skills/`, `README*.md`, `llms.txt`,
  `SECURITY.md` and `k8s/`, and prints the file, line, term and what to write instead. Migration text goes in the
  `7.x to 8.0` section of `operations/upgrading.md`, the only exempt section. A deliberate contrast elsewhere
  (for example "7.x matchers have no 8.0 equivalent") is a file-and-term entry with a reason in the script's
  `ALLOWED` list; there is no inline escape, and an entry that matches nothing fails, so delete it with the text.

- **Reader paths.** [Where to go next](guide/introduction.md#where-to-go-next) defines five reading paths
  (evaluator, developer, policy author, operator, contributor). Every page on a path ends with one
  `**Next:**` line (`**下一步：**` in `zh/`) linking to the following page. Adding, removing or reordering a page
  on a path means updating that table and the `**Next:**` lines around it, in both languages.

## Glossary

Use these terms, and only these, in both languages. Code identifiers, property names and quoted messages stay as
they are in the code.

| English | 中文 | Notes |
|---|---|---|
| principal | 主体 | Who makes the request. Not 用户 or 委托人. |
| anonymous principal / caller | 匿名主体 / 匿名调用方 | Principal id `(0)`. |
| tenant; default tenant; platform tenant; user tenant | 租户；默认租户；平台租户；用户租户 | `(0)`, `(platform)`, any other id. |
| credential | 凭证 | A JWT access token or an API key. Not 凭据. |
| token; access token; refresh token | 令牌；访问令牌；刷新令牌 | Not "Token" in Chinese prose. |
| API key | API key | Not API Key or API 密钥. |
| revocation, revoke | 吊销 | Not 撤销 or 注销. |
| membership; membership claims | 成员关系；成员关系声明 | |
| app; space; default space | 应用；空间；默认空间 | |
| scope | 作用域 | A resolved tenant, app and space, with the roles there. |
| document; platform / app / tenant document | 文档；平台文档 / 应用文档 / 租户文档 | |
| snapshot; revision | 快照；修订 | |
| policy; statement; action; resource; condition | 策略；语句；动作；资源；条件 | |
| binding; attachment (attached policy) | 绑定；附加策略 | |
| role; role binding | 角色；角色绑定 | |
| catalog; permission | 目录；权限 | Permission catalog: 权限目录. |
| boundary | 边界 | The platform boundary of a tenant. |
| break-glass | break-glass | Keep the English term. |
| effect: allow / deny | 效果：允许 / 拒绝 | `ALLOW`, `DENY` stay in English. |
| decision: Permit / Deny / Indeterminate | 决策：Permit / Deny / Indeterminate | Keep the three names in English. |
| obligation | 义务 | Rate limits run as obligations after a Permit. |
| evaluation order; candidate order | 求值顺序；候选顺序 | |
| deny first | 拒绝优先 | |
| default deny | 默认拒绝 | |
| fail closed; fail open | 失败即拒绝；失败即放行 | Not the English phrase in Chinese prose. |
| trust boundary | 信任边界 | |
| store of record | 权威存储 | Redis for snapshot documents. |
| local copy | 本地副本 | CoCache's in-process copy of a Redis value. |
| control plane; data plane | 控制面；数据面 | |
| change; change request | 变更；变更请求 | |
| candidate; shadow window | 候选；影子窗口 | |
| publish; rollback | 发布；回滚 | |
| security context | 安全上下文 | |
| route map | 路由映射 | |
| attribute; external attribute; attribute provider | 属性；外部属性；属性提供者 | |
| rate limit rule; permit | 限流规则；许可 | |
| decision listener; audit event | 决策监听器；审计事件 | |
| problem (problem+json) | problem | Keep the English term. |
| explain | explain、解释 | The API is named `explain`. |

## Mermaid

`vitepress-plugin-mermaid` turns a ` ```mermaid ` block into the `<Mermaid>` component
(`.vitepress/theme/Mermaid.vue`), which renders it for the current light/dark theme and adds zoom and an
expanded view. So:

- Add a diagram only when it explains a mechanism (a request path, a decision, a sequence) better than prose.
- Line breaks in labels: `<br>`, never `<br/>`.
- Sequence diagrams start with `autonumber`, so the prose can refer to steps by number. Never put `;` in
  sequence message text; Mermaid reads it as a statement separator.
- No per-node `style`, `classDef` or colors: the theme handles light and dark mode.
- Run `pnpm check:mermaid` before pushing. It parses every diagram with the real Mermaid parser and fails on
  `<br/>` or a sequence diagram without `autonumber`; `pnpm fix:mermaid` fixes both.
