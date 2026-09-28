import { createServer } from 'vite'
import react from '@vitejs/plugin-react'

// No .env loading, production config, or fallback to the habitual backend.
const backend = process.env.E2E_BACKEND_URL
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(backend || '')) {
  throw new Error('An isolated loopback backend is required.')
}
const server = await createServer({
  configFile: false,
  envDir: false,
  plugins: [react()],
  define: { 'import.meta.env.VITE_API_URL': JSON.stringify('/api') },
  server: {
    host: '127.0.0.1',
    port: Number(process.env.E2E_FRONTEND_PORT),
    strictPort: true,
    proxy: {
      // Preserve Host so Spring sees the browser's same-origin request through
      // this reverse proxy. The production CORS allowlist stays unchanged.
      '/api': { target: backend, changeOrigin: false, rewrite: path => path.replace(/^\/api/, '') },
    },
  },
})
await server.listen()
console.log('E2E_FRONTEND_READY')
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => { await server.close(); process.exit(0) })
}
