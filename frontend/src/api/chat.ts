import { apiFetch } from './client'
import type { Member, Message, MessagePage, Room, RoomPage, User } from './types'

export type MessageQuery = { after?: number; before?: number }

export function createUser(nickname: string) {
  return apiFetch<User>('/api/dev/users', { method: 'POST', body: { nickname } })
}

export function listRooms(userId: number, cursor?: string | null) {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
  return apiFetch<RoomPage>(`/api/rooms${query}`, { userId })
}

export function createRoom(userId: number, name: string) {
  return apiFetch<Room>('/api/rooms', { method: 'POST', userId, body: { name } })
}

export function joinRoom(userId: number, roomId: number) {
  return apiFetch<Member>(`/api/rooms/${roomId}/members`, { method: 'POST', userId })
}

export function leaveRoom(userId: number, roomId: number) {
  return apiFetch<null>(`/api/rooms/${roomId}/members/me`, { method: 'DELETE', userId })
}

export function sendMessage(userId: number, roomId: number, content: string) {
  return apiFetch<Message>(`/api/rooms/${roomId}/messages`, { method: 'POST', userId, body: { content } })
}

export function readMessages(userId: number, roomId: number, query: MessageQuery = {}) {
  const params = new URLSearchParams()
  if (query.after !== undefined) params.set('after', String(query.after))
  if (query.before !== undefined) params.set('before', String(query.before))
  const qs = params.toString()
  return apiFetch<MessagePage>(`/api/rooms/${roomId}/messages${qs ? `?${qs}` : ''}`, { userId })
}
