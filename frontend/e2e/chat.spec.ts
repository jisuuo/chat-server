import { expect, test } from '@playwright/test'
import type { APIResponse, Browser, Page, WebSocketRoute } from '@playwright/test'

// 기본 폴링 주기 2초(ADR-083)보다 넉넉하게 기다린다
const POLL_TIMEOUT = 10_000
// WebSocket push는 폴링 주기와 무관하다. CI·첫 컴파일 지연을 고려한다.
const PUSH_TIMEOUT = 5_000

type Watch = { polls: string[]; restSends: string[]; frames: string[]; sentFrames: string[] }

// ADR-131·139: 기본 통로의 WS 송수신과 REST 전송·폴링 부재를 확인한다. Vite HMR 소켓은 제외한다.
function watch(page: Page): Watch {
  const result: Watch = { polls: [], restSends: [], frames: [], sentFrames: [] }
  page.on('request', (request) => {
    if (/\/api\/rooms\/\d+\/messages\?after=/.test(request.url())) result.polls.push(request.url())
    if (request.method() === 'POST' && /\/api\/rooms\/\d+\/messages(?:$|\?)/.test(request.url())) {
      result.restSends.push(request.url())
    }
  })
  page.on('websocket', (ws) => {
    if (!ws.url().includes('/ws?userId=')) return
    ws.on('framereceived', (frame) => result.frames.push(String(frame.payload)))
    ws.on('framesent', (frame) => result.sentFrames.push(String(frame.payload)))
  })
  return result
}

async function newUser(browser: Browser, nickname: string, base = '/'): Promise<{ page: Page; seen: Watch }> {
  const page = await (await browser.newContext()).newPage()
  const seen = watch(page)
  await page.goto(base)
  await page.getByLabel('닉네임').fill(nickname)
  await page.getByRole('button', { name: '새 사용자로 시작' }).click()
  await expect(page.getByRole('button', { name: '방 만들기' })).toBeVisible()
  return { page, seen }
}

async function send(page: Page, content: string) {
  if (new URL(page.url()).searchParams.get('transport') !== 'polling') {
    await expect(page.locator('.panel')).toContainText('연결됨')
  }
  await page.getByLabel('메시지').fill(content)
  await page.getByRole('button', { name: '보내기' }).click()
  await expect(page.getByRole('list', { name: '대화' })).toContainText(content)
}

async function enter(page: Page, roomHash: string, base = '/') {
  await page.goto(`${base}${roomHash}`)
  await page.getByRole('button', { name: '입장' }).click()
  await expect(page.getByLabel('메시지')).toBeVisible()
}

test('두 사용자가 WebSocket으로 대화하고 폴링 요청은 없으며, 다시 입장하면 이전 메시지가 보이지 않는다 (R4)', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const { page: a, seen: aSeen } = await newUser(browser, `a-${suffix}`)

  await a.getByLabel('방 이름').fill(`e2e-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash
  const roomId = Number(roomHash.split('/').at(-1))
  await expect(a.locator('.panel')).toContainText('연결됨')

  const { page: b, seen } = await newUser(browser, `b-${suffix}`)
  await enter(b, roomHash)
  await expect(b.locator('.panel')).toContainText('연결됨')
  await send(a, '안녕 B')
  await expect(b.getByRole('list', { name: '대화' })).toContainText('안녕 B', { timeout: PUSH_TIMEOUT })
  expect(aSeen.sentFrames.map((frame) => JSON.parse(frame))).toContainEqual({
    type: 'send', roomId, content: '안녕 B',
  })
  expect(aSeen.restSends).toEqual([])
  expect(seen.polls).toEqual([])
  expect(seen.frames.some((frame) => frame.includes('"type":"message"') && frame.includes('안녕 B'))).toBe(true)

  const bId = await b.evaluate(() => Number(sessionStorage.getItem('chat.userId')))
  const stale = await (await browser.newContext()).newPage()
  const staleSeen = watch(stale)
  await stale.goto('/')
  await stale.getByLabel('사용자 id').fill(String(bId))
  await stale.getByRole('button', { name: '이 id로 시작' }).click()
  await expect(stale.getByRole('button', { name: '방 만들기' })).toBeVisible()
  await stale.goto(`/${roomHash}`)
  await expect(stale.getByLabel('메시지')).toBeVisible()
  await stale.getByText('연결 상태').click()
  await expect(stale.locator('.panel')).toContainText('연결됨')

  await b.getByRole('button', { name: '나가기' }).click()
  await expect(b).toHaveURL(/#\/rooms$/)
  await stale.getByLabel('메시지').fill('비멤버 전송')
  await stale.getByRole('button', { name: '보내기' }).click()
  await expect(stale.getByRole('button', { name: '입장' })).toBeVisible()
  expect(staleSeen.sentFrames.some((frame) => frame.includes('"type":"send"') && frame.includes('비멤버 전송'))).toBe(true)
  expect(staleSeen.frames.some((frame) => frame.includes('"type":"error"') && frame.includes('"code":"NOT_A_MEMBER"'))).toBe(true)
  await send(a, '나간 뒤 메시지')

  await enter(b, roomHash)
  await send(b, '다시 왔어')
  await expect(a.getByRole('list', { name: '대화' })).toContainText('다시 왔어', { timeout: PUSH_TIMEOUT })
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('안녕 B')
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('나간 뒤 메시지')
})

test('?transport=polling이면 기존 폴링으로 대화한다 (Step 6 비교용 회귀)', async ({ browser }) => {
  const base = '/?transport=polling'
  const suffix = Date.now().toString(36)
  const { page: a, seen: aSeen } = await newUser(browser, `pa-${suffix}`, base)
  const { page: b, seen } = await newUser(browser, `pb-${suffix}`, base)

  await a.getByLabel('방 이름').fill(`poll-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  await enter(b, new URL(a.url()).hash, base)
  await send(a, '폴링으로')

  await expect(b.getByRole('list', { name: '대화' })).toContainText('폴링으로', { timeout: POLL_TIMEOUT })
  expect(aSeen.restSends).toHaveLength(1)
  expect(aSeen.sentFrames).toEqual([])
  expect(seen.polls.length).toBeGreaterThan(0)
  expect(seen.frames).toEqual([])
  expect(seen.sentFrames).toEqual([])
  await b.getByText('폴링 상태').click()
  await expect(b.locator('.panel')).toContainText('after 커서')
})

