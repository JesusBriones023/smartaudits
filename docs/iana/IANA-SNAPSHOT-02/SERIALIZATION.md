# Candidate catalog serialization — smartaudits-destination-catalog-candidate/1

Status: CANDIDATE — PENDING ASTRA REVIEW. Not approved.

Revision 2 (gate IANA-SNAPSHOT-02). Documentation-only revision that resolves Astra finding M1
(nested escaping of attributes was incompletely specified). The format identifier is unchanged and the
seven candidate files are byte-identical to IANA-SNAPSHOT-01: this text describes what `tools/build.py`
(SHA256 `F169945E3D33198B032CDC1423B52639E65AED9B8BF8CB73A2BA3CCDB925551A`, unchanged) actually writes.
Nothing here introduces a new format.

Notation. `BS` is the backslash character (U+005C, byte `5C`). `TAB` is U+0009 (`09`), `LF` is U+000A (`0A`),
`CR` is U+000D (`0D`). Text inside backticks is shown exactly as it appears in the file; the hex columns of the
examples are the authoritative bytes (a rendering of backslashes on screen is not evidence of the bytes).

## Encoding
- UTF-8, no BOM, LF line endings, exactly one final LF, no blank lines, no trailing whitespace.
- Content depends only on the bytes of the five primary XML sources and on tools/build.py.
  No run timestamps appear inside the catalog files (they live only in manifest/manifest.json).

## Header lines (start with `#`, fields separated by TAB)
1. `#format<TAB>smartaudits-destination-catalog-candidate/1`
2. `#file<TAB><file name>`
3. `#policy<TAB>ADR-0002 sha256:<approved ADR hash>`
4. one `#source<TAB><id><TAB>updated=<registry <updated> date><TAB>sha256=<raw source bytes><TAB><url>` per source, sorted by id
5. `#columns<TAB>` followed by the 11 column names

Header lines are not escaped. Their values are constants, dates, uppercase hex and URLs, none of which contains
TAB, LF, CR or BS. Header lines appear only before the first data row. A data row never starts with `#`
(its first field is `ADDR` or `NAME`).

## Data rows: exactly 11 fields separated by TAB, no quoting
| Field | Meaning | Nullable (`-`) |
| --- | --- | --- |
| kind | `ADDR` or `NAME` | no |
| layer | `POSITIVE`, `NEGATIVE` or `NOT_FORBIDDEN` (names only) | no |
| family | `4`, `6`; `-` for names | NAME only |
| network | canonical network address (IPv4 dotted decimal, IPv6 RFC 5952 compressed) or canonical name (ASCII lowercase, no root dot) | no |
| prefix_len | decimal prefix length; `-` for names | NAME only |
| network_hex | network address bytes as lowercase hex (8 or 32 digits); `-` for names | NAME only |
| origin | `IANA` (registry entry) or `PRODUCT` (SmartAudits rule from ADR-0002) | no |
| source_id | `IANA-IPV4-SPACE`, `IANA-IPV4-SPECIAL`, `IANA-IPV6-SPECIAL`, `IANA-IPV6-UNICAST`, `IANA-SUDN` or `ADR-0002` | no |
| record_ref | `<registry id>#<1-based record index>` for IANA rows; `product-addr#n ...` / `product-name#n ...` for product rows | no |
| label | official name/designation (IANA) or rule description (PRODUCT) | no |
| attrs | attribute set (see below); `-` is the empty set | `-` = empty set |

`-` is a null marker only in the columns marked above. In every other column it would be a literal value
(no such value occurs in this snapshot). No field is ever empty: an empty field is malformed.

## Escaping — two levels, exactly as produced by tools/build.py

The builder has three functions (copied verbatim from tools/build.py):

```python
def esc(v):
    return str(v).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

def esc_attr(v):
    return esc(v).replace(";", "\\;").replace("=", "\\=")

def attrs(d):
    return ";".join(k + "=" + esc_attr(d[k]) for k in sorted(d)) if d else "-"
```

and writes every data row as `TAB.join(esc(row[c]) for c in COLUMNS)`. The attrs column already holds the
output of `attrs(...)`, so **the attrs column is escaped twice**; the other ten columns once.

### Level F — field level (all 11 columns)
`esc`, applied to the whole field text, in this order:
1. `BS` → `BS BS` (first, so that later replacements never see a backslash they introduced)
2. `TAB` → `BS t`
3. `LF` → `BS n`
4. `CR` → `BS r`

Nothing else is escaped at level F: `;`, `=`, `#`, `-`, spaces and non-ASCII characters pass unchanged.
After level F a field contains no raw TAB, LF or CR, and every BS starts a two-character escape pair.

