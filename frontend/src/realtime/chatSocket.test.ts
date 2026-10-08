import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RECONNECT_DELAY_MS, createChatSocket, socketUrl } from './chatSocket'
import type { ServerFrame } from './chatSocket'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  url: string
  readyState = 0
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }
  send(data: string) { this.sent.push(data) }
  close() { this.readyState = 3; this.onclose?.({ code: 1000 }) }
  accept() { this.readyState = 1; this.onopen?.() }
  deliver(data: unknown) { this.onmessage?.({ data: typeof data === 'string' ? data : JSON.stringify(data) }) }
  drop(code: number) { this.readyState = 3; this.onclose?.({ code }) }
}

const open = (url: string) => new FakeWebSocket(url) as unknown as WebSocket
const URL_7 = 'ws://test/ws?userId=7'

function latest(): FakeWebSocket {
  const ws = FakeWebSocket.instances.at(-1)
  if (!ws) throw new Error('연결이 없다')
  return ws
}

const message: ServerFrame = { type: 'message', message: { id: 1, roomId: 1, senderId: 2, content: '안녕', createdAt: '2026-10-08T00:00:00Z' } }

describe('chatSocket', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  it('같은 origin의 /ws에 userId 쿼리로 연결한다', () => {
    expect(socketUrl(7, { protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws?userId=7')
    expect(socketUrl(7, { protocol: 'https:', host: 'chat.example' })).toBe('wss://chat.example/ws?userId=7')
  })

  it('start 전에는 연결하지 않고, 열리기 전 send는 false', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    expect(FakeWebSocket.instances).toHaveLength(0)
    socket.start()
    socket.start()
    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(latest().url).toBe(URL_7)
    expect(socket.stats().state).toBe('connecting')
    expect(socket.send(1, '안녕')).toBe(false)
    expect(latest().sent).toHaveLength(0)
  })

  it('send는 type·roomId·content JSON 한 프레임으로 보낸다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    socket.start()
    latest().accept()
    expect(socket.send(3, '안녕\n😀')).toBe(true)
    expect(JSON.parse(latest().sent[0])).toEqual({ type: 'send', roomId: 3, content: '안녕\n😀' })
    expect(socket.stats()).toMatchObject({ state: 'open', sent: 1 })
  })

  it('받은 프레임을 구독자에게 넘기고, 구독을 해제하면 넘기지 않는다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    const listener = vi.fn()
    const unsubscribe = socket.subscribe(listener)
    socket.start()
    latest().accept()
    latest().deliver(message)
    expect(listener).toHaveBeenCalledWith(message)
    unsubscribe()
    latest().deliver(message)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(socket.stats().received).toBe(2)
  })

  it('JSON이 아닌 프레임은 세기만 하고 넘기지 않는다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    const listener = vi.fn()
    socket.subscribe(listener)
    socket.start()
    latest().accept()
    latest().deliver('{')
    expect(listener).not.toHaveBeenCalled()
    expect(socket.stats().received).toBe(1)
  })

  it('끊기면 정확히 1초 뒤 다시 연결하고 횟수와 종료 코드를 남긴다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    socket.start()
    latest().accept()
    latest().drop(1006)
    expect(socket.stats()).toMatchObject({ state: 'closed', lastCloseCode: 1006, reconnects: 0 })

    vi.advanceTimersByTime(RECONNECT_DELAY_MS - 1)
    expect(FakeWebSocket.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(socket.stats()).toMatchObject({ state: 'connecting', reconnects: 1 })
  })

  it('stop하면 닫고 다시 연결하지 않으며, 다시 start할 수 있다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    socket.start()
    latest().accept()
    socket.stop()
    expect(latest().readyState).toBe(3)
    expect(socket.stats().state).toBe('closed')
    vi.advanceTimersByTime(RECONNECT_DELAY_MS * 5)
    expect(FakeWebSocket.instances).toHaveLength(1)

    socket.start()
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('watch는 상태가 바뀔 때마다 알리고 stats는 바뀔 때만 새 객체다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    const watcher = vi.fn()
    socket.watch(watcher)
    const before = socket.stats()
    expect(socket.stats()).toBe(before)
    socket.start()
    latest().accept()
    expect(watcher).toHaveBeenCalled()
    expect(socket.stats()).not.toBe(before)
  })
})
