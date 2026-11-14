import { inflateSync } from 'node:zlib'

// Test-only subset emitted by SmartAudits: direct /Length, a single /FlateDecode,
// optional /DecodeParms null, LF/CRLF delimiters, and WinAnsi TJ/Tj text. Key order
// and comments do not matter. Indirect lengths, filter arrays, non-null parameters
// and other stream entries/encodings are rejected, not silently skipped. This is
// not a PDF validator: it does not resolve xrefs, page trees or font encodings.
const MAX_INPUT = 16 * 1024 * 1024
const MAX_DECODED = 32 * 1024 * 1024
const white = c => c !== undefined && '\0\t\n\f\r '.includes(c)
const boundary = c => c === undefined || white(c) || '()<>[]{}/%'.includes(c)
const invalid = detail => { throw new Error(`Invalid or unsupported PDF: ${detail}`) }

// A small lexical reader keeps strings, comments and nested values opaque to the
// outer scanner. Its cursor only advances; lookahead tokens are retained in order.
class Reader {
  constructor(raw) { this.raw = raw; this.cursor = 0; this.pending = [] }
  peek(index = 0) {
    while (this.pending.length <= index) this.pending.push(this.token())
    return this.pending[index]
  }
  take() { return this.pending.length ? this.pending.shift() : this.token() }
  token() {
    const raw = this.raw
    while (this.cursor < raw.length) {
      if (white(raw[this.cursor])) this.cursor++
      else if (raw[this.cursor] === '%') {
        while (this.cursor < raw.length && !/[\r\n]/.test(raw[this.cursor])) this.cursor++
      } else break
    }
    if (this.cursor === raw.length) return { kind: 'eof' }
    const start = this.cursor++
    const c = raw[start]
    if (c === '(') {
      let value = '', depth = 1
      while (this.cursor < raw.length) {
        let ch = raw[this.cursor++]
        if (ch === '\\') {
          if (this.cursor === raw.length) invalid('unterminated literal string')
          ch = raw[this.cursor++]
          if (ch === '\r' || ch === '\n') {
            if (ch === '\r' && raw[this.cursor] === '\n') this.cursor++
          } else if (/[0-7]/.test(ch)) {
            let octal = ch
            while (octal.length < 3 && /[0-7]/.test(raw[this.cursor] ?? '')) octal += raw[this.cursor++]
            value += String.fromCharCode(parseInt(octal, 8) & 255)
          } else value += ({ n: '\n', r: '\r', t: '\t', b: '\b', f: '\f' })[ch] ?? ch
        } else if (ch === ')' && --depth === 0) return { kind: 'string', value }
        else {
          if (ch === '(' && ++depth > 32) invalid('literal nesting limit')
          if (ch === '\r') { if (raw[this.cursor] === '\n') this.cursor++; ch = '\n' }
          value += ch
        }
      }
      invalid('unterminated literal string')
    }
    if ((c === '<' || c === '>') && raw[this.cursor] === c) {
      this.cursor++
      return { kind: c === '<' ? 'dict' : 'dictEnd' }
    }
    if (c === '<') {
      let hex = ''
      while (this.cursor < raw.length && raw[this.cursor] !== '>') {
        const ch = raw[this.cursor++]
        if (!white(ch)) { if (!/[0-9a-f]/i.test(ch)) invalid('hexadecimal string'); hex += ch }
      }
      if (this.cursor === raw.length) invalid('unterminated hexadecimal string')
      this.cursor++
      return { kind: 'string', value: Buffer.from(hex.length % 2 ? hex + '0' : hex, 'hex').toString('latin1') }
    }
    if (c === '[' || c === ']') return { kind: c === '[' ? 'array' : 'arrayEnd' }
    if ('>){}'.includes(c)) invalid(`unexpected delimiter at byte ${start}`)
    while (!boundary(raw[this.cursor])) this.cursor++
    const value = raw.slice(c === '/' ? start + 1 : start, this.cursor)
    if (c === '/') {
      if (/#(?![0-9a-f]{2})/i.test(value)) invalid('name escape')
      return { kind: 'name', value: value.replace(/#([0-9a-f]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16))) }
    }
    return /^[+-]?(?:\d+\.?\d*|\.\d+)$/.test(value)
      ? { kind: 'number', value: Number(value), integer: /^[+-]?\d+$/.test(value) }
      : { kind: 'word', value }
  }
  value(depth = 0) {
    if (depth > 32) invalid('collection nesting limit')
    const token = this.take()
    if (token.kind === 'dict') {
      const entries = new Map()
      while (this.peek().kind !== 'dictEnd') {
        const key = this.take()
        if (key.kind !== 'name' || entries.has(key.value)) invalid('dictionary key or duplicate entry')
        entries.set(key.value, this.value(depth + 1))
      }
      this.take()
      return { kind: 'dict', entries }
    }
    if (token.kind === 'array') {
      const items = []
      while (this.peek().kind !== 'arrayEnd') items.push(this.value(depth + 1))
      this.take()
      return { kind: 'array', items }
    }
    if (['eof', 'dictEnd', 'arrayEnd'].includes(token.kind)) invalid('incomplete value')
    if (token.kind === 'number' && this.peek().kind === 'number'
      && this.peek(1).kind === 'word' && this.peek(1).value === 'R') {
      this.take(); this.take()
      return { kind: 'reference' }
    }
    return token
  }
}

function textLines(content) {
  const reader = new Reader(content)
  const decoder = new TextDecoder('windows-1252')
  const lines = []
  let previous
  while (reader.peek().kind !== 'eof') {
    const value = reader.value()
    if (value.kind === 'word' && ['TJ', 'Tj'].includes(value.value)) {
      const parts = value.value === 'TJ' && previous?.kind === 'array' ? previous.items
        : value.value === 'Tj' && previous?.kind === 'string' ? [previous] : invalid('text operand')
      if (parts.some(p => !['string', 'number'].includes(p.kind))) invalid('text array')
      lines.push(parts.filter(p => p.kind === 'string').map(p => decoder.decode(Buffer.from(p.value, 'latin1'))).join(''))
    } else if (value.kind === 'word' && ['BI', "'", '"'].includes(value.value)) invalid('unsupported content operator')
    previous = value
  }
  return lines
}

export function extractPdfText(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length > MAX_INPUT) invalid('expected a Buffer of at most 16 MiB')
  // Latin-1 is one code unit per original byte, so all cursor offsets stay exact.
  const reader = new Reader(bytes.toString('latin1'))
  const lines = []
  let decoded = 0
  while (reader.peek().kind !== 'eof') {
    const value = reader.value()
    if (value.kind !== 'dict' || reader.peek().kind !== 'word' || reader.peek().value !== 'stream') {
      if (value.kind === 'word' && ['stream', 'endstream'].includes(value.value)) invalid('stream without a dictionary')
      continue
    }
    reader.take()
    const entries = value.entries
    for (const key of entries.keys()) if (!['Length', 'Filter', 'DecodeParms'].includes(key)) invalid(`stream entry /${key}`)
    const length = entries.get('Length')
    if (length?.kind !== 'number' || !length.integer || !Number.isSafeInteger(length.value) || length.value < 0) {
      invalid('direct nonnegative integer /Length required')
    }
    const filter = entries.get('Filter')
    if (filter?.kind !== 'name' || filter.value !== 'FlateDecode') invalid('single /FlateDecode required')
    const params = entries.get('DecodeParms')
    if (params && !(params.kind === 'word' && params.value === 'null')) invalid('/DecodeParms must be null')
    const eol = reader.raw.startsWith('\r\n', reader.cursor) ? 2 : reader.raw[reader.cursor] === '\n' ? 1 : 0
    if (!eol) invalid('LF or CRLF required after stream')
    const start = reader.cursor + eol
    if (length.value > bytes.length - start) invalid('Invalid PDF stream length: truncated payload')
    const end = start + length.value
    const terminator = /^(?:\r\n|\n)endstream/.exec(reader.raw.slice(end, end + 11))
    if (!terminator || !boundary(reader.raw[end + terminator[0].length])) invalid('Invalid PDF stream length or terminator')
    // Skip the exact encoded payload before looking for another dictionary. The
    // terminator is checked only at /Length; a final compressed 0x0D stays intact.
    reader.cursor = end + terminator[0].length
    if (decoded === MAX_DECODED) invalid('decoded stream limit (32 MiB total)')
    let inflated
    try {
      inflated = inflateSync(bytes.subarray(start, end), { info: true, maxOutputLength: MAX_DECODED - decoded })
    } catch (cause) { throw new Error(`Invalid PDF Flate stream at byte ${start}: ${cause.code}`, { cause }) }
    if (inflated.engine.bytesWritten !== length.value) invalid('compressed bytes remain after Flate end')
    decoded += inflated.buffer.length
    for (const line of textLines(inflated.buffer.toString('latin1'))) lines.push(line)
  }
  if (lines.length === 0) throw new Error('No supported PDF text found')
  return lines.join('\n')
}
