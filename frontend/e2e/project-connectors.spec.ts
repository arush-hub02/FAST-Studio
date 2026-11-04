import { expect, test } from '@playwright/test'

if (!process.env.CI) {
  test.use({ channel: 'chrome' })
}

// Connector marketplace (P3b, ad servers P3d). The platform adds a VAST ad server to Aurora from the tenant page with
// the form built from its fields; the project owner sees it in Settings as managed by the platform, and can't add their
// own without the AD_OWN_TAGS feature. The platform then removes it again (leaves no data behind).
test('platform adds an ad server; the project sees it read-only', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const name = 'E2E ' + Date.now()

  await page.goto('/login')
  await page.getByRole('button', { name: /Platform Admin/ }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/platform')
  await page.getByRole('link', { name: 'Manage Aurora Media Network' }).click()
  await page.getByRole('navigation', { name: 'Tenant settings' }).getByRole('button', { name: 'Connectors' }).click()
  const connections = page.getByRole('region', { name: 'Connections' })
  const vast = connections.locator('.connector-card', { hasText: 'VAST ad servers' })
  await vast.getByRole('button', { name: /Connect|Add another/ }).click()
  const form = page.getByRole('form', { name: 'VAST ad servers connection' })
  await form.getByRole('textbox', { name: 'Name' }).fill(name)
  await form.getByLabel('VAST tag URL').fill('https://ads.example.test/vast')
  await form.getByRole('button', { name: 'Connect' }).click()
  await expect(vast.getByText(name)).toBeVisible()
  const tenantPage = page.url()

  await page.context().clearCookies()   // switch accounts
  await page.goto('/login')
  await page.getByRole('button', { name: /Asha Rao/ }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/aurora/settings?tab=connectors')
  const panel = page.getByRole('region', { name: 'Connectors' })
  await expect(panel.getByText('Your platform administrator connects this.').first()).toBeVisible()   // Mux: NOT_ALLOWED
  const own = panel.locator('.connector-card', { hasText: 'VAST ad servers' })
  const row = own.locator('div.between', { hasText: name })
  await expect(row.getByText('Managed by the platform')).toBeVisible()
  await expect(row.getByRole('button', { name: 'Edit' })).toHaveCount(0)
  await expect(own.getByRole('button', { name: /Connect|Add another/ })).toHaveCount(0)   // AD_OWN_TAGS is off for Aurora

  await page.context().clearCookies()   // switch accounts
  await page.goto('/login')
  await page.getByRole('button', { name: /Platform Admin/ }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(tenantPage)
  await vast.getByRole('button', { name: `Remove ${name}` }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(vast.getByText(name)).toHaveCount(0)
  expect(errors).toEqual([])
})
