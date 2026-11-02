import { expect, test } from '@playwright/test'

if (!process.env.CI) {
  test.use({ channel: 'chrome' })
}

// Connector marketplace in project Settings (P3b): the owner sees which connectors they may connect, connects a VAST
// ad server with only its external fields, and removes it again (leaves no data behind).
test('project owner connects and removes an ad server from Settings', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))

  await page.goto('/login')
  await page.getByRole('button', { name: /Asha Rao/ }).click()
  await expect(page).not.toHaveURL(/\/login/)

  await page.goto('/aurora/settings')
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('button', { name: 'Connectors' }).click()
  await expect(page).toHaveURL(/tab=connectors/)
  const panel = page.getByRole('region', { name: 'Connectors' })
  await expect(panel.getByText('Your platform administrator connects this.')).toBeVisible()   // Mux: NOT_ALLOWED

  const vast = panel.locator('.connector-card', { hasText: 'VAST ad servers' })
  await vast.getByRole('button', { name: /Connect|Add another/ }).click()
  const form = page.getByRole('form', { name: 'VAST ad servers connection' })
  await expect(form.getByLabel('VAST tag URL')).toBeVisible()
  const name = 'E2E ' + Date.now()
  await form.getByRole('textbox', { name: 'Name' }).fill(name)
  await form.getByLabel('VAST tag URL').fill('https://ads.example.test/vast')
  await form.getByRole('button', { name: 'Connect' }).click()
  await expect(vast.getByText(name)).toBeVisible()
  await expect(vast.getByText('Connected', { exact: true })).toBeVisible()

  await vast.getByRole('button', { name: `Remove ${name}` }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(vast.getByText(name)).toHaveCount(0)
  expect(errors).toEqual([])
})
