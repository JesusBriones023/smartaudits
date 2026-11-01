import { Document, Page, StyleSheet, Text, View } from '@react-pdf/renderer'
import { JOINT_FALLBACKS, JOINT_LABELS, buildJointReport } from '../report/jointReportPresentation'

// A4 report of a joint audit. Only built-in Helvetica (no external fonts or images);
// user text is always a <Text> string, never markup.
const styles = StyleSheet.create({
  page: { fontFamily: 'Helvetica', fontSize: 9, color: '#1F2937', paddingTop: 48, paddingBottom: 48, paddingHorizontal: 40, lineHeight: 1.45 },
  header: { position: 'absolute', top: 18, left: 40, right: 40, flexDirection: 'row', justifyContent: 'space-between', fontSize: 8, color: '#6B7280', borderBottomWidth: 0.5, borderBottomColor: '#D1D5DB', paddingBottom: 4 },
  footer: { position: 'absolute', bottom: 18, left: 40, right: 40, flexDirection: 'row', justifyContent: 'space-between', fontSize: 8, color: '#6B7280' },
  brand: { fontSize: 18, fontFamily: 'Helvetica-Bold', color: '#1D4ED8' },
  title: { fontSize: 16, fontFamily: 'Helvetica-Bold', marginTop: 10, marginBottom: 4 },
  muted: { color: '#4B5563' },
  notice: { marginTop: 10, padding: 8, borderWidth: 0.5, borderColor: '#F59E0B', backgroundColor: '#FFFBEB', fontSize: 8.5 },
  h2: { fontSize: 13, fontFamily: 'Helvetica-Bold', marginTop: 14, marginBottom: 6, color: '#111827' },
  h3: { fontSize: 11, fontFamily: 'Helvetica-Bold', marginTop: 10, marginBottom: 3 },
  row: { flexDirection: 'row', borderBottomWidth: 0.5, borderBottomColor: '#E5E7EB', paddingVertical: 3 },
  headRow: { flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: '#9CA3AF', paddingVertical: 3, fontFamily: 'Helvetica-Bold' },
  colDoc: { width: '34%', paddingRight: 4 },
  colCat: { width: '26%', paddingRight: 4 },
  colScore: { width: '14%' },
  colLevel: { width: '16%' },
  colFindings: { width: '10%', textAlign: 'right' },
  meta: { marginBottom: 1 },
  label: { fontFamily: 'Helvetica-Bold' },
  item: { marginLeft: 8, marginBottom: 2 },
  finding: { marginTop: 6, paddingLeft: 6, borderLeftWidth: 2, borderLeftColor: '#93C5FD' },
  findingTitle: { fontFamily: 'Helvetica-Bold' },
})

// The built-in fonts only render Latin-1 reliably: react-pdf drops other characters
// (dashes, ellipsis, curly quotes, euro...). Transliterate them keeping the meaning;
// accented letters outside Latin-1 lose the accent and anything else becomes "?".
const EQUIVALENTES = new Map([
  [0x2012, '-'], [0x2013, '-'], [0x2014, '-'], [0x2015, '-'], [0x2212, '-'], [0x2026, '...'],
  [0x2018, "'"], [0x2019, "'"], [0x201A, "'"], [0x2032, "'"], [0x201C, '"'], [0x201D, '"'], [0x201E, '"'], [0x2033, '"'],
  [0x2022, '-'], [0x20AC, 'EUR'], [0x2122, '(TM)'], [0x2009, ' '], [0x200A, ' '], [0x202F, ' '],
  [0x200B, ''], [0x200C, ''], [0x200D, ''], [0xFEFF, ''],
])

export function textoPdf(valor) {
  if (typeof valor !== 'string') return valor
  let salida = ''
  for (const c of valor) {
    const code = c.codePointAt(0)
    if (code <= 0xFF) salida += c
    else if (EQUIVALENTES.has(code)) salida += EQUIVALENTES.get(code)
    else {
      const base = c.normalize('NFD')[0]
      salida += base.codePointAt(0) <= 0xFF ? base : '?'
    }
  }
  return salida
}

const textosPdf = valor => (Array.isArray(valor) ? valor.map(textosPdf)
  : valor && typeof valor === 'object' ? Object.fromEntries(Object.entries(valor).map(([k, v]) => [k, textosPdf(v)]))
    : textoPdf(valor))

// Long unbroken tokens (URLs, evidence) are split so they wrap inside the A4 margins.
const romper = palabra => (palabra.length > 30 ? palabra.match(/.{1,24}/g) : [palabra])

const T = ({ style, children, ...props }) => (
  <Text style={style} hyphenationCallback={romper} {...props}>{children}</Text>
)

const Campo = ({ label, value, field }) => (
  <T style={styles.meta}>
    <Text style={styles.label}>{label}: </Text>
    <Text data-joint-field={field}>{value}</Text>
  </T>
)

// Fixed header and footer, repeated on every page. Each section is its own <Page> and
// long content stays as direct page children: react-pdf miscomputes the layout of one
// large View split over many pages when a page-number render is present.
const Marco = ({ date }) => (
  <>
    <View style={styles.header} fixed>
      <Text>SmartAudits · Informe de auditoría conjunta</Text>
      <Text>{date}</Text>
    </View>
    <View style={styles.footer} fixed>
      <Text>Basado en el HTML aportado. No acredita por sí solo el cumplimiento normativo.</Text>
      <Text render={({ pageNumber, totalPages }) => `Página ${pageNumber} de ${totalPages}`} />
    </View>
  </>
)

