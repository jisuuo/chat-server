import { expect, test } from '@playwright/test'
import type { Browser, Page } from '@playwright/test'

// 기본 폴링 주기 2초(ADR-083)보다 넉넉하게 기다린다
const POLL_TIMEOUT = 10_000

async function newUser(browser: Browser, nickname: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage()
  await page.goto('/')
  await page.getByLabel('닉네임').fill(nickname)
  await page.getByRole('button', { name: '새 사용자로 시작' }).click()
  await expect(page.getByRole('button', { name: '방 만들기' })).toBeVisible()
  return page
}

async function send(page: Page, content: string) {
  await page.getByLabel('메시지').fill(content)
  await page.getByRole('button', { name: '보내기' }).click()
  await expect(page.getByRole('list', { name: '대화' })).toContainText(content)
}

async function enter(page: Page, roomHash: string) {
  await page.goto(`/${roomHash}`)
  await page.getByRole('button', { name: '입장' }).click()
  await expect(page.getByLabel('메시지')).toBeVisible()
}

test('두 사용자가 폴링으로 대화하고, 다시 입장하면 이전 메시지가 보이지 않는다 (R4)', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const a = await newUser(browser, `a-${suffix}`)
  const b = await newUser(browser, `b-${suffix}`)

  await a.getByLabel('방 이름').fill(`e2e-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash

  await enter(b, roomHash)
  await send(a, '안녕 B')
  await expect(b.getByRole('list', { name: '대화' })).toContainText('안녕 B', { timeout: POLL_TIMEOUT })

  await b.getByRole('button', { name: '나가기' }).click()
  await expect(b).toHaveURL(/#\/rooms$/)
  await send(a, '나간 뒤 메시지')

  await enter(b, roomHash)
  await send(b, '다시 왔어')
  await expect(a.getByRole('list', { name: '대화' })).toContainText('다시 왔어', { timeout: POLL_TIMEOUT })
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('안녕 B')
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('나간 뒤 메시지')
})

test('비멤버가 방 링크를 열면 입장 버튼이 보이고 메시지는 보이지 않는다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const a = await newUser(browser, `a2-${suffix}`)
  await a.getByLabel('방 이름').fill(`e2e2-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  await send(a, '비밀')

  const c = await newUser(browser, `c-${suffix}`)
  await c.goto(`/${new URL(a.url()).hash}`)
  await expect(c.getByRole('button', { name: '입장' })).toBeVisible()
  await expect(c.getByText('비밀')).toHaveCount(0)
})

test('375px에서는 목록과 대화를 한 화면씩 보이고 다크 테마를 적용한다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const a = await newUser(browser, `mobile-${suffix}`)
  await a.setViewportSize({ width: 375, height: 812 })
  await expect(a.locator('.sidebar')).toBeVisible()
  await expect(a.locator('.main')).toBeHidden()

  await a.getByLabel('방 이름').fill(`mobile-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  await expect(a.locator('.sidebar')).toBeHidden()
  await expect(a.getByRole('button', { name: '방 목록으로' })).toBeVisible()

  await send(a, '다크 모드 확인')
  await a.emulateMedia({ colorScheme: 'dark' })
  await expect(a.locator('body')).toHaveCSS('background-color', 'rgb(21, 23, 26)')
  await expect(a.locator('.msg.mine .bubble')).toHaveCSS('background-color', 'rgb(79, 143, 247)')

  await a.getByRole('button', { name: '방 목록으로' }).click()
  await expect(a.locator('.sidebar')).toBeVisible()
  await expect(a.locator('.main')).toBeHidden()
})

test('과거 조회와 상대 메시지가 읽던 위치를 지키고 여러 줄을 전송한다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const a = await newUser(browser, `scroll-a-${suffix}`)
  const b = await newUser(browser, `scroll-b-${suffix}`)
  await a.getByLabel('방 이름').fill(`scroll-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash
  const roomId = Number(roomHash.split('/').at(-1))
  const userId = await a.evaluate(() => Number(sessionStorage.getItem('chat.userId')))
  await enter(b, roomHash)

  for (let n = 1; n <= 55; n++) {
    const response = await a.request.post(`/api/rooms/${roomId}/messages`, {
      headers: { 'X-User-Id': String(userId) },
      data: { content: `seed-${String(n).padStart(2, '0')}` },
    })
    expect(response.ok()).toBeTruthy()
  }
  await a.reload()
  const list = a.getByRole('list', { name: '대화' })
  await expect(a.getByRole('button', { name: `scroll-${suffix}` })).toHaveAttribute('aria-current', 'page')
  await expect(a.getByRole('button', { name: '이전 메시지 더 보기' })).toBeVisible()
  await expect(a.locator('.msg.mine')).toHaveCount(50)
  await expect(a.locator('.msg time')).toHaveCount(50)
  await expect(a.locator('.day')).toHaveCount(1)
  await list.evaluate((element) => { element.scrollTop = 0 })
  const anchor = a.getByText('seed-06', { exact: true })
  const before = await anchor.evaluate((element) => element.getBoundingClientRect().top)
  await a.getByRole('button', { name: '이전 메시지 더 보기' }).click()
  await expect(a.getByText('seed-01', { exact: true })).toBeVisible()
  const after = await anchor.evaluate((element) => element.getBoundingClientRect().top)
  expect(Math.abs(after - before)).toBeLessThan(3)

  await b.reload()
  await expect(b.getByText('seed-55', { exact: true })).toBeVisible()
  await expect(b.locator('.msg.mine')).toHaveCount(0)
  await expect(b.locator('.sender')).toHaveCount(1)
  await expect(b.locator('.sender')).toHaveText(`scroll-a-${suffix}`)
  await b.getByRole('list', { name: '대화' }).evaluate((element) => { element.scrollTop = 0 })
  await send(a, '상대의 새 메시지')
  await expect(b.getByRole('button', { name: '새 메시지 1개' })).toBeVisible({ timeout: POLL_TIMEOUT })
  await b.getByRole('button', { name: '새 메시지 1개' }).click()
  await expect(b.getByRole('button', { name: '새 메시지 1개' })).toHaveCount(0)

  await a.getByLabel('메시지').fill('첫 줄')
  await a.getByLabel('메시지').press('Shift+Enter')
  await a.getByLabel('메시지').type('둘째 줄')
  await a.getByLabel('메시지').press('Enter')
  await expect(a.getByRole('list', { name: '대화' })).toContainText('첫 줄\n둘째 줄')
  await a.getByText('폴링 상태').click()
  await expect(a.locator('.panel')).toBeVisible()
  await expect(a.locator('.panel')).toContainText('after 커서')
  await expect(a.locator('.panel')).toContainText('X-Request-Id')
})
