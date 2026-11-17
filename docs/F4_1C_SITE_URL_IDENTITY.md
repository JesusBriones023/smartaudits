# F4.1-C — Site URL identity: offline contract

Baseline: clean `main` at `fe5d02c3fa6da8d2a32c17bc80e74dd9e00e3bac` (PR #22, ACQ-OSIV-BOUNDARY-01, merged).
Implementation branch: `feature/f41c-site-url-identity`. Uncommitted, for independent review.

This block answers one question, offline: **when do two URLs name the same Site?**

> Two texts name the same Site exactly when both are accepted as Site addresses and their
> canonical forms are equal. The canonical form is the web origin as written, `scheme://host`,
> in ASCII lowercase.

It adds one pure value object and its tests. It adds no migration, column, service, endpoint,
worker or network use, and it changes no existing file. Production URL acquisition stays disabled.

## The problem

`sites.base_url` holds the text exactly as received (V6, ADR-0001 section 7) and a Site is
identified only by its `id`. Nothing says whether `https://Empresa.com`, `https://empresa.com/`
and `https://empresa.com:443` are one Site or three, so:

- one organization can register the same web several times without noticing;
- there is no key on which uniqueness could be declared.

ADR-0001 section 7 forbids a production endpoint that creates Sites until F4 fixes identity by
URL. This block is that prerequisite. Today nothing in production creates a Site.

The question is not any of these, and the contract never uses them:

| Different question | Why it is not Site identity |
|---|---|
| Is the URL safe to visit? | Destination policy (ADR-0002), decided again on every attempt |
| Do two URLs resolve to one IP? | DNS is attacker-controlled, changes over time and needs the network |
| Does one redirect to the other? | Same: network, server-controlled, not stable |
| Do two pages have similar content? | Content is not identity |
| Do two sites belong to one company? | Ownership is not established anywhere |
| Are two Page URLs identical? | Page identity, phases 4.2-B and 4.5 |

## What the ADRs require

| Source | Requirement used here |
|---|---|
| ADR-0001 s7 | `base_url` stays literal. Identity by URL, "including how to treat URL and domain variants", is decided in F4.1/F4.5. No production Site creation before that |
| ADR-0001 s7 | Future URL or hash columns use binary semantics. The migration guard accepts one `CREATE TABLE` per migration and no `ALTER`: persisting identity needs a new table dependent on Site, or an explicit decision to widen the guard |
| ADR-0001 s6 | Every 2.0 resource is loaded bounded by organization; no access answers 404 without revealing existence; `sites(organization_id, id)` is the tenant key other tables reference |
| ADR-0001 s12 | Technical identifiers and URLs compare with binary semantics; a text collation never decides identity; each new column's collation is declared and tested with its migration |
| ADR-0001 s13 | A Site is archived logically (`ARCHIVED`), never deleted |
| ADR-0002 s7 | Four separate identities: acquisition destination (`DestinationUrl`), origin, Site (4.1-C: "http/https, `www`, subdomains, base path") and Page. `DestinationUrl` equality means only "same acquisition destination". A persisted canonical form is ASCII and compared with binary semantics |
| ADR-0002 s4-s9 | The destination profile: `http`/`https`, default ports only, ASCII host of at least two labels, no userinfo, IP literal or IDN |
| ADR-0002 s6, s21 | Merging `http` with `https` or `www` with the bare name is not a destination decision; it was left to 4.1-C |
| ADR-0002 s13, s21 | An approval documents one evaluation and is never stored or reused. "Resolve when the Site is created and reuse the answer" is a rejected alternative |
| ADR-0002 s14 | Syntax rejections are expected when the input is validated, before an Audit exists |

No ADR conflicts with the selected design. Three observations are listed under
"ADR observations" at the end.

## Identities kept apart

| Concept | Question it answers | Representation | Decided in |
|---|---|---|---|
| **Site identity** | Which registered Site is this, inside one organization? | `scheme://host`, at most 261 ASCII characters | This block: `SiteUrlIdentity` |
| Literal `base_url` | What did the user type? | Free text, up to 2048 characters | V6. Unchanged |
| Initial acquisition URL | Where does the first request of a Scan go? | A `DestinationUrl`. For a Site it is the root, `scheme://host/`, parsed again when needed | 4.2-A. Nothing is stored or derived here |
| Destination approval | May this request be made now, to these addresses? | `DestinationDecision.Approved`, per attempt | ADR-0002, 4.1-B. Never derived from an identity |
| Page identity | Are two acquired URLs the same page inside a Scan? | Full URL with rules for query, fragment and tracking | 4.2-B and 4.5. Not decided |
| Origin identity (RFC 6454) | Do two URLs share scheme, host and port (redirects, link scope)? | Derivable from a `DestinationUrl` | 4.1-B and 4.3 |
| Site membership | Which discovered URLs and hosts belong to a Site (redirect from the bare name to `www`, subdomains)? | — | 4.3 and 4.4. Not decided |

Site identity and origin identity have the same value for a registered address, but answer
different questions: this block decides the registration key only. ADR-0002 section 7 describes
Site identity as "which URLs and domains belong to the same registered Site"; the membership half
of that sentence stays open.

A Site identity is deliberately not a `DestinationUrl`:

- `https://example.com` is the Site; `https://example.com/` is the destination of its root page.
  The two texts differ on purpose.
- `https://example.com/a` and `https://example.com/b` are two valid destinations and two pages.
  Neither is a Site address, and neither is folded into `https://example.com`.
- A destination drops a fragment. A Site address with a fragment is refused.

## Alternatives considered

| Alternative | Outcome | Reason |
|---|---|---|
| Literal text equality (today) | Rejected | Protocol-equivalent spellings become different Sites |
| `DestinationUrl` canonical form as the identity | Rejected | It is a page-level URL with path and query, which ADR-0002 s7 says does not fix Site identity. Up to 2048 characters: a unique key would need a hash |
| **Origin as written; only a bare origin is accepted** | **Selected** | Smallest rule that needs no heuristic, strips nothing, and keeps Site apart from Page |
| Origin, silently stripping path, query and fragment | Rejected | `https://host/tienda` and `https://host/blog` (two webs hosted on paths of one platform) would become one Site without the user ever seeing it |
| Base URL with the path as part of the identity | Deferred (SI-1) | Needs rules nobody has decided: final slash, case, escapes, prefix scope, and a hash for the key. It also makes a single page registrable as a "Site". Can be added later without changing any key issued now |
| Host only, ignoring the scheme | Rejected | Merges `http` and `https`. No ADR supports it |
| Registrable domain (merge `www` and subdomains) | Rejected | Needs the Public Suffix List (new data that ages) and merges independent webs on shared suffixes |
| Identity from DNS, redirects, certificates or content | Rejected | Not offline, not deterministic, controlled by the remote party, and a TOCTOU by construction |
| A hash of the canonical form | Rejected | Unnecessary: the form is at most 261 bytes and is itself the key. No collision analysis is needed because no hash is used |

Why "conservative" means "do not merge" here: a rule that keeps two identities apart can later be
made coarser by an explicit migration that sees both rows. A rule that merged them cannot be
undone, because the second Site was never allowed to exist.

## Selected policy

A text is accepted as a Site address only when both hold:

1. **Destination profile.** `DestinationUrl.parse` accepts the text as is (ADR-0002 rules 1-25):
   no trim, repair, decoding or Unicode normalization.
2. **Bare origin.** Ignoring only the ASCII case of scheme and host, the whole text is
   `scheme://host`, optionally followed by `:` and the default port of that scheme, optionally
   followed by one `/`.

So the accepted language is exactly four spellings per identity:

```
scheme://host          scheme://host/          scheme://host:PORT          scheme://host:PORT/
```

with `PORT` = `443` for `https` and `80` for `http`.

The canonical form is `scheme://host`: lowercase, no port, no final slash. It is the ASCII
serialization of an origin (RFC 6454 section 6.2) restricted to the destination profile.

### The three normalizations, and why each is safe

Each one is an equivalence guaranteed by the URI and HTTP specifications, not a product rule.
They are three of the four changes ADR-0002 section 6 already applies to a destination.

| Normalization | Justification |
|---|---|
| Scheme and host: `A-Z` to `a-z` | Schemes and host names are case-insensitive (RFC 3986 s3.1, s3.2.2 and s6.2.2.1; RFC 4343 for DNS). The mapping is ASCII only and uses no locale. It is safe because the profile has already refused every non-ASCII character in the authority |
| Explicit default port omitted | RFC 3986 s6.2.3 and RFC 9110 s4.2.3: a port equal to the scheme default is equivalent to no port |
| Root slash omitted | RFC 3986 s6.2.3 and RFC 9110 s4.2.3: with an authority, an empty path is equivalent to `/` |

Nothing else is normalized. In particular there is no decoding or re-casing of escapes, no dot
segment resolution, no slash collapsing, no query ordering, no tracking-parameter removal, no
trim, no Unicode normalization, no scheme guessing and no change to any host label.

The fourth destination change, dropping the fragment, is not applied: a fragment is refused.

## Equivalence matrix

"Rejected" names the reason reported; for `DESTINATION_SYNTAX` the ADR-0002 code follows it.
Examples use `empresa.com` as in the roadmap; the tests use `example.com`.

| Row | Case | Decision and relation | Reason | Consequence for uniqueness |
|---|---|---|---|---|
| A | `http://empresa.com` vs `https://empresa.com` | Both accepted. **Distinct**. Each keeps its scheme | Different origins and protocols. That both serve one web cannot be known offline. For a compliance product the plain-http site is a different thing to audit | One organization can register both. Linking them is a future explicit feature (SI-5) |
| A | `HTTPS://empresa.com` | Accepted. **Equivalent** to `https://empresa.com` | Schemes are case-insensitive | One key |
| B | `https://EMPRESA.COM` | Accepted. **Equivalent** to `https://empresa.com` | Host names are case-insensitive | One key whatever case is typed |
| C | `https://empresa.com:443`, `http://empresa.com:80` | Accepted. **Equivalent** to the form without port | Default port equivalence | One key. No canonical form ever contains a port |
| D | `https://empresa.com:8443`, `http://empresa.com:443`, `https://empresa.com:`, `:0443` | **Rejected**: `DESTINATION_SYNTAX` / `PORT` | The profile admits default ports only (ADR-0002 s9). A Site that can never be acquired is not registrable | If ports are ever widened, such identities will carry the port and be distinct; keys issued now do not change. Until this contract is revised, such text stays refused even if the parser is widened (see "Fail closed") |
| E | `https://empresa.com` vs `https://www.empresa.com` | Both accepted. **Distinct**. No label is added or removed | `www` is an ordinary DNS label. No hostname heuristic | One organization can register both (SI-5) |
| F | `https://tienda.empresa.com`, `https://a.b.empresa.com` | Accepted. **Distinct** from every other host | A different host is a different origin. No registrable-domain logic | Every subdomain is its own Site |
| G | `https://empresa.com.` | **Rejected**: `DESTINATION_SYNTAX` / `HOST_FORMAT` | ADR-0002 rule 13. Not stripped | One spelling per host |
| H | `https://empresa.com/` vs `https://empresa.com` | Accepted. **Equivalent**. No final slash | Empty path equivalence | One key |
| I | `https://empresa.com/tienda`, `/index.html` | **Rejected**: `PATH` | A Site is identified by its origin. Stripping would merge different webs; keeping the path would make Site identity a page URL (SI-1) | No key contains a path |
| J | `https://empresa.com/tienda` vs `/tienda/` | Both **rejected**: `PATH`. No relation is defined | No rule is invented for a case that is not accepted | — |
| K | `?a=1&b=2`, `?b=2&a=1`, `?utm_source=x`, `?` | **Rejected**: `QUERY`, also when empty | A query belongs to a page request. No ordering or tracking rule exists because no query is accepted | No key contains a query |
| L | `#top`, `#` | **Rejected**: `FRAGMENT`, also when empty | It names a place inside a page and is never sent to a server. Refused rather than dropped, so nothing is ever removed from a text | No key contains a fragment |
| M | Host `ex%61mple.com` | **Rejected**: `DESTINATION_SYNTAX` / `PERCENT_ENCODING` | ADR-0002 rule 9 | — |
| M | Path `/%2F`, `/%41`, `/%7E` | **Rejected**: `PATH` | An escape is never decoded, so an escaped path is a path | No escape normalization exists |
| M | `/%zz`, `/%4`, `/%00`, `/%5C`, `/%2e` | **Rejected**: `DESTINATION_SYNTAX` / `PERCENT_ENCODING`, `URL_CHARACTERS` or `DOT_SEGMENT` | The profile decides first | — |
| N | U-labels, `xn--` labels, `ab--cd` | **Rejected**: `DESTINATION_SYNTAX` / `IDN_UNSUPPORTED` | ADR-0002 s8 defers IDN | When IDN is admitted the identity will be the lowercase A-label form: an addition, not a change |
| O | `127.0.0.1`, `8.8.8.8`, `[::1]`, `2130706433`, `0x7f000001` | **Rejected**: `DESTINATION_SYNTAX` / `IP_LITERAL` | ADR-0002 s8, public literals included | Same |
| P | Empty text, `empresa.com`, `//empresa.com`, `https:empresa.com`, `ftp://…`, `file:…`, `javascript:…`, `https://localhost` | **Rejected**: `DESTINATION_SYNTAX` / `URL_FORMAT`, `SCHEME`, `HOST_FORMAT` or `HOST_FORBIDDEN` | No scheme is guessed: `empresa.com` is not turned into `https://empresa.com` | — |
| Q | `https://user@empresa.com`, `https://@empresa.com` | **Rejected**: `DESTINATION_SYNTAX` / `USERINFO` | Credentials never belong to an identity; classic host confusion | — |
| R | Fullwidth or Cyrillic letters, zero-width characters, Unicode dots, Kelvin sign, dotless i, composed and decomposed accents, byte order mark, leading or trailing white space | **Rejected**: `DESTINATION_SYNTAX` / `IDN_UNSUPPORTED`, `URL_CHARACTERS` or `URL_FORMAT` | No trim and no Unicode normalization. The accepted language is ASCII letters, digits, hyphen and dot, so one identity has one byte sequence | ASCII look-alikes such as `examp1e.com` are valid and **distinct** |
| S | `/.`, `/..`, `/%2e` | **Rejected**: `DESTINATION_SYNTAX` / `DOT_SEGMENT` | Dot segments are refused, never resolved | — |
| S | `//`, `///`, backslashes, `https:///x` | **Rejected**: `PATH`, `DESTINATION_SYNTAX` / `URL_CHARACTERS` or `HOST_FORMAT` | Separators are never collapsed | — |
| T | Text over 2048 characters; host over 253; label over 63 | **Rejected**: `DESTINATION_SYNTAX` / `URL_TOO_LONG` or `HOST_FORMAT` | Limits of the profile | Canonical form: at most 261 ASCII characters, which are also bytes. Longest accepted text: 266 |

When several reasons apply, the profile decides first, and then the order is path, query, fragment.

### Examples

Accepted, with canonical form:

| Text | Canonical form |
|---|---|
| `https://example.com`, `https://example.com/`, `https://example.com:443`, `https://example.com:443/`, `HTTPS://EXAMPLE.COM` | `https://example.com` |
| `http://example.com`, `http://example.com:80/` | `http://example.com` |
| `https://www.example.com/` | `https://www.example.com` |
| `https://shop.example.com` | `https://shop.example.com` |

Rejected: `example.com`, `https://example.com/tienda`, `https://example.com/?`,
`https://example.com/#`, `https://example.com.`, `https://example.com:8443`,
`https://user@example.com`, `https://127.0.0.1`, `https://localhost`, ` https://example.com`
(leading space) and every text with a non-ASCII character.

## Value-object contract

Package `com.smartaudits.v2.site`: pure Java 17 over the destination parser.

```java
SiteUrlIdentityResult result = SiteUrlIdentity.parse(text);          // never throws for bad text
if (result instanceof SiteUrlIdentityResult.Accepted ok) {
    SiteUrlIdentity site = ok.identity();
    site.canonicalForm();   // "https://example.com"
    site.scheme();          // "https"
    site.host();            // "example.com"
} else {
    SiteUrlIdentityResult.Rejected no = (SiteUrlIdentityResult.Rejected) result;
    no.reason();                 // DESTINATION_SYNTAX, PATH, QUERY, FRAGMENT, NOT_AN_ORIGIN
    no.destinationRejection();   // the parser's own rejection, only for DESTINATION_SYNTAX
}
```

| Property | Contract |
|---|---|
| Construction | Only `SiteUrlIdentity.parse(String)`. No public constructor, no other factory. `null` is a contract error with a constant message; any other text gives a typed result |
| Immutability | Final classes, private final fields, no setters, not records, not `Serializable`, no annotations |
| Equality | `equals` and `hashCode` are those of the canonical form. Equality is the comparison of the full string; no hash decides identity |
| Determinism | Same text, same result. No clock, randomness, locale, environment, cache or shared state |
| Idempotence and round trip | The canonical form of an accepted text is itself accepted and is its own canonical form |
| Bound | `SiteUrlIdentity.MAX_CANONICAL_LENGTH` = 261 (`https://` plus a host of 253) |
| Diagnostics | `toString` shows only the scheme, or only the reason. The host is tenant data and is reachable only through the explicit accessors. A rejection keeps nothing of the text |
| Rejections | `SiteUrlRejection` has five stable names. `DESTINATION_SYNTAX` carries the parser's `UrlParseResult.Rejected` unchanged, so no ADR-0002 code is renamed or reinterpreted. Only its twelve syntax codes can appear: no DNS or address code exists offline |
| Reuse | The destination parser is reused, not copied. The package imports only `DestinationUrl`, `UrlParseResult`, `Objects` and `Optional` |

### Fail closed

The acceptance rule is written against the raw text, not against what the parser returns. This
protects against some later widenings of the destination profile, mainly unsupported port forms: the
new spellings do not match one of the four above and are refused as `NOT_AN_ORIGIN` until this
contract is revised on purpose. With the current profile that reason cannot occur; mutation M11
below shows it working.

The protection is partial. Host grammar and host and length limits are inherited from the existing
parser, not re-checked here. If the parser's hostname rule were relaxed, a punycode host such as
`https://xn--bcher-kva.example.com` would be its own origin and would be accepted without any change
to the raw-origin rule. Any change to the parser's hostname, character or length rules therefore
needs a coordinated Site identity review and Site identity regression tests before it ships.

## Security: identity is not permission

- An accepted identity has passed syntax only. No DNS, address, TLS or reachability check is made,
  and `example.com` may resolve to a private address tomorrow.
- The identity is never an authorization. No public member of an accepted identity returns a
  `DestinationUrl`, a host object or a decision. Every future acquisition and every redirect still
  needs the full destination policy at that moment, the pinned transport, the deadline, the
  admission control, the transaction guard and the trusted provenance. None of them is touched.
- Acceptance is relative to the destination profile and to the catalog revision, because rule 18
  uses the forbidden-name catalog. A name accepted today can be refused by a later catalog.
  Equality between identities never depends on the catalog: it is a string comparison. Stored
  canonical forms must therefore be compared as strings, never by parsing them again. A stored
  form that a newer catalog refuses is a Site that cannot be acquired, not a different Site.
- Registering a Site proves nothing about who controls the domain.
- Production acquisition stays disabled: `CapturaUrlConfig` still registers only
  `TransporteHttpNoDisponible`. Nothing in production uses the new package.

## Organization-scoped uniqueness (design; not implemented)

- **Key.** The pair (organization, canonical form). The identity itself carries no tenant.
- **Same organization.** Two Sites with equal canonical forms conflict.
- **Different organizations.** The same identity may exist in each. There is no
  cross-organization deduplication and no lookup by canonical form alone.
- **No existence leak.** Authorization (ACTIVE membership) is checked before any identity is
  compared, so a non-member gets the same 404 whether or not the Site exists. A conflict is only
  ever reported to a member of the organization that owns both Sites.
- **Conflict semantics.** A creation whose identity already exists in the organization is refused
  as a conflict; it never returns, updates or merges the existing Site.
- **Archived Sites (SI-2).** Recommended: an `ARCHIVED` Site keeps its identity reserved, as
  `(organization_id, user_id)` stays unique for a `REVOKED` membership (ADR-0001 s6). The history
  of one web then stays under one Site, and re-registration is a future "reactivate" operation.
  The alternative, uniqueness among `ACTIVE` Sites only, lets history split across several Sites
  and needs a conditional unique key that MariaDB does not have natively.

### Database collation

- The canonical form is ASCII and must be compared byte by byte (ADR-0001 s7 and s12, ADR-0002 s7).
- The table default, `utf8mb4_unicode_520_ci`, must not decide identity. It is case-insensitive
  and accent-insensitive, and its treatment of punctuation and trailing spaces was not examined
  here. A case-insensitive collation is not an identity policy: the application lowercases with a
  defined ASCII mapping, and the database must then compare exactly what it is given.
- Recommended column: `VARBINARY(261)`, or `VARCHAR(261)` with an explicit binary ASCII collation.
  Which one, and whether a `CHECK` also enforces the canonical grammar against rows written
  outside the application, is decided and probed on real MariaDB with the migration (ADR-0001 s12).
  Padding behaviour of the chosen type must be part of that probe.
- 261 bytes fit in a unique index. No hash column is needed.
- In Java the comparison is `String.equals` on an ASCII string. No collator or locale is involved.

### Planned persistence

Not implemented. The migration guard forbids `ALTER` on `sites` (ADR-0001 s7), so the
recommended shape is a new table created by one `CREATE TABLE` (SI-3):

```
site_url_identities
  site_id            BIGINT      PRIMARY KEY          -- one identity per Site, immutable like base_url
  organization_id    BIGINT      NOT NULL
  canonical_origin   binary, at most 261 bytes, NOT NULL
  UNIQUE (organization_id, canonical_origin)          -- the organization-scoped key
  FOREIGN KEY (organization_id, site_id) REFERENCES sites (organization_id, id)
```

The composite foreign key reuses `uq_sites_org_site`, so an identity row can never belong to a
different organization than its Site. `organization_id` leads the unique key, so no lookup crosses
tenants. `sites` and its rows are not touched. The alternative is a column on `sites`, which needs
an explicit decision to widen the migration guard.

The future service would parse the text, keep the literal in `base_url` as today, and insert the
Site and its identity in one transaction, following the invocation policy of `AuditScanService`.

### Legacy rows

- Existing `base_url` values are literal and may be duplicated, empty or not URLs at all. They are
  never rewritten, validated retroactively or deduplicated.
- SmartAudits is not in production (ADR-0001 s15) and no production path creates Sites, so no
  production row is expected. Test and probe rows live in isolated databases.
- With the planned table, a legacy Site simply has no identity row: it cannot conflict and it
  cannot violate the unique key.
- Giving a legacy Site an identity would be an explicit operation by a member (parse its literal
  with this same contract; attach it only if accepted and free). It is never automatic (SI-4).

### Concurrency

- A check followed by an insert is a race: two requests can both see "free". The database unique
  key must be the arbiter. The second transaction fails on the duplicate key and rolls back
  entirely, including its Site row.
- The service must report that failure as a conflict, not as a server error.
- A creation is never retried automatically when the commit outcome is uncertain (duplicate key,
  deadlock, lock timeout), as `AuditScanService` already requires.
- Equal identities are equal byte strings by construction, so two concurrent requests with
  different spellings of one Site (`https://EXAMPLE.com/` and `https://example.com:443`) collide
  on the same key.
- These are requirements for the persistence slice. Nothing is enforced today.

## Files

| File | Role |
|---|---|
| `smartaudits-backend/src/main/java/com/smartaudits/v2/site/SiteUrlIdentity.java` | The value object, `parse`, the spelling rule |
| `smartaudits-backend/src/main/java/com/smartaudits/v2/site/SiteUrlIdentityResult.java` | Sealed result: `Accepted`, `Rejected` |
| `smartaudits-backend/src/main/java/com/smartaudits/v2/site/SiteUrlRejection.java` | The five reasons |
| `smartaudits-backend/src/test/java/com/smartaudits/v2/site/SiteUrlIdentityMatrixTest.java` | The matrix: accepted spellings, equivalence classes, distinct identities, every rejected category |
| `smartaudits-backend/src/test/java/com/smartaudits/v2/site/SiteUrlIdentityContractTest.java` | Value-object contract, locale, diagnostics, identity versus destination and approval |
| `smartaudits-backend/src/test/java/com/smartaudits/v2/site/SiteUrlIdentityGeneratedTest.java` | Exhaustive bounded checks against an oracle written from the contract |
| `smartaudits-backend/src/test/java/com/smartaudits/v2/site/SiteUrlIdentityStaticTest.java` | Structural guards: imports, forbidden APIs, no production user |
| `docs/F4_1C_SITE_URL_IDENTITY.md` | This document |

No existing file is modified. The package is new because the destination package pins its exact
number of sources in `DestinationPackageStaticTest`, and because Site identity is not an
acquisition concept.

## Tests

190 new tests in four classes (189 at first validation, plus one added after review). Expectations are written from this contract, not copied from the
output of the code.

| Covered | Where |
|---|---|
| Every accepted spelling and its canonical form | `MatrixTest.everyAcceptedSpellingHasItsCanonicalForm`: 22 rows |
| Every rejected category, with exact reason and ADR-0002 code | `MatrixTest.everyCategoryTheDestinationProfileRejects`: 99 rows; `everyTextThatIsNotABareOriginIsRejectedAndNeverStripped`: 36 rows; `theDestinationProfileDecidesBeforeTheSiteLevel` |
| Equivalent spellings give identical identities | `MatrixTest.equivalentSpellingsProduceIdenticalIdentities`: 6 classes, 27 spellings |
| Distinct identities stay distinct; no accidental merge | `MatrixTest.knownDistinctIdentitiesAreNeverCollapsed`: 34 forms, every pair; `schemeWwwAndSubdomainsEachKeepTheirOwnIdentity`; `variantsThatWouldCollideIfAnythingWereStrippedAreAllRefused` |
| The accepted language is exactly the four spellings | `MatrixTest.theAcceptedLanguageIsExactlyTheFourOriginSpellings`: 1 512 texts (6 schemes, 6 hosts, 42 tails); `GeneratedTest`: every tail of up to five characters over ten characters for both schemes (222 222 texts) and every single-character edit of seven spellings with 156 characters (50 546 texts), each checked against an oracle written from the contract |
| Idempotence, stability, round trip | `ContractTest.normalizationIsIdempotentAndTheCanonicalFormRoundTrips`, `theCanonicalFormIsStableAsciiLowercaseAndBounded` |
| Equality and `hashCode` | `ContractTest.equalityAndHashCodeFollowTheCanonicalForm` |
| Maximum length and overflow | `ContractTest.theLengthBoundIsExact` |
| Percent-encoding, malformed escapes, Unicode and IDNA limits | Rows M, N and R of the matrix corpora |
| No use of the default locale | `ContractTest.resultsDoNotDependOnTheDefaultLocale`: seven default locales, Turkish, Azerbaijani and Lithuanian among them. It restores the general, DISPLAY and FORMAT defaults; `theLocaleRestorationKeepsDistinctCategoryDefaults` proves that restoration. The default locale is global JVM state: tests that change it must not run concurrently without isolation |
| Immutability, no public construction, sealed result, stable reasons | `ContractTest.typesAreFinalImmutableAndHaveNoPublicConstructionPath`, `theResultIsSealedWithTwoFinalVariantsAndFiveStableReasons` |
| Redacted diagnostics | `ContractTest.diagnosticsNeverRevealTheTextTheHostOrSecrets` |
| Parser behaviour unchanged and not reinterpreted | `ContractTest.aRejectionCarriesTheDestinationCodeExactlyForDestinationSyntax`, `aTextIsAcceptedOnlyIfTheDestinationProfileAcceptsIt`, plus the existing destination suites |
| Identity is not a destination, a page or an approval | `ContractTest.aSiteIdentityIsNotTheAcquisitionDestinationNorAPageUrl`, `anAcceptedIdentityIsNeverADestinationApproval` (the same accepted identity is refused as a destination when its name resolves to loopback, private or metadata addresses) |
| The spelling rule on its own, including ports it must refuse | `ContractTest.theSpellingRuleAcceptsOnlyTheFourSpellingsAndFailsClosed` |
| No DNS, HTTP, I/O, locale, persistence, framework or transport API | `StaticTest`: import allow-list of four types, forbidden-token scan, ASCII-only sources |
| No production code uses the identity; no transport activation | `StaticTest.nothingInProductionUsesTheIdentityYet`, plus the existing fail-closed suites |
| Legacy literals are never repaired into an identity | `MatrixTest.literalLegacyBaseUrlsAreNeverRepairedIntoAnIdentity` |

"No DNS or HTTP" is shown by structure (the package can only reach the destination parser, which
has its own static guard) and by a resolver that counts its calls. There is no runtime network
monitor in these tests.

## Validation

Every run used a fresh copy of `pom.xml` and `src` outside the repository, Maven offline, compiled
with javac (130 main and 95 test sources, release 17). JDK 17.0.15, Maven 3.9.12, default locale es-ES.

| Run | Result | Exit |
|---|---|---|
| New tests alone | 189 run, 0 failures, 0 errors, 0 skipped | 0 |
| The 27 existing suites this block could affect: destination URL, policy and catalog; Site, Organization, membership, Audit, Scan and `AuditScanService`; transport bootstrap; OSIV boundary and wiring; ingestion; capture contract and provenance | 1008 run, 0 failures, 0 errors, 0 skipped | 0 |
| Full backend suite | 1804 run in 78 suites, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS` | 0 |

Before this block the full suite had 1615 tests in 74 suites; 1615 + 189 = 1804.

Input verification: the 246 input files were hashed with SHA-256. Each copy was proven identical to
the repository before its run, and the repository inputs were hashed again after the last run and
had not changed. The evidence stays outside the repository and is not committed.

After the independent review, two low-severity corrections were made: the locale test restores all
three default locales, and the parser-dependence claims were qualified (see "Fail closed"). The only
production change is a comment. Re-validation: the four Site identity classes, on a fresh verified
copy, ran 190, 0 failures, 0 errors, 0 skipped, exit 0. The regression and full-suite results above
predate these corrections and were not rerun, nor were the mutants.

Not run: frontend tests, the migration validation and the E2E suite. Nothing they exercise changed:
no frontend file, migration, configuration, bean or request path. The E2E suite also needs Docker,
which was not running and was not started. Nothing was pushed, so there is no CI result.

## Mutation results

Eleven mutants, each in its own clean copy with exactly one change, run against the four new test
classes. All compiled. All were detected. None was applied to the working tree.

| Mutant | Change | Detected by | Failing tests | Exit |
|---|---|---|---|---|
| M01 | The canonical form keeps the host as typed (no case normalization) | 11 test methods | 13 of 189 | 1 |
| M02 | The canonical form keeps an explicit default port | 11 | 14 | 1 |
| M03 | `www.` is removed from the host | 9 | 10 | 1 |
| M04 | The scheme is always `https` | 14 | 17 | 1 |
| M05 | A path is accepted and stripped | 11 | 30 | 1 |
| M06 | A query is accepted and stripped | 9 | 18 | 1 |
| M07 | A fragment is accepted and dropped | 9 | 14 | 1 |
| M08 | Case is folded with the default locale | 2: the locale test, under `tr_TR`, and the static scan | 2 | 1 |
| M09 | The canonical form keeps the root slash | 13 | 17 | 1 |
| M10 | The spelling rule accepts any numeric port | 1: the direct test of the rule | 1 | 1 |
| M11 | The destination parser is widened: its port rule (19) is removed | 7 | 13 | 1 |

What the three unusual rows mean:

- **M08.** With the default locale of the test machine the mutant behaves like the original. It is
  caught only because one test changes the default locale, and because the static scan forbids
  the API.
- **M10.** Through `parse` the destination parser refuses those ports first, so only the direct
  test of the rule sees the difference. That test is what protects the rule if the parser changes.
- **M11.** This mutates the parser, not the new code. Every text with a non-default port was
  refused as `NOT_AN_ORIGIN` (or `PATH` when it also had a path). None was accepted and none was
  merged into the identity of the default port. It is the only run in which `NOT_AN_ORIGIN` occurs.

Not attempted: a mutant of the final `NOT_AN_ORIGIN` return. With the unmutated parser no text
reaches it, so no test of the unmutated code can detect such a change.

## Explicit non-guarantees

- **Not a permission.** An identity never says a URL is safe or allowed to be acquired.
- **Not ownership.** Nothing shows that the organization controls the domain.
- **Not "the same web".** Two identities may serve identical content. One organization can hold
  `http` and `https`, or `www` and the bare name, as separate Sites.
- **Not protection against look-alike names.** `examp1e.com` and `example.com` are valid and
  distinct. IDN, where most homograph risk lives, is refused entirely.
- **Not crawl scope.** Which discovered URLs belong to a Site is undecided.
- **Not everything registrable.** Webs hosted on a path, on a non-default port, on an IP literal
  or under an IDN name are refused.
- **Not enforced.** Nothing is persisted: no uniqueness exists until the persistence slice.
- **Not catalog-independent.** Acceptance of a name depends on the forbidden-name catalog revision.
- **Not observed on MariaDB.** The collation and concurrency statements are requirements for the
  persistence slice, not observations.
- **Static guards are text scans.** They catch an accidental import or caller, not a deliberate bypass.
- **Inherited dependency.** The contract depends on `DestinationUrl.parse` and, through it, on
  `java.net.URI` of Java 17 (ADR-0002 s22). A JDK change requires running these corpora again.

## Remaining design decisions

None blocks this block. Each is additive: no canonical form issued under this contract changes.

| Id | Decision | Recommendation | Belongs to |
|---|---|---|---|
| SI-1 | Whether a Site hosted on a path (`https://host/tienda`) can be registered | Keep origin only until a product need is confirmed. The roadmap speaks of adding a "dominio" throughout | Product, then a revision of this contract |
| SI-2 | Whether an `ARCHIVED` Site keeps its identity reserved | Yes | Persistence slice |
| SI-3 | Where the identity is stored | New table dependent on Site | Persistence slice |
| SI-4 | Whether and how a legacy Site gets an identity | Explicit operation, never automatic | Persistence slice |
| SI-5 | Linking the identities of one web (`http`/`https`, `www`/bare name) | A relation between Sites confirmed by a member; never a change of the identity rule | Product |
| SI-6 | Site membership and crawl scope | — | 4.3 and 4.4 |
| SI-7 | IDN, more ports, public IP literals | ADR-0002 s23. Each adds identities | ADR-0002 amendments |
| SI-8 | What the interface offers when a pasted text is refused | It may propose the origin for the user to confirm. The service never strips | Interface slice |

## ADR observations

No ADR conflicts with this design and none was edited. Three points need attention later:

1. ADR-0001 s7 says two Sites of the same organization may store the same URL. That stays true of
   `base_url`. Once identities are persisted under the planned unique key, two identified Sites of
   one organization can no longer share an identity. ADR-0001 s7 will need an amendment in the
   persistence slice.
2. ADR-0002 s7 assigns to 4.1-C "which URLs and domains belong to the same registered Site". This
   block decides the registration key and leaves membership to 4.3 and 4.4.
3. ADR-0002 still lists 4.1-C as future in its sub-phase table, and its status header still reads
   as a proposal without implementation although the destination policy and the pinned transport
   (kept disabled) are merged. The master roadmap has stale progress entries too. Neither was
   updated, by instruction.

## Deployment blockers

Unchanged and not affected by this block: D1 OPEN, D2 OPEN, D9 OPEN, R-EGR BLOCKED.
Real URL acquisition stays disabled.

## Next dependent slice

Not started. **Site identity persistence and internal Site creation**, still without any endpoint:

- one migration creating the identity table, with its collation declared and probed on MariaDB;
- an internal service that authorizes, parses with this contract, keeps the literal `base_url`,
  and inserts Site and identity in one transaction, with the unique key as the arbiter;
- lazy provisioning of the personal Organization, which ADR-0001 s6 ties to the first Site;
- a concurrency probe of equal identities written with different spellings;
- decisions SI-2, SI-3 and SI-4, and the ADR-0001 s7 amendment.

After it: 4.2-A (`scan_failures` and safe Scan execution), which derives the initial acquisition
URL from a Site and evaluates it with the full destination policy.

## Rollback

Delete the eight files listed under "Files". Nothing else refers to them.
