const ExternalNormativeLink = ({ ley, desc, url }) => (
  <a href={url} target="_blank" rel="noopener noreferrer"
    className="flex items-center space-x-3 p-3 bg-slate-50 border border-slate-200 rounded-xl hover:border-primary-300 hover:bg-primary-50 transition-colors group">
    <span className="text-xs font-bold text-primary-700 bg-primary-100 px-2 py-1 rounded-lg flex-shrink-0">{ley}</span>
    <span className="text-xs text-slate-600 group-hover:text-primary-700 transition-colors flex-1">{desc}</span>
    <svg className="w-4 h-4 text-slate-400 group-hover:text-primary-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
    </svg>
  </a>
)

export default ExternalNormativeLink
