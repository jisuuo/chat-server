import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Composer } from './Composer'

describe('Composer', () => {
  it('Enter로 보내고 성공하면 비운다', async () => {
    const onSend = vi.fn().mockResolvedValue(true)
    render(<Composer onSend={onSend} />)
    await userEvent.type(screen.getByLabelText('메시지'), '안녕{Enter}')
    expect(onSend).toHaveBeenCalledWith('안녕')
    await waitFor(() => expect(screen.getByLabelText('메시지')).toHaveValue(''))
  })

  it('Shift+Enter는 줄바꿈, 실패하면 입력을 남긴다', async () => {
    const onSend = vi.fn().mockResolvedValue(false)
    render(<Composer onSend={onSend} />)
    await userEvent.type(screen.getByLabelText('메시지'), '첫 줄{Shift>}{Enter}{/Shift}둘째 줄')
    expect(onSend).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(onSend).toHaveBeenCalledWith('첫 줄\n둘째 줄')
    expect(screen.getByLabelText('메시지')).toHaveValue('첫 줄\n둘째 줄')
  })

  it('한글 조합 중 Enter는 보내지 않는다', () => {
    const onSend = vi.fn().mockResolvedValue(true)
    render(<Composer onSend={onSend} />)
    const box = screen.getByLabelText('메시지')
    fireEvent.change(box, { target: { value: '안녕' } })
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('빈 입력이면 보내기 버튼을 끈다. 전송 중에는 끄지 않는다 (ADR-034, F33)', async () => {
    let finish!: (ok: boolean) => void
    const onSend = vi.fn().mockImplementation(() => new Promise<boolean>((resolve) => { finish = resolve }))
    render(<Composer onSend={onSend} />)
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()
    await userEvent.type(screen.getByLabelText('메시지'), '안녕')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(screen.getByRole('button', { name: '보내기' })).toBeEnabled()
    await act(async () => finish(true))
  })
})
