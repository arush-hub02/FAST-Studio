import { expect, test } from '@playwright/test'

if (!process.env.CI) {
  test.use({ channel: 'chrome' })
}

test('platform admin can view global configuration cards and use modal forms', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))

  await page.goto('/login')
  // Sign in as Platform Admin
  await page.getByRole('button', { name: /Platform Admin/ }).click()
  await expect(page).not.toHaveURL(/\/login/)

  // Navigate to Global Configuration
  await page.goto('/platform/configuration')
  await expect(page.locator('h1')).toContainText('Global configuration')

  // Verify Connector definitions tab (default)
  await expect(page.getByRole('button', { name: 'Connector definitions' })).toHaveClass(/active/)
  await expect(page.locator('.connector-card').first()).toBeVisible()

  // Verify connector cards exist (e.g. Mux, Cloudflare R2, VAST)
  const cards = page.locator('.connector-card')
  expect(await cards.count()).toBeGreaterThanOrEqual(3)

  // Verify Mux card has image icon and badges
  const muxCard = cards.filter({ hasText: 'Mux' }).first()
  await expect(muxCard).toBeVisible()
  await expect(muxCard.locator('.connector-icon-wrap')).toBeVisible()
  await expect(muxCard.locator('.badge', { hasText: 'Media' })).toBeVisible()
  await expect(muxCard.locator('.chip', { hasText: 'Mux Driver' })).toBeVisible()

  // Open "Add connector" modal and create a test connector definition
  const testKey = 'test_cdn_' + Date.now().toString().slice(-4)
  await page.getByRole('button', { name: 'Add connector' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Add connector definition')
  await expect(page.locator('.connector-preview-box')).toBeVisible()

  // Fill in form
  await page.locator('input[placeholder*="mux, r2"]').fill(testKey)
  await page.locator('input[placeholder*="Cloudflare R2"]').fill('Test Edge CDN')
  await page.locator('select').first().selectOption('STORAGE')
  await page.locator('textarea').fill('Test high performance edge storage')
  await page.getByRole('button', { name: 'Create connector' }).click()

  // Verify modal closes and new card appears
  await expect(page.getByRole('dialog')).not.toBeVisible()
  const newCard = page.locator('.connector-card', { hasText: testKey })
  await expect(newCard).toBeVisible()
  await expect(newCard.locator('.connector-icon-wrap')).toBeVisible()
  await expect(newCard.locator('.badge', { hasText: 'Storage' })).toBeVisible()

  // Edit the new connector
  await newCard.getByRole('button', { name: 'Edit definition' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText(`Edit connector: Test Edge CDN`)
  await page.locator('input[placeholder*="Cloudflare R2"]').fill('Test Edge CDN Updated')
  await page.getByRole('button', { name: 'Save connector' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.locator('.connector-card', { hasText: testKey })).toContainText('Test Edge CDN Updated')

  // Open "Edit definition" modal on Mux
  await muxCard.getByRole('button', { name: 'Edit definition' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Edit connector: Mux')
  await page.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()

  // Switch to Features & permissions: separate sections for features, permissions, feature groups and modules
  await page.getByRole('button', { name: 'Features & permissions' }).click()
  await expect(page.locator('h2', { hasText: 'Features' }).first()).toBeVisible()
  await expect(page.getByRole('cell', { name: /Library self-sync/ })).toBeVisible()

  // Add a custom permission
  const stamp = Date.now().toString().slice(-5)
  const permKey = 'TEST_EXPORT_' + stamp
  await page.getByRole('tab', { name: /^Permissions/ }).click()
  await page.getByRole('button', { name: 'Add permission' }).click()
  await expect(page.getByRole('dialog')).toContainText('Add a permission')
  await page.getByRole('dialog').locator('input[placeholder="e.g. CLIPS_EXPORT"]').fill(permKey)
  await page.getByRole('dialog').getByLabel('Name').fill('Test export')
  await page.getByRole('button', { name: 'Save permission' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.getByRole('cell', { name: new RegExp(permKey) }).first()).toBeVisible()

  // Add a feature that uses it
  const featureKey = 'TEST_CLIPS_' + stamp
  await page.getByRole('tab', { name: /^Features/ }).click()
  await page.getByRole('button', { name: 'Add feature' }).click()
  await expect(page.getByRole('dialog')).toContainText('Add a feature')
  await page.getByRole('dialog').locator('input[placeholder="e.g. CLIPS"]').fill(featureKey)
  await page.getByRole('dialog').getByLabel('Name').fill('Test clips')
  await page.getByRole('dialog').getByRole('checkbox', { name: permKey }).check()
  await page.getByRole('button', { name: 'Add feature' }).last().click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  const row = page.getByRole('row', { name: new RegExp(featureKey) })
  await expect(row).toContainText(permKey)

  // Feature groups and modules have their own lists
  await page.getByRole('tab', { name: /^Feature groups/ }).click()
  await expect(page.getByRole('cell', { name: /Library/ }).first()).toBeVisible()
  await page.getByRole('tab', { name: /^Modules/ }).click()
  await expect(page.getByRole('cell', { name: /Media/ }).first()).toBeVisible()

  // Switch to Properties tab
  await page.getByRole('button', { name: 'Properties' }).click()
  await expect(page.locator('h2', { hasText: 'Dynamic property catalogue' })).toBeVisible()
  await page.getByRole('button', { name: 'Add property' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Add property definition')
  await page.getByRole('button', { name: 'Cancel' }).click()

  // Switch to Subscription plans tab
  await page.getByRole('button', { name: 'Subscription plans' }).click()
  await expect(page.locator('h2', { hasText: 'Subscription plans' })).toBeVisible()
  await page.getByRole('button', { name: 'Add plan' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Add subscription plan')
  await page.getByRole('button', { name: 'Cancel' }).click()

  // Switch to Organization roles tab
  await page.getByRole('button', { name: 'Organization roles' }).click()
  await expect(page.locator('h2', { hasText: 'Configurable organization roles' })).toBeVisible()
  await page.getByRole('button', { name: 'Add role' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Define organization role')
  await expect(page.getByRole('dialog')).toContainText('Permissions by module, feature group and feature')
  await expect(page.getByRole('dialog').getByRole('tablist', { name: 'Modules' })).toBeVisible()
  await page.getByRole('button', { name: 'Cancel' }).click()

  expect(errors, 'uncaught errors in the page').toEqual([])
})
