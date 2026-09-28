import js from '@eslint/js'
import react from 'eslint-plugin-react'
import globals from 'globals'

export default [
  { ignores: ['node_modules/**', 'dist/**', '.e2e-runtime/**', 'test-results/**', 'playwright-report/**'] },
  {
    files: ['**/*.{js,jsx,mjs}'],
    ...js.configs.recommended,
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: { react },
    settings: { react: { version: '18.2' } },
    rules: {
      ...js.configs.recommended.rules,
      ...react.configs.recommended.rules,
      // Historical unused helpers and PropTypes are not a style-migration target.
      'no-unused-vars': 'off',
      'react/prop-types': 'off',
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      'react/no-unescaped-entities': 'off',
    },
  },
  {
    files: ['src/pages/DetalleAuditoria.jsx'],
    // The URL safety check intentionally rejects ASCII control characters.
    rules: { 'no-control-regex': 'off' },
  },
]
