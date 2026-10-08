import {
  Document,
  Page,
  Text,
  View,
  StyleSheet
} from '@react-pdf/renderer'

import { REPORT_LABELS, REPORT_POLICIES, reportHeader, seccionesInforme } from '../report/reportPresentation'

const levelColors = {
  BAJO: '#16a34a', MODERADO: '#ca8a04', ALTO: '#ea580c', MUY_ALTO: '#dc2626',
}

const styles = StyleSheet.create({
  page: {
    fontFamily: 'Helvetica',
    fontSize: 9,
    color: '#1F2937',
    backgroundColor: '#FFFFFF',
    paddingTop: 30,
    paddingHorizontal: 32,
    paddingBottom: 48,
    lineHeight: 1.45
  },

  hero: {
    borderRadius: 8,
    padding: 18,
    marginBottom: 14,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center'
  },

  heroLeft: {
    flexGrow: 1,
    flexShrink: 1,
    paddingRight: 16
},

  heroTitle: {
    fontSize: 15,
    fontWeight: 700,
    color: '#FFFFFF',
    marginBottom: 4
  },

  heroSubtitle: {
    fontSize: 9,
    color: '#FFFFFF',
    opacity: 0.95,
    marginBottom: 2
  },

  heroUrl: {
    fontSize: 8,
    color: '#FFFFFF',
    opacity: 0.9,
    marginTop: 2
  },

  scoreBox: {
    width: 110,
    minHeight: 78,
    alignItems: 'center',
    justifyContent: 'center'
    },

  score: {
    fontSize: 30,
    lineHeight: 1,
    fontWeight: 700,
    color: '#FFFFFF',
    marginBottom: 6
},

  scoreLabel: {
    width: 110,
    fontSize: 7,
    lineHeight: 1.3,
    color: '#FFFFFF',
    textAlign: 'center',
    marginBottom: 7
},

  scoreBadge: {
    backgroundColor: '#FFFFFF',
    borderRadius: 10,
    paddingVertical: 2,
    paddingHorizontal: 7,
    fontSize: 7,
    fontWeight: 700
  },

  section: {
    borderWidth: 1,
    borderStyle: 'solid',
    borderRadius: 7,
    marginBottom: 10,
    overflow: 'hidden'
  },

  sectionHeader: {
    paddingVertical: 7,
    paddingHorizontal: 10,
    borderBottomWidth: 1,
    borderBottomStyle: 'solid'
  },

  sectionTitle: {
    fontSize: 11,
    fontWeight: 700
  },

  sectionBody: {
    padding: 10
  },

  paragraph: {
    fontSize: 9,
    lineHeight: 1.5
  },

  listRow: {
    flexDirection: 'row',
    marginBottom: 4
  },

  bullet: {
    width: 11,
    fontSize: 9,
    fontWeight: 700
  },

  listText: {
    flex: 1,
    fontSize: 9,
    lineHeight: 1.45
  },

  errorCard: {
    backgroundColor: '#FFF7F7',
    borderLeftWidth: 3,
    borderLeftColor: '#EF4444',
    borderRadius: 4,
    padding: 9,
    marginBottom: 8
  },

  errorHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 6
  },

  errorTitle: {
    width: '80%',
    fontSize: 9.5,
    fontWeight: 700,
    color: '#111827'
  },

  severity: {
    borderRadius: 8,
    paddingVertical: 2,
    paddingHorizontal: 6,
    fontSize: 6.5,
    fontWeight: 700
  },

  field: {
    marginBottom: 5
  },

  fieldLabel: {
    fontSize: 7.5,
    fontWeight: 700,
    color: '#4B5563',
    marginBottom: 2
  },

  fieldText: {
    fontSize: 8.5,
    lineHeight: 1.4
  },

  evidence: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#E5E7EB',
    borderRadius: 3,
    padding: 5,
    fontSize: 8,
    color: '#4B5563'
  },

  action: {
    backgroundColor: '#F0FDF4',
    borderWidth: 1,
    borderColor: '#86EFAC',
    borderRadius: 3,
    padding: 5,
    fontSize: 8,
    color: '#166534'
  },

  suggestion: {
    backgroundColor: '#FFFFFF',
    borderWidth: 1,
    borderColor: '#86EFAC',
    borderRadius: 4,
    padding: 7,
    marginBottom: 6,
    fontSize: 8.5,
    lineHeight: 1.4
  },

  reference: {
    backgroundColor: '#F8FAFC',
    borderWidth: 1,
    borderColor: '#E2E8F0',
    borderRadius: 4,
    padding: 6,
    marginBottom: 4,
    fontSize: 8
  },

  original: {
    backgroundColor: '#F8FAFC',
    borderWidth: 1,
    borderColor: '#E5E7EB',
    borderRadius: 4,
    padding: 8,
    fontSize: 7.8,
    lineHeight: 1.45
  },

  footer: {
    position: 'absolute',
    left: 32,
    right: 32,
    bottom: 18,
    borderTopWidth: 1,
    borderTopColor: '#E5E7EB',
    paddingTop: 5,
    flexDirection: 'row',
    justifyContent: 'space-between',
    color: '#9CA3AF',
    fontSize: 7
  }
})

