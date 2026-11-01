// Takes the screenshots for docs/html/FEATURES.html from the running demo, following the recipes in docs/features/features.json.
// Run: npm run docs:shots   (demo mode; DOCS_BASE_URL changes the address). Then: node ../tools/build-feature-docs.mjs
import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

type Locate = { role?: string; name?: string; text?: string; placeholder?: string; label?: string; nth?: number; selector?: string }
type Step = { click?: Locate } | { fill?: Locate & { value: string } } | { select?: Locate & { value: string } }
  | { waitFor?: Locate } | { wait?: number } | { eval?: string }
type Shot = { id: string; account: string; path: string; steps?: Step[]; target?: string; mask?: string[]; caption: string }
type Feature = { id: string; screenshots: Shot[]; cleanup?: string }

const DOCS = join(process.cwd(), '..', 'docs', 'features')   // run from frontend/ (npm run docs:shots)
const catalog = JSON.parse(readFileSync(join(DOCS, 'features.json'), 'utf8')) as { accounts: Record<string, string>; features: Feature[] }
mkdirSync(join(DOCS, 'img'), { recursive: true })

function locate(page: Page, l: Locate) {
  if (l.selector) return page.locator(l.selector).nth(l.nth ?? 0)
  if (l.role) return page.getByRole(l.role as Parameters<Page['getByRole']>[0], { name: l.name, exact: false }).nth(l.nth ?? 0)
  if (l.placeholder) return page.getByPlaceholder(l.placeholder).nth(l.nth ?? 0)
  if (l.label) return page.getByLabel(l.label).nth(l.nth ?? 0)
  if (l.text) return page.getByText(l.text).nth(l.nth ?? 0)
  return page.locator('select').nth(l.nth ?? 0)     // { nth } alone: the nth drop-down on the page
}

async function run(page: Page, step: Step) {
  if ('click' in step && step.click) await locate(page, step.click).click()
  else if ('fill' in step && step.fill) await locate(page, step.fill).fill(step.fill.value)
  else if ('select' in step && step.select) await locate(page, step.select).selectOption(step.select.value)
  else if ('waitFor' in step && step.waitFor) await expect(locate(page, step.waitFor)).toBeVisible({ timeout: 15_000 })
  else if ('wait' in step && step.wait) await page.waitForTimeout(step.wait)
  else if ('eval' in step && step.eval) await page.evaluate(step.eval)
}

test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' })

for (const feature of catalog.features.filter(f => f.screenshots.length)) {
  test(feature.id, async ({ page }) => {
    let signedIn = ''
    for (const shot of feature.screenshots) {
      await test.step(shot.id, async () => {
        const person = catalog.accounts[shot.account] ?? shot.account
        if (person !== signedIn) {
          await page.context().clearCookies()
          await page.goto('/login')
          await page.getByRole('button', { name: new RegExp(person) }).click()
          await page.waitForURL(u => !u.pathname.startsWith('/login'))
          signedIn = person
        }
        await page.goto(shot.path)
        await page.waitForLoadState('networkidle')
        for (const step of shot.steps ?? []) await run(page, step)
        await page.waitForTimeout(400)                    // let transitions settle
        const file = join(DOCS, 'img', `${feature.id}--${shot.id}.png`)
        const mask = (shot.mask ?? []).map(s => page.locator(s))
        if (shot.target) await page.locator(shot.target).first().screenshot({ path: file, mask })
        else await page.screenshot({ path: file, mask })
        if (feature.cleanup) await page.evaluate(feature.cleanup)
      })
    }
  })
}
