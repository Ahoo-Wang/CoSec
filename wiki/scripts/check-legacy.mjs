// Guards the docs against APIs, properties and versions that 8.0 removed (RFC 0008 G4), so a page cannot teach
// something that no longer exists. It scans wiki/, skills/, README*.md, llms.txt, SECURITY.md and k8s/ for
// whole-word mentions of the terms in TERMS.
//
// Two exceptions, both explicit:
// - EXEMPT_SECTIONS: the 7.x to 8.0 section of the upgrading page, which describes the migration and so has to
//   name what it migrates from. A section is found by its heading anchor and runs to the next heading of the
//   same or a higher level.
// - ALLOWED: a deliberate contrast with 7.x elsewhere, one file and one term at a time, with the reason. An entry
//   that matches nothing fails the check, so the list cannot outlive the text it excuses.
// There is no inline escape. Prefer rewriting a mention over allowing it.
//
// Usage: node scripts/check-legacy.mjs   (from the wiki directory or via its path)
import { readFileSync, readdirSync, statSync } from 'fs'
import { join, relative, sep } from 'path'
import { fileURLToPath } from 'url'
import { headingAnchor } from './headings.mjs'

const repoDir = fileURLToPath(new URL('../..', import.meta.url)).replace(/\/$/, '')
const self = fileURLToPath(import.meta.url)

const HINT_PIPELINE = 'removed in 8.0: the pipeline in `cosec-runtime` decides (architecture/overview.md)'
const HINT_CONDITIONS = 'removed in 8.0: conditions are a fixed AST (policies/language.md); new facts come from an `AttributeProvider`'
const HINT_BREAK_GLASS = 'removed in 8.0: root became a break-glass binding (guide/security-model.md)'
const HINT_EXPRESSIONS = 'no expression language in 8.0: conditions are a fixed AST (policies/language.md)'

