import { Fragment } from 'react'
import { Link } from 'react-router-dom'

const links = [
  ['/aviso-legal', 'Aviso legal'],
  ['/politica-privacidad', 'Privacidad'],
  ['/politica-cookies', 'Cookies'],
  ['/condiciones-uso', 'Condiciones de uso'],
]

const containers = {
  auth: 'mt-6 pt-6 border-t border-slate-100 flex flex-wrap justify-center gap-x-4 gap-y-1',
  private: 'flex items-center gap-4',
  public: 'flex flex-wrap items-center justify-center gap-x-4 gap-y-1',
}

const LegalLinks = ({ variant }) => {
  const linkClass = variant === 'auth'
    ? 'text-xs text-slate-400 hover:text-slate-600 transition-colors'
    : 'text-xs text-slate-500 hover:text-primary-600 transition-colors'
  const separatorClass = variant === 'auth' ? 'text-slate-200 text-xs' : 'text-slate-300 text-xs'

  return (
    <div className={containers[variant]}>
      {links.map(([to, label], index) => (
        <Fragment key={to}>
          {index > 0 && <span className={separatorClass}>·</span>}
          <Link to={to} className={linkClass}>{label}</Link>
        </Fragment>
      ))}
    </div>
  )
}

export default LegalLinks
