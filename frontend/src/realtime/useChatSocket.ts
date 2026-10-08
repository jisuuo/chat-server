import { createContext, createElement, useContext, useEffect, useState, useSyncExternalStore } from 'react'
import type { PropsWithChildren } from 'react'
import { createChatSocket } from './chatSocket'
import type { ChatSocket, SocketStats } from './chatSocket'

export const ChatSocketContext = createContext<ChatSocket | null>(null)

type Props = PropsWithChildren<{ userId: number; enabled: boolean }>

// 계획 7 세부 8: 방을 옮겨도 연결을 유지하도록 App 수준에 둔다. 방 화면은 ADR-082대로 key로 다시 만들어진다.
// 사용자가 바뀌면 App이 key로 다시 만든다
export function ChatSocketProvider({ userId, enabled, children }: Props) {
  const [socket] = useState(() => createChatSocket(userId))
  useEffect(() => {
    if (!enabled) return
    socket.start()
    return () => socket.stop()
  }, [socket, enabled])
  return createElement(ChatSocketContext.Provider, { value: enabled ? socket : null }, children)
}

export function useChatSocket(): ChatSocket | null {
  return useContext(ChatSocketContext)
}

const noSubscription = () => () => {}
const noStats = () => null

export function useSocketStats(socket: ChatSocket | null): SocketStats | null {
  return useSyncExternalStore(socket ? socket.watch : noSubscription, socket ? socket.stats : noStats)
}