// Matched as whole words, case-sensitively unless `ignoreCase`. The hint says what to write instead.
const TERMS = [
  { term: 'SimpleAuthorization', hint: HINT_PIPELINE },
  { term: 'ActionMatcherFactory', hint: 'removed in 8.0: actions are built in (policies/language.md)' },
  { term: 'ConditionMatcherFactory', hint: HINT_CONDITIONS },
  { term: 'BlockingAware', hint: 'removed in 8.0: no condition runs code, and the data-plane SPIs suspend' },
  { term: 'RootPrincipalMatcher', hint: HINT_BREAK_GLASS },
  { term: 'root-enabled', hint: HINT_BREAK_GLASS },
  { term: 'init-repository', hint: 'removed in 8.0: documents come from `cosec-migrate` or the control plane (operations/upgrading.md)' },
  { term: 'ognl', ignoreCase: true, hint: HINT_EXPRESSIONS },
  { term: 'spel', ignoreCase: true, hint: HINT_EXPRESSIONS },
  { term: 'cosecExplain', hint: 'removed in 8.0: `explain` is a control-plane operation (integration/checks.md)' },
  { term: 'RolePermissionKeyMigration', hint: 'removed in 8.0 with the data migration from 6.x' },
  { term: 'CoSecSchemaMarker', hint: 'removed in 8.0: snapshot documents carry their own `schema`' },
  { term: 'cache.migration', hint: 'property removed in 8.0' },
  { term: 'CoCache 5', hint: '8.0 runs on CoCache 8; do not describe CoCache 5' },
  { term: '6.x', hint: '8.0 upgrades only from 7.x; leave 6.x to the docs on the `7.x` branch' },
  { term: 'StringIdGenerator', hint: 'CoSec takes its ids from `IdGenerator` (architecture/extending.md#ids)' },
  { term: 'me.ahoo.cosec.cache', hint: 'renamed in 8.0: `me.ahoo.cosec.cocache` (`cosec-cocache`)' },
  { term: 'me.ahoo.cosec.servlet', hint: 'renamed in 8.0: `me.ahoo.cosec.webmvc` (`cosec-webmvc`)' },
  { term: 'me.ahoo.cosec.spring.boot.starter', hint: 'renamed in 8.0: `me.ahoo.cosec.spring.boot` (`cosec-spring-boot`)' },
  { term: 'cosec-migrate cocache8', hint: 'removed at GA: an 8.0.0-alpha deployment moves its CoCache 6 data with the 8.0.0-alpha.4 tool (operations/upgrading.md)' },
  { term: 'UuidGenerator', hint: 'CoSec takes its ids from `IdGenerator` (architecture/extending.md#ids)' },
  // R3 to R9 (8.0 batches): what they removed or renamed.
  { term: 'KafkaAuditEventSink', hint: 'removed in 8.0 (R3): Kafka audit is `KafkaAuditListener` (operations/observability.md)' },
  { term: 'SimpleSecurityContext', hint: 'removed in 8.0 (R3): the adapter binds the pipeline\'s `SecurityContext` (integration/web.md)' },
  { term: 'RemovedKeyPrefixes', hint: 'removed in 8.0 (R6) with the 7.x key table' },
  { term: 'PolicyType', hint: 'removed in 8.0 (R7a): the audit record names the binding (`authorization.source.via`)' },
  { term: 'RoleCapable', hint: 'removed in 8.0 (R7b): a principal\'s roles are its memberships (integration/authentication.md)' },
  { term: 'RevocableTokenVerifier', hint: 'removed in 8.0: revocation is read through `TokenRevocation` and a `RevocationReader`' },
  { term: 'TokenStore.NoOp', hint: 'removed in 8.0: revocations are read through a `RevocationReader`' },
  { term: 'SnapshotVersion', hint: 'removed in 8.0: a decision event carries a `ViewVersion` (`me.ahoo.cosec.engine`)' },
  { term: 'CoSec-App-Id', hint: 'removed in 8.0: the credential declares the app (integration/authentication.md#apps)' },
  { term: 'ApiKeyStore', hint: 'removed in 8.0 (R9): read keys through `ApiKeyReader`; manage them with `InMemoryApiKeyStore` or `RedisApiKeyStore`' },
  { term: 'FlatTrace', hint: 'renamed in 8.0 (R9): `EvaluationTrace` (`me.ahoo.cosec.engine.trace`)' },
  { term: 'me.ahoo.cosec.engine.flat', hint: 'renamed in 8.0 (B3): the engine is `me.ahoo.cosec.engine`' },
  { term: 'FlatEngine', hint: 'renamed in 8.0 (B3): `Engine` (`me.ahoo.cosec.engine`)' },
  { term: 'CoSecFlatEngine', hint: 'renamed in 8.0 (B3): `CoSecEngine`' },
  { term: 'FlatRequest', hint: 'renamed in 8.0 (B3): `EngineRequest`' },
  { term: 'FlatEvaluator', hint: 'renamed in 8.0 (B3): `Evaluator`' },
  { term: 'FlatEvaluation', hint: 'renamed in 8.0 (B3): `Evaluation`' },
  { term: 'FlatAuthorizationBenchmark', hint: 'renamed in 8.0 (B3): `EngineAuthorizationBenchmark`' },
  { term: 'FlatEngineBenchmark', hint: 'renamed in 8.0 (B3): `EngineBenchmark`' },
  { term: 'FlatPlanBenchmark', hint: 'renamed in 8.0 (B3): `AttributePlanBenchmark`' },
  { term: 'FlatBenchmarkSupport', hint: 'renamed in 8.0 (B3): `EngineBenchmarkSupport`' },
  { term: 'supportCredentials', hint: 'renamed in 8.0 (R9): `Authentication.credentialsType`' },
  { term: 'AbstractRefreshTokenAuthentication', hint: 'removed in 8.0 (R9): `RefreshTokenAuthentication` implements `Authentication` itself' },
  { term: 'PipelineWebFilterKt', hint: 'renamed in 8.0 (R9): `checkMono` is in `AuthorizationCheckers` (`cosec-webflux`)' },
  { term: 'PipelineServletFilterKt', hint: 'renamed in 8.0 (R9): `checkBlocking` is in `AuthorizationCheckers` (`cosec-webmvc`)' },
  // A1 (RFC 0013 §3): packages that moved to cosec-document.
  { term: 'me.ahoo.cosec.runtime.document', hint: 'moved in 8.0 (A1): `me.ahoo.cosec.document` (`cosec-document`)' },
  { term: 'me.ahoo.cosec.runtime.route', hint: 'moved in 8.0 (A1): `me.ahoo.cosec.document.route` (`cosec-document`)' },
  // A3 (RFC 0013 §7.3): names that went.
  { term: 'SimpleCompositeToken', hint: 'removed in 8.0 (A3): `CompositeToken` (`cosec-api`) is a final class' },
  { term: 'SimpleRefreshTokenAuthentication', hint: 'renamed in 8.0 (A3): `RefreshTokenAuthentication` (`cosec-login`)' },
  { term: 'DefaultTokenRevoker', hint: 'renamed in 8.0 (A3): `StoreTokenRevoker` (`cosec-login`)' },
  { term: 'AbstractSwitchTenantAuthentication', hint: 'removed in 8.0 (A3): `SwitchTenantAuthentication` with a `TenantSwitch` (integration/authentication.md)' },
  { term: 'me.ahoo.cosec.api.CoSec', hint: 'removed in 8.0 (A3): each value has a name where it means something (operations/upgrading.md)' },
  { term: 'CoSec.COSEC', hint: 'removed in 8.0 (A3): the property prefix is `cosec`, the default key prefix `cosec` (`cosec.key-prefix`)' },
  { term: 'CoSec.COSEC_PREFIX', hint: 'removed in 8.0 (A3): write the `cosec.` prefix of a property or attribute out' },
  { term: 'CoSec.DEFAULT', hint: 'removed in 8.0 (A3): `CoSecPrincipal.ANONYMOUS_ID` or `TenantId.DEFAULT`, two ids spelled `(0)`' },
]

