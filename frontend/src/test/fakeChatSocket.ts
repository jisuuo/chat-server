import type { ChatSocket, ServerFrame, SocketStats } from '../realtime/chatSocket'

// 훅·화면 테스트용. 연결은 열려 있다고 보고 보낸 프레임을 모은다
export function fakeChatSocket() {
  const listeners = new Set<(frame: ServerFrame) => void>()
  const watchers = new Set<() => void>()
  const sent: { roomId: number; content: string }[] = []
  let stats: SocketStats = { state: 'open', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }
  let connected = true
  function update(next: Partial<SocketStats>) {
    stats = { ...stats, ...next }
    watchers.forEach((watcher) => watcher())
  }
  const socket: ChatSocket = {
    start: () => {},
    stop: () => {},
    send: (roomId, content) => {
      if (!connected) return false
      sent.push({ roomId, content })
      return true
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    watch: (watcher) => {
      watchers.add(watcher)
      return () => { watchers.delete(watcher) }
    },
    stats: () => stats,
  }
  return {
    socket,
    sent,
    push: (frame: ServerFrame) => {
      update({ received: stats.received + 1 })
      listeners.forEach((listener) => listener(frame))
    },
    disconnect: () => { connected = false; update({ state: 'closed', lastCloseCode: 1006 }) },
    reconnect: () => { connected = true; update({ state: 'open', reconnects: stats.reconnects + 1 }) },
  }
}
