import { act, render, screen, within } from '@testing-library/react'
import { StrictMode } from 'react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { ApiError } from '../api/client'
import type { Message } from '../api/types'
import { DEFAULT_POLL_INTERVAL_MS } from '../messages/usePolling'
import { ChatRoomPage } from './ChatRoomPage'

vi.mock('../api/chat')

const info = { status: 200, requestId: 'r', durationMs: 1 }
const msg = (id: number, senderId = 2, content = `m${id}`): Message => ({ id, roomId: 1, senderId, content, createdAt: '2026-10-07T00:00:00Z' })
const page = (messages: Message[], hasMore = false) => ({ data: { messages, hasMore }, info })

describe('ChatRoomPage', () => {
  beforeEach(() => vi.resetAllMocks())
  afterEach(() => vi.useRealTimers())

  it('멤버면 최신 메시지를 작성자 id와 함께 보여 준다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1, 2, '안녕')]))
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />)
    expect(await screen.findByText('안녕')).toBeInTheDocument()
    expect(screen.getByText('사용자 #2')).toBeInTheDocument()
    expect(chat.readMessages).toHaveBeenCalledWith(1, 1)
  })

  it('StrictMode 최초 조회의 늦은 응답은 최신 메시지와 커서를 덮어쓰지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let finishFirst!: (value: ReturnType<typeof page>) => void
    let finishSecond!: (value: ReturnType<typeof page>) => void
    vi.mocked(chat.readMessages)
      .mockImplementationOnce(() => new Promise((resolve) => { finishFirst = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { finishSecond = resolve }))
      .mockResolvedValue(page([]))
    render(<StrictMode><ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} /></StrictMode>)
    expect(chat.readMessages).toHaveBeenCalledTimes(2)

    await act(async () => finishSecond(page([msg(11)])))
    expect(screen.getByText('m11')).toBeInTheDocument()
    await act(async () => finishFirst(page([msg(10)])))
    expect(screen.getByText('m11')).toBeInTheDocument()
    expect(screen.queryByText('m10')).not.toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS)
    })
    expect(chat.readMessages).toHaveBeenNthCalledWith(3, 1, 1, { after: 11 })
  })

  it('StrictMode 최초 조회의 늦은 오류는 현재 멤버 상태를 바꾸지 않는다', async () => {
    let failFirst!: (reason: unknown) => void
    let finishSecond!: (value: ReturnType<typeof page>) => void
    vi.mocked(chat.readMessages)
      .mockImplementationOnce(() => new Promise((_, reject) => { failFirst = reject }))
      .mockImplementationOnce(() => new Promise((resolve) => { finishSecond = resolve }))
    render(<StrictMode><ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} /></StrictMode>)

    await act(async () => finishSecond(page([msg(11)])))
    await act(async () => failFirst(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 })))
    expect(screen.getByText('m11')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '입장' })).not.toBeInTheDocument()
  })

  it('멤버가 아니면 입장 버튼을 보이고, 입장하면 메시지를 읽는다', async () => {
    vi.mocked(chat.readMessages)
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
      .mockResolvedValue(page([msg(5, 2, '입장 후')]))
    vi.mocked(chat.joinRoom).mockResolvedValue({ data: { roomId: 1, userId: 1 }, info: { ...info, status: 201 } })
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '입장' }))
    expect(chat.joinRoom).toHaveBeenCalledWith(1, 1)
    expect(await screen.findByText('입장 후')).toBeInTheDocument()
  })

  it('입장이 409(이미 멤버)면 그대로 다시 읽는다', async () => {
    vi.mocked(chat.readMessages)
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
      .mockResolvedValue(page([msg(5, 2, '이미 멤버')]))
    vi.mocked(chat.joinRoom).mockRejectedValue(new ApiError(409, 'ALREADY_MEMBER', '이미 멤버입니다.', { ...info, status: 409 }))
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '입장' }))
    expect(await screen.findByText('이미 멤버')).toBeInTheDocument()
  })

  it('보낸 메시지는 바로 보이지만 폴링 커서는 조회 응답으로만 전진한다 (세부 #8)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(10)]))
      .mockResolvedValueOnce(page([msg(11, 3, '남의 메시지'), msg(12, 1, '내 메시지')]))
      .mockResolvedValue(page([]))
    vi.mocked(chat.sendMessage).mockResolvedValue({ data: msg(12, 1, '내 메시지'), info: { ...info, status: 201 } })
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />)
    await screen.findByText('m10')

    await user.type(screen.getByLabelText('메시지'), '내 메시지')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByText('내 메시지')).toBeInTheDocument()
    expect(screen.getByLabelText('메시지')).toHaveValue('')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS)
    })
    expect(chat.readMessages).toHaveBeenNthCalledWith(2, 1, 1, { after: 10 })
    expect(await screen.findByText('남의 메시지')).toBeInTheDocument()
    const items = within(screen.getByRole('list', { name: '대화' })).getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual(['사용자 #2m10', '사용자 #3남의 메시지', '사용자 #1내 메시지'])
  })

  it('이전 메시지 더 보기는 가장 오래된 id를 before로 보낸다', async () => {
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(20), msg(21)], true))
      .mockResolvedValueOnce(page([msg(18), msg(19)], false))
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '이전 메시지 더 보기' }))
    expect(chat.readMessages).toHaveBeenLastCalledWith(1, 1, { before: 20 })
    expect(await screen.findByText('m18')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이전 메시지 더 보기' })).not.toBeInTheDocument()
  })

  it('전송이 거절되면 서버 메시지를 보여 준다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    vi.mocked(chat.sendMessage).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />)
    await userEvent.type(await screen.findByLabelText('메시지'), '안녕')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('NOT_A_MEMBER')
  })

  it('나가면 방 목록으로 돌아간다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    vi.mocked(chat.leaveRoom).mockResolvedValue({ data: null, info })
    const onBack = vi.fn()
    render(<ChatRoomPage userId={1} roomId={1} onBack={onBack} />)
    await userEvent.click(await screen.findByRole('button', { name: '나가기' }))
    expect(chat.leaveRoom).toHaveBeenCalledWith(1, 1)
    expect(onBack).toHaveBeenCalled()
  })
})
