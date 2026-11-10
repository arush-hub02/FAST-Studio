import { expect, test } from '@playwright/test'

if (!process.env.CI) {
  test.use({ channel: 'chrome' })
}

// Ad delivery per ad server: with the Direct ad delivery feature on, an ad server connection can play the ad server's
// own HLS ("Direct from the ad server"); without it the choice isn't offered. Leaves no data behind.
async function pickAurora(page: import('@playwright/test').Page) {
  const select = page.getByLabel('Project')
  await select.selectOption(await select.locator('option', { hasText: 'Aurora' }).first().getAttribute('value') ?? '')
}

test('platform sets an ad server to Direct only while the feature is on', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const name = 'E2E direct ' + Date.now()
  const feature = page.getByRole('checkbox', { name: /Direct delivery/ })

  await page.goto('/login')
  await page.getByRole('button', { name: /Platform Admin/ }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/platform/connectors')
  await pickAurora(page)
  await expect(feature).toBeVisible()
  if (await feature.isChecked()) { await feature.click(); await expect(feature).not.toBeChecked() }
  await expect(page.getByLabel('Delivery for a new ad server')).toHaveCount(0)

  await feature.click()   // saved by the server, then shown
  await expect(feature).toBeChecked()
  await page.getByLabel('Ad server name').fill(name)
  await page.getByLabel('VAST tag URL').fill('https://pubads.g.doubleclick.net/gampad/ads?iu=/e2e-direct')
  await page.getByLabel('Delivery for a new ad server').selectOption({ label: 'Direct from the ad server' })
  await page.getByRole('button', { name: 'Add to waterfall' }).click()
  const row = page.locator('.row', { hasText: name })
  await expect(row.getByText('Direct', { exact: true })).toBeVisible()

  await page.reload()
  await pickAurora(page)
  await expect(page.locator('.row', { hasText: name }).getByText('Direct', { exact: true })).toBeVisible()
  await page.locator('.row', { hasText: name }).getByRole('button', { name: 'Remove' }).click()
  await expect(page.locator('.row', { hasText: name })).toHaveCount(0)
  await feature.click()
  await expect(feature).not.toBeChecked()
  await expect(page.getByLabel('Delivery for a new ad server')).toHaveCount(0)
  expect(errors).toEqual([])
})