### Level A — attribute level (attrs column only, applied before level F)
For each attribute value (never to keys), `esc_attr`, in this order:
1. the four level-F rules above (`BS` first), then
2. `;` → `BS ;`
3. `=` → `BS =`

Keys are not escaped. They are fixed identifiers matching `[a-z]+`; the keys used in this snapshot are
`classification destination forwardable global note policy raw reserved review source spec status termination`.
Pairs are written `key=value`, joined with a single `;`, in strictly ascending key order. An empty set is `-`.
An attribute value may be empty (`key=`).

### Full encoding order (attrs column)
value → level-A escape of the value → `key=` prefix → join pairs with `;` (or `-`) → level-F escape of the whole
attrs text → TSV field.

Consequence: every BS produced at level A is doubled again at level F. In the TSV bytes:

| Inside an attribute value | Level A text | TSV bytes | BS count in TSV |
| --- | --- | --- | --- |
| `;` | `BS ;` | `BS BS ;` (`5c5c3b`) | 2 |
| `=` | `BS =` | `BS BS =` (`5c5c3d`) | 2 |
| TAB | `BS t` | `BS BS t` (`5c5c74`) | 2 |
| LF | `BS n` | `BS BS n` (`5c5c6e`) | 2 |
| CR | `BS r` | `BS BS r` (`5c5c72`) | 2 |
| BS | `BS BS` | `BS BS BS BS` (`5c5c5c5c`) | 4 |
| pair separator `;` | `;` | `;` (`3b`) | 0 |
| key/value `=` | `=` | `=` (`3d`) | 0 |

In the ten single-level columns `;` and `=` are plain characters, TAB is `BS t` (`5c74`) and BS is `BS BS` (`5c5c`).

The sequence observed by Astra — two backslashes followed by `;` (`5c5c3b`) in the attrs column — is therefore an
**escaped semicolon inside an attribute value**, not a pair separator. It occurs exactly twice in catalog.tsv
(and twice in product-rules.tsv): in the `note` of product rules `224.0.0.0/4` and `::/96`. Example EX17 below
decodes the first one.

### Decoding (inverse order)
1. Split the line on raw TAB. Exactly 11 fields, otherwise the row is malformed (extra or missing columns are
   rejected before any field is interpreted).
2. Level-F decode each field by a single **left-to-right scan**: `BS BS`→BS, `BS t`→TAB, `BS n`→LF, `BS r`→CR.
   Any other character after BS (including `;` and `=`) is an error; a BS at the end of the field is an error;
   a raw TAB/LF/CR is an error.
3. For the attrs field only, level-A decode the result of step 2:
   - `-` → empty set; an empty text is an error.
   - Tokenize left to right into atoms: an escape pair (`BS BS`, `BS t`, `BS n`, `BS r`, `BS ;`, `BS =`) or a single
     character. Any other escape or a trailing BS is an error.
   - Split the atom list at unescaped `;` atoms. An empty pair (leading, trailing or repeated `;`) is an error.
   - Each pair must contain exactly one unescaped `=`. The key (atoms before it) must match `[a-z]+`; keys must be
     strictly ascending (which also forbids duplicates).
   - The value is its atoms with each escape pair replaced by its character. A raw TAB/LF/CR/`;`/`=` in a value is
     impossible after correct tokenizing and is rejected.

Decoding must be a scan, never a chain of `str.replace` calls and never "split on `;` not preceded by BS":
in the level-A text `a=BS BS;b=x` (example EX14, value of `a` is one backslash) the `;` *is* preceded by a BS and
is nevertheless a separator, because that BS belongs to the pair `BS BS`.

### Why the format is reversible and unambiguous
- Level F output has no raw TAB/LF/CR, so the TAB split in step 1 is exact.
- In level F output every BS begins a two-character pair from a fixed table, so the left-to-right scan has exactly one
  parse (prefix code). The same holds for level A with its six pairs.
- At level A, `;` and `=` inside values are always escaped, so unescaped `;` and `=` can only be separators.
- Each step is a bijection between values and their canonical encodings, and the decoder rejects every
  non-canonical form (unknown escapes, unsorted or duplicate keys, empty pairs, missing `=`), so each value has
  exactly one encoding. tools/verify.py checks this on every real row (re-encoding is byte-identical, check E8).

### Values seen by the builder
`tx()` in tools/build.py collapses every run of whitespace in the XML text to one space and strips the ends.
So the IANA-derived values in this snapshot contain no TAB/LF/CR, and `raw=` is the whitespace-normalized
registry text, not a byte-exact copy of the XML. The authoritative source of each record is
`source_id` + `record_ref` + the source SHA256 in the `#source` header. The escaping rules are normative
regardless (product rules and future sources may contain any character).

