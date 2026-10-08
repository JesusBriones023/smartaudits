// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const thisFile = fileURLToPath(import.meta.url)
const src = dirname(dirname(thisFile))

// Deliberately independent of production CSS. Adding a token requires reviewing
// its v3 rule and exclusion as well as updating this contract, including variants.
const siblingTokens = [
  'space-x-1', 'space-x-1.5', 'space-x-2', 'space-x-3', 'space-x-4',
  'space-y-0.5', 'space-y-1', 'space-y-1.5', 'space-y-2', 'space-y-3',
  'space-y-4', 'space-y-5', 'space-y-6',
  'divide-x', 'divide-y', 'divide-gray-200', 'divide-slate-100',
  'sm:divide-x', 'sm:divide-y-0',
]
const whiteAlphaTokens = [
  'bg-white/5', 'bg-white/10', 'bg-white/15',
  'text-white/40', 'text-white/50', 'text-white/60', 'text-white/70',
]
const reviewed = new Set([...siblingTokens, ...whiteAlphaTokens])

function unsupportedTokens(text) {
  // Class-like literals, retaining the entire variant chain (not just the base).
  // No JSX parser is needed: an unknown candidate fails conservatively.
  const tokens = text.match(/(?:[^\s"'`{}(),;]+:)*!?-?(?:space-[xy]-|divide-|(?:text|bg|border)-white\/)[^\s"'`{}(),;<>]+/g) || []
  return [...new Set(tokens)].filter(token => !reviewed.has(token))
}

function sourceFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(file)
    // Tests contain deliberate negative examples, not production UI classes.
    return /\.(?:[cm]?[jt]sx?|css|html)$/.test(file) && !/\.(?:test|spec)\./.test(file)
      ? [file] : []
  })
}

describe('Tailwind v3 compatibility contract', () => {
  it('rejects unreviewed spacing, divider and white-opacity tokens in real src files', () => {
    const files = sourceFiles(src)
    expect(files.length).toBeGreaterThan(0)
    expect(files).not.toContain(thisFile)
    const violations = files.flatMap(file => {
      const source = readFileSync(file, 'utf8')
      // CSS selectors declare the compatibility rules; only @apply consumes tokens.
      const content = extname(file) === '.css'
        ? [...source.matchAll(/@apply\s+([^;]+);/g)].map(match => match[1]).join(' ')
        : source
      return unsupportedTokens(content).map(token => `${relative(src, file)}: ${token}`)
    })
    expect(violations, 'Review v3 compatibility before adding these tokens').toEqual([])
  })

  it('keeps the production exclusion list synchronized with the reviewed contract', () => {
    const css = readFileSync(join(src, 'index.css'), 'utf8')
    const exclusion = css.match(/@source not in[l]ine\(['"]([^'"]+)['"]\)/)
    expect(exclusion).not.toBeNull()
    expect(exclusion[1].trim().split(/\s+/).sort()).toEqual([...siblingTokens].sort())
  })

  it('accepts all reviewed tokens alongside unrelated utilities', () => {
    expect(unsupportedTokens(`flex gap-4 ${[...reviewed].join(' ')} shadow-sm`)).toEqual([])
  })

  // Assemble negative examples so Tailwind's src scanner cannot emit them into
  // the production build merely because this regression test exists.
  it.each([
    ['md:', ['space', 'y', '8']],
    ['lg:', ['space', 'x', '2']],
    ['hover:', ['space', 'y', '4']],
    ['md:hover:', ['divide', 'y']],
    ['', ['divide', 'gray', '100']],
    ['md:', ['divide', 'gray', '200']],
    ['', ['bg', 'white/20']],
    ['hover:', ['text', 'white/60']],
    ['', ['border', 'white/10']],
    ['[&>*]:', ['space', 'y', '2']],
    ['', ['space', 'y', '[3px]']],
  ])('detects a new token with prefix "%s" and parts %j', (prefix, parts) => {
    const token = prefix + parts.join('-')
    expect(unsupportedTokens(`<div className="flex ${token} p-4" />`)).toEqual([token])
  })
})
