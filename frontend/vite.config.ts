import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': backend, '/play': backend, '/media': backend },
  },
  // hls.js (593 kB) is already its own chunk, loaded only when a video plays; warn only above it.
  build: { outDir: '../backend/src/main/resources/static', emptyOutDir: true, chunkSizeWarningLimit: 650 },
})
