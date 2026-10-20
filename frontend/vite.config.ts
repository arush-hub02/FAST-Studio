import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': backend, '/play': backend, '/media': backend },
  },
  build: { outDir: '../backend/src/main/resources/static', emptyOutDir: true },
})
