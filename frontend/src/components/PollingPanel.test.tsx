import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PollingPanel } from './PollingPanel'

const stats = { requests: 3, errors: 1, received: 7, last: { status: 200, requestId: 'r-9', durationMs: 12 }, lastError: null }

describe('PollingPanel', () => {
  it('커서, 요청 수, 마지막 응답과 요청 id를 보여 준다', () => {
    render(<PollingPanel stats={stats} cursor={10} intervalMs={2000} paused={false} onIntervalChange={vi.fn()} onTogglePause={vi.fn()} />)
    const panel = within(screen.getByRole('complementary', { name: '폴링 상태' }))
    expect(panel.getByText('10')).toBeInTheDocument()
    expect(panel.getByText('3')).toBeInTheDocument()
    expect(panel.getByText('200 · 12ms')).toBeInTheDocument()
    expect(panel.getByText('r-9')).toBeInTheDocument()
    expect(panel.queryByText('마지막 오류')).not.toBeInTheDocument()
  })

  it('마지막 오류가 있으면 보여 준다', () => {
    render(<PollingPanel stats={{ ...stats, lastError: '멤버가 아닙니다.' }} cursor={0} intervalMs={2000} paused={false} onIntervalChange={vi.fn()} onTogglePause={vi.fn()} />)
    expect(screen.getByText('멤버가 아닙니다.')).toBeInTheDocument()
  })

  it('주기 변경과 일시정지를 알린다', async () => {
    const onIntervalChange = vi.fn()
    const onTogglePause = vi.fn()
    render(<PollingPanel stats={stats} cursor={0} intervalMs={2000} paused={false} onIntervalChange={onIntervalChange} onTogglePause={onTogglePause} />)
    await userEvent.selectOptions(screen.getByLabelText('폴링 주기'), '500')
    expect(onIntervalChange).toHaveBeenCalledWith(500)
    await userEvent.click(screen.getByRole('button', { name: '일시정지' }))
    expect(onTogglePause).toHaveBeenCalled()
  })
})
