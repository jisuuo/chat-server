import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, CONNECTION_ERROR, apiFetch, errorMessage } from './client'

function respond(status: number, body: unknown, requestId = 'r-1') {
  const text = typeof body === 'string' ? body : JSON.stringify(body)
  return vi.fn().mockResolvedValue(new Response(text, { status, headers: { 'X-Request-Id': requestId } }))
}

describe('apiFetch', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('성공하면 data와 상태·요청 id를 돌려준다', async () => {
    vi.stubGlobal('fetch', respond(200, { success: true, data: { id: 1 }, error: null }))
    const result = await apiFetch<{ id: number }>('/api/rooms', { userId: 3 })
    expect(result.data).toEqual({ id: 1 })
    expect(result.info.status).toBe(200)
    expect(result.info.requestId).toBe('r-1')
    expect(result.info.durationMs).toBeGreaterThanOrEqual(0)
  })

  it('X-User-Id와 JSON 본문을 보낸다', async () => {
    const fetch = respond(201, { success: true, data: null, error: null })
    vi.stubGlobal('fetch', fetch)
    await apiFetch('/api/rooms', { method: 'POST', userId: 3, body: { name: '방' } })
    const [path, init] = fetch.mock.calls[0]
    expect(path).toBe('/api/rooms')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'X-User-Id': '3', 'Content-Type': 'application/json' })
    expect(init.body).toBe('{"name":"방"}')
  })

  it('사용자가 없으면 X-User-Id를 보내지 않는다', async () => {
    const fetch = respond(201, { success: true, data: { id: 1, nickname: 'a' }, error: null })
    vi.stubGlobal('fetch', fetch)
    await apiFetch('/api/dev/users', { method: 'POST', body: { nickname: 'a' } })
    expect(fetch.mock.calls[0][1].headers).toEqual({ 'Content-Type': 'application/json' })
  })

  it('실패 응답은 ApiError로 바꾼다', async () => {
    vi.stubGlobal('fetch', respond(403, { success: false, data: null, error: { code: 'NOT_A_MEMBER', message: '멤버가 아닙니다.' } }, 'r-9'))
    const error = await apiFetch('/api/rooms/1/messages', { userId: 3 }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 403, code: 'NOT_A_MEMBER', message: '멤버가 아닙니다.' })
    expect((error as ApiError).info.requestId).toBe('r-9')
  })

  it('JSON이 아닌 응답(예: 백엔드가 꺼져 proxy가 502)도 ApiError', async () => {
    vi.stubGlobal('fetch', respond(502, '<html>Bad Gateway</html>'))
    await expect(apiFetch('/api/rooms', { userId: 3 })).rejects.toMatchObject({ status: 502, code: 'UNKNOWN', message: 'HTTP 502' })
  })
})

describe('errorMessage', () => {
  it('ApiError는 서버 문구만, 연결 실패는 안내 문구 (ADR-118)', () => {
    const info = { status: 409, requestId: null, durationMs: 1 }
    expect(errorMessage(new ApiError(409, 'ALREADY_MEMBER', '이미 멤버입니다.', info))).toBe('이미 멤버입니다.')
    expect(errorMessage(new ApiError(502, 'UNKNOWN', 'HTTP 502', { ...info, status: 502 }))).toBe(CONNECTION_ERROR)
    expect(errorMessage(new TypeError('Failed to fetch'))).toBe(CONNECTION_ERROR)
  })
})