const EXEMPT_SECTIONS = [
  { file: 'wiki/operations/upgrading.md', anchor: '_7-x-to-8-0' },
  { file: 'wiki/zh/operations/upgrading.md', anchor: '_7-x-to-8-0' },
]

const ALLOWED = [
  {
    files: ['skills/cosec-custom-matcher/SKILL.md'],
    terms: ['ActionMatcherFactory', 'ConditionMatcherFactory'],
    reason: 'tells the reader that 7.x custom matchers have no 8.0 equivalent',
  },
  {
    files: ['skills/cosec-policy-author/SKILL.md'],
    terms: ['spel', 'ognl'],
    reason: 'states that conditions have no expression language ("no SpEL, no OGNL")',
  },
  {
    files: ['wiki/operations/upgrading.md', 'wiki/zh/operations/upgrading.md'],
    terms: ['me.ahoo.cosec.cache', 'me.ahoo.cosec.servlet', 'me.ahoo.cosec.spring.boot.starter'],
    reason: 'the upgrade entry that renames the three packages names the old ones',
  },
  {
    files: ['wiki/operations/upgrading.md', 'wiki/zh/operations/upgrading.md'],
    terms: ['me.ahoo.cosec.runtime.document', 'me.ahoo.cosec.runtime.route'],
    reason: 'the upgrade entry that moves them to cosec-document names the old packages',
  },
  {
    files: ['wiki/operations/upgrading.md', 'wiki/zh/operations/upgrading.md'],
    terms: [
      'SimpleCompositeToken', 'SimpleRefreshTokenAuthentication', 'DefaultTokenRevoker', 'CoSec.COSEC',
      'AbstractSwitchTenantAuthentication',
      'CoSec.COSEC_PREFIX', 'CoSec.DEFAULT',
    ],
    reason: 'the A3 upgrade entries, and the earlier preview entries, name the old classes',
  },
  {
    files: ['wiki/operations/upgrading.md', 'wiki/zh/operations/upgrading.md'],
    terms: [
      'KafkaAuditEventSink', 'SimpleSecurityContext', 'PolicyType', 'RoleCapable', 'RevocableTokenVerifier',
      'TokenStore.NoOp', 'SnapshotVersion', 'CoSec-App-Id', 'ApiKeyStore', 'FlatTrace', 'supportCredentials',
      'AbstractRefreshTokenAuthentication', 'PipelineWebFilterKt', 'PipelineServletFilterKt',
    ],
    reason: 'the 8.0.0-alpha upgrade entries that remove or rename them name the old ones',
  },
  {
    files: ['wiki/operations/upgrading.md', 'wiki/zh/operations/upgrading.md'],
    terms: ['me.ahoo.cosec.engine.flat'],
    reason: 'the R9 entry that renames FlatTrace names it where it was',
  },
  {
    files: ['wiki/operations/upgrading.md', 'wiki/zh/operations/upgrading.md'],
    terms: ['cosec-migrate cocache8'],
    reason: 'the alpha.2 section says how to move CoCache 6 data with the 8.0.0-alpha.4 tool, and the entry that removes the command names it',
  },
  {
    files: ['wiki/guide/roadmap.md', 'wiki/zh/guide/roadmap.md'],
    terms: ['ognl', 'spel'],
    reason: 'the roadmap names what the typed condition AST replaced',
  },
]

