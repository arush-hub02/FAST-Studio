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

  // Switch to Features & permissions tab
  await page.getByRole('button', { name: 'Features & permissions' }).click()
  await expect(page.locator('h2', { hasText: 'Features & permissions catalogue' })).toBeVisible()
  
  // Verify capability cards and icons
  const capabilityCards = page.locator('.connector-card')
  expect(await capabilityCards.count()).toBeGreaterThanOrEqual(10)
  const libraryCard = capabilityCards.filter({ hasText: 'Library self-sync' }).first()
  await expect(libraryCard).toBeVisible()
  await expect(libraryCard.locator('.connector-icon-wrap')).toBeVisible()
  await expect(libraryCard.locator('.badge', { hasText: 'Built in' })).toBeVisible()
  await expect(libraryCard.locator('.badge', { hasText: 'Feature' })).toBeVisible()

  // Open "Add feature or permission" modal
  await page.getByRole('button', { name: 'Add feature or permission' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Add a feature or permission')
  await expect(page.locator('.connector-preview-box')).toBeVisible()

  // Create custom capability
  const testCapKey = 'TEST_EXPORT_' + Date.now().toString().slice(-4)
  await page.locator('input[placeholder*="CLIPS_EXPORT"]').fill(testCapKey)
  await page.locator('input[placeholder*="Video Clips Export"]').fill('Test Export Tool')
  await page.locator('input[placeholder*="Media, Advertising"]').fill('Media')
  await page.locator('textarea').fill('Test feature for exporting clips')
  await page.getByRole('button', { name: 'Add capability' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()

  // Verify newly created capability appears as a card
  const newCapCard = page.locator('.connector-card', { hasText: testCapKey })
  await expect(newCapCard).toBeVisible()
  await expect(newCapCard.locator('.badge', { hasText: 'On' })).toBeVisible()

  // Edit custom capability via modal
  await newCapCard.getByRole('button', { name: 'Edit' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Edit capability: Test Export Tool')
  await page.locator('input[placeholder*="Video Clips Export"]').fill('Test Export Tool Updated')
  await page.getByRole('button', { name: 'Save capability' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.locator('.connector-card', { hasText: testCapKey })).toContainText('Test Export Tool Updated')

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
  await page.getByRole('button', { name: 'Cancel' }).click()

  expect(errors, 'uncaught errors in the page').toEqual([])
})
