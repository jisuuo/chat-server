import type { ChatSocket, ServerFrame, SocketStats } from '../realtime/chatSocket'

// 훅·화면 테스트용. 연결은 열려 있다고 보고 보낸 프레임을 모은다
export function fakeChatSocket() {
  const listeners = new Set<(frame: ServerFrame) => void>()
  const sent: { roomId: number; content: string }[] = []
  const stats: SocketStats = { state: 'open', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }
  let connected = true
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
    watch: () => () => {},
    stats: () => stats,
  }
  return {
    socket,
    sent,
    push: (frame: ServerFrame) => listeners.forEach((listener) => listener(frame)),
    disconnect: () => { connected = false },
  }
}