test('F6: 연결이 끊긴 동안 저장된 메시지는 재연결 뒤 조회해 복구한다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const { page: a } = await newUser(browser, `reconnect-a-${suffix}`)
  await a.getByLabel('방 이름').fill(`reconnect-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash
  const roomId = Number(roomHash.split('/').at(-1))
  const senderId = await a.evaluate(() => Number(sessionStorage.getItem('chat.userId')))

  const b = await (await browser.newContext()).newPage()
  let blocked = false
  let connectedRoute: WebSocketRoute | undefined
  await b.routeWebSocket(/\/ws\?userId=/, (ws) => {
    if (blocked) {
      void ws.close({ code: 1001 })
      return
    }
    ws.connectToServer()
    connectedRoute = ws
  })
  await b.goto('/')
  await b.getByLabel('닉네임').fill(`reconnect-b-${suffix}`)
  await b.getByRole('button', { name: '새 사용자로 시작' }).click()
  await enter(b, roomHash)
  await expect(b.locator('.panel')).toContainText('연결됨')
  expect(connectedRoute).toBeDefined()

  blocked = true
  await connectedRoute!.close({ code: 1001 })
  await expect(b.locator('.panel')).toContainText('끊김')
  const missed = `끊긴 동안-${suffix}`
  const response = await a.request.post(`/api/rooms/${roomId}/messages`, {
    headers: { 'X-User-Id': String(senderId) }, data: { content: missed },
  })
  expect(response.status()).toBe(201)
  blocked = false
  await expect(b.locator('.panel')).toContainText('연결됨', { timeout: POLL_TIMEOUT })
  await expect(b.getByRole('list', { name: '대화' })).toContainText(missed, { timeout: POLL_TIMEOUT })

  const after = `재연결 뒤-${suffix}`
  await send(a, after)
  await expect(b.getByRole('list', { name: '대화' })).toContainText(after, { timeout: PUSH_TIMEOUT })
  await expect(b.getByRole('list', { name: '대화' })).toContainText(missed)
})

test('F49: 최초 조회 중 수신한 프레임은 오래된 조회 응답과 합친다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const roomName = `first-load-${suffix}`
  const missed = `조회 틈-${suffix}`
  const { page: a } = await newUser(browser, `gap-a-${suffix}`)
  await a.getByLabel('방 이름').fill(roomName)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash
  const roomId = Number(roomHash.split('/').at(-1))
  const senderId = await a.evaluate(() => Number(sessionStorage.getItem('chat.userId')))
  const { page: b, seen: bSeen } = await newUser(browser, `gap-b-${suffix}`)
  await enter(b, roomHash)
  await b.getByText('연결 상태').click()
  await expect(b.locator('.panel')).toContainText('연결됨')
  const receivedCount = b.locator('.panel dt', { hasText: '받은 프레임' }).locator('xpath=following-sibling::dd[1]')
  const receivedBefore = Number(await receivedCount.textContent())

  // App 소켓은 유지하고 방 화면만 내려, 다음 최신 조회와 구독 사이를 통제한다.
  await b.evaluate(() => { window.location.hash = '#/rooms' })
  await expect(b.getByText('방을 고르거나 새로 만드세요.')).toBeVisible()
  let interceptions = 0
  let snapshotPromise: Promise<APIResponse> | undefined
  let injection: Promise<void> | undefined
  await b.route((url) => url.pathname === `/api/rooms/${roomId}/messages` && url.search === '', async (route) => {
    interceptions++
    // StrictMode가 최초 조회를 두 번 시작해도 두 응답 모두 전송 전 스냅샷으로 고정한다.
    const snapshot = await (snapshotPromise ??= route.fetch())
    await (injection ??= a.request.post(`/api/rooms/${roomId}/messages`, {
      headers: { 'X-User-Id': String(senderId) }, data: { content: missed },
    }).then(async (response) => {
      expect(response.status()).toBe(201)
      const messageId = (await response.json()).data.id as number
      await expect.poll(() => bSeen.frames.some((frame) => {
        const received = JSON.parse(frame)
        return received.type === 'message' && received.message.id === messageId
      })).toBe(true)
    }))
    await route.fulfill({ response: snapshot })
  })
  await b.evaluate((hash) => { window.location.hash = hash }, roomHash)
  await expect(b.getByLabel('메시지')).toBeVisible()
  expect(interceptions).toBeGreaterThan(0)
  // Playwright의 네트워크 프레임 관찰과 별도로 앱의 onmessage 처리까지 확인한다.
  await expect.poll(async () => Number(await receivedCount.textContent())).toBeGreaterThan(receivedBefore)
  await expect(b.getByRole('list', { name: '대화' })).toContainText(missed)
  await b.unrouteAll({ behavior: 'wait' })
})

test('F50: 연결이 열린 채 빠진 push를 주기 조회로 복구한다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const missed = `열린 연결 누락-${suffix}`
  const { page: a } = await newUser(browser, `open-gap-a-${suffix}`)
  await a.getByLabel('방 이름').fill(`open-gap-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash
  const roomId = Number(roomHash.split('/').at(-1))
  const senderId = await a.evaluate(() => Number(sessionStorage.getItem('chat.userId')))

  const b = await (await browser.newContext()).newPage()
  let dropped = 0
  await b.routeWebSocket(/\/ws\?userId=/, (ws) => {
    const server = ws.connectToServer()
    server.onMessage((message) => {
      const frame = JSON.parse(String(message))
      if (frame.type === 'message' && frame.message.content === missed) {
        dropped++
      } else {
        ws.send(message)
      }
    })
  })
  await b.goto('/')
  await b.getByLabel('닉네임').fill(`open-gap-b-${suffix}`)
  await b.getByRole('button', { name: '새 사용자로 시작' }).click()
  await enter(b, roomHash)
  await expect(b.locator('.panel')).toContainText('연결됨')
  await b.goto('/#/rooms')
  await b.clock.install()
  await b.goto(`/${roomHash}`)
  await expect(b.getByLabel('메시지')).toBeVisible()
  await expect(b.locator('.panel')).toContainText('연결됨')

  const response = await a.request.post(`/api/rooms/${roomId}/messages`, {
    headers: { 'X-User-Id': String(senderId) }, data: { content: missed },
  })
  expect(response.status()).toBe(201)
  await expect.poll(() => dropped).toBe(1)
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText(missed)

  await b.clock.fastForward(60_000)
  await expect(b.getByRole('list', { name: '대화' })).toContainText(missed)
  await expect(b.locator('.panel')).toContainText('연결됨')
})

