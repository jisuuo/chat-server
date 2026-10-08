import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chatSocket from './chatSocket'
import { ChatSocketProvider, useChatSocket } from './useChatSocket'
import { fakeChatSocket } from '../test/fakeChatSocket'

vi.mock('./chatSocket')

function Probe() {
  return <p>{useChatSocket() ? '소켓 있음' : '소켓 없음'}</p>
}

describe('ChatSocketProvider', () => {
  beforeEach(() => vi.resetAllMocks())

  it('enabled면 사용자 연결을 시작하고, 언마운트하면 멈춘다', () => {
    const fake = fakeChatSocket()
    const start = vi.spyOn(fake.socket, 'start')
    const stop = vi.spyOn(fake.socket, 'stop')
    vi.mocked(chatSocket.createChatSocket).mockReturnValue(fake.socket)
    const { unmount } = render(<ChatSocketProvider userId={3} enabled><Probe /></ChatSocketProvider>)
    expect(chatSocket.createChatSocket).toHaveBeenCalledWith(3)
    expect(start).toHaveBeenCalled()
    expect(screen.getByText('소켓 있음')).toBeInTheDocument()
    unmount()
    expect(stop).toHaveBeenCalled()
  })

  it('enabled가 아니면 연결하지 않고 소켓을 내주지 않는다 (계획 7 세부 10)', () => {
    const fake = fakeChatSocket()
    const start = vi.spyOn(fake.socket, 'start')
    vi.mocked(chatSocket.createChatSocket).mockReturnValue(fake.socket)
    render(<ChatSocketProvider userId={3} enabled={false}><Probe /></ChatSocketProvider>)
    expect(start).not.toHaveBeenCalled()
    expect(screen.getByText('소켓 없음')).toBeInTheDocument()
  })
})
