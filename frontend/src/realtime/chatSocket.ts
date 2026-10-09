import type { Message } from '../api/types'
import { reconnectDelay } from './reconnectDelay'

// WebSocket.OPEN. 테스트의 가짜 WebSocket에는 정적 상수가 없어서 값을 둔다
const OPEN = 1

export type ServerFrame =
  | { type: 'message'; message: Message }
  | { type: 'error'; roomId: number | null; code: string; message: string }
export type ConnectionState = 'connecting' | 'open' | 'closed'
export type SocketStats = { state: ConnectionState; reconnects: number; received: number; sent: number; lastCloseCode: number | null }
export type ChatSocket = {
  start: () => void
  stop: () => void
  send: (roomId: number, content: string) => boolean
  subscribe: (listener: (frame: ServerFrame) => void) => () => void
  watch: (listener: () => void) => () => void
  stats: () => SocketStats
}
type Options = { url?: string; open?: (url: string) => WebSocket; random?: () => number }

// ADR-130: 브라우저 WebSocket은 헤더를 붙일 수 없어 쿼리로 보낸다. ADR-028: 같은 origin
export function socketUrl(userId: number, location: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/ws?userId=${userId}`
}

export function createChatSocket(userId: number, options: Options = {}): ChatSocket {
  const url = options.url ?? socketUrl(userId)
  const open = options.open ?? ((target: string) => new WebSocket(target))
  const random = options.random ?? Math.random
  const listeners = new Set<(frame: ServerFrame) => void>()
  const watchers = new Set<() => void>()
  let stats: SocketStats = { state: 'closed', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }
  let current: WebSocket | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let attempt = 0

  function update(next: Partial<SocketStats>) {
    // useSyncExternalStore가 바뀐 것을 알도록 새 객체로 바꾼다
    stats = { ...stats, ...next }
    watchers.forEach((watcher) => watcher())
  }

  function connect() {
    const ws = open(url)
    current = ws
    update({ state: 'connecting' })
    // 닫은 뒤 늦게 오는 이전 연결의 이벤트가 새 연결의 상태를 바꾸지 않게 한다
    ws.onopen = () => {
      if (ws === current) {
        attempt = 0
        update({ state: 'open' })
      }
    }
    ws.onmessage = (event: MessageEvent) => {
      if (ws !== current) return
      update({ received: stats.received + 1 })
      let frame: ServerFrame
      try {
        frame = JSON.parse(String(event.data)) as ServerFrame
      } catch {
        return
      }
      listeners.forEach((listener) => listener(frame))
    }
    ws.onclose = (event: CloseEvent) => {
      if (ws !== current) return
      current = null
      update({ state: 'closed', lastCloseCode: event.code })
      if (!running) return
      // ADR-159: 연속 실패할수록 대기 범위를 넓히되 각 탭의 재접속 시각은 따로 고른다.
      // 방 화면은 다시 연결된 뒤 REST 조회로 놓친 메시지를 합친다(ADR-145).
      const delay = reconnectDelay(attempt++, random)
      timer = setTimeout(() => {
        update({ reconnects: stats.reconnects + 1 })
        connect()
      }, delay)
    }
  }

  return {
    start() {
      if (running) return
      running = true
      connect()
    },
    stop() {
      running = false
      attempt = 0
      clearTimeout(timer)
      const ws = current
      current = null
      ws?.close()
      update({ state: 'closed' })
    },
    send(roomId, content) {
      if (current?.readyState !== OPEN) return false
      current.send(JSON.stringify({ type: 'send', roomId, content }))
      update({ sent: stats.sent + 1 })
      return true
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    watch(watcher) {
      watchers.add(watcher)
      return () => { watchers.delete(watcher) }
    },
    stats: () => stats,
  }
}
