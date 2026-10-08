import { act, render, screen, within } from '@testing-library/react'
import { StrictMode } from 'react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { ApiError } from '../api/client'
import type { Message } from '../api/types'
import { DEFAULT_POLL_INTERVAL_MS } from '../messages/usePolling'
import { ChatSocketContext } from '../realtime/useChatSocket'
import { fakeChatSocket } from '../test/fakeChatSocket'
import { ChatRoomPage } from './ChatRoomPage'

vi.mock('../api/chat')

const info = { status: 200, requestId: 'r', durationMs: 1 }
const msg = (id: number, senderId = 2, content = `m${id}`): Message => ({ id, roomId: 1, senderId, content, createdAt: '2026-10-07T00:00:00Z' })
const page = (messages: Message[], hasMore = false) => ({ data: { messages, hasMore }, info })

describe('ChatRoomPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(chat.listUsers).mockResolvedValue({ data: [], info })
    window.history.replaceState(null, '', '/?transport=polling')
  })
  afterEach(() => { vi.useRealTimers(); window.history.replaceState(null, '', '/') })

  it('멤버면 최신 메시지를 작성자 id와 함께 보여 준다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1, 2, '안녕')]))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
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
    render(<StrictMode><ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} /></StrictMode>)
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
    render(<StrictMode><ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} /></StrictMode>)

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
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '입장' }))
    expect(chat.joinRoom).toHaveBeenCalledWith(1, 1)
    expect(await screen.findByText('입장 후')).toBeInTheDocument()
  })

  it('입장이 409(이미 멤버)면 그대로 다시 읽는다', async () => {
    vi.mocked(chat.readMessages)
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
      .mockResolvedValue(page([msg(5, 2, '이미 멤버')]))
    vi.mocked(chat.joinRoom).mockRejectedValue(new ApiError(409, 'ALREADY_MEMBER', '이미 멤버입니다.', { ...info, status: 409 }))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '입장' }))
    expect(await screen.findByText('이미 멤버')).toBeInTheDocument()
  })

  it('보낸 메시지는 바로 보이지만 폴링 커서는 조회 응답으로만 전진한다 (ADR-083)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(10)]))
      .mockResolvedValueOnce(page([msg(11, 3, '남의 메시지'), msg(12, 1, '내 메시지')]))
      .mockResolvedValue(page([]))
    vi.mocked(chat.sendMessage).mockResolvedValue({ data: msg(12, 1, '내 메시지'), info: { ...info, status: 201 } })
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
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
    const bubbles = within(screen.getByRole('list', { name: '대화' })).getAllByText(/^(m10|남의 메시지|내 메시지)$/)
    expect(bubbles.map((el) => el.textContent)).toEqual(['m10', '남의 메시지', '내 메시지'])
  })

  it('이전 메시지 더 보기는 가장 오래된 id를 before로 보낸다', async () => {
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(20), msg(21)], true))
      .mockResolvedValueOnce(page([msg(18), msg(19)], false))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '이전 메시지 더 보기' }))
    expect(chat.readMessages).toHaveBeenLastCalledWith(1, 1, { before: 20 })
    expect(await screen.findByText('m18')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이전 메시지 더 보기' })).not.toBeInTheDocument()
  })

  it('전송이 거절되면 서버 메시지를 보여 준다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    vi.mocked(chat.sendMessage).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    await userEvent.type(await screen.findByLabelText('메시지'), '안녕')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('멤버가 아닙니다.')
  })

  it('제목을 보이고, 내 메시지는 mine으로 표시한다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1, 2, '남'), msg(2, 1, '나')]))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    expect(await screen.findByRole('heading', { name: '잡담' })).toBeInTheDocument()
    expect(screen.getByText('나').closest('li')).toHaveClass('mine')
    expect(screen.getByText('남').closest('li')).not.toHaveClass('mine')
  })

  it('나가면 방 목록으로 돌아간다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    vi.mocked(chat.leaveRoom).mockResolvedValue({ data: null, info })
    const onBack = vi.fn()
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={onBack} />)
    await userEvent.click(await screen.findByRole('button', { name: '나가기' }))
    expect(chat.leaveRoom).toHaveBeenCalledWith(1, 1)
    expect(onBack).toHaveBeenCalled()
  })

  it('폴링 패널에 커서를 보이고, 일시정지하면 폴링하지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(10)]))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    await user.click(await screen.findByText('폴링 상태'))
    const panel = within(await screen.findByRole('complementary', { name: '폴링 상태' }))
    expect(panel.getByText('10')).toBeInTheDocument()

    await user.click(panel.getByRole('button', { name: '일시정지' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS * 3)
    })
    expect(chat.readMessages).toHaveBeenCalledTimes(1)
    expect(panel.getByRole('button', { name: '다시 시작' })).toBeInTheDocument()
  })

  it('진행 중 일시정지 후 재시작해도 늦은 응답은 커서를 바꾸지 않고 요청이 겹치지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    let finishOldPoll!: (value: ReturnType<typeof page>) => void
    let finishNewPoll!: (value: ReturnType<typeof page>) => void
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(10)]))
      .mockImplementationOnce(() => new Promise((resolve) => { finishOldPoll = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { finishNewPoll = resolve }))
    render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
    await user.click(await screen.findByText('폴링 상태'))
    const panel = within(await screen.findByRole('complementary', { name: '폴링 상태' }))

    await act(async () => { await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS) })
    expect(chat.readMessages).toHaveBeenCalledTimes(2)
    await user.click(panel.getByRole('button', { name: '일시정지' }))
    await user.click(panel.getByRole('button', { name: '다시 시작' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS) })
    expect(chat.readMessages).toHaveBeenCalledTimes(2)

    await act(async () => finishOldPoll(page([msg(11)])))
    expect(screen.queryByText('m11')).not.toBeInTheDocument()
    expect(panel.getByText('10')).toBeInTheDocument()
    expect(chat.readMessages).toHaveBeenNthCalledWith(3, 1, 1, { after: 10 })
    await act(async () => finishNewPoll(page([msg(12)])))
    expect(screen.getByText('m12')).toBeInTheDocument()
    expect(panel.getByText('12')).toBeInTheDocument()
  })
})

describe('ChatRoomPage (websocket)', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(chat.listUsers).mockResolvedValue({ data: [], info })
    window.history.replaceState(null, '', '/')
  })

  it('push로 받은 메시지를 보이고, 연결 상태 패널을 보인다', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(10)]))
    render(<ChatSocketContext.Provider value={fake.socket}>
      <ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />
    </ChatSocketContext.Provider>)
    await screen.findByText('m10')

    act(() => fake.push({ type: 'message', message: msg(11, 3, '실시간') }))
    expect(screen.getByText('실시간')).toBeInTheDocument()
    expect(screen.queryByText('폴링 상태')).not.toBeInTheDocument()
    await userEvent.click(screen.getByText('연결 상태'))
    expect(within(screen.getByRole('complementary', { name: '연결 상태' })).getByText('연결됨')).toBeInTheDocument()
  })
})
