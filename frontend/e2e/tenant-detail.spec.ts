import { expect, test } from '@playwright/test'

if (!process.env.CI) {
  test.use({ channel: 'chrome' })
}

// The tenant detail page: clicking a tenant opens a page with side tabs for everything the platform manages for it.
// Read-only: it opens each tab and changes nothing.
test('platform admin opens a tenant and moves through its side tabs', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))

  await page.goto('/login')
  await page.getByRole('button', { name: /Platform Admin/ }).click()
  await expect(page).not.toHaveURL(/\/login/)

  await page.goto('/platform')
  await page.getByRole('link', { name: 'Manage Aurora Media Network' }).click()
  await expect(page).toHaveURL(/\/platform\/tenants\//)
  const nav = page.getByRole('navigation', { name: 'Tenant settings' })
  await expect(nav).toBeVisible()
  await expect(page.locator('h2', { hasText: 'Subscription' })).toBeVisible()
  await expect(page.locator('h2', { hasText: 'Projects' })).toBeVisible()

  await nav.getByRole('button', { name: 'Configuration' }).click()
  await expect(page.getByText('Configuration scope')).toBeVisible()

  await nav.getByRole('button', { name: 'Features' }).click()
  await expect(page.locator('h2', { hasText: 'Project features' })).toBeVisible()
  await expect(page).toHaveURL(/tab=features/)

  await nav.getByRole('button', { name: 'Roles' }).click()
  await expect(page.locator('h2', { hasText: 'Roles' })).toBeVisible()

  await nav.getByRole('button', { name: 'Connectors' }).click()
  await expect(page.getByText('Mux video library')).toBeVisible()

  await nav.getByRole('button', { name: 'Media types' }).click()
  await expect(page.getByText('Permitted media types')).toBeVisible()

  // the address keeps the tab: a reload opens the same place
  await page.reload()
  await expect(page.getByText('Permitted media types')).toBeVisible()

  expect(errors, 'uncaught errors in the page').toEqual([])
})
