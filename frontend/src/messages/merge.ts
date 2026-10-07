import type { Message } from '../api/types'

// 폴링 응답과 내가 보낸 메시지가 겹쳐 오므로 id로 합친다
export function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  const byId = new Map(current.map((message) => [message.id, message]))
  for (const message of incoming) byId.set(message.id, message)
  return [...byId.values()].sort((a, b) => a.id - b.id)
}

export function lastId(messages: Message[]): number {
  return messages.length === 0 ? 0 : messages[messages.length - 1].id
}
