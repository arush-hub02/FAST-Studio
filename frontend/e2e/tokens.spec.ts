import { expect, test } from '@playwright/test'

// The API Tokens tab on every demo role's profile: the right scopes and roles, and a real token created, used and revoked.
const people = [
  { button: /Asha Rao/, scopes: 2, firstRoles: ['Owner (everything you can do)', 'Admin', 'Content manager', 'Channel operator', 'Ad operations', 'Read-only (analyst)'] },
  { button: /Ravi Menon/, scopes: 1, firstRoles: ['Channel operator', 'Read-only (analyst)'] },
  { button: /Lena Fischer/, scopes: 1, firstRoles: ['Read-only (analyst)'] },
  { button: /Sam Lee/, scopes: 1, firstRoles: ['Partner admin', 'Read-only (partner viewer)'] },
  { button: /Platform Admin/, scopes: 1, firstRoles: ['Platform administrator'] },
]

for (const p of people) {
  test(`API Tokens tab for ${p.button.source}`, async ({ page, request }) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))
    await page.goto('/login')
    await page.getByRole('button', { name: p.button }).click()
    await page.waitForURL(u => !u.pathname.startsWith('/login'))
    await page.getByRole('button', { name: 'Profile and Settings' }).click()
    await page.getByRole('button', { name: /API Tokens/ }).click()
    const selects = page.locator('form select')
    await expect(selects.nth(0).locator('option')).toHaveCount(p.scopes)
    await expect(selects.nth(1).locator('option')).toHaveText(p.firstRoles)

    await page.getByPlaceholder('Token name').fill('e2e check')
    await selects.nth(2).selectOption('never')
    await page.getByRole('button', { name: 'Create token' }).click()
    const token = await page.getByLabel('New token').inputValue()
    expect(token).toMatch(/^fsp_/)
    const me = await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } })
    expect(me.status()).toBe(200)
    await expect(page.locator('.settings-tab-content table')).toContainText('e2e check')
    await expect(page.locator('.settings-tab-content table')).toContainText('Never')

    const rows = page.locator('.settings-tab-content tbody tr')
    while (await rows.count() > 0) {
      const before = await rows.count()
      await page.getByRole('button', { name: 'Revoke' }).first().click()
      await page.getByRole('button', { name: 'Revoke', exact: true }).last().click()
      await expect(rows).toHaveCount(before - 1)
    }
    expect((await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(401)
    expect(errors).toEqual([])
  })
}
