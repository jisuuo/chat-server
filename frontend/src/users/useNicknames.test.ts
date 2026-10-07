import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { NicknameProvider, useNicknames } from './useNicknames'

vi.mock('../api/chat')
const info = { status: 200, requestId: 'r', durationMs: 1 }

describe('useNicknames', () => {
  beforeEach(() => vi.resetAllMocks())

  it('모르는 id만 조회하고, 받기 전에는 사용자 #id', async () => {
    vi.mocked(chat.listUsers).mockResolvedValue({ data: [{ id: 2, nickname: '영희' }], info })
    const { result, rerender } = renderHook(({ ids }) => useNicknames(1, ids), { initialProps: { ids: [2] } })
    expect(result.current(2)).toBe('사용자 #2')
    await waitFor(() => expect(result.current(2)).toBe('영희'))
    rerender({ ids: [2] })
    expect(chat.listUsers).toHaveBeenCalledTimes(1)
  })

  it('방을 옮겨도 진행 중인 조회를 반복하지 않고 받은 닉네임을 기억한다', async () => {
    let finish!: (value: Awaited<ReturnType<typeof chat.listUsers>>) => void
    vi.mocked(chat.listUsers).mockImplementation(() => new Promise((resolve) => { finish = resolve }))
    function Name({ room }: { room: number }) {
      const senderName = useNicknames(1, [2])
      return createElement('span', null, `${room}: ${senderName(2)}`)
    }
    const view = (room: number) => createElement(NicknameProvider, null, createElement(Name, { key: room, room }))
    const { rerender } = render(view(1))
    expect(screen.getByText('1: 사용자 #2')).toBeInTheDocument()
    expect(chat.listUsers).toHaveBeenCalledTimes(1)

    rerender(view(2))
    expect(screen.getByText('2: 사용자 #2')).toBeInTheDocument()
    expect(chat.listUsers).toHaveBeenCalledTimes(1)

    await act(async () => finish({ data: [{ id: 2, nickname: '영희' }], info }))
    expect(screen.getByText('2: 영희')).toBeInTheDocument()
    rerender(view(3))
    expect(screen.getByText('3: 영희')).toBeInTheDocument()
    expect(chat.listUsers).toHaveBeenCalledTimes(1)
  })
})
