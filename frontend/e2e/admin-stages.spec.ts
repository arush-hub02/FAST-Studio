import { expect, test, type Page } from '@playwright/test'

if (!process.env.CI) {
  test.use({ channel: 'chrome' })
}

async function signIn(page: Page, who: RegExp) {
  await page.context().clearCookies()
  await page.goto('/login')
  await page.getByRole('button', { name: who }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

// P2: the platform finds a person, adds them to an organization, switches the account off and on, and removes the
// membership again (leaves no change behind except the new account, which has no organization at the end).
test('platform manages people across organizations', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const email = `e2e-${Date.now()}@people.test`
  await signIn(page, /Platform Admin/)
  await page.goto('/platform/people')
  await expect(page.getByRole('heading', { name: 'People' })).toBeVisible()
  await page.getByRole('searchbox', { name: 'Search people' }).fill('owner@aurora')
  await expect(page.getByText('owner@aurora.test')).toBeVisible()

  await page.getByRole('button', { name: 'Add person' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Email').fill(email)
  await dialog.getByLabel('Name').fill('E2E Person')
  await dialog.getByLabel('Organization').selectOption({ label: 'Northwind Sports' })
  await dialog.getByLabel('Role').selectOption({ label: 'Analyst' })
  await dialog.getByRole('button', { name: 'Add' }).click()
  await expect(dialog.getByText('One-time password')).toBeVisible()
  await dialog.getByRole('button', { name: 'Done' }).click()

  await page.getByRole('searchbox', { name: 'Search people' }).fill(email)
  const row = page.getByRole('row', { name: /E2E Person/ })
  await expect(row.getByText('Northwind Sports · Analyst')).toBeVisible()
  await row.getByRole('button', { name: 'Switch off E2E Person' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Switch off' }).click()
  await expect(row.getByText('Switched off')).toBeVisible()
  await row.getByRole('button', { name: 'Switch on E2E Person' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Switch on' }).click()
  await expect(row.getByText('Active')).toBeVisible()
  await row.getByRole('button', { name: 'Remove E2E Person from Northwind Sports' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(row.getByText('No organization')).toBeVisible()
  expect(errors).toEqual([])
})

// P4 + P5: the project owner adds and deletes an own media type, and words an email its own way and goes back.
test('project owner keeps own media types and email wording', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  await signIn(page, /Asha Rao/)

  await page.goto('/aurora/settings?tab=media-types')
  await page.getByRole('button', { name: 'New type' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('E2E Clip')
  await dialog.getByRole('button', { name: 'Save' }).click()
  const row = page.getByRole('row', { name: /E2E Clip/ })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Delete E2E Clip' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByRole('row', { name: /E2E Clip/ })).toHaveCount(0)

  await page.goto('/aurora/settings?tab=emails')
  await page.getByRole('button', { name: /Password reset link/ }).click()
  const subject = page.getByLabel('Subject')
  await subject.fill('Aurora: reset your password')
  await page.getByRole('button', { name: 'Save template' }).click()
  await expect(page.locator('.badge', { hasText: 'Customized' }).first()).toBeVisible()
  await page.getByRole('button', { name: "Back to the platform's wording" }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Go back' }).click()
  await expect(page.locator('section .badge', { hasText: 'Platform wording' })).toBeVisible()
  expect(errors).toEqual([])
})

// Tenant-wide role: one role in every project of the tenant; the project's Team page shows it but can't change it.
test('platform gives a tenant-wide role', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const email = `wide-${Date.now()}@tenant.test`
  await signIn(page, /Platform Admin/)
  await page.goto('/platform')
  await page.getByRole('link', { name: 'Manage Aurora Media Network' }).click()
  await page.getByRole('navigation', { name: 'Tenant settings' }).getByRole('button', { name: 'People' }).click()
  await page.getByRole('button', { name: 'Add tenant-wide role' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Email').fill(email)
  await dialog.getByLabel('Name').fill('E2E Wide')
  await dialog.getByLabel('Role in every project').selectOption({ label: 'Analyst' })
  await dialog.getByRole('button', { name: 'Add' }).click()
  await expect(dialog.getByText('One-time password')).toBeVisible()
  await dialog.getByRole('button', { name: 'Done' }).click()
  const row = page.getByRole('row', { name: /E2E Wide/ }).first()
  await expect(row.getByRole('combobox', { name: 'Tenant-wide role of E2E Wide' })).toHaveValue('ANALYST')
  await expect(page.getByText('tenant-wide').first()).toBeVisible()   // the people list marks the memberships
  const tenantPage = page.url()

  await signIn(page, /Asha Rao/)
  await page.goto('/aurora/team')
  const member = page.getByRole('row', { name: /E2E Wide/ })
  await expect(member.getByText('Tenant-wide role')).toBeVisible()
  await expect(member.getByRole('button', { name: 'Remove' })).toHaveCount(0)

  await signIn(page, /Platform Admin/)
  await page.goto(tenantPage)
  await page.getByRole('button', { name: "Remove E2E Wide's tenant-wide role" }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByRole('combobox', { name: 'Tenant-wide role of E2E Wide' })).toHaveCount(0)
  expect(errors).toEqual([])
})
