import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import type { Message } from '../api/types'
import { recoverVisibleMessages } from './recover'

vi.mock('../api/chat')

const info = { status: 200, requestId: 'r', durationMs: 1 }
const msg = (id: number): Message => ({ id, roomId: 1, senderId: 2, content: `m${id}`, createdAt: '2026-10-08T00:00:00Z' })
const page = (messages: Message[], hasMore: boolean) => ({ data: { messages, hasMore }, info })

describe('recoverVisibleMessages', () => {
  beforeEach(() => vi.resetAllMocks())

  it('최신 50건 밖의 누락과 보이는 구간에 늦게 커밋한 작은 id를 함께 읽는다', async () => {
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(105), msg(106)], true))
      .mockResolvedValueOnce(page([msg(99), msg(100)], true))
      .mockResolvedValueOnce(page([msg(49), msg(50)], true))

    const messages = await recoverVisibleMessages(1, 1, 50)

    expect(messages.map((message) => message.id)).toEqual([105, 106, 99, 100, 49, 50])
    expect(chat.readMessages).toHaveBeenNthCalledWith(1, 1, 1, {})
    expect(chat.readMessages).toHaveBeenNthCalledWith(2, 1, 1, { before: 105 })
    expect(chat.readMessages).toHaveBeenNthCalledWith(3, 1, 1, { before: 99 })
  })
})
