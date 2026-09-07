// Placeholder WebSocket client for chat.
//
// Will connect to ws://localhost:8080/ws/chat, send SUBSCRIBE/UNSUBSCRIBE
// messages for chat rooms, and dedupe incoming messages by `messageId`.
// Not implemented yet — this is a typed skeleton only.

const WS_URL = 'ws://localhost:8080/ws/chat'

export type ChatSocketMessageType = 'SUBSCRIBE' | 'UNSUBSCRIBE'

export interface ChatSocketMessage {
  type: ChatSocketMessageType
  roomId: number
}

export function connectChatSocket(): WebSocket {
  return new WebSocket(WS_URL)
}
