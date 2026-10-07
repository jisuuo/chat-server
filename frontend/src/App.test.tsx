import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from './api/chat'
import App from './App'

vi.mock('./api/chat')
vi.mock('./pages/RoomListPage', () => ({ RoomListPage: () => <p>방 목록 화면</p> }))
vi.mock('./pages/ChatRoomPage', () => ({ ChatRoomPage: ({ roomId, title }: { roomId: number; title: string }) => <p>채팅방 {roomId} {title}</p> }))

describe('App', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    sessionStorage.clear()
    window.location.hash = ''
  })

  it('사용자가 없으면 사용자 선택 화면', () => {
    render(<App />)
    expect(screen.getByRole('button', { name: '새 사용자로 시작' })).toBeInTheDocument()
  })

  it('사용자가 있으면 hash에 따라 방 목록 또는 채팅방', () => {
    sessionStorage.setItem('chat.userId', '3')
    window.location.hash = '#/rooms/7'
    render(<App />)
    expect(screen.getByText('방 목록 화면')).toBeInTheDocument()
    expect(screen.getByText('채팅방 7 방 #7')).toBeInTheDocument()
    expect(screen.getByText('사용자 #3')).toBeInTheDocument()
  })

  it('방이 없으면 방을 고르라는 안내', () => {
    sessionStorage.setItem('chat.userId', '3')
    render(<App />)
    expect(screen.getByText('방 목록 화면')).toBeInTheDocument()
    expect(screen.getByText('방을 고르거나 새로 만드세요.')).toBeInTheDocument()
  })

  it('기존 id로 시작하고 사용자를 바꾸면 세션을 지운다', async () => {
    render(<App />)
    await userEvent.type(screen.getByLabelText('사용자 id'), '12')
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }))
    expect(screen.getByText('사용자 #12')).toBeInTheDocument()
    expect(sessionStorage.getItem('chat.userId')).toBe('12')
    await userEvent.click(screen.getByRole('button', { name: '사용자 바꾸기' }))
    expect(sessionStorage.getItem('chat.userId')).toBeNull()
    expect(screen.getByRole('button', { name: '새 사용자로 시작' })).toBeInTheDocument()
  })

  it('기존 id를 선택한 뒤 도착한 생성 응답은 저장된 사용자를 바꾸지 않는다', async () => {
    let finishCreate!: (value: Awaited<ReturnType<typeof chat.createUser>>) => void
    vi.mocked(chat.createUser).mockImplementation(() => new Promise((resolve) => {
      finishCreate = resolve
    }))
    render(<App />)
    await userEvent.type(screen.getByLabelText('닉네임'), '지수')
    await userEvent.type(screen.getByLabelText('사용자 id'), '12')
    await userEvent.click(screen.getByRole('button', { name: '새 사용자로 시작' }))
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }))
    expect(sessionStorage.getItem('chat.userId')).toBe('12')

    await act(async () => finishCreate({ data: { id: 5, nickname: '지수' }, info: { status: 201, requestId: 'r', durationMs: 1 } }))
    expect(sessionStorage.getItem('chat.userId')).toBe('12')
    expect(screen.getByText('사용자 #12')).toBeInTheDocument()
  })
})
