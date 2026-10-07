// 백엔드 DTO와 같은 모양 (RoomResponse, MessageResponse 등). Instant는 ISO 문자열로 온다
export type ApiBody<T> = {
  success: boolean
  data: T | null
  error: { code: string; message: string } | null
}

export type User = { id: number; nickname: string }
export type Room = { id: number; name: string; createdBy: number; lastMessageId: number | null; createdAt: string }
export type RoomPage = { rooms: Room[]; hasMore: boolean; nextCursor: string | null }
export type Member = { roomId: number; userId: number }
export type Message = { id: number; roomId: number; senderId: number; content: string; createdAt: string }
export type MessagePage = { messages: Message[]; hasMore: boolean }