const AuditoriaConjuntaPdfDocument = ({ informe }) => {
  const report = textosPdf(buildJointReport(informe))
  return (
    <Document title={`Auditoría conjunta - ${report.title}`} author="SmartAudits" creator="SmartAudits" producer="SmartAudits">
      <Page size="A4" style={styles.page}>
        <Marco date={report.date} />
        <Text style={styles.brand}>SmartAudits</Text>
        <T style={styles.title} data-joint-field="title">{report.title}</T>
        <T style={styles.muted}>
          <Text data-joint-field="date">{report.date}</Text>
          {' · '}
          <Text data-joint-field="documentCount">{report.documentCount}</Text> documentos analizados
        </T>
        <View style={styles.notice}>
          <T>{report.limitations[0]}</T>
          <T>{report.limitations[1]}</T>
        </View>

        <Text style={styles.h2}>Resumen de documentos</Text>
        <View style={styles.headRow}>
          <Text style={styles.colDoc}>Documento</Text>
          <Text style={styles.colCat}>{JOINT_LABELS.category}</Text>
          <Text style={styles.colScore}>Puntuación</Text>
          <Text style={styles.colLevel}>{JOINT_LABELS.level}</Text>
          <Text style={styles.colFindings}>Hallazgos</Text>
        </View>
        {report.documents.map(d => (
          <View key={d.id} style={styles.row} wrap={false} data-joint-summary-row={d.id}>
            <T style={styles.colDoc}>{d.title}</T>
            <T style={styles.colCat}>{d.category}</T>
            <Text style={styles.colScore}>{d.score}</Text>
            <Text style={styles.colLevel}>{d.classification || '-'}</Text>
            <Text style={styles.colFindings}>{d.findings.length}</Text>
          </View>
        ))}
      </Page>

      {report.documents.map((d, index) => (
        <Page key={d.id} size="A4" style={styles.page} data-joint-document={d.id}>
          <Marco date={report.date} />
          <T style={styles.h2} data-joint-field="docTitle">{`Documento ${index + 1}: ${d.title}`}</T>
          <Campo label={JOINT_LABELS.category} value={d.category} field="category" />
          {d.url && <Campo label={JOINT_LABELS.url} value={d.url} field="url" />}
          <Campo label={JOINT_LABELS.score} value={d.score} field="score" />
          {d.classification && <Campo label={JOINT_LABELS.level} value={d.classification} field="classification" />}
          <Campo label={JOINT_LABELS.engine} value={d.engine} field="engine" />

          <Text style={styles.h3}>{JOINT_LABELS.summary}</Text>
          <T data-joint-field="summary">{d.summary}</T>

          <Text style={styles.h3}>{JOINT_LABELS.risks}</Text>
          {d.risks.length === 0
            ? <Text style={styles.item}>{JOINT_FALLBACKS.noRisks}</Text>
            : d.risks.map((risk, i) => <T key={i} style={styles.item}>- <Text data-joint-field="risk">{risk}</Text></T>)}

          <Text style={styles.h3} minPresenceAhead={40}>{`${JOINT_LABELS.findings} (${d.findings.length})`}</Text>
          {d.findings.length === 0 && <Text style={styles.item}>{JOINT_FALLBACKS.noFindings}</Text>}
          {d.findings.map((f, i) => (
            <View key={i} style={styles.finding} data-joint-finding={i}>
              <T style={styles.findingTitle}>
                <Text data-joint-field="findingTitle">{f.title}</Text>
                {' - '}{JOINT_LABELS.severity}: <Text data-joint-field="severity">{f.severity}</Text>
              </T>
              {f.description ? <Campo label={JOINT_LABELS.description} value={f.description} field="description" /> : null}
              <Campo label={JOINT_LABELS.evidence} value={f.evidence} field="evidence" />
              {f.impact ? <Campo label={JOINT_LABELS.impact} value={f.impact} field="impact" /> : null}
              {f.action ? <Campo label={JOINT_LABELS.action} value={f.action} field="action" /> : null}
            </View>
          ))}
        </Page>
      ))}

      <Page size="A4" style={styles.page}>
        <Marco date={report.date} />
        <Text style={styles.h2}>{JOINT_LABELS.recommendations}</Text>
        {report.recommendations.length === 0 && <Text>{JOINT_FALLBACKS.noRecommendations}</Text>}
        {report.recommendations.map((r, i) => (
          <View key={i} style={styles.item} wrap={false} data-joint-recommendation={i}>
            <T>- <Text data-joint-field="recommendation">{r.text}</Text></T>
            <T style={styles.muted}>{`${JOINT_LABELS.appliesTo}: `}<Text data-joint-field="appliesTo">{r.documents.join(', ')}</Text></T>
          </View>
        ))}

        <Text style={styles.h2}>{JOINT_LABELS.missing}</Text>
        {report.missing.length === 0
          ? <Text>{JOINT_FALLBACKS.noMissing}</Text>
          : report.missing.map(c => <T key={c} style={styles.item}>- <Text data-joint-field="missing">{c}</Text></T>)}

        <Text style={styles.h2}>{JOINT_LABELS.limitations}</Text>
        {report.limitations.map((l, i) => <T key={i} style={styles.item}>- <Text data-joint-field="limitation">{l}</Text></T>)}
      </Page>
    </Document>
  )
}

export default AuditoriaConjuntaPdfDocument