test('비멤버가 방 링크를 열면 입장 버튼이 보이고 메시지는 보이지 않는다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const { page: a } = await newUser(browser, `a2-${suffix}`)
  await a.getByLabel('방 이름').fill(`e2e2-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  await send(a, '비밀')

  const { page: c } = await newUser(browser, `c-${suffix}`)
  await c.goto(`/${new URL(a.url()).hash}`)
  await expect(c.getByRole('button', { name: '입장' })).toBeVisible()
  await expect(c.getByText('비밀')).toHaveCount(0)
})

test('375px에서는 목록과 대화를 한 화면씩 보이고 다크 테마를 적용한다', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const { page: a } = await newUser(browser, `mobile-${suffix}`)
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
  const { page: a } = await newUser(browser, `scroll-a-${suffix}`)
  const { page: b } = await newUser(browser, `scroll-b-${suffix}`)
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
  await expect(b.getByRole('button', { name: '새 메시지 1개' })).toBeVisible({ timeout: PUSH_TIMEOUT })
  await b.getByRole('button', { name: '새 메시지 1개' }).click()
  await expect(b.getByRole('button', { name: '새 메시지 1개' })).toHaveCount(0)

  await a.getByLabel('메시지').fill('첫 줄')
  await a.getByLabel('메시지').press('Shift+Enter')
  await a.getByLabel('메시지').type('둘째 줄')
  await a.getByLabel('메시지').press('Enter')
  await expect(a.getByRole('list', { name: '대화' })).toContainText('첫 줄\n둘째 줄')
  await a.getByText('연결 상태').click()
  await expect(a.locator('.panel')).toBeVisible()
  await expect(a.locator('.panel')).toContainText('연결됨')
  await expect(a.locator('.panel')).toContainText('받은 프레임')
})
