import { readFileSync, readdirSync, statSync } from 'fs'
import { join, extname } from 'path'

const wikiDir = new URL('../..', import.meta.url).pathname.replace(/\/$/, '')

function walk(dir) {
  let files = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (entry === 'node_modules' || entry === '.vitepress') continue
    const stat = statSync(full)
    if (stat.isDirectory()) files = files.concat(walk(full))
    else if (extname(entry) === '.md') files.push(full)
  }
  return files
}

function extractMermaidBlocks(content) {
  const blocks = []
  const regex = /```mermaid\s*\n([\s\S]*?)```/g
  let match
  while ((match = regex.exec(content)) !== null) {
    blocks.push(match[1].trim())
  }
  return blocks
}

function detectDiagramType(block) {
  const firstLine = block.split('\n')[0].trim()
  if (firstLine.startsWith('sequenceDiagram')) return 'sequence'
  if (firstLine.startsWith('classDiagram')) return 'class'
  if (firstLine.startsWith('flowchart') || firstLine.startsWith('graph')) return 'flowchart'
  if (firstLine.startsWith('erDiagram')) return 'er'
  if (firstLine.startsWith('stateDiagram')) return 'state'
  if (firstLine.startsWith('pie')) return 'pie'
  if (firstLine.startsWith('gantt')) return 'gantt'
  return 'unknown'
}

const issues = []
let totalBlocks = 0

for (const file of walk(wikiDir)) {
  const content = readFileSync(file, 'utf-8')
  const blocks = extractMermaidBlocks(content)
  const relPath = file.replace(wikiDir + '/', '')

  for (let i = 0; i < blocks.length; i++) {
    totalBlocks++
    const block = blocks[i]
    const errors = []
    const diagramType = detectDiagramType(block)

    // Check 1: <br/> instead of <br>
    if (block.includes('<br/>')) {
      errors.push(`  ❌ Uses <br/> (use <br> instead)`)
    }

    // Check 2: Reserved words as participant names (sequence/class only)
    if (diagramType === 'sequence') {
      const participantNames = []
      const participantRegex = /participant\s+(\w+)\s+as\s/g
      let pm
      while ((pm = participantRegex.exec(block)) !== null) {
        participantNames.push(pm[1])
      }
      const directParticipantRegex = /^participant\s+(\w+)\s*$/gm
      while ((pm = directParticipantRegex.exec(block)) !== null) {
        participantNames.push(pm[1])
      }

      const reservedWords = ['and', 'or', 'not', 'as', 'is', 'loop', 'alt', 'else', 'end', 'opt', 'par', 'critical', 'break', 'rect', 'note', 'rec', 'autonumber', 'title']
      for (const name of participantNames) {
        if (reservedWords.includes(name.toLowerCase())) {
          errors.push(`  ❌ Participant "${name}" is a Mermaid reserved word`)
        }
      }
    }

    // Check 3: Unmatched subgraph/end (flowchart only)
    if (diagramType === 'flowchart') {
      const lines = block.split('\n')
      let inSubgraph = 0
      for (const line of lines) {
        if (/^\s*subgraph\b/.test(line)) inSubgraph++
        if (/^\s*end\s*$/.test(line)) inSubgraph--
      }
      if (inSubgraph !== 0) {
        errors.push(`  ❌ Unmatched subgraph/end (${inSubgraph > 0 ? 'missing end' : 'extra end'})`)
      }
    }

    // Check 4: style/classDef/class styling directives - only allowed in certain diagram types
    //   style:    only flowchart/graph
    //   classDef: flowchart/graph + stateDiagram
    //   class:    flowchart/graph + stateDiagram (styling only, not "class Name {" definitions)
    const hasStyle = /^\s*style\s+\S+\s+/gm.test(block)
    const hasClassDef = /^\s*classDef\b/m.test(block)
    // Match "class A,B,C styleName" or "class A styleName" but NOT "class Name {"
    const hasClassDir = /^\s*class\s+[\w,]+\s+\w+\s*$/m.test(block)

    if (hasStyle && diagramType !== 'flowchart' && diagramType !== 'unknown') {
      errors.push(`  ❌ "style" directive not allowed in ${diagramType} diagrams`)
    }
    if ((hasClassDef || hasClassDir) && !['flowchart', 'unknown', 'state'].includes(diagramType)) {
      errors.push(`  ❌ "classDef"/"class" styling directive not allowed in ${diagramType} diagrams`)
    }

    // Check 5: Unmatched sequence blocks (loop/alt/opt/par/critical/break)
    if (diagramType === 'sequence') {
      const lines = block.split('\n')
      let depth = 0
      for (const line of lines) {
        const trimmed = line.trim()
        if (/^(loop|alt|opt|par|critical|break|rect)\b/.test(trimmed)) depth++
        if (trimmed === 'end') depth--
      }
      if (depth !== 0) {
        errors.push(`  ❌ Unmatched sequence block (loop/alt/etc): depth=${depth}`)
      }
    }

    // Check 6: classDiagram - orphaned class body (<<stereotype>> without preceding "class Name {")
    if (diagramType === 'class') {
      const lines = block.split('\n')
      for (let li = 0; li < lines.length; li++) {
        const t = lines[li].trim()
        if (t.startsWith('<<') && (t === '<<interface>>' || t === '<<enumeration>>' || t === '<<abstract>>')) {
          const prev = li > 0 ? lines[li - 1].trim() : ''
          if (!prev.endsWith('{')) {
            errors.push(`  ❌ Orphaned ${t} body at line ${li + 1} (missing "class Name {" header)`)
          }
        }
      }
    }

    // Check 7: classDiagram - compound expressions inside class body
    if (diagramType === 'class') {
      const classBodies = block.match(/class\s+\w+\s*\{([^}]+)\}/g) || []
      for (const body of classBodies) {
        const inner = body.match(/\{([^}]*)\}/)?.[1] || ''
        for (const line of inner.split('\n')) {
          const t = line.trim()
          if (t && !t.startsWith('<<') && !t.startsWith('+') && !t.startsWith('-') && !t.startsWith('#') && !t.startsWith('~') && !t.startsWith('%') && !t.startsWith('$')) {
            // Check for relation-like syntax in class body (e.g. "ClassA + ClassB")
            if (/\w+\s*[+\-]\s*\w+/.test(t) && !/[:\(\)]/.test(t)) {
              errors.push(`  ❌ classDiagram: "${t}" inside class body is invalid (use relation lines outside)`)
            }
          }
        }
      }
    }

    // Check 6: Light-mode colors
    if (/fill:#fff|fill:#ffffff|fill:#f[0-9a-f]{5}(?!f)/i.test(block)) {
      errors.push(`  ⚠️  Uses light-mode fill colors`)
    }

    // Check 6: Missing autonumber in sequence diagrams
    if (diagramType === 'sequence' && !block.includes('autonumber')) {
      errors.push(`  ⚠️  sequenceDiagram missing autonumber`)
    }

    if (errors.length > 0) {
      issues.push(`\n📄 ${relPath} (block #${i + 1}, ${diagramType})`)
      issues.push(...errors)
    }
  }
}

if (issues.length > 0) {
  console.log(`\n🚨 Found issues in ${totalBlocks} Mermaid blocks:\n`)
  console.log(issues.join('\n'))
  process.exit(1)
} else {
  console.log(`\n✅ All ${totalBlocks} Mermaid blocks passed validation.\n`)
  process.exit(0)
}
