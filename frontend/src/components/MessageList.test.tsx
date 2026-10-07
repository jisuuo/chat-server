import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ChatItem } from '../messages/chatItems'
import { defaultSenderName } from '../users/senderName'
import { MessageList } from './MessageList'

const message = (id: number, senderId: number, mine: boolean, showSender: boolean): ChatItem => ({
  kind: 'message', key: `m-${id}`, mine, showSender, time: '오후 3:05',
  message: { id, roomId: 1, senderId, content: `내용${id}`, createdAt: '2026-10-07T06:05:00Z' },
})
const props = {
  senderName: defaultSenderName, listRef: createRef<HTMLOListElement>(), onScroll: vi.fn(),
  hasOlder: false, onLoadOlder: vi.fn(), unseen: 0, onJumpToBottom: vi.fn(),
}

describe('MessageList', () => {
  it('날짜 구분선, 남의 메시지 이름, 내 메시지 표시를 그린다', () => {
    const items: ChatItem[] = [{ kind: 'day', key: 'd', label: '2026년 10월 7일 수요일' }, message(1, 2, false, true), message(2, 1, true, false)]
    render(<MessageList {...props} items={items} />)
    const list = within(screen.getByRole('list', { name: '대화' }))
    expect(list.getByText('2026년 10월 7일 수요일')).toBeInTheDocument()
    expect(list.getByText('사용자 #2')).toBeInTheDocument()
    expect(list.queryByText('사용자 #1')).not.toBeInTheDocument()
    expect(list.getByText('내용2').closest('li')).toHaveClass('mine')
  })

  it('이전 메시지 버튼과 새 메시지 버튼', async () => {
    const onLoadOlder = vi.fn()
    const onJumpToBottom = vi.fn()
    render(<MessageList {...props} items={[]} hasOlder onLoadOlder={onLoadOlder} unseen={3} onJumpToBottom={onJumpToBottom} />)
    await userEvent.click(screen.getByRole('button', { name: '이전 메시지 더 보기' }))
    await userEvent.click(screen.getByRole('button', { name: '새 메시지 3개' }))
    expect(onLoadOlder).toHaveBeenCalled()
    expect(onJumpToBottom).toHaveBeenCalled()
  })
})