const paletas = {
  resumen: {
    background: '#FFFFFF',
    border: '#E5E7EB',
    header: '#F8FAFC',
    title: '#111827'
  },

  riesgos: {
    background: '#FEF2F2',
    border: '#FCA5A5',
    header: '#FEE2E2',
    title: '#991B1B'
  },

  errores: {
    background: '#FFFFFF',
    border: '#E5E7EB',
    header: '#F8FAFC',
    title: '#111827'
  },

  recomendaciones: {
    background: '#EFF6FF',
    border: '#93C5FD',
    header: '#DBEAFE',
    title: '#1E3A8A'
  },

  sugeridos: {
    background: '#F0FDF4',
    border: '#86EFAC',
    header: '#DCFCE7',
    title: '#166534'
  },

  faltantes: {
    background: '#FFFBEB',
    border: '#FCD34D',
    header: '#FEF3C7',
    title: '#92400E'
  },

  referencias: {
    background: '#FFFFFF',
    border: '#E5E7EB',
    header: '#F8FAFC',
    title: '#111827'
  },

  original: {
    background: '#FFFFFF',
    border: '#E5E7EB',
    header: '#F8FAFC',
    title: '#111827'
  }
}

const Section = ({
  id,
  title,
  palette,
  children
}) => (
  <View
    data-report-section={id}
    style={[
      styles.section,
      {
        backgroundColor: palette.background,
        borderColor: palette.border
      }
    ]}
  >
    <View
      style={[
        styles.sectionHeader,
        {
          backgroundColor: palette.header,
          borderBottomColor: palette.border
        }
      ]}
    >
      <Text
        data-report-field="heading"
        style={[
          styles.sectionTitle,
          { color: palette.title }
        ]}
      >
        {title}
      </Text>
    </View>

    <View style={styles.sectionBody}>
      {children}
    </View>
  </View>
)

const Lista = ({
  items,
  color = '#374151'
}) => (
  <View>
    {items.map((item, index) => (
      <View
        key={index}
        style={styles.listRow}
      >
        <Text
          style={[
            styles.bullet,
            { color }
          ]}
        >
          •
        </Text>

        <Text
          data-report-field="item"
          style={[
            styles.listText,
            { color }
          ]}
        >
          {item}
        </Text>
      </View>
    ))}
  </View>
)

const estiloSeveridad = (valor) => {

  if (valor === 'ALTA') {
    return {
      backgroundColor: '#FEE2E2',
      color: '#B91C1C'
    }
  }

  if (valor === 'MEDIA') {
    return {
      backgroundColor: '#FEF3C7',
      color: '#B45309'
    }
  }

  // Legacy visual fallback only: a null level does not mean BAJA.
  return { backgroundColor: '#DCFCE7', color: '#15803D' }
}

const Campo = ({
  field,
  label,
  value,
  variant
}) => (
  <View style={styles.field}>
    <Text style={styles.fieldLabel}>
      {label}
    </Text>

    <Text
      data-report-field={field}
      style={
        variant === 'evidence'
          ? styles.evidence
          : variant === 'action'
            ? styles.action
            : styles.fieldText
      }
    >
      {value}
    </Text>
  </View>
)

