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
  await expect(b.getByRole('button', { name: '방 만들기' })).toBeVisible()
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
