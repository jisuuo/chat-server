import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { ApiError } from '../api/client'
import type { Room } from '../api/types'
import { RoomListPage } from './RoomListPage'

vi.mock('../api/chat')

const info = { status: 200, requestId: 'r', durationMs: 1 }
const room = (id: number, name: string, lastMessageId: number | null = null): Room => ({
  id, name, createdBy: 1, lastMessageId, createdAt: '2026-10-07T00:00:00Z',
})

describe('RoomListPage', () => {
  beforeEach(() => vi.resetAllMocks())

  it('목록을 보여 주고 더 보기로 다음 커서를 읽어 이어 붙인다', async () => {
    vi.mocked(chat.listRooms)
      .mockResolvedValueOnce({ data: { rooms: [room(2, '둘째', 5), room(1, '첫째')], hasMore: true, nextCursor: '-:1' }, info })
      .mockResolvedValueOnce({ data: { rooms: [room(9, '셋째')], hasMore: false, nextCursor: null }, info })
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={vi.fn()} onRoomsChange={vi.fn()} />)
    expect(await screen.findByRole('button', { name: '둘째' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(chat.listRooms).toHaveBeenLastCalledWith(3, '-:1')
    expect(await screen.findByRole('button', { name: '셋째' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '둘째' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('새로고침하면 첫 페이지로 목록을 바꾼다', async () => {
    vi.mocked(chat.listRooms)
      .mockResolvedValueOnce({ data: { rooms: [room(1, '이전 방')], hasMore: false, nextCursor: null }, info })
      .mockResolvedValueOnce({ data: { rooms: [room(2, '새 방')], hasMore: false, nextCursor: null }, info })
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={vi.fn()} onRoomsChange={vi.fn()} />)
    expect(await screen.findByRole('button', { name: '이전 방' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '새로고침' }))
    expect(await screen.findByRole('button', { name: '새 방' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이전 방' })).not.toBeInTheDocument()
    expect(chat.listRooms).toHaveBeenLastCalledWith(3, null)
  })

  it('새로고침 응답을 기다리는 동안 목록 요청 버튼을 막는다', async () => {
    let finishRefresh!: (value: Awaited<ReturnType<typeof chat.listRooms>>) => void
    const pendingRefresh = new Promise<Awaited<ReturnType<typeof chat.listRooms>>>((resolve) => {
      finishRefresh = resolve
    })
    vi.mocked(chat.listRooms)
      .mockResolvedValueOnce({ data: { rooms: [room(2, '둘째')], hasMore: true, nextCursor: '-:2' }, info })
      .mockReturnValue(pendingRefresh)
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={vi.fn()} onRoomsChange={vi.fn()} />)
    expect(await screen.findByRole('button', { name: '둘째' })).toBeInTheDocument()

    const refresh = screen.getByRole('button', { name: '새로고침' })
    await userEvent.click(refresh)
    expect(refresh).toBeDisabled()
    expect(screen.getByRole('button', { name: '더 보기' })).toBeDisabled()
    fireEvent.click(refresh)
    expect(chat.listRooms).toHaveBeenCalledTimes(2)

    await act(async () => finishRefresh({ data: { rooms: [room(3, '새 방')], hasMore: false, nextCursor: null }, info }))
    expect(refresh).toBeEnabled()
    expect(screen.getByRole('button', { name: '새 방' })).toBeInTheDocument()
  })

  it('더 보기 응답을 기다리는 동안 같은 커서를 다시 요청하지 않는다', async () => {
    let finishMore!: (value: Awaited<ReturnType<typeof chat.listRooms>>) => void
    const pendingMore = new Promise<Awaited<ReturnType<typeof chat.listRooms>>>((resolve) => {
      finishMore = resolve
    })
    vi.mocked(chat.listRooms)
      .mockResolvedValueOnce({ data: { rooms: [room(2, '둘째')], hasMore: true, nextCursor: '-:2' }, info })
      .mockReturnValue(pendingMore)
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={vi.fn()} onRoomsChange={vi.fn()} />)
    expect(await screen.findByRole('button', { name: '둘째' })).toBeInTheDocument()
    const more = screen.getByRole('button', { name: '더 보기' })
    act(() => {
      fireEvent.click(more)
      fireEvent.click(more)
    })
    expect(chat.listRooms).toHaveBeenCalledTimes(2)
    expect(more).toBeDisabled()

    await act(async () => finishMore({ data: { rooms: [room(1, '첫째')], hasMore: false, nextCursor: null }, info }))
    expect(screen.getAllByRole('button', { name: '첫째' })).toHaveLength(1)
  })

  it('더 보기 중 새로고침하면 늦은 이전 응답을 무시한다', async () => {
    let finishMore!: (value: Awaited<ReturnType<typeof chat.listRooms>>) => void
    vi.mocked(chat.listRooms)
      .mockResolvedValueOnce({ data: { rooms: [room(2, '이전 방')], hasMore: true, nextCursor: '-:2' }, info })
      .mockImplementationOnce(() => new Promise((resolve) => {
        finishMore = resolve
      }))
      .mockResolvedValueOnce({ data: { rooms: [room(3, '새 방')], hasMore: false, nextCursor: null }, info })
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={vi.fn()} onRoomsChange={vi.fn()} />)
    expect(await screen.findByRole('button', { name: '이전 방' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))
    await userEvent.click(screen.getByRole('button', { name: '새로고침' }))
    expect(await screen.findByRole('button', { name: '새 방' })).toBeInTheDocument()

    await act(async () => finishMore({ data: { rooms: [room(1, '늦은 방')], hasMore: false, nextCursor: null }, info }))
    expect(screen.queryByRole('button', { name: '이전 방' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '늦은 방' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '새 방' })).toBeInTheDocument()
  })

  it('방을 누르면 그 방을 연다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [room(2, '둘째')], hasMore: false, nextCursor: null }, info })
    const onOpen = vi.fn()
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={onOpen} onRoomsChange={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '둘째' }))
    expect(onOpen).toHaveBeenCalledWith(2)
  })

  it('방을 만들면 만든 방을 연다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [], hasMore: false, nextCursor: null }, info })
    vi.mocked(chat.createRoom).mockResolvedValue({ data: room(10, '새 방'), info: { ...info, status: 201 } })
    const onOpen = vi.fn()
    render(<RoomListPage userId={3} activeRoomId={null} onOpen={onOpen} onRoomsChange={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('방 이름'), '새 방')
    await userEvent.click(screen.getByRole('button', { name: '방 만들기' }))
    expect(chat.createRoom).toHaveBeenCalledWith(3, '새 방')
    expect(onOpen).toHaveBeenCalledWith(10)
    expect(screen.getByLabelText('방 이름')).toHaveValue('')
    expect(chat.listRooms).toHaveBeenLastCalledWith(3, null)
    expect(chat.listRooms).toHaveBeenCalledTimes(2)
  })

  it('첫 목록 조회 중 방을 만들면 조회가 끝난 뒤 새 목록을 다시 읽는다', async () => {
    let finishInitial!: (value: Awaited<ReturnType<typeof chat.listRooms>>) => void
    vi.mocked(chat.listRooms)
      .mockImplementationOnce(() => new Promise((resolve) => { finishInitial = resolve }))
      .mockResolvedValueOnce({ data: { rooms: [room(10, '새 방')], hasMore: false, nextCursor: null }, info })
    vi.mocked(chat.createRoom).mockResolvedValue({ data: room(10, '새 방'), info: { ...info, status: 201 } })
    const onOpen = vi.fn()
    render(<RoomListPage userId={3} activeRoomId={10} onOpen={onOpen} onRoomsChange={vi.fn()} />)

    await userEvent.type(screen.getByLabelText('방 이름'), '새 방')
    await userEvent.click(screen.getByRole('button', { name: '방 만들기' }))
    expect(onOpen).toHaveBeenCalledWith(10)
    expect(chat.listRooms).toHaveBeenCalledTimes(1)

    await act(async () => finishInitial({ data: { rooms: [], hasMore: false, nextCursor: null }, info }))
    expect(await screen.findByRole('button', { name: '새 방' })).toHaveAttribute('aria-current', 'page')
    expect(chat.listRooms).toHaveBeenCalledTimes(2)
  })

  it('열린 방을 표시하고, 읽은 목록을 위로 알린다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [room(2, '둘째'), room(1, '첫째')], hasMore: false, nextCursor: null }, info })
    const onRoomsChange = vi.fn()
    render(<RoomListPage userId={3} activeRoomId={2} onOpen={vi.fn()} onRoomsChange={onRoomsChange} />)
    expect(await screen.findByRole('button', { name: '둘째' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: '첫째' })).not.toHaveAttribute('aria-current')
    expect(onRoomsChange).toHaveBeenLastCalledWith([room(2, '둘째'), room(1, '첫째')])
  })

  it('없는 사용자로 방을 만들면 서버의 401 메시지를 보여 준다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [], hasMore: false, nextCursor: null }, info })
    vi.mocked(chat.createRoom).mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED', '인증 정보가 없거나 올바르지 않습니다.', { ...info, status: 401 }))
    render(<RoomListPage userId={999} activeRoomId={null} onOpen={vi.fn()} onRoomsChange={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('방 이름'), '방')
    await userEvent.click(screen.getByRole('button', { name: '방 만들기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('인증 정보가 없거나 올바르지 않습니다.')
  })
})
