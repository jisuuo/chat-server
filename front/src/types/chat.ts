export interface ChatRoom {
  roomId: number
  name: string
  lastMessage: string | null
  lastMessageAt: string | null
}

export interface Message {
  messageId: number
  senderId: string
  senderName: string
  content: string
  createdAt: string
}
