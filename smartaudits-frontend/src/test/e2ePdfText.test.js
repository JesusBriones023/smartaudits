// @vitest-environment node
import { deflateSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { extractPdfText } from '../../e2e/pdfText.mjs'

const hex = text => Buffer.from(text, 'latin1').toString('hex')
const drawn = text => `BT\n[<${hex(text)}>] TJ\nET\n`
const stream = (compressed, eol = '\n', length = compressed.length) => Buffer.concat([
  Buffer.from(`<<${eol}/Length ${length}${eol}/Filter /FlateDecode${eol}>>${eol}stream${eol}`),
  compressed,
  Buffer.from(`${eol}endstream${eol}`),
])

const withEntries = (compressed, entries, eol = '\n') => Buffer.concat([
  Buffer.from(`<<${eol}${entries}${eol}>>${eol}stream${eol}`), compressed, Buffer.from(`${eol}endstream${eol}`),
])
const page = text => stream(deflateSync(drawn(text)))

describe('E2E PDF text extraction', () => {
  it.each(['\n', '\r\n'])('preserves a final compressed CR byte before a %j delimiter', eol => {
    // Reproduce the lost matrix page: its Adler-32 checksum ended in 0x0D.
    const content = drawn('Cobertura documental') + '%' + 'a'.repeat(252)
    const compressed = deflateSync(content)
    expect(compressed.at(-1)).toBe(0x0D)
    expect(extractPdfText(stream(compressed, eol))).toBe('Cobertura documental')
  })

  it('reads every stream, joins text runs and preserves accents and scores', () => {
    const content = `BT\n[<${hex('Cómo se compone ')}> 12 <${hex('la puntuación')}>] TJ\n<${hex('19/100')}> Tj\nET\n`
    const bytes = Buffer.concat([
      stream(deflateSync(drawn('Documento analizado'))),
      stream(deflateSync(content)),
      stream(deflateSync(drawn('Limitaciones del análisis'))),
    ])
    expect(extractPdfText(bytes)).toBe('Documento analizado\nCómo se compone la puntuación\n19/100\nLimitaciones del análisis')
  })

  it('fails on a corrupt stream instead of silently omitting its page', () => {
    const compressed = deflateSync(drawn('Cobertura documental'))
    compressed[compressed.length - 1] ^= 1
    const bytes = Buffer.concat([stream(deflateSync(drawn('Documento 1'))), stream(compressed)])
    expect(() => extractPdfText(bytes)).toThrow()
  })

  it('rejects a truncated declared stream length', () => {
    const compressed = deflateSync(drawn('Cobertura documental'))
    expect(() => extractPdfText(stream(compressed, '\n', compressed.length - 1))).toThrow('Invalid PDF stream length')
  })

  it.each([
    ['Filter', 'Length', 'DecodeParms'], ['Length', 'Filter', 'DecodeParms'],
    ['Length', 'DecodeParms', 'Filter'], ['Filter', 'DecodeParms', 'Length'],
    ['DecodeParms', 'Length', 'Filter'], ['DecodeParms', 'Filter', 'Length'],
  ])('P1: retains every page with dictionary order %j, %j, %j', (...keys) => {
    const compressed = deflateSync(drawn('Página 2 de 3') + drawn('Cobertura documental') + drawn('Documento analizado'))
    const values = { Length: compressed.length, Filter: '/FlateDecode', DecodeParms: 'null' }
    const entries = keys.map(key => `/${key} ${values[key]}`).join('\n')
    const bytes = Buffer.concat([page('Página 1 de 3'), withEntries(compressed, entries), page('Página 3 de 3')])
    expect(extractPdfText(bytes)).toBe('Página 1 de 3\nPágina 2 de 3\nCobertura documental\nDocumento analizado\nPágina 3 de 3')
  })

  it('accepts Filter before Length without optional entries', () => {
    const compressed = deflateSync(drawn('Cobertura documental'))
    expect(extractPdfText(withEntries(compressed, `/Filter /FlateDecode /Length ${compressed.length}`)))
      .toBe('Cobertura documental')
  })

  it('reads names and comments as PDF tokens, not as raw dictionary substrings', () => {
    const compressed = deflateSync(drawn('Cobertura documental'))
    const entries = `/Filter % /Length 0 /Filter /LZWDecode >> stream\r\n/Flate#44ecode\n`
      + `/Len#67th\t${compressed.length}\n/DecodeParms % ignored\nnull`
    expect(extractPdfText(withEntries(compressed, entries))).toBe('Cobertura documental')
  })

  it.each(['\n', '\r\n'])('P2: skips header-like bytes inside a stored DEFLATE payload with %j delimiters', eol => {
    const embedded = '<<\n/Length 3\n/Filter /FlateDecode\n>>\nstream\nabc\nendstream'
    const compressed = deflateSync(drawn('OUTER') + `(${embedded}) Tj\n`, { level: 0 })
    expect(compressed.includes(Buffer.from(embedded))).toBe(true)
    const entries = `/Length ${compressed.length}\n/Filter /FlateDecode`
    const bytes = Buffer.concat([withEntries(compressed, entries, eol), page('FINAL')])
    expect(extractPdfText(bytes)).toBe(`OUTER\n${embedded}\nFINAL`)
  })

  it('ignores header-like tokens in file strings, comments and nested unrelated values', () => {
    const fake = '<<\n/Length 3\n/Filter /FlateDecode\n>>\nstream\nabc\nendstream'
    const unrelated = `1 0 obj\n(${fake})\nendobj\n2 0 obj\n<${hex(fake)}>\nendobj\n`
      + fake.split('\n').map(line => `% ${line}\n`).join('')
      + '3 0 obj\n<< /Metadata << /Length 3 /Filter /FlateDecode >> /Note (stream) >>\nendobj\n'
      + '[<< /Length 3 /Filter /FlateDecode >> (stream)]\n'
      + '<< /Note (not a stream) >> /stream\n'
    expect(extractPdfText(Buffer.concat([Buffer.from(unrelated), page('REAL')]))).toBe('REAL')
  })

  it('does not use text operators hidden in comments or strings', () => {
    const literal = 'fake [<424144>] TJ /Length 0'
    const content = '% [<424144>] TJ\n' + `(${literal}) Tj\n` + drawn('REAL')
    expect(extractPdfText(stream(deflateSync(content)))).toBe(`${literal}\nREAL`)
  })

  it('handles escaped parentheses, octal bytes and continued literal strings', () => {
    const content = '(one\\(two\\)\\040three\\\r\nfour) Tj\n<414> Tj\n'
    expect(extractPdfText(stream(deflateSync(content)))).toBe('one(two) threefour\nA@')
  })

  it('keeps literal R text after consecutive numeric TJ adjustments', () => {
    const content = '[<505245464958> 1 0 (R)] TJ\n'
    expect(extractPdfText(stream(deflateSync(content)))).toBe('PREFIXR')
  })

  it.each([
    ['/Filter /LZWDecode', 'single /FlateDecode'],
    ['/Filter [/FlateDecode]', 'single /FlateDecode'],
    ['', 'single /FlateDecode'],
    ['/Filter /FlateDecode /DecodeParms << /Predictor 1 >>', '/DecodeParms must be null'],
    ['/Filter /FlateDecode /DecodeParms (null)', '/DecodeParms must be null'],
    ['/Filter /FlateDecode /Other null', 'stream entry /Other'],
  ])('rejects unsupported second-page dictionaries: %s', (entries, diagnostic) => {
    const compressed = deflateSync(drawn('MISSING PAGE'))
    const bytes = Buffer.concat([page('FIRST'), withEntries(compressed, `/Length ${compressed.length} ${entries}`)])
    expect(() => extractPdfText(bytes)).toThrow(diagnostic)
  })

  it.each(['5 0 R', '-1', '1.5', '1.0', '9007199254740992', '(20)', '[20]', 'null'])(
    'rejects an indirect, invalid or unsafe /Length %s', length => {
      const compressed = deflateSync(drawn('MISSING PAGE'))
      const bytes = Buffer.concat([page('FIRST'), withEntries(compressed, `/Length ${length} /Filter /FlateDecode`)])
      expect(() => extractPdfText(bytes)).toThrow('direct nonnegative integer /Length required')
    })

  it('does not obtain a missing Length from a comment or a nested dictionary', () => {
    const compressed = deflateSync(drawn('MISSING PAGE'))
    const entries = '/Filter /FlateDecode % /Length 3\n/DecodeParms << /Length 3 >>'
    expect(() => extractPdfText(Buffer.concat([page('FIRST'), withEntries(compressed, entries)])))
      .toThrow('direct nonnegative integer /Length required')
  })

  it('rejects duplicate stream dictionary keys', () => {
    const compressed = deflateSync(drawn('MISSING PAGE'))
    expect(() => extractPdfText(withEntries(compressed, `/Length 0 /Length ${compressed.length} /Filter /FlateDecode`)))
      .toThrow('duplicate entry')
  })

  it.each([-1, 1, 1000])('rejects a declared payload length off by %i bytes', delta => {
    const compressed = deflateSync(drawn('MISSING PAGE'))
    expect(() => extractPdfText(Buffer.concat([page('FIRST'), stream(compressed, '\n', compressed.length + delta)])))
      .toThrow('Invalid PDF stream length')
  })

  it('rejects extra encoded bytes even when the declared length includes them', () => {
    const compressed = Buffer.concat([deflateSync(drawn('MISSING PAGE')), Buffer.from('junk')])
    expect(() => extractPdfText(stream(compressed))).toThrow('compressed bytes remain after Flate end')
  })

  it('rejects a physically truncated payload', () => {
    const compressed = deflateSync(drawn('MISSING PAGE'))
    const header = Buffer.from(`<< /Length ${compressed.length} /Filter /FlateDecode >>\nstream\n`)
    expect(() => extractPdfText(Buffer.concat([header, compressed.subarray(0, -1)]))).toThrow('truncated payload')
  })

  it.each(['\r', ' ', ' \n'])('rejects malformed opening delimiter %j', eol => {
    const compressed = deflateSync(drawn('MISSING PAGE'))
    const bytes = Buffer.concat([Buffer.from(`<< /Length ${compressed.length} /Filter /FlateDecode >>\nstream${eol}`),
      compressed, Buffer.from('\nendstream')])
    expect(() => extractPdfText(bytes)).toThrow('LF or CRLF required after stream')
  })

  it.each(['endstream', '\rendstream', '\nendstreamX', '\nendstrea', '\n endstream'])(
    'rejects malformed closing delimiter %j', terminator => {
      const compressed = deflateSync(drawn('MISSING PAGE'))
      const bytes = Buffer.concat([Buffer.from(`<< /Length ${compressed.length} /Filter /FlateDecode >>\nstream\n`),
        compressed, Buffer.from(terminator)])
      expect(() => extractPdfText(bytes)).toThrow('Invalid PDF stream length or terminator')
    })

  it.each(['(', '<4', '<GG>', '[1', '<< /Key', '<< /Key >>', 'stream\nabc\nendstream'])(
    'fails clearly on incomplete or malformed syntax %j', suffix => {
      expect(() => extractPdfText(Buffer.concat([page('FIRST'), Buffer.from(suffix)]))).toThrow(/PDF/)
    })

  it('bounds collection nesting', () => {
    expect(() => extractPdfText(Buffer.from('['.repeat(34) + '0' + ']'.repeat(34)))).toThrow('nesting limit')
  })

  it('bounds decompressed allocation', () => {
    const compressed = deflateSync(Buffer.alloc(32 * 1024 * 1024 + 1, 32))
    expect(() => extractPdfText(stream(compressed))).toThrow('Invalid PDF Flate stream')
  })

  it('rejects input beyond the test helper size limit', () => {
    expect(() => extractPdfText(Buffer.alloc(16 * 1024 * 1024 + 1))).toThrow('at most 16 MiB')
  })
})
