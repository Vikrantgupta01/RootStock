import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The Vinnies coordinators' app. It talks to Rootstock (the case engine) through
// its API; in development the Vite server forwards /api to Rootstock, so the
// browser never makes a cross-origin request.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: process.env.ROOTSTOCK_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