### Malformed inputs (must be rejected, never repaired)
Dangling final BS; unknown escape (e.g. `BS x`, `BS u0041`); `BS ;` or `BS =` at level F; raw TAB/LF/CR inside a
field; wrong number of columns; empty attrs text; empty pair; pair without `=` or with two unescaped `=`;
key not matching `[a-z]+` (including an escaped key); unsorted or repeated keys; a truncated file (no final LF).
The full list exercised by the verifier is in tools/verify.py (`BAD_FIELDS`, `BAD_ATTRS`, check E6) and the
file-level probes in checks/adversarial-report.txt.

## Normative examples

Each attribute example is a one-row attribute set encoded by the real builder functions and decoded by the
independent decoder in tools/verify.py (check E2). For every example: field-level decoding of the TSV text gives
the level-A text, and level-A decoding gives back the original value (verified: `decode(encode(x)) == x`).
Original values use the notation ⟨BS⟩ ⟨TAB⟩ ⟨LF⟩ ⟨CR⟩ for the special characters.

### Attribute examples (attrs column: level A, then level F)

| id | case | original value | level-A text | TSV field text | BS in TSV | TSV field bytes (hex) |
| --- | --- | --- | --- | --- | --- | --- |
| EX01 | simple value | note = Multicast | `note=Multicast` | `note=Multicast` | 0 | `6e6f74653d4d756c746963617374` |
| EX02 | semicolon | note = a;b | `note=a\;b` | `note=a\\;b` | 2 | `6e6f74653d615c5c3b62` |
| EX03 | equals sign | note = x=1 | `note=x\=1` | `note=x\\=1` | 2 | `6e6f74653d785c5c3d31` |
| EX04 | backslash | note = C:⟨BS⟩d | `note=C:\\d` | `note=C:\\\\d` | 4 | `6e6f74653d433a5c5c5c5c64` |
| EX05 | TAB | note = a⟨TAB⟩b | `note=a\tb` | `note=a\\tb` | 2 | `6e6f74653d615c5c7462` |
| EX06 | LF | note = a⟨LF⟩b | `note=a\nb` | `note=a\\nb` | 2 | `6e6f74653d615c5c6e62` |
| EX07 | CR | note = a⟨CR⟩b | `note=a\rb` | `note=a\\rb` | 2 | `6e6f74653d615c5c7262` |
| EX08 | backslash then semicolon | note = ⟨BS⟩; | `note=\\\;` | `note=\\\\\\;` | 6 | `6e6f74653d5c5c5c5c5c5c3b` |
| EX09 | literal backslash + letter t (not a TAB) | note = ⟨BS⟩t | `note=\\t` | `note=\\\\t` | 4 | `6e6f74653d5c5c5c5c74` |
| EX10 | adjacent semicolon + equals | note = ;= | `note=\;\=` | `note=\\;\\=` | 4 | `6e6f74653d5c5c3b5c5c3d` |
| EX11 | repeated semicolons | note = ;; | `note=\;\;` | `note=\\;\\;` | 4 | `6e6f74653d5c5c3b5c5c3b` |
| EX12 | empty value | note = (empty) | `note=` | `note=` | 0 | `6e6f74653d` |
| EX13 | CR LF backslash equals (combined) | note = ⟨CR⟩⟨LF⟩⟨BS⟩= | `note=\r\n\\\=` | `note=\\r\\n\\\\\\=` | 10 | `6e6f74653d5c5c725c5c6e5c5c5c5c5c5c3d` |
| EX14 | value is one backslash, followed by another pair | a = ⟨BS⟩ and b = x | `a=\\;b=x` | `a=\\\\;b=x` | 4 | `613d5c5c5c5c3b623d78` |
| EX15 | two attributes with ';' and '=' | note = a;b and review = x=y | `note=a\;b;review=x\=y` | `note=a\\;b;review=x\\=y` | 4 | `6e6f74653d615c5c3b623b7265766965773d785c5c3d79` |
| EX16 | empty attribute set | (empty set) | `-` | `-` | 0 | `2d` |
| EX17 | real row: note of product rule 224.0.0.0/4 (Astra's sequence) | note = ...special-purpose registry; IPv4 Address Spa... | `...special-purpose registry\; IPv4 Address Sp...` | `...special-purpose registry\\; IPv4 Address S...` | 2 | `6e6f74653d4e6f7420696e2049507634207370656369616c2d707572706f73652072656769737472795c5c3b20495076342041646472657373205370616365206c69737473203232342d3233392f3820617320524553455256454420284d756c74696361737429` |

### Single-level field examples (the ten other columns: level F only)

| id | case | original value | TSV field text | BS in TSV | TSV field bytes (hex) |
| --- | --- | --- | --- | --- | --- |
| PF01 | label with ';' and '=' (single level: unescaped) | a;b=c | `a;b=c` | 0 | `613b623d63` |
| PF02 | label with backslash | a⟨BS⟩b | `a\\b` | 2 | `615c5c62` |
| PF03 | label with TAB, LF, CR | a⟨TAB⟩b⟨LF⟩c⟨CR⟩ | `a\tb\nc\r` | 3 | `615c74625c6e635c72` |
| PF04 | label: literal backslash + t | ⟨BS⟩t | `\\t` | 2 | `5c5c74` |
| PF05 | literal '-' in a non-nullable column | - | `-` | 0 | `2d` |

Trace EX17 (real row: note of product rule 224.0.0.0/4 (Astra's sequence)):

1. original value of `note`: ...special-purpose registry; IPv4 Address Spa...
2. level A (esc_attr, then `key=`): `...special-purpose registry\; IPv4 Address Sp...`
3. level F (esc of the whole attrs text) = TSV bytes: `...special-purpose registry\\; IPv4 Address S...` — 2 backslash byte(s)
4. level-F decoding (scan: each `BS BS` becomes one BS): `...special-purpose registry\; IPv4 Address Sp...`
5. level-A decoding (scan: `BS ;` is a literal `;`, not a separator): note = ...special-purpose registry; IPv4 Address Spa...
6. recovered value equals the original: True

Trace EX08 (backslash then semicolon):

1. original value of `note`: ⟨BS⟩;
2. level A (esc_attr, then `key=`): `note=\\\;`
3. level F (esc of the whole attrs text) = TSV bytes: `note=\\\\\\;` — 6 backslash byte(s)
4. level-F decoding (scan: each `BS BS` becomes one BS): `note=\\\;`
5. level-A decoding (scan: `BS ;` is a literal `;`, not a separator): note = ⟨BS⟩;
6. recovered value equals the original: True

Trace EX14 (value is one backslash, followed by another pair):

1. original value of `a`: ⟨BS⟩
2. level A (esc_attr, then `key=`): `a=\\;b=x`
3. level F (esc of the whole attrs text) = TSV bytes: `a=\\\\;b=x` — 4 backslash byte(s)
4. level-F decoding (scan: each `BS BS` becomes one BS): `a=\\;b=x`
5. level-A decoding (scan: `BS ;` is a literal `;`, not a separator): a = ⟨BS⟩
6. recovered value equals the original: True

## The `spec` attribute (observation LOW-A)
`spec=` lists the `data` attribute of every `<xref>` of the record, in document order, joined with `,`, or `-`
when there is none. The xref `type` is **not** preserved. In the local sources the types are `rfc` (e.g.
`rfc6890`), `note` (data `1`..`4`, a footnote number of the registry page) and `rfc-errata` (data `1752`).
A numeric token is therefore not an RFC number, and `spec` must not be read as a list of RFCs. The source record
(`source_id` + `record_ref` + source SHA256) remains the reference. Preserving `type` (e.g. `rfc:rfc6890`,
`note:1`) would change many rows and is left as an optional proposal for Astra; it is not applied here.

## Order (stable, total)
- `ADDR` rows before `NAME` rows.
- `ADDR`: family (4 before 6), network bytes (unsigned, big-endian), prefix length (ascending), then layer, origin,
  source_id, record_ref (plain string order) as tie-breakers.
- `NAME`: name as ASCII bytes, then layer, origin, source_id, record_ref.
- The key is strictly increasing in every file (no two rows share it).

## Files
- positive.tsv: layer 1 (IPv4 /8 ALLOCATED or LEGACY; IPv6 prefixes ALLOCATED to an RIR).
- negative-iana.tsv: layer 2 entries from both special-purpose registries (all entries, whatever their attributes; deprecated included).
- product-rules.tsv: SmartAudits rules (addresses and names), origin PRODUCT, review=pending-astra.
- names-iana.tsv: every Special-Use Domain Names entry with its ADR-0002 classification.
- catalog.tsv: consolidated union of the four files above, same order rules (exact row equality is checked, not only counts).
- excluded-from-positive.tsv / parse-errors.tsv: audit side files with a single `#columns` header and single-level
  (level F) escaping; 6 and 3 columns respectively. parse-errors.tsv is expected to hold only its header.

## Decision semantics (from ADR-0002, informative)
An address is admissible only if it is inside a POSITIVE prefix and inside no NEGATIVE prefix (deny dominates).
"Admissible" never means assigned, routed, reachable or safe. `Globally Reachable = True` in a special-purpose
registry entry never authorizes it: every entry of those registries is NEGATIVE.