const SKIP_DIRS = new Set(['node_modules', 'dist', '.git'])
const SKIP_PATHS = new Set(['wiki/.vitepress/cache'])
const SKIP_FILES = new Set([self, join(repoDir, 'wiki/pnpm-lock.yaml')])

const posix = (file) => relative(repoDir, file).split(sep).join('/')

function walk(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      return SKIP_DIRS.has(entry) || SKIP_PATHS.has(posix(full)) ? [] : walk(full)
    }
    return SKIP_FILES.has(full) ? [] : [full]
  })
}

const scanned = [
  ...['wiki', 'skills', 'k8s'].flatMap((dir) => walk(join(repoDir, dir))),
  ...readdirSync(repoDir)
    .filter((name) => /^README.*\.md$/.test(name) || name === 'llms.txt' || name === 'SECURITY.md')
    .map((name) => join(repoDir, name)),
]

// Whole word: no ASCII letter, digit or underscore next to it ("misspelled" is not "spel"). A version such as
// `6.x` also must not follow a dot, or `1.6.x` would match.
const matchers = TERMS.map(({ term, ignoreCase, hint }) => {
  const lead = /^\d/.test(term) ? '(?<![\\w.])' : '(?<!\\w)'
  const pattern = lead + term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?!\\w)'
  return { term, hint, regex: new RegExp(pattern, ignoreCase ? 'i' : '') }
})

const problems = []

// The 1-based lines of a section, from its heading to just before the next heading of the same or a higher level.
function sectionLines(file, lines, anchor) {
  let inFence = false
  let level = 0
  let start = 0
  for (let i = 0; i < lines.length; i++) {
    if (/^\s*```/.test(lines[i])) inFence = !inFence
    const heading = inFence ? null : /^(#{1,6})\s+(.*?)\s*$/.exec(lines[i])
    if (!heading) continue
    if (!start && headingAnchor(heading[2]) === anchor) {
      start = i + 1
      level = heading[1].length
    } else if (start && heading[1].length <= level) {
      return { start, end: i }
    }
  }
  if (!start) problems.push(`${file}: exempt section #${anchor} not found; update EXEMPT_SECTIONS in wiki/scripts/check-legacy.mjs`)
  return start ? { start, end: lines.length } : null
}

const allowed = new Map()
for (const { files, terms, reason } of ALLOWED) {
  for (const file of files) for (const term of terms) allowed.set(`${file}\0${term}`, { reason, used: false })
}

const exempt = new Map(EXEMPT_SECTIONS.map(({ file, anchor }) => [file, anchor]))
const hits = []
let checked = 0

for (const file of scanned) {
  const buffer = readFileSync(file)
  if (buffer.includes(0)) continue
  checked++
  const name = posix(file)
  const lines = buffer.toString('utf-8').split('\n')
  const section = exempt.has(name) ? sectionLines(name, lines, exempt.get(name)) : null
  lines.forEach((line, index) => {
    const number = index + 1
    if (section && number >= section.start && number <= section.end) return
    for (const { term, hint, regex } of matchers) {
      if (!regex.test(line)) continue
      const entry = allowed.get(`${name}\0${term}`)
      if (entry) entry.used = true
      else hits.push({ name, number, term, hint })
    }
  })
}

for (const [key, entry] of allowed) {
  if (!entry.used) {
    const [file, term] = key.split('\0')
    problems.push(`${file}: ALLOWED \`${term}\` (${entry.reason}) matches nothing; remove the entry from wiki/scripts/check-legacy.mjs`)
  }
}
hits.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : a.number - b.number))
for (const { name, number, term, hint } of hits) problems.push(`${name}:${number}: \`${term}\`, ${hint}`)

console.log(`${checked} files scanned for APIs removed in 8.0`)
if (problems.length) {
  console.error(problems.map((p) => `  ❌ ${p}`).join('\n'))
  console.error(
    '  Rewrite the mention. Only migration text belongs in the `7.x to 8.0` section of operations/upgrading.md;\n' +
      '  a deliberate contrast with 7.x goes into ALLOWED in wiki/scripts/check-legacy.mjs, with a reason.',
  )
  process.exit(1)
}
console.log('✅ No mention of APIs removed in 8.0.')
