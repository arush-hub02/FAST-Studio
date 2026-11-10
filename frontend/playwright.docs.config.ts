import { defineConfig } from '@playwright/test'

// Screenshots for the feature documentation (docs-shots/). Separate from the e2e tests so CI never rewrites the images.
export default defineConfig({
  testDir: 'docs-shots',
  timeout: 180_000,
  workers: 1,
  reporter: 'list',
  // Installed Chrome: Playwright's own Chromium cannot decode H.264, so Watch shots never start playback.
  use: { channel: 'chrome', baseURL: process.env.DOCS_BASE_URL ?? 'http://localhost:8080' },
})