const AuditoriaPdfDocument = ({ report }) => {
  const header = reportHeader(report)
  const nivel = report.score.level
  const levelColor = nivel ? levelColors[nivel.nivel] : '#2563EB'

  const fechaGeneracion =
    new Date().toLocaleString('es-ES')

  const renderSection = section => {
    switch (section.id) {
      case 'summary':
        return (
          <Section
          key={section.id}
          id={section.id}
          title={section.title}
          palette={paletas.resumen}
        >
          <Text data-report-field="text" style={styles.paragraph}>
            {section.text}
          </Text>
        </Section>
        )
      case 'risks':
        return (
          <Section
          key={section.id}
          id={section.id}
            title={`${section.title} (${section.items.length})`}
            palette={paletas.riesgos}
          >
            <Lista
              items={section.items}
              color="#991B1B"
            />
          </Section>
        )
      case 'findings':
        return (
          <Section
          key={section.id}
          id={section.id}
            title={`${section.title} (${section.items.length})`}
            palette={paletas.errores}
          >
            {section.items.map(
              (error, index) => (
                <View
                  key={index}
                  data-report-field="finding"
                  style={styles.errorCard}
                >
                  <View
                    style={styles.errorHeader}
                  >
                    <Text
                      data-report-field="title"
                      style={styles.errorTitle}
                    >
                      {error.title}
                    </Text>

                    <Text
                      data-report-field="severity"
                      style={[
                        styles.severity,
                        estiloSeveridad(
                          error.severityLevel
                        )
                      ]}
                    >
                      {error.severity}
                    </Text>
                  </View>

                  <Campo
                    field="description"
                    label={REPORT_LABELS.description}
                    value={error.description}
                  />

                  <Campo
                    field="evidence"
                    label={REPORT_LABELS.evidence}
                    value={error.evidence}
                    variant="evidence"
                  />

                  <Campo
                    field="impact"
                    label={REPORT_LABELS.impact}
                    value={error.impact}
                  />

                  <Campo
                    field="action"
                    label={REPORT_LABELS.action}
                    value={error.action}
                    variant="action"
                  />
                </View>
              )
            )}
          </Section>
        )
      case 'recommendations':
        return (
          <Section
          key={section.id}
          id={section.id}
            title={section.title}
            palette={
              paletas.recomendaciones
            }
          >
            <Lista
              items={
                section.items
              }
              color="#1D4ED8"
            />
          </Section>
        )
      case 'suggestedTexts':
        return (
          <Section
          key={section.id}
          id={section.id}
            title={section.title}
            palette={paletas.sugeridos}
          >
            {section.items.map(
              (item, index) => (
                <Text
                  key={index}
                  data-report-field="item"
                  style={styles.suggestion}
                >
                  {item}
                </Text>
              )
            )}
          </Section>
        )
      case 'unverifiableItems':
        return (
          <Section
          key={section.id}
          id={section.id}
            title={section.title}
            palette={paletas.faltantes}
          >
            <Lista
              items={section.items}
              color="#92400E"
            />
          </Section>
        )
      case 'legalReferences':
        return (
          <Section
          key={section.id}
          id={section.id}
            title={section.title}
            palette={paletas.referencias}
          >
            {section.items.map(
              (referencia, index) => (
                <Text
                  key={index}
                  data-report-field="item"
                  style={styles.reference}
                >
                  {referencia}
                </Text>
              )
            )}
          </Section>
        )
      case 'originalText':
        return (
          <Section
          key={section.id}
          id={section.id}
          title={section.title}
          palette={paletas.original}
        >
          <Text data-report-field="text" style={styles.original}>
            {section.text}
          </Text>
        </Section>
        )
      default:
        return null
    }
  }

  return (
    <Document
      title={`Informe - ${header.title}`}
      author="SmartAudits"
      subject="Informe de auditoría legal"
      creator="SmartAudits"
    >
      <Page
        size="A4"
        style={styles.page}
      >
        {/* Cabecera principal */}
        <View
          style={[
            styles.hero,
            {
              backgroundColor:
                levelColor
            }
          ]}
        >
          <View style={styles.heroLeft}>
            <Text data-report-field="title" style={styles.heroTitle}>
              {header.title}
            </Text>

            <Text style={styles.heroSubtitle}>
              <Text data-report-field="documentType">{header.documentType}</Text>
              {'  ·  '}
              <Text data-report-field="date">{header.date}</Text>
            </Text>

            {header.referenceUrl && (
              <Text style={styles.heroUrl}>
                {REPORT_LABELS.url}:{' '}
                <Text data-report-field="referenceUrl">{header.referenceUrl}</Text>
              </Text>
            )}
          </View>

          <View style={styles.scoreBox}>
            <Text data-report-field="score" style={styles.score}>
              {header.score}
            </Text>

            <Text style={styles.scoreLabel}>
              {REPORT_LABELS.score}
            </Text>

            {nivel && <Text
              data-report-field="level"
              style={[
                styles.scoreBadge,
                {
                  color:
                    levelColor
                }
              ]}
            >
              {header.level}
            </Text>}
          </View>
        </View>

        {seccionesInforme(report, REPORT_POLICIES.pdf).map(renderSection)}

        {/* Pie propio del PDF */}
        <View
          style={styles.footer}
          fixed
        >
          <Text>
            SmartAudits · Informe generado{' '}
            {fechaGeneracion}
          </Text>

          <Text
            render={({
              pageNumber,
              totalPages
            }) =>
              `Página ${pageNumber} de ${totalPages}`
            }
          />
        </View>
      </Page>
    </Document>
  )
}

export default AuditoriaPdfDocument
