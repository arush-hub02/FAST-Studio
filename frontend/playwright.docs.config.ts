import { defineConfig } from '@playwright/test'

// Screenshots for the feature documentation (docs-shots/). Separate from the e2e tests so CI never rewrites the images.
export default defineConfig({
  testDir: 'docs-shots',
  timeout: 180_000,
  workers: 1,
  reporter: 'list',
  use: { baseURL: process.env.DOCS_BASE_URL ?? 'http://localhost:8080' },
})
