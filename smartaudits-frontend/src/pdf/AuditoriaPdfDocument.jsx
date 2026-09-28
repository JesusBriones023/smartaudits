import {
  Document,
  Page,
  Text,
  View,
  StyleSheet
} from '@react-pdf/renderer'

import { getNivelCumplimiento } from '../utils/helpers'

const texto = (valor) => {
  if (valor === null || valor === undefined) return ''
  return String(valor)
}

const lista = (valor) => Array.isArray(valor) ? valor : []

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
  title,
  palette,
  children
}) => (
  <View
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
    {lista(items).map((item, index) => (
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
          style={[
            styles.listText,
            { color }
          ]}
        >
          {texto(item)}
        </Text>
      </View>
    ))}
  </View>
)

const estiloSeveridad = (severidad) => {
  const valor = texto(severidad).toUpperCase()

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

  return {
    backgroundColor: '#DCFCE7',
    color: '#15803D'
  }
}

const Campo = ({
  label,
  value,
  variant
}) => (
  <View style={styles.field}>
    <Text style={styles.fieldLabel}>
      {label}
    </Text>

    <Text
      style={
        variant === 'evidence'
          ? styles.evidence
          : variant === 'action'
            ? styles.action
            : styles.fieldText
      }
    >
      {texto(value)}
    </Text>
  </View>
)

const AuditoriaPdfDocument = ({
  auditoria
}) => {
  const resultado = auditoria?.resultado || {}
  const puntuacion =
    auditoria?.puntuacionRiesgo ?? 0

  const nivel =
    getNivelCumplimiento(puntuacion)

  const fecha = auditoria?.fechaCreacion
    ? new Date(
      auditoria.fechaCreacion
    ).toLocaleDateString('es-ES')
    : ''

  const fechaGeneracion =
    new Date().toLocaleString('es-ES')

  return (
    <Document
      title={`Informe - ${texto(auditoria?.titulo)}`}
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
                nivel?.hex || '#2563EB'
            }
          ]}
        >
          <View style={styles.heroLeft}>
            <Text style={styles.heroTitle}>
              {texto(auditoria?.titulo)}
            </Text>

            <Text style={styles.heroSubtitle}>
              {texto(auditoria?.tipoDocumento) ||
                'Tipo no especificado'}
              {'  ·  '}
              {fecha}
            </Text>

            {auditoria?.urlOpcional && (
              <Text style={styles.heroUrl}>
                URL de referencia:{' '}
                {texto(auditoria.urlOpcional)}
              </Text>
            )}
          </View>

          <View style={styles.scoreBox}>
            <Text style={styles.score}>
              {puntuacion}
            </Text>

            <Text style={styles.scoreLabel}>
              Puntuación de Cumplimiento
            </Text>

            <Text
              style={[
                styles.scoreBadge,
                {
                  color:
                    nivel?.hex || '#2563EB'
                }
              ]}
            >
              {texto(nivel?.etiqueta)}
            </Text>
          </View>
        </View>

        {/* Resumen */}
        <Section
          title="Resumen Ejecutivo"
          palette={paletas.resumen}
        >
          <Text style={styles.paragraph}>
            {texto(resultado.resumen) ||
              'Sin resumen disponible.'}
          </Text>
        </Section>

        {/* Incumplimientos */}
        {lista(resultado.riesgos).length > 0 && (
          <Section
            title={`Incumplimientos Detectados (${resultado.riesgos.length})`}
            palette={paletas.riesgos}
          >
            <Lista
              items={resultado.riesgos}
              color="#991B1B"
            />
          </Section>
        )}

        {/* Errores */}
        {lista(resultado.errores).length > 0 && (
          <Section
            title={`Errores Detectados (${resultado.errores.length})`}
            palette={paletas.errores}
          >
            {resultado.errores.map(
              (error, index) => (
                <View
                  key={index}
                  style={styles.errorCard}
                >
                  <View
                    style={styles.errorHeader}
                  >
                    <Text
                      style={styles.errorTitle}
                    >
                      {texto(error.titulo)}
                    </Text>

                    <Text
                      style={[
                        styles.severity,
                        estiloSeveridad(
                          error.severidad
                        )
                      ]}
                    >
                      {texto(error.severidad)}
                    </Text>
                  </View>

                  <Campo
                    label="Descripción"
                    value={error.descripcion}
                  />

                  <Campo
                    label="Evidencia"
                    value={error.evidencia}
                    variant="evidence"
                  />

                  <Campo
                    label="Impacto"
                    value={error.impacto}
                  />

                  <Campo
                    label="Acción correctiva"
                    value={error.accion}
                    variant="action"
                  />
                </View>
              )
            )}
          </Section>
        )}

        {/* Recomendaciones */}
        {lista(resultado.recomendaciones)
          .length > 0 && (
          <Section
            title="Recomendaciones"
            palette={
              paletas.recomendaciones
            }
          >
            <Lista
              items={
                resultado.recomendaciones
              }
              color="#1D4ED8"
            />
          </Section>
        )}

        {/* Textos sugeridos */}
        {lista(resultado.textosSugeridos)
          .length > 0 && (
          <Section
            title="Textos Sugeridos"
            palette={paletas.sugeridos}
          >
            {resultado.textosSugeridos.map(
              (item, index) => (
                <Text
                  key={index}
                  style={styles.suggestion}
                >
                  {texto(item)}
                </Text>
              )
            )}
          </Section>
        )}

        {/* Faltantes */}
        {lista(resultado.faltantes).length >
          0 && (
          <Section
            title="Elementos No Verificables"
            palette={paletas.faltantes}
          >
            <Lista
              items={resultado.faltantes}
              color="#92400E"
            />
          </Section>
        )}

        {/* Referencias */}
        {lista(resultado.referenciasLegales)
          .length > 0 && (
          <Section
            title="Referencias Legales"
            palette={paletas.referencias}
          >
            {resultado.referenciasLegales.map(
              (referencia, index) => (
                <Text
                  key={index}
                  style={styles.reference}
                >
                  {texto(referencia)}
                </Text>
              )
            )}
          </Section>
        )}

        {/* Texto original */}
        <Section
          title="Texto Original Auditado"
          palette={paletas.original}
        >
          <Text style={styles.original}>
            {texto(
              auditoria?.textoOriginal
            )}
          </Text>
        </Section>

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