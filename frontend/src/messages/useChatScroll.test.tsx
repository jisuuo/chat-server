import { fireEvent, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { Message } from '../api/types'
import { useChatScroll } from './useChatScroll'

const msg = (id: number, senderId = 2): Message => ({ id, roomId: 1, senderId, content: `m${id}`, createdAt: '2026-10-07T00:00:00Z' })
const ids = (from: number, to: number, senderId = 2) => Array.from({ length: to - from + 1 }, (_, i) => msg(from + i, senderId))

function Harness({ messages }: { messages: Message[] }) {
  const ref = useRef<HTMLOListElement>(null)
  const { unseen, onScroll, scrollToBottom } = useChatScroll(ref, messages, 1)
  return (
    <>
      <ol data-testid="list" ref={ref} onScroll={onScroll}>{messages.map((m) => <li className="msg" key={m.id}>{m.content}</li>)}</ol>
      <output>{unseen}</output>
      <button onClick={scrollToBottom}>아래로</button>
    </>
  )
}

const scrollTops = new WeakMap<Element, number>()
const descriptors = {
  scrollHeight: Object.getOwnPropertyDescriptor(Element.prototype, 'scrollHeight'),
  clientHeight: Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight'),
  scrollTop: Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop'),
  getBoundingClientRect: Object.getOwnPropertyDescriptor(Element.prototype, 'getBoundingClientRect'),
}

beforeEach(() => {
  Object.defineProperty(Element.prototype, 'scrollHeight', { configurable: true, get() { return this.childElementCount * 20 } })
  Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get() { return 100 } })
  Object.defineProperty(Element.prototype, 'scrollTop', {
    configurable: true,
    get() { return scrollTops.get(this) ?? 0 },
    set(value: number) { scrollTops.set(this, Math.max(0, Math.min(value, this.scrollHeight - 100))) },
  })
  Object.defineProperty(Element.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: function (this: Element) {
      const list = this.parentElement
      const messages = [...(list?.querySelectorAll('.msg') ?? [])]
      const top = messages.indexOf(this) * 20 - (list?.scrollTop ?? 0)
      return { top, bottom: top + 20, height: 20 } as DOMRect
    },
  })
})
afterEach(() => {
  for (const [key, descriptor] of Object.entries(descriptors)) {
    if (descriptor) Object.defineProperty(Element.prototype, key, descriptor)
  }
})

describe('useChatScroll', () => {
  it('처음 읽으면 맨 아래로 내린다', () => {
    render(<Harness messages={ids(1, 10)} />)
    expect(screen.getByTestId('list').scrollTop).toBe(100)
  })

  it('맨 아래에 있으면 새 메시지를 따라 내려간다', () => {
    const { rerender } = render(<Harness messages={ids(1, 10)} />)
    rerender(<Harness messages={ids(1, 12)} />)
    expect(screen.getByTestId('list').scrollTop).toBe(140)
    expect(screen.getByRole('status')).toHaveTextContent('0')
  })

  it('위로 올려 읽는 중이면 내려가지 않고 새 메시지 수를 센다. 버튼을 누르면 맨 아래로', () => {
    const { rerender } = render(<Harness messages={ids(1, 10)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={ids(1, 12)} />)
    expect(list.scrollTop).toBe(0)
    expect(screen.getByRole('status')).toHaveTextContent('2')

    fireEvent.click(screen.getByRole('button', { name: '아래로' }))
    expect(list.scrollTop).toBe(140)
    expect(screen.getByRole('status')).toHaveTextContent('0')
  })

  it('내가 보낸 메시지는 위에 있어도 맨 아래로 간다', () => {
    const { rerender } = render(<Harness messages={ids(1, 10)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={[...ids(1, 10), msg(11, 1)]} />)
    expect(list.scrollTop).toBe(120)
  })

  it('이전 메시지를 위에 붙이면 늘어난 높이만큼 내려서 보던 위치를 지킨다', () => {
    const { rerender } = render(<Harness messages={ids(11, 20)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={ids(6, 20)} />)
    expect(list.scrollTop).toBe(100) // 5개 × 20px
  })

  it('이전 메시지와 새 메시지가 함께 오면 위에 붙은 높이만 보정한다', () => {
    const { rerender } = render(<Harness messages={ids(11, 20)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={ids(6, 21)} />)
    expect(list.scrollTop).toBe(100)
    expect(screen.getByRole('status')).toHaveTextContent('1')
  })

  it('맨 아래에서 이전 메시지와 새 메시지가 함께 오면 새 메시지를 따라간다', () => {
    const { rerender } = render(<Harness messages={ids(11, 20)} />)
    const list = screen.getByTestId('list')
    rerender(<Harness messages={ids(6, 21)} />)
    expect(list.scrollTop).toBe(220)
  })
})
