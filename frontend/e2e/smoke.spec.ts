// Browser smoke test against a running FAST Studio in demo mode (default http://localhost:8080; set E2E_BASE_URL).
// Signs in as the demo owner and opens every main screen: each must show its heading without an uncaught error.
// Run: npx playwright test   (CI starts the server first; see .github/workflows/ci.yml)
import { expect, test } from '@playwright/test'

const screens: [path: string, heading: RegExp][] = [
  ['/aurora', /Good (morning|afternoon|evening)/],
  ['/aurora/library', /Media Library/],
  ['/aurora/channels', /Linear Channels/],
  ['/aurora/schedule', /Broadcast Schedule/],
  ['/aurora/guide', /Programme Guide/],
  ['/aurora/watch', /Live Broadcast Monitor/],
  ['/aurora/ads', /Advertising & Monetization/],
  ['/aurora/rights', /Rights/],
  ['/aurora/distribution', /Distribution/],
  ['/aurora/monitoring', /Monitoring/],
  ['/aurora/analytics', /Analytics/],
  ['/aurora/team', /Team/],
  ['/aurora/settings', /Settings/],
  ['/aurora/learn', /How FAST works/],
]

test('demo owner can open every main screen', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))

  await page.goto('/login')
  await page.getByRole('button', { name: /Asha Rao/ }).click()
  await expect(page).toHaveURL(/\/aurora/)

  for (const [path, heading] of screens) {
    await test.step(path, async () => {
      await page.goto(path)
      await expect(page.locator('main h1, main h2').first()).toContainText(heading, { timeout: 15_000 })
    })
  }
  expect(errors, 'uncaught errors in the page').toEqual([])
})
