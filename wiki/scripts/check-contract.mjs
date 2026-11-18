// Holds the claims table of integration/clients.md, in English and in Chinese, to contract/token/claims.json (PIP-25),
// which the server's tests write from what its tokens carry. A claim the contract has and the table lacks, a claim
// the table lists and the contract does not, a claim the table calls "do not depend on" that the contract says a
// client may read (or the other way round), and a claim the table gives a "when it is missing" cell that the contract
// says is always there (or the other way round) each fail the check.
//
// It lives here and not in the Gradle conformance tests because the table is a page of the wiki, the `Docs` job reads
// the wiki and `contract/` on every pull request with no JVM, and the conformance suite has no reason to parse prose.
// What a client does with the claims is tested against the same file in typescript/cosec-fetcher.
//
// Usage: node scripts/check-contract.mjs   (from the wiki directory or via its path)
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { join } from 'path'
import { headingAnchor } from './headings.mjs'

const repoDir = fileURLToPath(new URL('../..', import.meta.url)).replace(/\/$/, '')
const claims = JSON.parse(readFileSync(join(repoDir, 'contract/token/claims.json'), 'utf-8'))

const PAGES = [
  // The anchor of the section, and what its "do not depend on" rows begin with.
  { file: 'wiki/integration/clients.md', anchor: 'the-claims-a-client-may-read', doNotDepend: 'Nothing:' },
  { file: 'wiki/zh/integration/clients.md', anchor: 'the-claims-a-client-may-read', doNotDepend: '不要用：' },
]

/**
 * Every claim of the contract, by name. The table is about the access token ("the claims of an access token"), so an
 * access claim is that rule; a claim only the refresh token carries (`cosec.token`) is its rule.
 */
function contractClaims() {
  const byName = new Map()
  for (const kind of ['refresh', 'access']) {
    for (const [name, rule] of Object.entries(claims[kind])) byName.set(name, { ...rule, kind })
  }
  return byName
}

/** The rows of the table under the heading with [anchor]: the claim names, the use cell and the missing cell. */
function tableRows(text, anchor) {
  const lines = text.split('\n')
  const start = lines.findIndex((line) => {
    const heading = /^#{1,6}\s+(.*?)\s*$/.exec(line)
    return heading && headingAnchor(heading[1]) === anchor
  })
  if (start < 0) return null
  const rows = []
  let inTable = false
  for (const line of lines.slice(start + 1)) {
    if (/^#{1,6}\s/.test(line)) break
    if (!line.startsWith('|')) {
      if (inTable) break
      continue
    }
    inTable = true
    const cells = line.replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim())
    if (cells.length !== 3 || /^-+$/.test(cells[0].replace(/\s/g, '')) || /^:?-+:?$/.test(cells[0])) continue
    const names = [...cells[0].matchAll(/`([^`]+)`/g)].map((match) => match[1])
    if (names.length > 0) rows.push({ names, use: cells[1], missing: cells[2] })
  }
  return rows
}

const problems = []
const expected = contractClaims()

for (const { file, anchor, doNotDepend } of PAGES) {
  const rows = tableRows(readFileSync(join(repoDir, file), 'utf-8'), anchor)
  if (!rows) {
    problems.push(`${file}: no section #${anchor}`)
    continue
  }
  const seen = new Set()
  for (const { names, use, missing } of rows) {
    const refuses = use.startsWith(doNotDepend)
    for (const name of names) {
      seen.add(name)
      const rule = expected.get(name)
      if (!rule) {
        problems.push(`${file}: the table lists ${name}, which contract/token/claims.json does not have`)
        continue
      }
      if (refuses !== (rule.client === 'do-not-depend')) {
        problems.push(`${file}: ${name} is "${rule.client}" in the contract, and the table says otherwise`)
      }
      // The "when it is missing" cell is for the claims a client reads: the others are not depended on.
      if (rule.client === 'read') {
        const optional = missing !== ''
        if (optional !== (rule.presence === 'optional')) {
          problems.push(
            `${file}: ${name} is "${rule.presence}" in the contract, and the table's "when it is missing" cell is ${optional ? 'filled' : 'empty'}`,
          )
        }
      }
    }
  }
  for (const name of expected.keys()) {
    if (!seen.has(name)) problems.push(`${file}: the table lacks ${name}, which contract/token/claims.json has`)
  }
}

if (problems.length > 0) {
  console.error('❌ The claims table and contract/token/claims.json disagree:')
  for (const problem of problems) console.error(`  ${problem}`)
  process.exit(1)
}
console.log(`✅ The claims tables (en, zh) hold the ${expected.size} claims of contract/token/claims.json.`)
