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
pnpm check:docs          # links, #anchors, en/zh heading parity, en/zh sidebar parity
pnpm build               # production build; fails on dead links
```

CI (`.github/workflows/deploy-wiki.yml`) runs `check:mermaid`, `check:docs` and `build` on every pull request
that touches `wiki/`. `check:mermaid` needs Chrome; set `CHROME_BIN` if it is not on `PATH`.

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
| Evaluation order; deny-first, default-deny and fail-closed guarantees and their exceptions | `guide/security-model.md` |
| Who is root and what root bypasses | `guide/security-model.md#root-principal` |
| Which inputs the client controls (app, space, IP, inject mode) | `guide/security-model.md#trust-boundaries` |
| Terms (principal, tenant, policy, statement, permission, decision) | `guide/concepts.md` |
| Module list and starter capabilities | `guide/introduction.md#modules` |
| Reader paths (which page follows which) | `guide/introduction.md#where-to-go-next` |
| Decision to HTTP status mapping; `CoSec-*` headers | `integration/web.md` |
| Token claims, revocation, API keys | `integration/authentication.md` |
| The explain API, its configuration and fields | `integration/checks.md#explaining-a-decision` |
| Every policy field, action and condition type | `policies/language.md` |
| `inRole` matching rules | `policies/language.md#matching-roles` |
| Where path variables may be read | `policies/language.md#path-variables` |
| Strict loading of policy files | `policies/language.md#loading-policy-files` |
| How role grants are scoped to an app and a space | `policies/role-permissions.md#role-grant-scope` |
| `RateLimit-*` and `Retry-After` headers | `policies/rate-limiting.md#responses-and-headers` |
| Every `cosec.*` property and default | `operations/configuration.md` |
| Every Redis key; the schema marker | `operations/storage.md#redis-keys`, `operations/storage.md#schema-marker` |
| Behavior when Redis is unavailable | `operations/storage.md#when-redis-is-unavailable` |
| Which authorization decorators are present, and their switches | `operations/observability.md#the-decision-chain` |
| Migration steps | `operations/upgrading.md` |
| Order of the decorator chain and why | `architecture/overview.md#authorization-chain` |
| Invariants and the tests that guard them; performance numbers | `architecture/overview.md` |

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
| root principal | 根主体 | `root` stays in property names (`root-enabled`). |
| credential | 凭证 | A JWT access token or an API key. Not 凭据. |
| token; access token; refresh token | 令牌；访问令牌；刷新令牌 | Not "Token" in Chinese prose. |
| API key | API key | Not API Key or API 密钥. |
| revocation, revoke | 吊销 | Not 撤销 or 注销. |
| app; app permission; permission catalog | 应用；应用权限；权限目录 | |
| space; default space | 空间；默认空间 | |
| role; role grant | 角色；角色授权 | |
| policy; global policy; principal policy | 策略；全局策略；主体策略 | |
| statement; action; condition; part | 语句；动作；条件；part | Keep "part" in English: it names a path such as `request.path`. |
| effect: allow / deny | 效果：允许 / 拒绝 | `ALLOW`, `EXPLICIT_DENY`, `IMPLICIT_DENY` stay in English. |
| decision | 决策 | |
| evaluation order | 求值顺序 | |
| deny first | 拒绝优先 | |
| default deny | 默认拒绝 | |
| fail closed; fail open | 失败即拒绝；失败即放行 | Not the English phrase in Chinese prose. |
| trust boundary | 信任边界 | |
| store of record | 权威存储 | Redis for policies and permissions. |
| local copy | 本地副本 | CoCache's in-process copy of a Redis value. |
| global policy index | 全局策略索引 | |
| schema marker | schema 标记 | |
| security context | 安全上下文 | |
| request attribute | 请求属性 | |
| path variable; template | 路径变量；模板 | |
| rate limit rule; permit | 限流规则；许可 | |
| shadow mode; candidate | 影子模式；候选 | |
| explainer, explanation | explain、解释 | The API is named `explain`. |
| audit event; decision chain; authorization chain | 审计事件；决策链；授权链 | |
| inject mode | 注入模式 | `cosec.inject.enabled` with authorization off. |

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
