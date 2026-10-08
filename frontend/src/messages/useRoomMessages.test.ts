import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import type { PropsWithChildren } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { ApiError } from '../api/client'
import type { Message } from '../api/types'
import { ChatSocketContext } from '../realtime/useChatSocket'
import { fakeChatSocket } from '../test/fakeChatSocket'
import { useRoomMessages } from './useRoomMessages'

vi.mock('../api/chat')

const info = { status: 200, requestId: 'r', durationMs: 1 }
const msg = (id: number, senderId = 2): Message => ({ id, roomId: 1, senderId, content: `m${id}`, createdAt: '2026-10-07T00:00:00Z' })
const page = (messages: Message[], hasMore = false) => ({ data: { messages, hasMore }, info })

describe('useRoomMessages', () => {
  // 계획 7 세부 10: 기존 테스트는 ?transport=polling 회귀로 남긴다
  beforeEach(() => {
    vi.resetAllMocks()
    window.history.replaceState(null, '', '/?transport=polling')
  })
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('최신 메시지를 읽고 커서를 마지막 id로 둔다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(3), msg(4)], true))
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.messages.map((m) => m.id)).toEqual([3, 4])
    expect(result.current.hasOlder).toBe(true)
    expect(result.current.polling.cursor).toBe(4)
  })

  it('403 NOT_A_MEMBER면 notMember', async () => {
    vi.mocked(chat.readMessages).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('notMember'))
  })

  it('send는 성공하면 메시지를 합치고 true, 실패하면 오류를 남기고 false', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1)]))
    vi.mocked(chat.sendMessage)
      .mockResolvedValueOnce({ data: msg(2, 1), info: { ...info, status: 201 } })
      .mockRejectedValueOnce(new ApiError(400, 'INVALID_REQUEST', '요청 값이 올바르지 않습니다.', { ...info, status: 400 }))
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('ready'))

    let ok = false
    await act(async () => { ok = await result.current.send('안녕') })
    expect(ok).toBe(true)
    expect(result.current.messages.map((m) => m.id)).toEqual([1, 2])
    // ADR-083: 내 메시지 id로 커서를 옮기지 않는다
    expect(result.current.polling.cursor).toBe(1)

    await act(async () => { ok = await result.current.send('') })
    expect(ok).toBe(false)
    expect(result.current.error).not.toBeNull()
  })

  it('leave는 성공하면 true', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    vi.mocked(chat.leaveRoom).mockResolvedValue({ data: null, info })
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('ready'))
    let ok = false
    await act(async () => { ok = await result.current.leave() })
    expect(ok).toBe(true)
    expect(chat.leaveRoom).toHaveBeenCalledWith(1, 1)
  })
})

describe('useRoomMessages (websocket)', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    window.history.replaceState(null, '', '/')
  })

  function withSocket(socket: ReturnType<typeof fakeChatSocket>['socket']) {
    return ({ children }: PropsWithChildren) => createElement(ChatSocketContext.Provider, { value: socket }, children)
  }

  it('같은 방 push를 합치고 다른 방은 무시하며 폴링하지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1)]))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.transport).toBe('websocket')

    act(() => fake.push({ type: 'message', message: msg(3) }))
    act(() => fake.push({ type: 'message', message: msg(2) }))
    act(() => fake.push({ type: 'message', message: { ...msg(4), roomId: 9 } }))
    expect(result.current.messages.map((m) => m.id)).toEqual([1, 2, 3])

    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(chat.readMessages).toHaveBeenCalledTimes(1)
    expect(result.current.connection?.state).toBe('open')
    vi.useRealTimers()
  })

  it('ready가 되기 전(멤버 아님)에는 push를 합치지 않는다 (F49 관찰 대상)', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('notMember'))
    act(() => fake.push({ type: 'message', message: msg(5) }))
    expect(result.current.messages).toEqual([])
  })
})
