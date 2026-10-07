import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { ApiError } from '../api/client'
import { LoginPage } from './LoginPage'

vi.mock('../api/chat')

const info = { status: 201, requestId: 'r', durationMs: 1 }

describe('LoginPage', () => {
  beforeEach(() => vi.resetAllMocks())

  it('닉네임으로 새 사용자를 만들고 그 id로 시작한다', async () => {
    vi.mocked(chat.createUser).mockResolvedValue({ data: { id: 5, nickname: '지수' }, info })
    const onLogin = vi.fn()
    render(<LoginPage onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('닉네임'), '지수')
    await userEvent.click(screen.getByRole('button', { name: '새 사용자로 시작' }))
    expect(chat.createUser).toHaveBeenCalledWith('지수')
    expect(onLogin).toHaveBeenCalledWith(5)
  })

  it('기존 id로 시작한다', async () => {
    const onLogin = vi.fn()
    render(<LoginPage onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('사용자 id'), '12')
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }))
    expect(onLogin).toHaveBeenCalledWith(12)
  })

  it('기존 id를 선택한 뒤 끝난 사용자 생성은 선택을 덮어쓰지 않는다', async () => {
    let finishCreate!: (value: Awaited<ReturnType<typeof chat.createUser>>) => void
    vi.mocked(chat.createUser).mockImplementation(() => new Promise((resolve) => {
      finishCreate = resolve
    }))
    const onLogin = vi.fn()
    render(<LoginPage onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('닉네임'), '지수')
    await userEvent.type(screen.getByLabelText('사용자 id'), '12')
    await userEvent.click(screen.getByRole('button', { name: '새 사용자로 시작' }))
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }))
    expect(onLogin).toHaveBeenCalledExactlyOnceWith(12)

    await act(async () => finishCreate({ data: { id: 5, nickname: '지수' }, info }))
    expect(onLogin).toHaveBeenCalledExactlyOnceWith(12)
  })

  it('빠르게 중복 제출해도 사용자 생성 요청은 한 번만 보낸다', async () => {
    vi.mocked(chat.createUser).mockImplementation(() => new Promise(() => {}))
    render(<LoginPage onLogin={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('닉네임'), '지수')
    const button = screen.getByRole('button', { name: '새 사용자로 시작' })
    const form = button.closest('form')!
    act(() => {
      fireEvent.submit(form)
      fireEvent.submit(form)
    })
    expect(chat.createUser).toHaveBeenCalledTimes(1)
    expect(button).toBeDisabled()
  })

  it('형식이 틀린 id는 안내하고 시작하지 않는다', async () => {
    const onLogin = vi.fn()
    render(<LoginPage onLogin={onLogin} />)
    await userEvent.type(screen.getByLabelText('사용자 id'), '007')
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }))
    expect(screen.getByRole('alert')).toHaveTextContent('1 이상의 정수')
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('서버가 거절한 닉네임은 서버 메시지를 보여 준다', async () => {
    vi.mocked(chat.createUser).mockRejectedValue(new ApiError(400, 'INVALID_REQUEST', '요청 값이 올바르지 않습니다.', { ...info, status: 400 }))
    render(<LoginPage onLogin={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('닉네임'), ' ')
    await userEvent.click(screen.getByRole('button', { name: '새 사용자로 시작' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_REQUEST')
  })
})
