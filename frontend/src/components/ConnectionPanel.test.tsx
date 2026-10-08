import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ConnectionPanel } from './ConnectionPanel'

describe('ConnectionPanel', () => {
  it('연결 상태, 재연결 횟수, 프레임 수, 마지막 종료 코드를 보여 준다', () => {
    render(<ConnectionPanel stats={{ state: 'open', reconnects: 2, received: 7, sent: 3, lastCloseCode: 1006 }} />)
    const panel = within(screen.getByRole('complementary', { name: '연결 상태' }))
    expect(panel.getByText('연결됨')).toBeInTheDocument()
    expect(panel.getByText('2')).toBeInTheDocument()
    expect(panel.getByText('7')).toBeInTheDocument()
    expect(panel.getByText('3')).toBeInTheDocument()
    expect(panel.getByText('1006')).toBeInTheDocument()
  })

  it('종료 코드가 없으면 -', () => {
    render(<ConnectionPanel stats={{ state: 'connecting', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }} />)
    expect(screen.getByText('연결 중')).toBeInTheDocument()
    expect(screen.getByText('-')).toBeInTheDocument()
  })
})
