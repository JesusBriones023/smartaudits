import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // CI/local CI verification must never load a developer's .env files.
  envDir: process.env.CI === 'true' ? false : undefined,
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
