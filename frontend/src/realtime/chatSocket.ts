import type { Message } from '../api/types'

export const RECONNECT_DELAY_MS = 1000
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
type Options = { url?: string; open?: (url: string) => WebSocket }

// 계획 7 결정 D1: 브라우저 WebSocket은 헤더를 붙일 수 없어 쿼리로 보낸다. ADR-028: 같은 origin
export function socketUrl(userId: number, location: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/ws?userId=${userId}`
}

export function createChatSocket(userId: number, options: Options = {}): ChatSocket {
  const url = options.url ?? socketUrl(userId)
  const open = options.open ?? ((target: string) => new WebSocket(target))
  const listeners = new Set<(frame: ServerFrame) => void>()
  const watchers = new Set<() => void>()
  let stats: SocketStats = { state: 'closed', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }
  let current: WebSocket | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false

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
      if (ws === current) update({ state: 'open' })
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
      // 계획 7 세부 8: 고정 1초 뒤 다시 연결한다. 지수 대기·지터는 F17(Step 3)에서,
      // 끊긴 동안 온 메시지는 따라잡지 않는다(F6, 장애 선행)
      timer = setTimeout(() => {
        update({ reconnects: stats.reconnects + 1 })
        connect()
      }, RECONNECT_DELAY_MS)
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
